package org.tenshu.systemtest

import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.edit.MarkupEdit
import app.tenshu.photos.edit.NormPoint
import app.tenshu.photos.edit.Redaction
import app.tenshu.photos.edit.TransformEdit
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * ED-3 across two devices, over the real relay (`photos-editing.md` §12): an edit saved on one
 * device reaches the other as a `PhotoEdit` row plus a rendition whose thumbnails the EDITING
 * device minted — so the peer draws the baked picture (ED-D2) and never re-renders the recipe. The
 * photograph keeps its `contentId` on both, the rendition is never listed as a photo of its own, and
 * a revert on the peer reaches the editor.
 */
class PhotoEditSyncSystemTest {

    @Test fun anEditReachesThePeerAsItsRendition_andARevertComesBack() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val (host, joiner) = pairedHostAndJoiner(system)
            host.tenshuClient.fileService.defaultFileSystem
                .uploadFile("/", "photo.jpg", jpegBytes(640, 480), sourceModifiedAt = CAPTURED_AT)
                .valueOrFail("host uploadFile(/photo.jpg)")
            val hostPhotos = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test */)
            val joinerPhotos = TenshuPhotosRepository(joiner.tenshuClient, reachability = null /* test */)

            val original = (hostPhotos.feedPage(Long.MAX_VALUE, pageSize = 10) as PhotoPageResult.Page).photos.single()
            val recipe = EditRecipe(
                transform = TransformEdit(quarterTurns = 1),
                markup = MarkupEdit(listOf(Redaction(1, listOf(NormPoint(0.1f, 0.1f), NormPoint(0.5f, 0.1f), NormPoint(0.5f, 0.4f), NormPoint(0.1f, 0.4f))))),
            )
            val saved = assertNotNull(hostPhotos.saveEdit(original, recipe, jpegBytes(480, 640)))
            val rendition = assertNotNull(saved.renditionPath)

            // The peer: the photo, with the rendition, and its thumbnail — the editing device's mint.
            var thumbnail: ByteArray? = null
            system.advanceUntil(timeout = 30.seconds) {
                val row = (joinerPhotos.feedPage(Long.MAX_VALUE, pageSize = 10) as? PhotoPageResult.Page)
                    ?.photos?.singleOrNull() ?: return@advanceUntil false
                if (row.renditionPath != rendition) return@advanceUntil false
                thumbnail = joinerPhotos.preview(row.previewPath, 256, 256)?.bytes
                thumbnail != null
            }
            assertNotNull(thumbnail, "the peer draws the rendition's synced thumbnail")
            val peerRow = (joinerPhotos.feedPage(Long.MAX_VALUE, pageSize = 10) as PhotoPageResult.Page).photos
            assertEquals(listOf(original.path), peerRow.map { it.path }, "the rendition is never a photo of its own")
            assertEquals(original.contentId, peerRow.single().contentId)
            assertEquals(recipe, joinerPhotos.photoEdit(original.contentId)?.recipe, "the peer can re-open the edit")
            assertTrue(joinerPhotos.photoEdit(original.contentId)?.redacted == true)

            // A revert on the peer reaches the editor.
            assertNotNull(joinerPhotos.revertEdit(peerRow.single()))
            system.advanceUntil(timeout = 30.seconds) {
                (hostPhotos.feedPage(Long.MAX_VALUE, pageSize = 10) as? PhotoPageResult.Page)
                    ?.photos?.singleOrNull()?.renditionPath == null &&
                    hostPhotos.photoEdit(original.contentId) == null
            }
            assertEquals(null, hostPhotos.photoEdit(original.contentId))
        } finally {
            system.shutdown()
        }
    }

    private suspend fun pairedHostAndJoiner(system: TestSystem): Pair<TestClient, TestClient> {
        val host = system.client(deviceId = "host", role = TestClientRole.RealmHost, pollingIntervalMs = 1_000)
        val joiner = system.client(deviceId = "joiner", role = TestClientRole.JoiningDevice, pollingIntervalMs = 1_000)
        host.bootstrapAsHost(deviceName = "Host Desktop")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Joiner Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 30.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
        return host to joiner
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) {
            image.setRGB(x, y, (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x40)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private companion object {
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
