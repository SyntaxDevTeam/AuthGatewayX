package pl.syntaxdevteam.authgatewayx.paper

import io.papermc.paper.configuration.GlobalConfiguration
import net.kyori.adventure.text.Component
import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.authgatewayx.auth.login.LockoutPolicy
import pl.syntaxdevteam.authgatewayx.auth.login.LoginService
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationService
import pl.syntaxdevteam.authgatewayx.auth.registration.PasswordPolicy
import pl.syntaxdevteam.authgatewayx.auth.registration.RegistrationService
import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormCoordinator
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormResult
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookup
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogController
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogRouter
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogText
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthAdmission
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthEntryListener
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthIsolationListener
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthIsolationManager
import pl.syntaxdevteam.authgatewayx.paper.isolation.SessionPreAuthAccess
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeState
import pl.syntaxdevteam.authgatewayx.paper.listener.AuthenticationReadinessListener
import pl.syntaxdevteam.authgatewayx.paper.premium.PaperPremiumAuthenticationMode
import pl.syntaxdevteam.authgatewayx.paper.premium.PaperPremiumAuthenticationModeSelector
import pl.syntaxdevteam.authgatewayx.paper.premium.StandalonePremiumProtocolInterceptor
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorGate
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorPolicy
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorSignal
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstGate
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstPolicy
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate
import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.login.LoginAttemptGate
import pl.syntaxdevteam.authgatewayx.security.password.Argon2Parameters
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.security.registration.RegistrationAttemptGate
import pl.syntaxdevteam.authgatewayx.storage.jdbc.SqliteAccountStorage
import pl.syntaxdevteam.core.SyntaxCore
import pl.syntaxdevteam.message.MessageHandler
import pl.syntaxdevteam.message.SyntaxMessages
import java.time.Duration
import java.util.UUID

class AuthGatewayXPaper : JavaPlugin() {
    private val readiness = RuntimeReadiness()
    private var runtime: RuntimeComponents? = null

    override fun onEnable() {
        saveDefaultConfig()
        val floodGate = ConnectionFloodGate(
            FloodLimit(8, 3, Duration.ofSeconds(1)), FloodLimit(400, 200, Duration.ofSeconds(1)), 50_000,
        )
        val (usernameBurstGate, behaviorGate) = try {
            val maximumTrackedAddresses = positive("anti-bot.maximum-tracked-addresses")
            UsernameBurstGate(UsernameBurstPolicy(
                positive("anti-bot.username-burst.maximum-distinct-usernames"),
                positive("anti-bot.reconnect-loop.maximum-pre-auth-disconnects"),
                Duration.ofSeconds(positive("anti-bot.username-burst.window-seconds").toLong()),
                Duration.ofSeconds(positive("anti-bot.username-burst.quarantine-seconds").toLong()),
                maximumTrackedAddresses,
            )) to ConnectionBehaviorGate(ConnectionBehaviorPolicy(
                positive("anti-bot.behavior-score.threshold"),
                positive("anti-bot.behavior-score.connection-weight"),
                positive("anti-bot.behavior-score.distinct-username-weight"),
                positive("anti-bot.behavior-score.authentication-failure-weight"),
                positive("anti-bot.behavior-score.pre-auth-disconnect-weight"),
                Duration.ofSeconds(positive("anti-bot.behavior-score.window-seconds").toLong()),
                Duration.ofSeconds(positive("anti-bot.behavior-score.quarantine-seconds").toLong()),
                maximumTrackedAddresses,
            ))
        } catch (failure: Throwable) {
            fail("Anti-bot configuration is invalid", failure)
            server.pluginManager.registerEvents(AuthenticationReadinessListener(readiness, floodGate, null, null), this)
            return
        }
        server.pluginManager.registerEvents(AuthenticationReadinessListener(readiness, floodGate, usernameBurstGate, behaviorGate), this)
        if (server.onlineMode) {
            readiness.force(RuntimeState.FAILED)
            logger.severe("Paper standalone requires online-mode=false; authentication remains fail-closed")
            return
        }
        try {
            SyntaxCore.init(this, versionType = "paper")
            SyntaxMessages.initialize(this)
            startRuntime(SyntaxMessages.messages, floodGate, usernameBurstGate, behaviorGate)
        } catch (failure: Throwable) {
            fail("AuthGatewayX bootstrap failed", failure)
        }
    }

    private fun startRuntime(messages: MessageHandler, floodGate: ConnectionFloodGate,
        usernameBurstGate: UsernameBurstGate, behaviorGate: ConnectionBehaviorGate) {
        val scheduler = PaperPlatformScheduler(this)
        val sessions = InMemorySessionRegistry()
        val storageExecutor = BoundedTaskExecutor(positive("executors.storage-threads"), positive("executors.storage-queue"), "authgatewayx-storage")
        val passwordExecutor = BoundedTaskExecutor(positive("executors.password-threads"), positive("executors.password-queue"), "authgatewayx-password")
        val mojangExecutor = BoundedTaskExecutor(positive("executors.mojang-threads"), positive("executors.mojang-queue"), "authgatewayx-mojang")
        val components = RuntimeComponents(
            sessions, storageExecutor, passwordExecutor, mojangExecutor, floodGate, usernameBurstGate, behaviorGate,
        )
        runtime = components
        val hasher = Argon2PasswordHasher(Argon2Parameters(
            positive("authentication.password.argon2.iterations"), positive("authentication.password.argon2.memory-kib"),
            positive("authentication.password.argon2.parallelism"),
        ))
        val storageStage = storageExecutor.submit {
            dataFolder.mkdirs()
            SqliteAccountStorage(
                "jdbc:sqlite:${dataFolder.resolve(config.getString("storage.sqlite-file") ?: "authgatewayx.db").absolutePath}",
                positive("storage.pool-size"), storageExecutor,
            ).also { components.storage = it }
        }.thenCompose { storage -> storage.migrate().thenApply { storage } }
        val dummyHashStage = passwordExecutor.submit { hasher.hash("AuthGatewayX-dummy-password".toCharArray()) }
        storageStage.thenCombine(dummyHashStage) { storage, dummyHash -> storage to dummyHash }.whenComplete { initialized, failure ->
            scheduler.global(Runnable {
                if (!isEnabled || readiness.current() == RuntimeState.STOPPING) return@Runnable
                if (failure != null) return@Runnable fail("Storage migration or password subsystem failed", failure)
                runCatching {
                    installAuthentication(
                        initialized.first, initialized.second, hasher, messages, scheduler, sessions,
                        passwordExecutor, mojangExecutor, usernameBurstGate, behaviorGate,
                    )
                }
                    .onSuccess { readiness.force(RuntimeState.READY); logger.info("AuthGatewayX authentication runtime is READY") }
                    .onFailure { fail("Authentication adapters failed to install", it) }
            })
        }
    }

    private fun installAuthentication(storage: SqliteAccountStorage, dummyHash: String, hasher: Argon2PasswordHasher,
        messages: MessageHandler, scheduler: PaperPlatformScheduler, sessions: InMemorySessionRegistry,
        passwordExecutor: BoundedTaskExecutor, mojangExecutor: BoundedTaskExecutor,
        usernameBurstGate: UsernameBurstGate, behaviorGate: ConnectionBehaviorGate) {
        val access = SessionPreAuthAccess(sessions)
        val admission = PreAuthAdmission(positive("authentication.maximum-pre-auth-players"))
        val registrationAttemptGate = RegistrationAttemptGate(
            FloodLimit(
                positive("anti-bot.registration-attempts.capacity"),
                positive("anti-bot.registration-attempts.refill-tokens"),
                Duration.ofSeconds(positive("anti-bot.registration-attempts.refill-seconds").toLong()),
            ),
            positive("anti-bot.maximum-tracked-addresses"),
            Duration.ofSeconds(positive("anti-bot.registration-attempts.state-ttl-seconds").toLong()),
        )
        runtime?.registrationAttemptGate = registrationAttemptGate
        val isolation = PreAuthIsolationManager(this, scheduler, access, positive("authentication.timeout-seconds") * 20L,
            messages.stringMessageToComponentNoPrefix("auth", "timeout"))
        val offlineAuthenticationSuccess = messages.stringMessageToComponentNoPrefix("auth", "offline_authentication_success")
        val premiumAuthenticationSuccess = messages.stringMessageToComponentNoPrefix("auth", "premium_authentication_success")
        fun sendAuthenticationSuccess(playerId: UUID, message: Component) {
            scheduler.global(Runnable {
                server.getPlayer(playerId)?.let { player ->
                    scheduler.entity(player, Runnable {
                        if (player.isOnline && !access.isPreAuth(player.uniqueId)) player.sendMessage(message)
                    })
                }
            })
        }
        val login = LoginService(storage, hasher, passwordExecutor,
            LoginAttemptGate(FloodLimit(5, 1, Duration.ofSeconds(2)), 50_000), storage, dummyHash,
            LockoutPolicy(positive("authentication.lockout.attempts"), Duration.ofSeconds(positive("authentication.lockout.duration-seconds").toLong())))
        val registration = RegistrationService(storage, hasher, passwordExecutor,
            PasswordPolicy(positive("authentication.password.minimum-length"), positive("authentication.password.maximum-length")),
            attemptGate = registrationAttemptGate, auditSink = storage,
            maximumAccountsPerAddress = positive("anti-bot.registration-attempts.maximum-accounts-per-address"))
        val coordinator = AuthenticationFormCoordinator(login, registration, sessions, activationListener = { context ->
            admission.release(context.connectionId.value)
            isolation.activated(context)
            sendAuthenticationSuccess(context.connectionId.value, offlineAuthenticationSuccess)
        })
        val dialogs = AuthenticationDialogController(coordinator, scheduler, AuthenticationDialogText(
            messages.stringMessageToComponentNoPrefix("auth", "login_title"),
            messages.stringMessageToComponentNoPrefix("auth", "registration_title"),
            messages.stringMessageToComponentNoPrefix("auth", "login_prompt"),
            messages.stringMessageToComponentNoPrefix("auth", "registration_prompt"),
            messages.stringMessageToComponentNoPrefix("auth", "password_label"),
            messages.stringMessageToComponentNoPrefix("auth", "repeat_password_label"),
            messages.stringMessageToComponentNoPrefix("auth", "submit_label"),
            messages.stringMessageToComponentNoPrefix("auth", "cancel_label"),
            messages.stringMessageToComponentNoPrefix("auth", "password_mismatch"),
            messages.stringMessageToComponentNoPrefix("auth", "invalid_credentials"),
            messages.stringMessageToComponentNoPrefix("auth", "account_locked"),
            messages.stringMessageToComponentNoPrefix("auth", "rate_limited"),
            messages.stringMessageToComponentNoPrefix("auth", "account_exists"),
            messages.stringMessageToComponentNoPrefix("auth", "identity_conflict"),
            messages.stringMessageToComponentNoPrefix("auth", "internal_failure")),
            onOutcome = { context, result ->
                if (result != AuthenticationFormResult.AUTHENTICATED) {
                    behaviorGate.record(context.sourceAddress, ConnectionBehaviorSignal.AUTHENTICATION_FAILURE)
                }
            },
        )
        val premiumLookup = MojangProfileLookup(
            mojangExecutor,
            Duration.ofMillis(positive("premium.lookup.timeout-millis").toLong()),
            Duration.ofSeconds(positive("premium.lookup.positive-ttl-seconds").toLong()),
            Duration.ofSeconds(positive("premium.lookup.negative-ttl-seconds").toLong()),
            positive("premium.lookup.maximum-cache-size"),
        )
        when (PaperPremiumAuthenticationModeSelector.select(GlobalConfiguration.get().proxies.velocity.enabled)) {
            PaperPremiumAuthenticationMode.STANDALONE_PROTOCOL -> {
                val premiumProtocol = StandalonePremiumProtocolInterceptor(
                    net.minecraft.server.MinecraftServer.getServer(),
                    premiumLookup,
                    positive("premium.authentication.maximum-concurrent-handshakes"),
                    messages.stringMessageToComponentNoPrefix("auth", "mojang_unavailable"),
                    messages.stringMessageToComponentNoPrefix("auth", "premium_authentication_overloaded"),
                ) { logger.log(java.util.logging.Level.WARNING, "Standalone premium login classification failed", it) }
                premiumProtocol.install()
                runtime?.premiumProtocol = premiumProtocol
                logger.info("AuthGatewayX premium mode: standalone Paper protocol authentication")
            }
            PaperPremiumAuthenticationMode.VELOCITY_FORWARDED -> {
                logger.info("AuthGatewayX premium mode: Velocity modern forwarding; standalone Paper encryption interceptor disabled")
            }
        }
        val mojangAuthentication = VerifiedMojangAuthenticationService(storage, sessions, storage)
        val router = AuthenticationDialogRouter(
            storage, dialogs, access, scheduler,
            premiumLookup = premiumLookup,
            mojangAuthentication = mojangAuthentication,
            premiumAuthenticationRequiredMessage = messages.stringMessageToComponentNoPrefix("auth", "premium_authentication_required"),
            lookupUnavailableMessage = messages.stringMessageToComponentNoPrefix("auth", "mojang_unavailable"),
            identityConflictMessage = messages.stringMessageToComponentNoPrefix("auth", "identity_conflict"),
            internalFailureMessage = messages.stringMessageToComponentNoPrefix("auth", "internal_failure"),
            onMojangActivated = { player ->
                admission.release(player.uniqueId)
                isolation.release(player)
                sendAuthenticationSuccess(player.uniqueId, premiumAuthenticationSuccess)
            },
        ) { logger.log(java.util.logging.Level.WARNING, "Cannot select authentication form", it) }
        server.pluginManager.registerEvents(dialogs, this)
        server.pluginManager.registerEvents(
            PreAuthIsolationListener(access, isolation, sessions, admission, usernameBurstGate, behaviorGate), this,
        )
        server.pluginManager.registerEvents(PreAuthEntryListener(
            sessions, isolation, admission,
            messages.stringMessageToComponentNoPrefix("auth", "pre_auth_full"),
            onEntered = router::route,
        ), this)
    }

    private fun positive(path: String): Int = config.getInt(path).also { require(it > 0) { "$path must be positive" } }
    private fun fail(message: String, failure: Throwable) { readiness.force(RuntimeState.FAILED); logger.log(java.util.logging.Level.SEVERE, "$message; authentication remains closed", failure) }

    override fun onDisable() { readiness.force(RuntimeState.STOPPING); runtime?.close(); runtime = null }
}

private class RuntimeComponents(val sessions: InMemorySessionRegistry, val storageExecutor: BoundedTaskExecutor,
    val passwordExecutor: BoundedTaskExecutor, val mojangExecutor: BoundedTaskExecutor,
    val floodGate: ConnectionFloodGate, val usernameBurstGate: UsernameBurstGate,
    val behaviorGate: ConnectionBehaviorGate) : AutoCloseable {
    @Volatile var storage: SqliteAccountStorage? = null
    @Volatile var registrationAttemptGate: RegistrationAttemptGate? = null
    @Volatile var premiumProtocol: StandalonePremiumProtocolInterceptor? = null
    override fun close() { premiumProtocol?.close(); sessions.clear(); storage?.close(); storageExecutor.close(); passwordExecutor.close(); mojangExecutor.close(); floodGate.clear(); usernameBurstGate.clear(); behaviorGate.clear(); registrationAttemptGate?.clear() }
}
