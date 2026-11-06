package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TConstant
import org.tenshu.core.TString
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.util.isCompositeField
import org.tenshu.core.common.util.isConstantField
import org.tenshu.core.common.util.isGenericDataField
import org.tenshu.core.common.util.isParameterized
import org.tenshu.core.common.util.isValueField
import org.tenshu.core.core.TContext
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.baseSchema

/**
 * The store-row twin of [MigrationValueCodec] (`core-schema-evolution.md`
 * §8.1/§8.4 "Dispatch"): decode one [ObjectStore.DbEntry]'s positional
 * `DbValue` slots under the step's frozen `from` snapshot, run the
 * [CompositeMigration], re-encode the result's fields in `to` order.
 *
 * Why a separate codec: store rows are NOT `BinarySerializer` bytes. A row is
 * a `(type-id string, positional DbValue list, properties map)` — nested
 * composites and lists are NOT inlined (each lives in its own row, referenced
 * by `LongValue` hash), so the wire's nested-reshape ripple does not exist at
 * this seam; conversely the positional hazard is identical (slots pair with
 * the *producing* schema's field order — §2 fact 1 transfers verbatim).
 *
 * Value surface handed to `migrate`:
 *  - scalar fields (string/int/bool/bytes/constant) decode to REAL `Data`
 *    values — `derive`/`convert` closures can read them exactly as at the
 *    wire seam;
 *  - composite-scalar fields, list fields, genericData slots and
 *    reference-typed fields surface as opaque [StoredSlot] pass-through
 *    wrappers (their content lives in other rows, which migrate under their
 *    own type's steps). A `derive`/`convert` cannot look inside them at this
 *    seam, and a migration cannot MINT a fresh composite/list field value here
 *    (that would require creating child rows) — both fail loudly at encode.
 *
 * ⚠ Multi-step correctness (the §8.5 hazard, closed structurally): `migrate`
 * runs under an OVERLAY context whose `DataFactory` materialises the step's
 * migrated type ids as [SnapshotComposite]s bound to the step's `to` snapshot
 * — so field access on the output maps positions under TO order even when
 * `to` differs from the live schema (an intermediate step of a longer chain).
 * The orchestrator additionally runs each step as a full store pass (rows out
 * of step N are re-read from the store by step N+1), so no live-schema dto is
 * ever fed to a later step's decode. Escape-hatch (hand-written)
 * [CompositeMigration.migrate] implementations must build their output via
 * `context.context.create(typeId, fields)` for this to hold.
 */
internal class StoreRowMigrationCodec(
    step: SchemaMigration,
    private val live: SchemaRegistry,
    baseContext: TContext,
    deviceId: String,
) {

    private val byTypeId = step.migrations.associateBy { it.typeId }

    // Encode-side overlay: create(typeId, …) for a migrated type yields a
    // SnapshotComposite bound to the step's `to` snapshot (see class KDoc).
    // Shared with the history-upcast seam — see `MigrationOverlays.kt`.
    private val migrationContext = step.toBoundMigrationContext(live, baseContext, deviceId)

    /**
     * Migrates one row of a migrated type. Returns the rewritten entry (same
     * hash — a [CompositeMigration] never changes its `typeId`, and the
     * DataId hash derives from identity, not shape), or `null` when the value
     * is dropped BY DECISION (`migrate` returned null / a `dropType` step).
     *
     * Entity rows and plain composite rows share the same [ObjectStore.DbEntry]
     * shape — there is no entity envelope; an entity row simply also carries
     * a `properties` map (relation/property values keyed by property id).
     * Properties are outside the composite field list the migration artifact
     * covers, and their string keys make them order-independent, so they are
     * preserved verbatim on the rewritten row.
     */
    fun migrateRow(entry: ObjectStore.DbEntry): ObjectStore.DbEntry? {
        val migration = byTypeId[entry.type]
            ?: error("No migration registered for row type '${entry.type}' in this step.")
        val old = try {
            decode(entry, migration.from)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Store migration failed decoding row (type='${entry.type}', " +
                    "hash=${entry.dataIdHash}) under its v-from snapshot: ${t.message}",
                t,
            )
        }
        val migrated = migration.migrate(old, migrationContext) ?: return null
        return ObjectStore.DbEntry(
            type = entry.type,
            dataIdHash = entry.dataIdHash,
            fields = encode(migrated, migration.to, entry),
            properties = entry.properties,
        )
    }

    // --- decode: positional DbValues → SnapshotComposite under `from` ---------

    private fun decode(entry: ObjectStore.DbEntry, from: CompositeSchema): Data {
        val fields = from.values.toList()
        val values: List<Data?> = fields.mapIndexed { index, field ->
            // A row stored before an *optional* tail field was appended has
            // fewer slots than the snapshot declares — absent, not corrupt
            // (mirrors DbEntryMapper.deSerialize's tolerance). A short row on
            // a mandatory field IS corruption and must fail loudly.
            if (index > entry.fields.lastIndex) {
                check(field.isOptional.get()) {
                    "row has ${entry.fields.size} slot(s) but mandatory field " +
                        "'${field.id.get()}' sits at position $index"
                }
                return@mapIndexed null
            }
            decodeSlot(field, entry.fields[index])
        }
        return SnapshotComposite(from, values)
    }

    private fun decodeSlot(field: Field, dbValue: ObjectStore.DbValue): Data? = when {
        dbValue is ObjectStore.DbValue.NullValue -> null

        // Self-describing genericData slot: content is an inline primitive or
        // a composite row reference — opaque pass-through either way.
        field.isGenericDataField -> StoredSlot(dbValue)

        field.isValueField ->
            if (field.type is ReferenceSchema) {
                // Reference-typed field: serialized as a 16-byte id at the
                // wire, singleton at read — nothing here needs reshaping.
                StoredSlot(dbValue)
            } else {
                // Explicit type argument: the surrounding when-expression's
                // branch inference would otherwise pin T to a sibling branch's
                // type and insert a bogus checkcast.
                live.createValue<TValue>(
                    type = field.type.baseSchema?.id?.get()
                        ?: error("field '${field.id.get()}' has no base schema"),
                    value = dbValue.value,
                )
            }

        field.isConstantField ->
            live.createConstant<TConstant>((dbValue as ObjectStore.DbValue.StringValue).value)

        // List field: the LongValue is the array row's hash; members migrate
        // under their own type's steps in their own rows.
        field.isParameterized -> StoredSlot(dbValue)

        // Composite-scalar field: LongValue reference to the child's own row.
        field.isCompositeField -> StoredSlot(dbValue)

        else -> error("field '${field.id.get()}' has an unknown field kind")
    }

    // --- encode: `to`-ordered fields off the migrated value → DbValues --------

    private fun encode(
        migrated: Data,
        to: CompositeSchema,
        source: ObjectStore.DbEntry,
    ): List<ObjectStore.DbValue> {
        val composite = migrated as? Composite ?: error(
            "Migration for '${source.type}' returned a non-Composite " +
                "(${migrated::class.simpleName}) — cannot re-encode the store row."
        )
        return to.values.toList().map { field ->
            val id = field.id.get()
            val value: Data? = if (field.isOptional.get()) {
                composite.getOptionalField(id)
            } else {
                composite.getField(id)
            }
            encodeSlot(field, value, source)
        }
    }

    private fun encodeSlot(
        field: Field,
        value: Data?,
        source: ObjectStore.DbEntry,
    ): ObjectStore.DbValue {
        val raw: ObjectStore.DbValue = when (value) {
            null -> return ObjectStore.DbValue.NullValue
            is StoredSlot -> return value.dbValue // pass-through, incl. GenericValue wrapping
            // TConstant before the value types: a constant persists as the
            // StringValue of its schema id (DbEntryMapper's write-side rule).
            is TConstant -> ObjectStore.DbValue.StringValue(value.schema.id.get())
            is TString -> ObjectStore.DbValue.StringValue(value.get())
            is TInteger -> ObjectStore.DbValue.LongValue(value.get())
            is TBoolean -> ObjectStore.DbValue.BooleanValue(value.get())
            is TByteArray -> ObjectStore.DbValue.ByteArrayValue(value.get())
            else -> error(
                "Migration for '${source.type}' (row hash=${source.dataIdHash}) produced a fresh " +
                    "'${value.id}' value for field '${field.id.get()}' — a store-seam migration " +
                    "cannot mint composite/list field values (child rows would need creating); " +
                    "reshape the child type under its own migration instead."
            )
        }
        // A genericData slot must stay self-describing: a freshly produced
        // primitive gets tagged with its concrete type id (write-side rule in
        // DbEntryMapper.serialize); a passed-through slot kept its tag above.
        return if (field.isGenericDataField) {
            ObjectStore.DbValue.GenericValue(typeId = value.id, payload = raw)
        } else {
            raw
        }
    }
}

/**
 * Opaque pass-through wrapper for a store slot whose content lives outside
 * the row (composite/list row references, genericData slots, reference-typed
 * fields). Carried by the auto-copy field mapping and unwrapped 1:1 at
 * encode; a `derive`/`convert` receiving one can only pass it through.
 */
internal class StoredSlot(val dbValue: ObjectStore.DbValue) : Data
