package pl.syntaxdevteam.logincorex.api

import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Public v1 contract. Live reads never perform I/O. Async lookups use bounded storage. */
interface LoginCoreApi : AuthenticationSnapshotProvider {
    fun getIdentityType(minecraftUuid: UUID): IdentityType?
    fun getAccount(minecraftUuid: UUID): AccountSnapshot?
    fun getSession(minecraftUuid: UUID): AuthenticationSnapshot?
    fun findAccount(minecraftUuid: UUID): CompletionStage<AccountSnapshot?>
    fun findAccountByUsername(username: String): CompletionStage<AccountSnapshot?>
    /** Revokes only the session observed at invocation; never a replacement connection. */
    fun invalidateSession(minecraftUuid: UUID): CompletionStage<Boolean>
    fun events(): AuthEventSource
}

/** No password material, IP addresses, mutable account state or storage implementation. */
data class AccountSnapshot(
    val accountId: UUID,
    val minecraftUuid: UUID,
    val username: String,
    val identityType: IdentityType,
)
