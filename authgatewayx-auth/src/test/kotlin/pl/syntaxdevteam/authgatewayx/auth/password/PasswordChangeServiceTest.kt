package pl.syntaxdevteam.authgatewayx.auth.password

import pl.syntaxdevteam.authgatewayx.auth.registration.PasswordPolicy
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.security.audit.*
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2Parameters
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.*
import java.net.InetAddress
import java.time.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.*

class PasswordChangeServiceTest {
    private val username = AccountUsername.parse("PasswordUser")
    private val address = InetAddress.getLoopbackAddress()
    private val now = Instant.parse("2026-09-02T12:00:00Z")

    @Test
    fun `self change verifies old password and atomically replaces hash`() {
        val hasher = Argon2PasswordHasher(Argon2Parameters(1, 8192, 1))
        val storage = FakeStorage(account(), hasher.hash("old-password".toCharArray()))
        val audit = CapturingAudit()
        BoundedTaskExecutor(1, 4, "password-change-test").use { executor ->
            val result = service(storage, hasher, executor, audit).changeOwnPassword(
                username, address, "old-password".toCharArray(), "new-password".toCharArray(),
            ).toCompletableFuture().get()

            assertEquals(PasswordChangeResult.CHANGED, result)
            assertTrue(hasher.verify(storage.hash, "new-password".toCharArray()))
            assertEquals(SecurityEventType.PASSWORD_CHANGE, audit.events.single().type)
        }
    }

    @Test
    fun `wrong current password does not replace hash`() {
        val hasher = Argon2PasswordHasher(Argon2Parameters(1, 8192, 1))
        val original = hasher.hash("old-password".toCharArray())
        val storage = FakeStorage(account(), original)
        BoundedTaskExecutor(1, 4, "password-change-test").use { executor ->
            val result = service(storage, hasher, executor, CapturingAudit()).changeOwnPassword(
                username, address, "wrong-password".toCharArray(), "new-password".toCharArray(),
            ).toCompletableFuture().get()
            assertEquals(PasswordChangeResult.INVALID_CURRENT_PASSWORD, result)
            assertEquals(original, storage.hash)
        }
    }

    private fun service(storage: FakeStorage, hasher: Argon2PasswordHasher, executor: BoundedTaskExecutor, audit: SecurityAuditSink) =
        PasswordChangeService(storage, hasher, executor, PasswordPolicy(8, 128), audit, Clock.fixed(now, ZoneOffset.UTC))

    private fun account() = AuthAccount(AccountId.random(), username, IdentityType.OFFLINE, OfflineIdentity.minecraftUuid(username), AccountState.REGISTERED, now, now)

    private class FakeStorage(private val account: AuthAccount, var hash: String) : AccountStorage {
        override fun findCredentials(username: AccountUsername) = CompletableFuture.completedFuture(AccountCredentials(account, hash, 0, null))
        override fun replacePasswordHash(accountId: AccountId, expectedHash: String?, newHash: String): CompletionStage<Boolean> {
            if (expectedHash != null && expectedHash != hash) return CompletableFuture.completedFuture(false)
            hash = newHash
            return CompletableFuture.completedFuture(true)
        }
        override fun findByUsername(username: AccountUsername) = CompletableFuture.completedFuture<AuthAccount?>(account)
        override fun findPasswordHash(accountId: AccountId) = CompletableFuture.completedFuture<String?>(hash)
        override fun migrate() = CompletableFuture.completedFuture(Unit)
        override fun registerOffline(registration: OfflineRegistration) = CompletableFuture.completedFuture<RegistrationResult>(RegistrationResult.UsernameAlreadyExists)
        override fun bindVerifiedMojangIdentity(identity: VerifiedMojangIdentity) = CompletableFuture.completedFuture<MojangIdentityBindingResult>(MojangIdentityBindingResult.IdentityConflict)
        override fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant) = CompletableFuture.completedFuture(Unit)
        override fun recordLoginFailure(accountId: AccountId, failedAt: Instant, lockThreshold: Int, lockDuration: Duration) = CompletableFuture.completedFuture(FailedLoginUpdate(1, null))
        override fun close() = Unit
    }

    private class CapturingAudit : SecurityAuditSink {
        val events = mutableListOf<SecurityEvent>()
        override fun record(event: SecurityEvent): CompletionStage<Void> { events += event; return CompletableFuture.completedFuture(null) }
    }
}
