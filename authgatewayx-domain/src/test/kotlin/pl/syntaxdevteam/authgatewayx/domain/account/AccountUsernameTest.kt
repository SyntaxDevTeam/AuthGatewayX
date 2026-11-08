package pl.syntaxdevteam.authgatewayx.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountUsernameTest {
    @Test
    fun `normalizes lookup key without changing displayed username`() {
        val username = AccountUsername.parse("Example_User")

        assertEquals("Example_User", username.value)
        assertEquals("example_user", username.canonical)
    }

    @Test
    fun `rejects malformed usernames before expensive processing`() {
        listOf("ab", "seventeen_chars_x", "white space", "zażółć").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { AccountUsername.parse(invalid) }
        }
    }
}
