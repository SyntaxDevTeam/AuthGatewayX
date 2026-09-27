package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.auth.login.LoginResult
import pl.syntaxdevteam.authgatewayx.auth.login.OfflineLoginUseCase
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.net.InetAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

sealed interface PremiumMigrationAuthorizationResult {
    data class Prepared(val ticket: PremiumMigrationTicket) : PremiumMigrationAuthorizationResult
    data object InvalidCredentials : PremiumMigrationAuthorizationResult
    data object AccountLocked : PremiumMigrationAuthorizationResult
    data object RateLimited : PremiumMigrationAuthorizationResult
    data object IdentityConflict : PremiumMigrationAuthorizationResult
}

class PremiumMigrationService(
    private val login: OfflineLoginUseCase,
    private val storage: AccountStorage,
    private val auditSink: SecurityAuditSink,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun authorize(
        username: AccountUsername,
        sourceAddress: InetAddress,
        targetMinecraftUuid: UUID,
        password: CharArray,
    ): CompletionStage<PremiumMigrationAuthorizationResult> =
        login.login(username, sourceAddress, password).thenCompose { loginResult ->
            when (loginResult) {
                is LoginResult.Success -> {
                    val sourceAccount = loginResult.account
                    storage.preparePremiumMigration(
                        sourceAccount.id,
                        sourceAccount.username,
                        sourceAccount.minecraftUuid,
                        targetMinecraftUuid,
                        sourceAddress,
                        clock.instant(),
                    ).thenApply { prepared ->
                        when (prepared) {
                            is PremiumMigrationPreparationResult.Prepared -> {
                                audit(
                                    prepared.ticket,
                                    SecurityEventType.PREMIUM_MIGRATION_PREPARED,
                                    "OFFLINE_PASSWORD_VERIFIED",
                                )
                                PremiumMigrationAuthorizationResult.Prepared(prepared.ticket)
                            }
                            PremiumMigrationPreparationResult.IdentityConflict ->
                                PremiumMigrationAuthorizationResult.IdentityConflict
                        }
                    }
                }
                LoginResult.InvalidCredentials ->
                    completed(PremiumMigrationAuthorizationResult.InvalidCredentials)
                LoginResult.AccountLocked ->
                    completed(PremiumMigrationAuthorizationResult.AccountLocked)
                LoginResult.RateLimited ->
                    completed(PremiumMigrationAuthorizationResult.RateLimited)
            }
        }

    private fun audit(ticket: PremiumMigrationTicket, type: SecurityEventType, reason: String) {
        runCatching {
            auditSink.record(
                SecurityEvent(
                    clock.instant(),
                    ticket.accountId.value,
                    ticket.targetMinecraftUuid,
                    ticket.username.value,
                    ticket.sourceAddress,
                    type,
                    reason,
                ),
            )
        }
    }

    private fun completed(result: PremiumMigrationAuthorizationResult): CompletionStage<PremiumMigrationAuthorizationResult> =
        CompletableFuture.completedFuture(result)
}
