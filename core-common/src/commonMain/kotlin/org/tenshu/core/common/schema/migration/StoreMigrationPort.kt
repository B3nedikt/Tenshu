package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.proxy.db.ObjectStore

/**
 * Identity of one store-migration step: one service, one version bump
 * (`core-schema-evolution.md` §8.1/§8.4). The journal keys on this pair — the
 * step's [SchemaMigration] is resolved from the registered chain at run time,
 * never persisted (persisting transforms would be a second source of truth
 * that could drift from the registered artifact).
 */
data class StoreMigrationStep(
    val serviceId: String,
    val fromVersion: Int,
)

/** Per-step progress for the one-time migration progress UI (§8.4). */
class MigrationProgress(
    val serviceId: String,
    val fromVersion: Int,
    val toVersion: Int,
    /** Rows of migrated types rewritten in place. */
    val rewrittenRows: Int,
    /** Rows deleted BY DECISION — a `migrate` returning null / a `dropType`. */
    val droppedRows: Int,
)

/**
 * The crash-safety journal for store migrations, kept INSIDE the same SQLite
 * database as the rows it protects (its own `schema_migration_journal` table)
 * — the `DataIdMigrator`/`RekeyMigrationMarker` intent-journal SHAPE
 * (`core-schema-evolution.md` §8.1 "Crash-safety is a solved shape"),
 * extracted to the store level: marker in its own transaction → idempotent
 * step → clear marker → resume at open.
 *
 * Transaction discipline (owned by [StoreSchemaMigrator], stated here because
 * it is what makes resume idempotent):
 *
 *  1. [beginStep] commits ALONE, before any row of the step is rewritten.
 *  2. Every row rewrite of the step + [clearStep] + the service-version bump
 *     commit as ONE transaction.
 *
 * Therefore a marker that survives a crash implies the step's rewrite
 * transaction never committed — every row is still in its `from` shape — and
 * re-running the step decodes the same bytes under the same frozen snapshot
 * through the same pure [CompositeMigration.migrate], upserting by the same
 * unchanged `dataIdHash`. Deterministic in, deterministic out: idempotent.
 */
internal interface StoreMigrationJournal {

    /** Markers left behind by a crash — the resume input. Empty when the table is absent. */
    fun incompleteSteps(): List<StoreMigrationStep>

    /**
     * Writes [step]'s marker. The caller MUST commit this in its own
     * transaction BEFORE any row rewrite of the step (discipline (1) above).
     * Idempotent (upsert) — a resume re-running a step re-begins it.
     */
    fun beginStep(step: StoreMigrationStep)

    /**
     * Clears [step]'s marker. The caller MUST include this in the SAME
     * transaction as the step's row rewrites + version bump (discipline (2)).
     */
    fun clearStep(step: StoreMigrationStep)
}

/**
 * Minimal raw-row + version access [StoreSchemaMigrator] needs from a durable
 * store (`core-schema-evolution.md` §8.1) — implemented by the store's open
 * path over its own connection, synchronously, BEFORE the store's cache is
 * hydrated and before its writer exists (so no threading model to violate).
 *
 * Row scope: store rows are [ObjectStore.DbEntry]s — the `type` column is the
 * composite's stable schema id STRING (not the wire's 16-byte tag), fields are
 * a positional [ObjectStore.DbValue] list in schema field order, and nested
 * composites / lists live in their OWN rows referenced by `LongValue` hash.
 * [rowsOfTypes] therefore selects exactly the rows a step reshapes; everything
 * else (untouched types, array-membership rows, the reserved sync-cursor row)
 * is never even read.
 */
internal interface StoreMigrationPort {

    /**
     * Whether the per-service version table exists at all. Absent = a store
     * created before E2b (or brand new) — stamped current at open without
     * touching rows: `PRAGMA user_version` already gated the format, and
     * pre-E2b every reshape owed a format bump (House Rule #9), so a
     * matching-`user_version` store's rows ARE current-shaped.
     */
    fun hasServiceVersionTable(): Boolean

    /** The recorded per-service version vector (`schema_service_versions`). */
    fun readServiceVersions(): Map<String, Int>

    /** Upserts [versions] into the version table, creating it if needed. Caller owns the transaction. */
    fun stampServiceVersions(versions: Map<String, Int>)

    /** All rows whose `type` is in [typeIds], decoded. */
    fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>

    /** Upserts [entry] in place (same hash, new bytes). Caller owns the transaction. */
    fun rewriteRow(entry: ObjectStore.DbEntry)

    /** Deletes the row at [dataIdHash]. Caller owns the transaction. */
    fun deleteRow(dataIdHash: Long)

    /**
     * Deletes every object row — the below-floor wipe path (§8.1: a recorded
     * version below the service's derived floor has no migration steps left
     * that can read it). The co-located sync cursor row wipes with the data it
     * cursors over, by design (`ObjectStore.loadSyncCursor` Option A1).
     */
    fun wipeAllRows()

    /** Runs [block] inside one BEGIN IMMEDIATE … COMMIT; rolls back and rethrows on failure. */
    fun transaction(block: () -> Unit)
}
