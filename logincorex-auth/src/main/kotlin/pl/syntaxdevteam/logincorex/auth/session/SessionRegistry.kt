package pl.syntaxdevteam.logincorex.auth.session

import pl.syntaxdevteam.logincorex.domain.account.AccountId
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.AuthenticationMethod
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import java.time.Instant
import java.util.UUID

interface SessionRegistry {
    fun create(session: AuthSession): Boolean
    fun get(connectionId: ConnectionId): AuthSession?
    fun getActive(minecraftUuid: UUID): AuthSession?
    fun enterPreAuth(connectionId: ConnectionId): AuthSession

    fun activate(
        connectionId: ConnectionId,
        accountId: AccountId,
        minecraftUuid: UUID,
        identityType: IdentityType,
        authenticationMethod: AuthenticationMethod,
        authenticatedAt: Instant,
        expiresAt: Instant? = null,
        expectedSessionNonce: UUID? = null,
    ): AuthSession

    fun disconnect(connectionId: ConnectionId, expectedSessionNonce: UUID? = null): AuthSession?
    fun clear()
    fun size(): Int
}

class StaleConnectionSessionException(connectionId: ConnectionId) :
    IllegalStateException("Connection ${connectionId.value} no longer owns the original login attempt")

class SessionAlreadyActiveException(accountId: AccountId) :
    IllegalStateException("Account ${accountId.value} already has an active session")

class UnknownSessionException(connectionId: ConnectionId) :
    NoSuchElementException("Unknown connection ${connectionId.value}")

internal fun AuthSession.isActive(): Boolean = state == ConnectionState.ACTIVE
