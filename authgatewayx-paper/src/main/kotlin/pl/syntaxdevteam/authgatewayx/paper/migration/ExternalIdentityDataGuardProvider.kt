package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Blocks silent omission of plugins whose identity data may be stored remotely.
 * A plugin-specific provider opts out by claiming its data owner.
 */
class ExternalIdentityDataGuardProvider(
    private val enabledPluginNames: () -> Set<String>,
    private val managedDataOwnersSupplier: () -> Set<String>,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:external-identity-data-guard"

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> {
        val enabled = enabledPluginNames().mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val managed = managedDataOwnersSupplier().mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val unclaimed = GUARDED_OWNERS.firstOrNull {
            it.lowercase(Locale.ROOT) in enabled && it.lowercase(Locale.ROOT) !in managed
        }
        return CompletableFuture.completedFuture(
            if (unclaimed == null) {
                IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.NO_DATA,
                    "EXTERNAL_IDENTITY_DATA_CLAIMED_OR_ABSENT",
                )
            } else {
                IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.BLOCKED,
                    "IDENTITY_MIGRATION_PROVIDER_REQUIRED_${unclaimed.uppercase(Locale.ROOT)}",
                )
            },
        )
    }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        CompletableFuture.completedFuture(IdentityMigrationOperationResult.NoData)

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        CompletableFuture.completedFuture(IdentityMigrationOperationResult.NoData)

    companion object {
        private val GUARDED_OWNERS = listOf("Essentials", "LuckPerms")
    }
}
