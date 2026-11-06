package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.photosCapturedBetween
import org.tenshu.core.file.system.photosNear
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **PH-G16** (`photos-app.md` PH-5): a photo in the trash must not reach the realm-wide photo
 * reads — the ones **Timeline and Mia** make, not Photos.
 *
 * ⚠⚠ This is the obligation the trash decision created. Photos filtering its own feed would have
 * left every other consumer in the realm showing photographs the user deleted, and nothing would
 * have failed — a Timeline moment illustrated with a deleted picture looks like a working feature.
 */
class TrashedPhotosAreNotServedTest {

    private val noon = 1_700_000_000_000L
    private val munichLatE7 = 481_370_000L
    private val munichLngE7 = 116_760_000L

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

    private suspend fun FileService.upload(
        parent: String,
        name: String,
        capturedAtMs: Long,
        latE7: Long? = null,
        lngE7: Long? = null,
    ) {
        assertTrue(
            defaultFileSystem.uploadFile(
                parent,
                name,
                byteArrayOf(1, 2, 3),
                sourceModifiedAt = capturedAtMs,
                captureLatE7 = latE7,
                captureLngE7 = lngE7,
            ) is TResult.Success,
            "seeding $parent/$name should succeed",
        )
    }

    @Test
    fun photosCapturedBetweenSkipsTheTrash() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.defaultFileSystem.createFolder("/Trash")
        service.upload("/", "kept.jpg", noon + 1_000)
        service.upload("/Trash", "deleted.jpg", noon + 2_000)

        val photos = service.defaultFileSystem
            .photosCapturedBetween(noon, noon + 60_000, limit = 10)
            .getOrNull()?.toList().orEmpty()
            .filterIsInstance<CapturedPhoto>()

        assertEquals(listOf("/kept.jpg"), photos.map { it.path.get() })
    }

    @Test
    fun photosNearSkipsTheTrash() = runTest {
        val service = fileService(createFileProxy(backgroundScope))
        service.defaultFileSystem.createFolder("/Trash")
        service.upload("/", "kept.jpg", noon, munichLatE7, munichLngE7)
        service.upload("/Trash", "deleted.jpg", noon, munichLatE7, munichLngE7)

        val photos = service.defaultFileSystem
            .photosNear(munichLatE7, munichLngE7, radiusM = 500, limit = 10)
            .getOrNull()?.toList().orEmpty()
            .filterIsInstance<CapturedPhoto>()

        assertEquals(listOf("/kept.jpg"), photos.map { it.path.get() })
    }

    @Test
    fun aFolderThatMerelyStartsWithTrashIsUntouched() = runTest {
        // ⚠⚠ The prefix bug this guards: `startsWith("/Trash")` would silently empty a user's
        // `/Trashcan` folder out of Timeline and Mia, with nothing anywhere saying why.
        val service = fileService(createFileProxy(backgroundScope))
        service.defaultFileSystem.createFolder("/Trashcan")
        service.upload("/Trashcan", "kept.jpg", noon + 1_000)

        val photos = service.defaultFileSystem
            .photosCapturedBetween(noon, noon + 60_000, limit = 10)
            .getOrNull()?.toList().orEmpty()
            .filterIsInstance<CapturedPhoto>()

        assertEquals(listOf("/Trashcan/kept.jpg"), photos.map { it.path.get() })
    }
}
