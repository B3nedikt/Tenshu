package org.tenshu.file.proxy.jvm

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.system.BackfillThumbnailClasses
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.impl.BackfillThumbnailClassesImplementation
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.writeWantedThumbnailClasses
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ **A generation call renders from this file's own bytes or answers null — it never serves a
 * stored class.** `ThumbnailClassMinter.renderStill` passes `GetPreview.Params.GENERATE_FRESH` and
 * stores whatever comes back under the class it asked for. The actuals' short-circuits honoured the
 * flag, but their fallbacks for a render that could not happen (no frame decoder, no bytes, a failed
 * poster or page) did not, and those two routes store the wrong picture under a class's name:
 *
 * - **The backfill.** A desktop holding a video synced from a phone has no frame decoder, so the
 *   `XLARGE` render fell back to the stored `LARGE` poster — and the pass stored and published that
 *   256 px image as the 1024 px class. The class then reads as present and is never re-minted;
 *   every device that pulls it is served the small picture for good.
 * - **A replace.** The render runs before the row takes the new content id, so a fallback resolves
 *   the *previous* content's classes and stores them as the new content's.
 *
 * Both run the real JVM `GetPreview` through the real minter, because the fake in
 * `BackfillThumbnailClassesOpTest` answers whatever the test says and could not hold this.
 */
class GenerateFreshNeverServesStoredClassesTest {

    /** Stands in for a poster a phone minted and this desktop pulled: its identity is what matters. */
    private val pulledPoster = ByteArray(1_024) { (it * 5 % 251).toByte() }

    @Test
    fun theDesktopBackfillNeverPublishesAVideosStoredPosterAsItsHeroRung() = runTest {
        val emitter = RecordingContentEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = "desktop-1",
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        proxy.additionalImplementations[GetPreview.ID] = JvmGetPreviewImplementation(NoOpPreviewCache)

        val entity = Entity.create<File>(context = proxy.tContext, composite = File.create(proxy.tContext, "/clip.mp4")) {
            set(File.ContentId, "content-clip")
        }
        proxy.executionContext.saveData(entity)
        proxy.fileDatabase.writeByteArray("/clip.mp4", ByteArray(4_096) { 1 }.toTByteArray(proxy.tContext))
        proxy.fileDatabase.writeRawByteArray(
            FileBytesPropertyWriter.thumbnailPath("content-clip", GetStoredThumbnail.CLASS_LARGE),
            pulledPoster,
        )
        proxy.executionContext.writeWantedThumbnailClasses("phone", setOf(GetStoredThumbnail.CLASS_XLARGE))

        proxy.executionContext.executeAsync<Data>(
            operationId = BackfillThumbnailClasses.ID,
            caller = null,
            params = emptyMap(),
        )

        assertNull(
            proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath("content-clip", GetStoredThumbnail.CLASS_XLARGE)),
            "the desktop cannot render a video, so it must mint no hero rung — not store the pulled poster under that name",
        )
        assertTrue(
            emitter.propertyIds.none { it == File.ThumbnailXLarge.id },
            "and must publish nothing under the hero rung's property; emitted ${emitter.propertyIds}",
        )
        assertNotNull(
            proxy.fileDatabase.readBytes(
                BackfillThumbnailClassesImplementation.thumbnailDeclinePath("content-clip", GetStoredThumbnail.CLASS_XLARGE),
            ),
            "the render is recorded as declined, so the next pass does not try again",
        )
    }

    @Test
    fun replacingAPdfWithOneThatCannotRenderNeverStoresThePreviousPagesClasses() = runTest {
        val proxy = createFileProxy(backgroundScope)
        proxy.additionalImplementations[GetPreview.ID] = JvmGetPreviewImplementation(NoOpPreviewCache)

        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to "doc.pdf".toTString(),
                UploadFile.Params.DATA to pdfBytes().toTByteArray(proxy.tContext),
            ),
        )
        val firstContentId = contentIdOf(proxy, "/doc.pdf")
        assertNotNull(
            proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath(firstContentId, GetStoredThumbnail.CLASS_LARGE)),
            "precondition: the first PDF rendered and stored its page",
        )

        proxy.executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                ReplaceFile.Params.PARENT_PATH to "/".toTString(),
                ReplaceFile.Params.FILE_NAME to "doc.pdf".toTString(),
                ReplaceFile.Params.DATA to "not a pdf at all".encodeToByteArray().toTByteArray(proxy.tContext),
            ),
        )
        val secondContentId = contentIdOf(proxy, "/doc.pdf")
        assertTrue(secondContentId != firstContentId, "precondition: the replace moved the content id")

        for (thumbnailClass in listOf(GetStoredThumbnail.CLASS_LIST, GetStoredThumbnail.CLASS_LARGE, GetStoredThumbnail.CLASS_XLARGE)) {
            assertNull(
                proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath(secondContentId, thumbnailClass)),
                "an unrenderable replacement has no $thumbnailClass — not the previous page's, stored under the new content",
            )
        }
    }

    private suspend fun contentIdOf(proxy: org.tenshu.core.common.TestProxy, path: String): String {
        val id = DataId.StringDataId.create(proxy.tContext, File.ID, path)
        val row = assertNotNull(proxy.executionContext.database.getData(id), "the row for $path")
        val contentId = proxy.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString
        return assertNotNull(contentId?.get()?.takeIf { it.isNotEmpty() }, "the content id of $path")
    }

    private fun pdfBytes(): ByteArray {
        val document = PDDocument()
        document.addPage(PDPage(PDRectangle(600f, 800f)))
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
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
