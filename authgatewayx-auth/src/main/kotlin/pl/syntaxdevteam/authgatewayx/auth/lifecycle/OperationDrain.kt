package pl.syntaxdevteam.authgatewayx.auth.lifecycle

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException

/** Rejects new work after stop, while letting already admitted mutations finish. */
internal class OperationDrain {
    private var stopped = false
    private var pending = 0
    private val drained = CompletableFuture<Void>()

    fun <T> run(action: () -> CompletionStage<T>): CompletionStage<T> {
        synchronized(this) {
            if (stopped) return CompletableFuture.failedFuture(RejectedExecutionException("Runtime is stopping"))
            pending++
        }
        val stage = try { action() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        return stage.whenComplete { _, _ ->
            val finished = synchronized(this) { --pending == 0 && stopped }
            if (finished) drained.complete(null)
        }
    }

    fun stop(): CompletableFuture<Void> {
        val finished = synchronized(this) { stopped = true; pending == 0 }
        if (finished) drained.complete(null)
        return drained
    }
}
