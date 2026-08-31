package pl.syntaxdevteam.authgatewayx.security.registration

import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistrationAttemptGateTest {
    @Test
    fun `attempts are limited per address and refill over time`() {
        var time = 0L
        val gate = gate(NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.20")

        assertEquals(RegistrationAttemptDecision.ALLOW, gate.evaluate(address))
        assertEquals(RegistrationAttemptDecision.ALLOW, gate.evaluate(address))
        assertEquals(RegistrationAttemptDecision.RATE_LIMITED, gate.evaluate(address))
        time += Duration.ofMinutes(1).toNanos()
        assertEquals(RegistrationAttemptDecision.ALLOW, gate.evaluate(address))
    }

    @Test
    fun `expired state releases bounded tracking capacity`() {
        var time = 0L
        val gate = RegistrationAttemptGate(
            FloodLimit(2, 1, Duration.ofMinutes(1)), 1, Duration.ofMinutes(2), NanoTimeSource { time },
        )

        assertEquals(RegistrationAttemptDecision.ALLOW, gate.evaluate(InetAddress.getByName("192.0.2.21")))
        assertEquals(
            RegistrationAttemptDecision.TRACKING_CAPACITY_EXCEEDED,
            gate.evaluate(InetAddress.getByName("192.0.2.22")),
        )
        time += Duration.ofMinutes(2).toNanos()
        assertEquals(RegistrationAttemptDecision.ALLOW, gate.evaluate(InetAddress.getByName("192.0.2.22")))
        assertEquals(1, gate.trackedAddressCount())
    }

    private fun gate(timeSource: NanoTimeSource) = RegistrationAttemptGate(
        FloodLimit(2, 1, Duration.ofMinutes(1)), 10, Duration.ofMinutes(2), timeSource,
    )
}
