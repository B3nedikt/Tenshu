// Drives an installed Tenshu desktop app through macOS Accessibility for the store captures
// (docs/how-to/store-screenshots.md §10). Compose Desktop exposes its semantics as AX elements, so
// a control is found by the name a screen reader would read — the same rule as the Android walk's
// text match — never by coordinates.
//
// usage: desktop_shots <command> <process name> [args]
//   frontmost <proc>                     bring the app forward
//   size <proc> <w> <h> [x y]            set the main window's size (points) and origin; without x y,
//                                        a screen corner the pointer is not over (no hover in a capture)
//   window <proc>                        print "<CGWindowID> <x> <y> <w> <h>" of the main window
//   dump <proc>                          print every element: depth, role, description | title | value
//   has <proc> <label>                   exit 0 if an element carries the label, 1 if not
//   wait <proc> <label> [seconds]        wait until an element carries the label (default 20 s)
//   press <proc> <label> [nth] [up]      AXPress the nth (default 1st) element carrying the label, or
//                                        its ancestor `up` levels above (a Drive row: "More actions for X" 2)
//   scroll <proc> <lines> [then-shot.png] scroll the window's centre (the pointer visits it and returns
//                                        to where it was); positive = down. With a path, capture the
//                                        window right after — for UI that fades when scrolling stops
//   set <proc> <label> <text>            put text into the field carrying the label (AXValue, or focus
//                                        and type it — the app must be frontmost)
//   pressafter <proc> <label> <k>        AXPress the k-th element after the labelled one, in tree order
//                                        (unlabelled controls: Calendar's colour swatches)
//   scrub <proc> <x> <y0> <y1> <shot>    drag a scrubber thumb from (x,y0) to (x,y1), window points, and
//                                        capture while it is held (year pills and date bubble show then)
//
// ⚠ The first coordinate-addressed command: scroll only says WHERE in the window, never what it hits.
// A label matches an element's description, title or value exactly; "~text" matches a substring.
// ⚠ Needs Accessibility for the process macOS holds responsible — under Claude Code that is its own
// bundled claude.app, not Claude.app (how-to §10.2).
import AppKit
import ApplicationServices

func fail(_ message: String) -> Never {
    FileHandle.standardError.write("desktop_shots: \(message)\n".data(using: .utf8)!)
    exit(2)
}

func app(_ name: String) -> NSRunningApplication {
    let found = NSWorkspace.shared.runningApplications.filter {
        $0.localizedName == name || $0.executableURL?.lastPathComponent == name
    }
    guard let first = found.first else { fail("\(name) is not running") }
    return first
}

func attr(_ e: AXUIElement, _ key: String) -> AnyObject? {
    var v: AnyObject?
    return AXUIElementCopyAttributeValue(e, key as CFString, &v) == .success ? v : nil
}

func text(_ e: AXUIElement, _ key: String) -> String? {
    attr(e, key) as? String
}

func mainWindow(_ a: NSRunningApplication) -> AXUIElement {
    // ⚠ With no window open (Control closed to its tray), kAXMainWindow can answer with the
    // application element itself, whose children lead back to it: walking that never ends. Accept
    // only an element whose role really is AXWindow.
    // ⚠ A busy app (Drive mid-upload) can miss an AX request; retry for 10 s before giving up.
    let root = AXUIElementCreateApplication(a.processIdentifier)
    let isWindow = { (e: AXUIElement) in text(e, kAXRoleAttribute) == (kAXWindowRole as String) }
    let deadline = Date().addingTimeInterval(10)
    repeat {
        if let w = attr(root, kAXMainWindowAttribute), CFGetTypeID(w) == AXUIElementGetTypeID(),
           isWindow(w as! AXUIElement) { return w as! AXUIElement }
        if let ws = attr(root, kAXWindowsAttribute) as? [AXUIElement], let w = ws.first(where: isWindow) { return w }
        Thread.sleep(forTimeInterval: 0.5)
    } while Date() < deadline
    fail("\(a.localizedName ?? "app") has no open window")
}

func walk(_ e: AXUIElement, _ depth: Int = 0, _ visit: (AXUIElement, Int) -> Bool) -> Bool {
    if depth > 60 { fail("accessibility tree deeper than 60 — a cycle?") }
    if !visit(e, depth) { return false }
    for c in (attr(e, kAXChildrenAttribute) as? [AXUIElement]) ?? [] {
        if !walk(c, depth + 1, visit) { return false }
    }
    return true
}

func labels(_ e: AXUIElement) -> [String] {
    [kAXDescriptionAttribute, kAXTitleAttribute, kAXValueAttribute].compactMap { text(e, $0) }.filter { !$0.isEmpty }
}

func matches(_ e: AXUIElement, _ label: String) -> Bool {
    if label.hasPrefix("~") {
        let needle = String(label.dropFirst())
        return labels(e).contains { $0.contains(needle) }
    }
    return labels(e).contains(label)
}

func find(_ a: NSRunningApplication, _ label: String) -> [AXUIElement] {
    var out: [AXUIElement] = []
    _ = walk(mainWindow(a)) { e, _ in
        if matches(e, label) { out.append(e) }
        return true
    }
    return out
}

func point(_ e: AXUIElement, _ key: String) -> CGPoint {
    var p = CGPoint.zero
    if let v = attr(e, key) { AXValueGetValue(v as! AXValue, .cgPoint, &p) }
    return p
}

func size(_ e: AXUIElement, _ key: String) -> CGSize {
    var s = CGSize.zero
    if let v = attr(e, key) { AXValueGetValue(v as! AXValue, .cgSize, &s) }
    return s
}

/// Capture the on-screen window of `a` that has size `s` (the pointer is never drawn).
func capture(_ a: NSRunningApplication, _ s: CGSize, _ path: String) {
    let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
    let id = list.first { info in
        guard (info[kCGWindowOwnerPID as String] as? Int32) == a.processIdentifier,
              let b = info[kCGWindowBounds as String] as? [String: CGFloat] else { return false }
        return abs((b["Width"] ?? 0) - s.width) < 2 && abs((b["Height"] ?? 0) - s.height) < 2
    }?[kCGWindowNumber as String] as? Int
    guard let id else { fail("no on-screen window to capture") }
    let r = Process()
    r.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
    r.arguments = ["-l", "\(id)", "-o", "-x", path]
    try! r.run(); r.waitUntilExit()
    if r.terminationStatus != 0 { fail("screencapture failed") }
}

let args = CommandLine.arguments
guard args.count >= 3 else { fail("usage: desktop_shots <command> <process name> [args]") }
guard AXIsProcessTrusted() else { fail("no Accessibility access (how-to §10.2)") }
let target = app(args[2])

switch args[1] {
case "frontmost":
    // ⚠ activate() returns before the app is in front; a capture taken then has grey traffic
    // lights and a dimmed title. Wait until it really is the frontmost app.
    let deadline = Date().addingTimeInterval(5)
    repeat {
        target.activate()
        AXUIElementPerformAction(mainWindow(target), kAXRaiseAction as CFString)
        Thread.sleep(forTimeInterval: 0.3)
        if NSWorkspace.shared.frontmostApplication?.processIdentifier == target.processIdentifier { break }
    } while Date() < deadline
    if NSWorkspace.shared.frontmostApplication?.processIdentifier != target.processIdentifier {
        fail("\(args[2]) did not come to the front")
    }
case "size":
    guard args.count >= 5, let w = Double(args[3]), let h = Double(args[4]) else { fail("size <proc> <w> <h> [x y]") }
    let win = mainWindow(target)
    var p: CGPoint
    if args.count >= 7, let x = Double(args[5]), let y = Double(args[6]) {
        p = CGPoint(x: x, y: y)
    } else {
        // No origin given: a corner of the main screen the POINTER is not over. A pointer resting
        // on the window draws a hover highlight on whatever row is under it, into the capture.
        guard let screen = NSScreen.screens.first else { fail("no screen") }
        // ⚠ Inside the VISIBLE frame: a window whose bottom reaches under the Dock has its height
        // clamped by macOS (asked for 800, given 820 to the Dock's edge), and the check below fails.
        let sh = screen.frame.height, v = screen.visibleFrame
        let top = sh - v.maxY + 4, bottom = sh - v.minY - h - 4 // AX is top-left, AppKit bottom-left
        let left = v.minX + 20, right = v.maxX - w - 20
        let m = NSEvent.mouseLocation
        let pointer = CGPoint(x: m.x, y: sh - m.y)
        let corners = [CGPoint(x: left, y: top), CGPoint(x: right, y: top),
                       CGPoint(x: left, y: bottom), CGPoint(x: right, y: bottom)]
        guard let free = corners.first(where: { !CGRect(origin: $0, size: CGSize(width: w, height: h)).insetBy(dx: -4, dy: -4).contains(pointer) })
        else { fail("the pointer is over every place a \(Int(w))×\(Int(h)) window fits — move it to a screen edge") }
        p = free
    }
    AXUIElementSetAttributeValue(win, kAXPositionAttribute as CFString, AXValueCreate(.cgPoint, &p)!)
    var s = CGSize(width: w, height: h)
    AXUIElementSetAttributeValue(win, kAXSizeAttribute as CFString, AXValueCreate(.cgSize, &s)!)
    let got = size(win, kAXSizeAttribute)
    if Int(got.width) != Int(w) || Int(got.height) != Int(h) { fail("window is \(Int(got.width))×\(Int(got.height)), not \(Int(w))×\(Int(h))") }
case "window":
    let win = mainWindow(target)
    let p = point(win, kAXPositionAttribute), s = size(win, kAXSizeAttribute)
    let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
    // The CG window of this pid whose bounds are the AX window's: an app can own several (menus, tooltips).
    let id = list.first { info in
        guard (info[kCGWindowOwnerPID as String] as? Int32) == target.processIdentifier,
              let b = info[kCGWindowBounds as String] as? [String: CGFloat] else { return false }
        return abs((b["X"] ?? 0) - p.x) < 2 && abs((b["Y"] ?? 0) - p.y) < 2 &&
            abs((b["Width"] ?? 0) - s.width) < 2 && abs((b["Height"] ?? 0) - s.height) < 2
    }?[kCGWindowNumber as String] as? Int
    guard let id else { fail("no on-screen window of \(args[2]) at the AX window's bounds") }
    print("\(id) \(Int(p.x)) \(Int(p.y)) \(Int(s.width)) \(Int(s.height))")
case "scroll":
    guard args.count >= 4, let lines = Int32(args[3]) else { fail("scroll <proc> <lines> [then-shot.png]") }
    let win = mainWindow(target)
    let p = point(win, kAXPositionAttribute), s = size(win, kAXSizeAttribute)
    let centre = CGPoint(x: p.x + s.width / 2, y: p.y + s.height / 2)
    // ⚠ AWT ignores a scroll posted to the process (postToPid, even naming the window), and the
    // feed's scroll areas expose no AX scroll action. The one route is the window server: put the
    // pointer on the window for the scroll, then back where it was, with a move event so the app
    // sees the hover end before the capture.
    let home = CGEvent(source: nil)?.location ?? .zero
    CGWarpMouseCursorPosition(centre)
    Thread.sleep(forTimeInterval: 0.05)
    let step: Int32 = lines > 0 ? -1 : 1 // wheel units: negative moves the content up (down the list)
    for _ in 0..<abs(lines) {
        guard let e = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: step, wheel2: 0, wheel3: 0)
        else { fail("could not make a scroll event") }
        e.location = centre
        e.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.02)
    }
    CGWarpMouseCursorPosition(home)
    CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: home, mouseButton: .left)?.post(tap: .cghidEventTap)
    if args.count >= 5 {
        Thread.sleep(forTimeInterval: 0.35) // let the frame settle, well inside a 1.2 s fade
        capture(target, s, args[4])
    }
case "scrub":
    // Hold a scrubber thumb mid-drag and capture: a fast-scroll thumb shows its year pills and date
    // bubble only while held. Window-relative points; the wheel first, to wake a thumb that sleeps.
    guard args.count >= 7, let x = Double(args[3]), let y0 = Double(args[4]), let y1 = Double(args[5])
    else { fail("scrub <proc> <x> <y-from> <y-to> <shot.png>") }
    let win = mainWindow(target)
    let p = point(win, kAXPositionAttribute), s = size(win, kAXSizeAttribute)
    let home = CGEvent(source: nil)?.location ?? .zero
    let centre = CGPoint(x: p.x + s.width / 2, y: p.y + s.height / 2)
    CGWarpMouseCursorPosition(centre)
    Thread.sleep(forTimeInterval: 0.05)
    for _ in 0..<3 {
        let e = CGEvent(scrollWheelEvent2Source: nil, units: .line, wheelCount: 1, wheel1: -1, wheel2: 0, wheel3: 0)!
        e.location = centre; e.post(tap: .cghidEventTap); Thread.sleep(forTimeInterval: 0.02)
    }
    let from = CGPoint(x: p.x + x, y: p.y + y0), to = CGPoint(x: p.x + x, y: p.y + y1)
    CGWarpMouseCursorPosition(from)
    Thread.sleep(forTimeInterval: 0.15)
    CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: from, mouseButton: .left)?.post(tap: .cghidEventTap)
    for i in 1...20 {
        let q = CGPoint(x: from.x, y: from.y + (to.y - from.y) * Double(i) / 20)
        CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: q, mouseButton: .left)?.post(tap: .cghidEventTap)
        Thread.sleep(forTimeInterval: 0.03)
    }
    Thread.sleep(forTimeInterval: 0.6) // the jump refresh fills the bubble's date
    capture(target, s, args[6])
    CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: to, mouseButton: .left)?.post(tap: .cghidEventTap)
    CGWarpMouseCursorPosition(home)
    CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: home, mouseButton: .left)?.post(tap: .cghidEventTap)
case "set":
    guard args.count >= 5 else { fail("set <proc> <label> <text>") }
    guard let field = find(target, args[3]).first else { fail("\(args[2]): nothing labelled \(args[3])") }
    let text = args[4]
    if AXUIElementSetAttributeValue(field, kAXValueAttribute as CFString, text as CFString) != .success
        || (attr(field, kAXValueAttribute) as? String) != text {
        AXUIElementSetAttributeValue(field, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        AXUIElementPerformAction(field, kAXPressAction as CFString)
        Thread.sleep(forTimeInterval: 0.2)
        for ch in text.utf16 {
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)!
                var c = ch
                e.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c)
                e.post(tap: .cghidEventTap)
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
    }
case "pressafter":
    guard args.count >= 5, let k = Int(args[4]) else { fail("pressafter <proc> <label> <k>") }
    var seen = -1
    var hit: AXUIElement?
    _ = walk(mainWindow(target)) { e, _ in
        if seen < 0 { if matches(e, args[3]) { seen = 0 }; return true }
        seen += 1
        if seen == k { hit = e; return false }
        return true
    }
    guard let hit else { fail("\(args[2]): no element \(k) after \(args[3])") }
    let r = AXUIElementPerformAction(hit, kAXPressAction as CFString)
    if r != .success { fail("\(args[2]): AXPress on element \(k) after \(args[3]) failed (\(r.rawValue))") }
case "dump":
    _ = walk(mainWindow(target)) { e, depth in
        let role = text(e, kAXRoleAttribute) ?? "?"
        print(String(repeating: "  ", count: depth) + role + "  " + labels(e).joined(separator: " | "))
        return true
    }
case "has":
    guard args.count >= 4 else { fail("has <proc> <label>") }
    exit(find(target, args[3]).isEmpty ? 1 : 0)
case "wait":
    guard args.count >= 4 else { fail("wait <proc> <label> [seconds]") }
    let deadline = Date().addingTimeInterval(args.count >= 5 ? Double(args[4]) ?? 20 : 20)
    while Date() < deadline {
        if !find(target, args[3]).isEmpty { exit(0) }
        Thread.sleep(forTimeInterval: 0.25)
    }
    fail("\(args[2]): nothing labelled \(args[3]) within the timeout")
case "press":
    guard args.count >= 4 else { fail("press <proc> <label> [nth]") }
    let nth = args.count >= 5 ? Int(args[4]) ?? 1 : 1
    let up = args.count >= 6 ? Int(args[5]) ?? 0 : 0
    let hits = find(target, args[3])
    guard hits.count >= nth else { fail("\(args[2]): \(hits.count) element(s) labelled \(args[3]), wanted #\(nth)") }
    var hit = hits[nth - 1]
    for _ in 0..<up {
        guard let p = attr(hit, kAXParentAttribute) else { fail("\(args[3]) has no ancestor \(up) up") }
        hit = p as! AXUIElement
    }
    let r = AXUIElementPerformAction(hit, kAXPressAction as CFString)
    if r != .success { fail("\(args[2]): AXPress on \(args[3]) failed (\(r.rawValue))") }
default:
    fail("unknown command \(args[1])")
}
