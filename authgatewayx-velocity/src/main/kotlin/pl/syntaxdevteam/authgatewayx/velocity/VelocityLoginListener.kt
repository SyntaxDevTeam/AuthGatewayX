package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PreLoginEvent
import net.kyori.adventure.text.Component
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.integrations.mojang.PremiumUsernameLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.PremiumUsernameStatus
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionDecision
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate
import java.net.InetSocketAddress
import java.util.concurrent.Semaphore
import java.util.concurrent.CompletableFuture
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class VelocityLoginMessages(
    val unavailable: Component,
    val rateLimited: Component,
    val invalidUsername: Component,
    val mojangUnavailable: Component,
    val stateMismatch: Component,
    val vpnDenied: Component,
    val multiDenied: Component,
)

class VelocityLoginListener(
    private val ready: AtomicBoolean,
    private val floodGate: ConnectionFloodGate,
    private val lookup: PremiumUsernameLookup,
    private val pending: PendingConnectionRegistry,
    private val messages: VelocityLoginMessages,
    private val riskChecks: VelocityRiskChecks,
    maximumConcurrent: Int,
    private val notify: (String, ProxyRiskResult, Boolean) -> Unit,

) {
    private val capacity = Semaphore(maximumConcurrent)
    @Subscribe
    fun onPreLogin(event: PreLoginEvent, continuation: Continuation) {
        if (!event.result.isAllowed) { continuation.resume(); return }
        if (!ready.get()) return deny(event, messages.unavailable, continuation)
        val address = event.connection.remoteAddress.address
        if (floodGate.evaluate(address) != ConnectionDecision.ALLOW) return deny(event, messages.rateLimited, continuation)
        val username = try { AccountUsername.parse(event.username) } catch (_: IllegalArgumentException) {
            return deny(event, messages.invalidUsername, continuation)
        }
        if (!capacity.tryAcquire()) return deny(event, messages.rateLimited, continuation)
        val key = key(event.connection.remoteAddress, username.value)
        val stage = try {
            lookup.lookup(username).thenCompose { status ->
                if (status == PremiumUsernameStatus.UNAVAILABLE) CompletableFuture.failedFuture(IllegalStateException("Premium unavailable"))
                else riskChecks.assess(address, username, status == PremiumUsernameStatus.NOT_PREMIUM).thenApply { risk ->
                    PendingLoginDecision(if (status == PremiumUsernameStatus.PREMIUM) SelectedAuthenticationMode.MOJANG else SelectedAuthenticationMode.OFFLINE, risk)
                }
            }
        } catch (_: Exception) { CompletableFuture.failedFuture<PendingLoginDecision>(IllegalStateException("Admission unavailable")) }
        stage.whenComplete { decision, failure ->
            try {
                if (!ready.get() || !event.connection.isActive) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.unavailable)
                } else if (failure != null) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.mojangUnavailable)
                } else if (!event.result.isAllowed) {
                    // Preserve a denial from another plugin while async work was pending.
                } else if (decision.risk.denial != null) {
                    val message = when (decision.risk.denial) {
                        RiskDenial.VPN -> messages.vpnDenied
                        RiskDenial.MULTI_ACCOUNT -> messages.multiDenied
                        RiskDenial.UNAVAILABLE -> messages.unavailable
                    }
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(message)
                    runCatching { notify(username.value, decision.risk, true) }
                } else if (!pending.put(key, decision)) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.rateLimited)
                } else {
                    event.result = if (decision.mode == SelectedAuthenticationMode.MOJANG) PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                        else PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
                }
            } finally { capacity.release(); continuation.resume() }
        }
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        val player = event.player
        val selected = pending.remove(key(player.remoteAddress, player.username))
        val valid = when (selected?.mode) {
            SelectedAuthenticationMode.MOJANG -> player.isOnlineMode
            SelectedAuthenticationMode.OFFLINE -> !player.isOnlineMode
            null -> false
        }
        if (!ready.get() || !valid) event.result = ResultedEvent.ComponentResult.denied(messages.stateMismatch)
        else if (event.result.isAllowed) runCatching { notify(player.username, selected!!.risk, false) }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pending.discard(key(event.player.remoteAddress, event.player.username))
    }

    private fun deny(event: PreLoginEvent, message: Component, continuation: Continuation) {
        event.result = PreLoginEvent.PreLoginComponentResult.denied(message)
        continuation.resume()
    }

    private fun key(address: InetSocketAddress, username: String) = PendingConnectionKey(address, username.lowercase(Locale.ROOT))
}
