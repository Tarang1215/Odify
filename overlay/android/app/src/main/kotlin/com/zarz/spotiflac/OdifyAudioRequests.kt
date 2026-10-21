package com.zarz.spotiflac

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Shares bounded extraction work across Play and next-track warmup. */
internal class OdifyAudioRequests<T>(
    private val scope: CoroutineScope,
    private val budgetMs: Long,
) {
    private val pending = ConcurrentHashMap<String, Deferred<T>>()

    suspend fun get(key: String, loader: suspend () -> T): T {
        val created = scope.async(start = CoroutineStart.LAZY) {
            try {
                withTimeout(budgetMs) { loader() }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("NETWORK_TIMEOUT", error)
            }
        }
        val existing = pending.putIfAbsent(key, created)
        val active = existing ?: created
        if (existing != null) {
            created.cancel()
        } else {
            created.invokeOnCompletion { pending.remove(key, created) }
            created.start()
        }
        return active.await()
    }

    fun invalidate(key: String) {
        pending.remove(key)?.cancel()
    }
}
