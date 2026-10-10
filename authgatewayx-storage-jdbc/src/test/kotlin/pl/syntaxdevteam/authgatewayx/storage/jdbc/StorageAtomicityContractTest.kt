package pl.syntaxdevteam.authgatewayx.storage.jdbc

import org.junit.jupiter.api.Assumptions.assumeTrue
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.storage.*
import java.net.InetAddress
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StorageAtomicityContractTest {
    @Test fun `SQLite satisfies atomicity contract`() = exercise(JdbcDatabaseType.SQLITE)
    @Test fun `MySQL satisfies atomicity contract`() = exercise(JdbcDatabaseType.MYSQL)
    @Test fun `MariaDB satisfies atomicity contract`() = exercise(JdbcDatabaseType.MARIADB)
    @Test fun `PostgreSQL satisfies atomicity contract`() = exercise(JdbcDatabaseType.POSTGRESQL)

    private fun exercise(type: JdbcDatabaseType) {
        val temporary = if (type == JdbcDatabaseType.SQLITE) Files.createTempFile("agx-contract-", ".db") else null
        val prefix = "AGX_TEST_${type.name}"
        val url = temporary?.let { "jdbc:sqlite:$it" } ?: System.getenv("${prefix}_URL")
        if (type != JdbcDatabaseType.SQLITE && System.getenv("AGX_REQUIRE_REMOTE_DATABASES") == "true") {
            assertTrue(!url.isNullOrBlank(), "Missing ${prefix}_URL")
        }
        assumeTrue(!url.isNullOrBlank(), "${type.name} not configured")
        val executor = BoundedTaskExecutor(4, 32, "atomicity-contract")
        val storage = JdbcAccountStorage(url!!, 4, executor, type,
            System.getenv("${prefix}_USER"), System.getenv("${prefix}_PASSWORD"))
        try {
            storage.migrate().toCompletableFuture().get(30, TimeUnit.SECONDS)
            storage.migrate().toCompletableFuture().get(30, TimeUnit.SECONDS)
            val base = registration()
            val sameName = race(2) { storage.registerOffline(base.copy(accountId = AccountId.random())).toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertEquals(1, sameName.count { it is RegistrationResult.Created })
            assertEquals(1, sameName.count { it is RegistrationResult.UsernameAlreadyExists })

            val sharedAddress = syntheticAddress()
            val attempts = List(2) { registration().copy(sourceAddress = sharedAddress, maximumAccountsPerAddress = 1) }
            val slots = race(2) { index -> storage.registerOffline(attempts[index]).toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertEquals(1, slots.count { it is RegistrationResult.Created })
            assertEquals(1, slots.count { it is RegistrationResult.AddressLimitReached })
            val rejected = attempts[slots.indexOfFirst { it is RegistrationResult.AddressLimitReached }]
            assertNull(storage.findByUsername(rejected.username).toCompletableFuture().get(10, TimeUnit.SECONDS))

            val created = assertNotNull(storage.findByUsername(base.username).toCompletableFuture().get(10, TimeUnit.SECONDS))
            val failures = race(5) {
                storage.recordLoginFailure(created.id, base.createdAt, 5, Duration.ofMinutes(10))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS)
            }
            assertEquals(listOf(1, 2, 3, 4, 5), failures.map { it.failedLoginCount }.sorted())
            assertNotNull(storage.findCredentials(base.username).toCompletableFuture().get(10, TimeUnit.SECONDS)?.lockedUntil)
            val replacements = race(2) { index ->
                storage.replacePasswordHash(created.id, base.passwordHash, "replacement-$index")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS)
            }
            assertEquals(1, replacements.count { it })
            assertEquals("replacement-${replacements.indexOf(true)}",
                storage.findPasswordHash(created.id).toCompletableFuture().get(10, TimeUnit.SECONDS))
            storage.recordLoginSuccess(created.id, base.sourceAddress, base.createdAt.plusSeconds(600)).toCompletableFuture().get(10, TimeUnit.SECONDS)
            val reset = assertNotNull(storage.findCredentials(base.username).toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertEquals(0, reset.failedLoginCount)
            assertNull(reset.lockedUntil)

            storage.close()
            assertFails { storage.findCredentials(base.username).toCompletableFuture().get(10, TimeUnit.SECONDS) }
        } finally {
            storage.close()
            executor.close()
            temporary?.let { Files.deleteIfExists(it) }
        }
    }

    private fun registration(): OfflineRegistration {
        val username = AccountUsername.parse("Ct${UUID.randomUUID().toString().replace("-", "").take(12)}")
        return OfflineRegistration(AccountId.random(), username, OfflineIdentity.minecraftUuid(username), "synthetic-hash",
            syntheticAddress(), Instant.parse("2026-10-10T00:00:00Z"), 1000)
    }

    private fun syntheticAddress(): InetAddress {
        val id = UUID.randomUUID()
        val bytes = java.nio.ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        bytes[0] = 0xfd.toByte() // unique local IPv6, only stored as synthetic test data
        return InetAddress.getByAddress(bytes)
    }

    private fun <T> race(count: Int, operation: (Int) -> T): List<T> {
        val callers = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val jobs = List(count) { index -> callers.submit<T> { start.await(); operation(index) } }
            start.countDown()
            return jobs.map { it.get(20, TimeUnit.SECONDS) }
        } finally { callers.shutdownNow() }
    }
}
