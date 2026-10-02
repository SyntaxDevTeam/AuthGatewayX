package pl.syntaxdevteam.authgatewayx.api.craftconnect

import java.time.Instant
import java.util.UUID

data class CraftConnectDevice(
    val deviceId: String,
    val publicKey: ByteArray,
)

data class CraftConnectPairingChallenge(
    val challengeId: UUID,
    val playerUuid: UUID,
    val deviceId: String,
    val nonce: ByteArray,
    val expiresAt: Instant,
)

data class CraftConnectPairingRecord(
    val serverId: String,
    val playerUuid: UUID,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val revokedAt: Instant? = null,
) {
    val active: Boolean
        get() = revokedAt == null
}

/**
 * Platform/storage-neutral pairing boundary.
 *
 * Implementations are responsible for TTL, replay protection, rate limits,
 * persistence and audit. Calling code must not treat a device ID alone as proof
 * of possession of the paired key.
 */
interface CraftConnectPairingService {
    suspend fun beginPairing(playerUuid: UUID, device: CraftConnectDevice): CraftConnectPairingChallenge

    suspend fun confirmPairing(
        playerUuid: UUID,
        challengeId: UUID,
        signedChallenge: ByteArray,
    ): CraftConnectPairingRecord

    suspend fun revoke(playerUuid: UUID, deviceId: String): Boolean

    suspend fun pairedDevices(playerUuid: UUID): List<CraftConnectPairingRecord>
}
