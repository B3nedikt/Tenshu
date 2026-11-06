# ADR 0010 — Relation system rework: `ServiceDataRelation`, materialised dynamic chains, honest names

- **Status:** Accepted, implemented (Phases 0.5, 1, 2, 3, 4, 5.1 landed 2026-05-07; Phase 5.2 deferred)
- **Date:** 2026-05-07
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Amended by:** [ADR 0013 — property authoring unification](0013-property-authoring-unification.md)
  (anchor decision 8: the call-site `Property` / `StaticProperty` / `DynamicProperty`
  composites are renamed `PropertyRelation` / `StaticPropertyRelation` /
  `DynamicPropertyRelation`, a property is now a `Relation`, and the authoring
  surface is the `Property<T>` handle)
- **Depends on:** [ADR 0009 — operation schema typing](0009-operation-schema-typing.md) (`Type` infrastructure)

## Context

Four concrete shape problems in the pre-rework relation system:

1. **`EntityRelation` / `EntityListRelation` conflation.** Two service-binding
   relations that looked near-identical at the declaration site (same `from`,
   `to`, same registration helper shape), with the only real difference being
   single-vs-collection. Field names disagreed for no reason (`type` vs
   `itemType`). Resolver-side mirrored the split: `resolveEntity` and
   `resolveEntityList` as near-duplicate methods. The surface suggested
   "list-vs-single variants of the same concept" but the two were doing
   genuinely *different* things — single-by-id vs collection-by-type — that
   composed cleanly under a single `Type`-keyed primitive.

2. **`DynamicPropertyRelation.callstack: CallStack<*>` was the one
   schema-as-code seam.** A Kotlin lambda living on a schema. Every other
   piece of the schema system is `Composite` data — introspectable, walkable,
   serialisable. This one wasn't. Three consequences: the JS SDK walker
   couldn't tell what `User.Relations.AvatarImage` did (it saw `<dynamic>`
   and stopped); remote schema-introspection tools were blind to dynamic
   relations; the lambda re-ran on every `resolveDynamicProperty` call,
   even though the chain shape it produced was effectively constant per
   receiver type.

3. **`DynamicProperty` was a type-safety hole.** The pre-Phase-0.5
   `relations(vararg relations: Any)` selector switched on three shapes
   (`String`, `DynamicPropertyRelationSchema`, `DynamicProperty` data class),
   with an `else -> error` branch and a category error inside: the
   `DynamicPropertyRelationSchema` branch passed `schema.params` (the
   schema's *param types*, `TList<DataSchema>?`) as the selector's runtime
   `params: TList<Data>?`. `DataSchema : Data` so it compiled; semantically
   it was only safe when `params` was null. The fundamental confusion:
   the schema is a *type description*; the call-site value is a *reference
   binding runtime params*. Passing the schema directly relied on the
   coincidence that it had `id` + `params` fields.

4. **`CallstackLinearizer` and `CallStack` were misleadingly named.**
   `CallstackLinearizer.linearize(...)` didn't linearise anything; it
   *materialised* a chain from a deferred template. `CallStack<T>` wasn't a
   stack; it was a deferred chain-construction function. And the block's
   return type was declared `Data` rather than the lambda's actual return
   type, forcing `as Data` casts at every dynamic-relation declaration
   site (Kotlin inference for extension lambdas with bounded generics is
   finicky enough that the widening had stuck).

The four problems were independent enough to fix one at a time but shared
enough infrastructure that doing them together avoided two passes through
the same registration + resolver machinery.

## Decision

Four-axis rework, all behaviour-preserving, wire format unchanged.

API surface + authoring conventions live in
[`docs/schema-dsl.md`](../schema-dsl.md) §7 "Properties & Relations". This ADR
captures the rationale.

Nine anchor decisions:

1. **Four relation kinds, organised by role.** Today's five collapse into:
   *property relations* (describe shape on a composite — `PropertyRelation`,
   `DynamicPropertyRelation`) and *service-binding relations* (describe
   what a service-relation id resolves to — `ServiceRelation`,
   `ServiceDataRelation`). `EntityRelation` + `EntityListRelation` fold
   into `ServiceDataRelation`.
2. **`ServiceDataRelation` takes a `Type`.** Single-vs-list lives in the
   type (`Folder.Schema.toType()` vs `TList.typeOf(Notification.Schema)`).
   An optional `dataId: EntityDataId?` carries the by-id specification
   only for the single case; absent for the collection case.
3. **One resolver entry point.** `ExecutionContext.resolveServiceData(service,
   relation)` replaces both `resolveEntity` and `resolveEntityList`.
   Switches on `type.baseSchema?.id == TList.ID`: collection branch builds
   an `ArrayDatabaseReference`; single branch reads
   `database.getData(dataId, asEntity = true)`.
4. **Dynamic-relation chains are materialised at first schema-materialisation.**
   The `chain: TList<Data>` field on the schema is populated by invoking the
   `ChainTemplate<T, R>` lambda once via `CallstackMaterializer`, against a
   synthetic receiver RO of type `T`. The result is cached in
   `SchemaRegistry.materializationCache` for the duration of the context.
   The lambda runs at most once per `(LazyDataCreator, context)` pair.
5. **Chain *evaluation* stays per-resolution.** Materialisation produces
   the chain *shape* (`List<Data>`); evaluation produces the value.
   `evaluateCallstack` against the materialised chain runs as today on
   every `resolveDynamicProperty` call.
6. **`CallStack<T>` → `ChainTemplate<T : Data, R : Data>`.** Two type
   parameters: receiver and return. The block becomes
   `T.(params: List<Any>) -> R`. Existing callers gain an explicit return
   type (`ChainTemplate<IdentityService, TByteArray>` for `AvatarImage`),
   and the `as Data` cast deletes.
7. **`CallstackLinearizer` → `CallstackMaterializer`.** Same class, same
   constructor, same call signatures — name change only.
   `linearize(...)` becomes `materialize(...)`.
8. **`Property` Composite hierarchy at the call site.** Sealed `Property`
   parent with `StaticProperty` and `DynamicProperty` subtypes, both real
   Composites declared via the schema DSL. `relations(vararg refs: Property)`
   switches on the sealed hierarchy. `DynamicProperty` (the old Kotlin data
   class) deletes. The reference layer goes back to identity-only
   (`TConstants`); content-bearing call-site values live as Composites.
   This took three iterations to land — see "Alternatives considered".
9. **Receiver-type dispatch in dynamic chains is forbidden.** Today's
   `resolveDynamicProperty` passed the runtime data's type id as the
   chain's receiver. After this work the chain is pre-materialised against
   the template's `<T>`, and a relation whose lambda inspects the runtime
   receiver fails registration. Both existing call sites
   (`User.Relations.AvatarImage`, `User.Relations.Devices`) declare a fixed
   `<T>`; no current use case wants per-receiver dispatch. Decision #25 of the
   May-2026 open-design-decisions walkthrough (since deleted): *pre-materialise
   dynamic-relation chains unconditionally; reject receiver dispatch*.

## Consequences

- **Two schema types deleted** — `EntityRelationSchema`,
  `EntityListRelationSchema` (Phase 5.1). Their `*Dto` mirrors and wrapper
  registration helpers (`entityRelation(...)`, `entityListRelation(...)`)
  gone with them. `EvaluateCallstack`'s reference-dispatch branch
  consolidated.
- **One new schema type** — `ServiceDataRelationSchema` with `type: Type`
  and `dataId: DataId.EntityDataId?` fields. Single helper
  `serviceDataRelation(...)` covers both the single-by-id and
  collection-by-type cases that used to need two.
- **One new resolver method** — `resolveServiceData(...)` — replacing
  `resolveEntity` + `resolveEntityList`. The resolver switches on
  `type.baseSchema?.id` once.
- **`DynamicPropertyRelationSchema` gains `chain: TList<Data>` +
  `expectedReturnType: Type`.** Schema-level fields; `expectedReturnType`
  is captured from `ChainTemplate<T, R>`'s `R` and makes the relation's
  intended result type schema-visible. Future JS SDK walker enhancement
  reads it to emit accurate TypeScript types (`User.avatarImage:
  Promise<Uint8Array>` rather than `Promise<unknown>`).
- **Six `entityRelation` / `entityListRelation` call sites migrated**
  (Phase 3): one per service-data relation across the file / notification /
  identity / sync service modules.
- **`LazyDataMaterializer` registered as a `TContext` component** (Phase 4)
  so dynamic-relation chains materialise lazily without each call site
  reconstructing the materialiser.
- **`Property` Composite hierarchy lands** — `vararg: Any` + `else -> error`
  at the `relations(...)` call site is gone (Phase 0.5).
- **Wire format unchanged.** `chain` and `expectedReturnType` are
  schema-level proxy-side state today; they don't enter `OperationDto.params`
  or any serialised module configuration. The renames are internal-only.

### Phase 5.2 deferred

`DynamicPropertyRelationSchema.callstack` (the old `CallStack<*>` field) was
*not* deleted alongside Phase 5.1's `EntityRelation` / `EntityListRelation`
cleanup. It remains as the fallback for:

- **Param-bearing relations** — `File.Relation.Preview(width, height)`-style
  relations where the chain depends on runtime params. The current
  materialisation against a fixed receiver type doesn't model param
  variations; the original `CallStack<*>` lambda is the right primitive
  there until a wider design covers params explicitly.
- **Wire-deserialised schemas** — if a `DynamicPropertyRelationSchema` is
  ever reconstructed from a serialised module configuration (today: never;
  module configs re-register on startup), the materialiser would need a
  template to run, and only the original lambda has one.

This is the only remaining piece of the proposal. Lands when either of
the above motivates it.

## When to revisit

Re-open when one of these is true:

1. **Phase 5.2's `callstack` field can be fully deleted.** Triggers:
   (a) a design covers param-bearing dynamic relations so the materialised
   chain can encode param variations cleanly; (b) wire-deserialised module
   configurations become a real thing and force the
   `DynamicPropertyRelationSchema`-from-bytes path.
2. **A receiver-dispatched dynamic chain becomes a real requirement.**
   Anchor decision 9 forbids it; the registration check rejects relations
   whose template lambda inspects the receiver's runtime type. Re-open
   with a concrete use case and we redesign the materialisation contract
   to model the per-receiver-type variability honestly.
3. **The JS SDK schema walker grows the substitution helper.**
   `expectedReturnType` exposes the relation's intended return type
   schema-visibly; consumer-side substitution is what turns it into
   accurate TypeScript. Lands when the web track invests in the walker
   improvement.
4. **Index-Based DTO Fragility gets the long-term map-based fix.**
   `DynamicPropertyRelationSchemaDto` index assignments were appended for
   `chain` + `expectedReturnType`; the long-term map-based DTO storage
   (tech-debt) would make this kind of evolution structurally safe rather
   than convention-safe.

## Alternatives considered

- **Keep `EntityRelation` + `EntityListRelation` as two types; only
  unify the resolver.** Considered for narrow-scope. Rejected — the
  declaration-site duplication (same `from`/`to`/registration shape) was
  the primary cost; collapsing the resolver alone leaves the two
  near-duplicate registration helpers and the `type` vs `itemType` field
  naming inconsistency. `ServiceDataRelation` is one type that
  expresses the actual variation (single by id, collection by type) via
  its `Type` + optional `dataId` shape.
- **`DynamicProperty` as a `Reference` (not a `Composite`).** The
  iteration captured in 0.5b — content-bearing call-site values represented
  as references with optional `params`. Rejected as a category error:
  References are `TConstants` (pure identity, just a string id wrapped in
  a typed container). Adding content (`params`) made the reference no
  longer constant. The third attempt (0.5c) settled on Composites for
  content-bearing values; the reference layer goes back to identity-only.
- **`DynamicProperty` as a sealed Kotlin `PropertyRef` interface with
  data class impls** (the first 0.5 attempt). Rejected for the same
  reason `DynamicProperty` was a problem to begin with — Kotlin data
  classes off-pattern from the composite-everywhere convention; the
  selector helper would still need `vararg: Any` or a similar lift.
  Composites all the way down is the consistent answer.
- **Lazy materialisation per-resolution, not per-context.** Considered for
  storage hygiene. Rejected — anchor decision 4 caches materialisation in
  `SchemaRegistry.materializationCache` for the context's lifetime, so the
  lambda runs at most once per `(LazyDataCreator, context)` pair. Per-
  resolution materialisation would re-walk the template on every call,
  defeating one of the primary wins.
- **Allow receiver-type dispatch in dynamic chains** (the old
  `resolveDynamicProperty`-passes-runtime-type-id behaviour). Rejected
  per anchor decision 9 — both existing call sites (`AvatarImage`,
  `Devices`) declare a fixed `<T>`; no current use case wants per-receiver
  dispatch; the simpler choice (forbid via registration check) ships
  today's behaviour cleanly. Re-open with a concrete case.
- **Delete `DynamicPropertyRelationSchema.callstack` alongside Phase 5.1.**
  Considered for closure. Deferred to Phase 5.2 — the field remains
  load-bearing as fallback for param-bearing relations and the
  wire-deserialised-schema path. Closing it needs a wider design that
  covers params explicitly; not blocking and not motivated by current
  use cases.
- **Rename `RelationSchema` taxonomy with new sealed intermediate
  interfaces** ("property relation" / "service-binding relation" as
  sibling sealed types in Kotlin). Rejected — the conceptual organisation
  is documentary (helps readers); the Kotlin type hierarchy stays a
  single sealed `RelationSchema` family. No new abstract intermediates.

## References

- `docs/schema-dsl.md` §7 "Properties & Relations" — authoring reference for
  the three relation helpers + the `Property` Composite hierarchy at the
  call site + the rename pin.
- [ADR 0009](0009-operation-schema-typing.md) — the `Type` infrastructure
  prerequisite (Phase 1 of the operation-schema-typing work).
- `:core/.../schema/RelationSchema.kt` — `ServiceDataRelationSchema` +
  unchanged `PropertyRelationSchema` / `ServiceRelationSchema` /
  `DynamicPropertyRelationSchema`.
- `:core/.../ChainTemplate.kt` — `ChainTemplate<T : Data, R : Data>`
  (was `CallStack<T>`).
- `:core-common/.../proxy/CallstackMaterializer.kt` — was
  `CallstackLinearizer`.
- `:core-common/.../proxy/ExecutionContext.kt::resolveServiceData` —
  single resolver entry point.
- `:core/.../relation/Property.kt` — sealed `Property` Composite hierarchy
  + `StaticProperty` + `DynamicProperty`.
- Decision #25, May-2026 open-design-decisions walkthrough (deleted 2026-08-13):
  pre-materialise dynamic-relation chains unconditionally; reject receiver dispatch.
