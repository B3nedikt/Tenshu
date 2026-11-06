import SwiftUI
import TenshuSdk

// The viewer's About sheet (photos-ios.md §8.14, PIO-3 slice 3) — Compose's `AboutSheet` on iOS.
//
// ⚠⚠ Swift decides nothing (PIO-D2): which rows exist, what they are called, how an album line reads
// and what the settings section says are the shared `AboutSheetContent` (`PhotosSurface.aboutSheet`).
// This file lays them out.
//
// ⚠ Opened through the view model (`setInfoOpen`), never around it: that is what reads the details,
// the albums and — only for an open sheet — the file's own EXIF, and what keeps the sheet describing
// the photograph on screen.

struct AboutSheetView: View {
    let content: AboutSheetContent

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(content.title).font(.headline)
                Text(content.fileName).font(.footnote).foregroundStyle(.secondary)

                // ⚠ Omitted when empty — a heading that says nothing is not a section.
                if !content.albums.isEmpty {
                    heading("Albums")
                    // ⚠ Keyed by the album's id, not its words: two albums can share a name and count.
                    ForEach(content.albums, id: \.albumId) { album in
                        Text(album.line).font(.subheadline).padding(.vertical, 3)
                    }
                }

                // ⚠⚠ Only a NAMED place (D2) — absent for most photographs, with no fallback.
                if let place = content.place {
                    heading("Place")
                    Text(place).font(.subheadline)
                }

                heading("Details")
                ForEach(content.details, id: \.label) { row($0) }

                settings
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .accessibilityIdentifier("aboutSheet")
    }

    @ViewBuilder
    private var settings: some View {
        switch onEnum(of: content.settings) {
        case .reading, .silent:
            EmptyView()
        case .notOnDevice(let fact):
            // A fact about this device, said in one line — not a failure, and not hidden.
            Text(fact.message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 12)
        case .rows(let settings):
            if !settings.rows.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(settings.rows, id: \.label) { row($0) }
                }
                .padding(.top, 12)
            }
        }
    }

    private func heading(_ text: String) -> some View {
        Text(text)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.top, 16)
            .padding(.bottom, 4)
    }

    private func row(_ row: AboutRow) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(row.label)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(width: 104, alignment: .leading)
            Text(row.suffix.map { "\(row.value)  (\($0))" } ?? row.value)
                .font(.footnote)
                .textSelection(.enabled)
        }
        .padding(.vertical, 3)
    }
}
