package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `FolderMerger` is a **union** by path (see [FolderMergeTest], which pins that
 * behaviour), so it has no way to say "this child was removed". A peer that writes to a
 * folder before it learns one of the children was deleted therefore emits a legitimately
 * *newer* block that still lists the dead child, and the union puts it back — a row that
 * lists but cannot be opened, which is how a deleted Synced-Folders backup destination
 * came back empty on the other device (`drive-synced-folders.md` §3.9).
 *
 * ⚠ The two tests here are a pair and neither is sufficient alone. The first pins that a
 * dead child is purged; the second pins that a **re-created** one is not — the tombstone
 * for an id survives a legitimate higher-HLC re-create (Drive's own auto-backup does this
 * every time `ensureBackupDestination` rebuilds a chain a peer deleted), so a purge keyed
 * on "tombstoned" alone would delete live rows. Only "tombstoned **and** absent from the
 * store" distinguishes them.
 */
class FolderTombstonedMemberPurgeTest {

    @Test
    fun deadChildIsNotResurrectedByALaterParentBlock() = runTest {
        val fixture = Fixture()

        // The child is tombstoned and absent — this device has already applied the delete.
        fixture.ingestTombstoneFor("/gone", atMillis = 2000L)

        // A peer that had not seen the delete writes the parent and still lists the child.
        fixture.ingestParentListing(listOf("/kept", "/gone"), atMillis = 3000L)

        val children = fixture.currentChildPaths()
        assertTrue(children.contains("/kept"), "The live child must survive: $children")
        assertEquals(
            listOf("/kept"),
            children,
            "A tombstoned child must not be re-added by a union merge — it points at an " +
                "entity every device has deleted, so it lists and cannot be opened",
        )
    }

    @Test
    fun aRecreatedChildIsKeptEvenThoughItsIdWasTombstoned() = runTest {
        val fixture = Fixture()

        fixture.ingestTombstoneFor("/rebuilt", atMillis = 2000L)
        // ...and then it is genuinely re-created (higher HLC), exactly as Drive's
        // auto-backup re-creates a destination chain a peer deleted.
        fixture.storeEntityFor("/rebuilt")

        fixture.ingestParentListing(listOf("/kept", "/rebuilt"), atMillis = 3000L)

        assertTrue(
            fixture.currentChildPaths().contains("/rebuilt"),
            "A tombstone does not retire an id: an entity present in the store is alive and " +
                "must not be purged, or the purge hides live rows instead of dead ones",
        )
    }

    private class Fixture {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(fileServiceCommonModule)
        }
        val tContext = testProxy.tContext
        private val crypto = tContext.syncCryptoManager()
        private val syncManager = SyncManagerImpl(
            context = tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }

        private val keys = requireNotNull(syncManager.getDerivedKeys())
        private val serializer = BinarySerializer()

        private val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            mergerRegistry = SemanticMergerRegistry().apply { register(FolderMerger()) },
        )

        private val rootDto = Folder.create(tContext, "/", files = listOf(File.create(tContext, "/kept")))

        init {
            testProxy.database.saveData(rootDto)
        }

        fun fileDataId(path: String): DataId = DataId.StringDataId.create(
            context = tContext,
            type = File.ID,
            keyValue = path,
        )

        fun storeEntityFor(path: String) {
            testProxy.database.saveData(File.create(tContext, path))
        }

        fun ingestTombstoneFor(path: String, atMillis: Long) {
            processor.processDeleteBlock(
                org.tenshu.sync.common.EnvelopeAuth.tagTombstone(
                    tContext,
                    DeleteBlock.create(
                        context = tContext,
                        blockId = "delete-$path",
                        ownerId = "user-1",
                        hlc = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote"),
                        targetEntityIdHash = fileDataId(path).wireIdIn(syncManager),
                        targetEntityType = File.ID,
                    ),
                    keys.sharedIdentityKey,
                )
            )
        }

        /** A peer's newer state of `/`, listing [childPaths] as its children. */
        fun ingestParentListing(childPaths: List<String>, atMillis: Long) {
            val incoming = Folder.create(
                context = tContext,
                path = "/",
                files = childPaths.map { File.create(tContext, it) },
            )
            val payload = DataBlockPayload.create(
                context = tContext,
                propertyId = null,
                value = incoming,
            )
            processor.processSyncBlock(
                org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
                    crypto = crypto,
                    context = tContext,
                    blockId = "block-$atMillis",
                    ownerId = "user-1",
                    hlc = HybridLogicalClock.create(tContext, atMillis, 0, "device-remote"),
                    targetEntityIdHash = null,
                    plaintext = BlockPayloadEnvelope.seal(
                        serializer.serialize(payload),
                        testProxy.versionStampBytes(),
                    ),
                    identityKey = keys.sharedIdentityKey,
                )
            )
        }

        fun currentChildPaths(): List<String> {
            val rootId = assertNotNull(testProxy.executionContext.getDataId(rootDto))
            val stored = assertNotNull(testProxy.database.getData(rootId) as? Folder)
            return testProxy.executionContext
                .resolveListField(stored, Folder.Fields.FILES)
                .filterIsInstance<File>()
                .map { it.path.get() }
        }
    }
}
