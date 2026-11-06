package org.tenshu.core.common.proxy.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.tenshu.core.common.schema.migration.StoreMigrationJournal
import org.tenshu.core.common.schema.migration.StoreMigrationPort
import org.tenshu.core.common.schema.migration.StoreMigrationStep

/**
 * [StoreMigrationPort] + [StoreMigrationJournal] over `SqliteObjectStore`'s
 * own write connection (`core-schema-evolution.md` §8.1) — used only inside
 * `SqliteObjectStore.open`, synchronously, between the `PRAGMA user_version`
 * gate and cache hydration: the writer thread does not exist yet and the
 * store has not been handed out, so single-connection sequential access is
 * the whole threading model.
 *
 * Both tables are additive inside the store file and self-creating (created
 * lazily on first write): a pre-E2b store simply lacks them (handled by the
 * migrator's stamp-current posture), and a post-E2b store opened by pre-E2b
 * code ignores them — no `TENSHU_STORAGE_FORMAT_VERSION` bump owed. The
 * `objects` table stays forever `(hash, type, data)`; migration rewrites row
 * BYTES via the same upsert shape the store's writer uses.
 */
internal class SqliteStoreMigrationAdapter(
    private val conn: SQLiteConnection,
) : StoreMigrationPort, StoreMigrationJournal {

    // --- versions -------------------------------------------------------------

    override fun hasServiceVersionTable(): Boolean = tableExists(SERVICE_VERSIONS_TABLE)

    override fun readServiceVersions(): Map<String, Int> {
        if (!tableExists(SERVICE_VERSIONS_TABLE)) return emptyMap()
        val versions = LinkedHashMap<String, Int>()
        statement("SELECT service_id, version FROM $SERVICE_VERSIONS_TABLE") { stmt ->
            while (stmt.step()) versions[stmt.getText(0)] = stmt.getLong(1).toInt()
        }
        return versions
    }

    override fun stampServiceVersions(versions: Map<String, Int>) {
        exec(
            "CREATE TABLE IF NOT EXISTS $SERVICE_VERSIONS_TABLE (" +
                "service_id TEXT PRIMARY KEY NOT NULL, " +
                "version INTEGER NOT NULL)"
        )
        versions.forEach { (serviceId, version) ->
            statement(
                "INSERT INTO $SERVICE_VERSIONS_TABLE (service_id, version) VALUES (?, ?) " +
                    "ON CONFLICT(service_id) DO UPDATE SET version = excluded.version"
            ) { stmt ->
                stmt.bindText(1, serviceId)
                stmt.bindLong(2, version.toLong())
                stmt.step()
            }
        }
    }

    // --- journal --------------------------------------------------------------

    override fun incompleteSteps(): List<StoreMigrationStep> {
        if (!tableExists(JOURNAL_TABLE)) return emptyList()
        val steps = ArrayList<StoreMigrationStep>()
        statement(
            "SELECT service_id, from_version FROM $JOURNAL_TABLE " +
                "ORDER BY service_id, from_version"
        ) { stmt ->
            while (stmt.step()) {
                steps += StoreMigrationStep(stmt.getText(0), stmt.getLong(1).toInt())
            }
        }
        return steps
    }

    override fun beginStep(step: StoreMigrationStep) {
        exec(
            "CREATE TABLE IF NOT EXISTS $JOURNAL_TABLE (" +
                "service_id TEXT NOT NULL, " +
                "from_version INTEGER NOT NULL, " +
                "PRIMARY KEY (service_id, from_version))"
        )
        statement(
            "INSERT INTO $JOURNAL_TABLE (service_id, from_version) VALUES (?, ?) " +
                "ON CONFLICT(service_id, from_version) DO NOTHING"
        ) { stmt ->
            stmt.bindText(1, step.serviceId)
            stmt.bindLong(2, step.fromVersion.toLong())
            stmt.step()
        }
    }

    override fun clearStep(step: StoreMigrationStep) {
        statement(
            "DELETE FROM $JOURNAL_TABLE WHERE service_id = ? AND from_version = ?"
        ) { stmt ->
            stmt.bindText(1, step.serviceId)
            stmt.bindLong(2, step.fromVersion.toLong())
            stmt.step()
        }
    }

    // --- rows -----------------------------------------------------------------

    override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry> {
        if (typeIds.isEmpty()) return emptyList()
        val placeholders = typeIds.joinToString(", ") { "?" }
        val rows = ArrayList<ObjectStore.DbEntry>()
        statement("SELECT data FROM objects WHERE type IN ($placeholders)") { stmt ->
            typeIds.forEachIndexed { index, typeId -> stmt.bindText(index + 1, typeId) }
            while (stmt.step()) rows += DbEntryCodec.decode(stmt.getBlob(0))
        }
        return rows
    }

    override fun rewriteRow(entry: ObjectStore.DbEntry) {
        statement(
            "INSERT INTO objects (hash, type, data) VALUES (?, ?, ?) " +
                "ON CONFLICT(hash) DO UPDATE SET type = excluded.type, data = excluded.data"
        ) { stmt ->
            stmt.bindLong(1, entry.dataIdHash)
            stmt.bindText(2, entry.type)
            stmt.bindBlob(3, DbEntryCodec.encode(entry))
            stmt.step()
        }
    }

    override fun deleteRow(dataIdHash: Long) {
        statement("DELETE FROM objects WHERE hash = ?") { stmt ->
            stmt.bindLong(1, dataIdHash)
            stmt.step()
        }
    }

    override fun wipeAllRows() {
        exec("DELETE FROM objects")
    }

    override fun transaction(block: () -> Unit) {
        exec("BEGIN IMMEDIATE")
        try {
            block()
            exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
    }

    // --- helpers --------------------------------------------------------------

    private fun tableExists(name: String): Boolean =
        statement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?") { stmt ->
            stmt.bindText(1, name)
            stmt.step()
        }

    private fun exec(sql: String) {
        statement(sql) { it.step() }
    }

    private inline fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R {
        val stmt = conn.prepare(sql)
        return try {
            block(stmt)
        } finally {
            stmt.close()
        }
    }

    companion object {
        internal const val SERVICE_VERSIONS_TABLE = "schema_service_versions"
        internal const val JOURNAL_TABLE = "schema_migration_journal"
    }
}
