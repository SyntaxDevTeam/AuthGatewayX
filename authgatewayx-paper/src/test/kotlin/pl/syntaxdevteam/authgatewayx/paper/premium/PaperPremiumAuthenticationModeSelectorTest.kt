package pl.syntaxdevteam.authgatewayx.paper.premium

import kotlin.test.Test
import kotlin.test.assertEquals

class PaperPremiumAuthenticationModeSelectorTest {
    @Test
    fun `uses Velocity handoff when modern forwarding is enabled`() {
        assertEquals(
            PaperPremiumAuthenticationMode.VELOCITY_FORWARDED,
            PaperPremiumAuthenticationModeSelector.select(velocityForwardingEnabled = true),
        )
    }

    @Test
    fun `uses standalone protocol when Velocity forwarding is disabled`() {
        assertEquals(
            PaperPremiumAuthenticationMode.STANDALONE_PROTOCOL,
            PaperPremiumAuthenticationModeSelector.select(velocityForwardingEnabled = false),
        )
    }
}
