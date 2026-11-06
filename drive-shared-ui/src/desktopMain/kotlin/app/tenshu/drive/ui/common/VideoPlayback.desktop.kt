package app.tenshu.drive.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tenshu.macos.ffi.BgraFrame
import app.tenshu.macos.video.MacVideoPlayer
import app.tenshu.macos.video.MacVideoSession
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/**
 * Desktop playback exists on **macOS only**, through AVFoundation over the Panama/FFM
 * Objective-C bridge (`MacVideoPlayer`).
 *
 * ⚠⚠ The old answer here was a flat `false` with the note "no bundled codec, VLCJ/GStreamer are
 * out of scope" — true about the *JVM*, which ships no video decoder, and wrong as a conclusion.
 * KMP was never the constraint (iOS Drive plays the same files from Kotlin/Native), and neither
 * is macOS: `:macos-helpers` already drives AVFoundation in-process for the camera. Windows and
 * Linux genuinely have nothing equivalent, so they still report `false` and the router hands the
 * file to the OS's own player.
 *
 * ⚠⚠ **Not unit-testable from this module.** `:drive-shared-ui` is on `jvmToolchain(17)` and
 * `java.lang.foreign` arrived in 22, so anything here that reaches the bridge dies in a
 * `desktopTest` with `NoClassDefFoundError: java/lang/foreign/Arena` — the packaged app is fine
 * (jpackage embeds the JDK 25 runtime, `drive-desktop/build.gradle.kts` `FFM_JDK`). Coverage for
 * the FFM half therefore lives in `:macos-helpers` (`CoreMediaTest`), which compiles and *runs*
 * on 25. Do not try to write it here; it will look like a bug in the player.
 */
actual fun isVideoPlaybackSupported(): Boolean = MacVideoPlayer.isAvailable

@Composable
actual fun VideoPlayer(
    source: VideoSource,
    modifier: Modifier,
    onStateChanged: (VideoPlaybackState) -> Unit,
) {
    val report by rememberUpdatedState(onStateChanged)

    // ⚠ Only the D2a temp file. `isStreamingPreviewSupported()` is false on desktop, so the view
    // model never produces a Stream here — but a `null` session reports Failed rather than
    // spinning forever if that ever changes.
    val session: MacVideoSession? = remember(source) {
        (source as? VideoSource.File)?.let { MacVideoPlayer.open(it.path) }
    }

    DisposableEffect(session) {
        onDispose { session?.close() }
    }

    var frame by remember(session) { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember(session) { mutableStateOf(true) }
    var position by remember(session) { mutableStateOf(0.0) }
    var duration by remember(session) { mutableStateOf<Double?>(null) }
    // While the user drags, the slider shows the finger rather than the clock — otherwise each
    // frame's position write fights the drag and the handle jumps back under it.
    var scrubbing by remember(session) { mutableStateOf<Double?>(null) }

    LaunchedEffect(session) {
        if (session == null) {
            report(VideoPlaybackState.Failed("AVPlayer: asset would not open"))
            return@LaunchedEffect
        }
        session.play()

        // Compose's own frame clock is the pump — see MacVideoPlayer's KDoc for why there is no
        // CADisplayLink. `nextFrame()` returns null whenever the video has nothing new for this
        // instant, which at 60 Hz is most ticks.
        var rendered = false
        while (true) {
            withFrameNanos { }
            if (session.hasFailed) {
                report(VideoPlaybackState.Failed("AVPlayerItemStatusFailed"))
                return@LaunchedEffect
            }
            session.nextFrame()?.let { new ->
                frame = new.toImageBitmap()
                if (!rendered) {
                    rendered = true
                    report(VideoPlaybackState.Rendering)
                }
            }
            session.positionSeconds?.let { position = it }
            session.durationSeconds?.let { if (it > 0.0) duration = it }
        }
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            frame?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The transport is ours because the picture is: AVFoundation is handing over pixels, not
        // a view, so there are no platform controls to inherit. Audio still comes from AVPlayer.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    playing = !playing
                    if (playing) session?.play() else session?.pause()
                },
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }

            Text(formatTime(scrubbing ?: position))

            val total = duration
            Slider(
                value = ((scrubbing ?: position) / (total ?: 1.0)).toFloat().coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    total?.let { scrubbing = fraction * it }
                },
                onValueChangeFinished = {
                    scrubbing?.let { session?.seekTo(it) }
                    scrubbing = null
                },
                enabled = total != null,
                modifier = Modifier.weight(1f),
            )

            Text(total?.let(::formatTime) ?: "--:--")
        }
    }
}

/**
 * BGRA bytes → a Compose bitmap.
 *
 * ⚠ [BgraFrame.bytesPerRow] rather than `width * 4`: CoreVideo pads rows for alignment, so a
 * frame whose width is not a multiple of the alignment skews diagonally when read at the
 * narrower stride.
 */
private fun BgraFrame.toImageBitmap(): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val bitmap = Bitmap()
    bitmap.allocPixels(info)
    bitmap.installPixels(info, bgra, bytesPerRow)
    // Frames are handed straight to Compose and never written again; marking the bitmap
    // immutable lets Skia hand out the pixels without a defensive copy per frame.
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

private fun formatTime(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val minutes = total / 60
    val remainder = total % 60
    return "$minutes:${remainder.toString().padStart(2, '0')}"
}
