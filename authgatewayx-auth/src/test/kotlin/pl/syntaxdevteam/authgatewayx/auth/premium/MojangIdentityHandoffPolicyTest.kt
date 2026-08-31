package pl.syntaxdevteam.authgatewayx.auth.premium

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MojangIdentityHandoffPolicyTest {
    @Test
    fun `official uuid matching forwarded uuid allows verified Mojang identity`() {
        val uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

        assertEquals(
            MojangIdentityHandoffDecision.ALLOW_VERIFIED_MOJANG,
            MojangIdentityHandoffPolicy.decide(uuid, uuid),
        )
    }

    @Test
    fun `offline or otherwise mismatched forwarded uuid is denied`() {
        assertEquals(
            MojangIdentityHandoffDecision.DENY_FORWARDED_IDENTITY_MISMATCH,
            MojangIdentityHandoffPolicy.decide(
                UUID.fromString("49b23e5a-f09d-3558-a2fe-7c584057b1fe"),
                UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
            ),
        )
    }
}
