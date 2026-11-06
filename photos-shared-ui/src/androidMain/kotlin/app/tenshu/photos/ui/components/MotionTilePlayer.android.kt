package app.tenshu.photos.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.tenshu.photos.model.MotionTurn
import kotlinx.coroutines.delay

actual fun isMotionPlaybackSupported(): Boolean = true

/**
 * `MediaPlayer` over the clip's bytes, drawn through a `TextureView`.
 *
 * ⚠ In memory, not a temp file and not a content provider: the clip is ≤ 96 KB and already in
 * hand, so a [MediaDataSource] over the array is the whole delivery path — nothing lands on disk
 * and nothing is registered anywhere. The viewer's `VideoView` + proxy-fd shape exists because it
 * streams an original; none of that applies to a derivative the rotation already holds.
 *
 * ⚠ A `TextureView` rather than a `SurfaceView`: it composites like any other view, so it can sit
 * inside a clipped, rounded, inset grid cell and be scrolled without punching a hole in the
 * hierarchy. Its transform is what gives the clip `ContentScale.Crop`, matching the poster
 * underneath — D4's alignment is only invisible if the two are framed identically.
 */
@Composable
actual fun MotionTilePlayer(
    turn: MotionTurn,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // `onFinished` is a fresh lambda on every recomposition; the listeners below are installed
    // once per turn, so they read it at call time (the `VideoPlayback.android.kt` reason).
    val finished by rememberUpdatedState(onFinished)

    // ⚠⚠ **One view per tile, a fresh player per turn — and the view must NOT be keyed on the turn.**
    // `AndroidView` invokes its factory once per composition node and never again, so a TextureView
    // re-created for turn N+1 never reaches the hierarchy: `onSurfaceTextureAvailable` never fires,
    // `prepareAsync` is never called, no callback ever arrives, and the rotation parks on this tile
    // for ever. The lone-video case — "next" is this same tile, so the node is never disposed — is
    // where it bites (`photos-video-support.md` §15.9).
    val textureView = remember { TextureView(context) }
    val playback = remember(turn) { TilePlayback(turn.clip) { finished() } }

    DisposableEffect(playback) {
        playback.attach(textureView)
        onDispose { playback.release() }
    }

    // ⚠⚠ Every turn is bounded, not only one that failed to open: `onFinished` must fire *always*,
    // and a player that goes silent stalls every other video on screen, not just its own tile.
    LaunchedEffect(playback) {
        delay(if (playback.failedToOpen) MOTION_FAILED_TURN_MS else MOTION_TURN_CEILING_MS)
        playback.endTurn()
    }

    AndroidView(factory = { textureView }, modifier = modifier)
}

/**
 * One turn's player. Owns the `MediaPlayer` and the surface hand-off, and guarantees `onFinished`
 * fires once — on completion or on the first error — and never after [release].
 */
private class TilePlayback(
    clip: ByteArray,
    private val onFinished: () -> Unit,
) : TextureView.SurfaceTextureListener {

    private val player = MediaPlayer()
    private var view: TextureView? = null
    private var surface: Surface? = null
    private var released = false
    private var reported = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var prepareStarted = false

    /** Set when `setDataSource` itself threw: there is nothing to prepare, and the turn must still end. */
    var failedToOpen: Boolean = false
        private set

    init {
        try {
            player.setDataSource(ByteArrayMediaDataSource(clip))
            player.setVolume(0f, 0f)
            player.isLooping = false
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { finish() }
            // ⚠⚠ Returning `true` suppresses the framework's own handling; the tile has no error
            // surface (D8), so the only correct response is to hand over.
            player.setOnErrorListener { _, _, _ -> finish(); true }
            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                applyCrop()
            }
        } catch (e: Throwable) {
            failedToOpen = true
        }
    }

    /**
     * Take over [textureView] for this turn. ⚠⚠ The surface may **already** be available — this is
     * the same view the previous turn drew into — and `onSurfaceTextureAvailable` only fires on the
     * transition, so waiting for it would mean waiting for ever. Start from the texture the view
     * already holds instead.
     */
    fun attach(textureView: TextureView) {
        view = textureView
        textureView.surfaceTextureListener = this
        val ready = textureView.surfaceTexture
        if (ready != null && textureView.isAvailable) {
            onSurfaceTextureAvailable(ready, textureView.width, textureView.height)
        }
    }

    /**
     * ⚠ Reachable more than once per turn: the view outlives the turn, so a tile scrolled off and
     * back re-creates its surface while the same player still holds the slot. Re-pointing the
     * player is right; preparing it twice throws, so [prepareStarted] gates it.
     */
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || failedToOpen) return
        val newSurface = Surface(texture)
        surface = newSurface
        try {
            player.setSurface(newSurface)
            if (!prepareStarted) {
                prepareStarted = true
                player.prepareAsync()
            }
        } catch (e: Throwable) {
            finish()
        }
        applyCrop()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        applyCrop()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (!released) runCatching { player.setSurface(null) }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /**
     * `ContentScale.Crop` for a `TextureView`, whose default is to stretch the video to its bounds:
     * scale both axes by the larger of the two cover ratios about the centre, and the overflow is
     * clipped by the cell — the same framing the poster underneath gets from `Image`.
     */
    private fun applyCrop() {
        val textureView = view ?: return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return
        val cover = maxOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val scaleX = videoWidth * cover / viewWidth
        val scaleY = videoHeight * cover / viewHeight
        textureView.setTransform(
            Matrix().apply { setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f) },
        )
    }

    /** The composable's watchdog: end this turn if nothing else has. Idempotent, like [finish]. */
    fun endTurn() = finish()

    private fun finish() {
        if (reported || released) return
        reported = true
        onFinished()
    }

    fun release() {
        if (released) return
        released = true
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.stop() }
        runCatching { player.release() }
        surface?.release()
        surface = null
    }
}

/** A read-only [MediaDataSource] over the clip's bytes. `MediaDataSource` is API 23+; minSdk is 24. */
private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size, (data.size - position).toInt())
        if (count <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun close() {}
}
