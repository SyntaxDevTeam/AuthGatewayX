package pl.syntaxdevteam.authgatewayx.paper

import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.authgatewayx.paper.command.MutablePasswordCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableLogoutCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableMultiAccountCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableAccountInfoCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableMigrationAdminCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableAdminHelpCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableConsentCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.command.MutableReloadCommandGateway
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.paper.loader.PluginInitializer
import pl.syntaxdevteam.core.logging.Logger
import pl.syntaxdevteam.message.MessageHandler

class AuthGatewayXPaper : JavaPlugin() {
    lateinit var loggerX: Logger
        internal set
    lateinit var messageHandler: MessageHandler
        internal set

    internal val passwordCommandGateway = MutablePasswordCommandGateway()
    internal val logoutCommandGateway = MutableLogoutCommandGateway()
    internal val multiAccountCommandGateway = MutableMultiAccountCommandGateway()
    internal val accountInfoCommandGateway = MutableAccountInfoCommandGateway()
    internal val migrationAdminCommandGateway = MutableMigrationAdminCommandGateway()
    internal val adminHelpCommandGateway = MutableAdminHelpCommandGateway()
    internal val reloadCommandGateway = MutableReloadCommandGateway()
    internal val consentCommandGateway = MutableConsentCommandGateway()
    internal var commandsRegistered = false
    internal fun resetMessages() {
        if (this::messageHandler.isInitialized &&
            runCatching { pl.syntaxdevteam.message.SyntaxMessages.messages }.getOrNull() === messageHandler) {
            pl.syntaxdevteam.message.SyntaxMessages.reset()
        }
    }
    private var initializer: PluginInitializer? = null

    internal fun isAuthenticated(player: Player): Boolean = initializer?.isAuthenticated(player) == true

    override fun onEnable() {
        val current = PluginInitializer(this)
        initializer = current
        current.onEnable()
    }

    override fun onDisable() {
        initializer?.onDisable()
        initializer = null
    }
}
