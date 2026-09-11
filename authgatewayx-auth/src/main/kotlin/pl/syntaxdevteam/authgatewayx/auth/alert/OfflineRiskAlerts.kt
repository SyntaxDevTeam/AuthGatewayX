package pl.syntaxdevteam.authgatewayx.auth.alert

import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligence
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligenceLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class OfflineRiskAlerts(
    private val accounts: MultiAccountLookup,
    private val network: IpIntelligenceLookup?,
    private val multiAccountEnabled: Boolean,
    private val vpnAlertsEnabled: Boolean,
    private val cooldown: Duration,
    private val maximumTrackedAccounts: Int,
    private val maximumConcurrent: Int,
    private val isCurrent: (AuthSession) -> Boolean,
    private val emit: (OfflineRiskAlert) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val observed = LinkedHashMap<UUID, Instant>()
    private var inFlight = 0
    private var closed = false

    init {
        require(cooldown > Duration.ZERO && maximumTrackedAccounts in 1..100_000 && maximumConcurrent in 1..32)
    }

    /** Advisory work is never awaited by authentication and never throws into the auth pipeline. */
    fun observe(session: AuthSession) {
        if (session.state != ConnectionState.ACTIVE || session.identityType != IdentityType.OFFLINE ||
            (!multiAccountEnabled && (network == null || !vpnAlertsEnabled))) return
        synchronized(this) {
            if (closed || !isCurrent(session) || inFlight >= maximumConcurrent) return
            val now = clock.instant()
            val id = session.accountId!!.value
            if (observed[id]?.isAfter(now) == true) return
            observed.remove(id)
            if (observed.size >= maximumTrackedAccounts) observed.entries.removeIf { !it.value.isAfter(now) }
            if (observed.size >= maximumTrackedAccounts) return
            observed[id] = now.plus(cooldown)
            inFlight++
        }
        val report = safe<MultiAccountReport> { if (multiAccountEnabled) accounts.findRelatedOfflineAccounts(session.username, clock.instant())
            else CompletableFuture.completedFuture(null) }
        val intelligence = safe<IpIntelligence> { network?.lookup(session.sourceAddress) ?: CompletableFuture.completedFuture(null) }
        report.thenCombine(intelligence) { links, ip -> OfflineRiskAlert(session, links, ip) }.whenComplete { alert, failure ->
            synchronized(this) {
                inFlight--
                if (!closed && failure == null && isCurrent(session) &&
                    (alert.report?.accounts?.isNotEmpty() == true || vpnAlertsEnabled && alert.network?.suspicious == true)) {
                    runCatching { emit(alert) }
                }
            }
        }
    }

    private fun <T> safe(action: () -> CompletionStage<T?>): CompletionStage<T?> =
        try { action().handle { value, failure -> if (failure == null) value else null } }
        catch (_: Exception) { CompletableFuture.completedFuture(null) }

    @Synchronized
    override fun close() { closed = true; observed.clear() }
}

data class OfflineRiskAlert(val session: AuthSession, val report: MultiAccountReport?, val network: IpIntelligence?)
