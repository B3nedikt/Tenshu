package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * A param-less marker declaring that an operation must run **at an instant**,
 * not merely eventually (`routines-engine.md` RE-D5).
 *
 * The scheduling seam otherwise expresses only *cadence + constraints*, which is
 * right for maintenance ("drain sync every 15 minutes, when charging") and wrong
 * for a routine: **"turn the porch light on at sunset" has no useful deferred
 * form.** A platform scheduler that batches this into its next convenient
 * wake-up has not been slightly late, it has failed.
 *
 * ⚖️ **Deviation from RE-D5's letter, and why.** The doc proposed a `punctual`
 * boolean on `ScheduleSpec`. A trait is strictly better here: `ScheduleSpec` is
 * a stored composite, so a new field is a positional store-shape change owing a
 * `TENSHU_STORAGE_FORMAT_VERSION` bump and a lockstep rebuild of every app —
 * for information that never varies per invocation. Punctuality is a property
 * of *what the operation is*, not of a particular request to run it, and traits
 * are schema metadata that is **never serialised** (the `HeadlessSafe` /
 * `@defer` precedent). Same enforcement, no format cost.
 *
 * ⚠ **Advisory, like the rest of the `PlatformScheduler` contract.** A platform
 * that cannot promise an instant does not fail — it does the best it has, and
 * the honest consequence is stated where users can see it: on a phone with no
 * exact-alarm permission a time routine may be minutes late, and with no
 * always-on device in the household that is simply what "at sunset" means.
 * `routines-engine.md` §5 is the degradation table.
 *
 * ⚠ Orthogonal to [HeadlessSafe]: that answers *whether* an op may run with no
 * user present, this answers *how precisely* it must be woken. A routine's
 * `EvaluateDueRoutines` carries both; a maintenance drain carries only the first.
 */
interface Punctual : Trait {
    companion object {
        const val ID = "tenshu/core/operation/punctual"
        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isPunctual(): Boolean =
    schema.isPunctual()

fun OperationSchema.isPunctual(): Boolean =
    hasTrait(Punctual.ID)
