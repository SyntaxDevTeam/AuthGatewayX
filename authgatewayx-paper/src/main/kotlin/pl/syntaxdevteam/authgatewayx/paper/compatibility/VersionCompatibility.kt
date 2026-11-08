package pl.syntaxdevteam.authgatewayx.paper.compatibility

/** Version thresholds describe API availability, not verified NMS/runtime support. */
class VersionCompatibility(private val currentVersion: SemanticVersion?) {
    constructor(versionChecker: VersionChecker) : this(versionChecker.getSemanticVersion())

    enum class CompatibilityFlag { MODERN_LOGIN_EVENTS, DIALOGS }

    fun supports(flag: CompatibilityFlag): Boolean {
        val introducedAt = when (flag) {
            CompatibilityFlag.MODERN_LOGIN_EVENTS, CompatibilityFlag.DIALOGS -> SemanticVersion(1, 21, 7)
        }
        return currentVersion != null && currentVersion >= introducedAt
    }
}
