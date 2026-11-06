# Error Channel — Typed `TError` Taxonomy + Proxy-Side Crashlog Capture

**Status:** All six phases shipped on master 2026-06-01 / 2026-06-02 —
`e0d155fe` (Phases 1+2: TenshuException + structural
dispatcher), `5105efed` (Phase 3: catch-all consolidation +
Failure-returners → throws), `39e64101` (Phase 4: Shape A
flip across 60+ impls; load-bearing `T : Data?` widening discovered
during impl), `30721218` (Phase 5: `:logging-service` v1 +
Settings → Errors visibility), `db501265` (Phase 6: upload
CTA + redaction at the seam + threat-model A19 / §8.11).

**Outstanding orthogonal tracks** (not blocked on this proposal):
**Phase 5b** — 500-event ring buffer + 30-day daily eviction sweep
(maintenance ops); **Phase 4b** — JS-side `TenshuException` class for
`@tenshu/sdk` callers; **App Console deployment** — the `/reports`
endpoint + the `appConsoleUrl` config that flips the CTA from disabled
→ enabled in production; **Phase 3b cleanup** — fold remaining freetext
`GenericError(message=…)` typed throws into typed-only domain subtypes
under `tenshu/sync/error/...` / `tenshu/file/error/...`.

The proposal text below is left as-shipped for the historical record.
Three coupled sub-tracks: (E) typed `TError` realm-namespaced taxonomy,
(O) `OperationImplementation` contract shape (the "direct-return vs
`TResult<T,E>`" question), and (L) `:logging-service` for proxy-side
crashlog capture with user-initiated upload to the App Console.

**Owner:** Benedikt
**Depends on:**
[ADR 0016](../adr/0016-localisation.md) anchor #4 (typed codes, not
free-text strings, cross the proxy boundary — load-bearing);
[ADR 0012](../adr/0012-interactor-pattern.md) (non-suspend `Operation`
interface);
[`docs/sync/threat-model.md`](../sync/threat-model.md) House Rule #1
(the relay sees ciphertext only — the App Console upload is the same
trust class).
**Touches the threat model:** new asset (proxy-side crashlog buffer +
opt-in upload payload). Amendment in §7.

The recommendation in §3 has a clear winner (E + the direct-return
shape for O) but §3.2 puts the two O variants side-by-side with the
migration math so the call is documented, not buried.

---

## 1. Motivation

### 1.1 The dual error channel today

`OperationImplementation` declares its impls return `TResult<T>` —
sealed `Success<T> | Failure(error: TError)`. In practice errors travel
through **two channels**:

- **Returned** as `TResult.Failure(error)`: 15 of the 61
  `*Implementation.kt` files under `commonMain` (~25%).
- **Thrown** as `Exception` / `IllegalStateException`: 35 of 61
  (~57%). The `params.require(KEY)` helper at
  `core-common/.../OperationImplementation.kt:19–22` is `kotlin.error(...)`
  internally — every "missing parameter" path is a throw. `kotlin.require` /
  `kotlin.check` / explicit `throw` add the rest.

The throws are caught at **two boundaries** (we have a duplicate
catch-all today — see §1.1.1). The outer one is `ProxyGateway.call`
(`core-common/.../ProxyGateway.kt:155–167`):

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e
    println("IPC Proxy -> ERROR: ${e.message}")
    e.printStackTrace()
    TResult.Failure.create(
        context = …,
        error = GenericError.create(context = …, message = e.message ?: "Unknown error")
    )
}
```

The result is structurally bad:

1. **Type erasure at the catch.** Whatever the impl threw — a missing
   parameter, a malformed parse, a not-found, a state-machine
   violation — gets flattened to a single `GenericError` carrying a
   free-text `message`. The client cannot distinguish them.
2. **Free-text on the wire.** `e.message` propagates verbatim. It
   crosses the proxy boundary, lands in `Result.Failure`, and gets
   rendered (today as `Text(state.error)`). This *directly violates*
   ADR 0016 anchor #4 ("Anything that crosses the proxy ↔ client
   boundary as a user-facing error or status MUST travel as a typed
   code, not a free-text `String`"). Phase 2c just landed the
   ViewModel-side cleanup; the proxy-side leak is still in place.
3. **`println` to stdout.** The catch logs `e.message` + a stack
   trace via `println` / `e.printStackTrace()` — both unstructured,
   and `e.message` may carry user-realm payload (a parse exception
   in a `Notification` body, a file path the user named, an entity
   id that pins them to a public realm). Today's `println` is the
   first thing anyone running Control desktop sees in the terminal,
   and on Android it lands in Logcat where it survives reboot in
   recent buffers.
4. **No structured collection.** A user reporting "Tenshu crashed
   when I tapped X" gives us no breadcrumbs. We have no
   diagnostic-capture flow at all today — they paste a screenshot,
   maybe a Logcat dump if they're a developer.

### 1.1.1 Two catch-alls (not one) and a half-built structural validator

Two things on the existing code worth pinning down before §2:

- **`LocalTaskExecutor` has its own `e.message` catch-all** that
  predates ProxyGateway's, at
  `core-common/.../LocalTaskExecutor.kt:27–37` and again at
  `:65–75`. Both branches do the same `GenericError.create(message =
  e.message ?: "Unknown error")` flatten. An exception that escapes
  an `OperationImplementation` body is caught here *first*, repackaged
  as a `TResult.Failure(GenericError(freetext))`, and returned
  normally; ProxyGateway's outer catch only fires for exceptions
  thrown by the parser, the serializer, or the registry — not by
  impls. The leak is genuinely two-tier.

- **Structural param validation already exists at the
  construction boundary.** `OperationDto.init`
  (`core-common/.../impl/OperationDto.kt:15–39`) runs whenever the
  parser deserialises an inbound Operation off the wire OR a
  client-side RO builds one, and asserts (i) every `params` key
  corresponds to a declared schema field id, and (ii) each value's
  runtime schema is subtype-compatible with the declared field
  type. It throws `kotlin.require`'s `IllegalArgumentException`
  with a freetext message — which is then caught by
  `LocalTaskExecutor` and flattened to `GenericError(message)`,
  losing all type info. Required-field *presence* is **explicitly
  not enforced** here; the in-tree comment names the reason
  ("operation ROs construct partial chain entries that gain the
  full params map at execution time").

The combined picture: we have a half-built structural validator that
catches unknown-param + wrong-type at construction time, but it
throws freetext into a catch-all that flattens it. And the *missing*-
param case isn't validated at all at the dispatch boundary — each
impl re-litigates it via `params.require(KEY)` (also freetext,
caught the same way). This shapes the design in §2.2 and §3.0.

### 1.2 The Tenshu Console connection

`docs/milestones.md` § "App Console & Tenshu Store" describes a future
closed-source web portal where developers *"publish, telemeter, and
analyse their apps"*. That portal is where structured error reports
should land — both for Tenshu's own platform apps (Drive, Control) and
for third-party Tenshu-enabled apps once the publication chain ships.

The Console is not built yet. But the **proxy-side capture seam** is
ours today: ProxyGateway already sees every error. If we put a
typed-error logging service alongside it now — gated behind
user-initiated upload, with no auto-send — the Console gets a
canonical wire protocol the day it ships, and users of today's apps
get a "Send error report" button as soon as their app version contains
the service.

PII filtering is the load-bearing concern. The whole point of Tenshu
is "the relay sees ciphertext only" (House Rule #1). The App Console
needs the same trust model: the operator (us, today; the third-party
developer in v1.1) sees **types and counts and metadata**, never
realm payload. The way to deliver this isn't a regex scrubber — it's
typed errors that physically can't carry payload.

### 1.3 Why now

Three forces:

- **Phase 2c just landed.** The four flagged control-shared
  ViewModels are now typed-error end-to-end on the *client side*. The
  proxy-side `GenericError(message=…)` leak is the next domino — and
  Phase 2c's pattern (typed sealed errors + screen-side
  `stringResource` mapping) is the consumer pattern this proposal
  feeds.
- **String count is small and bounded.** Today's `params.require`
  throws are well-known buckets: missing parameter, wrong type,
  parse failure, not-found, already-exists, state-machine
  violation. Codifying them as a fixed taxonomy is a finite,
  one-time exercise.
- **The Console schema calcifies later.** Whatever wire shape we
  ship for crashlog upload becomes a backwards-compatibility
  contract with future Console versions. Designing the wire today
  while there is one consumer (ourselves) is structurally cheaper
  than designing it when there are many.

---

## 2. The typed `TError` taxonomy (sub-track E)

### 2.1 Existing shape

`core/Error.kt` declares `TError : Composite` with realm ID
`tenshu/core/error`. Concrete subtypes today:

- `GenericError` — id `tenshu/result/error/generic`, single
  mandatory `message: TString`.
- `NotFoundError` — `tenshu/core/error/not-found`, message field.
- `InvalidCallerError` — `tenshu/core/error/invalid-caller`,
  message field.
- `IfEmptyError` — `tenshu/core/error/if-empty`.
- `AlreadyExistsError` (file service) —
  `tenshu/file/error/already-exists`.

`messageOrNull()` (also in `core/Error.kt`) is the best-effort
`"${id}/message"` field reader the IPC failure log uses. It exists
because every concrete `TError` today carries a freetext message —
i.e. the *typed* layer the ID gives us is wasted by the fact that
the rendering side just reads the message.

### 2.2 Proposed namespacing

The fix is not "add more concrete types" — it's "split the namespace
so each kind has a stable, machine-readable id, and stop carrying
freetext for user-facing errors." Crucially we also split errors
**by who raises them** — the dispatcher vs the operation impl — so
the type story in §3 cleanly carves off the structural cases.

```
tenshu/core/error                          (root, today)

tenshu/core/error/dispatch/...             NEW family — DISPATCHER-RAISED (structural).
                                            See §3.0. None of these ever come from an op
                                            impl body; they are raised by the parser /
                                            EvaluateCallstack before any user code runs.
    /missing-parameter                     (carries: parameter name)
    /unknown-parameter                     (carries: parameter name, operation id)
    /wrong-parameter                       (carries: parameter name, actual type id, expected type id)
    /unknown-operation                     (carries: operation id)
    /invalid-caller                        (refactor of InvalidCallerError — wrong receiver type)

tenshu/core/error/parse/...                NEW family — PARSER-RAISED (wire/data).
    /malformed                             (carries: schema id)
    /unknown-type                          (carries: type id)

tenshu/core/error/operation/...            NEW family — IMPL-RAISED (semantic invariants).
                                            Per-op `Errors` companion lists which apply.
    /invalid-parameter                     (carries: parameter name, reason code — itself
                                            a typed enum: `negative`, `empty`, `out-of-range`,
                                            `path-traversal`, `bad-format`)
    (feature-service-specific impls extend this family or their own subtree.)

tenshu/core/error/state/...                NEW family — STATE-MACHINE / INVARIANT.
                                            Impl- or service-raised.
    /already-exists                        (refactor of AlreadyExistsError to family)
    /not-found                             (refactor of NotFoundError to family)
    /not-allowed                           (action vs current state)

tenshu/core/error/internal                 NEW — last-resort wrapper for an unexpected exception
                                            (carries dev-only message; never user-facing).
tenshu/core/error/cancelled                NEW — propagated CancellationException.
```

The `dispatch/` vs `operation/` split is load-bearing for §3.0 + §3:
once dispatcher-raised errors live in their own family, they don't
enter any per-op signature — Shape A and Shape B both stop needing
to thread `MissingParameter` through every operation type.

Feature services add their own subtrees under their service id, e.g.
`tenshu/file/error/...`, `tenshu/sync/error/...`. The naming rule
echoes Service ID conventions (`tenshu/core/service`,
`tenshu/notification/service`) — already lived-in. Within a segment,
kebab-case (`not-found`, `missing-parameter`) follows the in-tree
convention from `tenshu/core/error/not-found` etc.

### 2.3 No freetext on user-facing errors

Concrete principle:

- **Typed parameter fields are fine.** `MissingParameter(name:
  TString)` is OK — the *name* is a non-PII identifier the
  rendering side may want to interpolate (`"Required: $name"`). It
  is not a freetext message the developer happened to write at the
  throw site.
- **Freetext message fields go away** for every error a user can
  see. The whole point of the taxonomy is that the *id* tells you
  what happened.
- **One exception:** `tenshu/core/error/internal` keeps a
  developer-only `message: TString` for the
  catch-all-genuinely-unexpected case. It is NEVER rendered to the
  user; the screen surface for it is "Something went wrong" + an
  optional "Send error report" link that ships the typed
  payload to the proxy-side logging service (sub-track L, §4).
  Developer rendering (in-app log viewer, Console) is the only
  reader of the message field.

This is the recursive form of ADR 0016 anchor #4: the proxy obeys
the same contract the ViewModels just did in Phase 2c.

---

## 3. `OperationImplementation` contract shape (sub-track O)

This is the architectural question. Two clean shapes both
compose with the typed taxonomy in §2; the migration cost and
day-2 ergonomics differ. But before the comparison, §3.0 carves
off a large class of "errors" that aren't really part of the
per-op contract at all.

### 3.0 Structural vs semantic parameter errors

The §1.1.1 finding plus the dispatcher-raised family in §2.2
collapse a chunk of the contract question. Today the platform
mixes two kinds of "bad parameter" errors that should live in
different places:

| Class            | What it asserts                                                                | Where it belongs                                                                                                                              | Today                                                                                              | Tomorrow                                                                                                          |
|------------------|--------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| **Structural**   | param key matches schema; value is the declared type; required params present  | **Dispatch layer.** Schema-driven, op-agnostic. ONE check.                                                                                    | Half-done at `OperationDto.init` (unknown/wrong-type); missing is each impl's `params.require(KEY)`. Both throw freetext. | `OperationDto.init` keeps its checks but throws `TenshuException(dispatch/{unknown,wrong}-parameter)` instead of `kotlin.require`. `EvaluateCallstack` gains a missing-param check just before `executionContext.execute(...)` (§EvaluateCallstack.kt:177) using `schema.params` + `isOptional`. Impl bodies stop carrying these errors. |
| **Semantic**     | value-level invariants the schema can't encode (positive, non-empty, no `..`) | **Impl body, per-op.** Domain logic.                                                                                                          | Inconsistent. Some `kotlin.require(pageSize > 0)`; many no check.                                  | `throw TenshuException(InvalidParameter(name, reason))` where `reason` is itself a typed enum (`negative`, `empty`, `out-of-range`, `path-traversal`, `bad-format`). The per-op `Errors` companion lists which reasons it raises. |

Two consequences:

1. **`params.require(KEY)` goes away.** Once the dispatcher
   enforces presence + type, impl bodies use `params.getValue(KEY)
   as T` (or a tiny `params.read<T>(KEY)` shim that does the cast).
   No throw site in impl bodies for the structural cases.
2. **`MissingParameter` / `WrongParameter` / `UnknownParameter`
   are not per-op errors.** They're structural — the dispatcher
   raises them, the wire ships them, the client renders them
   from a global "request was malformed" catalogue. They never
   need to appear in the type parameter of `TResult<T, E>` or in
   an `@Throws` companion. Eliminates the most ubiquitous
   "but every op can throw it" objection to typing the error
   side.

This subsection is independent of the §3.1 / §3.2 / §3.3 choice
between Shape A and Shape B — it stands either way. But it makes
the comparison cleaner: the rows in §3.2 below now apply only to
the *semantic* + business errors per op, not the universal cases.

### 3.1 The two shapes

**Shape A — direct return + typed throws.** Impls return `T`;
errors throw a single new `TenshuException(error: TError)` carrying
a typed code. The platform's `params.require` helper throws
`TenshuException(MissingParameter(name=key))` instead of
`kotlin.error(...)`. `ProxyGateway.call`'s existing catch becomes
typed — unwrap `TenshuException` to `TResult.Failure(error)`, and
wrap any *other* `Exception` as `TResult.Failure(InternalError(e.message))`.

```kotlin
// Today
interface SyncOperationImplementation : OperationImplementation {
    fun <T : Data> execute(…): TResult<T>
}

// Proposed (Shape A)
interface SyncOperationImplementation : OperationImplementation {
    fun <T : Data> execute(…): T   // throws TenshuException
}
```

Caller side (ViewModel, repository, peer op) catches
`TenshuException` at the boundary where it has actionable typed
errors. Cross-process callers go through `ProxyGateway` and read
`TResult.Failure(error)` off the wire — Result lives on as the
*wire shape* of an error response, but is no longer the
*operation-contract* return type.

**Shape B — `TResult<T, E>` with the error type parameterised.**
Keep returning Result everywhere; add a second type parameter for
the error.

```kotlin
// Proposed (Shape B)
interface SyncOperationImplementation : OperationImplementation {
    fun <T : Data, E : TError> execute(…): TResult<T, E>
}

sealed class TResult<out T, out E : TError> {
    data class Success<T>(val value: T) : TResult<T, Nothing>()
    data class Failure<E : TError>(val error: E) : TResult<Nothing, E>()
}
```

Per-op signatures express which errors are possible
(`TResult<Realm, CreateRealmError>` where `CreateRealmError` is a
sealed union of the concrete codes that op may emit). Compile-time
exhaustive-when at the call site.

### 3.2 Side-by-side comparison

The comparison below applies to **semantic + business errors per
op** — the `tenshu/core/error/operation/...` and per-feature
families. Structural errors (`MissingParameter`, `WrongParameter`,
`UnknownParameter`) are handled by §3.0 in both shapes; they don't
factor in. That removes most of the 35 throw sites from the
migration math: only the *semantic* ones (~10 of the 35 — sample:
`SyncRepository`'s pairing state checks, `FileService`'s
path-validation `kotlin.require`s) survive as per-op throws.

| Dimension                                                   | Shape A (direct-return + typed throws)                                                                                                                                                                                                       | Shape B (`TResult<T, E>`)                                                                                                                                                                       |
|-------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Single error channel                                        | Yes — throws are it. ProxyGateway funnels both `TenshuException` and unexpected `Exception` to the same `TResult.Failure` wire shape.                                                                                                       | Yes on the contract level. In practice the surviving ~10 semantic throw sites still exist; they must be rewritten as early returns.                                                              |
| Caller ergonomics (happy path)                              | `val phrase = bip39.generate()` — reads naturally.                                                                                                                                                                                          | `val phrase = bip39.generate().getOrThrow()` or pattern-match every time. The today-style `.getOrNull()?.get() == true` ladder stays unless the caller pattern-matches.                          |
| Type-level visibility of possible errors                    | Lost — JVM checked-exception semantics aren't a thing in Kotlin. Per-op `@Throws(TenshuException::class)` is convention only.                                                                                                                | Preserved — the type parameter lists them. (Note: with §3.0, the type only lists *semantic* errors — `MissingParameter` etc. are not in it.)                                                     |
| Signature noise                                             | Lowest. `fun createRealm(...): Realm`.                                                                                                                                                                                                       | Highest. `fun createRealm(...): TResult<Realm, CreateRealmError>` everywhere, plus the sealed `CreateRealmError` union per op family.                                                            |
| Migration cost — the ~10 semantic throw sites               | Zero (they already throw — we just change what they throw and the catch type).                                                                                                                                                              | Each throw becomes an early return: ~3 LOC per site × ~10 sites ≈ 30 LOC mechanical change.                                                                                                      |
| Migration cost — the 15 Failure-returners                   | They get `throw TenshuException(error)` instead. Each is a 3 → 1 line collapse.                                                                                                                                                              | Just retype the signature; bodies often unchanged. Cheapest of the two on this slice.                                                                                                            |
| Migration cost — call sites                                 | Higher — every cross-process call that bridged Result→VM now needs a try/catch (or a `.runCatching { ... }.toResult()` helper). The 4 VMs Phase 2c touched + the ~30 repository call-sites.                                                  | Lower — same Result API the call sites already use; only the type parameter changes.                                                                                                             |
| Coroutine interop                                           | Cleanest — standard Kotlin idiom; exceptions cancel scopes correctly. `CancellationException` stays a real exception.                                                                                                                       | Awkward — Result is not a Throwable; cancellation is still a separate exception path. We end up with three things on the table: `Result.Success`, `Result.Failure`, and `CancellationException`. |
| `ProxyGateway` complexity                                   | Simpler. One catch:  unwrap `TenshuException → typed error`; wrap other `Exception → InternalError`.                                                                                                                                        | Same as today — catch is still last-resort for unexpected throws (since impls might still throw despite the Result return). The dual-channel problem is reduced but not eliminated.              |
| Plays well with `:logging-service` (sub-track L)            | Yes — every typed throw has a single capture point in ProxyGateway.                                                                                                                                                                          | Yes — but throws and Result-Failures need two capture call-sites in ProxyGateway, slightly more code.                                                                                            |
| Backwards-compat for `:tenshu-sdk-js` callers (`Promise<T>`)| Native — Promise reject = exception; Promise resolve = T. `@JsExport` impls would map cleanly.                                                                                                                                              | Result must be re-shaped to either `Promise<T>` (reject on Failure) or `Promise<{ok: T} | {error: E}>`. The current SDK bridges Result via the former — already an "exception" shape on JS.       |
| Risk — caller forgets to handle                             | Real — an uncaught `TenshuException` crashes the request (which ProxyGateway absorbs cross-process, but in-process callers would bubble up). Mitigation: every public client surface explicitly catches at its boundary.                       | Lower — Result is a value; the type system at least nudges the caller to look at it. But "nudges" only — `.getOrNull()` already discards it today, and would keep doing so.                       |

### 3.3 Recommendation

**Shape A.** Direct-return + typed throws. The justification (tightened
by §3.0):

- ProxyGateway is *already* the catch-all and *already* unwraps to
  Result on the wire. Shape A formalises what's de facto true; Shape
  B fights it.
- §3.0 takes Shape B's strongest argument away. Its "type-level
  visibility of which errors are possible" pitch only carried
  weight when *every op could throw `MissingParameter`* — turning
  that into universal type-parameter noise. Once `MissingParameter`
  leaves the per-op type story, Shape B's signatures only list
  the (often empty, or one-entry) semantic-error set — a much
  smaller payoff for the per-op union plumbing.
- Caller-side ergonomics dominate day-2 cost. The today-style
  `.getOrNull()?.get() == true` ladder shows that the Result envelope
  isn't earning its keep in the ~50% of call sites where Failure is
  treated as "false" anyway.
- The migration math favours Shape A on the ~10 surviving semantic throw sites (zero
  rewrites at the throw side; signature change only). Shape B
  favours the 15 Failure-returners (no body rewrite) — but those 15
  are concentrated in a few packages and disproportionately have
  domain errors worth explicit handling, so the body rewrite is
  arguably valuable rather than overhead.
- Shape A composes with `:tenshu-sdk-js`'s existing
  `Promise<T>`-shaped bridge with zero translation.
- Shape A reads naturally. `fun unlockLocal(passphrase: String):
  Boolean` reads what it does. `fun unlockLocal(passphrase: String):
  TResult<TBoolean, UnlockError>` reads like infrastructure.

The single real cost is "type-level visibility of which errors a
given op throws". The mitigation is convention + documentation:
each `Operation` defines a `Errors` companion listing its
`TError` subtypes (matches the existing `Params` / `Relations`
pattern). The Detekt rule we ship for the i18n lint deferral
(CLAUDE.md i18n row's "Outstanding") can be extended to verify the
list matches the throw sites — but that's a polish item, not v1.

### 3.4 What about `CancellationException`?

Stays a real Kotlin exception, propagated through the
`if (e is CancellationException) throw e` short-circuit that
ProxyGateway already does. It is *not* a `TenshuException`. The
typed wire code for "user cancelled" (`tenshu/core/error/cancelled`)
is emitted on the **wire path** when a cross-process call is
cancelled — never thrown locally.

### 3.5 Where this lives

- `TenshuException(val error: TError)` in `:core` next to `Error.kt`.
- **`params.require(KEY)` goes away** (§3.0). It's replaced by the
  structural dispatcher check (see below) and a tiny
  `Map<String, Data>.read<T>(key)` shim — `getValue(key) as T` —
  for typed access inside impl bodies. The shim does NOT throw a
  typed error; if it ever fails, the dispatcher missed a case and
  the resulting `ClassCastException` is correctly caught as
  `InternalError` (programmer-error path).
- **Structural dispatcher check.** `OperationDto.init`
  (`core-common/.../impl/OperationDto.kt:15–39`) keeps its
  unknown-param + wrong-type checks but switches the
  `kotlin.require(...)` throws to
  `throw TenshuException(UnknownParameter.create(...))` /
  `WrongParameter.create(...)`. `EvaluateCallstack` gains a
  missing-param check just before `executionContext.execute(...)`
  (today at `EvaluateCallstack.kt:177`), iterating
  `protocol.schema.params` and raising
  `TenshuException(MissingParameter.create(name = field.id))` for
  any required field absent from `protocol.params`.
- **Duplicate catch-all collapse.** `LocalTaskExecutor.kt:27–37`
  + `:65–75` and `ProxyGateway.kt:155–167` both wrap exceptions
  into `GenericError(message = e.message)`. After this proposal
  they unwrap `TenshuException → typed error` and wrap other
  `Exception → InternalError`. The `println` /
  `printStackTrace` calls move to `:logging-service` (sub-track L).
  Open question whether one of the two layers becomes a pure
  passthrough — see §8.
- New concrete `TError` subtypes (`MissingParameter`,
  `WrongParameter`, `UnknownParameter`, `Parse`,
  `InvalidParameter`, `Internal`, …) in `:core`, each with the
  same shape as today's `NotFoundError` / `InvalidCallerError`
  but carrying *only* typed parameter fields, no message — the
  `Internal` case excepted (§5).
- `ProxyGateway.call`'s catch rewrites to unwrap `TenshuException`
  to its typed error directly (no GenericError wrap) and wraps
  every other `Exception` as `InternalError(message = e.message)`.

### 3.6 As-built (2026-08-13): a catch-all is not enough — the request scope must supervise

⚠⚠ Shape A's typed throw travels through **structured concurrency** before it
reaches the catch-all, and that second edge is not something a `try/catch` can
close. `ExecutionContext` runs an `AsyncOperationImplementation` as
`evaluationScope.async { … }`; an ordinary `async` reports its failure **upward
to its parent job** as well as exposing it through `await()`. So when the
evaluation scope is a plain `coroutineScope { }`, an impl throwing a typed error
— the *designed* failure path of every impl in the platform — does both of these:

1. `LocalTaskExecutor.executeAsync` catches it at the `await()` and returns the
   correct `TResult.Failure`, and
2. the scope fails anyway as the block completes, rethrowing the same exception
   past the caller.

Cross-process this was invisible, because `ProxyGateway.call` builds its request
scope on a `SupervisorJob(parentJob)` and there is a wire boundary to absorb (2).
**In-process** — iOS route 1, the JS SDK, the test harness — there is neither:
the throw unwinds the calling coroutine and lands on the uncaught-exception
handler, which on Kotlin/Native is `abort()`.

The fix is one word per overload: `LocalTaskExecutor.executeAsyncCancellable`
opens a **`supervisorScope`**, matching what its cross-process twin always had.
Cancellation still flows downward, so `TaskHandle.cancel()` unwinds an op exactly
as before; only the upward failure edge — already owned by the catch-all — is cut.

⚠ The trigger is `withTask`, which is what routes an op to
`executeAsyncCancellable` rather than `executeAsync` (`DefaultInteractorProvider`,
TS-4). Found the hard way: a routine `AlreadyExists` from Drive's photo-backup
`ensureBackupDestination` — inside `withBulkUploadTask`, hence tagged — crashed
Drive-on-iOS on every *Back up now* after the first successful drain (iOS crash
report 2026-08-13). ⚠ A regression test that only asserts the returned `TResult`
passes with the bug present; `LocalTaskExecutorTest` has to assert that the call
**returns at all**.

---

## 4. `:logging-service` — proxy-side crashlog capture (sub-track L)

### 4.1 Shape

New module set: `:logging-service` + `-common` / `-client` / `-proxy`
/ `-test-utils`, symmetric with `:preference-service` /
`:notification-service` / `:identity-service`.

Service id: **`tenshu/logging/service`**.

Surface:

```kotlin
interface LoggingService : Service {
    /** Realm-scoped log of captured error events, newest-first. */
    val events: TList<ErrorEvent>

    companion object {
        const val ID = "tenshu/logging/service"
        // …
    }
}

interface ErrorEvent : Syncable {
    val errorTypeId: TString          // e.g. "tenshu/core/error/operation/missing-parameter"
    val operationId: TString?         // best-effort: the op id at the top of the callstack
    val callstackDepth: TInteger      // depth only — not the ids
    val capturedAt: TInstant          // proxy clock
    val deviceId: TString             // own device, for cross-device deduping
    val appId: TString                // which Tenshu-enabled app surface (Drive, Control, …)
    val appVersion: TString
    val osLabel: TString              // "Android 14" / "macOS 14" / "Linux/x86_64" — no model strings
    val internalDetail: TString?      // ONLY for tenshu/core/error/internal — proxy-only readable
}
```

Note what is **not** there: stack traces, exception messages
verbatim, operation parameters, file paths, entity ids, realm ids,
the `messageOrNull()` body of typed errors. The taxonomy in §2
already removed the messages from typed errors; the only freetext
field on `ErrorEvent` is `internalDetail`, scoped to the
`tenshu/core/error/internal` code only.

### 4.2 Capture seam

`ProxyGateway.call`'s catch is the **single capture point**:

```kotlin
} catch (e: Exception) {
    if (e is CancellationException) throw e
    val typedError = when (e) {
        is TenshuException -> e.error
        else -> InternalError.create(context = …, message = e.message ?: "")
    }
    loggingService?.record(typedError, callstack, executionContext)
    TResult.Failure.create(context = …, error = typedError)
}
```

`loggingService?.record(...)` is null-tolerant — the logging service
is opt-in at the module-DI level. Production wiring (both `:control`
desktop+android `PlatformProxyModule`s) includes it; tests can omit
it without a fixture.

The recorder also receives the *callstack* — the parsed
`TList<Data>` from the BinaryParser. It extracts only the top-level
operation id (e.g. `tenshu/sync/operation/createrealm`) and depth.
The downstream `select { ... }` projections and entity ids in the
callstack are *not* read.

### 4.3 User-initiated upload

A new screen — Settings → Diagnostics → "Send error report" — shows
the `events` list (typed-error label per row + capturedAt) and a
"Send to Tenshu" button. The upload is:

- **Always user-initiated.** No auto-send, no background upload, no
  "anonymous statistics" toggle that defaults on. The Console
  receives a payload only when the user pressed the button.
- **Anonymous.** The upload carries the rows themselves (already
  PII-free by construction per §4.1) plus an opaque `uploadId`
  (server-side dedup) — no device id, no realm id, no user
  identifier. The proxy strips `deviceId` from the event payload
  at upload time (it's useful for local cross-device dedup but
  serves no purpose Console-side and is identifying).
- **Encrypted in transit.** Same TLS posture as the sync relay.
  The App Console may run as a separate service or piggyback on
  the relay's deployment; that's a deployment-time decision, not
  a protocol one.

The Console wire shape (a `POST /reports` endpoint with a JSON
body of `ErrorEvent[]`) lives in
`docs/proposals/app-console-error-reports.md` as a separate
follow-up — gated on the App Console milestone landing
infrastructure-side. For Phase 1 of this proposal, the upload UI
is a hidden Settings → Diagnostics screen with a stub upload
endpoint.

### 4.4 Retention + ring buffer

`events` is realm-scoped and synced like any `Syncable`. It must
not grow without bound. Two complementary bounds:

- **Local ring buffer.** The recorder keeps the last 500 events; on
  insert it tombstones the oldest if the count would exceed the
  cap. The number is generous enough to capture a debugging
  session but small enough that an exception loop can't blow the
  realm.
- **Time-based eviction.** Events older than 30 days are
  tombstoned by a daily sweep. The user-facing rationale: "we
  only keep recent diagnostic events". The platform rationale:
  bounded growth, and the only legitimate reason to read a
  3-month-old error is "I'm writing a postmortem" — not the
  steady-state.

Both bounds are realm-local. Synced to all the user's devices for
correlation ("oh, my desktop saw the same parsing error my phone
did"), but never persisted beyond the cap.

---

## 5. PII-by-construction

The structural rule, in one sentence:

> The only freetext the `ErrorEvent` carries is the
> `internalDetail` for `tenshu/core/error/internal`, and that
> field is **redacted** before upload.

What "redacted" means: the proxy keeps the `internalDetail`
locally (it's useful for a developer running Control desktop with
DevTools open), but the upload pipeline drops the field — only the
typed-error fields ship. The user sees the Diagnostics screen with
the full local detail; the Console sees the typed family + a
"`InternalError` (detail redacted)" marker.

Alternatives considered and rejected:

- **Regex-based scrubbing.** Looking for emails, paths, names in
  the message string. Fundamentally brittle (what about
  internationalised paths? regex-evading typos? base64-encoded
  blobs?). The whole *point* of having a typed taxonomy is that we
  don't need to scrub — by the time a `MissingParameter(name="foo")`
  reaches the wire, "foo" is a developer-defined identifier, not
  user content.
- **`internalDetail` upload-allowed by user opt-in.** Tempting,
  but the consent surface is wrong — the user is reading a "send
  error report" button, not "send error report including a
  potentially identifying message I have not personally
  reviewed". The local UI can render the detail (so the user
  sees what they would have sent had we let them); the upload
  body cannot. If a developer needs the detail, they get it from
  a screenshot in the user's bug report — out-of-band, on the
  user's terms.

---

## 6. Migration plan

This is *three* coupled tracks. We can land them in order without
big-bang.

**Phase 1 — Typed taxonomy (sub-track E)** [SMALL]

- Add `TenshuException(val error: TError)` to `:core`.
- Add the new concrete `TError` subtypes
  (`MissingParameter`, `WrongParameter`, `UnknownParameter`,
  `Parse`, `InvalidParameter`, `Internal`, `Cancelled`) to
  `:core`. Refactor `NotFoundError` / `InvalidCallerError` /
  `AlreadyExistsError` ids to the new family roots; keep the
  type names (no source-incompatible change).
- No call-site changes yet — the new types just *exist*.

**Phase 2 — Structural validation lift (§3.0)** [SMALL,
independent, can ship before Phase 3]

- Retype `OperationDto.init`'s two `kotlin.require {…}` checks to
  `throw TenshuException(UnknownParameter.create(...))` /
  `WrongParameter.create(...)`. No behaviour change beyond the
  exception type — the catch-alls still wrap, but to typed
  `InternalError` instead of freetext `GenericError` (already
  better; full clean-up in Phase 3).
- Add the **missing-param dispatcher check** to
  `EvaluateCallstack` just before `executionContext.execute(...)`
  (today at `EvaluateCallstack.kt:177`): iterate
  `protocol.schema.params`, raise
  `TenshuException(MissingParameter.create(name = field.id))` for
  any required field absent from `protocol.params`. Bypassable
  for chain-DSL partial entries via the existing `isTerminal()`
  branch above (line 99) — partial entries reach this point only
  in their terminal form.
- **Drop `params.require(KEY)` from impl bodies.** Mechanical
  change across the 35 throw sites: `params.require(KEY)` becomes
  `params.read<T>(KEY)` (a tiny extension wrapping
  `getValue(KEY) as T`). The impls that *only* threw on missing
  params no longer throw at all — they trust the dispatcher
  did its job, and any cast failure falls through to
  `InternalError` (programmer error, not user error).
- This phase is the §3.0 lift in isolation. It ships value
  without touching the Shape A / B question: the dispatcher
  error path is now typed, and `MissingParameter` /
  `UnknownParameter` / `WrongParameter` never enter
  per-op signatures from Phase 3 onward.

**Phase 3 — Shape A flip in the catch-alls** [MEDIUM]

- Switch `LocalTaskExecutor.kt:27–37` + `:65–75` AND
  `ProxyGateway.kt:155–167` to unwrap `TenshuException → typed
  error` and wrap other `Exception → InternalError`. Decide
  whether the two layers consolidate to one (see §8).
- Remove the `println` / `printStackTrace` lines. Logging moves
  to the new service (Phase 5).
- Migrate the 15 Failure-returning impls to throws.
- Migrate the ~10 *semantic* throw sites that remain after
  Phase 2 (e.g. `kotlin.require(pageSize > 0)`) to
  `throw TenshuException(InvalidParameter.create(name,
  reason))`. Per-op `Errors` companion lists the reasons each op
  raises.
- **Cross-process call-site updates.** Repositories that today
  do `result.getOrNull()?.get() == true` keep working
  (Result-on-the-wire) but should opt into the typed-error
  path — that's the input the next i18n PR consumes.
- ViewModels add a `try/catch (e: TenshuException)` at the call
  boundary (or a small `runCatching` adapter on the
  repository).

**Phase 4 — Switch `OperationImplementation.execute`'s return
type to `T`** [LARGE]

- Change `Pure/Sync/AsyncOperationImplementation.execute`'s
  signature to return `T` (throwing) instead of `TResult<T>`.
- The wire shape on the cross-process boundary is
  unchanged — Result still ships on the wire; the change is
  in the *contract* between impls and the local task
  executor. Mechanical signature update across the 61 impls.
- Update `LocalTaskExecutor` to catch `TenshuException` and
  produce `TResult.Failure(error)` for the wire serializer.

**Phase 5 — `:logging-service` v1** [MEDIUM]

- New module set per §4.1.
- Wire the recorder into `ProxyGateway.call`'s catch (or the
  consolidated single catch-all from Phase 3).
- Settings → Diagnostics → "Error events" screen in
  `:control-shared` showing the list, with no upload button yet.
- Realm-scoped sync (rides the existing sync channel — no relay
  changes).

**Phase 6 — User-initiated upload** [GATED ON APP CONSOLE]

- Add the "Send error report" CTA + the stub
  `POST /reports` endpoint.
- Real Console-side ingestion when the App Console milestone
  lands.

Phases 1–5 are independent of the App Console milestone. Phase 6
is the only one that touches it. Phase 2 (the structural lift) is
the *only* phase that ships on its own without behaviour-change
elsewhere — useful as a first PR to validate the taxonomy with the
smallest blast radius.

---

## 7. Threat model amendment sketch

`docs/sync/threat-model.md` gains:

- **Asset A18 — proxy-side diagnostic event log.** Realm-scoped
  `LoggingService.events`. Carries typed error codes + operation
  ids + non-PII platform metadata. Subject to the same House Rule
  #1 trust model as any other realm data: the sync relay sees
  ciphertext only.
- **Asset A19 — Console upload payload.** The bytes
  `POST /reports` ships to the App Console. Anonymous; carries
  no `deviceId`, `realmId`, `userId`. Redacts `internalDetail`.
- **Threat T-L-01 (Console operator infers user identity from
  payload correlation).** Mitigation: zero identifying fields on
  the upload payload; user-initiated only; one-shot (no
  long-lived id). Residual risk: if multiple uploads correlate
  in time / app version / OS, the operator can group them — but
  cannot link to a user. Accepted.
- **Threat T-L-02 (regex-scrubbing bypass).** N/A — there is no
  scrubbing; PII safety is by-construction (§5).
- **Threat T-L-03 (uncaught exception leaks user payload via
  `e.message`).** Mitigation: `InternalError.message` is
  proxy-local and **never** shipped on the upload path. The
  field exists on the wire (between proxy and the local
  Diagnostics UI) but is upload-stripped at the seam.

---

## 8. Open questions

- **Consolidate `LocalTaskExecutor` + `ProxyGateway` catches?**
  §1.1.1 shows two catch-alls today. After Phase 3 they do the
  same thing (typed-error unwrap + InternalError wrap). Plausible
  designs:
  - **A — LocalTaskExecutor is the only catch.** ProxyGateway
    becomes pure transport; no try/catch. Cleaner separation
    (executor decides Result shape; gateway just ships bytes).
  - **B — ProxyGateway is the only catch.** LocalTaskExecutor
    rethrows. Argues that the catch belongs at the wire boundary
    where the error becomes a wire-level Result. But
    LocalTaskExecutor is also used by in-process consumers (no
    wire crossing) — they'd need their own catch.
  - **C — Keep both.** They guard different things:
    LocalTaskExecutor guards `evaluateCallstack` (the per-step
    failure during the chain); ProxyGateway guards the
    surrounding parse/serialize/registry plumbing. Less elegant
    but defensible.

  My lean is A — LocalTaskExecutor is the right layer; ProxyGateway
  rethrowing is fine because cross-process callers see
  `TResult.Failure` returned normally. But this is a call we
  should make before Phase 3.

- **TString vs TInstant for `capturedAt`.** Today nothing in the
  platform speaks `TInstant`; events would carry a `TLong`
  millis-since-epoch. Acceptable v1.
- **Sampling.** If a misbehaving op throws 10 000 times in an
  hour, do we cap the realm log? The 500-event ring buffer
  already caps. We may want per-error-type rate limiting so a
  single fault doesn't evict 500 distinct earlier events.
  Deferred to Phase 4 review.
- **In-process callers and `TenshuException`.** A repository in
  the same process as the proxy that calls an op directly (not
  via the `ProxyGateway`) gets the typed exception. That's
  correct, but the call sites today don't `try/catch` at all —
  they let exceptions bubble. The Phase 2 migration needs a
  pass on these. The `:file-service-proxy` impls invoking each
  other are the largest cluster.
- **JS-side throw semantics.** `@JsExport` on a function that
  throws works; the JS bridge maps it to `Promise.reject`. We
  should add a JS-side `TenshuException` class so callers can
  `try { } catch (e: TenshuException)` instead of `catch (e:
  Error) { if (e.tenshuErrorId === ...) ... }`. Confirms cleanly
  in the existing JS bridge story.
- **Should the `Errors` companion-on-`Operation` be enforced?**
  My take: convention now, Detekt rule when the i18n Detekt
  rule lands. Doesn't block this proposal.

---

## 9. References

- [ADR 0016](../adr/0016-localisation.md) anchor #4 — the
  load-bearing "typed codes, not free-text, on the proxy boundary"
  rule this proposal extends.
- [ADR 0012](../adr/0012-interactor-pattern.md) — non-suspend
  `Operation` interface; consistent with §3's throwing
  `execute` contract.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — the
  House Rule #1 trust model that motivates §5's
  PII-by-construction posture.
- `docs/milestones.md` § "App Console & Tenshu Store" — Phase 5
  consumer.
- `core-common/.../ProxyGateway.kt:139–167` — the catch site
  this proposal rewrites.
- `core-common/.../OperationImplementation.kt:19–22` — the
  `require` helper this proposal retypes.
- `core/Error.kt` + `core/GenericError.kt` — the existing
  taxonomy this proposal extends.
