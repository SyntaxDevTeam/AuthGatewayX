package pl.syntaxdevteam.authgatewayx.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookup
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate
import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.core.logging.DebugLevel
import pl.syntaxdevteam.core.proxy.ProxySyntaxCore
import pl.syntaxdevteam.message.SyntaxMessages
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import pl.syntaxdevteam.authgatewayx.integrations.network.ProxycheckIpLookup
import pl.syntaxdevteam.authgatewayx.storage.jdbc.JdbcAccountStorage
import java.util.concurrent.atomic.AtomicBoolean

class AuthGatewayXVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private val ready = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var storageExecutor: BoundedTaskExecutor? = null
    private var storage: JdbcAccountStorage? = null
    private var network: ProxycheckIpLookup? = null
    private var staffProof: VelocityStaffProof? = null
    private var admin: VelocityRiskAdmin? = null
    private var executor: BoundedTaskExecutor? = null
    private var pending: PendingConnectionRegistry? = null
    private var floodGate: ConnectionFloodGate? = null

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        proxy.eventManager.register(this, ReadinessGuard(ready))
        try {
            val container = container()
            val configuration = VelocityConfiguration.load(dataDirectory, javaClass.classLoader)
            ProxySyntaxCore.initVelocity(proxy, container, logger, dataDirectory.toFile(), DebugLevel.OFF, "velocity")
            val handler = SyntaxMessages.configure(
                VelocityMessageResources(dataDirectory, javaClass.classLoader),
                object : pl.syntaxdevteam.message.PluginMetaProvider { override val name = "AuthGatewayX" },
                object : pl.syntaxdevteam.message.MessageLogger {
                    private fun plain(message: String) = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(message))
                    override fun success(message: String) { logger.info(plain(message)) }
                    override fun err(message: String) { logger.error(plain(message)) }
                },
            )
            val work = BoundedTaskExecutor(configuration.mojangThreads, configuration.mojangQueue, "authgatewayx-mojang")
            val connections = PendingConnectionRegistry(Duration.ofSeconds(configuration.pendingTtlSeconds), configuration.maximumPending)
            val flood = ConnectionFloodGate(
                FloodLimit(8, 3, Duration.ofSeconds(1)), FloodLimit(400, 200, Duration.ofSeconds(1)), 50_000,
            )
            executor = work
            pending = connections
            floodGate = flood
            val lookup = MojangProfileLookup(
                work, Duration.ofMillis(configuration.lookupTimeoutMillis),
                Duration.ofSeconds(configuration.positiveTtlSeconds), Duration.ofSeconds(configuration.negativeTtlSeconds),
                configuration.maximumCacheSize,
            )
            val risk = configuration.risk
            val proof = VelocityStaffProof(proxy, this, risk.proofSecret).also { staffProof = it }
            proxy.eventManager.register(this, proof)
            val ip = if (risk.networkEnabled && risk.networkAction != RiskAction.DISABLED) ProxycheckIpLookup.create(
                risk.apiKey, Duration.ofMillis(risk.networkTimeoutMillis), Duration.ofSeconds(risk.cacheTtlSeconds),
                Duration.ofSeconds(risk.failureTtlSeconds), risk.maximumCacheSize, risk.networkConcurrent, risk.requestsPerMinute,
            ).also { network = it } else null
            val database = if (risk.storageEnabled) {
                val dbWork = BoundedTaskExecutor(2, 64, "authgatewayx-proxy-storage").also { storageExecutor = it }
                dbWork.submit {
                    JdbcAccountStorage(risk.jdbcUrl, 2, dbWork, username = risk.databaseUsername, password = risk.databasePassword)
                        .also { storage = it }
                }.thenCompose { db -> db.verifyHistorySchema().thenApply<JdbcAccountStorage?> { db } }
            } else CompletableFuture.completedFuture<JdbcAccountStorage?>(null)
            database.whenComplete { db, failure ->
                synchronized(this) {
                if (stopping.get()) { storage?.close(); return@whenComplete }
                if (failure != null) {
                    logger.error("AuthGatewayX shared history is unavailable; proxy admission remains closed. {}", StorageStartupDiagnostic.describe(failure))
                    proxy.scheduler.buildTask(this, Runnable { closeResources() }).schedule()
                    return@whenComplete
                }
                try {
                    val commands = VelocityRiskAdmin(proxy, db, proof, ready::get,
                        { key -> handler.stringMessageToComponentNoPrefix("risk", key) }, risk.alertCooldownSeconds, risk.consoleAlerts, risk.showGeo, risk.alertsEnabled)
                    admin = commands
                    proxy.commandManager.register(proxy.commandManager.metaBuilder("authgatewayx").plugin(this).build(), commands)
                    proxy.eventManager.register(this, VelocityLoginListener(ready, flood, lookup, connections, VelocityLoginMessages(
                        handler.stringMessageToComponentNoPrefix("auth", "unavailable"),
                        handler.stringMessageToComponentNoPrefix("auth", "rate_limited"),
                        handler.stringMessageToComponentNoPrefix("auth", "invalid_username"),
                        handler.stringMessageToComponentNoPrefix("auth", "mojang_unavailable"),
                        handler.stringMessageToComponentNoPrefix("auth", "state_mismatch"),
                        handler.stringMessageToComponentNoPrefix("auth", "vpn_denied"),
                        handler.stringMessageToComponentNoPrefix("auth", "multi_denied"),
                    ), VelocityRiskChecks(db, ip, risk.multiAction, risk.networkAction, risk.failClosed),
                        risk.maximumConcurrent, commands::notify))
                    ready.set(true)
                    logger.info("AuthGatewayX Velocity admission is READY; shared history={}, IP checks={}", risk.storageEnabled, risk.networkEnabled)
                } catch (problem: Throwable) {
                    ready.set(false)
                    logger.error("AuthGatewayX proxy risk initialization failed", problem)
                    proxy.scheduler.buildTask(this, Runnable { closeResources() }).schedule()
                }
                }
            }

        } catch (failure: Throwable) {
            ready.set(false)
            logger.error("AuthGatewayX Velocity initialization failed; logins remain closed", failure)
            closeResources()
        }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        synchronized(this) {
            stopping.set(true)
            ready.set(false)
            proxy.commandManager.unregister("authgatewayx")
        }
        closeResources()
    }

    private fun closeResources() {
        staffProof?.close()
        admin?.clear()
        network?.close()
        storage?.close()
        storageExecutor?.close()
        pending?.close()
        floodGate?.clear()
        executor?.close()
    }

    private fun container(): PluginContainer = proxy.pluginManager.getPlugin("authgatewayx")
        .orElseThrow { IllegalStateException("AuthGatewayX PluginContainer is unavailable") }

    private class ReadinessGuard(private val ready: AtomicBoolean) {
        @Subscribe
        fun onPreLogin(event: PreLoginEvent) {
            if (!ready.get()) {
                event.result = PreLoginEvent.PreLoginComponentResult.denied(
                    net.kyori.adventure.text.Component.text("Authentication service is unavailable."),
                )
            }
        }
    }
}
