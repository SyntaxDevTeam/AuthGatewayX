package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountLookup
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

private const val VIEW_IP_PERMISSION = "authgatewayx.admin.view-ip"
private const val VIEW_GEO_PERMISSION = "authgatewayx.admin.view-geo"

class VelocityRiskAdmin(
    private val proxy: ProxyServer,
    private val accounts: MultiAccountLookup?,
    private val proof: VelocityStaffProof,
    private val ready: () -> Boolean,
    private val text: (String) -> Component,
    private val cooldownSeconds: Long,
    private val consoleAlerts: Boolean,
    private val showGeo: Boolean,
    private val alertsEnabled: Boolean,
) : SimpleCommand {
    private val busy = AtomicBoolean()
    private val cooldowns = LinkedHashMap<String, Instant>()
    private var nextAlert = Instant.MIN

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.arguments().firstOrNull()?.equals("alts", true) == true

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (!ready() || !source.hasPermission("authgatewayx.admin.alts")) return
        val name = invocation.arguments().takeIf { it.size == 2 }?.get(1)
            ?.let { runCatching { AccountUsername.parse(it) }.getOrNull() }
        if (name == null) { source.sendMessage(text("usage").fill("argument", "<nick>")); return }
        if (accounts == null || !busy.compareAndSet(false, true)) { source.sendMessage(text("unavailable")); return }
        authorize(source).whenComplete firstProof@ { authorized, authFailure ->
            if (authorized != true || authFailure != null || !ready()) {
                busy.set(false)
                if (ready()) source.sendMessage(text("auth_required"))
                return@firstProof
            }
            val query = try { accounts.findRelatedOfflineAccounts(name, Instant.now()) }
            catch (_: Exception) {
                CompletableFuture.failedFuture<pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport?>(
                    IllegalStateException("Report unavailable"),
                )
            }
            query.whenComplete { report, failure ->
                authorize(source).whenComplete finalProof@ { confirmed, proofFailure ->
                    try {
                        if (!ready() || confirmed != true || proofFailure != null ||
                            !source.hasPermission("authgatewayx.admin.alts")) return@finalProof
                        when {
                            failure != null -> source.sendMessage(text("unavailable"))
                            report == null -> source.sendMessage(text("not_found"))
                            else -> {
                                source.sendMessage(text("header").fill("username", name.value))
                                if (report.accounts.isEmpty()) source.sendMessage(text("empty"))
                                report.accounts.forEach {
                                    source.sendMessage(text("entry").fill("username", it.username.value)
                                        .fill("count", it.sharedAddressCount.toString()))
                                }
                                if (report.truncated) source.sendMessage(text("truncated"))
                            }
                        }
                    } finally { busy.set(false) }
                }
            }
        }
    }

    private fun authorize(source: CommandSource): CompletionStage<Boolean> = when (source) {
        is ConsoleCommandSource -> CompletableFuture.completedFuture(true)
        is Player -> proof.authorize(source)
        else -> CompletableFuture.completedFuture(false)
    }

    @Synchronized
    fun notify(username: String, address: InetAddress, risk: ProxyRiskResult, denied: Boolean) {
        if (!ready() || !alertsEnabled || (!risk.suspicious && !risk.unavailable)) return
        val now = Instant.now()
        if (now < nextAlert || cooldowns[username]?.isAfter(now) == true) return
        cooldowns.entries.removeIf { !it.value.isAfter(now) }
        if (cooldowns.size >= 10_000) return
        cooldowns[username] = now.plusSeconds(cooldownSeconds)
        nextAlert = now.plusSeconds(1)

        if (consoleAlerts) proxy.consoleCommandSource.sendMessage(
            render(username, address, risk, denied, revealIp = true, revealGeo = true),
        )
        proxy.allPlayers.filter { it.isActive && it.hasPermission("authgatewayx.admin.alerts") }.forEach { recipient ->
            proof.authorize(recipient).thenAccept { authorized ->
                if (authorized && ready() && recipient.isActive && recipient.hasPermission("authgatewayx.admin.alerts")) {
                    recipient.sendMessage(render(
                        username,
                        address,
                        risk,
                        denied,
                        revealIp = recipient.hasPermission(VIEW_IP_PERMISSION),
                        revealGeo = recipient.hasPermission(VIEW_GEO_PERMISSION),
                    ))
                }
            }
        }
    }

    private fun render(
        username: String,
        address: InetAddress,
        risk: ProxyRiskResult,
        denied: Boolean,
        revealIp: Boolean,
        revealGeo: Boolean,
    ): Component {
        var message = text("announcement_header")
            .line(text(if (denied) "denied_alert" else "connection_alert").fill("username", username))
        if (revealIp) message = message.line(text("ip_alert").fill("ip", address.hostAddress))

        risk.report?.takeIf { it.accounts.isNotEmpty() }?.let { report ->
            val accounts = report.accounts.take(5).joinToString(", ") {
                "${it.username.value} (${it.sharedAddressCount} IP)"
            } + if (report.truncated || report.accounts.size > 5) " …" else ""
            message = message.line(text("account_alert").fill("count", report.accounts.size.toString()).fill("accounts", accounts))
        }

        risk.network?.let { network ->
            val signals = buildList {
                if (network.vpn == true) add("VPN")
                if (network.proxy == true) add("PROXY")
                if (network.tor == true) add("TOR")
            }
            if (signals.isNotEmpty()) {
                message = message.line(text("network_alert")
                    .fill("signals", signals.joinToString(", "))
                    .fill("confidence", network.confidence?.let { "${it}%" } ?: "brak danych")
                    .fill("risk", network.riskScore?.let { "${it}%" } ?: "brak danych"))
            }
            if (showGeo && revealGeo && listOf(network.continent, network.country, network.countryCode, network.region, network.city, network.timezone)
                    .any { it != null }) {
                message = message.line(text("geo_alert")
                    .fill("city", network.city.display()).fill("region", network.region.display())
                    .fill("country", network.country.display()).fill("country_code", network.countryCode.display())
                    .fill("continent", network.continent.display()).fill("timezone", network.timezone.display()))
            }
            if (showGeo && revealGeo && listOf(network.asn, network.provider, network.organisation, network.networkType).any { it != null }) {
                val owner = listOfNotNull(network.provider, network.organisation).distinct().joinToString(" / ").ifEmpty { "brak danych" }
                message = message.line(text("network_owner_alert")
                    .fill("asn", network.asn.display()).fill("owner", owner).fill("network_type", network.networkType.display()))
            }
            if (showGeo && revealGeo) network.operatorName?.let { message = message.line(text("operator_alert").fill("operator", it)) }
        }

        if (risk.unavailable) message = message.line(text("lookup_unavailable"))
        if (risk.suspicious) message = message.line(text("advisory").fill("username", username))
        return message.line(text("announcement_footer"))
    }

    @Synchronized fun clear() { cooldowns.clear() }
    private fun Component.line(line: Component): Component = append(Component.newline()).append(line)
    private fun String?.display(): String = this ?: "brak danych"
    private fun Component.fill(key: String, value: String) = replaceText(
        TextReplacementConfig.builder().matchLiteral("{$key}").replacement(Component.text(value)).build(),
    )
}
