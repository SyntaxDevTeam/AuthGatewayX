package pl.syntaxdevteam.authgatewayx.api.craftconnect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

/** Correlation envelope for one AuthGatewayX <-> CraftConnect custom-payload frame. */
data class CraftConnectFrame(
    val requestId: UUID,
    val message: CraftConnectMessage,
)

sealed interface CraftConnectMessage {
    data class ClientHello(
        val appVersion: String,
        val deviceId: String?,
    ) : CraftConnectMessage

    data class ServerHello(
        val serverId: String,
        val serverName: String,
        val supported: Set<CraftConnectCapability>,
    ) : CraftConnectMessage

    data class Capabilities(
        val granted: Set<CraftConnectCapability>,
    ) : CraftConnectMessage

    data class PairingBegin(
        val deviceId: String,
        val publicKey: ByteArray,
    ) : CraftConnectMessage

    data class PairingChallenge(
        val challengeId: UUID,
        val serverId: String,
        val nonce: ByteArray,
        val expiresAt: Instant,
    ) : CraftConnectMessage

    data class PairingConfirm(
        val challengeId: UUID,
        val signature: ByteArray,
    ) : CraftConnectMessage

    data class PairingResult(
        val paired: Boolean,
        val errorCode: String? = null,
    ) : CraftConnectMessage

    data class Error(
        val code: String,
    ) : CraftConnectMessage
}

class CraftConnectProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Dependency-free binary codec for protocol version 1.
 *
 * Frame layout:
 * magic:int32 | version:u8 | type:u8 | request UUID:128-bit | typed payload
 *
 * The Minecraft custom-payload packet supplies the outer frame length. Every
 * variable field is still length-bounded here to protect the server from
 * oversized or malformed client payloads.
 */
object CraftConnectWireProtocol {
    private const val MAGIC = 0x41475843 // "AGXC"

    private const val CLIENT_HELLO = 1
    private const val SERVER_HELLO = 2
    private const val CAPABILITIES = 3
    private const val PAIRING_BEGIN = 10
    private const val PAIRING_CHALLENGE = 11
    private const val PAIRING_CONFIRM = 12
    private const val PAIRING_RESULT = 13
    private const val ERROR = 127

    private const val MAX_STRING_BYTES = 4 * 1024
    private const val MAX_BINARY_BYTES = 16 * 1024
    private const val MAX_CAPABILITIES = 64

    fun encode(frame: CraftConnectFrame): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(CraftConnectProtocol.VERSION)
            output.writeByte(typeOf(frame.message))
            output.writeLong(frame.requestId.mostSignificantBits)
            output.writeLong(frame.requestId.leastSignificantBits)
            when (val message = frame.message) {
                is CraftConnectMessage.ClientHello -> {
                    output.writeString(message.appVersion)
                    output.writeNullableString(message.deviceId)
                }
                is CraftConnectMessage.ServerHello -> {
                    output.writeString(message.serverId)
                    output.writeString(message.serverName)
                    output.writeCapabilities(message.supported)
                }
                is CraftConnectMessage.Capabilities -> output.writeCapabilities(message.granted)
                is CraftConnectMessage.PairingBegin -> {
                    output.writeString(message.deviceId)
                    output.writeBytes(message.publicKey)
                }
                is CraftConnectMessage.PairingChallenge -> {
                    output.writeUuid(message.challengeId)
                    output.writeString(message.serverId)
                    output.writeBytes(message.nonce)
                    output.writeLong(message.expiresAt.toEpochMilli())
                }
                is CraftConnectMessage.PairingConfirm -> {
                    output.writeUuid(message.challengeId)
                    output.writeBytes(message.signature)
                }
                is CraftConnectMessage.PairingResult -> {
                    output.writeBoolean(message.paired)
                    output.writeNullableString(message.errorCode)
                }
                is CraftConnectMessage.Error -> output.writeString(message.code)
            }
        }
        return buffer.toByteArray().also {
            if (it.size > CraftConnectProtocol.MAX_FRAME_BYTES) {
                throw CraftConnectProtocolException("CraftConnect frame exceeds maximum size")
            }
        }
    }

    fun decode(bytes: ByteArray): CraftConnectFrame {
        if (bytes.size > CraftConnectProtocol.MAX_FRAME_BYTES) {
            throw CraftConnectProtocolException("CraftConnect frame exceeds maximum size")
        }
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) throw CraftConnectProtocolException("Invalid CraftConnect frame magic")
                val version = input.readUnsignedByte()
                if (version != CraftConnectProtocol.VERSION) {
                    throw CraftConnectProtocolException("Unsupported CraftConnect protocol version: $version")
                }
                val type = input.readUnsignedByte()
                val requestId = input.readUuid()
                val message = when (type) {
                    CLIENT_HELLO -> CraftConnectMessage.ClientHello(input.readString(), input.readNullableString())
                    SERVER_HELLO -> CraftConnectMessage.ServerHello(
                        input.readString(),
                        input.readString(),
                        input.readCapabilities(),
                    )
                    CAPABILITIES -> CraftConnectMessage.Capabilities(input.readCapabilities())
                    PAIRING_BEGIN -> CraftConnectMessage.PairingBegin(input.readString(), input.readBytes())
                    PAIRING_CHALLENGE -> CraftConnectMessage.PairingChallenge(
                        input.readUuid(),
                        input.readString(),
                        input.readBytes(),
                        Instant.ofEpochMilli(input.readLong()),
                    )
                    PAIRING_CONFIRM -> CraftConnectMessage.PairingConfirm(input.readUuid(), input.readBytes())
                    PAIRING_RESULT -> CraftConnectMessage.PairingResult(input.readBoolean(), input.readNullableString())
                    ERROR -> CraftConnectMessage.Error(input.readString())
                    else -> throw CraftConnectProtocolException("Unknown CraftConnect message type: $type")
                }
                if (input.available() != 0) throw CraftConnectProtocolException("Trailing CraftConnect frame data")
                CraftConnectFrame(requestId, message)
            }
        } catch (failure: CraftConnectProtocolException) {
            throw failure
        } catch (failure: Exception) {
            throw CraftConnectProtocolException("Malformed CraftConnect frame", failure)
        }
    }

    private fun typeOf(message: CraftConnectMessage): Int = when (message) {
        is CraftConnectMessage.ClientHello -> CLIENT_HELLO
        is CraftConnectMessage.ServerHello -> SERVER_HELLO
        is CraftConnectMessage.Capabilities -> CAPABILITIES
        is CraftConnectMessage.PairingBegin -> PAIRING_BEGIN
        is CraftConnectMessage.PairingChallenge -> PAIRING_CHALLENGE
        is CraftConnectMessage.PairingConfirm -> PAIRING_CONFIRM
        is CraftConnectMessage.PairingResult -> PAIRING_RESULT
        is CraftConnectMessage.Error -> ERROR
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) throw CraftConnectProtocolException("CraftConnect string is too long")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) throw CraftConnectProtocolException("Invalid CraftConnect string size")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeBytes(value: ByteArray) {
        if (value.size > MAX_BINARY_BYTES) throw CraftConnectProtocolException("CraftConnect binary field is too long")
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readBytes(): ByteArray {
        val size = readInt()
        if (size !in 0..MAX_BINARY_BYTES) throw CraftConnectProtocolException("Invalid CraftConnect binary size")
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes
    }

    private fun DataOutputStream.writeUuid(value: UUID) {
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

    private fun DataOutputStream.writeCapabilities(capabilities: Set<CraftConnectCapability>) {
        if (capabilities.size > MAX_CAPABILITIES) throw CraftConnectProtocolException("Too many CraftConnect capabilities")
        writeInt(capabilities.size)
        capabilities.sortedBy(CraftConnectCapability::wireId).forEach { writeString(it.wireId) }
    }

    private fun DataInputStream.readCapabilities(): Set<CraftConnectCapability> {
        val count = readInt()
        if (count !in 0..MAX_CAPABILITIES) throw CraftConnectProtocolException("Invalid CraftConnect capability count")
        return buildSet {
            repeat(count) {
                val capability = CraftConnectCapability.fromWireId(readString())
                if (capability != null) add(capability)
            }
        }
    }
}
