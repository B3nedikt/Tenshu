package org.tenshu.core.timeline

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
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
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.SliceKey
import org.tenshu.core.trait.Sliceable
import org.tenshu.core.trait.Syncable

/**
 * The **atom** of the Timeline model — a single timeline entry
 * (`docs/proposals/timeline-services.md` §5). Days and Trips are aggregations *over* moments;
 * the moment is the stored, synced spine.
 *
 * A moment carries its own presentational essence ([timestamp], [kind], [title], [durationSec])
 * plus a [subject] — the domain record it points at (a `Place` for a `Visit`, a `Workout` for
 * an `Activity`), modelled as the polymorphic [SubjectRef] (§5.1 Option A). Reference by id
 * keeps the services decoupled; the projector hydrates the subject.
 *
 * This scaffold carries the header + subject. Deferred a slice (§5, same reasons as place's
 * `externalRefs`): **photos + cover** are `:file-service` references (a list-of-refs → a
 * per-element DataIdProvider decision + cross-service wiring), and [isCurrent] ("● now") is a
 * derived UI flag (the moment whose range contains *now*), not stored.
 *
 * `Syncable(Eager)`, keyed by [momentId] via [MomentDataIdProvider]. `RealmData` deferred
 * (as `Notification` also skips it).
 */
interface Moment : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Our stable, self-owned id — the only primary key. */
    val momentId: TString
        get() = getField(Fields.MOMENT_ID)

    val timestamp: UnixInstant
        get() = getField(Fields.TIMESTAMP)

    /** Presentation category — `Activity` / `Visit` / `Event` / `Quiet` (drives the anatomy). */
    val kind: TString
        get() = getField(Fields.KIND)

    val title: TString
        get() = getField(Fields.TITLE)

    /** The domain record this moment points at — [SubjectKind.NONE] when it has none. */
    val subject: SubjectRef
        get() = getField(Fields.SUBJECT)

    /** Duration in seconds; `0` when not applicable. */
    val durationSec: TInteger
        get() = getField(Fields.DURATION_SEC)

    /**
     * Cross-source provenance (I6, §7.1) — the set of sources that saw this real-world moment
     * (`capture` / `google` / …). This is the **mergeable collection**: a `MomentMerger` unions it
     * across devices under `ConflictStrategy.Merge`, so a canonical moment accumulates every source's
     * confirmation. Empty for a moment recorded before reconciliation.
     */
    val sources: TList<MomentSource>
        get() = getField(Fields.SOURCES)

    /**
     * When this moment has been folded into a canonical by cross-source reconciliation (§7.1), the
     * canonical's key; `""` when this *is* a live/canonical moment. The projector hides moments whose
     * `supersededBy` is set — superseded, **not** tombstoned, so removing a source can re-expose them.
     */
    val supersededBy: TString
        get() = getField(Fields.SUPERSEDED_BY)

    companion object {
        const val ID = "tenshu/timeline/moment"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                MomentDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
                // C9 (`sync-device-catch-up.md` §5.7.3): a realm-wide singleton relation with no
                // sub-key inside it, so the partition is Timeline's to choose — by calendar day,
                // because the feed is chronological and its first screen is "today".
                Sliceable(SliceKey.Day),
            ),
            fields = {
                string(Fields.MOMENT_ID)
                timestamp(Fields.TIMESTAMP)
                string(Fields.KIND)
                string(Fields.TITLE)
                composite(id = Fields.SUBJECT, type = SubjectRef.Schema)
                int(Fields.DURATION_SEC)
                // Cross-source provenance + supersession (I6, §7.1) — the mergeable set + hide flag.
                // ⚠ appended at the end: adding fields to a synced positional composite is
                // peer-breaking, ship lockstep.
                list(id = Fields.SOURCES, of = MomentSource.Schema)
                string(Fields.SUPERSEDED_BY)
                // photos + cover (file refs) — next slice, see class KDoc
            }
        )

        fun create(
            context: TContext,
            momentId: String,
            timestamp: UnixInstant,
            kind: String,
            title: String,
            subject: SubjectRef,
            durationSec: Long = 0,
            sources: List<String> = emptyList(),
            supersededBy: String = "",
        ): Moment = context.create(
            type = ID,
            fields = listOf(
                momentId,
                timestamp,
                kind,
                title,
                subject,
                durationSec.toTInteger(context),
                sources.map { MomentSource.create(context, it) },
                supersededBy,
            )
        )
    }

    object Fields {
        const val MOMENT_ID = "$ID/momentId"
        const val TIMESTAMP = "$ID/timestamp"
        const val KIND = "$ID/kind"
        const val TITLE = "$ID/title"
        const val SUBJECT = "$ID/subject"
        const val DURATION_SEC = "$ID/durationSec"
        const val SOURCES = "$ID/sources"
        const val SUPERSEDED_BY = "$ID/supersededBy"
    }

    /** The presentation categories (`MomentKind` in the app model). Plain strings here. */
    object MomentKind {
        const val ACTIVITY = "Activity"
        const val VISIT = "Visit"
        const val EVENT = "Event"
        const val QUIET = "Quiet"

        /**
         * A captured movement segment — a walk/run/ride/drive between dwells (movement capture, M2).
         * Its subject is a [MovementTrace] ([SubjectKind.TRACE]). A plain new string value → no
         * `Moment` wire change; old peers fall back to the generic `Event` anatomy until they learn it.
         */
        const val MOVEMENT = "Movement"
    }
}

val Entity<out Moment>.momentId: TString
    get() = getFieldValue(Moment.Fields.MOMENT_ID)

/** Content-addresses a [Moment] by [Moment.momentId] so a re-record folds to one row. */
interface MomentDataIdProvider : CompositeDataIdProvider<Moment> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/moment/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Moment.Fields.MOMENT_ID
        )
    }
}
