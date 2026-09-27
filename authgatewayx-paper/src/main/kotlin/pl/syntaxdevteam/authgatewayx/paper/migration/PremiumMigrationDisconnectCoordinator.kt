package pl.syntaxdevteam.authgatewayx.paper.migration

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationCoordinator
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRunResult
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PremiumMigrationDisconnectCoordinator(
    private val coordinator: PremiumMigrationCoordinator,
    private val scheduler: PaperPlatformScheduler,
    private val preparedMessage: Component,
    private val identityConflictMessage: Component,
    private val onResult: (PremiumMigrationTicket, PremiumMigrationRunResult?, Throwable?) -> Unit,
) : Listener {
    private val pending = ConcurrentHashMap<UUID, PremiumMigrationTicket>()

    fun begin(player: Player, ticket: PremiumMigrationTicket) {
        if (ticket.targetMinecraftUuid != player.uniqueId) {
            player.kick(identityConflictMessage)
            return
        }
        pending[player.uniqueId] = ticket
        player.kick(preparedMessage)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val ticket = pending.remove(event.player.uniqueId) ?: return
        scheduler.delayedGlobal(20L, Runnable {
            coordinator.migrate(ticket).whenComplete { result, failure ->
                onResult(ticket, result, failure)
            }
        })
    }
}
