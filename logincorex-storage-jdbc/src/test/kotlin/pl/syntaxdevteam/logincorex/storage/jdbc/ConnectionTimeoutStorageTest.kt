package pl.syntaxdevteam.logincorex.storage.jdbc

import java.net.InetAddress
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import pl.syntaxdevteam.logincorex.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.logincorex.security.login.ConnectionTimeoutGate
import pl.syntaxdevteam.logincorex.security.login.DurableConnectionTimeouts
import pl.syntaxdevteam.logincorex.security.flood.NanoTimeSource
import pl.syntaxdevteam.logincorex.storage.ConnectionTimeoutState
import kotlin.test.*

class ConnectionTimeoutStorageTest {
    @Test fun `SQLite migration restart and rollback preserve timeout state`() {
        val file = Files.createTempFile("lcx-timeout-db", ".db")
        val executor = BoundedTaskExecutor(2, 32, "timeout-test")
        val ip = InetAddress.getByName("192.0.2.10")
        var now = Duration.ofDays(20000).toNanos()
        fun gate() = ConnectionTimeoutGate(3, Duration.ZERO, Duration.ofHours(24), 10, NanoTimeSource { now })
        fun storage() = JdbcAccountStorage("jdbc:sqlite:$file", 1, executor)
        try {
            storage().use { storage ->
                storage.migrate().toCompletableFuture().get(10, TimeUnit.SECONDS)
                storage.migrate().toCompletableFuture().get(10, TimeUnit.SECONDS)
                DurableConnectionTimeouts(gate()) { entries ->
                    storage.saveConnectionTimeouts(entries.map { ConnectionTimeoutState(it.address, it.started, it.failures, it.bannedUntil) })
                }.use { store ->
                    assertEquals(2, store.timeout(ip).get(10, TimeUnit.SECONDS))
                }
            }
            storage().use { storage ->
                val entries = storage.loadConnectionTimeouts(10).toCompletableFuture().get(10, TimeUnit.SECONDS)
                assertEquals(1, entries.single().failures)
                val original = entries.single()
                // Duplicate PK fails the batch: DELETE and partial INSERT must roll back together.
                assertFails { storage.saveConnectionTimeouts(listOf(original, original)).toCompletableFuture().get(10, TimeUnit.SECONDS) }
                assertEquals(entries, storage.loadConnectionTimeouts(10).toCompletableFuture().get(10, TimeUnit.SECONDS))
                val gate = gate()
                gate.restore(entries.map { ConnectionTimeoutGate.Entry(it.address, it.started, it.failures, it.bannedUntil) })
                DurableConnectionTimeouts(gate) { snapshot -> storage.saveConnectionTimeouts(snapshot.map {
                    ConnectionTimeoutState(it.address, it.started, it.failures, it.bannedUntil)
                }) }.use { store ->
                    assertEquals(1, store.timeout(ip).get(10, TimeUnit.SECONDS))
                    assertEquals(0, store.timeout(ip).get(10, TimeUnit.SECONDS))
                }
            }
            storage().use { storage ->
                val entries = storage.loadConnectionTimeouts(10).toCompletableFuture().get(10, TimeUnit.SECONDS)
                val gate = gate()
                gate.restore(entries.map { ConnectionTimeoutGate.Entry(it.address, it.started, it.failures, it.bannedUntil) })
                assertFalse(gate.admit(ip))
                now += Duration.ofHours(24).toNanos()
                assertTrue(gate.admit(ip))
            }
        } finally { executor.close(); Files.deleteIfExists(file) }
    }
}
