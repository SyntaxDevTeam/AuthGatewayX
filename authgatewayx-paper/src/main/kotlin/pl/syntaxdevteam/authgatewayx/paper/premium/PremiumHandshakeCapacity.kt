package pl.syntaxdevteam.authgatewayx.paper.premium

import java.util.concurrent.atomic.AtomicInteger

internal class PremiumHandshakeCapacity(private val maximum: Int) {
    private val active = AtomicInteger()

    init {
        require(maximum > 0) { "maximum must be positive" }
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= maximum) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        val remaining = active.decrementAndGet()
        check(remaining >= 0) { "premium handshake capacity released more than once" }
    }

    fun active(): Int = active.get()
}
