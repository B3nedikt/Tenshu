package org.tenshu.core.place

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A canonical, **provider-neutral** point of interest
 * (`docs/proposals/timeline-services.md` §3.2). The storage substrate behind the
 * Timeline app's `Place` presentation model and the `Visit`-kind moment's subject.
 *
 * The identity is entirely our own: [placeId] is the only primary key. The exact
 * coordinate is always kept ([latE7]/[lngE7]); external-provider ids live as optional
 * [xrefs] cross-references, never as keys (§3.1 — open-data ids storable freely;
 * Google/Apple ids storable only as content-less foreign keys). This keeps the record
 * something we can hold in an E2E store the operator cannot inspect.
 *
 * **Coordinates are E7 integers** — degrees × 1e7 (the standard GPS-storage idiom;
 * ~1 cm resolution, box-queryable, exact on the wire). The DSL has no float/double
 * field kind, and E7 is the right long-term choice anyway. Convert at the edge:
 * `latE7 / 1e7`.
 *
 * `Syncable(Eager)` so a place recorded on one device converges to all paired devices,
 * keyed by [placeId] via [PlaceDataIdProvider] so re-recording the same place folds to
 * one row. TODO(timeline-services §3): opt into `RealmData` (+ a `CREATED_AT`
 * fulfilment) once Place should surface in Control's Data section — deferred, exactly
 * as `Notification` (Syncable, not RealmData) is today.
 */
interface Place : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Our stable, self-owned id — the only primary key. */
    val placeId: TString
        get() = getField(Fields.PLACE_ID)

    /** Latitude in E7 (degrees × 1e7). */
    val latE7: TInteger
        get() = getField(Fields.LAT_E7)

    /** Longitude in E7 (degrees × 1e7). */
    val lngE7: TInteger
        get() = getField(Fields.LNG_E7)

    /** When this place was first captured. */
    val capturedAt: UnixInstant
        get() = getField(Fields.CAPTURED_AT)

    /** User-authored name/note. Empty string when unset (no geocode/label yet). */
    val label: TString
        get() = getField(Fields.LABEL)

    /** Locality from geocoding (place-geocoding.md G4). Empty string when unset. */
    val city: TString
        get() = getField(Fields.CITY)

    /** ISO country code from geocoding (place-geocoding.md G4). Empty string when unset. */
    val countryCode: TString
        get() = getField(Fields.COUNTRY_CODE)

    /**
     * Provenance of [label] — [LabelSource.GEOCODED] (or blank/unset) vs [LabelSource.USER]
     * (timeline-recognition-and-place-naming.md §4.4 / 4-C). A **user** label is authored by a
     * rename / the place picker and is *never* overwritten; a **geocoded** label may be improved
     * by a later, better lookup, so query refinements (4-A) reach places already in the store
     * instead of only new captures. Also load-bearing on the write path: a blank capture must
     * never clobber a non-blank name (see `TimelineImportWriter`).
     */
    val labelSource: TString
        get() = getField(Fields.LABEL_SOURCE)

    /**
     * Representative capture accuracy in metres (`0` = unknown / imported) —
     * timeline-recognition-and-place-naming.md §4.3 / 4-B. A wide-accuracy place is named by its
     * area/city rather than a confidently-wrong POI (the naming consumer coarsens on this).
     */
    val accuracyM: TInteger
        get() = getField(Fields.ACCURACY_M)

    // TODO(timeline-services §3.2): store [ExternalPlaceRef] cross-references
    //  (`externalRefs`). Deferred a slice: an embedded list-of-composite is persisted as
    //  array elements, each of which needs a DataIdProvider (like `File` in `Folder.files`)
    //  — so ref storage waits on a ref-identity decision (key by `provider`? by `(provider,
    //  externalId)`? global vs per-place). The `ExternalPlaceRef` type + its Dto/registration
    //  already exist for that slice.

    companion object {
        const val ID = "tenshu/place/place"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // ⚠⚠ ConflictStrategy.Merge (§9.2, P4d) — NOT the default last-writer-wins. Under LWW a
            // peer that captured a visit before it pulled your rename wrote a legitimately blank
            // label, and that newer whole row REPLACED the renamed one on every device. The guard
            // that prevents this locally (`TimelineImportWriter`, §4.4) reads the local store and so
            // cannot see the conflict at all; the authority that can is `PlaceMerger`.
            traits = listOf(
                PlaceDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
            ),
            fields = {
                string(Fields.PLACE_ID)
                int(Fields.LAT_E7)
                int(Fields.LNG_E7)
                timestamp(Fields.CAPTURED_AT)
                string(Fields.LABEL)
                // G4 (place-geocoding.md): appended — positional composites make added fields
                // peer-breaking on the wire; pre-release lockstep ship, local rows only-grow.
                string(Fields.CITY)
                string(Fields.COUNTRY_CODE)
                // 4-C (timeline-recognition-and-place-naming.md §4.4): appended — positional
                // composites make added fields peer-breaking on the wire, so this rides an
                // HR #9 TENSHU_STORAGE_FORMAT_VERSION lockstep bump (pre-release, local-only rows).
                string(Fields.LABEL_SOURCE)
                // 4-B (§4.3): representative capture accuracy, appended in the same v9 bump.
                int(Fields.ACCURACY_M)
            }
        )

        fun create(
            context: TContext,
            placeId: String,
            latE7: Long,
            lngE7: Long,
            capturedAt: UnixInstant,
            label: String = "",
            city: String = "",
            countryCode: String = "",
            labelSource: String = LabelSource.GEOCODED,
            accuracyM: Int = 0,
        ): Place = context.create(
            type = ID,
            fields = listOf(
                placeId,
                latE7.toTInteger(context),
                lngE7.toTInteger(context),
                capturedAt,
                label,
                city,
                countryCode,
                labelSource,
                accuracyM.toTInteger(context),
            )
        )

    }

    object Fields {
        const val PLACE_ID = "$ID/placeId"
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
        const val CAPTURED_AT = "$ID/capturedAt"
        const val LABEL = "$ID/label"
        const val CITY = "$ID/city"
        const val COUNTRY_CODE = "$ID/countryCode"
        const val LABEL_SOURCE = "$ID/labelSource"
        const val ACCURACY_M = "$ID/accuracyM"
    }
}

/**
 * The two provenances of [Place.label] (timeline-recognition-and-place-naming.md §4.4). Stored as a
 * plain string (the schema DSL has no enum field kind); an unrecognised/empty value is treated as
 * [GEOCODED] — the improvable, never-protected default, so a stale value can never wrongly pin a
 * label the enricher should be free to refine.
 */
object LabelSource {
    /** Machine-derived (reverse geocode) or not-yet-named — the enricher may improve it. */
    const val GEOCODED = "geocoded"

    /** Authored by the user (rename / place picker) — never overwritten by any lookup. */
    const val USER = "user"

    /** True when [source] denotes a user-authored label (anything not exactly [USER] is not). */
    fun isUser(source: String): Boolean = source == USER
}

/**
 * Projected-row accessors (`photos-app.md` PH-3 — a `select { fields(...) }` chain yields
 * `Entity<Place>`, not `Place`, so a reader needs these rather than the interface's own getters).
 *
 * ## ⚠⚠ Every one of them is nullable, and the schema is not why
 *
 * The schema declares all of these **non-optional**, so by the usual rule
 * (`Entity.getFieldValue` for a required field, [Entity.getOptionalFieldValue] for an optional one)
 * they would be non-null. `Place` is the standing exception, for two reasons the schema cannot
 * express:
 *
 *  - ⚠⚠ **The writer is a different app.** Timeline writes these rows; Photos and Maps read them.
 *    A row is whatever the Timeline that wrote it knew how to write, and nothing keeps two apps on
 *    one device at the same version — so a field this build requires may simply be absent. Several
 *    of these were *appended* to a positional composite (`CITY`, `COUNTRY_CODE`, `LABEL_SOURCE`),
 *    which is exactly the shape that leaves older rows short.
 *  - ⚠ **A projection is a subset.** `select { fields(LABEL) }` yields a row carrying `LABEL` and
 *    nothing else, so reading `.city` off it is absent-by-projection — within one app's control,
 *    and just as fatal through a non-null read.
 *
 * `getFieldValue` would be wrong for both: its unchecked cast puts null in a non-null slot, an NPE on
 * the JVM and a `SIGSEGV` on Kotlin/Native that **no `catch` can intercept** (measured). The
 * nullability is what makes the cast legal; what to do about each absent field is a per-field
 * judgement for the reader — `photoPlaceOrNull` is the worked example.
 */
val Entity<out Place>.placeId: TString?
    get() = getOptionalFieldValue(Place.Fields.PLACE_ID)

val Entity<out Place>.labelSource: TString?
    get() = getOptionalFieldValue(Place.Fields.LABEL_SOURCE)

val Entity<out Place>.label: TString?
    get() = getOptionalFieldValue(Place.Fields.LABEL)

val Entity<out Place>.city: TString?
    get() = getOptionalFieldValue(Place.Fields.CITY)

val Entity<out Place>.countryCode: TString?
    get() = getOptionalFieldValue(Place.Fields.COUNTRY_CODE)

val Entity<out Place>.latE7: TInteger?
    get() = getOptionalFieldValue(Place.Fields.LAT_E7)

val Entity<out Place>.lngE7: TInteger?
    get() = getOptionalFieldValue(Place.Fields.LNG_E7)

/**
 * Content-addresses a [Place] by its [Place.placeId], so re-recording the same place —
 * including concurrently on two devices — folds to a single entity rather than the
 * last-writer-wins clobber a single blob would suffer.
 */
interface PlaceDataIdProvider : CompositeDataIdProvider<Place> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/place/place/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Place.Fields.PLACE_ID
        )
    }
}
