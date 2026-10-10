package pl.syntaxdevteam.authgatewayx.auth.ui

import pl.syntaxdevteam.authgatewayx.auth.login.*
import pl.syntaxdevteam.authgatewayx.auth.registration.*
import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.auth.session.AccountActivationGuard
import pl.syntaxdevteam.authgatewayx.integrations.*
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import java.net.InetAddress
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AuthenticationLifecycleRaceTest {
    @Test
    fun `ban appearing during password verification denies activation`() {
        val context = context()
        val verification = CompletableFuture<LoginResult>()
        var banned = false
        val admission = LoginAdmissionService(null, PunishmentProvider {
            CompletableFuture.completedFuture(if (banned) LoginPunishmentResult.Deny("TEST_BAN") else LoginPunishmentResult.Allow)
        }, FailureStrategy.FAIL_CLOSED, FailureStrategy.FAIL_CLOSED)
        assertEquals(LoginAdmissionDecision.Allow, admission.check(name, account.minecraftUuid, context.sourceAddress).toCompletableFuture().get())
        val handler = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, password -> password.fill('\u0000'); verification },
            OfflineRegistrationUseCase { _, _, _ -> CompletableFuture.completedFuture(RegistrationOutcome.IdentityConflict) },
            sessions, Clock.fixed(now, ZoneOffset.UTC), AuthenticationActivationListener { activations.incrementAndGet() },
            AccountActivationGuard { account, address ->
                admission.check(account.username, account.minecraftUuid, address).thenApply { it == LoginAdmissionDecision.Allow }
            },
        )
        val result = handler.submitLogin(context, "synthetic".toCharArray()).toCompletableFuture()
        banned = true
        verification.complete(LoginResult.Success(account))
        assertEquals(AuthenticationFormResult.ACCESS_DENIED, result.get(2, TimeUnit.SECONDS))
        assertEquals(ConnectionState.PRE_AUTH, sessions.get(context.connectionId)?.state)
        assertEquals(0, activations.get())
    }

    @Test
    fun `admission failure after registration cannot release pre-auth`() {
        val context = context()
        val handler = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, _ -> CompletableFuture.completedFuture(LoginResult.InvalidCredentials) },
            OfflineRegistrationUseCase { _, _, password -> password.fill('\u0000'); CompletableFuture.completedFuture(RegistrationOutcome.Created(account)) },
            sessions, Clock.fixed(now, ZoneOffset.UTC), AuthenticationActivationListener { activations.incrementAndGet() },
            AccountActivationGuard { _, _ -> CompletableFuture.failedFuture(IllegalStateException("provider unavailable")) },
        )
        assertFails { handler.submitRegistration(context, "synthetic".toCharArray()).toCompletableFuture().get(2, TimeUnit.SECONDS) }
        assertEquals(ConnectionState.PRE_AUTH, sessions.get(context.connectionId)?.state)
        assertEquals(0, activations.get())
    }

    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private val name = AccountUsername.parse("RacePlayer")
    private val account = AuthAccount(AccountId.random(), name, IdentityType.OFFLINE,
        OfflineIdentity.minecraftUuid(name), AccountState.REGISTERED, now, now)
    private val sessions = InMemorySessionRegistry()
    private val activations = AtomicInteger()

    private fun context(): AuthenticationFormContext {
        val context = AuthenticationFormContext(ConnectionId.random(), name, InetAddress.getLoopbackAddress(), account.minecraftUuid)
        sessions.create(AuthSession.connecting(context.connectionId, name, context.sourceAddress, now))
        sessions.enterPreAuth(context.connectionId)
        return context
    }

    private fun coordinator(login: OfflineLoginUseCase, registration: OfflineRegistrationUseCase =
        OfflineRegistrationUseCase { _, _, _ -> CompletableFuture.completedFuture(RegistrationOutcome.IdentityConflict) }) =
        AuthenticationFormCoordinator(login, registration, sessions, Clock.fixed(now, ZoneOffset.UTC),
            AuthenticationActivationListener { activations.incrementAndGet() })

    @Test
    fun `two simultaneous successful password verifications activate only one connection`() {
        val pending = List(2) { CompletableFuture<LoginResult>() }
        val index = AtomicInteger()
        val handler = coordinator(OfflineLoginUseCase { _, _, password -> password.fill('\u0000'); pending[index.getAndIncrement()] })
        val contexts = List(2) { context() }
        val results = contexts.map { handler.submitLogin(it, "synthetic".toCharArray()).toCompletableFuture() }
        val callers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val completion = pending.map { future -> callers.submit { start.await(); future.complete(LoginResult.Success(account)) } }
            start.countDown()
            completion.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, results.count { !it.isCompletedExceptionally && it.get(2, TimeUnit.SECONDS) == AuthenticationFormResult.AUTHENTICATED })
            assertEquals(1, results.count { it.isCompletedExceptionally })
            assertEquals(1, activations.get())
            assertEquals(1, contexts.count { sessions.get(it.connectionId)?.state == ConnectionState.ACTIVE })
        } finally { callers.shutdownNow() }
    }

    @Test
    fun `disconnect or shutdown before password callback cannot reactivate a session`() {
        for (shutdown in listOf(false, true)) {
            val pending = CompletableFuture<LoginResult>()
            val context = context()
            val result = coordinator(OfflineLoginUseCase { _, _, password -> password.fill('\u0000'); pending })
                .submitLogin(context, "synthetic".toCharArray()).toCompletableFuture()
            if (shutdown) sessions.clear() else sessions.disconnect(context.connectionId)
            pending.complete(LoginResult.Success(account))
            assertTrue(result.isCompletedExceptionally)
            assertNull(sessions.getActive(account.minecraftUuid))
            assertEquals(0, activations.get())
        }
    }

    @Test
    fun `late registration callback does not recreate a disconnected session`() {
        val pending = CompletableFuture<RegistrationOutcome>()
        val context = context()
        val result = coordinator(OfflineLoginUseCase { _, _, _ -> CompletableFuture.completedFuture(LoginResult.InvalidCredentials) },
            OfflineRegistrationUseCase { _, _, password -> password.fill('\u0000'); pending })
            .submitRegistration(context, "synthetic".toCharArray()).toCompletableFuture()
        sessions.disconnect(context.connectionId)
        pending.complete(RegistrationOutcome.Created(account))
        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, sessions.size())
        assertEquals(0, activations.get())
    }
}
