package pl.syntaxdevteam.authgatewayx.paper.migration

import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.model.data.DataType
import net.luckperms.api.node.NodeEqualityPredicate
import org.bukkit.Server
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Migrates LuckPerms user nodes through its public API. The old user is intentionally
 * retained as a recovery copy. A non-empty, different target is never overwritten.
 */
class LuckPermsIdentityMigrationProvider(
    private val server: Server,
    private val journalRoot: java.nio.file.Path,
    private val executor: BoundedTaskExecutor,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:luckperms"
    override val managedDataOwners: Set<String> = setOf("LuckPerms")

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> {
        val api = api() ?: return absentOrUnavailable()
        val users = api.userManager
        return users.getUniqueUsers().thenCompose { uniqueUsers ->
            if (context.sourceMinecraftUuid !in uniqueUsers) {
                CompletableFuture.completedFuture(
                    IdentityMigrationInspection(IdentityMigrationInspectionStatus.NO_DATA, "LUCKPERMS_SOURCE_ABSENT"),
                )
            } else {
                loadBoth(api, context).thenApply { (sourceUser, targetUser) ->
                    val source = sourceUser.nodes
                    val target = targetUser.nodes
                    when {
                        target.isEmpty() || (Files.exists(marker(context)) && sameNodes(source, target)) -> IdentityMigrationInspection(
                            IdentityMigrationInspectionStatus.READY,
                            "LUCKPERMS_USER_NODES_READY",
                            legacyEvidence = true,
                        )
                        else -> IdentityMigrationInspection(
                            IdentityMigrationInspectionStatus.BLOCKED,
                            "LUCKPERMS_TARGET_HAS_DIFFERENT_DATA",
                            legacyEvidence = true,
                        )
                    }
                }
            }
        }
    }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> {
        val api = api() ?: return unavailableOperation()
        val users = api.userManager
        return loadBoth(api, context).thenCompose { (source, target) ->
            if (target.nodes.isNotEmpty() && !(Files.exists(marker(context)) && sameNodes(source.nodes, target.nodes))) {
                CompletableFuture.completedFuture(
                    IdentityMigrationOperationResult.Failure("LUCKPERMS_TARGET_HAS_DIFFERENT_DATA"),
                )
            } else {
                executor.submit {
                    Files.createDirectories(journalRoot)
                    if (!Files.exists(marker(context))) {
                        Files.writeString(marker(context), "target-was-empty", StandardOpenOption.CREATE_NEW)
                    }
                }.thenCompose {
                    source.nodes.forEach { target.getData(DataType.NORMAL).add(it) }
                    users.saveUser(target)
                }.thenCompose {
                    users.savePlayerData(context.targetMinecraftUuid, context.username)
                }.thenApply { IdentityMigrationOperationResult.Success }
            }
        }.exceptionally { failure ->
            IdentityMigrationOperationResult.Failure("LUCKPERMS_MIGRATE_${rootCause(failure).javaClass.simpleName}")
        }
    }

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> {
        val api = api() ?: return unavailableOperation()
        val users = api.userManager
        return loadBoth(api, context).thenCompose { (source, target) ->
            if (!Files.exists(marker(context))) {
                CompletableFuture.completedFuture(IdentityMigrationOperationResult.NoData)
            } else if (!sameNodes(source.nodes, target.nodes)) {
                CompletableFuture.completedFuture(
                    IdentityMigrationOperationResult.Failure("LUCKPERMS_TARGET_CHANGED_BEFORE_ROLLBACK"),
                )
            } else {
                target.getData(DataType.NORMAL).clear()
                users.saveUser(target).thenApply { IdentityMigrationOperationResult.Success }
            }
        }.exceptionally { failure ->
            IdentityMigrationOperationResult.Failure("LUCKPERMS_ROLLBACK_${rootCause(failure).javaClass.simpleName}")
        }
    }

    private fun api(): LuckPerms? = server.servicesManager.load(LuckPerms::class.java)
    private fun marker(context: IdentityMigrationContext) = journalRoot.resolve("${context.migrationId}.target-was-empty")

    private fun loadBoth(api: LuckPerms, context: IdentityMigrationContext): CompletionStage<Pair<User, User>> {
        val users = api.userManager
        return users.loadUser(context.sourceMinecraftUuid).thenCombine(users.loadUser(context.targetMinecraftUuid)) {
                source, target -> source to target
        }
    }

    private fun absentOrUnavailable(): CompletionStage<IdentityMigrationInspection> = CompletableFuture.completedFuture(
        if (server.pluginManager.isPluginEnabled("LuckPerms")) {
            IdentityMigrationInspection(IdentityMigrationInspectionStatus.BLOCKED, "LUCKPERMS_API_UNAVAILABLE")
        } else {
            IdentityMigrationInspection(IdentityMigrationInspectionStatus.NO_DATA, "LUCKPERMS_NOT_INSTALLED")
        },
    )

    private fun unavailableOperation(): CompletionStage<IdentityMigrationOperationResult> =
        CompletableFuture.completedFuture(
            if (server.pluginManager.isPluginEnabled("LuckPerms")) {
                IdentityMigrationOperationResult.Failure("LUCKPERMS_API_UNAVAILABLE")
            } else {
                IdentityMigrationOperationResult.NoData
            },
        )

    private fun sameNodes(first: Collection<net.luckperms.api.node.Node>, second: Collection<net.luckperms.api.node.Node>): Boolean =
        first.size == second.size && first.all { node ->
            second.any { candidate -> node.equals(candidate, NodeEqualityPredicate.EXACT) }
        }

    private fun rootCause(failure: Throwable): Throwable {
        var current = failure
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }
}
