@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.file.proxy.ios.IosUploadSourceReader
import org.tenshu.storage.proxy.EntitlementRecordCredentialProvider
import app.tenshu.control.data.CachingEntitlementTokenProvider
import app.tenshu.drive.data.FileMapper
import app.tenshu.drive.data.TenshuContactsRepository
import app.tenshu.drive.data.TenshuFilesRepository
import app.tenshu.drive.data.TenshuMembershipRepository
import app.tenshu.drive.domain.LogUnexpectedErrorUseCase
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import platform.Foundation.create
import platform.Foundation.writeToFile
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.common.proxy.db.sharedReplicaDirectory
import org.tenshu.core.common.security.jwtTokenProvider
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.UploadInput
import org.tenshu.sync.proxy.RestContentBlobStore

/**
 * The route-1 **in-process** Drive runtime (ios-drive-parity.md P1a): Drive runs the proxy stack
 * in its OWN process over the **shared App-Group replica** Control writes
 * (the SQLite store in `sharedReplicaDirectory("replica")`), so reads never wait for a suspendable
 * co-resident Control (the wake wall route-2 EPC hits).
 *
 * The assembly reuses the **production** Control-on-iOS wiring (`proxyModule` +
 * `platformProxyModule`, the same modules `ControlSurface.installRealRuntime()` boots — the
 * "mirror production wiring" rule) with a small last-wins override module for the bits where
 * Drive's role differs:
 *  - `platformPackageName` → Drive's own client id (the `LocalClientConnection` key);
 *  - `SecureStorageDelegate` → Drive's own per-app Keychain service (Drive must never mint
 *    secrets into a bucket named for Control; adopting Control's *shared* identity via
 *    `RealmKeyMaterialBridge` is the hand-off (P1b/Q1), not a storage-name collision).
 *
 * Without handed-off key material the realm in this process stays sealed — no genesis, no
 * unlock — which the runtime tolerates by design (Control itself boots pre-genesis on this graph
 * and idles): the sync scheduler stays gated and saves emit no blocks.
 */
object DriveLocalRuntime {

    /** Drive's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val DRIVE_CLIENT_ID: String = "app.tenshu.drive.ios"

    /** Drive's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.drive.ios.secure-storage"

    /**
     * The app-agnostic route-1 substrate (assembly + adopt + provisioning/custody
     * lifecycle), configured for Drive. Drive is the first consumer of [FirstPartyLocalRuntime]
     * (ios-first-party-route-1-migration.md P1); the Drive-specific bits — its repo set, the
     * background-upload overrides, the surface install + backup, and the DEV-harness probes — stay
     * here.
     */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = DRIVE_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** The Swift-registered background-upload transport (P3b); null until the app installs it. */
    private var backgroundUploadBridge: BackgroundUploadBridge? = null

    // Wall-clock through the one sanctioned wrapper (the NoDirectClockAccess audit) — for the
    // device-JWT + entitlement-proof headers the background uploader re-mints per enqueue.
    private val systemClock = SystemClock()
    private fun nowEpochSeconds(): Long = systemClock.nowEpochMillis() / 1000
    private fun nowMillis(): Long = systemClock.nowEpochMillis()

    /** One assembled runtime: the Koin graph plus the client surface built over it. */
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    class Assembly internal constructor(
        // Public for Photos' cross-app tests (`PhotosOwnedInferenceStoresTest` assembles
        // Drive's graph beside Photos'), hidden from Swift so the surface is unchanged
        // (`ios-per-app-frameworks.md` P3; P1 did the same for core's cross-module internals).
        @kotlin.native.HiddenFromObjC val koin: Koin,
        val client: EpcDriveClient.DriveClient,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The SHIPPING gate probe (ios-drive-parity.md P1d): whether Control has provisioned the
     * route-1 key material into the shared Keychain. This — not the C3 presence token — is
     * what actually makes Drive work on this device, so `RootView` routes on it: provisioned →
     * [install] + Files (live), absent → the Connect-to-Control screen. Cheap enough for a
     * launch/foreground probe.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point.
     * [replicaDirectory] overrides the store location (tests point two assemblies at one
     * temp dir to prove the cross-runtime read); production omits it and rides the
     * `platformProxyModule` binding (shared App-Group container, app-local fallback).
     * [secureStorage] overrides the Keychain-backed delegate (headless tests have no
     * Keychain entitlement); production omits it.
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
    ): Assembly {
        // Drive-specific last-wins overrides. The app-agnostic base overrides (client id, secure
        // storage, replica dir + writer lock, dev relay) + the graph assembly + adopt live in
        // [runtime]; this module only carries what is Drive's own.
        // ⚠ B.16 (228): the background-upload manager's work is this graph's, so it runs
        // on a scope of the graph's own, not the process's `ioScope`, ended when the shared
        // store's host stops the graph. The journal and the staged chunks are files, so the
        // rebuilt graph's manager re-drives whatever was left (`resumeIncomplete` at its install).
        val uploadScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default + SupervisorJob())
        val appOverrides = module {
            // D6 (`ios-per-app-frameworks.md`): the base binds the `file://`-only reader, because
            // the base is in every app's framework. Drive is the app whose uploads carry
            // `phasset://` sources, so Drive binds the PhotoKit-capable reader over it.
            single<IosUploadSourceReader> { IosPhotoUploadSource }
            // P3b (ios-drive-parity.md): route the personal-realm content-plane WRITE through a
            // background URLSession so a bulk upload survives app suspension/termination (O3-B).
            // `getChunk` stays foreground (on-demand reads are interactive). This last-wins
            // override replaces the platform module's plain `RestContentBlobStore` binding for
            // Drive's runtime only; Control-on-iOS keeps the foreground store (on iOS route-1
            // Drive is the background syncer). `emitFileContent` is untouched — it still calls
            // `putChunk`, behind the staging decorator.
            single<BackgroundUploadJournal> {
                BackgroundUploadJournal(filePath = "${sharedReplicaDirectory("upload-staging")}/journal")
            }
            single<ContentBlobStore>(named("p3bForegroundContentBlobStore")) {
                val syncManager: SyncManager = get()
                val cryptoManager: SyncCryptoManager = get()
                RestContentBlobStore(
                    baseUrl = get(named("syncRelayUrl")),
                    httpClient = get(),
                    context = get(),
                    authTokenProvider = jwtTokenProvider(
                        ownerIdProvider = { syncManager.currentOwnerId },
                        deviceIdProvider = { syncManager.currentDeviceId },
                        signer = { msg ->
                            cryptoManager.signEd25519(
                                privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                                message = msg,
                            )
                        },
                        nowEpochSec = { nowEpochSeconds() },
                    ),
                    entitlementTokenProvider = { get<CachingEntitlementTokenProvider>().token() },
                    entitlementProofProvider = { get<EntitlementRecordCredentialProvider>().proofHeader(nowMillis()) },
                )
            }
            single<BackgroundUploadManager> {
                val syncManager: SyncManager = get()
                val cryptoManager: SyncCryptoManager = get()
                BackgroundUploadManager(
                    stagingDir = sharedReplicaDirectory("upload-staging"),
                    journal = get(),
                    baseUrl = get(named("syncRelayUrl")),
                    foreground = get(named("p3bForegroundContentBlobStore")),
                    authTokenProvider = jwtTokenProvider(
                        ownerIdProvider = { syncManager.currentOwnerId },
                        deviceIdProvider = { syncManager.currentDeviceId },
                        signer = { msg ->
                            cryptoManager.signEd25519(
                                privateKey = syncManager.getDeviceSigningKeyPair().privateKey,
                                message = msg,
                            )
                        },
                        nowEpochSec = { nowEpochSeconds() },
                    ),
                    entitlementTokenProvider = { get<CachingEntitlementTokenProvider>().token() },
                    entitlementProofProvider = { get<EntitlementRecordCredentialProvider>().proofHeader(nowMillis()) },
                    bridgeProvider = { backgroundUploadBridge },
                    scope = uploadScope,
                    context = get(),
                )
            }
            single<ContentBlobStore> {
                BackgroundUrlSessionContentBlobStore(
                    foreground = get(named("p3bForegroundContentBlobStore")),
                    manager = get(),
                )
            }
        }
        // The app-agnostic substrate assembles the production graph over the shared replica and,
        // when Control has handed off keys, adopts Control's ONE device identity (joiner path, no
        // master key) so this is a live realm device.
        val base = runtime.assembleBase(
            replicaDirectory = replicaDirectory,
            secureStorage = secureStorage,
            handedOffMaterial = handedOffMaterial,
            relayBaseUrl = relayBaseUrl,
            appOverrides = appOverrides,
        )
        val client = base.tenshuClient
        // B.16 (228): the backup loop built over this client and the upload scope end with it.
        runtime.onCurrentGraphStop {
            uploadScope.cancel()
            DriveBackupSurface.detachIfOver(client)
        }
        // B.16 (244): given up with no rebuild to follow, its realm's kept answers go with it.
        runtime.onCurrentGraphAbandoned { DriveBackupSurface.dropKeptAnswers() }
        // B.17 (265): a kept answer is replayed at each foreground while this graph serves.
        runtime.onCurrentGraphForeground { DriveBackupSurface.replayKeptAnswers() }
        val filesRepository = TenshuFilesRepository(
            client,
            FileMapper(),
            LogUnexpectedErrorUseCase(),
            IosFilesRepositoryPlatformDelegate(),
        )
        return Assembly(
            koin = base.koin,
            client = EpcDriveClient.DriveClient(
                tenshuClient = client,
                filesRepository = filesRepository,
                contactsRepository = TenshuContactsRepository(client),
                membershipRepository = TenshuMembershipRepository(client),
            ),
        )
    }

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and
     * assemble over the shared replica — **live** (adopted realm device: emission + relay
     * cycle active) when Control has provisioned the material, read-only-sealed otherwise —
     * then install all three repositories on [DriveReadSurface] (the route-1 twin of
     * [DriveReadSurface.installRealClient]). Idempotent; the runtime is process-lifetime
     * (its Koin graph owns live scopes and the open store).
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        try {
            installLocked()
        } catch (t: Throwable) {
            // ⚠⚠ **Name the cause before it is destroyed.** This function is called bare from Swift
            // (`RootView.recheck()`), and it declares no `@Throws` — so anything it raises is an
            // *undeclared* exception at the ObjC boundary and Kotlin/Native answers with
            // `Kotlin_ObjCExport_trapOnUndeclaredException` → `abort()`. The message and stack are
            // gone by the time the crash report is written, so without this the report names the
            // trap and nothing else.
            //
            // ⚠ Rethrown, deliberately. A runtime that failed to install must not carry on looking
            // healthy — Drive would show an empty, writable-looking Files screen over no realm.
            // The abort stays; only the silence goes.
            println("DriveLocalRuntime: install FAILED — ${t::class.simpleName}: ${t.message}")
            println(t.stackTraceToString())
            throw t
        }
    }

    /**
     * ⚠ `ios-shared-store-sqlite.md` Appendix B.16: how the shared store's host rebuilds
     * this app's graph after a fence or a not-ready open, on the main thread: the re-provision path
     * ([install] after `installed` is dropped). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    private fun installLocked() {
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        DriveReadSurface.installRepository(assembly.client.filesRepository)
        DriveReadSurface.installSharingRepositories(
            assembly.client.contactsRepository,
            assembly.client.membershipRepository,
        )
        // P5: the real per-device preference seams (offline pinning + sync rules + the listing
        // display prefs — sort + list/grid view mode) over the same in-process client — the
        // `:preference-service` store replaces the Noop seams, so the choices survive navigation
        // and restart on device.
        DriveReadSurface.installPreferences(
            app.tenshu.drive.data.TenshuOfflinePreferences(assembly.client.tenshuClient),
            app.tenshu.drive.data.TenshuSyncRulesPreferences(assembly.client.tenshuClient),
            app.tenshu.drive.data.TenshuDisplayPreferences(assembly.client.tenshuClient),
        )
        // P3b: re-drive any chunks staged before a previous termination (idempotent, and a
        // no-op until the Swift background-upload bridge is installed).
        assembly.koin.get<BackgroundUploadManager>().resumeIncomplete()
        // P6b: start the Synced-Folders (photo auto-backup) loop over the shared engine + the iOS
        // PhotoKit source. Gates on the `enabled` pref (default off) — inert until the user turns
        // it on in Settings.
        DriveBackupSurface.start(assembly.client.tenshuClient, assembly.client.filesRepository)
        // The capsule's upload half (`ios-sync-activity-indicator.md` §6a.1): the run's count and
        // the chunks still with the background session. Both live in this process and nowhere
        // else, which is why only Drive installs them.
        SyncActivitySurface.installUploads(
            combine(
                DriveBackupSurface.observeUploadRun(),
                assembly.koin.get<BackgroundUploadManager>().inFlight.count,
            ) { run, transfersInFlight -> UploadInput(run, transfersInFlight) },
        )
        // ⚠ Anything that arrived before a sink existed — see `PendingOpenUrl`. Harmless when the
        // sink already drained it at launch, which is the normal case.
        PendingOpenUrl.drain()
        // The graph is complete; only from here does the shared store's host take it, and it may
        // stop it at once (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * The row a `tenshu-drive://open?...` URL names, or `null` for anything that is not one of ours
     * (`control-cross-app-open.md` §5i, TT-3).
     *
     * ⚠⚠ **Decodes with no runtime, no realm and no Koin graph** — see `OpenTargetDecoder`. The
     * route-1 graph is installed only after a Control session is found, and a *launch* URL arrives
     * before any graph exists: a decode that read the graph's parser would leave an unprovisioned
     * app unable to recognise an open at all. Nothing about parsing core types needs app state.
     *
     * ⚠⚠ Null is the ordinary case: a URL scheme is not exclusive — any app on the device may
     * `openURL` at us — so the Swift caller must treat null as "not for me" and carry on, never as
     * an error. ⚠ Only URLs that *look* like ours are ever logged, so an
     * unrelated app's `openURL` costs nothing.
     */
    fun openTargetFrom(url: String): OpenedTarget? {
        val decoded = decodeOpenTargetUrl(url)
        if (decoded == null && url.startsWith("tenshu-") && url.contains("://open?")) {
            println("TenshuOpenTarget: our scheme, but the payload did not decode")
        }
        return decoded
    }

    /**
     * P3b (ios-drive-parity.md): register the Swift background-upload transport
     * (`drive-ios/Sources/BackgroundUpload.swift`). Called once at app launch. Setting it also
     * re-drives anything left staged by a previous run — the resume that no-ops while no bridge
     * is installed (ordering-independent with [install]).
     */
    fun installBackgroundUploadBridge(bridge: BackgroundUploadBridge) {
        backgroundUploadBridge = bridge
        installed?.koin?.get<BackgroundUploadManager>()?.resumeIncomplete()
    }

    /**
     * P3b: the `URLSessionTaskDelegate.didCompleteWithError` callback from Swift. Forwards to the
     * manager, which acks (drops the journal entry + spent staging file) on 2xx, re-mints + re-
     * enqueues on 401/403 (expired device JWT), and leaves other failures for the next resume.
     */
    fun onBackgroundUploadCompleted(ref: String, statusCode: Int, errorMessage: String?) {
        installed?.koin?.get<BackgroundUploadManager>()?.onCompleted(ref, statusCode, errorMessage)
    }

    /**
     * P6a (ios-drive-parity.md): the background-refresh entry the BGAppRefreshTask handler
     * drives. On iOS route-1 **Drive is the background syncer**: adoption rehydrates from the
     * persisted joiner payload with no user present (durable custody is the point), while
     * Control's realm stays sealed until the user unlocks — so an OS wake of Drive can run a
     * REAL drain. Ensures the live runtime (no-op when already installed), kicks the one-shot drain (`syncNow` → the in-process
     * `ScheduleDrainLoop`, which is alive in a BG-launched process), and holds a bounded
     * window inside the ~30 s BGAppRefresh budget for pushes/pulls to flush: a bounded window, not
     * drain-completion signalling — there is no suspend-until-drained seam.
     */
    suspend fun runBackgroundRefresh(maxWaitSeconds: Int = 15): String {
        if (!isProvisioned()) return "bg-refresh: not provisioned"
        // The assembly's own open is admitted by the implicit token (B.5 (80)): the binding,
        // and so an entry's token, exists only once it has been installed here.
        install()
        // ⚠⚠ B.15: the wake's work under an entry token, so the gate admits it and the
        // token's expiry ends it. Unwrapped with no binding installed.
        return SharedReplicaEntries.withProtection("drive-refresh") {
            // B.13: the SQLite store's worker trigger at a wake. Nothing without a binding.
            runtime.wakeSharedReplica()
            val repo = installed?.client?.filesRepository ?: return@withProtection "bg-refresh: install failed"
            runCatching { repo.syncNow() }
            // P6b: an OS wake also nudges a photo-backup catch-up (best-effort; gates on the enabled
            // pref + storage/network). Drive is the iOS background syncer, so this is where a
            // background wake picks up new photos — the P6a-task-drives-backup path.
            runCatching { DriveBackupSurface.backupNow() }
            kotlinx.coroutines.delay(maxWaitSeconds * 1000L)
            "bg-refresh: drain window (${maxWaitSeconds}s) elapsed"
        } ?: "bg-refresh: no protected time, or it expired (S0b B.15)"
    }

    /**
     * P2a (ios-drive-parity.md): tear down Drive's side of the route-1 provisioning. Called by
     * the shipping gate when the bridge material has VANISHED while Drive holds adopted custody
     * — Control cleared it deliberately (realm reset / sign-out / revocation), and the
     * threat-model A4 duty says Drive's own at-rest copies must go with it. Without this, the
     * persisted joiner payload would silently resurrect the adopted identity on the next launch.
     *
     * What it does: seals the running graph (`SyncManager.lock()` — purges the in-memory realm
     * keys so emission + the relay drain idle, the RT-8 semantics), purges Drive's per-app
     * custody copies (joiner payload, ownerId, A4 blob, deviceId + the realm latches), and
     * swaps the surfaces back to fakes. The replica in the shared container is left alone —
     * it is Control's store, and a re-provision makes everything live again with no data loss.
     *
     * The sealed old graph stays orphaned until process death (no live teardown plumbing);
     * that is the same residual window as Control's own "Lock now", and the relay side is
     * already closed for a *revoked* device (its JWT stops verifying once purged).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        DriveReadSurface.installFakes()
        if (assembly != null) {
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            // Vanish detected at launch, before any runtime booted: purge through a standalone
            // delegate on the SAME Keychain bucket (no proxy graph needed).
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Drive still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }

    internal fun purgeCustody(delegate: SecureStorageDelegate) = runtime.purgeCustody(delegate)

    /**
     * DEV-harness probe (P1c): report the adoption state — whether the runtime rides Control's
     * handed-off identity (live realm device: emission + relay cycle) or runs sealed
     * (read-only). Never renders key material; ids are prefix-truncated.
     */
    fun debugAdoptionState(): String {
        val assembly = installed ?: return "adoption: not installed"
        val syncManager = assembly.koin.get<SyncManager>()
        val owner = syncManager.currentOwnerId
        return if (owner != null && syncManager.isEnabled()) {
            "adoption: LIVE as owner=${owner.take(12)}… device=${syncManager.currentDeviceId.take(12)}…"
        } else {
            "adoption: SEALED (no bridge material — read-only replica access)"
        }
    }

    /**
     * DEV-harness probe (`-localRuntime` banner): list "/" through the installed runtime's plain
     * non-paged read path, off the main thread. Splits an op-layer failure from a paging-layer one
     * when the Files list misbehaves over the real replica.
     */
    suspend fun debugListRoot(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val assembly = installed ?: return@withContext "not installed"
        runCatching { assembly.client.filesRepository.getFiles("/").first() }
            .fold(
                onSuccess = { listing -> "getFiles(/) ok: ${listing.size} rows ${listing.map { it.path }}" },
                onFailure = { error -> "getFiles(/) threw: $error" },
            )
    }

    /**
     * DEV-harness probe: drive the PAGED pipeline (the app's actual Files path —
     * `DriveReadSurface.create` → `TenshuPagingPresenter`) over the installed runtime on either
     * dispatcher and report whether the initial refresh ever lands. Splits "real Pager never
     * loads on Main" from "real Pager never loads at all" — the fake repo ships
     * `PagingData.from` (no `PagingSource.load`), so the app has never exercised a real Pager
     * through the presenter on iOS before route-1.
     */
    suspend fun debugPagedProbe(onMain: Boolean): String {
        val assembly = installed ?: return "not installed"
        val label = if (onMain) "paged(Main)" else "paged(Default)"
        val context = if (onMain) kotlinx.coroutines.Dispatchers.Main else kotlinx.coroutines.Dispatchers.Default
        val scope = kotlinx.coroutines.CoroutineScope(context)
        return try {
            val bound = DriveReadSurface.create(assembly.client.filesRepository, scope, context, "/")
            val settled = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                // Settled == the initial refresh landed (empty root is a valid result).
                bound.presenter.loadState.first {
                    it != null && it.refresh !is androidx.paging.LoadState.Loading
                }
            }
            if (settled != null) {
                "$label ok: refresh=${settled.refresh::class.simpleName} ${bound.presenter.snapshot.value.size} rows"
            } else {
                "$label TIMED OUT after 10s (refresh never settled)"
            }
        } catch (error: Throwable) {
            "$label threw: $error"
        } finally {
            scope.cancel()
        }
    }

    /**
     * DEV-harness probe (P1b): run the full mutation set — create / rename / move / delete —
     * through the installed runtime's real op path, each write one shared-replica transaction.
     * The mounted Files list refreshes live. Idempotent-ish: re-runs report benign failures on
     * already-applied steps.
     */
    suspend fun debugMutationCycle(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val repo = installed?.client?.filesRepository ?: return@withContext "not installed"
        fun ok(result: app.tenshu.drive.data.RepositoryResult<*>): String =
            if (result is app.tenshu.drive.data.RepositoryResult.Success) "ok" else "FAILED($result)"
        val steps = listOf(
            "create /P1b docs → ${ok(repo.createFolder("/P1b docs"))}",
            "create /P1b temp → ${ok(repo.createFolder("/P1b temp"))}",
            "create /P1b gone → ${ok(repo.createFolder("/P1b gone"))}",
            "rename docs → renamed: ${ok(repo.renameFile("/P1b docs", "P1b renamed"))}",
            "move temp into renamed: ${ok(repo.moveFile("/P1b temp", "/P1b renamed"))}",
            "delete gone: ${ok(repo.deleteFile("/P1b gone"))}",
        )
        "mutations: " + steps.joinToString("; ")
    }

    /**
     * DEV-harness probe (P3a): upload a real file through the production ingest path —
     * temp file → `IosFilesRepositoryPlatformDelegate` (bytes + source mtime) → the common
     * byte-based upload op → durable rows + local plaintext. The mounted Files list refreshes
     * live with a document row.
     */
    suspend fun debugUploadProbe(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val repo = installed?.client?.filesRepository ?: return@withContext "not installed"
        val dir = platform.Foundation.NSTemporaryDirectory() + "p3a-upload"
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        val path = "$dir/P3a-upload.txt"
        val written = memScoped {
            val bytes = "route-1 upload probe".encodeToByteArray()
            platform.Foundation.NSData.create(
                bytes = allocArrayOf(bytes),
                length = bytes.size.toULong(),
            ).writeToFile(path, atomically = true)
        }
        if (!written) return@withContext "upload: temp write failed"
        "upload: ${repo.uploadFile(uriString = path, parentPath = "/", fileName = "P3a-upload.txt")}"
    }

    /**
     * DEV-harness probe (P5): create a REAL contact invite through the live contacts repo —
     * the relay's invite mailbox routes with the adopted device-JWT. A returned
     * `https://tenshu.app/i/…#k=…` link is the proof the sharing cluster runs live over the
     * route-1 runtime (needs a dev relay and a device registered with it, which under
     * enforcement takes a real entitlement). The fragment key never leaves the device except
     * inside the link itself.
     */
    suspend fun debugCreateInviteProbe(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        if (installed == null) return@withContext "invite: not installed"
        val contacts = DriveReadSurface.installedContactsRepository()
        when (val result = runCatching { contacts.createInvite(intentKind = "", intentLabel = "") }.getOrNull()) {
            is app.tenshu.drive.data.RepositoryResult.Success ->
                "invite: LIVE link ${result.value.take(34)}…"
            else -> "invite: failed ($result)"
        }
    }

    /**
     * DEV-harness probe (P4): upload tiny embedded image + PDF fixtures through the real
     * ingest path so the preview screens have decrypted content to render headlessly
     * (`-previewImage` / `-previewPdf`). Re-runs report AlreadyExists harmlessly — the
     * fixture is already there and the preview renders it either way.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun debugUploadPreviewFixtures(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val repo = installed?.client?.filesRepository ?: return@withContext "not installed"
        val dir = platform.Foundation.NSTemporaryDirectory() + "p4-fixtures"
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null,
        )
        fun write(name: String, bytes: ByteArray): String? {
            val path = "$dir/$name"
            val ok = memScoped {
                platform.Foundation.NSData.create(
                    bytes = allocArrayOf(bytes),
                    length = bytes.size.toULong(),
                ).writeToFile(path, atomically = true)
            }
            return path.takeIf { ok }
        }
        val pngPath = write("p4-image.png", kotlin.io.encoding.Base64.decode(TINY_PNG_BASE64))
            ?: return@withContext "fixtures: png write failed"
        val pdfPath = write("p4-doc.pdf", tinyPdfBytes())
            ?: return@withContext "fixtures: pdf write failed"
        // Delete-then-upload: a row surviving from a PREVIOUS launch has no local plaintext
        // (the iOS FileDatabase is in-memory) and the harness runs relay-less — a fresh upload
        // restores the local bytes the preview reads.
        suspend fun freshUpload(name: String, path: String): app.tenshu.drive.data.RepositoryResult<Unit> {
            repo.deleteFile("/$name")
            return repo.uploadFile(uriString = path, parentPath = "/", fileName = name)
        }
        val image = freshUpload("p4-image.png", pngPath)
        val pdf = freshUpload("p4-doc.pdf", pdfPath)
        "fixtures: image=$image pdf=$pdf"
    }

    /** A 1×1 PNG — scaledToFit renders it as a solid fill, plenty for a screenshot proof. */
    private const val TINY_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /**
     * A minimal single-page PDF (blue square, no font resources) with a CORRECT xref table —
     * offsets computed at build time, so strict parsers accept it as-is.
     */
    internal fun tinyPdfBytes(): ByteArray {
        val content = "0.2 0.4 0.9 rg 50 50 200 200 re f"
        val builder = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R >>",
        ).forEachIndexed { index, body ->
            offsets += builder.length
            builder.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        offsets += builder.length
        builder.append("4 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        val xrefAt = builder.length
        builder.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { builder.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        builder.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        return builder.toString().encodeToByteArray()
    }

    /**
     * DEBUG seeding for the store screenshots (`docs/how-to/store-screenshots.md`): mirror the
     * fixture tree at [rootDir] into the realm through the SAME op path a user's upload takes —
     * `createFolder` for each directory, `uploadFile` for each file, so the source mtime becomes
     * the row's date and the upload mints the thumbnail exactly as it would for a picked file.
     *
     * ⚠ This seeds DATA only. Nothing here renders; the screens captured afterwards are the
     * shipping `RootView` over whatever this wrote. Hidden entries and `manifest.json` (the
     * fixture's provenance record) are skipped. Idempotent by path: an entry already in the
     * realm is left alone, so a second run over the same simulator adds nothing.
     */
    suspend fun debugSeedFixture(rootDir: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val repo = installed?.client?.filesRepository ?: return@withContext "seed: not installed"
        val fm = platform.Foundation.NSFileManager.defaultManager
        var folders = 0
        var files = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        suspend fun existing(parent: String): Set<String> =
            runCatching { repo.getFiles(parent).first().map { it.path }.toSet() }.getOrDefault(emptySet())

        suspend fun mirror(hostDir: String, realmParent: String) {
            val names = (fm.contentsOfDirectoryAtPath(hostDir, error = null) ?: return)
                .filterIsInstance<String>()
                .filter { !it.startsWith(".") && it != "manifest.json" }
                .sorted()
            val present = existing(realmParent)
            for (name in names) {
                val hostPath = "$hostDir/$name"
                val realmPath = if (realmParent == "/") "/$name" else "$realmParent/$name"
                val type = fm.attributesOfItemAtPath(hostPath, error = null)
                    ?.get(platform.Foundation.NSFileType)
                val isDir = type == platform.Foundation.NSFileTypeDirectory
                if (isDir) {
                    if (realmPath in present) {
                        skipped++
                    } else {
                        val result = repo.createFolder(realmPath)
                        if (result is app.tenshu.drive.data.RepositoryResult.Success) folders++
                        else failures += "$realmPath: $result"
                    }
                    mirror(hostPath, realmPath)
                } else if (realmPath in present) {
                    skipped++
                } else {
                    val result = repo.uploadFile(uriString = hostPath, parentPath = realmParent, fileName = name)
                    if (result is app.tenshu.drive.data.RepositoryResult.Success) files++
                    else failures += "$realmPath: $result"
                }
            }
        }

        mirror(rootDir.trimEnd('/'), "/")
        val summary = "seed: folders=$folders files=$files skipped=$skipped failed=${failures.size}"
        if (failures.isEmpty()) summary else summary + "\n" + failures.joinToString("\n")
    }

    /**
     * DEV-harness probe: create a folder through the installed runtime's REAL op path so the
     * Files screen has a row to render (a fresh genesis realm is empty), which also exercises
     * the Pager-invalidation observer end-to-end in-app. Idempotent by path.
     */
    suspend fun debugSeedFolder(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val assembly = installed ?: return@withContext "not installed"
        val path = "/Route-1 demo"
        val alreadyThere = runCatching {
            assembly.client.filesRepository.getFiles("/").first().any { it.path == path }
        }.getOrDefault(false)
        if (alreadyThere) return@withContext "seed: already present"
        "seed: ${assembly.client.filesRepository.createFolder(path)}"
    }
}
