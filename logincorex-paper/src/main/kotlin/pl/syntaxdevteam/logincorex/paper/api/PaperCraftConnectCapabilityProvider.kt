package pl.syntaxdevteam.logincorex.paper.api

import org.bukkit.Server
import pl.syntaxdevteam.logincorex.api.AuthenticationStatusProvider
import pl.syntaxdevteam.logincorex.api.craftconnect.CraftConnectCapabilities
import pl.syntaxdevteam.logincorex.api.craftconnect.CraftConnectCapability
import pl.syntaxdevteam.logincorex.api.craftconnect.CraftConnectCapabilityProvider
import java.util.UUID

/**
 * Permission-backed capability resolver for Paper/Folia.
 *
 * Callers must invoke this from the player's valid platform context. It performs
 * no database/network I/O and never trusts capability claims from the client.
 */
class PaperCraftConnectCapabilityProvider(
    private val server: Server,
    private val authenticationStatus: AuthenticationStatusProvider,
) : CraftConnectCapabilityProvider {
    override fun capabilitiesFor(playerUuid: UUID, pairedDevice: Boolean): CraftConnectCapabilities {
        if (!authenticationStatus.isAuthenticated(playerUuid)) return CraftConnectCapabilities()
        val player = server.getPlayer(playerUuid)?.takeIf { it.isOnline } ?: return CraftConnectCapabilities()

        val permitted = CraftConnectCapability.entries
            .asSequence()
            .filter { player.hasPermission(it.permission) }
            .filter { pairedDevice || it == CraftConnectCapability.PAIRING }
            .toCollection(linkedSetOf())

        return CraftConnectCapabilities(granted = permitted)
    }
}
