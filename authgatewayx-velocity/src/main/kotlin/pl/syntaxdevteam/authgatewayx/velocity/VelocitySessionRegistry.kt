package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import pl.syntaxdevteam.authgatewayx.api.AuthEventType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only proxy admission diagnostics. The OFFLINE mode is NOT proof that Paper
 * has verified the player's password. Session UUID is an identifier, never an auth token.
 */
data class ProxySessionSnapshot(
    val sessionId: UUID,
    val playerId: UUID,
    val username: String,
    val mode: SelectedAuthenticationMode,
    val connectedAt: Instant,
    val backend: String?,
)

class VelocitySessionRegistry(
    private val maximumSize: Int,
    private val observe: (AuthEventType, ProxySessionSnapshot) -> Unit = { _, _ -> },
    private val now: () -> Instant = Instant::now,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<UUID, ProxySessionSnapshot>()
    private val players = ConcurrentHashMap<UUID, UUID>()
    private var closed = false

    init { require(maximumSize > 0) }

    @Synchronized fun record(sessionId: UUID, playerId: UUID, username: String, mode: SelectedAuthenticationMode): Boolean {
        if (closed || sessions.containsKey(sessionId) || sessions.size >= maximumSize) return false
        // A reconnect for this account must not inherit the previous connection's state.
        val previous = players.put(playerId, sessionId)
        if (previous != null) sessions.remove(previous)?.let { observed(AuthEventType.PROXY_SESSION_ENDED, it) }
        sessions[sessionId] = ProxySessionSnapshot(sessionId, playerId, username, mode, now(), null)
        observed(AuthEventType.PROXY_SESSION_STARTED, sessions.getValue(sessionId))
        return true
    }

    fun findPlayer(playerId: UUID): ProxySessionSnapshot? = players[playerId]?.let { find(it, playerId) }

    private fun observed(type: AuthEventType, session: ProxySessionSnapshot) { runCatching { observe(type, session) } }

    fun find(sessionId: UUID, playerId: UUID): ProxySessionSnapshot? =
        sessions[sessionId]?.takeIf { it.playerId == playerId && players[playerId] == sessionId }

    @Synchronized fun backend(sessionId: UUID, playerId: UUID, name: String) {
        val previous = find(sessionId, playerId) ?: return
        sessions[sessionId] = previous.copy(backend = name)
        observed(AuthEventType.PROXY_BACKEND_CHANGED, sessions.getValue(sessionId))
    }

    @Synchronized fun discard(sessionId: UUID) {
        val removed = sessions.remove(sessionId) ?: return
        players.remove(removed.playerId, sessionId)
        observed(AuthEventType.PROXY_SESSION_ENDED, removed)
    }

    @Synchronized override fun close() {
        closed = true
        sessions.clear()
        players.clear()
    }
}

/** Backend switch updates the same connection-scoped UUID, without granting Paper authentication. */
class VelocitySessionListener(private val sessions: VelocitySessionRegistry) {
    @Subscribe fun onServerConnected(event: ServerConnectedEvent) {
        sessions.backend(VelocitySessionIds.id(event.player), event.player.uniqueId, event.server.serverInfo.name)
    }

    @Subscribe fun onDisconnect(event: DisconnectEvent) {
        VelocitySessionIds.existing(event.player)?.let(sessions::discard)
    }
}
