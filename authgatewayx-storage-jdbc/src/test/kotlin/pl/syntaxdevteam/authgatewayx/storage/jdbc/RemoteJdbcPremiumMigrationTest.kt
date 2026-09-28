package pl.syntaxdevteam.authgatewayx.storage.jdbc

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.account.OfflineIdentity
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.OfflineRegistration
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationCompletionResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationKind
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.PremiumMigrationStatus
import pl.syntaxdevteam.authgatewayx.storage.PremiumRecoveryPreparationResult
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteJdbcPremiumMigrationTest {
    @Test
    fun `premium migration contract is equivalent on configured remote engines`() {
        val cases = listOfNotNull(
            remote("MYSQL", JdbcDatabaseType.MYSQL),
            remote("MARIADB", JdbcDatabaseType.MARIADB),
            remote("POSTGRESQL", JdbcDatabaseType.POSTGRESQL),
        )
        if (cases.isEmpty()) return

        cases.forEach(::exercise)
    }

    private fun exercise(remoteCase: RemoteCase) {
        val executor = BoundedTaskExecutor(4, 64, "remote-${remoteCase.type.name.lowercase()}")
        val storage = JdbcAccountStorage(
            remoteCase.url,
            4,
            executor,
            remoteCase.type,
            remoteCase.username,
            remoteCase.password,
        )
        try {
            storage.migrate().toCompletableFuture().get(30, TimeUnit.SECONDS)
            storage.migrate().toCompletableFuture().get(30, TimeUnit.SECONDS)

            val suffix = remoteCase.type.name.take(5)
            val username = AccountUsername.parse("Up$suffix")
            val sourceUuid = OfflineIdentity.minecraftUuid(username)
            val registration = OfflineRegistration(
                AccountId.random(),
                username,
                sourceUuid,
                "test-hash",
                InetAddress.getLoopbackAddress(),
                Instant.parse("2026-09-28T01:00:00Z"),
                5,
            )
            assertIs<RegistrationResult.Created>(
                storage.registerOffline(registration).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )

            val targetUuid = UUID.nameUUIDFromBytes("premium-${remoteCase.type.name}".toByteArray())
            val prepared = assertIs<PremiumMigrationPreparationResult.Prepared>(
                storage.preparePremiumMigration(
                    registration.accountId,
                    username,
                    sourceUuid,
                    targetUuid,
                    InetAddress.getLoopbackAddress(),
                    Instant.parse("2026-09-28T01:01:00Z"),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )
            assertEquals(PremiumMigrationKind.UPGRADE, prepared.ticket.kind)
            assertTrue(
                storage.markPremiumMigrationStarted(
                    prepared.ticket.id,
                    Instant.parse("2026-09-28T01:01:01Z"),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )

            val start = CountDownLatch(1)
            val callers = Executors.newFixedThreadPool(2)
            try {
                val completions = (1..2).map {
                    callers.submit<PremiumMigrationCompletionResult> {
                        start.await()
                        storage.completePremiumMigration(
                            prepared.ticket.id,
                            Instant.parse("2026-09-28T01:01:02Z"),
                        ).toCompletableFuture().get(20, TimeUnit.SECONDS)
                    }
                }
                start.countDown()
                completions.forEach {
                    assertIs<PremiumMigrationCompletionResult.Completed>(it.get(25, TimeUnit.SECONDS))
                }
            } finally {
                callers.shutdownNow()
            }

            val upgraded = assertNotNull(storage.findByUsername(username).toCompletableFuture().get(20, TimeUnit.SECONDS))
            assertEquals(IdentityType.MOJANG, upgraded.identityType)
            assertEquals(targetUuid, upgraded.minecraftUuid)

            val recoveryName = AccountUsername.parse("Rc$suffix")
            val recoveryTarget = UUID.nameUUIDFromBytes("recovery-${remoteCase.type.name}".toByteArray())
            val premium = assertIs<MojangIdentityBindingResult.Bound>(
                storage.bindVerifiedMojangIdentity(
                    VerifiedMojangIdentity(
                        recoveryName,
                        recoveryTarget,
                        InetAddress.getLoopbackAddress(),
                        Instant.parse("2026-09-28T01:02:00Z"),
                    ),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            ).account
            val legacyUuid = OfflineIdentity.minecraftUuid(recoveryName)
            val recovery = assertIs<PremiumRecoveryPreparationResult.Prepared>(
                storage.preparePremiumRecovery(
                    premium.id,
                    recoveryName,
                    legacyUuid,
                    recoveryTarget,
                    InetAddress.getLoopbackAddress(),
                    Instant.parse("2026-09-28T01:03:00Z"),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )
            assertEquals(PremiumMigrationKind.RECOVERY, recovery.ticket.kind)
            assertTrue(
                storage.markPremiumMigrationStarted(
                    recovery.ticket.id,
                    Instant.parse("2026-09-28T01:03:01Z"),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )
            assertIs<PremiumMigrationCompletionResult.Completed>(
                storage.completePremiumMigration(
                    recovery.ticket.id,
                    Instant.parse("2026-09-28T01:03:02Z"),
                ).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )

            val latest = assertNotNull(
                storage.findLatestPremiumMigration(recoveryName).toCompletableFuture().get(20, TimeUnit.SECONDS),
            )
            assertEquals(PremiumMigrationKind.RECOVERY, latest.kind)
            assertEquals(PremiumMigrationStatus.COMPLETED, latest.status)
        } finally {
            storage.close()
            executor.close()
        }
    }

    private fun remote(prefix: String, type: JdbcDatabaseType): RemoteCase? {
        val url = System.getenv("AGX_TEST_${prefix}_URL")?.takeIf(String::isNotBlank) ?: return null
        return RemoteCase(
            type,
            url,
            System.getenv("AGX_TEST_${prefix}_USER") ?: "authgatewayx",
            System.getenv("AGX_TEST_${prefix}_PASSWORD") ?: "authgatewayx",
        )
    }

    private data class RemoteCase(
        val type: JdbcDatabaseType,
        val url: String,
        val username: String,
        val password: String,
    )
}
