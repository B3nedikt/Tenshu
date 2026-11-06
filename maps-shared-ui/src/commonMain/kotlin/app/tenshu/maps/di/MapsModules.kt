package app.tenshu.maps.di

import app.tenshu.maps.data.ClientRegionRepository
import app.tenshu.maps.data.MockPlaceRepository
import app.tenshu.maps.data.PlaceProjectionRepository
import app.tenshu.maps.data.mapsControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.ui.screens.detail.PlaceDetailViewModel
import app.tenshu.maps.ui.screens.places.PlacesViewModel
import app.tenshu.maps.ui.screens.regions.RegionPickerViewModel
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
 * Koin assembly for the Maps app (docs/proposals/maps-app.md §6). Both app shells
 * (`:maps-desktop`, `:maps-android`) `startKoin { modules(mapsModules()) }`.
 *
 * The repository is bound behind its interface — the real on-device `:place-service` store
 * replaces [MockPlaceRepository] here with no VM/UI change (Ph9 swap).
 */
fun mapsModules(): List<Module> = listOf(
    placeRepositoryModule(),
    regionRepositoryModule(),
    viewModelsModule(),
)

/**
 * The real binding (`maps-app.md` §7): a [app.tenshu.maps.data.PlaceProjectionRepository] over the
 * **Control** proxy's [TenshuClient] — the same `PlaceRepository` seam the mock used, zero VM/UI
 * change. `:place-service` is hosted in Control (as `MapRegionService` already was), so places are
 * durable, synced, and **shared with Timeline** — which is the point: a place is a place, whichever
 * app is looking at it.
 *
 * **No mock-POI seeding.** This used to seed demo POIs into the in-process store at every launch,
 * which was the only way the screens had content when that store was a throwaway. Against Control's
 * durable, synced store it would write fabricated places into the user's real realm and push them to
 * every paired device, so it is gone (`MapsPlaceSeeder` survives for tests and the demo). Real places
 * come from Timeline's ingestion — visits become places in the shared `:place-service`.
 *
 * ⚠ **Thin-screens tradeoff** (documented on `MapsPlaceProjection`): places land under Yours
 * (Saved / For-you empty) until `:place-service` graduates the rich fields. Swap to
 * [mockPlaceRepositoryModule] for the rich demo.
 */
fun placeRepositoryModule(): Module = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // The app's ONE reachability engine (fit-app.md §9.14 recipe): probe on the app scope;
    // the place AND region repositories feed the same shell-banner signal.
    // SK-1b: the seam between the schema announce and the shell banner's words. A single, because
    // the engine reads it and the client module writes it, in whichever order Koin resolves.
    single { MutableSchemaSkewSource() }
    single {
        mapsControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
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
    single<PlaceRepository> { PlaceProjectionRepository(get<TenshuClient>(), reach = get()) }
}

/** The original pure-mock binding — kept for a one-line revert / the rich demo / previews. */
fun mockPlaceRepositoryModule(): Module = module {
    single<PlaceRepository> { MockPlaceRepository() }
    // ⚠⚠ Bound here too, even though a mock graph has no announce to mirror. `mapsClientModule`
    // resolves this with `get`, not `getOrNull`, so a graph missing it fails loudly rather than
    // silently reporting every skewed device as a transport outage — and the price of that
    // choice is that BOTH repository modules must bind it (SK-1b).
    single { MutableSchemaSkewSource() }
}

/**
 * Binds [RegionRepository] over the real IPC [TenshuClient] that reaches Control's proxy
 * (`maps-region-delivery.md` D6): the picker now talks to the `MapRegionService` hosted in Control,
 * not the in-process dev graph. The client is provided by `mapsClientModule` + the platform
 * `platformClientModule` (Binder / UDS). The in-process `InProcessMapRegionProxy` is retained as
 * the headless integration-test harness (`RegionPickerPipelineTest`).
 */
fun regionRepositoryModule(): Module = module {
    // The platform RegionBundleStager (Android filesDir / desktop cache) is bound in
    // platformClientModule; getOrNull keeps the headless graph (no stager) rendering the demo.
    single<RegionRepository> { ClientRegionRepository(get<TenshuClient>(), getOrNull(), reach = get()) }
}

fun viewModelsModule(): Module = module {
    viewModel { PlacesViewModel(get()) }
    viewModel { (placeId: String) -> PlaceDetailViewModel(get(), get(), placeId) }
    viewModel { RegionPickerViewModel(get()) }
}
