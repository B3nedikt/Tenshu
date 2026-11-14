package org.tenshu.sdk.ios

import app.tenshu.reachability.SuiteSyncProblem
import app.tenshu.reachability.evaluateSuiteSyncProblem
import app.tenshu.reachability.withOfflineDelay
import kotlinx.coroutines.flow.combine
import org.tenshu.sync.client.syncConnectivity
import org.tenshu.sync.client.syncService
import org.tenshu.storage.common.RealmStorageState
import org.tenshu.storage.common.evaluateRealmStorage
import org.tenshu.storage.common.formatStorageBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.tenshu.core.common.TenshuClient
import org.tenshu.storage.client.status
import org.tenshu.storage.client.storageService
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The realm-storage banner for **first-party iOS apps** (`drive-synced-folders.md` §8b.5c) — and,
 * since SS-1b (`suite-sync-status.md`), the whole sync strip: this device's sync health (locked, not
 * recognised by the relay, needs attention, offline) as well as storage, in the one order every
 * Compose app uses ([evaluateSuiteSyncProblem]). Control's own reachability stays each Swift root's
 * banner. The name stays: ten Swift roots mount this banner, and a rename would buy nothing.
 *
 * ## Why iOS needs its own surface at all
 *
 * The Android/desktop banner is Compose, and **no iOS app links a Compose UI module** — iOS is
 * SwiftUI end to end. So `RealmStorageBanner` and `rememberControlServicesLauncher()` cannot be
 * reached here, and this is new rendering rather than adoption.
 *
 * ⚠⚠ **iOS is where the gap is worst, not least.** `StorageSyncGate` holds the personal realm's
 * push until a plan is bound, so a realm created on iOS sits at **0 blocks** on the relay even with
 * two devices correctly paired (`ios-services-status-and-purchase.md` §3.5). Without this surface
 * nothing says so, and the rescue needs a *second, non-iOS device*: an iOS-only user has no in-app
 * path out at all.
 *
 * ## Kotlin decides, Swift renders
 *
 * The **rule** stays [evaluateRealmStorage] — the same pure function the Compose banner uses, with
 * its own tests — so Android and iOS cannot drift about when a realm counts as full. What crosses
 * to Swift is a *rendered string*, following [DriveBackupSurface.observeStatusText]'s precedent
 * rather than exporting the state type.
 *
 * ⚠ That choice is deliberate: an umbrella `export(...)` can silently rename an exported type, and
 * a sealed hierarchy crossing into Swift is precisely the shape that goes wrong quietly. A `String?`
 * cannot be renamed into ambiguity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object RealmStorageSurface {

    /**
     * The client whose realm these figures describe, as a FLOW.
     *
     * ⚠⚠ A flow, not a field, and [DriveBackupSurface] documents why: reading an installed
     * dependency at *call* time pins the first subscriber to whatever was there — for the process's
     * lifetime. A banner that renders as soon as the app shell appears is far more likely to lose
     * that race than a Settings screen, and losing it means permanent silence, which is exactly the
     * failure this whole feature exists to remove.
     */
    private val clientFlow = MutableStateFlow<TenshuClient?>(null)

    /**
     * Install the runtime's client. Called once per app from `FirstPartyLocalRuntime.assembleBase`,
     * so every route-1 app gets this for free rather than each remembering to wire it:
     * registration is the load-bearing half, so it lives at the one shared seam instead of six.
     */
    fun install(client: TenshuClient, ownConnectivity: Flow<String?>? = null) {
        connectivityFlow.value = ownConnectivity
        clientFlow.value = client
    }

    /**
     * This runtime's own connectivity, read in-process from its `SyncHealthHolder`, when the installer
     * has one (`suite-sync-status.md` SS-1b).
     *
     * ⚠⚠ Not the store's `SyncHealthStatus` row: that is ONE row on the shared App Group replica,
     * written by whichever runtime wrote last — Control or a sibling app. Read from the store, Photos
     * said "Offline" or "locked" because a sibling was, while its own sync was fine, and the false
     * strip hid its real "Syncing…". The row is the fallback only when no stream is installed.
     */
    private val connectivityFlow = MutableStateFlow<Flow<String?>?>(null)

    /**
     * How many collectors the banner flows have right now, for a debug harness: one mounted banner
     * holds three (text, tone and button label) and one sync capsule holds one, so this is how a
     * harness sees a collector that outlived its view (`ios-sync-activity-indicator.md` §11).
     */
    fun debugCollectors(): Int = clientFlow.subscriptionCount.value

    /**
     * The banner's text, or `null` when there is nothing to say.
     *
     * ⚠ `null` rather than an empty string: "no banner" and "a banner with no words" are different
     * instructions to the view, and the second one renders an empty coloured strip.
     */
    fun observeBannerText(): Flow<String?> = observeProblem().map(::stripText)

    /**
     * Whether the banner should read as an error rather than information.
     *
     * ⚠ Full is an error tone; **no plan is not**. The app works, the data is safe, and it simply
     * is not leaving the device — shouting that on every screen of an unfunded realm is the loudest
     * possible way to say "you have not paid".
     */
    fun observeIsError(): Flow<Boolean> = observeProblem().map(::stripIsError)

    /**
     * The strip's button label, or `null` for none — the view offers it only when [canOpenControl].
     * Storage problems say *Manage storage*; a device Control must re-admit, or unlock, says *Open
     * Control*; offline offers nothing, because it recovers by itself.
     */
    fun observeActionLabel(): Flow<String?> = observeProblem().map(::stripActionLabel)

    /**
     * Whether Control can be reached from here, so the view knows whether to offer the action.
     *
     * ⚠⚠ A button that does nothing is worse than no button. Control may not be installed — Drive
     * on iOS runs its own in-process runtime and works perfectly without it — so this is a real
     * state, not a failure.
     *
     * ⚠ Answers `false` unless `tenshu-control` is in this app's `LSApplicationQueriesSchemes`;
     * iOS reports an installed app as absent without it. That entry belongs in `drive-ios/project.yml`,
     * since `Info.plist` is generated and gitignored.
     */
    fun canOpenControl(): Boolean {
        val url = NSURL.URLWithString(CONTROL_SERVICES_URL) ?: return false
        return UIApplication.sharedApplication.canOpenURL(url)
    }

    /**
     * Open Control on its Services screen — the destination §8b.5a settled on: the one surface that
     * shows the plan beside the real fill level, never an inline purchase.
     *
     * ⚠ `control-cross-app-open.md` rules out launching a sibling app by **bundle id**. A
     * registered URL scheme is a different mechanism, and Control-iOS registers
     * `tenshu-control` (`control-ios/project.yml` → `CFBundleURLSchemes`).
     */
    fun openControlServices() {
        val url = NSURL.URLWithString(CONTROL_SERVICES_URL) ?: return
        // ⚠⚠ The three-argument form: iOS 18 makes the deprecated one-argument `openURL(_:)` a
        // no-op that returns false, so this button would do nothing there.
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /**
     * Storage and this runtime's sync health, decided by the rule every Compose app uses.
     *
     * ⚠ The runtime's own health: under route 1 each iOS app runs its own scheduler, so it is this
     * app's sync that is broken or not — which is what the strip in this app should say, and why the
     * in-process stream ([install]'s `ownConnectivity`) is read in preference to the shared row. No
     * Control verdict is passed: each Swift root draws Control's banner itself.
     */
    private fun observeProblem(): Flow<SuiteSyncProblem> =
        clientFlow.flatMapLatest { client ->
            if (client == null) {
                flowOf(SuiteSyncProblem.None)
            } else {
                combine(
                    (connectivityFlow.value ?: client.syncService.syncConnectivity()).withOfflineDelay(),
                    observeState(),
                ) { connectivity, storage -> evaluateSuiteSyncProblem(null, connectivity, storage) }
            }
        }

    private fun observeState(): Flow<RealmStorageState> =
        clientFlow.flatMapLatest { client ->
            // No client yet ⇒ figures unknown ⇒ Ok ⇒ silence, and the flow switches the moment one
            // is installed. The pre-install window is a real state on iOS, not a millisecond.
            client?.storageService?.status()?.map { status ->
                evaluateRealmStorage(
                    hasStorage = status?.hasStorage?.get(),
                    usedBytes = status?.usedBytes?.get() ?: 0L,
                    limitBytes = status?.limitBytes?.get() ?: 0L,
                )
            } ?: flowOf(RealmStorageState.Ok)
        }

    private const val CONTROL_SERVICES_URL = "tenshu-control://services"
}

/**
 * The strip's words for [problem], or `null` for none (`suite-sync-status.md` SS-1b). English, as the
 * storage copy before it; the same sentences as `:ui-core`'s `suite_sync_*` strings.
 */
internal fun stripText(problem: SuiteSyncProblem): String? = when (problem) {
    SuiteSyncProblem.None, is SuiteSyncProblem.Control -> null
    is SuiteSyncProblem.StorageFull -> problem.state?.let {
        "Storage full — ${formatStorageBytes(it.usedBytes)} of ${formatStorageBytes(it.limitBytes)} " +
            "used. New files stay on this device and nothing is deleted, but they can't " +
            "sync until you free up space or get more."
    } ?: ("Storage full — your data stays on this device and nothing is deleted, but it can't sync " +
        "until you free up space or get more.")
    SuiteSyncProblem.NoPlan ->
        "Not syncing — this device has no storage plan, so your files stay on this device. " +
            "Nothing is lost."
    SuiteSyncProblem.Locked -> "Tenshu is locked — unlock Control to sync with your other devices."
    SuiteSyncProblem.DeviceUnrecognized ->
        "This device isn't connected to your account any more, so nothing syncs here. Your data on " +
            "it is safe. Open Control and add this device again from one of your other devices. " +
            "Don't use Forget this device first — anything still waiting to upload would be lost."
    SuiteSyncProblem.NeedsAttention ->
        "Storage needs attention on this device — your changes are saved here but can't sync yet. " +
            "Open Control to sync on this device."
    SuiteSyncProblem.Offline ->
        "Offline — your changes are saved on this device and will sync when you reconnect."
}

/**
 * Error tone where nothing syncs and waiting will not fix it. ⚠ No plan, locked and offline are not
 * errors: the app works and the data is safe.
 */
internal fun stripIsError(problem: SuiteSyncProblem): Boolean = when (problem) {
    is SuiteSyncProblem.StorageFull, SuiteSyncProblem.DeviceUnrecognized, SuiteSyncProblem.NeedsAttention -> true
    else -> false
}

internal fun stripActionLabel(problem: SuiteSyncProblem): String? = when (problem) {
    SuiteSyncProblem.NoPlan, is SuiteSyncProblem.StorageFull -> "Manage storage"
    SuiteSyncProblem.Locked, SuiteSyncProblem.DeviceUnrecognized, SuiteSyncProblem.NeedsAttention -> "Open Control"
    SuiteSyncProblem.Offline, SuiteSyncProblem.None, is SuiteSyncProblem.Control -> null
}
