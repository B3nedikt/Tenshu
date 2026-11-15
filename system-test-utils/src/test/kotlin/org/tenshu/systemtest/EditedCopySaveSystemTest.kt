package org.tenshu.systemtest

import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.model.Photo
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * An edited copy's *Save as copy* returns once the copy is on the device, not once the relay has
 * its bytes (`photos-editing.md` §9.6), over the real relay and the real `TenshuPhotosRepository`.
 *
 * ⚠⚠ The relay's content `PUT`s are HELD — not refused — for the whole save: `UploadFile` sends the
 * bytes inline before it returns, so a held `PUT` is exactly the 55 s the emulator spent on a
 * socket timeout. A save that waited on the op would never return here.
 */
class EditedCopySaveSystemTest {

    @Test fun aSaveReturnsWhileTheContentPushIsHeld_andTheCopySyncsOnceTheRelayAnswers() = runBlocking {
        val system = TestSystem.builder().build()
        val relayAnswers = CompletableDeferred<Unit>().apply { complete(Unit) }
        var gate = relayAnswers
        val heldPuts = AtomicInteger()
        system.relayHttpClient.plugin(HttpSend).intercept { request ->
            val current = gate
            if (request.method == HttpMethod.Put && "/sync/content/" in request.url.buildString() && !current.isCompleted) {
                heldPuts.incrementAndGet()
                try { current.await() } finally { heldPuts.decrementAndGet() }
            }
            execute(request)
        }
        val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (host, joiner) = pairedHostAndJoiner(system)
            val original = "/photo.jpg"
            host.tenshuClient.fileService.defaultFileSystem
                .uploadFile("/", "photo.jpg", jpegBytes(640, 480), sourceModifiedAt = CAPTURED_AT)
                .valueOrFail("host uploadFile($original)")

            val held = CompletableDeferred<Unit>()
            gate = held
            val photos = TenshuPhotosRepository(host.tenshuClient, reachability = null /* test */, copyUploadScope = uploadScope)
            val rendered = jpegBytes(480, 480)
            val copy = withTimeoutOrNull(20.seconds) {
                photos.saveEditedCopy(Photo(original, CAPTURED_AT, isVideo = false), rendered)
            }

            assertNotNull(copy, "The save must return while the relay holds the copy's content push")
            assertEquals("/photo-edited.jpg", copy.path)
            assertEquals(CAPTURED_AT, copy.capturedAtMs, "The copy keeps the original's capture time")
            assertTrue(heldPuts.get() > 0, "Precondition: the save returned with a content PUT still held")
            assertContentEquals(rendered, photos.originalBytes(copy.path), "The copy reads back on this device at once")

            held.complete(Unit)
            var joinerBytes: ByteArray? = null
            // ⚠ `fields(PATH)` in the select, or the search matches nothing and no fetch is made.
            system.advanceUntil(timeout = 30.seconds) {
                if (joiner.executionContext.database.getData(DataId.StringDataId.create(type = File.ID, keyValue = copy.path)) == null) {
                    return@advanceUntil false
                }
                joiner.tenshuClient.fileService.defaultFileSystem.root
                    .files
                    .select { fields(File.Field.PATH); relations(File.Data) }
                    .search<File> { this.path equalTo copy.path }
                    .resolve()
                joinerBytes = joiner.fileDatabase.readBytes(copy.path)?.get()
                joinerBytes != null
            }
            assertContentEquals(rendered, joinerBytes, "Once the relay answers, the paired device gets the copy's bytes")
        } finally {
            gate.complete(Unit)
            uploadScope.cancel()
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
