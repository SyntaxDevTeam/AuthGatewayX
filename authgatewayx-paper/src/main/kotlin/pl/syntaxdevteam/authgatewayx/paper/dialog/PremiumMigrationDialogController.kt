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
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationAuthorizationResult
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationService
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PremiumMigrationDialogText(
    val title: Component,
    val prompt: Component,
    val passwordLabel: Component,
    val submitLabel: Component,
    val cancelLabel: Component,
    val invalidCredentials: Component,
    val accountLocked: Component,
    val rateLimited: Component,
    val identityConflict: Component,
    val prepared: Component,
    val cancelled: Component,
    val internalFailure: Component,
)

class PremiumMigrationDialogController(
    private val service: PremiumMigrationService,
    private val scheduler: PaperPlatformScheduler,
    private val text: PremiumMigrationDialogText,
    private val onPrepared: (Player, PremiumMigrationTicket) -> Unit,
) : Listener {
    private data class Form(
        val account: AuthAccount,
        val targetMinecraftUuid: UUID,
    )

    private val forms = ConcurrentHashMap<UUID, Form>()
    private val submissions = ConcurrentHashMap.newKeySet<UUID>()

    fun show(player: Player, account: AuthAccount, targetMinecraftUuid: UUID, feedback: Component? = null) {
        forms[player.uniqueId] = Form(account, targetMinecraftUuid)
        player.showDialog(createDialog(account, targetMinecraftUuid, feedback))
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        val form = forms[player.uniqueId] ?: return

        if (event.identifier == CANCEL) {
            forms.remove(player.uniqueId)
            submissions.remove(player.uniqueId)
            scheduler.entity(player, Runnable {
                if (player.isOnline) player.kick(text.cancelled)
            })
            return
        }
        if (event.identifier != SUBMIT) return

        val response = event.dialogResponseView ?: return
        val password = response.getText(PASSWORD)?.toCharArray() ?: return
        val sourceAddress = player.address?.address ?: run {
            password.fill('\u0000')
            player.kick(text.internalFailure)
            return
        }
        if (!submissions.add(player.uniqueId)) {
            password.fill('\u0000')
            return
        }

        val stage = try {
            service.authorize(
                form.account.username,
                sourceAddress,
                form.targetMinecraftUuid,
                password,
            )
        } catch (_: Throwable) {
            submissions.remove(player.uniqueId)
            password.fill('\u0000')
            show(player, form.account, form.targetMinecraftUuid, text.internalFailure)
            return
        }

        stage.whenComplete { result, failure ->
            scheduler.entity(player, Runnable {
                submissions.remove(player.uniqueId)
                if (!player.isOnline) return@Runnable
                if (failure != null || result == null) {
                    show(player, form.account, form.targetMinecraftUuid, text.internalFailure)
                    return@Runnable
                }
                when (result) {
                    is PremiumMigrationAuthorizationResult.Prepared -> {
                        forms.remove(player.uniqueId)
                        player.closeDialog()
                        onPrepared(player, result.ticket)
                    }
                    PremiumMigrationAuthorizationResult.InvalidCredentials ->
                        show(player, form.account, form.targetMinecraftUuid, text.invalidCredentials)
                    PremiumMigrationAuthorizationResult.AccountLocked ->
                        show(player, form.account, form.targetMinecraftUuid, text.accountLocked)
                    PremiumMigrationAuthorizationResult.RateLimited ->
                        show(player, form.account, form.targetMinecraftUuid, text.rateLimited)
                    PremiumMigrationAuthorizationResult.IdentityConflict ->
                        show(player, form.account, form.targetMinecraftUuid, text.identityConflict)
                }
            })
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        forms.remove(event.player.uniqueId)
        submissions.remove(event.player.uniqueId)
    }

    private fun createDialog(
        account: AuthAccount,
        targetMinecraftUuid: UUID,
        feedback: Component?,
    ): Dialog = Dialog.create { builder ->
        val body = mutableListOf(
            DialogBody.plainMessage(
                replaceMigrationPlaceholders(text.prompt, account, targetMinecraftUuid),
                380,
            ),
        )
        if (feedback != null) body += DialogBody.plainMessage(feedback, 380)
        builder.empty()
            .base(
                DialogBase.builder(authenticationDialogTitle(text.title, account.username.value))
                    .canCloseWithEscape(false)
                    .pause(false)
                    .body(body)
                    .inputs(
                        listOf(
                            DialogInput.text(PASSWORD, text.passwordLabel)
                                .width(300)
                                .maxLength(128)
                                .build(),
                        ),
                    )
                    .build(),
            )
            .type(
                DialogType.confirmation(
                    ActionButton.builder(text.submitLabel)
                        .action(DialogAction.customClick(SUBMIT, null))
                        .build(),
                    ActionButton.builder(text.cancelLabel)
                        .action(DialogAction.customClick(CANCEL, null))
                        .build(),
                ),
            )
    }

    private fun replaceMigrationPlaceholders(
        component: Component,
        account: AuthAccount,
        targetMinecraftUuid: UUID,
    ): Component = component
        .replaceText(
            TextReplacementConfig.builder()
                .matchLiteral("{old_uuid}")
                .replacement(Component.text(account.minecraftUuid.toString()))
                .build(),
        )
        .replaceText(
            TextReplacementConfig.builder()
                .matchLiteral("{new_uuid}")
                .replacement(Component.text(targetMinecraftUuid.toString()))
                .build(),
        )

    companion object {
        private const val PASSWORD = "migration_password"
        private val SUBMIT = Key.key("authgatewayx:premium_migration_submit")
        private val CANCEL = Key.key("authgatewayx:premium_migration_cancel")
    }
}
