package org.tenshu.file.proxy.integration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.file.client.modificationsForFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-file history accessor: [FileService.modificationsForFile] filters the
 * realm-wide [FileService.modifications] log down to one file by `fileId`,
 * driven end-to-end through the client → proxy chain (the same `equalTo`
 * predicate idiom production `getRemoteFile` uses). Sorting / projection are the
 * call site's job; this just proves the filter resolves the right rows.
 */
class ModificationsForFileTest {

    private fun Data.fileId(): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(FileModification.Field.FILE_ID).get()
        is Composite -> getField<TString>(FileModification.Field.FILE_ID).get()
        else -> error("Unexpected modification shape: $this")
    }

    @Test
    fun modificationsForFile_returnsOnlyThatFilesHistory() = runTest {
        val testProxy = createFileProxy(backgroundScope, deviceId = "device-A")
        val executor = LocalTaskExecutor(testProxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = testProxy.tContext,
        )
        val fileService = env.getService<FileService>(FileService.ID)

        assertTrue(
            fileService.defaultFileSystem.uploadFile("/", "a.txt", byteArrayOf(1)) is TResult.Success
        )
        assertTrue(
            fileService.defaultFileSystem.uploadFile("/", "b.txt", byteArrayOf(2)) is TResult.Success
        )

        // The realm-wide log has both files' Created records.
        val all = fileService.modifications.flow().first()
        assertTrue(all is TResult.Success, "modifications query should succeed: $all")
        assertEquals(
            setOf("/a.txt", "/b.txt"),
            all.get().toList().map { it.fileId() }.toSet(),
            "realm-wide log spans every file"
        )

        // The per-file accessor returns only /a.txt's record.
        val aHistory = fileService.modificationsForFile("/a.txt").flow().first()
        assertTrue(aHistory is TResult.Success, "per-file query should succeed: $aHistory")
        val rows = aHistory.get().toList()
        assertEquals(1, rows.size, "only /a.txt's history")
        assertEquals("/a.txt", rows.single().fileId())
    }
}
