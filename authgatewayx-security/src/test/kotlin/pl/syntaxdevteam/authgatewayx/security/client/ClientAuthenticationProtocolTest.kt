package pl.syntaxdevteam.authgatewayx.security.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientAuthenticationProtocolTest {
    @Test fun `subscription and proof match existing CraftConnect wire format`() {
        val nonce = UUID.randomUUID().toString()
        assertEquals(nonce, ClientAuthenticationProtocol.readSubscription(ClientAuthenticationProtocol.subscription(nonce)))
        val expected = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeByte(1); it.writeUTF("authenticated"); it.writeUTF(nonce); it.writeUTF("AuthGatewayX")
            }
        }.toByteArray()
        assertTrue(expected.contentEquals(ClientAuthenticationProtocol.authenticated(nonce)))
        assertTrue(ClientAuthenticationProtocol.confirms(expected, nonce))
        assertFalse(ClientAuthenticationProtocol.confirms(expected, UUID.randomUUID().toString()))
        assertNull(ClientAuthenticationProtocol.readSubscription(expected))
        assertFalse(ClientAuthenticationProtocol.confirms(ClientAuthenticationProtocol.subscription(nonce), nonce))
    }

    @Test fun `malformed oversized truncated and unknown version messages cannot confirm`() {
        val nonce = UUID.randomUUID().toString()
        val proof = ClientAuthenticationProtocol.authenticated(nonce)
        for (payload in listOf(byteArrayOf(), ByteArray(257), proof.copyOf(proof.size - 1),
            proof + byteArrayOf(0), proof.copyOf().also { it[0] = 2 }, byteArrayOf(1, -1, -1))) {
            assertNull(ClientAuthenticationProtocol.readSubscription(payload))
            assertFalse(ClientAuthenticationProtocol.confirms(payload, nonce))
        }
    }
}
