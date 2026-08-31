package pl.syntaxdevteam.authgatewayx.auth.premium

import pl.syntaxdevteam.authgatewayx.auth.session.SessionRegistry
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.AuthAccount
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthenticationMethod
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityAuditSink
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEvent
import pl.syntaxdevteam.authgatewayx.security.audit.SecurityEventType
import pl.syntaxdevteam.authgatewayx.storage.AccountStorage
import pl.syntaxdevteam.authgatewayx.storage.MojangIdentityBindingResult
import pl.syntaxdevteam.authgatewayx.storage.VerifiedMojangIdentity
import java.net.InetAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletionStage

enum class MojangIdentityHandoffDecision {
    ALLOW_VERIFIED_MOJANG,
    DENY_FORWARDED_IDENTITY_MISMATCH,
}

object MojangIdentityHandoffPolicy {
    fun decide(forwardedMinecraftUuid: UUID, officialMinecraftUuid: UUID): MojangIdentityHandoffDecision =
        if (forwardedMinecraftUuid == officialMinecraftUuid) {
            MojangIdentityHandoffDecision.ALLOW_VERIFIED_MOJANG
        } else {
            MojangIdentityHandoffDecision.DENY_FORWARDED_IDENTITY_MISMATCH
        }
}

data class VerifiedMojangAuthenticationContext(
    val connectionId: ConnectionId,
    val username: AccountUsername,
    val sourceAddress: InetAddress,
    val minecraftUuid: UUID,
)

sealed interface VerifiedMojangAuthenticationResult {
    data class Success(val account: AuthAccount, val migratedFromOffline: Boolean) : VerifiedMojangAuthenticationResult
    data object IdentityConflict : VerifiedMojangAuthenticationResult
}

fun interface VerifiedMojangAuthenticationUseCase {
    fun authenticate(context: VerifiedMojangAuthenticationContext): CompletionStage<VerifiedMojangAuthenticationResult>
}

class VerifiedMojangAuthenticationService(
    private val storage: AccountStorage,
    private val sessions: SessionRegistry,
    private val auditSink: SecurityAuditSink,
    private val clock: Clock = Clock.systemUTC(),
) : VerifiedMojangAuthenticationUseCase {
    override fun authenticate(context: VerifiedMojangAuthenticationContext): CompletionStage<VerifiedMojangAuthenticationResult> {
        val verifiedAt = clock.instant()
        return storage.bindVerifiedMojangIdentity(
            VerifiedMojangIdentity(context.username, context.minecraftUuid, context.sourceAddress, verifiedAt),
        ).thenApply { binding ->
            when (binding) {
                is MojangIdentityBindingResult.Bound -> {
                    sessions.activate(
                        context.connectionId,
                        binding.account.id,
                        context.minecraftUuid,
                        IdentityType.MOJANG,
                        AuthenticationMethod.MOJANG,
                        verifiedAt,
                    )
                    audit(
                        context,
                        binding.account,
                        if (binding.migratedFromOffline) SecurityEventType.OFFLINE_TO_PREMIUM_MIGRATION
                        else SecurityEventType.PREMIUM_VERIFIED,
                        if (binding.migratedFromOffline) "OFFLINE_ACCOUNT_MIGRATED" else "FORWARDED_UUID_MATCH",
                    )
                    VerifiedMojangAuthenticationResult.Success(binding.account, binding.migratedFromOffline)
                }
                MojangIdentityBindingResult.IdentityConflict -> {
                    audit(context, null, SecurityEventType.PREMIUM_AUTH_FAILURE, "IDENTITY_CONFLICT")
                    VerifiedMojangAuthenticationResult.IdentityConflict
                }
            }
        }
    }

    private fun audit(
        context: VerifiedMojangAuthenticationContext,
        account: AuthAccount?,
        type: SecurityEventType,
        reasonCode: String,
    ) {
        runCatching {
            auditSink.record(
                SecurityEvent(
                    timestamp = clock.instant(),
                    accountId = account?.id?.value,
                    minecraftUuid = context.minecraftUuid,
                    username = context.username.value,
                    sourceAddress = context.sourceAddress,
                    type = type,
                    reasonCode = reasonCode,
                ),
            )
        }
    }
}
