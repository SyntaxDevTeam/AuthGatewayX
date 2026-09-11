package pl.syntaxdevteam.authgatewayx.velocity

import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class SelectedAuthenticationMode { MOJANG, OFFLINE }
data class PendingLoginDecision(val mode: SelectedAuthenticationMode, val risk: ProxyRiskResult)
data class PendingConnectionKey(val address: InetSocketAddress, val canonicalUsername: String)

class PendingConnectionRegistry(private val ttl: Duration, private val maximumSize: Int, private val clock: Clock = Clock.systemUTC()) {
    private var closed = false
    private val entries = ConcurrentHashMap<PendingConnectionKey, Entry>()
    init { require(!ttl.isZero && !ttl.isNegative && maximumSize > 0) }

    @Synchronized
    fun put(key: PendingConnectionKey, decision: PendingLoginDecision): Boolean {
        if (closed) return false
        cleanup()
        if (entries.size >= maximumSize && !entries.containsKey(key)) return false
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
