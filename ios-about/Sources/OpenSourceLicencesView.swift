import SwiftUI

// The open-source notices every first-party iOS app ships (`docs/oss-licences.md`) — the SwiftUI twin
// of `:ui-core`'s `OpenSourceLicencesScreen`. The library list is read from the bundle, never written
// here: `kotlin-licences.json` is generated from the app framework's Gradle graph, and each fragment
// that adds Swift-side code ships its own `*-licences.json` beside it.
//
// Compiled into all ten apps (`scripts/ios-smoke.sh --build-only`, 2026-10-24). Keep it small.

/// One component the app bundle ships, as its `*-licences.json` records it.
struct OssComponent: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let version: String?
    let licences: [OssLicence]
}

/// A licence as the notices show it. `text` is nil when the build has no copy (name + URL only).
struct OssLicence: Hashable, Sendable {
    let name: String
    let url: URL?
    let text: String?
}

/// Reads the bundle's notices — the AboutLibraries JSON format, whichever file wrote it.
enum OssNotices {
    private struct File: Decodable {
        let libraries: [Library]
        let licenses: [String: Licence]
    }
    private struct Library: Decodable {
        let uniqueId: String
        let artifactVersion: String?
        let name: String?
        let licenses: [String]?
    }
    private struct Licence: Decodable {
        let name: String
        let url: String?
        let content: String?
    }

    /// Every `*-licences.json` in [bundle], merged by id and sorted by name. Blocking.
    static func load(bundle: Bundle = .main) -> [OssComponent] {
        let urls = (bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? [])
            .filter { $0.lastPathComponent.hasSuffix("-licences.json") }
        var byId: [String: OssComponent] = [:]
        for url in urls {
            guard let data = try? Data(contentsOf: url),
                  let file = try? JSONDecoder().decode(File.self, from: data) else { continue }
            for library in file.libraries where byId[library.uniqueId] == nil {
                let licences = (library.licenses ?? []).compactMap { key -> OssLicence? in
                    guard let licence = file.licenses[key] else { return nil }
                    return OssLicence(
                        name: licence.name,
                        url: licence.url.flatMap(URL.init(string:)),
                        text: licence.content.flatMap { $0.isEmpty ? nil : $0 }
                    )
                }
                byId[library.uniqueId] = OssComponent(
                    id: library.uniqueId,
                    name: library.name ?? library.uniqueId,
                    version: library.artifactVersion,
                    licences: licences
                )
            }
        }
        return byId.values.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }
}

/// Data and assets that need a credit of their own — map data, fonts, a geocoder's results. Not
/// code, so no JSON file lists them; each app passes the ones it shows. Mirrors `DataAttributions`.
struct OssDataCredit: Identifiable {
    let id: String
    let title: LocalizedStringKey
    let credit: LocalizedStringKey
    /// The licence's name. A legal name, so deliberately not translated.
    let licence: String
    let url: URL?

    /// ⚠⚠ ODbL 1.0 §4.3: every app that renders or derives from OSM data carries this.
    static let openStreetMap = OssDataCredit(
        id: "osm", title: "OpenStreetMap", credit: "Map data © OpenStreetMap contributors",
        licence: "Open Database License (ODbL) 1.0", url: URL(string: "https://www.openstreetmap.org/copyright"))
    static let protomaps = OssDataCredit(
        id: "protomaps", title: "Protomaps basemap",
        credit: "Offline map tiles built from OpenStreetMap data with the Protomaps basemap schema",
        licence: "BSD 3-Clause (schema) · ODbL 1.0 (data)", url: URL(string: "https://protomaps.com"))
    /// ⚠ The OFL's own condition is met by `OFL.txt`, which ships in the bundled glyph folder.
    static let notoSans = OssDataCredit(
        id: "noto", title: "Noto Sans (map labels)", credit: "Copyright 2022 The Noto Project Authors",
        licence: "SIL Open Font License 1.1", url: URL(string: "https://openfontlicense.org"))
    static let photon = OssDataCredit(
        id: "photon", title: "Photon place lookup (komoot)",
        credit: "Place names, when online lookup is on, come from OpenStreetMap data via Photon, run by komoot",
        licence: "Open Database License (ODbL) 1.0", url: URL(string: "https://photon.komoot.io"))
    static let models = OssDataCredit(
        id: "models", title: "On-device models",
        credit: "Downloaded separately; each model shows its licence under Settings → Models",
        licence: "Apache-2.0 (per model)", url: nil)

    /// What every app that draws the offline basemap owes.
    static let basemap: [OssDataCredit] = [openStreetMap, protomaps, notoSans]
}

/// The settings row that pushes [OpenSourceLicencesView]. Must sit inside a `NavigationStack`.
struct OpenSourceLicencesLink: View {
    var data: [OssDataCredit] = []

    var body: some View {
        NavigationLink {
            OpenSourceLicencesView(data: data)
        } label: {
            Label("Open-source licences", systemImage: "doc.text")
        }
    }
}

struct OpenSourceLicencesView: View {
    let data: [OssDataCredit]
    @State private var components: [OssComponent]?

    var body: some View {
        List {
            Section {
                Text("This app is built on open-source software and open data. Each component is listed with its licence; select a library to read its licence text.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if !data.isEmpty {
                Section("Data and fonts") {
                    ForEach(data) { credit in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(credit.title)
                            Text(credit.credit).font(.caption).foregroundStyle(.secondary)
                            if let url = credit.url {
                                Link(credit.licence, destination: url).font(.caption)
                            } else {
                                Text(verbatim: credit.licence).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            Section("Libraries") {
                if let components {
                    if components.isEmpty {
                        Text("This build does not include its licence list.")
                    } else {
                        ForEach(components) { component in
                            NavigationLink {
                                OssLicenceTextView(component: component)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: [component.name, component.version].compactMap { $0 }.joined(separator: " "))
                                    Text(verbatim: component.licences.map(\.name).joined(separator: " · "))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } else {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
            }
        }
        .navigationTitle("Open-source licences")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if components == nil {
                components = await Task.detached(priority: .userInitiated) { OssNotices.load() }.value
            }
        }
    }
}

private struct OssLicenceTextView: View {
    let component: OssComponent

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(component.licences, id: \.self) { licence in
                    Text(verbatim: licence.name).font(.headline)
                    if let text = licence.text {
                        Text(verbatim: text).font(.footnote).textSelection(.enabled)
                    } else {
                        Text("The full licence text is available online.").font(.footnote)
                    }
                    if let url = licence.url {
                        Link("Open online", destination: url).font(.footnote)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle(Text(verbatim: component.name))
        .navigationBarTitleDisplayMode(.inline)
    }
}
