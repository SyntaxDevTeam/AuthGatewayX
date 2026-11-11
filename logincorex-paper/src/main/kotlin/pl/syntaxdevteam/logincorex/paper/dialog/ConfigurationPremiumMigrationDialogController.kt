@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.logincorex.paper.dialog

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import net.kyori.adventure.key.Key
import pl.syntaxdevteam.logincorex.auth.premium.PremiumMigrationAuthorizationResult
import pl.syntaxdevteam.logincorex.auth.premium.PremiumMigrationService
import pl.syntaxdevteam.logincorex.auth.session.PreJoinConnectionGate
import pl.syntaxdevteam.logincorex.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.logincorex.domain.account.AuthAccount
import pl.syntaxdevteam.logincorex.storage.PremiumMigrationTicket
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authorizes OFFLINE -> MOJANG migration without a Player entity.
 * The migration itself must start only AFTER the client's connection has closed.
 * The accepted outcome is intentionally a disconnect, not a release into gameplay.
 */
class ConfigurationPremiumMigrationDialogController(
    private val authorization: PremiumMigrationService,
    private val renderer: PremiumMigrationDialogController,
    private val gate: PreJoinConnectionGate,
    private val text: PremiumMigrationDialogText,
    private val onPrepared: (PremiumMigrationTicket) -> Unit,
) : Listener {
    private class Pending(
        val connection: PlayerConfigurationConnection,
        val ticket: PreJoinConnectionGate.Ticket,
        val context: AuthenticationFormContext,
        val account: AuthAccount,
        val targetId: UUID,
    ) {
        val submitting = AtomicBoolean(false)
    }

    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val closed = AtomicBoolean(false)

    fun show(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
        account: AuthAccount,
        targetId: UUID,
    ): Boolean {
        if (closed.get() || !gate.isPending(ticket) || !connection.isConnected ||
            context.connectionId != ticket.connectionId || targetId != context.minecraftUuid
        ) return false
        val form = Pending(connection, ticket, context, account, targetId)
        if (pending.putIfAbsent(targetId, form) != null) return false
        return runCatching { present(form); true }.getOrElse {
            reject(form)
            false
        }
    }

    @EventHandler
    fun onClick(event: PlayerCustomClickEvent) {
        val connection = event.commonConnection as? PlayerConfigurationConnection ?: return
        val id = connection.profile.id ?: return
        val form = pending[id] ?: return
        if (form.connection !== connection || !current(form)) return
        when (event.identifier) {
            CANCEL -> {
                pending.remove(id, form)
                connection.disconnect(text.cancelled)
                gate.deny(form.ticket)
                return
            }
            SUBMIT -> Unit
            else -> return
        }
        if (!form.submitting.compareAndSet(false, true)) return
        val password = event.dialogResponseView?.getText(PASSWORD)?.toCharArray()
        if (password == null || password.isEmpty()) {
            password?.fill('\u0000')
            form.submitting.set(false)
            runCatching { present(form, text.invalidCredentials) }.onFailure { reject(form) }
            return
        }
        val result = try {
            authorization.authorize(form.account.username, form.context.sourceAddress, form.targetId, password)
        } catch (_: Throwable) {
            password.fill('\u0000')
            form.submitting.set(false)
            runCatching { present(form, text.internalFailure) }.onFailure { reject(form) }
            return
        }
        result.whenComplete { response, failure ->
            password.fill('\u0000')
            form.submitting.set(false)
            if (!current(form)) return@whenComplete
            if (failure != null || response == null) {
                runCatching { present(form, text.internalFailure) }.onFailure { reject(form) }
                return@whenComplete
            }
            when (response) {
                is PremiumMigrationAuthorizationResult.Prepared -> {
                    pending.remove(id, form)
                    if (response.ticket.targetMinecraftUuid != form.targetId) {
                        reject(form)
                        return@whenComplete
                    }
                    // The player must be fully absent before world data is migrated.
                    connection.disconnect(text.prepared)
                    gate.deny(form.ticket)
                    runCatching { onPrepared(response.ticket) }
                }
                PremiumMigrationAuthorizationResult.InvalidCredentials ->
                    runCatching { present(form, text.invalidCredentials) }.onFailure { reject(form) }
                PremiumMigrationAuthorizationResult.AccountLocked ->
                    runCatching { present(form, text.accountLocked) }.onFailure { reject(form) }
                PremiumMigrationAuthorizationResult.RateLimited ->
                    runCatching { present(form, text.rateLimited) }.onFailure { reject(form) }
                PremiumMigrationAuthorizationResult.IdentityConflict ->
                    runCatching { present(form, text.identityConflict) }.onFailure { reject(form) }
            }
        }
    }

    private fun present(form: Pending, message: net.kyori.adventure.text.Component? = null) {
        if (current(form)) form.connection.audience.showDialog(renderer.createDialog(form.account, form.targetId, message))
    }

    private fun current(form: Pending): Boolean =
        !closed.get() && form.connection.isConnected && gate.isPending(form.ticket) &&
            pending[form.targetId] === form

    private fun reject(form: Pending) {
        pending.remove(form.targetId, form)
        if (form.connection.isConnected) form.connection.disconnect(text.internalFailure)
        gate.deny(form.ticket)
    }

    fun forget(ticket: PreJoinConnectionGate.Ticket) {
        val form = pending[ticket.connectionId.value] ?: return
        if (form.ticket === ticket) pending.remove(ticket.connectionId.value, form)
    }

    @EventHandler
    fun onClose(event: PlayerConnectionCloseEvent) {
        pending[event.playerUniqueId]?.let { form ->
            if (!form.connection.isConnected) forget(form.ticket)
        }
    }

    fun shutdown() {
        closed.set(true)
        pending.values.toList().forEach(::reject)
    }

    private companion object {
        const val PASSWORD = "migration_password"
        val SUBMIT = Key.key("logincorex:premium_migration_submit")
        val CANCEL = Key.key("logincorex:premium_migration_cancel")
    }
}
