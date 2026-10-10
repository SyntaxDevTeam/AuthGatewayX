package pl.syntaxdevteam.authgatewayx.paper.premium

import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginGuardDecision
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/** Opt-in, bounded diagnostics. Producers never perform logging or wait for the writer. */
internal class PremiumHandshakeDiagnostics(
    private val enabled: Boolean,
    private val maximumLinesPerMinute: Int,
    private val capacity: () -> Int,
    private val maximumCapacity: Int,
    private val sink: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    queueCapacity: Int = 128,
) : AutoCloseable {
    enum class Event {
        START, INVALID_HELLO, GUARD_DENIED, LOOKUP_START, LOOKUP_PREMIUM, LOOKUP_OFFLINE,
        LOOKUP_UNAVAILABLE, LATE_LOOKUP, SLOT_ACQUIRED, CAPACITY_DENIED, NATIVE_STATE,
        NATIVE_VERIFIED, NATIVE_DENIED, TIMEOUT, DISCONNECTED, SLOT_RELEASED,
    }

    enum class NativeState { HELLO, KEY, AUTHENTICATING, NEGOTIATING, VERIFYING, WAITING_FOR_DUPE_DISCONNECT, PROTOCOL_SWITCHING, ACCEPTED }

    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong()
    private val counters = Event.entries.associateWith { LongAdder() }
    private val guardCounters = PaperLoginGuardDecision.entries.associateWith { LongAdder() }
    private val suppressed = LongAdder()
    private val dropped = LongAdder()
    private var windowStart = nanoTime()
    private var windowLines = 0
    init {
        require(maximumLinesPerMinute > 0)
        require(maximumCapacity > 0)
    }

    private val writer = if (enabled) Thread(::writeLoop, "authgatewayx-premium-diagnostics").apply {
        isDaemon = true
        start()
    } else null

    fun newTrace(): Trace? = if (enabled && !closed.get()) Trace(sequence.incrementAndGet()) else null

    inner class Trace internal constructor(private val id: Long) {
        private val started = nanoTime()
        private var previous = started
        private var terminal = false
        private var lookupStarted: Long? = null
        private var handshakeStarted: Long? = null

        @Synchronized
        fun record(event: Event, terminalEvent: Boolean = false, state: NativeState? = null, guard: PaperLoginGuardDecision? = null) {
            if (terminal || closed.get()) return
            terminal = terminalEvent
            val now = nanoTime()
            counters.getValue(event).increment()
            if (guard != null) guardCounters.getValue(guard).increment()
            if (event == Event.LOOKUP_START) lookupStarted = now
            if (event == Event.SLOT_ACQUIRED) handshakeStarted = now
            val elapsed = TimeUnit.NANOSECONDS.toMillis((now - started).coerceAtLeast(0))
            val stage = TimeUnit.NANOSECONDS.toMillis((now - previous).coerceAtLeast(0))
            previous = now
            if (!permit(now)) return
            val lookup = lookupStarted?.let { TimeUnit.NANOSECONDS.toMillis((now - it).coerceAtLeast(0)) }
            val handshake = handshakeStarted?.let { TimeUnit.NANOSECONDS.toMillis((now - it).coerceAtLeast(0)) }
            val timing = when (event) {
                Event.LOOKUP_PREMIUM, Event.LOOKUP_OFFLINE, Event.LOOKUP_UNAVAILABLE -> " lookup_ms=$lookup"
                Event.SLOT_RELEASED, Event.NATIVE_VERIFIED, Event.DISCONNECTED -> " handshake_ms=$handshake"
                else -> ""
            }
            val phase = (state?.let { " state=$it" } ?: "") + (guard?.let { " reason=$it" } ?: "")
            if (!queue.offer("[premium-diag] trace=${id.toString(36)} event=$event elapsed_ms=$elapsed stage_ms=$stage active=${capacity()}/$maximumCapacity$timing$phase")) {
                dropped.increment()
            }
        }
    }

    @Synchronized
    private fun permit(now: Long): Boolean {
        if (now - windowStart >= TimeUnit.MINUTES.toNanos(1)) {
            windowStart = now
            windowLines = 0
        }
        if (windowLines >= maximumLinesPerMinute) {
            suppressed.increment()
            return false
        }
        windowLines++
        return true
    }

    internal fun summary(): String = "[premium-diag] summary active=${capacity()}/$maximumCapacity " +
        counters.entries.joinToString(" ") { "${it.key}=${it.value.sum()}" } +
        " " + guardCounters.entries.filter { it.key != PaperLoginGuardDecision.ALLOW }.joinToString(" ") { "${it.key}=${it.value.sum()}" } +
        " suppressed=${suppressed.sum()} dropped=${dropped.sum()}"

    private fun writeLoop() {
        var lastSummary = nanoTime()
        while (!closed.get() || queue.isNotEmpty()) {
            val line = try { queue.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            if (line != null) safelyWrite(line)
            val now = nanoTime()
            if (!closed.get() && now - lastSummary >= TimeUnit.SECONDS.toNanos(30)) {
                safelyWrite(summary())
                lastSummary = now
            }
        }
        safelyWrite(summary())
    }

    private fun safelyWrite(line: String) {
        try { sink(line) } catch (_: Exception) { dropped.increment() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) writer?.interrupt()
    }
}
