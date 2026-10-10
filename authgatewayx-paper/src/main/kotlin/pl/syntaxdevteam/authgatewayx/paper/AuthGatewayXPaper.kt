package pl.syntaxdevteam.authgatewayx.paper

import org.bukkit.plugin.java.JavaPlugin
import pl.syntaxdevteam.authgatewayx.paper.loader.PluginInitializer
import pl.syntaxdevteam.core.logging.Logger
import pl.syntaxdevteam.message.MessageHandler

class AuthGatewayXPaper : JavaPlugin() {
    lateinit var loggerX: Logger
        internal set
    lateinit var messageHandler: MessageHandler
        internal set

    private val initializer = PluginInitializer(this)

    override fun onEnable() = initializer.onEnable()

    override fun onDisable() = initializer.onDisable()
}
