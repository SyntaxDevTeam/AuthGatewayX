package pl.syntaxdevteam.authgatewayx.integrations.mojang

import pl.syntaxdevteam.authgatewayx.domain.account.AccountUsername
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

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

class MojangProfileLookup(
    private val executor: BoundedTaskExecutor,
    private val requestTimeout: Duration,
    private val positiveTtl: Duration,
    private val negativeTtl: Duration,
    private val maximumSize: Int,
    private val clock: Clock = Clock.systemUTC(),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
) : PremiumUsernameLookup, MojangProfileIdentityLookup {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<MojangProfileLookupResult>>()

    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative)
        require(!positiveTtl.isZero && !positiveTtl.isNegative)
        require(!negativeTtl.isZero && !negativeTtl.isNegative)
        require(maximumSize > 0)
    }

    override fun lookup(username: AccountUsername): CompletionStage<PremiumUsernameStatus> =
        lookupProfile(username).thenApply(::statusOf)

    override fun lookupProfile(username: AccountUsername): CompletionStage<MojangProfileLookupResult> {
        val now = clock.instant()
        cache[username.canonical]?.takeIf { now.isBefore(it.expiresAt) }?.let {
            return CompletableFuture.completedFuture(it.result)
        }
        cache.remove(username.canonical)
        val pending = CompletableFuture<MojangProfileLookupResult>()
        inFlight.putIfAbsent(username.canonical, pending)?.let { return it }
        executor.submit { request(username) }.whenComplete { result, failure ->
            inFlight.remove(username.canonical, pending)
            if (failure != null) {
                pending.complete(MojangProfileLookupResult.Unavailable)
            } else {
                if (result is MojangProfileLookupResult.Premium || result == MojangProfileLookupResult.NotPremium) {
                    store(username.canonical, result)
                }
                pending.complete(result)
            }
        }
        return pending
    }

    private fun request(username: AccountUsername): MojangProfileLookupResult = try {
        val request = HttpRequest.newBuilder(
            URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/${username.value}"),
        ).timeout(requestTimeout).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        when (response.statusCode()) {
            200 -> parsePremiumUuid(response.body())
                ?.let(MojangProfileLookupResult::Premium)
                ?: MojangProfileLookupResult.Unavailable
            204, 404 -> MojangProfileLookupResult.NotPremium
            else -> MojangProfileLookupResult.Unavailable
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        MojangProfileLookupResult.Unavailable
    } catch (_: Exception) {
        MojangProfileLookupResult.Unavailable
    }

    @Synchronized
    private fun store(key: String, result: MojangProfileLookupResult) {
        if (cache.size >= maximumSize && !cache.containsKey(key)) {
            cache.entries.minByOrNull { it.value.expiresAt }?.let { cache.remove(it.key, it.value) }
        }
        val ttl = if (result is MojangProfileLookupResult.Premium) positiveTtl else negativeTtl
        cache[key] = CacheEntry(result, clock.instant().plus(ttl))
    }

    fun invalidate(username: AccountUsername) { cache.remove(username.canonical) }

    private data class CacheEntry(val result: MojangProfileLookupResult, val expiresAt: Instant)

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
