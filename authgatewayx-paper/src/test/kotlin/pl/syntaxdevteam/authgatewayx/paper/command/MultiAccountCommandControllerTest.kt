package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiAccountCommandControllerTest {
    private class Harness {
        var permission = true
        var active = true
        var ready = true
        var calls = 0
        var pending = CompletableFuture<MultiAccountReport?>()
        val replies = mutableListOf<Component>()
        val dispatched = mutableListOf<Runnable>()
        val text = MultiAccountCommandText(Component.text("header"), Component.text("entry"),
            Component.text("empty"), Component.text("truncated"), Component.text("unavailable"), Component.text("notFound"))
        val controller = MultiAccountCommandController(object : MultiAccountLookup {
            override fun findRelatedOfflineAccounts(username: AccountUsername, observedAt: Instant): CompletableFuture<MultiAccountReport?> {
                calls++
                return pending
            }
        }, { _, task -> dispatched.add(task) }, text, { active }, { ready })

        fun sender(player: Boolean = false): CommandSender {
            val type = if (player) Player::class.java else ConsoleCommandSender::class.java
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "hasPermission" -> permission
                    "isOnline" -> true
                    "sendMessage" -> { args?.filterIsInstance<Component>()?.let(replies::addAll); null }
                    "toString" -> "test sender"
                    "hashCode" -> 1
                    else -> null
                }
            } as CommandSender
        }
    }

    @Test
    fun `no permission and pre auth never query storage`() {
        val h = Harness()
        h.permission = false
        h.controller.show(h.sender(), "Player")
        h.permission = true
        h.active = false
        h.controller.show(h.sender(player = true), "Player")
        assertEquals(0, h.calls)
    }

    @Test
    fun `concurrent commands are rejected before storage and failures are explicit`() {
        val h = Harness()
        val sender = h.sender()
        h.controller.show(sender, "Player")
        h.controller.show(sender, "Player")
        assertEquals(1, h.calls)
        assertEquals(listOf(h.text.unavailable), h.replies)
        h.pending.completeExceptionally(IllegalStateException("database unavailable"))
        assertEquals(1, h.dispatched.size)
        h.dispatched.single().run()
        assertEquals(listOf(h.text.unavailable, h.text.unavailable), h.replies)
        h.pending = CompletableFuture()
        h.controller.show(sender, "Player")
        assertEquals(2, h.calls)
    }

    @Test
    fun `permission is rechecked after async query before disclosing report`() {
        val h = Harness()
        h.controller.show(h.sender(), "Player")
        h.pending.complete(MultiAccountReport(emptyList(), false))
        assertTrue(h.replies.isEmpty())
        h.permission = false
        h.dispatched.single().run()
        assertTrue(h.replies.isEmpty())
    }

    @Test
    fun `inactive session and shutdown suppress pending responses`() {
        val h = Harness()
        h.controller.show(h.sender(player = true), "Player")
        h.pending.complete(MultiAccountReport(emptyList(), false))
        h.active = false
        h.dispatched.single().run()
        assertTrue(h.replies.isEmpty())
        val stopping = Harness()
        stopping.controller.show(stopping.sender(), "Player")
        stopping.ready = false
        stopping.pending.complete(MultiAccountReport(emptyList(), false))
        assertTrue(stopping.dispatched.isEmpty())
    }

    @Test
    fun `unknown account and empty history have different responses`() {
        val h = Harness()
        h.controller.show(h.sender(), "Unknown")
        h.pending.complete(null)
        h.dispatched.single().run()
        assertEquals(listOf(h.text.notFound), h.replies)
        val empty = Harness()
        empty.controller.show(empty.sender(), "Player")
        empty.pending.complete(MultiAccountReport(emptyList(), false))
        empty.dispatched.single().run()
        assertEquals(listOf(empty.text.header, empty.text.empty), empty.replies)
    }
}
