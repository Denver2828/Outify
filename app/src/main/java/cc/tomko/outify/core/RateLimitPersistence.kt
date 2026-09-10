package cc.tomko.outify.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One writer preserves snapshot order; process death before a write commits can lose it. */
object RateLimitPersistence {
    suspend fun run(
        gate: RateLimitGate,
        read: suspend () -> Long,
        write: suspend (Long) -> Unit,
        initialize: (Long, (Long) -> Unit) -> Unit,
    ): Unit = coroutineScope {
        // Never publish a native client, or write the initial zero, before reading storage.
        gate.restoreFrom(read())
        launch { gate.untilMs.collect { write(it) } }
        initialize(gate.effectiveUntilMs(), gate::adoptNativeUntilMs)
    }
}
