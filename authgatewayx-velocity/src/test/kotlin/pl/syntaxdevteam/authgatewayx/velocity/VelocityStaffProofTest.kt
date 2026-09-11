package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.*
import com.velocitypowered.api.proxy.messages.*
import com.velocitypowered.api.scheduler.*
import pl.syntaxdevteam.authgatewayx.security.proof.StaffSessionProof
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID
import kotlin.test.*

class VelocityStaffProofTest {
    private fun <T> mock(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader,arrayOf(type)) { self, method, args ->
            when(method.name) { "hashCode" -> System.identityHashCode(self); "equals" -> self === args?.get(0); "toString" -> type.simpleName; else -> handler(method.name,args) }
        })
    private inner class Fixture {
        val secret = "s".repeat(32)
        val id = UUID.randomUUID()
        var active = true
        var online = false
        lateinit var backend: ServerConnection
        var challenge = byteArrayOf()
        val timeouts = mutableListOf<Runnable>()
        var cancelled = 0
        val player = mock(Player::class.java) { method,_ -> when(method) {
            "isActive" -> active; "isOnlineMode" -> online; "getUniqueId" -> id; "getCurrentServer" -> Optional.of(backend); else -> null
        } }
        fun server() = mock(ServerConnection::class.java) { method,args -> when(method) {
            "getPlayer" -> player; "sendPluginMessage" -> { challenge = (args!![1] as ByteArray).copyOf(); true }; else -> null
        } }
        val task = mock(ScheduledTask::class.java) { method,_ -> if(method == "cancel") { cancelled++; null } else null }
        val scheduler = mock(Scheduler::class.java) { method,args ->
            if(method == "buildTask") {
                timeouts.add(args!![1] as Runnable)
                lateinit var builder: Scheduler.TaskBuilder
                builder = mock(Scheduler.TaskBuilder::class.java) { name,_ -> if(name == "schedule") task else builder }
                builder
            } else null
        }
        val channels = mock(ChannelRegistrar::class.java) {_,_->null}
        val proxy = mock(ProxyServer::class.java) {method,_-> when(method) { "getScheduler" -> scheduler; "getChannelRegistrar" -> channels; else -> null } }
        val verifier: VelocityStaffProof
        init { backend = server(); verifier = VelocityStaffProof(proxy, Any(), secret) }
        fun response() = StaffSessionProof(secret).respond(challenge,id,System.currentTimeMillis())!!
        fun event(source: ChannelMessageSource, bytes: ByteArray) = PluginMessageEvent(source,player,
            MinecraftChannelIdentifier.from(StaffSessionProof.CHANNEL),bytes)
    }
    @Test fun `client spoof is consumed while signed current backend response grants one read`() {
        val f = Fixture()
        val future = f.verifier.authorize(f.player).toCompletableFuture()
        val response = f.response()
        val spoof = f.event(f.player,response); f.verifier.onMessage(spoof)
        assertFalse(spoof.result.isAllowed); assertFalse(future.isDone)
        f.verifier.onMessage(f.event(f.backend,response))
        assertTrue(future.get()); assertEquals(1,f.cancelled)
        val second = f.verifier.authorize(f.player).toCompletableFuture()
        f.verifier.onMessage(f.event(f.backend,response))
        assertFalse(second.isDone)
        f.verifier.close(); assertFalse(second.get())
    }
    @Test fun `server switch timeout and shutdown cannot authorize stale requests`() {
        val f = Fixture()
        val future = f.verifier.authorize(f.player).toCompletableFuture()
        val old = f.backend; val reply = f.response(); f.backend = f.server()
        f.verifier.onMessage(f.event(old,reply)); assertFalse(future.isDone)
        f.timeouts.single().run(); assertFalse(future.get())
        val next = f.verifier.authorize(f.player).toCompletableFuture()
        f.verifier.close(); assertFalse(next.get())
        assertFalse(f.verifier.authorize(f.player).toCompletableFuture().get())
    }
    @Test fun `Mojang staff needs no backend proof and concurrent offline requests are bounded per player`() {
        val f = Fixture(); f.online = true
        assertTrue(f.verifier.authorize(f.player).toCompletableFuture().get()); assertTrue(f.timeouts.isEmpty())
        f.online = false
        val first = f.verifier.authorize(f.player).toCompletableFuture()
        assertFalse(f.verifier.authorize(f.player).toCompletableFuture().get())
        f.verifier.close(); assertFalse(first.get())
    }
}
