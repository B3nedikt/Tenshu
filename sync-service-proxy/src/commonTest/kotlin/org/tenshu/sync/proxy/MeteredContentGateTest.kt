package org.tenshu.sync.proxy

import org.tenshu.core.entity.getDataIdHash
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metered **content** gate (`sync-metered-plane-split.md` §4) — the plane the `WifiOnly`
 * policy actually applies to after the split, and the durable journal that lets an upload wait
 * for Wi-Fi across days and reboots.
 *
 * Drives [SyncObserver] directly (rather than through the scheduler) because these are claims
 * about the emit path itself; the scheduler-side split is pinned in [SyncSchedulerGateTest].
 */
class MeteredContentGateTest {

    private lateinit var proxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var fileDatabase: InMemoryFileDatabase
    private lateinit var contentSink: InMemoryContentBlobStore

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")
        fileDatabase = InMemoryFileDatabase()
        contentSink = InMemoryContentBlobStore(proxy.tContext)
    }

    /**
     * A fresh observer over the **same** execution context. Constructing a second one is how the
     * durability claim is tested: the in-memory hold queue dies with the instance, the journal
     * does not.
     */
    private fun observer(gateOpen: Boolean): SyncObserver = SyncObserver(
        executionContext = proxy.executionContext,
        syncManager = syncManager,
        cryptoManager = proxy.tContext.syncCryptoManager(),
        tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        contentBlobStore = contentSink,
        fileDatabase = fileDatabase,
        contentGate = SyncGate { gateOpen },
    )

    private fun entityId(path: String): DataId = DataId.StringDataId.create(
        context = proxy.tContext,
        type = SyntheticTestData.ID,
        keyValue = path,
    )

    private suspend fun SyncObserver.emit(path: String, bytes: Int = 64) {
        fileDatabase.writeRawByteArray(path, ByteArray(bytes) { it.toByte() })
        emitFileContent(
            targetEntityId = entityId(path),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = path,
        )
    }

    private fun journal(): List<PendingContentEmit> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = PendingContentEmit.ID,
                relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
            ),
            isEntity = false,
            elementSchemaId = PendingContentEmit.ID,
        )
        return proxy.executionContext.database.getArrayData(ref)
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            ?: emptyList()
    }

    @Test
    fun clearingAnIntentDeletesItsRow_notJustItsArrayMembership() = runTest {
        // ⚠⚠ The leak this pins is invisible by construction: the drain, the banner and
        // `deferredContentFigures()` all read the ARRAY, so a row left behind after a clear
        // reports as nothing pending and accumulates silently in the store.
        //
        // ⚠ Worse than untidy — the orphans read exactly like STUCK UPLOADS. On a real phone
        // (2026-08-29) 8 of them sat beside two photos still staged in `files/`, and the obvious
        // reading was "these never uploaded". They had: the bytes were on the relay all along.
        // A state that mimics data loss costs a full diagnosis every time someone finds it.
        val path = "/photo.jpg"
        observer(gateOpen = true).emit(path)

        assertTrue(journal().isEmpty(), "an open gate uploads immediately, clearing the intent")

        val entryId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = PendingContentEmit.ID,
            keyValue = PendingContentEmit.keyOf(
                targetEntityIdHash = entityId(path).getDataIdHash().toString(),
                propertyId = "${SyntheticTestData.ID}/data",
            ),
        )
        assertNull(
            proxy.executionContext.database.getData(entryId),
            "a cleared intent must leave NO PendingContentEmit row behind",
        )
    }

    @Test
    fun closedGate_journalsTheIntentInsteadOfUploading() = runTest {
        observer(gateOpen = false).emit("/photo.jpg")

        assertEquals(0, contentSink.putCount, "a metered network must not upload file bytes")
        assertEquals(1, journal().size, "the emit is parked as a durable intent")
        assertEquals(
            "/photo.jpg",
            journal().single().filePath.get(),
            "the intent records the path to read the bytes back from",
        )
    }

    @Test
    fun openGate_uploadsImmediatelyAndClearsItsIntent() = runTest {
        observer(gateOpen = true).emit("/photo.jpg")

        assertTrue(contentSink.putCount > 0, "an unmetered network uploads inline, as before")
        assertEquals(
            0,
            journal().size,
            "the intent is written first (P4) but cleared once the bytes land, so a settled " +
                "device shows an empty journal",
        )
    }

    /**
     * P4's headline: the intent is durable **before** any byte moves, so an upload that dies
     * half-way is finishable. Before this, a crash after `UploadFile` returned left the file
     * local-only with no record it was ever meant to sync — nothing retried, ever.
     */
    @Test
    fun aCrashMidUploadLeavesAnIntentTheNextDrainFinishes() = runTest {
        // A store that refuses every PUT stands in for the process dying mid-upload: the emit does
        // not complete, so nothing clears the intent.
        contentSink.failPuts = true
        observer(gateOpen = true).emit("/photo.jpg")

        assertEquals(0, contentSink.chunks.size, "sanity: nothing landed")
        assertEquals(
            1,
            journal().size,
            "the intent survives a failed/interrupted upload — this is what nothing recorded before",
        )

        // "Next process start": the store works again and the drain finishes the job.
        contentSink.failPuts = false
        val remaining = observer(gateOpen = true).drainDeferredContent()

        assertTrue(contentSink.putCount > 0, "the interrupted upload is finished by the drain")
        assertEquals(0, remaining, "and its intent is cleared")
        assertEquals(0, journal().size)
    }

    @Test
    fun aFailedUploadIsRetriedRatherThanStrandedOnItsCaller() = runTest {
        // A failed chunk PUT aborts the emit. It used to rely on the OP'S CALLER retrying the whole
        // operation; now the journal owns the retry, so a transient relay failure self-heals.
        contentSink.failPuts = true
        val o = observer(gateOpen = true)
        o.emit("/a.jpg")
        o.emit("/b.jpg")

        assertEquals(2, journal().size, "both failed uploads are still recorded")

        contentSink.failPuts = false
        assertEquals(0, observer(gateOpen = true).drainDeferredContent(), "both retried to success")
    }

    @Test
    fun drainOnceTheGateOpens_uploadsAndClearsTheJournal() = runTest {
        observer(gateOpen = false).emit("/photo.jpg")
        assertEquals(0, contentSink.putCount, "sanity: deferred")

        val remaining = observer(gateOpen = true).drainDeferredContent()

        assertTrue(contentSink.putCount > 0, "reaching Wi-Fi uploads the deferred bytes")
        assertEquals(0, remaining, "nothing left waiting")
        assertEquals(0, journal().size, "the intent is cleared once its bytes land")
    }

    /**
     * The reason the journal is durable rather than an in-memory channel: a Wi-Fi wait spans
     * process death, unlike the locked-realm hold it generalises. A second observer instance
     * stands in for the next process.
     */
    @Test
    fun theJournalSurvivesARestart() = runTest {
        observer(gateOpen = false).emit("/photo.jpg")

        // A brand-new observer — no in-memory state carried over — still finds the work.
        val revived = observer(gateOpen = true)
        assertEquals(1, journal().size, "the intent outlived the observer that wrote it")
        revived.drainDeferredContent()

        assertTrue(contentSink.putCount > 0, "a restarted process still uploads the deferred file")
        assertEquals(0, journal().size, "and clears the intent")
    }

    @Test
    fun forceReplaysPastAStillClosedGate() = runTest {
        val o = observer(gateOpen = false)
        o.emit("/photo.jpg")

        // An unforced drain under a still-closed gate reports the file as waiting and uploads
        // nothing — replaying would only re-journal what is already journalled.
        assertEquals(1, o.drainDeferredContent(force = false), "still waiting on Wi-Fi")
        assertEquals(0, contentSink.putCount, "an unforced drain respects the closed gate")

        o.drainDeferredContent(force = true) // the user's "upload over cellular" override
        assertTrue(contentSink.putCount > 0, "the override uploads despite the closed gate")
        assertEquals(0, journal().size, "and clears the intent")
    }

    /**
     * D5: re-uploading the same file before the drain must replace its intent, not queue a second
     * emit — otherwise every save of a file on a long cellular stretch grows the journal.
     */
    @Test
    fun reEmittingTheSameFileCoalescesToOneIntent() = runTest {
        val o = observer(gateOpen = false)
        o.emit("/photo.jpg", bytes = 64)
        o.emit("/photo.jpg", bytes = 128)
        o.emit("/photo.jpg", bytes = 256)

        assertEquals(1, journal().size, "last-wins per (entity, property)")
    }

    /**
     * ⚠ The limit of P4 as shipped: an inline (gate-open) upload journals, uploads and clears
     * *within one call*, so the queue never holds more than the file in flight. A twelve-file
     * folder upload on Wi-Fi therefore reports "1 of 1" twelve times, not "3 of 12" — the banner
     * shows that something is uploading, not how far through the batch it is.
     *
     * Getting the batch denominator means letting the emits accumulate and be drained as one pass,
     * which changes `UploadFile`'s contract (it would return before the bytes are up). That is
     * deliberately left to P4b — see `drive-upload-progress.md` §10.
     */
    @Test
    fun inlineUploadsDoNotAccumulateIntoABatch() = runTest {
        val o = observer(gateOpen = true)
        o.emit("/a.jpg")
        o.emit("/b.jpg")
        o.emit("/c.jpg")

        assertEquals(
            0,
            journal().size,
            "each inline upload clears its own intent, so no batch is ever visible to the banner",
        )
    }

    @Test
    fun theDrainReportsProgressPerFile() = runTest {
        // The figures behind "Uploading 3 of 12" (`drive-upload-progress.md` P2).
        val o = observer(gateOpen = false)
        o.emit("/a.jpg")
        o.emit("/b.jpg")
        o.emit("/c.jpg")

        val seen = mutableListOf<Pair<Int, Int>>()
        observer(gateOpen = true).drainDeferredContent { completed, total -> seen += completed to total }

        assertEquals(
            listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3),
            seen,
            "one callback before the first upload (so the denominator shows immediately), " +
                "then one per completion",
        )
    }

    @Test
    fun theDenominatorIsFixedForThePass() = runTest {
        // Entries added mid-pass belong to the NEXT pass; the total a pass reports must not move
        // under the user, which is the whole reason `passTotal` exists alongside the queue size.
        val o = observer(gateOpen = false)
        o.emit("/a.jpg")
        o.emit("/b.jpg")

        val totals = mutableSetOf<Int>()
        observer(gateOpen = true).drainDeferredContent { _, total -> totals += total }

        assertEquals(setOf(2), totals, "one denominator for the whole pass")
    }

    @Test
    fun aDroppedDeadIntentStillCountsAsProgress() = runTest {
        // A bar that stalls on an entry which can never succeed is worse than one that moves past
        // it — the file is gone, so leaving the queue IS the outcome.
        val o = observer(gateOpen = false)
        o.emit("/gone.jpg")
        o.emit("/live.jpg")
        fileDatabase.deleteFile("/gone.jpg")

        val seen = mutableListOf<Int>()
        observer(gateOpen = true).drainDeferredContent { completed, _ -> seen += completed }

        assertEquals(listOf(0, 1, 2), seen, "both entries counted, dead one included")
    }

    @Test
    fun distinctFilesEachGetTheirOwnIntent() = runTest {
        val o = observer(gateOpen = false)
        o.emit("/a.jpg")
        o.emit("/b.jpg")

        assertEquals(2, journal().size, "coalescing is per file, not global")
    }

    /**
     * Reported on a phone (2026-10-30): one uploaded photo read "Waiting for Wi-Fi — 5 files",
     * because it parks its data AND four thumbnail rungs, one journal entry each. The banner counts
     * files, so the figure is distinct holders; the bytes still cover every entry, since the
     * thumbnails cross the metered link too.
     */
    @Test
    fun aFileWithThumbnailRungsCountsAsOneFile() = runTest {
        val o = observer(gateOpen = false)
        o.emitWithThumbnailRungs("/photo.jpg", bytes = 100, rungBytes = 10)
        o.emit("/other.jpg", bytes = 50)

        assertEquals(6, journal().size, "one intent per (content, property)")
        val figures = o.deferredContentFigures()
        assertEquals(2, figures.count, "the photo and its rungs are ONE file")
        assertEquals(100L + 4 * 10 + 50, figures.bytes, "every parked byte still counts")
    }

    /**
     * The drain's "Uploading N of M" is in the same unit as the waiting banner. Counting entries
     * here made one photo read "Waiting for Wi-Fi. 1 file", then "Uploading 2 of 5".
     */
    @Test
    fun theDrainCountsAPhotoAndItsRungsAsOneFile() = runTest {
        val o = observer(gateOpen = false)
        o.emitWithThumbnailRungs("/photo.jpg")
        o.emit("/other.jpg")

        val seen = mutableListOf<Pair<Int, Int>>()
        observer(gateOpen = true).drainDeferredContent { completed, total -> seen += completed to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen, "progress moves once per FILE")
        assertTrue(journal().isEmpty(), "every entry, rungs included, was uploaded")
    }

    /** One photo as the upload path parks it: its data plus four thumbnail rungs, same holder. */
    private suspend fun SyncObserver.emitWithThumbnailRungs(path: String, bytes: Int = 64, rungBytes: Int = 16) {
        emit(path, bytes)
        listOf("thumbnail", "thumbnailLarge", "thumbnailXLarge", "thumbnailXXLarge").forEach { rung ->
            val rungPath = "/.thumbnails$path/$rung"
            fileDatabase.writeRawByteArray(rungPath, ByteArray(rungBytes))
            emitFileContent(
                targetEntityId = entityId(path),
                propertyId = "${SyntheticTestData.ID}/$rung",
                filePath = rungPath,
            )
        }
    }

    /**
     * D6: an intent whose file is gone can never succeed — retrying it forever would wedge the
     * drain behind a permanent failure. Its tombstone already reached peers on the ungated
     * metadata plane, so dropping it loses nothing.
     */
    @Test
    fun anIntentForADeletedFileIsDropped() = runTest {
        observer(gateOpen = false).emit("/gone.jpg")
        fileDatabase.deleteFile("/gone.jpg")

        val remaining = observer(gateOpen = true).drainDeferredContent()

        assertEquals(0, remaining, "a file that no longer exists stops being retried")
        assertEquals(0, journal().size, "and its intent is cleared")
        assertEquals(0, contentSink.putCount, "nothing was uploaded for it")
    }

    /**
     * The drain is resilient per entry: one dead intent must not stop a live one behind it from
     * uploading (the ordering hazard a `return`-on-failure loop would introduce).
     */
    @Test
    fun aDeadIntentDoesNotBlockALiveOneBehindIt() = runTest {
        val o = observer(gateOpen = false)
        o.emit("/gone.jpg")
        o.emit("/live.jpg")
        fileDatabase.deleteFile("/gone.jpg")

        observer(gateOpen = true).drainDeferredContent()

        assertTrue(contentSink.putCount > 0, "the surviving file still uploaded")
        assertEquals(0, journal().size, "both intents resolved — one uploaded, one dropped")
    }
}
