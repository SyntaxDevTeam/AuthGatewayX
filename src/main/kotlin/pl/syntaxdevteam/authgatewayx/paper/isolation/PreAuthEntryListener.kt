package pl.syntaxdevteam.authgatewayx.paper.isolation

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import java.time.Clock

class PreAuthEntryListener(
    private val sessions: SessionRegistry,
    private val isolation: PreAuthIsolationManager,
    private val admission: PreAuthAdmission,
    private val capacityMessage: net.kyori.adventure.text.Component,
    private val clock: Clock = Clock.systemUTC(),
    private val onEntered: (org.bukkit.entity.Player) -> Unit = {},
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!admission.acquire(player.uniqueId)) {
            player.kick(capacityMessage)
            return
        }
        val connectionId = ConnectionId(player.uniqueId)
        sessions.disconnect(connectionId)
        val created = sessions.create(AuthSession.connecting(
            connectionId = connectionId,
            username = AccountUsername.parse(player.name),
            sourceAddress = player.address.address,
            createdAt = clock.instant(),
        ))
        if (!created) {
            admission.release(player.uniqueId)
            player.kick(capacityMessage)
            return
        }
        sessions.enterPreAuth(connectionId)
        isolation.enter(player)
        onEntered(player)
    }
}
