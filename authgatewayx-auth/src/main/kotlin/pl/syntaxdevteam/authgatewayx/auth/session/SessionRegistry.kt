package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.AuthenticationMethod
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
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
    ): AuthSession

    fun disconnect(connectionId: ConnectionId): AuthSession?
    fun clear()
    fun size(): Int
}

class SessionAlreadyActiveException(accountId: AccountId) :
    IllegalStateException("Account ${accountId.value} already has an active session")

class UnknownSessionException(connectionId: ConnectionId) :
    NoSuchElementException("Unknown connection ${connectionId.value}")

internal fun AuthSession.isActive(): Boolean = state == ConnectionState.ACTIVE
