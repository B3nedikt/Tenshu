# Chain DSL — `sortBy` / `sortByDescending` Chain Steps

**Status:** **Landed** (2026-05-22). The `sortBy` / `sortByDescending` chain step,
its RO + engine impl, the value comparator, the `Comparable` trait (declared;
composite-key resolution deferred), wiring, and a pin + runtime test suite are in
`:core` / `:core-common`. Value sorting works end-to-end; composite-key sorting
errors with a pointer to the trait. See "What landed" below for deviations from
the original sketch.
Author: Benedikt + Claude (post-HomeScreen recent-files discussion, 2026-05-21).

## What landed (vs. the original sketch)

- **`Comparable` is a trait, but only for composites; values are hardcoded.**
  The decisive finding: `ValueSchema` is not a `CompositeSchema`, and
  `DataSchema.hasTrait` only inspects traits on `CompositeSchema`
  (`core/.../schema/DataSchema.kt`), so a trait attached to `TInteger.Schema` /
  `TString.Schema` / `TBoolean.Schema` would be **invisible**. The split falls
  along that line: `Comparable` (`core/.../trait/Comparable.kt`) marks
  *composite* schemas (where `hasTrait` works); the primitive value types are
  compared by a hardcoded `when` in `SortByImplementation` (where the trait can't
  reach). This is the §6.7/§6.8 seam resolved with a concrete blocker, not a
  free choice.
- **Composite-key resolution is deferred but the trait is consulted.**
  `SortByImplementation` reports two distinct errors for a composite sort key —
  "is Comparable, resolution pending (§6.7)" vs. "not Comparable, add the trait"
  — so the declared trait isn't dead code. The key-extraction callstack
  (composite flavour) lands with the first composite-sort consumer.
- **Selector returns `TValue`, not a `K : TValue` type parameter.** Kotlin
  forbids partial type-argument specification, so a second type parameter would
  force `sortBy<Subtype, Key> { … }` at narrowing call sites. The return type
  only depends on the element type `T`, so `K` was dropped.
- **Comparability is enforced at runtime** (the "registration/runtime rejection"
  path), not via a compile-time value-marker — the marker remains the deferred
  nicety in §6.8.

**Scope:** `:core` (`collection/list/SortBy.kt`, new), `:core-common`
(`impl/array/ListSortByRo.kt`, `proxy/SortByImplementation.kt`,
`module/CoreCommonModule.kt`, `proxy/executor/ImplementationFactory.kt`,
`ChainEntityTypingPinTest` extension or sibling pin test), `:core` schema-side
`Comparable` **trait** on the primitive value schemas (`TInstant`, `TLong`,
`TInteger`, `TString`, `TBoolean`), with a composite flavour (callstack-bearing)
deferred — see §2.2.

**Wire format:** new chain-step schema `SortBy.Schema` (and `SortByDescending.Schema`),
structurally a sibling of `Search.Schema`/`Select.Schema` — single selector lambda
returning a comparable schema field reference. **Storage shape:** unchanged.
**Semantics:** new — produces a re-ordered `TList<E>` (or `TList<Entity<E>>` when
chained after `searchAll`/`select`). Materialisation is forced at the sort step.

This proposal extends the chain DSL of
([`core-chain-entity-typing.md`](core-chain-entity-typing.md)) with a
sort-by-comparable-key chain step. It is a pure DSL/engine extension — no sync,
storage, or schema-on-the-wire changes.

It is the smaller of two proposals that grew out of the HomeScreen recent-files
discussion. The other —
[`file-service-history.md`](file-service-history.md) — proposes synced
`FileModification` history and is the substantive design problem; this one is the
boring mechanical enabler.

---

## 1. Motivation

`HomeScreenViewModel.init` (`:drive-shared`) currently lists the root folder and
calls it "recent files". The real spec is "the N files the user most recently
touched, regardless of folder". With the chain DSL as it stands today there is no
way to express that — `search`/`searchAll`/`select` filter and project, but
neither orders.

The end-state shape we want, once both this proposal and the file-history
proposal land:

```kotlin
allFiles
    .sortByDescending { it.lastModifiedAt }
    .take(20)
    .select {
        fields(File.Field.PATH, File.Field.CREATED_AT)
        relations(File.Relation.Size, File.Relation.LastModifiedAt)
    }
    .flow()
```

`sortBy` is the missing chain step. It mirrors Kotlin stdlib's
`Iterable.sortBy { … }` / `sortByDescending { … }` shape (lambda returns the
comparable key); it composes cleanly with `searchAll`'s `TList<T>` return and
`select`'s `TList<Entity<T>>` return.

Even without file history this unblocks every "ordered list" UI we don't have
today (by name, by size, by created-at when we add it).

### 1.1 Why a chain step, not an `Operation`

Sorting on its own does nothing useful — the chain `allFiles.sortBy { … }` makes
sense only when followed by `.take(N).flow()` or `.searchAll … .select … .flow()`.
That's the same shape rule §2 of the chain-DSL proposal already established
(`search`, `select`, `searchAll` are chain steps for the same reason). Modelling
sort as an `Operation` would force every caller into a two-step
`val sorted = sortBy(list); sorted.take(20)` dance and lose the lambda-DSL
ergonomics that make `search`/`select` pleasant.

The wire-level `OperationDto` per chain step is unchanged — sort is just one
more `OperationDto` in the chain.

---

## 2. Design

### 2.1 `sortBy` / `sortByDescending` overloads

Mirrors the `search` overload split from `core-chain-entity-typing.md` §2.1:

```kotlin
// Default: TList<File>.sortBy { it.createdAt } → TList<File>
@JvmName("sortByInferred")
inline fun <reified T : Data, K : TValue> TList<T>.sortBy(
    noinline selector: (T).() -> K
): TList<T> = sortByInternal(selector, descending = false)

// Narrowing: TList<*>.sortBy<File> { it.createdAt } → TList<File>
@JvmName("sortByNarrowed")
inline fun <reified T : Data, K : TValue> TList<*>.sortBy(
    noinline selector: (T).() -> K
): TList<T> = sortByInternal(selector, descending = false)

// Entity-shaped overload after .select: TList<Entity<File>>.sortBy { … }
@JvmName("sortByEntityInferred")
inline fun <reified T : Data, K : TValue> TList<Entity<T>>.sortBy(
    noinline selector: (Entity<T>).() -> K
): TList<Entity<T>> = sortByInternal(selector, descending = false)
```

`sortByDescending` is identical with `descending = true`. The two flavours share
the same `SortByImplementation`; the descending flag rides as an Op param so the
engine reverses the comparator after evaluation.

Three overloads cover the three shapes the chain DSL produces (`TList<T>` from
`searchAll`/raw lists, `TList<Entity<T>>` from `select`, narrowing for explicit
`<T>`). The same `@JvmName` trick from §2.1 disambiguates the erased signatures.

The selector return constraint is `K : TValue` (the Kotlin level), with
*comparability* enforced by the `Comparable` **trait** (§2.2) — checked at
schema-registration, the same way the relation system validates chains. A thin
compile-time Kotlin marker on the value types (so `sortBy { it.someByteArray }`
fails to compile rather than at registration) is a possible nicety; whether it's
worth the extra interface is a deferred call (§6). The pin test (§4) covers
whichever mechanism lands.

### 2.2 `Comparable` as a **schema trait**, not a Kotlin marker

The selector lambda's return type needs to be comparable. The first sketch made
`TComparable<Self>` a Kotlin marker interface on the value schemas — but that
only models *values* and can't express "this composite is comparable by some
derived key". Comparability is behaviour attached to a schema, which in Tenshu
is a **trait** (same shape as `Syncable`, `MergeStrategy`, the relation traits).

So `Comparable` is a trait with two flavours:

- **Value comparability** — for the primitive value schemas. A simple per-type
  compare; no chain needed. The trait just marks "this value type has a total
  order" and the engine compares the underlying values directly (`TInstant`,
  `TLong`, `TInteger`, `TString`, `TBoolean`; `TByteArray` / `TList` / `Maybe` /
  `Entity` deliberately get no value-comparable trait).

  ```kotlin
  // sketch — value flavour
  interface Comparable : Trait { companion object { const val ID = "tenshu/core/trait/comparable"; val Schema = trait(ID) } }
  // attached to TInstant.Schema, TLong.Schema, TInteger.Schema, TString.Schema, TBoolean.Schema
  ```

- **Composite comparability** — for arbitrary composites, comparing requires
  *extracting a comparable key*, which is a chain. The trait carries a
  `ChainTemplate` to the comparable value, exactly like the relation system's
  `ServiceDataRelation` / dynamic-relation traits carry a chain (ADR 0010). E.g.
  a `FileModification` could be comparable-by-`timestamp` via a
  `ChainTemplate<FileModification, TInstant> { timestamp }`. **Details deferred**
  — this is only needed once something actually sorts a list *of composites by
  the composite itself* (rather than by a projected value field). For v1, the
  selector projects to a value (`it.lastModifiedAt`, `it.timestamp`) and only
  the value flavour is exercised.

The selector's return-type constraint requires the returned schema to carry the
`Comparable` trait at the value level, so the key expression must land on a
comparable value at compile/registration time; `it.history.last()` (a
`FileModification` entity, no value-comparable trait) wouldn't satisfy it
without a follow-on `.timestamp` to reach a `TInstant`.

**Open (deferred decision):** the composite-comparability trait's exact shape —
`ChainTemplate` signature, how `thenBy` composes with it, registration-time
validation — is specified when the first composite-sort use case lands. Listed
here so it isn't lost; not designed in v1.

**Property selectors, not just field selectors.** The selector must resolve
both schema *fields* and schema *properties* to a comparable value. This
matters because [`file-service-history.md`](file-service-history.md) §3.3 moves
`File.lastModifiedAt` from a field to an eager-synced value property, and the
HomeScreen query sorts on it: `sortByDescending { it.lastModifiedAt }`. Field
access in a chain selector already works (`search`/`filter` predicates use it);
property access in a *sort* selector is the new combination. The selector
builds a chain expression, so `it.lastModifiedAt` must emit a property
reference the engine resolves and compares — confirm the chain-DSL machinery
emits property refs in selector position, not only field refs. Tracked as an
open question (§6) and in file-service-history §9.

**Open question:** do we want a `TComparator<T>` for "compare by multi-field
tuple"? I'd say no in v1 — `thenBy` chaining (§2.4) covers the multi-key case.

### 2.3 Engine — `SortByImplementation`

Single new impl class. Pseudo-shape:

```kotlin
class SortByImplementation : OperationImplementation {
    override val id = SortBy.ID
    override fun execute(ctx: ExecutionContext, caller: Data?, params: Params): TResult<Data> {
        val list = caller as? TList<*> ?: return Failure(InvalidCallerError)
        val selector = params.require(SELECTOR_LAMBDA)
        val descending = params.require(DESCENDING).get()
        val materialised = ctx.resolveList(list)        // sort forces materialisation
        val keyed = materialised.map { item ->
            // selector yields a value carrying the Comparable trait (§2.2)
            item to evaluateLambda(ctx, selector, item)
        }
        val sorted = keyed.sortedWith(comparatorFor(keySchema, descending))
        return Success(SimpleTList(sorted.map { it.first }))
    }
}
```

Materialisation is the cost. `sortBy` cannot stream — the engine has to pull
every element to compare keys. Document the implication in the chain DSL
agent docs: *prefer `sortBy(...).take(N)`*, *avoid `.sortBy(...).flow()` on
unbounded lists*.

`ImplementationFactory` routes `SortBy.ID` (and `SortByDescending.ID`, sharing
the impl) the same way it already routes the three `Select` IDs.

### 2.4 Multi-key sort — `thenBy` / `thenByDescending`

**SHIPPED 2026-07-13** (first consumer: Drive's folders-first + name-tiebreak
listing, `drive-listing-sort-display.md`). `list.sortBy(A).thenBy(B)` orders by A,
then breaks ties by B — lexicographic, each key with its own direction. Four
overloads mirror `sortBy` (lambda + property, asc/desc).

**As-built (the "second selector slot on the most recent `SortBy`" option):**

- **Schema (additive tail).** `SortBy.Schema` keeps the scalar `SELECTOR` /
  `DESCENDING` as the *primary* key untouched, and gains two **optional** parallel
  params — `THEN_SELECTORS` (a `TList<TList<Data>>`, one callstack per secondary
  key) and `THEN_DESCENDINGS` (a `TList<TBoolean>`). A single-key sort emits
  neither, so existing callers and their wire bytes are unchanged. This is a
  **same-version IPC change**, not a cross-device/peer concern: operation
  callstacks cross only the client→own-proxy boundary (Drive→Control, one app
  version); sync between devices ships storage/CRDT payloads, never chain ops.
- **RO fold (`SortByRo.thenBy`).** `thenBy` reads the preceding chain op (which
  must be a `SortBy`), and **appends a new `SortBy` op carrying the full ordered
  key list** (prior keys + the new one). It does *not* rewrite the prior op: the
  executable chain (`LocalRequestChain`/`TaskRequestChain`) wraps a builder and
  can't be truncated without knowing its concrete type, whereas `addArray`
  preserves it. Trade-off: the prior `SortBy(A)` stays and runs first — a
  redundant O(n log n) intermediate pass per `thenBy`. Negligible for the 1–2
  keys real listings use; a build-time fold behind a chain `replaceLast` is the
  optimisation if a deep `thenBy` chain ever matters.
- **Engine (`SortByImplementation`).** Reads primary + tail into an ordered list
  of `(selector, descending)` keys and compares **lexicographically** (first
  non-zero key decides, that key's direction applied), keeping the per-key value
  comparator and stable `sortedWith`. Mixed key kinds compose: a property primary
  (`sortBy(File.Size)`) + a lambda secondary (`thenBy { name }`) work together.

Tests: `SortByPropertyTest.sortsLexicographically_primaryThenSecondaryKey`
(engine comparator) + `FilesPagingIntegrationTest.thenBy_breaksPrimaryKeyTiesByName`
(RO fold end-to-end over the real proxy).

**Alternatives considered / future direction.** The other correct option is a
**distinct `ThenBy` operation** merged at evaluation, rather than folding into
`SortBy` at build time. It is *not* the naive "a `ThenBy` op that sorts again" —
that would stable-sort by B alone and invert precedence (B-major); lexicographic
`(A, B)` is "stable-sort by B **then** by A" (least-significant first), which the
most-significant-first DSL can't emit as an independent trailing sort. A correct
separate op instead reasons about its neighbours, and **the engine already has
the machinery for that**:

- `ArrayOperationContext` carries an `executionStack: List<Data>` and is passed to
  every array-op impl as its `operationContext` — so an op *can* look back to its
  preceding `SortBy` (impls are not neighbour-blind).
- `evaluateCallstack` already folds neighbouring ops: `foldWindowPrefix` consumes
  a `drop`/`take` prefix into one windowed read, `captureProjection` fuses a
  downstream `select`. A `foldSortKeys(sortBy, remaining)` peer — collect trailing
  `ThenBy` ops into one multi-key sort and consume them — would be idiomatic.

Trade-offs vs. the shipped fold: a separate op keeps `SortBy` single-purpose,
reads as a truer 1:1 chain (`sortBy` then `thenBy`), and — via `foldSortKeys` —
retires **both** the `SortBy` schema overload **and** the redundant intermediate
sort at once. Its cost is a new op schema + impl (or fusion rule). The
redundant-sort issue is *not* unique to the shipped approach: with two
independent ops you also get two sorts unless `SortBy` looks ahead and skips when
a `ThenBy` follows (or the walker consumes it) — symmetric with our
append-not-replace redundancy (which a chain `replaceLast` would fix).

Decision: the build-time fold was chosen for **economy** — one op, +2 optional
params, no new schema/registration/fusion rule — which is the right call for a
two-key tiebreak feature. **If `thenBy` grows beyond tiebreaks, migrate to a
distinct `ThenBy` op fused via `foldSortKeys`** (peer to `foldWindowPrefix`); that
is the cleaner end-state and the engine already supports it.

### 2.5 Stable sort & tie-breaking

Engine uses `sortedWith` (stable on JVM via TimSort, stable on JS via spec
since ES2019). Document the guarantee. Equal-key items retain input order;
input order is whatever `resolveList(caller)` produced (HLC-ordered for synced
lists, insertion-ordered for in-memory).

Explicit tie-break on entity ID is **not** added — it would change behaviour
for synced lists where HLC order is already deterministic. If a future caller
needs explicit deterministic tiebreaks across nondeterministic input,
`thenBy { it.id }` covers it (after §2.4).

### 2.6 Top-K opportunity (perf note, not v1)

`.sortBy(...).take(N)` with small `N` over a large list is a heap-based top-K.
The naive `sortedWith → take` impl works but is `O(M log M)` for `M` items
when `O(M log N)` is achievable. Mark as a perf TODO in the impl; the call site
in HomeScreen (`N=20`) doesn't care today.

---

## 3. Consumer migration

No existing consumers — this is a new chain step. The first consumer is the
HomeScreen recent-files query introduced in the file-history proposal.

A separate, broader audit: today's "list folders alphabetically" sort happens
client-side after the chain returns (`FilesRepository`,
`HomeScreenViewModel`). Once `sortBy` lands those consumers can move the sort
into the chain — separate cleanup, not blocking.

---

## 4. Test pinning

`core-common/src/commonTest/.../ChainSortPinTest.kt`. Same shape as
`ChainEntityTypingPinTest`:

| Chain                                                   | Return                |
|---------------------------------------------------------|-----------------------|
| `TList<File>.sortBy { it.createdAt }` (field)           | `TList<File>`         |
| `TList<File>.sortByDescending { it.lastModifiedAt }` (property) | `TList<File>`  |
| `TList<*>.sortBy<File> { it.createdAt }`                | `TList<File>`         |
| `TList<Entity<File>>.sortBy { it.createdAt }`           | `TList<Entity<File>>` |
| `searchAll<File>().sortBy { it.path }.select { … }`     | `TList<Entity<File>>` |
| Selector returning a non-comparable type                | rejected (see below)  |

Row 2 pins the **property selector** path (`lastModifiedAt` is a property per
file-service-history §3.3), distinct from row 1's field selector. The last row
guards against `.sortBy { it.history }` (a `TList`, no `Comparable` trait):
**registration-time rejection** by default (the engine refuses a key schema
without the trait), upgraded to a **compile error** if the optional value-marker
nicety (§2.2) lands. The pin asserts whichever mechanism is chosen.

---

## 5. Phases

1. **`Comparable` trait (value flavour)** attached to the five primitive value
   schemas (`TInstant`, `TLong`, `TInteger`, `TString`, `TBoolean`). Additive
   trait; no behaviour change. The composite flavour (callstack-bearing, §2.2)
   is deferred to its first use case.
2. **`SortBy.Schema` + `SortByDescending.Schema`** in `:core`, plus the three
   `TList`/`TList<Entity>`/narrowing extensions. Mirror `Search.kt`/`Select.kt`.
3. **`SortByImplementation` + `ListSortByRo`** in `:core-common`. Wire into
   `CoreCommonModule` and `ImplementationFactory`.
4. **Pin test** + integration test (`:core-common/jvmTest`) exercising
   sortBy → take → flow over a small in-memory list, asserting order.
5. **HomeScreen migration** lives in the file-service-history proposal — once
   the `lastModifiedAt` property and `sortBy` both exist, the ViewModel
   collapses to the §1 end-state.

Phases 1–4 ship in one PR; the HomeScreen consumer ships with the file-history
work.

---

## 6. Open questions

1. **`TComparator<T>` vs. `thenBy`.** I lean `thenBy`; the inline DSL stays
   Kotlin-shaped. Confirm before §2.4 lands.
2. **Top-K perf optimisation.** Worth doing in v1 (we know HomeScreen takes
   the first 20), or measure-then-optimise? My instinct: measure-then-optimise.
3. **Sorting `Maybe<T>` / `Entity<T>`.** Probably a no-op shape — neither
   contains multiple items to reorder. Confirm we don't accidentally allow
   `Entity<T>.sortBy { … }` to compile.
4. **JS engine.** `kotlin.collections.sortedWith` is stable on JS post-ES2019;
   confirm the `:tenshu-sdk-js` target's Kotlin runtime relies on the
   stable path. (No existing chain step needs stability; this is the first.)
5. **`SchemaProvider.allRealmData()` / RealmData sorts.** Once
   [`core-realm-data.md`](core-realm-data.md) lands, the Control "Data" section
   wants to list realm-data types by some criterion. Does `sortBy` over a
   `TList<RealmData>` need anything beyond the primitive comparable markers?
   Probably not — but flag for the Realm Data implementer.
6. **Property selectors in sort position (cross-proposal).** Can the selector
   lambda emit a *property* reference (`it.lastModifiedAt`), not just a field
   reference, and have the engine resolve + compare it? Required by
   file-service-history §3.3's field→property move. Field selectors already
   work in `search`/`filter` predicates; confirm the same machinery emits
   property refs in a sort selector. Hard dependency for the HomeScreen
   consumer.
7. **`Comparable` trait — composite flavour shape (deferred).** When something
   sorts a list of composites *by the composite itself* (not a projected value),
   the trait needs a callstack to extract the key — `ChainTemplate` signature,
   `thenBy` composition, registration validation. Specify at first use case.
8. **Value-marker compile-time nicety (deferred).** Whether to add a thin Kotlin
   marker on the value types so a non-comparable selector fails at compile time
   rather than registration (§2.1 / §2.2). Cheap, but one more interface; decide
   when implementing.

---

## 7. Non-goals

- **Stable global ordering for cross-device synced lists** — out of scope here.
  Synced lists arrive HLC-ordered; this chain step orders the materialised
  result, not the wire representation.
- **Sorting by *eager value* properties is in scope** (`it.lastModifiedAt`):
  the value loads with the entity, comparison is cheap. **Sorting by
  composite-relation-derived values** (`it.parent.name`, walking a relation to
  another composite) *will* work mechanically, but the perf characteristics are
  owned by the relation system and the deserializer's eager-recursion cost
  ([`tech-debt.md`](../tech-debt.md) §"Database Deserializer …"), not by
  `sortBy`.
- **Server-side sort pushdown** — there is no server; the relay sees
  ciphertext. Sorting always happens on the client.
- **`distinct`, `groupBy`, `chunked`** — these are real chain steps we'll
  eventually want, but each has its own materialisation/sync cost story. Not
  this proposal.

---

## 7b. Defect (2026-08-13) — the property-handle sort read its key off the wrong shape

⚠⚠ **`sortBy(File.LastModifiedAt)` ordered nothing.** From the day it landed
until 2026-08-13, a **value-property** sort over `defaultFileSystem.root.files`
produced `null` for *every* row's key. Every comparison tied, so the order was
decided by whatever broke the ties — Drive's `.thenBy { name.lowercase() }`, else
encounter order — **identically in both directions**. Reported as "the
ascending/descending toggle does nothing for Date modified and Date created, but
it works for Name".

**Why.** `SortByImplementation.extractKey`'s property arm was
`(element as? Entity<*>)?.getProperty(id)`. An array reference is entity-typed
only when its *element schema* is itself an `Entity` subtype
(`ReferenceResolver.resolveServiceData`: `isEntity = itemTypeSchema.isSubtypeOf(Entity.ID)`),
and **`File : Composite`** — so `root.files` reads back plain composites,
`element as? Entity<*>` is `null`, and the key read never happened. §7's
"the value loads with the entity, comparison is cheap" assumed an entity row;
the ordinary case does not have one. The rows *are stored* as entities — only the
read shape differs — which is why `select { relations(File.LastModifiedAt) }` on
the very same chain returned the correct timestamps while the sort ignored them.

**Why it survived so long.**
- `SortByPropertyTest` (`:file-service-proxy`) drives the operation with a
  hand-built `TList` of `Entity.create { set(File.LastModifiedAt, …) }` rows —
  properties inline, entity shape, green.
- `FilesPagingIntegrationTest.thenBy_breaksPrimaryKeyTiesByName` sorts folders by
  `File.Size`, where every row **ties at 0** — indistinguishable from every row
  reading `null`.
- `SortToggleIntegrationTest` toggles direction end-to-end but only for **Name**,
  the one key that takes the lambda arm.
- The `thenBy` tie-break made the failure *plausible*: the listing came back
  alphabetical rather than obviously unordered.

**Fix.** The property arm now falls back to re-reading the row as an entity
(`getDataId` → `database.getData(dataId, asEntity = true)` → `getProperty`) —
the same re-load every other relation read in the engine goes through, and what
`Projection`'s contract already assumes. Tolerant by construction: a row with no
`DataId`, or one that never reached the store, yields `null` and sorts per
`compareSortKeys`'s missing-key rule instead of throwing mid-listing.

⚠ **The fused path could not be fixed the same way and is now gated instead.**
`tryOrderedWindowFusion` reads its key off the loaded row by design (that is the
point of `Projection.keepProperties`), and a composite row has no `properties`
map to widen *into* — so `classifySortKey` refuses a value-property key when
`!reference.isEntity` and falls through to the (now correct) in-memory sort.
Widening the projection was tried first and is **not** the fix: it addresses a
different, non-manifesting case.

⚠ **Cost.** The fallback is one `getDataId` + one point read per row per property
key — the same order of per-row work the Name (lambda) sort already pays. It is
paid only when the user opts into a sort, and it is doubled by `thenBy`'s
redundant intermediate `SortBy` op (§2.4). Fold the two ops if a profile ever
shows it.

**Pinned by** `FileSortDirectionTest` (`:file-service-proxy`, all four keys over
the real chain, both directions), `OrderedWindowFusionTest`'s three
`propertyKeyOverACompositeArray_*` cases, and
`FilesPagingIntegrationTest.sortByDateModified_reversesWithTheDirection`. Every
one of them asserts **both** directions — a single direction can coincide with
encounter order and pass while the toggle does nothing. All seven were confirmed
to fail against the pre-fix engine.

---

## 8. PR / commit map (placeholder)

Single PR, branched off `master` once the proposal is approved. Suggested
branch: `feat/core-chain-sort`. Commits ordered to match §5 phases so the
diff reads cleanly.
