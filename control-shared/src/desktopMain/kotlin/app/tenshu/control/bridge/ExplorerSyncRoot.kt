package app.tenshu.control.bridge

import app.tenshu.windows.CloudFilesHResult
import app.tenshu.windows.WindowsAccount
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsSyncRootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.nio.file.Files
import java.nio.file.LinkOption
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
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    @Volatile private var connection: WindowsCloudFiles.Connection? = null
    private var jobs: List<Job> = emptyList()

    /** Registers (idempotent), connects and starts keeping the folder in step. False when Windows refused. */
    fun start(): Boolean {
        val id = syncRootId() ?: return false.also { log("explorer: no user SID; not registering") }
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
                    delay(REPAIR_INTERVAL_MILLIS)
                }
            },
        )
        return true
    }

    /** Stops serving; the registration and the placeholders stay (Control quitting). */
    fun stop() {
        scope.cancel()
        connection?.disconnect()?.let { log("explorer: disconnected -> ${CloudFilesHResult.describe(it)}") }
        connection = null
        fetchers.shutdownNow()
    }

    /**
     * Turns the integration off: stops, deletes every placeholder (never the user's files), unregisters, and
     * removes the folder if nothing is left in it.
     */
    fun remove() {
        stop()
        runCatching {
            if (root.exists()) {
                Files.walk(root).use { s -> s.filter { it != root }.toList() }
                    .sortedByDescending { it.nameCount }
                    .filter { isPlaceholder(it) }
                    .forEach { deletePlaceholder(it) }
            }
        }.onFailure { log("explorer: removing placeholders failed: $it") }
        syncRootId()?.let { log("explorer: Unregister -> ${WindowsSyncRootManager.unregister(it)}") }
        runCatching { if (root.isDirectory() && Files.list(root).use { it.findFirst().isEmpty }) Files.delete(root) }
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
        val actions = ExplorerMirror.plan(remote, localEntries())
        if (actions.isEmpty()) return
        var failed = 0
        for (action in actions) {
            val ok = when (action) {
                is ExplorerMirror.Action.Remove -> deletePlaceholder(root.resolve(action.relative))
                is ExplorerMirror.Action.Create -> create(action)
            }
            if (!ok) failed++
        }
        log("explorer: pass applied ${actions.size - failed} of ${actions.size} changes")
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
            ExplorerMirror.Local(
                relative = root.relativize(path).joinToString("/"),
                isFolder = attributes.isDirectory,
                isPlaceholder = isPlaceholder(path),
                size = if (attributes.isDirectory) 0 else attributes.size(),
                modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
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
        if (!CloudFilesHResult.succeeded(hr)) log("explorer: create '${action.relative}' -> ${CloudFilesHResult.describe(hr)}")
        return CloudFilesHResult.succeeded(hr)
    }

    private fun deletePlaceholder(path: Path): Boolean = runCatching {
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return true
        Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
        Files.delete(path)
        true
    }.getOrElse {
        // An open file cannot be deleted; the next pass tries again.
        log("explorer: could not remove '${root.relativize(path)}': $it")
        false
    }

    private fun isPlaceholder(path: Path): Boolean =
        (WindowsCloudFiles.fileAttributes(path) ?: 0) and WindowsCloudFiles.FILE_ATTRIBUTE_REPARSE_POINT != 0

    private val handler = object : WindowsCloudFiles.Handler {
        // Called on a CfAPI pool thread: hand off at once, answer from our own threads (X0: blocking there works,
        // but the pool is the OS's).
        override fun fetchData(request: WindowsCloudFiles.FetchData) {
            fetchers.execute {
                try {
                    runBlocking { serve(request) }
                } catch (t: Throwable) {
                    log("explorer: fetch failed: $t")
                    request.fail()
                } finally {
                    cancelled.remove(request.transferKey)
                }
            }
        }

        override fun cancelFetchData(cancel: WindowsCloudFiles.CancelFetchData) {
            cancelled += cancel.transferKey
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

    private fun syncRootId(): String? = WindowsAccount.currentUserSid()?.let { "$PROVIDER!$it!$ACCOUNT" }

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
        private const val REPAIR_INTERVAL_MILLIS = 60_000L
        private const val MAX_IDENTITY_BYTES = 4096

        /** `%USERPROFILE%\Tenshu Drive`. */
        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), DISPLAY_NAME)
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
