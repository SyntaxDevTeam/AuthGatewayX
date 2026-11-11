package pl.syntaxdevteam.logincorex.auth.session

import pl.syntaxdevteam.logincorex.domain.account.AccountId
import pl.syntaxdevteam.logincorex.domain.account.AccountUsername
import pl.syntaxdevteam.logincorex.domain.account.IdentityType
import pl.syntaxdevteam.logincorex.domain.session.AuthSession
import pl.syntaxdevteam.logincorex.domain.session.AuthenticationMethod
import pl.syntaxdevteam.logincorex.domain.session.ConnectionId
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemorySessionRegistryTest {
    @Test
    fun `shutdown racing activation cannot leave orphan active indexes`() {
        val callers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val registry = InMemorySessionRegistry()
                val session = connecting("RacePlayer")
                val uuid = UUID.randomUUID()
                registry.create(session)
                registry.enterPreAuth(session.connectionId)
                val start = CountDownLatch(1)
                val activation = callers.submit {
                    start.await()
                    runCatching { registry.activate(session.connectionId, AccountId.random(), uuid,
                        IdentityType.OFFLINE, AuthenticationMethod.PASSWORD, now) }
                }
                val clearing = callers.submit { start.await(); registry.clear() }
                start.countDown()
                activation.get(2, TimeUnit.SECONDS)
                clearing.get(2, TimeUnit.SECONDS)
                assertEquals(0, registry.size())
                assertNull(registry.getActive(uuid))
            }
        } finally { callers.shutdownNow() }
    }
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

    @Test
    fun `late callback cannot activate or disconnect a replacement session with the same connection UUID`() {
        val registry = InMemorySessionRegistry()
        val id = ConnectionId.random()
        val name = AccountUsername.parse("Reconnect")
        val old = AuthSession.connecting(id, name, InetAddress.getLoopbackAddress(), now.minusSeconds(1))
        assertTrue(registry.create(old))
        registry.enterPreAuth(id)
        assertNotNull(registry.disconnect(id, old.sessionNonce))
        val replacement = AuthSession.connecting(id, name, InetAddress.getLoopbackAddress(), now.minusSeconds(1))
        assertTrue(registry.create(replacement))
        registry.enterPreAuth(id)

        assertFalse(old.sessionNonce == replacement.sessionNonce)
        assertFailsWith<StaleConnectionSessionException> {
            registry.activate(id, AccountId.random(), id.value, IdentityType.OFFLINE,
                AuthenticationMethod.PASSWORD, now, expectedSessionNonce = old.sessionNonce)
        }
        assertNull(registry.disconnect(id, old.sessionNonce))
        assertEquals(replacement.sessionNonce, registry.get(id)?.sessionNonce)
        assertEquals(pl.syntaxdevteam.logincorex.domain.session.ConnectionState.PRE_AUTH, registry.get(id)?.state)
        assertEquals(1, registry.size())
        assertNotNull(registry.disconnect(id, replacement.sessionNonce))
    }

    private fun connecting(username: String) = AuthSession.connecting(
        ConnectionId.random(),
        AccountUsername.parse(username),
        InetAddress.getLoopbackAddress(),
        now.minusSeconds(1),
    )
}
