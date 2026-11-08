package pl.syntaxdevteam.authgatewayx.paper.compatibility

import pl.syntaxdevteam.core.platform.ServerEnvironment
import pl.syntaxdevteam.authgatewayx.paper.AuthGatewayXPaper

class VersionChecker(private val plugin: AuthGatewayXPaper) {

    companion object {
        private val SUPPORTED_VERSIONS: Set<SemanticVersion> = setOf(
            SemanticVersion(26, 1, 0),
            SemanticVersion(26, 1, 1),
            SemanticVersion(26, 1, 2),
            SemanticVersion(26, 2, 0),
            SemanticVersion(26, 3, 0),
        )

        fun isVersionSupported(version: String): Boolean =
            SemanticVersion.parseOrNull(version) in SUPPORTED_VERSIONS
    }

    private fun getRawVersion(): String =
        plugin.server.bukkitVersion

    fun getServerVersion(): String =
        getRawVersion().substringBefore("-")

    fun isSupported(): Boolean =
        isVersionSupported(getServerVersion())

    fun checkAndLog(): Boolean {
        val version = getServerVersion()
        return if (isSupported()) {
            val platformVersion = ServerEnvironment.describeWithVersion(version)
            plugin.loggerX.success("Minecraft version $platformVersion matches the AuthGatewayX target list; use the matching NMS artifact. Runtime compatibility requires separate verification.")
            true
        } else {
            plugin.loggerX.warning("Minecraft version $version is outside the AuthGatewayX target list. NMS compatibility is unverified; use a matching artifact and test before deployment.")
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
