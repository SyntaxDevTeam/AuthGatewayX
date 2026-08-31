package pl.syntaxdevteam.authgatewayx.storage.jdbc

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import java.net.InetAddress
import java.nio.file.Files
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqliteAccountStorageTest {
    @Test
    fun `migration is idempotent and registration round-trips`() = withStorage { storage ->
        storage.migrate().toCompletableFuture().get()
        storage.migrate().toCompletableFuture().get()
        val registration = registration("StoredPlayer")

        assertIs<RegistrationResult.Created>(storage.registerOffline(registration).toCompletableFuture().get())
        val stored = assertNotNull(storage.findByUsername(registration.username).toCompletableFuture().get())
        assertEquals(registration.accountId, stored.id)
        assertEquals(registration.passwordHash, storage.findPasswordHash(stored.id).toCompletableFuture().get())
        storage.record(SecurityEvent(
            registration.createdAt, stored.id.value, stored.minecraftUuid, stored.username.value,
            registration.sourceAddress, SecurityEventType.REGISTER, "OFFLINE_PASSWORD",
        )).toCompletableFuture().get()
    }

    @Test
    fun `two concurrent registrations create exactly one account`() = withStorage { storage ->
        storage.migrate().toCompletableFuture().get()
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                callers.submit<RegistrationResult> {
                    start.await()
                    storage.registerOffline(registration("RacePlayer")).toCompletableFuture().get()
                }
            }
            start.countDown()
            val resolved = results.map { it.get() }
            assertEquals(1, resolved.count { it is RegistrationResult.Created })
            assertEquals(1, resolved.count { it is RegistrationResult.UsernameAlreadyExists })
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `registration address limit rolls back the rejected account`() = withStorage { storage ->
        storage.migrate().toCompletableFuture().get()
        assertIs<RegistrationResult.Created>(storage.registerOffline(registration("SlotOne", 2)).toCompletableFuture().get())
        assertIs<RegistrationResult.Created>(storage.registerOffline(registration("SlotTwo", 2)).toCompletableFuture().get())
        assertIs<RegistrationResult.AddressLimitReached>(
            storage.registerOffline(registration("SlotThree", 2)).toCompletableFuture().get(),
        )
        assertNull(storage.findByUsername(AccountUsername.parse("SlotThree")).toCompletableFuture().get())
    }

    @Test
    fun `concurrent failures increment atomically and lock at threshold`() = withStorage { storage ->
        storage.migrate().toCompletableFuture().get()
        val registration = registration("LockPlayer")
        assertIs<RegistrationResult.Created>(storage.registerOffline(registration).toCompletableFuture().get())
        val start = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(5)
        try {
            val updates = (1..5).map {
                callers.submit<FailedLoginUpdate> {
                    start.await()
                    storage.recordLoginFailure(
                        registration.accountId, registration.createdAt, 5, Duration.ofMinutes(10),
                    ).toCompletableFuture().get()
                }
            }
            start.countDown()
            val resolved = updates.map { it.get() }
            assertEquals(listOf(1, 2, 3, 4, 5), resolved.map { it.failedLoginCount }.sorted())
            assertNotNull(resolved.single { it.failedLoginCount == 5 }.lockedUntil)
        } finally {
            callers.shutdownNow()
        }
    }

    private fun registration(usernameValue: String, maximumAccountsPerAddress: Int = 3): OfflineRegistration {
        val username = AccountUsername.parse(usernameValue)
        return OfflineRegistration(
            AccountId.random(), username, OfflineIdentity.minecraftUuid(username),
            "\$argon2id\$test-hash", InetAddress.getLoopbackAddress(), Instant.parse("2026-08-30T10:00:00Z"),
            maximumAccountsPerAddress = maximumAccountsPerAddress,
        )
    }

    private fun withStorage(test: (SqliteAccountStorage) -> Unit) {
        val file = Files.createTempFile("authgatewayx-", ".sqlite")
        val executor = BoundedTaskExecutor(2, 8, "storage-test")
        val storage = SqliteAccountStorage("jdbc:sqlite:$file", 2, executor)
        try {
            test(storage)
        } finally {
            storage.close()
            executor.close()
            Files.deleteIfExists(file)
        }
    }
}
