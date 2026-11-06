import Foundation

/// Where a first-party iOS app forwards the user to set up / open Tenshu Control.
///
/// Shared by every route-1 app (ADR 0022). Control is the device's proxy and key
/// custodian, so an app without it has no realm, no keys and nothing to show — the
/// forward is the whole recovery path, not a nicety.
enum Control {
    /// Custom URL scheme opened to reach Control (genesis / join a realm, then back).
    /// `openURL`'s completion reports whether ANY installed build handles it, which is
    /// how the caller learns Control is missing without asking for a permission-gated
    /// `canOpenURL` query.
    static let url = URL(string: "tenshu-control://setup")!

    /// App Store page for Tenshu Control, offered when the deep link reports no
    /// installed build.
    ///
    /// ⚠⚠ **Nil until the real product id is configured**, and that is deliberate.
    /// This was previously a hardcoded `id000000000` placeholder, which opens a broken
    /// App Store page — a failure the user sees and we never do. Nil instead makes the
    /// caller render an honest "not on the App Store yet" state, so the only way to ship
    /// a wrong link is to configure a wrong id.
    ///
    /// Set `TENSHU_CONTROL_APP_STORE_ID` (XcodeGen `settings:`), which lands in the
    /// app's Info.plist as `TenshuControlAppStoreId` — the same build-setting →
    /// Info.plist → Swift path `TenshuSharedKeychainGroup` already uses (ios-signing.md
    /// §2), so there is one mechanism to learn rather than two.
    static let appStoreURL: URL? = {
        let raw = Bundle.main.object(forInfoDictionaryKey: "TenshuControlAppStoreId") as? String
        let id = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        // ⚠ An unset build setting substitutes to the EMPTY string, not to a missing key,
        // so emptiness is the real "unconfigured" signal. The all-digits check additionally
        // rejects a literal `$(TENSHU_CONTROL_APP_STORE_ID)` (substitution never ran) and
        // the old `000000000` placeholder, both of which would otherwise sail through.
        guard !id.isEmpty, id.allSatisfy(\.isNumber), Int(id) ?? 0 > 0 else { return nil }
        return URL(string: "https://apps.apple.com/app/id\(id)")
    }()
}
