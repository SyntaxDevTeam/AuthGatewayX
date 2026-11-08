package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PreLoginEvent
import net.kyori.adventure.text.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Retains startup diagnostics so a connection attempt explains a failed startup in the console. */
internal class VelocityReadinessGuard(
    private val ready: AtomicBoolean,
    private val log: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) {
    enum class Phase { CONFIGURATION, SYNTAX_CORE, MESSAGES, EXECUTORS, STAFF_PROOF, IP_LOOKUP, DATABASE, LISTENERS, SHUTDOWN }
    @Volatile private var diagnostic = "STARTING: CONFIGURATION"
    private var nextLog = Instant.MIN

    fun starting(phase: Phase) { diagnostic = "STARTING: ${phase.name}" }
    fun failed(phase: Phase, detail: String) { diagnostic = "FAILED: ${phase.name}; $detail" }

    @Subscribe
    fun onPreLogin(event: PreLoginEvent) {
        if (ready.get() || !event.result.isAllowed) return
        event.result = PreLoginEvent.PreLoginComponentResult.denied(
            Component.text("Authentication service is unavailable. [AGX-STARTUP]"),
        )
        report()
    }

    @Synchronized private fun report() {
        val now = clock.instant()
        if (now.isBefore(nextLog)) return
        nextLog = now.plusSeconds(10)
        log("AuthGatewayX rejected login [AGX-STARTUP]: $diagnostic. Check the AuthGatewayX startup log and the proxy data-directory authgatewayx.yml.")
    }
}
