package org.tenshu.core.schema

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.core.dataFactory
import org.tenshu.core.toTString
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema

sealed interface RelationSchema : CompositeSchema {

    override val schema: CompositeSchema
        get() = Schema

    interface PropertyRelationSchema : RelationSchema {

        companion object {
            const val ID = "${RelationSchema.ID}/property"
            val Schema: PropertyRelationSchema = propertyRelation(
                id = ID
            )
        }
    }

    interface DynamicPropertyRelationSchema : PropertyRelationSchema {

        val params: TList<DataSchema>?
            get() = getOptionalField(Fields.PARAMS)

        val callstack: ChainTemplate<*, *>

        /**
         * Materialised chain produced by invoking [callstack] against a fresh receiver
         * RO once per `(LazyDataCreator, registry)` cache miss. Populated only when the
         * relation declares no runtime [params] AND the DSL builder was given a
         * `receiver` schema — in those cases the chain is param-independent and can be
         * captured at first schema-materialisation.
         *
         * Null when (a) the relation declares runtime [params] (chain bakes runtime
         * values, can't be eager — `File.Relation.Preview(w, h)` style), (b) the DSL
         * builder didn't receive a `receiver` schema, or (c) the schema was deserialised
         * from the wire (no chain field on the wire format). In these cases
         * `resolveDynamicProperty` falls back to invoking [callstack] via
         * `CallstackMaterializer` at resolve time.
         */
        val chain: TList<Data>?
            get() = getOptionalField(Fields.CHAIN)

        /**
         * Whether projections of this property are **deferred by default** — the
         * select emits the row immediately with a pending placeholder instead of
         * resolving the property inline (`docs/proposals/core-deferred-properties.md`).
         * Per-select `eager(...)`/`deferred(...)` wrappers override it.
         *
         * Code-side metadata like [callstack], **never a wire field**: composite
         * fields serialise positionally (an added optional field desyncs old peers
         * on every dynamic select), and only the proxy's own registration is ever
         * consulted — a schema deserialised from the wire defaults to eager.
         */
        val deferredByDefault: Boolean
            get() = false

        companion object {
            const val ID = "${RelationSchema.ID}/dynamicProperty"
            val Schema: CompositeSchema = composite(
                id = ID,
                parent = PropertyRelationSchema.Schema,
                fields = {
                    list(Fields.PARAMS, of = DataSchema.Schema, isOptional = true)
                    list(Fields.CHAIN, of = Data.Schema, isOptional = true)
                }
            )

            fun create(
                context: TContext,
                id: String,
                params: List<DataSchema>? = null,
                chain: List<Data>? = null,
                callstack: ChainTemplate<*, *>,
                deferred: Boolean = false,
            ): DynamicPropertyRelationSchema {
                val composite = context.create<Composite>(
                    type = ID,
                    fields = listOf(
                        id.toTString(context),
                        PropertyRelationSchema.Schema,
                        context.dataFactory().createList<Field>(emptyList()), // values
                        context.dataFactory().createList<TraitSchema>(emptyList()), // traits
                        null, // typeParams
                        params?.let { context.dataFactory().createList<DataSchema>(it) }, // params
                        chain?.let { context.dataFactory().createList<Data>(it) } // chain
                    )
                )
                return object : DynamicPropertyRelationSchema, Composite by composite {
                    override val schema: CompositeSchema get() = Schema
                    override val callstack = callstack
                    override val deferredByDefault = deferred
                }
            }
        }

        object Fields {
            const val PARAMS = "$ID/params"
            const val CHAIN = "$ID/chain"
        }
    }

    interface ServiceRelationSchema : RelationSchema {

        companion object {
            const val ID = "${RelationSchema.ID}/service"
            val Schema: CompositeSchema = composite(
                id = ID,
                parent = RelationSchema.Schema
            )

            fun create(
                context: TContext,
                id: String
            ): ServiceRelationSchema {
                return context.create(
                    type = ID,
                    fields = listOf(
                        id.toTString(context),
                        RelationSchema.Schema,
                        emptyList<Field>().toTList(context),
                        emptyList<Trait>().toTList(context)
                    )
                )
            }
        }
    }

    /**
     * Placeholder schema for `ServiceDataRelation` value registrations. Lets
     * `getSchema(targetId)` succeed and `createReference(...)` mint the right
     * reference type. The actual relation value (with `type: Type` and optional
     * `dataId`) lives in `ServiceDataRelation` and is stored in the module
     * config's `serviceDataRelations` map.
     */
    interface ServiceDataRelationSchema : RelationSchema {

        val type: Type
            get() = getField(Fields.TYPE)

        /**
         * Whether this relation's membership is **derived** — kept in step with
         * the element rows by the one generic
         * `RelationMaintainer` (`core-relation-system.md` §9.2.1). `false` means
         * the module maintains it by hand, which after R2 is only the two
         * relations that are not membership at all (`HealthService.LATEST_SAMPLES`
         * and the `FileSyncSetting` 2→1 fold — both rollups).
         *
         * ⚠ R2b replaced the former `order: MembershipOrder?` with this flag: the
         * *policy* half of that parameter is gone (every relation now converges
         * probe-first), so all that remained of it was the opt-in it doubled as.
         *
         * Code-side metadata like [ServiceSchema.version] and
         * [DynamicPropertyRelationSchema.deferredByDefault], **never a wire
         * field**: composites serialise positionally, so an added field desyncs
         * old peers mid-parse. Only the proxy's own registration is consulted; a
         * schema deserialised from the wire reports the default.
         */
        val maintained: Boolean
            get() = false

        /**
         * Whether the module registers a chain reference for this relation, i.e.
         * whether `service.relation` is readable through the chain DSL at all.
         * `false` makes a dedicated operation the only read surface — the choice
         * the `IdentityService.Relations` KDocs state in prose today ("*a plain
         * array-key id, not a `serviceDataRelation`*"), said directly instead.
         *
         * Same code-side-metadata rules as [maintained].
         */
        val chainReadable: Boolean
            get() = true

        companion object {
            const val ID = "${RelationSchema.ID}/serviceData"

            val Schema: CompositeSchema = composite(
                id = ID,
                parent = RelationSchema.Schema,
                fields = {
                    composite(Fields.TYPE, type = Type.Schema)
                }
            )

            fun create(
                context: TContext,
                id: String,
                type: Type,
                maintained: Boolean = false,
                chainReadable: Boolean = true,
            ): ServiceDataRelationSchema {
                val declaredMaintained = maintained
                val declaredChainReadable = chainReadable
                val composite = context.create<Composite>(
                    type = ID,
                    fields = listOf(
                        id.toTString(context),
                        RelationSchema.Schema,
                        emptyList<Field>().toTList(context),
                        emptyList<Trait>().toTList(context),
                        type
                    )
                )
                return object : ServiceDataRelationSchema, Composite by composite {
                    override val schema: CompositeSchema get() = Schema
                    override val maintained = declaredMaintained
                    override val chainReadable = declaredChainReadable
                }
            }
        }

        object Fields {
            const val TYPE = "$ID/type"
        }
    }

    companion object {
        const val ID = "tenshu/core/relation/schema"

        val Schema: PropertyRelationSchema = propertyRelation(
            id = ID
        )
    }
}
