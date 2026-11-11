@file:Suppress("UnstableApiUsage")
package pl.syntaxdevteam.logincorex.paper.dialog

import com.destroystokyo.paper.profile.PlayerProfile
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import pl.syntaxdevteam.logincorex.auth.session.*
import pl.syntaxdevteam.logincorex.auth.ui.*
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class ConfigurationAuthenticationCancellationTest {
    private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    @Test fun `cancel login or registration closes ticket and rejects late completion`() {
        for (register in listOf(false, true)) {
            val id = UUID.randomUUID()
            val gate = PreJoinConnectionGate(1)
            val ticket = gate.tryOpen(ConnectionId(id))!!
            val outcome = CompletableFuture<AuthenticationFormResult>()
            var displays = 0
            var disconnected = false
            var notifications = 0
            val profile = proxy<PlayerProfile> { if (it == "getId") id else null }
            val connection = proxy<PlayerConfigurationConnection> {
                when (it) {
                    "getProfile" -> profile
                    "isConnected" -> !disconnected
                    "getAudience" -> Audience.empty()
                    "disconnect" -> { disconnected = true; null }
                    else -> null
                }
            }
            val renderer = object : AuthenticationDialogPresentation {
                override fun presentLogin(audience: Audience, username: String, feedback: Component?) { displays++ }
                override fun presentRegistration(audience: Audience, username: String, feedback: Component?) { displays++ }
            }
            val text = Component.text("test")
            val messages = AuthenticationDialogText(text,text,text,text,text,text,text,text,text,text,text,text,text,text,text)
            val handler = object : AuthenticationFormHandler {
                override fun submitLogin(context: AuthenticationFormContext, password: CharArray) = outcome.also { password.fill('\u0000') }
                override fun submitRegistration(context: AuthenticationFormContext, password: CharArray) = submitLogin(context, password)
            }
            val controller = ConfigurationAuthenticationDialogController(handler, renderer, InMemorySessionRegistry(), gate, messages,
                onOutcome = { _, _ -> notifications++ })
            val context = AuthenticationFormContext(ConnectionId(id), AccountUsername.parse("Tester"), InetAddress.getLoopbackAddress(), id)
            assertTrue(if (register) controller.showRegistration(connection, ticket, context) else controller.showLogin(connection, ticket, context))
            fun click(key: String) = object : PlayerCustomClickEvent(Key.key(key), connection) {
                override fun getTag() = null
                override fun getDialogResponseView(): DialogResponseView = proxy { if (it == "getText") "password" else null }
            }
            controller.onCustomClick(click(if (register) "logincorex:register_submit" else "logincorex:login_submit"))
            controller.onCustomClick(click("logincorex:auth_cancel"))
            assertTrue(disconnected)
            assertEquals(PreJoinConnectionGate.Outcome.DENIED, gate.await(ticket, Duration.ofSeconds(1)))
            assertEquals(0, gate.pendingCount())
            outcome.complete(AuthenticationFormResult.AUTHENTICATED)
            assertEquals(1, displays)
            assertEquals(0, notifications)
            assertFalse(gate.authenticated(ticket))
            val reconnect = gate.tryOpen(ConnectionId(id))!!
            controller.onCustomClick(click("logincorex:auth_cancel"))
            assertTrue(gate.isPending(reconnect))
        }
    }
}
