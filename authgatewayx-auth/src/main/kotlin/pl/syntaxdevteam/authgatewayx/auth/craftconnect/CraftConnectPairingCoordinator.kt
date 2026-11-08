package pl.syntaxdevteam.authgatewayx.auth.craftconnect

import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import pl.syntaxdevteam.authgatewayx.storage.CraftConnectPairingStorage
import pl.syntaxdevteam.authgatewayx.storage.CraftConnectPairingWrite
import pl.syntaxdevteam.authgatewayx.storage.StoredCraftConnectPairing
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

sealed interface CraftConnectPairingResolution {
    data class Paired(val record: StoredCraftConnectPairing) : CraftConnectPairingResolution
    data class ApprovalRequired(val device: CraftConnectDevice) : CraftConnectPairingResolution
    data object MissingOrConsumed : CraftConnectPairingResolution
    data object Expired : CraftConnectPairingResolution
    data object PlayerMismatch : CraftConnectPairingResolution
    data object InvalidSignature : CraftConnectPairingResolution
    data object StoredKeyMismatch : CraftConnectPairingResolution
}

/**
 * Converts cryptographic proof into either an already-paired session or an
 * explicit human-approval request. No new record is persisted by [resolveProof].
 */
class CraftConnectPairingCoordinator(
    private val serverId: String,
    private val challenges: CraftConnectPairingChallengeRegistry,
    private val storage: CraftConnectPairingStorage,
    private val clock: () -> Instant = Instant::now,
) {
    fun resolveProof(
        playerUuid: UUID,
        challengeId: UUID,
        signedChallenge: ByteArray,
    ): CompletionStage<CraftConnectPairingResolution> {
        val verification = challenges.verify(playerUuid, challengeId, signedChallenge)
        if (verification !is CraftConnectPairingVerification.Verified) {
            return CompletableFuture.completedFuture(verification.toResolution())
        }
        val device = verification.device
        return storage.findActiveCraftConnectPairing(serverId, playerUuid, device.deviceId)
            .thenCompose { stored ->
                when {
                    stored == null -> CompletableFuture.completedFuture(
                        CraftConnectPairingResolution.ApprovalRequired(device),
                    )
                    !MessageDigest.isEqual(stored.devicePublicKey, device.publicKey) ->
                        CompletableFuture.completedFuture(CraftConnectPairingResolution.StoredKeyMismatch)
                    else -> storage.touchCraftConnectPairing(serverId, playerUuid, device.deviceId, clock())
                        .thenApply { touched ->
                            if (touched) CraftConnectPairingResolution.Paired(stored.copy(lastUsedAt = clock()))
                            else CraftConnectPairingResolution.ApprovalRequired(device)
                        }
                }
            }
    }

    fun approve(
        playerUuid: UUID,
        device: CraftConnectDevice,
    ): CompletionStage<StoredCraftConnectPairing> {
        val approvedAt = clock()
        return storage.saveApprovedCraftConnectPairing(
            CraftConnectPairingWrite(
                serverId = serverId,
                playerUuid = playerUuid,
                deviceId = device.deviceId,
                devicePublicKey = device.publicKey.copyOf(),
                approvedAt = approvedAt,
            ),
        )
    }

    fun revoke(playerUuid: UUID, deviceId: String): CompletionStage<Boolean> =
        storage.revokeCraftConnectPairing(serverId, playerUuid, deviceId, clock())

    fun list(playerUuid: UUID): CompletionStage<List<StoredCraftConnectPairing>> =
        storage.listCraftConnectPairings(serverId, playerUuid)

    private fun CraftConnectPairingVerification.toResolution(): CraftConnectPairingResolution = when (this) {
        is CraftConnectPairingVerification.Verified -> error("Verified resolution requires storage lookup")
        CraftConnectPairingVerification.MissingOrConsumed -> CraftConnectPairingResolution.MissingOrConsumed
        CraftConnectPairingVerification.Expired -> CraftConnectPairingResolution.Expired
        CraftConnectPairingVerification.PlayerMismatch -> CraftConnectPairingResolution.PlayerMismatch
        CraftConnectPairingVerification.InvalidSignature -> CraftConnectPairingResolution.InvalidSignature
    }
}
