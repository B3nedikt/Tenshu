package org.tenshu.core.common.schema.migration

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.Type

/**
 * The snapshot-keyed byte transform for one migration step
 * (`core-schema-evolution.md` §8.4 "Dispatch", single-type level): decode
 * bytes under the step's frozen `from` snapshot, run the [CompositeMigration],
 * re-encode the result. The later store-open / history-upcast seams (E2b+)
 * drive this per row / per delta; nothing here touches a store.
 *
 * Decoding old bytes needs the *producing* field list (§2 fact 1 — positional
 * encoding), so parsing runs against a [SchemaOverlayProvider]: the live
 * registry with the step's `from` snapshots overlaid for the migrated type
 * ids. Nested types that the step does NOT migrate resolve to their live
 * schemas — the nested-reshape ripple is rejected at
 * [SchemaMigrationRegistry.verify] until the overlay orchestration exists.
 */
internal class MigrationValueCodec(
    step: SchemaMigration,
    liveSchemas: SchemaProvider,
    liveFactory: DataFactory,
) {

    private val byTypeId = step.migrations.associateBy { it.typeId }

    private val overlay = SchemaOverlayProvider(
        liveSchemas = liveSchemas,
        liveFactory = liveFactory,
        snapshots = byTypeId.mapValues { (_, migration) -> migration.from },
    )

    private val parser = BinaryParser(overlay, overlay)
    private val serializer = BinarySerializer(liveSchemas)

    /** The step's migration for [typeId], or `null` when the type passes through untouched. */
    fun migrationFor(typeId: String): CompositeMigration? = byTypeId[typeId]

    /**
     * Decodes [bytes] under [typeId]'s frozen `from` snapshot, migrates, and
     * re-encodes under the migrated value's own (`to`-shaped) schema.
     * `null` ⇒ the value is dropped BY DECISION ([CompositeMigration.migrate]
     * returned null), never a parse accident.
     */
    fun migrate(typeId: String, bytes: ByteArray, context: MigrationContext): ByteArray? {
        val migration = byTypeId[typeId]
            ?: error("No migration registered for type '$typeId' in this step.")
        val old: Data = parser.parse(bytes, Type.create(baseSchema = migration.from))
        val migrated = migration.migrate(old, context) ?: return null
        return serializer.serialize(migrated)
    }
}

/**
 * A [SchemaProvider]/[DataFactory] that answers with a frozen `from` snapshot
 * for the migrated type ids and delegates everything else to the live
 * registry (§8.4). Three duties:
 *
 * - [getSchema] returns the snapshot, so positional decode runs against the
 *   producing field list;
 * - [getTypeId] resolves the 16-byte wire tag for snapshot ids even when the
 *   type has been retired from the live registry (a
 *   [SchemaMigrationBuilder.dropType] step);
 * - [createComposite] materialises migrated values as [SnapshotComposite]s
 *   bound to the snapshot schema, so `getField(id)` maps positions under the
 *   OLD field order — the live dto factory would map them under the live one.
 */
internal class SchemaOverlayProvider(
    private val liveSchemas: SchemaProvider,
    private val liveFactory: DataFactory,
    private val snapshots: Map<String, CompositeSchema>,
) : SchemaProvider by liveSchemas, DataFactory by liveFactory {

    private val snapshotTags: List<Pair<ByteArray, String>> =
        snapshots.keys.map { typeId -> idToUuid(typeId) to typeId }

    override fun getSchema(type: String): DataSchema? =
        snapshots[type] ?: liveSchemas.getSchema(type)

    override fun getTypeId(type: ByteArray): String {
        snapshotTags.firstOrNull { (tag, _) -> tag.contentEquals(type) }
            ?.let { (_, typeId) -> return typeId }
        return liveSchemas.getTypeId(type)
    }

    override fun <T : Composite> createComposite(type: String, fields: List<Data?>): T {
        val snapshot = snapshots[type] ?: return liveFactory.createComposite(type, fields)
        @Suppress("UNCHECKED_CAST")
        return SnapshotComposite(snapshot, fields) as T
    }
}

/**
 * A composite bound to an explicit (snapshot) schema instead of a registered
 * dto class — field access by id resolves positions through the snapshot's
 * field list. Used for values decoded under a `from` snapshot, and usable by
 * tests to hand-build legacy-shaped values.
 */
internal class SnapshotComposite(
    override val schema: CompositeSchema,
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues)
