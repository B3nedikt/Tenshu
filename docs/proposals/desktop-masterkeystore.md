# Desktop `MasterKeyStore` — Encrypted-at-Rest Secret Persistence + macOS Touch ID / Password Unlock

**Status:** Phase 1 shipped (2026-06-04) — macOS at-rest secret persistence
(`DesktopMasterKeyStore` + login-Keychain wrapping key via the `security` CLI; no rename, no
biometric). Threat-model A21 / T-C-17 landed. **Phase 2 seam landed (2026-06-04)** — Swift
`KeychainHelper` (Touch ID / login-password via `LocalAuthentication`) spawned by
`DesktopBiometricUnlockDelegate`; **dormant until packaged + signed**. **Phase 2 signing rig
(Slice 1, 2026-06-04)** — `KeychainHelper.xcodeproj` + `build.sh`: a loose binary is AMFI-killed
(needs an embedded provisioning profile), so the helper is an Xcode app target; a **free Apple team
suffices** but the first profile must be minted in the Xcode GUI. **Confirmed on-device:** signed
build runs, biometric activates. **Phase 2 Slice 2 (2026-06-05)** — `buildKeychainHelper`/
`embedKeychainHelper` Gradle tasks embed the signed helper in `Control.app/Contents/Helpers/`, and
`DesktopBiometricUnlockDelegate` resolves it **bundle-relative** (no env var, any launch context);
T-C-17 extended. Outstanding: installed-app on-device run + Developer-ID/notarize. Phase 3
(Windows/Linux) outstanding; see §8.

**Origin:** the desktop half of the `MasterKeyStore` abstraction promised in
[`core-preference-service.md`](core-preference-service.md) §10.4 (which §10.4 calls "a separate
doc"), surfaced concretely by the pairing investigation (branch `investigate/pairing-break`).
That work shipped `DesktopSecureStorageDelegate`, which persists only the **non-secret** device
strings (`DEVICE_ID`, `LAST_SYNC_TIMESTAMP`) and keeps every master-key secret **in-memory** — so
desktop must re-pair on every restart. This proposal closes that gap: persist the secrets
**encrypted at rest**, and on macOS gate them behind **Touch ID / the login password** using the
existing wrap-key architecture.

See also: [`tech-debt.md`](../tech-debt.md) §"Desktop `SecureStorageDelegate` — Persist Master-Key
Secrets", §"SecureStorageDelegate — Split + Drop Deprecated `EncryptedSharedPreferences`";
[`onboarding-low-friction.md`](onboarding-low-friction.md) (the "master key wrapped in the platform
secure element" vision); [`desktop-macos-integration.md`](desktop-macos-integration.md) (the native
Swift bridge this builds on); [`how-to/macos-signing.md`](../how-to/macos-signing.md).

---

## 1. Problem & scope

Desktop binds `InMemorySecureStorageDelegate` for the *secret* bucket (now `DesktopSecureStorageDelegate`
with the same in-memory secret behaviour). The secrets are:

- `RECOVERY_PHRASE` (asset **A9**), `JOINER_PAYLOAD`, `OWNER_ID`
- `UNLOCK_WRAPPER` / `BIOMETRIC_WRAPPER` — the master-key payload encrypted under a passphrase- or
  biometric-derived wrap key (wrappers over **A1**/derived volume+identity keys, **A2**)

Because they live in memory only, a restart loses the realm identity. Android persists them via
`AndroidSecureStorageDelegate` (deprecated `EncryptedSharedPreferences`, AndroidKeyStore-backed).
Desktop has no equivalent.

**In scope:** a desktop `MasterKeyStore` backend that persists these secrets encrypted at rest, and
a macOS biometric/password unlock path. **Out of scope:** the Android `EncryptedSharedPreferences →
AndroidKeyStore + DataStore` rebuild (that's the sibling tech-debt entry / §10.4), and the
shared-realm master keys / rotation (A12/A13, [`tenshu-rei.md`](tenshu-rei.md) — a *different* "master key").

The user-facing question this answers: **"can I unlock with my fingerprint or my laptop password,
like other Mac apps?"** — yes; see §4.

---

## 2. Background: the wrap-key indirection already exists

We do **not** need new crypto. The unlock model is already built (Android biometric track,
`project_android_biometric_unlock_track`):

```
                       enroll                                   setUpBiometricUnlock(wrapKey)
  platform secure element  ──────────►  wrapKey (32 B)  ──────────────────────────────────►  BIOMETRIC_WRAPPER
  (gated by biometric/PIN)             (ephemeral)          encrypt(masterSecretPayload, wrapKey)   (ciphertext at rest)

                       unwrap (biometric prompt)                unlockBiometric(wrapKey)
  platform secure element  ──────────►  wrapKey  ──────────────────────────────────────────►  masterSecretPayload
```

- `BiometricUnlockDelegate` (`commonMain`): `isAvailable()`, `hasEnrolled()`,
  `enrollWrapKey(wrapKey, host, copy): EnrollResult`, `unwrapWrapKey(host, copy): UnwrapResult`,
  `clear()`. The platform implementation owns a **hardware-gated key** that protects the 32-byte
  `wrapKey`. Android = AndroidKeyStore (`BIOMETRIC_STRONG`-only). Desktop = inert stub today
  (`DesktopBiometricUnlockDelegate`, `isAvailable=false`).
- `SyncManager.setUpBiometricUnlock(wrapKey)` / `unlockBiometric(wrapKey)` /
  `setUpLocalUnlock(passphrase)` (Argon2id → wrap key) / `unlockLocal(passphrase)` — store/consume
  `BIOMETRIC_WRAPPER` / `UNLOCK_WRAPPER` ciphertext in the secret bucket.

So this proposal is two composable pieces:

1. **Storage (§3):** a `MasterKeyStore` backend that persists the secret bucket **encrypted at rest**.
2. **Biometric wrap-key source (§4):** a macOS-Keychain-backed `BiometricUnlockDelegate` that gates
   the `wrapKey` behind Touch ID / password — the piece that gives the "standard Mac app" UX.

They are independent: (1) alone gives at-rest persistence with no prompt; (1)+(2) gives the gated
unlock. Either can ship first.

---

## 3. Storage layer — `MasterKeyStore` desktop backend

Two ways to protect the secret bucket on disk; we offer a layered default.

- **Baseline — OS credential store bound to the login session.** macOS **login Keychain**, Windows
  **DPAPI** (`CryptProtectData`, user scope), Linux **libsecret / GNOME Keyring**. Encryption-at-rest
  for free; unlocked while the user is logged in (no per-access prompt). This is the "saved password"
  behaviour most apps use. Protects against offline disk theft / another OS user; **not** against
  malware running as the logged-in user.
- **Stronger — per-access biometric/password gate.** macOS **Secure Enclave** key with a
  `kSecAccessControl` ACL (`.userPresence` / `.biometryCurrentSet`); a prompt on every unwrap. This
  is the `BIOMETRIC_WRAPPER` path (§4).

The `MasterKeyStore` interface mirrors the existing secret surface (`storeSecret` / `getSecret` /
`remove`, per §10.4's "secrets only" shrink). The desktop backend persists each secret as a
credential-store item (baseline) or stores a **single wrapping key** in the credential store and
keeps the secret blobs in a file encrypted under it (fewer keychain items, one prompt). The latter
composes cleanly with §4: the wrapping key *is* the biometric-gated `wrapKey`.

---

## 4. The macOS path — Touch ID / login password (answers the question)

**Yes, this gives the standard Mac-app behaviour.** macOS exposes exactly this via
`LocalAuthentication` + `Security.framework`:

- A Keychain item / Secure-Enclave key created with `SecAccessControlCreateWithFlags(...,
  kSecAccessControlBiometryCurrentSet | kSecAccessControlOr | kSecAccessControlDevicePasscode, ...)`
  — or the simpler `.userPresence` — prompts for **Touch ID and automatically falls back to the
  login password** (`LAPolicy.deviceOwnerAuthentication`). No custom UI; the OS sheet is the
  "standard behaviour from other apps."
- The gated key protects the 32-byte `wrapKey`. `enrollWrapKey` stores it; `unwrapWrapKey` triggers
  the OS prompt and returns it. This is a 1:1 fit for the existing `BiometricUnlockDelegate` contract
  and `UnwrapResult` (`Success` / `CancelledByUser` / `KeyInvalidated` (re-enroll changed the
  biometric set) / `NotAvailable`).

**The JVM cannot call these APIs**, so the desktop `BiometricUnlockDelegate` routes through the
**native Swift bridge** already established by the macOS integration
([`desktop-macos-integration.md`](desktop-macos-integration.md)): extend `NativeBridgeServer` with
additive keychain methods (`PROTOCOL_VERSION` stays `1`, additive precedent), Swift-side implemented
against `Security.framework` + `LocalAuthentication`:

| Bridge method | Swift impl | Notes |
|---|---|---|
| `secretStore(key, blobB64, accessControl)` | `SecItemAdd` with `kSecAttrAccessControl` | `accessControl ∈ {loginKeychain, userPresence}` |
| `secretLoad(key)` → blob \| `cancelled` \| `not_found` \| `invalidated` | `SecItemCopyMatching` with an `LAContext` | the `userPresence` item triggers the Touch ID / password sheet |
| `secretDelete(key)` | `SecItemDelete` | idempotent |

The biometric prompt **must be driven from the GUI process**, not the headless LaunchAgent daemon —
the daemon/window split means the daemon has no session to present `LAContext` UI. The unlock flow
already lives in the GUI (`LockedScreen` / `SetupUnlockScreen`); the bridge call is issued from there.

Macs **without Touch ID** still work — `deviceOwnerAuthentication` falls back to the login password.
`isAvailable()` returns true whenever `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)` holds.

---

## 5. Other desktops + no-secure-hardware fallback

- **Windows:** DPAPI baseline; **Windows Hello** (`KeyCredentialManager`) for the gated path
  (Phase 3). Same bridge shape via a Windows-native helper — out of scope until a Windows app exists.
- **Linux:** libsecret / GNOME Keyring baseline; no standard per-access biometric — gated path is
  password-via-Keyring at best (Phase 3).
- **No backend at all** (e.g. headless CI, a Linux box with no keyring): fall back to the current
  in-memory secret behaviour (re-pair after restart). `MasterKeyStore.isPersistent()` reports this so
  the UI can set expectations.

Passphrase unlock (`setUpLocalUnlock`, Argon2id → `UNLOCK_WRAPPER`) already works cross-platform with
**no** OS secret store and is the universal floor — the `MasterKeyStore` persistence makes its
ciphertext survive restart even where no biometric exists.

---

## 6. Prerequisites & risks

- **Codesigning.** Secure-Enclave key generation and Keychain ACLs need a **stably-signed** app with
  a `keychain-access-groups` entitlement and (for SE) hardened runtime — *not* the ad-hoc
  `codesign --sign -` used in dev today ([`macos-signing.md`](../how-to/macos-signing.md) §2c
  scaffolds `control.entitlements`). The **login-Keychain baseline (§3) works under ad-hoc/dev
  signing**; the SE/biometric path (§4) is gated on a real signing identity. Sequence the phases
  accordingly.
- **Keychain item identity churn.** An item is bound to the signing identity / access group; a
  signature change can orphan items → treat `secretLoad → not_found/invalidated` as "re-enroll",
  exactly like Android's `KeyInvalidated`.
- **Headless daemon.** See §4 — biometric prompts only from the GUI process.

---

## 7. Threat-model impact (amendment required — House Rule #2)

This **changes the at-rest exposure of A1 / A2 / A9 on desktop** (previously in-memory-only). It
therefore needs a `docs/sync/threat-model.md` amendment in the implementing PR:

- **New asset** (next free id, ~**A21**): *Desktop At-Rest Master-Key Store* — the persisted,
  encrypted secret bucket + its OS-credential-store-held wrapping key.
- **Exposure delta:** on desktop, A1/A2/A9-derived material now lives on disk as ciphertext whose
  key is held by the OS credential store (login-bound baseline) or Secure Enclave (gated). State the
  residual risk: a **local attacker running as the logged-in user** can, with the baseline, read the
  login-Keychain-protected blob (same risk surface as every password manager's "unlocked" state);
  the SE/`userPresence` path raises this to a per-access prompt.
- **Threats:** extend the desktop-client group (T-C-1x) with disk-theft (mitigated), logged-in-user
  malware (baseline: exposed; gated: prompt), and signature-swap orphaning (availability, not
  confidentiality).
- House Rule #1 (relay sees ciphertext only) is unaffected — this is purely device-local at rest.

---

## 8. Phasing

1. **Phase 1 — desktop baseline at-rest persistence (macOS, no biometric). ✅ Shipped 2026-06-04.**
   `DesktopMasterKeyStore` encrypts the secret bucket (`AES-256-GCM`, `secrets.enc`) under a single
   wrapping key held in the **macOS login Keychain** (via the `security` CLI — works under
   ad-hoc/dev signing); `WrappingKeyStore` abstracts the key source (`KeychainWrappingKeyStore` on
   macOS, `NoWrappingKeyStore` ⇒ in-memory fallback elsewhere). Closes re-pair-after-restart on
   macOS. Threat-model amendment (§7: A21 / T-C-17) landed here — the at-rest exposure changes at
   this phase (proposal §9 Q3). Composes with the existing `DesktopSecureStorageDelegate` non-secret
   half. **Deviations from the original Phase-1 plan (deliberate, per maintainer):** the
   `SecureStorageDelegate → MasterKeyStore` *rename* and the `isPersistent()` *interface* surface are
   **deferred** (no consumer yet; `DesktopMasterKeyStore.isPersistent()` exists internally), and the
   DPAPI/libsecret backends move to Phase 3 — only macOS is verifiable today.
2. **Phase 2 — macOS Touch ID / password gate. ◑ Seam landed 2026-06-04 (on-device verify +
   signing deferred).** A Swift `KeychainHelper` (`control-desktop/macos/KeychainHelper/`) performs
   the `Security.framework` + `LocalAuthentication` ops against a `.userPresence`-gated
   data-protection-keychain item (Touch ID, falling back to the login password via
   `deviceOwnerAuthentication`). `DesktopBiometricUnlockDelegate` **spawns** that helper per op and
   maps its status tokens to `EnrollResult`/`UnwrapResult`; `rememberBiometricPromptHost()` now
   returns non-null so the UI's `biometricAvailable && promptHost != null` gate can pass.
   - **⚠ Design deviation from the §4 wording (deliberate):** the helper is **spawned by the JVM**,
     **not** a `NativeBridgeServer` method. The bridge is *daemon-as-server* and the unlock GUI runs
     in that same daemon JVM, so there is no server→client path to reach a Swift process over the
     bridge; a method there would be the daemon calling its own socket. Spawning mirrors Phase 1's
     `/usr/bin/security` shell-out and lets the helper present the OS auth sheet from a foreground
     child process.
   - **Dormant until packaged + signed.** The delegate resolves the helper from
     `-Dtenshu.keychainHelper` / `$TENSHU_KEYCHAIN_HELPER`, **then** (Slice 2) bundle-relative inside
     the packaged `Control.app`; with none found, `isAvailable()=false` and desktop stays
     passphrase-only (no regression).
   - **⚠ Signing finding (2026-06-04): a loose `swiftc`+`codesign` helper does NOT work.** The
     data-protection keychain item carries a *restricted* entitlement, so AMFI `SIGKILL`s a
     profile-less binary on launch (exit 137, verified). The helper must live in an `.app` with an
     **embedded provisioning profile**. On a **free (personal) team** only Xcode can mint that
     profile (CLI `xcodebuild -allowProvisioningUpdates` errors `No Account for Team` — the first
     build must be the Xcode GUI; thereafter the CLI signs offline against it, **without** that flag).
     A free team *is* enough (App Groups, not this, is the paid gate).
   - **Slice 1 ✅ — Xcode signing rig.** `control-desktop/macos/KeychainHelper/KeychainHelper.xcodeproj`
     (minimal app target wrapping `main.swift`) + `build.sh` + comment-free
     `keychain-helper.entitlements` (the old XML comment broke `codesign`'s AMFI parser).
     **On-device confirmed by the maintainer:** with the GUI-minted profile, the signed `.app` runs
     and biometric activates in Settings (the free-team path works end-to-end).
   - **Slice 2 ✅ (this PR) — installed-app wiring.** `buildKeychainHelper` + `embedKeychainHelper`
     Gradle tasks (mirror `buildTrayAgent`/`embedTrayAgent`): xcodebuild the helper, embed the whole
     signed `keychain-helper.app` into `Control.app/Contents/Helpers/` (never re-signed → entitlement
     preserved). `DesktopBiometricUnlockDelegate.bundledKeychainHelperFor` resolves it relative to the
     running launcher (mirrors `LaunchAgentManager.trayBinaryFor`), so biometric lights up with **no
     env var** in every launch context (tray cold-launch, login LaunchAgent). Threat-model **T-C-17
     extended** (the per-access gate is now built). **Outstanding (on-device, gated on packaging):**
     run the embedded installed app + the Touch ID round-trip; the **Developer ID + notarization**
     inside-out signing mode (no profile needed) for distribution.
   - **Slice 2a ✅ — `has-enrolled` lockout fix (on-device, 2026-06-05).** On-device testing of the
     installed app surfaced a **lockout**: the helper's `has-enrolled` queried with
     `kSecUseAuthenticationUISkip`, which *skips* `.userPresence` items → `errSecItemNotFound` → a
     false "no". So `hasEnrolled()` always reported false even right after a successful enroll →
     Settings showed "not enabled" and the lock screen offered **passphrase** (which a biometric-only
     user doesn't have). Fix: existence-probe via `kSecReturnAttributes` (no data, no auth UI key) —
     finds the item without prompting and without the skip false-negative. Verified end-to-end on a
     free team: `enroll → ok`, `has-enrolled → yes` (silent), `unwrap → Touch ID → exact wrap-key`.
     ⚠ Note this class of bug isn't caught by the Kotlin fake-helper tests (real-keychain behavior) —
     on-device-only. ✅ Robustness follow-up **resolved** by the passphrase floor (§9, 2026-06-05):
     biometric can no longer be the sole credential, so a lost keychain item never means a permanent
     lockout — the recovery passphrase is always there.
   - **This is the phase that delivers the user's "fingerprint or laptop password" UX** — proven on a
     free team; production needs the Developer-ID/notarize packaging.
3. **Phase 3 — Windows Hello / Linux equivalents + DPAPI/libsecret baseline** (when those apps exist).

---

## 9. Open questions

- ~~One keychain item per secret, or one wrapping key + a file blob?~~ **Decided (Phase 1):** one
  wrapping key in the Keychain + an `AES-256-GCM` blob file (`DesktopMasterKeyStore`).
- Re-key on signing-identity change: silently re-enroll vs. force a recovery-phrase re-unlock?
  (Phase 1 takes the silent path — an undecryptable blob reads as empty → re-pair.)
- ~~Does Phase 1's baseline need its own threat-model note, or ride the Phase 2 amendment?~~
  **Decided:** at-rest exposure changes at Phase 1, so asset **A21** + threat **T-C-17** landed with
  Phase 1; Phase 2 *extends* T-C-17 with the per-access prompt.
- ~~`UNLOCK_WRAPPER` is the universal floor — do we *require* a passphrase even when biometric is
  set up, as the recovery path if the biometric credential is invalidated?~~ **Decided + implemented
  (2026-06-05): yes — passphrase floor.** New atomic `SyncManager.setUpUnlock(passphrase,
  biometricWrapKey?)` wraps both from one plaintext read (passphrase mandatory ⇒ biometric can never
  be the sole credential); standalone `setUpBiometricUnlock` now refuses when no `UNLOCK_WRAPPER`
  exists (Settings "add biometric later" backstop). Setup UI (`SetupUnlockStep` + new-user flow) is a
  credential **choice** → biometric enrolls, then *requires* a recovery passphrase shown with
  "only way back in if biometric breaks" framing; Settings gates the toggle on
  `UnlockStatus.hasPassphrase`. Cross-platform (Android shares the VM/UI — on-device glance pending).
- **iOS `MasterKeyStore` backend (new, 2026-06-21).** Control-on-iOS as a key-holding realm device
  ([`ios-proxy-architecture.md`](ios-proxy-architecture.md) §11, O6) needs the iOS analog of the
  desktop backend: a wrap key in the **Secure Enclave** + an `AES-256-GCM` blob in the Keychain,
  unlock gated by **Face ID / Touch ID / passcode** (`LocalAuthentication`), with the same
  passphrase-floor invariant (§9 above — biometric can never be the sole credential, so the
  recovery passphrase remains the way back in). Open: whether the iOS backend reuses the desktop
  helper-process shape (it should not — `LocalAuthentication` is in-process on iOS, no Swift helper
  needed) and how it shares the `SetupUnlockStep` VM with the SwiftUI onboarding (the §11 O7
  genesis flow). Out of scope until Phase 6 (Control-on-iOS) is picked up; flagged here so the
  `MasterKeyStore` abstraction is designed to admit an iOS `actual` rather than retrofitted.

---

## 10. Implementation Notes for Claude Code

Canonical handoff — file-level map.

| Area | File(s) |
|---|---|
| Secret surface → `MasterKeyStore` rename | `core-common/.../delegate/SecureStorageDelegate.kt` (split per §10.4), `sync-service-proxy/.../SyncManagerImpl.kt` (every `storeSecret`/`getSecret` call site) |
| Desktop backend (Phase 1) | new `control-shared/src/desktopMain/.../data/DesktopMasterKeyStore.kt`; wire in `control-shared/src/desktopMain/.../di/PlatformProxyModule.kt` (replaces the in-memory secret half of `DesktopSecureStorageDelegate`) |
| Biometric delegate (Phase 2) ✅ | `control-shared/src/desktopMain/.../data/DesktopBiometricUnlockDelegate.kt` — helper-backed (spawns the Swift helper, maps tokens); `rememberBiometricPromptHost()` non-null; **`bundledKeychainHelperFor(execPath)`** resolves the embedded helper bundle-relative (Slice 2); `DesktopBiometricUnlockDelegateTest` |
| macOS keychain helper (Phase 2) ✅ | `control-desktop/macos/KeychainHelper/main.swift` (+ `README.md`) — `Security.framework` + `LocalAuthentication`, `.userPresence`-gated data-protection-keychain item. **Spawned by the JVM, not a `NativeBridgeServer` method** (see §8 Phase 2 deviation note) |
| Helper signing rig (Phase 2, Slice 1) ✅ | `control-desktop/macos/KeychainHelper/KeychainHelper.xcodeproj` + `build.sh` (xcodebuild, GUI-mint profile first; no `-allowProvisioningUpdates`) — the helper needs an embedded profile, so it's an Xcode app target (free team OK). `.gitignore` for `build/`. |
| Installed-app embedding (Phase 2, Slice 2) ✅ | `control-desktop/build.gradle.kts` `buildKeychainHelper` + `embedKeychainHelper` (mirror `buildTrayAgent`/`embedTrayAgent`) → embed the signed `.app` at `Control.app/Contents/Helpers/keychain-helper.app`, never re-signed. Run `:control-desktop:embedKeychainHelper -Ptenshu.teamId=…`. |
| Entitlements / signing | `control-desktop/macos/entitlements/keychain-helper.entitlements` (`keychain-access-groups` via `$(AppIdentifierPrefix)`, comment-free — a comment breaks `codesign`); `control.entitlements`; `macos-signing.md` §3b |
| Threat model | `docs/sync/threat-model.md` — new asset (~A21) + T-C-1x extensions (Phase 2; possibly a short Phase 1 at-rest note) |
| Crypto (reuse, no change) | `SyncManager.setUpBiometricUnlock` / `unlockBiometric` / `setUpLocalUnlock`; `BiometricUnlockDelegate` contract + `EnrollResult` / `UnwrapResult` |

No new wire or relay surface. The non-secret `LocalStore` half already shipped as
`DesktopSecureStorageDelegate`.
