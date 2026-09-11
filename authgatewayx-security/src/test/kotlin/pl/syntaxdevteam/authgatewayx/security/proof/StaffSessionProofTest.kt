package pl.syntaxdevteam.authgatewayx.security.proof

import java.util.UUID
import kotlin.test.*

class StaffSessionProofTest {
    private val codec = StaffSessionProof("a".repeat(32))
    private val id = UUID.randomUUID()
    @Test fun `signed challenge is bound to uuid nonce expiry and direction`() {
        val challenge = codec.challenge(id, 5000)
        val response = codec.respond(challenge, id, 1)!!
        assertTrue(codec.verify(response, challenge, id, 2))
        assertFalse(codec.verify(response, codec.challenge(id, 5000), id, 2))
        assertFalse(codec.verify(response, challenge, UUID.randomUUID(), 2))
        assertFalse(codec.verify(response, challenge, id, 5000))
        assertFalse(codec.verify(challenge, challenge, id, 2))
        assertNull(codec.respond(response, id, 2))
        assertNull(codec.respond(challenge, id, -1))
    }
    @Test fun `tampering truncation and wrong secret are rejected`() {
        val challenge = codec.challenge(id, 5000)
        assertNull(StaffSessionProof("b".repeat(32)).respond(challenge, id, 1))
        for (index in challenge.indices) {
            val modified = challenge.copyOf(); modified[index] = (modified[index].toInt() xor 1).toByte()
            assertNull(codec.respond(modified, id, 1))
        }
        assertNull(codec.respond(challenge.copyOf(89), id, 1))
        assertNull(codec.respond(challenge + byteArrayOf(0), id, 1))
        assertFailsWith<IllegalArgumentException> { StaffSessionProof("short") }
    }
}
