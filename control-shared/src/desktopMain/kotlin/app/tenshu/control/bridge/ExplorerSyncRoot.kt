package app.tenshu.control.bridge

import app.tenshu.windows.CloudFilesHResult
import app.tenshu.windows.WindowsAccount
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsSyncRootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val fetchers = Executors.newFixedThreadPool(FETCH_THREADS) { r ->
        Thread(r, "explorer-fetch").apply { isDaemon = true }
    }
    /** Transfers being served, and those of them Windows cancelled; only an active one can be cancelled. */
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    /** When a pass first proposed a mass removal that has not been confirmed yet ([MASS_REMOVAL_CONFIRM_MILLIS]). */
    @Volatile private var massRemovalSince: Long? = null

    /** The effective pin marks the last pin pass saw (pin thread only). */
    private var previousMarks: Map<String, ExplorerMirror.Pin> = emptyMap()

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
        fetchers.shutdownNow()
    }

    /** Turns the integration off: [stop], then [removeAll]. */
    fun remove() {
        stop()
        removeAll(root, log)
    }

    private suspend fun watchRealm() {
        while (scope.isActive) {
            runCatching {
                koin.fileSystem().root.files.traverse().select { itemProjection() }.flow().collect { passes.trySend(Unit) }
            }.onFailure { log("explorer: realm listing stopped ($it); retrying") }
            delay(REPAIR_INTERVAL_MILLIS)
        }
    }

    private suspend fun runPasses() {
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
        val remote = remoteRows() ?: return
        val local = localEntries()
        val actions = ExplorerMirror.plan(remote, local)
        // ⚠ A listing that comes back empty or partial for a moment (a cold store, a reset) would take every
        // downloaded and pinned copy with it. So a mass removal must be proposed again a while later to be applied.
        if (ExplorerMirror.isMassRemoval(actions, local)) {
            val now = System.currentTimeMillis()
            val since = massRemovalSince ?: now.also { massRemovalSince = it }
            if (now - since < MASS_REMOVAL_CONFIRM_MILLIS) {
                log("explorer: the realm listing would remove most of the folder (${remote.size} rows); waiting for it to say so again")
                return
            }
        }
        massRemovalSince = null
        if (actions.isEmpty()) return
        var failed = 0
        for (action in actions) {
            if (!scope.isActive) return
            val ok = when (action) {
                is ExplorerMirror.Action.Remove -> deletePlaceholder(root.resolve(action.relative))
                is ExplorerMirror.Action.Create -> create(action)
            }
            if (!ok) failed++
        }
        log("explorer: pass applied ${actions.size - failed} of ${actions.size} changes")
        pins.trySend(Unit)
    }

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
                readOnly = !row.isFolder,
            ),
        ) ?: return false
        if (!CloudFilesHResult.succeeded(hr)) {
            log("explorer: create '${action.relative}' -> ${CloudFilesHResult.describe(hr)}")
            return false
        }
        // A replaced file the user kept on this device stays kept; the pin pass downloads the new version.
        if (action.pinned) {
            val pin = WindowsCloudFiles.setPinState(root.resolve(action.relative), WindowsCloudFiles.PIN_STATE_PINNED)
            if (pin != null && !CloudFilesHResult.succeeded(pin)) log("explorer: re-pin '${action.relative}' -> ${CloudFilesHResult.describe(pin)}")
        }
        return true
    }

    /**
     * "Always keep on this device" / "Free up space": Explorer only marks the files, the provider acts. Runs on
     * its own thread — a download blocks until our fetch threads have served it.
     */
    private fun pinPass() {
        if (!root.isDirectory()) return
        val previous = previousMarks
        val plan = ExplorerMirror.copies(localEntries(), previous)
        val marks = plan.effective.toMutableMap()
        for ((relative, copy) in plan.copies) {
            val path = root.resolve(relative)
            val hr = when (copy) {
                ExplorerMirror.Copy.DOWNLOAD -> WindowsCloudFiles.hydrate(path)
                ExplorerMirror.Copy.DROP -> WindowsCloudFiles.dehydrate(path)
            } ?: continue
            // ⚠ A file open elsewhere cannot be freed. Its mark is remembered as it was before, so the free request
            // still reads as new to the next pin pass and is tried again — not forgotten after one failure.
            if (copy == ExplorerMirror.Copy.DROP && !CloudFilesHResult.succeeded(hr)) previous[relative]?.let { marks[relative] = it }
            log("explorer: ${copy.name.lowercase()} '$relative' -> ${CloudFilesHResult.describe(hr)}")
        }
        previousMarks = marks
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
            }
        } catch (_: ClosedWatchServiceException) {
        } catch (_: InterruptedException) {
        }
    }

    private fun deletePlaceholder(path: Path): Boolean = deleteIfStillOurs(root, path, log)

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

        override fun cancelFetchData(cancel: WindowsCloudFiles.CancelFetchData) {
            // A cancel for a transfer already finished would otherwise stay in the set for good.
            if (cancel.transferKey in active) cancelled += cancel.transferKey
        }
    }

    /**
     * Delivers the requested range of the file the placeholder names. ⚠ The placeholder carries the content
     * version it was made for: bytes of a newer version would not match the size Windows already has, so a stale
     * placeholder is failed and the next pass replaces it.
     */
    private suspend fun serve(request: WindowsCloudFiles.FetchData) {
        val identity = ExplorerIdentity.decode(request.fileIdentity) ?: error("not a Tenshu placeholder")
        val current = koin.fileSystem().readEntityItem(identity.path)
        check(current.contentVersion == identity.contentVersion) { "'${identity.path}' changed since its placeholder was made" }
        check(current.size == request.fileSize) { "'${identity.path}' is ${current.size} bytes, the placeholder says ${request.fileSize}" }
        val end = minOf(request.requiredOffset + request.requiredLength, request.fileSize)
        val who = request.process?.imagePath?.substringAfterLast('\\') ?: "?"
        log("explorer: fetch '${identity.path}' ${request.requiredOffset}+${end - request.requiredOffset} for $who")

        val client = koin.get<TenshuClient>()
        val opening = client.syncService.openContentStream(File.ID, identity.path, File.Data.id)
            .fold(onSuccess = { it }, onFailure = { null })
        if (opening == null) {
            // No stream: the bytes are only local (not synced yet), so they are already here.
            val bytes = client.fileService.defaultFileSystem.readBytes(identity.path)
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
        check(request.transferKey !in cancelled) { "cancelled" }
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
        private const val MASS_REMOVAL_CONFIRM_MILLIS = 30_000L

        /** `%USERPROFILE%\Tenshu Drive`. */
        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), DISPLAY_NAME)

        private fun syncRootId(): String? = WindowsAccount.currentUserSid()?.let { "$PROVIDER!$it!$ACCOUNT" }

        /** One of our placeholders: it carries a Tenshu identity. `null` for anything else, links included. */
        private class Ours(val identity: ExplorerIdentity, val inSync: Boolean)

        private fun ourPlaceholder(path: Path): Ours? {
            val info = WindowsCloudFiles.placeholderInfo(path) ?: return null
            val identity = ExplorerIdentity.decode(info.identity) ?: return null
            return Ours(identity, info.inSync)
        }

        /**
         * Deletes [path] only if it is still one of our placeholders that holds what we put there — re-checked right
         * before deleting, so nothing the user changed since the scan (an edit saved in place, a link put there) goes.
         */
        private fun deleteIfStillOurs(root: Path, path: Path, log: (String) -> Unit): Boolean = runCatching {
            if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return true
            val ours = ourPlaceholder(path)
            if (ours == null || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !ours.inSync)) {
                log("explorer: kept '${root.relativize(path)}': it is not (or no longer) only ours")
                return false
            }
            Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
            Files.delete(path)
            true
        }.getOrElse {
            // An open file cannot be deleted; the next pass tries again.
            log("explorer: could not remove '${root.relativize(path)}': $it")
            false
        }

        /**
         * Empties [root] of our placeholders (never the user's files, never a link), unregisters, and removes the
         * folder if nothing is left. Needs no proxy, so it works before Control has finished starting.
         */
        fun removeAll(root: Path, log: (String) -> Unit) {
            runCatching {
                if (root.exists()) {
                    Files.walk(root).use { s -> s.filter { it != root }.toList() }
                        .sortedByDescending { it.nameCount }
                        .filter { ourPlaceholder(it) != null }
                        .forEach { deleteIfStillOurs(root, it, log) }
                }
            }.onFailure { log("explorer: removing placeholders failed: $it") }
            syncRootId()?.let { log("explorer: Unregister -> ${WindowsSyncRootManager.unregister(it)}") }
            runCatching { if (root.isDirectory() && Files.list(root).use { it.findFirst().isEmpty }) Files.delete(root) }
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
