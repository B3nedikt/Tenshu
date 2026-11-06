import AppKit
import Foundation

private let hostBundleIdentifier = "app.tenshu.control.ios"

private func containingHostAppURL() -> URL? {
    let executable = Bundle.main.executableURL
        ?? URL(fileURLWithPath: CommandLine.arguments[0])
    var candidate = executable
    for _ in 0 ..< 8 {
        candidate = candidate.deletingLastPathComponent()
        guard candidate.pathExtension == "app" else { continue }
        if candidate.lastPathComponent != "TenshuControlLoginItem.app" {
            return candidate
        }
    }
    return nil
}

let command = CommandLine.arguments.dropFirst().first
guard command == nil else {
    fputs("usage: TenshuControlLoginItem\n", stderr)
    exit(2)
}

NSApplication.shared.setActivationPolicy(.accessory)
NSApplication.shared.finishLaunching()

let configuration = NSWorkspace.OpenConfiguration()
configuration.activates = false
configuration.environment = [
    "TENSHU_CONTROL_START_HIDDEN": "true"
]

guard let host = containingHostAppURL() else {
    fputs("Containing Control.app not found\n", stderr)
    exit(1)
}

NSWorkspace.shared.openApplication(at: host, configuration: configuration) { _, _ in
    NSApplication.shared.terminate(nil)
}
NSApplication.shared.run()
