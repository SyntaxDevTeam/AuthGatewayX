package pl.syntaxdevteam.authgatewayx.security.executor

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedTaskExecutor(
    threadCount: Int,
    queueCapacity: Int,
    threadNamePrefix: String,
) : AutoCloseable {
    private val executor: ThreadPoolExecutor

    init {
        require(threadCount > 0) { "Thread count must be positive" }
        require(queueCapacity > 0) { "Queue capacity must be positive" }
        val sequence = AtomicInteger()
        val factory = ThreadFactory { task ->
            Thread(task, "$threadNamePrefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }
        executor = ThreadPoolExecutor(
            threadCount,
            threadCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            factory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun <T> submit(task: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try {
            executor.execute {
                try {
                    result.complete(task())
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (rejected: RejectedExecutionException) {
            result.completeExceptionally(rejected)
        }
        return result
    }

    fun shutdown(timeout: Duration): Boolean {
        executor.shutdown()
        if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) return true
        executor.shutdownNow()
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        shutdown(Duration.ofSeconds(5))
    }
}
