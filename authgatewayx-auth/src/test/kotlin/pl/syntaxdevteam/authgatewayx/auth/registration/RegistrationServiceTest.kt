package pl.syntaxdevteam.authgatewayx.auth.registration

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountState
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2Parameters
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.AccountCredentials
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegistrationServiceTest {
    @Test
    fun `registration hashes password off-thread and wipes caller array`() {
        val storage = CapturingStorage()
        val executor = BoundedTaskExecutor(1, 2, "argon-test")
        val service = RegistrationService(
            storage,
            Argon2PasswordHasher(Argon2Parameters(1, 8_192, 1)),
            executor,
            clock = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC),
        )
        val password = "secure-password".toCharArray()
        try {
            assertIs<RegistrationResult.Created>(
                service.register(AccountUsername.parse("NewPlayer"), InetAddress.getLoopbackAddress(), password)
                    .toCompletableFuture().get(),
            )
            assertTrue(storage.registration!!.passwordHash.startsWith("\$argon2id\$"))
            assertTrue(password.all { it == '\u0000' })
        } finally {
            executor.close()
        }
    }

    @Test
    fun `invalid password is rejected and wiped before scheduling`() {
        val executor = BoundedTaskExecutor(1, 1, "argon-test")
        val password = "short".toCharArray()
        try {
            val service = RegistrationService(
                CapturingStorage(),
                Argon2PasswordHasher(Argon2Parameters(1, 8_192, 1)),
                executor,
            )
            assertFailsWith<IllegalArgumentException> {
                service.register(AccountUsername.parse("NewPlayer"), InetAddress.getLoopbackAddress(), password)
            }
            assertTrue(password.all { it == '\u0000' })
        } finally {
            executor.close()
        }
    }

    private class CapturingStorage : AccountStorage {
        var registration: OfflineRegistration? = null
        override fun migrate(): CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)
        override fun registerOffline(registration: OfflineRegistration): CompletionStage<RegistrationResult> {
            this.registration = registration
            val account = AuthAccount(
                registration.accountId, registration.username, IdentityType.OFFLINE,
                registration.minecraftUuid, AccountState.REGISTERED, registration.createdAt, registration.createdAt,
            )
            return CompletableFuture.completedFuture(RegistrationResult.Created(account))
        }
        override fun findByUsername(username: AccountUsername) = CompletableFuture.completedFuture<AuthAccount?>(null)
        override fun findPasswordHash(accountId: AccountId) = CompletableFuture.completedFuture<String?>(null)
        override fun findCredentials(username: AccountUsername) = CompletableFuture.completedFuture<AccountCredentials?>(null)
        override fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant) =
            CompletableFuture.completedFuture(Unit)
        override fun recordLoginFailure(accountId: AccountId, failedAt: Instant, lockThreshold: Int, lockDuration: Duration) =
            CompletableFuture.completedFuture(FailedLoginUpdate(1, null))
        override fun close() = Unit
    }
}
