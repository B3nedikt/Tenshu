package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId

/**
 * Extension point for resolving a property whose schema declares
 * `Syncable(SyncStrategy.OnDemand)`.
 *
 * When [ExecutionContext.resolveDynamicPropertyAsync] gets a null result from
 * the local resolution chain (i.e. the bytes haven't been synced yet), it
 * looks up the `OnDemandResolution` component registered on the
 * [TContext] and calls [resolve] to materialise the property. After the
 * suspend call returns, the local resolution is retried — if the resolver
 * successfully wrote bytes / SyncBlocks into the local database the second
 * resolve produces the real value.
 *
 * `:core-common` defines only the abstract component; concrete
 * implementations live in the sync layer (e.g. `:sync-service-proxy`'s
 * `OnDemandResolutionImpl`, which routes through `relay.fetchEntityBlocks`
 * and `SyncProcessor`). This keeps `:core-common` ignorant of the sync
 * transport while still providing transparent OnDemand fetching to SDK
 * callers.
 *
 * Registered globally on the [TContext] (rather than per-schema as a
 * [org.tenshu.core.trait.Trait]) because the trait-resolution lookup
 * requires the trait to appear in a schema's `traits` list — we don't
 * want every `Syncable(OnDemand)`-bearing relation to also have to
 * declare `OnDemandResolution` explicitly. The `Syncable` trait remains
 * the per-schema marker; this component is the one-and-only behaviour
 * the resolver dispatches to when that marker says OnDemand.
 */
interface OnDemandResolution : TComponent {

    /**
     * Materialise the OnDemand-declared property identified by [entityId]
     * + [propertyId] for the caller [executionContext]. Returns
     * [TResult.Failure] for unrecoverable errors (network failure, decode
     * error) that should propagate to the caller. Returns [TResult.Success]
     * when the resolver ran successfully regardless of whether anything was
     * actually fetched — the caller re-reads the property afterwards to
     * determine the materialised value.
     */
    suspend fun resolve(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
        params: TList<Data>?
    ): TResult<Data?>

    /**
     * D2b streaming (`docs/proposals/drive-streaming-preview.md` §3.5): open a **seekable
     * content stream** for the OnDemand byte property [entityId] + [propertyId], instead of
     * the whole-file reassemble [resolve] performs. The resolver fetches the file's manifest
     * (the same realm-routed fetch [resolve] uses) and registers a session keyed by the
     * returned [ContentStreamOpening.handle], without materialising the whole file. Subsequent
     * [readContentRange] calls decrypt only the chunks a range touches.
     *
     * Returns `null` if the manifest isn't available (entity/bytes unsynced, locked, or this
     * resolver doesn't support streaming — the default). The default impls keep non-streaming
     * resolvers (and test fakes) compiling unchanged; `OnDemandResolutionImpl` overrides them.
     */
    suspend fun openContentStream(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
    ): ContentStreamOpening? = null

    /**
     * Read up to [length] plaintext bytes at [offset] from the stream [handle] opened by
     * [openContentStream]. Returns the bytes (≤ the requested length; clamped at EOF), or `null`
     * if the handle is unknown/closed or a chunk in range fails its per-chunk integrity gate.
     */
    suspend fun readContentRange(
        handle: String,
        offset: Long,
        length: Int,
    ): ByteArray? = null

    /** Close the stream [handle] and release its decrypted-chunk buffers. Idempotent. */
    fun closeContentStream(handle: String): Boolean = false

    companion object {
        const val ID = "tenshu/core/onDemandResolution"
    }
}

/** The result of [OnDemandResolution.openContentStream]: an opaque stream handle + the file's size. */
data class ContentStreamOpening(
    val handle: String,
    val totalSize: Long,
)

fun TContext.onDemandResolution(): OnDemandResolution? =
    components[OnDemandResolution.ID] as? OnDemandResolution
