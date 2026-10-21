package com.zarz.mornye

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.*
import org.junit.Test

class OdifyAudioRequestsTest {
    @Test fun warmupAndPlaybackShareOneExtraction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val requests = OdifyAudioRequests<String>(scope, 2000)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val warmup = async {
                requests.get("track") {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    "audio"
                }
            }
            started.await()
            // Start the second request before the first loader can finish.
            val playback = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                requests.get("track") { calls.incrementAndGet(); "duplicate" }
            }
            release.complete(Unit)
            assertEquals("audio", warmup.await())
            assertEquals("audio", playback.await())
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun cancellingAWaiterDoesNotCancelPlayback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val requests = OdifyAudioRequests<String>(scope, 2000)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val warmup = async {
                requests.get("track") { started.complete(Unit); release.await(); "audio" }
            }
            started.await()
            warmup.cancel()
            val playback = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                requests.get("track") { error("must join the existing extraction") }
            }
            release.complete(Unit)
            assertEquals("audio", playback.await())
        } finally { scope.cancel() }
    }

    @Test fun budgetInterruptsBlockingExtractionAndAllowsRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val requests = OdifyAudioRequests<String>(scope, 150)
            val interrupted = CountDownLatch(1)
            try {
                requests.get("track") {
                    runInterruptible(Dispatchers.IO) {
                        try { Thread.sleep(60_000); "unexpected" }
                        finally { interrupted.countDown() }
                    }
                }
                fail("must time out")
            } catch (error: IllegalStateException) {
                assertEquals("NETWORK_TIMEOUT", error.message)
            }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertEquals("fresh", requests.get("track") { "fresh" })
        } finally { scope.cancel() }
    }

    @Test fun invalidationCancelsStaleWorkWithoutRemovingItsReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val requests = OdifyAudioRequests<String>(scope, 2000)
            val started = CompletableDeferred<Unit>()
            val stale = async {
                requests.get("track") {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    "stale"
                }
            }
            started.await()
            requests.invalidate("track")
            try { stale.await(); fail("stale request must be cancelled") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("fresh", requests.get("track") { "fresh" })
        } finally { scope.cancel() }
    }
}
