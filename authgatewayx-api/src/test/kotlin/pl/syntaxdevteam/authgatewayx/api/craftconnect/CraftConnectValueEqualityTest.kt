package pl.syntaxdevteam.authgatewayx.api.craftconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CraftConnectValueEqualityTest {
    @Test fun `device keys compare by content and preserve hash collection lookup`() {
        val original = CraftConnectDevice("device", byteArrayOf(1, 2, 3))
        val equivalent = CraftConnectDevice("device", byteArrayOf(1, 2, 3))
        assertEquals(original, equivalent)
        assertEquals(original.hashCode(), equivalent.hashCode())
        assertEquals("paired", mapOf(original to "paired")[equivalent])
        assertNotEquals(original, equivalent.copy(publicKey = byteArrayOf(3, 2, 1)))
    }

    @Test fun `pairing wire messages compare byte arrays by content`() {
        val original = CraftConnectMessage.PairingBegin("device", byteArrayOf(1, 2))
        val decoded = CraftConnectMessage.PairingBegin("device", byteArrayOf(1, 2))
        assertEquals(original, decoded)
        assertEquals(original.hashCode(), decoded.hashCode())
        assertNotEquals(original, decoded.copy(publicKey = byteArrayOf(2, 1)))
    }
}
