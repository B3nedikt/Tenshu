import SwiftUI
import TenshuSdk

/// Tapping a row opens it in whichever app owns it (`control-cross-app-open.md` TT-2a).
///
/// ⚠⚠ **Timeline names no app.** It hands Control the file's `DataId` and Control decides — which
/// is CO-D1 at a second caller, and the thing TT-2 exists to prove. Timeline declares no
/// `LSApplicationQueriesSchemes`, links no viewer, and knows nothing about Drive.
///
/// ⚠ A `nil` path makes the modifier inert rather than tappable: a frame drawn from a colour alone
/// has nothing to open, and a tap that cannot be honoured is worse than none (CO-D7).
private struct OpenInOwningApp: ViewModifier {

    let path: String?

    @State private var refusal: String?

    func body(content: Content) -> some View {
        if let path, !path.isEmpty {
            content
                .contentShape(Rectangle())
                .onTapGesture { open(path) }
                .accessibilityAddTraits(.isButton)
                .alert(
                    "Couldn't open",
                    isPresented: Binding(
                        get: { refusal != nil },
                        set: { if !$0 { refusal = nil } }
                    )
                ) {
                    Button("OK", role: .cancel) { refusal = nil }
                } message: {
                    Text(refusal ?? "")
                }
        } else {
            content
        }
    }

    private func open(_ path: String) {
        Task {
            let outcome = try? await TimelineLocalRuntime.shared.openPhotoInOwningApp(path: path)
            // ⚠⚠ Silence on success is deliberate — the app coming to the front IS the
            // acknowledgement. Every other outcome says something, because a tap that produces
            // nothing and explains nothing is the failure CO-D7 exists to prevent.
            //
            // ⚠ `nil` means the suspend call itself threw, which is not one of CO-D9's reasons —
            // treated as unreachable rather than silently swallowed.
            guard outcome != "opened" else { return }
            refusal = Self.sentence(for: outcome ?? "unreachable")
        }
    }

    /// ⚠⚠ The reason TOKEN, never the proxy's developer message — that names an `appId` the user
    /// has never seen. ⚠ Names no app either: which app opens a photo is Control's resolution.
    private static func sentence(for reason: String) -> String {
        switch reason {
        case "noHandler": return "Nothing on this device opens photos yet."
        case "notInstalledHere": return "The app that opens photos isn't installed on this device."
        case "launchFailed": return "That app didn't open. Try again in a moment."
        case "notPermitted": return "Timeline isn't allowed to open that."
        case "noDelivery": return "Opening apps isn't supported here yet."
        case "unavailable": return "Timeline isn't connected to Control yet."
        case "unreachable": return "Can't reach Control right now."
        // ⚠ A newer proxy naming a reason this build has never heard of. Vague beats silent.
        default: return "That couldn't be opened."
        }
    }
}

extension View {
    /// See `OpenInOwningApp`. ⚠ Inert when `path` is nil or empty.
    func openInOwningApp(path: String?) -> some View {
        modifier(OpenInOwningApp(path: path))
    }
}
