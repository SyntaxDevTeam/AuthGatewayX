package pl.syntaxdevteam.authgatewayx.auth.registration

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.CompletionStage

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
) {
    fun register(
        username: AccountUsername,
        sourceAddress: InetAddress,
        password: CharArray,
    ): CompletionStage<RegistrationResult> {
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
            ))
        }
    }
}
