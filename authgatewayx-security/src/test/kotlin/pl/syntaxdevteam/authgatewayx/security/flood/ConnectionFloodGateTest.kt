package pl.syntaxdevteam.authgatewayx.security.flood

import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectionFloodGateTest {
    @Test
    fun `idle addresses expire only after complete token recovery`() {
        var time = 0L
        val gate = ConnectionFloodGate(FloodLimit(2, 1, Duration.ofMinutes(1)),
            FloodLimit(100, 100, Duration.ofSeconds(1)), 1, NanoTimeSource { time })
        val first = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val second = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(first))
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(first))
        time = Duration.ofMinutes(1).toNanos()
        assertEquals(ConnectionDecision.DENY_TRACKING_CAPACITY, gate.evaluate(second))
        time = Duration.ofMinutes(2).toNanos()
        assertEquals(ConnectionDecision.ALLOW, gate.evaluate(second))
        assertEquals(1, gate.trackedAddressCount())
        assertEquals(ConnectionDecision.DENY_TRACKING_CAPACITY, gate.evaluate(first))
    }

    @Test
    fun `active address cannot be evicted to reset rate limit`() {
        var time = 0L
        val gate = ConnectionFloodGate(FloodLimit(1, 1, Duration.ofMinutes(1)),
            FloodLimit(100, 100, Duration.ofSeconds(1)), 1, NanoTimeSource { time })
        val first = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val second = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        gate.evaluate(first)
        time = Duration.ofSeconds(59).toNanos()
        assertEquals(ConnectionDecision.DENY_PER_IP, gate.evaluate(first))
        time = Duration.ofSeconds(60).toNanos()
        assertEquals(ConnectionDecision.DENY_TRACKING_CAPACITY, gate.evaluate(second))
    }
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
