package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.schema.migration.InMemoryStoreMigration
import org.tenshu.core.common.schema.migration.coreVersionReachable
import org.tenshu.core.common.schema.migration.storeWipeReason
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.WipeableObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// IndexedDB-backed ObjectStore for the JS runtime.
//
// `ObjectStore` is synchronous (`fun storeObject`, `fun getObject`) and
// consumed inline by `DatabaseImpl`, so making the interface suspend would
// ripple into Android (SQLite) + Desktop wiring. Instead this impl is
// **cache-aside**. ⚠ It cannot do the JVM stores' bounded read-through
// (`bounded-store-cache.md`): IndexedDB has no synchronous read, so every
// row stays resident and the two strategies are deliberately forked.
//
//   - `open(...)` is suspend: opens IDB, reads every entry into an in-memory
//     `MutableMap<Long, DbEntry>` (the cache), then returns the store.
//     `JsRuntime` awaits this once via `JsRuntime.awaitReady()`; SDK methods
//     await the same gate before dispatching ops.
//   - `getObject` reads from the cache (sync).
//   - `storeObject` writes to the cache, fires listeners, and queues an async
//     IDB `put` on a child supervisor scope (fire-and-forget). The tab-close
//     window where a queued write can be lost is the durability gap.
//   - `removeObject` symmetric: cache delete + async IDB delete.
//   - Listeners stay in-memory only — they're per-session subscriptions, not
//     persistent.
//
// Cross-tab: each tab opens its own JsRuntime singleton with its own cache.
// IDB is shared across tabs but cache updates from another tab don't
// propagate, which is why only the runtime holding the lease writes
// (`RuntimeLease`, enforced by `StoreFence.kt`).
//
// Bundle cost: raw `js("indexedDB.open(...)")` bindings + `suspendCancellable`
// shims, no IDB library.
internal class IndexedDbObjectStore private constructor(
    private val cache: MutableMap<Long, DbEntry>,
    private val db: dynamic,
    parentScope: CoroutineScope,
    // The fence this store claimed on open (`StoreFence.kt`); null = opened read-only, writes refused.
    private val fenceToken: String?,
    // Told on the first write the fence refuses — another runtime claimed this database.
    onFencedOut: () -> Unit,
) : ObjectStore, WipeableObjectStore {

    private val fence = FenceReporter("objects", onFencedOut)

    private val writeScope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    private val listeners: MutableMap<Long, MutableList<(DbEntry) -> Unit>> = mutableMapOf()

    override fun storeObject(dbEntry: DbEntry) {
        cache[dbEntry.dataIdHash] = dbEntry
        notifyListeners(dbEntry.dataIdHash, dbEntry)
        writeScope.launch { fence.report(putEntry(db, fenceToken, dbEntry)) }
    }

    override fun getObject(dataIdHash: Long): DbEntry? = cache[dataIdHash]

    override fun setListener(
        dataIdHash: Long,
        listener: (update: DbEntry) -> Unit,
    ) {
        val list = listeners.getOrPut(dataIdHash) { mutableListOf() }
        list.add(listener)
        cache[dataIdHash]?.let(listener)
    }

    override fun removeListener(dataIdHash: Long) {
        listeners.remove(dataIdHash)
    }

    override fun removeObject(dataIdHash: Long) {
        cache.remove(dataIdHash)
        writeScope.launch { fence.report(deleteEntry(db, fenceToken, dataIdHash)) }
    }

    /**
     * Adds any service in [declared] that the recorded vector does not already
     * mention (`core-schema-evolution.md` §8.9) — for services contributed by a plugin folded in *after*
     * this store opened, whose versions the open-time gate never saw.
     *
     * ⚠⚠ **Never overwrites an existing entry.** A late-registered service already
     * recorded at an older version is real drift this open missed; stamping current
     * over it would hide it permanently, while leaving it stale lets the next open's
     * migration walk catch it. Fail towards detecting later, never towards silence.
     */
    suspend fun recordNewServiceVersions(declared: Map<String, Int>) {
        val recorded = readServiceVersions(db) ?: return
        val merged = declared + recorded // existing wins, by construction
        if (merged != recorded) writeServiceVersions(db, merged)
    }

    // Suspends until every queued IDB write has committed. Tests use this to
    // guarantee an entry is on disk before reopening the database.
    suspend fun flush() {
        val pending = writeScope.coroutineContext[Job]?.children?.toList().orEmpty()
        pending.joinAll()
    }

    /**
     * Flush every queued write, then refuse further ones (a launch on the cancelled scope never
     * runs) and close the IDB connection. The runtime-lease hand-over (`RuntimeLease.yieldNow`)
     * calls this before it releases the lock, so the next tab's hydration reads everything this one
     * wrote, and nothing lands after. ⚠ A write queued *while* this flush runs is dropped — a
     * suffix of the queue, never a hole in it, so the disk stays a consistent prefix.
     */
    suspend fun close() {
        flush()
        writeScope.cancel()
        db.close()
    }

    /**
     * Stop without flushing — the lock was STOLEN (`RuntimeLease.lost`), so another tab may already
     * have hydrated and written; anything still queued here is stale by definition. ⚠ Cancelling
     * cannot recall a transaction IndexedDB already started; the fence (`StoreFence.kt`) is what
     * refuses those.
     */
    fun abandon() {
        writeScope.cancel()
    }

    // Wipes the in-memory cache and the on-disk store. Used by
    // `Session.clear({purgeRealm: true})` for revocation cleanup. Awaits
    // any in-flight writes first so we don't race a queued put against
    // the clear and resurrect the entry.
    /** The discard path (`ios-identity-reinstall-gate.md` D3) — see [WipeableObjectStore]. */
    override suspend fun wipeAllObjects() = clearAll()
    suspend fun clearAll() {
        flush()
        cache.clear()
        // ⚠⚠ Fenced: a tab frozen inside `Session.clear({purgeRealm})` /
        // `ForgetThisDevice` that thaws after a steal must not wipe the new holder's rows.
        fence.report(
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME, META_STORE_NAME), META_STORE_NAME, fenceToken) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).clear()
            },
        )
    }

    private fun notifyListeners(dataIdHash: Long, updated: DbEntry) {
        listeners[dataIdHash]?.toList()?.forEach { it(updated) }
    }

    companion object {
        private const val OBJECT_STORE_NAME = "objects"

        // Small key/value store holding the storage-format-version marker. It exists
        // from IDB DB version 2: that version fires `onupgradeneeded` on a
        // single-store database so the `meta` store is created there.
        private const val META_STORE_NAME = "meta"
        private const val FORMAT_VERSION_KEY = "formatVersion"

        /**
         * Recorded per-service version vector — the `schema_service_versions`
         * twin, stored as one `serviceId=version` line per service under a single
         * meta key. Absent on a database written before per-service versions were
         * recorded, which the migrator reads as "no record" and stamps current
         * without touching rows.
         */
        private const val SERVICE_VERSIONS_KEY = "serviceVersions"
        private const val DB_VERSION = 2

        /**
         * @param migrations when supplied, the database gates on the **per-service**
         *   versions as well as the format version (`core-schema-evolution.md` §8.9).
         *   ⚠⚠ Passing null is only safe for a database no service version
         *   governs: a service-owned reshape deliberately does NOT bump
         *   [TENSHU_STORAGE_FORMAT_VERSION], so a format-only gate would see nothing
         *   and hand v-old rows to new code — silent positional garbling.
         */
        suspend fun open(
            dbName: String,
            scope: CoroutineScope,
            migrations: StoreSchemaMigrations? = null,
            // ⚠ Only the runtime holding the lease may claim — claiming fences every other writer
            // out. A read-only open (a test's simulated reload) passes false and can never write.
            claim: Boolean = true,
            onFencedOut: () -> Unit = {},
        ): IndexedDbObjectStore {
            val db = openDatabase(dbName)
            // ⚠ The open path's own writes below are deliberately UNFENCED — the Gate-1 wipe,
            // `replaceAllEntries`, `writeFormatVersion`, and the add-only service-version metadata
            // (`writeServiceVersions` / `recordNewServiceVersions`): only the claimer runs them,
            // under the lock, before anything else could write.
            // Claim before reading: writes a stale tab lands before this are in what we read; any
            // after it are refused.
            val fenceToken = if (claim) newFenceToken().also { claimFence(db, META_STORE_NAME, it) } else null
            // ── Gate 1: the core version, by the SHARED rule the JVM store uses.
            val recordedVersion = readFormatVersion(db)
            val coreFloor = migrations?.coreReadableFloor() ?: TENSHU_STORAGE_FORMAT_VERSION
            val coreReachable = coreVersionReachable(
                recorded = recordedVersion,
                current = TENSHU_STORAGE_FORMAT_VERSION,
                floor = coreFloor,
            )
            if (!coreReachable) {
                // Data loss is written down, never implied (`core-schema-evolution.md` §8.4).
                storeWipeReason(coreReachable, recordedVersion, TENSHU_STORAGE_FORMAT_VERSION, coreFloor)
                    ?.let { app.tenshu.diagnostics.Diagnostics.w("ObjectStore", "wiping store — $it.") }
                clearAllOnDisk(db)
            }
            if (recordedVersion != TENSHU_STORAGE_FORMAT_VERSION) {
                writeFormatVersion(db, TENSHU_STORAGE_FORMAT_VERSION)
            }

            val entries = readAllEntries(db)
            var cache = HashMap<Long, DbEntry>(entries.size).apply {
                entries.forEach { put(it.dataIdHash, it) }
            }

            // ── Gate 2: the per-service versions, via the shared load-migrate-persist
            // seam — the SAME StoreSchemaMigrator the JVM store runs, so the three
            // stores cannot drift in policy, only in how they write bytes. The rows are
            // already in memory for the cache, so handing them over costs nothing.
            //
            // ⚠⚠ FAIL CLOSED: any throw degrades to a wipe. A migration bug on a
            // platform whose tests do not run in CI must not be able to *corrupt* rows;
            // a resync is recoverable, a garbled store is not.
            if (migrations != null) {
                val migrated = runCatching {
                    InMemoryStoreMigration(migrations).run(cache, readServiceVersions(db))
                }.getOrNull()

                if (migrated == null) {
                    println(
                        "IndexedDbObjectStore: migration failed — wiping store rather than " +
                            "risking mis-shaped rows.",
                    )
                    clearAllOnDisk(db)
                    cache = HashMap()
                    writeServiceVersions(db, migrations.declaredServiceVersions())
                } else {
                    if (migrated.wiped) {
                        println(
                            "IndexedDbObjectStore: store wiped by the schema migrator " +
                                "(below floor, or written by newer code).",
                        )
                    }
                    if (migrated.changedRows > 0) {
                        // ⚖️ IndexedDB gives what iOS has to hand-roll: `replaceAllEntries`
                        // is ONE readwrite transaction, so the migrated state becomes
                        // visible all-or-nothing. A crash before it leaves every row
                        // from-shaped and the next open re-runs deterministically.
                        replaceAllEntries(db, migrated.rows.values.toList())
                        cache = HashMap(migrated.rows)
                    }
                    // Stamp last: a crash before this re-runs the same migration over
                    // rows that either fully committed or were never touched.
                    writeServiceVersions(db, migrated.serviceVersions)
                }
            }
            return IndexedDbObjectStore(cache, db, scope, fenceToken, onFencedOut)
        }

        // Opens the IDB database, creating the object + meta stores on first use
        // (or on version bumps). Resolves with the IDBDatabase handle.
        private suspend fun openDatabase(dbName: String): dynamic =
            suspendCancellableCoroutine { cont ->
                val version = DB_VERSION
                val request = js("indexedDB.open(dbName, version)")
                request.onupgradeneeded = { event: dynamic ->
                    val database = event.target.result
                    if (!database.objectStoreNames.contains(OBJECT_STORE_NAME)) {
                        database.createObjectStore(OBJECT_STORE_NAME)
                    }
                    if (!database.objectStoreNames.contains(META_STORE_NAME)) {
                        database.createObjectStore(META_STORE_NAME)
                    }
                    Unit
                }
                request.onsuccess = { _: dynamic ->
                    cont.resume(request.result)
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB open failed: ${request.error}"),
                    )
                }
            }

        // Reads the recorded storage-format version from the `meta` store, or 0 when
        // absent (fresh DB / pre-format-version DB). Stored as a string, mirroring how
        // Long keys are stringified elsewhere in this store.
        private suspend fun readFormatVersion(db: dynamic): Int =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(META_STORE_NAME, "readonly")
                val store = tx.objectStore(META_STORE_NAME)
                val request = store.get(FORMAT_VERSION_KEY)
                request.onsuccess = { _: dynamic ->
                    val raw: dynamic = request.result
                    val parsed = if (raw == null || raw == undefined) 0
                    else (raw.toString() as String).toIntOrNull() ?: 0
                    cont.resume(parsed)
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB meta get failed: ${request.error}"),
                    )
                }
            }

        /** The recorded per-service vector, or null when the key is absent (written before per-service versions, or fresh). */
        private suspend fun readServiceVersions(db: dynamic): Map<String, Int>? =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(META_STORE_NAME, "readonly")
                val store = tx.objectStore(META_STORE_NAME)
                val request = store.get(SERVICE_VERSIONS_KEY)
                request.onsuccess = { _: dynamic ->
                    val raw: dynamic = request.result
                    cont.resume(
                        if (raw == null || raw == undefined) {
                            null
                        } else {
                            (raw.toString() as String).lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .mapNotNull { line ->
                                    val at = line.lastIndexOf('=')
                                    if (at <= 0) return@mapNotNull null
                                    val version = line.substring(at + 1).toIntOrNull()
                                        ?: return@mapNotNull null
                                    line.substring(0, at) to version
                                }
                                .toMap()
                        },
                    )
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB meta get failed: ${request.error}"),
                    )
                }
            }

        private suspend fun writeServiceVersions(db: dynamic, versions: Map<String, Int>): Unit =
            suspendCancellableCoroutine { cont ->
                val text = versions.entries
                    .sortedBy { it.key }
                    .joinToString("\n") { (serviceId, version) -> "$serviceId=$version" }
                val tx = db.transaction(META_STORE_NAME, "readwrite")
                val store = tx.objectStore(META_STORE_NAME)
                store.put(text, SERVICE_VERSIONS_KEY)
                tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
                tx.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB meta put failed: ${tx.error}"),
                    )
                }
            }

        private suspend fun writeFormatVersion(db: dynamic, version: Int): Unit =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(META_STORE_NAME, "readwrite")
                val store = tx.objectStore(META_STORE_NAME)
                store.put(version.toString(), FORMAT_VERSION_KEY)
                tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
                tx.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB meta put failed: ${tx.error}"),
                    )
                }
            }

        private suspend fun readAllEntries(db: dynamic): List<DbEntry> =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readonly")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                val request = store.getAll()
                request.onsuccess = { _: dynamic ->
                    val raw = request.result.unsafeCast<Array<dynamic>>()
                    val out = ArrayList<DbEntry>(raw.size)
                    for (item in raw) out.add(decodeEntry(item))
                    cont.resume(out)
                }
                request.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB getAll failed: ${request.error}"),
                    )
                }
            }

        // ⚠⚠ Fenced: `meta` joins the transaction so the fence read and the write are atomic.
        private suspend fun putEntry(db: dynamic, token: String?, entry: DbEntry): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME, META_STORE_NAME), META_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).put(encodeEntry(entry), entry.dataIdHash.toString())
            }

        private suspend fun deleteEntry(db: dynamic, token: String?, dataIdHash: Long): Boolean =
            fencedWrite(db, arrayOf(OBJECT_STORE_NAME, META_STORE_NAME), META_STORE_NAME, token) { tx ->
                tx.objectStore(OBJECT_STORE_NAME).delete(dataIdHash.toString())
            }

        /**
         * Makes [entries] the object store's entire content in **one** readwrite
         * transaction — clear + put-all, committed together (the migrator's persist step).
         *
         * ⚖️ This is the atomicity the iOS twin has to hand-roll with a staging
         * directory and a commit marker: IndexedDB gives it for free, so a crash
         * mid-migration leaves every row `from`-shaped and the next open re-runs the
         * same deterministic migration.
         */
        private suspend fun replaceAllEntries(db: dynamic, entries: List<DbEntry>): Unit =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readwrite")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                store.clear()
                entries.forEach { entry ->
                    store.put(encodeEntry(entry), entry.dataIdHash.toString())
                }
                tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
                tx.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB migration commit failed: ${tx.error}"),
                    )
                }
            }

        private suspend fun clearAllOnDisk(db: dynamic): Unit =
            suspendCancellableCoroutine { cont ->
                val tx = db.transaction(OBJECT_STORE_NAME, "readwrite")
                val store = tx.objectStore(OBJECT_STORE_NAME)
                store.clear()
                tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
                tx.onerror = { _: dynamic ->
                    cont.resumeWithException(
                        IllegalStateException("IndexedDB clear failed: ${tx.error}"),
                    )
                }
            }
    }
}

// `DbEntry` ↔ JS object encoding. Long fields go through `toString()` /
// `toLong()` because IDB structured-clone has no native Long and BigInt is
// not a valid IDB key. ByteArray maps to Int8Array, which is the JS runtime
// representation Kotlin/JS already uses, so it round-trips through structured
// clone unchanged.
private fun encodeEntry(entry: DbEntry): dynamic {
    val obj: dynamic = js("({})")
    obj.type = entry.type
    obj.dataIdHash = entry.dataIdHash.toString()
    obj.fields = entry.fields.map { encodeValue(it) }.toTypedArray()
    val props: dynamic = js("({})")
    entry.properties.forEach { (k, v) -> props[k] = encodeValue(v) }
    obj.properties = props
    return obj
}

private fun decodeEntry(stored: dynamic): DbEntry {
    val rawFields = stored.fields.unsafeCast<Array<dynamic>>()
    val fields = ArrayList<DbValue>(rawFields.size).apply {
        for (f in rawFields) add(decodeValue(f))
    }
    val rawProps = stored.properties
    val props = mutableMapOf<String, DbValue>()
    val keys = js("Object.keys(rawProps)").unsafeCast<Array<String>>()
    for (k in keys) props[k] = decodeValue(rawProps[k])
    return DbEntry(
        type = (stored.type as String),
        dataIdHash = (stored.dataIdHash as String).toLong(),
        fields = fields,
        properties = props,
    )
}

private fun encodeValue(value: DbValue): dynamic {
    val obj: dynamic = js("({})")
    when (value) {
        is DbValue.StringValue -> { obj.t = "s"; obj.v = value.value }
        is DbValue.LongValue -> { obj.t = "l"; obj.v = value.value.toString() }
        is DbValue.ByteArrayValue -> { obj.t = "b"; obj.v = value.value }
        is DbValue.BooleanValue -> { obj.t = "o"; obj.v = value.value }
        is DbValue.GenericValue -> { obj.t = "g"; obj.ty = value.typeId; obj.v = encodeValue(value.payload) }
        DbValue.NullValue -> { obj.t = "n" }
    }
    return obj
}

private fun decodeValue(stored: dynamic): DbValue =
    when (val tag = stored.t as String) {
        "s" -> DbValue.StringValue(stored.v as String)
        "l" -> DbValue.LongValue((stored.v as String).toLong())
        "b" -> DbValue.ByteArrayValue(stored.v.unsafeCast<ByteArray>())
        "o" -> DbValue.BooleanValue(stored.v as Boolean)
        "g" -> DbValue.GenericValue(stored.ty as String, decodeValue(stored.v))
        "n" -> DbValue.NullValue
        else -> error("IndexedDbObjectStore: unknown DbValue tag '$tag'")
    }
