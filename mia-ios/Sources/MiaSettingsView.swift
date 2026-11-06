import SwiftUI

// Settings (home-and-mia-apps.md §4, D7): every entry present, gated until its machinery
// exists. iOS cannot replace Siri — the honest integration ceiling is the Action Button,
// "Hey Siri, ask Mia" App Intents, and widgets; that tier is a follow-up slice.
struct MiaSettingsView: View {
    var body: some View {
        List {
            entry(
                title: "Siri & the Action Button",
                body: "Siri can't be replaced on iOS. Bind the Action Button to Mia, or say \u{201C}Hey Siri, ask Mia\u{201D} once App Intents land.",
            )
            entry(
                title: "Voice identification",
                body: "Teach Mia your voice so the smart speaker knows who is talking.",
            )
            entry(
                title: "Model & inference",
                body: "Choose where Mia thinks — on this device, or on your Tenshu Hub.",
            )
            entry(
                title: "Skills",
                body: "Third-party voice skills arrive with the Tenshu Store.",
            )
            // In-app privacy policy (App Store guideline 5.1.1(i); ios-release/Sources), then the
            // open-source notices (docs/oss-licences.md) — both live, unlike the entries above.
            Section {
                PrivacyPolicyLink()
                OpenSourceLicencesLink()
            }
        }
        .navigationTitle("Settings")
    }

    private func entry(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.body.weight(.medium))
                Spacer()
                Text("Coming soon")
                    .font(.caption2)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color(.tertiarySystemFill), in: Capsule())
                    .foregroundStyle(.secondary)
            }
            Text(body).font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}
