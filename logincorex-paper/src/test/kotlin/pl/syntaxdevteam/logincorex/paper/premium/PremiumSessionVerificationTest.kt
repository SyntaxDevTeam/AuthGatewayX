package pl.syntaxdevteam.logincorex.paper.premium

import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class PremiumSessionVerificationTest {
    private var now = 0L
    private val delays = mutableListOf<Long>()
    private fun verifier(afterPause: () -> Unit = {}) = PremiumSessionVerification({ now }) {
        delays += it
        now += TimeUnit.MILLISECONDS.toNanos(it)
        afterPause()
    }

    @Test fun `transient outage recovers on same verification with bounded backoff`() {
        var calls = 0
        val profile = Any()
        assertSame(profile, verifier().verify(0, { true }) {
            if (++calls < 3) throw AuthenticationUnavailableException("temporary")
            profile
        })
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), delays)
    }

    @Test fun `persistent outage stops after three total requests`() {
        var calls = 0
        assertFailsWith<AuthenticationUnavailableException> {
            verifier().verify<Any>(0, { true }) { calls++; throw AuthenticationUnavailableException("down") }
        }
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 2000L), delays)
    }

    @Test fun `invalid session is final without delay or offline fallback`() {
        var calls = 0
        assertNull(verifier().verify<Any>(0, { true }) { calls++; null })
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test fun `invalid session following outage is not retried again`() {
        var calls = 0
        assertNull(verifier().verify<Any>(0, { true }) {
            if (++calls == 1) throw AuthenticationUnavailableException("temporary")
            null
        })
        assertEquals(2, calls)
        assertEquals(listOf(1000L), delays)
    }

    @Test fun `unexpected errors are final`() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            verifier().verify<Any>(0, { true }) { calls++; error("failure") }
        }
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test fun `disconnect during backoff prevents next request`() {
        var connected = true
        var calls = 0
        assertFailsWith<CancellationException> {
            verifier { connected = false }.verify<Any>(0, { connected }) {
                calls++; throw AuthenticationUnavailableException("down")
            }
        }
        assertEquals(1, calls)
    }

    @Test fun `late success after disconnect is discarded`() {
        var connected = true
        assertFailsWith<CancellationException> {
            verifier().verify(0, { connected }) { connected = false; Any() }
        }
    }

    @Test fun `late success beyond shared deadline is discarded`() {
        assertFailsWith<TimeoutException> {
            verifier().verify(0, { true }) { now = PremiumSessionVerification.TIMEOUT_NANOS; Any() }
        }
    }

    @Test fun `no backoff or further request when remaining budget is insufficient`() {
        var calls = 0
        assertFailsWith<TimeoutException> {
            verifier().verify<Any>(0, { true }) {
                calls++
                now = PremiumSessionVerification.TIMEOUT_NANOS - TimeUnit.MILLISECONDS.toNanos(500)
                throw AuthenticationUnavailableException("down")
            }
        }
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test fun `expired and inactive connections never call service`() {
        assertFailsWith<CancellationException> { verifier().verify<Any>(0, { false }) { fail("called") } }
        now = PremiumSessionVerification.TIMEOUT_NANOS
        assertFailsWith<TimeoutException> { verifier().verify<Any>(0, { true }) { fail("called") } }
    }

    @Test fun `cancellation interrupts retry sleep`() {
        val verification = PremiumSessionVerification({ now }) { throw InterruptedException() }
        var calls = 0
        assertFailsWith<InterruptedException> {
            verification.verify<Any>(0, { true }) { calls++; throw AuthenticationUnavailableException("down") }
        }
        assertEquals(1, calls)
    }

    @Test fun `stalled cancelled request keeps worker capacity bounded and shutdown rejects work`() {
        val workers = premiumSessionWorkers(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val task = workers.submit {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Simulate uninterruptible HTTP. */ }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            task.cancel(true)
            assertFailsWith<java.util.concurrent.RejectedExecutionException> { workers.submit { fail("unbounded") } }
            assertEquals(0, workers.queue.size)
            workers.shutdownNow()
            assertFailsWith<java.util.concurrent.RejectedExecutionException> { workers.submit {} }
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `native prelogin hook and verification state contract are present`() {
        val listener = net.minecraft.server.network.ServerLoginPacketListenerImpl::class.java
        val method = listener.getDeclaredMethod("callPlayerPreLoginEvents", com.mojang.authlib.GameProfile::class.java)
        assertEquals(com.mojang.authlib.GameProfile::class.java, method.returnType)
        assertEquals(com.mojang.authlib.GameProfile::class.java, listener.getField("authenticatedProfile").type)
        assertNotNull(net.minecraft.server.network.ServerLoginPacketListenerImpl.State.valueOf("VERIFYING"))
    }
}
