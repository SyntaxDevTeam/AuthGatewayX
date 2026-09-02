@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.dialog

import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import pl.syntaxdevteam.authgatewayx.auth.password.PasswordChangeResult
import pl.syntaxdevteam.authgatewayx.auth.password.PasswordChangeService
import pl.syntaxdevteam.authgatewayx.auth.ui.PasswordConfirmation
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.paper.command.PasswordCommandGateway
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PasswordChangeDialogText(
    val ownTitle: Component,
    val adminTitle: Component,
    val ownPrompt: Component,
    val adminPrompt: Component,
    val currentPasswordLabel: Component,
    val newPasswordLabel: Component,
    val repeatPasswordLabel: Component,
    val submitLabel: Component,
    val cancelLabel: Component,
    val mismatch: Component,
    val invalidCurrent: Component,
    val accountNotFound: Component,
    val offlineOnly: Component,
    val success: Component,
    val internalFailure: Component,
)

class PasswordChangeDialogController(
    private val service: PasswordChangeService,
    private val scheduler: PaperPlatformScheduler,
    private val sessions: pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry,
    private val text: PasswordChangeDialogText,
    private val onAdminChanged: (AccountUsername) -> Unit = {},
) : Listener, PasswordCommandGateway {
    private val forms = ConcurrentHashMap<UUID, Form>()
    private val submissions = ConcurrentHashMap.newKeySet<UUID>()

    override fun openOwnPasswordChange(player: Player) {
        val session = sessions.get(pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId(player.uniqueId))
        if (session?.identityType != IdentityType.OFFLINE || session.accountId == null) {
            player.sendMessage(text.offlineOnly)
            return
        }
        show(player, Form.Own)
    }

    override fun openAdminPasswordChange(player: Player, username: String) {
        val parsed = runCatching { AccountUsername.parse(username) }.getOrNull()
        if (parsed == null) {
            player.sendMessage(text.accountNotFound)
            return
        }
        show(player, Form.Admin(parsed))
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.identifier != SUBMIT) return
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        val form = forms[player.uniqueId] ?: return
        val response = event.dialogResponseView ?: return
        val newPassword = response.getText(NEW_PASSWORD)?.toCharArray() ?: return
        val repeated = response.getText(REPEAT_PASSWORD)?.toCharArray() ?: return
        if (!PasswordConfirmation.matches(newPassword, repeated)) {
            newPassword.fill('\u0000'); repeated.fill('\u0000')
            scheduler.entity(player, Runnable { show(player, form, text.mismatch) })
            return
        }
        repeated.fill('\u0000')
        if (!submissions.add(player.uniqueId)) { newPassword.fill('\u0000'); return }
        val address = player.address?.address ?: run { newPassword.fill('\u0000'); return }
        val stage = try {
            when (form) {
                Form.Own -> {
                    val current = response.getText(CURRENT_PASSWORD)?.toCharArray() ?: run {
                        submissions.remove(player.uniqueId); newPassword.fill('\u0000'); return
                    }
                    service.changeOwnPassword(AccountUsername.parse(player.name), address, current, newPassword)
                }
                is Form.Admin -> service.setPasswordByAdministrator(form.username, address, newPassword)
            }
        } catch (_: Throwable) {
            submissions.remove(player.uniqueId)
            newPassword.fill('\u0000')
            show(player, form, text.internalFailure)
            return
        }
        stage.whenComplete { result, failure -> scheduler.entity(player, Runnable {
            submissions.remove(player.uniqueId)
            if (!player.isOnline) return@Runnable
            when {
                failure != null -> show(player, form, text.internalFailure)
                result == PasswordChangeResult.CHANGED -> {
                    if (form is Form.Admin) onAdminChanged(form.username)
                    forms.remove(player.uniqueId); player.closeDialog(); player.sendMessage(text.success)
                }
                result == PasswordChangeResult.INVALID_CURRENT_PASSWORD -> show(player, form, text.invalidCurrent)
                result == PasswordChangeResult.ACCOUNT_NOT_FOUND -> show(player, form, text.accountNotFound)
                result == PasswordChangeResult.NOT_OFFLINE_ACCOUNT -> show(player, form, text.offlineOnly)
                else -> show(player, form, text.internalFailure)
            }
        }) }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { forms.remove(event.player.uniqueId); submissions.remove(event.player.uniqueId) }

    private fun show(player: Player, form: Form, feedback: Component? = null) {
        forms[player.uniqueId] = form
        player.showDialog(createDialog(form, feedback))
    }

    private fun createDialog(form: Form, feedback: Component?): Dialog = Dialog.create { builder ->
        val body = mutableListOf(DialogBody.plainMessage(if (form is Form.Own) text.ownPrompt else text.adminPrompt, 360))
        if (feedback != null) body += DialogBody.plainMessage(feedback, 360)
        val inputs = mutableListOf<DialogInput>()
        if (form is Form.Own) inputs += DialogInput.text(CURRENT_PASSWORD, text.currentPasswordLabel).width(300).maxLength(128).build()
        inputs += DialogInput.text(NEW_PASSWORD, text.newPasswordLabel).width(300).maxLength(128).build()
        inputs += DialogInput.text(REPEAT_PASSWORD, text.repeatPasswordLabel).width(300).maxLength(128).build()
        builder.empty().base(DialogBase.builder(if (form is Form.Own) text.ownTitle else text.adminTitle)
            .canCloseWithEscape(true).pause(false).body(body).inputs(inputs).build())
            .type(DialogType.confirmation(
                ActionButton.builder(text.submitLabel).action(DialogAction.customClick(SUBMIT, null)).build(),
                ActionButton.builder(text.cancelLabel).build(),
            ))
    }

    private sealed interface Form { data object Own : Form; data class Admin(val username: AccountUsername) : Form }
    companion object {
        private const val CURRENT_PASSWORD = "current_password"
        private const val NEW_PASSWORD = "new_password"
        private const val REPEAT_PASSWORD = "repeat_password"
        private val SUBMIT = Key.key("authgatewayx:password_change_submit")
    }
}
