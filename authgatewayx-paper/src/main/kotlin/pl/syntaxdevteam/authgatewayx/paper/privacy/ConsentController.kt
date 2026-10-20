@file:Suppress("UnstableApiUsage")

package pl.syntaxdevteam.authgatewayx.paper.privacy

import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.paper.isolation.PreAuthIsolationManager
import pl.syntaxdevteam.authgatewayx.paper.scheduler.PaperPlatformScheduler
import pl.syntaxdevteam.authgatewayx.storage.ConsentDecision
import pl.syntaxdevteam.authgatewayx.storage.ConsentStorage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConsentText(
    val title: Component, val summary: Component, val version: Component, val link: Component,
    val accept: Component, val reject: Component, val accepted: Component, val rejected: Component,
    val revoked: Component, val alreadyAccepted: Component, val revokeHint: Component,
    val processing: Component, val unavailable: Component, val pending: Component,
    val usage: Component, val timeout: Component,
)

/** Runs on Paper/Folia entity schedulers. JDBC is only accessed through bounded storage futures. */
class ConsentController(
    private val plugin: Plugin,
    private val scheduler: PaperPlatformScheduler,
    private val sessions: SessionRegistry,
    private val storage: ConsentStorage,
    private val isolation: PreAuthIsolationManager,
    private val gate: ConsentGate,
    private val enabled: Boolean,
    private val dialogsEnabled: Boolean,
    private val policyVersion: String,
    private val policyUrl: String,
    private val timeoutTicks: Long,
    private val text: ConsentText,
) : Listener {
    private val writing = ConcurrentHashMap<UUID, Any>()

    init {
        require(policyVersion.isNotBlank() && policyVersion.length <= 64)
        require(timeoutTicks > 0)
        require(policyUrl.isEmpty() || policyUrl.startsWith("https://")) { "privacy.policy-url must be empty or HTTPS" }
    }

    /** Called before routing the player's first authentication form. */
    fun prepare(id: UUID) {
        if (enabled) gate.prepare(id)
    }

    /** Invoked after BOTH offline or verified premium authentication paths succeed. */
    fun authenticated(id: UUID) {
        scheduler.global(Runnable {
            val player = plugin.server.getPlayer(id) ?: return@Runnable
            scheduler.entity(player, Runnable {
                if (!player.isOnline) return@Runnable
                if (!enabled) {
                    isolation.release(player)
                    return@Runnable
                }
                val token = gate.token(id) ?: return@Runnable
                val account = account(player) ?: return@Runnable
                val stage = try { storage.findConsent(account) } catch (failure: Throwable) {
                    plugin.logger.warning("Privacy storage read failed: " + failure.javaClass.simpleName)
                    player.kick(text.unavailable)
                    return@Runnable
                }
                stage.whenComplete { record, failure ->
                    scheduler.entity(player, Runnable {
                        if (!current(player, token, account)) return@Runnable
                        if (failure != null) {
                            plugin.logger.warning("Privacy storage read failed: " + failure.javaClass.simpleName)
                            player.kick(text.unavailable)
                        } else if (record?.decision == ConsentDecision.ACCEPTED && record.policyVersion == policyVersion) {
                            allow(player, token)
                        } else {
                            // Login form closes on its own callback; do not replace it in the same tick.
                            scheduler.delayedEntity(player, 2L, Runnable {
                                if (current(player, token, account)) showPending(player)
                            })
                            scheduler.delayedEntity(player, timeoutTicks, Runnable {
                                if (current(player, token, account)) player.kick(text.timeout)
                            })
                        }
                    })
                }
            })
        })
    }

    @EventHandler
    fun onDialog(event: PlayerCustomClickEvent) {
        val decision = when (event.identifier) {
            ACCEPT -> ConsentDecision.ACCEPTED
            REJECT -> ConsentDecision.REJECTED
            else -> return
        }
        val player = (event.commonConnection as? PlayerGameConnection)?.player ?: return
        scheduler.entity(player, Runnable { decide(player, decision) })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        writing.remove(event.player.uniqueId)
        gate.forget(event.player.uniqueId)
    }

    fun command(player: Player, action: String?) {
        if (!player.isOnline) return
        val accountId = account(player) ?: return
        when (action?.lowercase()) {
            null, "", "show" -> if (gate.isPending(player.uniqueId)) showPending(player) else {
                player.sendMessage(text.alreadyAccepted)
                player.sendMessage(text.revokeHint)
            }
            "accept" -> if (gate.isPending(player.uniqueId)) decide(player, ConsentDecision.ACCEPTED)
                else player.sendMessage(text.alreadyAccepted)
            "reject" -> if (gate.isPending(player.uniqueId)) decide(player, ConsentDecision.REJECTED)
                else player.sendMessage(text.revokeHint)
            "revoke" -> {
                if (!gate.isPending(player.uniqueId)) gate.prepare(player.uniqueId)
                decide(player, ConsentDecision.REJECTED, revoked = true)
            }
            "status" -> storage.findConsent(accountId).whenComplete { record, failure ->
                scheduler.entity(player, Runnable {
                    if (!player.isOnline || account(player) != accountId) return@Runnable
                    if (failure != null) player.sendMessage(text.unavailable)
                    else if (record?.decision == ConsentDecision.ACCEPTED && record.policyVersion == policyVersion)
                        player.sendMessage(text.alreadyAccepted)
                    else player.sendMessage(text.pending)
                })
            }
            else -> player.sendMessage(text.usage)
        }
    }

    private fun decide(player: Player, decision: ConsentDecision, revoked: Boolean = false) {
        val id = player.uniqueId
        val token = gate.token(id) ?: return
        val accountId = account(player) ?: return
        if (writing.putIfAbsent(id, token) != null) return
        player.sendMessage(text.processing)
        val stage = try { storage.saveConsent(accountId, decision, policyVersion, Instant.now()) }
        catch (failure: Throwable) {
            writing.remove(id, token)
            player.kick(text.unavailable)
            return
        }
        stage.whenComplete { _, failure ->
            scheduler.entity(player, Runnable {
                writing.remove(id, token)
                if (!current(player, token, accountId)) return@Runnable
                if (failure != null) {
                    plugin.logger.warning("Privacy storage write failed: " + failure.javaClass.simpleName)
                    player.kick(text.unavailable)
                } else if (decision == ConsentDecision.ACCEPTED) {
                    player.closeDialog()
                    player.sendMessage(text.accepted)
                    allow(player, token)
                } else {
                    player.kick(if (revoked) text.revoked else text.rejected)
                }
            })
        }
    }

    private fun allow(player: Player, token: Any) {
        if (gate.release(player.uniqueId, token)) isolation.release(player)
    }

    private fun showPending(player: Player) {
        if (!gate.isPending(player.uniqueId) || account(player) == null) return
        if (dialogsEnabled) {
            player.showDialog(dialog())
        } else {
            player.sendMessage(text.title)
            player.sendMessage(text.summary)
            player.sendMessage(versionText())
            if (policyUrl.isNotEmpty()) player.sendMessage(text.link.clickEvent(ClickEvent.openUrl(policyUrl)))
            player.sendMessage(text.accept.clickEvent(ClickEvent.runCommand("/privacy accept"))
                .append(Component.space())
                .append(text.reject.clickEvent(ClickEvent.runCommand("/privacy reject"))))
        }
    }

    private fun dialog(): Dialog = Dialog.create { builder ->
        val body = mutableListOf(
            DialogBody.plainMessage(text.summary, 380),
            DialogBody.plainMessage(versionText(), 380),
        )
        if (policyUrl.isNotEmpty()) body += DialogBody.plainMessage(
            text.link.clickEvent(ClickEvent.openUrl(policyUrl)), 380,
        )
        builder.empty().base(DialogBase.builder(text.title)
            .canCloseWithEscape(false)
            .pause(false)
            .body(body)
            .build())
            .type(DialogType.confirmation(
                ActionButton.builder(text.accept).action(DialogAction.customClick(ACCEPT, null)).build(),
                ActionButton.builder(text.reject).action(DialogAction.customClick(REJECT, null)).build(),
            ))
    }

    private fun versionText() = text.version.replaceText(
        TextReplacementConfig.builder().matchLiteral("{version}")
            .replacement(Component.text(policyVersion)).build(),
    )

    private fun account(player: Player): AccountId? = sessions.getActive(player.uniqueId)?.accountId
    private fun current(player: Player, token: Any, accountId: AccountId) =
        player.isOnline && gate.isCurrent(player.uniqueId, token) && account(player) == accountId

    companion object {
        private val ACCEPT = Key.key("authgatewayx:privacy_accept")
        private val REJECT = Key.key("authgatewayx:privacy_reject")
    }
}
