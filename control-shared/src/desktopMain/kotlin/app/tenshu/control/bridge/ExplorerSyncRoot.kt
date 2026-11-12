package app.tenshu.control.bridge

import app.tenshu.windows.CloudFilesHResult
import app.tenshu.windows.WindowsAccount
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsSyncRootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuClient
import java.util.Locale
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.common.util.FileContentChunkSizeBytes
import org.tenshu.core.common.util.IncrementalSha256
import org.tenshu.core.common.util.toHexLower
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.Data
import org.tenshu.core.fold
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.closeContentStream
import org.tenshu.sync.openContentStream
import org.tenshu.sync.readContentRange
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Drive in File Explorer (desktop-windows-port.md §7.8, X1): a Cloud Files sync root that Control registers,
 * serves and keeps in step with the realm. Windows only; one per Control.
 *
 * - **Shape**: every realm row becomes a placeholder — online-only until something opens it, then downloaded
 *   through the content stream, ranged, never whole in memory.
 * - **In step**: a pass re-lists the realm and applies [ExplorerMirror.plan]. Passes run at start, whenever the
 *   realm listing emits, and every [REPAIR_INTERVAL_MILLIS] (which also undoes local deletions).
 * - **Read-only until X3**: files are created `READONLY` and nothing local is uploaded. ⚠⚠ The user's own
 *   files in the folder are never touched — they would be the only copy.
 *
 * ⚠ CfAPI rule (X0): while connected, only this process may create placeholders, and with nothing connected
 * opening an online-only file fails at once. So this lives in the resident Control, never in Drive.
 */
internal class ExplorerSyncRoot(
    private val koin: Koin,
    private val root: Path,
    private val log: (String) -> Unit,
    journalFile: java.io.File? = null,
    /** Called after every pass with what the folder is doing (X4); from the pass's thread. */
    private val onStatus: (ExplorerStatus) -> Unit = {},
) {
    /** The realm version last put at each path ([ExplorerJournal]); what tells an edit from a conflict (§7.10). */
    private val journal = ExplorerJournal(journalFile)

    /** Writes run here: each holds a file unshared while it waits on the realm, which must not be an IO-pool thread. */
    private val writeThread = Executors.newSingleThreadExecutor { r -> Thread(r, "explorer-write").apply { isDaemon = true } }
    private val writeDispatcher = writeThread.asCoroutineDispatcher()

    /** When writing an entry last failed: not tried again for [WRITE_RETRY_MILLIS]. */
    private val writeFailedAt = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val fetchers = Executors.newFixedThreadPool(FETCH_THREADS) { r ->
        Thread(r, "explorer-fetch").apply { isDaemon = true }
    }
    /**
     * Transfers being served, and the byte ranges of them Windows cancelled; only an active one can be cancelled.
     * ⚠ A cancel may name only part of a request — the rest is still wanted by other readers — so it is a range, not a
     * flag on the whole transfer.
     */
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val cancelled = ConcurrentHashMap<Long, MutableList<LongRange>>()

    /** When a pass first proposed a mass removal that has not been confirmed yet ([MASS_REMOVAL_CONFIRM_MILLIS]). */
    @Volatile private var massRemovalSince: Long? = null


    @Volatile private var connection: WindowsCloudFiles.Connection? = null
    @Volatile private var watcher: WatchService? = null
    private val pins = Channel<Unit>(Channel.CONFLATED)
    private val pinThread = Executors.newSingleThreadExecutor { r -> Thread(r, "explorer-pin").apply { isDaemon = true } }
    private var jobs: List<Job> = emptyList()

    /** Registers (idempotent), connects and starts keeping the folder in step. False when Windows refused. */
    fun start(): Boolean {
        val id = syncRootId() ?: return false.also { log("explorer: no user SID; not registering") }
        sweepStale(id)
        Files.createDirectories(root)
        val outcome = WindowsSyncRootManager.register(
            WindowsSyncRootManager.Info(
                id = id,
                path = root.toString(),
                displayName = DISPLAY_NAME,
                iconResource = iconResource(),
                version = PROVIDER_VERSION,
                hydration = WindowsSyncRootManager.HYDRATION_FULL,
                population = WindowsSyncRootManager.POPULATION_ALWAYS_FULL,
                providerId = PROVIDER_ID,
            ),
        )
        log("explorer: Register $id at $root -> $outcome")
        if (outcome?.ok != true) return false

        val connect = WindowsCloudFiles.connect(
            root,
            handler,
            WindowsCloudFiles.CONNECT_FLAG_REQUIRE_PROCESS_INFO or
                WindowsCloudFiles.CONNECT_FLAG_REQUIRE_FULL_FILE_PATH or
                // ⚠ Our own scans must not download: a read inside Control would call back into Control.
                WindowsCloudFiles.CONNECT_FLAG_BLOCK_SELF_IMPLICIT_HYDRATION,
        )
        when (connect) {
            is WindowsCloudFiles.ConnectResult.Connected -> connection = connect.connection
            is WindowsCloudFiles.ConnectResult.Failed -> {
                log("explorer: CfConnectSyncRoot -> ${CloudFilesHResult.describe(connect.hresult)}")
                return false
            }
            null -> return false
        }
        log("explorer: connected")

        jobs = listOf(
            scope.launch { runPasses() },
            scope.launch { watchRealm() },
            scope.launch {
                while (isActive) {
                    passes.trySend(Unit)
                    pins.trySend(Unit)
                    delay(REPAIR_INTERVAL_MILLIS)
                }
            },
            scope.launch {
                while (isActive) {
                    pins.receive()
                    delay(PIN_DEBOUNCE_MILLIS)
                    // One at a time, on the pin thread; a pass already queued absorbs the signals behind it.
                    runCatching { pinThread.submit { runCatching { pinPass() }.onFailure { log("explorer: pin pass failed: $it") } }.get() }
                }
            },
        )
        // Made here, not in its thread, so a stop() right after start() always has a watcher to close.
        val watcher = root.fileSystem.newWatchService().also { this.watcher = it }
        Thread({ runCatching { watchFolder(watcher) }.onFailure { log("explorer: folder watch stopped: $it") } }, "explorer-watch")
            .apply { isDaemon = true }.start()
        return true
    }

    /**
     * Stops serving; the registration and the placeholders stay (Control quitting). ⚠ Waits for a pass in flight
     * (bounded): a pass still creating placeholders after [remove] has deleted them would leave orphans behind.
     */
    fun stop() {
        runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { scope.coroutineContext[Job]?.cancelAndJoin() } }
        runCatching { watcher?.close() }
        // Disconnecting fails any download a pin pass is still waiting on, so the pin thread can finish.
        connection?.disconnect()?.let { log("explorer: disconnected -> ${CloudFilesHResult.describe(it)}") }
        connection = null
        pinThread.shutdownNow()
        pinThread.awaitTermination(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        writeThread.shutdown()
        writeThread.awaitTermination(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        fetchers.shutdownNow()
        journal.save()
    }

    /** Turns the integration off: [stop], then [removeAll]. Answers whether the folder is clean. */
    fun remove(): Boolean {
        stop()
        return removeAll(root, log)
    }

    private suspend fun watchRealm() {
        while (scope.isActive) {
            runCatching {
                koin.fileSystem().root.files.traverse().select { itemProjection() }.flow().collect { passes.trySend(Unit) }
            }.onFailure {
                // Stopping cancels the listing: that is not a failure to report.
                if (it is kotlinx.coroutines.CancellationException) throw it
                log("explorer: realm listing stopped ($it); retrying")
            }
            delay(REPAIR_INTERVAL_MILLIS)
        }
    }

    private suspend fun runPasses() {
        runCatching { makeOursWritable() }.onFailure { log("explorer: could not make placeholders writable: $it") }
        while (scope.isActive) {
            passes.receive()
            delay(DEBOUNCE_MILLIS)
            runCatching { pass() }.onFailure { log("explorer: pass failed: $it") }
        }
    }

    private suspend fun pass() {
        if (!root.isDirectory()) {
            log("explorer: $root is gone; restart Control or turn the integration off and on")
            return
        }
        val remote = remoteRows()
        if (remote == null) {
            onStatus(ExplorerStatus.of(emptySet(), emptyMap(), unreachable = true))
            return
        }
        val local = localEntries()
        try {
            passOver(remote, local)
        } finally {
            publish(remote, local)
        }
    }

    /**
     * The Settings line (X4): what the folder holds that the realm does not have yet, and what of it cannot get there.
     * Taken from the pass's own view, so it lags a write by one pass — the pass that follows every write.
     */
    private fun publish(remote: List<ExplorerMirror.Remote>, local: List<ExplorerMirror.Local>) {
        val pending = HashSet<String>()
        // Long.MAX_VALUE: everything counts, settled or still being written.
        ExplorerWrites.candidates(remote, local, journal.snapshot(), Long.MAX_VALUE).mapTo(pending) { it.relative.lowercase(Locale.ROOT) }
        pendingOps.mapTo(pending) { it.relative.lowercase(Locale.ROOT) }
        val failures = HashMap<String, ExplorerStatus.Reason>()
        writeFailedAt.keys.forEach { failures[it.lowercase(Locale.ROOT)] = ExplorerStatus.Reason.RETRYING }
        tooLarge.forEach { failures[it] = ExplorerStatus.Reason.TOO_LARGE }
        tooLarge.retainAll(pending)
        onStatus(ExplorerStatus.of(pending, failures, unreachable = false))
    }

    /** Paths (lower case) over the upload limit: not retried as failures, shown as too large. */
    private val tooLarge = ConcurrentHashMap.newKeySet<String>()

    private suspend fun passOver(remote: List<ExplorerMirror.Remote>, local: List<ExplorerMirror.Local>) {
        // Deletes and renames made in the folder (X3b): planned first, because until they reach the realm the mirror
        // would undo them and the write path would take a renamed file for a new one.
        pendingOps = pendingOps + generateSequence { notices.poll() }.toList()
        val now0 = System.currentTimeMillis()
        appliedGuards.removeAll { guard -> now0 > guard.until || remote.none { it.path.equals(guard.gone, ignoreCase = true) } }
        val localPlan = ExplorerLocalOps.plan(pendingOps, remote, local, now0)
        val guarded = localPlan.guarded + appliedGuards.flatMap { it.keys }
        fun isGuarded(relative: String) = ExplorerLocalOps.isGuarded(relative.lowercase(Locale.ROOT), guarded)
        val planned = ExplorerMirror.plan(remote, local).filterNot { isGuarded(it.relative) }
        // ⚠ A listing that comes back empty or partial for a moment (a cold store, a reset) would take every
        // downloaded and pinned copy with it. So a mass removal must be proposed again a while later to be applied —
        // and nothing is written meanwhile: against a partial listing, an edited file looks new or in conflict.
        if (ExplorerMirror.isMassRemoval(planned, local)) {
            val now = System.currentTimeMillis()
            val since = massRemovalSince ?: now.also { massRemovalSince = it }
            if (now - since < MASS_REMOVAL_CONFIRM_MILLIS) {
                log("explorer: the realm listing would remove most of the folder (${remote.size} rows); waiting for it to say so again")
                return
            }
        } else {
            massRemovalSince = null
        }
        if (localPlan.steps.isNotEmpty() || localPlan.dropped.isNotEmpty()) {
            localPlan.dropped.forEach { log("explorer: not carried to the realm (the folder says otherwise): $it") }
            val applied = withContext(writeDispatcher) { applyLocalOps(localPlan, remote) }
            journal.save()
            if (applied > 0) {
                passes.trySend(Unit)
                return
            }
        }
        // Writes first (X3, §7.10): what the folder holds that the realm does not. A pass that wrote anything ends
        // there and asks for another, so the mirror below always plans against a realm that includes it.
        val now = System.currentTimeMillis()
        val writes = ExplorerWrites.candidates(remote, local, journal.snapshot(), now).filterNot { isGuarded(it.relative) }
        // Something still settling is looked at again once it has.
        ExplorerWrites.untilSettled(local, now)?.let { wait ->
            scope.launch {
                delay(wait + SETTLE_MARGIN_MILLIS)
                passes.trySend(Unit)
            }
        }
        if (writes.isNotEmpty()) {
            val wrote = withContext(writeDispatcher) { applyWrites(writes, remote, local) }
            journal.save()
            if (wrote > 0) {
                passes.trySend(Unit)
                return
            }
        }
        val actions = planned
        massRemovalSince = null
        if (actions.isEmpty()) return
        var failed = 0
        for (action in actions) {
            if (!scope.isActive) return
            val ok = when (action) {
                is ExplorerMirror.Action.Remove -> deletePlaceholder(root.resolve(action.relative))
                is ExplorerMirror.Action.Create -> create(action)
                is ExplorerMirror.Action.Adopt -> adopt(action)
            }
            if (!ok) failed++
        }
        log("explorer: pass applied ${actions.size - failed} of ${actions.size} changes")
        journal.save()
        pins.trySend(Unit)
    }

    /**
     * Carries [plan]'s steps to the realm, on the write thread; answers how many did. Every notice of the plan is done
     * with afterwards except the ones still [ExplorerLocalOps.Plan.waiting] and a failed step's, tried again by the next
     * pass — given up after [OP_GIVE_UP_MILLIS], when the mirror puts the folder back as the realm has it.
     */
    private fun applyLocalOps(plan: ExplorerLocalOps.Plan, remote: List<ExplorerMirror.Remote>): Int {
        val now = System.currentTimeMillis()
        var applied = 0
        val retry = ArrayList<ExplorerLocalOps.Op>()
        var later = plan.waiting
        for ((i, step) in plan.steps.withIndex()) {
            if (!scope.isActive) {
                retry += plan.steps.drop(i).map { it.op }
                break
            }
            val ok = runCatching { runBlocking(scope.coroutineContext[Job] ?: kotlin.coroutines.EmptyCoroutineContext) { applyStep(step, remote) } }
                .onFailure { log("explorer: ${step.op} failed: $it") }
                .getOrDefault(false)
            if (ok) {
                applied++
                if (step is ExplorerLocalOps.Step.Move && step.isFolder) {
                    // What is left was planned against the old paths: rebased onto the new ones and planned again.
                    later = (later + plan.steps.drop(i + 1).map { it.op }).map { ExplorerLocalOps.rebased(it, step.from, step.to) }
                    break
                }
            } else if (now - step.op.at < OP_GIVE_UP_MILLIS) {
                retry += step.op
            } else {
                log("explorer: gave up on ${step.op}; the folder is put back as the realm has it")
            }
        }
        pendingOps = later + retry
        return applied
    }

    private suspend fun applyStep(step: ExplorerLocalOps.Step, remote: List<ExplorerMirror.Remote>): Boolean {
        val fs = koin.fileSystem()
        when (step) {
            is ExplorerLocalOps.Step.Trash -> {
                ensureRealmFolder(ExplorerLocalOps.TRASH)
                val contentId = if (step.isFolder) null else runCatching { fs.readEntityItem(step.realmPath).contentId }.getOrNull()
                val to = ExplorerLocalOps.trashPathFor(
                    step.realmPath, contentId, System.currentTimeMillis(),
                    remote.filter { ExplorerLocalOps.isTrashed(it.path) }.mapTo(HashSet()) { it.path },
                )
                fs.moveFile(step.realmPath, to).orThrowWrite("trash", "${step.realmPath} -> $to")
                log("explorer: deleted in Explorer: '${step.realmPath}' moved to '$to'")
                forgetJournalUnder(step.op.relative)
                appliedGuards += AppliedGuard(setOf(key(step.op.relative), key(step.realmPath.trim('/'))), step.realmPath)
            }
            is ExplorerLocalOps.Step.Move -> {
                ensureRealmFolder(step.to.substringBeforeLast('/').ifEmpty { "/" })
                fs.moveFile(step.from, step.to).orThrowWrite("move", "${step.from} -> ${step.to}")
                log("explorer: renamed in Explorer: '${step.from}' -> '${step.to}'")
                val target = step.op.target!!
                reidentify(target, step.from, step.to)
                appliedGuards += AppliedGuard(
                    setOf(key(step.op.relative), key(target), key(step.from.trim('/'))), step.from,
                )
            }
        }
        return true
    }

    /**
     * After the realm moved [from] to [to]: our placeholders at [target] (and below it, for a folder) name the new
     * paths, so fetches keep working and the mirror sees nothing to replace — a downloaded copy stays downloaded. The
     * journal follows. A placeholder that cannot be updated now is left to the mirror, which replaces it.
     */
    private fun reidentify(target: String, from: String, to: String) {
        val top = root.resolve(target)
        val entries = if (Files.isDirectory(top, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(top).use { s -> s.toList() }
        } else {
            listOf(top)
        }
        for (path in entries) {
            val ours = ourPlaceholder(path) ?: continue
            val rebased = ExplorerLocalOps.rebased(
                ExplorerLocalOps.Op("", null, ours.identity.path, ours.identity.contentVersion, false, 0L), from, to,
            ).identityPath
            if (rebased == ours.identity.path) continue
            val hr = WindowsCloudFiles.updateIdentity(path, ExplorerIdentity(rebased, ours.identity.contentVersion).encode())
            if (hr == null || !CloudFilesHResult.succeeded(hr)) {
                log("explorer: re-identify '${root.relativize(path)}' -> ${hr?.let(CloudFilesHResult::describe)}")
            }
        }
        val oldPrefix = from.trim('/')
        for ((relative, version) in journal.snapshot()) {
            val moved = when {
                relative == key(oldPrefix) -> target
                relative.startsWith(key(oldPrefix) + "/") -> target + relative.substring(oldPrefix.length)
                else -> continue
            }
            val hash = journal.hashOf(relative)
            journal.forget(relative)
            journal.record(moved, version, hash)
        }
    }

    private fun forgetJournalUnder(relative: String) {
        val prefix = key(relative)
        journal.snapshot().keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach(journal::forget)
    }

    private fun key(relative: String) = relative.trim('/').lowercase(Locale.ROOT)

    /** A step applied whose effect the listing does not show yet: its paths stay guarded until [gone] has gone. */
    private class AppliedGuard(val keys: Set<String>, val gone: String) {
        val until = System.currentTimeMillis() + APPLIED_GUARD_MILLIS
    }

    /** Notices from Windows, noted on a CfAPI thread and taken by the next pass. */
    private val notices = java.util.concurrent.ConcurrentLinkedQueue<ExplorerLocalOps.Op>()

    /** Notices not carried to the realm yet. The pass's alone. */
    private var pendingOps: List<ExplorerLocalOps.Op> = emptyList()
    private val appliedGuards = ArrayList<AppliedGuard>()
    private val ownPid = ProcessHandle.current().pid()

    private fun note(path: Path?, target: String?, identity: ByteArray, isFolder: Boolean) {
        val relative = path?.let(::relativeOf) ?: return
        val id = ExplorerIdentity.decode(identity) ?: return
        notices += ExplorerLocalOps.Op(relative, target, id.path, id.contentVersion, isFolder, System.currentTimeMillis())
        scope.launch {
            delay(ExplorerLocalOps.GRACE_MILLIS + SETTLE_MARGIN_MILLIS)
            passes.trySend(Unit)
        }
    }

    /** [path] inside the folder, `/`-separated; `null` outside it (or the folder itself). */
    private fun relativeOf(path: Path): String? =
        if (path.startsWith(root) && path != root) root.relativize(path).joinToString("/") else null

    private suspend fun remoteRows(): List<ExplorerMirror.Remote>? =
        koin.fileSystem().root.files.traverse().select { itemProjection() }.flow().first().fold(
            onSuccess = { list ->
                list.toList().map(::ProjectedFile).map {
                    ExplorerMirror.Remote(
                        path = it.path,
                        isFolder = it.isFolder,
                        size = if (it.isFolder) 0 else it.size,
                        modifiedAtMillis = it.modifiedAtMillis ?: it.createdAtMillis ?: 0L,
                        contentVersion = it.contentVersion,
                        contentHash = it.contentHash,
                    )
                }
            },
            onFailure = {
                log("explorer: could not list the realm: $it")
                null
            },
        )

    private fun localEntries(): List<ExplorerMirror.Local> =
        Files.walk(root).use { s -> s.filter { it != root }.toList() }.mapNotNull { path ->
            val attributes = runCatching {
                Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return@mapNotNull null
            val bits = WindowsCloudFiles.fileAttributes(path) ?: 0
            val ours = ourPlaceholder(path)
            ExplorerMirror.Local(
                relative = root.relativize(path).joinToString("/"),
                isFolder = attributes.isDirectory,
                isPlaceholder = ours != null && (attributes.isDirectory || ours.inSync),
                ours = ours != null,
                hidden = attributes.isHidden || attributes.isSystem,
                contentVersion = ours?.identity?.contentVersion,
                identityPath = ours?.identity?.path,
                size = if (attributes.isDirectory) 0 else attributes.size(),
                modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                pin = when {
                    bits and WindowsCloudFiles.FILE_ATTRIBUTE_PINNED != 0 -> ExplorerMirror.Pin.PINNED
                    bits and WindowsCloudFiles.FILE_ATTRIBUTE_UNPINNED != 0 -> ExplorerMirror.Pin.UNPINNED
                    else -> ExplorerMirror.Pin.NONE
                },
                online = bits and WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS != 0,
            )
        }

    private fun create(action: ExplorerMirror.Action.Create): Boolean {
        val row = action.remote
        val identity = ExplorerIdentity(row.path, row.contentVersion).encode()
        if (identity.size > MAX_IDENTITY_BYTES) {
            log("explorer: '${row.path}' is too long to show")
            return false
        }
        val parent = action.relative.substringBeforeLast('/', "").let { if (it.isEmpty()) root else root.resolve(it) }
        val hr = WindowsCloudFiles.createPlaceholder(
            parent,
            WindowsCloudFiles.Placeholder(
                relativeName = action.relative.substringAfterLast('/'),
                size = row.size,
                identity = identity,
                modifiedAtMillis = row.modifiedAtMillis,
                flags = WindowsCloudFiles.PLACEHOLDER_FLAG_MARK_IN_SYNC or
                    // Population is ours, all of it: Windows never asks what a folder holds.
                    (if (row.isFolder) WindowsCloudFiles.PLACEHOLDER_FLAG_DISABLE_ON_DEMAND_POPULATION else 0),
                directory = row.isFolder,
            ),
        ) ?: return false
        if (!CloudFilesHResult.succeeded(hr)) {
            log("explorer: create '${action.relative}' -> ${CloudFilesHResult.describe(hr)}")
            return false
        }
        if (!row.isFolder) journal.record(action.relative, row.contentVersion, row.contentHash)
        // A replaced file keeps its mark: kept stays kept (the pin pass downloads the new version), and a free mark
        // standing inside a kept folder stays, or the new version would follow the folder and download.
        val state = when (action.pin) {
            ExplorerMirror.Pin.PINNED -> WindowsCloudFiles.PIN_STATE_PINNED
            ExplorerMirror.Pin.UNPINNED -> WindowsCloudFiles.PIN_STATE_UNPINNED
            ExplorerMirror.Pin.NONE -> null
        }
        if (state != null) {
            val pin = WindowsCloudFiles.setPinState(root.resolve(action.relative), state)
            if (pin != null && !CloudFilesHResult.succeeded(pin)) log("explorer: re-mark '${action.relative}' -> ${CloudFilesHResult.describe(pin)}")
        }
        return true
    }

    /** When converting a folder last failed: not tried again for [WRITE_RETRY_MILLIS]. */
    private val adoptFailedAt = ConcurrentHashMap<String, Long>()

    /** Makes an ordinary folder at a realm folder's path ours ([ExplorerMirror.Action.Adopt]); files are [write]'s. */
    private fun adopt(action: ExplorerMirror.Action.Adopt): Boolean {
        val row = action.remote
        adoptFailedAt[action.relative]?.let { if (System.currentTimeMillis() - it < WRITE_RETRY_MILLIS) return false }
        val hr = WindowsCloudFiles.convertToPlaceholder(
            root.resolve(action.relative), ExplorerIdentity(row.path, row.contentVersion).encode(), directory = true,
        ) ?: return false
        log("explorer: adopt '${action.relative}' -> ${CloudFilesHResult.describe(hr)}")
        if (CloudFilesHResult.succeeded(hr)) adoptFailedAt.remove(action.relative)
        else adoptFailedAt[action.relative] = System.currentTimeMillis()
        return CloudFilesHResult.succeeded(hr)
    }

    /** Applies [writes] on the write thread; answers how many changed the realm or the folder. */
    private fun applyWrites(
        writes: List<ExplorerWrites.Candidate>,
        remote: List<ExplorerMirror.Remote>,
        local: List<ExplorerMirror.Local>,
    ): Int {
        var done = 0
        for (candidate in writes) {
            if (!scope.isActive) break
            val now = System.currentTimeMillis()
            val failedAt = writeFailedAt[candidate.relative]
            if (failedAt != null && now - failedAt < WRITE_RETRY_MILLIS) continue
            val ok = runCatching { write(candidate, remote, local) }
                .onFailure { log("explorer: write '${candidate.relative}' failed: $it") }
                .getOrDefault(false)
            if (ok) {
                done++
                writeFailedAt.remove(candidate.relative)
            } else {
                writeFailedAt[candidate.relative] = now
            }
        }
        return done
    }

    /**
     * One write ([ExplorerWrites.Decision]). ⚠⚠ A file is held **unshared** from the moment its bytes are read until it
     * has been converted, re-identified or set to be deleted: what reaches the realm is exactly what was checked, and a
     * conflict's local file is only replaced by the realm's version once its bytes are safely in the realm.
     */
    private fun write(
        candidate: ExplorerWrites.Candidate,
        remote: List<ExplorerMirror.Remote>,
        local: List<ExplorerMirror.Local>,
    ): Boolean {
        val path = root.resolve(candidate.relative)
        // The realm's spelling, not the folder's: realm paths are case-sensitive, and a folder renamed by case elsewhere
        // keeps its old spelling here.
        val realmPath = candidate.row?.path ?: canonicalRealmPath(candidate.relative, remote)
        val parent = realmPath.substringBeforeLast('/').ifEmpty { "/" }
        val name = realmPath.substringAfterLast('/')
        val fs = koin.fileSystem()

        if (candidate.kind == ExplorerWrites.Kind.NEW_FOLDER) {
            runBlocking { ensureRealmFolder(realmPath) }
            log("explorer: new folder '${candidate.relative}' -> created in the realm")
            return true // the mirror adopts it next pass
        }
        val edited = candidate.kind == ExplorerWrites.Kind.EDITED_PLACEHOLDER
        if (edited && candidate.online) {
            // No local copy, so no bytes changed — only its metadata (an attribute). Mark it in sync as it was.
            val identity = ExplorerIdentity(candidate.row?.path ?: realmPath, candidate.base ?: return false).encode()
            val hr = WindowsCloudFiles.withUnsharedFile(path, asItself = false) { it.updateIdentity(identity) }
            return CloudFilesHResult.succeeded(hr)
        }

        val attributes = Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.size() > MAX_UPLOAD_BYTES) {
            log("explorer: '${candidate.relative}' is ${attributes.size()} bytes; uploads are limited to $MAX_UPLOAD_BYTES")
            tooLarge += candidate.relative.lowercase(Locale.ROOT)
            return false
        }
        val modifiedAt = attributes.lastModifiedTime().toMillis()
        // Read-only refuses the write access the conversion needs. Cleared for the write; ours afterwards stays writable.
        if (attributes.isReadOnly) Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
        var restoreReadOnly = attributes.isReadOnly
        try {
            return WindowsCloudFiles.withUnsharedFile(path, asItself = !edited) { file ->
                val bytes = readAll(file, attributes.size())
                val hash = wholeFileHashOf(bytes)
                // Cancelled with the scope: stop() does not wait out an upload it is about to make irrelevant.
                runBlocking(scope.coroutineContext[Job] ?: kotlin.coroutines.EmptyCoroutineContext) {
                    // ⚠ Decided against the realm as it is now, not the pass's listing: a version another device
                    // synced since (earlier writes in the pass widen that gap) must turn REPLACE into a conflict
                    // copy. `replaceFile` has no expected-version precondition, so the window left is this re-read
                    // to the replace below.
                    val current = candidate.copy(row = readRowOrNull(realmPath))
                    val decision = ExplorerWrites.decide(current, sameBytes = current.row?.contentHash == hash)
                    when (decision) {
                        ExplorerWrites.Decision.ALREADY_THERE -> markOurs(file, current, current.row!!)
                        ExplorerWrites.Decision.UPLOAD_NEW -> {
                            ensureRealmFolder(parent)
                            fs.uploadFile(parent, name, bytes, sourceModifiedAt = modifiedAt).orThrowWrite("upload", realmPath)
                            log("explorer: uploaded '${candidate.relative}' (${bytes.size} bytes)")
                            markUploaded(file, current, realmPath, hash)
                        }
                        ExplorerWrites.Decision.REPLACE -> {
                            fs.replaceFile(parent, name, bytes).orThrowWrite("replace", realmPath)
                            log("explorer: uploaded the edit of '${candidate.relative}' (${bytes.size} bytes)")
                            markUploaded(file, current, realmPath, hash)
                        }
                        ExplorerWrites.Decision.CONFLICT_COPY -> {
                            // ⚠ Idempotent: when handing the path back failed after the copy went up, the retry finds
                            // that copy (same bytes, a conflict name of this file) and only hands the path back.
                            val existing = remote.firstOrNull {
                                !it.isFolder && it.contentHash == hash &&
                                    it.path.substringBeforeLast('/').ifEmpty { "/" } == parent &&
                                    ExplorerWrites.isConflictNameOf(it.path.substringAfterLast('/'), name, computerName())
                            }
                            val taken = remote.filter { it.path.substringBeforeLast('/').ifEmpty { "/" } == parent }
                                .map { it.path.substringAfterLast('/') } +
                                local.filter { it.relative.substringBeforeLast('/', "") == candidate.relative.substringBeforeLast('/', "") }
                                    .map { it.relative.substringAfterLast('/') }
                            val copy = existing?.path?.substringAfterLast('/')
                                ?: ExplorerWrites.conflictName(name, computerName(), taken.toSet()).also { copy ->
                                    fs.uploadFile(parent, copy, bytes, sourceModifiedAt = modifiedAt)
                                        .orThrowWrite("conflict copy", "$parent/$copy")
                                }
                            // Only now, with the bytes safely in the realm: the realm's version takes the path back.
                            check(CloudFilesHResult.succeeded(file.deleteOnClose())) { "could not hand the path back" }
                            restoreReadOnly = false
                            journal.forget(candidate.relative)
                            journal.save()
                            log("explorer: '${candidate.relative}' changed elsewhere too; this edit is kept as '$copy'")
                            true
                        }
                    }
                }
            }
        } finally {
            if (restoreReadOnly && path.exists(LinkOption.NOFOLLOW_LINKS) && ourPlaceholder(path) == null) {
                // Still the user's ordinary file: put its attribute back as it was.
                runCatching { Files.setAttribute(path, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS) }
            }
        }
    }

    /** Makes the held file one of ours for [row]: converts an ordinary file, re-identifies an edited placeholder. */
    private fun markOurs(file: WindowsCloudFiles.UnsharedFile, candidate: ExplorerWrites.Candidate, row: ExplorerMirror.Remote): Boolean {
        val identity = ExplorerIdentity(row.path, row.contentVersion).encode()
        val hr = if (candidate.kind == ExplorerWrites.Kind.EDITED_PLACEHOLDER) file.updateIdentity(identity) else file.convert(identity)
        log("explorer: in sync '${candidate.relative}' -> ${CloudFilesHResult.describe(hr)}")
        if (CloudFilesHResult.succeeded(hr)) {
            journal.record(candidate.relative, row.contentVersion, row.contentHash)
            journal.save()
        }
        return CloudFilesHResult.succeeded(hr)
    }

    /**
     * [markOurs] for what was just uploaded — ⚠ only if the realm's row still holds these bytes. A version another device
     * synced between the upload and this read would otherwise give the local file its identity while it keeps our bytes,
     * and the mirror would never replace them. Not marked, the next pass sees the row moved on: a conflict copy.
     */
    private suspend fun markUploaded(
        file: WindowsCloudFiles.UnsharedFile,
        candidate: ExplorerWrites.Candidate,
        realmPath: String,
        hash: String,
    ): Boolean {
        val row = readRow(realmPath)
        if (row.contentHash != hash) {
            log("explorer: '${candidate.relative}' changed in the realm right after the upload; looked at again next pass")
            return false
        }
        return markOurs(file, candidate, row)
    }

    private suspend fun readRowOrNull(realmPath: String): ExplorerMirror.Remote? = try {
        readRow(realmPath)
    } catch (e: org.tenshu.core.common.proxy.BridgeException) {
        if (e.code == "not_found") null else throw e
    }

    /**
     * [relative]'s realm path, each existing folder of it in the realm's own spelling ([remote] rows matched as NTFS
     * matches names); what the realm does not have yet keeps the folder's spelling.
     */
    private fun canonicalRealmPath(relative: String, remote: List<ExplorerMirror.Remote>): String {
        val folders = remote.filter { it.isFolder }.associateBy { it.path.lowercase(Locale.ROOT) }
        val segments = relative.split('/')
        var path = ""
        for ((i, segment) in segments.withIndex()) {
            val next = "$path/$segment"
            path = if (i < segments.lastIndex) folders[next.lowercase(Locale.ROOT)]?.path ?: next else next
        }
        return path
    }

    private fun readAll(file: WindowsCloudFiles.UnsharedFile, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = file.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun readRow(realmPath: String): ExplorerMirror.Remote {
        val item = koin.fileSystem().readEntityItem(realmPath)
        return ExplorerMirror.Remote(
            path = item.path, isFolder = item.isFolder, size = item.size,
            modifiedAtMillis = item.modifiedAtMillis ?: item.createdAtMillis ?: 0L,
            contentVersion = item.contentVersion, contentHash = item.contentHash,
        )
    }

    /** Creates every folder of [realmPath] the realm does not have yet. */
    private suspend fun ensureRealmFolder(realmPath: String) {
        if (realmPath == "/" || realmPath.isEmpty()) return
        var prefix = ""
        for (segment in realmPath.trim('/').split('/')) {
            prefix = "$prefix/$segment"
            koin.fileSystem().createFolder(prefix).fold(
                onSuccess = {},
                onFailure = { if (it !is org.tenshu.core.file.system.AlreadyExistsError) throw IllegalStateException("createFolder $prefix: $it") },
            )
        }
    }

    private fun computerName(): String = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: "this PC"

    /**
     * "Always keep on this device" / "Free up space": Explorer only marks the files, the provider acts. Runs on
     * its own thread — a download blocks until our fetch threads have served it.
     */
    private fun pinPass() {
        if (!root.isDirectory()) return
        val rootBits = WindowsCloudFiles.fileAttributes(root) ?: 0
        val rootPin = when {
            rootBits and WindowsCloudFiles.FILE_ATTRIBUTE_PINNED != 0 -> ExplorerMirror.Pin.PINNED
            rootBits and WindowsCloudFiles.FILE_ATTRIBUTE_UNPINNED != 0 -> ExplorerMirror.Pin.UNPINNED
            else -> ExplorerMirror.Pin.NONE
        }
        val plan = ExplorerMirror.copies(localEntries(), rootPin)
        val failedDrops = HashSet<String>()
        for ((relative, copy) in plan.copies) {
            val path = root.resolve(relative)
            val hr = when (copy) {
                ExplorerMirror.Copy.DOWNLOAD -> WindowsCloudFiles.hydrate(path)
                ExplorerMirror.Copy.DROP -> WindowsCloudFiles.dehydrate(path)
            } ?: continue
            if (copy == ExplorerMirror.Copy.DROP && !CloudFilesHResult.succeeded(hr)) failedDrops += relative
            log("explorer: ${copy.name.lowercase()} '$relative' -> ${CloudFilesHResult.describe(hr)}")
        }
        // "Free up space" carried out: its mark goes, so a reopened file keeps its copy and a second request is new.
        // ⚠ Not while a drop it asked for failed (a file open elsewhere): the mark stays and the next pass retries.
        for ((relative, needed) in plan.cleared) {
            if (needed.any { it in failedDrops }) continue
            val hr = WindowsCloudFiles.setPinState(root.resolve(relative), WindowsCloudFiles.PIN_STATE_UNSPECIFIED) ?: continue
            if (!CloudFilesHResult.succeeded(hr)) log("explorer: clear free mark '$relative' -> ${CloudFilesHResult.describe(hr)}")
        }
    }

    /**
     * Change notifications for every folder in the tree; any of them asks for a pin pass.
     * ⚠ One registration per folder, not `ExtendedWatchEventModifier.FILE_TREE`: that lives in `jdk.unsupported`,
     * which the packaged app's trimmed runtime does not carry (measured: `NoClassDefFoundError` in the MSIX).
     */
    private fun watchFolder(watcher: WatchService) {
        val watched = HashSet<Path>()
        fun registerFolders() {
            Files.walk(root).use { s -> s.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
                .filter { watched.add(it) }
                .forEach { dir ->
                    runCatching {
                        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                    }.onFailure { watched.remove(dir) }
                }
        }
        try {
            registerFolders()
            while (true) {
                val key = watcher.take()
                val created = key.pollEvents().any { it.kind() == StandardWatchEventKinds.ENTRY_CREATE }
                if (!key.reset()) watched.remove(key.watchable() as Path)
                if (created) runCatching { registerFolders() }
                pins.trySend(Unit)
                // Something was written in the folder: the write path looks at it (once it has settled, §7.10).
                passes.trySend(Unit)
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun deletePlaceholder(path: Path): Boolean {
        val relative = root.relativize(path).joinToString("/")
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return true.also { journal.forget(relative) }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return (deleteIfStillOurs(root, path, log) == Deletion.DELETED).also { if (it) journal.forget(relative) }
        }
        return deleteFileIfUnchanged(path, relative).also { if (it) journal.forget(relative) }
    }

    /**
     * Removes our placeholder file at [path] — ⚠⚠ only if it holds nothing but what we put there, checked and deleted
     * on **one unshared handle** (`deleteOnClose`), so no save can land between the check and the delete. In sync is
     * not enough: a downloaded copy is also hashed against the journal's hash of its version, and bytes that differ —
     * an edit Windows did not mark — are marked not-in-sync instead, for the write path (an upload or a conflict copy).
     * The realm moving on must never delete an edit. Without a recorded hash (made before it was kept), or with no
     * complete local copy, there is nothing to compare and nothing of the user's to lose.
     */
    private fun deleteFileIfUnchanged(path: Path, relative: String): Boolean {
        if (ourPlaceholder(path)?.inSync != true) {
            log("explorer: kept '$relative': it is not (or no longer) only ours")
            return false
        }
        // Placeholders made before X3 were read-only, which refuses the write access the handle asks for.
        runCatching { Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS) }
        return runCatching {
            WindowsCloudFiles.withUnsharedFile(path, asItself = false) { file ->
                // Looked at again on the held handle: from here on nothing else can write to it.
                if (ourPlaceholder(path)?.inSync != true) {
                    log("explorer: kept '$relative': changed just now")
                    return@withUnsharedFile false
                }
                val online = (WindowsCloudFiles.fileAttributes(path) ?: 0) and WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS != 0
                val expected = journal.hashOf(relative)
                if (!online && expected != null && hashOf(file) != expected) {
                    val hr = file.clearInSync()
                    log("explorer: '$relative' was changed here; kept for upload -> ${CloudFilesHResult.describe(hr)}")
                    return@withUnsharedFile false
                }
                val hr = file.deleteOnClose()
                if (!CloudFilesHResult.succeeded(hr)) log("explorer: could not remove '$relative' -> ${CloudFilesHResult.describe(hr)}")
                CloudFilesHResult.succeeded(hr)
            }
        }.getOrElse {
            // Open elsewhere, typically: tried again by a later pass.
            log("explorer: could not remove '$relative' yet: $it")
            false
        }
    }

    /** [wholeFileHashOf] streamed off [file], so a large download is never held in memory whole. */
    private fun hashOf(file: WindowsCloudFiles.UnsharedFile): String {
        val hasher = IncrementalSha256()
        val chunk = ByteArray(FileContentChunkSizeBytes)
        val buffer = ByteArray(64 * 1024)
        var filled = 0
        var any = false
        while (true) {
            val n = file.read(buffer)
            if (n < 0) break
            var at = 0
            while (at < n) {
                val take = minOf(n - at, chunk.size - filled)
                System.arraycopy(buffer, at, chunk, filled, take)
                filled += take
                at += take
                if (filled == chunk.size) {
                    hasher.update(chunk.copyOf())
                    any = true
                    filled = 0
                }
            }
        }
        if (filled > 0 || !any) hasher.update(chunk.copyOf(filled))
        return hasher.digest().toHexLower()
    }

    /** Placeholders made before the write path (X3) were read-only; ours are writable now, so editors can save. */
    private fun makeOursWritable() {
        Files.walk(root).use { s -> s.filter { it != root }.toList() }.forEach { path ->
            val attributes = runCatching {
                Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return@forEach
            if (attributes.isDirectory || !attributes.isReadOnly || ourPlaceholder(path) == null) return@forEach
            runCatching { Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS) }
        }
    }

    private val handler = object : WindowsCloudFiles.Handler {
        // Called on a CfAPI pool thread: hand off at once, answer from our own threads (X0: blocking there works,
        // but the pool is the OS's).
        override fun fetchData(request: WindowsCloudFiles.FetchData) {
            active += request.transferKey
            fetchers.execute {
                try {
                    runBlocking { serve(request) }
                } catch (t: Throwable) {
                    log("explorer: fetch failed: $t")
                    request.fail()
                } finally {
                    active.remove(request.transferKey)
                    cancelled.remove(request.transferKey)
                }
            }
        }

        // ⚠ Our own deletes (the mirror's) are ignored by process: carried to the realm, they would trash what the
        // realm just replaced.
        override fun notifyDelete(notice: WindowsCloudFiles.NotifyDelete) {
            if (notice.isUndelete || notice.process?.pid == ownPid) return
            note(notice.path, null, notice.fileIdentity, notice.isDirectory)
        }

        override fun notifyRename(notice: WindowsCloudFiles.NotifyRename) {
            if (notice.process?.pid == ownPid) return
            // Out of the folder (the Recycle Bin) is a delete.
            note(notice.source, notice.target?.let(::relativeOf), notice.fileIdentity, notice.isDirectory)
        }

        override fun cancelFetchData(cancel: WindowsCloudFiles.CancelFetchData) {
            // A cancel for a transfer already finished would otherwise stay in the map for good.
            if (cancel.transferKey !in active || cancel.length <= 0) return
            cancelled.computeIfAbsent(cancel.transferKey) { java.util.Collections.synchronizedList(ArrayList()) }
                .add(cancel.offset until cancel.offset + cancel.length)
        }
    }

    /**
     * Delivers the requested range of the file the placeholder names. ⚠ The placeholder carries the content
     * version it was made for: bytes of a newer version would not match the size Windows already has, so a stale
     * placeholder is failed and the next pass replaces it.
     *
     * ⚠⚠ Checked again **after** the bytes are opened, before any is delivered: the stream resolves the path's content
     * on its own, so an overwrite that syncs in between would otherwise pour the new version into the old placeholder
     * (truncated if larger, or silently different if the same size). Unchanged before and after means the bytes opened
     * are the identity's: a content version names its bytes.
     */
    private suspend fun serve(request: WindowsCloudFiles.FetchData) {
        val identity = ExplorerIdentity.decode(request.fileIdentity) ?: error("not a Tenshu placeholder")
        suspend fun checkStillTheSame() {
            val current = koin.fileSystem().readEntityItem(identity.path)
            check(current.contentVersion == identity.contentVersion) { "'${identity.path}' changed since its placeholder was made" }
            check(current.size == request.fileSize) { "'${identity.path}' is ${current.size} bytes, the placeholder says ${request.fileSize}" }
        }
        checkStillTheSame()
        val end = minOf(request.requiredOffset + request.requiredLength, request.fileSize)
        val who = request.process?.imagePath?.substringAfterLast('\\') ?: "?"
        log("explorer: fetch '${identity.path}' ${request.requiredOffset}+${end - request.requiredOffset} for $who")

        val client = koin.get<TenshuClient>()
        val opening = client.syncService.openContentStream(File.ID, identity.path, File.Data.id)
            .fold(onSuccess = { it }, onFailure = { null })
        if (opening == null) {
            // No stream: the bytes are only local (not synced yet), so they are already here.
            val bytes = client.fileService.defaultFileSystem.readBytes(identity.path)
            checkStillTheSame()
            check(bytes.size.toLong() == request.fileSize) { "'${identity.path}' read ${bytes.size} bytes, the placeholder says ${request.fileSize}" }
            var offset = request.requiredOffset
            while (offset < end) {
                val next = minOf(offset + CHUNK_BYTES, end)
                deliver(request, offset, bytes.copyOfRange(offset.toInt(), next.toInt()))
                offset = next
            }
            return
        }
        val handle = opening.handle.get()
        try {
            checkStillTheSame()
            check(opening.totalSize.get() == request.fileSize) {
                "'${identity.path}' opened ${opening.totalSize.get()} bytes, the placeholder says ${request.fileSize}"
            }
            var offset = request.requiredOffset
            while (offset < end) {
                val next = minOf(offset + CHUNK_BYTES, end)
                val chunk = ByteArray((next - offset).toInt())
                var filled = 0
                while (filled < chunk.size) {
                    val read = client.syncService.readContentRange(handle, offset + filled, chunk.size - filled)
                        .fold(onSuccess = { it.getOrNull() }, onFailure = { null })
                    check(read != null && read.isNotEmpty()) { "content read failed at byte ${offset + filled}" }
                    read.copyInto(chunk, filled)
                    filled += read.size
                }
                deliver(request, offset, chunk)
                offset = next
            }
        } finally {
            client.syncService.closeContentStream(handle)
        }
    }

    private fun deliver(request: WindowsCloudFiles.FetchData, offset: Long, bytes: ByteArray) {
        // A chunk wholly inside a cancelled range is no longer wanted: skipped, not failed — failing would fail the
        // whole request, bytes other readers still wait for included.
        val end = offset + bytes.size
        val ranges = cancelled[request.transferKey]?.let { synchronized(it) { it.toList() } }.orEmpty()
        if (ranges.any { offset >= it.first && end - 1 <= it.last }) return
        val hr = request.transfer(offset, bytes) ?: error("CfAPI unavailable")
        check(CloudFilesHResult.succeeded(hr)) { "CfExecute(TRANSFER_DATA) at $offset -> ${CloudFilesHResult.describe(hr)}" }
    }

    /**
     * Unregisters this user's other `TenshuDrive!` roots — left by an earlier id scheme or a crashed run — so
     * Explorer never shows two. ⚠ `GetCurrentSyncRoots` omits roots under `%TEMP%` (§7.7 trap 1); ours never is.
     */
    private fun sweepStale(current: String) {
        val prefix = current.substringBeforeLast('!') + "!"
        val ids: List<String> = WindowsSyncRootManager.currentSyncRootIds()?.getOrNull() ?: emptyList()
        ids.filter { id -> id.startsWith(prefix) && id != current }
            .forEach { log("explorer: stale root $it -> Unregister: ${WindowsSyncRootManager.unregister(it)}") }
    }

    /** The running launcher's own icon (the packaged `TenshuControl.exe`); Windows' cloud icon in a dev run. */
    private fun iconResource(): String {
        val launcher = ProcessHandle.current().info().command().orElse(null)
        return if (launcher != null && launcher.endsWith("TenshuControl.exe", ignoreCase = true)) "$launcher,0"
        else "${System.getenv("SystemRoot") ?: "C:\\Windows"}\\System32\\imageres.dll,-1043"
    }

    companion object {
        const val PROVIDER = "TenshuDrive"
        private const val ACCOUNT = "default"
        const val DISPLAY_NAME = "Tenshu Drive"
        private const val PROVIDER_VERSION = "1"
        private val PROVIDER_ID: UUID = UUID.fromString("3d6f9a24-58c1-4b7e-a0d2-6e91c4f7b385")

        /** A multiple of the transfer alignment, so every chunk but the one at EOF is aligned. */
        private const val CHUNK_BYTES = 1L shl 20
        private const val FETCH_THREADS = 4
        private const val DEBOUNCE_MILLIS = 1_000L
        private const val PIN_DEBOUNCE_MILLIS = 500L
        private const val REPAIR_INTERVAL_MILLIS = 60_000L
        private const val MAX_IDENTITY_BYTES = 4096

        private const val STOP_TIMEOUT_MILLIS = 10_000L
        private const val WRITE_RETRY_MILLIS = 2 * 60_000L
        private const val SETTLE_MARGIN_MILLIS = 500L

        /** The largest upload the file-service can take: it is handed one `ByteArray`. */
        private const val MAX_UPLOAD_BYTES = Int.MAX_VALUE - 16L
        private const val MASS_REMOVAL_CONFIRM_MILLIS = 30_000L

        /** How long a delete or rename that keeps failing to reach the realm is tried for (X3b). */
        private const val OP_GIVE_UP_MILLIS = 10 * 60_000L

        /** How long an applied step's paths stay guarded while the listing does not show it yet (a deferred move). */
        private const val APPLIED_GUARD_MILLIS = 2 * 60_000L

        /** `%USERPROFILE%\Tenshu Drive`. */
        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), DISPLAY_NAME)

        private fun syncRootId(): String? = WindowsAccount.currentUserSid()?.let { "$PROVIDER!$it!$ACCOUNT" }

        /**
         * The realm path of the file placeholder at [windowsPath] in [root], read from its identity — or `null` for
         * anything that is not one of our file placeholders under the root (a folder, the user's own file, a link,
         * a path elsewhere). ⚠ Reads the placeholder's info only; nothing is opened for data, so nothing downloads.
         */
        fun realmPathOf(windowsPath: Path, root: Path = defaultRoot()): String? {
            // Real paths, not the text: a junction inside the folder must not lead the identity read outside it.
            // ⚠ Resolving links reads no file's data.
            val path = runCatching { windowsPath.toRealPath() }.getOrNull() ?: return null
            val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
            if (!path.startsWith(realRoot) || path == realRoot) return null
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
            return ourPlaceholder(path)?.identity?.path
        }

        /** One of our placeholders: it carries a Tenshu identity. `null` for anything else, links included. */
        private class Ours(val identity: ExplorerIdentity, val inSync: Boolean)

        private fun ourPlaceholder(path: Path): Ours? {
            val info = WindowsCloudFiles.placeholderInfo(path) ?: return null
            val identity = ExplorerIdentity.decode(info.identity) ?: return null
            return Ours(identity, info.inSync)
        }

        /** What [deleteIfStillOurs] did. */
        private enum class Deletion { DELETED, KEPT_NOT_OURS, FAILED }

        /**
         * Deletes [path] only if it is still one of our placeholders that holds what we put there — re-checked right
         * before deleting, so nothing the user changed since the scan (an edit saved in place, a link put there) goes.
         */
        private fun deleteIfStillOurs(root: Path, path: Path, log: (String) -> Unit): Deletion = runCatching {
            if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return Deletion.DELETED
            val ours = ourPlaceholder(path)
            if (ours == null || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !ours.inSync)) {
                log("explorer: kept '${root.relativize(path)}': it is not (or no longer) only ours")
                return Deletion.KEPT_NOT_OURS
            }
            Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
            Files.delete(path)
            Deletion.DELETED
        }.getOrElse {
            // An open file cannot be deleted; it is tried again (the next pass, or the cleanup retry).
            log("explorer: could not remove '${root.relativize(path)}': $it")
            Deletion.FAILED
        }

        /**
         * Empties [root] of our placeholders (never the user's files, never a link), unregisters, and removes the
         * folder if nothing is left. Needs no proxy, so it works before Control has finished starting. Answers whether
         * it finished.
         *
         * ⚠⚠ A placeholder it could not delete (open in an app that shares no delete access) keeps the root
         * **registered**: once unregistered, what is left turns into ordinary files (measured on uninstall, §7.9) that
         * [ourPlaceholder] no longer recognises — a decrypted copy nothing would ever remove. The caller retries.
         */
        fun removeAll(root: Path, log: (String) -> Unit): Boolean {
            var failed = 0
            runCatching {
                if (root.exists()) {
                    Files.walk(root).use { s -> s.filter { it != root }.toList() }
                        .sortedByDescending { it.nameCount }
                        .filter { ourPlaceholder(it) != null }
                        .forEach { entry ->
                            // ⚠ A folder of ours still holding the user's files stays, and is not a failure: it is
                            // done. Counted as one, the cleanup would never finish and never unregister.
                            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
                                Files.list(entry).use { it.findFirst().isPresent }
                            ) {
                                return@forEach
                            }
                            if (deleteIfStillOurs(root, entry, log) == Deletion.FAILED) failed++
                        }
                }
            }.onFailure {
                log("explorer: removing placeholders failed: $it")
                failed++
            }
            if (failed > 0) {
                log("explorer: $failed placeholder(s) could not be removed yet; the folder stays registered and is tried again")
                return false
            }
            syncRootId()?.let { log("explorer: Unregister -> ${WindowsSyncRootManager.unregister(it)}") }
            runCatching { if (root.isDirectory() && Files.list(root).use { it.findFirst().isEmpty }) Files.delete(root) }
            return true
        }
    }
}

/**
 * The FileIdentity blob of a placeholder: which realm path it shows, at which content version. Versioned so a
 * later shape can tell old placeholders apart.
 */
internal data class ExplorerIdentity(val path: String, val contentVersion: String) {
    fun encode(): ByteArray = "$VERSION\n$path\n$contentVersion".encodeToByteArray()

    companion object {
        private const val VERSION = "1"

        fun decode(bytes: ByteArray): ExplorerIdentity? {
            val parts = bytes.decodeToString().split('\n', limit = 3)
            if (parts.size != 3 || parts[0] != VERSION || !parts[1].startsWith('/')) return null
            return ExplorerIdentity(parts[1], parts[2])
        }
    }
}
