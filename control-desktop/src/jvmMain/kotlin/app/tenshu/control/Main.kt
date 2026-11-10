package app.tenshu.control

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit
import app.tenshu.control.data.AppLockController
import app.tenshu.control.data.shouldRaiseOnUnlock
import app.tenshu.control.data.FolderWatcher
import app.tenshu.control.data.FirstPartyDesktopPeers
import app.tenshu.control.data.SyncPauseState
import app.tenshu.control.di.dataModule
import app.tenshu.control.di.platformProxyModule
import app.tenshu.control.di.proxyModule
import app.tenshu.control.di.viewModelsModule
import app.tenshu.control.bridge.registerControlMethods
import org.koin.core.qualifier.named
import org.tenshu.sync.proxy.SyncHealthHolder
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import app.tenshu.control.bridge.SchemaFenceLog
import app.tenshu.control.bridge.registerDataMethods
import app.tenshu.control.bridge.FileProviderBridgeState
import app.tenshu.control.bridge.registerFileMethods
import app.tenshu.control.bridge.registerFileWriteMethods
import app.tenshu.control.bridge.registerPairingApprovalMethod
import app.tenshu.control.bridge.registerCalendarReminderMethods
import app.tenshu.control.bridge.registerPendingRequestMethods
import app.tenshu.control.bridge.registerWindowMethods
import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.control.lifecycle.DesktopShellSupport
import app.tenshu.control.lifecycle.DesktopStartAtLoginManager
import app.tenshu.control.lifecycle.LaunchAgentManager
import app.tenshu.control.lifecycle.TrayLiveness
import app.tenshu.control.lifecycle.WindowController
import app.tenshu.control.ui.App
import app.tenshu.control.ui.LocalWindowVisible
import app.tenshu.ui.layout.LocalNavRailPreference
import app.tenshu.ui.layout.filePersistedNavRailPreference
import app.tenshu.ui.layout.rememberPersistedWindowState
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.storage.DesktopAppPaths
import org.tenshu.core.common.storage.SingleInstanceLock
import kotlin.system.exitProcess
import org.tenshu.core.common.transport.PeerCredentialPolicy
import org.tenshu.core.common.transport.UnixSocketServer
import app.tenshu.control.ipc.AttestedPeerCredentialVerifier
import app.tenshu.control.ipc.FirstPartyTeamPolicy
import app.tenshu.control.ipc.PeerTrustAnchor
import org.tenshu.core.common.transport.SocketPathResolver
import org.tenshu.core.common.util.DesktopSavedStateRegistryOwner
import org.tenshu.core.common.util.DesktopViewModelStoreOwner

@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    // ⚠⚠ Before anything else, the tray included: one Control per data root
    // (desktop-macos-distribution.md §2.11). A second copy, from any install channel, must not
    // touch the store, the sockets or the tray. It asks the running one to show its window and
    // exits 0. A login autostart (started hidden) exits without asking, so two login items firing
    // at once do not pop a window up.
    val startHidden = System.getenv("TENSHU_CONTROL_START_HIDDEN")?.toBoolean() == true
    when (
        val decision = ControlInstanceGuard.decide(
            lockFile = DesktopAppPaths.sharedFile(ControlInstanceGuard.LOCK_FILE),
            binarySocket = SocketPathResolver.resolve(),
            log = ::println,
        )
    ) {
        is ControlInstanceGuard.Decision.Defer -> {
            val handedOff = !startHidden && ControlInstanceGuard.requestOpenWindow(SocketPathResolver.resolveBridge())
            println(
                "ControlInstanceGuard: Control is already running (${decision.holder}); " +
                    if (handedOff) "asked it to show its window, exiting." else "exiting.",
            )
            exitProcess(0)
        }
        is ControlInstanceGuard.Decision.Run -> instanceLock = decision.lock
    }

    Dispatchers.setMain(Dispatchers.Swing)

    // ⚠⚠ FIRST after the instance guard, before Koin and the store (desktop-tray-liveness.md §4.5). The tray is the
    // only thing a hidden daemon puts on screen, and it needs nothing from the proxy — it
    // dials the bridge when it wants it and treats an unreachable daemon as a no-op. Behind
    // `startKoin` it would inherit the store's cold-open latency, which can run to tens of
    // minutes — at login, indistinguishable from the tray never appearing at all. In front of it,
    // the icon is up in milliseconds and a slow or wedged proxy costs the user visibility of
    // Control, not Control itself.
    ensureTrayRunning()

    val koinApp = startKoin {
        modules(dataModule, platformProxyModule, proxyModule, viewModelsModule)
    }

    // Start IPC Server
    val proxyGateway = koinApp.koin.get<ProxyGateway>()
    val serializer = koinApp.koin.get<BinarySerializer>()
    // Desktop IPC peer-credential gate (desktop-ipc-client-identity.md — enforcing).
    // The verifier resolves each peer's identity from its code signature and the policy is
    // **strict**: a peer whose running code does not satisfy a requirement pinning
    // `anchor apple generic` + our Team ID is rejected and its channel closed.
    //
    // ⚠ The bridge gets the same verifier but its own quiet instance: NativeBridgeServer
    // opens a fresh connection per call, so a per-connection log there is per-call spam. Its
    // peer is Control's own tray agent, embedded in this bundle and signed with it.
    val peerPolicy = PeerCredentialPolicy(strict = true)
    val teamPolicy = FirstPartyTeamPolicy(PeerTrustAnchor.FIRST_PARTY_TEAM_IDS)
    val peerVerifier = AttestedPeerCredentialVerifier(policy = teamPolicy, log = { println(it) })
    // ⚠ The bridge also admits Control's own identifier: a second Control refused by the instance
    // guard asks this one to show its window over the bridge. It grants nothing new; that copy
    // could already open the data root. ipc.sock does not admit it (it is not an app).
    val bridgeVerifier = AttestedPeerCredentialVerifier(
        policy = FirstPartyTeamPolicy(
            PeerTrustAnchor.FIRST_PARTY_TEAM_IDS,
            knownIdentifiers = FirstPartyDesktopPeers.BRIDGE_IDENTIFIERS,
        ),
    )
    // A16 developer mode (D5/Q4). Sourced from the **build-type** flag the dev `run` task
    // sets, not from a runtime setting or an env var: a bypass an attacker can turn on is
    // indistinguishable from having no gate, and the packaged app never passes this flag, so
    // a release build cannot enable it. With the policy strict, this is what lets
    // an unsigned local build still connect to a dev-run Control — see the note in the doc
    // about dev-run vs packaged pairings.
    //
    // ⚠⚠ Never inside a packaged app, whatever the flag says: `JAVA_TOOL_OPTIONS` can put any `-D`
    // into a packaged JVM, but not remove the `jpackage.app-path` its launcher sets on its own
    // command line (the same rule as the apps' `ControlServerCheck.developerMode`).
    val developerMode = System.getProperty("tenshu.release") == "false" && System.getProperty("jpackage.app-path") == null
    val unixSocketServer = UnixSocketServer(
        proxyGateway,
        serializer,
        peerVerifier = peerVerifier,
        peerPolicy = peerPolicy,
        developerMode = developerMode,
    )
    unixSocketServer.start()

    // Desktop background-ingest: the resident daemon hosts the folder watcher
    // itself (drive-synced-folders-phase2.md §4.2) —
    // Drive-desktop only registers + shows settings. Walks each enabled folder
    // source's watched paths and uploads new files through the in-process client.
    FolderWatcher(koinApp.koin.get(), koinApp.koin.get(), grants = koinApp.koin.get()).start()

    // Daemon/window lifecycle split (docs/proposals/desktop-macos-integration.md
    // §4). With the Swift tray as the persistent presence, the
    // window's visibility is decoupled from the process: closing the window
    // hides it (the daemon + socket servers keep running); the tray re-opens it
    // over the bridge. TENSHU_CONTROL_START_HIDDEN lets the LaunchAgent
    // start straight to the menu bar; dev `:run` starts visible.
    val windowController = WindowController(initiallyVisible = !startHidden)
    // Desktop-local "close hides to tray vs quits" preference,
    // re-read on each window close so a Settings toggle takes effect immediately.
    val controlSettings = DesktopControlSettings()
    // The soft app-lock's gate state. Read here rather than in `AppLockGate` because raising the
    // window needs the window, which lives in this entry point — see the effect that uses it.
    val appLockController = koinApp.koin.get<AppLockController>()

    // Native-facing JSON bridge for the macOS tray + File Provider
    // (docs/proposals/desktop-macos-integration.md §2.2). Separate
    // group-container socket from the binary transport. Exposes ping +
    // getSyncState, plus the window-lifecycle methods below (registered
    // here, not in registerControlMethods, because they need the WindowController
    // which lives in the desktop entry point rather than in Koin).
    val nativeBridgeServer = NativeBridgeServer(
        peerVerifier = bridgeVerifier,
        peerPolicy = peerPolicy,
        developerMode = developerMode,
    )
    nativeBridgeServer.registerControlMethods(koinApp.koin)
    nativeBridgeServer.registerWindowMethods(windowController)
    nativeBridgeServer.registerDataMethods(koinApp.koin)
    // File-read methods (listFolder / itemMetadata / readContents) that
    // back the macOS Drive File Provider; served from the daemon's in-process
    // file-service.
    val fileProviderState = FileProviderBridgeState(
        journal = DesktopAppPaths.sharedFile("fileprovider-ids.tsv"),
        stagingDir = DesktopAppPaths.sharedFile("fileprovider-staging"),
    )
    nativeBridgeServer.registerFileMethods(koinApp.koin, fileProviderState)
    // File-write methods (createFolder / createFile / modifyContents /
    // moveItem / deleteItem) for the File Provider's write path.
    nativeBridgeServer.registerFileWriteMethods(koinApp.koin, fileProviderState)
    // Central notifications (control-central-notifications.md §4.3.4):
    // serves the inbound actionable-requests feed to the macOS tray, which polls
    // it and posts a system notification when one arrives — OS-level elevation so
    // the user is alerted with Control backgrounded.
    nativeBridgeServer.registerPendingRequestMethods(koinApp.koin)
    // The tray polls this and hands each reminder to macOS as a
    // `UNCalendarNotificationTrigger`, so it fires with Control not running.
    nativeBridgeServer.registerCalendarReminderMethods(koinApp.koin)
    // Pairing UX (control-pairing-ux.md §3.6): serves whether a device is asking
    // to join (HostPairingMonitor.activeRequest) so the tray can alert on the
    // close-to-tray case; tap → focus Control → the in-app approval modal. SAS-free.
    nativeBridgeServer.registerPairingApprovalMethod(koinApp.koin)
    nativeBridgeServer.start()

    koinApp.koin.loadModules(listOf(module {
        single { unixSocketServer }
        single { nativeBridgeServer }
        single { windowController }
        // bgproc §5.3 tray *Paused*: the shared pause switch. Constructed here
        // (not in :control-shared-proxy) because its persistence lives in
        // DesktopControlSettings, one layer up. The proxy's syncGate and the
        // tray's getSyncState/setSyncPaused bridge both resolve this single, so
        // toggling from the tray gates the in-process scheduler.
        single {
            SyncPauseState(
                initiallyPaused = controlSettings.syncPaused(),
                persist = { controlSettings.setSyncPaused(it) },
            )
        }
    }))

    // The schema pull fence's transitions, kept in a file — the only log line that names
    // why a fenced Control stopped pulling, which a packaged app's stdout would otherwise lose.
    SchemaFenceLog.start(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        verdicts = koinApp.koin.get<SyncHealthHolder>().observeSchemaFence(),
        file = File(DesktopAppPaths.dataDir(koinApp.koin.get<String>(named("platformPackageName"))), "diagnostics/control.log"),
    )

    // First run: default "Start at login" ON so Control behaves like
    // OneDrive/Dropbox — autostarts hidden at the next login and syncs in the
    // background. Latched so opting out later sticks. (Packaged bundle only.)
    ensureStartAtLoginDefault()

    // (The tray is started at the very top of main() — see the note there.)

    application {
        val isVisible by windowController.visible.collectAsState()
        val windowState = rememberPersistedWindowState(
            key = "control",
            storageDir = DesktopAppPaths.root()
        )

        // Quit is explicit (tray Quit → bridge quitApp → requestQuit, or Cmd+Q):
        // only then does application { } return and the proxy quiesce below.
        LaunchedEffect(Unit) {
            windowController.quitRequested.collect { exitApplication() }
        }

        // Always emit the Window — never conditionally removed — so toggling
        // `visible` to false hides it without dropping the window count to zero
        // (which would make application { } exit and kill the daemon).
        Window(
            onCloseRequest = {
                // ⚠⚠ Hide only where a tray exists to bring the window back. Elsewhere a hidden
                // window is a resident Control nobody can reach (desktop-windows-port.md §3.4).
                if (DesktopShellSupport.current.trayAvailable && controlSettings.closeToTray()) {
                    windowController.hide()
                } else {
                    windowController.requestQuit()
                }
            },
            visible = isVisible,
            title = "Tenshu Control",
            state = windowState
        ) {
            // Raise + focus when (re)shown — e.g. the tray's Open Control.
            LaunchedEffect(isVisible) {
                if (isVisible) {
                    window.toFront()
                    window.requestFocus()
                }
            }

            // ⚠⚠ Raise again when the soft app-lock clears, or authenticating LOOKS LIKE A CRASH.
            //
            // `applock-helper` is a bare binary with no bundle identity, so macOS gives it the
            // foreground to present the `LAContext` sheet and, when it exits, activation does NOT
            // come back to us — the window drops behind whatever is frontmost, and the process
            // that is still up reads as one that closed.
            //
            // ⚠⚠ `toFront()` ALONE IS NOT ENOUGH HERE, and it looks like it should be. `toFront()`
            // reorders windows *within* an application; macOS will not let a process that is not
            // active steal activation that way. The tray's *Open Control* works because it is a
            // native app calling `NSRunningApplication.activate()` (TrayAgent/main.swift), and the
            // `isVisible` effect above works because making a hidden window visible activates as a
            // side effect. Neither applies to an already-visible window of a backgrounded app.
            //
            // `Desktop.requestForeground(true)` is the JVM route to the same AppKit call
            // (`[NSApp activateIgnoringOtherApps:]`).
            //
            // ⚠ The two guards are what make this safe, not the `catch`: per the JDK source,
            // `getDesktop()` throws only `HeadlessException` (unreachable — this effect runs inside
            // a rendered window) or `UnsupportedOperationException` when `!isDesktopSupported()`,
            // and `requestForeground` throws only `UnsupportedOperationException` via the very
            // `isSupported(APP_REQUEST_FOREGROUND)` check made below. So every *documented* throw
            // is already excluded.
            //
            // ⚠⚠ The catch is therefore for undocumented native-peer failure only — and it LOGS
            // rather than swallows. A silent catch here would reproduce exactly the failure this
            // effect prevents (window behind, reads as a crash) with no signal anywhere. Not
            // rethrowing is deliberate: an uncaught throw in a `LaunchedEffect` surfaces as an
            // unhandled exception in the composition, and taking the proxy down over a focus nicety
            // is the worse trade.
            //
            // ⚠ Keyed on the controller rather than done inside `AppLockGate`: the gate lives in
            // `:control-shared` and has no window. And guarded on `isVisible` — a hidden
            // (close-to-tray / autostarted-hidden) window must not be yanked to the front just
            // because the gate's state settled.
            //
            // ⚠⚠ Only on the lock→UNLOCK transition — see `shouldRaiseOnUnlock`. Firing on any
            // change to either input would also fire on the tray's *Open Control*, where nothing
            // was locked; Control would then activate itself on top of the tray's own
            // `NSRunningApplication.activate()` and the tray's popover would stop closing.
            val appLocked by appLockController.locked.collectAsState()
            var previouslyLocked by remember { mutableStateOf(appLocked) }
            LaunchedEffect(appLocked) {
                val raise = shouldRaiseOnUnlock(previouslyLocked, appLocked, isVisible)
                previouslyLocked = appLocked
                if (raise) {
                    runCatching {
                        if (Desktop.isDesktopSupported()) {
                            val desktop = Desktop.getDesktop()
                            if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                                desktop.requestForeground(true)
                            }
                        }
                    }.onFailure {
                        println(
                            "AppLock: requestForeground failed (${it::class.simpleName}: " +
                                "${it.message}) — Control will stay behind the frontmost window " +
                                "after unlocking."
                        )
                    }
                    window.toFront()
                    window.requestFocus()
                }
            }

            val viewModelStoreOwner = remember { DesktopViewModelStoreOwner() }
            val savedStateRegistryOwner = remember { DesktopSavedStateRegistryOwner() }
            // ⚠ `remember`ed: AdaptiveNavScaffold reads the stored value keyed on this
            // instance, so a new one per recomposition would re-read the file every frame.
            val navRailPreference = remember {
                filePersistedNavRailPreference(key = "control", storageDir = DesktopAppPaths.root())
            }

            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner,
                LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
                LocalLifecycleOwner provides savedStateRegistryOwner,
                LocalNavRailPreference provides navRailPreference,
                // The window stays composed while hidden (close-to-tray /
                // autostarted-hidden daemon); pass visibility through so
                // visibility-sensitive effects — the Locked screen's biometric
                // auto-prompt — don't fire for a window the user can't see.
                LocalWindowVisible provides isVisible,
            ) {
                TenshuTheme {
                    // The soft app-lock gate lives inside App() — it wraps only the active
                    // app UI, so it never stacks over the LockedScreen / onboarding.
                    App()
                }
            }
        }
    }

    // `application { }` blocks until an explicit quit (exitApplication). On a
    // clean exit, quiesce the proxy — cancel **and join** its background loops
    // (sync scheduler, schedule-drain, constraint gate) before the process
    // tears down. The deterministic counterpart to process-death cleanup; see
    // the "Proxy Background Lifecycle" tech-debt entry.
    runBlocking { proxyGateway.shutdownAndJoin() }

    // Take the socket files with us. A client connecting to a leftover file gets "connection
    // refused" — the same answer a Control that is still starting gives — and a person looking
    // at the directory sees two sockets and concludes Control is up. Gone, the failure reads
    // as what it is: nothing is there. (A killed Control still leaves them; `start` removes
    // stale ones before binding.) ⚠ Each server removes only the file it bound: a second
    // Control started meanwhile owns these paths now, and its sockets must outlive this quit.
    nativeBridgeServer.stop()
    unixSocketServer.removeSocketFile()
    // Last, so a successor cannot start until our socket files are gone.
    instanceLock?.release()
}

/**
 * The single-instance lock, held for the life of the process. ⚠ A top-level field on purpose: an
 * unreachable `RandomAccessFile` is closed by its cleaner, and closing the channel drops the lock.
 */
@Volatile
private var instanceLock: SingleInstanceLock? = null

/**
 * One-time first-run step: make "Start at login" the default by installing the
 * **daemon** LaunchAgent, so Control autostarts (hidden) at the next login and
 * runs the sync loop in the background — the OneDrive/Dropbox model. The tray
 * follows for free: the autostarted daemon calls [ensureTrayRunning] itself.
 *
 * Two mechanisms are selected by [DesktopStartAtLoginManager]:
 *  - a sandboxed MAS bundle uses its embedded SMAppService login item;
 *  - an unsandboxed development or Developer ID bundle keeps the existing
 *    LaunchAgent path, without loading it live and starting a duplicate daemon.
 *
 * Latched via [DesktopControlSettings.startAtLoginDefaultApplied]. Runs exactly
 * once, so a user who turns Start-at-login off in Settings is not re-opted-in.
 *
 * Packaged-bundle only: the daemon launcher must live at
 * `<App>/Contents/MacOS/<exe>`; a dev `:run` (`…/bin/java`) is skipped.
 */
private fun ensureStartAtLoginDefault() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return
    val launcher = ProcessHandle.current().info().command().orElse(null) ?: return
    if (java.io.File(launcher).parentFile?.name != "MacOS") return
    val settings = DesktopControlSettings()
    if (settings.startAtLoginDefaultApplied()) return
    // The Mac App Store requires explicit user consent before an app enables
    // login launch. The Settings switch remains the opt-in path for sandboxed
    // builds; only unsandboxed desktop builds default it on.
    if (System.getenv("APP_SANDBOX_CONTAINER_ID")?.isNotBlank() == true) {
        settings.setStartAtLoginDefaultApplied(true)
        return
    }
    runCatching {
        DesktopStartAtLoginManager(launcher).enable()
    }
    settings.setStartAtLoginDefaultApplied(true)
}

/**
 * Spawn the embedded menu-bar tray agent (`Contents/Helpers/tray-agent`) so
 * Control has a persistent menu-bar presence whenever it's running, decoupled
 * from the "Start at login" opt-in (which only governs *autostart at login*).
 *
 * Guards, in order:
 *  - macOS only; the tray is a Cocoa `NSStatusItem` agent.
 *  - Resolve the binary relative to the running launcher. `null` on a dev /
 *    unpackaged run (no embedded helper) → no-op.
 *  - If the tray **LaunchAgent** is installed, launchd already owns the tray
 *    (`KeepAlive`); spawning a second one here would race it / risk a restart
 *    loop. Leave it to launchd.
 *  - Otherwise ask [TrayLiveness] what to do about whatever is (or isn't)
 *    running, and act on it.
 *
 * ⚠⚠ **A live tray process is not a working tray**
 * (docs/proposals/desktop-tray-liveness.md §1): a stale agent can hold the menu
 * bar with no icon on it, and skipping the spawn because a pid exists leaves it
 * there. `pgrep` cannot see a menu bar — so the tray publishes its
 * own liveness *and* whether AppKit placed its status item, and this replaces an
 * agent that fails either test.
 *
 * Best-effort: any failure is swallowed — a missing tray must never block the
 * app from coming up.
 */
private fun ensureTrayRunning() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) return
    val launcher = ProcessHandle.current().info().command().orElse(null) ?: return
    val tray = LaunchAgentManager.trayBinaryFor(launcher) ?: return
    if (LaunchAgentManager.forTray().isInstalled()) return

    val socketDir = File(SocketPathResolver.resolveDir())
    val now = System.currentTimeMillis()
    val pid = trayAgentPid(tray.absolutePath)
    val decision = TrayLiveness.decide(
        trayPid = pid,
        processAgeSeconds = pid?.let(::processAgeSeconds),
        heartbeat = TrayLiveness.readHeartbeat(socketDir),
        nowEpochMillis = now,
    )
    // Written before acting: under launchd the daemon's stdout goes nowhere, so this
    // file is the only trace a vanished tray leaves behind (§4.4).
    TrayLiveness.appendLog(socketDir, decision, now)

    when (decision.action) {
        TrayLiveness.Action.SKIP -> return
        TrayLiveness.Action.REPLACE -> pid?.let(::terminateAndAwait)
        TrayLiveness.Action.SPAWN -> Unit
    }

    runCatching {
        ProcessBuilder(tray.absolutePath)
            // ⚠ Pin the child to the directory this process actually resolved. Both sides
            // derive it independently — SocketPathResolver from system properties + env
            // here, env only in Swift — and agree only while neither is
            // configured. Passing it down removes that drift for the bridge socket and the
            // heartbeat alike (§4.1).
            .apply { environment()[SocketPathResolver.DIR_ENV] = socketDir.absolutePath }
            .start()
    }
}

/**
 * The running tray agent's pid, or null when none is. `pgrep -f` matches the full command
 * line, and the tray's *is* the absolute helper path — which is why this cannot collide
 * with the daemon's own (`Contents/MacOS/TenshuControl`).
 */
private fun trayAgentPid(path: String): Long? = runCatching {
    val process = ProcessBuilder("pgrep", "-f", path).start()
    val out = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    out.lineSequence().mapNotNull { it.trim().toLongOrNull() }.firstOrNull()
}.getOrNull()

/**
 * Seconds since [pid] started, or null when it cannot be read.
 *
 * ⚠⚠ `etime`, **not** `etimes`: the seconds-valued column is GNU/procps and BSD `ps` rejects
 * it outright, so asking for it here yields null on every macOS machine and quietly disables
 * the grace period this feeds. Parsing lives in [TrayLiveness.parseElapsedSeconds].
 */
private fun processAgeSeconds(pid: Long): Long? = runCatching {
    val process = ProcessBuilder("ps", "-o", "etime=", "-p", pid.toString()).start()
    val out = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    TrayLiveness.parseElapsedSeconds(out)
}.getOrNull()

/**
 * SIGTERM a stale tray and wait for it to actually go, so the replacement does not end up a
 * duplicate in the menu bar. Bounded — an agent that will not exit must not hold up Control's
 * startup, and a second icon is a smaller problem than a daemon that never finishes booting.
 */
private fun terminateAndAwait(pid: Long) {
    runCatching {
        ProcessHandle.of(pid).ifPresent { handle ->
            handle.destroy()
            if (!handle.awaitExit()) {
                // ⚠⚠ SIGTERM is a *request*, and the agent being replaced is by definition one
                // that stopped behaving — a hung or stopped process never handles it (a
                // SIGSTOP'd one cannot: the signal just stays pending). Without
                // this escalation the replacement joins the broken agent in the menu bar instead
                // of taking its place.
                handle.destroyForcibly()
                handle.awaitExit()
            }
        }
    }
}

/** True if the process exited within the bound; false on timeout (or interruption). */
private fun ProcessHandle.awaitExit(): Boolean = runCatching {
    onExit().orTimeout(TRAY_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS).join()
    true
}.getOrDefault(false)

private const val TRAY_TERMINATION_TIMEOUT_SECONDS = 5L
