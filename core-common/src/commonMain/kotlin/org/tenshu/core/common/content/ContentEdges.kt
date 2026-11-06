package org.tenshu.core.common.content

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.trait.ContentDerivation
import org.tenshu.core.trait.ContentDerivationKind
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One content reference of a row: the content-bearing [propertyId] and the content it names, or
 * `null` for **none** — the row has no content in that slot.
 *
 * ⚠ `none` is an answer, not an absence. Under candidate B a push carries **every**
 * content-bearing property of its row, so a property whose content went away still names itself
 * with `none` (`content-plane-cr2-quorumless.md` §10.2, "removals are explicit").
 */
data class ContentEdge(
    val propertyId: String,
    val contentId: String?,
    /** [ContentDerivation.Fields.REGENERABLE]: rebuildable from the primary's bytes. */
    val regenerable: Boolean,
)

/**
 * How one `Syncable(OnDemand)` property's content id derives from its row's
 * [ContentReferencing.CONTENT_ID] slot, read off the property's [ContentDerivation] trait.
 */
data class ContentPropertyDeclaration(
    val propertyId: String,
    val kind: ContentDerivationKind,
    val namespace: String,
    val qualifier: String,
    val regenerable: Boolean,
) {
    /** This property's content id for a row whose slot holds [primary]; `null` when it is blank. */
    fun contentIdFor(primary: String?): String? {
        val slot = primary?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            ContentDerivationKind.Primary -> slot
            ContentDerivationKind.Derived -> deriveContentId(namespace, slot, qualifier)
        }
    }
}

/**
 * ⚠⚠ The one derivation every derived content id goes through: `sha256("$namespace:$primary:$qualifier")`
 * as lower-case hex. Changing it re-addresses every stored blob of every derived property — see
 * [ContentDerivation].
 */
fun deriveContentId(namespace: String, primary: String, qualifier: String): String =
    sha256("$namespace:$primary:$qualifier".encodeToByteArray()).toHexLower()

/**
 * ⭐ *Which content a row references*, as declared by the schema (`content-plane-cr2-quorumless.md`
 * §10.11, phase B3a). Built once from a [SchemaRegistry] and then asked per row by [edgesOf].
 *
 * ## ⚠⚠ It reads only the row it is handed
 *
 * [edgesOf] reads the slot off the payload's **own** property map (`Entity.getProperty`), never
 * through `ExecutionContext.resolveProperty`. That resolver re-reads the row **from the store** by
 * its `DataId`, which is exactly the B2 audit's §2.1 defect: blocks are built later than the save,
 * so the stored row may be a later save, or already retired by a move. A caller holding a `DataId`
 * reads the row itself with `getData(dataId, asEntity = true)` and hands it over.
 *
 * ⚠ So the row must be an [Entity] — a bare [Composite] has no property map. A bare composite of a
 * content-referencing type therefore answers `none` for every property, which is what such a save
 * does to the stored row (audit W8: it replaces the whole entry with an empty property map).
 */
class ContentDeclarations private constructor(
    private val registry: SchemaRegistry,
    /** Receiver type → its content-bearing properties, in registration order. */
    val byReceiver: Map<String, List<ContentPropertyDeclaration?>>,
    /** Receiver type → its `Syncable(OnDemand)` property ids, whether or not they declare. */
    val onDemandByReceiver: Map<String, List<String>>,
) {

    /**
     * Every content reference of [row], `none` included, or **null when it cannot be said**:
     *  - a content-bearing property of the type carries no [ContentDerivation] trait, or
     *  - the type has content-bearing properties but binds no [ContentReferencing.CONTENT_ID].
     *
     * ⚠⚠ Null is "could not ask", never "references nothing". The empty list is the second answer:
     * the type (and every ancestor) has no content-bearing property at all — a `Folder`'s own type,
     * a `Moment`, a `Place`.
     *
     * ⚠ The type's ancestors count: a `Folder` row resolves `File`'s content-bearing properties and
     * `File.ContentId`, both through the parent chain, so it answers `none` for all six. A folder
     * replacing a file at the same `DataId` (they share the key space) is a write that clears them.
     */
    fun edgesOf(row: Data): List<ContentEdge>? {
        val typeId = typeIdOf(row)
        val declared = declarationsFor(typeId) ?: return emptyList()
        if (declared.any { it == null }) return null
        val slot = registry.resolveValueRequirement(typeId, ContentReferencing.CONTENT_ID) ?: return null
        val primary = (row as? Entity<*>)?.getProperty<Data>(slot.id).let { it as? TString }?.get()
        return declared.map { declaration ->
            declaration!!
            ContentEdge(
                propertyId = declaration.propertyId,
                contentId = declaration.contentIdFor(primary),
                regenerable = declaration.regenerable,
            )
        }
    }

    /** Whether [typeId], or an ancestor, has any content-bearing property. */
    fun referencesContent(typeId: String): Boolean = declarationsFor(typeId) != null

    /** The content-bearing properties [typeId] carries, walking its parent chain; null if none. */
    private fun declarationsFor(typeId: String): List<ContentPropertyDeclaration?>? {
        var t: String? = typeId
        while (t != null) {
            byReceiver[t]?.let { return it }
            t = (registry.getSchema(t) as? CompositeSchema)?.parent?.id?.get()
        }
        return null
    }

    companion object {

        /** Reads every registered relation once; build one per pass, not one per row. */
        fun of(registry: SchemaRegistry): ContentDeclarations {
            val onDemand = onDemandPropertiesByReceiver(registry)
            val declared = onDemand.mapValues { (_, propertyIds) ->
                propertyIds.map { propertyId -> declarationOf(registry, propertyId) }
            }
            return ContentDeclarations(registry, declared, onDemand)
        }

        /**
         * Receiver type → its `Syncable(OnDemand)` properties, read straight off each property
         * schema's trait list. `registeredRelations()` is precisely the statement *"this id can
         * appear as a slot on that receiver"*, which is the set that can ever hold content.
         */
        fun onDemandPropertiesByReceiver(registry: SchemaRegistry): Map<String, List<String>> =
            registry.registeredRelations()
                .filter { (_, propertyId) ->
                    val schema = registry.getSchema(propertyId) as? CompositeSchema ?: return@filter false
                    schema.traits.toList().any { trait ->
                        (trait as? Composite)
                            ?.getOptionalField<SyncStrategy>(Syncable.Fields.STRATEGY) == SyncStrategy.OnDemand
                    }
                }
                .groupBy({ it.first }, { it.second })

        /** [propertyId]'s declared derivation, or null when it carries no [ContentDerivation]. */
        fun declarationOf(registry: SchemaRegistry, propertyId: String): ContentPropertyDeclaration? {
            val schema = registry.getSchema(propertyId) as? CompositeSchema ?: return null
            val trait = schema.traits.toList()
                .filterIsInstance<Composite>()
                .firstOrNull { it.getOptionalField<TString>(DataSchema.Fields.SCHEMA_ID)?.get() == ContentDerivation.ID }
                ?: return null
            return ContentPropertyDeclaration(
                propertyId = propertyId,
                kind = trait.getOptionalField(ContentDerivation.Fields.KIND) ?: return null,
                namespace = trait.getOptionalField<TString>(ContentDerivation.Fields.NAMESPACE)?.get().orEmpty(),
                qualifier = trait.getOptionalField<TString>(ContentDerivation.Fields.QUALIFIER)?.get().orEmpty(),
                regenerable = trait.getOptionalField<TBoolean>(ContentDerivation.Fields.REGENERABLE)?.get() ?: false,
            )
        }

        /** An Entity carries its type; a bare composite IS its type (the `SyncObserver` idiom). */
        internal fun typeIdOf(row: Data): String = (row as? Entity<*>)?.type?.get() ?: row.id
    }
}

/**
 * One [ContentDeclarations] per [SchemaRegistry.generation], for a caller asked per save (CR-2 B3d's
 * emit and its lane admission). Building the declarations walks every registered relation, which is
 * right once and wasteful once per row; a module registered later moves the generation and rebuilds.
 *
 * ⚠ Racy by design and harmlessly so: two threads missing together both build, and either result is
 * the same answer.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class ContentDeclarationsCache(private val registry: SchemaRegistry) {
    private val cached = kotlin.concurrent.atomics.AtomicReference<Pair<Int, ContentDeclarations>?>(null)

    fun get(): ContentDeclarations {
        val generation = registry.generation
        cached.load()?.let { (built, declarations) -> if (built == generation) return declarations }
        return ContentDeclarations.of(registry).also { cached.store(generation to it) }
    }
}

/** [ContentDeclarations.edgesOf] for one row, building the declarations from [registry]. */
fun contentEdgesOf(registry: SchemaRegistry, row: Data): List<ContentEdge>? =
    ContentDeclarations.of(registry).edgesOf(row)

/**
 * The one edge of [row] for [propertyId] — what [ContentDeclarations.edgesOf] would answer for that
 * property — reading only that property's schema and the type's [ContentReferencing.CONTENT_ID]
 * binding. Null when the property declares no [ContentDerivation] or the type binds no slot.
 *
 * ⚠ For a per-property port (`ExternalPropertyWriter.resolveContentId`), which is asked per row per
 * property on read paths such as a thumbnail grid: building [ContentDeclarations] walks every
 * registered relation, which is right once per walk and wasteful once per thumbnail. It does not
 * check that [propertyId] is registered on the row's type; the port's own `supports` does.
 */
fun contentEdgeOf(registry: SchemaRegistry, row: Data, propertyId: String): ContentEdge? {
    val declaration = ContentDeclarations.declarationOf(registry, propertyId) ?: return null
    val slot = registry.resolveValueRequirement(ContentDeclarations.typeIdOf(row), ContentReferencing.CONTENT_ID)
        ?: return null
    val primary = (row as? Entity<*>)?.getProperty<Data>(slot.id).let { it as? TString }?.get()
    return ContentEdge(propertyId, declaration.contentIdFor(primary), declaration.regenerable)
}
