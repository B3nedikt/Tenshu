# Handover — Drive-on-iOS live-client wiring (Phase C/D)

**Audience:** a fresh session picking up the iOS Drive app. **Goal:** replace the fake
repositories that back every Drive-on-iOS screen with the **real `TenshuClient`** running over
an EPC channel to a Control, so the screens drive real sync data instead of canned fixtures.

This doc is the canonical entry point. The deep background is
[`ios-proxy-architecture.md`](ios-proxy-architecture.md) — read its **§3c-* as-built log**
(the running per-slice journal, near the end) and **§9.3** (the share-VMs/repos, native-UI
decision) and **§10/§11** (EPC pairing + Control-on-iOS). This handover assumes that context and
only states what's specific to the live-client step.

---

## 1. Where things stand (2026-06-24)

The full-parity Drive UI is **built and bound to fakes**. Every screen resolves a real
`:drive-shared-logic` view model over an **injected** repository; on iOS the injected impl is a
fake today. The sharing cluster (share-link, manage-access, shared-with-me, invite-link,
completion-prompt, redeem, scan-to-redeem) is complete + `XCUITest`-covered.

The **EPC transport + real client assembly already exist** — they're just not yet the default
data source for the app:

- `EpcDriveClient.assembleClient(channel)` (`:tenshu-sdk-ios` iosMain) hand-wires a **full real
  `TenshuClient`** over any `TaskTransport` (every service `-common`/`-client` schema config +
  `RemoteBinaryTaskExecutor` over the channel + `DefaultTenshuClient.init()`), and a real
  `TenshuFilesRepository`. **Returns only `tenshuClient` + `filesRepository` today.**
- `DriveReadSurface.installRealClient(channel)` calls that and installs the real
  `FilesRepository` into the `installed` slot the read/detail/mutation surfaces bind.
- `EpcRelayPairing.connect(pairingUri)` (iosMain) runs the **real** relay-backed EPC handshake
  (libsodium crypto, Darwin HTTP rendezvous client, anonymous-by-capability) and returns a live
  `EpcRendezvousChannel` + its 6-digit SAS. This is the `TaskTransport` `assembleClient` wants.

So the spine exists: `EpcRelayPairing.connect(uri)` → `channel` → `installRealClient(channel)` →
real `FilesRepository`.

> **Update (2026-06-24): Tier 1 shipped.** `assembleClient` now also builds
> `TenshuContactsRepository(client)` + `TenshuMembershipRepository(client)`; `DriveReadSurface`
> holds install slots for all three (`installSharingRepositories(...)`) and `installRealClient`
> installs them in one call; `DriveShareSurface`'s parameterless prod entry points bind the
> installed repos instead of hardcoded fakes (fakes stay the default, so all iosTest + XCUITests
> stay green). **Gap 1 below is closed.** Only the channel-lifecycle gap (Tier 2, §4) remains.

### Verified facts to rely on
- `TenshuContactsRepository(client)` and `TenshuMembershipRepository(client)` each take **only a
  `TenshuClient`** (`drive-shared-logic/.../data/ContactsRepository.kt:180`,
  `MembershipRepository.kt:106`). Android/desktop build all three in
  `drive-shared-ui/.../di/FilesRepositoryModule.kt` — mirror that, not a new design.
- The app is **Control-gated**: `RootView` (`drive-ios/Sources/RootView.swift`) routes on
  `ControlSessionStore.current()` — present → `PairedAppView`, absent → `ConnectToControlView`.
  Today `ControlSession` carries only a `String handle`; the DEV_HARNESS dev-stub
  (`DRIVE_DEV_PROVISION_CONTROL=1`) fakes "connected" with no real channel.
- **Control-on-iOS does not exist** (that's [`ios-proxy-architecture.md`] Phase 6). The "Control
  serving EPC" for the on-device round-trip is a **desktop or Android Control** over the relay
  rendezvous (`EpcRendezvousAcceptor`, slice 2b — already shipped).

---

## 2. The two gaps

1. **Contacts + membership repos are not wired to the real client.** `EpcDriveClient.assembleClient`
   builds only `filesRepository`; `DriveShareSurface` (the whole sharing cluster) **hardcodes
   `FakeContactsRepository()` / `FakeMembershipRepository()`** at every prod entry point
   (`DriveShareSurface.kt` lines 37–38, 55, 62, 77, 93). So even with a live `FilesRepository`,
   sharing screens stay on fakes.
2. **No channel lifecycle.** Nothing calls `EpcRelayPairing.connect` from the app, persists the
   pairing material, reconnects on relaunch, or calls `installRealClient`. `RootView`'s
   `.connected` state is reachable only via the dev stub. This is the "6e seam"
   ([`ios-proxy-architecture.md`] §10.4) and is the **on-device-gated** half.

---

## 3. Tier 1 — wire all three repos (✅ shipped 2026-06-24)

Mechanical, fully verifiable in the simulator without a Control. Keep fakes as the default so the
existing tests + XCUITests stay green.

1. **`EpcDriveClient.assembleClient`** — extend `DriveClient` to also hold
   `contactsRepository` + `membershipRepository`, and build them from the same `client`:
   `TenshuContactsRepository(client)`, `TenshuMembershipRepository(client)`. One-arg ctors; the
   Koin graph already configures `contactServiceCommon/Client` + `syncServiceCommon/Client`.
2. **Install seam for all three.** `DriveReadSurface.installed` only holds the `FilesRepository`.
   Add installed slots for contacts + membership (either extend `DriveReadSurface` or give
   `DriveShareSurface` its own `installRepositories(...)`), defaulting to the fakes. Have
   `installRealClient(channel)` install **all three** from the one `assembleClient` result.
3. **`DriveShareSurface`** — replace the hardcoded `FakeContactsRepository()` /
   `FakeMembershipRepository()` at the parameterless prod entry points with the installed repos
   (keep the explicit-deps overloads for tests — the iosTest suite calls those).
4. **Verify:** `:tenshu-sdk-ios:iosSimulatorArm64Test` (VM/fakes unchanged) + **both xcodebuild
   schemes** (`DriveIos`, `DriveIos-Dev`) + the `DriveIos-UITests` scheme. All should stay green
   because the default is still fakes. This tier ships behind the existing seam with no behaviour
   change — it just makes `installRealClient` complete.

After Tier 1, a single `installRealClient(channel)` swaps the **entire** app (files + sharing) to
the live client.

## 4. Tier 2 — channel lifecycle (dev-path wiring shipped; round-trip on-device gated)

Needs a live Control over a reachable relay; the simulator handshake fails at the transport
(connection refused) by design, so the **round-trip** can't be CI'd or sim-smoked — that's the
**on-device gate**. The *wiring* below is headless-buildable and shipped 2026-06-24.

> **Update (2026-06-24): dev-path channel lifecycle wired + the persist decision settled.** A single
> `LiveClientSession` (`:tenshu-sdk-ios` iosMain) owns the channel for the app session:
> `EpcRelayPairing.Channel.activate()` → `LiveClientSession.activate(...)` → `installRealClient` across
> all three repos + retains the channel; `teardown()` calls `channel.unbind()` (cancels the reader) and
> `DriveReadSurface.installFakes()`. `EpcPairingView` now adds a confirm/reject SAS step (confirm →
> `activate()`, mismatch/disconnect → `teardown()`) and an *active* card; on its `liveSessionChanged`
> notification `RootView.recheck()` (DEV_HARNESS) routes the App tab into `PairedAppView`. Server-push
> needs **no** separate consume scope — `RemoteBinaryTaskExecutor` (inside `assembleClient`) binds the
> channel's `onCallback`, so request/response *and* pushes ride the one retained channel; teardown is
> just `unbind()`. **What's still gated:** §4.4 round-trip (hardware) and the shipping local-channel
> provisioning (§4.2, Phase 6e). `ConnectToControlView` is unchanged (still forward-to-Control).

1. **Onboarding → pairing (✅ dev path).** `ConnectToControlView` stays a forward-to-Control
   placeholder (§11.6: a first-party app does *not* run its own QR/SAS ceremony). The pragmatic
   bring-up path is the **DEV_HARNESS EPC pair surface** (`EpcPairingView` + `EpcRelayPairing`)
   against a desktop/Android Control: scan/paste the `tenshu://epc/pair#…` URI → `connect(uri)` →
   confirm SAS → `activate()` (= `installRealClient`). Done; the live round-trip is §4.4.
2. **Persist + reconnect — DECISION: session-lived, re-pair each launch, persist nothing.** The
   channel is **not resumable** and we deliberately store **no** pairing material:
   - The EPC session keys are *ephemeral* (X25519 ECDH per handshake) and the `/sync/epc` rendezvous
     mailbox is *one-shot* (mirrors `/sync/invite`+`/sync/grant`) — there is nothing to "reconnect"
     to, and re-running `connect` against a torn-down rendezvous returns `NoSession`.
   - Persisting the high-entropy `pairingSecret` to silently re-handshake would be a **bearer-secret
     at rest** — a threat-model regression — for no gain (the rendezvous is gone anyway).
   So `ControlSession` stays a `handle` (not the seed): the **dev path re-pairs each launch**, and the
   **shipping path** (§11.6) rides a Control-provided *local* channel via the shared Keychain / App
   Group (Phase 6e), re-read each launch with no secret on disk. When 6e lands, it calls
   `LiveClientSession.activate(...)` with that local transport — the same seam — and `RootView`'s
   shipping branch can drop its DEV_HARNESS gate.
3. **Lifetime + teardown (✅).** The channel lives for the app session, owned by `LiveClientSession`;
   `teardown()` cancels the channel's reader scope (`unbind()`) and restores the fakes. *Auto*-detect
   of a relay drop / `NoSession` (the channel fails pending calls today) → surfacing a reconnect is a
   small follow-up; the manual *Disconnect* affordance + fake-fallback is wired.
4. **On-device round-trip verification** (the real acceptance test): pair Drive-iOS ↔ a
   desktop/Android Control over the dev relay; create a folder on one, see it on the other;
   run an invite handshake end to end. This is a maintainer/hardware task.

---

## 5. Gotchas (learned this cycle — don't relearn)

- **Run `xcodebuild`, not just the Kotlin test.** `iosSimulatorArm64Test` compiles the VMs/fakes,
  **not the SwiftUI** — a Swift-only break passes it and merges silently (it happened: a
  `Section("title"){} footer:{}` error shipped and was caught only by the next PR's build). Build both schemes; for UI,
  run `DriveIos-UITests`. See memory `feedback-ios-xcodebuild-swift-slices`.
- **SKIE doesn't surface Kotlin default args.** Call the full overload from Swift (e.g.
  `vm.onLinkEntered(link:)`, not the defaulted `onLinkEntered()`). Sealed Kotlin types bridge via
  `switch onEnum(of: value)`.
- **Simulator has no camera** (QR scan) and **no real relay** (EPC handshake refuses) — both are
  on-device-only. The fakes + paste paths are what's sim-testable.
- **SwiftUI List rows are static text, not buttons** in XCUITest — query `app.staticTexts[name]`.
- **CI is billing-blocked** — verify locally; merges are non-blocking.
- **Local `master` drifts** (concurrent maintainer git / worktree agents). Before branching:
  `git fetch origin && git switch -C master origin/master`; before each PR push, rebase onto
  `origin/master` (it moved every slice this cycle).
- **Generated Xcode artifacts are gitignored** (`DriveIos.xcodeproj/`, `Info.plist`, `build/`) —
  `project.yml` is the source of truth; `xcodegen generate` after editing it.

## 6. Build / test quick reference

```bash
# Kotlin VM/fake tests
./gradlew :tenshu-sdk-ios:iosSimulatorArm64Test

# iOS app builds (run from drive-ios/; assemble the XCFramework once up front)
cd drive-ios && xcodegen generate
(cd .. && ./gradlew :tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework)
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos      -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos-Dev  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build build

# Sharing-cluster XCUITests (needs a booted sim; provisions PairedAppView via launch-env)
xcodebuild -project DriveIos.xcodeproj -scheme DriveIos-UITests -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath build test
```

## 7. Key files

| What | Path |
|---|---|
| Real client assembly over EPC | `tenshu-sdk-ios/src/iosMain/kotlin/org/tenshu/sdk/ios/EpcDriveClient.kt` |
| Files install seam | `…/sdk/ios/DriveReadSurface.kt` (`installRealClient`, `installRepository`) |
| Sharing surfaces (hardcoded fakes → fix in Tier 1) | `…/sdk/ios/DriveShareSurface.kt` |
| Real repos (one-arg, take `TenshuClient`) | `drive-shared-logic/.../data/{ContactsRepository,MembershipRepository,FilesRepository}.kt` |
| Android/desktop DI to mirror | `drive-shared-ui/.../di/FilesRepositoryModule.kt` |
| Real relay EPC pairing | `…/sdk/ios/EpcRelayPairing.kt` |
| App root / Control gate | `drive-ios/Sources/RootView.swift`, `ControlSession.swift` |
| Suggested first PR | Tier 1 (repos wired, fakes still default) — small, green, no on-device gate |

## 8. Suggested order

1. **Tier 1** as one PR (repos wired through `assembleClient` + install seam + `DriveShareSurface`;
   fakes stay default; all tests + both schemes + UITests green). Low-risk, fully headless.
2. **Tier 2** as a separate track, paired with a desktop/Android Control on the dev relay for the
   on-device round-trip. Expect to evolve `ControlSession` + `RootView` and settle the
   re-handshake-vs-persist decision (write it down here when you do).
