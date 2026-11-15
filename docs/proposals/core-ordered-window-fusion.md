# Ordered-window fusion — top-N pushdown for `sortBy(key).take(n)`

**Status:** **F1 IMPLEMENTED 2026-07-25** (same-day as the Draft v0.1 design; as-built deltas in
§8). Follow-on from the Fit slow-load investigation ([`fit-read-scaling.md`](fit-read-scaling.md)
§Latency); direction reviewed with the maintainer (walker peephole over a combined operation or
impl-level lookahead). F2 (consumer changes) optional; F3 (ordered index) stays deferred.
**F4 (filter-aware fusion) IMPLEMENTED 2026-10-01** — `[traverse()] · filter* · sortBy · window ·
select` now fuses too; as-built and measurements in §9.

---

## 8. As built (F1, 2026-07-25)

`OrderedWindowFusion.kt` (`:core-common`, proxy package) + the gate branch in
`evaluateCallstack` beside the sibling fusions; `parseSortKeys`/`compareSortKeys` extracted
from `SortByImplementation` as shared top-level internals so fused and unfused paths parse and
compare identically. Deltas from the design sections below:

- **The single pass is eager, not streaming.** §3.2 assumed `getArrayData`'s `Sequence` was
  lazy; `DatabaseImpl` deliberately materialises inside its lock (race-avoidance vs concurrent
  writes), so the pass holds all N *thin* rows briefly. The bounded heap still caps what the
  sort keeps and skips the full O(N log N) sort, but the honest F1 win is the **projection**
  (no relations, no unprojected scalar fields, no per-row callstack interpreter) — not
  resident-set O(k). A lazy store cursor would be an F3-adjacent follow-up.
- **Value-property keys ride a new `Projection.keepProperties`** (`Set<String>`, default
  empty): the one sanctioned exception to projection-strips-relations. `DbEntryMapper` keeps
  only the named **scalar** relation shapes (`ValueSchema` / `ConstantSchema`); composite
  relations never appear (the gate rejects composite keys). Every existing caller passes the
  default ⇒ byte-for-byte previous behaviour.
- **Optional fields fall through** (gate 3 refinement): the unfused per-element read
  (`Composite.getField`) rejects optional fields, and the fusion must not be more permissive
  than the path it replaces.
- **`thenBy` is supported** (Q1 resolved — the bounded stable selection took it without
  fiddliness): all keys, primary and secondary, must individually pass gate 3.
- **Q2 (heap-size guard): none shipped**, as proposed — `keep = offset + limit` clamped to
  `Int.MAX_VALUE`; a huge `take` degrades toward today's cost, still on thin rows.
- **Tests** (`OrderedWindowFusionTest`, 11 cases): fused ordering/window/stability/`thenBy`/
  property-key/`take(0)`/stream-ref-recording, plus fall-throughs (no select, unbounded
  window, op ahead of sort, multi-step selector) pinned via counting delegates on
  `SortBy`/`Drop`/`Take` — the same recipe as `EvaluateCallstackWindowFusionTest`. App-level
  parity: the untouched Fit pipeline suite now runs its real chain through the fused path,
  including the reactive-stream test.
- **Gate 3 widened by three key kinds (2026-09-15, `drive-sorted-folder-paging.md` SP-1/SP-2/
  SP-4).** `classifySortKey` accepts, beside a stored field and a value property: a **type test**
  (`sortByDescending { isA<Folder>() }` — the new `:core` op `IsA`, read off the row's own type id
  and memoised per distinct type), a **field through pure scalar ops** (`path.lowercase()` — the
  op's own `lowercaseLogic`, from a one-line allowlist `PURE_SCALAR_TRANSFORMS`), and a **value
  property on a composite-typed array** (`root.files`), which used to be refused. ⚠ The last one is
  not a gate change but a mapper rule: a projection that keeps properties makes `DbEntryMapper`
  read the row **as an entity** whatever the reference says (a composite has no properties map to
  keep them on). ⚠⚠ Hence `readSortKey`'s type test takes the id from `Entity.type` for an entity
  and from `schema` for a composite — the same date/size chain reads both shapes, and an entity's
  `schema` is always `Entity.Schema`. Tests: `OrderedWindowFusionTest` is 20 cases; the
  composite-property case pins **one store read per row** through a counting `ObjectStore`,
  because a row assertion passes fused or not.
  ⚠⚠ **And a `thenBy` run folds first** (`foldSortByRun`, same day): the client's `thenBy`
  *appends* a full-key `SortBy` and leaves the prefix op on the chain, so a tie-broken sort
  reached the gate as `SortBy(A) · SortBy(A,B) · window` — and the gate, expecting a window after
  its `SortBy`, fell through on every one of them. A run of consecutive `SortBy` ops in which
  each extends the last now collapses to its last member before the gate; unrelated
  consecutive sorts are not a run and stay unfused (`drive-sorted-folder-paging.md` §3 B6).

---

## 1. Problem

Every sorted feed in the platform executes as *materialise everything, then sort, then discard
almost everything*:

- `SortByImplementation` resolves its receiver via `resolveList` → `getArrayData(ref)` with **no
  projection** — every row is fully materialised (all stored value props), regardless of what the
  downstream `select` asks for.
- The sort key is then extracted **per element**: a value-property selector is a direct
  `getProperty` read, but a lambda selector (`sortByDescending { start }`) is an interpreted
  `evaluateCallstack` walk per row — and the lambda form is what Fit is forced onto by the §9.17
  property-selector wire bug (`fit-app.md`).
- Only *after* the full sort does `take(n)` discard the rest.

The engine's existing windowing optimisations don't help, by design: window fusion and the
`captureProjection` pushdown in `evaluateCallstack` fire only for a `drop`/`take` prefix on a
**raw** array reference. A sort-first chain gets neither — `sortBy` must see every row, so it
can't sit below the window (`core-chain-sort.md` §2.3; pinned by `FilesListingEfficiencyTest`'s
"op ahead of drop/take" regression).

Consequences, today:

- **Fit** (`FitProjectionRepository.buildRecentWorkoutInputs`):
  `workouts.sortByDescending { start }.take(300).select {…}` walks the whole store — O(N) full
  materialisation + O(N) interpreted key extraction — on first subscribe *and on every reactive
  re-run*. Phase 1b (shareIn) removed the multiplication across screens; the per-run cost stands.
- **Drive** (`TenshuFilesRepository.getFilesPaged`): a *sorted* listing deliberately punts to
  whole-folder-single-page because sort defeats windowed paging. Tolerable per-folder; the
  documented dead end all the same.
- **Control** (`TenshuEventRepository`): `.sortByDescending { timestamp }` over the event log.
- Any future feed (Timeline, notifications) has the same shape: newest-first + a window.

The insight from the Fit investigation: **the sort must see every row's *key*, but today it
materialises every *row*.** Closing that gap is a generic engine win, not a Fit fix.

## 2. Prior art — this is the fourth member of an existing family

`evaluateCallstack` already implements three peephole fusions, all with the same anatomy
(`core-common/.../EvaluateCallstack.kt`):

| Fusion | Trigger | Fused execution |
|---|---|---|
| Window fold | `drop`/`take` prefix on a raw array | one `getArrayDataWindow(offset, limit)` read |
| Count short-circuit | `count()` on a raw array | member-hash list size, zero deserialisation |
| By-key search | `search { keyField equalTo K }` on a raw array | content-addressed point read |

Shared anatomy — and the contract this proposal inherits wholesale:

1. **Gate on a raw receiver:** `currentData is ArrayDatabaseReference` with
   `operationContext.executionStack.isEmpty()` — the accumulated deferred stack is the "look
   backwards"; empty means nothing has transformed the array yet.
2. **Lookahead into `remaining`** for the rest of the fusable shape (the same mechanism
   `captureProjection` uses to find a downstream `select`).
3. **Fall through on any gate miss** to the unchanged materialise path — fusions change *cost*,
   never *semantics*.

The alternatives considered and rejected (maintainer discussion 2026-07-25):

- **A combined operation** (`topN(key, n)` as a schema op): every call site changes, a new wire op
  raises peer-compat/lockstep questions, and it leaks an engine optimisation into the API. The
  chain DSL's promise — set by the three existing fusions — is that callers write the semantic
  composition and the engine recognises the cheap execution. Nobody had to call `countFast()`.
- **Generalised lookahead via `OperationContext`** (implementations see the downstream ops and
  fuse themselves): moves optimisation decisions out of the one place that holds them today
  (the walker) and into impl bodies, duplicating the gates. Not warranted at four fusions.

## 3. Design

### 3.1 Fusion gate

Fire the fused path when **all** hold; otherwise fall through to `SortByImplementation`
unchanged:

1. `!allowDeferring` (execution walk, same as the sibling fusions).
2. `currentData is ArrayDatabaseReference` with an empty `executionStack`. Note this can hold
   **mid-chain**, not only at the chain head — an upstream op that returns a fresh raw reference
   (e.g. a folder navigation) re-arms the gate, exactly as it does for the sibling fusions.
   An upstream `filter`/`traverse` that *defers* onto the stack disarms it (fall through) — that
   shape is F4's (§9), which gates on the `traverse`/`filter` op itself.
3. `protocol.id == SortBy.ID`, and every selector — primary and each `thenBy` — is
   **single-step**: one `StaticPropertyRelation` (the property overload) or one field
   `Reference` (the `{ start }`-shaped lambda). Multi-step selectors, selectors containing
   operations, and async selectors fall through.
4. `remaining` begins with a foldable window per `foldWindowPrefix` **with a finite limit** —
   at least one constant, non-negative `take`. A `drop`-only suffix has no bound, so top-N
   degenerates to a full sort: fall through.
5. `remaining.none { it.isStream() }` — same rationale as the sibling gates (a downstream
   stream op needs the reference, not a materialised window). Note the **reactive path still
   benefits**: `StreamRegistry.onUpdate` re-evaluates the *deferred stack* (which contains
   `sortBy`/`take`/`select` but not the stream terminal) against the bare reference through this
   same walker, so every stream re-run takes the fused path. This is the same free ride the
   page-load streams get from window fusion (`StreamRegistry.kt` comment at the evaluation site).

### 3.2 Fused execution (v1: single streaming pass, bounded heap)

```
keys      = sort-key field/property ids (primary + thenBys)
window    = foldWindowPrefix(remaining)            // offset, limit — finite by gate 4
projection = captureProjection(...) ∪ keys         // the select's projection, widened by the keys
rows      = getArrayData(reference, projection)    // a Sequence — stream it, don't collect it
winners   = boundedStableTopN(rows, comparator, offset + limit)
result    = winners.drop(offset)                   // remaining window ops consumed, as WindowFold does
```

- **One pass, not two.** A two-pass shape (key-only scan → point-re-read the winners with the
  full projection) reads less per row but adds a re-read path and a consistency window between
  passes. The single pass with the *merged* projection already removes both dominant costs —
  full-row materialisation (thumbnails/blobs/value props the select never asked for) and the
  per-row callstack interpreter — and `getArrayData` returns a `Sequence`, so with a bounded
  heap the resident set is O(offset + limit), not O(N). Two-pass is a measured follow-up, not v1.
- **Key extraction** on the projected row: `StaticPropertyRelation` → `getProperty`; field
  reference → direct field read. No `evaluateCallstack` per element on the fused path.
- **Comparator**: reuse `compareKeys` verbatim — same type support (`TInteger`/`TString`/
  `TBoolean`, `Instant` via `TInteger`), same missing-key ordering (nulls last ascending), same
  typed errors on non-comparable keys. Error parity is part of the semantics contract.
- **Stability**: `sortedWith` today is stable; the bounded selection must be too. Tie-break by
  input index (track it alongside the heap entries); equal-key rows come out in encounter order.
- **Window consumption**: like `WindowFold`, the fused path consumes the folded `drop`/`take`
  ops from the callstack so they don't also run in-memory; the downstream `select` then sees
  only the ≤ limit winners (and its projection was already applied at read time).

### 3.3 What this deliberately does NOT do

- **No wire, schema, op, or client change.** The fusion is proxy-side walker behaviour;
  callstacks on the wire are unchanged. ⇒ no `TENSHU_STORAGE_FORMAT_VERSION` bump, no relay
  impact, no lockstep concern (HR #9 untriggered).
- **No sub-O(N) ambition.** Rows are serialised blobs in `SqliteObjectStore` — fields are not
  columns, so there is no `ORDER BY` to lean on. O(N) *thin* reads is the floor without a
  maintained ordered index (key → DataId), which is **Phase F3, explicitly deferred** until a
  profile shows the thin scan itself as the bottleneck. Do not build it speculatively.
- **No filter pushdown** — *in F1.* `filter { … }.sortBy(k).take(n)` falls through gate 2 here.
  ✅ Built later as its own peephole, gated on the `traverse`/`filter` op instead of the sort:
  **§9 (F4)**.
- **No composite-key sort.** `compareKeys`' composite branch stays an error (core-chain-sort
  §6.7), fused and unfused alike.

### 3.4 Relation to §9.17 (property-selector wire round-trip)

The fusion accepts *both* selector forms (gate 3), so the lambda workaround Fit ships today
fuses just as well as the property form would. §9.17 thereby stops being performance-critical
and becomes a plain API-consistency fix, schedulable independently.

## 4. Beneficiaries (no changes required at these call sites)

- **Fit** `buildRecentWorkoutInputs`: `sortByDescending { start }.take(300).select {…}` —
  fires as-is; first paint and every reactive re-run drop from O(N) full rows to a streamed
  thin scan + 300 materialisations.
- **Drive sorted listings**: `getFilesPaged`'s sorted branch can move off
  whole-folder-single-page onto real windowed paging (`applySort(sort).drop(pos).take(n)`), each
  page a bounded top-(pos+n) over thin rows. (Paged *natural-order* listings keep the existing
  window fold — this fusion only fires when a sort is present.) Follow-up in `:drive-shared-logic`,
  not part of this proposal's core slice.
- **Control** `TenshuEventRepository` (`sortByDescending { timestamp }`) — fires once a window
  is appended; unbounded event reads should gain a `take` anyway.
- ✅ **Drive Home recents** (`traverse().sortByDescending(File.LastModifiedAt).take(n).select`)
  and **the Photos feed** (`traverse()` + three filters + sort + window + select) — F1 alone never
  fired for either (`traverse`/`filter` defer onto the stack ahead of the sort, gate 2 falls
  through); F4 (§9) fuses both.

## 5. Testing

- **Gate unit tests** (mirror `foldWindowPrefix`'s): each gate condition individually
  disarms the fusion; the armed shape fires.
- **Semantic parity**: fused vs. unfused results identical over fixtures with duplicate keys
  (stability), missing keys (nulls-last), mixed `thenBy` directions, `drop+take` compositions,
  and the typed error on a non-comparable key.
- **Efficiency pin**: extend the `FilesListingEfficiencyTest` recipe — assert the store sees no
  full-row materialisation for unprojected value props under a fused `sortBy.take.select`
  (the "op ahead of drop/take" regression gains a green sibling instead of a documented dead end).
- **Reactive**: a stream over `sortBy.take.select` re-runs through the fused path and emits the
  same diffs as before (StreamRegistry-level test beside the existing signal-only coverage).
- **Fit pipeline**: `FitPipelineIntegrationTest` already covers the consuming chain end-to-end;
  it must stay green untouched — that *is* the fall-through/parity proof at app level.

## 6. Phasing

- **F1 — the fusion** (`:core-common`): gate + bounded stable top-N + tests above. One slice,
  no dependents.
- **F2 — consumers** — **PARTIALLY SHIPPED 2026-07-29.** The primitive landed in `:core-paging`:
  `TenshuPagingSource(orderedSource = true)` cuts an ordered window as `take(pos + n).drop(pos)`
  instead of `drop(pos).take(n)`. Same elements, but only that order is a *leading finite* `take`,
  so only that order lets F1 fuse — an ordered page otherwise re-sorts the fully materialised list
  every load. Opt-in: the unordered default stays O(n) per page rather than O(pos + n).
  `TenshuPagingSourceTest` pins the window **shape**, since a row-only assertion passes either way.
  - **Control's event read: done.** `TenshuErrorEventsManager.events()` was unbounded, unprojected
    and sorted client-side; it is now `sortByDescending { capturedAt }.take(500).select { … }`.
    The error log is append-only with no eviction yet (`core-error-channel.md` Phase 5b), so a
    diagnostics screen that slowed down as errors accumulated was the wrong failure mode.
  - ✅ **Drive's sorted listing pages (2026-09-15).** `foldersFirst` was one of **four** blockers,
    not the only one: F1 also refused value-property keys on `File` rows (`File : Composite`, so
    the array is not `isEntity`), refused the multi-step `name.lowercase()` key, and offset pages
    drift under a newest-first sort. The three gate-side blockers closed in §8's last bullet; the
    Drive consumer, the projected change observer and the drift handling are
    `drive-sorted-folder-paging.md` §10.6. ⚠ The two-source design once proposed here does not
    escape the gate — both segments are filtered chains, and a filtered `count()` misses the count
    short-circuit. ⚠ `orderedSource` is retired with it: the order rides `TenshuPagingSource`'s
    `ordering`, applied ahead of a plain `drop(pos).take(n)`.
  - Fit needs nothing.
- **F3 — maintained ordered index** (deferred indefinitely): key → DataId index for sub-O(N)
  sorted windows. Gated on a profile showing the thin scan as the residual bottleneck.
  - **First named consumers (2026-07-30), and they are not the profile this phase expected.**
    `timeline-recognition-and-place-naming.md` §10.9/§10.12 has two passes blocked here: **P4e-2**
    (moment dedup in the proxy — "which moments are near this one in time?") and the **time
    corroboration** that would arbitrate an ambiguous place merge ("do these two rows' visits
    abut?"). Neither is blocked by the scan being *slow*; both are blocked because **there is no
    bounded read reachable from where they stand at all** — see the ⚠⚠ below. That is different
    evidence from "profile the thin scan", and arguably stronger for scheduling.
  - ⚠⚠ **F3 as specced would not serve them, and this is the part to fix when it is scheduled.**
    Both consumers are proxy-side `DataObserver`s reading `database.getArrayData`, which returns
    the whole relation and **bypasses the chain evaluator entirely** — where F1 lives and where F3
    would live. An ordered index under `evaluateCallstack` helps chain readers (ops, clients); it
    does nothing for an observer. F3's scope therefore needs a **proxy-side bounded/ordered read
    entry point**, not just the index, or it will land and still not fit the consumers that
    motivated it.
  - ⚠ Corollary worth knowing before then: F1 *already* makes a chain-side "most recent N" read
    affordable (thin projections, no per-row interpreter), so a consumer that can reach the chain
    is likely unblocked today. The 10.5k-workout incident was full materialisation, which F1
    removed.
  - ✅ **RESOLVED 2026-07-30 — and F3 itself was not needed.** Chasing the two consumers showed the
    blocker was only ever the *entry point*: they need the fused window's **engine**, not a smaller
    complexity class. `ExecutionContext.readOrderedWindow(reference, orderByFieldId, limit,
    descending, keepFields)` now offers it directly — same thin read, same [compareSortKeys], same
    bounded stable top-N, same nulls-last and encounter-order stability as the chain path, because
    it shares the implementation rather than duplicating the semantics.
    - ⚠ **Deliberately not callstack synthesis.** Hand-building `sortBy`/`take`/`select` protocol
      DTOs to re-enter the evaluator would put the gate's encoding (selector shapes, window folding,
      projection capture) on a caller that already holds all three inputs — and a subtle mistake
      there is the repo's recurring opaque-`error/internal` class. Share the engine, not the
      protocol.
    - ⚠ **F3 stays deferred, per §3.3.** This is still O(N thin reads + n resident); the win is the
      projection and the absent per-row interpreter. If a profile ever shows the thin scan as the
      bottleneck, F3 slots underneath `readOrderedWindow` without touching its callers.
    - Tests: `ReadOrderedWindowTest` (7), asserting the same windows `OrderedWindowFusionTest`
      pins for the fused chain — two implementations of one semantics is how they drift.

### 6.1 The scan pages across store accesses (2026-11-12)

`readOrderedWindow` bounds the **selection**, never the scan: it reads every member of the relation
thin. Until 2026-11-12 that scan was **one store access** — `DatabaseImpl.readArray` resolved every
member inside one `lock.withLock` — and on the iOS SQLite replica one outermost access is one
`BEGIN IMMEDIATE` transaction under the hold watchdog's `HOLD_MAX` (1 s,
`ios-shared-store-sqlite.md` S2, §4.2.1). At ~0.11 ms per statement and ~2 statements per member
(debug simulator) a relation past ~4–5k members, read whole by an observer pass
(`MomentReconciliationPass`, `PlaceRoleInferencePass`, `VisitTimeCorroboration`), a slice encode or a
listing, held the write lock past `HOLD_MAX` and fenced the store `COMMIT_FAILED`. ⚠ `HOLD_MAX` is
not the knob — it is never raised.

**As built.** Every relation read in `DatabaseImpl` — `getArrayData`, `getArrayDataWindow`, both
with and without the cancellable-read checkpoint — goes through one paged reader
(`DatabaseImpl.readArrayWindow`):

1. The **first access** reads the array's row, **snapshots** the window's member hashes, and
   resolves the first page.
2. Each **later access** resolves the next page of that snapshot (`DbEntryMapper.deserializeMembers`).
   A page is at most `ARRAY_READ_PAGE_SIZE` = 512 members (~0.1–0.2 s on a debug simulator).
3. A relation of at most one page is still exactly **one** access, as before.

So the bound holds for every caller **outside a transaction** with no call-site change, including
this doc's chain fusion (§3.2) and `readOrderedWindow`. ⚠⚠ Not for a read inside one — see the
transaction point below, which names a live production path.

⚠ A page is capped by **member count**, not cost: 512 assumes ~2 statements per member. A relation
whose members are composites with sizable inline lists or `genericData` children costs more per
member, and one page of those can still approach `HOLD_MAX`.

⚠⚠ **What a write between two pages can do to the result** — nothing else is possible:

| Concurrent write after the snapshot | In the result |
|---|---|
| member **added** | absent — as from a one-access read just before the add |
| member **removed**, row kept | present — the snapshot's view |
| member **removed** and row **deleted** | skipped; **not** reported as store corruption (the page re-reads the array and counts only misses that are still members) |
| member's row **updated** | present, as of its page — newer than the snapshot |
| member **replaced** atomically (remove A + delete A's row + add A′, one transaction) | **neither** A nor A′ — A's row is gone, A′ was added after the snapshot |
| the **array row** itself gone (relation deleted, or an iOS wipe fenced the handle) | empty — never the pages already read passed off as the whole relation |

**Never a member read twice, never a member lost that stayed a member throughout.** That is why the
reader snapshots hashes instead of re-slicing `[offset, offset + page)` against a list that moved:
an add or removal ahead of the cursor would shift it, and a re-sliced read would then duplicate or
skip a stable member. No dedupe is needed because nothing can duplicate.

**Why every caller tolerates it.** The result is *nearly* a one-access read taken at the snapshot,
with some members fresher than it — ⚠ with one exception no single-instant read could produce: an
atomic replace between pages can drop **both** versions (table above). So a caller that **acts on
a member's absence** (sweeps, keep-sets) must read inside `transaction { }`; the ones that exist do
(`RetiredBlockSweeper`'s keep-set) or tolerate a miss (`OutboundQueuePurge`,
`PendingBlockRestamper`). Every caller already lives with a result that is stale by the time it
returns — nothing holds the lock between `getArrayData` and the caller's next step — so beyond that
the new thing is **skew between members** (A as of page 1, B as of page 3):
- **Observer passes and `readOrderedWindow` consumers** (the three Timeline passes): they are
  re-triggered by the very write that made the skew, and they derive state from the members one by
  one (a dwell per visit, a duplicate pair), so a fresher member is the next pass's input anyway.
- **Listings and streams**: the stream re-evaluates on the array's write, which is the write that
  caused the skew.
- **Read-filter-rewrite callers** (a queue drain, a relation repair): if their step needs members
  mutually consistent, they need `transaction { }` around read **and** write already, or they race
  today. Inside a transaction see the next point.

⚠ **Inside an outer lock** (`transaction { }`, or any caller already inside `lock.withLock`) every
page re-enters the held lock, so no writer runs between pages and the result is exactly what one
access returned before — `PagedArrayReadTest.insideATransactionTheReadIsTheTransactionsOneAccess`.
⚠⚠ The flip side: a relation read **whole inside** a transaction is still O(members) of **that one**
transaction on iOS. Paging cannot help it; the caller must not do that on a relation that can grow.
The one live path that did — a synced `Place` reaching `PlaceReconciler` →
`VisitTimeCorroboration` → `readOrderedWindow` over all of `MOMENTS` inside
`SyncProcessor.resolveConflictAndSave`'s transaction — now records the anchor only and reconciles
coalesced on `sessionScope`, after the commit, like `MomentReconciliationPass` and
`PlaceRoleInferencePass` (`PlaceReconcilerTest.aSyncedPlaceIsNotReconciledInsideTheIngestTransaction`).
⚠ Other in-transaction reads that grow with the relation remain — `FolderMerger`, the File-delete
cascade, `RelationMaintainer.memberCount` — listed in `tech-debt.md` *"Synchronous observers and
mergers that read a growing relation inside a transaction"*.

**Tests.** `PagedArrayReadTest` (`:core-common` commonTest): a counting lock numbers outermost
accesses and a counting store attributes each member-row read to one, asserting no access resolves
more than a page; the same rig runs a write between two pages. Fails 4/6 on the one-access read.
`WholeRelationReadSharedReplicaTest` (`:tenshu-sdk-ios-core` iosTest, macOS only) reads a
20k-member `MOMENTS` relation over the real `SharedReplicaStore` and asserts the store stays
unfenced, with a control that the same read inside one transaction is refused past `HOLD_MAX`.

## 7. Open questions

- **Q1** `thenBy` in v1: the design supports it when all keys are single-step; if the bounded
  stable selection gets fiddly, shipping F1 primary-key-only (thenBy → fall through) is an
  acceptable cut — Fit and Drive both sort single-key.
- **Q2** Heap-size guard: `take(huge)` makes offset+limit ≈ N and the fused path approximates
  today's cost (plus thin rows — still a win). No guard proposed; noting it for the reviewer.
- **Q3** Should the fused path emit a debug-level trace (rows scanned vs. materialised) through
  the logging facade once that lands (`core-logging-facade.md`)? Cheap observability for F3's
  "is the thin scan the bottleneck" question.

## 9. Filter-aware fusion — as built (F4, 2026-10-01)

**Why.** The Photos feed page is `root.files.traverse().filter{isMedia()}.filter{createdAt.inRange(0,
end)}.filter{notTrashed()}.sortByDescending(File.CreatedAt).drop(skip).take(n).select{…}`. Every op
ahead of the sort *defers* onto the execution stack, so by the time F1's gate sees `SortBy` the
receiver is a materialised `TList`, not a raw reference: every page materialised the whole realm,
filtered it and fully sorted it. A device measurement (`photos-app.md` §37) put a page at
~1.6–2.1 s on a ~1,400-photo library, ~1.1–1.5 ms per library file per page, nearly independent of
page size. The feed's own comment claimed the window "fused into the sort (F1)"; it never had.

### 9.1 Gate and execution

`FilteredWindowFusion.kt` (`:core-common`, proxy package) + a fifth gate branch in
`evaluateCallstack`, beside F1's. It fires on the op that would otherwise *defer* — `traverse` or
`filter` — when the receiver is a raw `ArrayDatabaseReference` with an empty stack, `!allowDeferring`
and no stream op downstream (F1's gates 1, 2 and 5, unchanged), and the lookahead finds:

```
[traverse()] · filter{p1} · … · filter{pN} · <SortBy run> · <finite drop/take window> · select{…}
```

(at least one filter when there is no `traverse` — the bare sort stays F1's). The sort keys, window
and select go through **`planOrderedWindow`**, extracted from F1 so the two fusions cannot disagree on
which keys fuse, how a window folds or how ties break; the `thenBy` run folds first
(`foldSortByRun`), as for F1.

Execution is **one walk, push-fed into the same bounded stable top-N** (`BoundedStableTopN`, F1's
heap made push-shaped so a recursive walk can feed it without collecting its output first):

- every array the walk reads — the root, and under `traverse` every folder's list field — goes
  through `ExecutionContext.getArrayData` with one projection: the select's, **all fields kept**
  (`skipUnprojectedComposites = false`: predicates, keys, the DataId derivation and the descent all
  read fields, and fields are the cheap part of a row), relations stripped **except** the value
  properties the predicates and keys read (`keepProperties`);
- under `traverse`, rows are visited in `recursivelyWalk`'s order (item, then each `TList` field's
  members, depth-first, schema-field order), descending through the **same** helper
  (`nestedListFieldValues`, extracted from `recursivelyWalk`). Leaf rows (no `TList` field in their
  type) skip the skeleton re-read `recursivelyWalk` pays for every row;
- each row is tested against the predicates in order, short-circuiting; survivors are keyed and
  offered to the heap with an encounter index counted over *survivors* — which is the unfused order,
  since `filter` preserves order and the sort is stable;
- the folded window ops and the sort run are consumed; the `select` sees only the winners.

### 9.2 What a predicate may be (and everything else falls through)

`classifyFusedPredicate`: a **head** followed only by operators from a one-line allowlist
(`EqualTo`, `OneOf`, `InRange`, `Contains`, `Lowercase`, `SubstringAfterLast`) whose every operand is
a **constant** (a value or constant, or a list of them — a `Reference`, an `Operation` or a list
holding either is a per-row callstack and is refused). Heads:

| Head | Example | Read on the thin row |
|---|---|---|
| stored scalar field (non-optional, non-parameterised) | `path` | the row's field |
| stored value property (`ValueSchema` / `ConstantSchema`, not a reference property or entity relation) | `createdAt.inRange(…)` | `keepProperties` |
| synchronous, non-deferred, non-list dynamic property with a type-independent chain whose ops carry no IO / background / stream trait | `isMedia()` (= `File.Category oneOf …`), `notTrashed()` (= `File.Trashed equalTo false`), `name.contains(…)` | `ExecutionContext.resolveDynamicProperty` on the thin row — the function the unfused read calls |

⚠ **`File.Category` is a *dynamic* property** (the `FileCategoryOf` op over the path), not a value
property — the first design assumed otherwise. Its value is computed by the very resolver the
unfused path uses, over a row that carries every field, so it is the unfused value by construction;
what the fusion removes is the row re-reads *around* it.

The operators run through the **real dispatcher** on the value read off the row, so `oneOf`/
`inRange`/`equalTo`/`contains` answer exactly as unfused. Whenever the thin read has no value for a
head (an absent property, a dynamic property that came out `null`), that row's predicate is
evaluated by `evaluatePredicateOn` — the per-row body of `FilterImplementation`, extracted so it is
the same code — and therefore returns, or **raises**, exactly what the unfused filter does.

⚠⚠ **An absent stored value property is "no match", not a raise** (`photos-app.md` §40.2, since
2026-10-01). It used to raise *"Could not resolve property reference"* — so `contentHash oneOf …` failed
on the first folder a `traverse` met, i.e. on every real realm, while `inRange` and `File.contentHash`
documented the opposite. `evaluatePredicateOn` now reads `AbsentPropertyException` as `false`; fused
and unfused agree because the fused walk hands exactly those rows to it. An unresolvable dynamic
property, reference property or entity relation still raises. ⚠ An older Control still raises — a
caller that needs to work against one must have a fallback (§40.2 has the backup skip's).

**Falls through — whole chain, never approximated:** an op on the row itself (`isA<Folder>()`), a
non-constant operand, an operator outside the allowlist, multi-step navigation, reference properties
and entity relations, deferred/IO/list-valued dynamic properties, optional or parameterised fields,
any F1-refused key, no `select`, an unbounded (`drop`-only) window, a stream op downstream, a filter
*between* the sort and the window. Two misses are only discoverable mid-walk — a nested list that is
not a store-backed array (an inline `TList`), or one whose element schema differs from the root's —
and abandon the walk (`FallThrough`) before anything was consumed, so the unfused path runs from
the start.

⚠⚠ **No navigation inside a dynamic predicate's derivation**. A reference in the
derivation may only be its first step, to a scalar, non-composite, non-optional field of the row;
reaching into a nested composite (`child.child.name`), a property reference or a composite-field head
falls through. The fused read projects only what the select asked for, so a nested composite the
select prunes would be missing when the predicate walks through it — the fused filter threw where the
unfused one answered. As a second guard the fused projection **clears the select's nested
`children`**, so a kept composite always loads whole: the unfused read has no nested projection at
all, and the select still applies its own selectors to the winners. Pinned by
`aDynamicPredicateNavigatingIntoANestedComposite_fallsThrough_evenUnderAPruningSelect`.

⚠ One residual difference, accepted: if **two different rows would raise two different errors**,
which one surfaces may differ (unfused runs predicate 1 over every row before predicate 2; fused runs
all predicates per row). Both fail the chain.

### 9.3 Reactive path

Unchanged in kind from F1 (§3.1 item 5): `StreamRegistry.onUpdate` re-evaluates the deferred stack
against the bare reference through this walker, so every stream re-run fuses. Because every array
the walk reads goes through `getArrayData`, the reference collector records **exactly the arrays the
unfused `traverse` records** — pinned by comparing both sets — so a write in any folder still
invalidates the feed.

### 9.4 Measured (JVM, synthetic 10k-file realm)

`FilteredWindowFusionBenchmarkTest` (`:file-service-proxy` jvmTest, opt-in:
`TENSHU_FUSION_BENCH=10000`), production store pair (`SqliteObjectStore` + `ReentrantLockDatabaseLock`),
10 000 files over 45 folders (70 % jpg, 10 % mp4, 10 % pdf, 10 % txt, some in `/Trash`), median of 7
after 2 warm-ups, on a loaded shared machine:

| Chain | Before (master) | After (F4) | Unfused composition, same run |
|---|---|---|---|
| Feed head page (60) | 117 ms (11.7 µs/file) | **57 ms** (5.7 µs/file) | 110 ms |
| Feed page mid-library (60) | 98 ms | **41 ms** | 92 ms |
| Drive Home recents (20) | 60 ms | **11 ms** (1.1 µs/file) | 57 ms |

The benchmark also asserts fused and unfused agree row for row. ⚠ The JVM store is warm and its
rows carry no thumbnails, so the absolute numbers say little about a phone: what F4 removes there is
the per-row **unprojected** re-reads (one per property predicate, one per sort key, one skeleton
re-read per row), which is what the device's ~1.1 ms/file was made of. The residual feed cost on the
JVM (~4.5 µs/file above recents) is the two dynamic-property predicates going through the
interpreter per row; a faster derivation is a follow-up only if a device profile says so. Re-measure
on the device before quoting a phone number.

### 9.5 Tests

- `:file-service-proxy` `FilteredWindowFusionTest` (8) — the feed chain built with the **real** DSL
  predicates: the fused path is taken (zero `Traverse`/`Filter`/`SortBy`/`Drop`/`Take` dispatches —
  ⚠ a row assertion passes either way); row-for-row parity incl. the keyset tie cursor; the stream
  path (fused, re-emits on a write three folders down, observes the same arrays as unfused); Drive
  Home recents fused + parity; a randomized property (6 seeds × {traverse, root} × 4 filter sets × 4
  key sets incl. `thenBy` × 4 windows, ties and trash) asserting fused == unfused *and* that each
  fused; fall-throughs (op on the row, no select, `drop`-only window, filter after the sort). The
  path, stream, recents and randomized tests were **red on master** (dispatch counts 1–5).
- `:core-common` `FilteredWindowFusionPredicateTest` (6) — the classifier (constant vs callstack
  operands, ops on the row, unknown heads) and the no-`traverse` shape with parity.
- F1's `OrderedWindowFusionTest` (22) and `ReadOrderedWindowTest` (7) stay green over the extracted
  `planOrderedWindow`/`BoundedStableTopN`.

### 9.6 Not done

- **`count()` and unwindowed reads over the same filters** (`feedTotal`, `feedCaptureTimes`) still
  materialise: no window ⇒ no top-N, and a filtered `count()` misses the count short-circuit. A thin
  filtered scan (this walk without the heap) would serve both; not built. ⚠ Still not built after
  `photos-app.md` §39, which measured what it costs: on the emulator (1,400 photographs) the
  scrubber's `feedCaptureTimes` is a 0.71–0.75 s read in Control, started at the same moment as
  Collections' refresh, and it slows that refresh's first page by ~0.1–0.15 s.
- ⚠ **The winners' projection used to cost one store read per projected property.** `Select`
  resolved each `relations(…)` entry with its own row read, so a 500-row window projecting eight
  properties read the store ~4,000 times after the walk. Since §39 `Select` resolves a row's stored
  value properties with ONE read (`ReferenceResolver.resolveProperties`) — the walk is unchanged, and
  so is every answer.
- ⚠ **A window is one O(realm) walk, so reading a whole library through windows is quadratic.**
  Photos' Collections now does exactly that, because one reply carrying the whole library does not
  fit through Binder (`photos-app.md` §39: 17 walks at 10,000 photographs). F3's capture-time index
  is what would make each page O(page).
- **No wire, schema, op or client change** — proxy-side walker behaviour only, like F1; HR #9
  untriggered.

### 9.7 Follow-up, not scheduled — one-sided ordering predicates, so a STRING key can be a cursor

Recorded 2026-10-02 from the bounded-replies work (`photos-app.md` §40.1); wanted, deliberately later.

**The gap.** The chain's predicates are `equalTo`, `oneOf`, `contains` and `inRange`, and `inRange` is
integers only — against text it is `false` by design (`InRangeLogic.kt`). So a walk ordered by a
string key (a path, a name) cannot say *"the rows that sort after this one"*; it can only say
`sortBy(path).drop(offset).take(n)`. An offset is a position, and a position is right only while
nothing sorting before it is inserted or deleted between two reads.

**What that costs today.** `RealmContentReads.readSortedByPath` (Duplicates, the backup's content
skip) keeps its offsets honest with an overlap: each page re-reads the previous page's last rows and
restarts the walk when they moved. That is exact only because the anchor and the new rows arrive in
ONE read. When a single row is all a Binder transaction can carry, the anchor becomes a second read,
two reads are not atomic, and a delete plus an insert before the cursor can cancel out unseen — one
row missed for that walk. The bounded-replies work (`photos-app.md` §40) ships that weaker mode rather than a permanent *"could not look"*; it
is reached only by a path of ~500 KB, which nothing limits at write time but no file system
produces.

**What to add.** `greaterThan` and `lessThan` over a value, at least for strings:

- **Both, not one.** A cursor needs the strict `>` for an ascending walk and the strict `<` for a
  descending one, and — unlike integers, where the feed writes `inRange(0, cursor)` — text has no
  greatest value to stand in for an open end, so a half-open range cannot fake either.
- ⚠⚠ **The comparison must be the one `sortBy` uses** (`compareSortKeys`), or a cursor skips or
  repeats rows wherever the two orders disagree — case, non-BMP characters, normalisation.
- ⚠⚠ **Strict versus inclusive is the whole contract.** K-4 shipped a feed whose window excluded its
  own cursor, and a mock that used `<` where production used `<=`
  ([`core-keyset-paging.md`](core-keyset-paging.md) §6.3, `photos-app.md` §23). Decide it once, name
  it in the op, and give ties a second key.
- **Absent is never greater or less** — the `inRange` posture: a row without the field drops out.
- Integers would get them too, as the honest spelling of what `inRange(0, x)` is used for now.

With it the cursor is a VALUE: inserts and deletes before it shift nothing, a page of one row is as
correct as a page of 500, and the overlap, the restarts and the one-row mode's hole all go away. It
also joins this fusion's allowlist (§9.2) for free — a constant operand, a stored scalar head.

⚠ **Why it is not a small change.** A new predicate is a new core op: HR #9 applies, the PROXY must
be rebuilt, and skew is silent — an older Control answers a chain it does not understand wrongly
(`core-keyset-paging.md` §12.5). The alternative that needs no predicate, a window capped by reply
BYTES rather than rows, is an op parameter and so a reshape all the same.
