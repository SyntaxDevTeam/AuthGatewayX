package pl.syntaxdevteam.authgatewayx.paper.client

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import pl.syntaxdevteam.authgatewayx.api.AuthenticationStatusProvider
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectCapability
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectFrame
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectMessage
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectProtocol
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectWireProtocol
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import pl.syntaxdevteam.authgatewayx.auth.craftconnect.CraftConnectPairingChallengeRegistry
import pl.syntaxdevteam.authgatewayx.auth.craftconnect.CraftConnectPairingVerification
import pl.syntaxdevteam.authgatewayx.paper.api.PaperCraftConnectCapabilityProvider
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Native AuthGatewayX Enhanced Mode channel for Paper/Folia.
 *
 * Current vertical slice implements discovery, live permission-derived pre-pair
 * capabilities and cryptographic pairing proof-of-possession. A valid device
 * signature intentionally stops at `player_approval_required`; persistent pairing
 * is not created until the explicit in-game approval workflow is implemented.
 */
class PaperCraftConnectChannel(
    private val plugin: Plugin,
    authenticationStatus: AuthenticationStatusProvider,
) : PluginMessageListener, AutoCloseable {
    private val capabilityProvider = PaperCraftConnectCapabilityProvider(plugin.server, authenticationStatus)
    private val serverId: String? = (plugin as? JavaPlugin)
        ?.config
        ?.getString("craftconnect.server-id")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    private val serverName: String = (plugin as? JavaPlugin)
        ?.config
        ?.getString("craftconnect.server-name")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: plugin.server.name
    private val challenges = serverId?.let(::CraftConnectPairingChallengeRegistry)
    private val verificationExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(128),
        { runnable -> Thread(runnable, "authgatewayx-craftconnect-pairing").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile
    private var closed = false

    fun register() {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CraftConnectProtocol.CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CraftConnectProtocol.CHANNEL)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (closed || channel != CraftConnectProtocol.CHANNEL || !player.isOnline) return
        val frame = runCatching { CraftConnectWireProtocol.decode(message) }.getOrNull() ?: return

        when (val payload = frame.message) {
            is CraftConnectMessage.ClientHello -> handleHello(player, frame.requestId)
            is CraftConnectMessage.PairingBegin -> handlePairingBegin(player, frame.requestId, payload)
            is CraftConnectMessage.PairingConfirm -> handlePairingConfirm(player, frame.requestId, payload)
            else -> send(player, frame.requestId, CraftConnectMessage.Error("unexpected_client_message"))
        }
    }

    private fun handleHello(player: Player, requestId: UUID) {
        val capabilities = capabilityProvider.capabilitiesFor(player.uniqueId, pairedDevice = false)
        val supported = if (challenges != null) setOf(CraftConnectCapability.PAIRING) else emptySet()
        send(
            player,
            requestId,
            CraftConnectMessage.ServerHello(
                serverId = serverId ?: "unconfigured",
                serverName = serverName,
                supported = supported,
            ),
        )
        send(player, requestId, CraftConnectMessage.Capabilities(capabilities.granted.intersect(supported)))
    }

    private fun handlePairingBegin(
        player: Player,
        requestId: UUID,
        payload: CraftConnectMessage.PairingBegin,
    ) {
        val registry = challenges
        if (registry == null) {
            send(player, requestId, CraftConnectMessage.Error("server_id_not_configured"))
            return
        }
        val capabilities = capabilityProvider.capabilitiesFor(player.uniqueId, pairedDevice = false)
        if (!capabilities.supports(CraftConnectCapability.PAIRING)) {
            send(player, requestId, CraftConnectMessage.Error("pairing_forbidden"))
            return
        }

        val challenge = runCatching {
            registry.begin(player.uniqueId, CraftConnectDevice(payload.deviceId, payload.publicKey))
        }.getOrElse {
            send(player, requestId, CraftConnectMessage.Error("invalid_pairing_device"))
            return
        }
        send(
            player,
            requestId,
            CraftConnectMessage.PairingChallenge(
                challengeId = challenge.challengeId,
                serverId = challenge.serverId,
                nonce = challenge.nonce,
                expiresAt = challenge.expiresAt,
            ),
        )
    }

    private fun handlePairingConfirm(
        player: Player,
        requestId: UUID,
        payload: CraftConnectMessage.PairingConfirm,
    ) {
        val registry = challenges
        if (registry == null) {
            send(player, requestId, CraftConnectMessage.Error("server_id_not_configured"))
            return
        }
        try {
            verificationExecutor.execute {
                val result = registry.verify(player.uniqueId, payload.challengeId, payload.signature)
                player.scheduler.run(plugin, { _ ->
                    if (!closed && player.isOnline) {
                        when (result) {
                            is CraftConnectPairingVerification.Verified -> send(
                                player,
                                requestId,
                                CraftConnectMessage.PairingResult(false, "player_approval_required"),
                            )
                            CraftConnectPairingVerification.Expired -> send(
                                player,
                                requestId,
                                CraftConnectMessage.PairingResult(false, "challenge_expired"),
                            )
                            CraftConnectPairingVerification.PlayerMismatch -> send(
                                player,
                                requestId,
                                CraftConnectMessage.PairingResult(false, "player_mismatch"),
                            )
                            CraftConnectPairingVerification.InvalidSignature -> send(
                                player,
                                requestId,
                                CraftConnectMessage.PairingResult(false, "invalid_signature"),
                            )
                            CraftConnectPairingVerification.MissingOrConsumed -> send(
                                player,
                                requestId,
                                CraftConnectMessage.PairingResult(false, "challenge_missing_or_consumed"),
                            )
                        }
                    }
                }, null)
            }
        } catch (_: RejectedExecutionException) {
            send(player, requestId, CraftConnectMessage.Error("pairing_busy"))
        }
    }

    private fun send(player: Player, requestId: UUID, message: CraftConnectMessage) {
        if (closed || !player.isOnline) return
        player.sendPluginMessage(
            plugin,
            CraftConnectProtocol.CHANNEL,
            CraftConnectWireProtocol.encode(CraftConnectFrame(requestId, message)),
        )
    }

    override fun close() {
        closed = true
        verificationExecutor.shutdownNow()
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CraftConnectProtocol.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CraftConnectProtocol.CHANNEL)
    }
}
