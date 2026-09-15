package pl.syntaxdevteam.authgatewayx.paper.isolation

import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.*
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreAuthIsolationListenerSurfaceTest {
    @Test
    fun `world-affecting event surface remains guarded at highest priority`() {
        cancellableEvents.forEach { eventType ->
            val method = PreAuthIsolationListener::class.java.declaredMethods.singleOrNull {
                it.parameterTypes.contentEquals(arrayOf(eventType))
            }
            assertNotNull(method, "Missing PRE_AUTH guard for ${eventType.simpleName}")
            val handler = method.getAnnotation(EventHandler::class.java)
            assertNotNull(handler, "${method.name} must be a Bukkit event handler")
            assertEquals(EventPriority.HIGHEST, handler.priority)
            assertTrue(handler.ignoreCancelled, "${method.name} must preserve an earlier cancellation")
        }
    }

    @Test
    fun `command suggestion surface is cleared by a dedicated handler`() {
        val method = PreAuthIsolationListener::class.java.getDeclaredMethod(
            "onCommandSuggestions",
            PlayerCommandSendEvent::class.java,
        )
        val handler = method.getAnnotation(EventHandler::class.java)
        assertNotNull(handler)
        assertEquals(EventPriority.HIGHEST, handler.priority)
        assertFalse(handler.ignoreCancelled)
    }

    @Test
    fun `invulnerability is event scoped and never retained in the player snapshot`() {
        val snapshot = PreAuthIsolationManager::class.java.declaredClasses.single {
            it.simpleName == "PlayerSnapshot"
        }

        assertFalse(
            snapshot.declaredFields.any { it.name == "invulnerable" },
            "Persistent Bukkit invulnerability must not be used for connection-scoped PRE_AUTH isolation",
        )
        assertTrue(EntityDamageEvent::class.java in cancellableEvents)
    }

    private val cancellableEvents = listOf(
        PlayerMoveEvent::class.java,
        PlayerTeleportEvent::class.java,
        PlayerInteractEvent::class.java,
        PlayerInteractEntityEvent::class.java,
        PlayerArmorStandManipulateEvent::class.java,
        PlayerBucketEmptyEvent::class.java,
        PlayerBucketFillEvent::class.java,
        PlayerEditBookEvent::class.java,
        PlayerTakeLecternBookEvent::class.java,
        PlayerShearEntityEvent::class.java,
        PlayerFishEvent::class.java,
        BlockBreakEvent::class.java,
        BlockPlaceEvent::class.java,
        InventoryClickEvent::class.java,
        InventoryDragEvent::class.java,
        InventoryOpenEvent::class.java,
        PlayerDropItemEvent::class.java,
        EntityPickupItemEvent::class.java,
        EntityDamageEvent::class.java,
        EntityDamageByEntityEvent::class.java,
        EntityShootBowEvent::class.java,
        ProjectileLaunchEvent::class.java,
        HangingBreakByEntityEvent::class.java,
        PlayerCommandPreprocessEvent::class.java,
        PlayerPortalEvent::class.java,
        PlayerSwapHandItemsEvent::class.java,
        PlayerItemConsumeEvent::class.java,
        PlayerItemHeldEvent::class.java,
        VehicleEnterEvent::class.java,
        VehicleExitEvent::class.java,
    )
}
