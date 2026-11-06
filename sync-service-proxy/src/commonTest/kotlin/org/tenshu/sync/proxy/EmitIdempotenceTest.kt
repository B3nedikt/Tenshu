package org.tenshu.sync.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One content, emitted twice for one holder, is uploaded ONCE.
 *
 * ⚠⚠ Every emit mints a fresh CEK, so a second emit is a whole new chunk set. The manifest address and
 * the relay's describe are last-write-wins, and the losing set stays on the relay undescribed and
 * unreachable by any sweep — and the LIVE chunk set can be the undescribed one.
 */
class EmitIdempotenceTest {

    private class Port(var contentId: String, private val contentHash: String) : ExternalPropertyWriter {
        /** The production writer turns a failed read into null. */
        var unreadable = false
        override fun supports(propertyId: String): Boolean = true
        override fun write(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String, value: Data) = Unit
        override fun resolveContentId(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String): String? =
            if (unreadable) null else contentId
        override fun resolveContentHash(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String): String = contentHash
    }

    private class Harness(
        val proxy: TestProxy,
        val sink: InMemoryContentBlobStore,
        val observer: SyncObserver,
        val port: Port,
        /** Another runtime over the same store and relay — its own, empty, published table. */
        val anotherRuntime: () -> SyncObserver,
    )

    private val bytes = ByteArray(4000) { it.toByte() }
    private val property = "tenshu/file/file/data"

    private var gateOpen = true

    private fun harness(vararg paths: String): Harness {
        val port = Port("cid-1", wholeFileHashOf(bytes))
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to port),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val sm = SyncManagerImpl(context = proxy.tContext, cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate())
            .apply { initialize("mock phrase") }
        val fdb = InMemoryFileDatabase().apply { paths.forEach { writeRawByteArray(it, bytes) } }
        val sink = InMemoryContentBlobStore(proxy.tContext)
        fun observer() = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = sm,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
            contentBlobStore = sink,
            fileDatabase = fdb,
            contentGate = org.tenshu.sync.common.SyncGate { gateOpen },
        )
        return Harness(proxy, sink, observer(), port, ::observer)
    }

    /**
     * Another DEVICE: its own store and journal, the same realm key ("mock phrase") and the same relay.
     * ⚠ Not [Harness.anotherRuntime], which shares this device's store — the journal is keyed by content,
     * so a publish there clears this device's parked entry itself and hides the drain's behaviour.
     */
    private fun anotherDevice(h: Harness, vararg paths: String): Pair<SyncObserver, TestProxy> {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to Port(h.port.contentId, wholeFileHashOf(bytes))),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val sm = SyncManagerImpl(context = proxy.tContext, cryptoManager = crypto, storageDelegate = InMemorySecureStorageDelegate())
            .apply { initialize("mock phrase") }
        val fdb = InMemoryFileDatabase().apply { paths.forEach { writeRawByteArray(it, bytes) } }
        return SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = sm,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
            contentBlobStore = h.sink,
            fileDatabase = fdb,
            contentGate = org.tenshu.sync.common.SyncGate { true },
        ) to proxy
    }

    private fun id(h: Harness, path: String): DataId = DataId.StringDataId.create(h.proxy.tContext, SyntheticTestData.ID, path)

    @Test
    fun theSameContentForTheSameHolder_isUploadedOnce() = runTest {
        val h = harness("/a.jpg")
        assertTrue(h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg"))
        val afterFirst = h.sink.putCount

        assertTrue(h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg"), "a skip still reports success")

        assertEquals(afterFirst, h.sink.putCount, "the second emit uploaded nothing — no second chunk set")
    }

    @Test
    fun twoConcurrentEmits_uploadOnce() = runTest {
        // The concurrent shape: the second waits on the emit key, then must find the content published.
        val h = harness("/a.jpg")
        val single = harness("/a.jpg").also { it.observer.emitFileContent(id(it, "/a.jpg"), property, "/a.jpg") }.sink.putCount

        listOf(
            async { h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg") },
            async { h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg") },
        ).awaitAll()

        assertEquals(single, h.sink.putCount, "two racing emits of one content cost exactly one upload")
    }

    @Test
    fun aCopy_stillDescribesForItsOwnHolder() = runTest {
        // ⚠⚠ Copies share a contentId across holders. Skipping on content alone would skip the second
        // holder's description. The marker is per holder AND content. (The holder's EDGE rides its
        // own push, so this pins the describe.)
        val h = harness("/a.jpg", "/copy.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        h.observer.emitFileContent(id(h, "/copy.jpg"), property, "/copy.jpg")

        assertEquals(2, h.sink.describedContents.size, "each holder's emit described the content")
    }

    @Test
    fun newContentForTheSameHolder_isEmitted() = runTest {
        // An overwrite mints a new contentId — genuinely new bytes, never skipped.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val afterFirst = h.sink.putCount

        h.port.contentId = "cid-2"
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")

        assertTrue(h.sink.putCount > afterFirst, "new content uploads")
        assertEquals(listOf("cid-1", "cid-2"), h.sink.describedContents.map { it.contentId })
    }

    @Test
    fun aRepair_reEmitsEvenAfterAPublish() = runTest {
        // The relay LOST this content, so "already published by this runtime" is exactly the case
        // that must upload again — `ContentRepair` and `ensureContentOnRelay` come through here.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val afterFirst = h.sink.putCount

        assertTrue(h.observer.repairFileContent(id(h, "/a.jpg"), property, "/a.jpg"))

        assertTrue(h.sink.putCount > afterFirst, "a repair uploads again")
    }

    @Test
    fun alreadyPublishedByAnotherHolder_stillDescribesForThisHolder() = runTest {
        // ⚠⚠ A copy (or a shared thumbnail rung) finds its content already on the
        // relay; skipping the upload must not skip ITS edge, or deleting the original leaves the copy's
        // content looking unreferenced.
        val h = harness("/a.jpg", "/copy.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val puts = h.sink.putCount

        assertTrue(h.observer.ensureContentOnRelay(id(h, "/copy.jpg"), property, "/copy.jpg", "cid-1"))

        assertEquals(puts, h.sink.putCount, "resolvable, so nothing is uploaded")
        assertEquals(2, h.sink.describedContents.size, "but the content is described again for the copy")
    }

    @Test
    fun aPublishWhoseDeclareFailed_isNotMarked_soTheNextEmitRetries() = runTest {
        // Marking an undeclared publish would stop the missing edge ever being
        // retried in this runtime.
        val h = harness("/a.jpg")
        h.sink.failDeclarations = true
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val afterFirst = h.sink.putCount
        h.sink.failDeclarations = false

        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")

        assertTrue(h.sink.putCount > afterFirst, "not marked, so the next emit tries again")
        assertTrue(h.sink.describedContents.isNotEmpty(), "and this time the description lands")
    }

    @Test
    fun aDeferredUploadPublishedByTheDrain_isMarked() = runTest {
        // ⚠⚠ The metered/quota-deferred path publishes through the DRAIN; unmarked, the next ordinary
        // emit of the same content would re-upload it under a fresh CEK.
        val h = harness("/a.jpg")
        gateOpen = false
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        assertEquals(0, h.sink.putCount, "precondition: the closed gate journalled it")
        gateOpen = true
        h.observer.drainDeferredContent(force = true)
        val afterDrain = h.sink.putCount
        assertTrue(afterDrain > 0, "precondition: the drain published it")

        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")

        assertEquals(afterDrain, h.sink.putCount, "the drain's publish counts — no second chunk set")
    }

    @Test
    fun ensureContentOnRelay_waitsForTheContentsEmitKey() = runTest {
        // ⚠⚠ Resolve-then-declare must hold the content's emit key, or a concurrent
        // repair can republish under new refs between the two and the late declare writes stale ones.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()

        val holder = launch {
            h.observer.withContentEmitOwnership(id(h, "/a.jpg"), property) {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()
        val ensure = async { h.observer.ensureContentOnRelay(id(h, "/a.jpg"), property, "/a.jpg", "cid-1") }
        repeat(20) { kotlinx.coroutines.yield() }

        assertTrue(!ensure.isCompleted, "it must wait while another owner holds the content's key")
        release.complete(Unit)
        assertTrue(ensure.await(), "and completes once the key is released")
        holder.join()
    }

    @Test
    fun contentFoundOnTheRelayAndDeclared_isRecordedAsPublished() = runTest {
        // ⚠⚠ Content left by ANOTHER runtime (or device), declared for this holder by
        // ensureContentOnRelay, must count as published here — or a later ordinary emit duplicates it.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val puts = h.sink.putCount
        val other = h.anotherRuntime()

        assertTrue(other.ensureContentOnRelay(id(h, "/a.jpg"), property, "/a.jpg", "cid-1"))
        other.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")

        assertEquals(puts, h.sink.putCount, "found, declared, recorded — the ordinary emit uploads nothing")
    }

    @Test
    fun aForcedRepairWhoseDeclareFails_clearsTheOldMarker() = runTest {
        // ⚠⚠ A forced repair bypasses the marker without invalidating it; if its declare then fails,
        // a stale marker would make every later emit skip the missing declaration.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        h.sink.failDeclarations = true
        h.observer.repairFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val afterRepair = h.sink.putCount
        val describedBefore = h.sink.describedContents.size
        h.sink.failDeclarations = false

        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")

        assertTrue(h.sink.putCount > afterRepair, "the marker was cleared, so the emit retries")
        assertTrue(h.sink.describedContents.size > describedBefore, "and the description lands this time")
    }

    @Test
    fun aParkedEntryWhoseContentWasSinceFoundAndDeclared_isRetiredNotReUploaded() = runTest {
        // ⚠⚠ The drain reads the marker, not only writes it. Derived thumbnail rungs share one
        // content id on every device, so:
        val h = harness("/a.jpg")
        val (other, otherProxy) = anotherDevice(h, "/a.jpg")
        // 1. On cellular, this device finds the content absent: the forced emit journals it, the gate parks it.
        gateOpen = false
        h.observer.ensureContentOnRelay(id(h, "/a.jpg"), property, "/a.jpg", "cid-1")
        assertEquals(0, h.sink.putCount, "precondition: parked")
        // 2. Another device, on Wi-Fi, publishes the same content.
        other.emitFileContent(DataId.StringDataId.create(otherProxy.tContext, SyntheticTestData.ID, "/a.jpg"), property, "/a.jpg")
        val published = h.sink.putCount
        assertTrue(published > 0, "precondition: the other device published it")
        // 3. Viewed again, still on cellular: resolvable, so this holder is declared and marked.
        gateOpen = false
        assertTrue(h.observer.ensureContentOnRelay(id(h, "/a.jpg"), property, "/a.jpg", "cid-1"))

        // 4. Wi-Fi: the parked entry drains.
        gateOpen = true
        val remaining = h.observer.drainDeferredContent(force = true)

        assertEquals(published, h.sink.putCount, "no second chunk set — the other device's set stays the described one")
        assertEquals(0, remaining, "and the entry is retired, not kept for ever")
    }

    @Test
    fun aParkedRepair_isStillUploaded_evenThoughTheContentWasPublishedEarlier() = runTest {
        // The counter-case: a forced repair says the relay LOST the content, so an older marker must not
        // let the drain retire the parked repair as done.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val afterPublish = h.sink.putCount
        gateOpen = false
        h.observer.repairFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        assertEquals(afterPublish, h.sink.putCount, "precondition: the repair was parked")

        gateOpen = true
        h.observer.drainDeferredContent(force = true)

        assertTrue(h.sink.putCount > afterPublish, "the parked repair uploads")
    }

    @Test
    fun aStaleMarker_doesNotRetireAnUploadTheRelayNoLongerHas() = runTest {
        // ⚠⚠ The marker is in memory and a move never clears it, so it can outlive the
        // relay's copy (publish at /a, move away, repair parked, move back). The drain asks the relay
        // before retiring on it.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        h.sink.chunks.keys.toList().forEach { h.sink.dropBlob(it) } // the relay lost the content
        gateOpen = false
        // Park an intent for the same holder and content WITHOUT touching this runtime's marker.
        h.anotherRuntime().emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        val beforeDrain = h.sink.putCount

        gateOpen = true
        h.observer.drainDeferredContent(force = true)

        assertTrue(h.sink.putCount > beforeDrain, "the relay said ABSENT, so the parked upload goes out")
    }

    @Test
    fun aMarkedEntry_redeclaresItsHolderFromTheManifestBeforeItIsRetired() = runTest {
        // ⚠⚠ RESOLVABLE proves the bytes exist, not that the relay's description matches
        // the manifest. The retire must re-declare this holder against the manifest's refs first.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = false
        h.anotherRuntime().emitFileContent(id(h, "/a.jpg"), property, "/a.jpg") // parked, marker untouched
        gateOpen = true
        h.sink.describedContents.clear()

        val remaining = h.observer.drainDeferredContent(force = true)

        assertTrue(h.sink.describedContents.any { it.contentId == "cid-1" && it.refs.isNotEmpty() }, "re-described from the manifest")
        assertEquals(0, remaining, "then retired")
    }

    @Test
    fun aMarkedEntryWhoseRedeclareFails_isKept_andNothingIsReUploaded() = runTest {
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = false
        h.anotherRuntime().emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = true
        val puts = h.sink.putCount
        h.sink.failDeclarations = true

        val remaining = h.observer.drainDeferredContent(force = true)

        assertEquals(1, remaining, "kept for the next pass to declare")
        assertEquals(puts, h.sink.putCount, "and no second chunk set")
    }

    @Test
    fun aMarkedEntrysRedeclare_isRedoneWhenAnotherPropertyEmitsTheSameContentMidResolve() = runTest {
        // ⚠⚠ The drain's key covers one (content, property), but the manifest is
        // content-addressed — an emit through ANOTHER property can publish new refs between this resolve and
        // this declare. The per-content stamp catches it, and the pair is re-done.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = false
        h.anotherRuntime().emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = true
        val otherProperty = org.tenshu.sync.common.PendingContentEmit.contentIdKeyOf("cid-1", "tenshu/file/file/other")
        var overlapped = false
        val manifestFetches = mutableListOf<String>()
        h.sink.onGet = { ref ->
            if (manifestFetches.isEmpty() || ref == manifestFetches.first()) manifestFetches += ref
            if (!overlapped) {
                overlapped = true
                h.observer.emitKeyLocks.beginHold(otherProperty)
                h.observer.emitKeyLocks.endHold(otherProperty)
            }
        }

        val remaining = h.observer.drainDeferredContent(force = true)

        assertEquals(2, manifestFetches.size, "the overlap moved the content's stamp, so the resolve was re-done")
        assertEquals(0, remaining, "and once it held still, the entry retired")
    }

    @Test
    fun aMarkedEntryWhoseHolderReadFails_isKept_notReUploaded() = runTest {
        // ⚠ Null is "retired" or "unreadable". With the bytes here, skipping the marker would fall
        // through to PUBLISH — a duplicate chunk set.
        val h = harness("/a.jpg")
        h.observer.emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = false
        h.anotherRuntime().emitFileContent(id(h, "/a.jpg"), property, "/a.jpg")
        gateOpen = true
        val puts = h.sink.putCount
        h.port.unreadable = true

        val remaining = h.observer.drainDeferredContent(force = true)

        assertEquals(puts, h.sink.putCount, "no second chunk set")
        assertEquals(1, remaining, "kept for a pass that can read the holder")
    }
}
