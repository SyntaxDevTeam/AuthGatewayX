package pl.syntaxdevteam.authgatewayx.paper.premium

import io.papermc.paper.adventure.PaperAdventure
import net.kyori.adventure.text.Component
import net.minecraft.network.Connection
import net.minecraft.network.protocol.login.ClientboundHelloPacket
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookupResult
import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginCheapGuard
import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginGuardDecision
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Selects vanilla online authentication per connection while the server remains in offline mode. */
internal class StandalonePremiumLoginListener(
    private val minecraftServer: MinecraftServer,
    connection: Connection,
    transferred: Boolean,
    private val premiumLookup: MojangProfileIdentityLookup,
    private val cheapGuard: PaperLoginCheapGuard,
    private val capacity: PremiumHandshakeCapacity,
    private val unavailableMessage: Component,
    private val rateLimitedMessage: Component,
    private val overloadedMessage: Component,
    private val invalidSessionMessage: Component,
    private val onFailure: Consumer<Throwable>,
) : ServerLoginPacketListenerImpl(minecraftServer, connection, transferred) {
    private val lookupStarted = AtomicBoolean()
    private val capacityHeld = AtomicBoolean()
    private val premiumAuthenticationAttempted = AtomicBoolean()

    override fun handleHello(packet: ServerboundHelloPacket) {
        if (!lookupStarted.compareAndSet(false, true)) {
            disconnect(PaperAdventure.asVanilla(unavailableMessage))
            return
        }
        val sourceAddress = (connection.channel.remoteAddress() as? InetSocketAddress)?.address
        if (sourceAddress == null) {
            disconnect(PaperAdventure.asVanilla(unavailableMessage))
            return
        }
        val guardResult = cheapGuard.evaluateProtocol(sourceAddress, packet.name())
        if (guardResult.decision != PaperLoginGuardDecision.ALLOW) {
            val message = if (guardResult.decision == PaperLoginGuardDecision.DENY_INVALID_USERNAME) {
                unavailableMessage
            } else {
                rateLimitedMessage
            }
            disconnect(PaperAdventure.asVanilla(message))
            return
        }
        val username = guardResult.username ?: run {
            disconnect(PaperAdventure.asVanilla(unavailableMessage))
            return
        }
        premiumLookup.lookupProfile(username).whenComplete { result, failure ->
            connection.channel.eventLoop().execute {
                if (!connection.isConnected || connection.packetListener !== this) return@execute
                if (failure != null || result == null || result == MojangProfileLookupResult.Unavailable) {
                    failure?.let(onFailure::accept)
                    disconnect(PaperAdventure.asVanilla(unavailableMessage))
                    return@execute
                }
                when (result) {
                    MojangProfileLookupResult.NotPremium -> super.handleHello(packet)
                    is MojangProfileLookupResult.Premium -> beginPremiumAuthentication(packet)
                    MojangProfileLookupResult.Unavailable -> disconnect(PaperAdventure.asVanilla(unavailableMessage))
                }
            }
        }
    }

    private fun beginPremiumAuthentication(packet: ServerboundHelloPacket) {
        premiumAuthenticationAttempted.set(true)
        if (!capacity.tryAcquire()) {
            disconnect(PaperAdventure.asVanilla(overloadedMessage))
            return
        }
        capacityHeld.set(true)
        requestedUuid = packet.profileId()
        requestedUsername = packet.name()
        state = State.KEY
        connection.send(
            ClientboundHelloPacket(
                "",
                minecraftServer.keyPair.public.encoded,
                challengeBytes(),
                true,
            ),
        )
    }

    override fun tick() {
        super.tick()
        if (state != State.KEY && state != State.AUTHENTICATING) releaseCapacity()
        if (!connection.isConnected) releaseCapacity()
    }

    override fun onDisconnect(details: net.minecraft.network.DisconnectionDetails) {
        releaseCapacity()
        super.onDisconnect(details)
    }

    private fun releaseCapacity() {
        if (capacityHeld.compareAndSet(true, false)) capacity.release()
    }

    internal fun rewriteDisconnect(packet: ClientboundLoginDisconnectPacket): ClientboundLoginDisconnectPacket {
        if (!premiumAuthenticationAttempted.get()) return packet
        val reason = replaceInvalidPremiumSessionReason(packet.reason(), invalidSessionMessage)
        return if (reason === packet.reason()) packet else ClientboundLoginDisconnectPacket(reason)
    }

    private fun challengeBytes(): ByteArray = CHALLENGE_FIELD.get(this) as ByteArray

    private companion object {
        val CHALLENGE_FIELD = ServerLoginPacketListenerImpl::class.java.declaredFields
            .single { it.type == ByteArray::class.java }
            .also { it.isAccessible = true }
    }
}

internal fun replaceInvalidPremiumSessionReason(
    original: net.minecraft.network.chat.Component,
    replacement: Component,
): net.minecraft.network.chat.Component {
    val translation = original.contents as? net.minecraft.network.chat.contents.TranslatableContents
    return if (translation?.key == "multiplayer.disconnect.unverified_username") {
        PaperAdventure.asVanilla(replacement)
    } else {
        original
    }
}
