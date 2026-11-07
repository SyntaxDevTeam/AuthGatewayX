package pl.syntaxdevteam.authgatewayx.security.login

import java.net.InetAddress
import java.time.Duration
import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource

/** Bounded, atomic IP quarantine. A zero window retains failures until quarantine expires. */
class ConnectionTimeoutGate(
    private val attempts: Int,
    window: Duration,
    banDuration: Duration,
    private val maximumAddresses: Int,
    private val clock: NanoTimeSource = NanoTimeSource { Math.multiplyExact(System.currentTimeMillis(), 1_000_000L) },
) {
    private data class State(var started: Long, var failures: Int = 0, var bannedUntil: Long? = null)
    private val states = HashMap<InetAddress, State>()
    private val windowNanos = window.toNanos()
    private val banNanos = banDuration.toNanos()

    init {
        require(attempts > 0 && maximumAddresses > 0)
        require(!window.isNegative && banNanos > 0)
    }

    @Volatile private var healthy = true
    fun failClosed() { healthy = false }

    data class Entry(val address: InetAddress, val started: Long, val failures: Int, val bannedUntil: Long?)

    @Synchronized
    fun snapshot(): List<Entry> = states.map { (address, state) ->
        Entry(address, state.started, state.failures, state.bannedUntil)
    }

    @Synchronized
    fun restore(entries: List<Entry>) {
        require(entries.size <= maximumAddresses) { "Timeout state exceeds configured capacity" }
        require(entries.map { it.address }.toSet().size == entries.size) { "Duplicate timeout address" }
        require(entries.all { it.failures > 0 && it.started >= 0 &&
            (it.bannedUntil == null || it.bannedUntil >= it.started) }) { "Invalid timeout state" }
        check(states.isEmpty())
        entries.forEach { states[it.address] = State(it.started, it.failures, it.bannedUntil) }
        prune(clock.now())
    }

    @Synchronized
    fun admit(address: InetAddress): Boolean {
        if (!healthy) return false
        val now = clock.now()
        states[address]?.let { state ->
            val expired = state.bannedUntil?.let { now >= it }
                ?: (windowNanos > 0 && now - state.started >= windowNanos)
            if (expired) states.remove(address) else return state.bannedUntil == null
        }
        if (states.size >= maximumAddresses) prune(now)
        return states.size < maximumAddresses
    }

    /** Returns remaining attempts; null means this address was not admitted. */
    @Synchronized
    fun timeout(address: InetAddress): Int? {
        val now = clock.now()
        val state = states[address] ?: run {
            if (!admit(address)) return null
            State(now).also { states[address] = it }
        }
        if (state.bannedUntil != null) return 0 // Repeated packets never extend a ban.
        if (windowNanos > 0 && now - state.started >= windowNanos) {
            state.started = now
            state.failures = 0
        }
        state.failures++
        if (state.failures >= attempts) state.bannedUntil = Math.addExact(now, banNanos)
        return (attempts - state.failures).coerceAtLeast(0)
    }

    private fun prune(now: Long) {
        states.entries.removeIf { (_, state) ->
            state.bannedUntil?.let { now >= it }
                ?: (windowNanos > 0 && state.failures > 0 && now - state.started >= windowNanos)
        }
    }
}
