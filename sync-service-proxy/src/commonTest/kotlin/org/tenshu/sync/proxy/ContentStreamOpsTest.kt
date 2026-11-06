package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.OnDemandResolutionImpl
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2b S2b (`drive-streaming-preview.md` §3.5) — the pull op surface's integration logic in
 * [OnDemandResolutionImpl]: open a stream from a relay-served manifest (the same realm-routed
 * fetch the reassemble path uses, but captured into a session), read byte ranges, and close.
 *
 * The op impls (`OpenContentStreamImplementation` etc.) are thin param-plumbing over these three
 * methods; this drives them directly via a [MockSyncRelay] seeded with the emitted manifest, so
 * the open→read→close round-trip is exercised end-to-end without the full IPC stack.
 */
class ContentStreamOpsTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var fileDatabase: InMemoryFileDatabase
    private lateinit var contentSink: InMemoryContentBlobStore
    private lateinit var observer: SyncObserver

    private val chunkSize = FileContentChunkSizeBytes
    private val propertyId = "tenshu/file/file/data"

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")

        fileDatabase = InMemoryFileDatabase()
        contentSink = InMemoryContentBlobStore(testProxy.tContext)
        observer = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = contentSink,
            fileDatabase = fileDatabase
        )
    }

    private fun fileEntityId(path: String): DataId =
        DataId.StringDataId.create(
            context = testProxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = path
        )

    /** Emit [bytes] at [path] and return the manifest SyncBlock queued in pendingBlocks. */
    private suspend fun emitManifestBlock(path: String, bytes: ByteArray): SyncBlock {
        fileDatabase.writeRawByteArray(path, bytes)
        assertTrue(observer.emitFileContent(fileEntityId(path), propertyId, path))
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        return testProxy.executionContext.getArrayData(ref)
            ?.toList()?.filterIsInstance<SyncBlock>()?.first()
            ?: error("no manifest block emitted")
    }

    /** A resolver wired with a relay serving [manifestBlock] + a real personal processor. */
    private fun resolverServing(manifestBlock: SyncBlock): OnDemandResolutionImpl {
        val relay = MockSyncRelay(testProxy.tContext).apply { seedEntityBlocks(manifestBlock) }
        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            manifestApplier = FileContentManifestApplier(
                syncManager = syncManager,
                cryptoManager = testProxy.tContext.syncCryptoManager(),
                fileDatabase = fileDatabase,
                contentBlobStore = contentSink
            )
        )
        return OnDemandResolutionImpl(
            relay = relay,
            syncProcessorHolder = SyncProcessorHolder().apply { this.processor = processor }
        )
    }

    @Test
    fun open_read_close_roundTrip() = runTest {
        val size = (chunkSize * 2) + 4_096 // 3 chunks
        val original = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
        val resolver = resolverServing(emitManifestBlock("/movie.mp4", original))
        val entityId = fileEntityId("/movie.mp4")

        val opening = resolver.openContentStream(testProxy.executionContext, entityId, propertyId)
        assertTrue(opening != null, "stream opens from the relay-served manifest")
        assertEquals(size.toLong(), opening.totalSize)

        // Whole file via the handle.
        assertContentEquals(original, resolver.readContentRange(opening.handle, 0, size))
        // A boundary-spanning sub-range.
        assertContentEquals(
            original.copyOfRange(chunkSize - 50, chunkSize + 50),
            resolver.readContentRange(opening.handle, (chunkSize - 50).toLong(), 100)
        )

        // Close → handle no longer reads.
        assertTrue(resolver.closeContentStream(opening.handle))
        assertNull(resolver.readContentRange(opening.handle, 0, 16), "read after close is null")
        assertEquals(false, resolver.closeContentStream(opening.handle), "double close idempotent-false")
    }

    @Test
    fun open_unknownProperty_isNull() = runTest {
        val original = ByteArray(chunkSize + 10) { it.toByte() }
        val resolver = resolverServing(emitManifestBlock("/x.bin", original))
        // No manifest matches a different propertyId ⇒ no session.
        assertNull(
            resolver.openContentStream(testProxy.executionContext, fileEntityId("/x.bin"), "tenshu/file/file/other"),
            "no matching manifest ⇒ null opening"
        )
    }
}
