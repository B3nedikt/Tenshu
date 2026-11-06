import TenshuSdk

// Shared mapping from the domain `FileIcon` to an SF Symbol name, used by the Files list and the
// file detail screen.
extension FileIcon {
    var sfSymbol: String {
        switch self {
        case .home: return "house"
        case .folder: return "folder"
        case .audio: return "music.note"
        case .document: return "doc.text"
        case .image: return "photo"
        case .video: return "video"
        case .archive: return "archivebox"
        case .pdf: return "doc.richtext"
        case .other, .genericFile: return "doc"
        @unknown default: return "doc"
        }
    }

    // Whether a list row should attempt a rendered thumbnail (P4e gap #2) instead of just
    // the glyph. Matches the categories the iOS `GetPreview` resolver can produce or serve a
    // synced thumbnail for (images, PDF, and videos with a synced poster); everything else
    // stays a glyph, avoiding a wasted per-row `getPreview` byte read.
    var isThumbnailable: Bool {
        switch self {
        case .image, .video, .pdf: return true
        default: return false
        }
    }
}
