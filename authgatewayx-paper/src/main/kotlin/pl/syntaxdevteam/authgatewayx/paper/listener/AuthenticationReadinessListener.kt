package pl.syntaxdevteam.authgatewayx.paper.listener

import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorDecision
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorGate
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstDecision
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstGate
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionDecision
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate

class AuthenticationReadinessListener(
    private val readiness: RuntimeReadiness,
    private val floodGate: ConnectionFloodGate,
    private val usernameBurstGate: UsernameBurstGate?,
    private val behaviorGate: ConnectionBehaviorGate?,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (floodGate.evaluate(event.address) != ConnectionDecision.ALLOW) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Too many connection attempts. Try again later."))
            return
        }
        val username = try {
            AccountUsername.parse(event.name)
        } catch (_: IllegalArgumentException) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Connection rejected."))
            return
        }
        if (behaviorGate != null &&
            behaviorGate.evaluateConnection(event.address, username.value) != ConnectionBehaviorDecision.ALLOW
        ) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Too many connection attempts. Try again later."))
            return
        }
        if (usernameBurstGate != null && usernameBurstGate.evaluate(event.address, username.value) != UsernameBurstDecision.ALLOW) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Too many connection attempts. Try again later."))
            return
        }
        if (!readiness.acceptsAuthentication()) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Authentication service is not ready. Please try again later."),
            )
        }
    }
}
