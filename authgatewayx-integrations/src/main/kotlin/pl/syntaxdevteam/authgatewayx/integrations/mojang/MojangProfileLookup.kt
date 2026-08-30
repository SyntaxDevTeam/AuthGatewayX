package pl.syntaxdevteam.authgatewayx.integrations.mojang

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

enum class PremiumUsernameStatus { PREMIUM, NOT_PREMIUM, UNAVAILABLE }

fun interface PremiumUsernameLookup {
    fun lookup(username: AccountUsername): CompletionStage<PremiumUsernameStatus>
}

class MojangProfileLookup(
    private val executor: BoundedTaskExecutor,
    private val requestTimeout: Duration,
    private val positiveTtl: Duration,
    private val negativeTtl: Duration,
    private val maximumSize: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
) : PremiumUsernameLookup {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<PremiumUsernameStatus>>()

    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative)
        require(!positiveTtl.isZero && !positiveTtl.isNegative)
        require(!negativeTtl.isZero && !negativeTtl.isNegative)
        require(maximumSize > 0)
    }

    override fun lookup(username: AccountUsername): CompletionStage<PremiumUsernameStatus> {
        val now = clock.instant()
        cache[username.canonical]?.takeIf { now.isBefore(it.expiresAt) }?.let {
            return CompletableFuture.completedFuture(it.status)
        }
        cache.remove(username.canonical)
        val pending = CompletableFuture<PremiumUsernameStatus>()
        inFlight.putIfAbsent(username.canonical, pending)?.let { return it }
        executor.submit { request(username) }.whenComplete { status, failure ->
            inFlight.remove(username.canonical, pending)
            if (failure != null) {
                pending.complete(PremiumUsernameStatus.UNAVAILABLE)
            } else {
                if (status == PremiumUsernameStatus.PREMIUM || status == PremiumUsernameStatus.NOT_PREMIUM) {
                    store(username.canonical, status)
                }
                pending.complete(status)
            }
        }
        return pending
    }

    private fun request(username: AccountUsername): PremiumUsernameStatus = try {
        val request = HttpRequest.newBuilder(
            URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/${username.value}"),
        ).timeout(requestTimeout).header("Accept", "application/json").GET().build()
        when (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
            200 -> PremiumUsernameStatus.PREMIUM
            204, 404 -> PremiumUsernameStatus.NOT_PREMIUM
            else -> PremiumUsernameStatus.UNAVAILABLE
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        PremiumUsernameStatus.UNAVAILABLE
    } catch (_: Exception) {
        PremiumUsernameStatus.UNAVAILABLE
    }

    @Synchronized
    private fun store(key: String, status: PremiumUsernameStatus) {
        if (cache.size >= maximumSize && !cache.containsKey(key)) {
            cache.entries.minByOrNull { it.value.expiresAt }?.let { cache.remove(it.key, it.value) }
        }
        val ttl = if (status == PremiumUsernameStatus.PREMIUM) positiveTtl else negativeTtl
        cache[key] = CacheEntry(status, clock.instant().plus(ttl))
    }

    fun invalidate(username: AccountUsername) { cache.remove(username.canonical) }
    private data class CacheEntry(val status: PremiumUsernameStatus, val expiresAt: Instant)
}
