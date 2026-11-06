package app.tenshu.ui.components

import app.tenshu.macos.camera.CameraAuthorization
import app.tenshu.macos.camera.MacCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The in-process macOS scanner: [MacCamera] adapted to the [QrCameraScanner] seam.
 *
 * This is the thin half of the §13.6 split. The FFM work lives in `:macos-helpers` (JDK 25,
 * Compose-free); everything here is ordinary Kotlin mapping engine types to `ScanEvent`, so
 * `:ui-core` never sees `java.lang.foreign` and stays on its own toolchain.
 *
 * **What it buys** (§9): the camera is opened by *this process*, so the app is its own
 * responsible TCC subject and the first-grant SIGKILL that plagues the spawned-helper path
 * does not arise. That also makes [ScanOutcome.NeedsRetry] and its two-pass re-spawn dead
 * weight here — there is no second process to restart, so the flow simply asks and continues.
 *
 * Decoding is Vision, run by the engine over the same (throttled) frames it previews — on
 * macOS that is the only QR path, since `AVCaptureMetadataOutput` reads barcodes on iOS but
 * not here (§7). The first payload completes the flow, mirroring the helper's behaviour.
 */
internal class FfmMacQrCameraScanner : QrCameraScanner {

    /**
     * Pure status read — never opens a session, so it cannot activate the camera or provoke
     * a dialog. Same contract the Swift helper's `probe` mode offered, minus the subprocess.
     */
    override suspend fun probePermission(): CameraPermission = withContext(Dispatchers.IO) {
        MacCamera.authorizationStatus().toCameraPermission()
    }

    override fun scan(): Flow<ScanEvent> = callbackFlow {
        when (MacCamera.authorizationStatus()) {
            CameraAuthorization.Authorized -> Unit

            // In-process, asking is safe (§9) — so ask inline and carry on, instead of the
            // helper path's grant-then-respawn dance.
            CameraAuthorization.NotDetermined -> if (!MacCamera.requestAccess()) {
                send(ScanEvent.Result(ScanOutcome.PermissionDenied))
                close()
                return@callbackFlow
            }

            CameraAuthorization.Denied -> {
                send(ScanEvent.Result(ScanOutcome.PermissionDenied))
                close()
                return@callbackFlow
            }

            CameraAuthorization.NoCamera, CameraAuthorization.Unavailable -> {
                send(ScanEvent.Result(ScanOutcome.NoCamera))
                close()
                return@callbackFlow
            }
        }

        val session = MacCamera.createSession(
            onFrame = { frame ->
            // Frames arrive on the engine's serial queue at camera rate. `trySend` with a
            // conflating buffer means a slow collector drops stale frames instead of
            // applying backpressure to the capture callback — for a live preview the newest
            // frame is the only one worth showing, and blocking that queue would stall
            // AVFoundation itself.
                trySend(
                    ScanEvent.RawFrame(
                        bgra = frame.bgra,
                        width = frame.width,
                        height = frame.height,
                        bytesPerRow = frame.bytesPerRow,
                    ),
                )
            },
            onQrPayload = { payload ->
                // A decode is terminal. `trySend` rather than `send` because this arrives on
                // AVFoundation's delivery queue, which must not be suspended; `close()` then
                // completes the flow and `awaitClose` tears the camera down. A second payload
                // from a frame already in flight lands on a closed channel and is dropped.
                trySend(ScanEvent.Result(ScanOutcome.Decoded(payload)))
                close()
            },
        )

        if (session == null) {
            send(ScanEvent.Result(ScanOutcome.NoCamera))
            close()
            return@callbackFlow
        }

        session.start()
        // Cancelling collection (the screen going away) tears the camera down — the same
        // contract the helper-based scanner offers by killing its subprocess.
        awaitClose { session.close() }
    }
        .buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)
}

/** Map the engine's authorization to the seam's [CameraPermission]. */
private fun CameraAuthorization.toCameraPermission(): CameraPermission = when (this) {
    CameraAuthorization.Authorized -> CameraPermission.Authorized
    CameraAuthorization.NotDetermined -> CameraPermission.NotDetermined
    CameraAuthorization.Denied -> CameraPermission.Denied
    // "No device" and "not macOS / FFM unreachable" are the same thing to the UI: there is
    // no camera to offer, and no permission state that would change it.
    CameraAuthorization.NoCamera, CameraAuthorization.Unavailable -> CameraPermission.NoCamera
}
