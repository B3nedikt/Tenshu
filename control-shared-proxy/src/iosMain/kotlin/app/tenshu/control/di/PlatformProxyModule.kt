package app.tenshu.control.di

import org.tenshu.core.common.app.proxy.iosTargetDelivery
import org.tenshu.core.common.app.proxy.appOpenTargetImplementationFactory
import org.tenshu.file.proxy.declarableContent
import org.tenshu.file.proxy.readBackfillComplete
import org.tenshu.file.proxy.writeBackfillComplete
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import app.tenshu.control.data.CachingEntitlementTokenProvider
import app.tenshu.control.data.IosSharedHlcWatermarkStore
import app.tenshu.control.data.CommerceEntitlementMintClient
import app.tenshu.control.data.CommerceEntitlementTokenClient
import app.tenshu.control.data.CommercePublisherClient
import app.tenshu.control.data.ConstraintSyncGate
import app.tenshu.control.data.EntitlementMinterSelection
import app.tenshu.control.data.EntitlementTokenClient
import app.tenshu.control.data.IosNetworkStateProvider
import app.tenshu.control.data.ProxyOriginSelection
import app.tenshu.control.data.PublisherAccount
import app.tenshu.control.data.MockPublisherClient
import app.tenshu.control.data.PublisherClient
import app.tenshu.control.data.StorageSyncGate
import app.tenshu.control.data.initEntitlementStorage
import app.tenshu.control.proxy.RelayConfig
import app.tenshu.control.epc.DefaultEpcPairingHost
import app.tenshu.control.epc.EpcPairingHost
import app.tenshu.control.epc.EpcRendezvousServer
import kotlinx.coroutines.delay
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.time.SystemClock
import org.tenshu.epc.rendezvous.RendezvousRelayClient
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.proxy.ReclamationSources
import org.tenshu.sync.proxy.epc.EpcRendezvousAcceptor
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.core.TComponent
import org.tenshu.core.common.schema.ProxyHostIdentity
import org.tenshu.core.common.delegate.IosSecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.IosFileDatabase
import org.tenshu.core.common.proxy.InMemoryPreviewCache
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InProcessDatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.calendar.proxy.EventMerger
import org.tenshu.calendar.proxy.ReminderStateMerger
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.health.proxy.DailyActivityMerger
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.sync.proxy.SyncHealthHolder
import org.tenshu.file.proxy.iosFileServiceProxyImplementationFactory
import org.tenshu.file.proxy.ios.IosUploadSourceReader
import kotlinx.coroutines.launch
import org.tenshu.file.proxy.initFileService
import org.tenshu.file.proxy.registerOfflineKeeperSchedule
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.InferenceStoreGate
import org.tenshu.inference.proxy.UnownedEmbeddingStore
import org.tenshu.inference.proxy.UnownedFaceTemplateStore
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
import org.tenshu.logging.proxy.NoOpErrorReportUploader
import org.tenshu.logging.proxy.loggingServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.hubManagementServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.initHubManagementService
import org.tenshu.home.device.proxy.homeDeviceServiceProxyImplementationFactory
import org.tenshu.home.device.proxy.initHomeDeviceService
import org.tenshu.mia.proxy.miaActionRegistry
import org.tenshu.mia.proxy.miaServiceProxyImplementationFactory
import org.tenshu.routine.proxy.RoutineExecution
import org.tenshu.routine.proxy.initRoutineService
import org.tenshu.routine.proxy.routineServiceProxyImplementationFactory
import org.tenshu.preference.proxy.initPreferenceService
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.InviteResponsePickup
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.contact.proxy.RestInviteRelayClient
import org.tenshu.contact.proxy.contactServiceProxyImplementationFactory
import org.tenshu.contact.proxy.registerContactPhotoSweepSchedule
import kotlinx.datetime.TimeZone
import app.tenshu.notify.NoopNotificationPresenter
import app.tenshu.notify.NotificationPresenter
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.inference.proxy.inferenceServiceProxyImplementationFactory
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
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.NoOpPlatformScheduler
import org.tenshu.sync.proxy.syncProxyImplementationFactory
import org.tenshu.sync.proxy.relayUnreachableForFencing
import org.tenshu.sync.proxy.confirmWritableForFencing

// Wall-clock helpers — Kotlin/Native has no `System.currentTimeMillis()`, and the
// `NoDirectClockAccess` audit forbids touching `Clock.System.now()` outside `SystemClock`.
// So the iosMain runtime sources time through `SystemClock` (the one sanctioned wrapper),
// letting the bindings below read like their android/desktop twins.
private val systemClock = SystemClock()
private fun nowMillis(): Long = systemClock.nowEpochMillis()
private fun nowEpochSeconds(): Long = systemClock.nowEpochMillis() / 1000

// iOS `PlatformProxyModule` actual — the in-process Control proxy runtime on iOS
// (`docs/proposals/ios-control-handover.md` § "C4-RT"). Structurally a twin of the desktop
// actual: the `LocalClientConnection` + EPC-paired connection factory (no inter-app connection),
// and the same `initSyncService` wiring. Platform differences per the §C4-RT mapping table: the
// **Ktor Darwin** engine, `IosSecureStorageDelegate` for secrets (the real
// `IosBiometricUnlockDelegate` is bound by Control's own graph, not here), the store and its lock
// left to the shared replica's binding (`sharedReplicaStoreModule`, last-wins), and no
// WorkManager/SyncWorker — foreground sync rides the resident in-process scheduler.

/**
 * Whether this device puts content back on the relay from its own plaintext when the relay does not
 * have it (`ContentRepair`). Additive: every path uploads or does nothing.
 *
 * ⚠ The sweep is observe-only on this platform, so this is not the net under a deletion here. It is
 * the other half of the repair's job: an upload from this device whose manifest never landed.
 *
 * ⚠⚠ **The uploading device is the one that needs it most.** An upload whose chunks reached the
 * relay and whose manifest never did leaves the phone holding the bytes and no upload intent, so
 * nothing retries; and no other device can read a file with no manifest, so no reader-side repair
 * can fire. The declaration backfill hands such a content to the repair on the device that holds it
 * (`localBytesContentEnsure`). Desktop's copy of this constant carries the rest of the reasoning.
 */
private const val CONTENT_REPAIR_ENABLED = true

val platformProxyModule = buildPlatformProxyModule()

/**
 * A FRESH platform-module instance — see [buildProxyModule]'s KDoc for why reusing the module
 * `val` across `koinApplication`s leaks singletons (Koin holds instances on the Module's
 * factories). `DriveLocalRuntime` and its tests assemble through this.
 */
fun buildPlatformProxyModule(): org.koin.core.module.Module = module {

    single(named("platformPackageName")) { "app.tenshu.control.ios" }

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

    // ⚠ Overridden in every production graph: the shared replica's binding
    // (`sharedReplicaStoreModule`, `:tenshu-sdk-ios-core`) binds the SQLite store's own lock, every
    // outermost access one SQLite transaction across the co-resident apps. This in-process lock is
    // what a graph without that binding (a test's) gets.
    single<DatabaseLock> { InProcessDatabaseLock() }

    // Relay origin the proxy's Rest* clients talk to. Defaults to the production relay,
    // consistent with android/desktop. For local-relay dev against
    // `./gradlew :sync-relay-server:run` (reachable from the simulator over the host
    // loopback) swap this to `http://localhost:8080`, or use the `TENSHU_DEV_RELAY` override
    // (`DevRelayOverride`).
    single(named("syncRelayUrl")) { "https://relay.tenshu.app" }

    // No App Console on iOS (core-error-channel.md §4.3) — the Errors screen's
    // upload button stays disabled. Mirrors the android/desktop blank-URL default.
    single<ErrorReportUploader> { NoOpErrorReportUploader() }

    // ⚠⚠ **Noop here on purpose, and overridden per app** (CA-8c). `UNUserNotificationCenter` is
    // per *app*, and on route 1 both Control and Calendar run this same graph over the shared
    // replica — so if both bound the real presenter the user would get every reminder twice, once
    // from each app. `CalendarLocalRuntime` supplies `IosNotificationPresenter` through the
    // last-wins `appOverrides` slot; Control keeps this. The mirror of `ios-drive-parity.md` P6a,
    // where Drive rather than Control is the background syncer on route 1.
    //
    // ⚠⚠ **Control's pending-request alerts never come through here**
    // (`ios-notification-elevation.md` D3): they go through `PendingRequestAlertHandle` (the seam
    // Android and desktop implement), NOT through this presenter, which belongs to Calendar's
    // reminders. Routing them here would reintroduce exactly the double-post the noop binding
    // exists to prevent.
    //
    // ⚠ Note what is *not* the reason: "iOS cannot notify in the background". It can — a pre-armed
    // `UNCalendarNotificationTrigger` fires with the process dead, which is how Calendar works with
    // no background mode at all.
    single<NotificationPresenter> { NoopNotificationPresenter }

    single {
        HttpClient(Darwin) {
            install(ContentNegotiation) { json() }
            // Bound a stalled relay (TCP accepted but no response) — connect/socket
            // only, NO overall requestTimeout (shared with ContentBlobStore's large
            // but actively-progressing chunk transfers). Mirror of the desktop binding.
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    // Secrets sealed at rest under the C1 Keychain wrap key (libsodium AEAD via the
    // `AeadBackend` from `coreCommonModule`); non-secret ids ride the plaintext bucket.
    // The bucket is not Face ID-gated (`gateSecretBucket` defaults to false).
    single<SecureStorageDelegate> {
        IosSecureStorageDelegate(aead = get(), serviceName = "app.tenshu.control.ios.secure-storage")
    }

    // ⚠ No `BiometricUnlockDelegate` binding here. The Face ID realm unlock
    // (`IosBiometricUnlockDelegate`) is Control's alone, so Control's own graph binds it
    // (`ControlSurface.runtimeModules`). This module is assembled by EVERY route-1 app, and a
    // binding here — even one nothing resolves — keeps LocalAuthentication linked into every app's
    // binary (`docs/proposals/ios-per-app-frameworks.md` §3.3, P3).

    // No soft app-lock on iOS; it reports unavailable (control-keep-device-unlocked.md §6).
    single<app.tenshu.control.data.AppLockAvailability> { app.tenshu.control.data.UnavailableAppLock }
    // Reauthentication (§6.1): Control-on-iOS is native SwiftUI and presents its own LAContext
    // sheet, so the Kotlin binding is inert by design — the Swift sheet is the twin, not a binding.
    single<app.tenshu.control.data.Reauthenticator> { app.tenshu.control.data.UnavailableReauthenticator }

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
            // Calendar (CA-2j): the union of a series' deleted occurrences, and a
            // dismissal that a concurrent snooze cannot undo.
            register(EventMerger())
            register(ReminderStateMerger())
        }
    }

    // ⚠⚠ No `single { SyncProcessor(...) }` here. Production takes its processor from
    // `SyncProcessorHolder`, set by `initSyncService`; a direct definition without the realm
    // resolver would build a realm-blind processor that names shared-realm entities under the
    // personal key.

    // commerce-accounts-and-sessions.md §3–§4: the device-side commerce account/session client.
    // MockPublisherClient under the same dev-seed gate as the minter (so a dev/sim run with no
    // commerce service standing keeps a working local signup); the real CommercePublisherClient
    // otherwise — the path by which an iPhone obtains a real email-OTP / paid entitlement (mirror
    // of the desktop/android `*PublisherClient` helpers).
    // Process-lifetime single so the persisted session rehydrates once and feeds the entitlement
    // clients' synchronous token provider.
    single<PublisherClient> {
        val syncManager: SyncManager = get()
        iosPublisherClient(
            httpClient = get(),
            storageDelegate = get(),
            ownerIdProvider = { syncManager.currentOwnerId },
        )
    }

    // Shared cached entitlement-token provider for this device's PERSONAL
    // realm writes. Under the mock-billing default the token client is null, so no
    // `X-Entitlement` header is presented; otherwise it is the real session-gated commerce
    // token client (mirror of the desktop/android path).
    single {
        val syncManager: SyncManager = get()
        val publisherClient: PublisherClient = get()
        CachingEntitlementTokenProvider(
            client = iosEntitlementTokenClient(
                httpClient = get(),
                sessionTokenProvider = { publisherClient.currentSessionToken() },
            ),
            ownerIdProvider = { syncManager.currentOwnerId },
            emailProvider = {
                (publisherClient.account.value?.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address
            },
            nowEpochSec = { nowEpochSeconds() },
            // The tier the SYNCED record reports, so an upgrade invalidates a cached
            // token that still says the old one (it would otherwise stand for 72 h).
            recordTierProvider = { get<EntitlementRecordCredentialProvider>().currentTier },
        )
    }

    // The record-credential fallback (`second-device-entitlement-enforcement.md` option 4).
    // Holds the owner's synced signed Entitlement (fed by the storage reader below) and encodes it
    // as the `X-Entitlement` value when this session-less device has no bearer token.
    // ⚠⚠ Registered so D3's fence and the scheduler share ONE holder. Registering it without also
    // handing it to `initSyncService` below would be worse than not registering it: the scheduler
    // would write its own private instance while the fence read this one, which never leaves its
    // defaults — `online = true` — so the fence would silently never fire on the platform where an
    // offline move is most likely.
    single { SyncHealthHolder() }
    single { EntitlementRecordCredentialProvider() }

    single<SyncRelay> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { nowEpochSeconds() },
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
            entitlementProofProvider = { recordCredentialProvider.proofHeader(nowMillis()) }
        )
    }

    single<ContentBlobStore> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { nowEpochSeconds() },
        )
        RestContentBlobStore(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = tokenProvider,
            // Bearer token on X-Entitlement (when a session can obtain one) + the
            // signed-record proof on the separate X-Entitlement-Proof header (session-less fallback).
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(nowMillis()) }
        )
    }

    // Content-plane usage reader (retention-quota-model.md §5 / D5). Mirror of
    // the desktop/Android binding — same device-key JWT; a best-effort GET /sync/usage
    // that feeds the real per-owner `usedBytes` into the StorageStatus gauge.
    single<RelayUsageClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { nowEpochSeconds() },
        )
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        RelayUsageClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            authTokenProvider = tokenProvider,
            // The relay keeps no entitlement state, so it can only report the limit the
            // credential we present grants — the same pair the write paths send.
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(nowMillis()) },
        )
    }

    single<ShareRelayClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val entitlementProvider: CachingEntitlementTokenProvider = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { nowEpochSeconds() },
        )
        RestShareRelayClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = tokenProvider,
            // Bearer token on X-Entitlement (when a session can obtain one) + the
            // signed-record proof on the separate X-Entitlement-Proof header (session-less fallback).
            entitlementTokenProvider = { entitlementProvider.token() },
            entitlementProofProvider = { recordCredentialProvider.proofHeader(nowMillis()) }
        )
    }

    single<InviteRelayClient> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = cryptoManager,
            nowEpochSec = { nowEpochSeconds() },
        )
        RestInviteRelayClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            authTokenProvider = tokenProvider
        )
    }

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

    // EPC app-pairing proxy side (ios-proxy-architecture.md §10.6). Same device-key JWT
    // seam as the relay clients; the Darwin-backed rendezvous is proven by the smokes.
    single<EpcPairingHost> {
        val crypto: SyncCryptoManager = get()
        val syncManager: SyncManager = get()
        val proxyGateway: ProxyGateway = get()
        val relayBaseUrl: String = get(named("syncRelayUrl"))
        val httpClient: HttpClient = get()
        val tokenProvider = org.tenshu.sync.proxy.deviceJwtTokenProvider(
            syncManager = syncManager,
            cryptoManager = crypto,
            nowEpochSec = { nowEpochSeconds() },
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

                    override suspend fun awaitEstablishedSas(): String {
                        while (true) {
                            acceptor.establishedSas?.let { return it }
                            delay(50)
                        }
                    }
                }
            },
            dispatch = { clientId, request -> proxyGateway.call(clientId, request) },
            router = get(),
        )
    }

    // Durable plaintext content cache (ios-fit-timeline-maps-parity.md P4 / B-2).
    // ⚠⚠ Never an in-memory database: every cached byte would die with the process,
    // and Timeline's keep-offline, Drive's plaintext cache and Maps' downloaded
    // regions would silently forget their work on the next launch — the buttons
    // work, so it reads as a sync bug rather than a missing store.
    // Lives beside the object-store replica in the same App-Group container, which
    // is the trust extension route-1 makes deliberately (threat-model A5 — the
    // co-resident first-party apps are realm co-owners).
    single<FileDatabase> { IosFileDatabase() }

    // ⚠⚠ The database above is the App Group's, and every first-party app runs this module. A real
    // inference store over it would be written by each of them: its constructor scrubs the log
    // and the realm-wipe hook below clears it. So every iOS graph binds stores that hold no
    // database, and the one app that owns inference (Photos) rebinds real ones over its own
    // container, last (`ownedInferenceStoresModule`, `ios-on-device-inference.md` §3.1).
    single<EmbeddingStore> { UnownedEmbeddingStore }
    single<FaceTemplateStore> { UnownedFaceTemplateStore }

    single<PreviewCache> { InMemoryPreviewCache() }

    // ⚠⚠ The materialised entity/relation store is the SQLite shared replica in the App-Group
    // container (`ios-shared-store-sqlite.md`), bound last-wins by the shared replica's binding
    // (`sharedReplicaStoreModule`, `:tenshu-sdk-ios-core`) in every production graph, Control's
    // included. A graph without it has no store: this fails loudly rather than run on a
    // non-durable one.
    single<ObjectStore> {
        error("no ObjectStore bound: the shared replica's module (sharedReplicaStoreModule) must be in this graph")
    }

    single<org.tenshu.core.common.proxy.OnDemandResolution> {
        org.tenshu.sync.proxy.impl.OnDemandResolutionImpl(
            relay = get<SyncRelay>(),
            syncProcessorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>(),
            realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>(),
        )
    }

    single { StorageCapacityHolder() }
    single { org.tenshu.sync.common.TombstoneEmitterHolder() }
    single { org.tenshu.sync.common.SyncBlockEmitterHolder() }
    single { org.tenshu.sync.proxy.SyncProcessorHolder() }
    single { org.tenshu.sync.proxy.RealmRegistryHolder() }
    // ⚠⚠ The live scheduler, for the head-less ops built long before it exists
    // (BackfillContentDeclarations, WriteSliceSnapshots, DrainSync, SyncNow). Missing here, every
    // one of them is a SILENT no-op on this platform.
    single { org.tenshu.sync.proxy.SyncSchedulerHolder() }

    // `drive-upload-progress.md`: the ONE sync runtime for this process. Route-1 gives each app its
    // own in-process proxy (one session), but EPC/route-2 can host several — elect regardless, it
    // is free when there is only one.
    single { org.tenshu.sync.proxy.SyncRuntimeHolder() }
    // Task composite (core-task-composite.md §5–§6): per-proxy live-task
    // registry — shared by the op factory, initSyncService (lease sweep), and
    // (Android) ProxyService's liveness sum.
    single { org.tenshu.sync.proxy.TaskRegistry(nowMs = { nowMillis() }) }

    single<org.tenshu.core.common.proxy.ExternalPropertyWriter> {
        // One registered component, fanned out per service (the dispatcher's
        // single stable lookup point): file bytes + workout stream blobs.
        org.tenshu.core.common.proxy.DelegatingExternalPropertyWriter(
            org.tenshu.file.proxy.FileBytesPropertyWriter(fileDatabase = get()),
            org.tenshu.health.proxy.WorkoutStreamsPropertyWriter(fileDatabase = get()),
            // Contacts (`contacts-app.md` §9.10): a card's relocated PHOTO lines.
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
            val orphanedFileSweeper =
                initFileService(
                    emitterHolder,
                    get<FileDatabase>(),
                    get<ObjectStore>(),
                )
            // Offline keeper (drive-offline-online.md §3.2): periodic KEEP_OFFLINE
            // download reconcile, dispatched by the in-process ScheduleDrainLoop.
            sessionScope.launch { registerOfflineKeeperSchedule() }
            // ⚠ No `registerMediaIndexSchedule()` here, unlike Android and desktop: one row would be
            // dispatched by every route-1 process's drain loop. The app that owns inference on iOS
            // drives its passes itself (`ios-on-device-inference.md` §3.1).
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
            // Pure relation maintainers, as on Android: without them a synced device, command or
            // routine row lands in the store and stays invisible to its relation.
            // ⚠⚠ `initRoutineService` starts nothing. The engine is built per op invocation, and
            // this platform's op factory refuses both ops that build one (see below).
            initHomeDeviceService()
            initRoutineService()
            initContactService { get<FileDatabase>() }
            // `contacts-app.md` §9.10: the daily sweep of superseded photo blobs. ⚠ Best-effort like its
            // neighbours.
            sessionScope.launch { registerContactPhotoSweepSchedule() }
            // Albums/flags. One line — every relation is `maintained = true`.
            // ⚠⚠ Without it a row arriving via sync lands in the store but stays invisible to
            // `PhotoService.albums`: present but unfindable, the Calendar failure noted below.
            initPhotoService()
            // ⚠⚠ Without it a calendar row arriving via
            // sync lands in the store but stays invisible to
            // `CalendarService.events` — present but unfindable.
            initCalendarService(implementationFactory)
            initStorageService(sessionScope, get<StorageProvider>(), get<StorageCapacityHolder>())
            val publisherClient = get<PublisherClient>()
            initEntitlementStorage(
                provider = get<EntitlementStorageProvider>(),
                accountFlow = publisherClient.account,
                isRelease = iosIsRelease(),
                ownerId = { syncManager.currentOwnerId },
                scope = sessionScope,
                now = { nowMillis() },
                // §10.3: dev-key minter under the mock-billing gate (the sim-verified debug
                // path); otherwise the off-device commerce minter,
                // carrying the bound account's real commerce session token.
                mintClient = iosEntitlementMintClient(
                    cryptoManager = cryptoManager,
                    httpClient = get(),
                    sessionTokenProvider = { publisherClient.currentSessionToken() },
                ),
                // Persist redeemed (commerce-signed) grants — no session to mint through.
                redeemedFlow = publisherClient.redeemedEntitlements,
                // Feed the record-credential fallback the current signed record.
                recordCredentialProvider = get<EntitlementRecordCredentialProvider>(),
                // retention-quota-model.md §5: relay-reported per-owner usage → the gauge's
                // usedBytes. Best-effort; null-safe when offline.
                usageReader = { get<RelayUsageClient>().fetchUsage()?.let { it.usedBytes to it.limitBytes } },
                // D1 read-path signature check (entitlement-trust-anchor-distribution.md).
                verifyEd25519 = cryptoManager::verifyEd25519,
            )
            // The six-hourly, unmetered-only feed refresh. ⚠ Costs nothing
            // when there are no subscriptions — the op finds its own work.
            sessionScope.launch { registerSubscriptionRefreshSchedule() }
            errorEventRecorder = loggingServiceProxyImplementationFactory(
                sessionExecutionContext = this,
                deviceId = { syncManager.currentDeviceId },
                appId = "control",
                appVersion = hostAppVersion(),
                osLabel = "iOS",
            )
            initAppService()
            // Path A (bgproc-scheduling-roles.md): gate the in-process scheduler on the
            // realm's WifiOnly preference. iOS reports unmetered, so this never blocks
            // here — it's the wiring an NWPathMonitor provider would plug into.
            val syncGate = ConstraintSyncGate(
                preferenceService = get<TenshuClient>().preferenceService,
                networkState = IosNetworkStateProvider(),
                scope = sessionScope,
            )
            val storageGate = StorageSyncGate(get<StorageCapacityHolder>(), syncManager)
            val realmRegistrar = RealmRegistrar(syncManager, cryptoManager, get<SyncRelay>())
            val grantBaseUrl: String = get(named("syncRelayUrl"))
            val grantHttpClient: HttpClient = get()
            val grantedRelayFactory: (org.tenshu.sync.common.RealmContext) -> org.tenshu.sync.common.SyncRelay? =
                { realmContext ->
                    org.tenshu.sync.proxy.perRealmDeviceRelay(
                        baseUrl = grantBaseUrl,
                        httpClient = grantHttpClient,
                        context = context,
                        cryptoManager = cryptoManager,
                        realm = realmContext,
                        nowEpochSec = { nowEpochSeconds() },
                    )
                }
            val components = initSyncService(
                // `core-schema-skew-surface.md`: who hosts this runtime, so a client can
                // name the right app to update.
                //
                // ⚠⚠ Resolved, not hard-coded, because **route-1 apps reuse this very module**
                // (`FirstPartyLocalRuntime.assembleBase` applies its own overrides last). Writing
                // Control's id unconditionally here would tell every route-1 app that Control owns
                // its runtime — the exact inversion this row exists to prevent.
                hostAppId = getOrNull<String>(named("hostAppId")) ?: RuntimeIdentity.CONTROL_APP_ID,
                hostAppVersion = hostAppVersion(),
                // The catch-up heartbeat (`ios-sync-activity-indicator.md` §12): on iOS several
                // runtimes share one replica and read each other's beats.
                catchUpHeartbeat = true,
                // Reuse the process's one runtime (`SyncRuntimeHolder`).
                runtimeHolder = get(),
                // ⚠ The same instance the fence reads — see the registration above.
                syncHealthHolder = get(),
                taskRegistry = get(),
                syncManager = syncManager,
                cryptoManager = cryptoManager,
                mergerRegistry = mergerRegistry,
                syncProcessorHolder = processorHolder,
                contentBlobStore = get<ContentBlobStore>(),
                fileDatabase = get<FileDatabase>(),
                // Orphan sweep (core-local-store-orphan-gc.md). ⚠ Without it every leaked block
                // row stays for good. A store that cannot enumerate self-refuses, so passing it is
                // safe regardless of which implementation is bound here.
                objectStore = get<ObjectStore>(),
                syncGate = syncGate,
                // Targeted entitlement fetch (sync-device-catch-up.md §5.3): the scheduler
                // asks the relay for the entitlement row directly when the local one is missing or
                // expired, instead of waiting for the bulk replay to reach it. Same relay the
                // operation layer uses.
                personalRelay = get<SyncRelay>(),
                // The describe backfill's sources (`content-plane-refcount.md` §9.7).
                // ⚠⚠ No device frees relay content: the relay's own sweep is the only freeing path
                // (`content-plane-cr2-b6-spec.md` §2).
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
                sharedRealmRelayFactory = org.tenshu.sync.proxy.sharedRealmRelayFactory(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = context,
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    nowEpochSec = { nowEpochSeconds() },
                ),
                sharedRealmContentBlobStoreFactory = org.tenshu.sync.proxy.sharedRealmContentBlobStoreFactory(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = context,
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    nowEpochSec = { nowEpochSeconds() },
                ),
                grantedRelayFactory = grantedRelayFactory,
                nowMs = { nowMillis() },
                // `sync-hlc-tip-device-identity.md`: the device's HLC watermark, in the
                // SAME container as the replica and guarded by a cross-process `FileAdvisoryLock`.
                //
                // ⚠⚠ Route 1 runs a runtime per first-party app, all under one shared-Keychain
                // deviceId, while the relay keeps a single tip slot per device — so this store
                // being shared across those runtimes is what stops them overwriting that slot with
                // each other's unrelated clock positions.
                //
                // ⚠⚠ Do not substitute a `SecureStorageDelegate`-backed store back in. Its sharing
                // does not track the replica's: `IosSecureStorageDelegate` is app-private while
                // `sharedReplicaDirectory()` is container-or-app-local, so the two disagree in
                // both directions, and a watermark wider than the store it describes forges the
                // relay's tombstone-GC ack.
                hlcWatermarkStore = IosSharedHlcWatermarkStore(),
                platformScheduler = NoOpPlatformScheduler,
            )
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
                // never call it, and the orphans it leaves block re-upload.
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
                // EPC-paired client (ios-proxy-architecture.md §3c): server-pushes seal
                // back through the session's acceptor via the router.
                clientId.startsWith(app.tenshu.control.epc.DefaultEpcPairingHost.CLIENT_ID_PREFIX) ->
                    app.tenshu.control.epc.EpcClientConnection(clientId, router, serializer)
                // No inter-app connection on iOS (`ios-control-handover.md` §C4-RT mapping
                // table).
                else -> error("Unsupported clientId on iOS proxy: $clientId")
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
        // `drive-upload-source-read.md`: resolves a `phasset://` / `file://` source to
        // bytes INSIDE the upload op, so a source that cannot be read fails as a `TResult` via
        // `LocalTaskExecutor` instead of throwing past an unhandled launch. Supplied by the SDK
        // layer, which owns PhotoKit. ⚠ `getOrNull` here, but the factory errors loudly if an
        // upload op actually arrives without it — absence is a DI bug, not a runtime state.
        val uploadSourceReader: IosUploadSourceReader? = getOrNull()

        val contactComponents: ContactProxyComponents = get()
        val realmRegistryHolder: org.tenshu.sync.proxy.RealmRegistryHolder = get()

        val syncRelayProvider = { get<SyncRelay>() }

        val grantBaseUrl: String = get(named("syncRelayUrl"))
        val grantHttpClient: HttpClient = get()
        val grantContext: org.tenshu.core.core.TContext = get()
        val grantedRelayFactory: (org.tenshu.sync.common.RealmContext) -> org.tenshu.sync.common.SyncRelay? =
            { realmContext ->
                org.tenshu.sync.proxy.perRealmDeviceRelay(
                    baseUrl = grantBaseUrl,
                    httpClient = grantHttpClient,
                    context = grantContext,
                    cryptoManager = cryptoManager,
                    realm = realmContext,
                    nowEpochSec = { nowEpochSeconds() },
                )
            }

        val mapRegionComps: org.tenshu.mapregion.proxy.MapRegionProxyComponents = get()

        // ⚠ ONE registry per proxy: it is the state that spans the two calls of a confirmed
        // action (MQ-D7), so a fresh one per operation would make every tap find it empty.
        // ⚠ Per PROCESS on route 1: a confirmation shown in one app cannot be tapped in another.
        val miaActions = miaActionRegistry { nowMillis() }

        val factory: (String) -> OperationImplementation = { operationType ->
            implementationFactory(operationType)
                // Cross-app open (control-cross-app-open.md §5i). ⚠⚠ Unregistered, this op
                // resolves to no implementation, which fails as `Could not handle protocol` a long
                // way from the cause. Registering it is `inference-service.md` §17's rule: an op
                // ships when it is reachable.
                //
                // ⚠⚠ The delivery hands back a URL rather than pushing: under route 1 this proxy
                // is the CALLER's own, and it has no channel to any other app (§5i).
                ?: appOpenTargetImplementationFactory(
                    id = operationType,
                    deviceId = { syncManager.currentDeviceId },
                    delivery = iosTargetDelivery(serializer = get()),
                    // ⚠⚠ Read at CALL time from the executing context, not captured here: the
                    // choice is a realm row that can change while the proxy runs (TT-4).
                    preferredHandlerFor = { context, type -> context.preferredHandlerFor(type) },
                )
                ?: syncProxyImplementationFactory(
                    operationType,
                    syncRelayProvider(),
                    syncManager,
                    cryptoManager,
                    emitterHolder.emitter,
                    realmRegistry = realmRegistryHolder.realmRegistry,
                    grantedRelayFactory = grantedRelayFactory,
                    nowMs = { nowMillis() },
                    registrationGate = StorageSyncGate(storageCapacityHolder, syncManager),
                    // iOS runs in-process with the host app (bgproc §5.5); the
                    // ScheduleDrainLoop drains the registry.
                    platformScheduler = NoOpPlatformScheduler,
                    syncSchedulerHolder = get<org.tenshu.sync.proxy.SyncSchedulerHolder>(),
                    // Task composite: registry + lane API (deref-at-call-time,
                    // same timing pattern as realmRegistryHolder above).
                    taskRegistry = get(),
                    laneEmitter = { get<org.tenshu.sync.common.SyncBlockEmitterHolder>().emitter },
                )
                ?: identityProxyImplementationFactory(operationType)
                // iOS platform overlay: the UIKit/PDFKit `GetPreview` renderer
                // (ios-drive-parity.md P4e) — consulted before the common factory,
                // mirroring desktop's `jvmFileServiceProxyImplementationFactory`.
                // Everything else falls through to the commonMain file-service
                // factory — no android FD-cache / jvm native variant.
                ?: iosFileServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    previewCache,
                    uploadSourceReader,
                    blockEmitterHolder,
                    syncManager.currentDeviceId,
                    storageCapacityHolder,
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
                    null /* no gzip on iOS yet — railLinesIn returns empty (M9-4) */,
                    // `timeline-movement-capture.md` §33.3 — without this the `linesServing` op
                    // exists and always answers "".
                    transitLines = mapRegionComps.transitLines,
                )
                ?: ingestServiceProxyImplementationFactory(operationType)
                ?: preferenceServiceProxyImplementationFactory(operationType)
                ?: hubManagementServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { nowMillis() },
                    issuerDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                )
                ?: homeDeviceServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { nowMillis() },
                    issuerDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                )
                ?: miaServiceProxyImplementationFactory(
                    id = operationType,
                    actions = miaActions,
                    // ⚠⚠ Mia on an iPhone answers and never offers to DO anything
                    // (`mia-query-layer.md` §19). A routine cannot run here at all (the factory
                    // below), so "Run …" would be a button guaranteed to fail; device control and
                    // event creation are withheld with it until each is decided for this platform.
                    // Enforced in planning, so no confirmation token is ever minted.
                    actionOffer = org.tenshu.mia.plan.MiaActionOffer.WITHHELD,
                    // ⚠⚠ Mia's photo refusals name Photos here: on an iPhone Photos alone owns
                    // inference (PIO-D10), so not "this device" (§1.8).
                    photoSearch = org.tenshu.mia.proxy.MiaPhotoSearchHome.PHOTOS_APP,
                    newId = { org.tenshu.core.common.util.generateId() },
                )
                // ⚠⚠ An iPhone stores, lists and edits routines and NEVER runs one
                // (`routines-engine.md` RE-D13). This module is every route-1 app's runtime, all
                // under one device id over one store, and `NoOpPlatformScheduler` gives none of
                // them a wake — so an engine here would be several evaluation loops able to fire
                // the same routine, none of them on time. `RunRoutineNow` and
                // `EvaluateDueRoutines` answer with a typed error instead.
                ?: routineServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { nowMillis() },
                    authorDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                    execution = RoutineExecution.NEVER,
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
                // Semantic photo search. ⚠ Registered in every graph: with no backend in the
                // registry it answers `NO_BACKEND` rather than failing as an unknown operation.
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
                    // ⚠ Resolved per query: open everywhere but in the graph that owns the stores,
                    // where it is the realm-stamp check.
                    storeGate = { get<InferenceStoreGate>().open(fromPass = false) },
                )
                ?: calendarServiceProxyImplementationFactory(
                    id = operationType,
                    // Subscription fetches are Control's, which is what
                    // keeps Calendar's manifest free of INTERNET.
                    fetcher = org.tenshu.calendar.proxy.KtorIcsSubscriptionFetcher(get()),
                    // ⚠ Resolved rather than constructed: on route 1 only the app that
                    // OWNS reminders binds a real presenter (CA-8c). Control resolves the
                    // Noop above and posts nothing; Calendar's runtime overrides it with
                    // `IosNotificationPresenter`, which pre-arms the OS.
                    presenter = get<NotificationPresenter>(),
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

// ─── iOS commerce configuration seam ────────────────────────────────────────────────────────
// iOS can't read process env the way desktop/android do through `System.getenv`/`getProperty`;
// it reads `NSProcessInfo.processInfo.environment` (the same seam the `TENSHU_DEV_RELAY` sim
// override and `DevRelayOverride` use). These mirror the android/desktop `TENSHU_BILLING_MOCK`
// / `TENSHU_COMMERCE_URL` conventions so a sim/dev run behaves identically, while a shipped
// release build fails closed to the real commerce service (never the dev key).
//
// ⚠⚠ **The env read is not a deployment seam** — a shipped iPhone build has no environment to
// read, and `ProxyOriginSelection` ignores the variable in a release binary anyway. The origins a
// release build actually uses are baked in at build time, in the generated [RelayConfig]
// (`control-shared-proxy/build.gradle.kts`); the env vars are the debug/simulator convenience on
// top.

/** Env-string read from the process environment, blank-filtered. */
private fun iosEnv(name: String): String? =
    (NSProcessInfo.processInfo.environment[name] as? String)?.takeIf { it.isNotBlank() }

/** Release-build signal — a shipped (non-debug) binary is release; a debug/sim binary is not. */
@OptIn(ExperimentalNativeApi::class)
private fun iosIsRelease(): Boolean = !kotlin.native.Platform.isDebugBinary

/**
 * Whether the in-process dev-key path is requested. Defaults to the build-time
 * [RelayConfig.BILLING_MOCK] (`true` unless a build set `tenshu.billingMock`), so an unconfigured
 * dev/sim run keeps a working local signup + entitlement with no commerce service standing;
 * overridable at runtime via `TENSHU_BILLING_MOCK`. The [EntitlementMinterSelection.useDevSeed]
 * guard still forces the real path in a release build regardless — the committed `tenshu.dev` key
 * never signs in production, which is why this one keeps honouring its variable in release: the
 * variable cannot weaken anything there.
 */
private fun iosBillingMock(): Boolean =
    iosEnv("TENSHU_BILLING_MOCK")?.toBooleanStrictOrNull() ?: RelayConfig.BILLING_MOCK

/**
 * Commerce origin: the build-time [RelayConfig.COMMERCE_URL] (production unless a build pointed it
 * elsewhere), with `TENSHU_COMMERCE_URL` as a **debug-only** override for a locally-run
 * `:commerce-service` — the [ProxyOriginSelection] discipline `DevRelayOverride` already applies to
 * the relay. Mirror of the android seam, which reads the same generated constant.
 */
private fun iosCommerceUrl(): String = ProxyOriginSelection.resolve(
    envOverride = iosEnv("TENSHU_COMMERCE_URL"),
    isRelease = iosIsRelease(),
    default = RelayConfig.COMMERCE_URL,
)

/**
 * Selects the device-side commerce account client (`commerce-accounts-and-sessions.md` §3–§4).
 * [MockPublisherClient] under the same dev-seed gate as the minter; the real
 * [CommercePublisherClient] otherwise. Commerce origin mirrors the minter's `TENSHU_COMMERCE_URL`.
 * Mirror of `desktopPublisherClient` / `androidPublisherClient`.
 */
private fun iosPublisherClient(
    httpClient: HttpClient,
    storageDelegate: SecureStorageDelegate,
    ownerIdProvider: () -> String?,
): PublisherClient {
    if (EntitlementMinterSelection.useDevSeed(iosBillingMock(), iosIsRelease())) {
        return MockPublisherClient(storageDelegate)
    }
    return CommercePublisherClient(
        baseUrl = iosCommerceUrl(),
        httpClient = httpClient,
        ownerIdProvider = ownerIdProvider,
        storageDelegate = storageDelegate,
    )
}

/**
 * Entitlement minter for iOS (`publishing-commerce-reconciliation.md` §10.3). The in-process
 * dev-key minter under the mock-billing gate (the sim-verified debug path); the
 * off-device commerce minter otherwise — session-gated, so it no-ops until a bound account has a
 * session. Mirror of `desktopEntitlementMintClient` / `androidEntitlementMintClient`. The dev-key
 * release guard (§9.3) forces the commerce path in a release build regardless of the mock flag.
 */
private fun iosEntitlementMintClient(
    cryptoManager: SyncCryptoManager,
    httpClient: HttpClient,
    sessionTokenProvider: () -> String?,
): EntitlementMintClient {
    if (EntitlementMinterSelection.useDevSeed(iosBillingMock(), iosIsRelease())) {
        return DevSeedEntitlementMintClient(
            sign = { msg -> cryptoManager.signEd25519(EntitlementSignature.DEV_SEED, msg) },
            now = { nowMillis() },
        )
    }
    return CommerceEntitlementMintClient(
        baseUrl = iosCommerceUrl(),
        httpClient = httpClient,
        sessionTokenProvider = sessionTokenProvider,
    )
}

/**
 * Entitlement-TOKEN client for iOS — `null` under the mock-billing default (the issuer seed lives
 * only in `:commerce-service`, so no dev-seed token client exists → the
 * [CachingEntitlementTokenProvider] presents no `X-Entitlement` header). Otherwise the real
 * session-gated commerce token client. Mirror of `desktopEntitlementTokenClient`.
 */
private fun iosEntitlementTokenClient(
    httpClient: HttpClient,
    sessionTokenProvider: () -> String?,
): EntitlementTokenClient? {
    if (EntitlementMinterSelection.useDevSeed(iosBillingMock(), iosIsRelease())) return null
    return CommerceEntitlementTokenClient(
        baseUrl = iosCommerceUrl(),
        httpClient = httpClient,
        sessionTokenProvider = sessionTokenProvider,
    )
}

/**
 * The running bundle's short version string — what `ErrorEvent.appVersion` and
 * `RuntimeIdentity.appVersion` report.
 *
 * ⚠⚠ Never a literal like `"dev"`: every error report and every runtime identity would name the
 * same fictional build. A version nobody can act on is worse than none: it looks like an answer.
 *
 * ⚠ Under route 1 this reads the **hosting app's** bundle, not Control's — which is correct: the
 * app is its own runtime owner there, and its version is the one a user would be asked to update.
 */
private fun hostAppVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)
        ?.takeIf { it.isNotBlank() } ?: RuntimeIdentity.UNKNOWN_VERSION
