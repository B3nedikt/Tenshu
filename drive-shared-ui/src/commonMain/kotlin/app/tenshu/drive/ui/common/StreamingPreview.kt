package app.tenshu.drive.ui.common

/**
 * Whether this platform can stream a preview through a seekable, on-demand-decrypting source
 * (D2b, `docs/proposals/drive-streaming-preview.md` §3.5) rather than decrypting the whole file
 * to a temp file first (D2a). Android API 26+ has `StorageManager.openProxyFileDescriptor`; below
 * that — and on desktop (no embeddable player at all) — this is `false` and the caller falls back
 * to the D2a temp-file path. The video VM gates on this exactly as it gates on
 * [isVideoPlaybackSupported] (S4).
 */
expect fun isStreamingPreviewSupported(): Boolean
