package pl.syntaxdevteam.logincorex.api

import java.time.Instant
import java.util.UUID
import java.util.function.Consumer

/** Observations only: cannot cancel or override authentication/security decisions. */
enum class AuthEventType {
    PRE_AUTHENTICATION, AUTHENTICATION_SUCCESS, AUTHENTICATION_FAILED,
    PLAYER_REGISTERED, PLAYER_LOGGED_OUT, SESSION_ENDED, PREMIUM_IDENTITY_VERIFIED,
    ACCOUNT_IDENTITY_CHANGED, SECURITY_POLICY_DENIED, BOT_CONNECTION_DENIED,
    FLOOD_CONNECTION_DENIED, PASSWORD_CHANGED, ACCOUNT_LOCKED,
    PROXY_SESSION_STARTED, PROXY_SESSION_ENDED, PROXY_BACKEND_CHANGED,
}

enum class AuthFailureReason {
    INVALID_CREDENTIALS, ACCOUNT_LOCKED, RATE_LIMITED, ACCOUNT_ALREADY_EXISTS,
    IDENTITY_CONFLICT, ACCESS_DENIED, INTERNAL_ERROR,
}

/** UUID may be unknown before verification. connectionId identifies a physical login generation. */
data class AuthEvent(
    val type: AuthEventType,
    val occurredAt: Instant,
    val minecraftUuid: UUID?,
    val username: String,
    val connectionId: UUID?,
    val authentication: AuthenticationSnapshot?,
    val failureReason: AuthFailureReason? = null,
)

interface AuthEventSource {
    /** Async callbacks; use the platform scheduler for player/world access.
     * Close on consumer disable. Throws if closed or subscription capacity is exhausted.
     * Delivery is bounded and best effort; events must never be used as authorization tokens.
     */
    fun subscribe(listener: Consumer<AuthEvent>): AuthSubscription
    fun droppedEvents(): Long
}

fun interface AuthSubscription : AutoCloseable {
    override fun close()
}
