package pl.syntaxdevteam.authgatewayx.api.craftconnect

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

enum class CraftConnectDeviceKeyAlgorithm {
    EC_P256_SHA256,
}

data class CraftConnectDevice(
    val deviceId: String,
    val publicKey: ByteArray,
    val algorithm: CraftConnectDeviceKeyAlgorithm = CraftConnectDeviceKeyAlgorithm.EC_P256_SHA256,
)

data class CraftConnectPairingChallenge(
    val challengeId: UUID,
    val serverId: String,
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

/** Canonical, domain-separated bytes signed by the device for protocol v1 pairing. */
object CraftConnectPairingSignaturePayload {
    private const val DOMAIN = "AGX-CRAFTCONNECT-PAIR-V1"
    private const val MAX_STRING_BYTES = 4 * 1024
    private const val MAX_NONCE_BYTES = 256

    fun encode(challenge: CraftConnectPairingChallenge): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeUtf8(DOMAIN)
                output.writeUtf8(challenge.serverId)
                output.writeLong(challenge.challengeId.mostSignificantBits)
                output.writeLong(challenge.challengeId.leastSignificantBits)
                output.writeLong(challenge.playerUuid.mostSignificantBits)
                output.writeLong(challenge.playerUuid.leastSignificantBits)
                output.writeUtf8(challenge.deviceId)
                require(challenge.nonce.size in 16..MAX_NONCE_BYTES) { "Invalid CraftConnect pairing nonce" }
                output.writeInt(challenge.nonce.size)
                output.write(challenge.nonce)
                output.writeLong(challenge.expiresAt.toEpochMilli())
            }
            buffer.toByteArray()
        }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "CraftConnect pairing field is too long" }
        writeInt(bytes.size)
        write(bytes)
    }
}

/**
 * Platform/storage-neutral pairing boundary.
 *
 * Implementations are responsible for TTL, replay protection, rate limits,
 * persistence, explicit player approval and audit. Calling code must not treat a
 * device ID alone as proof of possession of the paired key.
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
