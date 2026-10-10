package pl.syntaxdevteam.authgatewayx.security.benchmark

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pl.syntaxdevteam.authgatewayx.security.bot.*
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import pl.syntaxdevteam.authgatewayx.security.flood.*
import pl.syntaxdevteam.authgatewayx.security.password.*
import pl.syntaxdevteam.authgatewayx.security.registration.RegistrationAttemptGate
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.ceil

/** No sockets, DNS, server addresses or real credentials are used by this harness. */
@Tag("benchmark")
class SecurityBenchmark {
    private data class Measurement(val name: String, val nanos: LongArray, val elapsed: Long) {
        fun json(): String {
            val sorted = nanos.sorted()
            fun percentile(fraction: Double) = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
            return """{"name":"$name","samples":${nanos.size},"p50_ms":${percentile(.50)},"p95_ms":${percentile(.95)},"p99_ms":${percentile(.99)},"operations_per_second":${nanos.size * 1_000_000_000.0 / elapsed}}"""
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `measure actual Argon2 and bounded guard workloads`() {
        val samples = System.getProperty("agx.benchmark.samples", "8").toInt()
        require(samples in 3..64) { "Benchmark samples must be between 3 and 64" }
        val parameters = Argon2Parameters() // production defaults: 3 iterations, 64 MiB, parallelism 1
        val hasher = Argon2PasswordHasher(parameters)
        val results = mutableListOf<Measurement>()
        val hash = hasher.hash("benchmark-synthetic-password".toCharArray())
        repeat(2) { assertTrue(hasher.verify(hash, "benchmark-synthetic-password".toCharArray())) }
        results += measure("argon2_hash", samples) { hasher.hash("benchmark-synthetic-password".toCharArray()) }
        results += measure("argon2_verify_valid", samples) {
            assertTrue(hasher.verify(hash, "benchmark-synthetic-password".toCharArray()))
        }
        results += measure("argon2_verify_invalid", samples) {
            assertEquals(false, hasher.verify(hash, "benchmark-wrong-password".toCharArray()))
        }

        // Documentation-only IP objects; getByAddress never performs DNS or network I/O.
        val addresses = Array(256) { InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, it.toByte())) }
        val names = Array(64) { "Synthetic$it" }
        val limit = FloodLimit(8, 1, Duration.ofHours(1))
        val flood = ConnectionFloodGate(limit, FloodLimit(128, 1, Duration.ofHours(1)), 128)
        val burst = UsernameBurstGate(UsernameBurstPolicy(6, 3, Duration.ofSeconds(60), Duration.ofMinutes(10), 128))
        val behavior = ConnectionBehaviorGate(ConnectionBehaviorPolicy(40, 1, 5, 8, 8,
            Duration.ofSeconds(60), Duration.ofMinutes(10), 128))
        val registration = RegistrationAttemptGate(limit, 128, Duration.ofHours(2))
        var expensiveCalls = 0
        results += measure("cheap_guard_pipeline_mixed_addresses", 100_000) { index ->
            val address = addresses[index % addresses.size]
            if (flood.evaluate(address) == ConnectionDecision.ALLOW &&
                burst.evaluate(address, names[index % names.size]) == UsernameBurstDecision.ALLOW &&
                behavior.evaluateConnection(address, names[index % names.size]) == ConnectionBehaviorDecision.ALLOW) {
                expensiveCalls++ // a counter stands in for storage/HTTP/Argon2; none is invoked here
            }
        }
        results += measure("username_burst_and_reconnect", 100_000) { index ->
            val address = addresses[index % addresses.size]
            burst.evaluate(address, names[index % names.size])
            burst.recordPreAuthDisconnect(address)
        }
        results += measure("behavior_failed_auth_and_disconnect", 100_000) { index ->
            val address = addresses[index % addresses.size]
            behavior.evaluateConnection(address, names[index % names.size])
            behavior.record(address, ConnectionBehaviorSignal.AUTHENTICATION_FAILURE)
            behavior.record(address, ConnectionBehaviorSignal.PRE_AUTH_DISCONNECT)
        }
        results += measure("registration_gate", 100_000) { index -> registration.evaluate(addresses[index % addresses.size]) }
        assertTrue(flood.trackedAddressCount() <= 128)
        assertTrue(burst.trackedAddressCount() <= 128)
        assertTrue(behavior.trackedAddressCount() <= 128)
        assertTrue(expensiveCalls <= 128)

        // Real Argon2 jobs exercise the queue, with at most two workers and four queued jobs.
        val executor = BoundedTaskExecutor(2, 4, "agx-local-benchmark")
        var accepted = 0
        var rejected = 0
        val queueStart = System.nanoTime()
        val latencies = mutableListOf<Long>()
        try {
            val jobs = (0 until 32).map {
                val submitted = System.nanoTime()
                executor.submit {
                    assertTrue(hasher.verify(hash, "benchmark-synthetic-password".toCharArray()))
                    System.nanoTime() - submitted
                }
            }
            jobs.forEach { job ->
                try { latencies += job.get(10, TimeUnit.SECONDS); accepted++ }
                catch (failure: java.util.concurrent.ExecutionException) {
                    assertTrue(failure.cause is java.util.concurrent.RejectedExecutionException)
                    rejected++
                }
            }
        } finally { executor.close() }
        assertEquals(32, accepted + rejected)
        assertTrue(rejected > 0)
        results += Measurement("argon2_bounded_queue_end_to_end", latencies.toLongArray(), System.nanoTime() - queueStart)
        val report = Path.of(System.getProperty("agx.benchmark.report"))
        Files.createDirectories(report.parent)
        // No hashes, passwords, host names or IPs are included in the report.
        Files.writeString(report, """{
          "generated_at":"${Instant.now()}",
          "java":"${System.getProperty("java.version")}",
          "os":"${System.getProperty("os.name")}",
          "arch":"${System.getProperty("os.arch")}",
          "processors":${Runtime.getRuntime().availableProcessors()},
          "max_heap_bytes":${Runtime.getRuntime().maxMemory()},
          "argon2":{"iterations":3,"memory_kib":65536,"parallelism":1},
          "queue":{"workers":2,"capacity":4,"submitted":32,"accepted":$accepted,"rejected":$rejected,"maximum_worker_argon2_memory_kib":131072},
          "pipeline_expensive_calls":$expensiveCalls,
          "measurements":[${results.joinToString(",") { it.json() }}]
        }
        """.trimIndent())
    }

    private fun measure(name: String, count: Int, operation: (Int) -> Unit): Measurement {
        val durations = LongArray(count)
        val begin = System.nanoTime()
        repeat(count) { index ->
            val start = System.nanoTime()
            operation(index)
            durations[index] = System.nanoTime() - start
        }
        return Measurement(name, durations, System.nanoTime() - begin)
    }
}
