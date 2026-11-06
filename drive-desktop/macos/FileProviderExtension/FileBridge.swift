// Typed wrappers over the JSON native bridge for the File Provider
// (docs/proposals/desktop-macos-integration.md §5 / §5a). The daemon-side methods are
// control-shared's `registerFileMethods` / `registerFileWriteMethods` (ControlFileBridge.kt).
//
// Every item is addressed by an opaque IDENTIFIER the daemon mints and keeps stable across
// renames and moves — never by its realm path, which changes with both. The realm root is
// "root" and maps to NSFileProviderItemIdentifier.rootContainer.

import FileProvider
import Foundation

/// One file/folder as shaped by the bridge.
struct BridgeFile {
    let id: String
    let parentId: String
    let name: String
    let isFolder: Bool
    let size: Int64
    let createdAtMillis: Int64?
    let modifiedAtMillis: Int64?
    let category: String?        // stable code: "image"/"audio"/… (ADR 0016), nil for folders
    let contentVersion: String   // changes exactly when the bytes do; "" for folders

    init?(_ dict: [String: Any]) {
        guard let id = dict["id"] as? String, let name = dict["name"] as? String else { return nil }
        self.id = id
        self.parentId = (dict["parentId"] as? String) ?? TenshuId.root
        self.name = name
        self.isFolder = (dict["isFolder"] as? Bool) ?? false
        self.size = (dict["size"] as? NSNumber)?.int64Value ?? 0
        self.createdAtMillis = (dict["created"] as? NSNumber)?.int64Value
        self.modifiedAtMillis = (dict["modified"] as? NSNumber)?.int64Value
        self.category = dict["category"] as? String
        self.contentVersion = (dict["contentVersion"] as? String) ?? ""
    }
}

/// What changed since an anchor. `nil` from `changesSince` means the anchor expired.
struct BridgeChanges {
    let anchor: String
    let updated: [BridgeFile]
    let deleted: [String]
}

extension BridgeClient {
    /// Children of the folder `id`.
    func listFolder(_ id: String) throws -> [BridgeFile] {
        items(try call(TenshuBridge.method("listFolder"), params: ["id": id]))
    }

    /// Every item in the realm, with the change-feed anchor for exactly that listing.
    func listAll() throws -> (items: [BridgeFile], anchor: String) {
        let result = try call(TenshuBridge.method("listAll"))
        return (items(result), (result["anchor"] as? String) ?? "")
    }

    /// The change-feed anchor for the realm as it is now.
    func currentAnchor() throws -> String {
        guard let anchor = try call(TenshuBridge.method("currentAnchor"))["anchor"] as? String else {
            throw BridgeError(message: "currentAnchor: missing anchor")
        }
        return anchor
    }

    /// Changes since `anchor`, or `nil` when the daemon can no longer answer it and the
    /// caller must re-list (a Control restart, or an anchor older than its history).
    func changesSince(_ anchor: String) throws -> BridgeChanges? {
        let result = try call(TenshuBridge.method("changesSince"), params: ["anchor": anchor])
        if (result["expired"] as? Bool) ?? true { return nil }
        return BridgeChanges(
            anchor: (result["anchor"] as? String) ?? anchor,
            updated: ((result["updated"] as? [[String: Any]]) ?? []).compactMap(BridgeFile.init),
            deleted: (result["deleted"] as? [String]) ?? []
        )
    }

    /// Metadata for the single item `id`.
    func itemMetadata(_ id: String) throws -> BridgeFile {
        try item(call(TenshuBridge.method("itemMetadata"), params: ["id": id]), id)
    }

    /// Raw bytes for the file `id` (base64-decoded from the JSON reply).
    func readContents(_ id: String) throws -> Data {
        let result = try call(TenshuBridge.method("readContents"), params: ["id": id])
        guard let b64 = result["data"] as? String, let bytes = Data(base64Encoded: b64) else {
            throw BridgeError(message: "readContents: missing/invalid data for '\(id)'")
        }
        return bytes
    }

    /// A stored thumbnail for `id`, or `nil` when the realm has none. Never the file's own
    /// bytes: the daemon reads the synced thumbnail classes, so the item stays dataless.
    func thumbnail(_ id: String, size: CGSize) throws -> Data? {
        let result = try call(TenshuBridge.method("thumbnail"), params: [
            "id": id,
            "width": max(1, Int(size.width.rounded(.up))),
            "height": max(1, Int(size.height.rounded(.up))),
        ])
        guard (result["available"] as? Bool) ?? false else { return nil }
        guard let b64 = result["data"] as? String, let bytes = Data(base64Encoded: b64) else {
            throw BridgeError(message: "thumbnail: missing/invalid data for '\(id)'")
        }
        return bytes
    }

    // MARK: - Contents by hand-off (proposal §5b)
    //
    // File contents do not travel through the bridge. They cross as a FILE in the staging
    // directory both processes can reach, and only its name is sent — so a large video is
    // never held in memory, base64-encoded, or stopped by the bridge's frame limit.

    /// Asks the daemon to write the contents of `id` into the staging file `staged`.
    /// Returns the byte count; the file is ours to move away afterwards.
    func fetchContents(_ id: String, staged: String) throws -> Int64 {
        let result = try call(TenshuBridge.method("fetchContents"), params: ["id": id, "staged": staged])
        return (result["size"] as? NSNumber)?.int64Value ?? 0
    }

    /// Stops the `fetchContents` writing to `staged`. Sent on its own connection: the call
    /// being cancelled is blocked waiting for its reply.
    func cancelTransfer(staged: String) {
        _ = try? call(TenshuBridge.method("cancelTransfer"), params: ["staged": staged])
    }

    /// Creates a file from the contents staged as `staged`; the daemon consumes the file.
    /// `modified` is the file's own modification date, when the system's template carried one,
    /// so a file dragged in keeps its date instead of taking the moment of the copy.
    func createFile(parentId: String, name: String, staged: String, modified: Date?) throws -> BridgeFile {
        var params = ["parentId": parentId, "name": name, "staged": staged]
        if let modified {
            params["modified"] = String(Int64((modified.timeIntervalSince1970 * 1000).rounded()))
        }
        return try item(call(TenshuBridge.method("createFileFrom"), params: params), name)
    }

    /// Overwrites `id` with the contents staged as `staged`; the daemon consumes the file.
    func modifyContents(_ id: String, staged: String) throws -> BridgeFile {
        try item(call(TenshuBridge.method("modifyContentsFrom"), params: ["id": id, "staged": staged]), id)
    }

    // MARK: - Write

    func createFolder(parentId: String, name: String) throws -> BridgeFile {
        try item(call(TenshuBridge.method("createFolder"), params: ["parentId": parentId, "name": name]), name)
    }

    func createFile(parentId: String, name: String, _ data: Data) throws -> BridgeFile {
        try item(call(TenshuBridge.method("createFile"), params: [
            "parentId": parentId, "name": name, "data": data.base64EncodedString(),
        ]), name)
    }

    func modifyContents(_ id: String, _ data: Data) throws -> BridgeFile {
        try item(call(TenshuBridge.method("modifyContents"), params: ["id": id, "data": data.base64EncodedString()]), id)
    }

    /// Renames and/or moves `id`; the item keeps its identifier.
    func moveItem(_ id: String, parentId: String, name: String) throws -> BridgeFile {
        try item(call(TenshuBridge.method("moveItem"), params: ["id": id, "parentId": parentId, "name": name]), id)
    }

    func deleteItem(_ id: String) throws {
        _ = try call(TenshuBridge.method("deleteItem"), params: ["id": id])
    }

    private func items(_ result: [String: Any]) -> [BridgeFile] {
        ((result["items"] as? [[String: Any]]) ?? []).compactMap(BridgeFile.init)
    }

    private func item(_ result: [String: Any], _ what: String) throws -> BridgeFile {
        guard let dict = result["item"] as? [String: Any], let item = BridgeFile(dict) else {
            throw BridgeError(message: "malformed item for '\(what)'")
        }
        return item
    }
}

enum TenshuBridge {
    /// The File Provider methods are versioned in their NAME (`ControlFileBridge.kt`,
    /// FILE_PROVIDER_METHOD_PREFIX): Control and Drive install separately, and a daemon that
    /// does not speak this version must fail every call cleanly rather than half-answer.
    static func method(_ name: String) -> String { "fileProvider.v2.\(name)" }
}

/// The directory contents are handed over in (`FileProviderStaging.kt` on the daemon side).
///
/// ⚠ In the App Group container, beside the sockets: the one place the sandboxed extension and
/// the unsandboxed daemon can both read and write. Decrypted bytes sit here only between one
/// side writing and the other collecting (threat model T-C-16).
enum TenshuStaging {
    static var directory: URL {
        URL(fileURLWithPath: BridgeClient.defaultSocketDir()).appendingPathComponent("fileprovider-staging")
    }

    /// A fresh token. The daemon accepts only names of this shape, never a path.
    static func newName() -> String { UUID().uuidString }

    static func url(for name: String) -> URL { directory.appendingPathComponent(name) }

    /// Stages a copy of `source` for an upload and returns its name. `nil` stages an empty file.
    /// On APFS the copy is a clone, so this costs no extra disk for the moment it exists.
    static func stage(_ source: URL?) throws -> String {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = newName()
        if let source = source {
            try FileManager.default.copyItem(at: source, to: url(for: name))
        } else {
            try Data().write(to: url(for: name))
        }
        return name
    }

    static func discard(_ name: String) {
        try? FileManager.default.removeItem(at: url(for: name))
    }
}

// MARK: - Bridge id ⇄ File Provider identifier

enum TenshuId {
    /// The daemon's name for the realm root (`FileProviderIds.ROOT_ID`).
    static let root = "root"

    static func bridgeId(for identifier: NSFileProviderItemIdentifier) -> String {
        identifier == .rootContainer ? root : identifier.rawValue
    }

    static func identifier(for bridgeId: String) -> NSFileProviderItemIdentifier {
        bridgeId == root ? .rootContainer : NSFileProviderItemIdentifier(bridgeId)
    }
}
