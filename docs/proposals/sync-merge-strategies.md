# Configurable Merge Strategies — Trait-Based DSL

**Status:** Spec'd; not started.

**Scope:** `:sync-service-common` (`SemanticMerger.kt`, `SemanticMergerRegistry.kt`,
new `MergeStrategy*.kt`), `:core` (`schema/Dsl.kt` — `fieldLevelMerge` + `traitFor`
DSL helpers, new `MergeStrategy` traits), `:file-service-proxy/.../FolderMerger.kt`
(migration), per-service `*Module.kt` files that need to declare merge strategies.

This doc replaces the per-type Kotlin `SemanticMerger` registration as the *only* way
to configure merging. After this lands, most merge logic moves into the schema (via
declarative per-field strategies attached as traits), and `SemanticMerger` becomes the
escape hatch for cases that genuinely can't be expressed declaratively.

---

## 1. Motivation

Today's merge machinery is per-type Kotlin code:

```kotlin
class FolderMerger : SemanticMerger {
    override val typeId: String = Folder.ID
    override fun merge(...): Data { /* path-keyed children merge */ }
}
```

Two limits:

1. **Coarse.** "Register a `SemanticMerger` for type X" is the only knob. Per-field
   semantics — "this field is LWW, this field is set-union, this field is a counter" —
   have to be expressed inside hand-rolled Kotlin.
2. **Opaque.** The schema doesn't know how a type merges. Tooling, JS SDK, validators
   can't introspect merge behaviour.

The fix: lift merge configuration into the schema as **traits**, with
`PerFieldMergeStrategy` carrying per-field declarations. Trait inheritance gives
override semantics for free; trait introspection gives tooling what it needs.

---

## 2. Design Decisions

Locked in for the rest of the doc:

1. **Trait hierarchy** with one abstract trait and concrete subtypes:
    - `MergeStrategy` (abstract; marker that the type configures merging).
    - `PerFieldMergeStrategy` (concrete; carries per-field strategy map).
    - `SemanticMergeStrategy` (concrete; marker for types with a registered Kotlin
      `SemanticMerger` — the escape hatch).
    - `CRDTMergeStrategy` (placeholder; future, when there's a real CRDT use case).

2. **Default for unmarked types.** A type with no `MergeStrategy` trait gets an
   *implicit* `PerFieldMergeStrategy` synthesised at materialisation time, with all
   fields defaulting to `LastWriteWins`. The dispatcher always goes through the
   per-field path; there's no separate "no merger" branch.

3. **Strategies live on the trait, not on `Field`.** The trait carries the
   `Map<FieldRef, MergeStrategyKind>`. `Field.Schema` is unchanged. Strategies are
   declared via a dedicated DSL (§3.2) and can be attached either inline at schema
   definition or via `traitFor(...)` from outside.

4. **Trait goes on the wire.** Schemas are serialised today, traits with them; the
   `PerFieldMergeStrategy` trait's per-field map is part of the wire schema.
   Single source of truth.

5. **Built-in strategy primitives:** `LastWriteWins`, `Max`, `Min`, `SetUnion`,
   `Counter`, `KeyedMerge(key, tiebreaker)`. `KeyedMerge`'s tiebreaker shape:
   `MaxBy(fieldRef)`, `MinBy(fieldRef)`, `LastBy(hlcFieldRef)`.

6. **`SemanticMerger` survives** as the registered Kotlin escape hatch. Types that
   declare `SemanticMergeStrategy` look up their merger in the renamed
   `MergerRegistry`. **Probably zero current types need it** after migration —
   `FolderMerger` collapses to `KeyedMerge(File.PATH, MaxBy(File.LAST_MODIFIED))`.

7. **Fail-fast at registration:** if a schema declares `SemanticMergeStrategy` and
   no `SemanticMerger` is registered for that typeId, module-config build fails.

---

## 3. Final Shape

### 3.1 Trait declarations

```kotlin
// :core/.../trait/

interface MergeStrategy : Trait {
    companion object {
        const val ID = "tenshu/core/trait/mergeStrategy"
        val Schema: TraitSchema = trait(ID)
    }
}

interface PerFieldMergeStrategy : MergeStrategy {
    val fieldStrategies: TList<FieldMergeBinding>
        get() = getField(Fields.FIELD_STRATEGIES)

    companion object {
        const val ID = "tenshu/core/trait/mergeStrategy/perField"
        val Schema: TraitSchema = trait(ID, parent = MergeStrategy.Schema, fields = {
            list(Fields.FIELD_STRATEGIES, of = FieldMergeBinding.Schema)
        })
    }
    object Fields { const val FIELD_STRATEGIES = "$ID/fieldStrategies" }
}

interface SemanticMergeStrategy : MergeStrategy {
    companion object {
        const val ID = "tenshu/core/trait/mergeStrategy/semantic"
        val Schema: TraitSchema = trait(ID, parent = MergeStrategy.Schema)
    }
}

// FieldMergeBinding is a Composite carrying (fieldRef, strategy)
interface FieldMergeBinding : Composite {
    val fieldRef: FieldReference
    val strategy: FieldMergeStrategy   // sealed: LastWriteWins | Max | Min | SetUnion | Counter | KeyedMerge

    companion object {
        const val ID = "tenshu/core/mergeStrategy/binding"
        val Schema: CompositeSchema = composite(id = ID, fields = {
            reference(Fields.FIELD_REF, type = FieldReference.Schema)
            composite(Fields.STRATEGY, type = FieldMergeStrategy.Schema)
        })
    }
}
```

`FieldMergeStrategy` is a union schema (or sealed parent) over the six built-ins:

```kotlin
sealed interface FieldMergeStrategy : Composite {
    interface LastWriteWins : FieldMergeStrategy { /* ... */ }
    interface Max : FieldMergeStrategy { /* ... */ }
    interface Min : FieldMergeStrategy { /* ... */ }
    interface SetUnion : FieldMergeStrategy { /* ... */ }
    interface Counter : FieldMergeStrategy { /* ... */ }
    interface KeyedMerge : FieldMergeStrategy {
        val key: FieldReference
        val tiebreaker: KeyedMergeTiebreaker  // MaxBy(field) | MinBy(field) | LastBy(hlcField)
    }
}
```

### 3.2 DSL — `fieldLevelMerge` and `traitFor`

Two ways to declare. **Inline** at schema definition:

```kotlin
val Schema = composite(
    id = ID,
    traits = listOf(
        fieldLevelMerge {
            Fields.PATH using LastWriteWins
            Fields.SIZE using LastWriteWins
            Fields.LAST_MODIFIED using Max
            Fields.TAGS using SetUnion
            Fields.FILES using KeyedMerge(
                key = fieldRef(File.Field.PATH),
                tiebreaker = MaxBy(fieldRef(File.Field.LAST_MODIFIED)),
            )
        }
    ),
    fields = {
        string(Fields.PATH)
        int(Fields.SIZE)
        timestamp(Fields.LAST_MODIFIED)
        list(Fields.TAGS, of = TString.Schema)
        list(Fields.FILES, of = File.Schema)
    },
)
```

**External**, via `traitFor`:

```kotlin
// In a module configuration, after the schema is registered:
traitFor(File.Schema, fieldLevelMerge {
    Fields.PATH using LastWriteWins
    // ...
})
```

`traitFor` mutates the schema's traits at registration time. Useful when merge
strategies are declared in a different module from the schema (e.g. service-side
overrides).

`fieldLevelMerge` is a builder that produces a `PerFieldMergeStrategy` trait
instance; it's registered on the type's `traits` list like any other trait.

The `using` infix function maps a field id to a strategy. Strategies are constructed
by calling DSL helpers (`LastWriteWins`, `Max`, etc.) — no string magic, all typed.

### 3.3 `MergerRegistry` and dispatch

```kotlin
class MergerRegistry(
    private val schemaProvider: SchemaProvider,
    private val executionContextProvider: () -> ExecutionContext,
) {
    private val semanticMergers = mutableMapOf<String, SemanticMerger>()

    fun register(merger: SemanticMerger) { semanticMergers[merger.typeId] = merger }

    fun merge(typeId: String, existing: Data, incoming: Data): Data {
        val schema = schemaProvider.getSchema(typeId) as? CompositeSchema ?: return incoming
        val ctx = executionContextProvider()
        return when {
            schema.hasTrait(SemanticMergeStrategy.ID) -> {
                val merger = semanticMergers[typeId]
                    ?: error("Type $typeId declares SemanticMergeStrategy but no merger registered")
                merger.merge(ctx, existing, incoming)
            }
            else -> {
                // PerFieldMergeStrategy (explicit or synthesised)
                val perField = schema.findTrait<PerFieldMergeStrategy>()
                    ?: implicitPerFieldFor(schema)
                perFieldMerge(ctx, schema, perField, existing, incoming)
            }
        }
    }

    private fun implicitPerFieldFor(schema: CompositeSchema): PerFieldMergeStrategy =
        // synthesised: every field is LastWriteWins
        PerFieldMergeStrategy.create(
            fieldStrategies = schema.values.toList().map { field ->
                FieldMergeBinding.create(field.id, LastWriteWins)
            }
        )
}
```

### 3.4 `FolderMerger` after migration

Today's hand-rolled `FolderMerger` becomes:

```kotlin
// :file-service/.../Folder.kt
val Schema = composite(
    id = ID,
    parent = File.Schema,
    traits = listOf(
        fieldLevelMerge {
            Folder.Fields.FILES using KeyedMerge(
                key = fieldRef(File.Field.PATH),
                tiebreaker = MaxBy(fieldRef(File.Field.LAST_MODIFIED)),
            )
            // PATH, SIZE etc. inherit File's strategies via trait inheritance
        }
    ),
    fields = {
        list(Folder.Fields.FILES, of = File.Schema)
    },
)
```

The Kotlin `FolderMerger` class deletes. The merge logic is now declarative, in the
schema, where any consumer can read it.

---

## 4. Implementation Plan

### Phase 1 — Trait + DSL infrastructure

- New file `core/.../trait/MergeStrategy.kt` with the three traits (abstract +
  PerField + Semantic).
- New file `core/.../mergeStrategy/FieldMergeStrategy.kt` with the union of six
  built-in strategies.
- New file `core/.../mergeStrategy/MergeStrategyDsl.kt` with `fieldLevelMerge`,
  `traitFor`, `using`, and the strategy-constructor helpers.
- DTOs and serialisation in `:core-common/.../impl/mergeStrategy/`.
- Register in module configurations via the existing trait DSL.

### Phase 2 — `MergerRegistry` and dispatch

- Rename `SemanticMergerRegistry` → `MergerRegistry`.
- Implement `merge(...)` per §3.3, including implicit-PerField synthesis.
- Implement `perFieldMerge(...)` — iterate the field bindings, dispatch each strategy.
- Implement each strategy (`LastWriteWins`, `Max`, `Min`, `SetUnion`, `Counter`,
  `KeyedMerge`).
- Fail-fast: if a schema declares `SemanticMergeStrategy` and no merger is registered,
  the registry's `register*` methods report at startup.

### Phase 3 — Migrate `FolderMerger`

- Replace `Folder.Schema`'s registration with the trait declaration per §3.4.
- Delete `FolderMerger.kt`.
- Tests: `FolderMergeTest` should pass unchanged.

### Phase 4 — Migrate other types (audit)

- Audit every Composite for non-LWW field semantics. Probably very few hits beyond
  the existing `Folder`. Add `fieldLevelMerge` declarations where appropriate.

### Phase 5 — Cleanup

- If `SemanticMergeStrategy` has no consumers, mark the trait `@Deprecated` (kept
  available as escape hatch). Don't delete; the optionality is cheap.

---

## 5. Risks and Open Questions

- **Strategy serialisation size.** Per-field bindings add bytes to every schema
  serialisation. Probably a few dozen bytes per type with merge config; negligible
  in practice. Measure if it ever matters.

- **Cross-field merge logic.** A merge that depends on relationships *between*
  fields (e.g. "if A increased, B must reset") still needs `SemanticMergeStrategy`.
  No current Tenshu type needs this; sized as the "why we keep the escape hatch".

- **Tombstone interaction.** A `DeleteBlock` (per
  [`docs/sync/design.md`](../sync/design.md) §2.6) wins over any field-level merge —
  tombstones short-circuit the merger entirely. The merge strategy applies only to
  non-tombstone updates.

- **`KeyedMerge` and lazy fields.** If a `KeyedMerge` field is itself a list of
  entities with lazy properties, the merge happens at the entity-key level; lazy
  properties are merged by reference (the lazy-block id), not by content. Same
  principle as the rest of the platform's lazy/eager split.

---

## 6. Out of Scope

- **CRDT-level primitives** (OR-set with vector clocks, RGA for text, etc.).
  Placeholder trait `CRDTMergeStrategy` reserves the name; concrete CRDT support
  lands when there's a real-time-collab use case.

- **Block-level merge hints.** A merge hint carried in the block envelope ("this
  particular update is additive, not LWW") was discussed and explicitly out of
  scope; per-field strategy + semantic-merger escape hatch covers everything we
  need today.

- **Migration of legacy data.** This change is forward-only; existing `SyncBlock`s
  in the realm don't need re-encoding. The merge strategies apply at merge time,
  consuming whatever blocks are present.

---

## 7. Test Plan

- `mergerRegistry_synthesisesPerFieldForUnmarkedTypes` — type with no trait gets
  the implicit LWW strategy.
- `fieldLevelMerge_dslProducesValidTrait` — DSL output round-trips through
  serialisation.
- `traitFor_attachesToExistingSchema` — external attachment works and survives
  schema lookup.
- Per-strategy unit tests: `lastWriteWins_takesIncoming`,
  `max_picksLargerValue`, `setUnion_combinesUniqueElements`,
  `counter_addsBothDeltas`, `keyedMerge_withMaxByTiebreaker_picksHigherForCollision`.
- `folderMerger_migrationRegression` — `FolderMergeTest` passes after migrating
  `Folder.Schema` to trait-based merge with no Kotlin merger registered.
- `semanticMergeStrategy_failsRegistration_whenNoMergerProvided` — fail-fast
  invariant.

---

## 8. Sequencing

- **Pairs with operation-schema-typing.** Both add structured metadata to schema
  elements; doing them in the same release lets us land one consistent
  schemas-have-richer-metadata wave.
- **Independent of the landed synced device-management work**
  ([ADR 0007](../adr/0007-sync-device-management.md)) and `sync-relay-api.md`.
  Different layer.
- **Resolves an existing seam.** CLAUDE.md notes "the Folder semantic-merge work
  surfaced a real architectural seam between *lazy* and *materialised* data" — the
  `resolveListField` helper becomes the implementation of `KeyedMerge` for list
  fields, and the merger pattern stops being one-off per type.
