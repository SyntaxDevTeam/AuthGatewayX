package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.paper.dialog.showAdminReportDialog
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountLookup
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

fun interface MultiAccountCommandGateway {
    fun show(sender: CommandSender, username: String)
}

class MutableMultiAccountCommandGateway : MultiAccountCommandGateway {
    @Volatile var delegate: MultiAccountCommandGateway? = null
    override fun show(sender: CommandSender, username: String) = delegate?.show(sender, username) ?: Unit
}

data class MultiAccountCommandText(
    val title: Component,
    val close: Component,
    val header: Component,
    val entry: Component,
    val empty: Component,
    val truncated: Component,
    val unavailable: Component,
    val notFound: Component,
)

class MultiAccountCommandController(
    private val lookup: MultiAccountLookup,
    private val dispatch: (CommandSender, Runnable) -> Unit,
    private val text: MultiAccountCommandText,
    private val isAuthenticated: (Player) -> Boolean,
    private val isReady: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : MultiAccountCommandGateway {
    private val inFlight = AtomicBoolean()

    override fun show(sender: CommandSender, username: String) {
        if (!allowed(sender) || !isReady()) return
        val parsed = runCatching { AccountUsername.parse(username) }.getOrNull()
        if (parsed == null) {
            present(sender, username, listOf(text.notFound))
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            present(sender, parsed.value, listOf(text.unavailable))
            return
        }
        try {
            lookup.findRelatedOfflineAccounts(parsed, clock.instant()).whenComplete { report, failure ->
                inFlight.set(false)
                if (isReady()) {
                    val reply = Runnable {
                        if (!isReady() || !allowed(sender)) return@Runnable
                        when {
                            failure != null -> present(sender, parsed.value, listOf(text.unavailable))
                            report == null -> present(sender, parsed.value, listOf(text.notFound))
                            else -> {
                                val sections = mutableListOf(
                                    text.header.withText("{username}", parsed.value),
                                )
                                if (report.accounts.isEmpty()) sections += text.empty
                                report.accounts.forEach {
                                    sections += text.entry
                                        .withText("{username}", it.username.value)
                                        .withText("{count}", it.sharedAddressCount.toString())
                                }
                                if (report.truncated) sections += text.truncated
                                present(sender, parsed.value, sections)
                            }
                        }
                    }
                    dispatch(sender, reply)
                }
            }
        } catch (_: Exception) {
            inFlight.set(false)
            present(sender, parsed.value, listOf(text.unavailable))
        }
    }

    private fun present(sender: CommandSender, username: String, sections: List<Component>) {
        if (sender is Player) {
            showAdminReportDialog(sender, text.title.withText("{username}", username), sections, text.close)
        } else {
            sections.forEach(sender::sendMessage)
        }
    }

    private fun allowed(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender ||
            sender.hasPermission("authgatewayx.admin.alts") &&
            (sender !is Player || sender.isOnline && isAuthenticated(sender))

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )
}
