package pl.syntaxdevteam.authgatewayx.storage

import java.net.InetAddress
import java.util.concurrent.CompletionStage

/** UTC epoch nanoseconds; persisted deadlines are independent of the current ban duration. */
data class ConnectionTimeoutState(
    val address: InetAddress,
    val started: Long,
    val failures: Int,
    val bannedUntil: Long?,
)

/** Snapshot owned by one Paper authentication runtime per database. */
interface ConnectionTimeoutStorage {
    fun loadConnectionTimeouts(maximumAddresses: Int): CompletionStage<List<ConnectionTimeoutState>>
    fun saveConnectionTimeouts(entries: List<ConnectionTimeoutState>): CompletionStage<Unit>
}
