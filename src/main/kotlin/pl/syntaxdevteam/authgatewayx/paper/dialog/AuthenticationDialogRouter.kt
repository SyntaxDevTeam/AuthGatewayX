package pl.syntaxdevteam.authgatewayx.paper.dialog

import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthAccess
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage

class AuthenticationDialogRouter(
    private val storage: AccountStorage,
    private val dialogs: AuthenticationDialogController,
    private val access: PreAuthAccess,
    private val scheduler: PaperPlatformScheduler,
    private val offlineTestAllowed: (String) -> Boolean,
    private val identityUnavailableMessage: net.kyori.adventure.text.Component,
    private val onFailure: (Throwable) -> Unit,
) {
    fun route(player: Player) {
        if (!offlineTestAllowed(player.name)) {
            scheduler.entity(player, Runnable { if (player.isOnline) player.kick(identityUnavailableMessage) })
            return
        }
        storage.findByUsername(AccountUsername.parse(player.name)).whenComplete { account, failure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                if (failure != null) {
                    onFailure(failure)
                    player.kick(net.kyori.adventure.text.Component.text("Authentication storage failure"))
                } else if (account == null) {
                    dialogs.showRegistration(player)
                } else {
                    dialogs.showLogin(player)
                }
            })
        }
    }
}
