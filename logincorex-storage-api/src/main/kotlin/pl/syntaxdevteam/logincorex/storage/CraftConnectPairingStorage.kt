package pl.syntaxdevteam.logincorex.storage

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

data class StoredCraftConnectPairing(
    val serverId: String,
    val playerUuid: UUID,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val revokedAt: Instant?,
) {
    val active: Boolean
        get() = revokedAt == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StoredCraftConnectPairing

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

data class CraftConnectPairingWrite(
    val serverId: String,
    val playerUuid: UUID,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val approvedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CraftConnectPairingWrite

        if (serverId != other.serverId) return false
        if (playerUuid != other.playerUuid) return false
        if (deviceId != other.deviceId) return false
        if (!devicePublicKey.contentEquals(other.devicePublicKey)) return false
        if (approvedAt != other.approvedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + playerUuid.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + devicePublicKey.contentHashCode()
        result = 31 * result + approvedAt.hashCode()
        return result
    }
}

/**
 * Persistent device-pairing store shared by SQLite/MySQL/MariaDB/PostgreSQL backends.
 * Implementations must execute blocking database work on the existing bounded
 * storage executor and must never expose private device key material.
 */
interface CraftConnectPairingStorage {
    fun findActiveCraftConnectPairing(
        serverId: String,
        playerUuid: UUID,
        deviceId: String,
    ): CompletionStage<StoredCraftConnectPairing?>

    fun saveApprovedCraftConnectPairing(
        pairing: CraftConnectPairingWrite,
    ): CompletionStage<StoredCraftConnectPairing>

    fun touchCraftConnectPairing(
        serverId: String,
        playerUuid: UUID,
        deviceId: String,
        usedAt: Instant,
    ): CompletionStage<Boolean>

    fun revokeCraftConnectPairing(
        serverId: String,
        playerUuid: UUID,
        deviceId: String,
        revokedAt: Instant,
    ): CompletionStage<Boolean>

    fun listCraftConnectPairings(
        serverId: String,
        playerUuid: UUID,
    ): CompletionStage<List<StoredCraftConnectPairing>>
}
