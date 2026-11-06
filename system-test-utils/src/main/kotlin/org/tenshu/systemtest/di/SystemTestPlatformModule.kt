package org.tenshu.systemtest.di

import org.tenshu.sync.proxy.NoOpPlatformScheduler

import io.ktor.client.HttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.ClientConnection
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.LocalClientConnection
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.calendar.proxy.EventMerger
import org.tenshu.calendar.proxy.ReminderStateMerger
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.initCalendarService
import org.tenshu.file.proxy.FolderMerger
import org.tenshu.health.proxy.DailyActivityMerger
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.file.proxy.jvmFileServiceProxyImplementationFactory
import org.tenshu.identity.proxy.identityProxyImplementationFactory
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.notifications.proxy.initNotificationService
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory
import org.tenshu.mia.proxy.miaActionRegistry
import org.tenshu.mia.proxy.miaServiceProxyImplementationFactory
import org.tenshu.home.device.proxy.homeDeviceServiceProxyImplementationFactory
import org.tenshu.home.device.proxy.initHomeDeviceService
import org.tenshu.routine.proxy.RoutineExecution
import org.tenshu.routine.proxy.initRoutineService
import org.tenshu.routine.proxy.routineServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.hubManagementServiceProxyImplementationFactory
import org.tenshu.hub.management.proxy.initHubManagementService
import org.tenshu.preference.proxy.initPreferenceService
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import org.tenshu.storage.proxy.EntitlementStorageProvider
import org.tenshu.storage.proxy.initEntitlementStorageReader
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.InviteResponsePickup
import org.tenshu.contact.proxy.PendingRedemptionStore
import org.tenshu.contact.proxy.RestInviteRelayClient
import org.tenshu.contact.proxy.contactServiceProxyImplementationFactory
import org.tenshu.contact.proxy.initContactService
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.TombstoneEmitterHolder
import org.tenshu.sync.proxy.RestShareRelayClient
import org.tenshu.sync.proxy.RestSyncRelay
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.sync.proxy.SyncProcessor
import org.tenshu.file.proxy.readBackfillComplete
import org.tenshu.file.proxy.writeBackfillComplete
import org.tenshu.file.proxy.declarableContent
import org.tenshu.sync.proxy.initSyncService
import org.tenshu.sync.proxy.syncProxyImplementationFactory

/**
 * Per-[TestClient] platform bindings — the SystemTest equivalent of
 * `app.tenshu.control.di.platformProxyModule` (`:control-shared` `desktopMain`).
 *
 * Two transport bindings differ from production:
 *
 * - `single<HttpClient>` resolves to the shared
 *   `testApplication.client` passed in via [relayHttpClient]. `RestSyncRelay`
 *   uses it like any other Ktor client; the client dispatches into the in-process
 *   `Application.module()` instead of a real socket. `syncRelayUrl` is empty so
 *   request paths are relative.
 *
 * - `platformClientConnectionFactory` always returns a [LocalClientConnection],
 *   regardless of `clientId`. SystemTests only ever have one client per proxy
 *   (the test app + the proxy live in the same JVM, in the same TestClient).
 *
 * @param deviceId becomes `platformPackageName`; must be unique per TestClient
 *                 so each client's `ClientRegistry` keys its `ExecutionContext`
 *                 distinctly.
 * @param relayHttpClient the long-lived `testApplication.client` from
 *                 [TestSystem]; shared across all clients in the system.
 */
fun systemTestPlatformModule(
    deviceId: String,
    relayHttpClient: HttpClient,
    /**
     * SyncScheduler polling cadence for this client. Default 100ms drains block
     * propagation inside a SystemTest's wall-clock budget; production is 30s.
     * Tests that want to reproduce timing races (e.g. host pushes blocks
     * AFTER the joiner has already pulled once) can pass a much larger value
     * on the host side to suppress its automatic drain.
     */
    pollingIntervalMs: Long = 100,
    /**
     * Supplies the commerce **bearer token** for `X-Entitlement`, the first-class relay credential
     * (`commerce-billing-plane.md` D2). Defaults to `{ null }` — no token — which is what every
     * pre-CE-7 test wants and is also the real posture of a session-less paired device.
     *
     * `CommerceRelaySystemTest` passes a provider backed by a **real `:commerce-service`** running
     * in-process, which is the only way to exercise the token path end to end: a token can only be
     * obtained from a commerce session, so before CE-7 the harness genuinely had no analogue and
     * the token half of the two-carrier design was untested anywhere.
     */
    entitlementTokenProvider: suspend () -> String? = { null },
    /**
     * Wraps the implementation this device resolves for an operation — the identity by default.
     * For a test that needs one device to behave like an older build or a less capable platform
     * (`SyncedThumbnailSystemTest`: a host that never minted the hero rung) without a fake of the
     * whole proxy.
     */
    operationDecorator: (operationType: String, implementation: OperationImplementation) -> OperationImplementation =
        { _, implementation -> implementation },
    /**
     * The content-plane gate — production's metered `WifiOnly` policy. Open by default, which is
     * what every test before it wanted. ⚠ It gates ONLY file bytes: metadata still drains and pulls
     * with it closed (`sync-metered-plane-split.md`), which is exactly the state a device on
     * cellular is in, and the only way a harness can hold an upload's intent in the journal while
     * the rest of the realm moves on around it.
     */
    contentGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
    /**
     * The personal realm's **push** gate — production's local-first storage gate (ADR 0019). Open
     * by default. Closed, this device keeps writing locally and queueing blocks and tombstones, and
     * pushes none of them (`SyncScheduler.drainAndPull`'s `pushAllowed`); they flush on the first
     * tick after it reopens.
     *
     * ⚠ It gates push only — the pull keeps running. That is what makes it the right lever for a
     * *divergence* test and the wrong one for "this device is offline": while two devices both hold
     * it shut neither can learn of the other, so each edits the realm as it last saw it, and
     * reopening them one at a time makes the **ingest order** of the resulting blocks a parameter of
     * the test rather than a race. `ConcurrentMoveVsDeleteSystemTest` is the case it was added for.
     */
    storageGate: org.tenshu.sync.common.SyncGate = org.tenshu.sync.common.SyncGate.AlwaysAllow,
) = module {
    single(named("platformPackageName")) { deviceId }

    // Empty string: the testApplication client routes by path. Production
    // has e.g. "http://localhost:8080" here so a real Netty client knows
    // where to send.
    single(named("syncRelayUrl")) { "" }

    single<HttpClient> { relayHttpClient }

    single<SecureStorageDelegate> { InMemorySecureStorageDelegate() }

    single<SyncManager> {
        SyncManagerImpl(
            cryptoManager = get(),
            objectStore = get(),
            // Mirrors production: the cursor rows are read and written under the `Database`'s lock.
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

    // ⚠⚠ The `single { SyncProcessor(...) }` that stood here is DELETED, for the same reason as the
    // three platform modules': nothing resolved it — the harness takes its processor from
    // `SyncProcessorHolder`, set by `initSyncService` — and it omitted the EI-1 resolver, so the
    // first `get<SyncProcessor>()` would have built a realm-blind one.

    // Track B3 (second-device-entitlement-enforcement.md option 4) — mirrors production's
    // `single { EntitlementRecordCredentialProvider() }` in each app's platformProxyModule.
    // Holds the owner's synced signed `Entitlement` and encodes it as `X-Entitlement-Proof`,
    // which is the ONLY credential a session-less device (every paired joiner, and every
    // harness client) can present. Fed by `initEntitlementStorageReader` below.
    single { EntitlementRecordCredentialProvider() }

    single<SyncRelay> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()

        // JWT minting still uses real wall time. SystemTests run in
        // milliseconds so 60s TTL doesn't fire; tests that need to drive
        // expiration deterministically will need finding 3 (relay-side
        // time injection) — out of scope for v1.
        val tokenProvider = jwtTokenProvider(
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
            ownerIdProvider = { syncManager.currentOwnerId ?: "user-1" },
            // Mirrors production (every app's platformProxyModule wires this pair). Without it the
            // harness could never attach a credential, so no test could observe enforcement — the
            // coverage hole that let a 402 storm reach real hardware (`docs/tech-debt.md`). No
            // Both carriers, as production wires them. The proof header is the session-less path
            // every paired device uses; the token is the session-gated one, supplied by a real
            // commerce service in `CommerceRelaySystemTest` and absent (null) everywhere else.
            entitlementTokenProvider = entitlementTokenProvider,
            entitlementProofProvider = {
                recordCredentialProvider.proofHeader(System.currentTimeMillis())
            }
        )
    }

    single<FileDatabase> { InMemoryFileDatabase() }

    // SR-file-model 3c.1d: personal content-plane client (`RestContentBlobStore`
    // against the same test relay). Wiring it flips the harness's *personal* file
    // bytes onto the two-plane content path (`emitFileContent` + manifest + chunk
    // fetch) — the same path production runs and the prerequisite for the shared
    // path to share its plumbing. (Before this, the harness left it null and personal
    // bytes rode inside the SyncBlock via `emitPropertyBlock`; both paths are
    // exercised by `FileBytesOnDemandSystemTest`, now over the content plane.)
    single<org.tenshu.sync.common.ContentBlobStore> {
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val recordCredentialProvider: EntitlementRecordCredentialProvider = get()
        org.tenshu.sync.proxy.RestContentBlobStore(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = jwtTokenProvider(
                ownerIdProvider = { syncManager.currentOwnerId },
                deviceIdProvider = { syncManager.currentDeviceId },
                signer = { msg ->
                    cryptoManager.signEd25519(
                        privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                        message = msg
                    )
                },
                nowEpochSec = { System.currentTimeMillis() / 1000 }
            ),
            // ⚠⚠ The same credential pair the `SyncRelay` above carries, as every production
            // `PlatformProxyModule` wires it. At their `{ null }` defaults a content `PUT` presents
            // NOTHING while the same device's pushes present a valid credential — so under
            // enforcement every chunk is refused `402` before the quota is consulted, and the `507`
            // path (quota → refuse → recover) is unreachable from any system test. ⚠ Silent: only a
            // test that enforces AND uploads content can notice, and every other test stays green.
            entitlementTokenProvider = entitlementTokenProvider,
            entitlementProofProvider = {
                recordCredentialProvider.proofHeader(System.currentTimeMillis())
            },
        )
    }

    // OnDemand byte-payload resolver — mirrors production's
    // `:control-shared/.../PlatformProxyModule`. `ExecutionContext` looks
    // this up via `context.components[OnDemandResolution.ID]` when
    // `Maybe.get()` encounters a `PendingOnDemandProperty` placeholder.
    single<org.tenshu.core.common.proxy.OnDemandResolution> {
        org.tenshu.sync.proxy.impl.OnDemandResolutionImpl(
            relay = get<SyncRelay>(),
            syncProcessorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>(),
            // SR-file-model 3c.1d: route a shared-realm entity's OnDemand fetch
            // through that realm's own relay + realm-key-aware processor. The
            // holder's `realmRegistry` is populated in the init hook below.
            realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>(),
        )
    }

    single { TombstoneEmitterHolder() }
    single { org.tenshu.sync.common.SyncBlockEmitterHolder() }
    single { org.tenshu.sync.proxy.SyncProcessorHolder() }
    single { org.tenshu.sync.proxy.RealmRegistryHolder() }
    // Mirrors production's `PlatformProxyModule` (android/desktop/ios): the one Koin single the
    // init hook populates with the live `SyncScheduler`. Production's reader is the head-less
    // `DrainSync` op; the harness's is `TestClient.syncScheduler`, which a test needs whenever it
    // has to stand on a **tick boundary** rather than a wall-clock guess — `drainNow()` takes the
    // scheduler's own `tickMutex`, so it cannot return while a tick is in flight.
    single { org.tenshu.sync.proxy.SyncSchedulerHolder() }
    // Task composite (TS-3/TS-4): the per-proxy live-task registry.
    single { org.tenshu.sync.proxy.TaskRegistry(nowMs = { System.currentTimeMillis() }) }

    // Joiner-side: routes decoded `File.Relation.Data` bytes from the
    // sync stream into the local `FileDatabase`.
    single<org.tenshu.core.common.proxy.ExternalPropertyWriter> {
        // Mirrors production (control-shared-proxy): one registered component
        // fanned out per service — file bytes + workout stream blobs.
        org.tenshu.core.common.proxy.DelegatingExternalPropertyWriter(
            org.tenshu.file.proxy.FileBytesPropertyWriter(fileDatabase = get()),
            org.tenshu.health.proxy.WorkoutStreamsPropertyWriter(fileDatabase = get()),
            org.tenshu.contact.proxy.ContactPhotosPropertyWriter(fileDatabase = get()),
        )
    }

    // Contact-invitation mailbox client + dependency bundle (mirrors the
    // production PlatformProxyModule wiring; same JWT seam, fast ack poll
    // for the system-test wall-clock budget).
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
            nowEpochSec = { System.currentTimeMillis() / 1000 }
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
            ackPollIntervalMs = 100L,
            ackPollMaxAttempts = 100,
            // v2 (`contacts-app.md` §9.10) — mirrors production.
            fileDatabase = get(),
            syncBlockEmitterHolder = get(),
            tombstoneEmitterHolder = get(),
        )
    }

    single<ExecutionContext.() -> Unit>(named("platformProxyInitialization")) {
        val init: ExecutionContext.() -> Unit = {
            val syncManager = get<SyncManager>()
            val cryptoManager = get<SyncCryptoManager>()
            val mergerRegistry = get<SemanticMergerRegistry>()
            val emitterHolder = get<TombstoneEmitterHolder>()
            val blockEmitterHolder = get<org.tenshu.sync.common.SyncBlockEmitterHolder>()
            val processorHolder = get<org.tenshu.sync.proxy.SyncProcessorHolder>()
            val realmRegistryHolder = get<org.tenshu.sync.proxy.RealmRegistryHolder>()
            // ⭐ Production's wiring (CR-2 B3b/B3d): the emitter holder gives the folder reaper its
            // `SubtreeReapOrdering` (reap only ≤ T, record the reap point) and the store builds the
            // `OrphanedFileSweeper`. Without them the harness ran the pre-B3b cascade, reaping a peer's
            // writes above T on the device that applied the tombstone while every other device kept
            // them — a divergence production does not have.
            initFileService(
                tombstoneEmitterHolder = emitterHolder,
                fileDatabase = get<FileDatabase>(),
                objectStore = get<org.tenshu.core.common.proxy.db.ObjectStore>(),
            )
            initHealthService()
            initIdentityService()
            initNotificationService()
            initPreferenceService()
            // ⚠ Mirrors production (Control's `PlatformProxyModule`): without the relation
            // maintainer a calendar row arriving by sync is stored but invisible to
            // `CalendarService.mirrors`, and a two-device calendar test would read "absent" for
            // the wrong reason.
            initCalendarService()
            initHubManagementService()
            initHomeDeviceService()
            initRoutineService()
            initContactService { get<org.tenshu.core.common.proxy.FileDatabase>() }
            // Mirrors the reader half of production's `initEntitlementStorage` (the writer half is
            // commerce-side minting, which the harness has no analogue of — tests seed the signed
            // `Entitlement` row directly). The `recordSink` lambda is the same one-liner production
            // installs, so a synced entitlement reaches the credential provider here exactly as it
            // does on a device, and `X-Entitlement-Proof` goes out on gated writes.
            initEntitlementStorageReader(
                provider = EntitlementStorageProvider(),
                ownerId = { syncManager.currentOwnerId },
                now = { System.currentTimeMillis() },
                recordSink = { signed -> get<EntitlementRecordCredentialProvider>().update(signed) },
                // The owner-transition backstop: a joiner's entitlement arrives by sync *after* the
                // realm is adopted, and on an otherwise idle device no incidental save would trip
                // the observer. Production passes the session scope here for the same reason.
                scope = get(named("clientScope")),
            )
            // 100ms scheduler poll matches `RevokeDevicePropagationTest`'s test
            // shape — fast enough to drain block propagation inside a single
            // SystemTest's wall-clock budget. Production uses 30s. Tests can
            // override via the [pollingIntervalMs] parameter.
            // GR-3: per-realm-device-key relay factory for granted realms (used by the
            // grant ops + the self-bind observer). Resolve transport deps once and
            // capture (the factory runs later, when a granted realm registers).
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
            // ⚠⚠ Mirrors production: every `PlatformProxyModule` (android/desktop/ios) builds one
            // of these and hands it to `initSyncService`. Without it `SyncScheduler.realmRegistrar`
            // is null and `recoverRegistration` returns at its FIRST LINE — so a device the relay
            // has forgotten backs off correctly, logs that it is "attempting re-registration", and
            // never attempts anything. The harness looked healthy while making the entire repair
            // path untestable, which is why `RelayAmnesiaRepairSystemTest` could not see it.
            val realmRegistrar = org.tenshu.sync.proxy.RealmRegistrar(
                syncManager,
                cryptoManager,
                get<org.tenshu.sync.common.SyncRelay>(),
            )
            val components = initSyncService(
                taskRegistry = get(),
                syncGate = contentGate,
                storageGate = storageGate,
                syncManager = syncManager,
                cryptoManager = cryptoManager,
                realmRegistrar = realmRegistrar,
                mergerRegistry = mergerRegistry,
                // Hand the per-client `clientScope` so `TestSystem.shutdown`
                // can cancel the scheduler loop instead of leaking it as a
                // zombie hitting the closed Koin scope every tick.
                coroutineScope = get(named("clientScope")),
                pollingIntervalMs = pollingIntervalMs,
                syncProcessorHolder = processorHolder,
                // SR-3b.4c: per-shared-realm relay client (JWT scoped to the shared
                // ownerId, same device A4 signer) against the same test relay.
                sharedRealmRelayFactory = org.tenshu.sync.proxy.sharedRealmRelayFactory(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = context,
                    syncManager = syncManager,
                    cryptoManager = cryptoManager,
                    nowEpochSec = { System.currentTimeMillis() / 1000 },
                ),
                // SR-file-model 3c.1d: per-shared-realm content-plane client (sibling
                // of the relay factory, same JWT scoping) against the same test relay,
                // so a shared file's chunks `PUT`/`GET` against the realm's own content
                // keyspace. `fileDatabase` is handed in too so each realm's
                // `SyncProcessor` builds a realm-key-aware `FileContentManifestApplier`
                // (3b.2b) that can reassemble into the local FS. (Personal
                // `contentBlobStore` is the personal content plane; with it +
                // `fileDatabase` the personal `FileContentManifestApplier` is built too.)
                contentBlobStore = get(),
                fileDatabase = get(),
                // The describe backfill's sources — the same `ExecutionContext` extensions production
                // points at. ⚠ CR-2 B6 removed the device-side sweeps (and the candidate list and
                // recorder) that used to ride these: content is freed only by the relay's own sweep,
                // which `ContentSweepSystemTest` drives directly.
                reclamationSources = org.tenshu.sync.proxy.ReclamationSources(
                    declarableContent = { declarableContent() },
                    readBackfillComplete = { readBackfillComplete(syncManager.currentDeviceId) },
                    writeBackfillComplete = { complete ->
                        writeBackfillComplete(syncManager.currentDeviceId, complete)
                    },
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
                nowMs = { System.currentTimeMillis() },
                platformScheduler = NoOpPlatformScheduler,
            )
            // Inviter-side "pending invites?" piggyback on the sync cycle
            // (contact-invitation-handshake.md §12).
            val invitePickup = InviteResponsePickup(this, get<ContactProxyComponents>())
            components.scheduler.addSyncCycleHook { invitePickup.checkOnce() }
            // Same assignment production's init hook makes — see the `SyncSchedulerHolder` single.
            get<org.tenshu.sync.proxy.SyncSchedulerHolder>().scheduler = components.scheduler
            emitterHolder.emitter = components.tombstoneEmitter
            blockEmitterHolder.emitter = components.syncBlockEmitter
            // SR-file-model 3c.1d: the two-plane content emitter (host side) — without
            // it `ShareFolderIntoRealmImplementation` can't fan a shared file's bytes
            // into the realm content store. Mirrors production's PlatformProxyModule.
            blockEmitterHolder.fileContentEmitter = components.fileContentEmitter
            realmRegistryHolder.realmRegistry = components.realmRegistry
        }
        init
    }

    single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
        val factory: (String) -> ClientConnection = { _ -> LocalClientConnection() }
        factory
    }

    single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
        val fileDatabase: FileDatabase = get()
        val syncManager: SyncManager = get()
        val cryptoManager: SyncCryptoManager = get()
        val emitterHolder: TombstoneEmitterHolder = get()
        val blockEmitterHolder: org.tenshu.sync.common.SyncBlockEmitterHolder = get()
        val realmRegistryHolder: org.tenshu.sync.proxy.RealmRegistryHolder = get()
        val syncRelayProvider = { get<SyncRelay>() }

        // Mode-A share content plane (docs/file-sharing.md): mirror
        // production's `PlatformProxyModule` — wire the real `RestShareRelayClient`
        // so `ShareLink` re-encrypts + uploads chunks (and `RevokeShareLink` issues
        // the relay `DELETE`) instead of degrading to local-only. Without this the
        // harness left the op in degraded mode, so no SystemTest ever exercised the
        // sharer→relay→recipient content round-trip. JWT minting mirrors
        // `RestSyncRelay` / `RestContentBlobStore` above.
        val shareRelayClient = RestShareRelayClient(
            baseUrl = get(named("syncRelayUrl")),
            httpClient = get(),
            context = get(),
            authTokenProvider = jwtTokenProvider(
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
        )

        // GR-3: per-realm-device-key relay factory for the grant ops (mirror of the
        // initSyncService wiring above).
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

        // ⚠ ONE registry per proxy: it is the state that spans the two calls of a confirmed
        // action (MQ-D7), so a fresh one per operation would make every tap find it empty.
        val miaActions = miaActionRegistry { System.currentTimeMillis() }

        val factory: (String) -> OperationImplementation = { operationType -> operationDecorator(operationType,
            implementationFactory(operationType)
                ?: syncProxyImplementationFactory(
                    operationType,
                    syncRelayProvider(),
                    syncManager,
                    cryptoManager,
                    emitterHolder.emitter,
                    realmRegistryHolder.realmRegistry,
                    grantedRelayFactory = grantedRelayFactory,
                    nowMs = { System.currentTimeMillis() },
                    // Task composite (TS-4): mirror of the production wiring.
                    taskRegistry = get(),
                    laneEmitter = { get<org.tenshu.sync.common.SyncBlockEmitterHolder>().emitter },
                )
                ?: identityProxyImplementationFactory(operationType)
                ?: jvmFileServiceProxyImplementationFactory(operationType, fileDatabase)
                ?: fileServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    emitterHolder.emitter,
                    blockEmitterHolder,
                    syncManager.currentDeviceId,
                    shareRelayClient,
                    cryptoManager,
                )
                ?: notificationServiceProxyImplementationFactory(operationType)
                // ⚠ The tombstone emitter is what makes DeleteWorkout converge across devices;
                // without it the delete is device-local and a system test could not tell the
                // difference between "converged" and "never left this process".
                ?: healthServiceProxyImplementationFactory(
                    operationType,
                    fileDatabase,
                    blockEmitterHolder,
                    emitterHolder.emitter,
                )
                ?: preferenceServiceProxyImplementationFactory(operationType)
                ?: calendarServiceProxyImplementationFactory(
                    id = operationType,
                    nowEpochMs = { System.currentTimeMillis() },
                    // As in production: without it a mirror stop never leaves the device.
                    tombstoneEmitter = emitterHolder.emitter,
                )
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
                    // The JVM system-test proxy stands in for Android/desktop Control.
                    photoSearch = org.tenshu.mia.proxy.MiaPhotoSearchHome.THIS_DEVICE,
                    newId = { org.tenshu.core.common.util.generateId() },
                )
                ?: routineServiceProxyImplementationFactory(
                    operationType,
                    nowEpochMs = { System.currentTimeMillis() },
                    authorDeviceId = { syncManager.currentOwnerId?.let { syncManager.currentDeviceId } },
                    execution = RoutineExecution.EXECUTES,
                    // Without it a routine delete never leaves this device (`SyncObserver.onDataDeleted` is a no-op).
                    tombstoneEmitter = emitterHolder.emitter,
                )
                ?: contactServiceProxyImplementationFactory(operationType, get<ContactProxyComponents>())
                ?: error("No executor found for operation $operationType")
        ) }
        factory
    }
}
