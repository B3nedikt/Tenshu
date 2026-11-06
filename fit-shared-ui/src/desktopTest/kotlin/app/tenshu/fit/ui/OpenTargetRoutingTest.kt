package app.tenshu.fit.ui

import app.tenshu.fit.ui.navigation.Destination
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What Fit will and will not open when another app names a row (TT-2b).
 *
 * ⚠⚠ **Fit is a smoke-gate canary** (HR #8) — the gate installs it but never rebuilds it, so this
 * route is not exercised on the emulator. These assertions and a maintainer's own device are the
 * only things standing behind it.
 */
class OpenTargetRoutingTest {

    private fun id(type: String, key: String) =
        DataId.StringDataId.create(context = null, type = type, keyValue = key)

    @Test
    fun aWorkoutOpensItsDetailScreen() {
        assertEquals(
            Destination.WorkoutDetail("workout-7"),
            destinationForTarget(id("tenshu/health/workout", "workout-7")),
        )
    }

    @Test
    fun somethingElseIsIgnored() {
        // ⚠ Opening `WorkoutDetail` on a `placeId` would be a plausible-looking wrong screen —
        // much harder to notice than a tap that refuses.
        assertNull(destinationForTarget(id("tenshu/place/place", "place-7")))
        assertNull(destinationForTarget(id("tenshu/health/workout", "")))
    }
}
