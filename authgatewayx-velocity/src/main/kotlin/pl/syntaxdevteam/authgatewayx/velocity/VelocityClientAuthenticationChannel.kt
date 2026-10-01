package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.scheduler.ScheduledTask
import pl.syntaxdevteam.authgatewayx.security.client.ClientAuthenticationProtocol
import java.util.concurrent.TimeUnit

/** Offline authentication belongs to Paper. Proxy relays only its current backend's reply. */
class VelocityClientAuthenticationChannel(
    private val proxy: ProxyServer,
    private val plugin: Any,
    private val ready: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Pending(
        val server: ServerConnection,
        val nonce: String,
        val channel: MinecraftChannelIdentifier,
        val deadline: Long,
    ) {
        var task: ScheduledTask? = null
        var confirmed = false
    }
    private val channels = ClientAuthenticationProtocol.channels.map(MinecraftChannelIdentifier::from)
    private val pending = mutableMapOf<Player, Pending>()
    private var closed = false

    init { proxy.channelRegistrar.register(*channels.toTypedArray()) }

    @Subscribe
    fun onMessage(event: PluginMessageEvent) {
        if (event.identifier !in channels) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        synchronized(this) {
            if (closed || !ready()) return
            when (val source = event.source) {
                is Player -> subscribe(source, event)
                is ServerConnection -> confirm(source, event)
            }
        }
    }

    private fun subscribe(player: Player, event: PluginMessageEvent) {
        val nonce = ClientAuthenticationProtocol.readSubscription(event.data) ?: return
        val server = player.currentServer.orElse(null) ?: return
        if (!player.isActive || event.target !== server || pending.containsKey(player) || pending.size >= 5_000) return
        val channel = event.identifier as MinecraftChannelIdentifier
        val request = Pending(server, nonce, channel, clock() + 300_000)
        pending[player] = request
        try {
            request.task = proxy.scheduler.buildTask(plugin, Runnable {
                synchronized(this) {
                    if (closed || !ready() || !player.isActive || player.currentServer.orElse(null) !== server ||
                        clock() >= request.deadline || pending[player] !== request) {
                        if (pending.remove(player, request)) request.task?.cancel()
                    } else if (!request.confirmed) {
                        server.sendPluginMessage(request.channel, ClientAuthenticationProtocol.subscription(request.nonce))
                    }
                }
            }).repeat(2, TimeUnit.SECONDS).schedule()
            if (request.confirmed || closed || pending[player] !== request) request.task?.cancel()
        } catch (_: Exception) { pending.remove(player, request); request.task?.cancel() }
    }

    private fun confirm(server: ServerConnection, event: PluginMessageEvent) {
        val player = server.player
        val request = pending[player] ?: return
        if (request.confirmed || server !== request.server || event.target !== player ||
            player.currentServer.orElse(null) !== server || !player.isActive || clock() >= request.deadline ||
            event.identifier != request.channel || !ClientAuthenticationProtocol.confirms(event.data, request.nonce)) return
        if (player.sendPluginMessage(request.channel, event.data)) {
            request.confirmed = true
            request.task?.cancel()
        }
    }

    @Subscribe fun onDisconnect(event: DisconnectEvent) = discard(event.player)
    @Subscribe fun onSwitch(event: ServerPreConnectEvent) = discard(event.player)
    @Synchronized private fun discard(player: Player) { pending.remove(player)?.task?.cancel() }

    @Synchronized override fun close() {
        closed = true
        pending.values.forEach { it.task?.cancel() }
        pending.clear()
        proxy.channelRegistrar.unregister(*channels.toTypedArray())
    }
}
