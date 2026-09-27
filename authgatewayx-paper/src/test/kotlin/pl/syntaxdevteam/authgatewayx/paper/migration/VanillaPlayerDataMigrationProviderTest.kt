package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VanillaPlayerDataMigrationProviderTest {
    private val context = IdentityMigrationContext(
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        "UpgradeMe",
        UUID.fromString("11111111-1111-3111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
    )

    @Test
    fun `migration backs up and replaces target then rollback restores it`() {
        val world = Files.createTempDirectory("agx-world-")
        val backups = Files.createTempDirectory("agx-backups-")
        val executor = BoundedTaskExecutor(1, 8, "migration-test")
        try {
            val source = world.resolve("playerdata").resolve("${context.sourceMinecraftUuid}.dat")
            val target = world.resolve("playerdata").resolve("${context.targetMinecraftUuid}.dat")
            Files.createDirectories(source.parent)
            source.writeText("old-profile")
            target.writeText("pre-auth-target")

            val provider = VanillaPlayerDataMigrationProvider(listOf(world), backups, executor)
            assertEquals(
                IdentityMigrationInspectionStatus.READY,
                provider.inspect(context).toCompletableFuture().get().status,
            )
            assertIs<IdentityMigrationOperationResult.Success>(
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertEquals("old-profile", target.readText())
            assertEquals("old-profile", source.readText())

            assertIs<IdentityMigrationOperationResult.Success>(
                provider.rollback(context).toCompletableFuture().get(),
            )
            assertEquals("pre-auth-target", target.readText())
        } finally {
            executor.close()
            world.toFile().deleteRecursively()
            backups.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rollback deletes target that did not exist before migration`() {
        val world = Files.createTempDirectory("agx-world-")
        val backups = Files.createTempDirectory("agx-backups-")
        val executor = BoundedTaskExecutor(1, 8, "migration-test")
        try {
            val source = world.resolve("stats").resolve("${context.sourceMinecraftUuid}.json")
            val target = world.resolve("stats").resolve("${context.targetMinecraftUuid}.json")
            Files.createDirectories(source.parent)
            source.writeText("{\"stat\":1}")

            val provider = VanillaPlayerDataMigrationProvider(listOf(world), backups, executor)
            assertIs<IdentityMigrationOperationResult.Success>(
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertTrue(Files.exists(target))

            provider.rollback(context).toCompletableFuture().get()
            assertFalse(Files.exists(target))
            assertTrue(Files.exists(source))
        } finally {
            executor.close()
            world.toFile().deleteRecursively()
            backups.toFile().deleteRecursively()
        }
    }
}
