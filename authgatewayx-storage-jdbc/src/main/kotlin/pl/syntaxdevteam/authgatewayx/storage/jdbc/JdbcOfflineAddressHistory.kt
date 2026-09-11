package pl.syntaxdevteam.authgatewayx.storage.jdbc

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import pl.syntaxdevteam.authgatewayx.storage.RelatedOfflineAccount
import java.net.InetAddress
import java.sql.Connection
import java.time.Duration
import java.time.Instant

internal object JdbcOfflineAddressHistory {
    private const val MAX_ADDRESSES = 16
    private const val MAX_RESULTS = 20
    private val window = Duration.ofDays(30)

    fun migrate(connection: Connection) {
        connection.createStatement().use {
            it.executeUpdate("""CREATE TABLE offline_account_addresses (
                account_id VARCHAR(36) NOT NULL,
                slot INTEGER NOT NULL,
                source_ip VARCHAR(45) NOT NULL,
                last_seen BIGINT NOT NULL,
                PRIMARY KEY (account_id, slot),
                UNIQUE (account_id, source_ip),
                FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )""".trimIndent())
            it.executeUpdate("CREATE INDEX offline_addresses_ip ON offline_account_addresses(source_ip, last_seen)")
        }
    }

    /** Caller holds the account write lock in the same transaction, including on remote engines. */
    fun record(connection: Connection, accountId: AccountId, address: InetAddress, at: Instant) {
        val id = accountId.value.toString()
        connection.prepareStatement("DELETE FROM offline_account_addresses WHERE account_id = ? AND last_seen < ?").use {
            it.setString(1, id)
            it.setLong(2, at.minus(window).toEpochMilli())
            it.executeUpdate()
        }
        val entries = connection.prepareStatement(
            "SELECT slot, source_ip, last_seen FROM offline_account_addresses WHERE account_id = ? ORDER BY last_seen, slot",
        ).use {
            it.setString(1, id)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(Entry(rows.getInt(1), rows.getString(2), rows.getLong(3)))
            } }
        }
        val existing = entries.find { it.address == address.hostAddress }
        val slot = existing?.slot ?: (1..MAX_ADDRESSES).firstOrNull { slot -> entries.none { it.slot == slot } }
            ?: entries.first().slot
        val occupied = entries.any { it.slot == slot }
        val sql = if (occupied) {
            "UPDATE offline_account_addresses SET source_ip = ?, last_seen = ? WHERE account_id = ? AND slot = ?"
        } else {
            "INSERT INTO offline_account_addresses(source_ip, last_seen, account_id, slot) VALUES (?, ?, ?, ?)"
        }
        connection.prepareStatement(sql).use {
            it.setString(1, address.hostAddress)
            it.setLong(2, maxOf(at.toEpochMilli(), existing?.lastSeen ?: Long.MIN_VALUE))
            it.setString(3, id)
            it.setInt(4, slot)
            it.executeUpdate()
        }
    }

    fun find(connection: Connection, username: AccountUsername, at: Instant): MultiAccountReport? {
        val id = connection.prepareStatement(
            "SELECT id FROM accounts WHERE canonical_username = ? AND identity_type = 'OFFLINE'",
        ).use {
            it.queryTimeout = 5
            it.setString(1, username.canonical)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return null
        val cutoff = at.minus(window).toEpochMilli()
        val matches = connection.prepareStatement("""SELECT candidate.username, COUNT(*) AS shared_addresses
            FROM offline_account_addresses own
            JOIN accounts target ON target.id = own.account_id AND target.identity_type = 'OFFLINE'
            JOIN offline_account_addresses other ON other.source_ip = own.source_ip
            JOIN accounts candidate ON candidate.id = other.account_id AND candidate.identity_type = 'OFFLINE'
            WHERE own.account_id = ? AND other.account_id <> own.account_id
                AND own.last_seen >= ? AND other.last_seen >= ?
            GROUP BY candidate.id, candidate.username
            ORDER BY shared_addresses DESC, candidate.username ASC
            LIMIT ?""".trimIndent()).use {
            it.queryTimeout = 5
            it.setString(1, id)
            it.setLong(2, cutoff)
            it.setLong(3, cutoff)
            it.setInt(4, MAX_RESULTS + 1)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(RelatedOfflineAccount(AccountUsername.parse(rows.getString(1)), rows.getInt(2)))
            } }
        }
        return MultiAccountReport(matches.take(MAX_RESULTS), matches.size > MAX_RESULTS)
    }

    private data class Entry(val slot: Int, val address: String, val lastSeen: Long)
}
