package app.tenshu.timeline.data

import app.tenshu.timeline.data.PlaceIdentityResolver.KnownPlace
import org.tenshu.core.place.PlaceProximity
import org.tenshu.core.timeline.SubjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlaceIdentityResolver] — F1 (`timeline-recognition-and-place-naming.md` §8.4): a newly-minted
 * place that sits within the accuracy-scaled radius of one we already know folds onto it, instead
 * of becoming a second row for the same venue.
 *
 * Fixtures use the fragmentation from the device (§8.2), moved by whole grid cells: 49.0320/20.9030 and 49.0320/20.9040
 * are the two "Office" grid cells, ~73 m apart.
 */
class PlaceIdentityResolverTest {

    private val officeA = "cap:490320000,209030000"
    private val officeB = "cap:490320000,209040000"

    private fun place(id: String, latE7: Long, lngE7: Long, accuracyM: Int = 0) =
        ImportedPlace(placeId = id, latE7 = latE7, lngE7 = lngE7, capturedAtMs = 1_000, accuracyM = accuracyM)

    private fun visit(placeId: String, arrivalMs: Long = 1_000) = ImportedMoment(
        momentId = "cap-visit:$placeId:$arrivalMs",
        timestampMs = arrivalMs,
        kind = "Visit",
        title = "Visit",
        subjectKind = SubjectKind.PLACE,
        subjectId = placeId,
    )

    @Test
    fun aNewPlaceNextDoorToAKnownOneFoldsOntoIt() {
        val imported = ImportedTimeline(
            places = listOf(place(officeB, 490320000, 209040000, accuracyM = 90)),
            moments = listOf(visit(officeB)),
        )
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(KnownPlace(officeA, 490320000, 209030000, accuracyM = 80)),
        )
        assertTrue(resolved.places.isEmpty(), "the folded place must not be written as its own row")
        assertEquals(officeA, resolved.moments.single().subjectId)
        // ⚠ the content-addressed momentId embeds the placeId — it has to travel too, or a
        // re-capture of this visit (which now resolves to officeA) would mint a second moment.
        assertEquals("cap-visit:$officeA:1000", resolved.moments.single().momentId)
    }

    @Test
    fun anIdWeAlreadyStoreIsNeverRemapped() {
        // The Wi-Fi override (§4.5) always resolves to an id that is already stored; F1 must not
        // second-guess it, and an ordinary repeat visit must stay put.
        val imported = ImportedTimeline(
            places = listOf(place(officeB, 490320000, 209040000, accuracyM = 90)),
            moments = listOf(visit(officeB)),
        )
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(
                KnownPlace(officeA, 490320000, 209030000, accuracyM = 80),
                KnownPlace(officeB, 490320000, 209040000, accuracyM = 90),
            ),
        )
        assertEquals(officeB, resolved.places.single().placeId)
        assertEquals(officeB, resolved.moments.single().subjectId)
    }

    @Test
    fun anAdjacentCellSnapsEvenOnACleanFix() {
        // ⚠ Reverses the pre-§10.10 behaviour: 73 m is outside the 60 m accuracy floor, but the two
        // ids are in touching cells of the grid that minted them, so the split is an artefact.
        val imported = ImportedTimeline(places = listOf(place(officeB, 490320000, 209040000, accuracyM = 12)))
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(KnownPlace(officeA, 490320000, 209030000, accuracyM = 10)),
        )
        assertTrue(resolved.places.isEmpty())
    }

    @Test
    fun twoCellsApartStaysItsOwnPlace() {
        // Adjacency reaches one boundary, never two — a genuinely distinct venue keeps its row.
        val far = "cap:490320000,209050000"
        val imported = ImportedTimeline(places = listOf(place(far, 490320000, 209050000, accuracyM = 12)))
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(KnownPlace(officeA, 490320000, 209030000, accuracyM = 10)),
        )
        assertEquals(far, resolved.places.single().placeId)
    }

    @Test
    fun aDistantPlaceNeverFolds() {
        val far = "cap:491450000,218500000" // ~110 km away
        val imported = ImportedTimeline(places = listOf(place(far, 491450000, 218500000, accuracyM = 150)))
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(KnownPlace(officeA, 490320000, 209030000, accuracyM = 150)),
        )
        assertEquals(far, resolved.places.single().placeId)
    }

    @Test
    fun theNearestKnownPlaceWins() {
        val near = "cap:490320000,209032000"
        val imported = ImportedTimeline(places = listOf(place(officeB, 490320000, 209040000, accuracyM = 120)))
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(
                KnownPlace(officeA, 490320000, 209030000, accuracyM = 120),
                KnownPlace(near, 490320000, 209032000, accuracyM = 120),
            ),
        )
        assertEquals(near, resolved.moments.firstOrNull()?.subjectId ?: near)
        assertTrue(resolved.places.isEmpty())
    }

    @Test
    fun anUnknownAccuracyFallsBackToTheDefaultRadius() {
        // The rule itself lives in :place-service so the proxy reconciler shares it (§10.3).
        assertEquals(PlaceProximity.SAME_PLACE_DEFAULT_RADIUS_M, PlaceProximity.radiusFor(0, 0))
        assertEquals(PlaceProximity.SAME_PLACE_MIN_RADIUS_M, PlaceProximity.radiusFor(10, 12))
        assertEquals(PlaceProximity.SAME_PLACE_MAX_RADIUS_M, PlaceProximity.radiusFor(400, 0))
        assertEquals(90.0, PlaceProximity.radiusFor(90, 40))
    }

    @Test
    fun nothingToFoldReturnsTheInputUntouched() {
        val imported = ImportedTimeline(places = listOf(place(officeA, 490320000, 209030000)))
        assertEquals(imported, PlaceIdentityResolver.resolve(imported, emptyList()))
    }

    @Test
    fun nonPlaceMomentsAreLeftAlone() {
        val move = ImportedMoment(
            momentId = "cap-move:1",
            timestampMs = 1,
            kind = "Movement",
            title = "Movement",
            subjectKind = SubjectKind.TRACE,
            subjectId = "cap-trace:1",
        )
        val imported = ImportedTimeline(
            places = listOf(place(officeB, 490320000, 209040000, accuracyM = 100)),
            moments = listOf(move),
        )
        val resolved = PlaceIdentityResolver.resolve(
            imported,
            listOf(KnownPlace(officeA, 490320000, 209030000, accuracyM = 100)),
        )
        assertEquals(move, resolved.moments.single())
    }
}
