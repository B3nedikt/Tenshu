#if DEV_HARNESS
import Foundation
import TenshuSdk

/// A file a headless run can read to prove a cross-app open was decoded
/// (`control-cross-app-open.md` §5i).
///
/// ⚠⚠ **This exists because neither log channel is reliable enough to verify against.** `NSLog`
/// did not surface under `log show --predicate` for this app, and `simctl launch --console-pty`
/// captured nothing from the shipping configuration — so five simulator runs produced no evidence
/// either way, and "the feature is broken" and "the log did not arrive" were indistinguishable. A
/// file in the app's own container is unambiguous: `simctl get_app_container` names the path, and
/// its presence and contents are readable after the fact with no attach, no predicate and no
/// timing window.
///
/// ⚠ `DEV_HARNESS` only. Recording what the user opened is not something a shipping build should
/// write to disk, and the receipt has no purpose outside a headless check.
enum OpenTargetReceipt {

    /// ⚠ Appends rather than overwrites, so a run that delivers twice is distinguishable from a
    /// run that delivers once — the cold-then-warm sequence depends on telling those apart.
    static func write(_ target: OpenedTarget) {
        append("open type=\(target.type) key=\(target.key) parts=\(target.parts.joined(separator: ","))\n")
    }

    /// Records that a URL reached the handler at all, decoded or not — see `handleOpenTarget`.
    static func sawUrl(_ url: String, decoded: Bool) {
        append("saw decoded=\(decoded) url=\(url)\n")
    }

    /// A free-form line — used to record that a lifecycle callback ran at all.
    static func note(_ text: String) {
        append("note \(text)\n")
    }

    /// Proves the receipt path works, from a call site that definitely runs. See `-openTargetSelfTest`.
    static func selfTest() {
        append("selftest ok\n")
    }

    private static func append(_ line: String) {
        guard let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        else { return }
        let url = dir.appendingPathComponent(fileName)
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            handle.write(Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }

    static let fileName = "tenshu-open-target.txt"
}
#endif
