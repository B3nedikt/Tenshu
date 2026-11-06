// Swift client for the Control daemon's JSON native bridge
// (docs/proposals/desktop-macos-integration.md §2.2, docs/protocol/desktop-ipc.md §6.2).
//
// Copy of control-desktop/macos/TrayAgent/BridgeClient.swift — the
// File Provider .appex is a separate Xcode target and can't share a source file
// without a shared framework, so the bridge seam is duplicated (same trade-off
// as Phase-0 HelloClient vs the tray's client). Keep the two in sync; the
// canonical shape is the proposal's §2.2. Typed file-read wrappers live in
// FileBridge.swift.

import Foundation
import Security
#if canImport(Glibc)
import Glibc
#else
import Darwin
#endif

struct BridgeError: Error, CustomStringConvertible {
    let message: String
    /// The daemon's error code (`not_found`, `already_exists`, `unavailable`, …); nil for a
    /// failure on this side of the socket. Decisions key on this, never on the message text.
    var code: String? = nil
    var description: String { message }
}

final class BridgeClient {
    private let socketPath: String

    init(socketPath: String = BridgeClient.defaultSocketPath()) {
        self.socketPath = socketPath
    }

    /// The directory hosting the daemon's sockets. Mirrors core-common's SocketPathResolver:
    /// $TENSHU_IPC_DIR → group container ($TENSHU_TEAM_ID, else the Team ID this binary is
    /// signed with) → ~/.tenshu.
    ///
    /// ⚠ In the sandboxed .appex only the middle arm can work: there is no environment, and
    /// `~/.tenshu` resolves inside the extension's own container.
    static func defaultSocketDir() -> String {
        let env = ProcessInfo.processInfo.environment
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        if let dir = env["TENSHU_IPC_DIR"], !dir.isEmpty {
            return dir
        }
        let envTeam = env["TENSHU_TEAM_ID"].flatMap { $0.isEmpty ? nil : $0 }
        if let teamId = envTeam ?? signingTeamId(), let dir = groupContainer(teamId: teamId) {
            return dir
        }
        return "\(home)/.tenshu"
    }

    /// The Team ID this process was signed with, read off its own code signature — nil for
    /// an ad-hoc or unsigned build.
    ///
    /// This is how a signed native client finds the App Group container with nothing handed
    /// to it: the JVM apps get the same value pinned into their launcher cfg at signing time
    /// (gradle/desktop-app-signing.gradle.kts), so "signed by team T" means "uses T's
    /// container" on both sides. A sandboxed extension has no environment to read and a
    /// launchd-started agent inherits none, so the signature is the one source both have.
    static func signingTeamId() -> String? {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code = code else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess,
              let staticCode = staticCode else { return nil }
        var info: CFDictionary?
        let flags = SecCSFlags(rawValue: kSecCSSigningInformation)
        guard SecCodeCopySigningInformation(staticCode, flags, &info) == errSecSuccess,
              let dict = info as? [String: Any] else { return nil }
        return dict[kSecCodeInfoTeamIdentifier as String] as? String
    }

    /// The App Group container for `teamId`. Asked of the system rather than built from the
    /// home directory: inside a sandbox `homeDirectoryForCurrentUser` is the extension's own
    /// container, so a hand-built `~/Library/Group Containers/…` points somewhere that does
    /// not exist.
    static func groupContainer(teamId: String) -> String? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "\(teamId).app.tenshu.shared")?
            .path
    }

    static func defaultSocketPath() -> String {
        "\(defaultSocketDir())/bridge.sock"
    }

    /// Whether the daemon is reachable (a successful `ping`). `retrying: false` asks once,
    /// without the connect patience — for a caller that will simply ask again later.
    func isDaemonReachable(retrying: Bool = true) -> Bool {
        (try? call("ping", retryingConnect: retrying)) != nil
    }

    /// Sends `method` with `params`, returns the parsed `result` object.
    /// Throws `BridgeError` on connect failure or an `error` reply.
    @discardableResult
    func call(_ method: String, params: [String: Any] = [:], retryingConnect: Bool = true) throws -> [String: Any] {
        let fd = try connect(retrying: retryingConnect)
        defer { close(fd) }

        let request: [String: Any] = [
            "v": 1,
            "id": UUID().uuidString,
            "method": method,
            "params": params,
        ]
        let payload = try JSONSerialization.data(withJSONObject: request)
        try writeFrame(fd, payload)

        let responseData = try readFrame(fd)
        guard let obj = try JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
            throw BridgeError(message: "malformed bridge response")
        }
        if let err = obj["error"] as? [String: Any] {
            throw BridgeError(
                message: (err["message"] as? String) ?? "bridge error",
                code: err["code"] as? String)
        }
        return (obj["result"] as? [String: Any]) ?? [:]
    }

    // MARK: - Socket

    /// Waits between connection attempts when nothing is listening yet (≈7 s in all).
    ///
    /// ⚠⚠ Why this exists: answering the system "server unreachable" is expensive. It stops
    /// asking for minutes (measured 2026-10-05: three failed calls a minute after Control
    /// launched, then nothing for four), so a Control that is merely still starting — or being
    /// restarted by an update — turns into a Finder that looks dead long after Control is up.
    /// A few seconds of patience here is cheaper than that backoff.
    static let connectRetryDelays: [TimeInterval] = [0.2, 0.4, 0.8, 1.6, 2.0, 2.0]

    private func connect(retrying: Bool) throws -> Int32 {
        var failure: Int32 = 0
        for delay in [0] + (retrying ? Self.connectRetryDelays : []) {
            if delay > 0 { Thread.sleep(forTimeInterval: delay) }
            switch connectOnce() {
            case .success(let fd):
                return fd
            case .failure(let error):
                failure = error.code
                // Only "nobody is listening" is worth waiting out: a refused connection or a
                // socket file that is not there yet. Anything else will not change in seconds.
                if failure != ECONNREFUSED && failure != ENOENT { break }
                continue
            }
            break
        }
        // The errno is the whole diagnosis: ENOENT = Control is not running (it removes its
        // sockets on quit), ECONNREFUSED = a leftover file or a full backlog, EPERM/EACCES =
        // the sandbox said no. Without it all three read "not reachable".
        throw BridgeError(message:
            "Control daemon not reachable at \(socketPath) (errno \(failure): \(String(cString: strerror(failure))))")
    }

    private struct ConnectFailure: Error { let code: Int32 }

    private func connectOnce() -> Result<Int32, ConnectFailure> {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return .failure(ConnectFailure(code: errno)) }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let cap = MemoryLayout.size(ofValue: addr.sun_path)
        if socketPath.utf8.count >= cap {
            close(fd)
            return .failure(ConnectFailure(code: ENAMETOOLONG))
        }
        _ = socketPath.withCString { src in
            withUnsafeMutablePointer(to: &addr.sun_path) { dst in
                dst.withMemoryRebound(to: CChar.self, capacity: cap) { strcpy($0, src) }
            }
        }
        let rc = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard rc == 0 else {
            let code = errno
            close(fd)
            return .failure(ConnectFailure(code: code))
        }
        return .success(fd)
    }

    private let typeResponse: UInt8 = 0

    private func writeFrame(_ fd: Int32, _ payload: Data) throws {
        let n = UInt32(payload.count)
        var frame: [UInt8] = [
            UInt8((n >> 24) & 0xff), UInt8((n >> 16) & 0xff),
            UInt8((n >> 8) & 0xff), UInt8(n & 0xff),
            typeResponse,
        ]
        frame.append(contentsOf: payload)
        try frame.withUnsafeBytes { raw in
            var off = 0
            while off < raw.count {
                let w = write(fd, raw.baseAddress!.advanced(by: off), raw.count - off)
                if w <= 0 { throw BridgeError(message: "socket write failed") }
                off += w
            }
        }
    }

    private func readN(_ fd: Int32, _ count: Int) throws -> [UInt8] {
        if count == 0 { return [] }
        var buf = [UInt8](repeating: 0, count: count)
        var off = 0
        try buf.withUnsafeMutableBytes { raw in
            while off < count {
                let r = read(fd, raw.baseAddress!.advanced(by: off), count - off)
                if r <= 0 { throw BridgeError(message: "socket closed mid-frame") }
                off += r
            }
        }
        return buf
    }

    private func readFrame(_ fd: Int32) throws -> Data {
        let header = try readN(fd, 4)
        let length = (Int(header[0]) << 24) | (Int(header[1]) << 16)
            | (Int(header[2]) << 8) | Int(header[3])
        _ = try readN(fd, 1) // type byte (ignored)
        return Data(try readN(fd, length))
    }
}
