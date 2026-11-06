# ADR 0009 — Operation schema typing via `receiver`, `typeParams`, and `TypeVariable`

- **Status:** Accepted, implemented (Phases 1–5 closed out 2026-05-05 / 2026-05-06)
- **Date:** 2026-05-05
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **See also:** [ADR 0005](0005-composite-schema-typing.md) — the composite-side sibling

## Context

Pre-this-work, three concepts lived implicitly in Kotlin generics + call-site
lookup keys but were absent from operation schemas:

1. **The receiver** — the `Type` an operation dispatches on. Hand-passed as the
   first arg of `findOperation<X>(typeId, opId)`; never stored on the schema.
   Every consumer needed to know about this side channel.
2. **Type variables** — the Kotlin generic linking a receiver's `T` to a return's
   `T`. The schema saw neither.
3. **Parameterised return types** — `Drop<T>` returns `TList<T>` at runtime but
   the schema declared `TList<Data>`; the element-type binding was lost in
   the gap.

Four concrete consequences:

- `findOperation<T>(typeId, opId)` required the caller to know `typeId`
  separately from the schema. Wrong combination = silent miss.
- The JS SDK schema-walker couldn't tell that `Drop` preserves the element type;
  its TypeScript surface for any list operation degraded to `TList<unknown>`
  without per-op hand overrides.
- `ModuleConfiguration.registerOperation(baseTypeId, ...)` had two overloads —
  the second only existed to carry the receiver to the registry, because the
  schema couldn't supply that information.
- `OperationTypeProvider`, a marker interface carrying the runtime element-type
  binding via a mutable `var type: String` set through unsafe casts, was the
  most-tampered-with surface in the proxy layer. Every parameterised RO field-
  access site (`CompositeRo.resolveField`, `EntityRo`, `NotificationServiceRo`,
  etc.) hand-stitched the same "create-with-default-then-mutate" pattern,
  occasionally forgetting and producing `TList<Data>` where `TList<File>` was
  expected (the tech-debt entry `Element Type Not Propagated Through List ROs`
  captured the recurring bug class).

The matching composite-side work landed as [ADR 0005](0005-composite-schema-typing.md);
this ADR documents the operation-side decisions.

## Decision

Add three concepts to operation schemas as first-class slots:

1. `receiver: Type?` — the `Type` the operation dispatches on.
2. `typeParams: TList<TypeParameterDecl>` — named, bounded type variables the
   operation declares.
3. `TypeVariable : Type` — a new `Type` subkind referencing a declared parameter.

DSL + authoring conventions live in
[`docs/schema-dsl.md` §3 Operations](../schema-dsl.md#3-operations). This ADR
captures the rationale.

Nine anchor decisions:

1. **Three new kwargs on `operation(...)`, in this order**: `receiver`,
   `typeParams`, `params`, `returnType`. `receiver` reads as the headline
   ("what does this op dispatch on?"); `typeParams` introduces variables for
   use further down; `params` and `returnType` are unchanged in meaning.
2. **`receiver: Type? = null`** — single `Type`, not a list. Operations never
   dispatch on multiple receivers; multi-arg dispatch belongs in `params`.
   `null` means the operation is unattached (top-level service op with no
   implicit caller).
3. **`typeParams` is a builder, parallel to `params`.** Each `type(name, bound)`
   call inside binds a name *and* returns the `TypeParameterDecl` for binder-based
   reuse. Both name-based (`listType("E")`) and binder-based (`listType(E)`)
   references are supported; binder-based is the recommended form.
4. **`TypeVariable` is a new `Type` subkind**, distinct from `GenericType` and
   `UnionType`. Carries the variable name + the declaring operation's id (for
   diagnostics; not lookup). Does *not* carry the bound — bounds live on the
   `TypeParameterDecl`.
5. **Receiver dispatch supersedes `typeId`-keyed lookup.** During migration both
   were supported; after Phase 4, the `(typeId, opId)` overload of `findOperation`
   and the `(baseTypeId, ...)` overload of `registerOperation` were deleted.
   `Maybe<R>.select` (the one polymorphic-receiver carve-out) was migrated to
   "select on the list, then search" on its two production call sites
   (`MaybeIntegrationTest`, `TenshuFilesRepository.downloadFile`); `MaybeSelectRo`,
   the `Maybe<R>.select` extension, and the `Maybe.ID` Select registration are
   all gone.
6. **Bounds default to `Data.Schema`.** Same default as today's `returnType`.
   `type("E")` means `E : Data`.
7. **No declaration-site variance on `TypeParameterDecl` in this round.**
   Use-site `Variance` on `TypeParameter` continues to apply unchanged; every
   `TypeParameterDecl` is invariant. Adding declaration-site variance is a
   future concern.
8. **No constraint that the receiver use a declared type variable.** `Drop`'s
   `listType("E")` references `E`, but a non-generic op like `LogOperation`
   can have `receiver = LoggingService.Schema.toType()` with empty
   `typeParams`. Equally, an op can declare `typeParams` whose variables only
   appear in `returnType` (factories).
9. **Type-variable substitution is the consumer's job.** The schema stores
   `TypeVariable` references unresolved; the JS SDK walker substitutes when
   emitting TS types, the resolver substitutes when checking compatibility.
   The schema is the *declaration*, not the *instantiation*.

## Consequences

- **`findOperation<X>(X.ID)` is single-overload.** `typeId` argument gone; the
  receiver is derived from `schema.receiver`. Every call site got cleaner.
- **`OperationTypeProvider` is deleted.** Replaced by `OperationReceiverHolder`
  (typed `receiver: Type` + `narrowedElementType`). The
  "create-with-default-then-mutate" pattern at parameterised RO field-access
  sites collapsed to a single `createRequestObject(field.type, ...)` call —
  resolved the recurring tech-debt entry `Element Type Not Propagated Through
  List ROs` as a side effect.
- **`RoRegistry.createRequestObject` takes `Type`** (was `String`).
  Parameterised RO factories (`ListRo`, `MaybeRo`, etc.) extract
  `elementType: DataSchema` from `Type.params` at construction time. The
  binding is **purely client-side / chain-construction** — never enters
  `OperationDto.params`. **Wire format unchanged.**
- **`ModuleConfiguration.registerOperation` is single-overload.** Receiver
  derived from `protocolSchema.receiver`. Startup-time validation asserts
  (a) the receiver's `baseSchema` is a registered `CompositeSchema` or null,
  (b) `TypeVariable`s in `receiver`/`returnType` are declared in `typeParams`.
  Sibling `OperationSchemaTypingInvariantTest` to the existing
  `OperationSchemaInvariantTest`.
- **`evaluateCallstack` validates receiver `baseSchema` compatibility** before
  executing, returning `InvalidCallerError` on mismatch. Full TypeVariable
  substitution + nested-generic checks remain deferred (see "When to revisit").
- **`OperationDto`'s `init` rejects unknown param keys + per-value subtype
  mismatches** at construction (Phase 5.7). File-service ROs that were
  passing plain `TString` for `FilePath` params have been tightened to
  `FilePath.create(...)`.
- **`OperationImplementation.execute` narrowed `caller: Any?` → `caller: Data?`**
  (the §5.1 ride-along). Implementations migrating to
  `executionContext.resolveList(caller)` did paperwork once.
- **Index-based DTO fragility motivated.** `OperationSchemaDto` gained two
  field slots (receiver, typeParams). Safe append-only ordering preserves
  existing layout but the very fragility is what `tech-debt.md`
  §`Index-Based DTO Fragility` calls out as the long-term map-based fix.

## When to revisit

Re-open when one of these is true:

1. **Full TypeVariable substitution in `evaluateCallstack`.** Today's
   receiver check is `baseSchema`-only; full substitution + nested-generic
   compatibility checks (e.g. rejecting `findOperation<Drop<File>>` invoked
   on `TList<Folder>`) need a substitution helper threaded through the
   validator. ADR 0005's `substitute(...)` is the same machinery on the
   composite side.
2. **Declaration-site variance on `TypeParameterDecl`.** First time a real
   use case wants `out E` or `in E` on an operation's type parameter. Today's
   invariant default is a good base; variance composes additively. Note that
   the field already exists on `TypeParameter` — the symmetry is half-done.
3. **JS SDK schema-walker grows past `unknown`.** When the TS-type emitter
   needs typed operation signatures, the walker gains a `TypeVariable` branch
   that substitutes per consumer call site. The infrastructure for that is
   the schema-level data added here; the substitution helper is the open work.
4. **Schema-first code generation lands** (`tech-debt.md` §`Index-Based DTO
   Fragility` long-term fix). Typed implementation signatures (`SyncImpl<TList<Data>,
   TList<Data>>` shape, with the receiver + params + return mirrored from the
   schema) become possible once the generator can read `schema.receiver` +
   `schema.typeParams`. This ADR's work was a prerequisite for that.

## Alternatives considered

- **Leave the receiver implicit, fix the JS walker per-op by hand.** Considered
  for narrow scope. Rejected — the per-op overrides scale linearly with every
  new operation and need to stay in sync with Kotlin generic shapes; the
  schema already carries every other operation-describing concept (params,
  return), and receiver is the same kind of declaration.
- **Receiver as a list (`receiver: List<Type>`).** Considered for symmetry
  with future multi-arg dispatch. Rejected — operations don't dispatch on
  multiple receivers; multi-arg dispatch belongs in `params` (anchor
  decision 2). A single-value `receiver` matches what every operation in
  the codebase actually wants.
- **Store the bound on `TypeVariable` (not `TypeParameterDecl`).**
  Considered briefly. Rejected — a `TypeVariable` is a *reference* to a
  declared parameter; the parameter is what carries the bound. The same
  variable can appear in multiple places (receiver, return, nested types);
  duplicating the bound at every reference site would invite drift.
- **Keep `OperationTypeProvider` as a transitional shim.** Considered for
  smaller-scope Phase 5. Rejected — the marker's mutable `var type: String`
  + unsafe-cast pattern was the proximate cause of the `Element Type Not
  Propagated Through List ROs` recurring bug. Replacing it with construction-
  required `elementType: DataSchema` on parameterised ROs (no setter, no
  cast) was net cheaper than maintaining the legacy path and broke nothing —
  the wire format was already independent.
- **Eager substitution at schema materialisation.** Considered for ergonomics
  (have the schema resolve typeVars to their bindings up-front). Rejected
  per anchor decision 9 — substitution depends on the consumer's context
  (what concrete types the caller bound the variables to), which the schema
  layer doesn't have. Pushing substitution to consumers is the one-way
  constraint that fits how the data actually flows.
- **Bundle composite schema typing into the same proposal.** Considered for
  unification. Rejected — different migration radii (`OperationSchema`'s
  receiver/typeParams touch ~40 files; `CompositeSchema`'s would touch
  hundreds). Operations first, composites as a follow-up that landed cleanly
  as [ADR 0005](0005-composite-schema-typing.md).
- **Drop `evaluateCallstack` receiver validation entirely** rather than
  doing baseSchema-only checks (the carve-out's compromise). Considered
  because the typed-RO path catches most receiver mismatches at construction.
  Rejected — `OperationDto.params` arrives over the wire from an arbitrary
  client; runtime validation is the last line. baseSchema-only is the
  cheapest correct check.

## References

- `docs/schema-dsl.md` §3 — authoring reference for `receiver` /
  `typeParams` / `typeVar(...)` / `listType` / `maybeType` helpers.
- [ADR 0005](0005-composite-schema-typing.md) — composite-side sibling
  (`typeParams` on `CompositeSchema`).
- `:core/.../schema/Type.kt::TypeVariable` — new `Type` subkind.
- `:core/.../schema/TypeParameterDecl.kt` — declared parameter with name +
  bound.
- `:core/.../schema/OperationSchema.kt` — `receiver` + `typeParams` slots.
- `:core/.../schema/Dsl.kt` — `operation(...)` signature + `listType(E)` /
  `maybeType(E)` helpers + `typeVar(name)`.
- `:core-common/.../proxy/RoRegistry.kt::createRequestObject(type: Type, ...)`.
- `:core-common/.../proxy/EvaluateCallstack.kt::checkReceiverCompatibility`
  — baseSchema-level receiver check.
- `OperationSchemaInvariantTest` + `OperationSchemaTypingInvariantTest`
  — schema-level invariants.
