package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
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
            sender.sendMessage(text.notFound)
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            sender.sendMessage(text.unavailable)
            return
        }
        try {
            lookup.findRelatedOfflineAccounts(parsed, clock.instant()).whenComplete { report, failure ->
                inFlight.set(false)
                if (isReady()) {
                    val reply = Runnable {
                        if (!isReady() || !allowed(sender)) return@Runnable
                        when {
                            failure != null -> sender.sendMessage(text.unavailable)
                            report == null -> sender.sendMessage(text.notFound)
                            else -> {
                                sender.sendMessage(text.header.withText("{username}", parsed.value))
                                if (report.accounts.isEmpty()) sender.sendMessage(text.empty)
                                report.accounts.forEach {
                                    sender.sendMessage(text.entry.withText("{username}", it.username.value)
                                        .withText("{count}", it.sharedAddressCount.toString()))
                                }
                                if (report.truncated) sender.sendMessage(text.truncated)
                            }
                        }
                    }
                    dispatch(sender, reply)
                }
            }
        } catch (_: Exception) {
            inFlight.set(false)
            sender.sendMessage(text.unavailable)
        }
    }

    private fun allowed(sender: CommandSender): Boolean = sender.hasPermission("authgatewayx.admin.alts") &&
        (sender is ConsoleCommandSender || sender is Player && sender.isOnline && isAuthenticated(sender))

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )
}
