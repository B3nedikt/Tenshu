# Device Event History — What the Home Did, and What It Costs to Remember

**Status:** Draft v0.1 — design, no code (2026-08-27). Closes the last gap the Activity tab
exposed: `home-and-mia-apps.md` §3 specifies the feed as three kinds and only one has a
source. Written *because* the Automation kind shipped —
[`routines-engine.md`](routines-engine.md) RE-1 follow-up — and the empty state now has to
say out loud that device events are not recorded.

⚠ **This doc introduces no code and no schema.** It exists so the schema decision is made
before someone reaches for the obvious `DeviceEvent` row; **DE-1 owes its own House Rule #9
assessment** (§7).

**Scope:** whether a smart-home device's discrete state transitions are remembered, in what
shape, for how long, written by whom, and at what cost in blocks and quota. Includes the
consent posture, because a door sensor's history is a presence signal.

**Not in scope:** numeric telemetry as a *time series* (temperature graphs, power-draw
charts) — §3 D3 argues that is a different artifact with a different shape and belongs in
its own doc; camera media; the Activity tab's `Voice` kind (waits on the voice pipeline);
and any security/audit guarantee (§6 T-DE-02).

---

## 1. Background — a tab that promises three things and can deliver one

Home's Activity tab is specified as one chronological feed with a kind filter: **Device**,
**Automation**, **Voice** (`home-and-mia-apps.md` §3, §120, `ActivityKind`). As of
2026-08-27:

| Kind | Source | State |
|---|---|---|
| `Automation` | `RoutineRun` rows | shipped — the receipt *is* the history row (RE-1) |
| `Device` | — | **nothing stores device events** |
| `Voice` | — | no pipeline |

⚠⚠ **The device half is not merely unbuilt; there is nowhere for it to read from.**
`:home-device-service` holds three types and none of them is a history:

- `HomeDevice` — inventory, one row per device.
- `DeviceState` — the **current value**: `online`, `power`, `levelPercent`,
  `temperatureDeciC`, `freshAsOf`, … One row per device, last-writer-wins. A device that
  turns on and off again leaves *no trace* — the row simply holds the newer value.
- `DeviceCommand` — what *we asked for*, which is a different thing from what happened
  (and says nothing about a wall switch, a sensor, or the device's own timer).

The only stream of transitions that exists is `LocalDeviceEventSource.events()`, and
`routines-engine.md` RE-D11 is explicit that it is **not** storage: bound only in a process
hosting a `DeviceBridge`, `MutableSharedFlow` with a short `replay` tail and
`DROP_OLDEST` — *"losing the oldest of a flood is the right failure"*. It is a trigger bus.
Reading a feed off it would show whatever happened to be in a ring buffer since the app
started.

`home-device-service.md` already lists "telemetry history" as *not in* HD-1's slice. This
doc is that line, taken seriously.

---

## 2. The retention question comes first, not last

`retention-quota-model.md` §2 is emphatic that conflating storage tiers with opposite
retention semantics is what caused the content-plane data-loss bug (time-based blob GC deleting durable content), and its three-tier table is
about **file content**. Device events sit on a different axis, and naming that axis is most
of this design:

> **`DeviceState` is *state*. A device event is a *log entry*.**

State is a value that is replaced; a log is a sequence that accumulates. The two want
opposite policies, and the failure mode of getting it wrong is not data loss but the
reverse — **unbounded growth on a synced type**.

⭐ The platform has already answered this once, for exactly this shape. `RoutineRun`'s KDoc:

> *"one row per firing is unbounded growth on a synced type. The executing device prunes via
> ordinary tombstones — keep the most recent `RETAIN_ROWS` rows or `RETAIN_MS`, whichever is
> smaller. Pruning is the executor's job (it already holds the claim) and is best-effort: a
> missed prune costs rows, never correctness."*

**D1 — A device event log is capped by construction, and the cap is part of the type.**
`RETAIN_ROWS` / `RETAIN_MS`, whichever is smaller, pruned by the writer with ordinary
tombstones, best-effort. Not a setting, not a background sweep to be scheduled later, not
something a user can raise: a row cap is what makes the volume question answerable at
design time rather than after a household with a hallway sensor fills a realm.

⚠ The number is **not** `RoutineRun`'s 200. A routine fires a handful of times a day; a
motion sensor in a hallway fires hundreds. §7 OQ-2 owes the number, and whether the cap is
per-realm or per-device.

---

## 3. Decisions

**D2 — Only the bridge host writes events, and that falls out of observability rather than
policy.** This is RE-D1's argument inherited verbatim: a phone cannot see a device
transition at all, and a remote observer sees only a `DeviceState` row at HD-D2's 3–5 s
poll cadence. The hub sees it in milliseconds because it *is* the bridge.

⭐ A useful consequence: **one writer, so no claim and no multi-device race.** `RoutineRun`
needed RE-D2's executor claim because any device might fire a routine. Nothing analogous is
needed here — the process that observes is the only process that could write, and pruning
has no contender either.

⚠ It follows that a realm with no hub records nothing, and the Activity tab's device filter
stays empty there **forever**, not "until later". The empty state must say which of the two
it is.

**D3 — Only discrete transitions are events. Numeric telemetry is not.** A door is open or
closed; a light is on or off; motion was detected. Those are events, and a feed of them
reads like a story of the home. Temperature, humidity, battery and power draw are *samples*
— they change continuously, they are meaningless as individual rows ("22.3 °C" at 14:07),
and one sensor reporting every 30 s would produce **2,880 rows a day on its own**.

⚠⚠ **This is the decision that turns "a motion sensor is a firehose" into a bounded
problem.** Without it, every design below drowns; with it, a busy household is tens of rows
a day, not thousands.

⚠ Telemetry is not thereby discarded — `DeviceState` continues to carry the current value
exactly as it does now. What does not exist is *history*, and building a time series is a
separate artifact (columnar, downsampled, chart-shaped) that should not be smuggled in as
a feed row. Its own doc, if and when a chart is wanted.

**D4 — Echoes of our own commands are not events.** `ObservedStateChange.lastCommandId`
already carries the `DeviceCommand` that caused a change, and RE-D9 uses it for
self-trigger suppression. The same field answers "did the home do this, or did we ask for
it?" — and a feed that lists *"Sofa lamp turned on"* immediately after the user tapped the
switch in Home is narrating their own tap back at them.

⚠ Not dropped, **attributed**: the row records the cause, and the feed can render a
command-caused change differently (or hide it behind the Automations filter, where the
routine's own `RoutineRun` row already describes it). Two rows for one act is the failure
to avoid.

**D5 — Coalesce a settling value, not every sample of it.** Dragging a brightness slider
0 → 100 emits a change per step. One row per *settled* value after a short quiet window,
per `(deviceId, field)`. ⚠ The window is a hardware question, not an armchair one — it is
DE-2's job to set it against a real bulb (§5).

**D6 — The feed joins the device's name; it does not store it.** The Activity feed adopted
this rule for routine names days ago and the reasoning transfers unchanged: a device is
renamed, and a history that froze the old name is defensible for an audit trail and wrong
for a feed that should say what the thing is called *now*. ⚠ A removed device keeps its
rows — what happened, happened — and they read as a removed device.

**D7 — ⚖️ Consent: one realm switch, and the tab names it.** T-RE-04 already established
that `PresenceState` is *"a new sensitive artifact … it deserves its own consent surface
rather than arriving as a side effect of writing a routine."* A door and motion log is
**strictly richer**: it is when someone came home, which rooms they moved through, and when
the house was empty.

Two things make it a different call from `PresenceState`, and both point the same way:

- The events come from devices the user deliberately paired into their own home, not from a
  phone's location — no new *category* of data enters the realm.
- The Activity tab is one of Home's three tabs. An app whose core surface is empty by
  default, with a switch buried in settings, is a worse product *and* a worse consent
  surface: nobody consents to something they never saw working.

**Recommendation: on by default, one realm-scoped switch, named where the data appears
(the Activity tab) rather than only in Settings.** ⚖️ Flagged for the maintainer because it
is a privacy default, not an engineering one — and because a household is not one person:
the switch is realm-scoped and every member sees the same log.

---

## 4. Shape sketch (not a schema)

```
DeviceEvent            // :home-device-service, additive type
  eventId      String  // ULID-ish: sortable, minted by the one writer (D2)
  deviceId     String  // → HomeDevice; the NAME is joined at read time (D6)
  kind         String  // "power" | "contact" | "motion" | "occupancy" | …
  value        String  // the settled discrete value ("on", "open", "detected")
  atEpochMs    Long    // when the bridge observed it, not when the row synced
  causeCommandId String // "" when the home did it; a DeviceCommand id when we did (D4)
```

⚠ `kind` is a **string vocabulary, not an enum type**, on RE-D10's reasoning: additive
composite fields are peer-breaking today, so a new device class must not need a schema
version to be describable. The renderer maps unknown kinds to a generic line rather than
dropping the row.

⚠⚠ **Read path:** `ActivityKind.Device` maps these to `ActivityEvent` beside the automation
rows that already flow through `SyncedHomeActivityRepository`. That repository's KDoc
currently records *why* the device half is absent; DE-1 replaces that paragraph with the
join.

---

## 5. Phases

- **DE-1 — the log.** The type + registration; the bridge-host writer behind D3/D4/D5; the
  prune; the consent switch; the Activity feed's `Device` kind and the empty-state copy that
  stops saying "not recorded yet". Owes an HR#9 assessment (§7) and a threat-model
  amendment (§6).
- **DE-2 — against real hardware.** ⚠ Hardware-gated, like HD-2's Pi/bulb pass: the quiet
  window (D5), the actual per-day row volume of a real hallway sensor, and whether the cap
  from OQ-2 survives contact with a household. **A number chosen here without a sensor in a
  hallway is a guess wearing a constant.**
- **DE-3 — telemetry time series.** Explicitly *not* this design (D3). Listed so the next
  reader knows it was considered and separated, not forgotten.

---

## 6. Threat model

Amendments DE-1 owes to `docs/sync/threat-model.md`:

- **T-DE-01 — A device event log is a presence record.** Motion and contact events describe
  when a household was in, out, and asleep, at room granularity. It is E2E like all realm
  data, and the mitigations are D1's cap (a bounded window, not a life history), D7's switch,
  and the fact that it never leaves the realm. ⚠ Related to but distinct from T-RE-04: that
  is *where a phone is*, this is *what happened in the house*.
- **T-DE-02 — It is not an audit trail and must never be relied on as one.** Best-effort
  pruning, a hub that may be offline, `DROP_OLDEST` upstream and a row cap mean absence of a
  row is **not** evidence that nothing happened. ⚠⚠ Worth writing into the type's KDoc: the
  first person to reach for this as security evidence will be looking at a feed, not at this
  doc.
- **T-DE-03 — Volume is a quota surface.** Every row is a block, and a realm that fills its
  storage stops syncing — the failure that already reads as *"backup never starts"*. D1's cap
  is the mitigation; DE-2 is what proves the number.

---

## 7. Open questions

- **OQ-1 — ⚖️ Is the default on?** §3 D7 recommends yes with a named switch. A maintainer
  call, not an engineering one.
- **OQ-2 — What is the cap, and is it per-realm or per-device?** A realm-wide 200 (the
  `RoutineRun` number) is minutes of history for a busy home; per-device is fairer but lets
  one chatty sensor crowd every other device out of the *feed* even while its own rows are
  capped. ⚠ Needs DE-2's measured volume before it is answerable.
- **OQ-3 — ⭐ Do these rows need to sync at all?** The Activity tab renders on phones, so the
  obvious answer is yes. But the hub is the only writer *and* a full-replica realm device
  (H3), so a hub-local log with an on-demand read would cost **zero blocks** and no quota —
  at the price of a tab that is empty when the hub is unreachable. This is
  `retention-quota-model.md`'s durable-vs-delivery-log question one level up, and it is the
  one open question that could change the whole shape of DE-1.
- **OQ-4 — Does a removed device's history survive its `HomeDevice` row?** D6 says the rows
  stay. HD-3's note about device *removal* when a device leaves the Zigbee network is the
  other half of that decision and should be settled with it.

---

## 8. House Rule #9 assessment (owed by DE-1, not by this doc)

`DeviceEvent` is a **new** composite in an existing service, so:

- The owning service's `service(id, version = N)` moves, and a `SchemaMigration` step is
  registered — ⚠ **not** `TENSHU_STORAGE_FORMAT_VERSION`, which is `:core`'s alone and
  wipes every store when moved.
- A new type is additive and reshapes no existing composite, so the migration has no rows to
  rewrite — but it must exist, because a bumped service with no registered chain still wipes
  below its derived floor.
- ⚠ `SchemaShapeBaselineEnforcementTest` pins positional layouts; regenerate once the bump
  and the (no-op) chain are in place.
- ⚠ No relay coordination: `RELAY_PROTOCOL_VERSION` is untouched, and the relay stores
  ciphertext (HR #1).
