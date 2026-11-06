package app.tenshu.drive.integration

import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end coverage for the first producer-side **deferred property**
 * consumer (`core-deferred-properties.md` §4): [FilesRepository.fileDetails]
 * projects `File.Preview(480, 480)` — deferred by default — over the real
 * in-process proxy chain. The details stream must:
 *
 *  1. emit the row's metadata IMMEDIATELY with `previewBytes == null`
 *     (the marker crossed IPC and null-collapsed — no inline resolution), and
 *  2. re-emit the SAME row with the preview bytes once the proxy's
 *     background resolution patches it (`StreamRegistry.resolveAndPatch` →
 *     per-item `StreamElementValueDto` → the client flow's list-state merge).
 *
 * Step 2 is the one integration point the proxy-side unit tests can't reach:
 * the CLIENT merging a per-item patch into a collected chain flow.
 */
class FileDetailsDeferredPreviewIntegrationTest {

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
    fun detailsStream_emitsMetadataFirst_thenPatchesPreviewBytes() = runBlocking<Unit> {
        filesRepository.uploadFile(
            uriString = "mock://photo.jpg?w=8&h=8",
            parentPath = "/",
            fileName = "photo.jpg"
        )

        val emissions = mutableListOf<FileDetailsModel>()
        val patched = withTimeout(10_000) {
            filesRepository.fileDetails("/photo.jpg")
                .onEach { details -> details?.let { emissions.add(it) } }
                .first { (it?.file as? FileModel.File)?.previewBytes != null }
        }

        // The settled row: preview bytes arrived through the stream patch.
        assertNotNull(patched)
        val patchedFile = patched.file as FileModel.File
        assertNotNull(patchedFile.previewBytes, "the deferred patch must deliver the preview bytes")
        assertEquals("photo.jpg", patchedFile.name)

        // The FIRST emission carried metadata only — the deferred marker never
        // blocks the initial read and null-collapses client-side. (Initial
        // emission and patch ride the same ordered connection, so the
        // metadata-only frame always arrives first.)
        val initial = emissions.first()
        val initialFile = initial.file as FileModel.File
        assertNull(
            initialFile.previewBytes,
            "initial emission must not wait for the preview — that's the blocking read @defer removes"
        )
        assertEquals("photo.jpg", initialFile.name, "metadata rides the initial emission")
        assertNotNull(initial.size, "size resolves inline (eager metadata)")
    }
}
