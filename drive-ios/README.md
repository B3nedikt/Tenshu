# Drive iOS app (Phase 6b.5 — forward-to-Control onboarding + minimal main UI)

The first iOS app for Tenshu. It links its own static `TenshuSdk` framework (`:tenshu-sdk-ios-drive`) and is
a **Control-gated** first-party Drive client (proposal §11.6): onboarding is a
forward-to-Control handoff, and the paired app is SwiftUI over the SKIE-exported
`:drive-shared-logic` view models.

## Two schemes

| Scheme | Config | What it is |
|--------|--------|------------|
| **DriveIos** | `Debug` | the shipping first-party app — `RootView` only |
| **DriveIos-Dev** | `DebugHarness` (defines `DEV_HARNESS`) | adds the dev/test surfaces §11.6 moves out of the shipping UI |

### DriveIos (shipping)

`RootView` reads the **shared Keychain** for a Control-provisioned session and routes:

- **present →** ride Control's identity silently → **Files** (no QR, no SAS — a
  first-party app co-resident with Control rides its identity over the App Group +
  shared Keychain, an Apple-gated same-Team-ID local channel, §1/§11.5);
- **absent →** a single **Connect to Control** screen that deep-links the user to
  Control to set up, then re-checks on return.

The paired app is one `NavigationStack` rooted at **Files**, with **+ Add**
(Document / Photo picker → foreground `FileUploader`, §9.4 / O3 Option A) and
**Settings** (status + *Open Control*; **no in-Drive unpair** — removal lives in
Control, `control-ia.md` §4.3) on the toolbar. Folder rows push a child Files
screen. Everything below `RootView` binds the **real** `FilesScreenViewModel` /
`AddFileActionsViewModel` (`:drive-shared-logic`) — the listing *contents* are
canned (`DriveReadSurface`) until a proxy serves Drive ops on iOS (6d/6e).

### DriveIos-Dev (dev/test harness)

Additionally surfaces, as extra tabs, the surfaces §11.6 keeps out of the shipping
UI — they are the dev/test harness + future third-party path, **not** first-party
onboarding:

- **Pair** — the §10 QR+SAS EPC pairing client (`EpcPairingView`): scan or paste a
  Control's `tenshu://epc/pair#…` link, confirm the 6-digit SAS, and the live
  `EpcDriveClient` installs over the relay.

(The **Smokes** tab — in-app crypto / rendezvous / EPC loopback / read-surface
probes — was deleted 2026-11-05; the Kotlin unit tests cover the protocol, and
`EpcDriveClientTest` guards the client assembly on `iosSimulatorArm64Test`.)

For UI work with no Control on the simulator, a dev stub stands in: the *Connect to Control* screen has a **Simulate Control
set-up** button (DEV_HARNESS only), or launch with
`DRIVE_DEV_PROVISION_CONTROL=1` to provision headlessly. (The unsigned simulator
has no Keychain entitlement, so the stub also uses a local fallback there; the
shipping read path is Keychain-only.)

## Layout

- `project.yml` — XcodeGen spec (committed; the single source of truth — defines
  both schemes + the `DebugHarness` config).
- `Sources/` — SwiftUI app. Shipping: `RootView` / `ConnectToControlView` /
  `PairedAppView` / `FilesView` / `SettingsView` / `ControlSession`. DEV_HARNESS
  only: `EpcPairingView`.
- `DriveIos.xcodeproj`, `Info.plist` — **generated, gitignored.** Regenerate with
  `xcodegen generate`.

## Prerequisites

- Xcode (tested on 26.x) + an iOS Simulator runtime.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`.

## Generate + build

```bash
# 1. Generate the Xcode project from project.yml
cd drive-ios && xcodegen generate

# 2. Nothing to assemble first: Drive links its own Kotlin framework,
#    `:tenshu-sdk-ios-drive`, by search path and name, and the build's pre-step
#    (`scripts/ios-build-sdk.sh drive`) links one slice of it fresh every build
#    (docs/proposals/ios-per-app-frameworks.md P3).

# 3. Build the shipping app (or DriveIos-Dev for the harness). A generic
#    simulator destination avoids device-name resolution drift.
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build build
```

## Run on the simulator

```bash
xcrun simctl boot 'iPhone 16' 2>/dev/null || true

# Shipping app → Connect to Control (no session provisioned yet).
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/DriveIos.app
xcrun simctl launch booted app.tenshu.drive.ios.smoke

# Dev harness, forwarded straight into Files (provisions the stub at launch).
xcrun simctl install booted build/Build/Products/DebugHarness-iphonesimulator/DriveIos.app
SIMCTL_CHILD_DRIVE_DEV_PROVISION_CONTROL=1 \
  xcrun simctl launch --console-pty booted app.tenshu.drive.ios.smoke
```

## UI tests (sharing cluster)

`DriveIosUITests` drives the real sharing-cluster SwiftUI in the simulator — the layer
the Kotlin `iosSimulatorArm64Test` (view models only) can't reach. Run via the
`DriveIos-UITests` scheme (builds the app in `DebugHarness`, provisioned into
`PairedAppView` by the `DRIVE_DEV_PROVISION_CONTROL` launch-env hook):

```bash
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos-UITests \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath build test
```

Screenshots are `.deleteOnSuccess` attachments in the `.xcresult` (never committed; kept
only on a failing run). Still fake-backed — no real Control/relay/camera (that's the
on-device pass).

`UploadConflictUITests` additionally drives the **photo picker**, which the sharing-cluster suite
cannot: the picker is an out-of-process remote view whose grid is invisible to XCUITest (only its
"Photos"/"Cancel" chrome shows up in the host tree), so the photo is chosen by a **coordinate tap**
— which pins that suite to the iPhone 16 geometry.

⚠⚠ It needs the simulator to have **at least one photo**, or the blind tap lands on an empty grid
and the assertions merely time out:

```bash
xcrun simctl addmedia booted /path/to/any.png
```

See [`docs/proposals/ios-proxy-architecture.md`](../docs/proposals/ios-proxy-architecture.md)
**§11.6** (decisions §11.1–§11.5) + §9.

## Not in scope here (later phases / deferred per §11.6)

- The real shared-Keychain provisioning (lands with **Control-on-iOS**, Phase 6e,
  replacing the dev stub) and a real Control to forward to.
- Real-device builds, a Developer Team ID, code signing, App Attest — **6c/6d**.
- A live EPC round-trip against a real proxy over the relay (the JVM
  `EpcRendezvousOverRelayTest` covers it; on-device is 6c+).
- Shared surface tab, background `URLSession` upload (O3-B), synced-folder
  auto-backup, the in-Drive QR **scanner** (future third-party path).
