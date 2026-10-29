package com.riyadm.unsendvault.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * The app's only network client: one anonymous HTTPS GET to [LATEST_RELEASE_URL].
 *
 * The request carries only the headers set in [fetchLatest] (Accept, API version, User-Agent "UnsendVault/<version>"):
 * no cookies, no auth, no body, no device or archive data. The response is size-limited and parsed into
 * [ReleaseInfo]; every failure becomes a [FetchResult.Failed] instead of an exception.
 */
class GitHubReleaseClient internal constructor(
    private val endpoint: String,
    private val appVersion: String,
    private val dispatcher: CoroutineDispatcher,
    private val timeoutMillis: Int,
    private val maxResponseBytes: Int,
    /** Tests only: lets the JVM tests talk to a plain-HTTP server on the loopback interface. */
    private val allowLoopbackHttpForTests: Boolean,
) : ReleaseSource {

    constructor(appVersion: String) : this(
        endpoint = LATEST_RELEASE_URL,
        appVersion = appVersion,
        dispatcher = Dispatchers.IO,
        timeoutMillis = TIMEOUT_MILLIS,
        maxResponseBytes = MAX_RESPONSE_BYTES,
        allowLoopbackHttpForTests = false,
    )

    override suspend fun fetchLatest(): FetchResult = withContext(dispatcher) {
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnknownHostException) {
            FetchResult.Failed(FailureReason.OFFLINE)
        } catch (_: ConnectException) {
            FetchResult.Failed(FailureReason.OFFLINE)
        } catch (_: NoRouteToHostException) {
            FetchResult.Failed(FailureReason.OFFLINE)
        } catch (_: SocketTimeoutException) {
            FetchResult.Failed(FailureReason.TIMEOUT)
        } catch (_: ResponseTooLargeException) {
            FetchResult.Failed(FailureReason.TOO_LARGE)
        } catch (_: DeadlineExceededException) {
            FetchResult.Failed(FailureReason.TIMEOUT)
        } catch (_: SSLException) {
            FetchResult.Failed(FailureReason.NETWORK)
        } catch (_: IOException) {
            FetchResult.Failed(FailureReason.NETWORK)
        } catch (_: Exception) {
            // SecurityException (permission revoked), IllegalStateException from the platform stack, ...
            FetchResult.Failed(FailureReason.NETWORK)
        }
    }

    private fun request(): FetchResult {
        val url = URL(endpoint)
        if (!isAllowedEndpoint(url)) return FetchResult.Failed(FailureReason.NETWORK)
        val connection = url.openConnection() as HttpURLConnection
        if (connection !is HttpsURLConnection && !allowLoopbackHttpForTests) {
            connection.disconnect()
            return FetchResult.Failed(FailureReason.NETWORK)
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.doOutput = false
            connection.instanceFollowRedirects = true // same-protocol only, so HTTPS stays HTTPS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "UnsendVault/$appVersion")

            val status = connection.responseCode
            return when {
                status == HttpURLConnection.HTTP_OK -> {
                    val declared = connection.contentLengthLong
                    if (declared > maxResponseBytes) throw ResponseTooLargeException()
                    val text = connection.inputStream.use { readLimited(it) }.toString(Charsets.UTF_8)
                    when (val parsed = ReleaseParser.parse(text)) {
                        is ReleaseParser.Result.Release -> FetchResult.Found(parsed.release)
                        ReleaseParser.Result.NotEligible -> FetchResult.NoRelease
                        ReleaseParser.Result.Malformed -> FetchResult.Failed(FailureReason.INVALID_RESPONSE, status)
                    }
                }
                status == HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.NoRelease
                status == 429 -> FetchResult.Failed(FailureReason.RATE_LIMITED, status)
                status == HttpURLConnection.HTTP_FORBIDDEN && isRateLimited(connection) ->
                    FetchResult.Failed(FailureReason.RATE_LIMITED, status)
                else -> FetchResult.Failed(FailureReason.HTTP_ERROR, status)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowedEndpoint(url: URL): Boolean = when (url.protocol) {
        "https" -> true
        "http" -> allowLoopbackHttpForTests && runCatching { InetAddress.getByName(url.host).isLoopbackAddress }.getOrDefault(false)
        else -> false
    }

    /** GitHub signals an exhausted primary limit with remaining=0, and a secondary limit with Retry-After. */
    private fun isRateLimited(connection: HttpURLConnection): Boolean =
        connection.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" || connection.getHeaderField("Retry-After") != null

    private fun readLimited(input: InputStream): ByteArray {
        val deadline = System.nanoTime() + OVERALL_DEADLINE_NANOS
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxResponseBytes) throw ResponseTooLargeException()
            out.write(buffer, 0, n)
            if (System.nanoTime() > deadline) throw DeadlineExceededException()
        }
        return out.toByteArray()
    }

    private class ResponseTooLargeException : IOException()

    private class DeadlineExceededException : IOException()

    companion object {
        /** Fixed; never built from user input or from the response. */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/riyadmondol2006/UnsendVault/releases/latest"
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        private const val OVERALL_DEADLINE_NANOS = 30_000_000_000L
    }
}
