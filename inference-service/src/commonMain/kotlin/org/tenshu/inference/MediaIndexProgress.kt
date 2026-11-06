package org.tenshu.inference

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.toTBoolean

/**
 * One tick of this device's media index pass (IN-D6, the 1e-3 slice).
 *
 * ⚠⚠ **[indexing] is a field rather than an inference from [total], and that is deliberate.** A pass
 * over a library with no images really does start with `total = 0` — `MediaIndexer` is handed
 * `media.size` — so "no pass is running" and "a pass over an empty library" would be the same tick
 * with opposite meanings. The screen has to say one of *"Not indexing"* or *"Indexing 0 of 0"*, and
 * guessing which from a zero is how the two get confused.
 *
 * ⚠ [done] counts images **visited**, including the ones skipped as already current: *"how far
 * through my library are you"* is the question, and a count that only moved on newly-indexed
 * photographs would sit still for an entire incremental pass and read as stuck.
 *
 * ⚠ Device-local and never synced (IN-D4/D5), like [ModelDownloadProgress]: which device is part-way
 * through indexing is device state, and pushing it to the user's other devices would describe work
 * none of them is doing.
 */
interface MediaIndexProgress : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Whether a pass is running **right now** on this device. */
    val indexing: TBoolean
        get() = getField(Fields.INDEXING)

    /** Images visited so far — ⚠ including skips. Meaningless unless [indexing]. */
    val done: TInteger
        get() = getField(Fields.DONE)

    /** ⚠ The library handed to the pass, not the count that will be newly indexed. */
    val total: TInteger
        get() = getField(Fields.TOTAL)

    companion object {
        const val ID = "tenshu/inference/mediaIndexProgress"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                boolean(Fields.INDEXING)
                int(Fields.DONE)
                int(Fields.TOTAL)
            },
        )

        fun create(
            context: TContext,
            indexing: Boolean,
            done: Int,
            total: Int,
        ): MediaIndexProgress = context.create(
            type = ID,
            fields = listOf(
                indexing.toTBoolean(context),
                done.toLong().toTInteger(context),
                total.toLong().toTInteger(context),
            ),
        )

        /** "Nothing is running here" — the terminal tick of [IndexProgress]. */
        fun idle(context: TContext): MediaIndexProgress =
            create(context, indexing = false, done = 0, total = 0)
    }

    object Fields {
        const val INDEXING = "$ID/indexing"
        const val DONE = "$ID/done"
        const val TOTAL = "$ID/total"
    }
}
