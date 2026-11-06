import SwiftUI
import UIKit
import UserNotifications
import TenshuSdk

// The Drive iOS app (docs/proposals/ios-proxy-architecture.md §11.6).
//
// Shipping shape: a Control-gated `RootView` — onboarding is a forward-to-Control
// handoff (read the shared Keychain for a Control-provisioned session; present →
// Files, absent → a single Connect-to-Control screen). No QR, no SAS in the
// first-party path.
//
// The DEV_HARNESS scheme (`DriveIos-Dev`) additionally surfaces the QR+SAS EPC
// pairing tab (`Pair`), which §11.6 moves out of the shipping UI. (The `Smokes`
// tab and its headless verdict were deleted 2026-11-05 — the Kotlin unit tests
// cover the EPC protocol.)
@main
struct DriveIosApp: App {
    // P3b: the UIApplicationDelegate is how iOS re-delivers background-URLSession completion
    // events after it relaunches the app (`handleEventsForBackgroundURLSession`).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // P6a: BGAppRefreshTask registration must happen during launch (both schemes).
        BackgroundSync.register()
        // P3b: register the O3-B background-upload transport and re-attach its background
        // URLSession so any transfer that completed while we were terminated delivers its event
        // (installing the bridge also re-drives anything left staged by a previous run).
        BackgroundUpload.shared.reattach()
        DriveLocalRuntime.shared.installBackgroundUploadBridge(bridge: BackgroundUpload.shared)
        // New-album backup prompt: register the action category (a notification whose
        // category is unknown at delivery renders with no buttons) and install the Swift
        // surface Kotlin posts through. Authorization is requested later, when the user
        // turns backup on — see NewFolderPrompt.requestAuthorizationIfNeeded.
        NewFolderPrompt.registerCategory()
        DriveBackupSurface.shared.installNewFolderPromptBridge(bridge: NewFolderPrompt.shared)
        // Cross-app open (§5i): install the sink BEFORE the runtime can drain into it. A URL that
        // arrived at launch is decoded the moment the graph exists — see `PendingOpenUrl`.
        PendingOpenUrl.shared.setSink { target in
            DriveIosApp.report(target)
        }
        #if DEV_HARNESS
        // ⚠⚠ Self-test for the receipt itself (`control-cross-app-open.md` §5i). An instrument
        // nobody has seen produce output cannot distinguish "the thing under test did not happen"
        // from "the instrument does not work" — which is the position the cross-app-open URL check
        // was stuck in for a whole session. `-openTargetSelfTest` runs the receipt through a path
        // that definitely executes, so a later empty receipt means something.
        if ProcessInfo.processInfo.arguments.contains("-openTargetSelfTest") {
            OpenTargetReceipt.selfTest()
        }
        OpenTargetSelfFire.fireIfRequested()
        // Headless hook: with DRIVE_DEV_PROVISION_CONTROL=1, write the stub session
        // at launch so the connected (forward-into-Files) path is testable without a
        // tap. DEV_HARNESS-only — the shipping app only ever reads a real Control's item.
        if ProcessInfo.processInfo.environment["DRIVE_DEV_PROVISION_CONTROL"] == "1" {
            ControlSessionStore.provisionDevStub()
        }
        #endif
    }

    // Same-device round-trip harness (`-epcAutoConnectFromControl`): land on the Pair tab so its
    // `.task` reads the invite Control published into the shared Keychain and auto-connects head-less.
    @State private var selection = ProcessInfo.processInfo.arguments.contains("-epcAutoConnectFromControl") ? 1 : 0

    var body: some Scene {
        WindowGroup {
            // ⚠ A `Group` because `.onOpenURL` is a **View** modifier, not a Scene one — applying
            // it to `WindowGroup` compiles under `iosKotlinCheck` (which sees no Swift at all) and
            // fails only in `scripts/ios-smoke.sh`, the one thing here that builds Swift.
            Group {
            #if DEV_HARNESS
            if ProcessInfo.processInfo.arguments.contains("-localRuntime") {
                // P1a (ios-drive-parity.md): the PRODUCTIZED route-1 read plane — install the in-process
                // proxy runtime over the shared App-Group replica and mount the REAL Files UI over it (no
                // EPC, no relay, no second process). Run after Control -genesisAutoComplete.
                LocalRuntimeView()
            } else if ProcessInfo.processInfo.arguments.contains("-previewImage") {
                // P4: headless preview proof — real fixture upload, then the router renders the
                // decrypted image through the production preview path.
                PreviewProbeView(fileName: "p4-image.png")
            } else if ProcessInfo.processInfo.arguments.contains("-previewPdf") {
                PreviewProbeView(fileName: "p4-doc.pdf")
            } else {
                // ⚠ The readout rides above the tabs so a UI test can query it from any screen.
                harnessTabs.overlay(alignment: .top) { OpenTargetReadout() }
            }
            #else
            #if DEBUG
            // Store screenshots (docs/how-to/store-screenshots.md): a separate launch that only
            // SEEDS the realm, then exits the picture. The captures are taken on the next launch,
            // which takes the `RootView()` branch below like any user's launch does.
            if let directory = StoreFixtureSeed.requestedDirectory {
                StoreFixtureSeedView(directory: directory)
            } else {
                StorageStateRoot { RootView() }
            }
            #else
            StorageStateRoot { RootView() }
            #endif
            #endif
            }
            // Cross-app open (`control-cross-app-open.md` §5i, TT-3). ⚠⚠ On iOS the URL is the
            // ONLY carrier: under route 1 every first-party app runs its own in-process proxy, so
            // there is no channel for Control to push a request down — CO-D3's cheap path does not
            // exist here.
            //
            // ⚠⚠ A scheme is not exclusive, and iOS delivers URLs from senders that have nothing
            // to do with us. `openTargetFrom` returns nil for all of them, and nil means "not for
            // me" — never an error, and never a reason to disturb what the user is looking at.
            .onOpenURL { url in
                DriveIosApp.handleOpenTarget(url)
            }
        }
    }

    /// Decode a cross-app open and hand it to the UI.
    ///
    /// ⚠⚠ **Decoding is all this does today, and that is the slice boundary.** TT-1a shipped the
    /// wire and the resolution with no delivery, for the same reason: the carrier is worth proving
    /// on its own, and routing a decoded target into Drive's SwiftUI navigation is per-app work
    /// that would hide whether the URL half ever worked.
    ///
    /// ⚠ The runtime may not be installed yet — a URL can arrive at launch, before `install()`
    /// runs — in which case this logs and returns. That is the ordering problem `OpenTargetInbox`'s
    /// replay solves on the other platforms, and iOS still owes its equivalent.
    static func handleOpenTarget(_ url: URL) {
        let target = DriveLocalRuntime.shared.openTargetFrom(url: url.absoluteString)
        #if DEV_HARNESS
        // ⚠⚠ Recorded BEFORE the guard, and on every URL — including ones we decline. A receipt
        // that only wrote on success made "the handler never ran" and "the handler ran and
        // decoded nothing" the same observable event, which is the exact diagnosability failure
        // this slice keeps finding one layer down. Writing the arrival separately is what makes
        // the harness able to answer the question it exists to answer.
        OpenTargetReceipt.sawUrl(url.absoluteString, decoded: target != nil)
        #endif
        // ⚠ nil is ordinary: a URL scheme is not exclusive, so "not one of ours" is the common
        // case and must not disturb the user.
        guard let target else { return }
        DriveIosApp.report(target)
    }

    /// ⚠ TEMPORARY — TT-3 iOS ships the CARRIER, not the navigation. Routing a decoded target
    /// into Drive's SwiftUI stack is per-app work, and doing it in the same slice would hide
    /// whether the URL half ever worked. TT-1a drew the same line for the same reason.
    static func report(_ target: OpenedTarget) {
        // The navigation half: hand it to the Files stack, which may not exist yet.
        OpenTargetRouter.shared.offer(target)
        NSLog("Tenshu cross-app open: type=\(target.type) key=\(target.key) parts=\(target.parts)")
        print("TENSHU-OPEN-TARGET type=\(target.type) key=\(target.key)")
        #if DEV_HARNESS
        OpenTargetReceipt.write(target)
        OpenTargetHarness.shared.record(target)
        #endif
    }

    #if DEV_HARNESS
    private var harnessTabs: some View {
        Group {
            TabView(selection: $selection) {
                StorageStateRoot { RootView() }
                    .tabItem { Label("App", systemImage: "folder") }
                    .tag(0)
                EpcPairingView()
                    .tabItem { Label("Pair", systemImage: "shield.lefthalf.filled") }
                    .tag(1)
            }
            // Round-trip harness: once the Pair tab installs the live client, jump to the App tab so
            // PairedAppView mounts and issues a real files query over the EPC channel (the data-op proof).
            .onReceive(NotificationCenter.default.publisher(for: .liveSessionChanged)) { _ in
                if ProcessInfo.processInfo.arguments.contains("-epcAutoConnectFromControl") {
                    selection = 0
                }
            }
        }
    }
    #endif
}

// P3b (ios-drive-parity.md): the background-URLSession relaunch hook. When a transfer finishes
// while Drive is suspended/terminated, iOS relaunches the app into the background and calls this;
// we stash the completion handler and re-attach the same-identifier session so `BackgroundUpload`
// replays the events, then invoke the handler from `urlSessionDidFinishEvents`.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    /// Route URL delivery through a **scene** delegate (`control-cross-app-open.md` §5i.7).
    ///
    /// ⚠⚠ **This is the path a SwiftUI app actually receives URLs on, and neither of the other two
    /// fired.** A scene-based app is offered a URL on its `UIScene`, not on the application:
    /// `application(_:open:options:)` is the pre-scene API and is simply not called, and
    /// SwiftUI's `.onOpenURL` — which normally bridges the scene callback — did not run either,
    /// measured with a proven instrument (§5i.7's elimination table). Supplying an explicit scene
    /// delegate is the one route that does not depend on that bridge.
    ///
    /// ⚠ The delegate deliberately creates no window: SwiftUI still owns the scene's content. It
    /// exists only to receive the two URL callbacks.
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: nil,
            sessionRole: connectingSceneSession.role
        )
        configuration.delegateClass = OpenUrlSceneDelegate.self
        #if DEV_HARNESS
        OpenTargetReceipt.note("configurationForConnecting — scene delegate installed")
        #endif
        return configuration
    }

    /// Cross-app open, delegate path (`control-cross-app-open.md` §5i).
    ///
    /// ⚠⚠ **Drive is the only first-party iOS app with a `UIApplicationDelegateAdaptor`, and that
    /// changes where a URL lands.** With an adaptor present, UIKit offers the URL to the app
    /// delegate first; SwiftUI's `.onOpenURL` is the *scene* path. Handling both is not belt-and-
    /// braces for its own sake — it is the difference between the one app that has a delegate
    /// behaving like the eight that do not.
    ///
    /// ⚠ Returns false for anything that is not ours, so UIKit's normal handling is unaffected.
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        guard let target = DriveLocalRuntime.shared.openTargetFrom(url: url.absoluteString) else {
            // ⚠ nil is also "held until the runtime is up" (`PendingOpenUrl`), which is still a URL
            // we accepted — but we cannot tell the two apart here, and claiming an unrelated URL
            // would be worse than declining one of ours.
            return false
        }
        DriveIosApp.report(target)
        return true
    }

    // Claim the notification delegate at launch. Drive posts only the new-album backup
    // prompt today; if a second notification surface lands, route here rather than
    // reassigning the delegate — there is exactly one per app.
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    // Show the prompt even while Drive is foregrounded: a new album is exactly as
    // relevant when the user happens to have the app open.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        NewFolderPrompt.shared.handle(response: response)
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == BackgroundUpload.sessionId else {
            completionHandler()
            return
        }
        BackgroundUpload.shared.backgroundCompletionHandler = completionHandler
        BackgroundUpload.shared.reattach()
    }
}

#if DEV_HARNESS
// P4 headless preview proof (ios-drive-parity.md): install the route-1 runtime, upload the tiny
// embedded fixtures through the REAL ingest path, then mount the production FilePreviewView on the
// uploaded file — the screenshot shows the router rendering real decrypted content (image fill /
// PDFKit page). Reached via `-previewImage` / `-previewPdf`.
struct PreviewProbeView: View {
    let fileName: String
    @State private var ready = false
    @State private var status = "Preparing preview fixtures…"

    var body: some View {
        Group {
            if ready {
                NavigationStack {
                    FilePreviewView(path: "/\(fileName)", name: fileName)
                        .navigationDestination(for: FileDetailRoute.self) { route in
                            FileDetailView(path: route.path, name: route.name, isFolder: false)
                        }
                }
            } else {
                VStack(spacing: 16) {
                    ProgressView()
                    Text(status)
                        .font(.system(.callout, design: .monospaced))
                        .padding(.horizontal, 24)
                }
            }
        }
        .task {
            DriveReadSurface.shared.installLocalRuntime()
            status = (try? await DriveLocalRuntime.shared.debugUploadPreviewFixtures()) ?? "fixtures threw"
            ready = true
        }
    }
}

// P1a route-1 production read (ios-drive-parity.md). Where the C4e views below are one-shot PROBES,
// this is the productized assembly: `DriveReadSurface.installLocalRuntime()` boots the full in-process
// proxy graph (`DriveLocalRuntime`) over the shared App-Group replica and swaps the app's repositories
// to it, then the REAL Files UI (`PairedAppView`) mounts on top — every row it lists is a real op
// through the real proxy stack against Control's replica, in Drive's own process. Reached via `-localRuntime`.
struct LocalRuntimeView: View {
    @State private var status = "Installing the route-1 in-process runtime…"
    @State private var installed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(status)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .padding(12)
            if installed {
                Divider()
                PairedAppView()
            }
        }
        .task {
            DriveReadSurface.shared.installLocalRuntime()
            installed = true
            status = "✓ route-1 local runtime installed — Files below is the REAL proxy stack over the "
                + "shared replica IN-PROCESS."
            // P1c: whether the runtime adopted Control's handed-off identity (live) or runs sealed.
            status += "\n\(DriveLocalRuntime.shared.debugAdoptionState())"
            // Op-layer cross-check off the paged pipeline: the plain read path against the same replica.
            let probe = (try? await DriveLocalRuntime.shared.debugListRoot()) ?? "probe threw"
            status += "\n\(probe)"
            // Paged-pipeline split: real Pager through the presenter on Default vs Main.
            let pagedDefault = (try? await DriveLocalRuntime.shared.debugPagedProbe(onMain: false)) ?? "probe threw"
            status += "\n\(pagedDefault)"
            let pagedMain = (try? await DriveLocalRuntime.shared.debugPagedProbe(onMain: true)) ?? "probe threw"
            status += "\n\(pagedMain)"
            // Give the (empty) genesis realm a row so Files renders real content, and prove
            // the Pager-invalidation observer refreshes the mounted list.
            let seed = (try? await DriveLocalRuntime.shared.debugSeedFolder()) ?? "seed threw"
            status += "\n\(seed)"
            // P1b: full mutation set through the real op path, lock-guarded; the mounted list
            // below must end at [P1b renamed (containing P1b temp), Route-1 demo].
            let mutations = (try? await DriveLocalRuntime.shared.debugMutationCycle()) ?? "mutations threw"
            status += "\n\(mutations)"
            // P3a: a real byte upload through the production ingest path — a document row appears.
            let upload = (try? await DriveLocalRuntime.shared.debugUploadProbe()) ?? "upload threw"
            status += "\n\(upload)"
            // P5: the sharing cluster live — a REAL invite via the relay mailbox (needs the
            // dev relay + registered device; fails loudly otherwise).
            let invite = (try? await DriveLocalRuntime.shared.debugCreateInviteProbe()) ?? "invite threw"
            status += "\n\(invite)"
        }
    }
}

#endif

/// Receives cross-app-open URLs on the scene (`control-cross-app-open.md` §5i.7).
///
/// ⚠⚠ **Both callbacks are needed and they are not interchangeable.** A URL that *launches* the app
/// arrives once, in `willConnectTo`'s `connectionOptions` — `openURLContexts` is never called for
/// it — and a URL delivered to an already-running app arrives only in `openURLContexts`. Wiring one
/// and not the other yields exactly half a feature, and the half that works is whichever one you
/// happened to test.
final class OpenUrlSceneDelegate: NSObject, UIWindowSceneDelegate {

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        #if DEV_HARNESS
        OpenTargetReceipt.note("willConnectTo urlContexts=\(connectionOptions.urlContexts.count)")
        #endif
        // ⚠ The cold-start case: this is the only delivery a launching URL gets.
        connectionOptions.urlContexts.forEach { DriveIosApp.handleOpenTarget($0.url) }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        #if DEV_HARNESS
        OpenTargetReceipt.note("openURLContexts count=\(URLContexts.count)")
        #endif
        // ⚠ The warm case.
        URLContexts.forEach { DriveIosApp.handleOpenTarget($0.url) }
    }
}
