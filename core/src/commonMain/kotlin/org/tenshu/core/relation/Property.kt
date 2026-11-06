package org.tenshu.core.relation

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.dynamicPropertyRef
import org.tenshu.core.schema.dynamicPropertyRelation
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.schema.typeOf
import org.tenshu.core.trait.TraitSchema

/**
 * A typed handle for a **property** of a composite — one object that is the
 * single source of truth for that property across its whole lifecycle:
 *
 *  - **declaration** — `valueProperty(fromId, handle) { … }` /
 *    `valueProperty(fromId, handle)` / `dynamicProperty(fromId, handle)`
 *    registers the schema, (optional) value factory, and property relation;
 *  - **writing** — `Entity.create(ctx, composite) { set(handle, value) }`
 *    (static value handles only);
 *  - **reading** — `entity.get(handle)` (via [decode]);
 *  - **projecting** — `select { relations(handle) }` (via [selector]);
 *  - **sorting** — `list.sortBy(handle)` (static value handles only).
 *
 * There are exactly **two** kinds (`docs/adr/0013-property-authoring-unification.md`
 * §3–§4): [Value] (a stored scalar OR a constant/singleton — both resolve to a
 * `StaticPropertyRelation` at the call site) and [Dynamic] (a chain-computed property,
 * optionally param-bearing). Developers never construct a handle directly — the
 * DSL builders ([valueProperty] / [dynamicProperty]) return it; it then appears in
 * `get` / `set` / `relations` / `sortBy` signatures, which is why it takes the
 * prime, dev-facing name `Property` (`entity.get(File.Size)`,
 * `relations(File.Category)`, `sortBy(File.LastModifiedAt)`). The call-site **wire**
 * composites it mints are the relation family `PropertyRelation` /
 * `StaticPropertyRelation` / `DynamicPropertyRelation` (`relation/PropertyRelation.kt`).
 *
 * [T] is the stored [Data] value type ([decode] casts the resolved cell to it,
 * null-tolerantly — a `Dynamic` OnDemand placeholder collapses to `null`).
 * Reads return the [Data] value; writes take the *raw* Kotlin value and encode
 * through the registered value factory (the read-`TValue` / write-raw asymmetry
 * that lets [valueProperty] stay generic — §7).
 */
sealed interface Property<T : Data> : RelationArg {

    val id: String

    /** Resolved stored [Data] cell -> typed value; `null`-tolerant (§7.1). */
    fun decode(data: Data?): T?

    /**
     * The call-site wire composite (`StaticPropertyRelation` / `DynamicPropertyRelation`) this
     * property projects/sorts as. [params] override any params bound on the
     * handle (e.g. `File.Preview(w, h)`); ignored by the [Value] kind.
     */
    fun selector(params: List<Data> = emptyList()): PropertyRelation

    /**
     * Static property — any `TValue`/`TConstant`: a stored scalar (`File.Size`)
     * OR a constant/singleton (`File.Category`). Both resolve to a
     * `StaticPropertyRelation`; [decode] is a cast to the value's type. Built by
     * [valueProperty]. [schema] is the value's schema — a `ValueSchema` for the
     * op-written scalar case (registration mints a per-property `ValueSchema`
     * parented to it) or the existing schema for the constant case (registration
     * registers it as-is, no value factory).
     */
    class Value<T : Data>(
        override val id: String,
        val schema: DataSchema,
        private val decoder: (Data?) -> T?,
    ) : Property<T> {

        override fun decode(data: Data?): T? = decoder(data)

        override fun selector(params: List<Data>): PropertyRelation =
            StaticPropertyRelation.create(reference = propertyRef(id))
    }

    /**
     * Dynamic property — chain-computed, optionally param-bearing. Wraps the
     * existing [RelationSchema.DynamicPropertyRelationSchema] (carrying the
     * `ChainTemplate`); [boundParams] hold params bound via [withParams] (the
     * `File.Preview(w, h)` sugar). [decode] casts the resolved chain result and
     * preserves the OnDemand null-collapse. Built by [dynamicProperty].
     */
    class Dynamic<T : Data>(
        override val id: String,
        val relationSchema: RelationSchema.DynamicPropertyRelationSchema,
        val boundParams: List<Data> = emptyList(),
        private val decoder: (Data?) -> T?,
    ) : Property<T> {

        override fun decode(data: Data?): T? = decoder(data)

        override fun selector(params: List<Data>): PropertyRelation {
            val effective = params.ifEmpty { boundParams }
            return DynamicPropertyRelation.create(
                reference = dynamicPropertyRef(id),
                params = effective.takeIf { it.isNotEmpty() }?.let { SimpleTList(it) },
            )
        }
    }
}

/**
 * What `select { relations(…) }` accepts: a bare [Property] handle (resolution
 * follows the property's schema default) or a [RelationResolution] wrapper
 * ([eager]/[deferred]) forcing per-select resolution
 * (`docs/proposals/core-deferred-properties.md` §3.2).
 */
sealed interface RelationArg

/**
 * A [Property] handle with an explicit per-select resolution override — built
 * by [eager] / [deferred], consumed by `relations(…)`, which mints the
 * `Selector.DynamicPropertySelector.Resolved` wire shape for it.
 */
class RelationResolution internal constructor(
    val handle: Property<*>,
    val deferred: Boolean,
) : RelationArg

/**
 * Force **inline** resolution of [handle] in this projection, overriding a
 * `deferred = true` schema default — e.g. a one-shot `.get()` that genuinely
 * wants the expensive property materialised before returning
 * (`relations(eager(File.Preview(100, 100)))`).
 *
 * Accepts any handle kind: on a static [Property.Value] it is a no-op (statics
 * always resolve inline), so callers can wrap uniformly.
 */
fun eager(handle: Property<*>): RelationResolution = RelationResolution(handle, deferred = false)

/**
 * Force **deferral** of [handle] in this projection: the row emits immediately
 * with the property as a pending placeholder (`null` through [Property.decode]);
 * a streaming terminal patches the row when the proxy resolves it, a one-shot
 * terminal leaves it `null` (`core-deferred-properties.md` §4).
 *
 * [Property.Dynamic] only — static value properties resolve in microseconds and
 * have no schema carrier for deferral; deferring them buys nothing.
 */
fun deferred(handle: Property.Dynamic<*>): RelationResolution = RelationResolution(handle, deferred = true)

/**
 * Builds a static [Property.Value] for **any** `TValue`/`TConstant` from
 * its [id] and value [schema] — the generalisation of the old per-type value
 * factories *and* `referenceProperty` into one builder (§7.3). [decode] is a
 * null-tolerant cast to [T]:
 *
 *  - op-written scalar (`File.Size`): `schema` is the base `ValueSchema`
 *    (`TInteger.Schema`, …) and the registration supplies a value factory;
 *  - constant / singleton (`File.Category`): `schema` is the value's own schema
 *    (`FileCategory.Schema`) and the registration supplies no factory.
 */
inline fun <reified T : Data> valueProperty(
    id: String,
    schema: DataSchema,
): Property.Value<T> = Property.Value(
    id = id,
    schema = schema,
    decoder = { it as? T },
)

/**
 * Builds a static [Property.Value] for an **entity relation** — a list-valued
 * property `TList<E>` whose elements live in a per-entity array keyed by the
 * receiver's own `DataId` (`docs/proposals/core-nested-projection.md` §3.3). The
 * handle projects/sorts as a `StaticPropertyRelation` like any value property; the
 * list-ness + per-entity keying is a proxy-side resolution detail registered via
 * `entityRelation(fromId, property)` in the module DSL, which reads [element]'s id
 * as the [EntityRelationDescriptor.elementTypeId].
 *
 * [decode] casts the resolved value (a projected `TList<Entity<E>>`) to `TList<E>`
 * null-tolerantly. [element] is the array's **element** schema (e.g.
 * `FileModification.Schema`), not the list type — the handle's value type is the
 * list, but the descriptor needs the element id.
 */
fun <E : Data> entityRelation(
    id: String,
    element: CompositeSchema,
): Property.Value<TList<E>> = Property.Value(
    id = id,
    schema = element,
    decoder = {
        @Suppress("UNCHECKED_CAST")
        (it as? TList<E>)
    },
)

/**
 * Navigate from a chain entity into a list-valued static property — appends a
 * `PropertyTypeReference` step to the chain and returns a `TList<E>` chain RO,
 * ready for the usual chain ops (`.filter`/`.search`/`.sortBy`/`.select`/`.flow()`).
 *
 * The list-FIELD analogue is `Entity<Folder>.files` (a chain navigation of the FILES
 * field); this is its counterpart for a list **property-relation** (e.g.
 * `File.FileHistory` — the `entityRelation` primitive). The engine resolves the step
 * via `resolveReference → resolveProperty → resolveEntityRelation`, yielding an
 * `ArrayDatabaseReference` that streams reactively under `.flow()` like any other
 * list-relation chain (e.g. `fileService.modifications`).
 *
 * Shape-typed to `Property.Value<TList<E>>` — works for every present and future
 * list-valued static property. Other static-property kinds (scalar values, future
 * map relations) would add their own overloads when consumers want them as chain
 * steps; the underlying mechanism (`PropertyTypeReference` step → engine resolves)
 * is the same.
 *
 * Only valid on a chain RO (a [RequestObject]) — calling this on a materialised
 * [Entity] errors. Mirrors `Entity<T>.select` / `findOperation`'s established cast.
 */
fun <T : Data, E : Data> Entity<T>.relation(
    handle: Property.Value<TList<E>>,
): TList<E> {
    val ro = this as? RequestObject
        ?: error("Entity.relation requires a chain RO (RequestObject); was called on $this")
    val context = ro.context
    // The list element type comes from the registered `EntityRelationDescriptor`,
    // not `handle.schema` — handles for OTHER list-typed property kinds wouldn't
    // necessarily carry the element schema directly. This also asserts the handle is
    // an entity-relation (the chain-navigable kind today).
    val descriptor = context.schemaProvider().getEntityRelation(handle.id)
        ?: error("Entity.relation: ${handle.id} is not a registered entity relation; only entityRelation handles are chain-navigable today")
    val element = context.schemaProvider().getSchema(descriptor.elementTypeId) as? CompositeSchema
        ?: error("Entity.relation: element schema ${descriptor.elementTypeId} not registered as a composite")

    // `RequestChain.addReference` returns a chain with the step appended — the
    // contract supports both mutate-in-place (`SimpleRequestChain`) and immutable
    // copy (`LocalRequestChain`); the caller must use the returned chain, not the
    // receiver.
    val nextChain = ro.requestChain.addReference(handle.id)
    @Suppress("UNCHECKED_CAST")
    return context.roProvider().createRequestObject(
        type = TList.typeOf(element),
        requestChain = nextChain,
        context = context,
    ) as TList<E>
}

/**
 * Builds a [Property.Dynamic] from a chain template. [receiver] is the
 * composite the chain reads from (used to materialise the param-independent
 * chain eagerly at schema-construction; param-bearing chains defer to resolve
 * time). [decode] casts the resolved chain result to [T] null-tolerantly so an
 * unresolved OnDemand placeholder collapses to `null`.
 *
 * [deferred] marks the property **deferred by default** in projections
 * (`core-deferred-properties.md` §3.1): selects emit the row with a pending
 * placeholder instead of resolving inline; `eager(handle)` overrides per select.
 * Set it on any property whose resolver does real IO (`File.Preview`).
 */
inline fun <reified R : Composite, reified T : Data> dynamicProperty(
    id: String,
    receiver: CompositeSchema,
    params: List<DataSchema>? = null,
    traits: List<TraitSchema> = emptyList(),
    deferred: Boolean = false,
    noinline block: R.(params: List<Any>) -> T,
): Property.Dynamic<T> = Property.Dynamic(
    id = id,
    relationSchema = dynamicPropertyRelation(
        id = id,
        params = params,
        receiver = receiver,
        callstack = ChainTemplate(block),
        traits = traits,
        deferred = deferred,
    ),
    decoder = { it as? T },
)

/**
 * Returns a copy of this dynamic handle with [params] bound — the engine behind
 * `File.Preview(w, h)`-style param sugar. The bound params are emitted by
 * [Property.Dynamic.selector] when `relations(handle)` projects it.
 */
fun <T : Data> Property.Dynamic<T>.withParams(
    vararg params: Data,
): Property.Dynamic<T> = Property.Dynamic(
    id = id,
    relationSchema = relationSchema,
    boundParams = params.toList(),
    decoder = { decode(it) },
)
