package pl.syntaxdevteam.authgatewayx.velocity

import kotlin.test.*

class VelocityRiskConfigurationTest {
    @Test fun `upgrade defaults do not enable external requests or an unrelated database`() {
        val config = VelocityRiskConfiguration.parse(emptyMap())
        assertFalse(config.storageEnabled); assertFalse(config.networkEnabled)
        assertEquals(RiskAction.DENY,config.networkAction); assertEquals(RiskAction.ALERT,config.multiAction)
        assertTrue(config.failClosed)
    }
    @Test fun `explicit modes and invalid or overflowing limits are validated`() {
        val config = VelocityRiskConfiguration.parse(mapOf("multi-account" to mapOf("action" to "DENY"),"risk" to mapOf("failure-strategy" to "FAIL_OPEN")))
        assertEquals(RiskAction.DENY,config.multiAction); assertFalse(config.failClosed)
        assertFailsWith<IllegalArgumentException> { VelocityRiskConfiguration.parse(mapOf("risk" to mapOf("maximum-concurrent" to Long.MAX_VALUE))) }
        assertFailsWith<IllegalArgumentException> { VelocityRiskConfiguration.parse(mapOf("ip-intelligence" to mapOf("timeout-millis" to 0))) }
        assertFailsWith<IllegalArgumentException> { VelocityRiskConfiguration.parse(mapOf("risk" to mapOf("failure-strategy" to "ALLOW_ALL"))) }
    }
}
