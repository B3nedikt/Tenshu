// NSFileProviderEnumerator backed by the bridge
// (docs/proposals/desktop-macos-integration.md §5 task 3, §5a).
//
// Listings come from `listFolder` (one folder) or `listAll` (the working set). Changes come
// from the daemon's change feed: the anchor the system hands back is the daemon's, verbatim.

import FileProvider
import Foundation

final class TenshuFileProviderEnumerator: NSObject, NSFileProviderEnumerator {

    private let containerIdentifier: NSFileProviderItemIdentifier
    private let bridge: BridgeClient

    init(containerIdentifier: NSFileProviderItemIdentifier, bridge: BridgeClient) {
        self.containerIdentifier = containerIdentifier
        self.bridge = bridge
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        // Nothing is listed under the system's trash container: a Tenshu delete is final here.
        if containerIdentifier == .trashContainer {
            observer.finishEnumerating(upTo: nil)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // The working set is everything: it is the one enumerator the system asks for
                // changes, so an item missing from it would never hear about its own updates.
                let files = self.containerIdentifier == .workingSet
                    ? try self.bridge.listAll().items
                    : try self.bridge.listFolder(TenshuId.bridgeId(for: self.containerIdentifier))
                for chunk in files.chunked(max(observer.suggestedPageSize ?? 200, 1)) {
                    observer.didEnumerate(chunk.map { TenshuFileProviderItem($0) })
                }
                observer.finishEnumerating(upTo: nil)
            } catch {
                observer.finishEnumeratingWithError(FileProviderError.from(error))
            }
        }
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from anchor: NSFileProviderSyncAnchor) {
        DispatchQueue.global(qos: .utility).async {
            do {
                let since = String(decoding: anchor.rawValue, as: UTF8.self)
                guard let changes = try self.bridge.changesSince(since) else {
                    // The daemon restarted or the anchor is older than its history. The system
                    // re-lists from scratch, which is always correct.
                    observer.finishEnumeratingWithError(NSFileProviderError(.syncAnchorExpired))
                    return
                }
                // The feed is realm-wide. An enumerator on a folder reports its children, and
                // one on a single document reports that document — in both cases the item
                // whose id IS the container counts too. Deletions are reported everywhere: a
                // deleted id has no parent left to filter on, and an unknown one is ignored.
                //
                // (For a replicated extension the system takes changes from the working set
                // alone — FileProvider's own header says other signals are ignored — so this
                // branch is here for correctness if it ever asks, not because it does today.)
                let container = TenshuId.bridgeId(for: self.containerIdentifier)
                let relevant = self.containerIdentifier == .workingSet
                    ? changes.updated
                    : changes.updated.filter { $0.parentId == container || $0.id == container }
                // The system caps one change enumeration at 100 × its suggested batch size. The
                // feed has no intermediate anchors to page a larger delta with, so one that
                // big is reported as an expired anchor instead: the system re-lists, which is
                // slower and always correct. Anything smaller goes out in suggested-size calls.
                let batch = max(observer.suggestedBatchSize ?? 200, 1)
                if relevant.count + changes.deleted.count > batch * 100 {
                    observer.finishEnumeratingWithError(NSFileProviderError(.syncAnchorExpired))
                    return
                }
                for chunk in relevant.chunked(batch) {
                    observer.didUpdate(chunk.map { TenshuFileProviderItem($0) })
                }
                for chunk in changes.deleted.chunked(batch) {
                    observer.didDeleteItems(withIdentifiers: chunk.map(TenshuId.identifier(for:)))
                }
                observer.finishEnumeratingChanges(
                    upTo: NSFileProviderSyncAnchor(Data(changes.anchor.utf8)), moreComing: false)
            } catch {
                observer.finishEnumeratingWithError(FileProviderError.from(error))
            }
        }
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        DispatchQueue.global(qos: .utility).async {
            // nil when the daemon is unreachable: the system asks again later rather than
            // pinning an anchor that names nothing.
            let anchor = try? self.bridge.currentAnchor()
            completionHandler(anchor.map { NSFileProviderSyncAnchor(Data($0.utf8)) })
        }
    }
}

private extension Array {
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}
