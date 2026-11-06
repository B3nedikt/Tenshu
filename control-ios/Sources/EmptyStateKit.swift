import SwiftUI

/// `ContentUnavailableView` is iOS 17+; every Tenshu iOS shell targets 16.0, so this is the
/// stand-in. It is the fifth copy of the same shape in the repo (`FitEmptyState`,
/// timeline's `DetailSupport`, contacts' and maps' local versions) — the two Control screens
/// that need it are the first pair in one app, which is why it lives in its own file here
/// rather than at the bottom of whichever view happened to want it first.
///
/// The layout matches `HomeView.emptyState` (glyph, headline, optional footnote) so the
/// three empty surfaces in this app read as one design rather than three approximations.
struct ControlEmptyState: View {
    let title: String
    let systemImage: String
    var description: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            if let description {
                Text(description)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
    }
}
