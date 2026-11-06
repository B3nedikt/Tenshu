// NSFileProviderItem mapping for a Tenshu file/folder
// (docs/proposals/desktop-macos-integration.md §5 task 4).

import CryptoKit
import FileProvider
import Foundation
import UniformTypeIdentifiers

final class TenshuFileProviderItem: NSObject, NSFileProviderItem {

    private let file: BridgeFile

    init(_ file: BridgeFile) {
        self.file = file
    }

    /// Synthetic root for the realm — `item(for: .rootContainer)` returns this.
    static var root: TenshuFileProviderItem {
        TenshuFileProviderItem(BridgeFile([
            "id": TenshuId.root, "parentId": TenshuId.root,
            "name": "Tenshu Drive", "isFolder": true, "size": 0,
        ])!)
    }

    var itemIdentifier: NSFileProviderItemIdentifier { TenshuId.identifier(for: file.id) }

    var parentItemIdentifier: NSFileProviderItemIdentifier { TenshuId.identifier(for: file.parentId) }

    var filename: String { file.name }

    var contentType: UTType {
        if file.isFolder { return .folder }
        let ext = (file.name as NSString).pathExtension
        return UTType(filenameExtension: ext) ?? .data
    }

    var capabilities: NSFileProviderItemCapabilities {
        let shared: NSFileProviderItemCapabilities =
            [.allowsReading, .allowsRenaming, .allowsReparenting, .allowsDeleting]
        return file.isFolder
            ? shared.union([.allowsContentEnumerating, .allowsAddingSubItems])
            // ⚠ `.allowsEvicting` is what lets "Remove Download" (and the system, under disk
            // pressure) turn a downloaded file back into an online-only one. Without it every
            // file opened once stays on disk for good — measured: eviction refused with -2008.
            // (Deprecated since macOS 13 in favour of `contentPolicy`, and still what the system
            // honoured on macOS 26 — eviction worked with this flag and not without it.)
            : shared.union([.allowsWriting, .allowsEvicting])
    }

    var documentSize: NSNumber? {
        file.isFolder ? nil : NSNumber(value: file.size)
    }

    var creationDate: Date? { file.createdAtMillis.map(Self.date) }

    var contentModificationDate: Date? { file.modifiedAtMillis.map(Self.date) }

    /// The system compares these to decide whether to re-download and whether an edit raced
    /// a remote change. Content tracks the daemon's content version (it moves exactly when
    /// the bytes do). Metadata covers everything else Finder shows — kind, where the item is,
    /// what it is called, its dates, its size — because the system refreshes a dataless item's
    /// attributes only when this value changes. NOT the identifier, which is stable.
    ///
    /// ⚠ Both are DIGESTS. The system caps each at 128 bytes, and a parent id plus a file name
    /// alone can exceed that; only equality is ever compared, so nothing is lost by hashing.
    var itemVersion: NSFileProviderItemVersion {
        let metadata = [
            Self.presentationRevision,
            file.isFolder ? "d" : "f", file.parentId, file.name,
            // "-" for absent: nil and the Unix epoch are different dates to Finder.
            file.createdAtMillis.map(String.init) ?? "-",
            file.modifiedAtMillis.map(String.init) ?? "-",
            String(file.size),
        ].joined(separator: "\u{0}")
        return NSFileProviderItemVersion(
            contentVersion: Self.digest(file.contentVersion),
            metadataVersion: Self.digest(metadata)
        )
    }

    /// Bump when THIS FILE starts presenting an unchanged item differently — a new capability,
    /// a different content type. The system keeps what it was told until the metadata version
    /// moves, so without this a change here reaches only items that happen to change anyway
    /// (measured: files listed before `.allowsEvicting` was added stayed un-evictable).
    private static let presentationRevision = "2"

    private static func digest(_ text: String) -> Data {
        Data(SHA256.hash(data: Data(text.utf8)))
    }

    private static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
    }
}
