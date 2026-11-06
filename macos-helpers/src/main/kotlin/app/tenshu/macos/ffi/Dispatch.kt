package app.tenshu.macos.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * The sliver of libdispatch the in-process camera path needs: a serial queue to hand
 * AVFoundation for frame delivery (`desktop-qr-scanning.md` §13 P1).
 *
 * This is the piece that keeps the design off the main thread. `AVCaptureVideoDataOutput`
 * delivers sample buffers on the queue you give it, so with a queue of our own the frame
 * path never depends on the AWT/`CFRunLoop` being pumped — which is what reduced the §9
 * "run-loop risk" to a footnote for this design (it would only bite
 * `AVCaptureVideoPreviewLayer` or a main-queue delegate, neither of which we use).
 *
 * Dispatch objects are ObjC objects on modern macOS, so [ObjC.release] balances [serialQueue].
 */
internal object Dispatch {

    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()

    private val libdispatch: SymbolLookup? by lazy {
        if (linker == null || !ObjC.isAvailable) null
        // libdispatch lives inside libSystem, which the default lookup already covers.
        else runCatching { linker!!.defaultLookup() }.getOrNull()
    }

    /**
     * A **serial** dispatch queue named [label], or a null segment when unavailable.
     *
     * Serial is load-bearing rather than incidental: frames must not be decoded
     * concurrently out of order, and a serial queue gives that for free without any lock
     * on the decode path.
     */
    fun serialQueue(label: String): MemorySegment {
        val lookup = libdispatch ?: return MemorySegment.NULL
        val address = lookup.find("dispatch_queue_create").orElse(null) ?: return MemorySegment.NULL
        val handle = linker!!.downcallHandle(
            address,
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        return Arena.ofConfined().use { arena ->
            // A NULL attribute means DISPATCH_QUEUE_SERIAL — the default.
            handle.invokeExact(arena.allocateFrom(label), MemorySegment.NULL) as MemorySegment
        }
    }
}
