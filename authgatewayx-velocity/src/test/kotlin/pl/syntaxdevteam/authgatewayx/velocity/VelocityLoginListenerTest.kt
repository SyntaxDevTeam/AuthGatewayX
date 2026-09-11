package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.proxy.InboundConnection
import net.kyori.adventure.text.Component
import pl.syntaxdevteam.authgatewayx.integrations.mojang.*
import pl.syntaxdevteam.authgatewayx.integrations.network.*
import pl.syntaxdevteam.authgatewayx.security.flood.*
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
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
    private fun event(port: Int = 20000): PreLoginEvent {
        val connection = Proxy.newProxyInstance(InboundConnection::class.java.classLoader,arrayOf(InboundConnection::class.java)) { _, method, _ ->
            when(method.name) { "getRemoteAddress" -> InetSocketAddress("127.0.0.1",port); "isActive" -> true; else -> null }
        } as InboundConnection
        return PreLoginEvent(connection,"NewName",null)
    }
    private fun listener(pending: PendingConnectionRegistry, ready: AtomicBoolean = AtomicBoolean(true),
                         lookup: PremiumUsernameLookup = PremiumUsernameLookup { CompletableFuture.completedFuture(PremiumUsernameStatus.NOT_PREMIUM) },
                         network: IpIntelligenceLookup? = null) = VelocityLoginListener(ready,
        ConnectionFloodGate(FloodLimit(100,100,Duration.ofSeconds(1)),FloodLimit(100,100,Duration.ofSeconds(1)),100),
        lookup,pending,VelocityLoginMessages(Component.text("unavailable"),Component.text("rate"),Component.text("name"),Component.text("mojang"),Component.text("state"),Component.text("vpn"),Component.text("multi")),
        VelocityRiskChecks(null,network,RiskAction.ALERT,RiskAction.DENY,true),1,{_,_,_->})

    @Test fun `VPN denial completes prelogin exactly once and never grants pending backend admission`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val listener = listener(pending, network = IpIntelligenceLookup { CompletableFuture.completedFuture(IpIntelligence(true,false,false,null,null)) })
        val event = event(); val resume = Resume()
        listener.onPreLogin(event,resume)
        assertFalse(event.result.isAllowed); assertEquals(1,resume.count)
        assertNull(pending.remove(PendingConnectionKey(event.connection.remoteAddress,"newname")))
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
        assertNull(pending.remove(PendingConnectionKey(first.connection.remoteAddress,"newname")))
    }
    @Test fun `failed premium lookup never falls back and existing plugin denials are preserved`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val listener = listener(pending,lookup=PremiumUsernameLookup { CompletableFuture.completedFuture(PremiumUsernameStatus.UNAVAILABLE) })
        val event = event(); listener.onPreLogin(event,Resume()); assertFalse(event.result.isAllowed)
        val denied = event(); val reason = Component.text("other plugin")
        denied.result = PreLoginEvent.PreLoginComponentResult.denied(reason)
        listener.onPreLogin(denied,Resume()); assertEquals(reason,denied.result.reasonComponent.get())
    }
    @Test fun `same IP and name on different sockets cannot consume each others mode`() {
        val pending = PendingConnectionRegistry(Duration.ofSeconds(30),100)
        val risk = ProxyRiskResult(null,null,null,false)
        val a = PendingConnectionKey(InetSocketAddress("127.0.0.1",1),"player")
        val b = PendingConnectionKey(InetSocketAddress("127.0.0.1",2),"player")
        pending.put(a,PendingLoginDecision(SelectedAuthenticationMode.OFFLINE,risk))
        pending.put(b,PendingLoginDecision(SelectedAuthenticationMode.MOJANG,risk))
        assertEquals(SelectedAuthenticationMode.OFFLINE,pending.remove(a)!!.mode)
        assertEquals(SelectedAuthenticationMode.MOJANG,pending.remove(b)!!.mode)
        assertNull(pending.remove(a))
    }
}
