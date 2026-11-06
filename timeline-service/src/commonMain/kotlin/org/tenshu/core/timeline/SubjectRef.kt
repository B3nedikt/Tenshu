package org.tenshu.core.timeline

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * A [Moment]'s **subject** — the domain record it points at
 * (`docs/proposals/timeline-services.md` §5.1, **Option A** — the locked, polymorphic ref).
 *
 * One `{kind, id}` pair: [kind] says *which service* owns the subject
 * ([SubjectKind]) and [id] is that service's stable id (a `Place.placeId`, a
 * `Workout.workoutId`, …). The spine stays **closed over its subjects** — a new subject kind
 * is a new [SubjectKind] value with **no `Moment` schema change**, and the projector resolves
 * every subject through one uniform `resolveSubject(ref)` dispatch. This is deliberately loose
 * coupling by id: `:timeline-service` has **no compile dependency** on `:place-service` /
 * `:health-service`; the projector (a later slice) does the hydration.
 *
 * [SubjectKind.NONE] is the empty state (an `Event` note or a `Quiet` gap has no subject),
 * with `id == ""` — chosen over a `Maybe<SubjectRef>` for a simpler scaffold; it can graduate
 * to `Maybe` + a `TConstant` [SubjectKind] enum when the projector lands.
 */
interface SubjectRef : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Which service owns the subject — see [SubjectKind]. */
    val kind: TString
        get() = getField(Fields.KIND)

    /** The owning service's stable id for the subject; `""` when [kind] is [SubjectKind.NONE]. */
    val id: TString
        get() = getField(Fields.SUBJECT_ID)

    companion object {
        const val ID = "tenshu/timeline/subjectRef"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.KIND)
                string(Fields.SUBJECT_ID)
            }
        )

        fun create(context: TContext, kind: String, id: String): SubjectRef =
            context.create(type = ID, fields = listOf(kind, id))

        /** The empty subject — an `Event`/`Quiet` moment with no domain record. */
        fun none(context: TContext): SubjectRef = create(context, SubjectKind.NONE, "")
    }

    object Fields {
        const val KIND = "$ID/kind"
        const val SUBJECT_ID = "$ID/id"
    }
}

/**
 * The subject-owning services. Plain string values (not a `TConstant` enum) for the scaffold;
 * graduate when the projector lands. Extensible with no `Moment`/`SubjectRef` schema change —
 * the point of Option A.
 */
object SubjectKind {
    const val NONE = "none"
    const val PLACE = "place"       // → Place.placeId in :place-service
    const val WORKOUT = "workout"   // → Workout.workoutId in :health-service
    const val TRACE = "trace"       // → MovementTrace.traceId in :timeline-service (movement capture, M2)
}

val Entity<out SubjectRef>.kind: TString
    get() = getFieldValue(SubjectRef.Fields.KIND)

val Entity<out SubjectRef>.id: TString
    get() = getFieldValue(SubjectRef.Fields.SUBJECT_ID)
