# ios-onboarding

The shared **forward-to-Control** surface for first-party route-1 iOS apps — see
[ADR 0022](../docs/adr/0022-ios-forward-to-control-surface.md).

On iOS every first-party app runs its own in-process proxy and adopts Control's device
identity and realm keys from the shared Keychain. Without Control it has no realm, no
keys and nothing to show, so "Control is absent" is a product surface, not an error case.
This module owns that surface once.

## Contents

| File | Role |
|---|---|
| `Sources/ConnectToControlView.swift` | The **blocking** screen: *Set Up Tenshu Control*, with the deep-link → App Store routing. |
| `Sources/ControlNotConnectedBanner.swift` | The **non-blocking** bar: says why the screens are empty, and carries a *Set up* button that presents the screen above as a sheet. |
| `Sources/ControlSetUpGate.swift` | The **root gate**: the blocking screen until Control has provisioned the app, the app after (ADR 0022 §Decision 6). |
| `Sources/ControlLink.swift` | `Control.url` (the `tenshu-control://setup` scheme) + `Control.appStoreURL` (build-setting driven). |
| `onboarding.yml` | XcodeGen fragment — the `ControlOnboardingApp` target template. |

## Which one do I owe?

Two postures, one destination — decided by **provisioning**, never by the app
(ADR 0022 §Decision 6, which amends §Decision 4 + §Decision 5):

| State | Use | Today |
|---|---|---|
| Control **never provisioned** this app — no runtime, so nothing to read | `ControlSetUpGate` at the shipping root (the full-screen `ConnectToControlView`) | Drive · Photos · Calendar · Contacts · Maps · Timeline · Fit (neither of its two routes live) |
| **Provisioned**, runtime cannot reach Control | `ControlNotConnectedBanner`, above the app | the same apps |

⚠ A route-1 app has no cached content without its runtime, so a banner over an
unprovisioned app sits above screens that are empty by construction and reads as lost
data. A new route-1 app wraps its shipping root in `ControlSetUpGate`.

## Adding a consumer

Two lines in the app's `project.yml`, plus one Info.plist key:

```yaml
include:
  - path: ../ios-onboarding/onboarding.yml

targets:
  <App>:
    templates:
      - ControlOnboardingApp
    info:
      properties:
        TenshuControlAppStoreId: $(TENSHU_CONTROL_APP_STORE_ID)
```

Then render it where the app is genuinely dead without Control:

```swift
ConnectToControlView(
    appName: "Fit",
    purpose: "record and sync your workouts, end-to-end encrypted",
    onRecheck: { /* re-read provisioning */ }
)
```

…or, above a surface that still shows something, the banner:

```swift
if !MapsSurface.shared.controlReachable() {
    ControlNotConnectedBanner(
        appName: "Maps",
        purpose: "keep the places you save end-to-end encrypted and synced across your devices",
        onRecheck: onRecheck
    )
}
```

⚠ **Gate only a dead end, never a browsable surface.** Fit gates *Record* alone — its
other tabs stay readable over cached data behind the banner. Drive gates at the root
because without a realm it has no files at all. Timeline / Maps / Contacts take the banner
only: their surfaces still show cached content, and a wall would take that away. See
ADR 0022 §Decision 4 + §Decision 5.

⚠ **`appName` + `purpose` are caller-supplied on purpose.** The copy has to name what
*this* app loses; a generic sentence is true of nothing in particular.

## Gotchas

- ⚠ Sources are compiled **into each app target**, not vended as a Swift package — the
  same constraint `ios-basemap` documents. SwiftPM resolves a `.binaryTarget` before any
  build phase runs, so a package alongside the Gradle-built `TenshuSdk` framework fails
  on a fresh `-derivedDataPath`.
- ⚠ `Control.appStoreURL` is **nil until `TENSHU_CONTROL_APP_STORE_ID` is set**, and the
  screen renders an honest "not on the App Store yet" state. That is deliberate: the
  previous hardcoded `id000000000` opened a broken Store page that only a user would ever
  see.
- ⚠ The banner does **not** dismiss its own sheet. Each host already tears the tree down
  when provisioning arrives (Timeline/Maps remount on `liveToken`, Contacts on its
  reachability flow, Fit on `.liveSessionChanged`), taking the sheet with it. A
  banner-local check would be a second source of truth for a question the host answers.
- ⚠ `onRecheck` is only called by the `DEV_HARNESS` stub — the shipping path recovers
  through the host's `scenePhase` gate — but thread it anyway, or the simulator path
  (the only place these get exercised without a Control build) does nothing.
- ⚠ A `DEV_HARNESS`-only `devStub` must be passed as a **conditional constant**, not by
  `#if`-ing the call site — naming a harness-only symbol unconditionally fails the
  shipping build, and duplicating the initialiser across both branches is how copies
  drift. See `drive-ios/Sources/RootView.swift`.
