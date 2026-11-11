package pl.syntaxdevteam.logincorex.auth.ui

import pl.syntaxdevteam.logincorex.auth.login.LoginResult
import pl.syntaxdevteam.logincorex.auth.login.OfflineLoginUseCase
import pl.syntaxdevteam.logincorex.auth.registration.OfflineRegistrationUseCase
import pl.syntaxdevteam.logincorex.auth.registration.RegistrationOutcome
import pl.syntaxdevteam.logincorex.auth.session.InMemorySessionRegistry
import pl.syntaxdevteam.logincorex.domain.account.*
import pl.syntaxdevteam.logincorex.domain.session.*
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `successful first-entry registration activates session and invokes success hook`() {
        val now = Instant.parse("2026-08-30T10:00:00Z")
        val username = AccountUsername.parse("FirstEntry")
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
        var activatedAfterSessionTransition = false
        val coordinator = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, _ -> CompletableFuture.completedFuture(LoginResult.InvalidCredentials) },
            OfflineRegistrationUseCase { _, _, password ->
                password.fill('\u0000')
                CompletableFuture.completedFuture(RegistrationOutcome.Created(account))
            },
            sessions,
            Clock.fixed(now, ZoneOffset.UTC),
            AuthenticationActivationListener {
                activatedAfterSessionTransition = sessions.get(it.connectionId)?.state == ConnectionState.ACTIVE
            },
        )

        assertEquals(
            AuthenticationFormResult.AUTHENTICATED,
            coordinator.submitRegistration(context, "secure-password".toCharArray()).toCompletableFuture().get(),
        )
        assertEquals(ConnectionState.ACTIVE, sessions.get(context.connectionId)?.state)
        assertTrue(activatedAfterSessionTransition)
    }
    @Test
    fun verifiedAccountCanLogInWithServerPasswordWithoutLosingMojangIdentity() {
        val now = Instant.parse("2026-08-30T10:00:00Z")
        val username = AccountUsername.parse("VerifiedPlayer")
        val officialUuid = java.util.UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val account = AuthAccount(AccountId.random(), username, IdentityType.MOJANG,
            officialUuid, AccountState.REGISTERED, now, now)
        val sessions = InMemorySessionRegistry()
        val context = AuthenticationFormContext(ConnectionId.random(), username,
            InetAddress.getLoopbackAddress(), officialUuid, verifiedMojang = true)
        sessions.create(AuthSession.connecting(context.connectionId, username, context.sourceAddress, now))
        sessions.enterPreAuth(context.connectionId)
        val coordinator = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, password ->
                password.fill('\u0000')
                CompletableFuture.completedFuture(LoginResult.Success(account))
            },
            OfflineRegistrationUseCase { _, _, _ ->
                CompletableFuture.completedFuture(RegistrationOutcome.IdentityConflict)
            },
            sessions,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        assertEquals(AuthenticationFormResult.AUTHENTICATED,
            coordinator.submitLogin(context, "correct-password".toCharArray()).toCompletableFuture().get())
        assertEquals(IdentityType.MOJANG, sessions.get(context.connectionId)?.identityType)
        assertEquals(AuthenticationMethod.PASSWORD, sessions.get(context.connectionId)?.authenticationMethod)
        assertEquals(officialUuid, sessions.get(context.connectionId)?.minecraftUuid)
    }

    @Test
    fun passwordAuthenticationRejectsAccountWithDifferentVerifiedUuid() {
        val now = Instant.parse("2026-08-30T10:00:00Z")
        val username = AccountUsername.parse("VerifiedPlayer")
        val account = AuthAccount(AccountId.random(), username, IdentityType.MOJANG,
            java.util.UUID.randomUUID(), AccountState.REGISTERED, now, now)
        val sessions = InMemorySessionRegistry()
        val context = AuthenticationFormContext(ConnectionId.random(), username,
            InetAddress.getLoopbackAddress(), java.util.UUID.randomUUID(), verifiedMojang = true)
        sessions.create(AuthSession.connecting(context.connectionId, username, context.sourceAddress, now))
        sessions.enterPreAuth(context.connectionId)
        val coordinator = AuthenticationFormCoordinator(
            OfflineLoginUseCase { _, _, _ -> CompletableFuture.completedFuture(LoginResult.Success(account)) },
            OfflineRegistrationUseCase { _, _, _ ->
                CompletableFuture.completedFuture(RegistrationOutcome.IdentityConflict)
            },
            sessions,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        assertEquals(AuthenticationFormResult.ACCESS_DENIED,
            coordinator.submitLogin(context, "correct-password".toCharArray()).toCompletableFuture().get())
        assertEquals(ConnectionState.PRE_AUTH, sessions.get(context.connectionId)?.state)
    }

}
