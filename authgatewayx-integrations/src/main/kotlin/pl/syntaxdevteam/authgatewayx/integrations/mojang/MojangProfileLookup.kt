package pl.syntaxdevteam.authgatewayx.integrations.mojang

import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor

enum class PremiumUsernameStatus { PREMIUM, NOT_PREMIUM, UNAVAILABLE }

sealed interface MojangProfileLookupResult {
    data class Premium(val minecraftUuid: UUID) : MojangProfileLookupResult
    data object NotPremium : MojangProfileLookupResult
    data object Unavailable : MojangProfileLookupResult
}

fun interface PremiumUsernameLookup {
    fun lookup(username: AccountUsername): CompletionStage<PremiumUsernameStatus>
}

fun interface MojangProfileIdentityLookup {
    fun lookupProfile(username: AccountUsername): CompletionStage<MojangProfileLookupResult>
}

enum class MojangLookupFailure {
    HTTP_ERROR, DNS_ERROR, TLS_ERROR, HTTP_TIMEOUT, QUEUE_TIMEOUT, TRANSPORT_ERROR,
    INVALID_RESPONSE, EXECUTOR_OVERLOADED, BACKOFF, INTERRUPTED,
}

/** Contains no player identifiers, response bodies, or exception messages. */
data class MojangLookupDiagnostic(
    val reason: MojangLookupFailure,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long = 0,
    val suppressed: Long = 0,
) {
    fun logLine(): String = "[mojang-lookup] reason=$reason" +
        (httpStatus?.let { " http_status=$it" } ?: "") +
        " retry_after_seconds=$retryAfterSeconds suppressed=$suppressed"
}

class MojangProfileLookup(
    private val executor: BoundedTaskExecutor,
    private val requestTimeout: Duration,
    private val positiveTtl: Duration,
    private val negativeTtl: Duration,
    private val maximumSize: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
    /** Must enqueue logging, never perform blocking I/O on the caller/timer thread. */
    private val onDiagnostic: (MojangLookupDiagnostic) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : PremiumUsernameLookup, MojangProfileIdentityLookup, AutoCloseable {
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private val inFlight = mutableMapOf<String, Pending>()
    private val diagnosticWindows = mutableMapOf<MojangLookupFailure, DiagnosticWindow>()
    private var backoffUntil = Instant.MIN
    private var closed = false

    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative)
        require(!positiveTtl.isZero && !positiveTtl.isNegative)
        require(!negativeTtl.isZero && !negativeTtl.isNegative)
        require(maximumSize > 0)
    }

    override fun lookup(username: AccountUsername): CompletionStage<PremiumUsernameStatus> =
        lookupProfile(username).thenApply(::statusOf)

    @Synchronized
    override fun lookupProfile(username: AccountUsername): CompletionStage<MojangProfileLookupResult> {
        if (closed) return CompletableFuture.completedFuture(MojangProfileLookupResult.Unavailable)
        val now = clock.instant()
        cache[username.canonical]?.takeIf { now.isBefore(it.expiresAt) }?.let {
            return CompletableFuture.completedFuture(it.result)
        }
        cache.remove(username.canonical)
        inFlight[username.canonical]?.let { return it.result.copy() }
        if (now.isBefore(backoffUntil)) {
            diagnose(MojangLookupDiagnostic(MojangLookupFailure.BACKOFF,
                retryAfterSeconds = remainingBackoffSeconds(now)))
            return CompletableFuture.completedFuture(MojangProfileLookupResult.Unavailable)
        }
        val pending = Pending(nanoTime())
        inFlight[username.canonical] = pending
        val work = executor.submit {
            val remaining = remainingNanos(pending)
            if (remaining <= 0) throw TimeoutException()
            // Recheck after queueing: another request may have received a 429 meanwhile.
            val coolingDown = synchronized(this) { closed || clock.instant().isBefore(backoffUntil) }
            if (coolingDown) Outcome(failure = MojangLookupFailure.BACKOFF)
            else {
                pending.started.set(true)
                request(username, Duration.ofNanos(remaining))
            }
        }
        pending.work = work
        work.orTimeout(remainingNanos(pending).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            .whenComplete { outcome, failure -> finish(username.canonical, pending, outcome, failure) }
        return pending.result.copy()
    }

    private fun remainingNanos(pending: Pending): Long = requestTimeout.toNanos() - (nanoTime() - pending.created)

    private fun finish(key: String, pending: Pending, outcome: Outcome?, failure: Throwable?) {
        var diagnostic: MojangLookupDiagnostic? = null
        synchronized(this) {
            if (inFlight[key] !== pending) return
            val reason = when {
                failure is RejectedExecutionException -> MojangLookupFailure.EXECUTOR_OVERLOADED
                failure is TimeoutException || remainingNanos(pending) <= 0 ->
                    if (pending.started.get()) MojangLookupFailure.HTTP_TIMEOUT else MojangLookupFailure.QUEUE_TIMEOUT
                failure != null -> MojangLookupFailure.TRANSPORT_ERROR
                else -> outcome?.failure
            }
            val result = if (closed || reason != null) MojangProfileLookupResult.Unavailable
                else outcome?.result ?: MojangProfileLookupResult.Unavailable
            if (!closed) {
                if (reason == MojangLookupFailure.HTTP_TIMEOUT) unavailable(reason)
                if (result is MojangProfileLookupResult.Premium || result == MojangProfileLookupResult.NotPremium) {
                    store(key, result)
                }
                if (reason != null) diagnostic = MojangLookupDiagnostic(reason, outcome?.httpStatus,
                    remainingBackoffSeconds(clock.instant()))
            }
            inFlight.remove(key)
            // Serialize publication with close, so shutdown cannot be followed by a late success.
            pending.result.complete(result)
        }
        // Consumers complete before observability; logger failures cannot strand a login.
        diagnostic?.let(::diagnose)
    }

    private fun request(username: AccountUsername, timeout: Duration): Outcome = try {
        val request = HttpRequest.newBuilder(
            URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/${username.value}"),
        ).timeout(timeout).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        when (val status = response.statusCode()) {
            200 -> parsePremiumUuid(response.body())
                ?.let { Outcome(result = MojangProfileLookupResult.Premium(it)) }
                ?: unavailable(MojangLookupFailure.INVALID_RESPONSE, status)
            204, 404 -> Outcome(result = MojangProfileLookupResult.NotPremium)
            else -> unavailable(MojangLookupFailure.HTTP_ERROR, status,
                if (status == 429 || status == 503) retryAfter(response) else null)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        unavailable(MojangLookupFailure.INTERRUPTED)
    } catch (_: HttpTimeoutException) {
        unavailable(MojangLookupFailure.HTTP_TIMEOUT)
    } catch (failure: Exception) {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
        unavailable(when {
            causes.any { it is UnknownHostException || it is java.nio.channels.UnresolvedAddressException } -> MojangLookupFailure.DNS_ERROR
            causes.any { it is SSLException } -> MojangLookupFailure.TLS_ERROR
            else -> MojangLookupFailure.TRANSPORT_ERROR
        })
    }

    private fun unavailable(reason: MojangLookupFailure, status: Int? = null, retryAfter: Duration? = null): Outcome {
        synchronized(this) {
            if (!closed) {
                val delay = retryAfter ?: if (status == 429) Duration.ofSeconds(60) else Duration.ofSeconds(2)
                val until = clock.instant().plus(delay)
                if (until > backoffUntil) backoffUntil = until
            }
        }
        return Outcome(failure = reason, httpStatus = status)
    }

    private fun retryAfter(response: HttpResponse<*>): Duration? {
        val value = response.headers().firstValue("Retry-After").orElse(null)?.trim() ?: return null
        val seconds = value.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
        val delay = seconds ?: runCatching {
            Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
        }.getOrNull() ?: return null
        return delay.coerceIn(Duration.ofSeconds(1), Duration.ofHours(24))
    }

    private fun remainingBackoffSeconds(now: Instant): Long =
        if (now >= backoffUntil) 0 else (Duration.between(now, backoffUntil).toMillis() + 999) / 1000

    private fun diagnose(diagnostic: MojangLookupDiagnostic) {
        val message = synchronized(this) {
            if (closed) return
            val now = nanoTime()
            val window = diagnosticWindows[diagnostic.reason]
            if (window != null && now - window.started < TimeUnit.SECONDS.toNanos(30)) {
                window.suppressed++
                return
            }
            diagnosticWindows[diagnostic.reason] = DiagnosticWindow(now)
            diagnostic.copy(suppressed = window?.suppressed ?: 0)
        }
        try { onDiagnostic(message) } catch (_: Exception) { /* Observability must not affect auth. */ }
    }

    private fun store(key: String, result: MojangProfileLookupResult) {
        if (cache.size >= maximumSize && !cache.containsKey(key)) cache.remove(cache.keys.first())
        val ttl = if (result is MojangProfileLookupResult.Premium) positiveTtl else negativeTtl
        cache[key] = CacheEntry(result, clock.instant().plus(ttl))
    }

    @Synchronized
    fun invalidate(username: AccountUsername) { cache.remove(username.canonical) }

    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            cache.clear()
            diagnosticWindows.clear()
            inFlight.values.toList().also { inFlight.clear() }
        }
        pending.forEach {
            it.work?.cancel(false)
            it.result.complete(MojangProfileLookupResult.Unavailable)
        }
        client.shutdownNow()
    }

    private data class CacheEntry(val result: MojangProfileLookupResult, val expiresAt: Instant)
    private class Pending(val created: Long) {
        val result = CompletableFuture<MojangProfileLookupResult>()
        val started = AtomicBoolean()
        var work: CompletableFuture<Outcome>? = null
    }
    private data class Outcome(
        val result: MojangProfileLookupResult = MojangProfileLookupResult.Unavailable,
        val failure: MojangLookupFailure? = null,
        val httpStatus: Int? = null,
    )
    private class DiagnosticWindow(val started: Long, var suppressed: Long = 0)

    companion object {
        private val PROFILE_ID = Regex("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"")

        internal fun parsePremiumUuid(body: String): UUID? {
            val raw = PROFILE_ID.find(body)?.groupValues?.getOrNull(1) ?: return null
            val dashed = buildString(36) {
                append(raw, 0, 8); append('-')
                append(raw, 8, 12); append('-')
                append(raw, 12, 16); append('-')
                append(raw, 16, 20); append('-')
                append(raw, 20, 32)
            }
            return runCatching { UUID.fromString(dashed) }.getOrNull()
        }

        private fun statusOf(result: MojangProfileLookupResult): PremiumUsernameStatus = when (result) {
            is MojangProfileLookupResult.Premium -> PremiumUsernameStatus.PREMIUM
            MojangProfileLookupResult.NotPremium -> PremiumUsernameStatus.NOT_PREMIUM
            MojangProfileLookupResult.Unavailable -> PremiumUsernameStatus.UNAVAILABLE
        }
    }
}
