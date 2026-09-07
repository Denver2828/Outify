package cc.tomko.outify.data.repository

import kotlinx.coroutines.CancellationException

/**
 * Outcome of a like toggle.
 * @property liked the local liked state once the procedure finished.
 * @property succeeded whether Spotify accepted the change; `false` means it was rolled back.
 */
data class LikeToggleResult(val liked: Boolean, val succeeded: Boolean)

/**
 * Shared "flip a liked flag" procedure used by every like control in the app.
 *
 * Order of operations:
 * 1. read the current local state;
 * 2. flip it locally so the UI reacts immediately;
 * 3. run the remote call;
 * 4. on a remote failure (a `false` result or a non-cancellation exception) restore the
 *    previous local state.
 *
 * The remote lambda is expected to be main-safe already. Callers that need to react to the
 * local flips (for example, a media notification that is not observing the database) get
 * [onLocalStateChanged] on every local write, including the rollback.
 */
internal suspend fun optimisticLikeToggle(
    isLiked: suspend () -> Boolean,
    add: suspend () -> Unit,
    remove: suspend () -> Unit,
    remote: suspend (wasLiked: Boolean) -> Boolean,
    onLocalStateChanged: suspend () -> Unit = {},
): LikeToggleResult {
    val wasLiked = isLiked()

    if (wasLiked) remove() else add()
    onLocalStateChanged()

    val success = try {
        remote(wasLiked)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    if (!success) {
        if (wasLiked) add() else remove()
        onLocalStateChanged()
        return LikeToggleResult(liked = wasLiked, succeeded = false)
    }
    return LikeToggleResult(liked = !wasLiked, succeeded = true)
}
