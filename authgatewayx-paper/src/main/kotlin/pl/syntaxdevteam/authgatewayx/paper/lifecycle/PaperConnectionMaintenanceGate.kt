package pl.syntaxdevteam.authgatewayx.paper.lifecycle

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/** Retained while unloaded: deliberately contains no plugin or runtime references. */
internal class PaperConnectionMaintenanceGate(private val parents: List<Channel>) {
    private val state = MaintenanceConnectionState()
    val previousCleanup: CompletableFuture<Void> = CompletableFuture.allOf(
        *parents.mapNotNull { it.attr(CLEANUP).get() }.toTypedArray(),
    )

    fun install() {
        parents.forEach { parent ->
            onEventLoop(parent) {
                val handler = MaintenanceAcceptor(state)
                if (parent.pipeline().get(HANDLER) != null) {
                    parent.pipeline().replace(HANDLER, HANDLER, handler)
                } else {
                    val acceptor = parent.pipeline().names().firstOrNull { name ->
                        parent.pipeline().get(name)?.javaClass?.name?.contains("ServerBootstrapAcceptor") == true
                    } ?: error("Cannot locate Netty server acceptor for maintenance gate")
                    parent.pipeline().addBefore(acceptor, HANDLER, handler)
                }
            }
        }
    }

    fun ready() {
        check(previousCleanup.isDone && !previousCleanup.isCompletedExceptionally) { "Previous runtime has not retired" }
        state.ready()
    }

    fun stop(cleanup: CompletableFuture<Void>, existing: Collection<Channel>) {
        val allCleanup = CompletableFuture.allOf(previousCleanup, cleanup)
        parents.forEach { it.attr(CLEANUP).set(allCleanup) }
        state.stop(existing)
    }

    companion object {
        const val HANDLER = "authgatewayx-maintenance-acceptor"
        private val CLEANUP = AttributeKey.valueOf<CompletableFuture<Void>>("authgatewayx-runtime-cleanup")

        fun create(server: MinecraftServer) = PaperConnectionMaintenanceGate(listeningChannels(server))
    }
}

internal class MaintenanceConnectionState {
    private var accepting = false
    private var stopped = false
    private val channels = mutableSetOf<Channel>()

    @Synchronized fun ready() {
        if (!stopped) accepting = true
    }

    @Synchronized fun accept(channel: Channel): Boolean {
        if (!accepting) {
            closeTransport(channel)
            return false
        }
        channels += channel
        channel.closeFuture().addListener { synchronized(this) { channels -= channel } }
        return true
    }

    @Synchronized fun stop(existing: Collection<Channel>) {
        accepting = false
        stopped = true
        (channels.toList() + existing).distinct().forEach(::closeTransport)
        channels.clear()
    }
}

internal class MaintenanceAcceptor(private val state: MaintenanceConnectionState) : ChannelInboundHandlerAdapter() {
    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        if (message !is Channel || state.accept(message)) context.fireChannelRead(message)
    }
}

internal fun listeningChannels(server: MinecraftServer): List<Channel> {
    val futures = server.connection.javaClass.declaredFields.asSequence()
        .filter { List::class.java.isAssignableFrom(it.type) }
        .mapNotNull { field ->
            field.isAccessible = true
            (field.get(server.connection) as? List<*>)?.takeIf { list ->
                list.isNotEmpty() && list.all { it is io.netty.channel.ChannelFuture }
            }
        }.firstOrNull() ?: error("Cannot locate Paper listening channels")
    return futures.map { (it as io.netty.channel.ChannelFuture).channel() }
}

internal fun closeTransport(channel: Channel) {
    // Newly accepted sockets have not yet been registered on a child event loop.
    if (channel.isRegistered) channel.close() else channel.unsafe().closeForcibly()
}

internal fun onEventLoop(channel: Channel, action: () -> Unit) {
    if (channel.eventLoop().inEventLoop()) action()
    else channel.eventLoop().submit { action() }.syncUninterruptibly()
}
