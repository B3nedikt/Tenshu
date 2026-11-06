package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which stored paths still name a photograph the user has — the realm half of P0f-a's read filter
 * (`media-processing-pipeline-sweep.md` F0), against a real file service.
 *
 * ⚠⚠ What this answers decides whether a deleted photograph's face template keeps shaping People
 * until the next index pass, so every way a photo leaves the user's library is a case here.
 */
class LivePhotoPathsTest {

    private fun serviceOf(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.upload(parent: String, name: String) {
        assertTrue(
            defaultFileSystem.uploadFile(parent, name, byteArrayOf(1, 2, 3)) is TResult.Success,
            "seeding $parent/$name should succeed",
        )
    }

    @Test
    fun onlyAnUntrashedFileThatExistsIsLive() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = serviceOf(proxy)
        service.defaultFileSystem.createFolder("/Trash")
        service.upload("/", "kept.jpg")
        service.upload("/", "deleted.jpg")
        service.upload("/", "trashed.jpg")

        assertTrue(service.defaultFileSystem.deleteFile("/deleted.jpg") is TResult.Success)
        assertTrue(service.defaultFileSystem.moveFile("/trashed.jpg", "/Trash/trashed.jpg") is TResult.Success)

        val live = proxy.executionContext.livePathsAmong(
            listOf("/kept.jpg", "/deleted.jpg", "/trashed.jpg", "/Trash/trashed.jpg", "/never-existed.jpg"),
        )

        // ⚠ The trashed copy EXISTS at its new path — it is the trash rule, not existence, that
        // keeps it out, which is `collectImagePaths`' rule too.
        assertEquals(setOf("/kept.jpg"), live)
    }

    /**
     * ⚠⚠ P0f-b PR-1's failed-read rule. A store that answers "no row" for everything — here, one
     * whose rows are gone from under the file service, the way a wipe in progress looks — must not
     * be read as "every photograph is dead": the read filter would hide every face, and the
     * reconcile would erase them. It throws instead.
     */
    @Test
    fun aStoreThatCannotSeeTheRealmRootRefusesToAnswer() = runTest {
        val store = InMemoryObjectStore()
        val proxy = createFileProxy(backgroundScope, objectStore = store)
        val service = serviceOf(proxy)
        service.upload("/", "kept.jpg")
        assertEquals(setOf("/kept.jpg"), proxy.executionContext.livePathsAmong(listOf("/kept.jpg")), "precondition")

        store.clear()

        assertFailsWith<LivenessUnanswered> {
            proxy.executionContext.livePathsAmong(listOf("/kept.jpg"))
        }
    }

    @Test
    fun askingAboutNothingNeedsNoRoot() = runTest {
        val store = InMemoryObjectStore()
        val proxy = createFileProxy(backgroundScope, objectStore = store)
        store.clear()

        assertEquals(emptySet(), proxy.executionContext.livePathsAmong(emptyList()))
    }
}
