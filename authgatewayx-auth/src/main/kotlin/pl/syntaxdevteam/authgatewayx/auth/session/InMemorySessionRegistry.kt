package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.AuthenticationMethod
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class InMemorySessionRegistry : SessionRegistry {
    private val sessions = ConcurrentHashMap<ConnectionId, AtomicReference<AuthSession>>()
    private val activeAccounts = ConcurrentHashMap<AccountId, ConnectionId>()
    private val activeMinecraftIds = ConcurrentHashMap<UUID, ConnectionId>()

    override fun create(session: AuthSession): Boolean =
        sessions.putIfAbsent(session.connectionId, AtomicReference(session)) == null

    override fun get(connectionId: ConnectionId): AuthSession? = sessions[connectionId]?.get()

    override fun getActive(minecraftUuid: UUID): AuthSession? =
        activeMinecraftIds[minecraftUuid]?.let(::get)?.takeIf { it.isActive() }

    override fun enterPreAuth(connectionId: ConnectionId): AuthSession =
        update(connectionId, AuthSession::enterPreAuth)

    override fun activate(
        connectionId: ConnectionId,
        accountId: AccountId,
        minecraftUuid: UUID,
        identityType: IdentityType,
        authenticationMethod: AuthenticationMethod,
        authenticatedAt: Instant,
        expiresAt: Instant?,
    ): AuthSession {
        val ref = sessions[connectionId] ?: throw UnknownSessionException(connectionId)
        synchronized(ref) {
            val activated = ref.get().activate(
                accountId,
                minecraftUuid,
                identityType,
                authenticationMethod,
                authenticatedAt,
                expiresAt,
            )
            val owningConnection = activeAccounts.putIfAbsent(accountId, connectionId)
            if (owningConnection != null && owningConnection != connectionId) {
                throw SessionAlreadyActiveException(accountId)
            }
            val owningMinecraftConnection = activeMinecraftIds.putIfAbsent(minecraftUuid, connectionId)
            if (owningMinecraftConnection != null && owningMinecraftConnection != connectionId) {
                activeAccounts.remove(accountId, connectionId)
                throw IllegalStateException("Minecraft UUID $minecraftUuid already has an active session")
            }
            ref.set(activated)
            return activated
        }
    }

    override fun disconnect(connectionId: ConnectionId): AuthSession? {
        val ref = sessions[connectionId] ?: return null
        synchronized(ref) {
            val current = ref.get()
            val disconnected = current.disconnect()
            ref.set(disconnected)
            current.accountId?.let { activeAccounts.remove(it, connectionId) }
            current.minecraftUuid?.let { activeMinecraftIds.remove(it, connectionId) }
            sessions.remove(connectionId, ref)
            return disconnected
        }
    }

    override fun clear() {
        sessions.clear()
        activeAccounts.clear()
        activeMinecraftIds.clear()
    }

    override fun size(): Int = sessions.size

    private fun update(connectionId: ConnectionId, transition: (AuthSession) -> AuthSession): AuthSession {
        val ref = sessions[connectionId] ?: throw UnknownSessionException(connectionId)
        synchronized(ref) {
            val current = ref.get()
            val updated = transition(current)
            ref.set(updated)
            return updated
        }
    }
}
