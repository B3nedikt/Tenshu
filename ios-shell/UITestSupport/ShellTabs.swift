import XCTest

/// `AdaptiveAppShell`'s destinations, found by a UI test in each placement the shell draws.
/// Compiled into each app's store-screenshot target (its `project.yml`, beside `Screenshots`),
/// the way `shell.yml` compiles the shell itself into each app (docs/how-to/store-screenshots.md §3e).
///
/// ⚠⚠ By POSITION, never by title: tab titles localize (`AppDestination.title` is a
/// `LocalizedStringKey`), and SwiftUI carries no `accessibilityIdentifier` of ours onto a tab
/// button. `index` is the destination's place in the app's `AppDestination` list, which the shell
/// requires to be fixed.
///
/// The three placements, as XCUITest sees them on the iOS 27 simulator:
///
/// - **iPhone** — a `TabBar` at the bottom whose buttons are the destinations.
/// - **iPad, tab bar** (any width under the shell's 1100 pt — a 13" iPad in portrait) — ⚠⚠ **no
///   `TabBar` element at all.** The floating pill at the top is a plain `Other` holding the
///   sidebar toggle (`ToggleSideBar`, *Toggle sidebar*) and then one `Button` per destination,
///   each with a nested duplicate `Button` and the SF symbol as its identifier (`house.fill`).
///   So `tabBars.buttons` finds nothing there, and a positional lookup must skip the toggle and
///   take only the container's DIRECT button children (the duplicates are grandchildren).
/// - **iPad, sidebar** (1100 pt and wider — landscape) — the toggle moves into the sidebar panel
///   and the destinations become its rows (`ios-adaptive-navigation.md` §2.1 F3/F4). ⚠⚠ NOT
///   supported: no walk has run there (they all run portrait), so rather than guess at the
///   sidebar's hierarchy, `shellTab` FAILS the test, saying so, when the toggle's row of buttons is
///   not a horizontal tab bar.
///
/// ⚠ The toggle's identifier changes case with its state (`ToggleSideBar` collapsed,
/// `ToggleSidebar` expanded — F4), so it is matched case-insensitively.
extension XCUIApplication {

    /// The destination at `index`, resolved when called — so call it once the shell is up
    /// (`waitForShell`), and look it up again rather than holding the element across launches.
    func shellTab(_ index: Int, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        if tabBars.firstMatch.exists {
            return tabBars.firstMatch.buttons.element(boundBy: index)
        }
        // ONE snapshot of the tree (Photos' library holds hundreds of elements; a query per
        // candidate is slow), searched for the toggle's own parent — the node whose DIRECT
        // children include the toggle (as `PhotosWalk.floatingTabs` does).
        guard let root = try? snapshot(), let pill = Self.togglePath(root) else {
            XCTFail("shellTab: no tab bar and no sidebar toggle on screen — is the shell up? (waitForShell)",
                    file: file, line: line)
            return buttons["shellTab: no shell"]
        }
        let parent = pill.last!
        let siblings = parent.children.filter { $0.elementType == .button }
        let toggleY = siblings.first(where: Self.isToggle)!.frame.midY
        let row = siblings.filter { !Self.isToggle($0) }
        // The iPad tab bar is ONE row: every destination level with the toggle. Anything else is
        // the sidebar placement (or a hierarchy this was never measured on) — fail, don't guess.
        guard !row.isEmpty, row.allSatisfy({ abs($0.frame.midY - toggleY) < 4 }) else {
            XCTFail("shellTab: the sidebar toggle's buttons are not a tab bar — the sidebar placement " +
                    "(a window ≥ 1100 pt wide) is not supported; run the walk in portrait. Found: " +
                    row.map(\.label).joined(separator: ", "), file: file, line: line)
            return buttons["shellTab: sidebar unsupported"]
        }
        // Addressed by POSITION inside that exact container — never by re-querying the tab's
        // identifier, an SF symbol another button on screen may carry too. The container is the
        // k-th element of its type that contains the toggle: those are exactly the toggle's
        // ancestors of that type, in document order (outermost first).
        let k = pill.filter { $0.elementType == parent.elementType }.count - 1
        return descendants(matching: parent.elementType)
            .containing(Self.sidebarToggle)
            .element(boundBy: k)
            .children(matching: .button)
            .matching(NSCompoundPredicate(notPredicateWithSubpredicate: Self.sidebarToggle))
            .element(boundBy: index)
    }

    /// Waits until the shell's destinations are on screen, in whichever placement. `false` when
    /// they never appeared within `timeout`.
    @discardableResult
    func waitForShell(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if isShellUp { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        } while Date() < deadline
        return isShellUp
    }

    /// The shell's destinations are on screen (a tab bar, or the iPad's toggle beside its tabs).
    var isShellUp: Bool {
        tabBars.firstMatch.exists || buttons.matching(Self.sidebarToggle).firstMatch.exists
    }

    /// The path from `node` down to the sidebar toggle's own PARENT (inclusive): the node whose
    /// DIRECT children include the toggle. ⚠ Not "a node that contains the toggle" — every
    /// ancestor does, and a direct button of an outer one would shift the indices.
    private static func togglePath(_ node: XCUIElementSnapshot) -> [XCUIElementSnapshot]? {
        if node.children.contains(where: isToggle) { return [node] }
        for child in node.children {
            if let path = togglePath(child) { return [node] + path }
        }
        return nil
    }

    private static func isToggle(_ node: XCUIElementSnapshot) -> Bool {
        node.elementType == .button && node.identifier.lowercased() == "togglesidebar"
    }

    private static let sidebarToggle = NSPredicate(
        format: "elementType == %lu AND identifier ==[c] 'ToggleSidebar'",
        XCUIElement.ElementType.button.rawValue
    )
}
