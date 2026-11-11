package pl.syntaxdevteam.logincorex.storage

import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CompletionStage

interface ConnectionAccountLookup : MultiAccountLookup {
    fun findOfflineAccountsByAddress(address: InetAddress, excluding: AccountUsername, at: Instant): CompletionStage<MultiAccountReport>
    fun verifyHistorySchema(): CompletionStage<Unit>
}
