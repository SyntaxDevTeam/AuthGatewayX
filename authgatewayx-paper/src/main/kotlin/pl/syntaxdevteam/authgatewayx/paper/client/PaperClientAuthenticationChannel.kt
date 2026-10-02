package pl.syntaxdevteam.authgatewayx.paper.client

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import pl.syntaxdevteam.authgatewayx.api.AuthenticationStatusProvider
import pl.syntaxdevteam.authgatewayx.security.client.ClientAuthenticationProtocol
import java.util.concurrent.ConcurrentHashMap

/** Native AGX transport; it only reads authentication state and never changes PRE_AUTH. */
class PaperClientAuthenticationChannel(
    private val plugin: Plugin,
    private val status: AuthenticationStatusProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : PluginMessageListener, Listener, AutoCloseable {
    private class Pending(val nonce: String, val channel: String, val deadline: Long) {
        @Volatile var task: ScheduledTask? = null
        @Volatile var confirmed = false
    }

    private val pending = ConcurrentHashMap<Player, Pending>()
    private val enhancedChannel = PaperCraftConnectChannel(plugin, status)
    @Volatile private var closed = false

    fun register() {
        ClientAuthenticationProtocol.channels.forEach { channel ->
            plugin.server.messenger.registerIncomingPluginChannel(plugin, channel, this)
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, channel)
        }
        enhancedChannel.register()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (closed || channel !in ClientAuthenticationProtocol.channels) return
        val nonce = ClientAuthenticationProtocol.readSubscription(message) ?: return
        val request = Pending(nonce, channel, clock() + 300_000)
        synchronized(pending) {
            if (closed || pending.size >= 5_000 || pending.putIfAbsent(player, request) != null) return
        }
        request.task = player.scheduler.runAtFixedRate(plugin, { task ->
            if (closed || pending[player] !== request || !player.isOnline || clock() >= request.deadline) {
                pending.remove(player, request)
                task.cancel()
            } else if (!request.confirmed && status.isAuthenticated(player.uniqueId)) {
                player.sendPluginMessage(plugin, request.channel, ClientAuthenticationProtocol.authenticated(request.nonce))
                request.confirmed = true
                task.cancel()
            }
        }, { pending.remove(player, request) }, 1L, 20L)
        if (request.task == null) pending.remove(player, request)
        if (closed || request.confirmed || pending[player] !== request) request.task?.cancel()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player)?.task?.cancel() }

    override fun close() {
        synchronized(pending) {
            closed = true
            pending.values.forEach { it.task?.cancel() }
            pending.clear()
        }
        enhancedChannel.close()
        ClientAuthenticationProtocol.channels.forEach { channel ->
            plugin.server.messenger.unregisterIncomingPluginChannel(plugin, channel, this)
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, channel)
        }
    }
}
