import SwiftUI

// Settings (proposal §11.6): connection status + an *Open Control* link. There is
// deliberately **no in-Drive unpair** — Drive rides Control's identity (§11.5) and
// does not own the pairing; removal lives in Control (App Detail → Connections,
// control-ia.md §4.3).
struct SettingsView: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Connected through Control")
                        Text("on this device")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "checkmark.shield.fill")
                        .foregroundStyle(.green)
                }
            } header: {
                Text("Connection")
            } footer: {
                Text("Drive keeps your files end-to-end encrypted through Tenshu Control. To disconnect, open Control.")
            }

            Section("Preferences") {
                NavigationLink {
                    SyncPreferencesView()
                } label: {
                    Label("Sync Preferences", systemImage: "arrow.triangle.2.circlepath")
                }
            }

            // P6b: photo auto-backup (Synced-Folders reframe).
            PhotoBackupSection()

            Section {
                Button {
                    openURL(Control.url)
                } label: {
                    Label("Open Tenshu Control", systemImage: "arrow.up.forward.app")
                }
                // In-app privacy policy (App Store guideline 5.1.1(i)); ios-release/Sources. Then the
                // open-source notices (docs/oss-licences.md); Drive shows no third-party data.
                PrivacyPolicyLink()
                OpenSourceLicencesLink()
            }

            #if DEV_HARNESS
            Section("Developer") {
                Button(role: .destructive) {
                    ControlSessionStore.clearDevStub()
                } label: {
                    Label("Clear Control session (dev)", systemImage: "trash")
                }
            }
            #endif
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
    }
}
