# Typed operation returns — dissolving the RO-factory wiring gap

*(Formerly "Typed `Task<T>` operation returns". The `Task<T>` name is retired —
it collides with the since-shipped TS-3 task composite, and the settled shape
introduces no new type at all; see §8.)*

**Status:** v0.2 (2026-07-25) — **direction SETTLED (§8); no code, not yet
scheduled.** v0.1 (2026-07-02) was idea capture stubbed out of the post-mortem
on the streaming-video crash. The settled shape deviates from
the v0.1 sketch in one load-bearing way: **no new `Task<T>` schema type is
introduced — the existing sealed `Interactor` family is promoted to the
declared operation returns** (§8.2). §§2–7 are kept as v0.1 context; where they
conflict with §8, §8 wins.

**Owner:** Benedikt
**Motivating incident:** the streaming-video crash — a missing client RO factory for
`ContentStreamHandle` (fix), then `OpenContentStreamRo` erased to `Data` (idiomatic follow-up) — see §1;
trigger re-fired twice since, see §8.1.
**Depends on / extends:** [ADR 0012](../adr/0012-interactor-pattern.md) (the
Interactor pattern — the client-side task object this proposal promotes to the
declared return); the operation-/composite-schema typing work
([ADR 0009](../adr/0009-operation-schema-typing.md),
[ADR 0005](../adr/0005-composite-schema-typing.md)) whose parameterised-type +
`substitute(defaultForUnbound = Data)` machinery is the mechanism this leans on.
**Touches the threat model:** no — this is a client-internal type-shape
refactor; under the settled shape the schema and wire are untouched (§8.2), so
`TENSHU_STORAGE_FORMAT_VERSION` is untriggered (HR #9).

---

## 1. Motivation — the wiring gap this dissolves

An operation's client-side RO builds the request chain for its return value via
`createRequestObject(type = …)`. The `type` argument's **base** must have a
registered request-object factory in the `SchemaRegistry`, or the call throws at
build time:

```
java.lang.IllegalStateException: No request object factory registered for type: tenshu/sync/contentStreamHandle
```

That's exactly the streaming-video crash. `OpenContentStreamRo` was the **only**
terminal-op RO that passed a *bare concrete composite* as the base:

```kotlin
createRequestObject<ContentStreamHandle>(type = ContentStreamHandle.Schema.toType())
```

Every other terminal-op RO (47 of them, e.g. `GetSyncStatusRo`) erases the base
to `Data`:

```kotlin
createRequestObject<Data>(type = Data.Schema.toType())
```

and lets the DTO/composite factory materialise the concrete type from the wire
response at `execute()` time. The follow-up moved `openContentStream` onto that
convention, which is the current fix. But "remember to erase to `Data`" is an
unenforced convention — the next author who passes a concrete base reintroduces
the crash, and (see the post-mortem) it can't be caught by a registry/schema
walk, a startup self-check, or a cheap lint:

- A schema walk can't tell an erase-to-`Data` op (return composite, **no** RO
  factory needed) from a concrete-base op (needs one) — the distinction lives in
  the RO's *implementation*, not the schema. It false-positives on all 47.
- A startup check has strictly *less* info than the schema walk and can't see
  the RO's choice either (`ContentStreamHandle` already had a *DTO* factory
  pre-fix, so a "has some factory" check passes it while it's still broken).
- A source scan needs JVM-only reflection or brittle regex.

So today the guard is the convention itself. This proposal removes the need for
the convention by making the mistake **structurally unrepresentable**.

## 2. The shape — `Task<out T>` (and kind subtypes)

Today an operation method returns an opaque `RequestObject`; the result type
survives only as the Kotlin generic on `asTaskInteractor<T>()` / `execute()`.
The proposal: every operation returns a first-class **`Task<out T>`** schema
composite — with kind subtypes for the async flavours we already distinguish via
traits, e.g. `TerminalTask<T>`, `StreamTask<T>`, `AsyncTask<T>`.

```
returnType = StreamTask<ContentStreamHandle>   // instead of raw ContentStreamHandle + IoOperation/TerminalOperation traits
```

The RO then builds against the **task container** as the base:

```kotlin
createRequestObject(type = streamTask(ContentStreamHandle.Schema))  // base = StreamTask; result rides as a type param
```

## 3. Why the crash class dissolves

The base handed to `createRequestObject` is now *always* one of a small, fixed
set of task containers — all registered once in `:core` with **parameterised**
RO factories, exactly like `TList` / `Maybe` today. The result type `T` is a
type *parameter*, never the base. So "author picks an unregistered concrete
base" becomes unrepresentable — the same reason `listType(X)` ops (`getDevices`,
`getRealmMembers`, …) never hit this bug. You'd be making that already-safe
shape universal.

This is the **typed generalization of the current `Data`-erasure** (§5): instead
of erasing all the way to the top type `Data`, you erase to a covariant
container `Task<out T>` whose base is registered and whose `T` is carried
covariantly and *survives into the schema*.

**Bonus — it answers the "reflect the return type in the Schema" wish.** Today
the return type is erased at the RO layer and the task-kind lives in
`IoOperation` / `TerminalOperation` *traits*. `Task<T>` folds both back into a
single typed `returnType`, so the schema states both *what kind of async* and
*what result type* an operation produces.

## 4. Relationship to existing machinery (this is a promotion, not an invention)

- **The kind is already half-encoded.** Operations carry `IoOperation.Schema` /
  `TerminalOperation.Schema` traits — the "task kind" is already schema-visible.
  `Task<T>` consolidates trait + raw returnType into one typed return.
- **The task object already exists client-side.** `TaskInteractor` (ADR 0012) is
  the runtime task; this promotes it to a typed schema return rather than a
  wrapper synthesised from a generic.
- **The wire is unaffected.** The response already carries the concrete result
  type id (that's how erase-to-`Data` materialises today); `Task<T>` changes the
  *client/proxy request-object typing*, not the wire format.

## 5. The variance / `Data`-default connection

Erase-to-`Data` is safe **because** `Data` is the covariant top type: every
composite *is-a* `Data`, so a `Data`-typed request object can legitimately yield
a `ContentStreamHandle`, recovered dynamically at materialisation. The same
default is baked into the type machinery — `substitute(defaultForUnbound =
Data.Schema.toType())` and `elementSchema()`'s `?: Data.Schema` fallback: the
unknown collapses to the top type rather than failing. Were the system invariant
(exact types required, no `Data` default), you *couldn't* erase — every op would
be forced to pass its concrete type and the crash would be the default for
everyone, not a one-off deviation. `Task<out T>` keeps that covariant
subsumption but stops at a registered container instead of the bare top type.

## 6. What it does *not* solve (honest scope)

- **Only the crash class.** Traversing *into* `T` lazily (field access on the
  result to build a sub-chain) still needs `T`'s RO factory — the same benign,
  opt-in seam that `Member` / `PendingGrant` already live with under
  `listType(X)` (registered in `*-common` only; whole-value `execute()` works,
  typed lazy element chaining is opt-in). `Task<T>` covers the `execute()` path;
  it doesn't retroactively make every result type lazily chainable.
- **It's a real refactor, not a drive-by.** Open design questions before anyone
  starts: the exact set of task kinds and how they map to ADR 0012 interactors;
  how `Task<T>` is declared in the operation DSL; migration of ~all operation
  return sites; whether the kind-traits are dropped or kept as a compatibility
  shim during migration.

## 7. Open questions

1. Task-kind taxonomy — is `{Terminal, Stream, Async}` the right cut, or does it
   collapse to a single `Task<T>` + trait, keeping kinds orthogonal?
2. DSL surface — `operation(… returns = terminalTask(X.Schema))` vs inferring
   the wrapper from the existing traits at schema-build time.
3. Migration shape — big-bang vs a per-service sweep with the raw-returnType
   path kept working alongside during transition.
4. Does promoting the interactor to a schema type interact with the JS SDK
   walker / `@tenshu/sdk` typing in any load-bearing way?

## 8. Decision — direction settled (v0.2, 2026-07-25)

### 8.1 The trigger fired — twice

v0.1 deferred this "for when another instance of the RO-factory gap makes it
worth scheduling." Two more instances landed within three weeks of each other:

- **Workout.Streams (Fit read-scaling option B):** the on-demand dynamic-property work hit
  the same class from the registration side — a missing *client-module* Ro
  registration made every select projecting the property fail as an opaque
  `error/internal` (recorded in the
  [`fit-workout-streams-content-plane.md`](fit-workout-streams-content-plane.md)
  as-built notes).
- **TS-4 Fit import:** the import op Ros needed **generic `Data`
  request objects** + client `type(...)` registrations — the same
  erase-to-`Data` convention trap, on the request-argument side
  ([`core-task-composite.md`](core-task-composite.md) as-built).

The convention is demonstrably not holding, and the pre-release window (no
compat shims) makes the sweep as cheap as it will ever be.

### 8.2 Settled shape: promote the `Interactor` family — no new schema type

The v0.1 sketch (§2) introduced `Task<out T>` as a first-class schema
composite. Settled otherwise: **the typed return is the existing sealed
`Interactor` hierarchy** (`core/.../Interactor.kt`, ADR 0012 — which already
calls the interactor "the client-side task object"). Operations declare the
interactor they resolve to; nothing new is invented:

```kotlin
interface GetAll<E : Data> : Operation {
    fun getAll(): TaskInteractor<TList<E>>       // was: RequestObject

    companion object {
        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = listType("E"),           // UNCHANGED — declarative result
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema), // UNCHANGED — kind
        )
    }
}

// Client DSL extension — the caller-asserted generic disappears:
suspend fun <E : Data> TList<E>.getAll(): TResult<TList<E>> =
    findOperation<GetAll<E>>(GetAll.ID).getAll().execute()

// :core, built once — op methods stay non-suspend (HR #3); provider
// resolution moves inside execute(); RequestObject retreats to a private field
internal class DeferredTaskInteractor<T : Data?>(
    private val requestObject: RequestObject,
) : TaskInteractor<T> {
    override suspend fun execute(): TResult<T> =
        requestObject.asTaskInteractor<T>().execute()
}
```

**The realisation that killed the schema-composite variant:** the return
container never crosses the wire (the *chain* serialises; the container does
not), is never stored, and never impersonates a data type. Everything that
forced `RequestObject : Data` came from the *masquerade* role of lazy-chaining
ROs (a `SearchAllRo` handing your predicate an RO that impersonates `T`) —
a role the terminal return does not have. A type with no wire form, no fields
and no storage is not a Tenshu `Data`; `TaskHandle<out T : Data?>` is the
in-repo precedent for exactly this phantom-typed plain-Kotlin handle.

Consequences:

- **Zero new `Data` inheritors.** The handles live outside the data ontology.
- **Schema and wire untouched.** `returnType` keeps stating the result; the
  kind stays in the `IoOperation`/`TerminalOperation`/`StreamOperation`/
  `BackgroundOperation` traits. The *signature* now agrees with the schema
  instead of erasing it to `RequestObject`. Definitively no format bump.
- **The crash class dissolves as §3 promised**, without the schema change: the
  Ro constructs a deferred interactor directly — no per-type RO factory lookup
  on the terminal path, ever. Erase-to-`Data` materialisation stays internal
  at execute time (§5 machinery unchanged).
- **The caller-asserted generic on `asTaskInteractor<T>()` (~113 call sites)
  disappears** — `T` flows from the interface declaration instead of being
  asserted at the call site (a second, previously unnamed convention hole).
- **Wrong-interactor selection becomes a compile error** — today each
  `as*Interactor` extension is a runtime `as?` cast with an `error(...)`
  fallback.
- **`RequestObject` retires from the public surface** (end-state): relocated
  to the `:core-common` impl layer, `internal`, and — pending the §8.5 gate —
  drops `: Data` entirely, shrinking to the masquerade mixin
  (`TContext` + `requestChain`) it always really was.

### 8.3 Resolutions of the §7 open questions

1. **Taxonomy:** the existing sealed set as-is — `TaskInteractor` /
   `SyncInteractor` / `StreamInteractor` / `CancellableTaskInteractor`
   (the deprecated `BackgroundInteractor` is not migrated to). No new kinds.
   `StreamInteractor` keeps its callback surface verbatim in the sweep;
   Flow-ification is an explicitly separate follow-up, if ever.
2. **DSL surface:** none — the operation schema is untouched; the change is
   signature-level only. Signature↔trait drift is closed by a one-line assert
   at operation-schema registration ("`TaskInteractor` return ⇒ carries
   `IoOperation` + `TerminalOperation`"), failing loudly at startup rather
   than in a user flow.
3. **Migration shape:** per-service sweep with coexistence, not big-bang.
   Phase 1 = `:core` machinery (deferred-interactor impls + a typed builder
   replacing bare `createRequestObject` on the terminal path) + `:core`'s own
   collection ops; Phases 2..N = per-service sweeps (mechanical); final phase
   = retire `RequestObject` per §8.2. Scope finding: **no `*-proxy` module
   names `RequestObject`** — proxy-side dispatch is the callstack evaluator
   keyed by schema id, so the sweep touches service interfaces, client Ros and
   `:core`/`:core-common` machinery only.
4. **JS SDK:** schema untouched ⇒ no walker impact expected; verify in
   Phase 1 (`:tenshu-sdk-js` wraps the Kotlin op surfaces, and plain
   phantom-typed interfaces already cross that boundary — `TaskHandle`).

### 8.4 Alternatives rejected (2026-07-25 review)

- **Return the result type directly** (`getAll(): TList<E>`): recreates the
  crash class *as the default* — building an impersonating RO of the concrete
  return type at chain-build time needs that type's registered factory, which
  is exactly the `ContentStreamHandle` crash. `TList`-returning ops deceive because `TList` has a
  parameterised factory in `:core`; most terminal ops return plain composites
  (`ContentStreamHandle`, `SyncStatus`, `Entitlement`, …). It is the right
  shape for *chaining* ops only — where it already exists. It also leaves
  `execute()` homeless: either an extension polluting the data type ("every
  `TList` looks executable") or a cast back to `RequestObject`.
- **New `Request<T>`/`Task<T>` schema-composite family** (the v0.1 §2 shape):
  ~5 new top-of-lattice `Data` inheritors carrying no data; schema-DSL +
  walker + JS-export churn and a callstack wire-shape question — all for the
  §3 "bonus" (kind+result unified in `returnType`) alone. That bonus is
  severable and can land later as a purely additive schema refinement if a
  real consumer appears.
- **Reuse the TS-3 task composite** (`TaskSpec`/`BackgroundTask` from
  [`core-task-composite.md`](core-task-composite.md)): semantic freight —
  a TS-3 "task" is registry-tracked, leased, liveness-monitored,
  sync-lane-scoped durable background work; a list read is not that. Cold
  spec vs hot handle vs pending invocation are three different things;
  unifying them muddies both sides. (`TaskHandle` still appears as the result
  of `CancellableTaskInteractor.execute()` — unchanged.)
- **An `Operation` subtype:** an *invocation* is not an *operation* —
  operations are found via `findOperation` and dispatched by schema id; the
  return would become a peer of the op declaring it. Wrong machinery.

### 8.5 Phase-1 verification gate

The one assumption that lets the handles leave the `Data` lattice: **no code
path passes a terminal op's return onward as a `Data`-typed op argument**
(a cross-chain sub-request). A module scan found none — every terminal return
is executed immediately by its DSL extension — but confirm during Phase 1
before dropping `: Data` from `RequestObject`.
