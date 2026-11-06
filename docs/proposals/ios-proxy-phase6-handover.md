# Handover — iOS proxy architecture, Phase 6 (Control-on-iOS + the first iOS app)

> Paste the **Kickoff prompt** below into a fresh Claude Code session at the repo root to start
> Phase 6. The rest of this file is context for a human picking the work up. Companion to the
> decision doc [`ios-proxy-architecture.md`](ios-proxy-architecture.md) (read its Appendix first)
> and the original [`ios-proxy-architecture-handover.md`](ios-proxy-architecture-handover.md)
> (Phase 1, now shipped).

---

## Context in one paragraph

iOS can't do the cross-vendor on-device IPC the Android/Desktop proxy model relies on, so every
iOS Tenshu app reaches its services through the whitepaper's **External Proxy Connection (EPC)** —
a paired, attested, E2E-encrypted app↔proxy channel, brokered through the relay
([`ios-proxy-architecture.md`](ios-proxy-architecture.md)). Phases 1–4a built that channel as a
**self-contained, fully-tested protocol/transport library** with no iOS app yet: the K/N
`XCFramework` + CI gate (Phase 1), the EPC handshake + session + transport + connector (Phase 2),
the relay `/sync/epc` rendezvous mailbox + threat-model amendment + relay-backed transport
(Phase 3), and the app-attestation **seam** with a software reference verifier (Phase 4a). **Phase 6
is the convergence point**: it stands up the first actual iOS app module, swaps every software
stand-in for the real Apple-backed implementation, and wires the channel into a live proxy. It is
gated on Apple-side prerequisites (an Xcode project, a Developer Team ID, App Attest entitlements)
that don't exist in the repo today, which is why it was deliberately left until last.

## Status going in (shipped 2026-06-16 → 06-17)

All in `org.tenshu.core.common.epc` (`:core-common`) unless noted:

- **Phase 1:** `:tenshu-sdk-ios` umbrella → `TenshuSdk.xcframework` (exports the
  client-facing surface only — `:core`/`:core-common`/`:core-client` + service `-common`/`-client`;
  **excludes `-proxy` + `-test-utils`**). `.github/workflows/ios.yml` runs `iosSimulatorArm64Test`
  + `assembleTenshuSdkXCFramework` (macOS runner). iOS test gate re-enabled for the client-facing
  modules (root `build.gradle.kts`, `afterEvaluate`).
- **Phase 2 (2a–2c):** `EpcHandshake` (X25519 + 6-digit SAS), `EpcSession` (directional
  AEAD `seal`/`open`, seq-as-AAD replay/reorder reject), `EpcTaskTransport` (encryption layer over
  any `TaskTransport`), `EpcConnector`/`EpcResponder` (handshake driver). Proven over a loopback.
- **Phase 3 (3a relay, 3b transport):** relay `/sync/epc/{sessionId}` mailbox
  (`EpcRendezvousRouting`, `RendezvousRegistry`/`RendezvousStore`/`SqliteRendezvousStore`, SQLite
  **migration v10**, JWT-gated provision + anonymous capability-addressed frame traffic);
  threat-model amendment **A31 / surface #14 / §8.16 T-RDV-01–06**; `RendezvousRelayClient` +
  `RelayRendezvousTaskTransport` + `EpcRendezvousAcceptor` (`:sync-service-proxy`
  `org.tenshu.sync.proxy.epc`); end-to-end `EpcRendezvousOverRelayTest` in `:system-test-utils`.
- **Phase 4a:** `AppAttestor`/`AppAttestVerifier` (`fun interface`s over opaque bytes) +
  `SoftwareAppAttestor`/`SoftwareAppAttestVerifier` (Ed25519 reference) + `EpcAttestationClient`/
  `EpcAttestationServer` (positional two-frame prelude that gates operations until attested).
- **Phase 6b (shipped 2026-06-19):** EPC rendezvous **client packaging**. New module
  `:epc-rendezvous-client` (`org.tenshu.epc.rendezvous`) — the client/shared half (`RendezvousRelayClient`
  + `RelayRendezvousTaskTransport` + types + `darwinRendezvousHttpClient()`) extracted out of
  `:sync-service-proxy` so the umbrella can export it without the proxy's master-key/op-impl code (HR#1).
  Engine injected; iOS uses `ktor-client-darwin`. `EpcRendezvousAcceptor` stays proxy-side. JVM E2E test
  still green; `drive-ios` smoke proves the Darwin engine links+runs. No threat-model change.
- **Phase 6a app-module half (shipped 2026-06-19):** the **first iOS app** (`drive-ios/`) — at this
  phase a SwiftUI crypto *smoke harness*, not the Drive client proper. Links the umbrella
  `TenshuSdk.xcframework` and runs Ed25519/X25519/XChaCha20-Poly1305/Argon2id against the
  `LibsodiumCryptoBackendsTest` vectors *inside a real app binary*, through the production
  `cryptoBackendsModule()` Koin wiring — proving the umbrella framework links and resolves the
  libsodium symbols (incl. internal `argon2id_hash_raw`) in a consuming app. XcodeGen project
  (`project.yml` committed; `.xcodeproj`+`Info.plist` generated/gitignored); the iOS app is an Xcode
  consumer, **not** a Gradle module (`settings.gradle.kts` unchanged). `CryptoSmoke` harness lives in
  the umbrella `iosMain` (`org.tenshu.sdk.ios`). Verified on the iPhone-16 simulator (BUILD SUCCEEDED
  + `simctl launch` → `RESULT: ALL PASSED`); real-device/signing/App Attest deferred to 6c/6d. No
  threat-model change. See proposal "Phase 6a — as-built (app-module half)".
- **Phase 6a crypto half:** the shared iOS crypto foundation. `CryptoBackendsModule.ios.kt`'s throwing
  stubs replaced by `Libsodium{Ed25519,X25519,Aead,Kdf}Backend`, libsodium-backed via the repo's
  first cinterop (`core-common/src/nativeInterop/cinterop/libsodium.def`). The static lib is built
  per-target from pinned + checksummed source by `scripts/build-libsodium-apple.sh` (no binaries in
  git), wired by `buildLibsodium<Target>` → `cinteropLibsodium<Target>` in
  `core-common/build.gradle.kts`. All four backends are byte-for-byte interoperable with the JVM
  (BouncyCastle) / JS (@noble) backends (Argon2id binds the phc `argon2id_hash_raw`, not the
  salt-locked `crypto_pwhash`, so the 22-byte production salt works). `ktor-client-darwin` added to
  the catalog (consumed in 6b). Proven by `LibsodiumCryptoBackendsTest` (iosTest gate, 14 tests) +
  `assembleTenshuSdkXCFramework` linking libsodium into the umbrella framework. No threat-model
  change (real impl behind unchanged seams). On-device + Xcode-app wiring still outstanding (6b+).

**Decisions locked:** O2 = *no* edge App Attest (relay admission is JWT-gating + caps; T-RDV-03
residual Medium). Protocol-first-over-loopback for Phase 2. Software-reference-first for Phase 4a.
**2026-06-19 (proposal §9.5):** SKIE for the `StateFlow`→Swift bridge; paging via `androidx.paging`
`PagingDataPresenter` (not a hand-rolled `AsyncStream`); O3 v1 = foreground-only uploads
(content-key exception deferred); O1/O4 deferred to Control-on-iOS (6e). Next slice = **6b.5**
(Drive-client build-out) — see `ios-proxy-phase6b5-handover.md`.

**Everything Apple-specific or app-dependent was deferred to here — the software stand-ins prove the
flow; Phase 6 makes it real on a device.**

## Phase 6 decomposition (sequence per the §9 decision)

> **Sequencing decided (2026-06-17, proposal §9):** the first iOS app is the **Drive client**,
> *not* Control, and *not* both in parallel — Drive-on-iOS is a pure EPC client that pairs with an
> existing desktop/Android proxy and forces the shared foundation (crypto actuals, darwin engine,
> real App Attest) that Control-on-iOS later reuses. **Rei is deferred:** first-party v1 reuses the
> existing bundled-manifest path (`AppManifest`/`BundledAppManifestSource`); `:rei-service` is
> third-party-only and not a prerequisite. Order: 6a → 6b → 6c/6d → pin Info.plist→`AppManifest`
> mapping → 6e → 6f.
>
> **Code-sharing boundary decided (2026-06-19, proposal §9.3):** the real Drive iOS client shares the
> SDK **+ the view-model/repository layer** (`:drive-shared`'s VMs are already `androidx.lifecycle.ViewModel`
> + `StateFlow` — iOS-capable) and writes the **UI in SwiftUI** (Compose-MP UI rejected on iOS for the
> House-Rule-#5 non-native reason). Bridge `StateFlow`→Swift with SKIE. Prerequisite: split
> `:drive-shared`'s logic from its Compose-UI deps + add iOS targets — a packaging job sequenced with
> the real client, **not** a blocker for 6b.

The proposal lists Phase 6 as "Control-on-iOS." In practice it splits into a build-up:

1. **6a — real K/N crypto + darwin engine.** ✅ **Crypto half SHIPPED (this PR).** The throwing iOS
   crypto stubs (`core-common/src/iosMain/.../security/CryptoBackendsModule.ios.kt`) are replaced by
   libsodium-backed `Ed25519`/`X25519`/`XChaCha20-Poly1305`/`Argon2id` `actual`s — **not** CryptoKit
   (Swift-only, unreachable from K/N interop), but libsodium via the repo's first cinterop, built
   from pinned source by `scripts/build-libsodium-apple.sh`, byte-compatible with the JVM/JS
   backends and proven on the `iosSimulatorArm64` gate + in the umbrella XCFramework. `ktor-client-darwin`
   is in the version catalog. ✅ **The app-module half SHIPPED 2026-06-19:** `drive-ios/` — the first
   iOS app, a SwiftUI crypto *smoke harness* (not the Drive client proper) consuming
   `TenshuSdk.xcframework` via an XcodeGen project, simulator-verified (all crypto checks pass in a
   real app binary). It is an Xcode consumer, **not** a Gradle module — `settings.gradle.kts` is
   unchanged. **Still outstanding:** the real Drive client UI/EPC flow (needs 6b transport), a
   real-device build + Developer Team ID + signing + App Attest (6c/6d; App Attest can't run on the
   simulator).
2. **6b — EPC client packaging. ✅ SHIPPED 2026-06-19.** The client half was **extracted** (not the
   whole module exported — that would drag `:core-proxy` into the client framework). New KMP module
   **`:epc-rendezvous-client`** (`org.tenshu.epc.rendezvous`, android+jvm+js+iOS) holds
   `RendezvousRelayClient` + `RelayRendezvousTaskTransport` + shared types + a `darwinRendezvousHttpClient()`
   factory; deps are only `:core-common` + ktor-client (zero proxy types). `:sync-service-proxy`
   `api`-depends on it and keeps the proxy-side `EpcRendezvousAcceptor` (ships with Control-on-iOS, 6e).
   Umbrella exports it. Verified: JVM `EpcRendezvousOverRelayTest` still green + `drive-ios` smoke proves
   the Darwin engine links/runs (closed-port request → `RendezvousRelayException`). No threat-model
   change. See proposal "Phase 6b — as-built".
3. **6b.5 — Drive-client build-out (the §9.3 extraction + first real UI). ← NEXT.** Turn `drive-ios`
   from a crypto smoke harness into an actual Drive client: make `:drive-shared` (VM/repo layer) and
   `:core-paging` iOS-capable, add SKIE at the umbrella, and put the first real SwiftUI screens — EPC
   pairing/SAS-confirm + one read surface (a folder/file list) — on the EPC channel, paired to a
   desktop/Android Control. Gated by the Phase-4a **software** attestor (real Apple App Attest is
   6c/6d, swapped in underneath). **No Apple Team ID needed** → the longest unblocked runway. SKIE /
   `PagingDataPresenter` / O3-foreground-only decisions are in proposal §9.5; the Claude-Code-ready
   slice plan + kickoff prompt is in [`ios-proxy-phase6b5-handover.md`](ios-proxy-phase6b5-handover.md).
4. **6c — Phase 4b: real Apple App Attest.** Proxy-side `AppAttestVerifier` doing the real
   verification (CBOR attestation object + X.509 chain to Apple's App Attest root CA + nonce/RP-ID/
   counter checks + assertion signatures), and an iOS app-side `AppAttestor` over
   `DCAppAttestService`. Test against **Apple sample vectors** (now sourceable). The Phase-4a seam
   takes both without protocol change.
5. **6d — live attestation gating.** Wire `EpcAttestationServer` into the proxy's accept path so a
   real channel runs the prelude before issuing a per-client `ExecutionContext` (today the
   gated-handler composition lives only in the 4a tests). `:core-proxy` / `:control-shared`.
6. **6e — Control-on-iOS co-resident proxy.** For the iPhone-only user (§3): Control ships on iOS
   as an EPC endpoint (not an App-Group broker), woken for sync windows via **`BGTaskScheduler`/push**
   (O4), reached over the *same* relay-rendezvous channel as a remote proxy. Likely wants the
   deferred **long-poll** on `/sync/epc` (`?wait=` — 3a is simple-poll) + the §3 wake fabric.
7. **6f — Phase 5: Rei (measure 3).** **Blocked: `:rei-service` does not exist** — it's an unsized
   separate track ([`tenshu-rei.md`](tenshu-rei.md)). When it lands: proxy-side REL evaluation of
   the iOS app's `Info.plist`, `BundledReiSource` (first-party) vs `OnlineReiSource` (third-party),
   sequenced per O5. Measure 2 (attestation) is independent and already done.

## Prerequisites (outside the repo's control)

- **Apple Developer Team ID + App ID + App Attest entitlement.** (A free team `FAYT82GWD7` exists
  for the desktop MasterKeyStore cert OU, but App Attest needs a real registered App ID.)
- **An Xcode project / toolchain** to consume `TenshuSdk.xcframework` and host the Swift layer.
- **A macOS build host.** `ios.yml` already targets `macos-latest`, but CI is currently
  **billing-blocked** (verify locally; see the "CI blocked by GitHub Actions billing" history).

## Open questions still live (resolve as the relevant slice is picked up)

- **O1 — Co-resident transport.** Foreground loopback fast-path vs always relay-brokered (relevant
  to 6e). Deferred from Phase 3 deliberately.
- **O3 — Data-plane exceptions.** Which first-party iOS surfaces (if any) get the in-process
  master-key path vs proxy-mediated EPC (§4) at v1. **Proposal §9.4 sharpens this:** the
  load-bearing exception is a *content key* (not the master key) so bulk uploads can ride a
  background `URLSession` — interactive EPC can't run while the app is suspended. **v1 resolved
  (§9.5): Option A foreground-only — no content key, no threat-model change; Option B (content-key
  exception + background `URLSession`, the Photos/OneDrive model) deferred to when
  survives-backgrounding upload is built.**
- **O4 — Background liveness budget.** `BGTaskScheduler`/push wake windows ↔ EPC session liveness;
  the degraded (no reachable proxy) UX. Core to 6e. **See proposal §9.4** — continuous background
  sync is not achievable on iOS; Synced-Folders auto-backup must be reframed, not ported.
- **O5 — Rei ordering.** Only measure 3 (6f) is gated on Rei; measure 2 is done.

Also note the **relay JVM-21 vs KMP-17 split** (tech-debt: "Relay Bytecode Target Floats With the
Gradle JDK") — a JVM-17 module still cannot depend on `:sync-relay-server`, so any Phase-6 test that
needs the live relay belongs in `:system-test-utils` (JVM 21), as the Phase-3b test does.

---

## Kickoff prompt (paste into Claude Code)

> I'm picking up **Phase 6** of `docs/proposals/ios-proxy-architecture.md` — Control-on-iOS + the
> first iOS app. **Read first:** `docs/proposals/ios-proxy-phase6-handover.md` (this note),
> `docs/proposals/ios-proxy-architecture.md` (Appendix — the Phase-1→4a as-built sections + the
> "Later phases" Phase 5/6 bullets), the EPC code in `:core-common` `org.tenshu.core.common.epc`,
> the rendezvous client/acceptor in `:sync-service-proxy` `org.tenshu.sync.proxy.epc`, the iOS
> crypto stubs in `core-common/src/iosMain/.../security/CryptoBackendsModule.ios.kt`, and House
> Rules #1–#7 in `AGENTS.md`.
>
> **Before writing code, confirm scope with me** — Phase 6 is large and Apple-gated, so propose the
> first slice (likely **6a**: iOS app module + CryptoKit crypto actuals + `ktor-client-darwin`) and
> the decisions in O1/O3/O4 before starting. **Stop and ask** rather than silently: standing up an
> Xcode project / signing identity, exporting `-proxy` to the iOS umbrella (House-Rule-#1-adjacent
> packaging call, 6b), or building the real Apple App Attest verifier without sample vectors to test
> against. Keep the Phase-1 umbrella rule (no per-module frameworks) and the Phase-2/4a "real impl
> swaps in behind the existing seam, no protocol change" property. Operations stay non-`suspend` at
> the interface level (`IoOperation` + `TaskInteractor`, ADR 0012). Threat-model amendments ride the
> same PR as the code (House Rule #2) — Control-on-iOS background-wake + any data-plane exception
> (O3) will touch the trust model.
>
> **When done with a slice:** open a PR (squash; the repo auto-deletes branches), verify by running
> the affected tests (House Rule #7 — note that on-device/Xcode verification may be impossible in a
> headless session, so say so explicitly), update the proposal Appendix with the as-built notes, and
> update this handover's "Status going in".
