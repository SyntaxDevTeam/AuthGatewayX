package pl.syntaxdevteam.authgatewayx.storage

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CompletionStage

data class AccountAddressObservation(
    val address: InetAddress,
    val lastSeen: Instant,
)

data class AccountSecurityObservation(
    val timestamp: Instant,
    val eventType: String,
    val reasonCode: String,
    val sourceAddress: InetAddress,
)

data class AccountInspection(
    val account: AuthAccount,
    val lastLoginAt: Instant?,
    val lastLoginAddress: InetAddress?,
    val premiumVerifiedAt: Instant?,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val addresses: List<AccountAddressObservation>,
    val securityEvents: List<AccountSecurityObservation>,
)

fun interface AccountInspectionLookup {
    fun inspect(username: AccountUsername): CompletionStage<AccountInspection?>
}
