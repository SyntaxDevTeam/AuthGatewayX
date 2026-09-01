package pl.syntaxdevteam.authgatewayx.paper.premium

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import net.kyori.adventure.text.Component
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.protocol.login.ServerboundHelloPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

internal class StandalonePremiumProtocolInterceptor(
    private val server: MinecraftServer,
    private val premiumLookup: MojangProfileIdentityLookup,
    maximumConcurrentHandshakes: Int,
    private val unavailableMessage: Component,
    private val overloadedMessage: Component,
    private val onFailure: Consumer<Throwable>,
) : AutoCloseable {
    private val capacity = PremiumHandshakeCapacity(maximumConcurrentHandshakes)
    private val parentHandlerName = "authgatewayx-standalone-premium-acceptor"
    private val childHandlerName = "authgatewayx-standalone-premium-login"
    private val installedParents = CopyOnWriteArrayList<Channel>()
    private val packetListenerFields: List<Field> = Connection::class.java.declaredFields
        .filter { PacketListener::class.java.isAssignableFrom(it.type) }
        .onEach { it.isAccessible = true }

    fun install() {
        listeningChannels().forEach(::installParentHandler)
        server.connection.connections.forEach { installChildHandler(it.channel) }
    }

    private fun installParentHandler(channel: Channel) {
        channel.eventLoop().submit {
            if (channel.pipeline().get(parentHandlerName) == null) {
                val acceptor = channel.pipeline().names().firstOrNull { name ->
                    channel.pipeline().get(name)?.javaClass?.name?.contains("ServerBootstrapAcceptor") == true
                } ?: error("Cannot locate Netty server acceptor")
                channel.pipeline().addBefore(acceptor, parentHandlerName, object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(context: ChannelHandlerContext, message: Any) {
                        if (message is Channel) prepareAcceptedChannel(message)
                        context.fireChannelRead(message)
                    }
                })
                installedParents += channel
            }
        }.syncUninterruptibly()
    }

    private fun prepareAcceptedChannel(channel: Channel) {
        channel.pipeline().addFirst(object : ChannelInitializer<Channel>() {
            override fun initChannel(initialized: Channel) {
                initialized.eventLoop().execute { installChildHandler(initialized) }
            }
        })
    }

    private fun installChildHandler(channel: Channel) {
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute { installChildHandler(channel) }
            return
        }
        if (channel.pipeline().get(childHandlerName) != null) return
        val packetHandler = channel.pipeline().names().firstOrNull { channel.pipeline().get(it) is Connection } ?: return
        val connection = channel.pipeline().get(packetHandler) as Connection
        channel.pipeline().addBefore(packetHandler, childHandlerName, object : ChannelInboundHandlerAdapter() {
            override fun channelRead(context: ChannelHandlerContext, message: Any) {
                if (message is ServerboundHelloPacket) replaceLoginListener(connection)
                context.fireChannelRead(message)
            }
        })
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
                server, connection, original.transferred, premiumLookup, capacity,
                unavailableMessage, overloadedMessage, onFailure,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun listeningChannels(): List<Channel> {
        val channelFutures = server.connection.javaClass.declaredFields
            .asSequence()
            .filter { List::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                field.isAccessible = true
                (field.get(server.connection) as? List<*>)
                    ?.takeIf { list -> list.isNotEmpty() && list.all { it is io.netty.channel.ChannelFuture } }
            }
            .firstOrNull()
            ?: error("Cannot locate Paper listening channels")
        return channelFutures.map { (it as io.netty.channel.ChannelFuture).channel() }
    }

    override fun close() {
        installedParents.forEach { channel ->
            channel.eventLoop().execute {
                if (channel.pipeline().get(parentHandlerName) != null) channel.pipeline().remove(parentHandlerName)
            }
        }
        server.connection.connections.forEach { connection ->
            connection.channel.eventLoop().execute {
                if (connection.channel.pipeline().get(childHandlerName) != null) {
                    connection.channel.pipeline().remove(childHandlerName)
                }
            }
        }
        installedParents.clear()
    }
}
