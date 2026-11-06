# `:home-device-service` — Smart-Home Devices as Realm Data

**Status:** v0.2 — design settled 2026-07-24 (session with the maintainer, following
the H1–H4 hub prototype track + the Home storage-tile slice). **HD-1 + HD-2 IMPLEMENTED
2026-07-25** (as-built notes inline in §3; HD-2's Pi/bulb pass is the outstanding
verification). HD-3 (Android runtime) is next. This is the doc
that `home-and-mia-apps.md` §"Not in scope" promised for "the smart-home device service
protocol".

**Scope:** the service through which smart-home devices (lights, plugs, sensors,
thermostats, …) bridged by the Hub become realm data — inventory, live state, and
commands — consumed by Home's Devices tab, the future routines engine, and Mia. Includes
the hub-side bridge architecture (which radio stack first, and behind what seam).

**Not in scope:** the routines *engine* (**designed 2026-07-27 →
[`routines-engine.md`](routines-engine.md)**, RE-1..RE-5 — it consumes this service's
commands and state rather than reshaping them), the voice
pipeline, scenes, and the smart-speaker audio path (decisions pre-recorded in
[`audio-and-voice.md`](audio-and-voice.md) — notably AV-D5, which owes an argued
exception to HD-D2's LAN-listener rejection). **Cameras** are a deliberate
exception with a split home: their *identity and control* stay here (a camera is a
`HomeDevice` like any other), but their media/event plane — snapshots, clips, live view,
detection — belongs to [`camera-service.md`](camera-service.md), sequenced after HD-2.

---

## 1. The shape in one paragraph

Devices ride the same three-row pattern H4 proved for hub management, generalised:
**inventory** (`HomeDevice`, hub-authored + user-editable naming), **state**
(`DeviceState`, hub-authored, change-gated), and **commands** (`DeviceCommand`,
idempotent caller-minted rows the hub consumes at most once). Sync is the transport —
no new channel, the hub stays outbound-only, and every realm device (and Mia) sees the
home's state through ordinary replication. The hub talks to actual radios through a
pluggable `DeviceBridge` seam; bridge #0 is a **simulated bridge** (proves the whole
spine with no hardware), bridge #1 is **Zigbee via a zigbee2mqtt sidecar**.

---

## 2. Decisions

### HD-D1 — Device data rides sync; the hub is the state authority

Three composites (new types = additive, no format bump owed — `MovementTrace` precedent;
exact field lists are HD-1's job, principles here):

- **`HomeDevice`** — `Syncable(Eager)`, keyed by a bridge-scoped stable device id.
  Identity + configuration: kind, manufacturer/model, capability ids, `name`, `room`
  (a string field per `home-and-mia-apps.md` D5 — a `Room` entity waits for real need).
  Hub-authored on join; `name`/`room` are user-editable from any device — field-level
  LWW resolves the two authors, no special machinery.
- **`DeviceState`** — `Syncable(Eager)`, keyed by device id, **hub-authored only**.
  A snapshot of current state: a small set of common typed fields (power, level,
  temperature, battery, online) — all optional — plus a `genericData` escape hatch for
  per-kind extras (the preference-service precedent). ⚠ Optional-field *additions* later
  are peer-breaking (positional composites), so HD-1 should be deliberately generous with
  the common field set and lean on the escape hatch for the long tail.
  **Change-gated + rate-limited** like `HubStatus`: quantise noisy analog values
  (temperature to 0.5 °C, level to meaningful steps) and enforce a per-device minimum
  publish interval so a chatty sensor cannot flood the realm with blocks. Telemetry
  *history* is explicitly out of v1 (future: content-plane rollups; the Fit
  `LatestSample` work is the precedent for "latest is realm state, history is elsewhere").
- **`DeviceCommand`** — the H4 `HubCommand` pattern verbatim: caller-minted `commandId`,
  target device id, capability verb + args, issuer + issuedAt. Hub consumes **at most
  once** (durable consumed-journal written before execution) with a **short staleness
  window — 2 minutes**, much tighter than hub management's 15: a light toggle replayed
  from a stale sync backlog must never fire hours later. Outcome rides the `DeviceState`
  row (`lastCommandId`/`outcome`), no ack type.

### HD-D2 — Latency posture: fast-poll now, relay long-poll later, LAN listener rejected

Commands over sync inherit the sync cadence; a light switch needs to feel instant. Three
options, decided:

1. **v1: the hub polls fast.** H1 already exposed `syncPollingIntervalMs`; the hub runs a
   3–5 s cadence (configurable) whenever bridged devices exist. A plugged-in node doesn't
   care about battery, and the relay cost of empty polls is trivial. Perceived latency:
   phone-tap → command row → relay → next hub poll → radio, worst case one poll interval.
   Acceptable for the prototype; honest about not being instant.
2. **The real fix: relay long-poll** — `sync-relay-api.md` Phase 5 (long-poll on
   `/sync/changes`) is designed, unshipped, and this is finally its forcing consumer.
   Hub long-polls; the relay completes the poll when the phone's command block arrives;
   sub-second end-to-end with the hub still strictly outbound-only. Scheduled as HD-4
   (or lands with the relay proposal, whichever moves first).
3. **Rejected: a LAN listener on the hub** for a phone→hub fast path. It would reopen the
   §11 no-listener decision, add mDNS/discovery + a second auth surface, and split the
   command path into two code paths with different security stories. Revisit only if
   long-poll measurably disappoints.

### HD-D3 — Bridge architecture: pluggable seam, JVM is the brain, radios are sidecars

Per the hub proposal §6: radio stacks are native-Linux territory; the JVM proxy
orchestrates. The seam is a Kotlin **`DeviceBridge`** interface on the hub —
roughly *discover/observe devices*, *observe state*, *execute command*, *permit-join* —
so radio backends are pluggable and the schema/sync layer never knows which radio a
device came from.

**First real bridge: Zigbee via a `zigbee2mqtt` sidecar.** Rationale: the maintainer's
Pi radio does Zigbee; zigbee2mqtt is the battle-tested path with an enormous supported
device database and a clean MQTT contract; the hub JVM is then an MQTT **client**
(outbound-only even locally, consistent posture) speaking to a local mosquitto +
zigbee2mqtt pair deployed as two more systemd units next to `tenshu-hub`. Writing a
coordinator protocol ourselves was rejected as pure liability; a Python/Node child
process embedded in our tree likewise (deploy two proven upstream services instead).

**Thread/Matter is deliberately second** (OTBR + a Matter controller is a bigger lift
with its own doc-worthy decisions — fabric credentials, commissioning flow); nothing in
the seam presumes Zigbee.

### HD-D4 — HD-1 ships on a simulated bridge

Bridge #0 is `SimulatedDeviceBridge`: a couple of fake devices (a dimmable light, a
temperature/battery sensor) with real little state machines, running inside the hub.
This proves the **entire** spine — bridge → rows → sync → Home tab → command row → hub
executor → bridge → state row → back to the phone — with zero hardware, in system tests
and in the maintainer's established sim-hub-on-Mac verification pattern. HD-2 swaps in
the real radio with no protocol change. (This mirrors how the whole hub track was built:
runtime first against fakes, hardware last.)

### HD-D5 — Home consumes it through the same seam pattern as the storage tile

`home-shared-logic` gets a `DeviceSource` beside `HubStatusSource`: real implementation
over the `:home-device-service-client` surface, mock fallback preserved (mock remains
Home's honest default; never render fake devices as real). Desktop is the proven path;
Android runtime rides the already-tracked Control-proxy wiring + Home Binder-client
follow-up (same gate as the tile).

### HD-D6 — Module set symmetric with the other services

`:home-device-service` + `-common` / `-client` / `-proxy`, Service & Operation pattern
(`docs/agent.md` §2). The only op that executes on a *manager's* proxy is
`IssueDeviceCommand` (mirroring `IssueHubCommand`); reads are local reactive reads of the
synced rows. Hub-side host (`DeviceBridgeHost`: publisher + command executor + journal)
lives with the hub the way `HubManagementAgent` does.

### HD-D7 — Threat model: two new surfaces, amended per slice

1. **RF ingest (HD-2):** device names/attributes arriving over Zigbee are **untrusted
   input from the neighbourhood's airspace** — validate/bound them before they become
   realm rows rendered in every app. The Zigbee network key lives with zigbee2mqtt on
   the hub's disk — same at-rest class as the H3 replica, covered by the LUKS posture;
   permit-join is the admission gate and must be explicit + time-bounded (a
   `HubCommand`-style op, never always-on).
2. **Physical actuation (HD-1):** realm devices can now cause physical effects. Realm
   devices are already fully trusted peers (H4 posture note), so no new *authority*
   class — but the **staleness window + consumed journal are load-bearing** (replayed
   backlog must not flip lights at 3 a.m.) and get pinned by tests. Amendment lands with
   HD-1; the RF half with HD-2.

---

## 3. Phasing

- **HD-1 — the spine (no hardware).** Module set + `HomeDevice`/`DeviceState`/
  `DeviceCommand` schemas + client surface; hub-side `DeviceBridgeHost` (publisher with
  change-gate/rate-limit, command executor with journal + 2-min window) over
  `SimulatedDeviceBridge`; Home Devices tab real source behind the seam (desktop);
  threat-model amendment (actuation half). System test: simulated light toggled from a
  manager client through the full loop, exactly-once pinned; sensor chatter proves the
  rate limit; staleness proves the window. Format: additive types only, **no bump**.

  **HD-1 IMPLEMENTED (2026-07-25, one PR).** As-built, per HD-D1..D7 with the field
  lists and a few slice-level calls:

  - *Module set*: `:home-device-service` + `-common`/`-client`/`-proxy`
    (`org.tenshu.home.device`), symmetric with `:hub-management-service`; registered in
    `buildProxyModule()`'s `coreSchemaConfigs` (all platforms ingest the rows) + Home's
    `homeClientModule` + `:system-test-utils`; relation maintainer
    (`initHomeDeviceService`) + op factory wired in the **desktop** `PlatformProxyModule`
    and `:system-test-utils`. All three composites additive NEW types — **no
    `TENSHU_STORAGE_FORMAT_VERSION` bump (v12 unchanged), no relay change**;
    serialize→parse pinned per type (wire-registration-gap guard), incl. the
    `genericData` extras slot. ⚠ Android + iOS `PlatformProxyModule`s deliberately
    untouched (the same two-line follow-up as hub management — HD-3).
  - *Schemas*: `HomeDevice` (key `deviceId` = `"<bridgeId>:<local-id>"`; `bridgeId`,
    `kind`, `manufacturer`, `model`, `capabilityIds` — a **space-separated string**, not
    a native list field (keyed-row list reads come back empty over the chain; known
    footgun), user-editable `name`/`room`, `joinedAtEpochMs`, `extras` genericData);
    `DeviceState` (key `deviceId`, hub-authored only; `online`, tri-state `power`,
    `levelPercent`, `temperatureDeciC`, `humidityPercent`, `batteryPercent`,
    `colorTempMireds`, `powerDrawDeciW` — generous on purpose, sentinel-encoded
    (`NO_PERCENT`/`NO_VALUE`/`NO_TEMPERATURE_DECI_C` — temperatures are negative, so
    not `-1`), `lastCommandId`/`lastCommandOutcome` (outcomes ride this row, no ack
    type), `freshAsOf`, `extras` genericData); `DeviceCommand` (key caller-minted
    `commandId`; `targetDeviceId`, `verb` + **string** `argValue` (not genericData: the
    hub parses per-verb anyway, new verbs are additive with no schema change, and a
    string arg is the zigbee2mqtt shape), `issuedAtEpochMs`, `issuedByDeviceId`;
    `STALE_WINDOW_MS = 2 min` lives on the schema companion — contract, not hub knob).
    Verbs v1: `setPower`/`toggle`/`setLevel`.
  - *Ops*: `IssueDeviceCommand` (manager's proxy stamps issuedAt + issuer, saves the
    row — the `IssueHubCommand` mirror) and `UpdateHomeDevice` (rename/re-room:
    read-modify-write preserving every hub-authored field; `setName`/`setRoom` flags
    because `""` is a legitimate room value). Client surface:
    `homeDevicesFlow()`/`deviceStatesFlow()` + getAll twins, `getDeviceState(id)`,
    `setDevicePower`/`toggleDevice`/`setDeviceLevel`/`renameDevice`/`setDeviceRoom`.
  - *Hub side* (`:hub`): `DeviceBridge` seam (devices/states as **state flows** — replay
    makes host start-order races impossible; `execute` returns outcome + optional
    synchronous new state, the async-radio shape kept open for z2m; `setPermitJoin`
    stub) + `DeviceBridgeHost` (inventory publisher change-gated on hub-authored fields
    only + user-field-preserving on re-announce; state publisher through the pure
    `DeviceStatePublishGate` — 0.5 °C / 5-point quantisation, 10 s per-device interval
    floor with held-then-flushed changes (never lost), 1 h heartbeat; command executor
    reusing H4's `HubCommandGate` decision table + a second durable
    `ConsumedCommandJournal` (`consumed-device-commands.log`) written before execution,
    2-min window; outcome force-publishes the state row). `SimulatedDeviceBridge`:
    dimmable light + drifting climate sensor (bounded sub-quantum random walk — the
    rate limit's honest workload), commands really mutate the machines; enabled only
    via `tenshu-hub --simulated-devices` (OFF by default). HD-D2 fast poll: with the flag
    set and no explicit `--sync-interval-ms`, the hub defaults to a 5 s cadence
    (`DEVICE_FAST_POLL_INTERVAL_MS`). ⚠ It did **not** "land trivially": until 2026-09-13
    the knob never reached the scheduler — `HubRuntime` bound it under the wrong Koin type
    and every hub ran at the 30 s production cadence (`tech-debt.md`, "The hub's sync
    cadence override never reached its scheduler").
  - *Home side* (HD-D5, desktop-proven): `DeviceSource`/`SyncedDeviceInfo` seam beside
    `HubStatusSource` + `ClientDeviceSource` (joins device+state flows; every failure →
    empty = fall back to mock) + `SyncedDeviceAwareHomeDevicesRepository` wrapping the
    storage-tile decorator: real devices **replace the mock household wholesale**
    (infrastructure Hub/Storage tiles kept for the tile overlay; a mock lamp is never
    rendered next to a real one — mock stays the honest fallback, the two never mix);
    synced `room` strings resolve to same-name mock rooms else mint `synced-room:` ids.
    Android compiles; runtime stays mock (HD-3).
  - *Threat model*: surface #17 + §8.19 (T-HD-01 backlog replay / T-HD-02 re-delivery /
    T-HD-03 trusted-peer posture) + header sub-amendment; RF half deferred to HD-2.
  - *Tests*: `HomeDeviceSystemTest` (full loop: publish → manager sees the light via
    `ClientDeviceSource` → toggle/setLevel execute exactly once, light really changes,
    outcome + level sync back → re-delivered id is a no-op → sub-quantum sensor chatter
    writes no rows, quantum crossing does → rename LWW-merges against concurrent state
    publishes and survives an inventory re-announce; separate stale test: a hub seeing
    commands 3 min old never actuates) + `DeviceStatePublishGateTest` /
    `SimulatedDeviceBridgeTest` / `DeviceCommandGateTest` (2-min boundary, journal
    durability) + 3 serialization tests + `SyncedDeviceAwareHomeDevicesRepositoryTest`.
  - *Not in this slice*: zigbee2mqtt + permit-join UX (HD-2), Android runtime wiring
    (HD-3), relay long-poll (HD-4), telemetry history (⭐ the *event* half is now designed in
    [`home-device-event-history.md`](home-device-event-history.md); the time-series half is
    deliberately still nobody's), Devices-tab command UI (the tab
    renders live state; toggling from Home rides a follow-up — the client op exists).
- **HD-2 — Zigbee for real.** `Zigbee2MqttBridge` (MQTT client), mosquitto + zigbee2mqtt
  systemd units + how-to (Pi deploy doc grows a §), permit-join flow surfaced in Home's
  "Add device", RF threat-model amendment. Verified on the Pi with a real bulb — the
  first physical light bulb moment.

  **HD-2 IMPLEMENTED (2026-07-25, one PR) — hardware pass pending.** Everything but the
  mosquitto socket and the 2.4 GHz airspace is exercised by tests; the Pi run is the
  outstanding verification (checklist in the PR + `hub-pi-deploy.md` §7.5).

  - *The seam that made it testable*: a thin `MqttClient` interface (connect / subscribe /
    publish / hot `messages()` / `connected()` state) in front of the library, with
    `PahoMqttClient` (Eclipse Paho v3 — dependency-free pure JVM; HiveMQ's client would have
    dragged Netty into a runtime that has none) as the only implementation and a
    `FakeMqttClient` in tests. **Reconnect-with-backoff lives above the seam**, in the bridge,
    precisely so it is observable and unit-tested — Paho's own `isAutomaticReconnect` is
    deliberately OFF so a second invisible retry loop cannot race ours. ⚠ `messages()` must
    **replay a short tail** (both implementations do): a broker delivers retained publishes
    the instant a subscription is acknowledged, which can precede the collector attaching —
    with `replay = 0` the entire inventory is dropped on the floor and the hub sits there
    empty until something changes.
  - *Wire semantics are pure*: `Zigbee2Mqtt` (topic algebra + parsing + mapping + units +
    sanitisation) has no I/O, so real z2m payload shapes replay in unit tests.
    `Zigbee2MqttBridge` owns only I/O and lifecycle.
  - *Identity vs. addressing* (the slice's central asymmetry): rows key off the **IEEE
    address**, topics off the **friendly name**, and the mapping is re-derived from every
    retained `bridge/devices` publish — so a rename in z2m moves the topics and touches
    nothing else (test-pinned both directions). ⚠ Friendly names may contain `/`, so the
    device-topic remainder is resolved *whole* against the known-name map, never split.
  - *Capabilities*: z2m `exposes` walked recursively (a `light` expose carries
    `state`/`brightness`/`color_temp` as nested features) → `CAP_*`; kind from the expose
    *types* (`light`/`switch`/`climate`) with a sensor fallback. Devices z2m has no definition
    for still become devices (`KIND_OTHER`, no capabilities, attributes in `extras`).
    Unit conversions are pinned: brightness 0–254 ↔ `levelPercent` 0–100 round-trips as the
    identity for **every** percent value; °C → `temperatureDeciC`; `setLevel 0` becomes an
    explicit `OFF` (brightness 0 is not a reliable off across vendors).
  - *Extras*: unmapped attributes (`linkquality`, `voltage`, `pressure`, …) ride the
    `genericData` slot as bounded JSON on both rows — new `BridgeDevice.extras` /
    `BridgeDeviceState.extras` (hub-internal data classes, **no schema change**). ⚠ Extras are
    deliberately **outside** `DeviceStatePublishGate`'s change comparison: `linkquality` moves
    on every report and would otherwise defeat the flood brake.
  - *Permit-join shape (the decision)*: the bridge announces **itself** as one inventory row
    whose `deviceId` **IS** its `bridgeId` (`"zigbee2mqtt"`) — a deliberate exception to
    `"<bridgeId>:<local-id>"`, because that row *is* the bridge. It advertises the new
    `CAP_PERMIT_JOIN`, and `permitJoin` (arg = seconds) is an ordinary `DeviceCommand`
    addressed to it. Consequences, all free: the ownership check passes, the durable consumed
    journal makes it at-most-once, the **2-minute staleness window** makes a backlog-replayed
    "open the network" inert (the sharp case — a silently re-opened radio is a standing
    invitation), the outcome rides the bridge's own `DeviceState` row, and Home discovers
    "pairing is possible here" from *data* rather than a hard-coded bridge id. The window is
    clamped to `PERMIT_JOIN_MAX_SECONDS` (5 min) **in the host**, so every future bridge
    inherits the bound, and again in the z2m bridge. The row also solves the bootstrap: a
    never-paired network has no devices, so without it there would be nothing to address.
  - *Constants added* (additive, no wire/format change — **v12 untouched**):
    `HomeDevice.BRIDGE_ZIGBEE2MQTT` / `KIND_BRIDGE` / `CAP_PERMIT_JOIN`,
    `DeviceCommand.VERB_PERMIT_JOIN` / `PERMIT_JOIN_MAX_SECONDS` / `PERMIT_JOIN_DEFAULT_SECONDS`.
    Client surface: `openPairingWindow(bridgeId, seconds)`.
  - *Seam change*: `DeviceBridge.setPermitJoin(enabled: Boolean)` (HD-1's stub) became
    `setPermitJoin(seconds: Int): BridgeCommandResult`; the host routes `VERB_PERMIT_JOIN`
    there and accepts `bridgeId` as a command target.
  - *Home side*: `DevicePairingSource` beside `DeviceSource` (+ `ClientDevicePairingSource`);
    `SyncedDeviceInfo` gains `bridgeId`/`capabilities`; `DevicesViewModel` gains a
    `PairingUiState` (open / countdown / joined names / failed) and the Add FAB opens the
    pairing dialog **only when a `CAP_PERMIT_JOIN` bridge exists** — otherwise the mock
    add-a-device dialog stands, because Home must never offer a flow it cannot honour. The
    "a device joined" signal is the ordinary synced inventory (no second reporting channel);
    the countdown is honest about elapsed time, not authoritative about the radio.
    `KIND_BRIDGE` renders as a `DeviceKind.Hub` row. ⚠ Note the HD-1 consequence: a realm with
    a bridge but no paired devices still replaces the mock household (with the single bridge
    row) — correct per "the two never mix", and the path to *Add device*.
  - *Demo mode*: `SimulatedDeviceBridge(pairable = true)` (used by `--simulated-devices`)
    announces a bridge row and lets a fake bulb join an open window, so the whole Add-device
    flow is clickable on a desktop with no radio in the building. Default `false` keeps HD-1's
    fixtures byte-identical.
  - *Deploy*: `--zigbee2mqtt` + `--mqtt-url` / `--mqtt-base-topic` (credentials via
    `TENSHU_MQTT_USERNAME`/`_PASSWORD` — **never argv**, `/proc` is world-readable), one
    `DeviceBridgeHost` per enabled bridge each with its own journal;
    `hub/deploy/mosquitto-tenshu.conf` (⚠ **binds 127.0.0.1 only**, persistence off),
    `zigbee2mqtt.service`, `zigbee2mqtt-configuration.yaml` (⚠ `permit_join: false`
    permanently; `network_key: GENERATE` → the data dir belongs on the LUKS volume);
    `hub-pi-deploy.md` §7 covers adapter choice (⚠ the **Pi 5 has no Zigbee radio**), both
    units, the hub flags, a first-bulb walkthrough and a troubleshooting table.
  - *Threat model*: surface **#17b** + **§8.20 (T-HD-04 air-sourced metadata / T-HD-05 open
    radio / T-HD-06 network key at rest / T-HD-07 the local broker)** + header sub-amendment.
    Sanitisation replaces control characters rather than dropping them (so `a\nb` cannot
    become `ab`), caps fields at 64 chars, extras at 16 keys / 512 chars, the inventory at 512
    devices. Radio proximity is called out as a *capability* of AD1/AD2, not a new adversary.
  - *Tests* (all hardware-free): `Zigbee2MqttMappingTest` (21 — recorded z2m payload shapes:
    inventory, capabilities, undefined devices, rename, state mapping + partial-publish merge,
    malformed input, brightness round-trip across all 101 values, `/set` payloads, adversarial
    names, topic classification); `Zigbee2MqttBridgeTest` (13 — retained replay, rename keeps
    identity, reconnect + resubscribe, connect-failure backoff, bridge-offline, availability,
    malformed/unknown traffic, permit-join clamp); `Zigbee2MqttSystemTest` (2 — the full loop
    against the fake broker with real everything-else, incl. exactly-once and a stale
    permit-join reaching neither radio nor journal); `SimulatedDeviceBridgeTest` +
    `DevicePairingViewModelTest` (7) + a `DevicesScreenTest` pairing case.
  - *Not in this slice*: Matter/Thread (a second HD-D3 backend), Android runtime (HD-3), relay
    long-poll (HD-4), device *removal* from the realm when it leaves the Zigbee network (rows
    persist; needs a tombstone story), OTA updates, groups/scenes, and the z2m frontend
    (shipped disabled — it is an unauthenticated LAN listener).
- **HD-3 — Android rides in.** Lands with the tracked Android wiring (Control proxy
  hub-management + device-service registration, Home Binder client, smoke gate, v12
  lockstep) — phone becomes a full manager.

  **HD-3 IMPLEMENTED (2026-07-26, one PR) — no new types, no schema/wire/relay change.**
  Two mirrors and a gate entry; every seam it lights up was built and desktop-proven by
  HD-1/HD-2, so the slice is pure platform wiring:

  - *Control's Android proxy* (`control-shared-proxy` androidMain `PlatformProxyModule`):
    `initHubManagementService()` + `initHomeDeviceService()` beside the other relation
    maintainers, and both `…ProxyImplementationFactory` fallbacks in the op-executor
    chain — line-for-line the desktop twin, including the `issuerDeviceId` rule (stamp an
    issuer only once the device has an owner: an unowned proxy has no realm to issue
    into). The two `-proxy` modules join the `androidMain` dependency block
    (`desktopMain` has carried them since H4). Android proxies already *ingested* the
    rows — the schema is commonMain-registered — so what was missing was exactly the
    relation maintenance and the ability to serve `IssueHubCommand` /
    `IssueDeviceCommand` / `UpdateHomeDevice`.
  - *Home's Android client half*: a new `androidMain` `homePlatformClientModule` —
    `BinderTaskTransport` + `RemoteBinaryTaskExecutor` + `AndroidTenshuClient`, the
    Fit/Timeline shape verbatim; `platformSchemaConfigs` is empty (Home's reads and
    commands are ordinary op traffic) and the FD/SharedMemory caches are bound only
    because the transport requires them — Home moves no bytes. `HomeApplication` starts
    Koin with `homeModules() + listOf(homeClientModule, homePlatformClientModule)`
    (client modules LAST — module order is override order, so their
    `HomeDevicesRepository` binding replaces the mock household) and emits the
    schema-lockstep beacon. Manifest gains the `<queries>` entry for Control (package
    visibility, else `bindService` silently fails), the `ClientCallbackService`
    declaration for the reverse push channel (this is how the Devices tab gets live
    state without polling), and `INTERNET` stripped with `tools:node="remove"` — all
    egress is Control's, and "a smart-home app that cannot reach the internet" is a
    claim worth being able to make at the OS boundary (the Maps/Timeline precedent).
  - *Smoke gate*: `:home-android:installDebug` + launch + liveness + schema-lockstep
    beacon + `TenshuSchemaAnnounce` check (WARN-if-missing, like the other lazy clients).
    Home parses hub-authored rows now, so a stale Home install is exactly the HR #9
    silent-garbling case the gate exists to name.
  - *Fallback unchanged and load-bearing*: no Control installed, Control sealed, or no
    hub paired → every source read comes back empty and the tab is the mock household,
    honestly. Home still launches standalone.
  - *Not in this slice*: ~~actuation **from** Home~~ (**landed the same day** — see the
    follow-up below), iOS `PlatformProxyModule` (wired for both services since 2026-10-02 —
    the relation maintainers and op factories, as on Android), relay long-poll (HD-4).

  **HD-3 follow-up — actuation from Home (2026-07-26, one PR).** Closes the gap HD-1 left
  open: the tab rendered live state but never called the command ops. UI + client-side
  only — **no schema, wire, relay or hub change** (fmt v15 untouched); every op it uses
  shipped with HD-1/HD-2.

  - *Seam*: new `DeviceControlSource` (`setPower`/`setLevel`) beside
    `DevicePairingSource`, with `ClientDeviceControlSource` over the existing client
    extensions. Same degrade contract: a failure to *issue* returns false, never throws.
  - ⚖️ **Absolute, never relative.** The service also offers `toggle`, and the seam
    deliberately does not expose it: a switch expresses the state the user *wants*, while
    a toggle expresses a flip relative to a reading that may already be stale when the hub
    consumes the row — and they disagree exactly when it matters (a state row landing
    between tap and command). `toggle` stays right for a physical button, wrong for a
    control that displays state.
  - ⚖️ **Correlation on the echoed command id, not value equality.** `SyncedDeviceInfo`
    now carries `lastCommandId`/`lastCommandOutcome` (they were already on the row — HD-1
    put outcomes there rather than inventing an ack type), and the view model resolves a
    pending command only when the hub echoes *its* id. Value equality cannot tell "the hub
    did what I asked" from "someone flipped the wall switch", and would report a stale
    coincidence as confirmation.
  - *Status taxonomy* (`DeviceCommandStatus`): `Pending` → `Idle` on `OUTCOME_OK`;
    `Failed` when the command could not be issued at all or the hub reported
    `failed`/`unsupported`; **`Unconfirmed`** when nothing arrives within
    `COMMAND_OUTCOME_TIMEOUT_MS` (75 s). The last is not a failure and the copy says so:
    the command row is durable and the hub may still act inside its own 2-minute staleness
    window. ⚠ The timeout is sized against the hub's *slowest honest* path — the default
    sync cadence is 30 s each way (a `--simulated-devices` hub answers in seconds) — so a
    tighter one would cry wolf on a healthy house.
  - *Rendering*: controls live in `DevicesUiState.controls`, keyed by device id, and a
    device **absent from the map has no controls** — every mock row (the mock household is
    a fallback, not a switchable house) and every read-only device (a climate sensor). While
    a command is in flight the control shows the *requested* value with a spinner, because a
    switch that snapped back for 30 s reads as a broken app. The brightness slider issues
    on **release**: every change is a synced command row, and a per-pixel slider would flood
    the realm (the hub's publish gate brakes state, not commands).
  - ⚠ Deliberately **not** gated on `online`: a bridge that does not publish availability
    reports every device as offline, and disabling every switch in that setup is a dead
    end. The outcome on the state row is the honest answer to "did it work" — let the user
    try.
  - *Tests*: `DeviceControlViewModelTest` (10 — capability gating, absolute command,
    optimistic render, the wall-switch case, hub-reported failure + dismissal,
    unissuable-command, silence→`Unconfirmed`, late outcome, supersession, clamping) +
    two `DevicesScreenTest` cases (a real device renders one power switch that issues and
    then surfaces a failure note; a mock-only household renders none) +
    **`HomeDeviceControlSystemTest`** (`:hub`) driving the *real* view model over
    `ClientDeviceSource`/`ClientDeviceControlSource` against a live hub + simulated bridge:
    control appears → switch → the simulated light really turns on → the echoed outcome
    resolves the row → dim to 45% likewise. That last test is the one that pins the
    `ClientDeviceSource` mapping of the correlation fields, which no fake source can prove.
  - *Not in this slice*: thermostat set-points and colour temperature (no verb yet), a
    per-device detail screen, rename/re-room from the UI (`UpdateHomeDevice` exists),
    room-grouped dashboard.
- **HD-4 — latency.** Relay long-poll (sync-relay-api Phase 5) consumed by the hub's
  drain loop; drop the fast-poll cadence to a fallback. May land earlier inside the
  relay-api track; HD-4 is then just adoption.

## 4. Pointers

- `home-and-mia-apps.md` — Home IA (Devices tab, D5 rooms), the mock this replaces.
- [`camera-service.md`](camera-service.md) — cameras: identity/control stay in *this*
  service (CD-D1), media/events live there; ONVIF-first, Matter later; starts after HD-2.
- [`routines-engine.md`](routines-engine.md) — the first non-UI consumer. RE-D1 makes the
  bridge host the only eligible executor for device-event triggers (HD-D2's 3–5 s poll is
  useless for motion→light), RE-D8 inherits the HD-3 follow-up's absolute-`setPower`
  vocabulary and `Pending`/`Failed`/`Unconfirmed` semantics unchanged, and RE-D9 rides the
  caller-minted `commandId` + echoed `lastCommandId` for loop suppression — so the engine
  needs **no change to any composite here**.
- `hub-hardware-and-management-service.md` §6 (sidecar posture), §13 H4 (the synced
  status/command pattern this generalises).
- `docs/proposals/sync-relay-api.md` — Phase 5 long-poll (HD-4's dependency).
- Fit `LatestSample` rollups — the "latest synced, history elsewhere" precedent for
  future telemetry.
