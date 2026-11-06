package app.tenshu.drive.ui.common

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

actual fun isVideoPlaybackSupported(): Boolean = true

@Composable
actual fun VideoPlayer(
    source: VideoSource,
    modifier: Modifier,
    onStateChanged: (VideoPlaybackState) -> Unit,
) {
    val context = LocalContext.current

    // ⚠ The listeners below are installed ONCE, inside `remember`, but `onStateChanged` is a fresh
    // lambda on every recomposition. Capturing it directly would pin the very first one and report
    // into a dead closure the moment the screen recomposes; `rememberUpdatedState` is read at call
    // time instead.
    val report by rememberUpdatedState(onStateChanged)

    // For a streaming source, register the open handle with the content provider so VideoView can
    // open it as a seekable content:// fd, and unregister on dispose. (The proxy fd's onRelease
    // closes the proxy-side session; this only releases the lookup entry.)
    val streamUri: Uri? = when (source) {
        is VideoSource.Stream -> {
            val uri = remember(source.handle.handle) {
                val ext = source.fileName.substringAfterLast('.', "").lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
                StreamingVideoProvider.register(context, source.handle, mime)
            }
            DisposableEffect(uri) { onDispose { StreamingVideoProvider.unregister(uri) } }
            uri
        }

        is VideoSource.File -> null
    }

    // VideoView wraps MediaPlayer. A File source plays straight from the app-private temp-file
    // path (D2a); a Stream source plays from the content:// uri whose reads decrypt on demand
    // (D2b). A MediaController supplies the play/pause/seek transport. The view is remembered so we
    // can stop playback (releasing the codec + fd, which closes the streaming session) on dispose.
    val videoView = remember(source) {
        VideoView(context).apply {
            setMediaController(
                MediaController(context).also { it.setAnchorView(this) }
            )
            when (source) {
                is VideoSource.File -> setVideoPath(source.path)
                is VideoSource.Stream -> setVideoURI(streamUri)
            }

            setOnPreparedListener {
                it.start()
                // ⚠ API < 26 has no first-frame signal on VideoView (`setOnInfoListener` landed in
                // 26), so "prepared" is the best we can say there. It is a fair approximation:
                // preparing is what reads the container header, which on the streaming path is the
                // slow part. On 26+ the info listener below supersedes this with the real thing —
                // and streaming is 26+ only anyway, so below it the source is a local temp file
                // that prepares near-instantly.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    report(VideoPlaybackState.Rendering)
                }
            }

            // ⚠⚠ Returning `true` suppresses VideoView's own "Can't play this video" AlertDialog —
            // unthemed, dismissable to a black screen, and it says nothing a bug report can use.
            // The screen renders the failure instead, carrying the codes.
            setOnErrorListener { _, what, extra ->
                report(VideoPlaybackState.Failed("MediaPlayer error $what ($extra)"))
                true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        // The one that matters: a frame has actually been drawn.
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                            report(VideoPlaybackState.Rendering)

                        // Mid-playback re-fill. On the streaming path a stall means chunk fetches
                        // are in flight, so the spinner comes back rather than the picture just
                        // freezing.
                        MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                            report(VideoPlaybackState.Buffering)

                        MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                            report(VideoPlaybackState.Rendering)
                    }
                    false // Never consumed: this is observation, not handling.
                }
            }
        }
    }

    DisposableEffect(videoView) {
        onDispose { videoView.stopPlayback() }
    }

    AndroidView(factory = { videoView }, modifier = modifier)
}
