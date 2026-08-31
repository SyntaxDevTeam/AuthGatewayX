package pl.syntaxdevteam.authgatewayx.auth.registration

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.security.registration.RegistrationAttemptDecision
import pl.syntaxdevteam.authgatewayx.security.registration.RegistrationAttemptGate
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture

fun interface OfflineRegistrationUseCase {
    fun register(username: AccountUsername, sourceAddress: InetAddress, password: CharArray): CompletionStage<RegistrationOutcome>
}

sealed interface RegistrationOutcome {
    data class Created(val account: AuthAccount) : RegistrationOutcome
    data object UsernameAlreadyExists : RegistrationOutcome
    data object IdentityConflict : RegistrationOutcome
    data object RateLimited : RegistrationOutcome
}

data class PasswordPolicy(val minimumLength: Int = 8, val maximumLength: Int = 128) {
    init { require(minimumLength in 1..maximumLength) }

    fun validate(password: CharArray) {
        require(password.size in minimumLength..maximumLength) {
            "Password length must be between $minimumLength and $maximumLength characters"
        }
    }
}

class RegistrationService(
    private val storage: AccountStorage,
    private val hasher: Argon2PasswordHasher,
    private val passwordExecutor: BoundedTaskExecutor,
    private val passwordPolicy: PasswordPolicy = PasswordPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val attemptGate: RegistrationAttemptGate? = null,
    private val auditSink: SecurityAuditSink? = null,
    private val maximumAccountsPerAddress: Int = 3,
) : OfflineRegistrationUseCase {
    init { require(maximumAccountsPerAddress > 0) }

    override fun register(
        username: AccountUsername,
        sourceAddress: InetAddress,
        password: CharArray,
    ): CompletionStage<RegistrationOutcome> {
        val attemptDecision = attemptGate?.evaluate(sourceAddress) ?: RegistrationAttemptDecision.ALLOW
        if (attemptDecision != RegistrationAttemptDecision.ALLOW) {
            password.fill('\u0000')
            audit(username, sourceAddress, SecurityEventType.ANTI_BOT_DENY, "REGISTRATION_RATE_LIMITED")
            return CompletableFuture.completedFuture(RegistrationOutcome.RateLimited)
        }
        try {
            passwordPolicy.validate(password)
        } catch (failure: Throwable) {
            password.fill('\u0000')
            throw failure
        }

        val hashStage = passwordExecutor.submit { hasher.hash(password) }
        hashStage.whenComplete { _, _ -> password.fill('\u0000') }
        return hashStage.thenCompose { passwordHash ->
            storage.registerOffline(OfflineRegistration(
                accountId = AccountId.random(),
                username = username,
                minecraftUuid = OfflineIdentity.minecraftUuid(username),
                passwordHash = passwordHash,
                sourceAddress = sourceAddress,
                createdAt = clock.instant(),
                maximumAccountsPerAddress = maximumAccountsPerAddress,
            )).thenApply { result ->
                when (result) {
                    is RegistrationResult.Created -> {
                        audit(username, sourceAddress, SecurityEventType.REGISTER, "OFFLINE_PASSWORD", result.account.id)
                        RegistrationOutcome.Created(result.account)
                    }
                    RegistrationResult.UsernameAlreadyExists -> RegistrationOutcome.UsernameAlreadyExists
                    RegistrationResult.MinecraftUuidAlreadyExists -> RegistrationOutcome.IdentityConflict
                    RegistrationResult.AddressLimitReached -> {
                        audit(username, sourceAddress, SecurityEventType.ANTI_BOT_DENY, "REGISTRATION_ADDRESS_LIMIT")
                        RegistrationOutcome.RateLimited
                    }
                }
            }
        }
    }

    private fun audit(username: AccountUsername, sourceAddress: InetAddress, type: SecurityEventType, reason: String, accountId: AccountId? = null) {
        runCatching {
            auditSink?.record(SecurityEvent(
                clock.instant(), accountId?.value, OfflineIdentity.minecraftUuid(username), username.value,
                sourceAddress, type, reason,
            ))
        }
    }
}
