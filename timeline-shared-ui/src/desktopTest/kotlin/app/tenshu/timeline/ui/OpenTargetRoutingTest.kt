package app.tenshu.timeline.ui

import app.tenshu.timeline.ui.navigation.Destination
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What Timeline will and will not open when another app names a row (TT-2b).
 *
 * ⚠⚠ Timeline is the first app that handles **two** types, so the interesting assertion is that the
 * TYPE picks the screen. A `placeId` and a `momentId` are both opaque strings — a router that read
 * the key without checking the type would open the wrong screen on a plausible id.
 */
class OpenTargetRoutingTest {

    private fun id(type: String, key: String) =
        DataId.StringDataId.create(context = null, type = type, keyValue = key)

    @Test
    fun aPlaceOpensThePlaceScreen() {
        assertEquals(
            Destination.PlaceDetail("place-7"),
            destinationForTarget(id("tenshu/place/place", "place-7")),
        )
    }

    @Test
    fun aMomentOpensTheMomentScreen() {
        assertEquals(
            Destination.MomentDetail("moment-7"),
            destinationForTarget(id("tenshu/timeline/moment", "moment-7")),
        )
    }

    @Test
    fun theTypeDecidesTheScreenEvenWhenTheKeysLookAlike() {
        // ⚠⚠ Same key, two types, two screens — the case a key-shape heuristic would get wrong.
        assertEquals(Destination.PlaceDetail("x"), destinationForTarget(id("tenshu/place/place", "x")))
        assertEquals(
            Destination.MomentDetail("x"),
            destinationForTarget(id("tenshu/timeline/moment", "x")),
        )
    }

    @Test
    fun somethingElseIsIgnored() {
        assertNull(destinationForTarget(id("tenshu/file", "/a.jpg")))
        assertNull(destinationForTarget(id("tenshu/place/place", "")))
    }
}
