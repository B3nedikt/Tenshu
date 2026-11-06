import SwiftUI

// Auto-continue on a terminal success screen — `docs/proposals/control-pairing-ux.md` §9.
// The SwiftUI twin of `:ui-core`'s `AppAutoContinueButton` (Compose) and web/apex's
// `AutoContinueButton.tsx`. Same rules, same timing:
//   • counts down only while `armed` (the shared VM's `PairingWizardUiState.autoContinueArmed`),
//     not cancelled, the scene `.active`, and VoiceOver off;
//   • a tap anywhere on the screen (`AutoContinueController.cancel`) or "Stay" stops it for good;
//   • leaving the foreground pauses it, and coming back restarts the full countdown;
//   • Reduce Motion drops the ring, never the timer.

/// ⚠ Mirrors `AUTO_CONTINUE_DURATION_MS` (Kotlin) and `AUTO_CONTINUE_DURATION_MS` (web). Change all three.
let autoContinueDurationMs = 3_000

/// The hoisted cancel half of `AutoContinueButton`, so the screen around it can cancel on any tap.
/// Sticky until `reset()`, which the screen calls when a new terminal appears.
@MainActor
final class AutoContinueController: ObservableObject {
    @Published private(set) var isCancelled = false

    func cancel() {
        if !isCancelled { isCancelled = true }
    }

    func reset() {
        if isCancelled { isCancelled = false }
    }
}

struct AutoContinueButton: View {
    let title: String
    let armed: Bool
    @ObservedObject var controller: AutoContinueController
    var outlined = false
    let action: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOverEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var fired = false
    @State private var startedAt: Date? = nil
    @State private var secondsLeft = (autoContinueDurationMs + 999) / 1_000

    private var counting: Bool {
        armed && !fired && !controller.isCancelled && !voiceOverEnabled && scenePhase == .active
    }

    var body: some View {
        VStack(spacing: 8) {
            if outlined {
                button.buttonStyle(OutlinedButtonStyle())
            } else {
                button.buttonStyle(PrimaryButtonStyle())
            }
            if counting {
                Button { controller.cancel() } label: { Text("Stay") }
                    .buttonStyle(LinkButtonStyle())
            }
        }
        // Restarts from the full duration whenever `counting` flips back on — the return from the
        // background included — and is cancelled the moment it flips off.
        .task(id: counting) { await countDown() }
    }

    private var button: some View {
        Button {
            fired = true
            action()
        } label: {
            HStack(spacing: 8) {
                if counting && !reduceMotion, let startedAt {
                    TimelineView(.animation) { context in
                        ring(fraction: context.date.timeIntervalSince(startedAt) * 1_000 / Double(autoContinueDurationMs))
                    }
                }
                Text(title)
            }
        }
        .accessibilityValue(counting ? countdownLabel : "")
    }

    private func ring(fraction: Double) -> some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.3), lineWidth: 2)
            Circle()
                .trim(from: 0, to: min(max(fraction, 0), 1))
                .stroke(style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 16, height: 16)
        .accessibilityHidden(true)
    }

    private var countdownLabel: String {
        String(localized: "Continuing in \(secondsLeft) seconds")
    }

    private func countDown() async {
        secondsLeft = (autoContinueDurationMs + 999) / 1_000
        guard counting else {
            startedAt = nil
            return
        }
        startedAt = Date()
        var left = autoContinueDurationMs
        while left > 0 {
            secondsLeft = (left + 999) / 1_000
            let step = left % 1_000 == 0 ? 1_000 : left % 1_000
            do {
                try await Task.sleep(nanoseconds: UInt64(step) * 1_000_000)
            } catch {
                return // cancelled: paused, Stay, or the screen went away
            }
            left -= step
        }
        guard counting else { return }
        fired = true
        action()
    }
}
