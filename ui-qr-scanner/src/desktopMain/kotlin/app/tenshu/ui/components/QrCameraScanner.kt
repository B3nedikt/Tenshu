package app.tenshu.ui.components

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Headless camera→QR bridge for desktop. [scan] is a cold flow that emits live
 * preview [ScanEvent.Frame]s (JPEG bytes) until a terminal [ScanEvent.Result],
 * then completes; cancelling collection tears down the camera/helper. The
 * preview is rendered *inside* the Compose window (no second OS window — see
 * `docs/proposals/desktop-qr-scanning.md` §8).
 *
 * macOS wraps a bundled AVFoundation helper; other desktop OSes have no camera
 * backend (`forCurrentOs` returns null) and fall back to manual code entry.
 */
internal interface QrCameraScanner {
    fun scan(): Flow<ScanEvent>

    /**
     * One-shot camera authorization probe. Pure status read — never opens a
     * capture session, so it can't trigger the macOS first-grant host crash.
     * The desktop surface uses it to show a one-time "enable camera" explainer
     * *before* the grant (which can SIGKILL the host) instead of springing it
     * mid-pairing. See `docs/proposals/desktop-qr-scanning.md` §9.
     */
    suspend fun probePermission(): CameraPermission

    companion object {
        /**
         * System property selecting the macOS backend during FFM bring-up (§13 D5):
         * `-Dtenshu.qr.impl=ffm` picks the in-process scanner, anything else keeps the
         * bundled Swift helper.
         */
        const val IMPL_PROPERTY = "tenshu.qr.impl"

        /** Opt-in value for [IMPL_PROPERTY]. */
        const val IMPL_FFM = "ffm"

        /**
         * macOS → a scanner; Windows/Linux → null (no backend).
         *
         * **The Swift helper stays the default on purpose.** As of §13 P3 the in-process FFM
         * path ([FfmMacQrCameraScanner]) is functionally complete — it previews *and* decodes
         * — but "passes its tests" is not "verified on a signed build against a live camera",
         * and the claim it rests on (that the first grant no longer kills the host) is
         * precisely the one no test can make. Flipping the default belongs with that
         * on-device pass (§13.4); until then the flag runs the two A/B on the same build.
         */
        fun forCurrentOs(): QrCameraScanner? = when {
            !isMacOs() -> null
            System.getProperty(IMPL_PROPERTY) == IMPL_FFM -> FfmMacQrCameraScanner()
            else -> MacQrCameraScanner()
        }
    }
}

/** Camera authorization state, as reported by the helper's `probe` mode. */
internal enum class CameraPermission { Authorized, NotDetermined, Denied, NoCamera }

/** One item in the [QrCameraScanner.scan] stream. */
internal sealed interface ScanEvent {
    /**
     * A JPEG-encoded preview frame — the **Swift-helper** shape, which has to encode
     * because frames cross a pipe.
     */
    data class Frame(val jpeg: ByteArray) : ScanEvent

    /**
     * A raw BGRA preview frame — the **in-process** shape (§13 D2). Nothing crosses a
     * process boundary, so there is no reason to encode and re-decode a JPEG per frame;
     * Skia rasters these bytes directly.
     *
     * ⚠ [bytesPerRow] is **not** necessarily `width * 4` — CoreVideo pads rows for
     * alignment, so any consumer must step by the stride.
     */
    data class RawFrame(
        val bgra: ByteArray,
        val width: Int,
        val height: Int,
        val bytesPerRow: Int,
    ) : ScanEvent

    /** Terminal outcome; the stream completes after this. */
    data class Result(val outcome: ScanOutcome) : ScanEvent
}

/** Terminal result of a scan attempt. */
internal sealed interface ScanOutcome {
    data class Decoded(val payload: String) : ScanOutcome
    data object Cancelled : ScanOutcome
    data object PermissionDenied : ScanOutcome
    data object NoCamera : ScanOutcome
    data class Failed(val reason: String) : ScanOutcome

    /**
     * The helper just granted camera access for the first time and exited
     * without starting the session (it must not render a preview while the
     * permission dialog is up — that SIGKILLs the host app). The scanner
     * re-spawns a fresh helper that finds `.authorized`. Internal to the
     * scanner; never surfaced to the UI.
     */
    data object NeedsRetry : ScanOutcome
}

/** Desktop is a single Kotlin target, so Mac-vs-rest is a runtime decision. */
internal fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

// --- Framed stdout protocol (must match qr-scan-helper.swift) ---------------
//   [1-byte type][4-byte big-endian length][payload]

internal const val TYPE_FRAME = 0x01
internal const val TYPE_RESULT = 0x02
private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

/**
 * Parses the helper's framed stdout into [ScanEvent]s. A clean EOF or a partial
 * frame (helper killed mid-stream) yields [ScanOutcome.Cancelled]; a malformed
 * frame yields [ScanOutcome.Failed]. Pure over an [InputStream] so it's unit
 * testable without a camera.
 */
internal fun parseFramedStream(input: InputStream): Flow<ScanEvent> = flow {
    val data = DataInputStream(input)
    try {
        while (true) {
            val type = data.read()
            if (type == -1) break
            val length = data.readInt()
            if (length < 0 || length > MAX_FRAME_BYTES) {
                emit(ScanEvent.Result(ScanOutcome.Failed("bad frame length $length")))
                return@flow
            }
            val payload = ByteArray(length)
            data.readFully(payload)
            when (type) {
                TYPE_FRAME -> emit(ScanEvent.Frame(payload))
                TYPE_RESULT -> {
                    emit(ScanEvent.Result(parseResult(payload.decodeToString())))
                    return@flow
                }
                else -> {
                    emit(ScanEvent.Result(ScanOutcome.Failed("bad frame type $type")))
                    return@flow
                }
            }
        }
    } catch (_: IOException) {
        // Helper died mid-frame / stream broke — fall through to Cancelled
        // rather than letting the error propagate out of the flow (which would
        // crash the collecting LaunchedEffect). EOFException is an IOException.
    }
    emit(ScanEvent.Result(ScanOutcome.Cancelled))
}

/**
 * Reads the helper's framed stdout and returns the first RESULT payload's raw
 * text, ignoring any FRAME bytes (used by the `probe` path, which emits a single
 * RESULT and no frames). Null on EOF/short read. Pure over an [InputStream].
 */
internal fun readFirstResult(input: InputStream): String? {
    val data = DataInputStream(input)
    return try {
        while (true) {
            val type = data.read()
            if (type == -1) return null
            val length = data.readInt()
            if (length < 0 || length > MAX_FRAME_BYTES) return null
            val payload = ByteArray(length)
            data.readFully(payload)
            if (type == TYPE_RESULT) return payload.decodeToString()
            // else FRAME — skip and keep reading.
        }
        @Suppress("UNREACHABLE_CODE") null
    } catch (_: IOException) {
        null
    }
}

/** Maps a `probe`-mode RESULT payload to a [CameraPermission]. */
internal fun parsePermissionResult(text: String?): CameraPermission =
    when (text?.substringBefore('\t')) {
        "authorized" -> CameraPermission.Authorized
        "not_determined" -> CameraPermission.NotDetermined
        "denied" -> CameraPermission.Denied
        else -> CameraPermission.NoCamera
    }

/** Maps a RESULT payload (tab-separated `status[\targ]`) to a [ScanOutcome]. */
internal fun parseResult(text: String): ScanOutcome {
    val tab = text.indexOf('\t')
    val status = if (tab >= 0) text.substring(0, tab) else text
    val arg = if (tab >= 0) text.substring(tab + 1) else ""
    return when (status) {
        "decoded" -> ScanOutcome.Decoded(arg)
        "permission_denied" -> ScanOutcome.PermissionDenied
        "no_camera" -> ScanOutcome.NoCamera
        "retry" -> ScanOutcome.NeedsRetry
        "failed" -> ScanOutcome.Failed(arg)
        else -> ScanOutcome.Failed("unknown status: $status")
    }
}
