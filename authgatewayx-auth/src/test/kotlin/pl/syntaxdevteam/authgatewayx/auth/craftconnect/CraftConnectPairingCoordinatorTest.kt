package pl.syntaxdevteam.authgatewayx.auth.craftconnect

import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectPairingSignaturePayload
import pl.syntaxdevteam.authgatewayx.storage.CraftConnectPairingStorage
import pl.syntaxdevteam.authgatewayx.storage.CraftConnectPairingWrite
import pl.syntaxdevteam.authgatewayx.storage.StoredCraftConnectPairing
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CraftConnectPairingCoordinatorTest {
    @Test
    fun newDeviceRequiresApprovalButKnownKeyReconnectsWithoutAnotherApproval() {
        val now = Instant.parse("2026-10-02T20:00:00Z")
        val serverId = "server-one"
        val player = UUID.randomUUID()
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val device = CraftConnectDevice(deviceId(keyPair.public.encoded), keyPair.public.encoded)
        val storage = InMemoryPairingStorage()
        val challenges = CraftConnectPairingChallengeRegistry(serverId, clock = { now })
        val coordinator = CraftConnectPairingCoordinator(serverId, challenges, storage) { now }

        val first = challenges.begin(player, device)
        val firstResolution = coordinator.resolveProof(
            player,
            first.challengeId,
            sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(first)),
        ).toCompletableFuture().join()
        assertIs<CraftConnectPairingResolution.ApprovalRequired>(firstResolution)

        coordinator.approve(player, device).toCompletableFuture().join()
        val second = challenges.begin(player, device)
        val secondResolution = coordinator.resolveProof(
            player,
            second.challengeId,
            sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(second)),
        ).toCompletableFuture().join()
        assertIs<CraftConnectPairingResolution.Paired>(secondResolution)
    }

    @Test
    fun storedDeviceIdWithDifferentKeyIsRejected() {
        val now = Instant.parse("2026-10-02T20:00:00Z")
        val serverId = "server-one"
        val player = UUID.randomUUID()
        val original = keyPair()
        val replacement = keyPair()
        val replacementDevice = CraftConnectDevice(deviceId(replacement.public.encoded), replacement.public.encoded)
        val storage = InMemoryPairingStorage().apply {
            records += StoredCraftConnectPairing(
                serverId,
                player,
                replacementDevice.deviceId,
                original.public.encoded,
                now,
                now,
                null,
            )
        }
        val challenges = CraftConnectPairingChallengeRegistry(serverId, clock = { now })
        val coordinator = CraftConnectPairingCoordinator(serverId, challenges, storage) { now }
        val challenge = challenges.begin(player, replacementDevice)

        val resolution = coordinator.resolveProof(
            player,
            challenge.challengeId,
            sign(replacement.private, CraftConnectPairingSignaturePayload.encode(challenge)),
        ).toCompletableFuture().join()

        assertIs<CraftConnectPairingResolution.StoredKeyMismatch>(resolution)
    }

    private class InMemoryPairingStorage : CraftConnectPairingStorage {
        val records = mutableListOf<StoredCraftConnectPairing>()

        override fun findActiveCraftConnectPairing(serverId: String, playerUuid: UUID, deviceId: String) =
            CompletableFuture.completedFuture(
                records.firstOrNull { it.serverId == serverId && it.playerUuid == playerUuid && it.deviceId == deviceId && it.active },
            )

        override fun saveApprovedCraftConnectPairing(pairing: CraftConnectPairingWrite) =
            CompletableFuture.completedFuture(
                StoredCraftConnectPairing(
                    pairing.serverId,
                    pairing.playerUuid,
                    pairing.deviceId,
                    pairing.devicePublicKey.copyOf(),
                    pairing.approvedAt,
                    pairing.approvedAt,
                    null,
                ).also { record ->
                    records.removeAll { it.serverId == record.serverId && it.playerUuid == record.playerUuid && it.deviceId == record.deviceId }
                    records += record
                },
            )

        override fun touchCraftConnectPairing(serverId: String, playerUuid: UUID, deviceId: String, usedAt: Instant) =
            CompletableFuture.completedFuture(
                records.indexOfFirst { it.serverId == serverId && it.playerUuid == playerUuid && it.deviceId == deviceId && it.active }
                    .takeIf { it >= 0 }
                    ?.let { index -> records[index] = records[index].copy(lastUsedAt = usedAt); true }
                    ?: false,
            )

        override fun revokeCraftConnectPairing(serverId: String, playerUuid: UUID, deviceId: String, revokedAt: Instant) =
            CompletableFuture.completedFuture(false)

        override fun listCraftConnectPairings(serverId: String, playerUuid: UUID) =
            CompletableFuture.completedFuture(records.filter { it.serverId == serverId && it.playerUuid == playerUuid })
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun deviceId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return try {
            buildString(35) {
                append("cc-")
                for (index in 0 until 16) append("%02x".format(digest[index]))
            }
        } finally {
            digest.fill(0)
        }
    }

    private fun sign(privateKey: java.security.PrivateKey, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
}
