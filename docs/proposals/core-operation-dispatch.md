# Core — Operation dispatch, receiver polymorphism, and the `Select` simplification

**Status: Direction note (research complete, no code planned).** Written 2026-07-13
after the `thenBy` work surfaced the question "does the platform support
polymorphism for operations, the way it does for traits?" The answer is *no*, and
this doc records what exists today, the cheap near-term `Select` cleanup, and the
pattern to reach for when a real need arrives — so we don't build dispatch
machinery speculatively.
Author: Benedikt + Claude.

---

## 1. What dispatch is today: single dispatch on the op-id string

- `ImplementationFactory` (`core-common/.../proxy/executor/ImplementationFactory.kt`)
  is a flat `when (id) → OperationImplementation`. The factory type is
  `fun interface OperationImplementationFactory { operator fun invoke(operationId: String): … }`
  — the id is the *only* input; the caller/receiver cannot participate in routing.
- `ExecutionContext.execute` selects the impl from the id, then `when`s on the
  **implementation kind** (Pure / Sync / Async execution shape) — not the caller.
- The receiver's runtime type *is* computed at dispatch time
  (`actualReceiverType`, `EvaluateCallstack.kt` ~459) but is used **only to
  validate** (`checkReceiverCompatibility` → `InvalidCallerError`). It never
  selects an implementation; after the check it is discarded.

**`OperationReceiverHolder` / `narrowedElementType` is not dispatch.** It is
chain-construction plumbing (ADR 0009): `findOperation` back-populates the
receiver onto the operation RO so `receiverElementType` can type the *returned*
RO (explicit narrowing → receiver's element type → `Data.Schema`). Used by
`search`/`searchAll`/`filter`/`traverse`/`sortBy`. It propagates element types
forward; it plays no part in choosing an implementation.

**Traits are the genuine receiver-keyed mechanism** — the reference point.
`TraitResolverImpl.findTraitInTypeHierarchy` resolves a behaviour object from
`(typeId, traitId)` and **walks the schema parent chain**, so subtypes inherit.
That is real, inheritance-aware, type-keyed resolution — and operations have
nothing equivalent.

---

## 2. The `Select` shape — three op-ids, one impl, internal `when(caller)`

`select` exists as **three distinct operations** with their own DSL entry points
and schemas (differing in `receiver`/`returnType`): `Select.ID` (list),
`MaybeSelect.ID`, `EntitySelect.ID`. All three share the selectors param key and
are bound in `ImplementationFactory` to **one shared `SelectImplementation`**,
which then re-discriminates at runtime:

```kotlin
return when (caller) {
    is Maybe<*>  -> selectFromMaybe(…)      // + a further when(Some/Empty) inside
    is Entity<*> -> selectItem(…)
    else         -> /* list path */
}
```

The static receiver knowledge the DSL had is discarded and rediscovered with
`is`-checks. This is the "polymorphism we don't like": a manual stand-in for
dispatch, living inside one implementation.

### 2.1 The simplification (when we get to it): split per id — no new machinery

Because the three IDs already exist, cleaning this up needs **zero new dispatch
infrastructure**: bind each id to its own implementation in the existing factory —

```
Select.ID       → ListSelectImplementation
MaybeSelect.ID  → MaybeSelectImplementation
EntitySelect.ID → EntitySelectImplementation
```

— with the shared projection core (`selectItem` / `applyProjection` / the
pending-marker logic) extracted into a common helper the three thin impls call.
The `when (caller)` disappears; each impl's `caller` cast is exact; routing stays
id-keyed. The DSL layer already performed the "dispatch" statically by picking
the op id — the engine just has to stop ignoring it.

Not needed today (the branching works and is tested); do it opportunistically,
e.g. the next time `SelectImplementation` is touched for another reason.

---

## 3. Future pattern: receiver-keyed dispatch — build it when a real case arrives

True dynamic dispatch is only *needed* when **one op-id must serve an open set of
receiver types** — where the DSL cannot statically split ids the way `select`
did. No such op exists today. When one arrives, the design that fits this
codebase:

- Key the implementation registry on **`(opId, receiverSchemaId)` with
  parent-chain fallback**, mirroring `TraitResolverImpl.findTraitInTypeHierarchy`
  (the working precedent for inheritance-aware, type-keyed resolution).
- The receiver type id is already computed at the dispatch site
  (`actualReceiverType`) — today it feeds only validation; it would feed the
  lookup instead of being discarded.
- Changes localise to `ImplementationFactory` + `ExecutionContext.execute`. The
  **wire is unaffected**: an `OperationDto` stays keyed by op id; which impl
  runs is a proxy-side concern.

### 3.1 When polymorphism is the *wrong* tool

Two look-alike cases that do **not** want receiver-keyed dispatch:

- **A variant with different params** (e.g. a case-insensitive
  `equalTo(other, ignoreCase = true)`): the extra param makes it a different DSL
  call and different op params regardless — there is nothing for the receiver to
  select. The type safety belongs at the **Kotlin extension-receiver level**
  (declare it on `TString`, and it is compile-time impossible on a non-string),
  while the wire rides an optional param on the existing op (the additive-tail
  pattern `SortBy.THEN_SELECTORS` used). Dispatch stays monomorphic.
- **Closed-set value branching** (the `equalToLogic` / `containsLogic` /
  `compareKeys` `when`-ladders over `TString`/`TInteger`/`TBoolean`/…): the
  primitive value types are a closed, stable set. A `when`-ladder over them is
  legible and cheap; polymorphic dispatch earns its keep on *open* sets
  (composites, feature types). Leave these as they are.

---

## 4. Adjacent finding — case-sensitivity defaults were inconsistent (RESOLVED)

Surfaced by the same research; **decided 2026-07-13 → align with the norm**
(implemented):

- `containsLogic` was unconditionally **case-insensitive**
  (`contains(needle, ignoreCase = true)`) — while borrowing Kotlin's exact
  `infix contains` syntax, whose stdlib semantics are case-*sensitive*. One
  production call site (Drive search), where the flag was invisible.
- `equalToLogic` and `SortByImplementation.compareKeys` were already
  case-**sensitive** (plain `==` / `compareTo`).

The norm in both languages (Kotlin, Swift, Java, JS) and query systems (Django
`__contains` vs `__icontains`, Postgres `LIKE` vs `ILIKE`, NSPredicate
`CONTAINS` vs `CONTAINS[cd]`) is **case-sensitive defaults + an explicit
insensitive variant**. As-built, following that principle —
*case-sensitive engine defaults, explicit opt-in at the call site*:

- `contains` reverted to case-sensitive; a new non-infix
  `contains(other, ignoreCase)` overload (Kotlin's own shape) carries the
  opt-in as an **optional op param**, so plain predicates stay byte-identical.
  Drive search opts in explicitly — user-visible behaviour unchanged.
- Case-insensitive *sort* is expressed in the selector —
  `sortBy { name.lowercase() }` via a new `lowercase()` chain transform
  (sibling of `substringAfterLast`) — not as hidden comparator policy; the
  engine comparator stays case-sensitive like Kotlin's `sorted()`. This is
  also a §3.1 case in action: the variant-with-a-param needed **no**
  receiver polymorphism, just a TString-scoped extension.

---

## 5. The third predicate — `inRange`, and why it is in `:core` (SHIPPED 2026-08-11)

For most of the platform's life the chain DSL had exactly **two** predicates, `equalTo` and
`contains`. That is enough to ask *which* row, and not enough to ask *when* — a **time
window is not expressible at all**, which is a much bigger hole than it sounds, because
almost every question a person asks about their own data is bounded in time.

The workaround shipped twice before it was named: `photosCapturedBetween` (`timeline-photos.md`
T-P2) is a bespoke per-service operation that exists *only* because the predicate did not,
and its own KDoc says so — "the DSL has no comparison predicate … so a time *window* is not
expressible in a chain at all". Mia's MQ-1 grammar then had to refuse date questions outright
(*"I can only search files by name, not by date yet"*).

**Decision (2026-08-11): add the predicate to `:core` rather than an op per service.**

T-P2 weighed exactly this and chose the op — correctly, on the information it had: it was
costing "wire work on the hottest shared path" against **one** caller, for a filter that
still scans. What changed is the count. `mia-query-layer.md` MQ-2 needs the same window over
Timeline moments, Fit workouts and Calendar events, and MQ-1 left the file case open — so the
alternative is now **four bespoke ops**, each with its own id, implementation, client RO,
common registration, baseline entry and proxy-side scan. One predicate is less total wire
surface than four operations, and it is the shape every future consumer already expects.

⚠ **What it does not change.** `inRange` is a predicate, not an index: it narrows what
crosses the wire and what a caller materialises, never what the store walks. T-P2's "a filter
that still scans" remains true, and a per-service op is still the right answer where the scan
itself is the cost (which is why `photosCapturedBetween` stays — it also *ranks*, which no
predicate does).

**As built** — deliberately one operation, not a family:

- **`inRange(startInclusive, endExclusive)`, half-open.** ⚠⚠ Half-open because every other
  range in this codebase is (VAT filing periods in commerce WC-2; Mia's `TimeWindow`), and
  disagreeing would put a double-counting bug at every window boundary one refactor away.
- ⚠ **No `greaterThan`/`lessThan` siblings, on purpose.** There is **no boolean combinator**
  in the DSL — no `and`, no `or` — so a pair of open-ended comparisons could not be composed
  into a window anyway, and neither has a consumer alone. One op answers every case that
  exists.
- **Numeric only**, which covers every timestamp for free: `Instant : TInteger`, so a
  `UnixInstant` field compares as an ordinary integer and no temporal sibling is needed.
- ⚠ **Absent is never in range, and an empty range matches nothing.** A row whose field is
  missing drops out of every window (the `PhotosCapturedBetween` call: without a timestamp a
  row cannot be placed in time, and reading absence as `0` would file it under whichever
  window reaches back furthest). `inRange(x, x)` is the empty interval and returns `false` —
  silently widening it to "no filter" would turn a caller's arithmetic slip into a full scan
  that looks like a successful answer.
- Non-numeric receiver or bound → `false`, the `containsLogic` posture: no error, no
  coercion, no guess.

**Compatibility.** A new operation reshapes nothing, so the schema baseline gains one entry
and changes none — **no `TENSHU_STORAGE_FORMAT_VERSION` bump** (the same reasoning that
applies to any brand-new type). ⚠ A client emitting `inRange` to a proxy that predates it
meets **E3-1's typed `UnsupportedOperationError`** (`core-schema-evolution.md` §9.3.1) rather
than a cryptic `error/internal` — which is precisely the case that phase was built for.


---

## 6. Adjacent finding — a typed RO is what makes a type *filterable* (RESOLVED 2026-08-11)

Surfaced by MQ-2a, and it is a sibling of §4 in kind: an invisible default that had been got
right by accident in some places and not others.

**Two factories, two jobs, no connection between them.** `dtoFactory` builds a row when it is
**read**. `roFactory` builds the **receiver** a chain-DSL predicate lambda runs against. A
composite registered with only the former reads perfectly — rows materialise, projections
work — and `filter<T> { … }` throws `class CompositeRo cannot be cast to class T` from inside
`FilterRo`, naming neither the module nor the registration that caused it.

⚠⚠ **And a client module can shadow a common module's correct registration**, because
`configs.asReversed()` means the later config wins. So "I registered a typed RO" is not
sufficient — it has to be the *last* one registered for that type.

At the time of writing the repo had **15** composites whose RO was a bare `CompositeRo`, and
three whose RO was typed (`FileModification`, `Workout`, `HomeDevice`) — the difference being
only whether somebody had written `object : CompositeRo(…), X {}`. All 15 are fixed, and
`TypedPredicateReceiverTest` (`:system-test-utils`) now judges every registered composite in
the service graph and prints the exact replacement. ⚠ Zero positional shapes changed, so this
carried no wire impact at all.

The correct form:

```kotlin
roFactory = { chain, ctx ->
    object : CompositeRo(chain, ctx, X.Schema), X {
        override val schema: CompositeSchema get() = X.Schema
    }
}
```
