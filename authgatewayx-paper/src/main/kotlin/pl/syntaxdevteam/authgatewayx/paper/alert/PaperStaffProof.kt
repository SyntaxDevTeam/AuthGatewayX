package pl.syntaxdevteam.authgatewayx.paper.alert

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import pl.syntaxdevteam.authgatewayx.security.proof.StaffSessionProof

class PaperStaffProof(
    private val plugin: JavaPlugin,
    private val proof: StaffSessionProof,
    private val active: (Player) -> Boolean,
) : PluginMessageListener, AutoCloseable {
    @Volatile private var closed = false
    private val pending = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Boolean>()
    fun register() {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, StaffSessionProof.CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, StaffSessionProof.CHANNEL)
    }
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (closed || channel != StaffSessionProof.CHANNEL || message.size != 90) return
        val response = proof.respond(message, player.uniqueId, System.currentTimeMillis()) ?: return
        synchronized(pending) {
            if (pending.size >= 10_000 || pending.putIfAbsent(player.uniqueId, true) != null) return
        }
        val accepted = player.scheduler.execute(plugin, Runnable {
            try {
                if (!closed && player.isOnline && active(player) &&
                    java.nio.ByteBuffer.wrap(response).getLong(18) > System.currentTimeMillis()) {
                    player.sendPluginMessage(plugin, StaffSessionProof.CHANNEL, response)
                }
            } finally { pending.remove(player.uniqueId) }
        }, Runnable { pending.remove(player.uniqueId) }, 1L)
        if (!accepted) pending.remove(player.uniqueId)
    }

    override fun close() {
        closed = true
        pending.clear()
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, StaffSessionProof.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, StaffSessionProof.CHANNEL)
    }
}
