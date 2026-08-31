package pl.syntaxdevteam.authgatewayx.auth.ui

import pl.syntaxdevteam.authgatewayx.auth.login.LoginResult
import pl.syntaxdevteam.authgatewayx.auth.login.OfflineLoginUseCase
import pl.syntaxdevteam.authgatewayx.auth.registration.OfflineRegistrationUseCase
import pl.syntaxdevteam.authgatewayx.auth.registration.RegistrationOutcome
import pl.syntaxdevteam.authgatewayx.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.*
import pl.syntaxdevteam.authgatewayx.domain.session.*
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthenticationFormCoordinatorTest {
    @Test
    fun `successful dialog login atomically activates pre-auth session`() {
        val now = Instant.parse("2026-08-30T10:00:00Z")
        val username = AccountUsername.parse("DialogPlayer")
        val account = AuthAccount(
            AccountId.random(), username, IdentityType.OFFLINE, OfflineIdentity.minecraftUuid(username),
            AccountState.REGISTERED, now, now,
        )
        val sessions = InMemorySessionRegistry()
        val context = AuthenticationFormContext(
            ConnectionId.random(), username, InetAddress.getLoopbackAddress(), account.minecraftUuid,
        )
        sessions.create(AuthSession.connecting(context.connectionId, username, context.sourceAddress, now.minusSeconds(1)))
        sessions.enterPreAuth(context.connectionId)
        val coordinator = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, password ->
                password.fill('\u0000')
                CompletableFuture.completedFuture(LoginResult.Success(account))
            },
            OfflineRegistrationUseCase { _, _, _ ->
                CompletableFuture.completedFuture(RegistrationOutcome.UsernameAlreadyExists)
            },
            sessions,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        assertEquals(
            AuthenticationFormResult.AUTHENTICATED,
            coordinator.submitLogin(context, "secret-password".toCharArray()).toCompletableFuture().get(),
        )
        assertEquals(ConnectionState.ACTIVE, sessions.get(context.connectionId)?.state)
    }

    @Test
    fun `registration limiter result keeps session in pre-auth`() {
        val now = Instant.parse("2026-08-30T10:00:00Z")
        val username = AccountUsername.parse("LimitedPlayer")
        val sessions = InMemorySessionRegistry()
        val context = AuthenticationFormContext(
            ConnectionId.random(), username, InetAddress.getLoopbackAddress(), OfflineIdentity.minecraftUuid(username),
        )
        sessions.create(AuthSession.connecting(context.connectionId, username, context.sourceAddress, now))
        sessions.enterPreAuth(context.connectionId)
        val coordinator = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, _ -> CompletableFuture.completedFuture(LoginResult.RateLimited) },
            OfflineRegistrationUseCase { _, _, password ->
                password.fill('\u0000')
                CompletableFuture.completedFuture(RegistrationOutcome.RateLimited)
            },
            sessions,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        assertEquals(
            AuthenticationFormResult.RATE_LIMITED,
            coordinator.submitRegistration(context, "secure-password".toCharArray()).toCompletableFuture().get(),
        )
        assertEquals(ConnectionState.PRE_AUTH, sessions.get(context.connectionId)?.state)
    }
}
