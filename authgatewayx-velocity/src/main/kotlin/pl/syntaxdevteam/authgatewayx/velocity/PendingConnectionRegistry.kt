package pl.syntaxdevteam.authgatewayx.velocity

import java.util.UUID
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class SelectedAuthenticationMode { MOJANG, OFFLINE }
data class PendingLoginDecision(val mode: SelectedAuthenticationMode, val risk: ProxyRiskResult)
/** Velocity 4.3.0 binds the same UUID to PreLogin and Login, including after backend changes. */
data class PendingConnectionKey(val sessionId: UUID)

class PendingConnectionRegistry(private val ttl: Duration, private val maximumSize: Int, private val clock: Clock = Clock.systemUTC()) {
    private var closed = false
    private val entries = ConcurrentHashMap<PendingConnectionKey, Entry>()
    private var additionsSinceSweep = 0
    init { require(!ttl.isZero && !ttl.isNegative && maximumSize > 0) }

    @Synchronized
    fun put(key: PendingConnectionKey, decision: PendingLoginDecision): Boolean {
        if (closed) return false
        // A full map still evicts expired entries; normal logins do not scan the map O(n).
        if (++additionsSinceSweep >= 256 || entries.size >= maximumSize) {
            cleanup()
            additionsSinceSweep = 0
        }
        if (entries.size >= maximumSize && !entries.containsKey(key)) return false
        if (entries.containsKey(key)) return false // Never replace an earlier admission for the same connection.
        entries[key] = Entry(decision, clock.instant().plus(ttl))
        return true
    }

    fun remove(key: PendingConnectionKey): PendingLoginDecision? =
        entries.remove(key)?.takeIf { clock.instant().isBefore(it.expiresAt) }?.decision

    fun discard(key: PendingConnectionKey) { entries.remove(key) }
    fun clear() = entries.clear()
    @Synchronized fun close() { closed = true; entries.clear() }
    private fun cleanup() { val now = clock.instant(); entries.entries.removeIf { !now.isBefore(it.value.expiresAt) } }
    private data class Entry(val decision: PendingLoginDecision, val expiresAt: Instant)
}
