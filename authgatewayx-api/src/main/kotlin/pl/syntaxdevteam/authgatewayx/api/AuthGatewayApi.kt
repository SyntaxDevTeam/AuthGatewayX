package pl.syntaxdevteam.authgatewayx.api

import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import java.util.UUID
import java.util.concurrent.CompletionStage

interface AuthGatewayApi {
    fun isAuthenticated(minecraftUuid: UUID): Boolean

    fun getIdentityType(minecraftUuid: UUID): IdentityType?

    fun getAccount(minecraftUuid: UUID): AuthAccount?

    fun getSession(minecraftUuid: UUID): AuthSession?

    fun findAccount(minecraftUuid: UUID): CompletionStage<AuthAccount?>

    fun invalidateSession(minecraftUuid: UUID): CompletionStage<Boolean>
}
