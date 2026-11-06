package org.tenshu.core.trait

import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Marks a **composite** schema as orderable by the `sortBy` chain step
 * (`docs/proposals/core-chain-sort.md` §2.2).
 *
 * Composite flavour only. The primitive value types (`TInteger` — including
 * `Instant`/`UnixInstant` — `TString`, `TBoolean`) are compared directly by the
 * sort engine and do **not** carry this trait: value schemas are not
 * `CompositeSchema`, so `DataSchema.hasTrait` cannot see traits attached to them
 * (`DataSchema.kt`). That is exactly why the split exists — trait where traits
 * are visible (composites), hardcoded comparator where they are not (values).
 *
 * **Key-extraction is deferred (§6.7).** A composite is ordered by a comparable
 * key projected from it via a chain (`ChainTemplate<T, TValue>`), but that
 * resolution is wired only when the first composite-sort consumer lands. Today
 * the trait is a declared marker that the engine *consults*:
 * `SortByImplementation` reports a clear, distinct error for a composite sort
 * key depending on whether it carries this trait (resolution pending) or not
 * (declare the trait). Project the selector to a value for now.
 */
interface Comparable : Trait {
    companion object {
        const val ID = "tenshu/core/trait/comparable"
        val Schema: TraitSchema = trait(ID)
    }
}

fun DataSchema.isComparable(): Boolean = hasTrait(Comparable.ID)
