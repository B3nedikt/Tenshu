package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the content drain does when the relay says the owner is **out of space** (`507`).
 *
 * ## The bug these pin
 *
 * Measured on a real phone 2026-09-06. The realm had **836 bytes** free on a 5 GiB tier — not
 * "full" by any client-side test, but far too little for a 2 MiB chunk — so the relay refused every
 * content `PUT` with `507 over_quota`. Nothing recognised that answer as final:
 *
 *  - `RestContentBlobStore.putChunk` tagged only `402`, so the failure looked *transient*;
 *  - `ChunkPutFailed` was constructed without the transport's reason, so even a tagged `507`
 *    was stripped before any caller could classify it; and
 *  - the drain therefore re-attempted its whole journal every tick.
 *
 * Result: **606 journalled emits, ~30 rejected `PUT`s per second, sustained for 80+ minutes**,
 * Control pinned at 106% CPU, and a banner reading "Uploading 297 of 606 · 31.8 Mb left" the entire
 * time. The 176,000 log lines it produced also evicted every other Tenshu line from logcat.
 *
 * ⚠ These are claims about *classification and bounding*, not about the relay: the fake answers
 * `507` directly ([InMemoryContentBlobStore.failPutsOverQuota]). The relay's own half — that
 * `over_quota` is what it returns — lives in `ContentRoutingTest`.
 */
class OverQuotaContentDrainTest {

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

    private fun observer(gateOpen: Boolean = true): SyncObserver = SyncObserver(
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

    /** Journal [count] files behind a closed gate, then open it for the drain. */
    private suspend fun journalFiles(count: Int): SyncObserver {
        val closed = observer(gateOpen = false)
        repeat(count) { closed.emit("/photo-$it.jpg") }
        assertEquals(count, journal().size, "precondition: every file is journalled")
        return observer(gateOpen = true)
    }

    @Test
    fun aDrainStopsAtTheFirstOverQuotaRejection_insteadOfAttemptingEveryQueuedFile() = runTest {
        val observer = journalFiles(count = 8)
        contentSink.failPutsOverQuota = true

        observer.drainDeferredContent()

        // ⚠⚠ THE claim. Every entry in the queue meets the same quota check, so once the relay has
        // refused one there is nothing to learn from asking about the other seven — and asking is
        // not free: the relay reads the whole body before it checks quota, so each doomed attempt
        // uploads a full chunk. On the measured device this was 606 attempts per pass, ~30/second,
        // for 80+ minutes.
        assertEquals(
            1,
            contentSink.attemptedRefs.size,
            "a 507 must end the pass — every further attempt is a chunk uploaded to be refused",
        )
    }

    @Test
    fun everyRejectedEntryStaysJournalled_soNothingIsLostWhenSpaceIsFreed() = runTest {
        val observer = journalFiles(count = 8)
        contentSink.failPutsOverQuota = true

        observer.drainDeferredContent()

        // ⚠ Stopping the pass must not be confused with abandoning the work. These files are
        // perfectly valid and go out as soon as there is room; dropping them would turn a
        // recoverable quota problem into data that never syncs.
        assertEquals(8, journal().size, "an over-quota pass drops nothing")
    }

    @Test
    fun theRejectionIsLatchedForTheScheduler_soItCanBackOffRatherThanRetryNextTick() = runTest {
        val observer = journalFiles(count = 3)
        contentSink.failPutsOverQuota = true

        observer.drainDeferredContent()

        // The per-pass stop above bounds ONE pass. Without this latch reaching the scheduler, the
        // next tick a second later starts another one — which is how a bounded pass still adds up
        // to an unbounded storm.
        assertTrue(
            observer.contentStorageRejected,
            "the drain must tell the scheduler the relay refused for space",
        )
    }

    @Test
    fun aTransientFailureIsNotTreatedAsOverQuota_soOrdinaryRetriesStillHappen() = runTest {
        val observer = journalFiles(count = 5)
        // A relay that is down, not full: the journal SHOULD keep trying this one.
        contentSink.failPuts = true

        observer.drainDeferredContent()

        assertEquals(
            5,
            contentSink.attemptedRefs.size,
            "a transient failure must not short-circuit the pass — only a 507 is doomed",
        )
        assertFalse(
            observer.contentStorageRejected,
            "a generic transport failure must not arm the storage backoff",
        )
    }

    @Test
    fun theLatchClearsOnAPassThatSucceeds_soFreeingSpaceResumesUploads() = runTest {
        val observer = journalFiles(count = 3)
        contentSink.failPutsOverQuota = true
        observer.drainDeferredContent()
        assertTrue(observer.contentStorageRejected, "precondition: the latch is set")

        // The owner freed bytes / upgraded. Recovery must be automatic — a latch that only ever
        // sets would leave the device permanently refusing to upload after one bad tick.
        contentSink.failPutsOverQuota = false
        observer.drainDeferredContent()

        assertFalse(observer.contentStorageRejected, "a clean pass clears the latch")
        assertTrue(journal().isEmpty(), "and the queue drains once there is room")
    }

    @Test
    fun aLaterInlineUploadDoesNotRetryWhileTheRelayIsStillRefusing() = runTest {
        // ⚠⚠ Measured on the phone 2026-09-06 *after* the scheduler's backoff was in place: the
        // storm was gone but ~10 doomed PUTs/minute remained, one per `tenshu-scheduled-op` — the
        // Synced Folders backlog going through the INLINE emit path, which the scheduler's backoff
        // never sees. Bounding the drain alone stops the storm and leaves the drip.
        val observer = observer(gateOpen = true)
        contentSink.failPutsOverQuota = true
        observer.emit("/first.jpg")
        assertEquals(1, contentSink.attemptedRefs.size, "precondition: the first upload tries once")

        observer.emit("/second.jpg")
        observer.emit("/third.jpg")

        assertEquals(
            1,
            contentSink.attemptedRefs.size,
            "an upload started while the relay is refusing must not put another chunk on the wire",
        )
        // ⚠ Refused ≠ lost. All three are journalled and go out when there is room — the file is on
        // disk and the intent is durable, which is the whole point of journalling before uploading.
        assertEquals(3, journal().size, "every intent is still queued")
    }

    @Test
    fun anAcceptedWriteClearsTheStickyLatch_soUploadsResumeWithoutARestart() = runTest {
        val observer = observer(gateOpen = true)
        contentSink.failPutsOverQuota = true
        observer.emit("/first.jpg")

        // The owner freed bytes. ⚠ Only evidence of an *accepted* write may clear the latch — if it
        // reset on a timer or at pass start, a still-full device would re-open the storm every tick.
        contentSink.failPutsOverQuota = false
        observer.drainDeferredContent()

        assertFalse(observer.contentStorageRejected, "a landed chunk clears the latch")
        assertTrue(journal().isEmpty(), "and the backlog goes out")

        observer.emit("/second.jpg")
        assertTrue(journal().isEmpty(), "later inline uploads work normally again")
    }

    @Test
    fun theFailureCarriesTheRelaysReason_notJustTheChunkItFailedOn() = runTest {
        // ⚠⚠ The diagnostic half, and it is not cosmetic: callers *classify* on this text. When
        // `ChunkPutFailed` was built without it, the marker was stripped at the throw site, so the
        // 507 became indistinguishable from a network blip — and the phone logged the same
        // reasonless line 176,000 times while nobody could tell why.
        val failure = ChunkPutFailed(
            message = "SyncObserver -> file-content chunk PUT failed (file=1, chunk=0/1)",
            reason = "$STORAGE_REJECTION_MARKER: content PUT rejected (507 Insufficient Storage)",
        )

        assertTrue(failure.isStorageRejection(), "the marker must survive onto the exception")
        assertTrue(
            failure.message?.contains("507") == true,
            "and the logged line must name the cause, not only the chunk",
        )
    }

    @Test
    fun consumeStorageRefusalIsAnEdge_readOnceThenGone_whileTheStickyLevelStays() = runTest {
        // ⚠⚠ The distinction the scheduler's classification depends on, and the one the wedge came
        // from reading the wrong side of. `contentStorageRejected` is a LEVEL — "the relay was full
        // when we last heard", cleared only by an accepted write. `consumeStorageRefusal` is an
        // EDGE — "the relay refused us since you last asked". A tick that attempts nothing produces
        // no edge, which is what lets it clear; reading the level there re-arms the backoff for
        // ever and wedges the device with bytes free.
        val observer = observer(gateOpen = true)
        contentSink.failPutsOverQuota = true
        observer.emit("/first.jpg")

        assertTrue(observer.contentStorageRejected, "precondition: the level is set")
        assertTrue(observer.consumeStorageRefusal(), "the refusal is reported once")
        assertFalse(
            observer.consumeStorageRefusal(),
            "and only once — a second read sees no NEW refusal, which is what makes a quiet pass " +
                "clear the backoff instead of re-arming it",
        )
        assertTrue(
            observer.contentStorageRejected,
            "while the sticky level is untouched by the read: it still gates the inline upload " +
                "path between passes, which is the job it exists for",
        )
    }

    @Test
    fun aFurtherRefusalRaisesTheEdgeAgain_soAStillFullRelayKeepsBackingOff() = runTest {
        // ⚠ Driven through the DRAIN, not another inline emit. Once the sticky level is set the
        // inline path returns before touching the network (`SyncObserver`'s "does not make the
        // upload fail" guard), so it can raise no edge — which is exactly why the probing attempt
        // has to come from a drain pass the backoff has permitted.
        val observer = journalFiles(count = 2)
        contentSink.failPutsOverQuota = true
        observer.drainDeferredContent()
        assertTrue(observer.consumeStorageRefusal(), "precondition: the first pass was refused")

        observer.drainDeferredContent()

        assertTrue(
            observer.consumeStorageRefusal(),
            "a device that is genuinely still full raises the edge on every refused pass, so the " +
                "backoff keeps re-arming — the fix narrows the classification, it does not remove it",
        )
    }

}
