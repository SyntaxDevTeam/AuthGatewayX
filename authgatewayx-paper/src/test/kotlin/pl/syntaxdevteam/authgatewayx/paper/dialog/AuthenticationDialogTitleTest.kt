package pl.syntaxdevteam.authgatewayx.paper.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticationDialogTitleTest {
    @Test
    fun `registration title contains the current username`() {
        val title = authenticationDialogTitle(
            Component.text("Rejestracja konta dla {username}"),
            "DialogPlayer",
        )

        assertEquals(
            "Rejestracja konta dla DialogPlayer",
            PlainTextComponentSerializer.plainText().serialize(title),
        )
    }
}
