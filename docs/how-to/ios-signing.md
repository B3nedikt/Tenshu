# How-to: on-device signing for the iOS apps

> ⚠⚠ **The iOS apps are on the paid company team `752LWAZMKZ` since 2026-10-02**, with bundle ids
> `app.tenshu.<app>.ios`. This guide was written for the free personal team
> (`FAYT82GWD7`) and §0–§5 still read that way; the mechanics are unchanged, the limits are not.
> What differs today:
>
> - **Team.** `local.properties` still carries `tenshu.teamId=FAYT82GWD7`, which the **macOS**
>   desktop build is pinned to. For every iOS device build export `TENSHU_TEAM_ID=752LWAZMKZ` and
>   re-run `xcodegen generate` in the app's directory with it set (the team is substituted at
>   generate time), then build with `-allowProvisioningUpdates -allowProvisioningDeviceRegistration`.
>   ⚠⚠ **A device build without it registers the bundle id to the personal team**, and an app id
>   belongs to one team: that is how `app.tenshu.drive.ios` became unavailable to the company team
>   on 2026-10-02, which kept Drive from signing with the App Group (§6) until the id was
>   released and Drive signed under it on 2026-10-11.
> - **App Groups are available and declared** on device builds (§6). The free-tier gate below is
>   history.
> - **No 7-day expiry and no three-app cap.** Those were free-team limits.

Practical guide for taking the iOS surfaces from **simulator-only** (where the apps run
today — `drive-ios/` fully unsigned; `control-ios/` **ad-hoc-signed on the sim** for a
per-app Keychain, see the RT-5 note below) to a **free-tier signed build that runs on a
physical iPhone**. This is the cheapest rung of the signing
ladder in [`../proposals/ios-control-handover.md`](../proposals/ios-control-handover.md)
§C4d, and it unlocks everything de-riskable without paying Apple:

- the **cross-app Keychain handshake** — Control writing an item into a shared Keychain group
  and another first-party app reading it (Keychain Sharing is **free-tier**). Today that is the
  route-1 key material (`RealmKeyMaterialBridge`); the C3 `epc-session` presence token this
  guide was written against was retired 2026-11-05;
- a genuine **two-process check of `FileAdvisoryLock`** cross-process exclusion (the
  route-1 single-writer assumption the simulator spike could *not* prove — see §C4d:
  same-process `flock` doesn't serialise, so this needs two real processes).

The mirror to copy is the macOS machinery in
[`macos-signing.md`](macos-signing.md): the same free personal team (`FAYT82GWD7`), the
same `-Ptenshu.teamId` / `$TENSHU_TEAM_ID` / `local.properties` resolution, and
`@TEAM_ID@`-templated `.entitlements`.

There are three rungs; only one costs money:

| Tier | When you need it | Account |
|-------|------------------|---------|
| **Simulator, ad-hoc-signed** | Everything on the sim — UI, fakes, the in-process data plane (C4), **a real per-app Keychain** (genesis's `setUpUnlock` + the RT-5 durable realm-device probe), **and the C3 cross-app Keychain handshake** (shared `app.tenshu.shared` group — verified Control-writes → Drive-reads on the sim) | Free / no account |
| **Free, signed (`FAYT82GWD7`)** | An on-device smoke of C1/C2/C3 (real hardware), and the **two-process `FileAdvisoryLock`** check | Free Apple ID |
| **Paid ($99/yr)** | **iOS App Groups** (the shared-container transport for the route-1 replica, C4d/C4e) and **App Attest** | Apple Developer Program |

The hard gate is **App Groups** (`com.apple.security.application-groups`): unavailable on a
free personal team, exactly as on macOS. Keychain Sharing
(`keychain-access-groups`) is *not* gated — which is why the C3 handshake is fully
testable for free.

> **Simulator Keychain (RT-5, no account).** `control-ios/` now **ad-hoc-signs the simulator
> build** (`CODE_SIGN_IDENTITY = "-"`, `CODE_SIGN_ENTITLEMENTS = Control.entitlements` with a
> per-app `application-identifier`) so the app gets a working **per-app** Keychain on the sim —
> no Apple ID / Team ID, since ad-hoc signing is honoured by the simulator (a profile is only a
> real-device requirement). This was the gate that blocked genesis's `setUpUnlock` and the RT-5
> probe: a fully-unsigned build has *no* `application-identifier`, so every Keychain op fails
> with `errSecMissingEntitlement (-34018)`. The `SecurityFrameworkKeychain` backing
> `IosSecureStorageDelegate` sets no `kSecAttrAccessGroup`, so the **default** group (= the
> `application-identifier`) is all it needs — no shared group, no team prefix.

> **Simulator cross-app Keychain — C3 also works on the sim (no account).** Updated: the
> **shared** `app.tenshu.shared` group works on the simulator too, not only on a signed device.
> Both `control-ios` *and* `drive-ios` ad-hoc-sign with an entitlements file declaring the
> **bare** `app.tenshu.shared` group (no `$(AppIdentifierPrefix)` — ad-hoc has no team), matching
> the literal string `ControlSessionProvisioner` / `ControlSession.swift` pass as
> `kSecAttrAccessGroup`. Verified end-to-end with an A/B on a freshly-erased sim: with no session
> written, `drive-ios` shows *Connect to Control*; after `control-ios -provisionSession` writes the
> `epc-session` item into the shared group, `drive-ios` relaunches straight into **Files**
> (⚠ historical: `-provisionSession` and the presence token were deleted 2026-11-05 with the pre-route-1 co-resident EPC handoff.
> Today Drive routes on the route-1 key material, and **there is no tap-free way to publish it**:
> `-genesisAutoComplete` creates a realm but never calls `ensureDriveProvisioned`. Neither did
> `-provisionSession`. Its token stopped making Drive live at P1d, and the route ran no unlocked
> realm to publish keys from. The A/B today: onboard Control in its normal UI (genesis, join or
> recovery), or unlock it from `LockedView`, or cold-start it into an unlocked realm. Each of
> these calls `ensureCoResidentProvisioning` → `ensureDriveProvisioned`, which publishes the keys.
> Then launch `drive-ios`.) — i.e. it
> read Control's item across the app boundary, no `errSecMissingEntitlement` on either side. So the
> **C3 forward-to-Control handoff (§11.6) is functional + testable on the simulator with no Apple ID
> / Team ID / device.** ⚠ This is the *simulator* config (bare group + ad-hoc). A **real device**
> still needs the Team-prefixed `$(AppIdentifierPrefix)app.tenshu.shared` + both apps signed under
> one team (§2/§4); that rung is only required for genuine *on-device* (and App Attest, C5).

---

## 0. Prerequisites

- A physical iPhone running iOS 16+ (the deployment target), connected over USB and
  trusted (`Trust This Computer`).
- An Apple ID added to Xcode → **Settings → Accounts**. Xcode mints a **free personal
  team** automatically; note its Team ID (ours is `FAYT82GWD7`, the same one the macOS
  desktop build uses).
- The standard worktree toolchain: `xcodegen` and `local.properties`. The app's Kotlin framework
  needs no separate step: its Xcode prebuild links it (`scripts/ios-build-sdk.sh <app>`).

> ⚠ Free personal-team provisioning profiles **expire after 7 days** and are limited to
> 3 app IDs / 3 devices. Re-sign weekly. This is fine for a smoke pass, not for a beta.

---

## 1. Real bundle IDs (drop the `.smoke` suffix)

The simulator targets use throwaway IDs (`app.tenshu.control.ios.smoke`,
`app.tenshu.drive.ios.smoke`). A provisioning profile binds to a *real* app ID, and the two
apps must share a Keychain group, so give them stable IDs:

| App | Simulator (today) | Signed |
|-----|-------------------|--------|
| Control | `app.tenshu.control.ios.smoke` | `app.tenshu.control.ios` |
| Drive | `app.tenshu.drive.ios.smoke` | `app.tenshu.drive.ios` |

In `control-ios/project.yml` (and the Drive mirror) set `PRODUCT_BUNDLE_IDENTIFIER`
accordingly under the target's `settings.base`.

---

## 2. Signing fields + entitlements in `project.yml`

**Landed 2026-07-15 (as-built, all four iOS apps).** The device flip is in each
`project.yml` as **`[sdk=iphoneos*]`-conditional settings**, so every simulator build
(ad-hoc, bare groups, `.smoke` IDs where applicable) is byte-identical to before — the
device config only activates when building for a real iPhone:

```yaml
# all four project.ymls — settings.base (simulator lines unchanged above these)
CODE_SIGNING_REQUIRED[sdk=iphoneos*]: "YES"
CODE_SIGN_STYLE[sdk=iphoneos*]: Automatic
CODE_SIGN_IDENTITY[sdk=iphoneos*]: "Apple Development"
DEVELOPMENT_TEAM[sdk=iphoneos*]: ${TENSHU_TEAM_ID}   # env at `xcodegen generate` time
```

`${TENSHU_TEAM_ID}` is substituted by xcodegen **at generate time** — regenerate with
`TENSHU_TEAM_ID=$(grep tenshu.teamId local.properties | cut -d= -f2) xcodegen generate`
(the iOS mirror of the macOS `-Ptenshu.teamId` flow).

Drive + Control additionally gate, per target:

```yaml
PRODUCT_BUNDLE_IDENTIFIER[sdk=iphoneos*]: app.tenshu.drive.ios      # §1: profiles bind to real IDs
CODE_SIGN_ENTITLEMENTS[sdk=iphoneos*]: Drive.device.entitlements   # device variant, see below
TENSHU_SHARED_KEYCHAIN_GROUP: app.tenshu.shared                    # sim default (bare)
TENSHU_SHARED_KEYCHAIN_GROUP[sdk=iphoneos*]: $(DEVELOPMENT_TEAM).app.tenshu.shared
```

The **device entitlements variants** (`<App>.device.entitlements`, nine apps) carry the
Keychain groups and, since 2026-10-02, the App Group (§6). They carry no
`application-identifier` (the profile injects it). Until that date they held the Keychain groups
only, because a free team cannot provision an App Group:

```xml
<!-- control-ios/Control.device.entitlements -->
<key>keychain-access-groups</key>
<array>
  <string>$(AppIdentifierPrefix)app.tenshu.control.ios</string>
  <string>$(AppIdentifierPrefix)app.tenshu.shared</string>
</array>
<key>com.apple.security.application-groups</key>
<array>
  <string>group.app.tenshu.shared</string>
</array>
```

`$(AppIdentifierPrefix)` expands to `<TeamID>.` at signing time, so the effective Keychain group
is `<TeamID>.app.tenshu.shared` — identical in every app. The App Group id takes **no** prefix.

**How the code learns the prefixed group:** the group is no longer hardcoded. Both apps'
Info.plists carry `TenshuSharedKeychainGroup: $(TENSHU_SHARED_KEYCHAIN_GROUP)`;
`ControlSession.swift` (Drive) and the Kotlin `ControlSessionProvisioner.ACCESS_GROUP`
(Control's write side; `RealmKeyMaterialBridge` aliases it) read that key at runtime and
fall back to the bare group when it's absent/unexpanded (= the simulator config). So sim
builds keep passing the bare literal and device builds pass the Team-prefixed one, with no
compile-time fork.

> **Naming note (carried from §C4d):** the iOS Keychain/App-Group identifier is
> `app.tenshu.shared`, *not* the `group.org.tenshu` that `docs/protocol/ios-ipc.md` once
> sketched. The App Group is `group.`-prefixed (`group.app.tenshu.shared`); Keychain groups
> carry no such prefix requirement.

After editing, regenerate and the project picks up the signing config:

```bash
cd control-ios && xcodegen generate
```

---

## 3. Install on the device

> ⚠ The 2026-10-02 "not safe to install yet" hold is answered in code: since 2026-11-05 every build
> opens the shared replica on SQLite (S0b, [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md)
> B.22), and the old file store is deleted (B.22.9). Detail and what is still owed on a device:
> [`ios-background-scheduling.md`](../proposals/ios-background-scheduling.md) §4.1.1.
> ⚠⚠ **On a test phone, delete every Tenshu app before installing 1.0.1 or later, and never install
> a build older than 1.0.1 beside newer ones**: nothing removes an old file store's `objects/` any
> more ([`ios-drive-parity.md`](../proposals/ios-drive-parity.md) §19.1).

Find the device's UDID (`xcrun xctrace list devices`), then build+install with the team
threaded through (mirrors the macOS `-Ptenshu.teamId` flow):

```bash
(cd control-ios && TENSHU_TEAM_ID=752LWAZMKZ xcodegen generate)
xcodebuild -project control-ios/ControlIos.xcodeproj -scheme ControlIos \
  -configuration Debug \
  -destination 'platform=iOS,id=<DEVICE-UDID>' \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  build
```

`-allowProvisioningUpdates` lets Xcode mint/refresh the profile headlessly (and register the
App ID and the App Group with the team the first time, §6). To
launch, the simplest path is opening the generated `.xcodeproj` in Xcode once, selecting
the device, and pressing Run (the first run also walks you through **Settings → General →
VPN & Device Management → trust the developer** on the phone).

Do this for **both** `ControlIos` and `DriveIos` so they land on the same device with the
same Team ID — the precondition for the C3 handshake.

Once the device is set up, **`scripts/ios-smoke.sh`** is the repeatable gate: it launches
each installed app via `devicectl` and fails if the process dies within seconds of launch
(`--build` re-builds + re-installs first — also the weekly profile re-mint). It is the iOS
analogue of `scripts/android-smoke.sh`.

⚠⚠ **`--build-only` needs no device at all, and is the check to run on any iOS-touching
change.** It assembles the XCFramework and compiles every app shell against it
(`control drive fit timeline maps contacts home mia`), then stops — no signing, no install,
no launch. Run it *before* reaching for hardware: nothing else in the repo compiles Swift, so
a Swift/Kotlin API mismatch is invisible to `./gradlew desktopTest jvmTest test`, to the
Android smoke gate, and to CI. `contacts-ios` shipped and did not compile *once* until a later
fix — SKIE bridges a Kotlin sealed interface and a Kotlin enum quite differently from
the plain Obj-C export spelling the SwiftUI had guessed at, and no green tick anywhere said
so. The device path above cannot be that backstop: it needs a plugged-in iPhone, and the free
personal team fits only three of the eight apps on one at a time.

---

## 4. Verify the C3 cross-app Keychain handshake (free)

This is the payoff: prove Control → Drive cross-app session discovery end-to-end on a real
device, which the simulator can't (no shared entitlement enforcement, and the apps run in
isolation).

1. Launch **Control**, finish genesis onboarding (passphrase floor). On `Continue`,
   `ControlSurface.ensureDriveProvisioned()` publishes the route-1 key material into the
   `app.tenshu.shared` Keychain group (see `RealmKeyMaterialBridge`).
2. Launch **Drive**. Its first screen reads the same group; with the material present it should
   go straight into Files instead of *Connect to Control*.
3. Confirm the round-trip survives a cold start of Drive (Keychain is persistent), and that
   wiping Control's data (which deletes the item) flips Drive back to locked.

If step 2 fails, the usual cause is a Keychain-group **mismatch** — the two
`keychain-access-groups` strings must be byte-identical *after* `$(AppIdentifierPrefix)`
expansion, i.e. both signed with the same Team ID.

---

## 5. Verify `FileAdvisoryLock` cross-process exclusion (free)

The C4d simulator spike established the lock *API* works but could only prove serialisation
with an in-process coroutine `Mutex` — because, empirically, a blocking `flock(2)` between
two **threads of the same process** does **not** serialise (the advisory lock is bound to
the open file description, not the thread). Route-1's single-writer assumption rests on
**cross-process** exclusion, which needs two genuine processes.

The free tier is enough to settle it, because the two iOS apps *are* two processes sharing
one container path. Minimal harness:

1. Have both apps target the same lock file under the shared group container (or, pre-App
   Group, a path both can reach for the test — e.g. a file in a shared Keychain-gated
   location, or run the check as a 2-process desktop test which uses the same
   `core-common` `FileAdvisoryLock` actual).
2. In Control, acquire the lock and hold it for ~3 s while writing a marker.
3. In Drive, attempt to acquire the same lock during that window and record whether it
   **blocks** (correct: cross-process exclusion holds) or **proceeds** (route-1 is unsafe).
4. The correct outcome is: Drive's acquisition blocks until Control releases, then both
   markers are present and ordered — no interleave, no fork.

> If a desktop 2-process run is more convenient than two phones, it exercises the **same**
> `flock`-based actual (`core-common/.../db/FileAdvisoryLock.kt`) and is a valid proxy for
> the cross-process semantics; the on-device run additionally confirms iOS sandbox
> container reachability. Either satisfies the C4d "two-process check" gate.

---

## 6. The App Group on device builds

The route-1 replica lives in the App Group container (`group.app.tenshu.shared`):
`sharedReplicaDirectory` → `objects/`, `files/`, `upload-staging/`, `sync-hlc/` and the writer
lock. A free personal team cannot provision `com.apple.security.application-groups`, so until
2026-10-02 no device entitlements file declared it and **every device build silently ran on a
store in its own container** — N replicas under one device id.

**As of 2026-10-02** ([`ios-background-scheduling.md`](../proposals/ios-background-scheduling.md)
§4.1.1):

- ⚠⚠ Build, do not install: see the block at the top of §3.
- All nine `*.device.entitlements` declare the group. Mia has no entitlements yet.
- **Nothing to do in the developer portal for the group.** With automatic signing and
  `-allowProvisioningUpdates`, the first device build of an app registers its explicit App ID and
  the App Group with team `752LWAZMKZ` and mints a profile that carries it. (The team's wildcard
  profile, which most apps signed with before, cannot carry an App Group.) ⚠ Drive is the
  exception until its bundle id is free of the personal team, see the note at the top. Check a
  built bundle:

  ```bash
  codesign -d --entitlements - --xml <path>/ControlIos.app | plutil -p -
  ```

- **A device build that cannot resolve the container throws** at the first store open
  (`SharedReplicaUnavailableException`, printed first) instead of falling back. The app-local
  fallback remains on the simulator and the headless test host only.
- **No store migration.** A device resyncs once into the shared container. ⚠ Since 2026-11-05 no
  code removes an app-local replica a pre-App-Group build left behind (the reclaimer and
  `TENSHU_DISCARD_LEGACY_REPLICA` are deleted, `ios-shared-store-sqlite.md` B.22.9): delete the app
  instead.

**Not part of this:** App Attest (C5), and capabilities still commented out in a device
entitlements file (Timeline's `com.apple.developer.networking.wifi-info`).
