package pl.syntaxdevteam.authgatewayx.velocity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class VelocityLoginDiagnosticsTest {
    @Test fun `flood output is bounded and written outside caller thread`() {
        val count = AtomicInteger()
        val delivered = CountDownLatch(120)
        val caller = Thread.currentThread()
        val wrongThread = AtomicInteger()
        VelocityLoginDiagnostics {
            if (Thread.currentThread() === caller) wrongThread.incrementAndGet()
            count.incrementAndGet()
            delivered.countDown()
        }.use { diagnostics ->
            repeat(1000) { diagnostics.record("test") }
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(120, count.get())
            assertEquals(0, wrongThread.get())
            diagnostics.close()
            diagnostics.record("after shutdown")
            assertEquals(120, count.get())
        }
    }
}
