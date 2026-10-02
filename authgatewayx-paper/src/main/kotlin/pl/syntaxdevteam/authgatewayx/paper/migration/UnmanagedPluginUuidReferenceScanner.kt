package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionStage

class UnmanagedPluginUuidReferenceScanner(
    private val pluginsRoot: Path,
    private val authGatewayDataDirectory: Path,
    private val managedDataOwnersSupplier: () -> Set<String>,
    private val executor: BoundedTaskExecutor,
    private val maximumFiles: Int,
    private val maximumTotalBytes: Long,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:unmanaged-plugin-scan"

    init {
        require(maximumFiles > 0)
        require(maximumTotalBytes > 0)
    }

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> =
        executor.submit {
            val excluded = managedDataOwnersSupplier()
                .map { it.lowercase(Locale.ROOT) }
                .toMutableSet()
                .apply { add(authGatewayDataDirectory.fileName.toString().lowercase(Locale.ROOT)) }

            val patterns = uuidPatterns(context.sourceMinecraftUuid)
            var filesSeen = 0
            var bytesSeen = 0L
            val matches = linkedMapOf<String, String>()

            if (!Files.isDirectory(pluginsRoot, LinkOption.NOFOLLOW_LINKS)) {
                return@submit IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.BLOCKED,
                    "PLUGIN_ROOT_UNAVAILABLE",
                )
            }

            Files.list(pluginsRoot).use { children ->
                val directories = children.iterator()
                while (directories.hasNext()) {
                    val directory = directories.next()
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                    if (directory.fileName.toString().lowercase(Locale.ROOT) in excluded) continue

                    Files.walk(directory).use { paths ->
                        val iterator = paths.iterator()
                        while (iterator.hasNext()) {
                            val path = iterator.next()
                            if (shouldSkip(path, directory)) continue
                            val displayPath = displayPath(path)
                            if (Files.isSymbolicLink(path)) {
                                return@submit IdentityMigrationInspection(
                                    IdentityMigrationInspectionStatus.BLOCKED,
                                    diagnostic("UNMANAGED_PLUGIN_SYMLINK", displayPath),
                                )
                            }
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue

                            filesSeen++
                            if (filesSeen > maximumFiles) {
                                return@submit IdentityMigrationInspection(
                                    IdentityMigrationInspectionStatus.BLOCKED,
                                    diagnostic("UNMANAGED_SCAN_FILE_LIMIT", displayPath, maximumFiles.toString()),
                                )
                            }

                            val size = Files.size(path)
                            if (size > maximumTotalBytes - bytesSeen) {
                                return@submit IdentityMigrationInspection(
                                    IdentityMigrationInspectionStatus.BLOCKED,
                                    diagnostic(
                                        "UNMANAGED_SCAN_BYTE_LIMIT",
                                        displayPath,
                                        size.toString(),
                                        bytesSeen.toString(),
                                        maximumTotalBytes.toString(),
                                    ),
                                )
                            }
                            bytesSeen += size

                            val fileName = path.fileName.toString().lowercase(Locale.ROOT)
                            if (
                                patterns.textNames.any { fileName.contains(it) } ||
                                containsPattern(path, patterns.bytes)
                            ) {
                                matches.putIfAbsent(directory.fileName.toString(), displayPath)
                                if (matches.size >= MAX_REPORTED_OWNERS) {
                                    return@submit IdentityMigrationInspection(
                                        IdentityMigrationInspectionStatus.BLOCKED,
                                        uuidReferencesReason(matches),
                                        legacyEvidence = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (matches.isEmpty()) {
                IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.NO_DATA,
                    "NO_UNMANAGED_LOCAL_UUID_REFERENCES",
                )
            } else {
                IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.BLOCKED,
                    uuidReferencesReason(matches),
                    legacyEvidence = true,
                )
            }
        }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        java.util.concurrent.CompletableFuture.completedFuture(IdentityMigrationOperationResult.NoData)

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        java.util.concurrent.CompletableFuture.completedFuture(IdentityMigrationOperationResult.NoData)

    private fun displayPath(path: Path): String =
        runCatching {
            pluginsRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
        }.getOrElse { path.fileName?.toString() ?: "?" }
            .replace("::", "_")
            .take(MAX_REPORTED_PATH_LENGTH)

    private fun diagnostic(code: String, vararg values: String): String =
        (listOf(code) + values.map { it.replace("::", "_").take(MAX_REPORTED_PATH_LENGTH) })
            .joinToString("::")

    private fun uuidReferencesReason(matches: Map<String, String>): String {
        val owners = matches.keys.sorted().joinToString(",") { it.replace("::", "_") }
        val paths = matches.toSortedMap().values.joinToString(",") {
            it.replace("::", "_").take(MAX_REPORTED_PATH_LENGTH)
        }
        return diagnostic("UNMANAGED_UUID_REFERENCES", owners, paths)
    }

    private fun shouldSkip(path: Path, root: Path): Boolean {
        if (path == root) return false
        val relative = root.relativize(path)
        return relative.any { part ->
            part.toString().lowercase(Locale.ROOT) in SKIPPED_DIRECTORY_NAMES
        }
    }

    private fun containsPattern(path: Path, patterns: List<ByteArray>): Boolean {
        if (patterns.isEmpty() || Files.size(path) == 0L) return false
        val longest = patterns.maxOf { it.size }
        val buffer = ByteArray(BUFFER_SIZE)
        var tail = ByteArray(0)
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return false
                val combined = ByteArray(tail.size + read)
                System.arraycopy(tail, 0, combined, 0, tail.size)
                System.arraycopy(buffer, 0, combined, tail.size, read)
                if (patterns.any { containsBytes(combined, it) }) return true
                val keep = minOf(longest - 1, combined.size)
                tail = combined.copyOfRange(combined.size - keep, combined.size)
            }
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (offset in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[offset + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }

    private fun uuidPatterns(uuid: UUID): Patterns {
        val canonical = uuid.toString()
        val compact = canonical.replace("-", "")
        val raw = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        val strings = listOf(
            canonical.lowercase(Locale.ROOT),
            canonical.uppercase(Locale.ROOT),
            compact.lowercase(Locale.ROOT),
            compact.uppercase(Locale.ROOT),
        )
        return Patterns(
            strings.map { it.toByteArray(StandardCharsets.US_ASCII) } + raw,
            strings.map { it.lowercase(Locale.ROOT) },
        )
    }

    private data class Patterns(
        val bytes: List<ByteArray>,
        val textNames: List<String>,
    )

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REPORTED_OWNERS = 8
        private const val MAX_REPORTED_PATH_LENGTH = 180
        private val SKIPPED_DIRECTORY_NAMES = setOf(
            "logs",
            "log",
            "backup",
            "backups",
            "cache",
            "caches",
        )
    }
}
