package pl.syntaxdevteam.authgatewayx.security.audit

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class SecurityEventType {
    REGISTER,
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    ACCOUNT_LOCK,
    ACCOUNT_UNLOCK,
    PREMIUM_VERIFIED,
    PREMIUM_AUTH_FAILURE,
    OFFLINE_TO_PREMIUM_MIGRATION,
    USERNAME_POLICY_DENY,
    PUNISHMENT_DENY,
    ANTI_BOT_DENY,
    ANTI_FLOOD_DENY,
    SESSION_INVALIDATED,
}

data class SecurityEvent(
    val timestamp: Instant,
    val accountId: UUID?,
    val minecraftUuid: UUID?,
    val username: String,
    val sourceAddress: InetAddress,
    val type: SecurityEventType,
    val reasonCode: String,
)

fun interface SecurityAuditSink {
    fun record(event: SecurityEvent): CompletionStage<Void>
}
