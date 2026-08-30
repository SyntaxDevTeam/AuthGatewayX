package pl.syntaxdevteam.authgatewayx.security.flood

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class FloodLimit(
    val capacity: Int,
    val refillTokens: Int,
    val refillPeriod: Duration,
)

enum class ConnectionDecision {
    ALLOW,
    DENY_PER_IP,
    DENY_GLOBAL,
    DENY_TRACKING_CAPACITY,
}

class ConnectionFloodGate(
    private val perIpLimit: FloodLimit,
    globalLimit: FloodLimit,
    private val maxTrackedAddresses: Int,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val global = globalLimit.toBucket()
    private val perIp = ConcurrentHashMap<InetAddress, TokenBucket>()

    init {
        require(maxTrackedAddresses > 0) { "Tracked address limit must be positive" }
    }

    fun evaluate(address: InetAddress): ConnectionDecision {
        val bucket = perIp[address] ?: createBucket(address) ?: return ConnectionDecision.DENY_TRACKING_CAPACITY
        if (!bucket.tryConsume()) return ConnectionDecision.DENY_PER_IP
        if (!global.tryConsume()) return ConnectionDecision.DENY_GLOBAL
        return ConnectionDecision.ALLOW
    }

    fun trackedAddressCount(): Int = perIp.size

    fun clear() = perIp.clear()

    @Synchronized
    private fun createBucket(address: InetAddress): TokenBucket? {
        perIp[address]?.let { return it }
        if (perIp.size >= maxTrackedAddresses) return null
        val candidate = perIpLimit.toBucket()
        perIp[address] = candidate
        return candidate
    }

    private fun FloodLimit.toBucket() = TokenBucket(capacity, refillTokens, refillPeriod, timeSource)
}

class PreAuthCapacity(private val maximum: Int) {
    private val active = AtomicInteger()

    init {
        require(maximum > 0) { "Maximum pre-auth sessions must be positive" }
    }

    fun tryAcquire(): Lease? {
        while (true) {
            val current = active.get()
            if (current >= maximum) return null
            if (active.compareAndSet(current, current + 1)) return Lease()
        }
    }

    fun activeCount(): Int = active.get()

    inner class Lease internal constructor() : AutoCloseable {
        private val released = AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) active.decrementAndGet()
        }
    }
}
