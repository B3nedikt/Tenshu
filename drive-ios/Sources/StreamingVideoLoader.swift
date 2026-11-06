import Foundation
import AVFoundation
import UniformTypeIdentifiers
import TenshuSdk

// S7 (ios-drive-parity.md): seekable video playback over the pull-range streaming contract.
//
// Replaces P4's D2a "decrypt the whole file to a temp, AVPlayer(url: temp)" with an
// AVAssetResourceLoaderDelegate — Apple's first-class on-demand range loader. AVFoundation asks
// for byte ranges as the user plays/seeks; we fulfil each from the Kotlin
// `IosContentStreamSession` (which range-decrypts only the touched chunks). This is the iOS twin
// of Android S4's `openProxyFileDescriptor` `onRead`→`readContentRange`, minus the fd/content-
// provider indirection — the delegate IS the seam.
//
// A5 (threat model): a NET REDUCTION in disk exposure — no whole-file plaintext temp; only the
// bounded decrypted-chunk LRU in the proxy session plus a transient ≤256 KiB range in memory.
// House Rule #1 unchanged: bytes were decrypted client-side over the same op path.
//
// ⚠ Actual playback is framework-level (AVPlayer + resource loader) → the P7 on-device pass, the
// same verification ceiling as P4 video and Android S4.
final class StreamingVideoLoader: NSObject, AVAssetResourceLoaderDelegate {

    private let session: IosContentStreamSession
    private let fileName: String
    private let queue = DispatchQueue(label: "app.tenshu.drive.stream-loader")

    // A custom scheme so AVFoundation routes resource loading to this delegate instead of trying
    // to fetch the URL itself.
    private static let scheme = "tenshustream"
    private static let maxRangeChunk = 256 * 1024 // stay within the ReadContentRange inline cap

    init(session: IosContentStreamSession, fileName: String) {
        self.session = session
        self.fileName = fileName
    }

    /// Build the AVPlayer whose asset pulls its bytes through this delegate.
    func makePlayer() -> AVPlayer {
        var components = URLComponents()
        components.scheme = Self.scheme
        components.host = "video"
        // Keep the filename (its extension drives the content-type UTI below).
        components.path = "/" + fileName
        let asset = AVURLAsset(url: components.url!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return AVPlayer(playerItem: AVPlayerItem(asset: asset))
    }

    /// Release the proxy-side session. Idempotent; safe to call from a non-async context.
    func close() {
        Task { try? await session.close() }
    }

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        Task { await fulfil(loadingRequest) }
        return true
    }

    private func fulfil(_ request: AVAssetResourceLoadingRequest) async {
        let total = session.totalSize

        if let info = request.contentInformationRequest {
            info.isByteRangeAccessSupported = true
            info.contentLength = total
            info.contentType = contentTypeIdentifier()
        }

        guard let dataRequest = request.dataRequest else {
            request.finishLoading()
            return
        }

        // Fulfil from wherever AVFoundation currently needs bytes, up to the requested end (or EOF
        // for an open-ended request), looping ≤256 KiB reads across chunk boundaries.
        var offset = dataRequest.currentOffset
        let end: Int64 = dataRequest.requestsAllDataToEndOfResource
            ? total
            : min(total, dataRequest.requestedOffset + Int64(dataRequest.requestedLength))

        do {
            while offset < end {
                if request.isCancelled { return }
                let want = Int32(min(Int64(Self.maxRangeChunk), end - offset))
                guard let data = try await session.readRange(offset: offset, length: want),
                      !data.isEmpty else {
                    break // EOF or a per-chunk integrity-gate failure → stop; finishLoading below.
                }
                dataRequest.respond(with: data)
                offset += Int64(data.count)
            }
            request.finishLoading()
        } catch {
            request.finishLoading(with: error)
        }
    }

    private func contentTypeIdentifier() -> String {
        let ext = (fileName as NSString).pathExtension
        if !ext.isEmpty, let type = UTType(filenameExtension: ext) {
            return type.identifier
        }
        return UTType.movie.identifier
    }
}
