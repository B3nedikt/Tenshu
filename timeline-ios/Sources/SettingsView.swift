import SwiftUI
import TenshuSdk

// Settings — the destination behind the Home gear (docs/proposals/timeline-app.md §7). Minimal +
// honest for v1: an Appearance note (theme follows the system light/dark setting; no persisted
// preference store is wired into Timeline yet) and an About card. Static — no view model, mirroring
// the Compose `SettingsScreen`. Strings match the shared `timeline_settings_*` resources.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    // Place-name lookups (place-geocoding.md G5): shown only when the live client is installed
    // (the mock tier has nothing to look up), mirroring the bound-only Compose toggle. The
    // consent flag persists via NSUserDefaults (threat-model A35(d) — off by default).
    private let geocodingAvailable = TimelineSurface.shared.geocodingAvailable()
    @State private var geocodingEnabled = TimelineSurface.shared.geocodingEnabled()
    // Live location capture (M4, timeline-movement-capture.md §6): shown only over the live
    // route-1 runtime (the mock tier has nowhere to write). Off by default (threat-model A35(a));
    // the toggle tap is the consent moment — enabling requests Always location authorization and
    // starts CLVisit + significant-change monitoring; a denial resets the flag.
    private let captureAvailable = TimelineCaptureRuntime.shared.isCaptureAvailable()
    @State private var captureEnabled = TimelineCaptureRuntime.shared.isCaptureEnabled()
    // Wi-Fi place recognition (timeline-recognition-and-place-naming.md §4.5): shown only over the
    // live route-1 runtime, which owns the fingerprint store. Off by default. The status line under
    // the toggle is the S7 observability half — without it "it does nothing" is unanswerable
    // without a log tail, which is exactly how the feature looked dead on Android.
    private let wifiAvailable = TimelineSurface.shared.wifiRecognitionAvailable()
    @State private var wifiEnabled = TimelineSurface.shared.wifiRecognitionEnabled()
    @State private var wifiStatus = ""

    var body: some View {
        List {
            if captureAvailable {
                Section {
                    Toggle("Record places and trips", isOn: $captureEnabled)
                        .onChange(of: captureEnabled) { enabled in
                            TimelineCaptureRuntime.shared.setCaptureEnabled(enabled: enabled)
                        }
                    Text("Capture the places you visit and the trips between them, in the background. Off by default — everything stays end-to-end encrypted on your own devices; no location ever reaches a server in the clear. Needs the \u{201C}Always\u{201D} location permission to record while the app is closed.")
                        .font(.caption).foregroundStyle(.secondary)
                } header: {
                    Text("Location capture")
                }
            }

            if wifiAvailable {
                Section {
                    Toggle("Recognise places by Wi-Fi", isOn: $wifiEnabled)
                        .onChange(of: wifiEnabled) { enabled in
                            TimelineSurface.shared.setWifiRecognitionEnabled(enabled: enabled)
                            Task { await refreshWifiStatus() }
                        }
                    Text("Indoors, GPS is often only accurate to about 100 m — enough to name the shop next door instead of the one you were in. Remembering which Wi-Fi network you were on fixes that. Off by default; network names never leave your devices.")
                        .font(.caption).foregroundStyle(.secondary)
                    if wifiEnabled, !wifiStatus.isEmpty {
                        Text(wifiStatus)
                            .font(.caption)
                            // Only the withheld case is a problem the user can act on; everything
                            // else is neutral (Compose `WifiStatusLine` takes the same posture — a
                            // row that cried failure before capture had run would train them to
                            // ignore it).
                            .foregroundStyle(wifiStatus == Self.wifiWithheldCopy ? Color.red : Color.secondary)
                    }
                } header: {
                    Text("Place recognition")
                }
            }

            if geocodingAvailable {
                Section {
                    Toggle("Look up place names", isOn: $geocodingEnabled)
                        .onChange(of: geocodingEnabled) { enabled in
                            TimelineSurface.shared.setGeocodingEnabled(enabled: enabled)
                        }
                    Text("Name unnamed places automatically. Off by default — a lookup sends a place's coordinates to the lookup provider, and nothing else leaves your devices.")
                        .font(.caption).foregroundStyle(.secondary)
                } header: {
                    Text("Place names")
                }
            }

            // Beside the geocoding toggle, which is where §4 argues an egress election belongs —
            // in Settings, not as an onboarding fork.
            MapRendererSection()

            Section {
                LabeledContent("Theme", value: "Follows your system")
            } header: {
                Text("Appearance")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Timeline").font(.timelineSerif(24, weight: .semibold))
                    Text("Your places, moments, and trips — a private chapter of your life.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Text("End-to-end encrypted · Built on Tenshu")
                        .font(.caption).foregroundStyle(Color.accentColor)
                }
                .padding(.vertical, 4)
                // In-app privacy policy (App Store guideline 5.1.1(i)); ios-release/Sources.
                PrivacyPolicyLink()
                // The open-source notices (docs/oss-licences.md): the basemap's credits, plus
                // Photon for the place names.
                OpenSourceLicencesLink(data: OssDataCredit.basemap + [.photon])
            } header: {
                Text("About")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        // ⚠ `.task`, not a plain getter in the property initialiser: on iOS the radio read is
        // asynchronous, so a synchronous read on appear would report whatever the cache last held —
        // usually nothing on a freshly-opened screen. The probe refreshes first and never learns.
        .task { await refreshWifiStatus() }
    }

    private func refreshWifiStatus() async {
        guard wifiAvailable, wifiEnabled else {
            wifiStatus = ""
            return
        }
        let key = (try? await TimelineSurface.shared.probeWifiRecognition()) ?? ""
        // Keys produced by `TimelineSurface.STATUS_*`; the Compose twin maps the same four buckets
        // to string resources in `SettingsScreen.WifiStatusLine`.
        switch key {
        case "working": wifiStatus = "Recognising the network you're on."
        case "no_network": wifiStatus = "No Wi-Fi network detected."
        case "withheld": wifiStatus = Self.wifiWithheldCopy
        default: wifiStatus = ""
        }
    }

    private static let wifiWithheldCopy =
        "Turn on Location Services so Timeline can identify the network."
}
