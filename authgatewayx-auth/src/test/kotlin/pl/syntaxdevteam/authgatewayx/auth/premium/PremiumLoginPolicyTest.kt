package pl.syntaxdevteam.authgatewayx.auth.premium

import kotlin.test.Test
import kotlin.test.assertEquals

class PremiumLoginPolicyTest {
    @Test
    fun `known premium username is denied after failed Mojang authentication`() {
        val policy = PremiumLoginPolicy(PremiumPolicyConfiguration())

        assertEquals(
            LoginIdentityDecision.DENY_PREMIUM_AUTHENTICATION_FAILED,
            policy.decide(true, MojangAuthenticationResult.FAILURE),
        )
    }

    @Test
    fun `known premium username requires Mojang before an attempt`() {
        val policy = PremiumLoginPolicy(PremiumPolicyConfiguration())

        assertEquals(
            LoginIdentityDecision.REQUIRE_MOJANG_AUTHENTICATION,
            policy.decide(true, MojangAuthenticationResult.NOT_ATTEMPTED),
        )
    }

    @Test
    fun `unknown username enters offline pre-auth in auto mode`() {
        val policy = PremiumLoginPolicy(PremiumPolicyConfiguration())

        assertEquals(
            LoginIdentityDecision.ALLOW_OFFLINE_PRE_AUTH,
            policy.decide(false, MojangAuthenticationResult.NOT_ATTEMPTED),
        )
    }

    @Test
    fun `force all mode requires Mojang even for unknown username`() {
        val policy = PremiumLoginPolicy(PremiumPolicyConfiguration(mode = PremiumMode.FORCE_FOR_ALL_MATCHING))

        assertEquals(
            LoginIdentityDecision.REQUIRE_MOJANG_AUTHENTICATION,
            policy.decide(false, MojangAuthenticationResult.NOT_ATTEMPTED),
        )
    }

    @Test
    fun `disabled mode still protects known premium usernames by default`() {
        val policy = PremiumLoginPolicy(PremiumPolicyConfiguration(mode = PremiumMode.DISABLED))

        assertEquals(
            LoginIdentityDecision.DENY_PREMIUM_AUTHENTICATION_FAILED,
            policy.decide(true, MojangAuthenticationResult.FAILURE),
        )
    }
}
