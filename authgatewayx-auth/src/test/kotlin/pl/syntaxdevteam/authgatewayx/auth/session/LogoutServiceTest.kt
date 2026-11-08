package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import pl.syntaxdevteam.authgatewayx.security.audit.*
import java.net.InetAddress
import java.time.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.*

class LogoutServiceTest {
    @Test
    fun `logout invalidates active offline session and writes audit`() {
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val username = AccountUsername.parse("LogoutUser")
        val connectionId = ConnectionId.random()
        val sessions = InMemorySessionRegistry()
        sessions.create(AuthSession.connecting(connectionId, username, InetAddress.getLoopbackAddress(), now))
        val accountId = AccountId.random()
        sessions.activate(connectionId, accountId, OfflineIdentity.minecraftUuid(username), IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now)
        val audit = CapturingAudit()

        val result = LogoutService(sessions, audit, Clock.fixed(now, ZoneOffset.UTC)).logout(connectionId)

        assertEquals(LogoutResult.LOGGED_OUT, result)
        assertNull(sessions.get(connectionId))
        assertEquals(SecurityEventType.SESSION_INVALIDATED, audit.events.single().type)
        assertEquals("PLAYER_LOGOUT", audit.events.single().reasonCode)
    }

    private class CapturingAudit : SecurityAuditSink {
        val events = mutableListOf<SecurityEvent>()
        override fun record(event: SecurityEvent): CompletionStage<Void> {
            events += event
            return CompletableFuture.completedFuture(null)
        }
    }
}
