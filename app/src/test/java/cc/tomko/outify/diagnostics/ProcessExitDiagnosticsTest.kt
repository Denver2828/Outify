package cc.tomko.outify.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDiagnosticsTest {

    private fun dump(headerLines: Int, mainAt: Int, mainLines: Int, trailing: Int): String = buildString {
        repeat(headerLines) { appendLine("header $it") }
        repeat(mainAt - headerLines) { appendLine("\"Thread-$it\" prio=5 tid=$it Native") }
        appendLine("\"main\" prio=5 tid=1 Blocked")
        repeat(mainLines - 1) { appendLine("  at cc.tomko.outify.Frame$it(Frame.kt:$it)") }
        appendLine()
        repeat(trailing) { appendLine("\"Worker-$it\" prio=5 tid=${100 + it} Waiting") }
    }

    @Test
    fun `short traces pass through untouched`() {
        val trace = dump(headerLines = 5, mainAt = 5, mainLines = 10, trailing = 3)

        assertEquals(trace, ProcessExitDiagnostics.truncateTrace(trace))
    }

    @Test
    fun `main thread block beyond the head window is kept in full`() {
        val trace = dump(headerLines = 10, mainAt = 350, mainLines = 12, trailing = 200)

        val result = ProcessExitDiagnostics.truncateTrace(trace, headLines = 300)

        assertTrue(result.contains("\"main\" prio=5 tid=1 Blocked"))
        assertTrue(result.contains("cc.tomko.outify.Frame10(Frame.kt:10)"))
        assertTrue(result.contains("main thread block, found at line 351"))
        assertTrue(result.contains("[trace truncated,"))
        assertFalse(result.contains("Worker-199"))
    }

    @Test
    fun `main thread block inside the head window is not duplicated`() {
        val trace = dump(headerLines = 10, mainAt = 20, mainLines = 12, trailing = 500)

        val result = ProcessExitDiagnostics.truncateTrace(trace, headLines = 300)

        assertEquals(1, Regex("\"main\" prio=5").findAll(result).count())
        assertFalse(result.contains("main thread block, found at line"))
        assertTrue(result.endsWith("lines omitted]"))
    }

    @Test
    fun `character cap is enforced on top of the line window`() {
        val trace = dump(headerLines = 10, mainAt = 350, mainLines = 12, trailing = 0)

        val result = ProcessExitDiagnostics.truncateTrace(trace, headLines = 300, maxChars = 2_000)

        assertTrue(result.contains("[trace cut at 2000 characters]"))
        assertTrue(result.length < 2_200)
    }
}

class ProcessExitBinaryTraceTest {
    @org.junit.Test
    fun `text traces are not binary`() {
        val text = "Subject: ANR\n\"main\" prio=5 tid=1 Native\n".toByteArray()
        org.junit.Assert.assertFalse(ProcessExitDiagnostics.looksBinary(text))
    }

    @org.junit.Test
    fun `tombstone-like blobs are binary and yield their strings`() {
        val blob = byteArrayOf(0x0A, 0x12, 0x00, 0x03) +
            "SIGABRT".toByteArray() + byteArrayOf(0x00, 0x01) +
            "panic: called Option::unwrap() on a None value".toByteArray() +
            byteArrayOf(0x00, 0x7F.toByte()) + "ab".toByteArray()
        org.junit.Assert.assertTrue(ProcessExitDiagnostics.looksBinary(blob))
        val strings = ProcessExitDiagnostics.printableStrings(blob)
        org.junit.Assert.assertEquals(
            "SIGABRT\npanic: called Option::unwrap() on a None value\n",
            strings,
        )
    }
}
