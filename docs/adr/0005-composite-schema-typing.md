# ADR 0005 — Composite schema typing via `typeParams`

- **Status:** Accepted, implemented (closed out 2026-05-06)
- **Date:** 2026-05-06
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Tenshu has parameterised composites — `TList<E>`, `Maybe<T>`,
`TPair<A, B>`, `TMap<K, V>`, `TResult.Success<T>` / `Failure<T>`,
`StreamElement.Value/Removed/End<T>`, `Entity<T>` — but the schema layer
had no slot for the type parameters. Field declarations on those
composites erased to `Data`: `Maybe.Some.value` was declared as
`composite(Fields.VALUE)` defaulting to `Data.Schema`; `TPair.first` /
`second` similarly; `TList<File>` looked the same as `TList<Folder>` at
schema-introspection time.

The erasure made three things impossible / awkward:

1. **Schema-walking code generators** (the JS SDK's `CompositeSnapshot`,
   future `.d.ts` emitter) couldn't reconstruct the parameterisation —
   `Maybe<File>` walked the same as `Maybe<Data>`.
2. **Proxy-side type validation** (`checkReceiverCompatibility` in
   `EvaluateCallstack`) couldn't enforce parametric receiver matching —
   a chain claiming `TList<File>` accepted any `TList<*>` actual.
3. **Type-driven DSL writers** (e.g. the field declaration on
   `TMap.pairs`) had to either inline `Data.Schema` and lose typing or
   carry runtime "type witness" fields (`TMap.keyType`, `valueType`) as
   a workaround — pure schema-layer redundancy.

The matching `OperationSchema.typeParams` slot is captured in
[ADR 0009](0009-operation-schema-typing.md); the work closing out here is
that ADR's structural sibling for `CompositeSchema`.

## Decision

Add `typeParams: TList<TypeParameterDecl>` to `CompositeSchema` as a
first-class slot, alongside `values` and `traits`. The
`composite(...)` builder accepts `typeParams = { type("E") }` between
`parent` and `fields`. Field types reference declared variables via
`typeVar("E")`. Authoring + substitution conventions are documented in
`docs/schema-dsl.md` §6.

Seven anchor decisions shape the design:

1. **`typeParams` lives on `CompositeSchema`, not `DataSchema`.** Value
   schemas, constants, and reference schemas don't carry generics.
   Keeping the slot narrow avoids polluting categories that don't need
   it.
2. **Type-parameter bound default is `Data.Schema`.** Same default as
   the operation-schema-typing proposal locked in.
3. **No declaration-site variance in v1.** `Variance` exists on use-site
   `TypeParameter`; declaration-site variance is a future concern. Every
   `TypeParameterDecl` is invariant.
4. **Inheritance is by-value, not by-reference.** When a composite
   extends a parameterised parent (e.g. `Maybe.Some` extends `Maybe`),
   the child re-declares the parent's parameterisation in its own
   `parent = composite(Maybe.ID, typeParams = { type("E") })` slot —
   the child's `typeParams` replaces, it does not unify with the
   parent's. Simpler model; unification semantics can be added later
   if a real case demands it.
5. **`TypeVariable` references are scoped to the declaring composite.**
   `composite(typeParams = { type("E") })` introduces `E` into the
   `fields = { ... }` block. Nested composites re-declare their own.
6. **Wire format unchanged.** `CompositeDto.fieldValues` stays
   index-keyed; `typeParams` is a schema-level attribute, not a
   per-instance field. Materialised composites do not carry their
   parameterisation at runtime — that binding stays on the producing
   `Type` (e.g. `listType(File.Schema)`), exactly parallel to how the
   operation receiver binding works.
7. **Substitution is the consumer's job.** The schema stores
   `TypeVariable` references unresolved; consumers (proxy validator,
   field-access ROs, future codegen) substitute via
   `substitute(type, bindings, defaultForUnbound = Data.Schema.toType())`.
   When the caller can't supply a binding, fallback preserves the
   historical erased behaviour.

## Consequences

- **Every parameterised composite declares `typeParams`.** A regression
  test (`phase2_parameterisedComposites_declareTypeParams`) freezes the
  inventory.
- **`TMap`'s runtime type-witness fields are gone.** `keyType` /
  `valueType` `TypeReference` slots on `TMapDto` were a workaround for
  the absent schema-level parameterisation. After this work they're
  semantically redundant; the cleanup dropped the schema slots, getters,
  constants, and `EntityDto` constructor sites that fed them. This was
  an atomic wire-format break — defensible because the database is
  ephemeral and the IPC channel ships in one package, but the next such
  cleanup needs a real migration story tracked under the
  `Index-Based DTO Fragility` tech-debt entry.
- **`CompositeRo` / `EntityRo` accept an optional `params` list** so
  field resolution can compute bindings from
  `schema.typeParams.zip(params)` and `substitute` before delegating to
  the RO factory. `MaybeRo` propagates its construction-time
  `elementType` via this slot.
- **`checkReceiverCompatibility` walks expected/actual pairwise into
  `GenericType` params.** A `TypeVariable` in the expected slot accepts
  any actual; the walk is conservative when actual carries no params
  (i.e. preserves today's behaviour for runtime composites that haven't
  surfaced their parameterisation).
- **The DSL author surface is +1 keyword** (`typeParams`) and one new
  field-type spelling (`typeVar("E")`). Default empty block keeps every
  pre-existing call site compiling unchanged.

## When to revisit

Re-open when one of these lands:

1. **Declaration-site variance.** First time a real use case wants
   `out E` or `in E` on a composite. Today's invariant default is a
   good base; variance composes additively.
2. **TypeVariable substitution in operation return types.** Currently
   deferred — operations declare `returnType` but the substitute path
   doesn't propagate caller bindings through. Closing this lets
   `findOperation<Search<File>>(...)` return a typed `Maybe<Entity<File>>`
   without manual annotation.
3. **JS SDK schema-walker TypeVariable substitution** (the parked
   "Phase 3.2" from the original proposal). Today `CompositeSnapshot.kt`
   is a runtime value flattener; substitution only matters once a
   schema → `.d.ts` emitter exists. When the web track adds that
   emitter (see `docs/tech-debt.md` §"Dukat Output Quality" + the
   hand-written-adapter alternative), this becomes a one-step extension
   of that emitter.

## Alternatives considered

- **Carry runtime type-witness fields on every parameterised composite.**
  How `TMap.keyType` / `valueType` worked pre-work. Rejected — the
  witness fields and the schema-level parameterisation say the same
  thing twice, with no machinery to keep them in sync. The migration
  dropped the witnesses.
- **`typeParams` on `DataSchema` (not `CompositeSchema`).** Considered
  for symmetry with whatever future categorical schemas might want
  parameterisation. Rejected as premature pollution — only composites
  are parameterised today, and `DataSchema` is the bootstrap floor
  (every value/constant/reference inherits it).
- **Resolution-side substitution** — substitute typeVars at schema
  materialisation time rather than at consumer time. Rejected:
  materialised composites genuinely don't know their parameterisation
  (a `Maybe.Some` row in the DB doesn't carry "I was created for
  `File`"); the binding lives on the producing `Type` at the call site.
  Pushing substitution to the consumer is a one-way constraint that
  fits how the data actually flows.
- **Wire-encode the parameterisation per-instance.** Have
  `CompositeDto.fieldValues` carry the typeParam bindings on every
  serialised composite. Rejected — wire-size cost (every entity grows
  by the param list length) for no consumer benefit (consumers already
  recover the binding from the producing `Type`).
- **Unification on inheritance** — `Maybe.Some<T>` automatically
  unifies its `T` with `Maybe<T>`'s. Rejected for v1 — simpler to
  re-declare; the duplication is local and obvious. Add unification
  when a real call site demands it.

## References

- `docs/schema-dsl.md` §6 — authoring reference for `typeParams` and
  `typeVar(...)`.
- [ADR 0009](0009-operation-schema-typing.md) — the structural sibling on
  `OperationSchema` (still active for downstream substitution work).
- `core-preference-service.md` §4 — first proposal-doc consumer of
  the convention (`Setting<V : Data>` super-composite + concrete
  `RealmSetting<V>` / `DeviceSetting<V>`).
- `:core/schema/Type.kt::substitute` — the resolution helper.
- `core-common/.../proxy/EvaluateCallstack.kt::checkReceiverCompatibility` —
  the pairwise param walk.
- `phase2_parameterisedComposites_declareTypeParams` — inventory pin
  test.
