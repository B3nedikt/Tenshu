import SwiftUI

// Photos' shared presentation bits (the `FitTheme.swift` / `EmptyStateKit.swift` precedent).

/// A centred empty / error state with an optional action.
///
/// ⚠ Hand-rolled on purpose: `ContentUnavailableView` is iOS 17+ and the floor is 16.0 (PIO-D11).
/// That gotcha has cost five Mac passes across the sibling apps.
struct PhotosEmptyState: View {
    let title: String
    let systemImage: String
    var description: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.title3.bold())
            if let description {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
