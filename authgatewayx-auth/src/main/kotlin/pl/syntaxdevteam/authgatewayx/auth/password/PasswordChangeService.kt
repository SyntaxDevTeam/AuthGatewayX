package pl.syntaxdevteam.authgatewayx.auth.password

import pl.syntaxdevteam.authgatewayx.auth.registration.PasswordPolicy
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

enum class PasswordChangeResult { CHANGED, INVALID_CURRENT_PASSWORD, ACCOUNT_NOT_FOUND, NOT_OFFLINE_ACCOUNT, CONCURRENT_CHANGE }

class PasswordChangeService(
    private val storage: AccountStorage,
    private val hasher: Argon2PasswordHasher,
    private val passwordExecutor: BoundedTaskExecutor,
    private val passwordPolicy: PasswordPolicy,
    private val auditSink: SecurityAuditSink,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun changeOwnPassword(
        username: AccountUsername,
        sourceAddress: InetAddress,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): CompletionStage<PasswordChangeResult> {
        try {
            passwordPolicy.validate(newPassword)
        } catch (failure: Throwable) {
            currentPassword.fill('\u0000')
            newPassword.fill('\u0000')
            throw failure
        }
        return storage.findCredentials(username).thenCompose { credentials ->
            if (credentials == null) {
                currentPassword.fill('\u0000')
                newPassword.fill('\u0000')
                return@thenCompose CompletableFuture.completedFuture(PasswordChangeResult.ACCOUNT_NOT_FOUND)
            }
            passwordExecutor.submit {
                if (!hasher.verify(credentials.passwordHash, currentPassword)) {
                    newPassword.fill('\u0000')
                    null
                } else {
                    hasher.hash(newPassword)
                }
            }.thenCompose { newHash ->
                if (newHash == null) CompletableFuture.completedFuture(PasswordChangeResult.INVALID_CURRENT_PASSWORD)
                else storage.replacePasswordHash(credentials.account.id, credentials.passwordHash, newHash).thenApply { replaced ->
                    if (!replaced) PasswordChangeResult.CONCURRENT_CHANGE
                    else {
                        audit(credentials.account.id, username, sourceAddress, SecurityEventType.PASSWORD_CHANGE)
                        PasswordChangeResult.CHANGED
                    }
                }
            }
        }.whenComplete { _, _ -> currentPassword.fill('\u0000'); newPassword.fill('\u0000') }
    }

    fun setPasswordByAdministrator(
        username: AccountUsername,
        sourceAddress: InetAddress,
        newPassword: CharArray,
    ): CompletionStage<PasswordChangeResult> {
        try {
            passwordPolicy.validate(newPassword)
        } catch (failure: Throwable) {
            newPassword.fill('\u0000')
            throw failure
        }
        return storage.findCredentials(username).thenCompose { credentials ->
            if (credentials == null) {
                newPassword.fill('\u0000')
                return@thenCompose storage.findByUsername(username).thenApply { account ->
                    if (account == null) PasswordChangeResult.ACCOUNT_NOT_FOUND else PasswordChangeResult.NOT_OFFLINE_ACCOUNT
                }
            }
            passwordExecutor.submit { hasher.hash(newPassword) }.thenCompose { newHash ->
                storage.replacePasswordHash(credentials.account.id, null, newHash).thenApply { replaced ->
                    if (!replaced) PasswordChangeResult.CONCURRENT_CHANGE
                    else {
                        audit(credentials.account.id, username, sourceAddress, SecurityEventType.ADMIN_PASSWORD_RESET)
                        PasswordChangeResult.CHANGED
                    }
                }
            }
        }.whenComplete { _, _ -> newPassword.fill('\u0000') }
    }

    private fun audit(accountId: AccountId, username: AccountUsername, address: InetAddress, type: SecurityEventType) {
        runCatching { auditSink.record(SecurityEvent(clock.instant(), accountId.value, null, username.value, address, type, type.name)) }
    }
}
