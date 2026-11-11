package pl.syntaxdevteam.logincorex.security.login

import pl.syntaxdevteam.logincorex.security.flood.FloodLimit
import pl.syntaxdevteam.logincorex.security.flood.NanoTimeSource
import pl.syntaxdevteam.logincorex.security.flood.TokenBucket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

enum class LoginAttemptDecision { ALLOW, RATE_LIMITED, TRACKING_CAPACITY_EXCEEDED }

class LoginAttemptGate(
    private val limit: FloodLimit,
    private val maxTrackedAddresses: Int,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val buckets = ConcurrentHashMap<InetAddress, TokenBucket>()

    init { require(maxTrackedAddresses > 0) }

    fun evaluate(address: InetAddress): LoginAttemptDecision {
        val bucket = buckets[address] ?: create(address)
            ?: return LoginAttemptDecision.TRACKING_CAPACITY_EXCEEDED
        return if (bucket.tryConsume()) LoginAttemptDecision.ALLOW else LoginAttemptDecision.RATE_LIMITED
    }

    @Synchronized
    private fun create(address: InetAddress): TokenBucket? {
        buckets[address]?.let { return it }
        if (buckets.size >= maxTrackedAddresses) return null
        return TokenBucket(limit.capacity, limit.refillTokens, limit.refillPeriod, timeSource).also { buckets[address] = it }
    }

    fun clear() = buckets.clear()
}
