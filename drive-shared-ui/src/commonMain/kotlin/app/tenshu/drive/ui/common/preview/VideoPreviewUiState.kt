package app.tenshu.drive.ui.common.preview

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.ui.common.VideoSource

/**
 * State for [VideoPreviewScreen]: a spinner while [isLoading], error text on [error], and the
 * player once [videoSource] (a streaming session or a decrypted temp file) is ready.
 *
 * ⚠ There is no "unsupported" state. A platform without a player never reaches this screen —
 * `App.kt`'s router gates on `isVideoPlaybackSupported()` and sends the file to
 * `UnsupportedPreviewScreen` instead, which can at least hand it to the OS.
 *
 * ⚠⚠ [videoSource] means **the bytes are reachable**, not **the picture is up**. The player's own
 * buffering is reported separately, out of `VideoPlayer` — see
 * [app.tenshu.drive.ui.common.VideoPlaybackState].
 */
data class VideoPreviewUiState(
    val videoSource: VideoSource? = null,
    val isLoading: Boolean = false,
    /**
     * ⚠⚠ A catalogue **key**, never a sentence (`core-error-copy-catalogue.md` §5.0). This was a
     * `String?` holding `RepositoryError.toString()`, which rendered the sealed class's own name on
     * screen. Screens resolve it with `driveLabel()`.
     */
    val error: ErrorCopyKey? = null,
)
