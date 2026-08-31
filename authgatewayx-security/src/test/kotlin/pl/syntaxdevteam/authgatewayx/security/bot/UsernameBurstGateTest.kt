package pl.syntaxdevteam.authgatewayx.security.bot

import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class UsernameBurstGateTest {
    @Test
    fun `repeated username is cheap while distinct username burst triggers quarantine`() {
        var time = 0L
        val gate = gate(timeSource = NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.10")

        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "alice"))
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Bob"))
        assertEquals(UsernameBurstDecision.DENY_USERNAME_BURST, gate.evaluate(address, "Carol"))
        assertEquals(UsernameBurstDecision.DENY_QUARANTINED, gate.evaluate(address, "Alice"))

        time += Duration.ofMinutes(2).toNanos()
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
    }

    @Test
    fun `observation window resets distinct username history`() {
        var time = 0L
        val gate = gate(timeSource = NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.11")

        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Bob"))
        time += Duration.ofSeconds(31).toNanos()
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Carol"))
    }

    @Test
    fun `tracking capacity denies new addresses and later evicts expired state`() {
        var time = 0L
        val gate = UsernameBurstGate(
            UsernameBurstPolicy(2, 3, Duration.ofSeconds(30), Duration.ofMinutes(1), 1),
            NanoTimeSource { time },
        )
        val first = InetAddress.getByName("192.0.2.12")
        val second = InetAddress.getByName("192.0.2.13")

        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(first, "Alice"))
        assertEquals(UsernameBurstDecision.DENY_TRACKING_CAPACITY, gate.evaluate(second, "Bob"))
        time += Duration.ofSeconds(31).toNanos()
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(second, "Bob"))
        assertEquals(1, gate.trackedAddressCount())
    }

    @Test
    fun `repeated pre-auth disconnects quarantine the next reconnect`() {
        var time = 0L
        val gate = gate(timeSource = NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.14")

        repeat(3) {
            assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
            assertEquals(UsernameBurstDecision.ALLOW, gate.recordPreAuthDisconnect(address))
            time += Duration.ofSeconds(2).toNanos()
        }
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
        assertEquals(UsernameBurstDecision.DENY_RECONNECT_LOOP, gate.recordPreAuthDisconnect(address))
        assertEquals(UsernameBurstDecision.DENY_QUARANTINED, gate.evaluate(address, "Alice"))
    }

    @Test
    fun `disconnect history expires outside observation window`() {
        var time = 0L
        val gate = gate(timeSource = NanoTimeSource { time })
        val address = InetAddress.getByName("192.0.2.15")

        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
        assertEquals(UsernameBurstDecision.ALLOW, gate.recordPreAuthDisconnect(address))
        time += Duration.ofSeconds(31).toNanos()
        assertEquals(UsernameBurstDecision.ALLOW, gate.evaluate(address, "Alice"))
        assertEquals(UsernameBurstDecision.ALLOW, gate.recordPreAuthDisconnect(address))
    }

    @Test
    fun `disconnect without admitted connection does not allocate tracked state`() {
        val gate = gate(timeSource = NanoTimeSource { 0L })

        assertEquals(
            UsernameBurstDecision.ALLOW,
            gate.recordPreAuthDisconnect(InetAddress.getByName("192.0.2.16")),
        )
        assertEquals(0, gate.trackedAddressCount())
    }

    private fun gate(timeSource: NanoTimeSource) = UsernameBurstGate(
        UsernameBurstPolicy(2, 3, Duration.ofSeconds(30), Duration.ofMinutes(1), 10),
        timeSource,
    )
}
