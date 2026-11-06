package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.shareFolderIntoRealm
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3a of `docs/proposals/drive-shared-realm-file-model.md` (additive model):
 * `ShareFolderIntoRealm` mints a `SharedFile` per descendant of a personal folder,
 * locating the subtree in an already-bootstrapped shared realm. This is the real
 * **producer** the `SharedFileRelationMaintainer` was built for — the test drives
 * the op through the client → proxy chain and asserts the realm's per-parent
 * children tree the maintainer derived.
 */
class ShareFolderIntoRealmOperationTest {

    private val realm = "realm-owner-1"

    private fun env(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return tenshu.getService(FileService.ID)
    }

    /** The child placements of folder [parentFileId] in [realm], read from the store. */
    private fun children(proxy: TestProxy, parentFileId: String): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = DataId.StringDataId.create(
                    proxy.tContext,
                    SharedFile.ID,
                    SharedFile.compositeKey(realm, parentFileId),
                ),
                propertyId = SharedFile.CHILDREN_RELATION_ID,
            ),
            isEntity = false,
            elementSchemaId = SharedFile.ID,
        )
        return proxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("unexpected placement shape: $this")
    }

    private fun List<Data>.names(): Set<String> = map { it.field(SharedFile.Field.NAME) }.toSet()

    private fun Data.isFolder(): Boolean = when (this) {
        is Entity<*> -> getFieldValue<org.tenshu.core.TBoolean>(SharedFile.Field.IS_FOLDER).get()
        is Composite -> getField<org.tenshu.core.TBoolean>(SharedFile.Field.IS_FOLDER).get()
        else -> error("unexpected SharedFile shape: $this")
    }

    @Test
    fun sharesFolderSubtreeAsSharedFiles() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        // Build a personal folder tree: /shared/{a.txt, sub/{b.txt}}.
        assertTrue(fs.createFolder("/shared") is TResult.Success)
        assertTrue(fs.uploadFile("/shared", "a.txt", byteArrayOf(1, 2)) is TResult.Success)
        assertTrue(fs.createFolder("/shared/sub") is TResult.Success)
        assertTrue(fs.uploadFile("/shared/sub", "b.txt", byteArrayOf(3, 4)) is TResult.Success)

        val result = fs.shareFolderIntoRealm(folderPath = "/shared", realmId = realm)
        assertTrue(result is TResult.Success, "shareFolderIntoRealm should succeed")

        // The realm root holds a single wrapper node named for the shared folder — this
        // is what carries the folder's name into the realm for the joiner to read.
        val realmRoot = children(proxy, SharedFile.ROOT_PARENT)
        assertEquals(
            setOf("shared"),
            realmRoot.names(),
            "the shared folder is a single named wrapper node at the realm root",
        )
        val wrapper = realmRoot.single()
        assertTrue(wrapper.isFolder(), "the wrapper root is a folder")

        // The shared folder's direct children nest under the wrapper's fileId.
        val wrapperFileId = wrapper.field(SharedFile.Field.FILE_ID)
        val rootChildren = children(proxy, wrapperFileId)
        assertEquals(
            setOf("a.txt", "sub"),
            rootChildren.names(),
            "the shared folder's direct children nest under the wrapper node",
        )

        // The nested file is placed under its parent folder's minted fileId — the tree
        // is reconstructed by following parentFileId, not personal paths.
        val subNode = rootChildren.first { it.field(SharedFile.Field.NAME) == "sub" }
        val subFileId = subNode.field(SharedFile.Field.FILE_ID)
        assertEquals(
            setOf("b.txt"),
            children(proxy, subFileId).names(),
            "the nested file is placed under its parent's fileId",
        )

        // isFolder marks the navigable nodes: the subfolder is a folder, the files aren't.
        assertTrue(subNode.isFolder(), "a shared subfolder is marked isFolder")
        assertTrue(
            rootChildren.none { it.field(SharedFile.Field.NAME) == "a.txt" && it.isFolder() },
            "a shared file is not a folder",
        )
        assertTrue(
            children(proxy, subFileId).none { it.isFolder() },
            "the nested file b.txt is not a folder",
        )
    }

    @Test
    fun sharedFilesAreScopedToTheTargetRealm() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.createFolder("/shared") is TResult.Success)
        assertTrue(fs.uploadFile("/shared", "a.txt", byteArrayOf(1)) is TResult.Success)

        assertTrue(fs.shareFolderIntoRealm("/shared", realm) is TResult.Success)

        // The realm root holds the wrapper; the file nests under it.
        val wrapper = children(proxy, SharedFile.ROOT_PARENT).single()
        assertEquals("shared", wrapper.field(SharedFile.Field.NAME))
        assertEquals(setOf("a.txt"), children(proxy, wrapper.field(SharedFile.Field.FILE_ID)).names())

        // A different realm's root has its own (empty) children array — the realm id
        // is the `::` key prefix, so placements never cross realms.
        val otherRef = ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                parentEntity = DataId.StringDataId.create(
                    proxy.tContext,
                    SharedFile.ID,
                    SharedFile.compositeKey("other-realm", SharedFile.ROOT_PARENT),
                ),
                propertyId = SharedFile.CHILDREN_RELATION_ID,
            ),
            isEntity = false,
            elementSchemaId = SharedFile.ID,
        )
        assertTrue(
            proxy.executionContext.getArrayData(otherRef)?.toList().isNullOrEmpty(),
            "no placements leak into another realm",
        )
    }

    /** Records each emitFileContent call (targetKey, propertyId, filePath). */
    private class RecordingContentEmitter : FileContentSyncEmitter {
        val calls = mutableListOf<Triple<String, String, String>>()

        /** Recorded (contentId, targetRealmOwnerId, targetPropertyId) per cross-realm share. */
        val shareCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun emitFileContent(
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
        ): Boolean {
            calls += Triple((targetEntityId as DataId.StringDataId).keyValue.get(), propertyId, filePath)
            return true
        }

        override suspend fun shareContentToRealm(
            contentId: String,
            targetRealmOwnerId: String,
            targetPropertyId: String,
        ): Boolean {
            shareCalls += Triple(contentId, targetRealmOwnerId, targetPropertyId)
            return true
        }
    }

    @Test
    fun fansFileContentIntoTheRealmPerFile() = runTest {
        val emitter = RecordingContentEmitter()
        val holder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter }
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A", syncBlockEmitterHolder = holder)
        val fs = env(proxy).defaultFileSystem

        // /shared/{a.txt, sub/{b.txt}} — two files, one subfolder.
        assertTrue(fs.createFolder("/shared") is TResult.Success)
        assertTrue(fs.uploadFile("/shared", "a.txt", byteArrayOf(1, 2)) is TResult.Success)
        assertTrue(fs.createFolder("/shared/sub") is TResult.Success)
        assertTrue(fs.uploadFile("/shared/sub", "b.txt", byteArrayOf(3, 4)) is TResult.Success)

        // The uploads above also emit (personal-realm) content through the same holder;
        // clear so we observe only the share op's realm fan-out.
        emitter.calls.clear()
        assertTrue(fs.shareFolderIntoRealm("/shared", realm) is TResult.Success)

        // §3.4.1 3-2b: content is re-shared by CONTENT-ID (re-wrap CEK + copy encrypted
        // blobs), not by reading personal plaintext — so no per-file emitFileContent.
        assertTrue(emitter.calls.isEmpty(), "3-2b re-shares by contentId, not emitFileContent")

        // One cross-realm content re-share per *file* (folders carry no bytes).
        assertEquals(2, emitter.shareCalls.size, "exactly the two files get content re-shared")
        // Targeted at the shared realm, under the SharedFile data slot.
        assertTrue(
            emitter.shareCalls.all { it.second == realm && it.third == SharedFile.DATA_PROPERTY_ID },
            "each re-share targets the realm's SharedFile data slot",
        )
        // Each file's stable, non-empty personal contentId is the re-share handle; the two
        // files have distinct content so distinct ids.
        val contentIds = emitter.shareCalls.map { it.first }
        assertTrue(contentIds.all { it.isNotEmpty() }, "every re-share carries a contentId")
        assertEquals(2, contentIds.toSet().size, "the two distinct files have distinct contentIds")
    }
}
