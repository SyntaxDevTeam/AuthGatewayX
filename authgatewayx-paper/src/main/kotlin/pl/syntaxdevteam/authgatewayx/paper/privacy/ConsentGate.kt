package pl.syntaxdevteam.authgatewayx.paper.privacy

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Per-connection consent restriction with token checks against stale async completions. */
class ConsentGate {
    private val pending = ConcurrentHashMap<UUID, Any>()
    fun prepare(id: UUID): Any = Any().also { pending[id] = it }
    fun isPending(id: UUID): Boolean = pending.containsKey(id)
    fun token(id: UUID): Any? = pending[id]
    fun isCurrent(id: UUID, token: Any): Boolean = pending[id] === token
    fun release(id: UUID, token: Any): Boolean = pending.remove(id, token)
    fun forget(id: UUID) { pending.remove(id) }
    fun clear() { pending.clear() }
}
