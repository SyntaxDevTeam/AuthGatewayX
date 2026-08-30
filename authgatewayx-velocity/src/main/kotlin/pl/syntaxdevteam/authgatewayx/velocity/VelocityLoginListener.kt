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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class VelocityLoginMessages(
    val unavailable: Component,
    val rateLimited: Component,
    val invalidUsername: Component,
    val mojangUnavailable: Component,
    val stateMismatch: Component,
)

class VelocityLoginListener(
    private val ready: AtomicBoolean,
    private val floodGate: ConnectionFloodGate,
    private val lookup: PremiumUsernameLookup,
    private val pending: PendingConnectionRegistry,
    private val messages: VelocityLoginMessages,
) {
    @Subscribe
    fun onPreLogin(event: PreLoginEvent, continuation: Continuation) {
        try {
            if (!ready.get()) return deny(event, messages.unavailable, continuation)
            val address = event.connection.remoteAddress.address
            if (floodGate.evaluate(address) != ConnectionDecision.ALLOW) {
                return deny(event, messages.rateLimited, continuation)
            }
            val username = try { AccountUsername.parse(event.username) } catch (_: IllegalArgumentException) {
                return deny(event, messages.invalidUsername, continuation)
            }
            val key = key(address, username.value)
            pending.discard(key)
            lookup.lookup(username).whenComplete { status, failure ->
                try {
                    if (failure != null || status == PremiumUsernameStatus.UNAVAILABLE) {
                        event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.mojangUnavailable)
                    } else {
                        val mode = if (status == PremiumUsernameStatus.PREMIUM) SelectedAuthenticationMode.MOJANG else SelectedAuthenticationMode.OFFLINE
                        if (!pending.put(key, mode)) {
                            event.result = PreLoginEvent.PreLoginComponentResult.denied(messages.rateLimited)
                        } else {
                            event.result = if (mode == SelectedAuthenticationMode.MOJANG) {
                                PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                            } else {
                                PreLoginEvent.PreLoginComponentResult.forceOfflineMode()
                            }
                        }
                    }
                    continuation.resume()
                } catch (problem: Throwable) {
                    continuation.resumeWithException(problem)
                }
            }
        } catch (problem: Throwable) {
            continuation.resumeWithException(problem)
        }
    }

    @Subscribe
    fun onLogin(event: LoginEvent) {
        val player = event.player
        val selected = pending.remove(key(player.remoteAddress.address, player.username))
        val valid = when (selected) {
            SelectedAuthenticationMode.MOJANG -> player.isOnlineMode
            SelectedAuthenticationMode.OFFLINE -> !player.isOnlineMode
            null -> false
        }
        if (!valid) event.result = ResultedEvent.ComponentResult.denied(messages.stateMismatch)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pending.discard(key(event.player.remoteAddress.address, event.player.username))
    }

    private fun deny(event: PreLoginEvent, message: Component, continuation: Continuation) {
        event.result = PreLoginEvent.PreLoginComponentResult.denied(message)
        continuation.resume()
    }

    private fun key(address: InetAddress, username: String) = PendingConnectionKey(address, username.lowercase(Locale.ROOT))
}
