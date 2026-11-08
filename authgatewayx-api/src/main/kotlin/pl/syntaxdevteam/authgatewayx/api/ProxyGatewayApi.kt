package pl.syntaxdevteam.authgatewayx.api

import java.time.Instant
import java.util.UUID

/** Proxy admission diagnostics only. This API never asserts backend password authentication. */
interface ProxyGatewayApi {
    fun getProxySession(minecraftUuid: UUID): ProxyAdmissionSnapshot?
    fun events(): AuthEventSource
}

data class ProxyAdmissionSnapshot(
    val connectionId: UUID,
    val minecraftUuid: UUID,
    val username: String,
    val mojangVerified: Boolean,
    val connectedAt: Instant,
    val backend: String?,
)
