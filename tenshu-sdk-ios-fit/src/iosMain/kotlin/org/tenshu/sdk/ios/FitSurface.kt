package org.tenshu.sdk.ios

import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.IosRecordingEngine
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.data.MockRecordingEngine
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingEngineFactory
import app.tenshu.fit.data.RegionBundleResolver
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.ui.AppViewModel
import app.tenshu.fit.ui.screens.browse.BrowseViewModel
import app.tenshu.fit.ui.screens.category.CategoryDetailViewModel
import app.tenshu.fit.ui.screens.category.CategoryMetric
import app.tenshu.fit.ui.screens.history.HistoryViewModel
import app.tenshu.fit.ui.screens.home.HomeViewModel
import app.tenshu.fit.ui.screens.medical.MedicalViewModel
import app.tenshu.fit.ui.screens.onboarding.OnboardingViewModel
import app.tenshu.fit.ui.screens.profile.ProfileViewModel
import app.tenshu.fit.ui.screens.record.RecordViewModel
import app.tenshu.fit.ui.screens.workout.WorkoutDetailViewModel
import app.tenshu.fit.data.ActivityFile
import app.tenshu.fit.data.ActivityFileParser
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.ui.screens.datasource.BatchImportViewModel
import app.tenshu.fit.ui.screens.datasource.DataSourceDetailViewModel
import app.tenshu.fit.data.ActivityFileSource
import app.tenshu.fit.data.IosArchiveReader
import app.tenshu.fit.data.TakeoutParser
import app.tenshu.fit.data.xml.IosXmlReader
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import org.tenshu.core.common.transport.TaskTransport

/**
 * Wires the **real** Fit view-model layer (`:fit-shared-logic`) to iOS so the SwiftUI `fit-ios` app
 * can resolve each screen's view model and observe its `StateFlow` through SKIE (proposal §9.3). The
 * Fit twin of [TimelineSurface] / [DriveReadSurface].
 *
 * Fit's Koin DI lives in `:fit-shared-ui` (Compose, not on iOS); this object is the minimal
 * hand-wiring the SwiftUI client needs instead of `fitModules()`.
 *
 * The data source is an **injected** [FitRepository] (default [MockFitRepository]); the live-EPC
 * tier later installs a real `FitProjectionRepository` over the EPC channel via [installRepository]
 * with zero Swift change (the Drive Tier-1/Tier-2 shape).
 */
object FitSurface {

    // Default to the honest "not connected" surface, NOT the mock: Fit has no store of its own, so
    // until the live EPC client installs there is nothing real to show, and fabricated mock data
    // would mislead (fit-app.md §11.7). The mock is now a test/preview surface only (installMock).
    private var installed: FitRepository = NotConnectedFitRepository()

    /**
     * The recorder the next [recordViewModel] binds. Defaults to the deterministic
     * [MockRecordingEngine]; a live client swaps in the real [IosRecordingEngine] (GPS / BLE-HR /
     * step counter), mirroring how Android's `realDataModule` binds a `RecordingEngineFactory`
     * (fit-app.md §11.1). Kept in lockstep with [installed]: the real repository is the recorder's
     * `addWorkout` write target.
     */
    private var recordingEngineFactory: RecordingEngineFactory = RecordingEngineFactory { MockRecordingEngine(it) }

    /**
     * The live client's background-refresh action (R-iOS-5), or null when only the mock is
     * installed. Set by [installRealClient], cleared by [installMock] — see [runBackgroundRefresh].
     */
    private var backgroundRefresh: (suspend () -> String)? = null

    /**
     * The live tier's `HealthImportBootstrap.refresh` (fit-app.md §9.18), or null when nothing live
     * is installed. Installed by whichever tier built the bootstrap ([installRealClient] for EPC,
     * [FitLocalRuntime.install] for route 1) and cleared by [installNotConnected] / [installMock].
     *
     * ⚠⚠ Separate from [backgroundRefresh] on purpose. That one is the `BGAppRefreshTask` action —
     * the OS decides when it runs (≥15 min apart, never on the simulator) — so it cannot answer
     * "the user just opened the app". This one can, and it debounces, which is why the seam is the
     * bootstrap rather than another copy of the pass.
     */
    private var foregroundRefresh: (() -> Unit)? = null

    /**
     * The offline-basemap region resolver [workoutViewModel] binds (`maps-region-delivery.md` §7).
     * Null on the not-connected / mock tiers — exactly like the Compose DI's `getOrNull()`, so a
     * workout route renders on the bundled demo basemap rather than failing. Installed alongside
     * the repository by whichever tier brought a live [org.tenshu.core.common.TenshuClient]
     * ([installRealClient] for EPC, [FitLocalRuntime.install] for route 1), because the resolver
     * reaches `MapRegionService` over that same client.
     */
    private var regions: RegionBundleResolver? = null

    /** Install the [FitRepository] subsequently-built view models bind (live-EPC tier). */
    fun installRepository(repository: FitRepository) {
        installed = repository
    }

    /** Install the [RecordingEngineFactory] the next [recordViewModel] builds its engine over. */
    fun installRecordingEngineFactory(factory: RecordingEngineFactory) {
        recordingEngineFactory = factory
    }

    /**
     * Assemble the **real** Fit client over an established EPC [channel] and install its
     * [FitProjectionRepository][app.tenshu.fit.data.FitProjectionRepository] — every screen built
     * afterwards reads the live `:health-service` store instead of the mock, with no SwiftUI change.
     * The Fit twin of [DriveReadSurface.installRealClient]; delegates to [EpcFitClient.assembleClient].
     *
     * The live op round-trip needs a Control serving EPC over a reachable relay (the on-device gate);
     * [FitLiveClientSession] owns the channel lifecycle and the [installMock] fallback.
     */
    fun installRealClient(channel: TaskTransport) {
        val fit = EpcFitClient.assembleClient(channel)
        installed = fit.fitRepository
        // The live tier records for real: swap the mock ticker for the CoreLocation/CoreMotion/
        // CoreBluetooth engine, writing through the just-installed live repository (R-iOS-1).
        recordingEngineFactory = RecordingEngineFactory { IosRecordingEngine(it) }
        backgroundRefresh = fit.backgroundRefresh
        // The region rail rides the same client (Control's proxy hosts MapRegionService) — see
        // [regions]. Best-effort: a failure to reach the catalog just leaves the demo basemap.
        regions = fitRegionResolver(fit.tenshuClient)
    }

    /**
     * Install a **route-1 in-process** Fit runtime ([FitLocalRuntime], ios-first-party-route-1-
     * migration.md P2): the already-built [FitProjectionRepository][app.tenshu.fit.data.FitProjectionRepository]
     * over the shared App-Group replica, plus its background-refresh action. The route-1 twin of
     * [installRealClient] — no EPC channel; works whenever Control has provisioned key material once.
     * Also swaps in the real [IosRecordingEngine] recorder (route-1 Fit records for real).
     */
    fun installLocalRuntime(repository: FitRepository, backgroundRefresh: suspend () -> String) {
        installed = repository
        recordingEngineFactory = RecordingEngineFactory { IosRecordingEngine(it) }
        this.backgroundRefresh = backgroundRefresh
    }

    /**
     * Restore the honest **not-connected** surface (the live-client teardown / fallback target) —
     * empty reads + `controlReachable = false` so the shell shows the "Can't reach Tenshu Control"
     * banner instead of fabricated mock data (fit-app.md §11.7).
     */
    fun installNotConnected() {
        installed = NotConnectedFitRepository()
        recordingEngineFactory = RecordingEngineFactory { MockRecordingEngine(it) }
        backgroundRefresh = null
        foregroundRefresh = null
        regions = null
    }

    /**
     * Install the deterministic [MockFitRepository] + mock recorder. **Tests and previews only** —
     * it is no longer a runtime surface (the runtime not-connected state is [installNotConnected]).
     */
    fun installMock() {
        installed = MockFitRepository()
        recordingEngineFactory = RecordingEngineFactory { MockRecordingEngine(it) }
        backgroundRefresh = null
        foregroundRefresh = null
        regions = null
    }

    /**
     * Import a file the user picked — a **Google Takeout archive**, or a bare `.gpx`/`.tcx`/`.fit`
     * activity (fit-app.md §16). [fileUrl] is a `file://` URL string, the shape Swift's
     * `.fileImporter` yields; the caller must have copied the document into the app's own container
     * first (the P3a security-scope lesson: the scope is released when the picker callback returns,
     * long before this async read runs).
     *
     * This is the ONLY route by which real Google Fit *history* reaches iOS — Google Fit feeds
     * neither Health Connect nor HealthKit, so the years of it live solely in a Takeout export.
     *
     * A `.zip` is streamed member-by-member ([IosArchiveReader]); anything else is read whole as one
     * activity file. An archive that will not parse falls back to the single-file path rather than
     * failing the import — a mis-detected container should cost one bad parse, not the whole pick.
     * Outcome + progress flow back through the ordinary `importStatus`, exactly like a HealthKit
     * refresh, so the Swift surface needs no import-specific state.
     *
     * Returns a short human-readable result for the caller to log; never throws.
     */
    suspend fun importPickedFile(fileUrl: String): String {
        val path = NSURL.URLWithString(fileUrl)?.path
            ?: fileUrl.removePrefix("file://")
        val name = path.substringAfterLast('/')
        val parser = TakeoutParser(ActivityFileParser(xml = IosXmlReader()))

        val source = if (name.endsWith(".zip", ignoreCase = true)) {
            IosArchiveReader.source(path, parser::isRelevant)
        } else {
            null
        } ?: run {
            val bytes = NSData.dataWithContentsOfFile(path)?.toByteArray()
                ?: return "file import: could not read $name"
            ActivityFileSource.of(listOf(ActivityFile(name = name, bytes = bytes)))
        }

        return runCatching { installed.importFiles(source); "file import: done ($name)" }
            .getOrElse { "file import: FAILED ($it)" }
    }

    /**
     * Run a background HealthKit import refresh + recorder-outbox drain over the live client
     * (R-iOS-5, fit-app.md §11.5), then hold the task window open for [maxWaitSeconds] so reactive
     * work settles. Driven by the `BGAppRefreshTask` (`FitBackgroundRefresh`). A no-op
     * ("bg-refresh: not live") when no live client is installed — the Swift caller takes the route-1
     * `FitLocalRuntime.runBackgroundRefresh` path whenever Fit is provisioned, so this one runs only
     * unprovisioned. Best-effort: never throws out of the task handler.
     */
    suspend fun runBackgroundRefresh(maxWaitSeconds: Int = 15): String {
        val refresh = backgroundRefresh ?: return "bg-refresh: not live"
        val status = runCatching { refresh() }.getOrElse { "bg-refresh: failed ($it)" }
        if (maxWaitSeconds > 0) kotlinx.coroutines.delay(maxWaitSeconds * 1000L)
        return status
    }

    /**
     * Install the offline-basemap [RegionBundleResolver] [workoutViewModel] binds — the C3
     * data-path wiring. Called by the tier that owns the live client; `null` restores the
     * demo-basemap-only behaviour. Internal: Swift never names it, so the exported Obj-C header is
     * unchanged.
     */
    internal fun installRegionResolver(resolver: RegionBundleResolver?) {
        regions = resolver
    }

    /**
     * Install the live tier's [foregroundRefresh] action — the same shape as
     * [installRegionResolver], and installed by the same tiers. `null` restores the no-op.
     */
    internal fun installForegroundRefresh(action: (() -> Unit)?) {
        foregroundRefresh = action
    }

    /**
     * Re-read the platform health source because the app just came to the foreground
     * (`fit-app.md` §9.18). No-op when nothing live is installed, and debounced by
     * `HealthImportBootstrap` — so the shell may call it on every `scenePhase == .active`
     * without thinking about it.
     *
     * ⚠⚠ The bug this closes: the bootstrap's own pass fires once per **process**, and
     * `FitLocalRuntime.install()` is idempotent, so re-foregrounding an already-installed runtime
     * re-read nothing. On Android the identical gap meant a ride recorded that morning was still
     * missing at 10:39 (the process had been up 13 h 26 m). iOS reclaims processes sooner, which
     * makes this rarer here — not absent.
     */
    fun refreshHealthImport() {
        val action = foregroundRefresh
        if (action == null) {
            println("[fit] foreground refresh: nothing live installed")
            return
        }
        action()
    }

    /** The currently-installed region resolver — exposed for a test to assert the wiring. */
    internal fun installedRegionResolver(): RegionBundleResolver? = regions

    /** The currently-installed repository — exposed for a smoke/test to assert against. */
    internal fun installedRepository(): FitRepository = installed

    /**
     * Build a recorder over the installed factory **without** constructing a [RecordViewModel] —
     * the seam a test asserts against ([RecordViewModel]'s init arms the sensors, which on the real
     * engine would prompt for Location/Bluetooth authorization; constructing the engine is
     * side-effect-free).
     */
    internal fun buildRecordingEngine(): RecordingEngine = recordingEngineFactory.create(installed)

    /** App-shell VM — exposes the onboarding gate that routes to Onboarding vs the main shell. */
    fun appViewModel(): AppViewModel = AppViewModel(installed)

    fun homeViewModel(): HomeViewModel = HomeViewModel(installed)

    fun historyViewModel(): HistoryViewModel = HistoryViewModel(installed)

    fun browseViewModel(): BrowseViewModel = BrowseViewModel(installed)

    /**
     * Per-category detail (fit-app.md §12 B1): [categoryId] selects the metric — Activity → steps
     * trend, Sleep → duration trend, Body → latest weight, Vitals → latest resting HR. Medical opens
     * its own screen; Nutrition/Cycle cards are disabled, so they never reach here.
     */
    fun categoryViewModel(categoryId: CategoryId): CategoryDetailViewModel =
        CategoryDetailViewModel(categoryId, installed)

    /**
     * The same detail opened on one of Activity's series — Home's week cards and the values under
     * the ring open steps, active minutes, distance and energy burned (fit-app.md §18.18). Any
     * other category ignores [metric]. An overload rather than a default argument: Obj-C has no
     * defaults, and the one-argument form is what Browse calls.
     */
    fun categoryViewModel(categoryId: CategoryId, metric: CategoryMetric): CategoryDetailViewModel =
        CategoryDetailViewModel(categoryId, installed, metric)

    fun medicalViewModel(): MedicalViewModel = MedicalViewModel(installed)

    fun profileViewModel(): ProfileViewModel = ProfileViewModel(installed)

    fun onboardingViewModel(): OnboardingViewModel = OnboardingViewModel(installed)

    fun workoutViewModel(workoutId: String): WorkoutDetailViewModel =
        WorkoutDetailViewModel(installed, regions = regions, workoutId = workoutId)

    /**
     * One data source's detail (`fit-data-sources-restructure.md` DS-5).
     *
     * ⚠ [deviceId] is part of the identity, not decoration: two phones in one realm mean two
     * source rows, and a screen keyed on [kind] alone would show one device's figures under the
     * other's name. Null for `TenshuHealth`, which is a projection rather than a registry row.
     *
     * ⚠ No `HealthPermissionRequester` is bound on iOS, so the deep-history grant and the
     * sharing check are absent here rather than shown inert — HealthKit imposes no 30-day window
     * to lift and offers no settings screen we could send anyone to.
     */
    fun dataSourceViewModel(kind: DataSourceKind, deviceId: String?): DataSourceDetailViewModel =
        DataSourceDetailViewModel(kind, deviceId, installed)

    /**
     * The batch-import history.
     *
     * ⚠ No `HealthFilePicker` is passed: on iOS the **shell** owns the picker (`.fileImporter`
     * → [importPickedFile]), so `canImportFiles` stays false here and Swift renders its own
     * button. Wiring a picker through Kotlin would duplicate a seam SwiftUI already owns.
     */
    fun batchImportViewModel(): BatchImportViewModel = BatchImportViewModel(installed)

    /**
     * A recording VM over a fresh engine (the installed [RecordingEngineFactory] — mock by default,
     * the real [IosRecordingEngine] on a live client). The pre-start picker and the live surface
     * share **one** instance — the SwiftUI Record flow builds it once and passes it to both
     * (mirrors the Compose keyed-singleton `RECORD_VM_KEY`).
     */
    fun recordViewModel(): RecordViewModel = RecordViewModel(recordingEngineFactory.create(installed))
}

/** NSData → ByteArray, the repo's standard shape. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
