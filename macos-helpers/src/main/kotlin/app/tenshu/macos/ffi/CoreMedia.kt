package app.tenshu.macos.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * `CMTime` — CoreMedia's rational timestamp, and the one type that made AVFoundation playback
 * more than a copy of the camera path.
 *
 * **Why it needed its own file.** [ObjC] deliberately binds only pointer / integer / void
 * signatures. Every time-bearing AVFoundation call takes or returns a `CMTime` **by value**:
 * `seekToTime:`, `itemTimeForHostTime:`, `hasNewPixelBufferForItemTime:`,
 * `copyPixelBufferForItemTime:itemTimeForDisplay:`, `duration`. There is no pointer-shaped way
 * around them — so the bridge grew [ObjC.msgSend] / [ObjC.msgSendReturningStruct], and this is
 * where the layout and the arithmetic live.
 *
 * **The layout** (`CMTime.h`) is `{ int64 value; int32 timescale; uint32 flags; int64 epoch }`.
 * ⚠ The 24 bytes are natural: `value` is 8-aligned, the two 32-bit fields pack into one 8-byte
 * slot, `epoch` is 8-aligned — no padding to declare. It is nonetheless **larger than 16 bytes**,
 * which is exactly the threshold that puts it in memory rather than registers and makes
 * [ObjC.msgSendReturningStruct]'s architecture split load-bearing.
 *
 * ⚠ A time is a rational, not a double: `seconds = value / timescale`. Converting through a
 * double is fine for a progress bar and wrong for anything that has to round-trip, so
 * [ofSeconds] goes through `CMTimeMakeWithSeconds` (a plain C call — struct returns from C are
 * FFM's ordinary business, no ObjC symbol split involved) rather than reconstructing the fields.
 */
internal object CoreMedia {

    /** `CMTime` — see the class KDoc for why the field order and the 24 bytes matter. */
    val CM_TIME: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("value"),
        ValueLayout.JAVA_INT.withName("timescale"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_LONG.withName("epoch"),
    ).withName("CMTime")

    /** `kCMTimeFlags_Valid`. A zeroed `CMTime` is *invalid*, which is a meaningful answer. */
    private const val FLAG_VALID = 1

    private val ptr = ValueLayout.ADDRESS
    private val f64 = ValueLayout.JAVA_DOUBLE
    private val i32 = ValueLayout.JAVA_INT

    private val handles = ConcurrentHashMap<String, MethodHandle>()

    private fun cHandle(symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
        handles[symbol]?.let { return it }
        val lookup: SymbolLookup = ObjC.framework("CoreMedia") ?: return null
        val address = lookup.find(symbol).orElse(null) ?: return null
        val handle = ObjC.linkerOrNull()?.downcallHandle(address, descriptor) ?: return null
        handles[symbol] = handle
        return handle
    }

    /**
     * A `CMTime` for [seconds], allocated in [arena].
     *
     * Built by `CMTimeMakeWithSeconds` rather than by hand so the timescale choice is
     * CoreMedia's. [preferredTimescale] 600 is the conventional video default — divisible by
     * 24, 25, 30 and 60, so the common frame rates land on exact values instead of drifting.
     */
    fun ofSeconds(
        arena: Arena,
        seconds: Double,
        preferredTimescale: Int = 600,
    ): MemorySegment? {
        val handle = cHandle(
            "CMTimeMakeWithSeconds", FunctionDescriptor.of(CM_TIME, f64, i32),
        ) ?: return null
        return handle.invokeWithArguments(arena, seconds, preferredTimescale) as MemorySegment?
    }

    /**
     * [time] in seconds, or `null` when it is invalid or indefinite — a live stream's duration,
     * or any time read before the item is ready.
     *
     * ⚠ Read the flags, don't just divide: an unset `CMTime` is all zeroes, and `0 / 0` would
     * hand the caller a `NaN` to propagate instead of an absence to handle.
     */
    fun seconds(time: MemorySegment): Double? {
        val flags = time.get(ValueLayout.JAVA_INT, 12L)
        if (flags and FLAG_VALID == 0) return null
        val timescale = time.get(ValueLayout.JAVA_INT, 8L)
        if (timescale == 0) return null
        val value = time.get(ValueLayout.JAVA_LONG, 0L)
        return value.toDouble() / timescale.toDouble()
    }

    /**
     * Send [selector] to [receiver] with no arguments and read the `CMTime` it returns, into
     * [arena]. `[AVPlayer currentTime]`, `[AVPlayerItem duration]`.
     */
    fun sendReturningTime(
        arena: Arena,
        receiver: MemorySegment,
        selector: String,
    ): MemorySegment? {
        val handle = ObjC.msgSendReturningStruct(
            FunctionDescriptor.of(CM_TIME, ptr, ptr),
        ) ?: return null
        return handle.invokeWithArguments(arena, receiver, ObjC.sel(selector)) as MemorySegment?
    }

    /**
     * Send [selector] to [receiver] with one `Float64` argument, reading back a `CMTime`.
     * `[AVPlayerItemVideoOutput itemTimeForHostTime:]`.
     */
    fun sendReturningTime(
        arena: Arena,
        receiver: MemorySegment,
        selector: String,
        hostTime: Double,
    ): MemorySegment? {
        val handle = ObjC.msgSendReturningStruct(
            FunctionDescriptor.of(CM_TIME, ptr, ptr, f64),
        ) ?: return null
        return handle.invokeWithArguments(arena, receiver, ObjC.sel(selector), hostTime)
            as MemorySegment?
    }

    /**
     * `+[NSValue valueWithCMTime:]` — box [time] so it can cross an API that takes an object.
     *
     * Kept here rather than at a call site because it is also the cheapest **probe** of the
     * struct-argument path: it takes the 24 bytes by value and hands back a plain pointer, so
     * it exercises the binding without needing a player, a file or a window.
     */
    fun sendReturningBoxedTime(nsValueClass: MemorySegment, time: MemorySegment): MemorySegment? {
        val handle = ObjC.msgSend(FunctionDescriptor.of(ptr, ptr, ptr, CM_TIME)) ?: return null
        return (handle.invokeWithArguments(nsValueClass, ObjC.sel("valueWithCMTime:"), time)
            as MemorySegment?)?.takeIf { !ObjC.isNull(it) }
    }

    /** Send [selector] to [receiver] with a single `CMTime` argument, for its side effect. */
    fun sendVoidWithTime(receiver: MemorySegment, selector: String, time: MemorySegment) {
        val handle = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, CM_TIME)) ?: return
        handle.invokeWithArguments(receiver, ObjC.sel(selector), time)
    }

    /** Send [selector] with a single `CMTime` argument and read the `BOOL` back. */
    fun sendBoolWithTime(
        receiver: MemorySegment,
        selector: String,
        time: MemorySegment,
    ): Boolean {
        val handle = ObjC.msgSend(
            FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ptr, ptr, CM_TIME),
        ) ?: return false
        return (handle.invokeWithArguments(receiver, ObjC.sel(selector), time) as Byte?)
            ?.toInt() != 0
    }

    /**
     * `copyPixelBufferForItemTime:itemTimeForDisplay:` — a `CMTime` argument and a pointer.
     *
     * ⚠ `copy` in the name is the ObjC ownership convention: the returned `CVPixelBuffer` is
     * **+1** and the caller must `CVBufferRelease` it, or every displayed frame leaks a full
     * frame buffer.
     */
    fun copyPixelBufferForItemTime(
        receiver: MemorySegment,
        time: MemorySegment,
    ): MemorySegment? {
        val handle = ObjC.msgSend(
            FunctionDescriptor.of(ptr, ptr, ptr, CM_TIME, ptr),
        ) ?: return null
        val result = handle.invokeWithArguments(
            receiver,
            ObjC.sel("copyPixelBufferForItemTime:itemTimeForDisplay:"),
            time,
            MemorySegment.NULL,
        ) as MemorySegment?
        return result?.takeIf { !ObjC.isNull(it) }
    }

    /**
     * `CACurrentMediaTime()` — the host clock `itemTimeForHostTime:` expects.
     *
     * ⚠ Lives in QuartzCore, not CoreMedia, and it is **not** wall-clock time: it is
     * `mach_absolute_time` in seconds, which does not advance while the machine is asleep.
     * That is the property the video output wants — a lid closed mid-playback must not read as
     * a seek forward.
     */
    fun currentMediaTime(): Double {
        val lookup = ObjC.framework("QuartzCore") ?: return 0.0
        val address = lookup.find("CACurrentMediaTime").orElse(null) ?: return 0.0
        val handle = ObjC.linkerOrNull()?.downcallHandle(address, FunctionDescriptor.of(f64))
            ?: return 0.0
        return handle.invokeExact() as Double
    }

    /** Balance the +1 from [copyPixelBufferForItemTime]. No-op on nil. */
    fun releasePixelBuffer(pixelBuffer: MemorySegment) {
        if (ObjC.isNull(pixelBuffer)) return
        val lookup = ObjC.framework("CoreVideo") ?: return
        val address = lookup.find("CVBufferRelease").orElse(null) ?: return
        val handle = ObjC.linkerOrNull()
            ?.downcallHandle(address, FunctionDescriptor.ofVoid(ptr)) ?: return
        handle.invokeExact(pixelBuffer)
    }
}
