@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.dialog

import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import pl.syntaxdevteam.authgatewayx.api.craftconnect.CraftConnectDevice
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All user-facing strings are supplied by MessageHandler from the platform composition root. */
data class CraftConnectPairingDialogText(
    val title: Component,
    val prompt: Component,
    val approve: Component,
    val reject: Component,
)

data class CraftConnectPairingApproval(
    val requestId: UUID,
    val playerUuid: UUID,
    val device: CraftConnectDevice,
)

/**
 * Explicit human approval gate used only after cryptographic proof-of-possession.
 *
 * The controller is deliberately in-memory. Persistence happens only through the
 * supplied decision callback after APPROVE. A timeout, quit, replacement request
 * or REJECT can never create a pairing record.
 */
class CraftConnectPairingDialogController(
    private val text: CraftConnectPairingDialogText,
    private val onDecision: (CraftConnectPairingApproval, Boolean) -> Unit,
    private val timeout: Duration = Duration.ofMinutes(2),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Listener {
    private data class Pending(
        val approval: CraftConnectPairingApproval,
        val expiresAtMillis: Long,
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    init {
        require(!timeout.isNegative && !timeout.isZero)
    }

    fun show(player: Player, requestId: UUID, device: CraftConnectDevice) {
        if (!player.isOnline) return
        val approval = CraftConnectPairingApproval(requestId, player.uniqueId, device.copy(publicKey = device.publicKey.copyOf()))
        pending[player.uniqueId] = Pending(approval, clockMillis() + timeout.toMillis())
        player.showDialog(createDialog(player, approval))
    }

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.identifier != APPROVE && event.identifier != REJECT) return
        val connection = event.commonConnection as? PlayerGameConnection ?: return
        val player = connection.player
        val entry = pending.remove(player.uniqueId) ?: return
        if (entry.approval.playerUuid != player.uniqueId || entry.expiresAtMillis <= clockMillis()) {
            player.closeDialog()
            return
        }

        player.closeDialog()
        onDecision(entry.approval, event.identifier == APPROVE)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)
    }

    fun cancel(playerUuid: UUID) {
        pending.remove(playerUuid)
    }

    private fun createDialog(player: Player, approval: CraftConnectPairingApproval): Dialog = Dialog.create { builder ->
        val prompt = text.prompt
            .replaceText(TextReplacementConfig.builder()
                .matchLiteral("{player}")
                .replacement(Component.text(player.name))
                .build())
            .replaceText(TextReplacementConfig.builder()
                .matchLiteral("{device}")
                .replacement(Component.text(approval.device.deviceId))
                .build())

        builder.empty()
            .base(
                DialogBase.builder(text.title)
                    .canCloseWithEscape(false)
                    .pause(false)
                    .body(listOf(DialogBody.plainMessage(prompt, 380)))
                    .build(),
            )
            .type(
                DialogType.confirmation(
                    ActionButton.builder(text.approve).action(DialogAction.customClick(APPROVE, null)).build(),
                    ActionButton.builder(text.reject).action(DialogAction.customClick(REJECT, null)).build(),
                ),
            )
    }

    companion object {
        private val APPROVE = Key.key("authgatewayx:craftconnect_pair_approve")
        private val REJECT = Key.key("authgatewayx:craftconnect_pair_reject")
    }
}
