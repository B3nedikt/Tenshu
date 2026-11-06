package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `SyncObserver.ensureContentOnRelay`, the relay half of an unchanged overwrite
 * (`UploadFileImplementation`): it keeps the file's contentId, so it may skip the upload only when the
 * relay is known to hold that content's manifest.
 *
 * ⚠⚠ The branch that matters most is the unknown one. An unchanged save is a way a device holding
 * the bytes puts back content the relay lost, so an unreachable relay must hand off, never read as
 * healthy.
 */
class EnsureContentOnRelayTest {

    private val contentId = "cid-ensure-0123456789"
    private val path = "/kept.jpg"
    private val bytes = ByteArray(5_000) { (it and 0xFF).toByte() }
    private val dataPropertyId: String = REPAIRABLE_PROPERTY_IDS.first()

    private class FixedContentAddress(private val contentId: String, private val contentHash: String) : ExternalPropertyWriter {
        override fun supports(propertyId: String): Boolean = true
        override fun write(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String, value: Data) = Unit
        override fun resolveContentId(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String): String = contentId
        override fun resolveContentHash(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String): String = contentHash
    }

    private class Fixture(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val observer: SyncObserver,
        val sink: InMemoryContentBlobStore,
        val entityId: DataId,
        /** Non-null only for `fixture(declareCancels = true)`. */
        val declares: CancelOnDeclare? = null,
    )

    /** The chunk refs the manifest currently stored for [contentId] names. */
    private fun chunkRefsOf(f: Fixture): List<String> {
        val cipher = f.sink.chunks[manifestContentRef(contentId)] ?: return emptyList()
        val parser = BinaryParser(f.proxy.tContext.schemaProvider(), f.proxy.tContext.dataFactory())
        val plain = f.proxy.tContext.syncCryptoManager().decrypt(cipher, f.syncManager.getDerivedKeys()!!.sharedIdentityKey)
        val manifest = parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes) as FileContentManifestPayload
        return manifest.chunks.toList().map { it.ref.get() }
    }

    private fun fixture(
        contentGate: SyncGate = SyncGate.AlwaysAllow,
        declareCancels: Boolean = false,
    ): Fixture {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to FixedContentAddress(contentId, wholeFileHashOf(bytes))),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val sink = InMemoryContentBlobStore(proxy.tContext)
        val declares = if (declareCancels) CancelOnDeclare(sink) else null
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
            contentBlobStore = declares ?: sink,
            fileDatabase = InMemoryFileDatabase().apply { writeRawByteArray(path, bytes) },
            contentGate = contentGate,
        )
        return Fixture(
            proxy,
            syncManager,
            observer,
            sink,
            DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path),
            declares,
        )
    }

    /**
     * ⚠⚠ The backfill hands over an id its walk read earlier. Asked through the
     * plain ensure about an id the holder no longer names, the relay's "not here" force-emits what
     * the holder names NOW. The named variant compares against the target it would upload.
     */
    @Test
    fun aHolderThatNamesOtherContentNow_sendsNothing_andOneThatStillNamesIt_isEnsured() = runTest {
        val f = fixture()

        assertEquals(
            NamedContentEnsure.MOVED_ON,
            f.observer.ensureNamedContentOnRelay(f.entityId, dataPropertyId, path, "cid-the-walk-read-earlier"),
        )
        assertEquals(0, f.sink.putCount, "nothing is uploaded for content the holder no longer names")

        assertEquals(
            NamedContentEnsure.ACTED,
            f.observer.ensureNamedContentOnRelay(f.entityId, dataPropertyId, path, contentId),
        )
        assertNotNull(f.sink.chunks[manifestContentRef(contentId)], "the relay had nothing, so it was emitted")
    }

    @Test
    fun contentTheRelayHolds_isNotSentAgain() = runTest {
        val f = fixture()
        assertTrue(f.observer.emitFileContent(f.entityId, dataPropertyId, path))
        val puts = f.sink.putCount

        assertTrue(f.observer.ensureContentOnRelay(f.entityId, dataPropertyId, path, contentId))

        assertEquals(chunkRefsOf(f).toSet(), f.sink.presenceChecks.toSet(), "every chunk the manifest names is checked")
        assertEquals(puts, f.sink.putCount, "nothing is uploaded when the relay already holds the content")
    }

    @Test
    fun aManifestWhoseChunkIsGone_isRepairedRatherThanTrusted() = runTest {
        // Reclamation deletes chunks before the manifest, so a partial delete leaves a manifest that
        // resolves and a file no peer can assemble.
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        val lost = chunkRefsOf(f).first()
        f.sink.chunks.remove(lost)
        val puts = f.sink.putCount

        assertFalse(f.observer.ensureContentOnRelay(f.entityId, dataPropertyId, path, contentId))

        assertTrue(f.sink.putCount > puts, "a missing chunk must hand the content off")
        for (ref in chunkRefsOf(f)) {
            assertNotNull(f.sink.chunks[ref], "the manifest now stored names a chunk that is missing: $ref")
        }
    }

    @Test
    fun contentTheRelayLost_isReEmittedAtTheSameManifestRef() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        f.sink.chunks.clear()

        assertFalse(f.observer.ensureContentOnRelay(f.entityId, dataPropertyId, path, contentId))

        assertNotNull(f.sink.chunks[manifestContentRef(contentId)], "the manifest is back where every peer looks for it")
    }

    @Test
    fun queuedBytesFor_areTheJournalledEntrysFileSize_andZeroWithoutOne() = runTest {
        val f = fixture(contentGate = SyncGate { false })
        assertEquals(0L, f.observer.queuedContentBytesFor(f.entityId, dataPropertyId), "nothing journalled yet")

        assertFalse(f.observer.emitFileContent(f.entityId, dataPropertyId, path), "a closed gate journals and stops")

        assertEquals(bytes.size.toLong(), f.observer.queuedContentBytesFor(f.entityId, dataPropertyId))
        assertEquals(f.observer.deferredContentFigures().bytes, f.observer.queuedContentBytesFor(f.entityId, dataPropertyId),
            "one entry, so it is the whole queued figure the quota gate subtracts from")
    }

    // ── The tri-state behind it (`drive-xxlarge-thumbnail-rung.md` §4.7) ──
    //
    // ⚠⚠ `ensureContentOnRelay` collapses ABSENT and UNKNOWN (handing bytes off is safe either way).
    // A caller that *deletes* on absence — the keeper's eviction sentinel — cannot, so the two are
    // distinguished here and every no-answer path says UNKNOWN.

    @Test
    fun resolvability_isRESOLVABLE_whenTheManifestAndEveryChunkAreThere() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)

        assertEquals(ContentResolvability.RESOLVABLE, f.observer.contentResolvability(f.entityId, contentId))
    }

    @Test
    fun resolvability_isABSENT_whenTheRelayAnswersThatTheManifestIsGone() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        f.sink.chunks.clear()

        assertEquals(ContentResolvability.ABSENT, f.observer.contentResolvability(f.entityId, contentId))
    }

    @Test
    fun resolvability_isABSENT_whenAChunkTheManifestNamesIsGone() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        f.sink.chunks.remove(chunkRefsOf(f).first())

        assertEquals(ContentResolvability.ABSENT, f.observer.contentResolvability(f.entityId, contentId))
    }

    /** ⚠⚠ The branch a deleting caller depends on: an unreachable relay is never "the relay lost it". */
    @Test
    fun resolvability_isUNKNOWN_whenTheRelayCannotBeReached() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        f.sink.presenceUnknown = true

        assertEquals(ContentResolvability.UNKNOWN, f.observer.contentResolvability(f.entityId, contentId))

        // ⚠ And the same when the manifest read itself fails: getChunk cannot tell missing from
        // unreachable, so the presence check is what decides — and it knows nothing here either.
        f.sink.chunks.clear()
        assertEquals(ContentResolvability.UNKNOWN, f.observer.contentResolvability(f.entityId, contentId))
    }

    @Test
    fun resolvability_isUNKNOWN_whenTheRealmIsLocked() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        f.syncManager.lock()

        assertEquals(ContentResolvability.UNKNOWN, f.observer.contentResolvability(f.entityId, contentId))
    }

    /** What a producer asks before making bytes at all — both halves of "no" are sticky. */
    @Test
    fun canPublishContentNow_followsTheMeteredGateAndTheQuotaLatch() = runTest {
        assertTrue(fixture().observer.canPublishContentNow(), "an open gate and a relay taking bytes")

        assertFalse(
            fixture(contentGate = SyncGate { false }).observer.canPublishContentNow(),
            "a closed metered gate means nothing can go out",
        )

        val rejecting = fixture()
        rejecting.sink.failPutsOverQuota = true
        rejecting.observer.emitFileContent(rejecting.entityId, dataPropertyId, path)
        assertFalse(rejecting.observer.canPublishContentNow(), "a relay refusing on quota is the other half")
    }

    @Test
    fun anUnreachableRelay_handsTheContentOffRatherThanReadingAsHealthy() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        val puts = f.sink.putCount
        f.sink.presenceUnknown = true

        assertFalse(f.observer.ensureContentOnRelay(f.entityId, dataPropertyId, path, contentId))

        assertTrue(f.sink.putCount > puts, "unknown must hand off, not skip")
    }

    /**
     * ⚠⚠ A cancelled declare must not read as "not declared", which is a RESOLVABLE outcome: the
     * check would answer `true` — the relay has it, skip the upload — to a caller that had already
     * gone, and record the publish on its way out. Cancellation is not a verdict about the relay, so
     * it has to leave through the caller rather than become one.
     */
    @Test
    fun aCancelledDeclareCancelsTheCheck_ratherThanAnsweringForACallerThatHasGone() = runTest {
        val f = fixture(declareCancels = true)
        assertTrue(f.observer.emitFileContent(f.entityId, dataPropertyId, path))
        val puts = f.sink.putCount
        f.declares!!.armed = true

        assertFailsWith<CancellationException> {
            f.observer.ensureContentOnRelay(f.entityId, dataPropertyId, path, contentId)
        }

        assertEquals(1, f.declares.attemptsWhileArmed, "the cancelling declare must be reached, not skipped")
        assertEquals(puts, f.sink.putCount, "nothing is uploaded for a caller that has gone")
    }

    /**
     * ⚠ `describeContent` suspends, so a cancelled caller reaches the observer through it. Armed
     * only after the setup emit, which describes through the same call.
     */
    private class CancelOnDeclare(
        private val inner: InMemoryContentBlobStore,
    ) : ContentBlobStore by inner {
        var armed = false
        var attemptsWhileArmed = 0
            private set

        override suspend fun describeContent(contents: List<ContentDescription>): Boolean {
            if (!armed) return inner.describeContent(contents)
            attemptsWhileArmed++
            throw CancellationException("the overwrite's scope went away")
        }
    }
}
