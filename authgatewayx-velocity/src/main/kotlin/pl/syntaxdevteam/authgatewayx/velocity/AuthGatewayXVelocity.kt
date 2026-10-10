package pl.syntaxdevteam.authgatewayx.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import pl.syntaxdevteam.authgatewayx.velocity.loader.PluginInitializer
import pl.syntaxdevteam.message.MessageHandler
import java.nio.file.Path

class AuthGatewayXVelocity @Inject constructor(
    proxy: ProxyServer,
    logger: Logger,
    @DataDirectory dataDirectory: Path,
) {
    lateinit var messageHandler: MessageHandler
        internal set
    private val initializer = PluginInitializer(this, proxy, logger, dataDirectory)

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) = initializer.onEnable()

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) = initializer.onDisable()
}
