package pl.syntaxdevteam.authgatewayx.auth.ui

import pl.syntaxdevteam.authgatewayx.auth.login.LoginResult
import pl.syntaxdevteam.authgatewayx.auth.login.OfflineLoginUseCase
import pl.syntaxdevteam.authgatewayx.auth.registration.OfflineRegistrationUseCase
import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthenticationMethod
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.storage.RegistrationResult
import java.net.InetAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

data class AuthenticationFormContext(
    val connectionId: ConnectionId,
    val username: AccountUsername,
    val sourceAddress: InetAddress,
    val minecraftUuid: UUID,
)

enum class AuthenticationFormResult {
    AUTHENTICATED,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    RATE_LIMITED,
    ACCOUNT_ALREADY_EXISTS,
    IDENTITY_CONFLICT,
}

fun interface AuthenticationFormHandler {
    fun submitLogin(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult>

    fun submitRegistration(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult> =
        CompletableFuture.completedFuture(AuthenticationFormResult.IDENTITY_CONFLICT)
}

fun interface AuthenticationActivationListener {
    fun activated(context: AuthenticationFormContext)
}

class AuthenticationFormCoordinator(
    private val loginService: OfflineLoginUseCase,
    private val registrationService: OfflineRegistrationUseCase,
    private val sessions: SessionRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val activationListener: AuthenticationActivationListener = AuthenticationActivationListener {},
) : AuthenticationFormHandler {
    override fun submitLogin(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult> =
        loginService.login(context.username, context.sourceAddress, password).thenApply { result ->
            when (result) {
                is LoginResult.Success -> {
                    sessions.activate(
                        context.connectionId, result.account.id, result.account.minecraftUuid,
                        IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, clock.instant(),
                    )
                    activationListener.activated(context)
                    AuthenticationFormResult.AUTHENTICATED
                }
                LoginResult.InvalidCredentials -> AuthenticationFormResult.INVALID_CREDENTIALS
                LoginResult.AccountLocked -> AuthenticationFormResult.ACCOUNT_LOCKED
                LoginResult.RateLimited -> AuthenticationFormResult.RATE_LIMITED
            }
        }

    override fun submitRegistration(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult> =
        registrationService.register(context.username, context.sourceAddress, password).thenApply { result ->
            when (result) {
                is RegistrationResult.Created -> {
                    sessions.activate(
                        context.connectionId, result.account.id, result.account.minecraftUuid,
                        IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, clock.instant(),
                    )
                    activationListener.activated(context)
                    AuthenticationFormResult.AUTHENTICATED
                }
                RegistrationResult.UsernameAlreadyExists -> AuthenticationFormResult.ACCOUNT_ALREADY_EXISTS
                RegistrationResult.MinecraftUuidAlreadyExists -> AuthenticationFormResult.IDENTITY_CONFLICT
            }
        }
}

object PasswordConfirmation {
    fun matches(first: CharArray, second: CharArray): Boolean {
        var difference = first.size xor second.size
        val maximum = maxOf(first.size, second.size)
        for (index in 0 until maximum) {
            val left = if (index < first.size) first[index].code else 0
            val right = if (index < second.size) second[index].code else 0
            difference = difference or (left xor right)
        }
        return difference == 0
    }
}
