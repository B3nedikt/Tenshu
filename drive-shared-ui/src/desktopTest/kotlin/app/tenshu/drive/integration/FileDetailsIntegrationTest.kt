package app.tenshu.drive.integration

import app.tenshu.drive.data.FileActivityKind
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
 * End-to-end coverage for [FilesRepository.fileDetails] (Phase 2 metadata) and
 * [FilesRepository.fileActivity] (Phase 3 history) over the real in-process proxy
 * chain. Metadata: `size` + re-introduced `createdAt` + `lastModifiedAt` for files
 * and folders (a fresh entry has `createdAt == lastModifiedAt`). Activity: file
 * writes emit `FileModification`s that surface as activity rows with the right
 * kind + detail (the harness wires a device id so the emit hooks fire).
 */
class FileDetailsIntegrationTest {

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
    fun fileDetails_reportsMetadataForAFile() = runBlocking {
        filesRepository.uploadFile(uriString = "mock://photo.jpg", parentPath = "/", fileName = "photo.jpg")

        val details = withTimeout(5_000) {
            filesRepository.fileDetails("/photo.jpg").first { it != null }!!
        }

        assertEquals("photo.jpg", details.file.name)
        assertTrue(details.size.isNotEmpty(), "size should be formatted: $details")
        assertTrue(details.created.isNotEmpty(), "createdAt should be recorded: $details")
        assertEquals(
            details.created, details.lastModified,
            "a fresh file is created and modified in the same write, so the two stamps match"
        )
    }

    @Test
    fun fileDetails_reportsMetadataForAFolder() = runBlocking {
        filesRepository.createFolder("/docs")

        val details = withTimeout(5_000) {
            filesRepository.fileDetails("/docs").first { it != null }!!
        }

        assertEquals("docs", details.file.name)
        assertTrue(details.created.isNotEmpty(), "a new folder records createdAt: $details")
    }

    @Test
    fun fileDetails_emitsNullForMissingPath() = runBlocking {
        val details = withTimeout(5_000) {
            filesRepository.fileDetails("/nope.txt").first()
        }
        assertEquals(null, details, "no entry at the path -> null")
    }

    @Test
    fun fileActivity_recordsCreatedForANewFile() = runBlocking {
        filesRepository.uploadFile(uriString = "mock://a.jpg", parentPath = "/", fileName = "a.jpg")

        val activity = withTimeout(5_000) {
            filesRepository.fileActivity("/a.jpg").first { it.isNotEmpty() }
        }

        assertEquals(1, activity.size, "a single Created record: $activity")
        assertEquals(FileActivityKind.Created, activity.single().kind)
        assertTrue(activity.single().deviceId.isNotEmpty(), "the authoring device id is recorded")
    }

    @Test
    fun fileActivity_recordsRenameWithDetail() = runBlocking {
        filesRepository.uploadFile(uriString = "mock://a.jpg", parentPath = "/", fileName = "a.jpg")
        filesRepository.renameFile("/a.jpg", "b.jpg")

        // History is path-keyed: the rename record is filed under the NEW path.
        val renamed = withTimeout(5_000) {
            filesRepository.fileActivity("/b.jpg")
                .first { list -> list.any { it.kind == FileActivityKind.Renamed } }
                .first { it.kind == FileActivityKind.Renamed }
        }

        assertEquals("a.jpg → b.jpg", renamed.detail)
    }
}
