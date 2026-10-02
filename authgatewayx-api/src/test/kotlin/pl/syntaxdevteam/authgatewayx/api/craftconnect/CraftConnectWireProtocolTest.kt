package pl.syntaxdevteam.authgatewayx.api.craftconnect

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CraftConnectWireProtocolTest {
    @Test
    fun helloAndCapabilitiesRoundTrip() {
        val id = UUID.fromString("12345678-1234-5678-1234-567812345678")
        val frame = CraftConnectFrame(
            id,
            CraftConnectMessage.ServerHello(
                serverId = "moonvale-1",
                serverName = "MoonVale",
                supported = setOf(
                    CraftConnectCapability.STATUS,
                    CraftConnectCapability.CONSOLE_VIEW,
                    CraftConnectCapability.BRANDING,
                ),
            ),
        )

        assertEquals(frame, CraftConnectWireProtocol.decode(CraftConnectWireProtocol.encode(frame)))
    }

    @Test
    fun pairingChallengeRoundTripsBinaryNonceAndServerIdentity() {
        val nonce = ByteArray(32) { it.toByte() }
        val message = CraftConnectMessage.PairingChallenge(
            challengeId = UUID.randomUUID(),
            serverId = "moonvale-1",
            nonce = nonce,
            expiresAt = Instant.ofEpochMilli(1_800_000_000_000),
        )
        val decoded = CraftConnectWireProtocol.decode(
            CraftConnectWireProtocol.encode(CraftConnectFrame(UUID.randomUUID(), message)),
        ).message as CraftConnectMessage.PairingChallenge

        assertEquals(message.challengeId, decoded.challengeId)
        assertEquals(message.serverId, decoded.serverId)
        assertEquals(message.expiresAt, decoded.expiresAt)
        assertContentEquals(nonce, decoded.nonce)
    }

    @Test
    fun rejectsFramesWithWrongMagic() {
        val encoded = CraftConnectWireProtocol.encode(
            CraftConnectFrame(UUID.randomUUID(), CraftConnectMessage.ClientHello("0.1.0", null)),
        )
        encoded[0] = 0

        assertFailsWith<CraftConnectProtocolException> { CraftConnectWireProtocol.decode(encoded) }
    }

    @Test
    fun capabilityWireIdsAreStableAndIndependentFromEnumNames() {
        assertEquals("console.execute", CraftConnectCapability.CONSOLE_EXECUTE.wireId)
        assertTrue(CraftConnectCapability.fromWireId("future.capability") == null)
    }
}
