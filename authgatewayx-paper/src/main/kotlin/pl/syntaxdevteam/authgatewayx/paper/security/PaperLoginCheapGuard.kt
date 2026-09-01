package pl.syntaxdevteam.authgatewayx.paper.security

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorDecision
import pl.syntaxdevteam.authgatewayx.security.bot.ConnectionBehaviorGate
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstDecision
import pl.syntaxdevteam.authgatewayx.security.bot.UsernameBurstGate
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionDecision
import pl.syntaxdevteam.authgatewayx.security.flood.ConnectionFloodGate
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

enum class PaperLoginGuardDecision {
    ALLOW,
    DENY_FLOOD,
    DENY_INVALID_USERNAME,
    DENY_BEHAVIOR,
    DENY_USERNAME_BURST,
}

data class PaperLoginGuardResult(
    val decision: PaperLoginGuardDecision,
    val username: AccountUsername? = null,
)

/**
 * Owns the cheap Paper login checks and their execution point.
 *
 * In standalone mode the protocol adapter becomes the sole owner of stateful connection checks,
 * because it runs before the first Minecraft Services lookup. AsyncPlayerPreLoginEvent still
 * validates the username and readiness later, but deliberately does not consume the same token
 * buckets or behavioral state for a second time.
 *
 * When standalone protocol ownership is inactive (for example Velocity-forwarded Paper), the
 * Bukkit/Paper pre-login event remains the owner of the cheap checks.
 */
class PaperLoginCheapGuard(
    private val floodGate: ConnectionFloodGate,
    private val usernameBurstGate: UsernameBurstGate?,
    private val behaviorGate: ConnectionBehaviorGate?,
) {
    private val standaloneProtocolOwner = AtomicBoolean(false)

    fun activateStandaloneProtocolOwnership() {
        standaloneProtocolOwner.set(true)
    }

    fun deactivateStandaloneProtocolOwnership() {
        standaloneProtocolOwner.set(false)
    }

    fun evaluateProtocol(address: InetAddress, rawUsername: String): PaperLoginGuardResult =
        evaluateStateful(address, rawUsername)

    fun evaluatePreLogin(address: InetAddress, rawUsername: String): PaperLoginGuardResult {
        if (!standaloneProtocolOwner.get()) return evaluateStateful(address, rawUsername)
        val username = parseUsername(rawUsername)
            ?: return PaperLoginGuardResult(PaperLoginGuardDecision.DENY_INVALID_USERNAME)
        return PaperLoginGuardResult(PaperLoginGuardDecision.ALLOW, username)
    }

    fun standaloneProtocolOwnsStatefulChecks(): Boolean = standaloneProtocolOwner.get()

    private fun evaluateStateful(address: InetAddress, rawUsername: String): PaperLoginGuardResult {
        if (floodGate.evaluate(address) != ConnectionDecision.ALLOW) {
            return PaperLoginGuardResult(PaperLoginGuardDecision.DENY_FLOOD)
        }
        val username = parseUsername(rawUsername)
            ?: return PaperLoginGuardResult(PaperLoginGuardDecision.DENY_INVALID_USERNAME)
        if (behaviorGate != null &&
            behaviorGate.evaluateConnection(address, username.value) != ConnectionBehaviorDecision.ALLOW
        ) {
            return PaperLoginGuardResult(PaperLoginGuardDecision.DENY_BEHAVIOR)
        }
        if (usernameBurstGate != null &&
            usernameBurstGate.evaluate(address, username.value) != UsernameBurstDecision.ALLOW
        ) {
            return PaperLoginGuardResult(PaperLoginGuardDecision.DENY_USERNAME_BURST)
        }
        return PaperLoginGuardResult(PaperLoginGuardDecision.ALLOW, username)
    }

    private fun parseUsername(rawUsername: String): AccountUsername? =
        try {
            AccountUsername.parse(rawUsername)
        } catch (_: IllegalArgumentException) {
            null
        }
}
