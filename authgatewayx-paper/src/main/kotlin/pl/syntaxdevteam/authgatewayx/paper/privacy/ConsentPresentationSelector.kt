package pl.syntaxdevteam.authgatewayx.paper.privacy

import pl.syntaxdevteam.authgatewayx.paper.compatibility.SemanticVersion
import pl.syntaxdevteam.authgatewayx.paper.compatibility.VersionCompatibility

/**
 * Minecraft Dialog arrived in Minecraft Java 1.21.7.
 * Compare the Minecraft server version, not the Paper build number
 * or the connecting client's protocol version.
 */
internal enum class ConsentPresentation { MINECRAFT_DIALOG, CHAT }

internal object ConsentPresentationSelector {
    fun select(minecraftVersion: String, dialogsEnabled: Boolean): ConsentPresentation {
        val compatibility = VersionCompatibility(
            SemanticVersion.parseOrNull(minecraftVersion),
        )
        return if (dialogsEnabled && compatibility.supports(
                VersionCompatibility.CompatibilityFlag.DIALOGS,
            )) ConsentPresentation.MINECRAFT_DIALOG else ConsentPresentation.CHAT
    }
}
