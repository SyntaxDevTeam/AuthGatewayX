package pl.syntaxdevteam.authgatewayx.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.Player
import org.slf4j.Logger
import pl.syntaxdevteam.authgatewayx.velocity.loader.PluginInitializer
import pl.syntaxdevteam.message.MessageHandler
import java.nio.file.Path
import pl.syntaxdevteam.authgatewayx.api.*
import pl.syntaxdevteam.authgatewayx.integrations.api.BoundedAuthEventBus

class AuthGatewayXVelocity @Inject constructor(
    proxy: ProxyServer,
    val owner: Any,
    logger: Logger,
    @DataDirectory dataDirectory: Path,
) {
    lateinit var messageHandler: MessageHandler
        internal set
    @Volatile private var sessionRegistry: VelocitySessionRegistry? = null

    /** Diagnostic proxy session state. OFFLINE means proxy admission, NOT backend password auth. */
    fun session(player: Player): ProxySessionSnapshot? = sessionRegistry?.find(VelocitySessionIds.id(player), player.uniqueId)

    internal fun attachSessions(registry: VelocitySessionRegistry) { sessionRegistry = registry }
    internal fun detachSessions() { sessionRegistry = null; apiEvents.close() }

    private val apiEvents = BoundedAuthEventBus()
    val api: ProxyGatewayApi = object : ProxyGatewayApi {
        override fun getProxySession(minecraftUuid: java.util.UUID): ProxyAdmissionSnapshot? =
            sessionRegistry?.findPlayer(minecraftUuid)?.let {
                ProxyAdmissionSnapshot(it.sessionId, it.playerId, it.username,
                    it.mode == SelectedAuthenticationMode.MOJANG, it.connectedAt, it.backend)
            }
        override fun events(): AuthEventSource = apiEvents
    }
    internal fun observe(type: AuthEventType, session: ProxySessionSnapshot) {
        apiEvents.publish(AuthEvent(type, java.time.Instant.now(), session.playerId, session.username, session.sessionId, null))
    }

    private val initializer = PluginInitializer(this, proxy, logger, dataDirectory)

    @Subscribe
    fun onInitialize(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) = initializer.onEnable()

    @Subscribe
    fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) = initializer.onDisable()
}
