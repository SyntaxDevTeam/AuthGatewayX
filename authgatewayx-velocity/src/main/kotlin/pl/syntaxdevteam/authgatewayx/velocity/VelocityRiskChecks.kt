package pl.syntaxdevteam.authgatewayx.velocity

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligence
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligenceLookup
import pl.syntaxdevteam.authgatewayx.storage.ConnectionAccountLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class RiskAction { DISABLED, ALERT, DENY }
enum class RiskDenial { VPN, MULTI_ACCOUNT, UNAVAILABLE }
data class ProxyRiskResult(val report: MultiAccountReport?, val network: IpIntelligence?, val denial: RiskDenial?, val unavailable: Boolean) {
    val suspicious: Boolean get() = report?.accounts?.isNotEmpty() == true || network?.suspicious == true
}

class VelocityRiskChecks(
    private val accounts: ConnectionAccountLookup?,
    private val network: IpIntelligenceLookup?,
    private val multiAction: RiskAction,
    private val networkAction: RiskAction,
    private val failClosed: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun assess(address: InetAddress, username: AccountUsername, offline: Boolean): CompletionStage<ProxyRiskResult> {
        val checkAccounts = offline && accounts != null && multiAction != RiskAction.DISABLED
        val checkNetwork = network != null && networkAction != RiskAction.DISABLED
        val links = safe<MultiAccountReport> { if (checkAccounts) accounts.findOfflineAccountsByAddress(address, username, clock.instant()).thenApply<MultiAccountReport?> { it } else CompletableFuture.completedFuture(null) }
        val ip = safe<IpIntelligence> { if (checkNetwork) network.lookup(address) else CompletableFuture.completedFuture(null) }
        return links.thenCombine(ip) { report, intelligence ->
            val unavailable = checkAccounts && report == null || checkNetwork &&
                (intelligence == null || intelligence.vpn == null || intelligence.proxy == null || intelligence.tor == null)
            val denial = when {
                networkAction == RiskAction.DENY && intelligence?.suspicious == true -> RiskDenial.VPN
                multiAction == RiskAction.DENY && report?.accounts?.isNotEmpty() == true -> RiskDenial.MULTI_ACCOUNT
                unavailable && failClosed -> RiskDenial.UNAVAILABLE
                else -> null
            }
            ProxyRiskResult(report, intelligence, denial, unavailable)
        }
    }
    private fun <T> safe(action: () -> CompletionStage<T?>): CompletionStage<T?> =
        try { action().handle { value, failure -> if (failure == null) value else null } }
        catch (_: Exception) { CompletableFuture.completedFuture(null) }
}
