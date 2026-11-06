@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_ERROR
import platform.posix.arc4random
import platform.posix.usleep

/** How an open of the shared store ended, when it did not throw (§5.2). */
sealed interface ReplicaOpenResult {
    /** The store is open on a compatible layout. [created]: this open made it. */
    class Ready(val store: SharedReplicaStore, val created: Boolean) : ReplicaOpenResult

    /**
     * The store was written by a newer build. Nothing was written to it, no read/write connection
     * was opened on it, and it is never migrated or wiped from here: the update surface (§6, §7).
     */
    data class Skew(val detail: String) : ReplicaOpenResult

    /**
     * A wipe or a migration is in progress on the store, and this build may resume it (§5.2
     * step 3): one this open found marked, or one it has just started because the store was
     * behind this build (a migration's Start, or a below-floor wipe's first chunk). ⚠⚠ **No
     * store is handed out**: nothing serves a row until the job's finish. The host shows
     * "Updating storage…" or "Refreshing…", and opens again once the job answers
     * [ReplicaRunOutcome.Finished].
     *
     * [scheduled] (Appendix B.11): a worker is attached and [replacement] is already handed to it,
     * which runs it off the launch path under a job token per run and reports the outcome; the host
     * must not run it as well: a second `run()` of the object while the worker's run (or its
     * end-of-job checkpoint) goes on throws `IllegalStateException`. Not [scheduled], the host
     * runs [replacement] itself, off the launch path.
     */
    class Updating(val job: ReplicaJob, val replacement: ReplicaReplacement, val scheduled: Boolean = false) : ReplicaOpenResult

    /** `replica/CORRUPT` exists, found or just written. Durable; never rebuilt or repaired. */
    data class Corrupt(val detail: String) : ReplicaOpenResult
}

/**
 * The open of §5.2: step 0 on the file system, a read-only classification on a `CLASSIFY`
 * connection, and only for a fresh or compatible store a read/write connection and one write
 * transaction that re-reads the marker and creates or validates.
 *
 * ⚠⚠ What it never does (S19): on a store it has not judged fresh or compatible it opens no
 * read/write connection, issues no pragma that persists, runs no DDL and no checkpoint. A
 * read-only connection may create or rebuild `-shm`, and creates a zero-length `-wal` for a WAL
 * database that has none (Q0 results §11 (7)); `objects.db` and an existing `-wal` are untouched.
 *
 * The two halves are separate functions so a test can interleave two openers at the one point
 * where a sibling can change the answer.
 */
internal class ReplicaOpener(
    private val directory: String,
    private val build: ReplicaBuild,
    /** ⚠ No default: "this build has no migration chain" is an answer, and a wipe follows from it. */
    private val steps: ReplicaMigrationSteps,
    private val appName: String,
    private val foreground: Boolean,
    private val protectedDataAvailable: () -> Boolean,
    private val files: ReplicaFiles,
    private val registry: ReplicaHandles,
) {
    private val databasePath = "$directory/$DATABASE_NAME"
    private val marker = ReplicaCorruptMarker(files, directory)
    private val confirmation = ReplicaCorruptionConfirm(registry, files, marker, appName, build, protectedDataAvailable)

    /** Test seam: called between a damage verdict and the second read that must repeat it. Null in production. */
    internal var beforeConfirm: (() -> Unit)? = null

    /** Fault injection: called inside the open's write transaction, once it holds the write lock; a throw is a statement's failure. Null in production. */
    internal var writeTransactionFault: (() -> Unit)? = null

    /** Fault injection: called on the classification's connection inside its watched read; a throw is that read's failure. Null in production. */
    internal var classifyFault: ((SqliteConnection) -> Unit)? = null

    /** Test seam: called in the open's write transaction after its last statement and before its `COMMIT`, outside any statement. Null in production. */
    internal var beforeCommitFault: (() -> Unit)? = null

    /** True while the open's write transaction holds a gate admission ([admittedForMarker]). */
    private var admittedNow = false

    /** The handles this open registered and has neither released nor handed over. */
    private val handles = ArrayList<ReplicaHandle>()

    /**
     * ⚠ C6: the whole open, its file work before the first SQLite call included (the directory, the
     * empty `objects.db` and its directory sync, the marker's descriptor), runs inside one gate
     * admission taken here, waiting for protection if there is none: so an implicit token cannot
     * idle out mid-setup, and a quiesce waits for the open like any access.
     */
    fun open(): ReplicaOpenResult = registry.gate.admittedFor("open:$appName", ReplicaOperation.OPEN, registry.limits) {
        owning {
            when (val first = stepZero() ?: refusedWhileChecked() ?: readAndJudge()) {
                is Classified.Marked -> ReplicaOpenResult.Corrupt(first.detail)
                is Classified.Judged -> judged(first.classification)
                Classified.Absent -> error("unreachable: an open creates the file it does not find")
            }
        }
    }

    /**
     * ⚠⚠ **The opener owns everything it acquires until it hands a live store back**: the
     * directory descriptor [marker] keeps open once it was asked, and every handle it registered
     * (with it the handle's connection). [ReplicaOpenResult.Ready] is the one hand-over, to the
     * store, which gives them back when its handle is released. **Every other way out of
     * [block], each verdict and each throw, releases all of it here**, in the one `finally`
     * every entry point of this class runs under; no branch closes the marker for itself, so a
     * new early return or a new throw cannot leave a descriptor behind.
     *
     * A handle is also released earlier, by [release], where the open goes on after it (the
     * classification's connection is closed before the read/write one is opened); that only
     * takes it off the list this empties.
     */
    private inline fun <R> owning(block: () -> R): R {
        var handedOver = false
        try {
            return block().also { handedOver = it is ReplicaOpenResult.Ready }
        } finally {
            if (!handedOver) releaseEverything()
        }
    }

    private fun releaseEverything() {
        for (handle in handles.toList()) {
            try {
                release(handle)
            } catch (_: StoreUnavailable) {
                // Latched and logged by the registry, which finishes the release when it can.
            }
        }
        marker.close()
    }

    private fun register(kind: ReplicaHandleKind, label: String): ReplicaHandle =
        registry.register(kind, label).also { handles += it }

    /** Releases a handle of this open's. If the close fails it is the registry's from then on. */
    private fun release(handle: ReplicaHandle) {
        handles -= handle
        registry.release(handle)
    }

    internal sealed interface Classified {
        data class Marked(val detail: String) : Classified
        data class Judged(val classification: ReplicaClassification) : Classified

        /** No `objects.db` and no sidecar, for a caller that asked step 0 not to create one (a wipe). */
        data object Absent : Classified
    }

    /**
     * Steps 0 to 2. Every connection it opened is released before it returns.
     *
     * ⚠⚠ `replica/CORRUPT` is permanent, so nothing here writes it on evidence a sibling's
     * ordinary work could make momentarily true (Appendix B.4 of the design walks each path):
     * step 0's orphan rule orders its lookups, a corruption code from SQLite is believed only
     * when a second read repeats it (here, and for the read/write half in
     * [ReplicaCorruptionConfirm]), and a damage verdict is read again in [finish].
     */
    fun classifyReadOnly(): Classified = registry.gate.admittedFor("classify:$appName", ReplicaOperation.OPEN, registry.limits) { owning { stepZero() ?: readAndJudge() } }

    /**
     * Step 0, on the file system: the marker, and a database to open. Null when there is one.
     *
     * ⚠ **The one step 0, for an open and for the store-less wipe alike** ([create] false: a wipe
     * makes no file, and is told [Classified.Absent] instead). A second copy of these lookups in
     * the wipe answered "nothing there" for a sidecar without its database, which the next open
     * of the same directory calls corruption.
     */
    private fun stepZero(create: Boolean = true): Classified? {
        try {
            files.ensureDirectory(directory)
            // Through the marker, not a bare lookup: a marker found is made durable by this
            // process before it is reported (ReplicaCorruptMarker).
            if (markerFound()) return Classified.Marked("replica/CORRUPT exists")
            if (!files.exists(databasePath)) {
                // ⚠⚠ The order of these lookups is the evidence. A sibling's first launch makes
                // objects.db and then, through SQLite, its -wal and -shm; a sidecar seen after a
                // stale "no objects.db" is that healthy store, not an orphan. So the database is
                // looked for again AFTER a sidecar was seen: a sidecar can only come into being
                // beside an objects.db, and nothing removes an objects.db (S1), so "sidecar
                // there, then database not there" is a state no creator passes through.
                val sidecar = files.exists("$databasePath-wal") || files.exists("$databasePath-shm")
                if (sidecar) {
                    if (!files.exists(databasePath)) {
                        return Classified.Marked(corrupt("a -wal or -shm without objects.db"))
                    }
                } else if (!create) {
                    return Classified.Absent
                } else {
                    // The one act on objects.db that is not SQLite's (S1): O_EXCL never truncates
                    // a file a sibling made first. A create lost to power loss is repeated next open.
                    if (files.createExclusive(databasePath)) syncDirectory(files, directory)
                }
            }
        } catch (e: ReplicaFileException) {
            throw unavailable(e)
        }
        return null
    }

    /**
     * ⚠ **Q5's fence across runs and wakes** (Appendix B.8): while this app's confirmation walk is
     * owed (requested in this process, or a position on disk from an earlier one), the open hands
     * out no store and answers [StoreBeingChecked]; the walk is scheduled again. After step 0, so
     * a marker is still `Corrupt`. Always null, or throws.
     */
    private fun refusedWhileChecked(): Classified? {
        val walk = registry.confirmationWalk(appName, files, build, protectedDataAvailable)
        val pending = try {
            walk.isPending()
        } catch (e: ReplicaFileException) {
            throw unavailable(e)
        }
        if (!pending) return null
        // Rate-limited (B.8.3 (115)): a host retrying its open in a loop drives no run per retry.
        walk.scheduleFromAnOpen()
        if (walk.isStalled) {
            throw StoreBeingChecked("[replica] the store could not be checked after a corruption code: $appName's confirmation walk cannot advance (it is tried again at the next launch)", stalled = true)
        }
        throw StoreBeingChecked("[replica] the store is being checked after a corruption code ($appName's confirmation walk is owed)")
    }

    /**
     * Steps 1 and 2: one read-only connection, one read transaction, the judgement. A corruption
     * code from SQLite is acted on only when a second, separate read repeats it.
     */
    private fun readAndJudge(): Classified {
        var corruption: String? = null
        readOnce { corruption = it }?.let { return it }
        usleep(REVERIFY_PAUSE_MICROS)
        readOnce { corruption = it }?.let { return it }
        return Classified.Marked(corrupt(corruption.orEmpty()))
    }

    /** One read-only classification. Null, with [onCorrupt] told why, when SQLite called the file corrupt. */
    private fun readOnce(onCorrupt: (String) -> Unit): Classified? {
        registry.lock.lock()
        try {
            val reading = try {
                // No connection of the classification outlives its read ([readOnly]). ⚠ Under the
                // hold watchdog at HOLD_MAX (Q15, Appendix B.10): the read holds a read mark, which
                // a sibling's ceiling checkpoint waits on.
                registry.readOnly("classify:$appName", watched = true) { connection ->
                    classifyFault?.invoke(connection)
                    readMarker(connection)
                }
            } catch (e: HoldCeilingExceeded) {
                // §4.2.1's table: "classification retried (StoreUnavailable)". Nothing was judged
                // and nothing is written: the caller opens again.
                refusedAtHoldMax("classification", e)
                throw StoreUnavailable("[replica] the read-only classification held its read mark past HOLD_MAX; try again: ${e.message}", e)
            } catch (e: SqliteException) {
                when (ReplicaVerdicts.of(e.primaryCode, ReplicaPhase.OPEN_CLASSIFY, protectedDataAvailable = protectedDataAvailable())) {
                    ReplicaVerdict.CORRUPT -> {
                        onCorrupt(e.message.orEmpty())
                        return null
                    }
                    ReplicaVerdict.RETRY_UNCLASSIFIABLE ->
                        throw StoreUnclassifiable("[replica] the read-only open could not classify the store: ${e.message}", e)
                    ReplicaVerdict.RETRY_PROTECTED_DATA ->
                        throw ProtectedDataUnavailable("[replica] protected data is unavailable", e)
                    else -> throw StoreUnavailable("[replica] the store could not be classified: ${e.message}", e)
                }
            } catch (e: SqliteUnfixedLibraryException) {
                throw StoreUnavailable("[replica] ${e.message}", e)
            }
            // A sibling may have written the marker since step 0.
            val marked = try {
                markerFound()
            } catch (e: ReplicaFileException) {
                throw unavailable(e)
            }
            if (marked) return Classified.Marked("replica/CORRUPT exists")
            return Classified.Judged(classify(reading, build))
        } finally {
            registry.lock.unlock()
        }
    }

    /** Steps 2a and 3, for a classification the read-only half produced. */
    fun finish(classification: ReplicaClassification): ReplicaOpenResult = registry.gate.admittedFor("finish:$appName", ReplicaOperation.OPEN, registry.limits) { owning { judged(classification) } }

    private fun judged(classification: ReplicaClassification): ReplicaOpenResult = when (classification) {
        is ReplicaClassification.Damaged -> confirmDamage()
        is ReplicaClassification.Skew -> ReplicaOpenResult.Skew(classification.detail)
        // Retryable, and decided on the read-only connection: nothing is opened read/write on it.
        is ReplicaClassification.OlderLayout -> throw StoreUnavailable("[replica] ${classification.detail}")
        is ReplicaClassification.Updating -> {
            // A wipe may be resumed by a wake under its token; a migration only in the foreground.
            if (classification.job == ReplicaJob.MIGRATION && !foreground) {
                throw StoreNeedsForeground("[replica] a migration is in progress on the store")
            }
            admitJob()
        }
        is ReplicaClassification.Behind -> {
            if (!foreground) throw StoreNeedsForeground("[replica] the store needs a replacement: ${classification.detail}")
            admitJob()
        }
        ReplicaClassification.Fresh, ReplicaClassification.Compatible -> openReadWrite()
    }

    /**
     * A damage verdict came from one read, some time ago, with no lock held since; the marker it
     * leads to is permanent. So the store is read once more, and only a verdict that is damage
     * again is written. Anything else the second read finds is acted on as that.
     */
    private fun confirmDamage(): ReplicaOpenResult = when (val again = damageReadAgain()) {
        is Classified.Marked -> ReplicaOpenResult.Corrupt(again.detail)
        is Classified.Judged -> judged(again.classification)
        Classified.Absent -> error("unreachable: only step 0 answers Absent")
    }

    /**
     * The second read of a damage verdict. [Classified.Marked] when it is damage again, with the
     * marker written; otherwise what the store now is, **judged and nothing more**: what follows
     * from that reading is the caller's to do. (An open goes on to open; a wipe must not, or it
     * would be handed a store it has no use for and nothing would ever release its handle.)
     */
    private fun damageReadAgain(): Classified {
        usleep(REVERIFY_PAUSE_MICROS)
        beforeConfirm?.invoke()
        return when (val again = readAndJudge()) {
            is Classified.Judged -> when (val second = again.classification) {
                is ReplicaClassification.Damaged -> Classified.Marked(corrupt(second.detail))
                else -> again
            }
            else -> again
        }
    }

    private fun newReplacement() = ReplicaReplacement(registry, files, directory, build, steps, appName, protectedDataAvailable)

    /**
     * §5.2 step 3 for a store the read-only half found marked or behind: one write transaction
     * ([ReplicaReplacement.admit]) that validates the schema, judges the store again under the
     * write lock, and starts the job or applies the resumer rule. No runtime handle is made.
     */
    private fun admitJob(attempt: Int = 0): ReplicaOpenResult {
        val replacement = newReplacement()
        return when (val admitted = replacement.admit(foreground)) {
            is ChunkResult.More -> {
                // ⚠ The hand-off only dispatches (the worker's queue): this open still holds its
                // admission, and the run takes its own token and admissions on another thread.
                val worker = registry.onReplacementRequested
                worker?.invoke(replacement)
                ReplicaOpenResult.Updating(admitted.job, replacement, scheduled = worker != null)
            }
            is ChunkResult.Ended -> when (val outcome = admitted.outcome) {
                // A sibling finished the job since the read-only half: judged again, from the top.
                ReplicaRunOutcome.Finished -> {
                    if (attempt >= REJUDGE_ATTEMPTS) throw StoreUnavailable("[replica] the store kept changing under the open")
                    when (val again = readAndJudge()) {
                        is Classified.Marked -> ReplicaOpenResult.Corrupt(again.detail)
                        Classified.Absent -> error("unreachable: only step 0 answers Absent")
                        is Classified.Judged -> when (val second = again.classification) {
                            is ReplicaClassification.Updating, is ReplicaClassification.Behind -> admitJob(attempt + 1)
                            else -> judged(second)
                        }
                    }
                }
                is ReplicaRunOutcome.Skew -> ReplicaOpenResult.Skew(outcome.detail)
                is ReplicaRunOutcome.Corrupt -> ReplicaOpenResult.Corrupt(outcome.detail)
                ReplicaRunOutcome.NeedsForeground -> throw StoreNeedsForeground("[replica] the store needs a migration")
                is ReplicaRunOutcome.Stopped ->
                    if (protectedDataAvailable()) throw StoreUnavailable("[replica] ${outcome.reason}", outcome.cause)
                    else throw ProtectedDataUnavailable("[replica] protected data is unavailable", outcome.cause)
            }
        }
    }

    /**
     * The store-less wipe (§5.1). Step 0 and the read-only classification first, as for any open:
     * a marked or a newer store is refused with nothing written and no read/write connection. No
     * file is created: with no `objects.db`, or one never made into a store, there is nothing to
     * wipe (false).
     */
    fun wipe(reason: String): Boolean = when (startWipe(reason, handOff = false) {}) {
        SharedReplicaWipeStart.NOTHING_TO_WIPE -> false
        SharedReplicaWipeStart.FINISHED -> true
        SharedReplicaWipeStart.HANDED_OFF -> error("unreachable: an inline wipe is not handed off")
    }

    /**
     * The same wipe, but with [handOff] its chunks after the first are **handed to the worker**
     * (Appendix B.13 (178)) instead of run on the caller: the answer is then [SharedReplicaWipeStart.HANDED_OFF]
     * and the worker tells the outcome. ⚠ With no worker attached a hand-off is refused.
     */
    fun startWipe(reason: String, handOff: Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart =
        registry.gate.admittedFor("wipe:$appName", ReplicaOperation.OPEN, registry.limits) { wipeAdmitted(reason, handOff, beforeHandOff) }

    private fun wipeAdmitted(reason: String, handOff: Boolean, beforeHandOff: (jobId: Long) -> Unit): SharedReplicaWipeStart = owning {
        val worker = registry.onReplacementRequested
        if (handOff && worker == null) throw IllegalStateException("[replica] a wipe handed off needs a worker attached")
        val classification = when (val read = stepZero(create = false) ?: readAndJudge()) {
            is Classified.Marked -> throw WipeRefused(ReplicaRunOutcome.Corrupt(read.detail))
            Classified.Absent -> return@owning SharedReplicaWipeStart.NOTHING_TO_WIPE
            is Classified.Judged -> read.classification
        }
        when (classification) {
            ReplicaClassification.Fresh -> return@owning SharedReplicaWipeStart.NOTHING_TO_WIPE
            is ReplicaClassification.Skew -> throw WipeRefused(ReplicaRunOutcome.Skew(classification.detail))
            is ReplicaClassification.OlderLayout -> throw StoreUnavailable("[replica] ${classification.detail}")
            is ReplicaClassification.Damaged -> when (val confirmed = damageReadAgain()) {
                is Classified.Marked -> throw WipeRefused(ReplicaRunOutcome.Corrupt(confirmed.detail))
                // Not damage after all. Nothing is opened on that reading here: the caller retries.
                else -> throw StoreUnavailable("[replica] the store changed under the wipe; try again")
            }
            else -> Unit
        }
        val replacement = newReplacement()
        when (val started = replacement.startWipe(reason)) {
            is ChunkResult.More -> Unit
            is ChunkResult.Ended -> when (val outcome = started.outcome) {
                ReplicaRunOutcome.Finished -> return@owning SharedReplicaWipeStart.NOTHING_TO_WIPE
                is ReplicaRunOutcome.Skew, is ReplicaRunOutcome.Corrupt -> throw WipeRefused(outcome)
                // The first chunk did not commit: nothing changed, an ordinary failed wipe.
                else -> throw StoreUnavailable("[replica] the wipe could not start: $outcome")
            }
        }
        if (handOff && worker != null) {
            // ⚠ Only dispatches (the worker's queue), as the open's hand-off does: the caller
            // (a launch's custody purge, on main) returns now and the outcome comes later.
            // ⚠ The caller learns the job's id BEFORE the dispatch (B.13 (183)): a worker that
            // finishes before this returns must still find what the caller waits for.
            beforeHandOff(replacement.jobId)
            worker(replacement)
            return@owning SharedReplicaWipeStart.HANDED_OFF
        }
        when (val outcome = replacement.run()) {
            ReplicaRunOutcome.Finished -> SharedReplicaWipeStart.FINISHED
            else -> throw WipeNotFinished(outcome)
        }
    }

    private fun openReadWrite(): ReplicaOpenResult {
        val handle = register(ReplicaHandleKind.RUNTIME, appName)
        // Run on every connection this handle ever opens, the reopen after a quiesce included
        // (§2.3.1): journal_mode is persistent but is set and read back every time.
        //
        // ⚠⚠ No `PRAGMA auto_vacuum` is issued, here or anywhere. It has to precede the first
        // table, so it could only run before the write lock is held, on a file this open had
        // judged fresh a moment earlier; and against a store that exists it is a write
        // transaction (Q0 results §11 (5)). A sibling creating in between would have had a frame
        // appended to its store, by an older build too (S12, S19). The vendored SQLite is built
        // with `SQLITE_DEFAULT_AUTOVACUUM=2` instead (scripts/build-sqlite-apple.sh), so whichever
        // connection first writes page 1 makes an incremental-vacuum database, and the freshness
        // check and the create are both under `BEGIN IMMEDIATE` below. Read back here, like WAL.
        handle.onOpened = ::requireStoreSettings

        var admitted = false
        var hold: ReplicaHold? = null
        registry.lock.lock()
        try {
            val connection = registry.acquire(handle, "open") { true }
                ?: throw StoreUnavailable("[replica] open: the handle was released before its write transaction began")
            admitted = true
            admittedNow = true
            // ⚠⚠ HOLD_MAX starts HERE, with the write lock just obtained (Q15, Appendix B.10), as
            // an access's does: every statement below is bracketed by the watch, the marker
            // lookup and the directory sync count against it, and it is disarmed before any
            // ROLLBACK and as the COMMIT is issued. Firing, it is rule (a) for a store that is
            // not handed out yet: rolled back, the handle released, a retryable StoreUnavailable.
            val watch = registry.holdWatchdog.arm("open:$appName", connection, registry.limits.holdMaxMs)
            hold = watch
            watch.statement { writeTransactionFault?.invoke() }
            // ⚠ File work under the lock is exempt from HOLD_MAX (B.10 (140)): not a statement.
            if (watch.exempt { markerFound() }) {
                watch.disarm()
                connection.exec("ROLLBACK")
                return ReplicaOpenResult.Corrupt("replica/CORRUPT exists")
            }
            // A sibling may have created, migrated or wiped the store since the read-only half.
            val again = classify(watch.statement { readMarker(connection) }, build)
            val created = when (again) {
                ReplicaClassification.Fresh -> {
                    watch.statement { create(connection) }
                    // The empty objects.db may be a sibling's, made and never followed by its
                    // directory sync (it died, or has not got there). Whoever creates the store
                    // in it makes the name durable before the store exists for anyone.
                    // An F_FULLFSYNC: exempt from HOLD_MAX (B.10 (140)).
                    watch.exempt { syncDirectory(files, directory) }
                    true
                }
                ReplicaClassification.Compatible -> {
                    // ⚠ The marker is written with the write lock still held, like every damage
                    // verdict of this transaction (the catch blocks below, B.3 (27)).
                    watch.statement { ReplicaSchema.validate(connection) }?.let { damage ->
                        return ReplicaOpenResult.Corrupt(corrupt("schema validation: $damage"))
                    }
                    false
                }
                else -> {
                    watch.disarm()
                    connection.exec("ROLLBACK")
                    // Closed before the gate is left, like every other way out.
                    release(handle)
                    registry.gate.leave()
                    admitted = false
                    admittedNow = false
                    return judged(again)
                }
            }
            val epoch = watch.statement { ReplicaSchema.metaInteger(connection, ReplicaSchema.META_EPOCH) }
            val seq = watch.statement { ReplicaSchema.metaInteger(connection, ReplicaSchema.META_SEQ) }
            beforeCommitFault?.invoke()
            // Refused if the deadline has passed; otherwise disarmed in the same step, so the
            // COMMIT and its sync are never interrupted (R2).
            watch.beforeCommit()
            connection.exec("COMMIT")
            val store = SharedReplicaStore(registry, handle, files, marker, appName, build, protectedDataAvailable, epoch, seq, ::newReplacement)
            // The hand-over: the handle is the store's from here, and with `Ready` the marker too.
            handles -= handle
            registry.openServed()
            return ReplicaOpenResult.Ready(store, created)
        } catch (e: HoldCeilingExceeded) {
            // Rolled back and released by the finally below, the watch disarmed first.
            refusedAtHoldMax("write transaction", e)
            throw StoreUnavailable("[replica] the open's write transaction held the write lock past HOLD_MAX; try again: ${e.message}", e)
        } catch (e: SqliteException) {
            when (ReplicaVerdicts.of(e.primaryCode, ReplicaPhase.OPEN_READ_WRITE, protectedDataAvailable = protectedDataAvailable())) {
                // One answer does not write the permanent marker (B.3 (45)). This handle is done
                // either way: its transaction is rolled back and its connection closed by the
                // confirmation, before the second reading and inside this open's admission.
                // ⚠ The watch is disarmed before the confirmation closes the connection.
                ReplicaVerdict.CORRUPT -> return when (
                    val outcome = run {
                        hold?.disarm()
                        confirmation.run(e.message.orEmpty(), failing = handle, alreadyAdmitted = admittedNow)
                    }
                ) {
                    is ReplicaCorruptionConfirmation.Confirmed -> ReplicaOpenResult.Corrupt(outcome.detail)
                    is ReplicaCorruptionConfirmation.Unconfirmed ->
                        if (outcome.handedOff) {
                            throw StoreBeingChecked(
                                "[replica] the store could not be opened: SQLite answered a corruption code, ${outcome.reason}: ${e.message}",
                                e,
                            )
                        } else if (protectedDataAvailable()) {
                            throw StoreCorruptionUnconfirmed(
                                "[replica] the store could not be opened: SQLite answered a corruption code that was not " +
                                    "confirmed (${outcome.reason}): ${e.message}",
                                e,
                            )
                        } else {
                            throw ProtectedDataUnavailable("[replica] protected data is unavailable", e)
                        }
                }
                ReplicaVerdict.RETRY_PROTECTED_DATA -> throw ProtectedDataUnavailable("[replica] protected data is unavailable", e)
                else -> throw StoreUnavailable("[replica] the store could not be opened: ${e.message}", e)
            }
        } catch (e: SqliteUnfixedLibraryException) {
            throw StoreUnavailable("[replica] ${e.message}", e)
        } catch (e: ReplicaDamaged) {
            return ReplicaOpenResult.Corrupt(corrupt(e.detail))
        } catch (e: ReplicaFileException) {
            // The marker could not be looked for: a retry, never "no marker".
            throw unavailable(e)
        } finally {
            // ⚠ Disarmed before the release's ROLLBACK (B.9 (128)); a no-op once the COMMIT was issued.
            hold?.disarm()
            // An open that ends fenced or failed releases its handle while it still holds the
            // registry's lock (§2.8), which also rolls back whatever transaction is still open,
            // and ⚠ before it leaves the gate: the rollback and the close touch the database.
            if (handle in handles) {
                try {
                    release(handle)
                } catch (_: StoreUnavailable) {
                    // Latched and logged by the registry.
                }
            }
            admittedNow = false
            if (admitted) registry.gate.leave()
            registry.lock.unlock()
        }
    }

    /** The fresh-store transaction (§5.2 step 3): the only place a `CREATE` runs. */
    private fun create(connection: SqliteConnection) {
        connection.exec("PRAGMA application_id = ${ReplicaSchema.APPLICATION_ID}")
        connection.exec("PRAGMA user_version = ${build.coreVersion}")
        ReplicaSchema.createStatements.forEach(connection::exec)
        val meta = connection.prepare("INSERT INTO meta (k, v) VALUES (?, ?)")
        try {
            for ((key, value) in listOf(
                ReplicaSchema.META_EPOCH to newEpoch(),
                ReplicaSchema.META_SEQ to 0L,
                ReplicaSchema.META_LAYOUT to build.layout.toLong(),
            )) {
                meta.bindText(1, key)
                meta.bindLong(2, value)
                meta.step()
                meta.reset()
            }
        } finally {
            meta.close()
        }
        val stamp = connection.prepare("INSERT INTO ${ReplicaSchema.SERVICE_VERSIONS} (service_id, version) VALUES (?, ?)")
        try {
            for ((service, version) in build.services) {
                stamp.bindText(1, service)
                stamp.bindLong(2, version.toLong())
                stamp.step()
                stamp.reset()
            }
        } finally {
            stamp.close()
        }
    }

    /**
     * The marker, for an open: true only for a marker this process has made durable. One that is
     * there but could not be made durable refuses the open as a retry ([ReplicaMarkerState]):
     * `Corrupt` would claim a fence a crash can still undo. Thrown as [ReplicaFileException] so
     * each caller's existing mapping makes it [StoreUnavailable] (or protected-data unavailable).
     */
    private fun markerFound(): Boolean = when (marker.state()) {
        ReplicaMarkerState.ABSENT -> false
        ReplicaMarkerState.DURABLE -> true
        ReplicaMarkerState.NOT_DURABLE ->
            throw ReplicaFileException("replica/CORRUPT is there but its directory could not be synced")
    }

    /**
     * Writes `replica/CORRUPT` and returns [detail]; a marker that cannot be written is a retry.
     * For a damage verdict this open read for itself (step 0's orphan, the frozen marker read
     * twice, schema validation under the write lock). A corruption **code** from SQLite never
     * comes here: [ReplicaCorruptionConfirm].
     */
    private fun corrupt(detail: String): String {
        try {
            registry.admittedForMarker(admittedNow) { marker.write(corruptMarkerDetail(files, appName, detail)) }
        } catch (e: ReplicaFileException) {
            throw unavailable(e)
        }
        println("[replica] the store is CORRUPT: $detail")
        return detail
    }

    /**
     * Counted and logged (B.10 (140)): an open refused at `HOLD_MAX` every time is otherwise a
     * silent retry loop. From [OPEN_HOLD_REFUSALS_REPORTED] in a row the log says so.
     */
    private fun refusedAtHoldMax(what: String, e: HoldCeilingExceeded) {
        // B.15: the token-expiry interrupt says nothing about how fast the store opens: not counted.
        if (isProtectionExpiry(e)) {
            println("[replica] the open's $what was stopped by the token-expiry interrupt: ${e.message}")
            return
        }
        val inARow = registry.openRefusedAtHoldMax()
        if (inARow >= OPEN_HOLD_REFUSALS_REPORTED) {
            println("[replica] ⚠ $appName's open has been refused at HOLD_MAX $inARow times in a row (its $what): the store may be too slow to open in 1 s on this device: ${e.message}")
        } else {
            println("[replica] the open's $what was refused at HOLD_MAX ($inARow in a row): ${e.message}")
        }
    }

    private fun unavailable(e: ReplicaFileException): StoreUnavailable =
        if (protectedDataAvailable()) StoreUnavailable("[replica] ${e.message}", e)
        else ProtectedDataUnavailable("[replica] protected data is unavailable", e)

    companion object {
        const val DATABASE_NAME = "objects.db"
        private const val REJUDGE_ATTEMPTS = 3

        /** ⚠ Placeholder (B.10 (140)): opens refused at `HOLD_MAX` in a row before the log calls it out. */
        const val OPEN_HOLD_REFUSALS_REPORTED = 3

        /** Between a corruption or damage finding and the second read that must repeat it. */
        private const val REVERIFY_PAUSE_MICROS = 20_000u

        /** A random 64-bit `meta.epoch`. */
        fun newEpoch(): Long = (arc4random().toLong() shl 32) or arc4random().toLong()
    }
}

/**
 * Run on every read/write connection to the store as the registry opens it, the reopen after a
 * quiesce included (§2.3.1): `journal_mode` is persistent but is set and read back every time,
 * and `auto_vacuum` (compiled in, B.3 (8)) and the page size the budget's arithmetic depends on
 * are read back beside it. ⚠ Only ever on a store the read-only classification did not judge
 * newer (S19).
 */
internal fun requireStoreSettings(connection: SqliteConnection) {
    val mode = connection.queryText("PRAGMA journal_mode=WAL")
    if (!mode.equals("wal", ignoreCase = true)) throw StoreUnusable("[replica] journalMode=$mode")
    val vacuum = connection.queryLong("PRAGMA auto_vacuum")
    if (vacuum != 2L) throw StoreUnusable("[replica] autoVacuum=$vacuum")
    val pageBytes = connection.queryLong("PRAGMA page_size")
    if (pageBytes != ReplicaLimits.PAGE_BYTES.toLong()) throw StoreUnusable("[replica] pageSize=$pageBytes")
}

/**
 * Reads the frozen bootstrap marker (§5.2 step 2): `application_id`, `user_version`, the schema
 * object count, and through `meta` and `schema_service_versions`, only if each has its frozen
 * shape, the layout, the two job markers and the service stamps. The caller holds a transaction.
 */
internal fun readMarker(connection: SqliteConnection): ReplicaReading {
    val applicationId = connection.queryLong("PRAGMA application_id").toInt()
    val userVersion = connection.queryLong("PRAGMA user_version").toInt()
    var schemaObjects = 0
    var metaTable = false
    var versionsTable = false
    connection.query("SELECT type, name FROM sqlite_schema") { row ->
        schemaObjects++
        if (row.getText(0) == "table") {
            when (row.getText(1)) {
                ReplicaSchema.META -> metaTable = true
                ReplicaSchema.SERVICE_VERSIONS -> versionsTable = true
            }
        }
    }
    // The frozen shape exactly, not the column names alone: a `meta` or a versions table declared
    // otherwise is not one whose rows mean what this reads them as.
    val hasMeta = metaTable && ReplicaSchema.hasExpectedShape(connection, ReplicaSchema.META)
    var layout: Int? = null
    var wipe: ByteArray? = null
    var migration: ByteArray? = null
    var metaDamage: String? = null
    if (hasMeta) {
        metaDamage = ReplicaSchema.metaDamage(connection)
        connection.query("SELECT k, v, typeof(v) FROM ${ReplicaSchema.META}") { row ->
            when (row.getText(0)) {
                // In range before it is narrowed: 2^32 + 1 must not read as layout 1.
                ReplicaSchema.META_LAYOUT -> if (row.getText(2) == "integer") {
                    layout = row.getLong(1).takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                }
                // The two optional rows: a job is in progress iff the row exists, whatever it
                // holds. ⚠ Its bytes are handed on only when it is a blob: a text or an integer
                // read through `getBlob` would come back as bytes that look like a frame.
                ReplicaSchema.META_WIPE -> wipe = if (row.getText(2) == "blob") row.getBlob(1) else ByteArray(0)
                ReplicaSchema.META_MIGRATION -> migration = if (row.getText(2) == "blob") row.getBlob(1) else ByteArray(0)
            }
        }
    }
    var versions: MutableMap<String, Int>? = null
    if (versionsTable && ReplicaSchema.hasExpectedShape(connection, ReplicaSchema.SERVICE_VERSIONS)) {
        val found = LinkedHashMap<String, Int>()
        // A stamp that is not an integer would read as version 0, "below every floor", and send
        // the store to a wipe: a malformed row makes the whole table unreadable instead.
        var malformed = false
        connection.query(
            "SELECT service_id, version, typeof(service_id), typeof(version) FROM ${ReplicaSchema.SERVICE_VERSIONS}",
        ) { row ->
            val version = row.getLong(1)
            // A stamp is a version: not negative, and within 32 bits before it is narrowed
            // (2^32 + 3 must not read as version 3).
            if (row.getText(2) != "text" || row.getText(3) != "integer" || version !in 0..Int.MAX_VALUE) malformed = true
            else found[row.getText(0)] = version.toInt()
        }
        versions = found.takeUnless { malformed }
    }
    return ReplicaReading(
        applicationId = applicationId,
        userVersion = userVersion,
        schemaObjects = schemaObjects,
        hasMeta = hasMeta,
        layout = layout,
        serviceVersions = versions,
        wipeMarker = wipe,
        migrationMarker = migration,
        metaDamage = metaDamage,
    )
}

/**
 * The first column of the first row, as an integer. ⚠ For a pragma or a `count(*)` only, whose
 * result is an integer by construction: `sqlite3_column_int64` coerces anything else, so a value
 * read from a row goes through a typed read ([ReplicaSchema.metaInteger]) instead.
 */
internal fun SqliteConnection.queryLong(sql: String): Long {
    var value = 0L
    var found = false
    query(sql) { row ->
        if (!found) {
            value = row.getLong(0)
            found = true
        }
    }
    if (!found) throw SqliteException(SQLITE_ERROR, "no row for: $sql")
    return value
}

internal fun SqliteConnection.queryText(sql: String): String {
    var value = ""
    query(sql) { row -> value = row.getText(0) }
    return value
}
