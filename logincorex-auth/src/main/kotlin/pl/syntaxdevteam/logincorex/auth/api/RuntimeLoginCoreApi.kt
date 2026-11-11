package pl.syntaxdevteam.logincorex.auth.api

import pl.syntaxdevteam.logincorex.integrations.api.BoundedAuthEventBus
import pl.syntaxdevteam.logincorex.api.*
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.AuthAccount
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.ConnectionState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class RuntimeLoginCoreApi(
    private val ready: () -> Boolean,
    private val live: (UUID) -> AuthSession?,
    private val byUuid: (UUID) -> CompletionStage<AuthAccount?>,
    private val byName: (AccountUsername) -> CompletionStage<AuthAccount?>,
    private val revoke: (AuthSession) -> CompletionStage<Boolean>,
    private val bus: BoundedAuthEventBus,
    private val now: () -> Instant = Instant::now,
) : LoginCoreApi, AutoCloseable {
    @Volatile private var closed = false
    private fun available() = !closed && ready()
    private fun active(id: UUID): AuthSession? {
        if (!available()) return null
        return live(id)?.takeIf {
            it.minecraftUuid == id && it.state == ConnectionState.ACTIVE &&
                (it.expiresAt?.isAfter(now()) != false)
        }
    }
    override fun isAuthenticated(minecraftUuid: UUID) = getAuthentication(minecraftUuid) != null
    override fun getAuthentication(minecraftUuid: UUID) = active(minecraftUuid)?.snapshot()
    override fun getSession(minecraftUuid: UUID) = getAuthentication(minecraftUuid)
    override fun getIdentityType(minecraftUuid: UUID) = getAuthentication(minecraftUuid)?.identityType
    override fun getAccount(minecraftUuid: UUID): AccountSnapshot? = active(minecraftUuid)?.let {
        AccountSnapshot(it.accountId!!.value, minecraftUuid, it.username.value, it.identityType!!)
    }
    override fun findAccount(minecraftUuid: UUID): CompletionStage<AccountSnapshot?> = lookup { byUuid(minecraftUuid) }
    override fun findAccountByUsername(username: String): CompletionStage<AccountSnapshot?> = lookup { byName(AccountUsername.parse(username)) }
    private fun lookup(operation: () -> CompletionStage<AuthAccount?>): CompletionStage<AccountSnapshot?> {
        if (!available()) return CompletableFuture.failedFuture(IllegalStateException("LoginCoreX unavailable"))
        return try {
            operation().thenApply { account ->
                check(available()) { "LoginCoreX unavailable" }
                account?.let { AccountSnapshot(it.id.value, it.minecraftUuid, it.username.value, it.identityType) }
            }.toCompletableFuture().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
    }
    override fun invalidateSession(minecraftUuid: UUID): CompletionStage<Boolean> {
        val session = active(minecraftUuid) ?: return CompletableFuture.completedFuture(false)
        return try { revoke(session) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
    }
    override fun events(): AuthEventSource = bus
    override fun close() { closed = true; bus.close() }
}

fun AuthSession.snapshot(): AuthenticationSnapshot? {
    if (state != ConnectionState.ACTIVE) return null
    return AuthenticationSnapshot(minecraftUuid ?: return null, identityType ?: return null,
        authenticationMethod ?: return null, authenticatedAt ?: return null, expiresAt)
}
