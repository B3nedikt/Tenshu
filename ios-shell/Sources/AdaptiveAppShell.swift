import SwiftUI

/// The one top-level navigation shell every tab-shaped first-party iOS app renders
/// (`docs/proposals/ios-adaptive-navigation.md`, N6).
///
/// Its whole job is one decision: **a tab bar on phones and narrow iPads, a sidebar on wide
/// iPads** — matching what iPadOS itself does, measured against Apple's own Files app rather
/// than read off the HIG (§2.1 F12/F13; the guideline text alone produced the wrong answer).
///
/// ⚠⚠ **Why one component and not `if wide { NavigationSplitView } else { TabView }`.** A
/// container swap tears down and rebuilds the whole subtree on **every rotation and every Split
/// View resize**, and each destination's `NavigationStack` path lives inside that subtree — so
/// the user is thrown back to the root mid-gesture. `.sidebarAdaptable` changes *presentation*
/// without changing the view tree, so the paths survive by construction. This is the iOS twin of
/// what `ui-adaptive-navigation.md` N3 recorded for Compose: one component that adapts beats two
/// components selected by a `when`.
///
/// Compiled **into** each app target via `ios-shell/shell.yml` rather than vended as a Swift
/// package — same constraint `ios-onboarding` documents: a SwiftPM `.binaryTarget` resolves before
/// any build phase runs, so a package sitting alongside the Gradle-built `TenshuSdk` framework
/// fails on the fresh `-derivedDataPath` this repo's build instructions require.
struct AdaptiveAppShell: View {

    private let destinations: [AppDestination]
    private let search: AppDestination?
    private let destinationOverlay: AnyView?
    @Binding private var selection: String

    /// ⚠ The selection is a **binding the host owns**, deliberately, even for apps that never
    /// write it. Hoisting it above the shell is what makes the container safe to change its mind
    /// about: the `if #available` split below, and any future placement change, must not be able
    /// to take navigation state with it. Apps with a cross-destination jump (Fit's Browse →
    /// Profile) need the binding anyway; the rest pay one `@State` line to make the hoist explicit.
    ///
    /// `search` — an app's **search destination**, or `nil` (every app but Photos today). It is
    /// selected by its `id` through the same binding, and drawn as the OS draws a search tab:
    /// iOS 26's detached round button, a trailing magnifying-glass tab on iOS 18, an ordinary last
    /// tab on iOS 16–17, and a row of the sidebar on a wide iPad (`photos-ios.md` §8.19).
    ///
    /// ⚠⚠ **Purely additive.** With `search == nil` the body is the `tabItem` code every app has run
    /// since N6, untouched — see `tabs`. An app opts in by passing one, and only then does its tab
    /// bar move to the iOS 18 `Tab` builder.
    ///
    /// `destinationOverlay` — one view drawn bottom-aligned over **every** destination's content,
    /// or `nil`. It is how an app shows one small thing "above the tab bar" in every section (the
    /// sync-activity capsule, `ios-sync-activity-indicator.md` §6a.2).
    ///
    /// ⚠⚠ Over each destination, not over the shell: an overlay on the `TabView` itself sits ON the
    /// tab bar, and only a view inside a tab inherits the safe area that clears it — in either
    /// placement, on every OS version, with nothing measured.
    /// ⚠ The shell knows nothing about what it is. A view that needs state must hold it above the
    /// shell: a destination is built when first selected, so each one gets its own copy of this.
    init(
        selection: Binding<String>,
        destinations: [AppDestination],
        search: AppDestination? = nil,
        destinationOverlay: AnyView? = nil
    ) {
        self._selection = selection
        self.destinations = destinations
        self.search = search
        self.destinationOverlay = destinationOverlay
    }

    /// Measured live rather than taken from `UIScreen`: under Split View / Slide Over the window
    /// is narrower than the screen, and that is precisely the case where the answer differs.
    @State private var width: CGFloat = 0

    private var placement: ShellPlacement { ShellPlacement.forWidth(width) }

    var body: some View {
        tabs
        .modifier(AdaptivePlacement(placement: placement))
        // ⚠ Measured in a `background`, not by wrapping the `TabView` in a `GeometryReader`:
        // a GeometryReader parent places its child at its ideal size in the top-leading corner
        // and swallows the safe-area behaviour the tab bar depends on. A zero-sized background
        // reader observes the same width and changes no layout.
        .background(
            GeometryReader { geometry in
                Color.clear.preference(key: ShellWidthKey.self, value: geometry.size.width)
            }
        )
        .onPreferenceChange(ShellWidthKey.self) { width = $0 }
        #if DEBUG
        // `-shellDestination <id>` (debug builds only): open on that destination. `simctl` has no
        // tap, so this is how a scripted launch looks at a tab other than the first.
        .onAppear {
            let args = ProcessInfo.processInfo.arguments
            guard let i = args.firstIndex(of: "-shellDestination"), i + 1 < args.count else { return }
            let known = destinations.map(\.id) + (search.map { [$0.id] } ?? [])
            if known.contains(args[i + 1]) { selection = args[i + 1] }
        }
        #endif
    }
}

extension AdaptiveAppShell {
    /// The tab container.
    ///
    /// ⚠⚠ **Which branch runs is fixed for the life of the app** — `search` is a `let`, and the OS
    /// version does not change under a running process — so this `if` never swaps one container for
    /// the other and never rebuilds a destination's `NavigationStack` (the type's own rule, above).
    ///
    /// ⚠⚠ Why two APIs and not one. The search **role** exists only on the iOS 18 `Tab` builder, and
    /// a `TabView` takes either `Tab`s or `tabItem` views, never both. Moving every app to `Tab` to
    /// serve the one app with a search tab would change eight shipped tab bars for nothing — so an
    /// app with no search destination stays on the `tabItem` path, verbatim, and an app with one
    /// uses `Tab` where the OS has it and falls back to an ordinary last tab where it does not.
    @ViewBuilder
    fileprivate var tabs: some View {
        if let search {
            if #available(iOS 18.0, *) {
                SearchRoleTabs(
                    selection: $selection,
                    destinations: destinations,
                    search: search,
                    placement: placement,
                    destinationOverlay: destinationOverlay
                )
            } else {
                // iOS 16–17: no role to give — Search is the last tab of a plain tab bar.
                classicTabs(destinations + [search])
            }
        } else {
            classicTabs(destinations)
        }
    }

    /// The `tabItem` container — N6's body, unchanged.
    private func classicTabs(_ destinations: [AppDestination]) -> some View {
        TabView(selection: $selection) {
            ForEach(destinations) { destination in
                destination.content
                    .destinationOverlay(destinationOverlay)
                    .tabItem {
                        // HIG: a sidebar takes the **outlined** symbol, a tab bar the filled one.
                        Label(destination.title, systemImage: placement == .sidebar
                            ? destination.symbol
                            : destination.filledSymbol)
                    }
                    .badge(destination.badge ?? 0)   // 0 renders nothing
                    .tag(destination.id)
            }
        }
    }
}

/// The same destinations through the iOS 18 `Tab` builder, plus one `Tab(role: .search)`.
///
/// The role is what makes the OS draw it as ITS search affordance instead of a tab like the others:
/// detached from the bar as a round button on iOS 26 (what Apple's Photos does), the trailing
/// magnifying-glass tab on iOS 18, and pinned in the sidebar on a wide iPad. Nothing here positions
/// it — a search tab placed by hand would be right on one OS version and wrong on the next.
///
/// ⚠ `.sidebarAdaptable` and `defaultAdaptableTabBarPlacement` (`AdaptivePlacement`) apply to this
/// container exactly as to the `tabItem` one: the `Tab` builder is the API they were designed for.
/// ⚠ `SwiftUI.Tab`, qualified: this file is compiled INTO every app target (`shell.yml`), and an app
/// that one day declares its own `Tab` type would otherwise shadow it here.
@available(iOS 18.0, *)
private struct SearchRoleTabs: View {
    @Binding var selection: String
    let destinations: [AppDestination]
    let search: AppDestination
    let placement: ShellPlacement
    let destinationOverlay: AnyView?

    var body: some View {
        TabView(selection: $selection) {
            ForEach(destinations) { destination in
                SwiftUI.Tab(
                    destination.title,
                    // HIG: a sidebar takes the **outlined** symbol, a tab bar the filled one.
                    systemImage: placement == .sidebar ? destination.symbol : destination.filledSymbol,
                    value: destination.id
                ) {
                    destination.content.destinationOverlay(destinationOverlay)
                }
                .badge(destination.badge ?? 0)   // 0 renders nothing
            }
            SwiftUI.Tab(search.title, systemImage: search.symbol, value: search.id, role: .search) {
                search.content.destinationOverlay(destinationOverlay)
            }
        }
    }
}

private extension View {
    /// The shell's one overlay over a destination. ⚠ With none, the destination is returned
    /// untouched: an app that passes nothing runs the view tree it ran before the parameter existed.
    @ViewBuilder
    func destinationOverlay(_ overlay: AnyView?) -> some View {
        if let overlay {
            self.overlay(alignment: .bottom) { overlay }
        } else {
            self
        }
    }
}

/// Which presentation the shell wants. Not a user preference — the *default* for the current
/// width; `.sidebarAdaptable` still vends its own toggle so the user can override per session.
enum ShellPlacement {
    case tabBar
    case sidebar

    /// ⚠ **1100 pt is ours, bracketed by measurement — it is not a published Apple value**, and
    /// the HIG states none (`ios-adaptive-navigation.md` D5). Apple's Files app, on this repo's
    /// simulators, defaults to:
    ///
    /// | width | Files shows |
    /// |---|---|
    /// | 834 pt — 11" portrait | tab bar |
    /// | 1024 pt — 13" portrait | tab bar |
    /// | 1210 pt — 11" landscape | **sidebar** |
    ///
    /// So the real breakpoint lies in (1024, 1210]. 1100 is the midpoint of the bracket. ⚠ If a
    /// future measurement narrows it, move this constant — do not add a second heuristic beside it.
    ///
    /// ⚠ A width of 0 means "not measured yet" (first pass, before the background reader reports)
    /// and must read as `.tabBar`: starting narrow and widening is a quiet correction, whereas
    /// starting wide would flash a sidebar onto a phone.
    static func forWidth(_ width: CGFloat) -> ShellPlacement {
        width >= 1100 ? .sidebar : .tabBar
    }
}

/// One destination in the shell — a top-level app section.
struct AppDestination: Identifiable {
    let id: String
    /// ⚠⚠ A `LocalizedStringKey`, not a `String`: `Label`/`Tab` render a `String` **verbatim**, which
    /// is how every tab bar stayed English on a German device although the keys were translated.
    /// The key resolves against `Bundle.main` — and because this file is compiled INTO each app
    /// target (`shell.yml`), that is the **app's own** `Localizable.xcstrings`, not a shell bundle.
    /// So each app owns its tab titles' translations; a title with no entry falls back to the key.
    let title: LocalizedStringKey
    /// The **outlined** SF Symbol, used in the sidebar (HIG).
    let symbol: String
    /// The **filled** variant, used in the tab bar. Defaults to `symbol` for symbols that have no
    /// filled counterpart, or where the outline is already the right weight in both.
    let filledSymbol: String
    /// Badge count, or nil for none. Shown in both placements.
    let badge: Int?
    let content: AnyView

    /// ⚠ `AnyView` is the right erasure here despite the usual advice: the destination list is
    /// heterogeneous, `TabContentBuilder` is iOS 18+ and this shell must build at 16, and each
    /// destination is mounted once for the app's lifetime rather than in a list body. Identity
    /// comes from `id`, so it is stable as long as the array is built in a fixed order — **that
    /// stability is the requirement**, not the erasure.
    init<Content: View>(
        id: String,
        title: LocalizedStringKey,
        symbol: String,
        filledSymbol: String? = nil,
        badge: Int? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.id = id
        self.title = title
        self.symbol = symbol
        self.filledSymbol = filledSymbol ?? symbol
        self.badge = badge
        self.content = AnyView(content())
    }
}

/// Applies the adaptive style where the OS has it.
///
/// ⚠ `.sidebarAdaptable` is iOS 18+, the deployment floor is 16.0, and the floor is staying
/// (`ios-adaptive-navigation.md` Q-A): availability-gating loses nobody, and because this shell is
/// shared the gate exists **once** rather than once per app. iOS 16/17 iPads keep the tab bar.
///
/// ⚠⚠ It composes with the legacy `.tabItem` API — verified on 18.5 and 26.5 (§2.1 F1). No
/// migration to the iOS 18 `Tab(…)` builder is required, which is what keeps adoption to one
/// modifier per app. ⚠ An app WITH a search destination is on that builder (`SearchRoleTabs`), and
/// this modifier applies to it unchanged.
private struct AdaptivePlacement: ViewModifier {
    let placement: ShellPlacement

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content
                .tabViewStyle(.sidebarAdaptable)
                .defaultAdaptableTabBarPlacement(placement == .sidebar ? .sidebar : .tabBar)
        } else {
            content
        }
    }
}

private struct ShellWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
