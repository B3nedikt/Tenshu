package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.resolve
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upload path's half of M4 (`photos-video-support.md` §4.1, §4.5, §13.4): a video's `MOTION`
 * class is minted through the [MotionThumbnailRenderer] seam, written to the file's own
 * `/.thumbnails/<contentId>/motion` blob and emitted onto the content plane under
 * `File.ThumbnailMotion` — and every way that can *not* happen leaves the upload intact.
 *
 * The renderer is a fake: this pins the wiring a codec sits behind, on the JVM, where the codec
 * cannot run. What the Android encoder actually produces is the device pass's business.
 */
class UploadMintsMotionThumbnailTest {

    private val videoBytes = ByteArray(4_096) { (it * 7 % 251).toByte() }
    private val fakeClip = ByteArray(1_000) { (it % 13).toByte() }

    @Test
    fun aVideoUploadStoresAndEmitsTheMotionClass() = runTest {
        val emitter = RecordingContentEmitter()
        val renderer = RecordingRenderer { fakeClip }
        val proxy = proxy(emitter, renderer)

        proxy.upload("clip.mp4", videoBytes)

        val contentId = assertNotNull(proxy.contentIdOf("/clip.mp4"), "the upload itself must succeed")
        val path = FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_MOTION)
        assertContentEquals(
            fakeClip,
            proxy.fileDatabase.readBytes(path)?.get(),
            "the clip must be stored at the motion class's own blob path",
        )
        val motionEmit = emitter.calls.single { it.propertyId == File.ThumbnailMotion.id }
        assertEquals(path, motionEmit.filePath, "the content-plane emit must address the same blob")

        val call = renderer.calls.single()
        assertContentEquals(videoBytes, call.bytes, "the renderer is handed the uploaded bytes as-is")
        assertEquals(UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES, call.maxBytes, "the cap is the renderer's input")
    }

    /** §4.1: minted only for `FileCategory.Video` — the gate is the upload path's, not the renderer's. */
    @Test
    fun aStillIsNeverHandedToTheRenderer() = runTest {
        val emitter = RecordingContentEmitter()
        val renderer = RecordingRenderer { fakeClip }
        val proxy = proxy(emitter, renderer)

        proxy.upload("photo.jpg", videoBytes)
        proxy.upload("notes.pdf", videoBytes)

        assertTrue(renderer.calls.isEmpty(), "a non-video must not reach the renderer; got ${renderer.calls.size}")
        assertNull(emitter.calls.firstOrNull { it.propertyId == File.ThumbnailMotion.id })
    }

    /** §4.5: an over-cap blob is never stored — the same drop rule as the three still classes. */
    @Test
    fun anOverCapClipIsDroppedAndTheUploadStillSucceeds() = runTest {
        val emitter = RecordingContentEmitter()
        val overCap = ByteArray(UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES + 1)
        val proxy = proxy(emitter, RecordingRenderer { overCap })

        proxy.upload("clip.mp4", videoBytes)

        val contentId = assertNotNull(proxy.contentIdOf("/clip.mp4"))
        assertNull(proxy.fileDatabase.readBytes(FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_MOTION)))
        assertNull(emitter.calls.firstOrNull { it.propertyId == File.ThumbnailMotion.id })
    }

    /** §12.6 / §13.4: a renderer that declines (`null`) or yields nothing stores nothing. */
    @Test
    fun aDeclinedOrEmptyClipStoresNothing() = runTest {
        for (result in listOf<ByteArray?>(null, ByteArray(0))) {
            val emitter = RecordingContentEmitter()
            val proxy = proxy(emitter, RecordingRenderer { result })

            proxy.upload("clip.mp4", videoBytes)

            assertNotNull(proxy.contentIdOf("/clip.mp4"))
            assertNull(emitter.calls.firstOrNull { it.propertyId == File.ThumbnailMotion.id })
        }
    }

    /** Thumbnailing never fails an upload — a renderer that throws is a video with no motion class. */
    @Test
    fun aThrowingRendererDoesNotFailTheUpload() = runTest {
        val emitter = RecordingContentEmitter()
        val proxy = proxy(emitter, RecordingRenderer { error("codec exploded") })

        proxy.upload("clip.mp4", videoBytes)

        assertNotNull(proxy.contentIdOf("/clip.mp4"), "the upload must survive the renderer")
        assertNull(emitter.calls.firstOrNull { it.propertyId == File.ThumbnailMotion.id })
        assertTrue(emitter.calls.any { it.propertyId == File.Data.id }, "the file's own bytes still emit")
    }

    /** Every platform but Android: no renderer, no motion class, nothing else changes. */
    @Test
    fun noRendererMintsNothing() = runTest {
        val emitter = RecordingContentEmitter()
        val proxy = proxy(emitter, renderer = null)

        proxy.upload("clip.mp4", videoBytes)

        assertNotNull(proxy.contentIdOf("/clip.mp4"))
        assertNull(emitter.calls.firstOrNull { it.propertyId == File.ThumbnailMotion.id })
    }

    // ---- harness ----

    private fun kotlinx.coroutines.test.TestScope.proxy(
        emitter: RecordingContentEmitter,
        renderer: MotionThumbnailRenderer?,
    ): TestProxy = createFileProxy(
        backgroundScope,
        deviceId = "device-1",
        syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        motionThumbnailRenderer = renderer,
    )

    private suspend fun TestProxy.upload(fileName: String, bytes: ByteArray) {
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(tContext),
                UploadFile.Params.FILE_NAME to fileName.toTString(tContext),
                UploadFile.Params.DATA to bytes.toTByteArray(tContext),
            ),
        )
    }

    /** The file's stamped `contentId` through the select chain (the `MoveFileOnDemandBytesTest` pattern). */
    private suspend fun TestProxy.contentIdOf(filePath: String): String? {
        val executor = LocalTaskExecutor(executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = tContext,
        )
        val fileService = env.getService<FileService>(FileService.ID)
        return fileService.defaultFileSystem.root.files
            .search<File> { this.path equalTo filePath }
            .select { relations(File.ContentId) }
            .resolve()
            .getOrNull()
            ?.get(File.ContentId)?.get()
    }

    private class RenderCall(val bytes: ByteArray, val maxBytes: Int)

    private class RecordingRenderer(private val result: () -> ByteArray?) : MotionThumbnailRenderer {
        val calls = mutableListOf<RenderCall>()
        override fun render(videoBytes: ByteArray, maxBytes: Int): ByteArray? {
            calls += RenderCall(videoBytes, maxBytes)
            return result()
        }
    }

    private class EmitCall(val entityId: String, val propertyId: String, val filePath: String)

    private class RecordingContentEmitter : FileContentSyncEmitter {
        val calls = mutableListOf<EmitCall>()
        override suspend fun emitFileContent(
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
        ): Boolean {
            calls += EmitCall(
                (targetEntityId as DataId.StringDataId).keyValue.get(),
                propertyId,
                filePath,
            )
            return true
        }

        override suspend fun shareContentToRealm(
            contentId: String,
            targetRealmOwnerId: String,
            targetPropertyId: String,
        ): Boolean = true
    }
}
