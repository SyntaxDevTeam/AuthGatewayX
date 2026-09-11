package pl.syntaxdevteam.authgatewayx.paper.alert

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.alert.OfflineRiskAlert
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import pl.syntaxdevteam.authgatewayx.storage.*
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
            RiskAlertText(Component.text("{username}"), Component.text("{accounts}"), Component.text("{signals}"), Component.text("{country}")), true)
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
}
