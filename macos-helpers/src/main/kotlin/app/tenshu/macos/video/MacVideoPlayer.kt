package app.tenshu.macos.video

import app.tenshu.macos.ffi.BgraFrame
import app.tenshu.macos.ffi.CoreMedia
import app.tenshu.macos.ffi.CoreVideo
import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process video playback through AVFoundation, driven from the JVM over FFM — the same
 * bridge `MacCamera` uses for capture, pointed at `AVPlayer` instead of `AVCaptureSession`.
 *
 * **Why this exists.** The JDK ships no video decoder, so Compose Desktop had no player and a
 * video opened onto a dead end (`drive-content-preview.md` §5 deferred desktop indefinitely).
 * That was never a KMP limit — iOS Drive plays the same files from Kotlin/Native today — and on
 * macOS it is not a platform limit either: AVFoundation is Objective-C, and
 * `ui-core`'s `FfmMacQrCameraScanner` already proves the JVM can drive it in-process and pump
 * `CVPixelBuffer`s into Compose. This is that pipeline with a different source.
 *
 * **The shape.** `AVPlayer` owns decode, the clock and **audio**; we only take pictures off it.
 * An `AVPlayerItemVideoOutput` attached to the item hands out a `CVPixelBuffer` for a given
 * item time, which goes through the same [CoreVideo.readBgra] the camera preview uses. Nothing
 * here decodes anything itself — that is the whole point of using the platform.
 *
 * ⚠ **Not a `CADisplayLink`.** The pull is the caller's: Compose already has a frame clock
 * (`withFrameNanos`), so the composable asks for [nextFrame] once per frame it is going to draw
 * anyway. Adding a display link would mean a second clock delivering on a CoreVideo thread that
 * then has to hand off to Compose's — more machinery, and it would still be rate-limited by the
 * frame clock at the end of it.
 *
 * ⚠⚠ **macOS only, and it says so** rather than pretending: [isAvailable] is false off macOS
 * (or without a usable FFM linker), and `VideoPlayback.desktop.kt` reports that through
 * `isVideoPlaybackSupported()` so the *router* sends the file to the OS hand-off instead.
 * Windows and Linux have no equivalent here and are expected to keep taking that route.
 */
object MacVideoPlayer {

    /** Whether in-process playback can be attempted here. */
    val isAvailable: Boolean
        get() = ObjC.isAvailable && !ObjC.isNull(ObjC.cls("AVPlayer"))

    /**
     * Open [filePath] — a decrypted local file — for playback, or `null` if AVFoundation is
     * unreachable or refuses the asset.
     *
     * ⚠ A local path on purpose. The D2b streaming path (an `AVAssetResourceLoaderDelegate` over
     * `ReadContentRange`, as iOS does in `StreamingVideoLoader.swift`) is the next slice and
     * needs a runtime-registered delegate class; this one takes the D2a temp file the video view
     * model already produces when streaming is unavailable, so desktop gets playback without
     * waiting for it.
     */
    fun open(filePath: String): MacVideoSession? {
        if (!isAvailable) return null
        return ObjC.autoreleasePool {
            val url = ObjC.send(
                ObjC.cls("NSURL"), "fileURLWithPath:", ObjC.nsString(filePath),
            )
            if (ObjC.isNull(url)) return@autoreleasePool null

            val item = ObjC.send(ObjC.cls("AVPlayerItem"), "playerItemWithURL:", url)
            if (ObjC.isNull(item)) return@autoreleasePool null

            // BGRA, so the frame lands in the one format CoreVideo.readBgra + Compose agree on.
            // The dictionary is built by hand because the key is a framework constant compared by
            // identity, exactly like the camera's pixel-format attributes.
            val attributes = pixelBufferAttributes() ?: return@autoreleasePool null
            val output = ObjC.send(
                ObjC.send(ObjC.cls("AVPlayerItemVideoOutput"), "alloc"),
                "initWithPixelBufferAttributes:",
                attributes,
            )
            if (ObjC.isNull(output)) return@autoreleasePool null
            ObjC.sendVoid(item, "addOutput:", output)

            val player = ObjC.send(ObjC.cls("AVPlayer"), "playerWithPlayerItem:", item)
            if (ObjC.isNull(player)) {
                ObjC.release(output)
                return@autoreleasePool null
            }

            // Retained because everything above is autoreleased and this pool drains on return.
            MacVideoSession(
                player = ObjC.send(player, "retain"),
                item = ObjC.send(item, "retain"),
                output = output,
            )
        }
    }

    /** `@{ kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA) }`. */
    private fun pixelBufferAttributes(): MemorySegment? {
        val key = ObjC.frameworkConstant("CoreVideo", "kCVPixelBufferPixelFormatTypeKey")
        if (ObjC.isNull(key)) return null
        val value = ObjC.sendWithInt(ObjC.cls("NSNumber"), "numberWithInt:", BGRA_32)
        if (ObjC.isNull(value)) return null
        val dictionary = ObjC.send(
            ObjC.cls("NSDictionary"), "dictionaryWithObject:forKey:", value, key,
        )
        return dictionary.takeIf { !ObjC.isNull(it) }
    }

    /** `kCVPixelFormatType_32BGRA` — the FourCC `'BGRA'`, which is what the constant is. */
    private const val BGRA_32 = 0x42475241
}

/**
 * One open video. Not thread-safe by construction: the composable that owns it drives every
 * call from Compose's frame clock, which is one thread.
 *
 * ⚠⚠ **Everything here is a +1 Objective-C reference.** [close] is not optional housekeeping —
 * an `AVPlayer` that is never released keeps a decode session and its audio unit alive, so a
 * leaked one goes on making sound after its screen is gone.
 */
class MacVideoSession internal constructor(
    private val player: MemorySegment,
    private val item: MemorySegment,
    private val output: MemorySegment,
) {
    private val closed = AtomicBoolean(false)

    /** Total length in seconds, or `null` until the item is ready (or for a live stream). */
    val durationSeconds: Double? get() = timeInSeconds(item, "duration")

    /** Where playback is now, in seconds, or `null` before the item is ready. */
    val positionSeconds: Double? get() = timeInSeconds(player, "currentTime")

    /**
     * Read a `CMTime`-returning property as seconds.
     *
     * ⚠ **A confined arena per call, deliberately not one long-lived scratch.** A returned
     * struct is written into caller memory, so every read allocates 24 bytes somewhere. Holding
     * a session-lived `Arena` for it would never free them, and this is read from the frame
     * clock: twice a frame at 60 Hz is a slow, invisible leak for exactly as long as the video
     * is open. The allocation is stack-cheap and the scope ends with the read.
     */
    private fun timeInSeconds(receiver: MemorySegment, selector: String): Double? {
        if (closed.get()) return null
        return Arena.ofConfined().use { scratch ->
            CoreMedia.sendReturningTime(scratch, receiver, selector)?.let(CoreMedia::seconds)
        }
    }

    /**
     * Whether the item failed to load — a codec macOS won't decode, a truncated file.
     *
     * ⚠ `AVPlayerItemStatusFailed` is 2. Checked rather than assumed, because a decode failure
     * is otherwise indistinguishable from a slow first frame: both are "no picture yet".
     */
    val hasFailed: Boolean
        get() = !closed.get() && ObjC.sendLong(item, "status") == STATUS_FAILED

    fun play() {
        if (!closed.get()) ObjC.sendVoid(player, "play")
    }

    fun pause() {
        if (!closed.get()) ObjC.sendVoid(player, "pause")
    }

    /** Seek to [seconds]. Clamped by AVFoundation; a seek past the end lands at the end. */
    fun seekTo(seconds: Double) {
        if (closed.get()) return
        Arena.ofConfined().use { scratch ->
            val time = CoreMedia.ofSeconds(scratch, seconds) ?: return
            CoreMedia.sendVoidWithTime(player, "seekToTime:", time)
        }
    }

    /**
     * The next decoded frame, or `null` when there isn't a new one for this instant — which is
     * the ordinary answer most of the time, since the frame clock runs faster than most video.
     *
     * ⚠⚠ **The pixel buffer is +1 and is released here.** `copyPixelBufferForItemTime:` follows
     * the `copy` convention, so every frame not released leaks a full frame buffer — at 60 Hz
     * that is measured in hundreds of megabytes per minute. [CoreVideo.readBgra] copies the
     * bytes out first, which it has to do anyway: the base address is only valid while locked.
     */
    fun nextFrame(): BgraFrame? {
        if (closed.get()) return null
        return Arena.ofConfined().use { scratch ->
            val hostTime = CoreMedia.currentMediaTime()
            val itemTime = CoreMedia.sendReturningTime(
                scratch, output, "itemTimeForHostTime:", hostTime,
            ) ?: return@use null

            if (!CoreMedia.sendBoolWithTime(output, "hasNewPixelBufferForItemTime:", itemTime)) {
                return@use null
            }
            val pixelBuffer = CoreMedia.copyPixelBufferForItemTime(output, itemTime)
                ?: return@use null
            try {
                CoreVideo.readBgra(pixelBuffer)
            } finally {
                CoreMedia.releasePixelBuffer(pixelBuffer)
            }
        }
    }

    /** Stop playback and release everything. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ObjC.sendVoid(player, "pause")
        ObjC.sendVoid(item, "removeOutput:", output)
        ObjC.release(output)
        ObjC.release(item)
        ObjC.release(player)
    }

    private companion object {
        /** `AVPlayerItemStatusFailed`. */
        const val STATUS_FAILED = 2L
    }
}
