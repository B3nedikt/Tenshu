// appgroup-claim — asks macOS for the App Group container, so the app that launched it may use it.
//
// Usage: appgroup-claim <group-identifier>
// Prints the container path and exits 0; exits 1 when macOS returns no container.
//
// Why a helper (docs/tech-debt.md "A packaged desktop app is refused its own App Group container"):
// macOS refuses a development-signed app access to `~/Library/Group Containers/<group>` until the
// app has asked for the container through `containerURL(forSecurityApplicationGroupIdentifier:)`.
// The refusal is silent, with no consent dialog. The JVM opens the path directly and never makes
// that call. Measured 2026-10-29: the grant goes to the process that LAUNCHED the caller, so the
// app runs this helper once at startup, before it touches the container.
// Eight of the ten apps bundle Java 21, which cannot reach Foundation in-process (FFM is final
// only in 22), which is why this is a helper and not an FFM call.
import Foundation

guard CommandLine.arguments.count == 2 else {
    FileHandle.standardError.write("usage: appgroup-claim <group-identifier>\n".data(using: .utf8)!)
    exit(2)
}
let group = CommandLine.arguments[1]
guard let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
    FileHandle.standardError.write("no container for \(group)\n".data(using: .utf8)!)
    exit(1)
}
print(url.path)
