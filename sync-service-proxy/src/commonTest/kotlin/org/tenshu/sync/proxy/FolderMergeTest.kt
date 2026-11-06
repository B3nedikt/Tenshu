package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FolderMergeTest {

    @Test
    fun testFolderMerging() = runTest {
        try {
            println("TEST: Start")

            // CHECK SUBTYPING
            val fileSchema = File.Schema
            val traitSchema = org.tenshu.core.file.FileDataIdProvider.Schema
            println("TEST: File ID: ${fileSchema.id.get()}")
            println("TEST: Trait ID: ${traitSchema.id.get()}")
            println("TEST: Is subtype: ${traitSchema.isSubtypeOf(org.tenshu.core.entity.DataIdProvider.ID)}")

            val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
            testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
            testProxy.schemaRegistry.registerConfig(fileServiceCommonModule)
            println("TEST: Config registered")

            val tContext = testProxy.tContext
            val crypto = tContext.syncCryptoManager()
            val syncManager = SyncManagerImpl(
                context = tContext,
                cryptoManager = crypto,
                storageDelegate = InMemorySecureStorageDelegate()
            )
            syncManager.initialize("mock phrase")

            val keys = syncManager.getDerivedKeys()
            assertNotNull(keys, "Derived keys should not be null after initialization")
            println("TEST: Keys derived")

            val mergerRegistry = SemanticMergerRegistry()
            mergerRegistry.register(FolderMerger())

            val processor = SyncProcessor(
                wireIdResolver = WireIdResolver(syncManager),
                executionContext = testProxy.executionContext,
                syncManager = syncManager,
                cryptoManager = crypto,
                mergerRegistry = mergerRegistry
            )

            // 1. Initial State: Folder with File A
            println("TEST: Creating fileA")
            val fileA = File.create(tContext, "/A")
            println("TEST: Creating folderDto")
            val folderDto = Folder.create(tContext, "/", files = listOf(fileA))
            println("TEST: Saving initialFolder")
            testProxy.database.saveData(folderDto)
            println("TEST: Initial state saved")

            // 2. Incoming SyncBlock: Folder with File B (newer HLC)
            val fileB = File.create(tContext, "/B")
            val incomingFolderDto = Folder.create(tContext, "/", files = listOf(fileB))

            val serializer = BinarySerializer()
            val newerHlc = HybridLogicalClock.create(tContext, 2000L, 0, "device-remote")
            // Wrap in DataBlockPayload — propertyId now lives inside the
            // encrypted payload, not on the wire.
            val payload = org.tenshu.sync.DataBlockPayload.create(
                context = tContext,
                propertyId = null,
                value = incomingFolderDto
            )
            // Envelope bound as AAD (sync-envelope-authentication §3.2).
            val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
                crypto = crypto,
                context = tContext,
                blockId = "block-2",
                ownerId = "user-1",
                hlc = newerHlc,
                targetEntityIdHash = null,
                plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
                identityKey = keys.sharedIdentityKey,
            )
            println("TEST: Sync block created")

            // 3. Process
            println("TEST: Processing...")
            processor.processSyncBlock(block)
            println("TEST: Processed")

            // 4. Verify: Merged Folder should have BOTH File A and File B
            val dataId = testProxy.executionContext.getDataId(folderDto)!!
            val finalFolder = testProxy.database.getData(dataId) as Folder

            // Use context-aware resolution for verification
            val finalFiles =
                testProxy.executionContext.resolveListField(finalFolder, Folder.Fields.FILES)
                    .filterIsInstance<File>()

            println("TEST: Final files count: ${finalFiles.size}")
            finalFiles.forEach { println("TEST: File: ${it.path.get()}") }

            assertEquals(2, finalFiles.size, "Folder should have 2 files after merge")
            assertTrue(finalFiles.any { it.path.get() == "/A" }, "Should contain File A")
            assertTrue(finalFiles.any { it.path.get() == "/B" }, "Should contain File B")
            println("TEST: Done")
        } catch (e: Throwable) {
            println("TEST: FAILED with $e")
            e.printStackTrace()
            throw e
        }
    }
}
