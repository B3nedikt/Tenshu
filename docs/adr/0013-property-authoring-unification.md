# ADR 0013 — Property authoring: one `Property<T>` handle, `Relation` as marker

- **Status:** Accepted, implemented
- **Date:** 2026-05-24
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Depends on:** [ADR 0010 — relation system rework](0010-relation-system.md)
- **Amends:** [ADR 0010](0010-relation-system.md) anchor decision 8 (the call-site
  `Property` Composite hierarchy is renamed `*PropertyRelation` here, and a property
  is now a `Relation`)

## Context

ADR 0010 settled the *schema-side* relation taxonomy. One layer up — the Kotlin
**authoring surface** for declaring/reading/projecting/sorting a property — was
still a tangle:

1. **Three property kinds, three unrelated declaration surfaces.** A *value*
   (stored scalar — `File.Size`), a *reference* (constant/singleton —
   `File.Category`), and a *dynamic* (chain-computed — `File.Data`/`Preview`)
   property were each declared differently, projected differently
   (`handle.projection` vs a raw `StaticProperty` val vs a `DynamicProperty`
   factory), and read differently (`entity.get(handle)` vs bespoke
   `entity.category`/`data`/`preview` accessors). There was no single thing called
   `File.Category` the way there was a `File.Size`.

2. **id-vs-marker duplication.** A property existed twice — a string id **and** a
   hand-written `File.Relation.X` projection marker **and** an `entity.x`
   accessor — three declarations for one concept.

3. **Two dead runtime composites.** `relation/PropertyRelation.kt` and
   `relation/DynamicPropertyRelation.kt` (with `type`/`property`/`entityId`/`from`/
   `to`) were never instantiated as runtime values — scaffolding that read as the
   model and wasn't. (Distinct from the real schema descriptors
   `RelationSchema.{Property,DynamicProperty}RelationSchema` and the real call-site
   composites `relation/Property.kt`'s `StaticProperty`/`DynamicProperty`.)

4. **`from`/`to` only fit one relation.** The runtime `Relation` base carried
   `from`/`to`. But once the dead composites (3) were deleted, the *only* runtime
   `Relation` was `ServiceDataRelation` — and `from`/`to` only ever fit *it*
   (service → data). The dead `PropertyRelation` had crammed `(type, property,
   entityId)` into `from` and never read it: a property/projection has a single
   reference (+ params), not a two-endpoint edge. The runtime `Relation` hierarchy
   had drifted from the schema-descriptor side, where `PropertyRelationSchema` was
   *already* a `RelationSchema`.

5. **The wire composite owned the prime name.** `relation/Property.kt`'s
   `Property`/`StaticProperty`/`DynamicProperty` held the name `Property`, leaving
   the dev-facing handle stuck with `PropertyHandle`.

## Decision

A property is **a typed handle from a composite to a value**; the authoring
surface unifies on it. Eight anchor decisions. API + authoring conventions live in
[`docs/schema-dsl.md`](../schema-dsl.md) §7 "Properties & Relations"; this ADR is
the rationale.

1. **A stored value-property kind, on the existing read path.** A stored scalar
   property is a `ValueSchema` whose **id is the property id** (parent = the base
   value type), registered as (a) a schema, (b) a `valueFactory` keyed by that id,
   (c) a property relation of the owner. The deserializer's existing
   `is ValueSchema -> createValue(id, …)` branch reads it back — **no
   serializer/deserializer change**. (Decision A over a new
   `ValuePropertyRelationSchema` + a new deserializer branch — §"Alternatives".)

2. **One `Property<T>` handle, two kinds.** `Property.Value<T>` (static — *any*
   `TValue`/`TConstant`) and `Property.Dynamic<T>` (chain-computed). The old
   "value" and "reference" kinds are the same `StaticPropertyRelation` at the call
   site — they differ only in the value's type (a stored `TInteger` vs a
   `FileCategory` constant) — so one generic `valueProperty(id, schema: DataSchema)`
   builds both; `referenceProperty` and the per-type factories
   (`longValueProperty`, …) are dropped. `dynamicProperty(id, receiver){ chain }`
   builds the Dynamic kind (carrying the existing `ChainTemplate` unchanged).

3. **Properties are extension handles, outside the interface.** A property is
   *extension* data: any module declares `val File.Companion.Size: Property.Value<…>`
   without `File` enumerating it. The interface stays identity-only.

4. **Read returns the stored `TValue`; write takes the raw value.**
   `entity.get(File.Size): TInteger?` (decode = a null-tolerant cast — a Dynamic
   OnDemand placeholder collapses to `null`); `set(File.Size, 0L)` encodes the raw
   value via the registered factory. This read-`TValue` / write-raw asymmetry is
   what lets `valueProperty(id, schema)` stay generic.

5. **Declare/write/read/project/sort all go through the handle.** `set(handle, v)`
   (`Entity.create` builder), `entity.get(handle)`, `relations(handle)`,
   `list.sortBy(handle)`. `relations(vararg handles: Property<*>)` calls
   `handle.selector(params)` to mint the wire selector — collapsing the old
   `.projection` / raw-`StaticProperty` / param-factory surfaces into one.

6. **`Relation` is a fieldless marker; a property *is* a `Relation`.** `from`/`to`
   move onto `ServiceDataRelation` (the explicit-endpoint binding); `Relation`
   becomes the common supertype of *both* `ServiceDataRelation` and the projection
   selectors (`PropertyRelation`/`StaticPropertyRelation`/`DynamicPropertyRelation`).
   This aligns the runtime hierarchy with the schema-descriptor side (which already
   unified them). Conceptually: a one-to-one (or one-to-collection) binding vs a
   many-entities-to-many-values projection.

7. **Naming: the handle takes the prime name.** Handle `PropertyHandle<T>` →
   `Property<T>` (dev-facing — `entity.get(File.Size)`, `relations(File.Category)`,
   `sortBy(File.LastModifiedAt)`). The wire composites become the relation family
   `Property`/`StaticProperty`/`DynamicProperty` → `PropertyRelation`/
   `StaticPropertyRelation`/`DynamicPropertyRelation` (names freed by deleting the
   dead composites in 8). The `RelationSchema.{Property,DynamicProperty}RelationSchema`
   descriptors are **kept as-is** — the base `PropertyRelationSchema` already
   parity-matches the wire base `PropertyRelation`.

8. **The dead runtime composites are deleted** — `relation/PropertyRelation.kt`,
   `relation/DynamicPropertyRelation.kt`, `PropertyRelationDto`, and their bootstrap
   registration. Confirmed never instantiated (no wire payload carries their ids).

## Consequences

- **One authoring surface across kinds.** `File`'s `Size`/`LastModifiedAt`/
  `Category`/`Data`/`Preview` are five extension `Property` handles; the
  `File.Relation` object and the bespoke `entity.category`/`data`/`preview`
  accessors are gone. Registration is `valueProperty(fromId, handle){ factory }`
  (op-written scalar) / `valueProperty(fromId, handle)` (constant — no factory) /
  `dynamicProperty(fromId, handle)`.
- **Read call sites gain a `.get()`** — `get(File.Size)` yields `TInteger?` (was
  `Long?`), `.get()` for the raw scalar. The deliberate consequence of the generic
  builder (decision 4).
- **Runtime ⇄ schema hierarchy now agree** — `Property : Relation` mirrors
  `PropertyRelationSchema : RelationSchema`. The `Relation` marker has no
  polymorphic consumer *yet* (RealmData's metamodel is the obvious near-term one);
  immediately it buys hierarchy symmetry + a real common supertype.
- **Wire format unchanged.** Selector ids (`tenshu/core/relation/property/value`,
  `…/static`, `…/dynamic`) and `ServiceDataRelation`'s resolution are identical;
  the rename is Kotlin-identifier-only. `ServiceDataRelation`'s own field ids moved
  under `…/serviceData/` — safe, that relation is in-memory registration config,
  never serialised.
- **−100 lines of dead code** (decision 8); the per-kind authoring shims collapse
  into the handle.
- Pinned by `core/.../relation/PropertySelectorPinTest` (handle → wire-selector
  shape) + `RelationHierarchyPinTest` (property selectors are `Relation`s) +
  `ValuePropertyRoundTripTest` (stored value survives serialize round-trip).

## When to revisit

1. **A composite-valued property** (value is an arbitrary composite). Cheap *by
   construction* — it's a `StaticPropertyRelation` at the wire, resolved with
   `decode = cast`, so `valueProperty(id, SomeComposite.Schema)` likely already
   expresses the reference/derived case. Only the **stored** variant needs work:
   the deserializer special-cases `is ValueSchema -> createValue`, so persisting a
   composite *value* would need a composite branch there.
2. **A `Relation` consumer arrives** (RealmData metamodel walking relations, or a
   relation-introspection API). Today the marker is hierarchy-symmetry +
   documentation; a consumer makes it load-bearing.
3. **The `StaticPropertyRelationSchema` parity gap bites.** Wire
   `StaticPropertyRelation` has no dedicated schema descriptor — it reuses the base
   `PropertyRelationSchema`. If a tool ever needs to distinguish static from the
   base descriptor by name, introduce `StaticPropertyRelationSchema` then
   (`§"Alternatives"` covers why it wasn't done now).
4. **A collection / reverse-relation property kind.** The per-file
   `File.Relation.History`-style join is a different shape, not a property; out of
   scope here, would be a new kind on the same handle pattern.

## Alternatives considered

- **A new `ValuePropertyRelationSchema` + value-type field + a new deserializer
  branch** (decision 1's option B). Cleaner stored values (plain `TInteger` rather
  than a value whose type-id is the property id), but touches the performance-
  sensitive core read path for no functional gain. Rejected for v1; revisit only if
  value-type proliferation becomes a real problem.
- **Keep `referenceProperty` as a separate builder.** Rejected — a constant
  property is just a static property whose value is a `TConstant`; same
  `StaticPropertyRelation` call-site, same `decode = cast`. The separate builder
  constrained nothing. One generic `valueProperty(id, schema)` covers both.
- **Rename `PropertyRelationSchema` → `StaticPropertyRelationSchema`** for full
  descriptor↔wire parity. Rejected — it would make `DynamicPropertyRelationSchema :
  StaticPropertyRelationSchema` ("dynamic is-a static"), or require a new abstract
  base + reparenting. The base descriptor `PropertyRelationSchema` already matches
  the wire base `PropertyRelation`; the static leaf reusing the base descriptor is
  an acceptable, smaller residual (When-to-revisit 3).
- **Collapse `Relation` into `ServiceDataRelation`** (delete the one-inhabitant
  base) rather than reshaping it into a marker. Rejected — the maintainer's framing
  is that a property *is* a relation too; the marker is the common supertype that
  realises that and matches the schema side, rather than conceding `Relation` ==
  `ServiceDataRelation`.
- **Keep the handle named `PropertyHandle`** (no rename — zero churn). Rejected
  once a property became a genuine `Relation` (decision 6): the prime, dev-facing
  name belongs on the handle, and the wire composites read better as the
  `*PropertyRelation` family. The rename re-touches ADR 0010's call-site names —
  recorded here (Amends).
- **Force `from`/`to` onto every relation** (the original "property inherits
  Relation with from/to" plan). Rejected — a projection has one endpoint; the
  dead `PropertyRelation`'s unused `(type, property, entityId)`-in-`from` cram was
  the evidence. `Relation` is a marker; `from`/`to` are `ServiceDataRelation`'s.

## References

- `docs/schema-dsl.md` §7 "Properties & Relations" — authoring reference (the handle,
  the registration helpers, the wire selectors).
- [ADR 0010](0010-relation-system.md) — the schema-side relation taxonomy this
  extends + amends (call-site names).
- `:core/.../relation/Property.kt` — the `Property<T>` handle (`Value` / `Dynamic`)
  + builders `valueProperty` / `dynamicProperty` / `withParams`.
- `:core/.../relation/PropertyRelation.kt` — wire composites `PropertyRelation` /
  `StaticPropertyRelation` / `DynamicPropertyRelation`.
- `:core/.../relation/Relation.kt` — the fieldless marker.
- `:core/.../relation/ServiceDataRelation.kt` — owns `from`/`to`.
- `:core-common/.../module/ModuleDsl.kt` — registration DSL (`valueProperty` ×2 +
  `dynamicProperty`).
- `:file-service/.../File.kt` — the five extension handles (worked example).
- `:core/.../relation/PropertySelectorPinTest.kt` + `RelationHierarchyPinTest.kt`.
