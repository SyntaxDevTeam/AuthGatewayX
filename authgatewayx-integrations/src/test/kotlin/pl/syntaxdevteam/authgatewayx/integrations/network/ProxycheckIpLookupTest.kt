package pl.syntaxdevteam.authgatewayx.integrations.network

import pl.syntaxdevteam.authgatewayx.security.executor.BoundedTaskExecutor
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProxycheckIpLookupTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(8,8,8,8))
    private val other = InetAddress.getByAddress(byteArrayOf(1,1,1,1))
    private val result = IpIntelligence(true, false, false, "PL", "AS123")
    private class TestClock : Clock() {
        var now = Instant.parse("2026-09-11T12:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private fun lookup(clock: Clock = TestClock(), size: Int = 2, quota: Int = 10, request: IpRequest) =
        ProxycheckIpLookup(BoundedTaskExecutor(1, 1, "ip-test"), request, size, 1, quota,
            Duration.ofSeconds(60), Duration.ofSeconds(10), clock)

    @Test
    fun `parser keeps exact flags and limits untrusted geography to codes`() {
        val parsed = ProxycheckIpLookup.parse("""{"status":"warning","8.8.8.8":{
            "detections":{"vpn":true,"proxy":false,"tor":null,"hosting":true},
            "location":{"country_code":"PL"},"network":{"asn":"AS123"}}}""", "8.8.8.8")!!
        assertTrue(parsed.vpn == true)
        assertFalse(parsed.proxy!!)
        assertNull(parsed.tor)
        assertEquals("PL", parsed.countryCode)
        assertEquals("AS123", parsed.asn)
        assertNull(ProxycheckIpLookup.parse("""{"status":"denied"}""", "8.8.8.8"))
        assertNull(ProxycheckIpLookup.parse("invalid", "8.8.8.8"))
        val unknown = ProxycheckIpLookup.parse("""{"status":"ok","8.8.8.8":{
            "detections":{"vpn":"true","hosting":true},"location":{"country_code":"<click:run_command:'/op x'>"}}}""", "8.8.8.8")!!
        assertFalse(unknown.suspicious)
        assertNull(unknown.countryCode)
    }

    @Test
    fun `cache TTL invalidation and capacity limit cause bounded refresh`() {
        val clock = TestClock(); val calls = AtomicInteger()
        lookup(clock, size = 1, request = { calls.incrementAndGet(); result }).use { service ->
            repeat(2) { assertEquals(result, service.lookup(ip).toCompletableFuture().get()) }
            assertEquals(1, calls.get())
            clock.now = clock.now.plusSeconds(61)
            service.lookup(ip).toCompletableFuture().get()
            service.invalidate(ip)
            service.lookup(ip).toCompletableFuture().get()
            service.lookup(other).toCompletableFuture().get()
            service.lookup(ip).toCompletableFuture().get()
            assertEquals(5, calls.get())
        }
    }

    @Test
    fun `same address deduplicates and concurrency rejects other addresses`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        lookup(request = { calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); result }).use { service ->
            try {
                val first = service.lookup(ip).toCompletableFuture()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val second = service.lookup(ip).toCompletableFuture()
                assertNull(service.lookup(other).toCompletableFuture().get())
                release.countDown()
                assertEquals(result, first.get(2, TimeUnit.SECONDS))
                assertEquals(result, second.get(2, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test
    fun `errors back off globally and minute quota blocks request flood`() {
        val clock = TestClock(); val calls = AtomicInteger()
        lookup(clock, quota = 1, request = { calls.incrementAndGet(); error("transport failure") }).use { service ->
            assertNull(service.lookup(ip).toCompletableFuture().get())
            assertNull(service.lookup(other).toCompletableFuture().get())
            clock.now = clock.now.plusSeconds(11)
            assertNull(service.lookup(other).toCompletableFuture().get())
            assertEquals(1, calls.get())
            clock.now = clock.now.plusSeconds(60)
            assertNull(service.lookup(other).toCompletableFuture().get())
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `private addresses and closed service never call transport`() {
        val calls = AtomicInteger()
        val service = lookup(request = { calls.incrementAndGet(); result })
        listOf("127.0.0.1", "10.0.0.1", "192.168.0.1", "100.64.0.1", "::1", "fc00::1", "2001:db8::1").forEach {
            assertNull(service.lookup(InetAddress.getByName(it)).toCompletableFuture().get())
        }
        service.close()
        assertNull(service.lookup(ip).toCompletableFuture().get())
        assertEquals(0, calls.get())
    }

    @Test
    fun `oversized HTTP response cancels before buffering payload`() {
        var cancelled = false
        val subscriber = LimitedBodySubscriber(4)
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {}
            override fun cancel() { cancelled = true }
        })
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1,2,3)), ByteBuffer.wrap(byteArrayOf(4,5))))
        assertTrue(cancelled)
        assertTrue(subscriber.body.toCompletableFuture().isCompletedExceptionally)
    }
}
