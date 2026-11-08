package pl.syntaxdevteam.authgatewayx.security.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Informational client notification, never an authorization token for server actions. */
object ClientAuthenticationProtocol {
    const val CHANNEL = "authgatewayx:auth"
    const val COMPATIBILITY_CHANNEL = "craftconnect:auth"
    val channels = setOf(CHANNEL, COMPATIBILITY_CHANNEL)

    fun subscription(nonce: String): ByteArray = encode("subscribe", nonce, "")

    fun readSubscription(payload: ByteArray): String? =
        decode(payload, "subscribe")?.takeIf { it.second.isEmpty() }?.first

    fun authenticated(nonce: String): ByteArray = encode("authenticated", nonce, "AuthGatewayX")

    fun confirms(payload: ByteArray, nonce: String): Boolean =
        decode(payload, "authenticated")?.let { it.first == nonce && it.second == "AuthGatewayX" } == true

    private fun encode(kind: String, nonce: String, provider: String): ByteArray {
        require(validNonce(nonce)) { "Invalid client connection nonce" }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(1)
                output.writeUTF(kind)
                output.writeUTF(nonce)
                output.writeUTF(provider)
            }
        }.toByteArray()
    }

    private fun decode(payload: ByteArray, kind: String): Pair<String, String>? {
        if (payload.size > 256) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readUnsignedByte() != 1 || input.readBoundedUtf() != kind) return null
                val nonce = input.readBoundedUtf()
                if (!validNonce(nonce)) return null
                val provider = input.readBoundedUtf()
                if (input.available() != 0) return null
                nonce to provider
            }
        }.getOrNull()
    }

    private fun validNonce(nonce: String): Boolean =
        nonce.length == 36 && runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false)

    private fun DataInputStream.readBoundedUtf(): String {
        mark(2)
        val length = readUnsignedShort()
        require(length <= 64 && length <= available()) { "Invalid client message field" }
        reset()
        return readUTF()
    }
}
