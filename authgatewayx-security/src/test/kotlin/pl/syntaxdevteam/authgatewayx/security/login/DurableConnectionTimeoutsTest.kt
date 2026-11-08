package pl.syntaxdevteam.authgatewayx.security.login

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import kotlin.test.*

class DurableConnectionTimeoutsTest {
    private val ip = InetAddress.getByName("192.0.2.9")
    private var now = Duration.ofDays(20000).toNanos()
    private fun gate(banHours: Long = 24) = ConnectionTimeoutGate(
        3, Duration.ZERO, Duration.ofHours(banHours), 10, NanoTimeSource { now },
    )

    @Test fun `restart retains counter and original deadline after configuration change`() {
        var database = emptyList<ConnectionTimeoutGate.Entry>()
        fun store(gate: ConnectionTimeoutGate) = DurableConnectionTimeouts(gate) { entries ->
            database = entries
            CompletableFuture.completedFuture(Unit)
        }
        store(gate()).use { assertEquals(2, it.timeout(ip).get(5, TimeUnit.SECONDS)) }
        now += Duration.ofDays(7).toNanos()
        val second = gate().also { it.restore(database) }
        store(second).use {
            assertEquals(1, it.timeout(ip).get(5, TimeUnit.SECONDS))
            assertEquals(0, it.timeout(ip).get(5, TimeUnit.SECONDS))
        }
        now += Duration.ofHours(23).toNanos()
        val restored = gate(48).also { it.restore(database) }
        assertFalse(restored.admit(ip))
        now += Duration.ofHours(1).toNanos()
        assertTrue(restored.admit(ip))
    }

    @Test fun `feedback waits for commit while new connections already see ban`() {
        val gate = gate()
        repeat(2) { gate.timeout(ip) }
        val commit = CompletableFuture<Unit>()
        DurableConnectionTimeouts(gate) { commit }.use { store ->
            val feedback = store.timeout(ip)
            assertFalse(gate.admit(ip))
            assertFalse(feedback.isDone)
            commit.complete(Unit)
            assertEquals(0, feedback.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `failed commit fails future and closes admission`() {
        val gate = gate()
        DurableConnectionTimeouts(gate) { CompletableFuture.failedFuture(IllegalStateException("DB unavailable")) }.use { store ->
            assertFails { store.timeout(ip).get(5, TimeUnit.SECONDS) }
            assertFalse(gate.admit(ip))
            assertFails { store.timeout(ip).get(5, TimeUnit.SECONDS) }
        }
    }
}
