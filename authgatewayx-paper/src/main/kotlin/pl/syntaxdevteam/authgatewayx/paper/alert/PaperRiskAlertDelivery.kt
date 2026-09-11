package pl.syntaxdevteam.authgatewayx.paper.alert

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.alert.OfflineRiskAlert

/** API reads and sends happen on each recipient's entity scheduler. */
class PaperRiskAlertDelivery(
    private val recipients: () -> Collection<Player>,
    private val entity: (Player, Runnable) -> Unit,
    private val active: (Player) -> Boolean,
    private val current: (OfflineRiskAlert) -> Boolean,
    private val console: ((Component) -> Unit)?,
    private val text: RiskAlertText,
    private val showGeo: Boolean,
) {
    fun deliver(alert: OfflineRiskAlert) {
        if (!current(alert)) return
        var message = text.header.withText("{username}", alert.session.username.value)
        alert.report?.takeIf { it.accounts.isNotEmpty() }?.let { report ->
            val names = report.accounts.take(5).joinToString(", ") { it.username.value }
            val suffix = if (report.truncated || report.accounts.size > 5) " …" else ""
            message = message.append(Component.newline()).append(text.accounts.withText("{accounts}", names + suffix))
        }
        alert.network?.let { network ->
            val signals = buildList {
                if (network.vpn == true) add("VPN")
                if (network.proxy == true) add("PROXY")
                if (network.tor == true) add("TOR")
            }
            if (signals.isNotEmpty()) message = message.append(Component.newline())
                .append(text.network.withText("{signals}", signals.joinToString(", ")))
        }
        if (showGeo) message = message.append(Component.newline()).append(text.geo
            .withText("{country}", alert.network?.countryCode ?: "?").withText("{asn}", alert.network?.asn ?: "?"))
        val rendered = message
        console?.invoke(rendered)
        recipients().forEach { recipient -> entity(recipient, Runnable {
            if (current(alert) && recipient.isOnline && active(recipient) && recipient.hasPermission("authgatewayx.admin.alerts")) {
                recipient.sendMessage(rendered)
            }
        }) }
    }

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )
}

data class RiskAlertText(val header: Component, val accounts: Component, val network: Component, val geo: Component)
