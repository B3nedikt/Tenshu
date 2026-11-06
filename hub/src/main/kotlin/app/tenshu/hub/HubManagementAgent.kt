package app.tenshu.hub

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.client.hubManagementService

/**
 * H4 (hub proposal §8, as-built): the hub side of `HubManagementService` —
 * a **status publisher** and a **command executor** over the synced rows.
 *
 *  - *Status*: authors this hub's [HubStatus] row (keyed by its device id) on
 *    start, after each backup pass, after executing a command, and on a
 *    bounded heartbeat. Writes are change-gated: a pass that changed nothing
 *    observable produces no sync block ([publishStatus] compares against the
 *    last published snapshot, ignoring the always-moving `freshAsOf` /
 *    `lastBackupPassAt` timestamps), and the [heartbeatMs] cap bounds how
 *    stale a healthy hub's row can look.
 *  - *Commands*: observes [HubCommand] rows arriving via ordinary sync and
 *    executes each **at most once** — consumed ids are persisted in a durable
 *    [ConsumedCommandJournal] under the hub's data dir *before* execution,
 *    and commands older than [staleWindowMs] are ignored entirely, so a hub
 *    restored from a sync backlog does not replay a restart storm. Outcomes
 *    are reported on the status row (`lastCommandId` / `lastCommandOutcome`).
 *
 * `RestartHub(mode="proxy")` publishes [HubStatus.OUTCOME_RESTARTING] and
 * invokes [restartHook] (production: clean shutdown + `exitProcess(EXIT_RESTART)`,
 * which systemd's `Restart=` re-spawns — see `hub/deploy/tenshu-hub.service`).
 * `mode="system"` is v1-unimplemented → [HubStatus.OUTCOME_UNSUPPORTED].
 */
class HubManagementAgent(
    private val runtime: HubRuntime,
    private val deviceName: String,
    private val version: String = System.getProperty("tenshu.hub.version") ?: "dev",
    private val journal: ConsumedCommandJournal =
        ConsumedCommandJournal(File(runtime.dataRoot, "consumed-commands.log")),
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
    private val refreshTickMs: Long = DEFAULT_REFRESH_TICK_MS,
    private val staleWindowMs: Long = HubCommandGate.DEFAULT_STALE_WINDOW_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Invoked for an accepted `RestartHub(mode="proxy")` — see class KDoc. */
    private val restartHook: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contentRoot = File(runtime.dataRoot, "content")

    private var heartbeatJob: Job? = null
    private var commandJob: Job? = null

    /** The last published snapshot (change gate); `null` until the first write. */
    @Volatile
    private var lastPublished: StatusSnapshot? = null

    @Volatile
    private var lastPublishedAtMs: Long = 0L

    @Volatile
    private var lastCommandId: String = ""

    @Volatile
    private var lastCommandOutcome: String = ""

    /** Executed commands this process — the system tests' exactly-once probe. */
    @Volatile
    var executedCommandCount: Long = 0L
        private set

    /** Start the publisher + executor. Idempotent; call after `runtime.start()`. */
    fun start() {
        if (heartbeatJob?.isActive == true) return
        runtime.onBackupPassCompleted = { publishStatus() }
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    publishStatus()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("tenshu-hub: status publish failed (will retry): $t")
                }
                delay(refreshTickMs)
            }
        }
        commandJob = scope.launch {
            runtime.client.hubManagementService.hubCommands.flow().collect { result ->
                val commands = (result as? TResult.Success)?.value?.toList().orEmpty()
                for (command in commands) {
                    try {
                        handleCommand(command)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        println("tenshu-hub: command handling failed: $t")
                    }
                }
            }
        }
    }

    suspend fun stop() {
        runtime.onBackupPassCompleted = null
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        commandJob?.cancelAndJoin()
        commandJob = null
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    /**
     * Author/refresh this hub's [HubStatus] row. Change-gated unless [force]:
     * writes when an observable field differs from the last published
     * snapshot, or [heartbeatMs] has elapsed (bounded freshness). The
     * always-moving `freshAsOf`/`lastBackupPassAt` timestamps are excluded
     * from the comparison — a routine backup pass that fetched nothing does
     * not produce a sync block.
     */
    suspend fun publishStatus(force: Boolean = false) {
        val deviceId = runtime.syncManager.currentDeviceId ?: return
        if (!runtime.isPaired()) return

        val disk = diskStats()
        val snapshot = StatusSnapshot(
            deviceId = deviceId,
            name = deviceName,
            version = version,
            startedAtEpochMs = runtime.startedAtEpochMs,
            replica = replicaStats(),
            diskTotalBytes = disk.totalBytes,
            diskFreeCoarseBytes = disk.freeBytes / DISK_FREE_CHANGE_QUANTUM_BYTES,
            paired = true,
            lastCommandId = lastCommandId,
            lastCommandOutcome = lastCommandOutcome,
        )
        val now = nowMs()
        val heartbeatDue = now - lastPublishedAtMs >= heartbeatMs
        if (!force && snapshot == lastPublished && !heartbeatDue) return

        val entity = HubStatus.create(
            context = executionContext().context,
            deviceId = deviceId,
            name = snapshot.name,
            version = snapshot.version,
            startedAtEpochMs = snapshot.startedAtEpochMs,
            replicaFileCount = snapshot.replica.fileCount,
            replicaByteCount = snapshot.replica.byteCount,
            diskFreeBytes = disk.freeBytes,
            diskTotalBytes = disk.totalBytes,
            lastBackupPassAtEpochMs = runtime.lastBackupPassAtEpochMs,
            paired = snapshot.paired,
            freshAsOf = now,
            lastCommandId = snapshot.lastCommandId,
            lastCommandOutcome = snapshot.lastCommandOutcome,
        )
        executionContext().saveData(entity)
        lastPublished = snapshot
        lastPublishedAtMs = now
    }

    /** At-most-once command execution — see class KDoc. */
    internal suspend fun handleCommand(command: HubCommand) {
        val ownDeviceId = runtime.syncManager.currentDeviceId ?: return
        val commandId = command.commandId.get()
        val decision = HubCommandGate.decide(
            targetDeviceId = command.targetDeviceId.get(),
            issuedAtEpochMs = command.issuedAtEpochMs.get(),
            ownDeviceId = ownDeviceId,
            nowEpochMs = nowMs(),
            alreadyConsumed = journal.isConsumed(commandId),
            staleWindowMs = staleWindowMs,
        )
        if (decision != HubCommandGate.Decision.Execute) return

        // Persist BEFORE executing: at-MOST-once. A crash between here and the
        // execution loses the command (the issuer re-issues off the stale
        // status row) — never doubles it.
        journal.markConsumed(commandId)
        executedCommandCount += 1
        lastCommandId = commandId

        when (command.kind.get()) {
            HubCommand.KIND_RUN_BACKUP_NOW -> {
                lastCommandOutcome = try {
                    runtime.runBackupPassNow()
                    HubStatus.OUTCOME_OK
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("tenshu-hub: commanded backup pass failed: $t")
                    HubStatus.OUTCOME_FAILED
                }
                publishStatus(force = true)
            }

            HubCommand.KIND_RESTART -> when (command.mode.get()) {
                HubCommand.MODE_PROXY -> {
                    // The status block is durably journaled by saveData; if the
                    // process exits before the next drain tick, it is emitted on
                    // the post-restart boot (the standard durable-block drain).
                    lastCommandOutcome = HubStatus.OUTCOME_RESTARTING
                    publishStatus(force = true)
                    println("tenshu-hub: restart requested (command $commandId) — exiting for supervisor re-spawn")
                    restartHook()
                }

                else -> {
                    // mode="system" (node reboot) is v1-unimplemented (documented
                    // in the proposal §8 as-built notes), as is any unknown mode.
                    lastCommandOutcome = HubStatus.OUTCOME_UNSUPPORTED
                    publishStatus(force = true)
                }
            }

            else -> {
                lastCommandOutcome = HubStatus.OUTCOME_UNSUPPORTED
                publishStatus(force = true)
            }
        }
    }

    private fun executionContext() =
        runtime.koin.get<ClientRegistry>().getExecutionContext(HubRuntime.PACKAGE_NAME)

    /** Replica figures straight off the H3 content dir (cheap local walk). */
    private fun replicaStats(): ReplicaStats {
        var files = 0L
        var bytes = 0L
        contentRoot.walkTopDown().forEach { f ->
            if (f.isFile) {
                files += 1
                bytes += f.length()
            }
        }
        return ReplicaStats(fileCount = files, byteCount = bytes)
    }

    private data class ReplicaStats(val fileCount: Long, val byteCount: Long)

    /**
     * Free/total bytes of the filesystem holding the hub's data dir (and thus
     * the H3 content replica under it) — Home's storage tile figures. `0/0`
     * when the filesystem can't be probed (consumers treat that as "unknown",
     * never as a zero-capacity disk).
     */
    private fun diskStats(): DiskStats {
        val probe = if (contentRoot.exists()) contentRoot else runtime.dataRoot
        return DiskStats(freeBytes = probe.freeSpace, totalBytes = probe.totalSpace)
    }

    private data class DiskStats(val freeBytes: Long, val totalBytes: Long)

    /**
     * The change-gate comparison unit: everything on the status row EXCEPT
     * the always-moving timestamps (`freshAsOf`, `lastBackupPassAt`) — those
     * ride along whenever a write happens but never cause one by themselves.
     * Disk free space is compared **coarsely** ([DISK_FREE_CHANGE_QUANTUM_BYTES])
     * for the same reason: unrelated processes nibble at the filesystem
     * constantly, and a byte-exact comparison would turn every refresh tick
     * into a sync write. The published row still carries the exact figures.
     */
    private data class StatusSnapshot(
        val deviceId: String,
        val name: String,
        val version: String,
        val startedAtEpochMs: Long,
        val replica: ReplicaStats,
        val diskTotalBytes: Long,
        val diskFreeCoarseBytes: Long,
        val paired: Boolean,
        val lastCommandId: String,
        val lastCommandOutcome: String,
    )

    companion object {
        /** Bounded staleness for an otherwise-unchanged status row (1 h). */
        const val DEFAULT_HEARTBEAT_MS = 60L * 60 * 1000

        /** Publisher tick — how often the change gate re-evaluates (1 min). */
        const val DEFAULT_REFRESH_TICK_MS = 60L * 1000

        /**
         * Change-gate granularity for disk free space (100 MB — the display
         * granularity of Home's "n.n GB" tile). Movements smaller than this
         * don't publish by themselves; any real write carries exact figures.
         */
        const val DISK_FREE_CHANGE_QUANTUM_BYTES = 100_000_000L
    }
}

/**
 * Pure at-most-once gate for [HubCommand]s — separated from the agent so the
 * decision table is unit-testable without a runtime.
 */
object HubCommandGate {

    /** Ignore commands older than this (15 min) — the restart-storm guard. */
    const val DEFAULT_STALE_WINDOW_MS = 15L * 60 * 1000

    enum class Decision {
        /** Execute now (and persist the id as consumed first). */
        Execute,

        /** Addressed to a different device — not ours, ignore silently. */
        NotOurs,

        /** Already consumed (journal hit) — the idempotence no-op. */
        AlreadyConsumed,

        /** Older than the sanity window — backlog replay guard, ignore. */
        Stale,
    }

    fun decide(
        targetDeviceId: String,
        issuedAtEpochMs: Long,
        ownDeviceId: String,
        nowEpochMs: Long,
        alreadyConsumed: Boolean,
        staleWindowMs: Long = DEFAULT_STALE_WINDOW_MS,
    ): Decision = when {
        targetDeviceId != ownDeviceId -> Decision.NotOurs
        alreadyConsumed -> Decision.AlreadyConsumed
        nowEpochMs - issuedAtEpochMs > staleWindowMs -> Decision.Stale
        else -> Decision.Execute
    }
}

/**
 * Durable consumed-command journal: one command id per line, appended (and
 * flushed) *before* execution so a re-delivered or backlog-replayed command
 * row is a no-op across hub restarts. Lives in the hub's data dir beside the
 * other durable state. Append-only — command volume is human-gesture-scale.
 */
class ConsumedCommandJournal(private val file: File) {

    private val consumed: MutableSet<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() }.toMutableSet()
        else mutableSetOf()

    @Synchronized
    fun isConsumed(commandId: String): Boolean = commandId in consumed

    @Synchronized
    fun markConsumed(commandId: String) {
        if (!consumed.add(commandId)) return
        file.parentFile?.mkdirs()
        file.appendText(commandId + "\n")
    }
}
