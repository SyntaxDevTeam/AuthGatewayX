package pl.syntaxdevteam.logincorex.api

import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.domain.session.AuthenticationMethod
import java.time.Instant
import java.util.UUID

/** Non-blocking live authentication view. No credentials, addresses or storage records. */
interface AuthenticationSnapshotProvider : AuthenticationStatusProvider {
    /** Null for unavailable, expired, PRE_AUTH or consent-pending sessions. */
    fun getAuthentication(minecraftUuid: UUID): AuthenticationSnapshot?
}

/** A point-in-time observation, never a reusable authorization token. */
data class AuthenticationSnapshot(
    val minecraftUuid: UUID,
    val identityType: IdentityType,
    val method: AuthenticationMethod,
    val authenticatedAt: Instant,
    val expiresAt: Instant?,
)
