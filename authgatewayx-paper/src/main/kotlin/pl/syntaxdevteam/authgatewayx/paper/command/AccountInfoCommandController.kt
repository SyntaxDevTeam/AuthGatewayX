package pl.syntaxdevteam.authgatewayx.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileIdentityLookup
import pl.syntaxdevteam.authgatewayx.integrations.mojang.MojangProfileLookupResult
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligence
import pl.syntaxdevteam.authgatewayx.integrations.network.IpIntelligenceLookup
import pl.syntaxdevteam.authgatewayx.paper.dialog.showAdminReportDialog
import pl.syntaxdevteam.authgatewayx.storage.AccountInspection
import pl.syntaxdevteam.authgatewayx.storage.AccountInspectionLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountLookup
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun interface AccountInfoCommandGateway {
    fun show(sender: CommandSender, username: String)
}

class MutableAccountInfoCommandGateway : AccountInfoCommandGateway {
    @Volatile var delegate: AccountInfoCommandGateway? = null
    override fun show(sender: CommandSender, username: String) = delegate?.show(sender, username) ?: Unit
}

data class AccountInfoCommandText(
    val title: Component,
    val close: Component,
    val notFound: Component,
    val unavailable: Component,
    val externalIdentity: Component,
    val identity: Component,
    val lifecycle: Component,
    val session: Component,
    val ip: Component,
    val ipHistoryHeader: Component,
    val ipHistoryEntry: Component,
    val geo: Component,
    val network: Component,
    val securityHeader: Component,
    val securitySummary: Component,
    val securityEntry: Component,
    val altsHeader: Component,
    val altsEntry: Component,
    val noData: Component,
)

class AccountInfoCommandController(
    private val inspection: AccountInspectionLookup,
    private val alts: MultiAccountLookup,
    private val network: IpIntelligenceLookup?,
    private val mojang: MojangProfileIdentityLookup,
    private val sessions: SessionRegistry,
    private val dispatch: (CommandSender, Runnable) -> Unit,
    private val text: AccountInfoCommandText,
    private val isAuthenticated: (Player) -> Boolean,
    private val isReady: () -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : AccountInfoCommandGateway {
    override fun show(sender: CommandSender, username: String) {
        if (!allowed(sender)) return
        val parsed = runCatching { AccountUsername.parse(username) }.getOrNull()
        if (parsed == null) {
            reply(sender, username, listOf(text.notFound))
            return
        }
        if (!isReady()) {
            reply(sender, parsed.value, listOf(text.unavailable))
            return
        }

        val baseStage = try {
            inspection.inspect(parsed)
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture<AccountInspection?>(failure)
        }
        val mojangStage = safeMojang { mojang.lookupProfile(parsed) }
        baseStage.thenCombine(mojangStage) { snapshot, mojangResult -> snapshot to mojangResult }
            .thenCompose { (snapshot, mojangResult) ->
                if (snapshot == null) {
                    CompletableFuture.completedFuture(Loaded(null, null, null, null, mojangResult))
                } else {
                    val session = sessions.getActive(snapshot.account.minecraftUuid)
                    val address = session?.sourceAddress ?: snapshot.lastLoginAddress
                    val networkStage = if (can(sender, VIEW_GEO_PERMISSION) && network != null && address != null) {
                        safe { network.lookup(address) }
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                    val altsStage = if (can(sender, ALTS_PERMISSION)) {
                        safe { alts.findRelatedOfflineAccounts(parsed, clock.instant()) }
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                    networkStage.thenCombine(altsStage) { intelligence, report ->
                        Loaded(snapshot, session, intelligence, report, mojangResult)
                    }
                }
            }.whenComplete { loaded, failure ->
                if (!isReady()) return@whenComplete
                dispatch(sender, Runnable {
                    if (!isReady() || !allowed(sender)) return@Runnable
                    if (failure != null || loaded == null) {
                        present(sender, parsed.value, listOf(text.unavailable))
                    } else {
                        present(sender, parsed.value, render(sender, loaded))
                    }
                })
            }
    }

    private fun render(sender: CommandSender, loaded: Loaded): List<Component> {
        val snapshot = loaded.inspection
        if (snapshot == null) {
            return listOf(
                text.notFound,
                text.externalIdentity
                    .withText("{status}", mojangStatus(loaded.mojang))
                    .withText("{uuid}", mojangUuid(loaded.mojang)),
            )
        }

        val account = snapshot.account
        val sections = mutableListOf<Component>()
        sections += text.identity
            .withText("{username}", account.username.value)
            .withText("{account_id}", account.id.value.toString())
            .withText("{minecraft_uuid}", account.minecraftUuid.toString())
            .withText("{identity}", identityName(account.identityType))
            .withText("{state}", account.state.name)
            .withText("{mojang_status}", mojangStatus(loaded.mojang))
            .withText("{official_uuid}", mojangUuid(loaded.mojang))

        sections += text.lifecycle
            .withText("{created_at}", snapshot.account.createdAt.display())
            .withText("{updated_at}", snapshot.account.updatedAt.display())
            .withText("{last_login_at}", snapshot.lastLoginAt.display())
            .withText("{premium_verified_at}", snapshot.premiumVerifiedAt.display())

        sections += text.session
            .withText("{online}", if (loaded.session != null) "TAK" else "NIE")
            .withText("{method}", loaded.session?.authenticationMethod?.name ?: "brak aktywnej sesji")
            .withText("{authenticated_at}", loaded.session?.authenticatedAt.display())
            .withText("{expires_at}", loaded.session?.expiresAt.display())

        val viewIp = can(sender, VIEW_IP_PERMISSION)
        val viewGeo = can(sender, VIEW_GEO_PERMISSION)
        val viewSecurity = can(sender, VIEW_SECURITY_PERMISSION)
        val viewAlts = can(sender, ALTS_PERMISSION)

        if (viewIp) {
            sections += text.ip
                .withText("{current_ip}", loaded.session?.sourceAddress?.hostAddress ?: "brak aktywnej sesji")
                .withText("{last_ip}", snapshot.lastLoginAddress?.hostAddress ?: "brak danych")
            sections += text.ipHistoryHeader.withText("{count}", snapshot.addresses.size.toString())
            if (snapshot.addresses.isEmpty()) {
                sections += text.noData
            } else {
                snapshot.addresses.forEach { observation ->
                    sections += text.ipHistoryEntry
                        .withText("{ip}", observation.address.hostAddress)
                        .withText("{last_seen}", observation.lastSeen.display())
                }
            }
        }

        if (viewGeo) {
            val intelligence = loaded.network
            if (intelligence == null) {
                sections += text.geo
                    .withText("{city}", "brak danych")
                    .withText("{region}", "brak danych")
                    .withText("{country}", "brak danych")
                    .withText("{country_code}", "?")
                    .withText("{continent}", "brak danych")
                    .withText("{timezone}", "brak danych")
                sections += text.network
                    .withText("{asn}", "brak danych")
                    .withText("{owner}", "brak danych")
                    .withText("{network_type}", "brak danych")
                    .withText("{vpn}", "?")
                    .withText("{proxy}", "?")
                    .withText("{tor}", "?")
                    .withText("{confidence}", "?")
                    .withText("{risk}", "?")
                    .withText("{operator}", "brak danych")
            } else {
                sections += text.geo
                    .withText("{city}", intelligence.city.display())
                    .withText("{region}", intelligence.region.display())
                    .withText("{country}", intelligence.country.display())
                    .withText("{country_code}", intelligence.countryCode.display("?"))
                    .withText("{continent}", intelligence.continent.display())
                    .withText("{timezone}", intelligence.timezone.display())
                val owner = listOfNotNull(intelligence.provider, intelligence.organisation)
                    .distinct().joinToString(" / ").ifEmpty { "brak danych" }
                sections += text.network
                    .withText("{asn}", intelligence.asn.display())
                    .withText("{owner}", owner)
                    .withText("{network_type}", intelligence.networkType.display())
                    .withText("{vpn}", intelligence.vpn.display())
                    .withText("{proxy}", intelligence.proxy.display())
                    .withText("{tor}", intelligence.tor.display())
                    .withText("{confidence}", intelligence.confidence?.let { "$it%" } ?: "?")
                    .withText("{risk}", intelligence.riskScore?.let { "$it%" } ?: "?")
                    .withText("{operator}", intelligence.operatorName.display())
            }
        }

        if (viewSecurity) {
            sections += text.securityHeader
            sections += text.securitySummary
                .withText("{failed_logins}", snapshot.failedLoginCount.toString())
                .withText("{locked_until}", snapshot.lockedUntil.display())
                .withText("{event_count}", snapshot.securityEvents.size.toString())
            if (snapshot.securityEvents.isEmpty()) {
                sections += text.noData
            } else {
                snapshot.securityEvents.forEach { event ->
                    sections += text.securityEntry
                        .withText("{time}", event.timestamp.display())
                        .withText("{type}", event.eventType)
                        .withText("{reason}", event.reasonCode)
                        .withText("{ip}", if (viewIp) " • IP ${event.sourceAddress.hostAddress}" else "")
                }
            }
        }

        if (viewAlts) {
            sections += text.altsHeader
            val report = loaded.alts
            if (report == null || report.accounts.isEmpty()) {
                sections += text.noData
            } else {
                report.accounts.forEach { related ->
                    sections += text.altsEntry
                        .withText("{username}", related.username.value)
                        .withText("{count}", related.sharedAddressCount.toString())
                }
                if (report.truncated) {
                    sections += Component.text("… wynik został ograniczony do pierwszych 20 powiązań.")
                }
            }
        }
        return sections
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

    private fun reply(sender: CommandSender, username: String, sections: List<Component>) {
        dispatch(sender, Runnable {
            if (sender is Player && (!sender.isOnline || !isAuthenticated(sender))) return@Runnable
            present(sender, username, sections)
        })
    }

    private fun allowed(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender ||
            sender.hasPermission(INFO_PERMISSION) &&
            (sender !is Player || sender.isOnline && isAuthenticated(sender))

    private fun can(sender: CommandSender, permission: String): Boolean =
        sender is ConsoleCommandSender || sender.hasPermission(permission)

    private fun <T> safe(action: () -> CompletionStage<T?>): CompletionStage<T?> =
        try {
            action().handle { value, failure -> if (failure == null) value else null }
        } catch (_: Throwable) {
            CompletableFuture.completedFuture(null)
        }

    private fun safeMojang(action: () -> CompletionStage<MojangProfileLookupResult>): CompletionStage<MojangProfileLookupResult> =
        try {
            action().handle { value, failure ->
                if (failure == null && value != null) value else MojangProfileLookupResult.Unavailable
            }
        } catch (_: Throwable) {
            CompletableFuture.completedFuture(MojangProfileLookupResult.Unavailable)
        }

    private fun identityName(type: IdentityType): String = when (type) {
        IdentityType.MOJANG -> "PREMIUM"
        IdentityType.OFFLINE -> "NON-PREMIUM"
    }

    private fun mojangStatus(result: MojangProfileLookupResult): String = when (result) {
        is MojangProfileLookupResult.Premium -> "PREMIUM"
        MojangProfileLookupResult.NotPremium -> "NON-PREMIUM"
        MojangProfileLookupResult.Unavailable -> "NIEDOSTĘPNY"
    }

    private fun mojangUuid(result: MojangProfileLookupResult): String =
        (result as? MojangProfileLookupResult.Premium)?.minecraftUuid?.toString() ?: "brak danych"

    private fun Instant?.display(): String = this?.toString() ?: "brak danych"
    private fun String?.display(fallback: String = "brak danych"): String = this ?: fallback
    private fun Boolean?.display(): String = when (this) {
        true -> "TAK"
        false -> "NIE"
        null -> "?"
    }

    private fun Component.withText(placeholder: String, value: String): Component = replaceText(
        TextReplacementConfig.builder().matchLiteral(placeholder).replacement(Component.text(value)).build(),
    )

    private data class Loaded(
        val inspection: AccountInspection?,
        val session: AuthSession?,
        val network: IpIntelligence?,
        val alts: MultiAccountReport?,
        val mojang: MojangProfileLookupResult,
    )

    companion object {
        private const val INFO_PERMISSION = "authgatewayx.admin.info"
        private const val VIEW_IP_PERMISSION = "authgatewayx.admin.view-ip"
        private const val VIEW_GEO_PERMISSION = "authgatewayx.admin.view-geo"
        private const val VIEW_SECURITY_PERMISSION = "authgatewayx.admin.view-security"
        private const val ALTS_PERMISSION = "authgatewayx.admin.alts"
    }
}
