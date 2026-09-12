package cc.tomko.outify.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CloudDiagnosticsTest {
    @Test fun uploadConfigurationAcceptsOnlyCompleteHexCapabilities() {
        assertTrue(CloudUploader.validToken("a".repeat(64)))
        listOf("", "a".repeat(63), "g".repeat(64), "a".repeat(64) + "\n").forEach {
            assertFalse(CloudUploader.validToken(it))
        }
        val configured = cc.tomko.outify.BuildConfig.DIAGNOSTICS_UPLOAD_TOKEN
        assertEquals(configured.isNotEmpty(), CloudUploader.isConfigured)
        assertTrue(configured.isEmpty() || CloudUploader.validToken(configured))
    }

    private val audio = "pcm: frames=12 lastSize=32 lastRate=44100 lastChannels=2"
    private fun report(extra: String = "") = "--- Audio engine ---\n$audio\n$extra\n--- Recorded events (oldest first) ---\nsecret"

    @Test fun snapshotKeepsTypedCountersButOmitsPrivateFreeText() {
        val privateText = "Authorization: Bearer secret\nCookie: session=secret\npassword=secret\n" +
            "token=secret\nclient_secret=secret\nemail=a@example.com\nip=192.168.1.1\n" +
            "userId=123\ndeviceId=456\nhttps://example.com/?token=secret\n" +
            "{\n\"password\":\n\"secret\"\n}\ntrack=private song"
        val snapshot = CloudReport.prepare(report(privateText))!!
        assertEquals("Spoty cloud audio summary\n$audio", snapshot.text)
        assertFalse(snapshot.text.contains("secret"))
    }

    @Test fun rejectsMissingOversizeAndUnvalidatedFields() {
        assertNull(CloudReport.prepare(""))
        assertNull(CloudReport.prepare("x".repeat(CloudReport.MAX_BYTES + 1)))
        assertNull(CloudReport.prepare("é".repeat(CloudReport.MAX_BYTES / 2 + 1)))
        assertNull(CloudReport.prepare("--- Audio engine ---\npcm: frames=secret"))
        assertNull(CloudReport.prepare("--- Recorded events (oldest first) ---\n$audio"))
    }

    @Test fun snapshotDoesNotFollowSubsequentReportChanges() {
        var current = report()
        val snapshot = CloudReport.prepare(current)!!
        current = report("writes: bytes=5 errors=2 partial=0 dropped=0 lastResult=-1")
        assertFalse(snapshot.text.contains("writes"))
        assertTrue(CloudReport.prepare(current)!!.text.contains("errors=2"))
    }

    @Test fun mapsHttpFailuresWithoutServerText() {
        mapOf(401 to CloudFailure.AUTH, 413 to CloudFailure.SIZE, 429 to CloudFailure.CAPACITY,
            503 to CloudFailure.UNAVAILABLE, 302 to CloudFailure.UNAVAILABLE).forEach { (code, expected) ->
            assertEquals(expected, CloudUploader.failure(code))
        }
    }

    @Test fun validatesReceiptAndRejectsUnexpectedValues() {
        val id = "8b5ecd54-30a4-444b-a5f9-64093fd8a320"
        assertEquals(id, CloudUploader.receipt("""{"id":"$id","expires_at":1789755536}""").id)
        listOf("{}", """{"id":"secret","expires_at":1}""",
            """{"id":"$id","expires_at":-1}""").forEach {
            assertTrue(runCatching { CloudUploader.receipt(it) }.isFailure)
        }
    }

    @Test fun transportDisablesRedirectsAndAutomaticRetries() {
        val client = CloudUploader.client
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(30000, client.callTimeoutMillis)
    }
}
