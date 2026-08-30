package pl.syntaxdevteam.authgatewayx.paper

import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.authgatewayx.auth.login.LockoutPolicy
import pl.syntaxdevteam.authgatewayx.auth.login.LoginService
import pl.syntaxdevteam.authgatewayx.auth.registration.PasswordPolicy
import pl.syntaxdevteam.authgatewayx.auth.registration.RegistrationService
import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormCoordinator
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogController
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogRouter
import pl.syntaxdevteam.authgatewayx.paper.dialog.AuthenticationDialogText
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthEntryListener
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthIsolationListener
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthIsolationManager
import pl.syntaxdevteam.authgatewayx.paper.isolation.SessionPreAuthAccess
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeState
import pl.syntaxdevteam.authgatewayx.paper.listener.AuthenticationReadinessListener
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.login.LoginAttemptGate
import pl.syntaxdevteam.authgatewayx.security.password.Argon2Parameters
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.jdbc.SqliteAccountStorage
import pl.syntaxdevteam.core.SyntaxCore
import pl.syntaxdevteam.message.MessageHandler
import pl.syntaxdevteam.message.SyntaxMessages
import java.time.Duration

class AuthGatewayXPaper : JavaPlugin() {
    private val readiness = RuntimeReadiness()
    private var runtime: RuntimeComponents? = null

    override fun onEnable() {
        saveDefaultConfig()
        server.pluginManager.registerEvents(AuthenticationReadinessListener(readiness), this)
        if (server.onlineMode) {
            readiness.force(RuntimeState.FAILED)
            logger.severe("Paper standalone requires online-mode=false; authentication remains fail-closed")
            return
        }
        try {
            SyntaxCore.init(this, versionType = "paper")
            SyntaxMessages.initialize(this)
            startRuntime(SyntaxMessages.messages)
        } catch (failure: Throwable) {
            fail("AuthGatewayX bootstrap failed", failure)
        }
    }

    private fun startRuntime(messages: MessageHandler) {
        val scheduler = PaperPlatformScheduler(this)
        val sessions = InMemorySessionRegistry()
        val storageExecutor = BoundedTaskExecutor(positive("executors.storage-threads"), positive("executors.storage-queue"), "authgatewayx-storage")
        val passwordExecutor = BoundedTaskExecutor(positive("executors.password-threads"), positive("executors.password-queue"), "authgatewayx-password")
        val components = RuntimeComponents(sessions, storageExecutor, passwordExecutor)
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
                runCatching { installAuthentication(initialized.first, initialized.second, hasher, messages, scheduler, sessions, passwordExecutor) }
                    .onSuccess { readiness.force(RuntimeState.READY); logger.info("AuthGatewayX authentication runtime is READY") }
                    .onFailure { fail("Authentication adapters failed to install", it) }
            })
        }
    }

    private fun installAuthentication(storage: SqliteAccountStorage, dummyHash: String, hasher: Argon2PasswordHasher,
        messages: MessageHandler, scheduler: PaperPlatformScheduler, sessions: InMemorySessionRegistry,
        passwordExecutor: BoundedTaskExecutor) {
        val access = SessionPreAuthAccess(sessions)
        val isolation = PreAuthIsolationManager(this, scheduler, access, positive("authentication.timeout-seconds") * 20L,
            messages.stringMessageToComponentNoPrefix("auth", "timeout"))
        val login = LoginService(storage, hasher, passwordExecutor,
            LoginAttemptGate(FloodLimit(5, 1, Duration.ofSeconds(2)), 50_000), storage, dummyHash,
            LockoutPolicy(positive("authentication.lockout.attempts"), Duration.ofSeconds(positive("authentication.lockout.duration-seconds").toLong())))
        val registration = RegistrationService(storage, hasher, passwordExecutor,
            PasswordPolicy(positive("authentication.password.minimum-length"), positive("authentication.password.maximum-length")))
        val coordinator = AuthenticationFormCoordinator(login, registration, sessions, activationListener = isolation)
        val dialogs = AuthenticationDialogController(coordinator, scheduler, AuthenticationDialogText(
            messages.stringMessageToComponentNoPrefix("auth", "login_title"),
            messages.stringMessageToComponentNoPrefix("auth", "registration_title"),
            messages.stringMessageToComponentNoPrefix("auth", "password_label"),
            messages.stringMessageToComponentNoPrefix("auth", "repeat_password_label"),
            messages.stringMessageToComponentNoPrefix("auth", "submit_label"),
            messages.stringMessageToComponentNoPrefix("auth", "cancel_label")))
        val allowedTestNames = config.getStringList("testing.offline-username-allowlist").map(String::lowercase).toSet()
        val router = AuthenticationDialogRouter(
            storage, dialogs, access, scheduler,
            offlineTestAllowed = { it.lowercase() in allowedTestNames },
            identityUnavailableMessage = messages.stringMessageToComponentNoPrefix("auth", "identity_unavailable"),
        ) { logger.log(java.util.logging.Level.WARNING, "Cannot select authentication form", it) }
        server.pluginManager.registerEvents(dialogs, this)
        server.pluginManager.registerEvents(PreAuthIsolationListener(access, isolation, sessions), this)
        server.pluginManager.registerEvents(PreAuthEntryListener(sessions, isolation, onEntered = router::route), this)
    }

    private fun positive(path: String): Int = config.getInt(path).also { require(it > 0) { "$path must be positive" } }
    private fun fail(message: String, failure: Throwable) { readiness.force(RuntimeState.FAILED); logger.log(java.util.logging.Level.SEVERE, "$message; authentication remains closed", failure) }

    override fun onDisable() { readiness.force(RuntimeState.STOPPING); runtime?.close(); runtime = null }
}

private class RuntimeComponents(val sessions: InMemorySessionRegistry, val storageExecutor: BoundedTaskExecutor,
    val passwordExecutor: BoundedTaskExecutor) : AutoCloseable {
    @Volatile var storage: SqliteAccountStorage? = null
    override fun close() { sessions.clear(); storage?.close(); storageExecutor.close(); passwordExecutor.close() }
}
