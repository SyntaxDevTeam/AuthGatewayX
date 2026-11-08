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
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.CompletableFuture
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
    private val sessions: VelocitySessionRegistry,
    private val messages: VelocityLoginMessages,
    private val riskChecks: VelocityRiskChecks,
    maximumConcurrent: Int,
    private val notify: (String, InetAddress, ProxyRiskResult, Boolean) -> Unit,
    private val admissionTimeoutMillis: Long = 20_000,

) {
    private val capacity = Semaphore(maximumConcurrent)
    init { require(admissionTimeoutMillis > 0) }
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
        val key = VelocitySessionIds.native(event.connection)?.let(::PendingConnectionKey)
        val stage = try {
            lookup.lookup(username).thenCompose { status ->
                if (status == PremiumUsernameStatus.UNAVAILABLE) CompletableFuture.failedFuture(IllegalStateException("Premium unavailable"))
                else riskChecks.assess(address, username, status == PremiumUsernameStatus.NOT_PREMIUM).thenApply { risk ->
                    PendingLoginDecision(if (status == PremiumUsernameStatus.PREMIUM) SelectedAuthenticationMode.MOJANG else SelectedAuthenticationMode.OFFLINE, risk)
                }
            }
        } catch (_: Exception) { CompletableFuture.failedFuture(IllegalStateException("Admission unavailable")) }
        // Release the concurrency slot even if a third-party lookup never completes.
        stage.toCompletableFuture().orTimeout(admissionTimeoutMillis, TimeUnit.MILLISECONDS).whenComplete { decision, failure ->
            try {
                if (!ready.get() || !event.connection.isActive) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.unavailable)
                } else if (failure != null) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.mojangUnavailable)
                } else if (!event.result.isAllowed) {
                    // Preserve a denial from another plugin while async work was pending.
                } else if (decision == null) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.unavailable)
                } else if (decision.risk.denial != null) {
                    val message = when (decision.risk.denial) {
                        RiskDenial.VPN -> messages.vpnDenied
                        RiskDenial.MULTI_ACCOUNT -> messages.multiDenied
                        RiskDenial.UNAVAILABLE -> messages.unavailable
                    }
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(message)
                    runCatching { notify(username.value, address, decision.risk, true) }
                } else if (key != null && !pending.put(key, decision)) {
                    event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.rateLimited)
                } else {
                    event.result = if (decision.mode == SelectedAuthenticationMode.MOJANG) PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                        else PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
                }
            } finally { capacity.release(); continuation.resume() }
        }
    }

    /**
     * Native session IDs (Velocity >=4.2.1) bind PRE_LOGIN to LOGIN directly.
     * Velocity 4.0–4.2.0 has no public session ID: recompute premium status and risk
     * from authenticated LoginEvent instead of reusing a decision keyed by IP/port/name.
     */
    @Subscribe
    fun onLogin(event: LoginEvent, continuation: Continuation = ImmediateContinuation) {
        val player = event.player
        val nativeId = VelocitySessionIds.native(player)
        if (nativeId != null) {
            try {
                val selected = pending.remove(PendingConnectionKey(nativeId))
                finishLogin(event, selected)
            } finally {
                continuation.resume()
            }
            return
        }
        // Pre-login admission on legacy Velocity is rechecked here; a new socket can
        // never inherit a previous connection's premium/offline classification.
        if (!ready.get() || !event.result.isAllowed || !player.isActive) {
            event.result = ResultedEvent.ComponentResult.denied(messages.unavailable)
            continuation.resume()
            return
        }
        if (!capacity.tryAcquire()) {
            event.result = ResultedEvent.ComponentResult.denied(messages.rateLimited)
            continuation.resume()
            return
        }
        val username = try { AccountUsername.parse(player.username) } catch (_: IllegalArgumentException) {
            capacity.release()
            event.result = ResultedEvent.ComponentResult.denied(messages.invalidUsername)
            continuation.resume()
            return
        }
        val address = player.remoteAddress.address
        val stage = try {
            lookup.lookup(username).thenCompose { status ->
                if (status == PremiumUsernameStatus.UNAVAILABLE) {
                    CompletableFuture.failedFuture(IllegalStateException("Premium unavailable"))
                } else {
                    riskChecks.assess(address, username, status == PremiumUsernameStatus.NOT_PREMIUM).thenApply { risk ->
                        PendingLoginDecision(
                            if (status == PremiumUsernameStatus.PREMIUM) SelectedAuthenticationMode.MOJANG
                            else SelectedAuthenticationMode.OFFLINE, risk,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            CompletableFuture.failedFuture(IllegalStateException("Admission unavailable"))
        }
        stage.toCompletableFuture().orTimeout(admissionTimeoutMillis, TimeUnit.MILLISECONDS)
            .whenComplete { decision, failure ->
                try {
                    if (failure != null || decision == null || !ready.get() || !player.isActive) {
                        event.result = ResultedEvent.ComponentResult.denied(messages.mojangUnavailable)
                    } else {
                        finishLogin(event, decision)
                    }
                } finally {
                    capacity.release()
                    continuation.resume()
                }
            }
    }

    private fun finishLogin(event: LoginEvent, selected: PendingLoginDecision?) {
        val player = event.player
        val valid = when (selected?.mode) {
            SelectedAuthenticationMode.MOJANG -> player.isOnlineMode
            SelectedAuthenticationMode.OFFLINE -> !player.isOnlineMode
            null -> false
        }
        if (!ready.get() || !valid) {
            event.result = ResultedEvent.ComponentResult.denied(messages.stateMismatch)
        } else if (event.result.isAllowed) {
            val denial = selected?.risk?.denial
            if (denial != null) {
                event.result = ResultedEvent.ComponentResult.denied(when (denial) {
                    RiskDenial.VPN -> messages.vpnDenied
                    RiskDenial.MULTI_ACCOUNT -> messages.multiDenied
                    RiskDenial.UNAVAILABLE -> messages.unavailable
                })
                return
            }
            if (!sessions.record(VelocitySessionIds.id(player), player.uniqueId, player.username, selected!!.mode)) {
                event.result = ResultedEvent.ComponentResult.denied(messages.rateLimited)
            } else {
                runCatching { notify(player.username, player.remoteAddress.address, selected.risk, false) }
            }
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val id = VelocitySessionIds.discard(event.player) ?: return
        pending.discard(PendingConnectionKey(id))
        sessions.discard(id)
    }

    private object ImmediateContinuation : Continuation {
        override fun resume() {}
        override fun resumeWithException(exception: Throwable) { throw IllegalStateException(exception) }
    }

    private fun deny(event: PreLoginEvent, message: Component, continuation: Continuation) {
        event.result = PreLoginEvent.PreLoginComponentResult.denied(message)
        continuation.resume()
    }

}
