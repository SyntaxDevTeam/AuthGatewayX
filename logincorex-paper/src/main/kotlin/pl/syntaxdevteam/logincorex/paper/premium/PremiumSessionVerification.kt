package pl.syntaxdevteam.logincorex.paper.premium

import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs only on a bounded authentication worker, never on Netty or a game thread. */
internal class PremiumSessionVerification(
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun <T : Any> verify(
        startedNanos: Long,
        active: () -> Boolean,
        onRetry: () -> Unit = {},
        lookup: () -> T?,
    ): T? {
        fun checkActive() {
            if (!active() || Thread.currentThread().isInterrupted) throw CancellationException()
            if (nanoTime() - startedNanos >= TIMEOUT_NANOS) throw TimeoutException()
        }
        for (attempt in 0..2) {
            checkActive()
            try {
                val result = lookup()
                checkActive()
                return result // A negative session result is final, never retried.
            } catch (failure: AuthenticationUnavailableException) {
                checkActive()
                if (attempt == 2) throw failure
                val delayMillis = (attempt + 1) * 1000L
                if (TIMEOUT_NANOS - (nanoTime() - startedNanos) <= TimeUnit.MILLISECONDS.toNanos(delayMillis)) {
                    throw TimeoutException()
                }
                onRetry()
                pause(delayMillis)
            }
        }
        error("Unreachable")
    }

    companion object {
        const val TIMEOUT_SECONDS = 12L
        val TIMEOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
    }
}

/** No queue: an HTTP call ignoring interruption still consumes a worker until it exits. */
internal fun premiumSessionWorkers(maximum: Int) = java.util.concurrent.ThreadPoolExecutor(
    0, maximum, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    Thread.ofPlatform().daemon(true).name("lcx-session-verification-", 0).factory(),
    java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
)
