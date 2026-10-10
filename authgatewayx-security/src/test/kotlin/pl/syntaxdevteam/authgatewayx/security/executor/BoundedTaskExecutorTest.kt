package pl.syntaxdevteam.authgatewayx.security.executor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.time.Duration
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoundedTaskExecutorTest {
    @Test
    fun `forced shutdown completes queued futures without executing them`() {
        val executor = BoundedTaskExecutor(1, 2, "shutdown-test")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var queuedRan = false
        try {
            val running = executor.submit { started.countDown(); release.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = executor.submit { queuedRan = true }
            executor.shutdown(Duration.ofMillis(50))
            val failure = kotlin.runCatching { queued.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
            assertIs<ExecutionException>(failure)
            assertIs<RejectedExecutionException>(failure.cause)
            assertTrue(running.isDone)
            assertEquals(false, queuedRan)
            assertTrue(executor.submit { 1 }.isCompletedExceptionally)
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `cancelled queued work does not execute expensive operation`() {
        val executor = BoundedTaskExecutor(1, 1, "cancel-test")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var queuedRan = false
        try {
            val running = executor.submit { started.countDown(); release.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = executor.submit { queuedRan = true }
            queued.cancel(false)
            release.countDown()
            running.get(2, TimeUnit.SECONDS)
            assertTrue(executor.shutdown(Duration.ofSeconds(2)))
            assertEquals(false, queuedRan)
        } finally { release.countDown(); executor.close() }
    }

    @Test
    fun `rejects work after worker and queue capacity are exhausted`() {
        val executor = BoundedTaskExecutor(1, 1, "auth-test")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val running = executor.submit { started.countDown(); release.await(); 1 }
            started.await()
            val queued = executor.submit { 2 }
            val rejected = executor.submit { 3 }

            val failure = kotlin.runCatching { rejected.get() }.exceptionOrNull()
            assertIs<ExecutionException>(failure)
            assertIs<RejectedExecutionException>(failure.cause)
            release.countDown()
            assertEquals(1, running.get())
            assertEquals(2, queued.get())
        } finally {
            release.countDown()
            executor.close()
        }
    }
}
