package pl.syntaxdevteam.authgatewayx.auth.session

import pl.syntaxdevteam.authgatewayx.domain.account.AccountId
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.domain.account.IdentityType
import pl.syntaxdevteam.authgatewayx.domain.session.AuthSession
import pl.syntaxdevteam.authgatewayx.domain.session.AuthenticationMethod
import pl.syntaxdevteam.authgatewayx.domain.session.ConnectionId
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySessionRegistryTest {
    private val now = Instant.parse("2026-08-30T10:00:00Z")

    @Test
    fun `disconnect removes all active indexes and is idempotent`() {
        val registry = InMemorySessionRegistry()
        val accountId = AccountId.random()
        val minecraftUuid = UUID.randomUUID()
        val session = connecting("FirstPlayer")
        assertTrue(registry.create(session))
        registry.enterPreAuth(session.connectionId)
        registry.activate(session.connectionId, accountId, minecraftUuid, IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now)

        assertNotNull(registry.getActive(minecraftUuid))
        registry.disconnect(session.connectionId)
        assertNull(registry.getActive(minecraftUuid))
        assertNull(registry.disconnect(session.connectionId))
        assertEquals(0, registry.size())
    }

    @Test
    fun `only one concurrent session can activate the same account`() {
        val registry = InMemorySessionRegistry()
        val accountId = AccountId.random()
        val sessions = listOf(connecting("FirstPlayer"), connecting("SecondPlayer"))
        sessions.forEach { registry.create(it); registry.enterPreAuth(it.connectionId) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = sessions.map { session ->
                executor.submit<Boolean> {
                    start.await()
                    runCatching {
                        registry.activate(session.connectionId, accountId, UUID.randomUUID(), IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now)
                    }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get() })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun connecting(username: String) = AuthSession.connecting(
        ConnectionId.random(),
        AccountUsername.parse(username),
        InetAddress.getLoopbackAddress(),
        now.minusSeconds(1),
    )
}
