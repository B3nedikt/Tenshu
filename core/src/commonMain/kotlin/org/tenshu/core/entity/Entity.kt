package org.tenshu.core.entity

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.core.TContext
import org.tenshu.core.core.createEntity
import org.tenshu.core.core.dataFactory
import org.tenshu.core.reference.CompositeTypeReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.relation.Property
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.map
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.type

interface Entity<T : Data> : Composite {

    override val schema: CompositeSchema get() = Schema

    val type: CompositeTypeReference
        get() = getField(Fields.TYPE)

    val fields: Map<FieldReference, Data>
        get() = getField(Fields.FIELDS)

    val properties: Map<PropertyTypeReference, Data>
        get() = getField(Fields.PROPERTIES)

    fun <T : Data> getProperty(reference: String): T?

    /**
     * Read a projected field, **which must be present**.
     *
     * ⚠⚠ Declared non-null and implemented as an unchecked cast of a map lookup, so an absent
     * field yields `null` in a slot the caller declared non-null: an NPE on the JVM, and on
     * Kotlin/Native a dereference of `0x0` — `EXC_BAD_ACCESS` / `SIGSEGV`, which **no `catch` can
     * intercept**. That is the correct behaviour for a field the schema declares non-optional (an
     * absent one contradicts the schema, and a store that contradicts its schema owes a
     * `SchemaMigration`, not a softer read) — and the wrong one for anything else. Use
     * [getOptionalFieldValue] for a field that may legitimately be missing.
     */
    fun <T> getFieldValue(reference: String): T

    /**
     * Read a projected field that may legitimately be absent, or `null`.
     *
     * ⚠⚠ **The nullable return type is the whole mechanism, not decoration.** Every implementation
     * ends in an unchecked cast; asking for a nullable `T` is what makes the cast *legal* when the
     * lookup misses, so the null reaches the caller's `?.` instead of being dereferenced. The
     * `runCatching` in [optionalFieldValue] never could: on Kotlin/Native the process is gone
     * before any handler runs.
     *
     * ⚠ Two distinct reasons a field is absent, and both belong here:
     *  - the schema declares it `isOptional = true`, so its accessor should be nullable;
     *  - the row was written by a **different app** at a different version, which is a case the
     *    schema cannot express — a field required by this build was simply unknown to the writer.
     *    `Place` is the standing example (Timeline writes it, Photos and Maps read it), and what to
     *    do about each absent field is a per-field judgement rather than a read-shape one
     *    (`photoPlaceOrNull`).
     */
    fun <T> getOptionalFieldValue(reference: String): T?

    companion object {
        const val ID = "tenshu/core/entity"

        val Schema: CompositeSchema = composite(
            id = ID,
            // E binds the projected element type when an Entity<E> wraps a domain
            // composite via the proxy's `select { ... }` mechanism. Field/property
            // values stay typed as Data — projection is lossy at the schema level.
            typeParams = { type("E") },
            traits = listOf(EntityDataIdProvider.Schema),
            fields = {
                reference(id = Fields.TYPE, type = CompositeTypeReference.Schema)
                map(
                    id = Fields.FIELDS,
                    keyType = FieldReference.Schema,
                    valueType = Data.Schema
                )
                map(
                    id = Fields.PROPERTIES,
                    keyType = PropertyTypeReference.Schema,
                    valueType = Data.Schema
                )
            }
        )

        fun <T : Composite> create(
            context: TContext,
            composite: Composite,
            properties: Map<String, Data> = emptyMap()
        ): Entity<T> = context.createEntity(composite, properties)

        /**
         * Builder overload — attach stored value properties via typed
         * [Property] handles instead of a hand-built `Map<String, Data>`:
         *
         * ```
         * Entity.create(context, File.create(context, path)) {
         *     set(File.Size, bytes)
         *     set(File.LastModifiedAt, now)
         * }
         * ```
         *
         * Extensible by design: any module can `set` its own property on an
         * entity without the owning composite enumerating it. Replaces the old
         * per-composite `File.entity(size, lastModified, …)` factories.
         */
        fun <T : Composite> create(
            context: TContext,
            composite: Composite,
            build: EntityPropertiesBuilder.() -> Unit,
        ): Entity<T> = create(
            context = context,
            composite = composite,
            properties = EntityPropertiesBuilder(context).apply(build).properties,
        )
    }

    object Fields {
        const val TYPE = "$ID/type"
        const val FIELDS = "$ID/fields"
        const val PROPERTIES = "${ID}/properties"
    }
}

/**
 * Collects stored value properties for [Entity.create]'s builder overload. Each
 * [set] encodes the **raw** value through the **registered** value factory
 * (`context.dataFactory().createValue(id, value)`), so the property's DTO
 * construction stays where it was declared. [include] merges a pre-existing
 * property map (e.g. preserving an entity's other properties across a rebuild).
 *
 * Only static value handles ([Property.Value]) are op-written this way;
 * the raw value is whatever the registered factory accepts (e.g. a `Long` for a
 * `TInteger`-backed property).
 */
class EntityPropertiesBuilder(private val context: TContext) {

    val properties: MutableMap<String, Data> = mutableMapOf()

    fun set(property: Property.Value<*>, value: Any) {
        properties[property.id] = context.dataFactory().createValue<TValue>(property.id, value)
    }

    fun include(existing: Map<String, Data>) {
        properties.putAll(existing)
    }
}

/**
 * Reads a property by its typed [property] handle, or `null`. Returns the stored
 * [Data] value ([Property.decode] casts the resolved cell); call `.get()`
 * on the result for the raw Kotlin value of a scalar.
 */
fun <T : Data> Entity<*>.get(property: Property<T>): T? =
    property.decode(getProperty<Data>(property.id))
