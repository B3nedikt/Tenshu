# Handover — iOS proxy architecture, Phase 6b.5 (Drive-client build-out)

> Paste the **Kickoff prompt** below into a fresh Claude Code session at the repo root to start
> Phase 6b.5. The rest of this file is context for a human picking the work up. Companion to the
> decision doc [`ios-proxy-architecture.md`](ios-proxy-architecture.md) (read §9.3 / §9.4 / §9.5
> first) and the Phase-6 note [`ios-proxy-phase6-handover.md`](ios-proxy-phase6-handover.md).

---

## Context in one paragraph

The EPC stack is a finished, fully-tested library and `drive-ios/` is a real iOS app target — but
it is still only a **crypto smoke harness** (`SmokeView`/`SmokeReport`/`DriveIosApp`), not the Drive
client. 6b.5 turns it into the first **real Drive-on-iOS client**: a pure EPC client paired to the
user's awake desktop/Android **Control**, which does all the durable work. Per the §9.3 code-sharing
decision, the client shares the umbrella SDK **plus** Drive's view-model/repository layer
(`:drive-shared`) and writes its UI in **SwiftUI**; the bridge is **SKIE** (§9.5). The honest blocker
is not the UI — it is that `:drive-shared` and `:core-paging` are `androidTarget` + `jvm("desktop")`
only today, so they must be made iOS-capable (a packaging/extraction job, not a rewrite — the ~21 VMs
already extend the multiplatform `ViewModel` and expose `StateFlow`) before any SwiftUI screen can
resolve a VM. 6b.5 needs **no Apple Team ID, no App Attest, no signing** (it runs against the Phase-4a
**software** attestor and the iOS Simulator), which is why it is the longest unblocked runway on the
track and is sequenced *before* the Apple-gated 6c/6d.

## Status going in (as of 2026-06-19)

- **Shipped:** Phases 1–4a (EPC channel + relay rendezvous + attestation seam/software verifier),
  6a (libsodium crypto actuals + the `drive-ios` app target + crypto smoke harness), 6b
  (`:epc-rendezvous-client` extracted + exported, Darwin engine links/runs in the app binary).
- **🎉 6b.5 COMPLETE — Slice 4b SHIPPED (2026-06-19): real Drive VM → SwiftUI list.** `DriveReadSurface`
  (umbrella iosMain) hand-wires the real `FilesScreenViewModel` (+ `SelectionViewModel` + a
  `CannedFilesRepository` for the 27-member interface) and a `TenshuPagingPresenter<FileUiModel>`
  collecting its `pagedFiles`. SwiftUI `FolderListView` (a `Files` tab) observes `presenter.snapshot`
  (SKIE `StateFlow`→`AsyncSequence`) and renders the listing; `FileIcon`→SF Symbols. Data is canned
  (no proxy serves Drive ops on iOS — 6d/6e); the VM/paging/presenter are real, so it's a repository
  swap to go live. **Verified on the iPhone-16 sim: RESULT: ALL PASSED** incl. `[Drive read surface
  (6b.5)] … surfaced 4 files (Photos, sunset.jpg, notes.txt, budget.pdf)`. See proposal Appendix
  "Phase 6b.5 — as-built (Slice 4b…)". **All four slices (1→4b) shipped; Phase 6b.5 is done.** The
  follow-up `:drive`→`:drive-android` rename also landed.
- **⚠ Gap flagged (2026-06-19): the 4a pairing screen is a loopback demo, not real pairing.** It has
  no out-of-band exchange — the app can't learn *which* proxy to reach. The **real EPC pairing flow**
  (proxy generates a QR `{relayBaseUrl, pairingSecret}` → iOS scans → real `RelayRendezvousTaskTransport`
  → SAS-confirm on both ends) is now **designed in proposal §10** and is the actual prerequisite next
  step — it's cross-cutting (Control + iOS) and really the first piece of **6d/6e**, *ahead of* 6c
  (App Attest gates a channel that can't yet be established). Open scoping (which Control hosts the
  proxy side first) deferred to when the slice is picked up. **Track remainder: §10 real pairing flow
  → 6c/6d (Apple App Attest) → 6e (Control-on-iOS — real listings over EPC).**
- **6b.5 Slice 4a SHIPPED (2026-06-19): real EPC channel + SAS screen in the iOS app.** `EpcLoopback`
  (umbrella iosMain) runs the real `EpcConnector`↔`EpcResponder` handshake over an in-memory transport
  → 6-digit SAS + directional-AEAD sealed round-trip, inside the app binary. SwiftUI `EpcPairingView`
  (a `Pair` tab) drives `connect()` (SKIE `suspend`→`async`) and shows the SAS; `EpcLoopbackSmoke`
  folds into the `RESULT: ALL PASSED` verdict. Loopback by necessity (no proxy on iOS; Control serves
  EPC at 6d/6e). The iosX64 drop forced two app-side fixes: a no-arg `connect()` overload (SKIE doesn't
  surface default function-typed params) + `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64` in
  `project.yml` (else SKIE's async extensions vanish behind the Obj-C fallback). **Verified on the
  iPhone-16 sim: RESULT: ALL PASSED** incl. the EPC-channel rows. See proposal Appendix "Phase 6b.5 —
  as-built (Slice 4a…)". **Next: 4b** — the `FilesScreenViewModel` read surface + presenter binding.
- **6b.5 Slice 3 SHIPPED (2026-06-19): SKIE at the umbrella + logic exported.** Added SKIE
  (`co.touchlab.skie` 0.10.12 — first release supporting Kotlin 2.3.21, zero headroom; tech-debt entry
  "SKIE ⨯ Kotlin version pin" gates Kotlin bumps) to `:tenshu-sdk-ios`; **analytics upload disabled**
  (HR#1). Exported `:core-paging` + `:drive-shared-logic`; **dropped `iosX64` from the umbrella** (the
  paging dep has no iosX64 klib — the slice-1 open decision, resolved the recommended way). Bridge
  verified: `FilesScreenViewModel` `suspend`→Swift `async`, sealed `RepositoryResult`→Swift enum +
  `onEnum`, `SkieSwiftStateFlow` (Flow→AsyncSequence) generated. Both framework arches link;
  `assembleTenshuSdkXCFramework` produces ios-arm64 + ios-arm64-simulator. ⚠ SKIE renames the 6a
  `object TenshuSdk`→`TenshuSdk_` (Xcode-side smoke touch-up; slice 4 uses SKIE names). See proposal
  Appendix "Phase 6b.5 — as-built (Slice 3…)".
- **6b.5 Slice 2 SHIPPED (2026-06-19): Drive logic layer is iOS-capable.** Two PRs. **2a** de-Composed
  the logic in-place (`ImageVector`→`FileIcon` enum, `ImageBitmap`→`previewBytes: ByteArray`,
  `DecimalFormat`→KMP formatter). **2b** split `:drive-shared` into **`:drive-shared-ui`** (Compose
  screens + DI + preview subsystem) + **`:drive-shared-logic`** (66 Compose-free VM/repo/model/domain
  files, **iosArm64 + iosSimulatorArm64**). Packages unchanged → zero screen import churn (same-package
  across the module boundary). `toPagingData` is now plain commonMain (via `:core-paging`). Verified:
  `:drive-shared-logic` compiles for iOS sim + device + desktop + android; desktopTest green (logic 4 +
  ui 208); both apps compile. ⚠ `:drive`→`:drive-android` rename deferred to a follow-up PR. See
  proposal Appendix "Phase 6b.5 — as-built (Slice 2…)".
- **6b.5 Slice 1 SHIPPED (2026-06-19): `:core-paging` iOS-ization.** Added iOS targets
  (`iosArm64` + `iosSimulatorArm64` only — `paging-common` 3.4.2 ships no `iosX64` klib), moved
  `PagingUtil.kt` `jvmMain`→`commonMain`, added the non-Compose `TenshuPagingPresenter<T>`
  (`snapshot`/`loadState`/`get`/`size` over `PagingDataPresenter`) + a `commonTest` contract test
  green on the `iosSimulatorArm64` gate. SKIE gate **verified green** (SKIE 0.10.12 supports the
  pinned Kotlin 2.3.21). `:drive-shared` jvm consumer still compiles. As-built in the proposal
  Appendix ("Phase 6b.5 — as-built (Slice 1…)"). ⚠ **Slice-3 carry-over:** umbrella still declares
  `iosX64`; exporting the paging surface forces dropping iosX64 umbrella-wide (recommended) or
  keeping paging off the framework.
- **`drive-ios/`** is an XcodeGen project (`project.yml` committed; `.xcodeproj`/`Info.plist`
  generated + gitignored), a pure consumer of `TenshuSdk.xcframework`. Sources today:
  `Sources/DriveIosApp.swift`, `Sources/SmokeView.swift`, `Sources/SmokeReport.swift`. Simulator-
  verified (`RESULT: ALL PASSED`).
- **`:drive-shared`** is `androidTarget` + `jvm("desktop")` only; mixes VM/repo logic with Compose-UI
  deps (`:ui-core`, `paging.compose`, the `@Composable` screens). ~21 VMs already extend
  `androidx.lifecycle.ViewModel` + expose `StateFlow`; only **5** spots touch `mutableStateOf`.
- **`:core-paging`** is `jvm("desktop")` only (`PagingUtil.kt`). The VMs expose
  `Flow<PagingData<FileUiModel>>`; the Compose screens (`FilesScreen` / `FolderScreen` /
  `MovePickerScreen`) consume it via `.collectAsLazyPagingItems()`.
- **EPC transport** to reach a proxy is ready: `:epc-rendezvous-client`
  (`RelayRendezvousTaskTransport` + `darwinRendezvousHttpClient()`) + `EpcConnector` /
  `EpcTaskTransport` / `EpcAttestationClient` in `:core-common`. The proxy side
  (`EpcRendezvousAcceptor`, `EpcResponder`, `EpcAttestationServer` with `SoftwareAppAttestVerifier`)
  lives in `:sync-service-proxy` and ships with the desktop/Android Control you pair against.

## Decisions already locked for this slice (proposal §9.5)

- **Bridge = SKIE** (not KMP-NativeCoroutines). Applied at the `:tenshu-sdk-ios` umbrella only.
  ⚠ **Verify SKIE supports the repo's pinned Kotlin before adopting** — it is a compiler-version-
  coupled plugin (same fragility class the libsodium cinterop avoided); gate future Kotlin bumps on
  SKIE support, like the CMP 1.7.3 pin in `tech-debt.md`.
- **Paging = `androidx.paging` `PagingDataPresenter`** (the non-Compose base `LazyPagingItems` is
  built on, in the KMP `paging-common` artifact) — *not* a hand-rolled `AsyncStream`, which would
  drop load states / retry / placeholders / the load-next trigger. v1 shortcut: a plain
  `StateFlow<List<FileUiModel>>` is acceptable for small/bounded lists; add the presenter when a
  folder is actually large.
- **O3 (upload) = Option A, foreground-only** for v1 — no content key, no threat-model change.
  Backgrounding cancels an in-flight upload. Option B (content-key exception + background
  `URLSession`) is deferred to when survives-backgrounding upload is built.
- **O1 / O4 = deferred** (Control-on-iOS, 6e). v1 assumes a reachable desktop/Android proxy at
  pairing time.
- **Attestation = the Phase-4a software attestor** as the dev gate. Real Apple App Attest is 6c/6d,
  swapped in underneath the now-working client (Phase-4a seam → no protocol change).

## Suggested slice plan (each its own PR or small stack)

1. ~~**`:core-paging` iOS-ization.**~~ ✅ **DONE (2026-06-19).** Added `iosArm64` +
   `iosSimulatorArm64` (**not** `iosX64` — paging-common ships no iosX64 klib); wrapped
   `PagingDataPresenter` in `TenshuPagingPresenter` (`snapshot` + `loadState` + `get(index)` +
   `size`). Compose `collectAsLazyPagingItems` path intact for android/desktop. `commonTest` runs on
   the `iosSimulatorArm64` gate. See proposal Appendix "Phase 6b.5 — as-built (Slice 1…)".
2. ~~**`:drive-shared` extraction.**~~ ✅ **DONE (2026-06-19), two PRs.** **2a:** de-Composed the
   logic in-place (`FileIcon` enum + `previewBytes` + KMP formatter; the "5 `mutableStateOf` sites"
   turned out to be in `@Composable` screens, not VMs — the real entanglement was `ImageVector` /
   `ImageBitmap` / `DecimalFormat` in the model API). **2b:** split `:drive-shared` →
   `:drive-shared-ui` (Compose) + `:drive-shared-logic` (iOS-capable VM/repo/model layer). Packages
   kept → no screen import churn. See proposal Appendix "Phase 6b.5 — as-built (Slice 2…)".
3. ~~**SKIE at the umbrella.**~~ ✅ **DONE (2026-06-19).** SKIE 0.10.12 on `:tenshu-sdk-ios`
   (analytics off); exported `:core-paging` + `:drive-shared-logic`; dropped umbrella `iosX64`.
   Verified `FilesScreenViewModel` `suspend`→`async`, sealed `RepositoryResult`→Swift enum,
   `SkieSwiftStateFlow` (Flow→AsyncSequence). See proposal Appendix "Phase 6b.5 — as-built (Slice 3…)".
4. **First SwiftUI screens in `drive-ios`.** (a) ✅ **DONE (2026-06-19) — EPC pairing / SAS-confirm.**
   `EpcLoopback` runs the real `EpcConnector`↔`EpcResponder` handshake **over an in-app loopback**
   (not `darwinRendezvousHttpClient()` — no live proxy serves EPC yet, 6d/6e), surfacing
   `EpcConnection.sas` in a SwiftUI `EpcPairingView`. Verified on the sim (RESULT: ALL PASSED). See
   proposal Appendix "Phase 6b.5 — as-built (Slice 4a…)".
   (b) ✅ **DONE (2026-06-19) — one read surface.** SwiftUI `FolderListView` (a `Files` tab) bound to
   the real `FilesScreenViewModel` via `DriveReadSurface` (umbrella iosMain), observing its paged
   `StateFlow` through `TenshuPagingPresenter` (SKIE `StateFlow`→`AsyncSequence`). Data canned
   (`CannedFilesRepository`) — real listing over `EpcTaskTransport` waits for a proxy that serves EPC
   (6d/6e). Verified on the sim (RESULT: ALL PASSED). See proposal Appendix "Phase 6b.5 — as-built
   (Slice 4b…)".
5. **(Optional, if cheap) the §9.2 paper pass** — pin how an iOS `Info.plist` maps into the existing
   `AppManifest` shape, so the first-party bundled-manifest artifact format is settled before 6c.

## Prerequisites & gotchas

- **No Apple Team ID / signing / App Attest required** — that's the point. Verify on the **iOS
  Simulator** (`xcodebuild -sdk iphonesimulator` + `simctl`), against a locally-run desktop Control
  (`./gradlew :control-desktop:run`) and a local relay (`./gradlew :sync-relay-server:run`).
- **Paging is the trap.** `androidx.paging` core is KMP but `collectAsLazyPagingItems()` is Compose;
  do not let the Compose paging dep leak into the iOS-exported slice (step 1 is what prevents it).
- **Watch the lazy-vs-materialised seam** if any read surface touches folder listing — see
  [ADR 0024](../adr/0024-merger-field-resolution.md) (`SemanticMerger` / `resolveListField`).
- **Operations stay non-`suspend` at the interface level** (`IoOperation` + `TaskInteractor`,
  ADR 0012 / House Rule #3). Channel bring-up orchestration may be `suspend` (it is not a schema op —
  see Phase 2c's "design correction"), but schema dispatch must not.
- **CI is Actions-billing-blocked** — the `ios.yml` lane can't be observed green; verify locally and
  say so explicitly in the PR (House Rule #7).
- **No threat-model change is expected** in 6b.5 (software attestor, foreground-only upload, no
  content key). If you find yourself needing the master key or a content key on the device, **stop**
  — that is O3 / a data-plane exception and rides its own PR + threat-model amendment (House Rule #2).

## Open questions still live after 6b.5

- **6c/6d** — real Apple App Attest verifier + live gating (need a Team ID + Apple sample vectors).
- **O3** — content-key exception for survives-backgrounding upload (the first real upload feature).
- **O1 / O4** — co-resident transport + background-wake, both part of Control-on-iOS (6e).
- **Synced-Folders** — reframe to the iOS-achievable best-effort UX (§9.4) before building it.

---

## Kickoff prompt (paste into Claude Code)

> I'm picking up **Phase 6b.5** of `docs/proposals/ios-proxy-architecture.md` — the Drive-on-iOS
> client build-out (turn `drive-ios/` from a crypto smoke harness into a real EPC client with the
> first SwiftUI screens, paired to a desktop Control). **Read first:**
> `docs/proposals/ios-proxy-phase6b5-handover.md` (this note), `ios-proxy-architecture.md` §§9.3/9.4/9.5
> (the SKIE / `PagingDataPresenter` / O3-foreground-only / software-attestor decisions),
> `ios-proxy-phase6-handover.md`, the EPC code in `:core-common` `org.tenshu.core.common.epc`, the
> rendezvous client in `:epc-rendezvous-client`, `:drive-shared` and `:core-paging` (current targets
> + the `mutableStateOf` / `collectAsLazyPagingItems` sites), and House Rules #1–#7 in `AGENTS.md`.
>
> **Confirm scope with me before writing code.** Propose the first slice (likely **`:core-paging`
> iOS-ization** then **`:drive-shared` extraction**) and the SKIE-vs-pinned-Kotlin compatibility
> check up front. **Stop and ask** rather than silently: adopting SKIE if it does not support the
> pinned Kotlin; exporting any `@Composable` screen or `-proxy` module to the iOS umbrella; putting a
> master key *or a content key* on the device (that is O3 — a data-plane/threat-model change, out of
> 6b.5 scope); or adding `suspend` to a schema `Operation` interface (HR #3). Keep the Phase-1
> umbrella rule (no per-module frameworks) and the "real impl swaps in behind the existing seam, no
> protocol change" property. v1 is foreground-only (no upload-survives-backgrounding) and uses the
> Phase-4a software attestor — do **not** build real Apple App Attest here (that's 6c/6d).
>
> **When done with a slice:** open a PR (squash; branches auto-delete), verify by running the affected
> tests + a Simulator build against a locally-run desktop Control + relay (note that CI is
> billing-blocked and on-device/signing verification is out of scope — say so explicitly), update the
> proposal Appendix + §9.5 with the as-built notes, and update this handover's "Status going in".
