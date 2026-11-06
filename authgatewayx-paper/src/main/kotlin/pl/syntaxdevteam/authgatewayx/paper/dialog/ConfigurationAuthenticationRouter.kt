@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.dialog

import io.papermc.paper.connection.PlayerConfigurationConnection
import net.kyori.adventure.text.Component
import pl.syntaxdevteam.authgatewayx.auth.premium.MojangIdentityHandoffDecision
import pl.syntaxdevteam.authgatewayx.auth.premium.MojangIdentityHandoffPolicy
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationContext
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationResult
import pl.syntaxdevteam.authgatewayx.auth.premium.VerifiedMojangAuthenticationUseCase
import pl.syntaxdevteam.authgatewayx.auth.session.PreJoinConnectionGate
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.ui.AuthenticationFormContext
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionState
import pl.syntaxdevteam.authgatewayx.integrations.LoginAdmissionDecision
import pl.syntaxdevteam.authgatewayx.integrations.LoginAdmissionService
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookupResult
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage

/**
 * Pre-world routing and admission based solely on connection metadata.
 *
 * The caller must create an AuthSession in PRE_AUTH before routing, and await the same ticket
 * from AsyncPlayerConnectionConfigureEvent. Never route a migration through an unauthenticated
 * Player object: migration authorization is handled by a configuration-phase dialog,
 * and the physical migration runs only after disconnect.
 */
class ConfigurationAuthenticationRouter(
    private val storage: AccountStorage,
    private val dialogs: ConfigurationAuthenticationDialogController,
    private val migrationDialogs: ConfigurationPremiumMigrationDialogController,
    private val sessions: SessionRegistry,
    private val gate: PreJoinConnectionGate,
    private val lookup: MojangProfileIdentityLookup,
    private val mojang: VerifiedMojangAuthenticationUseCase,
    private val admission: LoginAdmissionService,
    private val integrationDeniedMessage: Component,
    private val premiumRequiredMessage: Component,
    private val lookupUnavailableMessage: Component,
    private val identityConflictMessage: Component,
    private val migrationRequiredMessage: Component,
    private val migrationInProgressMessage: Component,
    private val internalFailureMessage: Component,
    private val onMojangActivated: (java.util.UUID) -> Unit,
    private val onAdmissionDenied: (AuthenticationFormContext, String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    fun route(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ) {
        if (!current(connection, ticket, context)) return
        try {
            admission.check(context.username, context.minecraftUuid, context.sourceAddress)
                .whenComplete { decision, failure ->
                    if (!current(connection, ticket, context)) return@whenComplete
                    if (failure != null || decision == null || decision is LoginAdmissionDecision.Deny) {
                        if (failure != null) runCatching { onFailure(failure) }
                        runCatching {
                            onAdmissionDenied(context, (decision as? LoginAdmissionDecision.Deny)?.reasonCode ?: "INTEGRATION_FAILURE")
                        }
                        deny(connection, ticket, integrationDeniedMessage)
                    } else {
                        routeIdentity(connection, ticket, context)
                    }
                }
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
            deny(connection, ticket, internalFailureMessage)
        }
    }

    private fun routeIdentity(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ) {
        try {
            lookup.lookupProfile(context.username).whenComplete { result, failure ->
                if (!current(connection, ticket, context)) return@whenComplete
                if (failure != null || result == null || result == MojangProfileLookupResult.Unavailable) {
                    if (failure != null) runCatching { onFailure(failure) }
                    deny(connection, ticket, lookupUnavailableMessage)
                    return@whenComplete
                }
                when (result) {
                    is MojangProfileLookupResult.Premium ->
                        routePremium(connection, ticket, context, result.minecraftUuid)
                    MojangProfileLookupResult.NotPremium -> routeOffline(connection, ticket, context)
                    MojangProfileLookupResult.Unavailable -> deny(connection, ticket, lookupUnavailableMessage)
                }
            }
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
            deny(connection, ticket, lookupUnavailableMessage)
        }
    }

    private fun routePremium(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
        officialUuid: java.util.UUID,
    ) {
        if (MojangIdentityHandoffPolicy.decide(context.minecraftUuid, officialUuid) !=
            MojangIdentityHandoffDecision.ALLOW_VERIFIED_MOJANG
        ) {
            deny(connection, ticket, premiumRequiredMessage)
            return
        }
        try {
            mojang.authenticate(VerifiedMojangAuthenticationContext(
                context.connectionId, context.username, context.sourceAddress, officialUuid,
            )).whenComplete { result, failure ->
                if (!liveConnection(connection, ticket, context)) return@whenComplete
                if (failure != null || result == null) {
                    if (failure != null) runCatching { onFailure(failure) }
                    deny(connection, ticket, internalFailureMessage)
                    return@whenComplete
                }
                when (result) {
                    is VerifiedMojangAuthenticationResult.Success -> {
                        val active = sessions.get(context.connectionId)
                        if (active?.state == ConnectionState.ACTIVE && active.minecraftUuid == officialUuid) {
                            if (gate.authenticated(ticket)) runCatching { onMojangActivated(context.minecraftUuid) }
                        } else deny(connection, ticket, internalFailureMessage)
                    }
                    is VerifiedMojangAuthenticationResult.MigrationRequired -> {
                        val shown = migrationDialogs.show(
                            connection, ticket, context, result.account, result.targetMinecraftUuid,
                        )
                        if (!shown) deny(connection, ticket, migrationRequiredMessage)
                    }
                    is VerifiedMojangAuthenticationResult.MigrationInProgress ->
                        deny(connection, ticket, migrationInProgressMessage)
                    VerifiedMojangAuthenticationResult.IdentityConflict ->
                        deny(connection, ticket, identityConflictMessage)
                    VerifiedMojangAuthenticationResult.AccessDenied ->
                        deny(connection, ticket, integrationDeniedMessage)
                }
            }
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
            deny(connection, ticket, internalFailureMessage)
        }
    }

    private fun routeOffline(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ) {
        try {
            storage.findByUsername(context.username).whenComplete { account, failure ->
                if (!current(connection, ticket, context)) return@whenComplete
                if (failure != null) {
                    runCatching { onFailure(failure) }
                    deny(connection, ticket, lookupUnavailableMessage)
                } else {
                    val shown = if (account == null) dialogs.showRegistration(connection, ticket, context)
                        else dialogs.showLogin(connection, ticket, context)
                    if (!shown) deny(connection, ticket, internalFailureMessage)
                }
            }
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
            deny(connection, ticket, internalFailureMessage)
        }
    }

    private fun liveConnection(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ) = gate.isPending(ticket) && connection.isConnected &&
        context.connectionId == ticket.connectionId

    private fun current(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        context: AuthenticationFormContext,
    ) = liveConnection(connection, ticket, context) &&
        sessions.get(context.connectionId)?.state == ConnectionState.PRE_AUTH

    private fun deny(
        connection: PlayerConfigurationConnection,
        ticket: PreJoinConnectionGate.Ticket,
        reason: Component,
    ) {
        if (gate.deny(ticket) && connection.isConnected) connection.disconnect(reason)
    }
}
