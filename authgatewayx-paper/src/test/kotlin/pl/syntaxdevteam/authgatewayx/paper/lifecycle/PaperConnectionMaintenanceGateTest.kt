package pl.syntaxdevteam.authgatewayx.paper.lifecycle

import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class PaperConnectionMaintenanceGateTest {
    @Test fun `rejects unregistered real Netty socket before acceptor registration`() {
        val socket = io.netty.channel.socket.nio.NioSocketChannel()
        assertTrue(socket.isOpen)
        assertFalse(socket.isRegistered)
        assertFalse(MaintenanceConnectionState().accept(socket))
        assertFalse(socket.isOpen)
    }

    @Test fun `closed start rejects sockets and ready admits them`() {
        val state = MaintenanceConnectionState()
        val parent = EmbeddedChannel(MaintenanceAcceptor(state))
        val rejected = EmbeddedChannel()
        assertFalse(parent.writeInbound(rejected))
        assertFalse(rejected.isOpen)
        state.ready()
        val accepted = EmbeddedChannel()
        assertTrue(parent.writeInbound(accepted))
        assertSame(accepted, parent.readInbound())
        state.stop(emptyList())
        assertFalse(accepted.isOpen)
        parent.finishAndReleaseAll()
    }

    @Test fun `stopping closes existing sockets and stale ready cannot reopen gate`() {
        val state = MaintenanceConnectionState()
        val existing = EmbeddedChannel()
        state.stop(listOf(existing))
        state.ready()
        assertFalse(existing.isOpen)
        val reconnect = EmbeddedChannel()
        assertFalse(state.accept(reconnect))
        assertFalse(reconnect.isOpen)
    }

    @Test fun `new generation replaces gate and waits for inherited cleanup`() {
        val parent = EmbeddedChannel()
        parent.pipeline().addLast("ServerBootstrapAcceptor", FakeServerBootstrapAcceptor())
        val old = PaperConnectionMaintenanceGate(listOf(parent))
        old.install()
        old.ready()
        val cleanup = CompletableFuture<Void>()
        old.stop(cleanup, emptyList())
        val next = PaperConnectionMaintenanceGate(listOf(parent))
        next.install()
        assertEquals(1, parent.pipeline().names().count { it == PaperConnectionMaintenanceGate.HANDLER })
        assertFalse(next.previousCleanup.isDone)
        assertFailsWith<IllegalStateException> { next.ready() }
        val rejected = EmbeddedChannel()
        assertFalse(parent.writeInbound(rejected))
        assertFalse(rejected.isOpen)
        cleanup.complete(null)
        assertTrue(next.previousCleanup.isDone)
        next.ready()
        val accepted = EmbeddedChannel()
        assertTrue(parent.writeInbound(accepted))
        assertSame(accepted, parent.readInbound())
        next.stop(CompletableFuture.completedFuture(null), emptyList())
        parent.finishAndReleaseAll()
    }

    @Test fun `disabling second generation cannot bypass pending first cleanup`() {
        val parent = EmbeddedChannel()
        parent.pipeline().addLast("ServerBootstrapAcceptor", FakeServerBootstrapAcceptor())
        val first = PaperConnectionMaintenanceGate(listOf(parent))
        first.install()
        val cleanup = CompletableFuture<Void>()
        first.stop(cleanup, emptyList())
        val second = PaperConnectionMaintenanceGate(listOf(parent))
        second.install()
        second.stop(CompletableFuture.completedFuture(null), emptyList())
        val third = PaperConnectionMaintenanceGate(listOf(parent))
        third.install()
        assertFalse(third.previousCleanup.isDone)
        cleanup.complete(null)
        assertTrue(third.previousCleanup.isDone)
        parent.finishAndReleaseAll()
    }

    @Test fun `cleanup failure is preserved across repeated reloads`() {
        val parent = EmbeddedChannel()
        parent.pipeline().addLast("ServerBootstrapAcceptor", FakeServerBootstrapAcceptor())
        val first = PaperConnectionMaintenanceGate(listOf(parent))
        first.install()
        val failure = CompletableFuture.failedFuture<Void>(IllegalStateException("cleanup failed"))
        first.stop(failure, emptyList())
        val second = PaperConnectionMaintenanceGate(listOf(parent))
        second.install()
        assertTrue(second.previousCleanup.isCompletedExceptionally)
        second.stop(CompletableFuture.completedFuture(null), emptyList())
        val third = PaperConnectionMaintenanceGate(listOf(parent))
        third.install()
        assertTrue(third.previousCleanup.isCompletedExceptionally)
        assertFailsWith<IllegalStateException> { third.ready() }
        parent.finishAndReleaseAll()
    }

    private class FakeServerBootstrapAcceptor : ChannelInboundHandlerAdapter()
}
