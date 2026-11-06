package app.tenshu.photos.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.flow.map
import org.tenshu.core.common.app.wireSchemaAnnounce
import app.tenshu.photos.ui.components.isMotionPlaybackSupported
import app.tenshu.photos.data.declarePhotosWantedThumbnailClasses
import org.tenshu.sync.client.runtimeIdentity
import org.tenshu.sync.client.schemaFence
import org.tenshu.sync.client.syncService
import org.tenshu.sync.fencedServices
import org.tenshu.sync.isControlHosted
import org.tenshu.core.common.createRemoteTenshuEnvironment
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.RemoteTaskExecutor
import org.tenshu.core.common.request.TaskExecutor
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule

/**
 * Every service surface Photos' client graph registers.
 *
 * ⚠⚠ **A top-level val so a test can hold it.** `PhotosClientGraphTest` asserts that every service
 * this app's repositories reach is listed here. A missing one fails *quietly*:
 * `TenshuEnvironment.getService` errors on an unregistered schema and every caller catches, so
 * Places renders empty, Settings says "Can't reach Tenshu Control", and the duplicate-skip toggle
 * reads its default. Nothing crashes; nothing says why.
 */
val photosSchemaConfigs: List<ModuleConfiguration> = listOf(
        fileServiceCommonModule,
        fileServiceClientModule,
        // Albums, ordering and flags. ⚠ Both halves — the common module lets the client
        // materialise a row, the client module builds the ops' request objects.
        photoServiceCommonModule,
        photoServiceClientModule,
        // ⚠⚠ Each one below backs a screen that reads it, and a missing one fails silently (see
        // above); `PhotosClientGraphTest` fails if a service the app reaches is not listed here.
        //
        // The Places section joins the feed against `Place` rows.
        placeServiceCommonModule,
        placeServiceClientModule,
        // The shared ingest-source registry behind the Settings tab.
        ingestServiceCommonModule,
        ingestServiceClientModule,
        // The skip-duplicates setting, and the device identity every per-device row is
        // keyed on (`DeviceIdentity` reads the sync status row).
        preferenceServiceCommonModule,
        preferenceServiceClientModule,
        syncServiceCommonModule,
        syncServiceClientModule,
        // Search (`photos-app.md` S2): the label and semantic sources. ⚠ Registered even when
        // the device has no backend — an absent *model* must surface as `photosLike`'s typed
        // refusal, not as "no schema registered", which the repository would swallow as `Failed`.
        inferenceServiceCommonModule,
        inferenceServiceClientModule,
        // The realm-wide storage answer, for `RealmStorageBannerHost`. Photos has the strongest
        // claim of any app to say this out loud: it exists to put photos somewhere safe, and an
        // unfunded realm means the one thing it is for is silently not happening — `StorageSyncGate`
        // holds the personal realm's push at the scheduler until a plan is bound.
        org.tenshu.storage.common.storageServiceCommonModule,
        org.tenshu.storage.client.storageServiceClientModule,
)

/**
 * Photos' client graph: a `TenshuClient` that reaches the **Control** proxy over IPC (Android
 * Binder / desktop UDS).
 *
 * Two service surfaces. **`:file-service`** — because a photo *is* a file, and the feed is a chain
 * read over the same rows Drive lists and the backup drain writes: there is one library, and Photos
 * is a second way of looking at it rather than a second copy of it. **`:photo-service`** — what the
 * user has *said* about those photos (albums, order, favourites), which is the only thing this app
 * owns.
 *
 * The platform half (`platformClientModule`, per source set) provides the `RemoteTaskExecutor`, the
 * transport, and the platform-built `TenshuClient` (named `platformTenshuClient`).
 */
val photosClientModule = module {
    includes(coreCommonModule())

    single<List<ModuleConfiguration>>(named("coreSchemaConfigs")) {
        photosSchemaConfigs +
            (getOrNull<List<ModuleConfiguration>>(named("platformSchemaConfigs")) ?: emptyList())
    }

    single<TaskExecutor> { get<RemoteTaskExecutor>() }

    single {
        createRemoteTenshuEnvironment(
            tContext = get(),
            remoteTaskExecutor = get<RemoteTaskExecutor>(),
            schemaRegistry = get(),
        )
    }

    single<TenshuClient> {
        get<TenshuClient>(named("platformTenshuClient")).also { client ->
            // Schema announce (core-schema-evolution.md §6.4): the executor's session preflight, so
            // it rides ahead of the session's first call and every post-reconnect resubscribe.
            // ⚠ At launch, not on the first operation — this app's opening screen issues a read
            // immediately, but an announce that waits for one would never register a shell that
            // opens on an error state.
            val announcer = wireSchemaAnnounce(
                client = client,
                executor = get<RemoteTaskExecutor>(),
                appId = "app.tenshu.photos",
                tContext = get(),
                schemaProvider = get<SchemaRegistry>(),
                launchScope = CoroutineScope(Dispatchers.Default),
            )
            // The announcer's refusal verdict feeds the skew surface
            // (`core-schema-skew-surface.md`). Unbound, a schema-skewed Photos says "Can't reach
            // Tenshu Control", which is both wrong and unactionable: the proxy is answering, and
            // refusing.
            //
            // ⚠ Bound into a source the repository already holds rather than passed to it: the
            // repository is built from its own module and may exist before this single resolves.
            get<MutableSchemaSkewSource>().bind(announcer, get<CoroutineScope>())
            // Which binary owns the runtime, and this device's realm pull fence. Bound
            // on the same object as the verdict so one forgotten bind cannot leave the app silently
            // unable to mention a problem it is already detecting.
            get<MutableSchemaSkewSource>().bindHost(
                client.syncService.runtimeIdentity().map { it.isControlHosted() },
                get<CoroutineScope>(),
            )
            get<MutableSchemaSkewSource>().bindFence(
                client.syncService.schemaFence().map { it?.fencedServices() ?: emptyList() },
                get<CoroutineScope>(),
            )
            // Say which rungs this app draws, at launch (`drive-synced-thumbnails.md` §15.11). The
            // list — and why each rung is on it — is shared with iOS
            // (`photosWantedThumbnailClasses`).
            // ADR 0025 §8.6: a synced, device-scoped write at launch — through the realm gate.
            // Resolved lazily: this runs while the client single itself is being built.
            val koinScope = this
            get<CoroutineScope>().declarePhotosWantedThumbnailClasses(
                client = client,
                motionPlaybackSupported = isMotionPlaybackSupported(),
                realmGate = { koinScope.getOrNull<app.tenshu.reachability.ControlReachability>() },
            )
        }
    }
}
