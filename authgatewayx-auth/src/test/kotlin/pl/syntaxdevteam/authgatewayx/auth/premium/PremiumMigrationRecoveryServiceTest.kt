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
import pl.syntaxdevteam.authgatewayx.storage.PremiumRecoveryPreparationResult
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

class PremiumMigrationRecoveryServiceTest {
    private val now = Instant.parse("2026-09-28T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val username = AccountUsername.parse("Recovered")
    private val premium = AuthAccount(
        AccountId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
        username,
        IdentityType.MOJANG,
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        AccountState.REGISTERED,
        now.minusSeconds(100),
        now,
    )

    @Test
    fun `recovery is rejected when no provider confirms legacy data`() {
        val storage = FakeStorage(premium)
        val audit = CapturingAudit()
        val coordinator = PremiumMigrationCoordinator(
            storage,
            audit,
            { listOf(FakeProvider(IdentityMigrationInspectionStatus.NO_DATA, "NO_DATA")) },
            clock,
        )
        val service = PremiumMigrationRecoveryService(storage, coordinator, audit, { it.run() }, clock)

        val result = service.prepare(username, InetAddress.getLoopbackAddress()).toCompletableFuture().get()

        assertIs<PremiumRecoveryStartResult.NoEvidence>(result)
        assertTrue(!storage.prepared)
    }

    @Test
    fun `explicit legacy uuid is accepted only after provider evidence`() {
        val storage = FakeStorage(premium)
        val audit = CapturingAudit()
        val coordinator = PremiumMigrationCoordinator(
            storage,
            audit,
            { listOf(FakeProvider(IdentityMigrationInspectionStatus.READY, "LEGACY_DATA_FOUND")) },
            clock,
        )
        val service = PremiumMigrationRecoveryService(storage, coordinator, audit, { it.run() }, clock)
        val explicitSource = UUID.fromString("cccccccc-cccc-3ccc-8ccc-cccccccccccc")

        val result = assertIs<PremiumRecoveryStartResult.Prepared>(
            service.prepare(username, InetAddress.getLoopbackAddress(), explicitSource).toCompletableFuture().get(),
        )

        assertEquals(explicitSource, result.ticket.sourceMinecraftUuid)
        assertEquals(PremiumMigrationKind.RECOVERY, result.ticket.kind)
        assertTrue(storage.prepared)
        assertTrue(audit.events.any { it.type.name == "PREMIUM_RECOVERY_PREPARED" })
    }

    private class FakeProvider(
        private val status: IdentityMigrationInspectionStatus,
        private val reason: String,
    ) : IdentityMigrationProvider {
        override val id = "test:recovery"
        override fun inspect(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture(IdentityMigrationInspection(status, reason))
        override fun migrate(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture<IdentityMigrationOperationResult>(IdentityMigrationOperationResult.NoData)
        override fun rollback(context: IdentityMigrationContext) =
            CompletableFuture.completedFuture<IdentityMigrationOperationResult>(IdentityMigrationOperationResult.NoData)
    }

    private class FakeStorage(private val account: AuthAccount) : AccountStorage {
        var prepared = false

        override fun preparePremiumRecovery(
            accountId: AccountId,
            username: AccountUsername,
            sourceMinecraftUuid: UUID,
            targetMinecraftUuid: UUID,
            sourceAddress: InetAddress,
            preparedAt: Instant,
        ): CompletionStage<PremiumRecoveryPreparationResult> {
            prepared = true
            return CompletableFuture.completedFuture(
                PremiumRecoveryPreparationResult.Prepared(
                    PremiumMigrationTicket(
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
                    ),
                ),
            )
        }

        override fun findByUsername(username: AccountUsername) =
            CompletableFuture.completedFuture<AuthAccount?>(if (username.canonical == account.username.canonical) account else null)

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
        override fun completePremiumMigration(migrationId: UUID, completedAt: Instant) =
            CompletableFuture.completedFuture<PremiumMigrationCompletionResult>(PremiumMigrationCompletionResult.NotFound)
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

    private class CapturingAudit : SecurityAuditSink {
        val events = mutableListOf<SecurityEvent>()
        override fun record(event: SecurityEvent): CompletionStage<Void> {
            events += event
            return CompletableFuture.completedFuture(null)
        }
    }
}
