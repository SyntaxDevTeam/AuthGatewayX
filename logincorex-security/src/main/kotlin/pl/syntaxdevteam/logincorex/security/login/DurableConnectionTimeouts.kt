package pl.syntaxdevteam.logincorex.security.login

import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import pl.syntaxdevteam.logincorex.security.executor.BoundedTaskExecutor

/** Serializes durable commits off Netty/game threads, backed by the configured storage. */
class DurableConnectionTimeouts(
    private val gate: ConnectionTimeoutGate,
    private val persist: (List<ConnectionTimeoutGate.Entry>) -> CompletionStage<Unit>,
) : AutoCloseable {
    private val failed = java.util.concurrent.atomic.AtomicBoolean()
    private val writer = BoundedTaskExecutor(1, 128, "logincorex-timeout-store")

    fun timeout(address: InetAddress): CompletableFuture<Int?> {
        val remaining = gate.timeout(address)
        return writer.submit {
            check(!failed.get()) { "Timeout persistence has failed" }
            // Separate bounded writer: storage executor never waits on itself.
            persist(gate.snapshot()).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS)
            remaining
        }.whenComplete { _, failure -> if (failure != null) { failed.set(true); gate.failClosed() } }
    }

    override fun close() {
        check(writer.shutdown(Duration.ofSeconds(30))) { "Timeout persistence did not drain" }
    }
}
