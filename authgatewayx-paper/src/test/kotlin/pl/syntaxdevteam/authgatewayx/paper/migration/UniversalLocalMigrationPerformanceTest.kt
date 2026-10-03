package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UniversalLocalMigrationPerformanceTest {
    @Test
    fun `migration reuses plan from immediately preceding inspection`() {
        val root = Files.createTempDirectory("agx-universal-plan-cache-")
        val own = root.resolve("AuthGatewayX").also { Files.createDirectories(it) }
        val recipes = own.resolve("migration-recipes")
        val backup = own.resolve("migration-backups/universal-local")
        val executor = BoundedTaskExecutor(1, 8, "universal-plan-cache-test")
        val context = IdentityMigrationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "UpgradeMe",
            UUID.fromString("11111111-1111-3111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        try {
            val users = root.resolve("SomePlugin/users").also { Files.createDirectories(it) }
            val source = users.resolve("${context.sourceMinecraftUuid}.yml")
            val target = users.resolve("${context.targetMinecraftUuid}.yml")
            source.writeText("coins: 42\n")
            val provider = UniversalLocalIdentityMigrationProvider(
                pluginsRoot = root,
                authGatewayDataDirectory = own,
                backupRoot = backup,
                managedDataOwnersSupplier = { emptySet() },
                recipeRegistry = MigrationRecipeRegistry(recipes),
                executor = executor,
                maximumFiles = 1000,
                maximumTotalBytes = 8L * 1024 * 1024,
                genericUuidFilesEnabled = true,
                genericExtensions = setOf("yml", "yaml", "json"),
                maximumScanBytesPerSecond = 64L * 1024 * 1024,
            )

            val inspection = provider.inspect(context).toCompletableFuture().get()
            assertEquals(IdentityMigrationInspectionStatus.READY, inspection.status)

            // A file created after inspection would force REVIEW_REQUIRED if migrate rebuilt the
            // entire content scan. The same pipeline intentionally consumes its just-produced
            // plan instead, eliminating the duplicate deep scan.
            root.resolve("OtherPlugin").also { Files.createDirectories(it) }
                .resolve("late.yml")
                .writeText("uuid: ${context.sourceMinecraftUuid}\n")

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertTrue(target.exists())
        } finally {
            executor.close()
            root.toFile().deleteRecursively()
        }
    }
}
