@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

internal fun showAdminReportDialog(
    player: Player,
    title: Component,
    sections: List<Component>,
    closeLabel: Component,
) {
    val body = sections.ifEmpty { listOf(Component.empty()) }
        .map { DialogBody.plainMessage(it, 560) }
    player.showDialog(Dialog.create { builder ->
        builder.empty()
            .base(DialogBase.builder(title)
                .canCloseWithEscape(true)
                .pause(false)
                .body(body)
                .build())
            .type(DialogType.notice(ActionButton.builder(closeLabel).build()))
    })
}
