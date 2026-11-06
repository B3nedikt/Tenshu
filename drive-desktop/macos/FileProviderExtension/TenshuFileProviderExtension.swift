// Tenshu Drive File Provider — replicated extension
// (docs/proposals/desktop-macos-integration.md §5, §5a, §6).
//
// A headless NSFileProviderReplicatedExtension that puts Tenshu Drive in Finder. It is a thin
// client of the Control daemon: every item/list/fetch/write is a JSON call over the
// group-container Unix socket (BridgeClient), which the daemon maps to file-service ops. No
// sync engine of its own; the relay still sees ciphertext only (plaintext exists transiently
// in this process, inside the local trust boundary — threat model A20 / T-C-16).

import FileProvider
import Foundation
import ImageIO
import UniformTypeIdentifiers

final class TenshuFileProviderExtension: NSObject, NSFileProviderReplicatedExtension, NSFileProviderThumbnailing {

    private let domain: NSFileProviderDomain
    private let bridge: BridgeClient
    private let manager: NSFileProviderManager?

    /// How often the working set is asked to look for changes while this process is alive.
    ///
    /// ⚠ A poll, because the bridge has no push channel, and a slow one, because each look
    /// makes the daemon walk the whole realm.
    ///
    /// ⚠⚠ The system starts this process when something uses the domain and kills it within a
    /// minute of the last use (measured 2026-10-04), so the timer alone would rarely fire.
    /// The FIRST signal is what carries the weight: it goes out as soon as the process starts —
    /// i.e. when the user opens the folder — and catches up on everything that changed while
    /// nothing was running. The repeat only matters while a Finder window keeps us alive.
    ///
    /// Only `.workingSet` is signalled, deliberately: for a replicated extension
    /// NSFileProviderManager.h states that other container identifiers are ignored and that
    /// the system propagates working-set changes to whatever the UI is showing.
    private static let changePollInterval: TimeInterval = 30
    private static let firstSignalDelay: TimeInterval = 1

    private var changePoll: DispatchSourceTimer?

    required init(domain: NSFileProviderDomain) {
        self.domain = domain
        self.bridge = BridgeClient()
        self.manager = NSFileProviderManager(for: domain)
        super.init()
        startChangePoll()
    }

    func invalidate() {
        changePoll?.cancel()
        changePoll = nil
    }

    private func startChangePoll() {
        guard let manager = manager else { return }
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + Self.firstSignalDelay, repeating: Self.changePollInterval)
        timer.setEventHandler { [bridge] in
            // ⚠⚠ Un-stick first. After a "server unreachable" answer the system stops asking —
            // for half an hour and more once failures pile up (measured: `next:` 38 minutes
            // out) — and nothing it does on its own notices that Control has since come up. So
            // while that answer is outstanding, look for Control here, and tell the system the
            // moment it is back; it then retries everything it had parked.
            //
            // ⚠ Only while THIS process lives. The system kills an idle extension within a
            // minute and, while backing off, does not start a new one — so an outage longer
            // than that is not recovered from here. Drive covers it at launch (`tenshu-domain
            // add` sends the same signal); with Drive closed, Finder waits out its backoff.
            if let reported = Unreachable.outstanding, bridge.isDaemonReachable(retrying: false) {
                // Cleared only once the system has accepted it: a failed signal leaves Finder
                // parked, so the next poll must try again. And only the report this ping
                // answered: Control can drop again before the completion runs, and clearing
                // that newer report would end the polling with Finder parked.
                manager.signalErrorResolved(NSFileProviderError(.serverUnreachable)) { error in
                    if error == nil { Unreachable.clear(reported) }
                }
            }
            manager.signalEnumerator(for: .workingSet) { _ in }
        }
        timer.resume()
        changePoll = timer
    }

    // MARK: - Metadata

    func item(
        for identifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        if identifier == .rootContainer {
            completionHandler(TenshuFileProviderItem.root, nil)
            progress.completedUnitCount = 1
            return progress
        }
        let id = TenshuId.bridgeId(for: identifier)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let item = TenshuFileProviderItem(try self.bridge.itemMetadata(id))
                completionHandler(item, nil)
            } catch {
                completionHandler(nil, FileProviderError.from(error))
            }
            progress.completedUnitCount = 1
        }
        return progress
    }

    // MARK: - Content fetch

    func fetchContents(
        for itemIdentifier: NSFileProviderItemIdentifier,
        version requestedVersion: NSFileProviderItemVersion?,
        request: NSFileProviderRequest,
        completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void
    ) -> Progress {
        // Indeterminate until the size is known; then it counts bytes, read off the staging
        // file as the daemon writes it.
        let progress = Progress(totalUnitCount: -1)
        let id = TenshuId.bridgeId(for: itemIdentifier)
        let staged = TenshuStaging.newName()
        let stagedURL = TenshuStaging.url(for: staged)
        // Finder's cancel reaches the daemon on a connection of its own; the fetch below is
        // blocked on its reply and returns `cancelled` when the daemon notices.
        progress.cancellationHandler = { BridgeClient().cancelTransfer(staged: staged) }

        let poll = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        poll.schedule(deadline: .now() + 0.5, repeating: 0.5)
        poll.setEventHandler {
            let written = (try? stagedURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if progress.totalUnitCount > 0 {
                progress.completedUnitCount = min(Int64(written), progress.totalUnitCount)
            }
        }
        poll.resume()

        DispatchQueue.global(qos: .userInitiated).async {
            defer { poll.cancel() }
            do {
                let file = try self.bridge.itemMetadata(id)
                let item = TenshuFileProviderItem(file)
                progress.totalUnitCount = max(file.size, 1)
                try FileManager.default.createDirectory(
                    at: TenshuStaging.directory, withIntermediateDirectories: true)
                _ = try self.bridge.fetchContents(id, staged: staged)
                // Out of the shared directory at once, into a location only this extension
                // owns; the system takes the file from there.
                let base = (try? self.manager?.temporaryDirectoryURL()) ?? FileManager.default.temporaryDirectory
                let url = base.appendingPathComponent(UUID().uuidString).appendingPathComponent(item.filename)
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try FileManager.default.moveItem(at: stagedURL, to: url)
                progress.completedUnitCount = progress.totalUnitCount
                completionHandler(url, item, nil)
            } catch {
                TenshuStaging.discard(staged)
                completionHandler(nil, nil, FileProviderError.from(error))
            }
        }
        return progress
    }

    // MARK: - Thumbnails
    //
    // From the realm's stored thumbnail classes, so a file that is not downloaded still has a
    // picture and asking for one downloads nothing. The system caches what this returns until
    // the item's content version moves.

    func fetchThumbnails(
        for itemIdentifiers: [NSFileProviderItemIdentifier],
        requestedSize size: CGSize,
        perThumbnailCompletionHandler: @escaping (NSFileProviderItemIdentifier, Data?, Error?) -> Void,
        completionHandler: @escaping (Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: Int64(itemIdentifiers.count))
        DispatchQueue.global(qos: .userInitiated).async {
            // One at a time: each is a daemon round-trip that may itself wait on the relay.
            for identifier in itemIdentifiers {
                // The items not reached are covered by the one error below.
                if progress.isCancelled {
                    completionHandler(NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
                    return
                }
                do {
                    let stored = try self.bridge.thumbnail(TenshuId.bridgeId(for: identifier), size: size)
                    // No thumbnail is (nil, nil), and so is one this Mac cannot decode.
                    perThumbnailCompletionHandler(identifier, stored.flatMap(Thumbnail.systemReadable), nil)
                } catch {
                    // This item only — the rest of the batch still gets its pictures.
                    perThumbnailCompletionHandler(identifier, nil, FileProviderError.from(error))
                }
                progress.completedUnitCount += 1
            }
            completionHandler(nil)
        }
        return progress
    }

    // MARK: - Enumeration

    func enumerator(
        for containerItemIdentifier: NSFileProviderItemIdentifier,
        request: NSFileProviderRequest
    ) throws -> NSFileProviderEnumerator {
        TenshuFileProviderEnumerator(containerIdentifier: containerItemIdentifier, bridge: bridge)
    }

    // MARK: - Write surface (§6)
    //
    // Each Finder edit maps to a write bridge method. Permission enforcement is proxy-side
    // and currently ungated (the rei/AppGrant track, §6 task 2).

    func createItem(
        basedOn itemTemplate: NSFileProviderItem,
        fields: NSFileProviderItemFields,
        contents url: URL?,
        options: NSFileProviderCreateItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let parentId = TenshuId.bridgeId(for: itemTemplate.parentItemIdentifier)
        let name = itemTemplate.filename
        let isFolder = itemTemplate.contentType == .folder
        // `contentModificationDate` is `Date??` on the protocol: optional requirement, optional
        // value. Only when the system says the template carries one.
        let modified: Date? = fields.contains(.contentModificationDate)
            ? (itemTemplate.contentModificationDate ?? nil)
            : nil
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let created: BridgeFile
                if isFolder {
                    created = try self.bridge.createFolder(parentId: parentId, name: name)
                } else {
                    // Staged, not read: the daemon consumes the file, so nothing here ever
                    // holds the contents in memory. It also removes the file; the discard
                    // covers a call that never reached it.
                    let staged = try TenshuStaging.stage(url)
                    defer { TenshuStaging.discard(staged) }
                    created = try self.bridge.createFile(
                        parentId: parentId, name: name, staged: staged, modified: modified
                    )
                }
                completionHandler(TenshuFileProviderItem(created), [], false, nil)
            } catch {
                completionHandler(nil, [], false, FileProviderError.from(error))
            }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func modifyItem(
        _ item: NSFileProviderItem,
        baseVersion version: NSFileProviderItemVersion,
        changedFields: NSFileProviderItemFields,
        contents newContents: URL?,
        options: NSFileProviderModifyItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let id = TenshuId.bridgeId(for: item.itemIdentifier)
        let parentId = TenshuId.bridgeId(for: item.parentItemIdentifier)
        let name = item.filename
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                var result: BridgeFile? = nil
                if changedFields.contains(.contents), let url = newContents {
                    let staged = try TenshuStaging.stage(url)
                    defer { TenshuStaging.discard(staged) }
                    result = try self.bridge.modifyContents(id, staged: staged)
                }
                // A rename and a move are one call, and the item keeps its identifier through
                // both — which is why this no longer needs to know where the item used to be.
                if changedFields.contains(.filename) || changedFields.contains(.parentItemIdentifier) {
                    result = try self.bridge.moveItem(id, parentId: parentId, name: name)
                }
                let final = try result ?? self.bridge.itemMetadata(id)
                completionHandler(TenshuFileProviderItem(final), [], false, nil)
            } catch {
                completionHandler(nil, [], false, FileProviderError.from(error))
            }
            progress.completedUnitCount = 1
        }
        return progress
    }

    func deleteItem(
        identifier: NSFileProviderItemIdentifier,
        baseVersion version: NSFileProviderItemVersion,
        options: NSFileProviderDeleteItemOptions = [],
        request: NSFileProviderRequest,
        completionHandler: @escaping (Error?) -> Void
    ) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let id = TenshuId.bridgeId(for: identifier)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try self.bridge.deleteItem(id)
                completionHandler(nil)
            } catch {
                // Already gone is what was asked for.
                let mapped = FileProviderError.from(error)
                completionHandler(mapped.code == NSFileProviderError.noSuchItem.rawValue ? nil : mapped)
            }
            progress.completedUnitCount = 1
        }
        return progress
    }
}

enum Thumbnail {
    /// `stored` as JPEG or PNG image data, or `nil` when it is not a picture ImageIO can read.
    ///
    /// The classes uploads mint today are JPEG and pass through untouched. Older list-size
    /// ones are PNG (desktop, iOS) or **WebP** (Android), and nothing establishes that the
    /// system takes WebP here — `NSFileProviderThumbnailing` says only "image data" — so
    /// anything that is not already JPEG or PNG is re-encoded as PNG.
    static func systemReadable(_ stored: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(stored as CFData, nil),
              let type = CGImageSourceGetType(source) as String? else { return nil }
        if type == UTType.jpeg.identifier || type == UTType.png.identifier { return stored }
        guard let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        let png = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            png, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? png as Data : nil
    }
}

/// Maps bridge failures to `NSFileProviderError`s the system understands.
enum FileProviderError {
    static func from(_ error: Error) -> NSError {
        if let already = error as NSError?, already.domain == NSFileProviderErrorDomain { return already }
        let bridgeError = error as? BridgeError
        let message = bridgeError?.message ?? error.localizedDescription
        // Everything not named here — daemon unreachable, a move not applied yet, bytes not
        // local — is retryable, so the system tries again instead of giving up on the item.
        let code: NSFileProviderError.Code
        switch bridgeError?.code {
        case "not_found": code = .noSuchItem
        case "already_exists": code = .filenameCollision
        // Retrying cannot help a file the engine cannot hold; say so instead of looping.
        case "too_large": code = .cannotSynchronize
        case "cancelled":
            return NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError,
                           userInfo: [NSLocalizedDescriptionKey: message])
        default:
            code = .serverUnreachable
            Unreachable.mark()
        }
        return NSError(domain: NSFileProviderErrorDomain, code: code.rawValue,
                       userInfo: [NSLocalizedDescriptionKey: message])
    }
}

/// Whether this process has told the system "server unreachable" and not yet told it otherwise.
enum Unreachable {
    private static let lock = NSLock()
    // ⚠ Starts outstanding: the system's backoff outlives this process, and a fresh one has no
    // idea what its predecessor reported. Assuming the worst costs one harmless "resolved" signal.
    private static var isOutstanding = true
    /// Counts the reports, so a "resolved" can be matched to the report it answers.
    private static var reports = 0

    /// The latest report while one is outstanding; `nil` once it has been resolved.
    static var outstanding: Int? {
        lock.lock(); defer { lock.unlock() }
        return isOutstanding ? reports : nil
    }

    static func mark() {
        lock.lock(); reports += 1; isOutstanding = true; lock.unlock()
    }

    /// Clears `report`, unless a newer one has been made since it was read.
    static func clear(_ report: Int) {
        lock.lock(); defer { lock.unlock() }
        if reports == report { isOutstanding = false }
    }
}
