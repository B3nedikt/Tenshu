package org.tenshu.sync.proxy.impl

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ContentStreamOpening
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.entity.DataId
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RealmRegistryHolder
import org.tenshu.sync.proxy.StreamingContentRegistry
import org.tenshu.sync.proxy.SyncProcessor
import org.tenshu.sync.proxy.SyncProcessorHolder

/**
 * Concrete [OnDemandResolution] for `Syncable(OnDemand)`-marked relations:
 * pulls every block the relay holds for the parent entity via
 * [SyncRelay.fetchEntityBlocks] and feeds each through the session's
 * [org.tenshu.sync.proxy.SyncProcessor.processSyncBlockAsync] (resolved
 * at call-time from the [SyncProcessorHolder] populated by
 * `initSyncService`). The *async* ingest is used deliberately: it is
 * the one place a `FileContentManifestPayload` is applied — the
 * opened file's chunks are fetched from the content plane + decrypted
 * + integrity-gated + reassembled into `FileDatabase` (the P2 read
 * path; `docs/adr/0017-file-content-two-plane-channel.md`). Because the
 * property identity lives inside the decrypted payload (zero-trust:
 * `docs/sync/threat-model.md` §"Property-ID exposure to relay"), the
 * relay can't filter to "just the property we asked for" — the
 * resolver downloads all blocks for the entity and lets the processor
 * route them by their decoded `DataBlockPayload.propertyId`. After
 * this returns, the terminal call site
 * (`Maybe.get()` via [org.tenshu.core.common.proxy.MaybeGetImplementation],
 * or `.flow()`) re-resolves the relation against the local database to
 * replace its [org.tenshu.core.common.proxy.PendingOnDemandProperty]
 * placeholder with the now-materialised value.
 *
 * ⚠ The processor is the session's, never one constructed per
 * `resolve()`: a fresh `SyncProcessor` defaults its `TombstoneRegistry`
 * to `TombstoneRegistry(executionContext)`, whose init block registers
 * itself as a `DataObserver`. Stream emissions go through the same
 * materialiser, so resolution fires inside stream `onUpdate` coroutines —
 * concurrent with the `saveData` that triggered the stream — and that
 * `addDataObserver` would mutate `observerRegistry` mid-iteration →
 * `ConcurrentModificationException`.
 *
 * Bandwidth note: fetching all entity blocks costs N blocks per fetch
 * where N is the entity's fan-out (entity + properties). For File that's
 * ~3 blocks (entity, Data, Preview); the targeted Data ask still
 * downloads the Preview alongside. Negligible for current shapes; if
 * fan-out grows, a different indexing mechanism is needed (e.g. opaque
 * keyed property hashes) — separate track.
 *
 * Registered as a [org.tenshu.core.core.TComponent] under
 * [OnDemandResolution.ID] in the proxy's Koin module, so
 * `context.components[OnDemandResolution.ID]` resolves to this instance.
 *
 * **Realm routing (drive-shared-realm-file-model.md §6).** When
 * [realmRegistryHolder] is wired and [entityId]'s key carries a registered
 * shared realm's `"$realmId::"` prefix, the fetch + ingest are routed through
 * *that* realm's runtime — its own [SyncRelay] (JWT scoped to the realm's
 * `ownerId`, so `fetchEntityBlocks` hits the realm's content keyspace) and its
 * own [org.tenshu.sync.proxy.SyncProcessor] (whose `FileContentManifestApplier`
 * decrypts under the realm's keys + writes the realm content store).
 * A personal entity — or no registry / an unregistered realm / a realm not yet
 * given both a relay and a processor — keeps the personal relay + processor.
 * The holder defaults to `null` for wirings that don't sync shared realms.
 */
class OnDemandResolutionImpl(
    private val relay: SyncRelay,
    private val syncProcessorHolder: SyncProcessorHolder,
    private val realmRegistryHolder: RealmRegistryHolder? = null,
    /**
     * Told about every pull before it is served (`drive-synced-thumbnails.md` §15.2 — *the pull is
     * the declaration*). Null, the default, observes nothing; see [OnDemandPullObserver] for the
     * contract, including why it must be cheap and may never fail the fetch.
     */
    private val onPull: OnDemandPullObserver? = null,
) : OnDemandResolution {

    /** Decrypted-chunk stream sessions opened by [openContentStream], keyed by handle (D2b). */
    private val contentStreams = StreamingContentRegistry()

    /**
     * Resolve the (relay, processor) runtime for [entityId]: a shared-realm entity routes to
     * its own runtime (realm relay + realm-key-aware processor), everything else to the personal
     * relay + processor. `null` when no processor is available. Shared by [resolve] (reassemble)
     * and [openContentStream] (stream) so both route identically.
     */
    private fun routeRuntime(entityId: DataId): Pair<SyncRelay, SyncProcessor>? {
        val registry = realmRegistryHolder?.realmRegistry
        val realm = registry?.realmForDataId(entityId)
        val runtime =
            if (registry != null && realm != null && !registry.isPersonal(realm)) {
                registry.runtimeFor(realm.ownerId)
            } else {
                null
            }
        val useRealm = runtime?.relay != null && runtime.processor != null
        val effectiveRelay: SyncRelay = if (useRealm) runtime!!.relay!! else relay
        val processor = (if (useRealm) runtime!!.processor else syncProcessorHolder.processor)
            ?: return null
        return effectiveRelay to processor
    }

    override suspend fun resolve(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
        params: TList<Data>?
    ): TResult<Data?> {
        // ⚠ Before routing, so the observer sees the pull whether or not a processor exists yet:
        // what it records is that this device *asked*, which is true either way.
        notifyPull(executionContext, entityId, propertyId)

        // Route by the entity's realm (shared vs personal); no processor ⇒ clean no-op success.
        val (effectiveRelay, processor) = routeRuntime(entityId)
            ?: return TResult.Success.create<Data?>(
                context = executionContext.context,
                value = null
            )

        // Seed the reverse index with this entity's DataId before applying its blocks.
        // The OnDemand byte-fetch is the one place a *reader* holds the entity's real
        // DataId, so it can repair the `idHash → DataId` mapping that
        // FileContentManifestApplier needs to locate the file. That mapping is otherwise
        // written only on a live save (TombstoneRegistry.onDataSaved) and never rebuilt
        // from the non-enumerable store — so an entity hydrated from disk at startup, but
        // not re-saved this session, would have no mapping and its bytes would silently
        // fail to materialise. Seeding here makes the index self-heal on the exact access
        // that needs it. Idempotent (no-op once the mapping exists). See
        // TombstoneRegistry.rememberDataId.
        processor.rememberEntity(entityId)

        // Content addressing (file-content-addressing.md §8.3): a content-
        // addressed file's manifest lives on the content plane keyed by its stable
        // `contentId` (not in the mailbox), so fetch it by contentId — stable across a
        // move — via the ExternalPropertyWriter port. A null contentId (shared-realm
        // file, whose id is already stable; a legacy/other OnDemand property) falls back
        // to the entity-id block fetch below.
        val contentId = executionContext.context.externalPropertyWriter()
            ?.resolveContentId(executionContext, entityId, propertyId)
        if (contentId != null) {
            // ⚠ `propertyId` is threaded so content repair can re-emit the *same* property if the manifest
            // turns out to be missing. This is the only place that holds it alongside the entity,
            // which is why the repair hangs off this call rather than off the ingest path.
            processor.resolveContentManifest(entityId, contentId, propertyId)
            return TResult.Success.create<Data?>(
                context = executionContext.context,
                value = null
            )
        }

        // ⚠ By the entity's wire id under the routed realm (B3 wire spec §11.3) — never its `DataId`,
        // which carried the path. No realm key ⇒ nothing the relay could match: a clean no-op.
        val wireId = processor.wireIdOf(entityId)
            ?: return TResult.Success.create<Data?>(context = executionContext.context, value = null)
        val result = effectiveRelay.fetchEntityBlocks(wireId)
        if (result is TResult.Success) {
            // Suspend ingest: a DataBlockPayload applies exactly as on
            // the eager drain; a FileContentManifestPayload is applied
            // here (and ONLY here) — chunks are fetched + decrypted +
            // integrity-gated + reassembled into FileDatabase. The
            // resolver scoped `fetchEntityBlocks` to this one entity,
            // so only the opened file's chunks are pulled
            // (`docs/adr/0017-file-content-two-plane-channel.md` selective fetch).
            val blocks: TList<SyncBlock>? = result.value
            blocks?.toList()?.forEach { block ->
                processor.processSyncBlockAsync(block)
            }
        }

        if (result is TResult.Failure) {
            @Suppress("UNCHECKED_CAST")
            return result as TResult<Data?>
        }
        return TResult.Success.create<Data?>(
            context = executionContext.context,
            value = null
        )
    }

    private suspend fun notifyPull(executionContext: ExecutionContext, entityId: DataId, propertyId: String) {
        val observer = onPull ?: return
        try {
            observer.onPull(executionContext, entityId, propertyId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A bookkeeping hook never fails the fetch it observed; the next pull is another chance.
        }
    }

    override suspend fun openContentStream(
        executionContext: ExecutionContext,
        entityId: DataId,
        propertyId: String,
    ): ContentStreamOpening? {
        // Same realm-routed fetch as resolve(), but capture the manifest into a streaming
        // session instead of reassembling the whole file to disk (drive-streaming-preview.md §3.5).
        val (effectiveRelay, processor) = routeRuntime(entityId) ?: return null
        // Content addressing: stream from the content-plane manifest by contentId
        // (stable across a move); fall back to the entity-id block fetch for a
        // non-content-addressed (shared-realm / legacy) file.
        val contentId = executionContext.context.externalPropertyWriter()
            ?.resolveContentId(executionContext, entityId, propertyId)
        val session = if (contentId != null) {
            processor.openContentStreamByContentId(contentId, propertyId)
        } else {
            val wireId = processor.wireIdOf(entityId) ?: return null
            val blocks = (effectiveRelay.fetchEntityBlocks(wireId) as? TResult.Success)?.value
                ?.toList() ?: return null
            processor.openContentStream(blocks, propertyId)
        } ?: return null
        val handle = contentStreams.open(session)
        return ContentStreamOpening(handle = handle, totalSize = session.totalSize)
    }

    override suspend fun readContentRange(handle: String, offset: Long, length: Int): ByteArray? =
        contentStreams.get(handle)?.readRange(offset, length)

    override fun closeContentStream(handle: String): Boolean = contentStreams.close(handle)
}
