package pl.syntaxdevteam.authgatewayx.integrations.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.*

class ProxycheckHttpTest {
    @Test
    fun `HTTP timeout quota errors and oversized bodies return unknown without redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool()
        server.executor = workers
        val body = """{"status":"ok","8.8.8.8":{"detections":{"vpn":true,"proxy":false,"tor":false},"location":{"country_code":"PL"}}}"""
        server.createContext("/ok") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/quota") { exchange -> exchange.sendResponseHeaders(429, -1); exchange.close() }
        server.createContext("/oversized") { exchange ->
            val bytes = ByteArray(70_000) { 32 }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { exchange.responseBody.use { it.write(bytes) } }
        }
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            // Deliberately stall after headers to verify the entire-body timeout.
            runCatching { Thread.sleep(1000); exchange.responseBody.use { it.write(body.toByteArray()) } }
            exchange.close()
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.start()
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().use { client ->
            try {
                fun request(path: String, timeout: Duration = Duration.ofSeconds(2)) = ProxycheckIpLookup.requestHttp(
                    client, "8.8.8.8", URI.create("http://127.0.0.1:${server.address.port}/$path"), timeout,
                )
                assertTrue(request("ok")!!.vpn == true)
                assertNull(request("quota"))
                assertNull(request("oversized"))
                assertNull(request("redirect"))
                assertNull(request("slow", Duration.ofMillis(100)))
            } finally { server.stop(0); workers.shutdownNow() }
        }
    }
}
