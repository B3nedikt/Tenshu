package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.EntityDto
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.relation.EntityRelationDescriptor
import org.tenshu.core.relation.ReferencePropertyDescriptor
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RoProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.LazyDataCreator
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params
import org.tenshu.core.collection.list.toList
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

@OptIn(ExperimentalAtomicApi::class)
class SchemaRegistry(
    private val tContextProvider: () -> TContext
) : SchemaProvider, DataFactory, RoProvider, OperationResolver, LazyDataMaterializer {

    val tContext by lazy { tContextProvider() }
    private val configs: MutableList<ModuleConfiguration> = mutableListOf()

    /** Lazily-built by-bytes type-id index; dropped on every [registerConfigImpl]. */
    private val typeIdIndexRef = AtomicReference<Map<TypeIdKey, String>?>(null)

    /**
     * Materialised [LazyComposite]s are cached by their creator instance. Reads
     * dominate writes — most lookups hit an already-populated cache — and the
     * race window is the first concurrent miss for a given creator from two
     * `Dispatchers.Default` workers (the SystemTest setup that surfaced the
     * `DatabaseImpl` lock-DI flake also hits this path). Lock-free CAS keeps
     * the read path allocation-free while making the rare miss-race benign:
     * both threads materialize a fresh instance, but only the winner's
     * instance is cached and both callers return it (see [getOrCreate]).
     *
     * `materialize` is pure (the DSL builders construct an object graph from
     * the captured config — no external side effects), so the worst case
     * under contention is one wasted allocation, never a correctness bug.
     */
    private val materializationCache =
        AtomicReference<Map<LazyDataCreator<*>, Composite>>(emptyMap())

    override fun <T : Composite> getOrCreate(creator: LazyDataCreator<T>): T {
        materializationCache.load()[creator]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as T
        }
        val materialised = creator.materialize(tContext)
        while (true) {
            val current = materializationCache.load()
            current[creator]?.let {
                // Another thread won the CAS while we were materialising —
                // discard our instance and return the cached one so the
                // single-instance contract on [LazyDataMaterializer] holds.
                @Suppress("UNCHECKED_CAST")
                return it as T
            }
            if (materializationCache.compareAndSet(current, current + (creator to materialised))) {
                return materialised
            }
        }
    }

    private fun constantFactory(typeId: String): Data {
        return configs.asReversed().firstNotNullOfOrNull { it.constantFactories[typeId] }
            ?.invoke()
            ?: error("No constant factory registered for type: $typeId")
    }

    private fun valueFactory(typeId: String, value: Any): Data {
        return configs.asReversed().firstNotNullOfOrNull { it.valueFactories[typeId] }
            ?.invoke(value)
            ?: error("No value factory registered for type: $typeId")
    }

    private fun compositeFactory(typeId: String, fields: List<Data?>): Data {
        return configs.asReversed().firstNotNullOfOrNull { it.compositeFactories[typeId] }
            ?.invoke(fields)
            ?: error("No composite factory registered for type: $typeId")
    }

    private fun requestObjectFactory(
        type: Type,
        requestChain: RequestChain,
        context: TContext
    ): Data {
        val typeId = type.baseSchema?.id?.get()
            ?: error("Cannot resolve request-object factory for non-base type: $type")
        return configs.asReversed().firstNotNullOfOrNull { it.requestObjectFactories[typeId] }
            ?.invoke(requestChain, context, type)
            ?: error("No request object factory registered for type: $typeId")
    }

    /**
     * Adds [config] (and, depth-first, every module in its [ModuleConfiguration.prerequisites]
     * chain) to the registry. Idempotent: a module already registered — including
     * transitively via another module's prerequisites — is skipped.
     *
     * Order: each prerequisite is fully registered before [config] itself, so a
     * dependency graph A→B→C ends up as `configs = [C, B, A]`. Lookup
     * (`firstNotNullOfOrNull(asReversed())`) then makes A's entries win on
     * collision, matching the "last-registered wins" rule from the previous
     * eager-merge semantics.
     *
     * Cycles in the prerequisite graph are detected and rejected with an
     * actionable error — protects against the third-party-plugin future where
     * cross-imports could otherwise produce a stack overflow.
     */
    fun registerConfig(config: ModuleConfiguration) {
        registerConfigImpl(config, inProgress = mutableSetOf())
        cachedRelationSpecs = null
        generation++
    }

    /**
     * Moves on every [registerConfig], so a cache built over this registry (e.g.
     * `ContentDeclarationsCache`) can tell it has gone stale without re-walking it.
     */
    var generation: Int = 0
        private set

    private fun registerConfigImpl(
        config: ModuleConfiguration,
        inProgress: MutableSet<ModuleConfiguration>,
    ) {
        if (config in configs) return
        check(config !in inProgress) {
            "Cycle detected in ModuleConfiguration prerequisites: ${
                (inProgress + config).joinToString(" → ") { it::class.simpleName ?: "?" }
            }"
        }
        inProgress += config
        try {
            for (prereq in config.prerequisites) {
                registerConfigImpl(prereq, inProgress)
            }
        } finally {
            inProgress -= config
        }
        configs.add(config)
        // ⚠ A new config can add — or shadow — type mappings, so the index must be rebuilt. Drop
        // rather than patch: registration is rare and correctness here is worth more than the
        // rebuild.
        typeIdIndexRef.store(null)
    }

    override fun getSchema(type: String): DataSchema? {
        return configs.asReversed().firstNotNullOfOrNull { it.schemaMapping[type] }
    }

    /**
     * Flat view of every schema registered across every [ModuleConfiguration].
     * Used by introspection tests (e.g. `SchemaLazyFieldsCompletenessTest`).
     * Duplicates across configs are not de-duplicated — later registrations
     * shadow earlier ones at lookup time, but for completeness checks each
     * registered schema is exercised in isolation.
     */
    fun allSchemas(): Collection<DataSchema> =
        configs.flatMap { it.schemaMapping.values }

    /**
     * Every registered relation as `(receiverTypeId, relationId)` — the map the
     * property/relation DSL writes (`registerValueProperty`,
     * `registerDynamicPropertyRelation`, `registerPropertyRelation` and the
     * service-relation registrations all land here).
     *
     * Exposed for [org.tenshu.core.common.schema.migration.PropertyKindBaseline],
     * which needs the *set of ids that can appear as a property slot in a stored
     * row* — a question `allSchemas()` cannot answer, because a schema id alone
     * does not say whether anything ever stores a slot under it.
     */
    fun registeredRelations(): List<Pair<String, String>> =
        configs.flatMap { cfg ->
            cfg.relations.entries.mapNotNull { (key, value) ->
                key.takeIf { it.size == 2 }?.let { it[0] to value }
            }
        }

    /**
     * The registered module graph's [ServiceSchema]s — the input to the
     * schema-version vector walk (`core-schema-evolution.md` §4.1.1). The
     * DSL-declared instances are returned as registered, so their
     * never-serialized [ServiceSchema.version] metadata is intact.
     */
    override fun serviceSchemas(): Collection<ServiceSchema> =
        allSchemas().filterIsInstance<ServiceSchema>()

    /**
     * Every [SchemaMigration][org.tenshu.core.common.schema.migration.SchemaMigration]
     * registered across the module graph (`core-schema-evolution.md` §8.4) —
     * the input to the derived per-service floor
     * (`SchemaProvider.currentVersionFloors`) and, at E2b+, the store-open /
     * history-upcast seams. Registration order across configs is preserved;
     * ordering within a service is by `fromVersion`, enforced at
     * [org.tenshu.core.common.schema.migration.SchemaMigrationRegistry.verify].
     */
    fun schemaMigrations(): List<org.tenshu.core.common.schema.migration.SchemaMigration> =
        configs.flatMap { it.schemaMigrations }

    /**
     * Every relation declared across the module graph
     * (`ModuleConfiguration.registerRelationMaintainer`,
     * `core-relation-system.md` §9.2) — the input to
     * [org.tenshu.core.common.relation.RelationRegistry], which indexes them by
     * element type for the one generic
     * [org.tenshu.core.common.relation.RelationMaintainer]. Registration order
     * across configs is preserved.
     *
     * Memoised (invalidated by [registerConfig]) because R2 gave it a *read*-path
     * caller: `ExecutionContext.relationReference` derives a reader's array
     * reference from the same spec the maintainer writes through (D4), and
     * per-request `ExecutionContext`s would otherwise re-walk the whole module
     * graph on every op.
     */
    fun relationSpecs(): List<org.tenshu.core.common.relation.RelationSpec> =
        cachedRelationSpecs ?: configs.flatMap { it.relationSpecs }
            .also { cachedRelationSpecs = it }

    private var cachedRelationSpecs: List<org.tenshu.core.common.relation.RelationSpec>? = null

    /**
     * The [ServiceSchema] id that *owns* [typeId], or `null` when the type is
     * unattributable — the mapping the realm-epoch bump needs to turn "which
     * types did this write contain?" into "which services' epoch entries must
     * rise?" (`core-schema-evolution.md` §4.1.3 / §7.2).
     *
     * **The rule** (mechanical, derived, nothing declared twice): the service
     * registered in the same [ModuleConfiguration] as the type — a module *is*
     * the release unit that carries both a service's schemas and its version
     * (§4). Config selection follows ordinary lookup precedence
     * (last-registered wins, as [getSchema]); within a config the
     * **first-declared** service is chosen.
     *
     * **Its limits, stated rather than hidden** — this is a derived
     * approximation, not an index:
     *  - A module registering **several** services attributes *all* its types
     *    to the first (`:file-service-common` → `FileService`, not
     *    `FileSystem`; `:sync-service-common` → `SyncService`, not
     *    `ScheduleService`/`TaskService`). The error direction is
     *    over-attribution: a sibling service's epoch entry may rise on a write
     *    that carried none of its types, which can only make the fence *more*
     *    conservative — never less — and the siblings ship as one unit anyway.
     *  - `:core`'s own composites attribute to `AppService`, the single service
     *    `coreCommonModule` registers, rather than to the core version slot
     *    §4 describes. Inert in practice: `AppService` is the frozen-forever
     *    announce channel (§6.2), pinned at v1, so its entry never fences
     *    anyone. Core shape changes still fence through `coreVersion`.
     *  - A type registered by a module with **no** service (test fixtures,
     *    `:core`-only registries) returns `null` — unattributable, and so
     *    covered by the global core floor.
     *
     * The drift-free answer is a real per-type→service index, which arrives
     * only if the module system's descriptive half graduates to composites
     * (§4.2 future track — a `ModuleDescriptor`). Until then this is the
     * honest mechanism.
     */
    fun owningServiceOf(typeId: String): String? =
        configs.asReversed()
            .firstOrNull { typeId in it.schemaMapping }
            ?.schemaMapping
            ?.values
            ?.firstNotNullOfOrNull { it as? ServiceSchema }
            ?.id
            ?.get()

    override fun getTrait(type: String, trait: String): Trait? {
        val traitSchema = getSchema(trait) as? TraitSchema ?: return null
        return configs.asReversed().firstNotNullOfOrNull { it.traitFactories[type to trait] }
            ?.invoke(tContext, traitSchema)
    }

    override fun getTrait(type: String, traitSchema: TraitSchema): Trait? {
        val traitId = traitSchema.id.get()
        return configs.asReversed().firstNotNullOfOrNull { it.traitFactories[type to traitId] }
            ?.invoke(tContext, traitSchema)
    }

    /**
     * Type ids of every registered schema that **declares** the [RealmData] marker trait
     * (proposal `docs/proposals/core-realm-data.md` §2.3 / §3.2), in registration order.
     *
     * "Declared, not inherited" (§2.4): a schema declares the trait iff [RealmData] is in its
     * own `traits` list but **not** its parent's — so `Folder` (whose schema parents `File`)
     * inherits `RealmData` for resolution yet folds into File's single entry rather than
     * producing a duplicate. Presentation + ordering are the consumer's concern (§8), so the
     * ids are returned unsorted (in config-registration order).
     *
     * The walk is linear and only runs when a consumer opens the Data section, so no reverse
     * index is maintained (§2.3; the memoised map is an available Phase 6 optimisation).
     */
    override fun realmDataTypeIds(): List<String> {
        val ids = LinkedHashSet<String>()
        for (schema in allSchemas()) {
            if (schema is CompositeSchema && declaresRealmData(schema)) {
                ids += schema.id.get()
            }
        }
        return ids.toList()
    }

    /**
     * Whether [schema] *declares* [RealmData] directly — present in its own `traits` list but
     * not inherited from a parent (so sub-types fold into their parent, not duplicate; §2.4).
     */
    private fun declaresRealmData(schema: CompositeSchema): Boolean {
        val ownDeclares = schema.traits.toList().any { it.isSubtypeOf(RealmData.ID) }
        if (!ownDeclares) return false
        return schema.parent?.hasTrait(RealmData.ID) != true
    }

    override fun getServiceDataRelation(
        service: ServiceTypeReference,
        ref: Reference,
    ): org.tenshu.core.relation.ServiceDataRelation? {
        return configs.asReversed().firstNotNullOfOrNull { it.serviceDataRelations[service to ref] }
    }

    /**
     * Reverse of [getServiceDataRelation]: every registered **collection-shaped**
     * `ServiceDataRelation` whose `TList<E>` element type is [typeId] or a sub-type
     * of it (`docs/proposals/control-data-reset.md` §4).
     *
     * This is the *only* route from "a type" to "its rows" — there is no type-wide
     * enumeration in [Database]; a row is reachable only through the array that holds
     * it. What is returned is the relation's **lookup key**, not a resolved reference,
     * so the caller hands it straight to `ExecutionContext.resolveServiceData` and
     * gets the very same `ArrayDatabaseReference` an ordinary chain read builds —
     * see [ServiceDataArrayBinding][org.tenshu.core.relation.ServiceDataArrayBinding].
     *
     * Like [realmDataTypeIds], **no reverse index is maintained** — the walk is linear
     * over the registered configs and only runs when a consumer resets a data type,
     * which is rare and user-initiated. De-duplicated by key so a relation shadowed by
     * a later config (the ordinary last-registered-wins rule) is visited once.
     */
    override fun serviceDataArraysHolding(
        typeId: String,
    ): List<org.tenshu.core.relation.ServiceDataArrayBinding> {
        val seen = LinkedHashMap<Pair<ServiceTypeReference, Reference>, org.tenshu.core.relation.ServiceDataArrayBinding>()
        for (config in configs.asReversed()) {
            for ((key, relation) in config.serviceDataRelations) {
                if (key in seen) continue
                if (relation.type.baseSchema?.id?.get() != TList.ID) continue
                val elementTypeId = relation.type.params.toList().firstOrNull()
                    ?.type?.baseSchema?.id?.get()
                    ?: continue
                if (elementTypeId != typeId &&
                    getSchema(elementTypeId)?.isSubtypeOf(typeId) != true
                ) continue
                seen[key] = org.tenshu.core.relation.ServiceDataArrayBinding(
                    service = key.first,
                    ref = key.second,
                    elementTypeId = elementTypeId,
                )
            }
        }
        return seen.values.toList()
    }

    override fun getServiceRelation(from: List<String>): String? {
        return configs.asReversed().firstNotNullOfOrNull { it.relations[from] }
    }

    override fun getReferenceProperty(
        propertyId: String,
    ): ReferencePropertyDescriptor? {
        return configs.asReversed().firstNotNullOfOrNull { it.referenceProperties[propertyId] }
    }

    override fun getEntityRelation(
        propertyId: String,
    ): EntityRelationDescriptor? {
        return configs.asReversed().firstNotNullOfOrNull { it.entityRelations[propertyId] }
    }

    /**
     * Resolves the `(typeId, requirementId)` value-requirement binding (e.g. `File.CreatedAt`
     * for `RealmData.CREATED_AT`), walking [typeId]'s parent chain so a sub-type inherits its
     * parent's binding (`Folder` resolves via `File`). See `core-realm-data.md` §8.
     */
    override fun resolveValueRequirement(
        typeId: String,
        requirementId: String,
    ): org.tenshu.core.relation.Property.Value<*>? {
        var t: String? = typeId
        while (t != null) {
            val key = t to requirementId
            configs.asReversed().firstNotNullOfOrNull { it.valueRequirementBindings[key] }
                ?.let { return it }
            t = (getSchema(t) as? CompositeSchema)?.parent?.id?.get()
        }
        return null
    }

    override fun <T : TConstant> createConstant(type: String): T {
        @Suppress("UNCHECKED_CAST")
        return constantFactory(type) as T
    }

    override fun <T : TValue> createValue(type: String, value: Any): T {
        @Suppress("UNCHECKED_CAST")
        return valueFactory(type, value) as T
    }

    override fun <T : Data> createList(values: List<Data?>): TList<T> {
        @Suppress("UNCHECKED_CAST")
        return TListDto(values as List<T>)
    }

    override fun <T : Composite> createComposite(type: String, fields: List<Data?>): T {
        @Suppress("UNCHECKED_CAST")
        return compositeFactory(type, fields) as T
    }

    override fun <T : Composite> createEntity(
        type: String,
        fields: Map<String, Data>,
        relations: Map<String, Data>
    ): Entity<T> {
        return EntityDto(
            type = getReference(type),
            fields = fields,
            properties = relations
        )
    }

    override fun <T : Data> createRequestObject(
        type: Type,
        requestChain: RequestChain,
        context: TContext
    ): T {
        @Suppress("UNCHECKED_CAST")
        return requestObjectFactory(type, requestChain, context) as T
    }

    override fun <T : Operation> findOperation(
        operationId: String,
        requestChain: RequestChain,
        context: TContext
    ): T? {
        // Phase 4.2 of `docs/adr/0009-operation-schema-typing.md`: the receiver type id is read
        // from the operation schema; the deleted two-arg overload's parent-chain walk is
        // inlined here. Falls back to `Operation.ID` for top-level / receiver-less ops
        // (services dispatched without an implicit caller).
        val schema = getSchema(operationId) as? org.tenshu.core.schema.OperationSchema
        var currentType: String? = schema?.receiver?.baseSchema?.id?.get()
            ?: org.tenshu.core.Operation.ID
        while (currentType != null) {
            val factory = configs.asReversed().firstNotNullOfOrNull { config ->
                config.operations[currentType]?.get(operationId)
            }
            if (factory != null) {
                @Suppress("UNCHECKED_CAST")
                return factory.invoke(requestChain, context) as T
            }
            currentType = (getSchema(currentType) as? CompositeSchema)?.parent?.id?.get()
        }
        return null
    }

    /**
     * Wire type-id bytes → type id.
     *
     * ⚠⚠ **This was a linear scan over every registered mapping, on a path the parser hits once
     * per nested value it decodes.** Measured on a real iPhone 2026-08-05: **1,307 entries scanned
     * and ~390 µs per call, 80,000 calls in 200 s ⇒ 31.7 s — ~16% of wall-clock**, and not only
     * during sync: every parse pays it, so every IPC call and every chain read did too.
     *
     * The scan existed because `ByteArray` cannot be a map key — its `equals` is reference-based —
     * so `typeMapping` has to be searched with `contentEquals`. [TypeIdKey] gives the bytes
     * value-semantics, which turns 1,307 comparisons into one hash lookup.
     */
    override fun getTypeId(type: ByteArray): String =
        typeIdIndex()[TypeIdKey(type)]
            ?: error("No type id registered for bytes: ${type.toHexString()}")

    /**
     * The index, built on first use and dropped whenever a config is registered.
     *
     * ⚠⚠ **Precedence is load-bearing and inverted from the loop it replaces.** The old code was
     * `configs.asReversed().firstNotNullOfOrNull { … }` — i.e. the **last-registered** config wins
     * a duplicate. Building the map in forward order with plain overwrite reproduces exactly that
     * (later writes clobber earlier ones). Building it reversed would silently resolve a duplicated
     * type id to the *wrong* type, and nothing about the failure would point here.
     */
    private fun typeIdIndex(): Map<TypeIdKey, String> {
        typeIdIndexRef.load()?.let { return it }
        val built = HashMap<TypeIdKey, String>()
        // Forward order + overwrite == asReversed().first — see the KDoc above.
        configs.forEach { config ->
            config.typeMapping.forEach { (bytes, id) -> built[TypeIdKey(bytes)] = id }
        }
        // A CAS race costs one duplicate build, never a wrong answer — the `getOrCreate` pattern.
        typeIdIndexRef.compareAndSet(null, built)
        return typeIdIndexRef.load() ?: built
    }

    /** [ByteArray] with value semantics, so it can be a hash-map key. */
    private class TypeIdKey(private val bytes: ByteArray) {
        // ⚠ Precomputed: this is the whole point — rehashing per lookup would give back the cost.
        private val hash = bytes.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is TypeIdKey && bytes.contentEquals(other.bytes)
    }

    override fun getTypeId(kClass: KClass<Data>): String {
        return configs.asReversed().firstNotNullOfOrNull { it.classToTypeMapping[kClass] }
            ?: error("No type id registered for class: ${kClass.simpleName}")
    }

    override fun getReference(type: String): Reference {
        return configs.asReversed().firstNotNullOfOrNull { it.referenceMapping[type] }
            ?: error("No reference registered for type: $type")
    }
}
