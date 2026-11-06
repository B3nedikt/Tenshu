package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **Every new upload mints and publishes the whole still ladder** — `drive-xxlarge-thumbnail-rung.md`
 * §9.4's central as-built claim, which nothing pinned until the X-1 review looked for it (D-15: minted
 * unconditionally, like `XLARGE`, with no D-8 gating).
 *
 * It runs the real JVM `GetPreview` through the real minter on a real 2400 px JPEG, because the thing
 * that could break is not the table — `ThumbnailClassesTest` covers that — but the upload actually
 * walking it: a rung the loop skips, a blob written under the wrong path, or a class stored and never
 * emitted would all leave the table right and the device wrong.
 *
 * ⚠ 2400 px on purpose: above `XXLARGE_PX`, so the top rung is a genuine downscale. A source *below*
 * 2048 px renders no larger than itself, and `XLARGE` and `XXLARGE` then hold the same picture under
 * two content ids — real, measured in the X-1 review, and tracked as §8's open question rather than
 * hidden inside this fixture.
 */
class UploadMintsTheFullStillLadderTest {

    @Test
    fun anImageUploadStoresAndEmitsEveryStillRung() = runTest {
        val emitter = RecordingContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = "desktop-1",
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        proxy.additionalImplementations[GetPreview.ID] = JvmGetPreviewImplementation(NoOpPreviewCache)

        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "photo.jpg".toTString(),
                UploadFile.Params.DATA to photoBytes(edge = 2_400).toTByteArray(proxy.tContext),
            ),
        )

        val contentId = contentIdOf(proxy, "/photo.jpg")
        val expected = mapOf(
            GetStoredThumbnail.CLASS_LIST to File.Thumbnail.id,
            GetStoredThumbnail.CLASS_LARGE to File.ThumbnailLarge.id,
            GetStoredThumbnail.CLASS_XLARGE to File.ThumbnailXLarge.id,
            GetStoredThumbnail.CLASS_XXLARGE to File.ThumbnailXXLarge.id,
        )
        for ((thumbnailClass, propertyId) in expected) {
            val path = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)
            assertNotNull(proxy.fileDatabase.readBytes(path), "$thumbnailClass must be stored at $path")
            assertTrue(propertyId in emitter.propertyIds, "$thumbnailClass must reach the content plane")
        }

        // ⚠ The top rung is the picture at its own edge, not a copy of the one below it.
        val xxlarge = assertNotNull(
            proxy.fileDatabase.readBytes(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XXLARGE)
            )?.get()
        )
        assertEquals(GetStoredThumbnail.XXLARGE_PX, decodedEdge(xxlarge), "the top rung renders at its own edge")
        assertTrue(
            xxlarge.size <= UploadFileImplementation.MAX_XXLARGE_THUMBNAIL_BYTES,
            "a photograph must clear D-13's cap: ${xxlarge.size} B",
        )

        val xlarge = assertNotNull(
            proxy.fileDatabase.readBytes(
                FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_XLARGE)
            )?.get()
        )
        assertEquals(GetStoredThumbnail.XLARGE_PX, decodedEdge(xlarge), "and the rung below it is unchanged")
    }

    private fun decodedEdge(bytes: ByteArray): Int =
        ImageIO.read(bytes.inputStream()).let { maxOf(it.width, it.height) }

    private suspend fun contentIdOf(proxy: TestProxy, path: String): String {
        val id = DataId.StringDataId.create(proxy.tContext, File.ID, path)
        val row = assertNotNull(proxy.executionContext.database.getData(id), "the row for $path")
        val contentId = proxy.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString
        return assertNotNull(contentId?.get()?.takeIf { it.isNotEmpty() }, "the content id of $path")
    }

    private fun photoBytes(edge: Int): ByteArray {
        val image = java.awt.image.BufferedImage(edge, edge, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until edge step 4) for (x in 0 until edge step 4) {
            val rgb = ((x * 255 / edge) shl 16) or ((y * 255 / edge) shl 8) or 0x40
            for (dy in 0 until 4) for (dx in 0 until 4) image.setRGB(x + dx, y + dy, rgb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return out.toByteArray()
    }

    private class RecordingContentEmitter : FileContentSyncEmitter {
        val propertyIds = mutableListOf<String>()

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean {
            propertyIds += propertyId
            return true
        }

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true
    }
}
