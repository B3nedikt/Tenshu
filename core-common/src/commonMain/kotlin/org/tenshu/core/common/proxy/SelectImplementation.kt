package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * `Select` is a *declarative* projection — it stays synchronous and only
 * describes what should be loaded. Field and static-relation selectors
 * materialise inline against the local database as before.
 *
 * Dynamic-relation selectors targeting a schema with
 * `Syncable(SyncStrategy.OnDemand)` that yield `null` against the local
 * chain are special: instead of writing `null` (and losing the chance to
 * fetch the bytes), we write a [PendingOnDemandProperty] placeholder
 * carrying the entity id + relation id + params. The terminal operation
 * ([MaybeGetImplementation], `.flow()`) consumes those placeholders,
 * dispatches [OnDemandResolution.resolve] for each, then replaces them
 * with the materialised value.
 *
 * This separation keeps the I/O concern where it belongs (the terminal,
 * which is already async / suspend-friendly) and lets `Select` remain a
 * pure transform — matching the established `ArrayDatabaseReference`
 * pattern of "describe now, materialise on terminal".
 */
internal class SelectImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        // Phase 3 of `docs/proposals/core-error-channel.md`: the dispatcher's
        // structural validation (Phase 2) guarantees [Select.Params.SELECTORS] is
        // present + typed as `TList<Selector<*>>`. The impl-side missing-param
        // Failure branch is gone.
        val selectors: TList<Selector<Data>> = params.read(Select.Params.SELECTORS)
        val selectorList = selectors.toList()

        return when (caller) {
            is Maybe<*> -> selectFromMaybe(executionContext, caller, selectorList) as T

            // Entity caller: project the wrapped composite in place, return
            // a new Entity carrying the same id with the projected
            // fields/relations attached. The `else` branch below would
            // funnel this through `resolveList` (Entity isn't a list →
            // returns null → empty TList), losing the value entirely;
            // dispatch explicitly here. See chain-DSL redesign §2.2 follow-up.
            is Entity<*> -> selectItem(executionContext, caller as Data, selectorList) as T

            else -> {
                val sequence = executionContext.resolveList(caller)
                    ?: return emptyList<Data>().toTList(executionContext.context) as T

                sequence.map { item ->
                    selectItem(executionContext, item, selectorList)
                }.toList().toTList(executionContext.context) as T
            }
        }
    }

    private fun <T : Data> selectFromMaybe(
        executionContext: ExecutionContext,
        caller: Maybe<*>,
        selectors: List<Selector<T>>
    ): Maybe<Data> {
        return when (caller) {
            is Maybe.Some<*> -> {
                val item = selectItem(executionContext, caller.value as Data, selectors)
                Maybe.Some.create(executionContext.context, item)
            }

            else -> Maybe.Empty.create(executionContext.context)
        }
    }

    private fun <T : Data> selectItem(
        executionContext: ExecutionContext,
        item: Data,
        selectors: List<Selector<T>>
    ): Data {
        item as? Composite ?: error("Select must be called on a composite or entity. Was: $item")

        val fields = mutableMapOf<String, Data>()
        val properties = mutableMapOf<String, Data>()

        val itemSchema = item.schema
        val allFieldSchemas = itemSchema.values.toList()

        // ⚠⚠ Every property selector of this row resolved in ONE call, so the resolver reads the
        // stored row once rather than once per property (`ReferenceResolver.resolveProperties`).
        // Nine value properties over a whole library were nine full-row reads and decodes per row,
        // ~70 % of the read in Control (`photos-app.md` §39). The answers are the one-at-a-time
        // answers; only the number of store reads changes.
        val propertySelectors = selectors.filterIsInstance<Selector.PropertySelector<*>>()
        val propertyValues: List<Data?> =
            if (propertySelectors.isEmpty()) emptyList()
            else executionContext.resolveProperties(item, propertySelectors.map { it.propertyReference })
        // Consumed in selector order below — the order [propertySelectors] was built in.
        var nextPropertyValue = 0

        for (selector in selectors) {
            when (selector) {
                is Selector.FieldSelector -> {
                    val fieldRef = selector.fieldReference.get()
                    val fieldSchema = allFieldSchemas.find { it.id.get() == fieldRef }

                    var value: Data? = when {
                        fieldSchema != null -> {
                            if (fieldSchema.isOptional.get()) {
                                item.getOptionalField<Data>(fieldRef)
                            } else {
                                item.getField<Data>(fieldRef)
                            }
                        }

                        item is Entity<*> -> {
                            item.getFieldValue<Data?>(fieldRef)
                        }

                        else -> null
                    }

                    if (value != null) {
                        if (value is ArrayDatabaseReference) {
                            // Only resolve database references if we need to
                            val nestedArray = executionContext.resolveList(value)
                            if (nestedArray != null) {
                                fields[fieldRef] =
                                    nestedArray.toList().toTList(executionContext.context)
                            } else {
                                fields[fieldRef] = value
                            }
                        } else if (value is CompositeDatabaseReference) {
                            // The projection names this composite field, so
                            // materialise it now. A field left out of the
                            // projection keeps its deferred reference unread —
                            // that is the over-fetch the lazy seam removes.
                            executionContext.getComposite(value)?.let { fields[fieldRef] = it }
                        } else {
                            fields[fieldRef] = value
                        }
                    }
                }

                is Selector.PropertySelector -> {
                    val propRef = selector.propertyReference
                    val value = propertyValues[nextPropertyValue++]
                    if (value != null) {
                        val nestedSelectors = selector.nested
                        properties[propRef.get()] = when {
                            nestedSelectors != null ->
                                applyNested(executionContext, value, nestedSelectors.toList())

                            // A bare array-valued relation (an `entityRelation`,
                            // e.g. `relations(File.FileHistory)` with no nested block)
                            // resolves to an `ArrayDatabaseReference`; materialise it now,
                            // mirroring the `FieldSelector` arm for list fields. With a
                            // nested block `applyNested` already does this per element.
                            value is ArrayDatabaseReference ->
                                executionContext.resolveList(value)
                                    ?.toList()?.toTList(executionContext.context)
                                    ?: value

                            else -> value
                        }
                    }
                }

                is Selector.DynamicPropertySelector -> {
                    val dynRef = selector.dynamicPropertyReference
                    // A dynamic relation registered on a *subtype* (e.g. the
                    // Folder-only `FileCount` projected over a mixed File
                    // listing) is simply absent on rows of other branches —
                    // mirroring how subtype FIELDS absent on a row are
                    // skipped. Without this guard the relation's chain would
                    // evaluate against a receiver that lacks its fields and
                    // throw, failing the whole projection.
                    if (!dynamicRelationAppliesTo(executionContext, item, dynRef.get())) {
                        continue
                    }
                    // Deferred resolution (`core-deferred-properties.md`): the
                    // per-select override (a `Resolved` selector) beats the
                    // property schema's `deferredByDefault`. A deferred selector
                    // writes a [PendingDeferredProperty] marker INSTEAD of paying
                    // the resolver inline — the terminal decides what "later"
                    // means. Rows without a stable DataId have nothing to patch
                    // against and fall through to inline resolution.
                    val isDeferred = selector.deferred?.get()
                        ?: relationDeferredByDefault(executionContext, dynRef.get())
                    if (isDeferred) {
                        val entityId = executionContext.getDataId(item)
                        if (entityId != null) {
                            properties[dynRef.get()] = PendingDeferredProperty.create(
                                context = executionContext.context,
                                entityId = entityId,
                                propertyId = dynRef.get(),
                                params = selector.params
                            )
                            continue
                        }
                    }
                    val value = executionContext.resolveProperty(item, dynRef, selector.params)
                    if (value != null) {
                        properties[dynRef.get()] = value
                    } else {
                        // Local miss. If the relation schema declares
                        // `Syncable(OnDemand)` and the parent entity has a
                        // stable id, record a [PendingOnDemandProperty] so the
                        // terminal can fetch the bytes — see class docs.
                        val pending = buildPendingMarker(
                            executionContext = executionContext,
                            item = item,
                            dynRef = dynRef,
                            params = selector.params
                        )
                        if (pending != null) {
                            properties[dynRef.get()] = pending
                        }
                    }
                }
            }
        }

        val type = (item as? Entity<*>)?.type?.get() ?: item.id
        return executionContext.context.dataFactory().createEntity<Composite>(
            type = type,
            fields = fields,
            relations = properties
        )
    }

    /**
     * Apply a nested projection to a resolved relation value (`relations(handle) { … }`):
     * project each element of a list relation — or the single resolved entity — through
     * [selectItem] with the [nested] selectors, so the result is shaped to any depth
     * (`core-nested-projection.md` Slice 2.2). Read-pruning of the nested relation is a
     * separate efficiency step (Slice 2.3 / Phase 3, reusing the dbEntryMapper's tree
     * projection); this shapes the output.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyNested(
        executionContext: ExecutionContext,
        value: Data,
        nested: List<Selector<*>>,
    ): Data {
        val elements = executionContext.resolveList(value)
        return if (elements != null) {
            elements
                .map { selectItem(executionContext, it, nested as List<Selector<Data>>) }
                .toList()
                .toTList(executionContext.context)
        } else {
            selectItem(executionContext, value, nested as List<Selector<Data>>)
        }
    }

    private fun buildPendingMarker(
        executionContext: ExecutionContext,
        item: Data,
        dynRef: org.tenshu.core.reference.DynamicPropertyTypeReference,
        params: TList<Data>?
    ): PendingOnDemandProperty? {
        val relationId = dynRef.get()
        val relationSchema = executionContext.context.schemaProvider()
            .getSchema(relationId) as? CompositeSchema ?: return null

        val syncable = relationSchema.traits.toList()
            .firstNotNullOfOrNull { trait ->
                executionContext.traitResolver.getTrait<Syncable>(
                    typeId = relationId,
                    traitId = Syncable.ID
                )
            } ?: return null

        if (syncable.strategy != SyncStrategy.OnDemand) return null

        val entityId = executionContext.getDataId(item) ?: return null
        return PendingOnDemandProperty.create(
            context = executionContext.context,
            entityId = entityId,
            propertyId = relationId,
            params = params
        )
    }

    /**
     * The property schema's `deferredByDefault` — code-side metadata on the
     * registered [org.tenshu.core.schema.RelationSchema.DynamicPropertyRelationSchema]
     * (the same lookup `resolveDynamicProperty` uses for `callstack`, so the
     * Kotlin-level flag is always the proxy's own registration, never a
     * wire-deserialised copy).
     */
    private fun relationDeferredByDefault(
        executionContext: ExecutionContext,
        relationId: String,
    ): Boolean = (executionContext.context.schemaProvider().getSchema(relationId)
            as? org.tenshu.core.schema.RelationSchema.DynamicPropertyRelationSchema)
        ?.deferredByDefault ?: false

    /**
     * Whether a dynamic relation is registered on the row's type or one of
     * its ancestors (`registerDynamicPropertyRelation` writes
     * `relations[[fromId, relationId]]`; the walk gives subtype rows their
     * inherited relations, e.g. `File.Preview` on a `Folder` row).
     */
    private fun dynamicRelationAppliesTo(
        executionContext: ExecutionContext,
        item: Data,
        relationId: String,
    ): Boolean {
        val typeId = (item as? Entity<*>)?.type?.get() ?: item.id
        val provider = executionContext.context.schemaProvider()
        var schema = provider.getSchema(typeId)
        while (schema != null) {
            if (provider.getServiceRelation(listOf(schema.id.get(), relationId)) != null) {
                return true
            }
            schema = schema.parent
        }
        return false
    }
}
