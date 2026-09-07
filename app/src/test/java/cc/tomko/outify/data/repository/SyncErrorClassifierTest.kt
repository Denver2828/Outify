package cc.tomko.outify.data.repository

import cc.tomko.outify.core.SpClientException
import cc.tomko.outify.data.metadata.NativeError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncErrorClassifierTest {

    @Test
    fun `native rate limit error is rate limited`() {
        val error = SpClientException("429", NativeError.RateLimited("rate limited", 30))

        assertEquals(SyncFailure.RATE_LIMITED, SyncErrorClassifier.classify(error, gateLimited = false))
    }

    @Test
    fun `messages mentioning 429 or rate limit are rate limited`() {
        val fromJni = RuntimeException("Spotify request failed: rate limited, retry after 12 s: {}")
        val status = RuntimeException("get_top failed with status 429: API rate limit exceeded")

        assertEquals(SyncFailure.RATE_LIMITED, SyncErrorClassifier.classify(fromJni, gateLimited = false))
        assertEquals(SyncFailure.RATE_LIMITED, SyncErrorClassifier.classify(status, gateLimited = false))
    }

    @Test
    fun `a bare 429 inside an id or count is not a rate limit`() {
        val trackId = RuntimeException("Failed to get metadata for spotify:track:4290fzkq1a7ne1dg0bcbeb")
        val byteCount = RuntimeException("decode failed after 14429 bytes")
        assertEquals(SyncFailure.FATAL, SyncErrorClassifier.classify(trackId, gateLimited = false))
        assertEquals(SyncFailure.FATAL, SyncErrorClassifier.classify(byteCount, gateLimited = false))
    }

    @Test
    fun `http status phrasings with 429 are rate limited`() {
        listOf(
            "get_saved failed with status 429: {}",
            "Request failed with status code: 429. Body: {}",
            "HTTP 429 Too Many Requests",
        ).forEach { msg ->
            assertEquals(msg, SyncFailure.RATE_LIMITED, SyncErrorClassifier.classify(RuntimeException(msg), gateLimited = false))
        }
    }

    @Test
    fun `any failure while the gate is armed counts as rate limited`() {
        val error = RuntimeException("error decoding response body")

        assertEquals(SyncFailure.RATE_LIMITED, SyncErrorClassifier.classify(error, gateLimited = true))
        assertEquals(SyncFailure.FATAL, SyncErrorClassifier.classify(error, gateLimited = false))
    }

    @Test
    fun `network and timeout failures are transient`() {
        assertEquals(SyncFailure.TRANSIENT, SyncErrorClassifier.classify(SocketTimeoutException("read timed out"), false))
        assertEquals(SyncFailure.TRANSIENT, SyncErrorClassifier.classify(UnknownHostException("api.spotify.com"), false))
        assertEquals(SyncFailure.TRANSIENT, SyncErrorClassifier.classify(IOException("broken pipe"), false))
        assertEquals(
            SyncFailure.TRANSIENT,
            SyncErrorClassifier.classify(RuntimeException("Connection reset by peer"), false)
        )
    }

    @Test
    fun `cause chain is inspected`() {
        val wrapped = RuntimeException("metadata failed", IOException("network is unreachable"))

        assertEquals(SyncFailure.TRANSIENT, SyncErrorClassifier.classify(wrapped, gateLimited = false))
    }

    @Test
    fun `session gone or bad payload is fatal`() {
        assertEquals(
            SyncFailure.FATAL,
            SyncErrorClassifier.classify(RuntimeException("Internal error { Session not created }"), false)
        )
        assertEquals(SyncFailure.FATAL, SyncErrorClassifier.classify(IllegalStateException("bad json"), false))
    }
}
