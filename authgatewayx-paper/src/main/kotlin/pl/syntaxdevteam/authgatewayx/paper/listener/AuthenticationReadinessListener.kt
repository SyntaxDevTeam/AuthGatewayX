package pl.syntaxdevteam.authgatewayx.paper.listener

import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.RuntimeReadiness
import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginCheapGuard
import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginGuardDecision

class AuthenticationReadinessListener(
    private val readiness: RuntimeReadiness,
    private val cheapGuard: PaperLoginCheapGuard,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        when (cheapGuard.evaluatePreLogin(event.address, event.name).decision) {
            PaperLoginGuardDecision.ALLOW -> Unit
            PaperLoginGuardDecision.DENY_INVALID_USERNAME -> {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Connection rejected."))
                return
            }
            else -> {
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Too many connection attempts. Try again later."),
                )
                return
            }
        }
        if (!readiness.acceptsAuthentication()) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Authentication service is not ready. Please try again later."),
            )
        }
    }
}
