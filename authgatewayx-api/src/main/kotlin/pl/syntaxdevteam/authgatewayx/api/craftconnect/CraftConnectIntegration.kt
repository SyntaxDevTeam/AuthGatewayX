package pl.syntaxdevteam.authgatewayx.api.craftconnect

/**
 * Public, platform-independent contract for CraftConnect Enhanced Mode.
 *
 * This API intentionally contains no Paper, Velocity, transport or storage types.
 */
object CraftConnectProtocol {
    const val CHANNEL: String = "authgatewayx:craftconnect"
    const val VERSION: Int = 1
}

enum class CraftConnectCapability(
    val permission: String,
) {
    PAIRING("authgatewayx.craftconnect.pair"),
    STATUS("authgatewayx.craftconnect.status"),
    STATS("authgatewayx.craftconnect.stats"),
    CONSOLE_VIEW("authgatewayx.craftconnect.console.view"),
    CONSOLE_EXECUTE("authgatewayx.craftconnect.console.execute"),
    SERVER_INFO("authgatewayx.craftconnect.server-info"),
    BRANDING("authgatewayx.craftconnect.branding"),
    DIAGNOSTICS("authgatewayx.craftconnect.diagnostics"),
}

data class CraftConnectCapabilities(
    val protocolVersion: Int = CraftConnectProtocol.VERSION,
    val granted: Set<CraftConnectCapability> = emptySet(),
) {
    fun supports(capability: CraftConnectCapability): Boolean = capability in granted
}

/**
 * Resolves the currently granted capabilities for an already authenticated player.
 * Implementations must derive authorization from current server-side permissions,
 * never from claims sent by the CraftConnect client.
 */
fun interface CraftConnectCapabilityProvider {
    fun capabilitiesFor(playerUuid: java.util.UUID): CraftConnectCapabilities
}
