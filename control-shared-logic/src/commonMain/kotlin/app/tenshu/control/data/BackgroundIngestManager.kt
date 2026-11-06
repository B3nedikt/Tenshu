package app.tenshu.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.setSourceEnabled
import org.tenshu.ingest.client.ingestService

/** A registered background-ingest source as the Control management surface sees it (§8). */
data class IngestSourceModel(
    val key: String,
    val sourceId: String,
    val appId: String,
    val deviceId: String,
    val displayName: String,
    val sourceKind: String,
    val enabled: Boolean,
    val status: String,
    val pendingCount: Long,
    /** Desktop only: newline-separated dirs the Control `FolderWatcher` walks (§4.2). Empty on mobile. */
    val watchedPaths: String,
)

/**
 * Control's view of the generic ingest registry
 * (`docs/proposals/drive-synced-folders-phase2.md` §3/§8): the inventory of
 * background-ingest sources across all apps + the central kill switch.
 *
 * Read side is the synced `IngestService.sources` relation (so sources another
 * app on this — or, once the cross-device view lands, another *device* — registers
 * appear automatically). Write side is [setEnabled], the kill switch: it flips
 * `IngestSource.enabled`; the owning app honours it on its next tick / watch event
 * (§5), there is no push.
 */
interface BackgroundIngestManager {
    fun sources(): Flow<List<IngestSourceModel>>
    suspend fun setEnabled(sourceId: String, deviceId: String, enabled: Boolean)
}

/** Real [BackgroundIngestManager] over the in-realm [TenshuClient]. Mirrors [TenshuShareManager]. */
class TenshuBackgroundIngestManager(
    private val client: TenshuClient,
) : BackgroundIngestManager {

    override fun sources(): Flow<List<IngestSourceModel>> =
        // Bare `.flow()` (no projection): `IngestSource` is all scalar fields and is
        // registered with the generic `CompositeRo`, so this mirrors
        // `TenshuEventRepository`'s `notifications.flow()` read (the `.select{}`
        // projection path is for `EntityRo`-typed relations like `Share`).
        client.ingestService.sources.flow()
            .map { result ->
                result.getOrNull()
                    ?.toList()
                    ?.map { entity ->
                        IngestSourceModel(
                            key = entity.key.get(),
                            sourceId = entity.sourceId.get(),
                            appId = entity.appId.get(),
                            deviceId = entity.deviceId.get(),
                            displayName = entity.displayName.get(),
                            sourceKind = entity.sourceKind.get(),
                            enabled = entity.enabled.get(),
                            status = entity.lastStatus?.get() ?: IngestSource.STATUS_IDLE,
                            pendingCount = entity.pendingCount?.get() ?: 0L,
                            watchedPaths = entity.watchedPaths?.get() ?: "",
                        )
                    }
                    .orEmpty()
                    .sortedBy { it.displayName }
            }
            .flowOn(Dispatchers.Default)

    override suspend fun setEnabled(sourceId: String, deviceId: String, enabled: Boolean) {
        // The op flips the Eager `enabled` flag; the result is folded away — the
        // reactive `sources()` projection reflects it, so the UI converges without
        // a return value (mirrors `TenshuShareManager.revoke`).
        client.ingestService.setSourceEnabled(sourceId, deviceId, enabled)
    }
}

/** No-op for previews / tests where Control isn't wired. */
object NoopBackgroundIngestManager : BackgroundIngestManager {
    override fun sources(): Flow<List<IngestSourceModel>> = flowOf(emptyList())
    override suspend fun setEnabled(sourceId: String, deviceId: String, enabled: Boolean) = Unit
}
