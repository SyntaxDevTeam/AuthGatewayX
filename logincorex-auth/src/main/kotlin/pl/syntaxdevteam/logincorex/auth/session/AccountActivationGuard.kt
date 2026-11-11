package pl.syntaxdevteam.logincorex.auth.session

import pl.syntaxdevteam.logincorex.domain.account.AuthAccount
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Rechecks account admission after expensive work, immediately before session activation. */
fun interface AccountActivationGuard {
    fun allows(account: AuthAccount, sourceAddress: InetAddress): CompletionStage<Boolean>

    companion object {
        val ALLOW = AccountActivationGuard { _, _ -> CompletableFuture.completedFuture(true) }
    }
}
