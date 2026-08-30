package pl.syntaxdevteam.authgatewayx.paper

import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeState
import pl.syntaxdevteam.authgatewayx.paper.listener.AuthenticationReadinessListener
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate
import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.flood.PreAuthCapacity
import java.time.Duration

class AuthGatewayXPaper : JavaPlugin() {
    private val readiness = RuntimeReadiness()
    private var runtime: RuntimeComponents? = null

    override fun onEnable() {
        server.pluginManager.registerEvents(AuthenticationReadinessListener(readiness), this)
        runtime = RuntimeComponents(
            scheduler = PaperPlatformScheduler(this),
            sessions = InMemorySessionRegistry(),
            floodGate = ConnectionFloodGate(
                perIpLimit = FloodLimit(8, 3, Duration.ofSeconds(1)),
                globalLimit = FloodLimit(400, 200, Duration.ofSeconds(1)),
                maxTrackedAddresses = 50_000,
            ),
            preAuthCapacity = PreAuthCapacity(500),
            expensiveWork = BoundedTaskExecutor(
                threadCount = maxOf(2, Runtime.getRuntime().availableProcessors() / 2),
                queueCapacity = 256,
                threadNamePrefix = "authgatewayx-work",
            ),
        )
        readiness.force(RuntimeState.DEGRADED)
        logger.warning("AuthGatewayX authentication adapters are not complete; login remains fail-closed")
    }

    override fun onDisable() {
        readiness.force(RuntimeState.STOPPING)
        runtime?.close()
        runtime = null
    }
}

private class RuntimeComponents(
    @Suppress("unused") val scheduler: PaperPlatformScheduler,
    val sessions: InMemorySessionRegistry,
    val floodGate: ConnectionFloodGate,
    @Suppress("unused") val preAuthCapacity: PreAuthCapacity,
    val expensiveWork: BoundedTaskExecutor,
) : AutoCloseable {
    override fun close() {
        sessions.clear()
        floodGate.clear()
        expensiveWork.close()
    }
}
