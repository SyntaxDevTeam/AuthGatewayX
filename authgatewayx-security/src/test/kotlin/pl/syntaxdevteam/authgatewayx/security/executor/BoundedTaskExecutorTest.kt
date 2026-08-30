package pl.syntaxdevteam.authgatewayx.security.executor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoundedTaskExecutorTest {
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
