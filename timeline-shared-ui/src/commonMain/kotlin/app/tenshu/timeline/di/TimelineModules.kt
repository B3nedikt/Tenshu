package app.tenshu.timeline.di

import org.tenshu.preference.client.TenshuOwnerProfile
import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.FileTimelineImporter
import app.tenshu.timeline.data.TakeoutPhotoImporter
import app.tenshu.timeline.data.TimelineProjectionRepository
import app.tenshu.reachability.MutableSchemaSkewSource
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.ui.screens.home.TimelineHomeViewModel
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.place.PlaceDetailViewModel
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus
import org.tenshu.core.common.TenshuClient

/**
 * Koin assembly for the Timeline app (docs/proposals/timeline-app.md §7 Ph6). Both app
 * shells (`:timeline-desktop`, `:timeline-android`) `startKoin { modules(timelineModules()) }`.
 *
 * The repository is bound behind its interface — the real on-device store replaces
 * [MockTimelineRepository] here with no VM/UI change (§2 seam). Ph3 adds the view models.
 */
fun timelineModules(): List<Module> = listOf(
    // The app runs over the real services hosted in **Control** (timeline-app.md §10). Swap to
    // `timelineRepositoryModule()` for the pure-mock app.
    timelineRealRepositoryModule(),
    viewModelsModule(),
)

/** The original pure-mock binding — kept for a one-line revert / previews / tests. */
fun timelineRepositoryModule(): Module = module {
    single<TimelineRepository> { MockTimelineRepository() }
    // ⚠⚠ Bound here too, even though a mock graph has no announce to mirror.
    // `timelineClientModule` resolves this with `get`, not `getOrNull`, so a graph missing it
    // fails loudly rather than silently reporting every skewed device as a transport outage —
    // and the price of that choice is that BOTH repository modules must bind it (SK-1b).
    single { MutableSchemaSkewSource() }
}

/**
 * The real binding (`timeline-app.md` §10): a [TimelineProjectionRepository] over the **Control**
 * proxy's [org.tenshu.core.common.TenshuClient] — the client `timelineClientModule` builds, so an
 * app shell must include that module (both do). Moments, places and activity rollups land in
 * Control's durable, synced store; Timeline keeps no store of its own. The `TimelineRepository`
 * seam is unchanged, so no VM or screen moved.
 *
 * **No first-run seeding.** This used to fire a fixture import (`ReferenceTimelineSource`) at every
 * launch, which was harmless when the store was a throwaway in-memory one — it was the only way the
 * screens had content. Against Control's *durable, synced* store it would write fabricated moments
 * into the user's real realm and push them to every paired device, so it is gone. Real content
 * arrives the way it is meant to: the Google-Timeline file import, or live location capture. The
 * fixture lives on for tests and [timelineRepositoryModule].
 */
fun timelineRealRepositoryModule(): Module = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // probeScope: the reachability probe (fit-app.md §9.14 recipe) runs on the app scope.
    // wifi: getOrNull — only Android binds the fingerprint seams, and it is the rename-time
    // network binding (§8.4 F2); absent, rename behaves exactly as before.
    // B2: one binding both shells resolve, so the phone and the desktop import through exactly the
    // same object (the enricher is `getOrNull` — only platforms with a geocoder bind one).
    single { FileTimelineImporter(get(), get(), getOrNull()) }
    // B5: the photo half, resolved only when the user has switched it on.
    single { TakeoutPhotoImporter(get()) }
    // SK-1b: the seam between the schema announce and the shell banner's words. A single, because
    // the repository reads it and the client module writes it, in whichever order Koin resolves.
    single { MutableSchemaSkewSource() }
    single<TimelineRepository> {
        TimelineProjectionRepository(
            get(), get(),
            probeScope = get(),
            wifi = getOrNull(),
            // The owner's units, read over the same client (fit-app.md §18.13).
            ownerProfile = TenshuOwnerProfile(get()),
            // The place-name opt-in gates the transit-stop + picker lookups (both reach Photon).
            // getOrNull: a platform that binds no control gets no lookups — absence is NO.
            geocodingConsent = getOrNull(),
            // Why it is unreachable, when the answer is "the two sides disagree on a schema
            // version" rather than "the channel is down" (SK-1b).
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
}

fun viewModelsModule(): Module = module {
    viewModel { TimelineHomeViewModel(get()) }
    viewModel { (tripId: String) -> TripDetailViewModel(get(), getOrNull<RegionBundleResolver>(), tripId) }
    viewModel { (momentId: String) -> MomentDetailViewModel(get(), momentId, getOrNull<RegionBundleResolver>()) }
    viewModel { (placeId: String) -> PlaceDetailViewModel(get(), placeId, getOrNull<RegionBundleResolver>()) }
}
