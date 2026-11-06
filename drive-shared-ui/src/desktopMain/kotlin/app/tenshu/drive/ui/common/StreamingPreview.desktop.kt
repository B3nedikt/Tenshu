package app.tenshu.drive.ui.common

// Desktop has no embeddable player (drive-content-preview.md §5 / drive-streaming-preview.md SQ6),
// so streaming preview is unsupported; the caller stays on the D2a temp-file path (where a player
// lands later, S9). Mirrors isVideoPlaybackSupported() = false.
actual fun isStreamingPreviewSupported(): Boolean = false
