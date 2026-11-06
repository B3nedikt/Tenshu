import SwiftUI
import TenshuSdk

// Photos' Settings (ios-on-device-inference.md §14.18, M1): behind the Library's toolbar button, and
// today two rows, Models and the privacy policy. PIO-5's rows (sources, the album picker,
// skip-duplicates, play-videos) join this list.
//
// ⚠⚠ The Models row exists only in a build with on-device AI (`PhotosSettings.modelsOffered`). The
// screen itself is in every build since the privacy-policy row joined it (App Store guideline
// 5.1.1(i) wants the policy reachable in-app) — before that, a build without AI had no button and
// no screen, because a Settings list with nothing in it is a dead screen.
// ⚠ Every word is `PhotosModelsCopy`'s. *Settings → Models* is the path the search bar's consent
// line names (`ModelsSwitchHome.THIS_APP`): renaming either row here makes that sentence wrong.

@MainActor
enum PhotosSettings {
    /// Whether this build has the Models row: the build flag, read once.
    static let modelsOffered: Bool = {
        #if DEV_HARNESS
        // `-photosModels <state>`: the screens over the fixture, in a build whose flag is off.
        if ModelsProbe.probe != nil { return true }
        #endif
        return PhotosLocalRuntime.shared.modelsScreenInThisBuild()
    }()
}

struct PhotosSettingsScreen: View {
    @State private var showModels = PhotosSettingsScreen.opensModels

    private static var opensModels: Bool {
        #if DEV_HARNESS
        return ModelsProbe.opens != nil
        #else
        return false
        #endif
    }

    var body: some View {
        List {
            if PhotosSettings.modelsOffered {
                Button { showModels = true } label: {
                    PushRow(title: PhotosModelsCopy.shared.MODELS, status: PhotosModelsCopy.shared.MODELS_ROW_DETAIL, detail: nil)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings-models")
            }
            // In-app privacy policy (App Store guideline 5.1.1(i)); ios-release/Sources.
            PrivacyPolicyLink()
            // The open-source notices (docs/oss-licences.md). Photos shows no third-party data.
            OpenSourceLicencesLink()
        }
        .navigationTitle(PhotosModelsCopy.shared.SETTINGS)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showModels) { ModelsScreen() }
    }
}

/// A list row that pushes a screen: a title, up to two lines under it, and the chevron.
/// ⚠ A `Button` with its own chevron, not a `NavigationLink`: the destination is presented by a
/// flag, so the harness can open it without a tap (`-photosModelsOpen`).
struct PushRow: View {
    let title: String
    let status: String?
    let detail: String?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.body).foregroundStyle(.primary)
                if let status { Text(status).font(.subheadline).foregroundStyle(.secondary) }
                if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
            }
            // ⚠ The sentences wrap, at every text size: nothing here is one line.
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}
