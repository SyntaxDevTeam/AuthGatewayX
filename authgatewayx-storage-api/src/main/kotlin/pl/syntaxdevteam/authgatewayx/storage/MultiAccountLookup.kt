package pl.syntaxdevteam.authgatewayx.storage

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import java.time.Instant
import java.util.concurrent.CompletionStage

/** A shared address is a lead for manual review, never proof of a shared owner. */
data class RelatedOfflineAccount(val username: AccountUsername, val sharedAddressCount: Int)

data class MultiAccountReport(
    val accounts: List<RelatedOfflineAccount>,
    val truncated: Boolean,
)

interface MultiAccountLookup {
    /** Null means the requested offline account does not exist. No raw addresses are exposed. */
    fun findRelatedOfflineAccounts(username: AccountUsername, observedAt: Instant): CompletionStage<MultiAccountReport?>
}
