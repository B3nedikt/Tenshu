# Deferred Properties — incremental delivery for expensive projections

**Status**: v1.0 — **Phases 1–4 ALL SHIPPED (2026-07-05, branch
`feat/core-deferred-properties`)**: P1 schema flag + `eager()`/`deferred()` DSL;
P2 `PendingDeferredProperty` marker + one-shot null semantics; P3 `StreamRegistry`
per-stream resolution queue + per-row patches; P4 `File.Preview` `deferred = true`
+ `eager(...)` at the one-shot call sites. Phase 5 (prioritisation & flow control)
remains future. **See §10 for as-built deltas** — the wire design in §3 changed
during implementation (positional composite serialisation makes an added optional
selector field peer-breaking; the override rides a new `Resolved` selector type
instead). The immediate consumer-side fix (Drive's Files list loads thumbnails
through the per-row async loader) shipped separately; this document is
the platform-level generalisation.

---

## 1. Motivation

Opening a 2 152-file folder in Drive took ~30 s and eventually ANR'd. The
paging window fix (`:core-paging` `toPagingData` projection slot — select runs
on the `drop().take()` window, not the whole list) brought a page down to one
window, but the projection itself stayed slow:

```
[perf][select] rows=20 took=364ms          // ~18 ms PER ROW
```

The cost is `File.Preview`: a **dynamic property resolved synchronously inside
`SelectImplementation.selectItem`**. For a file without a stored thumbnail
(anything uploaded before synced thumbnails) that means read + decrypt the
whole file, sha256 it, decode, scale, encode — per row, on the projection's
thread, before the page can emit. One expensive property holds the entire
row set hostage: metadata that resolves in microseconds waits behind
thumbnails that take tens of milliseconds.

The general problem: **a projection is all-or-nothing.** Every selected
field/relation must resolve before anything is emitted. There is no way to
say "this property is expensive; give me the row now and the property when
it's ready."

## 2. Prior art & naming

This is GraphQL's **`@defer` / `@stream`** (they call the umbrella
*incremental delivery*): the response arrives as an initial payload with
deferred fields omitted, followed by patches that fill them in. The same shape
appears as "skeleton screens + backfill" in UI land and as the async image
loader (Glide/Coil) in list UIs. We adopt the term **deferred property**.

Two layers of the pattern exist and stay complementary:

- **Consumer-side (async loader)** — the UI fetches the expensive property
  per *visible* row, bounded and cancelled on scroll. Best whenever a viewport
  exists, because only the client knows what's visible. Drive's Files list
  uses this today (`HorizontalFileItem.previewLoader` →
  `FilesRepository.getPreview`).
- **Producer-side (this proposal)** — the stream itself patches rows as
  deferred properties resolve. Best for long-lived reactive surfaces with no
  meaningful viewport (details screens, dashboards, watch lists) and for
  consumers that can't run a loader loop (`@tenshu/sdk` snapshot readers).

## 3. Concept

Deferredness becomes **schema metadata with per-select overrides**.

### 3.1 Declaration — property default

`Property` handles carry a `deferredByDefault` flag, authored in the property
DSL (`core/src/commonMain/kotlin/org/tenshu/core/relation/Property.kt`):

```kotlin
// Static value property — eager by default (unchanged):
val Size = valueProperty<TInteger>(id = "tenshu/file/size", schema = TInteger.Schema)

// Dynamic property — DEFERRED by default:
val Preview = dynamicProperty<File, TByteArray>(
    id = "tenshu/file/preview",
    receiver = File.Schema,
    params = listOf(TInteger.Schema, TInteger.Schema),
    deferred = true,                       // NEW
) { params -> ... }
```

The flag rides the property's schema (`RelationSchema`), so the proxy knows it
without the client having to say anything.

### 3.2 Projection — per-select override

`select { ... }` follows the property's default unless the caller overrides:

```kotlin
select {
    fields(File.Field.PATH)
    relations(
        File.Size,                          // eager (its default)
        File.Preview(100, 100),             // deferred (ITS default) — row emits, patch follows
        eager(File.Preview(100, 100)),      // force inline resolution (e.g. a one-shot export)
        deferred(File.LastModifiedAt),      // force deferral of a normally-eager property
    )
}
```

`eager(...)` / `deferred(...)` wrap the handle's `PropertyRelation` selector;
the `Selector` wire composite gains an optional resolution field
(`default | eager | deferred` — absent ⇒ `default`, so old clients/proxies
interoperate).

## 4. Semantics

**A deferred property never blocks a read.** What "later" means depends on the
terminal:

- **Streaming terminal (`.flow()`)** — the initial emission carries the row
  with the deferred property as `null` (placeholder under the hood, §5); the
  proxy resolves it in the background and **re-emits that row** (a per-item
  patch on the existing stream) when ready. The client's `Property.decode`
  already collapses placeholders to `null`, so consumer code reads
  `entity.get(File.Preview)` unchanged — it's `null` first, present after the
  patch.
- **One-shot terminal (`.get()`, and paging's `.flow().first()`)** — the
  deferred property is **`null`, full stop**. There is no channel to patch
  through; the caller opted into deferral and re-requests on demand (the
  loader pattern) or forces `eager(...)`.

**Rejected alternative** — resolving deferred properties eagerly on one-shot
terminals ("GraphQL on a non-incremental transport" behaviour). It makes the
cost of the same projection depend on the terminal, silently reintroduces the
blocking read this proposal exists to kill, and the `eager(...)` override
already covers callers that genuinely want inline resolution.

## 5. Mapping onto existing machinery

The seams already exist — deferral is mostly *not building new ones*:

- **Placeholder**: `PendingOnDemandProperty` (`:core-common`) is already a
  wire-safe inert marker that `Select` writes on a local miss and terminals
  materialise. Generalise it (or add a sibling `PendingDeferredProperty`) with
  a `reason: onDemand | deferred` discriminator. `SelectImplementation`
  writes it for deferred selectors *instead of* calling `resolveProperty`.
- **One-shot terminals**: `MaybeGetImplementation` / `OnDemandMaterialiser`
  currently materialise every pending marker before returning. They skip
  `deferred` markers (the null-collapse in `Property.decode` does the rest).
- **Stream patches**: `StreamRegistry.setDatabaseListener`'s `onUpdate`
  already sends **per-item** `StreamElementValueDto`s and the client already
  merges them into its list state (this is how diffs work today). Deferral
  adds: after the initial emission, walk the emitted items' deferred markers
  into a **per-stream resolution queue** (bounded parallelism, e.g. 4;
  cancelled by `removeDatabaseListener`); each completed resolution re-runs
  `selectItem` for that one row with the property eager and sends the row as
  a normal per-item update. No new DTO types; no client protocol change.
- **Resolver-level caches** stay orthogonal: `GetPreview`'s stored-thumbnail
  short-circuit and `PreviewCache` make the deferred resolution itself cheap
  when it can be — deferral bounds *latency*, caches bound *work*.

## 6. What this does NOT replace

Drive's Files list keeps the consumer-side loader even after this ships.
A paged list uses one-shot loads (deliberately — long-lived per-page streams
were a leak class), and only the client knows the viewport: fetching previews
for the 15 *visible* rows beats patching all 20 of a window the user already
scrolled past. Deferred properties are for the reactive surfaces where the
stream *is* the lifecycle.

## 7. Phasing

Each phase independently shippable:

1. **Schema + DSL plumbing** — `deferred` param on `valueProperty` /
   `dynamicProperty`, `eager()`/`deferred()` selector wrappers, resolution
   field on the `Selector` wire composite. No behaviour change (markers still
   resolved eagerly everywhere). Pure risk-free substrate.
2. **One-shot semantics** — deferred selectors resolve to `null` on one-shot
   terminals (`SelectImplementation` writes the marker; `Get`/materialiser
   skip it). First observable behaviour change; gate on migrating the two
   in-repo `File.Preview` projections first (both already moved off inline
   previews).
3. **Stream patches** — the per-stream resolution queue + per-row re-emission
   in `StreamRegistry`; cancellation on stream close.
4. **Migrate `File.Preview` to `deferred = true`** and audit other dynamic
   properties (anything whose resolver does IO belongs here).
5. **(Future) prioritisation & flow control** — viewport hints from client to
   proxy; scroll-velocity load-shedding (cancel patches for rows the user
   flung past); dynamic page sizes. Recorded from the 2026-07-03 discussion;
   all need the client→proxy hint channel, which is its own design.
6. **(Future) `streamed(...)` — the third resolution kind**, for properties too
   large to cross the IPC boundary whole. Design sketch in **§11**; ⚠ it is
   *not* a third `RelationResolution`, and §11.2 is why.

## 8. Open questions

- **Q1** Failed deferred resolution: emit a patch with `null` (settles the row)
  or leave the placeholder (retry on next stream update)? Leaning patch-null +
  logged `ErrorEvent`.
- **Q2** Marker unification: fold `onDemand` and `deferred` into one
  `PendingProperty` shape, or keep separate types? (OnDemand implies a relay
  fetch; deferred is local-compute — retry/backoff policies differ.)
- **Q3** Patch vs. invalidation races: a full re-evaluation (array change)
  concurrent with an in-flight patch — last-write-wins per row is probably
  fine since both derive from current DB state, but needs a test.
- **Q4** Does the JS SDK's snapshot bridge need `@defer` surfaced, or do web
  consumers stay on TanStack-Query refetch (their existing pattern)?
- **Q5** Should `sortBy` on a deferred property be rejected at the DSL level?
  (Sorting requires materialisation — silently forcing eager would surprise.)

## 9. Implementation notes for Claude Code

File-level map for Phase 1–3 (the canonical handoff section):

| Change | Files |
|---|---|
| `deferred` param + handle flag | `core/.../relation/Property.kt` (`valueProperty`, `dynamicProperty`, `Property.Value/Dynamic`), `core/.../schema/*` (RelationSchema carrier) |
| `eager()`/`deferred()` wrappers + Selector resolution field | `core/.../collection/list/Select.kt` (`Selector`, select DSL builder) |
| Marker + skip-on-deferred | `core-common/.../proxy/PendingOnDemandProperty.kt`, `SelectImplementation.kt` (DynamicPropertySelector + PropertySelector arms), `OnDemandMaterialiser.kt`, `MaybeGetImplementation.kt` |
| Per-stream resolution queue + row patches | `core-common/.../proxy/StreamRegistry.kt` (`setDatabaseListener` / `removeDatabaseListener`) |
| `File.Preview` migration | `file-service/.../File.kt` property registration; both `GetPreview` impls unchanged |
| Tests | `:core-common` select/get/stream unit tests; `:drive-shared-ui` `FilesPagingIntegrationTest` (metadata-only contract already pinned) |

Perf context that motivated this (2026-07-03 device logs): select at
~18 ms/row inline vs. sub-ms metadata-only; full trace in the tech-debt entry
"Paged Listings Still Pay O(n) Raw Reads Per Page".

## 10. As-built deltas (2026-07-05)

The shipped implementation deviates from §§3/5/8 in four load-bearing ways, all
discovered against the real serializer/parser:

1. **No wire field on either schema or selector.** Composite fields serialise
   **positionally** (`LinearSerializer` writes an 8-byte 0-marker for every
   absent optional; list elements carry no per-element framing), so the §3
   design — an optional field on `DynamicPropertyRelationSchema` and on the
   existing `DynamicPropertySelector` — would desync old↔new peers on **every**
   dynamic select, even without an override. The `SIGNAL_ONLY` omit-when-default
   recipe applies only to op params (map-assembled by the parser), not composite
   fields. As built:
   - the schema flag is **code-side Kotlin metadata**
     (`DynamicPropertyRelationSchema.deferredByDefault`, the `callstack`
     precedent) — never serialised; a wire-deserialised schema defaults to
     eager, and only the proxy's own registration is ever consulted;
   - the per-select override is a **new union member**
     `Selector.DynamicPropertySelector.Resolved` (own composite id; fields
     `reference`, `params?`, `deferred` required). Plain selects stay
     byte-identical; an old peer sees the new type id only when a call site
     actually uses `eager(...)`/`deferred(...)` (ship lockstep — the
     `SIGNAL_ONLY` posture, scoped to feature use). `Resolved` implements the
     `DynamicPropertySelector` Kotlin interface, so `is`-dispatch and
     `filterIsInstance` sites needed no changes.
2. **`valueProperty` has no schema-level `deferred`** (§3.1's first example is
   NOT built): `Property.Value` carries no `RelationSchema` — there is nowhere
   for the flag to ride. `deferred(handle)` is signature-restricted to
   `Property.Dynamic`; `eager(handle)` accepts any handle (no-op on statics,
   which always resolve inline). §3.2's `deferred(File.LastModifiedAt)` example
   is therefore invalid as written — statics resolve in microseconds and were
   never the problem.
3. **Q2 resolved as a sibling type**, `PendingDeferredProperty`, not a `reason`
   field on `PendingOnDemandProperty` — the same positional-wire constraint (a
   new optional field would change the bytes of every existing OnDemand marker
   crossing IPC), plus the semantics genuinely differ: OnDemand markers the
   terminal must await, deferred markers it must NOT. Bonus: the
   `is PendingOnDemandProperty` checks in `MaybeGetImplementation` /
   `OnDemandMaterialiser` skip the sibling type without modification — the §4
   one-shot semantics fell out for free.
4. **§5's stream patch does not re-run `selectItem`.** `resolveAndPatch`
   mirrors `OnDemandMaterialiser`'s per-marker path (`database.getData(entityId)`
   → `resolveDynamicProperty` → merge → per-item `StreamElementValueDto`):
   any concurrent DB change already triggers a full re-projection, so
   re-dispatching `EntitySelect` per patch buys nothing. Patches serialise with
   full re-evaluations via the per-stream mutex (hoisted from the local
   `updateMutex` into a registry map) and apply only while the current row
   still carries the marker (Q3: last-write-wins). Bounded by a registry-wide
   `Semaphore(4)`; cancelled in `removeDatabaseListener`/`clear()`.

Remaining open-question resolutions: **Q1** failure patches the row with the
property key dropped (settles to client-`null`; the next full re-evaluation
writes a fresh marker — natural retry) + `printStackTrace` logging, matching
the stream error-path convention. **Q5** is moot — `sortBy` accepts static
value handles only and deferral is dynamic-only. **Q4** unchanged: web stays
on snapshot + refetch.

Accepted v1 behaviour (documented on `scheduleDeferredResolutions`): an array
change re-projects and re-writes fresh markers, so a row transiently re-emits
null-then-patched; resolver caches (stored thumbnails, `PreviewCache`) bound
the re-resolution work. Carry-forward of patched values is a possible later
optimisation, as is Phase 5.

**First producer-side consumer (follow-up slice, same day):** Drive's file
details screen. `TenshuFilesRepository.fileDetails` now narrows the stream to
the ONE matching row (`filter { path equalTo path }` — previously the whole
parent listing was projected + shipped per update and matched client-side) and
projects `File.Preview(480, 480)` bare (deferred by default): metadata renders
instantly, the preview patches in through the stream; the details screen's
separate `getPreview` loader call is gone (`FileDetailsViewModel.loadPreviewBytes`
deleted). The VM keeps the last non-null preview across re-emissions so the
accepted marker-churn never flickers the header back to the icon. The surface
rule this sets: **lists use the consumer-side loader (§6), single-entity
reactive surfaces use deferred projection.** Home recents deliberately stays on
the loader — at `limit≈20` the viewport argument is moot, but deferred
projection would push ~20 thumbnails of patch traffic on every folder change
even when Home isn't visible, vs. the loader's on-compose point reads.
`FileDetailsDeferredPreviewIntegrationTest` (drive-shared-ui desktopTest) pins
the one integration the proxy-side tests can't reach: the CLIENT merging a
per-item patch into a collected chain flow (metadata-first emission with null
preview, then the patched row with bytes, over the real in-process proxy).
Its forced twin, `FileDetailsDeferredPreviewForcedInterleavingTest`, pins the
*delivery order* that assertion depends on: the client must hand the snapshot
to the flow before the patch even when the patch's coroutine runs first
(`tech-debt.md`, gate-flake entry, sighting 2026-09-14 — the per-element
`launch` every in-process client used was replaced by
`forwardStreamElementsInOrder`).

Tests: `DeferredPropertyFlagTest` (:core), `ResolvedSelectorRoundTripTest`
(:core-common, wire), `DeferredSelectOperationTest` +
`DeferredStreamPatchTest` (:file-service-proxy, semantics incl. the real
`File.Preview` P4 pins), deferred-skip pins in `OnDemandMaterialiserTest` /
`MaybeGetOperationTest` (:core-common). Regression gates:
`FilesPagingIntegrationTest`, `FilesListingEfficiencyTest`,
`SyncedThumbnailSystemTest` (now `eager(...)`).

---

## 11. `streamed(...)` — the third resolution kind (design sketch, 2026-08-06)

**Status:** sketch, no code. Raised while scoping `sync-device-catch-up.md` C7
(thumbnails → OnDemand), where the question *"should `eager` keep working?"* turned
into *"`eager` bounds **when**, not **how much**"*.

### 11.1 The gap

`eager` and `deferred` are the same answer to one question — **when** does this
property resolve, inline or later. Neither touches **how much crosses the boundary**.
A 15 MB `File.Data` projected `eager` is still 15 MB through Binder in one
transaction; projected `deferred` it is still 15 MB, merely later. §1's motivation was
*latency*; this is *volume*, and it is a different axis.

That matters on exactly the platforms with the least headroom: a constrained client
(a watch, a low-memory Android device, a browser tab) may be unable to hold the value
at all, no matter when it arrives. `bounded-store-cache.md` is the same ceiling one
layer down.

⚠ **This is not a replacement for `deferred` + OnDemand.** A 7 KB thumbnail (C7) wants
`deferred`: it fits in one message, and a range protocol would cost more than the
payload. The kinds partition by **size**, not by preference — and a property should
declare the one that matches what it actually carries.

### 11.2 ⚠⚠ It is NOT a third `RelationResolution`, and that is the whole finding

The obvious shape is `RelationResolution(handle, streamed = true)`, a sibling of
`eager()`/`deferred()`. **It does not work**, for a reason worth stating before someone
writes it:

> `eager` and `deferred` both yield the property's **declared type** — `TByteArray`
> either way, differing only in arrival time, which is why `Property.decode` collapses
> a pending marker to `null` and every consumer reads `entity.get(File.Data)`
> unchanged. A streamed property **cannot** yield `TByteArray`: the entire point is
> that the bytes never materialise as one value.

So `streamed` changes the *type* of what the projection returns, where the existing two
change only its *timing*. Fitting it into `RelationResolution` would mean either lying
about the type or making `decode` return something whose shape depends on a flag —
both worse than admitting it is a different verb.

**Proposed shape: a sibling handle property, not a resolution flag.**

```kotlin
select {
    fields(File.Field.PATH)
    relations(
        File.Size,                 // eager
        File.Preview(100, 100),    // deferred (its default)
        stream(File.Data),         // ⇒ a ContentStreamAddress, NOT bytes
    )
}
```

`stream(handle)` projects a small composite carrying the **address** of the content —
and nothing else:

```
ContentStreamAddress
  entityType : "tenshu/file"
  entityKey  : "<the file's key>"
  propertyId : "tenshu/file/data"
  totalSize  : 15_728_640          // so a caller can size buffers / show progress
```

### 11.3 ⭐ The transport already exists, and is already property-addressed

This is why the sketch is worth recording rather than deferring: **no new plumbing.**
D2b shipped `OpenContentStream` / `ReadContentRange` / `CloseContentStream` on
`SyncService` (S2b, `drive-streaming-preview.md`), and they are addressed by
`(entityType, entityKey, propertyId)` — *the OnDemand resolve triple*, i.e. exactly the
three fields above. `ContentStreamAddress` is therefore not a new concept; it is the
existing op's parameter list, promoted to a projectable value.

The consumers exist too: Android's `openProxyFileDescriptor` (`onRead` → range read),
the web Service-Worker `Range` shim (S6b), `FilesRepository.openStreamingContent`
(S3). Each of them today *constructs* that triple by hand from a path. A streamed
projection would simply hand it to them.

### 11.4 ⚠ Project the address, never the session

The one lifecycle trap. `OpenContentStream` mints a session that must be closed, and a
projection has **no lifecycle hook** — a row that is dropped, scrolled past, or never
read would leak its session. Every shipped consumer closes explicitly (the fd's
`onRelease`, `onCleared`).

So the projection must return the **address**, and opening happens when the caller
actually reads. Then there is nothing to leak: an unread `ContentStreamAddress` is
four fields that get garbage-collected. ⚠ This also keeps the projection cheap enough
to be **eager** — a streamed property is *eager in its metadata and lazy in its bytes*,
which is why it composes with, rather than competes with, §4's semantics.

### 11.5 Open questions

- **S1** Does `stream(...)` belong in `relations(...)` at all, or is it a separate
  projection verb? It reads naturally there, but it is the only entry that changes the
  result type — which may be an argument for making that visible in the DSL.
- **S2** Should a property be able to declare `streamed` as its *default* (the §3.1
  analogue), or is it always caller-chosen? Leaning caller-chosen: whether bytes fit is
  a property of the **consumer**, not of the data — the same 15 MB is fine on desktop
  and fatal on a watch.
- **S3** What does a **one-shot** terminal do (§4's fork)? An address is cheap and
  needs no patch channel, so unlike `deferred` it can resolve identically on both — a
  pleasant asymmetry worth confirming.
- **S4** Interaction with `getRemoteFile`, the motivating call site: it currently
  returns whole bytes over IPC. Migrating it is the natural first consumer, but it is
  used by the D2a temp-file preview path — which already writes to disk, so it may want
  `stream(...)` straight into the file rather than through the client at all.
- **S5** Does the JS SDK surface this, or keep its Service-Worker shim (which already
  does exactly this, by hand)?

### 11.6 Not in scope

Threat model: **no delta.** Same ops, same per-chunk AEAD, same decrypt-in-the-proxy
posture as D2b; this only changes which layer *names* the content. No relay change, no
wire reshape (`ContentStreamAddress` would be a new composite — additive, the
`GetSyncLogBefore` posture, `SchemaShapeBaseline` "1 new type, 0 changed shapes").
