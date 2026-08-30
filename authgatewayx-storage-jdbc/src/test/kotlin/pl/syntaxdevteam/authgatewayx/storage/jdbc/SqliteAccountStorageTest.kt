package pl.syntaxdevteam.authgatewayx.storage.jdbc

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.net.InetAddress
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

    private fun registration(usernameValue: String): OfflineRegistration {
        val username = AccountUsername.parse(usernameValue)
        return OfflineRegistration(
            AccountId.random(), username, OfflineIdentity.minecraftUuid(username),
            "\$argon2id\$test-hash", InetAddress.getLoopbackAddress(), Instant.parse("2026-08-30T10:00:00Z"),
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
