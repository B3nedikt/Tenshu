package org.tenshu.core.health

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.trait.PrimaryContent
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A **provider-neutral** workout/activity record — the metadata plane of the health
 * service (`docs/proposals/timeline-services.md` §4). The storage substrate behind the
 * Timeline app's `ActivityRollup` and the `Activity`-kind moment's subject.
 *
 * There is no neutral cross-platform health standard: every platform gates data behind a
 * proprietary API (HealthKit, Health Connect — Google Fit dies end-2026), and the only real
 * interchange is the FIT/TCX/GPX file trio. So this is *our own* neutral shape, fed by a
 * per-platform import seam (§4.2). It follows the HealthKit/Health-Connect split — **summary
 * on the session, dense time-series elsewhere** — so a low-fidelity import (a bare GPX track)
 * and a high-fidelity one (a FIT file with per-second HR/power/laps) both fit without forcing
 * empty fields.
 *
 * This scaffold carries the **session header + summary** only. Deferred a slice (§4.1):
 *  - **`laps`** — an embedded list-of-composite (`WorkoutLap`) persists as array elements each
 *    needing a `DataIdProvider` (as `File` does in `Folder.files`); a lap-identity decision.
 *  - **route + sample streams** — the dense payload rides the **content plane** (ADR 0017
 *    chunked AEAD), referenced from here and fetched lazily, exactly like File bytes.
 *
 * Optional stats use a sentinel (`0` / `""`) for the scaffold; they graduate to `maybe`
 * fields when the import seam lands. `Syncable(Eager)`, keyed by [workoutId] via
 * [WorkoutDataIdProvider] so a re-import folds to one row.
 *
 * **`RealmData`** (`docs/proposals/core-realm-data.md`): workouts are the user's data, not
 * Fit's — Timeline's importer writes the same rows Fit records — so they surface in Control's
 * **Data** section as their own card and are resettable there
 * (`docs/proposals/control-data-reset.md` D1: the unit of reset is a data *type*, never an app).
 * The trait's `CREATED_AT` contract is bound to [Workout.Start] in `healthServiceCommonModule`.
 */
interface Workout : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Our stable, self-owned id — the only primary key. */
    val workoutId: TString
        get() = getField(Fields.WORKOUT_ID)

    /** Neutral activity type — `Running` / `Walking` / `Cycling` / `Strength` / … */
    val activityType: TString
        get() = getField(Fields.ACTIVITY_TYPE)

    /** Where it came from — `HealthKit` / `HealthConnect` / `Fit` / `Tcx` / `Gpx` / `Manual`. */
    val source: TString
        get() = getField(Fields.SOURCE)

    val start: UnixInstant
        get() = getField(Fields.START)

    val end: UnixInstant
        get() = getField(Fields.END)

    val durationSec: TInteger
        get() = getField(Fields.DURATION_SEC)

    /** User/title label; `""` when unset. */
    val title: TString
        get() = getField(Fields.TITLE)

    /** Free-text notes; `""` when unset. */
    val notes: TString
        get() = getField(Fields.NOTES)

    /** Total distance in metres; `0` when unknown. */
    val distanceM: TInteger
        get() = getField(Fields.DISTANCE_M)

    /** Active energy in kilojoules; `0` when unknown. */
    val activeEnergyKj: TInteger
        get() = getField(Fields.ACTIVE_ENERGY_KJ)

    /** Average heart rate in bpm; `0` when unknown. */
    val avgHr: TInteger
        get() = getField(Fields.AVG_HR)

    /** Steps taken during the session (on-device step counter); `0` when unknown/not step-based. */
    val steps: TInteger
        get() = getField(Fields.STEPS)

    companion object {
        const val ID = "tenshu/health/workout"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // ⚠ Trait membership is code-side schema metadata — never serialised (the `@defer`
            // precedent). Adding RealmData adds no field and reorders none, so the positional
            // encoding of a Workout row is byte-identical before and after: no
            // TENSHU_STORAGE_FORMAT_VERSION bump, no SchemaMigration, no service version bump.
            traits = listOf(
                WorkoutDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                RealmData.Schema,
                // §6e N1: names the property this type's reference handles derive from.
                ContentReferencing.Schema,
            ),
            fields = {
                string(Fields.WORKOUT_ID)
                string(Fields.ACTIVITY_TYPE)
                string(Fields.SOURCE)
                timestamp(Fields.START)
                timestamp(Fields.END)
                int(Fields.DURATION_SEC)
                string(Fields.TITLE)
                string(Fields.NOTES)
                int(Fields.DISTANCE_M)
                int(Fields.ACTIVE_ENERGY_KJ)
                int(Fields.AVG_HR)
                int(Fields.STEPS)
                // laps + route + sample streams — next slice, see class KDoc
            }
        )

        fun create(
            context: TContext,
            workoutId: String,
            activityType: String,
            source: String,
            start: UnixInstant,
            end: UnixInstant,
            durationSec: Long,
            title: String = "",
            notes: String = "",
            distanceM: Long = 0,
            activeEnergyKj: Long = 0,
            avgHr: Long = 0,
            steps: Long = 0,
        ): Workout = context.create(
            type = ID,
            fields = listOf(
                workoutId,
                activityType,
                source,
                start,
                end,
                durationSec.toTInteger(context),
                title,
                notes,
                distanceM.toTInteger(context),
                activeEnergyKj.toTInteger(context),
                avgHr.toTInteger(context),
                steps.toTInteger(context),
            )
        )
    }

    object Fields {
        const val WORKOUT_ID = "$ID/workoutId"
        const val ACTIVITY_TYPE = "$ID/activityType"
        const val SOURCE = "$ID/source"
        const val START = "$ID/start"
        const val END = "$ID/end"
        const val DURATION_SEC = "$ID/durationSec"
        const val TITLE = "$ID/title"
        const val NOTES = "$ID/notes"
        const val DISTANCE_M = "$ID/distanceM"
        const val ACTIVE_ENERGY_KJ = "$ID/activeEnergyKj"
        const val AVG_HR = "$ID/avgHr"
        const val STEPS = "$ID/steps"
    }
}

val Entity<out Workout>.workoutId: TString
    get() = getFieldValue(Workout.Fields.WORKOUT_ID)

private val workoutStart: Property.Value<UnixInstant> =
    valueProperty(id = Workout.Fields.START, schema = UnixInstant.Schema)

/**
 * The workout's start time as a **sortable value property** — the `Fields.START` timestamp exposed
 * for `sortByDescending`, so a list read can page/window newest-first at the store rather than
 * loading every workout to sort in memory (`docs/proposals/fit-read-scaling.md`; mirrors
 * `File.LastModifiedAt`, which Drive's paged file list sorts on).
 */
val Workout.Companion.Start: Property.Value<UnixInstant> get() = workoutStart

private val workoutStreams: Property.Dynamic<TByteArray> = dynamicProperty<Workout, TByteArray>(
    id = "${Workout.ID}/streams",
    receiver = Workout.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), PrimaryContent()),
) { getStreams() }

/**
 * The dense per-sample **route + heart-rate streams** of a workout — a **content-plane** blob
 * (`org.tenshu.health.common.WorkoutStreams` encoded by `WorkoutStreamsCodec`), read only on a
 * workout-**detail** surface.
 *
 * Mirrors `File.Data` (read-scaling option B, `docs/proposals/fit-workout-streams-content-plane.md`):
 * a `dynamicProperty(Syncable(OnDemand))` whose bytes live in the external `FileDatabase` under
 * `/.health/streams/{workoutId}` — never on the `Workout` entity, so a full-history import keeps
 * the entity cache small and the History list select stays byte-less by construction. Resolved
 * via [getStreams]; on a device that hasn't fetched the blob yet the on-demand cycle pulls the
 * sealed chunks from the relay lazily. `null` for a summary-only workout (a bare GPX /
 * Health-Connect header import) or while the bytes are not yet local.
 *
 * ⚠ A route reveals home/work patterns and HR is GDPR Article 9 special-category (threat-model
 * **A34**); on the content plane the blob rides CEK-sealed AEAD chunks — never relay-visible.
 */
val Workout.Companion.Streams: Property.Dynamic<TByteArray> get() = workoutStreams

/**
 * Content-addresses a [Workout] by its [Workout.workoutId], so re-importing the same
 * workout — including concurrently on two devices — folds to a single entity rather than
 * duplicating.
 */
interface WorkoutDataIdProvider : CompositeDataIdProvider<Workout> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/health/workout/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Workout.Fields.WORKOUT_ID
        )
    }
}

/**
 * A workout's **content identity** — the stable random id its `Streams` ciphertext chunks and
 * manifest are addressed by. The health twin of `File.ContentId`, and deliberately the same shape:
 * a `valueProperty` (a named slot), **not** a positional composite field, so adding it reshapes no
 * stored row and owes no service-version bump.
 *
 * Minted at **emit**, not at record: it names *content*, and a summary-only workout has none. Empty
 * until the streams are first emitted.
 *
 * ## ⚠⚠ Why random per emit, not derived from `workoutId`
 *
 * A derived id needs no storage at all — but it is stable across a re-emit, so re-recording a
 * workout's streams would overwrite the manifest in place and orphan the previous chunks under an id
 * that is **still declared**. Nothing could reclaim those short of a subtractive sweep. A fresh
 * random id leaves the superseded content *declared with no live edge*, which is exactly a CR-5
 * candidate (`content-plane-refcount.md` §9.5) — self-cleaning rather than swept. Same reasoning as
 * `File.ContentId`, which is an identity and never a digest.
 *
 * ## ⚠⚠ It keeps the manifest off the ordered plane, which no sweep could undo
 *
 * An id routes the manifest to the **content plane**; its absence routes it to a block on the
 * ordered metadata drain. Joiner catch-up is sequence-bound — measured at 7.5 seq/s in
 * `SyncScheduler` — so a manifest block is a cost every future device pays, permanently. That is
 * the half of this identity that reclamation cannot substitute for.
 */
private val workoutContentId: Property.Value<TString> =
    valueProperty(id = "${Workout.ID}/contentId", schema = TString.Schema)

/** See [workoutContentId]. */
val Workout.Companion.ContentId: Property.Value<TString> get() = workoutContentId
