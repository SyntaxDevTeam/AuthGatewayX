package pl.syntaxdevteam.authgatewayx.velocity

import java.time.Instant
import java.util.UUID
import kotlin.test.*

class VelocitySessionRegistryTest {
    @Test fun `backend switches preserve identity and do not imply Paper authentication`() {
        val registry = VelocitySessionRegistry(2) { Instant.EPOCH }
        val session = UUID.randomUUID()
        val player = UUID.randomUUID()
        assertTrue(registry.record(session, player, "Player", SelectedAuthenticationMode.OFFLINE))
        registry.backend(session, player, "lobby")
        registry.backend(session, player, "survival")
        val state = registry.find(session, player)!!
        assertEquals(session, state.sessionId)
        assertEquals("survival", state.backend)
        assertEquals(SelectedAuthenticationMode.OFFLINE, state.mode)
        assertEquals(Instant.EPOCH, state.connectedAt)
    }

    @Test fun `reconnect prevents old disconnect from deleting new session`() {
        val registry = VelocitySessionRegistry(2)
        val player = UUID.randomUUID()
        val old = UUID.randomUUID()
        val latest = UUID.randomUUID()
        assertTrue(registry.record(old, player, "player", SelectedAuthenticationMode.MOJANG))
        assertTrue(registry.record(latest, player, "player", SelectedAuthenticationMode.OFFLINE))
        registry.discard(old)
        assertNull(registry.find(old, player))
        assertNotNull(registry.find(latest, player))
    }

    @Test fun `bounded registry rejects excess sessions and closes cleanly`() {
        val registry = VelocitySessionRegistry(1)
        val a = UUID.randomUUID()
        val p = UUID.randomUUID()
        assertTrue(registry.record(a, p, "first", SelectedAuthenticationMode.OFFLINE))
        assertFalse(registry.record(a, p, "first", SelectedAuthenticationMode.MOJANG))
        assertFalse(registry.record(UUID.randomUUID(), UUID.randomUUID(), "second", SelectedAuthenticationMode.OFFLINE))
        registry.close()
        assertNull(registry.find(a, p))
        assertFalse(registry.record(UUID.randomUUID(), p, "first", SelectedAuthenticationMode.OFFLINE))
    }
}
