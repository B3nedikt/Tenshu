@file:OptIn(org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.db.sqlite.DamagedCopy
import org.tenshu.core.common.proxy.db.sqlite.DamagedSnapshotOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaDamagedCopies
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken
import platform.Foundation.NSRecursiveLock
import kotlin.concurrent.AtomicInt
import kotlin.native.HiddenFromObjC

/**
 * What this process knows of the damaged store's copy (Appendix B.18, Q47), for the "Storage
 * damaged" cover's copy and its "Export damaged data". ⚠ Nothing but [COMPLETE] carries files.
 */
internal enum class DamagedCopyPhase {
    /** Not looked at: the store is not known to be corrupt here. */
    UNKNOWN,

    /** No complete copy: nothing taken yet, the attempt stopped or was refused (busy, failed). */
    NOT_COPIED,

    /** Control is taking the copy now. */
    COPYING,

    /** A complete copy of this marker and these originals is on disk ([DamagedCopyView.files]). */
    COMPLETE,

    /** Refused: not enough free space for a whole copy and its floor ([DamagedCopyView.neededBytes]). */
    NO_ROOM,

    /** The byte copy's fingerprints differed in every attempt (§6: "could not be copied consistently"). */
    INCONSISTENT,

    /** ⭐ Q51: the user deleted this damage's copy in Control; it is not copied again (B.18.8 (320)). */
    DELETED,
}

internal data class DamagedCopyView(val phase: DamagedCopyPhase, val files: List<String> = emptyList(), val neededBytes: Long = 0) {
    companion object {
        val UNKNOWN = DamagedCopyView(DamagedCopyPhase.UNKNOWN)
        val NOT_COPIED = DamagedCopyView(DamagedCopyPhase.NOT_COPIED)
        val COPYING = DamagedCopyView(DamagedCopyPhase.COPYING)
        val DELETED = DamagedCopyView(DamagedCopyPhase.DELETED)

        fun of(copy: DamagedCopy?): DamagedCopyView = copy?.let { DamagedCopyView(DamagedCopyPhase.COMPLETE, it.files) } ?: NOT_COPIED

        /** What one attempt of Control's snapshot leaves the cover saying. */
        fun of(outcome: DamagedSnapshotOutcome): DamagedCopyView = when (outcome) {
            is DamagedSnapshotOutcome.Completed -> of(outcome.copy)
            is DamagedSnapshotOutcome.AlreadyComplete -> of(outcome.copy)
            is DamagedSnapshotOutcome.NoRoom -> DamagedCopyView(DamagedCopyPhase.NO_ROOM, neededBytes = outcome.neededBytes)
            is DamagedSnapshotOutcome.Inconsistent -> DamagedCopyView(DamagedCopyPhase.INCONSISTENT)
            DamagedSnapshotOutcome.Deleted -> DELETED
            DamagedSnapshotOutcome.NotCorrupt -> UNKNOWN
            // ⚠ (4): stopped, busy, failed, a marker not durable yet: still on the phone, not copied yet.
            else -> NOT_COPIED
        }
    }
}

/**
 * ⚠⚠ **The damaged store's copy, per process** (Appendix B.18, Q47). One per
 * [SharedReplicaBinding], so behind the switch only: an unswitched process has none, and the
 * surface's view is [DamagedCopyView.UNKNOWN] for good.
 *
 * - **Control** has an [exporter] ([DamagedSnapshotRunner]): while the host shows `CORRUPT` and
 *   the process is in the foreground, it takes the snapshot (§6 step 4: "Control alone does this")
 *   and publishes each attempt's result.
 * - **Every other app** only looks ([SharedReplicaDamagedCopies.current]: file looks, no SQLite),
 *   when its host turns `CORRUPT` and at each foreground while it is, so its cover can say whether
 *   Control has kept a copy.
 */
@HiddenFromObjC
internal class DamagedDataCopies(
    internal val replicaDirectory: String,
    private val look: (String) -> DamagedCopy? = SharedReplicaDamagedCopies::current,
    /** ⭐ Q51: the user's delete was of this damage ([SharedReplicaDamagedCopies.isDeleted]). A test seam. */
    private val deleted: (String) -> Boolean = SharedReplicaDamagedCopies::isDeleted,
    /** ⭐ Q51: the delete itself ([SharedReplicaDamagedCopies.delete]). A test seam. */
    private val deleter: (String) -> Boolean = SharedReplicaDamagedCopies::delete,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = ::println,
) {
    // ⚠ Every field above any function that reads it (K/N).
    private val lock = NSRecursiveLock()
    private val viewFlow = MutableStateFlow(DamagedCopyView.UNKNOWN)
    private var following: Job? = null
    private val looking = AtomicInt(0)

    /** B.18.10 (327): moved, under [lock], by each delete as it publishes. */
    private var deleteGeneration = 0L

    /** The cover's view of the copy. */
    val view: StateFlow<DamagedCopyView> = viewFlow.asStateFlow()

    private val exporterFlow = MutableStateFlow<DamagedSnapshotRunner?>(null)
    private var followed: StateFlow<SharedReplicaHostStatus>? = null
    private var followedForeground: () -> Boolean = { false }

    /**
     * Control's snapshot runner; null in every other process. Set once, by [ControlSharedReplica].
     * ⚠ B.18.8 (318): the follow may already be running (it starts with the binding), so setting
     * it while the host shows `CORRUPT` starts the attempt then, and [isExporterFlow] moves the
     * cover: a Control is never shown, nor run, as another app for want of ordering.
     */
    var exporter: DamagedSnapshotRunner?
        get() = exporterFlow.value
        set(value) {
            exporterFlow.value = value
            val status = lock.run { lock(); try { followed } finally { unlock() } }
            if (value != null && status?.value?.state == SharedReplicaHostState.CORRUPT) onCorrupt(followedForeground())
        }

    /** True in Control's process: its cover offers the export itself. */
    val isExporter: Boolean get() = exporter != null

    /** [isExporter], as a flow for the cover. */
    val isExporterFlow: kotlinx.coroutines.flow.Flow<Boolean> = exporterFlow.map { it != null }.distinctUntilChanged()

    /**
     * Follows the host's state: at each move into `CORRUPT` the copy is taken (Control, in the
     * foreground) or looked at (every other app); out of it, the view is reset. Once per binding.
     */
    fun follow(status: StateFlow<SharedReplicaHostStatus>, isForeground: () -> Boolean) {
        lock.lock()
        try {
            if (following != null) return
            followed = status
            followedForeground = isForeground
            following = scope.launch {
                status.map { it.state == SharedReplicaHostState.CORRUPT }.distinctUntilChanged().collect { corrupt ->
                    if (corrupt) onCorrupt(isForeground()) else viewFlow.value = DamagedCopyView.UNKNOWN
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** The binding's foreground hook, while the host shows `CORRUPT`: §6 retries at the next foreground. */
    fun onForeground(state: SharedReplicaHostState) {
        if (state == SharedReplicaHostState.CORRUPT) onCorrupt(foreground = true)
    }

    private fun onCorrupt(foreground: Boolean) {
        val runner = exporter
        if (runner != null) {
            // ⚠ Foreground only (§6, F6k): in the background Control does not even look.
            if (foreground) runner.start() else if (viewFlow.value.phase == DamagedCopyPhase.UNKNOWN) lookNow()
        } else {
            lookNow()
        }
    }

    /** A look off the calling thread, one at a time. */
    private fun lookNow() {
        if (!looking.compareAndSet(0, 1)) return
        scope.launch {
            try {
                publish(if (deleted(replicaDirectory)) DamagedCopyView.DELETED else DamagedCopyView.of(look(replicaDirectory)))
            } catch (t: Throwable) {
                log("[replica-damaged] the look at the copies threw: ${t.message}")
            } finally {
                looking.value = 0
            }
        }
    }

    /**
     * ⭐ Q51 (decided 2026-10-30): **"Delete the copy"**, Control's only, over a complete copy, once
     * the user confirmed it (Swift). Off the main thread; the cover then says it was deleted, or,
     * if it could not be, keeps the copy and its export.
     */
    fun delete(): Boolean {
        if (!isExporter || viewFlow.value.phase != DamagedCopyPhase.COMPLETE) return false
        scope.launch {
            val done = try {
                deleter(replicaDirectory)
            } catch (t: Throwable) {
                log("[replica-damaged] the delete threw: ${t.message}")
                false
            }
            if (done) {
                // ⚠ B.18.10 (327): the bump and the publish are one step under the lock, the same
                // one an attempt's publish takes, so no result of an earlier attempt can follow it.
                lock.lock()
                try {
                    deleteGeneration++
                    publish(DamagedCopyView.DELETED)
                } finally {
                    lock.unlock()
                }
            } else {
                log("[replica-damaged] the copy was not deleted: it stays, with its export")
            }
        }
        return true
    }

    /**
     * ⚠ B.18.9 (323): an attempt's result, published outside the engine's lock, so it can land
     * after a delete that ran just behind it: a copy is never shown again once the user deleted it.
     */
    internal fun publishAttempt(outcome: DamagedSnapshotOutcome, startedAtGeneration: Long = deleteGenerationNow()) {
        val view = DamagedCopyView.of(outcome)
        val stale = view.phase == DamagedCopyPhase.COMPLETE && try {
            deleted(replicaDirectory)
        } catch (t: Throwable) {
            false
        }
        beforeAttemptPublish?.invoke()
        // ⚠⚠ B.18.10 (327), Codex P2 at b136495: the tombstone read above is a check, and a delete
        // can land between it and the publish. So the publish is decided again under the lock the
        // delete publishes under: a delete that published since this attempt began wins.
        lock.lock()
        try {
            if (view.phase == DamagedCopyPhase.COMPLETE && deleteGeneration != startedAtGeneration) return
            publish(if (stale) DamagedCopyView.DELETED else view)
        } finally {
            lock.unlock()
        }
    }

    /** Deletes published so far in this process (B.18.10 (327)); an attempt takes it as it begins. */
    internal fun deleteGenerationNow(): Long {
        lock.lock()
        try {
            return deleteGeneration
        } finally {
            lock.unlock()
        }
    }

    /** Test seam: called in [publishAttempt] between its tombstone check and its publish. Null in production. */
    @kotlin.concurrent.Volatile
    internal var beforeAttemptPublish: (() -> Unit)? = null

    internal fun publish(view: DamagedCopyView) {
        viewFlow.value = view
    }

    /** Test seam: the current view, read off the flow. */
    internal val current: DamagedCopyView get() = viewFlow.value
}

/**
 * ⚠⚠ **Control's snapshot, run** (§6 step 4, F6k; Appendix B.18): one attempt at a time, off the
 * main thread, under an explicit protection token for the whole attempt. The token's expiry, the
 * gate closing and the foreground ending each stop it at its next check; the engine leaves its
 * `.partial`, and the next foreground starts again. Each attempt's result is published to the
 * cover ([DamagedDataCopies.publish]).
 */
@HiddenFromObjC
internal class DamagedSnapshotRunner(
    private val copies: DamagedDataCopies,
    private val take: (shouldStop: () -> Boolean) -> DamagedSnapshotOutcome,
    /** An explicit token for the attempt (the binding's entry token); null when none can be had. */
    private val protect: (onExpired: () -> Unit) -> SharedReplicaEntryToken?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = ::println,
) {
    private val running = AtomicInt(0)

    /** Test seam: attempts started. */
    internal val attempts = AtomicInt(0)

    /** Test seam: attempts ended, their result published. */
    internal val finished = AtomicInt(0)

    /** Starts an attempt unless one is running. True when one was started. */
    fun start(): Boolean {
        if (!running.compareAndSet(0, 1)) return false
        attempts.incrementAndGet()
        scope.launch {
            try {
                run()
            } catch (t: Throwable) {
                log("[replica-damaged] the snapshot threw (${t::class.simpleName}: ${t.message}): nothing is offered")
                copies.publish(DamagedCopyView.NOT_COPIED)
            } finally {
                running.value = 0
                finished.incrementAndGet()
            }
        }
        return true
    }

    private fun run() {
        val expired = AtomicInt(0)
        val token = try {
            protect { expired.value = 1 }
        } catch (t: Throwable) {
            log("[replica-damaged] no token for the snapshot: ${t.message}")
            null
        }
        if (token == null) {
            log("[replica-damaged] no protected time for the snapshot: retried at the next foreground")
            if (copies.current.phase != DamagedCopyPhase.COMPLETE) copies.publish(DamagedCopyView.NOT_COPIED)
            return
        }
        try {
            // ⚠ A complete copy stays shown while the attempt checks it: no flicker to "copying".
            if (copies.current.phase != DamagedCopyPhase.COMPLETE && copies.current.phase != DamagedCopyPhase.DELETED) copies.publish(DamagedCopyView.COPYING)
            // B.18.10 (327): taken before the attempt, so a delete that publishes meanwhile wins.
            val generation = copies.deleteGenerationNow()
            val outcome = take { expired.value == 1 }
            log("[replica-damaged] snapshot attempt: $outcome")
            copies.publishAttempt(outcome, generation)
        } finally {
            token.release()
        }
    }
}
