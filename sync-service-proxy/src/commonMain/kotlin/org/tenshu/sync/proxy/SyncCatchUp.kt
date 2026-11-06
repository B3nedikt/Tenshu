package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncCatchUpHeartbeat
import org.tenshu.sync.SyncCatchUpStatus
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.CatchUpHeartbeatTiming
import org.tenshu.sync.common.wallClockMillis

/**
 * Whether this runtime is **pulling data in**, and the one writer of its [SyncCatchUpStatus] row
 * (`ios-sync-activity-indicator.md` §3.1).
 *
 * The [SyncScheduler] raises it on evidence and settles it at every tick end. Nothing here is a
 * timer.
 *
 * ## A raise lands before the work it announces
 *
 * The other status rows are published by collecting a conflating `StateFlow`. That cannot work
 * here. A one-page pull raises, applies and settles inside one tick, so a collector may only ever
 * see the final `false`, and the row never reads `true` however long the apply took. So [raise]
 * **suspends until the row is written**, and the scheduler calls it before the apply loop.
 *
 * ## One writer, and it writes the current value
 *
 * [raise] writes directly; a settle is written by the collector [publishIn] starts. Both go
 * through [flush], which holds [writeLock] and writes what [state] says **now**. A collector that
 * wakes late with an old `false` therefore cannot land it on top of a newer `true`. It writes
 * only when the store disagrees, so a ten-page catch-up costs two writes.
 *
 * ## Stopping
 *
 * `SyncScheduler.stop()` only cancels; a tick may still be running and would settle after it.
 * [stop] sets a latch inside the same atomic value as the flag, so once it is set no [raise] or
 * [settle] is accepted and the clear is the last thing this runtime says. [resume] reopens it.
 *
 * ## The heartbeat (`ios-sync-activity-indicator.md` §12)
 *
 * Beside its own row, the writer keeps a [SyncCatchUpHeartbeat] row: while [catchingUp], it is
 * rewritten every [CatchUpHeartbeatTiming.intervalMs] with the wall-clock time, so a reader in a
 * **sibling** process can count it and stop counting it once the beats stop — a killed app's
 * `true` expires by itself. The beat is the one timer here, and it runs only while catching up:
 * it is started by the flag going up and cancelled by it going down or by [stop]. A settle writes
 * the heartbeat row `false` at once, so a sibling clears promptly on a normal finish.
 *
 * ⚠ Deliberately **not** a field of [SyncHealthFigures]: [initSyncHealth] rewrites the health row
 * on every emission of that holder, so the flag would cost two extra health writes per catch-up.
 */
class SyncCatchUp internal constructor(
    private val row: Row,
    private val heartbeat: Heartbeat? = null,
    private val nowMillis: () -> Long = wallClockMillis,
    private val timing: CatchUpHeartbeatTiming = CatchUpHeartbeatTiming.Default,
) {

    /**
     * This writer's row, as **this process's** view of the store has it.
     *
     * ⚠⚠ "Change only" is decided against the store, not against a memory of the last write, and
     * that is what makes it hold on a shared replica. The relation is one array of member hashes
     * and every process caches it; a sibling writing its own row from a stale cache can drop
     * this writer's membership, and the next rehydrate then shows this process a relation
     * without its row. A remembered "I already wrote `true`" would never write again. Asking the
     * store costs two small reads per raise and puts the row back within a tick.
     */
    internal interface Row {
        /** Whether a reader of this writer's row would already see [value], and durably so. */
        fun reads(value: Boolean): Boolean

        /** Replace this writer's row with [value]. */
        fun write(value: Boolean)
    }

    /**
     * This writer's [SyncCatchUpHeartbeat] row, as this process's view of the store has it.
     * Asked, not remembered, for [Row]'s reason (§10.3).
     */
    internal interface Heartbeat {
        /** The stored row, or `null` when there is none. */
        fun read(): Beat?

        /** Replace this writer's heartbeat row. */
        fun write(catchingUp: Boolean, atMillis: Long)
    }

    /** A stored heartbeat. [member] is whether the relation lists it, i.e. whether a reader sees it. */
    internal data class Beat(val catchingUp: Boolean, val atMillis: Long, val member: Boolean)

    /**
     * ⚠⚠ [revision] moves on every change of [catchingUp], and it is what the collector follows.
     * A `StateFlow` conflates by equality: a raise and its settle inside one tick take the flag
     * `false → true → false`, a collector that did not run in between is offered a value equal
     * to the one it last saw, and it is never woken — leaving the `true` that [raise] wrote in
     * the store for ever. Two states that differ only by revision are not equal, so it wakes.
     */
    private data class State(
        val catchingUp: Boolean = false,
        val stopped: Boolean = false,
        val revision: Long = 0,
    )

    private val state = MutableStateFlow(State())

    private val writeLock = Mutex()

    /** The flag as the scheduler last left it. In memory; the row may lag a settle. */
    val catchingUp: Boolean get() = state.value.catchingUp

    /**
     * Work is expected or a page of rows is about to be applied. Returns once the row reads
     * `true`. A no-op while [stop]ped.
     */
    suspend fun raise() {
        if (!accept(true)) return
        flush()
    }

    /**
     * A tick ended: [backlog] is whether any pull path still reports one. The row follows
     * asynchronously. A no-op while [stop]ped.
     */
    fun settle(backlog: Boolean) {
        accept(backlog)
    }

    /** The scheduler is stopping: clear, and refuse everything until [resume]. */
    fun stop() {
        state.update { State(catchingUp = false, stopped = true, revision = it.revision + 1) }
    }

    /** The scheduler is starting again. */
    fun resume() {
        state.update { it.copy(stopped = false) }
    }

    /** Applies [value] unless stopped; returns whether it was accepted. */
    private fun accept(value: Boolean): Boolean {
        var accepted = false
        state.update { current ->
            accepted = !current.stopped
            if (current.stopped || current.catchingUp == value) {
                current
            } else {
                current.copy(catchingUp = value, revision = current.revision + 1)
            }
        }
        return accepted
    }

    /** Write the row if it differs from the flag as it stands now; the heartbeat with it. */
    private suspend fun flush() = writeLock.withLock {
        val now = state.value.catchingUp
        guardDerivedViewTick(SyncService.Relations.CATCH_UP) {
            if (!row.reads(now)) row.write(now)
        }
        guardDerivedViewTick(SyncService.Relations.CATCH_UP_HEARTBEAT) {
            syncHeartbeat(now)
        }
    }

    /**
     * The heartbeat row, brought in line with [catchingUp]. Raising writes a beat unless the store
     * already holds one of ours that a reader sees and that is younger than one interval — so a
     * ten-page catch-up does not cost a heartbeat write per page, and a row a sibling dropped from
     * the relation is put back. Settling writes `false` over any stored `true`, member or not
     * ([Row.reads]'s reason: a stale-cache sibling can put a dropped member back).
     */
    private fun syncHeartbeat(catchingUp: Boolean) {
        val beat = heartbeat ?: return
        val stored = beat.read()
        val now = nowMillis()
        if (catchingUp) {
            val recent = stored != null && stored.catchingUp && stored.member &&
                now - stored.atMillis in 0 until timing.intervalMs
            if (!recent) beat.write(catchingUp = true, atMillis = now)
        } else if (stored?.catchingUp == true) {
            beat.write(catchingUp = false, atMillis = now)
        }
    }

    /**
     * One periodic beat. Unconditional while catching up: rewriting is the point, and it also
     * restores a row a sibling dropped. Under [writeLock], so it cannot land after a settle's
     * `false` with a stale `true`.
     */
    private suspend fun beat() = writeLock.withLock {
        val current = state.value
        if (!current.catchingUp || current.stopped) return@withLock
        val beat = heartbeat ?: return@withLock
        guardDerivedViewTick(SyncService.Relations.CATCH_UP_HEARTBEAT) {
            beat.write(catchingUp = true, atMillis = nowMillis())
        }
    }

    /**
     * Clear what a killed process left behind, and start following the flag.
     *
     * ⚠ The clear happens **here, synchronously**, not in the collector: a process killed
     * mid-pull leaves its row `true`, and a reader that subscribes before the collector first
     * runs would show it. It touches only this writer's row, and writes nothing when that row is
     * absent or already `false` — which is every ordinary launch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun publishIn(scope: CoroutineScope) {
        guardDerivedViewTick(SyncService.Relations.CATCH_UP) {
            if (!row.reads(false)) row.write(false)
        }
        // A heartbeat this app's previous process left `true` would expire by itself; clearing it
        // here makes a relaunch prompt, and costs nothing on an ordinary launch.
        guardDerivedViewTick(SyncService.Relations.CATCH_UP_HEARTBEAT) {
            if (heartbeat?.read()?.catchingUp == true) heartbeat.write(catchingUp = false, atMillis = nowMillis())
        }
        scope.launch {
            // Every revision, not every distinct boolean — see [State].
            state.collect { flush() }
        }
        if (heartbeat != null) {
            scope.launch {
                // The one timer, and only while catching up: a flag going down (or [stop])
                // cancels the loop through `collectLatest`.
                state.map { it.catchingUp && !it.stopped }
                    .distinctUntilChanged()
                    .collectLatest { active ->
                        while (active) {
                            delay(timing.intervalMs)
                            beat()
                        }
                    }
            }
        }
    }
}

/**
 * Builds this runtime's [SyncCatchUp] over the per-writer [SyncService.Relations.CATCH_UP]
 * relation and starts publishing it. [writerId] is the host app id; a runtime whose host declared
 * none writes under [SyncCatchUpStatus.UNNAMED_WRITER].
 *
 * Remove-then-add of **this writer's** row only, in one transaction, like the single-row
 * publishers beside it — but only when the store does not already say so ([SyncCatchUp.Row]).
 * Local derivation: never synced.
 */
fun ExecutionContext.initSyncCatchUp(
    scope: CoroutineScope,
    writerId: String?,
    nowMillis: () -> Long = wallClockMillis,
    timing: CatchUpHeartbeatTiming = CatchUpHeartbeatTiming.Default,
    heartbeat: Boolean = true,
): SyncCatchUp {
    val key = writerId?.takeIf { it.isNotBlank() } ?: SyncCatchUpStatus.UNNAMED_WRITER
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.CATCH_UP,
        ),
        isEntity = false,
        elementSchemaId = SyncCatchUpStatus.ID,
    )
    val rowId = DataId.StringDataId.create(
        context = context,
        type = SyncCatchUpStatus.ID,
        keyValue = key,
    )
    val beatRef = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.CATCH_UP_HEARTBEAT,
        ),
        isEntity = false,
        elementSchemaId = SyncCatchUpHeartbeat.ID,
    )
    val beatRowId = DataId.StringDataId.create(
        context = context,
        type = SyncCatchUpHeartbeat.ID,
        keyValue = key,
    )
    val catchUp = SyncCatchUp(
        object : SyncCatchUp.Row {
            override fun reads(value: Boolean): Boolean {
                val stored = (database.getData(rowId, asEntity = false, projection = null)
                    as? SyncCatchUpStatus)?.catchingUp?.get()
                // `false` needs no row at all: a reader maps absence to `false`. What it must not
                // leave behind is a stored `true`, member or not — a sibling writing the relation
                // from a stale cache can put a dropped member back.
                if (!value) return stored != true
                return stored == true && database.getArrayData(ref)
                    ?.any { (it as? SyncCatchUpStatus)?.key?.get() == key } == true
            }

            override fun write(value: Boolean) {
                val status = SyncCatchUpStatus.create(context = context, writerId = key, catchingUp = value)
                database.transaction {
                    database.removeFromArray(ref, rowId)
                    database.addToArray(ref, status)
                }
            }
        },
        heartbeat = if (!heartbeat) null else object : SyncCatchUp.Heartbeat {
            override fun read(): SyncCatchUp.Beat? {
                val stored = database.getData(beatRowId, asEntity = false, projection = null)
                    as? SyncCatchUpHeartbeat ?: return null
                val member = database.getArrayData(beatRef)
                    ?.any { (it as? SyncCatchUpHeartbeat)?.key?.get() == key } == true
                return SyncCatchUp.Beat(
                    catchingUp = stored.catchingUp.get(),
                    atMillis = stored.beatAtMillis.get(),
                    member = member,
                )
            }

            override fun write(catchingUp: Boolean, atMillis: Long) {
                val beat = SyncCatchUpHeartbeat.create(
                    context = context,
                    writerId = key,
                    catchingUp = catchingUp,
                    beatAtMillis = atMillis,
                )
                database.transaction {
                    val member = database.getArrayData(beatRef)
                        ?.any { (it as? SyncCatchUpHeartbeat)?.key?.get() == key } == true
                    if (member) {
                        // ⚠ An update in place, not remove-then-add: that pair reaches a relation
                        // stream as a removal and then an addition, so every beat read as
                        // "gone, back" — a `false` between two `true`s for any reader — and cost
                        // three store writes. The R2b path: one row write, one member-updated event.
                        database.saveData(beat)
                        database.notifyArrayMemberUpdated(beatRef, beat)
                    } else {
                        // Absent, or dropped from the relation by a stale-cache sibling (§10.3).
                        database.removeFromArray(beatRef, beatRowId)
                        database.addToArray(beatRef, beat)
                    }
                }
            }
        },
        nowMillis = nowMillis,
        timing = timing,
    )
    catchUp.publishIn(scope)
    return catchUp
}
