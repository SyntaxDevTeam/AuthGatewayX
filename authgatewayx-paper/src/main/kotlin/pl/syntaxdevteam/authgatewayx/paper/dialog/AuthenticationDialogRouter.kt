package pl.syntaxdevteam.authgatewayx.paper.dialog

import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthAccess
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.integrations.mojang.PremiumUsernameLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.PremiumUsernameStatus

class AuthenticationDialogRouter(
    private val storage: AccountStorage,
    private val dialogs: AuthenticationDialogController,
    private val access: PreAuthAccess,
    private val scheduler: PaperPlatformScheduler,
    private val premiumLookup: PremiumUsernameLookup,
    private val premiumAuthenticationRequiredMessage: net.kyori.adventure.text.Component,
    private val lookupUnavailableMessage: net.kyori.adventure.text.Component,
    private val onFailure: (Throwable) -> Unit,
) {
    fun route(player: Player) {
        val username = AccountUsername.parse(player.name)
        premiumLookup.lookup(username).whenComplete { status, lookupFailure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                if (lookupFailure != null || status == PremiumUsernameStatus.UNAVAILABLE) {
                    lookupFailure?.let(onFailure)
                    player.kick(lookupUnavailableMessage)
                    return@Runnable
                }
                if (status == PremiumUsernameStatus.PREMIUM) {
                    player.kick(premiumAuthenticationRequiredMessage)
                    return@Runnable
                }
                routeOffline(player, username)
            })
        }
    }

    private fun routeOffline(player: Player, username: AccountUsername) {
        storage.findByUsername(username).whenComplete { account, failure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                if (failure != null) {
                    onFailure(failure)
                    player.kick(lookupUnavailableMessage)
                } else if (account == null) dialogs.showRegistration(player) else dialogs.showLogin(player)
            })
        }
    }
}
