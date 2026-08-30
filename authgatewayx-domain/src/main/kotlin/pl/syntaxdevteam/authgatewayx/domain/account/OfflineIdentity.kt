package pl.syntaxdevteam.authgatewayx.domain.account

import java.nio.charset.StandardCharsets
import java.util.UUID

object OfflineIdentity {
    fun minecraftUuid(username: AccountUsername): UUID = UUID.nameUUIDFromBytes(
        "OfflinePlayer:${username.value}".toByteArray(StandardCharsets.UTF_8),
    )
}
