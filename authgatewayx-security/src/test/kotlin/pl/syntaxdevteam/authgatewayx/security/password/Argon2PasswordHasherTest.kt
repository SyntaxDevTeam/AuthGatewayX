package pl.syntaxdevteam.authgatewayx.security.password

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Argon2PasswordHasherTest {
    private val hasher = Argon2PasswordHasher(Argon2Parameters(iterations = 1, memoryKiB = 8_192))

    @Test
    fun `hash uses Argon2id and verifies only the correct password`() {
        val password = "correct horse battery staple".toCharArray()
        val hash = hasher.hash(password)

        assertTrue(hash.startsWith("\$argon2id\$"))
        assertTrue(password.all { it == '\u0000' })
        assertTrue(hasher.verify(hash, "correct horse battery staple".toCharArray()))
        assertFalse(hasher.verify(hash, "wrong password".toCharArray()))
    }
}
