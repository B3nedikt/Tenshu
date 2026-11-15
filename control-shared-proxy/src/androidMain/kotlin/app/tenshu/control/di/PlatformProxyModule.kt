package app.tenshu.control.di

import app.tenshu.control.inference.installNewArrivalsIndexing
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.file.proxy.declarableContent
import org.tenshu.file.proxy.readBackfillComplete
import org.tenshu.file.proxy.writeBackfillComplete
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import app.tenshu.control.data.AndroidBiometricUnlockDelegate
import app.tenshu.control.data.AndroidNetworkStateProvider
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.BuildVariant
import app.tenshu.control.data.CachingEntitlementTokenProvider
import app.tenshu.control.data.CommerceEntitlementMintClient
import app.tenshu.control.data.CommerceEntitlementTokenClient
import app.tenshu.control.data.ConstraintSyncGate
import app.tenshu.control.data.EntitlementMinterSelection
import app.tenshu.control.data.CommercePublisherClient
import app.tenshu.control.data.EntitlementTokenClient
import app.tenshu.control.data.MockPublisherClient
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.StorageSyncGate
import app.tenshu.control.data.initEntitlementStorage
import app.tenshu.control.epc.DefaultEpcPairingHost
import app.tenshu.control.epc.EpcPairingHost
import app.tenshu.control.epc.EpcRendezvousServer
import app.tenshu.control.proxy.RelayConfig
import org.tenshu.core.common.app.proxy.PeerAttestationProbe
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.proxy.ReclamationSources
import org.tenshu.sync.proxy.install
import org.tenshu.sync.proxy.epc.EpcRendezvousAcceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.tenshu.core.core.TComponent
import org.tenshu.core.common.schema.ProxyHostIdentity
import org.tenshu.core.common.delegate.AndroidSecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.sync.proxy.SecureStorageDelegateHlcWatermarkStore
import org.tenshu.core.common.executor.androidImplementationFactory
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.common.module.coreCommonAndroidModule
import org.tenshu.core.common.proxy.AndroidClientConnection
import org.tenshu.core.common.proxy.AndroidFileDatabase
import org.tenshu.inference.proxy.AndroidIndexingConditions
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.onnx.AndroidImageDecoder
import org.tenshu.inference.proxy.onnx.OnnxInferenceBackend
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.onnx.FaceModel
import org.tenshu.inference.proxy.onnx.MediaPipeFaceBackend
import org.tenshu.inference.proxy.catalog.SUPPORTED_RUNTIMES
import org.tenshu.inference.proxy.onnx.OnnxModelFiles
import org.tenshu.core.common.proxy.AndroidFileDatabaseImpl
import org.tenshu.core.common.proxy.AndroidPreviewCache
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import java.io.File
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.file.FileService
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.calendar.proxy.EventMerger
import org.tenshu.calendar.proxy.ReminderStateMerger
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.health.proxy.DailyActivityMerger
import org.tenshu.file.proxy.androidFileServiceProxyImplementationFactory
import org.tenshu.file.proxy.androidMotionThumbnailRenderer
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.sync.proxy.SyncHealthHolder
import kotlinx.coroutines.launch
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.proxy.registerOfflineKeeperSchedule
import org.tenshu.file.proxy.registerThumbnailBackfillSchedule
import org.tenshu.inference.proxy.registerMediaIndexSchedule
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.files.util.SharedMemoryCache
import org.tenshu.identity.proxy.identityProxyImplementationFactory
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.core.common.app.proxy.initAppService
import org.tenshu.ingest.proxy.initIngestService
import org.tenshu.ingest.proxy.ingestServiceProxyImplementationFactory
import org.tenshu.notifications.proxy.initNotificationService
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory
import org.tenshu.timeline.proxy.VisitTimeCorroboration
import org.tenshu.timeline.proxy.initTimelineService
import org.tenshu.timeline.proxy.timelineServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.place.proxy.initPlaceService
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory
import org.tenshu.logging.common.ErrorReportUploader
import org.tenshu.logging.proxy.HttpErrorReportUploader
import org.tenshu.logging.proxy.NoOpErrorReportUploader
import org.tenshu.logging.proxy.loggingServiceProxyImplementationFactory
import org.tenshu.core.common.app.proxy.appOpenTargetImplementationFactory
import org.tenshu.mia.proxy.miaActionRegistry
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
import org.tenshu.mia.proxy.miaServiceProxyImplementationFactory
import org.tenshu.home.device.proxy.homeDeviceServiceProxyImplementationFactory
import org.tenshu.home.device.proxy.initHomeDeviceService
import org.tenshu.routine.proxy.RoutineExecution
import org.tenshu.routine.proxy.initRoutineService
import org.tenshu.routine.proxy.routineServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.hubManagementServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.initHubManagementService
import org.tenshu.preference.proxy.initPreferenceService
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.InviteResponsePickup
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.contact.proxy.RestInviteRelayClient
import org.tenshu.contact.proxy.contactServiceProxyImplementationFactory
import org.tenshu.contact.proxy.registerContactPhotoSweepSchedule
import app.tenshu.notify.AlertActionHandler
import app.tenshu.notify.AndroidNotificationPresenter
import app.tenshu.notify.NotificationPresenter
import kotlinx.datetime.TimeZone
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.CalendarReminderActionHolder
import org.tenshu.calendar.proxy.initCalendarService
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.registerSubscriptionRefreshSchedule
import org.tenshu.contact.proxy.initContactService
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import org.tenshu.identity.EntitlementSignature
import org.tenshu.storage.proxy.DevSeedEntitlementMintClient
import org.tenshu.storage.proxy.EntitlementMintClient
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageProvider
import org.tenshu.storage.proxy.initStorageService
import org.tenshu.storage.proxy.storageServiceProxyImplementationFactory
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.RelayUsageClient
import org.tenshu.sync.proxy.RestContentBlobStore
import org.tenshu.sync.proxy.RestShareRelayClient
import org.tenshu.sync.proxy.RestSyncRelay
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.SyncProcessor
import org.tenshu.sync.proxy.BackgroundSyncScheduler
import org.tenshu.sync.proxy.RealmRegistrar
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import org.tenshu.sync.proxy.relayUnreachableForFencing
import org.tenshu.sync.proxy.confirmWritableForFencing
import org.tenshu.sync.DrainSync
import app.tenshu.control.data.backgroundSyncConstraints


/**
 * Whether this device puts content back on the relay from its own plaintext when the relay does not
 * have it (`ContentRepair`). Additive: every path uploads or does nothing.
 *
 * ⚠ The relay's own sweep is the only thing that frees content; repair is the net under a wrong
 * free.
 *
 * ⚠⚠ **The uploading device is the one that needs it most.** A file whose chunks reached the relay
 * and whose manifest never did is readable by no other device, so no reader-side repair can fire;
 * the declaration backfill hands such a content to the repair on the device that holds it
 * (`localBytesContentEnsure`). Desktop's copy of this constant carries the rest of the reasoning.
 */
private const val CONTENT_REPAIR_ENABLED = true

val platformProxyModule = module {
    single(named("platformPackageName")) { androidContext().packageName }

    // The proxy's host binary, echoed in every announce reply (`SessionAdmission.proxyAppId` /
    // `proxyAppVersion`, `core-version-compatibility.md` D4): a refused client cannot read the
    // `RuntimeIdentity` row, so the reply is the only place it can learn which Control it met.
    // ⚠ Same two values `initSyncService` stamps into `RuntimeIdentity` — keep them one source;
    // `controlProxyHostIdentity` maps its "unknown" sentinel to the reply's empty = unknown.
    single<TComponent>(named(ProxyHostIdentity.COMPONENT_ID)) {
        controlProxyHostIdentity(
            hostAppId = getOrNull<String>(named("hostAppId")),
            hostAppVersion = hostAppVersion(),
        )
    }

    // Android backing for AppRegistration.verified (control-app-catalogue.md C4). ProxyService
    // records each uid-derived client id here; the registration trigger reads it back through
    // the PeerAttestationProbe seam. One instance, two roles — bound under both types so the
    // service can write and the trigger can read.
    single { BinderAttestedClients() }
    single<PeerAttestationProbe> { get<BinderAttestedClients>() }

    // Build variant for the dev-key release guard (§9.3): a non-debuggable APK is a release build.
    single {
        val debuggable = (androidContext().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        BuildVariant(isRelease = !debuggable)
    }

    single<DatabaseLock> { ReentrantLockDatabaseLock() }

    // Relay origin the proxy talks to. Android has no runtime env / `-D` knob, so the
    // default is baked at build time into [RelayConfig] (see `generateAndroidRelayConfig`
    // in this module's build.gradle.kts) — the production relay unless overridden via
    // `local.properties` `tenshu.relayUrl` (the gitignored per-dev local-relay seam). The
    // env / system-property reads stay ahead of it so CI / scripted / instrumented runs can
    // still override at runtime.
    single(named("syncRelayUrl")) {
        System.getenv("TENSHU_RELAY_URL")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("tenshu.relayUrl")?.takeIf { it.isNotBlank() }
            ?: RelayConfig.RELAY_URL
    }

    // `docs/proposals/core-error-channel.md` §4.3. See the
    // desktop module for the rationale. Empty here keeps the "Send error
    // report" button disabled.
    single<String>(named("appConsoleUrl")) { "" }
    single<ErrorReportUploader> {
        val url: String = get(named("appConsoleUrl"))
        if (url.isBlank()) NoOpErrorReportUploader()
        else HttpErrorReportUploader(appConsoleUrl = url, httpClient = get())
    }

    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json()
            }
            // Bound a stalled relay (TCP accepted but no response) so a hung
            // request fails as a TResult.Failure instead of suspending forever
            // — without this a stuck `postPairingRequest` pins the pairing
            // wizard on the "Computing" SAS placeholder with no error, since
            // the proxy catch-all only fires on throw/return, never on a hang.
            // connect/socket only — NO overall requestTimeout: this client is
            // shared with `ContentBlobStore`, whose large but actively-
            // progressing chunk transfers must not be capped.
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    single<SecureStorageDelegate> {
        AndroidSecureStorageDelegate(androidContext())
    }

    // UI-side biometric protector for the local-unlock wrap-key.
    // Backed by AndroidKeyStore + BiometricPrompt; consumed by
    // LockedViewModel + SetupUnlockViewModel from
    // [`docs/tech-debt.md`](../../../../../../../docs/tech-debt.md)
    // §"Android Biometric Unlock — Missing, Undermines the Onboarding-
    // Low-Friction UX." Desktop binding is the inert
    // `DesktopBiometricUnlockDelegate`.
    single<BiometricUnlockDelegate> {
        AndroidBiometricUnlockDelegate(androidContext())
    }

    // Soft app-lock (control-keep-device-unlocked.md §4.2, toggle 1). The
    // availability probe accepts BIOMETRIC_STRONG | DEVICE_CREDENTIAL (a secure
    // lock screen is enough); the controller is the process-lifetime state machine
    // the ProcessLifecycle observer + MainActivity gate drive. UI-only — no keys.
    single<app.tenshu.control.data.AppLockAvailability> {
        app.tenshu.control.data.AndroidAppLockAvailability(androidContext())
    }
    single { app.tenshu.control.data.AppLockController(settings = get()) }
    // §6.1: the presence check in front of an irreversible action, and the single
    // prompt implementation the soft lock's gate overlay also calls. Independent of the
    // "Require unlock" preference by design — see `Reauthenticator`.
    single<app.tenshu.control.data.Reauthenticator> { app.tenshu.control.data.AndroidReauthenticator() }

    single<SyncManager> {
        SyncManagerImpl(
            // §33: the realm's local copy going takes the state DERIVED from it — the vector store is
            // keyed by file path, and a path means a different photograph in the next realm.
            onLocalStoreWiped = {
                // ⚠⚠ Stops the pass, then clears every store derived from the realm — vectors, face
                // templates (biometric, A40) and IN-D6a's arrived paths — `wipeDerivedInferenceState`.
                app.tenshu.control.inference.wipeDerivedInferenceState(
                    runner = get(),
                    embeddings = get(),
                    faces = get(),
                    arrivals = get(),
                )
            },
            cryptoManager = get(),
            objectStore = get(),
            // The lock that store's `Database` runs under: the cursor rows are the one access to
            // it that does not come through `DatabaseImpl` (`SyncManagerImpl.storeLock`).
            storeLock = get<DatabaseLock>(),
            storageDelegate = get(),
            context = get()
        )
    }

    single<SemanticMergerRegistry> {
        SemanticMergerRegistry().apply {
            register(FolderMerger())
            register(DailyActivityMerger())
            // Calendar: the union of a series' deleted occurrences, and a
            // dismissal that a concurrent snooze cannot undo.
            register(EventMerger())
            register(ReminderStateMerger())
        }
    }

    // ⚠⚠ No `single { SyncProcessor(...) }` here: production takes its processor from
    // `SyncProcessorHolder`, set by `initSyncService`. A Koin definition that omitted the realm
    // resolver would build a realm-blind processor that names shared-realm entities under the
    // personal key.

    // The device-side commerce account/session client (mirror of the desktop binding).
    // MockPublisherClient under the dev-seed gate; the real CommercePublisherClient otherwise.
    single<PublisherClient> {
        val syncManager: SyncManager = get()
        androidPublisherClient(
            httpClient = get(),
            storageDelegate = get(),
            ownerIdProvider = { syncManager.currentOwnerId },
            isRelease = get<BuildVariant>().isRelease,
        )
    }

    // `commerce-billing-plane.md` §8: shared cached entitlement-token provider for this device's
    // PERSONAL realm writes (mirror of the desktop binding). Shared-realm writes
    // (`perRealmDeviceRelay`) stay unentitled.
    single {
        val syncManager: SyncManager = get()
        val publisherClient: PublisherClient = get()
        CachingEntitlementTokenProvider(
            client = androidEntitlementTokenClient(
                get(),
                get<BuildVariant>().isRelease,
                sessionTokenProvider = { publisherClient.currentSessionToken() },
            ),
            ownerIdProvider = { syncManager.currentOwnerId },
            emailProvider = {
                (publisherClient.account.value?.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address
            },
            nowEpochSec = { System.currentTimeMillis() / 1000 },
            // The tier the SYNCED record reports, so an upgrade invalidates a cached
            // token that still says the old one (it would otherwise stand for 72 h).
            recordTierProvider = { get<EntitlementRecordCredentialProvider>().currentTier },
        )
    }

    // second-device-entitlement-enforcement.md option 4: the record-credential fallback.
    // Holds the owner's synced signed Entitlement (fed by the storage reader below) and encodes it
    // as the `X-Entitlement` value when this session-less device has no bearer token.
    single { EntitlementRecordCredentialProvider() }

    single<SyncRelay> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()

        // Per sync-backend-relay-plan.md §4.1, every relay request carries a short-lived
        // JWT signed by the device's Ed25519 key. Mirror of the desktop binding.
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )

        RestSyncRelay(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = tokenProvider,
            // No dev-owner fallback: when there's no realm-bound owner yet, the JWT
            // provider mints no token and the request goes out unauthenticated, rather
            // than impersonating a placeholder owner the relay rejects (401/403).
            ownerIdProvider = { syncManager.currentOwnerId },
            // Bearer token on X-Entitlement (when a session can obtain one) + the
            // signed-record proof on the separate X-Entitlement-Proof header (session-less fallback).
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(System.currentTimeMillis()) }
        )
    }

    // Content plane (docs/adr/0017-file-content-two-plane-channel.md write path). Mirror of
    // the desktop binding — same device-key JWT, streamed chunk PUT.
    single<ContentBlobStore> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        RestContentBlobStore(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = tokenProvider,
            // Bearer token on X-Entitlement (when a session can obtain one) + the
            // signed-record proof on the separate X-Entitlement-Proof header (session-less fallback).
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(System.currentTimeMillis()) }
        )
    }

    // Content-plane usage reader (retention-quota-model.md §5 / D5). Mirror of
    // the desktop binding — same device-key JWT; a best-effort GET /sync/usage that
    // feeds the real per-owner `usedBytes` into the StorageStatus gauge.
    single<RelayUsageClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        RelayUsageClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            authTokenProvider = tokenProvider,
            // The relay keeps no entitlement state, so it can only report the limit the
            // credential we present grants — the same pair the write paths already send.
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(System.currentTimeMillis()) },
        )
    }

    // Mode-A link sharing (docs/file-sharing.md). Mirror of the desktop
    // binding — same device-key JWT; POSTs the descriptor + streams the
    // K_share-encrypted chunks. K_share stays in the URL fragment (A11).
    single<ShareRelayClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        RestShareRelayClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = tokenProvider,
            // Bearer token on X-Entitlement (when a session can obtain one) + the
            // signed-record proof on the separate X-Entitlement-Proof header (session-less fallback).
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(System.currentTimeMillis()) }
        )
    }

    // Contact-invitation mailbox client (contact-invitation-handshake.md §6).
    // Same device-key JWT seam as the share/content clients for the four
    // inviter-side verbs; the invitee-side verbs send no credential (the
    // redeemer is protocol-anonymous). NOT part of SyncRelay by design.
    single<InviteRelayClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        RestInviteRelayClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            authTokenProvider = tokenProvider
        )
    }

    // Contact-service proxy dependency bundle. The A13 seam resolves through
    // SyncManager so :contact-service-proxy itself stays sync-agnostic.
    single<ContactProxyComponents> {
        val syncManager: SyncManager = get()
        ContactProxyComponents(
            relayClient = get(),
            cryptoManager = get(),
            identityKeyPair = { syncManager.getRealmEncryptionKeyPair() },
            redemptionStore = PendingRedemptionStore(get()),
            // `contacts-app.md` §9.10: big PHOTO lines on the content plane — the byte store
            // they share with files and workout streams, the emit seam, and DeletePerson's tombstones.
            fileDatabase = get(),
            syncBlockEmitterHolder = get(),
            tombstoneEmitterHolder = get(),
        )
    }

    // OnDemand byte-payload resolver — `ExecutionContext` looks this up via
    // `context.components[OnDemandResolution.ID]` when `Maybe.get()`
    // encounters a `PendingOnDemandProperty` placeholder left by `Select`.
    // drive-synced-thumbnails.md §15.2: this device's wanted thumbnail classes, written the
    // first time a client here pulls a rung — the resolver's pull observer below is the writer.
    single {
        val syncManager: SyncManager = get()
        org.tenshu.file.proxy.WantedThumbnailClasses { syncManager.currentDeviceId }
    }

    single<org.tenshu.core.common.proxy.OnDemandResolution> {
        val wantedThumbnailClasses = get<org.tenshu.file.proxy.WantedThumbnailClasses>()
        org.tenshu.sync.proxy.impl.OnDemandResolutionImpl(
            relay = get<SyncRelay>(),
            syncProcessorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>(),
            // Route a shared-realm entity's OnDemand fetch
            // through that realm's own relay + realm-key-aware processor.
            realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>(),
            // The pull is the declaration. ⚠ Omitting this compiles and no device ever
            // declares a class, so the backfill below mints nothing — `ThumbnailBackfillWiringTest`.
            onPull = { executionContext, _, propertyId ->
                wantedThumbnailClasses.notePull(executionContext, propertyId)
            },
        )
    }

    single<List<ModuleConfiguration>>(named("platformSchemaConfigs")) {
        listOf(
            coreCommonAndroidModule(
                fileDescriptorCache = get(),
                sharedMemoryCache = get()
            ),
        )
    }

    // EPC app-pairing proxy side (ios-proxy-architecture.md §10.6; control-ia.md §4.3).
    // Apps → "Connect on another device" drives this: provision a /sync/epc rendezvous
    // session under the device-key JWT (the admission gate), serve the EPC accept loop, and
    // dispatch each decrypted op through the proxy gateway. The app stays keyless — the proxy
    // holds the keys (§10.1). Same device-key JWT seam as the relay/content clients.
    single<EpcPairingHost> {
        val crypto: SyncCryptoManager = get()
        val syncManager: SyncManager = get()
        val proxyGateway: ProxyGateway = get()
        val relayBaseUrl: String = get(named("syncRelayUrl"))
        val httpClient: HttpClient = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = crypto,
            nowEpochSec = { System.currentTimeMillis() / 1000 },
        )
        DefaultEpcPairingHost(
            relayBaseUrl = relayBaseUrl,
            serverFactory = { sessionId ->
                val acceptor = EpcRendezvousAcceptor(
                    crypto = crypto,
                    client = RendezvousRelayClient(
                        baseUrl = relayBaseUrl,
                        httpClient = httpClient,
                        authTokenProvider = tokenProvider,
                    ),
                    sessionId = sessionId,
                )
                object : EpcRendezvousServer {
                    override suspend fun provision() = acceptor.provision()
                    override suspend fun serve(handler: suspend (ByteArray) -> ByteArray) =
                        acceptor.serve(handler)
                    override suspend fun pushCallback(plaintext: ByteArray) = acceptor.pushCallback(plaintext)

                    // Bridge the acceptor's @Volatile SAS to a suspend point (set by serve() on the
                    // handshake frame). Human-pace pairing, so a coarse poll is fine.
                    override suspend fun awaitEstablishedSas(): String {
                        while (true) {
                            acceptor.establishedSas?.let { return it }
                            kotlinx.coroutines.delay(50)
                        }
                    }
                }
            },
            dispatch = { clientId, request -> proxyGateway.call(clientId, request) },
            router = get(),
        )
    }

    single<AndroidFileDatabase> { AndroidFileDatabaseImpl(androidContext()) } bind FileDatabase::class

    // The real inference backend. ⭐ This is the device §15.12 measured: 121 ms/image on the CPU EP,
    // no throttle within a pass, 1.65× across a warm one.
    //
    // ⚠⚠ Weights come from the **ModelStore first, the side-load convention second** (§22.4). The
    // store's answer wins because it is the only verified one — every artifact's sha256 checked
    // against the signed catalog, and all three landed together. ⚠ With an empty store the fallback
    // is what resolves, and a device with nothing pushed contributes a backend that claims nothing:
    // `supports()` is false while the files are absent and `photosLike` answers `NO_BACKEND`. ⚠
    // Nothing here downloads anything — that is Settings→Models' job. IN-D13. ⚠⚠ This artifact
    // links `onnxMain`, so it can execute an `onnx` checkpoint — and says so HERE rather than
    // letting a `commonMain` constant claim it on every platform. iOS and web contribute nothing
    // and therefore download nothing. ⚠ Plus the face pair (`FaceModel.RUNTIME`): MediaPipe + ONNX,
    // and only Android has MediaPipe — so the face entry is offered here and filtered out on
    // desktop by construction.
    single<Set<String>>(named("supportedModelRuntimes")) { SUPPORTED_RUNTIMES + FaceModel.RUNTIME }
    // §32 — charging, battery not low, screen off. Without it the pass never runs (ProxyModule).
    single<IndexingConditions>(named("indexingConditions")) { AndroidIndexingConditions(androidContext()) }
    // Whether the index pass may pull a photo's preview from the relay — never on a
    // metered connection. ⚠ The same test the view-mint gate uses (`meteredNetwork` below).
    single<org.tenshu.file.proxy.MeteredNetworkSignal>(named("indexMeteredNetwork")) {
        val provider = AndroidNetworkStateProvider(androidContext())
        org.tenshu.file.proxy.MeteredNetworkSignal {
            provider.current() == app.tenshu.control.data.NetworkStatus.METERED
        }
    }

    single<List<InferenceBackend>>(named("inferenceBackends")) {
        val root = androidContext().filesDir
        listOf(
            OnnxInferenceBackend(
                decoder = AndroidImageDecoder,
                locate = OnnxModelFiles.storeLocator(get(), root, ModelArtifactRole.VISION),
                // The query half. ⚠ Both absent on a device that only indexes, and `photosLike`
                // then answers NO_TEXT_EMBEDDING rather than pretending to search.
                locateText = OnnxModelFiles.storeLocator(get(), root, ModelArtifactRole.TEXT),
                locateVocabulary = OnnxModelFiles.storeLocator(get(), root, ModelArtifactRole.VOCABULARY),
            ),
            // People. ⚠ Claims FACE_CLUSTERING only while BOTH artifacts are installed,
            // and consent is still enforced per file by `MediaIndexer` and per read by `faceGroups`.
            MediaPipeFaceBackend(
                context = androidContext(),
                locateDetector = OnnxModelFiles.faceStoreLocator(
                    get(), root, ModelArtifactRole.FACE_DETECTOR, FaceModel.PinnedAuraFace,
                ),
                locateEmbedder = OnnxModelFiles.faceStoreLocator(
                    get(), root, ModelArtifactRole.FACE_EMBEDDER, FaceModel.PinnedAuraFace,
                ),
            ),
        )
    }

    // Server-side preview cache (`docs/tech-debt.md` §"File Previews Are
    // Regenerated From the Full File on Every Access"). Persistent
    // `<filesDir>/.previews/` store — the on-device cure for the
    // Binder-watchdog kill from regenerating previews on every flow
    // re-emit. No GC: orphans linger.
    single<PreviewCache> { AndroidPreviewCache(androidContext()) }

    // Durable materialised entity/relation store, in the app's private
    // files dir (cleared on uninstall, no extra permission). `open()`
    // hydrates the cache from disk and is suspend; bridged via runBlocking
    // — first resolution is proxy-init (ProxyService), not a request path.
    // Integration harnesses override this with InMemory.
    single<ObjectStore> {
        val path = File(androidContext().filesDir, "objects.db").absolutePath
        runBlocking {
            openSqliteObjectStore(
                path = path,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                migrations = StoreSchemaMigrations(
                    context = get(),
                    schemas = get(),
                    // §8.4 MigrationContext.deviceId — what a `derive` may consult.
                    // A store with rows old enough to migrate belongs to a device
                    // that has an identity; a fresh store stamps current and never
                    // reads this. Empty when identity does not exist yet.
                    deviceId = getOrNull<SecureStorageDelegate>()
                        ?.getString(SyncManagerImpl.Keys.DEVICE_ID) ?: "",
                ),
            )
        }
    }
    single { ParcelFileDescriptorCache() }
    single { SharedMemoryCache() }
    // Expose caches as named TComponents so anything resolving via
    // `TContext.components` (e.g. proxy-side helpers in tests) finds
    // them through the generic fallback in `coreCommonModule`.
    single<org.tenshu.core.core.TComponent>(named(ParcelFileDescriptorCache.ID)) {
        get<ParcelFileDescriptorCache>()
    }
    single<org.tenshu.core.core.TComponent>(named(SharedMemoryCache.ID)) {
        get<SharedMemoryCache>()
    }

    single { StorageCapacityHolder() }
    single { org.tenshu.sync.common.TombstoneEmitterHolder() }
    single { org.tenshu.sync.common.SyncBlockEmitterHolder() }
    single { org.tenshu.sync.proxy.SyncProcessorHolder() }
    single { org.tenshu.sync.proxy.RealmRegistryHolder() }
    // Task composite (core-task-composite.md §5–§6): per-proxy live-task
    // registry — shared by the op factory, initSyncService (lease sweep), and
    // (Android) ProxyService's liveness sum.
    single { org.tenshu.sync.proxy.TaskRegistry(nowMs = { System.currentTimeMillis() }) }
    single { org.tenshu.sync.proxy.SyncSchedulerHolder() }

    // `drive-upload-progress.md`: the ONE sync runtime for this process. `ClientRegistry`
    // runs `platformProxyInitialization` per connected app, so without this every app would stand
    // up its own scheduler + health holder, all racing on the same single-row status relations and
    // the same content journal. See `SyncRuntimeHolder`.
    single { org.tenshu.sync.proxy.SyncRuntimeHolder() }

    // Exposed as a single (`drive-upload-progress.md`) so `ProxyService` can render upload
    // progress in the foreground-service notification. `initSyncService` would otherwise create
    // its own instance internally and nothing outside the sync graph could observe the figures.
    single { org.tenshu.sync.proxy.SyncHealthHolder() }
    // §14.1 (`drive-upload-progress.md`): which ingest runs an app is driving on THIS device, so
    // the notification above can stand down while that app narrates the same upload with the
    // figures only it has. Shared by the ingest op factory (the writer) and ProxyService (the
    // reader), hence a single.
    single { org.tenshu.ingest.proxy.IngestRunRegistry() }
    // Bridges the OS-woken TenshuOperationWorker back to the proxy.
    single { org.tenshu.sync.proxy.ScheduledOpDispatcherHolder() }
    // The one WorkManager binding: `Schedule`/`CancelScheduled` register through it, the in-process
    // `ScheduleDrainLoop` asks it which items are WorkManager's alone (§5.6), and the push job and
    // the debug maintenance receiver enqueue through it.
    single { org.tenshu.sync.proxy.WorkManagerScheduler(androidContext()) }
    // Index now's WorkManager worker reads the session it runs the pass in from here (§5.6.1).
    single { app.tenshu.control.indexing.ManualIndexContextHolder() }
    // control-background-work-off-fgs.md §5.3: Control's own sync-push job, because the foreground
    // service is for user-started work only. A process single (not per session) because the
    // OS-woken SyncPushWorker reports back to the same instance that enqueued it.
    single {
        val holder = get<org.tenshu.sync.proxy.SyncSchedulerHolder>()
        val workManager = get<org.tenshu.sync.proxy.WorkManagerScheduler>()
        org.tenshu.sync.proxy.SyncPushScheduler(
            backgroundLiveness = get(),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            ),
            pendingContentBytes = { holder.scheduler?.drainablePendingContentBytes() },
            enqueue = workManager::enqueueSyncPush,
            contentDeferred = get<SyncHealthHolder>().observe().map { it.deferred },
            enqueueDeferredContentPush = workManager::enqueueDeferredContentPush,
            nowMs = android.os.SystemClock::elapsedRealtime,
            // §5.7: a file's content landing, on any path, ends a stall's park.
            contentProgress = { holder.scheduler?.contentProgressTotal() ?: 0L },
            planeWaitRemainingMs = { holder.scheduler?.contentPlaneWaitRemainingMs() ?: 0L },
        )
    }
    // core-background-processing.md §4.6: the sync bulk-drain liveness
    // source. Shared instance (bound in the common ProxyModule so the map-region download and the
    // sync scheduler share it) — the scheduler (initSyncService, below) raises it while it has a
    // permitted, pushable backlog; ProxyService merges it with the in-flight RPC count to hold the
    // foreground service up across a long drain so a big folder upload isn't stranded when Drive unbinds
    // mid-pass.

    // Central notifications, OS elevation (control-central-notifications.md
    // §4.3.4): the Android system-notification effect + the SyncWorker post-drain hook
    // that delivers an alert when Control is *closed* (the only scheduled wake of a
    // dead process). Both share one notifier so the stable per-request id dedupes.
    single { app.tenshu.control.notifications.AndroidPendingRequestNotifier(androidContext()) }

    // ⚠ The icon is Control's, not Calendar's: Control is the process that owns
    // the alarm and therefore posts — a consequence of "Control is the proxy".
    // ⚠⚠ The *tap* still carries Calendar's payload, so it must be routed into
    // Calendar rather than leaving the user in Control.
    single<NotificationPresenter> {
        AndroidNotificationPresenter(
            context = androidContext(),
            smallIconRes = android.R.drawable.ic_dialog_info,
        )
    }

    // The notification's own buttons. ⚠⚠ Bound as the bare
    // `AlertActionHandler` the framework-instantiated `AlertActionReceiver`
    // resolves — it has no constructor to inject through, so this binding *is*
    // the wiring. ⚠ The holder is filled by `initCalendarService` below, once
    // there is an `ExecutionContext` to write through.
    single { CalendarReminderActionHolder() }
    single<AlertActionHandler> { get<CalendarReminderActionHolder>() }
    single<org.tenshu.sync.proxy.PostDrainHook> {
        app.tenshu.control.notifications.PendingRequestPostDrainHook(get(), get())
    }

    // Joiner-side: routes decoded `File.Relation.Data` bytes from the
    // sync stream into the local `FileDatabase`. Registered via
    // `context.components[ExternalPropertyWriter.ID]`.
    single<org.tenshu.core.common.proxy.ExternalPropertyWriter> {
        // One registered component, fanned out per service (the dispatcher's
        // single stable lookup point): file bytes + workout stream blobs.
        org.tenshu.core.common.proxy.DelegatingExternalPropertyWriter(
            org.tenshu.file.proxy.FileBytesPropertyWriter(fileDatabase = get()),
            org.tenshu.health.proxy.WorkoutStreamsPropertyWriter(fileDatabase = get()),
            // `contacts-app.md` §9.10: a card's relocated PHOTO lines.
            org.tenshu.contact.proxy.ContactPhotosPropertyWriter(fileDatabase = get()),
        )
    }

    // C8 joiner snapshots (`sync-device-catch-up.md` §5.7): the services that can project a
    // relation into a slice. Registered by component id — `:sync-service-proxy`'s writer and
    // reader fan out across whatever is here and cannot see any service's types.
    //
    // ⚠ A list, unlike the single `ExternalPropertyWriter` above: every service contributes its
    // own producer, and a service that registers none simply never gets a snapshot.
    single<org.tenshu.core.core.TComponent>(
        named(org.tenshu.core.common.proxy.SliceSnapshotProducer.ID),
    ) {
        org.tenshu.core.common.proxy.SliceSnapshotProducerRegistry(
            listOf(
                org.tenshu.file.proxy.FileSliceSnapshotProducer(),
                // A producer over a realm-wide singleton relation.
                org.tenshu.timeline.proxy.MomentSliceSnapshotProducer(),
            ),
        )
    }

    single<ExecutionContext.() -> Unit>(named("platformProxyInitialization")) {
        val init: ExecutionContext.() -> Unit = {
            val syncManager = get<SyncManager>()
            val cryptoManager = get<SyncCryptoManager>()
            val mergerRegistry = get<SemanticMergerRegistry>()
            val emitterHolder = get<org.tenshu.sync.common.TombstoneEmitterHolder>()
            val blockEmitterHolder = get<org.tenshu.sync.common.SyncBlockEmitterHolder>()
            val processorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>()
            val realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>()
            val syncSchedulerHolder = get<org.tenshu.sync.proxy.SyncSchedulerHolder>()
            val scheduledOpDispatcherHolder = get<org.tenshu.sync.proxy.ScheduledOpDispatcherHolder>()
            val orphanedFileSweeper =
                initFileService(emitterHolder, get<FileDatabase>(), get<ObjectStore>())
            // Offline keeper (drive-offline-online.md §3.2): periodic KEEP_OFFLINE
            // download reconcile — dispatched by ScheduleDrainLoop in-process and by
            // WorkManager when Control is closed (the schedule is unmetered-only).
            sessionScope.launch { registerOfflineKeeperSchedule() }
            // drive-synced-thumbnails.md §15.3: idle + charging + unmetered thumbnail-class
            // backfill on the byte holder. ⚠ Best-effort like its neighbours.
            sessionScope.launch { registerThumbnailBackfillSchedule() }
            // IN-D6: idle + charging maintenance. ⚠ Best-effort like its
            // neighbour — a failed registration must not take the proxy down.
            sessionScope.launch { registerMediaIndexSchedule() }
            // IN-D6a: a photograph that arrives — uploaded here or synced in — arms a debounced
            // battery-not-low WorkManager job that indexes a small batch through the same runner.
            // ⚠ The 12 h idle+charging job above keeps the backlog.
            val arrivalsContext = androidContext()
            installNewArrivalsIndexing(
                app.tenshu.control.inference.ThrottledArrivalScheduler(
                    scope = sessionScope,
                    queue = get<org.tenshu.inference.proxy.ArrivalQueue>(),
                    job = { app.tenshu.control.indexing.NewArrivalsIndexWorker.jobState(arrivalsContext) },
                ) {
                    app.tenshu.control.indexing.NewArrivalsIndexWorker.enqueue(arrivalsContext)
                },
            )
            initIdentityService()
            initNotificationService()
            initTimelineService(mergerRegistry)
            initHealthService()
            // ⚠ The corroboration source is the timeline's moments (§10.12): a pair of adjacent
            // places whose machine-guessed names disagree is arbitrated by whether their visits
            // abut. Silent (⇒ merge) wherever there is no visit history to read.
            initPlaceService(mergerRegistry, corroboration = VisitTimeCorroboration(this))
            initIngestService()
            initPreferenceService()
            // Hub management (hub proposal §13) + bridged smart-home devices
            // (home-device-service.md HD-3): the phone is a full manager — it reads the
            // hub's status/device rows and issues commands. Both are pure relation
            // maintainers over rows the hub authors; Control never talks to a radio.
            initHubManagementService()
            initHomeDeviceService()
            initRoutineService()
            initContactService { get<FileDatabase>() }
            // `contacts-app.md` §9.10: the daily sweep of superseded photo blobs. ⚠ Best-effort like its
            // neighbours.
            sessionScope.launch { registerContactPhotoSweepSchedule() }
            // Photos albums/flags. One line — every relation is `maintained = true`.
            // ⚠⚠ Without it a row arriving via sync lands in the store but stays invisible to
            // `PhotoService.albums`: present but unfindable, the Calendar failure noted below.
            initPhotoService()
            // ⚠⚠ Without it a calendar row arriving via
            // sync lands in the store but stays invisible to
            // `CalendarService.events` — present but unfindable.
            initCalendarService(
                implementationFactory = implementationFactory,
                // Lets a Snooze/Dismiss button write without opening an app.
                actionHandler = get<CalendarReminderActionHolder>(),
                nowEpochMs = { System.currentTimeMillis() },
            )
            // The six-hourly, unmetered-only feed refresh. ⚠ Costs nothing
            // when there are no subscriptions — the op finds its own work.
            sessionScope.launch { registerSubscriptionRefreshSchedule() }
            // storage-capacity-gating.md: derive StorageStatus from the
            // bound StorageProvider on this session's scope.
            initStorageService(sessionScope, get<StorageProvider>(), get<StorageCapacityHolder>())
            // storage-entitlement-sync.md: derive figures from the synced
            // Entitlement (reader: DataObserver + seed) and write it on subscribe
            // (mock Publisher writer). Feeds the same EntitlementStorageProvider that
            // initStorageService collects above.
            val publisherClient = get<PublisherClient>()
            initEntitlementStorage(
                provider = get<EntitlementStorageProvider>(),
                accountFlow = publisherClient.account,
                isRelease = get<BuildVariant>().isRelease,
                ownerId = { syncManager.currentOwnerId },
                scope = sessionScope,
                now = { System.currentTimeMillis() },
                // §10.3: minting is off-device by default (the commerce service signs with
                // `tenshu.commerce`). TENSHU_BILLING_MOCK (default true for an unconfigured
                // debug build) keeps the in-process dev-key minter so a debug build works
                // with no commerce service standing. The off-device minter carries a real
                // commerce session token from the bound account.
                mintClient = androidEntitlementMintClient(
                    cryptoManager,
                    get(),
                    get<BuildVariant>().isRelease,
                    sessionTokenProvider = { publisherClient.currentSessionToken() },
                ),
                // commerce-accounts-and-sessions.md §2.3: persist redeemed (commerce-signed) grants
                // — no session to mint through.
                redeemedFlow = publisherClient.redeemedEntitlements,
                // Feed the record-credential fallback the current signed record.
                recordCredentialProvider = get<EntitlementRecordCredentialProvider>(),
                // retention-quota-model.md: real relay-reported per-owner usage → the gauge's
                // usedBytes. Best-effort; null-safe when offline.
                usageReader = { get<RelayUsageClient>().fetchUsage()?.let { it.usedBytes to it.limitBytes } },
                // D1 read-path signature check (entitlement-trust-anchor-distribution.md).
                verifyEd25519 = cryptoManager::verifyEd25519,
            )
            // Maintains AppService's appRegistrations / appGrants relations as
            // RegisterApp / SetAppGrant write (control-app-registration.md).
            initAppService()
            // `docs/proposals/core-error-channel.md`: see the
            // matching desktop wiring for the rationale (recorder constructed
            // against *this* session ExecutionContext post-construction).
            errorEventRecorder = loggingServiceProxyImplementationFactory(
                sessionExecutionContext = this,
                deviceId = { syncManager.currentDeviceId },
                appId = "control",
                appVersion = hostAppVersion(),
                osLabel = "Android ${android.os.Build.VERSION.RELEASE}",
            )
            // Path A (bgproc-scheduling-roles.md): gate the in-process scheduler
            // on the realm's WifiOnly preference vs the live network type.
            val syncGate = ConstraintSyncGate(
                preferenceService = get<TenshuClient>().preferenceService,
                networkState = AndroidNetworkStateProvider(androidContext()),
                // Session-owned: cancelled when ClientRegistry tears this
                // session down, instead of leaking onto a detached dispatcher.
                scope = sessionScope,
            )
            // Local-first storage gate (ADR 0019): gates the personal realm's
            // relay traffic on a bound storage subscription, reading the same
            // StorageCapacityHolder the Tier-2 upload guard does. Without a
            // subscription the personal realm is local-only — no relay request.
            val storageGate = StorageSyncGate(get<StorageCapacityHolder>(), syncManager)
            // Lazy realm genesis (ADR 0019): a realm created without a
            // subscription stays local-only; the scheduler runs POST /sync/realm
            // over the personal relay the first time storageGate opens.
            val realmRegistrar = RealmRegistrar(syncManager, cryptoManager, get<SyncRelay>())
            // Per-realm-device-key relay factory for granted realms (grant ops +
            // self-bind observer). Resolve transport deps once and capture.
            val grantBaseUrl: String = get(named("syncRelayUrl"))
            val grantHttpClient: io.ktor.client.HttpClient = get()
            val grantedRelayFactory: (org.tenshu.sync.common.RealmContext) -> org.tenshu.sync.common.SyncRelay? =
                { realmContext ->
                    org.tenshu.sync.proxy.perRealmDeviceRelay(
                        baseUrl = grantBaseUrl,
                        httpClient = grantHttpClient,
                        context = context,
                        cryptoManager = cryptoManager,
                        realm = realmContext,
                        nowEpochSec = { System.currentTimeMillis() / 1000 },
                    )
                }
            val components = initSyncService(
                // `core-schema-skew-surface.md`: who hosts this runtime, so a client can
                // name the right app to update. ⚠ The override is honoured for symmetry with the
                // iOS module, where route-1 apps genuinely reuse this wiring; on this platform
                // Control is always the host.
                hostAppId = getOrNull<String>(named("hostAppId")) ?: RuntimeIdentity.CONTROL_APP_ID,
                hostAppVersion = hostAppVersion(),
                // The catch-up heartbeat (`suite-sync-status.md` SS-D8): every app's "Syncing…" reads Control's
                // pull through it, so a `true` left by a killed Control is discounted instead of sticking.
                catchUpHeartbeat = true,
                // Reuse the process's one runtime; a later session gets only its own
                // observers back (`drive-upload-progress.md`, `SyncRuntimeHolder`).
                runtimeHolder = get(),
                // §5.6: WorkManager is the only driver of every item with a constraint the
                // in-process ScheduleDrainLoop cannot check, so the loop leaves those alone.
                platformScheduler = get<org.tenshu.sync.proxy.WorkManagerScheduler>(),
                syncHealthHolder = get(),
                taskRegistry = get(),
                syncManager = syncManager,
                cryptoManager = cryptoManager,
                mergerRegistry = mergerRegistry,
                syncProcessorHolder = processorHolder,
                contentBlobStore = get<ContentBlobStore>(),
                fileDatabase = get<FileDatabase>(),
                // Orphan sweep (core-local-store-orphan-gc.md): the SQLite store can
                // enumerate, so this wiring sweeps. iOS/JS omit it and self-refuse.
                objectStore = get<ObjectStore>(),
                syncGate = syncGate,
                // Targeted entitlement fetch (sync-device-catch-up.md §5.3): the scheduler
                // asks the relay for the entitlement row directly when the local one is missing or
                // expired, instead of waiting for the bulk replay to reach it. Same relay the
                // operation layer already uses.
                personalRelay = get<SyncRelay>(),
                // `content-plane-refcount.md` §9.7: the describe backfill's sources.
                // ⚠⚠ No device frees relay content (`content-plane-cr2-b6-spec.md` §2) — the
                // relay's own sweep is the only freeing path.
                reclamationSources = ReclamationSources(
                    declarableContent = { declarableContent() },
                    readBackfillComplete = {
                        readBackfillComplete(syncManager.currentDeviceId)
                    },
                    writeBackfillComplete = { complete ->
                        writeBackfillComplete(syncManager.currentDeviceId, complete)
                    },
                ),
                onReclamationReport = { line -> println("SyncProxy -> $line") },
                // ⭐ `content-plane-cr2-b4-spec.md` §2.4: the OS alert for a change lost to a
                // file another device deleted. The synced notice goes out regardless; this is the shade.
                onChangeLost = { fileName, path ->
                    get<NotificationPresenter>().post(
                        app.tenshu.notify.AppAlert(
                            id = "change-lost:$path",
                            channel = CHANGE_LOST_ALERT_CHANNEL,
                            title = "Change lost",
                            body = org.tenshu.core.file.notifications.ChangeLostNotification.message(fileName),
                        )
                    )
                },
                // Put content back from local plaintext when the relay does not have it.
                repairDanglingContent = CONTENT_REPAIR_ENABLED,
                storageGate = storageGate,
                // ⚠⚠ The relay's `507` (over quota) reaches the *upload* path here, so a file the
                // user picks is refused up front with a typed error instead of being accepted,
                // journalled and then held forever. The two holders are both singletons in this
                // module; `:sync-service-proxy` has no edge to `:storage-service-proxy`, hence a
                // lambda. See `StorageCapacityHolder.relayRejected`.
                onStorageRejectionChanged = { rejected ->
                    get<StorageCapacityHolder>().relayRejected = rejected
                },
                // The journal's outstanding bytes, so `ensureWithinQuota` and the Tier-3
                // `CheckCapacity` pre-flight both measure a new upload against the queue in front
                // of it rather than against raw headroom.
                onQueuedContentBytes = { bytes ->
                    get<StorageCapacityHolder>().queuedContentBytes = bytes
                },
                realmRegistrar = realmRegistrar,
                // Shared-realm sync in the real proxy runtime (mirrors SystemTestPlatformModule +
                // the desktop wiring).
                sharedRealmRelayFactory = org.tenshu.sync.proxy.sharedRealmRelayFactory(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = context,
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    nowEpochSec = { System.currentTimeMillis() / 1000 },
                ),
                sharedRealmContentBlobStoreFactory = org.tenshu.sync.proxy.sharedRealmContentBlobStoreFactory(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = context,
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    nowEpochSec = { System.currentTimeMillis() / 1000 },
                ),
                grantedRelayFactory = grantedRelayFactory,
                // The shared liveness source ProxyService's FGS driver reads.
                backgroundLiveness = get<org.tenshu.core.common.proxy.BackgroundLiveness>(),
                nowMs = { System.currentTimeMillis() },
                // `sync-hlc-tip-device-identity.md`: the device's durable HLC watermark, so the
                // published tip is the *store's* position rather than this process's — a restart
                // must not republish a `(0,0)` tip, which zeroes this device's tombstone-GC quorum
                // on the relay.
                hlcWatermarkStore = SecureStorageDelegateHlcWatermarkStore(
                    get<SecureStorageDelegate>()
                ),
            )
            // Inviter-side "pending invites?" piggyback (contact-invitation-
            // handshake.md §12): polls GET /sync/invite/responses on the sync
            // cycle, self-gated on locally-PENDING ContactInvite rows so
            // steady-state sync traffic is unchanged.
            val invitePickup = InviteResponsePickup(this, get<ContactProxyComponents>())
            components.scheduler.addSyncCycleHook { invitePickup.checkOnce() }
            // Orphaned-file sweep (`core-local-store-orphan-gc.md`). Rides the same hook as
            // the invite pickup: self-refusing, self-terminating, one bounded page per tick, and
            // isolated by the hook runner so cleanup can never break a sync cycle.
            orphanedFileSweeper?.let { sweeper ->
                // ⚠⚠ `ios-shared-store-sqlite.md` B.22.7: the sweep judges a missing folder only once
                // the store has pulled its history, as the scheduler sees it (merged lane included).
                sweeper.historyPulled = components.scheduler::storeHistoryPulled
                // ⚠ addMaintenanceHook, NOT addSyncCycleHook: cycle hooks run after the enabled
                // guard, so a sealed-realm process (Drive-on-iOS) would construct the sweeper and
                // never call it, leaving orphans that also block re-upload.
                components.scheduler.addMaintenanceHook {
                    sweeper.sweepOnce()?.let { pass ->
                        if (pass.deleted > 0 || pass.done) {
                            println(
                                "OrphanedFileSweeper: scanned=${pass.scanned} " +
                                    "deleted=${pass.deleted} done=${pass.done}"
                            )
                        }
                    }
                }
            }
            // SyncScheduler.notifyEntitlementCredentialChanged: when the synced
            // record first appears, drop any 402 backoff + wake so a session-less device re-drives
            // its stranded writes promptly instead of waiting a backoff window (or restart) out.
            get<EntitlementRecordCredentialProvider>().onRecordUsable = {
                components.scheduler.notifyEntitlementCredentialChanged()
            }
            emitterHolder.emitter = components.tombstoneEmitter
            blockEmitterHolder.emitter = components.syncBlockEmitter
            blockEmitterHolder.fileContentEmitter = components.fileContentEmitter
            // Hand the registry to the OnDemand resolver so a
            // shared-realm read routes to that realm's runtime.
            realmRegistryHolder.realmRegistry = components.realmRegistry
            // Expose the resident scheduler so the `DrainSync` op
            // (dispatched by the OS-woken worker) drives one serialized background
            // drain via drainNow() — the same single-flight the in-process loop uses.
            syncSchedulerHolder.scheduler = components.scheduler
            // Let the OS-woken TenshuOperationWorker run a scheduled op
            // by id through the same executeAsync path the in-process ScheduleDrainLoop uses.
            // ⚠⚠ As the worker's child (`dispatchScheduledOp`), so a job the OS stops stops its op
            // too, rather than leaving it running detached with no job and no network (§5.6).
            scheduledOpDispatcherHolder.install(this)
            get<app.tenshu.control.indexing.ManualIndexContextHolder>().executionContext = this
            // bgproc-scheduling-roles.md §6: register the head-less `DrainSync` op as a
            // single dedup-keyed periodic ScheduleItem reconciled with the realm's
            // WifiOnly/MaxMonthlyDataMb ConstraintSpec. App-closed sync runs on the generic
            // WorkManager path (WorkManagerScheduler → TenshuOperationWorker →
            // DrainSync → drainNow), so the constraint is a WorkManager-native
            // wake filter (no wasteful metered wakeups) rather than a wake-then-no-op.
            // maxDataMb is not enforced.
            BackgroundSyncScheduler(
                executionContext = this,
                constraints = get<TenshuClient>().preferenceService.backgroundSyncConstraints(),
                scope = sessionScope,
                opId = DrainSync.ID,
                periodMs = 60L * 60 * 1000, // 1h (WorkManager floors at 15m); battery-friendly
            ).start()
            // control-background-work-off-fgs.md §5.3: a pushable backlog enqueues Control's own
            // sync-push job (not a per-edge expedited DrainSync, which would fire once per
            // uploaded file). Idempotent across sessions — the scheduler is a process single.
            get<org.tenshu.sync.proxy.SyncPushScheduler>().start()
        }
        init
    }

    single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
        val router: app.tenshu.control.epc.EpcCallbackRouter = get()
        val epcSerializer: org.tenshu.core.common.parser.BinarySerializer = get()
        val factory: (String) -> ClientConnection = { clientId ->
            when {
                clientId == androidContext().packageName -> LocalClientConnection()
                // EPC-paired client (ios-proxy-architecture.md §3c): server-pushes seal back through
                // the session's acceptor via the router, instead of a Binder client connection.
                clientId.startsWith(app.tenshu.control.epc.DefaultEpcPairingHost.CLIENT_ID_PREFIX) ->
                    app.tenshu.control.epc.EpcClientConnection(clientId, router, epcSerializer)
                else -> AndroidClientConnection(
                    context = androidContext(),
                    serializer = get(),
                    fileDatabase = get(),
                    clientPackageName = clientId
                )
            }
        }
        factory
    }

    single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
        val fileDatabase: AndroidFileDatabase = get()
        val fileDescriptorCache: ParcelFileDescriptorCache = get()
        val previewCache: PreviewCache = get()
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val emitterHolder: org.tenshu.sync.common.TombstoneEmitterHolder = get()
        val blockEmitterHolder: org.tenshu.sync.common.SyncBlockEmitterHolder = get()
        val shareRelayClient: ShareRelayClient = get()
        val storageCapacityHolder: StorageCapacityHolder = get()

        // Lazy provider breaks the circular dependency during DI initialization.
        val contactComponents: ContactProxyComponents = get()
        val realmRegistryHolder: org.tenshu.sync.proxy.RealmRegistryHolder = get()
        // Same Koin single the init hook populates with the live
        // scheduler, so the `DrainSync` op can delegate to its drainNow().
        val syncSchedulerHolder: org.tenshu.sync.proxy.SyncSchedulerHolder = get()

        val syncRelayProvider = { get<SyncRelay>() }

        // Cross-app open (control-cross-app-open.md). ⚠⚠ `connectionFor` resolves the
        // registry at CALL time, not here: `ClientRegistry` is built with this very factory, so
        // a `get()` at construction time is the circular dependency the `contactComponents` note
        // above is about. The delivery itself holds no session state.
        val targetDelivery = org.tenshu.core.common.app.proxy.androidTargetDelivery(
            context = androidContext(),
            serializer = get(),
            connectionFor = { clientId ->
                GlobalContext.get().get<org.tenshu.core.common.proxy.ClientRegistry>()
                    .connectionFor(clientId)
            },
        )

        // Per-realm-device-key relay factory for the shared-realm grant ops.
        val grantBaseUrl: String = get(named("syncRelayUrl"))
        val grantHttpClient: io.ktor.client.HttpClient = get()
        val grantContext: org.tenshu.core.core.TContext = get()
        val grantedRelayFactory: (org.tenshu.sync.common.RealmContext) -> org.tenshu.sync.common.SyncRelay? =
            { realmContext ->
                org.tenshu.sync.proxy.perRealmDeviceRelay(
                    baseUrl = grantBaseUrl,
                    httpClient = grantHttpClient,
                    context = grantContext,
                    cryptoManager = cryptoManager,
                    realm = realmContext,
                    nowEpochSec = { System.currentTimeMillis() / 1000 },
                )
            }

        val mapRegionComps: org.tenshu.mapregion.proxy.MapRegionProxyComponents = get()
        // ⚠ Resolved ONCE here, like `miaActions` below and for the same reason: the factory lambda
        // runs on every operation lookup, and a per-lookup instance would forget every run the
        // moment it was recorded.
        val ingestRuns: org.tenshu.ingest.proxy.IngestRunRegistry = get()
        // The metered-network gate's signal (D5 / §7.1 track 3g), over the same `NetworkStateProvider`
        // the Path-A sync constraint gate uses. Built once here rather than per operation lookup — the
        // factory lambda runs on every call.
        val meteredNetworkProvider = AndroidNetworkStateProvider(androidContext())

        // ⚠ ONE registry per proxy: it is the state that spans the two calls of a confirmed
        // action (MQ-D7), so a fresh one per operation would make every tap find it empty.
        val miaActions = miaActionRegistry { System.currentTimeMillis() }

        // photos-video-support.md §4.4: Android is the one platform that mints the `MOTION`
        // class, and it reaches the upload core through BOTH file factories below — the
        // descriptor-backed ops in the Android one and the in-memory `UploadFile`/`ReplaceFile` in
        // the common one. ⚠⚠ Omitting it on either compiles and uploads with no motion class;
        // `MotionThumbnailRendererWiringTest` audits both calls.
        val motionThumbnailRenderer = androidMotionThumbnailRenderer()

        val factory: (String) -> OperationImplementation = { operationType ->
            implementationFactory(operationType)
                ?: syncProxyImplementationFactory(
                    operationType,
                    syncRelayProvider(),
                    syncManager,
                    cryptoManager,
                    emitterHolder.emitter,
                    // The shared-realm registry so CreateSharedRealm + the
                    // membership-grant ops resolve.
                    realmRegistry = realmRegistryHolder.realmRegistry,
                    grantedRelayFactory = grantedRelayFactory,
                    nowMs = { System.currentTimeMillis() },
                    // Local-first (ADR 0019): defer CreateRealm's relay genesis when
                    // there's no subscription — same StorageCapacityHolder gate the
                    // scheduler uses.
                    registrationGate = StorageSyncGate(storageCapacityHolder, syncManager),
                    // Schedule/CancelScheduled mirror the registry onto
                    // WorkManager so scheduled ops wake a closed Control.
                    platformScheduler = get<org.tenshu.sync.proxy.WorkManagerScheduler>(),
                    // So the `DrainSync` op delegates to the live
                    // scheduler's drainNow() when the OS-woken worker dispatches it.
                    syncSchedulerHolder = syncSchedulerHolder,
                    // Task composite: registry + lane API (deref-at-call-time,
                    // same timing pattern as realmRegistryHolder above).
                    taskRegistry = get(),
                    laneEmitter = { get<org.tenshu.sync.common.SyncBlockEmitterHolder>().emitter },
                )
                ?: identityProxyImplementationFactory(operationType)
                ?: androidFileServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    fileDescriptorCache,
                    blockEmitterHolder,
                    previewCache,
                    syncManager.currentDeviceId,
                    storageCapacityHolder,
                    motionThumbnailRenderer = motionThumbnailRenderer,
                )
                ?: fileServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    emitterHolder.emitter,
                    blockEmitterHolder,
                    syncManager.currentDeviceId,
                    shareRelayClient,
                    cryptoManager,
                    storageCapacityHolder,
                    motionThumbnailRenderer = motionThumbnailRenderer,
                    // The thumbnail backfill enumerates File rows and every device's
                    // wanted-classes row from the store. ⚠ Null makes the pass a silent no-op.
                    objectStore = get<ObjectStore>(),
                    // `drive-xxlarge-thumbnail-rung.md` §4.6: mint-on-view downloads a whole
                    // original, so it is refused on a metered network unless the user asked for that
                    // picture. ⚠⚠ The same provider the region download uses — and the same reason
                    // it cannot be `canPublishContentNow()`, which is the WifiOnly *policy* and is
                    // open on cellular whenever the user allowed background sync there.
                    meteredNetwork = {
                        meteredNetworkProvider.current() == app.tenshu.control.data.NetworkStatus.METERED
                    },
                    // ⚠⚠ The offline-move refusal (`content-plane-cr2-b6-spec.md` §3, D3):
                    // connectivity. Sourced from the health holder, which the scheduler writes from
                    // `RelayReachability.isOnline()` — deliberately NOT `caughtUp`, whose own
                    // contract says to read it as "has everything", never as "is online". A device
                    // mid-backlog is caughtUp = false and perfectly able to push; fencing on it
                    // would refuse moves through every ordinary catch-up.
                    // ⚠ Audited by `MoveFenceWiringTest`: null reads as online, so a platform that
                    // forgets this never refuses and nothing says so.
                    isOffline = { get<SyncHealthHolder>().current().relayUnreachableForFencing() },
                    // ⚠⚠ The cached verdict above is up to 45 s old; a device that LOOKS online
                    // asks the relay before applying a move. Audited beside `isOffline`.
                    confirmWritable = { get<SyncRelay>().confirmWritableForFencing(get<SyncHealthHolder>().current()) },
                )
                ?: notificationServiceProxyImplementationFactory(operationType)
                ?: timelineServiceProxyImplementationFactory(operationType)
                ?: healthServiceProxyImplementationFactory(
                    operationType,
                    // Content-plane Workout.Streams (read-scaling option B): the blob
                    // shares the file byte store (namespaced /.health/…) and the
                    // entity-then-bytes emit seam files use.
                    fileDatabase,
                    blockEmitterHolder,
                    // ⚠ DeleteWorkout's tombstone. Without this the delete is device-local
                    // and the next pull resurrects the row (SyncObserver.onDataDeleted is a
                    // deliberate no-op — the tombstone is the operation's duty).
                    emitterHolder.emitter,
                )
                ?: placeServiceProxyImplementationFactory(operationType)
                ?: org.tenshu.mapregion.proxy.mapRegionServiceProxyImplementationFactory(
                    operationType,
                    mapRegionComps.catalogSource,
                    mapRegionComps.store,
                    mapRegionComps.downloader,
                    mapRegionComps.streamingSource,
                    mapRegionComps.progressHolder,
                    mapRegionComps.reverseGeocoder,
                    JvmTileDecompressor,
                    meteredNetwork = {
                        meteredNetworkProvider.current() == app.tenshu.control.data.NetworkStatus.METERED
                    },
                    // `timeline-movement-capture.md` §33.3 — without this the `linesServing` op
                    // exists and always answers "".
                    transitLines = mapRegionComps.transitLines,
                )
                // The run registry is what lets ProxyService's notification stand down while an app
                // is narrating its own ingest run (`drive-upload-progress.md` §14.1).
                ?: ingestServiceProxyImplementationFactory(operationType, ingestRuns)
                ?: preferenceServiceProxyImplementationFactory(operationType)
                ?: hubManagementServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { System.currentTimeMillis() },
                    // Only stamp an issuer once this device has an owner — an unowned
                    // proxy has no realm to issue into (the desktop twin's rule).
                    issuerDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                )
                ?: homeDeviceServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { System.currentTimeMillis() },
                    issuerDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                )
                ?: miaServiceProxyImplementationFactory(
                    id = operationType,
                    actions = miaActions,
                    actionOffer = org.tenshu.mia.plan.MiaActionOffer.OFFERED,
                    photoSearch = org.tenshu.mia.proxy.MiaPhotoSearchHome.THIS_DEVICE,
                    newId = { org.tenshu.core.common.util.generateId() },
                )
                // Semantic photo search. ⚠ The registry holds `OnnxInferenceBackend`;
                // it answers `NO_BACKEND` while no model files are present, rather
                // than failing as an unknown operation — the honest state, and reachable.
                ?: inferenceServiceProxyImplementationFactory(
                    id = operationType,
                    registry = get(),
                    embeddings = get(),
                    deviceId = syncManager.currentDeviceId,
                    // ⚠ The SAME components the backend locators read, so the screen's
                    // "installed" and the backend's "which weights do I load" cannot disagree.
                    delivery = get(),
                    // IN-D6 — the scheduled index pass. ⚠ The indexer shares the one
                    // `EmbeddingStore` singleton `photosLike` reads (see `ProxyModule`).
                    indexer = get(),
                    mediaSource = get(),
                    // The SAME holder singleton the indexer ticks (see `ProxyModule`). Two
                    // would give a bar that never moves beside a pass that is working perfectly.
                    indexProgress = get(),
                    // §34 — "Index now", and the single-flight both paths run through.
                    runner = get(),
                    // §5.6.1 — Index now runs as WorkManager's long-running worker, under its own
                    // mediaProcessing/dataSync notification, not under ProxyService's FGS.
                    manualIndexLauncher = app.tenshu.control.indexing.ManualIndexWorker.launcher(androidContext()),
                    // ⚠ The SAME `FaceTemplateStore` singleton the indexer writes (see
                    // `ProxyModule`); while its clustering is `Uncalibrated`, `faceGroups` answers
                    // `UNCALIBRATED` rather than "nobody is here".
                    faceGrouping = get(),
                )
                // Cross-app open (control-cross-app-open.md). Control pushes the
                // request straight in when the handler is connected, and otherwise hands the
                // CALLER an immutable launch ticket — a background proxy may not start an
                // activity, measured (CO-D3).
                ?: appOpenTargetImplementationFactory(
                    id = operationType,
                    deviceId = { syncManager.currentDeviceId },
                    delivery = targetDelivery,
                    // ⚠⚠ Read at CALL time from the executing context, not captured here: the
                    // choice is a realm row that can change while the proxy runs
                    // (`control-cross-app-open.md`).
                    preferredHandlerFor = { context, type -> context.preferredHandlerFor(type) },
                )
                ?: routineServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { System.currentTimeMillis() },
                    authorDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                    execution = RoutineExecution.EXECUTES,
                    // Without it a routine delete never leaves this device (`SyncObserver.onDataDeleted` is a no-op).
                    tombstoneEmitter = emitterHolder.emitter,
                )
                ?: contactServiceProxyImplementationFactory(operationType, contactComponents)
                ?: photoServiceProxyImplementationFactory(
                    id = operationType,
                    nowEpochMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
                    // ED-3: without it a *Revert* never leaves this device (`SyncObserver.onDataDeleted` is a no-op).
                    tombstoneEmitter = emitterHolder.emitter,
                )
                ?: calendarServiceProxyImplementationFactory(
                    id = operationType,
                    // Subscription fetches are Control's, which is what
                    // keeps Calendar's manifest free of INTERNET.
                    fetcher = org.tenshu.calendar.proxy.KtorIcsSubscriptionFetcher(get()),
                    // ⚠ Every platform passes a real presenter: desktop a
                    // `SchedulingNotificationPresenter` (`PendingAlertSchedule`), iOS a
                    // `NotificationPresenter` under route-1 ownership.
                    presenter = get(),
                    nowEpochMs = { System.currentTimeMillis() },
                    zoneId = { TimeZone.currentSystemDefault().id },
                    // ⚠⚠ *Stop syncing*'s tombstone. Without it the stop is device-local and the
                    // bound phone brings the mirror back (`calendar-external-sources.md` §11).
                    tombstoneEmitter = emitterHolder.emitter,
                )
                ?: storageServiceProxyImplementationFactory(operationType, storageCapacityHolder)
                ?: androidImplementationFactory(operationType, fileDescriptorCache)
                ?: error("No executor found for operation $operationType")
        }
        factory
    }
}

/**
 * Selects the entitlement minter (`publishing-commerce-reconciliation.md` §10.3). Default is the
 * off-device commerce service (`tenshu.commerce`-signed); `TENSHU_BILLING_MOCK` /
 * `-Dtenshu.billingMock` (default `true` for an unconfigured debug build) keeps the in-process
 * dev-key minter. The commerce origin mirrors the relay-URL seam (`TENSHU_COMMERCE_URL` /
 * `-Dtenshu.commerceUrl`), defaulting to the build-time [RelayConfig.COMMERCE_URL] (production
 * unless a build pointed it elsewhere).
 */
private fun androidEntitlementMintClient(
    cryptoManager: SyncCryptoManager,
    httpClient: HttpClient,
    isRelease: Boolean,
    sessionTokenProvider: () -> String?,
): EntitlementMintClient {
    val billingMock = System.getenv("TENSHU_BILLING_MOCK")?.toBooleanStrictOrNull()
        ?: System.getProperty("tenshu.billingMock")?.toBooleanStrictOrNull()
        ?: RelayConfig.BILLING_MOCK
    // Dev-key release guard (§9.3): never self-sign with the dev key in a release build.
    if (EntitlementMinterSelection.useDevSeed(billingMock, isRelease)) {
        return DevSeedEntitlementMintClient(
            sign = { msg -> cryptoManager.signEd25519(EntitlementSignature.DEV_SEED, msg) },
            now = { System.currentTimeMillis() },
        )
    }
    val commerceUrl = System.getenv("TENSHU_COMMERCE_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("tenshu.commerceUrl")?.takeIf { it.isNotBlank() }
        ?: RelayConfig.COMMERCE_URL
    // [sessionTokenProvider] is the bound account's commerce session (PublisherClient).
    return CommerceEntitlementMintClient(
        baseUrl = commerceUrl,
        httpClient = httpClient,
        sessionTokenProvider = sessionTokenProvider,
    )
}

/**
 * Selects the device-side commerce account client (`commerce-accounts-and-sessions.md` §3–§4).
 * Mock vs real under the same `TENSHU_BILLING_MOCK` gate as the minter; origin mirrors
 * `TENSHU_COMMERCE_URL`, defaulting to the build-time [RelayConfig.COMMERCE_URL].
 */
private fun androidPublisherClient(
    httpClient: HttpClient,
    storageDelegate: SecureStorageDelegate,
    ownerIdProvider: () -> String?,
    isRelease: Boolean,
): PublisherClient {
    val billingMock = System.getenv("TENSHU_BILLING_MOCK")?.toBooleanStrictOrNull()
        ?: System.getProperty("tenshu.billingMock")?.toBooleanStrictOrNull()
        ?: RelayConfig.BILLING_MOCK
    if (EntitlementMinterSelection.useDevSeed(billingMock, isRelease)) {
        return MockPublisherClient(storageDelegate)
    }
    val commerceUrl = System.getenv("TENSHU_COMMERCE_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("tenshu.commerceUrl")?.takeIf { it.isNotBlank() }
        ?: RelayConfig.COMMERCE_URL
    return CommercePublisherClient(
        baseUrl = commerceUrl,
        httpClient = httpClient,
        ownerIdProvider = ownerIdProvider,
        storageDelegate = storageDelegate,
    )
}

/**
 * Selects the entitlement-TOKEN client (`commerce-billing-plane.md` §8) — the relay-write
 * bearer token, distinct from the synced-record minter above. `null` (no `X-Entitlement` header)
 * under the same `TENSHU_BILLING_MOCK` default-on: the token can't be signed in-process (the issuer
 * seed lives only in `:commerce-service`), so a mock build presents no token. Origin mirrors the
 * minter's `TENSHU_COMMERCE_URL` seam, defaulting to the build-time [RelayConfig.COMMERCE_URL].
 */
private fun androidEntitlementTokenClient(
    httpClient: HttpClient,
    isRelease: Boolean,
    sessionTokenProvider: () -> String?,
): EntitlementTokenClient? {
    val billingMock = System.getenv("TENSHU_BILLING_MOCK")?.toBooleanStrictOrNull()
        ?: System.getProperty("tenshu.billingMock")?.toBooleanStrictOrNull()
        ?: RelayConfig.BILLING_MOCK
    if (EntitlementMinterSelection.useDevSeed(billingMock, isRelease)) return null
    val commerceUrl = System.getenv("TENSHU_COMMERCE_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("tenshu.commerceUrl")?.takeIf { it.isNotBlank() }
        ?: RelayConfig.COMMERCE_URL
    // [sessionTokenProvider] is the bound account's commerce session (PublisherClient).
    return CommerceEntitlementTokenClient(
        baseUrl = commerceUrl,
        httpClient = httpClient,
        sessionTokenProvider = sessionTokenProvider,
    )
}

/**
 * The installed build's version name — what `ErrorEvent.appVersion` and
 * `RuntimeIdentity.appVersion` report.
 *
 * ⚠⚠ Never a literal like `"dev"`: a version nobody can act on is worse than none, because it
 * looks like an answer.
 *
 * ⚠ Falls back to [RuntimeIdentity.UNKNOWN_VERSION] rather than a guess — `getPackageInfo` can
 * throw, and `versionName` is nullable.
 */
private fun org.koin.core.scope.Scope.hostAppVersion(): String = runCatching {
    val ctx = androidContext()
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
}.getOrNull()?.takeIf { it.isNotBlank() } ?: RuntimeIdentity.UNKNOWN_VERSION

/**
 * The channel for a change this device could not deliver (`content-plane-cr2-b4-spec.md` §2.4). ⚠ A
 * promise to the user once created (see [app.tenshu.notify.AlertChannel]): the id and importance
 * never change.
 */
private val CHANGE_LOST_ALERT_CHANNEL = app.tenshu.notify.AlertChannel(
    id = "sync_change_lost",
    name = "Lost changes",
    importance = app.tenshu.notify.AlertImportance.DEFAULT,
)

