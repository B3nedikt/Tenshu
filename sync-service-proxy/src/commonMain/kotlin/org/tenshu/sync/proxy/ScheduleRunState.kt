package org.tenshu.sync.proxy

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A per-key **generation** for the `ScheduleItem` registry: bumped by everything that registers or
 * cancels a key, read by [ScheduleDrainLoop] when it starts a run and again when that run completes.
 *
 * ⚠⚠ **Why it exists.** Every row for a key has the same `DataId` (it is derived from `dedupKey`
 * alone), so a run's completion cannot tell the row it started from one that replaced it meanwhile.
 * Without this, a `Schedule` for the key during its run was overwritten by the run's completion, and
 * a `CancelScheduled` during its run was undone by it. A completion that finds the generation moved
 * writes nothing.
 *
 * ⚠⚠ **An atomic, never a coroutine `Mutex`.** Both writers are plain non-suspend `execute`s
 * (`ScheduleImplementation`, `CancelScheduledImplementation`) and must stay so (House Rule 3). They
 * bump **inside their `Database.transaction`, before they touch the row**: the store lock makes bump
 * and row one step against the completion, and bump-first means a row write that throws costs one
 * extra run rather than a lost registration.
 *
 * ⚠ **Process-local on purpose.** Nothing is persisted, so no stored shape moves (House Rule 9). A
 * restart starts at zero for every key, which is sound: a run from a dead process has no completion.
 * The limit is iOS route 1, where several app processes share one store and cannot see each other's
 * bumps.
 */
@OptIn(ExperimentalAtomicApi::class)
class ScheduleRunState {

    private val generations = AtomicReference<Map<String, Long>>(emptyMap())

    /** The generation of [key]; `0` for a key nothing has registered or cancelled in this process. */
    fun generationOf(key: String): Long = generations.load()[key] ?: 0L

    /** Every key's generation at one instant — what a tick reads before it reads the rows. */
    fun snapshot(): Map<String, Long> = generations.load()

    /** ⚠ Call inside the writer's `Database.transaction`, before the row write. */
    fun bump(key: String) {
        while (true) {
            val current = generations.load()
            val next = current + (key to (current[key] ?: 0L) + 1)
            if (generations.compareAndSet(current, next)) return
        }
    }

    companion object {
        /**
         * The process's one instance — the writers and the loop must share it, and there is one
         * store per process. A default rather than a DI binding so the guard adds no wiring.
         */
        val Default = ScheduleRunState()
    }
}
