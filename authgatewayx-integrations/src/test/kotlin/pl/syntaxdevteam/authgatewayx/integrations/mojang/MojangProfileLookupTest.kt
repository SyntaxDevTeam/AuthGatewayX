package pl.syntaxdevteam.authgatewayx.integrations.mojang

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MojangProfileLookupTest {
    @Test
    fun `official profile uuid is parsed from undashed Minecraft Services response`() {
        assertEquals(
            UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            MojangProfileLookup.parsePremiumUuid("""{"id":"0123456789abcdef0123456789abcdef","name":"PremiumUser"}"""),
        )
    }

    @Test
    fun `malformed premium response is not accepted as a verified profile`() {
        assertNull(MojangProfileLookup.parsePremiumUuid("""{"id":"not-a-uuid","name":"PremiumUser"}"""))
    }
}
