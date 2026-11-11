package pl.syntaxdevteam.logincorex.auth.api

import pl.syntaxdevteam.logincorex.api.*
import pl.syntaxdevteam.logincorex.auth.session.*
import pl.syntaxdevteam.logincorex.domain.account.*
import pl.syntaxdevteam.logincorex.domain.session.*
import pl.syntaxdevteam.logincorex.integrations.api.BoundedAuthEventBus
import pl.syntaxdevteam.logincorex.security.audit.SecurityAuditSink
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SessionNotificationTest {
    @Test fun `session events describe committed transitions and stale disconnect emits nothing`() {
        val events = LinkedBlockingQueue<AuthEvent>()
        BoundedAuthEventBus().use { bus ->
            bus.subscribe { events.add(it) }
            val registry = InMemorySessionRegistry(bus::observeSession)
            val session = connecting()
            assertTrue(registry.create(session))
            registry.enterPreAuth(session.connectionId)
            assertEquals(AuthEventType.PRE_AUTHENTICATION, events.poll(5, TimeUnit.SECONDS)?.type)
            assertNull(registry.disconnect(session.connectionId, UUID.randomUUID()))
            registry.disconnect(session.connectionId, session.sessionNonce)
            val end = events.poll(5, TimeUnit.SECONDS)!!
            assertEquals(AuthEventType.SESSION_ENDED, end.type)
            assertEquals(session.sessionNonce, end.connectionId)
            assertNull(registry.disconnect(session.connectionId, session.sessionNonce))
            assertTrue(events.isEmpty())
        }
    }

    @Test fun `logout racing reconnect cannot remove replacement session or audit logout`() {
        val registry = InMemorySessionRegistry()
        val old = connecting()
        registry.create(old)
        val account = AccountId.random()
        registry.activate(old.connectionId, account, old.connectionId.value, IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, old.createdAt)
        var newNonce: UUID? = null
        val racing = object : SessionRegistry by registry {
            override fun disconnect(connectionId: ConnectionId, expectedSessionNonce: UUID?): AuthSession? {
                registry.disconnect(connectionId)
                val replacement = connecting(connectionId)
                newNonce = replacement.sessionNonce
                registry.create(replacement)
                registry.activate(connectionId, account, connectionId.value, IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, replacement.createdAt)
                return registry.disconnect(connectionId, expectedSessionNonce)
            }
        }
        var audits = 0
        val logout = LogoutService(racing, SecurityAuditSink { audits++; CompletableFuture.completedFuture(null) })
        assertEquals(LogoutResult.NOT_ACTIVE, logout.logout(old.connectionId))
        assertEquals(newNonce, registry.get(old.connectionId)?.sessionNonce)
        assertEquals(0, audits)
    }

    private fun connecting(id: ConnectionId = ConnectionId.random()) = AuthSession.connecting(
        id, AccountUsername.parse("ApiUser"), InetAddress.getLoopbackAddress(), Instant.now(),
    )
}
