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
import pl.syntaxdevteam.authgatewayx.storage.ConnectionAccountLookup
import pl.syntaxdevteam.authgatewayx.storage.RelatedOfflineAccount
import pl.syntaxdevteam.authgatewayx.storage.MultiAccountReport
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.AccountAddressObservation
import pl.syntaxdevteam.authgatewayx.storage.AccountInspection
import pl.syntaxdevteam.authgatewayx.storage.AccountInspectionLookup
import pl.syntaxdevteam.authgatewayx.storage.AccountSecurityObservation
import pl.syntaxdevteam.authgatewayx.storage.AccountCredentials
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationCompletionResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationKind
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationStatus
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import pl.syntaxdevteam.authgatewayx.storage.PremiumRecoveryPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class JdbcDatabaseType {
    SQLITE, MYSQL, MARIADB, POSTGRESQL;

    val driverClassName: String
        get() = when (this) {
            SQLITE -> "org.sqlite.JDBC"
            MYSQL -> "com.mysql.cj.jdbc.Driver"
            MARIADB -> "org.mariadb.jdbc.Driver"
            POSTGRESQL -> "org.postgresql.Driver"
        }

    companion object {
        fun fromJdbcUrl(url: String): JdbcDatabaseType = when {
            url.startsWith("jdbc:sqlite:") -> SQLITE
            url.startsWith("jdbc:mysql:") -> MYSQL
            url.startsWith("jdbc:mariadb:") -> MARIADB
            url.startsWith("jdbc:postgresql:") -> POSTGRESQL
            else -> throw IllegalArgumentException("Unsupported JDBC URL")
        }
    }
}

class JdbcAccountStorage(
    jdbcUrl: String,
    maximumPoolSize: Int,
    private val executor: BoundedTaskExecutor,
    private val databaseType: JdbcDatabaseType = JdbcDatabaseType.fromJdbcUrl(jdbcUrl),
    username: String? = null,
    password: String? = null,
) : AccountStorage, SecurityAuditSink, ConnectionAccountLookup, AccountInspectionLookup {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.driverClassName = databaseType.driverClassName
        poolName = "AuthGatewayX-Storage"
        this.maximumPoolSize = maximumPoolSize
        minimumIdle = minOf(1, maximumPoolSize)
        connectionTimeout = 5_000
        validationTimeout = 2_000
        isAutoCommit = true
        if (!username.isNullOrBlank()) this.username = username
        if (password != null) this.password = password
        if (databaseType == JdbcDatabaseType.SQLITE) {
            addDataSourceProperty("busy_timeout", "5000")
            addDataSourceProperty("foreign_keys", "true")
        }
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
                if (!hasMigration(connection, 1)) recordMigration(connection, 1)
                val hasVersion2 = connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = 2").use {
                    it.executeQuery().use { result -> result.next() }
                }
                if (!hasVersion2) {
                    connection.createStatement().use { it.executeUpdate("ALTER TABLE accounts ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0") }
                    recordMigration(connection, 2)
                }
                val hasVersion3 = connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = 3").use {
                    it.executeQuery().use { result -> result.next() }
                }
                if (!hasVersion3) {
                    connection.createStatement().use { it.executeUpdate("""CREATE TABLE security_events (
                        id ${auditIdDefinition()},
                        occurred_at VARCHAR(40) NOT NULL,
                        account_id VARCHAR(36),
                        minecraft_uuid VARCHAR(36),
                        username VARCHAR(16) NOT NULL,
                        source_ip VARCHAR(45) NOT NULL,
                        event_type VARCHAR(40) NOT NULL,
                        reason_code VARCHAR(64) NOT NULL
                    )""".trimIndent()) }
                    recordMigration(connection, 3)
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
                    recordMigration(connection, 4)
                }
                if (!hasMigration(connection, 5)) {
                    JdbcOfflineAddressHistory.migrate(connection)
                    recordMigration(connection, 5)
                }
                if (!hasMigration(connection, 6)) {
                    JdbcOfflineAddressHistory.backfillAllAccounts(connection)
                    recordMigration(connection, 6)
                }
                if (!hasMigration(connection, 7)) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("""CREATE TABLE IF NOT EXISTS account_identities (
                            account_id VARCHAR(36) NOT NULL,
                            minecraft_uuid VARCHAR(36) NOT NULL,
                            username VARCHAR(16) NOT NULL,
                            identity_type VARCHAR(16) NOT NULL,
                            active INTEGER NOT NULL,
                            first_seen_at VARCHAR(40) NOT NULL,
                            last_seen_at VARCHAR(40),
                            PRIMARY KEY (account_id, minecraft_uuid, identity_type)
                        )""".trimIndent())
                        statement.executeUpdate("""CREATE TABLE IF NOT EXISTS identity_migrations (
                            id VARCHAR(36) PRIMARY KEY,
                            account_id VARCHAR(36) NOT NULL,
                            username VARCHAR(16) NOT NULL,
                            source_uuid VARCHAR(36) NOT NULL,
                            target_uuid VARCHAR(36) NOT NULL,
                            source_ip VARCHAR(45) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            created_at VARCHAR(40) NOT NULL,
                            updated_at VARCHAR(40) NOT NULL,
                            failure_reason VARCHAR(512),
                            UNIQUE (account_id, target_uuid)
                        )""".trimIndent())
                        statement.executeUpdate("""INSERT INTO account_identities
                            (account_id, minecraft_uuid, username, identity_type, active, first_seen_at, last_seen_at)
                            SELECT a.id, a.minecraft_uuid, a.username, a.identity_type, 1, a.created_at, NULL
                            FROM accounts a
                            WHERE NOT EXISTS (
                                SELECT 1 FROM account_identities i
                                WHERE i.account_id = a.id
                                  AND i.minecraft_uuid = a.minecraft_uuid
                                  AND i.identity_type = a.identity_type
                            )""".trimIndent())
                    }
                    recordMigration(connection, 7)
                }
                if (!hasMigration(connection, 8)) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "ALTER TABLE identity_migrations ADD COLUMN migration_kind VARCHAR(16) NOT NULL DEFAULT 'UPGRADE'",
                        )
                    }
                    recordMigration(connection, 8)
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
                    JdbcOfflineAddressHistory.record(connection, registration.accountId, registration.sourceAddress, registration.createdAt)
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

    override fun bindVerifiedMojangIdentity(identity: VerifiedMojangIdentity): CompletionStage<MojangIdentityBindingResult> =
        executor.submit { bindVerifiedMojangIdentityBlocking(identity) }

    override fun preparePremiumMigration(
        accountId: AccountId,
        username: AccountUsername,
        sourceMinecraftUuid: UUID,
        targetMinecraftUuid: UUID,
        sourceAddress: InetAddress,
        preparedAt: Instant,
    ): CompletionStage<PremiumMigrationPreparationResult> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val account = findAccountById(connection, accountId)
                val targetOwner = findAccount(connection, "minecraft_uuid", targetMinecraftUuid.toString())
                if (
                    account == null ||
                    account.username.canonical != username.canonical ||
                    account.identityType != IdentityType.OFFLINE ||
                    account.minecraftUuid != sourceMinecraftUuid ||
                    targetMinecraftUuid == sourceMinecraftUuid ||
                    (targetOwner != null && targetOwner.id != accountId)
                ) {
                    connection.rollback()
                    return@submit PremiumMigrationPreparationResult.IdentityConflict
                }

                val existing = findMigrationTicket(connection, accountId, targetMinecraftUuid)
                if (existing != null) {
                    val ticket = if (existing.status == PremiumMigrationStatus.FAILED) {
                        connection.prepareStatement("""UPDATE identity_migrations
                            SET status = 'PREPARED', source_ip = ?, updated_at = ?, failure_reason = NULL
                            WHERE id = ?""".trimIndent()).use { statement ->
                            statement.setString(1, sourceAddress.hostAddress)
                            statement.setString(2, preparedAt.toString())
                            statement.setString(3, existing.id.toString())
                            check(statement.executeUpdate() == 1)
                        }
                        existing.copy(
                            sourceAddress = sourceAddress,
                            status = PremiumMigrationStatus.PREPARED,
                            updatedAt = preparedAt,
                            failureReason = null,
                        )
                    } else {
                        existing
                    }
                    recordIdentity(connection, account, active = true, seenAt = preparedAt)
                    connection.commit()
                    return@submit PremiumMigrationPreparationResult.Prepared(ticket)
                }

                val ticket = PremiumMigrationTicket(
                    UUID.randomUUID(),
                    accountId,
                    username,
                    sourceMinecraftUuid,
                    targetMinecraftUuid,
                    sourceAddress,
                    PremiumMigrationStatus.PREPARED,
                    preparedAt,
                    preparedAt,
                    null,
                )
                connection.prepareStatement("""INSERT INTO identity_migrations
                    (id, account_id, username, source_uuid, target_uuid, source_ip, status, created_at, updated_at, failure_reason, migration_kind)
                    VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, NULL, 'UPGRADE')""".trimIndent()).use { statement ->
                    statement.setString(1, ticket.id.toString())
                    statement.setString(2, accountId.value.toString())
                    statement.setString(3, username.value)
                    statement.setString(4, sourceMinecraftUuid.toString())
                    statement.setString(5, targetMinecraftUuid.toString())
                    statement.setString(6, sourceAddress.hostAddress)
                    statement.setString(7, preparedAt.toString())
                    statement.setString(8, preparedAt.toString())
                    statement.executeUpdate()
                }
                recordIdentity(connection, account, active = true, seenAt = preparedAt)
                connection.commit()
                PremiumMigrationPreparationResult.Prepared(ticket)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun preparePremiumRecovery(
        accountId: AccountId,
        username: AccountUsername,
        sourceMinecraftUuid: UUID,
        targetMinecraftUuid: UUID,
        sourceAddress: InetAddress,
        preparedAt: Instant,
    ): CompletionStage<PremiumRecoveryPreparationResult> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val account = findAccountById(connection, accountId)
                if (account == null || account.username.canonical != username.canonical) {
                    connection.rollback()
                    return@submit PremiumRecoveryPreparationResult.IdentityConflict
                }
                if (account.identityType != IdentityType.MOJANG || account.minecraftUuid != targetMinecraftUuid) {
                    connection.rollback()
                    return@submit PremiumRecoveryPreparationResult.AccountNotPremium
                }
                if (sourceMinecraftUuid == targetMinecraftUuid) {
                    connection.rollback()
                    return@submit PremiumRecoveryPreparationResult.IdentityConflict
                }

                val existing = findMigrationTicket(connection, accountId, targetMinecraftUuid)
                if (existing != null) {
                    if (existing.kind != PremiumMigrationKind.RECOVERY || existing.sourceMinecraftUuid != sourceMinecraftUuid) {
                        connection.rollback()
                        return@submit PremiumRecoveryPreparationResult.IdentityConflict
                    }
                    val ticket = if (existing.status == PremiumMigrationStatus.FAILED) {
                        connection.prepareStatement("""UPDATE identity_migrations
                            SET status = 'PREPARED', source_ip = ?, updated_at = ?, failure_reason = NULL
                            WHERE id = ? AND migration_kind = 'RECOVERY'""".trimIndent()).use { statement ->
                            statement.setString(1, sourceAddress.hostAddress)
                            statement.setString(2, preparedAt.toString())
                            statement.setString(3, existing.id.toString())
                            check(statement.executeUpdate() == 1)
                        }
                        existing.copy(
                            sourceAddress = sourceAddress,
                            status = PremiumMigrationStatus.PREPARED,
                            updatedAt = preparedAt,
                            failureReason = null,
                        )
                    } else {
                        existing
                    }
                    connection.commit()
                    return@submit PremiumRecoveryPreparationResult.Prepared(ticket)
                }

                val ticket = PremiumMigrationTicket(
                    UUID.randomUUID(),
                    accountId,
                    username,
                    sourceMinecraftUuid,
                    targetMinecraftUuid,
                    sourceAddress,
                    PremiumMigrationStatus.PREPARED,
                    preparedAt,
                    preparedAt,
                    null,
                    PremiumMigrationKind.RECOVERY,
                )
                connection.prepareStatement("""INSERT INTO identity_migrations
                    (id, account_id, username, source_uuid, target_uuid, source_ip, status, created_at, updated_at, failure_reason, migration_kind)
                    VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?, NULL, 'RECOVERY')""".trimIndent()).use { statement ->
                    statement.setString(1, ticket.id.toString())
                    statement.setString(2, accountId.value.toString())
                    statement.setString(3, username.value)
                    statement.setString(4, sourceMinecraftUuid.toString())
                    statement.setString(5, targetMinecraftUuid.toString())
                    statement.setString(6, sourceAddress.hostAddress)
                    statement.setString(7, preparedAt.toString())
                    statement.setString(8, preparedAt.toString())
                    statement.executeUpdate()
                }
                connection.commit()
                PremiumRecoveryPreparationResult.Prepared(ticket)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun findPremiumMigration(migrationId: UUID): CompletionStage<PremiumMigrationTicket?> = executor.submit {
        dataSource.connection.use { connection -> findMigrationTicket(connection, migrationId) }
    }

    override fun findLatestPremiumMigration(username: AccountUsername): CompletionStage<PremiumMigrationTicket?> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""SELECT * FROM identity_migrations
                WHERE LOWER(username) = ? ORDER BY created_at DESC LIMIT 1""".trimIndent()).use { statement ->
                statement.setString(1, username.canonical)
                statement.executeQuery().use { rows -> if (rows.next()) mapMigrationTicket(rows) else null }
            }
        }
    }

    override fun findIncompletePremiumMigrations(limit: Int): CompletionStage<List<PremiumMigrationTicket>> = executor.submit {
        require(limit in 1..1000)
        dataSource.connection.use { connection ->
            connection.prepareStatement("""SELECT * FROM identity_migrations
                WHERE status IN ('PREPARED', 'MIGRATING')
                ORDER BY updated_at ASC LIMIT ?""".trimIndent()).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(mapMigrationTicket(rows))
                } }
            }
        }
    }

    override fun retryPremiumMigration(
        migrationId: UUID,
        retriedAt: Instant,
    ): CompletionStage<PremiumMigrationTicket?> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val current = findMigrationTicket(connection, migrationId)
                    ?: run {
                        connection.rollback()
                        return@submit null
                    }
                if (current.status == PremiumMigrationStatus.COMPLETED) {
                    connection.rollback()
                    return@submit current
                }
                if (current.status == PremiumMigrationStatus.FAILED) {
                    connection.prepareStatement("""UPDATE identity_migrations
                        SET status = 'PREPARED', updated_at = ?, failure_reason = NULL WHERE id = ? AND status = 'FAILED'""".trimIndent()).use { statement ->
                        statement.setString(1, retriedAt.toString())
                        statement.setString(2, migrationId.toString())
                        check(statement.executeUpdate() == 1)
                    }
                }
                val refreshed = findMigrationTicket(connection, migrationId)
                connection.commit()
                refreshed
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun markPremiumMigrationStarted(migrationId: UUID, startedAt: Instant): CompletionStage<Boolean> = executor.submit {
        dataSource.connection.use { connection ->
            val updated = connection.prepareStatement("""UPDATE identity_migrations
                SET status = 'MIGRATING', updated_at = ?, failure_reason = NULL
                WHERE id = ? AND status = 'PREPARED'""".trimIndent()).use { statement ->
                statement.setString(1, startedAt.toString())
                statement.setString(2, migrationId.toString())
                statement.executeUpdate() == 1
            }
            if (updated) true else connection.prepareStatement(
                "SELECT status FROM identity_migrations WHERE id = ?",
            ).use { statement ->
                statement.setString(1, migrationId.toString())
                statement.executeQuery().use { rows ->
                    rows.next() && rows.getString(1) == PremiumMigrationStatus.MIGRATING.name
                }
            }
        }
    }

    override fun completePremiumMigration(
        migrationId: UUID,
        completedAt: Instant,
    ): CompletionStage<PremiumMigrationCompletionResult> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val ticket = findMigrationTicket(connection, migrationId)
                    ?: run {
                        connection.rollback()
                        return@submit PremiumMigrationCompletionResult.NotFound
                    }
                val account = findAccountById(connection, ticket.accountId)
                if (ticket.status == PremiumMigrationStatus.COMPLETED) {
                    connection.rollback()
                    return@submit if (account != null && account.minecraftUuid == ticket.targetMinecraftUuid) {
                        PremiumMigrationCompletionResult.Completed(account)
                    } else {
                        PremiumMigrationCompletionResult.IdentityConflict
                    }
                }
                if (ticket.status != PremiumMigrationStatus.PREPARED && ticket.status != PremiumMigrationStatus.MIGRATING) {
                    connection.rollback()
                    return@submit PremiumMigrationCompletionResult.IdentityConflict
                }

                if (ticket.kind == PremiumMigrationKind.RECOVERY) {
                    if (
                        account == null ||
                        account.identityType != IdentityType.MOJANG ||
                        account.minecraftUuid != ticket.targetMinecraftUuid ||
                        account.username.canonical != ticket.username.canonical ||
                        ticket.sourceMinecraftUuid == ticket.targetMinecraftUuid
                    ) {
                        connection.rollback()
                        return@submit PremiumMigrationCompletionResult.IdentityConflict
                    }
                    val legacy = account.copy(
                        identityType = IdentityType.OFFLINE,
                        minecraftUuid = ticket.sourceMinecraftUuid,
                        updatedAt = completedAt,
                    )
                    recordIdentity(connection, legacy, active = false, seenAt = completedAt)
                    recordIdentity(connection, account, active = true, seenAt = completedAt)
                    markMigrationCompleted(connection, migrationId, completedAt)
                    connection.commit()
                    return@submit PremiumMigrationCompletionResult.Completed(account)
                }

                val targetOwner = findAccount(connection, "minecraft_uuid", ticket.targetMinecraftUuid.toString())
                if (
                    account == null ||
                    account.identityType != IdentityType.OFFLINE ||
                    account.minecraftUuid != ticket.sourceMinecraftUuid ||
                    account.username.canonical != ticket.username.canonical ||
                    (targetOwner != null && targetOwner.id != account.id)
                ) {
                    connection.rollback()
                    return@submit PremiumMigrationCompletionResult.IdentityConflict
                }

                connection.prepareStatement("""UPDATE accounts
                    SET identity_type = 'MOJANG', minecraft_uuid = ?, password_hash = NULL, state = 'REGISTERED',
                        failed_login_count = 0, locked_until = NULL, last_login_at = ?, last_login_ip = ?,
                        premium_verified_at = ?, updated_at = ?
                    WHERE id = ? AND identity_type = 'OFFLINE' AND minecraft_uuid = ?""".trimIndent()).use { statement ->
                    statement.setString(1, ticket.targetMinecraftUuid.toString())
                    statement.setString(2, completedAt.toString())
                    statement.setString(3, ticket.sourceAddress.hostAddress)
                    statement.setString(4, completedAt.toString())
                    statement.setString(5, completedAt.toString())
                    statement.setString(6, ticket.accountId.value.toString())
                    statement.setString(7, ticket.sourceMinecraftUuid.toString())
                    if (statement.executeUpdate() != 1) {
                        connection.rollback()
                        return@submit PremiumMigrationCompletionResult.IdentityConflict
                    }
                }
                connection.prepareStatement("DELETE FROM registration_ip_slots WHERE account_id = ?").use { statement ->
                    statement.setString(1, ticket.accountId.value.toString())
                    statement.executeUpdate()
                }

                recordIdentity(connection, account, active = false, seenAt = completedAt)
                val migrated = account.copy(
                    identityType = IdentityType.MOJANG,
                    minecraftUuid = ticket.targetMinecraftUuid,
                    state = AccountState.REGISTERED,
                    updatedAt = completedAt,
                )
                recordIdentity(connection, migrated, active = true, seenAt = completedAt)
                markMigrationCompleted(connection, migrationId, completedAt)
                JdbcOfflineAddressHistory.record(connection, ticket.accountId, ticket.sourceAddress, completedAt)
                connection.commit()
                PremiumMigrationCompletionResult.Completed(migrated)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    override fun failPremiumMigration(migrationId: UUID, failedAt: Instant, reason: String): CompletionStage<Unit> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""UPDATE identity_migrations
                SET status = 'FAILED', updated_at = ?, failure_reason = ?
                WHERE id = ? AND status <> 'COMPLETED'""".trimIndent()).use { statement ->
                statement.setString(1, failedAt.toString())
                statement.setString(2, reason.take(500))
                statement.setString(3, migrationId.toString())
                statement.executeUpdate()
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

    override fun inspect(username: AccountUsername): CompletionStage<AccountInspection?> = executor.submit {
        dataSource.connection.use { connection ->
            val base = connection.prepareStatement(
                "SELECT * FROM accounts WHERE canonical_username = ?",
            ).use { statement ->
                statement.setString(1, username.canonical)
                statement.executeQuery().use { results ->
                    if (!results.next()) null else InspectionBase(
                        account = mapAccount(results),
                        lastLoginAt = results.getString("last_login_at")?.let(Instant::parse),
                        lastLoginAddress = results.getString("last_login_ip")?.let(InetAddress::getByName),
                        premiumVerifiedAt = results.getString("premium_verified_at")?.let(Instant::parse),
                        failedLoginCount = results.getInt("failed_login_count"),
                        lockedUntil = results.getString("locked_until")?.let(Instant::parse),
                    )
                }
            } ?: return@submit null

            val addresses = connection.prepareStatement(
                "SELECT source_ip, last_seen FROM offline_account_addresses WHERE account_id = ? ORDER BY last_seen DESC, slot ASC",
            ).use { statement ->
                statement.setString(1, base.account.id.value.toString())
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        add(AccountAddressObservation(
                            InetAddress.getByName(rows.getString(1)),
                            Instant.ofEpochMilli(rows.getLong(2)),
                        ))
                    }
                } }
            }
            val events = connection.prepareStatement("""SELECT occurred_at, event_type, reason_code, source_ip
                FROM security_events
                WHERE account_id = ? OR LOWER(username) = ?
                ORDER BY occurred_at DESC
                LIMIT 20""".trimIndent()).use { statement ->
                statement.setString(1, base.account.id.value.toString())
                statement.setString(2, username.canonical)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        add(AccountSecurityObservation(
                            Instant.parse(rows.getString(1)),
                            rows.getString(2),
                            rows.getString(3),
                            InetAddress.getByName(rows.getString(4)),
                        ))
                    }
                } }
            }
            AccountInspection(
                base.account,
                base.lastLoginAt,
                base.lastLoginAddress,
                base.premiumVerifiedAt,
                base.failedLoginCount,
                base.lockedUntil,
                addresses,
                events,
            )
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
            connection.prepareStatement(
                "SELECT * FROM accounts WHERE canonical_username = ? AND identity_type = 'OFFLINE' AND password_hash IS NOT NULL",
            ).use { statement ->
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

    override fun replacePasswordHash(accountId: AccountId, expectedHash: String?, newHash: String): CompletionStage<Boolean> = executor.submit {
        dataSource.connection.use { connection ->
            val sql = if (expectedHash == null) {
                "UPDATE accounts SET password_hash = ?, updated_at = ? WHERE id = ? AND identity_type = 'OFFLINE'"
            } else {
                "UPDATE accounts SET password_hash = ?, updated_at = ? WHERE id = ? AND identity_type = 'OFFLINE' AND password_hash = ?"
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, newHash)
                statement.setString(2, Instant.now().toString())
                statement.setString(3, accountId.value.toString())
                if (expectedHash != null) statement.setString(4, expectedHash)
                statement.executeUpdate() == 1
            }
        }
    }

    override fun verifyHistorySchema(): CompletionStage<Unit> = executor.submit {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.queryTimeout = 5
                it.executeQuery("SELECT h.account_id, h.source_ip, h.last_seen, a.username, a.canonical_username, a.identity_type FROM offline_account_addresses h JOIN accounts a ON a.id = h.account_id WHERE 1 = 0").close()
            }
        }
    }

    override fun findOfflineAccountsByAddress(address: java.net.InetAddress, excluding: AccountUsername, at: Instant): CompletionStage<MultiAccountReport> = executor.submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement("""SELECT a.username FROM offline_account_addresses h
                JOIN accounts a ON a.id = h.account_id
                WHERE h.source_ip = ? AND h.last_seen >= ?
                    AND a.canonical_username <> ? ORDER BY a.canonical_username LIMIT 21""".trimIndent()).use {
                it.queryTimeout = 5
                it.setString(1, address.hostAddress)
                it.setLong(2, at.minus(Duration.ofDays(30)).toEpochMilli())
                it.setString(3, excluding.canonical)
                val names = it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(RelatedOfflineAccount(AccountUsername.parse(rows.getString(1)), 1))
                } }
                MultiAccountReport(names.take(20), names.size > 20)
            }
        }
    }

    override fun findRelatedOfflineAccounts(username: AccountUsername, observedAt: Instant): CompletionStage<MultiAccountReport?> = executor.submit {
        dataSource.connection.use { JdbcOfflineAddressHistory.find(it, username, observedAt) }
    }

    override fun recordLoginSuccess(accountId: AccountId, sourceAddress: java.net.InetAddress, authenticatedAt: Instant): CompletionStage<Unit> = executor.submit {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                // This update serializes address rotation with concurrent logins and premium migration.
                connection.prepareStatement("""UPDATE accounts SET failed_login_count = 0, locked_until = NULL,
                    last_login_at = ?, last_login_ip = ?, updated_at = ? WHERE id = ? AND identity_type = 'OFFLINE'""".trimIndent()).use { statement ->
                    statement.setString(1, authenticatedAt.toString())
                    statement.setString(2, sourceAddress.hostAddress)
                    statement.setString(3, authenticatedAt.toString())
                    statement.setString(4, accountId.value.toString())
                    check(statement.executeUpdate() == 1) { "Offline account disappeared during login" }
                }
                JdbcOfflineAddressHistory.record(connection, accountId, sourceAddress, authenticatedAt)
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
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
            connection.autoCommit = false
            val lockUntil = failedAt.plus(lockDuration)
            connection.prepareStatement("""UPDATE accounts
                SET failed_login_count = failed_login_count + 1,
                    locked_until = CASE WHEN failed_login_count + 1 >= ? THEN ? ELSE locked_until END,
                    updated_at = ?
                WHERE id = ?""".trimIndent()).use { statement ->
                statement.setInt(1, lockThreshold)
                statement.setString(2, lockUntil.toString())
                statement.setString(3, failedAt.toString())
                statement.setString(4, accountId.value.toString())
                check(statement.executeUpdate() == 1) { "Account disappeared during failed login update" }
            }
            connection.prepareStatement("SELECT failed_login_count, locked_until FROM accounts WHERE id = ?").use { statement ->
                statement.setString(1, accountId.value.toString())
                statement.executeQuery().use { results ->
                    check(results.next()) { "Account disappeared during failed login update" }
                    val update = FailedLoginUpdate(results.getInt(1), results.getString(2)?.let(Instant::parse))
                    connection.commit()
                    update
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

    private data class InspectionBase(
        val account: AuthAccount,
        val lastLoginAt: Instant?,
        val lastLoginAddress: InetAddress?,
        val premiumVerifiedAt: Instant?,
        val failedLoginCount: Int,
        val lockedUntil: Instant?,
    )

    private fun bindVerifiedMojangIdentityBlocking(identity: VerifiedMojangIdentity): MojangIdentityBindingResult {
        dataSource.connection.use { connection ->
            for (attempt in 0..1) {
                connection.autoCommit = false
                try {
                    val result = bindMojangInTransaction(connection, identity)
                    if (result == MojangIdentityBindingResult.IdentityConflict) connection.rollback() else connection.commit()
                    return result
                } catch (failure: SQLException) {
                    connection.rollback()
                    if (!isConstraintViolation(failure) || attempt == 1) throw failure
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        }
        error("Unable to bind verified Mojang identity")
    }

    private fun bindMojangInTransaction(connection: Connection, identity: VerifiedMojangIdentity): MojangIdentityBindingResult {
        val byName = findAccount(connection, "canonical_username", identity.username.canonical)
        val byUuid = findAccount(connection, "minecraft_uuid", identity.minecraftUuid.toString())
        if (byName != null && byUuid != null && byName.id != byUuid.id) {
            return MojangIdentityBindingResult.IdentityConflict
        }
        return when {
            byName != null -> bindNamedAccount(connection, byName, identity)
            byUuid != null -> bindUuidAccount(connection, byUuid, identity)
            else -> createMojangAccount(connection, identity)
        }
    }

    private fun bindNamedAccount(
        connection: Connection,
        account: AuthAccount,
        identity: VerifiedMojangIdentity,
    ): MojangIdentityBindingResult {
        if (account.identityType == IdentityType.OFFLINE) {
            return MojangIdentityBindingResult.MigrationRequired(account, identity.minecraftUuid)
        }
        if (account.minecraftUuid != identity.minecraftUuid) {
            return MojangIdentityBindingResult.IdentityConflict
        }
        findBlockingRecoveryTicket(connection, account.id, identity.minecraftUuid)?.let { ticket ->
            return MojangIdentityBindingResult.MigrationInProgress(account, ticket.id)
        }
        val updated = updateMojangAccount(connection, account, identity)
        return MojangIdentityBindingResult.Bound(updated, false)
    }

    private fun bindUuidAccount(
        connection: Connection,
        account: AuthAccount,
        identity: VerifiedMojangIdentity,
    ): MojangIdentityBindingResult {
        if (account.identityType != IdentityType.MOJANG) return MojangIdentityBindingResult.IdentityConflict
        findBlockingRecoveryTicket(connection, account.id, identity.minecraftUuid)?.let { ticket ->
            return MojangIdentityBindingResult.MigrationInProgress(account, ticket.id)
        }
        return MojangIdentityBindingResult.Bound(updateMojangAccount(connection, account, identity), false)
    }

    private fun updateMojangAccount(
        connection: Connection,
        account: AuthAccount,
        identity: VerifiedMojangIdentity,
    ): AuthAccount {
        connection.prepareStatement("""UPDATE accounts
            SET username = ?, canonical_username = ?, identity_type = 'MOJANG', minecraft_uuid = ?,
                password_hash = NULL, state = 'REGISTERED', failed_login_count = 0, locked_until = NULL,
                last_login_at = ?, last_login_ip = ?, premium_verified_at = ?, updated_at = ?
            WHERE id = ?""".trimIndent()).use { statement ->
            statement.setString(1, identity.username.value)
            statement.setString(2, identity.username.canonical)
            statement.setString(3, identity.minecraftUuid.toString())
            statement.setString(4, identity.verifiedAt.toString())
            statement.setString(5, identity.sourceAddress.hostAddress)
            statement.setString(6, identity.verifiedAt.toString())
            statement.setString(7, identity.verifiedAt.toString())
            statement.setString(8, account.id.value.toString())
            check(statement.executeUpdate() == 1) { "Account disappeared during Mojang identity binding" }
        }
        connection.prepareStatement("DELETE FROM registration_ip_slots WHERE account_id = ?").use { statement ->
            statement.setString(1, account.id.value.toString())
            statement.executeUpdate()
        }
        JdbcOfflineAddressHistory.record(connection, account.id, identity.sourceAddress, identity.verifiedAt)
        return account.copy(
            username = identity.username,
            identityType = IdentityType.MOJANG,
            minecraftUuid = identity.minecraftUuid,
            state = AccountState.REGISTERED,
            updatedAt = identity.verifiedAt,
        )
    }

    private fun createMojangAccount(
        connection: Connection,
        identity: VerifiedMojangIdentity,
    ): MojangIdentityBindingResult.Bound {
        val account = AuthAccount(
            AccountId.random(), identity.username, IdentityType.MOJANG, identity.minecraftUuid,
            AccountState.REGISTERED, identity.verifiedAt, identity.verifiedAt,
        )
        connection.prepareStatement("""INSERT INTO accounts
            (id, username, canonical_username, identity_type, minecraft_uuid, password_hash, state,
             created_at, updated_at, last_login_at, last_login_ip, premium_verified_at, failed_login_count)
            VALUES (?, ?, ?, 'MOJANG', ?, NULL, 'REGISTERED', ?, ?, ?, ?, ?, 0)""".trimIndent()).use { statement ->
            statement.setString(1, account.id.value.toString())
            statement.setString(2, identity.username.value)
            statement.setString(3, identity.username.canonical)
            statement.setString(4, identity.minecraftUuid.toString())
            statement.setString(5, identity.verifiedAt.toString())
            statement.setString(6, identity.verifiedAt.toString())
            statement.setString(7, identity.verifiedAt.toString())
            statement.setString(8, identity.sourceAddress.hostAddress)
            statement.setString(9, identity.verifiedAt.toString())
            statement.executeUpdate()
        }
        JdbcOfflineAddressHistory.record(connection, account.id, identity.sourceAddress, identity.verifiedAt)
        return MojangIdentityBindingResult.Bound(account, false)
    }

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
        val sql = when (databaseType) {
            JdbcDatabaseType.SQLITE -> "INSERT OR IGNORE INTO registration_ip_slots(source_ip, slot, account_id) VALUES (?, ?, ?)"
            JdbcDatabaseType.MYSQL, JdbcDatabaseType.MARIADB -> "INSERT IGNORE INTO registration_ip_slots(source_ip, slot, account_id) VALUES (?, ?, ?)"
            JdbcDatabaseType.POSTGRESQL -> "INSERT INTO registration_ip_slots(source_ip, slot, account_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
        }
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

    private fun findAccountById(connection: Connection, accountId: AccountId): AuthAccount? {
        connection.prepareStatement("SELECT * FROM accounts WHERE id = ?").use { statement ->
            statement.setString(1, accountId.value.toString())
            statement.executeQuery().use { results -> return if (results.next()) mapAccount(results) else null }
        }
    }

    private fun findBlockingRecoveryTicket(
        connection: Connection,
        accountId: AccountId,
        targetMinecraftUuid: UUID,
    ): PremiumMigrationTicket? {
        connection.prepareStatement("""SELECT * FROM identity_migrations
            WHERE account_id = ? AND target_uuid = ? AND migration_kind = 'RECOVERY'
              AND (
                status IN ('PREPARED', 'MIGRATING')
                OR (status = 'FAILED' AND failure_reason LIKE '%_ROLLBACK_%')
              )
            ORDER BY created_at DESC LIMIT 1""".trimIndent()).use { statement ->
            statement.setString(1, accountId.value.toString())
            statement.setString(2, targetMinecraftUuid.toString())
            statement.executeQuery().use { rows -> return if (rows.next()) mapMigrationTicket(rows) else null }
        }
    }

    private fun findMigrationTicket(
        connection: Connection,
        accountId: AccountId,
        targetMinecraftUuid: UUID,
    ): PremiumMigrationTicket? {
        connection.prepareStatement("""SELECT * FROM identity_migrations
            WHERE account_id = ? AND target_uuid = ? ORDER BY created_at DESC""".trimIndent()).use { statement ->
            statement.setString(1, accountId.value.toString())
            statement.setString(2, targetMinecraftUuid.toString())
            statement.executeQuery().use { rows -> return if (rows.next()) mapMigrationTicket(rows) else null }
        }
    }

    private fun findMigrationTicket(connection: Connection, migrationId: UUID): PremiumMigrationTicket? {
        connection.prepareStatement("SELECT * FROM identity_migrations WHERE id = ?").use { statement ->
            statement.setString(1, migrationId.toString())
            statement.executeQuery().use { rows -> return if (rows.next()) mapMigrationTicket(rows) else null }
        }
    }

    private fun markMigrationCompleted(connection: Connection, migrationId: UUID, completedAt: Instant) {
        connection.prepareStatement("""UPDATE identity_migrations
            SET status = 'COMPLETED', updated_at = ?, failure_reason = NULL WHERE id = ?""".trimIndent()).use { statement ->
            statement.setString(1, completedAt.toString())
            statement.setString(2, migrationId.toString())
            check(statement.executeUpdate() == 1)
        }
    }

    private fun recordIdentity(
        connection: Connection,
        account: AuthAccount,
        active: Boolean,
        seenAt: Instant,
    ) {
        val updated = connection.prepareStatement("""UPDATE account_identities
            SET username = ?, active = ?, last_seen_at = ?
            WHERE account_id = ? AND minecraft_uuid = ? AND identity_type = ?""".trimIndent()).use { statement ->
            statement.setString(1, account.username.value)
            statement.setInt(2, if (active) 1 else 0)
            statement.setString(3, if (active) null else seenAt.toString())
            statement.setString(4, account.id.value.toString())
            statement.setString(5, account.minecraftUuid.toString())
            statement.setString(6, account.identityType.name)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement("""INSERT INTO account_identities
                (account_id, minecraft_uuid, username, identity_type, active, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent()).use { statement ->
                statement.setString(1, account.id.value.toString())
                statement.setString(2, account.minecraftUuid.toString())
                statement.setString(3, account.username.value)
                statement.setString(4, account.identityType.name)
                statement.setInt(5, if (active) 1 else 0)
                statement.setString(6, account.createdAt.toString())
                statement.setString(7, if (active) null else seenAt.toString())
                statement.executeUpdate()
            }
        }
    }

    private fun mapMigrationTicket(results: java.sql.ResultSet) = PremiumMigrationTicket(
        UUID.fromString(results.getString("id")),
        AccountId(UUID.fromString(results.getString("account_id"))),
        AccountUsername.parse(results.getString("username")),
        UUID.fromString(results.getString("source_uuid")),
        UUID.fromString(results.getString("target_uuid")),
        InetAddress.getByName(results.getString("source_ip")),
        PremiumMigrationStatus.valueOf(results.getString("status")),
        Instant.parse(results.getString("created_at")),
        Instant.parse(results.getString("updated_at")),
        results.getString("failure_reason"),
        PremiumMigrationKind.valueOf(results.getString("migration_kind")),
    )

    private fun findAccount(connection: Connection, column: String, value: String): AuthAccount? {
        val allowedColumn = requireNotNull(mapOf("canonical_username" to "canonical_username", "minecraft_uuid" to "minecraft_uuid")[column])
        connection.prepareStatement("SELECT * FROM accounts WHERE $allowedColumn = ?").use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { results -> return if (results.next()) mapAccount(results) else null }
        }
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

    private fun hasMigration(connection: Connection, version: Int): Boolean =
        connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?").use { statement ->
            statement.setInt(1, version)
            statement.executeQuery().use { it.next() }
        }

    private fun recordMigration(connection: Connection, version: Int) {
        connection.prepareStatement("INSERT INTO schema_history(version, applied_at) VALUES (?, ?)").use { statement ->
            statement.setInt(1, version)
            statement.setString(2, Instant.now().toString())
            statement.executeUpdate()
        }
    }

    private fun auditIdDefinition(): String = when (databaseType) {
        JdbcDatabaseType.SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT"
        JdbcDatabaseType.MYSQL, JdbcDatabaseType.MARIADB -> "BIGINT PRIMARY KEY AUTO_INCREMENT"
        JdbcDatabaseType.POSTGRESQL -> "BIGSERIAL PRIMARY KEY"
    }

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

@Deprecated("Use JdbcAccountStorage; retained for source compatibility")
typealias SqliteAccountStorage = JdbcAccountStorage
