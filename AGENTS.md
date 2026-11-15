# Tenshu — Project Notes for coding agents

Tenshu is an open-source app platform built on Kotlin Multiplatform with end-to-end encrypted
sync between user devices. The first apps are **Drive** (file management) and **Control**
(the proxy / control plane that mediates everything else); the platform supports Android,
Desktop and iOS today and is being extended to web. The sync relay server lives in this
repository as `:sync-relay-server`.

You are joining a project where most of the architectural design has been written down. Before
making decisions, **check the relevant doc first** — most questions are already answered.

---

## Read these first

- **[`docs/agent.md`](docs/agent.md)** — top-level platform conventions. Module map, Service &
  Operation pattern, schema DSL, ViewModel and DI patterns, naming. Read this before changing
  anything in `:core`, `:core-common`, or any feature service.
- **[`docs/sync/threat-model.md`](docs/sync/threat-model.md)** — the security contract. Every
  change touching sync, pairing, recovery, or the relay must preserve the invariants here.
  Modifications to assets A1–A4 or A9, or to the sync protocol, require a threat-model
  amendment in the same PR.
- **[`docs/milestones.md`](docs/milestones.md)** — what's in development, what's planned, and
  the dependencies between them.
- **[`docs/tech-debt.md`](docs/tech-debt.md)** — known issues, sized engineering tasks, and
  things to be careful about across the codebase.
- **[`docs/README.md`](docs/README.md)** — the curated index; complete for the stable sets, a
  subset of the 167 proposals. The two tables below are a *routing* surface, not a status
  report; when you need the state of a track, the doc itself is the source of truth.

Module-specific conventions live in `*-agent.md` files inside each module (e.g.
`core/core-agent.md`, `ui-core/ui-agent.md`). Read those when working in the corresponding
module.

---

## In-flight architectural work

Work with unshipped phases or an undecided question. Each row is a pointer plus the one thing
you could still get wrong; **the linked doc carries the reasoning, the phase map and the
as-built notes**. Rows are capped at one line — see *Keeping this file small* below.

| Track | Doc | The trap |
|-------|-----|-----------|
| Sync relay hardening (device-key JWTs; `SyncMailbox`) | [`sync-backend-relay-plan.md`](docs/proposals/sync-backend-relay-plan.md) | ⚠ §1's named stack and its Ph4 status are both stale — `sync-relay-persistence.md` is the backend truth. |
| Chain DSL — entity-aware `search` / `select` | [`core-chain-entity-typing.md`](docs/proposals/core-chain-entity-typing.md) | Entity-aware end-to-end; `ClientConnection.tokenize` is the Android SHM/PFD seam. |
| Preference service — synced per-app / per-device settings | [`core-preference-service.md`](docs/proposals/core-preference-service.md) | `RealmSetting`/`DeviceSetting` over `genericData`. ⚠ §3.1/§4.1–§4.3/§5 describe designs that did **not** ship — read the doc header. |
| Logging facade — level-gated `Log` over `:logging-service` | [`core-logging-facade.md`](docs/proposals/core-logging-facade.md) | ⚠ **The facade is not built**, and is superseded-in-part: a device-local single-stream `:diagnostics` shipped instead (§0), with §0.1 the convergence plan — read both before building the two-stream design. |
| Shared-realm file model — folder sharing | [`drive-shared-realm-file-model.md`](docs/proposals/drive-shared-realm-file-model.md) | Two links: `SharedFile` = content inside a realm, `Placement` = the per-member mount; read surface is dedicated ops, **not** the chain DSL (§6). |
| Commerce & billing plane — paid storage | [`commerce-billing-plane.md`](docs/proposals/commerce-billing-plane.md) | The commerce↔relay bridge is a signed entitlement token, so account lifecycle stays entirely in commerce (D5). |
| Commerce end-to-end dry run — one flow before Stripe | [`commerce-end-to-end-dry-run.md`](docs/proposals/commerce-end-to-end-dry-run.md) | Runbook: `how-to/commerce-dry-run.md`. ⚖️ Counsel owes the withdrawal acknowledgement. |
| Publishing / commerce reconciliation | [`publishing-commerce-reconciliation.md`](docs/proposals/publishing-commerce-reconciliation.md) | Two coexisting entitlement carriers (synced record + bearer token) with relay-side enforcement; D1–D6 locked. |
| Commerce accounts & sessions — registration, self-MoR billing | [`commerce-accounts-and-sessions.md`](docs/proposals/commerce-accounts-and-sessions.md) | As-built §9, decisions D1–D15 §8. ⚖️ Ph4b IAP gated on counsel + Apple. |
| Retention & quota model — durable content vs local cache | [`retention-quota-model.md`](docs/proposals/retention-quota-model.md) | ⚠⚠ §2's three-tier taxonomy is the load-bearing part: conflating durable content with the delivery log deletes user data. ⚠⚠ **P3 is LIVE**: a closed account's realm is deleted after 30 d unless an open one holds it. ⚖️ Owes counsel sign-off. |
| iOS Drive parity — productize route-1 | [`ios-drive-parity.md`](docs/proposals/ios-drive-parity.md) | In-process runtime over the shared replica, durable custody, preview, background drain (as-built §6–§24.5). ⚠ **P7** — App Attest, real-device signing, TestFlight — is the ship gate; runbook §19. |
| iOS parity for Fit, Timeline and Maps | [`ios-fit-timeline-maps-parity.md`](docs/proposals/ios-fit-timeline-maps-parity.md) | ⚠ MapKit is never the *default* renderer (A35) — [election-only](docs/proposals/ios-apple-maps-option.md). Standing rules D1–D12 in §8. |
| Timeline movement capture — the *trip* between dwells | [`timeline-movement-capture.md`](docs/proposals/timeline-movement-capture.md) | ⚠⚠ **§32 is the live-defect list, and §32.8 falsifies two of §32.4's entries — read §32.8 first.** Classifier v14, tier stamps 20/23/24 (⚠ retired 10/12/22 never reused, §32.15/§39). |
| Control-on-iOS — notification center + shares | [`ios-control-notifications-and-shares.md`](docs/proposals/ios-control-notifications-and-shares.md) | ⚠⚠ Plist keys live in **`project.yml`** — `Info.plist` is generated + gitignored. ⚠⚠ Not-connected is a **failure, never an empty success**; fakes are opt-in, never a default. |
| Ordered-window fusion — top-N pushdown | [`core-ordered-window-fusion.md`](docs/proposals/core-ordered-window-fusion.md) | Four key kinds fuse (§8). ⚠⚠ A sorted listing observes the **projected** chain: a bare `Folder.files` diff sees `path` alone, so an overwrite is never served (§6). |
| Inference & voice — classification, embeddings, dictation | [`inference-service.md`](docs/proposals/inference-service.md) | ⭐ Settings→Models is the only route to a model; the scheduled indexer needs idle+charging, *Index now* keeps only battery-not-low (§34). ⚠⚠ **Labels INERT** three ways (§37): empty vocabulary, no backend claims tagging, `tagImage` returns nothing. ⚠⚠ A service ships when in the proxy's schema list (§17). ⚠⚠ Two-switch consent, absence = NO. ⭐ IN-3m: 121ms/img. |
| Cross-app open — a cited row opens its owner | [`control-cross-app-open.md`](docs/proposals/control-cross-app-open.md) | ⚠⚠ The address is a **`DataId`**, never an app (CO-D1); on iOS convention only (§5i.2). ⚠⚠ Android: Control may neither launch nor raise (§5e.1). ⚠⚠ A receiver **replaces** its back stack (CO-D11). ⚠⚠ A table line with no receiving route is worse than none (§5g.2). ⚠ Barely device-verified. |
| Mia query layer — an utterance becomes a chain | [`mia-query-layer.md`](docs/proposals/mia-query-layer.md) | The planner is a grammar and **there is still no model**. ⚠⚠ Read-only is **enforced, not inherent**. ⚠⚠ A refusal returned as `ANSWERED` renders as "nothing matched" (§14.1). ⚠⚠ A model may pick the INTENT, never the slots and never a write (§16.1/§16.2). |
| Photos videos — the `MOTION` class | [`photos-video-support.md`](docs/proposals/photos-video-support.md) | ⚠⚠ `MOTION` never joins `GetPreview`'s ladder. Backfill → `drive-synced-thumbnails.md` §15.11. |
| Takeout import & recall (Track B) | [`takeout-import-and-recall.md`](docs/proposals/takeout-import-and-recall.md) | The **zip** is the unit of import and the router decides by **content, not path** (Google localises the folder names). ⚠⚠ Nobody has run any of it against a real export on a real phone, and `importFiles` still holds every timeline file's bytes at once. |
| Schema-skew surface — telling the user to update | [`core-schema-skew-surface.md`](docs/proposals/core-schema-skew-surface.md) | ⚠⚠ The app to name is **not** the app the banner is in: Android/desktop = Control, iOS route-1 = the app itself. ⚠⚠ A fenced device is **reachable** — gate on `reason`, not `state`. ⚠⚠ The Update button opens the **stale** binary's page, from that copy's own channel (§8.5). |
| Drive on the web — parity with KMP/iOS | [`drive-web-parity.md`](docs/proposals/drive-web-parity.md) | Browser uploads mint thumbnails. ⚠⚠ `GetPreview` has two call paths, only the dispatcher awaits — **nothing on web may project `File.Preview`** (§1.2i). ⚠ Android mints WebP, others PNG. |

---

## Shipped — constraints that outlive the work

Done, but each left behind a rule a later PR could break. Read the doc before changing the area.

| Track | Doc | The constraint |
|-------|-----|----------------|
| Schema evolution — versioned data, announce-gated sessions | [`core-schema-evolution.md`](docs/proposals/core-schema-evolution.md) | Ground facts + authoring rules in §2 + §8.4, and **House Rule 9 is the operative summary**. ⚠ Compaction is built but **never runs** — no production DI line (§8.3.7). JS persist path unverified. |
| Realm Data — schema-side data-type ontology | [`core-realm-data.md`](docs/proposals/core-realm-data.md) | `RealmData` is a fieldless **marker** trait; presentation lives in each consumer's registry, never on the trait. Marker-vs-base rationale in §§1–8. |
| Sync relay HTTP API | [`sync-relay-api.md`](docs/proposals/sync-relay-api.md) | The canonical relay wire surface — every other doc defers to this one. ⚠ `LazyBlock` / `GET /sync/block/{id}` were designed but never built. |
| Error channel — typed `TError` + `:logging-service` | [`core-error-channel.md`](docs/proposals/core-error-channel.md) | Ops return `T` and throw typed errors; the catch-all is confined to `LocalTaskExecutor`. Outstanding orthogonal items live in `tech-debt.md`. |
| Contact service & invitation handshake | [`contact-invitation-handshake.md`](docs/proposals/contact-invitation-handshake.md) | **No directory**: identity is acquired only via a mutual invitation handshake (link/QR), sealed-box over a relay mailbox, and `K_link` never reaches the relay. Threat model A24/A25, §8.13. |
| Shared-realm membership grant — cross-user join | [`shared-realm-membership-grant.md`](docs/proposals/shared-realm-membership-grant.md) | ⚠ There is no GR-5 — it split into GR-6/GR-7. ⚠ §11.1 supersedes D2: the **owner** mints A26 and ships the private half sealed. Security posture: threat model A26/A27, §8.14, T-GR-02 → Low. |
| Drive on the web | [`drive-web-app.md`](docs/proposals/drive-web-app.md) | Thin-SDK + TS rebuild over the apex bridge; ⚠ **the fork is locked** (§2). Drift since → `drive-web-parity.md`. |
| Deferred properties — `@defer` for expensive projections | [`core-deferred-properties.md`](docs/proposals/core-deferred-properties.md) | `deferred = true` on `dynamicProperty` + per-select `eager(…)`/`deferred(…)`; the authoring reference is `schema-dsl.md`. ⚠ `valueProperty` never got the parameter (§10.2) — §7 and §3.1's examples are wrong. |
| Drive content preview — PDF / video / docs | [`drive-content-preview.md`](docs/proposals/drive-content-preview.md) | Image, PDF page and video poster all reduce to an `ImageBitmap` (§2); playback is the one thing that breaks the pattern (§5). Streaming is its own doc, `drive-streaming-preview.md`. Owes iOS viewers. |
| Content-addressed file content | [`file-content-addressing.md`](docs/proposals/file-content-addressing.md) | Move/rename/copy are pure metadata, plus the generic `DataId`→ref-migration primitive (§3.3.4). ⚠ A relay-*derived* refcount is HR#1-impossible; a client-**declared** one shipped — `content-plane-refcount.md`. |
| Compose Multiplatform upgrade | [`cmp-version-upgrade.md`](docs/proposals/cmp-version-upgrade.md) | CMP 1.12.1 + BOM 2026.09.00 (⚠ bump the pair together). ⚠ Never re-introduce the `force()`/`exclude()` blocks — `tech-debt.md` *CMP Version Pin* is the copy to trust. |
| Store resident-set ceiling — bounded mailbox + read-through | [`bounded-store-cache.md`](docs/proposals/bounded-store-cache.md) | Closes the ~8k–12k thumbnail ceiling by construction. ⚠ The JS twin is **deliberately forked** — `IndexedDbObjectStore` cannot do synchronous read-through at all. ⚠ No store size-tiers any more (iOS's SQLite store has no row cache). |
| Task-scoped sync lane + composite | [`core-task-composite.md`](docs/proposals/core-task-composite.md) | 1 task == 1 lane == 1 checkpoint block, via `DeltaBatchPayload` + an emit-boundary lane (`checkpointSyncLane`; no `SyncLane` type) + `TaskSpec`/`withTask`. ⚠ `core-background-processing.md`'s header is stale; §10.6 is truth. |
| iOS first-party route-1 migration | [`ios-first-party-route-1-migration.md`](docs/proposals/ios-first-party-route-1-migration.md) | Drive, Fit, Timeline and Maps are all on route 1 (§9 is canonical); EPC stays for third-party + remote proxy (§8). ⚠ Decision C — first-party apps are realm co-owners on iOS, no inter-app least privilege — is recorded in the threat model at A4. |
| Client resubscription after a proxy restart | [`core-client-resubscription.md`](docs/proposals/core-client-resubscription.md) | Reactive IPC reads (listings, storage, sync health, membership) survive a proxy restart, and this is the reference for `TaskTransport` connection semantics. Open: Q3 thundering herd, Q4, Q6. |
| Paged listings keep their place — `cachedIn` | [`core-paging-scroll-restoration.md`](docs/proposals/core-paging-scroll-restoration.md) | ⚠⚠ The three reactivity guards are the live contract: **one goes red ⇒ the ban is back — put `cachedIn` away rather than weaken the test.** ⚠⚠ On K/N `viewModelScope` is `Main.immediate` (§3.1). |
| Apple Maps as a user-elected iOS renderer | [`ios-apple-maps-option.md`](docs/proposals/ios-apple-maps-option.md) | ⚠⚠ MapKit renders the **three heroes only**, behind the off-by-default election, per **app** not per device (D9/D10) — never the region picker or Android. ⚠ macOS needs no Maps entitlement; claiming one is the SIGKILL (§8.1). ⚠ Both renderers share `BASEMAP_MAX_ZOOM` — never spell it twice (§8.2 F5). |
| Forward-to-Control surface — one route back | [ADR 0025](docs/adr/0025-forward-to-control-surface.md) | Widens [ADR 0022](docs/adr/0022-ios-forward-to-control-surface.md) off iOS. ⚠⚠ Posture follows the **reason**, never the platform. ⚠⚠ Presence and store route are both tri-state — silence read as absence tells users to install what they have, and a store link is offered only to a copy that came from that store. |
| Merger field reads — the lazy-vs-materialised seam | [ADR 0024](docs/adr/0024-merger-field-resolution.md) | A `SemanticMerger` reads a collection field with `resolveListField`, **never** `getField` — `merge`'s two sides arrive in different representations, so a direct read silently drops members. ⚠ Settles the read only; removal still comes from `purgeDeadMembers` + tombstones. |
| Content-plane transfer concurrency | [`content-plane-transfer-concurrency.md`](docs/proposals/content-plane-transfer-concurrency.md) | Chunk `PUT`s overlap (`mapChunksBounded`, N=4) because a serial loop against the single-region relay is RTT-bound. ⚠ Emit residency is now `O(N × CHUNK)`; `chunkTransferConcurrency = 1` is the kill-switch. |

---

## Sync proposal cluster

[`sync-merge-strategies.md`](docs/proposals/sync-merge-strategies.md) — trait-based merge
configuration; renames `SemanticMergerRegistry` → `MergerRegistry`. ⚠ It and `sync-relay-api.md`
each carry an **Implementation Notes for Claude Code** appendix (§5b / §8b): that is the
canonical handoff section, and everything before it is architectural context.

---

## Build & test

⚠⚠ **`./gradlew test` is NOT the test suite.** Almost every module is KMP and `test` matches
**none** of their test tasks — it runs **0** `jvmTest`/`desktopTest` (only the 8 Android
`testDebugUnitTest` ones), and is green even when KMP tests would fail. Use the first command
below, always.

⚠⚠ **The `./gradlew` line under it compiles no iOS and no JS — `run-gate.sh` closes that itself.**
Since 2026-09-01 the script also runs the two lanes (`compileTestKotlinJs`,
`compileTestKotlinIosSimulatorArm64`), because a gate covering three of five targets is the same
class of lie as an UP-TO-DATE test. Each lane had shipped broken to master behind a green gate — an
`iosMain` import (2026-08-13) and five JS/iOS breaks at once (2026-09-01).

⚠⚠⚠ **Still uncovered: Swift** (only `scripts/ios-smoke.sh` compiles any), **and the iOS lane off
macOS** — K/N cannot cross-compile an Apple target, so the script prints *"iOS lane NOT VERIFIED"*
rather than passing. ⚠ `iosKotlinCheck` is not the lane check: it reaches only what
the `:tenshu-sdk-ios-*` modules + `:ui-core` reach (`tech-debt.md` *"The JS and iOS lanes had no local gate"*).

```bash
# All tests — the JVM/desktop/Android gate. `test` alone silently skips every KMP module.
# ⚠⚠ RE-running this reports BUILD SUCCESSFUL having executed ZERO test tasks (all UP-TO-DATE).
# Use the script when a green must mean something — it clears results and asserts tasks ran.
scripts/run-gate.sh          # `desktopTest jvmTest test testAndroidHostTest` + the JS/iOS lanes
                             # ⚠⚠ `testAndroidHostTest` is NOT reachable from `test` — omitting it
                             # hid 219 test classes and let a widened AIDL break master.
scripts/run-gate.sh --quick  # changed modules + dependents; ⚠ full before shared merges/syncs

# Every `iosMain` the SDK graph REACHES — not all of them, see above. NOT covered by the gate; ~45 s.
# ⚠⚠ macOS ONLY, and it REFUSES elsewhere rather than lying: K/N cannot cross-compile an Apple
# target, so an iosMain change written in a Linux container is UNVERIFIED — say so, don't cite the
# gate. docs/tech-debt.md "iosKotlinCheck was a no-op off macOS".
./gradlew iosKotlinCheck

# The two lanes WITHOUT the test gate (run-gate.sh runs both already).
# ⚠⚠ `--continue`, or Gradle stops at the first failure — that is how five breaks read as one on
# 2026-09-01. ⚠ The iOS one is macOS-only and reports success having compiled NOTHING elsewhere,
# which is why the script guards it by `uname` and not by the exit code.
./gradlew compileTestKotlinJs --continue
./gradlew compileTestKotlinIosSimulatorArm64 --continue

# Every module's iOS simulator TEST suite; NOT in the gate above. macOS-only (refuses elsewhere).
# Pins the iOS crypto backends against JVM vectors. ⚠ No HTTPS in its harness: docs/tech-debt.md.
./gradlew iosTestCheck

# The only thing in this repo that compiles SWIFT (assembles the XCFramework first, so it
# supersedes iosKotlinCheck — and is much slower). One app, or all ten if none is named.
scripts/ios-smoke.sh --build-only fit

# Specific module — KMP modules have `jvmTest` / `desktopTest`, NOT `test`.
# (`./gradlew :sync-service-proxy:test` does not exist and fails with "task not found".)
./gradlew :sync-service-proxy:jvmTest
./gradlew :core-common:desktopTest

# Run desktop variants
./gradlew :drive-desktop:run
./gradlew :control-desktop:run

# Local sync relay (dev mode; binds 0.0.0.0:8080)
./gradlew :sync-relay-server:run

# Build everything
./gradlew assemble
```

For Android, run from Android Studio / IntelliJ. The Drive and Control apps install onto the
same device together — Control is the proxy, Drive talks to it via Android Binder.

**Android smoke gate** (House Rule #8): `scripts/android-smoke.sh` boots the emulator, builds +
installs Control + Drive + Maps + Home + Calendar + Timeline + Mia + Photos, launches them,
and fails on any crash / ANR. It also asserts **schema lockstep** across installed apps (HR #9). Run it for
any PR touching `androidMain`, `:control`, or `:drive-android`.
⚠⚠ **Add a new first-party app to the built set the day it exists — but at least one must
always stay un-built** (Fit, Contacts today): an app the gate reinstalls can never be caught
out of step, so the un-built ones are the only ones whose lockstep assertions carry
information. Both halves have already cost real phases —
[`docs/testing.md`](docs/testing.md) §6 has the three cases, the canary caveat and what the
lockstep beacon actually checks.
⚠ **Its *build* step is flaky**: `*:packageDebug` fails ~1 run in 3, in a **different app module
each time**, and succeeds on retry with no change — `tech-debt.md` *"The Android smoke gate's
packaging step fails ~1 run in 3"*. ⚠⚠ Retry once; a second failure **on the same task** is not this.

⚠ **`compileSdk` is 37** (raised from 36 in the 2026-08-10 dependency sweep) — you need **Android
SDK Platform 37** installed or every Android module fails at `checkDebugAarMetadata`, not at
compile, with a message naming the *dependency* rather than the missing platform. The bump was
forced, not elective: `androidx.core:core:1.19.0` and `androidx.lifecycle:*:2.11.0` both declare a
compileSdk-37 floor in their AAR metadata, so holding 36 means freezing that whole line.
`minSdk` (24) and `targetSdk` (36) are deliberately **unchanged**, so this carries no on-device
behavioural change.

**Compose Multiplatform is on 1.12.1** + `compose-bom` **2026.09.00** — bump the pair together or
the Android and multiplatform runtimes split (the 1.7.3 pin was lifted 2026-07-08 — AGP 9 support
landed in CMP 1.9.3). The plugin manages the compose graph now; **do not re-introduce
`force()`/`exclude()` resolutionStrategy blocks or `fixComposeResourcesForAndroid` Sync tasks** —
those were the 1.7.3 workaround and are actively harmful at 1.9.x+. See `docs/tech-debt.md`
"CMP Version Pin" (RESOLVED) + [`docs/proposals/cmp-version-upgrade.md`](docs/proposals/cmp-version-upgrade.md).

---

## House rules

These have load-bearing reasons. If you intend to break one, flag it in the PR description.

1. **The relay sees ciphertext only.** `:sync-relay-server` is governed by the Zero-Trust
   Backend principle (whitepaper §"Data Privacy & Security Design Principles"). Server-side
   business logic that decrypts user data is a hard reject.
2. **Threat-model amendments come with the code change.** PRs that touch A1–A4, A9, the
   relay's data shape, or the sync protocol must amend `docs/sync/threat-model.md` in the
   same PR. Don't ship code that contradicts the threat model.
3. **Operations stay non-suspend at the interface level.** The Interactor pattern
   ([ADR 0012](docs/adr/0012-interactor-pattern.md)) is how IO ops express their async-ness.
   Don't add `suspend` to `Operation` interfaces; use the `IoOperation` trait + `TaskInteractor`.
4. **Schema-first for cross-platform data.** Cross-platform domain types live in a schema (in
   `:core`, `:core-common`, or the feature service module). Add to the schema DSL rather than
   ad-hoc data classes when the type needs to be wire-serialisable. See
   `docs/schema-dsl.md`.
5. **Web frontend is TypeScript + React, not Compose Multiplatform Web.** See
   `docs/web/architecture.md` §12.5 for the reasoning. The protocol layer is shared via KMP →
   JS; the UI is not.
6. **`LazyComposite` is stateless; `SchemaRegistry` owns per-context caching.** New
   `LazyDataCreator` implementations MUST NOT cache materialised state on the instance.
   Materialisation goes through `SchemaRegistry.getOrCreate(...)`. See `core/core-agent.md` §10.
7. **Tests over screenshots, screenshots over markdown for verification.** Don't claim a
   refactor works without running the affected tests. The `:core-test-utils` and per-module
   `*-test-utils` modules give you fixtures.
8. **Android-touching PRs pass the smoke gate.** Any PR that changes `androidMain`, `:control`,
   or `:drive-android` runs `scripts/android-smoke.sh` (boot emulator → install the eight built
   apps → launch → assert no crash/ANR) before merge. Kotlin unit tests and `compileDebugKotlin`
   miss DI-wiring / manifest / startup regressions; the emulator catches them. Truly
   device/network-dependent flows (real uploads, pairing, metered-network deferral) still need
   a maintainer's own device — the gate is smoke, not functional.
9. **Changed the stored-row or wire shape? Bump the owning service's version and ship its
   `SchemaMigration` in the same PR** (amended by schema-evolution **E2j-2**, 2026-07-30 —
   see below for what changed and why). Positional composites make even "additive" field
   changes store/peer-breaking, so a reshape is never free. Which version moves depends on
   who owns the type:
   - **A service-owned type** → bump `service(id, version = N)` and register the
     `SchemaMigration` step. ⚠ Do **not** bump `TENSHU_STORAGE_FORMAT_VERSION` for this: that
     constant is `:core`'s version alone.
   - **A `:core`-owned type** (no owning service) → bump `TENSHU_STORAGE_FORMAT_VERSION` and
     register a `schemaMigration(CORE_SERVICE_ID, …)` step, else the bump still wipes.
   - **Unsure whether you reshaped anything?** You do not have to be: `SchemaShapeBaseline`
     pins every composite's positional layout **and every constant's existence** (E2j-1b), so a
     reshape — or a new member of a sealed `TConstant` family — fails
     `SchemaShapeBaselineEnforcementTest` and names it. Regenerate the pinned file once the bump
     and migration are in place.
     ⚠ A **new** service passes that test for the wrong reason until its `-common` module is
     added to `:system-test-utils` — it is outside the baseline's graph until then.

   ⚠⚠ **Post-release, a wipe is data loss.** Every version restarted at **1** at the release
   cut (2026-10-27; old numbering: [`core-schema-evolution.md`](docs/proposals/core-schema-evolution.md)
   Appendix A). Never delete a step; only E4 compaction shortens a chain (§8.3). A bump with
   no step wipes every store — `ServiceMigrationChainsReachTheirVersionTest` catches it.
   **Clients** still move across a bump in lockstep.
   ⚠ **A `TENSHU_STORAGE_FORMAT_VERSION` bump no longer implies a relay redeploy** (E2e): the
   relay's 426 gate reads `RELAY_PROTOCOL_VERSION` (`core-common/.../RelayProtocol.kt`) — its
   *own* wire contract — and never looks at the payload version, because it stores ciphertext
   (HR #1). Coordinate a redeploy only when you bump `RELAY_PROTOCOL_VERSION`: envelope
   reshape, route semantics, auth, or blob-ref format. That gate accepts a `[min, current]`
   range, so even then there is no flag day. See §7.5/§7.8.

---

## Keeping this file small

This file is loaded into **every** session before the user says anything — by Codex directly, by
Claude Code through the one-line `CLAUDE.md` that imports it — so its size is a standing tax.
⚠⚠ **Codex silently truncates it past 32 KiB** (`project_doc_max_bytes`): overflow drops the
*end* of this file with no error, so the budget is a ceiling. And content added to `CLAUDE.md`
is invisible to Codex. It has been compacted before and grew straight back (~37 KB → ~52 KB in
two days), always the same way: a PR appends its finding to a track's row, and a routing table
silently becomes a changelog.

The rule: **a row holds the pointer plus the trap — never the narrative, and never the
status.** If a finding needs a second sentence to be understood, that sentence belongs in the
linked doc, which is where the next reader will actually look for it.

**Status is the half that rots, so it does not live here at all.** Most of `docs/proposals/`
carries a `**Status:**` line up top — that is its home, and a decaying field kept in two places
goes stale in the copy nobody maintains.

> **The test:** would this sentence change when a phase ships? It is status — put it in the
> doc. Would it still be true next year, and cost someone a day to get wrong? It is a trap —
> it belongs here.

Shrinking a row is therefore mechanical: **keep the ⚠/⭐/⚖️-marked clauses, drop the unmarked
phase bookkeeping.** A marked claim is a trap by construction, and it is what
`check-doc-claims.py` fingerprints.

**And a track earns a row only if it can break something silently.** A doc with no code cannot
— nothing built can contradict it — so design-only tracks are pure routing and live in
[`docs/README.md`](docs/README.md) instead. That is the entry rule.

Two gates:

- `scripts/check-agents-md-size.py` — enforces the budget (total size, per-row length, table
  well-formedness, and `CLAUDE.md` staying the bare import). Run it before committing here.
- `scripts/check-doc-claims.py --base master` — run this **when shrinking a row**. It reads the
  pre-edit rows from a git ref and checks their ⚠/⭐/⚖️-marked claims against the docs as they
  stand now, so it catches a claim that existed only here. ⚠ Necessary, not sufficient: it
  tests whether a claim's tokens appear in the target doc, not whether it is still true there.

---

## Repo layout (orientation)

```
Drive/
  AGENTS.md                    # this file
  CLAUDE.md                    # `@AGENTS.md` only — Claude Code's import; add nothing here
  agent.md                     # one-line pointer to docs/agent.md
  build.gradle.kts             # root Gradle config
  settings.gradle.kts          # module list

  docs/                        # all design docs
    README.md                  # curated index — start here when browsing
    agent.md                   # canonical platform conventions
    milestones.md
    tech-debt.md
    proposals/                 # time-bounded design docs (subject prefix in filename)
    sync/                      # sync architecture & spec (timeless)
      threat-model.md          # security contract
    web/                       # web architecture (timeless)
    protocol/                  # IPC protocol specs
    design/                    # design-system audit
    adr/                       # architectural decision records
    how-to/
    policy/
    tracks/

  # Platform core (KMP)
  core/, core-common/, core-client/, core-proxy/, core-paging/, core-aidl/, core-test-utils/

  # Feature services — TWENTY, each split [name] / -common / -client / -proxy / -test-utils.
  # Table: README.md#feature-services   Only four have a *-agent.md (docs/agent.md §1).

  # Servers (JVM/Ktor)
  sync-relay-server/, commerce-service/, hub/          # + deploy/ for their images & Terraform

  # Shared UI primitives (Compose Multiplatform)
  ui-core/, ui-map/, basemap-core/, vector-tile/

  # Apps — ten. Each is [app]-shared-logic (Compose-free VM/repo, iOS-capable) + -shared-ui
  # (Compose) + thin -android / -desktop shells; iOS = XcodeGen projects, each over its own tenshu-sdk-ios-<app>.
  drive-*, maps-*, timeline-*, calendar-*, contacts-*, fit-*, photos-*, home-*, mia-*
  control/, control-shared{,-logic,-proxy}/, control-desktop/     # Control predates the split

  # Web — TypeScript + React over the KMP→JS SDK (House Rule 5), NOT Compose Web
  web/apex/, web/drive/, tenshu-sdk-js/
```

When working in a module, also read `<module>/<name>-agent.md` if it exists — that's where
module-specific conventions live.

---

## Communicating across sessions

Architectural decisions land in docs, not in commit messages. If you make a non-trivial
design choice, write it down — either in the appropriate doc, or in a new design doc under
`docs/` if the topic doesn't have a home yet. The threshold is *"would the next person
working in this area benefit from knowing the rationale?"* — if yes, write it down.

Conversations with the project maintainer that produce architectural decisions should result
in doc updates within the same session, not a "I'll write it down later" deferral. The docs
are how decisions persist; commit messages are not.

Comments say what code does, not its history. ⚠ Never cite a PR number (`#NNNN`): this tree
is exported publicly; name the test or doc section (`check-pr-refs.py`).
