package pl.syntaxdevteam.authgatewayx.storage

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import java.net.InetAddress
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage

data class OfflineRegistration(
    val accountId: AccountId,
    val username: AccountUsername,
    val minecraftUuid: UUID,
    val passwordHash: String,
    val sourceAddress: InetAddress,
    val createdAt: Instant,
    val maximumAccountsPerAddress: Int,
)

sealed interface RegistrationResult {
    data class Created(val account: AuthAccount) : RegistrationResult
    data object UsernameAlreadyExists : RegistrationResult
    data object MinecraftUuidAlreadyExists : RegistrationResult
    data object AddressLimitReached : RegistrationResult
}

data class AccountCredentials(
    val account: AuthAccount,
    val passwordHash: String,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
)

data class FailedLoginUpdate(
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
)

interface AccountStorage : AutoCloseable {
    fun migrate(): CompletionStage<Unit>
    fun registerOffline(registration: OfflineRegistration): CompletionStage<RegistrationResult>
    fun findByUsername(username: AccountUsername): CompletionStage<AuthAccount?>
    fun findPasswordHash(accountId: AccountId): CompletionStage<String?>
    fun findCredentials(username: AccountUsername): CompletionStage<AccountCredentials?>
    fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant): CompletionStage<Unit>
    fun recordLoginFailure(
        accountId: AccountId,
        failedAt: Instant,
        lockThreshold: Int,
        lockDuration: Duration,
    ): CompletionStage<FailedLoginUpdate>
}
