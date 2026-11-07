package pl.syntaxdevteam.authgatewayx.security.login

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import kotlin.test.*

class ConnectionTimeoutGateTest {
    private val ip = InetAddress.getByName("192.0.2.1")
    private var now = 0L
    private fun gate(window: Long = 0, capacity: Int = 10) = ConnectionTimeoutGate(
        3, Duration.ofSeconds(window), Duration.ofHours(24), capacity, NanoTimeSource { now },
    )

    @Test fun `zero window retains failures and ban expires without extension`() {
        val gate = gate()
        assertTrue(gate.admit(ip))
        assertEquals(2, gate.timeout(ip))
        now += Duration.ofDays(10).toNanos()
        assertEquals(1, gate.timeout(ip))
        assertEquals(0, gate.timeout(ip))
        assertFalse(gate.admit(ip))
        now += Duration.ofHours(23).toNanos()
        assertEquals(0, gate.timeout(ip))
        assertFalse(gate.admit(ip))
        now += Duration.ofHours(1).toNanos()
        assertTrue(gate.admit(ip))
        assertEquals(2, gate.timeout(ip))
    }

    @Test fun `finite window resets failures and addresses are independent`() {
        val gate = gate(60)
        assertEquals(2, gate.timeout(ip))
        now = Duration.ofSeconds(60).toNanos()
        assertEquals(2, gate.timeout(ip))
        assertEquals(2, gate.timeout(InetAddress.getByName("192.0.2.2")))
    }

    @Test fun `bounded state fails closed without evicting bans`() {
        val gate = gate(capacity = 1)
        repeat(3) { gate.timeout(ip) }
        assertFalse(gate.admit(InetAddress.getByName("192.0.2.2")))
        assertFalse(gate.admit(ip))
        now = Duration.ofHours(24).toNanos()
        assertTrue(gate.admit(InetAddress.getByName("192.0.2.2")))
    }

    @Test fun `concurrent failures atomically exhaust allowance`() {
        val gate = gate()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll(List(3) { java.util.concurrent.Callable { gate.timeout(ip) } })
            assertEquals(listOf(0, 1, 2), results.map { it.get()!! }.sorted())
            assertFalse(gate.admit(ip))
        } finally { pool.shutdownNow() }
    }

    @Test fun `invalid configuration rejected`() {
        assertFailsWith<IllegalArgumentException> { gate(-1) }
        assertFailsWith<IllegalArgumentException> { gate(capacity = 0) }
    }
}
