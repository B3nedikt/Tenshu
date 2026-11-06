# Home & Mia — Smart-Home App + Standalone Assistant (UI-first)

**Status:** M0–M5 shipped 2026-07-22 and the mocks have since been retired behind real services on
Android and desktop; on iPhone, Home is a read-only route-1 app as of 2026-10-02 (§8.1). The
design below is the 2026-07-22 draft.
Reshapes `milestones.md` §Mia's single-app five-surface sketch into **two apps** (D1) and
starts them **UI-first over mock repositories**, the same pattern Timeline, Fit, and Maps
used for their v1s. The *Skills* surface stays gated on Tenshu Rei and is explicitly out of
scope (D3) — which removes the sequencing dependency that parked Mia behind App Console &
Tenshu Store.

**Scope:** information architecture, app split + naming, OS-integration posture, mock data
model, module layout, and phasing for the two app UIs. **Not** in scope: the Voice App
Service, the routines *engine*, the smart-home device service protocol, the Hub audio
pipeline, and Skills — each of those gets its own doc when its phase is real (pointers in
§9).

---

## 1. Background

`milestones.md` §Mia describes one Essential App with five surfaces (Onboarding / Home /
Skills / Routines / Settings+Activity), shipping after App Console & Tenshu Store because
the Skills tab needs Rei's attestation machinery. Two observations changed the shape:

1. **Dropping Skills from v1 removes the only hard gate.** The milestone doc itself names
   "ship without Skills" as the alternative; a UI-only v1 over mocks is the established
   app-bootstrap pattern in this repo.
2. **The user-facing product is really two products.** A smart-home dashboard
   (devices/automations/activity) and a personal assistant (conversation over realm data)
   serve different users — many people have no smart home but would still use an assistant
   that can find photos in Drive or places in Timeline. Google (Home vs. Assistant/Gemini)
   and Apple (Home vs. Siri) both ship this exact split; only Amazon merges everything
   into one Alexa app.

---

## 2. Decisions

### D1 — Two apps: **Home** and **Mia**

- **Home** — the smart-home app: device dashboard (rooms-first), automations, activity
  feed, and the home-facing Hub surfaces (speaker status, connected storage, IoT bridge).
- **Mia** — the standalone assistant: conversation-first (write or speak), installable on
  its own with a home-screen widget, useful with **zero** smart-home devices via
  realm-data queries ("find the photo I saved last summer", "where was that café from my
  Timeline?").
- Home embeds a Mia entry point (an "Ask Mia" affordance); Mia can control home devices by
  voice once the device service exists. Both are lenses over the same realm.

### D2 — Naming: "Home" is right, despite the Hub

The "Home sounds weird with Hub functionality integrated" concern dissolves on precedent:
Google Home manages Nest hubs and Nest Wifi routers; Apple Home manages HomePods and Apple
TVs *as home hubs*. Hub-in-the-home-app is what users already expect. The Hub's
*infrastructure* roles — realm device admission, the store-and-forward relay buffer —
stay **Control-side** (see D6); Home shows only the Hub's home-facing surfaces.

### D3 — Skills deferred (post-Rei)

v1 ships without the Skills tab. The seam is a Settings entry ("Skills — coming later")
plus the rule that the automation *action* vocabulary stays extensible, so third-party
voice skills can later slot in without reshaping the IA. This is what un-gates Mia from
App Console & Tenshu Store.

### D4 — Conversation is text-first; voice behind a capability flag

The conversation UI is identical for text and voice; what voice adds (audio capture →
Voice App Service → STT) is exactly the unbuilt, Hub/ML-gated machinery. v1 ships the chat
surface against a mock conversation backend, with the mic affordance present but gated on
a capability flag. Wake-word ("Hey Mia") is a speaker/Hub feature, not a phone feature
(see D7).

### D5 — Rooms are first-class in the data model

Both competitors organise by room/area; a flat device list stops scaling past ~8 devices.
The v1 *rendering* may be a flat, sortable list, but `Device.room` exists from day one so
no migration is needed when the grouped dashboard lands.

### D6 — Hub setup rides the existing pairing machinery

"Headless Tenshu proxy setup flow" = the existing realm pairing flow with the Hub as the
joining device — `docs/sync/pairing-flow.md` already names a Tenshu Hub as a valid proxy,
and the `tenshu://offer` offerer flow is precisely the mechanism. **Home guides, Control's
machinery admits**: Home's "Add device → Tenshu Hub" walks the user through it, but no new
admission protocol is created. ~~Building this flow will force the open question in
`hub-hardware-and-management-service.md` §10 (does an unpaired node need a minimal local
bootstrap surface?); that gets decided when the flow is built, not here.~~ **Resolved
2026-07-23:** no local bootstrap surface — headless pairing is a printed-static-key
variant of the offer flow; see the hub proposal §11 (design) + §13 (H1–H4 phasing).

### D7 — OS assistant integration posture

- **Android — standard, not vendor-specific.** `RoleManager.ROLE_ASSISTANT` +
  `VoiceInteractionService` are AOSP APIs; Settings → Default apps → Digital assistant is
  stock Android and third parties hold the role today (Alexa, Perplexity). Long-press-home
  / corner-swipe then invokes Mia system-wide. The genuinely vendor/privileged part is
  **always-on hotword with the screen off** (low-power DSP paths) — out of reach, and out
  of scope: ambient wake-word belongs to the smart speaker + Hub.
- **iOS — Siri cannot be replaced.** No supported default-assistant path (the DMA has
  forced browser/store defaults in the EU but, as of this writing, not the voice
  assistant — re-check when the iOS phase lands). Practical substitutes, same ceiling
  Alexa/Gemini live with: **App Intents** ("Hey Siri, ask Mia to …"), the **Action
  Button**, Lock Screen widgets, and a Control Center control.

---

## 3. Home — information architecture

Four tabs (Google Home has five; four is fine):

1. **Home** — the dashboard.
   - Device list with per-category state rows: speaker *Connected/Disconnected*, lamp
     on/off + brightness, camera snapshot/last-event, thermostat temp, **Connected
     Storage: n GB free / total** (reads the Hub's `HubStatus` / `ExpansionModule`
     surfaces from the `HubManagementService` stub, `hub-hardware-and-management-service.md`
     §8).
   - Add / remove / reorder devices; `room` carried in the model (D5). Add-device includes
     **Tenshu Hub** (D6 flow) and, later, IoT devices bridged via the Hub.
   - An "Ask Mia" affordance (embedded entry into the shared conversation surface).
2. **Automations** — the milestone's *Routines* under a friendlier name. See §5.
3. **Activity** — one chronological feed of device events (motion, door, camera
   person-detection), automation runs, and (when surfaced here) voice interactions, with
   filter chips *Devices / Automations / Voice*.
4. **Settings** — household/rooms management, per-device notification toggles, Hub
   management deep-link, the D3 Skills seam.

Device detail screens are per-category (light controls, camera timeline, speaker volume /
grouping, storage breakdown). Scenes (Apple-style named state presets) are **deferred** —
Google folded them into automations and so do we.

## 4. Mia — information architecture

Conversation-first, deliberately small:

1. **Chat** (the app's home) — message thread with Mia; text input always, mic behind the
   D4 capability flag. Renders rich results (photo grids from Drive, place cards from
   Timeline) — mocked in v1.
2. **History** — past interactions (the milestone's Activity sub-screen, promoted).
3. **Settings** — voice-ID setup entry, *set as default assistant* (Android role request;
   iOS: App Intents / Action Button guidance per D7), model/inference settings
   (`AIService` config per `control-ia.md` §6.2 eventually), the D3 Skills seam.

Plus a **widget**: Android Glance ("talk to Mia" + last answer), iOS WidgetKit +
interactive App Intents. Onboarding (voice-ID, default-assistant) is a flow, not a tab.

## 5. Automations taxonomy

Headline (from the milestone doc, and the differentiator vs. Google/Amazon): **routines
execute locally on the proxy — no cloud round-trip**. The engine is an in-proxy rule
evaluator over service operations, not a server workflow. The engine is out of scope here
(**designed 2026-07-27 → [`routines-engine.md`](routines-engine.md)**); the *editor UI* and
the taxonomy it encodes are in scope:

- **Triggers:** time/schedule, sunrise/sunset ± offset, geofence arrive/leave (Timeline's
  capture work proves the location plumbing), device event (motion, contact, camera
  person-detection), voice phrase.
- **Conditions** (the thing IFTTT lacks): time window ("only after sunset"), presence
  ("only when nobody's home"), device state.
- **Actions:** device command, notification, run another automation; extensible so
  post-Rei skills can contribute actions (D3).

An automation = 1 trigger + 0..n conditions + 1..n actions, with enable/disable and a
last-run timestamp (feeds the Activity tab).

---

## 6. Module layout

Follows the established app pattern (Timeline/Fit/Maps), twice:

```
:home-shared-logic      # Compose-free VMs + repos (iOS-capable); mock repos in v1
:home-shared-ui         # Compose Multiplatform (Android + desktop)
:home-android  :home-desktop
home-ios/               # SwiftUI + XcodeGen, {App}Surface seam, born on route-1

:mia-shared-logic       # conversation VM + mock ConversationRepository
:mia-shared-ui          # chat surface (Compose)
:mia-android   :mia-desktop
mia-ios/                # SwiftUI, WidgetKit + App Intents live here
```

- **Home depends on `:mia-shared-logic`/`-ui`** for the embedded "Ask Mia" entry — the
  conversation surface is authored once.
- `*-shared-data` modules appear when schema types become real; v1 models are plain
  classes in `-logic`.
- iOS shells are **born on route-1** (`FirstPartyLocalRuntime`) per
  `ios-first-party-route-1-migration.md` — no EPC detour. iOS slices gate on `xcodebuild`
  of the app, not just Kotlin tests.
- Real services later, none blocking the UI milestone: a `:home-device-service` module set
  (device state/commands), the routines engine proxy-side, `:hub-management-service` per
  its existing stub.

## 7. Mock data model (v1, plain classes in `-logic`)

Sketch — names matter more than fields at this stage:

```kotlin
class Room(val id: String, val name: String)

class Device(
    val id: String, val name: String, val roomId: String?,
    val kind: DeviceKind,           // Speaker | Light | Camera | Thermostat | Sensor | HubStorage | Hub
    val state: DeviceState,         // per-kind sealed: Connectivity, OnOff+Brightness, TempTarget, StorageUsage(free,total), …
    val sortIndex: Int,
)

class ActivityEvent(
    val id: String, val at: Instant,
    val source: ActivitySource,     // Device(id) | Automation(id) | Voice
    val summary: String,
)

class Automation(
    val id: String, val name: String, val enabled: Boolean,
    val trigger: Trigger,           // Schedule | Sun | Geofence | DeviceEvent | VoicePhrase
    val conditions: List<Condition>,// TimeWindow | Presence | DeviceState
    val actions: List<Action>,      // DeviceCommand | Notify | RunAutomation
    val lastRunAt: Instant?,
)

class MiaMessage(
    val id: String, val at: Instant, val fromUser: Boolean,
    val body: MessageBody,          // Text | PhotoGrid | PlaceCard | DeviceAck — rich results mocked
)
```

When these graduate to synced schema types, device *sort order / per-user layout* rides
the `PreferenceService` (`DeviceSetting`), not the shared record.

## 8. Phasing

All M-series phases SHIPPED 2026-07-22 (as-built deltas in §8.1):

- **M0** — this doc + the `milestones.md` §Mia pointer.
- **M1** — Home: module scaffolding + mock repos + Home tab (device list with
  add/remove/sort, rooms in the model, storage tile) on Android + desktop.
- **M2** — Home: Activity + Automations tabs (mock), automation editor with the §5
  taxonomy.
- **M3** — Mia: scaffolding + chat surface (text-first, mock backend, rich-result cells) +
  History + Settings; Home's embedded "Ask Mia" entry.
- **M4** — iOS shells for both (SwiftUI over the Surface seams); Mia widget (Glance);
  Android assistant-role qualification.
- **M5** — Onboarding flows (Compose + SwiftUI, device-local durable gate) + the D4
  capability-flag audit (`MiaCapabilities.voiceInput` gates the mic).
- **Later, in dependency order:** Hub prototype track H1–H4 (headless runtime → headless
  pairing → full-replica backup → `HubManagementService` v1; hub proposal §13) →
  `:home-device-service` + real device state → routines engine →
  voice pipeline (speaker/Hub) → Skills (post-Rei).

### 8.1 As-built deltas

- **Assistant role (M4):** `ROLE_ASSISTANT` is **not dialog-requestable** by apps — the
  D7 "role request" became a stub `VoiceInteractionService` (qualifies Mia for the system
  picker; the session opens the chat) + a Settings/onboarding deep-link to the picker
  (the Alexa shape). The `AssistantRoleLauncher` seam is Android-bound only.
- **iOS WidgetKit (M4):** deferred to the App-Intents slice — the Glance widget shipped;
  the iOS widget wants App Intents anyway, so they land together.
- **SKIE naming:** Fit already exports `HistoryViewModel`/`HistoryUiState` on the ObjC
  surface, so Mia's are `MiaHistoryViewModel`/`MiaHistoryUiState` (Timeline's prefix
  precedent). Check the umbrella's existing export surface before naming new VM/state
  classes in `-shared-logic` modules.
- **Onboarding persistence (M5):** deliberately device-local, NOT realm state (each
  device shows its own intro — it has its own integration setup to offer). Android =
  SharedPreferences, desktop = `java.util.prefs`, iOS = `@AppStorage` (never enters
  Kotlin). Koin overrides ride module order: the platform module is listed after
  `miaModules()` and its `OnboardingStore` binding wins.
- **Mia on iOS left the mock (2026-10-02):** M4's `mia-ios` ran over
  `MockConversationRepository`. It is on route 1 now — `MiaLocalRuntime` +
  `TenshuConversationRepository`, read-only, behind the shared set-up gate, with no mock
  reachable from a shipping build. As built: [`mia-query-layer.md`](mia-query-layer.md) §19.
  ⚠ The threat-model line below ("UI-only phases … no amendment owed") stopped covering Mia
  on iOS at that point; the amendment is at A39 / A4.
- **Automation editor:** Android/desktop only for now; the iOS Automations tab is
  a read-only list (below). A SwiftUI editor is its own future slice.
- **Home on iOS — route 1, read-only (2026-10-02):** `home-ios` no longer renders mocks. It
  roots in `ControlSetUpGate` (ADR 0022 §Decision 6): unprovisioned → *Set Up Tenshu Control*;
  provisioned → `HomeLocalRuntime` runs the proxy graph in-process and `HomeSurface` binds
  `readOnlySyncedHomeRepositories` — the same `SyncedDeviceAware` → `HubAware` → empty chain
  `homeClientModule` composes (now one function, `syncedHomeDevicesRepository`), the synced
  automation list, and `RoutineRun` history. An empty realm is an empty state, never demo rows.
  - ⚠⚠ **Read-only is a maintainer decision, enforced below the UI.** No device control, no
    pairing, no automation toggle / remove / run-now / edit. The surface builds
    `DevicesViewModel` with no control or pairing source, and the automations repository sits
    over `ReadOnlyRoutineControlSource`, which throws. The SwiftUI switch and swipe-to-delete
    were removed rather than disabled.
  - **The later slice — "Home on iOS — control":** device power/level, permit-join pairing,
    automation toggle/remove, then the editor. It swaps `HomeLocalRuntime`'s one composition
    call; ⚠ run-now stays unavailable on iPhone regardless (`RoutineExecution.NEVER`, RE-D13).
  - ⚠ Control needed **no change** to provision Home: provisioning is the one shared
    `RealmKeyMaterial` blob in the shared Keychain group, not a per-app grant. What Home needed
    was entitlements — it had none, so it had no Keychain to read the blob from.
  - ⚠ **What was actually seen (2026-10-02).** Simulator, no Control: the set-up screen with
    Home's sentence. iPhone, Control set up: the provisioned shell with *"No devices yet"*, no
    banner. **Not seen:** a device row, the hub tile, an automation or a run on a phone — only
    in `HomeLocalRuntimeTest`, which lists a routine written to the replica.
  - ⚠ **Open: "No devices yet" cannot tell an empty realm from one still syncing.** On a device
    there is no App Group, so Home's replica is app-local and starts empty; until its first pull
    lands, the empty state is shown for a realm that may have a hub. Every route-1 app shares
    this; the fix is a sync-state signal on the surface, not copy.
  - Custody: threat model A4.
- **Home on web — read-only, the same reads (2026-10-28, `web-app-suite.md` §4.H):** run A
  mirrors this iOS slice's repositories in `tenshu-sdk-js` (`Home.kt` / `HomeBook.kt`, a second
  reader of the same relations, since `:home-shared-logic` has no `js` target). ⚠⚠ Read-only is
  enforced the same way, below the UI: the web plugins map no operation at all. Two deliberate
  differences from native: a failed read is a failure rather than `[]`, and automations carry the
  engine's program, so routines the M2 editor model drops (device-event / geofence / voice
  triggers, unparseable programs) are listed.
- **CMP resource gotcha (M1):** the formatter renders the Android-style `%%` escape
  literally — use a bare `%` in composeResources strings (sibling of the ADR 0016
  apostrophe gotcha).
- **Storage tile live over real hub data (2026-07-24, post-M5 slice):** Home gained its
  **first Tenshu client stack** — deliberately minimal: core client +
  `:hub-management-service-client` only (no file/notification services), the Fit/Maps
  client-assembly shape (`homeClientModule` in `:home-shared-ui` commonMain +
  `homePlatformClientModule` desktopMain = UDS to Control-desktop). Behind the existing
  `HomeDevicesRepository` seam, `HubAwareHomeDevicesRepository` (`:home-shared-logic`)
  decorates the mock: it overlays the freshest hub-authored `HubStatus` row (via a plain
  `HubStatusSource` seam → `ClientHubStatusSource` over `hubStatusesFlow()`) onto the
  first Storage row (real disk free/total — `HubStatus` gained
  `diskFreeBytes`/`diskTotalBytes`, fmt **v12** — plus replica figures + last backup pass
  in `DeviceState.LiveHubStorage`) and the Hub row's connectivity. **Staleness**: a
  snapshot older than 2× the hub's 1 h publish heartbeat marks the tile
  stale/"Hub offline" (a ticker re-derives the flag without new rows); the UI shows an
  "As of {time}" line on live tiles. **Fallback = the mock, honestly**: no connection,
  no `HubStatus` row, or a row without disk figures (`diskTotalBytes <= 0`) → the mock
  devices pass through untouched and are never marked live. Wired at runtime by
  `:home-desktop` only in that slice (Koin module order = override order); Android stayed
  on the mock at runtime until the Android Control proxy gained the wiring — **closed by
  HD-3, 2026-07-26** (`home-device-service.md` §3: Control's `androidMain`
  `PlatformProxyModule` registers hub-management + the device service, Home gained an
  `androidMain` `homePlatformClientModule` over Binder, and `:home-android` joined the
  smoke gate). Tests: `HubAwareHomeDevicesRepositoryTest` (mapping/staleness/fallback) +
  `HomeStorageTileSystemTest` (`:hub`, live end-to-end).

## 9. Threat model & deferred items

The UI-only phases (M1–M5) run over mocks: **no new assets, no amendment owed.** Amendments
land with the phases that create surfaces, per House Rule #2:

- `:home-device-service` — device command/state as realm data; IoT bridge surface.
- Routines engine — automations acting on services without user presence.
- Voice pipeline — audio capture, in-use plaintext on Hub/co-computer (already flagged in
  `hub-hardware-and-management-service.md` §9).
- Hub store-and-forward + always-on proxy — flagged in the same doc.
- Home on iOS route 1 (2026-10-02) — **amended**: A4 names Home as a key-holding co-owner.

Deferred design questions, each to its own doc when real: ~~routines-engine evaluation
semantics~~ (**designed 2026-07-27 → [`routines-engine.md`](routines-engine.md)**,
RE-1..RE-5 — which device executes a synced routine and why, `firingId` idempotence, the
run receipt that doubles as the Activity row, and the safety rails);
~~how an utterance becomes an answer over realm data~~ (**designed 2026-07-27 →
[`mia-query-layer.md`](mia-query-layer.md)**, MQ-1..MQ-5 — replaces the mock behind
`ConversationRepository`; the plan lowers to an ordinary chain-DSL callstack behind one
validator; argues Voice/ML/Skills are *not* on Mia's critical path);
~~where on-device inference runs on a phone with no Hub~~ (**designed 2026-07-27 →
[`inference-service.md`](inference-service.md)**, IN-1..IN-5 — the seam, the per-platform
runtime, model distribution, and the opt-in consent model; shared with Drive's image
features and CM-4); ~~device-service protocol + Hub IoT bridge~~ (**designed 2026-07-24 →
[`home-device-service.md`](home-device-service.md)**, HD-1..HD-4); ~~cameras~~
(**designed 2026-07-25 → [`camera-service.md`](camera-service.md)**, CM-1..CM-5 — the
§3 Devices-tab camera row lands with CM-1); wake-word/speaker audio path (**decisions
pre-recorded 2026-07-25 → [`audio-and-voice.md`](audio-and-voice.md)**; full proposal
when the speaker hardware is real); scenes;
Skills (post-Rei); ~~the hub-proposal §10 local-bootstrap question (forced by the D6
flow)~~ (resolved — see D6).
