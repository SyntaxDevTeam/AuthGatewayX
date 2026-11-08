package pl.syntaxdevteam.authgatewayx.security.proof

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Proof authorizes one administrative read, never Minecraft authentication or PRE_AUTH release. */
class StaffSessionProof(secret: String) {
    private val key = secret.toByteArray(Charsets.UTF_8).also { require(it.size >= 32) }
    fun challenge(playerId: UUID, expiresAt: Long): ByteArray {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return signed(ByteBuffer.allocate(58).put(1).put(0).putLong(playerId.mostSignificantBits)
            .putLong(playerId.leastSignificantBits).putLong(expiresAt).put(nonce).array())
    }
    fun respond(challenge: ByteArray, playerId: UUID, now: Long): ByteArray? {
        if (!valid(challenge, 0, playerId, now)) return null
        return signed(challenge.copyOf(58).also { it[1] = 1 })
    }
    fun verify(response: ByteArray, challenge: ByteArray, playerId: UUID, now: Long): Boolean =
        valid(response, 1, playerId, now) && challenge.size == 90 &&
            MessageDigest.isEqual(response.copyOfRange(2, 58), challenge.copyOfRange(2, 58))
    private fun valid(packet: ByteArray, type: Int, id: UUID, now: Long): Boolean {
        if (packet.size != 90 || packet[0] != 1.toByte() || packet[1] != type.toByte()) return false
        val fields = ByteBuffer.wrap(packet)
        fields.position(2)
        if (UUID(fields.long, fields.long) != id) return false
        val expiry = fields.long
        if (expiry <= now || expiry - now > 5000) return false
        return MessageDigest.isEqual(mac(packet.copyOf(58)), packet.copyOfRange(58, 90))
    }
    private fun signed(body: ByteArray) = body + mac(body)
    private fun mac(body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(body)
    }
    companion object { const val CHANNEL = "authgatewayx:staff_proof" }
}
