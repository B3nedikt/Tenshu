package app.tenshu.timeline.data

import app.tenshu.timeline.data.projection.TimelineProjection
import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [PlaceMergeFolding] — the read half of place merging
 * (`timeline-recognition-and-place-naming.md` §10.11, P4f). Ids are the reporting device's two
 * "Office" cells (§8.2).
 */
class PlaceMergeFoldingTest {

    private val officeA = "cap:490320000,209030000"
    private val officeB = "cap:490320000,209040000"

    private fun place(id: String, label: String = "") =
        PlaceInput(id = id, label = label, latE7 = 490_320_000, lngE7 = 209_030_000)

    private fun visit(id: String, placeId: String) = MomentInput(
        id = id,
        timestampMs = 1_000,
        kind = "Visit",
        title = "Visit",
        subjectKind = TimelineProjection.Subject.PLACE,
        subjectId = placeId,
        durationSec = 0,
    )

    @Test
    fun aFoldedPlaceDisappearsFromTheList() {
        val folded = PlaceMergeFolding.rootsOnly(
            listOf(place(officeA, "Office"), place(officeB, "Office")),
            mapOf(officeB to officeA),
        )
        assertEquals(listOf(officeA), folded.map { it.id })
    }

    @Test
    fun theFoldedPlacesVisitsFollowItToTheSurvivor() {
        val moments = PlaceMergeFolding.repointToRoots(
            listOf(visit("m1", officeA), visit("m2", officeB)),
            mapOf(officeB to officeA),
        )
        assertEquals(listOf(officeA, officeA), moments.map { it.subjectId })
    }

    @Test
    fun aChainResolvesAllTheWayToTheRoot() {
        val c = "cap:490320000,209050000"
        val merges = mapOf(c to officeB, officeB to officeA)
        assertEquals(listOf(officeA), PlaceMergeFolding.rootsOnly(
            listOf(place(officeA), place(officeB), place(c)), merges,
        ).map { it.id })
        assertEquals(officeA, PlaceMergeFolding.repointToRoots(listOf(visit("m", c)), merges).single().subjectId)
    }

    @Test
    fun nonPlaceMomentsAreUntouched() {
        val move = MomentInput(
            id = "mv",
            timestampMs = 1,
            kind = "Movement",
            title = "Movement",
            subjectKind = TimelineProjection.Subject.TRACE,
            subjectId = "cap-trace:1",
            durationSec = 0,
        )
        assertEquals(move, PlaceMergeFolding.repointToRoots(listOf(move), mapOf(officeB to officeA)).single())
    }

    @Test
    fun noMergesIsTheIdentityAndAllocatesNothing() {
        // The overwhelmingly common case — it must not cost a copy of every row on every emission.
        val places = listOf(place(officeA))
        val moments = listOf(visit("m1", officeA))
        assertSame(places, PlaceMergeFolding.rootsOnly(places, emptyMap()))
        assertSame(moments, PlaceMergeFolding.repointToRoots(moments, emptyMap()))
    }

    @Test
    fun aDanglingPointerLeavesTheRowVisible() {
        // The target has not synced yet. Better to render the row we have than to drop it and show
        // the user nothing at all.
        val folded = PlaceMergeFolding.rootsOnly(listOf(place(officeB)), mapOf(officeB to "cap:not-here-yet"))
        assertEquals(emptyList(), folded.map { it.id })
        // …and its visits point at the (absent) root rather than a place that is hidden.
        assertEquals(
            "cap:not-here-yet",
            PlaceMergeFolding.repointToRoots(listOf(visit("m", officeB)), mapOf(officeB to "cap:not-here-yet"))
                .single().subjectId,
        )
    }
}
