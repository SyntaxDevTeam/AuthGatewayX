package pl.syntaxdevteam.logincorex.domain.session

import pl.syntaxdevteam.logincorex.domain.account.AccountId
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AuthSessionTest {
    private val createdAt = Instant.parse("2026-08-30T10:00:00Z")

    @Test
    fun `offline connection must pass through pre-auth before password activation`() {
        val session = newSession().enterPreAuth().activate(
            accountId = AccountId.random(),
            minecraftUuid = UUID.randomUUID(),
            identityType = IdentityType.OFFLINE,
            authenticationMethod = AuthenticationMethod.PASSWORD,
            authenticatedAt = createdAt.plusSeconds(2),
        )

        assertEquals(ConnectionState.ACTIVE, session.state)
        assertEquals(IdentityType.OFFLINE, session.identityType)
    }

    @Test
    fun `verified Mojang identity can authenticate with a server password`() {
        val session = newSession().enterPreAuth().activate(
            accountId = AccountId.random(),
            minecraftUuid = UUID.randomUUID(),
            identityType = IdentityType.MOJANG,
            authenticationMethod = AuthenticationMethod.PASSWORD,
            authenticatedAt = createdAt.plusSeconds(1),
        )
        assertEquals(ConnectionState.ACTIVE, session.state)
        assertEquals(IdentityType.MOJANG, session.identityType)
        assertEquals(AuthenticationMethod.PASSWORD, session.authenticationMethod)
    }

    @Test
    fun `Mojang verified connection can activate without offline pre-auth`() {
        val session = newSession().activate(
            accountId = AccountId.random(),
            minecraftUuid = UUID.randomUUID(),
            identityType = IdentityType.MOJANG,
            authenticationMethod = AuthenticationMethod.MOJANG,
            authenticatedAt = createdAt.plusSeconds(1),
        )

        assertEquals(ConnectionState.ACTIVE, session.state)
    }

    @Test
    fun `disconnection is terminal and idempotent`() {
        val disconnected = newSession().enterPreAuth().disconnect()

        assertSame(disconnected, disconnected.disconnect())
        assertFailsWith<IllegalArgumentException> { disconnected.enterPreAuth() }
        assertFailsWith<IllegalArgumentException> {
            disconnected.activate(
                AccountId.random(),
                UUID.randomUUID(),
                IdentityType.OFFLINE,
                AuthenticationMethod.PASSWORD,
                createdAt.plusSeconds(1),
            )
        }
    }

    @Test
    fun `session timestamps cannot move backwards`() {
        assertFailsWith<IllegalArgumentException> {
            newSession().activate(
                AccountId.random(),
                UUID.randomUUID(),
                IdentityType.MOJANG,
                AuthenticationMethod.MOJANG,
                createdAt.minusSeconds(1),
            )
        }
    }

    private fun newSession(): AuthSession = AuthSession.connecting(
        connectionId = ConnectionId.random(),
        username = AccountUsername.parse("ExampleUser"),
        sourceAddress = InetAddress.getLoopbackAddress(),
        createdAt = createdAt,
    )
}
