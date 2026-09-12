package pl.syntaxdevteam.authgatewayx.velocity

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import pl.syntaxdevteam.message.ResourceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Uses the plugin configuration without creating MessageHandler's default config.yml. */
class VelocityMessageResources(directory: Path, private val loader: ClassLoader) : ResourceProvider {
    override val dataFolder = directory.toFile()
    private val configuration = read(directory.resolve("authgatewayx.yml"))
    // Read-only compatibility for installations that selected a language in the old file.
    private val legacyLanguage = if (configuration["language"] == null) read(directory.resolve("config.yml"))["language"] else null

    private fun read(path: Path): Map<String, Any?> = if (Files.exists(path))
        Files.newBufferedReader(path).use { Yaml(LoaderOptions()).load<Map<String, Any?>>(it) ?: emptyMap() }
    else emptyMap()

    override fun <T> getConfigValue(path: String, default: T): T {
        var value: Any? = configuration
        for (part in path.split('.')) value = (value as? Map<*, *>)?.get(part)
        if (path == "language" && value == null) value = legacyLanguage ?: "PL"
        @Suppress("UNCHECKED_CAST")
        return value as? T ?: default
    }

    override fun getResourceStream(resourcePath: String) = loader.getResourceAsStream(resourcePath)

    override fun saveResource(resourcePath: String, replace: Boolean) {
        val target = dataFolder.toPath().resolve(resourcePath)
        if (Files.exists(target) && !replace) return
        getResourceStream(resourcePath)?.use {
            Files.createDirectories(target.parent)
            if (replace) Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) else Files.copy(it, target)
        }
    }
}
