package app.tenshu.fit.di

import app.tenshu.fit.data.FitProjectionRepository
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthFilePicker
import app.tenshu.fit.data.HealthImportBootstrap
import app.tenshu.fit.data.HealthImporter
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.NoHealthSource
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.data.TenshuFitGoalPreferences
import app.tenshu.reachability.MutableSchemaSkewSource
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus
import org.tenshu.core.getOrNull
import org.tenshu.core.common.TenshuClient
import app.tenshu.fit.data.TenshuAutoDetectPreferences
import app.tenshu.fit.data.NoopAutoDetectPreferences
import app.tenshu.fit.data.AutoDetectPreferences
import app.tenshu.fit.data.AutoDetectBootstrap
import org.tenshu.preference.client.TenshuOwnerProfile
import app.tenshu.fit.data.MockRecordingEngine
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingEngineFactory
import app.tenshu.fit.data.RegionBundleResolver
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.ui.screens.datasource.BatchImportViewModel
import app.tenshu.fit.ui.screens.datasource.DataSourceDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.tenshu.fit.ui.AppViewModel
import app.tenshu.fit.ui.FirstDetectionViewModel
import app.tenshu.fit.ui.screens.browse.BrowseViewModel
import app.tenshu.fit.ui.screens.category.CategoryDetailViewModel
import app.tenshu.fit.ui.screens.category.CategoryMetric
import app.tenshu.fit.ui.screens.history.HistoryViewModel
import app.tenshu.fit.ui.screens.home.HomeViewModel
import app.tenshu.fit.ui.screens.medical.MedicalViewModel
import app.tenshu.fit.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.fit.ui.screens.profile.ProfileViewModel
import app.tenshu.fit.ui.screens.sharing.SharingWizardViewModel
import app.tenshu.fit.ui.screens.record.RecordViewModel
import app.tenshu.fit.ui.screens.workout.WorkoutDetailViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get

/**
 * Koin assembly for the Fit app (docs/proposals/fit-app.md Ph6). Both app shells
 * (`:fit-desktop`, `:fit-android`) `startKoin { modules(fitModules()) }`.
 *
 * The repository and recording engine are bound behind their interfaces — the real
 * `:health-service`-backed store and sensor pipeline replace [MockFitRepository] /
 * [MockRecordingEngine] here with no VM/UI change (§2 seam).
 */
fun fitModules(): List<Module> = listOf(realDataModule(), viewModelsModule())

/**
 * The real binding (docs/proposals/fit-app.md §10): a `FitProjectionRepository` over the **Control**
 * proxy's [TenshuClient] — the client `fitClientModule` builds, so an app shell must include that
 * module (both do). Health rows land in Control's durable, synced store; Fit keeps no store of its
 * own. The [FitRepository] seam is unchanged, so no VM or screen moved.
 *
 * [HealthImportBootstrap] refreshes from the platform health source on launch and on every
 * foreground (the shell calls `refresh`), but **only where a
 * real [HealthImporter] is bound** — with none (desktop) there is no source ([NoHealthSource]).
 * ⚠⚠ Never the sample fixture: a manual import would write it into Control's durable, synced
 * store and push fabricated workouts to every paired device (fit-app.md §9.15).
 *
 * Swap to [mockDataModule] for the pure-mock app (previews, a Control-less demo).
 */
fun realDataModule(): Module = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // SK-1 (`core-schema-skew-surface.md`): the seam between the schema announce and the shell
    // banner's words. A single, because the repository reads it and the client module writes it,
    // and they are constructed in whichever order Koin resolves them.
    single { MutableSchemaSkewSource() }
    // The import source (§4.2). A platform module may bind a HealthImporter (Android →
    // HealthConnectImporter, P5.2); absent one there is no source. `fileParser` is the
    // Takeout / GPX / TCX import (§9.6b), also platform-bound (it needs an XML reader).
    single<FitRepository> {
        FitProjectionRepository(
            client = get(),
            context = get(),
            importer = getOrNull() ?: NoHealthSource,
            fileParser = getOrNull(),
            // Goals + the owner's person as REALM preference rows (fit-app.md §18). Both read
            // through the same client, so there is nothing platform-specific to bind.
            ownerProfile = TenshuOwnerProfile(get()),
            goalPreferences = TenshuFitGoalPreferences(get()),
            // The durable recorder outbox (§9.13) — platform-bound (needs a storage directory);
            // Android and desktop bind one. Absent, a failed recorder save throws and the engine
            // retains the session instead.
            outbox = getOrNull(),
            // The reachability probe (§9.14) runs on the app scope: while Control is
            // unreachable it pings slowly, then re-subscribes the read streams + drains the
            // recorder outbox on recovery.
            probeScope = get(),
            // Why it is unreachable, when the answer is "the two sides disagree on a schema
            // version" rather than "the channel is down" (SK-1).
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            // SK-3: the realm pull fence — a device that is behind its own realm.
            schemaFence = get<MutableSchemaSkewSource>().fence,
            // SK-2: which binary to name when fenced — published by the proxy, not assumed here.
            proxyHost = get<MutableSchemaSkewSource>().host,
            // Fit's own detection setting — the Tenshu Health row's status (DS-6). Bound here
            // rather than in a view model because `DataSourceProjection` builds the row (D10).
            autoDetect = getOrNull<AutoDetectPreferences>(),
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
    // Eager: the import is the app's data-refresh pass, so it must run whether or not a screen asks
    // for it. `createdAtStart` fires it from `startKoin` — where InProcessFitProxy's `init` used to.
    // ⚠ A `single`, so the shell's per-foreground `refresh()` reaches the same instance and its
    // debounce actually debounces.
    single(createdAtStart = true) {
        HealthImportBootstrap(
            repository = get(),
            scope = get(),
            // The refresh is further gated on the importer's autoRefreshReady() — a grant-less
            // or platform-absent source never auto-imports (fixture must not reach the durable,
            // synced store; a permission abort must not park a permanent banner).
            importer = getOrNull(),
        )
    }
    // D1's preference pair. The device-id resolver is the same shape Calendar uses; Fit
    // registers :sync-service, so `getSyncStatus` resolves rather than throwing. A null id
    // defers the prompt — it never substitutes a key (see TenshuAutoDetectPreferences).
    single<AutoDetectPreferences> {
        TenshuAutoDetectPreferences(
            client = get(),
            resolveDeviceId = {
                runCatching { get<TenshuClient>().syncService.getSyncStatus().getOrNull()?.deviceId?.get() }
                    .getOrNull()
            },
        )
    }
    // Eager, beside HealthImportBootstrap and for the same reason: promotion is a data pass, not
    // something a screen asks for. Gated on the realm toggle, so it is inert until the user says
    // yes (fit-automatic-activity-detection.md D5).
    single(createdAtStart = true) {
        AutoDetectBootstrap(repository = get(), preferences = get(), scope = get())
    }
    // The recorder. A platform module may bind a RecordingEngineFactory (Android →
    // AndroidRecordingEngine over GPS/BLE-HR/steps, §9.8); absent one, the deterministic mock is
    // used (desktop/iOS). Mirrors the optional-HealthImporter seam above.
    single<RecordingEngine> { (getOrNull<RecordingEngineFactory>() ?: RecordingEngineFactory { MockRecordingEngine(it) }).create(get()) }
}

/** The original pure-mock binding — kept for a one-line revert / previews / tests. */
fun mockDataModule(): Module = module {
    single<FitRepository> { MockFitRepository() }
    single<AutoDetectPreferences> { NoopAutoDetectPreferences }
    single<RecordingEngine> { MockRecordingEngine(get()) }
    // ⚠⚠ Bound here too, even though a mock graph has no announce to mirror. `fitClientModule`
    // resolves this with `get`, not `getOrNull`, so that a graph missing it fails loudly rather
    // than reporting every skewed device as a transport outage — and the price of that choice is
    // that BOTH data modules must bind it. Fit is an un-built smoke canary, so a startup Koin
    // miss here would reach a user rather than a gate.
    single { MutableSchemaSkewSource() }
}

fun viewModelsModule(): Module = module {
    viewModel { AppViewModel(get()) }
    // D1's one-time prompt. ⚠ `getOrNull` on the preferences for the same reason Profile
    // does it: the mock graph binds none, and the Noop reports "already answered" so a
    // proxy-less surface never shows a prompt whose answer it could not record.
    viewModel {
        FirstDetectionViewModel(
            get(),
            getOrNull<AutoDetectPreferences>() ?: NoopAutoDetectPreferences,
        )
    }
    // The permission requester is bound only by a shell that can prompt (Android); absent it,
    // the banner's retry degrades to a plain re-import. Mirrors the optional-importer seam.
    viewModel {
        HomeViewModel(
            get(),
            getOrNull<HealthPermissionRequester>(),
            canImportFiles = getOrNull<HealthFilePicker>() != null,
        )
    }
    viewModel { HistoryViewModel(get()) }
    viewModel { (workoutId: String) -> WorkoutDetailViewModel(get(), getOrNull<RegionBundleResolver>(), workoutId) }
    viewModel { RecordViewModel(get()) }
    viewModel { BrowseViewModel(get()) }
    viewModel { (categoryId: CategoryId, metric: CategoryMetric) ->
        CategoryDetailViewModel(categoryId, get(), metric)
    }
    // DS-4. The device id is part of the key: with two phones in one realm there are two Health
    // Connect rows, and a VM keyed on kind alone would serve one device's screen from the other's.
    viewModel { (kind: DataSourceKind, deviceId: String?) ->
        DataSourceDetailViewModel(
            kind,
            deviceId,
            get(),
            getOrNull<HealthPermissionRequester>(),
            // Q1 (DS-6): auto-detection is Tenshu Health's own setting, so it binds here rather
            // than to Profile.
            getOrNull<AutoDetectPreferences>() ?: NoopAutoDetectPreferences,
        )
    }
    viewModel { BatchImportViewModel(get(), getOrNull<HealthFilePicker>()) }
    viewModel { MedicalViewModel(get()) }
    viewModel { OnboardingViewModel(get(), getOrNull<HealthPermissionRequester>()) }
    // ⚠ `getOrNull` for the preferences: `viewModelsModule` is shared by the real and mock
    // data graphs, and only the real one binds an AutoDetectPreferences. The Noop default is
    // what keeps the mock app and previews from needing a proxy to render Profile.
    viewModel {
        ProfileViewModel(
            get(),
            getOrNull<HealthFilePicker>(),
            getOrNull<HealthPermissionRequester>(),
        )
    }
    viewModel { SharingWizardViewModel(get(), getOrNull<HealthPermissionRequester>()) }
}
