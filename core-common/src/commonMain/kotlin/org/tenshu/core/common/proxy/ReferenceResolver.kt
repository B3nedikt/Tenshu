package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.lazyDataMaterializer
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.getOrNull
import org.tenshu.core.id
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.EntityRelationDescriptor
import org.tenshu.core.relation.ReferencePropertyDescriptor
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.LazyDataCreator
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params

/**
 * Owns the "what reference shapes can a list-valued field carry, and how do I read each"
 * question for [ExecutionContext] and [DbEntryMapper], so it is answered in one place. Part
 * of the decomposition in `docs/adr/0008-execution-context-decomposition.md`.
 *
 * `resolveProperty` and `resolveDynamicProperty` take [ExecutionContext] as a parameter
 * — mirroring [evaluateCallstack]'s shape, where the context flows through as data
 * rather than as a held back-reference. This keeps `ReferenceResolver` itself stateless
 * with respect to the context and lets it be constructed before any
 * [ExecutionContext] exists (the default constructor on [ExecutionContext] does this
 * during init).
 */
interface ReferenceResolver {

    /**
     * Collapse the four shapes a list-valued field can carry into a uniform
     * `Sequence<Data>?` view. `null` means "not list-shaped" — callers treat it as
     * empty or fail per their own contract.
     */
    fun resolveList(data: Any?): Sequence<Data>?

    /**
     * Fetch the contents of an [ArrayDatabaseReference] from storage. Side-effects:
     * if a reference collector is active (see [collectingReferences]), the reference
     * is recorded so the stream layer can later subscribe listeners on it.
     */
    fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
    ): Sequence<Data>?

    /**
     * Windowed variant of [getArrayData] — same reference-collector side effect
     * (the stream layer must still subscribe listeners on a windowed read; the
     * window changes *what is deserialised*, not *what is observed*).
     */
    fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection? = null,
        offset: Int,
        limit: Int,
    ): Sequence<Data>? = getArrayData(reference, projection)
        ?.drop(offset)
        ?.take(limit)

    /**
     * The cancellable-read variants (`core-background-processing.md` §6.2.1): [checkpoint] reaches
     * the store's eager materialisation ([Database.getArrayData] with a checkpoint). Defaults drop
     * it — correct, just not stoppable mid-read; [ReferenceResolverImpl] forwards it.
     */
    fun resolveList(data: Any?, checkpoint: () -> Unit): Sequence<Data>? = resolveList(data)

    fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        checkpoint: () -> Unit,
    ): Sequence<Data>? = getArrayData(reference, projection)

    fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
        checkpoint: () -> Unit,
    ): Sequence<Data>? = getArrayDataWindow(reference, projection, offset, limit)

    /**
     * The array's member hashes (no deserialisation) — see
     * [Database.getArrayMemberHashes]. Records the reference in an active
     * collector like [getArrayData]: a caller that decides against reading
     * the array based on its hashes (the by-key search fast path) still
     * *observed* it, so stream invalidation must cover it. `null` = unknown →
     * caller falls back to a full read.
     */
    fun getArrayMemberHashes(reference: ArrayDatabaseReference): List<Long>? = null

    /**
     * Materialise a single deferred composite ([CompositeDatabaseReference]) — the
     * single-composite peer of [getArrayData]. No reference-collector side effect:
     * single composites have no array-listener equivalent.
     */
    fun getCompositeData(reference: CompositeDatabaseReference): Data?

    /**
     * Resolve a service-relation path. `currentServiceId` may be null when the
     * lookup originates outside any service (e.g. cross-service navigation from
     * a root request).
     */
    fun resolveServiceType(
        currentServiceId: String?,
        referencedServiceId: String,
    ): ServiceTypeReference

    /**
     * Unified resolver for `ServiceDataRelation` — returns an
     * [ArrayDatabaseReference] for `TList<X>`-typed relations, an [Entity] for
     * single-entity relations with a `dataId`.
     */
    fun resolveServiceData(
        service: ServiceTypeReference,
        ref: Reference,
    ): Data

    /**
     * Run [block] with [into] installed as the active reference collector — any
     * [ArrayDatabaseReference] resolved via [getArrayData] inside [block] (directly
     * or via [resolveList]) is added to [into]. The previous collector is restored
     * on exit, so nested scopes compose.
     *
     * The scope belongs to the calling thread: a read on another thread — another stream's
     * evaluation, an ordinary request — is not recorded into it. [block] is deliberately not
     * suspending, so it cannot leave the thread it was entered on.
     */
    fun <T> collectingReferences(
        into: MutableSet<ArrayDatabaseReference>,
        block: () -> T,
    ): T

    /**
     * Resolve a property reference against an entity. Dispatches to dynamic-relation
     * evaluation when the reference is a [DynamicPropertyTypeReference]; otherwise
     * reads the property from the entity's materialised property map after a database
     * lookup by `dataId`.
     *
     * Takes [executionContext] because the static-property path needs
     * [ExecutionContext.getDataId] (which depends on `pureOperationRunner` +
     * `traitResolver` not owned by the resolver) and the dynamic-property path
     * recurses through [evaluateCallstack] which needs the context.
     */
    fun resolveProperty(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: PropertyTypeReference,
        params: TList<Data>? = null,
    ): Data?

    /**
     * Resolve several property references against ONE row — what `Select` asks for each row it
     * projects. Answers in [propertyReferences]' order, `null` where [resolveProperty] would.
     *
     * ⚠⚠ Exists for cost, not for meaning: every answer must be exactly what [resolveProperty]
     * gives for that reference alone. The default is that loop; [ReferenceResolverImpl] reads a
     * row's **stored** value properties from one store read instead of one per property — a
     * whole-library projection of nine value properties is otherwise nine full-row reads and
     * decodes per row (`photos-app.md` §39).
     */
    fun resolveProperties(
        executionContext: ExecutionContext,
        data: Data,
        propertyReferences: List<PropertyTypeReference>,
    ): List<Data?> = propertyReferences.map { resolveProperty(executionContext, data, it, null) }

    /**
     * Evaluate a dynamic-property relation chain in the context of [data].
     *
     * Hot path: when [params] is null and the relation schema is a [LazyDataCreator],
     * the materialised chain is read off the schema directly (cache-hit on the
     * `LazyDataMaterializer` registry). Slow path: materialise the chain via
     * [CallstackMaterializer] from the schema's chain template.
     */
    fun resolveDynamicProperty(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: DynamicPropertyTypeReference,
        params: TList<Data>?,
    ): Data?
}

class ReferenceResolverImpl(
    private val context: TContext,
    private val database: Database,
    private val materializer: CallstackMaterializer,
) : ReferenceResolver {

    private val schemaProvider: SchemaProvider get() = context.schemaProvider()

    /**
     * ⚠⚠ Per thread, not per resolver. One resolver serves a whole session, and each of its
     * streams re-evaluates on its own coroutine, so [collectingReferences] scopes overlap on
     * different threads all the time. As a plain field, the scope entered second stole the first
     * one's reads and the first one's exit cleared the collector under the second — and a stream
     * that comes out of an evaluation with a reference missing unsubscribes from that array and
     * never hears of it again. `ReferenceCollectorConfinementTest` forces the interleaving.
     */
    private val referenceCollector = ThreadConfinedSlot<MutableSet<ArrayDatabaseReference>>()

    override fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
    ): Sequence<Data>? {
        referenceCollector.get()?.add(reference)
        return database.getArrayData(reference, projection)
    }

    override fun getArrayData(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        checkpoint: () -> Unit,
    ): Sequence<Data>? {
        referenceCollector.get()?.add(reference)
        return database.getArrayData(reference, projection, checkpoint)
    }

    override fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
        checkpoint: () -> Unit,
    ): Sequence<Data>? {
        referenceCollector.get()?.add(reference)
        return database.getArrayDataWindow(reference, projection, offset, limit, checkpoint)
    }

    override fun resolveList(data: Any?, checkpoint: () -> Unit): Sequence<Data>? {
        arrayReferenceOrNull(data)?.let { return getArrayData(it, null, checkpoint) }
        return resolveList(data)
    }

    override fun getArrayDataWindow(
        reference: ArrayDatabaseReference,
        projection: Projection?,
        offset: Int,
        limit: Int,
    ): Sequence<Data>? {
        referenceCollector.get()?.add(reference)
        return database.getArrayDataWindow(reference, projection, offset, limit)
    }

    override fun getArrayMemberHashes(reference: ArrayDatabaseReference): List<Long>? {
        referenceCollector.get()?.add(reference)
        return database.getArrayMemberHashes(reference)
    }

    override fun getCompositeData(reference: CompositeDatabaseReference): Data? =
        database.getComposite(reference)

    override fun resolveList(data: Any?): Sequence<Data>? {
        arrayReferenceOrNull(data)?.let { return getArrayData(it) }
        return when (data) {
            is TList<*> -> data.toList().asSequence()
            is Sequence<*> -> {
                @Suppress("UNCHECKED_CAST")
                data as Sequence<Data>
            }

            else -> null
        }
    }

    override fun resolveServiceType(
        currentServiceId: String?,
        referencedServiceId: String,
    ): ServiceTypeReference {
        val path = listOfNotNull(currentServiceId, referencedServiceId)
        val resolvedId = schemaProvider.getServiceRelation(path)
            ?: referencedServiceId

        return schemaProvider.getReference(resolvedId) as ServiceTypeReference
    }

    override fun resolveServiceData(
        service: ServiceTypeReference,
        ref: Reference,
    ): Data {
        val relation = schemaProvider.getServiceDataRelation(service, ref)
            ?: error("Could not resolve service-data relation from $service to $ref")

        val baseSchemaId = relation.type.baseSchema?.id?.get()
        return when {
            baseSchemaId == TList.ID -> {
                val relationId = when (val to = relation.to) {
                    is Reference -> to.get()
                    is TString -> to.get()
                    else -> error("Invalid 'to' field in relation: $to")
                }
                val itemTypeId = relation.type.params.toList().firstOrNull()
                    ?.type?.baseSchema?.id?.get()
                    ?: error("ServiceDataRelation list type missing element parameterisation: $relation")
                val itemTypeSchema = schemaProvider.getSchema(itemTypeId)
                val isEntity = itemTypeSchema?.isSubtypeOf(Entity.ID) ?: false

                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = context,
                        type = TList.ID,
                        relationId = relationId,
                    ),
                    isEntity = isEntity,
                    elementSchemaId = itemTypeId,
                )
            }
            relation.dataId != null -> {
                val data = database.getData(relation.dataId!!, asEntity = true)
                    ?: error("Entity not found for ${relation.dataId}")
                data as? Entity<*>
                    ?: error("Resolved data for ${relation.dataId} is not an Entity: $data")
            }
            else -> error(
                "ServiceDataRelation has neither TList type nor dataId: ${relation.type}"
            )
        }
    }

    override fun <T> collectingReferences(
        into: MutableSet<ArrayDatabaseReference>,
        block: () -> T,
    ): T {
        val prev = referenceCollector.get()
        referenceCollector.set(into)
        try {
            return block()
        } finally {
            referenceCollector.set(prev)
        }
    }

    override fun resolveProperty(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: PropertyTypeReference,
        params: TList<Data>?,
    ): Data? {
        if (propertyReference is DynamicPropertyTypeReference) {
            return resolveDynamicProperty(executionContext, data, propertyReference, params)
        }
        schemaProvider.getReferenceProperty(propertyReference.get())?.let { descriptor ->
            return resolveReferenceProperty(data, descriptor)
        }
        schemaProvider.getEntityRelation(propertyReference.get())?.let { descriptor ->
            return resolveEntityRelation(executionContext, data, propertyReference, descriptor)
        }
        return resolveStaticProperty(executionContext, data, propertyReference)
    }

    override fun resolveProperties(
        executionContext: ExecutionContext,
        data: Data,
        propertyReferences: List<PropertyTypeReference>,
    ): List<Data?> {
        // ⚠ One store read for every STORED value property of the row, taken the first time one is
        // asked for and never otherwise — so a projection with none pays nothing, and the read (and
        // its "no DataId" invariant error) happens exactly where `resolveStaticProperty` would have
        // raised it. Everything that is not a plain stored value goes through [resolveProperty]
        // unchanged, which is what keeps each answer identical to the one-at-a-time path.
        var stored: Data? = null
        var read = false
        return propertyReferences.map { ref ->
            if (!isStoredValueProperty(ref)) return@map resolveProperty(executionContext, data, ref, null)
            if (!read) {
                stored = readStoredRow(executionContext, data)
                read = true
            }
            (stored as? Entity<*>)?.properties?.get(ref)
        }
    }

    /** What [resolveProperty] sends to [resolveStaticProperty] — the same three exclusions. */
    private fun isStoredValueProperty(ref: PropertyTypeReference): Boolean =
        ref !is DynamicPropertyTypeReference &&
            schemaProvider.getReferenceProperty(ref.get()) == null &&
            schemaProvider.getEntityRelation(ref.get()) == null

    /**
     * Resolve an **entity relation**: build the per-entity array reference keyed by the
     * receiver's own `DataId` (an `EntityPropertyDataId`) and return it as an
     * [ArrayDatabaseReference] — the same deferred-list shape a list field carries, so
     * `select`/`resolveList` materialise it lazily (and a nested projection shapes each
     * element). `null` when the receiver has no derivable `DataId`. The array is
     * populated by a maintained reverse-index (e.g. `FileHistoryRelationMaintainer`);
     * see `docs/proposals/core-nested-projection.md` §3.3.
     */
    private fun resolveEntityRelation(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: PropertyTypeReference,
        descriptor: EntityRelationDescriptor,
    ): Data? {
        val parentEntity = executionContext.getDataId(data) as? DataId.EntityDataId
            ?: return null
        // `isEntity = false` + `elementSchemaId` mirror what the maintainer writes the
        // array with — the rows are concrete element composites read back as composites
        // (an `Entity<E>` wrapper would not project / decode through the element schema).
        val isEntity = schemaProvider.getSchema(descriptor.elementTypeId)
            ?.isSubtypeOf(Entity.ID) ?: false
        return ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = context,
                type = TList.ID,
                parentEntity = parentEntity,
                propertyId = propertyReference.get(),
            ),
            isEntity = isEntity,
            elementSchemaId = descriptor.elementTypeId,
        )
    }

    /**
     * Resolve a reference value property: read the target's key off the receiver's own
     * [ReferencePropertyDescriptor.keyFieldRef] field, build the target's `StringDataId`,
     * and dereference the live entity via the existing `Database.getData` mechanism —
     * `null` if either the key or the target is absent. The key is a scalar field (always
     * materialised, even under projection pushdown), so no receiver re-fetch is needed. No
     * wire involvement; see `docs/proposals/core-reference-property.md`.
     */
    private fun resolveReferenceProperty(
        data: Data,
        descriptor: ReferencePropertyDescriptor,
    ): Data? {
        // The key field is a foreign key — a mandatory scalar — so read it with the
        // canonical accessors (`getField` on a composite throws for an absent mandatory
        // field; the real `CompositeDto` rejects `getOptionalField` on it).
        val raw: Data? = when (data) {
            is Entity<*> -> data.getFieldValue<Data?>(descriptor.keyFieldRef)
            is Composite -> data.getField<Data>(descriptor.keyFieldRef)
            else -> null
        }
        val key = (raw as? TString)?.get() ?: return null
        val targetId = DataId.StringDataId.create(
            context = context,
            type = descriptor.targetTypeId,
            keyValue = key,
        )
        // `asEntity = false`: the typed handle is a `Property.Value<T>` whose decode is
        // `it as? T` (e.g. `as? Device`), so the resolved cell must be the target composite
        // itself — an `Entity<T>` wrapper would not cast to `T`.
        return database.getData(targetId, asEntity = false)
    }

    private fun resolveStaticProperty(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: PropertyTypeReference,
    ): Data? {
        // ⚠⚠ A row this store does not hold is an ABSENCE, not a violation, and must resolve
        // to no value rather than raise. An array naming a row that has not arrived is routine
        // while syncing: a peer's create can land before the parent array that names it, a pull
        // can be interrupted mid-batch, and a pull-fenced device sits in that state indefinitely.
        //
        // Raising here reaches much further than one unreadable property. A projected chain
        // resolves every row this way, and Drive's sorted listing uses one as its *invalidation*
        // chain; `StreamRegistry` stops a stream on any `Throwable`, so a raise ends the folder's
        // change stream, after which the client re-subscribes on a backoff settling at 15 s and
        // fails again each time — the listing keeps its rows and silently never updates again,
        // all for one missing folder row (`core-version-compatibility.md` §1.6).
        val dataEntity = readStoredRow(executionContext, data) ?: return null

        if (dataEntity !is Entity<*>) return null

        return dataEntity.properties[propertyReference]
    }

    /**
     * The stored row behind [data], with its value properties — `null` when this store does not
     * hold it (see [resolveStaticProperty] for why that is an absence and not an error).
     */
    private fun readStoredRow(executionContext: ExecutionContext, data: Data): Data? {
        // A composite that cannot be addressed at all is an invariant violation — nothing could
        // have stored it, and nothing can read it back.
        val dataId = executionContext.getDataId(data)
            ?: error("DataId not found for ${data.id}")
        return database.getData(dataId, asEntity = true)
    }

    override fun resolveDynamicProperty(
        executionContext: ExecutionContext,
        data: Data,
        propertyReference: DynamicPropertyTypeReference,
        params: TList<Data>?,
    ): Data? {
        val relationSchema = schemaProvider.getSchema(propertyReference.get())
                as? RelationSchema.DynamicPropertyRelationSchema
            ?: error("Dynamic relation schema not found for ${propertyReference.get()}")

        val schemaChain = if (params == null && relationSchema is LazyDataCreator<*>) {
            @Suppress("UNCHECKED_CAST")
            val materialised = context.lazyDataMaterializer().getOrCreate(
                relationSchema as LazyDataCreator<RelationSchema.DynamicPropertyRelationSchema>
            )
            materialised.chain?.toList()
        } else null

        val callstack = schemaChain ?: materializer.materialize(
            type = if (data is Entity<*>) data.type.get() else data.id,
            template = relationSchema.callstack,
            params = params
        )

        val localResult = evaluateCallstack(
            executionContext = executionContext,
            contextData = fromEntity(data),
            callstack = callstack,
            allowDeferring = false
        )

        if (localResult == null) return null

        return if (relationSchema.isSubtypeOf(TList.ID)) {
            val list = localResult as? TList<*> ?: listOf(localResult).toTList(context)
            @Suppress("UNCHECKED_CAST")
            list as Data
        } else {
            localResult
        }
    }

    private fun fromEntity(data: Data): Data {
        if (data !is Entity<*>) return data
        val typeId = data.type.get()
        val schema = schemaProvider.getSchema(typeId) as? CompositeSchema
            ?: error("Schema not found for type $typeId")

        val allFields = schema.values.toList()

        val fieldValues = allFields.map { field ->
            val fieldId = field.id.get()
            data.getFieldValue<Data?>(fieldId)
        }

        return context.dataFactory().createComposite(typeId, fieldValues)
    }
}

/**
 * Detect whether [data] refers to a database-backed array — either directly
 * (`ArrayDatabaseReference`) or wrapped behind a [DatabaseReference] whose
 * `getOperationContext()` is an [ArrayOperationContext].
 *
 * Single source of truth for the "is this an array reference?" question, asked by
 * [ReferenceResolver.resolveList] and `DbEntryMapper.writeValuesToDb` — two sites that
 * must not drift apart. Lives as a free function so consumers can call
 * it without taking a `ReferenceResolver` constructor dependency.
 *
 * Returns `null` for anything that is not array-shaped — callers decide what
 * that means (treat as non-list, fall through to a different `when` arm, etc.).
 */
fun arrayReferenceOrNull(data: Any?): ArrayDatabaseReference? = when (data) {
    is ArrayDatabaseReference -> data
    is DatabaseReference -> (data.getOperationContext() as? ArrayOperationContext)
        ?.arrayDatabaseReference
    else -> null
}
