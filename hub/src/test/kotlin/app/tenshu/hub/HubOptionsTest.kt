package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The two cadence flags reach real loops — `--sync-interval-ms` the sync scheduler's
 * `awaitOrTimeout`, `--backup-interval-ms` a bare `delay(…)` — and a non-positive value makes
 * either spin. `parse` therefore treats `0`, a negative, a non-number and a missing value all
 * the same way it treats any malformed argument: `null`, and the caller prints the usage.
 */
class HubOptionsTest {

    @Test
    fun cadenceFlags_acceptAPositiveNumberOfMilliseconds() {
        val options = assertNotNull(
            HubOptions.parse(arrayOf("--sync-interval-ms", "250", "--backup-interval-ms", "900000")),
        )
        assertEquals(250L, options.syncIntervalMs)
        assertEquals(900_000L, options.backupIntervalMs)
    }

    @Test
    fun cadenceFlags_leftOut_stayNullSoTheDefaultsApply() {
        val options = assertNotNull(HubOptions.parse(arrayOf("run")))
        assertNull(options.syncIntervalMs)
        assertNull(options.backupIntervalMs)
    }

    @Test
    fun syncInterval_rejectsZeroNegativeNonNumericAndMissing() {
        for (bad in listOf("0", "-5", "abc", "1.5")) {
            assertNull(HubOptions.parse(arrayOf("--sync-interval-ms", bad)), "--sync-interval-ms $bad")
        }
        assertNull(HubOptions.parse(arrayOf("--sync-interval-ms")), "--sync-interval-ms with no value")
    }

    @Test
    fun backupInterval_rejectsZeroNegativeNonNumericAndMissing() {
        for (bad in listOf("0", "-5", "abc")) {
            assertNull(HubOptions.parse(arrayOf("--backup-interval-ms", bad)), "--backup-interval-ms $bad")
        }
        assertNull(HubOptions.parse(arrayOf("--backup-interval-ms")), "--backup-interval-ms with no value")
    }

    @Test
    fun simulatedDeviceName_isRepeatableByRole_andNeedsTheSimulatedBridge() {
        val options = assertNotNull(
            HubOptions.parse(
                arrayOf(
                    "--simulated-devices",
                    "--simulated-device-name", "light=Living room lamp",
                    "--simulated-device-name", "sensor=Bedroom climate",
                ),
            ),
        )
        assertEquals(mapOf("light" to "Living room lamp", "sensor" to "Bedroom climate"), options.simulatedDeviceNames)
        assertEquals(emptyMap(), assertNotNull(HubOptions.parse(arrayOf("--simulated-devices"))).simulatedDeviceNames)

        assertNull(HubOptions.parse(arrayOf("--simulated-device-name", "light=Lamp")), "without --simulated-devices")
        for (bad in listOf("bulb=Lamp", "light=", "light", "=Lamp")) {
            assertNull(HubOptions.parse(arrayOf("--simulated-devices", "--simulated-device-name", bad)), bad)
        }
        assertNull(HubOptions.parse(arrayOf("--simulated-devices", "--simulated-device-name")), "no value")
    }
}
