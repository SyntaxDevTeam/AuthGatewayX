package pl.syntaxdevteam.authgatewayx.paper.premium

import io.papermc.paper.adventure.PaperAdventure
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PremiumDisconnectReasonTest {
    @Test
    fun `replaces only vanilla invalid-session reason with AuthGatewayX explanation`() {
        val replacement = Component.text("AuthGatewayX: nie potwierdzono sesji konta premium.")

        val result = replaceInvalidPremiumSessionReason(
            net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.unverified_username"),
            replacement,
        )

        assertEquals(replacement, PaperAdventure.asAdventure(result))
    }

    @Test
    fun `preserves unrelated disconnect reason`() {
        val original = net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.authservers_down")

        val result = replaceInvalidPremiumSessionReason(original, Component.text("replacement"))

        assertSame(original, result)
    }
}
