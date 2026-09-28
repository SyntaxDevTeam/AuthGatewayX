package pl.syntaxdevteam.authgatewayx.paper.migration

import org.bukkit.Server
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CompletionStage

class PunisherXIdentityMigrationProvider(
    private val server: Server,
    private val executor: BoundedTaskExecutor,
) : IdentityMigrationProvider {
    override val id: String = "authgatewayx:punisherx"
    override val managedDataOwners: Set<String> = setOf("PunisherX")

    override fun inspect(context: IdentityMigrationContext): CompletionStage<IdentityMigrationInspection> =
        executor.submit {
            val bridge = bridge()
                ?: return@submit if (server.pluginManager.isPluginEnabled(PLUGIN_NAME)) {
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.BLOCKED,
                        "PUNISHERX_MIGRATION_BRIDGE_UNAVAILABLE",
                    )
                } else {
                    IdentityMigrationInspection(
                        IdentityMigrationInspectionStatus.NO_DATA,
                        "PUNISHERX_NOT_INSTALLED",
                    )
                }
            val result = invoke(bridge, "inspect", context)
            when (result.status) {
                "READY" -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.READY,
                    result.reasonCode,
                    result.legacyEvidence,
                )
                "NO_DATA" -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.NO_DATA,
                    result.reasonCode,
                    result.legacyEvidence,
                )
                else -> IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.BLOCKED,
                    result.reasonCode,
                    result.legacyEvidence,
                )
            }
        }

    override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            val bridge = bridge()
                ?: return@submit if (server.pluginManager.isPluginEnabled(PLUGIN_NAME)) {
                    IdentityMigrationOperationResult.Failure("PUNISHERX_MIGRATION_BRIDGE_UNAVAILABLE")
                } else {
                    IdentityMigrationOperationResult.NoData
                }
            val result = invoke(bridge, "migrate", context)
            when (result.status) {
                "SUCCESS" -> IdentityMigrationOperationResult.Success
                "NO_DATA" -> IdentityMigrationOperationResult.NoData
                else -> IdentityMigrationOperationResult.Failure(result.reasonCode)
            }
        }

    override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> =
        executor.submit {
            val bridge = bridge()
                ?: return@submit if (server.pluginManager.isPluginEnabled(PLUGIN_NAME)) {
                    IdentityMigrationOperationResult.Failure("PUNISHERX_MIGRATION_BRIDGE_UNAVAILABLE")
                } else {
                    IdentityMigrationOperationResult.NoData
                }
            val result = invoke(bridge, "rollback", context)
            when (result.status) {
                "ROLLED_BACK", "SUCCESS" -> IdentityMigrationOperationResult.Success
                "NO_DATA" -> IdentityMigrationOperationResult.NoData
                else -> IdentityMigrationOperationResult.Failure(result.reasonCode)
            }
        }

    private fun bridge(): Any? {
        val plugin = server.pluginManager.getPlugin(PLUGIN_NAME) ?: return null
        if (!plugin.isEnabled) return null
        val bridgeClass = runCatching {
            plugin.javaClass.classLoader.loadClass(BRIDGE_CLASS)
        }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        return server.servicesManager.load(bridgeClass as Class<Any>)
    }

    private fun invoke(bridge: Any, method: String, context: IdentityMigrationContext): BridgeResult {
        val value = try {
            bridge.javaClass.getMethod(
                method,
                UUID::class.java,
                UUID::class.java,
                UUID::class.java,
            ).invoke(
                bridge,
                context.migrationId,
                context.sourceMinecraftUuid,
                context.targetMinecraftUuid,
            )
        } catch (failure: InvocationTargetException) {
            throw failure.targetException ?: failure
        }
        check(value != null) { "PunisherX migration bridge returned null" }
        val type = value.javaClass
        val status = type.getMethod("getStatus").invoke(value)?.toString()
            ?: error("PunisherX migration bridge returned no status")
        val reason = type.getMethod("getReasonCode").invoke(value)?.toString()
            ?: error("PunisherX migration bridge returned no reason")
        val evidence = type.getMethod("getLegacyEvidence").invoke(value) as? Boolean ?: false
        return BridgeResult(status, reason, evidence)
    }

    private data class BridgeResult(
        val status: String,
        val reasonCode: String,
        val legacyEvidence: Boolean,
    )

    companion object {
        private const val PLUGIN_NAME = "PunisherX"
        private const val BRIDGE_CLASS =
            "pl.syntaxdevteam.punisher.identity.PunisherXIdentityMigrationService"
    }
}
