package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.proxy.db.ObjectStore

/**
 * The store-open migration seam for stores that hold every row in memory —
 * the JS `IndexedDbObjectStore` (and, until 2026-11-05, the iOS file store)
 * (`core-schema-evolution.md` §8.9, E2j-3's capability half).
 *
 * ## Why load-migrate-persist instead of a live port
 *
 * [StoreMigrationPort] is shaped for SQLite: synchronous row access inside real
 * `BEGIN IMMEDIATE … COMMIT` transactions. IndexedDB cannot honour that shape:
 * it is **asynchronous**, so a synchronous port is not expressible.
 *
 * But the store already reads *every* row at open to hydrate its cache. So
 * the natural shape is: hand the rows in, migrate against an in-memory port,
 * hand the result back, and let the store persist it however it can commit
 * atomically. The migrator itself is unchanged — it is the same
 * [StoreSchemaMigrator] with the same chain-verify, the same per-service floors
 * and the same wipe paths, so the stores cannot drift in *policy*, only in
 * how they write bytes.
 *
 * ⚖️ **Crash safety comes out stronger, not weaker.** Nothing is written until
 * the caller persists, so a crash mid-migration leaves the store exactly as it
 * was — every row still `from`-shaped — and the next open re-runs from scratch
 * deterministically. That is the journal's guarantee obtained structurally
 * rather than by protocol; the in-memory journal below therefore never has to
 * survive anything. ⚠ The caller still owes an **atomic persist** — see
 * [Result.rows] — or a crash *during the write* can leave a half-migrated store
 * that no marker describes.
 *
 * ⚠ A multi-step chain commits as ONE persist rather than per step. That is
 * sound for the same reason: intermediate steps are visible to the next step
 * (they mutate the same buffer, satisfying "step N's committed rows are step
 * N+1's input"), and a crash before the persist loses all of them together.
 */
class InMemoryStoreMigration(private val config: StoreSchemaMigrations) {

    /**
     * The migrated state, for the caller to persist.
     *
     * @property rows every row the store should hold afterwards. ⚠ The caller
     *   must make this visible **atomically** — replace-all semantics, not a
     *   row-by-row patch that a crash could interrupt halfway.
     * @property serviceVersions the per-service vector to record alongside.
     * @property wiped whether the migrator took a wipe path (below floor, or a
     *   store written by newer code) — reported so the caller can log it.
     * @property changedRows how many rows were rewritten or dropped; `0` with
     *   [wiped] false means a no-drift open that the caller can skip persisting
     *   rows for entirely.
     */
    class Result(
        val rows: Map<Long, ObjectStore.DbEntry>,
        val serviceVersions: Map<String, Int>,
        val wiped: Boolean,
        val changedRows: Int,
    )

    /**
     * Runs [resumeIncompleteMigrations][StoreSchemaMigrator.resumeIncompleteMigrations]
     * — vestigial here, since an in-memory journal never survives a crash — and
     * then the store-open walk over [rows].
     *
     * @param recordedVersions the store's recorded vector, or `null` when it has
     *   none (pre-E2b / fresh) — which stamps current and touches no rows.
     */
    fun run(
        rows: Map<Long, ObjectStore.DbEntry>,
        recordedVersions: Map<String, Int>?,
    ): Result {
        val port = InMemoryMigrationPort(rows, recordedVersions)
        val migrator = StoreSchemaMigrator(port, port, config)
        migrator.resumeIncompleteMigrations()
        migrator.migrateStoreAtOpen()
        return Result(
            rows = port.rows,
            serviceVersions = port.versions,
            wiped = port.wiped,
            changedRows = port.changedRows,
        )
    }
}

/**
 * [StoreMigrationPort] + [StoreMigrationJournal] over plain maps.
 *
 * `transaction` runs the block directly: there is nothing to roll back because
 * nothing is durable yet, and a throw propagates out of [InMemoryStoreMigration.run]
 * leaving the caller's store untouched — the fail-closed path every caller relies on.
 */
private class InMemoryMigrationPort(
    initialRows: Map<Long, ObjectStore.DbEntry>,
    recordedVersions: Map<String, Int>?,
) : StoreMigrationPort, StoreMigrationJournal {

    val rows: MutableMap<Long, ObjectStore.DbEntry> = initialRows.toMutableMap()
    val versions: MutableMap<String, Int> = recordedVersions.orEmpty().toMutableMap()

    /** ⚠ Tracks whether the store HAD a record, not whether [versions] is empty — an
     *  empty-but-present record is a real state and must not read as "pre-E2b". */
    private val hadVersionRecord = recordedVersions != null

    var wiped: Boolean = false
        private set

    var changedRows: Int = 0
        private set

    private val markers = mutableSetOf<StoreMigrationStep>()

    // --- journal (never persisted; see the class KDoc) -------------------------

    override fun incompleteSteps(): List<StoreMigrationStep> = markers.toList()

    override fun beginStep(step: StoreMigrationStep) {
        markers += step
    }

    override fun clearStep(step: StoreMigrationStep) {
        markers -= step
    }

    // --- port -----------------------------------------------------------------

    override fun hasServiceVersionTable(): Boolean = hadVersionRecord

    override fun readServiceVersions(): Map<String, Int> = versions.toMap()

    override fun stampServiceVersions(versions: Map<String, Int>) {
        this.versions.putAll(versions)
    }

    override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry> =
        rows.values.filter { it.type in typeIds }

    override fun rewriteRow(entry: ObjectStore.DbEntry) {
        rows[entry.dataIdHash] = entry
        changedRows++
    }

    override fun deleteRow(dataIdHash: Long) {
        if (rows.remove(dataIdHash) != null) changedRows++
    }

    override fun wipeAllRows() {
        changedRows += rows.size
        rows.clear()
        wiped = true
    }

    override fun transaction(block: () -> Unit) = block()
}
