package pl.syntaxdevteam.logincorex.integrations

import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class FailureStrategy { FAIL_OPEN, FAIL_CLOSED }

@Suppress("unused") // Public adapter results include policies supplied by external providers.
enum class UsernameVerdict { ALLOW, DENY_PROFANITY, DENY_PATTERN, DENY_RESERVED, UNAVAILABLE }

fun interface UsernamePolicyProvider {
    fun validate(username: AccountUsername): CompletionStage<UsernameVerdict>
}

data class LoginIdentity(
    val username: AccountUsername,
    val minecraftUuid: UUID?,
    val sourceAddress: InetAddress,
)

sealed interface LoginPunishmentResult {
    data object Allow : LoginPunishmentResult
    data class Deny(val reasonCode: String, val expiresAt: Instant? = null, val punishmentId: String? = null) : LoginPunishmentResult
    data class Unavailable(val cause: Throwable? = null) : LoginPunishmentResult
}

fun interface PunishmentProvider {
    fun checkLogin(identity: LoginIdentity): CompletionStage<LoginPunishmentResult>
}
