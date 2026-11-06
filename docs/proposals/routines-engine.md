# Routines Engine — Evaluating and Executing Automations Locally

**Status:** Draft v0.2 — design, no code (2026-07-28; §4 interface sketch + engine loop
added to v0.1). Closes the last device-free design
gap on the Home track: `home-and-mia-apps.md` §9 lists "routines-engine evaluation
semantics" as deferred, and M2 shipped the *editor* over
`MockAutomationsRepository` with nothing beneath it. This doc is design-only — it
introduces no schema, no wire change and no code, so **House Rule #9 is untriggered by the
doc itself**; RE-1 will owe its own assessment (§3, RE-D10).

**Scope:** how an automation is evaluated and executed — which device runs it and why,
how triggers are observed and scheduled, when conditions are read, what an action may be,
what stops a runaway, the schema that replaces the M2 mock, the run history that feeds
Home's Activity tab, and (§4) the interface sketch plus the engine loop the RE-1 slice
builds against.

**Not in scope:** the automation *editor* (shipped in M2), scenes (deferred in
`home-and-mia-apps.md` §3), Skills-contributed actions (post-Rei, D3), the voice pipeline
that will one day deliver a voice-phrase trigger
([`audio-and-voice.md`](audio-and-voice.md)), and camera-detection triggers, which are
CM-4's to define ([`camera-service.md`](camera-service.md)) and arrive at this engine as
ordinary device events.

---

## 1. The shape in one paragraph

A `Routine` is realm data, so every proxy in the realm holds it — and the whole design
problem is that a naive engine therefore runs "turn on the porch light at sunset" once per
device. The answer is not an election: **which device may execute a routine falls out of
which device can *observe* its trigger**, and for the one trigger class where several
devices can (time and sun) a **deterministic claim** — computed identically everywhere from
the synced device roster, with a short **grace fallback** for a primary that is asleep —
picks one with zero coordination messages. Every firing has a **deterministic `firingId`**,
and the synced `RoutineRun` row keyed by it is simultaneously the cross-device dedupe
receipt *and* the history row Home's Activity tab renders. Device-event triggers are
evaluated **inside the hub's bridge host, before the state row is even published**, which
is where the product claim gets its literal form: when the hub is both executor and bridge
host, a routine runs **with no network I/O at all** — not even to the relay.

---

## 2. What exists, and what is missing

Shipped and reusable:

- **The taxonomy and the editor** (M2): `Automation` = 1 trigger + 0..n conditions +
  1..n actions, `enabled`, `lastRunAt`; `Trigger.Schedule|Sun|Geofence|DeviceEvent|
  VoicePhrase`, `Condition.TimeWindow|Presence|DeviceIs`, `Action.DeviceCommand|Notify|
  RunAutomation` (`home-shared-logic/.../AutomationModel.kt`), behind an
  `AutomationsRepository` seam with a mock implementation — exactly the seam
  `DeviceSource`/`HubStatusSource` used to go live.
- **The device plane**: `HomeDevice` / `DeviceState` / `DeviceCommand`, the
  `IssueDeviceCommand` op, the hub-side `DeviceBridgeHost` with its consumed-command
  journal and 2-minute staleness window, and the `DeviceControlSource` vocabulary
  (`setPower`, absolute; `Pending`/`Failed`/`Unconfirmed` resolved on the echoed
  `lastCommandId`) — `home-device-service.md` HD-1..HD-3.
- **Scheduling**: `ScheduleService` + persisted `ScheduleItem` (`ScheduleSpec`,
  `ConstraintSpec`, `RetryPolicy`) drained in-process by `ScheduleDrainLoop` and handed to
  the OS by a per-platform `PlatformScheduler` — "one store, two drivers"
  (`core-background-processing.md` §5.1/§8).
- **Notifications**: the `SendNotification` op on `:notification-service`.
- **A precedent for exactly this coordination problem**: the compaction design in
  `core-schema-evolution.md` §8.3 — **no leader election, no quorum**, dedup by a synced
  receipt row, because there is no adversary inside the trusted device set.

Missing: literally everything that evaluates or executes. There is no engine, no synced
routine row, no run history, and no answer to "which device runs it".

---

## 3. Decisions

### RE-D1 — Executor eligibility falls out of observability, not policy

The instinct is to declare an executor ("the hub runs routines") or to let everyone run and
sort it out afterwards. Both are wrong, and the reason is that the trigger classes have
genuinely different physics:

| Trigger class | Who can observe it | Latency reality |
|---|---|---|
| **Time / Sun** | every device with a resident proxy | ⚠ Android restricts exact alarms; iOS `BGTaskScheduler` gives no timing guarantee at all (sim `submit` fails outright — `ios-drive-parity.md` P6a). A phone genuinely cannot promise "at sunset". |
| **Device event** | ⭐ only the process hosting the bridge | A remote observer sees the change as a `DeviceState` row at the HD-D2 3–5 s poll cadence — useless for motion→light. The bridge host sees it in milliseconds. |
| **Geofence** | only a device with a location capture source | ⚖️ A hub can *never* fire these; only the phone that crossed the boundary knows. |
| **Voice phrase** | the device that received the utterance | Arrives as an explicit invocation, not a background evaluation (RE-D8, MQ-3). |

⭐ **The insight worth remembering: three of the four classes have exactly one eligible
observer by construction, so the multi-device problem is not general — it is confined to
time and sun.** That is what makes the rest of this design small. "The hub runs routines"
is wrong because it can never fire a geofence; "every device runs routines" is wrong
because it triples every device command and because three devices reacting to a motion
event at 3–5 s is three wrong answers rather than one.

So: **each trigger class declares its eligible-executor predicate, and the engine on a
given device simply does not arm a routine whose trigger it cannot observe.** No
negotiation, no configuration, no per-device routine assignment UI.

⚠ Consequence that must be stated in product terms rather than hidden:
**device-event routines do not run at all when there is no hub.** The editor must gate
them — greyed with "needs a Tenshu Hub", never offered and silently dead. This is the
HD-2 precedent verbatim: the Add-device FAB opens the pairing dialog only when a
`CAP_PERMIT_JOIN` bridge exists, because *Home must never offer a flow it cannot honour*.
§5 states the full no-hub degradation.

### RE-D2 — Time/sun: a deterministic claim plus a grace fallback. No election, no quorum

For the one contended class, the coordination model is `core-schema-evolution.md` §8.3's,
sharpened. Compaction could tolerate a race because re-emission is idempotent and
convergent; **a routine firing is not** — three `Notify` actions are three notifications.
So a receipt alone is not enough, and the reason is timing:

⚠⚠ **A synced receipt cannot prevent a race, only record one.** The sync round trip
(30 s default cadence; 3–5 s on a fast-polling hub) is *longer* than the action it is
meant to suppress. A receipt written by the desktop at 21:04:00 does not reach the phone
before the phone fires at 21:04:00. Any design that leans on the receipt as the primary
mutual-exclusion mechanism is broken by construction.

The mechanism is therefore **assignment, not agreement**:

1. **Deterministic claim.** Every device computes the same pure function
   `executorFor(routineId, roster)` over the **synced device roster** it already
   holds. ⚠ Note there is deliberately **no `now` parameter** (§4.3): a time-varying claim
   would let two devices disagree across a tick, which is the one thing this determinism
   exists to prevent. The grace fallback below is applied by the *caller*, which is where
   time belongs. Ordering: *capability tier* first — a device that publishes a `HubStatus` row
   (always-on, resident, punctual) outranks a resident desktop, which outranks a phone —
   then a stable tiebreak on `deviceId`. Pure, offline, message-free, and identical on
   every device that has converged.
   ⚖️ Rendezvous hashing (spreading routines across peers) was considered and **rejected as
   premature**: a household has tens of routines, not thousands, and a stable single
   executor makes run history far easier to read.
2. **Grace fallback.** The primary fires at `T`. Any *other eligible* device fires at
   `T + GRACE` (default **90 s**) **only if** no `RoutineRun` receipt for this `firingId`
   has arrived. 90 s is chosen to exceed one full sync round trip on the default cadence,
   so the happy path never double-fires; a fallback firing is *late by design* and the run
   record says so.
3. **A genuine race is contained, not prevented.** Two devices firing inside the grace
   window write the same `firingId`-keyed row (LWW-merged to one) and may run the actions
   twice. That is survivable **only because the action vocabulary is absolute** (RE-D8):
   `setPower(on)` twice is `on`; `Notify` collapses on its dedupe key; `RunRoutine`
   inherits the same `firingId` root and collapses on its own receipt.
   ⭐ This is exactly why HD-3's `setPower`-not-`toggle` decision turns out to be
   load-bearing far outside the UI it was made for.

⚠ Roster churn: a device that goes offline permanently keeps winning the claim until it is
revoked, and its routines then only fire on the 90 s fallback. That is a mild, visible,
self-explaining degradation ("everything runs 90 s late") — and the existing device-
management path (ADR 0007 revocation) is the fix the user already holds. Deliberately **no
liveness heartbeat**: it would be a new synced hot row whose staleness window would have to
be tuned, to buy 90 s.

### RE-D3 — `firingId` is the unit of idempotence, and the receipt *is* the history row

Everything above depends on two devices independently agreeing on the *name* of a firing.
`routineId` alone is not enough (which sunset?), and a random id defeats the receipt.

- **Scheduled triggers:** `firingId = H(routineId ‖ triggerInstantEpochMs)`. Both devices
  compute the same `triggerInstant` because the routine carries its **own timezone id and
  its own home coordinates** (RE-D10) — ⚠⚠ *never* the executing device's. A laptop
  travelling in another timezone must not compute a different instant for "22:30", and a
  desktop 400 km north must not compute a different sunset. This is the single most likely
  way to build a subtly broken version of this engine.
- **Device-event triggers:** derived from the observed event's own identity
  (`deviceId ‖ freshAsOf` of the state transition). Only one device is eligible anyway; the
  determinism buys crash-restart idempotence, not cross-device dedupe.
- **Chained and manual firings** carry the root `firingId` plus an index, so a chain is one
  traceable unit.

⭐ **The receipt and the history row are the same row.** A receipt that carries its outcome
is precisely what Home's Activity tab needs, so there is no second type and no risk of the
two disagreeing. `RoutineRun` (RE-D10) is keyed by `firingId` and moves
`Claimed → Ran | Failed | Skipped | Missed | Throttled`.

⚠ Its `lastRunAt` is **derived** from these rows, and deliberately *not* a mutable field on
`Routine`. A `lastRunAt` on the config row would make every firing a write to the row the
user edits — racing a rename into an LWW clobber, and turning a cold config row into a hot
one that emits a block on every sunset. The M2 UI model keeps its `lastRunAt`; the
repository derives it.

### RE-D4 — Two dedupe mechanisms, two jobs

⭐ **The synced receipt dedupes across *devices*; a device-local durable journal dedupes
across *restarts*.** They are not redundant and neither can do the other's job: sync is far
too slow to guard a restart, and a local journal is invisible to peers.

The local half is the H4 / HD-1 discipline verbatim: the executor appends `firingId` to a
durable journal and writes the `RoutineRun` row in state `Claimed` **before** running any
action, then updates to `Ran`. **A crash mid-run therefore drops the routine rather than
repeating it** — at-most-once, matching the hub's `ConsumedCommandJournal` posture, and for
the same reason: a repeated physical actuation is worse than a missed one.

⚠ A `Claimed` row that never advances is *visible* in run history (rendered as
"interrupted"), which is strictly better than the silence a purely-local journal would give.

### RE-D5 — Scheduling: one next-wake item, a new *punctual* class, and an empty `ConstraintSpec`

- **One `ScheduleItem`, not N.** The engine keeps a single `ONE_SHOT` item armed to the
  *earliest* upcoming firing across all routines it is eligible for, re-armed after every
  fire and after every routine edit. `ScheduleItem` is param-less by design
  (`core-background-processing.md` Phase 5) and that is fine here: the item's op is one
  `EvaluateDueRoutines`, which finds its own work. One OS alarm beats N.
- ⚠ The op **must carry the `HeadlessSafe` trait** — `ScheduleImplementation` fail-closed
  rejects anything else at registration, which is the correct gate and will otherwise be
  discovered as a confusing runtime refusal.
- ⚠⚠ **`ConstraintSpec` must be empty.** Idle + charging + unmetered is the *compaction*
  scheduling class (§8.3) and is exactly wrong for routines: a routine that only fires while
  the phone is on the charger is not a routine. Nothing about a routine is deferrable
  maintenance.
- **`PlatformScheduler` grows a punctual class.** Today the seam expresses cadence +
  constraints; a routine needs "at this instant". Recommended shape: a boolean on
  `ScheduleSpec` (`punctual`) that platforms map as they can —
  - *hub / desktop*: a resident process with a timer. Nothing needed; this is the case that
    actually works.
  - *Android*: `AlarmManager.setExactAndAllowWhileIdle` when `SCHEDULE_EXACT_ALARM` is
    granted, else `setAndAllowWhileIdle` (inexact but Doze-aware — still far better than
    WorkManager's 15-minute floor, which is the wrong tool entirely).
    ⚖️ **Do not request `USE_EXACT_ALARM`**: it is Play-policy-restricted to alarm-clock and
    calendar apps and claiming it for a smart-home app is a review risk. Ask for
    `SCHEDULE_EXACT_ALARM` *only* at the moment a user creates a time routine in a
    household with no always-on device, with copy that says why — the one configuration
    where it is genuinely load-bearing.
  - *iOS*: `BGTaskScheduler`, best-effort, honestly labelled. ⚠ Route-1 first-party apps
    inherit `ios-drive-parity.md` P6a's finding that live wake is hardware-gated.
    ⚠⚠ Superseded by RE-D13: an iPhone never executes a routine.

### RE-D6 — Missed triggers: a bounded catch-up, then a recorded `Missed`

A proxy that was asleep at `T` wakes at `T + Δ`. Fire late, or skip?

**Decision: fire if `Δ ≤ CATCHUP_WINDOW` (default 15 min), otherwise skip and record
`Missed`.** Both extremes are wrong: "always fire late" is how a laptop resuming from a
weekend suspend turns the lights on at 3 a.m. (the T-HD-01 hazard, one layer up); "always
skip" silently drops the sunset routine for a household whose desktop woke two minutes late
and looks like the feature is broken.

⭐ **Skipped-and-recorded is the third option and it is the right one.** A `Missed` row in
the Activity tab is an honest, self-explaining artifact; silence is not.

⚠ A tempting misreading to head off: `DeviceCommand.STALE_WINDOW_MS` (2 min) does **not**
bound this. `IssueDeviceCommand` stamps `issuedAt` at *issue* time, so a routine firing 14
minutes late issues a perfectly fresh command that the hub happily executes. The device
staleness window guards *sync backlog replay of the command row*; `CATCHUP_WINDOW` guards
*the engine deciding to act late*. They are independent guards against different failures,
and the engine's is the only one standing between a suspended laptop and a 3 a.m. porch
light.

### RE-D7 — Conditions: one snapshot, taken at action time, that cites its inputs

- **Evaluated at action time, not trigger time**, against a **single snapshot** taken once
  immediately before the first action. Trigger-time evaluation is wrong ("only after
  sunset" must be true when the light actually goes on); per-action evaluation is worse —
  action 3 would see a world that action 1 changed, which is the seed of the feedback loops
  RE-D9 exists to prevent.
- **The snapshot is cited in the run record.** Each condition records what it read and the
  `freshAsOf` of the row it read it from. ⚠ `DeviceState` rows are up to a publish interval
  + a sync round trip old, so "only if the kitchen light is off" evaluated on a phone can be
  wrong — and a wrong outcome that cites its inputs is debuggable while a wrong outcome that
  does not is a support ticket. (Same instinct as MQ-D3's inspectable `QueryPlan`.)
- ⭐ **Device-state conditions pin the executor to the hub when one exists**, upgrading
  RE-D2's capability tier from a preference to a requirement for those routines. The hub's
  view of device state is the authoritative one — it authored it.
- **Presence is scoped OUT of RE-1..RE-3.** ⚠ There is no presence notion anywhere in the
  codebase; the M2 editor offers `Condition.Presence` over a mock and nothing could honour
  it. It is gated (greyed, "coming with geofences") until **RE-4**, where the minimum design
  is:
  - a `PresenceState` row per location-capable device (`inside`/`outside`/`unknown` +
    `freshAsOf`), authored by that device off the same geofence machinery RE-4 needs for
    triggers anyway — no second mechanism;
  - "nobody's home" = every non-revoked, location-capable, non-hub device reports `outside`
    **and** reported recently;
  - ⚠⚠ **fail closed**: any `unknown` or stale device makes the condition `Unknown` and the
    routine does **not** run. The naive implementation fails open, and "arm the alarm when
    nobody's home" firing because someone's phone battery died is the worst possible
    failure mode of this entire engine.

### RE-D8 — Actions: closed, local, absolute; continue on failure; depth-guarded

**The action vocabulary is closed and entirely local. There is no webhook, no HTTP action,
and there never will be one without a separate egress decision.** ⭐ This is what makes
"routines execute locally on the proxy — no cloud round-trip" a *structural* property
rather than an aspiration: IFTTT is webhooks; Tenshu's engine cannot reach the network even
if a routine asked it to. It also matches the posture the apps already take at the OS
boundary — Home, Maps and Timeline ship with `INTERNET` stripped; all egress is Control's.

v1 vocabulary, all over shipped ops:

- **Device command** — `IssueDeviceCommand`, and **absolute only**: `setPower`, `setLevel`;
  ⚖️ `toggle` is deliberately not exposed, inheriting the HD-3 follow-up reasoning
  unchanged (a toggle expresses a flip relative to a reading that may already be stale, and
  the two disagree exactly when it matters). For an engine that may fire twice under RE-D2's
  race, absoluteness is not a nicety — it is the containment.
- **Notify** — `SendNotification`, carrying a dedupe key derived from the `firingId` so a
  duplicated firing collapses to one notification.
- **Run another routine** — chained through the same engine (see the guard below).

**Failure semantics for a partial list: continue, record each action's outcome, and mark the
run `Failed` if any action failed.** ⭐ *There is no rollback for a light bulb.* Aborting on
first failure would leave the home in a half state with no signal, and the failure we
actually expect (one bulb offline) is precisely the case where the rest should still run.
Actions run sequentially in author order so ordering intent is preserved.

⚠ **A run record is not final when the run ends.** Issuing a `DeviceCommand` returns as soon
as the row is written; the physical outcome arrives later on the `DeviceState` row. The
engine inherits HD-3's vocabulary verbatim — `Pending` → `Idle` on `OUTCOME_OK`, `Failed` on
a hub-reported failure, **`Unconfirmed`** after the timeout — and patches the run record when
the echo arrives, correlating on the echoed `lastCommandId`, never on value equality.

**Recursion guard.** A firing carries `depth` and the chain of routine ids that produced it:

- `depth > MAX_CHAIN_DEPTH` (default **4**) → refused, recorded as `Failed` with the reason;
- a routine already present in the chain → refused as a **cycle**, recorded and *named* in
  the Activity feed.
- ⭐ The cycle check is per-firing, not a global rate limit, because two mutually-referencing
  routines are an authoring mistake the user should be *told about*, not silently throttled.
  (The throttle in RE-D9 catches the loops that are not syntactically visible.)

### RE-D9 — Safety rails: causal self-trigger suppression, a throttle, and `SUSPENDED` as a third state

A routine whose action changes the state its own trigger watches is a feedback loop, and
this is the class of thing that is miserable to retrofit.

1. ⭐⭐ **Self-trigger suppression rides the `commandId` — for free, with no schema change.**
   `DeviceCommand`'s id is *caller-minted*, and the hub already echoes it back as
   `lastCommandId` on the resulting `DeviceState` row. So the engine mints
   `commandId = "firing:<firingId>:<index>"`, and any device-event trigger whose state
   transition carries a `lastCommandId` naming a firing of *this* routine is suppressed.
   Causality is already on the wire; we only have to encode it.
   ⚠ Note the alternative that must **not** be taken: adding an `issuedForFiringId` field to
   the shipped `DeviceCommand` composite would be a peer-breaking positional-composite change
   owing an HR#9 bump — for information the `commandId` can already carry.
2. **Per-routine throttle.** A token bucket, default **10 firings per rolling 10 minutes**.
   Excess firings are dropped and recorded as `Throttled`. ⚠ This is also a *sync* brake:
   every firing writes a `RoutineRun` row, so a flapping sensor without a throttle floods the
   realm with blocks — the same argument that produced `DeviceStatePublishGate`.
3. **Runaway → `SUSPENDED`, a third state.** Sustained throttling (default: throttled in
   3 consecutive windows, or 30 firings in an hour) suspends the routine and notifies the
   user with the reason.
   ⚠⚠ **Suspension must not be written as `enabled = false`.** That would be indistinguishable
   from the user's own toggle, would silently destroy their intent, and would leave them
   re-enabling a routine with no idea why it stopped. `SUSPENDED` is a distinct, synced state
   with a reason string, and re-enabling is one tap plus an explanation.
4. **Pause all.** A per-realm "pause automations" switch (both Google and Apple ship one;
   it is what people actually want when hosting or away). Small, and it belongs with the
   safety rails rather than as a feature.

### RE-D10 — Schema: `Routine` + `RoutineRun`, with the program as one versioned field

Two new composites, both `Syncable(Eager)`, both additive **new** types — so, on the
`MovementTrace` / HD-1 precedent, **RE-1 as designed owes no `TENSHU_STORAGE_FORMAT_VERSION`
bump and no relay change**. ⚠ Any deviation that adds a field to a *shipped* composite
(`DeviceCommand`, `DeviceState`, `HomeDevice`) does owe one, and its PR must say so — HR#9.
Both types should carry the `RealmData` marker so Control's Data section enumerates them
for free (`core-realm-data.md`).

**`Routine`** — keyed by `routineId`. Scalars: `name`, `enabled`, `state`
(`ACTIVE`/`SUSPENDED`) + `suspendedReason`, `timeZoneId`, `homeLatE5`/`homeLonE5`,
`authorDeviceId`, `createdAtEpochMs`, `updatedAtEpochMs`, `extras` (`genericData`).
Then the load-bearing one:

⭐ **The program — trigger + conditions + actions — rides as ONE bounded, versioned string
field (`programJson` + `programVersion`), not as nested composite lists.** Three reasons,
each independently sufficient:

1. ⚠⚠ Positional composites make *every* future trigger or action kind a peer-breaking
   change, and the vocabulary is **guaranteed** to grow — D3 explicitly requires the action
   vocabulary to stay extensible so post-Rei Skills can contribute actions. A shape that
   makes its own stated roadmap a format bump each time is the wrong shape.
2. Native list fields on a keyed row are a known footgun in this codebase (they read back
   empty over the chain — HD-1 encoded `capabilityIds` as a space-separated string for
   exactly this reason), and `TList` members need a `DataIdProvider` (the E2d-1 finding).
3. The preference service's `genericData` slot stores inline primitives and composite refs,
   **not arrays**, so it does not solve this either; HD-2's "bounded JSON in a string" is the
   established escape hatch.

⚠ **The cost, stated plainly:** the program is opaque to the chain DSL, so "every routine
targeting device X" cannot be a server-side query. Accepted — a household has tens of
routines, so the engine loads them all and filters in memory.

⭐ `programVersion` is a small but necessary fence: **a device whose engine does not
understand a routine's `programVersion` must neither execute it nor claim the executor slot
for it** — it falls through to the RE-D2 grace fallback on a device that does. Without this,
a routine authored on an updated phone gets silently mangled by an older desktop, which is
precisely the HR#9 silent-garbling class re-created inside a single row. ⚠ This is *not* a
re-implementation of schema-evolution E2: E2's epoch fences *readability* of realm data;
this fences *execution eligibility* for one row, and the vocabulary moves far more often
than the format does.

**`RoutineRun`** — keyed by `firingId` (RE-D3). `routineId`, `triggerInstantEpochMs`,
`executorDeviceId`, `claimedAtEpochMs`, `finishedAtEpochMs`, `state`
(`CLAIMED`/`RAN`/`FAILED`/`SKIPPED`/`MISSED`/`THROTTLED`), `wasFallback`, `chainRootFiringId`
+ `depth`, and two bounded strings: `conditionCitations` (RE-D7) and `actionOutcomes`
(RE-D8). ⚠ Be generous with the scalar set up front, per HD-D1 — later optional additions are
peer-breaking.

⚠ **Retention.** One row per firing is unbounded growth on a synced type. The executing
device prunes via ordinary tombstones: keep the most recent **200 rows or 30 days**,
whichever is smaller. Pruning is the executor's job (it already holds the claim), and it is
best-effort — a missed prune costs rows, never correctness.

### RE-D11 — `:routine-service`, and no hub-only code path

- Module set `:routine-service` + `-common`/`-client`/`-proxy`, Service & Operation pattern
  (`docs/agent.md` §2), symmetric with `:home-device-service`. Ops: `SaveRoutine`,
  `DeleteRoutine`, `SetRoutineEnabled`, `RunRoutineNow`, and the `HeadlessSafe`
  `EvaluateDueRoutines`. HR#3 holds — `IoOperation` + `TaskInteractor`, no `suspend` on the
  operation interfaces.
- ⭐ **The engine lives in `-proxy` and runs in every proxy; the hub is not special-cased.**
  It is simply the device that usually wins the RE-D2 claim. The one genuinely hub-local
  piece is the device-event fast path, and even that is a seam: an optional
  `LocalDeviceEventSource` bound only in a process that hosts a `DeviceBridge`, so the
  engine observes bridge events **before** `DeviceBridgeHost` publishes the state row.
- ⭐ **The zero-network property falls out, with no new mechanism.** When the executor is also
  the bridge host, the engine's `DeviceCommand` row is written to the *local* store, the
  local observer (`deviceCommands.flow()` — already how `DeviceBridgeHost` works) fires
  immediately, and the bridge acts. The relay is never in the path; the row syncs afterwards
  purely as the audit trail. That is the product claim in its literal, strongest form.
- ⚖️ **Naming: `Routine` in code, "Automations" in the UI.** The milestone doc says routines,
  the shipped and localised UI says automations. Renaming shipped, translated strings for
  internal consistency is pure churn; the mapping is one line in the repository.
- Home consumes it exactly like the device plane did (HD-D5): the real implementation slots
  behind the existing `AutomationsRepository` seam, mock preserved as the honest fallback,
  desktop first and Android with the wiring slice.

### RE-D12 — Personal realm only, in v1

Routines are evaluated against, and act on, the **personal realm** only. A routine in a
shared realm would let one member's automation act on another member's home, which is a
permission question this doc does not open — and it echoes E2d-2's decision not to fence
shared realms (a constant epoch key means "personal realm" there too). Shared-realm
automations are a future track that needs the granular capability model already flagged in
`ios-first-party-route-1-migration.md` §4 and CD-D8.

### RE-D13 — An iPhone lists and stores routines; it never executes one (2026-10-02)

Maintainer decision. The iOS proxy serves the routine rows and `SaveRoutine` /
`DeleteRoutine` / `SetRoutineEnabled`, and builds no engine. Two reasons:

1. **No wake.** iOS binds `NoOpPlatformScheduler`, so an armed next-wake item has nothing to
   deliver it — this supersedes RE-D5's *iOS: `BGTaskScheduler`* bullet.
2. **Several evaluators, one identity.** The iOS `PlatformProxyModule` is every route-1 app's
   runtime: each process would run its own evaluation over one shared store under one
   `deviceId`, so several could fire the same routine — the Calendar double-reminder shape.

How it is enforced: `routineServiceProxyImplementationFactory` takes a **required**
`execution: RoutineExecution`; iOS passes `NEVER`, under which `RunRoutineNow` and
`EvaluateDueRoutines` throw a typed error (*"Routines do not run on this device"*) without
constructing a `RoutineEngine`. Nothing else on that platform builds one — no
`RoutineEventHost`, no armer. Pinned by `RoutineExecutionTest` and `RoutineExecutionWiringTest`.

⚠⚠ **Open: other devices still count the iPhone as an eligible executor.** RE-D2's roster is
built from `Device` rows (`RoutineRosterSource`), which carry no platform signal, so an iPhone
is `RESIDENT` like any desktop. With a hub in the realm nothing changes (the hub outranks it).
Without one, if the iPhone's `deviceId` sorts first it holds the claim for every time/sun
routine and never fires, so the others fire on the 90 s grace fallback, each run marked
`wasFallback` — late, never doubled, never lost while another device is awake. Excluding it
needs a synced per-device signal that every executor reads, which is a schema or wire decision
and was deliberately not taken with the wiring.

⚠ Mia on iOS still offers the *Run "…"* confirmation cell (RE-5a); the tap fails with the typed
error above.

---

## 4. Interfaces

Sketch, not signatures to copy verbatim — names and shapes matter more than exact
parameter lists at this stage. Two rules run through all of it:

⚠⚠ **These are code-side types, NOT schema types.** The only schema composites are `Routine`
and `RoutineRun` (RE-D10); the program is a *string field* on `Routine`, and everything in
§4.1 is what that string parses into. Do not add `Trigger`/`Condition`/`Action` to the
schema DSL — that is precisely the peer-breaking shape RE-D10 rejected, and HR#4 does not
apply because none of this crosses the wire.

⭐ **Everything in §4.1–§4.5 is pure.** No IO, no clock, no store — that is what makes §6's
"fake clock plus `SimulatedDeviceBridge`, zero hardware" a design property rather than an
aspiration. The IO seams are quarantined in §4.6–§4.8.

### 4.1 The program (`:routine-service-common`, pure)

```kotlin
class RoutineProgram(
    val version: Int,
    val trigger: Trigger,
    val conditions: List<Condition>,   // 0..n
    val actions: List<Action>,         // 1..n, executed in author order
)

sealed interface Trigger {
    class TimeOfDay(val at: LocalTime, val days: Set<DayOfWeek>) : Trigger
    class Sun(val event: SunEvent, val offsetMinutes: Int) : Trigger
    class DeviceEvent(val deviceId: String, val match: StateMatch) : Trigger
    class Geofence(val placeId: String, val direction: Direction) : Trigger  // RE-4
    class VoicePhrase(val phrase: String) : Trigger                          // RE-5
}

sealed interface Condition {
    class TimeWindow(val from: LocalTime, val to: LocalTime) : Condition
    class DeviceStateIs(val deviceId: String, val match: StateMatch) : Condition
    class Presence(val expect: Occupancy) : Condition                        // gated → RE-4
}

sealed interface Action {
    class SetPower(val deviceId: String, val on: Boolean) : Action
    class SetLevel(val deviceId: String, val percent: Int) : Action
    class Notify(val title: String, val body: String) : Action
    class RunRoutine(val routineId: String) : Action
}
```

⚠ **What is absent is load-bearing.** There is no `Toggle` (RE-D8 — absolute commands are
what contain the RE-D2 race) and no `Webhook`/`HttpRequest` (RE-D8 — the closed local
vocabulary is what makes "no cloud round-trip" structural). Both omissions should carry a
comment in the source saying so, or someone will helpfully add them.

### 4.2 Parsing and the version fence (pure)

```kotlin
interface RoutineProgramCodec {
    fun parse(json: String, version: Int): ProgramParse
    fun encode(program: RoutineProgram): EncodedProgram   // -> programJson + programVersion
}

sealed interface ProgramParse {
    class Parsed(val program: RoutineProgram) : ProgramParse
    class UnsupportedVersion(val version: Int) : ProgramParse
    class Malformed(val reason: String) : ProgramParse
}
```

⭐ **`UnsupportedVersion` is a value, never an exception.** RE-D10's fence needs two distinct
consequences from one verdict — *do not execute* **and** *do not claim the executor slot* —
so the caller must be able to branch on it. A thrown parse error would collapse both into
"something went wrong" and the routine would silently never run anywhere.

### 4.3 Eligibility and the claim (pure — RE-D1, RE-D2)

```kotlin
fun interface TriggerObservability {
    /** RE-D1: can THIS process observe this trigger class at all? */
    fun canObserve(trigger: Trigger): Boolean
}

interface ExecutorClaim {
    /** RE-D2: pure, offline, message-free; identical on every converged device. */
    fun executorFor(routineId: String, roster: List<RosterDevice>): String
}

class RosterDevice(val deviceId: String, val tier: CapabilityTier, val revoked: Boolean)

enum class CapabilityTier { ALWAYS_ON, RESIDENT, MOBILE }   // ordering IS the priority
```

⚠ `executorFor` takes **no clock**. A time-varying claim would let two devices disagree
across a tick, which is the one thing RE-D2's determinism exists to prevent. The 90 s grace
fallback is applied by the caller, not baked into the claim.

### 4.4 Firing identity (pure — RE-D3)

```kotlin
@JvmInline value class FiringId(val value: String)

object FiringIds {
    fun scheduled(routineId: String, triggerInstantEpochMs: Long): FiringId
    fun deviceEvent(routineId: String, deviceId: String, freshAsOfEpochMs: Long): FiringId
    fun chained(root: FiringId, index: Int): FiringId
}
```

### 4.5 Next firing, conditions, safety (pure)

```kotlin
class RoutineLocale(val timeZoneId: String, val homeLatE5: Int, val homeLonE5: Int)

interface FiringCalculator {
    fun nextFiring(trigger: Trigger, locale: RoutineLocale, after: Instant): Instant?
}
```

⭐⭐ **`RoutineLocale` is passed in, and the device's own zone and coordinates are not
parameters at all** — RE-D3's sharpest trap ("a laptop abroad computes a different instant
for 22:30") becomes *unrepresentable* rather than merely documented. This is the single
most valuable shape in the section.

```kotlin
interface ConditionSnapshot {                       // RE-D7: taken ONCE, at action time
    val takenAtEpochMs: Long
    fun deviceState(deviceId: String): ObservedState?
    fun presence(): Occupancy?                      // null = unknown → fail closed
}

interface ConditionEvaluator {
    fun evaluate(conditions: List<Condition>, snapshot: ConditionSnapshot): ConditionVerdict
}

sealed interface ConditionVerdict {
    val citations: List<Citation>
    class Met(override val citations: List<Citation>) : ConditionVerdict
    class NotMet(override val citations: List<Citation>) : ConditionVerdict
    class Unknown(override val citations: List<Citation>, val reason: String) : ConditionVerdict
}

class Citation(val what: String, val read: String, val freshAsOfEpochMs: Long)
```

⭐ **`Unknown` is a third verdict, distinct from `NotMet`** — this is RE-D7's fail-closed
presence rule made structural. A two-valued verdict is exactly how "arm the alarm when
nobody's home" fires because a phone battery died. ⚠ Citations ride on *every* verdict,
including `Unknown`; an unexplained non-run is the support ticket RE-D7 is trying to avoid.

```kotlin
interface SafetyGate {                              // RE-D9 — pure, token bucket + guards
    fun admit(firing: Firing, now: Instant): Admission
}

sealed interface Admission {
    data object Admit : Admission
    class Throttled(val retryAfter: Duration) : Admission
    class Suspended(val reason: String) : Admission
    class CycleRefused(val chain: List<String>) : Admission
    class DepthExceeded(val depth: Int) : Admission
}

class Firing(
    val id: FiringId,
    val routineId: String,
    val triggerInstantEpochMs: Long,
    val chainRoot: FiringId,
    val depth: Int,
    val wasFallback: Boolean,
)
```

⚠ Each refusal is a *named* variant rather than a boolean, because RE-D8/RE-D9 require the
reason to reach the Activity feed — a cycle the user authored should be reported to them,
not silently dropped.

### 4.6 The IO seams (`-proxy`)

```kotlin
interface RoutineFiringJournal {                    // RE-D4: local, durable, restart dedupe
    suspend fun beginFiring(id: FiringId)           // BEFORE any action runs
    suspend fun completeFiring(id: FiringId)
    suspend fun incompleteFirings(): List<FiringId> // replayed at init; at-most-once
}

interface ActionExecutor {
    suspend fun execute(action: Action, firing: Firing): ActionOutcome
}

sealed interface ActionOutcome {                    // HD-3's vocabulary, inherited verbatim
    data object Ok : ActionOutcome
    class Pending(val commandId: String) : ActionOutcome   // patched on the echoed lastCommandId
    class Failed(val reason: String) : ActionOutcome
    data object Unconfirmed : ActionOutcome
}

/** RE-D11: bound ONLY in a process that hosts a DeviceBridge. Absent elsewhere. */
interface LocalDeviceEventSource {
    fun events(): Flow<ObservedStateChange>
}
```

⚠ `LocalDeviceEventSource.events()` must **replay a short tail**, per HD-2's finding that a
`replay = 0` hot flow silently drops retained state that lands before the collector attaches.
The same bug class applies here and costs a missed trigger rather than an empty inventory.

⚠ `commandId` is minted by the engine as `"firing:<firingId>:<index>"` (RE-D9) — the
self-trigger suppression depends on it being derivable, so it is not an implementation
detail of `ActionExecutor` but part of its contract.

⚠ HR#3 note: `ActionExecutor` and the journal are **engine-internal seams**, not
`Operation` interfaces, so `suspend` is fine here. The service ops in §4.7 follow HR#3
unchanged.

### 4.7 The service surface (`:routine-service`, RE-D11)

Ops per the Service & Operation pattern (`docs/agent.md` §2), `IoOperation` +
`TaskInteractor`, no `suspend` on the operation interfaces (HR#3):

| Op | Notes |
|---|---|
| `SaveRoutine` | create + update; encodes the program via §4.2 |
| `DeleteRoutine` | |
| `SetRoutineEnabled` | ⚠ user intent only — never used for `SUSPENDED` (RE-D9) |
| `RunRoutineNow` | manual firing; mints a fresh chain root |
| `EvaluateDueRoutines` | ⚠⚠ **must carry `HeadlessSafe`** — `ScheduleImplementation` fail-closed rejects anything else at registration (RE-D5) |

⚠ Per `core-operation-task-return.md`, new ops should declare returns as the sealed
`Interactor` family rather than a bare `RequestObject` if that lands first — this service is
a natural early adopter, and the `Workout.Streams`/TS-4 RO-factory crash class is exactly
what it avoids.

⚠ Client-module Ro registration is **not optional**: an op whose Ro is registered only in
the common module dies as an opaque `error/internal` at every call site (the
`GetWorkoutStreamsRo` finding). Register in the *client* module.

### 4.8 Clock

```kotlin
fun interface Clock { fun now(): Instant }
```

⚠⚠ **No engine code calls a platform `now()` directly.** Every time source — the calculator,
the token bucket, the catch-up window, the grace fallback — takes this. A single direct call
to `Clock.System.now()` anywhere in the engine makes the fake clock worthless and silently
un-tests the §6 boundary tables.

### 4.9 The loop

The decisions above constrain the *order* of operations more tightly than they might look.
This section pins that sequence, because several of the guards are only guards if they
happen at the right point — a journal write after the first action is not an at-most-once
journal, and a condition snapshot taken at trigger time is not RE-D7's snapshot.

#### Entry points

The engine is passive; it is woken by five things.

| Woken by | Path |
|---|---|
| `EvaluateDueRoutines` (the armed `ScheduleItem` fires) | the time/sun path — the op finds its own work |
| `LocalDeviceEventSource.events()` | the fast path, bridge host only (RE-D11) |
| `RunRoutineNow` | manual; mints a fresh chain root, bypasses the claim (the user is here, on this device) |
| Geofence delivery (RE-4) | phone only |
| `SaveRoutine` / `DeleteRoutine` / `SetRoutineEnabled`, roster change, init | **re-arm only** — no firing |

#### One firing, in order

Given a candidate `(routine, triggerInstant)`:

```
 0. [device-event path only] SELF-TRIGGER CHECK
    the transition's lastCommandId names a firing of THIS routine  -> drop, silently
 1. PARSE the program                                              (§4.2)
    UnsupportedVersion -> return: do NOT execute, do NOT claim      (RE-D10 fence)
    Malformed          -> record FAILED once; do not retry-loop
 2. OBSERVABILITY   canObserve(trigger)         false -> return     (§4.3, RE-D1)
 3. CLAIM           executorFor(routineId, roster)                  (§4.3, RE-D2)
    me    -> continue now
    other -> re-arm for triggerInstant + GRACE and return;
             on that wake, a RoutineRun receipt for this firingId
             means stand down, its absence means continue with
             wasFallback = true
 4. FIRING ID       FiringIds.scheduled(routineId, triggerInstant)  (§4.4)
 5. CATCH-UP        now - triggerInstant > CATCHUP_WINDOW
                    -> record MISSED, done                          (RE-D6)
 6. SAFETY GATE     admit(firing, now)                              (§4.5, RE-D9)
    Throttled | Suspended | CycleRefused | DepthExceeded
                    -> record the named state, done
 7. JOURNAL         beginFiring(firingId)      <-- durable, BEFORE anything
 8. RECEIPT         write RoutineRun(state = CLAIMED)
 9. SNAPSHOT        take ONE ConditionSnapshot                      (RE-D7)
10. CONDITIONS      evaluate(conditions, snapshot)
    Met     -> continue
    NotMet  -> record SKIPPED + citations, goto 13
    Unknown -> record SKIPPED + reason, goto 13                     (fail closed)
11. ACTIONS         sequentially, author order, CONTINUE ON FAILURE (RE-D8)
                    commandId = "firing:<firingId>:<index>"
12. RESULT          RoutineRun -> RAN (all ok) | FAILED (any failed)
                    + citations + per-action outcomes
13. JOURNAL         completeFiring(firingId)
14. RE-ARM          recompute the single earliest ScheduleItem
--  later, asynchronously --
15. PATCH           device-command echoes resolve Pending ->
                    Idle | Failed | Unconfirmed on the stored run   (HD-3)
```

**Why the sharp orderings are what they are:**

- ⚠ **7 before 8, and both before 11.** The journal is the restart guard and the row is the
  cross-device receipt; they are not interchangeable (RE-D4). Journal *first*: a crash
  between 7 and 8 leaves a journal entry with no row and the restart correctly declines to
  repeat, whereas the reverse order leaves a row with no journal entry and the restart
  repeats the actions. At-most-once depends on this one line of ordering.
- ⚠ **9 is at action time, not trigger time**, and it is taken **once** for the whole run
  (RE-D7) — per-action snapshots would let action 3 observe what action 1 changed, which is
  the seed of the loops RE-D9 exists to prevent.
- ⚠ **5 and 6 precede 7.** A missed or throttled firing must not leave a journal entry to
  replay, and must not consume the at-most-once slot for a `firingId` that never ran.
- ⚠ **0 precedes everything on the device-event path.** Suppression is cheapest before the
  program is even parsed, and it is the guard that keeps a self-triggering routine from
  consuming its own throttle budget.

#### Three consequences worth pinning, because each is easy to get wrong

1. ⚠⚠ **`Throttled` must coalesce, or the throttle does not throttle.** RE-D9 calls the
   token bucket a *sync* brake — but if every refused firing still writes its own
   `RoutineRun` row, the brake changes a state field and nothing else: a flapping sensor
   floods the realm with `THROTTLED` rows instead of `RAN` rows. **Record the first refusal
   in a window as one row and coalesce the rest into a count on it** (or drop them
   silently), so the row rate is bounded by *windows*, not by firings. This is the
   `DeviceStatePublishGate` lesson arriving one layer up. ⭐ Note that §6's runaway test
   already asserts "the throttle demonstrably bounds the `RoutineRun` rows written" — a
   naive per-firing implementation fails it, which is the test doing its job.
2. ⭐ **Re-arm on roster change, not only on edits.** Step 3 makes the armed set a function
   of the roster: if the hub goes offline and is revoked, the desktop becomes the executor
   for routines it was never armed for — and nothing in the edit path or the firing path
   would notice. A roster observer is a required input to re-arm, and its absence is a
   silent "routines just stopped running" that would be very hard to diagnose.
3. ⭐ **A non-executor still arms a timer** — at `triggerInstant + GRACE`, not at
   `triggerInstant`. RE-D5's "earliest upcoming firing across all routines it is eligible
   for" must be read to include *fallback* instants, or the grace fallback never fires and
   RE-D2's second half is decorative.

#### Startup

```
1. load routines
2. incompleteFirings()  ->  for each: the run was interrupted mid-flight.
                            do NOT re-run (at-most-once, RE-D4);
                            leave the CLAIMED row visible as "interrupted",
                            clear the journal entry.
3. arm the ScheduleItem (including fallback instants, per (3) above)
4. subscribe to LocalDeviceEventSource, if one is bound in this process
```

⚠ Step 2 is deliberately **not** a recovery: it is a reconciliation. The `CLAIMED` row that
never advanced is the artifact the user sees, and re-running it is the one behaviour RE-D4
rules out — a repeated physical actuation is worse than a missed one.

⚠ **Per-routine serialisation.** Two firings of the same routine can overlap (a long action
list, a fast-repeating trigger). The engine serialises per `routineId`; otherwise two runs
interleave against snapshots taken at different instants and the run records become
uninterpretable. Different routines run concurrently.

## 5. What degrades with no hub

An always-on hub must remain optional, so the honest statement matters more than the
feature list:

| Trigger | With a hub | Phone + desktop | Phone only |
|---|---|---|---|
| **Time / Sun** | punctual (resident timer, no permission) | punctual while the desktop is awake; ⚠ the 90 s fallback covers sleep, and a suspended machine past `CATCHUP_WINDOW` records `Missed` | ⚠⚠ best-effort — exact alarms need a user-granted permission (RE-D5); otherwise minutes late, and nothing fires while the phone is in deep Doze |
| **Device event** | ⭐ sub-second, entirely in-process, no network at all | ⚠⚠ **not available** — there is no bridge to observe (editor-gated, RE-D1) | ⚠⚠ **not available** |
| **Geofence** | ⚖️ never — the hub has no location | on the phone | on the phone |
| **Voice phrase** | on the hub, once the voice pipeline lands | via Mia on the device that heard it | via Mia |

⭐ The framing for the product surface: **a hub buys punctuality and the entire device-event
class.** Everything else works without one. That is a far better story than "requires a hub",
and it is true.

## 6. Testability

The whole engine is provable with **a fake clock plus `SimulatedDeviceBridge`** and zero
hardware. That is a design constraint, not a hope — it is why the core is pure.

**Pure, unit-testable, no IO** (the `MovementSegmenter` / `HubCommandGate` /
`DeviceStatePublishGate` precedent): `RoutineProgram` parse + validate (incl. unknown
`programVersion` → refuse), `nextFiring(routine, now)` (timezone and sun maths — the sharp
edges of RE-D3), `executorFor(routineId, roster)` (RE-D2), `ConditionEvaluator(snapshot)`,
`SafetyGate` (token bucket, flap → `SUSPENDED`, depth and cycle guards), and the
`CATCHUP_WINDOW` boundary table. ⚠ The engine never calls a platform `now()` directly — every
time source is the injected `Clock`, or the fake clock is worthless.

**System tests**, against in-process proxies + a simulated bridge:

1. *Exactly-once across two executors* — two proxies, same realm, one time routine; the
   primary fires, the fallback observes the receipt and stands down; the light changes once.
2. *Grace fallback* — primary proxy stopped; the second fires at `T + 90 s` and its run
   record is marked `wasFallback`.
3. *A genuine race* — both fire inside the grace window (receipts collapse on `firingId`;
   the absolute `setPower` leaves the light in the same state; one `Notify`).
4. *Device-event fast path* — simulated motion → light on, evaluated before the `DeviceState`
   row publishes, with the relay stopped entirely (⭐ the zero-network claim, pinned).
5. *Restart idempotence* — kill the executor between the journal append and the action;
   restart re-ingests and does **not** repeat (at-most-once, RE-D4); the `CLAIMED` row is
   visible.
6. *Catch-up* — fake clock jumps 14 min (fires) and 40 min (records `Missed`).
7. *Self-trigger suppression* — a routine whose action changes the state its trigger watches
   fires exactly once; the second event carries its own `commandId` and is suppressed.
8. *Runaway* — a flapping simulated sensor drives throttle → `SUSPENDED` + notification, and
   the throttle demonstrably bounds the `RoutineRun` rows written.
9. *Presence fail-closed* (RE-4) — one device `unknown` ⇒ "nobody's home" does not run.
10. *Roster-change re-arm* (§4.9) — a two-proxy realm where the primary is revoked; the
    survivor must **re-arm and fire** the routines it inherited, with no edit and no restart
    to prompt it. ⚠ This is the test that catches "routines silently stopped running", the
    failure mode with no error and no log line.

⚠ **The one thing tests cannot prove is OS wake punctuality** — Android Doze and exact-alarm
grants, iOS `BGTaskScheduler` delivery. Same split as `PlatformScheduler` and the HD track
generally: everything up to the wake is hardware-free; the wake itself is a maintainer
device pass.

## 7. Threat-model duties

Per House Rule #2, amendments land **with the slice that creates the surface**, not here.
`home-and-mia-apps.md` §9 already flags this engine ("automations acting on services without
user presence"); HD-1 established the physical-actuation surface (**#17**, §8.19) that this
extends rather than replaces. ⚠ Nothing here touches the relay — every row is realm-key-sealed
sync and the relay stores ciphertext (**House Rule #1 intact, no relay change, no
`RELAY_PROTOCOL_VERSION` movement**).

Candidate threats for the RE-1 amendment:

- **T-RE-01 — Late/replayed firing actuates the home.** The engine deciding to fire long
  after its trigger instant. Defense: `CATCHUP_WINDOW` (RE-D6) — and the amendment must say
  explicitly that `DeviceCommand.STALE_WINDOW_MS` does *not* cover this case, because the
  engine issues a *fresh* command.
- **T-RE-02 — ⚠⚠ A synced routine is a standing instruction that outlives its author.**
  Revocation (ADR 0007) removes a *device*; it does not un-author that device's writes. A
  routine created on a stolen-while-unlocked phone keeps acting on the home after the phone
  is revoked, without the user present, forever.
  ⚖️ **Recommended mitigation, cheap and fail-closed: on device revocation, move routines
  whose `authorDeviceId` is the revoked device to `SUSPENDED` pending user review.** Revocation
  is exactly the moment the user is already paying attention. `Routine.authorDeviceId` exists
  for this; Home should also render "created on {device}".
- **T-RE-03 — Loop / runaway as self-inflicted DoS and block amplification.** RE-D9's
  suppression, throttle and suspension; the throttle is also the sync brake.
- **T-RE-04 — (RE-4) `PresenceState` is a new sensitive artifact.** Per-device inside/outside
  is a coarse location signal about household members, held as ordinary realm data. It is
  E2E, but it is new for a household that never enabled Timeline capture — it deserves its own
  consent surface rather than arriving as a side effect of writing a routine.
- **T-RE-05 — Home coordinates on the routine row.** Sun triggers need a shared location
  (RE-D3). ⚖️ Store it rounded to ~1 km (`homeLatE5`/`homeLonE5` rounded at authoring time):
  sunset shifts roughly 4 s per km east-west, so 1 km is already far more precision than the
  feature needs, and it keeps the row from being a precise home address.
- **Explicitly not a threat, and worth recording as such:** the closed local action
  vocabulary (RE-D8) means a routine cannot be turned into an egress or exfiltration channel.
  A webhook action would change that in one line, which is why it is a decision and not an
  omission.

## 8. Phasing

Each slice is independently shippable and — the binding constraint — **provable with no
hardware**, on a fake clock plus `SimulatedDeviceBridge`.

- **RE-1 — the spine: time and sun triggers, single-device.** `:routine-service` module set;
  `Routine` + `RoutineRun` schemas (additive types, **no format bump**); the pure core
  (program parse/validate, next-firing, condition eval, safety gate); `firingId` + local
  journal + `Claimed`-before-acting; the `ScheduleService` next-wake arming; actions =
  device command + notify over the shipped ops; **manual "Run now"** from Home (the cheapest
  possible end-to-end proof, and something users want anyway); Home's `AutomationsRepository`
  swaps from mock to real behind the seam, desktop first (HD-D5 pattern), with `Presence`
  and `DeviceEvent` triggers editor-gated. Run history + derived `lastRunAt` feed the
  Activity tab. Threat-model amendment: T-RE-01..T-RE-03 + the revocation-suspend rule.
- **RE-2 — device-event triggers on the hub.** The `LocalDeviceEventSource` seam and the
  bridge-local fast path; the "needs a hub" editor gate goes live; self-trigger suppression
  via the `firing:` `commandId`. System test: simulated motion → light, **with the relay
  stopped**, sub-second.
- **RE-3 — multi-device hardening.** RE-D2's claim + 90 s grace + the two-proxy exactly-once,
  fallback and race tests; `programVersion` execution fencing; throttle/`SUSPENDED`/pause-all
  proven; the `punctual` `PlatformScheduler` class + the Android exact-alarm permission flow;
  Android runtime wiring + the smoke gate (`:home-android` already rides it — HD-3).
  ⚠ The live OS wake is the hardware-gated tail.
- **RE-4 — geofence + presence.** `PresenceState` rows off the location capture plumbing
  (`timeline-movement-capture.md`); geofence triggers, phone-only executors; the `Presence`
  condition un-gated, fail-closed on `Unknown`; T-RE-04's consent surface. ⚠ Real geofence
  delivery is an on-device pass.
- **RE-5 — invocation surfaces.** Mia's routine invocation (MQ-D7 / MQ-3 — a mutating plan
  behind the confirmation cell, inheriting `Pending`/`Failed`/`Unconfirmed` unchanged);
  voice-phrase triggers once the voice pipeline lands; the Skills widening seam (D3), which
  is a *new action kind* and therefore a `programVersion` bump, not a schema change — which
  is the whole point of RE-D10.

  **RE-5a IMPLEMENTED (2026-08-26, one PR) — Mia's invocation half. No schema, wire or relay
  change**, so House Rule #9 is untriggered: it adds a `MiaSource`, a plan type and a grammar
  route, and fires the already-shipped `RunRoutineNow`.

  - **`MiaSource.ROUTINES`**, its own grant rather than folded into `DEVICES` (the `LABELS`
    precedent): *"Mia may see my lamps"* and *"Mia may run a routine that turns half the house
    on"* are different permissions, and the second is the one a user is likelier to want off.
    ⚠ **Read grant only**, like `DEVICES` — what stands between an utterance and the house
    changing is MQ-D7's cell showing the resolved routine, not a second preference.
  - **`QueryPlan.RunRoutine` never lowers** (MQ-D4a): the name resolution is a direct row read
    proxy-side, so like `CreateEvent` it is settled before the chain path.
  - ⚠⚠ **The verb is what makes it an instruction.** *"show my routines"* is a request to see
    them, and firing one in response would be the worst possible misreading — the rule
    `deviceIntent` applies to a bare "on", where the cost is also physical. It answers by naming
    Home's Automations tab rather than refusing blankly. ⚠ Both nouns route: Home's UI says
    *automations* and the service says *routines*.
  - ⚠⚠ **Never guesses between candidates.** Two routines matching "evening" ask which; only one
    match confirms. Guard mutation-checked — making it pick the first turns exactly that test red.
  - ⚠⚠ **A suspended routine is refused before the cell**, because `RunRoutineNow` honours
    `STATE_SUSPENDED` and a tap guaranteed to fail is worse than an honest no (the rule
    `eventConfirmation` set for a missing default calendar). ⚠ A *disabled* one still runs — the
    op ignores `enabled` on purpose — and the cell says "Currently off — runs now anyway" rather
    than letting that read as a contradiction with the Automations tab.
  - The confirmation carries the resolved **id and name**; re-resolving at execution would let a
    routine renamed in between fire under a title nobody agreed to.

  **RE-1 follow-up — the Activity feed, 2026-08-27 (one PR).** RE-1 specified "run history +
  derived `lastRunAt` feed the Activity tab" and delivered only the second half: `RoutineRun` rows
  were read client-side to derive `lastRunAt` and then discarded, while the tab stayed on
  `MockHomeActivityRepository`. `SyncedHomeActivityRepository` now maps them, retiring Home's last
  mock. No schema change — the rows already existed.

  - ⭐ The doc's own claim carried the design: *"the receipt and the history row are the same row"*,
    so the feed reads receipts rather than a parallel history that could disagree with them.
  - ⚠ `routineName` is **joined, not denormalised**: a run records which routine fired, and the
    name is mutable. Freezing it at firing time is defensible for an audit trail and wrong for a
    feed that should say what the routine is called *now*. ⚠ A deleted routine keeps its rows —
    a firing that happened is a fact about the home — and they read "A deleted routine".
  - ⚠ A `Claimed` row is not yet a feed row: it has no outcome to describe and becomes `Ran` or
    `Failed` moments later. ⚠⚠ `Skipped` says *"Conditions not met"*, never "Failed": a skipped
    routine's conditions were false, which is it working correctly, and "Failed" sends the user
    looking for a fault that is not there. Guard mutation-checked.
  - ⚠⚠ **It covers one of the tab's three kinds, and the screen says so.** `Device` events have
    **no store at all** — `home-device-service` holds `HomeDevice`, `DeviceState` and
    `DeviceCommand`, i.e. current values rather than a history, and `LocalDeviceEventSource` is a
    live trigger seam with "a short replay tail", bridge-host only. `Voice` waits on the pipeline.
    So the empty state is kind-aware: the old copy promised that devices and Mia "will show up",
    which two thirds of the filter chips can never honour.

  ⚠ **Persisting device events is a new schema decision, not a wiring one** — now designed in
  [`home-device-event-history.md`](home-device-event-history.md) (2026-08-27, no code). Its
  load-bearing move is that `DeviceState` is *state* and an event is a *log*, so the log is capped
  by construction on this doc's own `RoutineRun` precedent; and that only **discrete transitions**
  are events, which is what turns "a motion sensor is a firehose" into tens of rows a day rather
  than thousands.

  ⚠ **Still outstanding in RE-5:** voice-phrase triggers (gated on the voice pipeline, which does
  not exist) and the Skills widening seam (post-Rei, D3). Neither is blocked by this slice.

## 9. Open questions

- **OQ-1 — Should the program graduate from JSON to real composites once schema evolution
  E2 is proven in production?** RE-D10's string program is a *pre-migration* pragmatic
  choice: it exists because additive composite fields are peer-breaking today. E2's
  `SchemaMigration` machinery (`core-schema-evolution.md` §8.5/§8.6) is precisely the thing
  that could make a properly-typed program affordable, at which point the chain DSL could
  query routines by target device.
  **Recommendation: revisit after E2 has carried at least one real per-service migration in
  production, and not before.** Do not pre-build for it; `programJson` and a typed program
  can coexist behind the same parse seam.
- **OQ-2 — Does `RoutineRun` want to be on the content plane eventually?** Run history is
  append-mostly and grows without bound; RE-D10's 200-row/30-day prune is the v1 answer, and
  it is the same shape `MovementTrace` took (eager now, content-plane escape hatch
  pre-built). **Recommendation: keep it eager; revisit only if a real household's Activity
  tab actually strains it.**
- **OQ-3 — Multi-person households.** RE-D7's presence rule treats every location-capable
  device as a person, which is wrong in both directions (one person, two phones; a guest's
  device is not in the realm at all). The realm is one user's devices, so "nobody's home"
  really means "none of *my* devices is home". **Recommendation: say exactly that in the UI
  copy at RE-4** and let a real household model wait for cross-user shared realms (RE-D12).
- **OQ-4 — Should a routine be able to target a *shared*-realm device?** Deferred by RE-D12;
  raised here so the answer is recorded as deliberate rather than forgotten.

## 10. Pointers

- [`home-and-mia-apps.md`](home-and-mia-apps.md) — §5 the taxonomy this implements, §7 the
  mock model it replaces, §9 the deferral this doc closes, D3 (extensible action vocabulary).
- [`home-device-service.md`](home-device-service.md) — HD-D1 (`DeviceCommand`, the
  caller-minted id and the 2-minute window), HD-D2 (latency posture), HD-D4
  (`SimulatedDeviceBridge`), HD-D5 (the source seam Home consumes), and the HD-3 follow-up
  (`DeviceControlSource`, `setPower`-not-`toggle`, `Pending`/`Failed`/`Unconfirmed`).
- [`hub-hardware-and-management-service.md`](hub-hardware-and-management-service.md) §11
  (outbound-only, no listener), §13 H4 (the `HubCommand` + journal-before-execution pattern
  RE-D4 inherits).
- [`core-schema-evolution.md`](core-schema-evolution.md) §8.3 — no election, no quorum,
  dedup by receipt; the coordination precedent RE-D2 sharpens.
- [`core-background-processing.md`](core-background-processing.md) §5.1/§8 +
  [`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md) — `ScheduleService`,
  `ScheduleItem`, `PlatformScheduler`, and why routines are not maintenance work.
- [`core-task-composite.md`](core-task-composite.md) §5–§6 — `TaskSpec` / `withTask`; a long
  multi-action routine is a future consumer, not an RE-1 dependency.
- [`mia-query-layer.md`](mia-query-layer.md) — MQ-D7 (reads run, actions confirm) and §5 MQ-3,
  which sequences routine invocation after this engine.
- [`timeline-movement-capture.md`](timeline-movement-capture.md) — the location capture
  plumbing RE-4's geofence and presence rows ride.
- [`camera-service.md`](camera-service.md) CM-4 — detection events arrive here as ordinary
  device-event triggers.
- `docs/sync/threat-model.md` §8.19 (surface #17, physical actuation) — the group RE-1's
  amendment extends.
