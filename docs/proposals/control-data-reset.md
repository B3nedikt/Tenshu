# Control — resetting data an app has written

**Status:** Draft v0.1 — design, no code (2026-07-28).
**Scope:** The missing verb in Control: *delete the data*. Adds a generic delete path over
`RealmData` types (`:core` + `:core-common` proxy), an action in Control's **Data** section, and a
link into it from **App Detail**. Touches [`control-ia.md`](control-ia.md) §4.2/§5,
[`core-realm-data.md`](core-realm-data.md), and ADR 0006 (tombstones). No wire or schema change is
expected — deletion rides machinery that already exists.

Opened by a concrete dead end: [`fit-app.md`](fit-app.md) §17 fixed the Takeout importer, but the
~10,700 fragment rows a previous import wrote **cannot be removed** — not by the app, not by
Control, not by clearing app data. There is no delete verb anywhere in the stack above
`:file-service`.

---

## 1. What exists, and what is missing

| Capability | State |
|---|---|
| Deregister an app (`RemoveApp`, per-device or all devices) | **Built** (Track A) |
| Revoke an app's permission grants | **Built** (`SetAppGrant`; removal purges grants) |
| Enumerate the realm's data types (`realmDataTypeIds()`) + Control's Data section | **Built** (Track B) |
| Delete a *file* (`deleteFile` + tombstone) | **Built** |
| Delete **any other** realm data — one row or all rows | **Absent** |
| `:health-service` delete of any kind | **Absent** (record + read ops only) |

[`control-ia.md`](control-ia.md) §4.2 is explicit that *Uninstall* means **deregister** ("Control
cannot delete the binary") and lists *"per-app data summary — data this app has touched"* as a
**deferred** field. So the gap is not an oversight in one service; the IA never claimed the verb.

---

## 2. D1 — the unit of reset is a data **type**, not an app

The intuitive framing ("clear Fit's data") is the one thing the design must **not** adopt.

Apps are lenses over shared realm data, and that is not a slogan here — it is literally true of the
data in question. `TimelineImportWriter` writes `Workout` rows into `:health-service`, the same rows
Fit surfaces (made visible in Timeline's bundled manifest). "Delete Fit's data" would
therefore delete workouts Timeline imported, and "delete Timeline's data" would delete workouts Fit
recorded. Neither app *owns* them. The realm does.

**Decision:** the destructive verb belongs to the **Data** section, keyed by `RealmData` type. App
Detail *links* into it — that is exactly the deferred "data this app has touched" field — and never
hosts a delete of its own. This also keeps the model honest in the UI: uninstalling an app visibly
does not remove data, because the data was never the app's.

⚠ Consequence for the copy: "Reset Fit" is not offerable. What is offerable is *"Workouts — 3,002
items · Delete all"*, reached from Fit's detail page via "data Fit has touched".

---

## 3. ⚠⚠ The trap: `deleteData` does not tombstone

The obvious implementation — walk the type's rows and call `ExecutionContext.deleteData` — is
**wrong, and wrong in the worst way**: it deletes locally and converges nowhere.

`SyncObserver.onDataDeleted` is deliberately a **no-op**:

> Tombstones are emitted explicitly by operation implementations via `emit` — not on every database
> delete (cascade-delete inside an operation must not produce one tombstone per child).

So a naive bulk delete would clear the rows on this device, leave every peer untouched, and let the
next pull carry them back — the user watches their data reappear. Any reset must **emit one
tombstone per top-level entity** and let cascade maintainers remove children silently, exactly as
`DeleteFileImplementation` does today.

This is also why "clear the app's data" via the OS is meaningless here: Fit's rows live in
**Control's** store, and even wiping *Control's* store deletes only this device's replica while
peers keep theirs.

---

## 4. D2 — one generic op, not N per-service ops

**Option A — per-service delete ops** (`DeleteWorkout`, `DeleteMoment`, …). Precise and app-callable,
but every service re-derives cascade and tombstone handling, and the count grows with the schema.

**Option B — one generic `DeleteRealmData(typeId, createdBefore?)`** in `:core`, implemented
proxy-side over `realmDataTypeIds()` + the existing registry. Control-only.

**Option C — both, in that order.**

**Decision: C, with B first.** B alone closes the live need (a Data-section "delete all"), needs no
per-service work, and cannot drift out of sync with the schema because it enumerates it. A is added
per service only where an *app* needs to delete a single row (Fit deleting one workout), and is then
a thin wrapper over the same proxy path.

### 4.1 The filter question, and why `createdBefore` is the whole v1

The Fit case does not want "delete all workouts" — it wants "delete the ones the broken importer
wrote". A general predicate would need a serialisable filter over arbitrary fields, which is a
chain-DSL-sized problem.

But `RealmData` already guarantees exactly one structural field: **`CREATED_AT`** (the sort key the
Data section orders by, bound per type via `fulfilValueRequirement`). That single contract is enough
for the real case — *"delete workouts created before I re-imported"* — and it is generic by
construction, because every realm data type fulfils it. v1 takes `typeId` + optional
`createdBefore`; anything richer waits for a demonstrated second case (Q1).

---

## 5. What "deleted" honestly means

Per [`retention-quota-model.md`](retention-quota-model.md)'s three tiers, a reset touches them
differently, and the UI must not over-promise:

1. **The synced row** — tombstoned, converges to every device. This is the part that is genuinely
   deleted, and it is what the user sees.
2. **Content-plane blobs** (`Workout.Streams`, file bytes) — become unreferenced. ⚠ *Superseded
   2026-10-23:* the tombstones remove the rows' content edges, and the relay's sweep, when armed, frees the
   content T (15 min) after its last edge (CR-2 B5/B6, `retention-quota-model.md` §3.4); the additive-only
   backup mirror still keeps a copy. The 2026-07 text read: per-file reclaim on the relay is
   impossible by construction (HR#1), reclamation is account-lifecycle-only. **Local** plaintext is
   purged with the row.
3. **Relay block history** — the ciphertext blocks that carried the original writes remain until
   compaction advances the floor (schema-evolution E4). They are undecryptable to the relay and
   invisible to every client that has applied the tombstone.

⚖️ **Copy rule:** the action says *"removes it from all your devices"*, not *"erases it everywhere"*.
If a stronger claim is ever needed (a data-subject-deletion posture), it depends on E4 compaction and
belongs with the retention model's D4 legal clause, not here.

---

## 6. D3 — reset is realm-wide, deliberately asymmetric with `RemoveApp`

`RemoveApp` defaults to *this device only* and offers *remove from all devices* — right for a
registration, which is genuinely per-device.

Data is not. A device-local delete would be undone by the next pull, so offering the choice would be
offering a button that silently does nothing. **A data reset is realm-wide, with no per-device
option**, and the confirmation says so.

---

## 7. Who may call it

v1: **Control only.** An app must not be able to wipe a service's data — least of all data other
apps wrote (§2). Mechanically this is free today: Control *is* the proxy, so the op resolves
in-process and never needs to be exposed to an IPC client.

When per-row deletes arrive (§4 Option A), they become ordinary app-callable operations and want the
destructive capability class the permission model already has a slot for
(`GestureRequirement.RequiresExplicitConfirmation`, the posture Drive's `deleteFile` carries) — see
[`tenshu-rei.md`](tenshu-rei.md) §2.2.

---

## 8. Surfaces

- **Data → type card** — item count, and *Delete all…* with an optional "only items created before
  {date}" narrowing. Destructive styling, typed confirmation, realm-wide wording (§6).
- **App Detail → "Data this app has touched"** — the deferred `control-ia.md` §4.2 field, now
  *derivable*: an app's bundled manifest lists `serviceId`s, and a service's schema declares its
  `RealmData` types, so the link set falls out of data already in the registry (the manifests
  for all five first-party apps are populated). The card links into Data; it does not delete.
  ⚠ It must read *"Fit reads and writes Workouts"*, never *"Fit's workouts"*.

---

## 9. Phasing

| Phase | Content | Closes |
|---|---|---|
| **R1** | `DeleteRealmData(typeId, createdBefore?)` + explicit tombstone per entity + cascade + Data-section action | The Fit fragment case, and every future one |
| ↳ R1 service layer | **SHIPPED** — see §11. | |
| ↳ R1 UI + `Workout` opt-in | **SHIPPED** — see §12. **R1 COMPLETE.** | |
| **R2** | App Detail → "data this app has touched" (derived from manifest `serviceId`s) | `control-ia.md` §4.2's deferred field |
| **R3** | Per-service single-row delete ops where an app needs them (`DeleteWorkout` first) | Fit deleting one workout from its own UI |
| **R4** | Align unreferenced content-plane blobs with the retention model's GC phase | §5.2 |

R1 is independently shippable and is the only phase the current need requires.

---

## 10. Open questions

- **Q1 — filter expressiveness.** Is `createdBefore` enough beyond the Fit case? A `source`-keyed
  filter ("everything imported from a file") is the obvious next ask, but `source` is a
  `:health-service` field, not a `RealmData` contract — adding it would mean either a second trait
  requirement or a real predicate language. Deferred until a second case exists.
  ⚠⚠ **Reopened by §12.2**: `createdBefore` currently narrows nothing on `Workout`, because a
  `CREATED_AT` handle that merely *aliases a schema field* is unreadable through the entity
  property map the op reads. The whole-type reset — the only thing the UI offers — is
  unaffected, so this blocks nothing; but "enough for the Fit case" is now a question about
  the *read path*, not the filter's expressiveness.
- **Q2 — undo.** None proposed: tombstones converge, so an undo would have to re-emit every row.
  The confirmation is the guard. Worth revisiting only if resets prove accident-prone.
- **Q3 — shared realms.** A reset inside a shared realm deletes for *every member*, not just the
  operator. v1 should scope to the personal realm and refuse (or loudly confirm) on shared ones.
- **Q4 — count before delete.** ✅ **Resolved: omit it, report after.** The Data section's item
  count is `core-realm-data.md`'s optional Phase 6 ("cheap count") and is not built, so no card
  and no dialog prints a number; the op returns the deleted-row count and the result message
  says what actually happened. A guessed or placeholder number in a destructive confirmation
  would be worse than none.

---

## 11. As built — R1, service layer

Shipped: the operation, its proxy implementation and its registration. The Data-section
action (§8) is the remaining half of R1 and lands separately.

**Where it lives, and why three modules.**

| Piece | Module | Reason |
|---|---|---|
| `RealmDataService` + `DeleteRealmData` schema + `deleteRealmData(...)` extension | `:core` (`org.tenshu.core.realmdata`) | The op is generic over the core-level `RealmData` category. |
| `RealmDataServiceRo` + `DeleteRealmDataRo`, registered in `coreCommonModule` | `:core-common` | Where every `:core` op's client Ro lives. |
| `DeleteRealmDataImplementation`, wired in `syncProxyImplementationFactory` | `:sync-service-proxy` | ⚠⚠ See below. |

**⚠⚠ The implementation cannot live in `:core-common`.** `TombstoneEmitter` is declared in
`:sync-service-common`, which *depends on* `:core-common` — so a `:core-common`
implementation would be a dependency cycle. That is not just a build accident: per §3,
emitting the tombstone **is** the operation's substance, and `syncProxyImplementationFactory`
is the one factory already holding the emitter (so Android, desktop, iOS and the system-test
DI all pick it up from a single line).

**A new service, not a new `AppService` op** — hanging a data-reset verb off the
`appId`-keyed service would encode in code exactly the model D1 rejects. `RealmDataService`
declares **no relations**: it is a verb receiver, and the read surface (`realmDataTypeIds()`
plus each type's own service) already exists.
⚠ It is registered **after** `AppService` in `coreCommonModule` on purpose:
`SchemaRegistry.owningServiceOf` attributes a config's types to its *first-declared* service,
and `:core`'s composites must keep attributing to `AppService` (the frozen-forever,
never-fencing announce channel — schema-evolution §4.1.3).

**How typeId → rows works.** `Database` has **no type-wide enumeration**; a row is reachable
only through the service-data relation array that holds it. New:
`SchemaProvider.serviceDataArraysHolding(typeId)` (implemented on `SchemaRegistry`) walks the
registered configs' `serviceDataRelations` map and returns each matching relation's **lookup
key** as a `ServiceDataArrayBinding` — deliberately the key, not a rebuilt reference, so the
implementation hands it straight to the existing `ExecutionContext.resolveServiceData` and
gets the very same `ArrayDatabaseReference` an ordinary chain read builds. Matching includes
sub-types (a `TList<Folder>` array answers a `File` reset), mirroring how `realmDataTypeIds()`
folds sub-types into the declaring parent. Like `realmDataTypeIds()`, **no reverse index is
maintained** — the walk is linear and rare.

**⚠⚠ A type with no service-data relation deletes nothing, truthfully.** `File` — today's
*only* production `RealmData` type — is exactly that case: its rows live in the parent-folder
tree, not a service relation, which is why it keeps `deleteFile`. ⓘ §12.3 turns this into a UI
rule: a type with no service-data array renders **no** reset action, so the truthful zero-delete
is never something a user can trigger. The motivating type, `Workout`, does have
`HealthService.workouts` — and declares the marker as of §12.1.

**Behaviour details worth keeping.**
- Per-row order is **remove-from-array → `deleteData` → emit one tombstone**; the array
  removal first, because a deleted row left listed is a dangling member.
- `createdBefore` is an **exclusive** upper bound and is read off the row's *entity*
  properties via the row's own type's `RealmData.CREATED_AT` binding (so a sub-type row
  resolves through its parent's). ⚠ A row whose createdAt is **absent is kept** — the
  narrowing exists to spare rows, and there is no undo (Q2).
- A non-`RealmData` typeId is **rejected** (`NotFoundError`), never a silent no-op: a
  quiet zero-delete reads to the user as "the reset didn't work".
- The op **returns the deleted-row count** (`TInteger`) — Q4's "carry the count or omit
  the number" resolved as *report what actually happened* rather than guess beforehand.

**No format bump (HR#9).** No stored-row or wire shape changes: a new service id, a new op
id, and a proxy-side registration detail (`ServiceDataArrayBinding`) that is never
serialised. No relay redeploy, no threat-model delta (deletion rides the existing tombstone
mechanism, ADR 0006).

**Tests** — `sync-service-proxy/src/commonTest/.../realmdata/DeleteRealmDataTest.kt`, driven
through the SDK chain over a fixture realm-data service: deletes every row of the named type;
emits **exactly one tombstone per top-level entity** and none for a cascaded child; honours
`createdBefore` (incl. the exclusive boundary and the absent-timestamp case); rejects a
non-`RealmData` type without deleting or tombstoning anything; leaves sibling types untouched;
plus the `serviceDataArraysHolding` reverse walk itself.

---

## 12. As built — R1, `Workout` opt-in + the Data-section action

The other half of R1: the type the motivating case is about joins `RealmData`, and the Data
section grows the destructive verb. **R1 is complete.**

### 12.1 `Workout` is realm data

`Workout.Schema` now declares `RealmData.Schema`, and `healthServiceCommonModule` binds
`Workout.Start` → `RealmData.CREATED_AT`.

**Why `Start`.** A workout *is* its session, users date one by when it started, and `START` is
already the key the whole read layer orders history by (`sortByDescending { start }`,
`fit-read-scaling.md`). `END` dates the same thing later for no benefit; there is no separate
"recorded at" field, and minting one would be a schema change for a value no producer writes.

**⚖️ Schema-evolution verdict: no bump of any kind.** Checked against
`core-schema-evolution.md` rather than assumed:

- **`TENSHU_STORAGE_FORMAT_VERSION`** — unchanged. Trait membership is code-side schema
  metadata (§6.9's `service(id, version=…)` / the `@defer` precedent) and is never serialised.
  No field is added, removed or reordered, so the positional encoding (§2 fact 1) of a
  `Workout` row is byte-identical before and after — both in the store (`DbEntry` = type id +
  positional `DbValue` list) and on the wire. HR#9's trigger is "changed the stored-row or wire
  shape"; nothing here does.
- **`SchemaMigration`** — none owed. A migration exists to re-decode *field lists* under a
  producing snapshot (`CompositeMigration.from`/`to`); there is no reshape to describe.
- **Per-service version** — `HealthService` stays unversioned. A bump exists to fence peers
  that cannot read what this device writes (§7.6/§7.7). A pre-bump peer parses every post-bump
  `Workout` byte-for-byte identically; it merely does not *list* Workouts in its Data section,
  which is a local presentation difference, not a decode hazard. Bumping would fence peers over
  nothing.
- Relay: untouched (`RELAY_PROTOCOL_VERSION` unchanged, HR#1 — the relay never sees a trait).
- Threat model: no delta. Deletion rides ADR 0006 tombstones, already covered by §11.

### 12.2 ⚠⚠ Found: a field-aliased `CREATED_AT` narrows nothing

`DeleteRealmDataImplementation` reads the cut-off with `Entity.getProperty(handle.id)`, and an
entity's property map holds **only registered value properties** — never its fields
(`DbEntryMapper` deserialises fields and relations into separate maps;
`ReferenceResolver.resolveStaticProperty` reads the same property map).

`Workout.Start` is a handle whose id *is* the `START` schema field. It exists for `sortBy`,
which resolves through the **wire reference** layer where a field reference outranks a
value-property one (`WorkoutSortWireTest`, `fit-app.md` §9.17) — a different path entirely. So
`getProperty(START)` returns `null`, every row falls on the op's conservative *keep* side, and
a `createdBefore`-narrowed reset of Workouts deletes **zero rows and reports success**.

It is invisible to the op's guard: `resolveValueRequirement` returns the handle happily, so the
"binds no `CREATED_AT`" rejection never fires. `File.CreatedAt` is the other, working shape — a
genuine op-written value property with its own id.

Pinned by `fieldAliasedCreatedAtBinding_narrowsNothing_soARangedDeleteKeepsEveryRow` in
`DeleteRealmDataTest`. **Not fixed here** (it belongs to the op, and R1's UI ships the
whole-type reset only). The fix is one of: teach `isBeforeCutoff` to fall back to a field read
when the bound handle's id names a field of the row's schema (cheapest, and arguably what a
"value property" already means to every other caller), or give `Workout` a real stored
`CreatedAt` written at record time (a stored-row change, so an HR#9 bump). Tracked at Q1.

### 12.3 The Data-section action

`control-shared-logic` (Compose-free) → ViewModel → screen, mirroring `SharesViewModel`'s
click → confirm → op → report flow.

- **`RealmDataClient`** gains `isResettable(typeId)` and `deleteAll(typeId)`, and returns a
  `RealmDataResetOutcome` (`Deleted(count)` / `NotResettable` / `Failed`) rather than `Unit`.
  ⚠ Deliberately data, not an exception and not a discarded `TResult`: the caller *must*
  render something (the dropped-`Failure` bug class).
- **`DataViewModel`** holds `pendingResetTypeId` / `resettingTypeId` / `resetResult`. There is
  no scope state, because there is no per-device option to choose (D3).
- **`DataScreen`** renders *Delete all…* on the card, an `AlertDialog` confirmation, and the
  outcome in a snackbar.

**⚠ The action only renders for a type the reset can reach** — `isResettable` is
`serviceDataArraysHolding(typeId).isNotEmpty()`, derived from the schema, not a policy list.
`File` has no service-data array (§11), so a "Delete all Files" button would delete zero rows
and report success — the same mistake D3 rejects for the per-device option: a control that
silently does nothing. Files are deleted in Drive, which owns the tree-aware op. The VM
re-checks on click so a stale card cannot open a dialog promising a reset the op won't perform.

**The copy** (`control-shared/src/commonMain/composeResources/values/strings.xml`, all through
`stringResource` per ADR 0016):

> **Delete all Workouts?**
> This removes your Workouts from all your devices, and it cannot be undone. Apps that show
> this data will lose it too — the data belongs to you, not to any one app.
> `[Cancel]` `[Delete all]`

⚠ *"from all your devices"* is D3 (realm-wide, no per-device option) and §5's honesty rule in
one phrase: it never claims erasure *everywhere*, because relay block history survives until
compaction (E4) and the backup mirror keeps freed content blobs (`retention-quota-model.md` §3.4;
the relay itself can now free them per file). The second
sentence is D1 said to the user — it is why deleting Workouts also empties Fit *and* Timeline's
activity view, and saying so in the confirmation is cheaper than a support question later.

Results: *"Deleted 12 items from all your devices"* / *"Deleted 1 item…"* / *"There was nothing
to delete"* / *"Could not delete your Workouts. Nothing was removed — please try again."*
⚠ A failure must never read as a success.

**`createdBefore` is deliberately not exposed.** It would need a date picker in a destructive
dialog to buy one narrowing that (§12.2) does not currently work for the only type offering the
action. Whole-type reset ships; the narrowing waits for §12.2's fix, and then wants its own
slice with a real cut-off UI.

**Presentation.** `realmDataPresentation` became `@Composable` so its labels go through
`stringResource` (they were English literals from before the resources plugin landed);
`Workout` gets *"Workouts"* + a fitness icon and `sortOrder` 200. ⚠ The derived fallback for an
unregistered type is still an unlocalised label built from the schema id — it cannot be
otherwise, since there is no catalogue key for a runtime-derived name, and giving a type a real
entry here *is* what localises it.

**No format bump, no relay redeploy, no threat-model delta** — see §12.1.

**Tests.** `WorkoutRealmDataTest` (`:health-service-common`, new test source set): marker
declared, `CREATED_AT` → `Workout.Start`, and the `serviceDataArraysHolding` walk lands on
`HealthService.workouts` (the precondition for the action existing at all).
`DataViewModelTest` (9): ordering incl. Workout, the resettable subset, click-arms-nothing-yet,
dismiss-deletes-nothing, an un-resettable type cannot even arm, confirm runs it and reports the
count, a failure is reported not swallowed. `DataScreenContentTest` (8): one card per type via
presentation, the action renders **only** for a resettable type, tap arms the right type, the
confirmation's exact "all your devices" copy, confirm/cancel routing, and every result message
(⚠ asserted through `resetResultMessage`, not the snackbar — a snackbar auto-dismisses on the
test clock, and the message mapping is the part that can be wrong).

---

## 13. As built — R1b, the reset survives a real store

**Shipped 2026-07-29.** R1 was exercised on an emulator with a handful of rows. On the
maintainer's phone — ~10 k `Workout` rows from the Takeout import — *Delete all* froze Control
until the system killed it. Four causes, all in this section; none of them is specific to
`Workout`, so every future realm-data type inherits the fix.

### 13.1 What the ANR record actually said

⚠ **Read the dropbox entry, not the trace.** An ANR in Control is self-obscuring: ART aborts
inside its own unwinder while dumping, so the crash buffer shows a native `SIGABRT` in
`SignalCatcher` and the trace file contains every process *except* Control's (see
`docs/tech-debt.md`, Core Architecture). `adb shell dumpsys dropbox --print data_app_anr` still
carries the CPU breakdown and the per-thread waiting channels, which is what settles the case.

For the 17:28 entry: `Input dispatching timed out … MainActivity is not responding`, Control's
main thread in **`futex_wait`**, Control **absent from the CPU list** while `app.tenshu.fit` ran
at 133 % and `app.tenshu.timeline` at 46 % over the same 10 k-row store; RSS ≈ 500 MB, 109 MB
swapped. So the UI thread was **blocked, not computing** — the same signature as the 15:59 ANR
recorded in tech-debt, and the reason the fix below is *both* "get off the main thread" and
"stop writing so much".

### 13.2 The four causes

1. ⚠⚠ **The reset ran on the caller's thread.** `DeleteRealmDataImplementation` was a
   `SyncOperationImplementation`, which `ExecutionContext.execute` invokes **inline**, and
   nothing in the in-process path hops dispatcher: `DataViewModel.viewModelScope` (Main) →
   `DefaultInteractorProvider.buildTaskInteractor` → `SessionAwareLocalTaskExecutor` →
   `LocalTaskExecutor.executeAsync` → `evaluateCallstack`. The op's schema declared
   `IoOperation` the whole time — **the trait describes the contract, it does not move the
   work**; only an `AsyncOperationImplementation` reaches the `AsyncReference` path and runs on
   the session scope. `DeleteFileImplementation` is the precedent that had it right.
2. ⚠⚠ **Quadratic array maintenance, twice.** `DatabaseImpl.removeFromArray` reads a relation's
   whole member-hash list, filters one id out and stores the whole list back — so N rows cost N
   rewrites of an N-sized list. `SyncObserver.emit` does the mirror image on the way out:
   `addToArray(PENDING_TOMBSTONES)` rewrites the whole (growing) outbound queue per tombstone,
   and wakes the scheduler once per row.
3. ⚠⚠ **A kill mid-run could diverge silently.** The order was remove → delete → tombstone, so
   a process death between the delete and the emit loses the row's id from this device: a
   re-run cannot tombstone what it can no longer see, and every peer keeps the row forever.
4. **No progress, no way out.** The card only disabled its button, so a multi-minute reset was
   indistinguishable from a frozen app — which is exactly how it was reported.

### 13.3 The fix

- **Async impl** (cause 1), chunked at 250 rows with `ensureActive()` at each boundary, so a
  cancel unwinds between chunks and never mid-write.
- **Bulk array primitives** (cause 2): new `Database.addAllToArray` / `removeAllFromArray` —
  one rewrite per batch, membership tested against a hash **set**, listeners still firing once
  per element so nothing downstream can tell the difference. Both default to looping, so the
  test fakes and non-batching stores need no change; `DatabaseImpl` overrides them. The
  tombstone side gets the matching `TombstoneEmitter.emitAll`, which `SyncObserver` implements
  as one array write and **one** wake.
- **Tombstones first** (cause 3): each chunk's tombstones are queued — durably — *before* its
  rows are removed. A kill after that point leaves a delete that still converges; a kill before
  it leaves rows a re-run finds again. ⚠ The residue in the crash window is now the harmless
  direction: a row this device still shows and its peers do not, which the same button removes.
  Replaying the queue to finish the local half *without* a re-tap is deliberately not built —
  see §13.5.
- **Liveness + Stop** (cause 4): `TenshuRealmDataClient.deleteAll` registers a
  `GenericTaskSpec("control/realm-data-reset")` task and runs the op inside `withTask`, exactly
  as Fit's import does. ⚠ That registration is not bookkeeping — a RUNNING task row is a term in
  `ProxyService`'s liveness `combine`, so Android holds the foreground service for the duration
  (this device kills Control routinely). It also puts the op on the **L2 cancellable envelope**,
  which is what binds the op's evaluation scope to the caller's coroutine and so makes *Stop*
  actually reach it. Registration failure is non-fatal: the reset still runs, unprotected.

`RealmDataResetOutcome.Stopped` is a **third** outcome, not a `Failed`: what was deleted before
the stop stays deleted on every device, so "nothing was removed" would be a lie. The count is
unknown by construction (a cancelled call has no return value), hence "some", never a number.

### 13.4 Tests

`DeleteRealmDataTest` gains three: batches are `[250, 250, 100]` over 600 rows (not 600 emits);
a probe inside the emitter proves every row of a batch is **still readable** when its tombstone
is queued; and a cancel fired from the emitter leaves 350 rows listed with no id both tombstoned
and present. The cancellation test drives the op through `WithTaskElement` — without it the
op runs on the session scope and the caller's cancel would not reach it, which is precisely the
production distinction. `DatabaseArrayListenerTest` gains the two bulk-primitive contracts;
`DataViewModelTest` gains the *Stop* path.

### 13.5 First on-device pass — ⚠ the fix was **not yet sufficient** (2026-07-29, 18:16–18:21)

Run on the maintainer's phone against the real ~10 k-row store. It found a second dispatcher
defect and then a wall.

**Find 1 — ⚠⚠ `withTask` put the reset back on the main thread, cancelling out §13.2 cause 1.**
An in-process op runs on **the caller's dispatcher** whenever a `withTask` block is in scope:
`LocalTaskExecutor.executeAsyncCancellable` opens its evaluation scope with `coroutineScope { }`,
which inherits the caller's context, and `ExecutionContext.execute` dispatches the async impl with
`evaluationScope.async { }`. From `viewModelScope` that is **Main**. So the liveness wrapper added
for C silently undid the async implementation added for A. Symptom: the confirmation dialog froze
mid-reset and Control died to a **20 s `ProxyService` service ANR** (`executing service …, waited
20001ms`) — a service-lifecycle timeout, because service callbacks also run on the main thread.
Fixed by `withContext(Dispatchers.Default)` in `TenshuRealmDataClient.withResetTask`. ⚠ The trap
is general: any long in-process op called from a Main-dispatched coroutine inside `withTask`
inherits it.

**Find 2 — the reset still ANRs, now for a different reason.** With the dispatcher fixed the
failure mode *changed*, which is itself the evidence: before, Control sat at **<3 % CPU with its
main thread in `futex_wait`**; after, Control runs at **130–140 % CPU** on background threads — the
work really did move. But the UI thread still misses the 5 s input deadline
(`am_anr … Waited 5000ms`, on the confirm tap itself), and the process is killed again.

That is the **`DatabaseImpl` single-lock convoy** already listed below, now the binding constraint:
a chunk takes the lock for its array rewrite and then 250 back-to-back `deleteData` transactions,
the lock is not fair, and a background thread that re-acquires immediately starves the UI thread
that is waiting for its own reactive reads. Nothing about this is specific to the reset — it is the
same contention recorded in tech-debt from the 15:59 ANR — but the reset is the heaviest producer
yet, so it hits it every time.

### 13.6 The pacing slice — yielding the lock instead of holding it

Answer to find 2, and the last piece of R1b.

- **`yield()` every 25 deletes, `delay(10 ms)` between chunks.** ⚠ The chunk size is deliberately
  *not* lowered to shorten holds: each chunk rewrites the relation array whole, so smaller chunks
  mean *more* full-array writes. Fairness is bought with time instead, which costs nothing per row
  — ~10 k rows pay about a second of added wall-clock, and a reset has no deadline.
- ⚠⚠ **The chunk's three writes are `NonCancellable` as a unit.** Once the array entries are
  dropped, the store rows they named must follow — otherwise a cancel landing between the array
  rewrite and the deletes strands rows that are unreachable from any array, already tombstoned,
  and still occupying the store. A `yield()` under `NonCancellable` still reschedules without
  throwing, so pacing survives; only the *cancellation* is deferred to the next chunk boundary,
  which is what §13.3 already promised. Pinned by an assertion in the cancellation test: exactly
  the rows still listed in the array still exist in the store.
- **The drain batches its queue maintenance too.** `drainPendingTombstones` still pushes one block
  per relay call (the route takes one), but the per-block `removeFromArray` — a whole-array rewrite
  each time, O(N²) over ~10 k tombstones, on the same lock — is now a `removeAllFromArray` every
  100 pushed blocks, flushed on the way out (including through a cancellation). ⚠ Safe because a
  block pushed but not yet dropped is simply pushed again next drain: `processDeleteBlock` and
  `TombstoneRegistry.record` are idempotent. This matters as much as the reset itself — after the
  delete, the drain is what keeps Control busy.

**On-device result (2026-07-29, 18:44–18:49, same phone, same ~10 k-row store): PASSED.** The
confirmation dismissed immediately, the card rendered its spinner + *Stop*, the reset ran ~2.5 min
at 150–530 % CPU across background threads, and **no ANR of any kind** was recorded (input or
service) — against the two runs before it, which ANR'd within seconds. Re-running reported *"There
was nothing to delete"* (complete + idempotent), and Fit's History — a different app, reading the
same rows — is empty.

⚠ **Not exercised:** this device's realm is local-only, so the ~10 k tombstones queued but never
drained to a relay. The drain batching above is therefore verified by unit test and reasoning, not
on the wire; the first paired device to run a large reset is what proves it.

⚠ Left on the device: a **partial** delete. That is the designed-for state — every removed row was
tombstoned before removal, so what is gone is gone everywhere, and re-running finishes the rest.

### 13.7 Still open

- **Replay-on-resume.** The durable tombstone queue is now the journal, but nothing re-applies
  it locally after a kill, so finishing still needs a re-tap. The `resumeIncompleteMigrations()`
  shape (`file-content-addressing.md` Ph2 3c) is the precedent when this is worth building.
- **The drain is still per-block.** `SyncScheduler.drainPendingTombstones` pushes one
  `PushTombstone` **and** does one `removeFromArray` per block, so the third quadratic — and
  ~10 k relay round-trips — survives on the outbound path. Batching it is the natural next
  slice and is the one piece that touches the relay contract.
- **The general convoy** (§13.5 find 2). `DatabaseImpl`'s single unfair lock still serialises every
  client's reads against every write (tech-debt, Core Architecture), so any hot background producer
  can starve the UI thread. §13.6 makes *this* producer well-behaved; it does not fix the lock. The
  next heavy writer will rediscover it — the structural fix (fair lock, or read/write separation)
  belongs in that tech-debt entry, not here.
- ⚠ **The pacing constants are proven sufficient, not tuned.** `PACE_EVERY`/`PACE_MS` held the UI
  responsive on one device under one load; they were chosen to be obviously-enough rather than
  measured. `DRAIN_REMOVE_BATCH` has not run against a relay at all (§13.6).
- **Stop is untested on-device** — the button rendered throughout the passing run, but the reset was
  allowed to finish, so only its unit test covers the path.

---

## Version

v0.3 · 2026-07-29 · §13 R1b — the reset ANR'd on a real (~10 k-row) store. Async impl + bulk
array primitives + tombstone-first ordering + a registered liveness task with *Stop*, then §13.5's
on-device pass and §13.6's pacing. ⚠⚠ Records three traps worth more than the fix: an `IoOperation`
**trait** does not move work off the caller's thread (only an `AsyncOperationImplementation` does);
inside `withTask` an op runs on the **caller's** dispatcher, so the liveness wrapper undid the async
impl; and an ANR in Control cannot be read from its trace — use the dropbox header.

v0.2 · 2026-07-28 · R1 complete (§11 service layer, §12 `Workout` opt-in + Data-section
action). Q4 resolved (omit the count, report what the op did); Q1 reopened by the §12.2
field-aliased-`CREATED_AT` finding. ⚠⚠ Records that a `RealmData.CREATED_AT` handle aliasing a
schema *field* is unreadable through the entity property map, so a narrowed reset silently
matches nothing.

v0.1 · 2026-07-28 · Opened from the Fit Takeout-fragment dead end (`fit-app.md` §17). Decisions: D1
the unit is a data **type**, not an app (apps genuinely share rows — Timeline writes Fit's
workouts); D2 one generic `DeleteRealmData` first, per-service row deletes later; D3 realm-wide, no
per-device option. ⚠⚠ Records the `deleteData`-does-not-tombstone trap that would make a naive
implementation delete locally and resurrect on the next pull.
