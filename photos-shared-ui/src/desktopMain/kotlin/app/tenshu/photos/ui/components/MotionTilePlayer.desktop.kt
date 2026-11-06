package app.tenshu.photos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.tenshu.macos.ffi.BgraFrame
import app.tenshu.macos.video.MacVideoPlayer
import app.tenshu.macos.video.MacVideoSession
import app.tenshu.photos.model.MotionTurn
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.file.Files

/**
 * macOS only, through AVFoundation over the `:macos-helpers` FFM bridge — the same bridge Drive's
 * viewer uses (`VideoPlayback.desktop.kt`), for the same reason: the JVM ships no decoder, and
 * Windows and Linux have nothing equivalent, so they report `false` and the grid stays static.
 *
 * ⚠⚠ `runCatching`, and not decoration: `:photos-shared-ui` is on `jvmToolchain(17)` and the
 * bridge needs `java.lang.foreign` (22+), so merely *touching* `MacVideoPlayer` in a `desktopTest`
 * dies with `NoClassDefFoundError` — an `Error`, which is why it is `Throwable` that is caught. The
 * packaged app runs on the embedded JDK 25 and never sees this branch.
 */
actual fun isMotionPlaybackSupported(): Boolean =
    runCatching { MacVideoPlayer.isAvailable }.getOrDefault(false)

/**
 * ⚠ The bridge opens a *path*, so the clip spends its turn in a temp file — ≤ 96 KB, deleted with
 * the turn. Frames are pumped off Compose's frame clock exactly as the viewer does it; the turn
 * ends when the item's position reaches its duration, which is the one end-of-clip signal the
 * session exposes.
 */
@Composable
actual fun MotionTilePlayer(
    turn: MotionTurn,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    val finished by rememberUpdatedState(onFinished)
    val playback = remember(turn) { openClip(turn.clip) }
    var frame by remember(turn) { mutableStateOf<ImageBitmap?>(null) }

    DisposableEffect(playback) {
        onDispose { playback?.close() }
    }

    LaunchedEffect(playback) {
        if (playback == null) {
            // Could not open: hold the slot for a nominal turn, then hand over (the contract).
            delay(MOTION_FAILED_TURN_MS)
            finished()
            return@LaunchedEffect
        }
        val session = playback.session
        session.play()
        while (true) {
            withFrameNanos { }
            if (session.hasFailed) {
                delay(MOTION_FAILED_TURN_MS)
                finished()
                return@LaunchedEffect
            }
            session.nextFrame()?.let { frame = it.toImageBitmap() }
            val position = session.positionSeconds
            val duration = session.durationSeconds
            if (position != null && duration != null && duration > 0.0 && position >= duration - END_EPSILON_S) {
                finished()
                return@LaunchedEffect
            }
        }
    }

    frame?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** An open session plus the temp file it plays from; both go when the turn does. */
private class ClipPlayback(val session: MacVideoSession, private val file: File) {
    fun close() {
        runCatching { session.close() }
        runCatching { file.delete() }
    }
}

private fun openClip(clip: ByteArray): ClipPlayback? = runCatching {
    val file = Files.createTempFile("motion-tile", ".mp4").toFile()
    file.writeBytes(clip)
    val session = MacVideoPlayer.open(file.absolutePath)
    if (session == null) {
        file.delete()
        null
    } else {
        ClipPlayback(session, file)
    }
}.getOrNull()

/** BGRA bytes → a Compose bitmap, at CoreVideo's own row stride (`VideoPlayback.desktop.kt`). */
private fun BgraFrame.toImageBitmap(): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(info, bgra, bytesPerRow)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

/** AVPlayer reports a position a few ms short of the duration at the end of a clip. */
private const val END_EPSILON_S = 0.05
