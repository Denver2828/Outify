package cc.tomko.outify.data.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A cached value and when it was stored, for TTL checks. */
data class CacheEntry<T>(val value: T, val storedAtMs: Long)

/** What a cache-first load emits, in order: at most one [Cached], then one [Fresh] or [RefreshFailed]. */
sealed class CacheFirstResult<out T> {
    /** Served from the local cache right away; [stale] means a refresh is on its way. */
    data class Cached<T>(val value: T, val stale: Boolean) : CacheFirstResult<T>()

    /** The refresh completed and this is the up-to-date value. */
    data class Fresh<T>(val value: T) : CacheFirstResult<T>()

    /** The refresh failed; [cached] is what is still available (null when nothing was cached). */
    data class RefreshFailed<T>(val kind: RefreshFailure, val cached: T?) : CacheFirstResult<T>()
}

/**
 * Cache-first policy, free of Android types so it can be unit tested.
 *
 * A valid cached value (younger than [ttlMs]) is emitted immediately and, unless the caller
 * forces it, no refresh happens. A stale or missing value triggers one refresh whose outcome
 * is emitted after the cached one. A failed refresh never removes what was cached.
 */
class CacheFirstLoader<T>(
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun isStale(storedAtMs: Long): Boolean = clock() - storedAtMs >= ttlMs

    /**
     * @param force refresh even when the cached value is still fresh (manual refresh).
     * @param readCache local read; null when nothing is cached.
     * @param refresh remote fetch and persist; receives the cached value for diffing.
     * @param classify maps a refresh exception to the failure kind reported to the UI.
     */
    fun load(
        force: Boolean,
        readCache: suspend () -> CacheEntry<T>?,
        refresh: suspend (cached: T?) -> T,
        classify: (Throwable) -> RefreshFailure,
    ): Flow<CacheFirstResult<T>> = flow {
        val entry = readCache()
        val stale = entry == null || isStale(entry.storedAtMs)

        if (entry != null) {
            emit(CacheFirstResult.Cached(entry.value, stale = stale))
            if (!stale && !force) return@flow
        }

        val fresh = try {
            refresh(entry?.value)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            emit(CacheFirstResult.RefreshFailed(classify(t), entry?.value))
            return@flow
        }
        emit(CacheFirstResult.Fresh(fresh))
    }
}
