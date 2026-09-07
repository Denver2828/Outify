package cc.tomko.outify.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class RefreshFailureTest {

    @Test
    fun `rate limit exception is rate limited`() {
        assertEquals(
            RefreshFailure.RATE_LIMITED,
            RefreshFailure.classify(RateLimitException("429", 12), gateLimited = false),
        )
    }

    @Test
    fun `native service unavailable is a network failure`() {
        val error = NativeOperationException("down", NativeError.ServiceUnavailable("down"))

        assertEquals(RefreshFailure.NETWORK, RefreshFailure.classify(error, gateLimited = false))
    }

    @Test
    fun `io exception is a network failure and armed gate wins`() {
        assertEquals(RefreshFailure.NETWORK, RefreshFailure.classify(IOException("x"), gateLimited = false))
        assertEquals(RefreshFailure.RATE_LIMITED, RefreshFailure.classify(IOException("x"), gateLimited = true))
    }

    @Test
    fun `unknown exception is other`() {
        assertEquals(RefreshFailure.OTHER, RefreshFailure.classify(IllegalStateException("x"), gateLimited = false))
    }
}
