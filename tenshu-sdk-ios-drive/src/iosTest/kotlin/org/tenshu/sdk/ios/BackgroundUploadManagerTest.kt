@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.core.TContext
import org.tenshu.sync.common.ContentBlobStore
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3b coverage (ios-drive-parity.md): the O3-B background-upload substrate — the durable journal,
 * the staging decorator, and the manager's stage → enqueue → ack/re-enqueue/resume lifecycle. The
 * live `URLSession(.background)` transfer that survives suspension is framework-level (Swift
 * `BackgroundUpload`) → the P7 on-device pass; here the bridge is a fake that records enqueues, so
 * every branch of the Kotlin orchestration is exercised deterministically.
 *
 * Real dispatchers ([runBlocking]) on purpose: the manager's completion/resume work launches on a
 * live scope, so effects are polled with a timeout the way the sibling runtime tests do.
 */
class BackgroundUploadManagerTest {

    private class FakeBridge : BackgroundUploadBridge {
        data class Enqueued(val ref: String, val filePath: String, val url: String, val headers: Map<String, String>)
        val enqueued = mutableListOf<Enqueued>()
        override fun enqueue(ref: String, filePath: String, urlString: String, headers: Map<String, String>) {
            enqueued += Enqueued(ref, filePath, urlString, headers)
        }

        /** What the session "already holds"; `null` is a bridge that never answers. */
        var live: List<String>? = emptyList()
        /** Runs after the list was read and before it is delivered. */
        var beforeAnswering: () -> Unit = {}
        var asked = 0
        override fun liveTaskRefs(completion: (List<String>) -> Unit) {
            asked++
            val snapshot = live ?: return
            beforeAnswering()
            completion(snapshot)
        }
    }

    private suspend fun awaitInFlight(mgr: BackgroundUploadManager, expected: Int) {
        withTimeout(5_000) { while (mgr.inFlight.current != expected) delay(20) }
    }

    /** A foreground store that only records `putChunk` — for the no-bridge fallback assertion. */
    private class RecordingForeground(private val context: TContext) : ContentBlobStore {
        val put = mutableListOf<String>()
        override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> {
            put += ref
            return TResult.Success.create(context, null)
        }
        override suspend fun getChunk(ref: String): TResult<TByteArray> =
            TResult.Failure.create(context, org.tenshu.core.GenericError.create(context, "unused"))
    }

    private fun tempDir(prefix: String): String {
        val dir = NSTemporaryDirectory() + prefix + "-" + Random.nextLong().toString(16)
        ensureDirectory(dir)
        return dir
    }

    /** A real [TContext] from an assembled runtime — the graph binds one for TResult construction. */
    private fun testContext(): TContext {
        val replica = NSTemporaryDirectory() + "p3b-ctx-" + Random.nextLong().toString(16)
        return DriveLocalRuntime.assemble(replica, InMemorySecureStorageDelegate()).koin.get<TContext>()
    }

    private fun manager(
        context: TContext,
        stagingDir: String,
        journal: BackgroundUploadJournal,
        bridge: () -> BackgroundUploadBridge?,
        foreground: ContentBlobStore = RecordingForeground(context),
        scope: CoroutineScope,
        auth: suspend () -> String? = { "jwt-token" },
    ) = BackgroundUploadManager(
        stagingDir = stagingDir,
        journal = journal,
        baseUrl = "https://relay.example",
        foreground = foreground,
        authTokenProvider = auth,
        entitlementTokenProvider = { null },
        entitlementProofProvider = { null },
        bridgeProvider = bridge,
        scope = scope,
        context = context,
    )

    @Test
    fun journalPersistsPendingRefsAcrossInstances() = runBlocking {
        val file = tempDir("p3b-journal") + "/journal"
        BackgroundUploadJournal(file).record("blob-aaa")
        BackgroundUploadJournal(file).record("blob-bbb")
        // A fresh instance (a "relaunch") loads the durable set from disk.
        val reloaded = BackgroundUploadJournal(file)
        assertEquals(setOf("blob-aaa", "blob-bbb"), reloaded.pendingRefs().toSet())
        reloaded.markComplete("blob-aaa")
        assertEquals(setOf("blob-bbb"), BackgroundUploadJournal(file).pendingRefs().toSet())
    }

    @Test
    fun stageAndEnqueueStagesTheCiphertextJournalsItAndEnqueuesTheRequest() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-staging")
        val journal = BackgroundUploadJournal("$staging/journal")
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, journal, { bridge }, scope = scope)
            val cipher = Random.nextBytes(1024)
            val result = mgr.stageAndEnqueue("blob-abc", cipher)

            assertTrue(result is TResult.Success, "stage+enqueue returns Success, got $result")
            assertTrue(readBytesFromFile("$staging/blob-abc")?.contentEquals(cipher) == true, "ciphertext staged byte-identical")
            assertEquals(listOf("blob-abc"), journal.pendingRefs(), "ref recorded in the durable journal")
            assertEquals(1, bridge.enqueued.size)
            val e = bridge.enqueued.single()
            assertEquals("blob-abc", e.ref)
            assertEquals("$staging/blob-abc", e.filePath)
            assertEquals("https://relay.example/sync/content/blob-abc", e.url)
            assertEquals("Bearer jwt-token", e.headers["Authorization"], "the device JWT is on the request")
            assertEquals("application/octet-stream", e.headers["Content-Type"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onCompletedSuccessDropsTheJournalEntryAndDeletesTheStagingFile() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-ack")
        val journal = BackgroundUploadJournal("$staging/journal")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, journal, { FakeBridge() }, scope = scope)
            mgr.stageAndEnqueue("blob-done", Random.nextBytes(64))
            assertEquals(listOf("blob-done"), journal.pendingRefs())

            mgr.onCompleted("blob-done", statusCode = 204, errorMessage = null)
            withTimeout(5_000) { while (journal.pendingRefs().isNotEmpty()) delay(20) }
            assertNull(readBytesFromFile("$staging/blob-done"), "spent staging file deleted on 2xx")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aStagedBlobCountsAsPresentUntilItIsAcknowledged() = runBlocking {
        // An unchanged overwrite skips its upload only on a present manifest. A manifest still in
        // nsurlsessiond is on its way, so asking the relay alone would re-stage the whole file.
        val ctx = testContext()
        val staging = tempDir("p3b-presence")
        val journal = BackgroundUploadJournal("$staging/journal")
        val scope = CoroutineScope(Dispatchers.Default)
        val asked = mutableListOf<String>()
        val foreground = object : ContentBlobStore {
            override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> = TResult.Success.create(ctx, null)
            override suspend fun getChunk(ref: String): TResult<TByteArray> =
                TResult.Failure.create(ctx, org.tenshu.core.GenericError.create(ctx, "unused"))
            override suspend fun hasChunk(ref: String): Boolean? {
                asked += ref
                return false
            }
        }
        try {
            val mgr = manager(ctx, staging, journal, { FakeBridge() }, foreground = foreground, scope = scope)
            val store = BackgroundUrlSessionContentBlobStore(foreground, mgr)
            store.putChunk("blob-manifest", Random.nextBytes(64))

            assertEquals(true, store.hasChunk("blob-manifest"), "staged and journalled reads as present")
            assertTrue(asked.isEmpty(), "a pending blob is answered locally, not by the relay")

            journal.markComplete("blob-manifest")
            assertEquals(false, store.hasChunk("blob-manifest"), "once acknowledged, the relay answers")
            assertEquals(listOf("blob-manifest"), asked)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onCompletedUnauthorizedReMintsAndReEnqueues() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-401")
        val journal = BackgroundUploadJournal("$staging/journal")
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            // A JWT that changes per call proves the re-enqueue re-mints rather than reusing.
            var mint = 0
            val mgr = manager(ctx, staging, journal, { bridge }, scope = scope, auth = { "jwt-${mint++}" })
            mgr.stageAndEnqueue("blob-401", Random.nextBytes(64))
            assertEquals(1, bridge.enqueued.size)
            assertEquals("Bearer jwt-0", bridge.enqueued[0].headers["Authorization"])

            mgr.onCompleted("blob-401", statusCode = 401, errorMessage = null)
            withTimeout(5_000) { while (bridge.enqueued.size < 2) delay(20) }
            assertEquals("Bearer jwt-1", bridge.enqueued[1].headers["Authorization"], "re-enqueue mints a fresh token")
            assertEquals(listOf("blob-401"), journal.pendingRefs(), "still pending until an ack")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noBridgeFallsBackToForegroundUploadWithoutStaging() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-fallback")
        val journal = BackgroundUploadJournal("$staging/journal")
        val foreground = RecordingForeground(ctx)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, journal, bridge = { null }, foreground = foreground, scope = scope)
            val result = mgr.stageAndEnqueue("blob-fg", Random.nextBytes(64))
            assertTrue(result is TResult.Success)
            assertEquals(listOf("blob-fg"), foreground.put, "uploaded through the foreground store")
            assertNull(readBytesFromFile("$staging/blob-fg"), "no bridge → nothing staged")
            assertTrue(journal.pendingRefs().isEmpty(), "no bridge → nothing journaled")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resumeReEnqueuesChunksStagedBeforeATermination() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-resume")
        val journalFile = "$staging/journal"
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            // First "run": stage two chunks through a bridge, then the process dies.
            val before = manager(ctx, staging, BackgroundUploadJournal(journalFile), { FakeBridge() }, scope = scope)
            before.stageAndEnqueue("blob-r1", Random.nextBytes(32))
            before.stageAndEnqueue("blob-r2", Random.nextBytes(32))

            // "Relaunch": a fresh manager + journal over the SAME staging dir + a fresh bridge.
            val freshBridge = FakeBridge()
            val after = manager(ctx, staging, BackgroundUploadJournal(journalFile), { freshBridge }, scope = scope)
            after.resumeIncomplete()
            withTimeout(5_000) { while (freshBridge.enqueued.size < 2) delay(20) }
            assertEquals(setOf("blob-r1", "blob-r2"), freshBridge.enqueued.map { it.ref }.toSet(), "both staged chunks re-enqueued")
        } finally {
            scope.cancel()
        }
    }

    // ---- Transfers in flight (`ios-sync-activity-indicator.md` §6a.1) ------------------------

    @Test
    fun aHandOffIsInFlight_andASuccessfulCompletionEndsIt() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-ok")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, BackgroundUploadJournal("$staging/journal"), { FakeBridge() }, scope = scope)
            assertEquals(0, mgr.inFlight.current)
            mgr.stageAndEnqueue("blob-1", Random.nextBytes(64))
            mgr.stageAndEnqueue("blob-2", Random.nextBytes(64))
            assertEquals(2, mgr.inFlight.current)

            mgr.onCompleted("blob-1", statusCode = 200, errorMessage = null)
            assertEquals(1, mgr.inFlight.current, "counted down at once, not when the journal work runs")
            mgr.onCompleted("blob-2", statusCode = 204, errorMessage = null)
            assertEquals(0, mgr.inFlight.current)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedTransferIsNoLongerInFlight_thoughItStaysJournalled() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-fail")
        val journal = BackgroundUploadJournal("$staging/journal")
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, journal, { bridge }, scope = scope)
            mgr.stageAndEnqueue("blob-net", Random.nextBytes(64))
            mgr.stageAndEnqueue("blob-500", Random.nextBytes(64))

            mgr.onCompleted("blob-net", statusCode = -1, errorMessage = "The network connection was lost.")
            mgr.onCompleted("blob-500", statusCode = 500, errorMessage = null)

            assertEquals(0, mgr.inFlight.current, "nothing is running")
            delay(300)
            assertEquals(setOf("blob-net", "blob-500"), journal.pendingRefs().toSet(), "both wait for a later resume")
            assertEquals(2, bridge.enqueued.size, "and nothing re-enqueued them")
            assertEquals(0, mgr.inFlight.current)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReEnqueueAfter401_isInFlightAgain() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-401")
        val bridge = FakeBridge()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, BackgroundUploadJournal("$staging/journal"), { bridge }, scope = scope)
            mgr.stageAndEnqueue("blob-401", Random.nextBytes(64))
            mgr.onCompleted("blob-401", statusCode = 401, errorMessage = null)
            withTimeout(5_000) { while (bridge.enqueued.size < 2) delay(20) }
            awaitInFlight(mgr, 1)

            mgr.onCompleted("blob-401", statusCode = 200, errorMessage = null)
            assertEquals(0, mgr.inFlight.current)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCompletionForATransferThisProcessNeverCounted_isIgnored() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-stray")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, BackgroundUploadJournal("$staging/journal"), { FakeBridge() }, scope = scope)
            mgr.stageAndEnqueue("blob-live", Random.nextBytes(64))
            mgr.onCompleted("blob-from-an-earlier-launch", statusCode = 200, errorMessage = null)
            assertEquals(1, mgr.inFlight.current, "the live transfer is still counted")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theFirstResume_seedsFromTheSessionsLiveTasks_once_beforeReEnqueueing() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-seed")
        val journalFile = "$staging/journal"
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            // An earlier run staged one chunk; `nsurlsessiond` still holds it and one more whose
            // staging file this launch no longer needs to re-enqueue.
            manager(ctx, staging, BackgroundUploadJournal(journalFile), { FakeBridge() }, scope = scope)
                .stageAndEnqueue("blob-staged", Random.nextBytes(32))
            val bridge = FakeBridge().apply { live = listOf("blob-staged", "blob-other") }
            val mgr = manager(ctx, staging, BackgroundUploadJournal(journalFile), { bridge }, scope = scope)

            mgr.resumeIncomplete()
            withTimeout(5_000) { while (bridge.enqueued.isEmpty()) delay(20) }
            // Two the session held, plus the resume's own re-enqueue of the staged one.
            awaitInFlight(mgr, 3)

            mgr.resumeIncomplete()
            withTimeout(5_000) { while (bridge.enqueued.size < 2) delay(20) }
            awaitInFlight(mgr, 4)
            assertEquals(1, bridge.asked, "the session is asked once per process")

            repeat(3) { mgr.onCompleted("blob-staged", statusCode = 200, errorMessage = null) }
            mgr.onCompleted("blob-other", statusCode = 200, errorMessage = null)
            assertEquals(0, mgr.inFlight.current)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTransferThatCompletesWhileTheSessionIsBeingAsked_isNotSeededAsLive() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-seed-race")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val bridge = FakeBridge().apply { live = listOf("blob-a", "blob-b") }
            val mgr = manager(ctx, staging, BackgroundUploadJournal("$staging/journal"), { bridge }, scope = scope)
            bridge.beforeAnswering = { mgr.onCompleted("blob-a", statusCode = 200, errorMessage = null) }

            mgr.resumeIncomplete()
            withTimeout(5_000) { while (bridge.asked == 0) delay(20) }
            awaitInFlight(mgr, 1)
            delay(200)
            assertEquals(1, mgr.inFlight.current, "only blob-b: blob-a will never be reported again")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noBridge_nothingIsInFlight() = runBlocking {
        val ctx = testContext()
        val staging = tempDir("p3b-inflight-nobridge")
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val mgr = manager(ctx, staging, BackgroundUploadJournal("$staging/journal"), bridge = { null }, scope = scope)
            mgr.stageAndEnqueue("blob-fg", Random.nextBytes(64))
            assertEquals(0, mgr.inFlight.current, "the foreground store returned when the relay answered")
        } finally {
            scope.cancel()
        }
    }
}
