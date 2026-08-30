package pl.syntaxdevteam.authgatewayx.security.login

import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginAttemptGateTest {
    @Test
    fun `limits attempts independently per address`() {
        val gate = LoginAttemptGate(FloodLimit(1, 1, Duration.ofMinutes(1)), 10)
        val first = InetAddress.getByName("192.0.2.1")
        val second = InetAddress.getByName("192.0.2.2")

        assertEquals(LoginAttemptDecision.ALLOW, gate.evaluate(first))
        assertEquals(LoginAttemptDecision.RATE_LIMITED, gate.evaluate(first))
        assertEquals(LoginAttemptDecision.ALLOW, gate.evaluate(second))
    }
}
