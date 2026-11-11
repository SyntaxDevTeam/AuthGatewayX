package pl.syntaxdevteam.logincorex.api.craftconnect

/**
 * Public, platform-independent contract for CraftConnect Enhanced Mode.
 *
 * This API intentionally contains no Paper, Velocity, transport or storage types.
 */
object CraftConnectProtocol {
    const val CHANNEL: String = "logincorex:craftconnect"
    const val VERSION: Int = 1
    const val MAX_FRAME_BYTES: Int = 64 * 1024
}

enum class CraftConnectCapability(
    val wireId: String,
    val permission: String,
) {
    PAIRING("pairing", "logincorex.craftconnect.pair"),
    STATUS("status", "logincorex.craftconnect.status"),
    STATS("stats", "logincorex.craftconnect.stats"),
    CONSOLE_VIEW("console.view", "logincorex.craftconnect.console.view"),
    CONSOLE_EXECUTE("console.execute", "logincorex.craftconnect.console.execute"),
    SERVER_INFO("server.info", "logincorex.craftconnect.server-info"),
    BRANDING("branding", "logincorex.craftconnect.branding"),
    DIAGNOSTICS("diagnostics", "logincorex.craftconnect.diagnostics");

    companion object {
        private val BY_WIRE_ID = entries.associateBy(CraftConnectCapability::wireId)

        fun fromWireId(wireId: String): CraftConnectCapability? = BY_WIRE_ID[wireId]
    }
}

data class CraftConnectCapabilities(
    val protocolVersion: Int = CraftConnectProtocol.VERSION,
    val granted: Set<CraftConnectCapability> = emptySet(),
) {
    fun supports(capability: CraftConnectCapability): Boolean = capability in granted
}

/**
 * Resolves capabilities for an authenticated player and the current device state.
 *
 * Implementations must derive authorization from current server-side permissions,
 * never from claims sent by the CraftConnect client. Before a device proves an
 * active pairing, enhanced administration capabilities must not be granted.
 */
fun interface CraftConnectCapabilityProvider {
    fun capabilitiesFor(playerUuid: java.util.UUID, pairedDevice: Boolean): CraftConnectCapabilities
}
