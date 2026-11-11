package pl.syntaxdevteam.logincorex.paper.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pl.syntaxdevteam.logincorex.domain.account.AccountId
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.AuthenticationMethod
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import java.net.InetAddress
import java.time.Instant
import java.util.UUID

class PaperAuthenticationStatusProviderTest {
    @Test
    fun confirmsOnlyReadyActiveUnexpiredSessionForRequestedIdentity() {
        val uuid = UUID.randomUUID()
        var ready = false
        var now = Instant.parse("2026-10-01T00:00:00Z")
        var session: AuthSession? = null
        val provider = PaperAuthenticationStatusProvider({ ready }, { session }, { now })
        assertFalse(provider.isAuthenticated(uuid))
        ready = true
        assertFalse(provider.isAuthenticated(uuid))
        session = AuthSession.connecting(
            ConnectionId(uuid), AccountUsername.parse("OfflineUser"),
            InetAddress.getLoopbackAddress(), now,
        )
        assertFalse(provider.isAuthenticated(uuid))
        session = checkNotNull(session).enterPreAuth()
        assertFalse(provider.isAuthenticated(uuid))
        session = checkNotNull(session).activate(
            AccountId(UUID.randomUUID()), uuid, IdentityType.OFFLINE,
            AuthenticationMethod.PASSWORD, now, now.plusSeconds(60),
        )
        assertTrue(provider.isAuthenticated(uuid))
        val snapshot = checkNotNull(provider.getAuthentication(uuid))
        assertEquals(uuid, snapshot.minecraftUuid)
        assertEquals(IdentityType.OFFLINE, snapshot.identityType)
        assertEquals(AuthenticationMethod.PASSWORD, snapshot.method)
        assertEquals(now, snapshot.authenticatedAt)
        assertEquals(now.plusSeconds(60), snapshot.expiresAt)
        assertNull(provider.getAuthentication(UUID.randomUUID()))
        assertFalse(provider.isAuthenticated(UUID.randomUUID()))
        ready = false
        assertFalse(provider.isAuthenticated(uuid))
        ready = true
        now = now.plusSeconds(60)
        assertFalse(provider.isAuthenticated(uuid))
        assertNull(provider.getAuthentication(uuid))
        now = now.minusSeconds(60)
        session = checkNotNull(session).disconnect()
        assertFalse(provider.isAuthenticated(uuid))
        session = null
        assertFalse(provider.isAuthenticated(uuid))
    }
}
