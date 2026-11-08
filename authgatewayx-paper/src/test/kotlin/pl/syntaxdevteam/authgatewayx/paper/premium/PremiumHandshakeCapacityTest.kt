package pl.syntaxdevteam.authgatewayx.paper.premium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PremiumHandshakeCapacityTest {
    @Test
    fun `concurrent acquire and release never exceeds maximum`() {
        val capacity = PremiumHandshakeCapacity(4)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(16)
        val start = java.util.concurrent.CountDownLatch(1)
        val concurrent = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        try {
            val results = (1..16).map {
                workers.submit {
                    start.await()
                    repeat(1000) {
                        if (capacity.tryAcquire()) {
                            try {
                                peak.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
                                Thread.yield()
                            } finally {
                                concurrent.decrementAndGet()
                                capacity.release()
                            }
                        }
                    }
                }
            }
            start.countDown()
            results.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(peak.get() in 1..4)
            assertEquals(0, capacity.active())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `invalid release does not corrupt capacity`() {
        val capacity = PremiumHandshakeCapacity(1)
        assertFailsWith<IllegalStateException> { capacity.release() }
        assertEquals(0, capacity.active())
        assertTrue(capacity.tryAcquire())
        assertFalse(capacity.tryAcquire())
    }

    @Test
    fun `limits and releases concurrent premium handshakes`() {
        val capacity = PremiumHandshakeCapacity(2)

        assertTrue(capacity.tryAcquire())
        assertTrue(capacity.tryAcquire())
        assertFalse(capacity.tryAcquire())
        assertEquals(2, capacity.active())

        capacity.release()
        assertTrue(capacity.tryAcquire())
        assertEquals(2, capacity.active())
    }
}
