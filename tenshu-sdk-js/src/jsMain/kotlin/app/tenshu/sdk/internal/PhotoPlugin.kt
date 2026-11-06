package app.tenshu.sdk.internal

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory
import kotlin.js.Date

/**
 * Photo-service plugin: the write and read surface behind `@tenshu/sdk/photos` (`Photos.kt`,
 * `web-app-suite.md` Wave 2, Photos run A). Until this run photo was one of the F7 sync-only
 * entries in `SyncedServicePlugins.kt`, which carried schema only. This plugin keeps that schema and
 * adds the two things a read and write surface needs:
 *
 *  - **the operation factory**: the seven `:photo-service` ops (albums, entries, flags, the trash
 *    record). Each one is a store write, and none of them dials the relay, so the plugin sits in the
 *    pre-sync arms of the chain, like [StoragePlugin] and [TimelinePlugin];
 *  - **`initPhotoService`**: one line, `installRelationMaintainer()`, the generic observer that
 *    derives `PhotoService.{albums, albumEntries, photoFlags, trashedPhotos}` from the rows as they
 *    are saved or synced in.
 *
 * ⚠⚠ **No background work, by construction.** The maintainer is a singleton data observer
 * (idempotent: it returns early if one is already installed, and the contact and notification
 * plugins install it too). It runs inside a save and starts no job, timer or loop. Everything that
 * makes the native Photos app *do* things lives elsewhere and is deliberately absent here:
 *  - the backup drain (`:ingest`);
 *  - the thumbnail backfill (`:file-service`'s `BackfillThumbnailClasses`, never scheduled on web);
 *  - the media indexer and face grouping (`:inference`, still sync-only);
 *  - mint-on-view (`EnsureThumbnailClass`, not exposed by `Photos.kt`; `photos-app.md` §42.5).
 *
 * `PhotoPluginTest` holds the plugin to exactly that: no mergers, an op factory that answers the
 * seven photo ids and nothing else.
 *
 * ⚠ No mergers, same as the sync-only entry it replaces: every photo row is last-writer-wins
 * natively too (the three `PlatformProxyModule`s register none for this service).
 *
 * ⚠ `nowEpochMs` is the wall clock, as on desktop/iOS. The `{ 0L }` default would stamp every
 * album's `createdAt` and every trash record's `trashedAt` at 1970, so "newest first" would sort
 * nothing.
 */
internal object PhotoPlugin : JsRuntimePlugin {
    override val id: String = "photo"

    override val schemaConfigs =
        listOf(photoServiceCommonModule, photoServiceClientModule)

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id ->
            photoServiceProxyImplementationFactory(id, nowEpochMs = { Date.now().toLong() })
        }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        executionContext.initPhotoService()
    }
}
