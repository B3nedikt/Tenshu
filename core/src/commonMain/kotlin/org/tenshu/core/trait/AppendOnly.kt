package org.tenshu.core.trait

import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Marks a composite type whose realm-wide collection is **append-only**: any
 * device may add rows, but a row is immutable once written and is never removed
 * except via the parent's tombstone cascade.
 *
 * Merge under this trait is trivial — each device's local store is the union of
 * all rows it has received, ordered deterministically by their HLC timestamps.
 * The first consumer is `FileModification`
 * (`docs/proposals/file-service-history.md` §4.2).
 *
 * Phase-1 status: this is a declared marker. The merge-side *enforcement*
 * (union + HLC order in the semantic merger) lands with the history sync work
 * (Phase 2); today the trait records intent and rides on the schema.
 */
interface AppendOnly : Trait {
    companion object {
        const val ID = "tenshu/core/trait/appendOnly"
        val Schema: TraitSchema = trait(ID)
    }
}

fun DataSchema.isAppendOnly(): Boolean = hasTrait(AppendOnly.ID)
