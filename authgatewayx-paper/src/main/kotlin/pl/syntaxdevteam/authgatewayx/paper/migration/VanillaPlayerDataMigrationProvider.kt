package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletionStage

class VanillaPlayerDataMigrationProvider(
    private val worldRoots: List<Path>,
    private val backupRoot: Path,
    private val executor: BoundedTaskExecutor,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:vanilla"

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> =
        executor.submit {
            val mappings = mappings(context)
            val source = mappings.filter { Files.exists(it.source, LinkOption.NOFOLLOW_LINKS) }
            val unsafe = source.firstOrNull {
                Files.isSymbolicLink(it.source) ||
                    (Files.exists(it.target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(it.target))
            }
            when {
                unsafe != null -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.BLOCKED,
                    "VANILLA_SYMLINK_REJECTED",
                )
                source.isEmpty() -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.NO_DATA,
                    "NO_VANILLA_UUID_DATA",
                )
                else -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.READY,
                    "VANILLA_FILES_${source.size}",
                    legacyEvidence = true,
                )
            }
        }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            val mappings = mappings(context).filter { Files.exists(it.source, LinkOption.NOFOLLOW_LINKS) }
            if (mappings.isEmpty()) return@submit IdentityMigrationOperationResult.NoData

            for (mapping in mappings) {
                requireRegularFile(mapping.source)
                if (Files.exists(mapping.target, LinkOption.NOFOLLOW_LINKS)) requireRegularFile(mapping.target)

                Files.createDirectories(mapping.backupBase.parent)
                copyIfMissing(mapping.source, mapping.sourceBackup)
                if (
                    !Files.exists(mapping.targetBackup, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.exists(mapping.targetAbsentMarker, LinkOption.NOFOLLOW_LINKS)
                ) {
                    if (Files.exists(mapping.target, LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(mapping.target, mapping.targetBackup, StandardCopyOption.COPY_ATTRIBUTES)
                    } else {
                        Files.createFile(mapping.targetAbsentMarker)
                    }
                }

                Files.createDirectories(mapping.target.parent)
                val temp = mapping.target.resolveSibling(
                    ".${mapping.target.fileName}.agx-${context.migrationId}.tmp",
                )
                try {
                    Files.copy(
                        mapping.source,
                        temp,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    atomicReplace(temp, mapping.target)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
            IdentityMigrationOperationResult.Success
        }

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            for (mapping in mappings(context)) {
                when {
                    Files.exists(mapping.targetBackup, LinkOption.NOFOLLOW_LINKS) -> {
                        requireRegularFile(mapping.targetBackup)
                        Files.createDirectories(mapping.target.parent)
                        val temp = mapping.target.resolveSibling(
                            ".${mapping.target.fileName}.agx-rollback-${context.migrationId}.tmp",
                        )
                        try {
                            Files.copy(
                                mapping.targetBackup,
                                temp,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES,
                            )
                            atomicReplace(temp, mapping.target)
                        } finally {
                            Files.deleteIfExists(temp)
                        }
                    }
                    Files.exists(mapping.targetAbsentMarker, LinkOption.NOFOLLOW_LINKS) ->
                        Files.deleteIfExists(mapping.target)
                }
            }
            IdentityMigrationOperationResult.Success
        }

    private fun mappings(context: IdentityMigrationContext): List<FileMapping> {
        val source = context.sourceMinecraftUuid.toString()
        val target = context.targetMinecraftUuid.toString()
        val relativePairs = listOf(
            "playerdata/$source.dat" to "playerdata/$target.dat",
            "playerdata/$source.dat_old" to "playerdata/$target.dat_old",
            "stats/$source.json" to "stats/$target.json",
            "advancements/$source.json" to "advancements/$target.json",
        )
        val uniqueRoots = worldRoots.map { it.toAbsolutePath().normalize() }.distinct()
        return buildList {
            uniqueRoots.forEachIndexed { index, root ->
                relativePairs.forEachIndexed { pairIndex, pair ->
                    val sourcePath = root.resolve(pair.first).normalize()
                    val targetPath = root.resolve(pair.second).normalize()
                    check(sourcePath.startsWith(root) && targetPath.startsWith(root))
                    val base = backupRoot
                        .resolve(context.migrationId.toString())
                        .resolve(id.replace(':', '_'))
                        .resolve("world-$index")
                        .resolve("file-$pairIndex")
                    add(
                        FileMapping(
                            sourcePath,
                            targetPath,
                            base,
                            base.resolveSibling("${base.fileName}.source"),
                            base.resolveSibling("${base.fileName}.target"),
                            base.resolveSibling("${base.fileName}.target-absent"),
                        ),
                    )
                }
            }
        }
    }

    private fun requireRegularFile(path: Path) {
        check(!Files.isSymbolicLink(path)) { "Symbolic links are not accepted for migration: $path" }
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Expected regular file: $path" }
    }

    private fun copyIfMissing(source: Path, backup: Path) {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun atomicReplace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class FileMapping(
        val source: Path,
        val target: Path,
        val backupBase: Path,
        val sourceBackup: Path,
        val targetBackup: Path,
        val targetAbsentMarker: Path,
    )
}
