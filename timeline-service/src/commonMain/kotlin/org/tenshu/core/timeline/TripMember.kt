package org.tenshu.core.timeline

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * One member of a [Trip]'s membership collection (`docs/proposals/timeline-services.md` §5.2) — a
 * reference to a member [Moment] by its [Moment.momentId]. A plain embedded composite (like
 * [SubjectRef]), so the membership can be a `list(of = TripMember.Schema)` the DB serialiser and the
 * `TripMerger` handle exactly as `Folder.files` handles `File` — value-lists aren't serialisable, so
 * members must be composites. Room to grow (a day-date / ordering key) with no `Trip` schema change.
 */
interface TripMember : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The member moment's [Moment.momentId]. */
    val momentId: TString
        get() = getField(Fields.MOMENT_ID)

    companion object {
        const val ID = "tenshu/timeline/tripMember"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(TripMemberDataIdProvider.Schema),
            fields = {
                string(Fields.MOMENT_ID)
            }
        )

        fun create(context: TContext, momentId: String): TripMember =
            context.create(type = ID, fields = listOf(momentId))
    }

    object Fields {
        const val MOMENT_ID = "$ID/momentId"
    }
}

val Entity<out TripMember>.momentId: TString
    get() = getFieldValue(TripMember.Fields.MOMENT_ID)

/** Content-addresses a [TripMember] by its [TripMember.momentId] (so the same moment folds to one row). */
interface TripMemberDataIdProvider : CompositeDataIdProvider<TripMember> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/tripMember/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = TripMember.Fields.MOMENT_ID
        )
    }
}
