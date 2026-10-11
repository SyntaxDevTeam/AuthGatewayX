package pl.syntaxdevteam.authgatewayx.storage.jdbc.fixture

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import pl.syntaxdevteam.authgatewayx.storage.jdbc.JdbcAccountStorage
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineMigrationFixtureTest {
    @Test
    fun `fixture reopens as offline account with valid password and requires migration`() = withDirectory { base ->
        val directory = OfflineMigrationFixture.create(base, "FixtureUser")
        val username = AccountUsername.parse("FixtureUser")
        val password = Files.readString(directory.resolve("test-password.txt")).trim()
        val metadata = Files.readString(directory.resolve("fixture.json"))
        assertFalse(password in metadata)
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("test-password.txt"))))
        }
        BoundedTaskExecutor(1, 8, "fixture-test").use { executor ->
            JdbcAccountStorage("jdbc:sqlite:${directory.resolve("authgatewayx.db")}", 1, executor).use { storage ->
                storage.migrate().toCompletableFuture().get(20, TimeUnit.SECONDS)
                val credentials = assertNotNull(storage.findCredentials(username).toCompletableFuture().get(20, TimeUnit.SECONDS))
                assertEquals(IdentityType.OFFLINE, credentials.account.identityType)
                assertEquals(OfflineIdentity.minecraftUuid(username), credentials.account.minecraftUuid)
                assertTrue(Argon2PasswordHasher().verify(credentials.passwordHash, password.toCharArray()))
                assertFalse(Argon2PasswordHasher().verify(credentials.passwordHash, "incorrect-test-password".toCharArray()))
                val binding = storage.bindVerifiedMojangIdentity(VerifiedMojangIdentity(
                    username, UUID.randomUUID(), InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), Instant.now(),
                )).toCompletableFuture().get(20, TimeUnit.SECONDS)
                assertIs<MojangIdentityBindingResult.MigrationRequired>(binding)
                assertEquals(credentials.account, binding.account)
                assertEquals(credentials.passwordHash, storage.findPasswordHash(credentials.account.id).toCompletableFuture().get(20, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `repeated fixtures cannot overwrite an existing database`() = withDirectory { base ->
        val existing = base.resolve("authgatewayx.db")
        Files.writeString(existing, "existing-database-sentinel")
        val first = OfflineMigrationFixture.create(base, "FixtureUser")
        val second = OfflineMigrationFixture.create(base, "FixtureUser")
        assertFalse(first == second)
        assertTrue(Files.exists(first.resolve("authgatewayx.db")))
        assertTrue(Files.exists(second.resolve("authgatewayx.db")))
        assertEquals("existing-database-sentinel", Files.readString(existing))
    }

    @Test
    fun `invalid nickname is rejected before creating any fixture`() = withDirectory { base ->
        assertFailsWith<IllegalArgumentException> { OfflineMigrationFixture.create(base, "../invalid") }
        Files.list(base).use { assertEquals(0L, it.count()) }
    }

    private fun withDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("agx-fixture-test-")
        try { test(directory) } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
