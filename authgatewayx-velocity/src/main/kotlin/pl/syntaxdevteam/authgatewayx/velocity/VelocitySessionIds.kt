package pl.syntaxdevteam.authgatewayx.velocity

import com.velocitypowered.api.proxy.InboundConnection
import com.velocitypowered.api.proxy.Player
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Velocity 4.0+ connection identity adapter.
 *
 * getSessionId() became public in Velocity 4.2.1. Never correlate unauthenticated
 * login decisions by IP, TCP port or username on older versions.
 *
 * For older Velocity releases, post-login ids exist only on the authenticated Player
 * object and are dropped on disconnect. Admission is recomputed at LoginEvent instead
 * of trusting a guessed mapping from PreLoginEvent.
 */
internal object VelocitySessionIds {
    private val nativeMethod = InboundConnection::class.java.methods.firstOrNull {
        it.name == "getSessionId" && it.parameterCount == 0 && it.returnType == UUID::class.java
    }
    private val legacy = IdentityHashMap<Player, UUID>()

    fun native(connection: InboundConnection): UUID? =
        runCatching { nativeMethod?.invoke(connection) as? UUID }.getOrNull()

    @Synchronized
    fun id(player: Player): UUID =
        native(player) ?: legacy.getOrPut(player) { UUID.randomUUID() }

    @Synchronized
    fun discard(player: Player): UUID? {
        val id = native(player) ?: legacy[player]
        legacy.remove(player)
        return id
    }

    @Synchronized
    fun clear() = legacy.clear()
}
