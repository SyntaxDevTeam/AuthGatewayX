package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.*
import com.velocitypowered.api.proxy.messages.*
import com.velocitypowered.api.scheduler.*
import pl.syntaxdevteam.authgatewayx.security.client.ClientAuthenticationProtocol
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID
import kotlin.test.*

class VelocityClientAuthenticationChannelTest {
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
        var ready = true
        var now = 0L
        var cancelled = 0
        lateinit var backend: ServerConnection
        val replies = mutableListOf<ByteArray>()
        val forwards = mutableListOf<ByteArray>()
        val ticks = mutableListOf<Runnable>()
        val player = mock(Player::class.java) { name, args -> when (name) {
            "isActive" -> true
            "isOnlineMode" -> true // Even Mojang clients wait for backend ACTIVE.
            "getCurrentServer" -> Optional.of(backend)
            "sendPluginMessage" -> { replies += (args!![1] as ByteArray).copyOf(); true }
            else -> null
        } }
        fun server() = mock(ServerConnection::class.java) { name, args -> when (name) {
            "getPlayer" -> player
            "sendPluginMessage" -> { forwards += (args!![1] as ByteArray).copyOf(); true }
            else -> null
        } }
        val task = mock(ScheduledTask::class.java) { name, _ -> if (name == "cancel") { cancelled++; null } else null }
        val scheduler = mock(Scheduler::class.java) { name, args ->
            if (name == "buildTask") {
                ticks += args!![1] as Runnable
                lateinit var builder: Scheduler.TaskBuilder
                builder = mock(Scheduler.TaskBuilder::class.java) { method, _ -> if (method == "schedule") task else builder }
                builder
            } else null
        }
        val registrar = mock(ChannelRegistrar::class.java) { _, _ -> null }
        val proxy = mock(ProxyServer::class.java) { name, _ -> when (name) {
            "getScheduler" -> scheduler
            "getChannelRegistrar" -> registrar
            else -> null
        } }
        val channel: VelocityClientAuthenticationChannel
        val nonce = UUID.randomUUID().toString()
        val identifier = MinecraftChannelIdentifier.from(ClientAuthenticationProtocol.CHANNEL)
        init { backend = server(); channel = VelocityClientAuthenticationChannel(proxy, Any(), { ready }, { now }) }
        fun request(payload: ByteArray = ClientAuthenticationProtocol.subscription(nonce)) =
            PluginMessageEvent(player, backend, identifier, payload)
        fun response(source: ServerConnection = backend, payload: ByteArray = ClientAuthenticationProtocol.authenticated(nonce)) =
            PluginMessageEvent(source, player, identifier, payload)
    }

    @Test fun `only current backend response confirms and client spoof is consumed`() {
        val f = Fixture()
        val spoof = f.request(ClientAuthenticationProtocol.authenticated(f.nonce))
        f.channel.onMessage(spoof)
        assertFalse(spoof.result.isAllowed)
        assertTrue(f.ticks.isEmpty())
        val request = f.request(); f.channel.onMessage(request); f.channel.onMessage(f.request())
        assertFalse(request.result.isAllowed)
        assertEquals(1, f.ticks.size)
        f.ticks.single().run()
        assertEquals(f.nonce, ClientAuthenticationProtocol.readSubscription(f.forwards.single()))
        assertTrue(f.replies.isEmpty())
        f.channel.onMessage(f.response(payload = ClientAuthenticationProtocol.authenticated(UUID.randomUUID().toString())))
        assertTrue(f.replies.isEmpty())
        f.channel.onMessage(f.response()); f.channel.onMessage(f.response())
        assertEquals(1, f.replies.size)
        assertTrue(ClientAuthenticationProtocol.confirms(f.replies.single(), f.nonce))
        f.channel.close()
    }

    @Test fun `stale backend disconnect and expired subscriptions do not confirm`() {
        val f = Fixture(); f.channel.onMessage(f.request())
        val old = f.backend; f.backend = f.server()
        val response = f.response(old); f.channel.onMessage(response)
        assertFalse(response.result.isAllowed)
        assertTrue(f.replies.isEmpty())
        f.ticks.single().run()
        f.channel.onMessage(f.request())
        f.now = 300_000
        f.channel.onMessage(f.response())
        assertTrue(f.replies.isEmpty())
        f.ticks.last().run()
        f.channel.onMessage(f.request())
        f.channel.onDisconnect(DisconnectEvent(f.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        f.channel.onMessage(f.response())
        assertTrue(f.replies.isEmpty())
        f.channel.close()
    }

    @Test fun `unavailable runtime and shutdown cannot relay confirmations`() {
        val f = Fixture(); f.ready = false
        f.channel.onMessage(f.request())
        assertTrue(f.ticks.isEmpty())
        f.ready = true; f.channel.onMessage(f.request()); f.channel.close()
        f.channel.onMessage(f.response()); f.ticks.single().run()
        assertTrue(f.replies.isEmpty())
        assertTrue(f.forwards.isEmpty())
    }
}
