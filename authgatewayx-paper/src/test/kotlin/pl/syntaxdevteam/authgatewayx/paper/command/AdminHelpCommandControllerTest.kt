package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminHelpCommandControllerTest {
    private val text = AdminHelpCommandText(
        header = Component.text("header {version}"),
        playerSection = Component.text("player"),
        adminSection = Component.text("admin"),
        changePassword = Component.text("change"),
        logout = Component.text("logout"),
        info = Component.text("info"),
        alts = Component.text("alts"),
        setPassword = Component.text("setpassword"),
        migrateStatus = Component.text("status"),
        migrateInspect = Component.text("inspect"),
        migrateRetry = Component.text("retry"),
        migrateRecover = Component.text("recover"),
        hover = Component.text("hover"),
        footer = Component.text("footer"),
    )

    @Test
    fun `player sees only commands allowed by permissions and entries are clickable`() {
        val replies = mutableListOf<Component>()
        val player = sender(
            Player::class.java,
            setOf(
                AdminHelpCommandController.LOGOUT_PERMISSION,
                AdminHelpCommandController.ALTS_PERMISSION,
            ),
            replies,
        )
        AdminHelpCommandController(text, "1.2.3").show(player)

        assertEquals(6, replies.size)
        assertTrue(replies[0].toString().contains("1.2.3"))
        assertEquals(text.playerSection, replies[1])
        assertEquals(ClickEvent.suggestCommand("/logout"), replies[2].clickEvent())
        assertEquals(text.adminSection, replies[3])
        assertEquals(ClickEvent.suggestCommand("/agx alts "), replies[4].clickEvent())
        assertEquals(text.footer, replies[5])
        assertFalse(replies.any { it == text.info || it == text.setPassword })
    }

    @Test
    fun `console sees console-capable administration commands but not player-only commands`() {
        val replies = mutableListOf<Component>()
        val console = sender(ConsoleCommandSender::class.java, emptySet(), replies)
        AdminHelpCommandController(text, "1.0.0").show(console)

        assertEquals(text.adminSection, replies[1])
        assertTrue(replies.contains(text.info))
        assertTrue(replies.contains(text.alts))
        assertTrue(replies.contains(text.migrateStatus))
        assertTrue(replies.contains(text.migrateInspect))
        assertTrue(replies.contains(text.migrateRetry))
        assertTrue(replies.contains(text.migrateRecover))
        assertFalse(replies.contains(text.changePassword))
        assertFalse(replies.contains(text.logout))
        assertFalse(replies.contains(text.setPassword))
        assertEquals(text.footer, replies.last())
    }

    @Test
    fun `player without plugin permissions receives only frame`() {
        val replies = mutableListOf<Component>()
        val player = sender(Player::class.java, emptySet(), replies)
        AdminHelpCommandController(text, "1.0.0").show(player)

        assertEquals(2, replies.size)
        assertEquals(text.footer, replies.last())
    }

    private fun <T : CommandSender> sender(
        type: Class<T>,
        permissions: Set<String>,
        replies: MutableList<Component>,
    ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        when (method.name) {
            "hasPermission" -> (args?.firstOrNull() as? String) in permissions
            "sendMessage" -> {
                args?.filterIsInstance<Component>()?.let(replies::addAll)
                null
            }
            "isOnline" -> true
            "toString" -> "help-test-sender"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as T
}
