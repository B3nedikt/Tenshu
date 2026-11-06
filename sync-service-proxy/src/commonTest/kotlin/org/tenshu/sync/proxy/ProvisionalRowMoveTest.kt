package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.StillSyncingError
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.common.slice.FileSliceEntry
import org.tenshu.file.proxy.FileSliceSnapshotProducer
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.ProvisionalRows
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B5's provisional-row move guard (`content-plane-cr2-b5-spec.md` §4,
 * `content-plane-cr2-quorumless.md` §10.11 / §12.3): a row a joiner materialised from a relation-slice
 * snapshot (C8) has no `SyncMetadata` until the log reaches it, and names the content id **as of the
 * snapshot**. Moving it pushes that possibly stale id at a fresh HLC and tombstones the old path at a
 * fresh HLC too, so every peer follows the joiner's stale copy over the newer one.
 *
 * Real slice applier, real ingest, real `TombstoneRegistry`, real `MoveFile` and the real
 * [ProvisionalRowProbe]; the only stand-in is the emitter, which records the tombstones a move would push
 * and carries the probe exactly as `SyncObserver` does.
 *
 * ⭐ **Finding (2026-10-03), test first:** before the guard nothing blocked such a move. It succeeded,
 * the new row carried the snapshot's content id and the old path was tombstoned for every peer
 * ([withoutAProbe_aProvisionalRowMoves_andPushesItsStaleContent] keeps that measurement, as the
 * behaviour of an emitter that carries no probe).
 */
class ProvisionalRowMoveTest {

    private companion object {
        const val SOURCE = "/a.txt"
        const val TARGET = "/b.txt"
        const val STALE_CONTENT = "content-as-of-the-snapshot"
    }

    private val testProxy = TestProxy(
        schemaRegistry = createSyntheticTestRegistry(),
    ) { id, proxy ->
        fileServiceProxyImplementationFactory(id = id, fileDatabase = proxy.fileDatabase, tombstoneEmitter = emitter)
    }.apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceClientModule)
    }
    private val tContext = testProxy.tContext
    private val crypto = tContext.syncCryptoManager()
    private val syncManager = SyncManagerImpl(
        context = tContext,
        cryptoManager = crypto,
        storageDelegate = InMemorySecureStorageDelegate(),
    ).apply { initialize("mock phrase") }
    private val keys = requireNotNull(syncManager.getDerivedKeys())
    private val serializer = BinarySerializer()

    private val registry = TombstoneRegistry(
        executionContext = testProxy.executionContext,
        wireIdFor = WireIdResolver(syncManager)::ofOrNull,
        localEventHlc = { syncManager.hlcManager.tick() },
    )
    private val processor = SyncProcessor(
        wireIdResolver = WireIdResolver(syncManager),
        executionContext = testProxy.executionContext,
        syncManager = syncManager,
        cryptoManager = crypto,
        tombstoneRegistry = registry,
        mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) },
    )

    /** What a move pushes as tombstones, by local path. */
    private val tombstoned = mutableListOf<String>()

    /** Whether the emitter carries the probe, as `SyncObserver` does; off = the pre-guard behaviour. */
    private var probeWired = true
    private val probe = ProvisionalRowProbe(syncManager, registry)

    /** `SyncObserver`, reduced to what a move reads off it and what it pushes. */
    private val emitter: TombstoneEmitter = object : TombstoneEmitter {
        override val reapOrdering: SubtreeReapOrdering = registry
        override val provisionalRows: ProvisionalRows? get() = if (probeWired) probe else null
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? {
            tombstoned += targetEntityIdHash
            return "tombstone-${tombstoned.size}"
        }
        override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? {
            tombstoned += (targetEntityId as DataId.StringDataId).keyValue.get()
            return "tombstone-${tombstoned.size}"
        }
    }

    init {
        testProxy.executionContext.addDataObserver(registry)
        testProxy.executionContext.initFileService(tombstoneEmitterHolder = TombstoneEmitterHolder().also { it.emitter = emitter })
    }

    private fun id(path: String): DataId =
        DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)

    private fun row(path: String) = testProxy.database.getData(id(path))

    private fun contentIdAt(path: String): String? = row(path)?.let {
        (testProxy.executionContext.resolveProperty(it, propertyRef(File.ContentId.id)) as? TString)?.get()
    }

    /** A joiner part-way through C2's backward walk — the only state a slice snapshot is applied in. */
    private fun catchingUp() {
        syncManager.backfillCursor = 400
        syncManager.backfillTarget = 0
    }

    /** What `SliceSnapshotReader.bootstrapOnce` hands the file producer for the root slice. */
    private fun applyRootSnapshot(vararg paths: String) {
        val entries = paths.map { path ->
            FileSliceEntry(
                entityId = path,
                name = path.substringAfterLast('/'),
                isFolder = false,
                size = 3,
                contentId = STALE_CONTENT,
                contentHash = STALE_CONTENT,
                createdAtMillis = 1,
                lastModifiedAtMillis = 1,
            )
        }
        FileSliceSnapshotProducer().applySlice(
            testProxy.executionContext, Folder.Fields.FILES, "/", FileSliceEntry.encode(entries), isDeleted = { false },
        )
    }

    private fun ingestFile(path: String, atMillis: Long) = processor.processSyncBlock(
        org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = tContext,
            blockId = "file-$path-$atMillis",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(
                serializer.serialize(
                    DataBlockPayload.create(context = tContext, propertyId = null, value = File.create(tContext, path)),
                ),
                testProxy.versionStampBytes(),
            ),
            identityKey = keys.sharedIdentityKey,
        ),
    )

    private suspend fun move(source: String, target: String): Result<Data?> = runCatching {
        testProxy.executionContext.executeAsync<Data?>(
            operationId = MoveFile.ID,
            caller = null,
            params = mapOf(
                MoveFile.Params.SOURCE_PATH to source.toTString(tContext),
                MoveFile.Params.TARGET_PATH to target.toTString(tContext),
            ),
        )
    }

    private fun assertRefusedAsStillSyncing(result: Result<Data?>, what: String) {
        assertTrue(result.isFailure, "$what: the move must be refused")
        assertTrue(
            result.exceptionOrNull()?.message?.contains(StillSyncingError.ID) == true,
            "$what: refused with the typed StillSyncingError, not a generic failure: ${result.exceptionOrNull()?.message}",
        )
    }

    @Test
    fun withoutAProbe_aProvisionalRowMoves_andPushesItsStaleContent() = runTest {
        probeWired = false
        catchingUp()
        applyRootSnapshot(SOURCE)
        assertNotNull(row(SOURCE), "precondition: the snapshot materialised the row")
        assertNull(registry.versionOf(id(SOURCE)), "precondition: provisional — no SyncMetadata, no local write")

        val result = move(SOURCE, TARGET)

        assertTrue(result.isSuccess, "FINDING: nothing else blocks the move: ${result.exceptionOrNull()}")
        assertEquals(STALE_CONTENT, contentIdAt(TARGET), "the moved row carries the snapshot's content id")
        assertEquals(listOf(SOURCE), tombstoned, "and the old path is tombstoned for every peer")
    }

    @Test
    fun aProvisionalRowIsRefused_andNothingIsWrittenOrPushed() = runTest {
        catchingUp()
        applyRootSnapshot(SOURCE)

        assertRefusedAsStillSyncing(move(SOURCE, TARGET), "a provisional file")

        assertEquals(STALE_CONTENT, contentIdAt(SOURCE), "the row stays where it is, untouched")
        assertNull(row(TARGET), "nothing is created at the target")
        assertEquals(emptyList(), tombstoned, "and no tombstone is pushed")
    }

    @Test
    fun theSameRowMovesOnceTheLogHasReachedIt() = runTest {
        catchingUp()
        applyRootSnapshot(SOURCE)
        assertRefusedAsStillSyncing(move(SOURCE, TARGET), "before the real block")

        ingestFile(SOURCE, atMillis = 100L)
        assertNotNull(registry.versionOf(id(SOURCE)), "precondition: the real block wrote SyncMetadata")

        val result = move(SOURCE, TARGET)
        assertTrue(result.isSuccess, "a caught-up row moves: ${result.exceptionOrNull()}")
        assertNotNull(row(TARGET))
        assertEquals(listOf(SOURCE), tombstoned)
    }

    @Test
    fun aRowThisDeviceWroteItselfMoves_evenWhileCatchingUp() = runTest {
        catchingUp()
        testProxy.executionContext.saveData(File.create(tContext, SOURCE))
        assertNotNull(registry.versionOf(id(SOURCE)), "precondition: a local write stamps LocalWriteMetadata")

        val result = move(SOURCE, TARGET)

        assertTrue(result.isSuccess, "the user's own row is never provisional: ${result.exceptionOrNull()}")
    }

    @Test
    fun nothingIsProvisionalOnceCatchUpHasCompleted() = runTest {
        // ⚠ A row with no version at all is also what a pre-`LocalWriteMetadata` local row looks like.
        // Once the walk is complete every real block has been ingested, so refusing it then would
        // strand it for ever.
        catchingUp()
        applyRootSnapshot(SOURCE)
        syncManager.backfillCursor = 0L

        val result = move(SOURCE, TARGET)

        assertTrue(result.isSuccess, "a caught-up device refuses nothing: ${result.exceptionOrNull()}")
    }

    @Test
    fun aFolderHoldingAProvisionalRowIsRefused() = runTest {
        // A folder move re-keys every descendant, so a provisional child is pushed stale just the same.
        testProxy.executionContext.executeAsync<Data?>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/f".toTString(tContext)),
        )
        catchingUp()
        FileSliceSnapshotProducer().applySlice(
            testProxy.executionContext,
            Folder.Fields.FILES,
            "/f",
            FileSliceEntry.encode(
                listOf(
                    FileSliceEntry(
                        entityId = "/f/a.txt", name = "a.txt", isFolder = false, size = 3,
                        contentId = STALE_CONTENT, contentHash = STALE_CONTENT,
                        createdAtMillis = 1, lastModifiedAtMillis = 1,
                    ),
                ),
            ), isDeleted = { false },
        )
        assertNotNull(row("/f/a.txt"), "precondition: the provisional child is listed under /f")

        assertRefusedAsStillSyncing(move("/f", "/g"), "a folder with a provisional child")

        assertNotNull(row("/f/a.txt"), "the child stays where it was")
        assertNull(row("/g"), "and no destination folder is created")
        assertEquals(emptyList(), tombstoned)
    }
}
