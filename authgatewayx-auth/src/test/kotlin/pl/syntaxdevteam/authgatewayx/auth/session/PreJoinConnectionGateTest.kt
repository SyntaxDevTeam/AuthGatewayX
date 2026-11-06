package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.auth.session.PreJoinConnectionGate.Outcome
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreJoinConnectionGateTest {
    @Test
    fun `authenticated ticket releases one slot and cannot be replayed`() {
        PreJoinConnectionGate(1).use { gate ->
            val ticket = assertNotNull(gate.tryOpen(ConnectionId.random()))
            assertEquals(1, gate.pendingCount())
            assertTrue(gate.authenticated(ticket))
            assertEquals(Outcome.AUTHENTICATED, gate.await(ticket, Duration.ofSeconds(1)))
            assertFalse(gate.authenticated(ticket))
            assertFalse(gate.deny(ticket))
            assertEquals(0, gate.pendingCount())
            assertNotNull(gate.tryOpen(ConnectionId.random()))
        }
    }

    @Test
    fun `ticket is valid only while held and shutdown cannot authenticate it`() {
        val gate = PreJoinConnectionGate(2)
        val ticket = assertNotNull(gate.tryOpen(ConnectionId.random()))
        assertTrue(gate.isPending(ticket))
        gate.close()
        assertFalse(gate.isPending(ticket))
        assertEquals(Outcome.SHUTDOWN, gate.await(ticket, Duration.ofSeconds(1)))
        assertFalse(gate.authenticated(ticket))
        assertNull(gate.tryOpen(ConnectionId.random()))
    }

    @Test
    fun `expired previous ticket cannot affect reconnect with the same ID`() {
        PreJoinConnectionGate(1).use { gate ->
            val id = ConnectionId.random()
            val previous = assertNotNull(gate.tryOpen(id))
            assertEquals(Outcome.TIMED_OUT, gate.await(previous, Duration.ofNanos(1)))
            val newer = assertNotNull(gate.tryOpen(id))
            assertFalse(gate.isPending(previous))
            assertTrue(gate.isPending(newer))
            assertFalse(gate.authenticated(previous))
            assertTrue(gate.deny(newer))
            assertEquals(Outcome.DENIED, gate.await(newer, Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `capacity and duplicate connection IDs are rejected`() {
        PreJoinConnectionGate(1).use { gate ->
            val id = ConnectionId.random()
            val first = assertNotNull(gate.tryOpen(id))
            assertNull(gate.tryOpen(id))
            assertNull(gate.tryOpen(ConnectionId.random()))
            assertTrue(gate.deny(first))
            assertEquals(Outcome.DENIED, gate.await(first, Duration.ofSeconds(1)))
            assertNotNull(gate.tryOpen(ConnectionId.random()))
        }
    }

    @Test
    fun `timeout denies connection and cleans up capacity`() {
        PreJoinConnectionGate(1).use { gate ->
            val first = assertNotNull(gate.tryOpen(ConnectionId.random()))
            assertEquals(Outcome.TIMED_OUT, gate.await(first, Duration.ofNanos(1)))
            assertEquals(0, gate.pendingCount())
            assertFalse(gate.authenticated(first))
            assertNotNull(gate.tryOpen(ConnectionId.random()))
        }
    }

    @Test
    fun `disconnect unblocks waiter before timeout`() {
        PreJoinConnectionGate(1).use { gate ->
            val ticket = assertNotNull(gate.tryOpen(ConnectionId.random()))
            assertTrue(gate.disconnected(ticket))
            assertEquals(Outcome.DISCONNECTED, gate.await(ticket, Duration.ofSeconds(5)))
            assertFalse(gate.authenticated(ticket))
            assertEquals(0, gate.pendingCount())
        }
    }

    @Test
    fun `stale ticket cannot complete a new connection for the same UUID`() {
        PreJoinConnectionGate(1).use { gate ->
            val id = ConnectionId(UUID.randomUUID())
            val previous = assertNotNull(gate.tryOpen(id))
            assertTrue(gate.disconnected(previous))
            val current = assertNotNull(gate.tryOpen(id))
            assertFalse(gate.authenticated(previous))
            assertFalse(gate.deny(previous))
            assertEquals(1, gate.pendingCount())
            assertTrue(gate.authenticated(current))
            assertEquals(Outcome.AUTHENTICATED, gate.await(current, Duration.ofSeconds(1)))
        }
    }

    @Test
    fun `shutdown fails all pending tickets and prohibits new admission`() {
        val gate = PreJoinConnectionGate(2)
        val first = assertNotNull(gate.tryOpen(ConnectionId.random()))
        val second = assertNotNull(gate.tryOpen(ConnectionId.random()))
        gate.close()
        gate.close()
        assertEquals(Outcome.SHUTDOWN, gate.await(first, Duration.ofSeconds(1)))
        assertEquals(Outcome.SHUTDOWN, gate.await(second, Duration.ofSeconds(1)))
        assertNull(gate.tryOpen(ConnectionId.random()))
        assertFalse(gate.authenticated(first))
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun `concurrent attempts with same connection ID acquire at most one ticket`() {
        PreJoinConnectionGate(16).use { gate ->
            val id = ConnectionId.random()
            val pool = Executors.newFixedThreadPool(8)
            try {
                val ready = CountDownLatch(8)
                val start = CountDownLatch(1)
                val attempts = (1..8).map {
                    pool.submit<PreJoinConnectionGate.Ticket?> {
                        ready.countDown()
                        start.await()
                        gate.tryOpen(id)
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val admitted = attempts.mapNotNull { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, admitted.size)
                assertEquals(1, gate.pendingCount())
                assertTrue(gate.deny(admitted.single()))
                assertEquals(0, gate.pendingCount())
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
