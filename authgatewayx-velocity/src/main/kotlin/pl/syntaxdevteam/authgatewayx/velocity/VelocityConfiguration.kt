package pl.syntaxdevteam.authgatewayx.velocity

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class VelocityConfiguration(
    val lookupTimeoutMillis: Long,
    val positiveTtlSeconds: Long,
    val negativeTtlSeconds: Long,
    val maximumCacheSize: Int,
    val pendingTtlSeconds: Long,
    val maximumPending: Int,
    val mojangThreads: Int,
    val mojangQueue: Int,
) {
    init {
        require(lookupTimeoutMillis > 0 && positiveTtlSeconds > 0 && negativeTtlSeconds > 0)
        require(maximumCacheSize > 0 && pendingTtlSeconds > 0 && maximumPending > 0)
        require(mojangThreads > 0 && mojangQueue > 0)
    }

    companion object {
        fun load(dataDirectory: Path, classLoader: ClassLoader): VelocityConfiguration {
            Files.createDirectories(dataDirectory)
            val path = dataDirectory.resolve("authgatewayx.yml")
            if (Files.notExists(path)) {
                classLoader.getResourceAsStream("authgatewayx.yml").use { source ->
                    requireNotNull(source) { "Bundled authgatewayx.yml is missing" }
                    Files.copy(source, path)
                }
            }
            val root = Files.newBufferedReader(path).use { Yaml(LoaderOptions()).load<Map<String, Any>>(it) }
            fun section(parent: Map<String, Any>, name: String): Map<String, Any> =
                @Suppress("UNCHECKED_CAST") (parent[name] as? Map<String, Any>) ?: error("Missing config section: $name")
            fun positive(map: Map<String, Any>, name: String): Long =
                (map[name] as? Number)?.toLong()?.takeIf { it > 0 } ?: error("$name must be positive")
            val premium = section(root, "premium")
            val lookup = section(premium, "lookup")
            val connections = section(root, "connections")
            val executors = section(root, "executors")
            return VelocityConfiguration(
                positive(lookup, "timeout-millis"), positive(lookup, "positive-ttl-seconds"),
                positive(lookup, "negative-ttl-seconds"), positive(lookup, "maximum-cache-size").toInt(),
                positive(connections, "pending-ttl-seconds"), positive(connections, "maximum-pending").toInt(),
                positive(executors, "mojang-threads").toInt(), positive(executors, "mojang-queue").toInt(),
            )
        }
    }
}
