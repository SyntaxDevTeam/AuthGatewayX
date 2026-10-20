package pl.syntaxdevteam.authgatewayx.paper.privacy

/**
 * Minecraft Dialog arrived in Minecraft Java 1.21.7.
 * Compare the Minecraft server version, not the Paper build number
 * or the connecting client's protocol version.
 */
internal enum class ConsentPresentation { MINECRAFT_DIALOG, CHAT }

internal object ConsentPresentationSelector {
    private val versionPattern = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:$|[-+].*)""")

    fun select(minecraftVersion: String, dialogsEnabled: Boolean): ConsentPresentation {
        if (!dialogsEnabled) return ConsentPresentation.CHAT
        val match = versionPattern.matchEntire(minecraftVersion.trim()) ?: return ConsentPresentation.CHAT
        val major = match.groupValues[1].toIntOrNull() ?: return ConsentPresentation.CHAT
        val minor = match.groupValues[2].toIntOrNull() ?: return ConsentPresentation.CHAT
        val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return ConsentPresentation.CHAT

        // Minecraft 26.x uses year-based numbering and supports native dialogs.
        val supportsDialogs = major > 1 ||
            (major == 1 && (minor > 21 || (minor == 21 && patch >= 7)))
        return if (supportsDialogs) ConsentPresentation.MINECRAFT_DIALOG else ConsentPresentation.CHAT
    }
}
