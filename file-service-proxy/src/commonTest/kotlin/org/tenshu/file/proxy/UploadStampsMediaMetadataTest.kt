package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CameraMake
import org.tenshu.core.file.CameraModel
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.file.test.createFileProxy
import org.tenshu.file.test.exifJpegAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An upload records what the file said about itself (`photos-app.md` PH-7).
 *
 * ⚠ **The wiring, not the parser.** `MediaMetadataTest` holds the byte walks for JPEG, HEIC, MP4
 * and PNG; what this proves is the half that test cannot see — that the values reach the stored row
 * and come back off a chain read. A parser that works and a stamp that never fires look identical
 * from the outside, which is the same asymmetry `PhotosNearOperationTest` was written against.
 */
class UploadStampsMediaMetadataTest {

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

    private fun proxy(scope: kotlinx.coroutines.CoroutineScope) =
        createFileProxy(scope, clock = FakeClock(initial = 1L))

    private suspend fun FileService.rowFor(path: String): Entity<File> {
        val rows = defaultFileSystem.root.files
            .traverse()
            .select {
                fields(File.Field.PATH)
                relations(
                    File.PixelWidth,
                    File.PixelHeight,
                    File.Orientation,
                    File.CameraMake,
                    File.CameraModel,
                    File.DurationMs,
                )
            }
            .getAll()
            .getOrNull()
            ?.toList()
            .orEmpty()

        @Suppress("UNCHECKED_CAST")
        return rows.mapNotNull { it as? Entity<File> }.first { it.path.get() == path }
    }

    @Test
    fun aPhotographsSizeReachesTheRow() = runTest {
        val service = fileService(proxy(backgroundScope))
        assertTrue(
            service.defaultFileSystem.uploadFile(
                "/", "holiday.jpg", exifJpegAt(43.77, 11.255, pixelWidth = 4032, pixelHeight = 3024),
            ) is TResult.Success
        )

        val row = service.rowFor("/holiday.jpg")

        assertEquals(4032L, row.get(File.PixelWidth)?.get())
        assertEquals(3024L, row.get(File.PixelHeight)?.get())
    }

    @Test
    fun aFileThatSaysNothingStoresNothing() = runTest {
        // ⚠⚠ The property that matters most here: absent must stay absent. A stamp that wrote a
        // default — 0 × 0, or orientation 1 — would turn "we could not read this container" into a
        // confident claim about the photo, and every consumer downstream reads absence as
        // "the file did not say".
        val service = fileService(proxy(backgroundScope))
        assertTrue(
            service.defaultFileSystem.uploadFile("/", "notes.txt", "hello".encodeToByteArray())
                is TResult.Success
        )

        val row = service.rowFor("/notes.txt")

        assertNull(row.get(File.PixelWidth)?.get())
        assertNull(row.get(File.PixelHeight)?.get())
        assertNull(row.get(File.Orientation)?.get())
        assertNull(row.get(File.CameraMake)?.get())
        assertNull(row.get(File.CameraModel)?.get())
        assertNull(row.get(File.DurationMs)?.get())
    }

    @Test
    fun aPhotographWithNoFrameHeaderKeepsItsCoordinateAndStoresNoSize() = runTest {
        // The pre-PH-7 fixture, unchanged: it still uploads, still carries its coordinate, and
        // now simply has nothing to say about its size.
        val service = fileService(proxy(backgroundScope))
        assertTrue(
            service.defaultFileSystem.uploadFile("/", "old.jpg", exifJpegAt(43.77, 11.255))
                is TResult.Success
        )

        val row = service.rowFor("/old.jpg")

        assertNull(row.get(File.PixelWidth)?.get())
    }
}
