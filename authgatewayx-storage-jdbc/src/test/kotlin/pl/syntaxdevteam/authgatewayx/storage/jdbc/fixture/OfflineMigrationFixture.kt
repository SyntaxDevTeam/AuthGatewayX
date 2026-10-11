package pl.syntaxdevteam.authgatewayx.storage.jdbc.fixture

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.password.Argon2PasswordHasher
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.jdbc.JdbcAccountStorage
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Test tooling only: never packaged in the plugin or connected to a running server. */
object OfflineMigrationFixture {
    fun create(baseDirectory: Path, rawUsername: String): Path {
        val username = AccountUsername.parse(rawUsername)
        Files.createDirectories(baseDirectory)
        val directory = Files.createTempDirectory(baseDirectory, "offline-migration-")
        restrict(directory, "rwx------")
        val database = directory.resolve("authgatewayx.db")
        val passwordFile = directory.resolve("test-password.txt")
        val accountId = AccountId(UUID.randomUUID())
        val offlineUuid = OfflineIdentity.minecraftUuid(username)
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val hex = "0123456789abcdef"
        val password = CharArray(48) { index ->
            val value = random[index / 2].toInt() and 255
            hex[if (index % 2 == 0) value ushr 4 else value and 15]
        }
        random.fill(0)
        try {
            val hash = Argon2PasswordHasher().hash(password.copyOf())
            BoundedTaskExecutor(1, 8, "migration-fixture").use { executor ->
                JdbcAccountStorage("jdbc:sqlite:$database", 1, executor).use { storage ->
                    storage.migrate().toCompletableFuture().get(20, TimeUnit.SECONDS)
                    val result = storage.registerOffline(
                        OfflineRegistration(
                            accountId, username, offlineUuid, hash,
                            InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)),
                            Instant.now(), 1,
                        ),
                    ).toCompletableFuture().get(20, TimeUnit.SECONDS)
                    check(result is RegistrationResult.Created) { "Fixture registration failed" }
                }
            }
            restrict(database, "rw-------")
            Files.createFile(passwordFile)
            restrict(passwordFile, "rw-------")
            Files.newBufferedWriter(passwordFile, StandardOpenOption.WRITE).use { writer ->
                writer.write(password)
                writer.newLine()
            }
            // Username is validated by AccountUsername; it cannot inject JSON or file paths.
            Files.writeString(directory.resolve("fixture.json"), """
                {
                  "scope": "isolated test database; no Mojang verification or world data generated",
                  "username": "${username.value}",
                  "account_id": "${accountId.value}",
                  "identity_type": "OFFLINE",
                  "source_minecraft_uuid": "$offlineUuid",
                  "database": "authgatewayx.db",
                  "password_file": "test-password.txt"
                }
            """.trimIndent() + "\n", StandardOpenOption.CREATE_NEW)
            return directory
        } finally {
            password.fill('\u0000')
        }
    }

    private fun restrict(path: Path, permissions: String) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected username and fixture base directory" }
        val result = create(Path.of(args[1]), args[0])
        println("Created isolated OFFLINE migration fixture: $result")
        println("Test password is in test-password.txt; it is not printed to logs.")
    }
}
