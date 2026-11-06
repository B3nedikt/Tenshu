package org.tenshu.timeline.proxy

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.readOrderedWindow
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.PlaceService
import org.tenshu.core.place.RoleSource
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.Moment.MomentKind
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.timeline.common.PlaceRoleDetector
import org.tenshu.timeline.common.PlaceRoleNames
import kotlin.time.Clock

/**
 * **Home / Work inference, decided in the proxy** (`docs/proposals/place-roles.md` PR-2). Reads a
 * bounded window of recent `Visit` moments, runs the pure [PlaceRoleDetector], and writes its verdict
 * as `roleSource = inferred` [PlaceRole] rows for the UI to offer for confirmation.
 *
 * ## ⚠⚠ Why here and not in `:inference-service`
 *
 * That seam is task-shaped over **content** — every designed operation takes a `contentRef` (an
 * image, an audio blob) — and has no registration point for "read stored rows, write a derived row".
 * None of it is built, either. The shipped home for a row-derived fact is a proxy-side [DataObserver],
 * which is what `inference-service.md` itself cites as *its* precedent. Deriving here also means the
 * verdict is computed **once per device** and visible to every client, rather than recomputed inside
 * each app's read path.
 *
 * ## ⚠⚠ Why it must not ride the app-side moments read
 *
 * `TimelineProjectionRepository.momentsFlow()` is unbounded and `places()` is O(places × moments) over
 * it. That read is what blanked Timeline Home at ~10.5k rows (`fit-read-scaling.md`), and a
 * place-side pass wanting visit times was abandoned for exactly this reason
 * (`timeline-recognition-and-place-naming.md` §10). This pass uses [readOrderedWindow] instead —
 * bounded selection, thin projection — the same affordable read [MomentReconciliationPass] uses.
 *
 * ## The user always wins
 *
 * A place carrying a `roleSource = user` row is skipped entirely: inference may improve on a previous
 * guess, but it may not talk over the person it is guessing about. That includes a **dismissal**
 * (`role = none, roleSource = user`) — "this is not my home" is an answer, not an absence, and
 * re-suggesting it would make the prompt un-dismissable. The cross-device half of the same guarantee
 * lives in `PlaceRoleMerger`; this is the local half.
 */
class PlaceRoleInferencePass(
    private val executionContext: ExecutionContext,
    /**
     * How many recent moments to read. Bounds the *cost* of the pass — it is **not** what bounds
     * recency; see [maxAgeDays] for that.
     *
     * ⚠ Lowered from 2 000 after a device pass: a count is a **density-dependent** proxy for "recent"
     * and therefore a bad one. At a few visits a day, 2 000 moments spans years — which makes D3's
     * whole premise ("a flat you moved out of a year ago must stop being Home") vacuous — while on a
     * busy device the same number spans weeks. 400 keeps the read cheap at any density; the age cut
     * is what actually enforces recency.
     */
    private val windowSize: Int = DEFAULT_WINDOW,
    /**
     * ⭐ **The real recency bound**, expressed in the unit it actually means.
     *
     * Home and Work are questions about *current* life (D3), and "current" is a duration, not a row
     * count. Filtering by age here makes the rule independent of how densely this device captures —
     * the failure mode a count-based window has by construction.
     *
     * ⚠ Deliberately generous at 90 days: it exists to *expire* a home you have moved out of, not to
     * chase a recent trend, and the detector's distinct-day and share gates already handle "not
     * enough evidence". ⚠⚠ Still unverified against real history — the device this was measured on
     * held roughly two days of capture, so nothing here is calibrated, only reasoned.
     */
    private val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
    /**
     * Recompute after this many `Visit` saves.
     *
     * ⚠ Tuned down from 20 once a test showed what 20 actually costs: **visits are low-frequency
     * events**, a handful a day, so 20 of them is several days — nothing like 20 file writes. The
     * eager run in `initTimelineService` happens before those visits exist, so a fresh device would
     * have sat on sufficient evidence for days without saying anything. 5 bounds first-verdict
     * latency to roughly a day while still costing only a few passes per day.
     */
    private val recomputeEvery: Int = DEFAULT_RECOMPUTE_EVERY,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val config: PlaceRoleDetector.Config = PlaceRoleDetector.Config(),
) : DataObserver {

    /**
     * Visits seen since the last run. ⚠ Deliberately in-memory and **not** a stored row: a proxy
     * restart runs the pass anyway (`initTimelineService`), so persisting the counter would add a row
     * whose only effect is to *delay* a recompute that is already cheap and idempotent.
     */
    private var visitsSinceRun: Int = 0

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        if (typeId != Moment.ID) return
        if (string(data, Moment.Fields.KIND) != MomentKind.VISIT) return

        visitsSinceRun++
        if (visitsSinceRun < recomputeEvery) return
        inferNow()
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // A deleted visit can only weaken a verdict, and the next recompute picks that up. Nothing
        // urgent enough to justify a scan on the delete path.
    }

    /**
     * Run the inference once. Public and synchronous so tests drive it directly rather than racing an
     * observer — the same shape as [MomentReconciliationPass.reconcileRecent] and
     * `PlaceReconciler.reconcileAll`.
     */
    fun inferNow() {
        visitsSinceRun = 0

        val existing = readExistingRoles()
        val candidates = PlaceRoleDetector.detect(readRecentVisits(), config)
            .associate { it.placeId to it.role }

        // Every place the pass has an opinion about: the ones it now names, plus the ones it named
        // before. ⚠⚠ The second half is load-bearing — without it a home that MOVED would leave its
        // old row saying `home` forever and the user would have two. A verdict is a statement about
        // the whole window, so withdrawing one is as much a part of it as making one.
        val touched = candidates.keys + existing.filterValues { it.isInferred }.keys

        touched.forEach { placeId ->
            val current = existing[placeId]
            // The user has spoken about this place. Never overwrite, in either direction.
            if (current != null && !current.isInferred) return@forEach

            val role = candidates[placeId] ?: PlaceRoles.NONE
            // ⚠ Only on change. A settled window must write nothing, or every recompute would emit a
            // SyncBlock per role and the peers would replay it.
            if (current != null && current.role == role) return@forEach
            // Nothing to withdraw and nothing to say: never mint a `none` row from thin air, or every
            // place the user ever visited would eventually carry one.
            if (current == null && role == PlaceRoles.NONE) return@forEach

            executionContext.saveData(
                PlaceRole.create(
                    context = executionContext.context,
                    placeId = placeId,
                    role = role,
                    roleSource = RoleSource.INFERRED,
                    decidedAt = UnixInstantDto(nowEpochMs()),
                )
            )
        }
    }

    // ── store access ───────────────────────────────────────────────────────────

    /**
     * The recent `Visit` moments, localised for the detector. A moment whose subject is not a place,
     * or which carries no duration, contributes nothing and is dropped here rather than weighted at
     * zero downstream.
     */
    private fun readRecentVisits(): List<PlaceRoleDetector.VisitDwell> =
        executionContext.readOrderedWindow(
            reference = executionContext.relationReference(TimelineService.Relations.MOMENTS),
            orderByFieldId = Moment.Fields.TIMESTAMP,
            limit = windowSize,
            descending = true,
            keepFields = setOf(
                Moment.Fields.KIND, Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC,
                Moment.Fields.SUPERSEDED_BY,
            ),
        ).mapNotNull { row ->
            if (string(row, Moment.Fields.KIND) != MomentKind.VISIT) return@mapNotNull null
            // A superseded moment is a duplicate of another already in this window
            // ([MomentReconciliationPass]); counting both would double its place's dwell.
            if (string(row, Moment.Fields.SUPERSEDED_BY).isNotEmpty()) return@mapNotNull null

            val subject = subjectOf(row) ?: return@mapNotNull null
            if (subject.kind.get() != SubjectKind.PLACE) return@mapNotNull null
            val placeId = subject.id.get()
            if (placeId.isBlank()) return@mapNotNull null

            val durationSec = int(row, Moment.Fields.DURATION_SEC)
            if (durationSec <= 0L) return@mapNotNull null

            val timestamp = timestampOf(row) ?: return@mapNotNull null
            // The recency cut (see [maxAgeDays]). Applied here rather than in the detector for the
            // same reason the timezone conversion is: this is the boundary that owns the clock, and
            // the pure module must stay free of both.
            if (nowEpochMs() - timestamp.toEpochMilliseconds() > maxAgeDays * DAY_MS) return@mapNotNull null
            PlaceRoleDetector.VisitDwell(
                placeId = placeId,
                localStartMs = toLocalWallClockMs(timestamp),
                durationSec = durationSec,
            )
        }

    /**
     * Epoch millis → **wall-clock** millis in [zone], the form [PlaceRoleDetector] buckets by.
     *
     * ⚠ The offset is resolved per instant, not once for the window, so a visit either side of a DST
     * boundary buckets under the offset actually in force then. ⚠⚠ It is still the *device's current*
     * zone applied to a past instant — `Moment` stores no offset of its own (proposal D6) — so a visit
     * recorded abroad buckets under home hours. That is the known soft spot; the detector's
     * distinct-days gate is what keeps a trip from unseating a real home.
     */
    private fun toLocalWallClockMs(timestamp: UnixInstant): Long {
        val epochMs = timestamp.toEpochMilliseconds()
        val local = kotlin.time.Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return local.toInstant(TimeZone.UTC).toEpochMilliseconds()
    }

    private data class StoredRole(val role: String, val isInferred: Boolean)

    /**
     * `placeId → its stored role`. Small by construction: one row per place that has ever been given
     * or offered a role, not one per place.
     *
     * ⚠ Empty when the place service is not registered in this proxy — `relationReference` fails
     * loudly on an undeclared relation, deliberately, and a timeline-only wiring is legitimate. The
     * safe direction: with no roles readable, [inferNow] writes nothing (every `saveData` would fail
     * the same way), rather than mistaking "cannot read" for "no user has answered".
     */
    private fun readExistingRoles(): Map<String, StoredRole> = runCatching {
        executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.ROLES))
            ?.toList()
            .orEmpty()
            .mapNotNull { row ->
                val id = string(row, PlaceRole.Fields.PLACE_ID)
                if (id.isBlank()) return@mapNotNull null
                id to StoredRole(
                    role = string(row, PlaceRole.Fields.ROLE),
                    isInferred = !RoleSource.isUser(string(row, PlaceRole.Fields.ROLE_SOURCE)),
                )
            }
            .toMap()
    }.getOrElse { emptyMap() }

    companion object {
        const val DEFAULT_WINDOW: Int = 400
        const val DEFAULT_MAX_AGE_DAYS: Int = 90
        private const val DAY_MS: Long = 86_400_000L
        const val DEFAULT_RECOMPUTE_EVERY: Int = 5

        init {
            // The pure module cannot depend on the place schema, so the two vocabularies are declared
            // twice. This is the seam that would silently emit an unrecognised role if they drifted.
            require(PlaceRoleNames.HOME == PlaceRoles.HOME && PlaceRoleNames.WORK == PlaceRoles.WORK) {
                "PlaceRoleNames and PlaceRoles disagree — the detector would emit an unknown role"
            }
        }

        private fun string(data: Data, fieldId: String): String = when (data) {
            is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
            is Composite -> data.getField<TString>(fieldId).get()
            else -> ""
        }

        private fun int(data: Data, fieldId: String): Long = when (data) {
            is Entity<*> -> data.getFieldValue<TInteger>(fieldId).get()
            is Composite -> data.getField<TInteger>(fieldId).get()
            else -> 0L
        }

        private fun timestampOf(data: Data): UnixInstant? = when (data) {
            is Entity<*> -> data.getFieldValue(Moment.Fields.TIMESTAMP)
            is Composite -> data.getField(Moment.Fields.TIMESTAMP)
            else -> null
        }

        private fun subjectOf(data: Data): SubjectRef? = when (data) {
            is Entity<*> -> data.getFieldValue(Moment.Fields.SUBJECT)
            is Composite -> data.getField(Moment.Fields.SUBJECT)
            else -> null
        }
    }
}
