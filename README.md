# Tenshu

Tenshu is an open-source app ecosystem and Kotlin Multiplatform technology stack that puts users
in control of their data. Apps built on Tenshu cannot reach the internet directly — they go
through a *proxy* app on the user's own device. The user decides, per app, what data and which
services that proxy is allowed to expose, and the proxy keeps the user's data end-to-end
encrypted as it syncs between their devices.

This repository holds the platform's core libraries, the feature services, the relay
server that backs E2EE sync, and the first-party apps that consume them — `Drive` (files),
`Control` (the proxy / control plane), and a growing set of others (Maps, Timeline, Calendar,
Contacts, Fit, Photos, Home, Mia).

> **Status.** Version 1.0.1 is out on Google Play; the App Store and Mac App Store builds follow. The
> core type system, 20 feature services, the binary protocol, the sync relay and its content
> plane, pairing, recovery-from-phrase and cross-user shared realms are all implemented and
> exercised by tests. Platforms: **Android** (Google Play) and **macOS** (Mac App Store for
> Control and Drive, notarized downloads for all ten apps); **iOS** runs the first-party apps on
> the in-process "route 1" runtime; **web** (`web/`) is a TypeScript/React frontend over a
> KMP→JS SDK. Windows and Linux desktop builds run from source. The broader vision —
> Tenshu Rei, the Mia IPA platform, the Hub device, the app store and the App Console — is
> described in the [Tenshu White Paper](docs/whitepaper.adoc); some of it (Hub, Mia, commerce)
> now has code here, most of it does not.
>
> For the current state of any individual track, the design doc under
> [`docs/proposals/`](docs/proposals) is the source of truth — [`docs/README.md`](docs/README.md)
> is the curated index, and [`AGENTS.md`](AGENTS.md) routes to the ~40 live ones.

---

## Get the apps

Every app needs **Control** installed on the same device: it is the proxy the other apps talk
through, and where you pair devices and grant permissions. Install it first.

### Android (Google Play)

| App | | App | |
|-----|-|-----|-|
| **Control** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.control.android) | **Calendar** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.calendar.android) |
| **Drive** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.drive.android) | **Contacts** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.contacts.android) |
| **Photos** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.photos.android) | **Home** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.home.android) |
| **Maps** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.maps.android) | **Mia** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.mia.android) |
| **Timeline** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.timeline.android) | **Fit** | [Google Play](https://play.google.com/store/apps/details?id=app.tenshu.fit.android) |

### macOS (Homebrew)

The ten desktop apps are notarized and ship as casks in our own tap. They need macOS 14 or
later on Apple silicon.

```bash
brew install --cask b3nedikt/tenshu/tenshu-control   # first: every other app talks through it
brew install --cask b3nedikt/tenshu/tenshu-drive     # then any of: tenshu-photos, tenshu-maps, tenshu-timeline,
                                                     # tenshu-calendar, tenshu-contacts, tenshu-fit, tenshu-home, tenshu-mia
```

`brew upgrade --cask` updates them; `brew uninstall --cask tenshu-<app>` removes one. Upgrade all
of them together: apps one version apart can refuse to sync until the others catch up.

---

## What problem Tenshu solves

Most apps make it hard for users to understand when their data leaves their device, who
receives it, and what happens to it next. Tenshu addresses this with three design choices that
work together:

- **A proxy on the user's device.** Tenshu-enabled apps route every external call through a
  proxy app the user controls. The proxy can refuse, transform, or fulfil the call locally
  from data already in the user's realm.
- **Semantic permissions.** Permissions describe *what an app actually does with data*
  ("read a file matching a given name") rather than coarse capabilities ("read the file
  system"). The proxy can group, explain, and enforce them in terms a user can reason about.
- **Zero-trust backend.** The backend services Tenshu does run (the sync relay, in this repo)
  are designed so that compromising them does not reduce the security of the system. They
  see ciphertext and timing metadata, never plaintext or long-term keys. See
  [Threat Model](docs/sync/threat-model.md).

## How sync works

A user's devices that share their master identity and volume keys are called the user's
**realm devices**. A new device becomes a realm device by scanning a QR code on an existing
realm device, performing an ephemeral key exchange, and confirming a short authentication
string on both screens. Once joined, realm devices encrypt every change locally, write the
ciphertext to the sync relay, and pull each other's ciphertext on the way back. The relay
never holds long-term secrets and never sees plaintext. If the user loses every device, a
24-word recovery code unlocks a server-side backup whose contents the user controls through
a *recovery manifest* (always the master identity key; per-volume opt-in for everything
else). Data can also be shared *across users*: a **shared realm** is a separate end-to-end
encrypted store whose membership is granted over a mutual invitation handshake — there is no
user directory, and the relay learns no more about a shared realm than about a personal one.

The full pairing protocol is documented in
[docs/sync/pairing-flow.md](docs/sync/pairing-flow.md); the relay's wire surface in
[docs/proposals/sync-relay-api.md](docs/proposals/sync-relay-api.md); the adversary model and
invariants in [docs/sync/threat-model.md](docs/sync/threat-model.md).

---

## Repository layout

The codebase is modularised so that **definitions**, **client-side proxies**, and
**proxy-side implementations** live in separate Gradle modules per feature. There are ~185
Gradle modules; the tables below are the orientation map, not the full list — see
[`settings.gradle.kts`](settings.gradle.kts).

### Platform core

| Module             | Purpose                                                                    |
|--------------------|----------------------------------------------------------------------------|
| `:core`            | Root interfaces (`Data`, `Composite`, `Service`), schema DSL, type system. |
| `:core-common`     | DTO base classes, binary serialization & parsing, crypto backends.         |
| `:core-client`     | Client-side request objects (ROs), reactive streams, the chain DSL.       |
| `:core-proxy`      | Proxy-side execution context, task execution, dependency management.       |
| `:core-paging`     | Paging primitives shared by the client and UI layers.                      |
| `:core-aidl`       | Android Binder interface definitions.                                      |
| `:core-test-utils` | Shared test infrastructure.                                                |
| `:diagnostics`     | Device-local diagnostic log stream.                                        |

### Feature services

Each feature service follows the same `:[service]` / `-common` / `-client` / `-proxy` /
`-test-utils` split. The feature module holds the service interfaces and schemas; the
layered modules hold the DTO, RO, and implementation factories.

| Service                  | What it owns                                                                     |
|--------------------------|----------------------------------------------------------------------------------|
| `:file-service`          | Virtual file system, previews, transfers, content-addressed file content.        |
| `:sync-service`          | Block-level sync, pairing, recovery, shared-realm grants, background scheduling.  |
| `:identity-service`      | Realm identity: device roster, members, memberships, entitlement records.        |
| `:notification-service`  | System events, alerting, broadcasts.                                             |
| `:preference-service`    | Synced per-app / per-device settings.                                            |
| `:storage-service`       | The user's storage provision — available, used, remaining.                       |
| `:logging-service`       | Proxy-side capture of typed errors that escape implementation bodies.            |
| `:contact-service`       | The contact graph, acquired only via the mutual invitation handshake.            |
| `:calendar-service`      | Calendars and events.                                                            |
| `:photo-service`         | Statements *about* photos (albums, ordering, favourites) — never the bytes.      |
| `:place-service`         | Canonical POIs, reusable by anything that records "where".                       |
| `:timeline-service`      | `Moment`s and `Trip`s — the Timeline app's spine.                                |
| `:health-service`        | Neutral `Workout` records behind per-platform import seams.                      |
| `:map-region-service`    | Offline basemap region delivery (operations, not synced entities).               |
| `:ingest-service`        | Control's generic background-ingest registry.                                    |
| `:inference-service`     | Derived rows — classification, embeddings, dictation. Backend registry is empty. |
| `:mia-service`           | Mia's query layer: an utterance becomes a chain over the user's own realm data.  |
| `:routine-service`       | Automations as realm data, evaluated locally in every proxy.                     |
| `:home-device-service`   | Bridged smart-home devices as synced state.                                      |
| `:hub-management-service`| Management surface for Tenshu Hub nodes.                                         |

### Servers

- `:sync-relay-server` — Ktor/Netty server that brokers encrypted sync blocks, the content
  plane, and pairing mailboxes. Holds ciphertext and ephemeral state only.
- `:commerce-service` — Ktor issuer for accounts, sessions and signed entitlement tokens.
  Deliberately separate from the relay: the bridge between them is a signed token.
- `:hub` — the Tenshu Hub node (JVM; deployed to a Raspberry Pi, see
  [docs/how-to/hub-pi-deploy.md](docs/how-to/hub-pi-deploy.md)).
- `deploy/` — container build and Terraform/Compose deployment for the above.

### UI, apps & SDKs

- `:ui-core`, `:ui-qr-scanner`, `:ui-map`, `:basemap-core`, `:vector-tile` — shared Compose Multiplatform
  components, theming, KMP navigation, and the map/basemap rendering stack.
- Per app, the split is `-shared-logic` (Compose-free view-model/repository layer, iOS-capable),
  `-shared-ui` (Compose UI on top of it), then thin `-android` / `-desktop` shells:
  **Drive**, **Maps**, **Timeline**, **Calendar**, **Contacts**, **Fit**, **Photos**,
  **Home**, **Mia**. Control predates that convention and uses `:control` (Android),
  `:control-shared` (UI), `:control-shared-logic` and `:control-shared-proxy` — it is where the
  user manages permissions, realm devices, and the recovery manifest.
- iOS apps are XcodeGen projects (`drive-ios/`, `control-ios/`, `maps-ios/`, …) each linking
  its own `:tenshu-sdk-ios-<app>` Kotlin framework, plus the shared `ios-shell` / `ios-storage` / `ios-basemap` /
  `ios-onboarding` modules. ⚠ `Info.plist` is generated from `project.yml` and gitignored.
- `web/` — TypeScript + React frontends (`web/drive`, `web/apex`) over the KMP→JS
  `:tenshu-sdk-js`. The UI is deliberately *not* Compose Multiplatform Web; see
  [docs/web/architecture.md](docs/web/architecture.md) §12.5.

---

## Building & running

### Prerequisites

- **A JDK 17+** to run Gradle. Modules compile against a JDK 17 toolchain (21 for `:hub`);
  Gradle downloads a missing toolchain automatically via the foojay resolver.
- **Android SDK Platform 37** — `compileSdk` is 37; without it every Android module fails at
  `checkDebugAarMetadata` with a message naming a *dependency* rather than the missing platform.
  (`minSdk` 24, `targetSdk` 36.)
- Android Studio, or any IDE with the matching Android Gradle Plugin, for Android builds.
- **macOS + Xcode** for anything iOS. Kotlin/Native will not cross-compile Apple targets
  elsewhere: `iosKotlinCheck` refuses to run, and the gate prints *"iOS lane NOT VERIFIED"*.
- **Node 22+ and pnpm** only for the TypeScript frontends under `web/` (the Kotlin/JS tests in
  the gate fetch their own Node).
- The Gradle wrapper (Gradle 9.8.1) handles the rest.

### Tests

> ⚠ **`./gradlew test` is not the test suite.** Almost every module here is Kotlin
> Multiplatform, and `test` matches none of their test tasks — it picks up only the handful of
> Android `testDebugUnitTest` ones and reports green while KMP tests would fail.

```bash
# The JVM / desktop / Android gate. Use the script rather than the raw Gradle line: a
# re-run of the raw line reports BUILD SUCCESSFUL having executed ZERO test tasks (all
# UP-TO-DATE), so the script clears results first and asserts that tests actually ran.
scripts/run-gate.sh
# ≡ ./gradlew desktopTest jvmTest test testAndroidHostTest, then the JS tests and the
#   Kotlin/JS + iOS compile lanes (`compileTestKotlinJs`, `compileTestKotlinIosSimulatorArm64`).
#   (`testAndroidHostTest` is NOT reachable from `test`; omitting it hides ~219 test classes.)

# A single module. KMP modules have `jvmTest` / `desktopTest`, NOT `test`.
./gradlew :sync-service-proxy:jvmTest
./gradlew :core-common:desktopTest
./gradlew :sync-relay-server:test          # JVM-only modules do have `test`

# The `iosMain` code the iOS SDK frameworks reach. macOS only.
./gradlew iosKotlinCheck

# Every module's iOS simulator test suite — NOT in the gate. macOS only.
./gradlew iosTestCheck

# The only thing here that compiles Swift (assembles the XCFramework first, so it
# supersedes iosKotlinCheck, and is much slower). One app, or all ten if none is named.
scripts/ios-smoke.sh --build-only drive

# Android smoke gate: boot emulator → install Control + Drive + Maps + Home + Calendar +
# Timeline + Mia + Photos → launch → assert no crash/ANR, and assert schema lockstep across apps.
scripts/android-smoke.sh
```

The gate runs the JVM, desktop, Android and JS tests and compiles the Kotlin/JS and iOS lanes,
but **it compiles no Swift and runs no iOS tests**. A change touching a shared `*UiState` or an
exported model owes `scripts/ios-smoke.sh` too, and an `iosMain` change written off macOS is
*unverified*, not green.

### Running

```bash
# Build everything.
./gradlew assemble

# Desktop variants.
./gradlew :drive-desktop:run
./gradlew :control-desktop:run

# Sync relay locally (dev mode; binds 0.0.0.0:8080).
./gradlew :sync-relay-server:run
```

For Android, install the debug variants from your IDE, or via
`./gradlew :control:installDebug :drive-android:installDebug`. Control must be installed
alongside any client app — Drive (or any other Tenshu-enabled client) binds to Control over
Android Binder via the `ProxyService` Control publishes.

### Toolchain versions

Versions are centralised in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The
current line is Kotlin **2.4.20**, AGP **9.5.0**, Gradle **9.8.1**, Compose Multiplatform
**1.12.1** (with `compose-bom` **2026.09.00**; bump the pair together), Ktor **3.6.1**, Koin **4.2.2**. One constraint is load-bearing:

- **Kotlin bumps are gated on SKIE.** SKIE (the Swift/Kotlin bridge applied to every
  `:tenshu-sdk-ios-<app>` framework) is compiler-version-coupled, and the repo pin sits at the top of
  its supported range — the next Kotlin feature release needs a SKIE release first. See
  [docs/tech-debt.md](docs/tech-debt.md), *"SKIE ⨯ Kotlin version pin"*.

Keep all module Kotlin versions synchronised to avoid `LinkageError` during Koin initialisation
or Compose compiler execution.

---

## Documentation map

The architectural conventions live alongside the code. Start at the top and follow the links
into module-specific notes.

- **[docs/README.md](docs/README.md)** — the curated index of every design doc. **Start here
  when browsing.**
- **[docs/agent.md](docs/agent.md)** — top-level architectural conventions and an index of
  every module-specific knowledge file. **Read this first when changing code.**
- **[AGENTS.md](AGENTS.md)** — routing table for the live architectural tracks, the build/test
  gates, and the house rules.
- **[docs/milestones.md](docs/milestones.md)** — what's in development now and what's planned
  next.
- **[docs/tech-debt.md](docs/tech-debt.md)** — known issues, sized engineering tasks, toolchain
  pins and upgrade triggers.
- **[docs/sync](docs/sync)** — sync protocol design, pairing flow, threat model.
    - [Design](docs/sync/design.md) · [Pairing Flow](docs/sync/pairing-flow.md) ·
      [Threat Model](docs/sync/threat-model.md) · [Roadmap](docs/sync/roadmap.md)
    - [Relay HTTP API](docs/proposals/sync-relay-api.md) ·
      [Backend Implementation Plan](docs/proposals/sync-backend-relay-plan.md)
- **[docs/proposals](docs/proposals)** — time-bounded design docs, one per track. Each carries
  its own `Status:` header, which is the source of truth for that track.
- **[docs/adr](docs/adr)** — architectural decision records.
- **[docs/how-to](docs/how-to)** — recipes: adding composites and operations, iOS/macOS signing,
  Xcode project generation, the Android, iOS and macOS releases, relay/apex/commerce deployment,
  the tiles pipeline.
- **[docs/schema-dsl.md](docs/schema-dsl.md)** — the Tenshu schema DSL (`tModule { }`,
  `composite`, `operation`, etc.).
- **[docs/testing.md](docs/testing.md)** — test tiers, scoping rules, the `*-test-utils`
  pattern, and what the smoke gates do and don't cover.
- **[docs/protocol](docs/protocol)** — desktop and iOS IPC protocols.
- **[docs/web](docs/web)** — web architecture and roadmap.
- **[docs/design](docs/design)** — design-system audit and improvement plan, and the related
  ADR ([0001](docs/adr/0001-design-system-direction.md)).
- **[core-common/binary-protocol.md](core-common/binary-protocol.md)** — wire format for
  the schema-driven binary protocol that backs every transport.
- **[docs/whitepaper.adoc](docs/whitepaper.adoc)** — vision document for the broader
  Tenshu ecosystem.

Module-level conventions live in the `*-agent.md` file inside each module.

---

## Contributing

Four house rules worth knowing before opening a PR (the full set is in
[AGENTS.md](AGENTS.md)):

1. **The schema is load-bearing.** Adding a field, type, or operation means touching the
   schema DSL, the DTO, the RO, and (often) the proxy implementation, in the matching
   `-common` / `-client` / `-proxy` modules. See
   [docs/how-to/add-composites-and-operations.md](docs/how-to/add-composites-and-operations.md).
2. **Changing a stored-row or wire shape means a version bump plus a migration, in the same
   PR.** Positional composites make even "additive" field changes store-breaking. Bump the
   owning `service(id, version = N)` and register its `SchemaMigration`; bump
   `TENSHU_STORAGE_FORMAT_VERSION` only for `:core`-owned types. `SchemaShapeBaselineEnforcementTest`
   fails and names the field that moved if you forget.
3. **Anything that touches the sync surface needs a threat-model amendment.** The threat
   model is the contract for what the relay is and isn't allowed to learn. New data on the
   wire, new persistence on the relay, or new attestation flows require an entry in
   [docs/sync/threat-model.md](docs/sync/threat-model.md) in the same PR as the code.
4. **Android-touching PRs pass the smoke gate.** Any PR that changes `androidMain`,
   `:control`, or `:drive-android` runs `scripts/android-smoke.sh` before merge — unit tests
   and `compileDebugKotlin` miss DI-wiring, manifest and startup regressions.

Architectural decisions belong in docs, not commit messages. If you make a non-trivial design
choice, write it down in the relevant doc or a new one under `docs/`.

---

## License

Tenshu is licensed under the [Apache License, Version 2.0](LICENSE). Copyright 2026
Benedikt Löbbecke.

Two things are **not** covered by that licence — see [TRADEMARKS.md](TRADEMARKS.md):

- the Tenshu name and brand;
- the image and icon files.

Third-party software and data ship under their own licences, listed in [NOTICE](NOTICE) and
[docs/oss-licences.md](docs/oss-licences.md) — including the bundled map data,
© OpenStreetMap contributors under the ODbL 1.0.
