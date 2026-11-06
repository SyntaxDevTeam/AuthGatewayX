@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.isolation

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import pl.syntaxdevteam.authgatewayx.auth.session.PreJoinConnectionGate
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import pl.syntaxdevteam.authgatewayx.paper.dialog.ConfigurationAuthenticationDialogController
import pl.syntaxdevteam.authgatewayx.paper.dialog.ConfigurationAuthenticationRouter
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental Paper-only pre-join admission. This listener must NEVER be installed together
 * with the old PreAuthEntryListener: exactly one component owns initial AuthSession creation.
 *
 * Async configure handler is intentionally bounded, and blocks only the async configuration
 * event (never a game/region thread). The gate is the only release path into the world.
 */
class PreJoinConfigurationListener(
    private val sessions: SessionRegistry,
    private val gate: PreJoinConnectionGate,
    private val dialogs: ConfigurationAuthenticationDialogController,
    private val router: ConfigurationAuthenticationRouter,
    private val isReady: () -> Boolean,
    private val timeout: Duration,
    private val unavailableMessage: Component,
    private val capacityMessage: Component,
    private val timeoutMessage: Component,
    private val onPreAuth: (UUID) -> Unit,
    private val onWorldJoin: (UUID) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : Listener {
    private data class ConnectionRecord(
        val connection: PlayerConfigurationConnection,
        val ticket: PreJoinConnectionGate.Ticket,
    )

    private val connections = ConcurrentHashMap<UUID, ConnectionRecord>()
    private val stopping = AtomicBoolean(false)

    init { require(!timeout.isZero && !timeout.isNegative) }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onConfigure(event: AsyncPlayerConnectionConfigureEvent) {
        val connection = event.connection
        if (stopping.get() || !isReady()) {
            connection.disconnect(unavailableMessage)
            return
        }
        val id = connection.profile.id
        val username = try {
            connection.profile.name?.let(AccountUsername::parse)
        } catch (_: IllegalArgumentException) {
            null
        }
        val address = connection.address.address
        if (id == null || username == null || address == null) {
            connection.disconnect(unavailableMessage)
            return
        }
        val connectionId = ConnectionId(id)
        val ticket = gate.tryOpen(connectionId)
        if (ticket == null) {
            connection.disconnect(capacityMessage)
            return
        }
        val record = ConnectionRecord(connection, ticket)
        if (connections.putIfAbsent(id, record) != null) {
            gate.deny(ticket)
            connection.disconnect(capacityMessage)
            return
        }

        try {
            // Never disconnect another connection's session simply because this UUID reconnected.
            val created = sessions.create(AuthSession.connecting(connectionId, username, address, clock.instant()))
            if (!created) {
                gate.deny(ticket)
                connection.disconnect(capacityMessage)
                return
            }
            sessions.enterPreAuth(connectionId)
            onPreAuth(id)
            router.route(connection, ticket, AuthenticationFormContext(connectionId, username, address, id))
            val result = gate.await(ticket, timeout)
            if (result == PreJoinConnectionGate.Outcome.AUTHENTICATED && !stopping.get() && isReady() &&
                connection.isConnected && sessions.get(connectionId)?.state == ConnectionState.ACTIVE
            ) {
                // The player can now transition into the world, but a close before PlayerJoinEvent
                // must still invalidate the session. Keep the connection record until join.
                return
            }
            disconnectRecord(id, record, if (result == PreJoinConnectionGate.Outcome.TIMED_OUT)
                timeoutMessage else unavailableMessage)
        } catch (_: Throwable) {
            gate.deny(ticket)
            disconnectRecord(id, record, unavailableMessage)
        } finally {
            // Release only the failed or terminal connections; successful ones remain tracked
            // until PlayerJoinEvent to prevent a connection-close session leak.
            if (sessions.get(connectionId)?.state != ConnectionState.ACTIVE || !connection.isConnected) {
                removeRecord(id, record)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val id = event.player.uniqueId
        val record = connections.remove(id)
        if (record == null || !record.connection.isConnected ||
            sessions.get(ConnectionId(id))?.state != ConnectionState.ACTIVE
        ) {
            event.player.kick(unavailableMessage)
            return
        }
        onWorldJoin(id)
    }

    @EventHandler
    fun onConnectionClose(event: PlayerConnectionCloseEvent) {
        val id = event.playerUniqueId
        val record = connections[id] ?: return
        // Paper's close event only exposes UUID, not a connection reference.
        // A stale close must never invalidate the current, still-connected session.
        if (!record.connection.isConnected) removeRecord(id, record)
    }

    private fun disconnectRecord(id: UUID, record: ConnectionRecord, reason: Component) {
        if (record.connection.isConnected) record.connection.disconnect(reason)
        removeRecord(id, record)
    }

    private fun removeRecord(id: UUID, record: ConnectionRecord) {
        if (connections.remove(id, record)) {
            dialogs.forget(record.ticket)
            gate.disconnected(record.ticket)
            sessions.disconnect(record.ticket.connectionId)
        }
    }

    /** Called synchronously when the plugin begins shutdown/reload. */
    fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        gate.close()
        dialogs.shutdown()
        connections.entries.toList().forEach { (id, record) ->
            disconnectRecord(id, record, unavailableMessage)
        }
    }
}
