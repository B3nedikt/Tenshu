package app.tenshu.control.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.getOrNull
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.SecurityScopedFolderGrant
import org.tenshu.core.ingest.updateSourceStatus
import app.tenshu.macos.MacSecurityScopedBookmark
import org.tenshu.file.client.fileService
import org.tenshu.identity.client.identityService
import org.tenshu.identity.deviceName
import org.tenshu.ingest.client.ingestService
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import java.io.File

/**
 * Control's in-process desktop ingester (`drive-synced-folders-phase2.md`
 * §4.2/§7). On desktop the resident Control daemon — not Drive — owns the
 * watcher: it reads this device's enabled folder sources from the registry,
 * walks their `watchedPaths`, and uploads new files into the realm through its
 * own in-process [TenshuClient] (the data plane it already owns). No reverse
 * trigger, no app process in the loop — Control *is* the ingester.
 *
 * **v1 shape (decided, all reversible follow-ups):**
 * - **Periodic poll** ([pollIntervalMs]) rather than `WatchService` — simpler +
 *   robust for a resident daemon; a `WatchService` edge-trigger is a refinement.
 * - **In-memory mtime watermark** per source (no persisted cursor). A restart
 *   re-scans from the floor; the content-derived **dedup backstop** (§3.5,
 *   `AlreadyExists → Skip`) makes that idempotent — also why this can run
 *   alongside Drive-desktop's still-present Phase-1 drain without duplicating
 *   files (disabling that redundant Drive drain is a follow-up).
 * - **Images only** (the registry doesn't carry Drive's per-device video toggle).
 * - Gate = source **enabled** (registry kill switch) + **storage**; desktop is
 *   assumed unmetered, so there is no network/charging gate.
 *
 * The risky logic (the folder walk) is the pure, unit-tested [FolderScanner];
 * this class is the daemon wiring (correctness-by-construction — the in-process
 * upload needs a paired desktop session to exercise live).
 */
class FolderWatcher(
    private val client: TenshuClient,
    private val manager: BackgroundIngestManager,
    private val grants: SecurityScopedGrantStore = SecurityScopedGrantStore(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 30_000L,
) {
    private var started = false
    private val watermarks = mutableMapOf<String, Long>()
    /** Sources last reported as ERROR for an unresolvable grant, so the report is sent once. */
    private val reportedUnresolvable = mutableSetOf<String>()
    private var cachedDeviceId: String? = null
    private var cachedDeviceLabel: String? = null

    /** Begin the resident ingest loop. Idempotent; call once at Control startup. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { tickOnce() }
                delay(pollIntervalMs)
            }
        }
    }

    /** One ingest pass over this device's enabled folder sources. `internal` for tests. */
    internal suspend fun tickOnce() {
        val deviceId = deviceId() ?: return
        // §5 read-gate: storage is the hard gate (enabled is per-source, below).
        val storage = client.storageService.status().first()
        if (storage == null || !storage.hasStorage.get()) return

        val sources = manager.sources().first().filter {
            it.deviceId == deviceId && it.enabled && it.watchedPaths.isNotBlank()
        }
        if (sources.isEmpty()) return
        // Friendly name for the /Backup/<device>/ root; falls back to the raw
        // deviceId until the Device row resolves. Status reporting still uses the
        // raw deviceId (the registry key), so only the destination path changes.
        val label = deviceLabel(deviceId)
        for (source in sources) ingestSource(deviceId, label, source)
    }

    private suspend fun ingestSource(deviceId: String, deviceLabel: String, source: IngestSourceModel) {
        val access = WatchedFolderAccess.open(source.watchedPaths, grants)
        try {
            // A grant Control can no longer open (folder deleted, volume gone, bookmark lost) is
            // not "nothing new": say so once, in the status Control's ingest screen shows,
            // instead of quietly backing up nothing.
            if (access.unresolvable > 0) {
                if (reportedUnresolvable.add(source.sourceId)) {
                    client.ingestService.updateSourceStatus(
                        source.sourceId, deviceId, IngestSource.STATUS_ERROR, 0,
                    )
                }
            } else if (reportedUnresolvable.remove(source.sourceId)) {
                // Readable again: clear the ERROR even if this pass finds nothing new to upload.
                client.ingestService.updateSourceStatus(
                    source.sourceId, deviceId, IngestSource.STATUS_UP_TO_DATE, 0,
                )
            }
            val since = watermarks[source.sourceId] ?: Long.MIN_VALUE
            val files = FolderScanner.scanNewImages(access.paths, since)
            if (files.isEmpty()) return

            client.ingestService.updateSourceStatus(
                source.sourceId, deviceId, IngestSource.STATUS_RUNNING, files.size.toLong(),
            )

            var maxMtime = since
            for (file in files) {
                val bucket = File(file.absolutePath).parentFile?.name ?: "Backup"
                val destination = backupDestinationFolder(deviceLabel, bucket)
                // AlreadyExists / transient failures fold away — the dedup backstop
                // (§3.5) means a re-upload is a cheap Skip; the watermark advances
                // regardless so the loop makes forward progress (per-file retry within
                // a session is a follow-up; a restart re-scans from the floor).
                runCatching {
                    client.fileService.defaultFileSystem.uploadFile(
                        parentPath = destination,
                        fileName = file.name,
                        data = File(file.absolutePath).readBytes(),
                    )
                }
                maxMtime = maxOf(maxMtime, file.lastModifiedMillis)
            }
            watermarks[source.sourceId] = maxMtime

            // A pass that uploaded the readable folders is still not "up to date" while one
            // folder cannot be opened at all.
            client.ingestService.updateSourceStatus(
                source.sourceId,
                deviceId,
                if (access.unresolvable > 0) IngestSource.STATUS_ERROR else IngestSource.STATUS_UP_TO_DATE,
                0,
            )
        } finally {
            access.close()
        }
    }

    /**
     * Resolves Control-owned grants for one pass and keeps every security scope alive until the
     * scan and uploads finish. A raw path (unsandboxed development) passes through unchanged.
     *
     * ⚠⚠ A stale bookmark is re-minted **in Control's [SecurityScopedGrantStore], under the same
     * grant id** — never by rewriting `watchedPaths`. Drive re-registers `watchedPaths` from its
     * selection on every launch, so a refresh written there was put back to the stale value.
     * An unresolvable grant is counted, and never treated as a host path.
     */
    private class WatchedFolderAccess private constructor(
        val paths: List<String>,
        val unresolvable: Int,
        private val scopes: List<MacSecurityScopedBookmark.Access>,
    ) : AutoCloseable {
        override fun close() = scopes.forEach { it.close() }

        companion object {
            fun open(encoded: String, grants: SecurityScopedGrantStore): WatchedFolderAccess {
                val paths = mutableListOf<String>()
                val scopes = mutableListOf<MacSecurityScopedBookmark.Access>()
                var unresolvable = 0
                encoded.split('\n').filter { it.isNotBlank() }.forEach { value ->
                    val grant = SecurityScopedFolderGrant.decode(value)
                    if (grant == null) {
                        paths += value
                        return@forEach
                    }
                    val access = grants.read(grant.grantId)?.let(MacSecurityScopedBookmark::resolve)
                    if (access == null) {
                        unresolvable++
                        return@forEach
                    }
                    scopes += access
                    paths += access.path
                    // Re-mint while the scope is active; macOS answers stale until we do.
                    if (access.isStale) {
                        MacSecurityScopedBookmark.create(access.path)?.let { grants.replace(grant.grantId, it) }
                    }
                }
                return WatchedFolderAccess(paths, unresolvable, scopes)
            }
        }
    }

    private suspend fun deviceId(): String? =
        cachedDeviceId
            ?: client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()
                ?.takeIf { it.isNotBlank() }
                ?.also { cachedDeviceId = it }

    // Friendly device name for the destination root, cached once resolved; while
    // only the deviceId fallback is available the next tick retries (mirrors
    // Drive's TenshuBackupPreferences.deviceLabel).
    private suspend fun deviceLabel(deviceId: String): String {
        cachedDeviceLabel?.let { return it }
        val name = runCatching { client.identityService.deviceName(deviceId) }.getOrNull()
        if (name != null) cachedDeviceLabel = name
        return name ?: deviceId
    }

    private fun backupDestinationFolder(deviceLabel: String, bucketName: String): String =
        "/Backup/${sanitizeSegment(deviceLabel)}/${sanitizeSegment(bucketName)}"

    private fun sanitizeSegment(segment: String): String =
        segment.replace('/', '_').replace('\\', '_').trim().ifEmpty { "unknown" }
}
