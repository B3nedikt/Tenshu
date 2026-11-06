package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.util.bestEffort
import org.tenshu.sync.BackgroundTask

/**
 * Proxy-side registry of live bulk tasks (`core-task-composite.md` §5–§6,
 * TS-3): one row per registered `BackgroundTask`, the task's **lease**, and
 * the live-task count that feeds the platform liveness sum ([activeCount] —
 * mirror of `ProxyGateway.inFlightCount`; a task in flight holds the Android
 * FGS via the same additive `combine` in `ProxyService`).
 *
 * v1 durability is **Layer 2**: rows are in-memory and die with the proxy —
 * staged deltas survive independently via the TS-2 lane journal, and
 * recovery is re-register + consumer idempotency. §10.5 D9: the task lease
 * IS the lane lease — [sweepExpired] transitions expired RUNNING tasks to
 * FAILED and the sweep's caller closes their lanes (auto-checkpointing
 * staged deltas so nothing strands with a dead client).
 *
 * Lease renewal (v1): `register`, `renewLease`, and `transition` renew. A
 * client loop should tick `renewLease` at least every [leaseMs]/2 — constituent
 * calls do NOT renew in v1 (that would need a ProxyGateway→registry hook;
 * noted as-built).
 */
class TaskRegistry(
    private val nowMs: () -> Long,
    private val leaseMs: Long = DEFAULT_LEASE_MS,
) {
    companion object {
        const val DEFAULT_LEASE_MS: Long = 5 * 60 * 1000L
    }

    class TaskRow(
        val taskId: String,
        val specType: String,
        var state: String,
        var leaseExpiresAtMs: Long,
        /** A constituent call arrived marked user-started ([markUserInitiated]). Sticky. */
        var userInitiated: Boolean = false,
    )

    private val mutex = Mutex()
    private val rows = mutableMapOf<String, TaskRow>()

    private val _activeCount = MutableStateFlow(0)

    /** Number of RUNNING tasks — the liveness contribution (§6). */
    val activeCount: StateFlow<Int> get() = _activeCount

    private val _userInitiatedActiveCount = MutableStateFlow(0)

    /**
     * RUNNING tasks a user started — what Android's foreground service counts between a bulk
     * task's calls (`control-background-work-off-fgs.md` §5.2).
     *
     * ⚠⚠ Needed beside the in-flight mark: a user-started folder upload has gaps between its calls
     * longer than the demotion linger (hashing a multi-GB video before its upload call), and once the
     * user has left, a demoted service cannot promote again.
     */
    val userInitiatedActiveCount: StateFlow<Int> get() = _userInitiatedActiveCount

    /**
     * A constituent call of [taskId] arrived marked user-started (`ProxyGateway`'s hook). Unknown
     * ids — a per-call Background op's minted id, not a registered task — are ignored.
     */
    suspend fun markUserInitiated(taskId: String) {
        mutex.withLock {
            val row = rows[taskId] ?: return@withLock
            if (!row.userInitiated) {
                row.userInitiated = true
                refreshActiveCount()
            }
        }
    }

    suspend fun register(taskId: String, specType: String): TaskRow =
        mutex.withLock {
            rows.getOrPut(taskId) {
                TaskRow(
                    taskId = taskId,
                    specType = specType,
                    state = BackgroundTask.States.RUNNING,
                    leaseExpiresAtMs = nowMs() + leaseMs,
                )
            }.also {
                it.leaseExpiresAtMs = nowMs() + leaseMs
                refreshActiveCount()
            }
        }

    suspend fun renewLease(taskId: String): TaskRow? = mutex.withLock {
        rows[taskId]?.also {
            if (it.state == BackgroundTask.States.RUNNING) {
                it.leaseExpiresAtMs = nowMs() + leaseMs
            }
        }
    }

    /** Idempotent: an already-terminal row is returned unchanged. */
    suspend fun transition(taskId: String, terminalState: String): TaskRow? = mutex.withLock {
        rows[taskId]?.also {
            if (it.state == BackgroundTask.States.RUNNING) {
                it.state = terminalState
                refreshActiveCount()
            }
        }
    }

    suspend fun get(taskId: String): TaskRow? = mutex.withLock { rows[taskId] }

    /**
     * D9 lease sweep: transition every RUNNING row past its lease to FAILED
     * and return their ids — the caller closes each task's sync lane
     * (auto-checkpointing its staged deltas).
     */
    suspend fun sweepExpired(now: Long = nowMs()): List<String> = mutex.withLock {
        val expired = rows.values.filter {
            it.state == BackgroundTask.States.RUNNING && it.leaseExpiresAtMs <= now
        }
        expired.forEach { it.state = BackgroundTask.States.FAILED }
        if (expired.isNotEmpty()) refreshActiveCount()
        expired.map { it.taskId }
    }

    /**
     * Self-contained sweep ticker (no `SyncScheduler` coupling): checks every
     * [leaseMs]/2 and hands expired task ids to [onExpired] (production:
     * `SyncObserver.closeSyncLane`). Started by `initSyncService`.
     */
    fun startLeaseSweep(scope: CoroutineScope, onExpired: suspend (String) -> Unit) {
        scope.launch {
            while (isActive) {
                delay(leaseMs / 2)
                sweepExpired().forEach { taskId ->
                    bestEffort("TaskRegistry -> expiring a lease") { onExpired(taskId) }
                }
            }
        }
    }

    private fun refreshActiveCount() {
        _activeCount.value = rows.values.count { it.state == BackgroundTask.States.RUNNING }
        _userInitiatedActiveCount.value =
            rows.values.count { it.state == BackgroundTask.States.RUNNING && it.userInitiated }
    }
}
