package org.tenshu.sdk.ios

import app.tenshu.ingest.DeviceIdentity
import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.calendar.data.calendarControlReachability
import app.tenshu.notify.IosNotificationPresenter
import app.tenshu.notify.NotificationPresenter
import app.tenshu.reachability.ControlReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.epc.RealmKeyMaterial
import org.tenshu.core.common.epc.RealmKeyMaterialBridge
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.getOrNull
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.getSyncStatus

/**
 * The route-1 **in-process** Calendar runtime — the Calendar twin of [ContactsLocalRuntime] /
 * [MapsLocalRuntime] / [TimelineLocalRuntime] / [FitLocalRuntime] / [DriveLocalRuntime], over the
 * [FirstPartyLocalRuntime] substrate (`ios-first-party-route-1-migration.md`).
 *
 * It runs the production proxy graph in Calendar's own process over the shared App-Group replica
 * Control writes, so it works whenever Control has provisioned key material once — no live
 * channel, and (by construction, being in-process) none of the client-resubscription gap
 * `core-client-resubscription.md` describes. Like Maps and Contacts, Calendar is **born** on route
 * 1: there is no EPC tier to migrate off, so there is no `EpcCalendarClient` beside this.
 *
 * ⚠ **No new service wiring.** Every service Calendar reads is already hosted by the route-1
 * graph: `:calendar-service` (`initCalendarService` runs in the iOS `PlatformProxyModule`) and
 * `:preference-service` (the default calendar, the reminder defaults, this device's hidden set).
 * That is what makes this file thin.
 *
 * ⚠⚠ **Calendar owns its own reminders on this platform.** The graph's default presenter
 * is `NoopNotificationPresenter`, bound in the iOS `PlatformProxyModule`; this runtime overrides it
 * with [IosNotificationPresenter] through the last-wins `appOverrides` slot. That override is the
 * whole difference between Control and Calendar here, and it is load-bearing:
 * `UNUserNotificationCenter` is per *app*, so if Control bound the real presenter too, every
 * reminder would arrive twice — once from each process running this same graph over the shared
 * replica. The mirror of `ios-drive-parity.md` P6a, where Drive rather than Control is route 1's
 * background syncer.
 *
 * Whole-blob key custody (migration proposal §4 → **C**): first-party apps are realm co-owners
 * on iOS route 1.
 */
object CalendarLocalRuntime {

    /** Calendar's client id for the in-process `LocalClientConnection` (route-1, not Control's). */
    const val CALENDAR_CLIENT_ID: String = "app.tenshu.calendar.ios"

    /** Calendar's per-app Keychain bucket — the assembly's delegate and [deprovision] hit the same items. */
    private const val SECURE_STORAGE_SERVICE = "app.tenshu.calendar.ios.secure-storage"

    /** The app-agnostic route-1 substrate (assembly + adopt + lifecycle), configured for Calendar. */
    private val runtime = FirstPartyLocalRuntime(
        FirstPartyLocalRuntime.Config(
            clientId = CALENDAR_CLIENT_ID,
            secureStorageService = SECURE_STORAGE_SERVICE,
        ),
    )

    /** One assembled runtime: the Koin graph plus the repository built over its client. */
    class Assembly internal constructor(
        internal val koin: Koin,
        val tenshuClient: TenshuClient,
        val calendarRepository: CalendarRepository,
        internal val reachability: ControlReachability,
        internal val reachScope: CoroutineScope,
        internal val presenter: IosNotificationPresenter,
    )

    private var installed: Assembly? = null

    /** Whether [install] has run (the shipping gate reads this to skip re-assembly). */
    fun isInstalled(): Boolean = installed != null

    /**
     * The shipping gate probe: whether Control has provisioned the route-1 key material into the
     * shared Keychain. `CalendarIosApp` routes on it — provisioned → [install] + live, absent →
     * the honest not-connected surface.
     */
    fun isProvisioned(): Boolean = runtime.isProvisioned()

    /**
     * Assemble a runtime without touching the global surface — the testable entry point.
     * [replicaDirectory] / [secureStorage] override the store + Keychain for hermetic tests;
     * production omits them.
     *
     * The **one** reachability engine is built here and shared, exactly as the Compose modules do
     * (`fit-app.md` §9.14 — one signal per app, not one per repository).
     */
    fun assemble(
        replicaDirectory: String? = null,
        secureStorage: SecureStorageDelegate? = null,
        handedOffMaterial: RealmKeyMaterial? = null,
        relayBaseUrl: String? = null,
    ): Assembly {
        // ⚠⚠ Built BEFORE the graph and handed in, rather than resolved from it afterwards: the
        // ops factory closes over whatever `NotificationPresenter` Koin holds at construction, so
        // an override installed later would be read by nothing.
        val presenter = IosNotificationPresenter()
        val base = assembleBase(replicaDirectory, secureStorage, handedOffMaterial, relayBaseUrl, presenter)
        val client = base.tenshuClient
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // ⚠ This graph's own scope ends with the graph when the shared store's host stops it
        // (`ios-shared-store-sqlite.md` Appendix B.16).
        runtime.onCurrentGraphStop { scope.cancel() }
        return Assembly(
            koin = base.koin,
            tenshuClient = client,
            calendarRepository = TenshuCalendarRepository(
                client = client,
                // ⚠ A resolver, not a value — the same wiring `calendarRepositoryModule()` uses on
                // Compose, and for the same reason: sync identity is not up the instant the app
                // starts, and an id read once and found blank would strand every per-device
                // preference for the session (see `CalendarDeviceIdentity`).
                deviceIdentity = CalendarDeviceIdentity {
                    client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()
                },
                reachability = null /* iOS route-1 wires no reachability engine into the repository (ADR 0025 §8.6) */,
            ),
            reachability = calendarControlReachability(client, probeScope = scope, controlHasRealm = app.tenshu.reachability.RealmProbe.None("iOS route-1: provisioning is ControlProvisioning's, not this engine's")),
            reachScope = scope,
            presenter = presenter,
        )
    }

    /**
     * Ask the OS for permission to show reminders, and report whether it was granted.
     *
     * ⚠⚠ **The shell's call, not the runtime's** — `NotificationPresenter`'s own KDoc says asking
     * belongs to the shell, and the reason is sharper on iOS: the prompt is once per install and
     * unrepeatable, so firing it at proxy init would spend it on a launch the user had no reason
     * to connect with reminders. A refusal is not an error; it is a state the calendar keeps
     * working in, minus its alerts.
     *
     * ⚠ Returns `false` when nothing is installed yet, rather than assembling a runtime as a side
     * effect of asking a permission question.
     */
    fun requestNotificationAuthorization(onResult: (Boolean) -> Unit) {
        val assembly = installed ?: return onResult(false)
        assembly.presenter.requestAuthorization(onResult)
    }

    /**
     * Re-read the OS's notification authorization into the presenter's cache.
     *
     * ⚠ Worth calling on every foreground: authorization can be revoked in Settings while the app
     * is backgrounded, and a presenter that kept saying yes would silently drop every post.
     */
    fun refreshNotificationAuthorization(onResult: (Boolean) -> Unit) {
        val presenter = installed?.presenter ?: return onResult(false)
        presenter.refreshAuthorization(onResult)
    }

    /**
     * Whether a reminder posted now would actually reach the user.
     *
     * ⚠ The presenter's cache, so it is only as fresh as the last
     * [refreshNotificationAuthorization]. `false` when nothing is installed — which is correct
     * rather than merely convenient: with no runtime there are no reminders to deliver either.
     */
    fun canPostNotifications(): Boolean = installed?.presenter?.canPost() ?: false

    /**
     * Production path: read the durable realm-key hand-off ([RealmKeyMaterialBridge]) and
     * assemble over the shared replica — **live** (adopted realm device) when Control has
     * provisioned the material, read-only-sealed otherwise — then install the real repository on
     * [CalendarSurface]. Idempotent; the runtime is process-lifetime.
     */
    fun install() {
        if (installed != null) return
        runtime.rebuildWith(::rebuildGraph)
        val assembly = assemble(
            handedOffMaterial = RealmKeyMaterialBridge.read(),
            relayBaseUrl = devRelayOverride(),
        )
        installed = assembly
        CalendarSurface.installRepository(
            calendarRepository = assembly.calendarRepository,
            reachability = assembly.reachability.state,
        )
        // ⚠⚠ The first calendar + the default-calendar preference — the iOS twin of the Compose
        // `App`'s launch-time `ensureDefaultCalendar`. Without it a fresh realm has no calendar at
        // all on iOS: the editor's Save stays disabled with nothing on screen to say why, and the
        // headless create (Mia's) fails with "no default calendar". Idempotent and fixed-id, so a
        // second device seeding the same realm converges; best-effort, like the Compose call — a
        // refused write leaves it for the next launch, and logged.
        // ⚠ It can run before this device's first sync, like the Compose one; the seed writes
        // nothing over existing rows (docs/tech-debt.md, "Calendar seeds its default calendar
        // before the first sync").
        assembly.reachScope.launch {
            bestEffort("Calendar -> seeding the default calendar at launch") {
                assembly.calendarRepository.ensureDefaultCalendar()
            }
        }
        // The graph is complete; only now the shared store's host takes it, and may stop it at
        // once (a not-ready stand-in). Nothing when the process has no binding.
        runtime.graphReady()
    }

    /**
     * ⚠ How the shared store's host rebuilds this app's graph after a fence or a not-ready open, on
     * the main thread: the re-provision path ([install] after `installed` is dropped;
     * `ios-shared-store-sqlite.md` Appendix B.16). Registered by [install]; never called
     * without a binding.
     */
    private fun rebuildGraph() {
        installed = null
        install()
    }

    /**
     * The graph [assemble] builds, with the presenter handed in. Split out so the per-app
     * service-version guard (`DeclaredServiceVersionsTest`, `ios-per-app-frameworks.md` §6.2) can
     * assemble Calendar's exact graph: the real presenter reaches `UNUserNotificationCenter` at
     * construction, which aborts the bundle-less test host.
     */
    internal fun assembleBase(
        replicaDirectory: String?,
        secureStorage: SecureStorageDelegate?,
        handedOffMaterial: RealmKeyMaterial?,
        relayBaseUrl: String?,
        presenter: NotificationPresenter,
    ): FirstPartyLocalRuntime.Base = runtime.assembleBase(
        replicaDirectory = replicaDirectory,
        secureStorage = secureStorage,
        handedOffMaterial = handedOffMaterial,
        relayBaseUrl = relayBaseUrl,
        // The one thing that makes this app, and not Control, the device's reminder source.
        appOverrides = module { single<NotificationPresenter> { presenter } },
    )

    /**
     * Tear down Calendar's side of the route-1 provisioning (the teardown twin): seal the running
     * graph (`SyncManager.lock()`), stop the reachability probe, purge Calendar's per-app custody
     * copies (so the persisted joiner payload cannot silently resurrect the adoption), and
     * restore the honest not-connected surface. The shared replica is left alone (Control's
     * store).
     */
    fun deprovision() {
        val assembly = installed
        installed = null
        CalendarSurface.installNotConnected()
        if (assembly != null) {
            assembly.reachScope.cancel()
            runCatching { assembly.koin.get<SyncManager>().lock() }
            runtime.purgeCustody(assembly.koin.get<SecureStorageDelegate>())
        } else {
            runtime.purgeCustody(runtime.standaloneDelegate())
        }
    }

    /** Whether Calendar still holds adopted custody at rest (the joiner payload) — the deprovision trigger. */
    fun hasResidualCustody(): Boolean {
        installed?.let { return runtime.hasResidualCustody(it.koin.get<SecureStorageDelegate>()) }
        return runtime.hasResidualCustody(runtime.standaloneDelegate())
    }
}
