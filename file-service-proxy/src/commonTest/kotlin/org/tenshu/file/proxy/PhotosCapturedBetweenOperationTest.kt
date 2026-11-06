package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.photosCapturedBetween
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `photosCapturedBetween` (`timeline-photos.md` T-P2) end-to-end over the op wire — RO → param map
 * → proxy scan → returned composites.
 *
 * ⚠ **Over the wire on purpose.** T-P1's photo join was unit-green and completely unproven at this
 * seam: an in-process harness registers every module, so it cannot catch a client that forgot one,
 * but it *can* catch the half that a pure test cannot — that the params arrive, the scan walks a
 * real store, and the returned type materialises on the far side.
 */
class PhotosCapturedBetweenOperationTest {

    private val noon = 1_700_000_000_000L

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.upload(parent: String, name: String, capturedAtMs: Long) {
        assertTrue(
            defaultFileSystem.uploadFile(
                parent, name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
            ) is TResult.Success,
            "seeding $parent/$name should succeed",
        )
    }

    private suspend fun FileService.photos(
        startMs: Long,
        endMs: Long,
        limit: Int = 10,
    ): List<CapturedPhoto> =
        defaultFileSystem.photosCapturedBetween(startMs, endMs, limit)
            .getOrNull()
            ?.toList()
            .orEmpty()
            .filterIsInstance<CapturedPhoto>()

    @Test
    fun returnsTheImagesCapturedInsideTheWindow_newestFirst() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "a.jpg", noon + 1_000)
        service.upload("/", "b.jpg", noon + 2_000)

        val photos = service.photos(noon, noon + 60_000)

        assertEquals(listOf("/b.jpg", "/a.jpg"), photos.map { it.path.get() })
        assertEquals(noon + 2_000, photos.first().capturedAt.get())
    }

    @Test
    fun theWindowIsHalfOpen() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "before.jpg", noon - 1)
        service.upload("/", "start.jpg", noon)
        service.upload("/", "end.jpg", noon + 60_000)

        val photos = service.photos(noon, noon + 60_000)

        // The lower bound is inclusive and the upper exclusive, so two adjacent moments cannot both
        // claim a photo taken exactly on their boundary.
        assertEquals(listOf("/start.jpg"), photos.map { it.path.get() })
    }

    @Test
    fun ignoresFilesThatAreNotImages() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "notes.pdf", noon + 1)
        service.upload("/", "clip.mp4", noon + 2)
        service.upload("/", "photo.HEIC", noon + 3)

        // Video is a deliberate omission, not an oversight — a poster frame is another OnDemand
        // blob class (§7 Q4).
        assertEquals(listOf("/photo.HEIC"), service.photos(noon, noon + 60_000).map { it.path.get() })
    }

    @Test
    fun walksNestedFoldersSoBackedUpPhotosAreFound() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        assertTrue(service.defaultFileSystem.createFolder("/Backup") is TResult.Success)
        assertTrue(service.defaultFileSystem.createFolder("/Backup/Phone") is TResult.Success)
        assertTrue(service.defaultFileSystem.createFolder("/Backup/Phone/Camera") is TResult.Success)
        service.upload("/Backup/Phone/Camera", "IMG_1.jpg", noon + 5)

        val photo = service.photos(noon, noon + 60_000).single()

        assertEquals("/Backup/Phone/Camera/IMG_1.jpg", photo.path.get())
        assertTrue(photo.backedUp.get(), "a file under the backup root is flagged as such")
    }

    @Test
    fun aPhotographYouTookOutranksAScreenshotSharingTheWindow() = runTest {
        // The point of the ranking: with limit = 1 the answer must be the camera-roll photo, even
        // though the screenshot is newer. Ordering by time alone would return the screenshot.
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        assertTrue(service.defaultFileSystem.createFolder("/Backup") is TResult.Success)
        assertTrue(service.defaultFileSystem.createFolder("/Backup/Phone") is TResult.Success)
        service.upload("/Backup/Phone", "IMG_1.jpg", noon + 1_000)
        service.upload("/", "Screenshot_2026.png", noon + 9_000)

        val best = service.photos(noon, noon + 60_000, limit = 1).single()

        assertEquals("/Backup/Phone/IMG_1.jpg", best.path.get())
        assertTrue(best.backedUp.get())
    }

    @Test
    fun anUnbackedPhotoIsStillReturnedWhenItIsAllThereIs() = runTest {
        // Ranking, never filtering: a moment whose only image is a screenshot shows the screenshot
        // rather than nothing.
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "Screenshot_2026.png", noon + 1)

        val photo = service.photos(noon, noon + 60_000).single()

        assertEquals("/Screenshot_2026.png", photo.path.get())
        assertTrue(!photo.backedUp.get())
    }

    @Test
    fun aZeroLimitScansNothing() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "a.jpg", noon + 1)

        assertTrue(service.photos(noon, noon + 60_000, limit = 0).isEmpty())
    }

    @Test
    fun anInvertedWindowReturnsNothingRatherThanEverything() = runTest {
        val service = fileService(createFileProxy(backgroundScope, clock = FakeClock(initial = 1L)))
        service.upload("/", "a.jpg", noon + 1)

        assertTrue(service.photos(noon + 60_000, noon).isEmpty())
    }
}
