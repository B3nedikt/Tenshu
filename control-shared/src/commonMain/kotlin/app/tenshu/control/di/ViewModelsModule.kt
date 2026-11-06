package app.tenshu.control.di

import app.tenshu.control.data.SyncRepository
import app.tenshu.control.epc.EpcPairingViewModel
import app.tenshu.control.ui.screens.apps.AppDetailViewModel
import app.tenshu.control.ui.screens.apps.AppsViewModel
import app.tenshu.control.ui.screens.data.DataViewModel
import app.tenshu.control.ui.screens.home.HomeScreenViewModel
import app.tenshu.control.ui.screens.settings.ingest.BackgroundIngestViewModel
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.ui.screens.home.OfflineMapsSuggestionViewModel
import app.tenshu.control.ui.screens.home.QuotaSoftLimitViewModel
import app.tenshu.control.ui.screens.home.RecoveryAffordanceViewModel
import app.tenshu.control.ui.screens.home.SchemaFenceBannerViewModel
import app.tenshu.control.ui.screens.notifications.NotificationsViewModel
import app.tenshu.control.ui.screens.locked.LockedViewModel
import app.tenshu.control.ui.screens.newuser.NewUserViewModel
import app.tenshu.control.ui.screens.recovery.RecoveryViewModel
import app.tenshu.control.ui.screens.services.CheckoutViewModel
import app.tenshu.control.ui.screens.services.AccountDeletionViewModel
import app.tenshu.control.ui.screens.services.DisconnectConfirmViewModel
import app.tenshu.control.ui.screens.services.FreeAccountSignupViewModel
import app.tenshu.control.ui.screens.services.ReiKeyViewModel
import app.tenshu.control.ui.screens.services.ServicesViewModel
import app.tenshu.control.ui.screens.services.StorageStatusModel
import app.tenshu.control.ui.screens.settings.SettingsViewModel
import app.tenshu.control.ui.screens.settings.errors.ErrorsViewModel
import app.tenshu.control.inference.IndexCoverageSource
import app.tenshu.control.inference.IndexProgressSource
import app.tenshu.control.inference.ModelStoreAccessSource
import app.tenshu.control.ui.screens.models.ClientModelsDevices
import app.tenshu.control.ui.screens.models.ClientModelsRepository
import app.tenshu.control.ui.screens.models.ModelsRepository
import app.tenshu.control.ui.screens.models.ModelsViewModel
import app.tenshu.control.ui.screens.offlinemaps.ClientOfflineMapsRepository
import app.tenshu.control.ui.screens.offlinemaps.OfflineMapsRepository
import app.tenshu.control.ui.screens.offlinemaps.OfflineMapsViewModel
import app.tenshu.control.ui.screens.settings.security.SecuritySettingsViewModel
import app.tenshu.control.ui.screens.shares.SharesViewModel
import app.tenshu.control.ui.screens.settings.backgroundsync.BackgroundSyncSettingsViewModel
import app.tenshu.control.ui.screens.settings.unlock.UnlockSettingsViewModel
import app.tenshu.control.ui.screens.sync.PairingWizardViewModel
import app.tenshu.control.ui.screens.sync.SyncDashboardViewModel
import app.tenshu.control.ui.screens.unlock.SetupUnlockViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService

/**
 * The synced `StorageStatus` projected to the Services/checkout view model shape. Derived from the
 * synced `Entitlement`, so it reflects a subscription regardless of which device bought it — the
 * authoritative signal the checkout flow watches for the purchased tier's quota to land.
 */
private fun Scope.storageStatusModelFlow(): Flow<StorageStatusModel?> =
    get<TenshuClient>().storageService.status().map { status ->
        status?.let {
            StorageStatusModel(
                hasStorage = it.hasStorage.get(),
                usedBytes = it.usedBytes.get(),
                limitBytes = it.limitBytes.get(),
            )
        }
    }

/**
 * The [ModelsViewModel] of the feature screen. ⚠ Declared above the module that names it: a
 * top-level value is set in file order.
 */
val MODEL_FEATURE_VIEW_MODEL = named("modelFeature")

val viewModelsModule = module {
    viewModel { HomeScreenViewModel(get()) }
    viewModel { NotificationsViewModel(get()) }
    viewModel {
        RecoveryAffordanceViewModel(
            preferences = get(),
            syncRepository = get(),
            // The synced entitlement, not the local account: realm-wide, so a phrase holder whose
            // realm was funded from another device is not told nothing syncs.
            syncFunded = storageStatusModelFlow().map { it?.hasStorage },
        )
    }
    viewModel {
        // control-subscription-ux.md §2.3 / retention-quota-model.md: the one-time
        // free-tier 80% quota soft-limit home banner, over the same synced StorageStatus.
        QuotaSoftLimitViewModel(
            preferences = get(),
            storageStatusFlow = storageStatusModelFlow(),
        )
    }
    // The "Update Control to keep syncing" home banner, over the fence row.
    viewModel { SchemaFenceBannerViewModel(get<SyncRepository>().schemaFence()) }
    viewModel {
        // maps-region-delivery.md §7: Home's offline-maps discovery card. The
        // "any region installed" probe reuses the Offline-maps repository rather than a second
        // read path, so the card and the screen it points at can never disagree.
        val offlineMaps = get<OfflineMapsRepository>()
        OfflineMapsSuggestionViewModel(
            preferences = get(),
            installedApps = get<AppRegistry>().installedApps(),
            anyRegionInstalled = {
                // null = could not tell (the store read failed) ⇒ the card stays hidden.
                runCatchingCancellable { offlineMaps.regions().regions.any { it.installed } }.getOrNull()
            },
        )
    }
    viewModel { AppsViewModel(get()) }
    viewModel { (appId: String) -> AppDetailViewModel(appId, get()) }
    // EPC app pairing — proxy side. The host is bound platform-side (PlatformProxyModule).
    viewModel { EpcPairingViewModel(get()) }
    viewModel { DataViewModel(get()) }
    viewModel { BackgroundIngestViewModel(get()) }
    viewModel { SharesViewModel(get()) }
    viewModel {
        // storage-entitlement-sync.md: the Services tab reads the synced StorageStatus
        // (derived from the synced Entitlement) so a 2nd device reflects the
        // subscription even with no device-local account.
        ServicesViewModel(
            publisherClient = get(),
            storageStatusFlow = storageStatusModelFlow(),
            storeBilling = getOrNull(),
        )
    }
    viewModel { (productId: String) ->
        CheckoutViewModel(
            publisherClient = get(),
            storageStatusFlow = storageStatusModelFlow(),
            productId = productId,
            billingRail = app.tenshu.control.ui.billingRail,
            // Bound only in a Play build (androidBillingModule); null on the Stripe rail.
            storeBilling = getOrNull(),
        )

    }
    viewModel { FreeAccountSignupViewModel(get()) }
    viewModel { ReiKeyViewModel(get()) }
    viewModel { DisconnectConfirmViewModel(publisherClient = get(), syncRepository = get()) }
    viewModel { AccountDeletionViewModel(publisherClient = get()) }
    viewModel { SettingsViewModel() }
    viewModel { BackgroundSyncSettingsViewModel(get()) }
    viewModel { ErrorsViewModel(errorEventsManager = get(), uploader = get()) }
    // Offline maps management over Control's own in-process client (maps-region-delivery.md §7).
    single<OfflineMapsRepository> { ClientOfflineMapsRepository(get<TenshuClient>()) }
    // Control owns the model store, so it owns the management surface.
    // ⚠ `ClientModelsDevices` and not the `SyncRepository`: the screen needs the device id
    // (`IndexingPaused` is DEVICE-scoped and the SDK has no `currentDeviceId` resolver) and
    // a roster whose failed read is *not known*, which `SyncRepository.devices` reads as empty.
    single<ModelsRepository> { ClientModelsRepository(
            client = get<TenshuClient>(),
            devices = ClientModelsDevices(get<TenshuClient>()),
            // ⚠⚠ `get`, not `getOrNull`: without it the index bar silently misses every pass that
            // starts after the screen opened. `ProxyModule` binds it beside the holder it reads.
            indexProgress = get<IndexProgressSource>(),
            // ⚠ The proxy binds both holders under these read interfaces (`ProxyModule`).
            coverage = getOrNull<IndexCoverageSource>(),
            storeAccess = getOrNull<ModelStoreAccessSource>(),
        ) }
    viewModel { OfflineMapsViewModel(get()) }
    viewModel { ModelsViewModel(get()) }
    // ⚠ The feature screen's instance is another one (its own back-stack entry): it shows the
    // consents and takes nothing a model did, which only the Models screen would show.
    viewModel(MODEL_FEATURE_VIEW_MODEL) { ModelsViewModel(get(), forTheModelsScreen = false) }
    viewModel {
        UnlockSettingsViewModel(syncRepository = get(), biometricDelegate = get())
    }
    viewModel {
        SecuritySettingsViewModel(
            appLockSettings = get(),
            availability = get(),
            syncRepository = get(),
            biometricDelegate = get(),
            reauthenticator = get(),
        )
    }
    viewModel { SyncDashboardViewModel(get(), get()) }
    viewModel { PairingWizardViewModel(get(), get()) }
    viewModel { NewUserViewModel(syncRepository = get(), biometricDelegate = get()) }
    viewModel { RecoveryViewModel(get()) }
    viewModel { LockedViewModel(syncRepository = get(), biometricDelegate = get()) }
    viewModel { SetupUnlockViewModel(syncRepository = get(), biometricDelegate = get()) }
}
