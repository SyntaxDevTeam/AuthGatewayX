package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationCompletionResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationKind
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

data class PremiumMigrationProviderInspection(
    val providerId: String,
    val status: IdentityMigrationInspectionStatus,
    val reasonCode: String,
)

sealed interface PremiumMigrationRunResult {
    data class Completed(val account: AuthAccount) : PremiumMigrationRunResult
    data class Blocked(val providerId: String, val reasonCode: String) : PremiumMigrationRunResult
    data class Failed(val providerId: String?, val reasonCode: String) : PremiumMigrationRunResult
    data object AlreadyRunning : PremiumMigrationRunResult
}

class PremiumMigrationCoordinator(
    private val storage: AccountStorage,
    private val auditSink: SecurityAuditSink,
    private val providers: () -> List<IdentityMigrationProvider>,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val running = ConcurrentHashMap.newKeySet<UUID>()

    fun inspect(ticket: PremiumMigrationTicket): CompletionStage<List<PremiumMigrationProviderInspection>> =
        inspect(
            IdentityMigrationContext(
                ticket.id,
                ticket.accountId.value,
                ticket.username.value,
                ticket.sourceMinecraftUuid,
                ticket.targetMinecraftUuid,
            ),
        )

    fun inspect(context: IdentityMigrationContext): CompletionStage<List<PremiumMigrationProviderInspection>> {
        val providerList = try {
            providers().toList()
        } catch (failure: Throwable) {
            return CompletableFuture.failedFuture(failure)
        }
        val unique = LinkedHashMap<String, IdentityMigrationProvider>()
        for (provider in providerList) {
            if (!PROVIDER_ID.matches(provider.id) || unique.putIfAbsent(provider.id, provider) != null) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("Invalid or duplicate migration provider id: ${provider.id}"),
                )
            }
        }
        if (unique.isEmpty()) {
            return CompletableFuture.failedFuture(IllegalStateException("No migration providers"))
        }
        return inspectSequentially(unique.values.toList(), context).thenApply { inspections ->
            inspections.map { (provider, inspection) ->
                PremiumMigrationProviderInspection(provider.id, inspection.status, inspection.reasonCode)
            }
        }
    }

    fun migrate(ticket: PremiumMigrationTicket): CompletionStage<PremiumMigrationRunResult> {
        if (!running.add(ticket.id)) {
            return CompletableFuture.completedFuture(PremiumMigrationRunResult.AlreadyRunning)
        }

        val operation = runCatching { providers().toList() }
            .fold(
                onSuccess = { providerList -> runMigration(ticket, providerList) },
                onFailure = { failure ->
                    fail(ticket, null, "PROVIDER_DISCOVERY_FAILED_${failure.javaClass.simpleName}")
                },
            )

        return operation.whenComplete { _, _ -> running.remove(ticket.id) }
    }

    private fun runMigration(
        ticket: PremiumMigrationTicket,
        providerList: List<IdentityMigrationProvider>,
    ): CompletionStage<PremiumMigrationRunResult> {
        val unique = LinkedHashMap<String, IdentityMigrationProvider>()
        for (provider in providerList) {
            if (!PROVIDER_ID.matches(provider.id) || unique.putIfAbsent(provider.id, provider) != null) {
                return fail(ticket, provider.id, "INVALID_OR_DUPLICATE_PROVIDER_ID")
            }
        }
        if (unique.isEmpty()) return fail(ticket, null, "NO_MIGRATION_PROVIDERS")

        val context = IdentityMigrationContext(
            ticket.id,
            ticket.accountId.value,
            ticket.username.value,
            ticket.sourceMinecraftUuid,
            ticket.targetMinecraftUuid,
        )
        val ordered = unique.values.toList()

        return inspectSequentially(ordered, context).thenCompose { inspections ->
            val blocked = inspections.firstOrNull { it.second.status == IdentityMigrationInspectionStatus.BLOCKED }
            if (blocked != null) {
                fail(ticket, blocked.first.id, blocked.second.reasonCode).thenApply {
                    PremiumMigrationRunResult.Blocked(blocked.first.id, blocked.second.reasonCode)
                }
            } else {
                val ready = inspections
                    .filter { it.second.status == IdentityMigrationInspectionStatus.READY }
                    .map { it.first }
                storage.markPremiumMigrationStarted(ticket.id, clock.instant()).thenCompose { started ->
                    if (!started) fail(ticket, null, "MIGRATION_STATE_CHANGED")
                    else migrateSequentially(ticket, context, ready, mutableListOf(), 0)
                }
            }
        }.exceptionallyCompose { failure ->
            fail(ticket, null, "MIGRATION_PIPELINE_EXCEPTION_${rootCause(failure).javaClass.simpleName}")
        }
    }

    private fun inspectSequentially(
        providers: List<IdentityMigrationProvider>,
        context: IdentityMigrationContext,
    ): CompletionStage<List<Pair<IdentityMigrationProvider, IdentityMigrationInspection>>> {
        val result = CompletableFuture<List<Pair<IdentityMigrationProvider, IdentityMigrationInspection>>>()
        val inspections = mutableListOf<Pair<IdentityMigrationProvider, IdentityMigrationInspection>>()

        fun next(index: Int) {
            if (index >= providers.size) {
                result.complete(inspections)
                return
            }
            val provider = providers[index]
            val stage = runCatching { provider.inspect(context) }.getOrElse {
                result.completeExceptionally(it)
                return
            }
            stage.whenComplete { inspection, failure ->
                if (failure != null) {
                    result.completeExceptionally(rootCause(failure))
                } else if (inspection == null) {
                    result.completeExceptionally(IllegalStateException("Provider ${provider.id} returned null inspection"))
                } else {
                    inspections += provider to inspection
                    next(index + 1)
                }
            }
        }

        next(0)
        return result
    }

    private fun migrateSequentially(
        ticket: PremiumMigrationTicket,
        context: IdentityMigrationContext,
        ready: List<IdentityMigrationProvider>,
        migrated: MutableList<IdentityMigrationProvider>,
        index: Int,
    ): CompletionStage<PremiumMigrationRunResult> {
        if (index >= ready.size) {
            return storage.completePremiumMigration(ticket.id, clock.instant()).thenCompose { completion ->
                when (completion) {
                    is PremiumMigrationCompletionResult.Completed -> {
                        val eventType = if (ticket.kind == PremiumMigrationKind.RECOVERY) {
                            SecurityEventType.PREMIUM_RECOVERY_COMPLETED
                        } else {
                            SecurityEventType.OFFLINE_TO_PREMIUM_MIGRATION
                        }
                        audit(ticket, eventType, "CONTROLLED_MIGRATION_COMPLETED")
                        CompletableFuture.completedFuture(PremiumMigrationRunResult.Completed(completion.account))
                    }
                    PremiumMigrationCompletionResult.NotFound ->
                        rollbackAndFail(ticket, context, migrated, null, "MIGRATION_TICKET_NOT_FOUND")
                    PremiumMigrationCompletionResult.IdentityConflict ->
                        rollbackAndFail(ticket, context, migrated, null, "IDENTITY_CONFLICT_DURING_FINALIZATION")
                }
            }
        }

        val provider = ready[index]
        val stage = runCatching { provider.migrate(context) }.getOrElse { failure ->
            return rollbackAndFail(
                ticket, context, migrated + provider, provider.id,
                "PROVIDER_EXCEPTION_${failure.javaClass.simpleName}",
            )
        }
        return stage.handle { operation, failure ->
            when {
                failure != null -> ProviderStep.Failed(
                    "PROVIDER_EXCEPTION_${rootCause(failure).javaClass.simpleName}",
                )
                operation == null -> ProviderStep.Failed("PROVIDER_RETURNED_NULL")
                operation is IdentityMigrationOperationResult.Failure -> ProviderStep.Failed(operation.reasonCode)
                operation is IdentityMigrationOperationResult.Success -> ProviderStep.Succeeded
                operation is IdentityMigrationOperationResult.NoData -> ProviderStep.Skipped
                else -> ProviderStep.Failed("UNKNOWN_PROVIDER_RESULT")
            }
        }.thenCompose { step ->
            when (step) {
                ProviderStep.Succeeded -> {
                    migrated += provider
                    migrateSequentially(ticket, context, ready, migrated, index + 1)
                }
                ProviderStep.Skipped ->
                    migrateSequentially(ticket, context, ready, migrated, index + 1)
                is ProviderStep.Failed ->
                    rollbackAndFail(ticket, context, migrated + provider, provider.id, step.reasonCode)
            }
        }
    }

    private fun rollbackAndFail(
        ticket: PremiumMigrationTicket,
        context: IdentityMigrationContext,
        migrated: List<IdentityMigrationProvider>,
        providerId: String?,
        reasonCode: String,
    ): CompletionStage<PremiumMigrationRunResult> =
        rollbackSequentially(migrated.asReversed(), context, 0).thenCompose { rollbackFailure ->
            val reason = if (rollbackFailure == null) reasonCode else "${reasonCode}_ROLLBACK_${rollbackFailure}"
            fail(ticket, providerId, reason)
        }

    private fun rollbackSequentially(
        providers: List<IdentityMigrationProvider>,
        context: IdentityMigrationContext,
        index: Int,
    ): CompletionStage<String?> {
        if (index >= providers.size) return CompletableFuture.completedFuture(null)
        val provider = providers[index]
        val stage = runCatching { provider.rollback(context) }.getOrElse {
            return CompletableFuture.completedFuture("${provider.id}_${it.javaClass.simpleName}")
        }
        return stage.handle { result, failure ->
            when {
                failure != null -> "${provider.id}_${rootCause(failure).javaClass.simpleName}"
                result is IdentityMigrationOperationResult.Failure -> "${provider.id}_${result.reasonCode}"
                else -> null
            }
        }.thenCompose { failureCode ->
            if (failureCode != null) CompletableFuture.completedFuture(failureCode)
            else rollbackSequentially(providers, context, index + 1)
        }
    }

    private fun fail(
        ticket: PremiumMigrationTicket,
        providerId: String?,
        reasonCode: String,
    ): CompletionStage<PremiumMigrationRunResult> =
        storage.failPremiumMigration(ticket.id, clock.instant(), reasonCode).handle { _, failure ->
            val finalReason = if (failure == null) reasonCode
            else "${reasonCode}_PERSIST_${rootCause(failure).javaClass.simpleName}"
            audit(ticket, SecurityEventType.PREMIUM_MIGRATION_FAILED, finalReason)
            PremiumMigrationRunResult.Failed(providerId, finalReason)
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
                    reason.take(64),
                ),
            )
        }
    }

    private fun rootCause(failure: Throwable): Throwable {
        var current = failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private sealed interface ProviderStep {
        data object Succeeded : ProviderStep
        data object Skipped : ProviderStep
        data class Failed(val reasonCode: String) : ProviderStep
    }

    companion object {
        private val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9._:-]{1,63}$")
    }
}
