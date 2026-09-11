package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.scheduler.ScheduledTask
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import pl.syntaxdevteam.authgatewayx.security.proof.StaffSessionProof
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

class VelocityStaffProof(private val proxy: ProxyServer, private val plugin: Any, secret: String) : AutoCloseable {
    private val proof = secret.takeIf { it.isNotEmpty() }?.let(::StaffSessionProof)
    private val channel = MinecraftChannelIdentifier.from(StaffSessionProof.CHANNEL)
    private data class Pending(val server: ServerConnection, val challenge: ByteArray, val future: CompletableFuture<Boolean>, var timeout: ScheduledTask? = null)
    private val pending = mutableMapOf<Player, Pending>()
    private var closed = false
    init { proxy.channelRegistrar.register(channel) }

    @Synchronized
    fun authorize(player: Player): CompletionStage<Boolean> {
        if (closed || !player.isActive) return CompletableFuture.completedFuture(false)
        if (player.isOnlineMode) return CompletableFuture.completedFuture(true)
        val proof = proof ?: return CompletableFuture.completedFuture(false)
        val server = player.currentServer.orElse(null) ?: return CompletableFuture.completedFuture(false)
        if (pending.size >= 1000 || pending.containsKey(player)) return CompletableFuture.completedFuture(false)
        val request = Pending(server, proof.challenge(player.uniqueId, System.currentTimeMillis() + 5000), CompletableFuture())
        pending[player] = request
        try {
            request.timeout = proxy.scheduler.buildTask(plugin, Runnable {
                synchronized(this) { if (pending.remove(player, request)) request.future.complete(false) }
            }).delay(5, TimeUnit.SECONDS).schedule()
            if (!server.sendPluginMessage(channel, request.challenge)) { pending.remove(player); request.timeout?.cancel(); request.future.complete(false) }
        } catch (_: Exception) {
            pending.remove(player); request.timeout?.cancel(); request.future.complete(false)
        }
        return request.future.copy()
    }

    @Subscribe
    fun onMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        val server = event.source as? ServerConnection ?: return
        synchronized(this) {
            val player = server.player
            val request = pending[player] ?: return
            if (closed || server !== request.server || player.currentServer.orElse(null) !== server || !player.isActive) return
            if (proof?.verify(event.data, request.challenge, player.uniqueId, System.currentTimeMillis()) != true) return
            pending.remove(player)
            request.timeout?.cancel()
            request.future.complete(true)
        }
    }
    @Subscribe fun onDisconnect(event: DisconnectEvent) = discard(event.player)
    @Subscribe fun onSwitch(event: ServerPreConnectEvent) = discard(event.player)
    @Synchronized private fun discard(player: Player) { pending.remove(player)?.let { it.timeout?.cancel(); it.future.complete(false) } }
    @Synchronized override fun close() {
        closed = true
        val requests = pending.values.toList(); pending.clear()
        requests.forEach { it.timeout?.cancel(); it.future.complete(false) }
        proxy.channelRegistrar.unregister(channel)
    }
}
