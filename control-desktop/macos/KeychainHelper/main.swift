// Tenshu macOS Keychain helper (desktop MasterKeyStore biometric unlock).
//
// docs/proposals/desktop-masterkeystore.md §4: the desktop biometric unlock path.
// The JVM cannot call Security.framework / LocalAuthentication, so
// `DesktopBiometricUnlockDelegate` spawns this small Swift binary to gate the
// 32-byte unlock wrap-key behind Touch ID / the login password — the same way
// the at-rest wrapping-key store shells out to `/usr/bin/security`.
//
// Why a spawned helper and not a NativeBridgeServer method (proposal §4 wording):
// the bridge is daemon-as-server and the unlock GUI runs in that same daemon
// JVM, so there is no server→client call path to reach a Swift process over the
// bridge. A spawned helper is the simplest fit and presents the OS auth sheet
// from a foreground child process (like `sudo` / `security` prompts).
//
// Protocol — one status token on stdout (exit 0 unless it crashes); the JVM keys
// off the token, never the exit code:
//   is-available             -> "yes" | "no"
//   has-enrolled             -> "yes" | "no"
//   enroll  [reason]         -> stdin = base64(wrapKey); -> "ok" | "cancelled" | "unavailable" | "failure:<msg>"
//   unwrap  [reason]         -> "ok:<base64>" | "cancelled" | "not_found" | "invalidated" | "unavailable" | "failure:<msg>"
//   clear                    -> "ok"
//   authenticate [reason]    -> "ok" | "cancelled" | "unavailable" | "failure:<msg>"
//
// `authenticate` is the soft app-lock gate (docs/proposals/control-keep-device-unlocked.md
// §4.2, "Require unlock" on desktop): a keyless Touch ID / login-password check via
// `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`. It touches NO keychain item, so —
// unlike enroll/unwrap — it needs no `keychain-access-groups` entitlement and works under
// ad-hoc/dev signing. It is a UI barrier only; the realm keys stay headless.
//
// The keychain item is a userPresence-gated generic password in the
// data-protection keychain — so unlock = Touch ID OR the login password, the
// "standard Mac app" behaviour. Storing/reading an access-control item needs a
// signed binary with the `keychain-access-groups` entitlement + hardened
// runtime; under ad-hoc/dev signing SecItemAdd returns errSecMissingEntitlement
// and enroll reports "failure" (the delegate then stays on passphrase).
//
// Build:  swiftc -O -o keychain-helper main.swift
// Sign:   codesign --force --options runtime \
//           --entitlements ../entitlements/keychain-helper.entitlements \
//           --sign "<Apple Development identity>" keychain-helper
// See docs/how-to/macos-signing.md §3b.

import Foundation
import LocalAuthentication
import Security

private let SERVICE = "app.tenshu.control.biometric-unlock"
private let ACCOUNT = "wrap-key"

private func emit(_ token: String) {
    print(token)
}

/// Base query identifying the single wrap-key item (data-protection keychain).
private func baseQuery() -> [String: Any] {
    [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: SERVICE,
        kSecAttrAccount as String: ACCOUNT,
        kSecUseDataProtectionKeychain as String: true,
    ]
}

private func authContext(_ reason: String) -> LAContext {
    let ctx = LAContext()
    ctx.localizedReason = reason
    return ctx
}

private func isAvailable() {
    var error: NSError?
    let ok = LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    emit(ok ? "yes" : "no")
}

/// Existence probe that must NOT prompt and must NOT false-negative.
///
/// Query attributes only (`kSecReturnAttributes`, no `kSecReturnData`) AND pass an
/// `LAContext` with `interactionNotAllowed = true`. This second part is the crux:
/// with the default UI policy (`kSecUseAuthenticationUIAllow`), macOS may decide a
/// `.userPresence` item needs Touch ID and **present the auth sheet just to answer
/// "does it exist?"** — a phantom first prompt on the lock screen, before the real
/// `unwrap` prompt. `interactionNotAllowed` makes the query *fail*
/// with `errSecInteractionNotAllowed` instead of prompting.
///
/// We deliberately do NOT use `kSecUseAuthenticationUISkip` — that flag *skips*
/// items requiring user presence, returning `errSecItemNotFound` for an item that
/// actually exists (a false "no" → passphrase-only lockout).
///
/// Result mapping: `errSecItemNotFound` ⇒ absent ("no"); anything else — a clean
/// success, or `errSecInteractionNotAllowed` ("exists but would need auth to read")
/// — ⇒ present ("yes"). Neither path prompts.
private func hasEnrolled() {
    let ctx = LAContext()
    ctx.interactionNotAllowed = true

    var query = baseQuery()
    query[kSecReturnAttributes as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    query[kSecUseAuthenticationContext as String] = ctx

    let status = SecItemCopyMatching(query as CFDictionary, nil)
    emit(status == errSecItemNotFound ? "no" : "yes")
}

private func enroll(reason: String) {
    let stdin = FileHandle.standardInput.readDataToEndOfFile()
    guard
        let b64 = String(data: stdin, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
        let keyData = Data(base64Encoded: b64)
    else {
        emit("failure:invalid wrap-key on stdin")
        return
    }

    var acError: Unmanaged<CFError>?
    guard let access = SecAccessControlCreateWithFlags(
        kCFAllocatorDefault,
        kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        .userPresence,
        &acError
    ) else {
        emit("failure:access control: \(acError.map { String(describing: $0.takeRetainedValue()) } ?? "unknown")")
        return
    }

    // Fresh start: drop any prior item so re-enroll always overwrites cleanly.
    SecItemDelete(baseQuery() as CFDictionary)

    var add = baseQuery()
    add[kSecValueData as String] = keyData
    add[kSecAttrAccessControl as String] = access
    add[kSecUseAuthenticationContext as String] = authContext(reason)

    let status = SecItemAdd(add as CFDictionary, nil)
    switch status {
    case errSecSuccess:
        emit("ok")
    case errSecUserCanceled:
        emit("cancelled")
    case errSecNotAvailable:
        emit("unavailable")
    default:
        emit("failure:\(secErrorMessage(status))")
    }
}

private func unwrap(reason: String) {
    var query = baseQuery()
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    query[kSecUseAuthenticationContext as String] = authContext(reason)

    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    switch status {
    case errSecSuccess:
        guard let data = item as? Data else { emit("failure:no data"); return }
        emit("ok:\(data.base64EncodedString())")
    case errSecUserCanceled:
        emit("cancelled")
    case errSecItemNotFound:
        emit("not_found")
    case errSecAuthFailed, errSecInvalidOwnerEdit:
        // The gated item no longer matches the current auth set.
        emit("invalidated")
    case errSecNotAvailable:
        emit("unavailable")
    default:
        emit("failure:\(secErrorMessage(status))")
    }
}

private func clear() {
    SecItemDelete(baseQuery() as CFDictionary) // idempotent — ignore not-found
    emit("ok")
}

/// Soft app-lock gate: a keyless Touch ID / login-password check. Presents the OS auth
/// sheet via `evaluatePolicy(.deviceOwnerAuthentication)` (biometry OR passcode), touching
/// no keychain item — so it needs no entitlement and works under ad-hoc signing. The
/// callback-based API is bridged to the helper's one-shot CLI shape with a semaphore.
private func authenticate(reason: String) {
    let ctx = LAContext()
    var error: NSError?
    guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
        emit("unavailable")
        return
    }
    let sem = DispatchSemaphore(value: 0)
    var token = "failure:unknown"
    ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, evalError in
        if success {
            token = "ok"
        } else if let e = evalError as NSError? {
            switch e.code {
            case LAError.userCancel.rawValue, LAError.appCancel.rawValue,
                 LAError.systemCancel.rawValue, LAError.userFallback.rawValue:
                token = "cancelled"
            default:
                token = "failure:\(e.localizedDescription)"
            }
        }
        sem.signal()
    }
    sem.wait()
    emit(token)
}

private func secErrorMessage(_ status: OSStatus) -> String {
    (SecCopyErrorMessageString(status, nil) as String?) ?? "OSStatus \(status)"
}

// MARK: - Entry point

let args = CommandLine.arguments
let command = args.count > 1 ? args[1] : ""
let reason = args.count > 2 ? args[2] : "verify it's you"

switch command {
case "is-available": isAvailable()
case "has-enrolled": hasEnrolled()
case "enroll": enroll(reason: reason)
case "unwrap": unwrap(reason: reason)
case "clear": clear()
case "authenticate": authenticate(reason: reason)
default:
    FileHandle.standardError.write(Data("unknown command: \(command)\n".utf8))
    exit(2)
}
