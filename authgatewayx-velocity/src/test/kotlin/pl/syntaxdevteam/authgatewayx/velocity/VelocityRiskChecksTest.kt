package pl.syntaxdevteam.authgatewayx.velocity

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.integrations.network.*
import pl.syntaxdevteam.authgatewayx.storage.*
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class VelocityRiskChecksTest {
    private val name = AccountUsername.parse("NewName")
    private val address = InetAddress.getLoopbackAddress()
    private val links = MultiAccountReport(listOf(RelatedOfflineAccount(AccountUsername.parse("OldName"),1)),false)
    private val vpn = IpIntelligence(true,false,false,"PL","AS1")
    private class Storage(val report: MultiAccountReport?) : ConnectionAccountLookup {
        var calls = 0
        override fun verifyHistorySchema() = CompletableFuture.completedFuture(Unit)
        override fun findRelatedOfflineAccounts(username: AccountUsername, observedAt: Instant) = CompletableFuture.completedFuture(report)
        override fun findOfflineAccountsByAddress(address: InetAddress, excluding: AccountUsername, at: Instant): CompletableFuture<MultiAccountReport> {
            calls++
            return report?.let { CompletableFuture.completedFuture(it) } ?: CompletableFuture.failedFuture(IllegalStateException("unavailable"))
        }
    }
    @Test fun `actions distinguish alerts and denial before backend admission`() {
        for (action in RiskAction.entries) {
            val storage = Storage(links)
            val checks = VelocityRiskChecks(storage,null,action,RiskAction.DISABLED,true)
            val result = checks.assess(address,name,true).toCompletableFuture().get()
            assertEquals(if(action == RiskAction.DENY) RiskDenial.MULTI_ACCOUNT else null,result.denial)
            assertEquals(if(action == RiskAction.DISABLED) 0 else 1,storage.calls)
        }
    }
    @Test fun `premium skips offline account matching but is still subject to VPN policy`() {
        val storage = Storage(links)
        val checks = VelocityRiskChecks(storage,IpIntelligenceLookup { CompletableFuture.completedFuture(vpn) },RiskAction.DENY,RiskAction.DENY,true)
        assertEquals(RiskDenial.VPN,checks.assess(address,name,false).toCompletableFuture().get().denial)
        assertEquals(0,storage.calls)
    }
    @Test fun `unknown failed and overloaded sources follow explicit failure strategy`() {
        for (closed in listOf(true,false)) {
            val checks = VelocityRiskChecks(Storage(null),IpIntelligenceLookup { CompletableFuture.completedFuture(null) },RiskAction.ALERT,RiskAction.DENY,closed)
            val result = checks.assess(address,name,true).toCompletableFuture().get()
            assertTrue(result.unavailable)
            assertEquals(if(closed) RiskDenial.UNAVAILABLE else null,result.denial)
        }
    }
    @Test fun `geo alone is not a block and disabled providers do no work`() {
        val checks = VelocityRiskChecks(null,IpIntelligenceLookup { CompletableFuture.completedFuture(IpIntelligence(false,false,false,"RU","AS1")) },RiskAction.DENY,RiskAction.DENY,true)
        assertNull(checks.assess(address,name,true).toCompletableFuture().get().denial)
        val disabled = VelocityRiskChecks(null,IpIntelligenceLookup { error("must not call") },RiskAction.DISABLED,RiskAction.DISABLED,true)
        assertFalse(disabled.assess(address,name,true).toCompletableFuture().get().unavailable)
    }
}
