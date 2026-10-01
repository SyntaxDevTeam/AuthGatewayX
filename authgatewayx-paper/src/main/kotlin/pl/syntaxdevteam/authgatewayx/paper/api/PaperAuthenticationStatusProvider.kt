package pl.syntaxdevteam.authgatewayx.paper.api

import pl.syntaxdevteam.authgatewayx.api.AuthenticationStatusProvider
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import java.time.Instant
import java.util.UUID

/** Reads immutable in-memory session snapshots; no entity access or blocking I/O. */
class PaperAuthenticationStatusProvider(
    private val isReady: () -> Boolean,
    private val findSession: (UUID) -> AuthSession?,
    private val clock: () -> Instant = Instant::now,
) : AuthenticationStatusProvider {
    override fun isAuthenticated(minecraftUuid: UUID): Boolean {
        if (!isReady()) return false
        val session = findSession(minecraftUuid) ?: return false
        if (session.state != ConnectionState.ACTIVE || session.minecraftUuid != minecraftUuid) return false
        return session.expiresAt?.isAfter(clock()) ?: true
    }
}
