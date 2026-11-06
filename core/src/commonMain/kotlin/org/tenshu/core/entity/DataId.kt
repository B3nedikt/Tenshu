package org.tenshu.core.entity

import org.tenshu.core.Composite
import org.tenshu.core.SimpleComposite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

interface DataId : Composite {

    val type: TString
        get() = getField(Fields.TYPE)

    override val schema: CompositeSchema
        get() = Schema

    interface EntityDataId : DataId

    interface ValueDataId : EntityDataId

    interface StringDataId : ValueDataId {

        override val schema: CompositeSchema
            get() = Schema

        val keyValue: TString
            get() = getField(Fields.KEY_VALUE)

        companion object {
            const val ID = "tenshu/core/entity/dataId/string"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    string(Fields.KEY_VALUE)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                keyValue: String
            ): StringDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            keyValue.toTString(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.KEY_VALUE to keyValue.toTString()
                        )
                    ), StringDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val KEY_VALUE = "$ID/keyValue"
        }
    }

    interface LongDataId : ValueDataId {

        override val schema: CompositeSchema
            get() = Schema

        val keyValue: TInteger
            get() = getField(Fields.KEY_VALUE)

        companion object {
            const val ID = "tenshu/core/entity/dataId/long"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    int(Fields.KEY_VALUE)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                keyValue: Long
            ): LongDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            keyValue.toTInteger(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.KEY_VALUE to keyValue.toTInteger()
                        )
                    ), LongDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val KEY_VALUE = "$ID/keyValue"
        }
    }

    interface CompositeEntityDataId : EntityDataId {

        override val schema: CompositeSchema
            get() = Schema

        val keyValues: TList<ValueDataId>
            get() = getField(Fields.KEY_VALUES)

        companion object {
            const val ID = "tenshu/core/entity/dataId/composite"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    list(Fields.KEY_VALUES, of = DataId.Schema)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                keyValues: List<ValueDataId>
            ): CompositeEntityDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            keyValues.toTList(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.KEY_VALUES to SimpleTList(keyValues)
                        )
                    ), CompositeEntityDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val KEY_VALUES = "$ID/keyValues"
        }
    }

    interface ReferencedDataId : DataId

    interface EntityFieldDataId : ReferencedDataId {

        override val schema: CompositeSchema
            get() = Schema

        val parentEntity: EntityDataId
            get() = getField(Fields.PARENT_ENTITY)

        val fieldRef: TString
            get() = getField(Fields.FIELD_REF)

        companion object {
            const val ID = "tenshu/core/entity/dataId/referenced/entity"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    composite(Fields.PARENT_ENTITY, type = DataId.Schema)
                    string(Fields.FIELD_REF)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                parentEntity: EntityDataId,
                fieldRef: String
            ): EntityFieldDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            parentEntity,
                            fieldRef.toTString(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.PARENT_ENTITY to parentEntity,
                            Fields.FIELD_REF to fieldRef.toTString()
                        )
                    ), EntityFieldDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val PARENT_ENTITY = "$ID/parentEntity"
            const val FIELD_REF = "$ID/fieldRef"
        }
    }

    interface RelationDataId : ReferencedDataId {

        override val schema: CompositeSchema
            get() = Schema

        val relationId: TString
            get() = getField(Fields.RELATION_ID)

        companion object {
            const val ID = "tenshu/core/entity/dataId/referenced/entityList"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    string(Fields.RELATION_ID)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                relationId: String
            ): RelationDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            relationId.toTString(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.RELATION_ID to relationId.toTString()
                        )
                    ), RelationDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val RELATION_ID = "$ID/relationId"
        }
    }

    /**
     * Per-entity property-relation array — the array backing a property *relation* on a
     * specific entity (e.g. `File("/a").FileHistory`). The property-id counterpart of
     * [EntityFieldDataId] (which keys per-entity *field* arrays like `Folder.files`); distinct
     * from it so nothing that special-cases field arrays mistakes a relation array for a field.
     * Used by `entityRelation` properties (`core-nested-projection.md` Phase 3).
     */
    interface EntityPropertyDataId : ReferencedDataId {

        override val schema: CompositeSchema
            get() = Schema

        val parentEntity: EntityDataId
            get() = getField(Fields.PARENT_ENTITY)

        val propertyId: TString
            get() = getField(Fields.PROPERTY_ID)

        companion object {
            const val ID = "tenshu/core/entity/dataId/referenced/entityProperty"

            val Schema = composite(
                id = ID,
                parent = DataId.Schema,
                fields = {
                    composite(Fields.PARENT_ENTITY, type = DataId.Schema)
                    string(Fields.PROPERTY_ID)
                }
            )

            fun create(
                context: TContext? = null,
                type: String,
                parentEntity: EntityDataId,
                propertyId: String
            ): EntityPropertyDataId {
                return if (context != null) {
                    context.create(
                        type = ID,
                        fields = listOf(
                            type.toTString(context),
                            parentEntity,
                            propertyId.toTString(context)
                        )
                    )
                } else {
                    object : SimpleComposite(
                        Schema,
                        mapOf(
                            DataId.Fields.TYPE to type.toTString(),
                            Fields.PARENT_ENTITY to parentEntity,
                            Fields.PROPERTY_ID to propertyId.toTString()
                        )
                    ), EntityPropertyDataId {
                        override val schema: CompositeSchema get() = Schema
                    }
                }
            }
        }

        object Fields {
            const val PARENT_ENTITY = "$ID/parentEntity"
            const val PROPERTY_ID = "$ID/propertyId"
        }
    }

    companion object {
        const val ID = "tenshu/core/entity/dataId"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.TYPE)
            }
        )
    }

    object Fields {
        const val TYPE = "$ID/type"
    }
}

/**
 * The identity of a [DataId] as a string — *what* it names, with no opinion on how it is hashed.
 *
 * ⭐ Extracted so the wire id can be **keyed** (`WireEntityId`, `sync-entity-identity.md` EI-1)
 * while the local row key stays where it is. The two must agree about which entity they name and
 * differ only in how they name it, which is exactly what sharing this string gives them.
 *
 * ⚠⚠ Nested ids contribute their own identity **string** here, where [getDataIdHash] folds their
 * *hash*. That difference is deliberate and must not be "tidied up": [getDataIdHash] is the object
 * store's primary key, so changing how it composes would move every stored row — a storage-format
 * bump and a wipe. This function is new, has no stored population, and can afford the cleaner
 * recursion.
 */
fun DataId.dataIdIdentityString(): String = when (this) {
    is DataId.StringDataId -> "${type.get()}_${keyValue.get()}"
    is DataId.LongDataId -> "${type.get()}_${keyValue.get()}"
    is DataId.CompositeEntityDataId ->
        "${type.get()}_" + keyValues.toList().joinToString("_") { it.dataIdIdentityString() }
    is DataId.EntityFieldDataId ->
        "${type.get()}_${parentEntity.dataIdIdentityString()}_${fieldRef.get()}"
    is DataId.RelationDataId -> "${type.get()}_${relationId.get()}"
    is DataId.EntityPropertyDataId ->
        "${type.get()}_${parentEntity.dataIdIdentityString()}_${propertyId.get()}"
    is DataId.ReferencedDataId -> when (this) {
        is DataId.EntityFieldDataId ->
            "${type.get()}_${parentEntity.dataIdIdentityString()}_${fieldRef.get()}"
        is DataId.RelationDataId -> "${type.get()}_${relationId.get()}"
        is DataId.EntityPropertyDataId ->
            "${type.get()}_${parentEntity.dataIdIdentityString()}_${propertyId.get()}"
        else -> error("Unsupported ReferencedDataId type: ${this::class}")
    }
    else -> error("Unsupported DataId type: ${this::class}")
}

/**
 * ⚠⚠ **The LOCAL identity only** — the object store's row key, `StreamMerge`'s dedup, relation
 * maintenance. It is a 32-bit `String.hashCode`, and it is **not** what goes on the wire any more:
 * a relay-visible name must be unguessable, which this is not (`WireEntityId`).
 *
 * ⚠ Widening it is EI-1b and costs a storage-format bump, because it is this value that keys every
 * stored row.
 */
fun DataId.getDataIdHash(): Long {
    // EI-1b: the LOCAL identity is a 64-bit truncation of SHA-256 over the same identity string,
    // not `String.hashCode`.
    //
    // ⚠⚠ 32 bits collided at sizes this project already reaches: `p ≈ n²/2³³` gives 0.29 expected
    // colliding pairs at 50 000 rows — about a 25 % chance of at least one — against a 107 k-row
    // replica. A collision here is not a near-miss: tombstone GC drops the live twin's bodies,
    // `StreamMerge` merges two entities into one locally, and `TombstoneRegistry` short-circuits
    // the wrong entity's blocks.
    //
    // ⚠ This moves EVERY stored row's primary key, which is why it ships with a
    // `TENSHU_STORAGE_FORMAT_VERSION` bump and a wipe rather than on its own.
    // ⚠ FNV-1a rather than the truncated SHA-256 the proposal names, for a module reason: `sha256`
    // lives in `:core-common`, which sits ABOVE `:core`, and `StreamMerge` calls this from inside
    // `:core`. The requirement here is width, not preimage resistance — privacy lives in the wire
    // name (`WireEntityId`), which IS keyed — so a good 64-bit non-cryptographic hash answers it.
    //
    // ⚠⚠ Folded over the components rather than over `dataIdIdentityString()`, and that is not a
    // micro-optimisation: this is the object store's primary-key function, called on every row
    // touch. Materialising the identity string here — recursively, for nested ids — allocated
    // enough in a multi-file round trip to kill the test JVM outright.
    return fnv1a64(FNV_OFFSET_BASIS, this)
}

private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L

private fun fnv1a64(seed: Long, dataId: DataId): Long {
    var h = seed
    fun feed(s: String) {
        for (b in s.encodeToByteArray()) h = (h xor (b.toLong() and 0xFF)) * FNV_PRIME
    }
    when (dataId) {
        is DataId.StringDataId -> { feed(dataId.type.get()); feed("_"); feed(dataId.keyValue.get()) }
        is DataId.LongDataId -> { feed(dataId.type.get()); feed("_"); feed(dataId.keyValue.get().toString()) }
        is DataId.CompositeEntityDataId -> {
            feed(dataId.type.get()); feed("_")
            dataId.keyValues.toList().forEach { h = fnv1a64(h, it); feed("_") }
        }
        is DataId.RelationDataId -> { feed(dataId.type.get()); feed("_"); feed(dataId.relationId.get()) }
        is DataId.EntityFieldDataId -> {
            feed(dataId.type.get()); feed("_"); h = fnv1a64(h, dataId.parentEntity)
            feed("_"); feed(dataId.fieldRef.get())
        }
        is DataId.EntityPropertyDataId -> {
            feed(dataId.type.get()); feed("_"); h = fnv1a64(h, dataId.parentEntity)
            feed("_"); feed(dataId.propertyId.get())
        }
        else -> error("Unsupported DataId type: ${dataId::class}")
    }
    return h
}
