package pl.syntaxdevteam.authgatewayx.velocity

import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class SelectedAuthenticationMode { MOJANG, OFFLINE }
data class PendingConnectionKey(val address: InetAddress, val canonicalUsername: String)

class PendingConnectionRegistry(private val ttl: Duration, private val maximumSize: Int, private val clock: Clock = Clock.systemUTC()) {
    private val entries = ConcurrentHashMap<PendingConnectionKey, Entry>()
    init { require(!ttl.isZero && !ttl.isNegative && maximumSize > 0) }

    @Synchronized
    fun put(key: PendingConnectionKey, mode: SelectedAuthenticationMode): Boolean {
        cleanup()
        if (entries.size >= maximumSize && !entries.containsKey(key)) return false
        entries[key] = Entry(mode, clock.instant().plus(ttl))
        return true
    }

    fun remove(key: PendingConnectionKey): SelectedAuthenticationMode? =
        entries.remove(key)?.takeIf { clock.instant().isBefore(it.expiresAt) }?.mode

    fun discard(key: PendingConnectionKey) { entries.remove(key) }
    fun clear() = entries.clear()
    private fun cleanup() { val now = clock.instant(); entries.entries.removeIf { !now.isBefore(it.value.expiresAt) } }
    private data class Entry(val mode: SelectedAuthenticationMode, val expiresAt: Instant)
}
