package pl.syntaxdevteam.authgatewayx.velocity

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class PendingConnectionRegistryTest {
    private val decision = PendingLoginDecision(SelectedAuthenticationMode.MOJANG, ProxyRiskResult(null, null, null, false))

    @Test fun `duplicate prelogin cannot replace an existing authentication decision`() {
        val registry = PendingConnectionRegistry(Duration.ofSeconds(30), 1)
        val key = PendingConnectionKey(UUID.randomUUID())
        assertTrue(registry.put(key, decision))
        assertFalse(registry.put(key, decision.copy(mode = SelectedAuthenticationMode.OFFLINE)))
        assertEquals(SelectedAuthenticationMode.MOJANG, registry.remove(key)?.mode)
        assertNull(registry.remove(key))
    }

    @Test fun `pending expires and closes fail closed`() {
        var time = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = time
        }
        val registry = PendingConnectionRegistry(Duration.ofSeconds(3), 1, clock)
        val first = PendingConnectionKey(UUID.randomUUID())
        val second = PendingConnectionKey(UUID.randomUUID())
        assertTrue(registry.put(first, decision))
        assertFalse(registry.put(second, decision))
        time = time.plusSeconds(3)
        assertNull(registry.remove(first))
        assertTrue(registry.put(second, decision))
        registry.close()
        assertNull(registry.remove(second))
        assertFalse(registry.put(first, decision))
    }
}
