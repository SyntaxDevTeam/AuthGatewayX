package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnmanagedPluginUuidReferenceScannerTest {
    private val context = IdentityMigrationContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "UpgradeMe",
        UUID.fromString("11111111-1111-3111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
    )

    @Test
    fun `unmanaged plugin UUID reference blocks finalization`() {
        withScanner { root, own, executor ->
            val essentials = root.resolve("Essentials").also { Files.createDirectories(it) }
            essentials.resolve("userdata.yml").writeText("uuid: ${context.sourceMinecraftUuid}")
            val scanner = scanner(root, own, executor)

            val result = scanner.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.BLOCKED, result.status)
            assertTrue(result.reasonCode.contains("Essentials"))
        }
    }

    @Test
    fun `registered provider ownership excludes its plugin directory from unmanaged scan`() {
        withScanner { root, own, executor ->
            val plots = root.resolve("PlotsX").also { Files.createDirectories(it) }
            plots.resolve("player.json").writeText("${context.sourceMinecraftUuid}")
            val scanner = scanner(root, own, executor, setOf("PlotsX"))

            val result = scanner.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.NO_DATA, result.status)
        }
    }

    @Test
    fun `large individual file is scanned when aggregate budget allows it`() {
        withScanner { root, own, executor ->
            val plugin = root.resolve("LargePlugin").also { Files.createDirectories(it) }
            Files.write(plugin.resolve("players.db"), ByteArray(2 * 1024 * 1024) { 0x41 })
            val scanner = scanner(root, own, executor, maximumTotalBytes = 4L * 1024 * 1024)

            val result = scanner.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.NO_DATA, result.status)
            assertEquals("NO_UNMANAGED_LOCAL_UUID_REFERENCES", result.reasonCode)
        }
    }

    @Test
    fun `aggregate byte limit reports the path that could not be scanned`() {
        withScanner { root, own, executor ->
            val plugin = root.resolve("LargePlugin").also { Files.createDirectories(it) }
            Files.write(plugin.resolve("players.db"), ByteArray(2048) { 0x41 })
            val scanner = scanner(root, own, executor, maximumTotalBytes = 1024)

            val result = scanner.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.BLOCKED, result.status)
            assertTrue(result.reasonCode.startsWith("UNMANAGED_SCAN_BYTE_LIMIT::LargePlugin"))
            assertTrue(result.reasonCode.contains("players.db"))
        }
    }

    private fun scanner(
        root: java.nio.file.Path,
        own: java.nio.file.Path,
        executor: BoundedTaskExecutor,
        managed: Set<String> = emptySet(),
        maximumTotalBytes: Long = 8L * 1024 * 1024,
    ) = UnmanagedPluginUuidReferenceScanner(
        root,
        own,
        { managed },
        executor,
        maximumFiles = 100,
        maximumTotalBytes = maximumTotalBytes,
    )

    private fun withScanner(test: (java.nio.file.Path, java.nio.file.Path, BoundedTaskExecutor) -> Unit) {
        val root = Files.createTempDirectory("agx-plugins-")
        val own = root.resolve("AuthGatewayX").also { Files.createDirectories(it) }
        val executor = BoundedTaskExecutor(1, 8, "scanner-test")
        try {
            test(root, own, executor)
        } finally {
            executor.close()
            root.toFile().deleteRecursively()
        }
    }
}
