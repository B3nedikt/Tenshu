package app.tenshu.control.bridge

import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BundledAppCatalogue
import app.tenshu.control.data.macOsBundleIdFor
import app.tenshu.control.data.ClientBinding
import app.tenshu.control.data.Event
import app.tenshu.control.data.FirstPartyApp
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.NotificationMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.proxy.BridgeException
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.notifications.client.notificationService

/**
 * Registers the native-bridge **data** methods for the macOS tray
 * (docs/proposals/desktop-macos-integration.md §4 task 4, slice 1B):
 *
 *  - `listApps` — registered Tenshu apps, from [AppRegistry] (the same source
 *    Control's Apps screen uses), followed by the first-party macOS apps that have
 *    never connected, so the tray can offer one that is installed but unopened.
 *  - `recentNotifications(limit)` — recent notification events, read through the
 *    in-process [TenshuClient] and shaped by [NotificationMapper] (same mapper
 *    + burst-aggregation Control's Home feed uses). Default limit 4 (the
 *    popover shows ≤4), capped at 50.
 *  - `launchApp(appId)` — launches the app's macOS bundle via LaunchServices
 *    (`open -b`), resolving platform-neutral app ids through their Apple bundle IDs.
 *    Returns `not_found` when no such bundle is installed.
 *
 * Deps are resolved lazily per request from [koin] (like
 * [registerControlMethods]), so a test only needs to provide what the method it
 * exercises actually reads. The JSON-shaping is factored into [appsResult] /
 * [notificationsResult] so it can be unit-tested without a socket or a proxy.
 *
 * Sync state for the tray's icon is served by the existing `getSyncState`
 * (Phase 0); the tray polls it. A push variant (`subscribeSyncState`) is
 * deferred — see the proposal's Phase 1 deviation note.
 */
fun NativeBridgeServer.registerDataMethods(
    koin: Koin,
    /**
     * Launches the macOS app with the given bundle id, returning true on success.
     * Injectable so the bridge logic (id mapping + error paths) is unit-testable
     * without actually spawning `open`; defaults to the real LaunchServices launch.
     */
    launchBundle: (bundleId: String) -> Boolean = ::openMacOsBundle,
) {
    register("listApps") {
        val apps = runBlocking { koin.get<AppRegistry>().installedApps().first() }
        appsResult(apps, BundledAppCatalogue.apps)
    }

    register("recentNotifications") { params ->
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, MAX_NOTIFICATIONS) ?: DEFAULT_NOTIFICATIONS
        val client = koin.get<TenshuClient>()
        val mapper = NotificationMapper()
        val events = runBlocking {
            val result = client.notificationService.notifications.flow().first()
            val notifications = (result as? TResult.Success)?.value?.toList().orEmpty()
            // No client-side burst fold: the producer coalesces a bulk upload
            // into one `count`-bearing row, and `mapToEventOrNull` renders it
            // as the "N files added" event directly
            // (notification-burst-aggregation.md Slice 1/3).
            notifications
                .mapNotNull { mapper.mapToEventOrNull(it) }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }
        notificationsResult(events)
    }

    register("launchApp") { params ->
        val appId = params["appId"]
            ?: throw BridgeException("invalid_request", "missing 'appId'")
        // Internal app ids are platform-neutral; Apple bundle ids may carry the
        // shared iOS suffix for Universal Purchase. Launch through LaunchServices
        // (`open -b`) using the explicit mapping below.
        val bundleId = macOsBundleIdFor(appId)
            ?: throw BridgeException("not_supported", "appId is not a launchable macOS bundle id: $appId")
        if (!launchBundle(bundleId)) {
            throw BridgeException("not_found", "no installed app for bundle id $bundleId (appId=$appId)")
        }
        mapOf("launched" to true)
    }
}

/** Launches a macOS app by bundle id via `open -b`; true iff `open` exited 0. */
private fun openMacOsBundle(bundleId: String): Boolean = runCatching {
    ProcessBuilder("open", "-b", bundleId).start().waitFor() == 0
}.getOrDefault(false)

/**
 * `{ apps: [{ appId, name, iconName, version, verified, deviceCount, installedAt,
 * installedOnThisDevice, registered, windowsPackage }] }`.
 *
 * Registered apps come first, then every [catalogue] app with a macOS bundle or a Windows package that has **no**
 * registration, marked `registered = false`. Registration fires on session establishment, so an
 * app that was installed but never opened has no row — and a launcher fed only registrations
 * could never offer the one click that would create it.
 *
 * ⚠ An unregistered row is a *candidate*, not a claim that the bundle is on disk: the daemon does
 * not know. The tray asks LaunchServices and shows only what resolves (`control-app-catalogue.md`
 * D5). It is also never written back as an `AppRegistration` — a bundle id on disk is
 * self-asserted, and that row means an attested peer connected.
 */
internal fun appsResult(apps: List<InstalledApp>, catalogue: List<FirstPartyApp>): Map<String, Any?> {
    val registeredIds = apps.mapTo(HashSet()) { it.appId }
    // The Windows tray agent launches through the package's app-list entry, and needs the MSIX package name for
    // that (desktop-windows-port.md §3.9). The catalogue is the authority on it; `null` = no Windows build.
    val windowsPackages = catalogue.associate { app ->
        app.appId to app.clients.filterIsInstance<ClientBinding.WindowsPackage>().firstOrNull()?.packageName
    }
    val registered = apps.map { app ->
        mapOf(
            "appId" to app.appId,
            "name" to app.name,
            "iconName" to app.iconName,
            "version" to app.version,
            "verified" to app.verified,
            "deviceCount" to app.deviceCount,
            "installedAt" to app.installedAt,
            // D5: the tray is a launcher and filters on this; Control's Apps list keeps every
            // app in the realm. Sent rather than filtered here so the daemon stays the
            // authority on the *fact* and the tray owns the presentation.
            "installedOnThisDevice" to app.installedOnThisDevice,
            "registered" to true,
            "windowsPackage" to windowsPackages[app.appId],
        )
    }
    val neverConnected = catalogue
        // A MacCodeSignature binding is the attested bundle id the macOS tray can launch (it need not equal
        // the platform-neutral appId: Drive shares its iOS id); a WindowsPackage binding is what the Windows
        // agent launches. Each tray offers only what resolves on its own OS, so a candidate the other OS can
        // launch is never shown.
        .filter { app ->
            app.clients.any { it is ClientBinding.MacCodeSignature || it is ClientBinding.WindowsPackage }
        }
        .filter { it.appId !in registeredIds }
        .map { app ->
            mapOf(
                "appId" to app.appId,
                "name" to app.manifest.name,
                "iconName" to app.manifest.iconName,
                "version" to app.manifest.version,
                // Nothing has connected, so nothing was attested — and nothing failed to be.
                // The tray keys its unverified marker off `registered` for that reason.
                "verified" to false,
                "deviceCount" to 0,
                "installedAt" to 0L,
                "installedOnThisDevice" to false,
                "registered" to false,
                "windowsPackage" to windowsPackages[app.appId],
            )
        }
    return mapOf("apps" to registered + neverConnected)
}

/**
 * `{ notifications: [{ id, timestamp, kind, name, otherCount? }] }`. `kind` is a
 * stable event code; the tray composes the localised display string Swift-side
 * (ADR 0016: each surface owns its strings), so the bridge ships data not prose.
 */
internal fun notificationsResult(events: List<Event>): Map<String, Any?> = mapOf(
    "notifications" to events.map { event ->
        val base = mapOf<String, Any?>(
            "id" to event.id,
            "timestamp" to event.timestamp.toEpochMilliseconds(),
        )
        base + when (event) {
            is Event.FileEvent.Added ->
                mapOf("kind" to if (event.isFolder) "folder_added" else "file_added", "name" to event.fileName)
            is Event.FileEvent.MultipleAdded ->
                mapOf("kind" to "files_added", "name" to event.firstFileName, "otherCount" to event.otherFilesCount)
            is Event.FileEvent.Deleted ->
                mapOf("kind" to if (event.isFolder) "folder_deleted" else "file_deleted", "name" to event.fileName)
            is Event.FileEvent.ChangeLost ->
                mapOf("kind" to "change_lost", "name" to event.fileName)
            is Event.AppEvent.Registered ->
                mapOf("kind" to "app_registered", "name" to event.appName)
            is Event.AppEvent.Removed ->
                mapOf("kind" to "app_removed", "name" to event.appName)
        }
    }
)

private const val DEFAULT_NOTIFICATIONS = 4
private const val MAX_NOTIFICATIONS = 50
