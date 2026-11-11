package pl.syntaxdevteam.logincorex.paper.compatibility

import org.bukkit.Server
import pl.syntaxdevteam.core.platform.ServerEnvironment
import pl.syntaxdevteam.logincorex.paper.LoginCoreXPaper

class VersionChecker(private val plugin: LoginCoreXPaper) {

    companion object {
        private val SUPPORTED_VERSIONS: Set<SemanticVersion> = setOf(
            SemanticVersion(26, 1, 0),
            SemanticVersion(26, 1, 1),
            SemanticVersion(26, 1, 2),
            SemanticVersion(26, 2, 0),
            SemanticVersion(26, 3, 0),
        )

        // Bukkit reports API builds (e.g. 26.3.build.248), not the game version.
        internal fun getServerVersion(server: Server): String =
            server.minecraftVersion.substringBefore("-")

        fun isVersionSupported(version: String): Boolean =
            SemanticVersion.parseOrNull(version) in SUPPORTED_VERSIONS
    }

    fun getServerVersion(): String =
        getServerVersion(plugin.server)

    fun isSupported(): Boolean =
        isVersionSupported(getServerVersion())

    fun checkAndLog(): Boolean {
        val version = getServerVersion()
        return if (isSupported()) {
            val platformVersion = ServerEnvironment.describeWithVersion(version)
            plugin.loggerX.success("Minecraft version $platformVersion matches the LoginCoreX target list; use the matching NMS artifact. Runtime compatibility requires separate verification.")
            true
        } else {
            plugin.loggerX.warning("Minecraft version $version is outside the LoginCoreX target list. NMS compatibility is unverified; use a matching artifact and test before deployment.")
            false
        }
    }

    fun getSemanticVersion(): SemanticVersion = SemanticVersion.parse(getServerVersion())

    @Suppress("unused") // Public compatibility helper for integrations.
    fun isAtLeast(minVersion: String): Boolean {
        val current = getSemanticVersion()
        val required = SemanticVersion.parse(minVersion)
        return current >= required
    }
}
