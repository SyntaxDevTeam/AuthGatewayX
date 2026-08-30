package pl.syntaxdevteam.authgatewayx.auth.login

import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.FloodLimit
import pl.syntaxdevteam.authgatewayx.security.login.LoginAttemptGate
import pl.syntaxdevteam.authgatewayx.security.password.Argon2Parameters
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.*
import java.net.InetAddress
import java.time.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.*

class LoginServiceTest {
    private val now = Instant.parse("2026-08-30T10:00:00Z")
    private val username = AccountUsername.parse("LoginPlayer")
    private val address = InetAddress.getLoopbackAddress()
    private val hasher = Argon2PasswordHasher(Argon2Parameters(1, 8_192, 1))

    @Test
    fun `successful login resets failures and emits audit without secrets`() {
        val realHash = hasher.hash("valid-password".toCharArray())
        val storage = FakeStorage(credentials(realHash))
        val audit = CapturingAudit()
        val executor = BoundedTaskExecutor(1, 2, "login-test")
        try {
            val service = service(storage, audit, executor, realHash)
            val password = "valid-password".toCharArray()
            assertIs<LoginResult.Success>(service.login(username, address, password).toCompletableFuture().get())
            assertEquals(1, storage.successes)
            assertEquals("PASSWORD", audit.events.single().reasonCode)
            assertTrue(password.all { it == '\u0000' })
        } finally { executor.close() }
    }

    @Test
    fun `rate limit rejects before storage lookup`() {
        val dummyHash = hasher.hash("dummy-password".toCharArray())
        val storage = FakeStorage(null)
        val audit = CapturingAudit()
        val executor = BoundedTaskExecutor(1, 1, "login-test")
        try {
            val gate = LoginAttemptGate(FloodLimit(1, 1, Duration.ofHours(1)), 10)
            val service = LoginService(storage, hasher, executor, gate, audit, dummyHash, clock = fixedClock())
            assertIs<LoginResult.InvalidCredentials>(service.login(username, address, "wrong-password".toCharArray()).toCompletableFuture().get())
            val rejectedPassword = "wrong-password".toCharArray()
            assertIs<LoginResult.RateLimited>(service.login(username, address, rejectedPassword).toCompletableFuture().get())
            assertEquals(1, storage.lookups)
            assertTrue(rejectedPassword.all { it == '\u0000' })
        } finally { executor.close() }
    }

    private fun service(storage: FakeStorage, audit: CapturingAudit, executor: BoundedTaskExecutor, dummyHash: String) =
        LoginService(storage, hasher, executor,
            LoginAttemptGate(FloodLimit(10, 10, Duration.ofMinutes(1)), 10), audit, dummyHash,
            clock = fixedClock())

    private fun fixedClock() = Clock.fixed(now, ZoneOffset.UTC)

    private fun credentials(hash: String): AccountCredentials {
        val account = AuthAccount(AccountId.random(), username, IdentityType.OFFLINE, OfflineIdentity.minecraftUuid(username), AccountState.REGISTERED, now, now)
        return AccountCredentials(account, hash, 2, null)
    }

    private class FakeStorage(private val credentials: AccountCredentials?) : AccountStorage {
        var lookups = 0
        var successes = 0
        override fun findCredentials(username: AccountUsername): CompletionStage<AccountCredentials?> { lookups++; return CompletableFuture.completedFuture(credentials) }
        override fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant): CompletionStage<Unit> { successes++; return CompletableFuture.completedFuture(Unit) }
        override fun recordLoginFailure(accountId: AccountId, failedAt: Instant, lockThreshold: Int, lockDuration: Duration) = CompletableFuture.completedFuture(FailedLoginUpdate(1, null))
        override fun migrate() = CompletableFuture.completedFuture(Unit)
        override fun registerOffline(registration: OfflineRegistration) = CompletableFuture.completedFuture<RegistrationResult>(RegistrationResult.UsernameAlreadyExists)
        override fun findByUsername(username: AccountUsername) = CompletableFuture.completedFuture<AuthAccount?>(null)
        override fun findPasswordHash(accountId: AccountId) = CompletableFuture.completedFuture<String?>(null)
        override fun close() = Unit
    }

    private class CapturingAudit : SecurityAuditSink {
        val events = mutableListOf<SecurityEvent>()
        override fun record(event: SecurityEvent): CompletionStage<Void> {
            events += event
            return CompletableFuture.completedFuture(null)
        }
    }
}
