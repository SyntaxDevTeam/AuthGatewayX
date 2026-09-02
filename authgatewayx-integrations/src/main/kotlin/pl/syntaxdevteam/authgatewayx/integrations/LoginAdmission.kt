package pl.syntaxdevteam.authgatewayx.integrations

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

sealed interface LoginAdmissionDecision {
    data object Allow : LoginAdmissionDecision
    data class Deny(val reasonCode: String) : LoginAdmissionDecision
}

class LoginAdmissionService(
    private val usernamePolicy: UsernamePolicyProvider?,
    private val punishmentProvider: PunishmentProvider?,
    private val cleanerFailureStrategy: FailureStrategy,
    private val punishmentFailureStrategy: FailureStrategy,
) {
    fun check(username: AccountUsername, minecraftUuid: UUID?, sourceAddress: InetAddress): CompletionStage<LoginAdmissionDecision> {
        val usernameStage = usernamePolicy?.validate(username)
            ?: CompletableFuture.completedFuture(UsernameVerdict.ALLOW)
        return usernameStage.handle { verdict, failure ->
            when {
                failure != null || verdict == null || verdict == UsernameVerdict.UNAVAILABLE ->
                    if (cleanerFailureStrategy == FailureStrategy.FAIL_CLOSED) LoginAdmissionDecision.Deny("CLEANER_UNAVAILABLE") else LoginAdmissionDecision.Allow
                verdict != UsernameVerdict.ALLOW -> LoginAdmissionDecision.Deny("CLEANER_${verdict.name}")
                else -> LoginAdmissionDecision.Allow
            }
        }.thenCompose { usernameDecision ->
            if (usernameDecision is LoginAdmissionDecision.Deny) return@thenCompose CompletableFuture.completedFuture(usernameDecision)
            val punishmentStage = punishmentProvider?.checkLogin(LoginIdentity(username, minecraftUuid, sourceAddress))
                ?: CompletableFuture.completedFuture(LoginPunishmentResult.Allow)
            punishmentStage.handle { result, failure ->
                when {
                    failure != null || result == null || result is LoginPunishmentResult.Unavailable ->
                        if (punishmentFailureStrategy == FailureStrategy.FAIL_CLOSED) LoginAdmissionDecision.Deny("PUNISHMENT_UNAVAILABLE") else LoginAdmissionDecision.Allow
                    result is LoginPunishmentResult.Deny -> LoginAdmissionDecision.Deny(result.reasonCode)
                    else -> LoginAdmissionDecision.Allow
                }
            }
        }
    }
}
