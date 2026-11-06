# Deserializer Projection Pushdown — Read Only What `select` Asked For

**Status:** Implemented on `feat/deserializer-lazy-composite` (one `feat(core)` commit,
rebased on master; not yet merged). The full pushdown described below is in place: the
lazy `CompositeDatabaseReference` seam (`Database.getComposite`), the `Projection` producer
captured from `select` at the chain boundary (`captureProjection` in `EvaluateCallstack`,
threaded into `getArrayData`), and the projection-honouring deserializer. Verified green at
the test level — `:core-common:desktopTest` (incl. `CaptureProjectionTest` + projection
cases in `TenshuProtocolObjectDeserializerTest`) and the `:sync-service-proxy:jvmTest`
ClassCastException canary (§2). **Remaining before this is a settled win:** measure the
end-to-end over-fetch reduction on a real listing surface (HomeScreen recent-files);
on-device verification; and the single-entity `getData` projection path (only `getArrayData`
captures a projection today — list-read over-fetch is the target). Author: Benedikt + Claude.

**Scope:** `:core-common` —
`proxy/db/TenshuProtocolObjectDeserializer.kt` (`deSerialize`, `deserializeArray`),
`proxy/db/DatabaseImpl.kt` + `proxy/Database.kt` (`getData` / `getArrayData` projection
param), `proxy/EvaluateCallstack.kt` (projection capture at the materialisation boundary),
`proxy/SelectImplementation.kt` (selector → projection extraction),
`proxy/ReferenceResolver.kt` / `proxy/ExecutionContext.kt` (resolution seam — already in
place). No wire-format change. No storage-format change. Semantics preserved; the only
observable difference is *how much of the graph a read touches*.

**Supersedes:** the "Phase 1 (unconditional composite deferral)" half of the
`docs/tech-debt.md` entry *"Database Deserializer: Eager Deep Recursion + Projection-Blind
Reads"*. See §2 for why Phase 1 cannot stand alone.

---

## 1. Motivation

`TenshuProtocolObjectDeserializer.deSerialize` materialises a stored object's graph **eagerly,
to indefinite depth**, and **without consulting the query's projection**. A
`select { fields(NAME) }` over a list of entities still loads every entity's full composite
subgraph (composite scalar fields + composite relations + their children …), then throws all
but `NAME` away inside `SelectImplementation`. The list-field lazy seam
(`ArrayDatabaseReference`) covers one axis; composite scalar fields and composite relations
are walked eagerly. Every metadata-listing surface pays this per row — the canonical victim
is HomeScreen recent-files, where each `File` row pays its full relation graph.

The fix is to make the projection a **read-time pushdown**: `deSerialize` should load only the
fields/relations the chain's `select` actually named, and skip the rest.

## 2. Why unconditional deferral (the original Phase 1) is unsafe

The first attempt deferred *every* composite scalar field on *every* entity load behind a
`CompositeDatabaseReference` (resolve-on-use), independent of any projection. It compiled and
passed `:core-common`, but `:sync-service-proxy:jvmTest` went red with **7
`ClassCastException`s**:

```
class CompositeDatabaseReference cannot be cast to class org.tenshu.sync.ScheduleSpec
class CompositeDatabaseReference cannot be cast to class org.tenshu.sync.ConstraintSpec
class CompositeDatabaseReference cannot be cast to class org.tenshu.core.time.HybridLogicalClock
```

Root cause: real schemas (`ScheduleItem` → `ScheduleSpec` / `ConstraintSpec` / `RetryPolicy`;
`DeleteBlock` → `HybridLogicalClock`) carry composite **scalar** fields that are read via
**typed accessors** — `val x: ScheduleSpec get() = getField(Field.X)` — which do an unchecked
`as T`. These reads happen *directly on a loaded entity*, **not** through a `select` chain
(ROs, repositories, mergers, the sync processor). The `ArrayDatabaseReference` seam gets away
with unconditional deferral only because list-field readers were migrated to handle the
reference long ago; composite-scalar readers never were, and migrating all of them is a large,
brittle blast radius.

There is **no field-level signal in plain `getData`** that tells the deserializer whether a
field will be read directly. That signal exists only in the **projection**:

> **Safety insight.** A field a `select` *omits* is dropped from the projected output entity
> entirely — so no downstream reader of that output can observe it. Therefore the deserializer
> may safely **skip** (not load, not even reference) any field outside the projection, *when
> and only when* it knows the read is feeding a `select` that omits it.

This is what makes projection-gating safe where unconditional deferral was not.

## 3. Design

### 3.1 An optional projection on the read path

Introduce a lightweight `Projection` (a set of field refs + relation refs — effectively the
`select`'s selector set already modelled by `Selector.FieldSelector` /
`PropertySelector` / `DynamicPropertySelector`):

```kotlin
data class Projection(
    val fields: Set<String>,       // FieldReference ids to materialise
    val relations: Set<String>,    // PropertyTypeReference ids to materialise
)
```

Thread it as an **optional** parameter (default `null` = "load everything", today's behaviour):

```
Database.getData(dataId, asEntity, projection: Projection? = null)
Database.getArrayData(reference, projection: Projection? = null)
TenshuProtocolObjectDeserializer.deSerialize(..., projection: Projection? = null)
TenshuProtocolObjectDeserializer.deserializeArray(..., projection: Projection? = null)
```

### 3.2 Per-field behaviour in `deSerialize` when a projection is present

| Field kind | In projection | Not in projection |
|---|---|---|
| value field | materialise (cheap) | materialise (cheap — no recursion, simplest to keep) |
| list field (`ArrayDatabaseReference`) | reference (already lazy) | reference (already lazy) |
| **composite scalar field** | materialise (one level; its *own* nested composites may defer — §3.4) | **skip** — omit from the entity |
| **composite relation** | materialise | **skip** — omit from the entity |

"Skip" means the field/relation is simply absent from the constructed entity. `select` builds
its output from the projected selectors only, so the absence is invisible. With `projection ==
null` the table collapses to today's eager behaviour — **the default path is unchanged**, which
is why the CCE-prone direct readers stay correct (they never pass a projection).

### 3.3 Capturing the projection from the chain (the chain→read boundary)

The hard part: `select` appears *after* the producer (`searchAll` / `search`) in the callstack,
but the read happens when the producer materialises. In `evaluateCallstack`, a producer
resolves to an `ArrayDatabaseReference` (or a single entity), and subsequent ops accumulate on
its `executionStack` until a terminal forces materialisation
(`executionContext.getArrayData(ref)` at `EvaluateCallstack.kt:142 / :237 / :259`).

At that materialisation point, scan the deferred op stack and, **only if the first
field-touching op is a `Select`**, extract its selector set into a `Projection` and pass it to
`getArrayData` / `getData`:

- `searchAll().select { … }` / `search { … }.select { … }` → **pushdown applies** (the common
  metadata-listing shape, and the HomeScreen / FileDetails consumers).
- `searchAll().filter { … }.select { … }` → **no pushdown in v1.** `filter`'s predicate may
  read fields the projection omits, so we cannot project before it. Fall back to a full load.
  Same for `sortBy` on a non-projected key. Recognising when an intervening op is
  projection-safe is a **query-planner** concern (see §7) deferred past v1.

`SelectImplementation` gains a small helper that turns its `List<Selector<T>>` into a
`Projection`; `EvaluateCallstack` calls it when it detects the leading-`select` shape.

### 3.4 Nested deferral reuses the landed `CompositeDatabaseReference` groundwork

The primary win (§3.2) is *skipping* non-projected composite fields/relations — that alone
removes the bulk of the over-fetch and needs no deferred node. The landed
`CompositeDatabaseReference` seam (§6) provides the *secondary* win: a composite field that
**is** projected can be materialised one level deep with its own nested composites left as
deferred references, resolved only if the consumer navigates deeper. This is opt-in per the
same projection rules and is where the §6 groundwork pays off.

## 4. Implementation phases

1. **`Projection` type + threading** — add the optional param through `Database.getData` /
   `getArrayData` / `deSerialize` / `deserializeArray`, defaulting to `null`. Pure plumbing,
   no behaviour change, all existing tests green.
2. **`deSerialize` honours the projection** — skip non-projected composite fields + relations
   when a projection is present (§3.2). Test at the deserializer level with a counting
   `getEntry` (assert omitted-field entries are never fetched) on a synthetic
   composite-bearing fixture.
3. **Chain capture** — `SelectImplementation` selector→`Projection` helper +
   `EvaluateCallstack` leading-`select` detection + pass-through to `getArrayData`/`getData`
   (§3.3). Integration test: `searchAll().select{ fields(NAME) }` over entities with a
   composite relation, asserting the relation entry is never read.
4. **Nested deferral (optional)** — for projected composite fields, emit
   `CompositeDatabaseReference` for their nested composites (§3.4), reusing the landed seam.
5. **Relations** — extend §3.2's skip to the `dbEntry.properties` arm; same projection gate.

Phases 1–3 deliver the headline win and are independently shippable. 4–5 are follow-ups.

## 5. Testing

- Deserializer-level: counting `getEntry` proves omitted composite fields/relations are never
  fetched; projected ones are; `projection == null` is byte-for-byte the old behaviour.
- Engine-level: `EvaluateCallstack` / `Select` integration over a real `Database` +
  `ObjectStore`, asserting the leading-`select` shape pushes down and the
  `filter`-before-`select` shape does **not**.
- Regression: full `:core-common`, `:file-service-proxy`, `:sync-service-proxy` suites — the
  last is the canary that caught the unsafe Phase 1 (composite-scalar direct readers must keep
  seeing materialised values on the `projection == null` path).

## 6. Landed groundwork (`feat/deserializer-lazy-composite`)

The reusable, behaviour-neutral seam is already implemented and green (no producer wired, so
it is inert until this proposal's Phase 2–4):

- `CompositeDatabaseReference` (`proxy/CompositeDatabaseReference.kt`) — a `Data` node carrying
  `dataIdHash` + declared `schema` + `isEntity`. **Not** a `DatabaseReference`: a single
  composite has no lazy-chain semantics, so it resolves *eagerly on first use* rather than
  accumulating an execution stack. Caches nothing (House-Rule-6 safe).
- `Database.getComposite(ref)` + `DatabaseImpl` impl — lock-guarded, resolve-by-hash,
  materialised before return (mirrors `getArrayData`'s race-avoidance).
- `ReferenceResolver.getCompositeData` + `ExecutionContext.getComposite`.
- `EvaluateCallstack` resolves a `CompositeDatabaseReference` at the top of the loop and at
  final materialisation; `SelectImplementation.selectItem` resolves it when the projection
  names the field; `writeValuesToDb` writes back its `dataIdHash` on re-save (so re-saving an
  entity holding an unresolved deferred field doesn't crash or rewrite the child).

## 7. Risks / open questions

- **Intervening ops (query planner).** v1 only pushes down when `select` leads. General
  `filter`/`sortBy`-then-`select` pushdown needs field-dependency analysis of the predicate /
  sort key — the same query-planner direction flagged in `docs/tech-debt.md` "Executor
  Abstraction → Trade-off Worth Flagging." Out of scope here.
- **Partial-load cache coherence.** If any read-through cache keys entities by `dataId`, a
  projection-limited entity must not be cached as if it were the full entity. The current
  `DatabaseImpl` does not cache materialised entities (only `hashToDataId`), so this is a
  no-op today, but any future entity cache must be projection-aware.
- **`Projection` representation.** Modelled here as flat id sets; nested projections
  (`select` inside `select`) are not expressed. v1 treats a projected composite as
  "materialise one level"; deeper shaping is future work.
```
