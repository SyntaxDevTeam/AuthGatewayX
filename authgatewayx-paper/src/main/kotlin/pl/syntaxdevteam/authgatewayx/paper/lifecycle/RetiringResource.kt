package pl.syntaxdevteam.authgatewayx.paper.lifecycle

import java.util.concurrent.atomic.AtomicBoolean

/** stopAccepting never waits for I/O or for a concurrent constructor to finish. */
internal class RetiringResource<T : AutoCloseable> : AutoCloseable {
    private val stopping = AtomicBoolean(false)
    private var resource: T? = null

    fun stopAccepting() { stopping.set(true) }

    fun attach(value: T) {
        val accepted = synchronized(this) {
            if (stopping.get()) false
            else {
                check(resource == null) { "Resource already initialized" }
                resource = value
                true
            }
        }
        if (!accepted) {
            value.close()
            error("Runtime stopped during resource initialization")
        }
    }

    override fun close() {
        stopAccepting()
        val retiring = synchronized(this) { resource.also { resource = null } }
        retiring?.close()
    }
}
