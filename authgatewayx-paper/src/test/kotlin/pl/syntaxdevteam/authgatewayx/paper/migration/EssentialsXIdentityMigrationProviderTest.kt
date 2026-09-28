package pl.syntaxdevteam.authgatewayx.paper.migration

import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.util.UUID

class EssentialsXIdentityMigrationProviderTest {
    @Test
    fun `migrates UUID references and rolls back a previously absent target`() {
        val root = Files.createTempDirectory("agx-essentials-")
        val userdata = root.resolve("userdata").createDirectories()
        val context = context()
        userdata.resolve("${context.sourceMinecraftUuid}.yml")
            .writeText("uuid: ${context.sourceMinecraftUuid}\nmoney: '42'\n")
        BoundedTaskExecutor(1, 4, "essentials-test").use { executor ->
            val provider = EssentialsXIdentityMigrationProvider(userdata, root.resolve("backups"), executor)
            assertEquals(IdentityMigrationInspectionStatus.READY, provider.inspect(context).toCompletableFuture().get().status)
            assertEquals(IdentityMigrationOperationResult.Success, provider.migrate(context).toCompletableFuture().get())
            val target = userdata.resolve("${context.targetMinecraftUuid}.yml")
            assertTrue(target.readText().contains(context.targetMinecraftUuid.toString()))
            assertFalse(target.readText().contains(context.sourceMinecraftUuid.toString()))
            assertEquals(IdentityMigrationOperationResult.Success, provider.rollback(context).toCompletableFuture().get())
            assertFalse(Files.exists(target))
        }
    }

    private fun context() = IdentityMigrationContext(
        UUID.randomUUID(), UUID.randomUUID(), "Player", UUID.randomUUID(), UUID.randomUUID(),
    )
}
