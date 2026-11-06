import TenshuSdk

// What the viewer pages over (photos-ios.md §8.15, PIO-4 slice 1): the live feed, or a collection's
// own photographs — an album, Favourites, a place, a person. Compose makes the same split
// (`PhotoCollection.Feed` / `Fixed`): the feed is endless and pages under the viewer; a collection is
// whole already, so its list is a snapshot.
//
// ⚠ Plain Swift types here, so the viewer's lookup logic reads the same over either source.

@MainActor
protocol ViewerSource: AnyObject {
    /// Where the photograph at [path] sits now, or `nil` if this source does not hold it (yet).
    func positionOf(path: String) -> Int32?
    func photoAt(_ position: Int32) -> PhotosPhoto?
    /// The positions that hold a loaded photograph, in order.
    func loadedPositions() -> [Int32]
    /// Whether the WHOLE list is loaded — the one place absence proves a photograph gone.
    var isComplete: Bool { get }
    /// Fires whenever the list may have changed. A fixed source never changes under the viewer.
    func changes() -> AsyncStream<Void>
    /// Fires `true` while paging has failed. A fixed source never pages.
    func pagingFailures() -> AsyncStream<Bool>
    /// The viewer reached [position] — the feed's load trigger.
    func touch(_ position: Int32)
    func retryPaging()
    /// A write the user is watching changed the list (a trash) — reload before it is seen again.
    func refresh()
}

/// The live feed (`PhotosFeedBinding`): endless, paged, re-cut under the viewer.
@MainActor
final class FeedViewerSource: ViewerSource {
    let binding: PhotosFeedBinding

    init(binding: PhotosFeedBinding) { self.binding = binding }

    func positionOf(path: String) -> Int32? { binding.positionOf(path: path)?.int32Value }
    func photoAt(_ position: Int32) -> PhotosPhoto? { binding.photoAt(index: position) }
    func loadedPositions() -> [Int32] { binding.loadedPhotoPositions().map { $0.int32Value } }
    var isComplete: Bool { binding.layout.value.isComplete }

    func changes() -> AsyncStream<Void> {
        let layouts = binding.layout
        return AsyncStream { continuation in
            let task = Task { @MainActor in
                for await _ in layouts { continuation.yield(()) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func pagingFailures() -> AsyncStream<Bool> {
        let failures = binding.pagingFailed
        return AsyncStream { continuation in
            let task = Task { @MainActor in
                for await failed in failures { continuation.yield(failed.boolValue) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func touch(_ position: Int32) { binding.touch(index: position) }
    func retryPaging() { binding.retryPaging() }
    // ⚠ The file moved to `/Trash`, which the feed excludes — its tile must go before the grid is
    // seen again (Android refreshes in the same place).
    func refresh() { binding.refresh() }
}

/// A collection's photographs, whole: positions are indices, nothing pages, nothing re-cuts.
@MainActor
final class FixedViewerSource: ViewerSource {
    private let photos: [PhotosPhoto]
    private let onChanged: () -> Void

    /// [onChanged] — the collection the viewer came from reloads after a write (a trash).
    init(photos: [PhotosPhoto], onChanged: @escaping () -> Void) {
        self.photos = photos
        self.onChanged = onChanged
    }

    func positionOf(path: String) -> Int32? { photos.firstIndex { $0.path == path }.map(Int32.init) }
    func photoAt(_ position: Int32) -> PhotosPhoto? {
        position >= 0 && Int(position) < photos.count ? photos[Int(position)] : nil
    }
    func loadedPositions() -> [Int32] { (0..<photos.count).map(Int32.init) }
    var isComplete: Bool { true }
    func changes() -> AsyncStream<Void> { AsyncStream { $0.finish() } }
    func pagingFailures() -> AsyncStream<Bool> { AsyncStream { $0.finish() } }
    func touch(_ position: Int32) {}
    func retryPaging() {}
    func refresh() { onChanged() }
}
