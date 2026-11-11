package pl.syntaxdevteam.logincorex.paper.security

import pl.syntaxdevteam.logincorex.security.bot.ConnectionBehaviorGate
import pl.syntaxdevteam.logincorex.security.bot.ConnectionBehaviorPolicy
import pl.syntaxdevteam.logincorex.security.bot.UsernameBurstGate
import pl.syntaxdevteam.logincorex.security.bot.UsernameBurstPolicy
import pl.syntaxdevteam.logincorex.security.flood.ConnectionFloodGate
import pl.syntaxdevteam.logincorex.security.flood.FloodLimit
import pl.syntaxdevteam.logincorex.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperLoginCheapGuardTest {
    @Test
    fun `standalone protocol ownership prevents AsyncPlayerPreLogin from charging guards twice`() {
        val guard = guard(perIpCapacity = 2)
        val address = InetAddress.getByName("192.0.2.60")

        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Alice").decision)
        guard.activateStandaloneProtocolOwnership()
        assertTrue(guard.standaloneProtocolOwnsStatefulChecks())

        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluatePreLogin(address, "Alice").decision)
        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Bob").decision)
        assertEquals(PaperLoginGuardDecision.DENY_FLOOD, guard.evaluateProtocol(address, "Carol").decision)
    }

    @Test
    fun `pre-login remains owner when standalone protocol guard is inactive`() {
        val guard = guard(perIpCapacity = 1)
        val address = InetAddress.getByName("192.0.2.61")

        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluatePreLogin(address, "Alice").decision)
        assertEquals(PaperLoginGuardDecision.DENY_FLOOD, guard.evaluatePreLogin(address, "Alice").decision)
    }

    @Test
    fun `protocol path applies behavioral scoring before caller may continue to lookup`() {
        val address = InetAddress.getByName("192.0.2.62")
        val behavior = ConnectionBehaviorGate(
            ConnectionBehaviorPolicy(
                threshold = 4,
                connectionWeight = 1,
                distinctUsernameWeight = 1,
                authenticationFailureWeight = 1,
                preAuthDisconnectWeight = 1,
                observationWindow = Duration.ofMinutes(1),
                quarantineDuration = Duration.ofMinutes(1),
                maximumTrackedAddresses = 10,
            ),
            NanoTimeSource { 0L },
        )
        val guard = guard(perIpCapacity = 10, behaviorGate = behavior)

        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Alice").decision)
        assertEquals(PaperLoginGuardDecision.DENY_BEHAVIOR, guard.evaluateProtocol(address, "Bob").decision)
    }

    @Test
    fun `protocol path applies username burst before caller may continue to lookup`() {
        val address = InetAddress.getByName("192.0.2.63")
        val usernameBurst = UsernameBurstGate(
            UsernameBurstPolicy(1, 5, Duration.ofMinutes(1), Duration.ofMinutes(1), 10),
            NanoTimeSource { 0L },
        )
        val guard = guard(perIpCapacity = 10, usernameBurstGate = usernameBurst)

        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Alice").decision)
        assertEquals(PaperLoginGuardDecision.DENY_USERNAME_BURST, guard.evaluateProtocol(address, "Bob").decision)
    }

    @Test
    fun `pre-login still validates username while protocol owns stateful checks`() {
        val guard = guard(perIpCapacity = 1)
        val address = InetAddress.getByName("192.0.2.64")
        guard.activateStandaloneProtocolOwnership()

        assertEquals(
            PaperLoginGuardDecision.DENY_INVALID_USERNAME,
            guard.evaluatePreLogin(address, "invalid username").decision,
        )
        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Alice").decision)
    }

    @Test
    fun `timeout ban blocks rotated usernames and late pre-login after protocol admission`() {
        val timeoutGate = pl.syntaxdevteam.logincorex.security.login.ConnectionTimeoutGate(
            3, Duration.ZERO, Duration.ofHours(24), 100,
        )
        val guard = PaperLoginCheapGuard(
            ConnectionFloodGate(FloodLimit(100, 1, Duration.ofDays(1)),
                FloodLimit(1000, 1, Duration.ofDays(1)), 100),
            null, null, timeoutGate,
        )
        val address = InetAddress.getByName("192.0.2.70")
        assertEquals(PaperLoginGuardDecision.ALLOW, guard.evaluateProtocol(address, "Alice").decision)
        guard.activateStandaloneProtocolOwnership()
        repeat(3) { timeoutGate.timeout(address) }
        assertEquals(PaperLoginGuardDecision.DENY_SUSPICIOUS, guard.evaluateProtocol(address, "Bob").decision)
        assertEquals(PaperLoginGuardDecision.DENY_SUSPICIOUS, guard.evaluatePreLogin(address, "Alice").decision)
    }

    private fun guard(
        perIpCapacity: Int,
        usernameBurstGate: UsernameBurstGate? = UsernameBurstGate(
            UsernameBurstPolicy(100, 100, Duration.ofMinutes(1), Duration.ofMinutes(1), 100),
            NanoTimeSource { 0L },
        ),
        behaviorGate: ConnectionBehaviorGate? = ConnectionBehaviorGate(
            ConnectionBehaviorPolicy(
                threshold = 1000,
                connectionWeight = 1,
                distinctUsernameWeight = 1,
                authenticationFailureWeight = 1,
                preAuthDisconnectWeight = 1,
                observationWindow = Duration.ofMinutes(1),
                quarantineDuration = Duration.ofMinutes(1),
                maximumTrackedAddresses = 100,
            ),
            NanoTimeSource { 0L },
        ),
    ): PaperLoginCheapGuard {
        val timeSource = NanoTimeSource { 0L }
        return PaperLoginCheapGuard(
            ConnectionFloodGate(
                FloodLimit(perIpCapacity, 1, Duration.ofDays(1)),
                FloodLimit(1000, 1, Duration.ofDays(1)),
                100,
                timeSource,
            ),
            usernameBurstGate,
            behaviorGate,
        )
    }
}
