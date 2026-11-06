package org.tenshu.core.place

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlaceClustering] — the pure half of the P4e reconciliation pass
 * (`timeline-recognition-and-place-naming.md` §10.3). Fixtures use the fragmentation the device
 * showed (§8.2), moved by whole grid cells: `cap:490320000,209030000` and `cap:490320000,209040000` are the two "Office" cells,
 * 73 m apart.
 */
class PlaceClusteringTest {

    private val officeA = "cap:490320000,209030000"
    private val officeB = "cap:490320000,209040000"

    private fun candidate(
        id: String,
        latE7: Long = 490_320_000,
        lngE7: Long = 209_030_000,
        accuracyM: Int = 90,
        label: String = "",
        labelSource: String = LabelSource.GEOCODED,
        tags: Set<String> = emptySet(),
    ) = PlaceClustering.Candidate(id, latE7, lngE7, accuracyM, label, labelSource, tags)

    @Test
    fun twoCellsOfOneVenueFoldOntoTheSmallerId() {
        val a = candidate(officeA, label = "Office", labelSource = LabelSource.USER)
        val b = candidate(officeB, lngE7 = 209_040_000, label = "Office", labelSource = LabelSource.GEOCODED)

        val merges = PlaceClustering.mergesFor(anchor = b, others = listOf(a, b), existingMerges = emptyMap())

        assertEquals(listOf(PlaceClustering.Merge(officeB, officeA)), merges)
    }

    @Test
    fun theRootIsDeterministicWhicheverSideIsTheAnchor() {
        val a = candidate(officeA)
        val b = candidate(officeB, lngE7 = 209_040_000)
        val fromB = PlaceClustering.mergesFor(b, listOf(a, b), emptyMap())
        val fromA = PlaceClustering.mergesFor(a, listOf(a, b), emptyMap())
        // Same verdict from either device: B folds into A, never the other way round.
        assertEquals(fromB, fromA)
        assertEquals(officeA, fromB.single().mergedInto)
    }

    @Test
    fun aReRunOverTheSameInputWritesNothing() {
        // The idempotence the observer depends on: it saves rows, and those saves re-enter it.
        val a = candidate(officeA)
        val b = candidate(officeB, lngE7 = 209_040_000)
        val already = mapOf(officeB to officeA)
        assertTrue(PlaceClustering.mergesFor(b, listOf(a, b), already).isEmpty())
        assertTrue(PlaceClustering.mergesFor(a, listOf(a, b), already).isEmpty())
    }

    @Test
    fun twoDifferentlyNamedUserPlacesNeverAutoMerge() {
        val cafe = candidate("cap:1", label = "Café", labelSource = LabelSource.USER)
        val shop = candidate("cap:2", lngE7 = 209_030_500, label = "Bookshop", labelSource = LabelSource.USER)
        assertTrue(PlaceClustering.mergesFor(shop, listOf(cafe, shop), emptyMap()).isEmpty())
    }

    @Test
    fun aGeocodedGuessDoesNotVetoAMerge() {
        // ⚠ The reported bug itself: the geocoder named two cells of one venue differently. If a
        // machine guess could veto, this pass would preserve exactly the fragmentation it repairs.
        val street = candidate(officeA, label = "Street", labelSource = LabelSource.GEOCODED)
        val venue = candidate(officeB, lngE7 = 209_040_000, label = "Office", labelSource = LabelSource.GEOCODED)
        assertEquals(officeA, PlaceClustering.mergesFor(venue, listOf(street, venue), emptyMap()).single().mergedInto)
    }

    @Test
    fun distantPlacesNeverCluster() {
        val munich = candidate(officeA)
        val far = candidate("cap:491450000,218500000", latE7 = 491_450_000, lngE7 = 218_500_000)
        assertTrue(PlaceClustering.mergesFor(far, listOf(munich, far), emptyMap()).isEmpty())
    }

    @Test
    fun adjacentCellsMergeEvenOnCleanFixes() {
        // ⚠ This deliberately reverses the pre-§10.10 behaviour. Two clean fixes 73 m apart are
        // outside the 60 m accuracy floor — but they are in TOUCHING GRID CELLS, and the grid is what
        // split them in the first place. On the reporting device this exact gap left
        // home doubled after the first repair pass.
        val a = candidate(officeA, accuracyM = 10)
        val b = candidate(officeB, lngE7 = 209_040_000, accuracyM = 12)
        assertEquals(officeA, PlaceClustering.mergesFor(b, listOf(a, b), emptyMap()).single().mergedInto)
    }

    @Test
    fun cleanFixesTwoCellsApartStayDistinct() {
        // The property that survives: adjacency reaches exactly one cell boundary, never two.
        val a = candidate(officeA, accuracyM = 10)
        val far = candidate("cap:490320000,209050000", lngE7 = 209_050_000, accuracyM = 10)
        assertTrue(PlaceClustering.mergesFor(far, listOf(a, far), emptyMap()).isEmpty())
    }

    @Test
    fun aLatitudeStepMergesToo() {
        // 111 m north-south is one cell step but was outside every radius the rule ever used — the
        // reported failure, in the axis a metre radius cannot reach.
        val home = candidate("cap:490150000,209310000", latE7 = 490_150_000, lngE7 = 209_310_000, accuracyM = 0)
        val south = candidate("cap:490140000,209310000", latE7 = 490_140_000, lngE7 = 209_310_000, accuracyM = 0)
        assertEquals(
            "cap:490140000,209310000",
            PlaceClustering.mergesFor(home, listOf(home, south), emptyMap()).single().mergedInto,
        )
    }

    @Test
    fun aChainOfAdjacentCellsCollapsesOntoOneRow() {
        // ⚠ A–B–C where C touches B but not A. Once B has folded into A, anchoring on C must resolve
        // B to its root rather than skipping it, or C strands as a permanent duplicate.
        val a = candidate("cap:490150000,209310000", latE7 = 490_150_000, lngE7 = 209_310_000)
        val b = candidate("cap:490150000,209320000", latE7 = 490_150_000, lngE7 = 209_320_000)
        val c = candidate("cap:490150000,209330000", latE7 = 490_150_000, lngE7 = 209_330_000)
        val settled = mapOf(b.placeId to a.placeId)
        assertEquals(
            listOf(PlaceClustering.Merge(c.placeId, a.placeId)),
            PlaceClustering.mergesFor(c, listOf(a, b, c), settled),
        )
    }

    @Test
    fun anAlreadyMergedRowIsNotReExamined() {
        val a = candidate(officeA)
        val b = candidate(officeB, lngE7 = 209_040_000)
        val c = candidate("cap:490320000,209035000", lngE7 = 209_035_000)
        // b is already folded into a; anchoring on b must do nothing rather than start a chain.
        assertTrue(PlaceClustering.mergesFor(b, listOf(a, b, c), mapOf(officeB to officeA)).isEmpty())
        // …and c, anchoring itself, folds onto a without dragging the settled b back in.
        val merges = PlaceClustering.mergesFor(c, listOf(a, b, c), mapOf(officeB to officeA))
        assertEquals(listOf(PlaceClustering.Merge(c.placeId, officeA)), merges)
    }

    // ── §10.12 — the ambiguous middle, and what may arbitrate it ───────────────

    @Test
    fun theAmbiguousBucketIsExactlyTwoDisagreeingGuesses() {
        val blank = candidate(officeA)
        val guessA = candidate(officeA, label = "Corner café")
        val guessB = candidate(officeB, label = "Bookshop")
        val sameGuess = candidate(officeB, label = "Corner café")
        val userA = candidate(officeA, label = "Home", labelSource = LabelSource.USER)
        val userB = candidate(officeB, label = "Office", labelSource = LabelSource.USER)

        assertEquals(PlaceClustering.Verdict.ALLOW, PlaceClustering.mayMerge(blank, guessB))
        assertEquals(PlaceClustering.Verdict.ALLOW, PlaceClustering.mayMerge(guessA, sameGuess))
        assertEquals(PlaceClustering.Verdict.VETO, PlaceClustering.mayMerge(userA, userB))
        // ⚠⚠ A machine guess never contests a user's name — mixed stays ALLOW. Routing it through
        // corroboration would let home, captured in two cells on different days, stop merging.
        assertEquals(PlaceClustering.Verdict.ALLOW, PlaceClustering.mayMerge(userA, guessB))
        assertEquals(PlaceClustering.Verdict.NEEDS_CORROBORATION, PlaceClustering.mayMerge(guessA, guessB))
    }

    @Test
    fun corroborationIsNeverAskedOutsideTheAmbiguousBucket() {
        // ⚠⚠ §12.2, the one rule not to get wrong, as an assertion: a source that says "two venues"
        // to everything must not change a single verdict the labels already settle. If this test ever
        // fails, time has become a veto and repeat visits to one venue will re-fragment.
        val refuses = RecordingCorroboration(false)
        val pairs = listOf(
            candidate(officeA) to candidate(officeB, lngE7 = 209_040_000),
            candidate(officeA, label = "Office") to candidate(officeB, lngE7 = 209_040_000, label = "Office"),
            candidate(officeA, label = "Home", labelSource = LabelSource.USER) to
                candidate(officeB, lngE7 = 209_040_000, label = "Street"),
        )
        pairs.forEach { (a, b) ->
            assertEquals(
                officeA,
                PlaceClustering.mergesFor(b, listOf(a, b), emptyMap(), refuses).single().mergedInto,
            )
        }
        assertTrue(refuses.asked.isEmpty(), "the labels settled every pair; nothing should have been read")
    }

    @Test
    fun twoDisagreeingGuessesWhoseVisitsNeverAbutAreLeftAlone() {
        // The café and the bookshop next door: adjacent cells, both named by the geocoder, and their
        // visits belong to separate stays. Before §10.12 this folded on adjacency alone.
        val cafe = candidate(officeA, label = "Corner café")
        val shop = candidate(officeB, lngE7 = 209_040_000, label = "Bookshop")
        val separate = RecordingCorroboration(false)

        assertTrue(PlaceClustering.mergesFor(shop, listOf(cafe, shop), emptyMap(), separate).isEmpty())
        assertEquals(1, separate.asked.size, "exactly the ambiguous pair, asked once")
    }

    @Test
    fun twoDisagreeingGuessesWhoseVisitsAbutAreMerged() {
        // The town-hall/ice-cream case: a pause on a walk, recorded as its own dwell one cell over.
        val pause = candidate(officeA, label = "Town hall")
        val shop = candidate(officeB, lngE7 = 209_040_000, label = "Ice cream shop")

        val merges = PlaceClustering.mergesFor(shop, listOf(pause, shop), emptyMap(), RecordingCorroboration(true))

        assertEquals(officeA, merges.single().mergedInto)
    }

    @Test
    fun noEvidenceStillMerges() {
        // §12.7 D1: absence of a moment window is not proof of distinctness. Without this the pass
        // would quietly revert to the fragmented behaviour on every device with no recent visits.
        val cafe = candidate(officeA, label = "Corner café")
        val shop = candidate(officeB, lngE7 = 209_040_000, label = "Bookshop")
        val silent = RecordingCorroboration(null)

        assertEquals(
            officeA,
            PlaceClustering.mergesFor(shop, listOf(cafe, shop), emptyMap(), silent).single().mergedInto,
        )
        assertTrue(silent.asked.isNotEmpty(), "it was asked; it simply had nothing to say")
    }

    @Test
    fun aSharedNetworkTagSettlesTheAmbiguousPairWithoutReadingAnything() {
        // §12.3 D3: the tag is stronger evidence than time and is already in hand since F4 — so it is
        // tried first, and the store read never happens.
        val router = setOf("b:a4:2b:8c:20:00:01")
        val cafe = candidate(officeA, label = "Corner café", tags = router)
        val shop = candidate(officeB, lngE7 = 209_040_000, label = "Bookshop", tags = router)
        val refuses = RecordingCorroboration(false)

        assertEquals(
            officeA,
            PlaceClustering.mergesFor(shop, listOf(cafe, shop), emptyMap(), refuses).single().mergedInto,
        )
        assertTrue(refuses.asked.isEmpty())
    }

    @Test
    fun aDistantPairIsNeverEvenAskedAbout() {
        // Geometry gates the read: corroboration may touch the store, so only a spatially plausible
        // pair may reach it. Otherwise every unrelated place would trigger a moment window.
        val here = candidate(officeA, label = "Corner café")
        val far = candidate("cap:491450000,218500000", latE7 = 491_450_000, lngE7 = 218_500_000, label = "Bookshop")
        val recording = RecordingCorroboration(true)

        assertTrue(PlaceClustering.mergesFor(far, listOf(here, far), emptyMap(), recording).isEmpty())
        assertTrue(recording.asked.isEmpty())
    }

    private class RecordingCorroboration(private val answer: Boolean?) : PlaceCorroboration {
        val asked = mutableListOf<Pair<String, String>>()
        override fun sameVenue(a: PlaceClustering.Candidate, b: PlaceClustering.Candidate): Boolean? {
            asked += a.placeId to b.placeId
            return answer
        }
    }

    @Test
    fun theUserNameIsCarriedToTheRootWhenTheRootHasNone() {
        val root = candidate(officeA)
        val named = candidate(officeB, label = "Work", labelSource = LabelSource.USER)
        assertEquals("Work", PlaceClustering.userLabelToCarry(root, listOf(named)))
        // …and never overrides a name the root already carries.
        val namedRoot = candidate(officeA, label = "Office", labelSource = LabelSource.USER)
        assertEquals(null, PlaceClustering.userLabelToCarry(namedRoot, listOf(named)))
    }
}
