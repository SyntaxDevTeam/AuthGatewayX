package pl.syntaxdevteam.authgatewayx.security.password

import de.mkammerer.argon2.Argon2Factory

data class Argon2Parameters(
    val iterations: Int = 3,
    val memoryKiB: Int = 65_536,
    val parallelism: Int = 1,
) {
    init {
        require(iterations > 0 && memoryKiB >= 8_192 && parallelism > 0)
    }
}

class Argon2PasswordHasher(private val parameters: Argon2Parameters = Argon2Parameters()) {
    fun hash(password: CharArray): String {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        return try {
            require(password.isNotEmpty()) { "Password cannot be empty" }
            argon2.hash(parameters.iterations, parameters.memoryKiB, parameters.parallelism, password)
        } finally {
            argon2.wipeArray(password)
        }
    }

    fun verify(encodedHash: String, password: CharArray): Boolean {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        return try {
            argon2.verify(encodedHash, password)
        } finally {
            argon2.wipeArray(password)
        }
    }
}
