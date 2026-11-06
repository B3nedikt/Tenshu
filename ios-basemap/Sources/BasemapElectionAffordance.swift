import SwiftUI

/// The offer that appears **on the schematic fallback itself** — where the user is looking at a
/// squiggle and wondering why there is no map (`ios-apple-maps-option.md` §4, phase AM-4).
///
/// §4 argues this placement rather than an onboarding fork, and the argument is about *legibility*:
/// at first launch a privacy trade is a coin flip dressed as consent, because the user has no
/// places, trips or workouts whose disclosure they could evaluate. Here they have exactly one, in
/// front of them, and the trade is concrete.
///
/// ⚠⚠ **It offers the election and nothing else — deliberately.** §4's sketch paired it with
/// "download the region", and that half is *not* offered here, for two different reasons:
///
/// - **In Maps it already exists.** `MapsPlaceDetailView`'s "Keep <region> offline" row, with its
///   own size, progress and failure copy, sits on the same screen. A second download button would
///   be a duplicate with less state.
/// - **In Timeline and Fit it would be a false promise.** Neither shell has a region picker, and
///   under route 1 each app has its *own* region store — `*.device.entitlements` carries no App
///   Group (D9, `ios-control-offline-maps.md` §2) — so a region downloaded in Maps does not serve
///   Timeline's heroes on a real device. Sending a user to another app to fix this one would not
///   work, and finding that out costs them a 200 MB download.
///
/// So the honest offer at this moment is the one thing that *is* actionable in this app.
struct BasemapElectionAffordance: View {

    @ObservedObject private var election = MapRendererControl.shared

    var body: some View {
        VStack(spacing: 6) {
            Text("No offline map for this area.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Use Apple Maps here") {
                election.select(.mapKit)
            }
            .font(.caption.weight(.semibold))
            // ⚠ The cost is on screen *before* the tap, not in a confirmation afterwards — the same
            // discipline `MapRendererSection` follows, and the reason this is an offer rather than
            // a nudge. "In this app" is D9: the grant does not travel to the other apps.
            Text("Apple will see which places you open, and when. Applies in this app; you can turn it off in Settings.")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .padding(10)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(12)
    }
}
