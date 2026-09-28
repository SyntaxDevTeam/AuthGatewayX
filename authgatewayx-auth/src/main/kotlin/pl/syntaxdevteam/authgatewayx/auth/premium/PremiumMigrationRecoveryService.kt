package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.PremiumRecoveryPreparationResult
import java.net.InetAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

sealed interface PremiumRecoveryCandidateResult {
    data class Ready(
        val account: AuthAccount,
        val sourceMinecraftUuid: UUID,
        val inspections: List<PremiumMigrationProviderInspection>,
    ) : PremiumRecoveryCandidateResult

    data class NoEvidence(
        val account: AuthAccount,
        val sourceMinecraftUuid: UUID,
        val inspections: List<PremiumMigrationProviderInspection>,
    ) : PremiumRecoveryCandidateResult

    data object AccountNotFound : PremiumRecoveryCandidateResult
    data object AccountNotPremium : PremiumRecoveryCandidateResult
    data object IdentityConflict : PremiumRecoveryCandidateResult
}

sealed interface PremiumRecoveryStartResult {
    data class Prepared(
        val ticket: pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket,
        val inspections: List<PremiumMigrationProviderInspection>,
    ) : PremiumRecoveryStartResult

    data class NoEvidence(
        val sourceMinecraftUuid: UUID,
        val inspections: List<PremiumMigrationProviderInspection>,
    ) : PremiumRecoveryStartResult

    data object AccountNotFound : PremiumRecoveryStartResult
    data object AccountNotPremium : PremiumRecoveryStartResult
    data object IdentityConflict : PremiumRecoveryStartResult
}

class PremiumMigrationRecoveryService(
    private val storage: AccountStorage,
    private val coordinator: PremiumMigrationCoordinator,
    private val auditSink: SecurityAuditSink,
    private val dispatchInspection: (Runnable) -> Unit = { it.run() },
    private val clock: Clock = Clock.systemUTC(),
) {
    fun inspectCandidate(
        username: AccountUsername,
        explicitSourceMinecraftUuid: UUID? = null,
    ): CompletionStage<PremiumRecoveryCandidateResult> =
        storage.findByUsername(username).thenCompose { account ->
            when {
                account == null ->
                    CompletableFuture.completedFuture(PremiumRecoveryCandidateResult.AccountNotFound)
                account.identityType != IdentityType.MOJANG ->
                    CompletableFuture.completedFuture(PremiumRecoveryCandidateResult.AccountNotPremium)
                else -> {
                    val sourceUuid = explicitSourceMinecraftUuid ?: OfflineIdentity.minecraftUuid(account.username)
                    if (sourceUuid == account.minecraftUuid) {
                        CompletableFuture.completedFuture(PremiumRecoveryCandidateResult.IdentityConflict)
                    } else {
                        val context = IdentityMigrationContext(
                            UUID.randomUUID(),
                            account.id.value,
                            account.username.value,
                            sourceUuid,
                            account.minecraftUuid,
                        )
                        val result = CompletableFuture<PremiumRecoveryCandidateResult>()
                        dispatchInspection(Runnable {
                            coordinator.inspect(context).whenComplete { inspections, failure ->
                                if (failure != null) {
                                    result.completeExceptionally(failure)
                                } else if (inspections == null) {
                                    result.completeExceptionally(IllegalStateException("Migration inspection returned null"))
                                } else if (hasLegacyEvidence(inspections)) {
                                    result.complete(PremiumRecoveryCandidateResult.Ready(account, sourceUuid, inspections))
                                } else {
                                    result.complete(PremiumRecoveryCandidateResult.NoEvidence(account, sourceUuid, inspections))
                                }
                            }
                        })
                        result
                    }
                }
            }
        }

    fun prepare(
        username: AccountUsername,
        sourceAddress: InetAddress,
        explicitSourceMinecraftUuid: UUID? = null,
    ): CompletionStage<PremiumRecoveryStartResult> =
        inspectCandidate(username, explicitSourceMinecraftUuid).thenCompose { candidate ->
            when (candidate) {
                is PremiumRecoveryCandidateResult.Ready ->
                    storage.preparePremiumRecovery(
                        candidate.account.id,
                        candidate.account.username,
                        candidate.sourceMinecraftUuid,
                        candidate.account.minecraftUuid,
                        sourceAddress,
                        clock.instant(),
                    ).thenApply { prepared ->
                        when (prepared) {
                            is PremiumRecoveryPreparationResult.Prepared -> {
                                runCatching {
                                    auditSink.record(
                                        SecurityEvent(
                                            clock.instant(),
                                            prepared.ticket.accountId.value,
                                            prepared.ticket.targetMinecraftUuid,
                                            prepared.ticket.username.value,
                                            sourceAddress,
                                            SecurityEventType.PREMIUM_RECOVERY_PREPARED,
                                            "LEGACY_UUID_EVIDENCE_CONFIRMED",
                                        ),
                                    )
                                }
                                PremiumRecoveryStartResult.Prepared(prepared.ticket, candidate.inspections)
                            }
                            PremiumRecoveryPreparationResult.AccountNotPremium ->
                                PremiumRecoveryStartResult.AccountNotPremium
                            PremiumRecoveryPreparationResult.IdentityConflict ->
                                PremiumRecoveryStartResult.IdentityConflict
                        }
                    }
                is PremiumRecoveryCandidateResult.NoEvidence ->
                    CompletableFuture.completedFuture(
                        PremiumRecoveryStartResult.NoEvidence(
                            candidate.sourceMinecraftUuid,
                            candidate.inspections,
                        ),
                    )
                PremiumRecoveryCandidateResult.AccountNotFound ->
                    CompletableFuture.completedFuture(PremiumRecoveryStartResult.AccountNotFound)
                PremiumRecoveryCandidateResult.AccountNotPremium ->
                    CompletableFuture.completedFuture(PremiumRecoveryStartResult.AccountNotPremium)
                PremiumRecoveryCandidateResult.IdentityConflict ->
                    CompletableFuture.completedFuture(PremiumRecoveryStartResult.IdentityConflict)
            }
        }

    private fun hasLegacyEvidence(inspections: List<PremiumMigrationProviderInspection>): Boolean =
        inspections.any { it.legacyEvidence }
}
