package pl.syntaxdevteam.authgatewayx.domain.account

import java.time.Instant
import java.util.Locale
import java.util.UUID

@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun random(): AccountId = AccountId(UUID.randomUUID())
    }
}

enum class IdentityType {
    MOJANG,
    OFFLINE,
}

enum class AccountState {
    UNREGISTERED,
    REGISTERED,
    AUTHENTICATING,
    AUTHENTICATED,
    LOCKED,
}

@ConsistentCopyVisibility
data class AccountUsername private constructor(
    val value: String,
    val canonical: String,
) {
    init {
        require(validUsername.matches(value)) {
            "Minecraft username must contain 3-16 ASCII letters, digits or underscores"
        }
        require(canonical == value.lowercase(Locale.ROOT)) {
            "Canonical username must be the locale-independent lowercase value"
        }
    }

    companion object {
        private val validUsername = Regex("^[A-Za-z0-9_]{3,16}$")

        fun parse(value: String): AccountUsername {
            require(validUsername.matches(value)) {
                "Minecraft username must contain 3-16 ASCII letters, digits or underscores"
            }
            return AccountUsername(value, value.lowercase(Locale.ROOT))
        }
    }
}

data class AuthAccount(
    val id: AccountId,
    val username: AccountUsername,
    val identityType: IdentityType,
    val minecraftUuid: UUID,
    val state: AccountState,
    val createdAt: Instant,
    val updatedAt: Instant,
)
