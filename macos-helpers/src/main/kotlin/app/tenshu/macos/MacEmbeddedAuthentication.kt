package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Touch ID drawn **inside our own window** — `LAAuthenticationView` (LocalAuthenticationEmbeddedUI,
 * macOS 12+) placed over a slot the Compose UI reserves, then
 * `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` on the context the view is
 * bound to. With a view bound, macOS shows no system sheet at all.
 *
 * ⚠⚠ **Biometrics only — there is no password route here, and the caller must provide one.**
 * Device-verified 2026-10-15 (native probe, `docs/tech-debt.md` *"Desktop Control's unlock and
 * re-auth prompt…"*): a wrong finger just keeps the view waiting, and five of them end the call
 * with `authenticationFailed` and lock Touch ID until the login password is entered once. So the
 * caller offers "Use password" (the [MacLocalAuthentication] sheet) and falls through to it on
 * [Result.Failure] — or the user is stuck behind a locked sensor.
 *
 * ⚠ AppKit is touched only on the main thread ([onMain]); [authenticate] blocks its caller (call it
 * off the UI thread) and [cancel]/[move] may come from anywhere.
 */
object MacEmbeddedAuthentication {

    enum class Result { Success, Cancelled, Unavailable, Failure }

    /**
     * Where the view goes, in **points**, relative to the window's content area with a **top-left**
     * origin (AWT/Compose convention; flipped here if AppKit's content view is not).
     * [windowTitle] + [contentWidth] identify the `NSWindow` — AWT exposes no public route from a
     * `java.awt.Window` to it, and title-plus-size is unambiguous for Control's single main window.
     */
    data class Slot(
        val windowTitle: String,
        val contentWidth: Double,
        val contentHeight: Double,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    /** `LAPolicyDeviceOwnerAuthenticationWithBiometrics`. */
    private const val POLICY_BIOMETRICS = 1L

    /** `NSViewMinXMargin | NSViewMaxXMargin | NSViewMinYMargin | NSViewMaxYMargin`. */
    private const val FLEXIBLE_MARGINS = 1L or 4L or 8L or 32L

    // userCancel, systemCancel, appCancel, invalidContext — the last only ever from our own `invalidate`.
    private val CANCEL_CODES = setOf(-2L, -4L, -9L, -10L)

    private val ptr = ValueLayout.ADDRESS
    private val rect: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
    )

    private val viewClass: MemorySegment by lazy {
        if (ObjC.framework("LocalAuthentication") == null ||
            ObjC.framework("LocalAuthenticationEmbeddedUI") == null
        ) MemorySegment.NULL
        else ObjC.cls("LAAuthenticationView")
    }

    private val contextClass: MemorySegment by lazy { ObjC.cls("LAContext") }

    val isSupported: Boolean
        get() = runCatching {
            ObjC.isAvailable && !ObjC.isNull(viewClass) && !ObjC.isNull(contextClass)
        }.getOrDefault(false)

    /** Whether Touch ID can be asked right now — `false` with no sensor, no finger, or a lockout. */
    fun isAvailable(): Boolean = isSupported && runCatching {
        ObjC.autoreleasePool {
            val context = ObjC.send(contextClass, "alloc").let { ObjC.send(it, "init") }
            try {
                canEvaluate(context)
            } finally {
                ObjC.release(context)
            }
        }
    }.getOrDefault(false)

    // ── The in-flight call ───────────────────────────────────────────────────────

    private val lock = Any()
    private val pending = AtomicReference<CompletableFuture<Result>?>(null)
    private val currentContext = AtomicReference<MemorySegment?>(null)

    /** The view's position, applied on AppKit's main thread — see [SlotFollower] for why it exists. */
    private val follower = SlotFollower<Slot, MemorySegment> { view, slot ->
        runCatching {
            onMain {
                val content = contentViewOf(slot) ?: return@onMain
                setFrame(view, frameIn(content, slot))
            }
        }
    }

    private val replyBlock: MemorySegment by lazy {
        ObjC.boolErrorBlock { _, success, error ->
            // ⚠ Never throw out of an upcall — it kills the JVM.
            val result = try {
                when {
                    success.toInt() != 0 -> Result.Success
                    !ObjC.isNull(error) && ObjC.sendLong(error, "code") in CANCEL_CODES -> Result.Cancelled
                    else -> Result.Failure
                }
            } catch (t: Throwable) {
                Result.Failure
            }
            pending.get()?.complete(result)
        }
    }

    /**
     * Put the view at [slot] and wait for a finger. [Result.Unavailable] when the view cannot be
     * placed (no matching window, no Touch ID) — the caller then shows the password sheet instead.
     * [Result.Failure] is "too many wrong fingers / locked out": fall through to the password too.
     *
     * ⚠⚠ [isCancelled] belongs to THIS call, and is checked again once [lock] is held. The first
     * version kept one shared flag that each call reset on entry, so a call cancelled while queued
     * behind the previous one's teardown reset its own cancellation, put a view up with no caller
     * left to read the answer, and held [lock] — every later call queued behind it.
     */
    fun authenticate(
        slot: Slot,
        reason: String,
        timeoutSeconds: Long = 300,
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (!isSupported) return Result.Unavailable
        return synchronized(lock) {
            if (isCancelled()) return@synchronized Result.Cancelled
            runCatching { evaluate(slot, reason, timeoutSeconds, isCancelled) }
                .onFailure { println("EmbeddedAuth: ${it::class.simpleName}: ${it.message}") }
                .getOrDefault(Result.Unavailable)
        }
    }

    /**
     * Take the in-flight view down; the blocked [authenticate] returns [Result.Cancelled]. A call that
     * has not reached its view yet is stopped by its own `isCancelled`, which the caller sets first.
     */
    fun cancel() {
        currentContext.get()?.let { runCatching { ObjC.sendVoid(it, "invalidate") } }
    }

    /** Follow the slot when the window is resized or the layout moves. */
    fun move(slot: Slot) = follower.move(slot)

    private fun evaluate(slot: Slot, reason: String, timeoutSeconds: Long, isCancelled: () -> Boolean): Result {
        val context = ObjC.send(contextClass, "alloc").let { ObjC.send(it, "init") }
        if (ObjC.isNull(context)) return Result.Unavailable
        var view: MemorySegment? = null
        follower.begin(slot)
        try {
            if (!canEvaluate(context)) return Result.Unavailable
            if (isCancelled()) return Result.Cancelled

            view = onMain {
                val content = contentViewOf(slot) ?: return@onMain null
                val created = ObjC.send(ObjC.send(viewClass, "alloc"), "initWithContext:", context)
                if (ObjC.isNull(created)) return@onMain null
                setFrame(created, frameIn(content, slot))
                setAutoresizingMask(created, FLEXIBLE_MARGINS)
                ObjC.sendVoid(content, "addSubview:", created)
                created
            } ?: return Result.Unavailable
            // Re-placed on the latest slot: the layout may have moved while the view was created.
            follower.attach(view)

            val future = CompletableFuture<Result>()
            pending.set(future)
            currentContext.set(context)
            try {
                // After publishing the context: a cancel that missed this check invalidates it instead.
                if (isCancelled()) return Result.Cancelled
                val evaluate = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, ValueLayout.JAVA_LONG, ptr, ptr))
                    ?: return Result.Unavailable
                ObjC.autoreleasePool {
                    evaluate.invokeWithArguments(
                        context,
                        ObjC.sel("evaluatePolicy:localizedReason:reply:"),
                        POLICY_BIOMETRICS,
                        ObjC.nsString(reason),
                        replyBlock,
                    )
                }
                return try {
                    future.get(timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    ObjC.sendVoid(context, "invalidate")
                    runCatching { future.get(2, TimeUnit.SECONDS) }
                    Result.Cancelled
                }
            } finally {
                currentContext.set(null)
                pending.compareAndSet(future, null)
            }
        } finally {
            follower.end()
            view?.let { v ->
                runCatching {
                    onMain {
                        ObjC.sendVoid(v, "removeFromSuperview")
                        ObjC.release(v)
                    }
                }
            }
            ObjC.release(context)
        }
    }

    private fun canEvaluate(context: MemorySegment): Boolean {
        val handle = ObjC.msgSend(FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ptr, ptr, ValueLayout.JAVA_LONG, ptr))
            ?: return false
        val answer = handle.invokeWithArguments(
            context, ObjC.sel("canEvaluatePolicy:error:"), POLICY_BIOMETRICS, MemorySegment.NULL,
        ) as Byte
        return answer.toInt() != 0
    }

    // ── AppKit geometry (main thread only) ───────────────────────────────────────

    /** The content view of the visible window titled [Slot.windowTitle] whose width matches. */
    private fun contentViewOf(slot: Slot): MemorySegment? {
        val app = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication")
        val windows = ObjC.send(app, "windows")
        val count = ObjC.sendLong(windows, "count")
        var fallback: MemorySegment? = null
        for (i in 0 until count) {
            val window = ObjC.sendWithLong(windows, "objectAtIndex:", i)
            if (!ObjC.sendBool(window, "isVisible")) continue
            if (ObjC.kotlinString(ObjC.send(window, "title")) != slot.windowTitle) continue
            val content = ObjC.send(window, "contentView")
            if (ObjC.isNull(content)) continue
            val bounds = boundsOf(content)
            if (bounds != null && kotlin.math.abs(bounds[2] - slot.contentWidth) < 2.0) return content
            fallback = fallback ?: content
        }
        return fallback
    }

    /** `[view bounds]` as `[x, y, w, h]`, or null when the struct-return call is unavailable. */
    private fun boundsOf(view: MemorySegment): DoubleArray? {
        val handle = ObjC.msgSendReturningStruct(FunctionDescriptor.of(rect, ptr, ptr)) ?: return null
        return Arena.ofConfined().use { arena ->
            val out = handle.invokeWithArguments(arena, view, ObjC.sel("bounds")) as MemorySegment
            DoubleArray(4) { out.getAtIndex(ValueLayout.JAVA_DOUBLE, it.toLong()) }
        }
    }

    /** [slot] converted into [content]'s coordinate space (AppKit is bottom-left unless flipped). */
    private fun frameIn(content: MemorySegment, slot: Slot): DoubleArray {
        val height = boundsOf(content)?.get(3) ?: slot.contentHeight
        val y = if (ObjC.sendBool(content, "isFlipped")) slot.y else height - slot.y - slot.height
        return doubleArrayOf(slot.x, y, slot.width, slot.height)
    }

    /**
     * Let AppKit keep the view centred while a live resize outruns Compose's next layout, which then
     * corrects it through [move]. Proportional margins are exact for a centred slot, close otherwise.
     */
    private fun setAutoresizingMask(view: MemorySegment, mask: Long) {
        val handle = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, ValueLayout.JAVA_LONG)) ?: return
        handle.invokeWithArguments(view, ObjC.sel("setAutoresizingMask:"), mask)
    }

    private fun setFrame(view: MemorySegment, frame: DoubleArray) {
        val handle = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, rect)) ?: return
        Arena.ofConfined().use { arena ->
            val r = arena.allocate(rect)
            frame.forEachIndexed { i, v -> r.setAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong(), v) }
            handle.invokeWithArguments(view, ObjC.sel("setFrame:"), r)
        }
    }

    // ── Main-thread hop ──────────────────────────────────────────────────────────

    private val mainLock = Any()
    private val mainTask = AtomicReference<Runnable?>(null)

    /** An `NSObject` subclass whose `run` executes [mainTask] — the target of the main-thread hop. */
    private val runner: MemorySegment by lazy {
        val cls = ObjC.registerClass(
            name = "TenshuMainThreadRunner",
            superclassName = "NSObject",
            methods0 = mapOf(
                "run" to ("v@:" to ObjC.Method0 { _, _ ->
                    // ⚠ Never throw out of an upcall; the task records its own failure.
                    runCatching { mainTask.get()?.run() }
                }),
            ),
        )
        if (ObjC.isNull(cls)) MemorySegment.NULL else ObjC.send(ObjC.send(cls, "alloc"), "init")
    }

    /**
     * Run the block synchronously on AppKit's main thread and wait for it —
     * `performSelectorOnMainThread:` on [runner]. Serialised by [mainLock], so the one task slot is
     * never overwritten mid-flight.
     */
    fun <T> onMain(block: () -> T): T {
        val target = runner
        check(!ObjC.isNull(target)) { "main-thread runner unavailable" }
        val perform = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, ptr, ptr, ValueLayout.JAVA_BYTE))
            ?: error("performSelectorOnMainThread unavailable")
        return synchronized(mainLock) {
            var out: kotlin.Result<T>? = null
            mainTask.set(Runnable { out = runCatching(block) })
            try {
                perform.invokeWithArguments(
                    target,
                    ObjC.sel("performSelectorOnMainThread:withObject:waitUntilDone:"),
                    ObjC.sel("run"),
                    MemorySegment.NULL,
                    1.toByte(),
                )
            } finally {
                mainTask.set(null)
            }
            (out ?: error("main-thread task did not run")).getOrThrow()
        }
    }

}
