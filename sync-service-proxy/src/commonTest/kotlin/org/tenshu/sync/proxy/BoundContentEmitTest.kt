package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The **bound** content emit (`FileContentSyncEmitter.journalFileContent` / `emitFileContent` with a
 * committed content id and hash) against the production [SyncObserver] (`contacts-app.md`
 * §9.10.3–§9.10.4).
 *
 * The producer it exists for, the contacts photo writer, does not hold its row against ingest: a
 * peer's row naming other content can land between its commit and its journal, its upload, or the
 * replay of either after a locked window. Every one of those must carry the content the producer
 * committed, never whatever the row names when it runs. Every name is made up.
 */
class BoundContentEmitTest {

    private val propertyId = "${SyntheticTestData.ID}/photos"

    /** One holder row whose content the test moves, the way an ingested peer row does. */
    private class RowPort : ExternalPropertyWriter {
        var contentId: String? = null
        var contentHash: String? = null
        var path: String? = null
        override fun supports(propertyId: String): Boolean = true
        override fun write(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String, value: Data) = Unit
        override fun resolveExternalPath(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) = path
        override fun resolveContentId(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) = contentId
        override fun resolveContentHash(executionContext: ExecutionContext, targetEntityId: DataId, propertyId: String) = contentHash
    }

    private class Harness(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val files: InMemoryFileDatabase,
        val sink: InMemoryContentBlobStore,
        val observer: SyncObserver,
        val port: RowPort,
    ) {
        var gateOpen = true
        val holder: DataId = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, "vcard:made-up")

        /** The row names [contentId] at [path] (the hash of [bytes]), as an ingested row would leave it. */
        fun rowNames(contentId: String, path: String, bytes: ByteArray) {
            port.contentId = contentId
            port.path = path
            port.contentHash = wholeFileHashOf(bytes)
        }

        fun manifest(contentId: String): FileContentManifestPayload? {
            val cipher = sink.chunks[manifestContentRef(contentId)] ?: return null
            val plain = proxy.tContext.syncCryptoManager().decrypt(cipher, syncManager.getDerivedKeys()!!.sharedIdentityKey)
            val parser = BinaryParser(proxy.tContext.schemaProvider(), proxy.tContext.dataFactory())
            return parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes) as FileContentManifestPayload
        }

        fun journal(): List<PendingContentEmit> =
            proxy.executionContext.database.getArrayData(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.tContext,
                        type = PendingContentEmit.ID,
                        relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
                    ),
                    isEntity = false,
                    elementSchemaId = PendingContentEmit.ID,
                )
            )?.filterIsInstance<PendingContentEmit>()?.toList().orEmpty()
    }

    private fun harness(): Harness {
        val port = RowPort()
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to port),
        )
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val files = InMemoryFileDatabase()
        val sink = InMemoryContentBlobStore(proxy.tContext)
        lateinit var harness: Harness
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = proxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = sink,
            fileDatabase = files,
            contentGate = SyncGate { harness.gateOpen },
        )
        harness = Harness(proxy, syncManager, files, sink, observer, port)
        return harness
    }

    private val c1Bytes = ByteArray(3_000) { (it % 251).toByte() }
    private val c2Bytes = ByteArray(2_000) { ((it * 7) % 253).toByte() }

    @Test
    fun aBoundJournalAndEmitCarryTheCommittedContentWhileTheRowNamesAnother() = runTest {
        val h = harness()
        h.files.writeRawByteArray("/c1", c1Bytes)
        // The save committed C1; a peer's row naming C2 has landed since.
        h.rowNames("c2", "/c2", c2Bytes)

        h.gateOpen = false
        h.observer.journalFileContent(h.holder, propertyId, "/c1", "c1", wholeFileHashOf(c1Bytes))
        assertEquals(listOf("c1"), h.journal().map { it.contentId.get() }, "journalled under the committed content")

        h.gateOpen = true
        assertTrue(h.observer.emitFileContent(h.holder, propertyId, "/c1", "c1", wholeFileHashOf(c1Bytes)))

        val manifest = assertNotNull(h.manifest("c1"), "published at the committed content's address")
        assertEquals("c1", manifest.fileId.get())
        assertEquals(wholeFileHashOf(c1Bytes), manifest.wholeFileHash.get())
        // ⚠ The hash is the caller's, so it alone proves nothing about the bytes streamed.
        assertEquals(c1Bytes.size.toLong(), manifest.totalSize.get(), "C1's bytes were streamed, not C2's")
        assertEquals(null, h.manifest("c2"), "nothing landed under the content this device never held")
    }

    @Test
    fun twoBoundEmitsHeldWhileLockedEachReplayTheirOwnBytes() = runTest {
        val h = harness()
        h.files.writeRawByteArray("/c1", c1Bytes)
        h.files.writeRawByteArray("/c2", c2Bytes)
        h.syncManager.lock()
        // Two photo saves while locked: C1, then C2 (which the row names now).
        h.rowNames("c1", "/c1", c1Bytes)
        assertFalse(h.observer.emitFileContent(h.holder, propertyId, "/c1", "c1", wholeFileHashOf(c1Bytes)))
        h.rowNames("c2", "/c2", c2Bytes)
        assertFalse(h.observer.emitFileContent(h.holder, propertyId, "/c2", "c2", wholeFileHashOf(c2Bytes)))

        h.syncManager.initialize("mock phrase")
        h.observer.reemitPendingLockedWork()

        val m1 = assertNotNull(h.manifest("c1"), "C1's replay published C1")
        val m2 = assertNotNull(h.manifest("c2"), "C2's replay published C2")
        assertEquals(wholeFileHashOf(c1Bytes), m1.wholeFileHash.get(), "C1's manifest names C1's bytes")
        assertEquals(wholeFileHashOf(c2Bytes), m2.wholeFileHash.get(), "C2's manifest names C2's bytes")
        // ⚠ The hashes are the callers'; the sizes say which file each replay actually streamed.
        assertEquals(c1Bytes.size.toLong(), m1.totalSize.get(), "C1's replay streamed /c1")
        assertEquals(c2Bytes.size.toLong(), m2.totalSize.get(), "C2's replay streamed /c2")
    }

    @Test
    fun aJournalledOnlyPhotoIsPublishedByItsBytesAfterTheRowMovesOn() = runTest {
        val h = harness()
        h.files.writeRawByteArray("/c1", c1Bytes)
        h.rowNames("c1", "/c1", c1Bytes)
        // A Wi-Fi-only policy on cellular: the upload is only journalled.
        h.gateOpen = false
        assertFalse(h.observer.emitFileContent(h.holder, propertyId, "/c1", "c1", wholeFileHashOf(c1Bytes)))
        // A peer's row naming C2 arrives — C1 may still come back (an offline last-writer edit).
        h.rowNames("c2", "/c2", c2Bytes)

        h.gateOpen = true
        h.observer.drainDeferredContent()

        val m1 = assertNotNull(h.manifest("c1"), "published by its hash, not retired because the row moved on")
        assertEquals(c1Bytes.size.toLong(), m1.totalSize.get())
        assertTrue(h.journal().isEmpty(), "and the entry retires because it succeeded")
    }
}
