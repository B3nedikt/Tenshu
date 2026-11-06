package org.tenshu.sync.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.retargetPendingContentEmits
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A deferred content intent must survive its file being **moved**
 * (`content-plane-refcount.md` §6d D3).
 *
 * ⚠⚠ The journal entry records the path captured at `emitFileContent` time, and the drain asks
 * `fileDatabase.fileSize(entry.filePath)` to decide whether the file still exists. A move makes
 * those two disagree — the bytes are at the row's new path, the entry still names the old one —
 * and the drain's answer is not merely "retry later": `if (emitted || gone) clearDeferredContent(...)`
 * **durably deletes the intent**. The bytes are right there, the row still references content that
 * was never uploaded, and the one record that would have driven the repair is gone.
 *
 * ⚠ That is why this is a `gone` bug and not a `ContentRepair` bug. Fixing only the repair side
 * leaves the one site that turns a recoverable state into an unrecoverable one untouched.
 */
class DeferredIntentSurvivesAMoveTest {

    private val propertyId = "${SyntheticTestData.ID}/data"

    @Test
    fun anOwnedContentIsNotEmittedByTheDrainUnderneathItsProducer() = runTest {
        // ⚠⚠ The producer writes the bytes and saves the row BEFORE it emits, and
        // `emitFileContentNow` re-resolves both — so a drain landing in that window emits the
        // producer's new version under an older journal entry and clears it, and the producer then
        // emits the same version again under a fresh nonce. Two chunk sets, one manifest address,
        // and whichever set the surviving manifest does not name can never be freed.
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
        )
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val fileDatabase = InMemoryFileDatabase()
        val sink = InMemoryContentBlobStore(proxy.tContext)
        var gateOpen = false
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(
                proxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            ),
            contentBlobStore = sink,
            fileDatabase = fileDatabase,
            contentGate = SyncGate { gateOpen },
        )
        val entityId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "photo",
        )

        // An intent is already journalled — a previous emit the closed gate parked.
        fileDatabase.writeRawByteArray("/photo.jpg", ByteArray(64) { it.toByte() })
        observer.emitFileContent(entityId, propertyId, "/photo.jpg")
        assertEquals(1, journal(proxy).size, "precondition: an entry the drain can act on")
        gateOpen = true

        val owned = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val producer = launch {
            observer.withContentEmitOwnership(entityId, propertyId) {
                owned.complete(Unit)
                finish.await()
            }
        }
        owned.await()

        observer.drainDeferredContent(force = true)
        assertEquals(
            0,
            sink.putCount,
            "the producer owns this content — a drain that emits it here publishes a second " +
                "chunk set under the same manifest address, and one of the two is then unreachable",
        )
        assertEquals(1, journal(proxy).size, "and the entry is left for the producer to retire")

        finish.complete(Unit)
        producer.join()
    }

    @Test
    fun emittingInsideAnOwnershipDoesNotDeadlock() = runTest {
        // ⚠ The producer's own emit runs INSIDE its claim. A non-reentrant lock would hang the
        // upload here rather than duplicate it — a worse failure than the one being fixed.
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
        )
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val fileDatabase = InMemoryFileDatabase()
        val sink = InMemoryContentBlobStore(proxy.tContext)
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(
                proxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            ),
            contentBlobStore = sink,
            fileDatabase = fileDatabase,
            contentGate = SyncGate { true },
        )
        val entityId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "photo",
        )
        fileDatabase.writeRawByteArray("/photo.jpg", ByteArray(64) { it.toByte() })

        val emitted = observer.withContentEmitOwnership(entityId, propertyId) {
            observer.emitFileContent(entityId, propertyId, "/photo.jpg")
        }

        assertTrue(emitted, "the producer's own emit runs inside its own claim")
        assertTrue(sink.putCount > 0, "and it actually uploaded")
    }

    /**
     * Answers per ROW, the way `FileBytesPropertyWriter` does: a row that is not in [rows] resolves
     * nothing — exactly what `getData(retiredId)` returns in production after a move.
     *
     * ⚠⚠ That is the one axis these tests depend on: a double that answered without looking at the
     * entity id would let a test of "follow the row after a move" pass against a production path
     * that resolves the RETIRED row and gets null.
     */
    private class RowsPort : ExternalPropertyWriter {
        data class Row(val contentId: String, val contentHash: String?, val path: String)

        val rows = mutableMapOf<Long, Row>()

        /** Runs once, at the next content-id lookup — the first thing a drain reads about an entry. */
        var beforeNextLookup: (() -> Unit)? = null

        override fun supports(propertyId: String): Boolean = true
        override fun write(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String, value: Data) = Unit
        override fun resolveExternalPath(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) =
            rows[targetEntityId.getDataIdHash()]?.path
        override fun resolveContentId(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String): String? {
            beforeNextLookup?.also { beforeNextLookup = null }?.invoke()
            return rows[targetEntityId.getDataIdHash()]?.contentId
        }
        override fun resolveContentHash(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) =
            rows[targetEntityId.getDataIdHash()]?.contentHash
    }

    private class Harness(
        val proxy: TestProxy,
        val fdb: InMemoryFileDatabase,
        val sink: InMemoryContentBlobStore,
        val observer: SyncObserver,
        val port: RowsPort,
        val syncManager: SyncManagerImpl,
    ) {
        var gateOpen = false
        fun idFor(path: String): DataId = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path)
        fun row(path: String, contentId: String, bytes: ByteArray?) {
            port.rows[idFor(path).getDataIdHash()] =
                RowsPort.Row(contentId, bytes?.let { wholeFileHashOf(it) }, path)
        }
        fun retire(path: String) {
            port.rows.remove(idFor(path).getDataIdHash())
        }
    }

    private fun harness(readThrough: (InMemoryFileDatabase) -> FileDatabase = { it }): Harness {
        val port = RowsPort()
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to port),
        )
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val fdb = InMemoryFileDatabase()
        val sink = InMemoryContentBlobStore(proxy.tContext)
        lateinit var harness: Harness
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(
                proxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            ),
            contentBlobStore = sink,
            fileDatabase = readThrough(fdb),
            contentGate = SyncGate { harness.gateOpen },
        )
        harness = Harness(proxy, fdb, sink, observer, port, syncManager)
        return harness
    }

    private val photo = ByteArray(64) { it.toByte() }

    @Test
    fun aPeersMovePublishesTheParkedUploadUnderItsContentId() = runTest {
        // ⚠⚠ The case no operation on this device can see: a peer's move arrives as blocks, so the
        // row the intent was journalled against is retired and nothing re-keys anything. Ingest
        // never moves bytes, so they are still exactly where the entry says.
        //
        // ⚠⚠ The intent is keyed on the content, not the row. Resolved through the RETIRED row, the
        // drain would get null, publish nothing a reader can find, and clear the intent: the journal
        // says "done" and no reader can ever resolve the content.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")
        assertEquals(1, journal(h.proxy).size, "precondition: the upload is parked")

        // A peer's move, as block application leaves it: new row, old row gone, bytes untouched.
        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertTrue(
            manifestContentRef("cid-photo") in h.sink.chunks,
            "the content is published at the address the moved row resolves it by",
        )
        assertTrue(journal(h.proxy).isEmpty(), "and the intent retires because it SUCCEEDED")
    }

    @Test
    fun aPeersOverwriteNeverPublishesStaleBytesUnderTheNewContentsAddress() = runTest {
        // ⚠⚠ A peer overwrote the file; this device still holds the OLD bytes, because a row synced
        // from a peer does not fetch its content. Re-resolving the row would answer with the NEW
        // content id and hash, and the drain would publish the old plaintext at the new content's
        // address — stamped with the new hash, so every reader's integrity gate then rejects the file
        // the peer actually wrote.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-old", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        val theirs = ByteArray(80) { (it * 3).toByte() }
        h.row("/photo.jpg", contentId = "cid-new", bytes = theirs)

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertFalse(
            manifestContentRef("cid-new") in h.sink.chunks,
            "this device never held cid-new's bytes, so nothing it writes may land at cid-new's address",
        )
        // ⭐ The drain declares no edge at all (the row's own push carries its edges), so this pins
        // only the publish.
    }

    @Test
    fun bytesThatNoLongerHashToTheContentAreNeverPublishedAsIt() = runTest {
        // The same overwrite, after this device has fetched the new bytes over the old path.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-old", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        val theirs = ByteArray(80) { (it * 3).toByte() }
        h.fdb.writeRawByteArray("/photo.jpg", theirs)
        h.row("/photo.jpg", contentId = "cid-new", bytes = theirs)

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertEquals(0, h.sink.putCount, "the bytes at that path are someone else's content now")
        assertTrue(journal(h.proxy).isEmpty(), "and the superseded intent retires rather than retrying for ever")
    }

    @Test
    fun onceNoRowVouchesForThePathOnlyTheHashMaySayTheBytesAreStillTheContent() = runTest {
        // ⚠⚠ The case where nothing but the hash can decide. A peer moved the row away, so no row
        // claims the old path — and something else has since been written there. Publishing
        // "whatever is at the path" would put a stranger's bytes behind this content's address.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.fdb.writeRawByteArray("/photo.jpg", ByteArray(64) { 7 })

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertEquals(0, h.sink.putCount, "the bytes at the vacated path are not this content any more")
    }

    @Test
    fun aHashlessContentItsHolderHasSupersededRetiresInsteadOfPublishing() = runTest {
        // ⚠ A thumbnail rung carries no stored hash, so nothing can verify its bytes — and an
        // overwrite made while the rungs were parked moves the holder onto new rungs at NEW
        // contentId-derived paths, leaving the old ones exactly where their entries say. With no
        // hash, the holder naming other content is the only evidence there is, and it is enough:
        // a rung is re-mintable by any byte holder.
        val h = harness()
        val rung = ByteArray(32) { 3 }
        h.fdb.writeRawByteArray("/.thumbnails/cid-old/list", rung)
        h.port.rows[h.idFor("/photo.jpg").getDataIdHash()] =
            RowsPort.Row(contentId = "rung-old", contentHash = null, path = "/.thumbnails/cid-old/list")
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/.thumbnails/cid-old/list")

        h.port.rows[h.idFor("/photo.jpg").getDataIdHash()] =
            RowsPort.Row(contentId = "rung-new", contentHash = null, path = "/.thumbnails/cid-new/list")

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertEquals(0, h.sink.putCount, "a superseded rung is not published")
        assertTrue(journal(h.proxy).isEmpty(), "and its intent retires")
    }

    @Test
    fun bytesThatChangeWhileBeingStreamedAreNeverPublishedAsTheContent() = runTest {
        // ⚠⚠ Off the fast path no claim covers the location: a local recreate at
        // the old path after a peer's move, or an on-demand fetch promoting a newcomer's bytes, can
        // write it between the drain's pre-check and its ranged reads. A manifest over those reads
        // is mixed bytes behind this content's address, stamped with its hash — and every reader's
        // integrity gate then rejects the file the moved row names.
        val original = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() } // three 2 MiB chunks
        val replacement = ByteArray(original.size) { (it % 13).toByte() }
        var reads = 0
        lateinit var backing: InMemoryFileDatabase
        val h = harness { inner ->
            backing = inner
            object : FileDatabase by inner {
                override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
                    // The pre-check reads all three chunks; the emit's SECOND read is mid-stream.
                    if (filePath == "/photo.jpg" && ++reads == 5) backing.writeRawByteArray(filePath, replacement)
                    return inner.readBytesRange(filePath, offset, length)
                }
            }
        }
        h.fdb.writeRawByteArray("/photo.jpg", original)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = original)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        // A peer's move: nothing vouches for the old path any more, so the drain verifies.
        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = original)

        h.gateOpen = true
        h.observer.drainDeferredContent()
        assertFalse(
            manifestContentRef("cid-photo") in h.sink.chunks,
            "the bytes changed under the stream, so no manifest may name what was sent",
        )
        assertEquals(1, journal(h.proxy).size, "and the entry is left for the next pass to judge")

        h.observer.drainDeferredContent()
        assertFalse(manifestContentRef("cid-photo") in h.sink.chunks)
        assertTrue(journal(h.proxy).isEmpty(), "which sees bytes that are no longer this content, and retires it")
    }

    @Test
    fun anOverwriteRetiresTheIntentItSuperseded() = runTest {
        // ⚠⚠ Keyed on content, a new intent does not REPLACE its predecessor, and behind a closed
        // gate the drain that would retire it never runs: every overwrite on cellular would leave its
        // predecessor queued, inflating the waiting count and the bytes the quota pre-flight counts.
        // The overwrite names what it superseded.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-old", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        val edited = ByteArray(70) { 9 }
        h.fdb.writeRawByteArray("/photo.jpg", edited)
        h.row("/photo.jpg", contentId = "cid-new", bytes = edited)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")
        assertEquals(2, journal(h.proxy).size, "precondition: the new intent sits beside the one it superseded")

        h.observer.discardSupersededContent(h.idFor("/photo.jpg"), listOf("cid-old" to propertyId))

        assertEquals(listOf("cid-new"), journal(h.proxy).map { it.contentId.get() })
    }

    @Test
    fun anOverwriteDoesNotRetireAnIntentWhoseBytesSitWithATwin() = runTest {
        // ⚠ A divergent rename leaves two rows on ONE content id, so their intents are one entry —
        // holding whichever row journalled last, and reading the bytes at THAT row's path. Here the
        // twin holds them; overwriting the other row replaced nothing the entry depends on.
        val h = harness()
        h.fdb.writeRawByteArray("/twin.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-shared", bytes = photo)
        h.row("/twin.jpg", contentId = "cid-shared", bytes = photo)
        h.observer.emitFileContent(h.idFor("/twin.jpg"), propertyId, "/twin.jpg")

        val edited = ByteArray(70) { 9 }
        h.fdb.writeRawByteArray("/photo.jpg", edited)
        h.row("/photo.jpg", contentId = "cid-new", bytes = edited)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.observer.discardSupersededContent(h.idFor("/photo.jpg"), listOf("cid-shared" to propertyId))

        assertEquals(
            setOf("cid-shared", "cid-new"),
            journal(h.proxy).map { it.contentId.get() }.toSet(),
            "the shared content's intent still holds the twin's bytes, and the twin still names it",
        )
    }

    @Test
    fun aRetargetThatLandsAfterThePassReadTheJournalIsNotUndoneByIt() = runTest {
        // ⚠⚠ A pass snapshots the journal, then works through it. A local move retargets an entry IN
        // PLACE — same key — so by the time the pass reaches it the snapshot names a retired row and a
        // path with nothing at it, which reads as gone; retiring on that would delete the freshly
        // retargeted row and strand the moved upload.
        lateinit var h: Harness
        var moved = false
        h = harness { inner ->
            object : FileDatabase by inner {
                override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
                    // While the pass sends the FIRST entry, the user moves the file behind the second.
                    if (filePath == "/first.jpg" && !moved) {
                        moved = true
                        inner.moveFile("/photo.jpg", "/album/photo.jpg")
                        h.retire("/photo.jpg")
                        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)
                        retargetPendingContentEmits(
                            executionContext = h.proxy.executionContext,
                            oldDataId = h.idFor("/photo.jpg"),
                            newDataId = h.idFor("/album/photo.jpg"),
                            oldFilePath = "/photo.jpg",
                            newFilePath = "/album/photo.jpg",
                        )
                    }
                    return inner.readBytesRange(filePath, offset, length)
                }
            }
        }
        val first = ByteArray(40) { 1 }
        h.fdb.writeRawByteArray("/first.jpg", first)
        h.row("/first.jpg", contentId = "cid-first", bytes = first)
        h.observer.emitFileContent(h.idFor("/first.jpg"), propertyId, "/first.jpg")
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertTrue(moved, "precondition: the move landed mid-pass")
        assertTrue(
            manifestContentRef("cid-photo") in h.sink.chunks,
            "the moved upload is sent from where the move put it — the pass judged the row, not its snapshot",
        )
        assertTrue(journal(h.proxy).isEmpty())
    }

    @Test
    fun aRetargetRacingTheRetireIsNotDeletedByIt() = runTest {
        // ⚠ The narrower window the re-read cannot close: the retarget lands AFTER it, as the drain
        // starts asking about the entry — so the holder it asks about is already retired and the
        // bytes already moved, and the verdict is RETIRE. The retire is then a compare-and-delete in
        // a store transaction, the kind the retarget runs in, so a row that moved on survives.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.port.beforeNextLookup = {
            h.fdb.moveFile("/photo.jpg", "/album/photo.jpg")
            h.retire("/photo.jpg")
            h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)
            retargetPendingContentEmits(
                executionContext = h.proxy.executionContext,
                oldDataId = h.idFor("/photo.jpg"),
                newDataId = h.idFor("/album/photo.jpg"),
                oldFilePath = "/photo.jpg",
                newFilePath = "/album/photo.jpg",
            )
        }
        h.gateOpen = true
        h.observer.drainDeferredContent()
        assertEquals(null, h.port.beforeNextLookup, "precondition: the retarget landed inside the pass")
        assertEquals(
            listOf("/album/photo.jpg"),
            journal(h.proxy).map { it.filePath.get() },
            "the retargeted entry survives a retire decided on where it used to be",
        )

        h.observer.drainDeferredContent()
        assertTrue(manifestContentRef("cid-photo") in h.sink.chunks, "and the next pass sends it")
    }

    @Test
    fun aSecondOverwriteThatWaitedForOwnershipOwnsTheContentTheRowNamesNow() = runTest {
        // ⚠⚠ Both overwrites read the key of the content the row
        // names before either starts. The second gets that key only after the first has moved the
        // row on to new content — which the second then does not own, so a drain of the first
        // overwrite's intent could publish on the trusted fast path while the second rewrites it.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-1", bytes = photo)

        val firstOwns = CompletableDeferred<Unit>()
        val firstDone = CompletableDeferred<Unit>()
        val secondOwns = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val first = launch {
            h.observer.withContentEmitOwnership(h.idFor("/photo.jpg"), propertyId) {
                firstOwns.complete(Unit)
                firstDone.await()
            }
        }
        firstOwns.await()
        val second = launch {
            h.observer.withContentEmitOwnership(h.idFor("/photo.jpg"), propertyId) {
                secondOwns.complete(Unit)
                secondDone.await()
            }
        }
        testScheduler.runCurrent() // the second has read cid-1's key and is waiting on it

        // The first overwrite lands: new bytes, the row names cid-2, its intent parks.
        val v2 = ByteArray(50) { 2 }
        h.fdb.writeRawByteArray("/photo.jpg", v2)
        h.row("/photo.jpg", contentId = "cid-2", bytes = v2)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")
        firstDone.complete(Unit)
        secondOwns.await()

        h.gateOpen = true
        h.observer.drainDeferredContent(force = true)
        assertEquals(
            0,
            h.sink.putCount,
            "the second overwrite owns cid-2 now — a drain publishing it here reads bytes being rewritten",
        )

        secondDone.complete(Unit)
        first.join()
        second.join()
    }

    @Test
    fun aDrainInsideALocalMovesRelocationWindowKeepsTheIntent() = runTest {
        // ⚠⚠ `MoveFile` renames the bytes before it retargets the journal. A drain in between finds
        // nothing at the journalled path — and must not call that `gone` and delete the intent,
        // while the bytes sit one directory over. Every cheap re-ordering only mirrors the window;
        // what closes it is not reading absence as deletion while a live row still wants the content.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.fdb.moveFile("/photo.jpg", "/album/photo.jpg")
        h.gateOpen = true
        h.observer.drainDeferredContent()

        assertEquals(0, h.sink.putCount, "nothing to upload from the journalled path")
        assertEquals(1, journal(h.proxy).size, "but the intent is KEPT: the row still names this content")

        // The move finishes: row re-keyed, journal retargeted.
        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)
        retargetPendingContentEmits(
            executionContext = h.proxy.executionContext,
            oldDataId = h.idFor("/photo.jpg"),
            newDataId = h.idFor("/album/photo.jpg"),
            oldFilePath = "/photo.jpg",
            newFilePath = "/album/photo.jpg",
        )
        h.observer.drainDeferredContent()

        assertTrue(manifestContentRef("cid-photo") in h.sink.chunks, "and drains from where the bytes are now")
        assertTrue(journal(h.proxy).isEmpty())
    }

    @Test
    fun theEvictionPinFollowsTheContentToWhereAPeerMovedItsRow() = runTest {
        // ⚠ The pin (`hasQueuedContentFor`) is asked by row. Keyed on the retired row, the moved row
        // would have no pin — and the OLD path, which holds the only copy, is exactly what
        // `OrphanedBytesSweep` collects once no row names it.
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")

        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)

        assertTrue(h.observer.hasQueuedContentFor(h.idFor("/album/photo.jpg"), propertyId), "the moved row is pinned")
        assertTrue(h.observer.hasQueuedContentAt("/photo.jpg"), "and the old path is still owed, not orphaned")
    }

    @Test
    fun aRealmLockedMidDrainLeavesTheEntryForAMoveToRetargetAndTheUnlockToPublish() = runTest {
        // ⚠⚠ A drain that parks its emit on a locked realm must not hold a COPY of the entry's holder
        // and path: a move retargeting the entry meanwhile would be undone by the replay's journal
        // write, and the next drain would retire the moved file's upload as "no bytes".
        val h = harness()
        h.fdb.writeRawByteArray("/photo.jpg", photo)
        h.row("/photo.jpg", contentId = "cid-photo", bytes = photo)
        h.observer.emitFileContent(h.idFor("/photo.jpg"), propertyId, "/photo.jpg")
        assertEquals(1, journal(h.proxy).size, "precondition: the upload is parked in the journal")

        // The realm locks inside the pass — after the drain started on this entry.
        h.gateOpen = true
        h.port.beforeNextLookup = { h.syncManager.lock() }
        h.observer.drainDeferredContent()

        // While locked, the user moves the file: bytes, row and journal entry all follow it.
        h.fdb.moveFile("/photo.jpg", "/album/photo.jpg")
        h.retire("/photo.jpg")
        h.row("/album/photo.jpg", contentId = "cid-photo", bytes = photo)
        retargetPendingContentEmits(
            executionContext = h.proxy.executionContext,
            oldDataId = h.idFor("/photo.jpg"),
            newDataId = h.idFor("/album/photo.jpg"),
            oldFilePath = "/photo.jpg",
            newFilePath = "/album/photo.jpg",
        )

        h.syncManager.initialize("mock phrase")
        h.observer.reemitPendingLockedWork()
        assertEquals(
            listOf("/album/photo.jpg"),
            journal(h.proxy).map { it.filePath.get() },
            "the retargeted entry survived the unlock replay",
        )

        h.observer.drainDeferredContent()
        assertTrue(manifestContentRef("cid-photo") in h.sink.chunks, "and the moved file was published")
        assertTrue(journal(h.proxy).isEmpty())
    }

    @Test
    fun aRealmThatLocksDuringOneEntryLeavesTheNextEntryUntouched() = runTest {
        // ⚠ Pins `drainOne`'s locked-realm check: no verdict at all on a
        // locked realm. Both entries here would be RETIRED on an open one — bytes gone, no row naming
        // them — so a missing check shows as the second entry retired.
        val h = harness()
        listOf("/a.jpg", "/b.jpg").forEachIndexed { i, path ->
            val bytes = ByteArray(64) { (it + i).toByte() }
            h.fdb.writeRawByteArray(path, bytes)
            h.row(path, contentId = "cid-$i", bytes = bytes)
            h.observer.emitFileContent(h.idFor(path), propertyId, path)
            h.fdb.deleteFile(path)
            h.retire(path)
        }
        assertEquals(2, journal(h.proxy).size, "precondition: two parked uploads")

        // The realm locks during the first entry's lookup.
        h.gateOpen = true
        h.port.beforeNextLookup = { h.syncManager.lock() }
        h.observer.drainDeferredContent()

        assertEquals(1, journal(h.proxy).size, "the entry reached after the lock is kept, untouched")
    }

    private fun journalRef(proxy: TestProxy) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = proxy.tContext,
            type = PendingContentEmit.ID,
            relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
        ),
        isEntity = false,
        elementSchemaId = PendingContentEmit.ID,
    )

    private fun journal(proxy: TestProxy): List<PendingContentEmit> {
        return proxy.executionContext.database.getArrayData(journalRef(proxy))
            ?.filterIsInstance<PendingContentEmit>()
            ?.toList()
            ?: emptyList()
    }
}
