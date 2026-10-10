package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.auth.session.*
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.storage.*
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class VerifiedMojangAuthenticationLifecycleTest {
    @Test fun `premium admission denial does not activate or emit success audit`() = exercise("deny")
    @Test fun `premium admission failure remains fail closed`() = exercise("failure")
    @Test fun `disconnect during premium admission prevents late activation`() = exercise("disconnect")

    private fun exercise(mode: String) {
        val now = Instant.parse("2026-10-10T00:00:00Z")
        val name = AccountUsername.parse("PremiumRace")
        val account = AuthAccount(AccountId.random(), name, IdentityType.MOJANG, UUID.randomUUID(), AccountState.REGISTERED, now, now)
        val context = VerifiedMojangAuthenticationContext(ConnectionId.random(), name, InetAddress.getLoopbackAddress(), account.minecraftUuid)
        val sessions = InMemorySessionRegistry()
        sessions.create(AuthSession.connecting(context.connectionId, name, context.sourceAddress, now))
        sessions.enterPreAuth(context.connectionId)
        val binding = CompletableFuture<MojangIdentityBindingResult>()
        val admission = CompletableFuture<Boolean>()
        val storage = Proxy.newProxyInstance(AccountStorage::class.java.classLoader, arrayOf(AccountStorage::class.java)) { _, method, _ ->
            if (method.name == "bindVerifiedMojangIdentity") binding else error("Unexpected storage call ${method.name}")
        } as AccountStorage
        var audits = 0
        val service = VerifiedMojangAuthenticationService(storage, sessions, SecurityAuditSink {
            audits++; CompletableFuture.completedFuture(null)
        }, Clock.fixed(now, ZoneOffset.UTC), AccountActivationGuard { _, _ -> admission })
        val result = service.authenticate(context).toCompletableFuture()
        binding.complete(MojangIdentityBindingResult.Bound(account, false))
        assertFalse(result.isDone)
        when (mode) {
            "deny" -> admission.complete(false)
            "failure" -> admission.completeExceptionally(IllegalStateException("provider unavailable"))
            "disconnect" -> { sessions.disconnect(context.connectionId); admission.complete(true) }
        }
        if (mode == "deny") assertEquals(VerifiedMojangAuthenticationResult.AccessDenied, result.get(2, TimeUnit.SECONDS))
        else assertFails { result.get(2, TimeUnit.SECONDS) }
        assertNull(sessions.getActive(account.minecraftUuid))
        assertEquals(0, audits)
    }
}
