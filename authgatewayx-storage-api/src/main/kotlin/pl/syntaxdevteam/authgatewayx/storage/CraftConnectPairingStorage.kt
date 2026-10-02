package pl.syntaxdevteam.authgatewayx.storage

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
}

data class CraftConnectPairingWrite(
    val serverId: String,
    val playerUuid: UUID,
    val deviceId: String,
    val devicePublicKey: ByteArray,
    val approvedAt: Instant,
)

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
