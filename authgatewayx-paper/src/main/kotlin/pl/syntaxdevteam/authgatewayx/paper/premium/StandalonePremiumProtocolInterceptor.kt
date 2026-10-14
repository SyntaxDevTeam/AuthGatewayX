package pl.syntaxdevteam.authgatewayx.paper.premium

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPromise
import net.kyori.adventure.text.Component
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.authgatewayx.paper.security.PaperLoginCheapGuard
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import pl.syntaxdevteam.authgatewayx.paper.lifecycle.listeningChannels

internal class StandalonePremiumProtocolInterceptor(
    private val server: MinecraftServer,
    private val premiumLookup: MojangProfileIdentityLookup,
    private val cheapGuard: PaperLoginCheapGuard,
    maximumConcurrentHandshakes: Int,
    private val unavailableMessage: Component,
    private val rateLimitedMessage: Component,
    private val overloadedMessage: Component,
    private val invalidSessionMessage: Component,
    diagnosticsEnabled: Boolean,
    diagnosticsMaximumLinesPerMinute: Int,
    diagnosticSink: (String) -> Unit,
    private val onFailure: Consumer<Throwable>,
) : AutoCloseable {
    private val capacity = PremiumHandshakeCapacity(maximumConcurrentHandshakes)
    private val diagnostics = PremiumHandshakeDiagnostics(
        diagnosticsEnabled, diagnosticsMaximumLinesPerMinute, capacity::active,
        maximumConcurrentHandshakes, diagnosticSink,
    )
    private val parentHandlerName = "authgatewayx-standalone-premium-acceptor"
    private val childBootstrapHandlerName = "authgatewayx-standalone-premium-bootstrap"
    private val childHandlerName = "authgatewayx-standalone-premium-login"
    private val closed = AtomicBoolean(false)
    private val ownedHandlers = ConcurrentHashMap<Channel, MutableMap<String, io.netty.channel.ChannelHandler>>()
    private val installedParents = CopyOnWriteArrayList<Channel>()
    private val packetListenerFields: List<Field> = Connection::class.java.declaredFields
        .filter { PacketListener::class.java.isAssignableFrom(it.type) }
        .onEach { it.isAccessible = true }

    fun install() {
        cheapGuard.activateStandaloneProtocolOwnership()
        try {
            listeningChannels(server).forEach(::installParentHandler)
            server.connection.connections.forEach { installChildHandler(it.channel) }
        } catch (failure: Throwable) {
            cheapGuard.deactivateStandaloneProtocolOwnership()
            close()
            throw failure
        }
    }

    private fun installParentHandler(channel: Channel) {
        channel.eventLoop().submit {
            if (closed.get()) return@submit
            if (channel.pipeline().get(parentHandlerName) == null) {
                val acceptor = channel.pipeline().names().firstOrNull { name ->
                    channel.pipeline().get(name)?.javaClass?.name?.contains("ServerBootstrapAcceptor") == true
                } ?: error("Cannot locate Netty server acceptor")
                val handler = object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(context: ChannelHandlerContext, message: Any) {
                        if (message is Channel) prepareAcceptedChannel(message)
                        context.fireChannelRead(message)
                    }
                }
                channel.pipeline().addBefore(acceptor, parentHandlerName, handler)
                own(channel, parentHandlerName, handler)
                installedParents += channel
            }
        }.syncUninterruptibly()
    }

    private fun prepareAcceptedChannel(channel: Channel) {
        if (closed.get()) { channel.close(); return }
        val initializer = object : ChannelInitializer<Channel>() {
            override fun initChannel(initialized: Channel) {
                if (closed.get()) { initialized.close(); return }
                val handler = object : ChannelInboundHandlerAdapter() {
                    override fun channelActive(context: ChannelHandlerContext) {
                        context.pipeline().remove(this)
                        installChildHandler(context.channel())
                        context.fireChannelActive()
                    }
                }
                initialized.pipeline().addLast(childBootstrapHandlerName, handler)
                own(initialized, childBootstrapHandlerName, handler)
            }
        }
        channel.pipeline().addFirst("authgatewayx-standalone-premium-initializer", initializer)
        own(channel, "authgatewayx-standalone-premium-initializer", initializer)
    }

    private fun installChildHandler(channel: Channel) {
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute { installChildHandler(channel) }
            return
        }
        if (closed.get()) return
        if (channel.pipeline().get(childHandlerName) != null) return
        val packetHandler = channel.pipeline().names().firstOrNull { channel.pipeline().get(it) is Connection } ?: return
        val connection = channel.pipeline().get(packetHandler) as Connection
        val handler = object : ChannelDuplexHandler() {
            override fun channelRead(context: ChannelHandlerContext, message: Any) {
                if (message is ServerboundHelloPacket) replaceLoginListener(connection)
                context.fireChannelRead(message)
            }

            override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                val rewritten = if (message is ClientboundLoginDisconnectPacket) {
                    (connection.packetListener as? StandalonePremiumLoginListener)?.rewriteDisconnect(message) ?: message
                } else {
                    message
                }
                context.write(rewritten, promise)
            }
        }
        channel.pipeline().addBefore(packetHandler, childHandlerName, handler)
        own(channel, childHandlerName, handler)
    }

    private fun replaceLoginListener(connection: Connection) {
        val current = connection.packetListener ?: return
        if (current.javaClass != ServerLoginPacketListenerImpl::class.java) return
        val original = current as ServerLoginPacketListenerImpl
        val packetListenerField = packetListenerFields.singleOrNull { it.get(connection) === current }
            ?: error("Cannot locate active Paper packet listener field")
        packetListenerField.set(
            connection,
            StandalonePremiumLoginListener(
                server, connection, original.transferred, premiumLookup, cheapGuard, capacity,
                unavailableMessage, rateLimitedMessage, overloadedMessage, invalidSessionMessage, diagnostics.newTrace(), onFailure,
            ),
        )
    }

    // Called on the channel event loop. Cleanup only removes the exact objects installed here.
    private fun own(channel: Channel, name: String, handler: io.netty.channel.ChannelHandler) {
        ownedHandlers.computeIfAbsent(channel) {
            channel.closeFuture().addListener { ownedHandlers.remove(channel) }
            mutableMapOf()
        }[name] = handler
        if (closed.get()) removeOwned(channel)
    }

    private fun removeOwned(channel: Channel) {
        ownedHandlers.remove(channel)?.forEach { (name, handler) ->
            if (channel.pipeline().get(name) === handler) channel.pipeline().remove(handler)
        }
    }

    fun awaitTermination(timeout: java.time.Duration): Boolean = diagnostics.awaitTermination(timeout)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cheapGuard.deactivateStandaloneProtocolOwnership()
        // Drain parent removals before another generation installs the same names.
        installedParents.forEach { channel ->
            channel.eventLoop().submit { removeOwned(channel) }.syncUninterruptibly()
        }
        ownedHandlers.keys.toList().forEach { channel ->
            channel.eventLoop().execute { removeOwned(channel) }
        }
        installedParents.clear()
        diagnostics.close()
    }
}
