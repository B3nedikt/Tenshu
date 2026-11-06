package app.tenshu.drive.integration

import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage for [FilesRepository.recentFiles] over the real in-process
 * proxy chain (file-service-history Phase 6): `root.files.traverse()` reaches
 * files in nested folders, `sortByDescending(File.LastModifiedAt)` + `take`
 * bound the result. The cross-folder reach is the new behaviour vs the
 * folder-scoped `getFiles`.
 */
class RecentFilesIntegrationTest {

    private lateinit var filesRepository: FilesRepository

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        filesRepository = appKoin.get()
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun recentFiles_reachesNestedFolders() = runBlocking {
        filesRepository.createFolder("/a")
        filesRepository.createFolder("/a/b")
        filesRepository.uploadFile(uriString = "mock://deep.jpg", parentPath = "/a/b", fileName = "deep.jpg")
        filesRepository.uploadFile(uriString = "mock://top.jpg", parentPath = "/", fileName = "top.jpg")

        val recent = withTimeout(5_000) {
            filesRepository.recentFiles(limit = 20)
                .first { it is ListLoadState.Loaded && it.items.size >= 4 }
        }

        val paths = (recent as ListLoadState.Loaded).items.map { it.path }.toSet()
        assertTrue(
            paths.containsAll(listOf("/a", "/a/b", "/a/b/deep.jpg", "/top.jpg")),
            "recentFiles traverses the whole tree, not just the top level; got $paths"
        )
    }

    @Test
    fun recentFiles_respectsLimit() = runBlocking {
        repeat(5) { i ->
            filesRepository.uploadFile(uriString = "mock://f$i.jpg", parentPath = "/", fileName = "f$i.jpg")
        }

        val recent = withTimeout(5_000) {
            filesRepository.recentFiles(limit = 3)
                .first { it is ListLoadState.Loaded && it.items.size >= 3 }
        }

        assertEquals(3, (recent as ListLoadState.Loaded).items.size, "take(limit) caps the result")
    }
}
