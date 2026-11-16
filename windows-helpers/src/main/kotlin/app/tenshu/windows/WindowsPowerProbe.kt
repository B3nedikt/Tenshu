package app.tenshu.windows

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Whether this PC is on external power, whether its battery is low, and how long since the last
 * user input — the Windows twin of `:macos-helpers`' `MacPowerProbe`, answering the same three
 * questions for the same caller (`DesktopIndexingConditions`).
 *
 * - `GetSystemPowerStatus` (kernel32) fills a `SYSTEM_POWER_STATUS`; [WindowsPowerDecoding] reads
 *   its AC-line, battery-flag and battery-saver bytes.
 * - `GetLastInputInfo` (user32) answers the `GetTickCount` value of the last keyboard or mouse input
 *   **in this session**, which is the interactive user's session for a per-user Control.
 *
 * ⚠⚠ Every answer is **nullable, and null means unknown** — not Windows, no FFM, a symbol that did
 * not resolve, a call that failed. A caller deciding whether to start a multi-hour job must read
 * unknown as no.
 *
 * ⚠⚠ **This object must load on a JVM without `java.lang.foreign`** (the shared modules' tests run on
 * JDK 17): every FFM type lives in [PowerBindings], loaded only inside a `runCatching`, exactly as
 * [WindowsDpapi] does.
 */
object WindowsPowerProbe {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val bindings: PowerBindings? by lazy {
        if (!isWindows) null else runCatching { PowerBindings.loadOrNull() }.getOrNull()
    }

    /** Whether the probe is callable here: Windows, a 64-bit JVM with FFM, and every symbol resolved. */
    fun isAvailable(): Boolean = bindings != null

    /** `true` on AC power (or with no system battery), `false` on battery, `null` when unknown. */
    fun onExternalPower(): Boolean? = powerStatus()?.let { WindowsPowerDecoding.onExternalPower(it.acLineStatus, it.batteryFlag) }

    /** `true` when the battery is critical or battery saver is on, `null` when unknown. */
    fun batteryLow(): Boolean? = powerStatus()?.let { WindowsPowerDecoding.batteryLow(it.batteryFlag, it.systemStatusFlag) }

    /** Seconds since the last keyboard or mouse input in this session, `null` when unknown. */
    fun secondsSinceLastInput(): Double? = bindings?.let { b ->
        runCatching { b.lastInputAndNow() }.getOrNull()?.let { (lastInput, now) ->
            WindowsPowerDecoding.idleMillis(lastInputTick = lastInput, nowTick = now) / 1000.0
        }
    }

    /** The raw `SYSTEM_POWER_STATUS` bytes, each widened unsigned; `null` when unavailable or failed. */
    internal fun powerStatus(): PowerStatus? = bindings?.let { b -> runCatching { b.powerStatus() }.getOrNull() }

    /** The three `SYSTEM_POWER_STATUS` bytes the probe reads, each `0..255`. */
    internal data class PowerStatus(val acLineStatus: Int, val batteryFlag: Int, val systemStatusFlag: Int)
}

/**
 * The pure half of [WindowsPowerProbe]: raw Win32 values in, answers out. No FFM, tested on every
 * host. Constants are `winbase.h`'s `SYSTEM_POWER_STATUS` documentation.
 */
object WindowsPowerDecoding {

    const val AC_OFFLINE = 0
    const val AC_ONLINE = 1
    const val UNKNOWN_BYTE = 255

    /** `BatteryFlag` bits: 1 high (> 66 %), 2 low (< 33 %), 4 critical (< 5 %), 8 charging, 128 no battery. */
    const val BATTERY_LOW = 2
    const val BATTERY_CRITICAL = 4
    const val BATTERY_CHARGING = 8
    const val NO_SYSTEM_BATTERY = 128

    /** `SystemStatusFlag`: 1 while battery saver (Windows 11: energy saver) is on. */
    const val BATTERY_SAVER_ON = 1

    /**
     * AC online → `true`, offline → `false`. An unknown AC line on a machine that reports **no
     * system battery** is still mains-powered, so `true`; any other unknown is `null`.
     */
    fun onExternalPower(acLineStatus: Int, batteryFlag: Int): Boolean? = when (acLineStatus) {
        AC_ONLINE -> true
        AC_OFFLINE -> false
        else -> if (batteryFlag != UNKNOWN_BYTE && batteryFlag and NO_SYSTEM_BATTERY != 0) true else null
    }

    /**
     * ⚠ "Low" means **the OS is asking for restraint**, the analogue of macOS's low-battery warning —
     * not the `BatteryFlag` *low* bit, which is set below 33 % and would refuse a manual index on a
     * third of every discharge. So: battery saver on (Windows switches it on at 20 % by default, and
     * documents it as "avoid resource-intensive tasks"), or the *critical* bit (< 5 %).
     * No system battery → `false`; an unreadable flag with saver off → `null`.
     */
    fun batteryLow(batteryFlag: Int, systemStatusFlag: Int): Boolean? = when {
        systemStatusFlag == BATTERY_SAVER_ON -> true
        batteryFlag == UNKNOWN_BYTE -> null
        batteryFlag and NO_SYSTEM_BATTERY != 0 -> false
        else -> batteryFlag and BATTERY_CRITICAL != 0
    }

    /**
     * Milliseconds between two 32-bit `GetTickCount` values, `LASTINPUTINFO.dwTime` and now.
     *
     * ⚠ Both wrap every 49.7 days, so the difference is taken modulo 2³² — correct across a wrap.
     * ⚠ A difference in the upper half (≥ 2³¹ ms, ~24.8 days) is read as an input that landed just
     * *after* `now` was sampled, and answers 0: the safe direction, "someone is here".
     */
    fun idleMillis(lastInputTick: Int, nowTick: Int): Long {
        val delta = nowTick - lastInputTick // Int arithmetic is already modulo 2³²
        return if (delta < 0) 0L else delta.toLong()
    }
}

/** Every `java.lang.foreign` reference of [WindowsPowerProbe]. Loaded only once FFM is known to be wanted. */
private class PowerBindings private constructor(
    private val getSystemPowerStatus: MethodHandle,
    private val getLastInputInfo: MethodHandle,
    private val getTickCount: MethodHandle,
) {
    fun powerStatus(): WindowsPowerProbe.PowerStatus? = Arena.ofConfined().use { arena ->
        val status = arena.allocate(SYSTEM_POWER_STATUS)
        if (getSystemPowerStatus.invokeExact(status) as Int == 0) return@use null
        fun byteAt(offset: Long) = status.get(ValueLayout.JAVA_BYTE, offset).toInt() and 0xFF
        WindowsPowerProbe.PowerStatus(
            acLineStatus = byteAt(AC_LINE_STATUS_OFFSET),
            batteryFlag = byteAt(BATTERY_FLAG_OFFSET),
            systemStatusFlag = byteAt(SYSTEM_STATUS_FLAG_OFFSET),
        )
    }

    /** `(LASTINPUTINFO.dwTime, GetTickCount())`, sampled in that order so now ≥ last input. */
    fun lastInputAndNow(): Pair<Int, Int>? = Arena.ofConfined().use { arena ->
        val info = arena.allocate(LASTINPUTINFO)
        info.set(ValueLayout.JAVA_INT, 0L, LASTINPUTINFO.byteSize().toInt()) // cbSize
        if (getLastInputInfo.invokeExact(info) as Int == 0) return@use null
        val lastInput = info.get(ValueLayout.JAVA_INT, DW_TIME_OFFSET)
        val now = getTickCount.invokeExact() as Int
        lastInput to now
    }

    companion object {
        /**
         * `SYSTEM_POWER_STATUS { BYTE ACLineStatus; BYTE BatteryFlag; BYTE BatteryLifePercent;
         * BYTE SystemStatusFlag; DWORD BatteryLifeTime; DWORD BatteryFullLifeTime; }` — 12 bytes,
         * no padding on any ABI (four bytes, then two 4-aligned DWORDs).
         */
        private val SYSTEM_POWER_STATUS = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("ACLineStatus"),
            ValueLayout.JAVA_BYTE.withName("BatteryFlag"),
            ValueLayout.JAVA_BYTE.withName("BatteryLifePercent"),
            ValueLayout.JAVA_BYTE.withName("SystemStatusFlag"),
            ValueLayout.JAVA_INT.withName("BatteryLifeTime"),
            ValueLayout.JAVA_INT.withName("BatteryFullLifeTime"),
        )
        private val AC_LINE_STATUS_OFFSET = offset(SYSTEM_POWER_STATUS, "ACLineStatus")
        private val BATTERY_FLAG_OFFSET = offset(SYSTEM_POWER_STATUS, "BatteryFlag")
        private val SYSTEM_STATUS_FLAG_OFFSET = offset(SYSTEM_POWER_STATUS, "SystemStatusFlag")

        /** `LASTINPUTINFO { UINT cbSize; DWORD dwTime; }` — 8 bytes. */
        private val LASTINPUTINFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("cbSize"),
            ValueLayout.JAVA_INT.withName("dwTime"),
        )
        private val DW_TIME_OFFSET = offset(LASTINPUTINFO, "dwTime")

        private fun offset(layout: MemoryLayout, name: String) =
            layout.byteOffset(MemoryLayout.PathElement.groupElement(name))

        fun loadOrNull(): PowerBindings? {
            if (ValueLayout.ADDRESS.byteSize() != 8L) return null
            val linker = Linker.nativeLinker()
            fun find(library: String, symbol: String) =
                SymbolLookup.libraryLookup(library, Arena.global()).find(symbol).orElse(null)
            // `BOOL GetSystemPowerStatus(LPSYSTEM_POWER_STATUS)`
            val powerStatus = find("kernel32.dll", "GetSystemPowerStatus") ?: return null
            // `BOOL GetLastInputInfo(PLASTINPUTINFO)`
            val lastInput = find("user32.dll", "GetLastInputInfo") ?: return null
            // `DWORD GetTickCount(void)` — the clock `dwTime` is stamped from, wrap included.
            val tickCount = find("kernel32.dll", "GetTickCount") ?: return null
            val boolOfPointer = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
            return PowerBindings(
                linker.downcallHandle(powerStatus, boolOfPointer),
                linker.downcallHandle(lastInput, boolOfPointer),
                linker.downcallHandle(tickCount, FunctionDescriptor.of(ValueLayout.JAVA_INT)),
            )
        }
    }
}
