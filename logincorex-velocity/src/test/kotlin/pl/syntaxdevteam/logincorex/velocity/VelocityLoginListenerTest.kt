package pl.syntaxdevteam.logincorex.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import pl.syntaxdevteam.logincorex.integrations.mojang.*
import pl.syntaxdevteam.logincorex.integrations.network.*
import pl.syntaxdevteam.logincorex.security.flood.*
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.UUID
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class VelocityLoginListenerTest {
    private class Resume : Continuation {
        var count = 0
        override fun resume() { count++ }
        override fun resumeWithException(exception: Throwable) { throw AssertionError(exception) }
    }
    private fun event(port: Int = 20000, sessionId: UUID = UUID.randomUUID()): PreLoginEvent {
        val connection = Proxy.newProxyInstance(InboundConnection::class.java.classLoader,arrayOf(InboundConnection::class.java)) { _, method, _ ->
            when(method.name) { "getRemoteAddress" -> InetSocketAddress("127.0.0.1",port); "getSessionId" -> sessionId; "isActive" -> true; else -> null }
        } as InboundConnection
        return PreLoginEvent(connection,"NewName",null)
    }
    private fun listener(pending: PendingConnectionRegistry, ready: AtomicBoolean = AtomicBoolean(true),
                         lookup: PremiumUsernameLookup = PremiumUsernameLookup { CompletableFuture.completedFuture(PremiumUsernameStatus.NOT_PREMIUM) },
                         network: IpIntelligenceLookup? = null, diagnostic: (String) -> Unit = {}) = VelocityLoginListener(ready,
        ConnectionFloodGate(FloodLimit(100,100,Duration.ofSeconds(1)),FloodLimit(100,100,Duration.ofSeconds(1)),100),
        lookup,pending,VelocitySessionRegistry(100),VelocityLoginMessages(Component.text("unavailable"),Component.text("rate"),Component.text("name"),Component.text("mojang"),Component.text("state"),Component.text("vpn"),Component.text("multi")),
        VelocityRiskChecks(null,network,RiskAction.ALERT,RiskAction.DENY,true),1,{_,_,_,_->}, diagnostic = diagnostic)

    @Test fun `diagnostics distinguish premium offline and unavailable without changing admission`() {
        for (status in PremiumUsernameStatus.entries) {
            val lines = mutableListOf<String>()
            val subject = listener(PendingConnectionRegistry(Duration.ofSeconds(30),100),
                lookup = PremiumUsernameLookup { CompletableFuture.completedFuture(status) }, diagnostic = lines::add)
            val event = event()
            val resume = Resume()
            subject.onPreLogin(event, resume)
            assertEquals(1, resume.count)
            assertTrue(lines.any { it.contains("LOOKUP_$status") })
            assertTrue(lines.all { !it.contains("127.0.0.1") })
            when (status) {
                PremiumUsernameStatus.PREMIUM -> assertTrue(event.result.isOnlineModeAllowed)
                PremiumUsernameStatus.NOT_PREMIUM -> assertTrue(event.result.isForceOfflineMode)
                PremiumUsernameStatus.UNAVAILABLE -> assertFalse(event.result.isAllowed)
            }
        }
    }

    @Test fun `broken diagnostic sink cannot interrupt login`() {
        val subject = listener(PendingConnectionRegistry(Duration.ofSeconds(30),100),
            diagnostic = { error("broken sink") })
        val event = event(); val resume = Resume()
        subject.onPreLogin(event, resume)
        assertTrue(event.result.isAllowed)
        assertEquals(1, resume.count)
    }

    @Test fun `VPN denial completes prelogin exactly once and never grants pending backend admission`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val listener = listener(pending, network = IpIntelligenceLookup { CompletableFuture.completedFuture(IpIntelligence(true,false,false,null,null)) })
        val event = event(); val resume = Resume()
        listener.onPreLogin(event,resume)
        assertFalse(event.result.isAllowed); assertEquals(1,resume.count)
        assertNull(pending.remove(PendingConnectionKey(event.connection.sessionId)))
    }
    @Test fun `capacity rejects before additional expensive lookup and shutdown rejects late allow`() {
        var calls = 0
        val lookup = CompletableFuture<PremiumUsernameStatus>()
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val ready = AtomicBoolean(true)
        val listener = listener(pending,ready,PremiumUsernameLookup { calls++;lookup })
        val first = event(); val resume = Resume(); listener.onPreLogin(first,resume)
        val second = event(20001); val secondResume = Resume(); listener.onPreLogin(second,secondResume)
        assertFalse(second.result.isAllowed); assertEquals(1,secondResume.count); assertEquals(1,calls)
        ready.set(false); lookup.complete(PremiumUsernameStatus.NOT_PREMIUM)
        assertFalse(first.result.isAllowed); assertEquals(1,resume.count)
        assertNull(pending.remove(PendingConnectionKey(first.connection.sessionId)))
    }
    @Test fun `failed premium lookup never falls back and existing plugin denials are preserved`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val listener = listener(pending,lookup=PremiumUsernameLookup { CompletableFuture.completedFuture(PremiumUsernameStatus.UNAVAILABLE) })
        val event = event(); listener.onPreLogin(event,Resume()); assertFalse(event.result.isAllowed)
        val denied = event(); val reason = Component.text("other plugin")
        denied.result = PreLoginEvent.PreLoginComponentResult.denied(reason)
        listener.onPreLogin(denied,Resume()); assertEquals(reason,denied.result.reasonComponent.get())
    }
    @Test fun `independent sessions behind same NAT cannot consume each others mode`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val risk = ProxyRiskResult(null,null,null,false)
        val a = PendingConnectionKey(UUID.randomUUID())
        val b = PendingConnectionKey(UUID.randomUUID())
        pending.put(a,PendingLoginDecision(SelectedAuthenticationMode.OFFLINE,risk))
        pending.put(b,PendingLoginDecision(SelectedAuthenticationMode.MOJANG,risk))
        assertEquals(SelectedAuthenticationMode.OFFLINE,pending.remove(a)!!.mode)
        assertEquals(SelectedAuthenticationMode.MOJANG,pending.remove(b)!!.mode)
        assertNull(pending.remove(a))
    }

    @Test fun `legacy Velocity session IDs are unique and cleared per physical player`() {
        fun player(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getSessionId" -> null
                "getUniqueId" -> UUID.randomUUID()
                "getUsername" -> "NewName"
                "getRemoteAddress" -> InetSocketAddress("127.0.0.1",20000)
                "isActive" -> true
                "isOnlineMode" -> false
                else -> null
            }
        } as Player
        val first = player()
        val second = player()
        val id = VelocitySessionIds.id(first)
        assertEquals(id, VelocitySessionIds.id(first))
        assertNotEquals(id, VelocitySessionIds.id(second))
        assertEquals(id, VelocitySessionIds.discard(first))
        assertNotEquals(id, VelocitySessionIds.id(first))
        VelocitySessionIds.discard(first)
        VelocitySessionIds.discard(second)
    }

    @Test fun `legacy login recomputes risk and mode without a PreLogin session key`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30), 100)
        val registry = VelocitySessionRegistry(100)
        val listener = VelocityLoginListener(
            AtomicBoolean(true),
            ConnectionFloodGate(FloodLimit(100,100,Duration.ofSeconds(1)), FloodLimit(100,100,Duration.ofSeconds(1)),100),
            PremiumUsernameLookup { CompletableFuture.completedFuture(PremiumUsernameStatus.NOT_PREMIUM) },
            pending, registry,
            VelocityLoginMessages(Component.text("unavailable"),Component.text("rate"),Component.text("name"),
                Component.text("mojang"),Component.text("state"),Component.text("vpn"),Component.text("multi")),
            VelocityRiskChecks(null, null, RiskAction.ALERT, RiskAction.DENY, true), 4, { _, _, _, _ -> },
        )
        val uid = UUID.randomUUID()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getSessionId" -> null // Emulates Velocity < 4.2.1
                "getUniqueId" -> uid
                "getUsername" -> "NewName"
                "getRemoteAddress" -> InetSocketAddress("127.0.0.1", 22222)
                "isActive" -> true
                "isOnlineMode" -> false
                else -> null
            }
        } as Player
        val event = LoginEvent(player, null)
        val resumed = Resume()
        listener.onLogin(event, resumed)
        assertEquals(1, resumed.count)
        assertTrue(event.result.isAllowed)
        assertEquals(SelectedAuthenticationMode.OFFLINE, registry.find(VelocitySessionIds.id(player), uid)?.mode)
        listener.onDisconnect(DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        assertNull(VelocitySessionIds.existing(player))
    }
}
