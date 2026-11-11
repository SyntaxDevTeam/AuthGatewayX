package pl.syntaxdevteam.logincorex.integrations.api

import pl.syntaxdevteam.logincorex.api.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** Single ordered dispatcher, isolated from authentication executors. No caller-runs policy. */
class BoundedAuthEventBus(
    queueCapacity: Int = 1024,
    private val maximumSubscribers: Int = 128,
) : AuthEventSource, AutoCloseable {
    private val listeners = LinkedHashMap<Any, Consumer<AuthEvent>>()
    private var closed = false
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(queueCapacity),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "logincorex-api-events").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    init { require(maximumSubscribers > 0) }

    @Synchronized
    override fun subscribe(listener: Consumer<AuthEvent>): AuthSubscription {
        check(!closed) { "Event source is closed" }
        check(listeners.size < maximumSubscribers) { "Subscription limit reached" }
        val key = Any()
        listeners[key] = listener
        return AuthSubscription { synchronized(this) { listeners.remove(key); Unit } }
    }

    @Synchronized
    fun publish(event: AuthEvent) {
        if (closed || listeners.isEmpty()) return
        val recipients = listeners.keys.toList()
        try {
            executor.execute {
                recipients.forEach { key ->
                    val listener = synchronized(this) { if (closed) null else listeners[key] }
                    if (listener != null) try { listener.accept(event) } catch (_: Throwable) { /* consumer isolation */ }
                }
            }
        } catch (_: RejectedExecutionException) { dropped.incrementAndGet() }
    }

    override fun droppedEvents(): Long = dropped.get()

    @Synchronized
    override fun close() {
        closed = true
        listeners.clear()
        executor.shutdownNow()
    }
}
