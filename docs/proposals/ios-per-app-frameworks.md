# iOS per-app Kotlin frameworks: each app links only its own code

**Status:** v0.9, 2026-10-17. Option A (one framework per app) is being built. **P0 measured**
(§10.1), **P1 built**, **P2 and P3 built up to their TestFlight uploads**, **P4 built** (§11, as
built; §10.2 to §10.4 have the measurements): every app links its own `:tenshu-sdk-ios-<app>`.
Control's archive loads none of the five sensitive frameworks; Drive's keeps only Photos (and
AVFoundation); of the other eight, only Fit (all four of HealthKit, CoreLocation, CoreBluetooth,
CoreMotion) and Timeline (CoreLocation) load any, measured on debug builds. **P5 built**: the
umbrella `:tenshu-sdk-ios` is deleted. Open: one TestFlight upload each of Control
and Drive with no ITMS-90683, and one upload each of the other eight, which since 2026-10-17 have `ReleaseApp` and a first archive (§10.5). Option B (move platform-API code out of shared Kotlin)
stays rejected as the general fix and is used for two cases (§3.3: D6, and LocalAuthentication in
P3).

**Relates to:** `tech-debt.md` *"Every iOS app links every app's Kotlin"* (merged with
the TestFlight archive pipeline, with `how-to/ios-testflight.md`,
`ios-release/release.yml`, `scripts/ios-archive.sh`, `scripts/ios-build-sdk.sh`);
[`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) (route 1, Decision C);
[`ios-route1-replica-scope.md`](ios-route1-replica-scope.md) §2, C1;
[`core-schema-evolution.md`](core-schema-evolution.md) §8.1 and House Rule 9;
[`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §7 (M12);
[`ios-scheduler-lease.md`](ios-scheduler-lease.md) §3 (`APP_LOCAL` / `REALM_WIDE`);
[`ios-fit-timeline-maps-parity.md`](ios-fit-timeline-maps-parity.md) D12 (ObjC name clashes).

**How to read the evidence.** *Measured*: observed on a binary, with the date. *Read*: taken from
the cited file at `7a48d996f` (master, 2026-10-14). *Inferred*: follows from what was read or
measured, not observed. *GK*: Kotlin/Native or Apple behaviour from general knowledge, unverified
here; each GK claim the design leans on has a measurement in §10.

---

## 1. Decisions

| # | Decision | Recommendation | Why, in one line |
|---|---|---|---|
| D1 | One framework per app (A), or move platform code out of shared Kotlin (B)? | **A** | Apple judges what is linked into each binary; A gets there mechanically, B needs Swift rewrites in three route-1 runtimes (§3). |
| D2 | One Gradle module with N `binaries.framework` blocks, or N modules? | **N modules**: `:tenshu-sdk-ios-core` plus one `:tenshu-sdk-ios-<app>` per app | A Gradle module has one dependency set; separate modules are the practical way to keep `:fit-shared-data` off Control's link classpath (§5.1). |
| D3 | Framework `baseName` per app (`TenshuDriveSdk`) or the same everywhere? | **`TenshuSdk` everywhere** | 136 Swift files say `import TenshuSdk`, and the shared Swift fragments (`ios-storage`, `ios-basemap`) compile into several apps under one import (§5.2). |
| D4 | Does an app's in-process proxy keep the full service graph? | **Yes, every app, no exceptions** until per-service cursors exist | A runtime that lacks a service drops its blocks and advances the shared cursor past them (§6). This is the main risk. |
| D5 | How does Xcode link the per-app framework? | **By search path and name, one slice** (the `ArchiveApp` shape from the TestFlight archive pipeline), in debug too | One mechanism for debug and release; halves the debug link (§8.1). |
| D6 | PhotoKit in the app-agnostic runtime | **Move the `phasset://` reader to Drive** (the one B-shaped change) | It is bound in `FirstPartyLocalRuntime`'s base module, so per-app exports alone would leave Photos.framework in every app (§3.3). |
| D7 | Proof of done | **An allowlist check on `otool -L` in `ios-archive.sh`**, plus one TestFlight upload per app with no ITMS-90683 | The ITMS mail is the symptom; the load commands are the cause and can be checked locally (§9.3). The check is an allowlist, not attribution: it cannot tell Kotlin's hints from the app's own Swift imports. |
| D8 | Pilot | **Control first**, then Drive, then the other eight | Control's upload already failed once and it is set up for archiving. Not because its module set is small: `:control-shared-logic` api-exposes nearly every service (§7, §11). |
| D9 | What every app framework exports | **`:tenshu-sdk-ios-core` plus the base export list, in every app**, from one shared convention script | Shared Swift (`ios-storage` in all ten apps; `EpcRelayPairing` in Drive, Fit, Timeline) names core types, and `export` is not transitive (§5.2). So core's whole public surface is in every framework, and core must hold no app's code (§5.3). |

---

## 2. The problem

Measured on 2026-10-14 (Control's archive, `otool -L`): Control's binary has load commands for
HealthKit, CoreLocation, CoreBluetooth, CoreMotion and Photos. Control uses none of them.

App Store Connect answered the first upload with ITMS-90683 (from the maintainer's copy of the mail)
for exactly five keys: `NSHealthShareUsageDescription` and `NSHealthUpdateUsageDescription`
(errors), and `NSLocationAlwaysAndWhenInUseUsageDescription`, `NSLocationWhenInUseUsageDescription`
and `NSBluetoothAlwaysUsageDescription` (warnings). Photos and CoreMotion were linked but **not**
flagged.

The TestFlight archive pipeline's stopgap gives every archived app generic purpose strings ("this app does not use …") in
`ios-release/release.yml`. That gets the upload through. It does not change what is linked.

Other measured facts from the same session:

- Control's and Drive's executables are about 46 MB each.
- Measured afterwards (review, 2026-10-14, the debug framework of §2.1): the archive also contains
  the Compose runtime and Skia (`libskia` members, `CMP*` classes, undefined
  `__Konan_init_androidx.compose.runtime:*` symbols). Which exported or linked module brings them in
  is not identified. This contradicts the intent recorded in `build.gradle.kts` (no Compose on iOS)
  and is part of measurement M3.
- One release slice of `TenshuSdk` links in about 5 minutes at `-Xmx10g`; both slices at `-Xmx5g`
  ran out of heap (`scripts/ios-build-sdk.sh`).
- Every app's privacy manifest audit (`how-to/ios-testflight.md` §4) sees the shared framework's
  API use, not the app's.

Read: `tenshu-sdk-ios/build.gradle.kts` exports 38 modules into one static framework, including
every app's `-shared-logic`, and api-depends on `:fit-shared-data`, `:timeline-shared-data`,
`:maps-shared-data`, `:contacts-shared-data` and `:control-shared-proxy`. All ten app projects
(`*-ios/project.yml`) link `TenshuSdk.xcframework`.

### 2.1 How the load commands get into the app

Measured (2026-10-14, the debug `iosArm64` framework in the primary checkout's build directory,
built 11:28 that day): the static `TenshuSdk` archive has 4,133 members. The Kotlin program is one
of them, `TenshuSdk.framework.o`, and that object carries `LC_LINKER_OPTION` entries for
`-framework HealthKit`, `CoreLocation`, `CoreBluetooth`, `CoreMotion`, `Photos`, `AVFoundation`,
`CoreML` and others. The linker honours those auto-link hints for every object it loads, and it
always loads this one, because it holds all the Kotlin code. So every platform framework that the
Kotlin program uses ends up in every app.

Measured: the auto-linked set matches the platform packages imported somewhere in the repo's Kotlin,
plus their own dependencies (`CoreText`, `Metal`, `QuartzCore`, `_LocationEssentials`). It does
**not** contain platform libraries nobody imports (no `EventKit`, `Contacts`, `MapKit`). So K/N
emits a hint for a platform library it considers *used*, not for the whole SDK.

Not established: whether "used" is decided **after** dead-code elimination, or by whether any linked
klib depends on the platform library. This matters for D2 and is measurement M1 (§10).

⚠ The generated header imports only Foundation, and the module map declares `use Foundation` and
`use UserNotifications`. Swift's clang autolinking is therefore not the path (measured, same build).

⚠ `inference-harness-ios/project.yml` says *"a static Kotlin/Native framework carries no link
instructions of its own"*. For `TenshuSdk` that is false, as measured above. The harness may build
differently; reconcile the comment when this lands.

### 2.2 Where the sensitive frameworks come from

Read, by `import platform.<X>` in Kotlin:

| Framework | Kotlin source | Reached which apps' binaries at P0 (since P4: see §10.4) |
|---|---|---|
| HealthKit | `fit-shared-data` iosMain: `HealthKitImporter`, `IosHealthKitWorkoutWriter`, `IosRecordingEngine` | all ten |
| CoreBluetooth, CoreMotion | `fit-shared-data` iosMain: `IosRecordingEngine` | all ten |
| CoreLocation | `fit-shared-data` (`IosRecordingEngine`), `timeline-shared-data` iosMain (`IosLocationCapture`, `IosReverseGeocoder`) | all ten |
| Photos | `tenshu-sdk-ios` iosMain: `IosPhotoLibrary`, `IosMediaSourceProvider`, `IosMediaAccessProvider` | all ten |
| AVFoundation | `file-service-proxy` iosMain: `IosGetPreviewImplementation` (video posters) | all ten, and stays so (§3.3) |
| CoreML | `inference-service-proxy` iosMain | all ten, and stays so (§3.3) |
| LocalAuthentication | `control-shared-proxy` iosMain: `IosBiometricUnlockDelegate`, bound by `PlatformProxyModule` in every route-1 graph until P3; since P3 bound in Control's graph only | all ten until P3; since P3 Control only (§3.3) |

Measured (§2.1's object): the auto-link hints also include `PDFKit`, `UserNotifications`, `Network`
and `NetworkExtension`. None has an ITMS-90683 key for the way it is used here; they are listed so
the per-app allowlist (D7) starts from the complete set.

Read: no `-proxy` module imports HealthKit, CoreLocation, CoreBluetooth, CoreMotion or Photos. Those
come only from the two `-shared-data` modules (HealthKit, CoreLocation, CoreBluetooth, CoreMotion)
and from the SDK's own iosMain (Photos).

---

## 3. Options

### 3.1 A: one framework per app (recommended)

Each app links a framework built from the modules that app needs. Fit's framework contains
`:fit-shared-data` and therefore HealthKit; Control's framework does not contain it, so Control's
binary has no hint to link HealthKit.

For A:

- Apple judges each binary. A makes each binary contain its own app's code and the shared runtime,
  which is what review and the privacy audit assume.
- It is mechanical: Gradle module boundaries and export lists. No behaviour moves between Kotlin and
  Swift, and no route-1 runtime changes what it constructs (except D6).
- It is the common KMP shape for several apps: one umbrella per app.
- The next sensitive API added to one app's shared logic affects that app only.
- D12's cross-app ObjC name clashes (`Photo` → `Photo_`) stop being possible, because two apps'
  modules never share a framework again.

Against A:

- More link work (§8): N release links instead of one.
- The SDK's own iosMain must be split into modules (§5.3), which is a large file move.
- Each app's export list must be complete on its own. Today an app can get a type it needs from
  another app's export by accident (§5.2).

### 3.2 B: one framework, platform code moved into the app (rejected as the general fix)

Keep `TenshuSdk`, and move every HealthKit, CoreLocation, CoreBluetooth and CoreMotion call out of
`fit-shared-data` and `timeline-shared-data` into Swift in `fit-ios` and `timeline-ios`, behind
interfaces the Swift side injects.

Rejected because:

- It is more Swift: `HealthKitImporter`, `IosHealthKitWorkoutWriter`, `IosRecordingEngine`,
  `IosLocationCapture` and `IosReverseGeocoder` would be rewritten, with their tests, and the JVM-
  and iOS-tested Kotlin would become untested Swift.
- It touches the route-1 runtimes (`FitLocalRuntime`, `TimelineLocalRuntime`,
  `TimelineCaptureRuntime`), which construct these pieces today.
- It does not fix size, the build-wide ObjC name space (D12), or the privacy-manifest superset for
  anything that is not a platform API.
- Every future sensitive API needs the same treatment, so the rule becomes "no platform API in
  shared Kotlin", which the repo does not otherwise follow.

### 3.3 Where B is still the right tool

B is right when a module that **several apps need** itself uses a sensitive framework. Per-app
frameworks cannot remove that, because every app that needs the module links it.

Today there is one such case: **PhotoKit in the app-agnostic runtime** (D6). Read:
`FirstPartyLocalRuntime.assembleBase` binds `single<IosUploadSourceReader> { IosUploadSource }` in
the base module every app uses, and `IosUploadSource` calls `IosPhotoLibrary` for `phasset://`
sources. So every app's program references PhotoKit. Inferred fix: split the reader. A `file://`
reader stays in the core module as the base binding; the `phasset://`-capable reader moves to Drive's
module and Drive installs it through `appOverrides`. Read: the only Kotlin caller of the source-file
upload path on iOS is Drive's `IosFilesRepositoryPlatformDelegate`; confirm no other app uploads a
`phasset://` source before moving it. Control is unaffected: it does not assemble through
`assembleBase` (§6.1) and binds no upload reader.

Two more are shared on purpose and stay, and a third was moved in P3:

- **AVFoundation** (`file-service-proxy`, video posters) and **CoreML** (`inference-service-proxy`)
  come from `-proxy` modules that every in-process proxy graph contains (D4). Neither has an
  ITMS-90683 purpose-string key for this use. Inferred: they cost no review question. If that
  changes, B applies to them, not A.
- **LocalAuthentication** comes from `IosBiometricUnlockDelegate` in `:control-shared-proxy`, which
  `PlatformProxyModule` bound in every route-1 graph. Read: only `control-ios` declares
  `NSFaceIDUsageDescription`. Without that string iOS skips Face ID and falls back to the device
  passcode; it does not crash. **Settled in P3 (§11, read): no non-Control app can reach the
  biometric unlock path.** The binding was a lazy Koin `single` that only Control's view models
  (`LockedViewModel`, `NewUserViewModel`, through `ControlSurface.installRealRuntime`) resolve; no
  other app's Kotlin and no other app's Swift asks for it, and no other app's Swift uses `LAContext`.
  So P3 took the B-shaped answer: the binding moved into Control's graph
  (`ControlSurface.runtimeModules`), and LocalAuthentication left Drive's binary with it. Still open:
  whether App Store Connect flags an upload for the missing string at all (the maintainer reads it
  off the processing mail of Drive build 202610151433, which was uploaded on the umbrella).
- If a later module needed by several apps adds HealthKit or location calls, B is the answer for
  that module.

---

## 4. What per-app frameworks remove, and what must be measured

Inferred, if K/N decides "used" per program (§2.1):

| App | Sensitive frameworks expected after the change |
|---|---|
| Control | none of HealthKit, CoreLocation, CoreBluetooth, CoreMotion, Photos |
| Drive | Photos |
| Fit | HealthKit, CoreLocation, CoreBluetooth, CoreMotion |
| Timeline | CoreLocation |
| Maps, Photos, Home, Mia, Calendar, Contacts | none of the five |

Every app additionally keeps the shared set from §2.2 and §3.3: AVFoundation, CoreML,
PDFKit, UserNotifications, Network, NetworkExtension (and, until P3, LocalAuthentication). Each app's allowlist
(D7) is its row above plus that set plus whatever its own Swift imports (Drive's Swift imports Photos
and AVFoundation, Fit's imports CoreLocation; read). The allowlist cannot tell which side brought a
framework in; it only says the set did not grow.

Two entries deserve a note:

- **Photos (the app)** links no PhotoKit once D6 lands. Read: `photos-ios/project.yml` says PIO-1
  never touches PhotoKit, and `PhotosLocalRuntime` does not reference `IosPhotoLibrary`.
- **Timeline** loses HealthKit. Read: `timeline-shared-data` depends on `:health-service*` (the
  synced store), not on `:fit-shared-data`, and `timeline-ios/Timeline.entitlements` has no
  HealthKit.

What must be measured rather than assumed (M1–M3 in §10):

- Whether a platform library used by a klib that is **in the dependency graph but unreferenced**
  still gets an auto-link hint. D2 makes this mostly moot, because Control's graph will not contain
  `:fit-shared-data` at all. It still matters for modules every app links (`:control-shared-proxy`
  pulls in all twenty services' `-proxy` modules). ⚠ Measure it on a **release** link: §2.1's
  measurement was a debug framework, which K/N builds from per-library caches, and the release
  whole-program link may behave differently.
- `otool -L` on each archived binary against the allowlist above.
- Executable size, and what it is made of. ⚠ Do not assume 46 MB shrinks to a small fraction, and do
  not assume why. The shared proxy graph (`:control-shared-proxy` and every `-proxy`, `-common`,
  `-client` module) stays in every app (D4), but the archive also contains the Compose runtime and
  Skia from an unidentified module (§2). Which of the two dominates is unproven; M3 measures it.
  Size is a side benefit, not the goal.

---

## 5. Gradle shape

### 5.1 Modules

Recommended (D2):

- **`:tenshu-sdk-ios-core`**: an ordinary KMP library, no framework binary, no SKIE. It holds the
  app-agnostic iosMain (§5.3) and api-depends on what every app needs: `:core`, `:core-common`,
  `:core-client`, `:app-error-handling`, `:app-reachability`, the `-common`/`-client` halves that the
  shared surfaces name, `:epc-rendezvous-client` and `:control-shared-proxy` (unexported, as today).
- **`:tenshu-sdk-ios-region`**: the region stager `IosRegionSupport.stager` and its
  `:map-region-app` dependency, for Fit, Timeline and Maps.
- **`:tenshu-sdk-ios-<app>`**, one per app (`control`, `drive`, `fit`, `timeline`, `maps`, `photos`,
  `home`, `mia`, `calendar`, `contacts`): the app's surfaces and runtimes from today's iosMain, its
  unexported data modules, and its `binaries.framework` block with that app's `export(...)` list and
  SKIE.

Why not one module with ten framework blocks: all binaries of a Gradle module share that module's
dependency set and its own iosMain. The module's own code is always part of the framework's API.
So `FitSurface` would stay a root in Control's framework, `:fit-shared-data` would stay in Control's
program, and HealthKit would stay linked. Separate compilations inside one module (custom
compilations per binary) could avoid that, but that is unusual KGP configuration for the same
result. Separate modules are the practical way to make the absence structural rather than dependent
on dead-code elimination.

Module names are a suggestion; a `<app>-sdk-ios` spelling works as well.

### 5.2 Framework name, exports, SKIE

**`baseName = "TenshuSdk"` in every app module (D3).** Read: 136 Swift files contain
`import TenshuSdk`. The shared XcodeGen fragments are compiled into each app's target: `ios-storage`
(Swift naming `RealmStorageSurface`, `SyncActivitySurface`) goes into all ten apps, and
`ios-basemap` (five files importing `TenshuSdk`) into Fit, Timeline and Maps. With one module name
those fragments compile unchanged against whichever app's framework is linked. Per-app names would
need conditional imports in shared Swift. Each app links exactly one framework, so the shared name
never clashes.

**Core is exported by every app framework (D9).** Read: `ios-storage` Swift, compiled into all ten
apps, names `RealmStorageSurface` and `SyncActivitySurface` (e.g. `SyncActivityCapsule.swift`), and
Drive's, Fit's and Timeline's Swift name `EpcRelayPairing`. Those types live in
`:tenshu-sdk-ios-core`, so every `:tenshu-sdk-ios-<app>` declares
`export(project(":tenshu-sdk-ios-core"))`. Because `export` is not transitive, each app must also
export the modules whose types core's surfaces expose: the base list in §7. Two consequences:

- Core's whole public surface is in every framework and is a dead-code root in every app. Anything
  in core that references one app's code drags that app into all ten. This is why §5.3's cross-app
  edges must be cut, not merely tolerated.
- The core export and the base export list belong in the same shared convention script as SKIE, so
  no app module can forget one of them.

**Exports.** `export(...)` is not transitive (the existing comments in `build.gradle.kts` cover
`:preference-service`, `:app-list-state`, `:calendar-service-common` and `:inference-service`). The
trap that is new with A: an app can lose a type it reads today **because another app exported it**.
Example, read: the `:app-list-state` export is justified by Fit (`ListLoadPhase`, `ListLoadState`)
and also serves Drive's `FilesRepository`. After the split both Fit and Drive must list it. The
failure is loud: without the export, Swift sees a prefixed name with no members and the app's Swift
does not compile. Only `scripts/ios-smoke.sh --build-only <app>` compiles Swift, so each phase runs
it for every app it touches.

**SKIE** is applied in each `:tenshu-sdk-ios-<app>` module, and `:tenshu-sdk-ios-core` does not
apply it. ⚠ The `skie { analytics { enabled.set(false) } }` block is a privacy requirement and must
be in every module that applies SKIE. Put the plugin and that block in one shared build-logic
script or convention plugin, so a module cannot apply SKIE without it. The Kotlin ⨯ SKIE version pin
(`tech-debt.md`) is unchanged; it now covers ten modules instead of one.

**D12 and `scripts/check-objc-clashes.py`.** Cross-app clashes go away. Clashes between an app's own
modules and the core exports remain possible, so the script runs once per app framework's export
set. The existing `@ObjCName` annotations (`PhotosPhoto`, Contacts' `ContactsRepository`, and the
others) stay: removing one renames a Swift symbol. ⚠ Read: `:app-error-handling` is an `api`
dependency of the umbrella today, **not** an export. The base list in §7 exports it (Swift reads
`ErrorCopyKey` through `ErrorCopySurface`); exporting it adds names to every framework, so run
`check-objc-clashes.py` when it is added.

### 5.3 Splitting the SDK's own iosMain

Read, by non-comment references between files in `tenshu-sdk-ios/src/iosMain`. The code is already
partitioned by app. Three code edges cross apps:

- `IosRegionSupport` holds the shared stager plus `fitResolver` and `timelineResolver`. The stager
  goes to `:tenshu-sdk-ios-region`; each resolver goes to its app's module.
- `IosUploadSource` (bound by the core runtime) calls `IosPhotoLibrary`. D6 resolves it (§3.3).
- `EpcRelayPairing.Channel.activate()` (`EpcRelayPairing.kt:48`) calls `LiveClientSession.activate`,
  and `LiveClientSession` (lines 39 and 46) calls `DriveReadSurface.installRealClient` /
  `installFakes`. `EpcRelayPairing` is shared (Drive, Fit and Timeline Swift name it), but
  `LiveClientSession` is Drive's: its only Swift callers are `drive-ios` `RootView.swift:70` and
  `EpcPairingView.swift` (read). Left in core it would put Drive's surfaces, and with them PhotoKit,
  into every app (D9). Fix: `LiveClientSession` moves to the Drive module, and `Channel.activate()`
  becomes a Drive-module extension function. Fit and Timeline already activate a channel through
  their own `FitLiveClientSession` / `TimelineLiveClientSession`.

Every other cross-app mention is in KDoc: `DriveLocalRuntime` in seven runtimes, `ControlSurface` in
`TimelineSurface` and `DriveLocalRuntime`, `EpcFitClient` in `EpcTimelineClient`.

Proposed assignment:

| Module | Files |
|---|---|
| core | `TenshuSdk`, `FirstPartyLocalRuntime`, `RealmStorageSurface`, `SyncActivitySurface`, `ErrorCopySurface`, `OpenTargetDecoder`, `OpenTargetRouting`, `IosOpenTargetCaller`, `InProcessFileUploader`, `IosUploadSource` (the `file://` half), `EpcRelayPairing` (without `Channel.activate()`), `DevRelayOverride`, `AppGroupProbe`, `RelayAuthProbe`, `RelayBlockProbe`, `RelayFolderProbe` (these five deleted 2026-11-05, with `RealmCryptoProbe`), `CryptoSmoke`, `RealmCryptoProbe` |
| region | the stager from `IosRegionResolvers` |
| control | `ControlSurface`, `ControlEventRow`, `ControlPendingRequestRow`, `ControlShareRows`, `ControlHostPairingApproval`, `IosEpcPairingController`, `IosPendingRequestNotifier`, `NotConnectedControlRepositories`, `FakeControlRepositories`, `ServicesStatusModel` |
| drive | `Drive*Surface` (8) and their smokes, `DriveLocalRuntime`, `EpcDriveClient` + smoke, `Background*` (5), `BackupScopeSelection`, `IosBackupEnvironment`, `IosMediaAccessProvider`, `IosMediaSourceProvider`, `IosPhotoLibrary`, the `phasset://` reader, `IosContentStreamSession`, `IosFilesRepositoryPlatformDelegate`, `LiveClientSession` and the `Channel.activate()` extension, `NewFolderPromptBridge`, `Fake{Files,Membership,Contacts}Repository`, `EpcLoopback` + smoke, `EpcConsumeSmoke`, `RendezvousSmoke`, `EpcRelayPairingSmoke` |
| fit | `FitSurface`, `FitLocalRuntime`, `FitLiveClientSession`, `EpcFitClient`, `NotConnectedFitRepository`, `fitResolver` |
| timeline | `TimelineSurface`, `TimelineLocalRuntime`, `TimelineCaptureRuntime`, `TimelineLiveClientSession`, `EpcTimelineClient`, `NotConnectedTimelineRepository`, `timelineResolver` |
| maps | `MapsSurface`, `MapsLocalRuntime`, `NotConnectedMapsRepositories` |
| photos | `PhotosSurface`, `PhotosLocalRuntime`, `PhotosModelsBinding`, `PhotosModelsFixture`, `PhotosScreenshotFixture`, `ModelTransferBridge`, `ModelTransferLedger` |
| home / mia / calendar / contacts | `<App>Surface`, `<App>LocalRuntime` |

Smokes and probes that are Drive's or Control's today are placed by their current caller. A smoke
used by two apps goes to core.

The 45 test files in `tenshu-sdk-ios/src/iosTest` move with the code they test. `PhotosOwnedInferenceStoresTest`
asserts over graphs of several apps, and `SyncActivitySharedReplicaTest` reads across runtimes; both
belong in a test-only module that depends on the app modules involved, or in core with test
dependencies on those app modules.

---

## 6. The shared runtime: what must stay identical across apps

This is the main risk. Route-1 apps share one App Group replica and the shared Keychain items. Two
apps from the same commit but with different Kotlin in them must still agree on everything the
store and the sync cursor depend on.

### 6.1 What decides the declared service versions

Read:

- The nine non-Control route-1 runtimes assemble their in-process proxy through
  `FirstPartyLocalRuntime.assembleBase`, which loads `buildProxyModule()` and
  `buildPlatformProxyModule()` from `:control-shared-proxy`.
- Control does **not** go through `assembleBase`: `ControlSurface.installRealRuntime` builds
  `koinApplication { modules(proxyModule, platformProxyModule) }` directly (`ControlSurface.kt`
  around line 283; its own comment says so). It loads the same two modules, so it declares the same
  services today, by a second code path.
- The schemas the store sees come from `coreSchemaConfigurations()` in that module, plus
  `platformSchemaConfigs`. The iOS `PlatformProxyModule` binds no `platformSchemaConfigs` (read: no
  match in `control-shared-proxy/src/iosMain`).
- `StoreSchemaMigrator.migrateStoreAtOpen` walks the **declared** services. A recorded version
  above the declared one wipes **all rows** of the store (`wipeAllRows`, not scoped to the service).
  A service that is recorded but not declared is skipped. A service that is declared but not
  recorded is stamped.

So the declared vector is a function of `:control-shared-proxy`, not of the export list, on both
code paths. A per-app
framework that keeps `:control-shared-proxy` (via the core module) declares the same services at the
same versions as every other app built from the same commit. Per-app exports by themselves change
nothing here. This is the invariant to protect.

### 6.2 What would break it: a per-app service set

The tempting next step after this proposal is to slim each app's proxy graph to the services the app
uses, because the proxy graph is a large part of every binary. **D4 forbids that** until cursors
are kept per service. Read:

- `SchemaPullFence`'s KDoc (`sync-service-proxy`, lines 45–46): blocks for types this runtime has no
  schema for are dropped as unparseable by the ordinary ingest path.
- Cursors are not per service. ⚠ `ios-route1-replica-scope.md` C1 describes one per-device cursor
  advanced by `advancedCursor`; that function is gone (`SyncScheduler.kt`, the note around line 3013:
  deleted in `sync-lane-cursor-split.md` L1), and the cursors are now per lane over a merged log. The
  hazard is unchanged by that: a lane's cursor still moves past a block whatever happened to the
  block's rows at ingest.
- So if one app's runtime had no Fit schema and pulled first, Fit's rows would not reach the shared
  replica, and no later pull from that cursor would fetch them again. The relay still holds them;
  recovery is a cursor reset and a full re-pull.

Note the difference from the wipe hazard: a missing service does not wipe (the migrator skips
recorded-but-undeclared slots). It loses rows at ingest, silently.

Guard (proposed): a test **in each `:tenshu-sdk-ios-<app>` module** that assembles that app's
runtime the way the app does (Control through `installRealRuntime`'s graph, the others through their
`<App>LocalRuntime`) and asserts that the declared service versions equal a **committed, pinned
list** of service ids and versions, shared by all ten tests. A service bump updates the pinned list
once, in the same PR (House Rule 9). ⚠ Comparing against `coreSchemaConfigurations()` instead would
be a test that cannot fail: the base runtime's declared versions are that list by construction
(`ProxyModule.kt`, the `coreSchemaConfigs` single). Add a Gradle-level check that every
`:tenshu-sdk-ios-<app>` module depends on `:tenshu-sdk-ios-core`. Both are cheap; the first fails if
any app's runtime declares a different service set.

### 6.3 Version lockstep between installed apps

The hazard "one service slot behind wipes the whole store" (measured 2026-09-16 on the iPhone,
recorded as F′ in `core-version-compatibility.md` §1.6) applies whenever any installed app is behind
the store on any slot. It exists today: iOS updates apps one at a time, and TestFlight lets a tester
install one app's new build without the others.

What changes with A, inferred:

- Nothing in the mechanism. One commit still yields one declared vector in every app.
- Partial releases get cheaper. Today `ios-archive.sh` links one framework and archives several
  apps from it. With A, archiving only Control saves the other links. A release habit of "upload only
  what changed" makes skew between installed apps more likely, not less.

Proposed mitigations, none of which needs this proposal to land first:

- `ios-archive.sh` writes the declared service vector of the commit into each archive's Info.plist
  (a key such as `TenshuServiceVersions`), computed once per run from a JVM task over
  `coreSchemaConfigurations()`.
- The release runbook states the rule: when the vector changed since the last upload, upload
  **every** first-party app at the new commit, not only the one that changed.
- The real fix is on the device: `ios-shared-store-sqlite.md` §7 (M12) refuses a newer store
  instead of wiping it. That removes the data loss whatever the release habit is.

### 6.4 Other shared state

Read or inferred, all unchanged by A:

- **Keychain and App Group formats** (`RealmKeyMaterialBridge`, `ControlSessionProvisioner`, the
  `epc-session` item) are written by `:core-common` iosMain, which every framework contains.
- **No persisted data is keyed by an ObjC or Kotlin class name.** Read: no `NSKeyedArchiver`,
  `NSSecureCoding` or `NSStringFromClass` in the iOS Swift, the SDK iosMain or `core-common` iosMain.
  Since the module name stays `TenshuSdk` (D3), generated ObjC class names would not change anyway.
- **One K/N runtime per process.** Each app links one framework, as today. No app has an extension
  target that links a second one (read: the only extra targets are two UI-test bundles).
- **Jobs that any app may run.** `ios-scheduler-lease.md` (draft, not built) defines `REALM_WIDE`
  jobs that run in whichever app holds the lease. With A, a realm-wide job's code must live in the
  core module or the shared proxy graph; code in an app module can only back an `APP_LOCAL` job.
  Today the distinction is a convention; with A the linker enforces it.
- **Security posture.** Every app still holds realm keys in-process and runs the full proxy graph
  (Decision C, threat model A4). A smaller framework is not a security boundary. No threat-model
  amendment is needed: no asset, key path or wire shape changes.

---

## 7. Per-app module sets

Derived from (a) which SDK surfaces each app's Swift names (read: `<app>-ios/Sources` and the
fragments its `project.yml` includes), and (b) what those surfaces and the exported view-model
modules reference. "Exported" are the modules Swift reads types from; "linked" are unexported
dependencies the app's surfaces need. Everything in **Base** goes into every app.

**Base (all ten), through `:tenshu-sdk-ios-core`.** Exported by every app framework (D9), from the
shared convention script: `:tenshu-sdk-ios-core` itself, `:core`, `:core-common`, `:core-client`,
`:sync-service-common`/`-client` (`SyncActivitySurface`), `:storage-service-common`/`-client`
(`RealmStorageSurface`), `:epc-rendezvous-client` (`EpcRelayPairing`), `:app-error-handling`
(`ErrorCopySurface`; an `api` dependency today, newly exported, see §5.2). Linked:
`:control-shared-proxy` and with it `:control-shared-logic`, every service's `-common`/`-client`/
`-proxy` (D4), `:epc-rendezvous-client`, Ktor Darwin.

| App | Swift reaches | Exported, beyond base | Linked, beyond base | Sensitive frameworks, beyond the shared set (§4) |
|---|---|---|---|---|
| Control | `ControlSurface`, probes | `:control-shared-logic`, `:inference-service`, `:identity-service-common`/`-client`, `:contact-service-common`/`-client`, `:notification-service-common`/`-client`, `:preference-service*`, `:logging-service-common`/`-client`, `:core-paging` (inferred: `:control-shared-logic` api-exposes nearly every service, so this list is long, not short) | none | none |
| Drive | 8 `Drive*Surface`s, `DriveLocalRuntime`, `LiveClientSession`, smokes | `:drive-shared-logic`, `:core-paging`, `:app-list-state`, `:file-*`, `:ingest-*`, `:identity-*`, `:contact-*`, `:notification-*`, `:preference-*` | none | Photos |
| Fit | `FitSurface`, `FitLocalRuntime`, `ControlSessionProvisioner` (core-common) | `:fit-shared-logic`, `:app-list-state`, `:preference-service`, `:basemap-core` | `:fit-shared-data`, `:health-service-common`/`-client`, region | HealthKit, CoreLocation, CoreBluetooth, CoreMotion |
| Timeline | `TimelineSurface`, `TimelineLocalRuntime`, `TimelineCaptureRuntime` | `:timeline-shared-logic`, `:preference-service`, `:basemap-core` | `:timeline-shared-data`, `:timeline-service-*`, `:place-service-*`, `:health-service-*`, region | CoreLocation |
| Maps | `MapsSurface`, `MapsLocalRuntime` | `:maps-shared-logic`, `:basemap-core` | `:maps-shared-data`, region | none |
| Photos | `PhotosSurface`, `PhotosLocalRuntime`, `ModelTransferBridge`, `SyncActivitySurface` | `:photos-shared-logic`, `:control-shared-logic` (Models view model), `:inference-service`, `:core-paging` | none | none (after D6) |
| Home | `HomeSurface`, `HomeLocalRuntime` | `:home-shared-logic` | none | none |
| Mia | `MiaSurface`, `MiaLocalRuntime` | `:mia-shared-logic`, `:preference-service` | none | none |
| Calendar | `CalendarSurface`, `CalendarLocalRuntime` | `:calendar-shared-logic`, `:calendar-service-common` | `:calendar-service`, `:calendar-service-client` | none |
| Contacts | `ContactsSurface`, `ContactsLocalRuntime` | `:contacts-shared-logic` | `:contacts-shared-data` | none |

The "exported" column is the minimum the comments in today's `build.gradle.kts` justify, plus what
each view-model module api-exposes. ⚠ It is inferred, and §5.2's failure mode applies: the per-app
Swift compile in `ios-smoke.sh --build-only <app>` is what proves each row.

As built (P2 to P4), every row compiled its app's Swift as written, with two corrections: **Mia**
needs no `:preference-service` export (its Swift compiled without it), and **Calendar** also links
`:app-ingest` unexported (`CalendarDeviceIdentity` is an `app.tenshu.ingest.DeviceIdentity`, which
`:calendar-shared-logic` holds as `implementation`; on the umbrella it arrived through
`:drive-shared-logic`). Maps additionally links `kotlinx-datetime` (`MapsSurface.today()`). The
sensitive-framework column held for every app (§10.4).

`:inference-harness-ios` already has its own framework (`In3mHarnessKit`) and is unaffected.

---

## 8. Build cost

### 8.1 Debug, one app

Today each app's prebuild runs `:tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework`, which links
**both** slices of the whole umbrella. With D5, the prebuild links one slice of one app's framework:
`:tenshu-sdk-ios-<app>:linkDebugFramework<Target>`, chosen from `PLATFORM_NAME`, the way
`ios-build-sdk.sh` already picks the release target.

Inferred: a debug build of one app costs about the same as today or less. It loses one slice, and
it gains a smaller program. GK: K/N debug builds for iOS use per-library caches, so most of the
shared graph is not recompiled per framework; measure (M4).

### 8.2 CI and `ios-smoke.sh --build-only`

Today: one XCFramework assembly (two slices), then ten Xcode builds against it. With A: ten
simulator-slice debug links, then ten Xcode builds.

Inferred: the Gradle half grows by roughly ten single-slice debug links minus one two-slice link.
With debug caches, each per-app link is mostly the final link of the shared graph; the number is
unknown until M4. Mitigations:

- `ios-smoke.sh --build-only <app>` links only the frameworks of the named apps. The default (all
  ten) is for iOS-touching PRs that change the core module.
- Gradle can run the link tasks in parallel. The limit is heap, not CPU; take the worker count from
  M4, not from a guess.
- `.github/workflows/ios.yml` replaces `:tenshu-sdk-ios:assembleTenshuSdkXCFramework` with the
  per-app simulator links. Read: that workflow does not currently run (Actions billing, per its own
  header), so the local `ios-smoke.sh` remains the gate.

`iosTestCheck` gets one test binary per module instead of one for `tenshu-sdk-ios`. Each is a debug
link of that module's graph. This is extra cost, of the same kind as above.

### 8.3 Release archives

Measured: one release slice of the umbrella takes about 5 minutes at `-Xmx10g`. With A, each app
archived needs one release link of its own framework.

Inferred: each per-app release link is somewhat cheaper than the umbrella link, but not by much,
because the shared proxy graph dominates (D4). Assume 60 to 90 % of today's time per app until M4
says otherwise:

| Archive run | Today | With A (inferred) |
|---|---|---|
| Control only | ~5 min | ~3 to 4.5 min |
| Control + Drive (the two apps set up for archiving) | ~5 min | ~6 to 9 min |
| All ten | ~5 min | ~30 to 45 min |

Mitigations:

- Archive only the apps being uploaded; `ios-archive.sh` already takes the app list.
- Run release links **sequentially** with the 10 GB daemon from `ios-build-sdk.sh`. Two at once
  would need about 20 GB of heap; do not parallelise without measuring.
- §6.3 asks for every app to be uploaded when the service vector changes. That run is the 30 to 45
  minute case. It happens on schema bumps, not on every release.
- The heap floor for one per-app link is unknown, as it is for the umbrella today (`tech-debt.md`
  *"The iOS release build links a framework no test has run"*). Measure it once in M4 and set
  `-Xmx` from that.

---

## 9. Effect on existing pieces

### 9.1 Xcode projects

Each `*-ios/project.yml`:

- drops the `framework: ../tenshu-sdk-ios/build/XCFrameworks/debug/TenshuSdk.xcframework`
  dependency;
- adds `FRAMEWORK_SEARCH_PATHS` to
  `$(SRCROOT)/../tenshu-sdk-ios-<app>/build/bin/$(TENSHU_SDK_TARGET)/$(TENSHU_SDK_VARIANT)Framework`
  and `OTHER_LDFLAGS: -framework TenshuSdk`, which is the `ArchiveApp` mechanism of the TestFlight archive pipeline;
- runs `scripts/ios-build-sdk.sh <app>` as its prebuild, in place of the inline
  `./gradlew :tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework`.

With D5 there is one mechanism for debug and release, so `ArchiveApp` reduces to setting
`TENSHU_SDK_VARIANT: release`.

As built: `AppFramework` lives in `ios-release/release.yml`, which all ten apps include; since
2026-10-17 all ten apply `ReleaseApp` too (§10.5). Read: the comment there explains that the second spec
(`project.archive.yml`) exists because XcodeGen cannot vary a `framework:` dependency by
configuration. Without the `framework:` dependency, inferred, a configuration-level setting can
replace the second spec. Verify before deleting `project.archive.yml`.

### 9.2 Scripts and Gradle

- **`scripts/ios-build-sdk.sh`** takes the app name (or reads it from the Xcode target) and links
  `:tenshu-sdk-ios-<app>`. The release branch keeps its single slice and its `-Xmx10g`.
- **`scripts/ios-archive.sh`** links each named app's release framework before archiving it.
  Its check that the log names `bin/iosArm64/releaseFramework` becomes a per-app path check.
- **`scripts/ios-smoke.sh`** links the frameworks of the apps it builds (§8.2).
- **`iosKotlinCheck`** (root `build.gradle.kts`) depends on `:tenshu-sdk-ios:compileKotlinIosSimulatorArm64`
  today. It changes to depend on every `:tenshu-sdk-ios-*` module's compile task. As built (P4): it
  names all twelve, and still the umbrella's until P5; `iosTestCheck` names the ten app modules and
  core, and no longer the umbrella, which has no tests left. ⚠ Read: its
  comment already notes that it reaches only what the SDK graph depends on; that stays true, now over
  ten graphs.
- **`scripts/check-objc-clashes.py`** runs per app framework (§5.2).
- **`:tenshu-sdk-ios`** is removed once no project links it. Keep it buildable until the last app
  has moved, so the phases can land one app at a time.

### 9.3 Purpose strings and the ITMS proof

`ios-release/release.yml`'s generic purpose strings are deleted, per app, as each app moves. An app
that uses a permission keeps its own string in its `project.yml`, as Fit, Timeline and Drive already
do (read).

The proof has two parts:

1. **Local, every archive (D7).** `ios-archive.sh` runs `otool -L` on the archived executable and
   compares the sensitive frameworks found against a per-app allowlist (§4). A framework not on the
   list fails the archive. This makes "Control links HealthKit" a local failure instead of an App
   Store Connect email. ⚠ It is an allowlist, not attribution: `otool -L` cannot say whether a
   framework came from Kotlin's hints or from the app's own Swift (Drive's Swift imports Photos and
   AVFoundation, Fit's imports CoreLocation). It proves only that an app's set did not grow.
2. **Remote, once per app.** One TestFlight upload with the generic strings removed and no
   ITMS-90683 in the processing result.

### 9.4 Privacy manifests

With A, the required-reason audit in `how-to/ios-testflight.md` §4 can be done per app: the
accessed-API list of an app's `PrivacyInfo.xcprivacy` is the union of its Swift and its own
framework, not of all ten apps. Redo the audit for each app as it moves. ⚠ Inferred: the shared core
(the proxy graph and `core-common`) still accounts for most required-reason API use (file timestamps,
`UserDefaults`, disk space), so most entries will not change.

---

## 10. Measurements

| # | What | How | Decides |
|---|---|---|---|
| M1 | Does K/N emit an auto-link hint for a platform library that is in the graph but unreferenced? | Build Control's **release** framework from P2 with and without a dummy unreferenced dependency on `:fit-shared-data`; `otool -l` on `TenshuSdk.framework.o` for `LC_LINKER_OPTION`. Release, because §2.1's measurement was a debug framework built from per-library caches | Whether modules every app links must be kept free of sensitive imports, or only referenced code must be |
| M2 | Load commands of each archived app | `otool -L` on the archive's executable | The §4 table and the allowlist (D7) |
| M3 | Executable size per app, and what it is made of | `size` / file size of the archived executable; symbol sizes by module prefix; find which module brings in the Compose runtime and Skia (§2) | Whether size is worth stating as a benefit, and whether Compose/Skia removal is a separate, larger win |
| M4 | Link time and heap per app framework, debug and release | `--profile` on the link task, `-Xmx` lowered until it fails | §8 numbers, the worker count, the release `-Xmx` |
| M5 | Does the archive build without `project.archive.yml`? | Archive Control with a configuration-level `TENSHU_SDK_VARIANT` | Whether the second spec can go (§9.1) |

### 10.1 P0 baseline (measured 2026-10-14, the umbrella at `895f6d96c`'s SDK code)

The baseline every later phase compares against. One release slice of the umbrella, and Control's
archive from the TestFlight archive pipeline (built from `114768e59`, whose SDK code is the same).

- **Release `TenshuSdk.framework.o`** (`iosArm64`, the static archive's Kotlin program; 2,988 archive
  members, 511 of them `libskia*`): `LC_LINKER_OPTION` names `AVFoundation`, `CoreBluetooth`,
  `CoreFoundation`, `CoreGraphics`, `CoreLocation`, `CoreML`, `CoreMedia`, `CoreMotion`, `CoreText`,
  `Foundation`, `HealthKit`, `ImageIO`, `LocalAuthentication`, `Metal`, `Network`,
  `NetworkExtension`, `PDFKit`, `Photos`, `Security`, `UIKit`, `UserNotifications`,
  `_LocationEssentials`, plus `-lz -lc++ -lbz2 -lcompression -lresolv -late -ldl -lobjc -lSystem`.
  Same set as §2.1's debug framework, minus `QuartzCore`. So the release whole-program link also
  emits a hint for all five sensitive frameworks.
- **Control's archived executable** (`otool -L`): `Foundation`, `AVFoundation`, `BackgroundTasks`,
  `Combine`, `CoreBluetooth`, `CoreFoundation`, `CoreGraphics`, `CoreImage`, `CoreLocation`,
  `CoreML`, `CoreMedia`, `CoreMotion`, `HealthKit`, `ImageIO`, `LocalAuthentication`, `Network`,
  `NetworkExtension`, `PDFKit`, `Photos`, `Security`, `SwiftUI`, `UIKit`, `UserNotifications`,
  `libobjc`, `libSystem`, `libc++`, `libz` and 20 Swift runtime dylibs. All five sensitive
  frameworks are present. Executable 47,396,064 bytes (`__TEXT` 36.8 MB, `__text` 33.1 MB,
  `__DATA_CONST` 8.2 MB).
- **Release link of the umbrella** (`:tenshu-sdk-ios:linkReleaseFrameworkIosArm64`, `--profile`,
  daemon at `-Xmx10g`, machine load 24–47 on 14 cores): **7 min 35 s**. Daemon heap from its GC
  log: peak occupancy 7.5 GB before a collection, 7.2 GB live after one, 8.0 GB committed, no full
  GC. The heap peaks in the first ~2 minutes (the Kotlin IR phases); the remaining ~5 minutes show
  no GC at all, which fits LLVM code generation running off-heap. So `-Xmx10g` has ~2.5 GB of
  headroom, and §2's "about 5 minutes" was an unloaded machine.

---

### 10.2 P2 results: Control on its own framework (measured 2026-10-14)

| # | Result |
|---|---|
| M1 | **Hints follow the program after dead-code elimination, not the klib graph.** Control's release framework, linked once with an unreferenced `implementation(project(":fit-shared-data"))` added: `LC_LINKER_OPTION` identical to the link without it — no HealthKit, CoreLocation, CoreBluetooth, CoreMotion. Corroborated by the normal link: `:control-shared-proxy` (every service's `-proxy`, `inference-service-proxy`'s CoreML included) is in Control's graph, yet its release object has no hint for CoreML, ImageIO, Network or NetworkExtension. So R6 does not hold in release: a sensitive import in a module every app links costs only the apps whose program reaches it. ⚠ Debug frameworks (per-library caches) were not re-measured for this. |
| M2 | Control's archived executable (`otool -L`): `Foundation`, `AVFoundation`, `BackgroundTasks`, `Combine`, `CoreFoundation`, `CoreGraphics`, `CoreImage`, `CoreMedia`, `LocalAuthentication`, `PDFKit`, `Security`, `SwiftUI`, `UIKit`, `UserNotifications`, `libobjc`, `libSystem`, `libc++` and the Swift runtime. **Gone since P0: HealthKit, CoreLocation, CoreBluetooth, CoreMotion, Photos** — and CoreML, ImageIO, Network, NetworkExtension, `libz`. Release object `LC_LINKER_OPTION`: `AVFoundation CoreFoundation CoreGraphics CoreMedia CoreText Foundation LocalAuthentication Metal PDFKit Security UIKit UserNotifications`. ⚠ `libswiftCoreLocation.dylib` is still in the list, as at P0: a Swift runtime overlay, not the CoreLocation framework; whether App Store Connect reads anything into it is what the upload answers. Allowlist (`ios-archive.sh`): `AVFoundation LocalAuthentication`. |
| M3 | Executable **31,268,224 bytes** (P0: 47,396,064, −34 %); `__TEXT` 23.9 MB (36.8), `__text` 21.5 MB (33.1), `__DATA_CONST` 5.8 MB (8.2). The Kotlin program object is 85.6 MB (P0 umbrella: 130.9 MB). **Compose and Skia are not in either executable**: §2's finding was about the static archive's members. They arrive through `:core-common` → `koin-compose` → Compose `foundation`/`ui` → `skiko`, so all 511 `libskia*` members are still in every framework archive, but no linked object references them and the linker loads none (no Compose class and no undefined Skia symbol in the Kotlin object; no Skia string in either executable). Dropping `koin-compose` from `:core-common`'s iOS graph would shrink the framework archive and its link, not the app. |
| M4 | Release link, `iosArm64`, `-Xmx10g`: **Control 3 min 29 s, daemon heap peak 6.0 GB** (5.9 GB live after GC, 6.3 GB committed), against the umbrella's 7 min 35 s and 7.5 GB at P0 — both under machine load (P0 24–47, P2 19–65 on 14 cores), so the ratio is indicative only. Debug simulator slice, one link with `--rerun` (caches warm, load 10–18): **Control 40 s, the umbrella 62 s**. Today's everyday umbrella prebuild links two slices, so one app's debug prebuild drops from two umbrella links to one smaller one (§8.1). The heap floor (lowering `-Xmx` until it fails) was not measured. |
| M5 | **Yes.** Control archives from its one `project.yml`: `AppFramework` sets `TENSHU_SDK_VARIANT: release` for the Release configuration, the prebuild links the release framework, the archive log names `tenshu-sdk-ios-control/build/bin/iosArm64/releaseFramework`, and no `ProcessXCFramework` step runs. `control-ios/project.archive.yml` is deleted. |

### 10.3 P3 results: Drive on its own framework (measured 2026-10-15)

Before: Drive build `202610151433`, archived on the umbrella at `ba42315c0` (the build uploaded
that day). After: this change on the same base. Machine load 4–17 on 14 cores throughout, so link
times are indicative.

| # | Result |
|---|---|
| M2 | Drive's archived executable (`otool -L`): `Foundation`, `AVFoundation`, `BackgroundTasks`, `Combine`, `CoreFoundation`, `CoreGraphics`, `CoreImage`, `CoreMedia`, `CoreTransferable`, `Network`, `PDFKit`, `Photos`, `Security`, `SwiftUI`, `UIKit`, `UserNotifications`, `_AVKit_SwiftUI`, `_PhotosUI_SwiftUI`, `libobjc`, `libSystem`, `libc++` and the Swift runtime. **Gone since the umbrella build: HealthKit, CoreLocation, CoreBluetooth, CoreMotion and LocalAuthentication**, and CoreML, ImageIO, NetworkExtension, `libz`. Release object `LC_LINKER_OPTION`: `AVFoundation CoreFoundation CoreGraphics CoreMedia CoreText Foundation Metal Network PDFKit Photos Security UIKit` (the umbrella's at the same base also named `CoreBluetooth CoreLocation CoreML CoreMotion HealthKit ImageIO LocalAuthentication NetworkExtension UserNotifications _LocationEssentials`). `Network` stays, unlike Control: Drive's program reaches it. ⚠ `libswiftCoreLocation.dylib` is still listed, as for Control: a Swift overlay, not the framework. Allowlist (`ios-archive.sh`): `AVFoundation Photos`. |
| M3 | Executable **30,884,176 bytes** (umbrella build: 47,298,192, −35 %); `__TEXT` 23.6 MB (36.7), `__text` 21.3 MB (33.0), `__DATA_CONST` 5.8 MB (8.2). Exported `.ipa` 18.9 MB (28.2). Release framework archive 154.6 MB (umbrella 207.1 MB), the Kotlin program object in it 84.7 MB (130.4 MB); both archives have 2,988 members, the Skia ones included, as M3 found for Control. |
| M4 | Release link, `iosArm64`, `-Xmx10g`: **Drive 4 min 5 s**, against 5 min 38 s for the umbrella's at the same base (that day's umbrella archive). Debug simulator slice, one link with `--rerun`: **57 s** (Control 40 s, umbrella 62 s at P2, different load). Heap not measured. |
| M5 | Drive archives from its one `project.yml`; `drive-ios/project.archive.yml` is deleted, and with it the last user of `ArchiveApp`. |

### 10.4 P4 results: the other eight (measured 2026-10-15, debug simulator builds)

None of the eight has an archive setup, so these are **debug** builds from `ios-smoke.sh
--build-only` (`otool -L` on `<App>.debug.dylib`, which holds the program in a debug build;
purpose strings from the built `Info.plist`). ⚠ They stand in for M2 until each app's first archive:
a release link drops what dead-code elimination removes, so the archive's set can only be the same
or smaller (M1), never larger.

| App | Sensitive frameworks loaded | Purpose strings declared | Mismatch |
|---|---|---|---|
| Calendar | AVFoundation | none | none |
| Contacts | AVFoundation | none | none (on the umbrella, same day: HealthKit, CoreLocation, CoreBluetooth, CoreMotion with no string for any) |
| Home | AVFoundation | none | none |
| Mia | AVFoundation | none | none |
| Maps | AVFoundation | none | none in the executable; ⚠ the embedded `MapLibre.framework` links CoreLocation (below) |
| Photos | AVFoundation | none | none (CoreML and ImageIO for on-device inference; no PhotoKit, as §4 expected) |
| Timeline | CoreLocation, AVFoundation | `NSLocationWhenInUse…`, `NSLocationAlwaysAndWhenInUse…`, `NSCamera…` | ⚠ `NSCamera…` without a camera in the shipping build (below) |
| Fit | HealthKit, CoreLocation, CoreBluetooth, CoreMotion, AVFoundation | `NSHealthShare…`, `NSHealthUpdate…`, `NSLocationWhenInUse…`, `NSMotion…`, `NSBluetoothAlways…`, `NSCamera…` | ⚠ `NSCamera…` as Timeline |

- **AVFoundation** is in all eight: `file-service-proxy`'s video posters, in every in-process graph
  (§3.3). It reads assets and captures nothing, so it owes no `NSCameraUsageDescription`; the
  allowlist (D7) will list it for every app.
- **The camera string in Fit and Timeline** was for `QrScannerView`, which both compile only under
  `DEV_HARNESS`. **Resolved 2026-10-17:** no shipping code path in either app (Swift or Kotlin)
  touches the camera, so both `NSCameraUsageDescription`s are deleted, and the harness scanner
  checks for the key and shows its paste-instead message without it.
- **Fit declares no `NSLocationAlwaysAndWhenInUseUsageDescription`**, by design (its recording uses
  when-in-use plus the `location` background mode). On the umbrella, Fit's program also carried
  Timeline's `requestAlwaysAuthorization` call; read from the debug frameworks, Fit's has no
  `requestAlwaysAuthorization` and Timeline's does. So P4 is what makes Fit's set of strings match
  its code.
- **MapLibre** (`ios-basemap`, embedded as a dynamic framework in Fit, Timeline and Maps) links
  CoreLocation in its own binary. The D7 check reads only the app's executable. Fit and Timeline
  declare location strings anyway; Maps declares none. **Resolved 2026-10-17 (§10.5):** MapLibre's
  binary references `requestWhenInUseAuthorization` and `requestAlwaysAuthorization`, and Maps uses
  no location at all (`showsUserLocation = false`, no `CLLocationManager`), so Maps declares both
  location keys with a string saying exactly that, and Fit gains the `Always` key on the same ground.
  The link cannot be avoided from the app: MapLibre is a prebuilt SPM binary. Whether App Store
  Connect accepts the wording is the upload's to answer.
- **LocalAuthentication** is in none of the eight (moved to Control's graph in P3), and none
  declares `NSFaceIDUsageDescription`.
- **ObjC names.** `check-objc-clashes.py --framework` for each of the eight lists the same three
  clashes, all inside the base exports (`CoreCommonModuleKt`, `Get`, `NativeReferenceDataDto`), all
  among the umbrella's 15. A clash that the umbrella resolved with `_` between two apps (Fit's and
  Timeline's `DistanceForm`, Fit's and Mia's `OnboardingViewModel`) is gone, and every app's Swift
  compiled against the new names.

### 10.5 First archives of the other eight (measured 2026-10-17, release)

`scripts/ios-archive.sh <app>` for each, one at a time, on `a23cd9378` plus this change. Every
archive passed the script's checks (bundle id, build number, privacy manifest in the bundle, release
framework named, no debug framework, D7 allowlist), and every export produced a signed App Store
`.ipa` (automatic signing; Calendar's first export failed with a transient *"No Accounts"* and passed
on a rerun). Nothing was uploaded. So M2 is measured for all eight; the upload half of D7 is not.

| App | Sensitive frameworks (`otool -L`) | Other notable links | Executable |
|---|---|---|---|
| Calendar | AVFoundation | UserNotifications | 27.1 MB |
| Contacts | AVFoundation | | 26.1 MB |
| Home | AVFoundation | | 26.4 MB |
| Mia | AVFoundation | | 25.6 MB |
| Photos | AVFoundation | CoreML, ImageIO | 35.5 MB |
| Maps | AVFoundation | MapKit, embedded MapLibre (which links CoreLocation) | 26.3 MB |
| Timeline | AVFoundation, CoreLocation | MapKit, MapLibre, NetworkExtension | 27.8 MB |
| Fit | AVFoundation, CoreBluetooth, CoreLocation, CoreMotion, HealthKit | MapKit, MapLibre, BackgroundTasks | 29.9 MB |

The debug sets of §10.4 held exactly: release dead-code elimination removed nothing sensitive and
added nothing. Required-reason APIs seen in the executables (`nm -u`, `strings`): every app has
`stat`/`fstat`/`lstat`, `NSFileModificationDate`, `statfs`/`fstatfs` and `NSFileSystemFreeSize`
(the shared core); `NSUserDefaults` only in Maps, Timeline, Fit (the basemap renderer election, and
Timeline's opt-ins) and Mia (`@AppStorage`); `systemUptime` only in Photos and Fit. So Photos'
earlier manifest over-declared UserDefaults (it was Timeline's, linked into the umbrella) and now
does not. Each app's `PrivacyInfo.xcprivacy` is exactly that set.

## 11. Phasing

**P0: measure, no code change.** Record `LC_LINKER_OPTION` and `otool -L` for the current release
framework and Control's archive (baseline for M1 to M3). Record the umbrella's release link time and
heap.

**P1: extract the core, still one framework.** Create `:tenshu-sdk-ios-core` and
`:tenshu-sdk-ios-region`, move the app-agnostic files (§5.3), split `IosUploadSource` (D6), and add
the §6.2 guard tests. `:tenshu-sdk-ios` depends on core and keeps its exports, so every app still
links one umbrella. Gate: `iosTestCheck`, `ios-smoke.sh --build-only` (all ten). No behaviour
change, so it can land on its own.

**P1 as built (2026-10-14).**

- **Modules.** `:tenshu-sdk-ios-core` holds the §5.3 core files, unchanged apart from visibility, and
  `IosFileUploadSource` (renamed from `IosUploadSource`). `:tenshu-sdk-ios-region` holds
  `IosRegionSupport` (stager, terminus, the centroid maths); Fit's and Timeline's adapters are
  `FitRegionResolver.kt` / `TimelineRegionResolver.kt` in the umbrella, ready to move with their
  apps. Core depends on no `*-shared-logic` / `*-shared-data` module, so an app type no longer
  compiles there: that is the structural guard against a fourth edge.
- **The three edges.** D6: the base binds the `file://`-only `IosFileUploadSource`, which refuses a
  `phasset://` source by name; Drive binds `IosPhotoUploadSource` over it in `DriveLocalRuntime`'s
  `appOverrides`, and `IosFilesRepositoryPlatformDelegate` hashes through it. No other Kotlin caller
  hands an upload a `phasset://` source (read). `Channel.activate()` is an extension in
  `LiveClientSession.kt`; Swift's `pairedChannel?.activate()` still compiles (category method).
- **Visibility.** Five `internal` declarations that app code calls across the new boundary
  (`devRelayOverride`, `devRelayOverrideModule`, `IosOpenTargetCaller.open`,
  `EpcRelayPairing.realCrypto`, the upload readers) became public **and** `@HiddenFromObjC`, so the
  Swift surface is unchanged.
- **⚠ Deviation from §5.2: no script plugin.** The convention (SKIE with analytics off,
  `TenshuSdk`, static, the base exports, the pinned-list test sources) is a block in the root
  `build.gradle.kts`, applied **by module name** to `:tenshu-sdk-ios` and every
  `:tenshu-sdk-ios-<app>`. A `gradle/*.gradle.kts` script plugin was tried and cannot work here: an
  applied script compiles against its own classpath and does not see the Kotlin or SKIE plugin
  classes. Applying by name also replaces §6.2's proposed "every app module depends on core" Gradle
  check: a module so named cannot opt out. SKIE is now on the root classpath (`apply false`).
- **The guard (§6.2).** `PinnedServiceVersions` (`tenshu-sdk-ios-core/src/serviceVersionPin`, compiled
  into every app framework's iOS tests) pins `TENSHU_STORAGE_FORMAT_VERSION` 22 and 25 services.
  `DeclaredServiceVersionsTest` assembles all ten graphs the way each app does — Control from
  `ControlSurface.runtimeModules()` (extracted from `installRealRuntime`, same modules), Calendar
  through `CalendarLocalRuntime.assembleBase` with a silent presenter (the real one aborts a
  bundle-less test host), the others through `<App>LocalRuntime.assemble` — and compares each with
  the pin. All ten declared the same 25 services. ⚠ Proved able to fail: with Home's graph
  temporarily missing the two Calendar schema modules, exactly `home` went red with
  *"tenshu/calendar/service: pinned v5, NOT DECLARED by Home"*, the other nine stayed green.
  ⚠ The guard runs in `iosTestCheck` (macOS), not in `run-gate.sh`: a JVM-only service bump turns
  it red until the pin is updated.
- **Exports.** `:app-error-handling` and `:tenshu-sdk-ios-core` are now exported (D9).
  `scripts/check-objc-clashes.py` takes `--framework` and reads the base list from the root build:
  the umbrella's clash list is the same 15 as on master, none involving the two new exports.

**P2: Control pilot.** Create `:tenshu-sdk-ios-control` with Control's files and export set. Switch
`control-ios/project.yml` to it (D5). Remove the generic purpose strings from Control. Add the
`otool -L` allowlist check to `ios-archive.sh` for Control. Gate: `ios-smoke.sh --build-only
control`, the archive check passes, and **one TestFlight upload of Control with no ITMS-90683**.
Measure M1 to M5 here.

**P2 as built (2026-10-14).**

- **`:tenshu-sdk-ios-control`** holds the ten §5.3 Control files and their four tests, plus
  `ControlDeclaredServiceVersionsTest` (Control's half of the §6.2 guard, against the same pin).
  It exports the base list (root build) plus `:control-shared-logic`, `:core-paging`,
  `:inference-service` and the `identity`/`contact`/`notification`/`preference`/`logging` halves —
  §7's row, which compiled Control's Swift on the first try (`ios-smoke.sh --build-only control`).
  The umbrella no longer contains Control's files and still exports `:control-shared-logic` for
  Photos.
- **Xcode (D5).** `ios-release/release.yml` gains `AppFramework`: a search-path-and-name link of
  `$(TENSHU_SDK_MODULE)/build/bin/$(TENSHU_SDK_TARGET)/$(TENSHU_SDK_VARIANT)Framework`, one slice,
  debug by default and release in the Release configuration. Control applies it; its prebuild runs
  `scripts/ios-build-sdk.sh control`, which now takes the app name. `project.archive.yml` is gone
  (M5).
- **Purpose strings: a template split, not a removal.** XcodeGen merges a template's `info`
  properties into the target and lets the target override a value, but offers no way to *remove* a
  key a template sets. So the generic strings moved out of `ReleaseApp` into a separate
  `UmbrellaPurposeStrings` template, which Drive (still on the umbrella) applies and Control does
  not. Control's Info.plist now has only `NSCameraUsageDescription` and `NSFaceIDUsageDescription`.
  `UmbrellaPurposeStrings` is deleted in P5.
- **The D7 check.** `ios-archive.sh` runs `otool -L` on each archived executable and fails on any
  privacy-sensitive system framework not on the app's `allowed_sensitive_for` list (Control:
  AVFoundation, LocalAuthentication; Drive: today's umbrella set until P3). Verified both ways: run
  against P0's Control archive it reports HealthKit, CoreLocation, CoreBluetooth, CoreMotion and
  Photos as unexpected; the P2 archive passes. It also links each app's release framework before
  archiving (sequentially), and checks the archive log names the app's own framework path.
- **`ios-smoke.sh`** assembles the umbrella XCFramework only when an app still on it is in the run.
- ⚠ **Stale derived data.** The first incremental build of Control after the switch, in a derived
  data that had built the umbrella version, left the old umbrella link in `ControlIos.debug.dylib`
  (Fit's and Drive's classes, HealthKit and the rest). The next build relinked correctly, and a fresh
  derived data never showed it. Xcode does relink when the framework file changes (checked). Clean
  an app's derived data once when it switches; the archive script always uses a fresh one.
- **Not done here: the TestFlight upload** (`scripts/ios-archive.sh --upload control`), which the
  maintainer runs. The export step could not run in this environment either: `xcodebuild
  -exportArchive` reported no App Store Connect account and no distribution certificate. That is the
  same failure as the TestFlight archive pipeline's own worktree here, not caused by this change. Everything before export
  passed: bundle id, build number, privacy manifest, the release framework path and the allowlist.

**P3: Drive.** Same steps. Drive keeps Photos; the check proves it has none of the other four.
Also settle LocalAuthentication (§3.3): whether Drive's upload, which has no
`NSFaceIDUsageDescription`, is flagged for it, and whether any non-Control app can reach the
biometric unlock path. The answers decide whether the delegate binding moves into Control's graph.

**P3 as built (2026-10-15).**

- **`:tenshu-sdk-ios-drive`** holds the 36 Drive files of §5.3 (the table's list, with
  `IosPhotoUploadSource` from P1 and `BackgroundUploadStaging`) and 19 of the umbrella's tests, plus
  `DriveDeclaredServiceVersionsTest` (Drive's half of the §6.2 guard, against the same pin; it also
  keeps P1's D6 assertion that Drive binds the `phasset://` reader). It exports the base list plus
  §7's Drive row — `:drive-shared-logic`, `:core-paging`, `:app-list-state`, and the
  `file`/`ingest`/`identity`/`contact`/`notification`/`preference` halves — which compiled Drive's
  Swift on the first try. No `logging` or `inference` export was needed. `ios-smoke.sh
  --build-only` passes for Drive, Control and Photos (an umbrella app). `check-objc-clashes.py
  --framework :tenshu-sdk-ios-drive`: 4 clashes, all inside Drive's own export set and all among
  the umbrella's 15 (`DrivePreferences`, `Get`, `CoreCommonModuleKt`, `NativeReferenceDataDto`), so
  no Swift name changes.
- **Umbrella.** It no longer contains Drive's files. Its export list is unchanged, so no app still on
  it loses a type. Its iOS tests depend on `:tenshu-sdk-ios-drive` (test only), because
  `PhotosOwnedInferenceStoresTest` and `PhotosOnDeviceAiTest` assemble Drive's runtime beside
  Photos' (§5.3's "test dependencies on those app modules"). For that, `DriveLocalRuntime.Assembly.koin`
  became public and `@HiddenFromObjC`, P1's treatment; `PhotosModelDownloadTest` got its own copy of
  the `internal` file-write helper it borrowed from Drive.
- **LocalAuthentication (§3.3) — moved.** Read: the iOS binding was a lazy `single` in
  `PlatformProxyModule`; the only code that resolves `BiometricUnlockDelegate` on iOS is
  `ControlSurface.installRealRuntime` (for `LockedViewModel` and `NewUserViewModel`); the Compose
  `ViewModelsModule` that also asks is not on iOS; no non-Control Swift uses `LAContext`. So no
  other app can reach the biometric unlock path, and the binding moved to Control's graph
  (`ControlSurface.runtimeModules`, which `installRealRuntime` and Control's guard both use). It adds
  no service, so no declared vector changes. Both guards now say so: Control's resolves an
  `IosBiometricUnlockDelegate`, Drive's asserts none is bound (⚠ proved able to fail: with the old
  `PlatformProxyModule`, Drive's guard went red with *"Drive's graph binds a
  BiometricUnlockDelegate"*). Measured effect: no LocalAuthentication hint in Drive's release object
  and no load command in its executable. The umbrella apps lose it too (not measured; none of them
  is archived). ⚠ **Open, the maintainer's:** whether App Store Connect flagged Drive build
  `202610151433` (umbrella, LocalAuthentication linked, no `NSFaceIDUsageDescription`). After this
  change the question is moot for Drive, but the answer says whether the next app to link it needs
  the string.
- **Xcode.** `drive-ios/project.yml` applies `AppFramework` with `TENSHU_SDK_MODULE:
  tenshu-sdk-ios-drive`, and its prebuild runs `scripts/ios-build-sdk.sh drive`. No `framework:`
  dependency, no second spec.
- **Purpose strings.** Drive applies no generic strings. It keeps its two own:
  `NSCameraUsageDescription` (`QrScannerView`, used by the shipping *redeem an invite* sheet as well
  as the `DEV_HARNESS` pairing surface; the old comment said harness only) and
  `NSPhotoLibraryUsageDescription` (the photo backup's `PHPhotoLibrary.requestAuthorization`; the
  upload picker, SwiftUI's `PhotosPicker`, needs none). No `NSFaceIDUsageDescription`, no
  `NSPhotoLibraryAddUsageDescription` (Drive never writes to the library; read).
- **⚠ Deviation: the umbrella archive path is deleted now, not in P5.** Drive was the last user of
  `UmbrellaPurposeStrings` and `ArchiveApp`, so both templates are gone from `release.yml`, and
  `ios-archive.sh` lost its `umbrella` branch: every app it archives links its own framework. An
  umbrella app cannot be archived until it gets its own (P4), which is the order P4 needs anyway.
- **The D7 check.** Drive's allowlist is `AVFoundation Photos`, from the archive above:
  AVFoundation for the QR camera, the streaming player and video posters; Photos for the backup.
  PhotosUI is not on it because `otool -L` shows only the `_PhotosUI_SwiftUI` overlay, which is not
  on the sensitive list. The archive passed it, and the export produced an `.ipa` with the App Store
  Connect API key.
- **Not done here:** the TestFlight upload of Drive (`scripts/ios-archive.sh --upload drive`), which
  the maintainer runs; a device run of the release build; the privacy-manifest re-audit of §9.4
  (Drive's `PrivacyInfo.xcprivacy` is unchanged, and the four required-reason categories come from
  the shared core, which Drive still links).

**P4: the other eight.** Fit, Timeline, Maps, Photos, Home, Mia, Calendar, Contacts, one PR each or
in small groups. Each sets its allowlist and redoes its privacy audit. ⚠ For these eight, which
have no `ReleaseApp` setup yet, P4 proves only that their Swift compiles against their own framework
(`ios-smoke.sh --build-only`). Their load commands and their ITMS result are proved when each gets
`ReleaseApp` and its first archive.

**P4 as built (2026-10-15).** One commit per app, in the order Calendar, Contacts, Home, Mia, Maps,
Photos, Timeline, Fit; one PR.

- **Modules.** Each `:tenshu-sdk-ios-<app>` holds §5.3's files for that app (the umbrella's
  `FitRegionResolver` / `TimelineRegionResolver` from P1 go with their apps) and §7's export set,
  with the corrections noted under §7. Each has `<App>DeclaredServiceVersionsTest`, its half of the
  §6.2 guard against the same pin, which also asserts the base `file://` upload reader (D6) and that
  no `BiometricUnlockDelegate` is bound (§3.3). All ten guards declared the same 25 services. Each
  app's existing tests moved with its code. ⚠ The guards were not re-proved able to fail: they call
  the same `assertDeclaresPinnedServiceVersions` P1 proved, over each app's own assembly.
- **Xcode.** Each `project.yml` drops the `framework:` dependency and the umbrella search path,
  applies `AppFramework` with `TENSHU_SDK_MODULE: tenshu-sdk-ios-<app>`, and runs
  `scripts/ios-build-sdk.sh <app>` as its prebuild (§9.1's as-built note on `release.yml`).
- **Cross-app edges.** No new code edge: no app's main code named another app's. Three test edges,
  handled as P3 handled Drive's:
  - `PhotosOwnedInferenceStoresTest` and `PhotosOnDeviceAiTest` assemble Drive's and Mia's runtimes
    beside Photos'. They moved to `:tenshu-sdk-ios-photos` with **test-only** dependencies on
    `:tenshu-sdk-ios-drive` and `:tenshu-sdk-ios-mia`; `MiaLocalRuntime.Assembly.koin` became public
    and `@HiddenFromObjC`, as Drive's did. Photos' program contains neither app.
  - `IosRegionResolverWiringTest` asserted over Fit's and Timeline's surfaces in one test. It is
    split: `TimelineRegionResolverWiringTest` (with the route-1 drain) and
    `FitRegionResolverWiringTest`, so neither app's tests need the other's code.
  - `SyncActivitySharedReplicaTest` assembles only `FirstPartyLocalRuntime`, so it moved to
    `:tenshu-sdk-ios-core`.
  - Photos exports `:control-shared-logic` for the Models screen. That is not a code edge: every
    app's graph already holds it through `:control-shared-proxy` (D4); the export adds header names
    to Photos' framework only.
- **Purpose strings.** None changed: no app on the umbrella carried the generic ones (P3 deleted
  them), and §10.4 found no sensitive framework without its string. Two review questions remain,
  both older than P4: Fit's and Timeline's camera string, and MapLibre's CoreLocation in Maps.
- **The umbrella** has no sources and no tests. It still configures, and `iosKotlinCheck` still
  compiles it (to nothing), but its framework link reports `NO-SOURCE`: there is no umbrella
  XCFramework to link any more. Nothing in the build links it.
- **Not done here.** No app's `ReleaseApp`, privacy manifest or `ios-archive.sh` allowlist; no
  TestFlight upload; no device run; no release link of any of the eight (M2 to M4 for them wait for
  their first archive). The §9.4 privacy-manifest audit is owed per app at that point; Photos'
  existing manifest was written against the umbrella and may now over-declare. All of this landed
  on 2026-10-17 (§10.5).

**P5: remove the umbrella.** Delete `:tenshu-sdk-ios` and its XCFramework task. Update
`iosKotlinCheck`, CI and the `tech-debt.md` entry. (The generic strings, `ArchiveApp` and both
`project.archive.yml` files are already gone: P2 and P3.) What still references it after P4:
`settings.gradle.kts`; `tenshu-sdk-ios/build.gradle.kts` (export list and test dependencies, all
now unused); `iosKotlinCheck`'s `dependsOn(":tenshu-sdk-ios:…")` and the root build's convention
block, which names the umbrella in its comment; `scripts/ios-build-sdk.sh`'s no-argument branch
(which would now assemble nothing) and `scripts/ios-smoke.sh`'s `NEEDS_UMBRELLA` block (which no
app triggers); `.github/workflows/ios.yml` (`assembleTenshuSdkXCFramework`); and comments and docs
that call it "the umbrella" (`basemap-core/build.gradle.kts`, `ErrorCopyKey.kt`, `tech-debt.md`).

**P5 as built (2026-10-15).**

- **Deleted.** `:tenshu-sdk-ios` (its `build.gradle.kts`, the only file left) and its `include`.
  `./gradlew projects` lists only the twelve `:tenshu-sdk-ios-*` modules.
- **`iosKotlinCheck`** depends on the `compileKotlinIosSimulatorArm64` of the ten app modules,
  `-core` and `-region`, plus `:ui-core` as before. Checked before deleting: the
  `iosSimulatorArm64CompileKlibraries` graphs of those twelve reach exactly the same 116 project
  modules as the umbrella's, so the check lost no coverage. ⚠ A new app module must be added to the
  list by hand, or its code compiles in no check.
- **`scripts/ios-build-sdk.sh`** with no argument is now an error naming the ten apps, not "build all
  ten": every caller (the ten prebuilds, `ios-archive.sh`) names its app, and ten links behind a
  missing argument would hide a broken caller. `ios-smoke.sh --build-only` and `iosKotlinCheck`
  are the "all apps" commands.
- **Other scripts.** `ios-smoke.sh` lost its `NEEDS_UMBRELLA` block. `reinstall-all-apps.sh` and
  `store-screenshots.sh` still ran the umbrella's (empty) XCFramework assemble before their Xcode
  builds; both now rely on each app's prebuild. `check-objc-clashes.py` has no umbrella default:
  without `--framework` it checks every app framework in turn (3 clashes each, 4 for Drive).
- **CI.** `.github/workflows/ios.yml` links each app's debug simulator framework (§8.2) in place of
  `assembleTenshuSdkXCFramework`. Not run: Actions is billing-dark.
- **Comments and docs** that named the umbrella as current (root build, `basemap-core`,
  `ErrorCopyKey`, `ui-core`'s catalogue, the shared iOS fragments, `tech-debt.md`, `AGENTS.md`,
  `README.md`, `agent.md`, the how-tos) now name the per-app modules. Historical records (as-built
  notes here and in other proposals, incident write-ups) are unchanged.
- **Verified (this Mac).** `iosKotlinCheck` green (520 tasks executed, all twelve SDK modules
  compiled); `iosTestCheck` with cleared results: 19 test tasks, 3,240 tests, 0 failures once
  `PhotosModelDownloadTest`'s known load flake (`tech-debt.md`, red 2 of 8 under load) was rerun
  alone three times green; `ios-smoke.sh --build-only` passes for all ten apps; `ios-archive.sh
  control` links the release framework, archives, and passes the allowlist (AVFoundation,
  LocalAuthentication), then fails at export for want of an App Store Connect account here, as P2's
  run did. Not run: the CI workflow, any device run, any upload.

---

## 11b. Distributing the SDK as Swift packages later

Decided with the maintainer, 2026-10-15: the per-app structure is also the basis for shipping a
public iOS SDK, and needs no new shape for it. It is **not** a path to Maven-style modules on iOS.

- ⚠ **Kotlin frameworks do not compose.** Every Kotlin/Native framework carries its own copy of the
  Kotlin runtime and of every Kotlin module inside it. Two of them in one app (`TenshuCore` +
  `TenshuUI`, say) are two worlds: a type from one is a different class in the other, objects cannot
  cross, and each holds its own state. So an app links exactly **one** Kotlin framework that bundles
  everything it needs. The modularity lives in Gradle (the `:tenshu-sdk-ios-*` modules and the shared
  convention of §5.2), never in the binaries.
- **A public SDK is one more consumer of that convention:** a module such as `:tenshu-sdk-ios-public`
  with its own export set, assembled as an XCFramework and published through a `Package.swift`
  `binaryTarget` (a zip plus checksum; it could be hosted beside the store images on
  `assets.tenshu.app`). Variants are complete packages ("SDK", "SDK + Drive"), of which an app picks
  one, not pieces to combine.
- **Swift code on top can be modular.** SwiftUI components or helpers written in Swift against the
  framework are ordinary Swift packages and split freely. A UI kit for third parties would be Swift,
  not Kotlin.
- **Android has no such limit:** core, services and UI publish as separate Maven artifacts.
- **What to watch:** Kotlin's Swift export (generating Swift directly instead of going through
  Objective-C; experimental as of this writing) maps Kotlin modules to separate Swift modules, but
  still produces one binary per app. It would improve the Swift surface, not make binaries combinable.

## 12. Risks and open questions

- **R1, the main one: a slimmer proxy graph per app** (§6.2). Mitigated by D4 and the per-app
  pinned-list test. Revisit only once cursors are kept per service (`ios-route1-replica-scope.md`'s
  leading candidate).
- **R1b: app code left in core.** Core is exported by every app (D9), so one reference from core to
  an app's code puts that app, and its frameworks, into all ten. §5.3 lists the three edges found;
  the per-app allowlist (D7) catches a new one at archive time.
- **R2: release skew between installed apps** (§6.3). Not created by A, but made cheaper. Mitigated
  by the runbook rule and the Info.plist stamp; fixed for real by M12.
- **R3: a missing export breaks one app's Swift** (§5.2). Loud, but only `ios-smoke.sh` catches it.
  Every phase runs it for the apps it touches.
- **R4: SKIE analytics enabled by a new module.** Mitigated by the shared build-logic script.
- **R5: build time** (§8). Unknown until M4. If per-app release links are close to the umbrella's
  time, a ten-app release takes most of an hour.
- **R6: M1 comes out the wrong way.** If K/N emits hints for unreferenced but present platform
  libraries, then any sensitive import in a module every app links (the `-proxy` modules,
  `core-common`) reaches all ten apps. Today no such module imports one of the five (read, §2.2), so
  the immediate effect is none; the rule for new code would then be "no sensitive platform API in a
  module the core links", enforced by the allowlist check.
- **Q1:** Module naming (`:tenshu-sdk-ios-<app>` or `:<app>-sdk-ios`).
- **Q2:** Whether the Drive and Control smokes and probes (`EpcLoopbackSmoke`, `RelayBlockProbe`,
  and the rest) are still wanted at all. Moving them is the moment to delete the dead ones.
  ⚠ Partly answered 2026-11-05: the C4d/C4e route-1 probes (`AppGroupProbe`, `RealmCryptoProbe`,
  `RelayAuthProbe`, `RelayBlockProbe`, `RelayFolderProbe`) and their Swift routes were deleted.

---

## Version

- v0.1, 2026-10-14: first draft.
- v0.2, 2026-10-14: revised after review (below).
- v0.5, 2026-10-15: P3 built (§10.3, P3 as built); LocalAuthentication settled and moved (§3.3).
- v0.6, 2026-10-15: §11b, distributing the SDK as Swift packages (decided with the maintainer).
- v0.7, 2026-10-15: P4 built (§10.4, P4 as built); §7 corrected for Mia and Calendar; P5's list.
- v0.8, 2026-10-15: P5 built (P5 as built): the umbrella is deleted.
- v0.9, 2026-10-17: the other eight archive (§10.5); §10.4's camera and MapLibre items resolved.

## Review 2026-10-14

An independent review of this proposal found no blockers and four major issues. Each was checked against
the code before changing the text.

- **Third cross-app edge** (§5.3): `EpcRelayPairing.Channel.activate()` → `LiveClientSession` →
  `DriveReadSurface`. `LiveClientSession` moves to Drive; `activate()` becomes a Drive extension.
- **LocalAuthentication** added (§2.2, §3.3, §4, P3): `IosBiometricUnlockDelegate` in
  `:control-shared-proxy` is bound in every route-1 graph, and only Control declares
  `NSFaceIDUsageDescription`. PDFKit, UserNotifications, Network and NetworkExtension listed too.
- **Core is exported by every app** (new D9, §5.2, §7): shared Swift names core types, so core's
  whole surface lands in every framework; base exports go in the convention script with SKIE.
- **The guard test could not fail** (§6.1, §6.2): it compared `coreSchemaConfigurations()` with
  itself. Replaced by a per-app test against a committed pinned list. Corrected "every runtime goes
  through `assembleBase`": Control builds its graph in `ControlSurface`.
- Minor: §6.2 no longer cites the deleted `advancedCursor`; cursors are per lane now, the hazard
  stands (`SchemaPullFence`). §2 states exactly which five keys ITMS-90683 flagged (not Photos or
  CoreMotion). D2 says "practical", not "only". Compose runtime and Skia found in the archive, so
  "most code is the proxy graph" is unproven (M3). `:app-error-handling` is newly exported, so
  `check-objc-clashes.py` must run. D8 no longer calls Control's module set small. M1 must be a
  release link. The allowlist is called an allowlist, not attribution. P4 says what it proves for
  apps without `ReleaseApp`.
