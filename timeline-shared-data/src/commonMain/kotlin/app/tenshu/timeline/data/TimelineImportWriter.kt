package app.tenshu.timeline.data

import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.orThrow
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.core.TContext
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.Place
import org.tenshu.core.place.recordPlace
import kotlinx.coroutines.flow.first
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.Trip
import org.tenshu.core.timeline.recordMoment
import org.tenshu.core.timeline.recordMovementTrace
import org.tenshu.core.timeline.recordTrip
import org.tenshu.health.client.healthService
import org.tenshu.place.client.placeService
import org.tenshu.timeline.client.timelineService

/**
 * The **write side of the Timeline ingestion seam** (`docs/proposals/timeline-ingestion.md` §2) —
 * maps the neutral [ImportedTimeline] a source acquired onto the `:place-service` /
 * `:health-service` / `:timeline-service` schemas and persists each entity via its record op
 * (`recordPlace` / `recordWorkout` / `recordMoment` / `recordTrip`). Shared across every source
 * (the in-process [ReferenceTimelineSource], the Google-Timeline file parser (I2), the live
 * location capture (I3)) — only *acquisition* is source-specific.
 *
 * Subjects (places, workouts) are written before moments that reference them; content-addressing
 * makes order immaterial for correctness but keeps the intent readable.
 *
 * Idempotent: every record op is content-addressed (by `placeId` / `workoutId` / `momentId` /
 * `tripId`), so re-importing the same source — or re-capturing an already-stored visit — folds to
 * one row rather than duplicating. (Cross-*source* near-duplicates are a separate concern — the
 * I6 reconciliation pass, §7.1.)
 */
class TimelineImportWriter(
    private val client: TenshuClient,
    private val context: TContext,
    /**
     * ADR 0025 §8.6 write boundary: runs before EACH record op (place, workout, movement trace,
     * moment, trip) and throws to refuse it. ⚠ Required, never defaulted.
     */
    private val beforeEachWrite: suspend () -> Unit,
) {
    /**
     * Persists every entity, **throwing on the first failed op** (⚠ the record extensions
     * return a [TResult] — a not-connected / realm-less proxy yields `TResult.Failure`, NOT an
     * exception, and discarding it was the original "silent visit loss": the caller believed
     * the write landed). Throwing makes the failure visible to `CapturedVisitWriter`'s
     * outbox-retention and to the import surfaces' error dialogs.
     */
    suspend fun write(source: ImportedTimeline) {
        // 4-C (timeline-recognition-and-place-naming.md §4.4): `recordPlace` replaces the
        // placeId-keyed row, so a blank-labelled capture — including a Wi-Fi-recognised re-visit
        // that deliberately folds onto a *named* place (§4.5) — would otherwise clobber the name.
        // Read existing rows once and never erase or downgrade a name on the write path.
        val existing: Map<String, ExistingPlace> =
            if (source.places.isEmpty()) emptyMap() else readExistingPlaces()
        // F1 (§8.4): the same read also settles *identity* — a newly-minted place that sits within
        // the accuracy-scaled radius of one we already know folds onto it, instead of becoming a
        // second row for the same venue because its centroid crossed a grid-cell boundary.
        val imported = PlaceIdentityResolver.resolve(
            source,
            existing.map { (id, ex) ->
                PlaceIdentityResolver.KnownPlace(id, ex.latE7, ex.lngE7, ex.accuracyM)
            },
        )
        imported.places.forEach { p ->
            val ex = existing[p.placeId]
            val exLabel = ex?.label.orEmpty()
            val exSource = ex?.labelSource ?: LabelSource.GEOCODED
            // A user label is authoritative (protect against blank captures and re-imports alike);
            // and a blank capture must never overwrite an existing non-blank (geocoded) name.
            val wouldErase = p.label.isBlank() && exLabel.isNotBlank()
            val wouldOverrideUser = LabelSource.isUser(exSource) && exLabel.isNotBlank()
            // ⚠⚠ §32.16: this used to `return@forEach` — skipping the **whole row**, not just the name.
            // A place is therefore frozen at the accuracy of the *first* visit that ever created it,
            // because every later visit that could correct it is a blank capture onto a named place and
            // was dropped right here. Measured: a bar row was minted claiming ±14 m under the
            // pre-§32.10 rule; the same dwell re-measured at **128 m** three days running and the row
            // never moved, so `PlaceLabelEnricher` kept reading 14, kept taking the precise-venue
            // branch, and kept re-deriving the wrong venue name for Odeonsplatz.
            //
            // ⚠ The guard was right about what it protected and wrong about its blast radius: a name is
            // a claim the user may have made, an accuracy is a measurement nobody authored. Keep the
            // former, always refresh the latter.
            val keepExistingLabel = wouldErase || wouldOverrideUser
            // Non-null exactly when the row already exists AND we are keeping its identity — so the
            // elvis fallbacks below read as "a genuinely new place", not as a null-safety shrug.
            val kept = ex?.takeIf { keepExistingLabel }
            // A blank capture is machine-provenanced (enricher-fillable); an explicit imported
            // name is treated as user-authored so a later lookup never overwrites it.
            val labelSource = when {
                keepExistingLabel -> exSource
                p.label.isBlank() -> LabelSource.GEOCODED
                else -> LabelSource.USER
            }
            beforeEachWrite()
            client.placeService.recordPlace(
                Place.create(
                    context = context,
                    placeId = p.placeId,
                    // ⚠ A re-visit that is only here to refresh the accuracy must not restate the
                    // place's position or its first-seen time — it carries no new claim about either,
                    // and moving the pin on every passing visit is a change nobody asked for.
                    latE7 = kept?.latE7 ?: p.latE7,
                    lngE7 = kept?.lngE7 ?: p.lngE7,
                    capturedAt = kept?.capturedAt ?: UnixInstantDto(p.capturedAtMs),
                    label = kept?.label ?: p.label,
                    // Never blank structured fields a prior geocode already stored for this row.
                    city = ex?.city.orEmpty(),
                    countryCode = ex?.countryCode.orEmpty(),
                    labelSource = labelSource,
                    // Prefer a real capture accuracy; don't let a re-import (0 = unknown) wipe it.
                    accuracyM = if (p.accuracyM > 0) p.accuracyM else (ex?.accuracyM ?: 0),
                ),
            ).orThrow("recording place ${p.placeId}")
        }
        imported.workouts.forEach { w ->
            beforeEachWrite()
            client.healthService.recordWorkout(
                Workout.create(
                    context = context,
                    workoutId = w.workoutId,
                    activityType = w.activityType,
                    source = w.source,
                    start = UnixInstantDto(w.startMs),
                    end = UnixInstantDto(w.endMs),
                    durationSec = w.durationSec,
                ),
            ).orThrow("recording workout ${w.workoutId}")
        }
        imported.traces.forEach { t ->
            beforeEachWrite()
            client.timelineService.recordMovementTrace(
                MovementTrace.create(
                    context = context,
                    traceId = t.traceId,
                    mode = t.mode,
                    classifierVersion = t.classifierVersion,
                    modeConfidence = t.modeConfidence,
                    sourceActivityType = t.sourceActivityType,
                    medianSpeedMmPerS = t.medianSpeedMmPerS,
                    distanceM = t.distanceM,
                    startAt = UnixInstantDto(t.startMs),
                    endAt = UnixInstantDto(t.endMs),
                    pointCount = t.pointCount,
                    rawPointCount = t.rawPointCount,
                    minLatE7 = t.minLatE7,
                    minLngE7 = t.minLngE7,
                    maxLatE7 = t.maxLatE7,
                    maxLngE7 = t.maxLngE7,
                    startLatE7 = t.startLatE7,
                    startLngE7 = t.startLngE7,
                    endLatE7 = t.endLatE7,
                    endLngE7 = t.endLngE7,
                    polyline = t.polyline,
                    steps = t.steps,
                ),
            ).orThrow("recording movement trace ${t.traceId}")
        }
        imported.moments.forEach { m ->
            val subject = if (m.subjectKind == SubjectKind.NONE || m.subjectId.isEmpty()) {
                SubjectRef.none(context)
            } else {
                SubjectRef.create(context, m.subjectKind, m.subjectId)
            }
            beforeEachWrite()
            client.timelineService.recordMoment(
                Moment.create(
                    context = context,
                    momentId = m.momentId,
                    timestamp = UnixInstantDto(m.timestampMs),
                    kind = m.kind,
                    title = m.title,
                    subject = subject,
                    durationSec = m.durationSec,
                    sources = m.sources,
                ),
            ).orThrow("recording moment ${m.momentId}")
        }
        imported.trips.forEach { t ->
            beforeEachWrite()
            client.timelineService.recordTrip(
                Trip.create(
                    context = context,
                    tripId = t.tripId,
                    title = t.title,
                    dateRange = t.dateRange,
                    year = t.year,
                ),
            ).orThrow("recording trip ${t.tripId}")
        }
    }

    /**
     * The label-provenance fields of every stored place, keyed by id — the clobber guard's input,
     * and (with the coordinates) the F1 identity resolver's too.
     */
    private data class ExistingPlace(
        val label: String,
        val labelSource: String,
        val city: String,
        val countryCode: String,
        val accuracyM: Int,
        val latE7: Long,
        val lngE7: Long,
        /** ⚠ Read so a re-visit refreshing the accuracy cannot restate "when we first saw this". */
        val capturedAt: UnixInstant,
    )

    private suspend fun readExistingPlaces(): Map<String, ExistingPlace> =
        client.placeService.places
            .select {
                fields(
                    Place.Fields.PLACE_ID, Place.Fields.LABEL, Place.Fields.LABEL_SOURCE,
                    Place.Fields.CITY, Place.Fields.COUNTRY_CODE, Place.Fields.ACCURACY_M,
                    Place.Fields.LAT_E7, Place.Fields.LNG_E7, Place.Fields.CAPTURED_AT,
                )
            }
            .flow().first()
            .fold(
                onSuccess = { list ->
                    list.toList().associate { e ->
                        e.getFieldValue<TString>(Place.Fields.PLACE_ID).get() to ExistingPlace(
                            label = e.getFieldValue<TString>(Place.Fields.LABEL).get(),
                            labelSource = e.getFieldValue<TString>(Place.Fields.LABEL_SOURCE).get(),
                            city = e.getFieldValue<TString>(Place.Fields.CITY).get(),
                            countryCode = e.getFieldValue<TString>(Place.Fields.COUNTRY_CODE).get(),
                            accuracyM = e.getFieldValue<TInteger>(Place.Fields.ACCURACY_M).get().toInt(),
                            latE7 = e.getFieldValue<TInteger>(Place.Fields.LAT_E7).get(),
                            lngE7 = e.getFieldValue<TInteger>(Place.Fields.LNG_E7).get(),
                            capturedAt = e.getFieldValue(Place.Fields.CAPTURED_AT),
                        )
                    }
                },
                onFailure = { emptyMap() },
            )
}
