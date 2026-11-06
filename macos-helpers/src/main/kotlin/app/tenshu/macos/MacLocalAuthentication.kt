package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Touch ID / login-password check — `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` —
 * called **in this process** through FFM, rather than from the spawned `applock-helper`.
 *
 * Why in-process (`docs/tech-debt.md` *"Desktop Control's unlock and re-auth prompt is a separate
 * system sheet"*): a helper's sheet belongs to the helper, so macOS hands *it* the foreground and
 * does not give it back when it exits — the app drops behind whatever is frontmost and looks
 * crashed. Here the sheet belongs to the calling app, which stays active and names itself by its
 * own bundle (`CFBundleDisplayName`).
 *
 * Keyless: it touches no keychain item, so it needs no entitlement and works under ad-hoc signing,
 * exactly like the helper verb it replaces. A UI barrier, never a key gate.
 *
 * ⚠ Blocking — [authenticate] waits on the user. Call it off the UI thread.
 */
object MacLocalAuthentication {

    enum class Result { Success, Cancelled, Unavailable, Failure }

    /** `LAPolicyDeviceOwnerAuthentication` — biometrics, Watch, or the login password. */
    private const val POLICY_DEVICE_OWNER = 2L

    /** `LAError` codes the helper also reports as "cancelled" (`main.swift` `authenticate`). */
    private val CANCEL_CODES = setOf(
        -2L, // userCancel
        -3L, // userFallback
        -4L, // systemCancel
        -9L, // appCancel — what our own `invalidate` on timeout produces
    )

    private val ptr = ValueLayout.ADDRESS

    private val laContextClass: MemorySegment by lazy {
        if (ObjC.framework("LocalAuthentication") == null) MemorySegment.NULL
        else ObjC.cls("LAContext")
    }

    /** Whether the bridge can reach `LAContext` at all — macOS, FFM, the framework loaded. */
    val isSupported: Boolean
        get() = runCatching { ObjC.isAvailable && !ObjC.isNull(laContextClass) }.getOrDefault(false)

    /**
     * The in-flight call's result. There is ONE reply block for the process (a global block has no
     * per-call state — see [ObjC.globalBlock]), so calls are serialised by [lock] and the block
     * completes whichever future is current.
     */
    private val pending = AtomicReference<CompletableFuture<Result>?>(null)
    private val lock = Any()

    private val replyBlock: MemorySegment by lazy {
        ObjC.boolErrorBlock { _, success, error ->
            // ⚠ Never throw out of here: an exception escaping an upcall kills the JVM.
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

    /** `canEvaluatePolicy` — answers without prompting. `false` whenever unknown. */
    fun isAvailable(): Boolean = isSupported && runCatching {
        ObjC.autoreleasePool {
            val context = ObjC.send(laContextClass, "alloc").let { ObjC.send(it, "init") }
            try {
                canEvaluate(context)
            } finally {
                ObjC.release(context)
            }
        }
    }.getOrDefault(false)

    /**
     * Present the auth sheet with [reason] — macOS renders it as *"<app> is trying to <reason>."*,
     * so pass a lowercase verb phrase. Blocks until the user answers or [timeoutSeconds] passes; a
     * timeout cancels the sheet and reports [Result.Failure].
     */
    fun authenticate(reason: String, timeoutSeconds: Long = 120): Result {
        if (!isSupported) return Result.Unavailable
        return synchronized(lock) {
            runCatching { evaluate(reason, timeoutSeconds) }.getOrDefault(Result.Failure)
        }
    }

    private fun evaluate(reason: String, timeoutSeconds: Long): Result = ObjC.autoreleasePool {
        val context = ObjC.send(laContextClass, "alloc").let { ObjC.send(it, "init") }
        if (ObjC.isNull(context)) return@autoreleasePool Result.Unavailable
        try {
            if (!canEvaluate(context)) return@autoreleasePool Result.Unavailable
            val block = replyBlock
            if (ObjC.isNull(block)) return@autoreleasePool Result.Unavailable

            val future = CompletableFuture<Result>()
            pending.set(future)
            try {
                val evaluate = ObjC.msgSend(FunctionDescriptor.ofVoid(ptr, ptr, ValueLayout.JAVA_LONG, ptr, ptr))
                    ?: return@autoreleasePool Result.Unavailable
                evaluate.invokeWithArguments(
                    context,
                    ObjC.sel("evaluatePolicy:localizedReason:reply:"),
                    POLICY_DEVICE_OWNER,
                    ObjC.nsString(reason),
                    block,
                )
                try {
                    future.get(timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    // Take the sheet down, then let its appCancel reply land on THIS future rather
                    // than on the next call's.
                    ObjC.sendVoid(context, "invalidate")
                    runCatching { future.get(2, TimeUnit.SECONDS) }
                    Result.Failure
                }
            } finally {
                pending.compareAndSet(future, null)
            }
        } finally {
            ObjC.release(context)
        }
    }

    private fun canEvaluate(context: MemorySegment): Boolean {
        val handle = ObjC.msgSend(FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ptr, ptr, ValueLayout.JAVA_LONG, ptr))
            ?: return false
        val answer = handle.invokeWithArguments(
            context,
            ObjC.sel("canEvaluatePolicy:error:"),
            POLICY_DEVICE_OWNER,
            MemorySegment.NULL,
        ) as Byte
        return answer.toInt() != 0
    }
}
