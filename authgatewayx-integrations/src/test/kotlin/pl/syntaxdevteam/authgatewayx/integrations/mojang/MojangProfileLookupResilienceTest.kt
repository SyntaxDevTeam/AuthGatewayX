package pl.syntaxdevteam.authgatewayx.integrations.mojang

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MojangProfileLookupResilienceTest {
    private val username = AccountUsername.parse("ProbeUser")
    private val other = AccountUsername.parse("OtherUser")
    private val premiumBody = """{"id":"0123456789abcdef0123456789abcdef","name":"ProbeUser"}"""

    @Test
    fun `429 respects Retry-After across usernames and recovers after cooldown`() = fixture { f ->
        f.client.status = 429
        f.client.retryAfter = "60"
        assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
        assertEquals(MojangLookupFailure.HTTP_ERROR, f.diagnostic().reason)
        assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(other))
        assertEquals(1, f.client.calls.get())
        assertEquals(MojangLookupFailure.BACKOFF, f.diagnostic().reason)
        f.clock.advance(59)
        f.lookup(other)
        assertEquals(1, f.client.calls.get())
        f.clock.advance(1)
        f.client.status = 404
        assertEquals(MojangProfileLookupResult.NotPremium, f.lookup(other))
        assertEquals(2, f.client.calls.get())
    }

    @Test
    fun `Retry-After accepts HTTP dates and bounds excessive values`() {
        for (header in listOf("date", Long.MAX_VALUE.toString())) fixture { f ->
            f.client.status = 503
            f.client.retryAfter = if (header == "date") DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(f.clock.instant().plusSeconds(90), ZoneOffset.UTC),
            ) else header
            f.lookup(username)
            assertEquals(if (header == "date") 90L else 86400L, f.diagnostic().retryAfterSeconds)
        }
    }

    @Test
    fun `invalid or absent Retry-After uses 60 seconds for 429`() {
        for (header in listOf(null, "garbage", "-5", "999999999999999999999999999")) fixture { f ->
            f.client.status = 429
            f.client.retryAfter = header
            f.lookup(username)
            assertEquals(60, f.diagnostic().retryAfterSeconds)
        }
    }

    @Test
    fun `service transport and malformed responses remain unavailable with distinct diagnostics`() {
        val cases = listOf(
            Triple(500, null, MojangLookupFailure.HTTP_ERROR),
            Triple(200, null, MojangLookupFailure.INVALID_RESPONSE),
            Triple(200, UnknownHostException("must not be logged"), MojangLookupFailure.DNS_ERROR),
            Triple(200, SSLException("must not be logged"), MojangLookupFailure.TLS_ERROR),
            Triple(200, HttpTimeoutException("must not be logged"), MojangLookupFailure.HTTP_TIMEOUT),
            Triple(200, java.io.IOException("must not be logged"), MojangLookupFailure.TRANSPORT_ERROR),
        )
        for ((status, exception, reason) in cases) fixture { f ->
            f.client.status = status
            f.client.failure = exception
            assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
            val diagnostic = f.diagnostic()
            assertEquals(reason, diagnostic.reason)
            assertEquals(2, diagnostic.retryAfterSeconds)
            assertFalse(diagnostic.logLine().contains("must not be logged"))
            assertFalse(diagnostic.logLine().contains(username.value))
            f.lookup(other)
            assertEquals(1, f.client.calls.get())
        }
    }

    @Test
    fun `valid cached identities remain usable in cooldown but expired entries never bypass it`() = fixture { f ->
        f.client.status = 200
        f.client.body = premiumBody
        assertIs<MojangProfileLookupResult.Premium>(f.lookup(username))
        f.client.status = 429
        f.client.retryAfter = "86400"
        assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(other))
        assertIs<MojangProfileLookupResult.Premium>(f.lookup(username))
        assertEquals(2, f.client.calls.get())
        f.clock.advance(21601)
        assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
        assertEquals(2, f.client.calls.get())
    }

    @Test
    fun `confirmed absent profiles are cached and cache has bounded eviction and invalidation`() = fixture(maximumSize = 1) { f ->
        f.client.status = 404
        assertEquals(MojangProfileLookupResult.NotPremium, f.lookup(username))
        f.lookup(username)
        assertEquals(1, f.client.calls.get())
        f.lookup(other)
        f.lookup(username)
        assertEquals(3, f.client.calls.get())
        f.lookup.invalidate(username)
        f.lookup(username)
        assertEquals(4, f.client.calls.get())
        f.clock.advance(301)
        f.client.status = 204
        assertEquals(MojangProfileLookupResult.NotPremium, f.lookup(username))
        assertEquals(5, f.client.calls.get())
    }

    @Test
    fun `deadline includes queue and expired work never performs HTTP`() = fixture(timeout = Duration.ofMillis(100)) { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val running = f.executor.submit { started.countDown(); release.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
            assertEquals(MojangLookupFailure.QUEUE_TIMEOUT, f.diagnostic().reason)
            release.countDown()
            running.get(2, TimeUnit.SECONDS)
            // Executor barrier proves all preceding work has been handled.
            f.executor.submit { Unit }.get(2, TimeUnit.SECONDS)
            assertEquals(0, f.client.calls.get())
        } finally { release.countDown() }
    }

    @Test
    fun `HTTP receives only the time budget remaining after queueing`() {
        val nanos = java.util.concurrent.atomic.AtomicLong()
        fixture(nanoTime = nanos::get) { f ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val running = f.executor.submit { started.countDown(); release.await() }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val result = f.lookup.lookupProfile(username)
                nanos.set(TimeUnit.SECONDS.toNanos(1))
                f.client.status = 404
                release.countDown()
                running.get(2, TimeUnit.SECONDS)
                assertEquals(MojangProfileLookupResult.NotPremium, result.toCompletableFuture().get(2, TimeUnit.SECONDS))
                assertEquals(Duration.ofSeconds(1), f.client.lastTimeout)
            } finally { release.countDown() }
        }
    }

    @Test
    fun `work already queued rechecks cooldown before making another HTTP request`() = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.client.status = 429
        f.client.beforeSend = { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        try {
            val first = f.lookup.lookupProfile(username)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = f.lookup.lookupProfile(other)
            release.countDown()
            assertEquals(MojangProfileLookupResult.Unavailable, first.toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(MojangProfileLookupResult.Unavailable, queued.toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(1, f.client.calls.get())
            assertEquals(MojangLookupFailure.HTTP_ERROR, f.diagnostic().reason)
            assertEquals(MojangLookupFailure.BACKOFF, f.diagnostic().reason)
        } finally { release.countDown() }
    }

    @Test
    fun `queue overload is diagnosed without confusing it with HTTP failures`() = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            f.executor.submit { started.countDown(); release.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            f.executor.submit { Unit }
            assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
            assertEquals(MojangLookupFailure.EXECUTOR_OVERLOADED, f.diagnostic().reason)
            assertEquals(0, f.client.calls.get())
        } finally { release.countDown() }
    }

    @Test
    fun `deduplicated callers cannot cancel or complete each others lookup`() = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.client.beforeSend = { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        f.client.status = 200
        f.client.body = premiumBody
        try {
            val first = f.lookup.lookupProfile(username).toCompletableFuture()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val second = f.lookup.lookupProfile(username).toCompletableFuture()
            val third = f.lookup.lookupProfile(username).toCompletableFuture()
            first.cancel(false)
            second.complete(MojangProfileLookupResult.NotPremium)
            release.countDown()
            assertIs<MojangProfileLookupResult.Premium>(third.get(2, TimeUnit.SECONDS))
            assertIs<MojangProfileLookupResult.Premium>(f.lookup(username))
            assertEquals(1, f.client.calls.get())
        } finally { release.countDown() }
    }

    @Test
    fun `late HTTP success is discarded after deadline`() = fixture(timeout = Duration.ofMillis(100)) { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.client.status = 200
        f.client.body = premiumBody
        f.client.beforeSend = { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        try {
            val result = f.lookup.lookupProfile(username)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(MojangProfileLookupResult.Unavailable, result.toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertEquals(MojangLookupFailure.HTTP_TIMEOUT, f.diagnostic().reason)
            release.countDown()
            f.executor.submit { Unit }.get(2, TimeUnit.SECONDS)
            f.client.beforeSend = {}
            f.client.status = 404
            f.clock.advance(3)
            assertEquals(MojangProfileLookupResult.NotPremium, f.lookup(username))
            assertEquals(2, f.client.calls.get())
        } finally { release.countDown() }
    }

    @Test
    fun `close unblocks consumers rejects new lookups and prevents cache resurrection`() = fixture { f ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.client.beforeSend = { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
        try {
            val result = f.lookup.lookupProfile(username)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            f.lookup.close()
            assertEquals(MojangProfileLookupResult.Unavailable, result.toCompletableFuture().get(2, TimeUnit.SECONDS))
            assertTrue(f.client.stopped)
            assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(other))
            assertEquals(1, f.client.calls.get())
        } finally { release.countDown() }
    }

    @Test
    fun `diagnostics are rate limited and a failing sink cannot strand authentication`() {
        val nanos = java.util.concurrent.atomic.AtomicLong()
        fixture(nanoTime = nanos::get) { f ->
            f.client.status = 500
            f.lookup(username)
            assertEquals(0, f.diagnostic().suppressed)
            repeat(3) { f.clock.advance(3); f.lookup(username) }
            // Worker barrier ensures diagnostic updates have completed.
            f.executor.submit { Unit }.get(2, TimeUnit.SECONDS)
            assertTrue(f.diagnostics.isEmpty())
            nanos.set(TimeUnit.SECONDS.toNanos(31))
            f.clock.advance(3)
            f.lookup(username)
            assertEquals(3, f.diagnostic().suppressed)
        }
        fixture(sinkFailure = true) { f ->
            assertEquals(MojangProfileLookupResult.Unavailable, f.lookup(username))
        }
    }

    private fun fixture(
        timeout: Duration = Duration.ofSeconds(2), maximumSize: Int = 100,
        nanoTime: () -> Long = System::nanoTime, sinkFailure: Boolean = false,
        test: (Fixture) -> Unit,
    ) {
        val f = Fixture(timeout, maximumSize, nanoTime, sinkFailure)
        try { test(f) } finally { f.lookup.close(); f.executor.close() }
    }

    private class MutableClock : Clock() {
        @Volatile private var now = Instant.parse("2026-10-24T00:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    private class Fixture(timeout: Duration, maximumSize: Int, nanoTime: () -> Long, sinkFailure: Boolean) {
        val executor = BoundedTaskExecutor(1, 1, "mojang-test")
        val clock = MutableClock()
        val client = FakeClient()
        val diagnostics = LinkedBlockingQueue<MojangLookupDiagnostic>()
        val lookup = MojangProfileLookup(executor, timeout, Duration.ofHours(6), Duration.ofMinutes(5),
            maximumSize, clock, client, { if (sinkFailure) error("logger failed") else diagnostics.add(it) }, nanoTime)
        fun lookup(name: AccountUsername): MojangProfileLookupResult = lookup.lookupProfile(name).toCompletableFuture().get(3, TimeUnit.SECONDS)
        fun diagnostic(): MojangLookupDiagnostic = diagnostics.poll(2, TimeUnit.SECONDS) ?: error("Missing diagnostic")
    }

    private class FakeClient : HttpClient() {
        val calls = AtomicInteger()
        @Volatile var status = 500
        @Volatile var body = "{}"
        @Volatile var retryAfter: String? = null
        @Volatile var failure: Exception? = null
        @Volatile var beforeSend: () -> Unit = {}
        @Volatile var stopped = false
        @Volatile var lastTimeout: Duration? = null
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
        override fun shutdownNow() { stopped = true }
        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> = error("unused")
        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = error("unused")
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            calls.incrementAndGet()
            lastTimeout = request.timeout().orElse(null)
            beforeSend()
            failure?.let { throw it }
            return object : HttpResponse<String> {
                override fun statusCode(): Int = status
                override fun request(): HttpRequest = request
                override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
                override fun headers(): HttpHeaders = HttpHeaders.of(retryAfter?.let { mapOf("Retry-After" to listOf(it)) } ?: emptyMap()) { _, _ -> true }
                override fun body(): String = body
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri(): URI = request.uri()
                override fun version(): Version = Version.HTTP_1_1
            } as HttpResponse<T>
        }
    }
}
