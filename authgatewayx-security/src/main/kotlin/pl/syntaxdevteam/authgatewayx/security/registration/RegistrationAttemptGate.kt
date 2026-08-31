package pl.syntaxdevteam.authgatewayx.security.registration

import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import pl.syntaxdevteam.authgatewayx.security.flood.TokenBucket
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

enum class RegistrationAttemptDecision { ALLOW, RATE_LIMITED, TRACKING_CAPACITY_EXCEEDED }

class RegistrationAttemptGate(
    private val limit: FloodLimit,
    private val maximumTrackedAddresses: Int,
    stateTtl: Duration,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val states = ConcurrentHashMap<InetAddress, State>()
    private val stateTtlNanos = stateTtl.toNanos()

    init {
        require(maximumTrackedAddresses > 0) { "Tracked address limit must be positive" }
        require(stateTtlNanos > 0) { "State TTL must be positive" }
    }

    fun evaluate(address: InetAddress): RegistrationAttemptDecision {
        val now = timeSource.now()
        val state = states[address] ?: create(address, now)
            ?: return RegistrationAttemptDecision.TRACKING_CAPACITY_EXCEEDED
        state.touch(now)
        return if (state.bucket.tryConsume()) RegistrationAttemptDecision.ALLOW else RegistrationAttemptDecision.RATE_LIMITED
    }

    fun trackedAddressCount(): Int = states.size

    fun clear() = states.clear()

    @Synchronized
    private fun create(address: InetAddress, now: Long): State? {
        states[address]?.let { return it }
        if (states.size >= maximumTrackedAddresses) {
            states.entries.removeIf { (_, state) -> state.isExpired(now) }
        }
        if (states.size >= maximumTrackedAddresses) return null
        return State(
            TokenBucket(limit.capacity, limit.refillTokens, limit.refillPeriod, timeSource),
            now,
        ).also { states[address] = it }
    }

    private inner class State(val bucket: TokenBucket, lastSeenAt: Long) {
        @Volatile private var lastSeenAt = lastSeenAt

        fun touch(now: Long) { lastSeenAt = now }

        fun isExpired(now: Long): Boolean {
            val seen = lastSeenAt
            return now < seen || now - seen >= stateTtlNanos
        }
    }
}
