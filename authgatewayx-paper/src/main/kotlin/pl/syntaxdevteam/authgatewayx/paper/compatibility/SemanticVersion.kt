package pl.syntaxdevteam.authgatewayx.paper.compatibility

data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        return compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
    }

    override fun toString(): String {
        return "$major.$minor.$patch"
    }

    companion object {
        private val VERSION_PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

        fun parseOrNull(raw: String): SemanticVersion? {
            val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            return SemanticVersion(major, minor, patch)
        }

        fun parse(raw: String): SemanticVersion =
            requireNotNull(parseOrNull(raw)) { "Invalid Minecraft version: $raw" }
    }
}

fun SemanticVersion.isBetween(start: SemanticVersion, endInclusive: SemanticVersion?): Boolean =
    this >= start && (endInclusive == null || this <= endInclusive)
