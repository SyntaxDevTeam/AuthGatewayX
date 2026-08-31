package pl.syntaxdevteam.authgatewayx.paper.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class PaperPlatformScheduler(private val plugin: Plugin) {
    fun async(task: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin) { task.run() }
    }

    fun global(task: Runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task)
    }

    fun entity(player: Player, task: Runnable) {
        player.scheduler.execute(plugin, task, null, 1L)
    }

    fun region(location: Location, task: Runnable) {
        Bukkit.getRegionScheduler().execute(plugin, location, task)
    }

    fun delayedEntity(player: Player, delayTicks: Long, task: Runnable) {
        require(delayTicks > 0) { "Entity delay must be positive" }
        player.scheduler.execute(plugin, task, null, delayTicks)
    }

    fun delayedGlobal(delayTicks: Long, task: Runnable) {
        require(delayTicks > 0) { "Global delay must be positive" }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task.run() }, delayTicks)
    }
}
