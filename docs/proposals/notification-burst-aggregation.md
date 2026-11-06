# Notification Burst Aggregation — Producer-Side Coalescing + Paging Convergence

**Status:** Proposed 2026-07-02. **Slices 1–3 SHIPPED** — Slice 1 (producer
coalescing), Slice 2 (partial-mapper paging + invalidation
conflation + `toPagingData` → `:core-paging`), Slice 3 (`getEvents()`
convergence, 2026-07-03). As-built deltas in §5. Slice 4 (retention cap)
optional, unscheduled. Both tech-debt entries folded into a resolved note.
Supersedes the read-side
"convergence move" sketched in `docs/tech-debt.md` → *Notification
Burst-Collapsing Is Client-Side* and implements the source-side fix demanded by
the adjacent *Bulk / Backup Uploads Mint One Notification Per File* entry. Both
entries are resolved by this one design. Backward compatibility with
already-synced per-file notification rows is **explicitly not required**
(maintainer decision, 2026-07-02 — pre-release, realms can be reset).

**Scope:** `:file-service` (schema), `:file-service-proxy`
(`UploadFileImplementation`), `:core-paging` (partial-mapper support),
`:control-shared-logic` (`TenshuEventRepository` / `NotificationMapper`).
No relay, wire-format, or threat-model change (notifications are ordinary
synced app data — not A1–A4/A9; House Rule #2 not triggered).

---

## 1. Problem

A bulk upload (Synced-Folders auto-backup, folder upload) mints **one synced
`FileAddedNotification` per file** (`UploadFileImplementation`). Observed
on-device: ~920 rows from one photo backup. Costs, in order of severity:

1. **Unbounded synced rows** — every row is encrypted, relayed, and stored on
   every device in the realm; a TB-scale backup makes the count effectively
   unbounded. No read-side change can cap this.
2. **O(n) read-side fold, repeated** — Control's events feed reads the whole
   relation, sorts, and folds bursts client-side (`NotificationMapper.aggregate`)
   on every stream emission. This ANR'd on the 920-row backlog; an ANR fix patched
   it (`flowOn(Default)` + `conflate()` + `distinctUntilChanged()`) without
   capping the cost.
3. **Paging divergence** — because `aggregate` is a stateful fold (output count
   ≠ input count), `getEvents()` cannot use the windowed
   `TList.pagingSource`/`toPagingData` path the Drive Files listing uses, and
   instead re-pages an in-memory snapshot with a bespoke
   `EventListPagingSource`.

### 1.1 Why not a proxy-side fold chain operation

The old tech-debt sketch proposed a generic fold-adjacent chain op
(`<collapseBursts>`). Rejected here:

- A fold's output count differs from its input count, so `drop(n).take(m)`
  windows in *output* coordinates can't be served lazily from the input list —
  the proxy would have to materialise the folded result, landing on the
  lazy-vs-materialised seam (`SemanticMerger` / `resolveListField`,
  [ADR 0024](../adr/0024-merger-field-resolution.md)) to serve exactly one consumer.
  ⚠ ADR 0024 settled how a *merger* reads such a field; it does not make folding a
  window in output coordinates cheap, so this argument stands.
- It leaves cost #1 completely untouched: the rows still sync to every device.
  A read-side fold — client or proxy — is a band-aid over the wire/storage
  layer.

The correct layer is the **producer**: don't mint N durable synced rows for
one logical bulk action.

## 2. Design

### 2.1 Schema: `count` on `FileAddedNotification`

Extend `FileAddedNotification` (`:file-service`,
`org.tenshu.core.file.notifications`) with a `count` field (integer, always
set, `1` for a single upload). No new subtype: the mapper branches on
`count > 1`, so single-file display semantics are unchanged and a burst row
never has to *change type* mid-burst. `path` holds the **newest** file in the
burst (the display headline). `FolderAddedNotification` inherits the field and
always carries `1` (folders are never folded — unchanged).

Identity and merge need nothing new: notifications are keyed by `uuid`
(`NotificationDataIdProvider`, `stringDataId`) and are `Syncable(Eager)` +
LastWriterWins — an in-place re-save with the same `uuid` is an ordinary row
update on the existing sync path. Only the minting device ever updates its own
burst row, so LWW is conflict-free by construction.

Since backward compatibility is waived, the field is added directly to the
existing type (positional `create(fields = ...)` updated in place); no
migration, no legacy-row handling. Old already-synced rows without the field
are expected to be reset away.

### 2.2 Producer coalescing in `UploadFileImplementation`

Replace the unconditional per-file mint (`UploadFileImplementation.kt` ~L193)
with a coalescing mint keyed by a small in-memory tracker on the proxy:

```
BurstTracker { lastUuid: String?, lastTimestamp: Long, count: Int } // + Mutex
```

- If the previous file-added mint is within `BURST_WINDOW` (60 s, constant
  moves here from `NotificationMapper`): re-save the **same row**
  (`uuid = lastUuid`) with `count + 1`, `path = <newest file>`, refreshed
  `timestamp`. One row syncs per burst instead of N.
- Else: mint a fresh row with `count = 1` and reset the tracker.
- The tracker is guarded by a mutex (per-file `UploadFile` ops may overlap)
  and is deliberately **process-local**: a proxy restart mid-burst costs at
  most one extra row. No durable coalescing state.
- Interleaved `FolderAdded` / delete notifications do **not** break a burst
  (delta vs. the client fold, which broke a run on any non-file-added row).
  The burst is a single advancing row; other event rows simply sort around it
  by timestamp. Fewer rows, and nested-folder uploads no longer fragment into
  many small bursts.

Semantic deltas vs. the client-side fold (all accepted 2026-07-02):

- Coalescing is **per producing device**; two devices uploading simultaneously
  produce two summary rows instead of one cross-device merge (arguably clearer).
- The window closes against the minting clock, not re-derived at read time.
- Folders/deletes no longer split a run (above).

### 2.3 Read-side convergence: `getEvents()` on `toPagingData`

With bursts collapsed at the source, mapping is 1:1 again and Control's feed
becomes symmetric with Drive's Files listing:

```kotlin
override fun getEvents(): Flow<PagingData<Event>> =
    client.notificationService.notifications
        .sortByDescending(/* Notification timestamp */)
        .toPagingData(notificationMapper::mapToEventOrNull)
```

Deleted outright: `NotificationMapper.aggregate`, `EventListPagingSource`,
and the ANR-fix band-aids (`conflate`/`flowOn`/`distinctUntilChanged` and the
whole-relation `flow()` read). The O(n log n)-per-emission cost disappears —
windowed loads touch only the visible page, and reactivity rides
`pagingSource`'s invalidate-on-change observer. `mapToEventOrNull` renders
`FileAddedNotification` with `count > 1` as `Event.FileEvent.MultipleAdded`
(`otherFilesCount = count - 1`), `count == 1` as `Added`; the `else -> null`
forward-compat drop stays.

**Verification item:** the chain DSL must support `sortByDescending` over the
notification `timestamp` *field*. `recentFiles` sorts on `File.LastModifiedAt`,
which is projected as a value-property relation; if the sort op turns out to be
relation-only, Slice 3 includes the small extension to sort on a plain
timestamp field (or projects the field the same way). To be confirmed at the
start of Slice 3.

### 2.4 `:core-paging`: partial mapper

`mapToEventOrNull` is a *filter* (unknown future notification subtypes drop to
`null`), and `TenshuPagingSource`'s key math assumes a total mapper — with
filtering, `nextKey = position + data.size` would re-read dropped rows and
duplicate items. Generalise (new overload or widen the existing signature to
`(T) -> R?`):

- In `load`, capture the **raw** window size before `mapNotNull`;
  `nextKey = position + rawCount`; `nextKey = null` only when `rawCount == 0`.
- An all-filtered window returns
  `Page(data = emptyList(), nextKey = position + rawCount)` — Paging 3 walks
  past empty middle pages with a non-null `nextKey`.
- `getRefreshKey` is offset-based and unchanged.

This makes `toPagingData` the single paging helper for both call sites, with
filtering a supported mapper capability rather than a reason to fork.

### 2.5 `:core-paging`: invalidation conflation under burst emissions

The burst problem has two halves, and only one of them is notification-specific.
The **row-count** half (§2.1–2.2) cannot generalise: a notification is a derived
event *about* a bulk action, so the producer may coalesce rows; uploaded files
genuinely *are* N `File` rows, so there is nothing to coalesce at their source.
The **emission-storm** half is general: `.flow()` re-emits per save (and once
per element on initial replay), so a bulk upload hammers every reactive
consumer. Per-emission cost decides the blast radius — the events feed ANR'd
because it paid O(n log n) whole-relation folds on Main per emission; Drive's
`getFilesPaged` only pays `factory.invalidate()` → one O(page) windowed reload,
off-main — structurally fine, but a burst of N saves still triggers up to N
invalidations → N paging generations → N window reloads and visible list churn.

The generic fix has a single home: **conflate (or lightly debounce) the
change-observer inside `pagingSource`** so a burst of emissions collapses to
~one invalidation. Both call sites — Drive Files today, Control Events after
Slice 3 — inherit it for free, which is the payoff of converging on one paging
helper. Rides Slice 2. (The non-paged whole-list readers — `getFiles`,
`recentFiles` — are bounded by folder size / `take(limit)` and already run on
`Dispatchers.Default`; churn there is acceptable, no change.)

## 3. Relationship to the Task composite / batch-RPC idea

The burst is indeed a downstream consequence of how bulk uploads are shaped:
the SAF/tree walk is necessarily client-side (the proxy can't enumerate the
grant), so a folder upload reaches the proxy as N independent `UploadFile`
RPCs with no batch boundary — the producer can only *infer* the batch, which
is what the `BURST_WINDOW` heuristic does.

A Task API in the `core-task-composite.md` direction ("a task consisting of
multiple RPC calls" / sequential task execution) would make the boundary
**explicit**: the client opens a batch task, the per-file calls carry its id,
and the producer mints exactly one notification per task, updating `count` as
the task progresses — no time-window heuristic at all. That is the *right
eventual shape*, and this design is built to slot into it: the coalescing key
is isolated in `BurstTracker`, so "same burst = within window" can later be
replaced by "same burst = same batch/task id" without touching the schema, the
read side, or the sync behaviour (it's still one row updated in place).

It is deliberately **not** a dependency:

- `core-task-composite.md` is deferred with two unresolved load-bearing
  problems (§4.1 result delivery, §4.2 out-of-band cancel); gating an active
  ANR-class fix on a `:core` async-runtime refactor inverts the risk.
- The batch boundary alone doesn't need the full Task composite — even a
  lightweight optional `batchId` param on `UploadFile` (client mints one id
  per folder-upload/drain window) would do, and remains available as an
  intermediate step if the window heuristic ever misbehaves.
- Grouping (batch identity) and lifecycle (await/cancel/progress) are
  orthogonal axes; the Task composite earns its keep on the second, and the
  Synced-Folders drain has no natural "end" anyway — a drain is open-ended, so
  even under a Task API the window (or drain-cycle) heuristic survives there.

When the Task composite lands, revisit: replace the tracker's time key with
the task id for foreground folder uploads (one exact notification per user
action), keep the window for the drain.

## 4. Slices

Each independently shippable; 1 alone resolves the root-cause tech-debt entry.

| Slice | Content | Modules | Notes |
|---|---|---|---|
| **1. Producer coalescing** | `count` field on `FileAddedNotification` + `BurstTracker` coalescing in `UploadFileImplementation`; unit tests (burst folds to one row with count, window expiry starts a new row, folder/delete interleave doesn't split, concurrency via mutex) | `:file-service`, `:file-service-proxy` | Caps synced rows — fixes the true root. Feed keeps working unchanged (the client fold sees pre-collapsed rows and folds nothing; display of `count > 1` rows needs the Slice 3 mapper — sequence 1→3 close together or read `count` in the mapper here already) |
| **2. Partial-mapper paging + invalidation conflation** | `toPagingData`/`pagingSource`/`TenshuPagingSource` accept `(T) -> R?`; raw-count key math; conflate/debounce the change-observer (§2.5) so burst emissions collapse to ~one invalidation; `TenshuPagingSourceTest` cases (filtered window advances, all-filtered page, end detection, burst → single reload) | `:core-paging` | Independent of 1; benefits Drive Files immediately, Events after Slice 3 |
| **3. Feed convergence** | `getEvents()` → chain sort + `toPagingData(mapToEventOrNull)`; mapper reads `count`; delete `aggregate` + `EventListPagingSource` + ANR-fix band-aids; verify/extend chain sort on the timestamp field; update `NotificationMapperTest` / `HomeScreenTest` | `:control-shared-logic`, possibly `:core`(-proxy) for field sort | Depends on 1 + 2 |
| **4. Retention cap (optional)** | Bound the notifications relation proxy-side (e.g. keep newest 500, evict beyond) | `:notification-service-proxy` | Defence in depth: with coalescing the relation grows slowly, but "recent events" has no reason to be unbounded. Mirrors the `:logging-service` Phase 5b ring-buffer shape. Can ride any later PR |

Tech-debt: both entries get a pointer to this doc when Slice 1 lands and are
folded/removed when Slice 3 lands.

## 5. As-built notes — Slice 1 (2026-07-02)

Three deltas vs §2.1–2.2 as designed, all in the direction of less wiring:

- **The coalescing state is a durable local record, not in-memory tracker
  state.** §2.2 sketched a process-local `BurstTracker`; as built the state is
  `FileAddedBurst` (`:file-service`), a local-only pointer record in the same
  object store, saved via `Database.saveData` (no observers, no sync) — the
  exact `ResolvedDataId` shape from `TombstoneRegistry.rememberDataId`. Reason:
  operation implementations are minted per dispatch
  (`fileServiceProxyImplementationFactory`), so in-memory state would have
  required threading a holder through every DI module (the
  `StorageCapacityHolder` pattern, ~6 call sites, forgettable at new sites);
  the record needs zero wiring, works in every harness automatically, and a
  burst now survives a proxy restart mid-backup. Atomicity comes from
  `Database.transaction` (the DB's reentrant lock) around the
  read-decide-write, in place of the sketched mutex.
- **No new notification subtype and no type mutation:** `count` is an
  **optional** `int` field appended to `FileAddedNotification` itself; every
  upload mints `count = 1` and a burst bumps the same row. `null` count (a row
  minted pre-field) reads as 1. `FolderAddedNotification` inherits the field,
  never sets it.
- **`DbEntryMapper` learned trailing-optional tolerance** (`:core-common`): a
  stored row with fewer field slots than its (grown) schema now reads the
  missing tail as `null` when those fields are optional, instead of failing
  the whole row. Without this, pre-upgrade `FileAddedNotification` rows in the
  store would have thrown on every relation read — the "reset required"
  upgrade cliff. Mandatory fields keep the hard corruption check. This makes
  append-an-optional-field the sanctioned schema-evolution move for stored
  data generally.

Slice-1 also updated `NotificationMapper.mapToEventOrNull` (Control) to render
`count > 1` as `MultipleAdded` directly, so coalesced rows display correctly
ahead of Slice 3; `aggregate` and the ANR-fix band-aids stay until Slice 3 removes
them. Tests: `UploadFileNotificationBurstTest` (`:file-service-proxy` — one row
per burst incl. window-edge, expiry rollover, folder-interleave non-breaking)
+ 3 `mapToEventOrNull` cases in `NotificationMapperTest`.

### 5.1 As-built notes — Slice 2 (2026-07-02)

As designed (§2.4 partial mapper + §2.5 invalidation conflation), plus one
convergence move pulled forward from Slice 3:

- `TenshuPagingSource`/`pagingSource` mapper widened to `(T) -> R?`;
  `load` advances `nextKey` by the **raw** window size and only ends paging on
  an empty raw window (an all-filtered page returns empty data with a non-null
  `nextKey`). Source-compatible: a total `(T) -> R` mapper is a subtype.
- The invalidation observer is now `conflate()` + a 250 ms post-invalidate
  cooldown (`INVALIDATION_COOLDOWN_MS`): the first change still invalidates
  immediately (no added latency for single edits); a burst of N emissions
  costs ~2 paging generations (immediate + conflated tail) instead of N.
  Throttle-with-immediate-first was chosen over `debounce`, which would delay
  every single edit by the window.
- **`toPagingData` moved to `:core-paging`** (from `:drive-shared-logic`'s
  `FilesPaging.kt`, now deleted): the Drive-local bridge only existed because
  `:core-paging` used to be JVM-only, and Control couldn't reach it there.
  Both apps now share the one repository-facing helper — the Slice 3
  `getEvents()` rewrite imports `org.tenshu.core.paging.toPagingData`.

Tests: 4 new `TenshuPagingSourceTest` cases (raw-window key advance under a
filtering mapper; all-filtered page keeps walking; empty raw window ends
paging; emission burst → bounded generations). Drive's
`FilesPagingIntegrationTest` re-run green against the moved helper.

### 5.2 As-built notes — Slice 3 (2026-07-03)

`getEvents()` is now exactly the §2.3 shape:

```kotlin
client.notificationService.notifications
    .sortByDescending { timestamp }
    .toPagingData(notificationMapper::mapToEventOrNull)
```

Deleted: `NotificationMapper.aggregate` + `BURST_WINDOW` (the producer's
`FileAddedNotificationCoalescer.BURST_WINDOW_MS` is the one window now),
`EventListPagingSource`, and every ANR-fix band-aid (`conflate`/`flowOn`/
`distinctUntilChanged`/`flatMapLatest` + the whole-relation read). The macOS
tray bridge's `recentNotifications` (`ControlDataBridge`) — a second,
initially-missed `aggregate` consumer — drops its fold too (`count > 1` rows
render via the mapper).

The §2.3 verification item resolved as: the chain sort is the **selector
lambda** overload (`sortByDescending { timestamp }` — the runtime comparator
already accepted `UnixInstant` via `TInteger`), NOT a value-property handle;
no sort-op extension was needed. Two real client-infra gaps surfaced instead,
both "first consumer of this shape" holes:

- the **base** `Notification` type had no request-object factory (only its
  subtypes did), so the selector couldn't materialise an element RO —
  registered in `NotificationServiceClientModule`;
- `UnixInstant` registered a Dto factory but **no RO factory**, so the
  selector's field access died at chain-build — new `UnixInstantRo`
  (mirroring `TIntegerRo`) registered in `CoreCommonModule`. Every prior sort
  call site used the `Property`-handle overload (`File.LastModifiedAt`), which
  never touches either path.

Feed cost after convergence: each page load reads only its `drop().take()`
window; the proxy-side sort is O(n log n) over the relation per window read —
acceptable while coalescing keeps the relation small; Slice 4's retention cap
is the backstop if it ever isn't.

New test: `EventsFeedIntegrationTest` (`:control-shared` desktopTest, over the
real in-process proxy + `asSnapshot`) — a 3-file burst surfaces as ONE
`MultipleAdded` (headline = newest file), no per-file rows leak, folder event
separate, chain sort delivers newest-first. The `aggregate` unit tests are
deleted with the fold (producer behaviour is pinned by
`UploadFileNotificationBurstTest`).

## 6. Non-goals

- **Suppressing drain notifications entirely** (the "dedicated backup-status
  channel" option) — explicitly deferred (maintainer, 2026-07-02); coalescing
  makes it non-urgent.
- **Legacy backlog handling** — old per-file rows are reset away, not migrated.
- **The Task composite itself** — tracked in `core-task-composite.md`; §3 above
  records the linkage only.
- **Threat-model changes** — none (same synced app data, same relay contract).
