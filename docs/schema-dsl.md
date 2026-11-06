# Tenshu Schema DSL: Conventions & Best Practices

The Tenshu platform uses a custom Kotlin DSL for defining data schemas, operations, and services.
These definitions are self-describing and are used by both the client and proxy for validation,
serialization, and discovery.

## 1. Defining a Schema

Every Tenshu type (Entities, Snapshots, DTOs) MUST have a corresponding `Schema` defined within its
interface's `companion object`.

### Standard Pattern (`val Schema =`)

We standardize on using `val Schema = builder(...)` within the `companion object`.

* **Syntax**: `val Schema = composite(...)`
* **Rationale**:
    * **Robustness**: Avoids Kotlin resolution ambiguities and shadowing issues that often
      arise with `object ... by` delegation, especially when dealing with inherited members
      or common names like `Error`.
    * **Laziness**: The DSL builders (`composite`, `operation`, etc.) return `LazyComposite`
      objects. Using `val` is just as lazy as `by` delegation while being easier for the
      compiler to reason about.
    * **Conciseness**: Simplifies the declaration by removing the redundant interface name and
      the `by` keyword.

**Correct Example**:

```kotlin
interface File : Composite {
    companion object {
        const val ID = "tenshu/file"

        // Standard: simple val in companion
        val Schema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Field.PATH)
                int(Field.SIZE)
            }
        )
    }
}
```

**Avoid**:

```kotlin
// Avoid using delegation for schemas unless there is a specific structural need
object Schema : CompositeSchema by composite(...)
```

## 2. Field Definitions

Fields within a `composite` or `operation` block should use the semantic DSL helpers.

### Basic Types

* `string(id)`
* `int(id)`
* `boolean(id)`
* `timestamp(id)` (Unix Instant)
* `byteArray(id)`

### Collection Types

* **`list(id, of)`**: Defines a `TList`. The `of` parameter can be a `DataSchema` or a full `Type`.
* **`maybe(id, of)`**: Defines a `Maybe` (optional-like) value.

### Self-Describing Values

* **`genericData(id, isOptional = false)`**: a field whose concrete runtime `Data` type is **not
  known statically from the schema** — chosen at write time. The DB layer stores it as a
  self-describing `ObjectStore.DbValue.GenericValue` (concrete type id + payload) rather than
  inferring value-vs-reference from the field schema, so one field can carry a primitive
  (`TBoolean`, `TString`, …) **or** a composite interchangeably.

  Reach for it when the *shape* of a value varies per row and the schema genuinely cannot name it —
  the motivating case is `Setting.value` in `:preference-service`, where the type differs per
  preference. It is what lets a preference row be read through the schema with no per-app codec.

  ⚠ **It cannot hold an array.** A `genericData` slot stores an inline primitive or a composite
  *reference*, and nothing else. List-valued data must be byte-encoded into a `byteArray` instead —
  which is exactly what `StringListAdapter` does, and why it is **not** a `TList<TString>`. If you
  find yourself wanting a generic list, that is the boundary.

  ⚠ **Not the same as `composite(id, Data.Schema)`.** A plain composite-typed field (e.g.
  `DataBlockPayload.value`) stays on the ordinary composite-by-reference storage. `genericData` is
  a deliberately distinct kind, and the two are not interchangeable.

### Type Parameterization

For complex nested types, use the fluent `typeOf` builder:

```kotlin
list(id = "matrix", of = TList.typeOf(TInteger.Schema))
```

### Optional fields (`isOptional = true`)

The wire contract is `core-common/binary-protocol.md` §3.4, accepted permanently on 2026-10-29.
Three authoring rules follow from it:

1. ⚠⚠ **Never give null and empty different meanings on an optional field.** An optional string,
   byte array or list written empty **reads back as null**. If "none yet" must differ from "empty",
   make the field mandatory, or carry the distinction explicitly: a sentinel (`-1` = not counted, as
   on `MovementTrace.steps`) or a separate boolean.
2. **Position is free.** An optional field may sit anywhere in the composite, not only at the end.
3. ⚠ **Optional does not make a field addable later.** Adding one, even at the end, is a reshape and
   owes the House Rule 9 bump and `SchemaMigration`. On a composite used as an operation parameter it
   is blocked outright today ("Nested reshapes are not yet supported", `timeline-movement-capture.md`
   §16.3).

### Field Inheritance Invariant

`schema.values` always contains the **full merged field list**, including
fields inherited from `parent`. The `composite(...)` builder does the merge
at materialization time (`mergedValues = parent.values + this.fields`).

Consequence for consumers — **don't walk the parent chain yourself**:

```kotlin
// ✅ Correct — schema.values is already the full list.
private fun collectFields(schema: CompositeSchema): List<Field> =
    schema.values.toList()

// ❌ Wrong — double-counts every inherited field (since schema.values
//   already contains them).
private fun collectFields(schema: CompositeSchema): List<Field> {
    val parent = schema.parent as? CompositeSchema
    return (parent?.let { collectFields(it) } ?: emptyList()) + schema.values.toList()
}
```

This bit `DataFactory.collectFields` for a long time silently because
`CompositeDto.fieldMap` reads from `schema.values` directly and dropped the
duplicate positions. It surfaced when a parameterised composite with a
`typeVar` field in the parent hit one of those duplicate positions with a
non-`Data` Kotlin arg.

## 3. Operations

Operations use the `operation(...)` builder. The signature carries four kwargs that
fully describe what the operation is called on, what type abstractions it introduces,
what value parameters it takes, and what it returns. Design rationale + alternatives:
[ADR 0009](adr/0009-operation-schema-typing.md).

```kotlin
fun operation(
    id: String,
    parent: DataSchema = OperationSchema.Schema,
    receiver: Type? = null,                                              // what it dispatches on
    typeParams: MutableList<TypeParameterDecl>.() -> Unit = { },         // type variables this op introduces
    params: MutableList<Field>.() -> Unit = { },                         // value parameters
    traits: List<TraitSchema> = emptyList(),
    returnType: Type = Data.Schema.toType(),
): OperationSchema
```

### Receiver

`receiver` is a single `Type` (not a list) telling the platform what the operation
dispatches on. Operations never dispatch on multiple receivers; multi-arg dispatch
belongs in `params`. `null` means the operation is unattached — typically a top-level
service operation with no implicit caller.

### Type parameters + the `typeVar(...)` field type

A parameterised operation declares its type variables in `typeParams` and references
them by name (or by binder) in `receiver` / `returnType`:

```kotlin
interface Drop<T : Data> : Operation {
    companion object {
        const val ID = "tenshu/core/list/drop"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E", Data.Schema) },
            params = { int(Params.N) },
            returnType = listType("E"),
        )
    }
    object Params { const val N = "$ID/n" }
}

fun <T : Data> TList<T>.drop(n: Int): TList<T> {
    val operation = findOperation<Drop<T>>(Drop.ID)    // receiver derived from schema
    return operation.drop(n)
}
```

Two forms for referencing a declared variable:

- **String-based** (`listType("E")`, `typeVar("E")`) — usable anywhere.
- **Binder-based** (`listType(E)`, `typeVar(E)`) — `type(name, bound)` *returns* the
  `TypeParameterDecl`; capture it in a local val and reuse. Survives renames; compiler
  catches typos. **Recommended form** when the binder is in scope.

```kotlin
val Schema = operation(
    id = ID,
    typeParams = {
        val E = type("E")
        // … E is now usable below
    },
    receiver = listType(E),
    returnType = listType(E),
)
```

`typeVar(name)` produces a `TypeVariable` — a new `Type` subkind sibling to
`GenericType` and `UnionType`. It carries the variable name; bounds live on the
`TypeParameterDecl` in the operation's `typeParams`.

### Bounds + variance

* **Bounds default to `Data.Schema`.** `type("E")` means `E : Data`.
* **Declaration-site variance not supported today** (per ADR 0009 anchor decision 7);
  every `TypeParameterDecl` is invariant. Use-site `Variance.Covariant` etc. continues
  to work on `TypeParameter` as before.

### Substitution

The schema stores `TypeVariable` references unresolved. Consumers — the resolver, the
JS SDK schema-walker, future code generators — substitute at use sites that need it.
The schema is the *declaration*; substitution is the *instantiation*.

### Registration

`registerOperation(operation, factory)` is the single-overload form — `baseTypeId`
is derived from `schema.receiver`. The legacy `(baseTypeId, ...)` overload was
removed alongside the `OperationTypeProvider` marker in Phase 5
([ADR 0009](adr/0009-operation-schema-typing.md) anchor decision 5).
`RoRegistry.createRequestObject(type: Type, ...)` carries the construction-time
parameterisation so parameterised ROs (`ListRo`, `MaybeRo`) can extract their
`elementType: DataSchema` directly instead of mutating an `OperationTypeProvider` marker.

## 4. Implementation Registration

Once a schema is defined, it MUST be registered in the appropriate `ModuleConfiguration`.

* **Structural Registration**: Register the schema's existence.
  `composite(MyType.Schema)`
* **Implementation Registration**: Attach DTO and RO factories.
  `composite(MyType::class, MyType.Schema, ::MyTypeDto, ::MyTypeRo)`

## 5. Request DSL Extensions

To provide a discoverable and idiomatic API for users, every `Operation` or `Service` should
provide extension functions. These extensions bridge the gap between building an RO chain and
triggering its execution. The bridge is the **Interactor pattern**: design rationale +
alternatives in [ADR 0012](adr/0012-interactor-pattern.md).

### Location

DSL extension functions MUST be defined in the **same file** as the `Operation` interface they
trigger, but outside the interface itself.

### The "Color-Blind" Interface Rule

To keep core logic decoupled from the execution model:

1. **Operation methods return `RequestObject`, not `TResult`.** Chain construction is pure;
   no `suspend`, no execution, no `as` cast. Forcing the operation interface to commit to
   an execution shape would propagate `suspend` through pure composition (`.equalTo`,
   `.filter`, …) for the same reason an `IoOperation` would force every consumer to be
   `suspend`. The execution-mode commitment happens one layer later, at the DSL extension.
2. **DSL extensions commit to a mode.** The extension picks one of four typed Interactor
   shapes (`Sync` / `Task` / `Background` / `Stream`) and returns the right outer type
   (`TResult<T>` immediately / `suspend TResult<T>` / `Job` / `Flow<TResult<T>>`).

### The four execution modes

| Mode        | Operation trait                 | DSL extension shape        | Returns                                  |
|-------------|---------------------------------|----------------------------|------------------------------------------|
| Synchronous | (none — default)                | regular `fun`              | `TResult<T>` immediately                 |
| IO-bound    | `IoOperation`                   | `suspend fun`              | `TResult<T>` after `await()`             |
| Background  | `BackgroundOperation`           | regular `fun`              | `Job` (caller can cancel/observe)        |
| Stream      | `StreamOperation`               | `suspend fun`              | `Flow<TResult<T>>`                       |

The trait on the *operation schema* tells the platform which interactor to build; the DSL
extension picks the corresponding `as*Interactor()` and runs it.

### Non-Terminal Extensions (Builders)

Operations that return a new data structure for further composition (e.g., `Filter`, `Select`,
`Find`, `GetData`) should return the Tenshu type synchronously.

```kotlin
// In Find.kt
fun <T : Data> TList<T>.find(predicate: (T) -> TBoolean): Maybe<T> { ... }
```

### Terminal Extensions (Executors)

Terminal extensions pick the right `as*Interactor()` based on the operation's traits, then
call `.execute()` to produce the result. **Operation methods return `RequestObject`**, never
`TResult` or a casting-trap payload.

```kotlin
// Sync (e.g. equalTo) — no suspend, no IO; SyncInteractor under the hood.
fun <T : Data> Data.equalTo(other: T): TResult<TBoolean> {
    val operation = findOperation<EqualTo>(EqualTo.ID)
    return operation.equalTo(other).asSyncInteractor<TBoolean>().execute()
}

// IO-bound (e.g. Login) — operation marked `IoOperation`; TaskInteractor.
suspend fun IdentityService.login(email: String, password: String): TResult<Session> {
    val operation = findOperation<Login>(Login.ID)
    return operation.login(email, password).asTaskInteractor<Session>().execute()
}

// Background (e.g. StartPairing) — outlives the request; BackgroundInteractor returns a Job.
fun SyncService.startPairing(): Job {
    val operation = findOperation<StartPairing>(StartPairing.ID)
    return operation.startPairing().asBackgroundInteractor<Data?>().execute()
}

// Stream (e.g. notifications.observe) — emissions over time; Flow<TResult<T>>.
suspend fun <T : Data> TList<T>.flow(): Flow<TResult<TList<T>>> {
    val operation = findOperation<Stream>(Stream.ID)
    val interactor = operation.stream().asStreamInteractor<T>()
    return /* wrap interactor in Flow */
}
```

The four `as*Interactor()` extensions (`asSyncInteractor`, `asTaskInteractor`,
`asBackgroundInteractor`, `asStreamInteractor`) live in `:core-common`. They route through
`TContext.interactorProvider().getInteractor(ro)`, which inspects the terminal operation's
schema traits and returns the matching `Interactor<T>` subtype. Calling the wrong one (e.g.
`asSyncInteractor` on an `IoOperation`) is a fail-fast `error(...)` with an actionable
message.

### Rationale

This separation ensures that:

* **RO Construction is Pure**: Building a request chain never triggers IO or suspends.
* **Execution is Explicit**: The `suspend` modifier (or `Flow` / `Job` return) at the DSL
  layer clearly signals to the consumer that the call triggers evaluation.
* **One Pattern for Four Modes**: Every DSL extension follows the same shape — `findOperation`
  → build chain → `as*Interactor` → `execute()`. No casts to unrelated types, no `suspend`
  smuggled into the operation interface.

## 6. Type Parameterisation

Parameterised composites (`TList<E>`, `Maybe<T>`, `TPair<A, B>`, `TMap<K, V>`,
`TResult.Success<T>`, `StreamElement.Value<T>`, `Entity<T>`, …) declare their
type variables on the schema. Field types that depend on a type parameter
reference it via `typeVar("E")`. The rationale + alternatives lived in
[ADR 0005](adr/0005-composite-schema-typing.md); this section is the
authoring reference.

### Declaration

```kotlin
interface Some<out T : Data> : Maybe<T> {

    val value: T
        get() = getField(Fields.VALUE)

    companion object {
        const val ID = "tenshu/core/maybe/some"

        val Schema = composite(
            id = ID,
            parent = composite(Maybe.ID, typeParams = { type("E") }),
            typeParams = { type("E") },
            fields = {
                field(Fields.VALUE, typeVar("E"))
            }
        )
    }
}
```

Two pieces:

1. **`typeParams = { type("E") }`** introduces type variable `E` on the
   schema. The body of `type(...)` is the variable name; nested composites
   re-declare their own.
2. **`field(id, typeVar("E"))`** uses the variable as a field type.
   Replaces the older `composite(id)` / `list(id, of = Data.Schema)`
   placeholders that erased to `Data`.

### Inheritance

When a composite extends a parameterised parent, the child re-declares the
parent's parameterisation **by value** — the child's `typeParams` replaces
the parent's, it does not unify. Reference the parent schema with its own
`typeParams` block:

```kotlin
parent = composite(Maybe.ID, typeParams = { type("E") }),
typeParams = { type("E") },
```

The child's field declarations reference the child's own variable names.

### Parameterised fields via `typeOf(...).of(...)`

When a field's type is a parameterised composite of another type, use the
fluent builder:

```kotlin
// One typeVar:
field(Fields.PAIRS, typeOf(TList.Schema).of(typeVar("E")))

// Two typeVars (TPair-of-K-V inside a TList):
field(Fields.PAIRS, typeOf(TList.Schema).of(typeOf(TPair.Schema).of(typeVar("K"), typeVar("V"))))
```

This is how `TMap<K, V>.pairs` and similar field types are declared.

### Variance

All `TypeParameterDecl`s are **invariant** today. `Variance` already exists
on use-site `TypeParameter`; declaration-site variance is deferred — see
ADR 0005.

### Substitution

The schema stores `TypeVariable` references unresolved. Consumers — the
proxy validator (`checkReceiverCompatibility` in `EvaluateCallstack`),
field-access ROs (`MaybeRo`, `EntityRo`), and future code generators —
substitute via `substitute(type, bindings, defaultForUnbound)` in
`:core/schema/Type.kt`. When the caller can't supply a binding (typically
because runtime data lost its parameterisation), the substitution falls
back to `Data.Schema.toType()` — the historical erased behaviour.

## 7. Properties & Relations

This section is the authoring reference for the property-handle and relation system.
Design rationale + alternatives: [ADR 0013](adr/0013-property-authoring-unification.md)
(property authoring unification) and [ADR 0010](adr/0010-relation-system.md)
(relation system).

A **property** is a typed handle from a composite to a value
(`relation/Property.kt`) — the single source of truth across *declare / write /
read / project / sort*. Both kinds are declared as extension handles next to the
owning schema (`val File.Companion.Size: …`), so any module may attach its own
without the owner enumerating it (`File.Size`/`LastModifiedAt`/`Category`/`Data`/
`Preview` are the worked example).

| Kind | Declared (handle) | Registered | Read |
|---|---|---|---|
| **Static** — any `TValue`/`TConstant`: stored scalar (`File.Size`) **or** constant/singleton (`File.Category`) | `valueProperty(id, schema)` → `Property.Value<T>` | scalar: `valueProperty(fromId, handle) { factory }`; constant: `valueProperty(fromId, handle)` (no factory) | `entity.get(handle)` → the stored `TValue` (`.get()` for the raw scalar) |
| **Dynamic** — chain-computed, ± params (`File.Data`, `File.Preview`) | `dynamicProperty(id, receiver) { chain }` → `Property.Dynamic<T>` | `dynamicProperty(fromId, handle)` | `entity.get(handle)` (an unresolved OnDemand placeholder reads as `null`) |

Write via the `Entity.create(ctx, composite) { set(handle, raw) }` builder (static
handles; raw value encoded through the registered factory); project via
`relations(handle)`; sort via `list.sortBy(handle)`. The handle mints the wire
selector (`handle.selector(params)` → a `StaticPropertyRelation` /
`DynamicPropertyRelation`). `get` returns the stored `TValue`, `set` takes the raw
value — the read-cast / write-encode asymmetry that lets `valueProperty(id,
schema)` stay generic.

| Service-relation helper | What it declares | Resolves to |
|---|---|---|
| `serviceDataRelation(...)` | A service-relation id that resolves to realm data — single entity (with `dataId: EntityDataId`) or collection (`type` is `TList<E>`). Replaces the old `entityRelation` / `entityListRelation` pair. | Single: `database.getData(dataId, asEntity = true)`. Collection: `ArrayDatabaseReference` keyed by relation id. One resolver entry point: `ExecutionContext.resolveServiceData(service, relation)`. |
| `propertyRelation(...)` / `dynamicPropertyRelation(...)` | Lower-level relation-*schema* registration (e.g. `Folder.files`; a raw-schema dynamic relation). Takes a `ChainTemplate<T : Data, R : Data>` for the dynamic case. The `Property` handle DSL above is the preferred surface; reach for these only when registering a schema directly. | Direct field read / a chain materialised at first schema-materialisation (via `CallstackMaterializer`) — see [ADR 0010](adr/0010-relation-system.md). |

All relation composites — the property selectors **and** `ServiceDataRelation` —
are `Relation`s (a fieldless marker). `from`/`to` belong to `ServiceDataRelation`
specifically (a service→data binding); a property selector has a single reference
(± params), not a `from`/`to` pair. This mirrors the schema-descriptor side, where
`PropertyRelationSchema` is already a `RelationSchema`. See ADR 0013.

Two pieces of detail worth knowing about:

- **Receiver-type dispatch in dynamic chains is forbidden.** The chain is
  pre-materialised against a synthetic receiver RO of type `T`; a chain whose
  lambda inspects the runtime receiver's type fails registration. See
  ADR 0010 §"When to revisit" for the reopening criterion (no current use
  case wants per-receiver dispatch).
- **Call sites pass `Property` handles; the wire carries `*PropertyRelation`.**
  `relations(...)` in `.select { ... }` blocks takes `vararg handles: Property<*>`
  and calls `handle.selector(params)` to mint the wire selector — a
  `StaticPropertyRelation` or `DynamicPropertyRelation` (sealed under
  `PropertyRelation`, both real Composites declared via the schema DSL). The old
  `vararg: Any` + type-switch is gone; the call site is fully typed.

```kotlin
.select {
    relations(
        File.Category,                              // StaticPropertyRelation
        File.Data,                                  // DynamicPropertyRelation (no params)
        File.Preview(width = 100, height = 100),    // DynamicPropertyRelation (with params)
    )
}
```

`DynamicPropertyRelation` is content-bearing (carries the params); the schema
layer (`DynamicPropertyRelationSchema`) is identity-only. References continue to
be `TConstants`. Content-bearing call-site values live as Composites — that
distinction came out of the iteration captured in ADR 0010.

**Renames worth pinning** — `CallstackLinearizer` → `CallstackMaterializer`
(`linearize(...)` → `materialize(...)`), `CallStack<T>` → `ChainTemplate<T, R>`
(picks up a return type parameter — the `as Data` cast at every dynamic-relation
declaration is gone).

### Derived properties usable in predicates (`File.Name`)

A **non-deferred** `dynamicProperty` whose template is pure, synchronous chain
manipulation can be referenced inside a `search` / `filter` / `searchAll`
**predicate**, resolved per-row on the proxy — no persisted field, no wire/schema
migration. `File.Name` (the last path segment, derived from the only stored field
`File.path`) is the worked example:

```kotlin
private val fileName: Property.Dynamic<TString> = dynamicProperty<File, TString>(
    id = "${File.ID}/name", receiver = File.Schema,
) { path.substringAfterLast("/") }          // deferred = false (default)

// registered like any dynamic property:
dynamicProperty(fromId = File.ID, property = File.Name)
```

Two load-bearing facts make this work:

- **`deferred` is a projection-only flag.** It is read solely by
  `SelectImplementation` (a deferred property emits a `PendingDeferredProperty`
  marker instead of resolving inline). Every *non-projection* resolution —
  including a predicate scan — runs `resolveDynamicProperty →
  evaluateCallstack(allowDeferring = false)` and returns the concrete value
  regardless. So a **cheap, synchronous** template (`deferred = false`) resolves
  fine in a predicate; a `deferred = true`, IO-backed one (`File.Preview`) would
  *also* resolve there but pay its full IO cost per row — don't. Keep derived
  predicate-usable properties non-deferred.
- **A dynamic property needs a bare chain accessor to be referenced in a
  predicate.** `this.path` works because `path` is a stored schema field
  (`getField`); a dynamic property is not a field, so `getField` won't surface it,
  and the `relations(...)` projection selector is the wrong shape for a predicate
  step. Add a thin accessor that appends the reference directly (mirrors
  `Entity<T>.relation`):
  ```kotlin
  val File.name: TString get() = (this as RequestObject).let { ro ->
      ro.context.roProvider().createRequestObject(
          TString.Schema.toType(), ro.requestChain.addReference(File.Name.id), ro.context)
  }
  ```
  The id resolves to a `DynamicPropertyTypeReference` (registered at the highest
  weight), so the predicate callstack becomes
  `[DynamicPropertyReference(name), Contains(q)]` — the reference resolves inline,
  then the predicate op runs on the result.

The transform op the template needs must itself be **synchronous and
composable** — no `IoOperation` / `TerminalOperation` / stream traits — so it can
sit mid-chain (template leaf *and* followed by the predicate op). `substringAfterLast`
(`org.tenshu.core`) is authored exactly like `Contains`/`EqualTo` (plain schema +
`SyncOperationImplementation`); it is the general-purpose string verb `File.Name`
is built on. Cost note: this is still an O(n) linear scan (a derived-name predicate
never hits the exact-key fast path) — the win is that the proxy returns exactly the
matching rows, with no client-side re-filter.
