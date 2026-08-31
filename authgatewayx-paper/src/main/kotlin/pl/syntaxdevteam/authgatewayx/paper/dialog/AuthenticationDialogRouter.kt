package pl.syntaxdevteam.authgatewayx.paper.dialog

import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.premium.MojangIdentityHandoffDecision
import pl.syntaxdevteam.authgatewayx.auth.premium.MojangIdentityHandoffPolicy
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationContext
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationResult
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationUseCase
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookupResult
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthAccess
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import java.util.UUID

class AuthenticationDialogRouter(
    private val storage: AccountStorage,
    private val dialogs: AuthenticationDialogController,
    private val access: PreAuthAccess,
    private val scheduler: PaperPlatformScheduler,
    private val premiumLookup: MojangProfileIdentityLookup,
    private val mojangAuthentication: VerifiedMojangAuthenticationUseCase,
    private val premiumAuthenticationRequiredMessage: net.kyori.adventure.text.Component,
    private val lookupUnavailableMessage: net.kyori.adventure.text.Component,
    private val identityConflictMessage: net.kyori.adventure.text.Component,
    private val internalFailureMessage: net.kyori.adventure.text.Component,
    private val onMojangActivated: (Player) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    fun route(player: Player) {
        val username = AccountUsername.parse(player.name)
        premiumLookup.lookupProfile(username).whenComplete { result, lookupFailure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                if (lookupFailure != null || result == null || result == MojangProfileLookupResult.Unavailable) {
                    lookupFailure?.let(onFailure)
                    player.kick(lookupUnavailableMessage)
                    return@Runnable
                }
                when (result) {
                    is MojangProfileLookupResult.Premium -> routePremium(player, username, result.minecraftUuid)
                    MojangProfileLookupResult.NotPremium -> routeOffline(player, username)
                    MojangProfileLookupResult.Unavailable -> player.kick(lookupUnavailableMessage)
                }
            })
        }
    }

    private fun routePremium(player: Player, username: AccountUsername, officialUuid: UUID) {
        if (MojangIdentityHandoffPolicy.decide(player.uniqueId, officialUuid) !=
            MojangIdentityHandoffDecision.ALLOW_VERIFIED_MOJANG
        ) {
            player.kick(premiumAuthenticationRequiredMessage)
            return
        }
        val sourceAddress = player.address?.address
        if (sourceAddress == null) {
            player.kick(internalFailureMessage)
            return
        }
        mojangAuthentication.authenticate(
            VerifiedMojangAuthenticationContext(
                ConnectionId(player.uniqueId), username, sourceAddress, officialUuid,
            ),
        ).whenComplete { authenticationResult, authenticationFailure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline) return@Runnable
                if (authenticationFailure != null || authenticationResult == null) {
                    authenticationFailure?.let(onFailure)
                    player.kick(internalFailureMessage)
                    return@Runnable
                }
                when (authenticationResult) {
                    is VerifiedMojangAuthenticationResult.Success -> onMojangActivated(player)
                    VerifiedMojangAuthenticationResult.IdentityConflict -> player.kick(identityConflictMessage)
                }
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
