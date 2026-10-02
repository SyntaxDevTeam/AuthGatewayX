package pl.syntaxdevteam.authgatewayx.auth.craftconnect

import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDeviceKeyAlgorithm
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectPairingChallenge
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectPairingSignaturePayload
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class CraftConnectPairingVerification {
    data class Verified(val device: CraftConnectDevice) : CraftConnectPairingVerification()
    data object MissingOrConsumed : CraftConnectPairingVerification()
    data object Expired : CraftConnectPairingVerification()
    data object PlayerMismatch : CraftConnectPairingVerification()
    data object InvalidSignature : CraftConnectPairingVerification()
}

/**
 * Bounded, in-memory proof-of-possession gate for the first stage of pairing.
 *
 * A successful signature only proves possession of the submitted device key. It
 * does NOT create a pairing record and does NOT replace explicit player approval.
 * Challenges are removed before signature verification, making every challenge
 * single-use even when verification fails.
 */
class CraftConnectPairingChallengeRegistry(
    private val serverId: String,
    private val ttl: Duration = Duration.ofMinutes(2),
    private val maximumPending: Int = 5_000,
    private val clock: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Pending(
        val challenge: CraftConnectPairingChallenge,
        val device: CraftConnectDevice,
    )

    private val byChallenge = ConcurrentHashMap<UUID, Pending>()
    private val byPlayer = ConcurrentHashMap<UUID, UUID>()
    private val mutationLock = Any()

    init {
        require(serverId.isNotBlank())
        require(!ttl.isZero && !ttl.isNegative)
        require(maximumPending in 1..100_000)
    }

    fun begin(playerUuid: UUID, device: CraftConnectDevice): CraftConnectPairingChallenge {
        validateDevice(device)
        val now = clock()
        val challenge = CraftConnectPairingChallenge(
            challengeId = UUID.randomUUID(),
            serverId = serverId,
            playerUuid = playerUuid,
            deviceId = device.deviceId,
            nonce = ByteArray(NONCE_BYTES).also(random::nextBytes),
            expiresAt = now.plus(ttl),
        )
        synchronized(mutationLock) {
            purgeExpired(now)
            val previous = byPlayer.remove(playerUuid)
            if (previous != null) byChallenge.remove(previous)
            if (byChallenge.size >= maximumPending) {
                throw IllegalStateException("CraftConnect pairing challenge capacity reached")
            }
            byChallenge[challenge.challengeId] = Pending(challenge, device.copy(publicKey = device.publicKey.copyOf()))
            byPlayer[playerUuid] = challenge.challengeId
        }
        return challenge.copy(nonce = challenge.nonce.copyOf())
    }

    fun verify(
        playerUuid: UUID,
        challengeId: UUID,
        signedChallenge: ByteArray,
    ): CraftConnectPairingVerification {
        if (signedChallenge.isEmpty() || signedChallenge.size > MAX_SIGNATURE_BYTES) {
            consume(challengeId)
            return CraftConnectPairingVerification.InvalidSignature
        }
        val pending = consume(challengeId) ?: return CraftConnectPairingVerification.MissingOrConsumed
        val challenge = pending.challenge
        if (challenge.playerUuid != playerUuid) return CraftConnectPairingVerification.PlayerMismatch
        if (!challenge.expiresAt.isAfter(clock())) return CraftConnectPairingVerification.Expired

        val valid = runCatching {
            val key = decodeP256PublicKey(pending.device)
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(CraftConnectPairingSignaturePayload.encode(challenge))
                verify(signedChallenge)
            }
        }.getOrDefault(false)

        return if (valid) {
            CraftConnectPairingVerification.Verified(
                pending.device.copy(publicKey = pending.device.publicKey.copyOf()),
            )
        } else {
            CraftConnectPairingVerification.InvalidSignature
        }
    }

    fun pendingCount(): Int = byChallenge.size

    private fun consume(challengeId: UUID): Pending? = synchronized(mutationLock) {
        val pending = byChallenge.remove(challengeId) ?: return@synchronized null
        byPlayer.remove(pending.challenge.playerUuid, challengeId)
        pending
    }

    private fun purgeExpired(now: Instant) {
        byChallenge.entries.removeIf { (challengeId, pending) ->
            if (!pending.challenge.expiresAt.isAfter(now)) {
                byPlayer.remove(pending.challenge.playerUuid, challengeId)
                true
            } else {
                false
            }
        }
    }

    private fun validateDevice(device: CraftConnectDevice) {
        require(device.deviceId.length in 8..128) { "Invalid CraftConnect device id" }
        require(device.deviceId.all { it.isLetterOrDigit() || it in "-_.:" }) { "Invalid CraftConnect device id" }
        require(device.publicKey.size in 64..4096) { "Invalid CraftConnect public key size" }
        require(device.algorithm == CraftConnectDeviceKeyAlgorithm.EC_P256_SHA256) { "Unsupported CraftConnect key algorithm" }
        decodeP256PublicKey(device)
    }

    private fun decodeP256PublicKey(device: CraftConnectDevice): ECPublicKey {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(device.publicKey)) as? ECPublicKey
            ?: throw IllegalArgumentException("CraftConnect public key is not EC")
        require(key.params.curve.field.fieldSize == 256) { "CraftConnect device key must use a 256-bit EC curve" }
        require(key.params.order.bitLength() == 256) { "CraftConnect device key must use P-256" }
        return key
    }

    private companion object {
        const val NONCE_BYTES = 32
        const val MAX_SIGNATURE_BYTES = 1024
    }
}
