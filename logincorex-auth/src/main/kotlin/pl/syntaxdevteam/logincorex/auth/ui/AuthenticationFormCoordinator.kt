package pl.syntaxdevteam.logincorex.auth.ui

import pl.syntaxdevteam.logincorex.auth.login.LoginResult
import pl.syntaxdevteam.logincorex.auth.login.OfflineLoginUseCase
import pl.syntaxdevteam.logincorex.auth.registration.OfflineRegistrationUseCase
import pl.syntaxdevteam.logincorex.auth.registration.RegistrationOutcome
import pl.syntaxdevteam.logincorex.auth.session.SessionRegistry
import pl.syntaxdevteam.logincorex.auth.session.AccountActivationGuard
import pl.syntaxdevteam.logincorex.domain.account.AuthAccount
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.domain.session.AuthenticationMethod
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
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
    val sessionNonce: UUID? = null,
    val verifiedMojang: Boolean = false,
)

enum class AuthenticationFormResult {
    AUTHENTICATED,
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    RATE_LIMITED,
    ACCOUNT_ALREADY_EXISTS,
    IDENTITY_CONFLICT,
    ACCESS_DENIED,
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
    private val activationGuard: AccountActivationGuard = AccountActivationGuard.ALLOW,
    private val completionObserver: (AuthenticationFormContext, AuthenticationFormResult?) -> Unit = { _, _ -> },
) : AuthenticationFormHandler {
    override fun submitLogin(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult> =
        loginService.login(context.username, context.sourceAddress, password).thenCompose { result ->
            when (result) {
                null -> error("Async authentication operation returned null: result")
                is LoginResult.Success -> {
                    activate(context, result.account)
                }
                LoginResult.InvalidCredentials -> completed(AuthenticationFormResult.INVALID_CREDENTIALS)
                LoginResult.AccountLocked -> completed(AuthenticationFormResult.ACCOUNT_LOCKED)
                LoginResult.RateLimited -> completed(AuthenticationFormResult.RATE_LIMITED)
            }
        }.whenComplete { result, _ -> runCatching { completionObserver(context, result) } }

    override fun submitRegistration(context: AuthenticationFormContext, password: CharArray): CompletionStage<AuthenticationFormResult> =
        (if (context.verifiedMojang) {
            registrationService.registerVerified(context.username, context.sourceAddress, context.minecraftUuid, password)
        } else {
            registrationService.register(context.username, context.sourceAddress, password)
        }).thenCompose { result ->
            when (result) {
                null -> error("Async authentication operation returned null: result")
                is RegistrationOutcome.Created -> {
                    activate(context, result.account)
                }
                RegistrationOutcome.UsernameAlreadyExists -> completed(AuthenticationFormResult.ACCOUNT_ALREADY_EXISTS)
                RegistrationOutcome.IdentityConflict -> completed(AuthenticationFormResult.IDENTITY_CONFLICT)
                RegistrationOutcome.RateLimited -> completed(AuthenticationFormResult.RATE_LIMITED)
            }
        }.whenComplete { result, _ -> runCatching { completionObserver(context, result) } }

    private fun activate(context: AuthenticationFormContext, account: AuthAccount): CompletionStage<AuthenticationFormResult> =
        activationGuard.allows(account, context.sourceAddress).thenApply { allowed ->
            val requiredIdentity = if (context.verifiedMojang) IdentityType.MOJANG else IdentityType.OFFLINE
            if (allowed != true || account.identityType != requiredIdentity ||
                account.minecraftUuid != context.minecraftUuid ||
                account.username.canonical != context.username.canonical
            ) AuthenticationFormResult.ACCESS_DENIED
            else {
                sessions.activate(context.connectionId, account.id, account.minecraftUuid,
                    account.identityType, AuthenticationMethod.PASSWORD, clock.instant(),
                    expectedSessionNonce = context.sessionNonce)
                activationListener.activated(context)
                AuthenticationFormResult.AUTHENTICATED
            }
        }

    private fun completed(result: AuthenticationFormResult) = CompletableFuture.completedFuture(result)
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
