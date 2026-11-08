package pl.syntaxdevteam.authgatewayx.auth.craftconnect

import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectPairingSignaturePayload
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CraftConnectPairingChallengeRegistryTest {
    @Test
    fun validSignatureIsAcceptedOnlyOnce() {
        val now = Instant.parse("2026-10-02T20:00:00Z")
        val keyPair = keyPair()
        val registry = CraftConnectPairingChallengeRegistry(
            serverId = "server-one",
            clock = { now },
        )
        val player = UUID.randomUUID()
        val challenge = registry.begin(player, device(keyPair.public.encoded))
        val signature = sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(challenge))

        assertIs<CraftConnectPairingVerification.Verified>(registry.verify(player, challenge.challengeId, signature))
        assertEquals(
            CraftConnectPairingVerification.MissingOrConsumed,
            registry.verify(player, challenge.challengeId, signature),
        )
    }

    @Test
    fun wrongPlayerConsumesChallenge() {
        val keyPair = keyPair()
        val registry = CraftConnectPairingChallengeRegistry("server-one")
        val player = UUID.randomUUID()
        val challenge = registry.begin(player, device(keyPair.public.encoded))
        val signature = sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(challenge))

        assertEquals(
            CraftConnectPairingVerification.PlayerMismatch,
            registry.verify(UUID.randomUUID(), challenge.challengeId, signature),
        )
        assertEquals(
            CraftConnectPairingVerification.MissingOrConsumed,
            registry.verify(player, challenge.challengeId, signature),
        )
    }

    @Test
    fun expiredChallengeIsRejected() {
        var now = Instant.parse("2026-10-02T20:00:00Z")
        val keyPair = keyPair()
        val registry = CraftConnectPairingChallengeRegistry(
            serverId = "server-one",
            ttl = Duration.ofSeconds(30),
            clock = { now },
        )
        val player = UUID.randomUUID()
        val challenge = registry.begin(player, device(keyPair.public.encoded))
        val signature = sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(challenge))
        now = now.plusSeconds(31)

        assertEquals(
            CraftConnectPairingVerification.Expired,
            registry.verify(player, challenge.challengeId, signature),
        )
    }

    @Test
    fun newChallengeForPlayerInvalidatesPreviousOne() {
        val keyPair = keyPair()
        val registry = CraftConnectPairingChallengeRegistry("server-one")
        val player = UUID.randomUUID()
        val device = device(keyPair.public.encoded)
        val first = registry.begin(player, device)
        val second = registry.begin(player, device)

        assertEquals(1, registry.pendingCount())
        assertEquals(
            CraftConnectPairingVerification.MissingOrConsumed,
            registry.verify(player, first.challengeId, ByteArray(64)),
        )
        val signature = sign(keyPair.private, CraftConnectPairingSignaturePayload.encode(second))
        assertIs<CraftConnectPairingVerification.Verified>(registry.verify(player, second.challengeId, signature))
    }

    @Test
    fun rejectsDeviceIdThatDoesNotMatchPublicKeyFingerprint() {
        val keyPair = keyPair()
        val registry = CraftConnectPairingChallengeRegistry("server-one")

        assertFailsWith<IllegalArgumentException> {
            registry.begin(UUID.randomUUID(), CraftConnectDevice("cc-00000000000000000000000000000000", keyPair.public.encoded))
        }
    }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun device(publicKey: ByteArray): CraftConnectDevice = CraftConnectDevice(deviceId(publicKey), publicKey)

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
