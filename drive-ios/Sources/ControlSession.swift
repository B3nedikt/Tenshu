import Foundation
import Security

// Onboarding for a first-party iOS app is a forward-to-Control handoff, not an
// in-app QR/SAS pairing ceremony (proposal §11.6). The shipping gate (`RootView`)
// routes on the route-1 key material Control publishes (ios-drive-parity.md P1d),
// not on this item.
//
// ⚠ What is left here is the DEV_HARNESS dev stub: `provisionDevStub` writes a
// handle into the old C3 `epc-session` coordinates so `RootView` can forward into
// Files over the fakes (the XCUITests' `DRIVE_DEV_PROVISION_CONTROL=1`). Control
// stopped minting its presence token into the item on 2026-11-05; the shipping
// build never reads it.

/// A handle to a Control-provisioned EPC session, read from the shared Keychain.
struct ControlSession: Equatable {
    /// Opaque handle — a route tag (`route1-local`, `epc-live`) or the DEV_HARNESS stub.
    let handle: String
}

enum ControlSessionStore {

    /// Shared Keychain access group — the same-Team-ID channel §11.6 names. A
    /// signed build adds the matching `keychain-access-groups` entitlement; the
    /// unsigned simulator build has no entitlement, so reads/writes transparently
    /// fall back to the default keychain (which still round-trips the dev stub).
    ///
    /// On a signed **device** build the effective group is Team-prefixed
    /// (`<TeamID>.app.tenshu.shared`, ios-signing.md §2); the Info.plist carries
    /// that value under `TenshuSharedKeychainGroup` (from the
    /// `TENSHU_SHARED_KEYCHAIN_GROUP` build setting). Bare fallback = ad-hoc sim.
    static let accessGroup: String = {
        if let group = Bundle.main.object(forInfoDictionaryKey: "TenshuSharedKeychainGroup") as? String,
           !group.isEmpty, !group.hasPrefix("$") {
            return group
        }
        return "app.tenshu.shared"
    }()
    private static let service = "app.tenshu.control.session"
    private static let account = "epc-session"

    // MARK: Read (DEV_HARNESS callers only)

    /// Whatever sits at the `epc-session` coordinates — the dev stub, or a "Connect an
    /// app" invite while Control's sheet is open — or `nil`. Only DEV_HARNESS code calls
    /// this; it also consults a local dev fallback (the unsigned simulator has no Keychain
    /// entitlement, so the stub can't always ride the real Keychain there).
    static func current() -> ControlSession? {
        if let session = keychainRead() { return session }
        #if DEV_HARNESS
        if let handle = UserDefaults.standard.string(forKey: devFallbackKey) {
            return ControlSession(handle: handle)
        }
        #endif
        return nil
    }

    private static func keychainRead() -> ControlSession? {
        let result = withAccessGroupFallback { group -> (OSStatus, CFTypeRef?) in
            var query = baseQuery(group)
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var item: CFTypeRef?
            return (SecItemCopyMatching(query as CFDictionary, &item), item)
        }
        guard result.0 == errSecSuccess,
              let data = result.1 as? Data,
              let handle = String(data: data, encoding: .utf8) else { return nil }
        return ControlSession(handle: handle)
    }

    #if DEV_HARNESS
    // MARK: Dev stub (replaced by real Control provisioning at Phase 6e)

    private static let devFallbackKey = "app.tenshu.dev.control-session"

    /// Write a stub session so the forward-to-Control onboarding is exercisable
    /// before Control-on-iOS exists. Best-effort to the real shared Keychain, plus a
    /// local fallback that always works on the unsigned simulator. DEV_HARNESS-only.
    @discardableResult
    static func provisionDevStub(handle: String = "dev-stub-session") -> Bool {
        let data = Data(handle.utf8)
        _ = withAccessGroupFallback { group -> (OSStatus, CFTypeRef?) in
            var query = baseQuery(group)
            SecItemDelete(query as CFDictionary) // idempotent: replace any prior item
            query[kSecValueData as String] = data
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            return (SecItemAdd(query as CFDictionary, nil), nil)
        }
        UserDefaults.standard.set(handle, forKey: devFallbackKey)
        return true
    }

    /// Clear the stub session — to re-exercise the absent path.
    static func clearDevStub() {
        _ = withAccessGroupFallback { group -> (OSStatus, CFTypeRef?) in
            (SecItemDelete(baseQuery(group) as CFDictionary), nil)
        }
        UserDefaults.standard.removeObject(forKey: devFallbackKey)
    }
    #endif

    // MARK: Keychain plumbing

    private static func baseQuery(_ group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    /// Run a keychain op with the shared access group; on `errSecMissingEntitlement`
    /// (the unsigned-simulator case) retry against the default keychain so reads and
    /// writes stay consistent. Real signed builds keep the access group throughout.
    private static func withAccessGroupFallback(
        _ op: (_ group: String?) -> (OSStatus, CFTypeRef?)
    ) -> (OSStatus, CFTypeRef?) {
        let withGroup = op(accessGroup)
        if withGroup.0 == errSecMissingEntitlement {
            return op(nil)
        }
        return withGroup
    }
}

// `enum Control` (the deep link + App Store URL) moved to the shared
// `ios-onboarding/Sources/ControlLink.swift` when Fit became the second consumer —
// see ADR 0022. The App Store id is now a build setting rather than a hardcoded
// placeholder id.
