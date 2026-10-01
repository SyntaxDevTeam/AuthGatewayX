package pl.syntaxdevteam.authgatewayx.paper.client

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.bukkit.Server
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger
import pl.syntaxdevteam.authgatewayx.api.AuthenticationStatusProvider
import pl.syntaxdevteam.authgatewayx.security.client.ClientAuthenticationProtocol
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperClientAuthenticationChannelTest {
    private fun <T> mock(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> type.simpleName
                else -> handler(method.name, args)
            }
        })

    private inner class Fixture {
        var authenticated = false
        var now = 0L
        var scheduled = 0
        var cancelled = 0
        var tick: Consumer<ScheduledTask>? = null
        val replies = mutableListOf<ByteArray>()
        val task = mock(ScheduledTask::class.java) { name, _ ->
            if (name == "cancel") { cancelled++; null } else null
        }
        val scheduler = mock(EntityScheduler::class.java) { name, args ->
            if (name == "runAtFixedRate") {
                scheduled++
                @Suppress("UNCHECKED_CAST")
                tick = args!![1] as Consumer<ScheduledTask>
                task
            } else null
        }
        val messenger = mock(Messenger::class.java) { _, _ -> null }
        val server = mock(Server::class.java) { name, _ -> if (name == "getMessenger") messenger else null }
        val plugin = mock(Plugin::class.java) { name, _ -> if (name == "getServer") server else null }
        val player = mock(Player::class.java) { name, args -> when (name) {
            "getUniqueId" -> UUID(0, 1)
            "getScheduler" -> scheduler
            "isOnline" -> true
            "sendPluginMessage" -> { replies += (args!![2] as ByteArray).copyOf(); null }
            else -> null
        } }
        val channel = PaperClientAuthenticationChannel(plugin, AuthenticationStatusProvider { authenticated }, { now })
        val nonce = UUID.randomUUID().toString()
        fun subscribe() = channel.onPluginMessageReceived(ClientAuthenticationProtocol.CHANNEL, player,
            ClientAuthenticationProtocol.subscription(nonce))
    }

    @Test fun `waits for ACTIVE and repeated subscriptions do not create additional tasks`() {
        val f = Fixture()
        f.subscribe(); f.subscribe()
        assertEquals(1, f.scheduled)
        f.tick!!.accept(f.task)
        assertTrue(f.replies.isEmpty())
        f.authenticated = true
        f.tick!!.accept(f.task); f.tick!!.accept(f.task)
        assertEquals(1, f.replies.size)
        assertTrue(ClientAuthenticationProtocol.confirms(f.replies.single(), f.nonce))
    }

    @Test fun `client proof is not subscription and disconnect blocks stale callbacks`() {
        val f = Fixture()
        f.channel.onPluginMessageReceived(ClientAuthenticationProtocol.CHANNEL, f.player,
            ClientAuthenticationProtocol.authenticated(f.nonce))
        assertEquals(0, f.scheduled)
        f.subscribe()
        f.channel.onQuit(PlayerQuitEvent(f.player, null as net.kyori.adventure.text.Component?, PlayerQuitEvent.QuitReason.DISCONNECTED))
        f.authenticated = true
        f.tick!!.accept(f.task)
        assertTrue(f.replies.isEmpty())
    }

    @Test fun `expired subscription and shutdown never send late confirmations`() {
        val f = Fixture(); f.subscribe(); f.now = 300_000; f.authenticated = true
        f.tick!!.accept(f.task)
        assertTrue(f.replies.isEmpty())
        f.subscribe(); f.channel.close(); f.tick!!.accept(f.task)
        assertTrue(f.replies.isEmpty())
    }
}
