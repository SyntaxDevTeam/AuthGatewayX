package pl.syntaxdevteam.logincorex.auth.session

import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A bounded, single-use admission gate for authentication in Minecraft's configuration phase.
 *
 * This is deliberately platform-independent. The Paper listener must not allow a connection to
 * enter the game until its *own* ticket finishes with [Outcome.AUTHENTICATED] and the corresponding
 * [SessionRegistry] entry is ACTIVE. Merely reaching AUTHENTICATED here is not a proof of identity.
 *
 * No listener is wired to this class yet. Do not expose it as an opt-in authentication feature until
 * the full pre-join login/registration/premium pipeline is integrated and tested.
 */
class PreJoinConnectionGate(maximumPending: Int) : AutoCloseable {
    enum class Outcome {
        AUTHENTICATED,
        DENIED,
        DISCONNECTED,
        TIMED_OUT,
        SHUTDOWN,
    }

    class Ticket internal constructor(
        val connectionId: ConnectionId,
        internal val result: CompletableFuture<Outcome> = CompletableFuture(),
    )

    private val lock = Any()
    private val capacity = maximumPending
    private val pending = HashMap<ConnectionId, Ticket>()
    private var stopped = false

    init {
        require(maximumPending > 0) { "maximumPending must be positive" }
    }

    /** Returns null when at capacity, stopped, or another connection with this id is pending. */
    fun tryOpen(connectionId: ConnectionId): Ticket? = synchronized(lock) {
        if (stopped || pending.size >= capacity || pending.containsKey(connectionId)) return@synchronized null
        Ticket(connectionId).also { pending[connectionId] = it }
    }

    /**
     * Called only after all authentication and admission checks have succeeded for this ticket.
     * A stale callback (including an earlier connection with the same UUID) has no effect.
     */
    fun authenticated(ticket: Ticket): Boolean = terminate(ticket, Outcome.AUTHENTICATED)

    fun deny(ticket: Ticket): Boolean = terminate(ticket, Outcome.DENIED)

    fun disconnected(ticket: Ticket): Boolean = terminate(ticket, Outcome.DISCONNECTED)

    /**
     * Wait only from an asynchronous configuration event, never on a server tick or region thread.
     * Timeout and interruption both fail closed and release capacity. Other callers can explicitly
     * terminate the ticket on disconnect or shutdown.
     */
    fun await(ticket: Ticket, timeout: Duration): Outcome {
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        return try {
            ticket.result.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            terminate(ticket, Outcome.TIMED_OUT)
            ticket.result.getNow(Outcome.TIMED_OUT)
        } catch (_: InterruptedException) {
            terminate(ticket, Outcome.DISCONNECTED)
            Thread.currentThread().interrupt()
            ticket.result.getNow(Outcome.DISCONNECTED)
        } catch (failure: ExecutionException) {
            // Only this class completes the result, and never exceptionally.
            terminate(ticket, Outcome.DENIED)
            throw IllegalStateException("Unexpected pre-join gate failure", failure)
        }
    }

    /** Used by adapters to reject delayed packets and asynchronous results. */
    fun isPending(ticket: Ticket): Boolean = synchronized(lock) {
        !stopped && pending[ticket.connectionId] === ticket
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun terminate(ticket: Ticket, outcome: Outcome): Boolean {
        // A shutdown wins over any later "authenticated" callback.
        val removed = synchronized(lock) {
            if (stopped || pending[ticket.connectionId] !== ticket) false
            else {
                pending.remove(ticket.connectionId)
                true
            }
        }
        if (removed) ticket.result.complete(outcome)
        return removed
    }

    /** Force all pending connections to fail closed when the plugin is stopping/reloading. */
    override fun close() {
        val toReject = synchronized(lock) {
            if (stopped) emptyList()
            else {
                stopped = true
                pending.values.toList().also { pending.clear() }
            }
        }
        toReject.forEach { it.result.complete(Outcome.SHUTDOWN) }
    }
}
