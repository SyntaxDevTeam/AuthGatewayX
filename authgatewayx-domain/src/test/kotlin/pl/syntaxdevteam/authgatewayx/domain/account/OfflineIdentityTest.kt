package pl.syntaxdevteam.authgatewayx.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OfflineIdentityTest {
    @Test
    fun `offline UUID is deterministic and preserves protocol username case`() {
        val first = OfflineIdentity.minecraftUuid(AccountUsername.parse("ExampleUser"))
        val repeated = OfflineIdentity.minecraftUuid(AccountUsername.parse("ExampleUser"))
        val differentlyCased = OfflineIdentity.minecraftUuid(AccountUsername.parse("exampleuser"))

        assertEquals(first, repeated)
        assertNotEquals(first, differentlyCased)
    }
}
