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
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.constant
import org.tenshu.core.schema.string
import org.tenshu.core.schema.trait
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * How a content-bearing property's content id is computed from its row's
 * [ContentReferencing.CONTENT_ID] slot (`content-plane-cr2-quorumless.md` §10.11, phase B3a).
 */
sealed interface ContentDerivationKind : TConstant {

    /** The content id **is** the slot's value — `File.Data`, `Workout.Streams`. */
    object Primary : ContentDerivationKind {
        override val schema: ConstantSchema = constant("${ID}/primary", Schema)
    }

    /**
     * The content id is a hash of the slot's value, a namespace and a qualifier — the five
     * `File.Thumbnail*` rungs. See [ContentDerivation] for the exact form.
     */
    object Derived : ContentDerivationKind {
        override val schema: ConstantSchema = constant("${ID}/derived", Schema)
    }

    companion object {
        const val ID = "tenshu/core/content/derivationKind"
        val Schema: ConstantSchema = constant(ID)
    }
}

/**
 * Declared on a `Syncable(OnDemand)` **property**: how its content id derives from the row's
 * [ContentReferencing.CONTENT_ID] slot. Together with that binding it makes *which content a row
 * references* a schema declaration, so one pure function (`contentEdgesOf` in `:core-common`)
 * answers it for every type, from the payload in hand.
 *
 * ## Why this is schema data and not per-service code
 *
 * Under CR-2 candidate B the relay's edge registers hold exactly what each push names, so every
 * consumer — the block builder, the re-stamp walk, the backfill, the content emit — must compute
 * the **same** edges. Before B3a the thumbnail derivation lived only in `FileBytesPropertyWriter`,
 * and the sync layer could not know it without naming `File`.
 *
 * ## ⚠⚠ A [ContentDerivationKind.Derived] declaration must reproduce today's ids byte for byte
 *
 * The derived id is `sha256("$namespace:$primary:$qualifier")` as lower-case hex. A rung's content
 * id is fed to `manifestContentRef`, so a changed namespace, qualifier or form re-addresses every
 * stored blob of that property and strands the existing manifests. The thumbnail rungs declare
 * namespace `"thumbnail"` and their `GetStoredThumbnail.CLASS_*` id as qualifier, which is exactly
 * the form `FileBytesPropertyWriter.derivedThumbnailContentId` has always minted;
 * `ContentEdgesEquivalenceTest` pins it.
 *
 * ## [Fields.REGENERABLE]
 *
 * Content that can be rebuilt from the primary's bytes (every thumbnail rung). Under B4 it is never
 * the subject of `content_freed` and never part of a REVIVE's ref check: a freed rung is minted
 * again on view (§10.11).
 *
 * ⚠ Like every trait, this is code-side schema metadata and never serialised, so declaring it on a
 * property reshapes no stored row and moves no service version.
 */
interface ContentDerivation : Trait {

    val kind: ContentDerivationKind
        get() = getField(Fields.KIND)

    companion object {
        const val ID = "tenshu/core/trait/contentDerivation"

        val Schema: TraitSchema = trait(
            id = ID,
            fields = {
                composite(Fields.KIND, type = ContentDerivationKind.Schema)
                string(Fields.NAMESPACE)
                string(Fields.QUALIFIER)
                boolean(Fields.REGENERABLE)
            }
        )
    }

    object Fields {
        const val KIND = "${ID}/kind"

        /** Blank for [ContentDerivationKind.Primary]. */
        const val NAMESPACE = "${ID}/namespace"

        /** Blank for [ContentDerivationKind.Primary]. */
        const val QUALIFIER = "${ID}/qualifier"
        const val REGENERABLE = "${ID}/regenerable"
    }
}

/** DSL: `traits = listOf(Syncable(SyncStrategy.OnDemand), PrimaryContent())`. */
fun PrimaryContent(): TraitSchema =
    contentDerivation(ContentDerivationKind.Primary, namespace = "", qualifier = "", regenerable = false)

/**
 * DSL: `traits = listOf(Syncable(SyncStrategy.OnDemand), DerivedContent("thumbnail", CLASS_LIST))`.
 *
 * ⚠ See [ContentDerivation] before choosing [namespace] or [qualifier]: they are part of the
 * content address, so they can never change once content has been stored under them.
 */
fun DerivedContent(namespace: String, qualifier: String, regenerable: Boolean = true): TraitSchema {
    require(namespace.isNotBlank() && qualifier.isNotBlank()) {
        "a derived content id needs a namespace and a qualifier, or two properties could collide"
    }
    return contentDerivation(ContentDerivationKind.Derived, namespace, qualifier, regenerable)
}

private fun contentDerivation(
    kind: ContentDerivationKind,
    namespace: String,
    qualifier: String,
    regenerable: Boolean,
): TraitSchema = object : TraitSchema, Composite {
    override val id: TString get() = ContentDerivation.ID.toTString(null)
    override val parent: DataSchema get() = Trait.Schema
    override val traitRequirements: TList<TraitRequirementSchema> = SimpleTList(emptyList())

    override val schema: CompositeSchema get() = ContentDerivation.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getField(reference: String): T =
        getOptionalField(reference) ?: error("Field $reference not found")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
        ContentDerivation.Fields.KIND -> kind as T
        ContentDerivation.Fields.NAMESPACE -> namespace.toTString(null) as T
        ContentDerivation.Fields.QUALIFIER -> qualifier.toTString(null) as T
        ContentDerivation.Fields.REGENERABLE -> regenerable.toTBoolean(null) as T
        DataSchema.Fields.SCHEMA_ID -> id as T
        DataSchema.Fields.PARENT -> parent as T
        Trait.TRAIT_REQUIREMENTS -> traitRequirements as T
        else -> null
    }
}
