package org.tenshu.core.common.proxy.db.sqlite

/** One column of a table this build creates. */
internal data class ReplicaColumn(val name: String, val type: String, val notNull: Boolean, val primaryKey: Boolean)

/** What a wipe does to a table (§5.1). Every table states one, so none can be forgotten. */
internal enum class ReplicaWipe {
    /** The wipe deletes every row, in budgeted chunks of single-row deletes. */
    EMPTIED,

    /** The wipe keeps the table's rows: the store's own bookkeeping and its version stamps. */
    KEPT,
}

internal data class ReplicaTable(
    val name: String,
    val columns: List<ReplicaColumn>,
    val onWipe: ReplicaWipe,
) {
    val createSql: String
        get() = "CREATE TABLE $name (" + columns.joinToString(", ") { column ->
            buildString {
                append(column.name).append(' ').append(column.type)
                if (column.primaryKey) append(" PRIMARY KEY")
                if (column.notNull) append(" NOT NULL")
            }
        } + ")"
}

internal data class ReplicaIndex(val name: String, val table: String, val columns: List<String>) {
    val createSql: String get() = "CREATE INDEX $name ON $table(${columns.joinToString(", ")})"
}

/**
 * The layout of `replica/objects.db` (`docs/proposals/ios-shared-store-sqlite.md` §2.2), stated
 * once. Three things are derived from this one list and so cannot drift apart: the `CREATE`
 * statements of a fresh store, what schema validation requires of an existing one, and which
 * tables a wipe empties.
 *
 * ⚠ `schema_migration_journal`, the JVM adapter's second table, is **not** here and never will
 * be: on iOS a chunked migration's only marker is `meta.migration` (§5.3). A table of that name
 * in an iOS store is an unexpected object, which validation reports as damage like any other.
 *
 * ⚠⚠ Frozen for every layout version (§5.2, the bootstrap marker): `application_id`,
 * `user_version`, `meta`'s shape `(k TEXT PRIMARY KEY, v BLOB)` with its `'layout'` key, and
 * `schema_service_versions (service_id, version)`. A later layout adds beside them.
 */
internal object ReplicaSchema {

    /** `meta.layout` of the tables below. It moves only with them. */
    const val LAYOUT: Int = 1

    /** `PRAGMA application_id`: "TNSR" in ASCII. Another value on a non-empty file is damage. */
    const val APPLICATION_ID: Int = 0x544E5352

    const val META_EPOCH = "epoch"
    const val META_SEQ = "seq"
    const val META_LAYOUT = "layout"
    const val META_REPLACED_BY = "replaced_by"
    const val META_HLC_WATERMARK = "hlc_watermark"
    const val META_WAL_REWIND = "wal_rewind"
    const val META_MIGRATION = "migration"
    const val META_WIPE = "wipe"

    /** Written by the fresh-store transaction; an existing store without one is damaged. */
    val requiredMetaKeys: Set<String> = setOf(META_EPOCH, META_SEQ, META_LAYOUT)

    /** Written later, so their absence is valid. */
    val optionalMetaKeys: Set<String> =
        setOf(META_REPLACED_BY, META_HLC_WATERMARK, META_WAL_REWIND, META_MIGRATION, META_WIPE)

    /**
     * Why the `meta` row [key], of storage class [type] (`typeof(v)`), is not one this layout
     * writes, or null if it is. [integer] is its value when it is an integer, [bytes] its length
     * when it is a blob. ⚠⚠ `meta.v` has no type of its own (`BLOB` affinity keeps whatever is
     * stored), and SQLite reads a text or a blob as the integer 0 when asked for one: a row of
     * the wrong storage class would not fail, it would be believed, and the next bump would
     * overwrite it. So the class is checked here, and [metaInteger] reads without coercion.
     *
     * - `epoch`: an integer, any value (it is random). `seq`: an integer, not negative. `layout`:
     *   an integer from 1 up, within 32 bits.
     * - `wal_rewind`: an integer, not negative. `replaced_by`: text.
     * - `wipe`, `migration`: a blob holding at least a frame's format byte. ⚠ Only that much:
     *   the frames themselves are parsed by the slices that write them.
     * - `hlc_watermark` is **not** judged: a malformed frame there is decoded as "none" and
     *   overwritten by the next raise (§11, F6g), which is its own slice's rule.
     * - A key this build does not know is not judged either: a later slice of the same layout
     *   may add one beside these.
     */
    fun metaRowDamage(key: String, type: String, integer: Long, bytes: Long): String? {
        fun not(expected: String) = "meta.$key is $type, not $expected"
        return when (key) {
            META_EPOCH -> if (type != "integer") not("an integer") else null
            META_SEQ, META_WAL_REWIND -> when {
                type != "integer" -> not("an integer")
                integer < 0 -> "meta.$key is negative"
                else -> null
            }
            META_LAYOUT -> when {
                type != "integer" -> not("an integer")
                integer < 1 || integer > Int.MAX_VALUE -> "meta.layout is out of range"
                else -> null
            }
            META_REPLACED_BY -> if (type != "text") not("text") else null
            META_WIPE, META_MIGRATION -> when {
                type != "blob" -> not("a frame")
                bytes == 0L -> "meta.$key is empty"
                else -> null
            }
            else -> null
        }
    }

    /** Every `meta` row's damage ([metaRowDamage]), first one found, or null. */
    fun metaDamage(connection: SqliteConnection): String? {
        var damage: String? = null
        connection.query("SELECT k, typeof(v), CASE typeof(v) WHEN 'integer' THEN v ELSE 0 END, length(v) FROM $META") { row ->
            if (damage == null) damage = metaRowDamage(row.getText(0), row.getText(1), row.getLong(2), row.getLong(3))
        }
        return damage
    }

    /**
     * The integer `meta` row [key], read strictly: a row that is absent or not of storage class
     * integer throws [ReplicaDamaged], it is never coerced to a number.
     */
    fun metaInteger(connection: SqliteConnection, key: String): Long {
        val statement = connection.prepare("SELECT v, typeof(v) FROM $META WHERE k = ?")
        try {
            statement.bindText(1, key)
            if (!statement.step()) throw ReplicaDamaged("meta.$key is missing")
            if (statement.getText(1) != "integer") throw ReplicaDamaged("meta.$key is ${statement.getText(1)}, not an integer")
            return statement.getLong(0)
        } finally {
            statement.close()
        }
    }

    const val OBJECTS = "objects"
    const val META = "meta"
    const val SCHEDULE_LEASE = "schedule_lease"
    const val SERVICE_VERSIONS = "schema_service_versions"
    const val MIGRATION_DROPPED = "migration_dropped"
    const val MIGRATION_DONE = "migration_done"

    val tables: List<ReplicaTable> = listOf(
        ReplicaTable(
            OBJECTS,
            listOf(
                ReplicaColumn("hash", "INTEGER", notNull = true, primaryKey = true),
                ReplicaColumn("type", "TEXT", notNull = true, primaryKey = false),
                ReplicaColumn("data", "BLOB", notNull = true, primaryKey = false),
            ),
            ReplicaWipe.EMPTIED,
        ),
        ReplicaTable(
            META,
            listOf(
                ReplicaColumn("k", "TEXT", notNull = true, primaryKey = true),
                ReplicaColumn("v", "BLOB", notNull = true, primaryKey = false),
            ),
            ReplicaWipe.KEPT,
        ),
        ReplicaTable(
            SCHEDULE_LEASE,
            listOf(
                ReplicaColumn("key", "TEXT", notNull = true, primaryKey = true),
                ReplicaColumn("record", "BLOB", notNull = true, primaryKey = false),
            ),
            ReplicaWipe.EMPTIED,
        ),
        // The JVM adapter's version table, in its exact shape (SqliteStoreMigrationAdapter).
        ReplicaTable(
            SERVICE_VERSIONS,
            listOf(
                ReplicaColumn("service_id", "TEXT", notNull = true, primaryKey = true),
                ReplicaColumn("version", "INTEGER", notNull = true, primaryKey = false),
            ),
            ReplicaWipe.KEPT,
        ),
        ReplicaTable(
            MIGRATION_DROPPED,
            listOf(ReplicaColumn("hash", "INTEGER", notNull = true, primaryKey = true)),
            ReplicaWipe.EMPTIED,
        ),
        ReplicaTable(
            MIGRATION_DONE,
            listOf(ReplicaColumn("hash", "INTEGER", notNull = true, primaryKey = true)),
            ReplicaWipe.EMPTIED,
        ),
    )

    val indexes: List<ReplicaIndex> = listOf(ReplicaIndex("objects_type", OBJECTS, listOf("type")))

    /** Every `CREATE` of a fresh store, tables first. */
    val createStatements: List<String> get() = tables.map { it.createSql } + indexes.map { it.createSql }

    /** The tables a wipe empties, in the order it empties them. */
    val tablesEmptiedByWipe: List<String> get() = tables.filter { it.onWipe == ReplicaWipe.EMPTIED }.map { it.name }

    /** The tables a wipe keeps. */
    val tablesKeptByWipe: List<String> get() = tables.filter { it.onWipe == ReplicaWipe.KEPT }.map { it.name }

    /**
     * This layout as SQLite itself describes it ([describeSchema]): read back from an in-memory
     * database made with [createStatements], the very statements a fresh store is created with.
     * So what validation expects is never written down a second time and cannot drift from the
     * DDL. Built once per process.
     */
    val expected: Map<String, String> by lazy {
        val memory = SqliteConnection.open(":memory:")
        try {
            createStatements.forEach(memory::exec)
            describeSchema(memory)
        } finally {
            memory.close()
        }
    }

    /**
     * Why the schema read from [connection] is not this layout, or null if it is: exactly the
     * objects of [expected], each with exactly its description, and the required `meta` rows,
     * every known row of its own storage class and in range ([metaRowDamage]).
     * Anything else is damage, an extra index, trigger or view on a store table included. The
     * caller holds a transaction. Nothing is ever created or repaired here (§5.2 step 3).
     */
    fun validate(connection: SqliteConnection): String? {
        val found = describeSchema(connection)
        (found.keys - expected.keys).firstOrNull()?.let { return "unexpected schema object '$it'" }
        (expected.keys - found.keys).firstOrNull()?.let { return "missing schema object '$it'" }
        for ((name, description) in expected) {
            val actual = found.getValue(name)
            if (actual != description) return "'$name' is not this layout's: $actual"
        }

        val keys = HashSet<String>()
        connection.query("SELECT k FROM $META") { row -> keys += row.getText(0) }
        (requiredMetaKeys - keys).firstOrNull()?.let { return "missing meta row '$it'" }
        return metaDamage(connection)
    }

    /**
     * Does [table] exist in [connection] with exactly the shape this build creates it with? The
     * same description [validate] compares, for one table: what the read-only classification
     * asks of the two frozen marker tables before it believes a row of them.
     */
    fun hasExpectedShape(connection: SqliteConnection, table: String): Boolean =
        describeTable(connection, table) == expected[table]
}

/**
 * A schema as SQLite reports it, object name to a canonical description, for every object in
 * `sqlite_schema` (the automatic indexes of a `PRIMARY KEY` or `UNIQUE` included).
 *
 * ⚠⚠ **Read through the introspection pragmas, not from the `sql` text.** `sqlite_schema.sql` is
 * the statement as it was typed: whitespace, quoting and keyword case all survive in it, so
 * comparing it means normalising SQL, and a normaliser that is slightly wrong calls a healthy
 * store corrupt, which is durable and has no repair. The pragmas report the parsed shape, the
 * same for every spelling:
 * - a table: rowid or `WITHOUT ROWID`, `STRICT`, its foreign keys, and per column the position,
 *   name, declared type, `NOT NULL`, default, primary-key position and hidden or generated kind
 *   (`table_list`, `table_xinfo`, `foreign_key_list`);
 * - an index: its table, `UNIQUE`, its origin (created, or made for a constraint), whether it is
 *   partial, and per column the column, direction, collation and key or auxiliary
 *   (`index_list`, `index_xinfo`); an expression column has no table column and reads so;
 * - anything else (a trigger, a view): its type and table. None is expected, so each is damage.
 *
 * ⚠ What the pragmas do not report, and this therefore cannot see: a `CHECK` constraint, the
 * collation of a column no index covers, a conflict clause, and the text of a partial index's
 * `WHERE` or of an expression (that an index is partial, or has an expression, is seen).
 */
internal fun describeSchema(connection: SqliteConnection): Map<String, String> {
    val objects = ArrayList<Triple<String, String, String>>() // type, name, tbl_name
    connection.query("SELECT type, name, tbl_name FROM sqlite_schema") { row ->
        objects += Triple(row.getText(0), row.getText(1), row.getText(2))
    }
    val described = LinkedHashMap<String, String>()
    for ((type, name, table) in objects) {
        described[name] = when (type) {
            "table" -> describeTable(connection, name) ?: "table (unreadable)"
            "index" -> describeIndex(connection, name, table)
            else -> "$type on $table"
        }
    }
    return described
}

/** One table's description, or null when there is no table of that name. */
internal fun describeTable(connection: SqliteConnection, table: String): String? {
    val flags = connection.rows("pragma_table_list(?) WHERE schema = 'main'", listOf("type", "wr", "strict"), table)
    if (flags.isEmpty()) return null
    val columns = connection.rows(
        "pragma_table_xinfo(?)",
        listOf("cid", "name", "type", "\"notnull\"", "dflt_value", "pk", "hidden"),
        table,
    )
    val foreignKeys = connection.rows("pragma_foreign_key_list(?)", listOf("\"table\"", "\"from\"", "\"to\""), table)
    return "table ${flags.single()} columns=$columns foreignKeys=$foreignKeys"
}

private fun describeIndex(connection: SqliteConnection, index: String, table: String): String {
    val flags = connection.rows("pragma_index_list(?) WHERE name = ?", listOf("\"unique\"", "origin", "partial"), table, index)
    val columns = connection.rows("pragma_index_xinfo(?)", listOf("seqno", "cid", "name", "\"desc\"", "coll", "\"key\""), index)
    return "index on $table $flags columns=$columns"
}

/**
 * Every row of [source], each as its [columns] through `quote()` joined by `|`: a NULL reads
 * `NULL` and a string keeps its quotes, so no two different rows read alike. [arguments] are
 * bound as text, never spliced into the SQL: a name comes from the file being judged.
 */
private fun SqliteConnection.rows(source: String, columns: List<String>, vararg arguments: String): List<String> {
    val statement = prepare("SELECT " + columns.joinToString(" || '|' || ") { "quote($it)" } + " FROM $source")
    try {
        arguments.forEachIndexed { index, argument -> statement.bindText(index + 1, argument) }
        val rows = ArrayList<String>()
        while (statement.step()) rows += statement.getText(0)
        return rows
    } finally {
        statement.close()
    }
}

/** Prepares [sql], calls [onRow] for every row, finalizes. For the open's few uncached reads. */
internal inline fun SqliteConnection.query(sql: String, onRow: (SqliteStatement) -> Unit) {
    val statement = prepare(sql)
    try {
        while (statement.step()) onRow(statement)
    } finally {
        statement.close()
    }
}
