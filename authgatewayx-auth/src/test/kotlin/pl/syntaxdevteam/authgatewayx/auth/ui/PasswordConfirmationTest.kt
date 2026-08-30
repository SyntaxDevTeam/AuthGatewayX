package pl.syntaxdevteam.authgatewayx.auth.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordConfirmationTest {
    @Test
    fun `matches equal passwords and rejects content or length differences`() {
        assertTrue(PasswordConfirmation.matches("same-password".toCharArray(), "same-password".toCharArray()))
        assertFalse(PasswordConfirmation.matches("same-password".toCharArray(), "other-password".toCharArray()))
        assertFalse(PasswordConfirmation.matches("same-password".toCharArray(), "same-password-longer".toCharArray()))
    }
}
