package pl.syntaxdevteam.logincorex.auth.api

import pl.syntaxdevteam.logincorex.integrations.api.BoundedAuthEventBus
import pl.syntaxdevteam.logincorex.domain.account.*
import pl.syntaxdevteam.logincorex.domain.session.*
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class RuntimeLoginCoreApiTest {
    @Test fun `live reads fail closed for consent expiry identity mismatch and shutdown`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val id = UUID.randomUUID()
        var ready = true
        var consent = true
        var clock = now
        val active = AuthSession.connecting(ConnectionId(id), AccountUsername.parse("ApiUser"), InetAddress.getLoopbackAddress(), now)
            .enterPreAuth().activate(AccountId.random(), id, IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now, now.plusSeconds(10))
        var current = active
        BoundedAuthEventBus().use { events ->
            val api = RuntimeLoginCoreApi({ ready }, { if (consent) null else current },
                { CompletableFuture.completedFuture(null) }, { CompletableFuture.completedFuture(null) },
                { CompletableFuture.completedFuture(false) }, events, { clock })
            assertNull(api.getAccount(id))
            assertFalse(api.isAuthenticated(id))
            consent = false
            assertEquals(id, api.getSession(id)?.minecraftUuid)
            assertEquals(active.accountId!!.value, api.getAccount(id)?.accountId)
            assertNull(api.getSession(UUID.randomUUID()))
            clock = now.plusSeconds(10)
            assertFalse(api.isAuthenticated(id))
            clock = now
            current = active.disconnect()
            assertNull(api.getSession(id))
            current = active
            ready = false
            assertNull(api.getSession(id))
            ready = true
            api.close()
            assertFalse(api.isAuthenticated(id))
            assertFalse(api.invalidateSession(id).toCompletableFuture().join())
        }
    }

    @Test fun `async lookup never exposes a result after runtime retirement and validates names`() {
        var ready = true
        val pending = CompletableFuture<AuthAccount?>()
        BoundedAuthEventBus().use { events ->
            val api = RuntimeLoginCoreApi({ ready }, { null }, { pending }, { pending },
                { CompletableFuture.completedFuture(false) }, events)
            assertFails { api.findAccountByUsername("bad name").toCompletableFuture().join() }
            val query = api.findAccount(UUID.randomUUID()).toCompletableFuture()
            ready = false
            pending.complete(null)
            assertFails { query.join() }
            assertFails { api.findAccount(UUID.randomUUID()).toCompletableFuture().join() }
        }
    }

    @Test fun `invalidation passes captured generation rather than re-reading replacement`() {
        val id = UUID.randomUUID()
        val now = Instant.now()
        val session = AuthSession.connecting(ConnectionId(id), AccountUsername.parse("ApiUser"), InetAddress.getLoopbackAddress(), now)
            .activate(AccountId.random(), id, IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now)
        var captured: AuthSession? = null
        BoundedAuthEventBus().use { events ->
            val api = RuntimeLoginCoreApi({ true }, { session }, { CompletableFuture.completedFuture(null) },
                { CompletableFuture.completedFuture(null) }, { captured = it; CompletableFuture.completedFuture(true) }, events)
            assertTrue(api.invalidateSession(id).toCompletableFuture().join())
            assertSame(session, captured)
            assertEquals(session.sessionNonce, captured?.sessionNonce)
        }
    }
}
