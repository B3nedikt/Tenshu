package app.tenshu.windows

import app.tenshu.windows.WindowsPowerDecoding.AC_OFFLINE
import app.tenshu.windows.WindowsPowerDecoding.AC_ONLINE
import app.tenshu.windows.WindowsPowerDecoding.BATTERY_CHARGING
import app.tenshu.windows.WindowsPowerDecoding.BATTERY_CRITICAL
import app.tenshu.windows.WindowsPowerDecoding.BATTERY_LOW
import app.tenshu.windows.WindowsPowerDecoding.BATTERY_SAVER_ON
import app.tenshu.windows.WindowsPowerDecoding.NO_SYSTEM_BATTERY
import app.tenshu.windows.WindowsPowerDecoding.UNKNOWN_BYTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decoding on every host; the real calls on a Windows host only.
 *
 * ⚠ On Windows the availability assertion is **hard**, not a skip: a binding that silently failed to
 * resolve answers null forever, and null reads as "no" — scheduled indexing would never run on
 * Windows again and nothing would say so.
 */
class WindowsPowerProbeTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun acLine_onlineOfflineAndUnknown() {
        assertEquals(true, WindowsPowerDecoding.onExternalPower(AC_ONLINE, BATTERY_CHARGING))
        assertEquals(false, WindowsPowerDecoding.onExternalPower(AC_OFFLINE, 0))
        assertNull(WindowsPowerDecoding.onExternalPower(UNKNOWN_BYTE, 0))
        assertNull(WindowsPowerDecoding.onExternalPower(UNKNOWN_BYTE, UNKNOWN_BYTE), "255 is not a set no-battery bit")
    }

    @Test
    fun aDesktopWithNoBatteryIsOnExternalPower() {
        assertEquals(true, WindowsPowerDecoding.onExternalPower(AC_ONLINE, NO_SYSTEM_BATTERY))
        assertEquals(true, WindowsPowerDecoding.onExternalPower(UNKNOWN_BYTE, NO_SYSTEM_BATTERY))
        assertEquals(false, WindowsPowerDecoding.batteryLow(NO_SYSTEM_BATTERY, 0))
    }

    @Test
    fun theLowBitAloneIsNotLow_criticalOrBatterySaverIs() {
        assertEquals(false, WindowsPowerDecoding.batteryLow(BATTERY_LOW, 0), "< 33 % is ordinary use")
        assertEquals(false, WindowsPowerDecoding.batteryLow(0, 0))
        assertEquals(true, WindowsPowerDecoding.batteryLow(BATTERY_LOW or BATTERY_CRITICAL, 0))
        assertEquals(true, WindowsPowerDecoding.batteryLow(BATTERY_CRITICAL or BATTERY_CHARGING, 0))
        assertEquals(true, WindowsPowerDecoding.batteryLow(BATTERY_LOW, BATTERY_SAVER_ON))
        assertEquals(true, WindowsPowerDecoding.batteryLow(UNKNOWN_BYTE, BATTERY_SAVER_ON), "saver is an answer on its own")
        assertNull(WindowsPowerDecoding.batteryLow(UNKNOWN_BYTE, 0))
    }

    @Test
    fun idleMillis_plainAndAcrossTheTickWrap() {
        assertEquals(0L, WindowsPowerDecoding.idleMillis(lastInputTick = 1_000, nowTick = 1_000))
        assertEquals(300_500L, WindowsPowerDecoding.idleMillis(lastInputTick = 1_000, nowTick = 301_500))
        // dwTime stamped 1 s before the 49.7-day wrap, now 2 s after it: 3 s, not ~49.7 days.
        val beforeWrap = 0xFFFF_FC18L.toInt() // 2^32 - 1000
        assertEquals(3_000L, WindowsPowerDecoding.idleMillis(lastInputTick = beforeWrap, nowTick = 2_000))
        // Across the signed boundary, which is not a wrap at all.
        assertEquals(10L, WindowsPowerDecoding.idleMillis(lastInputTick = Int.MAX_VALUE - 4, nowTick = Int.MIN_VALUE + 5))
    }

    @Test
    fun anInputStampedAfterNowReadsAsInUse() {
        assertEquals(0L, WindowsPowerDecoding.idleMillis(lastInputTick = 5_000, nowTick = 4_990))
    }

    @Test
    fun availableExactlyOnWindows() {
        assertEquals(windows, WindowsPowerProbe.isAvailable())
        if (!windows) {
            assertNull(WindowsPowerProbe.onExternalPower())
            assertNull(WindowsPowerProbe.batteryLow())
            assertNull(WindowsPowerProbe.secondsSinceLastInput())
        }
    }

    @Test
    fun theRealProbeAnswersOnWindows() {
        if (!windows) return
        val raw = assertNotNull(WindowsPowerProbe.powerStatus(), "GetSystemPowerStatus failed")
        val idle = assertNotNull(WindowsPowerProbe.secondsSinceLastInput(), "GetLastInputInfo failed")
        assertTrue(idle >= 0.0)
        assertTrue(idle < 24.0 * 3600 * 24, "a uptime-scale idle means a mis-laid struct, not an absent user")
        // A mis-laid struct reads garbage; the documented values are a small closed set.
        assertTrue(raw.acLineStatus in setOf(AC_OFFLINE, AC_ONLINE, UNKNOWN_BYTE), "ACLineStatus=${raw.acLineStatus}")
        assertTrue(raw.systemStatusFlag in setOf(0, BATTERY_SAVER_ON), "SystemStatusFlag=${raw.systemStatusFlag}")
        println(
            "WindowsPowerProbe on this host: raw=$raw onExternalPower=${WindowsPowerProbe.onExternalPower()} " +
                "batteryLow=${WindowsPowerProbe.batteryLow()} secondsSinceLastInput=$idle",
        )
    }
}
