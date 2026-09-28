package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletionStage

/** Migrates EssentialsX YAML userdata while retaining the source file as a recovery copy. */
class EssentialsXIdentityMigrationProvider(
    private val userdataDirectory: java.nio.file.Path,
    private val backupRoot: java.nio.file.Path,
    private val executor: BoundedTaskExecutor,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:essentialsx"
    override val managedDataOwners: Set<String> = setOf("Essentials")

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> =
        executor.submit {
            val source = source(context)
            if (!Files.isRegularFile(source)) {
                IdentityMigrationInspection(IdentityMigrationInspectionStatus.NO_DATA, "ESSENTIALSX_SOURCE_ABSENT")
            } else {
                val expected = migratedContent(context, Files.readAllBytes(source))
                val target = target(context)
                if (!Files.exists(target) || Files.readAllBytes(target).contentEquals(expected)) {
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.READY,
                        "ESSENTIALSX_USERDATA_READY",
                        legacyEvidence = true,
                    )
                } else {
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.BLOCKED,
                        "ESSENTIALSX_TARGET_HAS_DIFFERENT_DATA",
                        legacyEvidence = true,
                    )
                }
            }
        }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            runCatching {
                val source = source(context)
                if (!Files.isRegularFile(source)) return@submit IdentityMigrationOperationResult.NoData
                val expected = migratedContent(context, Files.readAllBytes(source))
                val target = target(context)
                if (Files.exists(target) && !Files.readAllBytes(target).contentEquals(expected)) {
                    return@submit IdentityMigrationOperationResult.Failure("ESSENTIALSX_TARGET_HAS_DIFFERENT_DATA")
                }
                val backup = backup(context)
                Files.createDirectories(backup.parent)
                if (!Files.exists(backup) && !Files.exists(marker(context))) {
                    if (Files.exists(target)) Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
                    else Files.writeString(marker(context), "target-absent", StandardOpenOption.CREATE_NEW)
                }
                writeAtomically(target, expected)
                IdentityMigrationOperationResult.Success
            }.getOrElse { IdentityMigrationOperationResult.Failure("ESSENTIALSX_MIGRATE_${it.javaClass.simpleName}") }
        }

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            runCatching {
                when {
                    Files.exists(backup(context)) -> writeAtomically(target(context), Files.readAllBytes(backup(context)))
                    Files.exists(marker(context)) -> Files.deleteIfExists(target(context))
                    else -> return@submit IdentityMigrationOperationResult.NoData
                }
                IdentityMigrationOperationResult.Success
            }.getOrElse { IdentityMigrationOperationResult.Failure("ESSENTIALSX_ROLLBACK_${it.javaClass.simpleName}") }
        }

    private fun source(context: IdentityMigrationContext) = userdataDirectory.resolve("${context.sourceMinecraftUuid}.yml")
    private fun target(context: IdentityMigrationContext) = userdataDirectory.resolve("${context.targetMinecraftUuid}.yml")
    private fun backup(context: IdentityMigrationContext) = backupRoot.resolve(context.migrationId.toString()).resolve("target.yml")
    private fun marker(context: IdentityMigrationContext) = backupRoot.resolve(context.migrationId.toString()).resolve("target.absent")

    private fun migratedContent(context: IdentityMigrationContext, source: ByteArray): ByteArray =
        source.toString(StandardCharsets.UTF_8)
            .replace(context.sourceMinecraftUuid.toString(), context.targetMinecraftUuid.toString())
            .toByteArray(StandardCharsets.UTF_8)

    private fun writeAtomically(target: java.nio.file.Path, content: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.agx.tmp")
        Files.write(temporary, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
