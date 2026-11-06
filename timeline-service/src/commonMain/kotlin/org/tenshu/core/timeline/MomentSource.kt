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
 * One provenance tag in a [Moment]'s cross-source `sources` set (I6, `timeline-ingestion.md` §7.1) —
 * e.g. `capture` / `google` / `import`. A plain embedded composite (like [TripMember]) so the
 * `sources` collection can be a `list(of = MomentSource.Schema)` the DB serialiser + `MomentMerger`
 * handle (value-lists aren't serialisable — list members must be composites with a `DataIdProvider`).
 * Content-addressed by [source] so the same source folds to one row and the merge is a set-union.
 */
interface MomentSource : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The provenance tag (`capture` / `google` / `import` / …). */
    val source: TString
        get() = getField(Fields.SOURCE)

    companion object {
        const val ID = "tenshu/timeline/momentSource"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(MomentSourceDataIdProvider.Schema),
            fields = {
                string(Fields.SOURCE)
            }
        )

        fun create(context: TContext, source: String): MomentSource =
            context.create(type = ID, fields = listOf(source))
    }

    object Fields {
        const val SOURCE = "$ID/source"
    }
}

val Entity<out MomentSource>.source: TString
    get() = getFieldValue(MomentSource.Fields.SOURCE)

/** Content-addresses a [MomentSource] by its [MomentSource.source] tag (so a source folds to one row). */
interface MomentSourceDataIdProvider : CompositeDataIdProvider<MomentSource> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/timeline/momentSource/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = MomentSource.Fields.SOURCE
        )
    }
}
