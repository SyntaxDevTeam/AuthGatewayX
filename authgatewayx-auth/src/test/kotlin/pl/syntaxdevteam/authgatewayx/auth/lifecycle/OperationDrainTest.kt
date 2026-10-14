package pl.syntaxdevteam.authgatewayx.auth.lifecycle

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class OperationDrainTest {
    @Test fun `stop remains nonblocking while admitted provider is entering`() {
        val gate = OperationDrain()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = CompletableFuture<String>()
        val worker = thread {
            gate.run { entered.countDown(); release.await(5, TimeUnit.SECONDS); result }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val drain = gate.stop()
            assertFalse(drain.isDone)
            release.countDown()
            worker.join(5000)
            result.complete("done")
            assertTrue(drain.isDone)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
    }

    @Test fun `stop waits for admitted work and rejects later work`() {
        val gate = OperationDrain()
        val work = CompletableFuture<String>()
        val result = gate.run { work }
        val drain = gate.stop()
        assertFalse(drain.isDone)
        var invoked = false
        assertFailsWith<CompletionException> {
            gate.run { invoked = true; CompletableFuture.completedFuture("late") }.toCompletableFuture().join()
        }
        assertFalse(invoked)
        work.complete("done")
        assertEquals("done", result.toCompletableFuture().join())
        assertTrue(drain.isDone)
        assertSame(drain, gate.stop())
    }

    @Test fun `synchronous failure releases admitted operation`() {
        val gate = OperationDrain()
        assertFailsWith<CompletionException> {
            gate.run<String> { error("provider failed") }.toCompletableFuture().join()
        }
        assertTrue(gate.stop().isDone)
    }

    @Test fun `failed asynchronous operation does not prevent draining others`() {
        val gate = OperationDrain()
        val one = CompletableFuture<String>()
        val two = CompletableFuture<String>()
        gate.run { one }
        gate.run { two }
        val drain = gate.stop()
        one.completeExceptionally(IllegalStateException("failed"))
        assertFalse(drain.isDone)
        two.complete("done")
        assertTrue(drain.isDone)
        assertFalse(drain.isCompletedExceptionally)
    }
}
