package app.tenshu.macos

import app.tenshu.macos.ffi.ObjC
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Whether this Mac is on external power, whether its battery is low, and how long since the last
 * user input — the three things a background workload needs to stay out of someone's way.
 *
 * All three are plain C functions with scalar returns, so there is no CoreFoundation object to
 * release and no struct to lay out:
 * - `IOPSGetTimeRemainingEstimate()` (IOKit) answers `kIOPSTimeRemainingUnlimited` (−2.0) exactly
 *   when the machine runs on external power — including a Mac with no battery at all.
 * - `IOPSGetBatteryWarningLevel()` (IOKit) answers `kIOPSLowBatteryWarningNone` (1) unless the OS has
 *   raised its own early or final low-battery warning.
 * - `CGEventSourceSecondsSinceLastEventType(kCGEventSourceStateHIDSystemState, kCGAnyInputEventType)`
 *   (CoreGraphics) is the system idle timer: seconds since any keyboard, mouse or trackpad input.
 *
 * ⚠⚠ Every answer is **nullable, and null means unknown** — not macOS, no FFM, a symbol that did not
 * resolve, a call that threw, **or a call that did not return within [BoundedNativeCall]'s bound**.
 * A caller deciding whether to start a multi-hour job must read unknown as no.
 *
 * ⚠⚠ Every call is bounded because each one is Mach IPC (to WindowServer or powerd) that cannot be
 * cancelled, and the indexer asks it under a lock that a session teardown joins. One call that
 * never returned has already wedged a whole process ([BoundedNativeCall]).
 */
object MacPowerProbe {

    private const val TIME_REMAINING_UNLIMITED = -2.0
    private const val LOW_BATTERY_WARNING_NONE = 1
    private const val EVENT_SOURCE_STATE_HID_SYSTEM = 1
    private const val ANY_INPUT_EVENT_TYPE = -1 // kCGAnyInputEventType = ~0 as a uint32

    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    private val timeRemaining: MethodHandle? by lazy {
        handle("IOKit", "IOPSGetTimeRemainingEstimate", FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE))
    }

    private val warningLevel: MethodHandle? by lazy {
        handle("IOKit", "IOPSGetBatteryWarningLevel", FunctionDescriptor.of(ValueLayout.JAVA_INT))
    }

    private val secondsSinceEvent: MethodHandle? by lazy {
        handle(
            "CoreGraphics",
            "CGEventSourceSecondsSinceLastEventType",
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
    }

    // ⚠ Each public call resolves its `lazy` handle (framework load + downcall link, a cold-JVM
    // cost) on the caller's thread BEFORE the bounded call, so the bound times the downcall alone.

    /** `true` on AC power (or with no battery), `false` on battery, `null` when unknown. */
    fun onExternalPower(): Boolean? = timeRemaining?.let { externalPowerCall() }

    private val externalPowerCall = BoundedNativeCall("power-source") {
        timeRemaining?.let { handle -> (handle.invokeExact() as Double) == TIME_REMAINING_UNLIMITED }
    }

    /** `true` while the OS has raised a low-battery warning, `null` when unknown. */
    fun batteryLow(): Boolean? = warningLevel?.let { batteryLowCall() }

    private val batteryLowCall = BoundedNativeCall("battery-warning") {
        warningLevel?.let { handle -> (handle.invokeExact() as Int) != LOW_BATTERY_WARNING_NONE }
    }

    /** Seconds since the last keyboard, mouse or trackpad input, `null` when unknown. */
    fun secondsSinceLastInput(): Double? = secondsSinceEvent?.let { idleCall() }?.takeIf { it >= 0.0 }

    private val idleCall = BoundedNativeCall("idle-timer") {
        secondsSinceEvent?.let { handle ->
            handle.invokeExact(EVENT_SOURCE_STATE_HID_SYSTEM, ANY_INPUT_EVENT_TYPE) as Double
        }
    }

    private fun handle(framework: String, symbol: String, descriptor: FunctionDescriptor): MethodHandle? {
        if (!isMac) return null
        return runCatching {
            val address = ObjC.framework(framework)?.find(symbol)?.orElse(null) ?: return@runCatching null
            ObjC.linkerOrNull()?.downcallHandle(address, descriptor)
        }.getOrNull()
    }
}
