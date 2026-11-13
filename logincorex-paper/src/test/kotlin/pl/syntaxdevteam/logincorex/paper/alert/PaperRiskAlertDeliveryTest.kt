package pl.syntaxdevteam.logincorex.paper.alert

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import pl.syntaxdevteam.logincorex.auth.alert.OfflineRiskAlert
import pl.syntaxdevteam.logincorex.domain.account.*
import pl.syntaxdevteam.logincorex.domain.session.*
import pl.syntaxdevteam.logincorex.integrations.network.IpIntelligence
import pl.syntaxdevteam.logincorex.storage.*
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PaperRiskAlertDeliveryTest {
    @Test
    fun `delivery rechecks staff permissions authentication and origin session on entity scheduler`() {
        val now = Instant.now()
        val session = AuthSession.connecting(ConnectionId.random(), AccountUsername.parse("Player"), InetAddress.getLoopbackAddress(), now)
            .enterPreAuth().activate(AccountId.random(), UUID.randomUUID(), IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now)
        val alert = OfflineRiskAlert(session, MultiAccountReport(listOf(RelatedOfflineAccount(AccountUsername.parse("Other"), 1)), false), null)
        var permission = true; var active = true; var current = true
        var sends = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) { "isOnline" -> true; "hasPermission" -> permission; "sendMessage" -> { sends++; null }; else -> null }
        } as Player
        val tasks = mutableListOf<Runnable>()
        val delivery = PaperRiskAlertDelivery({ listOf(player) }, { _, task -> tasks.add(task) }, { active }, { current }, null,
            RiskAlertText(
                Component.text("<username>"),
                Component.text("<uuid> <identity> <method>"),
                Component.text("<ip>"),
                Component.text("<count> <accounts>"),
                Component.text("<signals> <confidence> <risk>"),
                Component.text("<city> <region> <country> <country_code> <continent> <timezone>"),
                Component.text("<asn> <owner> <network_type>"),
                Component.text("<operator>"),
                Component.text("<username>"),
            ), true)
        delivery.deliver(alert)
        assertEquals(0, sends)
        permission = false; tasks.removeFirst().run(); assertEquals(0, sends)
        permission = true; delivery.deliver(alert)
        active = false; tasks.removeFirst().run(); assertEquals(0, sends)
        active = true; delivery.deliver(alert)
        current = false; tasks.removeFirst().run(); assertEquals(0, sends)
        current = true; delivery.deliver(alert)
        tasks.removeFirst().run(); assertEquals(1, sends)
    }
    @Test
    fun `IP and GeoIP disclosures require independent permissions`() {
        val now = Instant.now()
        val session = AuthSession.connecting(
            ConnectionId.random(),
            AccountUsername.parse("Player"),
            InetAddress.getLoopbackAddress(),
            now,
        ).enterPreAuth().activate(
            AccountId.random(),
            UUID.randomUUID(),
            IdentityType.OFFLINE,
            AuthenticationMethod.PASSWORD,
            now,
        )
        val alert = OfflineRiskAlert(
            session,
            MultiAccountReport(listOf(RelatedOfflineAccount(AccountUsername.parse("LinkedAccount"), 1)), false),
            IpIntelligence(
                vpn = true,
                proxy = false,
                tor = false,
                countryCode = "PL",
                asn = "AS123",
                continent = "Europe",
                country = "Poland",
                region = "Mazowieckie",
                city = "Warsaw",
                timezone = "Europe/Warsaw",
                provider = "Example ISP",
                organisation = "Example Network",
                networkType = "Residential",
                operatorName = "Example VPN",
                confidence = 90,
                riskScore = 70,
            ),
        )

        val permissions = mutableMapOf(
            "logincorex.admin.alerts" to true,
            "logincorex.admin.view-ip" to false,
            "logincorex.admin.view-geo" to false,
        )
        var sent: Component? = null
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "isOnline" -> true
                "hasPermission" -> permissions[args!![0] as String] ?: false
                "sendMessage" -> { sent = args!![0] as Component; null }
                else -> null
            }
        } as Player
        val tasks = mutableListOf<Runnable>()
        val delivery = PaperRiskAlertDelivery(
            { listOf(player) },
            { _, task -> tasks.add(task) },
            { true },
            { true },
            null,
            RiskAlertText(
                Component.text("HEADER"),
                Component.text("IDENTITY"),
                Component.text("IP=<ip>"),
                Component.text("ACCOUNTS=<accounts>"),
                Component.text("RISK=<signals>"),
                Component.text("GEO=<city>"),
                Component.text("OWNER=<asn>"),
                Component.text("OP=<operator>"),
                Component.text("HINT"),
            ),
            true,
        )

        fun deliverText(): String {
            sent = null
            delivery.deliver(alert)
            tasks.removeFirst().run()
            return requireNotNull(sent).toString()
        }

        var rendered = deliverText()
        assertFalse(rendered.contains("RISK="))
        assertFalse(rendered.contains("IP="))
        assertFalse(rendered.contains("GEO="))
        assertFalse(rendered.contains("OWNER="))
        assertFalse(rendered.contains("OP="))
        assertFalse(rendered.contains("LinkedAccount"))

        permissions["logincorex.admin.view-ip"] = true
        rendered = deliverText()
        assertContains(rendered, "IP=")
        assertFalse(rendered.contains("GEO="))

        permissions["logincorex.admin.view-ip"] = false
        permissions["logincorex.admin.view-geo"] = true
        rendered = deliverText()
        assertFalse(rendered.contains("IP="))
        assertContains(rendered, "RISK=")
        assertContains(rendered, "GEO=")
        assertContains(rendered, "OWNER=")
        assertContains(rendered, "OP=")
        assertFalse(rendered.contains("LinkedAccount"))
        permissions["logincorex.admin.alts"] = true
        assertContains(deliverText(), "LinkedAccount")
        delivery.deliver(alert)
        permissions["logincorex.admin.view-geo"] = false
        permissions["logincorex.admin.alts"] = false
        sent = null
        tasks.removeFirst().run()
        rendered = requireNotNull(sent).toString()
        assertFalse(rendered.contains("RISK="))
        assertFalse(rendered.contains("LinkedAccount"))
    }

}
