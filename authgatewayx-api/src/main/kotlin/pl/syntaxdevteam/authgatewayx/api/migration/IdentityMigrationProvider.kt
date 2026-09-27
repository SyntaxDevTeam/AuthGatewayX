package pl.syntaxdevteam.authgatewayx.api.migration

import java.util.UUID
import java.util.concurrent.CompletionStage

data class IdentityMigrationContext(
    val migrationId: UUID,
    val accountId: UUID,
    val username: String,
    val sourceMinecraftUuid: UUID,
    val targetMinecraftUuid: UUID,
)

enum class IdentityMigrationInspectionStatus {
    READY,
    NO_DATA,
    BLOCKED,
}

data class IdentityMigrationInspection(
    val status: IdentityMigrationInspectionStatus,
    val reasonCode: String,
)

sealed interface IdentityMigrationOperationResult {
    data object Success : IdentityMigrationOperationResult
    data object NoData : IdentityMigrationOperationResult
    data class Failure(val reasonCode: String) : IdentityMigrationOperationResult
}

interface IdentityMigrationProvider {
    val id: String

    val managedDataOwners: Set<String>
        get() = emptySet()

    fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection>

    fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult>

    fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult>
}
