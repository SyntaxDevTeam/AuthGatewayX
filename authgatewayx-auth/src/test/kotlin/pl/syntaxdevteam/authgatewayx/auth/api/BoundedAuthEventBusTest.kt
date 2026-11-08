package pl.syntaxdevteam.authgatewayx.auth.api

import pl.syntaxdevteam.authgatewayx.integrations.api.BoundedAuthEventBus
import pl.syntaxdevteam.authgatewayx.api.*
import pl.syntaxdevteam.authgatewayx.security.audit.*
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BoundedAuthEventBusTest {
    private fun event() = AuthEvent(AuthEventType.AUTHENTICATION_FAILED, Instant.now(), null, "ApiUser", null, null)

    @Test fun `slow consumer cannot block publisher and queue and subscriptions are bounded`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        BoundedAuthEventBus(1, 1).use { bus ->
            bus.subscribe { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertFails { bus.subscribe {} }
            bus.publish(event())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            bus.publish(event())
            bus.publish(event())
            assertEquals(1, bus.droppedEvents())
            release.countDown()
        }
    }

    @Test fun `listener failures are isolated and close rejects new listeners`() {
        val delivered = CountDownLatch(1)
        val bus = BoundedAuthEventBus()
        bus.subscribe { error("consumer failure") }
        bus.subscribe { delivered.countDown() }
        bus.publish(event())
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        bus.close()
        assertFails { bus.subscribe {} }
        bus.publish(event())
    }

    @Test fun `unsubscribe suppresses queued callbacks and new subscribers do not receive history`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val calls = AtomicInteger()
        BoundedAuthEventBus().use { bus ->
            val blocker = bus.subscribe { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            val target = bus.subscribe { calls.incrementAndGet() }
            bus.publish(event())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            target.close()
            blocker.close()
            bus.subscribe { flushed.countDown() }
            bus.publish(event())
            release.countDown()
            assertTrue(flushed.await(5, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
        }
    }

    @Test fun `password verification audit is never authentication success`() {
        val received = LinkedBlockingQueue<AuthEvent>()
        BoundedAuthEventBus().use { bus ->
            bus.subscribe { received.add(it) }
            val audit = SecurityEvent(Instant.now(), null, null, "ApiUser", InetAddress.getLoopbackAddress(),
                SecurityEventType.LOGIN_SUCCESS, "PASSWORD")
            bus.observeSecurity(audit)
            bus.observeSecurity(audit.copy(type = SecurityEventType.REGISTER))
            assertEquals(AuthEventType.PLAYER_REGISTERED, received.poll(5, TimeUnit.SECONDS)?.type)
            assertTrue(received.isEmpty())
        }
    }
}
