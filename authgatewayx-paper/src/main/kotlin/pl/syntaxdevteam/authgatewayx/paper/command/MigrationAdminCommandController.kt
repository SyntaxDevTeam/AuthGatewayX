package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationCoordinator
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationProviderInspection
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRecoveryService
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationRunResult
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumRecoveryCandidateResult
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumRecoveryStartResult
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.dialog.showAdminReportDialog
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
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
    val provider: Component,
    val noTicket: Component,
    val retryStarted: Component,
    val completed: Component,
    val failed: Component,
    val blocked: Component,
    val alreadyRunning: Component,
    val recoveryPrepared: Component,
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

            safe { storage.findByUsername(parsed) }.whenComplete { account, accountFailure ->
                if (accountFailure != null || account == null) {
                    replyAsync(sender, parsed.value) {
                        if (accountFailure != null) listOf(text.unavailable) else listOf(text.notFound)
                    }
                    return@whenComplete
                }
                val source = explicit ?: pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity.minecraftUuid(account.username)
                val context = IdentityMigrationContext(
                    UUID.randomUUID(),
                    account.id.value,
                    account.username.value,
                    source,
                    account.minecraftUuid,
                )
                dispatch(sender, Runnable {
                    if (!allowed(sender, VIEW_PERMISSION)) return@Runnable
                    coordinator.inspect(context).whenComplete { inspections, inspectFailure ->
                        replyAsync(sender, parsed.value) {
                            if (inspectFailure != null || inspections == null) {
                                listOf(text.unavailable)
                            } else {
                                listOf(
                                    text.status
                                        .withText("{kind}", "RECOVERY-CANDIDATE")
                                        .withText("{status}", "INSPECT")
                                        .withText("{source_uuid}", source.toString())
                                        .withText("{target_uuid}", account.minecraftUuid.toString())
                                        .withText("{failure}", "-"),
                                ) + inspections.map(::renderProvider)
                            }
                        }
                    }
                })
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
                                listOf(text.recoveryPrepared, renderTicket(result.ticket)) +
                                    result.inspections.map(::renderProvider)
                            }
                            dispatch(sender, Runnable {
                                if (!allowed(sender, RECOVER_PERMISSION)) return@Runnable
                                runMigration(sender, parsed.value, result.ticket)
                            })
                        }
                        is PremiumRecoveryStartResult.NoEvidence ->
                            replyAsync(sender, parsed.value) {
                                listOf(
                                    text.noEvidence.withText("{source_uuid}", result.sourceMinecraftUuid.toString()),
                                ) + result.inspections.map(::renderProvider)
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
                    listOf(renderTicket(ticket)) + inspections.map(::renderProvider)
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
                            .withText("{provider}", result.providerId)
                            .withText("{reason}", result.reasonCode),
                    )
                    result is PremiumMigrationRunResult.Failed -> listOf(
                        text.failed
                            .withText("{provider}", result.providerId ?: "-")
                            .withText("{reason}", result.reasonCode),
                    )
                    result is PremiumMigrationRunResult.AlreadyRunning -> listOf(text.alreadyRunning)
                    else -> listOf(text.unavailable)
                }
            }
        }
    }

    private fun renderTicket(ticket: PremiumMigrationTicket): Component =
        text.status
            .withText("{kind}", ticket.kind.name)
            .withText("{status}", ticket.status.name)
            .withText("{source_uuid}", ticket.sourceMinecraftUuid.toString())
            .withText("{target_uuid}", ticket.targetMinecraftUuid.toString())
            .withText("{failure}", ticket.failureReason ?: "-")

    private fun renderProvider(inspection: PremiumMigrationProviderInspection): Component =
        text.provider
            .withText("{provider}", inspection.providerId)
            .withText("{status}", inspection.status.name)
            .withText("{reason}", inspection.reasonCode)

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

    companion object {
        const val VIEW_PERMISSION = "authgatewayx.admin.migration.view"
        const val EXECUTE_PERMISSION = "authgatewayx.admin.migration.execute"
        const val RECOVER_PERMISSION = "authgatewayx.admin.migration.recover"
    }
}
