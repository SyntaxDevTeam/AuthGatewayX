package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniversalLocalMigrationIoBudgetTest {
    @Test
    fun `inspection completes without executing scan on caller thread`() {
        val root = Files.createTempDirectory("agx-universal-worker-")
        val own = root.resolve("AuthGatewayX").also { Files.createDirectories(it) }
        val executor = BoundedTaskExecutor(1, 8, "migration-io-test")
        try {
            val context = IdentityMigrationContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "UpgradeMe",
                UUID.fromString("11111111-1111-3111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
            )
            root.resolve("SomePlugin").also { Files.createDirectories(it) }
                .resolve("data.yml")
                .writeText("uuid: ${context.sourceMinecraftUuid}\n")
            val provider = UniversalLocalIdentityMigrationProvider(
                pluginsRoot = root,
                authGatewayDataDirectory = own,
                backupRoot = own.resolve("migration-backups/universal-local"),
                managedDataOwnersSupplier = { emptySet() },
                recipeRegistry = MigrationRecipeRegistry(own.resolve("migration-recipes")),
                executor = executor,
                maximumFiles = 1000,
                maximumTotalBytes = 8L * 1024 * 1024,
                genericUuidFilesEnabled = true,
                genericExtensions = setOf("yml", "yaml", "json"),
                // One 64 KiB read is deliberately paced for about one second, guaranteeing
                // the callback is registered before the worker completes the scan.
                maximumScanBytesPerSecond = 64L * 1024,
            )

            val caller = Thread.currentThread().name
            val completionThread = AtomicReference<String>()
            provider.inspect(context).whenComplete { _, _ -> completionThread.set(Thread.currentThread().name) }
                .toCompletableFuture().get()

            assertFalse(completionThread.get() == caller)
            assertTrue(completionThread.get().startsWith("migration-io-test-"))
        } finally {
            executor.close()
            root.toFile().deleteRecursively()
        }
    }
}
