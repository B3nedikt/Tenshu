package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Marker trait for operations that need only a [TContext] + params to execute, *not*
 * the full `ExecutionContext` (no Database, no streams, no downstream operation
 * dispatch). Used to dispatch via `PureOperationRunner` from low-level components
 * (`Database`, list utilities, schema introspection) that don't have an
 * `ExecutionContext`.
 *
 * **"Pure" here is *not* the FP "referentially transparent" reading.** A
 * `PureOperation` may use RNG (e.g. `Bip39.generateMnemonic()`), read the system
 * clock, or otherwise be non-deterministic. The trait classifies *which runtime
 * context an op needs*, not behavioural purity. If we ever add a schema-level
 * type-check pass that wants the FP-pure meaning, that's a separate trait
 * (`SideEffectFreeOperation` or similar); the rename of this one is tracked in
 * `docs/tech-debt.md` §"`PureOperation` Trait Naming — KDoc Clarification".
 *
 * @see PureOperationImplementation
 * @see org.tenshu.core.PureOperationRunner
 */
interface PureOperation : Trait {

    companion object {
        const val ID = "tenshu/core/operation/pure"

        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isPure(): Boolean =
    schema.isPure()

fun OperationSchema.isPure(): Boolean =
    hasTrait(PureOperation.ID)
