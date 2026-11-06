import SwiftUI

// The paired first-party app (proposal §11.6): a two-tab shell — Files + Shared.
// Folder rows push a child Files screen along the Files tab's stack.
struct PairedAppView: View {
    // Drives the Files tab's stack programmatically. Rows navigate by appending here instead of
    // being `NavigationLink`s, which is what lets them drop the disclosure chevron: a row
    // carries a visible overflow menu (§23), and a chevron next to it is both visual clutter and
    // a second competing trailing tap target. Google Drive / OneDrive / Dropbox all show the
    // overflow and NO chevron; Apple Files shows the chevron and has no per-row button. The two
    // are alternative answers to "what does touching this row do" — pick one. SwiftUI has no API
    // to hide a NavigationLink's indicator, hence the explicit path.
    @StateObject private var filesRouter = FilesRouter()

    /// Hoisted above `AdaptiveAppShell` so the container can change presentation (tab bar ↔
    /// sidebar, on rotation or a Split View resize) without taking the selection with it. Drive
    /// never writes it itself; the binding exists so that stays true.
    @State private var selection = "files"

    /// Cross-app open (`control-cross-app-open.md` §5i): a row another app asked Drive to show.
    @ObservedObject private var openTargets = OpenTargetRouter.shared

    /// The sync-activity capsule (`ios-sync-activity-indicator.md` §6a.2): observed ONCE here, drawn
    /// by the shell over whichever destination is showing.
    @StateObject private var syncActivity = SyncActivityModel()

    var body: some View {
        // ⚠ Above the shell, not inside a tab: the realm's storage state is true of the whole app,
        // and a user who only ever opens Shared is owed it just as much. SwiftUI's safe area keeps
        // it clear of the status bar on its own; the Compose side pads for it explicitly.
        VStack(spacing: 0) {
            RealmStorageBanner()
            AdaptiveAppShell(selection: $selection, destinations: [
            AppDestination(id: "files", title: "Files", symbol: "folder", filledSymbol: "folder.fill") {
                NavigationStack(path: $filesRouter.path) {
                    // `String(localized:)`: the root title matches its localized tab, "Dateien" in German.
                    FilesView(folderPath: FilesView.rootPath, title: String(localized: "Files"), isRoot: true)
                        .navigationDestination(for: FolderRoute.self) { route in
                            FilesView(folderPath: route.path, title: route.name, isRoot: false)
                        }
                        .navigationDestination(for: FileRoute.self) { route in
                            // A tapped file opens its PREVIEW (the canonical UX); the detail +
                            // actions screen sits behind the preview's ⓘ toolbar button.
                            FilePreviewView(path: route.path, name: route.name)
                        }
                        .navigationDestination(for: FileDetailRoute.self) { route in
                            FileDetailView(
                                path: route.path,
                                name: route.name,
                                isFolder: false,
                                onFileGone: { filesRouter.popToFolder() },
                                onFileRenamed: { newPath in
                                    filesRouter.retargetFileChain(
                                        path: newPath,
                                        name: (newPath as NSString).lastPathComponent
                                    )
                                }
                            )
                        }
                }
                // Child `FilesView`s are built by `navigationDestination` above, so they inherit
                // the router from the environment rather than threading a binding through every hop.
                .environmentObject(filesRouter)
            },
            AppDestination(id: "shared", title: "Shared", symbol: "person.2", filledSymbol: "person.2.fill") {
                NavigationStack {
                    SharedView()
                }
            }
            ],
            // Drive takes the wall: this view is not built without a runtime, and it has no problem bar
            // of its own. It does mount `RealmStorageBanner`, above.
            destinationOverlay: AnyView(SyncActivityCapsuleSlot(
                model: syncActivity,
                hostProblem: false,
                showsStorageBar: true,
                hidden: false
            )))
        }
        .syncActivityObservation(syncActivity)
        // ⚠⚠ **Both, and neither is redundant.** `task` catches an open that was already waiting
        // when this view appeared — the cold-launch case, where the URL beat the Control session —
        // and `onChange` catches one that arrives while Drive is already on screen. Wiring only the
        // second loses every launch-time open; only the first loses every warm one.
        .task { showPendingOpenTarget() }
        .onChange(of: openTargets.pendingFilePath) { _ in showPendingOpenTarget() }
    }

    /// Push the file a cross-app open named.
    ///
    /// ⚠ Pushes rather than resetting the stack: the user may be deep in a folder, and throwing
    /// that away to land on the file would make an *open* destroy their place. Back returns them
    /// exactly where they were.
    ///
    /// ⚠ Switches to the Files tab first — a push onto a stack the user cannot see is the same
    /// silent no-op the whole receiving half exists to avoid.
    private func showPendingOpenTarget() {
        guard let path = openTargets.take() else { return }
        selection = "files"
        filesRouter.push(
            FileRoute(path: path, name: (path as NSString).lastPathComponent)
        )
    }
}

/// The Files tab's navigation path. Held by `PairedAppView` and published into the environment
/// so any depth of `FilesView` can push without a chevron-bearing `NavigationLink`.
@MainActor
final class FilesRouter: ObservableObject {
    @Published var path = NavigationPath()

    func push<Route: Hashable>(_ route: Route) { path.append(route) }

    /// Unwinds a whole file chain — the detail screen **and** the preview beneath it — back to
    /// the folder listing that owns them.
    ///
    /// Tapping a file pushes two hops (`FileRoute` preview → `FileDetailRoute` detail via the
    /// preview's ⓘ), so an action on the detail screen that leaves the path invalid (delete,
    /// move) cannot simply `dismiss()`: that lands on the preview of a file that is no longer
    /// there, which renders "Couldn't load this file". The hop count lives here, next to the
    /// route declarations that define it — `NavigationPath` is opaque, so it cannot be derived.
    func popToFolder() { path.removeLast(min(2, path.count)) }

    /// Re-addresses the current file chain at `path`/`name` — both hops, because both carry the
    /// old path (the preview loads bytes by it, the detail screen acts by it).
    ///
    /// Used after a **rename**, which is a `MoveFile` and therefore changes the file's identity.
    /// Rebuilding the two hops rather than popping keeps the user on the file they just renamed;
    /// the replaced screens come back with fresh view models bound to the new path.
    func retargetFileChain(path newPath: String, name: String) {
        popToFolder()
        push(FileRoute(path: newPath, name: name))
        push(FileDetailRoute(path: newPath, name: name))
    }
}

/// A folder push-nav hop.
struct FolderRoute: Hashable {
    let path: String
    let name: String
}

/// A file push-nav hop — opens the file's preview.
struct FileRoute: Hashable {
    let path: String
    let name: String
}
