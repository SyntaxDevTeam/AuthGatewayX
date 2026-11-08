package pl.syntaxdevteam.authgatewayx.auth.alert

import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import pl.syntaxdevteam.authgatewayx.integrations.network.*
import pl.syntaxdevteam.authgatewayx.storage.*
import java.net.InetAddress
import java.time.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class OfflineRiskAlertsTest {
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private fun session(identity: IdentityType = IdentityType.OFFLINE): AuthSession = AuthSession.connecting(
        ConnectionId.random(), AccountUsername.parse("Player"), InetAddress.getLoopbackAddress(), now,
    ).enterPreAuth().activate(AccountId.random(), UUID.randomUUID(), identity,
        if (identity == IdentityType.OFFLINE) AuthenticationMethod.PASSWORD else AuthenticationMethod.MOJANG, now)
    private class Harness {
        var calls = 0
        var networkCalls = 0
        var current = true
        var report = CompletableFuture<MultiAccountReport?>()
        var ip = CompletableFuture<IpIntelligence?>()
        val alerts = mutableListOf<OfflineRiskAlert>()
        val lookup = object : MultiAccountLookup {
            override fun findRelatedOfflineAccounts(username: AccountUsername, observedAt: Instant) = report.also { calls++ }
        }
        fun service(multi: Boolean = true, vpn: Boolean = true, enabled: Boolean = true, maximum: Int = 10) = OfflineRiskAlerts(
            lookup, if (enabled) IpIntelligenceLookup { networkCalls++; ip } else null,
            multi, vpn, Duration.ofSeconds(300), maximum, 1, { current }, { alerts.add(it) },
        )
        fun linked() = MultiAccountReport(listOf(RelatedOfflineAccount(AccountUsername.parse("Other"), 1)), false)
    }

    @Test
    fun `only authenticated offline sessions start checks and disabled means no work`() {
        val h = Harness(); val service = h.service()
        service.observe(session(IdentityType.MOJANG))
        service.observe(AuthSession.connecting(ConnectionId.random(), AccountUsername.parse("Player"), InetAddress.getLoopbackAddress(), now).enterPreAuth())
        h.service(multi = false, enabled = false).observe(session())
        assertEquals(0, h.calls); assertEquals(0, h.networkCalls)
    }

    @Test
    fun `local links survive provider failure and do not wait in authentication`() {
        val h = Harness(); val service = h.service(); val player = session()
        service.observe(player)
        assertTrue(h.alerts.isEmpty())
        h.report.complete(h.linked())
        h.ip.completeExceptionally(IllegalStateException("timeout"))
        assertEquals(1, h.alerts.size)
        assertNull(h.alerts.single().network)
        service.observe(player)
        assertEquals(1, h.calls)
    }

    @Test
    fun `VPN alert is independent of multi account detection`() {
        val h = Harness(); val service = h.service(multi = false)
        service.observe(session())
        h.ip.complete(IpIntelligence(true, false, false, "DE", "AS1"))
        assertEquals(0, h.calls)
        assertEquals(1, h.alerts.size)
        assertNull(h.alerts.single().report)
    }

    @Test
    fun `geo only and negative network results are not allegations`() {
        val h = Harness(); val service = h.service()
        service.observe(session())
        h.report.complete(MultiAccountReport(emptyList(), false))
        h.ip.complete(IpIntelligence(false, null, false, "PL", "AS1"))
        assertTrue(h.alerts.isEmpty())
    }

    @Test
    fun `concurrency and cooldown registry bounds reject before queries`() {
        val h = Harness(); val service = h.service(maximum = 1)
        service.observe(session()); service.observe(session())
        assertEquals(1, h.calls)
        h.report.complete(h.linked()); h.ip.complete(null)
        service.observe(session())
        assertEquals(1, h.calls)
    }

    @Test
    fun `disconnected or replaced sessions and shutdown suppress stale results`() {
        val h = Harness(); val service = h.service()
        service.observe(session()); h.current = false
        h.report.complete(h.linked()); h.ip.complete(null)
        assertTrue(h.alerts.isEmpty())
        val stopped = Harness(); val stopping = stopped.service()
        stopping.observe(session()); stopping.close()
        stopped.report.complete(stopped.linked()); stopped.ip.complete(null)
        assertTrue(stopped.alerts.isEmpty())
        stopping.observe(session()); assertEquals(1, stopped.calls)
    }
}
