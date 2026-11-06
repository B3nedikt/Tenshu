# Nested Projection — Per-Level `select`, and a File-Rooted History Relation

**Status:** In flight. **Phase 1** (tree `Projection` + deserializer threading) = **MERGED**;
**Phase 2** (producer: nested `select` DSL + `captureProjection` tree + `selectItem` recursion) =
**MERGED**. **Phase 3** (the
`File.FileHistory` consumer + the `entityRelation` primitive) = **LANDED** (§7 item 3). Two findings during
Phase 3 — a latent Phase-2 nested-`select`-DSL bug and the decode-fork resolution — are recorded in
§8.5–§8.6. Completes the projection-pushdown work
([`core-deserializer-projection-pushdown.md`](core-deserializer-projection-pushdown.md) §7,
landed) by making the read-time `Projection` a **tree** instead of a flat top level.
Driving consumer: a File-rooted FileDetailsScreen activity query
(`files.search { path == X }.select { relations(File.FileHistory) { relations(Device) } }`) —
the `File.FileHistory` reverse relation deferred in
[`file-service-history.md`](file-service-history.md) §3.2/§9.4, composing with the
`FileModification.Device` reference property ([`core-reference-property.md`](core-reference-property.md)).
Author: Benedikt + Claude.

**Scope:** `:core-common` — `proxy/Projection.kt` (flat → tree),
`proxy/db/TenshuProtocolObjectDeserializer.kt` (thread the child projection through the
recursion instead of `null`), `proxy/EvaluateCallstack.kt` (`captureProjection` builds a
tree), `proxy/SelectImplementation.kt` (recurse into projected relations),
`proxy/ReferenceResolver.kt` + `proxy/Database.kt` (thread a projection into relation
resolution + `getData`/`getArrayData`); `:core` — the nested `relations(handle) { … }`
select-DSL surface. Plus the driving consumer: `:file-service`(`-common`) `File.FileHistory`
reverse relation. **No wire-format change**; **no relay/sync-protocol change** — projection
is a read-side concern. See §6.

---

## 1. Motivation

### 1.1 The deserializer resolves in depth

Projection pushdown made `Projection` prune a read — but only at the **top level**. The moment
`TenshuProtocolObjectDeserializer.deSerialize` descends one level, it recurses **with no
projection**, so the whole subtree materialises in full:

- A kept composite field recurses `deSerialize(getEntry(hash), asEntity = …, getEntry)` —
  no `projection` argument (`TenshuProtocolObjectDeserializer.kt:141`).
- The relation walk is all-or-nothing: `projection != null ⇒ emptyMap()`; otherwise it walks
  **every** relation and recurses, again with no projection (`…:156-199`).

`Projection` itself encodes only a flat top level — `keepFields: Set<String>` +
`skipUnprojectedComposites: Boolean` (`Projection.kt:39`), with no per-child shape. The pushdown doc's
own §7 flagged this: *"nested projections are not expressed; v1 treats a projected composite
as 'materialise one level'; deeper shaping is future work."* This is that work.

### 1.2 What it blocks: efficient deep reads, and any File-rooted query

Because every descent is full-depth, a read that wants *one relation, shaped* can't ask for
it — it gets the whole subtree. Concretely, the FileDetailsScreen activity list would love to
read **one file** and walk down to its history and each row's device:

```kotlin
files.search { path equalTo "/photos/sunset.jpg" }
    .select {
        relations(File.FileHistory) {            // each FileModification…
            relations(FileModification.Device) { fields(Device.NAME) }   // …its device, name only
        }
    }
```

That read is cheap *in principle*: `search` by path is an O(1) `DataId` lookup (type+path →
hash → one row); then it walks only the projected subtree — the file's ≤`RETENTION_CAP`
modification refs, each modification's fields + a `Device` ref (O(1)), the device's name. So
**O(this file's modifications), all O(1) lookups, no realm-wide scan.**

The shipped flat alternative — `fileService.modifications.filter { fileId == path }.select { … }`
— instead scans the **entire realm modification log** (every file's history) and filters.
So the File-rooted query is *more* efficient at scale — **once the deserializer honours a
per-level projection** instead of resolving in depth. Two pieces are missing: the tree
projection (§3.1–3.2) and the `File.FileHistory` relation it walks (§3.3).

## 2. Design overview

Three composing pieces, in dependency order:

1. **Tree `Projection`** + thread it through the deserializer recursion and the
   relation-resolution path (§3.1) — the load-bearing platform fix. Benefits *every* deep
   read, not just history.
2. **Nested `select` DSL** — `relations(handle) { … }` builds the tree; `captureProjection`
   + `SelectImplementation` honour it (§3.2).
3. **`File.FileHistory` reverse relation** — the driving consumer; a maintained reverse-index
   (§3.3).

## 3. Design

### 3.1 `Projection` becomes a tree

```kotlin
data class Projection(
    val keepFields: Set<String>,
    val skipUnprojectedComposites: Boolean,
    /** Per kept field / named relation id → the sub-projection to apply when descending it. */
    val children: Map<String, Projection> = emptyMap(),
)
```

Threading (the §1.1 fix):

- **Kept composite field** (`…Deserializer.kt:141`): recurse with `projection.children[field.id]`
  instead of nothing. Absent child ⇒ `null` ⇒ that composite materialises fully (back-compat:
  a named-but-un-nested field behaves as today).
- **Relation resolution**: today a kept relation is re-resolved by `select` via
  `ReferenceResolver.resolveProperty` (a fresh `getData`/`getArrayData`). Thread the relation's
  `children[relationId]` sub-projection into that resolution so the relation's value (and, for
  a list relation, **each element**) materialises pruned. This needs `Projection?` params on
  `resolveProperty` / `resolveDynamicProperty` / `Database.getData` already exist for
  `getArrayData`/`getData`; extend the relation path to pass them.
- The reference-property path (`resolveReferenceProperty`) passes its child projection into the
  final `getData(asEntity = false, projection = child)` — so `relations(Device) { fields(NAME) }`
  reads only the device name (the refinement reserved in
  [`core-reference-property.md`](core-reference-property.md) §4 / §6 Phase 4).

No wire change: `Projection` is a proxy-side read-time value, never serialised (same as flat projection pushdown).

### 3.2 Nested `select` DSL — `relations(handle) { … }`

The select builder's `relations(...)` selector gains an optional trailing
`ListBuilder<Selector<E>>.() -> Unit` for the element type, recursing the same `fields(...)` /
`relations(...)` grammar:

```kotlin
select {
    relations(File.FileHistory) {            // E = FileModification
        fields(FileModification.Field.TIMESTAMP, …)
        relations(FileModification.Device) { fields(Device.NAME) }   // E = Device
    }
}
```

- `captureProjection` (`EvaluateCallstack.kt`) walks the selector tree → a tree `Projection`.
- `SelectImplementation.selectItem`: when a selector carries a nested block and resolves to an
  entity / list of entities, recurse `selectItem` (or pass the sub-projection through
  `resolveProperty`) so each element is projected by the nested selectors.

This is syntax **(a)** from the design discussion — chosen over the alternatives (§5) because
it mirrors `select { }`'s own trailing-lambda and nests to any depth with one engine change.

### 3.3 `File.FileHistory` — the reverse relation (the consumer)

`File → its modifications` is one-to-**many**; it is **not** a `DataId` lookup (`DataId` is a
forward, single-entity key→entity pointer — that direction is `modification → File`, already
covered by a `referenceProperty<File>`). The reverse needs one of:

- **(recommended) a maintained reverse-index relation** — on every `FileModification` save the
  emit/merge path mirrors its ref into the authoring file's `FileHistory` relation, exactly the
  `NotificationRelationMaintainer` / `FileModificationRelationMaintainer` derived-relation
  pattern. Read is O(file's mods), no realm-wide scan. This is the `file-service-history.md`
  §3.2 "parent relation back to File" made concrete.
- a **filter-backed dynamic relation** — `File.FileHistory = dynamicProperty { fileService.modifications.filter { fileId equalTo path } }`.
  Simpler, no maintenance, but it's a reverse-join chain (the resolver must root at
  `fileService.modifications` and filter by the receiver's field — the cross-service
  chain-rooting gap, `file-service-history.md` §9.4) **and** it re-introduces the whole-log
  scan the index avoids.

Recommend the maintained index. Either way it composes with §3.1: the history elements
materialise under the nested projection.

### 3.4 End to end

`files.search { path == X }` → O(1) `DataId` read of the File → tree projection walks:
File metadata, then `FileHistory` (the index → the file's modification refs), then per
modification its projected fields + the `Device` reference, then `Device.name`. All O(1)
lookups, bounded by the file's `RETENTION_CAP` history. The deserializer prunes at every
level, so nothing outside the projection is touched.

## 4. Why now / what it composes with

- **Completes projection pushdown.** Flat → tree is the deferred §7 item; it's the difference between
  "prune the top level" and "read exactly the shaped subtree."
- **Finishes the reference-property refinement.** `relations(Device) { fields(NAME) }` becomes
  real (today the whole `Device` materialises).
- **Unblocks a File-centric data model.** `File.FileHistory` is the first; the same nested
  projection serves any future "entity + shaped relations" read (folders, shares, …).

## 5. Alternatives

**Nested-projection syntax** (engine is identical for all but (c) — they differ only at the DSL):
- **(a) `relations(X) { … }` trailing lambda — chosen.** Mirrors `select { }`; nests to any
  depth; one engine change.
- (b) `relations(X)( select(…) )` — same power, clunkier (double-call; `select(…)` as a free
  expression). Rejected on ergonomics.
- (c) client `…select(relations(X)).map { it.x.select(relations(Y)) }` — **N+1 round-trips +
  client-side join.** No platform change; the *stopgap* that works today, not the target.
- (d) dotted path `relations(X then Y)` — breaks on branching / depth. Rejected.
- (e) handle carries its sub-projection `relations(X.select { … })` — a reusable
  "projected handle" variant of (a); (a) wins at the call site. Keep in mind for reuse.

**Reverse relation:** maintained index (recommended, O(1), no scan) vs filter-backed dynamic
relation (simpler, but whole-log scan + reverse-join chain rooting) vs the realm-wide
`modifications.filter` we shipped (works today, whole-log scan, no File-rooted ergonomics).

## 6. Threat model

Read-side only — no relay data, no wire shape, no protocol change; A1–A4/A9 untouched, House
Rule #2 not triggered. If anything, the tree projection *reduces* what a read materialises
(strictly less data touched). `File.FileHistory` surfaces the same `FileModification`s already
covered by `file-service-history.md` §4.5 (and the device-name visibility note, T-C-10) — no
new asset. A one-line confirmation note in `docs/sync/threat-model.md` at most.

## 7. Phases

1. **Tree `Projection` + deserializer threading (`:core-common`).** `Projection.children`;
   recurse with the child projection (§3.1); thread `Projection?` through the relation
   resolution path. Tests: a counting `getEntry` proves a 2-level projection touches only the
   named subtree (omitted relations/fields at each level are never fetched); `projection == null`
   stays byte-for-byte today's behaviour.
2. **Nested `select` DSL (`:core` + `:core-common`).** `relations(handle) { … }` overload;
   `captureProjection` tree build; `SelectImplementation` recursion. Tests: engine-level
   `select { relations(A) { relations(B) } }` resolves the 2-level tree; a counting store
   asserts no over-fetch.
3. **`File.FileHistory` reverse relation (`:file-service`(`-common`)) — LANDED.**
   Introduces a reusable **`entityRelation`** primitive first (the one-to-many counterpart of
   the reference value property, `core-reference-property.md`): a list-valued `Property` resolved from a per-entity array
   keyed by the receiver's own `DataId` (an `EntityPropertyDataId`). `File.FileHistory` is its
   first consumer, populated by an **additive** `FileHistoryRelationMaintainer` (it owns only the
   per-file mirror; the realm-wide `FileModificationRelationMaintainer`'s retention cap + tombstone
   cascade `deleteData` rows that fan out to *every* observer, so the per-file array is bounded +
   cascaded for free). **Deviations from this plan:** the end-to-end test lives in
   `:file-service-proxy` (the maintainer's home + the real `FileService` client→proxy chain), not
   `:file-service-common`; and the verified leaf is `relations(FileModification.Device)` **without**
   the `{ fields(NAME) }` sub-block (the sub-field optimisation is the deferred §8.6 read-pruning).
   `identity-service-common` is a test-only dep for the Device leaf.
4. **Drive consumer swap — LANDED.** `TenshuFilesRepository.fileActivity` now uses the File-rooted
   query — `root.files.navigateTo(parent).search<File>{path==X}.ifEmptyError().relation(File.FileHistory).select{…}.flow()` —
   replacing the realm-wide `modifications.filter { fileId == path }`. **Required a small platform
   addition first:** `Entity.relation(handle)` — a new chain accessor in `:core` that appends a
   `PropertyTypeReference` step from a chain entity and returns a `TList<E>` chain RO. It's the
   list-FIELD navigation `root.files` analogue for a list-valued *property-relation*: the engine
   already dispatches `resolveReference → resolveProperty → resolveEntityRelation` to an
   `ArrayDatabaseReference` that streams via `setArrayListener` like any other list-relation chain,
   so the accessor only wires the missing client-DSL entry point. Reactivity is preserved
   end-to-end (the `FileDetailsViewModel`'s `.collect { activity → … }` now observes only this
   file's array, O(this file's mods) instead of the realm-wide scan). The accessor is shape-typed
   `Property.Value<TList<E>>` and works for every present and future list-valued static property;
   other static-property kinds would add their own overloads on the same mechanism.
   Verified: `:drive-shared:desktopTest` (`FileDetailsIntegrationTest`, `FileDetailsScreenTest`).

## 8. Open questions

1. **Absent-child semantics.** A kept composite field / relation with no nested block ⇒
   materialise fully (back-compat) vs one-level. Recommend "fully" so existing flat selects are
   unchanged; nesting opts into deeper pruning. Settle in Phase 1.
2. **`skipUnprojectedComposites` per level.** The flag is a top-level safety gate (intervening
   predicate readers). At nested levels the element is freshly resolved by the projection, so it
   can default to `true`. Confirm against the predicate-before-select cases in Phase 2.
3. **Maintained index vs filter for `File.FileHistory`.** Index is the recommendation; gate the
   filter-backed dynamic relation behind reverse-join chain support if ever needed.
4. **`search`-by-path entry point.** `files.search { path == X }` over the tree vs a flat
   files index — files live under `root` as a tree today, so the entry is `root.files.traverse().search`
   or `navigateTo`. Confirm the cheapest single-file lookup (likely a direct `DataId` read by
   `StringDataId(File.ID, path)`).
5. **Read-pruning threading — STILL DEFERRED after Phase 3.** Phase 2's `selectItem`
   recursion shapes the *output* but resolves each nested relation fully (`resolveProperty` →
   `getData` with no sub-projection), then projects. To prune the *read*, thread the relation's
   `Projection.children[ref]` sub-projection through `resolveProperty`/`getData` so the
   deserializer's tree pruning (Phase 1) applies. Only observable on composite-bearing nested
   reads — the Phase 3 primary path (`relations(Device)` leaf, no sub-block) doesn't need it, so
   it stayed deferred. Pick it up when a consumer wants `relations(Device) { fields(NAME) }` to
   actually read only the device name.
6. **Nested single-relation decode — RESOLVED in Phase 3 as fork (A).** Projecting a relation
   through a nested block runs each element through `selectItem`, which returns an **`Entity`**.
   So `File.FileHistory`'s rows come back as `Entity<FileModification>`, **not** `FileModification`
   — the handle's `TList<FileModification>` element type is unsound for the nested case (the *bare*
   `relations(File.FileHistory)` path returns composites; nested returns entities). **Decision:
   consumers read nested-projected rows as `Entity`** (read the list via `as? TList<*>`, then each
   row's fields/relations through `Entity` accessors — `getFieldValue` / `get(handle)`). Forks (B)
   Entity-aware handle decode and (C) `selectItem` preserves the composite type were NOT taken
   (the primary list+leaf path doesn't need them; the single-reference-with-sub-block decode-null
   is subsumed by the deferred §8.5 read-pruning).
7. **Nested `select` DSL bug — FIXED in Phase 3.** Phase 2 shipped the nested
   `relations(handle) { … }` overload with a `ListBuilder<Selector<*>>` (star-projection) block
   receiver and **no runtime test**. `fields`/`relations` are `ListBuilder<Selector<T>>` extensions
   and can't infer `T` against `Selector<*>`, so inside the nested block they silently resolved
   against the *enclosing* select builder — flattening nested selectors to the top level (and
   resolving them against the wrong entity; `relations(File.FileHistory) { relations(Device) }`
   failed with "deviceId not found in File"). Fix: a concrete `ListBuilder<Selector<Data>>` block
   receiver. Benefits **every** nested-projection consumer, not just history.

## 9. Non-goals

- Arbitrary correlated queries / a join engine — this is shaped tree reads + one reverse
  relation, not general SQL.
- A wire/protocol change — projection stays a read-side proxy concern.
- Removing the flat `modifications.filter` accessor — it stays valid (realm-wide history views
  will want it); this adds the File-rooted path alongside.
