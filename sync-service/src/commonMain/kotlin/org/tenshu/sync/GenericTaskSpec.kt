package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.TaskSpec
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The ad-hoc [TaskSpec] escape hatch (`core-task-composite.md` §5): a task
 * kind identified only by a caller-chosen [kindId] string, with an optional
 * expected item count. Typed per-feature subtypes (with real fields and a
 * concrete phantom result type) are the primary surface — this exists for
 * tests and one-off callers, and is the spec TS-3's own tests register.
 */
interface GenericTaskSpec : TaskSpec<Data?> {

    override val schema: CompositeSchema
        get() = Schema

    val kindId: TString
        get() = getField(Fields.KIND_ID)

    companion object {
        const val ID = "tenshu/task/genericSpec"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TaskSpec.Schema,
            fields = {
                string(Fields.KIND_ID)
            }
        )

        fun create(
            context: TContext,
            kindId: String,
        ): GenericTaskSpec = context.create(
            type = ID,
            fields = listOf(
                kindId.toTString(context),
            )
        )
    }

    object Fields {
        const val KIND_ID = "$ID/kindId"
    }
}
