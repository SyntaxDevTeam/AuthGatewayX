package pl.syntaxdevteam.authgatewayx.paper.migration

import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

data class LocalUuidFileRecipeRule(
    val sourceTemplate: String,
    val targetTemplate: String,
    val rewriteUuidInContent: Boolean = false,
    val requireSourceUuidAbsentInContent: Boolean = !rewriteUuidInContent,
)

data class LocalMigrationRecipe(
    val id: String,
    val pluginDirectory: String,
    val rules: List<LocalUuidFileRecipeRule>,
)

data class MigrationRecipeLoadResult(
    val recipes: List<LocalMigrationRecipe>,
    val errorCode: String? = null,
)

/**
 * Registry of declarative local UUID migration recipes.
 *
 * Built-ins cover formats whose semantics are known. Administrators may add narrowly scoped
 * recipes in plugins/AuthGatewayX/migration-recipes without recompiling AuthGatewayX.
 *
 * Custom recipes deliberately support only UUID-indexed files. Arbitrary database writes and
 * global search/replace are outside this layer and still require a dedicated provider.
 */
class MigrationRecipeRegistry(
    private val customDirectory: Path,
) {
    fun load(): MigrationRecipeLoadResult {
        val recipes = BUILT_INS.toMutableList()
        return runCatching {
            Files.createDirectories(customDirectory)
            Files.list(customDirectory).use { files ->
                val iterator = files
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter {
                        val name = it.fileName.toString().lowercase(Locale.ROOT)
                        name.endsWith(".yml") || name.endsWith(".yaml")
                    }
                    .sorted()
                    .iterator()
                while (iterator.hasNext()) {
                    val file = iterator.next()
                    if (Files.isSymbolicLink(file)) {
                        return MigrationRecipeLoadResult(
                            recipes,
                            "UNIVERSAL_LOCAL_RECIPE_INVALID::${safeName(file)}::SYMLINK",
                        )
                    }
                    val size = Files.size(file)
                    if (size > MAX_RECIPE_BYTES) {
                        return MigrationRecipeLoadResult(
                            recipes,
                            "UNIVERSAL_LOCAL_RECIPE_INVALID::${safeName(file)}::TOO_LARGE",
                        )
                    }
                    val parsed = parse(file)
                    if (parsed.errorCode != null) return MigrationRecipeLoadResult(recipes, parsed.errorCode)
                    val recipe = parsed.recipes.single()
                    if (recipes.any { it.id.equals(recipe.id, ignoreCase = true) }) {
                        return MigrationRecipeLoadResult(
                            recipes,
                            "UNIVERSAL_LOCAL_RECIPE_INVALID::${safeName(file)}::DUPLICATE_ID",
                        )
                    }
                    recipes += recipe
                }
            }
            MigrationRecipeLoadResult(recipes)
        }.getOrElse {
            MigrationRecipeLoadResult(
                recipes,
                "UNIVERSAL_LOCAL_RECIPE_INVALID::registry::${it.javaClass.simpleName}",
            )
        }
    }

    private fun parse(file: Path): MigrationRecipeLoadResult {
        val yaml = runCatching { YamlConfiguration.loadConfiguration(file.toFile()) }.getOrElse {
            return MigrationRecipeLoadResult(
                emptyList(),
                "UNIVERSAL_LOCAL_RECIPE_INVALID::${safeName(file)}::YAML_${it.javaClass.simpleName}",
            )
        }
        val id = yaml.getString("id")?.trim().orEmpty()
        val pluginDirectory = yaml.getString("plugin-directory")?.trim().orEmpty()
        if (!RECIPE_ID.matches(id)) {
            return invalid(file, "INVALID_ID")
        }
        if (!validPluginDirectory(pluginDirectory)) {
            return invalid(file, "INVALID_PLUGIN_DIRECTORY")
        }

        val rawRules = yaml.getMapList("rules")
        if (rawRules.isEmpty() || rawRules.size > MAX_RULES_PER_RECIPE) {
            return invalid(file, "INVALID_RULE_COUNT")
        }

        val rules = mutableListOf<LocalUuidFileRecipeRule>()
        for (raw in rawRules) {
            val type = raw["type"]?.toString()?.trim()?.uppercase(Locale.ROOT)
            if (type != "UUID_FILE") return invalid(file, "UNSUPPORTED_RULE_TYPE")
            val source = raw["source"]?.toString()?.trim().orEmpty()
            val target = raw["target"]?.toString()?.trim().orEmpty()
            if (!validTemplate(source, "{source_uuid}", "{source_uuid_compact}") ||
                !validTemplate(target, "{target_uuid}", "{target_uuid_compact}")
            ) {
                return invalid(file, "INVALID_PATH_TEMPLATE")
            }
            val rewrite = raw.boolean("rewrite-uuid-in-content", false)
            val requireAbsent = raw.boolean("require-source-uuid-absent", !rewrite)
            rules += LocalUuidFileRecipeRule(
                sourceTemplate = source,
                targetTemplate = target,
                rewriteUuidInContent = rewrite,
                requireSourceUuidAbsentInContent = requireAbsent,
            )
        }
        return MigrationRecipeLoadResult(listOf(LocalMigrationRecipe(id, pluginDirectory, rules)))
    }

    private fun invalid(file: Path, reason: String) =
        MigrationRecipeLoadResult(
            emptyList(),
            "UNIVERSAL_LOCAL_RECIPE_INVALID::${safeName(file)}::$reason",
        )

    private fun validPluginDirectory(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_PLUGIN_DIRECTORY_LENGTH &&
            !value.contains('/') &&
            !value.contains('\\') &&
            value != "." &&
            value != ".."

    private fun validTemplate(value: String, vararg requiredAny: String): Boolean {
        if (value.isBlank() || value.length > MAX_TEMPLATE_LENGTH) return false
        if (!requiredAny.any(value::contains)) return false
        if (value.startsWith("/") || value.contains('\\') || value.contains("..")) return false
        if (value.contains('\u0000')) return false
        val allowed = setOf(
            "{source_uuid}",
            "{source_uuid_compact}",
            "{target_uuid}",
            "{target_uuid_compact}",
        )
        val placeholders = PLACEHOLDER.findAll(value).map { it.value }.toSet()
        return placeholders.all { it in allowed }
    }

    private fun Map<*, *>.boolean(key: String, default: Boolean): Boolean =
        when (val value = this[key]) {
            null -> default
            is Boolean -> value
            else -> value.toString().toBooleanStrictOrNull() ?: default
        }

    private fun safeName(path: Path): String =
        path.fileName?.toString()?.replace("::", "_")?.take(80) ?: "?"

    companion object {
        private const val MAX_RECIPE_BYTES = 256L * 1024L
        private const val MAX_RULES_PER_RECIPE = 32
        private const val MAX_TEMPLATE_LENGTH = 240
        private const val MAX_PLUGIN_DIRECTORY_LENGTH = 96
        private val RECIPE_ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        private val PLACEHOLDER = Regex("\\{[a-z_]+}")

        val BUILT_INS: List<LocalMigrationRecipe> = listOf(
            LocalMigrationRecipe(
                id = "advancedportals",
                pluginDirectory = "AdvancedPortals",
                rules = listOf(
                    LocalUuidFileRecipeRule(
                        sourceTemplate = "playerData/{source_uuid}.yaml",
                        targetTemplate = "playerData/{target_uuid}.yaml",
                        rewriteUuidInContent = false,
                        requireSourceUuidAbsentInContent = true,
                    ),
                ),
            ),
        )
    }
}
