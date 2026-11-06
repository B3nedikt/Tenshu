import SwiftUI

/// What a shipping root knows about Control's provisioning of this app.
enum ControlProvisioning: Equatable {
    /// Not read yet. One frame at launch; rendered as a spinner so the wall never flashes
    /// over an app that is about to be provisioned.
    case checking
    /// Control has not provisioned this app: no realm, no keys, no runtime.
    case absent
    case provisioned
}

/// The root gate of a first-party route-1 app (ADR 0022 §Decision 6): the full-screen
/// *Set Up Tenshu Control* screen until Control has provisioned the app, the app after.
///
/// ⚠⚠ Unprovisioned is a dead end in **every** route-1 app, whatever its screens look like.
/// The banner's case for itself was "the surface stays browsable over cached content", and
/// a route-1 app reads the shared replica only through the runtime that provisioning
/// installs — with none there is no cached content, so the banner sat above screens that
/// were empty by construction and read as an app that had lost its data.
///
/// ⚠ `content` is not built until provisioning is known. The surface swap onto the route-1
/// repositories happens at view-model construction, so a shell built first would hold view
/// models over the not-connected repository.
///
/// The banner stays what a *provisioned* app shows when its runtime reports Control
/// unreachable; this gate does not replace it.
struct ControlSetUpGate<Content: View>: View {
    let provisioning: ControlProvisioning
    /// See `ConnectToControlView`: the host app's name and what it loses without Control.
    let appName: String
    let purpose: LocalizedStringResource
    /// Re-read provisioning after the user returns from Control.
    var onRecheck: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        #if DEBUG
        // `-devShowContentUnprovisioned` (debug builds only): build the app's own root with no
        // provisioning, over the not-connected repositories. For a scripted simulator launch that
        // has to look at chrome — a bar, the sync capsule — on a simulator nothing has set up.
        // ⚠ Never a way to see data: every surface underneath is the honest empty one.
        if ProcessInfo.processInfo.arguments.contains("-devShowContentUnprovisioned") {
            content()
        } else {
            gated
        }
        #else
        gated
        #endif
    }

    @ViewBuilder private var gated: some View {
        switch provisioning {
        case .checking:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .absent:
            ConnectToControlView(appName: appName, purpose: purpose, onRecheck: onRecheck)
        case .provisioned:
            content()
        }
    }
}
