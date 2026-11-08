package pl.syntaxdevteam.authgatewayx.paper.isolation

import pl.syntaxdevteam.authgatewayx.security.flood.PreAuthCapacity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PreAuthAdmission(maximum: Int) {
    private val capacity = PreAuthCapacity(maximum)
    private val leases = ConcurrentHashMap<UUID, PreAuthCapacity.Lease>()

    fun acquire(playerId: UUID): Boolean {
        if (leases.containsKey(playerId)) return true
        val lease = capacity.tryAcquire() ?: return false
        val existing = leases.putIfAbsent(playerId, lease)
        if (existing != null) lease.close()
        return true
    }

    fun release(playerId: UUID) { leases.remove(playerId)?.close() }
    fun clear() { leases.keys.toList().forEach(::release) }
}
