package org.tenshu.timeline.proxy

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.TString
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.place.Place
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.Data
import org.tenshu.core.id
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.relation.installRelationMaintainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MomentReconciliationPass] over a real proxy (P4e-2,
 * `timeline-recognition-and-place-naming.md` §10.3) — the write pass that replaces the per-read,
 * per-device recompute I6c left behind.
 */
class MomentReconciliationPassTest {

    private val place = "cap:490320000,209030000"
    private val t0 = 1_800_000_000_000L

    @Test
    fun twoDevicesCapturesOfOneLunchElectOneCanonical() = runTest {
        val proxy = stackProxy(this, withPass = false)
        val ctx = proxy.executionContext
        recordPlace(proxy, place)

        // Same place cell, minutes apart — one real-world visit, two content-addressed ids.
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$place:${t0 + 120_000}", t0 + 120_000)

        MomentReconciliationPass(ctx).reconcileRecent()

        val superseded = supersededBy(proxy)
        assertEquals(1, superseded.count { it.value.isNotEmpty() }, "exactly one of the pair must fold")
        assertEquals(1, superseded.count { it.value.isEmpty() }, "and exactly one must survive")
        // The verdict points at a real canonical, not at itself or at nothing.
        val (folded, canonical) = superseded.entries.first { it.value.isNotEmpty() }.toPair()
        assertTrue(canonical in superseded.keys && canonical != folded)
    }

    @Test
    fun aBurstOfSavedMomentsCoalescesIntoOneRun() = runTest {
        // ⚠⚠ THE REGRESSION THIS GUARDS. Each run re-reads the whole window plus every place —
        // ~14.5 ms measured on device — and the pass considers the *entire* window every time, so
        // running it per saved moment makes N-1 of N runs pure waste. Ingesting one 500-block page
        // of moments cost ~5.8 s of window scans, ~20% of a sync tick.
        val proxy = stackProxy(this, withPass = true)
        recordPlace(proxy, place)

        var runs = 0
        proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                // The pass's own DERIVED write is the observable side effect of a run.
                val typeId = (data as? org.tenshu.core.entity.Entity<*>)?.type?.get() ?: data.id
                if (typeId == Moment.ID && origin == WriteOrigin.DERIVED) runs++
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })

        // A burst: many moments saved well inside one coalescing window.
        repeat(12) { i -> recordVisit(proxy, "cap-visit:$place:${t0 + i * 60_000L}", t0 + i * 60_000L) }
        assertEquals(0, runs, "nothing runs synchronously on the save path any more")

        advanceTimeBy(MomentReconciliationPass.COALESCE_MS + 1)
        runCurrent()

        assertTrue(runs > 0, "the burst must still be reconciled, one cadence later")
        assertTrue(
            runs < 12,
            "and a whole burst must not cost one full window scan per moment (runs=$runs)"
        )
    }

    @Test
    fun theDerivedVerdictIsNotBroadcast() = runTest {
        // ⚠⚠ THE REGRESSION. This pass wrote its verdict with the default `WriteOrigin.USER`, so
        // every recomputation was emitted as a sync block. `supersededBy` is a *projection* —
        // each device derives it from its own bounded window — so broadcasting it turns a local
        // derivation into a distributed election with no convergence rule: two devices flip the
        // field between their respective answers forever, each flip a fresh block with a newer
        // HLC that the other applies and re-derives.
        //
        // Measured on the live realm 2026-08-05 before the fix: `tenshu/timeline/moment` was
        // 1,597 of 1,600 ingested blocks (99.8%), of which only 4 carried an entity the receiver
        // had not already seen. The realm emitted 12-32 blocks/min around the clock with nobody
        // touching it, and a phone that had been off overnight woke ~29,000 blocks behind.
        val proxy = stackProxy(this, withPass = false)
        val ctx = proxy.executionContext
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$place:${t0 + 120_000}", t0 + 120_000)

        val origins = mutableListOf<WriteOrigin>()
        ctx.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                val typeId = (data as? org.tenshu.core.entity.Entity<*>)?.type?.get() ?: data.id
                if (typeId == Moment.ID) origins += origin
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })

        MomentReconciliationPass(ctx).reconcileRecent()

        assertTrue(origins.isNotEmpty(), "sanity: the pass must actually have written a verdict")
        assertTrue(
            origins.none { it == WriteOrigin.USER },
            "the derived verdict must never be written as a USER save — that is what emits a " +
                "sync block, and what made two devices broadcast their disagreement forever " +
                "(observed origins: $origins)"
        )
        assertTrue(
            origins.all { it == WriteOrigin.DERIVED },
            "and it must say what it is, so `SyncObserver` can skip it for the right reason " +
                "(observed: $origins)"
        )
    }

    @Test
    fun visitsAtDifferentPlacesAreLeftAlone() = runTest {
        val proxy = stackProxy(this, withPass = false)
        val other = "cap:491450000,218500000"
        recordPlace(proxy, place)
        recordPlace(proxy, other, latE7 = 491_450_000, lngE7 = 218_500_000)

        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$other:${t0 + 60_000}", t0 + 60_000, placeId = other)

        MomentReconciliationPass(proxy.executionContext).reconcileRecent()

        assertTrue(supersededBy(proxy).values.all { it.isEmpty() })
    }

    @Test
    fun visitsFarApartInTimeAreLeftAlone() = runTest {
        val proxy = stackProxy(this, withPass = false)
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        // Next day — a different time bucket, so a repeat visit to one venue is NOT a duplicate.
        recordVisit(proxy, "cap-visit:$place:${t0 + 86_400_000}", t0 + 86_400_000)

        MomentReconciliationPass(proxy.executionContext).reconcileRecent()

        assertTrue(supersededBy(proxy).values.all { it.isEmpty() })
    }

    @Test
    fun theSecondRunWritesNothingNew() = runTest {
        // Idempotence: the pass writes Moment rows, and those writes re-enter the observer. A settled
        // window must be a fixed point or it recurses forever.
        val proxy = stackProxy(this, withPass = false)
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$place:${t0 + 120_000}", t0 + 120_000)

        val pass = MomentReconciliationPass(proxy.executionContext)
        pass.reconcileRecent()
        val after = supersededBy(proxy)
        pass.reconcileRecent()

        assertEquals(after, supersededBy(proxy))
    }

    @Test
    fun aLoneMomentIsNeverTouched() = runTest {
        val proxy = stackProxy(this, withPass = false)
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)

        MomentReconciliationPass(proxy.executionContext).reconcileRecent()

        assertTrue(supersededBy(proxy).values.all { it.isEmpty() })
    }

    @Test
    fun theWindowBoundsWhatIsConsidered() = runTest {
        // ⚠ The bound is the point: a duplicate outside the recent window is NOT reconciled at write
        // time. Documented cost of not scanning history (§10.3) — pinned so it stays a decision.
        val proxy = stackProxy(this, withPass = false)
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$place:${t0 + 120_000}", t0 + 120_000)
        // Two newer, unrelated moments push the pair out of a window of 2.
        recordVisit(proxy, "cap-visit:other:${t0 + 10_000_000}", t0 + 10_000_000, placeId = "other")
        recordVisit(proxy, "cap-visit:other2:${t0 + 20_000_000}", t0 + 20_000_000, placeId = "other2")

        MomentReconciliationPass(proxy.executionContext, windowSize = 2).reconcileRecent()

        assertTrue(supersededBy(proxy).values.all { it.isEmpty() }, "the older pair is outside the window")
    }

    @Test
    fun theProductionWiringRunsThePassOnEverySavedMoment() = runTest {
        // The others drive `reconcileRecent()` directly so they test the pass rather than the
        // wiring; this one is the converse — no explicit call, only `initTimelineService`.
        val proxy = stackProxy(this, withPass = true)
        recordPlace(proxy, place)
        recordVisit(proxy, "cap-visit:$place:$t0", t0)
        recordVisit(proxy, "cap-visit:$place:${t0 + 120_000}", t0 + 120_000)

        // ⚠ The pass is COALESCED now, not synchronous on save — it re-reads the whole window per
        // run, so running it once per saved moment was ~500 wasted scans per ingested page. The
        // verdict still lands, one `COALESCE_MS` cadence later; virtual time makes that instant.
        advanceTimeBy(MomentReconciliationPass.COALESCE_MS + 1)
        runCurrent()

        assertEquals(1, supersededBy(proxy).count { it.value.isNotEmpty() })
    }

    @Test
    fun anAlreadySupersededMomentIsNeverResurrected() = runTest {
        // ⚠⚠ Caught by `nearDuplicateMomentsCollapseAndSupersededAreHidden` before this guard existed.
        // The window can prove two moments are one; it CANNOT prove a moment is a duplicate of
        // nothing — the counterpart may be older than the window, or the verdict may have come from
        // another device. Clearing on that basis resurrects it, which is worse than not running.
        val proxy = stackProxy(this, withPass = false)
        val supersededId = "cap-visit:$place:$t0"
        recordPlace(proxy, place)
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = supersededId,
                timestamp = UnixInstantDto(t0),
                kind = Moment.MomentKind.VISIT,
                title = "Visit",
                subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, place),
                durationSec = 1_800,
                sources = listOf("capture"),
                supersededBy = "some-canonical-we-cannot-see",
            )
        )
        recordVisit(proxy, "cap-visit:$place:${t0 + 86_400_000}", t0 + 86_400_000)

        MomentReconciliationPass(proxy.executionContext).reconcileRecent()

        assertEquals(
            "some-canonical-we-cannot-see",
            supersededBy(proxy).getValue(supersededId),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * A proxy hosting **both** timeline and place — the production shape (Control registers every
     * service). ⚠ `createTimelineProxy` alone would leave the places relation undeclared, and the
     * pass then excludes every `Visit` by design (see `readPlaceCoords`) — so the tests would pass
     * while proving nothing.
     */
    private fun stackProxy(scope: CoroutineScope, withPass: Boolean): TestProxy {
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
            if (!withPass) {
                // ⚠ Drop the observer so a test's explicit `reconcileRecent()` is the ONLY actor.
                // With it installed every `saveData` already reconciles at the default window, which
                // would make an explicit call — and a `windowSize` override — untestable.
                it.executionContext.observerRegistry.clear()
                it.executionContext.installRelationMaintainer()
            }
        }
    }

    private fun recordPlace(
        proxy: org.tenshu.core.common.TestProxy,
        placeId: String,
        latE7: Long = 490_320_000,
        lngE7: Long = 209_030_000,
    ) = proxy.executionContext.saveData(
        Place.create(
            context = proxy.tContext,
            placeId = placeId,
            latE7 = latE7,
            lngE7 = lngE7,
            capturedAt = UnixInstantDto(t0),
        )
    )

    private fun recordVisit(
        proxy: org.tenshu.core.common.TestProxy,
        momentId: String,
        atMs: Long,
        placeId: String = place,
    ) = proxy.executionContext.saveData(
        Moment.create(
            context = proxy.tContext,
            momentId = momentId,
            timestamp = UnixInstantDto(atMs),
            kind = Moment.MomentKind.VISIT,
            title = "Visit",
            subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, placeId),
            durationSec = 1_800,
            sources = listOf("capture"),
        )
    )

    /** `momentId → supersededBy`. */
    private fun supersededBy(proxy: org.tenshu.core.common.TestProxy): Map<String, String> =
        proxy.executionContext.database
            .getArrayData(proxy.executionContext.relationReference(TimelineService.Relations.MOMENTS))
            ?.toList()
            .orEmpty()
            .filterIsInstance<Moment>()
            .associate {
                it.getField<TString>(Moment.Fields.MOMENT_ID).get() to
                    it.getField<TString>(Moment.Fields.SUPERSEDED_BY).get()
            }
}
