package pl.syntaxdevteam.authgatewayx.auth.premium

enum class PremiumMode {
    DISABLED,
    AUTO,
    FORCE_FOR_KNOWN,
    FORCE_FOR_ALL_MATCHING,
}

enum class MojangAuthenticationResult {
    NOT_ATTEMPTED,
    SUCCESS,
    FAILURE,
}

enum class LoginIdentityDecision {
    REQUIRE_MOJANG_AUTHENTICATION,
    ALLOW_VERIFIED_MOJANG,
    ALLOW_OFFLINE_PRE_AUTH,
    DENY_PREMIUM_AUTHENTICATION_FAILED,
}

data class PremiumPolicyConfiguration(
    val mode: PremiumMode = PremiumMode.AUTO,
    val protectPremiumUsernames: Boolean = true,
)

class PremiumLoginPolicy(private val configuration: PremiumPolicyConfiguration) {
    fun decide(
        knownPremiumUsername: Boolean,
        authenticationResult: MojangAuthenticationResult,
    ): LoginIdentityDecision {
        if (authenticationResult == MojangAuthenticationResult.SUCCESS) {
            return LoginIdentityDecision.ALLOW_VERIFIED_MOJANG
        }

        val mustAuthenticateOnline = when (configuration.mode) {
            PremiumMode.DISABLED -> configuration.protectPremiumUsernames && knownPremiumUsername
            PremiumMode.AUTO, PremiumMode.FORCE_FOR_KNOWN -> knownPremiumUsername
            PremiumMode.FORCE_FOR_ALL_MATCHING -> true
        }

        if (!mustAuthenticateOnline) return LoginIdentityDecision.ALLOW_OFFLINE_PRE_AUTH
        if (authenticationResult == MojangAuthenticationResult.NOT_ATTEMPTED) {
            return LoginIdentityDecision.REQUIRE_MOJANG_AUTHENTICATION
        }

        // A protected premium identity never falls back to offline authentication.
        return LoginIdentityDecision.DENY_PREMIUM_AUTHENTICATION_FAILED
    }
}
