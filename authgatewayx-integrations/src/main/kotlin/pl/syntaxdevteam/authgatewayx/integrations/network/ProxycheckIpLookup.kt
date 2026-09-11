package pl.syntaxdevteam.authgatewayx.integrations.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

internal fun interface IpRequest { fun request(address: InetAddress): IpIntelligence? }

class ProxycheckIpLookup internal constructor(
    private val executor: BoundedTaskExecutor,
    private val request: IpRequest,
    private val maximumSize: Int,
    private val maximumConcurrent: Int,
    private val requestsPerMinute: Int,
    private val ttl: Duration,
    private val failureTtl: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val closeTransport: () -> Unit = {},
) : IpIntelligenceLookup, AutoCloseable {
    private data class Entry(val result: IpIntelligence?, val expires: Instant)
    private val cache = LinkedHashMap<String, Entry>()
    private val pending = mutableMapOf<String, CompletableFuture<IpIntelligence?>>()
    private var minuteStart = clock.instant()
    private var requests = 0
    private var backoffUntil = Instant.MIN
    private var closed = false

    init {
        require(maximumSize in 1..100_000 && maximumConcurrent in 1..32 && requestsPerMinute in 1..1000)
        require(ttl > Duration.ZERO && failureTtl > Duration.ZERO)
    }

    @Synchronized
    override fun lookup(address: InetAddress): CompletionStage<IpIntelligence?> {
        if (closed || !isPublicAddress(address)) return CompletableFuture.completedFuture(null)
        val key = address.hostAddress
        val now = clock.instant()
        cache[key]?.takeIf { now < it.expires }?.let { return CompletableFuture.completedFuture(it.result) }
        cache.remove(key)
        pending[key]?.let { return it.copy() }
        if (now >= minuteStart.plusSeconds(60)) { minuteStart = now; requests = 0 }
        if (pending.size >= maximumConcurrent || requests >= requestsPerMinute || now < backoffUntil) {
            return CompletableFuture.completedFuture(null)
        }
        val result = CompletableFuture<IpIntelligence?>()
        pending[key] = result
        requests++
        executor.submit { request.request(address) }.whenComplete { value, failure ->
            synchronized(this) {
                pending.remove(key)
                val resolved = if (failure == null) value else null
                if (!closed) {
                    if (cache.size >= maximumSize) cache.remove(cache.keys.first())
                    cache[key] = Entry(resolved, clock.instant().plus(if (resolved == null) failureTtl else ttl))
                    if (resolved == null) backoffUntil = clock.instant().plus(failureTtl)
                }
                result.complete(if (closed) null else resolved)
            }
        }
        return result.copy()
    }

    @Synchronized
    fun invalidate(address: InetAddress) { cache.remove(address.hostAddress) }

    override fun close() {
        synchronized(this) {
            closed = true
            cache.clear()
            pending.values.forEach { it.complete(null) }
            pending.clear()
        }
        closeTransport()
        executor.close()
    }

    companion object {
        fun create(apiKey: String, timeout: Duration, ttl: Duration, failureTtl: Duration,
                   maximumSize: Int, maximumConcurrent: Int, requestsPerMinute: Int): ProxycheckIpLookup {
            require(timeout >= Duration.ofMillis(100) && timeout <= Duration.ofSeconds(10))
            require(maximumSize in 1..100_000 && maximumConcurrent in 1..32 && requestsPerMinute in 1..1000)
            require(ttl > Duration.ZERO && failureTtl > Duration.ZERO)
            require(apiKey.length <= 256 && apiKey.none(Char::isISOControl))
            val executor = BoundedTaskExecutor(maximumConcurrent, maximumConcurrent, "authgatewayx-ip-intelligence")
            val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
            val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            return ProxycheckIpLookup(executor, IpRequest { address ->
                val ip = URLEncoder.encode(address.hostAddress, StandardCharsets.UTF_8)
                val uri = URI.create("https://proxycheck.io/v3/$ip?ver=24-June-2026&tag=0&p=0&key=$encodedKey")
                requestHttp(client, address.hostAddress, uri, timeout)
            }, maximumSize, maximumConcurrent, requestsPerMinute, ttl, failureTtl, closeTransport = { client.shutdownNow() })
        }

        internal fun requestHttp(client: HttpClient, address: String, uri: URI, timeout: Duration): IpIntelligence? {
            val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build()
            return try {
                val response = client.send(request) { LimitedBodySubscriber(65_536) }
                if (response.statusCode() == 200) parse(response.body().toString(StandardCharsets.UTF_8), address) else null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) { null }
        }

        internal fun parse(body: String, address: String): IpIntelligence? = runCatching {
            val reader = JsonReader(StringReader(body)).apply { strictness = Strictness.STRICT }
            val root = JsonParser.parseReader(reader).asJsonObject
            if (reader.peek() != JsonToken.END_DOCUMENT) return null
            val status = root["status"]?.asString
            if (status != "ok" && status != "warning") return null
            val entry = root.getAsJsonObject(address) ?: return null
            val detections = entry.getAsJsonObject("detections") ?: return null
            fun flag(key: String): Boolean? = detections[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            fun JsonObject.code(key: String, pattern: Regex): String? = this[key]
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(pattern::matches)
            val country = entry["location"]?.takeIf { it.isJsonObject }?.asJsonObject?.code("country_code", Regex("[A-Z]{2}"))
            val asn = entry["network"]?.takeIf { it.isJsonObject }?.asJsonObject?.code("asn", Regex("AS[0-9]{1,10}"))
            IpIntelligence(flag("vpn"), flag("proxy"), flag("tor"), country, asn)
        }.getOrNull()

        internal fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 255 }
            return if (b.size == 4) {
                b[0] != 0 && b[0] < 224 && !(b[0] == 100 && b[1] in 64..127) &&
                    !(b[0] == 192 && b[1] == 0 && b[2] in listOf(0, 2)) &&
                    !(b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) &&
                    !(b[0] == 203 && b[1] == 0 && b[2] == 113)
            } else {
                // Restrict to global unicast and exclude the documentation prefix.
                b[0] and 0xe0 == 0x20 && !(b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && b[3] == 0xb8)
            }
        }
    }
}

/** Cancels before buffering an oversized body; request timeout also covers body completion. */
internal class LimitedBodySubscriber(private val maximumBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(subscription: Flow.Subscription) { this.subscription = subscription; subscription.request(Long.MAX_VALUE) }
    override fun onNext(items: List<ByteBuffer>) {
        if (result.isDone) return
        for (item in items) {
            if (item.remaining() > maximumBytes - bytes.size()) {
                subscription?.cancel()
                result.completeExceptionally(IllegalStateException("IP response exceeds size limit"))
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
    }
    override fun onError(throwable: Throwable) { result.completeExceptionally(throwable) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}
