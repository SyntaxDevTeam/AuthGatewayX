package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UniversalLocalIdentityMigrationProviderTest {
    private val context = IdentityMigrationContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "UpgradeMe",
        UUID.fromString("11111111-1111-3111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
    )

    @Test
    fun `AdvancedPortals built-in recipe migrates UUID-indexed player data`() {
        withProvider { root, own, backup, recipes, executor ->
            val playerData = root.resolve("AdvancedPortals/playerData").also { Files.createDirectories(it) }
            val source = playerData.resolve("${context.sourceMinecraftUuid}.yaml")
            val target = playerData.resolve("${context.targetMinecraftUuid}.yaml")
            source.writeText("portalVisible: true\nselectedPortal: spawn\n")
            val provider = provider(root, own, backup, recipes, executor)

            val inspection = provider.inspect(context).toCompletableFuture().get()
            assertEquals(IdentityMigrationInspectionStatus.READY, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_READY::1::1::0"))

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertTrue(source.exists())
            assertEquals(source.readText(), target.readText())

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.rollback(context).toCompletableFuture().get(),
            )
            assertFalse(target.exists())
            assertTrue(source.exists())
        }
    }

    @Test
    fun `generic exact UUID yaml file is migrated when content has no UUID`() {
        withProvider { root, own, backup, recipes, executor ->
            val users = root.resolve("SomePlugin/users").also { Files.createDirectories(it) }
            val source = users.resolve("${context.sourceMinecraftUuid}.yml")
            val target = users.resolve("${context.targetMinecraftUuid}.yml")
            source.writeText("coins: 42\nlast-zone: spawn\n")
            val provider = provider(root, own, backup, recipes, executor)

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.READY, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_READY::1::0::1"))
            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertEquals(source.readText(), target.readText())
        }
    }

    @Test
    fun `generic file is review required when UUID also appears in content`() {
        withProvider { root, own, backup, recipes, executor ->
            val users = root.resolve("UnknownPlugin/users").also { Files.createDirectories(it) }
            users.resolve("${context.sourceMinecraftUuid}.yaml")
                .writeText("uuid: ${context.sourceMinecraftUuid}\ncoins: 42\n")
            val provider = provider(root, own, backup, recipes, executor)

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.BLOCKED, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_REVIEW_REQUIRED::0::UnknownPlugin"))
            assertTrue(inspection.legacyEvidence)
        }
    }

    @Test
    fun `custom recipe may explicitly rewrite UUID in one scoped text file`() {
        withProvider { root, own, backup, recipes, executor ->
            Files.createDirectories(recipes)
            recipes.resolve("custom.yml").writeText(
                """
                id: custom-users
                plugin-directory: CustomUsers
                rules:
                  - type: UUID_FILE
                    source: "profiles/{source_uuid}.yaml"
                    target: "profiles/{target_uuid}.yaml"
                    rewrite-uuid-in-content: true
                    require-source-uuid-absent: false
                """.trimIndent(),
            )
            val profiles = root.resolve("CustomUsers/profiles").also { Files.createDirectories(it) }
            val source = profiles.resolve("${context.sourceMinecraftUuid}.yaml")
            val target = profiles.resolve("${context.targetMinecraftUuid}.yaml")
            source.writeText("uuid: ${context.sourceMinecraftUuid}\nname: UpgradeMe\n")
            val provider = provider(root, own, backup, recipes, executor)

            val inspection = provider.inspect(context).toCompletableFuture().get()
            assertEquals(IdentityMigrationInspectionStatus.READY, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_READY::1::1::0"))

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertTrue(target.readText().contains(context.targetMinecraftUuid.toString()))
            assertFalse(target.readText().contains(context.sourceMinecraftUuid.toString()))
        }
    }

    @Test
    fun `dedicated provider ownership excludes its plugin directory from fallback`() {
        withProvider { root, own, backup, recipes, executor ->
            val plots = root.resolve("PlotsX").also { Files.createDirectories(it) }
            plots.resolve("${context.sourceMinecraftUuid}.yml").writeText("plots: 2\n")
            val provider = provider(
                root,
                own,
                backup,
                recipes,
                executor,
                managed = setOf("PlotsX"),
            )

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.NO_DATA, inspection.status)
            assertEquals("NO_UNIVERSAL_LOCAL_UUID_DATA", inspection.reasonCode)
        }
    }

    @Test
    fun `administrator may intentionally ignore selected plugin data while preserving recovery evidence`() {
        withProvider { root, own, backup, recipes, executor ->
            val database = root.resolve("CoreProtect").also { Files.createDirectories(it) }
                .resolve("database.db")
            database.writeText("player=${context.sourceMinecraftUuid}\n")
            val provider = provider(
                root,
                own,
                backup,
                recipes,
                executor,
                ignored = setOf("coreprotect"),
            )

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.NO_DATA, inspection.status)
            assertEquals("UNIVERSAL_LOCAL_IGNORED::CoreProtect", inspection.reasonCode)
            assertTrue(inspection.legacyEvidence)
            assertEquals(
                IdentityMigrationOperationResult.NoData,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertTrue(database.exists())
        }
    }

    @Test
    fun `ignored plugin does not block safe migrations belonging to other plugins`() {
        withProvider { root, own, backup, recipes, executor ->
            val ignoredDirectory = root.resolve("BeautyQuests").also { Files.createDirectories(it) }
            ignoredDirectory.resolve("questers.db").writeText("player=${context.sourceMinecraftUuid}\n")
            val users = root.resolve("SomePlugin/users").also { Files.createDirectories(it) }
            val source = users.resolve("${context.sourceMinecraftUuid}.yml")
            val target = users.resolve("${context.targetMinecraftUuid}.yml")
            source.writeText("coins: 42\n")
            val provider = provider(
                root,
                own,
                backup,
                recipes,
                executor,
                ignored = setOf("BeautyQuests"),
            )

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.READY, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_READY_WITH_IGNORED::1::0::1::"))
            assertTrue(inspection.reasonCode.endsWith("::BeautyQuests"))
            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertEquals(source.readText(), target.readText())
            assertFalse(ignoredDirectory.resolve("${context.targetMinecraftUuid}.db").exists())
        }
    }

    @Test
    fun `aggregate byte budget still fails closed and reports path`() {
        withProvider { root, own, backup, recipes, executor ->
            val plugin = root.resolve("LargePlugin").also { Files.createDirectories(it) }
            Files.write(plugin.resolve("players.db"), ByteArray(2048) { 0x41 })
            val provider = provider(
                root,
                own,
                backup,
                recipes,
                executor,
                maximumTotalBytes = 1024,
            )

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.BLOCKED, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNMANAGED_SCAN_BYTE_LIMIT::LargePlugin"))
            assertTrue(inspection.reasonCode.contains("players.db"))
        }
    }

    @Test
    fun `AdvancedPortals recipe fails closed if future schema starts embedding source UUID`() {
        withProvider { root, own, backup, recipes, executor ->
            val playerData = root.resolve("AdvancedPortals/playerData").also { Files.createDirectories(it) }
            playerData.resolve("${context.sourceMinecraftUuid}.yaml")
                .writeText("uuid: ${context.sourceMinecraftUuid}\nselectedPortal: spawn\n")
            val provider = provider(root, own, backup, recipes, executor)

            val inspection = provider.inspect(context).toCompletableFuture().get()

            assertEquals(IdentityMigrationInspectionStatus.BLOCKED, inspection.status)
            assertTrue(inspection.reasonCode.startsWith("UNIVERSAL_LOCAL_REVIEW_REQUIRED"))
        }
    }

    @Test
    fun `existing target is restored byte for byte on rollback`() {
        withProvider { root, own, backup, recipes, executor ->
            val users = root.resolve("SomePlugin/users").also { Files.createDirectories(it) }
            val source = users.resolve("${context.sourceMinecraftUuid}.json")
            val target = users.resolve("${context.targetMinecraftUuid}.json")
            source.writeText("{\"coins\":42}")
            val originalTarget = "{\"coins\":7,\"createdByPremium\":true}"
            target.writeText(originalTarget)
            val provider = provider(root, own, backup, recipes, executor)

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.migrate(context).toCompletableFuture().get(),
            )
            assertEquals(source.readText(), target.readText())

            assertEquals(
                IdentityMigrationOperationResult.Success,
                provider.rollback(context).toCompletableFuture().get(),
            )
            assertEquals(originalTarget, target.readText())
        }
    }

    private fun provider(
        root: java.nio.file.Path,
        own: java.nio.file.Path,
        backup: java.nio.file.Path,
        recipes: java.nio.file.Path,
        executor: BoundedTaskExecutor,
        managed: Set<String> = emptySet(),
        ignored: Set<String> = emptySet(),
        maximumTotalBytes: Long = 8L * 1024 * 1024,
    ) = UniversalLocalIdentityMigrationProvider(
        pluginsRoot = root,
        authGatewayDataDirectory = own,
        backupRoot = backup,
        managedDataOwnersSupplier = { managed },
        recipeRegistry = MigrationRecipeRegistry(recipes),
        executor = executor,
        maximumFiles = 1000,
        maximumTotalBytes = maximumTotalBytes,
        genericUuidFilesEnabled = true,
        genericExtensions = setOf("yml", "yaml", "json", "toml", "properties"),
        ignoredPluginDirectories = ignored,
    )

    private fun withProvider(
        test: (
            root: java.nio.file.Path,
            own: java.nio.file.Path,
            backup: java.nio.file.Path,
            recipes: java.nio.file.Path,
            executor: BoundedTaskExecutor,
        ) -> Unit,
    ) {
        val root = Files.createTempDirectory("agx-universal-local-")
        val own = root.resolve("AuthGatewayX").also { Files.createDirectories(it) }
        val backup = own.resolve("migration-backups/universal-local")
        val recipes = own.resolve("migration-recipes")
        val executor = BoundedTaskExecutor(1, 8, "universal-local-test")
        try {
            test(root, own, backup, recipes, executor)
        } finally {
            executor.close()
            root.toFile().deleteRecursively()
        }
    }
}
