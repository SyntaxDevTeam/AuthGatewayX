package pl.syntaxdevteam.authgatewayx.security.flood

import java.time.Duration
import kotlin.math.min

fun interface NanoTimeSource {
    fun now(): Long
}

class TokenBucket(
    private val capacity: Int,
    refillTokens: Int,
    refillPeriod: Duration,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val tokensPerNano: Double
    private var available: Double
    private var lastRefill: Long

    init {
        require(capacity > 0) { "Capacity must be positive" }
        require(refillTokens > 0) { "Refill tokens must be positive" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) { "Refill period must be positive" }
        tokensPerNano = refillTokens.toDouble() / refillPeriod.toNanos()
        available = capacity.toDouble()
        lastRefill = timeSource.now()
    }

    @Synchronized
    fun tryConsume(): Boolean {
        refill()
        if (available < 1.0) return false
        available -= 1.0
        return true
    }

    @Synchronized
    private fun refill() {
        val now = timeSource.now()
        val elapsed = now - lastRefill
        if (elapsed <= 0) return
        available = min(capacity.toDouble(), available + elapsed * tokensPerNano)
        lastRefill = now
    }
}
