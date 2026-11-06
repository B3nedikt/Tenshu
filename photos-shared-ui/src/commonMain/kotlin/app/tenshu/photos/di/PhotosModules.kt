package app.tenshu.photos.di

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotosRepository
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.data.controlCurrentRealm
import app.tenshu.photos.data.photosControlReachability
import app.tenshu.photos.ui.screens.albumdetail.AlbumDetailViewModel
import app.tenshu.photos.settings.GridMotionSettings
import app.tenshu.photos.settings.GridMotionSettingsStore
import app.tenshu.ingest.IngestSettings
import app.tenshu.ingest.IngestSettingsStore
import app.tenshu.ingest.IngestSourceRegistry
import app.tenshu.ingest.IngestSources
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.settings.SettingsViewModel
import app.tenshu.photos.ui.screens.duplicates.DuplicatesViewModel
import app.tenshu.photos.ui.screens.search.PhotoSearchViewModel
import app.tenshu.photos.ui.screens.trash.TrashViewModel
import app.tenshu.photos.time.PhotoDates
import app.tenshu.photos.ui.screens.feed.FeedFirstPage
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.viewer.PhotoViewerViewModel
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus

/**
 * Koin assembly for the Photos app. Both shells start
 * `startKoin { modules(listOf(photosClientModule, platformClientModule) + photosModules()) }`.
 *
 * The repository is bound behind its interface, so [mockPhotosRepositoryModule] is a one-line swap
 * for previews and render tests — the `contactsModules` precedent (contacts-app.md D8).
 */
fun photosModules(): List<Module> = listOf(
    photosRepositoryModule(),
    viewModelsModule(),
)

fun photosRepositoryModule(): Module = module {
    // The shared ingest-source registry (`:app-ingest`), the same one Drive's drain writes.
    single<IngestSources> { IngestSourceRegistry(get<TenshuClient>()) }
    single<IngestSettings> { IngestSettingsStore(get<TenshuClient>()) }
    // The feed's motion switch — Photos' own row, read by the shell and written by Settings.
    single<GridMotionSettings> { GridMotionSettingsStore(get<TenshuClient>()) }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // The app's ONE reachability engine (the `fit-app.md` §9.14 recipe): one signal feeding the
    // feed's error state, rather than one per read.
    // The seam between the schema announce and the shell banner's words. A single, because
    // the engine reads it and the client module writes it, in whichever order Koin resolves.
    single { MutableSchemaSkewSource() }
    single {
        photosControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            schemaFence = get<MutableSchemaSkewSource>().fence,
            proxyHost = get<MutableSchemaSkewSource>().host,
            // ADR 0025 §Decision 8: Control installed and answering, no realm on this device → the
            // wall. The same two reads as Control's landing router; `get()` throws on a failed read,
            // which the engine reads as "cannot tell".
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(get<TenshuClient>().let { client ->
                {
                    realmPresence(
                        isLocked = client.syncService.getUnlockStatus().get().isLocked.get(),
                        ownerId = client.syncService.getSyncStatus().get().ownerId?.get(),
                    )
                }
            }),
        )
    }
    single<PhotosRepository> {
        TenshuPhotosRepository(get<TenshuClient>(), reachability = get())
    }
}

/** Previews, desktop render tests, and anything that must not need a running Control. */
fun mockPhotosRepositoryModule(): Module = module {
    single<PhotosRepository> { MockPhotosRepository() }
}

fun viewModelsModule(): Module = module {
    // ⚠ The zone functions are supplied here rather than read by the view model, so its tests need
    // no calendar. ⚠⚠ The shells must pass the real ones (`PhotoDates`, shared with iOS): the
    // view model's UTC-ish default puts a photograph taken late on 31 December under the wrong
    // year — for the feed's headers and for the scrubber's pills.
    // §38: the feed marks its first rows on this; Collections' first refresh waits for it. ⚠ ONE,
    // shared — two instances would leave Collections waiting on a mark that never comes.
    single { FeedFirstPage() }
    viewModel { PhotosFeedViewModel(get(), PhotoDates::sectionStart, PhotoDates::yearOf, firstPage = get()) }
    viewModel { (photo: app.tenshu.photos.model.Photo) -> PhotoViewerViewModel(get(), photo) }
    // ⚠ The real, calendar-aware year (the `groupIntoDays` precedent).
    // §36 step 2: `getOrNull` — each platform module binds its local snapshot store; a graph without
    // one (previews, render tests) simply starts from placeholders. The realm is Control's answer.
    // §38: the first live refresh waits for the feed's first rows; the snapshot does not.
    viewModel {
        CollectionsViewModel(
            get(),
            yearOf = PhotoDates::yearOf,
            startAfter = get<FeedFirstPage>(),
            snapshotStore = getOrNull(),
            currentRealm = getOrNull<TenshuClient>()?.let { controlCurrentRealm(it) },
        )
    }
    // The last two are platform-optional — a device that cannot enumerate its own folders
    // (desktop) binds neither, and Settings then offers no picker rather than an empty one.
    viewModel { SettingsViewModel(get(), get(), getOrNull(), getOrNull(), get()) }
    // ⚠ `onChanged` from the route: a restore or delete changes what Collections shows, and
    // without the hook the tab comes back stale. Absent (the Settings route) is a no-op.
    viewModel { params -> TrashViewModel(get(), onChanged = params.getOrNull<() -> Unit>() ?: {}) }
    // ⚠ The same hook, for the same reason: a keep-only trashes or deletes photographs (§8.18).
    viewModel { params -> DuplicatesViewModel(get(), onChanged = params.getOrNull<() -> Unit>() ?: {}) }
    viewModel { PhotoSearchViewModel(get()) }
    viewModel { (albumId: String, title: String) -> AlbumDetailViewModel(get(), albumId, title) }
}
