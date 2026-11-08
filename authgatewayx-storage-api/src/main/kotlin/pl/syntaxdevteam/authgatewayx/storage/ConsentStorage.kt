package pl.syntaxdevteam.authgatewayx.storage

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import java.time.Instant
import java.util.concurrent.CompletionStage

/** Account identity survives OFFLINE -> MOJANG Minecraft UUID changes. */
enum class ConsentDecision { ACCEPTED, REJECTED }

data class ConsentRecord(
    val decision: ConsentDecision,
    val policyVersion: String,
    val updatedAt: Instant,
)

interface ConsentStorage {
    fun findConsent(accountId: AccountId): CompletionStage<ConsentRecord?>
    fun saveConsent(accountId: AccountId, decision: ConsentDecision, policyVersion: String, at: Instant): CompletionStage<Unit>
}
