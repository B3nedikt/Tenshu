package org.tenshu.core.collection.list

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.core.TContext
import org.tenshu.core.core.buildList
import org.tenshu.core.core.create
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.findOperation
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.relation.DynamicPropertyRelation
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.RelationArg
import org.tenshu.core.relation.RelationResolution
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.fieldRef
import org.tenshu.core.schema.list
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.of
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeOf
import org.tenshu.core.schema.typeVar
import org.tenshu.core.schema.unionSchema
import org.tenshu.core.toTBoolean

@Suppress("UNCHECKED_CAST")
fun <R : Data> TList<R>.select(
    selectors: ListBuilder<Selector<R>>.() -> Unit
): TList<Entity<R>> {
    val context = this as TContext
    val operation = findOperation<Select<R>>(Select.ID)
    return operation.select(context.buildList(selectors)) as TList<Entity<R>>
}

/**
 * **`Maybe<T>.select` — project a single (possibly-empty) entity in
 * place.**
 *
 * Mirrors [TList.select] for a single-value chain. The projection writes
 * the same `PendingOnDemandProperty` placeholders for the relations
 * named in the builder, deferring their materialisation to the terminal
 * op (`.get()`, `.flow()`). Empty Maybe propagates as empty — *not* an
 * error; that's the distinction from
 * `.ifEmptyError().select(...)` (which throws on empty).
 *
 * Engine-side, `SelectImplementation` already dispatches on
 * `caller: Maybe<*>` (see `SelectImplementation:65-70` — `selectFromMaybe`),
 * so no new engine-impl is needed; only the Kotlin chain shape +
 * registered RO + factory binding for [MaybeSelect.ID] in
 * `ImplementationFactory` and `CoreCommonModule` (both forwarded to
 * the existing `SelectImplementation`).
 *
 * See `docs/proposals/core-chain-entity-typing.md` §2.2 for the design.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Data> Maybe<T>.select(
    selectors: ListBuilder<Selector<T>>.() -> Unit
): Maybe<T> {
    val context = this as TContext
    val operation = findOperation<MaybeSelect<T>>(MaybeSelect.ID)
    return operation.select(context.buildList(selectors)) as Maybe<T>
}

/**
 * **`Entity<T>.select` — project a single (definitely-present) entity in
 * place.**
 *
 * The throw-on-empty companion to [Maybe.select], reached via
 * `.search { … }.ifEmptyError().select { … }`: `ifEmptyError` unwraps
 * `Maybe<Entity<T>>` → `Entity<T>`, this overload then keeps the chain
 * Entity-shaped through the projection.
 *
 * Engine-side, [SelectImplementation] picks up an `is Entity<*>` branch
 * (added alongside the Maybe branch) that funnels the caller through
 * the same `selectItem` projection used for list members. Both
 * [EntitySelect.ID] and [Select.ID] / [MaybeSelect.ID] share one
 * `SelectImplementation` instance — see `ImplementationFactory`.
 *
 * See `docs/proposals/core-chain-entity-typing.md` §2.2 for the design.
 */
@Suppress("UNCHECKED_CAST")
fun <T : Data> Entity<T>.select(
    selectors: ListBuilder<Selector<T>>.() -> Unit
): Entity<T> {
    val context = this as TContext
    val operation = findOperation<EntitySelect<T>>(EntitySelect.ID)
    return operation.select(context.buildList(selectors)) as Entity<T>
}

fun <T : Data> ListBuilder<Selector<T>>.fields(vararg fields: String) {
    addAll(fields.map {
        Selector.FieldSelector.create(
            context = this,
            fieldReference = fieldRef(it)
        )
    })
}

/**
 * Accepts bare [Property] handles *and* [eager]/[deferred] wrappers
 * ([RelationResolution]). A bare handle emits exactly the historical selector
 * bytes; a wrapper on a dynamic handle mints the
 * [Selector.DynamicPropertySelector.Resolved] wire shape (its own composite id,
 * so old peers only ever see it when the override feature is used). A wrapper
 * on a static handle degrades to the plain [Selector.PropertySelector] —
 * statics always resolve inline, there is nothing to override.
 */
fun <T : Data> ListBuilder<Selector<T>>.relations(vararg handles: RelationArg) {
    addAll(
        handles.map { arg ->
            val handle = when (arg) {
                is RelationResolution -> arg.handle
                is Property<*> -> arg
            }
            val forcedDeferred = (arg as? RelationResolution)?.deferred
            when (val selector = handle.selector()) {
                is DynamicPropertyRelation ->
                    if (forcedDeferred != null) {
                        Selector.DynamicPropertySelector.Resolved.create(
                            context = this,
                            dynamicPropertyReference = selector.reference,
                            params = selector.params,
                            deferred = forcedDeferred.toTBoolean(this),
                        )
                    } else {
                        Selector.DynamicPropertySelector.create(
                            context = this,
                            dynamicPropertyReference = selector.reference,
                            params = selector.params
                        )
                    }

                is StaticPropertyRelation -> Selector.PropertySelector.create(
                    context = this,
                    propertyReference = selector.reference
                )
            }
        }
    )
}

/**
 * **Nested projection.** Projects [handle] *and*, for each of its resolved element(s), the
 * fields/relations named in [nested] — `relations(child) { … }` recurses the same grammar to
 * any depth (`core-nested-projection.md`). Static (`Property.Value`) relations only for now;
 * nesting a dynamic relation throws (Slice 2.1).
 *
 * The nested-block receiver is `ListBuilder<Selector<Data>>` (concrete `Data`, **not** a
 * `Selector<*>` star projection): the `fields`/`relations` builders need a determinable
 * element type to resolve against this receiver. With a star projection they fail to bind
 * here and silently resolve against the *enclosing* select builder instead — flattening the
 * nested selectors to the top level (and resolving them against the wrong entity).
 */
fun <T : Data> ListBuilder<Selector<T>>.relations(
    handle: Property<*>,
    nested: ListBuilder<Selector<Data>>.() -> Unit,
) {
    val nestedSelectors = buildList(nested).toTList(this)
    when (val selector = handle.selector()) {
        is StaticPropertyRelation -> add(
            Selector.PropertySelector.create(
                context = this,
                propertyReference = selector.reference,
                nested = nestedSelectors,
            )
        )

        is DynamicPropertyRelation -> error(
            "Nested projection on the dynamic relation '${selector.reference.get()}' is not " +
                "supported yet (core-nested-projection.md Slice 2.1 covers static relations)."
        )
    }
}

sealed interface Selector<R : Data> : Composite {

    interface FieldSelector<R : Data> : Selector<R> {

        val fieldReference: FieldReference
            get() = getField(Field.REFERENCE)

        override val schema: CompositeSchema
            get() = Schema

        object Field {
            const val REFERENCE = "$ID/field"
        }

        companion object {

            const val ID = "tenshu/core/collection/selector/field"

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = Data.Schema,
                fields = {
                    composite(
                        id = Field.REFERENCE,
                        type = FieldReference.Schema
                    )
                }
            )

            fun <T : Data> create(
                context: TContext,
                fieldReference: FieldReference,
            ) = context.dataFactory().createComposite<FieldSelector<T>>(
                type = ID,
                fields = listOf(fieldReference)
            )
        }
    }

    interface PropertySelector<R : Data> : Selector<R> {

        val propertyReference: PropertyTypeReference
            get() = getField(Field.REFERENCE)

        /**
         * Optional nested projection for *this* relation's element(s) — the
         * `relations(handle) { … }` block. Loosely typed (`of = Data.Schema`) to
         * avoid a `Selector` ⊃ `Selector` schema init-cycle; the entries are child
         * [Selector]s. `null`/absent ⇒ no further projection (`core-nested-projection.md`).
         */
        val nested: TList<Selector<*>>?
            get() = getOptionalField(Field.NESTED)

        override val schema: CompositeSchema
            get() = Schema

        object Field {
            const val REFERENCE = "$ID/field"
            const val NESTED = "$ID/nested"
        }

        companion object {

            const val ID = "tenshu/core/collection/selector/property"

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = Data.Schema,
                fields = {
                    composite(
                        id = Field.REFERENCE,
                        type = PropertyTypeReference.Schema
                    )
                    list(
                        id = Field.NESTED,
                        of = Data.Schema,
                        isOptional = true,
                        itemVariance = Variance.Covariant
                    )
                }
            )

            fun <T : Data> create(
                context: TContext,
                propertyReference: PropertyTypeReference,
                nested: TList<Selector<*>>? = null,
            ) = context.create<PropertySelector<T>>(
                type = ID,
                fields = listOfNotNull(propertyReference, nested)
            )
        }
    }

    interface DynamicPropertySelector<R : Data> : Selector<R> {

        val dynamicPropertyReference: DynamicPropertyTypeReference
            get() = getField(Field.REFERENCE)

        val params: TList<Data>?
            get() = getOptionalField(Field.PARAMS)

        /**
         * Per-select resolution override: `null` ⇒ follow the property schema's
         * `deferredByDefault`; non-null ⇒ force (`true` = deferred, `false` =
         * eager). Non-null only on the [Resolved] wire shape — the base selector
         * never carries it (adding an optional field to THIS composite would
         * shift the positional wire for every dynamic select and desync old
         * peers; see `core-deferred-properties.md` §3.2 as-built).
         */
        val deferred: TBoolean?
            get() = null

        override val schema: CompositeSchema
            get() = Schema

        /**
         * Wire shape for an explicit [eager]/[deferred] override — a **separate
         * composite id** (not an optional field on the base selector) so plain
         * selects stay byte-identical and an old peer only ever encounters the
         * new type when a call site actually uses the override (ship lockstep;
         * the `Stream.Params.SIGNAL_ONLY` posture).
         */
        interface Resolved<R : Data> : DynamicPropertySelector<R> {

            override val dynamicPropertyReference: DynamicPropertyTypeReference
                get() = getField(Field.REFERENCE)

            override val params: TList<Data>?
                get() = getOptionalField(Field.PARAMS)

            override val deferred: TBoolean
                get() = getField(Field.DEFERRED)

            override val schema: CompositeSchema
                get() = Schema

            companion object {

                const val ID = "tenshu/core/collection/selector/dynamicPropertyResolved"

                object Field {
                    const val REFERENCE = "$ID/dynamicPropertyRef"
                    const val PARAMS = "$ID/params"
                    const val DEFERRED = "$ID/deferred"
                }

                val Schema: CompositeSchema = composite(
                    id = ID,
                    parent = Data.Schema,
                    fields = {
                        reference(
                            id = Field.REFERENCE,
                            type = DynamicPropertyTypeReference.Schema
                        )
                        list(
                            id = Field.PARAMS,
                            of = Data.Schema,
                            isOptional = true,
                            itemVariance = Variance.Covariant
                        )
                        boolean(id = Field.DEFERRED)
                    }
                )

                fun <T : Data> create(
                    context: TContext,
                    dynamicPropertyReference: DynamicPropertyTypeReference,
                    params: TList<Data>? = null,
                    deferred: TBoolean,
                ) = context.create<Resolved<T>>(
                    type = ID,
                    // Positional `listOf` — a null `params` must hold its slot so
                    // `deferred` maps to the third schema field.
                    fields = listOf(dynamicPropertyReference, params, deferred)
                )
            }
        }

        companion object {

            const val ID = "tenshu/core/collection/selector/dynamicProperty"

            object Field {
                const val REFERENCE = "$ID/dynamicPropertyRef"
                const val PARAMS = "$ID/params"
            }

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = Data.Schema,
                fields = {
                    reference(
                        id = Field.REFERENCE,
                        type = DynamicPropertyTypeReference.Schema
                    )
                    list(
                        id = Field.PARAMS,
                        of = Data.Schema,
                        isOptional = true,
                        itemVariance = Variance.Covariant
                    )
                }
            )

            fun <T : Data> create(
                context: TContext,
                dynamicPropertyReference: DynamicPropertyTypeReference,
                params: TList<Data>? = null
            ) = context.create<DynamicPropertySelector<T>>(
                type = ID,
                fields = listOfNotNull(dynamicPropertyReference, params)
            )
        }
    }

    companion object {

        const val ID = "tenshu/core/collection/selector"

        val Schema = unionSchema(
            id = ID,
            parent = Data.Schema,
            members = {
                listOf(
                    FieldSelector.Schema,
                    PropertySelector.Schema,
                    DynamicPropertySelector.Schema,
                    DynamicPropertySelector.Resolved.Schema
                )
            }
        )
    }
}

interface Select<R : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun select(selectors: List<Selector<R>>): Data

    companion object {
        const val ID = "tenshu/core/collection/list/select"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = listType("R"),
            typeParams = { type("R") },
            params = {
                list(
                    id = Params.SELECTORS,
                    of = Selector.Schema
                )
            },
            returnType = TList.typeOf(Entity.Schema)
        )
    }

    object Params {
        const val SELECTORS = "$ID/selectors"
    }
}

/**
 * Maybe-shaped sibling of [Select] — receiver is `Maybe<R>`, return is
 * also `Maybe<R>` (the existing entity gets its projection annotated
 * in place; the wrapper shape is preserved).
 *
 * Schema-wise it mirrors [Select.Schema] field-for-field except for the
 * `receiver` (changed from `listType("R")` to `maybeType("R")`) and the
 * `returnType` (changed from `TList.typeOf(Entity.Schema)` to
 * `Maybe.typeOf("R")`). The selectors parameter shape is identical so
 * `SelectImplementation` can apply the same `applyProjection` logic to
 * either receiver — the dispatch on `caller: Maybe<*>` already exists
 * (see `SelectImplementation:65-70` — `selectFromMaybe`).
 *
 * Both [Select.ID] and [MaybeSelect.ID] resolve to the same
 * `SelectImplementation` instance in `ImplementationFactory`; the
 * separation buys us the static-type difference at the chain level
 * without duplicating engine code.
 */
interface MaybeSelect<R : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun select(selectors: List<Selector<R>>): Data

    companion object {
        const val ID = "tenshu/core/maybe/select"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = maybeType("R"),
            typeParams = { type("R") },
            params = {
                list(
                    // Share `Select.Params.SELECTORS` (rather than a
                    // per-operation `$ID/selectors`) — the engine impl
                    // (`SelectImplementation`) is shared across all three
                    // *Select operations, and reads the selectors map by
                    // that single key. Diverging the param id by operation
                    // would silently miss the lookup and surface as a
                    // null Maybe/Entity at the next step. The schema
                    // validator accepts the shared id because the lookup
                    // is by string, not by which operation declared it.
                    id = Select.Params.SELECTORS,
                    of = Selector.Schema
                )
            },
            returnType = maybeType("R")
        )
    }
}

/**
 * Entity-shaped sibling of [Select] / [MaybeSelect]. Same
 * `SelectImplementation` engine instance (registered under all three
 * IDs in `ImplementationFactory`); schema differs only in the
 * `receiver` / `returnType` shape (both `Entity<R>` here).
 *
 * The selectors parameter is structurally identical to [Select.Params]
 * so the engine's `selectItem` can apply the same projection regardless
 * of which entry-point invoked it. See `docs/proposals/core-chain-entity-typing.md`
 * §2.2.
 */
interface EntitySelect<R : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun select(selectors: List<Selector<R>>): Data

    companion object {
        const val ID = "tenshu/core/entity/select"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = typeOf(Entity.Schema).of(typeVar("R")),
            typeParams = { type("R") },
            params = {
                // See [MaybeSelect.Schema] — shares `Select.Params.SELECTORS`
                // so the one engine impl (`SelectImplementation`) reads
                // selectors under a single key across all three shapes.
                list(
                    id = Select.Params.SELECTORS,
                    of = Selector.Schema
                )
            },
            returnType = typeOf(Entity.Schema).of(typeVar("R"))
        )
    }
}
