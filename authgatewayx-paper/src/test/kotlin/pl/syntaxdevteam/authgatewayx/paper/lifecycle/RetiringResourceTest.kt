package pl.syntaxdevteam.authgatewayx.paper.lifecycle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class RetiringResourceTest {
    @Test fun `resource attached before retirement closes exactly once`() {
        val slot = RetiringResource<AutoCloseable>()
        val closes = AtomicInteger()
        slot.attach(AutoCloseable { closes.incrementAndGet() })
        slot.stopAccepting()
        slot.close()
        slot.close()
        assertEquals(1, closes.get())
    }

    @Test fun `late initialization closes resource instead of publishing it`() {
        val slot = RetiringResource<AutoCloseable>()
        val closes = AtomicInteger()
        slot.stopAccepting()
        slot.close()
        assertFailsWith<IllegalStateException> { slot.attach(AutoCloseable { closes.incrementAndGet() }) }
        slot.close()
        assertEquals(1, closes.get())
    }

    @Test fun `stop does not wait for slow resource close`() {
        val slot = RetiringResource<AutoCloseable>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        slot.attach(AutoCloseable { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
        val closer = thread { slot.close() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            slot.stopAccepting()
        } finally {
            release.countDown()
            closer.join(5000)
        }
        assertFalse(closer.isAlive)
    }
}
