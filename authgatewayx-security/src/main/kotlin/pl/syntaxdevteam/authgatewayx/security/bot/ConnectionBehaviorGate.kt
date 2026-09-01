package pl.syntaxdevteam.authgatewayx.security.bot

import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class ConnectionBehaviorPolicy(
    val threshold: Int,
    val connectionWeight: Int,
    val distinctUsernameWeight: Int,
    val authenticationFailureWeight: Int,
    val preAuthDisconnectWeight: Int,
    val observationWindow: Duration,
    val quarantineDuration: Duration,
    val maximumTrackedAddresses: Int,
)

enum class ConnectionBehaviorSignal {
    AUTHENTICATION_FAILURE,
    PRE_AUTH_DISCONNECT,
}

enum class ConnectionBehaviorDecision {
    ALLOW,
    DENY_QUARANTINED,
    DENY_SCORE_THRESHOLD,
    DENY_TRACKING_CAPACITY,
}

/**
 * Bounded, local behavioral scoring for connection attempts.
 *
 * Only an admitted connection may allocate state. Late callbacks such as authentication failures
 * and PRE_AUTH disconnects update an existing state only, so they cannot grow the registry after
 * the connection that created the state has already disappeared.
 */
class ConnectionBehaviorGate(
    private val policy: ConnectionBehaviorPolicy,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val states = ConcurrentHashMap<InetAddress, AddressState>()
    private val windowNanos = policy.observationWindow.toNanos()
    private val quarantineNanos = policy.quarantineDuration.toNanos()

    init {
        require(policy.threshold > 0) { "Behavior score threshold must be positive" }
        require(policy.connectionWeight > 0) { "Connection weight must be positive" }
        require(policy.distinctUsernameWeight > 0) { "Distinct username weight must be positive" }
        require(policy.authenticationFailureWeight > 0) { "Authentication failure weight must be positive" }
        require(policy.preAuthDisconnectWeight > 0) { "PRE_AUTH disconnect weight must be positive" }
        require(windowNanos > 0) { "Observation window must be positive" }
        require(quarantineNanos > 0) { "Quarantine duration must be positive" }
        require(policy.maximumTrackedAddresses > 0) { "Tracked address limit must be positive" }
    }

    fun evaluateConnection(address: InetAddress, username: String): ConnectionBehaviorDecision {
        val now = timeSource.now()
        val state = states[address] ?: createState(address, now)
            ?: return ConnectionBehaviorDecision.DENY_TRACKING_CAPACITY
        return state.evaluateConnection(username.lowercase(Locale.ROOT), now)
    }

    /** Updates an existing admitted address only. It intentionally never allocates new state. */
    fun record(address: InetAddress, signal: ConnectionBehaviorSignal): ConnectionBehaviorDecision {
        val state = states[address] ?: return ConnectionBehaviorDecision.ALLOW
        return state.record(signal, timeSource.now())
    }

    fun trackedAddressCount(): Int = states.size

    fun clear() = states.clear()

    @Synchronized
    private fun createState(address: InetAddress, now: Long): AddressState? {
        states[address]?.let { return it }
        if (states.size >= policy.maximumTrackedAddresses) {
            states.entries.removeIf { (_, state) -> state.isExpired(now) }
        }
        if (states.size >= policy.maximumTrackedAddresses) return null
        return AddressState(now).also { states[address] = it }
    }

    private inner class AddressState(createdAt: Long) {
        private var windowStartedAt = createdAt
        private var lastSeenAt = createdAt
        private var quarantineStartedAt: Long? = null
        private var score = 0
        private val usernames = HashSet<String>()

        @Synchronized
        fun evaluateConnection(username: String, now: Long): ConnectionBehaviorDecision {
            prepareWindow(now)?.let { return it }
            score = saturatedAdd(score, policy.connectionWeight)
            if (usernames.add(username)) {
                score = saturatedAdd(score, policy.distinctUsernameWeight)
            }
            return thresholdDecision(now)
        }

        @Synchronized
        fun record(signal: ConnectionBehaviorSignal, now: Long): ConnectionBehaviorDecision {
            prepareWindow(now)?.let { return it }
            val weight = when (signal) {
                ConnectionBehaviorSignal.AUTHENTICATION_FAILURE -> policy.authenticationFailureWeight
                ConnectionBehaviorSignal.PRE_AUTH_DISCONNECT -> policy.preAuthDisconnectWeight
            }
            score = saturatedAdd(score, weight)
            return thresholdDecision(now)
        }

        @Synchronized
        fun isExpired(now: Long): Boolean {
            val quarantineActive = quarantineStartedAt?.let { elapsed(now, it) < quarantineNanos } ?: false
            return !quarantineActive && elapsed(now, lastSeenAt) >= windowNanos
        }

        private fun prepareWindow(now: Long): ConnectionBehaviorDecision? {
            lastSeenAt = now
            quarantineStartedAt?.let { startedAt ->
                if (elapsed(now, startedAt) < quarantineNanos) {
                    return ConnectionBehaviorDecision.DENY_QUARANTINED
                }
                quarantineStartedAt = null
                resetWindow(now)
            }
            if (elapsed(now, windowStartedAt) >= windowNanos) resetWindow(now)
            return null
        }

        private fun thresholdDecision(now: Long): ConnectionBehaviorDecision {
            if (score < policy.threshold) return ConnectionBehaviorDecision.ALLOW
            quarantineStartedAt = now
            return ConnectionBehaviorDecision.DENY_SCORE_THRESHOLD
        }

        private fun resetWindow(now: Long) {
            windowStartedAt = now
            lastSeenAt = now
            score = 0
            usernames.clear()
        }
    }

    private fun saturatedAdd(current: Int, increment: Int): Int =
        if (current > Int.MAX_VALUE - increment) Int.MAX_VALUE else current + increment

    private fun elapsed(now: Long, before: Long): Long = if (now >= before) now - before else Long.MAX_VALUE
}
