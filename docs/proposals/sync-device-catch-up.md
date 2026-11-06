# Device catch-up — how a new or wiped device reconstructs state

- **Status:** **v1.1 — TRACK CLOSED. C1 + C2 + C3 + C4 + C6 + C7 + C8 + C9 DONE, C8a DECIDED,
  C8b CLOSED, C5 INVESTIGATED-AND-CLOSED (2026-08-07).**
  ⚠⚠ **C5 (§11.8) invalidated §5.2's premise**: compaction does *not* reduce log volume here — the
  relay drops superseded blocks only for **tombstoned** entities, and an entity-keyed supersede rule
  is blocked by T-R-08 (`propertyId` rides inside the ciphertext) and by lane-batched blocks
  (`targetEntityIdHash = null`). E4 itself was already built and buys **readable-floor advancement**,
  a schema-evolution payoff. The remaining volume question is a **new relay-side design** owing a
  `RELAY_PROTOCOL_VERSION` bump and an HR#2 amendment, sized against storage/quota rather than
  catch-up latency.
  ⚠ **Start at [§11 Implementation Notes for Claude Code](#11-implementation-notes-for-claude-code)** —
  the canonical handoff. Order is **~~C6 census~~ → ~~C7 thumbnails~~ → ~~C8a decide~~ → ~~C8 snapshot~~ →
  ~~C8b measure~~ → ~~C9~~ → ~~C4 audit~~ → ~~C5 compaction~~** — all phases closed.
  ⚠ **Open, and outside the phase map: the read-half device verification of C8/C9.** The write half
  is device-verified (§11.4.1); nothing has yet *consumed* a slice on hardware, which needs a
  freshly-provisioned joiner.
  ⚠⚠ **C4 (§11.7) found that the batching it was auditing for had never been measured**: the two
  system tests proving TS-4/TS-5 assert *convergence*, which holds whether or not anything batches,
  and in that harness nothing did (a delegating executor dropped the lane id). Production was
  unaffected; the tests were not evidence.
  ⚠⚠ **v0.9: C8 is built (§11.4.1), C8b is measured and folded into C9 (§11.4.2), and building C8
  corrected §11.4's own reader step.** Setting
  `lastSyncTimestamp` to the snapshot's `asOfSeq` is **unsafe** against C2 as shipped: `backfillOnce`
  captures `backfillTarget` from that cursor at handover, so raising it first would stop the backward
  walk at `asOfSeq` and strand every row the (per-slice) snapshot did not carry. The reader touches no
  cursor; the snapshot's job is content, not position. ⚠ The step was written under §5.7.2's original
  one-manifest-per-tree reading and was not revisited when that same section argued for per-slice.
  ⚠ **v0.8: C8a is decided — opaque service-sealed entries inside a typed platform envelope**
  (§5.7.2). `genericData` cannot hold an array, a `TList` member needs a `DataIdProvider` (three
  shipped types already work around exactly that), and — the decisive one — a **regenerable**
  artifact must not pay the frozen-set tax that exists to protect irreplaceable history.
  ⚠⚠ **v0.7: C7 shipped, and two structural facts the plan missed are recorded in §11.3.**
  (1) A thumbnail needs a **per-property `contentId`** — `manifestContentRef` keys on the content
  id alone and a `File` has one, so `Data`/`Thumbnail`/`ThumbnailLarge` would have overwritten
  each other's manifests at the same relay ref. (2) The cold read is **two-phase**: `GetPreview`
  resolves the blob locally and the dynamic-property path is synchronous, so it cannot await a
  fetch — the OnDemand fetch only happens at a suspend terminal, which means a caller projects
  the thumbnail properties first and reads the preview second. ⚠ The doc's own trap list named
  the client-Ro registration and `getAll()`; neither of these.
  ⚠⚠ **v0.6: C6 is done, and it settled the gate against the hypothesis it was raised to test.**
  Thumbnails are **97.26 % of every byte a `File` row costs** (~18.4 KB/file, ~336 B without) — so C7
  is worth more than §2.4 claimed *as a ratio* and less *as a total* (**~12.9 GB** at 700 k files, not
  ~21 GB). But **C7 alone does not reach "usable in seconds" and C8 is NOT optional**: the remaining
  two ceilings are **row counts**, and removing bytes moves no count (§11.2's gate table). ⭐ It also
  needed **no probe and no device run** — `SqliteObjectStore`'s table is `objects(hash, type, data)`,
  so per-type *and per-property* byte share is a `GROUP BY` plus a ~60-line decoder over a copy of the
  live store (⚠ copy the `-wal`: it was 206 MB against a 21 MB db). ⚠⚠ Chasing the largest row found a
  **live bug unrelated to catch-up** — resurrection protection was silently off for 10,522 entities,
  and C2's backfill had just turned it from latent into active. ✅ **Fixed in the same PR** (§11.2.1).
  ⚠⚠ **v0.4 amends this note in two ways that matter.** (1) §2.2's throughput diagnosis was
  *unmeasured and wrong*: store writes are 3 %, and the real costs were a per-block version-stamp
  re-parse (80 ms) and a linear by-bytes type-id scan (~16 % of wall-clock) — both the same
  `ByteArray`-cannot-be-a-hash-key root cause, both fixed, ~32× total (§5.5). (2) A file-heavy realm
  has **three ceilings no constant factor clears** (§2.4) — replay volume, **eagerly-synced
  thumbnails**, and the iOS one-file-per-row store — so the goal is restated as *usable immediately,
  complete eventually*, answered by §5.6 (thumbnails → OnDemand) and §5.7 (a joiner snapshot
  manifest, reusing the artifact `drive-folder-link-sharing.md` already designs). ⚠ §4's index
  rejections stand and are **not** the answer to that — §5.7.1. §5.7.2 works a concrete
  `RelationSliceSnapshot` example (⚠ envelope shared, entry projection per service) and ⚠⚠ corrects a
  naive reading of §5.7: the manifest must be
  **per-directory**, because one flat whole-realm blob is ~84 MB at 700 k files and reproduces the
  problem it was meant to solve. §5.7.3 then generalises "directory" — ⚠ there is **no bucket concept**
  in the platform; the unit is a **relation instance**, `Folder.FILES` is bounded for free, and the
  realm-wide singletons (`MOMENTS`, `MODIFICATIONS`, …) need a **service-chosen** slicing key.
  §5.7.4 places that key on a **new trait** rather than a field on `Syncable` — ⚠⚠ on **design**
  grounds (absence-as-default; ingest reads `Syncable` per block and must not carry a field it never
  uses), *not* on migration cost, which pre-release is ~free and which an earlier draft wrongly
  treated as decisive — and records service-aware manifests as a **prototype question**
  (⚠ prioritisation, never scoping). Opened by a live incident
  (§1) in which a wiped iPhone was **write-dead for an estimated 13 hours** while replaying relay
  history. Records what the problem actually is (measured, §2), **rejects TWO index designs**
  with reasons (§4.A peer-published, §4.B producer-written chain — they fail differently), and
  ranks the levers (§5). ⚠⚠ Nothing here proposes a new subsystem: the
  primary answer is **E4 compaction**, already designed in
  [`core-schema-evolution.md`](core-schema-evolution.md) §8.3. This note exists so the rejected
  alternative is not re-proposed, and so the *one* genuinely cheap piece (§5.3) is findable.
- **Date:** 2026-08-04
- **Owner:** Benedikt
- **Related:** [`core-schema-evolution.md`](core-schema-evolution.md) §8.3 (E4 compaction — the
  primary answer) · [`retention-quota-model.md`](retention-quota-model.md) §2 tier 1 (the same log,
  classified) · [`sync-lane-cursor-split.md`](sync-lane-cursor-split.md) (the cursor track this came
  out of) · [`core-local-store-orphan-gc.md`](core-local-store-orphan-gc.md) (the client-local twin)
  · [`core-background-processing.md`](core-background-processing.md) §10 (`SyncLane`, the write-side
  lever) · [`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) (the **encrypted tree
  manifest** §5.7 reuses) · [`drive-synced-thumbnails.md`](drive-synced-thumbnails.md) (§5.6 re-opens
  its eager decision) · [`fit-workout-streams-content-plane.md`](fit-workout-streams-content-plane.md)
  (the eager→OnDemand precedent) · [`bounded-store-cache.md`](bounded-store-cache.md) (§2.4's third
  ceiling) · `memory/project_entitlement_proof_channel` (the incident's third 402 cause)

---

## 1. The incident that opened this

A `TENSHU_STORAGE_FORMAT_VERSION` 19→20 bump wiped Drive-on-iOS's store. The device rebuilt by
replaying relay history from seq 0. Two days later **every `POST /sync/push` returned 402
`entitlement_required`** — the client was attaching no proof at all.

The cause was not an entitlement bug. Measured on 2026-08-04:

| | validFrom → validUntil |
|---|---|
| Desktop's `tenshu/identity/entitlement` row | 2026-08-02 15:41 → **08-09 15:41 UTC** ✅ |
| The phone's copy of the **same row** (identical DataId hash) | 2026-07-26 16:13 → **08-02 16:13** ❌ |

The desktop **renewed** the record on 08-02 (the entitlement-renewal tick, T-CB-08). That renewal is a normal
`Syncable(Eager)` row, so it sits in the log near the tip. The phone's block cursor was at
**103,891** against a tombstone cursor of **457,976** — roughly **354,000 seq of block history
unread**. It had applied the July record early in the replay and had not yet reached the renewal.

An expired record fails the client's *own* window check, so `proofHeader(now)` returns null, so
nothing is attached, so the relay refuses every write.

**The device was therefore write-dead until a bulk replay happened to reach one 245-byte row.**

⚠ Pull is deliberately **not** entitlement-gated (`SyncScheduler`: *"Pull still runs below … so a
synced `Entitlement`/revocation or a reconnect can be observed and clear the block"*), so there is
no can't-push → back-off → can't-recover loop. The design already anticipated this recovery path.
It just made no promise about *how long* it takes.

---

## 2. What the problem actually is — three problems, not one

Conflating these is what makes the situation look like it needs a new subsystem. It does not.

### 2.1 The log is long because it is uncompacted (the volume problem)

Measured on the desktop Control store, same day:

| type | rows |
|---|---:|
| `tenshu/sync/block` | **78,773** |
| `tenshu/sync/metadata` | **14,208** |

`SyncMetadata` is one row per live synced entity. So the log holds **~5.5 historical blocks per
live entity** — the overwhelming majority of what a joiner replays is **superseded versions of rows
it is about to overwrite anyway**.

⚠ This is the number that decides §4: the waste is *supersession*, not *misrouting*.

### 2.2 Applying the log is slow (the throughput problem)

Measured: the phone's block cursor advanced **+1,500 seq in 200 s** of foreground running — about
three 500-row pages, ~67 s per page, ~8 blocks/s.

**It is not interval-bound.** `SyncWakeSignal` is a `CONFLATED` channel whose contract is explicitly
level-on-edge (*"a `wake` fired while nobody is waiting is retained"*), and `SyncScheduler` fires
`wakeSignal.wake()` whenever a pull reports a backlog. So the loop re-ticks immediately; the ~67 s
is work.

⚠⚠ **Measured 2026-08-05, and the prime suspect above was WRONG.** This paragraph used to name
`FileObjectStore`'s one-synchronous-atomic-write-per-object as the likely cause and to defer to
batching/write-behind. Store writes turned out to be **~3 %**. Recording the miss, because the
instinct was plausible and would have produced a real change that bought nothing:

| per block, on the phone | cost | share |
|---|---:|---:|
| relay fetch (amortised over a 500-block page) | ~5.4 ms | ~5 % |
| AEAD decrypt | **13 µs** | ~0 % |
| envelope open | ~0 | — |
| **parse — version-stamp** | **80 ms** | **~63 %** |
| parse — the actual payload | ~8 ms | ~6 % |
| conflict resolution + observers | ~10 ms | ~8 % |
| store writes (~4/block, ~1.3 ms each) | ~5 ms | **~3 %** |
| store reads | below the 5 000-read probe threshold | ~0 % |

Two findings behind that, both the *same* root cause — `ByteArray` cannot be a hash key, so both
sites fell back to scanning:

- **The version stamp was re-parsed per block** (80 ms), on the *fast* path where no migration work
  is due, to re-derive a value identical across almost every block in the realm. Fixed by a
  content-keyed cache → **300 → 994 seq/min**.
- **`SchemaRegistry.getTypeId(ByteArray)` was a linear scan** — 1 336 entries compared per call,
  ~396 µs, **80 000 calls in 200 s ⇒ 31.7 s, ~16 % of wall-clock**. Not sync-specific: every parse
  pays it, so every IPC call and chain read did too. Fixed by a content-keyed index →
  **3 500 → 16 000 seq/min**.

Plus one that was not a lookup at all: `MomentReconciliationPass` re-read a 200-moment window on
**every** saved moment (~14.5 ms × ~400/tick ≈ 5.8 s, ~20 % once the stamp cost was gone). Coalesced
→ **994 → 3 500 seq/min**.

⚠ **And one measured and deliberately NOT changed:** `getSchema(String)` walks 25 of 33 configs per
call and is called **400 000 times in 200 s** — but each step is a hash lookup, so it costs 2.3 s
(**1.2 %**). Flattening it would buy ~1 % for the same cache-invalidation risk the type-id index needed two tests
to make safe. 5× more calls than `getTypeId`, 70× less cost each: the difference is scan-vs-hash, and
it is the reason the type-id index was the right target and this is not.

Net: **~500 → ~16 000 seq/min, ~32×.** ⚠⚠ Every one of these is a **constant factor** on a path that
is still O(entire history) — see §2.4, which is why none of it makes §5.6/§5.7 unnecessary.

### 2.3 Being behind makes the device non-functional (the priority problem)

The sharp edge, and the one that actually hurt. A row that gates the device's own ability to
function sits in the **same FIFO as bulk history**. The entitlement is the instance we hit; the same
argument covers `Device` roster rows and revocation tombstones.

⚠ Note this is **independent of** §2.1 and §2.2. Even a perfectly compacted, fast log has a tip, and
a device that is behind it is behind it.

### 2.4 ⚠⚠ At file-realm scale there are THREE ceilings, and speed clears none of them

§2.1–§2.3 were written from a **Timeline-heavy** realm. A file-heavy one changes the shape, and the
question that exposed it was: *how long would a phone joining a ~2 TB photo realm take, and can the
root folder be usable in seconds?*

Extrapolating ~2 TB at ~3 MB/image ≈ **700 000 files** (⚠ the file *count* is still an extrapolation;
the per-file costs below are now **measured** — C6, §11.2):

| ceiling | at 700 000 files | why speed does not fix it |
|---|---|---|
| **replay volume** | ~700 k–3.8 M blocks (1×–5.5×/entity) ⇒ **~44 min–4 h** at 16 000/min | O(history); 32× already spent |
| **eager thumbnail bytes** | ✅ **measured ~18.4 KB/file for both classes** (7.4 KB `Thumbnail` + 11.0 KB `ThumbnailLarge`) × 700 k ≈ **~12.9 GB** | the bytes must arrive before the device is complete |
| **iOS store shape** | ~3.5 M rows ⇒ 3.5 M loose files in one directory; launch index ~**7 s** (from the measured 208 ms/107 k) | O(rows) at open, before anything else runs |

⚠⚠ **`File.Thumbnail` and `File.ThumbnailLarge` are stored value properties that ride `File`'s eager
entity sync** (`file-service/.../core/file/File.kt`) — verified, not assumed. So a joiner receives
every thumbnail of every file before it is "caught up". That is the ceiling nothing in §5.1–§5.5
touches.

✅ **C6 quantified it: thumbnails are 97.26 % of every byte a `File` row costs** — a `File` without
them is ~336 B, with them ~18.4 KB (§11.2). ⚠ Two corrections in opposite directions: the byte
ceiling is **~12.9 GB, not ~21 GB** (this table previously assumed ~30 KB for `Thumbnail` alone), and
the ratio is *higher* than this section implied. ⚠⚠ But the row-count ceilings are untouched by
removing bytes, which is why C7 does not make C8 optional — §11.2's gate table.

⚠ And the iOS constraint is not negotiable: while measuring, iOS reclaimed Drive **twice** in 15
minutes of unattended running. A design that requires an hour of foreground time is not a design.

**So the target has to change**, from *complete quickly* to **usable immediately, complete
eventually** — which is §5.6 + §5.7, not more throughput.

---

## 3. What comparable systems do

Surveyed because the obvious instinct — *index the log so the joiner can skip around it* — is not
what any of them chose, and the reasons transfer.

| System | "New replica, huge history" answer |
|---|---|
| **Kafka** | **Log compaction** (`cleanup.policy=compact`): retain only the latest value per key, so reading from offset 0 of a compacted topic yields ≈current state. Kafka *does* keep `.index`/`.timeindex` files — but they are **broker-local** offset→file-position maps for seeking, never a consumer-facing selective fetch. Consumers read **sequentially on purpose**: that is what enables page-cache reads and zero-copy `sendfile`. |
| **CouchDB** | `_changes?since=` (our cursor) returns **one entry per document — the current revision**, not one per historical edit; superseded revs are compacted away. The replicator then calls **`_revs_diff`**: the *target* declares which revisions it lacks and only those transfer. |
| **Git** | `clone` does not replay commits — it ships a **packfile** (bulk, delta-compressed) negotiated by want/have. `--depth` (shallow clone) is the explicit "do not send me ancient history" lever. |
| **Cassandra / Dynamo** | **Merkle trees** locate divergent ranges in O(log n) round trips — but for **anti-entropy between two mostly-synced replicas**, not bootstrap. Bootstrap is bulk SSTable streaming. |
| **Yjs / Automerge** | **State vectors**: the client sends what it has, the peer computes the delta. Bootstrap ships a compacted document snapshot, not the op log. |
| **Matrix** (closest analogue — E2EE, large room history) | `/sync` returns **current room state** plus a *limited* timeline and a `prev_batch` token for paginating backwards **on demand**. Current state is never reconstructed by replaying all history. |
| **git (bitmap index)** | The one mainstream *producer-side index over history*: `.bitmap` reachability bitmaps that make clone/fetch negotiation fast. ⚠ **Server-side, derived and recomputed** — not commits in the DAG. |
| **Datomic** | Explicit split: the **log is truth, indexes are derived** and rebuilt. Indexes are never part of the immutable history they describe. |
| **Bitcoin / SPV light clients** | The one family that genuinely **embeds a backward-linked chain in the log** (block headers), letting a light client skip body history. ⚠ It exists because there is **no trusted server to ask** — see §4.B.3. |

**The recurring shape is snapshot/compaction + tail**, with **pull-side negotiation** where the
target declares what it needs (`_revs_diff`, want/have, state vectors). Merkle trees appear only for
divergence detection between replicas that are already mostly in sync.

**No surveyed system publishes a push-side index of its log so that readers can skip around it.** The
closest things — git's bitmaps and Datomic's indexes — are **derived and rebuildable, stored outside
the log**, precisely so they can be regenerated when the history changes shape. The one embedded
chain (SPV) exists under an assumption we do not share.

---

## 4. ⚠⚠ Rejected alternatives

> ⚠ **Scope of this section.** What follows rejects indexes as a way to make **full replay faster**.
> It does **not** cover the *usable-before-complete* requirement raised on 2026-08-05 — see §5.7,
> which answers that with a snapshot of **state** rather than an index of **history**, and §5.7.1 for
> why that distinction keeps these rejections intact rather than reversing them.

Two were proposed and both are recorded, because the second is a genuine improvement on the first
and would otherwise look unexamined. **They fail for different reasons** — A on liveness and HR #1,
B on supersession-blindness and a conflict with compaction — so refuting one does not refute the
other.

### 4.A Joiner-announce + peer-published Index Manifest — REJECTED

**The proposal.** A joining device pushes a block announcing itself; peers respond by pushing an
"Index Manifest Block" mapping block ids → service ids; the joiner uses it to fetch only the blocks
it needs. Optionally partitioned per service, or per page.

**The instinct is correct** — a joiner should not blindly read everything, and "Drive does not need
Fit's data" is a real, *measured* observation (the phone's store is dominated by health rows Drive
cannot use). **The mechanism is aimed at the wrong axis.** Four reasons, the second decisive:

**4.A.1 It does not attack the actual waste.** §2.1: ~5.5 blocks per live entity. An index says which
blocks belong to Fit; it says nothing about the ~82 % that are stale versions of rows the joiner
will overwrite regardless. The joiner would still fetch and apply every historical version of every
entity it *does* want. Compaction removes that whole class; an index removes only the fraction
belonging to services this device does not host — which leads directly to:

**4.A.2 ⚠⚠ Route-1 co-ownership leaves nothing to filter.**
[`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) P0 decided
**option C: first-party apps are realm co-owners**. Every iOS app is its own in-process proxy
hosting the whole service graph — Drive-on-iOS also hosts `:place-service`, `MapRegionService` and
health. So "give Drive only Drive's blocks" is not available **on the platform where the problem is
worst**. The per-service partition the design leans on has no one to serve there.

**4.A.3 It is relay-visible metadata we already refused to expose.** A block→service index says which
apps the user runs. `core-schema-evolution.md` rejected exactly this for the epoch vector: *"per-
service versions would leak which apps the user runs"* — E2E metadata, **not** relay-visible (HR #1).
Encrypt the index and the relay cannot filter on it, so the joiner must fetch → decrypt → issue
selective fetches: an added round trip, and the relay must still serve arbitrary ranges.

**4.A.4 It needs peer liveness and has an invalidation problem.** "Peers push the index" assumes some
other device is awake to notice the join and publish. Compaction was specified with **no election,
no quorum** (§8.3) precisely because re-emission is idempotent and convergent, so races cost
bandwidth rather than correctness. An index that must be *current* is shared mutable state that
every write invalidates; per-page indexes reduce the churn but add a level of indirection to
maintain.

### 4.B Producer-written index chain during bulk imports — REJECTED

**The proposal.** During bulk operations that create the volume (a Google Takeout import, syncing a
1 TB OneDrive), the *producer* periodically emits index blocks that **forward-index** the next blocks
belonging together semantically (one activity log, a run of videos) and **backward-reference** the
previous index block of that kind. A replaying device follows the chain to build a local index.

**This is materially better than 4.A** and kills two of its four objections outright: written at
write time by the producer, so **no peer-liveness assumption**; and immutable, describing a range
already fixed, so **no invalidation problem**. The backward link is also a genuinely new capability —
it lets a joiner learn structure **from the tip**, which nothing else here does. It is still rejected,
for three different reasons.

**4.B.1 ⚠⚠ An index groups; it cannot identify supersession.** An index block is written at time *T*
and is immutable, so it cannot know that an edit at *T+n* superseded what it points at. It partitions
the log into labelled runs; it never says "these 40 000 blocks are dead." Against the measured shape
(§2.1) that is approximately no reduction — the same objection as 4.A.1, and it survives the redesign
because it is a property of *when* the index is written, not *who* writes it.

**4.B.2 For the case it is designed for, there is nothing to skip.** Take the 1 TB import literally:
~500 k file entities, write-once, no supersession. The index correctly labels them as one group — and
the joiner still needs **all 500 k**, because that is real data, not garbage. The index has labelled
the volume, not reduced it. The only genuine reduction is *"skip groups I do not want"*, which is
4.A.2's per-service filter again: blunted on route-1, real only on web, and on web the better answer
is §6 (do not hold the log at all).

**4.B.3 ⚠⚠ It fights compaction, and the prior art says why.** Index blocks reference blocks that
**E4 compaction will delete**. An index embedded in immutable history rots exactly as the history it
describes is compacted — you would have to make compaction index-aware, tolerate dangling chains, or
exclude indexed ranges from compaction, each of which makes the shorter log harder to get.

§3 is consistent on this: **indexes over a log are conventionally derived, rebuildable and stored
outside the log** (git bitmaps, Datomic), precisely so they survive the history changing shape. The
one family that embeds a backward chain — **SPV light clients** — does so because there is *no
trusted server to ask*. **We have a relay we can ask**, which is the whole difference, and it points
straight at §5.4.

**Smaller, but real:** forward references written *before* their group **dangle if a bulk import is
interrupted** (fixable by emitting the index block after the group — but then it cannot prefetch
during the import, which was half its appeal). And feasibility: there is today **no fetch-by-block-id
route** (only `POST /sync/fetch-entity-blocks`, by entity) and **no reverse pagination** — every read
is `seq > since` ascending. Walking a backward chain needs new relay surface either way.

### 4.C What to keep from both

Two things are worth carrying forward:

- **"The joiner should say what it needs and get only that"** is right — but as **pull-side
  negotiation**, not push-side publication (which also removes the liveness requirement). Tenshu
  already has the primitive: `fetchEntityBlocks` (`POST /sync/fetch-entity-blocks`, cursor-bypassing,
  already used by the OnDemand path). What is missing is an authoritative "what entities exist now"
  list — **which is exactly what compaction produces**. The two are complements, not alternatives.
- **Partition the log at write time; do not index it afterwards.** That is Kafka topics/partitions,
  and the codebase already has a `SyncLane` concept. ⚠ Deferred, not adopted: it leaks lane
  count/activity to the relay, and §4.A.2 blunts the benefit. Revisit only when a consumer genuinely
  needs a strict subset.
- ⭐ **From 4.B: "let a joiner start from the tip."** That is the strongest idea in either proposal
  and it should be built — but the cheap form is **reverse pagination on a route we already have**
  (§5.4), not a producer-written chain. Same capability; no new block type, no dangling references,
  no crash-consistency case, and it *composes* with compaction instead of competing with it.

---

## 5. Levers, ranked

### 5.1 Write-side batching — already built, under-used

`SyncLane` (TS-1…TS-5) collapsed **1,200 saves into 3 blocks**. At 5.5 blocks per live entity the
cheapest volume win is emitting fewer blocks, not reading them more cleverly. Fit import (TS-4) and
folder upload (TS-5) already ride it; **audit which remaining bulk writers do not.**

### 5.2 Compaction (E4) — ~~the primary answer to log VOLUME~~

> ⚠⚠ **Corrected 2026-08-07 (C5, §11.8). This section's premise is wrong.** Compaction does *not*
> reduce log volume in this system: the relay never drops a superseded block for a live entity
> (`gcSweep` walks the **tombstone** index only), so re-emission adds blocks and removes none. The
> reclamation half was never built, and the two things that would make it safe were each removed
> deliberately — per-property visibility for privacy (**T-R-08**) and per-entity block addressing
> for batching (§10.2 D2, `DeltaBatchPayload` carries a null hash). E4 is **already built** and
> buys **readable-floor advancement**, a schema-evolution payoff. Read §11.8 before acting on
> anything below.

⚠ Scope, sharpened by §5.7: this is the answer to §2.1's **volume**, not to time-to-usable. §5.7 is
the answer to the latter, and §5.7.5 sets out why neither replaces the other — and why they share
their traversal.

Matches every system in §3, attacks §2.1 directly, needs no peer liveness and no relay-visible
metadata, and is **already designed** (`core-schema-evolution.md` §8.3: compaction receipts,
roster-gated floor advance, idempotent re-emission, idle+charging scheduling, never
correctness-bearing). Nothing in this note changes that design; the incident is evidence for its
priority.

### 5.3 Priority fetch for function-gating rows — cheap, do first

Closes §2.3 without touching the log at all. When the credential provider finds no valid record, the
device already knows the entitlement's DataId (derived from `ownerId`) and can fetch **that one
entity** over the existing `fetchEntityBlocks`. No new op, no wire change, no relay redeploy, no
protocol bump.

Stated generally: **a row that gates the device's own ability to function must not queue behind bulk
history.** Candidates: `Entitlement`, `Device` roster rows, revocation tombstones.

✅ **Built (C1).** `EntitlementRecovery` in `:sync-service-proxy`, fired from `SyncScheduler`.
The two open questions above resolved as:

- **Trigger = the `EntitlementBackoff` *probing boundary*** (`isBlocked && !isBackingOff()`),
  evaluated immediately **before** the push drain. That placement is load-bearing on both sides: the
  drain about to run is a probing retry, so recovering first turns a doomed probe into one that can
  succeed *in the same tick* rather than a window later.
- **Rate limit = inherited, not invented.** The existing 2 s → 2 min geometric window already paces
  those boundaries, so a genuinely unentitled device costs at most one extra request per 2 minutes
  and no new timer exists to get wrong.
- ⚠ **The predicate is the LOCAL row, not the credential provider.** It fetches only when the stored
  `Entitlement` is missing or outside its window — i.e. only when the relay could plausibly hold
  something better. If the row is valid but the *provider* is empty, fetching changes nothing (that
  was the class where a null refresh wiped a good credential); predicating on the durable copy means this never issues a request that could
  not possibly help.
- ⚠ Ingest goes through the ordinary `SyncProcessor.processSyncBlock` funnel, so **this path grants
  nothing it did not verify** — the publisher-signature check that drops a forged `Entitlement` on
  the eager drain drops it here too.

⚠⚠ **A second, latent bug had to be fixed for C1 to be worth anything.**
`EntitlementRecordCredentialProvider.update` edge-triggered on `previous == null` only, so a device
holding an **expired** record — a non-null `current`, exactly §1's state — got **no notification**
when the renewal arrived, and its backoff was left to time out on its own. The edge now fires on
absent→present *or* a strictly later `validUntil` (a renewal), and the callback is renamed
`onRecordFirstAvailable` → **`onRecordUsable`** so the name cannot mis-describe it again. Without
this, C1 could land the row and nothing would spend it.

### 5.4 ⭐ Newest-first catch-up — a reverse cursor, not an index

The cheap form of 4.B's good half, and the one lever that changes how the *incident* felt rather than
only how long it lasted.

Today every read is `seq > since`, ascending: a device rebuilding from zero sees July before it sees
yesterday. Serving the log **backwards** — a `before=` cursor, `ORDER BY seq DESC` — means a device
is useful in seconds instead of hours, because recent state arrives first. This is exactly Matrix's
`prev_batch`, and it is why Matrix never needed an index.

⚠ **It would have dissolved §1 outright.** The renewed `Entitlement` sat *near the tip*; newest-first
it arrives in the first page or two rather than 13 hours in. §5.3 fixes that one row by name;
this fixes the whole class.

Against a producer-written chain it wins on every axis that matters here: no new block type, no
dangling references, no crash-consistency case, and — decisive — **a reverse cursor over a compacted
log just works**, where an embedded index over a compacted log is stale by construction (§4.B.3).

✅ **Built (C2).** `before=` on `POST /sync/log` (optional ⇒ additive ⇒ **no
`RELAY_PROTOCOL_VERSION` bump**, exactly as §9 required) + `MailboxStore.getSyncLogBefore` on both
backends + a `GetSyncLogBefore` op + `SyncScheduler.backfillOnce`.

**The open questions resolved as "both, concurrently, with two cursors that meet."** The forward pass
walks **up** from `lastSyncTimestamp`; the new `backfillCursor` walks **down** from the tip. Every
seq is covered by exactly one of them, so the total work is the log read once — what changes is the
*order*. No flip, no "useful prefix" threshold to tune: the newest page simply arrives first.

- ⚠ **Three cursor states, and they must stay distinct.** `null` = never walked (start at the tip) ·
  `> 0` = in progress · `0` = **complete** (reached either by exhausting the log or by meeting the
  forward cursor). Collapsing `null` and `0` would either re-walk the whole log every launch or never
  walk it at all — hence a nullable store read, the `loadTombstoneCursor` precedent.
- ⚠ The floor **only ever moves down**; a relay answering with a non-descending floor stops the walk
  rather than re-requesting the same page every tick.
- ⚠ Gated on the merged lane (a relay without `/sync/log` has no `before=`) and personal-realm only
  in v1, like the `SyncLane` work.

⚠⚠ **C2 shipped with two positions and that was only half a fix — corrected same day.** The
backward walk descends and never returns, so **new** writes — which land at the tip, above
everything it has already passed — were reachable only by the forward crawl. Measured on a real
iPhone: forward cursor 104 891, backfill floor 748 414, relay tip 749 476 ⇒ a **643 523-seq gap
covered by neither pass**, and at the measured 7.5 seq/s that is ~24 h before an upload made on
another device becomes visible. *Recent state arriving first is worthless if new state still
arrives last.*

The fix is a **third position**: on the handover tick the forward pass is promoted to a **live
tail**, jumping to the top of the first backward page, and `backfillTarget` records where it had
reached so the walk knows where to stop. Together: `[0, target]` already read · `(target, floor]`
the walk is filling · `(floor₁, ∞)` the live tail's.

- ⚠ Termination compares against the **target**, never the live tail — which now sits above the
  whole unread range and would end the walk on page one, silently abandoning the history.
- ⚠⚠ **An empty page must never be adopted.** It reports `nextCursor == beforeSeq` by contract, and
  at handover that is `Long.MAX_VALUE`; jumping the live tail to it sets the forward cursor past
  every seq the relay will ever mint and the device goes **permanently deaf**. The descend-or-retire
  guard therefore runs *before* any adoption — ordering that is load-bearing, not stylistic. Caught
  by `HomeDeviceControlSystemTest` as a convergence timeout; now pinned by a unit test too.

⭐ **It is not only a reordering — it is less work.** `resolveConflictAndSave` writes **nothing** when
an incoming block is older than the row it targets (only `Manual` records a marker). Forward replay
of an entity with five superseded versions performs five writes; backward replay performs **one** and
drops four. Against the measured ~5.5 blocks per live entity that is most of the store work avoided.
⚠ The claim rests on ingest being HLC-guarded in both directions — the same property that let the
merged lane deliver kind-separated pages.

⚠ It still does **not** subsume compaction: a reverse walk traverses everything eventually. It makes
the device *useful* in seconds; C5 is what makes the traversal short.

### 5.5 Throughput — ✅ done, and it was none of the suspected causes

⚠ This section used to read *"Ingest throughput — measure, then decide … the diagnosis is
**unmeasured**. The experiment is to time one `drainAndPull` on device split by phase."* That
experiment was run on 2026-08-05 and is reported below; the hypothesis it carried (store-write
batching) was refuted.

Measured and fixed 2026-08-05; the full breakdown and the wrong prediction it replaced are in §2.2.
**~500 → ~16 000 seq/min (~32×)** via the stamp cache, coalescing the reconciliation pass
and indexing the by-bytes type-id lookup — plus no longer broadcasting Timeline's derived verdict, which removed the traffic being replayed
in the first place, and the `TombstoneRegistry` point lookup, which took launch from 10–20 s to 1 s.

⚠⚠ **Treat this section as closed and do not return to it for scale.** Every item was a constant
factor. It made the *existing* backlog drainable (a 620 k-row walk went from ~34 h to ~12 min of
real running, verified to completion) and it does not move any ceiling in §2.4.

⚠ Two levers here were considered and rejected **on measurement**, so they are not open questions:
batched store writes (writes are 3 %) and a larger `SYNC_CHANGES_PAGE_LIMIT` (the fetch is
transfer-bound, so a bigger page scales fetch time proportionally, and a longer apply holds the
route-1 cross-process flock for longer).

### 5.6 ⭐ Thumbnails off the eager path — the biggest tractable win

The single largest item in §2.4, and the one with a **direct precedent already shipped**:
`Workout.Streams` moved from an eager property to `dynamicProperty(OnDemand)` on the content plane
(`fit-workout-streams-content-plane.md`). `File.Thumbnail` / `ThumbnailLarge` are the same shape —
bytes that ride an eager entity and that most devices will never display.

Removes ~21 GB from a 700 k-file join path, and shrinks *every* block on the way, so it compounds
with §5.5 rather than competing.

⚠ It is not free: the list tier exists precisely so a row renders **without** a fetch, so moving it
on-demand trades join cost for first-scroll latency. The mitigation is the same one Drive already
uses for previews — a per-row loader with a local cache — and the OnDemand plumbing (S1–S4, S6) is
built. ⚠ Note `drive-synced-thumbnails.md` deliberately made this eager; **that decision has to be
re-opened explicitly**, not quietly reversed, and its "must appear on every device" reasoning is
exactly what §2.4 now contradicts at scale.

### 5.7 ⭐⭐ A joiner snapshot — the "root usable in seconds" answer

This is the requirement §5.1–§5.6 do not address: **be usable before being complete.**

The shape: an **encrypted tree manifest** on the content plane — path → (entityId, contentId,
thumbnail ref, size, mtime) — written by an existing device, fetched by a joiner in **one** request.
Root renders from it immediately; folder metadata is fetched as folders are opened; history backfills
behind that, or never.

⚠⚠ **This artifact is already designed.** `drive-folder-link-sharing.md` specifies an *"encrypted
manifest carrying the tree"* so an account-less link viewer can list a folder without realm access. A
joiner is the same problem with a different credential. Reusing it is far smaller than a new
protocol — and it is the same snapshot shape E4 compaction wants anyway.

#### 5.7.1 ⚠⚠ Why this is a snapshot of STATE, not an index of HISTORY — and why §4 still stands

§4 rejected two index designs. That reasoning was aimed at *making full replay faster*, where §2.1's
5.5-blocks-per-entity means the waste is supersession and an index buys little. **It does not cover
the usable-first requirement**, where you genuinely must locate a small subset without replaying — so
§4 should not be cited to dismiss this.

But the answer still is not an index over the log:

- an **index of history** tells you *where blocks are*; you must still decide which are current, so
  you are back to replaying a chain per entity;
- a **snapshot of state** tells you *what is*, in one fetch, with no chain to walk;
- ⚠ decisive, and the same objection that killed §4.B: **an index over a compacted log is stale by
  construction**, whereas a snapshot *is* what compaction produces. Snapshot composes with E4;
  an index competes with it.

Open questions: who writes it and how often (§5.7 assumes an existing device on the idle+charging
`PlatformScheduler` path, like compaction); whether it is per-realm or per-folder-subtree; and how a
joiner learns it exists before it can read the log (probably the same bootstrap that already carries
realm keys).

#### 5.7.2 A worked example (Drive) — and ⚠⚠ why it must be PER-SLICE, not one tree blob

`drive-folder-link-sharing.md` §3.1 seals **one** manifest per share version, holding the whole tree.
That is right for a share — a share is a bounded folder someone chose. It is **wrong for a realm**,
and the arithmetic says so:

> 700 000 entries × ~120 B ≈ **~84 MB** for a flat whole-realm manifest.

84 MB is a minute of transfer and parse before the first row renders — the same failure as replaying
the log, one order of magnitude smaller. ⚠ So §5.7 must not be read as "one manifest for the tree".
**One manifest per slice.** For Drive a slice is a folder, so: addressed by the folder's entity id.
⚠ Everything below is **Drive's instance** of a general shape — §5.7.3 has the unit, and the split
between envelope and projection is drawn out under the example:

```
K_snapshot      = HKDF(sharedVolumeKey, info = "tenshu/realm/snapshot/v1")
ref(slice)      = blob-<sha256("snapshot:" ‖ realmId ‖ relationId ‖ sliceKey ‖ LE64(asOfSeq))>
AAD             = (realmId, relationId, sliceKey, asOfSeq, entryCount)
```

Mirrors the share derivation (`K_manifest[v]`) with the realm's own key instead of `K_share`, and
reuses `manifestContentRef`'s existing `blob-<sha256(...)>` shape — ⚠ which the relay's `REF_PATTERN`
(`^blob-[0-9a-f]{64}$`) already enforces, and violating it is the content-addressing Phase 1 regression.

**Two layers, and keeping them apart is the point.** An earlier draft showed a single
`DirectorySnapshot` — which named the artifact after the one service it fits, and quietly contradicted
§5.7.3. The envelope is shared; **what an entry contains is the owning service's business**:

```
RelationSliceSnapshot                       // ── the envelope: identical for every service
  version      : 1                          // ⚠ a blob CAN grow, but only if it starts versioned
  asOfSeq      : 791_201                    // ⚠⚠ the relay seq this reflects — see below
  relationId   : "tenshu/file/folder/files"  // WHICH relation (§5.7.3)
  sliceKey     : "<folder entity id>"        // WHICH slice; Timeline: "2026-08"; unsliced: ""
  entryCount   : 41
  entries      : [ … service-defined projection … ]
```

```
FileSliceEntry                              // ── Drive's projection. NOT part of the envelope.
  entityId                                  // ⚠ load-bearing — see below
  name: "photo.jpg", kind: FILE, size: 321_488, contentHash, mtime
  thumbnailRef : blob-…                     // §5.6: a REF, not the bytes
  childSliceRef: blob-…                     // FOLDER only ⇒ one fetch when the user opens it
```

Timeline's would be `MomentSliceEntry { entityId, timestamp, kind, title, subject }` — **no
`childSliceRef` at all**, because `MOMENTS` is flat; hierarchy is a Drive property, not a snapshot
property. That asymmetry is exactly why `childSliceRef` sits in the projection and not the envelope.

✅ **C8a — DECIDED 2026-08-06: opaque service-sealed entries inside a typed platform envelope.**
The fork was: `entries` as **opaque bytes** the platform never parses (each service seals its own
projection — maximally evolvable, but a generic renderer cannot walk it), a **typed list per
service** (walkable, but every projection becomes a schema the frozen-set rules apply to), or
`genericData()`. Two of the three fail on constraints this codebase has already paid for.

**`genericData()` cannot hold the list.** `GenericData` carries "a primitive … **or** a composite
interchangeably" — not an array. A typed `TList` of entries hits the same wall from the other side:
⚠⚠ **a `TList` member needs a `DataIdProvider`**, and three shipped types already work around
exactly that — `SchemaFenceStatus.fencedServiceIds` (a `,`-delimited String), `RealmSchemaEpoch`'s
vector (serialized into a `byteArray`), and `TombstoneTypeCounts.counts` (`,`-delimited
`type=count`). Snapshot entries have **nothing to key on**, so a typed list would mean minting
identity for the rows of a derived cache.

⭐ **The decisive argument against a typed list is what the artifact IS.** Registering each
projection as a composite buys baseline pinning, migrations and epoch fences — machinery built to
protect *irreplaceable history*. A slice snapshot is **regenerable derived state**: the writer
re-seals it, and a missing or stale slice already degrades to an ordinary relation read (§5.7.2,
§5.7.5). Paying the frozen-set tax on something you can discard and rebuild is the wrong trade.
⚠ Note this is a **design** argument, not a migration-cost one — the distinction §5.7.4(a) insisted
on, and it lands the same way here.

⚠ **The typed list's stated benefit has no identified consumer.** "A generic renderer can walk it"
— which renderer? Control's Data section walks *live* realm data, not snapshots; and the reader of a
slice is by construction the service that owns the relation, because the point is rendering *that
app's* first screen. Compare §4.A.2, where a per-service partition was rejected for the same reason:
the beneficiary did not exist.

**So the split is explicit:**

- the **envelope** is typed, versioned and platform-owned — readable without knowing any service
  (`version`, `asOfSeq`, `relationId`, `sliceKey`, `entryCount`);
- **`entries`** is opaque bytes the owning service seals and parses, carrying **its own** version
  discriminator.

⭐ **A property that falls out and is worth keeping:** a device that cannot parse a service's entries
still reads the envelope, so it learns the slice exists and what `asOfSeq` it reflects — enough to
**skip it safely** rather than mistake it for absent.

⚠ This also preserves what §5.7.2 called the advantage of being a blob rather than a wire composite:
outside the schema system, `RelationSliceSnapshot` can grow — which is precisely why the envelope
must **start** versioned.

Root with ~20 entries is **~2.4 KB — one request**. Navigating into `/test` is one more. The 2 TB
realm never has to be materialised to be usable.

**⚠⚠ `asOfSeq` is the field that makes this a snapshot rather than a cache.** Without it a joiner
cannot know *what it still owes*: it would have a tree and no idea which part of the log it has
effectively already applied. With it, bootstrap is:

1. fetch `ref(rootId)` → render root (seconds);
2. set the forward cursor to `asOfSeq` — so the log replay that follows covers **only the delta**,
   not history;
3. backfill below `asOfSeq` lazily, or never (§5.4's reverse cursor already does exactly this, and
   `backfillTarget` is already the "stop here" mechanism);
4. fetch a child manifest per folder opened; a folder whose manifest is missing falls back to today's
   relation read, so a partial or absent snapshot degrades to current behaviour rather than failing.

⚠ **Entries carry `entityId`, and that is load-bearing.** A block for `photo.jpg` arriving after the
snapshot must be recognised as an *update to a row the device already shows*, not a second file. The
share manifest needs no such field because a link viewer never syncs — this is the one place the
joiner case genuinely diverges from the artifact it reuses.

⚠ **It is a blob, not a wire composite, and that is an advantage worth stating.** Positional
composites cannot gain a field (§9, HR #9), but a content-plane blob carries its own version, so
`RelationSliceSnapshot` can grow. It must therefore **start** with a version discriminator, or it inherits
the very problem it escapes.

⚠ Residuals, stated rather than solved: a stale snapshot renders stale names until the delta applies
(bounded by `asOfSeq`, and the same class as the share design's accepted rollback residual); a
directory rewritten often rewrites its manifest, so the writer wants the idle+charging
`PlatformScheduler` path compaction already uses; and **who writes it** is still §5.7's open question.

#### 5.7.3 ⚠⚠ "Directory" was Drive-specific — the general unit is a *sliced relation*

§5.7.2 is written in Drive's vocabulary, and that hides the real question: **what is the unit for
Timeline, Fit, Places?** There is no directory there. Verified against the code, there is also **no
`bucket` or `partition` concept** in the platform to reach for.

What does exist is the **relation** — and it comes in two shapes, which is the whole point:

| shape | examples (verified) | bounded? |
|---|---|---|
| **per-entity relation** | `Folder.FILES` (`tenshu/file/folder/files`) — one instance per folder | ✅ naturally — a folder's children |
| **realm-wide singleton relation** | `TimelineService.Relations.MOMENTS` / `TRIPS` / `TRACES` / `SNAPPED_ROUTES`; `FileService.Relations.MODIFICATIONS` / `SHARES` | ❌ one instance for the whole realm |

So §5.7.2's "manifest per directory" is really **manifest per relation *instance***, and Drive is the
easy case only because `Folder.FILES` already partitions the tree for us. ⚠ The singleton relations
are exactly where the ~84 MB problem reappears — `MOMENTS` for a five-year timeline is one unbounded
collection with **no sub-key inside the relation to slice on**.

**The slicing key therefore has to come from the owning service, not from this design.** Which is the
shape originally proposed as *"index blocks with service specific indexing"* — that instinct was
right about the *partitioning*, and §5.7.1's argument is only about snapshot-vs-index, not about who
chooses the partition:

| relation | slicing key | why |
|---|---|---|
| `Folder.FILES` | the folder itself | already an instance per folder; nothing to add |
| `MOMENTS`, `TRACES`, `TRIPS` | **time bucket** (day or month) | the feed is chronological and `homeDays()` already reads it that way, so the UI's first screen maps to one slice |
| `MODIFICATIONS` | time bucket | an append-only log; only the recent tail is ever shown |
| `Place`, `Person`, contact rows | **none — no slice** | small, bounded populations; a snapshot buys nothing a plain replay does not |

⚠ **A service that declares no slicing gets no snapshot and falls back to log replay.** That is what
keeps this opt-in and incremental: it can ship for `Folder.FILES` alone (the case that motivated it),
and Timeline can adopt it later without a protocol change. `SchemaProvider.realmDataTypeIds()` is the
natural registry for which types participate.

⚠⚠ **The consequence worth naming: the snapshot is a SET of artifacts, not one.** A joiner does not
"fetch the snapshot" — it fetches *the slices its first screen needs*: root's folder manifest, plus
today's moment bucket. That is better than a single blob, because "usable in seconds" becomes a
per-app property that each service earns independently, rather than one global artifact every service
must be in before anyone benefits.

⚠ Open, and genuinely undecided: whether the bucket boundary is fixed (calendar day/month) or
size-targeted (~N entries), and how a slice that grows past its target splits. A calendar bucket is
predictable and cache-friendly but unbounded for a heavy day; a size-targeted one is bounded but its
boundaries move, which makes `ref()` unstable. Files sidestep this entirely, which is another reason
to ship `Folder.FILES` first and learn from it.

#### 5.7.5 ⚠⚠ This does NOT replace compaction — and the two share their machinery

A snapshot of current state looks like it subsumes E4, which re-emits current state. It does not, for
four reasons — and two of them are structural rather than a matter of degree:

1. **Different problems.** Compaction reduces log *volume*, for everyone: relay storage, retention,
   and every device's backfill. A snapshot reduces *time-to-usable* for a joiner and leaves the log
   exactly as long as it was. Neither substitutes.
2. ⚠⚠ **The snapshot is opt-in per relation (§5.7.3).** Absence of the slicing trait means no
   snapshot means replay the log — so for every un-sliced relation the log *is* the bootstrap path,
   and compaction is the only lever on it. The snapshot cannot be the universal answer by
   construction.
3. **`asOfSeq` skips history; it does not drop it.** A bootstrapped device never reads below its
   `asOfSeq`, but the relay still stores that range and it still counts against retention and quota.
   Only the floor advance removes it.
4. ⚠⚠ **A snapshot discharges none of §8.3's proof obligation.** E4's actual job is to let the
   *readable floor* advance so upcasters below F can be deleted — "supported floor F, moved forward
   deliberately, with compaction as the proof obligation". Re-emission is what proves no realm still
   depends on pre-F blocks. A blob nobody replays proves nothing.

**But they are the same computation with different outputs**, and `core-schema-evolution.md` §8.3
already says so in as many words: *"The recovery blob is already this artifact in spirit — a
current-state snapshot at one version."*

| | compaction (E4) | snapshot (§5.7) |
|---|---|---|
| walks | current state | current state |
| emits | fresh **blocks** at the current version | a sealed **blob** per slice |
| scheduled | idle + charging `PlatformScheduler`, never correctness-bearing | the same |
| effect | the floor can advance | a joiner can skip to `asOfSeq` |

⚠ So **C8 builds most of the traversal E4 needs**, and sequencing the snapshot first is cheaper than
treating them as separate tracks. Whether they should literally share one pass — walk once, emit
blocks *and* seal blobs — is worth deciding when E4 is scheduled rather than now; the note is that
neither should be designed as if the other does not exist.

**⚠ One real interaction to get right.** If the floor advances past a snapshot's `asOfSeq`, the range
`[asOfSeq, floor)` is gone from the relay. A device bootstrapping from that snapshot asks for
`seq > asOfSeq` and receives blocks from the floor up — which *includes compaction's re-emissions*, so
it still converges on a complete picture. **Stale but not wrong**, and merely redundant: it
re-receives what the snapshot already told it. The cheap remedy is to re-seal snapshots whenever
compaction runs, which falls out for free if they share a trigger.

#### 5.7.4 Where the slicing key is declared, and whether the manifest is service-aware

Two refinements raised in review. The first has a verified answer; the second is a **prototype
question**, and is recorded as one rather than decided here.

**(a) A NEW trait, not a field on `Syncable`.** Slicing is per-type sync behaviour, so `Syncable` is
the intuitive home — but the baseline says no:

```
tenshu/core/trait/syncable | tenshu/sync/service | 3 | traitRequirements…, strategy…, conflictStrategy…
```

`Syncable.Schema` is a **pinned composite with 3 positional fields, owned by
`tenshu/sync/service`**, so a fourth field is a reshape: HR #9 wants a bump plus a migration, and the
bump raises the realm epoch, which fences peers until they update.

⚠⚠ **That is NOT a reason to avoid it, and an earlier draft of this section wrongly said it was.**
The cost is **one-time and pre-release**, i.e. approximately free:

- HR #9 itself: *"pre-release, a spurious wipe+resync is cheap and a silent format mix is not"*;
- `file-content-addressing.md` chose exactly this trade twice — *"No backwards compat (pre-release) →
  Ph0 reset instead of migration"*, *"pre-release reset ⇒ no migration"*.

A fence clears as soon as the fleet updates, and the fleet here is a handful of the author's own
devices which get reset before release anyway. ⚠ **Generalisable: pre-release, migration and fence
cost is not a valid argument against a schema shape.** It only becomes one post-release, when a fence
means real users cannot pull until they update.

⚠ A second overstatement to retract: the baseline pinning `Syncable` does **not** prove wire
breakage. `SchemaShapeBaseline.compositeShapes()` pins *every registered composite with fields* and
says so — *"relation/service/**trait** schemas are composites too"* — filtering only on empty
`values`. And no serialization path for a `Syncable` *instance* was found: it is read through
`traitResolver.getTrait<Syncable>(typeId, …)` (`SyncProcessor.kt:563`, `SelectImplementation.kt:277`)
and constructed in-process by module factories. The bump would be **ceremony the baseline test
demands**, not corruption it prevents.

**So the decision rests on design, not cost — and it still comes out the same way:**

1. ⚠ **Absence is the default we want.** §5.7.3 needs `Place`/`Person` to opt *out*. On a separate
   trait they do that by saying nothing; on `Syncable` every syncable type in the platform carries a
   slicing field forever, and `Syncable` is the most widely-carried trait there is. That cost is
   **permanent**, unlike the migration.
2. **Different consumer, different hot path.** `Syncable` is read per ingested block on the merge path;
   slicing is read by the snapshot writer, on the idle+charging path. Widening the trait the ingest
   path resolves, to carry a field ingest never reads, is the wrong coupling — and this note's §2.2
   is a record of what per-block costs do at scale.
3. Precedent: `RealmData` is a **separate** trait chosen over extending a base
   (`core-realm-data.md` §§1–8). ⚠ One difference: `RealmData` is *fieldless*; a slicing trait carries
   its key, so it is value-bearing like `Syncable` — a trait *with* fields, not a marker.

⚠ If (1) and (2) ever stop holding — say slicing turns out to be wanted on nearly every syncable type
— then folding it into `Syncable` is the better shape and the migration is not what should stop it.

⚠ Absence of the trait is then the "no snapshot" default, which is what §5.7.3 wanted: `Place` and
`Person` opt out by saying nothing, rather than by carrying a nullable field forever.

**(b) Service-aware manifests — and the argument is stronger than lookup cost.** Grouping slices by
owning service does make Drive's lookup narrow (it needs `file/service` and little else). But the
real prize is elsewhere:

⚠⚠ **On iOS route 1, every first-party app is its own proxy over one shared realm** — and the durable-`FileDatabase` work (iOS P4-1)
measured Drive-on-iOS reaching 58,986 objects and being SIGKILLed at launch, *"the bulk of those rows
being Fit data Drive cannot use"*. Service-grouped slices let a joining **Drive** fetch the
file-service slices its first screen needs and defer everything else, which is the one lever that
addresses that finding without changing what a device is *entitled* to hold.

⚠ Which is the caution: this is **prioritisation, not scoping.** Route-1 P0 decision **C** made
first-party apps realm **co-owners** and explicitly rejected per-app content scoping
(`ios-first-party-route-1-migration.md` §4). A Drive joiner may hold Timeline data; it simply should
not have to *materialise it first*. If service-awareness is ever read as a permission boundary it
contradicts a settled decision — so the manifest set is organised by service for **fetch order**,
and for nothing else.

**⚠ Prototype before committing to either.** Both are cheap to get wrong in ways measurement settles
in an afternoon, and this note is a record of three plausible diagnoses being overturned by one
(§2.2, §5.5, §5.7.2). Specifically worth measuring: whether a service-grouped fetch actually shortens
Drive's first screen versus one flat root slice (it may not — root is ~2.4 KB either way), and what a
realistic slice size is once `Folder.FILES` carries real folders rather than an estimate. **C8 should
land the shape with the trait, and answer (b) with a number.**

### 5.8 Merkle / state vectors — not a bootstrap mechanism, and ⚠ not what `divergenceWarning` needs

**Not** a bootstrap mechanism; do not reach for it here. That much stands.

⚠⚠ **But an earlier draft cited `SyncManager.divergenceWarning` as an "adjacent need" for it, and
that was a category error** — caused by the shared word *divergence*. Reading
`HlcDivergenceTracker` settles it:

| | what it actually is |
|---|---|
| **`divergenceWarning`** | an **adversarial relay-honesty check** (threat model T-T-04 / I6). It verifies each peer HLC tip's **signature** against that peer's device key, and flags a **regression** — a tip lower than one already seen — as the relay serving a stale view to mask later blocks. Two failure modes: `SignatureMismatch`, `Regression`. |
| **Merkle / state vectors** | **anti-entropy between honest replicas**: find *which key ranges* differ so repair can be scoped, when there is no cheap way to ask "what am I missing". |

These share nothing but a noun. `divergenceWarning` asks *is the relay lying to me*, and a signature
plus a monotonicity check answers that completely — a Merkle tree adds nothing, because the tips are
already cryptographically attributable and totally ordered.

⚠ And the general reason Merkle trees do not fit **anywhere** here: they earn their keep when there is
**no total order and no cheap cursor** — Cassandra's ring has neither. Tenshu has both: `relay_seq` is
a single total order and every device holds a cursor into it, so *"what am I missing"* is a
**subtraction**, not a tree diff. §5.4's reverse cursor is that subtraction.

⚠ `HlcDivergenceTracker`'s own KDoc also puts the replica-vs-replica case out of scope in as many
words — *"Cross-device divergence — would require comparing my tracker against another realm device's
tracker over a side channel. Out of scope."* — so even the need was misattributed.

**If a genuine anti-entropy need ever surfaces, this is where to look instead:** blocks a device
*dropped* rather than never received — below-floor drops (E2c), undecryptable payloads, and the drain
quarantine. There a device knows it is missing something but not what, which is the one shape a
cursor cannot answer. ⚠ Even then the cheap answer is a **drop log** the device already writes, not a
hash tree over state.

---

## 6. ⚠ Apex / web raises the stakes rather than changing the answer

Replay-from-zero is *least* affordable in a browser: the JS store bulk-loads resident (the
deliberate `IndexedDbObjectStore` fork, `bounded-store-cache.md`), there is no durable plaintext
cache, and a tab replaying hundreds of thousands of seq is a non-starter.

This strengthens §5.2 rather than motivating §4. It may additionally call for the **Matrix shape** —
current state plus lazy backfill, i.e. **web does not hold the full log at all**. That is a real
open design question, but it is about *what the web client stores*, not about indexing the relay's
log, and it should be opened separately (likely against [`docs/web/architecture.md`](../web/architecture.md)).

---

## 7. The trigger is already mostly closed

This replay only happened because a core-version bump **wiped** the store. Schema-evolution E2j-2
made the store gate migrate-if-reachable, and registering an empty
`schemaMigration(CORE_SERVICE_ID, fromVersion = <previous>)` alongside a core bump keeps the recorded
version inside `[floor, current]` so the store **migrates instead of wiping** (House Rule #9).

So the 13-hour scenario should be rare going forward. That is an argument for doing §5.3 (cheap,
removes the sharp edge) and measuring §5.4 — **not** for pulling E4 forward on the strength of this
one incident.

---

## 8. Phasing

| Phase | Scope | Gate |
|---|---|---|
| ~~**C1**~~ **SHIPPED** | §5.3 priority fetch for the entitlement, over the existing `fetchEntityBlocks` | ✅ a missing/expired row is fetched at the backoff probing boundary; a *valid* one never is; no relay, no owner, and a failed call each decline cleanly. Plus the `onRecordUsable` renewal edge, without which the fetch would land unspent |
| ~~**C2**~~ **SHIPPED** | §5.4 newest-first catch-up — an additive `before=` reverse cursor, two cursors meeting in the middle | ✅ the walk starts at the tip, only descends, and terminates **both** ways (exhausted log · met the forward cursor); forward + backward provably partition the log rather than overlapping or gapping; the forward pass is unchanged |
| ~~**C3**~~ **SHIPPED** | §5.5 measure `drainAndPull` on device, split by phase; act only on the result | ✅ measured (§2.2); the store-write hypothesis was **refuted** (3 %) and the two real costs — a per-block stamp re-parse and a linear type-id scan — were fixed. ~500 → ~16 000 seq/min |
| ~~**C4**~~ **DONE** | §5.1 audit bulk writers not yet on `SyncLane` | ✅ audited (§11.7). One path fixed — **Timeline's Takeout file import**, the *larger* of the two bulk imports and never wrapped while Fit's was; the lifecycle became a shared `withBulkWriteTask`. ⚠⚠ The measurement found the harness had been dropping the lane id, so TS-4/TS-5 batching was off in every system test: **24 writes → 24 blocks** before, **1** after. ⚠ Not fixed, with reasons: proxy-internal ops (no seam), shared-realm writes (lanes are personal-only), and bulk **deletes** (tombstones have no lane at all) |
| ~~**C5**~~ **INVESTIGATED — closed, not shipped** | §5.2 E4 compaction | ⚠⚠ **E4 is already built** (§8.3.1–§8.3.7, 2026-08-02) except one DI line, which must stay absent while every registered chain is empty. ⚠⚠ And **compaction does not reduce log volume**: the relay drops superseded blocks only for *tombstoned* entities, and an entity-keyed supersede rule is blocked by T-R-08 (`propertyId` is inside the ciphertext) and by lane batching (null hash). Full working + recommendation in §11.8. The volume question becomes a separate relay-side design owing a protocol bump + threat-model amendment |
| ~~**C6**~~ **DONE** | §10 census a realm: block + byte share by type, thumbnail fraction | ✅ **thumbnails = 97.26 % of `File` bytes** (~18.4 KB/file vs ~336 B without). ⚠⚠ Decided **against** the hypothesis: C7 alone does *not* suffice, because the other two ceilings are counts and C7 moves no count — **C8 stays a prerequisite**. ⭐ Needed no probe and no device run; `objects(hash,type,data)` is the census (§11.2) |
| ~~**C7**~~ **DONE** | §5.6 `File.Thumbnail`/`ThumbnailLarge` → OnDemand on the content plane | ✅ the join path loses ~97 % of every `File` row's bytes; `SyncedThumbnailSystemTest` proves a joiner fetches + renders one without the file's bytes; `drive-synced-thumbnails.md` §11 amended (D1 + D2 reversed). ⚠ first-view latency is **not yet measured on device** — the one gate left open |
| ~~**C8a**~~ **DECIDED** | §5.7.2 decide the entries fork — opaque service bytes vs a typed per-service list vs `genericData()` | ✅ **opaque service-sealed entries, typed platform envelope.** The envelope is readable without knowing any service, so an unparseable slice is *skippable* rather than absent; entries carry their own version. Reasoning in §5.7.2 |
| **C8** | §5.7 joiner snapshot — one `RelationSliceSnapshot` per **relation slice** (§5.7.3), starting with `Folder.FILES` because it is already bounded (§5.7.2 works the shape) | a joining device lists root from **one** request holding no history; `asOfSeq` sets the forward cursor so replay covers only the delta; a missing manifest degrades to today's relation read |
| ~~**C8b**~~ **CLOSED** | §5.7.4(b) measure whether service-grouped slices shorten Drive's first screen vs one flat root slice | ✅ **moot by construction** (§11.9): C8 built **no manifest** — a slice's address is *derived* (`sha256("snapshot:realm:relation:sliceKey")`), so there is nothing to group. The lever it stood for is real but is **DI, not wire**: a joiner fetches exactly the producers its runtime registers. ⚠⚠ Currently wired the wrong way (one shared list ⇒ a Drive-on-iOS joiner fetches Timeline slices), but sized at **two blob GETs** — recorded with its trigger rather than restructured |
| **C9** | §5.7.3 extend to a realm-wide singleton relation — `MOMENTS`, time-bucketed — once C8 has settled the bucket-boundary question | Timeline's first screen renders from one slice; no protocol change vs C8 |
| — | §6 web state-vs-log — open separately against the web architecture | — |

⚠ **C6 before C7/C8.** Both are sized from an extrapolation (§2.4), and this whole note is a record
of measurement overturning plausible diagnoses three times — including one in its own §2.2.

---

## 9. House rules & threat model

- **HR #1** is a *reason* here, not a constraint to work around: §4.A.3 is why that index is rejected.
- **HR #9** untouched by C1–C3 (no stored-row or wire reshape). C4 carries its own analysis.
- **No relay redeploy** for C1; it uses a route that already exists.
- ✅ **C2 landed additive, as required.** `before=` is an optional query parameter an old client
  never sends, so the forward path is byte-identical and **no `RELAY_PROTOCOL_VERSION` bump** is owed
  (`RelayProtocol.kt`, normative). It does need a redeploy for the surface to exist — uncoordinated,
  and the same one `/sync/log` is already waiting on. ⚠ The client op is a **new id**
  (`GetSyncLogBefore`), not a widened `GetSyncLog`: callstack params are positional composites, so
  adding one would have garbled peers and cost a format bump. `SchemaShapeBaselineEnforcementTest`
  confirmed the result — *1 new type, 0 changed shapes*.
- ⚠ The three reserved cursor rows (`SYNC_CURSOR_HASH`, `TOMBSTONE_CURSOR_HASH`,
  `BACKFILL_CURSOR_HASH`) are raw `DbEntry`s with their own type strings, **not registered schema
  composites**, so the baseline never sees them and adding one reshapes nothing.
- **Threat model:** no new asset. C1 narrows a window in which a device cannot write — availability,
  not confidentiality. If §4.C's write-time partitioning is ever revisited, it **does** owe an
  amendment (lane count/activity becomes relay-visible).

---

## 10. ⚠ What is extrapolated, and what to measure before building §5.6/§5.7

Stated explicitly because §2.4's table is the basis for C7 and C8 and is **not** a measurement:

| claim | basis |
|---|---|
| `File.Thumbnail` / `ThumbnailLarge` ride `File`'s eager entity sync | ✅ **verified** in `file-service/.../core/file/File.kt` |
| **thumbnails = 97.26 % of `File`-row bytes; ~18.4 KB/file, ~336 B without** | ✅ **measured** — C6, per-property attribution over a real store (§11.2) |
| ~5.5 blocks per live entity | ✅ measured — but on a **Timeline-heavy** realm (§2.1) |
| ~16 000 seq/min ingest | ✅ measured on the phone, 2026-08-05 |
| iOS launch index 208 ms / 107 k rows | ✅ measured |
| ~700 000 files for 2 TB | ⚠⚠ **still extrapolated** — C6's realm holds 37 files |
| ~~~21 GB of thumbnails~~ → **~12.9 GB** | ✅ measured per-file cost × the extrapolated count |
| **blocks per *file* entity (supersession)** | ⚠⚠ **unmeasured, and now the load-bearing unknown** |

✅ **C6 is done (§11.2)** and it replaced most of this table without a probe or a device run — the
`objects` table is itself the census. It resolved the C7-vs-C8 gate **against** the hypothesis this
section carried: thumbnails do dominate the bytes (97.26 %), and C7 alone still does **not** reach
"usable in seconds", because the replay-volume and iOS-row ceilings are **counts** and C7 moves no
count. C8 stays a prerequisite.

⚠ **What C6 could not measure, by construction:** the store holds *current state*, so supersession —
blocks per live `File` — is invisible to it. That is the one number that could still move the plan (1×
⇒ ~44 min post-C7, 5.5× ⇒ ~4 h), and getting it is what the §11.2 ingest probe is for.

---

---

## 11. Implementation Notes for Claude Code

⚠ **This is the canonical handoff section** (project convention). §1–§10 are architectural context;
what follows is what a fresh session needs to start. **Nothing in C6–C9 is built.**

### 11.1 Order, and why it is this order

| # | Phase | Why here |
|---|---|---|
| ~~1~~ | ~~**C6** census a file-heavy realm~~ ✅ **DONE 2026-08-05** | Answered §11.2, and **against** the hypothesis: thumbnails are **97.26 %** of `File` bytes, but the other two ceilings are counts, so **C8 stays a prerequisite**. ⚠ Needed no probe and no device run — the `objects` table is the census. |
| ~~2~~ | ~~**C7** thumbnails → OnDemand~~ ✅ **DONE 2026-08-06** | Both classes are `dynamicProperty(Syncable(OnDemand))` on the content plane; `FileService` v2. ⚠⚠ Two structural findings the plan missed — a per-property `contentId` is mandatory, and the cold read is two-phase (§11.3). |
| ~~3~~ | ~~**C8a** decide the entries fork~~ ✅ **DECIDED 2026-08-06** | **Opaque service-sealed entries in a typed platform envelope** (§5.7.2). `genericData` cannot hold an array and a `TList` member needs a `DataIdProvider` (three shipped workarounds); and a *regenerable* artifact must not pay the frozen-set tax built for irreplaceable history. |
| ~~4~~ | ~~**C8** snapshot for `Folder.FILES`~~ ✅ **DONE 2026-08-06** | The "root in seconds" answer. Trait + envelope + Drive's producer/projection + writer + reader + the idle-charging schedule; as built in §11.4.1. ⚠⚠ It corrected §11.4's reader step — the cursors stay C2's alone, or a per-slice snapshot strands everything it did not carry. |
| ~~5~~ | ~~**C8b** measure service-grouped vs flat~~ ✅ **CLOSED 2026-08-07 (§11.9)** — moot by construction: there is no manifest to group; earlier, ⏭️ **FOLDED INTO C9 2026-08-06** | ⚠⚠ Unmeasurable with one producer — both layouts are byte-identical until a second service registers one. Measured against the real store instead (§11.4.2), which restated §5.7.4(b)'s premise (bookkeeping is 75.7 % of rows, at ~3.2 per entity) and surfaced that **C7 covers 1.7 % of this device's `File` rows** — existing rows keep their thumbnails forever. |
| ~~6~~ | ~~**C9** first singleton relation (`MOMENTS`, time-bucketed)~~ ✅ **DONE 2026-08-06** | `Sliceable(SliceKey.Day)` on `Moment` + `MomentSliceEntry` + `MomentSliceSnapshotProducer`; as built in §11.4.3. ⚠⚠ Key is **UTC**, feed renders local ⇒ the seed is today *and* yesterday; ⚠⚠ truncation is the only honest `null` — ⚠ and since §11.4.4 it covers the window's oldest day too; every slice is under a byte budget. |
| ~~7~~ | ~~**C4** bulk-writer audit~~ ✅ **DONE 2026-08-07** | Every bulk writer classified (§11.7). Timeline's file import wrapped; `withBulkWriteTask` extracted from its third copy. ⚠⚠ Its counting test found TS-4/TS-5 batching was **inert in the harness** — a hand-rolled `CancellableLocalTaskExecutor` overrode the one-arg `executeAsyncCancellable` but not the two-arg one, whose default drops the taskId. The convergence-only tests could never have caught it. |
| ~~8~~ | ~~**C5** E4 compaction~~ ✅ **INVESTIGATED 2026-08-07 — closed without shipping** | E4 was already built; its remaining DI line must stay absent until a non-empty chain exists. ⚠⚠ And its *volume* payoff does not exist in this system — nothing drops a superseded block for a live entity, and the fix is blocked by T-R-08 + null-hashed batch blocks. §11.8. |

### 11.2 ~~C6 — the census~~ ✅ **DONE 2026-08-05 — and it needed no probe and no device run**

The plan below was to instrument `SyncProcessor.resolveConflictAndSave` (the ingest-census technique) and run
it on a file-heavy realm. **That was not necessary**, and the reason generalises:

⭐ **The `objects` table already is the census.** `SqliteObjectStore`'s schema is
`objects(hash INTEGER, type TEXT, data BLOB)` — so per-type row *and byte* share is one `GROUP BY`
against a **copy of the live desktop store**, with no code change, no log spam, and no diagnostic that
could be merged by accident. Going one level deeper — decoding the blob with the format
`DbEntryCodec.kt` documents in its header comment (~60 lines of Python) — attributes bytes **per
property key**, which is strictly better than the histogram the probe would have produced: it names
`tenshu/file/thumbnail` directly instead of inferring it from a size gap.

⚠ Copy `objects.db`, `-wal` **and** `-shm`. The WAL was **206 MB against a 21 MB db** — reading the
db alone would have censused a small fraction of the store and looked plausible doing it.

**What it measured** (`~/.tenshu/app.tenshu.control.desktop`, 111,391 rows / 12,131,043 B):

| type | rows | bytes | share |
|---|---:|---:|---:|
| `tenshu/core/entity/dataId/string` | 29,040 | 2,814,986 | 23.20 % |
| `tenshu/core/time/hlc` | 25,094 | 2,484,306 | 20.48 % |
| `tenshu/sync/block/delete` | 10,523 | 2,156,955 | 17.78 % |
| `tenshu/sync/resolvedDataId` | 21,539 | 1,508,253 | 12.43 % |
| `tenshu/sync/metadata` | 14,514 | 958,223 | 7.90 % |
| `tenshu/health/workout` | 3,005 | 492,916 | 4.06 % |
| **`tenshu/file`** | **37** | **454,063** | **3.74 %** |

#### ⭐⭐ The number C6 existed to produce

Per-property attribution over the 37 `tenshu/file` rows:

| property | rows present | bytes | share of `File` |
|---|---:|---:|---:|
| `tenshu/file/thumbnailLarge` | 24 | 263,307 | **57.99 %** |
| `tenshu/file/thumbnail` | 24 | 178,313 | **39.27 %** |
| `contentHash` + `contentId` + `lastModifiedAt` + `createdAt` + `size` | 37 | 9,768 | 2.15 % |
| (entry header + positional fields) | 37 | 2,675 | 0.59 % |

> **Thumbnails are 97.26 % of every byte a `File` row costs** (441,620 of 454,063 B).
> A `File` without them is **~336 B**; with them, **~18.4 KB**.

The raw size distribution is sharply bimodal and needs no interpretation — 13 rows at 317–333 B, 24
rows at 4.6 KB–69.7 KB. §2.4's "verified, not assumed" claim that the thumbnails ride the eager entity
is now not merely verified but **quantified**.

#### ⚠ Two corrections to §2.4 / §10, in opposite directions

- **§2.4 over-estimated the bytes.** It assumed `File.Thumbnail` ≈ 30 KB *plus* `ThumbnailLarge`.
  Measured: **~18.4 KB for both together** (7.4 KB + 11.0 KB). The 700 k-file thumbnail ceiling is
  **~12.9 GB, not ~21 GB** — still the largest single item in this note, and still unpayable on a
  phone.
- **§2.4 under-stated the ratio.** 97.26 % is higher than the section implies. ⚠ And only **65 %** of
  files carry thumbnails at all (24 of 37) — so the per-*thumbnailed*-file cost is 18.4 KB while the
  per-file average is 11.9 KB. A realm of photos sits at the former.

#### ⚠⚠ The C7-vs-C8 gate resolves **against** §10's hypothesis — C8 is NOT optional

§10 floated that "if thumbnails dominate as predicted, C7 alone may reach *usable in seconds*". They
do dominate, and it still does not follow — because the two remaining ceilings are **counts, not
bytes**, and C7 moves no count:

| | before C7 | after C7 |
|---|---|---|
| bytes, 700 k files | ~12.9 GB | **~235 MB** ✅ |
| blocks to apply | ~700 k (≥1× supersession) | **~700 k — unchanged** |
| time at the measured 16 000 seq/min | transfer-bound, hours | **~44 min, count-bound** |
| iOS store rows | ~3.5 M | **~3.5 M — unchanged** |

**~44 minutes is not seconds.** C7 converts an impossible join into a slow one; only C8 makes the
device *usable* before it is complete. ⇒ **build both, in the stated order**, and C8's status as a
prerequisite is now measured rather than assumed.

⚠ The 44 min floor assumes files supersede ~1×. At §2.1's realm-wide 5.5× it is ~4 h. Files are
mostly write-once, so the truth is nearer 1× — but this is the **one input C6 could not measure**
(the store holds current state; supersession lives in the log). If C8 ever looks skippable, that is
the number to go get, and *then* the ingest probe below is the right tool.

#### Other findings worth carrying

- ⚠⚠ **81.8 % of the store is sync bookkeeping, not user data** — `dataId/string` + `hlc` +
  `resolvedDataId` + `metadata` + `block/delete`. That is the §2.4 iOS one-file-per-row ceiling's real
  driver: of 111,391 rows, ~91 k are bookkeeping. A file-realm projection that counts only `File`
  rows under-counts the row population by ~5×.
- ✅ **§5.7.3's singleton-relation model is confirmed on real data.** Every large `tenshu/core/list`
  row's member count matches a type population exactly — 3,005 = `workout`, 2,828 = `dailyActivity`,
  591 = `error-event`, 198 = `moment`. These are literally one row per relation whose size grows with
  the population, which is precisely the artifact §5.7.3 says needs a service-chosen slicing key.
- ⚠ The largest single row in the store is **277,803 B** — the legacy `RECEIVED_TOMBSTONES` array.
  See §11.2.1: chasing it found a live bug.

#### If a true ingest census is ever needed anyway

The store census cannot see supersession or attribute blocks to an emitting device. For that, the
ingest-census probe is still the tool: instrument `resolveConflictAndSave`, bucket by
`(hlc.deviceId, entityType, outcome)`, dump every N.

⚠ **Diagnostic, never merged.** One ingest-census probe once shipped to master by accident (removed minutes later) because staging used
directory paths. Stage explicit **files**, and `grep -rl <PROBE_NAME>` against `origin/master` after
merging.

⚠⚠ **But reach for the store census first.** It is faster, it is exact, it carries no merge risk, and
it answered the question the probe was specified for. The general lesson — *the schema of the thing
you want to measure may already be a table you can `GROUP BY`* — is the §11.6 habit applied one step
earlier.

### 11.2.1 ✅ **FIXED** — a live bug the census found by accident: resurrection protection was off for 10,522 entities

Not a catch-up finding; it fell out of asking why one row was 277 KB. **Recorded here because it is a
silent data-integrity bug on the maintainer's own devices**, and because how it hides is instructive.

**The evidence, in the order it appeared:**

1. The biggest row in the store is a `tenshu/core/list` with **30,863 members**, all
   `tenshu/sync/block/delete` — the legacy `RECEIVED_TOMBSTONES` array (per-key `PersistedTombstone` rows
   replace it).
2. Those 30,863 slots resolve to only **10,524 distinct hashes** — 9,816 appear exactly 3×, 707
   exactly 2×, one once. ⚠ Historical residue from the legacy append path (which did not dedupe
   across ingesting devices; the maintainer has three). Nothing appends to it now, so it cannot grow.
3. ⚠⚠ **Three members are absent from the store entirely** — dangling.
4. The store holds **one** `PersistedTombstone` row, and a `TombstoneTypeCounts` marker reading
   `tenshu/file=1`.

(4) is impossible if the fold ran. It did not, and it never will:

```kotlin
// TombstoneRegistry.ensureMigrated()
foldLegacyArray()    // ⚠ `getArrayData` throws on the dangling member → caught → `?: return`
writeTypeCounts()    // ⚠⚠ latches the "fold complete" marker anyway
migrated = true
```

`foldLegacyArray`'s 2026-08-04 defence-in-depth (*"a member it cannot read is worth strictly less than
the session it would kill"*) returns **normally** on degradation, so `ensureMigrated` writes the
completion marker over a fold that did nothing. Every later launch short-circuits at
`loadTypeCounts() != null`. ⚠ The KDoc asserts the opposite invariant — *"written **last**: a fold
interrupted halfway leaves the marker absent, so the next call redoes it"* — which is true for a
**crash** mid-fold and false for the degradation path added beside it.

**Consequence.** `tombstoneHlcFor` reads the in-memory map (empty at launch) then the per-key row —
so it returns `null` for 10,522 tombstoned entities, `resolveConflictAndSave`'s short-circuit never
fires, `existingData == null` falls straight through to `saveData`, and **a re-pulled create
resurrects a deleted entity**. That is exactly the hazard the class KDoc says it exists to prevent.
`SyncManager.currentRealmEpoch` reads the same counts (`tombstoneCountForType(Device.ID)` → 0), so
T-K-02 stale-device-block rejection is judging on a count that was never folded.

⚠⚠ **C2 turns this from latent into active, and C2 shipped today.** The hazard needs an *old* create
block to be re-delivered — historically a rare relay echo. §5.4's newest-first backfill is a machine
for exactly that: it walks the entire log **backwards from the tip**, so every create block for every
entity ever deleted gets re-offered to `resolveConflictAndSave`, against a registry that has forgotten
10,522 of its tombstones. ⚠ The interaction is not C2's fault — C2 is correct against a working
registry, and §5.4 rests explicitly on ingest being HLC-guarded — but it means **the fix is due before
the backfill finishes its walk**, not whenever C7–C9 are done.

#### ✅ As built

⚠ **The fix is not "remove the degradation"** — that guard was added because a dangling member bricked
a live proxy, and the registry is rebuildable state, so a member it cannot read really is worth less
than the session it would kill. The fix is to make *degrade* mean **skip that member**, not **abandon
the fold**. Three parts:

1. **The fold reads in windows and degrades per member.** `Database.getArrayMemberHashes` returns the
   member hashes with no deserialisation, so the fold windows over them (64 at a time) via
   `getArrayDataWindow`, which slices before deserialising. A window that throws is retried
   **one member at a time**, so a dangling member costs exactly one tombstone. ⚠ The happy path is
   ~165 point reads for a 10 k array rather than 10 k. A store that cannot report member hashes
   (`null` = *unknown*, never *empty*) falls back to the previous whole-array read.
2. ⚠⚠ **The marker key moved to v2** (`TombstoneTypeCounts.SINGLETON_KEY`). This is the part that
   matters for devices already in the bad state: a fix to the fold alone can never reach them,
   because they never call it again. The v1 row is left in place as inert bytes — ~95 B — and is
   still *read*, for (3).
3. **Counts merge with `max` against the v1 row.** A re-fold recomputes counts from the array, which
   cannot rediscover a tombstone recorded live *after* the array stopped growing — exactly the
   `tenshu/file=1` in the observed marker. Merging forward with `max` means
   `SyncManager.currentRealmEpoch` can never step backwards across the heal. ⚠ Relatedly the fold now
   counts **distinct entities it observes** rather than "entities that had no per-key row yet"; the
   old predicate under-counted on any re-run, which is precisely what a healing fold is.

**Tests** (`TombstoneRegistryTest`) — ⚠ both verified to **fail against the pre-fix registry**, which
is the only thing that makes them worth having: `oneDanglingMemberCostsOneTombstone_notTheWholeFold`
(a readable tombstone on *either side* of the dangling member survives) and
`aDeviceThatLatchedTheBrokenMarkerStillHeals` (the v1 marker does not suppress the fold, and its
counts carry forward). ⚠ HR #9 untriggered — `TombstoneTypeCounts` is local-only and never
serialised, and only a *key value* changed, not a positional shape; `SchemaShapeBaselineEnforcementTest`
confirms 0 changed shapes.

#### ⚠ Deliberately not done

- **The dangling members themselves.** `RetiredBlockSweeper` keeps `RECEIVED_TOMBSTONES` as a GC root
  precisely so its members are never swept, so three going missing means either the root does not
  cover what it should or they predate it. The fold no longer *cares* — but the root cause is
  unexplained, and it is the same "orphans were outbound" territory as orphan-GC G1/G3.
- **Deleting the legacy array after a clean fold.** Tempting: it would reclaim the 277 KB row **and**
  let the sweeper collect the 10,523 `DeleteBlock` rows it roots — together **~20 % of the store** —
  and it would make "array absent" a self-evident completion marker needing no key at all. Left out
  because it is destructive, and the fix above is the part that is urgent.

⚠ Sized separately from C6–C9; it blocks none of them, and none of them would have fixed it.

### 11.3 ~~C7 — thumbnails off the eager path~~ ✅ **DONE 2026-08-06**

**Move:** `file-service/src/commonMain/kotlin/org/tenshu/core/file/File.kt` — `fileThumbnail` (:295)
and `fileThumbnailLarge` (:317), both `Property.Value<TByteArray>` today, to
`dynamicProperty(Syncable(OnDemand))` on the content plane.

**Copy the precedent exactly** (`fit-workout-streams-content-plane.md`):
`health-service/.../health/Workout.kt` + `GetWorkoutStreams.kt` +
`health-service-client/.../impl/GetWorkoutStreamsRo.kt`.

⚠⚠ **Two traps that precedent already paid for:**

- **`operation(Schema)` in the common module is NOT enough.** The dynamic-property template dispatches
  through the **client** module's `roFactory` registration. Without a `GetFileThumbnailRo`-equivalent,
  every select projecting the property dies as an opaque `error/internal`.
- **Only `Maybe`/stream terminals materialise `PendingOnDemandProperty`; `getAll()` does not.** Fit's
  detail read had to move to `search → select → get()`. Drive's list rows read thumbnails per row, so
  check every call site rather than assuming.

⚠ **HR #9:** this reshapes `File`, so bump `service(tenshu/file/service, version = N+1)` and register
the migration step. Pre-release that cost is ~free — see
`memory/feedback_prerelease_migration_cost_is_not_an_argument`; do **not** let it steer the design.
`SchemaShapeBaselineEnforcementTest` names the moved field; regenerate the baseline after the bump.

⚠ **The regression to watch is first-scroll latency, not join time.** The eager tier exists so a row
renders *without* a fetch. Measure a cold folder scroll before and after; the mitigation is the
per-row loader Drive already uses for previews. And amend `drive-synced-thumbnails.md` — it made this
eager deliberately, so the decision is re-opened, not reversed silently.


#### ✅ As built — and ⚠⚠ the two traps that mattered were NOT the two listed below

Both predicted traps were real and both were paid: the client-module `roFactory`
registration exists (`GetStoredThumbnailRo`), and the `getAll()` limitation is why Drive's
paging window still cannot carry these. Neither was the hard part.

**(1) ⚠⚠ A thumbnail needs its OWN `contentId`.** `manifestContentRef(contentId)` keys the
manifest's relay address on the content id **alone**, and a `File` has exactly one — so
`Data`, `Thumbnail` and `ThumbnailLarge` would each PUT their manifest to the *same ref* and
overwrite one another. The seam turned out to exist already:
`ExternalPropertyWriter.resolveContentId(ctx, entityId, **propertyId**)` is per-property, so
`FileBytesPropertyWriter` derives `sha256("thumbnail:$contentId:$class")` and the whole
existing pipeline works untouched — no change to that shipped derivation (violating its
`REF_PATTERN` is the content-addressing Phase 1 regression), and the chunk AAD is domain-separated for free since
`fileChunkAad` binds whatever id the emit is handed.
⚠ `resolveContentHash` must return **null** for a thumbnail: `File.ContentHash` anchors the
*file's* bytes, and stamping it on a thumbnail manifest makes the integrity gate reject every
thumbnail fetched.

**(2) ⚠⚠ The cold read is two-phase, and one-shot `eager` is not enough.**
`GetPreview` reads the thumbnail **locally** — it is a proxy-side resolver reached through
`resolveDynamicProperty`, which is **not suspend**, so it cannot await a fetch no matter what
traits the op carries. The OnDemand fetch happens only at a **suspend terminal**
materialising the pending markers a projection left behind. Consequence: projecting
`File.Preview` alone on a cold device resolves to `null` **forever**.
So a caller projects the OnDemand properties first (`relations(File.Thumbnail,
File.ThumbnailLarge)` through a `Maybe.get()`), then reads the preview — two reads, because
within one select `File.Preview` resolves before the terminal materialises anything.
⚠ The class policy stays inside `GetPreview`; the caching step only fills the local cache and
re-asks, so §10.1's "single best-available preview API" invariant survives.
⚠ `SyncedThumbnailSystemTest` pins the new contract — it timed out against the old one, which
is how the gap was found rather than shipped.

**Also as built:** no `emitPropertyBlock` fallback on the thumbnail emit (that block is the
eager payload C7 removes); blobs keyed by `contentId` under `/.thumbnails/…` so a move stays
pure metadata; `FileService` **v2** + an empty migration — ⚠ the baseline reports **0 changed
shapes**, because a value property lives in the entry's string-keyed property map, not the
positional field list, so the bump is owed for the *sync-semantics* change, not a reshape.

⚠ **Still open: first-view latency is not measured on device.** The regression to watch is
unchanged, and the cold path now costs an extra round trip by construction.

### 11.4 C8 — the snapshot

**New slicing trait** in `core/src/commonMain/kotlin/org/tenshu/core/trait/` beside `AppendOnly.kt`,
`MeteredExempt.kt` etc. ⚠ A **new** trait, not a field on `Syncable` — §5.7.4(a) for why (design
reasons, not migration cost). Value-bearing (it carries the slicing key), so unlike `RealmData` it is
not a marker.

**Envelope + projection:** §5.7.2, and ✅ **C8a is decided** — a **typed, versioned, platform-owned
envelope** (`version`, `asOfSeq`, `relationId`, `sliceKey`, `entryCount`) around **opaque
service-sealed `entries`** carrying their own version discriminator. Do not model entries as a
registered composite list: `genericData` cannot hold an array, a `TList` member needs a
`DataIdProvider` (`SchemaFenceStatus` / `RealmSchemaEpoch` / `TombstoneTypeCounts` all work around
exactly that), and a regenerable artifact must not pay the frozen-set tax. Reasoning in §5.7.2.

⚠ Keep the envelope parseable **without** the owning service: a device that cannot read a service's
entries must still learn the slice exists and its `asOfSeq`, so it can skip it deliberately rather
than treat it as missing.

`ref()` must satisfy `ContentRouting.REF_PATTERN`
(`^blob-[0-9a-f]{64}$`, enforced on PUT **and** GET) — copy the shape of `manifestContentRef`
(`sync-service-proxy/.../FileContentChunking.kt:70`). ⚠ Violating it is the content-addressing Phase 1 regression and only
`:system-test-utils` caught it.

⚠⚠ **C7 paid a lesson that applies directly here:** two content-plane artifacts belonging to the same
entity need **distinct content ids**, because the manifest's relay address derives from the content id
alone. A per-slice `ref()` already encodes `(realmId, relationId, sliceKey, asOfSeq)`, so this is
satisfied by construction — but check it rather than assume it, since the failure is silent
overwriting, not an error.

**Writer:** the idle+charging `PlatformScheduler` path compaction will use. **Reader:** bootstrap in
`SyncScheduler` — fetch root's slice, then set `lastSyncTimestamp` to `asOfSeq` so replay covers only
the delta. ⚠ `backfillCursor`/`backfillTarget` (C2) already are the "stop here" mechanism; reuse them
rather than adding a third cursor.

> ⚠⚠ **CORRECTED as built (2026-08-06): the reader must NOT set `lastSyncTimestamp` to `asOfSeq`.**
> The paragraph above predates C2's live-tail handover, and against the cursor machinery as it
> actually shipped the jump silently strands data. `backfillOnce` captures `backfillTarget` **from
> `lastSyncTimestamp` at handover** and stops the backward walk there. A reader that raised the
> forward cursor to `asOfSeq` first would therefore make the walk stop at `asOfSeq` — declaring
> everything below it applied, when a **per-slice** snapshot covered exactly one slice. Every row
> outside the snapshot, below that seq, would be unreachable forever.
>
> The step was safe only under §5.7.2's original reading of one manifest for the whole tree; §5.7.2
> itself then argued the snapshot must be per-slice, and this consequence was not carried through.
>
> What the snapshot does instead is the half worth having: **content now**. C2 already makes a
> joiner live for new writes within a tick of its first backward page, and the walk still fills
> history underneath, so nothing is lost by leaving the cursors alone. `asOfSeq` keeps its other
> job — it is the reader's evidence of *what* it got, and the writer's promise gate (§11.4's
> `isCaughtUp`). Pinned by `SliceSnapshotReaderTest.theCursorsAreLeftEntirelyToC2`.

⚠ **Degrade, never fail:** a missing or stale slice must fall back to today's relation read. §5.7.5
shows a snapshot older than the compaction floor is *stale but not wrong*.

#### 11.4.1 As built (2026-08-06)

Four units, each independently inert until the next lands — no format, wire or relay change, and
`SchemaShapeBaselineEnforcementTest` stays green (a new op id reshapes no composite, so HR #9 is
untriggered).

| unit | where |
|---|---|
| trait + envelope | `core/.../trait/Sliceable.kt`, `sync-service-common/.../RelationSliceSnapshot.kt` |
| Drive's projection + producer | `file-service-common/.../slice/FileSliceEntry.kt`, `file-service-proxy/.../FileSliceSnapshotProducer.kt` |
| writer + reader | `sync-service-proxy/.../SliceSnapshotWriter.kt`, `.../SliceSnapshotReader.kt` |
| the schedule + the seed | `sync-service-proxy/.../SliceSnapshotSchedule.kt`, `sync-service/.../WriteSliceSnapshots.kt` |

The **port grew an ingest half**: `SliceSnapshotProducer` now also carries `bootstrapSliceKeys`
(the seed a device can name *before* it holds state — ⚠ `sliceKeys` enumerates the local store,
which on a joiner is empty) and `applySlice`. Both directions belong to one interface because a
device that can produce a slice can, by construction, apply one.

⚠⚠ **`applySlice` is create-only, and that is a correctness rule.** An entry is a *projection*, so
writing it over an existing row would discard every property the projection omits. It also writes
**no `SyncMetadata`** — that absence is what makes the first real block win
(`resolveConflictAndSave` reads missing metadata as "anything incoming is newer"), as an update to
the same entity id rather than a duplicate. Rows are saved `SYNC_INGEST`, so the parent's `FILES`
membership falls out of `ParentFilesRelationMaintainer` exactly as `CreateFolder` relies on, and
nothing is re-emitted.

**The two halves run on opposite schedules, deliberately.** The reader is a one-shot at the top of
`tickOnce` (a joiner needs its first screen *now*, and it is skipped entirely once
`backfillCursor == 0`); the writer runs only from the `WriteSliceSnapshots` op on an
idle+charging+unmetered+battery-not-low periodic item, because sealing every slice is expensive
work a device does **for other devices** and must never compete with what a user is waiting for.

Producers are registered per platform module beside `ExternalPropertyWriter`, as a *list* — every
service contributes its own, and one that registers none simply never gets a snapshot.

⚠⚠ **Two pre-existing properties of `ScheduleDrainLoop` shape when this actually runs, and the
first draft of this paragraph got the second one wrong.** (1) It never reads `ConstraintSpec` —
only Android's `WorkManager` path enforces idle/charging (the bgproc Phase 6 item the offline
keeper's `NETWORK_UNMETERED` waits on too). (2) Its cadence is a **per-process tick accumulator
with no wall clock** (`accumulatedMs` and `firstFired` both die with the process), so a threshold
only elapses across **continuous uptime**. A 24 h period therefore never comes round in-process
unless the proxy lives a day; on Android `WorkManager` is the real driver, and on desktop the pass
effectively runs once per long-lived session and rarely again. ⚠ The earlier claim here — "on
desktop it fires every 24 h regardless of idle or charging" — assumed wall-clock scheduling and was
wrong.

**Verified:** full gate (1 676 tasks) green, Android smoke gate passed (8 apps, schema v20,
sessions admitted, no IPC failures) — **and the write half now runs on a real device**:

```
SliceSnapshotWriter: sealed 10 slice(s) at seq 791875
```

Ten is exactly the folder count §11.4.2's census predicted for this realm, so the producer's tree
walk and the store agree. ⚠ Still **not** verified: a slice sealed on one device and *read* by
another. The reader is skipped on a caught-up device by design, so exercising it needs a joiner —
the pair-and-join pass remains owed.

Three things that pass cost, all worth keeping:

⚠⚠ **It did not work at first, and the cause was a property that became dynamic, not C8.** Every pass died with
`ClassCastException: ByteArrayValue cannot be cast to LongValue` — C7's `valueProperty` →
`dynamicProperty` kind flip left stale inline slots on every pre-existing `File` row, and
`FileSliceSnapshotProducer` walks exactly those rows. On this store that is **743 of 822** File
rows (§11.4.2 measured the bytes without understanding they were also poison). With that kind-flip fix in:
10 slices sealed, 0 exceptions. ⚠ A first analysis wrongly ruled the kind flip out — it checked only the
*wedged-pull* mechanism its fix leads with (genuinely absent here: 0 ingest frames in a 50-minute
capture) and missed the direct read path.

⚠⚠ **`ScheduleDrainLoop` is per client session, not per process.** Five sessions ⇒ **five loops**,
each with its own accumulator, so the pass ran five times and sealed the same ten slices five over.
Harmless here *only* because a re-seal is idempotent and lands on the same ref by design — but it
applies to `DrainSync` and the offline keeper too, and neither is idempotent in the same way. The
"one scheduler per process" election covers `SyncScheduler`, not this loop.

⚠⚠ **A scheduled op that throws is completely silent.** The loop catches every `Throwable` and
records a boolean, so the failure above produced no line at all — six named skip reasons were added
first and every one of them sat *upstream* of the throw. What finally named it was catching inside
`writeSliceSnapshots`. Any future `HeadlessSafe` op wants the same guard, or its failures are
invisible until someone pulls a store.

### 11.4.2 C8b — measured, and it answers a different question than it asked (2026-08-06)

Measured against the maintainer's **real** Android store (134,020 objects, 27.4 MB of row data) —
the `objects` table again replacing the probe that was about to be written.

**⚠⚠ C8b as scoped cannot be run yet, and that is the finding.** "Service-grouped vs flat" needs
**two** producers to differ; today `FileSliceSnapshotProducer` is the only one, so both layouts are
byte-identical. The comparison becomes measurable the moment C9 adds `MOMENTS`. ⇒ **Fold C8b into
C9** rather than run it now — and note that §5.7.4(b) already conceded the typed-list benefit had no
identified consumer, which is the same shape of answer.

**⚠ The §5.7.4(b) premise needs restating.** It argued from the iOS P4-1 measurement that a joining Drive materialises a
store whose bulk is "Fit data Drive cannot use". By row count the bulk is neither Fit's nor Drive's:

| bucket | rows | share | bytes |
|---|---|---|---|
| sync bookkeeping (`dataId/string`, `resolvedDataId`, `hlc`, `metadata`) | 101,426 | **75.7 %** | 8.65 MB |
| health domain | 23,321 | 17.4 % | 2.90 MB |
| file domain | 1,667 | 1.2 % | **14.66 MB (53.5 % of bytes)** |
| timeline domain | 543 | 0.4 % | 0.11 MB |

Bookkeeping runs at **~3.2 rows per domain entity**, so it is not an independent cost — it is a
multiplier on whatever a device materialises, and Fit is the largest contributor to that base. The
correction is that **slice grouping cannot collect it**: a snapshot changes what a joiner *renders
first*, not what the log makes it *materialise*. Deferring another service's rows would need
ingest-side filtering — a different mechanism, and one that would sit uncomfortably close to the
per-app scoping route-1 P0 decision **C** explicitly rejected.

**⚠⚠ And the measurement surfaced a limit on C7 that its own headline hides.** The store is stamped
`tenshu/file/service = 2`, so C7 has been applied — yet **743 of 822 `File` rows are still ≥ 10 KB**,
holding 13.9 MB. Only **14 rows** are at C7's predicted post-move size (~326 B); the rest predate it.
The registered step is deliberately empty (`schemaMigration(FileService.ID, fromVersion = 1) {}` —
"nothing reshaped positionally"), so **C7 changed the write path only and existing rows keep their
thumbnails forever**. On this device C7's win covers **1.7 % of File rows**, and a joiner replaying
this log still receives all 13.9 MB.

That is not an argument against C7 — moving the write path was right and the alternative (rewriting
every row) is what E4 compaction exists for. It *is* the reason the C8/C9 slice story should not be
read as "the joiner's bytes are solved": the snapshot's entries are thin, and the log behind it is
not.

### 11.4.3 C9 — the first singleton relation, as built (2026-08-06)

`MOMENTS` is the case §5.7.3 raised and C8 could dodge: a folder's children are bounded by the
user's own tree, but a five-year feed is one unbounded collection with **no sub-key inside it**. So
the partition is invented, and `Moment` declaring `Sliceable(SliceKey.Day)` is Timeline choosing
which invention it wants.

| unit | where |
|---|---|
| the projection | `timeline-service-common/.../slice/MomentSliceEntry.kt` |
| the producer | `timeline-service-proxy/.../MomentSliceSnapshotProducer.kt` |
| the declaration | `Moment.Schema` traits — `Sliceable(SliceKey.Day)` |

Adding the trait reshapes nothing (`SchemaShapeBaselineEnforcementTest` green), so **no version
bump is owed** — same as `File` in C8.

**⚠⚠ The key is UTC; the feed renders local. Both halves are deliberate.** A slice key is an
*address two devices must agree on*, so it cannot depend on the writer's zone — seal
`2026-08-06` in Berlin and a peer in Los Angeles asks for a bucket whose contents it cannot
predict. Grouping stays a *rendering* concern: entries carry `timestampMillis`, so the UI
re-groups locally from the same bytes. ⚠ The cost, stated rather than hidden: a local day
straddles two UTC buckets for most of the world, which is why the seed is **today *and*
yesterday** rather than today alone.

**Two bounds, because a chronological feed has no natural end.** A call reads at most
`RECENT_WINDOW` (2 000) rows through `readOrderedWindow` — the store is not enumerable by day, so
the window *is* the enumeration — and a pass seals at most `RECENT_DAYS` (7) buckets. Sealing 2019
buys nothing a log replay does not already give.

**⚠⚠ Truncation is the only honest reason to answer `null`, and a failing test is what found
that.** The first implementation refused any day older than the oldest row it had seen — which also
refuses every genuinely quiet day, the *normal* case for a feed. What matters is whether the window
**reached the end of the store**: an untruncated window has seen everything this device holds, so a
day it does not contain really is empty. Only a full window leaves older days unknowable. ⚠ "This
device holds nothing that day" is a safe proxy for "the realm holds nothing that day" **only
because the writer refuses to publish until catch-up is complete** — the same gate that makes
`asOfSeq` a promise rather than a guess.

⚠ `sources` is deliberately **not** projected: it is the mergeable set `MomentMerger` unions across
devices, and a create-only applier carrying half of one would produce a row that looks merged and
is not. `supersededBy` *is* projected — the projector hides superseded moments, and omitting it
would make a joiner render duplicates its peers already reconciled away.

⚠ Cost noted, not optimised: `sliceKeys` and each `encodeSlice` re-read the same window, so a pass
is O(days × window). Bounded and cheap at these constants; if either grows, the seam wants a
"produce every slice in one call" shape rather than a cache inside a producer that claims to be
stateless.

**This also makes C8b real.** With a second producer registered, "service-grouped vs flat" finally
has two services to compare — §11.4.2 folded the measurement into this phase for exactly that
reason. ⇒ Run in §11.9, where it turns out to be **moot by construction**: there is no manifest to
group.

### 11.4.4 Slice size — a byte budget on every slice (2026-11-01)

The iOS shared store's S0b list (`ios-shared-store-sqlite.md` §4.2.1) carried "`MomentSlice`
snapshot `entries`: size not traced" as a blocker, on the fear that a realm-wide singleton slice of a
few thousand moments would be one row over `ROW_MAX` (512 KiB). Traced, before designing:

| question | answer, from the code |
|---|---|
| Where does a slice live? | ⚠⚠ **Never in the object store.** `SliceSnapshotWriter` seals the envelope and `putChunk`s it to the **content plane** under `RelationSliceSnapshot.ref` (relay-side: one opaque blob, `MAX_CONTENT_BLOB_BYTES` = 8 MiB, else 413). Where an iOS app routes `putChunk` through the background session (Drive) it stages the bytes as a **file** (`BackgroundUploadManager`, journal holds the ref only). The reader `getChunk`s it into memory, decodes, and hands `entries` to the producer. No path writes the blob, or `entries`, into a row. |
| What does it write into the store? | `applySlice` writes **one ordinary entity row per entry** (`Moment`, `File`/`Folder`), each the size of the row the log would deliver, and the relation maintainers append to `MOMENTS` / `Folder.files`, which are segmented on the SQLite store since B.19. |
| Who produces it, when? | Each registered `SliceSnapshotProducer` (Drive's `FileSliceSnapshotProducer`, Timeline's `MomentSliceSnapshotProducer`), on the idle+charging `WriteSliceSnapshots` pass, only once the device is caught up. |
| Who reads it? | `SliceSnapshotReader.bootstrapOnce`, once per process on a device still catching up, and **only the seeds**: Drive's root folder, Timeline's today and yesterday (UTC). Nothing fetches a non-root folder's slice yet. |
| Is a `MOMENTS` slice a realm-wide singleton? | ⚠ **No.** The *relation* is; the slice is one **UTC day** (C9, `SliceKey.Day`), read through a window of `RECENT_WINDOW` (2 000) rows, so a slice can never hold more than the window. |
| Does the hazard reach other kinds? | Yes, and harder: `Folder.FILES` is one slice per folder with **no bound on children**. Not against `ROW_MAX` (no row), but against the relay's 8 MiB cap — at ~200 B an entry (path, name, content id and hash, times; *estimated*), a folder past ~40 000 files 413s on every pass, silently (the writer counted only successes) — and against the purpose: a 6 MiB first-screen fetch is no longer "usable in seconds". |

**So the S0b row is resolved by the trace** — no slice is a store row, and no slice of any size,
from any peer, makes the iOS store refuse anything (`MomentSliceRowMaxSharedReplicaTest`: a 600-entry,
>512 KiB `MOMENTS` slice applies row by row into the real SQLite store, where the same bytes inlined
in one row are `RowTooLarge`). What was real is an unbounded blob, and it is closed generically:

**Decision: (c) skip, in the writer, for every producer.** `RelationSliceSnapshot.MAX_ENCODED_BYTES`
= **384 KiB** of encoded envelope; `SliceSnapshotWriter` measures each slice after encoding and,
over it, seals **the withdrawn marker** at that address instead (`RelationSliceSnapshot.withdrawn()`:
the envelope version `WITHDRAWN_VERSION` = `Int.MAX_VALUE` and nothing else) and logs relation, entry
count and size (never the key — a folder's key is a path). Every reader ever shipped declines an
envelope newer than its own `VERSION`, so the joiner applies nothing and its first screen shows only
what replay has delivered so far, exactly as for a slice no device has sealed. ⚠⚠ The marker, not a
bare skip: a skip would leave an earlier, smaller seal at the address for good, and a joiner would
keep seeding from it while it aged — which, before the tombstone fix below, could recreate files
deleted since. A slice the producer answers `null` for (a day the window now cuts, a folder that
will not read) is withdrawn the same way. ⚠ A key that drops out of `sliceKeys` altogether — a
folder deleted, a day aged past `RECENT_DAYS` — is **not** withdrawn: the writer no longer names it,
and the reader fetches only the seeds. Drive's seed, root, is always named. A Timeline seed day drops
out only when every moment in it is gone, and the tombstone guard then skips each of its entries. Accepted;
it needs revisiting if navigation-fetch is built.

⚠⚠ **This leans on the relay replacing a ref's bytes on a re-`PUT`**, which a slice always did (it
is re-sealed in place) and the marker now does in both directions. Production runs
`SqliteContentBlobStore` (`sync-relay-server/docker-compose.yml` hard-codes `DATABASE_URL`), which
overwrites the bytes and upserts `byte_len`, over `FileBlobStore` or `S3BlobStore`, both of which
overwrite. The in-memory `mailbox.ContentBlobStore` — used by any relay started without
`DATABASE_URL`, and by tests — **kept the first upload**, so there a slice was frozen at its first
seal and a withdrawal never landed. It now replaces, matching SQLite; pinned for both backends and
both `PUT` paths by `ContentBlobReplacementContractTest`. Nothing relied on keep-first: a chunk's ref
is `sha256(ciphertext)`, so a retry carries identical bytes, and the refs that are not a hash of
their body (a manifest re-sealed under a fresh nonce, a slice) want the replacement. ⚠ Shared by both
backends and unchanged here: a re-`PUT` of an existing ref is not quota-checked, so a larger body
under an existing ref is bounded only by the 8 MiB per-blob cap (Q-SL2).

- **Why not (a), the most recent entries up to a budget.** A slice claims to be *the whole* of its
  folder or its day; a cut one renders entries silently missing, which `SliceSnapshotProducer`
  already forbids ("a wrong slice is far worse than none"). For a day bucket there is nothing to
  gain either: the fullest day a default window can seal is **276 061 bytes** (2 000 Takeout-shaped
  visits with long place names, measured in `MomentSliceSnapshotProducerTest`).
- **Why not (b), segments.** It is the right shape once a reader fetches big folders by navigation;
  nothing does yet, and a reader that must fetch all N parts or render none is more machinery than a
  regenerable, optional artifact needs today.
- **Why 384 KiB, when no slice is a row.** Under the relay's cap with room to spare; one fetch a
  first screen can afford; and under `ROW_MAX` with room for a seal and a row envelope, so a later
  change that persists a fetched slice locally (the navigation cache §5.7 sketches) cannot bring the
  S0b blocker back. The cost: a root folder over ~1 900 children (at the estimated ~205 B an entry)
  gets no seed slice; its first screen fills as replay delivers rows, as before C8.

⚠⚠ **And one honesty fix the trace turned up, in `MOMENTS`.** A full window also cuts the **oldest
day it reaches** — its earlier moments are the rows just past the window — and `encodeSlice` sealed
that day as if whole (`sliceKey < dayKey(oldestSeen)` refused only the days *before* it). It is now
`<=`: a full window seals a day only when an older day is in it too, so a sealed day is always whole
and holds at most `RECENT_WINDOW` entries. "Full" means the store holds **more** than the window, so
the producer reads one row past it: a store of exactly 2 000 moments is wholly seen, oldest day
included. At the defaults this never touches a seed day (the window spans months), but a day with
2 000 moments would have been handed to a joiner short. The fullest sealable day, 2 000 such visits,
fits the budget (`encodeSlice_ofTheFullestDayTheWindowCanSealFitsTheWriterBudget`).

⚠⚠ **And a resurrection gap older than this section (found in review).** `applySlice` was
create-only by `getData(id) != null` alone, and `bootstrapOnce` runs again on **every launch** while
`backfillCursor != 0` — hours, for a big realm, so an iOS joiner is relaunched mid-way. A file sealed
into the root slice and deleted later is created by the slice, deleted by its tombstone, then
**created again** by the next launch's slice — and the tombstone cursor is already past that
tombstone, so nothing deletes it a second time. Since this section, `SliceSnapshotProducer.applySlice`
takes a required `isDeleted: (DataId) -> Boolean`, the reader answers it from the processor's
`TombstoneRegistry` (`deletionPointOf(id) != null`: the persisted tombstone or reap point, which
survive a restart), and both producers skip every such entry — Drive also skips a whole slice whose
folder is deleted (`SliceResurrectionTest`). Skipping a row that was deleted and later recreated is
safe: its create block is newer than the tombstone, so replay still delivers it.

**What did not change, and why that is sound.** No envelope or entry format changed, so no
`VERSION` moved; both are content-plane bytes outside `SchemaShapeBaseline` (it pins only the
`writeSliceSnapshots` op), so HR 9 owes nothing — and the KDoc claim that an entry reshape costs
only its `VERSION` holds: an older reader returns `null` on a newer version and falls back. The
withdrawn marker uses that same rule and reshapes nothing. HR 1: the relay sees one more sealed blob
of a fixed small size at an address it already held, and no new field. HR 2: the relay change is the
in-memory store's re-`PUT` semantics only, aligned with the production backend; no table, route,
auth rule or stored shape changes, and no threat-model asset moves, so no amendment is owed. ⚠ The `applySlice` signature
change is an in-process port, not a wire or store shape. **An older peer** still seals any size up to 8 MiB;
the reader keeps applying it (`aSliceOverTheBudgetFromAnOlderPeerIsStillApplied`) — it is honest,
only big, and on iOS it lands row by row.

**Q-SL1 (closed in review).** A slice that grew over the budget used to leave its earlier blob at
its address for good; the writer now withdraws it (above). ⚠ What remains is the staleness every
slice has between passes: its rows are provisional, a later block for the same id replaces them,
and since the tombstone fix a deletion already ingested is never undone by a re-application. A
deletion the device has **not** ingested yet is removed when its tombstone arrives, after the slice.
Q0 (w) — the largest real row on the iPhone — stays owed, but no longer has a slice to find.

**Q-SL2 (open, not this PR's to change).** Both relay stores skip the quota check on a re-`PUT` of a
ref the owner already holds, so a client can grow an existing ref past its quota, up to the 8 MiB
per-blob cap per ref. A re-sealed slice can do this legitimately, by at most the budget. *Recommendation:*
check `used − old + new` for an existing ref in both stores, in its own relay PR, since it changes
production's quota behaviour.

### 11.5 Verification recipes (earned the hard way, 2026-08-05)

- **iOS:** `./gradlew :tenshu-sdk-ios:assembleTenshuSdkDebugXCFramework` — ⚠ **not**
  `assembleTenshuSdkXCFramework`, which OOMs on the Release link. Then byte-verify a literal your
  change introduces (K/N strings are **UTF-16LE**; `strings` cannot see them) in
  `.../XCFrameworks/debug/TenshuSdk.xcframework/**ios-arm64**/…` and again in the installed
  `build/ios-device/<App>/…/<App>.app/*.debug.dylib`. ⚠ Build duration is **not** a staleness signal.
- ⚠ `scripts/ios-smoke.sh` **terminates each app after its 6 s liveness check** — to observe work,
  launch with `devicectl device process launch --console` and leave it up.
- **Desktop:** `:control-desktop:packageControlApp` **plus** `:<app>-desktop:signDesktopApp` for the
  other seven. ⚠⚠ Bare `createDistributable` does **not** sign, and Control's IPC gate then refuses
  the app — which presents as an endless loading spinner. Check `codesign -dv` shows
  `TeamIdentifier=FAYT82GWD7`, never `adhoc`, **before** installing.
- **Flakes:** the gate has a known flaky population (`docs/tech-debt.md`). Re-run the failure alone,
  then force a full-module re-run; only then call it a flake. ⚠ If your change introduces asynchrony,
  be slower to do so — that is exactly what turns a latent order dependency intermittent.

### 11.6 ⚠⚠ The habit this whole note is a record of

Five plausible diagnoses were overturned by measurement in one day: store-write batching (writes are
3 %), page size (fetch is transfer-bound), the orphan sweep (starved, not unwired), store size
(107 k rows index in 208 ms), and `getSchema` (400 k calls, 1.2 %). **Two were one step from being
built.** Instrument the hot path, bucket by attribution, read the histogram — before writing the fix.


### 11.7 C4 — the bulk-writer audit, as built (2026-08-07)

⚠ Numbered out of order: C4 is §5.1's phase but shipped after C6–C9, so its as-built lands here
rather than renumbering §11.5/§11.6.

§5.1 asked one question — *`SyncLane` collapsed 1 200 saves into 3 blocks; which bulk writers still
do not ride it?* — on the reasoning that at ~5.5 blocks per live entity the cheapest volume win is
emitting fewer blocks, not reading them more cleverly.

#### The audit

A **bulk writer** here means one logical user action that saves many `Syncable` rows. Every such
path in the repo, and where it stands:

| path | status |
|---|---|
| Fit health import (Health Connect + file) | ✅ on a lane since TS-4 |
| Drive folder upload · Synced Folders | ✅ on a lane since TS-5 |
| Control R1 data reset | ✅ `RealmDataClient` wraps `withTask` |
| **Timeline Google-Takeout file import** | ❌ → **fixed here** |
| Maps mock-place seed | ❌ → left alone, below the threshold (see below) |
| Timeline live capture (`CapturedVisitWriter` / `CapturedMoveWriter`) | n/a — one event per call |
| Fit single-workout add · recording-outbox drain | n/a — one row, or a handful |
| `MomentReconciliationPass` | n/a — writes `DERIVED`, which emits nothing |
| `MoveFile` folder move (descendants + `rekeyHistory`) | ❌ proxy-internal — no seam, see below |
| `ShareFolderIntoRealm` (one `SharedFile` per node) | ❌ and a lane **cannot** help it yet |
| `PlaceRoleInferencePass` · `PlaceReconciler` merges | ❌ proxy-internal — no seam |
| bulk **deletes** (folder cascade, R1 reset, retention sweeps) | ❌ structurally — tombstones have no lane at all |

**The find.** Timeline's file import is the larger of the two bulk imports — a Takeout export
carries *years* of moments, places and traces — and it was never wrapped, while Fit's has been
since TS-4 landed. Both go through the same shape (`FileTimelineImporter` → `TimelineImportWriter`,
mirroring `FileHealthImporter` → `HealthImportWriter`); only one of them got the task. That
asymmetry is precisely the kind of thing an audit exists to find, and nothing else would have: the
import *worked*, it just cost one block per row.

⚠ Only the write phase is inside the task. The parse is pure, and the place enricher that follows
reaches the network per place — holding a lane open across a geocoding run would keep a lease alive
for minutes to batch writes that trickle in one at a time anyway.

⚠ The **capture** path deliberately stays bare even though it shares `TimelineImportWriter`: it
calls the writer with a single event, so a lane there would add a registration round trip per visit
to batch one row.

#### `withBulkWriteTask` — the fourth copy became a function

`sync-service-client/.../BulkWriteTask.kt`. TS-4 wrote the register → `withTask` → complete/fail/
cancel lifecycle inside Fit, TS-5 wrote it again inside Drive, and this audit found two more sites
wanting it. It is eleven lines with two subtleties that a fourth hand-written copy is exactly how
one loses: **degradation is silent and total** (no TaskService ⇒ the block still runs, per-save —
batching is an optimisation and must never cost a write), and the **terminal transition rides
`NonCancellable`** (by the time a `CancellationException` arrives the scope is already unwinding, so
the `cancel()` that closes the lane would itself throw, leaving the lane open until its lease
expired). Fit and Drive now delegate to it; Fit keeps only its record-counted heartbeat, which is
genuinely its own (a Fit record is small and steady; Drive's unit is a whole file that can take
minutes and must renew per unit).

#### ⚠⚠ The real find: the batching claim had never been measured

`BulkWriteTaskSystemTest` counts blocks **the relay accepted**, for N writes bare and the same N
writes wrapped. The first run said **24 writes → 24 blocks batched vs 28 bare**. The lane was doing
nothing.

Cause: `SystemTestProxyModule`'s hand-rolled `CancellableLocalTaskExecutor` overrides the *one-arg*
`executeAsyncCancellable(task)` and delegates — but not the **two-arg** `(task, taskId)` variant,
whose interface default *drops the id* and calls the one-arg form. So every `withTask`-tagged call
reached the proxy with `requestSyncLaneId = null`, missed the explicit-open gate, and took the
per-save path. The same omission was in `TestProxy` and `InProcessProxy`; all three now forward it.
⚠ **Production was never affected** — Control binds `SessionAwareLocalTaskExecutor`, which forwards
both — which is the whole argument for harnesses mirroring production wiring rather than
approximating it ([[feedback_mirror_production_wiring]]).

⚠⚠ **And the two tests that exist to prove TS-4/TS-5 were green throughout.**
`TaskBatchedImportSystemTest` and `TaskBatchedUploadSystemTest` assert that every task-batched row
reaches the joiner — which it does whether or not anything batched, because batching changes only
the block *count*. A test whose assertion cannot distinguish the feature being on from off is not
evidence for it. Post-fix the same measurement reads **24 writes → 1 block vs 27 bare**.

#### What was deliberately not fixed, and why

- **Proxy-internal bulk ops** (`MoveFile`'s descendant + history re-keys, the place passes). A lane
  is opened via `SyncBlockEmitter` and joined by tagging each `saveData` — but these ops write from
  a dozen call sites and, in `MoveFile`'s case, from inside `DataIdMigrator`. There is a seam
  (`ExecutionContext.withEvaluationScope(scope, requestSyncLaneId = …)` yields a derived context
  whose every save is tagged), so this is *buildable*; it is a slice, not a line.
- **`ShareFolderIntoRealm`** — laning it would achieve nothing today: `stageDelta` falls back to
  per-save emission for **non-personal realms** (lanes are personal-realm-only in v1), and every row
  it writes is a shared-realm row. It gets fixed by widening lanes, not by wrapping the op.
- **Bulk deletes.** `DataObserver.onDataDeleted(dataId, origin)` takes **no lane id at all** — the
  lane API only exists on the save path — so a folder cascade or an R1 reset emits one tombstone
  block per row unavoidably. `DeltaBatchPayload` carries value deltas, and tombstones ride a
  separate relay lane, so batching deletes is a protocol change (a payload kind), not a wiring one.
  Worth naming because a *delete-heavy* device is exactly the one whose log is long.
- **Maps' place seed** — a fixed dozen mock rows. Adding a module edge from `:maps-shared-data` to
  `:sync-service-client` to batch twelve writes costs more than it saves.

### 11.8 C5 — investigated, and the phase does not mean what §5.2 says it means (2026-08-07)

§5.2 calls compaction *"the primary answer to log VOLUME"* and defers the design wholesale to
`core-schema-evolution.md` §8.3. Both halves of that sentence turn out to need correction, and the
second one is structural.

#### (a) E4 is already built — all of it except one DI line

E4-1…E4-5 shipped 2026-08-02 (§8.3.1–§8.3.7): `DeviceSchemaVersion` + `RosterSchemaFloor` (the
roster gate), `DeviceSchemaVersionPublisher`, `CompactionReceipt` + `CompactionPolicy` (dedup
without election), `SchemaCompactor` (decode → `getDataId` → `saveData`), `FloorRetirement`
(the verdict — there is no runtime floor to *advance*), and `SchemaCompactionMaintenance` +
a `HeadlessSafe` `CompactSchema` op.

What is missing is deliberate: production DI supplies no `compactSchema` lambda, so no
`ScheduleItem` is registered. ⚠ **It should stay missing.** `CompactionPolicy.shouldCompact`
returns false whenever `readableFloor >= serviceVersion`, and **every registered chain in the repo
is an empty body** (`schemaMigration(X, fromVersion = N) {}` — file, timeline, place, calendar,
sync, core), so every floor equals its current version. Wiring it today registers a recurring
maintenance item that provably does nothing, which is precisely the failure §8.3.7 refused.

#### (b) ⚠⚠ Compaction as designed does not reduce log volume — the relay cannot drop superseded blocks

§8.3's volume story is one sentence: *"a device re-emits current state as fresh blocks; retention GC
eats the superseded backlog."* The second clause is false against the relay as built.

`SyncMailbox.pushBlock` (both backends) **appends unconditionally** — it never supersedes. The only
path that removes a block body is `MailboxStore.dropBodiesFor`, and its only caller is `gcSweep`,
which iterates `tombstoneOwnerIds()` / `tombstoneIndex(ownerId)`: it drops bodies **only for
entities that have a tombstone**, gated on every device having acked and the retention window having
elapsed. There is no mechanism anywhere that drops a superseded block for a *live* entity.

⚠ So re-emitting current state today makes the log **longer**: one block added per entity, none
removed. §2.1's 5.5-blocks-per-entity would become 6.5.

#### (c) ⚠⚠ And the obvious relay-side fix is blocked by a threat-model decision

The natural fix is a supersede rule at the relay: on a new block for `targetEntityIdHash` H, drop
older blocks for H once every device has acked. It cannot be written safely, for two independent
reasons.

1. **Entity-hash granularity is not fine enough.** Not every block for H is whole-entity state —
   property blocks (`DataBlockPayload(propertyId, value)`: file bytes, thumbnails, workout streams)
   are addressed to the same H and are **not** superseded by a newer entity block. A rule keyed on H
   alone deletes them. ⚠⚠ And the discriminator is unavailable **by design**: `propertyId` lives
   *inside the ciphertext*, which is threat-model **T-R-08** — the plaintext `targetPropertyId` was
   removed precisely so the relay could not enumerate each user's schema vocabulary. This is also
   why the existing GC is tombstone-shaped: deletion is the one case where entity granularity is
   safe, because *every* block for a deleted entity is droppable.
2. ⚠⚠ **Lane-batched blocks are structurally invisible to any such rule.** A `DeltaBatchPayload`
   block carries `targetEntityIdHash = null` (§10.2 D2 — containers hold deltas for many entities
   and carry no wire routing hash). So a batch block cannot be superseded, and cannot supersede.
   **This pulls directly against C4**: the more bulk writes ride lanes, the smaller the fraction of
   the log an entity-keyed supersede rule could ever reclaim.

A viable shape exists — a **one-bit** plaintext "this block is whole-entity state" marker, which
T-R-08's own carve-out already contemplates as an acceptable class of leak (*"that marker leaks a
single strategy bit per block, not the schema vocabulary"*). But that is a wire change to a
composite the relay parses ⇒ a `RELAY_PROTOCOL_VERSION` bump + coordinated redeploy, an HR#9 bump,
an HR#2 threat-model amendment, and a **destructive** relay mechanism operating on the only copy of
user history. It is a design slice of its own, not a phase of this note.

#### (d) Where that leaves the volume problem

⚖️ **Less urgent than §2.1 implies, because the payoff it was standing in for has already been
taken by other phases.** The redundant blocks cost:

| cost | status |
|---|---|
| a joiner's **time to usable** | ⭐ addressed — C1 (priority fetch), C2 (newest-first: 1 write per entity, not ~5.5), C8/C9 (snapshot) |
| a joiner's **bytes transferred** | partly — C7 took ~97 % of `File` bytes off the log; the snapshot skips, but does not shorten, the range below `asOfSeq` |
| a steady-state device | none — each device pulls each block exactly once, from its own cursor |
| **relay storage + the owner's quota** | ⚠ **unaddressed, and the only remaining sharp edge** — see `retention-quota-model.md` |

**Recommendation.** Close C5 as *investigated* rather than shipping either half of it. Do not wire
E4-5 until a real (non-empty) migration chain exists — the receipt/floor machinery is correct and
tested, and it will do its schema job the day it has one. Track the volume question separately, as a
relay-side supersede design owing a protocol bump and a threat-model amendment, and sized against
storage/quota rather than against catch-up latency.

⚠ **The premise to carry forward:** *"compaction reduces log volume"* is true of the classic
design and false of this system today, because the reclamation half was never built and the two
mechanisms that would make it safe — per-property visibility and per-entity block addressing — were
each deliberately removed for privacy (T-R-08) and for batching (§10.2 D2). Compaction here buys
**readable-floor advancement**, which is a schema-evolution payoff, not a volume one.

### 11.9 C8b — answered by reading the code, not by wiping a device (2026-08-07)

§11.4.2 folded C8b into C9 because "service-grouped vs flat" needs two producers to differ. C9
supplied the second. Running it now produces an answer in three parts, none of which required a
joiner, a device or a measurement.

#### (a) ⚠⚠ There is no manifest, so there is nothing to group

C8b was scoped as *"measure whether service-grouped slices shorten Drive's first screen vs one flat
root slice"* — a question about how a **manifest** is laid out. **C8 built no manifest.** A slice's
address is *derived*:

```
RelationSliceSnapshot.ref(realmId, relationId, sliceKey)
  = "blob-" + sha256("snapshot:$realmId:$relationId:$sliceKey")
```

`SliceSnapshotReader.bootstrapOnce` walks the registered producers, asks each for its
`bootstrapSliceKeys`, and derives one ref per slice. Nothing is enumerated, published or fetched as
a list. So the layout question is **moot by construction** — and §5.7.4(b) can be closed rather than
measured.

#### (b) The lever it was a proxy for is real — and it is DI, not wire

What §5.7.4(b) actually wanted was for a joining Drive to fetch *the file-service slices its first
screen needs and defer everything else*. With derived addressing, the set a joiner fetches is
exactly **the set of producers its runtime registers**. That is a dependency-injection fact, not a
protocol one, and it is a better place for the lever than a manifest: no wire surface, no version,
and each runtime already assembles its own graph.

#### (c) ⚠⚠ And it is currently wired the wrong way for the case that motivated it

All three `PlatformProxyModule`s register **one shared list** — `FileSliceSnapshotProducer` +
`MomentSliceSnapshotProducer` — and every route-1 app assembles that same
`buildPlatformProxyModule()` (`FirstPartyLocalRuntime`), with no app overriding the registry. So a
joining **Drive on iOS** derives and fetches Timeline's `MOMENTS` slices, and applies them, before
rendering its own first screen. That is precisely the direction §5.7.4(b) argued against, arrived at
by nobody deciding it.

⚖️ **Sized before acting: it costs almost nothing today.** `MOMENTS`' seed is two UTC day buckets
(§11.4.3), so the joiner pays **two extra blob GETs** and creates however many moments those days
hold — on the maintainer's store the entire timeline domain is 543 rows, so a day is tens. Against
that, restructuring producer registration across three platforms now would be moving DI for two
HTTP requests.

⚠ **What changes the answer**: a third producer, or a service whose seed is large. The rule to apply
then is a **reader/writer split**, not a per-app list — the *writer* should keep fanning out over
everything the device can project (or nobody seals those slices; on iOS route 1 Drive is the
background syncer, P6a), while the *reader's* bootstrap is scoped or ordered to the app's own
services. Recorded here so the next producer's author sees the shape rather than rediscovering it.

**⇒ C8b closed.** The manifest set stays flat because there is no manifest; the prioritisation it
stood for is a registration concern, noted with its trigger.

⚠ **This did not need a device wipe, and a wipe would not have answered it** — both layouts fetch
the same bytes, so a bootstrap timing would have measured noise. What a wipe *is* the instrument for
is the still-open **read-half verification** of C8/C9 (does a real joiner apply a real slice), which
is a different claim.

### 11.10 ⚠⚠ The walk could declare itself complete while history was missing (2026-08-14)

Found on the maintainer's iPhone, from the store rather than from a log:

```
tenshu/sync/backfillCursor = 0      # "complete"
tenshu/sync/backfillTarget = 500
```

`0` is the **complete** sentinel and `backfillOnce` opens with `if (floor == 0L) return false`, so
it is a **one-way latch**. That device had 98 rows on its Android peer that had never arrived and,
by construction, never would. Nothing was broken at the time — sync was healthy, the tick ran, the
device was admitted. It simply believed it was finished.

**How it latched.** The rule was `if (outcome.cursor >= before) → complete`. An empty backward page
reports `nextCursor == beforeSeq` *by contract*, so that test conflates two different facts: "the
relay says there is nothing below this seq" and "this page did not descend". Only the first is
evidence of the bottom.

**Why "just let the forward pass cover it" was wrong.** The old behaviour was deliberate, and
`aRelayThatMakesNoProgressDoesNotWedgeTheWalk` pinned it: *"no progress ⇒ stop, do not spin — the
forward pass still covers the history, slowly."* ⚠⚠ **That justification does not hold for the rows
this walk exists to fetch.** The forward pass starts where the device joined and moves *up*;
anything below its start is reachable only by the backward walk. Latching on no-progress therefore
did not trade speed for safety — it abandoned pre-join history permanently. The test now asserts the
reverse, and says why.

**Three properties, and each was individually enough to hide this:**

1. completion was **inferred** from a page rather than affirmed by the relay;
2. `pullSyncLogBefore` swallows every failure and returns `null` — deliberately, *"backfill is
   catch-up"* — so a walk that is failing is indistinguishable from one that is idle;
3. `reconcileStaleSyncCursor` only fires when `database.isEmpty()`, so it covers a total wipe and
   can never see a **partial** gap. The iPhone had 36 952 objects.

Same family as the `Entry not found` tick death (a dangling queue or relation member): **a bug whose consumers all fail
safe leaves no symptom.**

**As built.**

- `SyncLogOutcome` carries `received` (blocks + tombstones on the page). The latch now requires
  `received == 0 && !hasMore` — the relay affirming the bottom. Anything else retries next tick and
  logs a WARN naming the condition. ⚠ The spin the old comment feared is real but bounded: one
  request per tick, now observable. A bounded retry beats silent data loss.
- `ObjectStore.clearBackfillCursor()` + `SyncManager.reopenBackfill()`. ⚠⚠ These exist because
  `backfillCursor = null` **cannot** be written — the setter is `set(value) { store(value ?: return) }`,
  so the "walk from the tip" state was unreachable by assignment and the latch had no exit at all.
  `BackfillRelatchRepairTest.assigningNullCannotReopenTheWalk` pins that, so the justification
  cannot quietly stop being true.
- `reopenPrematurelyLatchedBackfill` — a one-shot repair beside `reconcileStaleSyncCursor`, re-opening
  a `0` latch once per device. ⚠ Marker written **after** the re-open and under its own key
  (`sync_backfill_relatch_repaired`), per §11.2.1's lesson about a marker latched by the degraded path.
  It is spent even when it changes nothing, or it would re-open every device that legitimately
  completes later.

⚠ **Still open: why that device's walk latched.** The defect is real either way, but the trigger was
not established — the maintainer's hypothesis is that it relates to the folder delete Android was
still syncing at the time. Worth knowing, because it decides whether other devices are affected.

### 11.11 ⚠⚠ …and the repair for it re-walked almost nothing (2026-08-14)

§11.10's repair shipped and was measured on the same iPhone the same day. It fired correctly —
`backfillTarget` moved **500 → 19 661**, proving the walk really was re-opened and re-ran — and
recovered **nothing**. The target moving *is* the bug.

`reopenBackfill()` clears the floor to `null`, and `handover` fires on `floor == null`. So a
re-opened walk lands in the handover branch and **re-captures its target from the forward cursor**
— which, on any device that has already handed over once, is a live tail near the tip. The repair
therefore reset the walk's stopping point to almost the top of the log: it re-walked `(19 661, tip]`,
found nothing, and re-latched. The range it was re-opened to cover was never touched.

⚠ **The first capture is the real boundary.** It is where the forward pass genuinely read to before
being promoted, and nothing since changes that fact. Re-deriving it from a cursor that has since
jumped does not *observe* the boundary — it destroys it. So an existing target now wins:
`syncManager.backfillTarget ?: syncManager.lastSyncTimestamp`.

⚠⚠ **And that fix had a latent regression of its own, which the test caught before merge.** The
handover's promotion guard read `if (outcome.cursor > target)`, annotated *"Only ever forward"* —
sound only while `target` **was** `lastSyncTimestamp`. Once a re-opened walk keeps a lower target the
two diverge, and a page landing between them satisfies `> target` while sitting *below* the live
tail: the forward cursor gets dragged **backwards**. Falsification measured it at 700 000 → 650 000.
The guard now compares against the live tail explicitly, which is what it always meant.

**Lesson, and it is the same one twice in two days:** a value that is only ever *derived* from
another can be compared against either of them and nobody notices — until something makes them
differ. §11.10 conflated two facts behind one number (`cursor >= before`); this conflated two
numbers behind one name (`target` vs the live tail). Both were invisible while the coincidence held.

⚠ **Whether this recovers the missing 98 rows is unproven.** What is established is that the
previous repair provably could not reach that range and this one can. Three prior predictions of
recovery in this thread were wrong; this one is deliberately not being made.

### 11.12 The marker had to move too — and when this code should be deleted (2026-08-14)

§11.11 fixed the repair *after* §11.10's version had already been spent on the affected devices.
The marker records **"this device has run the repair"**, so the corrected code would have shipped
and **never executed** on exactly the devices it was written for. The key moved to
`sync_backfill_relatch_repaired_v2`; v1 is left as inert bytes, as `TombstoneTypeCounts` left its
superseded singleton.

⚠⚠ **The rule, stated once: when a one-shot's *behaviour* changes, its key moves too.** A marker
means "this exact work is done", not "some work by this name is done". This repo has now been bitten
by that twice (the tombstone fold, then this), and both times the symptom was silence.
`BackfillRelatchRepairTest.aDeviceThatRanTheV1RepairStillGetsTheCorrectedOne` writes the v1 key as a
**raw string** deliberately — referencing the constant would make the test follow a future rename
and quietly stop testing the migration.

**Lifecycle — what is one-shot and what is not.** Three things landed together and they do not have
the same lifetime:

| | one-shot? | when to remove |
|---|---|---|
| `reopenPrematurelyLatchedBackfill` + its marker | **yes** | once every device has launched on a build with the re-opened backward-walk fix. Pre-release that is a handful of devices, so this is *weeks*, not a release cycle. |
| `ObjectStore.clearBackfillCursor` / `SyncManager.reopenBackfill` | **no** | keep only while something calls it |
| the affirmed-bottom latch rule (§11.10) | **no** | permanent — it is the actual invariant |

⚠ Deleting the migration also deletes the only caller of `reopenBackfill`, which would leave the
repo's classic *implemented-but-called-by-nothing* wart. So they go together, or the re-open gains a
real caller first. **The durable replacement is a gap detector** — the deferred third item from this
investigation: the walk is the only thing that can know history is missing, and today it says
nothing. With a detector, a wrongly-latched walk re-opens *itself* on evidence, and one-shot repairs
with hand-bumped keys stop being needed at all. Until then each new repair costs a key bump, which
is the cheap-but-manual price of not having one.

### 11.13 Re-opening was not enough — the boundary had to be discarded (2026-08-14)

§11.11 preserved `backfillTarget` across a re-open instead of clobbering it. Correct, and still not
sufficient: on the device it was written for, the value it faithfully preserved was **already the
corruption**.

Three builds, one afternoon, one iPhone:

| build | `backfillTarget` | result |
|-------|------------------|--------|
| before | **500** | the genuine boundary |
| §11.10 repair | **19 661** | re-open re-entered handover and clobbered it |
| §11.11 repair | **19 661** | preserved faithfully — walk met the forward pass at once |

The console said it plainly: `backfill complete (met the forward pass at 19661)`. The original 500
was overwritten **in place** and nothing records its history, so `(500, 19 661]` had been put beyond
the reach of any walk — by the repair meant to reach it.

**So the boundary is now discarded, not preserved.** A device this repair fires on latched under a
rule we no longer trust; its recorded boundary is not evidence of anything. `backfillTarget = 0`
makes the walk descend the whole log and stop only at the bottom. Bandwidth, bounded by log length,
once per device — ingest is HLC-guarded and idempotent, so re-applying cannot corrupt anything.

⚠ **`0`, never `null`.** Null means "no boundary recorded", which sends the handover back to
`backfillTarget ?: lastSyncTimestamp` — re-capturing from the live tail and clobbering it a *third*
time. `theBoundaryIsZeroedNotNulled` pins that distinction, because the two look interchangeable and
are not.

⚠⚠ **The pattern across §11.10–§11.13 is one bug wearing four costumes**: a value that is only ever
*derived* from another (`cursor` from `before`; `target` from the live tail; the marker from the
repair's name) can be compared against, or written from, either one — and nobody notices while the
two coincide. Each fix removed one coincidence and revealed the next. What would have ended it after
the first is a repair that **checks its own work**: every generation here fired, latched its marker,
and reported success without ever establishing whether the device gained a single row. That is the
gap detector §11.12 names, and it is worth more than the next repair in this sequence.

---

## 12. Options for making catch-up verifiable (2026-08-14, none built)

Four defects in one afternoon (§11.10–§11.13) all landed in the same place — not in *fetching*
history, but in the client's **bookkeeping about how much of it it has**. This section records the
options considered afterwards so the next person does not re-derive them. ⚠ Nothing here is built
or scheduled; §12.4 is the recommendation, not a decision.

### 12.1 Ground facts the options rest on

**History on the relay is durable.** `MailboxStore.gcSweep` is the *only* deletion path, and it
iterates **tombstoned targets only**: it drops a target's body bytes once every realm device has
ack'd the tombstone **and** the retention window has elapsed (default 30 days,
`DEFAULT_RETENTION_WINDOW_MS`; `Long.MAX_VALUE` = never). The tombstone marker survives, and dropped
ids are remembered in `droppedBlockIds`.
⚠⚠ **A block for an entity that was never tombstoned is never dropped.** So "the relay lost it" is
not an available explanation for missing history, and was falsified as one on 2026-08-13.

**The client-side sweeps are unrelated to this.** G1 (`RetiredBlockDisposal`, delete a block row when
it leaves the push queue) and G3 (`RetiredBlockSweeper`, the bounded sweep of pre-G1 leakage) are
local storage hygiene on the device that *queued* the block — 77 800 rows of which 101 were
referenced, before G1. Neither affects what a peer can pull.

**The snapshot is deliberately not a catch-up mechanism.** `SliceSnapshotWriter`: *"Never
correctness-bearing… nothing depends on one existing."* `SliceSnapshotReader` refuses to advance the
cursor to `asOfSeq`, because a per-slice snapshot covers one slice and everything else below that seq
would become "silently unreachable forever". Its job is **content now**, not completeness.
⚠ It is also *gated on* backfill: `bootstrapOnce` is skipped when `backfillCursor == 0`. On the
device in §11.10 the premature latch therefore disabled **both** mechanisms at once.

### 12.2 ⚠⚠ We already jump — the walk is a promise nothing verifies

The load-bearing observation, and the reason §12.3's options are less exotic than they look.

`backfillOnce`'s handover **already moves the forward cursor to the top of the first backward page**
(`SyncScheduler.kt`, *"promote the forward pass to a LIVE TAIL"*). Everything below that seq is
declared the backward walk's responsibility. So the architecture does not avoid cursor jumps — it
performs one on every joining device.

What differs between the jump we do and the jump `SliceSnapshotReader` refuses is only **what backs
it**: the walk claims it will descend to `backfillTarget`, and the snapshot could claim only one
slice. Both are promises; one is bigger.

⚠⚠ **And nothing checks the promise is kept.** That is precisely §11.10: the walk latched early, the
jump had already happened, and the range between them became unreachable with no symptom anywhere.
The bug was never the jump. It was a jump backed by an unverified claim.

### 12.3 The options

**Option A — make completeness verifiable (a gap detector).** The relay reports something the device
can check its own coverage against — a seq range, or a count per owner. The device compares that
against what it has applied and re-opens the walk on evidence rather than on a hand-bumped marker.
- Cheapest of the three; additive; no change to how history is fetched.
- Compatible with HR #1: sequence numbers and counts are relay bookkeeping, not plaintext — the relay
  already assigns and pages by them.
- ⭐ Would have caught **all four** of §11.10–§11.13 on the first tick, and would retire the one-shot
  repairs entirely (§11.12's "when to delete this code" question answers itself).

**Option B — ask the relay instead of tracking locally.** The device sends a watermark and the relay
computes the delta. Moves the fragile two-cursor arithmetic server-side, where there is one authority
instead of a converging pair.
- Removes the whole class of bug rather than detecting it.
- ⚠ Bigger wire change, and it makes the relay stateful about per-device progress — check against the
  threat model before assuming it is free.

**Option C — a complete (not per-slice) snapshot whose `asOfSeq` the cursor may jump to.** Then a
joiner needs no walk at all.
- §12.2 is why this is coherent: we already jump, so the question is only what backs the jump. A
  whole-realm snapshot backs it completely; today's per-slice one cannot, which is exactly what
  `SliceSnapshotReader`'s KDoc says.
- ⚠ Most expensive: producing, sealing and storing a whole-realm snapshot, and keeping it fresh.
- ⚠⚠ **And it is not safe before Option A — see §12.4.**

### 12.4 Recommendation: A, and C is *gated on* A rather than merely dearer

**Do Option A first, and do it before any further repair to the walk.**

⚠⚠ **The decisive argument is not cost, it is that C's correctness currently rests on the exact flag
that failed.** `SliceSnapshotWriter` refuses to publish unless
`isCaughtUp() = syncManager.backfillCursor == 0L`, and its own KDoc states the stake:

> A snapshot's `asOfSeq` is a promise: *"everything at or below this seq is already reflected in
> these entries, so a reader may skip it."* … Publishing then would hand a joiner a snapshot that
> tells it to skip history nobody has read, and **the joiner would silently miss rows it can never
> learn about.**

On the device in §11.10, `backfillCursor == 0` was **true for weeks while 98 rows were missing**.
That device would have published a snapshot claiming completeness. Today the damage is bounded
precisely because slices are *not* correctness-bearing (a bad slice costs a joiner an ordinary
relation read) — and **Option C is the change that makes them correctness-bearing.** It would
convert one device's silent gap into a gap inherited by every future joiner, permanently, with the
same absence of symptoms that made §11.10 take weeks to notice.

So the ordering is not a preference: **A makes C safe; C before A propagates gaps.**

**What A costs, concretely.** No per-entity accounting is needed — that is what keeps it cheap. The
relay reports its **lowest retained seq for the owner** (one `SELECT MIN(seq) WHERE owner = ?`; the
relay already assigns seqs per owner in SQLite) and the device asserts its own coverage reaches that
low. One integer on the existing `SyncLogResponse`. ⚠ No plaintext is involved, so HR #1 is
untouched: seqs are relay bookkeeping, and the relay already assigns and pages by them.

It also composes with what exists rather than replacing it: `backfillTarget` and the two cursors
already *describe* a coverage range — they are merely implicit, unverified and individually mutable.
A makes that range explicit and checkable, which is the one property missing from all four of
§11.10–§11.13.

**What A pays for immediately.** After four merged fixes, nobody can answer *"is this device missing
history?"* for a real phone without pulling its store and decoding it by hand — which is how every
finding in §11.10–§11.13 was actually made. A turns that into something the device answers about
itself, every tick. ⭐ It also retires the one-shot repairs and their marker bumps, so §11.12's
"when do we delete this code" answers itself.

⚠ A and B do **not** compose: B removes the arithmetic A would check. Pick one.
