package org.tenshu.place.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceClustering
import org.tenshu.core.place.PlaceCorroboration
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.PlaceService
import org.tenshu.core.time.UnixInstant

/**
 * **Place identity, decided in the proxy** (`docs/proposals/timeline-recognition-and-place-naming.md`
 * §10.3, P4e). Every time a [Place] is written — by a local capture *or* by a peer's block landing —
 * this looks at the places around it and records a [PlaceMerge] for any that are really the same
 * venue.
 *
 * ⚠⚠ **Why this cannot live in the app.** The write-time snap (`PlaceIdentityResolver`, §8.4 F1)
 * asks "do I already know a place here?" of the *writing device*. Two devices capturing one venue
 * before either has pulled the other — or one device offline for a day — each mint their own id, and
 * both are then perfectly valid rows that sync happily side by side. Write-time deduplication is a
 * good cheap filter and a bad convergence mechanism; the authority has to sit where every device's
 * data actually meets. On iOS route-1 that is still in-process (each first-party app hosts its own
 * proxy), so "in the proxy" costs no per-platform work.
 *
 * ⚠ **Complements, never replaces, F1.** The app-side snap stops the common case before it becomes a
 * row at all; this converges what F1 structurally cannot see. Moving F1 here would put a
 * cross-process round trip on every captured dwell.
 *
 * **Idempotent by construction** (§10.3): the pass writes rows, and those writes re-enter this
 * observer. [PlaceClustering.mergesFor] returns only merges *not already recorded with that target*,
 * so the second run over the same input writes nothing and the recursion stops after one step. The
 * decision is derived deterministically from the rows, never from arrival order, so a re-run is a
 * no-op rather than a different answer.
 *
 * **Bounding.** One pass reads every `Place` row and clusters around the saved one — O(N) in places,
 * where N is tens to low hundreds even for a heavy user (a place is a *venue*, not a visit). That is
 * the same read `TimelineImportWriter` already does on the capture path, so it is not a new cost
 * class. ⚠ It would become one if places ever reached the scale moments do; the fix then is a
 * grid-cell index, not a wider scan. Moments are deliberately **not** reconciled here — see the
 * as-built note in §10.9.
 *
 * ⚠⚠ **Never on the save path.** A save observer runs *inside* the writer's transaction — for a peer's
 * block that is `SyncProcessor.resolveConflictAndSave`'s `database.transaction { }`, which on the iOS
 * SQLite replica is one `BEGIN IMMEDIATE` under `HOLD_MAX`. The ambiguous-pair arbiter
 * (`VisitTimeCorroboration`) reads the MOMENTS relation, and paging cannot bound a read that re-enters
 * a held lock (`core-ordered-window-fusion.md` §6.1), so a synced `Place` used to fence a phone with
 * thousands of moments `COMMIT_FAILED`. [onDataSaved] therefore only records the anchor; the
 * reconciliation runs coalesced on `sessionScope`, after the saving transaction has committed — the
 * same shape as `MomentReconciliationPass` and `PlaceRoleInferencePass`.
 */
@OptIn(ExperimentalAtomicApi::class)
class PlaceReconciler(
    private val executionContext: ExecutionContext,
    /** Injected so tests are deterministic; production passes a real clock. */
    private val nowEpochMs: () -> Long,
    /**
     * Arbitrates the ambiguous middle only (§10.12) — both places named, both names machine-guessed,
     * the guesses disagreeing. Defaults to [PlaceCorroboration.Unknown], which is exactly today's
     * behaviour: no evidence ⇒ the fold goes ahead. Production hands it the timeline-backed
     * `VisitTimeCorroboration`; a place-only wiring legitimately has none.
     */
    private val corroboration: PlaceCorroboration = PlaceCorroboration.Unknown,
) : DataObserver {

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        val typeId = (data as? Entity<*>)?.type?.get() ?: data.id
        if (typeId != Place.ID) return

        val anchorId = string(data, Place.Fields.PLACE_ID)
        if (anchorId.isBlank()) return

        // ⚠⚠ Record, never reconcile, here — see the class KDoc.
        while (true) {
            val current = pendingAnchors.load()
            if (pendingAnchors.compareAndSet(current, current + anchorId)) break
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushArmed.compareAndSet(expectedValue = false, newValue = true)) return
        executionContext.sessionScope.launch {
            // ⚠ "After the commit" is the lock's doing, not the delay's: `transaction { }` holds the
            // store lock until it commits, so a flush that wakes while the writer is still inside
            // blocks on its first read and then sees the committed state.
            delay(COALESCE_MS)
            // ⚠ Cleared BEFORE the anchors are taken, so a save arriving during the flush arms the
            // next one instead of being swallowed by the run already in progress.
            flushArmed.store(false)
            reconcilePending()
        }
    }

    /** Whether a coalesced flush is already armed — see [onDataSaved]. */
    private val flushArmed = AtomicBoolean(false)

    /** The `placeId`s saved since the last flush. */
    private val pendingAnchors = AtomicReference<Set<String>>(emptySet())

    /**
     * Reconcile every anchor saved since the last flush, once each, outside any writer's transaction.
     * Public and synchronous so tests can drive it directly. Failures are logged and dropped **per
     * anchor**, as in [reconcileAll]: this runs detached on `sessionScope`, where a throw would reach no
     * caller, and one anchor's failure must not drop the rest of the batch.
     *
     * ⚠ An anchor not in PLACES is skipped, not reconciled from its payload: the relation maintainer
     * adds the row in the same synchronous save, so after a commit it is there — absence means the
     * save rolled back, and a merge pointing at a place that never committed would sync to peers.
     */
    fun reconcilePending() {
        val anchors = pendingAnchors.exchange(emptySet())
        if (anchors.isEmpty()) return
        val places: List<PlaceClustering.Candidate>
        var merges: Map<String, String>
        try {
            places = loadPlaces()
            merges = loadMerges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("WARN PlaceReconciler: coalesced pass skipped (${e::class.simpleName}: ${e.message})")
            return
        }
        val byId = places.associateBy { it.placeId }
        anchors.forEach { anchorId ->
            val anchor = byId[anchorId] ?: return@forEach
            try {
                reconcileAround(anchor, places, merges)
                // Re-read, as in [reconcileAll]: a fold just recorded must be visible to the next anchor.
                if (anchors.size > 1) merges = loadMerges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WARN PlaceReconciler: anchor $anchorId skipped (${e::class.simpleName}: ${e.message})")
            }
        }
    }

    /**
     * Reconcile **every** place once — the repair path for rows written before this pass existed
     * (§10.3). Called at init.
     *
     * ⚠⚠ Without it the duplicates already in a user's store would very likely never be repaired: the
     * observer fires on a `Place` *save*, and a repeat visit to an already-named place writes no
     * `Place` row at all — `TimelineImportWriter`'s §4.4 guard skips the write precisely because the
     * incoming blank label would clobber the name. So the rows that most need merging are exactly the
     * ones that stop triggering saves.
     *
     * Idempotent and cheap: a settled store yields no merges, and it is O(N²) over *places* (tens to
     * low hundreds), not moments. Failures are swallowed — a locked or empty store at init must never
     * take the service down, and the next save reconciles anyway.
     */
    fun reconcileAll() {
        try {
            val places = loadPlaces()
            if (places.size < 2) return
            var merges = loadMerges()
            places.forEach { anchor ->
                reconcileAround(anchor, places, merges)
                // Re-read rather than trusting the local copy: a fold just recorded must be visible
                // to the next anchor, or a three-row cluster would emit contradictory pointers.
                merges = loadMerges()
            }
        } catch (e: Exception) {
            println("WARN PlaceReconciler: startup sweep skipped (${e::class.simpleName}: ${e.message})")
        }
    }

    private fun reconcileAround(
        anchor: PlaceClustering.Candidate,
        places: List<PlaceClustering.Candidate>,
        existingMerges: Map<String, String>,
    ) {
        val merges = PlaceClustering.mergesFor(
            anchor = anchor,
            others = places,
            existingMerges = existingMerges,
            corroboration = corroboration,
        )
        if (merges.isEmpty()) return

        val byId = places.associateBy { it.placeId }
        val now = nowEpochMs()
        merges.forEach { merge ->
            executionContext.saveData(
                PlaceMerge.create(
                    context = executionContext.context,
                    placeId = merge.placeId,
                    mergedInto = merge.mergedInto,
                    mergedAt = UnixInstantDto(now),
                )
            )
        }

        // §9.4: the pointer target is deterministic, so the row that survives is often *not* the one
        // the user renamed. Carry the name across, or merging would hide it.
        val rootId = merges.first().mergedInto
        val root = byId[rootId] ?: return
        val carried = PlaceClustering.userLabelToCarry(
            root = root,
            merged = merges.mapNotNull { byId[it.placeId] },
        ) ?: return
        executionContext.saveData(rowOf(rootId, carried) ?: return)
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
        // A deleted place's pointer is left alone deliberately: `PlaceMerge.resolveRoot` treats a
        // dangling target as "the row we have", so nothing breaks, and keeping the row means an
        // undelete (or a late-arriving peer block) still resolves the way it did before.
    }

    // ── store access ───────────────────────────────────────────────────────────

    private fun loadPlaces(): List<PlaceClustering.Candidate> {
        val tags = loadNetworkTags()
        return executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.PLACES))
            ?.toList()
            .orEmpty()
            .mapNotNull { row ->
                val id = string(row, Place.Fields.PLACE_ID).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                candidateOf(row, tags[id].orEmpty())
            }
    }

    private fun loadMerges(): Map<String, String> =
        executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.MERGES))
            ?.toList()
            .orEmpty()
            .mapNotNull { row ->
                val from = string(row, PlaceMerge.Fields.PLACE_ID)
                val to = string(row, PlaceMerge.Fields.MERGED_INTO)
                if (from.isBlank() || to.isBlank()) null else from to to
            }
            .toMap()

    /** `placeId → network tags`; empty until F4 syncs fingerprints, which is fine (§9.5 gate). */
    private fun loadNetworkTags(): Map<String, Set<String>> =
        executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.NETWORK_FINGERPRINTS))
            ?.toList()
            .orEmpty()
            .mapNotNull { row ->
                val place = string(row, PlaceNetworkFingerprint.Fields.PLACE_ID)
                val tag = string(row, PlaceNetworkFingerprint.Fields.NETWORK_TAG)
                if (place.isBlank() || tag.isBlank()) null else place to tag
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.toSet() }

    /** The stored [Place] row for [placeId], re-recorded with a user-authored [label]. */
    private fun rowOf(placeId: String, label: String): Place? {
        val row = executionContext.database
            .getArrayData(executionContext.relationReference(PlaceService.Relations.PLACES))
            ?.toList()
            .orEmpty()
            .firstOrNull { string(it, Place.Fields.PLACE_ID) == placeId }
            ?: return null
        return Place.create(
            context = executionContext.context,
            placeId = placeId,
            latE7 = int(row, Place.Fields.LAT_E7),
            lngE7 = int(row, Place.Fields.LNG_E7),
            capturedAt = timestamp(row, Place.Fields.CAPTURED_AT),
            label = label,
            city = string(row, Place.Fields.CITY),
            countryCode = string(row, Place.Fields.COUNTRY_CODE),
            labelSource = LabelSource.USER,
            accuracyM = int(row, Place.Fields.ACCURACY_M).toInt(),
        )
    }

    private fun candidateOf(row: Data, tags: Set<String> = emptySet()) = PlaceClustering.Candidate(
        placeId = string(row, Place.Fields.PLACE_ID),
        latE7 = int(row, Place.Fields.LAT_E7),
        lngE7 = int(row, Place.Fields.LNG_E7),
        accuracyM = int(row, Place.Fields.ACCURACY_M).toInt(),
        label = string(row, Place.Fields.LABEL),
        labelSource = string(row, Place.Fields.LABEL_SOURCE),
        networkTags = tags,
    )

    companion object {
        /**
         * Coalescing cadence. A place is a venue, not a visit, so `Place` saves are rare and there is
         * no window for an anchor to age out of — the delay only folds a sync page's worth of places
         * into one read of PLACES/MERGES instead of one per row.
         */
        internal const val COALESCE_MS: Long = 1_000L
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

    private fun timestamp(data: Data, fieldId: String): UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(fieldId)
        is Composite -> data.getField(fieldId)
        else -> error("no $fieldId")
    }
}
