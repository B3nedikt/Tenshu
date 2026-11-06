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
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait
import org.tenshu.core.toTString

/**
 * How a type's realm-wide collection is cut into **slices** for the joiner snapshot
 * (`sync-device-catch-up.md` §5.7.3, C8).
 *
 * A joining device must be *usable before it is complete*: it fetches a small sealed
 * snapshot of the state its first screen needs, renders from that, and backfills history
 * behind it. The unit of that snapshot is one **relation instance** — and relations come in
 * two shapes, which is the whole reason this trait exists:
 *
 * - a **per-entity relation** (`Folder.FILES`) is bounded for free — one instance per
 *   folder, so the tree already partitions it;
 * - a **realm-wide singleton relation** (`MOMENTS`, `MODIFICATIONS`, …) is one unbounded
 *   collection with no sub-key inside it to cut on.
 *
 * ⚠ There is no `bucket` or `partition` concept in the platform to reach for, and the
 * platform cannot invent one: only the owning service knows whether its rows are naturally
 * chronological, hierarchical, or simply small enough not to bother. So the **slicing key
 * comes from the service**, declared here.
 *
 * ## ⚠⚠ Absence is meaningful, and is why this is a separate trait
 *
 * A type that does not declare [Sliceable] gets **no snapshot** and bootstraps by replaying
 * the log — which is correct for small bounded populations (`Place`, `Person`, contact
 * rows), where a snapshot buys nothing. That default has to be *saying nothing*.
 *
 * Folding this onto [Syncable] was considered and rejected on design grounds
 * (§5.7.4(a)), not migration cost:
 *
 *  1. every syncable type in the platform would carry a slicing field forever, and
 *     `Syncable` is the most widely-carried trait there is — a **permanent** cost, unlike
 *     a one-time migration;
 *  2. ⚠ different consumer, different hot path: `Syncable` is resolved **per ingested
 *     block** on the merge path, while slicing is read by the snapshot writer on the
 *     idle+charging path. Widening the trait ingest resolves, to carry a field ingest never
 *     reads, is the wrong coupling — and §2.2 of that note is a record of what per-block
 *     costs do at scale.
 *
 * Precedent: [RealmData] is likewise a separate trait rather than an extension of a base
 * (`core-realm-data.md`). ⚠ One difference — `RealmData` is *fieldless*; this one carries
 * its key, so it is a trait **with** fields, like [Syncable].
 */
sealed interface SliceKey : TConstant {

    /**
     * The relation is already one instance per parent entity, so the parent **is** the
     * slice. `Folder.FILES` is the motivating case: a folder's children are bounded by the
     * user's own tree, and the snapshot is addressed by the folder's entity id.
     */
    object PerEntity : SliceKey {
        override val schema: ConstantSchema = constant("${ID}/perEntity", Schema)
    }

    /**
     * A realm-wide singleton relation cut by **calendar day**. For chronological feeds
     * whose first screen is "today" — the shape `TimelineService.MOMENTS` needs, since
     * `homeDays()` already reads it that way, so the UI's first screen maps to one slice.
     */
    object Day : SliceKey {
        override val schema: ConstantSchema = constant("${ID}/day", Schema)
    }

    /**
     * As [Day], cut by calendar **month** — for feeds where a day is too fine to be worth a
     * fetch. `TRIPS` / `TRACES` are the expected consumers.
     */
    object Month : SliceKey {
        override val schema: ConstantSchema = constant("${ID}/month", Schema)
    }

    companion object {
        const val ID = "tenshu/core/sync/sliceKey"
        val Schema: ConstantSchema = constant(ID)
    }
}

/**
 * Marks a type whose collection participates in the joiner snapshot, and says how it is
 * cut. See [SliceKey] for why the key belongs to the service and what absence means.
 */
interface Sliceable : Trait {

    val sliceKey: SliceKey
        get() = getField(Fields.SLICE_KEY)

    companion object {
        const val ID = "tenshu/core/trait/sliceable"

        val Schema: TraitSchema = trait(
            id = ID,
            fields = {
                composite(Fields.SLICE_KEY, type = SliceKey.Schema)
            }
        )
    }

    object Fields {
        const val SLICE_KEY = "${ID}/sliceKey"
    }
}

/** DSL: `traits = listOf(Sliceable(SliceKey.PerEntity))`. Mirrors [Syncable]'s factory. */
fun Sliceable(sliceKey: SliceKey): TraitSchema = object : TraitSchema, Composite {
    override val id: TString get() = Sliceable.ID.toTString(null)
    override val parent: DataSchema get() = Trait.Schema
    override val traitRequirements: TList<TraitRequirementSchema> = SimpleTList(emptyList())

    override val schema: CompositeSchema get() = Sliceable.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getField(reference: String): T =
        getOptionalField(reference) ?: error("Field $reference not found")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
        Sliceable.Fields.SLICE_KEY -> sliceKey as T
        DataSchema.Fields.SCHEMA_ID -> id as T
        DataSchema.Fields.PARENT -> parent as T
        Trait.TRAIT_REQUIREMENTS -> traitRequirements as T
        else -> null
    }
}

/** True when [this] declares [Sliceable] — i.e. it participates in the joiner snapshot. */
fun DataSchema.isSliceable(): Boolean = hasTrait(Sliceable.ID)
