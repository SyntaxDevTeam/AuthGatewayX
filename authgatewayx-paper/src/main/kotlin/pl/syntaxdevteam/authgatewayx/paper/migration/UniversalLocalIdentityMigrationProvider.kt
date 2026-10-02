package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionStage

/**
 * Safe fallback for local plugin data that is not claimed by a dedicated migration provider.
 *
 * The provider never performs blind database edits or global text replacement. It can migrate:
 *  - exact UUID-indexed text files when the UUID exists only in the file name,
 *  - paths explicitly described by a trusted built-in/admin recipe.
 *
 * Everything else remains fail-closed and is reported for review.
 */
class UniversalLocalIdentityMigrationProvider(
    private val pluginsRoot: Path,
    private val authGatewayDataDirectory: Path,
    private val backupRoot: Path,
    private val managedDataOwnersSupplier: () -> Set<String>,
    private val recipeRegistry: MigrationRecipeRegistry,
    private val executor: BoundedTaskExecutor,
    private val maximumFiles: Int,
    private val maximumTotalBytes: Long,
    private val genericUuidFilesEnabled: Boolean,
    genericExtensions: Set<String>,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:universal-local"

    private val genericExtensions = genericExtensions
        .map { it.trim().lowercase(Locale.ROOT).removePrefix(".") }
        .filter { it.matches(EXTENSION) }
        .toSet()

    init {
        require(maximumFiles > 0)
        require(maximumTotalBytes > 0)
    }

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> =
        executor.submit {
            val plan = buildPlan(context)
            when {
                plan.blockedReason != null ->
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.BLOCKED,
                        plan.blockedReason,
                        legacyEvidence = plan.legacyEvidence,
                    )
                plan.operations.isEmpty() ->
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.NO_DATA,
                        "NO_UNIVERSAL_LOCAL_UUID_DATA",
                    )
                else ->
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.READY,
                        diagnostic(
                            "UNIVERSAL_LOCAL_READY",
                            plan.operations.size.toString(),
                            plan.operations.count { it.origin == OperationOrigin.RECIPE }.toString(),
                            plan.operations.count { it.origin == OperationOrigin.GENERIC }.toString(),
                            plan.operations.take(MAX_REPORTED_PATHS).joinToString(",") { displayPath(it.source) },
                        ),
                        legacyEvidence = true,
                    )
            }
        }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            val plan = buildPlan(context)
            if (plan.blockedReason != null) {
                return@submit IdentityMigrationOperationResult.Failure(plan.blockedReason)
            }
            if (plan.operations.isEmpty()) return@submit IdentityMigrationOperationResult.NoData

            runCatching {
                val resolved = plan.operations.map { operation ->
                    operation to desiredContent(operation, context)
                }
                prepareRollback(context, resolved.map { it.first })
                resolved.forEach { (operation, content) ->
                    writeAtomically(operation.target, content)
                }
                IdentityMigrationOperationResult.Success
            }.getOrElse { failure ->
                val reason = (failure as? LocalMigrationFailure)?.reasonCode
                    ?: "UNIVERSAL_LOCAL_MIGRATE_${failure.javaClass.simpleName}"
                IdentityMigrationOperationResult.Failure(reason)
            }
        }

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            runCatching {
                val root = migrationBackupRoot(context)
                val journal = root.resolve(JOURNAL_FILE)
                if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    return@submit IdentityMigrationOperationResult.NoData
                }
                val targets = Files.readAllLines(journal, StandardCharsets.UTF_8)
                    .filter(String::isNotBlank)
                    .map(::decodeJournalPath)
                    .asReversed()
                for (relative in targets) {
                    val target = resolveJournalTarget(relative)
                    if (hasSymlinkInPath(pluginsRoot, target)) {
                        throw LocalMigrationFailure("UNIVERSAL_LOCAL_ROLLBACK_SYMLINK")
                    }
                    val backup = targetBackup(root, relative)
                    val absent = targetAbsentMarker(root, relative)
                    when {
                        Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) ->
                            writeAtomically(target, Files.readAllBytes(backup))
                        Files.isRegularFile(absent, LinkOption.NOFOLLOW_LINKS) -> {
                            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                                !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            ) {
                                throw LocalMigrationFailure("UNIVERSAL_LOCAL_TARGET_NOT_REGULAR_FILE")
                            }
                            Files.deleteIfExists(target)
                        }
                        else ->
                            throw LocalMigrationFailure("UNIVERSAL_LOCAL_ROLLBACK_BACKUP_MISSING")
                    }
                }
                IdentityMigrationOperationResult.Success
            }.getOrElse { failure ->
                val reason = (failure as? LocalMigrationFailure)?.reasonCode
                    ?: "UNIVERSAL_LOCAL_ROLLBACK_${failure.javaClass.simpleName}"
                IdentityMigrationOperationResult.Failure(reason)
            }
        }

    private fun buildPlan(context: IdentityMigrationContext): LocalPlan {
        if (!Files.isDirectory(pluginsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return LocalPlan(blockedReason = "PLUGIN_ROOT_UNAVAILABLE")
        }

        val loadedRecipes = recipeRegistry.load()
        loadedRecipes.errorCode?.let {
            return LocalPlan(blockedReason = it)
        }

        val excluded = managedDataOwnersSupplier()
            .map { it.lowercase(Locale.ROOT) }
            .toMutableSet()
            .apply { add(authGatewayDataDirectory.fileName.toString().lowercase(Locale.ROOT)) }
        val recipesByDirectory = loadedRecipes.recipes.groupBy { it.pluginDirectory.lowercase(Locale.ROOT) }
        val patterns = uuidPatterns(context.sourceMinecraftUuid)
        val operationsByTarget = linkedMapOf<Path, LocalOperation>()
        val unresolvedOwners = linkedSetOf<String>()
        val unresolvedPaths = mutableListOf<String>()
        var unresolvedFound = false
        var filesSeen = 0
        var bytesSeen = 0L

        Files.list(pluginsRoot).use { children ->
            val directories = children.iterator()
            while (directories.hasNext()) {
                val directory = directories.next()
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                val owner = directory.fileName.toString()
                if (owner.lowercase(Locale.ROOT) in excluded) continue

                val recipeSources = linkedMapOf<Path, RecipeCandidate>()
                for (recipe in recipesByDirectory[owner.lowercase(Locale.ROOT)].orEmpty()) {
                    for (rule in recipe.rules) {
                        val source = safeResolve(directory, expand(rule.sourceTemplate, context))
                            ?: return LocalPlan(
                                blockedReason = diagnostic(
                                    "UNIVERSAL_LOCAL_UNSAFE_PATH",
                                    recipe.id,
                                    rule.sourceTemplate,
                                ),
                            )
                        val target = safeResolve(directory, expand(rule.targetTemplate, context))
                            ?: return LocalPlan(
                                blockedReason = diagnostic(
                                    "UNIVERSAL_LOCAL_UNSAFE_PATH",
                                    recipe.id,
                                    rule.targetTemplate,
                                ),
                            )
                        recipeSources[source] = RecipeCandidate(recipe.id, rule, target)
                    }
                }

                val recipeTargets = recipeSources.values.mapTo(mutableSetOf()) { it.target }

                Files.walk(directory).use { paths ->
                    val iterator = paths.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next().toAbsolutePath().normalize()
                        if (shouldSkip(path, directory.toAbsolutePath().normalize())) continue
                        if (Files.isSymbolicLink(path)) {
                            return LocalPlan(
                                blockedReason = diagnostic("UNMANAGED_PLUGIN_SYMLINK", displayPath(path)),
                                legacyEvidence = unresolvedFound || operationsByTarget.isNotEmpty(),
                            )
                        }
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue

                        filesSeen++
                        if (filesSeen > maximumFiles) {
                            return LocalPlan(
                                blockedReason = diagnostic(
                                    "UNMANAGED_SCAN_FILE_LIMIT",
                                    displayPath(path),
                                    maximumFiles.toString(),
                                ),
                                legacyEvidence = unresolvedFound || operationsByTarget.isNotEmpty(),
                            )
                        }

                        val size = Files.size(path)
                        if (size > maximumTotalBytes - bytesSeen) {
                            return LocalPlan(
                                blockedReason = diagnostic(
                                    "UNMANAGED_SCAN_BYTE_LIMIT",
                                    displayPath(path),
                                    size.toString(),
                                    bytesSeen.toString(),
                                    maximumTotalBytes.toString(),
                                ),
                                legacyEvidence = unresolvedFound || operationsByTarget.isNotEmpty(),
                            )
                        }
                        bytesSeen += size

                        val recipe = recipeSources[path]
                        if (recipe != null) {
                            val targetProblem = validateTarget(directory, recipe.target)
                            if (targetProblem != null) {
                                return LocalPlan(
                                    blockedReason = diagnostic(targetProblem, displayPath(recipe.target)),
                                    legacyEvidence = true,
                                )
                            }
                            if (recipe.rule.requireSourceUuidAbsentInContent &&
                                containsPattern(path, patterns.bytes)
                            ) {
                                unresolvedFound = true
                                unresolvedOwners += owner
                                reportPath(unresolvedPaths, path)
                                continue
                            }
                            if (recipe.rule.rewriteUuidInContent) {
                                runCatching { rewriteUuid(Files.readAllBytes(path), context) }.getOrElse {
                                    return LocalPlan(
                                        blockedReason = diagnostic(
                                            "UNIVERSAL_LOCAL_RECIPE_NOT_UTF8",
                                            recipe.recipeId,
                                            displayPath(path),
                                        ),
                                        legacyEvidence = true,
                                    )
                                }
                            }
                            val collision = addOperation(
                                operationsByTarget,
                                LocalOperation(
                                    owner = owner,
                                    source = path,
                                    target = recipe.target,
                                    origin = OperationOrigin.RECIPE,
                                    rewriteUuidInContent = recipe.rule.rewriteUuidInContent,
                                ),
                            )
                            if (collision != null) {
                                return LocalPlan(blockedReason = collision, legacyEvidence = true)
                            }
                            continue
                        }

                        val genericTarget = genericTarget(path, context)
                        if (genericTarget != null) {
                            val targetProblem = validateTarget(directory, genericTarget)
                            if (targetProblem != null) {
                                return LocalPlan(
                                    blockedReason = diagnostic(targetProblem, displayPath(genericTarget)),
                                    legacyEvidence = true,
                                )
                            }
                            if (containsPattern(path, patterns.bytes)) {
                                unresolvedFound = true
                                unresolvedOwners += owner
                                reportPath(unresolvedPaths, path)
                            } else {
                                val collision = addOperation(
                                    operationsByTarget,
                                    LocalOperation(
                                        owner = owner,
                                        source = path,
                                        target = genericTarget,
                                        origin = OperationOrigin.GENERIC,
                                        rewriteUuidInContent = false,
                                    ),
                                )
                                if (collision != null) {
                                    return LocalPlan(blockedReason = collision, legacyEvidence = true)
                                }
                            }
                            continue
                        }

                        val fileName = path.fileName.toString().lowercase(Locale.ROOT)
                        if (patterns.textNames.any(fileName::contains) || containsPattern(path, patterns.bytes)) {
                            unresolvedFound = true
                            unresolvedOwners += owner
                            reportPath(unresolvedPaths, path)
                        }
                    }
                }
            }
        }

        if (unresolvedFound) {
            return LocalPlan(
                operations = operationsByTarget.values.toList(),
                blockedReason = diagnostic(
                    "UNIVERSAL_LOCAL_REVIEW_REQUIRED",
                    operationsByTarget.size.toString(),
                    unresolvedOwners.take(MAX_REPORTED_OWNERS).joinToString(","),
                    unresolvedPaths.joinToString(","),
                ),
                legacyEvidence = true,
            )
        }
        return LocalPlan(
            operations = operationsByTarget.values.sortedBy { pluginsRelative(it.target).toString() },
        )
    }

    private fun addOperation(
        operationsByTarget: MutableMap<Path, LocalOperation>,
        operation: LocalOperation,
    ): String? {
        val normalizedTarget = operation.target.toAbsolutePath().normalize()
        val existing = operationsByTarget[normalizedTarget]
        if (existing == null) {
            operationsByTarget[normalizedTarget] = operation.copy(target = normalizedTarget)
            return null
        }
        if (existing.source == operation.source &&
            existing.rewriteUuidInContent == operation.rewriteUuidInContent
        ) {
            return null
        }
        return diagnostic(
            "UNIVERSAL_LOCAL_TARGET_COLLISION",
            displayPath(normalizedTarget),
        )
    }

    private fun genericTarget(path: Path, context: IdentityMigrationContext): Path? {
        val match = genericUuidFile(path.fileName.toString(), context.sourceMinecraftUuid) ?: return null
        val targetName = if (match.compact) {
            context.targetMinecraftUuid.toString().replace("-", "") + match.suffix
        } else {
            context.targetMinecraftUuid.toString() + match.suffix
        }
        return path.resolveSibling(targetName).toAbsolutePath().normalize()
    }

    private fun genericSourceForTarget(path: Path, context: IdentityMigrationContext): Path? {
        val match = genericUuidFile(path.fileName.toString(), context.targetMinecraftUuid) ?: return null
        val sourceName = if (match.compact) {
            context.sourceMinecraftUuid.toString().replace("-", "") + match.suffix
        } else {
            context.sourceMinecraftUuid.toString() + match.suffix
        }
        return path.resolveSibling(sourceName).toAbsolutePath().normalize()
    }

    private fun genericUuidFile(fileName: String, uuid: UUID): GenericFileMatch? {
        if (!genericUuidFilesEnabled || genericExtensions.isEmpty()) return null
        val canonical = uuid.toString()
        val compact = canonical.replace("-", "")
        val prefix = when {
            fileName.length > canonical.length &&
                fileName.regionMatches(0, canonical, 0, canonical.length, true) -> canonical
            fileName.length > compact.length &&
                fileName.regionMatches(0, compact, 0, compact.length, true) -> compact
            else -> return null
        }
        val suffix = fileName.substring(prefix.length)
        if (!suffix.startsWith(".") ||
            suffix.removePrefix(".").lowercase(Locale.ROOT) !in genericExtensions
        ) {
            return null
        }
        return GenericFileMatch(suffix, compact = prefix.length == compact.length)
    }

    private fun validateTarget(pluginDirectory: Path, target: Path): String? {
        val normalizedPlugin = pluginDirectory.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedPlugin)) return "UNIVERSAL_LOCAL_UNSAFE_PATH"
        if (hasSymlinkInPath(normalizedPlugin, normalizedTarget)) return "UNIVERSAL_LOCAL_SYMLINK"
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
        ) {
            return "UNIVERSAL_LOCAL_TARGET_NOT_REGULAR_FILE"
        }
        return null
    }

    private fun safeResolve(root: Path, expanded: String): Path? {
        if (expanded.isBlank() || expanded.contains('\u0000') || expanded.contains('\\')) return null
        return runCatching {
            val relative = Path.of(expanded)
            if (relative.isAbsolute || relative.any { it.toString() == ".." }) return null
            val normalizedRoot = root.toAbsolutePath().normalize()
            normalizedRoot.resolve(relative).normalize().takeIf { it.startsWith(normalizedRoot) }
        }.getOrNull()
    }

    private fun expand(template: String, context: IdentityMigrationContext): String =
        template
            .replace("{source_uuid}", context.sourceMinecraftUuid.toString())
            .replace("{source_uuid_compact}", context.sourceMinecraftUuid.toString().replace("-", ""))
            .replace("{target_uuid}", context.targetMinecraftUuid.toString())
            .replace("{target_uuid_compact}", context.targetMinecraftUuid.toString().replace("-", ""))

    private fun desiredContent(operation: LocalOperation, context: IdentityMigrationContext): ByteArray {
        if (!Files.isRegularFile(operation.source, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(operation.source)
        ) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_SOURCE_CHANGED")
        }
        val bytes = Files.readAllBytes(operation.source)
        return if (operation.rewriteUuidInContent) rewriteUuid(bytes, context) else bytes
    }

    private fun rewriteUuid(source: ByteArray, context: IdentityMigrationContext): ByteArray {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var text = decoder.decode(ByteBuffer.wrap(source)).toString()
        val old = context.sourceMinecraftUuid.toString()
        val new = context.targetMinecraftUuid.toString()
        val oldCompact = old.replace("-", "")
        val newCompact = new.replace("-", "")
        text = text
            .replace(old, new)
            .replace(old.uppercase(Locale.ROOT), new.uppercase(Locale.ROOT))
            .replace(oldCompact, newCompact)
            .replace(oldCompact.uppercase(Locale.ROOT), newCompact.uppercase(Locale.ROOT))
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun prepareRollback(context: IdentityMigrationContext, operations: List<LocalOperation>) {
        val root = migrationBackupRoot(context)
        Files.createDirectories(root)
        val journalEntries = operations.map { operation ->
            val relative = pluginsRelative(operation.target)
            prepareTargetBackup(root, operation.target, relative)
            encodeJournalPath(relative)
        }
        val expectedJournal = journalEntries.joinToString(separator = "\n", postfix = "\n")
        val journal = root.resolve(JOURNAL_FILE)
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) ||
                Files.readString(journal, StandardCharsets.UTF_8) != expectedJournal
            ) {
                throw LocalMigrationFailure("UNIVERSAL_LOCAL_JOURNAL_MISMATCH")
            }
        } else {
            writeAtomically(journal, expectedJournal.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun prepareTargetBackup(root: Path, target: Path, relative: Path) {
        if (hasSymlinkInPath(pluginsRoot, target)) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_SYMLINK")
        }
        val backup = targetBackup(root, relative)
        val absent = targetAbsentMarker(root, relative)
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(absent, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_BACKUP_CONFLICT")
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(absent, LinkOption.NOFOLLOW_LINKS)
        ) {
            return
        }

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw LocalMigrationFailure("UNIVERSAL_LOCAL_TARGET_NOT_REGULAR_FILE")
            }
            Files.createDirectories(backup.parent)
            Files.copy(
                target,
                backup,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
        } else {
            Files.createDirectories(absent.parent)
            Files.writeString(absent, "target-absent", StandardOpenOption.CREATE_NEW)
        }
    }

    private fun migrationBackupRoot(context: IdentityMigrationContext): Path =
        backupRoot.resolve(context.migrationId.toString())

    private fun targetBackup(root: Path, relative: Path): Path {
        val base = root.resolve("targets-present").resolve(relative).normalize()
        if (!base.startsWith(root.resolve("targets-present").normalize())) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_UNSAFE_BACKUP_PATH")
        }
        return base.resolveSibling(base.fileName.toString() + ".bak")
    }

    private fun targetAbsentMarker(root: Path, relative: Path): Path {
        val base = root.resolve("targets-absent").resolve(relative).normalize()
        if (!base.startsWith(root.resolve("targets-absent").normalize())) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_UNSAFE_BACKUP_PATH")
        }
        return base.resolveSibling(base.fileName.toString() + ".absent")
    }

    private fun pluginsRelative(path: Path): Path {
        val root = pluginsRoot.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_UNSAFE_PATH")
        }
        return root.relativize(normalized)
    }

    private fun resolveJournalTarget(relative: Path): Path {
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_JOURNAL_MISMATCH")
        }
        val root = pluginsRoot.toAbsolutePath().normalize()
        return root.resolve(relative).normalize().takeIf { it.startsWith(root) }
            ?: throw LocalMigrationFailure("UNIVERSAL_LOCAL_JOURNAL_MISMATCH")
    }

    private fun encodeJournalPath(relative: Path): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relative.toString().toByteArray(StandardCharsets.UTF_8))

    private fun decodeJournalPath(encoded: String): Path =
        runCatching {
            val decoded = Base64.getUrlDecoder().decode(encoded)
            Path.of(String(decoded, StandardCharsets.UTF_8))
        }.getOrElse {
            throw LocalMigrationFailure("UNIVERSAL_LOCAL_JOURNAL_MISMATCH")
        }

    private fun writeAtomically(target: Path, content: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.agx.tmp")
        Files.write(
            temporary,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasSymlinkInPath(root: Path, target: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedRoot)) return true
        var current = normalizedRoot
        for (part in normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun shouldSkip(path: Path, root: Path): Boolean {
        if (path == root) return false
        val relative = root.relativize(path)
        return relative.any { part ->
            part.toString().lowercase(Locale.ROOT) in SKIPPED_DIRECTORY_NAMES
        }
    }

    private fun reportPath(paths: MutableList<String>, path: Path) {
        if (paths.size < MAX_REPORTED_PATHS) paths += displayPath(path)
    }

    private fun displayPath(path: Path): String =
        runCatching {
            pluginsRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
        }.getOrElse { path.fileName?.toString() ?: "?" }
            .replace("::", "_")
            .replace(",", "_")
            .take(MAX_REPORTED_PATH_LENGTH)

    private fun diagnostic(code: String, vararg values: String): String =
        (listOf(code) + values.map {
            it.replace("::", "_").take(MAX_REPORTED_VALUE_LENGTH)
        }).joinToString("::")

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
            bytes = strings.map { it.toByteArray(StandardCharsets.US_ASCII) } + raw,
            textNames = strings,
        )
    }

    private data class Patterns(
        val bytes: List<ByteArray>,
        val textNames: List<String>,
    )

    private data class GenericFileMatch(
        val suffix: String,
        val compact: Boolean,
    )

    private data class RecipeCandidate(
        val recipeId: String,
        val rule: LocalUuidFileRecipeRule,
        val target: Path,
    )

    private data class LocalOperation(
        val owner: String,
        val source: Path,
        val target: Path,
        val origin: OperationOrigin,
        val rewriteUuidInContent: Boolean,
    )

    private data class LocalPlan(
        val operations: List<LocalOperation> = emptyList(),
        val blockedReason: String? = null,
        val legacyEvidence: Boolean = false,
    )

    private enum class OperationOrigin {
        RECIPE,
        GENERIC,
    }

    private class LocalMigrationFailure(val reasonCode: String) : RuntimeException(reasonCode)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_REPORTED_OWNERS = 8
        private const val MAX_REPORTED_PATHS = 8
        private const val MAX_REPORTED_PATH_LENGTH = 180
        private const val MAX_REPORTED_VALUE_LENGTH = 512
        private const val JOURNAL_FILE = "targets.journal"
        private val EXTENSION = Regex("^[a-z0-9_-]{1,16}$")
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
