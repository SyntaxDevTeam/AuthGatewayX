package pl.syntaxdevteam.authgatewayx.paper.isolation

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationActivationListener
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PreAuthIsolationManager(
    private val plugin: Plugin,
    private val scheduler: PaperPlatformScheduler,
    private val access: PreAuthAccess,
    private val timeoutTicks: Long,
    private val timeoutMessage: Component,
) : AuthenticationActivationListener {
    private val snapshots = ConcurrentHashMap<UUID, PlayerSnapshot>()

    init { require(timeoutTicks > 0) }

    fun enter(player: Player) {
        scheduler.entity(player, Runnable {
            if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
            snapshots.putIfAbsent(player.uniqueId, PlayerSnapshot(player.isCollidable, player.canPickupItems))
            // Entity invulnerability is serialized in player data. Older builds enabled it here,
            // so a crash or forced shutdown could make the player permanently invulnerable after
            // authentication. Damage is already denied by PreAuthIsolationListener; normalize the
            // legacy flag instead of using persistent entity state for a connection-scoped guard.
            if (player.isInvulnerable) player.isInvulnerable = false
            player.isCollidable = false
            player.canPickupItems = false
            plugin.server.onlinePlayers.forEach { other ->
                if (other.uniqueId != player.uniqueId) {
                    scheduler.entity(other, Runnable { if (other.isOnline) other.hidePlayer(plugin, player) })
                    player.hidePlayer(plugin, other)
                }
            }
        })
        scheduler.delayedEntity(player, timeoutTicks, Runnable {
            if (player.isOnline && access.isPreAuth(player.uniqueId)) player.kick(timeoutMessage)
        })
    }

    override fun activated(context: AuthenticationFormContext) {
        scheduler.global(Runnable {
            plugin.server.getPlayer(context.connectionId.value)?.let(::release)
        })
    }

    fun release(player: Player) {
        scheduler.entity(player, Runnable {
            val snapshot = snapshots.remove(player.uniqueId) ?: return@Runnable
            player.isCollidable = snapshot.collidable
            player.canPickupItems = snapshot.canPickupItems
            plugin.server.onlinePlayers.forEach { other ->
                if (other.uniqueId != player.uniqueId) {
                    scheduler.entity(other, Runnable { if (other.isOnline) other.showPlayer(plugin, player) })
                    player.showPlayer(plugin, other)
                }
            }
        })
    }

    fun forget(playerId: UUID) {
        snapshots.remove(playerId)
    }

    private data class PlayerSnapshot(
        val collidable: Boolean,
        val canPickupItems: Boolean,
    )
}
