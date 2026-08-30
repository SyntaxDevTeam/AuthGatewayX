package pl.syntaxdevteam.authgatewayx.storage.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountState
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage

class SqliteAccountStorage(
    jdbcUrl: String,
    maximumPoolSize: Int,
    private val executor: BoundedTaskExecutor,
) : AccountStorage {
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
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun registerOffline(registration: OfflineRegistration): CompletionStage<RegistrationResult> = executor.submit {
        try {
            dataSource.connection.use { connection -> insertOffline(connection, registration) }
        } catch (failure: SQLException) {
            if (!isConstraintViolation(failure)) throw failure
            dataSource.connection.use { connection ->
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

    override fun close() = dataSource.close()

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
