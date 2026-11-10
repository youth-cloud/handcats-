package com.handcats.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SingleFlightTest {
    @Test fun overlappingConsumersShareOneRequest() = runBlocking {
        val cache = SingleFlight<String, String>(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun load(): String { calls++; started.complete(Unit); release.await(); return "photo" }
        val first = async { cache.get("same", ::load) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.get("same", ::load) }
        release.complete(Unit)
        assertEquals(listOf("photo", "photo"), awaitAll(first, second))
        assertEquals(1, calls)
    }

    @Test fun leavingPreviewDoesNotCancelDetailDownload() = runBlocking {
        val cache = SingleFlight<String, String>(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun load(): String { calls++; started.complete(Unit); release.await(); return "photo" }
        val preview = async { cache.get("same", ::load) }
        started.await()
        preview.cancelAndJoin()
        val detail = async(start = CoroutineStart.UNDISPATCHED) { cache.get("same", ::load) }
        release.complete(Unit)
        assertEquals("photo", detail.await())
        assertEquals(1, calls)
    }

    @Test fun failedDownloadCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = SingleFlight<String, String>(scope)
            try { cache.get("photo") { throw IOException("offline") }; fail("Expected failure") }
            catch (_: IOException) { }
            assertEquals("retry", cache.get("photo") { "retry" })
        } finally { scope.cancel() }
    }

    @Test fun clearingSessionCancelsOldRequestsAndAllowsNewSession() = runBlocking {
        val cache = SingleFlight<String, String>(this)
        val started = CompletableDeferred<Unit>()
        val old = async {
            cache.get("photo") { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        cache.clear()
        try { old.await(); fail("Old request must be cancelled") } catch (_: CancellationException) { }
        assertEquals("new session", cache.get("photo") { "new session" })
    }
}
