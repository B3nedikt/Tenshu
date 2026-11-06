package org.tenshu.file.proxy

/**
 * Mints the `MOTION` thumbnail class (`photos-video-support.md` §4) from a video's bytes — a ~3 s,
 * 256 px, silent H.264/MP4 loop that starts at the poster frame's offset (D4).
 *
 * ⚠⚠ **A platform seam, not an operation.** The still classes are minted through the
 * `GetPreview` op because they *are* previews; a clip is not a bitmap and must never join that
 * ladder (§4.1, `MotionThumbnailClassTest`). Minting it therefore needs its own seam, and a plain
 * injected interface was chosen over a fourth schema operation: it is proxy-internal (an app never
 * touches video — `drive-synced-thumbnails.md` §12.4), it adds nothing to the wire or the
 * `SchemaShapeBaseline`, and a test can hand the upload path a fake.
 *
 * `null` from a platform means *this device does not mint*, which is the normal case everywhere
 * but Android today (§4.4: the JVM has no frame decoder, iOS is M6, macOS is M7). ⚠ It is also the
 * normal case on Android for a clip the encoder cannot handle, an over-cap result after the one
 * retry, or a window too short to be a loop (§12.6) — the tile keeps its static poster, which is
 * today's behaviour for every video everywhere. Best-effort by contract: an implementation must
 * never throw out of [render]; the caller treats a throw as `null` anyway so that a thumbnail can
 * never fail an upload.
 *
 * Wired as an optional constructor argument defaulting to `null` — and with a silent failure
 * mode: omitting it on a production site compiles,
 * runs, and mints nothing. `MotionThumbnailRendererWiringTest` audits the Android module for that.
 */
fun interface MotionThumbnailRenderer {

    /**
     * @param videoBytes the whole uploaded video, in memory (the upload already holds it — the
     *   poster path's `extractVideoPoster` reads the same array).
     * @param maxBytes the hard cap the result must sit under
     *   (`UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES`). The bitrate is *derived* from it
     *   (§12.2), so the cap is the input and the bitrate is not chosen.
     * @return the muxed MP4, or `null` for "no motion class for this file".
     */
    fun render(videoBytes: ByteArray, maxBytes: Int): ByteArray?
}
