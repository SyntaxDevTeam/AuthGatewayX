package pl.syntaxdevteam.authgatewayx.api

import java.util.UUID

/**
 * Read-only, non-blocking authentication status for a live Minecraft UUID.
 * Paper registers this contract in Bukkit ServicesManager after runtime installation.
 * false covers absent, pre-auth, disconnected, expired and unavailable sessions.
 * No passwords, account records or network addresses are exposed.
 */
fun interface AuthenticationStatusProvider {
    fun isAuthenticated(minecraftUuid: UUID): Boolean
}
