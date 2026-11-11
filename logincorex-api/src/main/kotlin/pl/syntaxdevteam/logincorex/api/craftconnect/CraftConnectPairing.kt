@file:Suppress("unused")

package pl.syntaxdevteam.logincorex.api.craftconnect

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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CraftConnectDevice

        if (deviceId != other.deviceId) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (algorithm != other.algorithm) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        return result
    }
}

data class CraftConnectPairingChallenge(
    val challengeId: UUID,
    val serverId: String,
    val playerUuid: UUID,
    val deviceId: String,
    val nonce: ByteArray,
    val expiresAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CraftConnectPairingChallenge

        if (challengeId != other.challengeId) return false
        if (serverId != other.serverId) return false
        if (playerUuid != other.playerUuid) return false
        if (deviceId != other.deviceId) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (expiresAt != other.expiresAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = challengeId.hashCode()
        result = 31 * result + serverId.hashCode()
        result = 31 * result + playerUuid.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CraftConnectPairingRecord

        if (serverId != other.serverId) return false
        if (playerUuid != other.playerUuid) return false
        if (deviceId != other.deviceId) return false
        if (!devicePublicKey.contentEquals(other.devicePublicKey)) return false
        if (createdAt != other.createdAt) return false
        if (lastUsedAt != other.lastUsedAt) return false
        if (revokedAt != other.revokedAt) return false
        if (active != other.active) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + playerUuid.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + devicePublicKey.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastUsedAt.hashCode()
        result = 31 * result + revokedAt.hashCode()
        result = 31 * result + active.hashCode()
        return result
    }
}

/** Canonical, domain-separated bytes signed by the device for protocol v1 pairing. */
object CraftConnectPairingSignaturePayload {
    // Frozen v1 signature domain; changing branding must not invalidate client signatures.
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
