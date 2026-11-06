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
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileService
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.listSharedFolder
import org.tenshu.core.file.system.shareFolderIntoRealm
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3c of `docs/proposals/drive-shared-realm-file-model.md`: `ListSharedFolder`
 * is the shared-realm browse op — list a realm's `SharedFile` children at a given
 * parent, drilling by `fileId` (realm root = `SharedFile.ROOT_PARENT`). Drives the
 * op through the client → proxy chain over a tree produced by `ShareFolderIntoRealm`.
 */
class ListSharedFolderOperationTest {

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

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("unexpected SharedFile shape: $this")
    }

    private fun List<Data>.names(): Set<String> = map { it.field(SharedFile.Field.NAME) }.toSet()

    @Test
    fun listsRealmTreeByParentDrillingIn() = runTest {
        val proxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val fs = env(proxy).defaultFileSystem

        assertTrue(fs.createFolder("/shared") is TResult.Success)
        assertTrue(fs.uploadFile("/shared", "a.txt", byteArrayOf(1, 2)) is TResult.Success)
        assertTrue(fs.createFolder("/shared/sub") is TResult.Success)
        assertTrue(fs.uploadFile("/shared/sub", "b.txt", byteArrayOf(3, 4)) is TResult.Success)
        assertTrue(fs.shareFolderIntoRealm("/shared", realm) is TResult.Success)

        // Realm root: the single wrapper node named for the shared folder.
        val rootResult = fs.listSharedFolder(realm, SharedFile.ROOT_PARENT)
        assertTrue(rootResult is TResult.Success, "listSharedFolder(root) should succeed")
        val realmRoot = rootResult.value!!.toList()
        assertEquals(setOf("shared"), realmRoot.names(), "realm root lists the shared folder wrapper")

        // Drill into the wrapper: the shared folder's direct children.
        val wrapperFileId = realmRoot.single().field(SharedFile.Field.FILE_ID)
        val root = (fs.listSharedFolder(realm, wrapperFileId) as TResult.Success).value!!.toList()
        assertEquals(setOf("a.txt", "sub"), root.names(), "the wrapper lists the shared folder's children")

        // Drill into the subfolder by its fileId.
        val subFileId = root.first { it.field(SharedFile.Field.NAME) == "sub" }
            .field(SharedFile.Field.FILE_ID)
        val sub = (fs.listSharedFolder(realm, subFileId) as TResult.Success).value!!.toList()
        assertEquals(setOf("b.txt"), sub.names(), "drilling into the subfolder lists its child")
    }

    @Test
    fun emptyForUnknownRealmOrParent() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val fs = env(proxy).defaultFileSystem

        val res = fs.listSharedFolder("never-shared-realm", SharedFile.ROOT_PARENT)
        assertTrue(res is TResult.Success)
        assertTrue(res.value!!.toList().isEmpty(), "an unknown realm lists nothing")
    }
}
