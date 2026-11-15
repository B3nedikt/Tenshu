package org.tenshu.timeline.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.PlaceService
import org.tenshu.core.place.RoleSource
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.timeline.common.timelineServiceCommonModule
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PlaceRoleInferencePass] over a real proxy (`docs/proposals/place-roles.md` PR-2). The detector's
 * own arithmetic is covered by `PlaceRoleDetectorTest`; what this pins is everything *around* it —
 * the store reads, the user-wins rule, and the write gating that keeps a settled window silent.
 */
class PlaceRoleInferencePassTest {

    // ⚠ Anchored to the real clock, deliberately: the pass now applies a **recency cut**
    // (`maxAgeDays`), and `theProductionWiringRunsThePass` exercises the production default — which
    // reads `Clock.System.now()`. A fixed epoch-era fixture would sit ~50 years in the past and be
    // filtered out entirely, which is the age cut working correctly against an unrealistic fixture.
    // Visits land 5–10 days back: recent enough to count, old enough not to depend on time-of-run.
    private val now = Clock.System.now().toEpochMilliseconds()
    private val mondayMs = (now / 86_400_000L) * 86_400_000L - 10L * 86_400_000L
    private val hour = 3_600_000L

    @Test
    fun nightsAtOnePlaceAreInferredAsHome() = runTest {
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        sleepAt(proxy, "flat", nights = 6)

        pass(proxy).inferNow()

        assertEquals(PlaceRoles.HOME to RoleSource.INFERRED, roles(proxy)["flat"])
    }

    @Test
    fun aUserAuthoredRoleIsNeverOverwritten() = runTest {
        // ⚠⚠ The guarantee the whole feature rests on. Inference may improve on its own guess; it
        // may not talk over the person it is guessing about.
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        recordPlace(proxy, "cabin")
        sleepAt(proxy, "flat", nights = 6)
        // The user has said the cabin is home, and the evidence disagrees.
        recordRole(proxy, "cabin", PlaceRoles.HOME, RoleSource.USER)

        pass(proxy).inferNow()

        assertEquals(PlaceRoles.HOME to RoleSource.USER, roles(proxy)["cabin"])
    }

    @Test
    fun aDismissalIsNeverReSuggested() = runTest {
        // A dismissal is `role = none, roleSource = user` — an answer, not an absence. Re-suggesting
        // it would make the prompt un-dismissable, which is the whole reason dismissal persists.
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        sleepAt(proxy, "flat", nights = 6)
        recordRole(proxy, "flat", PlaceRoles.NONE, RoleSource.USER)

        pass(proxy).inferNow()

        assertEquals(PlaceRoles.NONE to RoleSource.USER, roles(proxy)["flat"])
    }

    @Test
    fun aHomeThatMovedWithdrawsTheOldRow() = runTest {
        // ⚠⚠ Without the withdraw half, the old row keeps saying `home` forever and the user has two.
        // A verdict is a statement about the whole window, so retracting one is part of making it.
        val proxy = stackProxy(this)
        recordPlace(proxy, "old")
        recordPlace(proxy, "new")
        recordRole(proxy, "old", PlaceRoles.HOME, RoleSource.INFERRED)
        sleepAt(proxy, "new", nights = 6)

        pass(proxy).inferNow()

        assertEquals(PlaceRoles.HOME to RoleSource.INFERRED, roles(proxy)["new"])
        assertEquals(PlaceRoles.NONE to RoleSource.INFERRED, roles(proxy)["old"])
    }

    @Test
    fun aSettledWindowWritesNothingOnRerun() = runTest {
        // Change-gating. Without it every recompute emits a SyncBlock per role and every peer
        // replays it — a background pass that talks constantly for no reason.
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        sleepAt(proxy, "flat", nights = 6)

        val p = pass(proxy)
        p.inferNow()
        val afterFirst = roles(proxy)
        p.inferNow()

        assertEquals(afterFirst, roles(proxy))
        assertEquals(1, afterFirst.size, "only the one place should ever have gained a row")
    }

    @Test
    fun aPlaceWithNoVerdictNeverGainsAnEmptyRow() = runTest {
        // Every place the user ever visited would otherwise accumulate a `none` row saying nothing.
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        recordPlace(proxy, "cafe")
        sleepAt(proxy, "flat", nights = 6)
        // A handful of lunchtime visits — nowhere near a role.
        repeat(3) { day -> visit(proxy, "cafe", dayOffset = day.toLong(), hourOfDay = 12, hours = 1) }

        pass(proxy).inferNow()

        assertNull(roles(proxy)["cafe"])
    }

    @Test
    fun aSupersededDuplicateIsNotCountedTwice() = runTest {
        // `MomentReconciliationPass` folds two devices' captures of one visit; counting both would
        // double that place's dwell and could hand it a role it has not earned.
        val proxy = stackProxy(this)
        recordPlace(proxy, "flat")
        recordPlace(proxy, "dupe")
        sleepAt(proxy, "flat", nights = 6)
        // Six superseded nights at another place, which would otherwise tie with `flat`.
        (0L..5L).forEach { day ->
            visit(proxy, "dupe", dayOffset = day, hourOfDay = 23, hours = 6, supersededBy = "some-canonical")
        }

        pass(proxy).inferNow()

        assertEquals(PlaceRoles.HOME to RoleSource.INFERRED, roles(proxy)["flat"])
        assertNull(roles(proxy)["dupe"])
    }

    @Test
    fun theProductionWiringRunsThePass() = runTest {
        // The converse of the others: no explicit `inferNow()`, only `initTimelineService`. Guards
        // the registration itself, which is the half that fails silently.
        val proxy = stackProxy(this, withPass = true)
        recordPlace(proxy, "flat")
        sleepAt(proxy, "flat", nights = 6)
        // ⚠ Not on the save path: the observer arms one coalesced run (`COALESCE_MS`), so a burst
        // of visits costs one whole-relation scan rather than one per fifth visit.
        assertNull(roles(proxy)["flat"], "the save path must not run the pass inline")
        advanceTimeBy(PlaceRoleInferencePass.COALESCE_MS + 1)
        runCurrent()

        assertTrue(
            roles(proxy)["flat"] == PlaceRoles.HOME to RoleSource.INFERRED,
            "expected the registered observer to infer a home, got ${roles(proxy)}",
        )
    }

    @Test
    fun aHomeYouMovedOutOfExpires() = runTest {
        // ⭐ The point of the recency cut, and of the whole bounded-window design (D3): Home is a
        // question about *current* life. Six months of nights at the old flat must stop counting,
        // or the app keeps insisting you live somewhere you left.
        //
        // ⚠ This is what a row COUNT could never express. At a few visits a day a 2 000-moment
        // window spans years; on a busy device it spans weeks. Age says what we actually mean.
        val proxy = stackProxy(this)
        recordPlace(proxy, "old-flat")
        val longAgo = now - 200L * 86_400_000L
        (0L..9L).forEach { day ->
            visitAt(proxy, "old-flat", longAgo + day * 86_400_000L + 23 * hour, hours = 6)
        }

        pass(proxy).inferNow()

        assertNull(roles(proxy)["old-flat"], "visits older than the cut must not name a home")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * A proxy hosting **both** timeline and place — the production shape. ⚠ Timeline alone would
     * leave the roles relation undeclared, and the pass reads it into an empty map by design, so the
     * user-wins tests would pass while proving nothing.
     */
    private fun stackProxy(scope: CoroutineScope, withPass: Boolean = false): TestProxy {
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
                // ⚠ Drop the observers so a test's explicit `inferNow()` is the ONLY actor —
                // otherwise every `saveData` has already run the pass at production settings.
                it.executionContext.observerRegistry.clear()
                it.executionContext.installRelationMaintainer()
            }
        }
    }

    /** Deterministic: UTC, a fixed clock, and a recompute cadence of 1 for the wiring test. */
    private fun pass(proxy: TestProxy) = PlaceRoleInferencePass(
        executionContext = proxy.executionContext,
        zone = TimeZone.UTC,
        nowEpochMs = { now },
    )

    private fun recordPlace(proxy: TestProxy, placeId: String) =
        proxy.executionContext.saveData(
            Place.create(
                context = proxy.tContext,
                placeId = placeId,
                latE7 = 481_450_000,
                lngE7 = 115_760_000,
                capturedAt = UnixInstantDto(now),
            )
        )

    private fun recordRole(proxy: TestProxy, placeId: String, role: String, source: String) =
        proxy.executionContext.saveData(
            PlaceRole.create(
                context = proxy.tContext,
                placeId = placeId,
                role = role,
                roleSource = source,
                decidedAt = UnixInstantDto(now),
            )
        )

    /** `nights` consecutive 23:00 → 05:00 stays, which is what the detector reads as a home. */
    private fun sleepAt(proxy: TestProxy, placeId: String, nights: Int) =
        (0L until nights).forEach { day -> visit(proxy, placeId, day, hourOfDay = 23, hours = 6) }

    private fun visit(
        proxy: TestProxy,
        placeId: String,
        dayOffset: Long,
        hourOfDay: Long,
        hours: Long,
        supersededBy: String = "",
    ) = visitAt(proxy, placeId, mondayMs + dayOffset * 86_400_000L + hourOfDay * hour, hours, supersededBy)

    /** Same, at an absolute instant — for the recency cases the day-offset form cannot express. */
    private fun visitAt(
        proxy: TestProxy,
        placeId: String,
        atMs: Long,
        hours: Long,
        supersededBy: String = "",
    ) {
        proxy.executionContext.saveData(
            Moment.create(
                context = proxy.tContext,
                momentId = "cap-visit:$placeId:$atMs",
                timestamp = UnixInstantDto(atMs),
                kind = Moment.MomentKind.VISIT,
                title = "Visit",
                subject = SubjectRef.create(proxy.tContext, SubjectKind.PLACE, placeId),
                durationSec = hours * 3_600,
                sources = listOf("capture"),
                supersededBy = supersededBy,
            )
        )
    }

    /** `placeId → (role, roleSource)`. */
    private fun roles(proxy: TestProxy): Map<String, Pair<String, String>> =
        proxy.executionContext.database
            .getArrayData(proxy.executionContext.relationReference(PlaceService.Relations.ROLES))
            ?.toList()
            .orEmpty()
            .filterIsInstance<PlaceRole>()
            .associate {
                it.getField<TString>(PlaceRole.Fields.PLACE_ID).get() to
                    (it.getField<TString>(PlaceRole.Fields.ROLE).get() to
                        it.getField<TString>(PlaceRole.Fields.ROLE_SOURCE).get())
            }
}