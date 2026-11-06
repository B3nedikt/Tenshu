package org.tenshu.timeline.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.readOrderedWindow
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceService
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.sync.common.resolveListField
import org.tenshu.timeline.common.MomentReconciler
import org.tenshu.timeline.common.ReconcilableMoment

/**
 * **Cross-source moment reconciliation, decided in the proxy** (P4e-2,
 * `docs/proposals/timeline-recognition-and-place-naming.md` §10.3). Two devices at the same lunch
 * each write a `cap-visit:<placeId>:<arrivalMs>` moment — different arrival millisecond, so different
 * content-addressed ids, two valid rows, both synced. This elects one canonical and stamps
 * `supersededBy` on the rest, so every client sees one moment per real-world moment.
 *
 * ⚠⚠ **The verdict is persisted, not recomputed per read.** [MomentReconciler] decides it and
 * `Moment.sources` / `supersededBy` carry it. Recomputing it **per read, per device** would be
 * O(all moments) inside the `homeDays()`/`places()` combines — the unbounded shape that blanked Home
 * at ~10.5k workouts — and invisible to any client that does not run it (the iOS surfaces, the JS
 * SDK, a desktop).
 *
 * **Bounded by construction.** The pass reconciles the most recent [windowSize] moments via
 * [readOrderedWindow], never the whole relation — the affordable read of
 * `core-ordered-window-fusion.md` §6. It is *sufficient*, because the duplicate this exists to
 * catch is created by a second device within hours of the first. ⚠ A years-old import will not
 * dedupe against equally old captures at write time; that is the documented cost of not scanning
 * history, and the batched import pass (Q12) is where it would be addressed if it ever matters.
 *
 * **Idempotent, so it cannot loop.** The pass writes `Moment` rows, and those writes re-enter this
 * observer. It writes only when a moment's `supersededBy` actually *changes*, so the second run over
 * a settled window writes nothing and the recursion stops after one step. The verdict is derived
 * deterministically from the invariant (place cell + time bucket / workout subject), never from
 * arrival order, so a re-run is a no-op rather than a different answer.
 */
@OptIn(ExperimentalAtomicApi::class)
class MomentReconciliationPass(
    private val executionContext: ExecutionContext,
    /**
     * How many of the most recent moments to reconcile per run. 200 comfortably spans the window in
     * which two devices' captures of one real-world moment can arrive, while keeping the resident set
     * trivial. ⚠ It bounds the *selection*, not the scan — see [readOrderedWindow].
     */
    private val windowSize: Int = DEFAULT_WINDOW,
) : DataObserver {

    /**
     * ⚠⚠ **Coalesced, not per-save.** This used to call [reconcileRecent] on every saved moment,
     * and each call re-reads a [windowSize] window plus every place — ~14.5 ms measured on device.
     * Ingesting one 500-block page of moments therefore cost ~500 window scans (~5.8 s), which was
     * ~20% of a whole sync tick, to reach a verdict that one scan reaches just as well: the pass
     * considers the *whole* window every time, so N runs over one burst are N-1 runs wasted.
     *
     * ⚠ Deliberately a **fixed-cadence** coalesce, not a trailing-edge debounce that resets on each
     * save. A resetting timer never fires during a continuous burst — and a backfill is a burst
     * hours long — so moments would scroll out of the [windowSize] window unreconciled before the
     * first run. The invariant that replaces it: **[COALESCE_MS] must be short enough that
     * consecutive flushes are fewer than [windowSize] moments apart.** At the measured ingest rate
     * (~10 ms/block) one second is ~100 moments against a 200 window — half the budget.
     *
     * ⚠ [WriteOrigin.DERIVED] is skipped because that is this pass's own output; re-arming on it
     * would add a guaranteed empty run after every real one.
     */
    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        if (typeId != Moment.ID) return
        if (origin == WriteOrigin.DERIVED) return
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        if (!pendingFlush.compareAndSet(expectedValue = false, newValue = true)) return
        executionContext.sessionScope.launch {
            delay(COALESCE_MS)
            // ⚠ Cleared BEFORE the work, so a save arriving *during* a reconcile arms the next
            // flush instead of being swallowed by the one already running.
            pendingFlush.store(false)
            reconcileRecent()
        }
    }

    /** Whether a coalesced flush is already armed — see [onDataSaved]. */
    private val pendingFlush = AtomicBoolean(false)

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // A deleted moment leaves its cluster; re-running would re-elect, but a delete is rare and
        // the next save reconciles anyway. Deliberately no work on the delete path.
    }

    /**
     * Reconcile the recent window once. Public and synchronous so tests drive it directly rather than
     * racing an observer — the same shape as `PlaceReconciler.reconcileAll`.
     */
    fun reconcileRecent() {
        val moments = readRecentMoments()
        if (moments.size < 2) return

        val placeCoords = readPlaceCoords()
        // ⚠⚠ A Visit whose place coordinates we cannot resolve is EXCLUDED, never clustered at
        // (0, 0). The cluster key is `placeCell + timeBucket`, so defaulting missing coordinates to
        // zero would put every unresolvable visit in ONE cell and merge unrelated places that
        // happened to share a time bucket. (The app-side pass this replaces defaulted to 0 — a
        // latent over-merge that only stayed harmless because its place join rarely missed.)
        val inputs = moments.mapNotNull { it.toReconcilable(placeCoords) }
        val verdicts = MomentReconciler.reconcile(inputs)

        // momentId → the canonical it is folded into ("" for a canonical).
        val supersededBy = HashMap<String, String>()
        verdicts.forEach { v ->
            supersededBy[v.canonicalMomentId] = ""
            v.supersededMomentIds.forEach { supersededBy[it] = v.canonicalMomentId }
        }

        moments.forEach { row ->
            val target = supersededBy[row.momentId] ?: return@forEach
            // ⚠⚠ SET only, never CLEAR. A bounded window can prove "these two are the same moment";
            // it cannot prove "this one is a duplicate of nothing" — the counterpart may simply be
            // older than the window, or the verdict may have been reached by another device. Clearing
            // on that basis would RESURRECT a superseded moment, which is strictly worse than leaving
            // the pass out. Re-exposing a moment (§7.1's reversibility, when a source is removed) is a
            // deliberate act elsewhere, never a side effect of what this pass happened to see.
            if (target.isEmpty()) return@forEach
            // ⚠ Only on change — this is what stops the observer re-entering forever.
            if (target == row.supersededBy) return@forEach
            // ⚠⚠ DERIVED, never the default USER. `supersededBy` is a *projection* — every device
            // recomputes it from the moments it holds — so emitting it broadcasts this device's
            // opinion of a bounded local window as though it were new information. Two devices with
            // different windows then flip the field between their answers indefinitely, and that is
            // exactly what happened: 99.8% of all realm traffic, sustained, around the clock.
            // The peer does not need to be told; it derives the same verdict from the same rule.
            executionContext.saveData(
                row.withSupersededBy(target, executionContext),
                origin = WriteOrigin.DERIVED,
            )
        }
    }

    // ── store access ───────────────────────────────────────────────────────────

    private fun readRecentMoments(): List<MomentRow> =
        executionContext.readOrderedWindow(
            reference = executionContext.relationReference(TimelineService.Relations.MOMENTS),
            orderByFieldId = Moment.Fields.TIMESTAMP,
            limit = windowSize,
            descending = true,
            keepFields = setOf(
                Moment.Fields.MOMENT_ID, Moment.Fields.KIND, Moment.Fields.TITLE,
                Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC, Moment.Fields.SUPERSEDED_BY,
                Moment.Fields.SOURCES,
            ),
        ).mapNotNull { MomentRow.of(it, executionContext) }

    /**
     * `placeId → (latE7, lngE7)` — the join a `Visit`'s cluster key needs. Places are few.
     *
     * ⚠ Empty when the place service is not registered in this proxy: `relationReference` fails
     * loudly on an undeclared relation, deliberately, and a timeline-only wiring is a legitimate
     * configuration. Every `Visit` is then excluded from reconciliation, which is the safe direction
     * — no coordinates means no evidence that two visits are the same place.
     */
    private fun readPlaceCoords(): Map<String, Pair<Long, Long>> = runCatching {
        executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.PLACES))
            ?.toList()
            .orEmpty()
            .mapNotNull { row ->
                val id = string(row, Place.Fields.PLACE_ID)
                if (id.isBlank()) null else id to (int(row, Place.Fields.LAT_E7) to int(row, Place.Fields.LNG_E7))
            }
            .toMap()
    }.getOrElse { emptyMap() }

    private class MomentRow(
        val momentId: String,
        val kind: String,
        val title: String,
        val timestamp: UnixInstant,
        val durationSec: Long,
        val subjectKind: String,
        val subjectId: String,
        val subject: SubjectRef?,
        val supersededBy: String,
        val sources: List<String>,
    ) {
        fun toReconcilable(placeCoords: Map<String, Pair<Long, Long>>): ReconcilableMoment? {
            val coords = if (subjectKind == SubjectKind.PLACE) {
                placeCoords[subjectId] ?: return null // see the ⚠⚠ at the call site
            } else {
                null
            }
            return ReconcilableMoment(
                momentId = momentId,
                kind = kind,
                startMs = timestamp.toEpochMilliseconds(),
                latE7 = coords?.first ?: 0,
                lngE7 = coords?.second ?: 0,
                subjectKind = subjectKind,
                subjectId = subjectId,
                // One tag in practice (the writer's); take the first deterministically if a merge
                // unioned several — same rule the app-side reconcile used.
                source = sources.firstOrNull().orEmpty(),
            )
        }

        fun withSupersededBy(target: String, ctx: ExecutionContext): Moment = Moment.create(
            context = ctx.context,
            momentId = momentId,
            timestamp = timestamp,
            kind = kind,
            title = title,
            subject = subject ?: SubjectRef.none(ctx.context),
            durationSec = durationSec,
            sources = sources,
            supersededBy = target,
        )

        companion object {
            fun of(row: Data, ctx: ExecutionContext): MomentRow? {
                val id = string(row, Moment.Fields.MOMENT_ID)
                if (id.isBlank()) return null
                val subject = subjectOf(row)
                return MomentRow(
                    momentId = id,
                    kind = string(row, Moment.Fields.KIND),
                    title = string(row, Moment.Fields.TITLE),
                    timestamp = timestampOf(row) ?: return null,
                    durationSec = int(row, Moment.Fields.DURATION_SEC),
                    subjectKind = subject?.kind?.get().orEmpty(),
                    subjectId = subject?.id?.get().orEmpty(),
                    subject = subject,
                    supersededBy = string(row, Moment.Fields.SUPERSEDED_BY),
                    sources = ctx.resolveListField(row, Moment.Fields.SOURCES)
                        .filterIsInstance<MomentSource>()
                        .map { it.source.get() },
                )
            }
        }
    }

    companion object {
        /**
         * Coalescing cadence for [onDataSaved]. ⚠ Bounded ABOVE by [DEFAULT_WINDOW]: consecutive
         * flushes must be fewer than a window's worth of moments apart, or a mid-burst moment ages
         * out of the window before any run considers it. At the measured ingest rate (~10 ms/block)
         * one second is ~100 moments against a 200 window — half the budget.
         */
        internal const val COALESCE_MS: Long = 1_000L

        const val DEFAULT_WINDOW: Int = 200

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
