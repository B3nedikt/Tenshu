import SwiftUI
import TenshuSdk

// The offline region picker (`maps-region-delivery.md` D5 — Maps owns the picker) — the SwiftUI twin
// of `RegionPickerScreen`. Lists the catalog with honest sizes + install state; installed regions
// can be deleted, available ones downloaded (a `false` from the seam surfaces a transient message
// rather than a broken flow).
//
// ⚠⚠ **The Compose picker is a list, not a map**, and this mirrors it. It is worth stating because
// "region picker" sounds like a map-with-a-rectangle-you-drag: the catalog is a fixed, signed set of
// pre-built extracts (`maps-region-delivery.md`), so there is nothing to draw a selection box on —
// the choice is which of N named regions to hold. That is why nothing here uses `TenshuMapView`'s
// `interactive` flag, which remains unexercised across all three consumers.
//
// ⚠⚠ The copy says what the **iOS** renderer does, which is not what Android's does. iOS has no
// streamed region path (B-4, `ios-fit-timeline-maps-parity.md` §3.4): the map draws only from a
// downloaded region or the bundled Firenze demo, and falls back to a schematic sketch elsewhere — it
// never fetches tiles. So nothing here may say "the map fetches the tiles it needs". And the region
// store is durable since P4-1 (`IosFileDatabase`), so "this session only" is gone too. ⚠ The copy
// makes no claim about sharing a region across apps: that depends on the App Group (§P4-2).
@MainActor
private final class RegionPickerModel: ObservableObject {
    @Published var state: RegionPickerUiState?
    private let vm: RegionPickerViewModel

    init() {
        vm = MapsSurface.shared.regionPickerViewModel()
    }

    func observe() async {
        do { for try await value in vm.uiState { state = value } } catch {}
    }

    func download(_ id: String) { vm.download(regionId: id) }

    func delete(_ id: String) { vm.delete(regionId: id) }

    func consumeMessage() { vm.consumeMessage() }
}

struct RegionPickerView: View {
    @StateObject private var model = RegionPickerModel()
    @State private var showingUnavailable = false

    var body: some View {
        List {
            // Maps has no Settings screen of its own; this is the app's map-configuration surface,
            // so the renderer election belongs here — above the regions, because electing Apple
            // Maps changes what a region is *for*.
            MapRendererSection()

            Section {
                Text("Regions you keep on this device. Tenshu's map is drawn only from regions downloaded here and a small built-in area; it never fetches map tiles over the network. Without a downloaded region, places outside that area show as a simple sketch. A downloaded region works with no network at all.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
            if let state = model.state {
                if state.loading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                        .listRowBackground(Color.clear)
                } else if state.regions.isEmpty {
                    // Honest, not fabricated: with no runtime the catalog is served by nothing
                    // (`NotConnectedRegionRepository`), and with one it may still be empty.
                    Text(MapsSurface.shared.controlReachable()
                         ? "No regions offered yet."
                         : "The region catalogue lives behind Tenshu Control. Unlock Control to see it.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(state.regions, id: \.regionId) { region in
                        RegionRow(
                            region: region,
                            onDownload: { model.download(region.regionId) },
                            onDelete: { model.delete(region.regionId) }
                        )
                    }
                    Section {
                        Text("Downloaded regions stay on this device until you delete them.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .listRowBackground(Color.clear)
                    }
                }
            }
            // The legal entries: this is the screen Maps' settings gear opens (Maps has no Settings
            // screen), so the in-app privacy policy (App Store guideline 5.1.1(i);
            // ios-release/Sources) and the open-source notices, with the map data's own credits
            // (docs/oss-licences.md), live here.
            Section {
                PrivacyPolicyLink()
                OpenSourceLicencesLink(data: OssDataCredit.basemap)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Offline maps")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .onChange(of: model.state?.message) { message in
            if message == .downloadUnavailable {
                showingUnavailable = true
                model.consumeMessage()
            }
        }
        .alert("Download isn't available yet", isPresented: $showingUnavailable) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Regions you already have keep working.")
        }
    }
}

private struct RegionRow: View {
    let region: RegionItem
    let onDownload: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(region.name).font(.subheadline.weight(.medium)).lineLimit(1)
                // ⚠ `formatRegionSize`, not the Compose row's raw `byteSize / 1_000_000`: a
                // `Text` interpolation of an Int is locale-grouped, so a 2.1 GB region rendered as
                // "2.100 MB" — indistinguishable from 2.1 MB in a decimal-comma locale. The
                // detail screen's offline row already used this formatter; the two now agree.
                Text(formatRegionSize(region.byteSize))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if region.installed {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor)
                    Text("Downloaded").font(.caption).foregroundStyle(.secondary)
                    Button(role: .destructive, action: onDelete) {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Delete this region")
                }
            } else {
                Button("Get", action: onDownload)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
        }
        .padding(.vertical, 2)
    }
}
