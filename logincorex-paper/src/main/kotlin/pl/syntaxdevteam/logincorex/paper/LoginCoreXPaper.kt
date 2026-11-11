package pl.syntaxdevteam.logincorex.paper

import pl.syntaxdevteam.logincorex.paper.compatibility.VersionChecker
import pl.syntaxdevteam.logincorex.paper.compatibility.VersionCompatibility
import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.logincorex.paper.command.MutablePasswordCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableLogoutCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableMultiAccountCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableAccountInfoCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableMigrationAdminCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableAdminHelpCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableConsentCommandGateway
import pl.syntaxdevteam.logincorex.paper.command.MutableReloadCommandGateway
import org.bukkit.entity.Player
import pl.syntaxdevteam.logincorex.paper.loader.PluginInitializer
import pl.syntaxdevteam.core.logging.Logger
import pl.syntaxdevteam.message.MessageHandler

class LoginCoreXPaper : JavaPlugin() {
    lateinit var loggerX: Logger
        internal set
    lateinit var messageHandler: MessageHandler
        internal set

    val versionChecker = VersionChecker(this)
    val versionCompatibility by lazy {
        VersionCompatibility(versionChecker)
    }

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
