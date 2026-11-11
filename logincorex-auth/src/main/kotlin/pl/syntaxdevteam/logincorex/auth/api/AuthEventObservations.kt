package pl.syntaxdevteam.logincorex.auth.api

import pl.syntaxdevteam.logincorex.integrations.api.BoundedAuthEventBus
import pl.syntaxdevteam.logincorex.api.*
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import pl.syntaxdevteam.logincorex.security.audit.SecurityEvent
import pl.syntaxdevteam.logincorex.security.audit.SecurityEventType
import java.time.Instant

fun BoundedAuthEventBus.observeSession(session: AuthSession) {
    val type = when (session.state) {
        ConnectionState.PRE_AUTH -> AuthEventType.PRE_AUTHENTICATION
        ConnectionState.DISCONNECTED -> AuthEventType.SESSION_ENDED
        else -> return
    }
    publish(AuthEvent(type, Instant.now(), session.minecraftUuid, session.username.value, session.sessionNonce, null))
}

/** Security observations do not imply storage persistence or permission to enter the world. */
fun BoundedAuthEventBus.observeSecurity(event: SecurityEvent) {
    val type = when (event.type) {
        SecurityEventType.REGISTER -> AuthEventType.PLAYER_REGISTERED
        SecurityEventType.LOGIN_FAILURE, SecurityEventType.PREMIUM_AUTH_FAILURE -> AuthEventType.AUTHENTICATION_FAILED
        SecurityEventType.ACCOUNT_LOCK -> AuthEventType.ACCOUNT_LOCKED
        SecurityEventType.PREMIUM_VERIFIED -> AuthEventType.PREMIUM_IDENTITY_VERIFIED
        SecurityEventType.OFFLINE_TO_PREMIUM_MIGRATION, SecurityEventType.PREMIUM_RECOVERY_COMPLETED -> AuthEventType.ACCOUNT_IDENTITY_CHANGED
        SecurityEventType.USERNAME_POLICY_DENY, SecurityEventType.PUNISHMENT_DENY -> AuthEventType.SECURITY_POLICY_DENIED
        SecurityEventType.ANTI_BOT_DENY -> AuthEventType.BOT_CONNECTION_DENIED
        SecurityEventType.ANTI_FLOOD_DENY -> AuthEventType.FLOOD_CONNECTION_DENIED
        SecurityEventType.SESSION_INVALIDATED -> if (event.reasonCode == "PLAYER_LOGOUT") AuthEventType.PLAYER_LOGGED_OUT else return
        SecurityEventType.PASSWORD_CHANGE, SecurityEventType.ADMIN_PASSWORD_RESET -> AuthEventType.PASSWORD_CHANGED
        else -> return
    }
    publish(AuthEvent(type, event.timestamp, event.minecraftUuid, event.username, null, null))
}
