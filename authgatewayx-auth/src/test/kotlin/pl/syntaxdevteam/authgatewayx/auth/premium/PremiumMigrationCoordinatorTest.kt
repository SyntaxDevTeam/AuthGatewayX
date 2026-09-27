package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspection
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationOperationResult
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationProvider
import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountState
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.FailedLoginUpdate
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationCompletionResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationStatus
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationTicket
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PremiumMigrationCoordinatorTest {
    private val now = Instant.parse("2026-09-27T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ticket = PremiumMigrationTicket(
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        AccountId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
        AccountUsername.parse("UpgradeMe"),
        UUID.fromString("11111111-1111-3111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        InetAddress.getLoopbackAddress(),
        PremiumMigrationStatus.PREPARED,
        now,
        now,
        null,
    )

    @Test
    fun `all ready providers complete before account identity is finalized`() {
        val storage = FakeStorage(ticket)
        val first = FakeProvider("first", IdentityMigrationInspectionStatus.READY)
        val second = FakeProvider("second", IdentityMigrationInspectionStatus.NO_DATA)
        val coordinator = PremiumMigrationCoordinator(storage, CapturingAudit(), { listOf(first, second) }, clock)

        val result = coordinator.migrate(ticket).toCompletableFuture().get()

        assertIs<PremiumMigrationRunResult.Completed>(result)
        assertTrue(storage.started)
        assertTrue(storage.completed)
        assertEquals(1, first.migrations)
        assertEquals(0, second.migrations)
    }

    @Test
    fun `blocked provider prevents any mutation and marks ticket failed`() {
        val storage = FakeStorage(ticket)
        val ready = FakeProvider("ready", IdentityMigrationInspectionStatus.READY)
        val blocked = FakeProvider("blocked", IdentityMigrationInspectionStatus.BLOCKED, "UNMANAGED_UUID_REFERENCE")
        val coordinator = PremiumMigrationCoordinator(storage, CapturingAudit(), { listOf(ready, blocked) }, clock)

        val result = coordinator.migrate(ticket).toCompletableFuture().get()

        assertIs<PremiumMigrationRunResult.Blocked>(result)
        assertTrue(!storage.started)
        assertTrue(!storage.completed)
        assertEquals(0, ready.migrations)
        assertEquals("UNMANAGED_UUID_REFERENCE", storage.failureReason)
    }

    @Test
    fun `later provider failure rolls back already migrated providers and keeps account offline`() {
        val storage = FakeStorage(ticket)
        val first = FakeProvider("first", IdentityMigrationInspectionStatus.READY)
        val failing = FakeProvider(
            "failing",
            IdentityMigrationInspectionStatus.READY,
            migrateResult = IdentityMigrationOperationResult.Failure("WRITE_FAILED"),
        )
        val coordinator = PremiumMigrationCoordinator(storage, CapturingAudit(), { listOf(first, failing) }, clock)

        val result = coordinator.migrate(ticket).toCompletableFuture().get()

        assertIs<PremiumMigrationRunResult.Failed>(result)
        assertEquals(1, first.migrations)
        assertEquals(1, first.rollbacks)
        assertTrue(!storage.completed)
        assertEquals("WRITE_FAILED", storage.failureReason)
    }

    private class FakeProvider(
        override val id: String,
        private val inspectionStatus: IdentityMigrationInspectionStatus,
        private val inspectionReason: String = "TEST",
        private val migrateResult: IdentityMigrationOperationResult = IdentityMigrationOperationResult.Success,
    ) : IdentityMigrationProvider {
        var migrations = 0
        var rollbacks = 0

        override fun inspect(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture(IdentityMigrationInspection(inspectionStatus, inspectionReason))

        override fun migrate(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> {
            migrations++
            return CompletableFuture.completedFuture(migrateResult)
        }

        override fun rollback(context: IdentityMigrationContext): CompletionStage<IdentityMigrationOperationResult> {
            rollbacks++
            return CompletableFuture.completedFuture(IdentityMigrationOperationResult.Success)
        }
    }

    private class FakeStorage(private val ticket: PremiumMigrationTicket) : AccountStorage {
        var started = false
        var completed = false
        var failureReason: String? = null

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

        override fun failPremiumMigration(migrationId: UUID, failedAt: Instant, reason: String): CompletionStage<Unit> {
            failureReason = reason
            return CompletableFuture.completedFuture(Unit)
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
        override fun findCredentials(username: AccountUsername) =
            CompletableFuture.completedFuture<pl.syntaxdevteam.authgatewayx.storage.AccountCredentials?>(null)
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

    private class CapturingAudit : SecurityAuditSink {
        override fun record(event: SecurityEvent): CompletionStage<Void> = CompletableFuture.completedFuture(null)
    }
}
