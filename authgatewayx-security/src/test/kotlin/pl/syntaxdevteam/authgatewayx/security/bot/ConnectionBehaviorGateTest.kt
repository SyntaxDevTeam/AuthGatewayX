package pl.syntaxdevteam.authgatewayx.security.bot

import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionBehaviorGateTest {
    @Test
    fun `connection and distinct username weights trigger quarantine at threshold`() {
        var time = 0L
        val gate = gate(NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.40")

        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(address, "Alice"))
        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(address, "alice"))
        assertEquals(ConnectionBehaviorDecision.DENY_SCORE_THRESHOLD, gate.evaluateConnection(address, "Bob"))
        assertEquals(ConnectionBehaviorDecision.DENY_QUARANTINED, gate.evaluateConnection(address, "Alice"))

        time += Duration.ofMinutes(2).toNanos()
        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(address, "Alice"))
    }

    @Test
    fun `authentication failures and pre-auth disconnects update admitted state`() {
        val gate = gate(NanoTimeSource { 0L })
        val address = InetAddress.getByName("192.0.2.41")

        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(address, "Alice"))
        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.record(address, ConnectionBehaviorSignal.AUTHENTICATION_FAILURE))
        assertEquals(
            ConnectionBehaviorDecision.DENY_SCORE_THRESHOLD,
            gate.record(address, ConnectionBehaviorSignal.PRE_AUTH_DISCONNECT),
        )
    }

    @Test
    fun `late callbacks do not allocate state`() {
        val gate = gate(NanoTimeSource { 0L })
        val address = InetAddress.getByName("192.0.2.42")

        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.record(address, ConnectionBehaviorSignal.AUTHENTICATION_FAILURE))
        assertEquals(0, gate.trackedAddressCount())
    }

    @Test
    fun `tracking capacity is fail-closed and expired state can be reclaimed`() {
        var time = 0L
        val gate = ConnectionBehaviorGate(
            ConnectionBehaviorPolicy(
                threshold = 100,
                connectionWeight = 1,
                distinctUsernameWeight = 1,
                authenticationFailureWeight = 1,
                preAuthDisconnectWeight = 1,
                observationWindow = Duration.ofSeconds(30),
                quarantineDuration = Duration.ofMinutes(1),
                maximumTrackedAddresses = 1,
            ),
            NanoTimeSource { time },
        )
        val first = InetAddress.getByName("192.0.2.43")
        val second = InetAddress.getByName("192.0.2.44")

        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(first, "Alice"))
        assertEquals(ConnectionBehaviorDecision.DENY_TRACKING_CAPACITY, gate.evaluateConnection(second, "Bob"))
        time += Duration.ofSeconds(31).toNanos()
        assertEquals(ConnectionBehaviorDecision.ALLOW, gate.evaluateConnection(second, "Bob"))
        assertEquals(1, gate.trackedAddressCount())
    }

    private fun gate(timeSource: NanoTimeSource) = ConnectionBehaviorGate(
        ConnectionBehaviorPolicy(
            threshold = 10,
            connectionWeight = 1,
            distinctUsernameWeight = 3,
            authenticationFailureWeight = 3,
            preAuthDisconnectWeight = 3,
            observationWindow = Duration.ofSeconds(30),
            quarantineDuration = Duration.ofMinutes(1),
            maximumTrackedAddresses = 10,
        ),
        timeSource,
    )
}
