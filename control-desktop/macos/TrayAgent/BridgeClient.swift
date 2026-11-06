// Swift client for the Control daemon's JSON native bridge
// (docs/proposals/desktop-macos-integration.md §2.2, docs/protocol/desktop-ipc.md §6.2).
//
// Generalises the Phase-0 HelloClient: one synchronous request/response per
// call over the group-container Unix socket, framed `[4-byte BE length][1-byte
// type][JSON]`. Callers run it off the main thread and hop back for UI.

import Foundation
import Security
#if canImport(Glibc)
import Glibc
#else
import Darwin
#endif

struct BridgeError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

final class BridgeClient {
    private let socketPath: String

    init(socketPath: String = BridgeClient.defaultSocketPath()) {
        self.socketPath = socketPath
    }

    /// The directory hosting the daemon's sockets — and everything that must sit beside
    /// them, such as the tray's heartbeat (`TrayHeartbeat`).
    ///
    /// Mirrors core-common's SocketPathResolver:
    /// $TENSHU_IPC_DIR → group container ($TENSHU_TEAM_ID, else the Team ID this binary is
    /// signed with) → ~/.tenshu.
    ///
    /// ⚠ Control now passes `TENSHU_IPC_DIR` explicitly when it spawns the tray, so in a
    /// packaged launch the first arm is the one that fires. The two sides resolve this
    /// independently — system properties *and* env on the JVM side, env only here — and
    /// agreed until now only because neither was configured; the explicit hand-off removes
    /// that drift rather than documenting it.
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

    /// Whether the daemon is reachable (a successful `ping`).
    func isDaemonReachable() -> Bool {
        (try? call("ping")) != nil
    }

    /// Sends `method` with `params`, returns the parsed `result` object.
    /// Throws `BridgeError` on connect failure or an `error` reply.
    @discardableResult
    func call(_ method: String, params: [String: Any] = [:]) throws -> [String: Any] {
        let fd = try connect()
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
            throw BridgeError(message: (err["message"] as? String) ?? "bridge error")
        }
        return (obj["result"] as? [String: Any]) ?? [:]
    }

    // MARK: - Socket

    private func connect() throws -> Int32 {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw BridgeError(message: "socket() failed") }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let cap = MemoryLayout.size(ofValue: addr.sun_path)
        if socketPath.utf8.count >= cap {
            close(fd); throw BridgeError(message: "socket path too long")
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
            close(fd)
            throw BridgeError(message: "Control daemon not reachable at \(socketPath)")
        }
        return fd
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
