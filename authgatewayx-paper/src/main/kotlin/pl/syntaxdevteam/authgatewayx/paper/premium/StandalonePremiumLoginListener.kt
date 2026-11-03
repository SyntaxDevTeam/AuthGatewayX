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
import pl.syntaxdevteam.authgatewayx.paper.premium.PremiumHandshakeDiagnostics.Event
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
    private val sessionWorkers: java.util.concurrent.ExecutorService,
    private val unavailableMessage: Component,
    private val rateLimitedMessage: Component,
    private val overloadedMessage: Component,
    private val invalidSessionMessage: Component,
    private val diagnostics: PremiumHandshakeDiagnostics.Trace?,
    private val onFailure: Consumer<Throwable>,
) : ServerLoginPacketListenerImpl(minecraftServer, connection, transferred) {
    private val lookupStarted = AtomicBoolean()
    private val capacityHeld = AtomicBoolean()
    private val premiumAuthenticationAttempted = AtomicBoolean()

    private var previousState: State? = null

    override fun handleHello(packet: ServerboundHelloPacket) {
        if (!lookupStarted.compareAndSet(false, true)) {
            diagnostics?.record(Event.INVALID_HELLO)
            disconnect(PaperAdventure.asVanilla(unavailableMessage))
            return
        }
        diagnostics?.record(Event.START)
        val sourceAddress = (connection.channel.remoteAddress() as? InetSocketAddress)?.address
        if (sourceAddress == null) {
            diagnostics?.record(Event.INVALID_HELLO)
            disconnect(PaperAdventure.asVanilla(unavailableMessage))
            return
        }
        val guardResult = cheapGuard.evaluateProtocol(sourceAddress, packet.name())
        if (guardResult.decision != PaperLoginGuardDecision.ALLOW) {
            diagnostics?.record(Event.GUARD_DENIED, guard = guardResult.decision)
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
        diagnostics?.record(Event.LOOKUP_START)
        premiumLookup.lookupProfile(username).whenComplete { result, failure ->
            connection.channel.eventLoop().execute {
                if (!connection.isConnected || connection.packetListener !== this) {
                    diagnostics?.record(Event.LATE_LOOKUP)
                    return@execute
                }
                if (failure != null || result == null || result == MojangProfileLookupResult.Unavailable) {
                    diagnostics?.record(Event.LOOKUP_UNAVAILABLE)
                    failure?.let(onFailure::accept)
                    disconnect(PaperAdventure.asVanilla(unavailableMessage))
                    return@execute
                }
                when (result) {
                    MojangProfileLookupResult.NotPremium -> {
                        diagnostics?.record(Event.LOOKUP_OFFLINE, terminalEvent = true)
                        super.handleHello(packet)
                    }
                    is MojangProfileLookupResult.Premium -> beginPremiumAuthentication(packet)
                    MojangProfileLookupResult.Unavailable -> disconnect(PaperAdventure.asVanilla(unavailableMessage))
                }
            }
        }
    }

    private fun beginPremiumAuthentication(packet: ServerboundHelloPacket) {
        diagnostics?.record(Event.LOOKUP_PREMIUM)
        premiumAuthenticationAttempted.set(true)
        if (!capacity.tryAcquire()) {
            diagnostics?.record(Event.CAPACITY_DENIED)
            disconnect(PaperAdventure.asVanilla(overloadedMessage))
            return
        }
        capacityHeld.set(true)
        diagnostics?.record(Event.SLOT_ACQUIRED)
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

    override fun handleKey(packet: net.minecraft.network.protocol.login.ServerboundKeyPacket) {
        check(state == State.KEY && premiumAuthenticationAttempted.get()) { "Unexpected key packet" }
        val digest: String
        try {
            val privateKey = minecraftServer.keyPair.private
            check(packet.isChallengeValid(challengeBytes(), privateKey)) { "Protocol error" }
            val secret = packet.getSecretKey(privateKey)
            digest = java.math.BigInteger(net.minecraft.util.Crypt.digestData("", minecraftServer.keyPair.public, secret)).toString(16)
            state = State.AUTHENTICATING
            connection.setEncryptionKey(secret)
        } catch (failure: net.minecraft.util.CryptException) {
            throw IllegalStateException("Protocol error", failure)
        }
        val started = System.nanoTime()
        val finished = AtomicBoolean()
        val deadlineRef = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val taskRef = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<*>?>()
        val closeListener = io.netty.channel.ChannelFutureListener {
            finished.set(true)
            deadlineRef.getAndSet(null)?.cancel(false)
            taskRef.getAndSet(null)?.cancel(true)
        }
        fun cleanup() {
            deadlineRef.getAndSet(null)?.cancel(false)
            connection.channel.closeFuture().removeListener(closeListener)
            taskRef.set(null)
        }
        val task = java.util.concurrent.FutureTask<Unit> {
            fun active() = !finished.get() && connection.isConnected &&
                connection.packetListener === this && state == State.AUTHENTICATING && !sessionWorkers.isShutdown
            fun fail(reason: Component) {
                connection.channel.eventLoop().execute {
                    if (finished.compareAndSet(false, true)) {
                        cleanup()
                        if (connection.isConnected && connection.packetListener === this && state == State.AUTHENTICATING) {
                            disconnect(PaperAdventure.asVanilla(reason))
                        }
                    }
                }
            }
            try {
                val address = if (minecraftServer.preventProxyConnections) {
                    (connection.remoteAddress as? InetSocketAddress)?.address
                } else null
                val result = PremiumSessionVerification().verify(started, ::active,
                    onRetry = { diagnostics?.record(Event.SESSION_RETRY) },
                ) {
                    minecraftServer.services().sessionService().hasJoinedServer(requestedUsername!!, digest, address)
                }
                if (result == null) {
                    diagnostics?.record(Event.NATIVE_DENIED)
                    fail(invalidSessionMessage)
                } else {
                    // Keep Paper's async/sync pre-login events and profile transformations.
                    // Never replay these events when retrying a service request.
                    if (active()) {
                        val profile = PRE_LOGIN_EVENTS.invoke(this, result.profile()) as com.mojang.authlib.GameProfile
                        connection.channel.eventLoop().execute {
                            if (active() && System.nanoTime() - started < PremiumSessionVerification.TIMEOUT_NANOS &&
                                finished.compareAndSet(false, true)) {
                                cleanup()
                                minecraftServer.serverActivityMonitor.reportLoginActivity()
                                authenticatedProfile = profile
                                state = State.VERIFYING
                            }
                        }
                    }
                }
            } catch (_: java.util.concurrent.CancellationException) {
                // Disconnect, deadline or shutdown owns the terminal result.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // Includes exhausted service failures, deadline and pre-login errors: fail closed.
                fail(unavailableMessage)
            }
        }
        taskRef.set(task)
        val deadline = connection.channel.eventLoop().schedule({
            if (finished.compareAndSet(false, true)) {
                diagnostics?.record(Event.TIMEOUT)
                task.cancel(true)
                cleanup()
                if (connection.isConnected && connection.packetListener === this && state == State.AUTHENTICATING) {
                    disconnect(PaperAdventure.asVanilla(unavailableMessage))
                }
            }
        }, PremiumSessionVerification.TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        deadlineRef.set(deadline)
        connection.channel.closeFuture().addListener(closeListener)
        try {
            sessionWorkers.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            finished.set(true)
            cleanup()
            task.cancel(false)
            disconnect(PaperAdventure.asVanilla(overloadedMessage))
        }
    }

    override fun tick() {
        super.tick()
        if (state != previousState) {
            // Only an enum transition is counted, never arbitrary packet or exception content.
            diagnostics?.record(Event.NATIVE_STATE, state = PremiumHandshakeDiagnostics.NativeState.valueOf(state.name))
            previousState = state
        }
        if (capacityHeld.get() && state != State.KEY && state != State.AUTHENTICATING) {
            releaseCapacity()
            diagnostics?.record(Event.NATIVE_VERIFIED, terminalEvent = true)
        }
        if (!connection.isConnected) releaseCapacity()
    }

    override fun onDisconnect(details: net.minecraft.network.DisconnectionDetails) {
        releaseCapacity()
        diagnostics?.record(Event.DISCONNECTED, terminalEvent = true)
        super.onDisconnect(details)
    }

    private fun releaseCapacity() {
        if (capacityHeld.compareAndSet(true, false)) {
            capacity.release()
            diagnostics?.record(Event.SLOT_RELEASED)
        }
    }

    internal fun rewriteDisconnect(packet: ClientboundLoginDisconnectPacket): ClientboundLoginDisconnectPacket {
        val key = (packet.reason().contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key
        if (key == "multiplayer.disconnect.slow_login") diagnostics?.record(Event.TIMEOUT)
        if (!premiumAuthenticationAttempted.get()) return packet
        if (key == "multiplayer.disconnect.unverified_username") diagnostics?.record(Event.NATIVE_DENIED)
        val reason = replaceInvalidPremiumSessionReason(packet.reason(), invalidSessionMessage)
        return if (reason === packet.reason()) packet else ClientboundLoginDisconnectPacket(reason)
    }

    private fun challengeBytes(): ByteArray = CHALLENGE_FIELD.get(this) as ByteArray

    internal companion object {
        fun verifyNativeCompatibility() {
            check(PRE_LOGIN_EVENTS.returnType == com.mojang.authlib.GameProfile::class.java)
            check(CHALLENGE_FIELD.type == ByteArray::class.java)
        }
        // Resolve eagerly: incompatible Paper builds must fail initialization, never skip events.
        val PRE_LOGIN_EVENTS = ServerLoginPacketListenerImpl::class.java
            .getDeclaredMethod("callPlayerPreLoginEvents", com.mojang.authlib.GameProfile::class.java)
            .also { it.isAccessible = true }
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
