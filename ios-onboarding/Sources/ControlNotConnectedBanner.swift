import SwiftUI

/// The one **non-blocking** forward-to-Control surface, shared by every first-party route-1
/// iOS app (ADR 0022). The sibling of `ConnectToControlView`: same destination, different
/// posture.
///
/// ⚠⚠ **Which one an app owes is decided by the surface behind it, not by the app**
/// (ADR 0022 §Decision 4). `ConnectToControlView` replaces a *dead end* — Drive's root, Fit's
/// Record tab — where there is nothing to look at and a full-screen gate takes nothing away.
/// This banner belongs above a *browsable* surface — Timeline, Maps, Contacts, and Fit's other
/// tabs — where the app still shows cached content and a wall would delete it.
///
/// ⚠ It exists because the honest-empty-surface half of that decision shipped with the same
/// defect the ADR was written about: four apps rendered "open Control and unlock it, then come
/// back" as **text with nothing to tap**, which is an instruction the user cannot follow from
/// where they are standing. Saying why a screen is empty and offering a way out are two duties,
/// and only the first one had been done. The button hands off to `ConnectToControlView` in a
/// sheet, so both postures route through one screen and one App Store decision.
///
/// ⚠ The banner does **not** dismiss its own sheet, deliberately. Every host already tears this
/// tree down when provisioning arrives (Timeline/Maps remount on `liveToken`, Contacts on its
/// reachability flow, Fit on `.liveSessionChanged`), which takes the sheet with it. A second,
/// banner-local "am I still needed" check would be a second source of truth for a question the
/// host already answers — and the one that got it wrong would be invisible.
struct ControlNotConnectedBanner: View {
    /// The host app's display name, e.g. "Timeline". Forwarded to the sheet.
    let appName: String
    /// One clause completing "<appName> uses Tenshu Control to …". Forwarded to the sheet.
    let purpose: LocalizedStringResource
    /// Re-read provisioning after the user returns (host decides what that means). Only the
    /// `DEV_HARNESS` stub calls it — the shipping path recovers through the host's own
    /// `scenePhase` gate — but wiring it is what makes the simulator path work at all.
    var onRecheck: () -> Void = {}

    @State private var showingSetup = false

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            // ⚠ Names what the user is looking at ("what it already has") rather than issuing an
            // instruction: the instruction now lives on the button, where it can be acted on.
            Text("Can't reach Tenshu Control — \(appName) is showing what it already has.")
                .font(.footnote.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button {
                showingSetup = true
            } label: {
                Text("Set up")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color(.systemRed).opacity(0.18), in: Capsule())
            }
            // ⚠ `.plain`, so the label keeps the banner's red foreground instead of flipping to
            // the app's accent colour — the affordance has to read as part of the warning.
            .buttonStyle(.plain)
            .accessibilityLabel("Set up Tenshu Control")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(Color(.systemRed).opacity(0.14))
        .foregroundStyle(Color(.systemRed))
        .sheet(isPresented: $showingSetup) {
            ConnectToControlView(
                appName: appName,
                purpose: purpose,
                onRecheck: {
                    showingSetup = false
                    onRecheck()
                }
            )
            // Drag-to-dismiss works without it; the indicator is what says so. The screen has no
            // close button of its own because at its other call site it IS the app.
            .presentationDragIndicator(.visible)
        }
    }
}
