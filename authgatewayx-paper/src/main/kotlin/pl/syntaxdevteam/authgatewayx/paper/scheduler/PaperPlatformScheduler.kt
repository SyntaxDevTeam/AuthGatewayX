package pl.syntaxdevteam.authgatewayx.paper.scheduler

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class PaperPlatformScheduler(
    private val plugin: Plugin,
    private val isCurrent: () -> Boolean = { true },
) {
    private fun guarded(task: Runnable) = currentRuntimeTask(plugin, isCurrent, task)

    private inline fun schedule(action: () -> Unit) {
        if (!isCurrent() || !plugin.isEnabled) return
        try {
            action()
        } catch (failure: org.bukkit.plugin.IllegalPluginAccessException) {
            if (isCurrent() && plugin.isEnabled) throw failure
        }
    }
    fun async(task: Runnable) {
        schedule { Bukkit.getAsyncScheduler().runNow(plugin) { guarded(task).run() } }
    }

    fun global(task: Runnable) {
        schedule { Bukkit.getGlobalRegionScheduler().execute(plugin, guarded(task)) }
    }

    fun entity(player: Player, task: Runnable) {
        schedule { player.scheduler.execute(plugin, guarded(task), null, 1L) }
    }

    fun region(location: Location, task: Runnable) {
        schedule { Bukkit.getRegionScheduler().execute(plugin, location, guarded(task)) }
    }

    fun delayedEntity(player: Player, delayTicks: Long, task: Runnable) {
        require(delayTicks > 0) { "Entity delay must be positive" }
        schedule { player.scheduler.execute(plugin, guarded(task), null, delayTicks) }
    }

    fun delayedGlobal(delayTicks: Long, task: Runnable) {
        require(delayTicks > 0) { "Global delay must be positive" }
        schedule { Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { guarded(task).run() }, delayTicks) }
    }
}

internal fun currentRuntimeTask(plugin: Plugin, isCurrent: () -> Boolean, task: Runnable) = Runnable {
    if (isCurrent() && plugin.isEnabled) task.run()
}
