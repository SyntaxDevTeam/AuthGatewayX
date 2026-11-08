package pl.syntaxdevteam.authgatewayx.paper.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationContext
import pl.syntaxdevteam.authgatewayx.api.migration.IdentityMigrationInspectionStatus
import java.util.UUID

class ExternalIdentityDataGuardProviderTest {
    @Test
    fun `blocks LuckPerms when no provider claims its potentially remote data`() {
        val provider = ExternalIdentityDataGuardProvider({ setOf("LuckPerms") }, { emptySet() })
        val inspection = provider.inspect(context()).toCompletableFuture().get()
        assertEquals(IdentityMigrationInspectionStatus.BLOCKED, inspection.status)
        assertEquals("IDENTITY_MIGRATION_PROVIDER_REQUIRED_LUCKPERMS", inspection.reasonCode)
        assertFalse(inspection.legacyEvidence)
    }

    @Test
    fun `allows installed plugin when a provider claims its data owner case insensitively`() {
        val provider = ExternalIdentityDataGuardProvider({ setOf("Essentials") }, { setOf("essentials") })
        val inspection = provider.inspect(context()).toCompletableFuture().get()
        assertEquals(IdentityMigrationInspectionStatus.NO_DATA, inspection.status)
    }

    @Test
    fun `does not confuse Vault API with an economy data owner`() {
        val provider = ExternalIdentityDataGuardProvider({ setOf("Vault") }, { emptySet() })
        val inspection = provider.inspect(context()).toCompletableFuture().get()
        assertEquals(IdentityMigrationInspectionStatus.NO_DATA, inspection.status)
    }

    private fun context() = IdentityMigrationContext(
        UUID.randomUUID(), UUID.randomUUID(), "Player", UUID.randomUUID(), UUID.randomUUID(),
    )
}
