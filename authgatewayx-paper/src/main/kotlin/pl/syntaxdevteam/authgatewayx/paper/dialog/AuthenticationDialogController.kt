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
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormHandler
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormResult
import pl.syntaxdevteam.authgatewayx.auth.ui.PasswordConfirmation
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AuthenticationDialogText(
    val loginTitle: Component,
    val registrationTitle: Component,
    val loginPrompt: Component,
    val registrationPrompt: Component,
    val passwordLabel: Component,
    val repeatPasswordLabel: Component,
    val submitLabel: Component,
    val cancelLabel: Component,
    val passwordMismatch: Component,
    val invalidCredentials: Component,
    val accountLocked: Component,
    val rateLimited: Component,
    val accountExists: Component,
    val identityConflict: Component,
    val internalFailure: Component,
)

class AuthenticationDialogController(
    private val handler: AuthenticationFormHandler,
    private val scheduler: PaperPlatformScheduler,
    private val text: AuthenticationDialogText,
    private val onOutcome: (AuthenticationFormContext, AuthenticationFormResult) -> Unit = { _, _ -> },
) : Listener {
    private val expectedForms = ConcurrentHashMap<UUID, FormType>()
    private val submissions = ConcurrentHashMap.newKeySet<UUID>()

    fun showLogin(player: Player, feedback: Component? = null) {
        expectedForms[player.uniqueId] = FormType.LOGIN
        player.showDialog(createDialog(FormType.LOGIN, player.name, feedback))
    }

    fun showRegistration(player: Player, feedback: Component? = null) {
        expectedForms[player.uniqueId] = FormType.REGISTER
        player.showDialog(createDialog(FormType.REGISTER, player.name, feedback))
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        val connection = event.commonConnection as? PlayerGameConnection ?: return
        val player = connection.player
        if (event.identifier == CANCEL) {
            when (expectedForms[player.uniqueId]) {
                FormType.LOGIN -> scheduler.entity(player, Runnable { showLogin(player) })
                FormType.REGISTER -> scheduler.entity(player, Runnable { showRegistration(player) })
                null -> Unit
            }
            return
        }
        val type = when (event.identifier) {
            LOGIN_SUBMIT -> FormType.LOGIN
            REGISTER_SUBMIT -> FormType.REGISTER
            else -> return
        }
        if (expectedForms[player.uniqueId] != type) return
        val response = event.dialogResponseView ?: return
        val password = response.getText(PASSWORD_KEY)?.toCharArray() ?: return
        val context = context(player)

        if (type == FormType.REGISTER) {
            val repeated = response.getText(REPEAT_PASSWORD_KEY)?.toCharArray()
            if (repeated == null || !PasswordConfirmation.matches(password, repeated)) {
                password.fill('\u0000')
                repeated?.fill('\u0000')
                scheduler.entity(player, Runnable { showRegistration(player, text.passwordMismatch) })
                return
            }
            repeated.fill('\u0000')
        }
        if (!submissions.add(player.uniqueId)) {
            password.fill('\u0000')
            return
        }
        val outcome = try {
            if (type == FormType.REGISTER) handler.submitRegistration(context, password)
            else handler.submitLogin(context, password)
        } catch (_: Throwable) {
            submissions.remove(player.uniqueId)
            password.fill('\u0000')
            if (type == FormType.REGISTER) showRegistration(player, text.internalFailure) else showLogin(player, text.internalFailure)
            return
        }

        outcome.whenComplete { result, failure ->
            scheduler.entity(player, Runnable {
                submissions.remove(player.uniqueId)
                if (!player.isOnline) return@Runnable
                if (failure == null && result != null) onOutcome(context, result)
                if (failure == null && result == AuthenticationFormResult.AUTHENTICATED) {
                    expectedForms.remove(player.uniqueId)
                    player.closeDialog()
                } else if (type == FormType.LOGIN) {
                    showLogin(player, if (failure == null) feedback(result) else text.internalFailure)
                } else {
                    showRegistration(player, if (failure == null) feedback(result) else text.internalFailure)
                }
            })
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        expectedForms.remove(event.player.uniqueId)
        submissions.remove(event.player.uniqueId)
    }

    private fun context(player: Player) = AuthenticationFormContext(
        ConnectionId(player.uniqueId),
        AccountUsername.parse(player.name),
        player.address.address,
        player.uniqueId,
    )

    private fun createDialog(type: FormType, username: String, feedback: Component?): Dialog = Dialog.create { builder ->
        val inputs = mutableListOf(
            DialogInput.text(PASSWORD_KEY, text.passwordLabel).width(300).maxLength(128).build(),
        )
        if (type == FormType.REGISTER) {
            inputs += DialogInput.text(REPEAT_PASSWORD_KEY, text.repeatPasswordLabel).width(300).maxLength(128).build()
        }
        val submitKey = if (type == FormType.LOGIN) LOGIN_SUBMIT else REGISTER_SUBMIT
        val body = mutableListOf(DialogBody.plainMessage(
            if (type == FormType.LOGIN) text.loginPrompt else text.registrationPrompt, 360,
        ))
        if (feedback != null) body += DialogBody.plainMessage(feedback, 360)
        builder.empty()
            .base(DialogBase.builder(authenticationDialogTitle(
                if (type == FormType.LOGIN) text.loginTitle else text.registrationTitle,
                username,
            ))
                .canCloseWithEscape(false)
                .pause(false)
                .body(body)
                .inputs(inputs)
                .build())
            .type(DialogType.confirmation(
                ActionButton.builder(text.submitLabel).action(DialogAction.customClick(submitKey, null)).build(),
                ActionButton.builder(text.cancelLabel).action(DialogAction.customClick(CANCEL, null)).build(),
            ))
    }

    private enum class FormType { LOGIN, REGISTER }

    private fun feedback(result: AuthenticationFormResult?): Component = when (result) {
        AuthenticationFormResult.INVALID_CREDENTIALS -> text.invalidCredentials
        AuthenticationFormResult.ACCOUNT_LOCKED -> text.accountLocked
        AuthenticationFormResult.RATE_LIMITED -> text.rateLimited
        AuthenticationFormResult.ACCOUNT_ALREADY_EXISTS -> text.accountExists
        AuthenticationFormResult.IDENTITY_CONFLICT -> text.identityConflict
        else -> text.internalFailure
    }

    companion object {
        private const val PASSWORD_KEY = "password"
        private const val REPEAT_PASSWORD_KEY = "repeat_password"
        private val LOGIN_SUBMIT = Key.key("authgatewayx:login_submit")
        private val REGISTER_SUBMIT = Key.key("authgatewayx:register_submit")
        private val CANCEL = Key.key("authgatewayx:auth_cancel")
    }
}

internal fun authenticationDialogTitle(template: Component, username: String): Component =
    template.replaceText(TextReplacementConfig.builder()
        .matchLiteral("{username}")
        .replacement(Component.text(username))
        .build())
