package pl.syntaxdevteam.authgatewayx.security.flood

import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectionFloodGateTest {
    @Test
    fun `per-IP guard rejects before expensive work and refills`() {
        var time = 0L
        val gate = ConnectionFloodGate(
            FloodLimit(2, 1, Duration.ofSeconds(1)),
            FloodLimit(100, 100, Duration.ofSeconds(1)),
            10,
            NanoTimeSource { time },
        )
        val address = InetAddress.getByName("192.0.2.1")

        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(address))
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(address))
        assertEquals(ConnectionDecision.DENY_PER_IP, gate.evaluate(address))
        time += Duration.ofSeconds(1).toNanos()
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(address))
    }

    @Test
    fun `tracking cardinality is bounded`() {
        val gate = ConnectionFloodGate(
            FloodLimit(2, 1, Duration.ofSeconds(1)),
            FloodLimit(10, 1, Duration.ofSeconds(1)),
            1,
        )
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(InetAddress.getByName("192.0.2.1")))
        assertEquals(ConnectionDecision.DENY_TRACKING_CAPACITY, gate.evaluate(InetAddress.getByName("192.0.2.2")))
        assertEquals(1, gate.trackedAddressCount())
    }

    @Test
    fun `pre-auth capacity lease releases exactly once`() {
        val capacity = PreAuthCapacity(1)
        val lease = assertNotNull(capacity.tryAcquire())
        assertNull(capacity.tryAcquire())
        lease.close()
        lease.close()
        assertEquals(0, capacity.activeCount())
        assertNotNull(capacity.tryAcquire())
    }
}
