package pl.syntaxdevteam.logincorex.paper.api

import pl.syntaxdevteam.logincorex.api.AuthenticationSnapshotProvider
import pl.syntaxdevteam.logincorex.api.AuthenticationSnapshot
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import java.time.Instant
import java.util.UUID

/** Reads immutable in-memory session snapshots; no entity access or blocking I/O. */
class PaperAuthenticationStatusProvider(
    private val isReady: () -> Boolean,
    private val findSession: (UUID) -> AuthSession?,
    private val clock: () -> Instant = Instant::now,
) : AuthenticationSnapshotProvider {
    override fun isAuthenticated(minecraftUuid: UUID): Boolean = getAuthentication(minecraftUuid) != null

    override fun getAuthentication(minecraftUuid: UUID): AuthenticationSnapshot? {
        if (!isReady()) return null
        val session = findSession(minecraftUuid) ?: return null
        if (session.state != ConnectionState.ACTIVE || session.minecraftUuid != minecraftUuid) return null
        if (session.expiresAt?.isAfter(clock()) == false) return null
        return AuthenticationSnapshot(
            minecraftUuid, session.identityType ?: return null,
            session.authenticationMethod ?: return null, session.authenticatedAt ?: return null,
            session.expiresAt,
        )
    }
}
