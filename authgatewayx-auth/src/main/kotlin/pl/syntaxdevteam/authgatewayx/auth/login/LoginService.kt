package pl.syntaxdevteam.authgatewayx.auth.login

import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.login.LoginAttemptDecision
import pl.syntaxdevteam.authgatewayx.security.login.LoginAttemptGate
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.AccountCredentials
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

sealed interface LoginResult {
    data class Success(val account: AuthAccount) : LoginResult
    data object InvalidCredentials : LoginResult
    data object AccountLocked : LoginResult
    data object RateLimited : LoginResult
}

data class LockoutPolicy(val threshold: Int = 5, val duration: Duration = Duration.ofMinutes(10)) {
    init { require(threshold > 0 && !duration.isZero && !duration.isNegative) }
}

fun interface OfflineLoginUseCase {
    fun login(username: AccountUsername, sourceAddress: InetAddress, password: CharArray): CompletionStage<LoginResult>
}

class LoginService(
    private val storage: AccountStorage,
    private val hasher: Argon2PasswordHasher,
    private val passwordExecutor: BoundedTaskExecutor,
    private val attemptGate: LoginAttemptGate,
    private val auditSink: SecurityAuditSink,
    private val dummyPasswordHash: String,
    private val lockoutPolicy: LockoutPolicy = LockoutPolicy(),
    private val clock: Clock = Clock.systemUTC(),
) : OfflineLoginUseCase {
    override fun login(username: AccountUsername, sourceAddress: InetAddress, password: CharArray): CompletionStage<LoginResult> {
        if (attemptGate.evaluate(sourceAddress) != LoginAttemptDecision.ALLOW) {
            password.fill('\u0000')
            audit(username, sourceAddress, null, SecurityEventType.LOGIN_FAILURE, "RATE_LIMITED")
            return CompletableFuture.completedFuture(LoginResult.RateLimited)
        }
        return storage.findCredentials(username).thenCompose { credentials ->
            val now = clock.instant()
            if (credentials?.lockedUntil?.isAfter(now) == true) {
                password.fill('\u0000')
                audit(username, sourceAddress, credentials, SecurityEventType.LOGIN_FAILURE, "ACCOUNT_LOCKED")
                CompletableFuture.completedFuture(LoginResult.AccountLocked)
            } else {
                verifyAndComplete(username, sourceAddress, password, credentials, now)
            }
        }
    }

    private fun verifyAndComplete(
        username: AccountUsername,
        sourceAddress: InetAddress,
        password: CharArray,
        credentials: AccountCredentials?,
        now: java.time.Instant,
    ): CompletionStage<LoginResult> {
        val verification = passwordExecutor.submit {
            hasher.verify(credentials?.passwordHash ?: dummyPasswordHash, password)
        }
        verification.whenComplete { _, _ -> password.fill('\u0000') }
        return verification.thenCompose { matches ->
            if (matches && credentials != null) {
                storage.recordLoginSuccess(credentials.account.id, sourceAddress, now).thenApply {
                    audit(username, sourceAddress, credentials, SecurityEventType.LOGIN_SUCCESS, "PASSWORD")
                    LoginResult.Success(credentials.account)
                }
            } else if (credentials != null) {
                storage.recordLoginFailure(
                    credentials.account.id, now, lockoutPolicy.threshold, lockoutPolicy.duration,
                ).thenApply { update ->
                    val locked = update.lockedUntil != null
                    audit(username, sourceAddress, credentials, if (locked) SecurityEventType.ACCOUNT_LOCK else SecurityEventType.LOGIN_FAILURE, "INVALID_CREDENTIALS")
                    if (locked) LoginResult.AccountLocked else LoginResult.InvalidCredentials
                }
            } else {
                audit(username, sourceAddress, null, SecurityEventType.LOGIN_FAILURE, "INVALID_CREDENTIALS")
                CompletableFuture.completedFuture(LoginResult.InvalidCredentials)
            }
        }
    }

    private fun audit(
        username: AccountUsername,
        sourceAddress: InetAddress,
        credentials: AccountCredentials?,
        type: SecurityEventType,
        reason: String,
    ) {
        runCatching {
            auditSink.record(SecurityEvent(
                clock.instant(), credentials?.account?.id?.value, credentials?.account?.minecraftUuid,
                username.value, sourceAddress, type, reason,
            ))
        }
    }
}
