package pl.syntaxdevteam.authgatewayx.paper.premium

internal enum class PaperPremiumAuthenticationMode {
    STANDALONE_PROTOCOL,
    VELOCITY_FORWARDED,
}

internal object PaperPremiumAuthenticationModeSelector {
    fun select(velocityForwardingEnabled: Boolean): PaperPremiumAuthenticationMode =
        if (velocityForwardingEnabled) {
            PaperPremiumAuthenticationMode.VELOCITY_FORWARDED
        } else {
            PaperPremiumAuthenticationMode.STANDALONE_PROTOCOL
        }
}
