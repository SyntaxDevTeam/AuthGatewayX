package pl.syntaxdevteam.logincorex.paper.dialog

import org.bukkit.entity.Player
import pl.syntaxdevteam.logincorex.auth.premium.MojangIdentityHandoffDecision
import pl.syntaxdevteam.logincorex.auth.premium.MojangIdentityHandoffPolicy
import pl.syntaxdevteam.logincorex.auth.premium.VerifiedMojangAuthenticationContext
import pl.syntaxdevteam.logincorex.auth.premium.VerifiedMojangAuthenticationResult
import pl.syntaxdevteam.logincorex.auth.premium.VerifiedMojangAuthenticationUseCase
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import pl.syntaxdevteam.logincorex.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.logincorex.integrations.mojang.MojangProfileLookupResult
import pl.syntaxdevteam.logincorex.integrations.LoginAdmissionDecision
import pl.syntaxdevteam.logincorex.integrations.LoginAdmissionService
import pl.syntaxdevteam.logincorex.paper.isolation.PreAuthAccess
import pl.syntaxdevteam.logincorex.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.logincorex.storage.AccountStorage
import java.util.UUID

class AuthenticationDialogRouter(
    private val storage: AccountStorage,
    private val dialogs: AuthenticationDialogController,
    private val migrationDialogs: PremiumMigrationDialogController,
    private val access: PreAuthAccess,
    private val scheduler: PaperPlatformScheduler,
    private val premiumLookup: MojangProfileIdentityLookup,
    private val mojangAuthentication: VerifiedMojangAuthenticationUseCase,
    private val loginAdmission: LoginAdmissionService,
    private val integrationDeniedMessage: net.kyori.adventure.text.Component,
    private val onAdmissionDenied: (AccountUsername, java.net.InetAddress, String) -> Unit,
    private val premiumAuthenticationRequiredMessage: net.kyori.adventure.text.Component,
    private val lookupUnavailableMessage: net.kyori.adventure.text.Component,
    private val identityConflictMessage: net.kyori.adventure.text.Component,
    private val migrationInProgressMessage: net.kyori.adventure.text.Component,
    private val internalFailureMessage: net.kyori.adventure.text.Component,
    private val onMojangActivated: (Player) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    fun route(player: Player) {
        val username = AccountUsername.parse(player.name)
        val sourceAddress = player.address?.address
        if (sourceAddress == null) {
            player.kick(internalFailureMessage)
            return
        }
        loginAdmission.check(username, player.uniqueId, sourceAddress).whenComplete { decision, admissionFailure ->
            scheduler.entity(player, Runnable {
                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                if (admissionFailure != null || decision == null || decision is LoginAdmissionDecision.Deny) {
                    admissionFailure?.let(onFailure)
                    onAdmissionDenied(
                        username,
                        sourceAddress,
                        (decision as? LoginAdmissionDecision.Deny)?.reasonCode ?: "INTEGRATION_FAILURE",
                    )
                    player.kick(integrationDeniedMessage)
                } else {
                    routeAfterAdmission(player, username)
                }
            })
        }
    }

    private fun routeAfterAdmission(player: Player, username: AccountUsername) {
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
                passwordRequired = !player.hasPermission(PREMIUM_PASSWORD_BYPASS_PERMISSION),
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
                    is VerifiedMojangAuthenticationResult.PasswordRequired -> {
                        if (!access.isPreAuth(player.uniqueId)) return@Runnable
                        storage.findPasswordHash(authenticationResult.account.id).whenComplete { hash, failure ->
                            scheduler.entity(player, Runnable {
                                if (!player.isOnline || !access.isPreAuth(player.uniqueId)) return@Runnable
                                if (failure != null) {
                                    onFailure(failure)
                                    player.kick(internalFailureMessage)
                                } else if (hash == null) dialogs.showRegistration(player, verifiedMojang = true)
                                else dialogs.showLogin(player, verifiedMojang = true)
                            })
                        }
                    }
                    is VerifiedMojangAuthenticationResult.MigrationRequired -> {
                        if (!access.isPreAuth(player.uniqueId)) return@Runnable
                        migrationDialogs.show(
                            player,
                            authenticationResult.account,
                            authenticationResult.targetMinecraftUuid,
                        )
                    }
                    is VerifiedMojangAuthenticationResult.MigrationInProgress ->
                        player.kick(migrationInProgressMessage)
                    VerifiedMojangAuthenticationResult.IdentityConflict -> player.kick(identityConflictMessage)
                    VerifiedMojangAuthenticationResult.AccessDenied -> player.kick(integrationDeniedMessage)
                }
            })
        }
    }

    private companion object {
        const val PREMIUM_PASSWORD_BYPASS_PERMISSION = "logincorex.auth.skip-password"
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
