package pl.syntaxdevteam.authgatewayx.domain.session

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import java.net.InetAddress
import java.time.Instant
import java.util.UUID

@JvmInline
value class ConnectionId(val value: UUID) {
    companion object {
        fun random(): ConnectionId = ConnectionId(UUID.randomUUID())
    }
}

enum class ConnectionState {
    CONNECTING,
    PRE_AUTH,
    ACTIVE,
    DISCONNECTED,
}

enum class AuthenticationMethod {
    MOJANG,
    PASSWORD,
    TRUSTED_SESSION,
}

@ConsistentCopyVisibility
data class AuthSession private constructor(
    val connectionId: ConnectionId,
    val username: AccountUsername,
    val sourceAddress: InetAddress,
    val state: ConnectionState,
    val createdAt: Instant,
    val accountId: AccountId?,
    val minecraftUuid: UUID?,
    val identityType: IdentityType?,
    val authenticatedAt: Instant?,
    val expiresAt: Instant?,
    val authenticationMethod: AuthenticationMethod?,
) {
    init {
        if (state == ConnectionState.ACTIVE) {
            requireNotNull(accountId) { "An active session requires an account id" }
            requireNotNull(minecraftUuid) { "An active session requires a Minecraft UUID" }
            requireNotNull(identityType) { "An active session requires an identity type" }
            requireNotNull(authenticatedAt) { "An active session requires an authentication timestamp" }
            requireNotNull(authenticationMethod) { "An active session requires an authentication method" }
        }
    }

    fun enterPreAuth(): AuthSession {
        require(state == ConnectionState.CONNECTING) {
            "Only a connecting session can enter PRE_AUTH"
        }
        return copy(state = ConnectionState.PRE_AUTH)
    }

    fun activate(
        accountId: AccountId,
        minecraftUuid: UUID,
        identityType: IdentityType,
        authenticationMethod: AuthenticationMethod,
        authenticatedAt: Instant,
        expiresAt: Instant? = null,
    ): AuthSession {
        require(state == ConnectionState.CONNECTING || state == ConnectionState.PRE_AUTH) {
            "Only a connecting or pre-auth session can become active"
        }
        require(!authenticatedAt.isBefore(createdAt)) {
            "Authentication cannot predate session creation"
        }
        require(expiresAt == null || expiresAt.isAfter(authenticatedAt)) {
            "Session expiry must be later than authentication"
        }
        require(authenticationMethod != AuthenticationMethod.MOJANG || identityType == IdentityType.MOJANG) {
            "Mojang authentication can activate only a Mojang identity"
        }
        require(authenticationMethod != AuthenticationMethod.PASSWORD || identityType == IdentityType.OFFLINE) {
            "Password authentication can activate only an offline identity"
        }

        return copy(
            state = ConnectionState.ACTIVE,
            accountId = accountId,
            minecraftUuid = minecraftUuid,
            identityType = identityType,
            authenticatedAt = authenticatedAt,
            expiresAt = expiresAt,
            authenticationMethod = authenticationMethod,
        )
    }

    fun disconnect(): AuthSession =
        if (state == ConnectionState.DISCONNECTED) this else copy(state = ConnectionState.DISCONNECTED)

    companion object {
        fun connecting(
            connectionId: ConnectionId,
            username: AccountUsername,
            sourceAddress: InetAddress,
            createdAt: Instant,
        ): AuthSession = AuthSession(
            connectionId = connectionId,
            username = username,
            sourceAddress = sourceAddress,
            state = ConnectionState.CONNECTING,
            createdAt = createdAt,
            accountId = null,
            minecraftUuid = null,
            identityType = null,
            authenticatedAt = null,
            expiresAt = null,
            authenticationMethod = null,
        )
    }
}
