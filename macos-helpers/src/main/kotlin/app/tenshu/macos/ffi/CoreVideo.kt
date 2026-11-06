package app.tenshu.macos.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap

/**
 * One frame of raw BGRA bytes.
 *
 * ⚠ Declared here, beside the CoreVideo call that produces it, rather than in either consumer:
 * the camera preview and the video player both read frames out of a `CVPixelBuffer` by exactly
 * this path, and naming the shared type after one of them would have made the other look like
 * it was borrowing something.
 */
class BgraFrame(
    val bgra: ByteArray,
    val width: Int,
    val height: Int,
    /**
     * Row stride in bytes. **Not** necessarily `width * 4` — CoreVideo pads rows for
     * alignment, so any consumer that walks the buffer must step by this, not by width.
     */
    val bytesPerRow: Int,
)

/**
 * The CoreMedia/CoreVideo calls needed to get pixels out of a capture sample buffer.
 *
 * Plain C, no Objective-C dispatch — `CMSampleBuffer` and `CVPixelBuffer` are CoreFoundation
 * types, so these are ordinary downcalls. Deferred out of P1 on purpose: without a real
 * sample buffer to point them at, the only assertable thing would have been "the symbol
 * resolves" (`desktop-qr-scanning.md` §13.6).
 */
internal object CoreVideo {

    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()

    private val handles = ConcurrentHashMap<String, java.lang.invoke.MethodHandle>()

    private fun lookup(framework: String): SymbolLookup? = ObjC.framework(framework)

    private fun handle(
        framework: String,
        symbol: String,
        descriptor: FunctionDescriptor,
    ): java.lang.invoke.MethodHandle? {
        handles[symbol]?.let { return it }
        val address = lookup(framework)?.find(symbol)?.orElse(null) ?: return null
        val handle = linker!!.downcallHandle(address, descriptor)
        handles[symbol] = handle
        return handle
    }

    private val ptr = ValueLayout.ADDRESS
    private val i64 = ValueLayout.JAVA_LONG
    private val i32 = ValueLayout.JAVA_INT

    /**
     * Copy the BGRA pixels out of [sampleBuffer], or `null` if it carries no image buffer.
     *
     * **Copies, deliberately.** The base address is only valid between lock and unlock, and
     * CoreVideo recycles the buffer for the next frame the moment we let go — so anything
     * handed to a consumer that outlives the callback (a Compose bitmap, a decode queue)
     * must be our own memory, not the framework's.
     *
     * Read-only lock: this path never writes back, and telling CoreVideo so avoids the
     * write-back overhead on a buffer we are about to hand back.
     */
    fun readBgraFrame(sampleBuffer: MemorySegment): BgraFrame? =
        imageBufferOf(sampleBuffer)?.let(::readBgra)

    /**
     * The `CVPixelBuffer` inside a capture sample buffer, or `null`.
     *
     * Exposed separately so one frame can serve both consumers — the preview copy and Vision
     * detection — from a single fetch, rather than unwrapping the sample buffer twice.
     * ⚠ Valid only for the duration of the frame callback.
     */
    fun imageBufferOf(sampleBuffer: MemorySegment): MemorySegment? {
        if (ObjC.isNull(sampleBuffer)) return null
        val getImageBuffer = handle(
            "CoreMedia", "CMSampleBufferGetImageBuffer", FunctionDescriptor.of(ptr, ptr),
        ) ?: return null
        val pixelBuffer = getImageBuffer.invokeExact(sampleBuffer) as MemorySegment
        return pixelBuffer.takeIf { !ObjC.isNull(it) }
    }

    /** Copy the BGRA pixels out of an already-unwrapped [pixelBuffer]. */
    fun readBgra(pixelBuffer: MemorySegment): BgraFrame? {
        val lock = handle(
            "CoreVideo", "CVPixelBufferLockBaseAddress", FunctionDescriptor.of(i32, ptr, i64),
        ) ?: return null
        val unlock = handle(
            "CoreVideo", "CVPixelBufferUnlockBaseAddress", FunctionDescriptor.of(i32, ptr, i64),
        ) ?: return null

        val locked = lock.invokeExact(pixelBuffer, READ_ONLY) as Int
        if (locked != 0) return null
        try {
            val base = handle(
                "CoreVideo", "CVPixelBufferGetBaseAddress", FunctionDescriptor.of(ptr, ptr),
            )?.invokeExact(pixelBuffer) as MemorySegment? ?: return null
            if (ObjC.isNull(base)) return null

            val width = sizeOf("CVPixelBufferGetWidth", pixelBuffer)
            val height = sizeOf("CVPixelBufferGetHeight", pixelBuffer)
            val bytesPerRow = sizeOf("CVPixelBufferGetBytesPerRow", pixelBuffer)
            if (width <= 0 || height <= 0 || bytesPerRow <= 0) return null

            val size = bytesPerRow * height
            val bytes = base.reinterpret(size.toLong()).toArray(ValueLayout.JAVA_BYTE)
            return BgraFrame(
                bgra = bytes, width = width, height = height, bytesPerRow = bytesPerRow,
            )
        } finally {
            unlock.invokeExact(pixelBuffer, READ_ONLY) as Int
        }
    }

    private fun sizeOf(symbol: String, pixelBuffer: MemorySegment): Int {
        val handle = handle("CoreVideo", symbol, FunctionDescriptor.of(i64, ptr)) ?: return 0
        return (handle.invokeExact(pixelBuffer) as Long).toInt()
    }

    /** `kCVPixelBufferLock_ReadOnly`. */
    private const val READ_ONLY = 1L
}
