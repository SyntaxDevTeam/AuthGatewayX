package pl.syntaxdevteam.logincorex.paper.isolation

import pl.syntaxdevteam.logincorex.auth.session.SessionRegistry
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import java.util.UUID

fun interface PreAuthAccess {
    fun isPreAuth(playerId: UUID): Boolean
}

class SessionPreAuthAccess(private val sessions: SessionRegistry) : PreAuthAccess {
    override fun isPreAuth(playerId: UUID): Boolean =
        sessions.get(ConnectionId(playerId))?.state == ConnectionState.PRE_AUTH
}
