package pl.syntaxdevteam.authgatewayx.storage.jdbc

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiAccountHistoryTest {
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private fun ip(last: Int) = InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))
    private fun name(value: String) = AccountUsername.parse(value)
    private fun JdbcAccountStorage.register(value: String, address: Int, at: Instant = now): OfflineRegistration {
        val username = name(value)
        val registration = OfflineRegistration(AccountId.random(), username, OfflineIdentity.minecraftUuid(username),
            "test-hash", ip(address), at, 100)
        assertIs<RegistrationResult.Created>(registerOffline(registration).toCompletableFuture().get(10, TimeUnit.SECONDS))
        return registration
    }
    private fun JdbcAccountStorage.report(value: String, at: Instant = now) =
        findRelatedOfflineAccounts(name(value), at).toCompletableFuture().get(10, TimeUnit.SECONDS)

    @Test
    fun `v4 upgrade preserves accounts and starts history without guessing old observations`() = withStorage { storage, url, _ ->
        val first = storage.register("First", 1)
        storage.register("Second", 1)
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("DROP TABLE offline_account_addresses")
                it.executeUpdate("DELETE FROM schema_history WHERE version = 5")
            }
        }
        storage.migrate().toCompletableFuture().get()
        storage.migrate().toCompletableFuture().get()
        assertTrue(storage.report("First")!!.accounts.isEmpty())
        assertEquals(first.accountId, storage.findByUsername(first.username).toCompletableFuture().get()!!.id)
        storage.recordLoginSuccess(first.accountId, ip(1), now).toCompletableFuture().get()
        assertTrue(storage.report("First")!!.accounts.isEmpty())
    }

    @Test
    fun `shared history survives changed IP and restart without linking unrelated accounts`() = withStorage { storage, url, executor ->
        val first = storage.register("First", 1)
        storage.register("Second", 1)
        storage.register("Unrelated", 2)
        storage.recordLoginSuccess(first.accountId, ip(3), now.plusSeconds(1)).toCompletableFuture().get()
        assertEquals(listOf("Second"), storage.report("fIrSt")!!.accounts.map { it.username.value })
        storage.close()
        JdbcAccountStorage(url, 2, executor).use { reopened ->
            reopened.migrate().toCompletableFuture().get()
            assertEquals(listOf("Second"), reopened.report("First")!!.accounts.map { it.username.value })
        }
    }

    @Test
    fun `unknown and premium accounts are excluded and migration removes history`() = withStorage { storage, url, _ ->
        storage.register("First", 1)
        val second = storage.register("Second", 1)
        storage.bindVerifiedMojangIdentity(VerifiedMojangIdentity(second.username, UUID.randomUUID(), ip(1), now))
            .toCompletableFuture().get()
        assertNull(storage.report("Missing"))
        assertNull(storage.report("Second"))
        assertTrue(storage.report("First")!!.accounts.isEmpty())
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM offline_account_addresses").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1))
                }
            }
        }
        assertFailsWith<java.util.concurrent.ExecutionException> {
            storage.recordLoginSuccess(second.accountId, ip(1), now).toCompletableFuture().get()
        }
    }

    @Test
    fun `both sides must have recent observations and failures do not refresh history`() = withStorage { storage, _, _ ->
        val first = storage.register("First", 1, now.minus(Duration.ofDays(31)))
        storage.register("Second", 1)
        assertTrue(storage.report("First")!!.accounts.isEmpty())
        storage.recordLoginFailure(first.accountId, now, 5, Duration.ofMinutes(10)).toCompletableFuture().get()
        assertTrue(storage.report("First")!!.accounts.isEmpty())
        storage.recordLoginSuccess(first.accountId, ip(1), now).toCompletableFuture().get()
        assertEquals(1, storage.report("First")!!.accounts.size)
        assertTrue(storage.report("First", now.plus(Duration.ofDays(30)).plusMillis(1))!!.accounts.isEmpty())
    }

    @Test
    fun `distinct shared addresses are counted once and results are explicitly bounded`() = withStorage { storage, _, _ ->
        val first = storage.register("First", 1)
        for (index in 1..22) storage.register("Alt$index", 1)
        val second = storage.register("Second", 2)
        storage.recordLoginSuccess(first.accountId, ip(2), now).toCompletableFuture().get()
        repeat(3) { storage.recordLoginSuccess(second.accountId, ip(1), now).toCompletableFuture().get() }
        val report = assertNotNull(storage.report("First"))
        assertTrue(report.truncated)
        assertEquals(20, report.accounts.size)
        assertEquals("Second", report.accounts.first().username.value)
        assertEquals(2, report.accounts.first().sharedAddressCount)
        assertFalse(report.accounts.any { it.username.value == "First" })
    }

    @Test
    fun `concurrent address rotation is bounded and evicts the oldest address`() = withStorage { storage, url, _ ->
        val first = storage.register("First", 1, now.minusSeconds(100))
        storage.register("Second", 1)
        val futures = (2..40).map { storage.recordLoginSuccess(first.accountId, ip(it), now.plusSeconds(it.toLong())).toCompletableFuture() }
        futures.forEach { it.get(15, TimeUnit.SECONDS) }
        assertTrue(storage.report("First", now.plusSeconds(60))!!.accounts.isEmpty())
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM offline_account_addresses WHERE account_id = ?").use { statement ->
                statement.setString(1, first.accountId.value.toString())
                statement.executeQuery().use { assertTrue(it.next()); assertEquals(16, it.getInt(1)) }
            }
        }
    }

    @Test
    fun `address write failure rolls back registration and login state`() = withStorage { storage, url, _ ->
        val first = storage.register("First", 1)
        storage.recordLoginFailure(first.accountId, now, 5, Duration.ofMinutes(10)).toCompletableFuture().get()
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.executeUpdate("""CREATE TRIGGER reject_address BEFORE INSERT ON offline_account_addresses
                BEGIN SELECT RAISE(ABORT, 'test address failure'); END""") }
        }
        assertFailsWith<java.util.concurrent.ExecutionException> {
            storage.recordLoginSuccess(first.accountId, ip(2), now).toCompletableFuture().get()
        }
        assertEquals(1, storage.findCredentials(first.username).toCompletableFuture().get()!!.failedLoginCount)
        assertFailsWith<java.util.concurrent.ExecutionException> { storage.register("Rejected", 2) }
        assertNull(storage.findByUsername(name("Rejected")).toCompletableFuture().get())
    }

    @Test
    fun `proxy matches current address for a new name without writing an account`() = withStorage { storage, _, _ ->
        storage.register("Existing", 1)
        val result = storage.findOfflineAccountsByAddress(ip(1), name("NewName"), now).toCompletableFuture().get()
        assertEquals(listOf("Existing"), result.accounts.map { it.username.value })
        assertNull(storage.findByUsername(name("NewName")).toCompletableFuture().get())
        assertTrue(storage.findOfflineAccountsByAddress(ip(1), name("existing"), now).toCompletableFuture().get().accounts.isEmpty())
        assertTrue(storage.findOfflineAccountsByAddress(ip(2), name("NewName"), now).toCompletableFuture().get().accounts.isEmpty())
        assertTrue(storage.findOfflineAccountsByAddress(ip(1), name("NewName"), now.plus(Duration.ofDays(31))).toCompletableFuture().get().accounts.isEmpty())
        storage.verifyHistorySchema().toCompletableFuture().get()
    }

    private fun withStorage(test: (JdbcAccountStorage, String, BoundedTaskExecutor) -> Unit) {
        val file = Files.createTempFile("authgatewayx-alts-", ".sqlite")
        val executor = BoundedTaskExecutor(4, 128, "alts-test")
        val url = "jdbc:sqlite:$file"
        val storage = JdbcAccountStorage(url, 4, executor)
        try {
            storage.migrate().toCompletableFuture().get()
            test(storage, url, executor)
        } finally {
            storage.close()
            executor.close()
            Files.deleteIfExists(file)
        }
    }
}
