package app.tenshu.hub

import app.tenshu.control.data.DesktopMasterKeyStore
import app.tenshu.control.data.DesktopSecureStorageDelegate
import app.tenshu.control.di.buildPlatformProxyModule
import app.tenshu.control.di.buildProxyModule
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.JvmFileDatabase
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.sync.proxy.SyncManagerImpl
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.file.client.fileSyncDefaultPolicyOrNull
import org.tenshu.file.client.setFileSyncDefaultPolicy
import org.tenshu.preference.client.preferenceService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.getSyncStatus

/**
 * The headless Hub runtime (hub proposal §13 **H1**): the production proxy assembly of
 * `control-desktop` — `buildProxyModule()` + the desktop `buildPlatformProxyModule()` —
 * with a thin last-wins hub override module and **no Compose anywhere**. Same shape as
 * the route-1 `FirstPartyLocalRuntime`: fresh module instances per assembly (Koin caches
 * singletons on the module's factories), overrides loaded last.
 *
 * The hub overrides are deliberately minimal:
 *  - its own `platformPackageName` (`app.tenshu.hub`) so client-registry/session identity
 *    is the hub's, not Control-desktop's;
 *  - durable storage rooted at an explicit [dataRoot] (object store + secret bucket), with
 *    the secret bucket persisted via [FileWrappingKeyStore] — on Linux the inherited
 *    desktop wiring would fall back to in-memory secrets and re-pair on every restart;
 *  - a **durable, disk-backed [FileDatabase]** ([JvmFileDatabase], H3) rooted at the hub's
 *    own `<data-dir>/content/`, so content bytes survive restarts and relay loss. ⚠ This
 *    is no longer a *replacement* for the inherited desktop binding — desktop Control now
 *    binds the same class (2026-08-03); what stays hub-specific is only the root.
 *
 * Everything else — relay clients, JWT minting, sync scheduler, service inits, offer-flow
 * pairing ops — is the untouched production graph.
 *
 * H3 (hub proposal §12) additionally makes the paired hub the realm's **keep-everything
 * replica**: [startBackupLoop] periodically (a) pins this device's default sync policy to
 * [SyncPolicy.KEEP_OFFLINE] (a device-scoped `DeviceSetting` — it never alters any other
 * device's policy) and (b) drains the existing `HeadlessSafe` [OfflineKeeper] op, which
 * fetches every kept-offline file whose bytes are still missing. No new fetch pipeline —
 * the loop is only a JVM *driver* for the keeper (the desktop platform module wires
 * `NoOpPlatformScheduler`, and the in-process `ScheduleDrainLoop`'s keeper item ticks at
 * a fixed 6 h — too coarse for a device whose whole job is the replica). Eviction never
 * runs on the hub: the keeper is download-only by design, `EvictFileBytes` is a
 * client-invoked op no hub code calls, and the retention-model local-ceiling/LRU slice
 * (P2) is deliberately not wired here.
 */
class HubRuntime private constructor(
    private val koinApp: KoinApplication,
    val dataRoot: File,
) {
    val koin: Koin get() = koinApp.koin
    val client: TenshuClient get() = koin.get()
    val syncManager: SyncManager get() = koin.get()

    /** Owns the H3 backup loop; cancelled (and joined) in [shutdown]. */
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var backupJob: Job? = null

    /** Epoch ms this runtime was assembled — the H4 status row's `startedAtEpochMs`. */
    val startedAtEpochMs: Long = System.currentTimeMillis()

    /** Epoch ms of the last completed [runBackupPassNow]; `0` = none yet (H4 status). */
    @Volatile
    var lastBackupPassAtEpochMs: Long = 0L
        private set

    /** Completed backup passes this process — the H4 tests' exactly-once probe. */
    @Volatile
    var backupPassCount: Long = 0L
        private set

    /**
     * H4 hook: invoked after every completed backup pass (periodic or
     * commanded) so the management agent can refresh the published
     * [org.tenshu.hub.management.HubStatus] row. Registered by
     * [HubManagementAgent.start]; `null` before H4 wiring.
     */
    @Volatile
    var onBackupPassCompleted: (suspend () -> Unit)? = null

    /** Realm-bound already? Decides whether `main()` enters the pairing CLI first. */
    fun isPaired(): Boolean = syncManager.currentOwnerId != null

    /**
     * Wakes the proxy. The production `AsyncTaskExecutor` builds + inits its
     * `ExecutionContext` lazily on the first op — which runs the whole platform
     * initialization hook (service inits, sync scheduler, relay wiring). A headless
     * process has no UI to fire that first op, so issue a benign local status read.
     */
    suspend fun start() {
        val status = client.syncService.getSyncStatus()
        check(status !is TResult.Failure) { "hub proxy failed to start: $status" }
    }

    /**
     * Start the H3 **backup loop**: every [intervalMs], if the hub is paired,
     * run one [runBackupPassNow] (keep-everything policy pin + keeper drain).
     * Idempotent — a second call while running is a no-op. Best-effort per pass:
     * one failed pass (relay unreachable) is logged and retried next tick.
     */
    fun startBackupLoop(intervalMs: Long = DEFAULT_BACKUP_INTERVAL_MS) {
        if (backupJob?.isActive == true) return
        backupJob = runtimeScope.launch {
            while (isActive) {
                try {
                    runBackupPassNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    println("tenshu-hub: backup pass failed (will retry): $t")
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * One synchronous backup pass — the unit the loop ticks and the system tests
     * drive deterministically. No-op while unpaired (the keeper would no-op anyway:
     * it bails without a device id).
     *
     * (a) **Keep-everything policy**: pins this device's `file.sync.defaultPolicy`
     * `DeviceSetting` to [SyncPolicy.KEEP_OFFLINE] at the root — the same
     * device-scoped preference row the offline keeper reads (longest-prefix
     * `SyncRuleSet`; no overrides needed when the default keeps everything). Only
     * written when the explicitly-set row differs, so a routine boot writes nothing.
     *
     * (b) **Keeper drain**: dispatches the existing `HeadlessSafe` [OfflineKeeper]
     * op by id on the hub's session `ExecutionContext` — exactly how the
     * `ScheduleDrainLoop` dispatches it, just at the hub's own cadence.
     */
    suspend fun runBackupPassNow() {
        val deviceId = syncManager.currentDeviceId ?: return
        if (!isPaired()) return

        if (!keepEverythingEnsured) {
            val current = client.preferenceService.fileSyncDefaultPolicyOrNull(deviceId)
            if (current == SyncPolicy.KEEP_OFFLINE) {
                keepEverythingEnsured = true
            } else {
                val written = client.preferenceService
                    .setFileSyncDefaultPolicy(deviceId, SyncPolicy.KEEP_OFFLINE)
                check(written !is TResult.Failure) {
                    "failed to pin the hub's keep-everything sync policy: $written"
                }
                keepEverythingEnsured = true
                println("tenshu-hub: device sync policy pinned to keep-everything (KEEP_OFFLINE at /)")
            }
        }

        koin.get<ClientRegistry>()
            .getExecutionContext(PACKAGE_NAME)
            .executeAsync<Data>(operationId = OfflineKeeper.ID, caller = null, params = null)

        lastBackupPassAtEpochMs = System.currentTimeMillis()
        backupPassCount += 1
        onBackupPassCompleted?.invoke()
    }

    @Volatile
    private var keepEverythingEnsured = false

    /**
     * Quiesce the proxy — stop the backup loop, cancel and join the proxy's
     * background loops (sync scheduler, schedule drain, constraint gate) — then
     * close the Koin application. The same clean-exit contract `control-desktop`'s
     * `main()` honours.
     */
    suspend fun shutdown() {
        backupJob?.cancelAndJoin()
        backupJob = null
        runtimeScope.coroutineContext[Job]?.cancelAndJoin()
        koin.get<ProxyGateway>().shutdownAndJoin()
        koinApp.close()
    }

    companion object {
        /** The hub's client/package identity — also its subdirectory-free data-dir owner. */
        const val PACKAGE_NAME = "app.tenshu.hub"

        /**
         * Default backup-loop cadence (15 min). Deliberately much faster than the
         * inherited 6 h `ScheduleDrainLoop` keeper item: a fresh copy is the hub's
         * whole job, and each pass is cheap when nothing is missing (a metadata
         * walk; no relay traffic for files whose bytes are present).
         */
        const val DEFAULT_BACKUP_INTERVAL_MS = 15L * 60 * 1000

        /**
         * Assemble (but do not yet [start]) a hub runtime.
         *
         * @param dataRoot durable state directory (object store, secrets, wrap key).
         * @param syncIntervalMs sync-scheduler cadence; `null` keeps the 30s production
         *        default of the desktop init hook.
         * @param overrides extra last-wins Koin modules — the system tests bind the
         *        in-process relay's HttpClient + a fast cadence here.
         */
        fun assemble(
            dataRoot: File,
            syncIntervalMs: Long? = null,
            overrides: List<Module> = emptyList(),
        ): HubRuntime {
            dataRoot.mkdirs()
            val hubModule = module {
                single(named("platformPackageName")) { PACKAGE_NAME }
                // ⚠ The lambda parameter is NAMED on purpose. Inside a Koin `single { }` the implicit
                // `it` is the definition's `ParametersHolder`, and `{ it }` here bound THAT under this
                // name — so the desktop hook's `getOrNull<Long>` found nothing and every hub, the
                // system tests and `--sync-interval-ms` alike, ran at the 30 s production cadence.
                // `HubRuntimeAssemblyTest` pins the resolved type. docs/tech-debt.md "The hub's sync
                // cadence override never reached its scheduler" (2026-09-13).
                syncIntervalMs?.let { interval ->
                    // A non-positive cadence makes `SyncScheduler.awaitOrTimeout` return at once and
                    // the loop spin; the CLI rejects it, and so does the programmatic seam.
                    require(interval > 0) { "syncIntervalMs must be > 0, got $interval" }
                    single(named("syncPollingIntervalMs")) { interval }
                }
                single<SecureStorageDelegate> {
                    DesktopSecureStorageDelegate(
                        storageDir = dataRoot,
                        masterKeyStore = DesktopMasterKeyStore(
                            storageDir = dataRoot,
                            wrappingKeyStore = FileWrappingKeyStore(File(dataRoot, "wrap.key")),
                        ),
                    )
                }
                single<ObjectStore> {
                    runBlocking {
                        openSqliteObjectStore(
                            path = File(dataRoot, "objects.db").absolutePath,
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
                // H3: durable content bytes under the hub's OWN data root. ⚠ Only the root is
                // hub-specific now — desktop Control binds this same `JvmFileDatabase` (2026-08-03)
                // rather than the in-memory one this override originally replaced, so this is a
                // last-wins re-root, not a different implementation. See the class KDoc.
                single<FileDatabase> { JvmFileDatabase(File(dataRoot, "content")) }
            }
            val koinApp = koinApplication {
                modules(
                    listOf(buildProxyModule(), buildPlatformProxyModule(), hubModule) + overrides,
                )
            }
            return HubRuntime(koinApp, dataRoot)
        }
    }
}
