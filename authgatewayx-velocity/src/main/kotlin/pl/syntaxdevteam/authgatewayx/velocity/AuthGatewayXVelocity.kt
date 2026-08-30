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
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class AuthGatewayXVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private val ready = AtomicBoolean(false)
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
            val messageConfig = dataDirectory.resolve("config.yml")
            if (Files.notExists(messageConfig)) Files.writeString(messageConfig, "language: PL\n")
            val handler = SyntaxMessages.initialize(container, dataDirectory, logger)
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
            proxy.eventManager.register(this, VelocityLoginListener(ready, flood, lookup, connections, VelocityLoginMessages(
                handler.stringMessageToComponentNoPrefix("auth", "unavailable"),
                handler.stringMessageToComponentNoPrefix("auth", "rate_limited"),
                handler.stringMessageToComponentNoPrefix("auth", "invalid_username"),
                handler.stringMessageToComponentNoPrefix("auth", "mojang_unavailable"),
                handler.stringMessageToComponentNoPrefix("auth", "state_mismatch"),
            )))
            ready.set(true)
            logger.info("AuthGatewayX Velocity authentication selector is READY")
        } catch (failure: Throwable) {
            ready.set(false)
            logger.error("AuthGatewayX Velocity initialization failed; logins remain closed", failure)
        }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        ready.set(false)
        pending?.clear()
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
