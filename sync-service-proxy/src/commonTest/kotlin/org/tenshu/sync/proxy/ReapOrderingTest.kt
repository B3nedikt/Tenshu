package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * ⭐ CR-2 B3b, `content-plane-cr2-quorumless.md` §10.12 Q4 (revised and accepted 2026-10-02): a deleted
 * folder's subtree is reaped by **one ordering point, the folder tombstone's HLC T**, and that point
 * survives a restart (B2 audit gap 11).
 *
 * Real ingest, real `DeletedFolderSubtreeReaper`, real `TombstoneRegistry`; the only stand-in is the
 * emitter that carries the registry to the reaper, as `SyncObserver` does in production.
 *
 * ⚠⚠ What each test guards:
 *  - the reaper reaps a child at or below T and **keeps** one newer than T (it used to reap every
 *    child, each device at its own "now", so devices disagreed about late blocks);
 *  - a reaped child refuses a stale block **after a restart** (the in-memory marker it replaces died
 *    with the process, and an absent row then accepted any block);
 *  - a reaped child **accepts** a block above T in the same session, as every other device does (a
 *    "now" marker would refuse it here only).
 */
class ReapOrderingTest {

    private companion object {
        const val FOLDER = "/f"
        const val OLD = "/f/old.txt"
        const val NEW = "/f/new.txt"
        const val NONE = "/f/placeholder.txt"
        const val T = 500L
    }

    private val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
        schemaRegistry.registerConfig(syncServiceCommonModule)
        schemaRegistry.registerConfig(fileServiceCommonModule)
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

    private fun newRegistry() = TombstoneRegistry(
        executionContext = testProxy.executionContext,
        wireIdFor = WireIdResolver(syncManager)::ofOrNull,
        localEventHlc = { syncManager.hlcManager.tick() },
    )

    private fun newProcessor(registry: TombstoneRegistry) = SyncProcessor(
        wireIdResolver = WireIdResolver(syncManager),
        executionContext = testProxy.executionContext,
        syncManager = syncManager,
        cryptoManager = crypto,
        tombstoneRegistry = registry,
        mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) },
    )

    /** The emitter `SyncObserver` is in production, reduced to what the reaper reads off it. */
    private fun emitterFor(ordering: SubtreeReapOrdering) = object : TombstoneEmitter {
        override val reapOrdering: SubtreeReapOrdering = ordering
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? = null
        override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? = null
    }

    private val holder = TombstoneEmitterHolder()
    private var registry = newRegistry().also {
        testProxy.executionContext.addDataObserver(it)
        holder.emitter = emitterFor(it)
        testProxy.executionContext.initFileService(tombstoneEmitterHolder = holder)
    }
    private var processor = newProcessor(registry)

    private fun id(path: String): DataId =
        DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)

    private fun row(path: String) = testProxy.database.getData(id(path))

    private fun hlc(atMillis: Long) = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote")

    private fun block(value: org.tenshu.core.Data, atMillis: Long, blockId: String): SyncBlock =
        EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = hlc(atMillis),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(
                serializer.serialize(DataBlockPayload.create(context = tContext, propertyId = null, value = value)),
                testProxy.versionStampBytes(),
            ),
            identityKey = keys.sharedIdentityKey,
        )

    private fun ingestFile(path: String, atMillis: Long) =
        processor.processSyncBlock(block(File.create(tContext, path), atMillis, "file-$path-$atMillis"))

    private fun ingestFolder(children: List<String>, atMillis: Long) = processor.processSyncBlock(
        block(Folder.create(tContext, FOLDER, files = children.map { File.create(tContext, it) }), atMillis, "folder-$atMillis"),
    )

    private fun ingestFolderTombstone(atMillis: Long) = processor.processDeleteBlock(
        EnvelopeAuth.tagTombstone(
            tContext,
            DeleteBlock.create(
                context = tContext,
                blockId = "delete-$FOLDER-$atMillis",
                ownerId = "user-1",
                hlc = hlc(atMillis),
                targetEntityIdHash = id(FOLDER).wireIdIn(syncManager),
                targetEntityType = File.ID,
            ),
            keys.sharedIdentityKey,
        ),
    )

    /** Shared setup: a folder holding a child older than T and one newer, then the folder's tombstone at T. */
    private fun reapAtT() {
        ingestFolder(listOf(OLD, NEW, NONE), atMillis = 50L)
        ingestFile(OLD, atMillis = 100L)
        ingestFile(NEW, atMillis = 900L) // written after the delete, by a device that had not seen it
        assertNotNull(row(OLD), "precondition: $OLD is present")
        assertNotNull(row(NEW), "precondition: $NEW is present")

        ingestFolderTombstone(atMillis = T)
        assertNull(row(FOLDER), "the folder's own tombstone deletes it")
    }

    @Test
    fun aChildAtOrBelowTIsReaped_andAChildNewerThanTIsKept() {
        reapAtT()

        assertNull(row(OLD), "$OLD (version 100 ≤ T=$T) is reaped")
        assertNotNull(row(NEW), "$NEW (version 900 > T=$T) is kept — a live row on every device")
        assertEquals(
            T,
            registry.deletionPointOf(id(OLD))?.physicalTime?.get(),
            "the reaped child records the folder's T, not this device's 'now'",
        )
        assertEquals(T, registry.deletionPointOf(id(FOLDER))?.physicalTime?.get(), "and the folder's point is its tombstone")
    }

    @Test
    fun aPlaceholderChildWithNoVersionCountsAsAtOrBelowT() {
        reapAtT()

        assertNull(row(NONE), "a member with no block of its own (a placeholder) is reaped")
    }

    @Test
    fun aReapedChildAcceptsABlockAboveT_inTheSameSession_asEveryOtherDeviceDoes() {
        reapAtT()

        // ⚠ 600 is above T but far below this device's clock (wall time). A "now" marker would refuse it
        // here while every other device accepts it — the split the revised Q4 removes.
        ingestFile(OLD, atMillis = 600L)

        assertNotNull(row(OLD), "a block above T is accepted, consistently with every other device")
    }

    @Test
    fun aReapedChildRefusesAStaleBlockAfterARestart() {
        reapAtT()

        // ---- "Restart": a fresh registry and processor over the same store; in-memory markers gone.
        registry = newRegistry()
        processor = newProcessor(registry)

        ingestFile(OLD, atMillis = 400L)
        assertNull(row(OLD), "a block at or below T is refused after a restart too (audit gap 11)")

        ingestFile(OLD, atMillis = 600L)
        assertNotNull(row(OLD), "and one above T is still accepted")
    }
}
