import SwiftUI
import PDFKit
import AVKit
import TenshuSdk

// P4 (ios-drive-parity.md): the preview router — the iOS counterpart of the Compose
// `FilePreview` (drive-content-preview.md). The kind comes from the SHARED classifier
// (`DrivePreviewSurface.previewKindFor` — one truth with the list glyphs and the other
// platforms' routers); the bytes are the decrypted file over the installed repository,
// delivered through a Kotlin-written app-private temp file (the D2a shape — also what keeps
// multi-MB payloads from crossing the SKIE byte bridge). Image renders zoomable, PDF through
// native PDFKit (the §4.1 seam), video through AVPlayer, everything else gets the fallback
// with a share/export affordance. The temp file is deleted when the screen goes away.
struct FilePreviewView: View {
    let path: String
    let name: String

    @StateObject private var model: PreviewModel

    init(path: String, name: String) {
        self.path = path
        self.name = name
        _model = StateObject(wrappedValue: PreviewModel(path: path, name: name))
    }

    var body: some View {
        Group {
            switch model.state {
            case .loading:
                ProgressView("Loading…")
            case .image(let image):
                ZoomableImage(image: image)
            case .pdf(let document):
                PdfKitView(document: document)
            case .video(let player):
                VideoPlayer(player: player)
                    .onAppear { player.play() }
            case .other:
                fallbackView(note: "No preview for this file type.")
            case .unavailable:
                fallbackView(note: "Couldn't load this file.")
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: FileDetailRoute(path: path, name: name)) {
                    Image(systemName: "info.circle")
                }
                .accessibilityIdentifier("file-info")
            }
            if let url = model.shareURL {
                ToolbarItem(placement: .topBarTrailing) {
                    // Share/export of the decrypted copy — the D3 "open in another app"
                    // affordance in its iOS-native shape (the system share sheet).
                    ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
        .task { await model.load() }
        .onDisappear { model.cleanup() }
    }

    private func fallbackView(note: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "doc")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(name).font(.headline)
            Text(note).foregroundStyle(.secondary)
        }
        .padding(24)
    }
}

/// The file detail screen pushed from the preview's ⓘ (the old direct tap destination).
struct FileDetailRoute: Hashable {
    let path: String
    let name: String
}

@MainActor
final class PreviewModel: ObservableObject {
    enum PreviewState {
        case loading
        case image(UIImage)
        case pdf(PDFDocument)
        case video(AVPlayer)
        case other
        case unavailable
    }

    @Published var state: PreviewState = .loading
    @Published var shareURL: URL?

    private let path: String
    private let name: String
    private var tempPath: String?
    private var streamLoader: StreamingVideoLoader?

    init(path: String, name: String) {
        self.path = path
        self.name = name
    }

    func load() async {
        let kind = DrivePreviewSurface.shared.previewKindFor(fileName: name)

        // S7: a video streams seekably (range-decrypt, no whole-file plaintext temp) when a stream
        // is available; offline / unsynced / over the fake repo → fall through to the D2a temp-file
        // path below. Streamed playback has no on-disk copy, so there is no share URL for it (a
        // share would re-materialise the whole file, defeating the streaming win — the D2a fallback
        // path keeps its share affordance).
        if kind == "video",
           let session = (try? await DrivePreviewSurface.shared.openVideoStream(path: path)) ?? nil {
            let loader = StreamingVideoLoader(session: session, fileName: name)
            streamLoader = loader
            state = .video(loader.makePlayer())
            return
        }

        guard let tmp = try? await DrivePreviewSurface.shared.writePreviewTempFile(
            path: path, fileName: name
        ) else {
            state = .unavailable
            return
        }
        tempPath = tmp
        let url = URL(fileURLWithPath: tmp)
        shareURL = url
        switch kind {
        case "image":
            if let image = UIImage(contentsOfFile: tmp) {
                state = .image(image)
            } else {
                state = .unavailable
            }
        case "pdf":
            if let document = PDFDocument(url: url) {
                state = .pdf(document)
            } else {
                state = .unavailable
            }
        case "video":
            state = .video(AVPlayer(url: url))
        default:
            state = .other
        }
    }

    func cleanup() {
        if case .video(let player) = state { player.pause() }
        streamLoader?.close()
        streamLoader = nil
        if let tmp = tempPath {
            DrivePreviewSurface.shared.deletePreviewTempFile(tempPath: tmp)
            tempPath = nil
        }
    }
}

/// Aspect-fit image with pinch-to-zoom (double-tap resets).
private struct ZoomableImage: View {
    let image: UIImage
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .scaleEffect(scale)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .gesture(
                MagnificationGesture()
                    .onChanged { value in scale = max(1, lastScale * value) }
                    .onEnded { _ in lastScale = scale }
            )
            .onTapGesture(count: 2) {
                withAnimation {
                    scale = 1
                    lastScale = 1
                }
            }
    }
}

/// Native PDFKit rendering — the seam drive-content-preview.md §4.1 reserved for iOS
/// (the Compose `PdfDocument` actuals are deliberately android+desktop only).
private struct PdfKitView: UIViewRepresentable {
    let document: PDFDocument

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.document = document
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document !== document { view.document = document }
    }
}
