@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.isolation

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.session.PreJoinConnectionGate
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import pl.syntaxdevteam.authgatewayx.paper.dialog.ConfigurationAuthenticationDialogController
import pl.syntaxdevteam.authgatewayx.paper.dialog.ConfigurationAuthenticationRouter
import pl.syntaxdevteam.authgatewayx.paper.dialog.ConfigurationPremiumMigrationDialogController
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private val migrationDialogs: ConfigurationPremiumMigrationDialogController,
    private val router: ConfigurationAuthenticationRouter,
    private val isReady: () -> Boolean,
    private val timeout: Duration,
    private val unavailableMessage: Component,
    private val capacityMessage: Component,
    private val timeoutMessage: Component,
    private val onPreAuth: (UUID) -> Any?,
    private val onPreAuthEnd: (UUID, Any?) -> Unit,
    private val onWorldJoin: (UUID) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : Listener {
    private data class ConnectionRecord(
        val connection: PlayerConfigurationConnection,
        val ticket: PreJoinConnectionGate.Ticket,
        val sessionNonce: UUID,
        val ownsSession: AtomicBoolean = AtomicBoolean(false),
        val consentToken: AtomicReference<Any?> = AtomicReference(null),
    )

    private val connections = ConcurrentHashMap<UUID, ConnectionRecord>()
    private val reconfigurations = ConcurrentHashMap<UUID, PlayerConfigurationConnection>()
    private data class AdmittedPlayer(
        val player: Player,
        val sessionNonce: UUID,
        val consentToken: Any?,
    )

    private val admittedPlayers = ConcurrentHashMap<UUID, AdmittedPlayer>()
    private val stopping = AtomicBoolean(false)

    init { require(!timeout.isZero && !timeout.isNegative) }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onReconfigure(event: PlayerConnectionReconfigureEvent) {
        val connection = event.connection
        val id = connection.profile.id ?: return
        if (!stopping.get() && isReady() &&
            sessions.get(ConnectionId(id))?.state == ConnectionState.ACTIVE
        ) {
            reconfigurations[id] = connection
        } else {
            connection.disconnect(unavailableMessage)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onConfigure(event: AsyncPlayerConnectionConfigureEvent) {
        val connection = event.connection
        // Reconfiguration of an already-authenticated player is not a new login.
        // Match the exact connection object; the same UUID on a second socket cannot bypass auth.
        val reconfigureId = connection.profile.id
        if (reconfigureId != null && reconfigurations[reconfigureId] === connection) {
            if (stopping.get() || !isReady() ||
                sessions.get(ConnectionId(reconfigureId))?.state != ConnectionState.ACTIVE
            ) {
                connection.disconnect(unavailableMessage)
                reconfigurations.remove(reconfigureId, connection)
            } else {
                // Reconfiguration remains held until explicitly completed by the plugin.
                connection.completeReconfiguration()
            }
            return
        }
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
        val address = (connection.address as? InetSocketAddress)?.address
        if (id == null || username == null || address == null) {
            connection.disconnect(unavailableMessage)
            return
        }
        val connectionId = ConnectionId(id)
        val initialSession = AuthSession.connecting(connectionId, username, address, clock.instant())
        val ticket = gate.tryOpen(connectionId)
        if (ticket == null) {
            connection.disconnect(capacityMessage)
            return
        }
        val record = ConnectionRecord(connection, ticket, initialSession.sessionNonce)
        if (connections.putIfAbsent(id, record) != null) {
            gate.deny(ticket)
            connection.disconnect(capacityMessage)
            return
        }

        try {
            // Never disconnect another connection's session simply because this UUID reconnected.
            val created = sessions.create(initialSession)
            if (!created) {
                gate.deny(ticket)
                connection.disconnect(capacityMessage)
                removeRecord(id, record)
                return
            }
            record.ownsSession.set(true)
            if (connections[id] !== record) {
                // Disconnect could have removed the record while create() was in progress.
                sessions.disconnect(connectionId, record.sessionNonce)
                return
            }
            sessions.enterPreAuth(connectionId)
            val consentToken = onPreAuth(id)
            record.consentToken.set(consentToken)
            if (connections[id] !== record) {
                onPreAuthEnd(id, consentToken)
                return
            }
            router.route(connection, ticket, AuthenticationFormContext(connectionId, username, address, id, record.sessionNonce))
            val result = gate.await(ticket, timeout)
            if (result == PreJoinConnectionGate.Outcome.AUTHENTICATED && !stopping.get() && isReady() &&
                connection.isConnected && sessions.get(connectionId)?.state == ConnectionState.ACTIVE &&
                sessions.get(connectionId)?.sessionNonce == record.sessionNonce
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
            if (sessions.get(connectionId)?.state != ConnectionState.ACTIVE ||
                sessions.get(connectionId)?.sessionNonce != record.sessionNonce || !connection.isConnected) {
                removeRecord(id, record)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val id = event.player.uniqueId
        val record = connections.remove(id)
        if (record == null) {
            // A new socket with a duplicated UUID must not inherit the existing
            // player's reconfiguration grant. Compare the actual Player entity.
            val reconfiguration = reconfigurations[id]
            if (reconfiguration != null && reconfiguration.isConnected &&
                admittedPlayers[id]?.player === event.player &&
                sessions.get(ConnectionId(id))?.state == ConnectionState.ACTIVE
            ) {
                reconfigurations.remove(id, reconfiguration)
                return
            }
            event.player.kick(unavailableMessage)
            return
        }
        if (!record.connection.isConnected ||
            event.player.connection.address != record.connection.address ||
            !event.player.name.equals(record.connection.profile.name, ignoreCase = true) ||
            (sessions.get(ConnectionId(id))?.state != ConnectionState.ACTIVE ||
                sessions.get(ConnectionId(id))?.sessionNonce != record.sessionNonce)
        ) {
            // A failed PlayerJoinEvent validation must not retain ACTIVE or a consent token.
            sessions.disconnect(record.ticket.connectionId, record.sessionNonce)
            runCatching { onPreAuthEnd(id, record.consentToken.get()) }
            event.player.kick(unavailableMessage)
            return
        }
        admittedPlayers[id] = AdmittedPlayer(event.player, record.sessionNonce, record.consentToken.get())
        onWorldJoin(id)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        val admitted = admittedPlayers[id]
        if (admitted != null && admitted.player === event.player &&
            admittedPlayers.remove(id, admitted)
        ) {
            sessions.disconnect(ConnectionId(id), admitted.sessionNonce)
            runCatching { onPreAuthEnd(id, admitted.consentToken) }
        }
        reconfigurations[id]?.let { connection ->
            if (!connection.isConnected) reconfigurations.remove(id, connection)
        }
    }

    @EventHandler
    fun onConnectionClose(event: PlayerConnectionCloseEvent) {
        val id = event.playerUniqueId
        reconfigurations[id]?.let { connection ->
            if (!connection.isConnected) reconfigurations.remove(id, connection)
        }
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
            migrationDialogs.forget(record.ticket)
            gate.disconnected(record.ticket)
            if (record.ownsSession.get()) sessions.disconnect(record.ticket.connectionId, record.sessionNonce)
            runCatching { onPreAuthEnd(id, record.consentToken.get()) }
        }
    }

    /** Called synchronously when the plugin begins shutdown/reload. */
    fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        gate.close()
        dialogs.shutdown()
        migrationDialogs.shutdown()
        reconfigurations.clear()
        admittedPlayers.clear()
        connections.entries.toList().forEach { (id, record) ->
            disconnectRecord(id, record, unavailableMessage)
        }
    }
}
