package org.tenshu.core

import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * Describes a client-orchestrated bulk task — the Layer-2 sibling of
 * `ScheduleItem` (`core-task-composite.md` §5; `core-background-processing.md`
 * §10). Layer 3 already describes *deferred* work as data
 * (`ScheduleSpec`/`ConstraintSpec`); a `TaskSpec` describes *live* work the
 * same way: `taskService.register(spec)` creates the proxy-side task row,
 * opens its sync lane, and starts its lease; the work itself is the client
 * loop that follows under `withTask(task) { }`.
 *
 * [R] is the task's result type as a **phantom** type parameter — Kotlin-side
 * inference only (the chain-DSL `Entity<E>`/`TList<E>` precedent), NEVER a
 * schema field (the preference-service `Setting<V>` lesson: a `typeVar` value
 * field breaks `DataFactory.collectFields`). Feature modules declare small
 * typed subtypes (`FolderUploadTaskSpec : TaskSpec<FolderUploadSummary>`)
 * with `create(...)` companions; `GenericTaskSpec` (`:sync-service`) is the
 * ad-hoc escape hatch.
 *
 * The base schema is a fieldless polymorphic anchor: `RegisterTask` declares
 * its param as `composite(type = TaskSpec.Schema)` and accepts any subtype
 * (composites carry their concrete type on the wire — the `Schedule` op's
 * spec params are the precedent).
 */
@Suppress("unused") // R is deliberately phantom.
interface TaskSpec<R : Data?> : Composite {

    companion object {
        const val ID = "tenshu/core/taskSpec"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {}
        )
    }
}
