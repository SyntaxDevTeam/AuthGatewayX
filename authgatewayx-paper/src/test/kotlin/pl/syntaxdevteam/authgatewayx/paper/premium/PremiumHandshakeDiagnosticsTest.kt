package pl.syntaxdevteam.authgatewayx.paper.premium

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.syntaxdevteam.authgatewayx.paper.premium.PremiumHandshakeDiagnostics.Event

class PremiumHandshakeDiagnosticsTest {
    @Test
    fun `disabled diagnostics do not create traces or write`() {
        PremiumHandshakeDiagnostics(false, 120, { 0 }, 32, { error("disabled sink") }).use {
            assertNull(it.newTrace())
        }
    }

    @Test
    fun `reports deterministic durations and stops terminal trace`() {
        val time = AtomicLong()
        val lines = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        val diagnostics = PremiumHandshakeDiagnostics(true, 120, { 1 }, 32, {
            lines += it
            if (it.contains("summary")) finished.countDown()
        }, time::get)
        val trace = diagnostics.newTrace()!!
        trace.record(Event.GUARD_DENIED, guard = pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginGuardDecision.DENY_FLOOD)
        trace.record(Event.LOOKUP_START)
        time.set(TimeUnit.MILLISECONDS.toNanos(25))
        trace.record(Event.LOOKUP_PREMIUM)
        trace.record(Event.SLOT_ACQUIRED)
        time.set(TimeUnit.MILLISECONDS.toNanos(100))
        trace.record(Event.SLOT_RELEASED)
        trace.record(Event.NATIVE_VERIFIED, terminalEvent = true)
        trace.record(Event.DISCONNECTED)
        diagnostics.close()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(lines.any { "reason=DENY_FLOOD" in it })
        assertTrue(lines.last().contains("DENY_FLOOD=1"))
        assertTrue(lines.any { "lookup_ms=25" in it })
        assertTrue(lines.any { "handshake_ms=75" in it })
        assertTrue(lines.last().contains("NATIVE_VERIFIED=1"))
        assertTrue(lines.last().contains("DISCONNECTED=0"))
        assertTrue(lines.all { "trace=" in it || "summary" in it })
    }

    @Test
    fun `rate limit preserves counters and resets after a minute`() {
        val time = AtomicLong()
        val lines = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)
        val diagnostics = PremiumHandshakeDiagnostics(true, 1, { 0 }, 2, {
            lines += it
            if (it.contains("summary")) finished.countDown()
        }, time::get)
        repeat(20) { diagnostics.newTrace()!!.record(Event.CAPACITY_DENIED) }
        time.set(TimeUnit.MINUTES.toNanos(1))
        diagnostics.newTrace()!!.record(Event.START)
        diagnostics.close()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(2, lines.count { "trace=" in it })
        assertTrue(lines.last().contains("CAPACITY_DENIED=20"))
        assertTrue(lines.last().contains("suppressed=19"))
    }

    @Test
    fun `blocked writer cannot block producers and queue overflow remains visible`() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        val diagnostics = PremiumHandshakeDiagnostics(true, 1000, { 0 }, 2, {
            entered.countDown()
            unblock.await(3, TimeUnit.SECONDS)
            lines += it
            if (it.contains("summary")) finished.countDown()
        }, queueCapacity = 1)
        try {
            diagnostics.newTrace()!!.record(Event.START)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(20) { diagnostics.newTrace()!!.record(Event.CAPACITY_DENIED) }
            assertTrue(diagnostics.summary().contains("dropped=19"))
        } finally {
            unblock.countDown()
            diagnostics.close()
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(lines.last().contains("CAPACITY_DENIED=20"))
    }

    @Test
    fun `sink exception cannot escape into authentication`() {
        val attempts = CountDownLatch(2)
        val diagnostics = PremiumHandshakeDiagnostics(true, 10, { 0 }, 2, {
            attempts.countDown()
            throw IllegalStateException("test writer failure")
        })
        diagnostics.newTrace()!!.record(Event.START)
        diagnostics.close()
        assertTrue(attempts.await(3, TimeUnit.SECONDS))
        assertNull(diagnostics.newTrace())
        assertFalse(diagnostics.summary().contains("test writer failure"))
    }
}
