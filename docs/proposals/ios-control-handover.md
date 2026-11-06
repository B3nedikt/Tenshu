# Handover — Control-on-iOS (Phase 6e of the iOS proxy architecture)

> This is the **C0 deliverable** of the Control-on-iOS roadmap: the spec + sequencing that turns
> the documented Phase 6e ("Control-on-iOS co-resident proxy") into a buildable, sliced plan.
> Companion to the decision doc [`ios-proxy-architecture.md`](ios-proxy-architecture.md) (read §3,
> §9, §10, §11 first) and the phase handover [`ios-proxy-phase6-handover.md`](ios-proxy-phase6-handover.md).
> Drive-on-iOS (the EPC *client*) is shipped through slice 6b.5; this doc covers the *proxy/realm-device*
> half that an iPhone-only user needs and that Drive's first screen currently waits on.

---

## Why this exists / the problem in one paragraph

Drive-on-iOS is built and simulator-runnable, but its **first screen is locked**: it gates on a
local Tenshu Control being present, and Control does not exist on iOS yet. The gate lives in
`drive-ios/Sources/ConnectToControlView.swift`, which checks the shared Keychain
(`ControlSession.swift`: access group `app.tenshu.shared`, service `app.tenshu.control.session`,
account `epc-session`) for a session item that Control is supposed to provision. Nothing writes it;
the only way past today is the `DEV_HARNESS`-only "Simulate Control set-up" button
(`ControlSessionStore.provisionDevStub()`). For a real iPhone-only user, there is no proxy anywhere
and the platform is unusable on their primary device. **Control must ship on iOS** (§3 / §11.1) — as
a full, key-holding realm device, not a keyless EPC client.

---

## What's already decided (from `ios-proxy-architecture.md`)

These are settled; this doc does not relitigate them.

- **§3 / §11.1 — Control-on-iOS is a full realm device.** It mints the master key, holds it, does
  the crypto. The iPhone-only user is a *common* profile, not an edge case. Control-on-iOS is the
  one case where "Control" legitimately appears under Data → Devices.
- **§11.1 — three distinct flows** Control-on-iOS owes: **realm genesis** (first device, no peer),
  **Control device-pairing** (a 2nd Control joins via the existing cross-Control ceremony), and
  **EPC app pairing** (Drive ↔ Control, keyless app side). Genesis + device-pairing + key custody are
  what make this more than "a co-resident EPC endpoint."
- **§11.6 — Drive's onboarding is a forward-to-Control handoff, not an in-Drive pairing ceremony.**
  Control set up → ride its identity silently → Files. Control absent → one screen that deep-links to
  Control. The §10 QR+SAS EPC flow is dev-harness / third-party only, and lives in the debug scheme.
- **§9.4 — iOS has no persistent background process.** Continuous background sync is unachievable;
  Control-on-iOS gets best-effort, system-scheduled windows only (`BGTaskScheduler` / throttled push /
  background `URLSession`). This is the reason §9 sequenced Control-on-iOS last.
- **What's already built and reused here:** libsodium crypto actuals (6a), `:epc-rendezvous-client`
  + Darwin engine (6b), `:drive-shared-logic` iOS-ized + SKIE (6b.5), the relay `/sync/epc`
  rendezvous mailbox (3a/3b), and the proxy-side `EpcRendezvousAcceptor` / `EpcResponder` /
  `EpcAttestationServer` in `:sync-service-proxy` (these ship *with* Control on any platform).

### Repo state going in (verified 2026-06-25)

- `:control-shared` is KMP but **android + desktop only** — no iOS targets. *(Still true after C1 —
  its iOS-ization is rescoped to C2; see the C1 as-built note.)*
- `:control` is a plain Android app; `:control-desktop` is Compose Desktop. ~~No `control-ios/` app
  module exists.~~ — **C2b stood up `control-ios/`** (XcodeGen SwiftUI shell over fakes, simulator-green).
- ~~No iOS master-key custody (O6)~~ — **closed by C1**: `IosSecureStorageDelegate` (the iOS
  `MasterKeyStore`) landed in `core-common/iosMain`, simulator-test-green. No iPhone genesis UX (O7),
  no real Apple App Attest verifier (6c/6d), no background-wake fabric (O4).

---

## Data-plane decision — solo-iPhone rides the in-process collapse (2026-06-25)

The one decision that reshapes the back half of the roadmap. For the **solo-iPhone user** (Control +
Drive on one phone, no remote proxy), there were two routes:

1. **In-process collapse** (§11.2 / §11.5) — Drive runs the realm *crypto* in-process, riding
   Control's single device sync identity through the App Group; it never reaches out to Control at
   runtime.
2. **EPC to local Control** — Drive reaches the co-resident Control over the relay-rendezvoused EPC
   channel, same mechanism as a remote proxy (the documented v1 line).

**Decision: anchor the solo-iPhone case on route 1 (in-process collapse).** Route 2 is retained but
**only for the remote-proxy case (already shipped) and for future third-party apps** (which cannot
share the App Group and are forced onto keyless EPC regardless — §4).

### Rationale

- **EPC against a *co-resident* proxy is worse than ordinary network.** The EPC baseline is
  relay-rendezvoused (§5), so "Drive → local Control" is by default **phone → relay → same phone** —
  a server round-trip for same-device traffic, plus a *second* crypto layer (EPC seal/open wrapping
  the realm crypto). The O1 foreground-loopback fast-path only works when both apps are foregrounded.
- **The wake-window problem bites exactly the interactive case.** iOS does not keep Control awake
  because Drive is foreground. Drive-foreground + Control-suspended is the *common* moment, and it's
  when you'd need Control responsive — you'd have to push/`BGTask`-wake it on every interaction
  (seconds-to-never latency). Route 1 sidesteps this entirely: Drive does not depend on Control's
  liveness at runtime.
- **Route 2's good case is already built.** iPhone + desktop/Android Control (a *remote*, always-on
  proxy) is the shipped Drive-on-iOS v1. The data lives elsewhere, the round-trip is
  unavoidable and fine. We don't need route-2-to-*local*-Control to serve that.
- **No third-party iOS apps exist yet.** That track is unsized and gated on `:rei-service` (absent).
  So route 2's near-term *only* consumer would be Drive itself — in the one scenario (co-resident)
  where route 2 is worst.
- **The cost is a one-time foundation.** Route 1's price (§11.5) is real and sharp: a *single* HLC
  authority + single signing key + single shared replica per device, with first-party writes
  serialised through one on-device sync core (App Group advisory locks + device key in the shared
  Keychain). But it's paid once and reused by every future first-party lens. Route 2's wake fabric is
  permanent OS-fighting for a same-phone destination.

### The resulting matrix

| Profile | Data plane | Status |
|---|---|---|
| iPhone + desktop/Android/Hub Control (remote proxy) | EPC-over-relay to the remote proxy | **Shipped** (Drive-on-iOS v1) |
| iPhone-only (co-resident Control) | **In-process collapse (route 1)** | This roadmap (C4) |
| Third-party iOS app (future) | EPC to local Control (route 2) | Deferred to the third-party / Rei track |

### Caveat / fallback

Route 1's cross-process HLC coordination is the sharpest engineering in the whole iOS effort: two
processes (Control + Drive) writing under one `deviceId` must **not** fork the HLC (the regression
detector rejects forks → corrupted merge). If single-writer discipline over the App Group proves
intractable, the fallback is route 2 to the local Control + aggressive push-wake, accepting the
degraded UX. App Attest justifies *trusting* a first-party app to share the identity — it does
nothing for the HLC/key/replica coordination, which must be built. (§11.5 is the canonical spec.)

This refines **O3**: the in-process exception is *in-process crypto over a shared device sync
authority*, not an independent per-app direct-to-relay writer. Threat-model note rides the
implementing PR (House Rule #2 — data-plane trust model change).

---

## Roadmap — Control-on-iOS, sliced (C0–C6)

Dependency spine: **key custody → genesis → provisioning bridge → data plane**, then hardening.
Each slice is independently shippable. Apple-gating is called out per slice.

⚠ **Screen parity is not tracked by this roadmap, and three Compose Control screens have no iOS
sibling:** `offlinemaps`, `notifications` and `shares`. Offline maps is written up separately —
[`ios-control-offline-maps.md`](ios-control-offline-maps.md) — because it is **not** a port
question: D5's "Control owns the shared region store" is Android-shaped, and under route 1 every app
is its own proxy. The other two are written up in
[`ios-control-notifications-and-shares.md`](ios-control-notifications-and-shares.md) — the
**inverse** case: their view models already live in `:control-shared-logic`, so they really are
unwritten SwiftUI. ⚠ One catch each — iOS binds `NoopNotificationPresenter` *deliberately* (§3),
and Shares' shared-folder half has been stubbed since before GR landed (§4).

### C0 — Spec & decisions ✅ (this doc)

Close O6 (custody approach), O7 (genesis UX shape), and the data-plane route (above). No code.
**No Apple Team ID needed.**

### C1 — iOS `MasterKeyStore` (closes O6) ✅ LANDED (simulator-green; on-device pending)

Build the **Secure Enclave-backed wrap key + Keychain persistence, Face ID / passcode gated** — the
iOS analog of the desktop `MasterKeyStore`
([`desktop-masterkeystore.md`](desktop-masterkeystore.md) macOS Touch ID / login-password path). This
is the load-bearing foundation: Control cannot hold a realm master key without at-rest custody.
- *Threat-model:* key-custody amendment (mirrors the desktop MasterKeyStore entry).
- *Apple-gating:* Secure Enclave needs a real device for full verification; the Keychain path and the
  KMP iOS-ization compile + unit-test on the simulator.

**As-built (2026-06-25 — scope refined with the maintainer; "focused keystore foundation").**
The slice title's "iOS-ize `:control-shared`" was **rescoped to C2**: `:control-shared` cannot take
iOS targets as-is — its `commonMain` carries extensive Compose UI (`ui/`), depends on the
Compose-only `:ui-core`, and pulls `:identity-service-proxy` (no iOS target) into `commonMain`.
iOS-izing it therefore *requires the Drive-style `-logic` / `-ui` Compose split first* — a large
refactor, and exactly what **C2** needs anyway to bind logic-layer VMs to SwiftUI. So C1 delivers
**only the keystore foundation**, placed where it belongs structurally rather than dragging the
Compose closure to iOS:
- **`IosSecureStorageDelegate`** + `IosKeychain` seam + `SecurityFrameworkKeychain` (real
  `Security.framework` impl) landed in **`core-common/iosMain`** — the symmetric home, mirroring
  `AndroidSecureStorageDelegate` in `core-common/androidMain` (the `SecureStorageDelegate` interface
  already lives in `:core-common`, which already has an `iosMain` + `platform.Security` usage +
  libsodium cinterop). **No new module; no iOS targets added to `:control-shared`.**
- **Design** (mirrors desktop `DesktopMasterKeyStore` + `WrappingKeyStore`): two buckets — non-secret
  strings as plaintext Keychain items (the Keychain encrypts them at rest), secrets **AEAD-sealed**
  (libsodium XChaCha20-Poly1305, the existing cross-platform `AeadBackend`) under a single 32-byte
  **wrap key** concentrated as one Keychain item. The wrap key has a non-prompting **baseline**
  (`AfterFirstUnlockThisDeviceOnly`) and a **Secure-Enclave / `SecAccessControl` Face ID/passcode
  gate** (`userPresence`), selectable via a `gateSecretBucket` flag; the unwrapped key is cached
  per-process so the gate prompts once per launch. Unlike desktop, the gate runs **in-process**
  (`LocalAuthentication` via the Keychain ACL — **no Swift helper**). `getSecret` returns `null` on
  any decrypt failure (Android parity), all items are `ThisDeviceOnly` (out of iCloud/backup).
- **Deferred to C2 (deliberate):** (a) the `:control-shared` Compose `-logic`/`-ui` split + its iOS
  targets; (b) a separate `BiometricUnlockDelegate` iOS `actual` — on iOS the Face-ID gate is
  *intrinsic* to the wrap-key Keychain ACL (not a Compose-host enroll/unwrap dance), so the only
  thing left is wiring *when* the gate flips on through the SwiftUI unlock flow, which is C2. The
  cross-platform passphrase floor (`UNLOCK_WRAPPER`, Argon2id) already works, so biometric is never
  the sole credential.
- *Threat-model:* **A32** (iOS At-Rest Master-Key Store, [`threat-model.md`](../sync/threat-model.md)
  §5) + **T-C-18** (§8.4 Client device) landed, mirroring the desktop A21 / T-C-17.
- *Verification:* `./gradlew :core-common:iosSimulatorArm64Test` green (12 tests — round-trips,
  null-on-failure, at-rest sealing, plaintext string bucket, single concentrated wrap key,
  cross-instance persistence, and the gated-vs-baseline wrap-key protection) against the **real**
  libsodium AEAD + a **fake** Keychain. `SecurityFrameworkKeychain` (the real `Security.framework`
  cinterop) **compiles** for `iosSimulatorArm64` but is **not** headless-runtime-tested — the real
  Keychain needs a host app / simulator entitlements + a device passcode (same caveat the desktop
  helper carries); on-device/simulator-app exercise is pending C2's `control-ios/` shell.

### C2 — `control-ios/` app shell + realm genesis (closes O7)

**Sliced into three PRs (decided with the maintainer 2026-06-25):** C2a (the `:control-shared`
Compose split + iOS targets — the foundation), then C2b (the `control-ios/` SwiftUI app shell over
fakes), then C2c (the genesis onboarding UX). Genesis is built **over fakes first**; real
proxy-backed on-device genesis is a later follow-up overlapping C4 (it needs iOS-izing the
`sync-service-proxy` / `identity-service-proxy` closure — see §"genesis depth" below).

#### C2a — split `:control-shared` → `:control-shared-logic` (+ iOS targets) ✅ LANDED (all targets green)

The deferred-from-C1 Compose split. `:control-shared` **is** the proxy (unlike Drive, which runs no
proxy), so the split is asymmetric: the existing `:control-shared` keeps its name and retains the
Compose UI **and** the in-process proxy runtime (`ProxyModule`, `PlatformProxyModule`, the EPC
*server*, entitlement/storage init, the repository *impls*); only the **client-facing, Compose-free
logic** moves down to a new **`:control-shared-logic`** (android + desktop + `iosArm64` +
`iosSimulatorArm64`).
- **Moved (56 files):** all VMs (under their `ui.screens.*` packages, Compose-free), repository
  interfaces + the `Tenshu*`/`Mock*` impls, the data managers (`ShareManager` / `BackgroundIngestManager`
  / `ErrorEventsManager`), `Event`/`LandingState`/`PendingRequest` models, `NotificationMapper`,
  `HostPairingMonitor`, and the `BiometricUnlockDelegate` **contract**. Packages unchanged
  (`app.tenshu.control.*`) so screens and their VMs share a leaf package across the two modules with
  **zero import churn** (the `:drive-shared-logic`/`-ui` trick).
- **Stayed in `:control-shared`:** all Compose screens/components/navigation, `di/` (`ProxyModule` +
  `ViewModelsModule`), the proxy-entangled `epc/` package (`EpcClientConnection` → `core.common.proxy`),
  the `[P]` entitlement/storage-gate init, and the **`@Composable rememberBiometricPromptHost()`**
  resolver (the only Compose-coupled half of the biometric surface).
- **Biometric seam:** the `BiometricUnlockDelegate` interface + result types + `expect class
  BiometricPromptHost` + the `Android`/`Desktop` delegate impls moved to logic; the
  `BiometricPromptHost` actual's constructor was made **public** (was `internal`) so the
  `:control-shared` resolver can build it, with the backing `activity` field still `internal` to
  logic. An inert iOS `BiometricPromptHost` actual landed (no iOS delegate yet — the C1 Keychain ACL
  is the gate; a dedicated iOS delegate is deferred).
- **Umbrella:** `:control-shared-logic` is `export`ed (+ `api`'d) from `:tenshu-sdk-ios`, so Control's
  VMs land in the Swift-facing `TenshuSdk.xcframework` (SKIE). `:control-shared` (Compose) is **not**
  exported, mirroring `:drive-shared-ui`.
- **One cross-module wrinkle fixed:** `RealmDataPresentation` (Compose, stayed) held the type ordering
  `DataViewModel` (moved) needed → extracted a Compose-free `realmDataSortOrder(typeId)` into logic as
  the single ordering source. Two `if (x.prop != null) { use x.prop }` smart-casts on now-cross-module
  properties (`HomeScreen`, `PendingRequestCard`) were rewritten with a local capture.
- **Tests follow their subject:** every `:control-shared` desktopTest whose subject moved to logic
  moved with it (18 of them — VM/biometric tests plus `HostPairingMonitor`, `ConstraintSyncGate`,
  `PendingRequestAlerts`, `NotificationMapper`, the `Mock*` impls, …); they couldn't reach those
  classes' `internal` members across the new module boundary otherwise. The standalone
  `FakeBiometricUnlockDelegate` test fake moved too.
- *Verify (all green):* `:control-shared-logic` compiles desktop/`iosSimulatorArm64`/android and its
  desktopTest passes (**18 classes, 91 tests, 0 failures**); `:control-shared` still compiles
  desktop/android and its desktopTest passes (**50 classes, 194 tests, 0 failures**); `:control`
  (Android app) + `:control-desktop` (`compileKotlinJvm`) consumers compile; and
  `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64` compiles with the new `:control-shared-logic`
  export. (Full `assembleTenshuSdkDebugXCFramework` / on-device run pending C2b's `control-ios/` shell.)

#### C2b — `control-ios/` SwiftUI app shell over fakes ✅ LANDED (simulator build + launch verified)

Stood up the `control-ios/` XcodeGen SwiftUI app mirroring `drive-ios/`, binding the exported
`:control-shared-logic` VMs to SwiftUI via the §9.3 pattern over **fakes**.
- **`ControlSurface`** (`tenshu-sdk-ios/src/iosMain/.../ControlSurface.kt`) — the Control twin of
  `DriveReadSurface`: a singleton that constructs the VMs directly (no Koin — Control's DI is in the
  Compose `:control-shared`, not on iOS) over installable repos defaulting to the
  `:control-shared-logic` `Mock*` impls (`MockEventRepository` / `MockRealmDataClient` /
  `MockAppRegistry`). `homeBound()` wires `HomeScreenViewModel`'s paged `Flow<PagingData<Event>>`
  through a `TenshuPagingPresenter<Event>` snapshot; `dataViewModel()` / `appsViewModel()` /
  `settingsViewModel()` expose the rest.
- **App (`control-ios/`):** `project.yml` (copy of drive-ios — simulator-only, `EXCLUDED_ARCHS`
  x86_64, pre-build `assembleTenshuSdkDebugXCFramework`, links the static umbrella, gitignored
  generated project/`Info.plist`), `ControlIosApp` (`@main`, a 4-tab `TabView`), and `HomeView` /
  `DataView` / `AppsView` / `SettingsView` — each a `@MainActor ObservableObject` wrapping its Kotlin
  VM and observing `vm.uiState` (or `presenter.snapshot`) as a SKIE `AsyncSequence`, exactly the
  drive-ios `FilesModel`/`SharedModel` shape.
- *Verify:* `xcodebuild … -scheme ControlIos -sdk iphonesimulator` **BUILD SUCCEEDED**; the app
  **boots + launches** on the iPhone-16 simulator and renders (Home shows the 60 mock events flowing
  through the VM → presenter → SwiftUI, proving the binding round-trip; Data/Apps/Settings tabs
  present). No `ControlSession.swift` yet — Control is the *writer* of the `epc-session` item (C3),
  not a reader, so the gate-reader isn't needed in the shell. Genesis-driving VMs (`NewUserViewModel`
  / `RecoveryViewModel`, which need a `SyncRepository` + biometric fake) are **C2c**.

#### C2c — genesis onboarding UX ✅ LANDED (simulator-green; on-device pending)

First-run onboarding (the only genuinely new UX): **create realm → mint master key → silent BIP-39
phrase**, bound to the logic VMs over fakes. The shell's first screen is now the genesis flow, gating
entry into the C2b main tabs.

- **Reusable fakes (`tenshu-sdk-ios/src/iosMain/.../FakeControlRepositories.kt`):** `FakeControlSyncRepository`
  (the full 25-method `SyncRepository`, success-only — `createRealm`/`setUpUnlock`/`recoveryClaim` all
  succeed so the state machines run to completion) + `FakeControlBiometricUnlockDelegate` (reports
  *unavailable* → the flow stays on the cross-platform passphrase floor; there's no iOS biometric
  delegate yet — on iOS the wrap-key gate is intrinsic to the C1 Keychain ACL, not an enroll/unwrap
  delegate). Lifted from the `:control-shared-logic` desktopTest inline fakes into the umbrella's
  iosMain so SwiftUI can bind the genesis VMs (the drive-ios `FakeFilesRepository` shape).
- **`ControlSurface` (extended):** `genesisViewModel()` / `recoveryViewModel()` build the VMs over the
  installed fakes (install seams added for sync repo + biometric). The sealed `step` / typed-error
  states are mapped to **plain `String` keys + `String?` copy Kotlin-side** (`genesisStepKey` /
  `genesisErrorMessage` / `recoveryStepKey` / `recoveryErrorMessage`) so the Swift side never switches
  on a SKIE-bridged sealed type. ⚠ **`new`-prefix gotcha:** methods named `newUser*` were silently
  dropped from the generated ObjC header — Objective-C reserves the `new` method family and
  Kotlin/Native won't export `new`-prefixed selectors. Renamed to `genesis*`; the `recovery*` twins
  were unaffected.
- **App (`control-ios/Sources/`):** `ControlRootView` gates `OnboardingView` vs `MainTabsView` on an
  in-memory `setUp` flag (the durable "am I already a realm device?" probe is C4). `OnboardingView` +
  `NewUserView` drive the genesis state machine (credential choice → passphrase floor → Bootstrapping
  spinner → "You're set up" → Open Control); `RecoveryView` drives the 24-word recovery machine (a
  single text area split on whitespace into the VM's 24 word slots → Processing → Success). Each is the
  same `@MainActor ObservableObject` + `for await … in vm.uiState` shape as C2b; fields are owned
  locally and forwarded to the VM (the VM stays the validation source of truth).
- *Verify:* `xcodebuild … -scheme ControlIos` **BUILD SUCCEEDED**; the app **boots + launches** on the
  iPhone-16 simulator and renders the genesis welcome ("Start fresh on this device" + "I already have a
  Tenshu account" → recovery), bound to the real `NewUserViewModel`. Step transitions are VM-owned and
  covered by `:control-shared-logic`'s 91 unit tests; the Swift mappers are pure.
- *Genesis depth:* over fakes. Real proxy-backed on-device genesis needs the `sync-service-proxy` /
  `identity-service-proxy` closure on iOS — overlaps C4's in-process collapse.
- *Apple-gating:* simulator-buildable; on-device run needs a Team ID + signing.

### C3 — The provisioning bridge ✅ LANDED (writer + wiring; cross-app read signed-build-gated)

Control writes the shared-Keychain `epc-session` item that Drive's `ControlSession.swift` already
reads, replacing the role of `ControlSession.provisionDevStub()`. The smallest change that flips
Drive's gate from fake → real — Drive's onboarding code does not change (built end-state per §11.6);
only the *writer* becomes real.

- **Writer seam (`core-common/src/iosMain/.../epc/ControlSessionProvisioner.kt`):** `ControlSessionProvisioner`
  (`provision(handle)` / `clear()`) over a narrow `SharedKeychainWriter` seam (split out so the
  coordinates + handle logic are unit-testable against a fake — the real `Sec*` calls need a running
  Keychain, exactly as C1's `SecurityFrameworkKeychain` is). `SecuritySharedKeychainWriter` is the
  real `Security.framework` impl. It writes a `kSecClassGenericPassword` at the **exact coordinates**
  `ControlSession.swift` reads — service `app.tenshu.control.session`, account `epc-session`, access
  group `app.tenshu.shared`, value = the handle as UTF-8 — `AfterFirstUnlockThisDeviceOnly`. The
  **handle is an opaque presence token** (16 random bytes, hex); Drive only checks presence + non-empty
  decode. The richer payload (on-device rendezvous / shared sync-authority refs) is **C4** — this slice
  writes only the bridge.
- ⚠ **Retired 2026-11-05 (route-1 cleanup D):** Control no longer mints the presence token,
  `provisionDriveSession()` is deleted, and onboarding calls `ensureDriveProvisioned()` (route-1 key
  material only). The `epc-session` item survives for `publishEpcInvite` and `clearDriveSession()`.
  The bullets below are the as-built record.
- **Wiring:** `ControlSurface.provisionDriveSession()` / `clearDriveSession()` (umbrella iosMain);
  `control-ios` calls `provisionDriveSession()` at the onboarding handoff — `NewUserView`'s "Open
  Control" and `RecoveryView`'s success-continue — so a real handle lands when onboarding finishes.
- ⚠ **Cross-app read is signing-gated.** The shared `app.tenshu.shared` access group requires **both
  apps signed under one Team ID** with the `keychain-access-groups` entitlement (the macOS
  FileProvider already uses `$(TeamIdentifierPrefix)app.tenshu.shared`). On the **unsigned simulator**
  the shared-group write gets `errSecMissingEntitlement` and falls back to an app-local item (so
  onboarding still completes) — but two unsigned sim apps don't share a keychain scope, so the
  Drive-reads-Control's-item round-trip can't be shown on the sim. **Remaining for the live unblock:**
  add the `keychain-access-groups: app.tenshu.shared` entitlement to both `control-ios` and `drive-ios`
  (+ a Team ID) and run signed / on-device.
- *Verify:* `:core-common:iosSimulatorArm64Test` green (provisioner coordinates + handle generation
  against a fake `SharedKeychainWriter`); umbrella iOS compiles + `provisionDriveSession`/`clearDriveSession`
  exported; `control-ios` **BUILD SUCCEEDED** + boots/launches/renders genesis (provisioner constructs
  without crashing). The real `SecItemAdd` path + the signed cross-app read are device/signed-build
  work (as with C1's keychain).

### C4 — Data plane: in-process collapse (the route-1 build)

Wire Drive's in-process realm crypto riding Control's **single device sync identity** through the App
Group (§11.5). The load-bearing work:
- single signing key + single per-device HLC clock + single shared replica across the two processes;
- single-writer discipline via App Group advisory file locks + device key in the shared Keychain;
- first-party writes serialised through one on-device sync core (in-process *crypto*, **not** an
  independent direct-to-relay writer).
- *Threat-model:* data-plane exception (O3 / §4) amendment in the same PR.
- *Fallback:* if cross-process HLC coordination proves intractable, fall back to route 2 + push-wake
  (see caveat above).

#### C4 readiness + sub-slice plan (recon 2026-06-25 — pre-build scoping)

C4 is **not a single PR**: it's the largest and only architecturally-forked slice. Two findings from
the readiness recon reshape the plan:

1. **The proxy sync core has no iOS targets, and iOS-izing it is a cascade — not a target declaration.**
   `:sync-service-proxy` (which holds `SyncManagerImpl`, the device identity, HLC integration, the
   sync core) is android/jvm/js-only, and it `api`-depends on `:sync-service`, `:identity-service`,
   `:contact-service`, `:contact-service-common`, `:epc-rendezvous-client` — **none of which have iOS
   targets** — plus a Ktor client that needs the Darwin engine on iOS. So standing the sync core up on
   iOS means iOS-izing that whole transitive proxy graph (and supplying iOS actuals for anything
   currently jvm/android-bound — notably the durable local store: `SqliteObjectStore` is jvmMain-only;
   iOS has only `InMemoryObjectStore`). This is a multi-module effort, likely several PRs.
   *iOS-ready already (C1–C3 + earlier):* `:core-common` (libsodium, `HLCManager`, `IosSecureStorageDelegate`),
   `:core-proxy` + `:file-service-proxy` (targets declared), `:sync-service-common`, `:control-shared-logic`.
2. **The route-1 / route-2 fork is unresolved and gates the App-Group internals.** The single-writer
   discipline (App-Group advisory file locks + shared HLC state file + shared replica) is *route-1
   specific*. If on-device HLC-coordination proves intractable, the fallback is route 2 (Drive → local
   Control over relay-rendezvoused EPC + push-wake), in which the App-Group file-coordination work is
   moot. Resolving the fork needs **on-device** measurement, which needs a signed build (Team ID) —
   the same gate as C1–C3's on-device items. So building the App-Group internals now is speculative
   until the fork resolves.
   **Fork-independent fact:** *both* routes need Control's sync core running on the iPhone (route 1 to
   share it in-process; route 2 because Control-on-iOS is then a full EPC proxy endpoint). So
   iOS-izing the proxy sync core (finding 1) is **not wasted regardless of the fork** — it's the
   genuine critical path.

**Proposed sub-slices** (each its own PR; the cascade ones may sub-split further):

- **C4a — iOS-ize the proxy sync core (fork-independent critical path). ✅ LANDED.** The cascade was
  far shallower than the recon feared: of `:sync-service-proxy`'s transitive graph, **only two modules
  lacked iOS targets** — `:identity-service-proxy` and `:sync-service-proxy` themselves; every other dep
  (`:sync-service`, `:identity-service`, `:contact-service`(+`-common`), `:epc-rendezvous-client`,
  `:core-proxy`, `:file-service-proxy` and its `:notification-service-proxy`/`:storage-service-proxy`,
  `:sync-service-client`, …) already had them. Added `iosArm64()` + `iosSimulatorArm64()` to those two
  (no `iosX64` — paging-common has no x64 klib; no per-module `.framework`). **Zero source changes** —
  both modules' commonMain was already KMP-honest (no Darwin Ktor engine needed to *compile*; the engine
  is only instantiated at the umbrella, C4e). *Verify:* `:identity-service-proxy` + `:sync-service-proxy`
  `compileKotlinIosSimulatorArm64` + `…IosArm64` + `…Jvm` all green (klibs produced). Note: the root
  `build.gradle.kts` (lines 116–131) **deliberately skips iOS *test* compile/run for all `-proxy`
  modules** (Phase-1 deferral; the heavy K/N test-link OOMs) — only `main` iOS compilation runs, which
  is the intended bar. Re-enabling proxy iOS tests for these two now that they have an iOS consumer is a
  follow-up (the root comment flags possible K/N-vs-JVM proxy-logic gaps to fix first).
- **C4b — durable on-device local store. ✅ LANDED.** `FileObjectStore` (`core-common/iosMain`) — a
  durable, dependency-free file-backed `ObjectStore` replacing the non-durable `InMemoryObjectStore`
  fallback, so realm state survives process death. The iOS analogue of jvm's `SqliteObjectStore`:
  same cache-aside shape (in-memory cache authoritative for reads + listeners, hydrated on `open`) and
  the **same `DbEntryCodec` blob format** — `DbEntryCodec` was moved jvmMain→commonMain (it's pure
  `kotlinx.io`, already a commonMain dep — zero build change) so both stores share one codec. Backing
  is one file per object (`<hash>.obj`), each write a *synchronous, atomic* `NSData.writeToFile(atomically
  = true)` (write-temp-then-rename), so a committed entry is durable the moment the call returns —
  `flushBarrier` is a no-op (no async write-behind tail to lose, unlike `SqliteObjectStore`). Trade-off:
  sync file IO under the `DatabaseLock` (batching is a noted future optimisation, not needed for
  correctness). **App-local** (`applicationSupportDirectory` under Documents — always available, no
  entitlement); the shared App-Group container that lets Control + Drive read one replica is route-1
  (C4d/C4e). *No threat-model change:* at-rest encrypted user data (A5) under the OS file-protection
  class — same custody class as the existing stores; persists no new key material. *Verify:*
  `:core-common:iosSimulatorArm64Test` — 7 `FileObjectStore` tests green (cold-reopen durability,
  remove-then-reopen, listeners, ByteArray/GenericValue survive a reopen byte-for-byte, negative
  hashes) + jvm `SqliteObjectStore` (desktopTest) / JS unaffected by the codec move.
- **C4c — device signing-key (A4) persistence. ✅ LANDED.** Closed the `SyncManagerImpl`
  in-memory-key TODO: `getDeviceSigningKeyPair()` now loads-or-generates-and-persists the A4 keypair
  via `SecureStorageDelegate.getSecret/storeSecret` (`Keys.DEVICE_SIGNING_KEY`, blob
  `Base64(pub)|Base64(priv)`; malformed → regenerate). Cross-platform — fixes a real Android/desktop
  churn (A4 was regenerated every launch, re-registering a fresh relay pubkey each time) and is the
  prerequisite for iOS. **Stored in the per-app secret bucket**; the shared-Keychain custody that lets
  Control + Drive read *one* A4 is route-1 (C4d/C4e). Threat-model **A4 amended** (House Rule #2).
  *Verify:* `:sync-service-proxy:jvmTest` green (3 new tests — round-trip + load-precedence +
  malformed-recovery, written to *not* be vacuous given the deterministic test crypto; full suite no
  regression) + `compileKotlinIosSimulatorArm64` green. Fork-independent.
- ⚠ **The probe routes and probe types in the entries below** (`-appGroupWrite`, `-clockTick`,
  `-publishRealmKeys`, `-registerAndPublish`, `-decryptDriveBlock`, `-pullDriveBlock`, `-pullApplyFolder`,
  Drive's `-appGroupRead`/`-readRealmKeys`/`-encryptRealmBlock`/`-relay*`, `AppGroupProbe`,
  `RealmCryptoProbe`, `RelayAuthProbe`, `RelayBlockProbe`, `RelayFolderProbe`) **were deleted on
  2026-11-05**; their last version is at `066f6f363`. The entries are a record, not a how-to.
- **C4d — App-Group file-coordination substrate (route-1). ✅ SIM-CONFIRMED (primitive + viability spike + shared-container probe).**
  `FileAdvisoryLock` (`core-common/iosMain`) — a `flock(2)`-based cross-process advisory lock (the
  single-writer-discipline primitive). Plus **the route-1 viability spike** (`CrossProcessHlcCoordinationSpikeTest`,
  the *free* HLC-coordination probe) — see "Resolving the route fork" below for what it settled. **The
  two items this entry long marked "signing-gated" — the shared App-Group container-path accessor and a
  genuine two-process `FileAdvisoryLock` cross-process check — are now both confirmed *on the ad-hoc
  simulator with no paid Apple membership*, exactly as the C3 lesson predicted (the ad-hoc-sim
  trick extends from Keychain Sharing to App Groups).** New `AppGroupProbe` (`:tenshu-sdk-ios`, shared by
  both apps) resolves `containerURLForSecurityApplicationGroupIdentifier("group.app.tenshu.shared")`;
  both `control-ios` + `drive-ios` declare the **bare** `com.apple.security.application-groups`
  entitlement (no team prefix — ad-hoc has no team). **Verified A/B (iPhone-16 ad-hoc sim):** Control
  (`-appGroupWrite`) resolves `…/Containers/Shared/AppGroup/<UUID>`, writes a marker, acquires+holds the
  `FileAdvisoryLock`; Drive (`-appGroupRead`) resolves the **same `<UUID>` container**, reads Control's
  marker (shared-container round-trip ✓), and its `tryLock()` returns **false** while Control holds it
  (genuine cross-process exclusion ✓ — the confirmation the spike said needed two real processes). So
  the **route-1 substrate (shared replica path + single-writer lock) is buildable + testable on the sim
  with no $99 membership**; the paid gate applies only to a *real device* (Team-prefixed group + one
  team). *Remaining for C4e: the assembly itself.*
- **C4e — in-process collapse assembly + single-writer discipline.** Assemble Drive's in-process
  `SyncManager` over the shared identity/replica; serialise writes; crash recovery. The on-device-heavy
  finale. *Threat-model O3/§4 data-plane exception amendment here.*
  - **C4e-1 — relocate the on-device replica into the App-Group container. ✅ LANDED.** The
    first C4e brick, unblocked by C4d: the durable `FileObjectStore` (C4b) now opens at the
    **shared App-Group container** instead of the app-private Documents dir, so the realm replica lives
    in the substrate Drive can reach — the precondition for the in-process read. New
    `sharedReplicaDirectory("objects")` (`core-common/iosMain`) = `appGroupContainerDirectory(TENSHU_APP_GROUP, …)`
    (over `containerURLForSecurityApplicationGroupIdentifier`) with a graceful fallback to
    `applicationSupportDirectory` for an entitlement-less build; the iosMain `PlatformProxyModule`
    `single<ObjectStore>` swaps to it (the only wiring change — Android/desktop/Compose untouched).
    **Additive, iOS-only.** **Verified (iPhone-16 ad-hoc sim, erased clean):** Control `-genesisAutoComplete`
    → *"Realm created + persisted"* writing **into the App-Group `objects/`** dir (no regression from
    the relocation); Control relaunch (no args) → the durable probe reads the persisted realm from the
    new location → the **Locked / "Welcome back"** screen; then `drive-ios -appGroupRead` reports
    **`objects/ .obj blobs: 9`** + *"Control's realm replica (9 blobs) visible to Drive in the shared
    container"* — Control's genesis blobs are physically in the shared container and Drive reads them
    **cross-process** (`AppGroupProbe.objectStoreBlobCount()`). So the replica is co-resident; **C4e-2 is
    Drive opening that `FileObjectStore` and reading a row in-process** — no EPC, no relay, no
    second-process suspension (the route-2 wake-wall is what this route avoids).
  - **C4e-2 — Drive's in-process *structured read* of the shared replica. ✅ LANDED.** Goes
    beyond C4e-1's opaque blob *count* to the realm *content*: Drive decodes Control's replica into
    structured `DbEntry` rows **in its own process**. ⚠ **Framing correction from C4e-1's note above:**
    the read needs **no crypto and no Keychain** — the on-disk blob is **already-decrypted plaintext**
    (`DbEntryCodec` is opaque framing, not encryption; the sync-block crypto plane is upstream at ingest
    on Control, and the replica is protected at rest by OS file protection). So "decrypting a row + Control's
    device identity" overstated it — a co-resident first-party reader just *decodes*. New
    `readObjectStoreEntries(directory)` (`core-common/iosMain`, store-less read-only decode; no
    cache/listeners/writes) + `AppGroupProbe.readReplicaEntries()` (reads `sharedReplicaDirectory("objects")`
    — the same path Control writes — and summarises each row's schema `type` + a sample of field values) +
    drive-ios `-replicaRead` harness. **Additive, iOS-only** (no protocol/wire/key change). **Threat-model
    A5 amended** (House Rule #2): the shared-App-Group replica is now read in-process by a co-resident
    first-party app — relay exposure unchanged (House Rule #1), no key custody change, OS-protected at rest;
    the device-exposure delta (two first-party apps share one at-rest container) is deliberate and
    within-user. **Verified (iPhone-17 ad-hoc sim, erased clean):** Control `-genesisAutoComplete` writes
    9 realm blobs into the App-Group `objects/`; drive-ios `-replicaRead` decodes all **9 rows in-process**
    and renders real content — `tenshu/identity/device ["…","iPhone 17",<32B>,true]`,
    `tenshu/file/folder ["/",…]`, `tenshu/sync/block`, `tenshu/core/time/hlc`, `tenshu/storage/status` —
    *"Drive decoded 9 realm rows from the shared replica IN-PROCESS (no EPC/relay/2nd process)."* The
    route-1 **read** plane is proven; the write-side single-writer assembly (C4e-3) remains.
  - **C4e-3a — the route-1 *write* direction (non-owner write under the single-writer lock). ✅ LANDED.** The symmetric counterpart to C4e-2's read: drive-ios (the **non-owner** process) durably
    writes a row into Control's shared App-Group replica through the **real** `FileObjectStore.storeObject`
    atomic path, **serialised by the cross-process single-writer `FileAdvisoryLock`** (`tryLock` on a
    dedicated `replica.writer.lock`; the exclusion mechanism is the one C4d/#749 proved excludes
    cross-process), and the owner Control process reads the row back cross-process. New
    `AppGroupProbe.writeReplicaProbeEntry(tag)` + drive-ios `-replicaWrite` (writer) + control-ios
    `-replicaReadProbe` (owner read, store-less — `init()` skips the proxy runtime for it). ⚠ **Substrate
    only:** the probe writes a tagged `tenshu/probe/c4e3` `DbEntry` directly — it is **not** a real realm
    mutation (a genuine write needs the in-process `SyncManager` + HLC + sync-block crypto to produce a
    relay block — C4e-3b+). **Additive, iOS-only** (no protocol/wire/key change). **Threat-model A5
    amended** (House Rule #2): the shared replica is now read **and written** in-process by the co-resident
    first-party Drive app; App-Group containers are read-write to all members, so write *integrity* rests on
    the single-writer `FileAdvisoryLock` (both apps trusted writers within the user's app group) — relay
    exposure unchanged (House Rule #1), no key custody change. **Verified A/B (iPhone-17 ad-hoc sim, erased
    clean):** Control `-genesisAutoComplete` → 9 blobs; drive-ios `-replicaWrite` → *"Drive wrote a probe
    row… under the FileAdvisoryLock (single-writer); replica now holds 10 rows"* (blob count 9→10);
    control-ios `-replicaReadProbe` → *"Control (owner) reads Drive's lock-guarded write cross-process:
    `tenshu/probe/c4e3 ["drive-wrote-this-row"]`"* alongside the 9 genesis rows. ⚠ True **concurrent**
    contention on the writer lock (both apps writing at once) is on-device — the sim A/B is sequential, but
    the lock-acquire→write→release path runs and the exclusion mechanism is C4d-proven. The route-1 write
    *substrate* is proven; the in-process `SyncManager` + HLC + crypto assembly (C4e-3b) remains.
  - **C4e-3b — cross-process HLC coordination (the lock-guarded shared-clock read-modify-write). ✅ LANDED.** Closes the specific gap the route-1 viability spike (`CrossProcessHlcCoordinationSpikeTest`)
    left open: it proved the *algorithm* (serialised read-modify-write of shared clock state is fork-free /
    gap-free) but **only with an in-process coroutine `Mutex`** — a single-process unit test can't validate
    cross-process `flock` (its same-process/cross-thread semantics differ), so it deferred the real check to
    "a genuine two-process / on-device" run. This does that run **on the sim**: `AppGroupProbe.tickSharedClock()`
    advances a **shared logical clock** persisted in the App-Group container under a dedicated cross-process
    `FileAdvisoryLock` (`shared-clock.lock`) — acquire → read counter → +1 → write back → release. New
    drive-ios + control-ios `-clockTick` harnesses (store-less; control's `init()` skips the proxy runtime).
    ⚠ **Substrate only:** a pure logical (Lamport) counter — the **coordination half** of route-1's HLC; the
    physical-time + sync-block-crypto pieces are the in-process `SyncManager` (C4e-3c). **No threat-model
    change:** the clock state is a device-local counter — no user data, no key material, no relay exposure
    (House Rule #1 untouched; HR#2 triggers not hit). **Verified A/B (iPhone-17 ad-hoc sim, erased clean):**
    Control `-clockTick` → *"advanced the shared clock 1→5"* (`ticks: 1, 2, 3, 4, 5`); then drive-ios
    `-clockTick` → *"6→10 … continued from Control's last value cross-process"* (`ticks: 6, 7, 8, 9, 10`) —
    one shared, monotonic, gap-free sequence across two real processes (no lost update, no fork; Drive
    *continues* from 6 rather than restarting at 1). ⚠ True **concurrent** contention (both processes ticking
    at the same instant) is on-device; the sim A/B is sequential. The cross-process *coordination* primitive
    is proven; assembling it into the real in-process `SyncManager` (HLC + sync-block crypto + crash
    recovery) is C4e-3c.
  - **C4e-3c-1 — durable realm-key hand-off to Drive (the key-custody decision, implemented). ✅ LANDED.** The first brick of C4e-3c, and the one that needed a **decision** (see "Route-1 write key
    custody" below): for Drive to encrypt + upload sync blocks itself it needs realm key material in its
    process. Control now publishes its **A4 device signing key + the realm `derivedKeys`** (`sharedIdentityKey`
    + `sharedVolumeKey`) into the **shared `app.tenshu.shared` Keychain group**; Drive reads them cross-app.
    New `RealmKeyMaterialBridge` (`core-common/iosMain` — publish/read/clear + a non-secret `fingerprint`,
    over the existing `SecuritySharedKeychainWriter` + a new `SecuritySharedKeychainReader`) +
    `ControlSurface.publishRealmKeysToSharedKeychain()` (resolves the live `SyncManager.currentRealmContext()`
    + `getDeviceSigningKeyPair()` from the real Koin graph) + `AppGroupProbe.readSharedRealmKeySummary()` +
    control-ios `-publishRealmKeys` (genesis→publish, real runtime) + drive-ios `-readRealmKeys`. **Threat-model
    A4 amended** (House Rule #2 — the amendment the C4c A4 note explicitly deferred to here): A4 + realm content
    keys now sit at rest in a both-first-party-apps-readable location; Drive becomes a **key-holding realm
    device for route 1 / first-party same-device only** (route 2 / third-party stays keyless); relay exposure
    unchanged (House Rule #1 — relay still sees one A4 identity + ciphertext). **Verified A/B (iPhone-17 ad-hoc
    sim, erased clean):** Control `-publishRealmKeys` → *"keys published  id=32B/ad73  vol=32B/7fcd
    a4=89B/2083"*; drive-ios `-readRealmKeys` → reads all three cross-app with **fingerprints matching exactly**
    (`sharedIdentityKey 32B/ad73`, `sharedVolumeKey 32B/7fcd`, `deviceSigningKey 89B/2083`). The keys are now in
    Drive's hands; **C4e-3c-2** wires them into an in-process block-encrypt + upload (using the C4e-3b shared
    clock for the HLC), then crash recovery.
  - **C4e-3c-2 — in-process realm crypto with the handed-off key (the write *crypto* half). ✅ LANDED.** Proves Drive can do **real realm crypto in-process** with the key it received in C4e-3c-1,
    relay-free: Drive builds a standalone **real** `DefaultSyncCryptoManager` from the production
    `cryptoBackendsModule()` (libsodium — *not* the `BootstrapSyncCryptoManager` XOR stub), reads the
    `sharedIdentityKey` from the shared Keychain, **encrypts** a block (XChaCha20-Poly1305, `nonce||ct||tag`)
    and drops it in the shared App-Group container; Control then **decrypts** it with its *own* in-memory realm
    key (`currentRealmContext().derivedKeys` via the proxy `SyncCryptoManager`) — so the proof is the realm
    *authority* reading Drive's ciphertext, not a self-round-trip. New `RealmCryptoProbe` (`tenshu-sdk-ios`,
    standalone crypto + shared-container block IO) + `ControlSurface.decryptDriveBlock()` (unlock-gated) +
    drive-ios `-encryptRealmBlock` + control-ios `-decryptDriveBlock` (unlock→decrypt, real runtime). **No
    threat-model change:** the key sharing was already amended (C4e-3c-1); running AEAD with an already-shared
    key adds no boundary, the block is a transient probe in the already-shared container, no relay touch (HR#1).
    **Verified A/B (iPhone-17 ad-hoc sim, erased clean):** Control `-publishRealmKeys`; drive-ios
    `-encryptRealmBlock` → *"Drive encrypted 75B with the shared realm key (real XChaCha20-Poly1305) →
    route1-block.bin"* (75 = 35 plaintext + 40 AEAD overhead); control-ios `-decryptDriveBlock` →
    *"✓ Control decrypted Drive's block with its in-memory realm key: \"hello from Drive in-process @route1\""*.
    The crypto half is proven; **C4e-3c-3** is the relay upload — stamp the block with the C4e-3b shared-clock
    HLC, sign with A4, `POST /sync/changes` to a dev relay, and have Control/another device pull it.

  - **C4e-3c-3a — Drive authenticates to the relay with the handed-off A4 key (the relay-auth half). ✅ LANDED.** The first brick of the C4e-3c-3 relay upload, bounded to **authentication only** (no
    `SyncBlock`/`TContext` serialization) so the JWT path is proven end-to-end without the wire-encoding
    surface. The shared-Keychain hand-off now also carries the realm `ownerId` + `deviceId` (`RealmKeyMaterial`
    gained both fields; `RealmKeyMaterialBridge` writes/reads/clears two more items, `owner-id` + `device-id`,
    as UTF-8). Drive reads them + the A4 key, mints a **real device-signed JWT** with the production
    `jwtTokenProvider` + the same libsodium `signEd25519` (`RealmCryptoProbe.crypto` exposed `internal`), and
    makes one authenticated `GET /sync/tombstones?since=0` over a one-shot `HttpClient(Darwin)` (the Darwin
    engine added as an explicit `implementation` dep — it's `implementation`-only in `:control-shared-proxy`).
    A **200** proves the relay verified an EdDSA-signed JWT bound to the realm device; a wrong/unsigned token
    returns **401**. New `RelayAuthProbe` (`tenshu-sdk-ios`) + control-ios `-registerAndPublish` (genesis →
    `devBindFreeAccount` → settle 8s for the entitlement→gate→scheduler-tick→`POST /sync/realm` cascade →
    publish keys+ids, mirroring `EpcHostRegisteredView`) + drive-ios `-relayAuth`. **No threat-model change:**
    auth uses the already-shared A4 key (amended at C4e-3c-1); the `ownerId`/`deviceId` added to the Keychain
    are public realm identifiers, not secrets. **Verified A/B (iPhone-17 ad-hoc sim + dev `:sync-relay-server`,
    erased clean):** control-ios `-registerAndPublish` → *"Registered + keys published. … id=32B/3b37
    vol=32B/658c a4=89B/22ca"*; drive-ios `-relayAuth` → *"✓ relay accepted the A4-signed JWT (HTTP 200)
    owner=8888bbe92e09…  device=12cc8293-9c0…  Drive authenticated to the relay as the realm device,
    in-process."* The remaining **C4e-3c-3b** is the actual block push — stamp the C4e-3b shared-clock HLC,
    serialise + sign the `SyncBlock`, `POST /sync/changes`, and have Control/another device pull + decrypt it.

  - **C4e-3c-3b — Drive pushes a real `SyncBlock` to the relay, pulls it back, and decrypts it (the route-1
    write *finale*). ✅ LANDED.** The full client sync round-trip, **in Drive's own process**: Drive
    builds a genuine wire-valid `SyncBlock` (`SyncBlock.create` over a `TContext` assembled from
    `coreCommonModule()` + the `syncServiceCommonModule` schema config — the same `coreSchemaConfigs` seam
    production uses in `EpcDriveClient`), AEAD-seals a payload with the handed-off realm `sharedIdentityKey`,
    HLC-stamps it with the **C4e-3b cross-process shared clock** (`physicalTime` = wall clock, `logicalCounter`
    = `AppGroupProbe.tickSharedClock()`, `deviceId` = Control's — Drive rides Control's identity), then drives
    the **production `RestSyncRelay`** directly: `pushBlock` → `POST /sync/push` (device-JWT auth), then
    `getChanges(since=0, tip)` → `POST /sync/changes` with a real **signed HLC tip** (`SignedHlcTip` signed by
    the A4 key over `hlcTipCanonicalMessage`), finds the pushed block by `blockId`, and decrypts it back to the
    original plaintext. A self round-trip is a complete wire-validity proof: the block decrypts under the
    *realm* key Control also holds, so a block Drive reads here is one Control reads too (the cross-party
    decrypt was already shown in C4e-3c-2). New `RelayBlockProbe` (`tenshu-sdk-ios`) + drive-ios
    `-relayBlockRoundTrip`. **No threat-model change:** same already-shared A4 + realm key (amended at
    C4e-3c-1); the relay still sees only the device-JWT + opaque ciphertext (HR#1). **Verified (iPhone-17
    ad-hoc sim + dev `:sync-relay-server`, erased clean):** Control `-registerAndPublish` (id=32B/8e63); Drive
    `-relayBlockRoundTrip` → *"✓ Drive pushed a real SyncBlock to the relay, pulled it back, and decrypted it.
    blockId=f3eb89a3-a7d…  hlc=(1782651871169,1)  owner=d82f031581…  recovered: \"hello from Drive — a real
    SyncBlock over the relay @route1\""*. The route-1 write plane is now proven end-to-end in-process. Thin
    follow-ups: **3b-2** the *authority*-side read (a Control harness pulls Drive's block off the relay — its
    real proxy already does this in production); **3c** apply the pulled block as a real entity mutation into
    the replica (`DataBlockPayload`-wrapped, not a raw marker); then **crash recovery**.

  - **C4e-3c-3b-2 — the realm *authority* (Control) pulls Drive's block off the relay and decrypts it. ✅
    LANDED.** Closes the route-1 write→relay→authority-read loop the durable-key-custody decision was
    made for (*the awake app uploads; the authority reads*). A fresh Control launch unlocks the
    (sealed-on-relaunch) realm, then `ControlSurface.pullAndDecryptDriveBlock(...)` builds the **production
    `perRealmDeviceRelay`** over its real-runtime `TContext` + `SyncCryptoManager` + `currentRealmContext()`,
    constructs a real signed `SignedHlcTip`, `getChanges(since=0)` → `POST /sync/changes`, scans the returned
    blocks, and decrypts each with Control's *own* in-memory `derivedKeys.sharedIdentityKey` — reporting the
    one that recovers Drive's marker. Unlike C4e-3c-3b (Drive self-round-trips), here a **separate app/process
    — the realm authority — reads the block off the relay**, not the writer. New harness control-ios
    `-pullDriveBlock` (unlock → pull → decrypt). **No threat-model change:** reuses the already-shared key
    (C4e-3c-1) over the production relay client; relay sees only the device-JWT + opaque ciphertext (HR#1).
    **Verified (iPhone-17 ad-hoc sim + dev relay, erased clean):** Control `-registerAndPublish`; Drive
    `-relayBlockRoundTrip` pushes block `e2796ca7-7f9`; Control `-pullDriveBlock` → *"✓ Control pulled Drive's
    block off the relay and decrypted it with its own realm key. blockId=e2796ca7-7f9…  (of 3 block(s) on the
    realm)  recovered: \"hello from Drive — a real SyncBlock over the relay @route1\""* — the **same** block id
    Drive pushed. ⚠ `simctl launch` on an already-running app does **not** apply new launch args (reuses the
    live instance) — `simctl terminate` first. Remaining route-1 tail: **3c** apply the pulled block as a real
    entity mutation (`DataBlockPayload`-wrapped) into the replica; then **crash recovery**.

  - **C4e-3c-3c — a real entity mutation (a `Folder`) is applied into Control's database via the real
    `SyncProcessor`. ✅ LANDED.** The step beyond C4e-3c-3b-2's decrypt-a-string: the block now
    carries a `DataBlockPayload` wrapping a genuine `Folder` (`Syncable(Eager)` domain entity) — the exact
    shape `SyncObserver.generateSyncBlock` produces — and the receiver runs it through its **real ingest
    path**, landing the entity in its database. *Producer:* Drive's `RelayFolderProbe` assembles a `TContext`
    with `fileServiceCommonModule` on top of the sync/core schema, builds `Folder.create(ctx, path, [])`,
    wraps it `DataBlockPayload.create(propertyId = null, value = folder)`, seals + HLC-stamps + pushes (the
    C4e-3c-3b push path). *Consumer:* `ControlSurface.pullApplyAndReadFolder(...)` pulls via the production
    `perRealmDeviceRelay`, then feeds every block to the **live `SyncProcessor`** (resolved from the koin
    `SyncProcessorHolder.processor`) — the real `processSyncBlock` → decrypt → unwrap `DataBlockPayload` →
    `resolveConflictAndSave` → `saveData(entity, SYNC_INGEST)` — and confirms the `Folder` landed by reading
    Control's shared object store back (`AppGroupProbe.readReplicaEntries()`, the C4e-2 reader). New harnesses
    drive-ios `-relayFolderPush` + control-ios `-pullApplyFolder` (unlock → pull → apply → read). **No
    threat-model change:** reuses the already-shared realm key (C4e-3c-1) over the production relay + ingest;
    relay sees only the device-JWT + opaque ciphertext (HR#1). **Verified (iPhone-17 ad-hoc sim + dev relay,
    erased clean):** Control `-registerAndPublish`; Drive `-relayFolderPush` → *"Drive pushed a real entity
    mutation (a Folder) … path=\"/route1-c4e3c3c-folder\""* (block `7d2217f7-ad4`); Control `-pullApplyFolder`
    → *"✓ Control ingested Drive's block through the real SyncProcessor and the Folder landed in its replica.
    applied 3 block(s); 20 row(s) in the store. matched row: tenshu/file/folder [\"/route1-c4e3c3c-folder\",
    …]"*. **The route-1 plane — write → relay → authority ingest into the DB — is now complete end-to-end on
    the sim.** Remaining: **crash recovery** (the on-device-heavy durability tail).

  - **C4e-3c-3d — route-1 single-writer-lock crash recovery (no deadlock after a holder crash). ✅ LANDED.** The route-1 design serialises every replica write behind one cross-process `FileAdvisoryLock`
    (`replica.writer.lock`, C4e-3a). The crash hazard: if the process holding it dies *without releasing it*,
    does the lock leak and permanently deadlock all writers? It does not — a `flock` is bound to the open file
    description and the OS drops it when the process exits, cleanly or not. Proven on the sim by simulating the
    crash with `simctl terminate`: a new `AppGroupProbe.acquireWriterLock()` (acquire + **hold**
    `replica.writer.lock` for the process lifetime) + control-ios `-holdWriterLock`; the existing C4e-3a
    `-replicaWrite` (`tryLock` → write-or-bail) and `-replicaReadProbe` supply the contention + read-back.
    **No threat-model change** (a local advisory lock; no data/keys/relay touched, HR#1 intact). **Verified
    (iPhone-17 ad-hoc sim, erased clean):** Control `-holdWriterLock` → *"Holding the route-1 writer lock"*;
    Drive `-replicaWrite` (holder alive) → *"✗ Write failed — … the single-writer lock is held by Control"*
    (excluded); **`simctl terminate` Control (crash while holding)**; Drive `-replicaWrite` again → *"✓ Drive
    wrote a probe row … Replica now holds 1 rows"* (the OS released the dead holder's flock → the surviving
    writer acquired it and completed a durable write — no deadlock); Control `-replicaReadProbe` → reads it
    back cross-process: *"tenshu/probe/c4e3 [\"drive-wrote-this-row\"]"*. **The sim-verifiable route-1 plane
    is now complete: write → relay → authority ingest, plus writer-crash recovery.** The genuinely
    on-device-only durability tail (torn-write / power-loss atomicity, a durable pending-push queue so a crash
    *between* local-commit and relay-push re-drains on relaunch) is deferred to a physical device — the sim's
    `terminate` models a clean process death, not power loss.

##### Route-1 write key custody (decision, 2026-06-28)

For a Drive-authored write to reach the relay/other devices, *some* process must encrypt it into a sync
block and upload it. Four options were weighed (maintainer call):

| Option | Local write | Background sync timeliness | Keys in Drive? |
|--------|-------------|----------------------------|----------------|
| Writes via Control (EPC/IPC) | blocks on Control waking ❌ | when Control runs | no |
| Control egresses Drive's local plaintext | immediate ✅ | when Control runs (Drive can't wake it) | no |
| Ephemeral EPC key hand-off | immediate ✅ | instant while Drive foreground | in-memory only |
| **Durable shared-Keychain (chosen)** | immediate ✅ | **instant while Drive foreground** | **at rest, shared** |

**Decision: durable shared-Keychain.** Rationale: the app the user is actively touching (Drive) is the one
awake, so it should do the encrypt+upload itself — sync is then instant with **zero dependency on the
(suspendable) Control's wake state** (the §11.2/§11.5 wake-window simply doesn't apply to anything the user
is doing). This is what the route-1 design always intended ("Drive runs realm crypto in-process riding
Control's device identity"). Cost: Drive becomes a key-holding realm device (first-party same-device only;
route 2 stays keyless) and A4 + `derivedKeys` are now at rest in a both-apps-readable Keychain group — a real
but modest, first-party-scoped threat-model delta (amended on A4). The durable variant was chosen over
ephemeral because it lets Drive sync **even if the user never opens Control this session**; over the
Control-egress variants because those keep the wake-window cost background propagation was meant to avoid.
None of the options escape "iOS must run *a* proxy sometime" for background sync; durable maximises the
common foreground-Drive case. C4e-3b's cross-process HLC coordination is the prerequisite this unlocks (two
co-equal writers now both stamp HLCs).

**Status: C4a + C4b + C4c ✅ landed; C4d ✅ sim-confirmed; C4e-1 + C4e-2 + C4e-3a + C4e-3b + C4e-3c-1 + C4e-3c-2 + C4e-3c-3a + C4e-3c-3b + C4e-3c-3b-2 + C4e-3c-3c + C4e-3c-3d ✅ landed** — the
three fork-independent slices are in (proxy sync core compiles on iOS; durable on-device store; persisted
device key), the route-1 lock primitive + viability spike + the **shared-container probe** have landed (the
App-Group container is shared across two ad-hoc-signed sim apps and the cross-process `flock` excludes —
both **without a paid membership**), **the realm replica now lives in that shared container** (C4e-1),
**Drive decodes that replica into structured realm rows in-process** (C4e-2, the route-1 *read*),
**Drive durably writes into it under the single-writer lock, the owner reading the row back cross-process**
(C4e-3a, the route-1 *write substrate*), **two real processes advance one shared clock fork-free under
the lock** (C4e-3b, the cross-process *coordination* primitive), **Drive reads the realm `derivedKeys` +
A4 from the shared Keychain cross-app** (C4e-3c-1, the durable key hand-off — the key-custody decision
implemented), and **Drive encrypts a block in-process with that key, which the realm authority decrypts**
(C4e-3c-2, the write *crypto* half), **Drive authenticates to the relay with the handed-off A4 key,
HTTP 200** (C4e-3c-3a, the relay-*auth* half), and **Drive pushes a real `SyncBlock` to the relay, pulls it
back, and decrypts it** (C4e-3c-3b, the write *finale* — the full client sync round-trip in-process), and
**the realm authority (Control, a separate app) pulls Drive's block off the relay and decrypts it**
(C4e-3c-3b-2, the authority-side read), and **a real entity mutation (a `Folder`) is applied into Control's
database via the real `SyncProcessor`** (C4e-3c-3c), and **the single-writer lock recovers from a holder
crash without deadlocking** (C4e-3c-3d) — all proven on the sim. **The route-1 plane — write → relay →
authority ingest into the DB, plus writer-crash recovery — is now complete end-to-end on the sim.** What
remains is the genuinely on-device-only durability tail: torn-write / power-loss atomicity and a durable
pending-push queue (re-drain on relaunch) — both need a physical device, since the sim's `terminate` models
a clean process death, not power loss.

#### Resolving the route fork — what the free spike settled, and what signing unlocks

The route-1 viability spike (`CrossProcessHlcCoordinationSpikeTest`, run free on the simulator)
**de-risks the fork without spending anything**, and sharpens exactly what remains:

- ✅ **The coordination algorithm holds.** Serialising a read-modify-write of shared clock state
  through one lock yields a fork-free, gap-free HLC sequence under heavy concurrency; without
  serialisation the same writers fork. The shared-file I/O the replica uses is exercised end-to-end.
- ✅ **The lock primitive's API works** (`FileAdvisoryLock` over `flock` detects conflicts).
- ⚠️ **A single-process unit test cannot validate the *cross-process* lock** — discovered by running
  the spike: `flock`'s same-process/cross-thread semantics differ from its cross-process semantics (a
  blocking `flock(LOCK_EX)` from two threads of one process does *not* serialise them), so threads are
  an invalid stand-in for processes. The algorithm proof therefore uses a coroutine `Mutex` as the
  in-process serialiser; the real cross-process exclusion needs a **genuine two-process / on-device**
  check. That last confirmation is a narrow, well-understood POSIX question — not a novel risk.
- ✅ **Cross-process exclusion + coordination now sim-confirmed (ad-hoc, no paid tier)** — the
  "genuine two-process check" the spike deferred is met on the simulator by two ad-hoc-signed apps
  sharing the App-Group container: C4d/#749 showed `flock` *excludes* across processes (Control holds,
  Drive's `tryLock` fails), and **C4e-3b/#757 showed the lock-guarded read-modify-write *coordinates*
  across processes** — Control ticks a shared clock 1→5, then Drive *continues* 6→10 (one shared,
  monotonic, gap-free sequence; no lost update, no fork). What remains on-device is only true
  *concurrent* contention (both processes ticking at the same instant); the sim A/B is sequential.

**The signing ladder (readiness, 2026-06-26).** The macOS desktop build is the mature precedent to
mirror — Team ID `FAYT82GWD7` (a *free* personal team), wired through `-Ptenshu.teamId` / `$TENSHU_TEAM_ID`
/ `local.properties`, with `@TEAM_ID@`-templated `.entitlements` and Gradle `codesign`/`xcodebuild`
sign tasks (`signControlAppForAppGroup`, `signDriveAppForFileProvider`). Three tiers, only one of
which costs money (`docs/how-to/macos-signing.md` confirms the hard gate):

| Tier | Unlocks | Account |
|------|---------|---------|
| **Ad-hoc, unsigned-team (`-`)** | **Simulator only** — but covers far more than first assumed: per-app + **bare shared** Keychain groups (C3), **and bare App Groups** — the route-1 shared container + cross-process `FileAdvisoryLock` exclusion are sim-confirmed here (C4d), no Apple account at all | Free, no account |
| **Free, signed (FAYT82GWD7)** | **On-device** smoke of C1/C2/C3 (7-day profiles); the on-device repeat of the C3 Keychain + C4d App-Group two-process checks (sim-confirmed above) | Free |
| **Paid ($99/yr)** | **iOS App Groups *on a real device*** (Team-prefixed group + both apps under one team) — the on-device shared-container transport for the route-1 replica; App Attest registration. ⚠ *Not* needed for sim route-1 work (the ad-hoc row covers that) | Apple Developer Program |
| **Physical device** | True on-device runtime; App Attest (can never run on the simulator) | + a device |

Mechanical setup (mirror the macOS machinery): real bundle IDs (drop the `.smoke` suffix →
`app.tenshu.drive.ios` / `app.tenshu.control.ios`), iOS `.entitlements` (`keychain-access-groups`;
`com.apple.security.application-groups` at the paid tier), `project.yml` signing fields
(`DEVELOPMENT_TEAM`, `CODE_SIGN_STYLE = Automatic`, entitlements path) reusing the existing
`-Ptenshu.teamId` resolution. **One naming decision:** the docs are inconsistent —
`docs/protocol/ios-ipc.md` proposes `group.org.tenshu` while macOS uses `app.tenshu.shared`; iOS groups
must start with `group.`, so it'd be e.g. `group.app.tenshu.shared` (the macOS `<TeamID>.app.tenshu.shared`
form is macOS-specific and won't be the literal same identifier).

**Recommended path:** the free tier closes C3 end-to-end and confirms the cross-process lock; pay the
$99 only once a free two-process lock check + C3 round-trip confirm route-1 is the keeper. Concrete
free-tier on-device setup steps (real bundle IDs, `keychain-access-groups` entitlements, `project.yml`
signing fields, the §4 C3 handshake check + §5 two-process `FileAdvisoryLock` harness) are written up
in [`../how-to/ios-signing.md`](../how-to/ios-signing.md).

### C4-RT — Real Control proxy runtime on iOS (the `:control-shared-proxy` extraction)

**Status: planned (recon done 2026-06-26).** This is the **prerequisite for C6 device-pairing** and the
fork-independent foundation that C4e's route-1 collapse later assembles on. It is the slice that makes
Control *real* on iOS — replacing the genesis-over-fakes path with a networked proxy that mints real
keys and registers with the relay.

**Why it gates C6.** iOS Control runs entirely over fakes today: `ControlSurface` installs
`FakeControlSyncRepository` (success-only) + `Mock*` repos, so genesis mints no real realm keys and
never contacts the relay. Control↔Control device-pairing ([`../sync/pairing-flow.md`](../sync/pairing-flow.md))
is by nature a real-relay / real-key / real-sync handshake — rendezvous → key exchange → realm-state
sync — in *either* direction (iPhone-as-host or iPhone-as-joiner). It cannot run over success-only
fakes. So a live Control proxy on iOS must land first.

**Why it's tractable.** Fork-**independent** — the route-1/route-2 fork is about Drive↔Control *on the
same device*; this is Control↔Control *across* devices, ordinary cross-device sync every platform
already does. **No paid tier** — Control's own relay sync uses the network + the per-app Keychain, both
free; it runs on the **simulator against a dev relay** (`./gradlew :sync-relay-server:run`). The hardest
networking primitive — the **Ktor Darwin engine** — is already proven on iOS by `RendezvousSmoke` /
`EpcRelayPairingSmoke` (they make real Darwin relay requests today). And C4a/C4b/C4c already iOS-ized
the pieces the runtime plugs in (sync core, durable `FileObjectStore`, persisted A4).

**The blocker = module shape, not missing code.** The proxy runtime lives in `:control-shared`, which has
**no `iosMain`**: its `commonMain` pulls Compose (`compose.runtime/ui/material3`, `:ui-core`,
`koinComposeViewmodel`). So the Compose-free proxy-runtime half must be **extracted** into an iOS-capable
module. Recon confirms the files to move carry **zero Compose imports** and **do not import `:ui-core`**,
and every service-`-proxy` dependency (`sync`/`identity`/`file`/`notification`/`preference`/`contact`/
`storage`/`ingest`/`logging`) plus `:core-proxy` **already has iOS targets**.

**The split — new module `:control-shared-proxy`** (KMP targets `android`, `jvm("desktop")`, `iosArm64`,
`iosSimulatorArm64` — no `iosX64`, matching the umbrella's arch set). Layering becomes:

```
:control-shared-logic   (Compose-free: contracts, VMs, Tenshu*/Mock* impls)   — iOS ✓ today
:control-shared-proxy   (NEW; Compose-free: proxy runtime)  → depends on -logic — iOS target
:control-shared         (Compose UI)  → depends on both                         — android/desktop only
```

Move into `:control-shared-proxy` (all verified Compose-free):
- `di/ProxyModule.kt` — the Koin proxy graph (`TenshuClient`/`ProxyGateway`/`ClientRegistry`/`Database`/
  `SyncRepository`→`TenshuSyncRepository`/`HostPairingMonitor`/entitlement+storage providers/`AppRegistry`/
  `RealmDataClient`/`EpcCallbackRouter`/…).
- `di/PlatformProxyModule.kt` — the **android + desktop actuals move with it**; the **iosMain actual is
  new** (table below).
- `epc/` runtime: `DefaultEpcPairingHost`, `EpcPairingHost`, `EpcRendezvousServer`, `EpcClientConnection`,
  `EpcCallbackRouter`, `EpcPairingInvite` (pure logic, no Compose).
- `data/` wiring: `EntitlementMintClient`, `EntitlementStorageInitialization`, `StorageSyncGate`.

Stays in `:control-shared`: all of `ui/`, `App.kt`, navigation, the `@Composable expect
rememberBiometricPromptHost()` half of `BiometricUnlockDelegate.kt`, and the EPC pairing **screen/VM**
(`EpcPairingScreen`/`EpcPairingViewModel`).

**iosMain `PlatformProxyModule` actuals** — almost all already exist; this is wiring, not new subsystems:

| Binding | iOS source |
|---|---|
| `HttpClient` engine | Ktor **Darwin** (`libs.ktorClientDarwin`; proven by the rendezvous smokes) |
| `SecureStorageDelegate` | `IosSecureStorageDelegate` (C1, `core-common/iosMain`) |
| `BiometricUnlockDelegate` | inert iOS stub (the gate is intrinsic to the C1 Keychain ACL) |
| `SyncManager` | `SyncManagerImpl` (C4a iOS-capable; C4c persists A4) |
| `SyncRelay` / `ContentBlobStore` / `ShareRelayClient` / `InviteRelayClient` | `RestSyncRelay` / `RestContentBlobStore` / `RestShareRelayClient` (sync-service-proxy commonMain) + `RestInviteRelayClient` (contact-service-proxy commonMain) — all iOS-reachable ✓ |
| `ObjectStore` | `FileObjectStore` (C4b, `core-common/iosMain`) |
| `FileDatabase` / `PreviewCache` | `InMemoryFileDatabase` / `InMemoryPreviewCache` to start (desktop parity) |
| `ClientConnectionFactory` | `LocalClientConnection` (self) + `EpcClientConnection` (paired). **No inter-app connection** — that's the route-1/2 Drive↔Control fork, out of scope here |
| `OperationImplementationFactory` | compose `syncProxyImplementationFactory` + the **commonMain** `fileServiceProxyImplementationFactory` (iOS uses the common one — *not* the android/jvm native-FD variants) + identity/notification/preference/contact/storage/logging factories |
| `platformProxyInitialization` | the same `initXxxService` + gate wiring; **no WorkManager/SyncWorker** (background sync is C6/O4) — foreground sync drain via a coroutine/timer |

**Real genesis falls out for free.** `NewUserViewModel.onUnlockSetupSubmit()` →
`SyncRepository.createRealm()` → `client.syncService.createRealm()` → the `CreateRealm` op in
sync-service-proxy (mints master identity from the BIP-39 entropy, derives the A4 device key + realm
keys, `POST /sync/realm` with device-key JWT, seeds the authorized `Device`). Once `ProxyModule` + the
iosMain `PlatformProxyModule` run, `ControlSurface` swaps `FakeControlSyncRepository` for the real
`TenshuSyncRepository(get<TenshuClient>())` and genesis is real end-to-end.

**Sub-slices (each its own worktree → PR → squash-merge):**
- **RT-1 — pure refactor (the safety net):** create `:control-shared-proxy`; move `ProxyModule` + EPC +
  data wiring + the android/desktop `PlatformProxyModule`; rewire `:control-shared` and both apps to
  depend on it. **Green on android + desktop with zero behavior change** — all existing control-shared /
  control-shared-logic tests stay green. No iOS yet.
  - **✅ LANDED (RT-1 as-built).** New Compose-free module `:control-shared-proxy` (android +
    `jvm("desktop")` only — iOS targets deferred to RT-2). Layering is now
    `:control-shared-logic → :control-shared-proxy → :control-shared`. Moved (packages unchanged,
    so zero import churn): commonMain `di/ProxyModule` + `epc/{EpcCallbackRouter, EpcPairingHost,
    EpcPairingInvite, EpcRendezvousServer, DefaultEpcPairingHost, EpcClientConnection}` +
    `data/{StorageSyncGate, EntitlementStorageInitialization, EntitlementMintClient}`; androidMain
    `di/PlatformProxyModule` + `data/AndroidNetworkStateProvider` +
    `notifications/AndroidPendingRequestNotifier`; desktopMain `di/PlatformProxyModule` +
    `data/{DesktopNetworkStateProvider, DesktopSecureStorageDelegate, DesktopMasterKeyStore}`. The
    EPC pairing **UiState/ViewModel**, the `@Composable rememberBiometricPromptHost()` half, the
    `dataModule`/`viewModelsModule`, and all `ui/` stayed in `:control-shared`. **Two snags worth
    recording:** (1) the notifier imported `app.tenshu.control.shared.R` for 4 central-notification
    strings — moved `androidMain/res/values/strings.xml` with it and re-pointed the import at the new
    module's `app.tenshu.control.proxy.R` (the new module enables `androidResources`; it also needed
    an explicit `libs.core.ktx` since `NotificationCompat` had been reaching the notifier transitively
    via the UI deps that stayed behind). (2) `StorageSyncGateTest` exercises `internal`
    `storageAllowsSync` / `personalRealmSyncAllowed`, which are module-scoped — moved the test into
    `:control-shared-proxy/commonTest` alongside its subject. All other moved-class tests (EPC,
    EntitlementMint/Storage, DesktopMasterKeyStore) use public APIs and stayed in
    `:control-shared/desktopTest`, resolving the moved classes through the new `api` dependency.
    `:control-shared` `api`-depends on `:control-shared-proxy` so the apps still reach
    `proxyModule` / `platformProxyModule`. BAR met: `:control-shared{,-logic,-proxy}:desktopTest`
    green + `:control` / `:control-desktop` compile + android `compileAndroidMain` green. No
    threat-model touch (HR#2): no asset / relay / protocol change — pure module reshape.
- **RT-2 — iOS targets + actuals:** add `iosArm64`/`iosSimulatorArm64` to `:control-shared-proxy`; write
  the iosMain `PlatformProxyModule` (table above). Bar = `compileKotlinIosSimulatorArm64` + `IosArm64`
  green (root build skips iOS *test* compile for -proxy modules — C4a note).
  - **✅ LANDED (RT-2 as-built).** Added `iosArm64()` + `iosSimulatorArm64()` (no `iosX64` — `:core-paging`
    has no x64 klib) + an `iosMain` source set pulling the seven remaining service `-proxy` modules
    (`file`/`notification`/`ingest`/`sync`/`preference`/`contact`/`logging`; `identity`+`storage` were
    already in commonMain) + `libs.ktorClientDarwin` (`epc-rendezvous-client` rides transitively via
    `:sync-service-proxy`'s `EpcRendezvousAcceptor`). The iosMain `PlatformProxyModule` is a structural
    twin of the desktop actual; per the mapping table it swaps in the **Ktor Darwin** engine,
    `IosSecureStorageDelegate(aead = get(), …)` (the `AeadBackend` comes from `coreCommonModule`'s
    `cryptoBackendsModule()`), an inert `IosBiometricUnlockDelegate` + `IosNetworkStateProvider`,
    `InMemoryFileDatabase`/`InMemoryPreviewCache`, the durable `FileObjectStore` via
    `openFileObjectStore(applicationSupportDirectory("objects"))` (C4b), `LocalClientConnection` +
    `EpcClientConnection` (the no-inter-app branch `error()`s), the **commonMain**
    `fileServiceProxyImplementationFactory` only (no jvm/android FD variant), the DevSeed entitlement
    minter, and **no WorkManager** (the resident in-process scheduler from `initSyncService` drives
    foreground sync). **Two K/N gotchas:** (1) `ReentrantLockDatabaseLock` is JVM-only → bind
    `NoOpDatabaseLock` (the sanctioned iOS choice per the `DatabaseLock` KDoc, until a real K/N
    reentrant lock lands); (2) Kotlin/Native has no `System.currentTimeMillis()`, and the
    `NoDirectClockAccess` audit forbids `Clock.System.now()` outside `SystemClock` → time is sourced
    through a private `SystemClock()` (`nowMillis()` / `nowEpochSeconds()`). RT-2 keeps the runtime
    *defined* but unwired — `ControlSurface` still installs fakes; RT-3 flips it to real. BAR met:
    `:control-shared-proxy:compileKotlinIos{SimulatorArm64,Arm64}` green (from scratch); android +
    desktop + `:control-shared:desktopTest` + both apps unchanged. No threat-model touch.
- **RT-3 — flip the umbrella to real:** `ControlSurface` constructs the real ProxyModule-backed runtime
  (mirror **production** wiring — `CoreCommonModule`/`ProxyModule`, not `TestProxy`; the "mirror
  production wiring" rule). Real genesis from the **simulator against a dev relay**; smoke = create realm
  → relay registration → process-death durability (FileObjectStore + persisted A4).
  - **✅ LANDED (RT-3 as-built).** `:tenshu-sdk-ios` iosMain now `api`-depends on `:control-shared-proxy`
    (NOT in the `export {}` block — Swift drives the runtime through a `ControlSurface` Kotlin method,
    never referencing the proxy graph types, so the ObjC/Swift API surface is unchanged).
    `ControlSurface.installRealRuntime()` assembles `koinApplication { modules(proxyModule,
    platformProxyModule) }` (proxyModule `includes(coreCommonModule())`) and swaps every `installed*`
    seam to the real impls resolved off that graph — `TenshuSyncRepository` / `HostPairingMonitor` /
    `TenshuRealmDataClient` / `TenshuAppRegistry` over a real `TenshuClient`, plus the inert iOS
    `BiometricUnlockDelegate`; `EventRepository` (a `dataModule` binding absent on iOS) is built directly
    as `TenshuEventRepository(client, NotificationMapper())`. The resolved Koin is retained in a field
    (its singletons' lazy `get()`s capture the scope). `ControlIosApp.init()` calls it in the normal
    path; the DEBUG screenshot deep-links (`-pairingStep`/`-settingsHome`/`-onboardingRoute`) stay over
    fakes (no relay). **The SwiftUI screens are untouched** — they bind the same seams; only the data
    source flips. Genesis is **local-first** (ADR 0019): `createRealm` mints a real master key + A4
    device key and seeds the realm into the durable `FileObjectStore` (C4b) with **no relay** — relay
    registration is deferred until a storage subscription opens the gate, so the genesis smoke needs no
    dev relay running. **Verified:** `:tenshu-sdk-ios` iOS compile (from scratch) + debug XCFramework +
    `xcodebuild … BUILD SUCCEEDED`; the app **boots on the iPhone-16 simulator with the real runtime
    installed** (the full proxy Koin graph assembles on-device without crashing — the first time the
    real Control proxy runs on iOS) and renders the genesis welcome. ⚠ **Manual/pending:** tap-driven
    genesis *completion* (no idb/simctl tap tooling) → relay registration → process-death durability —
    the parts needing UI interaction + a live relay; coordinate with the RT-4 SyncDashboard read.
    Rebased onto Track B's B-3 (host "Show" tab) — RT-3's `ControlSurface.kt` additions merged
    cleanly with Track B's (disjoint regions); the pairing wizard now rides the **real**
    `HostPairingMonitor`. No threat-model touch.
- **RT-4 (optional pre-C6):** a SyncDashboard / devices read over the real proxy to confirm sync
  round-trips before layering pairing.
  - **✅ LANDED (RT-4 as-built).** The first **read** surface over the real proxy. `ControlSurface`
    gains `syncDashboardViewModel()` (= `SyncDashboardViewModel(installedSyncRepository,
    hostPairingMonitor())`) + a `SyncDeviceRow` flat bridge (`syncDevices(state)`) that reads the
    `org.tenshu.identity.Device` composites **Kotlin-side** (Swift never touches the SKIE-bridged
    composite — same discipline as `genesisStepKey`). A new SwiftUI `SyncDashboardView` binds the VM,
    shows the sync-enabled status + device rows (This-device marker, pending/authorized), and a refresh
    button; its model maps the Kotlin rows into a Swift-native `Identifiable` struct (avoids referencing
    the SKIE nested-class name). Reachable from Settings (`This realm's devices` `NavigationLink`) + a
    DEBUG `-syncDashboard` deep-link that renders it **over the real proxy** (init does *not* skip
    `installRealRuntime` for it). On `refresh()` the VM issues real `refreshDevices()` /
    `getSyncStatus()` ops through the in-process proxy — so this confirms a **live proxy read
    round-trip** on-device. **Verified:** umbrella iOS compile + debug XCFramework +
    `xcodebuild BUILD SUCCEEDED`; the app boots on the iPhone-16 simulator and renders the Devices
    screen over the real proxy — *Status "Local only"* + *"No devices yet."* pre-genesis (the real
    `refreshDevices()`/`getSyncStatus()` ran in-process without crashing). ⚠ A **populated** device list
    needs a completed genesis (tap-driven, manual). One Swift snag: `@Published` over a `private` row
    type needs `fileprivate`. Rebased onto Track B's B-4 (native QR) — disjoint from the
    SyncDashboard work. No threat-model touch. **C4-RT is now fully complete (RT-1..RT-4).**
- **RT-5 — durable realm-device probe at launch. ✅ LANDED.** Closes the "process-death
  durability" caveat the prior RT slices carried: `ControlRootView` decided onboarding-vs-MainTabs
  from an **in-memory `@State` flag**, so a genesised iPhone re-showed onboarding on every cold start.
  New `ControlSurface.isRealmDevice()` reads the real proxy's **`getUnlockStatus().isSetUp`** — true
  once genesis/recovery has written an unlock credential (`UNLOCK_WRAPPER` / `BIOMETRIC_WRAPPER`) into
  secure storage; durable across a relaunch and independent of the realm's sealed/unlocked state
  (a still-sealed realm is *still* a realm device). Deliberately **not** `getSyncStatus().ownerId` —
  that's null while sealed (keys not yet derived), so it would re-onboard a set-up device every cold
  start. `ControlRootView` now probes at launch + on foreground (the iOS twin of Drive's `RootView`
  Keychain probe) via a `checking → onboarding | ready` phase; a failed/cancelled probe falls back to
  onboarding (never wrongly "ready"). A DEBUG `-genesisAutoComplete` harness drives a real genesis to
  completion over the proxy with no tap tooling, so the probe can be exercised across a relaunch on a
  signed build. UI + a one-line read over the existing `getUnlockStatus` op — **no protocol/wire/
  threat-model change**. **Verified (iPhone-16 sim):** `xcodebuild BUILD SUCCEEDED`; fresh install →
  onboarding (probe false); relaunch with the keychain unavailable → **fail-safe onboarding** (no
  crash, never wrongly ready). ⚠ **The genesised "ready" branch is signing-gated:** on the unsigned
  sim a real genesis fails at `setUpUnlock` with **`errSecMissingEntitlement (-34018)`** ("Client has
  neither application-identifier nor keychain-access-groups entitlements") — the build has no
  entitlements, so every Keychain op fails; both genesis's credential write and the probe's read need
  a working per-app Keychain. This is the **same free-tier signing gate** as C1/C2/C3 (see
  `docs/how-to/ios-signing.md`); the harness localizes it precisely and will complete the
  genesis → relaunch → MainTabs round-trip once the sim build is signed. **A working sim Keychain
  (the free-tier entitlement) now unblocks the most-repeated on-device caveat across the whole iOS
  track — the recommended next step.**
- **RT-6 — ad-hoc-sign the simulator build for a working Keychain. ✅ LANDED.** Acts on
  RT-5's finding: the fully-unsigned sim build had **no `application-identifier` entitlement**, so
  *every* Keychain op failed with `errSecMissingEntitlement (-34018)` — blocking genesis's
  `setUpUnlock` and the RT-5 probe. Fix is **config-only**, no Apple account: `control-ios/project.yml`
  now ad-hoc-signs the sim build (`CODE_SIGNING_ALLOWED=YES`, `CODE_SIGN_STYLE=Manual`,
  `CODE_SIGN_IDENTITY="-"`) with a new `control-ios/Control.entitlements` carrying a per-app
  `application-identifier` (+ matching `keychain-access-groups`). The simulator honours ad-hoc
  entitlements with no profile (that's a real-device-only requirement); the `SecurityFrameworkKeychain`
  backing `IosSecureStorageDelegate` sets no `kSecAttrAccessGroup`, so the **default** group (= the
  `application-identifier`) is all genesis + the probe need — **not** the shared `app.tenshu.shared`
  group (cross-app C3 still needs free-tier *device* signing, `ios-signing.md` §2/§4). **Verified
  end-to-end (iPhone-16 sim):** `xcodebuild BUILD SUCCEEDED` (app now `codesign --force --sign -
  --entitlements …`); `-genesisAutoComplete` → **"Realm created + persisted"** (genesis's `setUpUnlock`
  succeeds — the *first* real genesis to complete on iOS); plain relaunch (no uninstall) → lands in
  **MainTabs / Control Home**, proving the RT-5 durable probe across process death. This closes the
  genesis-completion + cross-process-durability caveat the whole C4-RT track had carried as
  "tap-driven / on-device / manual". `ios-signing.md` tier table + intro updated. No threat-model
  touch (at-rest custody is the already-amended A32; no new key material / relay / wire).
- **RT-7 — returning-user unlock screen (the Locked landing branch). ✅ LANDED.** RT-6
  exposed a gap: a genesised device reopened in a fresh process is **sealed** (the unlock wrapper is
  persisted but the master keys aren't derived — the `SyncManager` autoload stays sealed once a
  wrapper exists), yet `ControlRootView` only knew `FirstTime` vs `Active`, so it landed in MainTabs
  with a locked, undecryptable realm and no way to unlock. Now mirrors the canonical three-way
  `TenshuLandingStateRepository` decision: `first_time → onboarding`, **`locked → unlock screen`**,
  `active → main tabs`. `ControlSurface` gains `unlockViewModel()` (over `LockedViewModel`),
  `unlockErrorMessage()` (typed `LockedError` → copy), and `landingStateKey()` (the canonical detect:
  `isLocked` first → `"locked"`, else `ownerId`-present → `"active"`, else `"first_time"`). New
  `LockedView.swift` = SwiftUI "Welcome back" + passphrase unlock (OnboardingKit vocabulary, mirrors
  the Compose `LockedScreen`); iOS has no biometric delegate so it's the passphrase path
  (`unlockLocal`) — on success keys load in-session → main tabs. `ControlRootView`'s launch/foreground
  probe switched to `landingStateKey()` + a `.locked` phase. DEBUG `-unlockAutoComplete` harness
  drives the unlock with the genesis verification passphrase (no taps). UI + VM wiring over existing
  `getUnlockStatus`/`getSyncStatus`/`unlockLocal` ops — **no protocol/wire/threat-model change**.
  **Verified end-to-end (iPhone-16 sim):** `xcodebuild BUILD SUCCEEDED`; genesis → relaunch now shows
  the **unlock screen** (was wrongly MainTabs); `-unlockAutoComplete` → `unlockLocal` →
  **MainTabs / Control Home** (keys loaded). Closes the sealed-on-reopen gap. ⚠ Biometric unlock is
  still inert on iOS (no `BiometricUnlockDelegate` actual — C1/C5 territory); passphrase is the floor.
- **RT-8 — "Lock now" (re-seal the realm on demand). ✅ LANDED.** Completes the unlock
  lifecycle: RT-7 unlocked a sealed realm, but nothing could *re-seal* one without killing the process
  (no re-lock primitive existed on **any** platform). Adds the cross-platform op + iOS UI. New
  **`LockRealm`** no-arg op (`sync-service`/`-client`/`-proxy`, returns `TBoolean`) registered in the
  client module + proxy dispatcher; **`SyncManager.lock()`** purges this session's in-memory key
  material (`derivedKeys` / master A1 / realm-encryption A13 / recovery phrase) so
  `getUnlockStatus().isLocked` flips true again — the persisted wrapper + device key (A4) untouched,
  sync-loop readers idle on the next tick (they already tolerate the null window). `SyncRepository.lock()`
  has a **throwing default** so only the real `TenshuSyncRepository` overrides it (no churn across the
  ~10 fakes). iOS: `ControlSurface.lockRealm()`; **Settings → Security → "Lock now"** (destructive)
  re-seals → `ControlRootView` routes back to the unlock screen (new `onLock` through `MainTabsView`).
  Threat-model A1 note (HR#2): a user lock only **shrinks** A1's in-memory window — no new boundary, no
  at-rest custody change. **Verified:** `control-shared-logic` desktopTest green (fakes inherit the
  default); JVM+iOS compile; `xcodebuild BUILD SUCCEEDED`; iPhone-16 sim `-lockRoundTrip`
  (unlock→`lockRealm`→`landingStateKey`) → *"landing is now locked (re-seal works)"*; Settings shows the
  Lock now button. **The full Control-on-iOS lifecycle — onboarding → genesis → relaunch-locked →
  unlock → main → Lock now → unlock — now works on the simulator.**
- **C3 cross-app Keychain handshake — works on the simulator (no device). ✅ LANDED.**
  Corrects an earlier assumption that C3 (Control writes the `epc-session` item → `drive-ios` reads
  it → Drive's locked first screen opens) needed free-tier *device* signing. RT-6 proved ad-hoc sim
  signing grants the *per-app* Keychain; this extends it to the **shared** `app.tenshu.shared` group.
  Both `control-ios` and `drive-ios` ad-hoc-sign with an entitlements file declaring the **bare**
  `app.tenshu.shared` group (no `$(AppIdentifierPrefix)` — ad-hoc has no team), matching the literal
  string the C3 read/write code passes as `kSecAttrAccessGroup`. `drive-ios` flips to ad-hoc signing
  (mirrors RT-6) + a new `drive-ios/Drive.entitlements`; `control-ios/Control.entitlements` adds the
  shared group; a DEBUG `-provisionSession` route writes the item tap-free. **Verified A/B on a
  freshly-erased sim:** no session → `drive-ios` shows *Connect to Control*; after `control-ios
  -provisionSession`, `drive-ios` relaunches straight into **Files** — it read Control's item across
  the app boundary (no `errSecMissingEntitlement` on either side). So the **§11.6 forward-to-Control
  handoff is functional + testable on the sim with no Apple ID / Team ID / device** — this unblocks
  Drive's locked first screen on iOS. No threat-model change (the C3 shared-item mechanism had already landed;
  this only makes it work on the sim). ⚠ This is the *simulator* config (bare group + ad-hoc);
  a **real device** still needs the Team-prefixed group + both apps under one team (`ios-signing.md`
  §2/§4) — that rung remains only for genuine on-device + App Attest (C5).
- **"Open Tenshu Control" deep-link — closes the C3 onboarding loop. ✅ LANDED.** C3 makes
  Drive's locked first screen open *once Control has already provisioned*; this slice wires the other
  half — on a fresh device `drive-ios` shows *Connect to Control* whose **Open Tenshu Control** button
  already calls `openURL(Control.url)` (`Control.url = tenshu-control://setup`, defined in
  `drive-ios/Sources/ControlSession.swift`), but nothing handled the scheme, so the `accepted`
  callback always returned `false` (Drive treated Control as not installed). Fix is **control-ios-only**:
  register `CFBundleURLTypes` (scheme `tenshu-control`, name `app.tenshu.control.setup`) in
  `control-ios/project.yml`'s `info.properties`. No URL-routing handler is needed — any launch lands on
  `ControlRootView`, which probes the realm state and shows onboarding/genesis. `drive-ios` needs **no**
  change: SwiftUI `openURL(_:completion:)` (UIApplication.open under the hood) does **not** consult
  `LSApplicationQueriesSchemes` — that allowlist only gates `canOpenURL`. **Verified on the sim:** after
  registering the scheme, `xcrun simctl openurl <DEV> tenshu-control://setup` makes iOS resolve the URL
  to **Control by name** (the system "Open in 'Control'?" confirmation appears, from both a foreground
  app and SpringBoard); pre-registration `openurl` errors out. So Drive's pre-existing button now
  launches Control, completing the fresh-device loop (Drive *Connect to Control* → Open → Control
  genesis/pairing → C3 `epc-session` write → return → Drive *Files*). No threat-model change (a URL
  scheme carries no secrets; the payload is a no-op `setup` host). ⚠ Tap-driven button press needs
  on-device/manual; the scheme→app resolution is sim-verified.
- ⚠ **The `-epcHostReal`, `-epcHostRegistered` and `-epcHostPublish` routes in the entries below were
  deleted on 2026-11-05** (last version at `066f6f363`); `-epcAppPairing` and Settings → Connect an app remain.
- **EPC app-pairing host UI on Control ("Connect an app"). ✅ LANDED.** The first surface
  to invoke the proxy's `DefaultEpcPairingHost.run()` on iOS — the §11.1 *third* flow (an app, e.g.
  Drive, pairs with Control), the Control-side counterpart to `drive-ios`'s EPC pairing *client*. The
  whole runtime existed after RT-2 (the `EpcPairingHost` `single` is bound in the iosMain
  `PlatformProxyModule`) but **nothing started it**; this adds the SwiftUI screen + bridge. Wiring is
  **additive, iOS-only** (no `:control-shared`/`-logic`/Compose change, so Android/desktop can't
  break): a new `tenshu-sdk-ios/iosMain/.../IosEpcPairingController.kt` mirrors the Compose
  `EpcPairingViewModel` state machine (`StateFlow<EpcPairingUiState>` with a plain `statusKey` String
  +`pairingUri`/`sas`/`error`) — it lives in the SDK, not `:control-shared-logic`, because the
  canonical VM **and** the `EpcPairingHost` interface sit in non-iOS / above-layer modules
  (`:control-shared` Compose UI, `:control-shared-proxy`), so an additive iOS controller resolved off
  the real runtime avoids a cross-module move. `ControlSurface` gains an `installedEpcPairingHost`
  seam (resolved from the real proxy Koin in `installRealRuntime()`) + an `epcPairingController()`
  factory; over fakes (DEBUG screenshot routes) a relay-free `sampleEpcPairingHost` surfaces a static
  invite so the screen still renders. New `control-ios/Sources/EpcAppPairingView.swift` renders the
  four states in the OnboardingKit vocabulary: **starting** (spinner) → **waiting** (the
  `tenshu://epc/pair#…` invite as a CoreImage QR via B-4's `qrCodeImage` + a copyable URI field +
  "Waiting for an app…") → **connected** (the 6-digit SAS "XXX YYY" to confirm against the app's
  screen) → **error** (message + Try again). Entry: Settings → **Apps → Connect an app**; DEBUG
  `-epcAppPairing` (sample host, no relay) + `-epcHostReal` (real runtime) deep-links for capture.
  **Verified (sim):** `xcodebuild BUILD SUCCEEDED` + `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64`
  green; `-epcAppPairing` renders the **waiting** state with the scannable QR + URI on the iPhone-17
  sim; `-epcHostReal` against a live dev relay (`:sync-relay-server:run`) drives the **real**
  `EpcPairingHost.run()` — it makes a real Darwin-engine request to the relay and renders the precise
  **`provision EPC session failed: 401 Unauthorized`** (the relay reached + rejected it, not a
  connection error). That 401 is the **expected next integration gate**: the EPC rendezvous-provision
  endpoint is auth-gated (device-key JWT), and the host harness skips genesis + relay registration, so
  the device has no JWT yet. A full green invite → Drive-connects → SAS → real File data needs the
  integration chain **genesis → relay registration (device JWT) → rendezvous provision authorized**,
  which is beyond this UI slice (and partly on-device). No protocol/wire/threat-model change — this is
  UI + a bridge over the existing `EpcPairingHost`; the EPC channel crypto + relay contract are
  unchanged. ⚠ The **connected/SAS** branch + the actual Drive↔Control data round-trip are gated on
  that 401 (relay auth) and remain pending.
- **EPC host over a relay-registered device — the 401 is closed (production-correct). ✅ LANDED.**
  The "Connect an app" host hit `provision EPC session failed: 401 Unauthorized` because the rendezvous-provision
  endpoint (`POST /sync/epc/{sessionId}`) is auth-gated on a relay-verifiable device-key JWT
  (`requireOwnerId` → `knownDevices.getPublicKey`), and the iOS device was never **registered** —
  genesis is local-first (ADR 0019): `CreateRealm` runs with the `registrationGate` *closed* (no
  subscription) so it seeds the realm locally and skips `POST /sync/realm`. The fix drives the
  **production registration path** head-less: a new `ControlSurface.devBindFreeAccount()` binds a free
  `PublisherClient` account (`signUpFree` → `pollVerification`, the same calls the Android/Desktop "set
  up account" flow makes), which the real runtime's entitlement writer maps to a minted `FREE`
  entitlement → the `StorageCapacityHolder` figures flip `hasStorage = true` → `StorageSyncGate`
  opens → the next `SyncScheduler` tick runs the deferred `RealmRegistrar.ensureRegistered()`
  (`POST /sync/realm`), registering the device. A new DEBUG `-epcHostRegistered` harness sequences
  genesis → `devBindFreeAccount()` → settle → present `EpcAppPairingView` over the **real** host.
  **Additive, iOS-only** (`ControlSurface` + harness; no `:control-shared`/`-logic`/proxy change — the
  whole registration chain already existed, it just had no head-less trigger on iOS). **Verified
  end-to-end (iPhone-16 ad-hoc-signed sim + dev `:sync-relay-server:run`)** — the relay log shows the
  exact chain: **`201 Created POST /sync/realm`** (device registered) → **`204 No Content POST
  /sync/epc/<sessionId>`** (provision **authed**, no 401) → repeated **`200 OK GET
  /sync/epc/<sessionId>/c2p`** (the host serving its accept loop); the screen renders the **real**
  `tenshu://epc/pair#…` invite (a live Bech32 payload with the relay URL embedded, not the `#sample`
  placeholder) + QR + "Waiting for an app…". So the EPC app-pairing host now provisions a real,
  app-scannable rendezvous session on the sim. No threat-model change — this is the *existing*
  local-first registration path (entitlement → gate → `POST /sync/realm`, all already amended), just
  triggered without tap tooling; `devBindFreeAccount()` is a DEBUG/dev seam. ⚠ The full **Drive↔Control
  data round-trip** (drive-ios scans the invite → SAS confirm → real ops over the channel) is the next
  slice. ⚠ Orthogonal observed anomaly (not blocking): a slow ~30s poller still logs periodic
  `401 POST /sync/changes` + `GET /sync/tombstones` alongside the fast 200 personal-realm sync —
  likely a second sync context using a stale/mismatched token; worth a follow-up look.
- **Same-device Drive↔Control EPC channel — handshake works; sustained ops hit the route-2 wake wall. ✅ LANDED.**
  The first time `drive-ios` and `control-ios` talk over a live EPC channel on **one simulator** — and an
  empirical confirmation of the §11.2/§11.5 route-1-vs-route-2 decision. Control publishes its live
  `tenshu://epc/pair#…` invite into the shared `epc-session` Keychain item (new
  `ControlSurface.publishEpcInvite()` over the existing `ControlSessionProvisioner` — the C4 "richer
  rendezvous payload" superseding the C3 presence-token placeholder), so a co-resident Drive reads it
  cross-app (both ad-hoc-signed into the bare `app.tenshu.shared` group, C3) — **no camera/QR needed
  same-device**. Drive's existing dev-harness EPC client (`EpcRelayPairing.connect` → SAS → `activate()`)
  does the rest. Two head-less harnesses sequence it: Control `-epcHostPublish` (genesis →
  `devBindFreeAccount` → register → provision → publish invite → **retain the controller so the
  accept loop keeps serving**) and Drive `-epcAutoConnectFromControl` (read invite from the Keychain →
  connect → auto-confirm SAS → on `liveSessionChanged`, jump to the App tab so `PairedAppView` issues a
  real files query). **Additive, iOS-only** (`ControlSurface.publishEpcInvite` + two DEBUG harnesses + a
  tab-switch; no protocol/proxy change). **Verified (iPhone-16 ad-hoc sim + dev relay, erased clean — the
  Keychain survives `simctl uninstall`, so a stale device key from a prior genesis blocks re-registration;
  `simctl erase` is required):** the relay log shows the **full bidirectional handshake** — Control
  `201 POST /sync/realm` → `204 POST /sync/epc/<id>` (provision) → both sides exchange handshake frames
  (`204 POST …/c2p` from Drive + `204 POST …/p2c` from Control) → Drive renders **"Live client active
  (SAS 384 547)"**, then auto-jumps to the App tab where `PairedAppView` mounts (real Drive Files UI) and
  fires a files query over the channel. **⚠ The data-op query then hangs on "Loading…" — and the relay log
  shows exactly why: Control's `c2p` serving polls STOP the instant Drive comes to the foreground
  (`last c2p 23:35:41`), while Drive keeps `p2c`-polling for a response that never comes
  (`last p2c 23:38:07`).** iOS **suspends the backgrounded Control even while Drive is foreground** — the
  exact "wake-window problem" §11.2/§11.5 names as the reason **route-1 (in-process collapse) is the
  same-device design** and route-2 (EPC-to-co-resident-Control over the relay) is reserved for the
  remote-proxy + push-wake case. So this slice **proves the EPC channel establishes same-device** (invite
  hand-off + bidirectional handshake + SAS + live-client install) **and empirically demonstrates why
  sustained same-device data flow needs route-1**, not this relay-rendezvoused path. No threat-model change
  (the invite is an ephemeral pairing secret, same as the QR; the channel crypto/relay contract are
  unchanged). **NEXT: route-1 — the in-process-collapse data plane (C4e), where Drive runs realm crypto
  in-process over Control's single device identity via the App Group (no second process to suspend).**

**Risks / notes.** (a) file-service: iOS uses the commonMain `fileServiceProxyImplementationFactory` — no
android FD-cache / jvm variant; confirm Control's Data section exercises no native-only op. (b) No new
threat-model assets expected — relay registration is the same device-key JWT path; key custody reuses the
already-amended A4/A32/A5; reconfirm during RT-3 (HR#2). (c) `:ui-core` stays out (verified). (d) **Then
C6 device-pairing** sits on top: `PairingWizardViewModel` (`-logic`) already exists; it needs a SwiftUI
`PairingWizard` port (like the C2c/onboarding-align SwiftUI screens) over the now-reachable sync-service
pairing host.

### C5 — Real Apple App Attest (6c/6d)

Proxy-side `AppAttestVerifier` doing real verification (CBOR attestation object + X.509 chain to
Apple's App Attest root CA + nonce / RP-ID / counter checks + assertion signatures), an iOS-side
`AppAttestor` over `DCAppAttestService`, and wiring `EpcAttestationServer` into the proxy accept
path. The Phase-4a seam takes both with no protocol change. Required for third-party apps over EPC
and to harden the channel. Test against Apple sample vectors.
- *Apple-gating:* needs a **registered App ID + App Attest entitlement**; App Attest cannot run on
  the simulator. This is the first hard-blocked slice.

### C6 — Remaining flows: Control device-pairing + background windows + Synced-Folders reframe

> **Hard dependency:** Control device-pairing requires **C4-RT** (a real, networked Control proxy on
> iOS). Today genesis runs over fakes, so the iPhone holds no real keys and never contacts the relay —
> the pairing handshake has nothing to ride. Land C4-RT first; pairing then reduces to a SwiftUI
> `PairingWizard` port over the (now-reachable) sync-service pairing host.

- **Control device-pairing** (the third §11.1 flow): a 2nd Control (desktop / 2nd phone) joins the
  iPhone-genesised realm via the existing cross-Control pairing ceremony
  ([`sync/pairing-flow.md`](../sync/pairing-flow.md)). `PairingWizardViewModel` already exists in
  `:control-shared-logic`; the missing piece is the SwiftUI screen + the real runtime from C4-RT.
- **Background sync windows** (O4): `BGTaskScheduler` / throttled push / background `URLSession`,
  with an iOS `PlatformScheduler` backing the portable `HeadlessSafe` / `ScheduleService` seams;
  `ConstraintSyncGate` WifiOnly / charging map onto `BGProcessingTask` requirements (§9.4). This is
  also the slice that makes route-2-to-local-Control viable for the future third-party case.
- **Synced-Folders auto-backup — reframe, do not port** (§9.4): PhotoKit change-history on
  launch/refresh + background `URLSession` + opportunistic `BGProcessingTask` + an explicit
  "open the app to finish backing up" nudge. Correctly gated as iOS Ph4.

#### Track B — SwiftUI device-pairing wizard over fakes (parallel to C4-RT)

The "Add a device" SwiftUI wizard binds the existing Compose-free `PairingWizardViewModel` +
`HostPairingMonitor` (`:control-shared-logic`) through `ControlSurface`, drivable on the simulator
over fakes before the real C4-RT runtime lands. Sliced B-1 (fakes + surface seam) → B-2 (joiner UI)
→ B-3 (host/Show UI) → B-4 (optional native QR). The only file shared with Track A is
`ControlSurface.kt`, edited additively.

- **B-1 — pairing fakes + `ControlSurface` seam. ✅ LANDED.** Enriched
  `FakeControlSyncRepository` (`tenshu-sdk-ios/.../FakeControlRepositories.kt`) so pairing works over
  fakes: `startPairing()` → a real `SyncStatus` with a fake `tenshu://pair` string (host **Show**
  tab renders a code); `initiatePairing()` → a real `PairingInitiation` with a fixed SAS (`123456` →
  "123 456" after the 3-3 split); `awaitPairing()` → a new `FakeControlPairingTaskHandle` resolving
  `Success(true)` after a short, cancellable delay (joiner reaches **Success** with no second
  device). The composites are materialised from a lazily-assembled minimal `TContext` (`coreCommonModule`
  + the `syncServiceCommonModule` schema only — the same `coreSchemaConfigs` seam the production
  `EpcDriveClient.assembleClient` uses). `ControlSurface` gained (additive only): an
  `installHostPairingMonitor` seam + a lazily-cached default `HostPairingMonitor` over the installed
  sync repo (process-lifetime single, mirroring the Compose Koin `single`); a `pairingWizardViewModel()`
  factory; and `pairingStepKey`/`deviceModeKey`/`pairingErrorMessage` `String` bridges so SwiftUI
  switches on plain keys, never the sealed `PairingStep`/`AddDeviceMode` across SKIE (the `genesisStepKey`
  pattern). *Verify:* `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64` + `assembleTenshuSdkDebugXCFramework`
  green; the new selectors export in the generated ObjC header (no `new`-prefix drop). No SwiftUI yet (B-2).
- **B-2 — joiner ("Scan") SwiftUI wizard. ✅ LANDED (simulator-green).** New
  `control-ios/Sources/PairingView.swift` binds `ControlSurface.pairingWizardViewModel()` and renders
  the joiner flow natively in the OnboardingKit vocabulary (`OnboardingScaffold` + Amethyst buttons +
  `MarkOutline`), switching on `pairingStepKey`: **ManualCode** (text entry → `onQrScanned` →
  NameDevice) → **NameDevice** (prefilled `UIDevice.current.name` + Continue → `confirmPairing`) →
  **Approval** (SAS rendered "XXX YYY" 3-3 split + spinner + Cancel→`onBackToScanStart`) → **Success**
  (Continue → `onSuccessContinue`). Copy mirrors the canonical `PairingWizardScreen.kt` (`pairing_manual_*`,
  `pairing_name_*`, `pairing_approval_*`, `pairing_success_*`) in a `PairingStrings` enum. ⚠ **text-code
  only** — `:ui-core`'s `QrCode`/`QrScanner` are Compose, unusable on iOS, so the camera `ScanQr` step
  collapses into ManualCode (native AVFoundation scanner = optional B-4). Entry: a Settings **Devices →
  Add a device** button presenting the wizard as a sheet. To let the joiner flow run over fakes with no
  real host, `ControlSurface.fakeJoinerPairingCode()` (additive) returns a valid Bech32-encoded
  `tenshu://pair` link prefilled into the ManualCode field (fakes-era; RT-3 drops it). DEBUG launch-args
  `-pairingStep manual|name|approval|success` + `-settingsHome` deep-link screens for screenshots
  (mirror `-onboardingRoute`). *Verify:* `xcodebuild -scheme ControlIos` **BUILD SUCCEEDED**; all four
  joiner steps + the Settings entry **render on the iPhone-17 simulator**, and the **full fake handshake
  completes end-to-end** (ManualCode decodes the Bech32 code → NameDevice → Approval shows "123 456" →
  `FakeControlPairingTaskHandle` resolves → Success). ⚠ gotcha re-confirmed: a freshly built
  `control-ios` app must be resolved from `xcodebuild -showBuildSettings BUILT_PRODUCTS_DIR`, **not**
  `find … | head` — multiple `ControlIos-*` DerivedData dirs exist and `head` grabs a stale one;
  `strings`/`grep` can't see Swift string literals so they're a useless freshness check. Host **Show**
  tab + segmented control = B-3.
- **B-3 — host ("Show") tab + Scan|Show segmented control. ✅ LANDED (simulator-green).** SwiftUI-only
  (extends `PairingView.swift`; no Kotlin change — the `deviceModeKey` bridge + `HostPairingMonitor`
  install seam + `hostPairingString`/`hostConnectionLost` uiState fields all landed in B-1). At "tab-root"
  states (Scan's ManualCode/ScanQr, or the whole Show tab) a `.segmented` `Picker` switches mode via
  `onSelectScan`/`onSelectShow` (the binding's setter); it hides once the joiner commits (NameDevice /
  Approval / Success), mirroring the canonical `isTabRoot`. The **Show** branch renders the host pairing
  string mirrored from the process-lifetime `HostPairingMonitor` (over B-1's fake `startPairing` →
  `SyncStatus.pairingString`): heading/body (`add_device_show_*`), the code as **selectable monospaced
  text** (`.textSelection(.enabled)`) in a bordered field with a **Copy** affordance (`UIPasteboard`) —
  the iOS analogue of the canonical read-only `AppTextField` + copy `IconButton` (no QR *image* on iOS;
  that's B-4) — a **connection-lost banner** (`hostConnectionLost` → `add_device_connection_lost`), and a
  destructive **Stop** (`onStopShowing`, new `OutlinedDestructiveButtonStyle`). When no code yet and no
  error, a spinner; on error, `sync_dashboard_pairing_error`. DEBUG `-pairingStep show` deep-links the
  Show tab. *Verify:* `xcodebuild` **BUILD SUCCEEDED**; the Show tab (segmented control + host code +
  Copy + Stop) and the segmented control on the Scan tab **render on the iPhone-17 simulator**. Optional
  **B-4** (AVFoundation camera scanner for ScanQr + CoreImage `CIQRCodeGenerator` for Show) remains.
- **B-4 — native QR (camera scanner + QR image). ✅ LANDED (simulator-green; live camera device-only).**
  Replaces the Compose `:ui-core` `QrScanner`/`QrCode` (unusable on iOS) with native equivalents in a new
  `control-ios/Sources/QRKit.swift`: **`QRScannerView`** (a `UIViewControllerRepresentable` over an
  `AVCaptureSession` + `AVCaptureMetadataOutput(.qr)` — emits the decoded `tenshu://pair` string to
  `onQrScanned` once, then stops the session) and **`qrCodeImage(from:)`** (CoreImage
  `CIFilter.qrCodeGenerator`, nearest-neighbour upscaled). The Scan tab now defaults to the camera
  (`scan_qr`, the VM's initial step — `start()` no longer force-routes to ManualCode) with an "Enter code
  instead" link (`pairing_scan_enter_code`) and, when `isCameraAvailable` is false (the **simulator**), a
  framed "Camera unavailable — enter the pairing code instead" fallback. The Show tab renders the
  `qrCodeImage` of the host pairing string above the copyable text. `NSCameraUsageDescription` added to
  `project.yml`'s generated `Info.plist`. ⚠ **The live camera can't be exercised on the simulator** (no
  capture device) — the scanner compiles + is wired, but visual verification of an actual scan is
  device-only; the simulator shows the graceful fallback. *Verify:* `xcodebuild` **BUILD SUCCEEDED**
  (PairingView + QRKit compile); the **Show QR image**, the **Scan camera-unavailable fallback +
  "Enter code instead" → manual**, and the manual path all **render on the iPhone-17 simulator**; the
  camera key is present in the built `Info.plist`. **Track B (C6 pairing UI) is now complete** (B-1 fakes
  + B-2 joiner + B-3 host + B-4 native QR); the real cross-Control handshake replaces the fakes via Track
  A's RT-3.
- **B-5 — wire the wizard to the real RT-3 runtime. ✅ LANDED (simulator-green).** Now that RT-3 shipped
  `installRealRuntime()` (called from `ControlIosApp.init` on every non-screenshot boot), the
  `pairingWizardViewModel()` factory already resolves the **real** `TenshuSyncRepository` + process-lifetime
  `HostPairingMonitor` through the `installed*` seams — so the production wizard rides the real proxy with
  no Kotlin change. The only fakes-era residue was the SwiftUI layer: `PairingModel.pairingCode` was
  hard-prefilled with `ControlSurface.fakeJoinerPairingCode()`, which over a real relay would post a
  garbage handshake (zero pubkey + `ios-fake-secret`). Fixed: the field now defaults to **empty** (the
  joiner enters/scans a real host code); the `-pairingStep` **DEBUG screenshot** deep-links — which run
  over the B-1 fakes because `ControlIosApp.init` skips `installRealRuntime()` for those routes — prefill
  the sample explicitly inside `start()`. `fakeJoinerPairingCode()`'s doc is retitled DEBUG-screenshot-only.
  *Verify:* `xcodebuild` **BUILD SUCCEEDED**; a normal launch (no args) **assembles the real proxy graph
  and renders genesis** on the iPhone-17 simulator (no crash — the wizard behind it now rides that runtime),
  and the `-pairingStep` deep-links still render the joiner over fakes. ⚠ A full real-relay pairing
  round-trip (genesis → Show/Scan against a dev relay, or a 2nd Control) is **device/tap-driven** and still
  pending — the same on-device gate as the rest of C4-RT.
- **B-6 — enable the onboarding joiner path. ✅ LANDED (simulator-green).** The last gap: the
  Welcome chooser's **"I have Tenshu on another device"** button was disabled (*"Coming soon on iOS"*).
  With B-5's real-runtime wiring in place it's now live. `WelcomeView` gains `onExistingDevice`, which
  presents the joiner wizard (`PairingView`) as a sheet. Two new `PairingView` params (defaulted, so
  `SettingsView` is untouched): `joinerOnly` hides the **Scan | Show** role picker — a realm-less
  onboarding device can't host — and `onPaired` fires on the success-`done` path before the sheet closes.
  On success the sheet calls `provisionDriveSession()` (C3 handoff; deleted 2026-11-05 in route-1 cleanup D — now `ensureDriveProvisioned()`) + `onComplete()`, mirroring genesis's
  `openControl`. The unused `welcomeExistingDeviceComingSoon` string is dropped; DEBUG
  `-onboardingRoute pairing` auto-presents the joiner sheet over the Welcome chooser for screenshots.
  UI-only over the existing real pairing runtime — no protocol/wire/threat-model change. *Verify:*
  `xcodebuild` **BUILD SUCCEEDED**; the Welcome button renders enabled (no caption); `-onboardingRoute
  pairing` shows the **Add a device** joiner sheet with no Scan|Show picker. Same on-device cross-user
  round-trip gate as the rest of C4-RT.
- **B-7 — final-review polish. ✅ LANDED (simulator-green).** Five low-risk cleanups from the track's
  final review: (1) **camera re-arm** — `QRScannerView` no longer permanently stops on a scan; it gates
  re-detection for a 2s cooldown so a *rejected* code can be retried without a frozen preview (a *valid*
  scan unmounts the view → `dismantleUIViewController` stops capture); (2) `AVCaptureSession.stopRunning()`
  moved **off the main thread** (a `stop()` helper); (3) the host **QR image is cached** on the model
  (regenerated only when `hostPairingString` changes, not every `body` eval — CoreImage render is
  non-trivial); (4) `FakeControlPairingTaskHandle`'s dead default delay param dropped + `cancelled`
  checked before the delay; (5) an accessibility label on the Scan|Show segmented `Picker`. UI/fakes-only;
  no protocol/wire/threat-model change. *Verify:* `xcodebuild` **BUILD SUCCEEDED** (no warnings); the Show
  QR + Scan fallback still render on the iPhone-17 simulator. The camera re-arm is device-only (the sim
  has no capture device), so it compiles + is wired but a live retry is verified on-device.

- **B-8 — the host could show a code but never answer it. ✅ LANDED (gate + Swift build green).**
  Found from a real Android↔iPhone attempt, 2026-08-26: the iPhone hosts (Show → `tenshu://join` QR),
  Android scans, posts its `PairingRequest`, shows the SAS — and the iPhone does nothing, with no error
  on either side until the joiner's ~5-min budget quietly elapses. ⚠⚠ **Nothing below the UI was
  broken**: the monitor polls, `GetPendingPairingRequests` is registered, the request arrives and lands
  in `HostPairingMonitor.activeRequest` — which on Android/desktop drives the app-global Compose
  `HostPairingApprovalDialog` (`App.kt`), and which **no iOS surface read**. B-3 ported the Show tab's
  code + banners + Stop; the approval is not in that screen on Compose, so it was never in the port
  either. §3 Step 3 — the "active approval" the whole ceremony turns on — had no iOS surface at all.
  Fixed in three pieces: (1) the shared VM now mirrors the request
  (`hostRequestDeviceName`/`hostRequestSas`/`hostApprovalError`) and exposes
  `approveHostRequest`/`denyHostRequest` — ⚠ Compose deliberately does **not** read these, or the
  approval would render twice; (2) `PairingView`'s Show tab renders the prompt in-tab (name + SAS card +
  Approve/Deny + failure reason) and, ⚠ load-bearing, a **`hostPairingComplete` terminal** — approval
  clears `hostPairingString`, so without it the tab falls straight back into its "no code yet" spinner
  and never says the pairing worked; (3) `ControlSurface.hostPairingApprovalBound()` +
  `HostPairingApprovalHost` put the same approval on the **ready-phase root** as an alert, because the
  host session is process-lifetime and the request can arrive after the sheet is closed. ⚠ The two
  suppress each other via `PairingSurfacePresence` (one monitor, two surfaces — an alert stacked over
  the in-tab prompt is the obvious wrong outcome). ⚠ While an approval is pending the Show tab is no
  longer "tab-root": the Scan|Show picker calls `onSelectScan` → `stopPairing()`, one stray tap from
  burning the session mid-comparison. Deliberately **no notification**: an approval arriving while
  Control is closed cannot be surfaced at all (§9.4), and approving from a lock-screen button without
  the SAS in front of you is the wrong ceremony
  ([`ios-control-notifications-and-shares.md`](ios-control-notifications-and-shares.md) §3). UI +
  view-state only; no protocol, wire or threat-model change. *Verify:* four new
  `PairingWizardViewModelTest` cases (mirror, approve→terminal, failed-approve keeps the prompt +
  reason, deny ends the session) — two of them fail if the mirror is removed; `scripts/run-gate.sh` all
  green; `iosKotlinCheck` + `scripts/ios-smoke.sh --build-only control` green. ⭐ **And on-device, 2026-08-26** — iPhone 17e hosts (Show → QR), a physical Android Control scans,
  **the approval prompt appears on the iPhone with the SAS, Approve → "Device added"**. That is the
  half B-8 fixes, and it is verified.
  ⚠⚠ **Corrected the same day: the JOINER half is NOT verified, and the first report of it here was
  wrong.** A later attempt on the same pair had the Android end on *"Pairing failed"* while the
  iPhone said "Device added"; its store, pulled over `run-as`, holds **no realm and no `Device` row
  at all** — only `awaitPairing → ChaCha20-Poly1305 decrypt failed: mac check failed`. So the joiner
  has never installed keys in either run, and this doc's original "and the joiner completes" was
  taken from a screen on the *other* device. **A host terminal reading "Device added" is not
  evidence about the joiner** — the host writes its peer row and posts its response before the
  joiner decrypts anything. See `tech-debt.md` → *"The joiner's decrypt fails its MAC check"*.
  ⚠ The host's happy path prints **nothing** — `HostPairingMonitor` logs only on failure, and the
  `PairingWizard ->` lines are the *joiner*'s. A `devicectl --console` stream with no pairing lines in
  it is what success looks like here; do not read it as the request never arriving.

- **B-9 — the Show tab's *other* role was missing too. ✅ LANDED (Swift build green).** Found while
  fixing B-8 and shipped with it: `PairingView` read only the **host** half of the Show tab. The VM's
  offerer role (`offerRole` / `offerUri` / `offerSas` / `offerPaired` / `offerDenied` / `offerFailed`,
  `sync-pairing-inverted-direction.md` §5.1) had no SwiftUI at all, so the tab fell through to the host
  branch's "no code yet" spinner and stayed there. ⚠⚠ **Not just the unpaired-device case** — a
  *paired* iPhone whose relay registration was lost is flipped into the offerer role **in place** (the
  `deviceUnrecognized` collector), which is the one way a user reaches it without doing anything
  unusual, and the spinner sat under the red "this device isn't recognised" banner: the failure the
  role switch exists to repair. Ported `OfferCodeStep` (offer QR + copyable code + Stop; the
  `denied`/`failed` terminals with "Show a new code"; the `paired` terminal with the **post-hoc** SAS,
  §4.5). ⚠ No connection-lost banner in this branch, matching Compose — being refused is *why* the tab
  switched roles. ⚠ Its Continue goes through `onSuccessContinue` (→ `onPaired`), unlike B-8's host
  terminal: here **this** device is the one that just joined. SwiftUI-only; the state, the role branch
  and the terminals were all already in the shared VM and covered by its tests. *Verify:*
  `scripts/ios-smoke.sh --build-only control` green. ⚠ **Not exercised on-device** — B-8's device pass
  covered the host role only; reaching this branch needs an unpaired iPhone, or one the relay has
  forgotten, neither of which was worth manufacturing on the maintainer's live device.

**✅ Track B (C6 pairing UI) — fully landed** (B-1…B-9): fakes/seam → joiner → host → native QR → real
runtime → onboarding joiner path → review polish → host approval → offerer role. Remaining is the on-device real-relay
round-trip (device/signing-gated, shared with the rest of C4-RT).

---

## Prerequisites outside the repo's control

- **Apple Developer Team ID + App ID + App Attest entitlement.** (The free team `FAYT82GWD7` exists
  for the desktop MasterKeyStore cert OU, but App Attest needs a real registered App ID.) Gates
  on-device C2/C3 and all of C5; the KMP/simulator parts of C0–C4 do not need it.
- **An Xcode project / toolchain + a macOS build host.** `ios.yml` targets `macos-latest`, but CI is
  currently billing-blocked (verify locally).

## Open questions still live

- **O1 — Co-resident transport.** Foreground loopback fast-path vs always relay-brokered for the
  *third-party* EPC-to-local case (route 1 makes this moot for first-party Drive). Resolve when C6
  picks up the third-party path.
- **O4 — Background liveness budget.** The push-assisted wake windows + degraded UX for route-2
  consumers. Core to C6.
- **O5 — Rei ordering.** Only measure 3 (Rei) is gated on `:rei-service` (absent); measure 2
  (attestation, C5) is independent.

---

## Kickoff prompt (paste into Claude Code to start C1)

> I'm starting **C1** of `docs/proposals/ios-control-handover.md` — iOS-izing `:control-shared` and
> building the iOS `MasterKeyStore` (Secure Enclave wrap key + Keychain, Face ID/passcode gated).
> **Read first:** this handover, `ios-proxy-architecture.md` §§3/9.4/11, `desktop-masterkeystore.md`
> (the macOS custody analog), the 6b.5 as-built notes (the `:core-paging` / `:drive-shared-logic`
> iOS-ization precedent for adding iOS targets to a KMP module), and House Rules #1–#7 in `AGENTS.md`.
>
> **Before writing code, confirm scope with me** — propose the iOS target set for `:control-shared`
> + its dependency closure, and the Secure Enclave / Keychain approach (matching the desktop
> MasterKeyStore shape), before starting. **Stop and ask** rather than silently: exporting any
> `-proxy` code into the iOS umbrella (House-Rule-#1 packaging call), or standing up a new Xcode
> project / signing identity. Keep the umbrella rule (no per-module frameworks). The key-custody
> threat-model amendment rides the same PR as the code (House Rule #2). Operations stay non-`suspend`
> at the interface level (ADR 0012).
>
> **When done:** open a PR (squash; branches auto-delete), verify by running the affected tests
> (House Rule #7 — note on-device/Xcode verification may be impossible headless; say so), and update
> this handover's "Repo state going in" + the C1 slice with as-built notes.

---

## Parallel kickoff prompts — Track A (C4-RT runtime) ∥ Track B (C6 pairing UI)

The real proxy runtime (C4-RT) and the SwiftUI pairing wizard (C6 UI) **parallelize**: Track A is
Kotlin module work; Track B is SwiftUI built over fakes (as the genesis onboarding was). The **only**
shared file is `tenshu-sdk-ios/src/iosMain/.../ControlSurface.kt` — Track B only *adds* accessors +
enriches the fakes; Track A flips the *installed* repos to real in RT-3. **Merge Track B first**, then
Track A's RT-3 rebases onto its accessors. Each track runs in its own worktree off latest master.

### Track A — paste into Session 1

> We're building Tenshu (KMP, E2E-encrypted sync; Control is the proxy app). **Read first:**
> `docs/proposals/ios-control-handover.md` — especially **§ "C4-RT — Real Control proxy runtime on
> iOS (the `:control-shared-proxy` extraction)"** (that IS your spec) — and `AGENTS.md` House Rules
> (esp. HR#5 native iOS UI; "mirror production wiring, not test fixtures").
>
> **GOAL:** stand up a real, networked Control proxy runtime on iOS, replacing genesis-over-fakes —
> the fork-independent, free-tier prerequisite for iOS device-pairing. Work the sub-slices in order,
> each its own worktree → PR → squash-merge → teardown:
>
> **RT-1 (pure refactor — safety net):** create a Compose-free module `:control-shared-proxy` (KMP:
> android, jvm("desktop"), iosArm64, iosSimulatorArm64 — NO iosX64). Move from
> `:control-shared/commonMain`: `di/ProxyModule.kt`, the EPC runtime (`epc/DefaultEpcPairingHost`,
> `EpcPairingHost`, `EpcRendezvousServer`, `EpcClientConnection`, `EpcCallbackRouter`,
> `EpcPairingInvite`), `data/` wiring (`EntitlementMintClient`, `EntitlementStorageInitialization`,
> `StorageSyncGate`), and the android + desktop `di/PlatformProxyModule.kt` actuals. Layering becomes
> `-logic` → `-proxy` → `-shared`. `:control-shared` keeps ALL Compose `ui/` + `App.kt` + navigation +
> the `@Composable rememberBiometricPromptHost()` half of `BiometricUnlockDelegate.kt` +
> `EpcPairingScreen/ViewModel`. Update `settings.gradle.kts` + build files of `:control-shared` and
> both apps. **BAR:** zero behavior change; `./gradlew :control-shared:desktopTest
> :control-shared-logic:desktopTest` green (+ android compile). No iOS yet.
>
> **RT-2 (iOS targets + actuals):** add iOS targets to `:control-shared-proxy` + write the iosMain
> `PlatformProxyModule` actual per the §C4-RT mapping table (Ktor Darwin engine; `IosSecureStorage`
> C1; inert biometric; `SyncManagerImpl` C4a/C4c; `Rest*` relay clients; `FileObjectStore` C4b;
> InMemory File/Preview; `LocalClientConnection`+`EpcClientConnection`, no inter-app; common
> `fileServiceProxyImplementationFactory`; no WorkManager — coroutine sync drain). **BAR:**
> `compileKotlinIosSimulatorArm64` + `IosArm64` green (root build skips iOS *test* compile for -proxy
> modules — expected).
>
> **RT-3 (flip umbrella to real):** in `ControlSurface.kt` construct the real ProxyModule-backed
> runtime (mirror PRODUCTION wiring — `CoreCommonModule`/`ProxyModule`, NOT `TestProxy`) and install
> the real `TenshuSyncRepository(get<TenshuClient>())` for genesis. Run `:sync-relay-server:run`
> locally; from the simulator do real genesis: create realm → confirm relay registration →
> kill+relaunch → confirm durability (FileObjectStore + persisted A4). Screenshot.
>
> **CONSTRAINTS:** fork-independent + free-tier (no App Group; simulator vs dev relay). No
> threat-model amendment expected (reuses device-key JWT + A4/A32/A5) — reconfirm in RT-3 and flag if
> you touch A1–A4/A9/relay/protocol (HR#2). Per worktree: rebuild the XCFramework
> (`./gradlew :tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework`) + copy `local.properties` in.
> **COORDINATION:** Track B (parallel) builds the pairing UI over fakes; only `ControlSurface.kt` is
> shared — keep its additive accessors when you rebase, just swap the installed repo to real. Land
> RT-3 after Track B. **Start with RT-1; verify `git log origin/master..HEAD` before any PR.** Update
> the §C4-RT slice with as-built notes when done.

### Track B — paste into Session 2

> We're building Tenshu (KMP, E2E-encrypted sync; Control is the proxy app; iOS UI is SwiftUI, not
> Compose — House Rule #5). **Read first:** `docs/proposals/ios-control-handover.md` § "C6" (and
> § "C4-RT" for context on the parallel runtime track), and the SwiftUI onboarding you'll mirror in
> style: `control-ios/Sources/OnboardingKit.swift` + `OnboardingView.swift` + `RecoveryView.swift`
> (centered `OnboardingScaffold`, Amethyst purple via `OnboardingColors`,
> Primary/Outlined/LinkButtonStyle, `MarkOutline`). **REUSE `OnboardingKit`** — don't reinvent the
> theme.
>
> **GOAL:** build the SwiftUI Control device-pairing wizard ("Add a device") over FAKES, drivable on
> the simulator before Track A's real runtime lands (as genesis onboarding was). Bind the existing
> Compose-free `PairingWizardViewModel` + `HostPairingMonitor` (`:control-shared-logic`) through
> `ControlSurface`; render natively.
>
> **STATE MACHINE** (`PairingWizardUiState`): `mode` Scan(joiner)|Show(host) segmented control;
> `step` ScanQr → ManualCode → NameDevice → Approval(SAS) → Success → Done. Handlers: onSelectScan/
> onSelectShow, onPickManual, onQrScanned(data), confirmPairing(deviceName), onBackToScanStart,
> onSuccessContinue, onStopShowing.
>
> **SLICES** (each its own worktree → PR → squash-merge → teardown):
> - **B-1 (fakes + surface seam):** enrich `FakeControlSyncRepository` (FakeControlRepositories.kt) so
>   pairing works — `initiatePairing` → non-null `PairingInitiation` with a fixed SAS ("123 456");
>   `awaitPairing` → a `TaskHandle` resolving Success(true) after a short delay; `startPairing` → a
>   `SyncStatus` with a fake pairing string. Add to `ControlSurface`: an installed `HostPairingMonitor`
>   + `installHostPairingMonitor` seam, a `pairingWizardViewModel()` factory, and Kotlin-side
>   `pairingStepKey`/`deviceModeKey`/`pairingErrorMessage` String bridges (mirror `genesisStepKey`).
>   **BAR:** umbrella iOS compiles + XCFramework assembles.
> - **B-2 (joiner UI):** SwiftUI over `pairingWizardViewModel().uiState`, switching on
>   `pairingStepKey`: ManualCode (text entry → onQrScanned then NameDevice), NameDevice (prefilled +
>   Continue), Approval (SAS big/monospace "XXX YYY" + spinner + Cancel), Success (Continue →
>   onSuccessContinue). Match canonical copy (`PairingWizardScreen.kt`: `pairing_manual_*`,
>   `pairing_name_*`, `pairing_approval_*`, `pairing_success_*`). Entry: a Settings "Add a device"
>   button. ⚠ `:ui-core` `QrCode`/`QrScanner` are Compose → NOT usable on iOS; MVP is text-code only.
> - **B-3 (host/Show UI):** the Scan|Show segmented control; Show renders the pairing string as
>   selectable text + Copy + connection-lost banner + Stop (onStopShowing), driven by the fake monitor.
> - **B-4 (OPTIONAL native QR):** AVFoundation camera scanner (ScanQr) + CoreImage `CIQRCodeGenerator`
>   image (Show). Defer if time-boxed.
>
> **VERIFY** each slice: `./gradlew :tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework`, then
> `(cd control-ios && xcodegen generate)` — REQUIRED when adding a `.swift` file (XcodeGen globs at
> generate-time) — then `xcodebuild` for the simulator + `simctl` screenshot. No tap tooling (no idb);
> add a DEBUG-only launch-arg deep-link for screenshots (mirror `OnboardingView`'s `-onboardingRoute`).
> **COORDINATION:** Track A (parallel) extracts the real runtime; only `ControlSurface.kt` is shared
> and your edits there are ADDITIVE. Merge Track B first. Don't touch `:control-shared` /
> `:control-shared-proxy` / build files (Track A). **Start with B-1; verify `git log
> origin/master..HEAD` before any PR.** Update the §C6 slice with as-built notes when done.
