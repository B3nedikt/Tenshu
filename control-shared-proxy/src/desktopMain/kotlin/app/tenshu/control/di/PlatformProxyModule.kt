package app.tenshu.control.di

import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.common.app.proxy.desktopTargetDelivery
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.macOsBundleIdFor
import app.tenshu.control.data.BuildVariant
import app.tenshu.control.data.CachingEntitlementTokenProvider
import app.tenshu.control.data.CommerceEntitlementMintClient
import app.tenshu.control.data.CommerceEntitlementTokenClient
import app.tenshu.control.data.ConstraintSyncGate
import app.tenshu.control.data.EntitlementMinterSelection
import app.tenshu.control.data.EntitlementTokenClient
import app.tenshu.control.data.CommercePublisherClient
import app.tenshu.control.data.MockPublisherClient
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.StorageSyncGate
import app.tenshu.control.data.SyncPauseState
import app.tenshu.control.data.initEntitlementStorage
import app.tenshu.control.data.DesktopBiometricUnlockDelegate
import app.tenshu.control.data.DesktopMasterKeyStore
import app.tenshu.control.data.DesktopNetworkStateProvider
import app.tenshu.control.data.DesktopSecureStorageDelegate
import app.tenshu.control.data.DpapiWrappingKeyStore
import app.tenshu.control.data.KeychainWrappingKeyStore
import app.tenshu.control.data.NoWrappingKeyStore
import app.tenshu.control.proxy.RelayConfig
import app.tenshu.control.epc.DefaultEpcPairingHost
import app.tenshu.control.epc.EpcPairingHost
import app.tenshu.control.epc.EpcRendezvousServer
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.proxy.epc.EpcRendezvousAcceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.core.TComponent
import org.tenshu.core.common.schema.ProxyHostIdentity
import org.tenshu.core.Data
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.sync.proxy.SecureStorageDelegateHlcWatermarkStore
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.JvmFileDatabase
import org.tenshu.inference.proxy.IndexingConditions
import org.tenshu.inference.proxy.InferenceBackend
import org.tenshu.inference.proxy.onnx.AwtImageDecoder
import org.tenshu.inference.proxy.onnx.OnnxInferenceBackend
import org.tenshu.inference.proxy.catalog.DEFAULT_MODELS_DIR
import org.tenshu.inference.proxy.catalog.FileLockModelStoreOwnership
import org.tenshu.inference.proxy.catalog.ModelArtifactRole
import org.tenshu.inference.proxy.catalog.ModelStoreOwnership
import org.tenshu.inference.proxy.catalog.SUPPORTED_RUNTIMES
import org.tenshu.inference.proxy.onnx.OnnxModelFiles
import org.tenshu.core.common.proxy.InMemoryPreviewCache
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
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.app.proxy.PeerAttestationProbe
import org.tenshu.core.common.transport.UnixSocketServer
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.calendar.proxy.EventMerger
import org.tenshu.calendar.proxy.ReminderStateMerger
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.health.proxy.DailyActivityMerger
import org.tenshu.core.time.clock
import org.tenshu.file.proxy.OrphanedBytesSweep
import org.tenshu.file.proxy.declarableContent
import org.tenshu.file.proxy.readBackfillComplete
import org.tenshu.file.proxy.writeBackfillComplete
import org.tenshu.file.proxy.orphanedBytesRecorderFactory
import org.tenshu.sync.proxy.ReclamationSources
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import kotlinx.coroutines.launch
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.proxy.registerOfflineKeeperSchedule
import org.tenshu.file.proxy.registerThumbnailBackfillSchedule
import org.tenshu.inference.proxy.registerMediaIndexSchedule
import org.tenshu.file.proxy.jvmFileServiceProxyImplementationFactory
import org.tenshu.file.proxy.jvmThumbnailLadderRenderer
import org.tenshu.identity.proxy.identityProxyImplementationFactory
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.core.common.app.proxy.initAppService
import org.tenshu.ingest.proxy.initIngestService
import org.tenshu.ingest.proxy.SecurityScopedFolderPicker
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
import app.tenshu.notify.PendingAlertSchedule
import kotlinx.datetime.TimeZone
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
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
import org.tenshu.sync.proxy.RealmRegistrar
import org.tenshu.sync.proxy.SyncHealthHolder
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.NoOpPlatformScheduler
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import org.tenshu.sync.proxy.relayUnreachableForFencing
import org.tenshu.sync.proxy.confirmWritableForFencing

/**
 * Whether this device puts back content the relay no longer holds, from its own plaintext
 * (`drive-upload-stop-and-reclamation.md` §4.18).
 *
 * ⭐ **On.** Repair is additive — every path uploads or does nothing — so its downside is spent
 * bandwidth, not lost bytes.
 *
 * ⚠⚠ This is the net under the relay's own sweep (§4.25): a wrong reclaim is re-emitted from a
 * device that still holds the plaintext, rather than a permanently broken file. It also covers
 * content whose manifest never landed, from an upload that died before the journal finished it.
 *
 * ⚠⚠ Scoped by `REPAIRABLE_PROPERTY_IDS`, and that scope is load-bearing rather than tidy. Every
 * thumbnail class is `Syncable(OnDemand)` too, so a thumbnail resolve reaches the repair with its own
 * property and its own content id — and the repair answers with the *file's* path. Without the
 * whitelist this would upload a full-size image under a thumbnail's content id, in the most ordinary
 * state there is: file downloaded, thumbnail not.
 */
private const val CONTENT_REPAIR_ENABLED = true

/**
 * Whether this device collects plaintext an **ingested** delete stranded on it — `docs/tech-debt.md`
 * *"A delete frees the deleting device's disk and no one else's"*.
 *
 * ⚠ Off, and the one switch covers both halves: recording without sweeping only grows a list, and
 * sweeping without recording finds nothing.
 *
 * ⚠⚠ This is the only pass in the project that deletes a user's **local** bytes without the user
 * having asked for that file to go, so it wants a device pass before it is trusted — one where the
 * candidate list is read back and its verdicts checked against what is actually on disk. ⭐ Losing
 * local plaintext is normally recoverable (the relay re-serves it on demand), which is why this is
 * less fraught than the reclamation sweep, not why it is safe.
 */
private const val ORPHANED_BYTES_SWEEP_ENABLED = false

val platformProxyModule = buildPlatformProxyModule()

/**
 * A FRESH desktop platform-module instance — the desktop twin of the iosMain builder (see
 * `buildProxyModule`'s KDoc for the Koin singleton-caching rationale). The app boots keep using
 * [platformProxyModule]; anything that assembles more than one runtime per process — the headless
 * `:hub` runtime and its tests — must build fresh instances through this.
 */
fun buildPlatformProxyModule(): org.koin.core.module.Module = module {
    single(named("platformPackageName")) { "app.tenshu.control.desktop" }

    // The picker must run in Control: this is the process that creates, stores and later
    // resolves the security-scoped bookmark. One store, shared with `FolderWatcher`.
    single { app.tenshu.control.data.SecurityScopedGrantStore() }
    single<SecurityScopedFolderPicker> { MacSecurityScopedFolderPickerBinding(get()) }

    // The proxy's host binary, echoed in every announce reply (`SessionAdmission.proxyAppId` /
    // `proxyAppVersion`, `core-version-compatibility.md` D4): a refused client cannot read the
    // `RuntimeIdentity` row, so the reply is the only place it can learn which Control it met.
    // ⚠ Same two values `initSyncService` stamps into `RuntimeIdentity` — keep them one source;
    // `controlProxyHostIdentity` maps its "unknown" sentinel to the reply's empty = unknown.
    single<TComponent>(named(ProxyHostIdentity.COMPONENT_ID)) {
        controlProxyHostIdentity(
            hostAppId = getOrNull<String>(named("hostAppId")),
            hostAppVersion = RuntimeIdentity.UNKNOWN_VERSION,
        )
    }

    // Where the proxy parks the reminders the tray hands to macOS. A singleton because the op
    // writes it and the native bridge reads it.
    single { PendingAlertSchedule() }

    // Desktop backing for AppRegistration.verified (`desktop-ipc-client-identity.md`): a
    // client counts as attested when its connection satisfied the peer-credential gate.
    // Resolved lazily per session — UnixSocketServer is registered by Main *after* the
    // modules load, and by the time a client session exists it is always present.
    single<PeerAttestationProbe> {
        PeerAttestationProbe { clientId ->
            getOrNull<UnixSocketServer>()?.isAttested(clientId) == true
        }
    }

    // Build variant for the dev-key release guard (§9.3). Desktop has no Gradle build-type signal
    // at runtime, so it reads `-Dtenshu.release` and **defaults to release-safe** (true); the
    // `:control-desktop:run` task sets it false so local dev keeps the dev-seed minter.
    single { BuildVariant(isRelease = System.getProperty("tenshu.release")?.toBooleanStrictOrNull() ?: true) }

    single<DatabaseLock> { ReentrantLockDatabaseLock() }

    // Relay origin the proxy's RestSyncRelay / RestContentBlobStore / RestShareRelayClient
    // talk to. Defaults to the production relay so an un-configured `:control-desktop:run`
    // just works; point it at a local dev relay via the `TENSHU_RELAY_URL` env var or
    // `-Dtenshu.relayUrl` system property (e.g. `TENSHU_RELAY_URL=http://localhost:8080
    // ./gradlew :control-desktop:run`). Mirrors the relay server's own env-driven config
    // (docs/how-to/deploy-relay-fly.md).
    single(named("syncRelayUrl")) {
        System.getenv("TENSHU_RELAY_URL")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("tenshu.relayUrl")?.takeIf { it.isNotBlank() }
            ?: "https://relay.tenshu.app"
    }

    // App Console base URL for user-initiated upload (`docs/proposals/core-error-channel.md`
    // §4.3). Blank → no Console configured → `NoOpErrorReportUploader` → the Errors screen's
    // "Send error report" button stays disabled.
    single<String>(named("appConsoleUrl")) { "" }
    single<ErrorReportUploader> {
        val url: String = get(named("appConsoleUrl"))
        if (url.isBlank()) NoOpErrorReportUploader()
        else HttpErrorReportUploader(appConsoleUrl = url, httpClient = get())
    }

    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json() }
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

    // The model store's one-writer rule, across PROCESSES (`photos-people-and-pets.md` §12.12, *one
    // process at a time*). ⚠⚠ Two Controls can still open the same data directory — a build without
    // `ControlInstanceGuard` does not take its lock — so whichever takes this lock first owns the
    // model store and the other only reads it. The lock is taken in the constructor. ⚠ Keyed on the
    // bound `FileDatabase`: an integration harness overrides it with an in-memory one, which no
    // other process can reach, and must not then contend for (or leave a lock file in) the real
    // data directory.
    single<ModelStoreOwnership> {
        when (get<FileDatabase>()) {
            is JvmFileDatabase -> FileLockModelStoreOwnership(
                File(desktopContentRoot(get(named("platformPackageName"))), "$DEFAULT_MODELS_DIR/store.lock"),
            )
            else -> ModelStoreOwnership.SoleProcess
        }
    }

    single<SecureStorageDelegate> {
        // ⚠⚠ FIRST, before the properties file below is read: that file holds the model index, it
        // is loaded once, and a process that read it while another Control could still write —
        // and took the lock only after that one died — would own the store with a stale index.
        get<ModelStoreOwnership>()
        // Non-secret DEVICE_ID etc. → plaintext properties (stable identity, no
        // duplicate Device per launch). Master-key secrets → DesktopMasterKeyStore,
        // encrypted at rest under a wrapping key in the macOS login Keychain, or sealed with
        // DPAPI on Windows, so the device resumes its realm across restarts. Elsewhere (Linux:
        // no credential store wired) the key store is the no-op fallback ⇒ in-memory secrets,
        // so the device re-pairs after a restart. See the delegate KDoc,
        // docs/proposals/desktop-masterkeystore.md §3 and desktop-windows-port.md §3.1.
        val packageName = get<String>(named("platformPackageName"))
        val storageDir = DesktopAppPaths.dataDir(packageName)
        val osName = System.getProperty("os.name").orEmpty()
        val wrappingKeyStore = when {
            osName.contains("mac", ignoreCase = true) ->
                KeychainWrappingKeyStore(service = "$packageName.master-key-store")
            osName.startsWith("Windows", ignoreCase = true) ->
                DpapiWrappingKeyStore(java.io.File(storageDir, DpapiWrappingKeyStore.FILE_NAME))
            else -> NoWrappingKeyStore
        }
        DesktopSecureStorageDelegate(
            storageDir = storageDir,
            masterKeyStore = DesktopMasterKeyStore(storageDir, wrappingKeyStore),
        )
    }

    // Desktop binds no biometric primitive — the inert stub reports
    // `isAvailable=false` so LockedScreen/SetupUnlockStep stay on the
    // passphrase-only path here. Android binds the real
    // `AndroidBiometricUnlockDelegate`.
    single<BiometricUnlockDelegate> { DesktopBiometricUnlockDelegate() }

    // Soft app-lock (control-keep-device-unlocked.md §4.2, "Require unlock") — desktop
    // mirror. The keyless Touch ID / login-password check runs in-process (`LAContext` over FFM),
    // falling back to the bundled helper's `authenticate` verb; dormant (unavailable) off macOS.
    // The controller is the process-lifetime gate state machine the window-visibility observer +
    // AppLockGate drive. UI-only — no keys.
    // The soft lock draws Touch ID inside the window (`embedded`) and keeps the sheet as its
    // password route; re-authentication (§6.1) stays on the sheet.
    single {
        app.tenshu.control.data.DesktopAppLock(
            inProcess = app.tenshu.control.data.macInProcessLocalAuth(),
            embedded = app.tenshu.control.data.macEmbeddedLocalAuth(),
        )
    }
    single<app.tenshu.control.data.AppLockAvailability> { get<app.tenshu.control.data.DesktopAppLock>() }
    single { app.tenshu.control.data.AppLockController(settings = get()) }
    // Re-authentication (§6.1): same helper-backed sheet as the soft lock, so it is dormant
    // (Unavailable ⇒ callers pass through) wherever the keychain helper is absent.
    single<app.tenshu.control.data.Reauthenticator> { get<app.tenshu.control.data.DesktopAppLock>() }

    single<SyncManager> {
        SyncManagerImpl(
            // §33: the realm's local copy going takes the state DERIVED from it — the vector store is
            // keyed by file path, and a path means a different photograph in the next realm.
            onLocalStoreWiped = {
                // ⚠⚠ STOP the pass first, then clear. A pass suspended in the model holds a list
                // of paths from the realm being wiped; left running it resumes after the clear,
                // finds an unstamped store and repopulates it with the OLD realm's vectors — the
                // cross-realm answer this clear exists to prevent (§34.6).
                get<org.tenshu.inference.proxy.MediaIndexRunner>().cancelRunningPass()
                get<org.tenshu.inference.proxy.EmbeddingStore>().clear()
                // ⚠⚠⚠ The FACE store too, and it matters MORE than the vectors
                // above for the same reason stated there — it is keyed by file path, so a path
                // means a different photograph in the next realm — plus one the vectors do not
                // carry: a template is BIOMETRIC data (threat model A40). Left behind, a reset or a
                // re-pair keeps the previous realm's faces on disk and the next face pass can group
                // and return people derived from a realm this device no longer belongs to.
                get<org.tenshu.inference.proxy.FaceTemplateStore>().clear()
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

    // Sync-health figures the scheduler writes each tick. Registered as a single
    // (rather than left internal to initSyncService) so the desktop tray's
    // getSyncState bridge reads the same online/isSyncing/backlog figures — the
    // bgproc §5.3 four-state surface.
    single { SyncHealthHolder() }

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

    // ⚠⚠ Deliberately no `single { SyncProcessor(...) }`: production takes its processor from
    // `SyncProcessorHolder`, set by `initSyncService`. A standalone definition built without the
    // realm resolver is realm-blind, and names shared-realm entities under the personal key.

    // One shared, cached entitlement-token provider for this device's PERSONAL realm writes (§8).
    // Owner = this user, so the token is theirs to present. Shared-realm writes go through
    // `perRealmDeviceRelay` (a cross-user member's identity must not be linked to the owner's realm,
    // Q30) and stay unentitled — shared-realm quota/billing is a separate concern.
    // The device-side commerce account/session client (`commerce-accounts-and-sessions.md`
    // §3–§4). MockPublisherClient under the same dev-seed gate as the minter; the real
    // CommercePublisherClient otherwise. Process-lifetime single so the persisted session rehydrates
    // once and feeds the entitlement clients' synchronous token provider.
    single<PublisherClient> {
        val syncManager: SyncManager = get()
        desktopPublisherClient(
            httpClient = get(),
            storageDelegate = get(),
            ownerIdProvider = { syncManager.currentOwnerId },
            isRelease = get<BuildVariant>().isRelease,
        )
    }

    single {
        val syncManager: SyncManager = get()
        val publisherClient: PublisherClient = get()
        CachingEntitlementTokenProvider(
            client = desktopEntitlementTokenClient(
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

    // The record-credential fallback (second-device-entitlement-enforcement.md option 4).
    // Holds the owner's synced signed Entitlement (fed by the storage reader below) and encodes it
    // as the `X-Entitlement` value when this session-less device has no bearer token.
    single { EntitlementRecordCredentialProvider() }

    single<SyncRelay> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()

        // Per sync-backend-relay-plan.md §4.1, every relay request carries a short-lived
        // JWT signed by the device's Ed25519 key. The provider mints a fresh token per
        // call (60s TTL) and returns null if pairing/recovery hasn't established the
        // realm yet — the relay rejects unauthenticated state-changing requests in
        // production mode (JwtAuthenticator).
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                cryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { System.currentTimeMillis() / 1000 }
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

    // Content plane (docs/adr/0017-file-content-two-plane-channel.md write path). Same
    // device-key JWT scheme as the relay; streams chunk ciphertext via
    // PUT /sync/content/{ref} without the BinarySerializer buffer.
    single<ContentBlobStore> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                cryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { System.currentTimeMillis() / 1000 }
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

    // Content-plane usage reader (retention-quota-model.md §5 / D5). Same
    // device-key JWT seam as the content plane; a best-effort GET /sync/usage that
    // feeds the real per-owner `usedBytes` into the StorageStatus gauge. Never throws —
    // null on any failure.
    single<RelayUsageClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                cryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { System.currentTimeMillis() / 1000 }
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

    // Mode-A link sharing (docs/file-sharing.md). Same device-key JWT
    // scheme as the content plane; POSTs the share descriptor + streams the
    // K_share-encrypted chunks via /share. K_share itself never reaches this
    // client — it stays in the URL fragment the user copies (A11).
    single<ShareRelayClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                cryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { System.currentTimeMillis() / 1000 }
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
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                cryptoManager.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg
                )
            },
            nowEpochSec = { java.lang.System.currentTimeMillis() / 1000 }
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
        val tokenProvider = org.tenshu.core.common.security.jwtTokenProvider(
            ownerIdProvider = { syncManager.currentOwnerId },
            deviceIdProvider = { syncManager.currentDeviceId },
            signer = { msg ->
                crypto.signEd25519(
                    privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                    message = msg,
                )
            },
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

    // Durable plaintext content cache. ⚠⚠ Must be durable: an in-memory one loses every
    // decrypted byte with the process, re-fetches it from the relay on the next launch, and
    // makes "keep offline" a promise the desktop cannot keep.
    // Sits at `<data-dir>/content/`, beside the `objects.db` replica, so the
    // content plane inherits the store's location decision rather than inventing a
    // second one. Integration harnesses override this with InMemory (see
    // AppIntegrationTestUtils) so tests stay ephemeral.
    single<FileDatabase> {
        JvmFileDatabase(desktopContentRoot(get(named("platformPackageName"))))
    }

    // The real inference backend on desktop, the same artifact and the same arithmetic the
    // phone runs (IN-D2's "one model everywhere"). ⚠ The *resampler* differs from Android's, so the
    // vectors are not comparable across devices — tolerable only because IN-D5 keeps them
    // device-local (`ImagePreprocessing`).
    //
    // ⚠⚠ Weights come from the **ModelStore first, the side-load convention second** (§22.4) — the
    // store's answer wins because it is the only verified one. ⚠ With an empty store and no
    // artifact under `<content>/inference/models/` this backend claims nothing and `photosLike`
    // answers `NO_BACKEND`. ⚠ Nothing here downloads — that is the Models screen's job.
    // IN-D13. ⚠⚠ This artifact links `onnxMain`, so it can execute an `onnx`
    // checkpoint — and says so HERE rather than letting a `commonMain` constant claim it on
    // every platform. iOS and web contribute nothing and therefore download nothing.
    single<Set<String>>(named("supportedModelRuntimes")) { SUPPORTED_RUNTIMES }
    // §32 — on AC power, no low-battery warning, five minutes without input. macOS and Windows read
    // the machine (`desktopIndexingConditions`); ⚠ Linux cannot tell, and so never indexes.
    single<IndexingConditions>(named("indexingConditions")) { desktopIndexingConditions() }

    single<List<InferenceBackend>>(named("inferenceBackends")) {
        val contentRoot = desktopContentRoot(get(named("platformPackageName")))
        listOf(
            OnnxInferenceBackend(
                decoder = AwtImageDecoder,
                locate = OnnxModelFiles.storeLocator(get(), contentRoot, ModelArtifactRole.VISION),
                // The query half. ⚠ Both absent on a device that only indexes, and `photosLike`
                // then answers NO_TEXT_EMBEDDING rather than pretending to search.
                locateText = OnnxModelFiles.storeLocator(get(), contentRoot, ModelArtifactRole.TEXT),
                locateVocabulary = OnnxModelFiles.storeLocator(get(), contentRoot, ModelArtifactRole.VOCABULARY),
            ),
        )
    }

    // Server-side preview cache (`docs/tech-debt.md` §"File Previews Are
    // Regenerated From the Full File on Every Access"). Desktop's cache is
    // in-memory and process-lifetime only; Android wires the persistent
    // `AndroidPreviewCache` instead. No GC; orphans linger.
    single<PreviewCache> { InMemoryPreviewCache() }

    // Durable materialised entity/relation store. `open()` hydrates the
    // in-memory cache from disk and is suspend; resolved here via the
    // `runBlocking` bridge — the first `get()` is the proxy-init path
    // (`koin.get<ProxyGateway>()` in Main, before the IPC server serves),
    // so the one-time load happens at startup, not on a request thread.
    // Integration harnesses override this with InMemory (see
    // AppIntegrationTestUtils) so tests stay ephemeral and isolated.
    single<ObjectStore> {
        runBlocking {
            openSqliteObjectStore(
                path = desktopObjectStorePath(get(named("platformPackageName"))),
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

    // This device's wanted thumbnail classes, written the
    // first time a client here pulls a rung — the resolver's pull observer below is the writer
    // (drive-synced-thumbnails.md §15.2).
    single {
        val syncManager: SyncManager = get()
        org.tenshu.file.proxy.WantedThumbnailClasses { syncManager.currentDeviceId }
    }

    // OnDemand byte-payload resolver — `ExecutionContext` looks this up via
    // `context.components[OnDemandResolution.ID]` when `Maybe.get()`
    // encounters a `PendingOnDemandProperty` placeholder left by `Select`.
    single<org.tenshu.core.common.proxy.OnDemandResolution> {
        val wantedThumbnailClasses = get<org.tenshu.file.proxy.WantedThumbnailClasses>()
        org.tenshu.sync.proxy.impl.OnDemandResolutionImpl(
            relay = get<SyncRelay>(),
            syncProcessorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>(),
            // Route a shared-realm entity's OnDemand fetch
            // through that realm's own relay + realm-key-aware processor. The
            // holder's `realmRegistry` is populated in the init hook below.
            realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>(),
            // The pull is the declaration. ⚠ Omitting this compiles and no device ever
            // declares a class, so the backfill below mints nothing — `ThumbnailBackfillWiringTest`.
            onPull = { executionContext, _, propertyId ->
                wantedThumbnailClasses.notePull(executionContext, propertyId)
            },
        )
    }

    single { StorageCapacityHolder() }
    single { org.tenshu.sync.common.TombstoneEmitterHolder() }
    single { org.tenshu.sync.common.SyncBlockEmitterHolder() }
    single { org.tenshu.sync.proxy.SyncProcessorHolder() }
    single { org.tenshu.sync.proxy.RealmRegistryHolder() }
    // ⚠⚠ The live scheduler, for the head-less ops built long before it exists
    // (BackfillContentDeclarations, WriteSliceSnapshots, DrainSync, SyncNow). Missing here, every
    // one of them is a SILENT no-op on this platform — the content-declaration backfill never runs.
    single { org.tenshu.sync.proxy.SyncSchedulerHolder() }

    // The ONE sync runtime for this process (`drive-upload-progress.md`). Control is a resident
    // daemon that several apps bind to, so without it each connected app's session would start its
    // own scheduler — the same holder Android binds.
    single { org.tenshu.sync.proxy.SyncRuntimeHolder() }
    // Task composite (core-task-composite.md §5–§6): per-proxy live-task
    // registry — shared by the op factory, initSyncService (lease sweep), and
    // (Android) ProxyService's liveness sum.
    single { org.tenshu.sync.proxy.TaskRegistry(nowMs = { System.currentTimeMillis() }) }

    // Joiner-side: routes decoded `File.Relation.Data` bytes from the
    // sync stream into the local `FileDatabase`. Registered via
    // `context.components[ExternalPropertyWriter.ID]` (see
    // `:core-common`'s `coreCommonModule`).
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

    // Joiner snapshots (`sync-device-catch-up.md` §5.7): the services that can project a
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
            // Collects plaintext an ingested delete strands here (`docs/tech-debt.md`). ⚠ The
            // recorder and the sweep are one feature and share one switch: recording without
            // sweeping only grows a list, and sweeping without recording finds nothing.
            val orphanedBytesSweep = if (ORPHANED_BYTES_SWEEP_ENABLED) {
                OrphanedBytesSweep(
                    executionContext = this,
                    fileDatabase = get<FileDatabase>(),
                    deviceId = { syncManager.currentDeviceId },
                    now = { context.clock().nowEpochMillis() },
                    // ⚠ No emitter yet reads as OWED, never as "nothing owed": a sweep that cannot
                    // see the journal must not delete what the journal might still need.
                    isOwed = { path -> blockEmitterHolder.fileContentEmitter?.hasQueuedContentAt(path) ?: true },
                )
            } else {
                null
            }
            val orphanedFileSweeper = initFileService(
                emitterHolder,
                get<FileDatabase>(),
                get<ObjectStore>(),
                orphanedBytesRecorder = orphanedBytesSweep?.let {
                    orphanedBytesRecorderFactory(
                        executionContext = this,
                        scope = sessionScope,
                        deviceId = { syncManager.currentDeviceId },
                        now = { context.clock().nowEpochMillis() },
                    )
                },
            )
            // Offline keeper (drive-offline-online.md §3.2): periodic KEEP_OFFLINE
            // download reconcile, dispatched by the in-process ScheduleDrainLoop.
            sessionScope.launch { registerOfflineKeeperSchedule() }
            // The thumbnail-class backfill (drive-synced-thumbnails.md §15.3). ⚠ Desktop's
            // in-process drain loop enforces no idle/charging constraint; the per-run budget bounds it.
            sessionScope.launch { registerThumbnailBackfillSchedule() }
            // IN-D6: idle + charging maintenance. ⚠ Best-effort like its
            // neighbour — a failed registration must not take the proxy down.
            sessionScope.launch { registerMediaIndexSchedule() }
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
            initHubManagementService()
            initHomeDeviceService()
            initRoutineService()
            initContactService { get<FileDatabase>() }
            // `contacts-app.md` §9.10: the daily sweep of superseded photo blobs. ⚠ Best-effort like its
            // neighbours.
            sessionScope.launch { registerContactPhotoSweepSchedule() }
            // Albums/flags. One line — every relation is `maintained = true`.
            // ⚠⚠ Without it a row arriving via sync lands in the store but stays invisible to
            // `PhotoService.albums`: present but unfindable, as with Calendar below.
            initPhotoService()
            // ⚠⚠ Without it a calendar row arriving via
            // sync lands in the store but stays invisible to
            // `CalendarService.events` — present but unfindable.
            initCalendarService(implementationFactory)
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
                // local run) keeps the in-process dev-key minter so `:control-desktop:run`
                // works with no commerce service standing. The off-device minter carries a
                // real commerce session token from the bound account.
                mintClient = desktopEntitlementMintClient(
                    cryptoManager,
                    get(),
                    get<BuildVariant>().isRelease,
                    sessionTokenProvider = { publisherClient.currentSessionToken() },
                ),
                // §2.3: persist redeemed (commerce-signed) grants — no session to mint through.
                redeemedFlow = publisherClient.redeemedEntitlements,
                // Feed the record-credential fallback the current signed record.
                recordCredentialProvider = get<EntitlementRecordCredentialProvider>(),
                // retention-quota-model.md: real relay-reported per-owner usage → the gauge's
                // usedBytes. Best-effort; null-safe when offline.
                usageReader = { get<RelayUsageClient>().fetchUsage()?.let { it.usedBytes to it.limitBytes } },
                // D1 read-path signature check (entitlement-trust-anchor-distribution.md).
                verifyEd25519 = cryptoManager::verifyEd25519,
            )
            // The six-hourly, unmetered-only feed refresh. ⚠ Costs nothing
            // when there are no subscriptions — the op finds its own work.
            sessionScope.launch { registerSubscriptionRefreshSchedule() }
            // `docs/proposals/core-error-channel.md`: install the
            // proxy-side `ErrorEventRecorder` so `LocalTaskExecutor`'s
            // catch-all persists every typed/internal exception as an
            // `ErrorEvent` on the realm-scoped `LoggingService.events`. The
            // recorder is constructed against *this* session ExecutionContext
            // (it needs to `saveData` into the realm DB) — so the slot is
            // populated post-construction here in the initialization hook,
            // rather than at the `ExecutionContext(...)` ctor call.
            errorEventRecorder = loggingServiceProxyImplementationFactory(
                sessionExecutionContext = this,
                deviceId = syncManager.currentDeviceId,
                appId = "control",
                // ⚠ Desktop has no version source to read — the bundle is assembled by the
                // package script and nothing injects a constant. UNKNOWN_VERSION says so rather
                // than a placeholder that reads like an answer.
                appVersion = RuntimeIdentity.UNKNOWN_VERSION,
                osLabel = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            )
            // Maintains AppService's appRegistrations / appGrants relations as
            // RegisterApp / SetAppGrant write (control-app-registration.md).
            initAppService()
            // Path A (bgproc-scheduling-roles.md): gate the in-process scheduler
            // on the realm's WifiOnly preference. Windows reports its metered
            // connections; macOS and Linux are assumed unmetered, so this never
            // blocks there (`DesktopNetworkStateProvider`).
            val constraintGate = ConstraintSyncGate(
                preferenceService = get<TenshuClient>().preferenceService,
                networkState = DesktopNetworkStateProvider(),
                // Session-owned: cancelled when ClientRegistry tears this
                // session down, instead of leaking onto a detached dispatcher.
                scope = sessionScope,
            )
            // bgproc §5.3 tray *Paused*: the user's device-local pause switch is
            // composed *ahead* of the network constraint gate, so pausing skips
            // the whole drain/pull cycle regardless of network. The SyncPauseState
            // single is registered by control-desktop's Main.kt (where the
            // DesktopControlSettings persistence lives, one layer up); getOrNull so
            // non-app wirings fall back to the plain constraint gate.
            val pauseState = getOrNull<SyncPauseState>()
            val syncGate = if (pauseState != null) {
                org.tenshu.sync.common.SyncGate { !pauseState.isPaused() && constraintGate.shouldSync() }
            } else {
                constraintGate
            }
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
                hostAppVersion = RuntimeIdentity.UNKNOWN_VERSION,
                // Reuse the process's one runtime (`SyncRuntimeHolder`).
                runtimeHolder = get(),
                taskRegistry = get(),
                // Composed at this level because it is the only place that sees both proxies.
                orphanedBytesSweep = orphanedBytesSweep?.let { sweep -> suspend { sweep.run(); Unit } },
                // The describe backfill's sources (`content-plane-refcount.md` §9.7). ⚠⚠ No device
                // frees relay content (`content-plane-cr2-b6-spec.md` §2) — the relay's own sweep,
                // deployed `armed`, is the only freeing path.
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
                // Put content back from local plaintext when the relay no longer has it.
                repairDanglingContent = CONTENT_REPAIR_ENABLED,
                syncManager = syncManager,
                cryptoManager = cryptoManager,
                mergerRegistry = mergerRegistry,
                // Optional cadence override for embedders of this production hook. The desktop
                // app never binds it (⇒ the 30s production default); the headless `:hub` runtime
                // exposes it as a config knob, and the hub system tests bind a fast cadence so
                // convergence assertions stay inside a test budget while still exercising THIS
                // init path rather than a test twin.
                pollingIntervalMs = getOrNull<Long>(named("syncPollingIntervalMs")) ?: 30000,
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
                // Shared-realm sync in the real proxy runtime (mirrors
                // SystemTestPlatformModule). Each registered shared realm gets its own
                // relay + content-plane client (JWT scoped to the realm's ownerId, signed
                // by the device's A4 key) against the same relay — so a shared folder
                // pushes/pulls its block stream + file content.
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
                syncHealthHolder = get<SyncHealthHolder>(),
                nowMs = { System.currentTimeMillis() },
                // The device's durable HLC watermark (`sync-hlc-tip-device-identity.md`), so the
                // published tip is the *store's* position rather than this process's — and a
                // restart never republishes a `(0,0)` tip, which zeroes this device's
                // tombstone-GC quorum on the relay.
                hlcWatermarkStore = SecureStorageDelegateHlcWatermarkStore(
                    get<SecureStorageDelegate>()
                ),
                platformScheduler = NoOpPlatformScheduler,
            )
            // Inviter-side "pending invites?" piggyback (contact-invitation-
            // handshake.md §12): polls GET /sync/invite/responses on the sync
            // cycle, self-gated on locally-PENDING ContactInvite rows so
            // steady-state traffic is unchanged.
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
            // shared-realm read routes to that realm's runtime (see the single above).
            realmRegistryHolder.realmRegistry = components.realmRegistry
            syncSchedulerHolder.scheduler = components.scheduler
        }
        init
    }

    single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
        val router: app.tenshu.control.epc.EpcCallbackRouter = get()
        val serializer: org.tenshu.core.common.parser.BinarySerializer = get()
        val factory: (String) -> ClientConnection = { clientId ->
            when {
                clientId == get<String>(named("platformPackageName")) -> LocalClientConnection()
                // EPC-paired client (ios-proxy-architecture.md §3c): server-pushes seal back through
                // the session's acceptor via the router, instead of a UDS remote connection.
                clientId.startsWith(app.tenshu.control.epc.DefaultEpcPairingHost.CLIENT_ID_PREFIX) ->
                    app.tenshu.control.epc.EpcClientConnection(clientId, router, serializer)
                else -> object : ClientConnection {
                    override fun init() {}
                    override fun send(data: Data) {
                        getOrNull<UnixSocketServer>()?.getRemoteConnection(clientId)?.send(data)
                    }

                    override fun destroy() {}
                }
            }
        }
        factory
    }

    single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
        val fileDatabase: FileDatabase = get()
        val previewCache: PreviewCache = get()
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val emitterHolder: org.tenshu.sync.common.TombstoneEmitterHolder = get()
        val blockEmitterHolder: org.tenshu.sync.common.SyncBlockEmitterHolder = get()
        val shareRelayClient: ShareRelayClient = get()
        val storageCapacityHolder: StorageCapacityHolder = get()

        val contactComponents: ContactProxyComponents = get()
        val realmRegistryHolder: org.tenshu.sync.proxy.RealmRegistryHolder = get()

        // Lazy provider breaks the circular dependency during DI initialization.
        val syncRelayProvider = { get<SyncRelay>() }

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

        // ⚠ ONE registry per proxy: it is the state that spans the two calls of a confirmed
        // action (MQ-D7), so a fresh one per operation would make every tap find it empty.
        val miaActions = miaActionRegistry { System.currentTimeMillis() }

        val factory: (String) -> OperationImplementation = { operationType ->
            implementationFactory(operationType)
                ?: syncProxyImplementationFactory(
                    operationType,
                    syncRelayProvider(),
                    syncManager,
                    cryptoManager,
                    emitterHolder.emitter,
                    // The shared-realm registry, so CreateSharedRealm + the membership-grant
                    // ops resolve.
                    realmRegistry = realmRegistryHolder.realmRegistry,
                    grantedRelayFactory = grantedRelayFactory,
                    nowMs = { System.currentTimeMillis() },
                    // Local-first (ADR 0019): defer CreateRealm's relay genesis when
                    // there's no subscription — same StorageCapacityHolder gate the
                    // scheduler uses.
                    registrationGate = StorageSyncGate(storageCapacityHolder, syncManager),
                    // Desktop is always-resident (daemon, bgproc §5.3) → the in-process
                    // ScheduleDrainLoop drains the registry; no OS hand-off needed.
                    platformScheduler = NoOpPlatformScheduler,
                    syncSchedulerHolder = get<org.tenshu.sync.proxy.SyncSchedulerHolder>(),
                    // Task composite: registry + lane API (deref-at-call-time,
                    // same timing pattern as realmRegistryHolder above).
                    taskRegistry = get(),
                    laneEmitter = { get<org.tenshu.sync.common.SyncBlockEmitterHolder>().emitter },
                )
                ?: identityProxyImplementationFactory(operationType)
                ?: jvmFileServiceProxyImplementationFactory(operationType, fileDatabase, previewCache)
                ?: fileServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    emitterHolder.emitter,
                    blockEmitterHolder,
                    syncManager.currentDeviceId,
                    shareRelayClient,
                    cryptoManager,
                    storageCapacityHolder,
                    // `media-processing-pipeline-sweep.md` §4.1: one decode renders an upload's
                    // whole still ladder. ⚠ Omitting it still mints every rung, each from its own full
                    // decode, so nothing fails — `ThumbnailLadderRendererWiringTest` audits it.
                    thumbnailLadderRenderer = jvmThumbnailLadderRenderer(),
                    // The thumbnail backfill enumerates File rows and every device's
                    // wanted-classes row from the store. ⚠ Null makes the pass a silent no-op.
                    objectStore = get<ObjectStore>(),
                    // `drive-xxlarge-thumbnail-rung.md` §4.6: mint-on-view's metered refusal.
                    // ⚠ Only Windows can answer METERED here (a hotspot, a connection marked metered);
                    // macOS and Linux report UNMETERED, so on those this is wired for the contract
                    // rather than for an effect.
                    meteredNetwork = {
                        DesktopNetworkStateProvider().current() == app.tenshu.control.data.NetworkStatus.METERED
                    },
                    // ⚠⚠ D3's offline-move refusal (`content-plane-cr2-b6-spec.md` §3):
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
                    // `timeline-movement-capture.md` §33.3 — without this the `linesServing` op
                    // exists and always answers "".
                    transitLines = mapRegionComps.transitLines,
                )
                ?: ingestServiceProxyImplementationFactory(
                    id = operationType,
                    folderPicker = getOrNull<SecurityScopedFolderPicker>(),
                )
                ?: preferenceServiceProxyImplementationFactory(operationType)
                ?: hubManagementServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { System.currentTimeMillis() },
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
                // Semantic photo search. ⚠ The registry holds `OnnxInferenceBackend`; it
                // answers `NO_BACKEND` while no model files are present, rather than failing
                // as an unknown operation — the honest state, and reachable.
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
                    // ⚠ The SAME `FaceTemplateStore` singleton the indexer writes (see
                    // `ProxyModule`); while its clustering is `Uncalibrated`, `faceGroups`
                    // answers `UNCALIBRATED` rather than "nobody is here".
                    faceGrouping = get(),
                )
                // Cross-app open (control-cross-app-open.md). ⭐ Unlike Android, Control
                // launches AND raises here: macOS has no background-activity-launch rule and the
                // daemon is unsandboxed, so the caller does nothing (§5h).
                ?: appOpenTargetImplementationFactory(
                    id = operationType,
                    deviceId = { syncManager.currentDeviceId },
                    delivery = desktopTargetDelivery(
                        // ⚠ Resolved at CALL time: `UnixSocketServer` is built alongside this
                        // factory, and a `get()` here would be the same circular dependency the
                        // Android side documents.
                        connectionFor = { clientId ->
                            getOrNull<UnixSocketServer>()?.getRemoteConnection(clientId)
                        },
                        launch = { appId -> openMacOsBundleForAppId(appId) },
                    ),
                    // ⚠⚠ Read at CALL time from the executing context, not captured here: the
                    // choice is a realm row that can change while the proxy runs.
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
                )
                ?: calendarServiceProxyImplementationFactory(
                    id = operationType,
                    // Subscription fetches are Control's, which is what
                    // keeps Calendar's manifest free of INTERNET.
                    fetcher = org.tenshu.calendar.proxy.KtorIcsSubscriptionFetcher(get()),
                    // ⚠ A `SchedulingNotificationPresenter`, so the op hands the
                    // whole forward set over and arms no alarm of its own — the
                    // OS holds the schedule (§4.6(a)).
                    presenter = get<PendingAlertSchedule>(),
                    nowEpochMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
                    zoneId = { TimeZone.currentSystemDefault().id },
                    // ⚠⚠ *Stop syncing*'s tombstone. Without it the stop is device-local and the
                    // bound phone brings the mirror back (`calendar-external-sources.md` §11).
                    tombstoneEmitter = emitterHolder.emitter,
                )
                ?: storageServiceProxyImplementationFactory(operationType, storageCapacityHolder)
                ?: error("No executor found for operation $operationType")
        }
        factory
    }
}

// Object store lives in the per-package app-data dir resolved by
// `DesktopAppPaths` (App Group container on a signed mac build, `~/.tenshu`
// otherwise). `dataDir` creates the parent — SQLite won't create missing dirs.
private fun desktopObjectStorePath(packageName: String): String =
    File(DesktopAppPaths.dataDir(packageName), "objects.db").absolutePath

/** Content-byte root, a sibling of [desktopObjectStorePath]'s `objects.db`. */
private fun desktopContentRoot(packageName: String): File =
    File(DesktopAppPaths.dataDir(packageName), "content")

/**
 * Selects the entitlement minter (`publishing-commerce-reconciliation.md` §10.3). Default is the
 * off-device commerce service (`tenshu.commerce`-signed); `TENSHU_BILLING_MOCK` /
 * `-Dtenshu.billingMock` (defaulting to the build-time [RelayConfig.BILLING_MOCK], `true` unless a
 * build set `tenshu.billingMock`) keeps the in-process dev-key minter so `:control-desktop:run`
 * works with no commerce service standing. The commerce origin mirrors the relay-URL seam:
 * `TENSHU_COMMERCE_URL` / `-Dtenshu.commerceUrl`, falling back to [RelayConfig.COMMERCE_URL].
 *
 * ⚠⚠ **That last fallback is not a detail — it is what a packaged app uses.** A
 * `TenshuControl.app` launched from Finder inherits no shell environment and is given no `-D`, so
 * the runtime seams above never fire and the baked constant is the only value in play.
 */
private fun desktopEntitlementMintClient(
    cryptoManager: SyncCryptoManager,
    httpClient: HttpClient,
    isRelease: Boolean,
    sessionTokenProvider: () -> String?,
): EntitlementMintClient {
    val billingMock = System.getenv("TENSHU_BILLING_MOCK")?.toBooleanStrictOrNull()
        ?: System.getProperty("tenshu.billingMock")?.toBooleanStrictOrNull()
        ?: RelayConfig.BILLING_MOCK
    // Dev-key release guard (§9.3): never self-sign with the dev key in a release build, whatever
    // TENSHU_BILLING_MOCK says.
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
 * Selects the device-side commerce account client (`commerce-accounts-and-sessions.md` §3–§4). The
 * [MockPublisherClient] under the same `TENSHU_BILLING_MOCK` default-on gate as the minter (so
 * `:control-desktop:run` has a working signup with no commerce service standing); the real
 * [CommercePublisherClient] otherwise. Commerce origin mirrors the minter's `TENSHU_COMMERCE_URL`,
 * falling back to the build-time [RelayConfig.COMMERCE_URL] — which is the value a packaged app
 * actually uses, having no environment to read.
 */
private fun desktopPublisherClient(
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
 * Selects the entitlement-TOKEN client (`commerce-billing-plane.md` §8) — the bearer token
 * presented to the relay on writes, distinct from the synced-record minter above. Returns `null`
 * (the [CachingEntitlementTokenProvider] then presents no `X-Entitlement` header) under the same
 * `TENSHU_BILLING_MOCK` default-on as the minter: unlike the record, the token cannot be signed
 * in-process (the issuer seed lives only in `:commerce-service`), so a mock/unconfigured run simply
 * has no token client. The commerce origin mirrors the minter's `TENSHU_COMMERCE_URL` seam, over
 * the same build-time [RelayConfig.COMMERCE_URL] fallback.
 */
private fun desktopEntitlementTokenClient(
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
 * Launch (or activate) a first-party app's macOS bundle for [appId]
 * (`control-cross-app-open.md` §5h).
 *
 * The catalogue resolves platform-neutral app ids to the Apple bundle ids used by LaunchServices,
 * including shared iOS/macOS identifiers. Unknown plausible ids remain launchable for registered
 * third-party apps, while malformed input is rejected before invoking `open`.
 */
private fun openMacOsBundleForAppId(appId: String): Boolean {
    val bundleId = macOsBundleIdFor(appId) ?: return false
    return runCatching {
        ProcessBuilder("open", "-b", bundleId).start().waitFor() == 0
    }.getOrDefault(false)
}
