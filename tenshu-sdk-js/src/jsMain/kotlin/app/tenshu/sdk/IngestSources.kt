@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.promise
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.setSourceEnabled
import org.tenshu.ingest.client.ingestService
import kotlin.js.Promise

/**
 * Web management surface for Control's generic background-ingest registry
 * (`drive-synced-folders-phase2.md` §8) — the apex counterpart of the native
 * `BackgroundIngestManager` (`:control-shared`). Backs the
 * `/control/data/background-ingest` page: list the registered ingest sources +
 * their live status, and flip the central kill switch.
 *
 * Reads the synced `IngestService.sources` relation over the authenticated
 * [JsRuntime.tenshuClient] (bare `.flow()`, `CompositeRo`-typed — same shape as
 * `Shares.list()`); `setEnabled` dispatches `SetSourceEnabled`. App→Control over
 * the in-process JS runtime; no new surface.
 */
@JsExport
object IngestSources {

    /**
     * Snapshot of every registered ingest source for the realm, by display name.
     * Promise-shaped (one emission) so JS can `await` it on mount and re-fetch
     * after [setEnabled].
     */
    fun list(): Promise<Array<IngestSourceJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val client = JsRuntime.tenshuClient
        client.ingestService.sources.flow().first().fold(
            onSuccess = { rows ->
                rows.toList()
                    .map { entity ->
                        IngestSourceJs(
                            key = entity.key.get(),
                            sourceId = entity.sourceId.get(),
                            appId = entity.appId.get(),
                            deviceId = entity.deviceId.get(),
                            displayName = entity.displayName.get(),
                            sourceKind = entity.sourceKind.get(),
                            enabled = entity.enabled.get(),
                            status = entity.lastStatus?.get() ?: IngestSource.STATUS_IDLE,
                            pendingCount = (entity.pendingCount?.get() ?: 0L).toDouble(),
                        )
                    }
                    .sortedBy { it.displayName }
                    .toTypedArray()
            },
            onFailure = { emptyArray() },
        )
    }

    /**
     * Flip a source's central kill switch (`IngestSource.enabled`). Resolves
     * `true` on success. The owning app honours the new value on its next
     * tick / watch event (§5); the caller should re-[list] afterwards.
     */
    fun setEnabled(sourceId: String, deviceId: String, enabled: Boolean): Promise<Boolean> =
        GlobalScope.promise {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.ingestService
                .setSourceEnabled(sourceId, deviceId, enabled)
                .fold(onSuccess = { true }, onFailure = { false })
        }
}

/** One registered ingest source, surfaced to JS. `pendingCount` is a `Double` (JS number). */
@JsExport
class IngestSourceJs internal constructor(
    val key: String,
    val sourceId: String,
    val appId: String,
    val deviceId: String,
    val displayName: String,
    val sourceKind: String,
    val enabled: Boolean,
    val status: String,
    val pendingCount: Double,
)
