package app.tenshu.ingest.backup

import app.tenshu.ingest.DeviceIdentity
import kotlinx.coroutines.flow.first
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.core.ingest.IngestSource
import org.tenshu.ingest.client.ingestService
import org.tenshu.core.ingest.registerSource
import org.tenshu.core.ingest.updateSourceStatus

/**
 * Drive's view of Control's generic background-ingest registry
 * (`docs/proposals/drive-synced-folders-phase2.md` §3). The write half of
 * "Drive consumes the registry" (Phase 2a-ii): Drive **registers** its gallery
 * source so it shows up in Control's management/inspection surface (§8), and
 * **reports** its live backup status as the loop runs.
 *
 * Calls flow **app→Control** over the existing IPC (the registry ops execute in
 * Control's proxy) — no new IPC direction, consistent with the "Control is a pull
 * authority, never a push driver" model (§1). Mirrors [TenshuBackupPreferences]:
 * the registry rows are device-scoped, so `deviceId` is resolved once via the
 * sync identity and cached.
 */
interface IngestRegistry {
    /** Idempotent upsert of this device's descriptor for [sourceId] (§3.3). */
    suspend fun registerSource(
        sourceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String = "",
    )

    /** Report live status for [sourceId] (§4); a no-op until the source is registered. */
    suspend fun updateStatus(
        sourceId: String,
        status: String,
        pendingCount: Long = 0,
    )

    /**
     * The central kill switch (§5): one-shot read of this device's
     * `IngestSource.enabled` for [sourceId], at tick time. `true` when the row is
     * absent (not yet registered) or unresolved, and **fail-open** on a read error
     * — a transient registry read must not silently stop backups; the in-app master
     * toggle remains the primary gate.
     */
    suspend fun isEnabled(sourceId: String): Boolean
}

class TenshuIngestRegistry(
    private val client: TenshuClient,
    private val identity: DeviceIdentity = DeviceIdentity(client),
) : IngestRegistry {

    // Retries across the startup race instead of silently no-op'ing (see [DeviceIdentity]).
    private suspend fun deviceId(): String? = identity.awaitOrNull()

    override suspend fun registerSource(
        sourceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String,
    ) {
        val id = deviceId() ?: return
        client.ingestService.registerSource(
            sourceId = sourceId,
            appId = APP_ID,
            deviceId = id,
            displayName = displayName,
            sourceKind = sourceKind,
            watchedPaths = watchedPaths,
        )
    }

    override suspend fun updateStatus(
        sourceId: String,
        status: String,
        pendingCount: Long,
    ) {
        val id = deviceId() ?: return
        client.ingestService.updateSourceStatus(
            sourceId = sourceId,
            deviceId = id,
            status = status,
            pendingCount = pendingCount,
        )
    }

    override suspend fun isEnabled(sourceId: String): Boolean {
        val id = deviceId() ?: return true
        val key = IngestSource.keyOf(sourceId, id)
        // Bare `.flow()` read (CompositeRo-typed relation; mirrors the Control-side
        // manager). Fail-open: an absent row or a transient read error must not
        // silently halt backups — the in-app master toggle stays the primary gate.
        val rows = client.ingestService.sources.flow().first().getOrNull() ?: return true
        return rows.toList().firstOrNull { it.key.get() == key }?.enabled?.get() ?: true
    }

    companion object {
        /** First-party Drive app id (== its bundle id; see macOS integration notes). */
        const val APP_ID = "app.tenshu.drive"
    }
}

/** No-op for previews / tests where the sync identity (or Control) is unavailable. */
object NoopIngestRegistry : IngestRegistry {
    override suspend fun registerSource(
        sourceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String,
    ) = Unit

    override suspend fun updateStatus(
        sourceId: String,
        status: String,
        pendingCount: Long,
    ) = Unit

    override suspend fun isEnabled(sourceId: String): Boolean = true
}
