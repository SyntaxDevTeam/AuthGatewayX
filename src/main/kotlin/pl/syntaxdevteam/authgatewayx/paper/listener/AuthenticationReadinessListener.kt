package pl.syntaxdevteam.authgatewayx.paper.listener

import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionDecision
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate

class AuthenticationReadinessListener(
    private val readiness: RuntimeReadiness,
    private val floodGate: ConnectionFloodGate,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (floodGate.evaluate(event.address) != ConnectionDecision.ALLOW) {
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
