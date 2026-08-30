package pl.syntaxdevteam.authgatewayx.storage

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

data class OfflineRegistration(
    val accountId: AccountId,
    val username: AccountUsername,
    val minecraftUuid: UUID,
    val passwordHash: String,
    val sourceAddress: InetAddress,
    val createdAt: Instant,
)

sealed interface RegistrationResult {
    data class Created(val account: AuthAccount) : RegistrationResult
    data object UsernameAlreadyExists : RegistrationResult
    data object MinecraftUuidAlreadyExists : RegistrationResult
}

interface AccountStorage : AutoCloseable {
    fun migrate(): CompletionStage<Unit>
    fun registerOffline(registration: OfflineRegistration): CompletionStage<RegistrationResult>
    fun findByUsername(username: AccountUsername): CompletionStage<AuthAccount?>
    fun findPasswordHash(accountId: AccountId): CompletionStage<String?>
}
