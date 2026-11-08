package pl.syntaxdevteam.authgatewayx.paper.migration

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.auth.premium.PremiumMigrationCoordinator
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountState
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.storage.AccountCredentials
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationCompletionResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationKind
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationStatus
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PremiumMigrationStartupRecoveryTest {
    private val now = Instant.parse("2026-09-28T06:00:00Z")
    private val ticket = PremiumMigrationTicket(
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        AccountId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
        AccountUsername.parse("Restarted"),
        UUID.fromString("11111111-1111-3111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        InetAddress.getLoopbackAddress(),
        PremiumMigrationStatus.MIGRATING,
        now.minusSeconds(30),
        now.minusSeconds(10),
        null,
        PremiumMigrationKind.UPGRADE,
    )

    @Test
    fun `startup resumes an offline MIGRATING ticket and completes it idempotently`() {
        val storage = FakeStorage(ticket)
        val coordinator = PremiumMigrationCoordinator(
            storage,
            NoopAudit,
            { listOf(NoDataProvider) },
        )
        var scheduledTicks = -1L

        PremiumMigrationStartupRecovery(
            storage = storage,
            coordinator = coordinator,
            scheduleDelayed = { ticks, task ->
                scheduledTicks = ticks
                task.run()
            },
            dispatchGlobal = { it.run() },
            isTargetOnline = { false },
            logger = Logger.getLogger("migration-recovery-test"),
            batchLimit = 10,
        ).schedule()

        assertEquals(20L, scheduledTicks)
        assertTrue(storage.started)
        assertTrue(storage.completed)
        assertEquals(1, storage.incompleteQueries)
    }

    @Test
    fun `startup leaves an unfinished ticket alone while target UUID is online`() {
        val storage = FakeStorage(ticket)
        val coordinator = PremiumMigrationCoordinator(
            storage,
            NoopAudit,
            { listOf(NoDataProvider) },
        )

        PremiumMigrationStartupRecovery(
            storage = storage,
            coordinator = coordinator,
            scheduleDelayed = { _, task -> task.run() },
            dispatchGlobal = { it.run() },
            isTargetOnline = { true },
            logger = Logger.getLogger("migration-recovery-online-test"),
            batchLimit = 10,
        ).schedule()

        assertTrue(!storage.started)
        assertTrue(!storage.completed)
        assertEquals(1, storage.incompleteQueries)
    }

    private class FakeStorage(private val ticket: PremiumMigrationTicket) : AccountStorage {
        var started = false
        var completed = false
        var incompleteQueries = 0

        override fun findIncompletePremiumMigrations(limit: Int): CompletionStage<List<PremiumMigrationTicket>> {
            incompleteQueries++
            return CompletableFuture.completedFuture(listOf(ticket))
        }

        override fun markPremiumMigrationStarted(migrationId: UUID, startedAt: Instant): CompletionStage<Boolean> {
            started = true
            return CompletableFuture.completedFuture(true)
        }

        override fun completePremiumMigration(
            migrationId: UUID,
            completedAt: Instant,
        ): CompletionStage<PremiumMigrationCompletionResult> {
            completed = true
            return CompletableFuture.completedFuture(
                PremiumMigrationCompletionResult.Completed(
                    AuthAccount(
                        ticket.accountId,
                        ticket.username,
                        IdentityType.MOJANG,
                        ticket.targetMinecraftUuid,
                        AccountState.REGISTERED,
                        ticket.createdAt,
                        completedAt,
                    ),
                ),
            )
        }

        override fun migrate() = CompletableFuture.completedFuture(Unit)
        override fun registerOffline(registration: OfflineRegistration) =
            CompletableFuture.completedFuture<RegistrationResult>(RegistrationResult.UsernameAlreadyExists)
        override fun bindVerifiedMojangIdentity(identity: VerifiedMojangIdentity) =
            CompletableFuture.completedFuture<MojangIdentityBindingResult>(MojangIdentityBindingResult.IdentityConflict)
        override fun preparePremiumMigration(
            accountId: AccountId,
            username: AccountUsername,
            sourceMinecraftUuid: UUID,
            targetMinecraftUuid: UUID,
            sourceAddress: InetAddress,
            preparedAt: Instant,
        ) = CompletableFuture.completedFuture<PremiumMigrationPreparationResult>(
            PremiumMigrationPreparationResult.IdentityConflict,
        )
        override fun findByUsername(username: AccountUsername) = CompletableFuture.completedFuture<AuthAccount?>(null)
        override fun findPasswordHash(accountId: AccountId) = CompletableFuture.completedFuture<String?>(null)
        override fun findCredentials(username: AccountUsername) = CompletableFuture.completedFuture<AccountCredentials?>(null)
        override fun replacePasswordHash(accountId: AccountId, expectedHash: String?, newHash: String) =
            CompletableFuture.completedFuture(false)
        override fun recordLoginSuccess(accountId: AccountId, sourceAddress: InetAddress, authenticatedAt: Instant) =
            CompletableFuture.completedFuture(Unit)
        override fun recordLoginFailure(
            accountId: AccountId,
            failedAt: Instant,
            lockThreshold: Int,
            lockDuration: Duration,
        ) = CompletableFuture.completedFuture(FailedLoginUpdate(1, null))
        override fun close() = Unit
    }

    private object NoDataProvider : IdentityMigrationProvider {
        override val id = "test:startup"
        override fun inspect(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture(
                IdentityMigrationInspection(
                    IdentityMigrationInspectionStatus.NO_DATA,
                    "NO_DATA",
                ),
            )
        override fun migrate(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture<IdentityMigrationOperationResult>(
                IdentityMigrationOperationResult.NoData,
            )
        override fun rollback(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture<IdentityMigrationOperationResult>(
                IdentityMigrationOperationResult.NoData,
            )
    }

    private object NoopAudit : SecurityAuditSink {
        override fun record(event: SecurityEvent): CompletionStage<Void> =
            CompletableFuture.completedFuture(null)
    }
}
