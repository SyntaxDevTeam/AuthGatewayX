package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationCoordinator
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationProviderInspection
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRecoveryService
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRunResult
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumRecoveryCandidateResult
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumRecoveryStartResult
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.dialog.showAdminReportDialog
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationKind
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationStatus
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import java.net.InetAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface MigrationAdminCommandGateway {
    fun status(sender: CommandSender, username: String)
    fun inspect(sender: CommandSender, username: String, sourceUuid: String?)
    fun retry(sender: CommandSender, username: String)
    fun recover(sender: CommandSender, username: String, sourceUuid: String?)
}

class MutableMigrationAdminCommandGateway : MigrationAdminCommandGateway {
    @Volatile var delegate: MigrationAdminCommandGateway? = null
    override fun status(sender: CommandSender, username: String) = delegate?.status(sender, username) ?: Unit
    override fun inspect(sender: CommandSender, username: String, sourceUuid: String?) =
        delegate?.inspect(sender, username, sourceUuid) ?: Unit
    override fun retry(sender: CommandSender, username: String) = delegate?.retry(sender, username) ?: Unit
    override fun recover(sender: CommandSender, username: String, sourceUuid: String?) =
        delegate?.recover(sender, username, sourceUuid) ?: Unit
}

data class MigrationAdminCommandText(
    val title: Component,
    val close: Component,
    val notFound: Component,
    val unavailable: Component,
    val invalidUuid: Component,
    val onlineBlocked: Component,
    val noEvidence: Component,
    val notPremium: Component,
    val identityConflict: Component,
    val status: Component,
    val summaryReady: Component,
    val summaryBlocked: Component,
    val providerReady: Component,
    val providerNoData: Component,
    val providerBlocked: Component,
    val reasonFallback: Component,
    val noTicket: Component,
    val retryStarted: Component,
    val completed: Component,
    val failed: Component,
    val blocked: Component,
    val alreadyRunning: Component,
    val recoveryPrepared: Component,
    val kindUpgrade: Component,
    val kindRecovery: Component,
    val kindCandidate: Component,
    val stateInspect: Component,
    val statePrepared: Component,
    val stateMigrating: Component,
    val stateCompleted: Component,
    val stateFailed: Component,
    val providerSystem: Component,
    val providerNames: Map<String, Component>,
    val reasonTemplates: Map<String, Component>,
)

class MigrationAdminCommandController(
    private val storage: AccountStorage,
    private val coordinator: PremiumMigrationCoordinator,
    private val recovery: PremiumMigrationRecoveryService,
    private val dispatch: (CommandSender, Runnable) -> Unit,
    private val text: MigrationAdminCommandText,
    private val isAuthenticated: (Player) -> Boolean,
    private val isReady: () -> Boolean,
    private val isTargetOnline: (UUID) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : MigrationAdminCommandGateway {
    override fun status(sender: CommandSender, username: String) {
        if (!allowed(sender, VIEW_PERMISSION)) return
        val parsed = parseUsername(sender, username) ?: return
        safe { storage.findLatestPremiumMigration(parsed) }.whenComplete { ticket, failure ->
            replyAsync(sender, parsed.value) {
                if (failure != null) listOf(text.unavailable)
                else if (ticket == null) listOf(text.noTicket)
                else listOf(renderTicket(ticket))
            }
        }
    }

    override fun inspect(sender: CommandSender, username: String, sourceUuid: String?) {
        if (!allowed(sender, VIEW_PERMISSION)) return
        val parsed = parseUsername(sender, username) ?: return
        val explicit = parseUuid(sender, parsed.value, sourceUuid) ?: if (sourceUuid != null) return else null

        safe { storage.findLatestPremiumMigration(parsed) }.whenComplete { ticket, ticketFailure ->
            if (ticketFailure != null) {
                replyAsync(sender, parsed.value) { listOf(text.unavailable) }
                return@whenComplete
            }
            if (ticket != null) {
                dispatch(sender, Runnable {
                    if (!allowed(sender, VIEW_PERMISSION)) return@Runnable
                    inspectTicket(sender, parsed.value, ticket)
                })
                return@whenComplete
            }

            safe { recovery.inspectCandidate(parsed, explicit) }.whenComplete { candidate, inspectFailure ->
                if (inspectFailure != null || candidate == null) {
                    replyAsync(sender, parsed.value) { listOf(text.unavailable) }
                    return@whenComplete
                }
                when (candidate) {
                    is PremiumRecoveryCandidateResult.Ready ->
                        replyAsync(sender, parsed.value) {
                            renderInspection(
                                renderCandidate(candidate.sourceMinecraftUuid, candidate.account.minecraftUuid),
                                candidate.inspections,
                            )
                        }
                    is PremiumRecoveryCandidateResult.NoEvidence ->
                        replyAsync(sender, parsed.value) {
                            renderInspection(
                                renderCandidate(candidate.sourceMinecraftUuid, candidate.account.minecraftUuid),
                                candidate.inspections,
                                extra = listOf(
                                    text.noEvidence.withText(
                                        "{source_uuid}",
                                        candidate.sourceMinecraftUuid.toString(),
                                    ),
                                ),
                            )
                        }
                    PremiumRecoveryCandidateResult.AccountNotFound ->
                        replyAsync(sender, parsed.value) { listOf(text.notFound) }
                    PremiumRecoveryCandidateResult.AccountNotPremium ->
                        replyAsync(sender, parsed.value) { listOf(text.notPremium) }
                    PremiumRecoveryCandidateResult.IdentityConflict ->
                        replyAsync(sender, parsed.value) { listOf(text.identityConflict) }
                }
            }
        }
    }

    override fun retry(sender: CommandSender, username: String) {
        if (!allowed(sender, EXECUTE_PERMISSION)) return
        val parsed = parseUsername(sender, username) ?: return
        safe { storage.findLatestPremiumMigration(parsed) }.whenComplete { ticket, failure ->
            if (failure != null || ticket == null) {
                replyAsync(sender, parsed.value) {
                    if (failure != null) listOf(text.unavailable) else listOf(text.noTicket)
                }
                return@whenComplete
            }
            if (ticket.status == PremiumMigrationStatus.COMPLETED) {
                replyAsync(sender, parsed.value) { listOf(renderTicket(ticket), text.completed) }
                return@whenComplete
            }
            dispatch(sender, Runnable {
                if (!allowed(sender, EXECUTE_PERMISSION)) return@Runnable
                if (isTargetOnline(ticket.targetMinecraftUuid)) {
                    present(sender, parsed.value, listOf(text.onlineBlocked))
                    return@Runnable
                }
                safe { storage.retryPremiumMigration(ticket.id, clock.instant()) }.whenComplete { retried, retryFailure ->
                    if (retryFailure != null || retried == null) {
                        replyAsync(sender, parsed.value) { listOf(text.unavailable) }
                        return@whenComplete
                    }
                    dispatch(sender, Runnable {
                        if (!allowed(sender, EXECUTE_PERMISSION)) return@Runnable
                        present(sender, parsed.value, listOf(text.retryStarted, renderTicket(retried)))
                        runMigration(sender, parsed.value, retried)
                    })
                }
            })
        }
    }

    override fun recover(sender: CommandSender, username: String, sourceUuid: String?) {
        if (!allowed(sender, RECOVER_PERMISSION)) return
        val parsed = parseUsername(sender, username) ?: return
        val explicit = parseUuid(sender, parsed.value, sourceUuid) ?: if (sourceUuid != null) return else null

        safe { storage.findByUsername(parsed) }.whenComplete { account, failure ->
            if (failure != null || account == null) {
                replyAsync(sender, parsed.value) {
                    if (failure != null) listOf(text.unavailable) else listOf(text.notFound)
                }
                return@whenComplete
            }
            dispatch(sender, Runnable {
                if (!allowed(sender, RECOVER_PERMISSION)) return@Runnable
                if (isTargetOnline(account.minecraftUuid)) {
                    present(sender, parsed.value, listOf(text.onlineBlocked))
                    return@Runnable
                }
                val address = (sender as? Player)?.address?.address ?: InetAddress.getLoopbackAddress()
                recovery.prepare(parsed, address, explicit).whenComplete { result, prepareFailure ->
                    if (prepareFailure != null || result == null) {
                        replyAsync(sender, parsed.value) { listOf(text.unavailable) }
                        return@whenComplete
                    }
                    when (result) {
                        is PremiumRecoveryStartResult.Prepared -> {
                            replyAsync(sender, parsed.value) {
                                renderInspection(
                                    renderTicket(result.ticket),
                                    result.inspections,
                                    extra = listOf(text.recoveryPrepared),
                                )
                            }
                            dispatch(sender, Runnable {
                                if (!allowed(sender, RECOVER_PERMISSION)) return@Runnable
                                runMigration(sender, parsed.value, result.ticket)
                            })
                        }
                        is PremiumRecoveryStartResult.NoEvidence ->
                            replyAsync(sender, parsed.value) {
                                renderInspection(
                                    renderCandidate(result.sourceMinecraftUuid, account.minecraftUuid),
                                    result.inspections,
                                    extra = listOf(
                                        text.noEvidence.withText(
                                            "{source_uuid}",
                                            result.sourceMinecraftUuid.toString(),
                                        ),
                                    ),
                                )
                            }
                        PremiumRecoveryStartResult.AccountNotFound ->
                            replyAsync(sender, parsed.value) { listOf(text.notFound) }
                        PremiumRecoveryStartResult.AccountNotPremium ->
                            replyAsync(sender, parsed.value) { listOf(text.notPremium) }
                        PremiumRecoveryStartResult.IdentityConflict ->
                            replyAsync(sender, parsed.value) { listOf(text.identityConflict) }
                    }
                }
            })
        }
    }

    private fun inspectTicket(sender: CommandSender, username: String, ticket: PremiumMigrationTicket) {
        coordinator.inspect(ticket).whenComplete { inspections, failure ->
            replyAsync(sender, username) {
                if (failure != null || inspections == null) {
                    listOf(text.unavailable)
                } else {
                    renderInspection(renderTicket(ticket), inspections)
                }
            }
        }
    }

    private fun runMigration(sender: CommandSender, username: String, ticket: PremiumMigrationTicket) {
        coordinator.migrate(ticket).whenComplete { result, failure ->
            replyAsync(sender, username) {
                when {
                    failure != null || result == null -> listOf(text.unavailable)
                    result is PremiumMigrationRunResult.Completed -> listOf(text.completed)
                    result is PremiumMigrationRunResult.Blocked -> listOf(
                        text.blocked
                            .withComponent("{provider}", providerName(result.providerId))
                            .withComponent("{reason}", reasonDescription(result.reasonCode))
                            .withText("{code}", reasonBase(result.reasonCode)),
                    )
                    result is PremiumMigrationRunResult.Failed -> listOf(
                        text.failed
                            .withComponent(
                                "{provider}",
                                result.providerId?.let(::providerName) ?: text.providerSystem,
                            )
                            .withComponent("{reason}", reasonDescription(result.reasonCode))
                            .withText("{code}", reasonBase(result.reasonCode)),
                    )
                    result is PremiumMigrationRunResult.AlreadyRunning -> listOf(text.alreadyRunning)
                    else -> listOf(text.unavailable)
                }
            }
        }
    }

    private fun renderInspection(
        header: Component,
        inspections: List<PremiumMigrationProviderInspection>,
        extra: List<Component> = emptyList(),
    ): List<Component> {
        val blocked = inspections.count { it.status == IdentityMigrationInspectionStatus.BLOCKED }
        val ready = inspections.count { it.status == IdentityMigrationInspectionStatus.READY }
        val noData = inspections.count { it.status == IdentityMigrationInspectionStatus.NO_DATA }
        val summaryTemplate = if (blocked == 0) text.summaryReady else text.summaryBlocked
        val summary = summaryTemplate
            .withText("{blocked}", blocked.toString())
            .withText("{ready}", ready.toString())
            .withText("{no_data}", noData.toString())
        return listOf(header) + extra + summary + inspections.map(::renderProvider)
    }

    private fun renderCandidate(sourceUuid: UUID, targetUuid: UUID): Component =
        renderStatus(
            text.kindCandidate,
            text.stateInspect,
            sourceUuid,
            targetUuid,
            Component.text("-"),
        )

    private fun renderTicket(ticket: PremiumMigrationTicket): Component =
        renderStatus(
            when (ticket.kind) {
                PremiumMigrationKind.UPGRADE -> text.kindUpgrade
                PremiumMigrationKind.RECOVERY -> text.kindRecovery
            },
            when (ticket.status) {
                PremiumMigrationStatus.PREPARED -> text.statePrepared
                PremiumMigrationStatus.MIGRATING -> text.stateMigrating
                PremiumMigrationStatus.COMPLETED -> text.stateCompleted
                PremiumMigrationStatus.FAILED -> text.stateFailed
            },
            ticket.sourceMinecraftUuid,
            ticket.targetMinecraftUuid,
            ticket.failureReason?.let(::reasonDescription) ?: Component.text("-"),
        )

    private fun renderStatus(
        kind: Component,
        status: Component,
        sourceUuid: UUID,
        targetUuid: UUID,
        failure: Component,
    ): Component =
        text.status
            .withComponent("{kind}", kind)
            .withComponent("{status}", status)
            .withText("{source_uuid}", sourceUuid.toString())
            .withText("{target_uuid}", targetUuid.toString())
            .withComponent("{failure}", failure)

    private fun renderProvider(inspection: PremiumMigrationProviderInspection): Component {
        val template = when (inspection.status) {
            IdentityMigrationInspectionStatus.READY -> text.providerReady
            IdentityMigrationInspectionStatus.NO_DATA -> text.providerNoData
            IdentityMigrationInspectionStatus.BLOCKED -> text.providerBlocked
        }
        return template
            .withComponent("{provider}", providerName(inspection.providerId))
            .withComponent("{description}", reasonDescription(inspection.reasonCode))
            .withText("{code}", reasonBase(inspection.reasonCode))
    }

    private fun providerName(providerId: String): Component =
        text.providerNames[providerId] ?: Component.text(providerId)

    private fun reasonDescription(reason: String): Component {
        val parts = reason.split("::")
        val code = parts.firstOrNull().orEmpty()
        val direct = text.reasonTemplates[code]
        if (direct != null) {
            return when (code) {
                "UNMANAGED_PLUGIN_SYMLINK" ->
                    direct.withText("{path}", parts.getOrNull(1) ?: "?")
                "UNMANAGED_SCAN_FILE_LIMIT" ->
                    direct
                        .withText("{path}", parts.getOrNull(1) ?: "?")
                        .withText("{limit}", parts.getOrNull(2) ?: "?")
                "UNMANAGED_SCAN_BYTE_LIMIT" ->
                    direct
                        .withText("{path}", parts.getOrNull(1) ?: "?")
                        .withText("{size}", humanBytes(parts.getOrNull(2)))
                        .withText("{used}", humanBytes(parts.getOrNull(3)))
                        .withText("{limit}", humanBytes(parts.getOrNull(4)))
                "UNMANAGED_UUID_REFERENCES" ->
                    direct
                        .withText("{owners}", parts.getOrNull(1) ?: "?")
                        .withText("{paths}", parts.getOrNull(2) ?: "?")
                "UNIVERSAL_LOCAL_READY" ->
                    direct
                        .withText("{total}", parts.getOrNull(1) ?: "?")
                        .withText("{recipe}", parts.getOrNull(2) ?: "?")
                        .withText("{generic}", parts.getOrNull(3) ?: "?")
                        .withText("{paths}", parts.getOrNull(4) ?: "?")
                "UNIVERSAL_LOCAL_REVIEW_REQUIRED" ->
                    direct
                        .withText("{safe}", parts.getOrNull(1) ?: "?")
                        .withText("{owners}", parts.getOrNull(2) ?: "?")
                        .withText("{paths}", parts.getOrNull(3) ?: "?")
                "UNIVERSAL_LOCAL_RECIPE_INVALID" ->
                    direct
                        .withText("{file}", parts.getOrNull(1) ?: "?")
                        .withText("{detail}", parts.getOrNull(2) ?: "?")
                "UNIVERSAL_LOCAL_UNSAFE_PATH" ->
                    direct
                        .withText("{recipe}", parts.getOrNull(1) ?: "?")
                        .withText("{path}", parts.getOrNull(2) ?: parts.getOrNull(1) ?: "?")
                "UNIVERSAL_LOCAL_RECIPE_NOT_UTF8" ->
                    direct
                        .withText("{recipe}", parts.getOrNull(1) ?: "?")
                        .withText("{path}", parts.getOrNull(2) ?: "?")
                "UNIVERSAL_LOCAL_SYMLINK",
                "UNIVERSAL_LOCAL_TARGET_NOT_REGULAR_FILE",
                "UNIVERSAL_LOCAL_TARGET_COLLISION" ->
                    direct.withText("{path}", parts.getOrNull(1) ?: "?")
                else -> direct
            }
        }

        VANILLA_FILES.matchEntire(code)?.let { match ->
            return reason("VANILLA_FILES")
                .withText("{files}", match.groupValues[1])
        }
        PLOTSX_COUNTS.matchEntire(code)?.let { match ->
            return reason("PLOTSX_COUNTS")
                .withText("{owner}", match.groupValues[1])
                .withText("{member}", match.groupValues[2])
                .withText("{history}", match.groupValues[3])
                .withText("{operations}", match.groupValues[4])
        }
        HORSEMANAGERX_COUNTS.matchEntire(code)?.let { match ->
            return reason("HORSEMANAGERX_COUNTS")
                .withText("{owner}", match.groupValues[1])
                .withText("{trust}", match.groupValues[2])
                .withText("{listing}", match.groupValues[3])
                .withText("{log}", match.groupValues[4])
        }
        PUNISHERX_COUNTS.matchEntire(code)?.let { match ->
            return reason("PUNISHERX_COUNTS")
                .withText("{active}", match.groupValues[1])
                .withText("{history}", match.groupValues[2])
                .withText("{reporter}", match.groupValues[3])
                .withText("{suspect}", match.groupValues[4])
                .withText("{bridge}", match.groupValues[5])
                .withText("{ip}", match.groupValues[6])
                .withText("{jail}", match.groupValues[7])
        }
        if (code.startsWith("IDENTITY_MIGRATION_PROVIDER_REQUIRED_")) {
            return reason("IDENTITY_MIGRATION_PROVIDER_REQUIRED")
                .withText("{plugin}", code.removePrefix("IDENTITY_MIGRATION_PROVIDER_REQUIRED_"))
        }
        return text.reasonFallback.withText("{reason}", code.ifBlank { reason })
    }

    private fun reason(key: String): Component =
        text.reasonTemplates[key] ?: text.reasonFallback.withText("{reason}", key)

    private fun reasonBase(reason: String): String = reason.substringBefore("::")

    private fun humanBytes(raw: String?): String {
        val bytes = raw?.toLongOrNull() ?: return raw ?: "?"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 10.0) {
            String.format(java.util.Locale.ROOT, "%.0f %s", value, units[unit])
        } else {
            String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
        }
    }

    private fun parseUsername(sender: CommandSender, username: String): AccountUsername? {
        val parsed = runCatching { AccountUsername.parse(username) }.getOrNull()
        if (parsed == null) present(sender, username, listOf(text.notFound))
        return parsed
    }

    private fun parseUuid(sender: CommandSender, username: String, raw: String?): UUID? {
        if (raw == null) return null
        val parsed = runCatching { UUID.fromString(raw) }.getOrNull()
        if (parsed == null) present(sender, username, listOf(text.invalidUuid))
        return parsed
    }

    private fun replyAsync(sender: CommandSender, username: String, sections: () -> List<Component>) {
        dispatch(sender, Runnable {
            if (!isReady() || sender is Player && (!sender.isOnline || !isAuthenticated(sender))) return@Runnable
            present(sender, username, sections())
        })
    }

    private fun present(sender: CommandSender, username: String, sections: List<Component>) {
        val title = text.title.withText("{username}", username)
        if (sender is Player) {
            showAdminReportDialog(sender, title, sections, text.close)
        } else {
            sender.sendMessage(title)
            sections.forEach(sender::sendMessage)
        }
    }

    private fun allowed(sender: CommandSender, permission: String): Boolean =
        isReady() && (
            sender is ConsoleCommandSender ||
                sender.hasPermission(permission) &&
                (sender !is Player || sender.isOnline && isAuthenticated(sender))
            )

    private fun <T> safe(action: () -> CompletionStage<T>): CompletionStage<T> =
        try {
            action()
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(failure)
        }

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )

    private fun Component.withComponent(placeholder: String, value: Component): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(value).build(),
    )

    companion object {
        private val VANILLA_FILES = Regex("^VANILLA_FILES_(\\d+)$")
        private val PLOTSX_COUNTS = Regex("^PLOTSX_OWNER_(\\d+)_MEMBER_(\\d+)_HISTORY_(\\d+)_OPERATIONS_(\\d+)$")
        private val HORSEMANAGERX_COUNTS =
            Regex("^HORSEMANAGERX_OWNER_(\\d+)_TRUST_(\\d+)_LISTING_(\\d+)_LOG_(\\d+)$")
        private val PUNISHERX_COUNTS =
            Regex("^PUNISHERX_ACTIVE_(\\d+)_HISTORY_(\\d+)_REPORTER_(\\d+)_SUSPECT_(\\d+)_BRIDGE_(\\d+)_IP_(\\d+)_JAIL_(\\d+)$")
        const val VIEW_PERMISSION = "authgatewayx.admin.migration.view"
        const val EXECUTE_PERMISSION = "authgatewayx.admin.migration.execute"
        const val RECOVER_PERMISSION = "authgatewayx.admin.migration.recover"
    }
}
