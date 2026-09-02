package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import java.time.Clock

enum class LogoutResult { LOGGED_OUT, NOT_ACTIVE, NOT_OFFLINE_ACCOUNT }

class LogoutService(
    private val sessions: SessionRegistry,
    private val auditSink: SecurityAuditSink,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun logout(connectionId: ConnectionId): LogoutResult {
        val session = sessions.get(connectionId) ?: return LogoutResult.NOT_ACTIVE
        val accountId = session.accountId
        if (session.identityType != IdentityType.OFFLINE || accountId == null) {
            return LogoutResult.NOT_OFFLINE_ACCOUNT
        }
        sessions.disconnect(connectionId) ?: return LogoutResult.NOT_ACTIVE
        runCatching {
            auditSink.record(SecurityEvent(
                clock.instant(), accountId.value, session.minecraftUuid, session.username.value,
                session.sourceAddress, SecurityEventType.SESSION_INVALIDATED, "PLAYER_LOGOUT",
            ))
        }
        return LogoutResult.LOGGED_OUT
    }
}
