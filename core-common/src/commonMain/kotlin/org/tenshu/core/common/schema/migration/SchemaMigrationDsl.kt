package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.baseSchema

/**
 * Declares one service version bump's [SchemaMigration]
 * (`core-schema-evolution.md` §8.4):
 *
 * ```kotlin
 * val FileServiceMigrationV2toV3 = schemaMigration(FileService.ID, fromVersion = 2) {
 *     migration(typeId = FileShare.ID, from = FILE_SHARE_AT_V2, to = FileShare.Schema) {
 *         drop(FileShare.Fields.LEGACY_FLAG)
 *         derive(FileShare.Fields.KIND) { old, ctx -> /* from real old data */ }
 *     }
 *     dropType(typeId = LegacyPin.ID, from = LEGACY_PIN_AT_V2)
 * }
 * ```
 *
 * All field-mapping accounting ([FieldMappingBuilder]) is enforced when the
 * builder resolves — a mis-declared migration fails at construction, before
 * anything runs against real data.
 */
fun schemaMigration(
    serviceId: String,
    fromVersion: Int,
    block: SchemaMigrationBuilder.() -> Unit,
): SchemaMigration = SchemaMigrationBuilder(serviceId, fromVersion).apply(block).build()

/** Collects one bump's per-type migrations. See [schemaMigration]. */
class SchemaMigrationBuilder internal constructor(
    private val serviceId: String,
    private val fromVersion: Int,
) {

    private val migrations = mutableListOf<CompositeMigration>()
    private val droppedProperties = mutableListOf<DroppedProperty>()

    /**
     * Declares one composite type's reshape. [from] and [to] are the frozen
     * schema snapshots ([CompositeMigration.from]/[CompositeMigration.to]);
     * [block] declares the exceptional field mappings — every id present in
     * both snapshots copies automatically, and everything else must be spelled
     * out ([FieldMappingBuilder]). Accounting is enforced here, at resolution.
     */
    fun migration(
        typeId: String,
        from: CompositeSchema,
        to: CompositeSchema,
        block: FieldMappingBuilder.() -> Unit = {},
    ) {
        migrations += FieldMappingBuilder(typeId, from, to).apply(block).build()
    }

    /**
     * Retires a whole type: its [CompositeMigration.migrate] returns `null`
     * for every value — data loss written down, never implied. The step's
     * `to` is the (never-encoded) [from] snapshot itself, so the chain check
     * stays trivially satisfied for a type that has no later steps.
     */
    fun dropType(typeId: String, from: CompositeSchema) {
        migrations += DroppedTypeMigration(typeId, from)
    }

    /**
     * Retires a property slot of [typeId]: every stored row of the type loses its [propertyId]
     * entry — data loss written down, never implied. See [DroppedProperty] for why a property
     * removal owes a step at all, and for the seam it does not cover.
     */
    fun dropProperty(typeId: String, propertyId: String) {
        droppedProperties += DroppedProperty(typeId, propertyId)
    }

    internal fun build(): SchemaMigration =
        SchemaMigration(serviceId, fromVersion, migrations.toList(), droppedProperties.toList())
}

/**
 * Field mapping — by id, with forced accounting
 * (`core-schema-evolution.md` §8.4). The mapping between `from` and `to` is
 * computed by **field id**: every id present in both snapshots copies across
 * automatically (position is irrelevant to the mapping — ids are stable
 * strings; position only governs decode/encode, which the snapshots own).
 * Everything else must be declared, and resolution fails loudly on any
 * unaccounted field (deletion by omission is rejected — a field nobody
 * mentioned is silently lost otherwise):
 *
 * - `from`-only field → explicit [drop] (or a [rename] source).
 * - `to`-only *optional* field → starts **absent**; no declaration, and
 *   deliberately no default-value concept ([MigrationContext] has no clock).
 * - `to`-only *required* field → explicit [derive] from real old data.
 * - same id, changed declared type → explicit [convert].
 * - renamed field → [rename] links the two ids (only needed when the type is
 *   being migrated anyway — pure renames are wire-free, ids never serialize).
 */
class FieldMappingBuilder internal constructor(
    private val typeId: String,
    private val from: CompositeSchema,
    private val to: CompositeSchema,
) {

    private val drops = mutableSetOf<String>()
    private val derives = mutableMapOf<String, (Data, MigrationContext) -> Data?>()
    private val converts = mutableMapOf<String, (Data?, MigrationContext) -> Data?>()

    /** oldId → newId. */
    private val renames = mutableMapOf<String, String>()

    /** Acknowledges the deletion of a `from`-only field. */
    fun drop(fieldId: String) {
        drops += fieldId
    }

    /**
     * Computes a `to`-only field's value from the whole old value ([old] is
     * the composite decoded under `from`). Mandatory for a `to`-only
     * *required* field; permitted for a `to`-only optional one. Returning
     * `null` for a required field fails the migration at run time.
     */
    fun derive(fieldId: String, block: (old: Data, ctx: MigrationContext) -> Data?) {
        derives[fieldId] = block
    }

    /**
     * Converts a same-id field whose declared type changed. [block] receives
     * the old field value (`null` when the `from` field is optional and
     * absent).
     */
    fun convert(fieldId: String, block: (oldValue: Data?, ctx: MigrationContext) -> Data?) {
        converts[fieldId] = block
    }

    /** Links [oldId] (a `from` field) to [newId] (a `to` field); the value copies across. */
    fun rename(oldId: String, newId: String) {
        renames[oldId] = newId
    }

    internal fun build(): CompositeMigration {
        val fromFields = from.values.toList()
        val toFields = to.values.toList()
        val fromById = fromFields.associateBy { it.id.get() }
        val toById = toFields.associateBy { it.id.get() }
        val renameByNew = renames.entries.associate { (old, new) -> new to old }

        // Rule sanity — every declared rule must name a real field on the
        // side(s) it operates on, and rules must not contradict each other.
        renames.forEach { (oldId, newId) ->
            check(fromById.containsKey(oldId)) {
                "Migration for '$typeId': rename source '$oldId' is not a field of the from snapshot."
            }
            check(toById.containsKey(newId)) {
                "Migration for '$typeId': rename target '$newId' is not a field of the to snapshot."
            }
            check(oldId !in drops) {
                "Migration for '$typeId': field '$oldId' is both drop()ped and rename()d — contradictory rules."
            }
            check(newId !in derives) {
                "Migration for '$typeId': field '$newId' is both a rename target and derive()d — contradictory rules."
            }
        }
        drops.forEach { id ->
            check(fromById.containsKey(id)) {
                "Migration for '$typeId': drop('$id') names no field of the from snapshot."
            }
            check(!toById.containsKey(id)) {
                "Migration for '$typeId': drop('$id') targets a field still present on the to side — " +
                    "a shared id copies automatically; dropping it would silently discard data."
            }
        }
        derives.keys.forEach { id ->
            check(toById.containsKey(id)) {
                "Migration for '$typeId': derive('$id') names no field of the to snapshot."
            }
            check(!fromById.containsKey(id)) {
                "Migration for '$typeId': derive('$id') targets a field that already exists on the from side — " +
                    "use convert(...) for a same-id transformation."
            }
        }
        converts.keys.forEach { id ->
            check(fromById.containsKey(id) && toById.containsKey(id)) {
                "Migration for '$typeId': convert('$id') requires the field on BOTH sides " +
                    "(from: ${fromById.containsKey(id)}, to: ${toById.containsKey(id)})."
            }
        }

        // Forced accounting — every field on both sides must be explained.
        fromFields.forEach { field ->
            val id = field.id.get()
            val accounted = toById.containsKey(id) || id in drops || renames.containsKey(id)
            check(accounted) {
                "Migration for '$typeId': unaccounted from-only field '$id' — a removed field must be " +
                    "explicitly drop()ped or rename()d; deletion-by-omission is rejected (a typo'd id " +
                    "would silently discard data)."
            }
        }
        toFields.forEach { field ->
            val id = field.id.get()
            when {
                fromById.containsKey(id) -> {
                    val fromTypeId = fromById.getValue(id).declaredTypeId()
                    val toTypeId = field.declaredTypeId()
                    check(fromTypeId == toTypeId || converts.containsKey(id)) {
                        "Migration for '$typeId': field '$id' changed declared type " +
                            "('$fromTypeId' → '$toTypeId') — declare convert('$id') { … }; an id match " +
                            "with a type mismatch never copies silently."
                    }
                }
                renameByNew.containsKey(id) || derives.containsKey(id) -> Unit
                else -> check(field.isOptional.get()) {
                    "Migration for '$typeId': new REQUIRED field '$id' has no derive(...) — a required " +
                        "add must be computed from real old data (make the field optional if it cannot " +
                        "be); there is no default-value concept."
                }
            }
        }

        // One writer per to-field, in to order — the ENCODE order.
        val writers = toFields.map { toField ->
            val id = toField.id.get()
            val produce: (Data, MigrationContext) -> Data? = when {
                renameByNew.containsKey(id) ->
                    copyOf(fromById.getValue(renameByNew.getValue(id)))
                derives.containsKey(id) -> derives.getValue(id)
                fromById.containsKey(id) -> {
                    val convert = converts[id]
                    if (convert != null) {
                        convertOf(fromById.getValue(id), convert)
                    } else {
                        copyOf(fromById.getValue(id))
                    }
                }
                // to-only optional: starts absent — "this row predates the field".
                else -> absent
            }
            FieldWriter(id, required = !toField.isOptional.get(), produce = produce)
        }

        return MappedCompositeMigration(typeId, from, to, writers)
    }
}

/** The field's declared type id (structural comparison unit — §8.4 chain check). */
private fun Field.declaredTypeId(): String? = type.baseSchema?.id?.get()

/** Reads a `from`-side field off [old] honouring the snapshot's optionality. */
private fun readFieldValue(old: Data, field: Field): Data? {
    val composite = old as? Composite
        ?: error("Migration source value is not a Composite: ${old::class.simpleName}")
    val id = field.id.get()
    return if (field.isOptional.get()) {
        composite.getOptionalField(id)
    } else {
        composite.getField(id)
    }
}

/** Identity copy from [source] (auto-copy / rename). */
private fun copyOf(source: Field): (Data, MigrationContext) -> Data? =
    { old, _ -> readFieldValue(old, source) }

/** [convert] applied to [source]'s old value. */
private fun convertOf(
    source: Field,
    convert: (Data?, MigrationContext) -> Data?,
): (Data, MigrationContext) -> Data? =
    { old, ctx -> convert(readFieldValue(old, source), ctx) }

/** A `to`-only optional field: starts absent, never defaulted. */
private val absent: (Data, MigrationContext) -> Data? = { _, _ -> null }

private class FieldWriter(
    val fieldId: String,
    val required: Boolean,
    val produce: (old: Data, context: MigrationContext) -> Data?,
)

/** Builder-generated [CompositeMigration]; the migrate impl the rules compile to. */
private class MappedCompositeMigration(
    override val typeId: String,
    override val from: CompositeSchema,
    override val to: CompositeSchema,
    private val writers: List<FieldWriter>,
) : CompositeMigration {

    override fun migrate(old: Data, context: MigrationContext): Data? {
        val values = writers.map { writer ->
            val value = writer.produce(old, context)
            check(value != null || !writer.required) {
                "Migration for '$typeId' produced null for required field '${writer.fieldId}' — " +
                    "derive/convert must return a value for a required target field."
            }
            value
        }
        return context.context.create(type = typeId, fields = values)
    }
}

private class DroppedTypeMigration(
    override val typeId: String,
    override val from: CompositeSchema,
) : CompositeMigration {

    /** Never encoded — a dropped value has no target shape; identity keeps the chain check satisfied. */
    override val to: CompositeSchema get() = from

    override fun migrate(old: Data, context: MigrationContext): Data? = null
}
