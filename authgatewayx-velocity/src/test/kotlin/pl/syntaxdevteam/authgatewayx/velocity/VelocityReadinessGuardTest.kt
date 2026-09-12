package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.connection.PreLoginEvent
import com.velocitypowered.api.proxy.InboundConnection
import net.kyori.adventure.text.Component
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VelocityReadinessGuardTest {
    private fun event(): PreLoginEvent {
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InboundConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getRemoteAddress" -> InetSocketAddress("127.0.0.1", 12345)
                "isActive" -> true
                else -> null
            }
        } as InboundConnection
        return PreLoginEvent(connection, "Test", null)
    }

    @Test fun `failed startup denies and repeats retained cause with bounded logging`() {
        val logs = mutableListOf<String>()
        val guard = VelocityReadinessGuard(AtomicBoolean(false), logs::add, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        guard.failed(VelocityReadinessGuard.Phase.DATABASE, "missing schema")
        repeat(100) {
            val event = event()
            guard.onPreLogin(event)
            assertFalse(event.result.isAllowed)
            assertEquals(Component.text("Authentication service is unavailable. [AGX-STARTUP]"), event.result.reasonComponent.get())
        }
        assertEquals(1, logs.size)
        assertTrue("FAILED: DATABASE; missing schema" in logs.single())
    }

    @Test fun `ready runtime allows login and preserves another plugins denial`() {
        val logs = mutableListOf<String>()
        val ready = AtomicBoolean(true)
        val guard = VelocityReadinessGuard(ready, logs::add)
        val allowed = event(); guard.onPreLogin(allowed); assertTrue(allowed.result.isAllowed)
        ready.set(false)
        val denied = event()
        denied.result = PreLoginEvent.PreLoginComponentResult.denied(Component.text("Other plugin"))
        guard.onPreLogin(denied)
        assertEquals(Component.text("Other plugin"), denied.result.reasonComponent.get())
        assertTrue(logs.isEmpty())
    }
}
