package pl.syntaxdevteam.logincorex.velocity

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional output: bounded queue, global 120 lines/minute, no logging on event threads. */
class VelocityLoginDiagnostics(private val sink: (String) -> Unit) : AutoCloseable {
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128),
        { task -> Thread(task, "logincorex-login-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())
    private var window = System.nanoTime()
    private var lines = 0

    @Synchronized
    fun record(line: String) {
        val now = System.nanoTime()
        if (now - window >= TimeUnit.MINUTES.toNanos(1)) { window = now; lines = 0 }
        if (lines >= 120 || writer.isShutdown) return
        lines++
        writer.execute { runCatching { sink(line) } }
    }

    override fun close() { writer.shutdownNow() }
}
