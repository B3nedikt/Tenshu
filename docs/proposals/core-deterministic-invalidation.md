# Deterministic reactive invalidation — collapse by boundary, not by timer

**Status:** **§8's P0a, P1 and P2 are on master (2026-11-11); P0b (on-device) and P3 are not.** **Draft v0.5 — design, §8 proposes a way through (2026-09-24).** v0.4 text follows. **Draft v0.4 — design, no code. Re-verified against master 2026-09-23; corrected under
review over two passes, 2026-09-24.** Drafted 2026-08-05 and left unmerged on a branch 950
commits behind. ⚠⚠ **Read §0.0 first.** Both halves of the proposed mechanism are down —
§4's lane boundary was contradicted by shipped design (§0.2), and §4's origin filter rests on a
misreading of `WriteOrigin.SYNC_INGEST` (§0.6) — and the *corrected* design then took two further
P1s of its own (§0.7): the replacement discriminator is falsified by store rehydration, and the
coalescing rule re-loses peer writes at aggregation. **P0 is the only unblocked phase in this
document.** ⭐ **v0.5 (§8) proposes an answer to the gating question** — test *store equality*,
not authorship — with a stacked P0a → P1 → P2 plan. P0a is a JVM system test; P0b is on-device.

⚠ **What survives is the diagnosis, not the remedy.** The reactive plane really does erase a
discriminator the sync plane finds load-bearing, and a timer really cannot name what it is
collapsing. What every subsequent revision has got wrong is the *instrument* — and §0.7 F1 is the
first correction for which no cheap replacement was found.

⚠ **This is not a bug report.** The debounce is correct today. The argument is that it is
*load-bearing for the wrong reason*, and that the timer is what made a whole class of bug
representable.

---

## 0. ⚠⚠ Re-verification ledger (2026-09-23) — what is checked, and what is not

v0.1 was drafted 2026-08-05 off the `pagingSource` mid-scroll regression (§3). Everything below is
re-checked against master at `23eb70c1a` unless the line says otherwise. **Nothing in this document
is a fresh measurement.** §2's numbers are carried forward from 2026-08 and are re-labelled as such
where they appear. §0.6 and §0.7 are later corrections, made under review at the same tree.

### 0.0 ⚖️ State at a glance — what is blocked, and on what

⚠ **Read this before any section below.** Most of this document describes a design that two review
passes have since taken apart. The sections are kept, and marked, because the record of *what was
checked and what turned out wrong* is the point — but nothing here is buildable except P0.

| Phase / claim | State | Blocked on |
|---|---|---|
| **P0** — measure the echo on a real device | 🟢 **Unblocked, and the only one.** Still the gate, still unanswered | Nothing. ⚠ Its probe now needs the block's *author* as well as its origin (§0.6), and must straddle two seams |
| **P1.1** — mint a self/peer discriminator at ingest | 🔴 **Blocked** | **§0.7 F1.** Device-id equality is not the predicate — rehydration authors its own first-time rows. Nothing today proves "this exact write is already materialised", and `SyncMetadata` structurally cannot |
| **P1.2** — carry the discriminator to the client | 🟡 Designable, not buildable | P1.1; plus §0.3's proxy→client seam (`StreamRegistry` drops it, `triggerUpdate` re-diffs) |
| **P2** — the filter itself | 🔴 **Blocked, twice** | P1.1, **and** §0.7 F2 — the coalescing rule must be re-derived as a conjunction, over a contributor set that does not exist yet |
| **P3** — retire or shrink `INVALIDATION_COOLDOWN_MS` | 🔴 **Blocked** | P1.1 + P2. Until then the cooldown keeps its correctness role whatever P0 returns (§4) |
| §4 bullet 1 — collapse at the **lane boundary** | ⛔ **Withdrawn** — not blocked, *wrong* | Contradicted by shipped design (§0.2, B4) |
| §4 bullet 2 — filter by **`origin`** | ⛔ **Withdrawn as written** | §0.6 — `SYNC_INGEST` marks "do not re-broadcast", not "my own echo" |
| ⭐ **§8 — store-equality predicate** (v0.5) | 🟢 **P0a ✅, P1 ✅, P2 ✅ — on master (2026-11-11).** A byte-identical re-save no longer re-evaluates relation streams; the comparison is paid only for types a relation keys on (§8.4) | **P0b** (on-device: is the ~280 ms wave the redundant re-save; the comparison's cost on Android and iOS stores), then P3 |
| §2 — the 2026-08 measurements | ⚠ Recorded history, **not** current | Probed against the unprojected chain Drive no longer observes (§0.4); re-probing folded into P0 |

**Net:** §8 answered the durable-state question (§0.7 F1) with store equality, and its P0a/P1/P2
are on master. What remains is P0b on a device, then P3. The rows above it are the v0.4 record.

### 0.1 Re-verified against current code

| v0.1 claim | Verified at |
|---|---|
| A tuned `INVALIDATION_COOLDOWN_MS = 400L` collapses reactive reload signals | `core-paging/.../PagingUtil.kt` |
| `SyncObserver` answers the identical question with no timer: `if (origin == SYNC_INGEST \|\| origin == DERIVED) return` | `SyncObserver.onDataSaved` — ⚠ now **`SyncObserver.kt:450`**, not `:300` |
| Lane/task identity collapses N saves from one task into one delta batch (TS-2/TS-3) | `ExecutionContext.saveData(data, origin, syncLaneId)`, `ExecutionContext.requestSyncLaneId`, `core-background-processing.md` §10 (TS-1…TS-5 implemented) |
| `withBulkUploadTask` wraps a folder upload as one task (TS-5) | `TenshuFilesRepository.withBulkUploadTask("drive/folder-upload")` — and now **also** `SyncedFolderController` (`"drive/synced-folders"`), which is §0.2's problem |
| `origin` reaches the array listener | `DatabaseImpl.fireArrayAdded(arrayHash, value, origin)` → `Database.ArrayListeners.onAdded(value, origin)`; pinned by `DatabaseArrayListenerTest` ("origin propagates to onAdded") |
| `changes()` erases every element to `Unit` | `core/.../collection/list/Stream.kt` — returns `Flow<Unit>`, `emit(Unit)` per `StreamElement.Value`/`Removed` |
| `WriteOrigin` has three members, `DERIVED` KDoc'd as quoted | `core-common/.../proxy/WriteOrigin.kt` — ⚠ it has **not** grown a fourth since; v0.1's "actively growing" is one data point, not a trend |
| The 403–425 ms near-zero-variance wait | `docs/tech-debt.md` § "`FilesPagingIntegrationTest` — a write during the subscribe window was lost" — entry intact |
| The readiness tick's mid-scroll `IndexOutOfBoundsException`, and its fix | `debounceExceptFirst` KDoc still records it verbatim; both commits are in master |
| All four named suites exist | `FilesPagingIntegrationTest`, `SortToggleIntegrationTest`, `FilesListingEfficiencyTest` in `:drive-shared-ui`; `TenshuPagingSourceTest` in `:core-paging` |

### 0.2 ⚠⚠ Contradicted by shipped design — §4's lane bullet

**`drive-sorted-folder-paging.md` B4 shipped 2026-09-15, and its finding is the opposite of
§4's first bullet.** A pure trailing debounce restarts on every signal, so a burst that never pauses
for 400 ms never emits at all — *"the drift window would be the burst, not 400 ms"*. The answer
shipped as a **second timer**, `INVALIDATION_MAX_WAIT_MS = 3_000L`: a rebuild fires at least every
3 s while signals keep arriving, regardless.

§4's lane boundary is *precisely the unbounded-defer shape that ceiling exists to prevent*, and it
is worse, not better, than the debounce: a lane is not a 19 ms burst. `withBulkUploadTask` wraps a
**whole Synced-Folders source drain** and a **whole folder-upload walk** — thousands of files,
minutes to hours. "One signal at the lane boundary" is a folder listing that does not update until
the backup finishes.

⚠ v0.1 half-saw this in open question §6.2, but framed it as a *pathological* lane that never
closes. The correction is stronger: the **normal** case already breaks it. A lane-scoped signal
therefore cannot be the collapsing primitive on its own — at best it is a signal *coalescer* under
the same 3 s ceiling, which is a much smaller claim than v0.1 made. §4 is rewritten accordingly and
§5's P2 re-scoped.

### 0.3 ⚠ Corrected: the erasure site named in §1 is the *second* of two, and the first is lossier

v0.1 said the discriminator is "present at the exact site that needs it" and thrown away by
`changes()`. That is true of the array listener but skips what sits between:

1. **`StreamRegistry.registerRefForStream` discards it first, proxy-side, before IPC:**
   `onAdded = { _, _ -> triggerUpdate(executionContext, hash) }`.
2. **`triggerUpdate` does not carry a change at all** — it schedules a *re-evaluation* of the whole
   stream, serialised per stream by `streamIdToMutex`, and emits the **diff** against the previous
   baseline. So writes of different origins arriving close together can be folded into one emission
   before any client sees anything.

⚠ Consequence for P1: "carry `origin` through `changes()`" is **not** the purely client-side,
additive change v0.1 described. It needs `origin` to survive the proxy→client stream (signal-only
streams deliberately ship no payload), and it needs an answer to *what origin a coalesced diff
carries* — plausibly "any non-ingest member wins", but that is a decision, not a threading exercise.
v0.1's "Additive, default-unchanged" understates it.

### 0.4 ⚠ Stale measurement context — §2 was probed against a chain Drive no longer observes

SP-3 gave `pagingSource` an `invalidation` hook, and Drive's listing now observes a
**projected** chain — `invalidation = { it.select { listingRow() } }` — because a bare `Folder.files`
diff compares `path` alone and misses an in-place overwrite. §2's counts were taken against the
*unprojected receiver*. A projected observer signals on changes the bare one did not, so the
per-change signal counts in §2.1/§2.2 **are not known to characterise today's listing**. They stand
as recorded history, not as current numbers. Re-probing them is folded into P0.

### 0.5 Other drift worth naming

- **`reloadSignals()` is now shared** by `pagingSource` *and* `keysetPagingSource` (K-1), so
  P2's blast radius is wider than in 2026-08: Photos and Fit ride it too.
- **The mid-scroll rebuild is now partly by design.** B4 ships a periodic rebuild *during* a backup
  and tests it with a scroll in flight
  (`SortedFolderPagingIntegrationTest.continuousWritesDuringAScroll_rebuildWithinTheMaxWait_andNeverDuplicateARow`),
  with a per-generation `rowKey` dedupe covering re-served rows. §3's argument survives — the dedupe
  stops *duplicate rows*, not a presenter reset — but §3 may no longer present a mid-scroll rebuild
  as purely a bug class. It is now a priced trade-off in one direction and a shipped bug in the
  other.
- **The trend since 2026-08 went the other way.** The pipeline gained a *second* tuned constant
  rather than losing the first. That is not a refutation; it is simultaneously the strongest
  argument for this proposal and the strongest argument against its urgency, and a reader should see
  both.
- **`docs/tech-debt.md` § "The full gate is flaky under parallel load"** is now marked **closed**, kept as the record. §7's cross-reference is updated.

### 0.6 ⚠⚠ Corrected under review (2026-09-24) — `SYNC_INGEST` is **not** a self-echo marker

Both v0.1 and v0.2 read `SYNC_INGEST` as *"this device's own write coming back"* and proposed to
drop those reactive notifications. **That is a misreading, and shipping the filter as written would
suppress genuine peer updates** — a file another device adds, renames or overwrites would not
invalidate an open listing until an unrelated event or a resubscription. The finding is correct and
the design is corrected here, not patched.

**What the origin actually means.** `WriteOrigin`'s own KDoc says it exists so an observer can
"adjust behaviour based on the call site", motivated by `SyncObserver` needing to *skip outbound
block generation* (`core-common/.../proxy/WriteOrigin.kt:3-14`, member at `:19-20`). `SYNC_INGEST` is
a **do-not-re-broadcast** marker. "Do not re-broadcast this" and "this is my own echo, ignore it" are
different questions, and the enum answers only the first.

**The self-echo is real — that part of v0.1 stands.** Two independent records say so:
`ExecutionContext.deleteData`'s KDoc — *"the relay echoes this device's own pushes back, so one is
usually in flight"* (`core-common/.../proxy/ExecutionContext.kt:624`) — and
`SyncProcessor.processDeleteBlock` — *"The device pulls its own pushed tombstone back from the relay
and applies it here … That echo is not filtered, deliberately"*
(`sync-service-proxy/.../SyncProcessor.kt:238-245`). What is wrong is the inference that observing
`SYNC_INGEST` therefore *identifies* it.

**`SYNC_INGEST` has at least three producers, meaning three different things.** Every one of them
reaches the reactive plane as the same value:

| Producer | Cited at | What the save actually is |
|---|---|---|
| Accepted inbound block | `SyncProcessor.resolveConflictAndSave` — `saveData(…, SYNC_INGEST)` at `SyncProcessor.kt:710-713` (Merge) / `:717` (LWW), metadata at `:722-730`. Same origin on the two sibling paths: `attachPropertyAndSave` (`:620-628`) and `processDeleteBlock`'s `deleteData` (`:265`) | **Either** a peer's change **or** this device's own echo — the two are not separated here |
| Joiner catch-up snapshot | `FileSliceSnapshotProducer.applySlice` writes `Folder`/`File` rows at `file-service-proxy/.../FileSliceSnapshotProducer.kt:147-150, 166`; `MomentSliceSnapshotProducer.kt:194`; mandated by contract at `core-common/.../proxy/SliceSnapshotProducer.kt:88-89` | Rows **materialising locally for the first time** — the loudest possible reason to invalidate a listing, and never an echo of anything |
| Content arrival | `FileContentManifestApplier.kt:190-198` — `SyncMetadata` under `SYNC_INGEST` with `syncManager.hlcManager.tick()` | A **locally minted** HLC: this device's own id, on a write that is not an echo |

The middle row is fatal to the filter on its own. A joiner rebuilding a folder it has none of writes
exactly the rows the Drive listing renders, under exactly the origin the proposal drops.

**Where the self/peer distinction does and does not exist.**

- ✅ **At the block ingest site it is computable today.** `HybridLogicalClock` carries its author's
  `deviceId` (`core/.../time/HybridLogicalClock.kt:40`, field at `:76`); `resolveConflictAndSave`
  receives that `hlc` as a parameter (`SyncProcessor.kt:651-654`); and this device's own id is
  reachable at the same seam (`SyncProcessor.kt:174`,
  `ownDeviceId = { syncManager.currentRealmContext()?.deviceId ?: "" }`). ⭐ There is a **working
  precedent doing precisely this comparison off precisely this HLC**:
  `DeviceLastSeenRecorder.recordOrThrow` — `if (deviceId == ownDeviceId()) return`
  (`sync-service-proxy/.../DeviceLastSeenRecorder.kt:63-67`).
- ⛔ **At the snapshot site it is structurally unavailable**, and not by oversight. The
  slice path writes **no `SyncMetadata` and carries no HLC at all**, deliberately — that absence is
  what lets a later real block win (`SliceSnapshotProducer.kt:91-95`, echoed at
  `FileSliceSnapshotProducer.kt:125`). There is no author to test, and adding one would break the
  rule it is there to serve.
- ⛔ **At the seam §4 actually filters, none of it is visible.** The entire channel from writer to
  reactive observer is `ExecutionContext.saveData(data, origin, syncLaneId)`
  (`core-common/.../proxy/ExecutionContext.kt:610-614`) → `DataObserver.onDataSaved(data, origin,
  syncLaneId)` (`core-common/.../proxy/DataObserver.kt:41`) → `DatabaseImpl.fireArrayAdded(arrayHash,
  value, origin)` (`core-common/.../proxy/db/DatabaseImpl.kt:64-66`), and `WriteOrigin` is a
  three-member enum with **no author field** (`WriteOrigin.kt:15-42`). `StreamRegistry` then drops
  even the origin (`StreamRegistry.kt:486-487`, §0.3).

⚠ **The obvious workaround does not work either.** "Read the entity's `SyncMetadata` and compare its
`hlc.deviceId`" fails twice: snapshot rows have no metadata row by contract (above), and on the block
path the entity is saved *before* its metadata (`SyncProcessor.kt:710`/`:717`, then `:722-730`), so at
the moment the entity's observer fanout runs the metadata row still holds the **previous** HLC.

⚠ **This conflation is already live in shipped code, which is the strongest argument that it is a
real seam and not a pedantic one.** `IngestedDeleteRecorder`'s KDoc reads the origin as "a delete
arrives **from another device**" and gates on `origin != WriteOrigin.SYNC_INGEST`
(`file-service-proxy/.../IngestedDeleteRecorder.kt:22, 40`) — while `SyncProcessor.kt:238-245` says
the same origin also covers this device's own echoed tombstone. Whether that observer over-records an
orphaned-bytes candidate on a self-echo is a separate question this document does **not** answer; it
is named here only as evidence that "SYNC_INGEST means a peer" is already being assumed downstream.

#### Verdict, and what would have to exist first

**§4's origin bullet is withdrawn as written and marked blocked.** Not "narrow the filter" — the
filter has no signal to narrow *to* at its own seam, and one of the three producers has no such
signal anywhere. Concretely, before an origin filter can be proposed again, all three must exist:

1. **An authorship discriminator on the save channel.** `saveData`/`onDataSaved` must carry more
   than a three-valued enum — either a fourth `WriteOrigin` member minted only where the author is
   known to be this device, or an explicit author field. ⚠ A fourth member is *not* free: `SYNC_INGEST`
   is read by at least `SyncObserver.kt:450`, `MemberSelfBindObserver.kt:57`,
   `IngestedDeleteRecorder.kt:40` and `TombstoneRegistry.kt:189, 284`, and each has to be told which
   side of the new split it wants.
2. **A decision for the snapshot producers**, which cannot supply that discriminator at all. The
   honest default is that they are **never** suppressible — a joiner's rows are news by construction —
   which means the filter must be keyed on something *narrower* than "arrived from the relay".
3. **§0.3's coalesced-diff rule**, unchanged and still required (§6.6).

Until those exist, **the timer is not replaceable by an origin filter**, and `INVALIDATION_COOLDOWN_MS`
keeps its correctness role whatever P0 returns. P0 is still worth running — it is now the question
"*is* there a second wave, and is it an echo?" rather than "how do we filter the echo we know is
there" — but a "yes" no longer implies an implementable filter.

### 0.7 ⚠⚠ Two further corrections under review (2026-09-24, second pass)

§0.6 corrected *which* signal the filter keys on. Review then found two defects in the corrected
design itself — one in the discriminator P1.1 was told to mint, one in the rule P2 would aggregate
it with. **Both are recorded, neither is closed.** They are findings about phases that do not exist
yet, so they belong in this document and the trade-off goes to the maintainer; settling them by
review volume would be the wrong move.

#### F1 — ⛔ Device-id equality is *not* the self-echo predicate. Rehydration falsifies it.

§0.6 concluded that the self/peer distinction is computable at ingest, from `hlc.deviceId` against
this device's own. **That is true of *authorship* and false of *redundancy*, and P1.1 needs the
second.** A device that has wiped its local store replays **its own relay history**, and every one
of those blocks satisfies `hlc.deviceId == ownDeviceId` while being that device's **first local
sight of the entity**. This document already records the mechanism — *"a device that wipes its store
rehydrates from its own blocks, and dropping its own tombstones on that path would resurrect
everything it ever deleted"* (`SyncProcessor.kt:238-245`) — but filed it under §6.8 as an objection
to a *different* idea. It falsifies the discriminator too.

⚠⚠ Consequence if built as §0.6 described it: **P2 would suppress every listing notification for the
duration of a store rehydration.** An open listing would stay empty while the device refills behind
it — which is a worse failure than the one §0.6 was opened to prevent, because it lasts for the whole
recovery rather than for one edit.

**So the predicate has to be two-part**, and only the first part exists:

| Part | State |
|---|---|
| "this block was authored by me" | ✅ `hlc.deviceId` (`HybridLogicalClock.kt:40`) vs `SyncProcessor.kt:174`; precedent `DeviceLastSeenRecorder.kt:63-67` |
| "…and this exact write is **already materialised** locally" | ⛔ **Nothing today answers this.** |

⚠ **`SyncMetadata` cannot be the second part, and the codebase says so in its own words.**
`LocalWriteMetadata`'s KDoc is titled *"Why this exists: `SyncMetadata` is not 'the entity's last
write'"* and states that `SyncMetadata` *"is written by the **ingest** paths only, so it records the
last write that arrived over the wire"*
(`sync-service-common/.../LocalWriteMetadata.kt:20-26`). A local write therefore has **no**
`SyncMetadata` until its own echo is accepted and writes one (`SyncProcessor.kt:722-730`) — so at the
instant the echo arrives, the test "do I already have this?" reads *false* for precisely the case it
must call *true*. ⚠ This is not a hypothetical reading: getting it backwards was the measured "ghost
file" data loss — a device applying its own tombstone (`LocalWriteMetadata.kt:28-35`).

⚠ **`LocalWriteMetadata` is the nearest existing record, and it is named here so the next reader does
not mistake it for the answer.** It is a durable local-only *"HLC at which this device last wrote this
entity"* — but (a) it is **one row per entity, highest-HLC-wins**, so two rapid local writes to the
same entity collapse and the first's echo compares against the second's stamp
(`TombstoneRegistry.kt:221-231`); (b) it stamps the **device's clock at write time**, not the HLC the
emitted block carried, so it is a `>=` ordering record and not a per-write identity; (c) it is gated
on `isSyncable(data)` behind an explicit *"standing assumption, not construction"* caveat
(`TombstoneRegistry.kt:184-191`); and (d) its own KDoc fences it — *"This record changes exactly one
comparison — the tombstone guard's — and nothing else reads it"* (`LocalWriteMetadata.kt:45-46`).
**It has not been evaluated for this purpose and is not proposed for it here.**

⚖️ **What would have to exist first.** A per-write "already materialised" proof that is correct
during rehydration — which means it must distinguish *"I minted this HLC and applied it"* from *"I
minted this HLC on a store I no longer have"*. That is a durable-state question, not a threading
one, and it is upstream of everything in P1/P2/P3. **Open. No cheap answer was found, and none is
proposed here.**

#### F2 — ⛔ The coalescing rule reintroduces the bug at aggregation. Corrected rule below.

§6.6 carried forward *"any non-`SYNC_INGEST` member wins"* as the obvious rule for what origin a
coalesced diff reports. **Once P1.1 mints a distinct self-echo value, that rule is wrong in exactly
the direction §0.6 exists to prevent.** Under the corrected split a peer's write stays `SYNC_INGEST`
and a self-echo gets the new value — so a diff coalescing *one peer write and one self-echo* has a
non-`SYNC_INGEST` member, the rule labels the whole emission a self-echo, and **P2 drops a real peer
update.** The original defect, re-entering one layer down.

✅ **Corrected rule, and it must be stated as a conjunction rather than a winner:**

> **Suppress a coalesced emission only when *every* contributing write is proven redundant.
> Any contributor that is news — or whose status is unknown — makes the whole emission news.**

⚠ The default matters as much as the rule. The identity element is **news**, not silence, so a
contributor that cannot be labelled at all degrades toward **over**-invalidating: a wasted reload,
never a stale screen. That is the only safe direction here, and it is what the snapshot producers
need, since they structurally cannot report an author (§0.6, `SliceSnapshotProducer.kt:91-95`).

⚠⚠ **And the rule is not expressible today at all.** `triggerUpdate` does not aggregate writes — it
re-evaluates the whole stream and diffs it against the previous baseline
(`StreamRegistry.kt:518-525`, §0.3), so there is **no set of contributing writes to quantify over**.
"Every contributor is redundant" requires that set to exist first. That is new surface **additional**
to P1.2's, and it is why §6.6 is no longer a rule to pick but a structure to build.

---

## 1. The claim

`core-paging/.../PagingUtil.kt` collapses reactive reload signals with a **trailing 400 ms debounce,
plus a 3 s ceiling** (the ceiling is new since v0.1 — §0.2):

```kotlin
internal fun <T : Data> TList<T>.reloadSignals(): Flow<Unit> = merge(
    flowOf(Unit),                                        // initial load
    changes(emitOnSubscribe = true)
        .debounceExceptFirst(INVALIDATION_COOLDOWN_MS, INVALIDATION_MAX_WAIT_MS)
        .map { },
)
```

The window exists because one user action can produce several change signals — per its own comment,
"the entity save, the parent-relation maintainer's array update, and the store's write-commit echo —
empirically two waves ~280 ms apart". Reloading per signal would mean N reloads per change.

**That is a real problem with a deterministic solution, and this codebase already implements it —
one plane over.** `SyncObserver` faces the identical question (N saves from one logical unit must
not emit N `SyncBlock`s) and answers it with two mechanisms, neither of them a timer:

| Mechanism | Where | What it collapses |
|---|---|---|
| **Lane / task identity** — `saveData(data, origin, syncLaneId)`, `requestSyncLaneId` | TS-2/TS-3, `core-background-processing.md` §10 | N saves from one logical task → ONE delta batch |
| **Write origin** — `if (origin == SYNC_INGEST \|\| origin == DERIVED) return` | `SyncObserver.kt:450` | ⚠ **what must not be re-broadcast**: any accepted inbound block — *whoever wrote it* — plus a locally derived projection. v0.2 glossed this as "the device's own write echoing back"; that is the misreading §0.6 corrects |

⚠ **Both signals reach the proxy and are then discarded — twice over.** `origin` is threaded to the
array listener (`fireArrayAdded(arrayHash, value, origin)` → `ArrayListeners.onAdded(value, origin)`,
pinned by `DatabaseArrayListenerTest`), `StreamRegistry` drops it on registration, `triggerUpdate`
replaces the change with a re-evaluated diff, and `changes()` maps whatever survives to `Unit`. The
discriminator exists; nothing downstream of the database is built to carry it (§0.3).

⚠ **`WriteOrigin` grew a third member, `DERIVED`, while v0.1 was being drafted** — a locally
recomputed projection — because `MomentReconciliationPass` wrote its verdict as `USER` and two paired
devices then emitted **~99.8% of all realm traffic** to each other, sustained at 12–32 blocks/min, of
which only 4 blocks in 1,600 carried an entity either side had not already seen. Its KDoc draws the
distinction precisely: "an ingest echo is a *duplicate*; a derived echo is a *disagreement*."

v0.1 read that as a *trend* — "the discriminator keeps growing because it keeps turning out to be
load-bearing". ⚠ **Seven weeks later it has not grown again** (§0.1). The honest version of the
argument is narrower and still sufficient: the discriminator is **three-valued and load-bearing on
the sync plane**, and the reactive plane erases all three to `Unit`. A timer can distinguish none of
them.

⚠⚠ **And narrower again after review (§0.6).** Erasing the discriminator is a real loss, but
recovering it would not by itself give the reactive plane what it needs: the question the sync plane
asks of `origin` is *"may I put this on the wire?"*, and the question a listing asks is *"is this news
to the screen?"*. `SYNC_INGEST` answers the first for both a peer's block and this device's own echo,
and it is also what a joiner's first-ever rows are written with (`SliceSnapshotProducer.kt:88-89`).
The two questions have different answers on the same value, which is exactly why §4's filter cannot
be built on it.

---

## 2. What was measured — ⚠ in 2026-08, and not re-run

Probe at the notification site (`DatabaseImpl.fireArrayAdded`/`fireArrayRemoved`), reporting
monotonic ms, `origin`, the `Database` instance and the observed array, over
`FilesPagingIntegrationTest` + `SortToggleIntegrationTest` + `FilesListingEfficiencyTest`.

⚠⚠ **Read §0.4 before using these numbers.** They were taken against the unprojected receiver; the
Drive listing has observed a projected chain since SP-3. They are recorded history, not a current
characterisation.

### 2.1 A single logical change emitted exactly ONE signal (2026-08)

```
db=780950100  arr=-1813171378   1 signal   origin=USER
db=1454084210 arr=-1813171378   1 signal   origin=USER
```

⚠ For the single-edit case the window **collapsed nothing and cost its full duration**. Corroborated
independently, and this corroboration *is* still current: the wait in `FilesPagingIntegrationTest`
takes **403–425 ms of 5 000 across 15 runs, near-zero variance under six CPU burners**
(`docs/tech-debt.md`, entry intact) — that flat distribution *is* the cooldown, not work. The file's
comment concedes the latency ("correctness over ~a quarter-second"); the finding was that in this
case it bought nothing at all.

### 2.2 Bursts were real — and ~20× smaller than the constant (2026-08)

```
db=371646484  arr=-250824328   3 signals at t=58, 71, 77 ms   (gaps 13 ms, 6 ms)
```

Three uploads → three signals spanning **19 ms**. A window of ~50 ms would have collapsed that burst
completely.

⚠ **What this does not generalise to, and §0.2 is why.** Three uploads are not a backup. A
Synced-Folders drain is a burst measured in minutes, which is the case B4 shipped the 3 s ceiling
for. "The constant is 20× the burst" is a statement about a three-file burst only.

### 2.3 ⚠⚠ The wave the constant is actually sized for was NOT observed — and could not be

No `SYNC_INGEST` signal appeared at all, and nothing resembling "two waves ~280 ms apart". **These
tests run without a relay, so a self-echo cannot occur in them.** The ~280 ms figure describes an
on-device observation involving a round-trip this environment structurally cannot produce.
⚠ **Corrected in v0.5 (§8.4 P0a):** *this suite* has no relay, but `:system-test-utils`' `TestSystem`
runs a real in-process one, so the echo **is** reproducible off-device.

⚠ **Corrected 2026-09-24 (§0.6): "no `SYNC_INGEST`" is weaker evidence than it reads as.** With no
relay these tests have **no ingest of any kind** — no self-echo *and* no peer write — so the absence
proves only that the environment is single-device. It does not license reading a *present*
`SYNC_INGEST` as a self-echo, which is the step v0.2 took in §4. It also means this probe never
exercised the discriminator P0 now has to measure.

⚠ **Therefore: do not shrink `INVALIDATION_COOLDOWN_MS` on the strength of §2.1/§2.2.** That would
be weakening a constant whose justification lives outside the measurement — the same move
`docs/tech-debt.md` records as wrong three times over (the store soak assertion, the `waitUntil`
sweep, the `MapRegionServiceTest` budget). The measurement narrows the question; it does not answer
it. **P0 answers it.**

---

## 3. Why a timer is the wrong shape, beyond elegance

Two costs, one of which is already paid:

1. **Latency on every isolated edit** — a quarter-second before a create appears, buying nothing
   (§2.1).
2. ⚠⚠ **It makes a timing bug representable, and one shipped.** The subscribe-window lost-write fix emitted a readiness tick and
   routed it through this same window; the reload therefore landed ~400 ms later, *after* the listing
   was on screen and being scrolled, and a new `PagingData` generation mid-scroll resets the
   presenter under the reader (`IndexOutOfBoundsException: Index: 44, Size: 40`,
   `FilesListingEfficiencyTest`, 3 of 3 full gates, invisible in isolation). The follow-up fixed it by
   routing the tick *around* the window, and `debounceExceptFirst`'s KDoc still carries the record.

   The general form is the point: **a deferred signal has a "later" in which to land, and the set of
   things it can land in the middle of is unbounded.** A boundary-triggered signal has no later. The
   follow-up fix removed one instance; it did not remove the class.

⚠ **Updated 2026-09-23 — cost 2 is now half-priced deliberately.** B4 ships a rebuild *during* a long
burst and tests it with a scroll in flight, because the alternative (a listing frozen for the length
of a backup) is worse. So a mid-scroll rebuild is no longer only a bug: it is a trade the codebase
now takes on purpose, with a per-generation `rowKey` dedupe covering the rows an offset re-serves.
What the dedupe does **not** cover is the presenter reset itself, which is the half the readiness tick tripped
over. The argument stands; its framing must not pretend the trade-off is unpriced.

---

## 4. The shape — ⚠⚠ re-scoped 2026-09-23, then **blocked** 2026-09-24

v0.1 proposed two collapsing primitives. ⚠⚠ **Neither is implementable as written today:** one is
contradicted by shipped design (§0.2), the other rests on a signal that does not exist (§0.6). What
is left is a diagnosis and a list of what would have to be built first.

- ~~**Fan-out within one request/task → one signal, keyed by lane id.**~~ ⚠⚠ **Withdrawn as
  written.** A lane spans a whole backup drain or folder-upload walk, not a 19 ms burst, and B4
  established that a listing must rebuild *during* such a burst. Emitting once at the lane boundary
  would reintroduce exactly the unbounded drift `INVALIDATION_MAX_WAIT_MS` exists to bound. If lane
  identity has a role here at all, it is as a *coalescer bounded by the same ceiling* — "at most one
  signal per lane per 3 s" — which is a throttle with a better key, not the timer's replacement. That
  is a smaller and much less interesting claim, and it should be argued on its own merits rather than
  inherited from this document.
- ~~**The async echo → filter by `origin`.**~~ ⚠⚠ **Blocked as written (§0.6), and this is the
  correction that matters most in this revision.** The premise is sound: a relay round-trip is not
  bracketed by any local request, so it is precisely what a timer cannot name, and it is the only
  half the constant's stated justification (the "~280 ms second wave") actually points at. The
  *instrument* is wrong. `WriteOrigin.SYNC_INGEST` marks a save that must not be re-broadcast, which
  covers **a peer's change, this device's own echo, and a joiner's first-ever rows alike**
  (`SyncProcessor.kt:710-730`; `SliceSnapshotProducer.kt:88-89`;
  `FileSliceSnapshotProducer.kt:147-166`). Dropping on it would leave a listing stale through exactly
  the remote edit it most needs to show.

  ⚠ The self/peer distinction *is* computable at the block ingest site — `hlc.deviceId`
  (`core/.../time/HybridLogicalClock.kt:40`) against this device's own
  (`SyncProcessor.kt:174`), the comparison `DeviceLastSeenRecorder.kt:63-67` already performs off the
  same HLC — but it is **not present at the seam this filter sits at**, and it is **structurally
  absent** on the snapshot path, which carries no HLC by contract (`SliceSnapshotProducer.kt:91-95`).
  So this is not a narrowing; it is new surface. §0.6 lists the three things that must exist first.

  ⚠⚠ **And the replacement is itself blocked (§0.7 F1).** `hlc.deviceId` proves *authorship*, not
  *redundancy*: a device rehydrating from its own relay history authors every block of its own
  first-time materialisation (`SyncProcessor.kt:238-245`). A filter keyed on authorship alone would
  hold a listing empty for the length of a store recovery. The missing half — a proof that the exact
  write is already materialised — does not exist today in any form this document could find.
- **Genuinely independent user actions stay independent.** Three folders created by hand are three
  changes and should be three reloads; they are seconds apart, so nothing needs collapsing.

⚠⚠ **Net, revised twice.** v0.2 said: if P0 shows the second wave is the self-echo, an `origin`
filter can retire the 400 ms cooldown's correctness role. **That conclusion does not follow.** Even a
P0 that returns "yes, the second wave is this device's own echo" leaves no origin the filter can key
on, because the origin it would key on is also carried by the peer writes and the joiner rows the
listing must not miss. The 400 ms cooldown therefore **keeps its correctness role until the
discriminator in §0.6 exists**, and `INVALIDATION_MAX_WAIT_MS` keeps its separate one (bounding drift
under a long burst) regardless. Neither constant is retirable on the strength of this document as it
stands.

⚠⚠ **Revised a third time (§0.7).** The discriminator §0.6 pointed at does not exist either — what
exists is half of it. So the sentence above is not "wait for a known piece of work to land"; it is
"wait for an unsolved durable-state question (§6.9) to be answered". **P0 is the only thing in this
document that can be done now**, and it is a measurement.

---

## 5. Phasing

**P0 — measure the echo on a real device. No behaviour change.** ⚖️ **Still the gate, still
unanswered as of 2026-09-24, and now the *only* phase that is unblocked.** Annotate the reactive
change signal with `origin` (available at the array listener) and log it behind the existing
diagnostic path, then reproduce the ~280 ms two-wave observation on-device with a live relay.
⚠ Re-probe §2.1/§2.2 against the **projected** invalidation chain while there (§0.4) — the old
counts characterise a chain Drive no longer observes.

⚠⚠ **P0 now needs a second field, and §0.6 is why.** Logging `origin` alone cannot answer the
question, because `SYNC_INGEST` does not separate a peer's write from this device's echo. The probe
must **also** record the block's author — `hlc.deviceId` at `SyncProcessor.kt:651-654`, against
`syncManager.currentRealmContext()?.deviceId` (`SyncProcessor.kt:174`) — so the wave can be
attributed. ⚠ Note that a save reaching the array listener has no author field to log
(`DatabaseImpl.kt:64-66`), so P0's probe has to straddle two seams: the author at ingest, the
notification at the listener, correlated by entity id. That is a measurement-only widening, but it is
not a one-line log.

Outcome, restated: either the second wave is an ingest of **this device's own** blocks (⇒ the
diagnosis holds, and the work is the discriminator in §0.6, not a filter), or it is a peer's (⇒ there
is nothing to suppress and the cooldown is doing exactly what it should), or it is something else
entirely (⇒ this proposal is wrong about the mechanism). **Nothing else here should be built before
P0 returns**, and a "yes" no longer implies an implementable filter.

**P1 — a *usable* discriminator on the reactive path.** ⚠⚠ **Re-scoped twice.** v0.1 called this
"carry `origin` to the client, additive, default-unchanged". §0.3 showed it is not client-side-only;
§0.6 shows that **carrying `origin` is not even sufficient** — the value that arrives is the one that
conflates peer, self and joiner. P1 is therefore two pieces, and the first is upstream of everything
v0.1 described:

1. **Mint the distinction where it exists.** 🔴 ⚠⚠ **Blocked on §0.7 F1 — and this is where the
   second review pass stopped the design.** `SyncProcessor` would classify a save as self-echo or
   peer and put that on the save channel — `ExecutionContext.saveData(data, origin, syncLaneId)`
   (`ExecutionContext.kt:610-614`) carries no author today. But **the classifier §0.6 proposed does
   not work**: `hlc.deviceId == ownDeviceId` (`SyncProcessor.kt:651-654`, `:174`) proves *authorship*,
   and a device replaying its own relay history after a store wipe authors every block of its own
   **first-time** materialisation (`SyncProcessor.kt:238-245`). Suppressing on authorship alone would
   hold a listing empty for the whole of a rehydration. The predicate needs a second part — *"and this
   exact write is already materialised"* — which **nothing today provides**, and `SyncMetadata`
   structurally cannot, because it is written by the ingest paths only
   (`sync-service-common/.../LocalWriteMetadata.kt:20-26`). See §0.7 F1 for why `LocalWriteMetadata`
   is not it either. ⚠ The rest of this step stands and is still owed once the predicate exists:
   whether the carrier is a fourth `WriteOrigin` member or a separate field is a real decision with a
   real blast radius — `SYNC_INGEST` is read by `SyncObserver.kt:450`, `MemberSelfBindObserver.kt:57`,
   `IngestedDeleteRecorder.kt:40` and `TombstoneRegistry.kt:189, 284`, and each must be told which
   side of the split it wants. ⚠⚠ And the snapshot producers cannot participate at all
   (`SliceSnapshotProducer.kt:91-95`), so the design must say explicitly what they emit — the honest
   default being "always news".
2. **Then** carry it to the client, which is §0.3's problem unchanged: `StreamRegistry` must keep
   what it drops at registration (`StreamRegistry.kt:486-487`), a rule is needed for what a
   **coalesced diff** carries, and a signal-only stream needs a way to report it without shipping a
   payload. `changes()` keeps its `Flow<Unit>` shape by default, so no existing consumer changes.

**P2 — the filter itself.** ⚠⚠ **Blocked on P1.1, not merely re-scoped.** There is no filter to write
until the signal exists (§0.6); v0.2's "re-scoped to the origin filter only" was still assuming a
filter was available, and it is not. ⚠⚠ **Real blast radius when it is unblocked, and wider than in
2026-08**: `reloadSignals()` now backs `keysetPagingSource` as well as `pagingSource`, so Photos and
Fit ride it alongside Drive. Wants its own verification at **gate scope** — module-scope green is what
missed the readiness tick's regression. ⚠ It also needs a test that a **peer's** write still invalidates an open
listing: that is the exact failure this revision was opened to prevent, and a filter that suppresses
it looks identical to one that works until a second device is in the room.

⚠⚠ **Second block, from the second review pass (§0.7 F2): P2's aggregation rule is wrong as
inherited.** "Any non-`SYNC_INGEST` member wins" labels a coalesced *peer-write-plus-self-echo* diff
a self-echo the moment P1.1 mints a distinct value for the latter, and P2 then drops a real peer
update. The rule must be **"suppress only when *every* contributing write is proven redundant"**, with
unknown counting as news — and `triggerUpdate` has no contributor set to quantify over today
(`StreamRegistry.kt:518-525`). So P2 is blocked on P1.1 *and* on a structure that does not exist.

**P3 — retire or shrink `INVALIDATION_COOLDOWN_MS`**, justified by P0's answer rather than by §2's
numbers. ⚠⚠ **Now gated on P1.1 and P2, not on P0 alone (§0.6).** P0 can only tell us what the wave
*is*; until a save can say whose write it was, the cooldown remains the only thing collapsing it, and
shrinking it on the strength of P0's diagnosis would be the §2.3 mistake with a better story. ⚠
`INVALIDATION_MAX_WAIT_MS` is **out of scope** throughout: it bounds drift under a long burst, which
no origin filter addresses.

---

## 6. Open questions

1. **What is the ~280 ms second wave?** The whole proposal turns on this. P0. **Open.** ⚠ Reframed
   by §0.6: the question is no longer "is it `SYNC_INGEST`" (that value cannot answer it) but "whose
   write is it" — which the probe must read off `hlc.deviceId`, not off `origin`.
2. ~~**Does a lane-scoped signal need a lease/timeout?**~~ **Answered, in the negative, by B4**
   (§0.2): a lane that closes *normally* but slowly is already enough to freeze a listing for the
   length of a backup, so the failure needs no pathological never-closing lane. `TaskRegistry`'s lease
   would not have saved it. This is the question that retired the lane half of §4.
3. **Is `flowOf(Unit)` + readiness tick still the right initial-load shape**, or does a
   boundary-scoped design let the initial load ride the same mechanism? ⚠ Note the constraint from
   the subscribe-window fix: gating the initial load on observer registration turns a *stale* screen into a permanently
   **blank** one if registration never lands. **Open.**
4. **Should `origin` be visible to app-facing consumers at all**, or stay proxy-internal? Leaking
   "this change came from sync" into UI-facing APIs invites consumers to branch on it. **Open.** ⚠
   §0.6 sharpens the risk: `IngestedDeleteRecorder.kt:22` already documents the origin as "from
   another device" when the enum does not promise that, and that is a *proxy-internal* consumer. A
   value this easy to misread is a poor candidate for a public API.
5. ⚠ **Should a `DERIVED` write invalidate a listing?** Not obvious, and it does **not** follow from
   `SyncObserver`'s answer. `DERIVED` must never be *broadcast* — the peer derives it itself — but
   locally it changes state a screen may be displaying (`supersededBy` is exactly such a field), so
   suppressing it in the reactive plane could strand a listing showing a value the device has already
   superseded. ⚖️ The sync answer and the UI answer genuinely differ here, and copying the former into
   the latter would be the easy mistake. **Open.**
6. ⭐ **What origin does a coalesced diff carry?** (2026-09-23) `triggerUpdate` re-evaluates and
   diffs rather than forwarding a write, so one emission may cover several writes of different origins
   (§0.3). ⚠⚠ **Answered in the negative, and re-opened in a different shape, by review (§0.7 F2).**
   "Any non-`SYNC_INGEST` member wins" — this document's own suggestion — is **wrong**: once P1.1
   mints a distinct self-echo value, that value is itself non-`SYNC_INGEST`, so a diff coalescing a
   peer write and a self-echo would be labelled self-echo and dropped. ✅ **The rule is: suppress only
   when *every* contributing write is proven redundant; any contributor that is news, or whose status
   is unknown, makes the emission news.** The default is news, so an unlabellable contributor costs a
   wasted reload rather than a stale screen. ⚠ Still to be *decided and pinned by a test* — an
   aggregation that silently defaulted the other way would make the filter suppress real updates, and
   that looks exactly like a working filter until a second device is in the room. **Open, and now
   also structural: there is no contributor set to quantify over (`StreamRegistry.kt:518-525`).**
7. ⭐⭐ **New (2026-09-24, from review): how does a save say whose write it was?** (§0.6) A fourth
   `WriteOrigin` member versus an explicit author field; what the five existing `SYNC_INGEST` readers
   want on each side of the split; and what the slice-snapshot producers emit when they structurally
   cannot answer (`SliceSnapshotProducer.kt:91-95`). ⚠ **No longer the gating question — question 9
   is.** Knowing *whose* write it was is necessary and not sufficient. **Open.**
8. ⭐ **New (2026-09-24): should a self-echo be suppressed at the notification site at all**, or
   simply not *produced*? `SyncProcessor` knows what it is about to write before it writes it
   (`SyncProcessor.kt:651-654`). Not re-saving a block this device both minted *and* already holds
   would remove the wave at source rather than filtering it downstream — cheaper, and it needs no new
   channel. ⚠⚠ **But this question is now downstream of question 9, not a shortcut past it.** v0.3
   suggested `SyncMetadata`'s `isNewer` comparison (`SyncProcessor.kt:689`) as the "have I already got
   this" test; §0.7 F1 shows it is not one, because `SyncMetadata` records only what arrived over the
   wire (`LocalWriteMetadata.kt:20-26`) and a local write has none until its own echo writes it. The
   rehydrate case (`SyncProcessor.kt:238-245`) constrains this form exactly as it constrains P1.1.
   **Open — still possibly cheaper than P1/P2, but not cheaper than the predicate they both need.**
9. ⭐⭐⭐ **New (2026-09-24, second review pass): what proves a write is already materialised?**
   ⚖️ **This is now the gating question for the whole document** (§0.7 F1). It must hold during a
   store rehydration, where this device authored blocks it has never applied — so it cannot be an
   authorship test, and it cannot be `SyncMetadata`, which by construction records only the wire
   (`LocalWriteMetadata.kt:20-26`). `LocalWriteMetadata` is the nearest existing record and is **not**
   proposed as the answer: it is one row per entity, highest-HLC-wins
   (`TombstoneRegistry.kt:221-231`), stamps the device's clock rather than the block's HLC, is gated
   behind a "standing assumption, not construction" caveat (`TombstoneRegistry.kt:184-191`), and is
   explicitly fenced to a single reader (`LocalWriteMetadata.kt:45-46`). This is a **durable-state**
   question and it is raised here, deliberately unanswered. ⚠ It gates P1.1, and through it P2 and
   P3. **Open. No cheap answer found.** ⭐ **v0.5 proposes one: store equality (§8).**

---

## 7. Relationship to adjacent work

- [`drive-sorted-folder-paging.md`](drive-sorted-folder-paging.md) §6 B4 / §10 — **COMPLETE, shipped
  2026-09-15.** ⚠⚠ The document that contradicts §4's lane bullet, and that added
  `INVALIDATION_MAX_WAIT_MS`, the `invalidation` hook and the `rowKey` dedupe. Read it before reading
  anything here as current.
- [`drive-folder-listing-consistency.md`](drive-folder-listing-consistency.md) — owns the staleness
  requirement this must not regress. The readiness tick closed its last known gap (a write landing in the subscribe
  window, before observer registration). ⚠ Its part (2) — dropping `cachedIn` — was deliberately undone
  on 2026-08-12; the doc carries the correction inline.
- [`core-paging-scroll-restoration.md`](core-paging-scroll-restoration.md) — ✅ complete (SR-1…SR-5).
  Its three reactivity guards are the live contract for this pipeline; if one goes red, the `cachedIn`
  ban is back and this proposal's environment has changed under it.
- [`core-keyset-paging.md`](core-keyset-paging.md) — K-1 shipped `keysetPagingSource`, which shares
  `reloadSignals()`. The reason P2's blast radius grew (§0.5).
- [`core-background-processing.md`](core-background-processing.md) §10 — the lane machinery
  (TS-1…TS-5, implemented 2026-07-24). Still the correct reference for what a lane *is*; §0.2 is why
  it is not the collapsing primitive for listings.
- [`core-client-resubscription.md`](core-client-resubscription.md) — `StreamSignal.Subscribed`, the
  readiness signal the subscribe-window fix turned on; R1–R6 complete.
- [`core-observer-async-dispatch.md`](core-observer-async-dispatch.md) — asks what relaxation of
  `ObserverRegistry.onDataSaved`'s blocking dispatch is acceptable. It shares this document's seam and
  should be read alongside P1: both want to change what a save tells its observers.
- `docs/tech-debt.md` § "`FilesPagingIntegrationTest` — a write during the subscribe window was lost"
  — where §2.1's 403–425 ms corroboration lives. ⚠ The § "The full gate is flaky under parallel load"
  entry v0.1 cited is now marked **closed** and kept as the record.
- ⚠⚠ [`sync-device-catch-up.md`](sync-device-catch-up.md) §5.7.2 (C8) and its port
  `core-common/.../proxy/SliceSnapshotProducer.kt` — **new to this revision, and the reason §4 is
  blocked rather than narrowed.** The joiner-snapshot contract *requires* `WriteOrigin.SYNC_INGEST`
  on rows that are a device's first sight of them (`SliceSnapshotProducer.kt:88-89`) and *forbids*
  the `SyncMetadata` that would carry an author (`:91-95`). Any future proposal that wants to branch
  on `SYNC_INGEST` has to answer to this contract first.

---

## 8. ⭐ v0.5 proposal (2026-09-24) — ask *"did the store change?"*, not *"who wrote it?"*

⚠ **A proposal, not a measurement.** Everything in this section is read off the code at master
`6cfcf55`. P0a (§8.4) is the phase that turns it into a finding, and until it returns this is a
hypothesis with a cheap test attached.

### 8.1 The reframe

Every revision so far has tried to answer §6.9 with an **authorship** test — `WriteOrigin` (v0.2,
withdrawn §0.6), then `hlc.deviceId` (v0.3, withdrawn §0.7 F1). Both fail for the same reason: the
listing does not care who wrote a row, it cares whether **what it would render changed**. So ask the
store that, directly:

> **A save is redundant iff every `DbEntry` it writes is equal to the entry already stored at that
> hash.** Unknown, absent, or unequal ⇒ the save is news.

That is a per-write "already materialised" proof in exactly §6.9's sense, and it needs no durable
state beyond the row itself.

### 8.2 Where the notification actually comes from

The reactive refresh for an existing member does **not** come from an array-membership change. It
comes from `RelationMaintainer.onDataSaved`'s steady-state branch
(`core-common/.../relation/RelationMaintainer.kt`, `1 -> executionContext.database.notifyArrayMemberUpdated(…)`),
which fires `onAdded` on **every re-save of a member, including a byte-identical one** — by design,
because "a re-save IS a change to what the relation resolves to". `StreamRegistry.triggerUpdate`
then re-evaluates the stream. A self-echo that writes back exactly the bytes already stored takes
this branch.

⚠ So that branch is the **only** consumer §8 proposes to change. `SyncObserver`, `TombstoneRegistry`,
`IngestedDeleteRecorder`, `MemberSelfBindObserver` and every other `DataObserver` see saves exactly as
today.

### 8.3 How it answers each blocker

| Blocker | Answer under §8.1 |
|---|---|
| §0.7 F1 — rehydration authors its own first-time rows | ✅ The row is **absent** ⇒ unequal ⇒ news. No authorship test is involved, so there is nothing to falsify |
| §0.6 — a peer's write is `SYNC_INGEST` too | ✅ Different bytes ⇒ news |
| §0.6 — slice-snapshot rows carry no HLC | ✅ First sight ⇒ absent ⇒ news. No author field needed, so the `SliceSnapshotProducer` contract is untouched |
| §0.7 F2 — a coalesced diff needs a contributor set | ✅ **Dissolved, not answered.** The redundant write is dropped *per write, before* `triggerUpdate`, so it never enters the coalesced re-evaluation. A peer write in the same window triggers on its own. The conjunction rule holds trivially: only fully redundant writes are elided |
| §6.5 — should `DERIVED` invalidate? | ✅ Iff its value changed. The sync answer and the UI answer can now differ, as §6.5 said they must |
| §6.7 — fourth `WriteOrigin` member vs author field | ✅ Neither. No enum, IPC, `StreamRegistry` or `changes()` change |
| §0.3 — origin is dropped proxy-side before IPC | ✅ Irrelevant: nothing new has to cross IPC |
| House Rule 9 / threat model | ✅ No stored-row or wire shape change, no A1–A4/A9 touch |

⚠ **What it does not answer.** §6.1 (*what* is the ~280 ms wave) is still an empirical question: §8
only helps if that wave is byte-identical re-saves. If it is something else, §8 is a no-op for it
(fails safe: every elided-or-not save behaves exactly as today when unequal).

### 8.4 Phasing — supersedes §5 for everything after P0

Each phase is its own stacked PR.

**P0a — measure it in a JVM system test.** 🟢 **Unblocked; start here.** ⚠ **Corrects §2.3:** the
echo is **not** structurally unreproducible off-device. `:system-test-utils`' `TestSystem` runs a
real in-process `:sync-relay-server`, so a host uploading a file *does* pull its own block back.
Scope, no behaviour change:
- `Database.saveDataReportingChange(data): Boolean` — defaulted to `saveData(data); true` (unknown ⇒
  news, so every existing fake keeps working and fails safe); `DatabaseImpl` overrides it by comparing
  each entry the mapper writes against `objectStore.getObject` **before** storing it.
- A system test that opens a listing observer on the host, uploads, lets the echo land, and records
  per `onAdded`: origin, whether the triggering save was redundant, and whether the block's
  `hlc.deviceId` is the host's own. It asserts nothing about behaviour; it pins what the echo *is*.

✅ **P0a result (2026-09-24, `SelfEchoRedundancySystemTest`, JVM, two clients over the in-process
relay).** Host saves of a file the host uploaded, and of one the joiner uploaded:

```
own.txt  : [USER redundant=false, SYNC_INGEST redundant=true]    ← the echo is byte-identical
peer.txt : [SYNC_INGEST redundant=false]                         ← a peer's write is news
```

The one self-echo is a single `SYNC_INGEST` save, and the store predicate calls it redundant while
calling the peer's write news — the two cases `WriteOrigin` cannot separate (§0.6). ⚠ Scope: one
upload each, JVM `InMemoryObjectStore`, unprojected. Rename/overwrite echoes, `SqliteObjectStore`,
and the ~280 ms on-device wave are P0b's.

**P0b — confirm on a real device.** 🟠 **Not done.** ⚠ It now also has to check
P1's production cost on the device stores (Android's `SqliteObjectStore`, iOS's SQLite store, which has no row cache) — below. Same fields, logged through
`:diagnostics`, against a live relay and the **projected** listing chain (§0.4). Its question is
narrower than the old P0: *is the on-device ~280 ms wave the redundant re-save P0a finds?*

**P1 — gate the steady-state notification on the bit.** `ExecutionContext.saveData` asks
`saveDataReportingChange` and hands the answer to observers that opt in — a
`LaneAwareDataObserver`-style sibling interface, so the other 15+ observers are untouched —
and `RelationMaintainer` skips **only** `notifyArrayMemberUpdated` when the save was redundant. The
`0 ->` (add) and duplicate-repair branches are unchanged.
⚠ Pitfalls:
- `DbValue.ByteArrayValue` is a `data class` over a `ByteArray`, so its `equals` is **reference**
  equality. The comparison needs a `contentEquals`-aware walk, or every byte-carrying row reads as
  changed — fail-safe, but it hides the benefit.
- One save writes several entries (nested composites, the only-grow list rows). The bit is the **OR**
  over all of them.
- Cost: one extra `getObject` per written entry. On `SqliteObjectStore` a cache miss is a disk read;
  measure on a bulk upload before calling it free.
- ⚠⚠ **Audit for deliberate identical re-saves used as a refresh kick** (the readiness-tick
  shape). Anything that relied on "a re-save always notifies" stops working, silently.

✅ **P1 merged (built 2026-09-24, revived onto master 2026-11-11).** `RelationMaintainer` is a `SaveOutcomeAwareDataObserver`;
only its steady-state `notifyArrayMemberUpdated` is skipped on a redundant save.
`RelationMaintainerWriteCountTest`'s re-save test was split: a **new value** still notifies once (the
`DeviceState` case `Database.notifyArrayMemberUpdated`'s KDoc names), an **identical** re-save now
notifies nobody. `FolderFilesRelationUpdateTest.aReSavedFileNowTellsOpenListings` was split the same
way; it was the gate's only red.
⭐ **Revived with a per-type gate.** `RelationMaintainer` is installed everywhere, so as first built
every production save paid the comparison. `SaveOutcomeAwareDataObserver.wantsSaveOutcome(data)`
(default `true`) now lets it ask only for types a relation keys on, so `SyncMetadata`, tombstones and
other bookkeeping rows take the plain `saveData` (pinned by
`RelationMaintainerWriteCountTest.aTypeNoRelationKeysOn_doesNotPayTheStoreComparison`).
A throwaway JVM micro-bench on `SqliteObjectStore` (20k rows, 64 KB row cache, warmed) measured fresh
inserts 60 → 60 ms, overwrites 61 → 62 ms, identical re-saves 61 → 72 ms: an absent row costs nothing
because `knownHashes` gates the read. ⚠ That is desktop JVM only; the device numbers are P0b's.
The refresh-kick audit (pitfall above) found no production code relying on an identical re-save
notifying a listing.
⚠ A changed **identified** child reached through its parent is redundant too, and correctly so:
`DbEntryMapper` writes such a child create-only, so the store does not change
(`SaveDataReportingChangeTest`).

**P2 — pin it at gate scope.** Tests that: a **peer's** write still invalidates an open listing; a
self-echo does not; a rehydrating (wiped) store fills an open listing as it arrives (F1's case); and
the B4 ceiling test
(`SortedFolderPagingIntegrationTest.continuousWritesDuringAScroll_rebuildWithinTheMaxWait_andNeverDuplicateARow`)
stays green. `keysetPagingSource` consumers (Photos, Fit) ride the same path (§0.5).

✅ **P2 merged (2026-11-11): `ListingNotificationSystemTest`**, at the array-listener seam
`StreamRegistry` hangs off. The host's own echo does not notify its root listing (**without P1 it
fails: `[USER, SYNC_INGEST]`**); a peer's **edit** of a row the host already lists does — the case that
reaches the listener only through the gated branch, so silencing it or keying it on `SYNC_INGEST`
turns the test red (both mutations checked). A peer's new file and a wiped joiner refilling its own
history (F1's case) also reach the listing. ⚠ Those two cannot be broken by P1's gate: a membership
change arrives through the ungated add and diff-fallback paths.
⚠ **Finding for §0.3:** the peer write and the refill reach the listener through
`DatabaseImpl.setArrayListener`'s **diff fallback** (the parent folder's row rewrite), which always
reports `WriteOrigin.USER` because the save's origin is not visible there. So even at the database
seam `origin` is not reliable, which is one more reason §8 keys on store equality and not on origin.
Gate (`scripts/run-gate.sh`, macOS, the combined P0a+P1+P2 tree, 2026-11-11): 146 test tasks,
2,195 classes, all green, incl. the three reactivity guards of `core-paging-scroll-restoration.md`;
JS tests, JS lane and iOS lane green; `:control:mergeLibDexDebug` green. ⚠ No device run (P0b).
⚠ **Its load flake was the test's own sampling race, not P1 (2026-11-11).** The joiner's
`IngestProbe` was attached only after the host's echo was seen, but the host's echo lands *inside*
`upload` and the joiner ingests the file exactly once, ~100 ms later on an idle box. Under load that
one ingest could land before the probe existed, and the wait ran out its 30 s. Every probe is now
attached before the write it waits on; a 1.5 s delay injected at the old attach point turns the old
test red and leaves the fixed one green.

**P3 — revisit `INVALIDATION_COOLDOWN_MS`** on P0b's numbers, unchanged from §5.
`INVALIDATION_MAX_WAIT_MS` stays out of scope.
