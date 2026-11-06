// Tenshu macOS native-bridge de-risk client (Phase 0).
//
// docs/proposals/desktop-macos-integration.md §3 task 4: the smallest possible
// native client that connects to the daemon's group-container Unix socket,
// speaks the JSON bridge envelope, and round-trips `ping` + `getSyncState`.
// If this prints a successful reply against a running Control daemon, the
// reachability + protocol assumptions hold and the rest of the macOS work is
// construction.
//
// Build:  swiftc -O -o hello-client main.swift
// Run:    ./hello-client                 # resolves the dev socket (~/.tenshu/bridge.sock)
//         ./hello-client /path/to.sock   # explicit socket path
//
// Socket resolution mirrors core-common's SocketPathResolver:
//   1. explicit argv[1]
//   2. $TENSHU_TEAM_ID set  → group container bridge.sock (sandboxed/signed)
//   3. $TENSHU_IPC_DIR set  → <dir>/bridge.sock
//   4. otherwise            → ~/.tenshu/bridge.sock  (unsigned dev default)

import Foundation
#if canImport(Glibc)
import Glibc
#else
import Darwin
#endif

// MARK: - Socket path

func defaultSocketPath() -> String {
    let env = ProcessInfo.processInfo.environment
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    if let teamId = env["TENSHU_TEAM_ID"], !teamId.isEmpty {
        return "\(home)/Library/Group Containers/\(teamId).app.tenshu.shared/bridge.sock"
    }
    if let dir = env["TENSHU_IPC_DIR"], !dir.isEmpty {
        return "\(dir)/bridge.sock"
    }
    return "\(home)/.tenshu/bridge.sock"
}

let socketPath = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : defaultSocketPath()

// MARK: - Connect

let fd = socket(AF_UNIX, SOCK_STREAM, 0)
guard fd >= 0 else { perror("socket"); exit(1) }
defer { close(fd) }

var addr = sockaddr_un()
addr.sun_family = sa_family_t(AF_UNIX)
let pathCap = MemoryLayout.size(ofValue: addr.sun_path)
if socketPath.utf8.count >= pathCap {
    FileHandle.standardError.write(Data("socket path too long: \(socketPath)\n".utf8))
    exit(1)
}
_ = socketPath.withCString { src in
    withUnsafeMutablePointer(to: &addr.sun_path) { dst in
        dst.withMemoryRebound(to: CChar.self, capacity: pathCap) { strcpy($0, src) }
    }
}

let connectRC = withUnsafePointer(to: &addr) { ptr in
    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
        connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
    }
}
guard connectRC == 0 else {
    perror("connect (is Control running? socket: \(socketPath))")
    exit(1)
}

// MARK: - Framing  ( [4-byte BE length][1-byte type][payload] — StreamFramer )

let TYPE_RESPONSE: UInt8 = 0

func writeFrame(_ jsonString: String) {
    let payload = Array(jsonString.utf8)
    let n = UInt32(payload.count)
    var frame: [UInt8] = [
        UInt8((n >> 24) & 0xff), UInt8((n >> 16) & 0xff),
        UInt8((n >> 8) & 0xff), UInt8(n & 0xff),
        TYPE_RESPONSE,
    ]
    frame.append(contentsOf: payload)
    frame.withUnsafeBytes { _ = write(fd, $0.baseAddress, frame.count) }
}

func readN(_ count: Int) -> [UInt8]? {
    guard count > 0 else { return [] }
    var buf = [UInt8](repeating: 0, count: count)
    var off = 0
    while off < count {
        let r = buf.withUnsafeMutableBytes { read(fd, $0.baseAddress!.advanced(by: off), count - off) }
        if r <= 0 { return nil }
        off += r
    }
    return buf
}

func readFrame() -> String? {
    guard let header = readN(4) else { return nil }
    let length = (Int(header[0]) << 24) | (Int(header[1]) << 16)
        | (Int(header[2]) << 8) | Int(header[3])
    guard readN(1) != nil else { return nil }          // type byte (ignored)
    guard let payload = readN(length) else { return nil }
    return String(decoding: payload, as: UTF8.self)
}

func call(_ id: String, _ method: String, _ params: String = "{}") -> String {
    writeFrame(#"{"v":1,"id":"\#(id)","method":"\#(method)","params":\#(params)}"#)
    return readFrame() ?? "<no reply>"
}

// MARK: - Round-trip

print("→ connected to \(socketPath)")
print("ping         → \(call("1", "ping", #"{"message":"hello from swift"}"#))")
print("getSyncState → \(call("2", "getSyncState"))")
