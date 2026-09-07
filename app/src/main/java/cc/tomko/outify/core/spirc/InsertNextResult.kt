package cc.tomko.outify.core.spirc

/**
 * Outcome of a "play next" insertion. Mirrors the result codes returned by the native
 * `Spirc.insertNext` call, plus the Kotlin-side pre-checks.
 */
enum class InsertNextResult {
    /** Inserted ahead of the next tracks; current track, position and history untouched. */
    INSERTED,

    /**
     * Inserted ahead of the next tracks. The current track and position were kept, but queued
     * tracks were already at the front so the next tracks had to be rewritten, which clears the
     * previous-tracks history on the native side.
     */
    INSERTED_HISTORY_CLEARED,

    /** Nothing is playing, so there is no "next" to insert ahead of. */
    NOTHING_PLAYING,

    /** The native call rejected the request or the session is unavailable. */
    FAILED;

    val succeeded: Boolean
        get() = this == INSERTED || this == INSERTED_HISTORY_CLEARED

    companion object {
        private const val NATIVE_FAILED = 0
        private const val NATIVE_INSERTED = 1
        private const val NATIVE_INSERTED_HISTORY_CLEARED = 2

        /** Maps the integer returned by the native `insertNext` export. Unknown codes are failures. */
        fun fromNative(code: Int): InsertNextResult = when (code) {
            NATIVE_INSERTED -> INSERTED
            NATIVE_INSERTED_HISTORY_CLEARED -> INSERTED_HISTORY_CLEARED
            NATIVE_FAILED -> FAILED
            else -> FAILED
        }
    }
}
