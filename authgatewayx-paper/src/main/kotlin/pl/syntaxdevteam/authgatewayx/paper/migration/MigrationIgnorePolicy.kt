package pl.syntaxdevteam.authgatewayx.paper.migration

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MigrationIgnorePolicy(initialDirectories: Collection<String> = emptyList()) {
    private val directories = AtomicReference(normalize(initialDirectories))

    fun snapshot(): Set<String> = directories.get()

    fun replace(values: Collection<String>): Set<String> = normalize(values).also(directories::set)

    companion object {
        fun normalize(values: Collection<String>): Set<String> {
            val normalized = linkedMapOf<String, String>()
            values.forEach { raw ->
                val value = raw.trim()
                require(validDirectory(value)) {
                    "Ignored plugin directory must be a simple directory name: $raw"
                }
                normalized.putIfAbsent(value.lowercase(Locale.ROOT), value)
            }
            return normalized.values.toSet()
        }

        private fun validDirectory(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= 96 &&
                !value.contains('/') &&
                !value.contains('\\') &&
                value != "." &&
                value != ".." &&
                !value.contains('\u0000')
    }
}
