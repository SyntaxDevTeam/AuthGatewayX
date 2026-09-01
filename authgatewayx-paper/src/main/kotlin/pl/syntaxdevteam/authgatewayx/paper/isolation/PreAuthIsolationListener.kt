package pl.syntaxdevteam.authgatewayx.paper.isolation

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.*
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorGate
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorSignal
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstGate

class PreAuthIsolationListener(
    private val access: PreAuthAccess,
    private val isolation: PreAuthIsolationManager,
    private val sessions: SessionRegistry,
    private val admission: PreAuthAdmission,
    private val usernameBurstGate: UsernameBurstGate,
    private val behaviorGate: ConnectionBehaviorGate,
) : Listener {
    private fun blocked(player: Player) = access.isPreAuth(player.uniqueId)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) { (event.whoClicked as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) { (event.whoClicked as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) { (event.player as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) { (event.entity as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) { (event.entity as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        if (attacker != null && blocked(attacker)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeldItem(event: PlayerItemHeldEvent) { if (blocked(event.player)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) { (event.entered as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVehicleExit(event: VehicleExitEvent) { (event.exited as? Player)?.takeIf(::blocked)?.let { event.isCancelled = true } }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (blocked(event.player)) {
            event.player.address?.address?.let { address ->
                usernameBurstGate.recordPreAuthDisconnect(address)
                behaviorGate.record(address, ConnectionBehaviorSignal.PRE_AUTH_DISCONNECT)
            }
        }
        isolation.forget(event.player.uniqueId)
        admission.release(event.player.uniqueId)
        sessions.disconnect(ConnectionId(event.player.uniqueId))
    }
}
