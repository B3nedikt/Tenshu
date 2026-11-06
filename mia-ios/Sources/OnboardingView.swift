import SwiftUI

// The one-time intro flow (home-and-mia-apps.md §4, M5), SwiftUI twin of the Compose
// onboarding: Welcome → integration guidance → Done, every step skippable. The middle
// step is iOS-specific (D7): Siri cannot be replaced, so it points at the Action Button /
// "Hey Siri, ask Mia" ceiling instead of a role request.
struct OnboardingView: View {
    @Binding var onboarded: Bool
    @State private var step = 0

    var body: some View {
        VStack {
            HStack {
                Spacer()
                if step < 2 {
                    Button("Skip") { onboarded = true }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)

            Spacer()
            Image(systemName: symbol)
                .font(.system(size: 52))
                .foregroundStyle(.tint)
            Text(title)
                .font(.title.weight(.semibold))
                .padding(.top, 20)
            Text(body_)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .padding(.top, 8)
            Spacer()

            HStack {
                if step > 0 {
                    Button("Back") { step -= 1 }
                }
                Spacer()
                if step < 2 {
                    Button("Next") { step += 1 }
                        .buttonStyle(.borderedProminent)
                } else {
                    Button("Get started") { onboarded = true }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
        }
    }

    private var symbol: String {
        switch step {
        case 0: return "bubble.left.and.text.bubble.right"
        case 1: return "sparkles"
        default: return "checkmark.circle"
        }
    }

    private var title: String {
        switch step {
        case 0: return "Meet Mia"
        case 1: return "Mia, one press away"
        default: return "All set"
        }
    }

    private var body_: String {
        switch step {
        // ⚠ Reads only: "…and control your home" stood here, and Mia on iPhone offers no action.
        case 0: return "Your assistant for everything in your realm — find photos from your Drive, places from your Timeline, and what's on your calendar. Private by design."
        case 1: return "Siri can't be replaced on iOS — but you can bind the Action Button to Mia, add the widget, and say \u{201C}Hey Siri, ask Mia\u{201D} once App Intents land."
        default: return "Ask Mia anything — and find voice identification, model settings and more in Settings."
        }
    }
}
