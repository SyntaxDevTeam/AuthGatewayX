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
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

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

    // Other subcommands, including setpassword, remain routed to the backend.
    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = invocation.arguments().firstOrNull()?.equals("alts", true) == true
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (!ready() || !source.hasPermission("authgatewayx.admin.alts")) return
        val name = invocation.arguments().takeIf { it.size == 2 }?.get(1)?.let { runCatching { AccountUsername.parse(it) }.getOrNull() }
        if (name == null) { source.sendMessage(text("usage")); return }
        if (accounts == null || !busy.compareAndSet(false, true)) { source.sendMessage(text("unavailable")); return }
        authorize(source).whenComplete firstProof@ { authorized, authFailure ->
            if (authorized != true || authFailure != null || !ready()) {
                busy.set(false)
                if (ready()) source.sendMessage(text("auth_required"))
                return@firstProof
            }
            val query = try { accounts.findRelatedOfflineAccounts(name, Instant.now()) }
                catch (_: Exception) { CompletableFuture.failedFuture<pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport?>(IllegalStateException("Report unavailable")) }
            query.whenComplete { report, failure ->
                authorize(source).whenComplete finalProof@ { confirmed, proofFailure ->
                    try {
                        if (!ready() || confirmed != true || proofFailure != null || !source.hasPermission("authgatewayx.admin.alts")) return@finalProof
                        when {
                            failure != null -> source.sendMessage(text("unavailable"))
                            report == null -> source.sendMessage(text("not_found"))
                            else -> {
                                source.sendMessage(text("header").fill("username", name.value))
                                if (report.accounts.isEmpty()) source.sendMessage(text("empty"))
                                report.accounts.forEach { source.sendMessage(text("entry").fill("username", it.username.value).fill("count", it.sharedAddressCount.toString())) }
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
    fun notify(username: String, risk: ProxyRiskResult, denied: Boolean) {
        if (!ready() || !alertsEnabled || (!risk.suspicious && !risk.unavailable)) return
        val now = Instant.now()
        if (now < nextAlert || cooldowns[username]?.isAfter(now) == true) return
        cooldowns.entries.removeIf { !it.value.isAfter(now) }
        if (cooldowns.size >= 10_000) return
        cooldowns[username] = now.plusSeconds(cooldownSeconds)
        nextAlert = now.plusSeconds(1)
        var message = text(if (denied) "denied_alert" else "connection_alert").fill("username", username)
        risk.report?.takeIf { it.accounts.isNotEmpty() }?.let { report ->
            message = message.append(Component.newline()).append(text("account_alert").fill("accounts",
                report.accounts.take(5).joinToString(", ") { it.username.value } + if (report.truncated || report.accounts.size > 5) " …" else ""))
        }
        risk.network?.let { ip ->
            val signals = buildList { if (ip.vpn == true) add("VPN"); if (ip.proxy == true) add("PROXY"); if (ip.tor == true) add("TOR") }
            message = message.append(Component.newline()).append(text(if(showGeo) "network_alert" else "network_alert_no_geo").fill("signals", signals.joinToString(", ").ifEmpty { "?" })
                .fill("country", ip.countryCode ?: "?").fill("asn", ip.asn ?: "?"))
        }
        if (risk.unavailable) message = message.append(Component.newline()).append(text("lookup_unavailable"))
        val rendered = message
        if (consoleAlerts) proxy.consoleCommandSource.sendMessage(rendered)
        proxy.allPlayers.filter { it.isActive && it.hasPermission("authgatewayx.admin.alerts") }.forEach { recipient ->
            proof.authorize(recipient).thenAccept { authorized ->
                if (authorized && ready() && recipient.isActive && recipient.hasPermission("authgatewayx.admin.alerts")) recipient.sendMessage(rendered)
            }
        }
    }
    @Synchronized fun clear() { cooldowns.clear() }
    private fun Component.fill(key: String, value: String) = replaceText(TextReplacementConfig.builder()
        .matchLiteral("{$key}").replacement(Component.text(value)).build())
}
