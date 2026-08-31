package pl.syntaxdevteam.authgatewayx.security.bot

import pl.syntaxdevteam.authgatewayx.security.flood.NanoTimeSource
import java.net.InetAddress
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class UsernameBurstPolicy(
    val maximumDistinctUsernames: Int,
    val maximumPreAuthDisconnects: Int,
    val observationWindow: Duration,
    val quarantineDuration: Duration,
    val maximumTrackedAddresses: Int,
)

enum class UsernameBurstDecision {
    ALLOW,
    DENY_QUARANTINED,
    DENY_USERNAME_BURST,
    DENY_RECONNECT_LOOP,
    DENY_TRACKING_CAPACITY,
}

/**
 * A cheap, bounded guard executed before storage, password hashing and external HTTP.
 * State is deliberately local and contains no account credentials or persistent identity data.
 */
class UsernameBurstGate(
    private val policy: UsernameBurstPolicy,
    private val timeSource: NanoTimeSource = NanoTimeSource(System::nanoTime),
) {
    private val states = ConcurrentHashMap<InetAddress, AddressState>()
    private val windowNanos = policy.observationWindow.toNanos()
    private val quarantineNanos = policy.quarantineDuration.toNanos()

    init {
        require(policy.maximumDistinctUsernames > 0) { "Distinct username limit must be positive" }
        require(policy.maximumPreAuthDisconnects > 0) { "Pre-auth disconnect limit must be positive" }
        require(windowNanos > 0) { "Observation window must be positive" }
        require(quarantineNanos > 0) { "Quarantine duration must be positive" }
        require(policy.maximumTrackedAddresses > 0) { "Tracked address limit must be positive" }
    }

    fun evaluate(address: InetAddress, username: String): UsernameBurstDecision {
        val now = timeSource.now()
        val state = states[address] ?: createState(address, now)
            ?: return UsernameBurstDecision.DENY_TRACKING_CAPACITY
        return state.evaluate(username.lowercase(Locale.ROOT), now)
    }

    fun trackedAddressCount(): Int = states.size

    /** Records only disconnects that happened while the connection was still in PRE_AUTH. */
    fun recordPreAuthDisconnect(address: InetAddress): UsernameBurstDecision {
        val state = states[address] ?: return UsernameBurstDecision.ALLOW
        return state.recordPreAuthDisconnect(timeSource.now())
    }

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
        private var preAuthDisconnects = 0
        private val usernames = HashSet<String>()

        @Synchronized
        fun evaluate(username: String, now: Long): UsernameBurstDecision {
            lastSeenAt = now
            quarantineStartedAt?.let { startedAt ->
                if (elapsed(now, startedAt) < quarantineNanos) return UsernameBurstDecision.DENY_QUARANTINED
                quarantineStartedAt = null
                windowStartedAt = now
            }
            if (elapsed(now, windowStartedAt) >= windowNanos) {
                resetWindow(now)
            }
            usernames += username
            if (usernames.size <= policy.maximumDistinctUsernames) return UsernameBurstDecision.ALLOW
            quarantineStartedAt = now
            usernames.clear()
            return UsernameBurstDecision.DENY_USERNAME_BURST
        }

        @Synchronized
        fun recordPreAuthDisconnect(now: Long): UsernameBurstDecision {
            lastSeenAt = now
            if (quarantineStartedAt?.let { elapsed(now, it) < quarantineNanos } == true) {
                return UsernameBurstDecision.DENY_QUARANTINED
            }
            if (elapsed(now, windowStartedAt) >= windowNanos) resetWindow(now)
            preAuthDisconnects++
            if (preAuthDisconnects <= policy.maximumPreAuthDisconnects) return UsernameBurstDecision.ALLOW
            quarantineStartedAt = now
            usernames.clear()
            return UsernameBurstDecision.DENY_RECONNECT_LOOP
        }

        @Synchronized
        fun isExpired(now: Long): Boolean {
            val quarantineActive = quarantineStartedAt?.let { elapsed(now, it) < quarantineNanos } ?: false
            return !quarantineActive && elapsed(now, lastSeenAt) >= windowNanos
        }

        private fun resetWindow(now: Long) {
            windowStartedAt = now
            usernames.clear()
            preAuthDisconnects = 0
        }
    }

    private fun elapsed(now: Long, before: Long): Long = if (now >= before) now - before else Long.MAX_VALUE
}
