package pl.syntaxdevteam.authgatewayx.storage.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountState
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.AccountCredentials
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage

class SqliteAccountStorage(
    jdbcUrl: String,
    maximumPoolSize: Int,
    private val executor: BoundedTaskExecutor,
) : AccountStorage, SecurityAuditSink {
    init {
        require(jdbcUrl.startsWith("jdbc:sqlite:")) { "SqliteAccountStorage requires a jdbc:sqlite URL" }
    }

    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        poolName = "AuthGatewayX-Storage"
        this.maximumPoolSize = maximumPoolSize
        minimumIdle = minOf(1, maximumPoolSize)
        connectionTimeout = 5_000
        validationTimeout = 2_000
        isAutoCommit = true
        addDataSourceProperty("busy_timeout", "5000")
        addDataSourceProperty("foreign_keys", "true")
    })

    override fun migrate(): CompletionStage<Unit> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        canonical_username VARCHAR(16) NOT NULL UNIQUE,
                        identity_type VARCHAR(16) NOT NULL,
                        minecraft_uuid VARCHAR(36) NOT NULL UNIQUE,
                        password_hash VARCHAR(512),
                        state VARCHAR(24) NOT NULL,
                        created_at VARCHAR(40) NOT NULL,
                        updated_at VARCHAR(40) NOT NULL,
                        last_login_at VARCHAR(40),
                        last_login_ip VARCHAR(45),
                        locked_until VARCHAR(40),
                        premium_verified_at VARCHAR(40)
                    )
                    """.trimIndent())
                    statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_history (
                        version INTEGER PRIMARY KEY,
                        applied_at VARCHAR(40) NOT NULL
                    )
                    """.trimIndent())
                }
                connection.prepareStatement("INSERT OR IGNORE INTO schema_history(version, applied_at) VALUES (1, ?)").use {
                    it.setString(1, Instant.now().toString())
                    it.executeUpdate()
                }
                val hasVersion2 = connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = 2").use {
                    it.executeQuery().use { result -> result.next() }
                }
                if (!hasVersion2) {
                    connection.createStatement().use { it.executeUpdate("ALTER TABLE accounts ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0") }
                    connection.prepareStatement("INSERT INTO schema_history(version, applied_at) VALUES (2, ?)").use {
                        it.setString(1, Instant.now().toString())
                        it.executeUpdate()
                    }
                }
                val hasVersion3 = connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = 3").use {
                    it.executeQuery().use { result -> result.next() }
                }
                if (!hasVersion3) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("""CREATE TABLE security_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            occurred_at VARCHAR(40) NOT NULL,
                            account_id VARCHAR(36),
                            minecraft_uuid VARCHAR(36),
                            username VARCHAR(16) NOT NULL,
                            source_ip VARCHAR(45) NOT NULL,
                            event_type VARCHAR(40) NOT NULL,
                            reason_code VARCHAR(64) NOT NULL
                        )""".trimIndent())
                    }
                    connection.prepareStatement("INSERT INTO schema_history(version, applied_at) VALUES (3, ?)").use {
                        it.setString(1, Instant.now().toString())
                        it.executeUpdate()
                    }
                }
                val hasVersion4 = connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = 4").use {
                    it.executeQuery().use { result -> result.next() }
                }
                if (!hasVersion4) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("""CREATE TABLE registration_ip_slots (
                            source_ip VARCHAR(45) NOT NULL,
                            slot INTEGER NOT NULL,
                            account_id VARCHAR(36) NOT NULL UNIQUE,
                            PRIMARY KEY (source_ip, slot),
                            FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                        )""".trimIndent())
                        statement.executeUpdate("""INSERT INTO registration_ip_slots(source_ip, slot, account_id)
                            SELECT last_login_ip,
                                   ROW_NUMBER() OVER (PARTITION BY last_login_ip ORDER BY created_at, id),
                                   id
                            FROM accounts
                            WHERE identity_type = 'OFFLINE' AND last_login_ip IS NOT NULL""".trimIndent())
                    }
                    connection.prepareStatement("INSERT INTO schema_history(version, applied_at) VALUES (4, ?)").use {
                        it.setString(1, Instant.now().toString())
                        it.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun registerOffline(registration: OfflineRegistration): CompletionStage<RegistrationResult> = executor.submit {
        require(registration.maximumAccountsPerAddress > 0)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val created = insertOffline(connection, registration)
                if (!claimRegistrationSlot(connection, registration)) {
                    connection.rollback()
                    RegistrationResult.AddressLimitReached
                } else {
                    connection.commit()
                    created
                }
            } catch (failure: SQLException) {
                connection.rollback()
                if (!isConstraintViolation(failure)) throw failure
                when {
                    exists(connection, "canonical_username", registration.username.canonical) -> RegistrationResult.UsernameAlreadyExists
                    exists(connection, "minecraft_uuid", registration.minecraftUuid.toString()) -> RegistrationResult.MinecraftUuidAlreadyExists
                    else -> throw failure
                }
            }
        }
    }

    override fun findByUsername(username: AccountUsername): CompletionStage<AuthAccount?> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM accounts WHERE canonical_username = ?").use { statement ->
                statement.setString(1, username.canonical)
                statement.executeQuery().use { results -> if (results.next()) mapAccount(results) else null }
            }
        }
    }

    override fun findPasswordHash(accountId: AccountId): CompletionStage<String?> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT password_hash FROM accounts WHERE id = ?").use { statement ->
                statement.setString(1, accountId.value.toString())
                statement.executeQuery().use { results -> if (results.next()) results.getString(1) else null }
            }
        }
    }

    override fun findCredentials(username: AccountUsername): CompletionStage<AccountCredentials?> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM accounts WHERE canonical_username = ?").use { statement ->
                statement.setString(1, username.canonical)
                statement.executeQuery().use { results ->
                    if (!results.next()) null else AccountCredentials(
                        account = mapAccount(results),
                        passwordHash = results.getString("password_hash"),
                        failedLoginCount = results.getInt("failed_login_count"),
                        lockedUntil = results.getString("locked_until")?.let(Instant::parse),
                    )
                }
            }
        }
    }

    override fun recordLoginSuccess(accountId: AccountId, sourceAddress: java.net.InetAddress, authenticatedAt: Instant): CompletionStage<Unit> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""UPDATE accounts SET failed_login_count = 0, locked_until = NULL,
                last_login_at = ?, last_login_ip = ?, updated_at = ? WHERE id = ?""".trimIndent()).use { statement ->
                statement.setString(1, authenticatedAt.toString())
                statement.setString(2, sourceAddress.hostAddress)
                statement.setString(3, authenticatedAt.toString())
                statement.setString(4, accountId.value.toString())
                check(statement.executeUpdate() == 1) { "Account disappeared during login" }
            }
        }
    }

    override fun recordLoginFailure(
        accountId: AccountId,
        failedAt: Instant,
        lockThreshold: Int,
        lockDuration: Duration,
    ): CompletionStage<FailedLoginUpdate> = executor.submit {
        require(lockThreshold > 0 && !lockDuration.isNegative && !lockDuration.isZero)
        dataSource.connection.use { connection ->
            val lockUntil = failedAt.plus(lockDuration)
            connection.prepareStatement("""UPDATE accounts
                SET failed_login_count = failed_login_count + 1,
                    locked_until = CASE WHEN failed_login_count + 1 >= ? THEN ? ELSE locked_until END,
                    updated_at = ?
                WHERE id = ?
                RETURNING failed_login_count, locked_until""".trimIndent()).use { statement ->
                statement.setInt(1, lockThreshold)
                statement.setString(2, lockUntil.toString())
                statement.setString(3, failedAt.toString())
                statement.setString(4, accountId.value.toString())
                statement.executeQuery().use { results ->
                    check(results.next()) { "Account disappeared during failed login update" }
                    FailedLoginUpdate(results.getInt(1), results.getString(2)?.let(Instant::parse))
                }
            }
        }
    }

    override fun close() = dataSource.close()

    override fun record(event: SecurityEvent): CompletionStage<Void> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""INSERT INTO security_events
                (occurred_at, account_id, minecraft_uuid, username, source_ip, event_type, reason_code)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent()).use { statement ->
                statement.setString(1, event.timestamp.toString())
                statement.setString(2, event.accountId?.toString())
                statement.setString(3, event.minecraftUuid?.toString())
                statement.setString(4, event.username)
                statement.setString(5, event.sourceAddress.hostAddress)
                statement.setString(6, event.type.name)
                statement.setString(7, event.reasonCode)
                statement.executeUpdate()
            }
        }
    }.thenApply<Void> { null }

    private fun insertOffline(connection: Connection, registration: OfflineRegistration): RegistrationResult {
        val sql = """INSERT INTO accounts
            (id, username, canonical_username, identity_type, minecraft_uuid, password_hash, state, created_at, updated_at, last_login_ip)
            VALUES (?, ?, ?, 'OFFLINE', ?, ?, 'REGISTERED', ?, ?, ?)""".trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, registration.accountId.value.toString())
            statement.setString(2, registration.username.value)
            statement.setString(3, registration.username.canonical)
            statement.setString(4, registration.minecraftUuid.toString())
            statement.setString(5, registration.passwordHash)
            statement.setString(6, registration.createdAt.toString())
            statement.setString(7, registration.createdAt.toString())
            statement.setString(8, registration.sourceAddress.hostAddress)
            statement.executeUpdate()
        }
        return RegistrationResult.Created(AuthAccount(
            registration.accountId, registration.username, IdentityType.OFFLINE,
            registration.minecraftUuid, AccountState.REGISTERED, registration.createdAt, registration.createdAt,
        ))
    }

    private fun claimRegistrationSlot(connection: Connection, registration: OfflineRegistration): Boolean {
        val sql = "INSERT OR IGNORE INTO registration_ip_slots(source_ip, slot, account_id) VALUES (?, ?, ?)"
        connection.prepareStatement(sql).use { statement ->
            for (slot in 1..registration.maximumAccountsPerAddress) {
                statement.setString(1, registration.sourceAddress.hostAddress)
                statement.setInt(2, slot)
                statement.setString(3, registration.accountId.value.toString())
                if (statement.executeUpdate() == 1) return true
            }
        }
        return false
    }

    private fun exists(connection: Connection, column: String, value: String): Boolean {
        val allowedColumn = requireNotNull(mapOf("canonical_username" to "canonical_username", "minecraft_uuid" to "minecraft_uuid")[column])
        connection.prepareStatement("SELECT 1 FROM accounts WHERE $allowedColumn = ?").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { return it.next() }
        }
    }

    private fun isConstraintViolation(failure: SQLException): Boolean =
        failure.sqlState?.startsWith("23") == true || failure.message?.contains("constraint", ignoreCase = true) == true

    private fun mapAccount(results: java.sql.ResultSet) = AuthAccount(
        AccountId(UUID.fromString(results.getString("id"))),
        AccountUsername.parse(results.getString("username")),
        IdentityType.valueOf(results.getString("identity_type")),
        UUID.fromString(results.getString("minecraft_uuid")),
        AccountState.valueOf(results.getString("state")),
        Instant.parse(results.getString("created_at")),
        Instant.parse(results.getString("updated_at")),
    )
}
