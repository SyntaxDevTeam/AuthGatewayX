package pl.syntaxdevteam.authgatewayx.paper.isolation

import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import java.net.InetAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPreAuthAccessTest {
    @Test
    fun `blocks only sessions in PRE_AUTH state`() {
        val sessions = InMemorySessionRegistry()
        val connectionId = ConnectionId.random()
        sessions.create(AuthSession.connecting(
            connectionId, AccountUsername.parse("GuardPlayer"), InetAddress.getLoopbackAddress(), Instant.now(),
        ))
        val access = SessionPreAuthAccess(sessions)
        assertFalse(access.isPreAuth(connectionId.value))
        sessions.enterPreAuth(connectionId)
        assertTrue(access.isPreAuth(connectionId.value))
        sessions.disconnect(connectionId)
        assertFalse(access.isPreAuth(connectionId.value))
    }
}
