package pl.syntaxdevteam.authgatewayx.paper.premium

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumHandshakeCapacityTest {
    @Test
    fun `limits and releases concurrent premium handshakes`() {
        val capacity = PremiumHandshakeCapacity(2)

        assertTrue(capacity.tryAcquire())
        assertTrue(capacity.tryAcquire())
        assertFalse(capacity.tryAcquire())
        assertEquals(2, capacity.active())

        capacity.release()
        assertTrue(capacity.tryAcquire())
        assertEquals(2, capacity.active())
    }
}
