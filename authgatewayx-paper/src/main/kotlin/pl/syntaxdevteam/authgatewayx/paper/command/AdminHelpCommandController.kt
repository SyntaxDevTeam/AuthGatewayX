package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player

fun interface AdminHelpCommandGateway {
    fun show(sender: CommandSender)
}

class MutableAdminHelpCommandGateway : AdminHelpCommandGateway {
    @Volatile var delegate: AdminHelpCommandGateway? = null

    override fun show(sender: CommandSender) = delegate?.show(sender) ?: Unit
}

data class AdminHelpCommandText(
    val header: Component,
    val playerSection: Component,
    val adminSection: Component,
    val changePassword: Component,
    val logout: Component,
    val info: Component,
    val alts: Component,
    val setPassword: Component,
    val migrateStatus: Component,
    val migrateInspect: Component,
    val migrateRetry: Component,
    val migrateRecover: Component,
    val reload: Component,
    val hover: Component,
    val footer: Component,
)

class AdminHelpCommandController(
    private val text: AdminHelpCommandText,
    private val version: String,
) : AdminHelpCommandGateway {
    override fun show(sender: CommandSender) {
        val playerEntries = buildList {
            if (sender is Player && sender.hasPermission(CHANGE_PASSWORD_PERMISSION)) {
                add(text.changePassword to "/changepassword")
            }
            if (sender is Player && sender.hasPermission(LOGOUT_PERMISSION)) {
                add(text.logout to "/logout")
            }
        }
        val adminEntries = buildList {
            if (allowed(sender, PasswordCommandRegistrar.RELOAD_PERMISSION)) {
                add(text.reload to "/agx reload")
            }
            if (allowed(sender, INFO_PERMISSION)) add(text.info to "/agx info ")
            if (allowed(sender, ALTS_PERMISSION)) add(text.alts to "/agx alts ")
            if (sender is Player && sender.hasPermission(SET_PASSWORD_PERMISSION)) {
                add(text.setPassword to "/agx setpassword ")
            }
            if (allowed(sender, MigrationAdminCommandController.VIEW_PERMISSION)) {
                add(text.migrateStatus to "/agx migrate status ")
                add(text.migrateInspect to "/agx migrate inspect ")
            }
            if (allowed(sender, MigrationAdminCommandController.EXECUTE_PERMISSION)) {
                add(text.migrateRetry to "/agx migrate retry ")
            }
            if (allowed(sender, MigrationAdminCommandController.RECOVER_PERMISSION)) {
                add(text.migrateRecover to "/agx migrate recover ")
            }
        }

        sender.sendMessage(text.header.withText("{version}", version))
        if (playerEntries.isNotEmpty()) {
            sender.sendMessage(text.playerSection)
            playerEntries.forEach { (component, suggestion) ->
                sender.sendMessage(interactive(sender, component, suggestion))
            }
        }
        if (adminEntries.isNotEmpty()) {
            sender.sendMessage(text.adminSection)
            adminEntries.forEach { (component, suggestion) ->
                sender.sendMessage(interactive(sender, component, suggestion))
            }
        }
        sender.sendMessage(text.footer)
    }

    private fun interactive(sender: CommandSender, component: Component, suggestion: String): Component =
        if (sender is Player) {
            component
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .hoverEvent(text.hover)
        } else {
            component
        }

    private fun allowed(sender: CommandSender, permission: String): Boolean =
        sender is ConsoleCommandSender || sender.hasPermission(permission)

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )

    companion object {
        const val CHANGE_PASSWORD_PERMISSION = "authgatewayx.command.changepassword"
        const val LOGOUT_PERMISSION = "authgatewayx.command.logout"
        const val INFO_PERMISSION = "authgatewayx.admin.info"
        const val ALTS_PERMISSION = "authgatewayx.admin.alts"
        const val SET_PASSWORD_PERMISSION = "authgatewayx.admin.password"
    }
}
