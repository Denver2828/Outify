package cc.tomko.outify.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Describes why previous instances of this process died.
 *
 * The audio report's logcat section only covers the live process, so an ANR or a crash
 * that killed the app minutes earlier leaves no trace in it. Android keeps the exit
 * reasons (with the ANR thread dump) and hands them back on the next run; this is the
 * only way to see a freeze from a phone without adb.
 */
object ProcessExitDiagnostics {
    private const val TAG = "ProcessExit"
    private const val MAX_EXITS = 10

    /** Lines of a trace kept before falling back to the main-thread block only. */
    internal const val TRACE_HEAD_LINES = 300

    /** Rough upper bound for one trace inside the report. */
    internal const val TRACE_MAX_CHARS = 40_000

    fun describe(context: Context): String = buildString {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLine("unavailable before Android 11")
            return@buildString
        }
        val exits = try {
            historicalExits(context)
        } catch (e: Exception) {
            appendLine("unavailable: $e")
            return@buildString
        }
        if (exits.isEmpty()) {
            appendLine("none recorded")
            return@buildString
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        exits.forEach { exit ->
            appendLine(
                "${dateFormat.format(Date(exit.timestamp))} reason=${reasonName(exit.reason)} " +
                    "status=${exit.status} importance=${importanceName(exit.importance)} " +
                    "pss=${exit.pss}kB rss=${exit.rss}kB pid=${exit.pid} process=${exit.processName}"
            )
            exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("  description: $it") }
            if (exit.reason == ApplicationExitInfo.REASON_ANR ||
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                appendLine("  trace:")
                appendLine(readTrace(exit).prependIndent("    "))
            }
        }
    }

    /**
     * One-line summary of the most recent exit, written to logcat so the next report's
     * logcat section also carries it. Runs whatever the caller's thread is; the system
     * call is a cheap binder round trip but callers should still keep it off the main thread.
     */
    fun logLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val last = historicalExits(context).firstOrNull() ?: return
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            Log.i(
                TAG,
                "last exit at ${dateFormat.format(Date(last.timestamp))}: reason=${reasonName(last.reason)} " +
                    "status=${last.status} description=${last.description ?: "-"}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read process exit history", e)
        }
    }

    private fun historicalExits(context: Context): List<ApplicationExitInfo> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        return activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
    }

    private fun readTrace(exit: ApplicationExitInfo): String {
        return try {
            val stream = exit.traceInputStream ?: return "trace unavailable: no trace attached"
            val bytes = stream.use { it.readBytes() }
            if (bytes.isEmpty()) return "trace unavailable: empty"
            // ANR traces are plain text; native crash traces are a binary tombstone protobuf.
            if (looksBinary(bytes)) {
                "(binary tombstone, ${bytes.size} bytes; printable strings follow)\n" +
                    truncateTrace(printableStrings(bytes))
            } else {
                truncateTrace(String(bytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            "trace unavailable: ${e.message ?: e}"
        }
    }

    /** Pids of the most recent abnormal exits (ANR, crash, native crash), newest first. */
    fun abnormalExitPids(context: Context, max: Int = 2): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            historicalExits(context)
                .filter {
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                        it.reason == ApplicationExitInfo.REASON_CRASH ||
                        it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                }
                .map { it.pid }
                .take(max)
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.size.coerceAtMost(512)
        var control = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0 || (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D)) control++
        }
        return control > sample / 16
    }

    /**
     * Extracts runs of printable ASCII (length >= [minRun]) from a binary blob, one per line,
     * the way `strings` does. Enough to read frames, abort messages and library paths from a
     * tombstone without a protobuf parser.
     */
    internal fun printableStrings(bytes: ByteArray, minRun: Int = 6): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minRun) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return out.toString()
    }

    /**
     * Keeps the first [headLines] lines of an ANR dump plus the complete "main" thread
     * block when that block starts after the head window, then notes how many lines were
     * dropped. Pure so it can be unit tested without Android classes.
     */
    internal fun truncateTrace(
        trace: String,
        headLines: Int = TRACE_HEAD_LINES,
        maxChars: Int = TRACE_MAX_CHARS,
    ): String {
        val lines = trace.lines()
        if (lines.size <= headLines && trace.length <= maxChars) return trace

        val head = lines.take(headLines)
        val mainStart = lines.indexOfFirst { it.startsWith("\"main\"") }
        val mainBlock = if (mainStart >= headLines) {
            val end = (mainStart + 1 until lines.size)
                .firstOrNull { lines[it].isBlank() }
                ?: lines.size
            lines.subList(mainStart, end)
        } else {
            emptyList()
        }

        val kept = buildList {
            addAll(head)
            if (mainBlock.isNotEmpty()) {
                add("")
                add("[... main thread block, found at line ${mainStart + 1} ...]")
                addAll(mainBlock)
            }
        }
        var text = kept.joinToString("\n")
        if (text.length > maxChars) {
            text = text.take(maxChars) + "\n[trace cut at $maxChars characters]"
        }
        val omitted = lines.size - head.size - mainBlock.size
        return if (omitted > 0) "$text\n[trace truncated, $omitted lines omitted]" else text
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "REASON_UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "REASON_USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "REASON_FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "REASON_PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "REASON_PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "IMPORTANCE_$importance"
    }
}
