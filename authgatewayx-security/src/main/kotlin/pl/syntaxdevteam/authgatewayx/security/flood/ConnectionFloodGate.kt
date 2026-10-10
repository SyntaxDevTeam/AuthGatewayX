package pl.syntaxdevteam.authgatewayx.security.flood

import java.net.InetAddress
import java.time.Duration
import java.util.LinkedHashMap
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
    private data class AddressBucket(val bucket: TokenBucket, var lastSeen: Long)
    // Access order permits amortized eviction without scanning the entire table on every new IP.
    private val perIp = LinkedHashMap<InetAddress, AddressBucket>(16, 0.75f, true)
    private val idleRetentionNanos = run {
        require(perIpLimit.capacity > 0 && perIpLimit.refillTokens > 0 &&
            !perIpLimit.refillPeriod.isZero && !perIpLimit.refillPeriod.isNegative)
        maxOf(
        Duration.ofMinutes(1).toNanos(),
        perIpLimit.refillPeriod.multipliedBy(
            (perIpLimit.capacity.toLong() + perIpLimit.refillTokens - 1) / perIpLimit.refillTokens,
        ).toNanos(),
        )
    }

    init {
        require(maxTrackedAddresses > 0) { "Tracked address limit must be positive" }
    }

    @Synchronized
    fun evaluate(address: InetAddress): ConnectionDecision {
        val now = timeSource.now()
        val state = perIp[address] ?: createBucket(address, now) ?: return ConnectionDecision.DENY_TRACKING_CAPACITY
        state.lastSeen = now
        if (!state.bucket.tryConsume()) return ConnectionDecision.DENY_PER_IP
        if (!global.tryConsume()) return ConnectionDecision.DENY_GLOBAL
        return ConnectionDecision.ALLOW
    }

    @Synchronized
    fun trackedAddressCount(): Int = perIp.size

    @Synchronized
    fun clear() = perIp.clear()

    @Synchronized
    private fun createBucket(address: InetAddress, now: Long): AddressBucket? {
        perIp[address]?.let { return it }
        val oldest = perIp.entries.iterator()
        while (oldest.hasNext()) {
            if (now - oldest.next().value.lastSeen < idleRetentionNanos) break
            oldest.remove()
        }
        if (perIp.size >= maxTrackedAddresses) return null
        val candidate = AddressBucket(perIpLimit.toBucket(), now)
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
