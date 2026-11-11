package pl.syntaxdevteam.logincorex.auth.registration

import pl.syntaxdevteam.logincorex.domain.account.AccountId
import pl.syntaxdevteam.logincorex.domain.account.AccountState
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.AuthAccount
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.logincorex.security.password.Argon2Parameters
import pl.syntaxdevteam.logincorex.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.logincorex.security.audit.SecurityAuditSink
import pl.syntaxdevteam.logincorex.security.audit.SecurityEvent
import pl.syntaxdevteam.logincorex.security.audit.SecurityEventType
import pl.syntaxdevteam.logincorex.security.flood.FloodLimit
import pl.syntaxdevteam.logincorex.security.registration.RegistrationAttemptGate
import pl.syntaxdevteam.logincorex.storage.AccountStorage
import pl.syntaxdevteam.logincorex.storage.AccountCredentials
import pl.syntaxdevteam.logincorex.storage.FailedLoginUpdate
import pl.syntaxdevteam.logincorex.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.logincorex.storage.OfflineRegistration
import pl.syntaxdevteam.logincorex.storage.RegistrationResult
import pl.syntaxdevteam.logincorex.storage.VerifiedMojangIdentity
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
import kotlin.test.assertEquals

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
            assertIs<RegistrationOutcome.Created>(
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

    @Test
    fun `rate limit rejects before hashing and storage and wipes password`() {
        val storage = CapturingStorage()
        val executor = BoundedTaskExecutor(1, 1, "argon-test")
        val events = mutableListOf<SecurityEvent>()
        val gate = RegistrationAttemptGate(
            FloodLimit(1, 1, Duration.ofHours(1)), 10, Duration.ofHours(2),
        )
        val service = RegistrationService(
            storage, Argon2PasswordHasher(Argon2Parameters(1, 8_192, 1)), executor,
            attemptGate = gate,
            auditSink = SecurityAuditSink { event ->
                events += event
                CompletableFuture.completedFuture(null)
            },
        )
        try {
            val first = "secure-password".toCharArray()
            assertIs<RegistrationOutcome.Created>(
                service.register(AccountUsername.parse("FirstPlayer"), InetAddress.getLoopbackAddress(), first)
                    .toCompletableFuture().get(),
            )
            storage.registration = null
            val rejected = "another-password".toCharArray()
            assertEquals(
                RegistrationOutcome.RateLimited,
                service.register(AccountUsername.parse("OtherPlayer"), InetAddress.getLoopbackAddress(), rejected)
                    .toCompletableFuture().get(),
            )
            assertTrue(rejected.all { it == '\u0000' })
            assertEquals(null, storage.registration)
            assertEquals(SecurityEventType.ANTI_BOT_DENY, events.last().type)
            assertEquals("REGISTRATION_RATE_LIMITED", events.last().reasonCode)
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
        override fun bindVerifiedMojangIdentity(identity: VerifiedMojangIdentity) =
            CompletableFuture.completedFuture<MojangIdentityBindingResult>(MojangIdentityBindingResult.IdentityConflict)
        override fun findByUsername(username: AccountUsername) = CompletableFuture.completedFuture<AuthAccount?>(null)
        override fun findPasswordHash(accountId: AccountId) = CompletableFuture.completedFuture<String?>(null)
        override fun findCredentials(username: AccountUsername) = CompletableFuture.completedFuture<AccountCredentials?>(null)
        override fun replacePasswordHash(accountId: AccountId, expectedHash: String?, newHash: String) = CompletableFuture.completedFuture(false)
        override fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant) =
            CompletableFuture.completedFuture(Unit)
        override fun recordLoginFailure(accountId: AccountId, failedAt: Instant, lockThreshold: Int, lockDuration: Duration) =
            CompletableFuture.completedFuture(FailedLoginUpdate(1, null))
        override fun close() = Unit
    }
}
