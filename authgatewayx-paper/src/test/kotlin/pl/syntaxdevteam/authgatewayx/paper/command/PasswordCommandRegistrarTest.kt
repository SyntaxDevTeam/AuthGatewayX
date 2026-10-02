package pl.syntaxdevteam.authgatewayx.paper.command

import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordCommandRegistrarTest {
    @Test
    fun `online player suggestions are filtered case-insensitively and sorted`() {
        assertEquals(
            listOf("Alex", "alice"),
            matchingOnlinePlayerNames(
                listOf("Zed", "alice", "Beta", "Alex"),
                "a",
            ),
        )
    }

    @Test
    fun `online player suggestions keep all names when input is empty`() {
        assertEquals(
            listOf("Alex", "Beta", "Zed"),
            matchingOnlinePlayerNames(
                listOf("Zed", "Alex", "Beta"),
                "",
            ),
        )
    }
}
