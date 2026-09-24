package pl.syntaxdevteam.authgatewayx.paper.alert

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.alert.OfflineRiskAlert

private const val VIEW_IP_PERMISSION = "authgatewayx.admin.view-ip"
private const val VIEW_GEO_PERMISSION = "authgatewayx.admin.view-geo"

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
        console?.invoke(render(alert, revealIp = true, revealGeo = true))
        recipients().forEach { recipient ->
            entity(recipient, Runnable {
                if (current(alert) && recipient.isOnline && active(recipient) &&
                    recipient.hasPermission("authgatewayx.admin.alerts")
                ) {
                    recipient.sendMessage(render(
                        alert,
                        revealIp = recipient.hasPermission(VIEW_IP_PERMISSION),
                        revealGeo = recipient.hasPermission(VIEW_GEO_PERMISSION),
                    ))
                }
            })
        }
    }

    private fun render(alert: OfflineRiskAlert, revealIp: Boolean, revealGeo: Boolean): Component {
        val session = alert.session
        var message = text.header.withText("{username}", session.username.value)
        message = message.line(text.identity
            .withText("{uuid}", session.minecraftUuid?.toString() ?: "brak danych")
            .withText("{identity}", session.identityType?.name ?: "UNKNOWN")
            .withText("{method}", session.authenticationMethod?.name ?: "UNKNOWN"))

        if (revealIp) message = message.line(text.ip.withText("{ip}", session.sourceAddress.hostAddress))

        alert.report?.takeIf { it.accounts.isNotEmpty() }?.let { report ->
            val accounts = report.accounts.take(5).joinToString(", ") {
                "${it.username.value} (${it.sharedAddressCount} IP)"
            } + if (report.truncated || report.accounts.size > 5) " …" else ""
            message = message.line(text.accounts
                .withText("{count}", report.accounts.size.toString())
                .withText("{accounts}", accounts))
        }

        alert.network?.let { network ->
            val signals = buildList {
                if (network.vpn == true) add("VPN")
                if (network.proxy == true) add("PROXY")
                if (network.tor == true) add("TOR")
            }
            if (signals.isNotEmpty()) {
                message = message.line(text.network
                    .withText("{signals}", signals.joinToString(", "))
                    .withText("{confidence}", network.confidence?.let { "${it}%" } ?: "brak danych")
                    .withText("{risk}", network.riskScore?.let { "${it}%" } ?: "brak danych"))
            }

            if (showGeo && revealGeo && listOf(network.continent, network.country, network.countryCode, network.region, network.city, network.timezone)
                    .any { it != null }) {
                message = message.line(text.geo
                    .withText("{city}", network.city.display())
                    .withText("{region}", network.region.display())
                    .withText("{country}", network.country.display())
                    .withText("{country_code}", network.countryCode.display())
                    .withText("{continent}", network.continent.display())
                    .withText("{timezone}", network.timezone.display()))
            }

            if (showGeo && revealGeo && listOf(network.asn, network.provider, network.organisation, network.networkType).any { it != null }) {
                val owner = listOfNotNull(network.provider, network.organisation).distinct().joinToString(" / ").ifEmpty { "brak danych" }
                message = message.line(text.networkOwner
                    .withText("{asn}", network.asn.display())
                    .withText("{owner}", owner)
                    .withText("{network_type}", network.networkType.display()))
            }

            if (showGeo && revealGeo) network.operatorName?.let {
                message = message.line(text.operator.withText("{operator}", it))
            }
        }

        return message.line(text.hint.withText("{username}", session.username.value))
    }

    private fun Component.line(line: Component): Component = append(Component.newline()).append(line)
    private fun String?.display(): String = this ?: "brak danych"

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )
}

data class RiskAlertText(
    val header: Component,
    val identity: Component,
    val ip: Component,
    val accounts: Component,
    val network: Component,
    val geo: Component,
    val networkOwner: Component,
    val operator: Component,
    val hint: Component,
)
