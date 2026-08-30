package pl.syntaxdevteam.authgatewayx.paper.isolation

import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import java.util.UUID

fun interface PreAuthAccess {
    fun isPreAuth(playerId: UUID): Boolean
}

class SessionPreAuthAccess(private val sessions: SessionRegistry) : PreAuthAccess {
    override fun isPreAuth(playerId: UUID): Boolean =
        sessions.get(ConnectionId(playerId))?.state == ConnectionState.PRE_AUTH
}
