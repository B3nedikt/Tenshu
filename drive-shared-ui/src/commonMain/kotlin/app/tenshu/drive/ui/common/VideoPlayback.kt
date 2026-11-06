package app.tenshu.drive.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.drive.data.StreamingContentHandle

/**
 * Whether this platform can play video in-app (`docs/proposals/drive-content-preview.md`
 * §5). Android plays via the platform `VideoView`; desktop has no bundled codec/player
 * (VLCJ/GStreamer are out of scope), so it reports `false`. iOS is not a target of this
 * Compose module (its player is the SwiftUI `AVPlayer`).
 *
 * ⚠ **The router asks this, not the viewer** — `App.kt`'s `Destination.FilePreview` branch
 * sends a video to [app.tenshu.drive.ui.common.preview.VideoPreviewScreen] only where
 * playback exists, and everywhere else to `UnsupportedPreviewScreen` and its
 * open-in-another-app hand-off. Same shape as `isImagePreviewSupported` for desktop HEIC:
 * being a video isn't enough, the platform has to be able to play it. So the viewer has no
 * "unsupported" state to render, and a platform without a player still has a way to watch
 * the file.
 */
expect fun isVideoPlaybackSupported(): Boolean

/**
 * What the platform player is doing, reported out of [VideoPlayer] so the screen can keep a
 * spinner up until there is something to look at.
 *
 * ⚠⚠ **The gap this closes.** Byte *delivery* being ready is not playback being ready. The
 * view model reaches [app.tenshu.drive.ui.common.preview.VideoPreviewUiState.videoSource] as
 * soon as the streaming session opens — which is fast, it is one op — but the player then
 * has to pull the container header (and, for a moov-at-tail MP4, the file's *tail*) through
 * the proxy fd, each read a round trip to the relay. Handing the viewer a black `VideoView`
 * for those seconds with no spinner is what makes a working player look like a broken one.
 */
sealed interface VideoPlaybackState {

    /** Opening / seeking / re-filling: nothing to see yet, so the screen shows a spinner. */
    data object Buffering : VideoPlaybackState

    /** Frames are on screen. */
    data object Rendering : VideoPlaybackState

    /**
     * The platform player gave up on this file.
     *
     * ⚠⚠ [diagnostic] is **never rendered**. `core-error-copy-catalogue.md` §5.0 — a screen shows a
     * catalogue sentence, never an error's own message (such as `RepositoryError.toString()`). The
     * screen resolves `DriveErrorKeys.PREVIEW_VIDEO_UNPLAYABLE` instead; the field is named so that
     * putting it in a `Text` looks as wrong as it is.
     *
     * @property diagnostic platform failure codes (Android's `what`/`extra`) kept for a bug report.
     */
    data class Failed(val diagnostic: String) : VideoPlaybackState
}

/**
 * What the [VideoPlayer] plays — the two byte-delivery paths from
 * `docs/proposals/drive-streaming-preview.md` (§5 of `drive-content-preview.md`):
 *
 *  - [File] is the **D2a** path: the whole file decrypted to a private temp file
 *    ([writePreviewTempFile]); the player seeks against a local path. Used as the fallback when
 *    streaming is unavailable (API < 26 / desktop) or the stream can't be opened.
 *  - [Stream] is the **D2b** streaming path: an open [StreamingContentHandle] the player consumes
 *    through a seekable proxy fd, decrypting only the chunks each read touches — the whole-file
 *    plaintext never lands on disk (threat-model A5 streaming amendment). Android-only.
 */
sealed interface VideoSource {
    /** A seekable local path to the decrypted temp file (D2a). */
    data class File(val path: String) : VideoSource

    /**
     * An open streaming session (D2b). [fileName] carries the extension so the Android consumer
     * can derive a MIME hint for the player; the proxy-side session is released when the player's
     * fd is closed (and, as a backstop, when the screen leaves the back stack).
     */
    data class Stream(val handle: StreamingContentHandle, val fileName: String) : VideoSource
}

/**
 * Plays [source] — a decrypted temp file ([VideoSource.File]) or an on-demand streaming session
 * ([VideoSource.Stream]). Android embeds a `VideoView` + `MediaController`, feeding the streaming
 * session through a `content://` proxy fd; the desktop actual is an unsupported placeholder (never
 * reached at runtime, since the router gates on [isVideoPlaybackSupported]).
 *
 * [onStateChanged] reports [VideoPlaybackState] as the player moves through it. An implementation
 * MUST emit [VideoPlaybackState.Rendering] once there are frames and [VideoPlaybackState.Failed]
 * instead of letting the platform show its own error UI — the screen owns both, so a player that
 * stays silent leaves a spinner up forever.
 */
@Composable
expect fun VideoPlayer(
    source: VideoSource,
    modifier: Modifier,
    onStateChanged: (VideoPlaybackState) -> Unit,
)
