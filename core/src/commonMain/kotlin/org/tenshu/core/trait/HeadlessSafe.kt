package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Phase 5 / Layer 3 (docs/proposals/core-background-processing.md §3.2(a),
 * §6.4): a param-less eligibility marker declaring that an operation is safe
 * to dispatch with **no user present** — no foreground UI, possibly a cold
 * proxy boot triggered by the OS scheduler. No SAS confirmation, no device
 * picker, no recovery-phrase entry, no "tap approve."
 *
 * It carries **no** schedule/constraint data — that is per-invocation runtime
 * data owned by `ScheduleService` / a persisted `ScheduleItem`, not op-type
 * schema metadata. `HeadlessSafe` answers only *whether* an op may run
 * head-less; `ScheduleService.schedule(...)` carries *when/under what
 * constraints*.
 *
 * **Fail-closed by construction (§6.4).** Layer 3 must never trigger a
 * recovery / pairing / UI-required flow. A runtime denylist fails *open* — a
 * new UI-requiring op added later is schedulable until someone remembers to
 * deny it. `HeadlessSafe` is an allowlist: an op is unschedulable *unless*
 * its author opted in by adding this trait, so the complete Layer-3 attack
 * surface is one grep (`isHeadlessSafe`). `RecoveryClaim` /
 * `ApprovePairingRequest` / `AwaitPairing` simply never carry it.
 *
 * **Orthogonal to [BackgroundOperation]** (§3.2): the latter is *how* an op
 * runs when invoked (returns a `TaskHandle`, cancellable in-proxy — Layer 2);
 * `HeadlessSafe` is *whether* it may be invoked with no user/UI. A deferred
 * upload carries both, but they answer different questions and neither
 * carries schedule data. Carrying `HeadlessSafe` is **not** predicated on
 * carrying `BackgroundOperation`.
 */
interface HeadlessSafe : Trait {
    companion object {
        const val ID = "tenshu/core/operation/headlessSafe"
        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isHeadlessSafe(): Boolean =
    schema.isHeadlessSafe()

fun OperationSchema.isHeadlessSafe(): Boolean =
    hasTrait(HeadlessSafe.ID)
