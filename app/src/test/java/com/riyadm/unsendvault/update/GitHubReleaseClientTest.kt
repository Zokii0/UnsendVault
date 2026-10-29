package com.riyadm.unsendvault.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors

/** Talks to a throwaway HTTP server on 127.0.0.1; the production endpoint is never contacted by tests. */
class GitHubReleaseClientTest {
    private val fixture = checkNotNull(javaClass.classLoader?.getResource("update/github_release_latest.json")).readBytes()
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    @Volatile private var handler: (HttpExchange) -> Unit = { it.respond(200, fixture) }

    data class RecordedRequest(val method: String, val path: String, val headers: Map<String, List<String>>, val bodyBytes: Int)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            requests += RecordedRequest(
                exchange.requestMethod,
                exchange.requestURI.toString(),
                exchange.requestHeaders.mapKeys { it.key.lowercase() },
                body.size,
            )
            try {
                handler(exchange)
            } catch (_: Exception) {
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    private val endpoint get() = "http://127.0.0.1:${server.address.port}/repos/riyadmondol2006/UnsendVault/releases/latest"

    private fun client(url: String = endpoint, timeoutMillis: Int = 2_000, maxBytes: Int = GitHubReleaseClient.MAX_RESPONSE_BYTES) =
        GitHubReleaseClient(url, "1.0.15", Dispatchers.IO, timeoutMillis, maxBytes, allowLoopbackHttpForTests = true)

    private fun fetch(c: GitHubReleaseClient = client()) = runBlocking { c.fetchLatest() }

    @Test
    fun productionEndpointIsAFixedHttpsUrl() {
        assertEquals("https://api.github.com/repos/riyadmondol2006/UnsendVault/releases/latest", GitHubReleaseClient.LATEST_RELEASE_URL)
        assertEquals(10_000, GitHubReleaseClient.TIMEOUT_MILLIS)
        assertEquals(512 * 1024, GitHubReleaseClient.MAX_RESPONSE_BYTES)
    }

    @Test
    fun success_parsesRelease_andSendsOnlyTheDocumentedHeaders() {
        val result = fetch()
        assertTrue("$result", result is FetchResult.Found)
        assertEquals("1.0.15", (result as FetchResult.Found).release.version)

        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/repos/riyadmondol2006/UnsendVault/releases/latest", request.path) // no query string
        assertEquals(0, request.bodyBytes)
        assertEquals(listOf("application/vnd.github+json"), request.headers["accept"])
        assertEquals(listOf("2022-11-28"), request.headers["x-github-api-version"])
        assertEquals(listOf("UnsendVault/1.0.15"), request.headers["user-agent"])
        for (forbidden in listOf("cookie", "authorization", "content-type", "content-length")) {
            assertNull("unexpected $forbidden header", request.headers[forbidden])
        }
    }

    @Test
    fun notFound_meansNoRelease() {
        handler = { it.respond(404, """{"message":"Not Found"}""".toByteArray()) }
        assertEquals(FetchResult.NoRelease, fetch())
    }

    @Test
    fun draftOrPrerelease_meansNoRelease() {
        handler = { it.respond(200, String(fixture).replace("\"prerelease\": false", "\"prerelease\": true").toByteArray()) }
        assertEquals(FetchResult.NoRelease, fetch())
    }

    @Test
    fun rateLimits() {
        handler = { it.responseHeaders.add("X-RateLimit-Remaining", "0"); it.respond(403, """{"message":"API rate limit exceeded"}""".toByteArray()) }
        assertEquals(FetchResult.Failed(FailureReason.RATE_LIMITED, 403), fetch())

        handler = { it.responseHeaders.add("Retry-After", "60"); it.respond(403, ByteArray(0)) }
        assertEquals(FetchResult.Failed(FailureReason.RATE_LIMITED, 403), fetch())

        handler = { it.respond(429, ByteArray(0)) }
        assertEquals(FetchResult.Failed(FailureReason.RATE_LIMITED, 429), fetch())
    }

    @Test
    fun otherHttpErrors() {
        handler = { it.responseHeaders.add("X-RateLimit-Remaining", "42"); it.respond(403, ByteArray(0)) }
        assertEquals(FetchResult.Failed(FailureReason.HTTP_ERROR, 403), fetch())
        handler = { it.respond(500, "oops".toByteArray()) }
        assertEquals(FetchResult.Failed(FailureReason.HTTP_ERROR, 500), fetch())
        handler = { it.respond(301, ByteArray(0)) } // redirect without Location
        assertEquals(FetchResult.Failed(FailureReason.HTTP_ERROR, 301), fetch())
    }

    @Test
    fun malformedJson_isInvalidResponse() {
        handler = { it.respond(200, "<html>not json</html>".toByteArray()) }
        assertEquals(FetchResult.Failed(FailureReason.INVALID_RESPONSE, 200), fetch())
    }

    @Test
    fun oversizedResponse_isRejected_withContentLength() {
        handler = { it.respond(200, ByteArray(4096) { ' '.code.toByte() }) }
        assertEquals(FetchResult.Failed(FailureReason.TOO_LARGE), fetch(client(maxBytes = 1024)))
    }

    @Test
    fun oversizedResponse_isRejected_whenChunked() {
        handler = { exchange ->
            exchange.sendResponseHeaders(200, 0) // 0 = chunked, no Content-Length
            exchange.responseBody.use { out -> repeat(64) { out.write(ByteArray(1024) { ' '.code.toByte() }) } }
        }
        assertEquals(FetchResult.Failed(FailureReason.TOO_LARGE), fetch(client(maxBytes = 16 * 1024)))
    }

    @Test
    fun realFixtureFitsTheProductionLimit() {
        assertTrue(fixture.size < GitHubReleaseClient.MAX_RESPONSE_BYTES)
    }

    @Test
    fun slowServer_timesOut() {
        handler = { Thread.sleep(3_000); it.respond(200, fixture) }
        assertEquals(FetchResult.Failed(FailureReason.TIMEOUT), fetch(client(timeoutMillis = 300)))
    }

    @Test
    fun connectionRefused_isOffline() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(FetchResult.Failed(FailureReason.OFFLINE), fetch(client(url = "http://127.0.0.1:$closedPort/x")))
    }

    @Test
    fun unknownHost_isOffline() {
        // .invalid never resolves (RFC 2606); the request fails before any connection is made.
        val result = fetch(client(url = "https://update-check.invalid/repos/x/releases/latest"))
        assertEquals(FetchResult.Failed(FailureReason.OFFLINE), result)
    }

    @Test
    fun plainHttpToANonLoopbackHost_isRefusedWithoutConnecting() {
        assertEquals(FetchResult.Failed(FailureReason.NETWORK), fetch(client(url = "http://example.com/releases/latest")))
        assertEquals(FetchResult.Failed(FailureReason.NETWORK), fetch(client(url = "ftp://127.0.0.1/releases/latest")))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun plainHttpIsRefusedWithoutTheTestOnlyFlag() {
        val strict = GitHubReleaseClient(endpoint, "1.0.15", Dispatchers.IO, 2_000, 1024, allowLoopbackHttpForTests = false)
        assertEquals(FetchResult.Failed(FailureReason.NETWORK), fetch(strict))
        assertFalse(requests.isNotEmpty())
    }

    private fun HttpExchange.respond(status: Int, body: ByteArray) {
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) }
    }
}
