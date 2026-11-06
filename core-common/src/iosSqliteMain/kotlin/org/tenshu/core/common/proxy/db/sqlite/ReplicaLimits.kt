package org.tenshu.core.common.proxy.db.sqlite

/**
 * Every constant of the shared store's WAL ceiling and transaction budget, in one place
 * (`docs/proposals/ios-shared-store-sqlite.md` §4.2.1, "Placeholders until Q0").
 *
 * The defaults are the design's placeholders. Q0's device batch (results doc §12.1) measured the
 * inputs of `Wmax`, `C`, `ROW_MAX` and `B_ckpt` warm on an iPhone 17e and found each one holding,
 * so none was changed; cold figures are still owed. A test passes its own instance to reach a
 * limit without writing megabytes.
 *
 * Sizes of the `-wal` are compared in **bytes of the file** (its physical size, §4.2.1 item 2):
 * a frame is a page plus a 24-byte header, after a 32-byte file header.
 */
data class ReplicaLimits(
    /** `Wmax`: no transaction may grow the `-wal` past this many pages' worth of bytes (S21). */
    val wmaxPages: Int = 3_000,
    /** `C`: `PRAGMA cache_size`, which bounds the dirty pages a transaction has not yet spilled. */
    val cachePages: Int = 256,
    /** `G`: what one single-row statement may dirty besides its overflow chains. */
    val statementPages: Int = 256,
    /** `ROW_MAX`: the largest encoded row any table takes. */
    val rowMaxBytes: Int = 512 * 1024,
    /** `MAX_DB_PAGES`: the used size past which a growing statement is refused (`StoreFull`). */
    val maxDbPages: Int = 524_288,
    /** `busy_timeout`, and so the length of one slice of every wait on a sibling (S7). */
    val busyTimeoutMs: Int = 50,
    /** `B_ckpt`: how long the ceiling checkpoint waits, in slices, for readers to end. */
    val ceilingWaitMs: Int = 1_250,
    /** `HOLD_MAX`: how long one transaction may hold the write lock up to its `COMMIT` (S8). */
    val holdMaxMs: Int = 1_000,
    /** A chunk loop's time stop: a chunk commits once it has run this long (§4.2.1 item 4). */
    val chunkTimeStopMs: Int = 500,
    /** A chunk loop's unit stop: the most rows one chunk of a wipe or a migration takes. */
    val chunkMaxRows: Int = 1_000,
    /**
     * `CONFIRM_MAX`: how long the second reading of a corruption code may run before it is
     * stopped (Appendix B.3 (45)). It holds a read mark and no write lock, so its ceiling is the
     * one §4.2.1's watchdog table gives a read-only statement, `HOLD_MAX`; at the defaults that
     * is under `B_ckpt`, so a sibling's ceiling checkpoint that waits for the reading outlasts it.
     * `quick_check` cost about 21 µs a page cold and 1.25 µs cached on the phone (Q0 results
     * §13.2): 1 s is near 47,000 pages cold and every page of `MAX_DB_PAGES` cached.
     */
    val confirmMaxMs: Int = 1_000,
    /**
     * `N`: the run of the job in which a migration step's failure on one and the same row turns
     * the migration into a wipe (Appendix B.3 (41), (53)). Each run counts at most one failure,
     * so the first `N − 1` runs stop with the store still "updating" and the `N`th wipes it.
     * At 1 the first failure wipes and no count is ever stored.
     * ⚠ The count is stored with the `N` of the build that wrote it, and each reader compares it
     * with its own, so two builds may differ here without either misreading the other.
     */
    val migrationRowAttempts: Int = 3,
    /*
     * ⚠⚠ The admission constants of §4.2.1's operations table (S0b-2 slice 3). **Every one is a
     * PLACEHOLDER awaiting a device measurement**, chosen as an upper bound the design states or
     * well above the simulator's figures (Q0 results §8.11, which are the Mac's and not values),
     * never as a measurement. Each names the measurement that settles it.
     */
    /** `R(Wmax + δ)`: an open's WAL-index rebuild. Placeholder: the design's own 200 ms p99 budget for `R + T` (§4.2.1), given to `R` whole. Settled by Q0 (s) on the phone. */
    val openRebuildMs: Int = 200,
    /** `T(Wmax + δ)`: one checkpoint, its sync included. Placeholder: the same 200 ms budget, given to `T` whole. Settled by Q0 (i) on the phone, cold. */
    val checkpointMs: Int = 200,
    /** `S`: the p99 of a `COMMIT`'s sync at `synchronous=FULL`, in protected background time. Placeholder 100 ms (the simulator's p99 is 78 µs). Settled by Q0 (z) on the phone, in a token. */
    val commitSyncMs: Int = 100,
    /** `S_full`: the p99 of an `F_FULLFSYNC` on a small file or a directory. Placeholder 500 ms (the simulator's worst was 0.33 s under load). Settled by Q0 (z)/(q) on the phone. */
    val fullSyncMs: Int = 500,
    /** The expiry margin every admission keeps: the design's 2 s (§4.2.1), not a measurement. */
    val expiryMarginMs: Int = 2_000,
    /**
     * `SEG`: the most members one segment of a relation array holds (Appendix B.19 (330)). 8,000
     * keeps a segment at 17 overflow pages (~72 KB), so every single-member change fits a
     * transaction begun at `Wmax − δ` and a 100,000-member list can still be removed or rewritten
     * whole in one transaction on a log just reset.
     */
    val listSegmentMembers: Int = 8_000,
    /**
     * `g`: what one single-row statement on `objects` can dirty besides its overflow chains, by
     * §4.2.1's depth table (`2 · (14 + 20)`). ⚠ Used only to **predict** a list write before its
     * first statement (B.19 (338)); every statement is still admitted by `G` ([statementPages]).
     */
    val objectsRowPages: Int = 68,
) {
    /** `Wmax` in bytes. */
    val wmaxBytes: Long get() = wmaxPages.toLong() * PAGE_BYTES

    /** `H` = `h(ROW_MAX, ROW_MAX)`: the largest headroom one statement can need, in pages. */
    val largestStatementPages: Int get() = headroomPages(rowMaxBytes, rowMaxBytes)

    /**
     * `U`: the most pages the statements of one migrated object can need, admitted together. A
     * **rewritten** `ROW_MAX` row is `H` beside three single-row statements (`meta.seq`,
     * `migration_done`, the cursor); a **dropped** one is `h(0, ROW_MAX)` beside four (those and
     * `migration_dropped`). Which is larger depends on `G` against the overflow chain, so both
     * are taken. ⚠ Whoever adds a statement to a migrated object adds it here.
     */
    val largestMigrationUnitPages: Int
        get() = maxOf(largestStatementPages + 3 * statementPages, headroomPages(0, rowMaxBytes) + 4 * statementPages)

    /** `δ` = `C + H` frames, in bytes: a transaction begins only at or below `Wmax − δ`. */
    val deltaBytes: Long get() = (cachePages + largestStatementPages).toLong() * WAL_FRAME_BYTES

    /** `δ` in whole pages, for the `used + δ ≤ MAX_DB_PAGES` rule. */
    val deltaPages: Int get() = ((deltaBytes + PAGE_BYTES - 1) / PAGE_BYTES).toInt()

    /**
     * `CKPT_MAX` = `2 · T(Wmax + δ) + B_ckpt` (§4.2.1's watchdog table): a checkpoint's own
     * deadline, over its copy, its `RESTART` waiting and its rewind write (Appendix B.10). ⚠
     * Derived from two placeholders (`T`, `B_ckpt`), so a placeholder too until Q0 (i) and (p).
     */
    val checkpointMaxMs: Int get() = 2 * checkpointMs + ceilingWaitMs

    /** `journal_size_limit` = `Wmax/4`: what a restarted log is truncated to. */
    val journalSizeLimitBytes: Long get() = wmaxBytes / 4

    /** The `-wal` size above which a transaction may not begin (writer rule 1). */
    val beginCeilingBytes: Long get() = wmaxBytes - deltaBytes

    /**
     * The room a transaction has on a log just reset (`journal_size_limit` = `Wmax/4`), in pages
     * of headroom as [fits] counts them: the most a list write may predict and ever commit.
     */
    val resetLogRoomPages: Int get() = ((wmaxBytes - journalSizeLimitBytes) / WAL_FRAME_BYTES - cachePages).toInt()

    /**
     * ⚠⚠ The prediction of one list write (Appendix B.19 (338)): every statement's `g + 2·(ov + ov_old)`
     * and, once, the `G − g` the last one's admission adds. If the `-wal` admits this before the
     * first statement, every statement of the write is admitted after it (each one grows the log
     * by at most its `g`-bound, and is admitted against `G`). [rows] are (new payload, old payload)
     * pairs, 0 for none.
     */
    fun listWritePages(rows: List<Pair<Int, Int>>): Int =
        rows.sumOf { (new, old) -> rowPages + 2 * (overflowPages(new) + overflowPages(old)) } + (statementPages - rowPages)

    /** `g`, never above `G`: a test's small `G` is also the bound of an `objects` row. */
    private val rowPages: Int get() = minOf(objectsRowPages, statementPages)

    init {
        require(listSegmentMembers >= 4) { "a list segment holds at least four members" }
        require(objectsRowPages >= 1) { "g is at least the page the row is on" }
        require(chunkMaxRows > 0 && chunkTimeStopMs in 1..holdMaxMs) { "a chunk must take a row and stop inside HOLD_MAX" }
        require(confirmMaxMs in 1..holdMaxMs) { "CONFIRM_MAX is a read mark's hold: at least 1 ms and at most HOLD_MAX" }
        require(migrationRowAttempts >= 1) {
            "N ($migrationRowAttempts) must be at least 1: at 0 a migration would be wiped before its step had failed on any row"
        }
        // B.3 (35): a chunk of a migration admits an object's statements together (a ROW_MAX
        // rewrite, `meta.seq`, `migration_done`, the cursor), which is more than the one statement
        // δ covers. When the first object of a chunk does not fit, the log is reset and the chunk
        // run again, and that only converges if a log just reset (journal_size_limit = Wmax/4)
        // leaves the room.
        val resetLogUnitBytes = journalSizeLimitBytes + (cachePages + largestMigrationUnitPages).toLong() * WAL_FRAME_BYTES
        require(resetLogUnitBytes <= wmaxBytes) {
            "Wmax/4 + C + U must be at most Wmax, U = max(H + 3G, h(0, ROW_MAX) + 4G) being a migration chunk's largest " +
                "object: Wmax/4 = $journalSizeLimitBytes bytes, C = $cachePages, H = $largestStatementPages, " +
                "h(0, ROW_MAX) = ${headroomPages(0, rowMaxBytes)}, G = $statementPages, U = $largestMigrationUnitPages pages give " +
                "$resetLogUnitBytes bytes, past Wmax ($wmaxBytes). A migration chunk could never admit that object, even on a log just reset"
        }
        require(wmaxBytes >= 2 * deltaBytes) {
            "Wmax ($wmaxBytes bytes) must be at least 2δ (${2 * deltaBytes}): a reset log must leave a transaction more than δ"
        }
    }

    /**
     * `ov(L)`: the overflow pages of a row whose payload is [payloadBytes] (0 at or under the
     * 4,061-byte local limit of a 4,096-byte page).
     */
    fun overflowPages(payloadBytes: Int): Int =
        if (payloadBytes <= LOCAL_PAYLOAD_MAX_BYTES) 0
        else (payloadBytes - LOCAL_PAYLOAD_MAX_BYTES + OVERFLOW_PAGE_PAYLOAD_BYTES - 1) / OVERFLOW_PAGE_PAYLOAD_BYTES

    /**
     * `h(L, L_old)` = `G + 2 · (ov(L) + ov(L_old))`: the pages one single-row statement may add
     * to the `-wal`, writing a row of [newPayloadBytes] over one of [oldPayloadBytes] (0 for none).
     */
    fun headroomPages(newPayloadBytes: Int, oldPayloadBytes: Int): Int =
        statementPages + 2 * (overflowPages(newPayloadBytes) + overflowPages(oldPayloadBytes))

    /**
     * The measured budget: may a statement needing [headroomPages] run with the `-wal` at
     * [walBytes]? `wal + C + h ≤ Wmax`.
     */
    fun fits(walBytes: Long, headroomPages: Int): Boolean =
        walBytes + (cachePages + headroomPages).toLong() * WAL_FRAME_BYTES <= wmaxBytes

    companion object {
        /** The store's page size. The budget's arithmetic depends on it, so the open checks it. */
        const val PAGE_BYTES: Int = 4096

        /** One WAL frame: a page and its 24-byte frame header. */
        const val WAL_FRAME_BYTES: Int = PAGE_BYTES + 24

        const val LOCAL_PAYLOAD_MAX_BYTES: Int = 4061
        const val OVERFLOW_PAGE_PAYLOAD_BYTES: Int = 4092
    }
}
