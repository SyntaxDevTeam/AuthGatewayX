@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.logincorex.paper.dialog

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import pl.syntaxdevteam.logincorex.auth.session.PreJoinConnectionGate
import pl.syntaxdevteam.logincorex.auth.session.SessionRegistry
import pl.syntaxdevteam.logincorex.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.logincorex.auth.ui.AuthenticationFormHandler
import pl.syntaxdevteam.logincorex.auth.ui.AuthenticationFormResult
import pl.syntaxdevteam.logincorex.auth.ui.PasswordConfirmation
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dialog transport for the configuration stage, without a Bukkit Player or a world.
 *
 * Not registered in the plugin runtime until the full pre-join routing/migration/consent
 * path is implemented. Only the matching live connection and ticket can complete auth.
 * The underlying AuthenticationFormCoordinator still owns activation and password policy.
 */
class ConfigurationAuthenticationDialogController(
    private val handler: AuthenticationFormHandler,
    private val renderer: AuthenticationDialogPresentation,
    private val sessions: SessionRegistry,
    private val gate: PreJoinConnectionGate,
    private val text: AuthenticationDialogText,
    private val onOutcome: (AuthenticationFormContext, AuthenticationFormResult) -> Unit = { _, _ -> },
) : Listener {
    private enum class Kind { LOGIN, REGISTER }

    private class Pending(
        val connection: PlayerConfigurationConnection,
        val ticket: PreJoinConnectionGate.Ticket,
        val context: AuthenticationFormContext,
        val kind: Kind,
    ) {
        val inFlight = AtomicBoolean(false)
    }

    private val awaiting = ConcurrentHashMap<UUID, Pending>()
    private val stopped = AtomicBoolean(false)

    /** Returns false if another connection already owns this profile, or the ticket expired. */
    fun showLogin(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ): Boolean = show(connection, ticket, context, Kind.LOGIN)

    fun showRegistration(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ): Boolean = show(connection, ticket, context, Kind.REGISTER)

    private fun show(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
        kind: Kind,
    ): Boolean {
        if (stopped.get() || !connection.isConnected || !gate.isPending(ticket) ||
            context.connectionId != ticket.connectionId || context.minecraftUuid != ticket.connectionId.value
        ) return false
        val pending = Pending(connection, ticket, context, kind)
        if (awaiting.putIfAbsent(ticket.connectionId.value, pending) != null) return false
        return try {
            present(pending)
            true
        } catch (_: Exception) {
            abandon(pending)
            false
        }
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        val connection = event.commonConnection as? PlayerConfigurationConnection ?: return
        val profileId = connection.profile.id ?: return
        val pending = awaiting[profileId] ?: return
        if (pending.connection !== connection || !current(pending)) return

        val kind = when (event.identifier) {
            LOGIN_SUBMIT -> Kind.LOGIN
            REGISTER_SUBMIT -> Kind.REGISTER
            CANCEL -> {
                // Terminate the ticket before disconnecting: late auth cannot release it.
                if (awaiting.remove(profileId, pending)) {
                    gate.deny(pending.ticket)
                    connection.disconnect(text.cancelled)
                }
                return
            }
            else -> return
        }
        if (pending.kind != kind || !pending.inFlight.compareAndSet(false, true)) return

        val response = event.dialogResponseView
        val password = response?.getText(PASSWORD_KEY)?.toCharArray()
        if (password == null || password.isEmpty()) {
            password?.fill('\u0000')
            pending.inFlight.set(false)
            runCatching { present(pending, text.invalidCredentials) }.onFailure { abandon(pending) }
            return
        }
        if (kind == Kind.REGISTER) {
            val repeated = response.getText(REPEAT_PASSWORD_KEY)?.toCharArray()
            if (repeated == null || !PasswordConfirmation.matches(password, repeated)) {
                password.fill('\u0000')
                repeated?.fill('\u0000')
                pending.inFlight.set(false)
                runCatching { present(pending, text.passwordMismatch) }.onFailure { abandon(pending) }
                return
            }
            repeated.fill('\u0000')
        }

        val stage = try {
            if (kind == Kind.LOGIN) handler.submitLogin(pending.context, password)
            else handler.submitRegistration(pending.context, password)
        } catch (_: Throwable) {
            password.fill('\u0000')
            pending.inFlight.set(false)
            runCatching { present(pending, text.internalFailure) }.onFailure { abandon(pending) }
            return
        }

        stage.whenComplete { result, failure ->
            pending.inFlight.set(false)
            if (!current(pending)) return@whenComplete
            if (failure == null && result != null) {
                runCatching { onOutcome(pending.context, result) }
            }
            if (failure == null && result == AuthenticationFormResult.AUTHENTICATED) {
                val active = sessions.get(pending.context.connectionId)
                if (active?.state == ConnectionState.ACTIVE && active.minecraftUuid == pending.context.minecraftUuid) {
                    if (gate.authenticated(pending.ticket)) {
                        awaiting.remove(profileId, pending)
                        return@whenComplete
                    }
                }
                abandon(pending)
                return@whenComplete
            }
            val message = if (failure != null) text.internalFailure else feedback(result)
            runCatching { present(pending, message) }.onFailure { abandon(pending) }
        }
    }

    private fun present(pending: Pending, feedback: Component? = null) {
        if (!current(pending)) return
        if (pending.kind == Kind.LOGIN) renderer.presentLogin(pending.connection.audience, pending.context.username.value, feedback)
        else renderer.presentRegistration(pending.connection.audience, pending.context.username.value, feedback)
    }

    /** Clear a matching ticket on disconnect/timeout; never touch a subsequent reconnect. */
    fun forget(ticket: PreJoinConnectionGate.Ticket) {
        val pending = awaiting[ticket.connectionId.value] ?: return
        if (pending.ticket === ticket) awaiting.remove(ticket.connectionId.value, pending)
    }

    @EventHandler
    fun onConnectionClose(event: PlayerConnectionCloseEvent) {
        val pending = awaiting[event.playerUniqueId] ?: return
        // The close event exposes a UUID but no connection instance; do not remove a newer reconnect.
        if (!pending.connection.isConnected) abandon(pending)
    }

    fun shutdown() {
        stopped.set(true)
        awaiting.values.toList().forEach(::abandon)
    }

    private fun current(pending: Pending): Boolean =
        !stopped.get() && pending.connection.isConnected && gate.isPending(pending.ticket) &&
            awaiting[pending.ticket.connectionId.value] === pending

    private fun abandon(pending: Pending) {
        awaiting.remove(pending.ticket.connectionId.value, pending)
        gate.disconnected(pending.ticket)
    }

    private fun feedback(result: AuthenticationFormResult?): Component = when (result) {
        AuthenticationFormResult.INVALID_CREDENTIALS -> text.invalidCredentials
        AuthenticationFormResult.ACCOUNT_LOCKED -> text.accountLocked
        AuthenticationFormResult.RATE_LIMITED -> text.rateLimited
        AuthenticationFormResult.ACCOUNT_ALREADY_EXISTS -> text.accountExists
        AuthenticationFormResult.IDENTITY_CONFLICT -> text.identityConflict
        AuthenticationFormResult.ACCESS_DENIED -> text.accessDenied
        else -> text.internalFailure
    }

    private companion object {
        const val PASSWORD_KEY = "password"
        const val REPEAT_PASSWORD_KEY = "repeat_password"
        val LOGIN_SUBMIT = Key.key("logincorex:login_submit")
        val REGISTER_SUBMIT = Key.key("logincorex:register_submit")
        val CANCEL = Key.key("logincorex:auth_cancel")
    }
}
