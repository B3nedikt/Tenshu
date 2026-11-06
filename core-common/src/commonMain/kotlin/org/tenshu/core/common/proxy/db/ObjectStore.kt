package org.tenshu.core.common.proxy.db

interface ObjectStore {

    fun storeObject(
        dbEntry: DbEntry
    )

    fun getObject(
        dataIdHash: Long
    ): DbEntry?

    /**
     * Whether a live row exists at [dataIdHash], **without deserialising it**.
     *
     * Defaulted to a full read so every existing store keeps working; a store that can answer
     * from an index should override, because the default's cost is exactly backwards for the
     * common case — a probe that finds a row pays the whole deserialise it did not want.
     */
    fun hasObject(dataIdHash: Long): Boolean = getObject(dataIdHash) != null

    fun setListener(
        dataIdHash: Long,
        listener: (update: DbEntry) -> Unit
    )

    fun removeListener(
        dataIdHash: Long
    )

    fun removeObject(
        dataIdHash: Long
    )

    /**
     * Every stored row whose type is in [typeIds], or **null when this store
     * cannot enumerate** (E4-3b, `core-schema-evolution.md` §8.3.4).
     *
     * ⚠⚠ **Null and empty mean opposite things, and conflating them loses data.**
     * Empty is "this store holds no such rows"; null is "do not ask me". A
     * compactor that read an unsupported store as empty would write a receipt
     * claiming it had re-emitted a service's state, and peers would skip the work
     * — so the *absence of the capability* must be distinguishable from the
     * absence of rows.
     *
     * ⚠ Deliberately narrow. This is not a general query surface: the store is
     * hash-keyed and has no index beyond it (`core-relation-system.md` §12 — the
     * relation array is the type index for reads). It exists because compaction
     * needs *all rows of a type* at runtime, which the relation arrays cannot
     * promise for a type outside a maintained relation. Reads still go through
     * the chain DSL.
     */
    fun rowsOfTypes(typeIds: Set<String>): List<DbEntry>? = null

    /**
     * One **bounded, resumable** scan for rows of [typeId]: hash-ordered, strictly after
     * [afterHash], yielding at most [matchLimit] matches and examining at most [examineLimit]
     * rows. `null` when this store cannot enumerate (same posture as [rowsOfTypes] — see
     * `SchemaCompactor`).
     *
     * ⚠ Exists because [rowsOfTypes] is unbounded, and for the type that needs sweeping most that
     * is fatal: a measured store held **77,800 `tenshu/sync/block` rows totalling 129 MB**, because
     * blocks carry their encrypted payload. Loading them to find the garbage would OOM the very
     * devices the garbage is hurting (`core-local-store-orphan-gc.md` G3). Paging by hash keeps
     * peak memory bounded and lets a sweep resume from its last cursor.
     *
     * ## ⚠⚠ Why there are TWO budgets, and why the result carries [TypeScanPage.exhausted]
     *
     * This replaced a `rowsOfTypePaged(typeId, afterHash, limit)` that bounded **matches only**.
     * On a store with a type index (`SqliteObjectStore`) that is the same thing. On one whose only
     * index is the hash (the retired iOS file store — the type lived *inside* the blob) it is not:
     * finding `limit` matches costs an unbounded number of reads, and for a type that is nearly
     * extinct it costs **all of them**. A real phone with 106,613 rows and ~3 surviving blocks
     * decoded the entire store on every pass, holding the store lock throughout — which on iOS
     * route 1 was a *cross-process* flock, so it stalled co-resident apps too.
     *
     * That version's KDoc considered a scan budget and rejected it, for a reason that was sound:
     * a short page would become ambiguous between *"no more rows of this type"* and *"I stopped
     * early"*, and reading the second as the first ends a sweep with garbage still on disk.
     *
     * [TypeScanPage.exhausted] is the answer to exactly that objection. The two conditions are now
     * separately expressible, so a budget can no longer be misread as completion — and callers
     * must key their "done" on `exhausted`, **never** on `rows.size < matchLimit`.
     *
     * [TypeScanPage.nextCursor] is where the scan *reached*, not where it last matched. Resuming
     * from the last match would re-examine the whole non-matching tail on the following pass.
     *
     * Ordering is by `hash`, not insertion — it only has to be *stable and total* so paging cannot
     * skip or repeat a row.
     */
    fun scanRowsOfType(
        typeId: String,
        afterHash: Long,
        matchLimit: Int,
        examineLimit: Int,
    ): TypeScanPage? = null

    /**
     * One bounded pass of [scanRowsOfType].
     *
     * @property rows       the matches found, hash-ordered.
     * @property nextCursor the hash the scan reached — pass it back as `afterHash` to resume.
     *                      Never below the `afterHash` it was given.
     * @property exhausted  true only when the scan ran off the end of the hash space, i.e. there
     *                      is provably nothing left to find. ⚠ A pass that stopped on either
     *                      budget reports `false` even when it happened to return no rows.
     */
    data class TypeScanPage(
        val rows: List<DbEntry>,
        val nextCursor: Long,
        val exhausted: Boolean,
    )

    /**
     * Durability barrier. `DatabaseImpl.transaction` calls this once per
     * transaction commit so a durable store can bound its on-disk loss
     * window to the unflushed tail (one in-flight transaction) rather than
     * an arbitrary debounce — see `core-sql-persistence.md` §2.5/§3.3.
     *
     * Default no-op for `InMemoryObjectStore`, `IndexedDbObjectStore` and the iOS
     * shared replica; only `SqliteObjectStore` does real work here.
     *
     * Intentionally **non-suspend and fire-and-forget**. `transaction` is
     * synchronous common code; making this suspend would force `transaction`
     * (and the `Database` interface) suspend, rippling into ~99 consumers —
     * out of scope and explicitly forbidden by the proposal. The durable
     * store enqueues a commit point and returns immediately; the writer
     * commits asynchronously, so "durable on the boundary" means *bounded
     * loss*, not synchronous fsync.
     *
     * Not part of the frozen observer/listener seam (§2.4) — this is the
     * one SPI addition the proposal sanctions.
     */
    fun flushBarrier() {}

    /**
     * Whether this store holds **no persisted objects** — used by the sync
     * cursor/store desync self-heal (`sync-cursor-store-lifecycle.md` §C):
     * a store hydrated empty from disk while the sync cursor (persisted
     * separately in `SyncManager`'s `storageDelegate`) is non-zero means the
     * data was reset out from under the cursor (an iOS route-1 container
     * wipe / reinstall leaves the Keychain-backed cursor while the object
     * store file is gone), so the cursor must be reset to back-fill.
     *
     * Default is `false` (the conservative answer: "not known to be empty" →
     * never trigger a reset). Real stores override with their actual count so
     * the self-heal can fire; a store that can't answer cheaply keeps the
     * default and simply never self-heals.
     */
    fun isEmpty(): Boolean = false

    /**
     * Load the persisted sync cursor (`sync-cursor-store-lifecycle.md` Option A1).
     *
     * The pull cursor rides the object store as one reserved, self-overwriting row so it shares
     * the **exact lifecycle** of the applied data: wiped when the store is wiped (an iOS route-1
     * container wipe / reinstall), persisted when it persists. That makes the cursor/store desync
     * (a non-zero cursor over an empty store → `since=<cursor>` skips the backlog forever)
     * *structurally unrepresentable*, replacing the storageDelegate/Keychain backing whose
     * independent lifecycle caused the bug. Default 0 (fresh / wiped store → back-fill from 0).
     *
     * Implemented once here in terms of [getObject]/[storeObject], so every store — SQLite, file,
     * in-memory, and any future backend — gets it for free with no per-impl persistence.
     */
    fun loadSyncCursor(): Long =
        (getObject(SYNC_CURSOR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value ?: 0L

    /** Persist the sync cursor as the reserved store row (see [loadSyncCursor]). */
    fun storeSyncCursor(value: Long) =
        storeObject(DbEntry(SYNC_CURSOR_TYPE, SYNC_CURSOR_HASH, listOf(DbValue.LongValue(value))))

    /**
     * Load the persisted **tombstone** pull cursor, or `null` when the row has never been
     * written (`sync-lane-cursor-split.md` L1).
     *
     * A sibling of [loadSyncCursor] with the same lifecycle, deliberately its **own row**
     * rather than a second field on the cursor row: appending a positional field is a
     * reshape, and one field is not worth bending that rule for even on a row that never
     * crosses the wire.
     *
     * ⚠ Returns `Long?`, not `0L`, precisely so the caller can tell **absent** from **zero**.
     * An upgrading device must seed this from [loadSyncCursor] — seeding 0 would replay every
     * tombstone in history against a store that already applied them. (Idempotent in
     * principle; not worth doing on every device to find out.)
     */
    fun loadTombstoneCursor(): Long? =
        (getObject(TOMBSTONE_CURSOR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the tombstone cursor as its reserved store row (see [loadTombstoneCursor]). */
    fun storeTombstoneCursor(value: Long) =
        storeObject(
            DbEntry(TOMBSTONE_CURSOR_TYPE, TOMBSTONE_CURSOR_HASH, listOf(DbValue.LongValue(value)))
        )

    /**
     * Load the **backfill floor** — how far back from the relay's tip this device has walked while
     * catching up newest-first (`sync-device-catch-up.md` C2 / §5.4).
     *
     * Three states, and the distinction matters:
     *  - `null` — backfill has never run. The next page starts **from the tip**.
     *  - `> 0`  — in progress; the next page asks for everything below this seq.
     *  - `0`    — **complete**. Either the walk reached the bottom of the log, or it met the forward
     *             cursor coming the other way; in both cases every seq is covered by one pass or the
     *             other, so there is nothing left to walk and the floor's exact value is moot.
     *
     * ⚠ `null` and `0` must stay distinguishable — a fresh device and a finished one differ only
     * here, and collapsing them would either re-walk the whole log forever or never walk it at all.
     * Hence the nullable read, exactly as [loadTombstoneCursor] does for the same reason.
     */
    fun loadBackfillCursor(): Long? =
        (getObject(BACKFILL_CURSOR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the backfill floor as its reserved store row (see [loadBackfillCursor]). */
    fun storeBackfillCursor(value: Long) =
        storeObject(
            DbEntry(BACKFILL_CURSOR_TYPE, BACKFILL_CURSOR_HASH, listOf(DbValue.LongValue(value)))
        )

    /**
     * Drop the backfill floor row, returning this device to the `null` state — *never walked* —
     * so the backward walk starts again from the tip.
     *
     * ⚠⚠ **This is the only way out of the `0` latch, and it exists because there was none.**
     * `SyncManager.backfillCursor`'s setter is `set(value) { store(value ?: return) }`, so
     * assigning `null` is silently a no-op — the state that means "walk from the tip" is
     * unreachable by assignment. A device that latched `0` prematurely was therefore stuck
     * permanently, with no code path anywhere able to re-open it. Deleting the row is what makes
     * `null` writable at all.
     *
     * ⚠ Re-walking is safe but not free: ingest is HLC-guarded and idempotent, so re-applying a
     * page cannot corrupt anything, but the walk does re-descend to the target.
     */
    fun clearBackfillCursor() = removeObject(BACKFILL_CURSOR_HASH)

    /**
     * The **backfill target** — the seq the backward walk must stop at, captured at handover
     * (`sync-device-catch-up.md` §5.4).
     *
     * ⚠⚠ This exists because the forward cursor is *promoted to a live tail* the moment backfill
     * engages, so it can no longer serve as the walk's stopping point — by the time backfill asks,
     * the forward cursor is up at the tip and the walk would terminate on its very first page,
     * leaving the whole history unread. The target is the forward cursor's value **before** that
     * promotion: the boundary below which the device has already read everything.
     *
     * `null` until the first backfill page; read as `0` (walk to the bottom) when absent.
     */
    fun loadBackfillTarget(): Long? =
        (getObject(BACKFILL_TARGET_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the backfill target as its reserved store row (see [loadBackfillTarget]). */
    fun storeBackfillTarget(value: Long) =
        storeObject(
            DbEntry(BACKFILL_TARGET_TYPE, BACKFILL_TARGET_HASH, listOf(DbValue.LongValue(value)))
        )

    /** Drop the backfill target row; the next handover captures it again. */
    fun clearBackfillTarget() = removeObject(BACKFILL_TARGET_HASH)

    /**
     * The **first-pull marker** (`ios-sync-activity-indicator.md` §3.1): whether this store has
     * ever finished a first forward pull. `null` = no row, [FIRST_PULL_STARTED], or
     * [FIRST_PULL_COMPLETED].
     *
     * ⚠⚠ The forward cursor cannot answer this. It reads `0` before any pull **and** after a pull
     * of an empty realm, because an empty page reports `nextCursor == since`. So the device
     * records the fact itself, in a row that shares the cursor's lifecycle: wiped with the store.
     *
     * ⚠ A small **state**, not a presence flag, so a process killed mid-pull (`STARTED` with a
     * cursor already persisted) is not mistaken for a device upgraded from a build without the
     * marker (no row, cursor `> 0`).
     */
    fun loadFirstPullMarker(): Long? =
        (getObject(FIRST_PULL_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the first-pull marker as its reserved store row (see [loadFirstPullMarker]). */
    fun storeFirstPullMarker(value: Long) =
        storeObject(DbEntry(FIRST_PULL_TYPE, FIRST_PULL_HASH, listOf(DbValue.LongValue(value))))

    /**
     * Drop the first-pull marker row, returning the store to *never pulled*.
     *
     * ⚠ The marker describes one realm's log. Whatever returns the forward cursor to zero for a
     * different realm must drop this with it, or the new realm inherits `COMPLETED`.
     */
    fun clearFirstPullMarker() = removeObject(FIRST_PULL_HASH)

    /**
     * ⚠ Whether this store has **never** pulled anything at all: no first-pull marker, both forward
     * cursors at zero, and no backward-walk row. Only then is a row's absence not a pull artifact
     * (`ios-shared-store-sqlite.md` B.22.7). A store mid-pull, or one whose pull this process cannot
     * see (a shared replica another process is filling), is not this.
     */
    fun hasNeverPulled(): Boolean =
        loadFirstPullMarker() == null &&
            loadSyncCursor() == 0L &&
            (loadTombstoneCursor() ?: 0L) == 0L &&
            loadBackfillCursor() == null

    /**
     * ⚠⚠ **Whether this store has pulled its realm's whole history**, by its own reserved rows
     * ([pulledHistoryComplete], `ios-shared-store-sqlite.md` B.22.6): the first forward pull
     * finished **and** no backward walk has pages left. Until then a row's absence is not evidence:
     * the C2 walk applies the log newest-first, so a file arrives before the folder it sits in.
     */
    fun hasPulledItsHistory(): Boolean = pulledHistoryComplete(
        firstPullMarker = loadFirstPullMarker(),
        forwardCursorMoved = loadSyncCursor() > 0L || (loadTombstoneCursor() ?: 0L) > 0L,
        backfillFloor = loadBackfillCursor(),
    )

    /**
     * How far the G3 retired-block sweep has scanned (`core-local-store-orphan-gc.md`), or
     * [SWEEP_COMPLETE] once it has run off the end of the hash space.
     *
     * ⚠⚠ **Durable because the sweep's cost is per-*scan*, not per-row.** The population it
     * collects is legacy — G1 closed the tap — so on a swept device every later pass is pure
     * overhead, and on a store with no type index that overhead is a full read of every row. Held
     * in memory (as it was) that means paying it again at **every process start**, forever.
     *
     * `null` = never scanned. Absent on every store predating this row, which reads correctly as
     * "start from the beginning".
     */
    fun loadRetiredBlockSweepCursor(): Long? =
        (getObject(SWEEP_CURSOR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the sweep cursor as its reserved store row (see [loadRetiredBlockSweepCursor]). */
    fun storeRetiredBlockSweepCursor(value: Long) =
        storeObject(
            DbEntry(SWEEP_CURSOR_TYPE, SWEEP_CURSOR_HASH, listOf(DbValue.LongValue(value)))
        )

    /**
     * Resume point for the **orphaned-file** sweep (`core-local-store-orphan-gc.md` G4a), or
     * `null` if it has never run. [SWEEP_COMPLETE] means finished — never scan again.
     *
     * ⚠ Its own reserved row, not shared with [loadRetiredBlockSweepCursor]: the two sweeps scan
     * different types, finish independently, and one having reached the end of the hash space says
     * nothing about the other.
     */
    fun loadOrphanedFileSweepCursor(): Long? =
        (getObject(FILE_SWEEP_CURSOR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Persist the orphaned-file sweep cursor (see [loadOrphanedFileSweepCursor]). */
    fun storeOrphanedFileSweepCursor(value: Long) =
        storeObject(
            DbEntry(
                FILE_SWEEP_CURSOR_TYPE,
                FILE_SWEEP_CURSOR_HASH,
                listOf(DbValue.LongValue(value)),
            )
        )

    /**
     * **Re-arm** the orphaned-file sweep: forget the cursor so the next pass walks from the
     * beginning, [SWEEP_COMPLETE] included.
     *
     * ⚠⚠ **[SWEEP_COMPLETE] is terminal, and rows were found stranded after it.** G4a was written
     * as one-time cleanup on the premise that its cause was closed — `DeletedFolderSubtreeReaper`
     * cascades, so no new stranded subtree appears. The measurement below falsifies that premise.
     *
     * The reaper degrades to *"descendants not reaped"* whenever it cannot read the children it
     * was about to delete, and it degrades rather than throws for a good reason: an escaping throw
     * inside sync ingest's observer fanout takes down the whole tick. The folder row dies anyway,
     * so whatever it skipped is orphaned — on a store whose sweep has already finished.
     *
     * Measured on the maintainer's device 2026-08-16, with both this cursor and the block sweep's
     * reading [SWEEP_COMPLETE]: **1 154 `tenshu/file` rows under `/Backup/OnePlus CPH2581/Camera`,
     * whose folder row is gone.** They block re-upload as well as waste disk — `UploadFileImplementation`
     * tests by path `DataId`, so a stranded row occupies its destination and every retry resolves
     * to `Skipped`.
     *
     * ⚠ Cleared **only** on that degrade path, never on every folder delete: a cascade that
     * succeeded leaves nothing behind, and re-walking the store on each successful delete would
     * reinstate the per-launch full read the sentinel exists to remove.
     */
    fun clearOrphanedFileSweepCursor() = removeObject(FILE_SWEEP_CURSOR_HASH)

    /**
     * Which one-shot sweep re-arm this device has spent, or `null` for none — see
     * [FILE_SWEEP_REPAIR_HASH].
     */
    fun loadOrphanedFileSweepRepair(): Long? =
        (getObject(FILE_SWEEP_REPAIR_HASH)?.fields?.firstOrNull() as? DbValue.LongValue)?.value

    /** Record a spent one-shot re-arm (see [loadOrphanedFileSweepRepair]). */
    fun storeOrphanedFileSweepRepair(version: Long) =
        storeObject(
            DbEntry(
                FILE_SWEEP_REPAIR_TYPE,
                FILE_SWEEP_REPAIR_HASH,
                listOf(DbValue.LongValue(version)),
            )
        )

    companion object {
        /**
         * Reserved [DbEntry.dataIdHash] for the sync-cursor row. A fixed sentinel no real
         * `DataId` hashes to; the cursor is thus a single row that overwrites itself. It counts
         * toward [isEmpty] like any row (so a store holding only the cursor reads non-empty),
         * which keeps the Option-C self-heal from ever misfiring once A1 co-locates the cursor.
         */
        const val SYNC_CURSOR_HASH: Long = Long.MIN_VALUE
        const val SYNC_CURSOR_TYPE: String = "tenshu/sync/cursor"

        /**
         * Reserved hash for the tombstone lane's cursor — the sentinel beside
         * [SYNC_CURSOR_HASH], same reasoning (no real `DataId` hashes here, the row
         * overwrites itself, it counts toward [isEmpty] like any other).
         */
        const val TOMBSTONE_CURSOR_HASH: Long = Long.MIN_VALUE + 1
        const val TOMBSTONE_CURSOR_TYPE: String = "tenshu/sync/tombstoneCursor"

        /**
         * Reserved hash for the **backfill floor** (C2), third in the sentinel run beside
         * [SYNC_CURSOR_HASH] and [TOMBSTONE_CURSOR_HASH].
         *
         * ⚠ These reserved rows are raw [DbEntry]s with their own type strings — **not registered
         * schema composites** — so `SchemaShapeBaseline` does not see them and adding one reshapes
         * nothing. A new sentinel therefore owes **no `TENSHU_STORAGE_FORMAT_VERSION` bump**: an
         * existing store simply has no such row until the first write, and the nullable read above
         * is what makes that state legible.
         */
        const val BACKFILL_CURSOR_HASH: Long = Long.MIN_VALUE + 2
        const val BACKFILL_CURSOR_TYPE: String = "tenshu/sync/backfillCursor"

        /** Reserved hash for the backfill **target** — fourth in the sentinel run. */
        const val BACKFILL_TARGET_HASH: Long = Long.MIN_VALUE + 3
        const val BACKFILL_TARGET_TYPE: String = "tenshu/sync/backfillTarget"

        /**
         * Reserved hash for the G3 sweep cursor — fifth in the sentinel run. Same "raw [DbEntry],
         * not a registered composite" reasoning as [BACKFILL_CURSOR_HASH], so it likewise owes no
         * `TENSHU_STORAGE_FORMAT_VERSION` bump.
         */
        const val SWEEP_CURSOR_HASH: Long = Long.MIN_VALUE + 4
        const val SWEEP_CURSOR_TYPE: String = "tenshu/sync/retiredBlockSweepCursor"

        /**
         * Reserved row for the orphaned-**file** sweep's cursor (G4a). A raw reserved row rather
         * than a schema type on purpose: it is device-local bookkeeping that must never sync, and
         * a schema addition would owe a version bump + migration under House Rule 9 for a value no
         * peer will ever read.
         */
        const val FILE_SWEEP_CURSOR_HASH: Long = Long.MIN_VALUE + 5
        const val FILE_SWEEP_CURSOR_TYPE: String = "tenshu/file/orphanSweepCursor"

        /**
         * Which **one-shot re-arm** of the G4a sweep this device has already spent
         * ([loadOrphanedFileSweepRepair]), or absent for none.
         *
         * ⚠⚠ A re-arm driven by [clearOrphanedFileSweepCursor] only fires on a *future* failed
         * cascade, which does nothing for rows stranded before the code shipped — and those exist:
         * 1 154 were measured under a deleted `…/Camera` on a store already reading
         * [SWEEP_COMPLETE]. They are not merely waste; they occupy their destination paths, so
         * Synced Folders re-uploads resolve to `Skipped` and the folder can never be rebuilt. This
         * row is what makes every existing device re-walk exactly **once** to collect them.
         *
         * ⚠ Versioned rather than boolean: a later repair bumps [FILE_SWEEP_REPAIR_VERSION] and spends a fresh one, instead of
         * needing a second row each time.
         */
        const val FILE_SWEEP_REPAIR_HASH: Long = Long.MIN_VALUE + 6
        const val FILE_SWEEP_REPAIR_TYPE: String = "tenshu/file/orphanSweepRepair"

        /**
         * Reserved hash for the **first-pull marker** ([loadFirstPullMarker]) — eighth in the
         * sentinel run. A raw [DbEntry] like the others, so it owes no version bump of any kind
         * (see [BACKFILL_CURSOR_HASH]).
         */
        const val FIRST_PULL_HASH: Long = Long.MIN_VALUE + 7
        const val FIRST_PULL_TYPE: String = "tenshu/sync/firstPull"

        /** A first forward pull was begun on this store and has not finished. */
        const val FIRST_PULL_STARTED: Long = 1L

        /** A forward pull finished with nothing more to come on any lane. */
        const val FIRST_PULL_COMPLETED: Long = 2L

        /**
         * The repair generation this build wants spent. Bump to re-arm every device once more.
         *
         * ⚠ Bumping re-walks the store on every device — bounded and resumable, but not free. It
         * is warranted when a *new* way of stranding rows is found and closed, not for routine
         * changes to the sweep.
         */
        const val FILE_SWEEP_REPAIR_VERSION: Long = 1L

        /**
         * Terminal value for [loadRetiredBlockSweepCursor]: the sweep reached the end of the hash
         * space and must never scan again.
         *
         * A sentinel rather than a second boolean row because the two facts are one fact — no
         * cursor can legitimately sit at `Long.MAX_VALUE`, since a row there would have to be
         * scanned *past* to reach it. ⚠ Distinct from `null` (never scanned) for the same reason
         * [loadBackfillCursor] keeps that distinction: collapsing them would either re-scan
         * forever or never scan at all.
         */
        const val SWEEP_COMPLETE: Long = Long.MAX_VALUE

        /**
         * Encoded-size boundary between a row a store keeps resident and one it serves from disk
         * per read (`bounded-store-cache.md` C-lite). Above it a row is a blob by construction —
         * thumbnails land at ~25–35 KiB — and holding blobs for the life of an account is the OOM
         * ceiling the proposal exists for; below it are entities, member-hash arrays and metadata,
         * ~99% of rows, whose residency is what makes hot reads cheap.
         *
         * ⚠ **No store retains on this boundary any more.** Since the 2026-08 lazy-hydration
         * change the JVM twin reads every row through a byte-bounded LRU, and the iOS file store
         * that kept it was retired on 2026-11-05 (the shared replica holds no rows in memory). The
         * "~99% of rows resident" figure above describes the C-lite design only.
         */
        const val LARGE_ROW_THRESHOLD_BYTES: Int = 8 * 1024
    }

    data class DbEntry(
        val type: String,
        val dataIdHash: Long,
        val fields: List<DbValue>,
        val properties: Map<String, DbValue> = emptyMap()
    )

    sealed class DbValue(
        open val value: Any
    ) {

        data class StringValue(
            override val value: String
        ) : DbValue(value)

        data class LongValue(
            override val value: Long
        ) : DbValue(value)

        data class ByteArrayValue(
            override val value: ByteArray
        ) : DbValue(value)

        data class BooleanValue(
            override val value: Boolean
        ) : DbValue(value)

        /**
         * A self-describing slot for a field whose runtime type isn't known
         * statically from the schema — a `genericData(...)` field (e.g.
         * `Setting.value`, which holds a `TBoolean` / `TString` / a composite /
         * ... depending on the preference). [typeId] is the concrete `Data` type
         * id; [payload] is the inline primitive `DbValue` for a value type, or a
         * [LongValue] reference for a composite/entity. Without this, a polymorphic
         * field can't be read back: a composite reference and an inline `TInteger`
         * are both `LongValue`, so the type can't be recovered. See [DbEntryMapper].
         */
        data class GenericValue(
            val typeId: String,
            val payload: DbValue,
        ) : DbValue(payload)

        object NullValue : DbValue(Unit)
    }
}

/**
 * ⚠⚠ The one definition of "this store has pulled its realm's whole history"
 * (`ios-shared-store-sqlite.md` B.22.6), shared by the scheduler and by everything that must not
 * act on a row's absence or trust the device's HLC watermark before it: the first forward pull
 * finished ([ObjectStore.FIRST_PULL_COMPLETED]; no marker with a forward cursor above zero is a
 * store upgraded from a build without the marker, and counts), **and** the backward walk is not
 * mid-way (its floor is never-walked `null` or complete `0`). ⚠ Unknown is not complete: any other
 * marker value answers false.
 */
fun pulledHistoryComplete(firstPullMarker: Long?, forwardCursorMoved: Boolean, backfillFloor: Long?): Boolean {
    val forwardDone = when (firstPullMarker) {
        ObjectStore.FIRST_PULL_COMPLETED -> true
        null -> forwardCursorMoved
        else -> false
    }
    return forwardDone && (backfillFloor == null || backfillFloor == 0L)
}
