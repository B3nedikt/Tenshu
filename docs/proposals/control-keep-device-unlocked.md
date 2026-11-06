# Headless realm autoload by default (+ an expert "Require unlock" lock)

Status: **v0.7 (2026-08-26).** Design + threat-model delta. **Ph1 + Ph1.1b + Ph-hard + Ph-desktop + Ph-ios shipped; Ph1/Ph-hard/Ph-desktop/Ph-ios on-device verified, Ph1.1b not yet.** ⚠⚠ **Ph-drive is WITHDRAWN** and replaced by **Ph-reauth** — the soft lock is a *control-plane* curtain, not data-at-view protection; see the §6.1 amendment for the reasoning and for what a platform-wide lock would actually cost.

> **Ph1.1 landed (PR A):** the headless-onboarding default + threat-model A1 amendment (T-K-06).
> `NewUserViewModel.onUseDeviceProtection()` creates the realm headless (no `setUpUnlock`); the
> passphrase/biometric lock is now an expert opt-in (`onShowAdvancedUnlock`) behind the
> "Add a passphrase for extra security" affordance on the new-user screen's device-protection intro.
> ⚠⚠ **The post-pairing / post-recovery entry was NOT converted with it, and this note used to claim
> it needed no conversion** — see Ph1.1b. The StrongBox hardening (Ph1.4 / Q2) shipped earlier, with the Ph1 groundwork.
>
> **Ph1.1b landed: the joiner and recovery paths get the same headless default.** ⚠⚠ Ph1.1's own note
> reasoned that *"`SetupUnlockViewModel.onSkip()` already left the plaintext (headless) path, so no
> change was needed there"* — **true of the outcome, false of the ask**. §4.1 requires the floor to be
> optional in `SetupUnlockViewModel` *and* `NewUserViewModel`; only genesis got the device-protection
> intro. A user pairing a second device still landed on *"Lock Tenshu when you're not using it"* with
> biometric/passphrase as the two prominent options and headless demoted to a *"Skip"* text button —
> and because the passphrase floor routes biometric → passphrase entry, choosing the *frictionless*
> option there still ended in a mandatory passphrase and a sealed realm. Exactly the default v0.2
> flipped away from, surviving on the path a real second device actually takes.
> `SetupUnlockScreen` now leads with the device-protection step and keeps the lock behind
> `onShowAdvancedUnlock`/`onExitAdvancedUnlock`, mirroring `NewUserScreen`; `DeviceProtectionStep`
> moved out of `NewUserScreen` into `:control-shared`'s `ui.screens.unlock` package (one copy, two
> callers) and its four strings were renamed `new_user_protect_*` → `device_protection_*`.
> `SetupUnlockViewModel.onSkip()` → `onUseDeviceProtection()` + the `advancedUnlock` state; nothing
> else in the VM changed, because completing headlessly really is just "don't call `setUpUnlock`".
> ⚠ **iOS is unaffected**: its joiner path never routed through a setup-unlock at all, and
> `OnboardingView` already leads with the Ph-ios device-protection intro. Tests:
> `SetupUnlockViewModelTest` gains three — the screen opens on device protection, taking it leaves
> `setUpUnlock` uncalled (that call is what clears the plaintext slot), and backing out of the
> advanced branch discards a pending biometric enrollment and returns to the headless exit.
> **No threat-model change** — this *removes* a sealing path; PR A's A1 amendment already covers
> the headless default (HR#2 satisfied).
>
> **Ph1.2/1.3 landed (PR B):** the soft "Require unlock" app-lock + Settings → Advanced → Security.
> `AppLockSettings` (device-local flag over the non-secret `SecureStorage` string bucket, default on),
> `AppLockController` (pure gate state machine — re-armed on background, cleared on auth; re-reads the
> pref at each transition, no live subscription needed since the opaque gate blocks reaching Settings),
> `AppLockAvailability` (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` probe; inert on desktop/iOS). Android
> glue: `AppLockGate` (`:control-shared` androidMain — opaque overlay + `BiometricPrompt`, wraps
> MainActivity's `App {}`) + a ProcessLifecycle `ON_STOP` observer in `ControlApplication`. New nav:
> `Destination.SettingsAdvanced`/`SettingsSecurity`, `AdvancedSettingsScreen` + `SecuritySettingsScreen`
> (live Require-unlock toggle + disabled "Encrypt on unlock only — coming soon"). Tests: `AppLockTest`,
> `SecuritySettingsViewModelTest`. **No new threat-model change** — the soft lock is UI-only (never keys),
> already documented in PR A's A1 amendment; PR B touches no A1–A4 / relay / protocol asset (HR#2 satisfied).
>
> **Ph-hard landed (PR C):** "Encrypt on unlock only" — the hard key gate. New `RelaxToHeadless` op
> (`:sync-service` + client `Ro` + proxy `Implementation` + `SyncManager.relaxToHeadless()` /
> `SyncManagerImpl`): re-persists the plaintext from the *unlocked* session (host = recovery phrase;
> joiner = re-encoded `JOINER_PAYLOAD`) — crucial for the **same-session disable** where `setUpUnlock`
> already cleared the plaintext — then drops both wrappers so the next cold start autoloads headless;
> refuses (`false`) when locked (no in-memory material). `SyncRepository.relaxToHeadless` +
> `TenshuSyncRepository`. `SecuritySettingsViewModel` reads hard-mode from `getUnlockStatus().isSetUp`;
> **enable** reuses the SetupUnlock passphrase-floor screen via `Destination.SettingsEncryptSetup` (pops
> back, no route-to-Home), **disable** calls `relaxToHeadless` + `biometricDelegate.clear()`; the row is a
> live child of "Require unlock". Re-reads state on resume (`LifecycleResumeEffect`). Tests:
> `RelaxToHeadlessTest` (same-session reseed / locked-refusal / fresh-unlock) + `SecuritySettingsViewModelTest`
> hard-mode cases. **No new threat-model change** — hard mode *restores* the sealed-until-unlock wrapper
> lifecycle already documented in the A1 note; no relay / protocol / asset change (HR#2 satisfied).
>
> **Ph-desktop landed (PR D): the macOS soft-lock mirror.** Finding: desktop **headless autoload already
> works** — `KeychainWrappingKeyStore` reads the login-Keychain wrap key via the Apple-signed
> `/usr/bin/security` (on the item ACL → prompt-free), `DesktopMasterKeyStore` decrypts `secrets.enc` at
> construction, and `init` autoloads `RECOVERY_PHRASE` when no wrapper exists → a cold start boots enabled
> with no prompt, like iOS. So only the soft "Require unlock" gate was built. Mechanism: a new
> **`authenticate`** verb on the Swift keychain helper — a *keyless* `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`
> (Touch ID / login password) that touches no keychain item, so it needs **no entitlement** and works under
> ad-hoc signing (unlike enroll/unwrap). `DesktopAppLock` (`:control-shared-logic` desktopMain) implements
> `AppLockAvailability` + `authenticate()` over that verb (same helper-resolution as the biometric delegate —
> dormant with no helper: bare `:run`/non-macOS ⇒ unavailable). Desktop `AppLockGate` (`:control-shared`
> desktopMain) obscures the window + auto-prompts; the **window-visibility** signal (`LocalWindowVisible`,
> driven by the tray hide/show over `WindowController`) is the ON_STOP equivalent — hiding re-arms, re-opening
> re-prompts. `Main.kt` wraps `App()`; desktop DI binds `DesktopAppLock` + `AppLockController`. Test:
> `DesktopAppLockTest` (fake-helper token mapping); helper swiftc-compiles. **No new threat-model change** —
> UI-only soft lock, already documented in PR A's A1 note. **Desktop hard mode** ("Encrypt on unlock only")
> is not wired: the Security toggle is gated on `available`, and the passphrase `setUpUnlock`/`relaxToHeadless`
> path works, but the natural desktop equivalent is the Touch-ID-gated MasterKeyStore wrap key — left
> for a later slice.
>
> **Ph-ios landed (PR E): the SwiftUI mirror.** ⚠ Finding correction: iOS was **not** headless by default —
> the *keychain* wrap key is `AfterFirstUnlock` (headless), but iOS onboarding **forced a passphrase**
> (`NewUserModel.observe()` called `onChoosePassphrase()`), so a genesised realm sealed like Android pre-PR-A.
> Two parts, both native SwiftUI over the existing `ControlSurface`/`NewUserViewModel` bridge (no Kotlin
> control change): **(1) headless onboarding** — `NewUserView` now leads with a "Protected by your device"
> intro (Continue → the already-exported `vm.onUseDeviceProtection()`, headless) + a de-emphasised "Add a
> passphrase for extra security" → `onShowAdvancedUnlock`/`onExitAdvancedUnlock`; mirrors the Compose
> `NewUserScreen`. **(2) soft "Require unlock" Face-ID gate** — `AppLockGate.swift` (LAContext
> `.deviceOwnerAuthentication` + `@AppStorage("app_lock_require_unlock")` default on + `scenePhase`: re-arm on
> `.background`, prompt on `.active`; opaque overlay), wrapping the `.ready` `MainTabsView`; a "Require unlock"
> toggle + disabled "Encrypt on unlock only" row in Settings → Security. **Hard mode deferred** (iOS-native
> shape = flip `IosSecureStorageDelegate(gateSecretBucket=true)` for a biometry-gated Keychain wrap key — a
> distinct mechanism, its own slice). **Drive-by:** fixed a **master iOS-SDK build break** — `TimelineSurface`
> passed the old `TripDetailViewModel(installed, tripId)` after that VM gained an optional `regions` param
> (`regions = null`, matching the Compose DI + tests). No threat-model change (UI-only). Verified: XCFramework
> assemble + **`xcodebuild` (iphonesimulator) BUILD SUCCEEDED**; on-sim Face-ID click-through = maintainer.
>
> **DECISION (2026-07-13): iOS gets NO distinct hard mode.** The "Encrypt on unlock only" passphrase model is an
> Android/desktop artifact — its value is an *off-device* secret layered under the OS keystore (defense if the
> keystore is extracted). On iOS, auth is one transparent primitive (`LAContext .deviceOwnerAuthentication` =
> Face ID / Touch ID / passcode, automatic fallback), which the **soft "Require unlock"** already provides, and
> the OS encrypts keys at rest by default (`AfterFirstUnlock`). A *Tenshu passphrase* on iOS would be foreign +
> clunky (type a passphrase instead of Face ID). The only thing a hard mode *adds* on iOS is "keys are ciphertext
> at rest until you authenticate" — achievable transparently via `IosSecureStorageDelegate(gateSecretBucket=true)`
> (a `userPresence` Keychain ACL on the wrap key), which stays a **possible future hardening** if that specific
> property is ever wanted; it needs its own sealed/unlock handling (doesn't fit the `UNLOCK_WRAPPER`/`LockedView`
> machinery), so it's a deliberate non-goal for now, not an unfinished slice. The iOS Settings "Encrypt on unlock
> only — Coming soon" placeholder is removed to match. (Note: the advanced-passphrase onboarding opt-in can still
> seal an iOS realm → `LockedView`; a small follow-up should mirror the Compose "satisfy the soft lock after
> a hard unlock" fix on iOS so those realms don't double-prompt.)
>
> **Ph1 + Ph-hard + Ph-desktop + Ph-ios code-complete; iOS hard mode = decided non-goal.** **Remaining:** on-device
> pass (maintainer) — Android reboot→bg-sync + app-lock prompt + enable/disable encryption; desktop packaged-build
> run; iOS-sim onboarding + Face-ID gate click-through. **Ph-drive is withdrawn (§6.1);** the open slice is
> **Ph-reauth** — re-authenticate before an irreversible action *inside* Control.

- v0.2 flipped the model: headless autoload is the **default**; the passphrase/biometric lock is an
  **expert opt-in** under a new *Settings → Advanced* submenu. Back-compat is a non-issue (dev
  resets frequently), so no migration.
- **v0.3 simplifies the mechanism.** Investigation showed the platform *already* provides what we
  need: `AndroidSecureStorageDelegate.storeSecret` seals under a **non-auth, hardware-backed
  AndroidKeyStore AES-GCM key**, and the *default* autoload path (`RECOVERY_PHRASE` /
  `JOINER_PAYLOAD` via `storeSecret`) is therefore already **headless + hardware-backed**. The realm
  only seals because **onboarding forces a mandatory passphrase "floor"** (`SetupUnlockViewModel` /
  `NewUserViewModel` → `setUpUnlock`, which clears the plaintext). So there is **no new
  `DEVICE_WRAPPER`**: the fix is to make that floor **optional** and expose the lock as an expert
  toggle. Ph1 shrinks accordingly.
- **v0.4 splits "lock" into a soft UI gate vs a hard key gate** (maintainer). The data plane is
  **always headless** (background sync never blocks). The lock becomes two nested Settings toggles:
  **"Require unlock"** — a *soft* app-lock (a biometric/device-credential prompt when the user
  **foregrounds Control's UI**; never touches keys; Ph1, Control-only) — and **"Encrypt on unlock
  only"** — the *hard* key-wrapping mode (keys stay encrypted at rest until unlock, so background
  sync pauses when locked; a later phase, opt-in child of "Require unlock").

## 1. Problem (confirmed on-device 2026-07-12)

When a Control realm is protected by a passphrase/biometric wrapper, it comes up **locked** on
every cold start (reboot, OOM, swipe-kill, or a WorkManager wake of a *dead* Control process). Until
the user foregrounds Control and re-authenticates, `SyncManager.isEnabled()` is `false`,
`currentOwnerId` is `null`, background sync is dead, `hasStorage` derives `false`, and Drive shows
"Local only" with uploads held (`project_sync_stuck_file_locked_emit_gap`).

More fundamentally: **requiring an app-level unlock before background sync is the wrong default.**
Dropbox / Google Drive / OneDrive all sync in the background after the normal device unlock; they
do not gate sync behind an in-app authentication. Making the average Tenshu user open + unlock
Control before anything syncs is a hard UX regression.

### Why the app-level unlock adds little for the average user

On a modern device A1's wrapped seed already sits in Control's **credential-encrypted, app-private
storage**, protected by (a) the **app sandbox** — no other app can read it — and (b) **File-Based
Encryption** keyed to the device lockscreen — when the device is off or freshly booted the data is
encrypted at rest by a key derived from the user's device credential. So for the "lost/stolen
powered-off phone" case the OS *already* protects A1. The extra app-level wrapper only helps in
narrow cases (someone holding the **already-unlocked** device, a shared device, on-device malware)
— most of which can open the app regardless. This is exactly why the incumbents rely on OS-level
protection and don't add an app unlock. The recovery code + device-revocation (GR-7 `Kr` rotation)
remain the real backstops for a lost device.

## 2. Goal

- **Default: headless autoload, no app-level unlock.** After the normal device unlock, the realm
  boots enabled and background sync just works — including after a reboot (see §3, no re-prompt, no
  notification).
- **Expert opt-in: "Require unlock for extra security"** under *Settings → Advanced* — restores a
  passphrase/biometric gate for users who want it (today's behaviour, made optional).

Non-goals: web (already caches A1 at rest via WebAuthn-PRF, Phase 5); migrating existing devices
(back-compat is a non-issue this cycle); a timed re-auth policy (later refinement).

## 3. Cold start / reboot behaviour (no re-prompt, no notification)

The default wrapper's key uses the **"available after first device unlock"** accessibility class
(iOS `kSecAttrAccessibleAfterFirstUnlock`; Android FBE + a Keystore key **without** per-use auth).
So after a reboot the key becomes usable the instant the user **unlocks the phone once** (which
they do anyway) — Control then autoloads headlessly on the next proxy start / WorkManager wake, and
sync resumes on its own. **No Control unlock and no "please unlock" notification are needed.** (A
`setUnlockedDeviceRequired(true)` variant — key usable only while the screen is unlocked — is
*more* restrictive and would break locked-screen background sync, so v1 uses after-first-unlock.)

## 4. Design (v0.3 — no new wrapper)

### 4.1 Default: keep the existing headless, hardware-backed autoload

`AndroidSecureStorageDelegate.storeSecret` already seals values under a **non-exportable,
hardware-backed AndroidKeyStore AES-256-GCM key** (no `setUserAuthenticationRequired`), and the
sealed blob lives in credential-encrypted app-private storage (FBE — available after first device
unlock). The host stores `RECOVERY_PHRASE` and the joiner `JOINER_PAYLOAD` this way, and
`SyncManagerImpl.init` autoloads from them **only when no passphrase/biometric wrapper exists**.

So the default autoload **already is** headless + hardware-backed. The single reason a real user's
realm seals is that **onboarding forces a passphrase floor**: `SetupUnlockViewModel` and
`NewUserViewModel` call `setUpUnlock(passphrase, wrapKey?)`, which wraps the payload and **clears
the plaintext slot** (`clearPlaintextPayloadSlots`) → the init autoload gate falls through to the
sealed branch → the realm requires an explicit unlock on every cold start.

**The fix is therefore to make the floor optional, not to add a new wrapper.**

- Onboarding (`NewUserViewModel` / `SetupUnlockViewModel`) **no longer requires** a passphrase.
  Default = "protected by your device" — the payload stays in `RECOVERY_PHRASE`/`JOINER_PAYLOAD`
  (keystore-sealed), the realm autoloads headlessly, background sync works.
  ⚠⚠ **Both view models, and the screens in front of them.** Ph1.1 converted genesis and left the
  post-pairing / post-recovery screen leading with the lock for six weeks, on the reasoning that its
  *Skip* button already reached the headless path. It did — but a default is what the screen puts
  first, not what its escape hatch permits. Closed by Ph1.1b.
- **Hardening (Q2):** upgrade `getOrCreateSecretKey` to request `setIsStrongBoxBacked(true)` with a
  TEE fallback on `StrongBoxUnavailableException`, so the default seed key is StrongBox-backed where
  available. (Small, self-contained; the existing key is already TEE-backed.)

No `SecureStorageDelegate` change, no `DEVICE_WRAPPER`, no proxy autoload-gate change.

### 4.2 The two Settings → Advanced → Security toggles

Nested; the data plane is **always headless** regardless of either.

**(1) "Require unlock" — soft app-lock (Ph1, Control-only, default ON).**
A pure **UI gate**: when the user *foregrounds Control's Activity*, show a `BiometricPrompt`
(`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, so it works on any device — falls back to the device PIN)
before revealing the UI; obscure the screen until it succeeds. **It never touches key material** —
keys stay keystore-sealed + autoloaded, so background sync is unaffected. Implementation is an
`AppLock` lifecycle gate in `:control` (ProcessLifecycle: re-lock on `ON_STOP`, prompt on the next
`Activity` start), driven by a **device-local** preference (not synced). Copy: *"Ask for your
fingerprint, face, or device PIN to open Control. Your files keep syncing in the background either
way."* Default **on** (privacy-first, near-zero friction, sync unaffected). Gracefully degrades to
"unavailable" if the device has no secure lock screen at all.

Scope note: Ph1 gates **Control** only — and ⚠⚠ as of 2026-08-23 that is the **settled** scope, not
an interim one. The "make Drive honor the same app-lock" follow-up (Ph-drive) was **withdrawn**; files
*are* viewable in Drive without the gate, and the setting's own copy — *"…to open Control"* — says so.
The reasoning, and what a platform-wide lock would actually cost, is in §6.1.

As-built corrections (2026-07-17, found on a fresh desktop install):

- **Dormant backend ⇒ gate passes through.** "Gracefully degrades to unavailable" was only
  half-implemented: the Settings toggle checked `isAvailable()`, the gate did not. With the
  default ON and a desktop bundle that ships no keychain helper (the helper is opt-in +
  signing-gated), `DesktopAppLock.authenticate()` can only return `Unavailable` — the gate was a
  **permanent lockout** of everything past onboarding. The desktop gate now auto-clears when the
  backend reports unavailable, matching the toggle's dormant posture.
- ⚠⚠ **Android had the same lockout (found 2026-10-30, on an emulator with no screen lock).** The
  parenthesis that used to stand here — "Android is unaffected: the prompt works on any device
  with a secure lock screen" — was true and beside the point: a device with **no** secure lock
  screen (never set one, or removed it later) got the default-ON gate and an *Unlock* button that
  could never succeed, locking it out of Control and so every app behind it. The pass-through now
  lives in the shared `AppLockController.passIfUnenforceable` and both gates call it on every
  engagement (and Android again after a prompt answers `Unavailable`); it is probed, never
  latched, so a screen lock added later is honoured at the next re-arm. Passing through is no
  weaker than the OS: a phone with no lock screen opens to anyone. Prompt errors still never clear
  the gate — lockout is `Failure` — only the "is there any credential at all" probe can.
  ⚠⚠ That probe is `KeyguardManager.isDeviceSecure()`, **never**
  `canAuthenticate(BIOMETRIC_STRONG | DEVICE_CREDENTIAL)`: AndroidX rejects that combination on
  API 28–29 whatever is enrolled, so the `canAuthenticate` form would have opened Control without a
  prompt on every Android 9/10 phone *with* a PIN. For the same reason the prompt itself asks for
  `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` on those two levels (`PromptInfo.build()` throws otherwise).
- **Onboarding is exempt end-to-end.** The gate keyed off `showNav`, which excludes the Welcome /
  NewUser / Recovery / Locked / SetupUnlock screens but not destinations pushed *from* Welcome —
  so first-run "I have Tenshu on another device" → PairingWizard engaged the lock mid-onboarding.
  The gate predicate (`appLockGateActive`) now also exempts any back stack still containing
  Welcome, which marks the whole first-run flow (every onboarding exit clears the stack).

**(2) "Encrypt on unlock only" — hard key gate (later phase, child of (1), default OFF).**
The key-wrapping mode: enabling it calls the existing `setUpUnlock(passphrase, biometricWrapKey?)`
so the payload is wrapped and the plaintext cleared → keys are **not** headless; a locked cold start
seals the realm and **background sync pauses until the user next unlocks**. Only tappable when
"Require unlock" is on. Disabling it → `relaxToHeadless()` (a new proxy call: re-persist the
plaintext payload from the in-memory phrase/keys, delete `UNLOCK_WRAPPER` / `BIOMETRIC_WRAPPER`,
clear the biometric keystore key) → back to headless. Passphrase stays the floor of this mode
(biometric never alone). Copy: *"Keys stay encrypted until you unlock. Most secure, but background
sync pauses until you next open Control."* (Advanced.)

In Ph1 the "Encrypt on unlock only" row is present but **disabled ("coming soon")** so the layout is
final; the hard-mode wiring lands in the later phase.

### 4.4 UI — new *Settings → Advanced* submenu

A new **Advanced** entry under Control's Settings, holding the expert **"Require unlock for extra
security"** toggle (off by default; gated on `BiometricUnlockDelegate.isAvailable()` for the
biometric option, passphrase always available). Copy makes the tradeoff explicit ("Tenshu already
protects your keys with your device's secure storage; turn this on to also require your
passphrase/biometric to open Control"). Future advanced/expert settings live here too.

The Drive banner / Control "Sign up" mislabelling then only ever appears for a *locked* expert
device pre-unlock; a small follow-up can still distinguish `Locked` from `no-storage` in the banner,
but it's orthogonal.

## 5. Threat-model delta (House Rule #2)

**This changes the *default* A1-at-rest posture and MUST land with a `docs/sync/threat-model.md`
amendment in the implementing PR.**

- **Default before → after.** Before: onboarding forced a passphrase floor, so the default sealed
  A1 at rest behind the user's passphrase/biometric (required at every cold start). After: the
  floor is optional; the default protects the A1 *seed* at rest with the **existing non-exportable,
  hardware-backed AndroidKeyStore AES-GCM key** (StrongBox-preferred, Q2), released by the OS
  **without user interaction after the first device unlock post-boot** — i.e. protection = *device
  credential (FBE) + app sandbox + hardware keystore*, no app-level auth. This is the
  **competitor-standard** posture (iOS `AfterFirstUnlock`, Android non-auth Keystore) and the same
  one Tenshu web already ships (A1 cached at rest wrapped under a WebAuthn-PRF key, Phase 5). Note
  this is not a *new* at-rest exposure — the seed already sat keystore-sealed via `storeSecret`
  before any wrapper was set; the change is that the default no longer *also* requires a passphrase.
- **Residual risk.** On the default posture, an attacker with the device **already unlocked**, or
  who can defeat the hardware keystore, can recover A1 without the user's passphrase/biometric.
  Mitigations: hardware-backed + non-exportable + StrongBox where available; after-first-unlock
  accessibility (a fully-powered-off / pre-first-unlock device is protected by FBE); the recovery
  code is never at rest on the device by default; and **device-revocation still applies** — a lost
  device is removed via the existing revocation + GR-7 `Kr` rotation, cutting it off regardless of
  A1 exposure. Users who want the stronger boundary enable *Require unlock*.
- **The "Require unlock" soft app-lock (Ph1) is UI-only.** It gates *viewing* Control's UI with a
  biometric/device-credential prompt; it does **not** encrypt the keys (they are headless either
  way), so it adds no cryptographic protection — it's a casual-access / shoulder-surf barrier, the
  same posture as WhatsApp/Dropbox app-locks. The cryptographic key gate is the opt-in
  "Encrypt on unlock only" hard mode (later phase), which behaves exactly as today's
  passphrase/biometric flow.
- **Unchanged.** Relay sees ciphertext only (HR#1); no protocol/wire change.

Amendment target: `threat-model.md` A1 note (replace the "persisted unlock wrapper … requires the
passphrase/biometric" framing with the default-headless posture + the expert-lock override) + a
T-K-04 sibling for the headless-autoload residual.

## 6. Phasing

- **Ph1 (the reported fix — headless default + soft app-lock, Control-only):**
  1. Onboarding no longer forces the passphrase floor — `NewUserViewModel` / `SetupUnlockViewModel`
     make unlock setup skippable (default = device-protected/headless), so a fresh device autoloads.
  2. **`AppLock`** in `:control`: a ProcessLifecycle-driven biometric/device-credential gate on the
     Control Activity (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`), a device-local "require unlock"
     preference (default on), and a lock overlay until authenticated. Pure UI — no key material.
  3. New **Settings → Advanced → Security** submenu: the **"Require unlock"** toggle (live) + the
     **"Encrypt on unlock only"** row (disabled/"coming soon").
  4. StrongBox-preferred hardening of `getOrCreateSecretKey` (Q2).
  5. **Threat-model amendment** (soft-gate posture: keys always headless; the app-lock is UI-only).
  Tests (skip-floor autoload, AppLock state machine) + Android smoke gate + on-device
  reboot→background-sync + app-lock-prompt check (maintainer).
- **Ph-hard (Encrypt on unlock only):** wire the child toggle to `setUpUnlock` / `relaxToHeadless()`
  (a new proxy call re-persisting the plaintext payload + clearing the wrappers); keys become
  non-headless when on. Tests extend `SetUpUnlockTest` / `BiometricUnlockTest`.
- **Ph-drive:** ⚠⚠ **WITHDRAWN 2026-08-23** — superseded by **Ph-reauth**. See §6.1.
- **Ph-desktop / Ph-ios:** confirm headless autoload when no lock is set; mirror "Require unlock".
- **Ph-reauth (NEW):** re-authenticate before an *irreversible* action inside Control. See §6.1.

Ph1 is the reported fix and is materially smaller than the v0.2 `DEVICE_WRAPPER` plan — no new
platform crypto, no autoload-gate change; the "lock" is a UI gate, not key encryption.

### 6.1 Amendment (2026-08-23) — Ph-drive withdrawn; the lock is a *control-plane* curtain

Ph-drive read *"make Drive honor the same app-lock (files live there)"*. It was written when Drive was
the only other first-party app; there are now **nine**. Re-examined before starting it, and withdrawn.

**What the toggle actually claims.** The shipped copy is *"Ask for your fingerprint, face, or device
PIN to open Control. Your files keep syncing in the background either way."*
(`control-shared/.../values/strings.xml`, `security_require_unlock_support`). It says *Control*, and it
means Control. ⚠ So Control-only is **not a half-delivered promise** — a framing this doc's own §4.2
scope note invited, and which is wrong. It is the whole of what the setting says.

**Two products; they want opposite scopes.**

- *Protect the **control plane*** — the irreversible actions all live in Control: reveal the recovery
  phrase, forget/revoke a device, approve a pairing, grant membership, account + billing. ⚠⚠
  `RecoveryPhraseScreen` has a tap-to-reveal but **no re-auth of its own**, so the front-door app-lock
  is today the *only* authentication standing in front of A1's plaintext. On this reading Control-only
  is complete.
- *Protect **realm data at view*** — then Control-only buys almost nothing: whoever holds the unlocked
  phone opens Drive, Timeline or Contacts directly. This is the reading Ph-drive was written under, and
  it is the one that drags in every app.

**Three findings that decided it.**

1. ⚠⚠ **Cross-app open makes independent per-app gates hostile.** With TT-1/2/3 shipped
   (`control-cross-app-open.md`), a Timeline row opens a file in Drive — which backgrounds Timeline
   (re-arming its gate) and foregrounds Drive (prompting): **one gesture, two prompts**, and the
   setting defaults ON. A platform-wide lock would therefore have to be **one shared session**
   ("authenticated until T"), not N gates — a materially different design from the shipped
   per-process `AppLockController`, not a repetition of it.
2. ⚠ **There is no middle position — the cost is the plumbing, not the apps.** `AppLockSettings`
   reads a **proxy-side** `SecureStorageDelegate` that no client app has, and the gate + its strings
   live in `:control-shared` (module-`internal` `Res`). Making *any* second app participate needs
   (a) an `:app-lock` module in the `app-notify`/`app-liveness`/`app-reachability` family, and (b) the
   flag readable cross-process — a DEVICE-scoped preference row **plus** a synchronously-readable local
   mirror, because the row only arrives after the client reaches the proxy, leaving the first frames of
   a cold start ungated. That is ~2 PRs; every app after the first is small. So *"just Drive"* costs
   nearly what *"all nine"* costs.
3. **Android's satellites are all `ComponentActivity`;** `BiometricPrompt` needs `FragmentActivity`
   (Control is the only app that already is). Mechanical, but it touches every app module.

**Decision (maintainer, 2026-08-23): the soft lock stays Control-only.** Depth over breadth — the
follow-up work goes *inside* Control (Ph-reauth), not across apps. §1's argument still holds for the
stolen-phone case (the device lock screen + FBE own it); the residual is shoulder-surfing, and
shoulder-surfing the control plane is worse than shoulder-surfing a photo.

⚠ **If this is ever revisited** — because the satellites become the primary surface — the trigger is a
platform-wide *session*, not a per-app gate, and findings 2 + 3 above are what it costs to start.

#### Ph-reauth — re-authenticate before an irreversible action

A prompt in front of the actions that cannot be undone, rather than only at the front door: **reveal
recovery phrase**, **forget / revoke a device**, and (when they land) close-account and membership
revocation. Reuses the existing `AppLockAvailability` + each platform's prompt — **no new module, no
preference transport, no cross-app work**.

- ⚠⚠ It must be available **independently of "Require unlock"**. A user who turned the front door off
  has said nothing about the recovery phrase, and today that user's phrase is behind no auth at all.
- ⚠⚠ It must degrade to **pass-through** wherever no prompt exists — the lesson of the soft app-lock's dormant-backend pass-through: a gate that
  cannot be authenticated is a lockout, and here it would make the phrase *unrecoverable* on that
  device rather than merely unviewable.
- ⚠ It is a **UI barrier, not a key gate**, exactly like "Require unlock" — the same threat-model
  posture (§5), so no A1–A4 / relay / protocol amendment is owed; the threat-model text that describes
  the soft lock as gating *"viewing Control's UI"* stays true.

#### As-built: RA-0 + RA-1, RA-2

- **RA-0** — `Reauthenticator` (`:control-shared-logic`), keyless, over the existing
  `BiometricPromptHost`. The pass-through policy lives in exactly one function, `confirm()`:
  **Success *and* Unavailable proceed**, Cancelled/Failure refuse. `AppLockAuthResult` moved
  `desktopMain` → `commonMain`. ⚠ The soft lock's gate overlay now calls the **same** prompt
  (the duplicate `BiometricPrompt` setup is gone) but deliberately does *not* pass through on
  `Unavailable` — its toggle is already gated on `AppLockAvailability`, and an overlay that clears
  itself on an error is not a gate.
- **RA-1** — Compose `RecoveryPhraseScreen`. ⚠⚠ Gates the transition **concealed → disclosed**, so
  **Copy is gated too**: the icon is always present and copying discloses the phrase without ever
  rendering it. Re-conceals on `ON_STOP`.
- **RA-2** — the SwiftUI twin (`control-ios/Sources/Reauth.swift`). ⚠ Copy needs no separate gate
  there: the button only renders once revealed. Re-conceals on **`.inactive`, not `.background`** —
  iOS takes the app-switcher snapshot on the way out, so waiting for `.background` re-conceals
  *after* the picture is taken. ⚠⚠ **Onboarding is deliberately ungated** (`requireAuthToReveal`
  is explicit, not derived from `startRevealed`): at genesis the user just made the realm on a
  phone they unlocked seconds ago, may have nothing enrolled, and this is the one screen whose
  entire purpose is that the phrase gets *seen* (§5b.12).

- **RA-3** — device removal, both platforms. ⚠⚠ **Where the gate sits differs by call site, and the
  rule is *does this surface disclose?*:**
  - *Revoke a device* (`SyncDashboardScreen` / `SyncDashboardView`) — gated on the **confirm**. The
    dialog is where the user learns removal is final for that identity, so prompting before they
    have read it would ask them to authorise something unexplained.
  - *Forget this device* (Compose `App.kt`) — gated **before the dialog opens**. `ForgetDeviceDialog`
    shows the recovery phrase *in the clear* (deliberately — §5b.12.2 routes destruction through the
    reveal), so it is a **second disclosure path to the secret RA-1 gated**. Gating its confirm would
    have left "open the forget dialog, read the words, cancel" as a way straight around RA-1. One
    prompt covers the disclosure and the irreversible action behind it.
  - ⚠⚠ *iOS "Forget this device"* (`LockedView`) is **deliberately ungated**. It is the escape hatch
    for a user who *cannot* get in, on a screen shown while the realm is still sealed — so it
    discloses nothing, and gating the exit from a lockout is the soft app-lock's dormant-backend lockout shape exactly.
  - ⚠ Asking is never refusing: `confirm` passes through on a device that cannot prompt, so a user
    with a broken or sold device can still remove it — which §5b.12.2 requires.
- **RA-4** — *disabling* "Encrypt on unlock only". ⚠⚠ Gated because it is the only control on the
  Security screen that **lowers** the at-rest posture: `relaxToHeadless` strips the passphrase
  wrapper off A1 and leaves the OS keystore alone holding it. ⚠ **Enabling is deliberately not
  gated** — it is a step *up*, and the passphrase the user must set is itself the proof. ⚠ Sequenced
  in the **ViewModel** (the host is a parameter, matching `UnlockSettingsViewModel.onToggle`) rather
  than in the composable, so a key-material guard is unit-testable rather than visible only to a UI
  test. ⚠ **Android + desktop only**: iOS has no hard mode at all (the decided non-goal of 2026-07-13),
  so there is no iOS twin for this one — and its pass-through matters more than elsewhere, since
  failing closed would strand a user whose biometrics broke *inside* the mode that pauses their
  background sync.
- ⚠ **Drive-by in RA-3:** the iOS revoke sheet still read *"has to be paired again to come back"* —
  the promise of re-admission that option F does not have, and which the Compose copy was corrected
  for. iOS was missed at the time; fixed in the same PR since it is the flow being touched.
- **The soft lock's own snapshot gap — fixed 2026-08-24.** `AppLockGate.swift` re-armed only on
  `.background`, so its overlay went up *after* iOS had taken the app-switcher picture; the comment
  claiming it doubled as the privacy screen was simply false. ⚠⚠ The fix is **not** to re-arm on
  `.inactive`: that phase fires for every transient interruption which never leaves the app —
  Control Centre, the notification shade, a call banner, a system permission alert, and
  self-defeatingly our own Face ID sheet — so locking there would demand re-authentication after
  each one and train the user to switch the feature off. **Covering and locking are separate
  events**: `.inactive` raises an interaction-free `PrivacyCover` (which is what the snapshot
  photographs), `.background` re-arms the lock. Both are gated on the feature being in use, so a
  user who turned "Require unlock" off sees no curtain either.
- ⚠⚠ **RA-2 has no automated test.** `control-ios` has no test target (only `drive-ios` does), so
  the Swift half is build-verified only and its click-through is a maintainer pass. The policy it
  mirrors *is* covered on the Kotlin side (`ReauthenticatorTest`), which is where a regression in
  the *rule* would show — but not one in the Swift transcription of it.

#### ⚠⚠ Device pass, 2026-08-25: authenticating on macOS looked like a crash

The first run where the desktop gate was ever live (RA-5 + the signing fix): open Control → Touch ID
sheet → touch → *"the app closes"*. It did not close. **No crash report, pid unchanged, and the
window was simply behind the terminal.**

`applock-helper` is a bare binary with no bundle identity, so macOS gives it the foreground to
present the `LAContext` sheet and, when it exits, activation does **not** return to Control. Every
symptom followed: the tray's *Open Control* appeared to "fix" it because that path raises a window
that was never gone, and closing re-armed the gate so the next open repeated the whole thing.

⚠⚠ **The obvious fix does not work, and it is worth knowing why before reaching for it again.**
`window.toFront()` + `requestFocus()` changed nothing on the device: `toFront()` reorders windows
*within* an application, and macOS will not let a process that is not active steal activation that
way. Two things had misled the reasoning — the tray's *Open Control* works because the tray is a
**native app calling `NSRunningApplication.activate()`** (`TrayAgent/main.swift`), and the existing
`isVisible` effect works because making a *hidden* window visible activates as a side effect.
Neither applies to an already-visible window of a backgrounded app.

The fix is `Desktop.requestForeground(true)` in `control-desktop/Main.kt`, keyed on
`AppLockController.locked` clearing — the JVM route to the same AppKit call the tray makes. Verified
on-device. It lives in the entry point because the gate is in `:control-shared` and has no window.

⚠ **Adjacent and NOT fixed:** the *hard* unlock (`LockedScreen` → biometric unwrap) and biometric
enrolment spawn a helper the same way and should lose activation identically. They are a different
flow, gated on hard mode, and were not exercised in this pass — so this is reasoned, not observed.
⭐ If it reproduces, the better home for the raise is the desktop **helper runner** itself (after any
helper process exits), which would cover the soft lock, the hard unlock and enrolment in one place
rather than one signal at a time.

##### ⚠⚠ The outcome string is LOGGED, not shown — and that is a known retreat

`ForgetThisDevice` is deliberately **partially-failable** — a retirement that cannot reach the relay
must not block the local wipe, because people forget a device precisely when something is broken. It
therefore returns a description of what happened rather than a bare success, and its own KDoc calls
the leftover case *"an **announced** outcome with a named remedy — never a silent orphan"*. `App.kt`
discarded that value from the day it shipped.

⚠ **Surfacing it in a modal was tried on 2026-08-26 and withdrawn the same day.** The dialog worked
in every sense a test can see — composed, correct state, live composition — and was **never visible
on macOS**, because it is raised *asynchronously* after an operation that takes seconds and so is
created behind its own parent window. Three fixes failed against it (`toFront()`, then process
activation as well), each compiling and reading well. The maintainer's call ended it, and the
reasoning is better than the implementation was: at that moment the device is already gone, the only
actionable half — *"remove it from another device"* — can only be acted on **elsewhere, later**, and
the rest is bookkeeping. A modal blocking the teardown is the wrong carrier at the wrong moment.

So: `println("ForgetThisDevice: …")` and navigate straight to Welcome.

⭐ **The contract is met again (2026-08-26), on the Welcome screen instead.** The outcome is carried
to the screen the flow already lands on and rendered as an `ErrorBanner` — no raise needed, because
the user is looking straight at it. The remedy is always *"from another device"*, which is exactly
why this surface fits: they no longer have the forgotten one.

- ⚠ **Only a non-clean outcome shows.** A warning banner on every successful fresh start is how
  people learn to ignore warnings, and this is the one screen a user reaches *expecting* everything
  to be fine.
- ⚠⚠ **The `⚠` marker is the contract**, and it is pinned from the **producing** side
  (`ForgetThisDeviceOutcomeMarkerTest`): the op marks exactly its non-clean outcomes and never a
  clean one. A test on the consumer would only prove `contains('⚠')` works; the way this breaks in
  practice is someone rewording an outcome and dropping the marker, after which the notice silently
  shows nothing. ⚠ Note this is *not* the relay-status-text trap — both ends live in this repo and
  change together.
- ⚠ `errorContainer` tone, not informational: a failed retirement means the admission strip never
  reached the relay, so a device that should have lost access may still have it.
- ⚠⚠ **Transient** — in-memory, gone on the next launch or as soon as the user leaves the screen.
  Good enough for the moment it matters and no more; **nothing durably records that a realm has an
  orphaned row**, which is a real remaining gap rather than a shortcut.
- ⚠ The outcome strings are hardcoded English in `:sync-service-proxy`, so this notice is not
  localised. Pre-existing, and now user-visible where it was not before.

##### ⭐⭐ RESOLVED (2026-08-26): it always worked — it took ~10s and said nothing

⚠⚠ **Four diagnoses, all wrong, all green.** "Confirming the forget does not navigate" was blamed on
a call that never returns, an acknowledgement-gated button, a dead composition, and an invisible
modal (which was real, but *I introduced it while investigating this*). Instrumentation eventually
showed the whole chain correct end to end — one `AppContent`, never disposed, the op returning, the
state written, `backStack` becoming `[Welcome]`, `currentDestination=Welcome`. The screen did
navigate. **It took about ten seconds, with no feedback of any kind.**

⭐ **What I never asked, for two days, was how long the operation takes.** The answer was a constant
in a file already read twice: `RETIREMENT_PUSH_TIMEOUT_MS = 8_000`. A relay answering
`404 Realm not found` cannot accept the tombstone, so the poll ran its **full 8s on every attempt** —
plus the round-trip, the queue purge and the store wipe. "Nothing happens" was a latency report, and
every fault I went looking for was invented to explain a delay.

Two fixes, and the first is the one that mattered:

- **`ForgetDeviceDialog` gains `working`** — it stays up with a spinner and *"Removing this device
  from your realm…"*, confirm and cancel both disabled, then navigates. ⚠ Cancel is disabled on
  purpose: by then the op is irreversible, and dismissing would drop the user on a Settings screen
  for a realm being torn down underneath it.
- **The wait short-circuits** when the relay refused the admission strip (`RevokeDeviceImplementation`
  gains an `onRelayStripFailed` observation channel; the strip stays best-effort and the callback
  never changes a revocation's outcome). Strip and push are different endpoints on the *same* relay,
  and what breaks one breaks the other.
  - ⚠⚠ It reports a distinct **`UNCONFIRMED`**, never `LEFTOVER_ROW`. A pessimistic short-circuit is
    not a diagnosis: a refused strip makes a push unlikely, not impossible, so asserting the row is
    still there would send the user hunting for something that may be fine.
  - ⚠⚠ It deliberately does **not** inspect *why* the strip failed. The relay reports a message
    string with no structured status, and matching relay errors by text is a trap this project has
    already been caught by (an H2 connection empties the reason phrase, so the match is inert in
    production and green everywhere else). Any failure is treated alike, which needs no parsing to
    stay correct.

⚠ Tests assert the **clock**, not just the wording — checking the string alone would pass with the
8s wait fully intact, which is the whole defect.

##### ⚠ Superseded: "confirming the forget does not navigate"

⚠⚠ **Every diagnosis of this was wrong, and each one was green.** The reported fault — confirm runs
the destructive half but never routes to Welcome — was blamed in turn on a call that never returns
(`forgetScope` is `rememberCoroutineScope()` and `LocalTaskExecutor` does not switch dispatcher), on
the acknowledgement-gated confirm button, and on a dead composition. Instrumentation killed all
three at once: one `AppContent`, never disposed, the call returning a real outcome, the state
written, the dialog **composed** — and nothing on screen.

The cause was the invisible modal above, which had been introduced *while investigating this very
bug*: `onDismiss` was both the only exit **and** the thing that navigated, so a dialog nobody could
see was a dead end for the whole flow. Removing the modal removes the fault.

⭐ The lesson is the sequencing, not the window: **never put the only path onward behind a surface
whose visibility you have not verified.** ⚠ And the original report — before any modal existed —
remains unexplained; if it recurs on the direct-navigation path, it is a *different* bug and the
three candidates above are still unfalsified for it.

##### ⚠⚠ Async modals on macOS desktop are not reliably visible (OPEN, wider than this flow)

Found chasing the above and **not fixed**. A Compose `AlertDialog` on desktop is a real platform
window. One opened straight from a click inherits its parent's front-most position — which is why
every dialog in Control looks fine. One raised from a coroutine or a poll does not, and lands behind
either its own parent *or* the whole application, which by then is often not frontmost. Measured:
neither `toFront()` (within-application ordering) nor `Desktop.requestForeground` (cross-application
activation), together or apart, made it appear; only closing Control's main window revealed it.

⚠⚠ **`HostPairingApprovalDialog` is the case that matters.** It is the most asynchronous modal in
the app — it arrives from `HostPairingMonitor`'s poll on no user action at all — and it refuses
passive dismissal by design, so a copy the user never sees does not fall away: the pairing device
waits, forever, on an approval sitting behind another window. Every other invisible-modal case here
cost a confusing minute; this one costs a pairing that never completes.

⭐ **`RaiseDialogToFront` is wired to it (2026-08-26), and is UNVERIFIED.** ⚠⚠ Three attempts at this
have already failed, so the fourth ships with an instrument rather than confidence. The likely reason
the earlier ones did nothing: they filtered the AWT window list to `ComposeDialog`, **a class never
confirmed to be what these dialogs are** — and a filter that matches nothing fails silently, which
fits two device runs that changed nothing at all. This version matches any showing `java.awt.Dialog`
(the primitive under `AppDialog` → `AlertDialog` → `androidx.compose.ui.window.Dialog`, so it holds
whichever wrapper is in play), does the cross-application activation *first*, and **logs the window
list it found**. ⚠ The next real pairing settles it either way — no separate test run needed, since
the dialog appears on its own during any ordinary pairing. Remove the log once it does.

⚠ Nothing in the repo can test any of this: no test reaches window activation or z-order. Every
attempt above was green and wrong.

##### ⚠⚠ It reproduced on Ph-reauth, 2026-08-26 — the raise's home moved

Reported as *"Forget this device closes the window"*. Same mechanism, a call site the `Main.kt` raise
cannot reach: it is keyed on an **`AppLockController` lock→unlock edge**, and `Reauthenticator`
**deliberately never touches that controller** (its own KDoc: *"a one-shot presence check must not
clear or re-arm `AppLockController`"*). So RA-3 inherited the bug without the fix, and so did every
other Ph-reauth site — **five** in total: forget-device (`App.kt`), revoke-device
(`SyncDashboardScreen`), and two on `RecoveryPhraseScreen`.

The raise now lives in **`DesktopAppLock.authenticate(reason)`**, the one place that knows a sheet
was actually on screen, so it covers both of that class's roles — the soft lock's gate *and* all
five reauth sites — at once. ⭐ **Device-verified 2026-08-26** — the window stays in front through
the forget prompt. `requestForegroundForThisProcess()` is process-level
(`[NSApp activateIgnoringOtherApps:]`), which is why it needs no window and can live outside
`Main.kt`. Policy is injected (`raiseToForeground`) and tested; the effect still needs a device.

- ⚠ **Only when a sheet was presented.** `Unavailable` — dormant helper, or `LAContext` declining —
  shows nothing and steals nothing, so raising there would be Control shoving itself in front of the
  user unprompted. `Cancelled` and `Failure` **do** raise: the sheet was up either way. That is the
  five-case table in `DesktopAppLockTest`, and deleting the raise turns it red.
- ⭐ It also closes a case `Main.kt` never could: **cancelling** the soft lock's own prompt leaves the
  gate locked, so there is no lock→unlock edge and the old raise stayed silent.
- ⚠ `Main.kt`'s raise is deliberately **left in place**, not replaced. It is device-verified and its
  `windowVisible` guard keeps a close-to-tray window from being yanked forward. Asking for the
  foreground twice is idempotent; deleting a verified fix to save one call is not a trade worth
  making.
- ⭐ **The biometric delegate followed (2026-08-26).** `DesktopBiometricUnlockDelegate` has its
  **own** helper runner, so the first pass left the hard unlock (`LockedScreen` → `unwrapWrapKey`)
  and `enrollWrapKey` behaving exactly as the original bug did. Both now raise, which retires the
  ⭐ above: the policy — not the runner — became the single place, which is better, because *when*
  a sheet was on screen is a per-verb fact and a shared runner would have had to be told anyway.
- ⚠⚠ **The policy is a whitelist, and "raise unless the token says `unavailable`" is WRONG.**
  `helperSheetWasPresented(command, token)` reads the table off the helper's own emissions in
  `KeychainHelper/main.swift`, because the two mistakes are not symmetric: a missed raise leaves a
  known bug in one path, while a **spurious** raise is a *new* bug — Control shoving itself in front
  of a user who was never interrupted. And it would land on the common configuration, not an edge:
  under ad-hoc signing `enroll` fails at `SecItemAdd` **before** prompting.
  ⚠ `failure:*` is the case that is **not uniform across verbs**, and reading it as uniform is the
  trap: for `authenticate` it means `evaluatePolicy` rejected the user *after* showing the sheet (a
  fumbled fingerprint — where the raise matters most), for `enroll`/`unwrap` it means
  `Security.framework` refused before presenting anything.
  ⚠ A non-answering helper (`null`) never raises: it conflates one that failed to start (no sheet)
  with one that timed out (sheet up, unanswered for two minutes), and neither earns a focus steal.

#### ⚠ Two follow-ups from that same pass, and one was a false attribution

- **The raise fires only on the lock→unlock transition**, via the pure `shouldRaiseOnUnlock(...)`
  (`:control-shared-logic` desktopMain, tested). The first version keyed on *any* change to lock
  state or window visibility, which also covered shows where nothing had been locked. ⭐ The split
  is the point: **policy is testable, the effect is not.** Whether `Desktop.requestForeground` wins
  activation from the window server needs a device; *when* a raise is warranted is a five-case
  table, and it is the half that actually broke. Same shape as `appLockGateActive`.
- ⚠⚠ **The tray panel staying open was NOT caused by any of this**, though it looked exactly like
  it. It reproduces with "Require unlock" switched off entirely — a path where no prompt is shown
  and no raise executes. The panel is dismissed by only two things (`TrayAgent/main.swift`):
  toggling the status item, and a **global** mouse-down monitor that by design does not fire for
  the agent's own events. So every button *inside* the panel — Open Control, app launches, Quit —
  ran and left the panel on screen. It now dismisses explicitly. What probably masked it before is
  `NSPanel.hidesOnDeactivate`: the panel vanished because *Control activating* deactivated the
  agent, making dismissal a side effect of activation succeeding rather than something the panel
  did for itself.
- ⚠ **The tray source already documented the original bug** — *"Control is a menu-bar-only
  (`LSUIElement`) app, so the JVM-side `window.toFront()` alone may surface the window behind the
  frontmost app — activating the process raises it."* That comment predates this work. Reading the
  tray agent first would have skipped the wrong fix entirely.

#### ⚠⚠ Platform reality for Ph-reauth — it is *not* a desktop-only gap, but desktop is the hole

Android and iOS each already have a live prompt primitive, so Ph-reauth there is new UI over something
that works. Desktop has a *built* soft lock that is **dormant in every normal build**, and off macOS it
has no backend at all:

| Platform | soft "Require unlock" | prompt primitive | Ph-reauth is |
|---|---|---|---|
| **Android** | shipped, live | `BiometricPrompt` (`BIOMETRIC_STRONG \| DEVICE_CREDENTIAL`) | new UI over a working primitive |
| **iOS** | shipped, live | `LAContext .deviceOwnerAuthentication` | new UI over a working primitive |
| **macOS desktop** | shipped, **live since RA-5** | soft lock: Touch ID **inside the window** (`LAAuthenticationView`, `MacEmbeddedAuthentication`, 2026-10-15) with *Use password* → the sheet; the sheet is `LAContext` **in-process** over FFM (`MacLocalAuthentication`, 2026-10-15); the `applock-helper` `authenticate` verb — ad-hoc `swiftc` build, embedded by default, no signing (see below) — is the fallback | live |
| **Windows / Linux desktop** | ⚠⚠ **no backend at all** — `DesktopAppLock` resolves the macOS helper only | none | unavailable; must pass through |

⚠⚠ **The macOS row was resolved on 2026-08-24 (RA-5), and the premise underneath it was wrong.**
"Blocked on signing" was inherited, never checked. The helper is Xcode-built and profile-gated
because its **wrap-key** verbs (`enroll`/`unwrap`) touch the data-protection keychain, whose
`keychain-access-groups` entitlement is restricted — AMFI SIGKILLs a binary that claims it without
a profile. But the two verbs the app-lock and every Ph-reauth gate actually use, `is-available` and
`authenticate`, are pure `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` calls that touch no
keychain item and need **no entitlement at all**. They had simply been living in the same binary.

So RA-5 needed no Apple account, no team id and no GUI-minted profile: the same `main.swift` is now
*also* built with `swiftc`, ad-hoc signed, and embedded in **every** macOS bundle by default as
`applock-helper`. Measured, not reasoned: the ad-hoc binary runs and answers `is-available` → `yes`.

⚠⚠ **The name is load-bearing.** `bundledKeychainHelperFor` accepts a *bare* `keychain-helper`
because Developer-ID inside-out signing produces exactly that — a full-capability bare binary. Had
the entitlement-free build reused that name, the biometric delegate would have resolved it,
`is-available` would answer "yes" and `has-enrolled` "no" (both verified), and the UI would offer an
enrolment that can never succeed. Honest dormancy beats a working-looking dead end, so the resolvers
are split: `bundledKeychainHelperFor` (wrap key) never sees `applock-helper`;
`bundledAppLockHelperFor` prefers the full-capability helper and falls back to it.

**Still signing-gated, and correctly so:** the Touch-ID *wrap-key* path (`embedKeychainHelper`,
`-Ptenshu.withKeychainHelper`) and desktop hard mode. **Still a pass-through:** Windows and Linux,
which have no backend at all. ⚠ Do not let that remaining asymmetry become an argument for
*skipping* the degrade-to-pass-through path — it is still the only behaviour on two platforms.

## 7. Resolved decisions

- **Default = headless** (v0.2). Lock is expert-only, under *Settings → Advanced*.
- **No migration / back-compat** — dev resets the system frequently; existing biometric devices need
  no special handling.
- **`lock()`** stays a *session-only* re-lock; the *Require unlock* toggle owns the wrapper state.
- **Accessibility = after-first-unlock** (not unlocked-device-required), so background sync survives
  a locked screen.
- **Scope of the soft lock = Control only** (2026-08-23). Ph-drive withdrawn; the follow-up is
  Ph-reauth, *inside* Control. Reasoning + the cost of the platform-wide alternative: §6.1.
- **Q1 — timed re-auth for the locked expert mode** (e.g. weekly biometric): **deferred**; v1 is
  "until you turn it off".
- **Q2 — StrongBox required vs preferred**: **StrongBox-preferred, TEE fallback**
  (`setIsStrongBoxBacked(true)`, retried without the flag on `StrongBoxUnavailableException`) —
  StrongBox is absent on many devices, so requiring it would make the default fail there.
