package pl.syntaxdevteam.authgatewayx.integrations

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginAdmissionServiceTest {
    private val username = AccountUsername.parse("AdmissionUser")
    private val address = InetAddress.getLoopbackAddress()

    @Test
    fun `cleaner denial stops punishment lookup`() {
        var punishmentCalls = 0
        val service = LoginAdmissionService(
            UsernamePolicyProvider { CompletableFuture.completedFuture(UsernameVerdict.DENY_PROFANITY) },
            PunishmentProvider { punishmentCalls++; CompletableFuture.completedFuture(LoginPunishmentResult.Allow) },
            FailureStrategy.FAIL_CLOSED,
            FailureStrategy.FAIL_CLOSED,
        )

        val decision = service.check(username, null, address).toCompletableFuture().get()

        assertIs<LoginAdmissionDecision.Deny>(decision)
        assertEquals(0, punishmentCalls)
    }

    @Test
    fun `required unavailable punishment provider fails closed`() {
        val service = LoginAdmissionService(
            null,
            PunishmentProvider { CompletableFuture.completedFuture(LoginPunishmentResult.Unavailable()) },
            FailureStrategy.FAIL_OPEN,
            FailureStrategy.FAIL_CLOSED,
        )

        assertEquals(
            LoginAdmissionDecision.Deny("PUNISHMENT_UNAVAILABLE"),
            service.check(username, null, address).toCompletableFuture().get(),
        )
    }
}
