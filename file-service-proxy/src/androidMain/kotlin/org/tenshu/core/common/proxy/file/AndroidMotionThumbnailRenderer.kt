package org.tenshu.core.common.proxy.file

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.motion.FrameDecimator
import org.tenshu.file.proxy.motion.MotionClipPlan
import org.tenshu.file.proxy.motion.MotionSize
import org.tenshu.file.proxy.motion.MotionWindow
import org.tenshu.file.proxy.motion.YuvFrame
import org.tenshu.file.proxy.motion.YuvPlaneScaler

/**
 * The Android `MOTION`-class mint (`photos-video-support.md` §4.4 / M4): `MediaExtractor` →
 * `MediaCodec` decoder → [YuvPlaneScaler] → `MediaCodec` H.264 encoder → `MediaMuxer`, framework
 * only, no new dependency — the same posture that chose `VideoView` over Media3.
 *
 * **Shape.** Both codecs run in ByteBuffer mode over flexible YUV 4:2:0 (`COLOR_FormatYUV420Flexible`,
 * which every video codec has supported since API 21), so the pixel path is three plane copies and
 * a pure-Kotlin area average per frame. Not a `Surface`, deliberately: surface-to-surface transcodes
 * take their frame timestamps from the render clock, so the clip's length would depend on how fast
 * the phone drew it, and they need an EGL context and a shader per frame — a thousand lines to
 * avoid a few milliseconds of CPU at 256 px. Here every frame carries its source presentation time
 * ([FrameDecimator.relativeUs]), the window is exact, and the scaler is unit-tested on the JVM.
 *
 * **Two passes.** Decode once into ≤ ~40 packed I420 frames (≈ 2 MB at 256 × 144) — then encode.
 * Keeping the frames is what makes the one bitrate retry ([MotionClipPlan.retryBitrateBps]) an
 * encode-only pass instead of a second decode of the original.
 *
 * **D4 exactly, not nearly.** The extractor seeks to the sync frame *closest* to the poster offset —
 * the same `CLOSEST_SYNC` rule `extractVideoPoster` uses — and the window is re-derived from the
 * sync frame's real timestamp ([MotionClipPlan.windowFrom]), so the clip's first frame *is* the
 * poster's keyframe and the hand-over from still to motion has nothing to pop.
 *
 * **Best-effort by contract.** Every failure — no video track, an undecodable or 10-bit source, a
 * window too short for a loop (§12.6), an encoder that rejects the size, an over-cap result after
 * the retry, a codec that stalls past its time budget — returns `null`, and the tile keeps the
 * static poster it has today. Nothing here may fail an upload.
 *
 * ⚠⚠ **Every attempt logs exactly one `Log.i` line — a decline included, and a decline says why.**
 * That is the device pass's only instrument, and the 2026-09-09 pass proved the point: two of seven
 * sources minted nothing, silently, and the two reasons turned out to be two separate defects
 * (§14.9 F3/F4). The success line also names the encoder, which on a phone is the field you want
 * first, and carries the bytes, bitrate, frames and wall time §12.5's `MediaCodec` re-run needs.
 */
internal class AndroidMotionThumbnailRenderer : MotionThumbnailRenderer {

    override fun render(videoBytes: ByteArray, maxBytes: Int): ByteArray? {
        val startedAt = SystemClock.elapsedRealtime()
        // ⚠⚠ Every `return null` below states why, and the decline is logged. A silent decline is
        // indistinguishable from a silent bug: on the 2026-09-09 device pass two of seven sources
        // minted nothing and the log said nothing, and finding out why meant pulling the sources off
        // the phone and parsing their boxes by hand (§14.9). The reasons are the runbook.
        var declined: String? = null
        val decline: (String) -> Nothing? = { reason -> declined = reason; null }
        return try {
            val clip = decodeClip(videoBytes, decline)
                ?: return null.also { logDecline(declined, startedAt) }
            val bitrate = MotionClipPlan.targetBitrateBps(maxBytes)
            var encoded = encode(clip, bitrate, decline)
                ?: return null.also { logDecline(declined, startedAt) }
            var finalBitrate = bitrate
            var retried = false
            if (encoded.bytes.size > maxBytes) {
                retried = true
                finalBitrate = MotionClipPlan.retryBitrateBps(bitrate, encoded.bytes.size, maxBytes)
                encoded = encode(clip, finalBitrate, decline)
                    ?: return null.also { logDecline(declined, startedAt) }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val verdict = if (encoded.bytes.size <= maxBytes) "stored" else "DROPPED over cap"
            Log.i(
                TAG,
                "motion class $verdict: ${encoded.bytes.size} B (cap $maxBytes) @ $finalBitrate bps" +
                    (if (retried) " after retry from $bitrate bps" else "") +
                    ", ${clip.frames.size} frames, window ${clip.window.startUs}-${clip.window.endUs} us" +
                    " of ${clip.durationUs} us, ${clip.sourceWidth}x${clip.sourceHeight} rot ${clip.rotation}" +
                    " -> ${clip.size.width}x${clip.size.height}, ${clip.mime} via ${encoded.encoder}," +
                    " ${elapsedMs} ms",
            )
            encoded.bytes.takeIf { it.size <= maxBytes }
        } catch (e: Throwable) {
            Log.w(TAG, "motion class not minted: $e")
            null
        }
    }

    private fun logDecline(reason: String?, startedAt: Long) {
        Log.i(
            TAG,
            "motion class not minted: ${reason ?: "no reason recorded"}," +
                " ${SystemClock.elapsedRealtime() - startedAt} ms",
        )
    }

    private class DecodedClip(
        val size: MotionSize,
        val window: MotionWindow,
        val frames: List<YuvFrame>,
        val durationUs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotation: Int,
        val mime: String,
    )

    /**
     * Decodes the clip window into packed, scaled I420 frames. `null` for anything that is not a
     * decodable video with a window worth storing.
     */
    private fun decodeClip(videoBytes: ByteArray, decline: (String) -> Nothing?): DecodedClip? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(InMemoryMediaDataSource(videoBytes))
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return decline("no video track in ${extractor.trackCount} tracks")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return decline("video track has no mime")
            // ⚠ The **track's** duration, which is not the container's: §12.6's 1.0 s clip declares
            // 1 002 700 us in `mvhd` and 970 744 us on its video track. Nothing is refused on it —
            // it only picks the seek target — because it is also simply absent (0) on a fragmented
            // MP4. See MotionClipPlan.windowFrom / storesAsLoop and §14.9.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                MotionClipPlan.normaliseRotation(format.getInteger(MediaFormat.KEY_ROTATION))
            } else {
                0
            }

            extractor.selectTrack(trackIndex)
            extractor.seekTo(MotionClipPlan.seekTargetUs(durationUs), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var syncUs = extractor.sampleTime
            if (syncUs < 0L) {
                // The seek target sat past the last sample (a source whose declared duration lies).
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                syncUs = extractor.sampleTime
            }
            if (syncUs < 0L) return decline("no sync sample (duration $durationUs us)")
            // The window starts on the keyframe the poster was taken from (D4). A sync frame that
            // leaves no window worth storing — a keyframe-sparse clip whose nearest sync sits near
            // the end — is a clip with no motion class, not one with a different start.
            val window = MotionClipPlan.windowFrom(syncUs, durationUs)
                ?: return decline("sync frame at $syncUs us leaves no window in $durationUs us")

            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            val decimator = FrameDecimator(window)
            val frames = ArrayList<YuvFrame>(MotionClipPlan.FRAME_RATE * 3 + 4)
            val info = MediaCodec.BufferInfo()
            val scratch = PlaneScratch()
            var size: MotionSize? = null
            var sourceWidth = 0
            var sourceHeight = 0
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + DECODE_BUDGET_MS

            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    return decline("decode exceeded ${DECODE_BUDGET_MS} ms (${frames.size} frames so far)")
                }

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                            ?: return decline("decoder returned no input buffer")
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        val sampleTimeUs = extractor.sampleTime
                        // Samples arrive in decode order, so a B-frame inside the window can follow a
                        // sample past it: feed a little beyond the end before flushing with EOS.
                        if (sampleSize < 0 || sampleTimeUs > window.endUs + INPUT_LOOKAHEAD_US) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = info.presentationTimeUs
                    if (info.size > 0 && decimator.keeps(ptsUs)) {
                        val image = codec.getOutputImage(outIndex)
                        if (image != null) {
                            try {
                                // 8-bit 4:2:0 only. A 10-bit (P010) decode has 2-byte samples the
                                // scaler would read as noise; that source gets no motion class.
                                if (image.format != ImageFormat.YUV_420_888) {
                                    return decline("decoder output format ${image.format} is not 8-bit YUV_420_888")
                                }
                                val frameSize = size ?: run {
                                    sourceWidth = image.width
                                    sourceHeight = image.height
                                    MotionClipPlan.outputSize(sourceWidth, sourceHeight, rotation)
                                        ?: return decline("no output size for ${image.width}x${image.height} rot $rotation")
                                }.also { size = it }
                                if (image.width == sourceWidth && image.height == sourceHeight) {
                                    frames += scaledFrame(image, frameSize, rotation, decimator.relativeUs(ptsUs), scratch)
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos || decimator.isPast(ptsUs)) outputDone = true
                }
            }

            val outputSize = size ?: return decline("no frame decoded in window ${window.startUs}-${window.endUs} us")
            // ⚠⚠ The floor, applied to what DECODED — see MotionClipPlan.storesAsLoop. The nominal
            // window cannot judge this: it is derived from a declared duration that may be the wrong
            // track's, or absent entirely.
            val spanUs = if (frames.isEmpty()) 0L else frames.last().presentationUs - frames.first().presentationUs
            if (!MotionClipPlan.storesAsLoop(frames.size, spanUs)) {
                return decline("decoded ${frames.size} frames spanning $spanUs us — not a loop")
            }
            return DecodedClip(
                size = outputSize,
                window = window,
                frames = frames,
                durationUs = durationUs,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                rotation = rotation,
                mime = mime,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Reusable copy buffers for the decoder's (possibly direct) plane buffers. */
    private class PlaneScratch {
        var bytes: ByteArray = ByteArray(0)

        fun fill(plane: Image.Plane): Int {
            val buffer = plane.buffer.duplicate()
            buffer.rewind()
            val length = buffer.remaining()
            if (bytes.size < length) bytes = ByteArray(length)
            buffer.get(bytes, 0, length)
            return length
        }
    }

    private fun scaledFrame(
        image: Image,
        size: MotionSize,
        rotation: Int,
        presentationUs: Long,
        scratch: PlaneScratch,
    ): YuvFrame {
        val frame = YuvFrame(width = size.width, height = size.height, presentationUs = presentationUs)
        val planes = image.planes
        check(planes.size == 3) { "expected 3 planes, got ${planes.size}" }
        val chromaWidth = (image.width + 1) / 2
        val chromaHeight = (image.height + 1) / 2
        scalePlane(planes[0], image.width, image.height, frame.width, frame.height, rotation, frame, frame.yOffset, scratch)
        scalePlane(planes[1], chromaWidth, chromaHeight, frame.chromaWidth, frame.chromaHeight, rotation, frame, frame.uOffset, scratch)
        scalePlane(planes[2], chromaWidth, chromaHeight, frame.chromaWidth, frame.chromaHeight, rotation, frame, frame.vOffset, scratch)
        return frame
    }

    private fun scalePlane(
        plane: Image.Plane,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        rotation: Int,
        frame: YuvFrame,
        dstOffset: Int,
        scratch: PlaneScratch,
    ) {
        val length = scratch.fill(plane)
        YuvPlaneScaler.scale(
            src = scratch.bytes,
            srcOffset = 0,
            srcLength = length,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            rotationDegrees = rotation,
            dst = frame.data,
            dstOffset = dstOffset,
        )
    }

    /** An encode's result: the muxed MP4 and the codec that produced it (the device pass reads it). */
    private class Encoded(val bytes: ByteArray, val encoder: String)

    /** Encodes [clip] at [bitrateBps] into a muxed MP4, or `null` if the platform could not. */
    private fun encode(clip: DecodedClip, bitrateBps: Int, decline: (String) -> Nothing?): Encoded? {
        val size = clip.size
        val encoderName = findEncoder(size)
            ?: return decline("no AVC encoder takes flexible YUV at ${size.width}x${size.height}")
        val format = MediaFormat.createVideoFormat(MIME_AVC, size.width, size.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, MotionClipPlan.FRAME_RATE)
            // Longer than the clip: one keyframe, at the loop's seek target, and no bytes on a second.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
        }
        val encoder = MediaCodec.createByCodecName(encoderName)
        val tempFile = java.io.File.createTempFile("motion", ".mp4")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val encoderCapabilities = encoder.codecInfo.getCapabilitiesForType(MIME_AVC).encoderCapabilities
            if (encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val mux = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var track = -1
            var next = 0
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + ENCODE_BUDGET_MS

            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    return decline("$encoderName exceeded ${ENCODE_BUDGET_MS} ms at $bitrateBps bps")
                }

                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (next == clip.frames.size) {
                            val eosUs = clip.frames.last().presentationUs + MotionClipPlan.FRAME_INTERVAL_US
                            encoder.queueInputBuffer(inIndex, 0, 0, eosUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val frame = clip.frames[next++]
                            val image = encoder.getInputImage(inIndex)
                                ?: return decline("$encoderName gave no input Image for flexible YUV")
                            fillImage(image, frame)
                            encoder.queueInputBuffer(inIndex, 0, frame.byteCount, frame.presentationUs, 0)
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return decline("$encoderName changed output format twice")
                        track = mux.addTrack(encoder.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }

                    outIndex >= 0 -> {
                        val buffer = encoder.getOutputBuffer(outIndex)
                            ?: return decline("$encoderName gave no output buffer")
                        // The muxer takes the codec config (SPS/PPS) from the track format, not the stream.
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0) {
                            if (!muxerStarted) return decline("$encoderName emitted a sample before its format")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            mux.writeSampleData(track, buffer, info)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }

            if (!muxerStarted) return decline("$encoderName produced no track")
            mux.stop()
            muxerStarted = false
            return Encoded(tempFile.readBytes(), encoderName)
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { tempFile.delete() }
        }
    }

    /**
     * The first AVC encoder that takes flexible YUV at this size. `MediaCodecList` lists hardware
     * encoders before software ones, so the phone's own encoder wins where it accepts the tile
     * size and the framework's software encoder catches the rest.
     */
    private fun findEncoder(size: MotionSize): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME_AVC, ignoreCase = true) } }
            .firstOrNull { codecInfo ->
                runCatching {
                    val capabilities = codecInfo.getCapabilitiesForType(MIME_AVC)
                    capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) &&
                        capabilities.videoCapabilities?.isSizeSupported(size.width, size.height) == true
                }.getOrDefault(false)
            }?.name

    private fun fillImage(image: Image, frame: YuvFrame) {
        val planes = image.planes
        check(planes.size == 3) { "encoder image has ${planes.size} planes" }
        val width = minOf(image.width, frame.width)
        val height = minOf(image.height, frame.height)
        copyPlane(frame.data, frame.yOffset, frame.width, width, height, planes[0])
        copyPlane(frame.data, frame.uOffset, frame.chromaWidth, width / 2, height / 2, planes[1])
        copyPlane(frame.data, frame.vOffset, frame.chromaWidth, width / 2, height / 2, planes[2])
    }

    private fun copyPlane(
        src: ByteArray,
        srcOffset: Int,
        srcRowLength: Int,
        width: Int,
        height: Int,
        plane: Image.Plane,
    ) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.put(src, srcOffset + y * srcRowLength, width)
            }
        } else {
            for (y in 0 until height) {
                val rowStart = y * rowStride
                val srcRow = srcOffset + y * srcRowLength
                for (x in 0 until width) {
                    buffer.put(rowStart + x * pixelStride, src[srcRow + x])
                }
            }
        }
    }

    private companion object {
        const val TAG = "MotionThumbnail"
        const val MIME_AVC = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val I_FRAME_INTERVAL_S = 10

        /** How far past the window's end the extractor keeps feeding, for reordered frames. */
        const val INPUT_LOOKAHEAD_US = 1_000_000L

        /** ⚠ A stalled codec must not stall an upload: past these, the class is simply not minted. */
        const val DECODE_BUDGET_MS = 30_000L
        const val ENCODE_BUDGET_MS = 15_000L
    }
}
