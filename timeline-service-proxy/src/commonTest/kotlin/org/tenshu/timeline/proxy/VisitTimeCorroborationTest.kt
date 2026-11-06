package org.tenshu.timeline.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceClustering
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.common.timelineServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [VisitTimeCorroboration] over a real proxy (`timeline-recognition-and-place-naming.md` §10.12) —
 * the evidence that splits the ambiguous middle: one stay that drifted across a cell boundary versus
 * two neighbouring venues.
 */
class VisitTimeCorroborationTest {

    private val cafe = "cap:490320000,209030000"
    private val shop = "cap:490320000,209040000"
    private val t0 = 1_800_000_000_000L
    private val halfHourSec = 1_800L

    @Test
    fun visitsMinutesApartReadAsOneInterruptedStay() = runTest {
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        // Departed at t0+30min, the next dwell confirmed five minutes later one cell over.
        recordVisit(proxy, shop, t0 + halfHourSec * 1_000 + 300_000)

        assertEquals(true, VisitTimeCorroboration(proxy.executionContext).sameVenue(a(cafe), a(shop)))
    }

    @Test
    fun overlappingVisitsReadAsOneStay() = runTest {
        // Two devices, or a re-capture, straddling the boundary: the intervals overlap outright.
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        recordVisit(proxy, shop, t0 + 600_000)

        assertEquals(true, VisitTimeCorroboration(proxy.executionContext).sameVenue(a(cafe), a(shop)))
    }

    @Test
    fun visitsOnTheirOwnDaysReadAsTwoVenues() = runTest {
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        recordVisit(proxy, cafe, t0 + 2 * 86_400_000L)
        recordVisit(proxy, shop, t0 + 86_400_000L)
        recordVisit(proxy, shop, t0 + 3 * 86_400_000L)

        assertEquals(false, VisitTimeCorroboration(proxy.executionContext).sameVenue(a(cafe), a(shop)))
    }

    @Test
    fun onePlaceWithNoVisitsIsNoEvidence() = runTest {
        // ⚠⚠ null, never false. A row whose visits are older than the window — or which has none —
        // must fall back to today's behaviour, not read as proof that the two are distinct.
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)

        assertNull(VisitTimeCorroboration(proxy.executionContext).sameVenue(a(cafe), a(shop)))
    }

    @Test
    fun visitsOlderThanTheWindowAreNoEvidence() = runTest {
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        recordVisit(proxy, shop, t0 + halfHourSec * 1_000 + 300_000)
        // Two newer moments elsewhere push the abutting pair out of a window of 2.
        recordVisit(proxy, "cap:other", t0 + 10_000_000)
        recordVisit(proxy, "cap:other2", t0 + 20_000_000)

        assertNull(
            VisitTimeCorroboration(proxy.executionContext, windowSize = 2).sameVenue(a(cafe), a(shop)),
        )
    }

    @Test
    fun aNonVisitMomentIsNotVisitEvidence() = runTest {
        // Only `Visit` moments carry a place subject; a workout in the same minutes says nothing about
        // where two cells are, and must not be mistaken for a stay.
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = "workout:$t0",
                timestamp = UnixInstantDto(t0 + 60_000),
                kind = Moment.MomentKind.ACTIVITY,
                title = "Run",
                subject = SubjectRef.create(proxy.tContext, SubjectKind.WORKOUT, shop),
                durationSec = halfHourSec,
                sources = listOf("health"),
            )
        )

        assertNull(VisitTimeCorroboration(proxy.executionContext).sameVenue(a(cafe), a(shop)))
    }

    @Test
    fun theWholeChainLeavesTwoNeighbouringVenuesApart() = runTest {
        // End to end through the production wiring: adjacent cells, both geocoded, names disagreeing,
        // visits on their own days ⇒ no merge. The same rows with abutting visits are covered by
        // `PlaceReconcilerTest`; here the point is that the real corroboration, not a fake, decides.
        val proxy = stackProxy(this)
        recordVisit(proxy, cafe, t0)
        recordVisit(proxy, shop, t0 + 86_400_000L)
        val corroboration = VisitTimeCorroboration(proxy.executionContext)

        assertEquals(false, corroboration.sameVenue(a(cafe, "Corner café"), a(shop, "Bookshop")))
        // …and the guard: the same pair, but the user named one of them ⇒ never even asked (that is
        // `PlaceClustering`'s job, asserted there) — here we only pin that the source itself is
        // symmetric, so which side is the anchor cannot change the verdict.
        assertEquals(
            corroboration.sameVenue(a(cafe), a(shop)),
            corroboration.sameVenue(a(shop), a(cafe)),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun a(placeId: String, label: String = "") = PlaceClustering.Candidate(
        placeId = placeId,
        latE7 = 490_320_000,
        lngE7 = if (placeId == shop) 209_040_000 else 209_030_000,
        accuracyM = 90,
        label = label,
        labelSource = LabelSource.GEOCODED,
    )

    /** Timeline **and** place, the production shape — the moments relation is what this reads. */
    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(timelineServiceCommonModule)
            registerConfig(placeServiceCommonModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, _ -> timelineServiceProxyImplementationFactory(id) },
        ).also {
            it.executionContext.initTimelineService(SemanticMergerRegistry())
            it.executionContext.initPlaceService(SemanticMergerRegistry())
            // ⚠ Drop the passes: `MomentReconciliationPass` would fold same-cell captures into one
            // another and `PlaceReconciler` would write merges, both of which would make the fixtures
            // depend on what the *other* passes did rather than on the intervals under test.
            it.executionContext.observerRegistry.clear()
            it.executionContext.installRelationMaintainer()
        }
    }

    private fun recordVisit(proxy: TestProxy, placeId: String, atMs: Long) {
        proxy.executionContext.saveData(
            Place.create(
                context = proxy.tContext,
                placeId = placeId,
                latE7 = 490_320_000,
                lngE7 = if (placeId == shop) 209_040_000 else 209_030_000,
                capturedAt = UnixInstantDto(atMs),
            )
        )
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = "cap-visit:$placeId:$atMs",
                timestamp = UnixInstantDto(atMs),
                kind = Moment.MomentKind.VISIT,
                title = "Visit",
                subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, placeId),
                durationSec = halfHourSec,
                sources = listOf("capture"),
            )
        )
    }
}
