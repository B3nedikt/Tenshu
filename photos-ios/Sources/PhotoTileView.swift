import ImageIO
import SwiftUI
import TenshuSdk
import UIKit

// One grid tile (photos-ios.md PIO-D8).
//
// ⚠⚠ Bytes live in the tile, never in a view model: a model holding every thumbnail scrolled past
// keeps hundreds of megabytes alive on a real library (`photos-app.md` §10.2). `.task(id:)` makes
// the fetch viewport-driven — SwiftUI cancels it when the tile scrolls off.
//
// ⚠ The rung is chosen by the shared `tilePreviewRequestPx` inside `PhotosSurface.tilePreview`, not
// here. Swift only says how big the tile is, in device pixels.

struct PhotoTileView: View {
    let photo: PhotosPhoto
    let edgePoints: CGFloat
    let scale: CGFloat
    /// Paused while the scrubber is held — a cached tile still shows (`PreviewGate`).
    @ObservedObject var previews: PreviewGate
    /// Part of the Library's selection: inset within its cell, with a mark.
    var selected: Bool = false
    /// The feed's motion rotation — a VIDEO tile plays its turn here. `nil` off the grid.
    var motion: MotionState? = nil

    @State private var image: UIImage?
    @State private var loadedPath: String?

    private struct TileRequest: Hashable { let path: String; let edgePixels: Int; let paused: Bool }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Rectangle().fill(Color(uiColor: .secondarySystemFill))
            // ⚠ The cache is asked HERE, not only in the task: a row rebuilt when its line is re-cut
            // starts with no image, and the task fills it a frame late — a grey flash on a photograph
            // that was on screen a moment ago (photos-ios.md §8.20).
            if let image = image ?? TileImageLoader.cached(photo: photo, edgePixels: Int(edgePoints * scale)) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            }
            #if DEV_HARNESS
            if let tone = PhotosScreenProbe.tileTone { Rectangle().fill(tone) }
            #endif
            if photo.isVideo, let motion {
                MotionSlot(path: photo.path, state: motion)
            }
            if photo.isVideo {
                Image(systemName: "play.fill")
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .shadow(radius: 2)
                    .padding(5)
            }
        }
        // ⚠ Inset rather than tinted, as Android draws it (`PhotoTile`): a tint reads as a filter, and
        // several selected tiles in a row look like a rendering fault. The cell keeps its size.
        .frame(width: selected ? edgePoints - 12 : edgePoints, height: selected ? edgePoints - 12 : edgePoints)
        .clipped()
        .frame(width: edgePoints, height: edgePoints)
        .overlay(alignment: .topTrailing) {
            if selected {
                Image(systemName: "checkmark.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.accentColor)
                    .font(.title3)
                    .padding(8)
            }
        }
        .animation(.easeOut(duration: 0.12), value: selected)
        .contentShape(Rectangle())
        // ⚠ Whether it is selected is the one thing about a tile a screen reader must be able to say.
        .accessibilityLabel((photo.isVideo ? "Video" : "Photo") + (selected ? ", selected" : ""))
        // ⚠ Keyed by the photograph AND the size it is drawn at: a regrouping resizes
        // a tile in place, and Year → Day would otherwise keep the much smaller Year thumbnail.
        // ⚠ ED-3: keyed on `previewPath` — an edited photo's tile is its rendition, and a save or a
        // revert must reload it (`photos-editing.md` §12.4).
        .task(id: TileRequest(path: photo.previewPath, edgePixels: Int(edgePoints * scale), paused: previews.paused)) {
            // A cell reused for ANOTHER photograph drops the previous occupant's image — it must not
            // stay while the new one loads, or for ever when there is no preview. A resize of the SAME
            // photograph keeps its picture until the sharper one arrives, so it never flickers.
            if loadedPath != photo.path { image = nil }
            loadedPath = photo.path
            // ⚠ Keyed on the pause too, so letting go of the scrubber loads what it skipped.
            if previews.paused {
                image = TileImageLoader.cached(photo: photo, edgePixels: Int(edgePoints * scale)) ?? image
                return
            }
            image = await TileImageLoader.load(photo: photo, edgePixels: Int(edgePoints * scale)) ?? image
        }
    }
}

/// Decodes tile bytes with ImageIO downsampling and caches the result per path + size.
///
/// ⚠ Downsampled at decode, not after: `UIImage(data:)` would decode the full rung (up to 1024 px)
/// into memory for a ~130 pt tile. `kCGImageSourceThumbnailMaxPixelSize` decodes straight to the
/// size drawn (PIO-D8).
enum TileImageLoader {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 600
        return cache
    }()

    /// A decoded tile already in memory — no fetch, no decode.
    static func cached(photo: PhotosPhoto, edgePixels: Int) -> UIImage? {
        cache.object(forKey: "\(photo.previewPath)#\(edgePixels)" as NSString)
    }

    static func load(photo: PhotosPhoto, edgePixels: Int) async -> UIImage? {
        let key = "\(photo.previewPath)#\(edgePixels)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        // ⚠⚠ ED-3: the rendition of an edited photo, or nothing — never its original (§12.4).
        let data: Foundation.Data? = try? await PhotosSurface.shared.tilePreview(
            path: photo.previewPath,
            tileEdgePx: Int32(edgePixels),
            pixelWidth: photo.pixelWidth?.int32Value ?? 0,
            pixelHeight: photo.pixelHeight?.int32Value ?? 0
        )
        guard let data, let image = downsample(data, maxPixels: edgePixels * 2) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    /// ⚠ `maxPixels` is the LONG edge. A tile crops to a square (`scaledToFill`), so the short edge
    /// must cover the tile — hence the caller's 2× headroom for a 2:1 panorama.
    static func downsample(_ data: Foundation.Data, maxPixels: Int) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            // ⚠ Honour EXIF orientation: a portrait iPhone photograph is stored landscape.
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(maxPixels, 1),
        ] as CFDictionary
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
