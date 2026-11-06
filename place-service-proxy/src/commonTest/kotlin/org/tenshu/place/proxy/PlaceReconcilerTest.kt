package org.tenshu.place.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceCorroboration
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceService
import org.tenshu.place.test.createPlaceProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlaceReconciler] over a real proxy (`timeline-recognition-and-place-naming.md` §10.3, P4e) —
 * saving a `Place` through `ExecutionContext.saveData` and asserting the `PlaceMerge` rows the
 * observer wrote. Coordinates are the reported fragmentation (§8.2), moved by whole grid cells: the two "Office" cells, 73 m
 * apart.
 */
class PlaceReconcilerTest {

    private val officeA = "cap:490320000,209030000"
    private val officeB = "cap:490320000,209040000"

    @Test
    fun twoCellsOfOneVenueAreMergedWhenTheSecondIsWritten() = runTest {
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        assertTrue(merges(proxy).isEmpty(), "one place alone merges nothing")

        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000))

        assertEquals(mapOf(officeB to officeA), merges(proxy))
    }

    @Test
    fun theUserNameFollowsTheMergeOntoTheSurvivingRow() = runTest {
        // The reported flow: the user renamed the row that does NOT deterministically survive.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        ctx.saveData(
            place(proxy, officeB, lngE7 = 209_040_000, label = "Office", labelSource = LabelSource.USER),
        )

        assertEquals(mapOf(officeB to officeA), merges(proxy))
        val root = places(proxy).getValue(officeA)
        assertEquals("Office", root.first, "the name must not disappear behind the merge")
        assertEquals(LabelSource.USER, root.second)
    }

    @Test
    fun theSecondRunWritesNothingNew() = runTest {
        // Idempotence: the observer's own writes re-enter it, so a settled state must be a fixed
        // point. Re-saving the same place must not add a second (or contradictory) pointer.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000))
        val after = merges(proxy)

        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000))
        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))

        assertEquals(after, merges(proxy))
    }

    @Test
    fun aDistinctVenueIsLeftAlone() = runTest {
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        ctx.saveData(place(proxy, "cap:491450000,218500000", latE7 = 491_450_000, lngE7 = 218_500_000))
        assertTrue(merges(proxy).isEmpty())
    }

    @Test
    fun twoDifferentlyNamedUserPlacesAreNeverAutoMerged() = runTest {
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.saveData(place(proxy, "cap:1", label = "Café", labelSource = LabelSource.USER))
        ctx.saveData(
            place(proxy, "cap:2", lngE7 = 209_030_400, label = "Bookshop", labelSource = LabelSource.USER),
        )
        assertTrue(merges(proxy).isEmpty())
    }

    @Test
    fun aPeersPlaceArrivingBySyncReconcilesToo() = runTest {
        // The convergence case F1 structurally cannot cover: this device never wrote the second row.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))

        // What `SyncProcessor` does when a peer's block lands.
        ctx.database.saveData(place(proxy, officeB, lngE7 = 209_040_000))
        ctx.observerRegistry.onDataSaved(
            place(proxy, officeB, lngE7 = 209_040_000),
            WriteOrigin.SYNC_INGEST,
        )

        assertEquals(mapOf(officeB to officeA), merges(proxy))
    }

    @Test
    fun theStartupSweepRepairsRowsWrittenBeforeThePassExisted() = runTest {
        // ⚠⚠ The path that actually reaches a user's existing duplicates. A repeat visit to a named
        // place writes no Place row (the §4.4 clobber guard), so the observer never fires for the
        // rows that most need merging — only the sweep reaches them.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext

        // The pre-P4e world exactly: the relation maintainer ran (so the rows are readable), the
        // reconciler did not. Dropping the observer reproduces that without a bare `database.saveData`,
        // which would bypass the maintainer and leave the sweep nothing to read.
        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        ctx.observerRegistry.clear()
        ctx.installRelationMaintainer()
        ctx.saveData(
            place(proxy, officeB, lngE7 = 209_040_000, label = "Office", labelSource = LabelSource.USER),
        )
        assertTrue(merges(proxy).isEmpty(), "no reconciler installed ⇒ nothing merged yet")

        PlaceReconciler(ctx, { 42L }).reconcileAll()

        assertEquals(mapOf(officeB to officeA), merges(proxy))
        assertEquals("Office", places(proxy).getValue(officeA).first)
    }

    @Test
    fun theSweepIsAFixedPoint() = runTest {
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000))
        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000))
        val reconciler = PlaceReconciler(ctx, { 42L })

        reconciler.reconcileAll()
        val after = merges(proxy)
        reconciler.reconcileAll()

        assertEquals(after, merges(proxy))
    }

    // ── §10.12 — the ambiguous middle reaches the corroboration source ─────────

    @Test
    fun twoDisagreeingGuessesAreNotMergedWhenTheirVisitsSayTwoVenues() = runTest {
        // The café and the bookshop next door, through the real pass: adjacent cells, both named by
        // the geocoder, names disagreeing. Before §10.12 this folded on adjacency alone.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.observerRegistry.clear()
        ctx.installRelationMaintainer()
        ctx.addDataObserver(PlaceReconciler(ctx, { 42L }, PlaceCorroboration { _, _ -> false }))

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000, label = "Corner café"))
        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000, label = "Bookshop"))

        assertTrue(merges(proxy).isEmpty())
    }

    @Test
    fun theSamePairMergesWhenTheirVisitsAbut() = runTest {
        // …and the converse, so the test above cannot be passing because the pass stopped working.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.observerRegistry.clear()
        ctx.installRelationMaintainer()
        ctx.addDataObserver(PlaceReconciler(ctx, { 42L }, PlaceCorroboration { _, _ -> true }))

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000, label = "Town hall"))
        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000, label = "Ice cream shop"))

        assertEquals(mapOf(officeB to officeA), merges(proxy))
    }

    @Test
    fun aRefusingSourceCannotUnpickWhatTheLabelsAlreadySettle() = runTest {
        // ⚠⚠ §12.2 at the wiring level: the default wiring's own fixtures (equal/blank labels) must be
        // untouched by a source that refuses everything. If this fails, corroboration has widened past
        // the ambiguous bucket and repeat visits to one venue will re-fragment.
        val proxy = createPlaceProxy(this)
        val ctx = proxy.executionContext
        ctx.observerRegistry.clear()
        ctx.installRelationMaintainer()
        ctx.addDataObserver(PlaceReconciler(ctx, { 42L }, PlaceCorroboration { _, _ -> false }))

        ctx.saveData(place(proxy, officeA, lngE7 = 209_030_000, label = "Office"))
        ctx.saveData(place(proxy, officeB, lngE7 = 209_040_000, label = "Office"))

        assertEquals(mapOf(officeB to officeA), merges(proxy))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun place(
        proxy: org.tenshu.core.common.TestProxy,
        placeId: String,
        latE7: Long = 490_320_000,
        lngE7: Long = 209_030_000,
        label: String = "",
        labelSource: String = LabelSource.GEOCODED,
        accuracyM: Int = 90,
    ) = Place.create(
        context = proxy.executionContext.context,
        placeId = placeId,
        latE7 = latE7,
        lngE7 = lngE7,
        capturedAt = UnixInstantDto(1_000L),
        label = label,
        labelSource = labelSource,
        accuracyM = accuracyM,
    )

    private fun merges(proxy: org.tenshu.core.common.TestProxy): Map<String, String> =
        proxy.executionContext.database
            .getArrayData(proxy.executionContext.relationReference(PlaceService.Relations.MERGES))
            ?.toList()
            .orEmpty()
            .filterIsInstance<PlaceMerge>()
            .associate { it.placeId.get() to it.mergedInto.get() }

    /** `placeId → (label, labelSource)`. */
    private fun places(proxy: org.tenshu.core.common.TestProxy): Map<String, Pair<String, String>> =
        proxy.executionContext.database
            .getArrayData(proxy.executionContext.relationReference(PlaceService.Relations.PLACES))
            ?.toList()
            .orEmpty()
            .filterIsInstance<Place>()
            .associate {
                it.getField<TString>(Place.Fields.PLACE_ID).get() to
                    (it.label.get() to it.labelSource.get())
            }
}
