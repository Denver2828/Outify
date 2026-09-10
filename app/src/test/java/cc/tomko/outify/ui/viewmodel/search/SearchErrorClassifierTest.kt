package cc.tomko.outify.ui.viewmodel.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchErrorClassifierTest {
    @Test
    fun `native evidence selects safe error categories through nested causes`() {
        val cases = mapOf(
            "search failed with status 401: rejected" to SearchErrorKind.AUTH,
            "No account token present!" to SearchErrorKind.MISSING_ACCOUNT,
            "refresh_token failed with status 400: invalid_client" to SearchErrorKind.REJECTED,
            "refresh_token failed with status 400: invalid_grant" to SearchErrorKind.REJECTED,
            "HTTP 403: denied" to SearchErrorKind.FORBIDDEN,
            "search failed with status 400: invalid parameter" to SearchErrorKind.BAD_REQUEST,
            "HTTP 503: unavailable" to SearchErrorKind.SERVER,
            "rate limited, retry after 20 s" to SearchErrorKind.RATE_LIMITED,
            "HTTP request failed: connection timed out" to SearchErrorKind.NETWORK,
            "JSON error: missing field" to SearchErrorKind.DECODING,
            "track403 HTTPish401" to SearchErrorKind.OTHER,
        )
        cases.forEach { (message, expected) ->
            val error = RuntimeException("wrapper", RuntimeException(message))
            assertEquals(message, expected, SearchErrorClassifier.classify(error, false))
        }
        assertEquals(SearchErrorKind.AUTH,
            SearchErrorClassifier.classify(RuntimeException("HTTP 401"), true))
        assertEquals(SearchErrorKind.RATE_LIMITED,
            SearchErrorClassifier.classify(RuntimeException("unknown"), true))
    }
}
