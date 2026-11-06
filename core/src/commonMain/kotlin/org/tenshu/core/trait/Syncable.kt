package org.tenshu.core.trait

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TConstant
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.constant
import org.tenshu.core.schema.trait
import org.tenshu.core.toTString

/**
 * Strategy for when data should be synchronized.
 */
sealed interface SyncStrategy : TConstant {

    /**
     * Data is synchronized immediately when available.
     */
    object Eager : SyncStrategy {
        override val schema: ConstantSchema = constant("${ID}/eager", Schema)
    }

    /**
     * Data is synchronized only when something asks for it — it does **not** ride the
     * entity's block to paired devices. The bytes live on the content plane and a device
     * fetches them the first time it needs them.
     *
     * Used for payloads too large to push to every device unconditionally: `File.Data`
     * (a file's own bytes), `Workout.Streams`, and both `File.Thumbnail` classes.
     *
     * ## ⚠⚠ Reading one: WHICH TERMINAL you use decides whether it is fetched at all
     *
     * This is the part that is repeatedly rediscovered the hard way, so it is written
     * here rather than at the call sites that trip over it:
     *
     * - **`Maybe.get()` / streaming terminals materialise it.** Projecting an OnDemand
     *   property in a `select { relations(...) }` writes a pending marker, and *these*
     *   terminals resolve that marker — issuing the fetch and awaiting it.
     * - ⚠ **`getAll()` does NOT.** It leaves the marker unresolved, so the value reads as
     *   absent and nothing is ever fetched. A paged listing loads its window via
     *   `getAll()`, which is why a paging projection cannot carry an OnDemand property —
     *   the row would show a permanent null rather than a slow value. Fit's detail read
     *   moved to `search → select → get()` for exactly this reason.
     * - ⚠⚠ **A proxy-side resolver cannot fetch one.** Dynamic properties resolve through
     *   `ExecutionContext.resolveDynamicProperty`, which is **not** `suspend`, so an
     *   operation implementation that reads an OnDemand property sees only what is already
     *   local — no trait on the op changes this. A resolver that needs one must therefore
     *   treat absence as "not here yet" and let a *client* projection do the fetching.
     *
     * The practical consequence for a consumer: if you need an OnDemand property's bytes,
     * project **that property** through a materialising terminal. Reading something derived
     * from it (e.g. `File.Preview`, which falls back to a stored thumbnail) does not fetch
     * it, because the derivation runs proxy-side.
     *
     * ## Writing one
     *
     * The bytes go to the content plane, not onto the entity: an `ExternalPropertyWriter`
     * maps the property id to an external path and a content id, and the emit ships sealed
     * chunks plus a manifest. ⚠ Two OnDemand properties on the *same* entity need
     * **distinct content ids** — the manifest's relay address derives from the content id
     * alone, so sharing one makes the second property overwrite the first's manifest.
     *
     * @see org.tenshu.core.relation.deferred for the orthogonal axis — deferral controls
     *   *when* a property resolves, this controls *whether the bytes are here at all*.
     */
    object OnDemand : SyncStrategy {
        override val schema: ConstantSchema = constant("${ID}/onDemand", Schema)
    }

    companion object {
        const val ID = "tenshu/core/sync/strategy"
        val Schema: ConstantSchema = constant(ID)
    }
}

/**
 * Strategy for resolving conflicts during synchronization.
 */
sealed interface ConflictStrategy : TConstant {

    /**
     * The last update (based on HLC timestamp) wins.
     */
    object LastWriterWins : ConflictStrategy {
        override val schema: ConstantSchema = constant("${ID}/lastWriterWins", Schema)
    }

    /**
     * Semantic merging of data (e.g. for folders).
     */
    object Merge : ConflictStrategy {
        override val schema: ConstantSchema = constant("${ID}/merge", Schema)
    }

    /**
     * Manual conflict resolution by the user.
     */
    object Manual : ConflictStrategy {
        override val schema: ConstantSchema = constant("${ID}/manual", Schema)
    }

    companion object {
        const val ID = "tenshu/core/sync/conflictStrategy"
        val Schema: ConstantSchema = constant(ID)
    }
}

/**
 * Trait that marks a data schema as syncable.
 */
interface Syncable : Trait {

    val strategy: SyncStrategy
        get() = getField(Fields.STRATEGY)

    val conflictStrategy: ConflictStrategy
        get() = getField(Fields.CONFLICT_STRATEGY)

    companion object {
        const val ID = "tenshu/core/trait/syncable"

        val Schema: TraitSchema = trait(
            id = ID,
            fields = {
                composite(Fields.STRATEGY, type = SyncStrategy.Schema)
                composite(Fields.CONFLICT_STRATEGY, type = ConflictStrategy.Schema)
            }
        )
    }

    object Fields {
        const val STRATEGY = "${ID}/strategy"
        const val CONFLICT_STRATEGY = "${ID}/conflictStrategy"
    }
}

/**
 * DSL function to create a [Syncable] trait requirement.
 */
fun Syncable(
    strategy: SyncStrategy = SyncStrategy.Eager,
    conflictStrategy: ConflictStrategy = ConflictStrategy.LastWriterWins
): TraitSchema = object : TraitSchema, Composite {
    override val id: TString get() = Syncable.ID.toTString(null)
    override val parent: DataSchema get() = Trait.Schema
    override val traitRequirements: TList<TraitRequirementSchema> = SimpleTList(emptyList())

    override val schema: CompositeSchema get() = Syncable.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getField(reference: String): T =
        getOptionalField(reference) ?: error("Field $reference not found")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
        Syncable.Fields.STRATEGY -> strategy as T
        Syncable.Fields.CONFLICT_STRATEGY -> conflictStrategy as T
        DataSchema.Fields.SCHEMA_ID -> id as T
        DataSchema.Fields.PARENT -> parent as T
        Trait.TRAIT_REQUIREMENTS -> traitRequirements as T
        else -> null
    }
}
