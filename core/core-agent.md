# Tenshu Platform: Core Type System & Schema Conventions

This document captures the conventions for defining and instantiating data types in the Tenshu
platform.

## 1. Data Hierarchy & Type System

* **`Data` as Root**: `Data` is the absolute root of the Tenshu data hierarchy, analogous to `Any`
  in Kotlin. Every piece of data in the system (Composites, Values, Arrays, Constants) implements
  `Data`.
* **Relationship between Data and Schema**:
    * Every `Data` instance has a `schema: DataSchema`.
    * Every `Composite` instance (which is `Data`) has a `schema: CompositeSchema`.
    * **Circular Relationship**: `DataSchema` itself is a `Composite`. This allows schemas to be
      treated as data (e.g., serialized, inspected).
* **Schema Hierarchy**:
    * `DataSchema` is the root interface for all schemas.
    * `CompositeSchema` extends `DataSchema` and adds `values` (fields) and `traits`.
    * `ValueSchema`, `OperationSchema`, `ServiceSchema`, and `ConstantSchema` all eventually derive
      from `DataSchema` (often via `CompositeSchema`).
* **The Root Schema (`Data.Schema`)**:
    * `Data.Schema` is the only schema with no `parent`.
    * In `DataSchema.kt`, the `parent` property uses `getOptionalField(Fields.PARENT)` to safely
      return `null` for the root `Data.Schema`.
* **Field Optionality**:
    * `Field` defines the schema-level metadata for a property.
    * `isOptional` resides on the `Field` object itself.
    * A `Field` must always have a non-null `Type`.
    * The fallback/default type for a field is `Data.Schema`.
* **Value & Constant Schemas**:
    * `ValueSchema` and `ConstantSchema` are specialized schemas that do not define their own
      fields.
    * `ValueSchema` relies on its hierarchy (`parent`) to determine its underlying primitive type (
      String, Integer, etc.) and its binary length.
* **Naming**: The platform uses `TString` and `TInteger` for its core value types.
* **Recursive Typing**: The `Type` object (`Type.Schema`) contains a `baseSchema` (the structural
  definition) and `params` (for generics/parameterized types like `TenshuList<T>`).

## 2. DSL Schema Definition

**Convention**: Always use the DSL from `Dsl.kt` (`composite`, `operation`, `value`, `constant`,
`serviceSchema`, `propertyRelation`, `dynamicPropertyRelation`, `serviceRelation`, `entityRelation`,
`entityListRelation`) to define schemas. NEVER use manual `object : CompositeSchema { ... }` or
similar implementations.

* **Field Definition**: Fields are now `Composite` objects defined via `Field.create`. They have a
  `type: Type` which supports full generic type information (e.g., `List<String>`).
* **Binary Serialization**: Field lengths are no longer stored in the `Field` object but are instead
  inferred by the `BinaryParser` based on the [baseSchema] (e.g., `Integer` is 8 bytes, `Constant`
  is 16).
* **Lazy Initialization**: The DSL provides `LazyComposite` which allows schemas to be materialized
  only when needed by a `TContext`. It implements `Composite` and delegates to a materialized
  instance once initialized.
* **Consistency**: Using the DSL ensures that common behaviors (like trait merging from parents or
  hierarchy resolution) are handled automatically.

## 3. Pattern Reference

See **[How-To: Adding Composites and Operations](../docs/how-to/add-composites-and-operations.md)**
for conventions on defining interfaces, factory methods, DTOs, ROs, and implementations.

## 4. Entity Creation Pattern

Use the `Entity.create` companion method to promote any `Composite` to an `Entity` or to create one
from raw fields.

* **Nesting Support**: `Entity.create` allows nesting calls for a DSL-like feel.

*Example*:

```kotlin
val entity = Entity.create<File>(
  context = context,
  composite = File.create(context = context, path = "/test.txt", size = 100, lastModified = now),
    properties = mapOf(
        File.Relation.CATEGORY to FileCategory.Image
    )
)
```

## 5. Identity & DataIds

* **`DataIdProvider`**: This trait is reserved for `Composite` entities only. It MUST NOT be used on
  `Service` schemas.
* **Stable Identity**: Use `getDataIdHash()` for stable, cross-platform identity comparisons.
* **Reference Types**:
    * **`EntityFieldDataId`**: Identifies data (an entity or a collection) whose identity is
      relative to a parent entity instance. It represents a field or relation on that instance.
    * **`RelationDataId`**: Identifies a collection of entities reachable via a unique relation that
      is owned by a specific service, not an entity instance.

## 6. Trait & Requirement Schema Convention

Traits and their requirements follow a specific schema pattern to ensure consistency and correct
hierarchy resolution.

* **Dedicated Schema Interfaces**: Every trait requirement (e.g., `GetStringDataId`) should have a
  corresponding schema interface (e.g., `ValueTraitRequirementSchema`).
* **Schema Property**: The requirement interface must override `schema` to return its specific
  schema object.
* **ID Constants**: Requirement IDs and their field IDs should be defined as constants within the
  interface or a dedicated `Fields` object.

## 7. DSL Anonymous Object Convention

When defining specialized traits or requirements in the DSL (e.g., via `stringDataId` helper), the
anonymous objects created should implement the relevant marker interface.

* **Marker Implementation**: The anonymous object should implement `TraitRequirementSchema` or
  `ValueTraitRequirementSchema`.
* **Parameter Collision**: DO NOT have these anonymous objects implement the `Operation` interface
  directly if they define a `params` property (which is a `TList<Field>`), as this will conflict
  with
  the `Operation.params` (which is a `Map`). Use the marker interfaces instead.

## 8. Entity vs. DTO (Snapshot)

The platform distinguishes between **Entities** and **Data Snapshots (DTOs)**:

* **DTO (Snapshot)**: a plain `Composite` containing only its own schema fields (e.g. `path`,
  `size`, `lastModified` for a `File`). Returned when callers just need the data itself.
* **Entity**: a `Composite` that *wraps* another `Composite`. Stores a subset of the wrapped
  object's fields plus a `properties` map for dynamically-fetched extras (e.g. `preview`, `data`,
  `category` for a `File`). An `Entity<File>` is not a different kind of `File` — it is a
  platform-managed wrapper that can carry additional information about the same underlying object.

`isEntity` is a property of a **schema or list definition**, not of a type. A `File` can appear as
a plain DTO in one context and as `Entity<File>` in another:

* **Heuristic**: whether items in a list are loaded as entities is driven by the schema's return
  type. `ExecutionContext` sets `isEntity = true` if and only if the item type defined in the
  schema is a subtype of `Entity.ID`.
* **The `Select` Gateway**: the `select` operation is the explicit gateway to entities. Its
  schema declares a return type of `TList<Entity<Data>>`.
* **Preserving Typed DTOs**: snapshots (like `Notification` subclasses) are declared as base
  interfaces (e.g. `TList<Notification>`) so they load as specialized DTO subclasses, preserving
  their typed interfaces and logic.
* **Reference Consistency**: when manually constructing an `ArrayDatabaseReference` in an
  `OperationImplementation`, the `isEntity` flag MUST match the schema's item type. Example: the
  `FILES` list uses `isEntity = false` because its schema returns `TList<File>` — items are plain
  DTOs. A mismatched flag will cause the database to miss updates or fail lookups.

## 9. Identity Rooting

To ensure database consistency and stable hashes across polymorphic hierarchies (like the File
System):

* **Identity Root**: all entities within a shared identity space MUST use a unified "Identity
  Root" type ID for their `DataId`s.
* **Example**: both `File` and `Folder` use `File.ID` when constructing their `DataId`s. This
  prevents hash mismatches when looking up the same path via different polymorphic types.
* **Implementation**: manual `DataId` creation in operations (e.g. `CreateFolder`) MUST adhere to
  the identity root defined for that service.

## 10. Self-Describing Type System (Bootstrap)

The Tenshu platform uses a self-describing type system where schemas are themselves `Composite`
objects. This creates a "Bootstrap Problem" during system initialization.

### The Bootstrap Problem

A `Composite` needs its `schema` to resolve fields. But the `schema` is also a `Composite`, so it
needs *its* schema to resolve its own `values`. If not handled carefully, this causes infinite
recursion (`StackOverflowError`) during the materialization of core schemas like `DataSchema`,
`CompositeSchema`, or `OperationSchema`.

### Architectural Rules for Schemas

1. **Fast-Path Metadata**: `LazyComposite` (in `:core`) carries `staticId` and `staticParent`
   properties. Core metadata lookups for `SCHEMA_ID` and `PARENT` MUST use these static values
   to bypass full materialization and break recursion.
2. **Index-Based Schema DTOs**: Subclasses of `SchemaDto` (like `OperationSchemaDto`,
   `CompositeSchemaDto` or `TraitRequirementSchemaDto` — there is no `TraitSchemaDto`; all of
   them live in `:core-common/impl/schema/SchemaDto.kt`) MUST override `getOptionalField` to
   use direct index access to their
   internal `fieldValues` array. They MUST NOT use the dynamic `fieldMap` for core schema
   properties (`id`, `parent`, `values`, `traits`, `params`, etc.).
3. **Specific Type IDs in DSL**: DSL methods (`operation`, `trait`, `stringDataId`) MUST use the
   most specific Type ID when creating their internal representation (e.g., `OperationSchema.ID`
   instead of `CompositeSchema.ID`). This ensures the `DataFactory` creates the correct
   specialized DTO with the necessary index-based overrides.
4. **Registration Parity**: Every specialized schema DTO MUST be registered in
   `CoreParserConfiguration.kt` against its corresponding interface and schema.

### `LazyComposite` is Stateless; `SchemaRegistry` Owns Per-Context Caching

`LazyComposite` (the base class for schema definitions held in companion-object `val`s) is
**stateless**. It does NOT cache its materialised result on the instance — that would leak the
first proxy's `TContext` into every subsequent caller's view of the schema in a multi-proxy
setup (e.g. `MultiProxySyncIntegrationTest`).

Per-context caching is the responsibility of `SchemaRegistry.materializationCache` (a
per-`TContext` `Map<LazyDataCreator, Composite>`). The canonical way to obtain a fully
materialised schema is **`schemaRegistry.getOrCreate(schema)`**, NOT
`schema.materialize(context)` directly.

Concretely:

* `LazyComposite.getField(reference)` reads only from a pre-baked `lazyFields` map populated by
  the DSL builder (`composite(...)`, `operation(...)`, `trait(...)`, etc.). If a requested field
  isn't in the map, it errors with an actionable message rather than silently falling back to
  on-instance materialisation.
* The two remaining direct callers of `LazyComposite.materialize(context)`
  (`TraitResolverImpl.kt` lines 47, 78) are tracked in
  [`docs/tech-debt.md`](../docs/tech-debt.md) §"`TraitResolverImpl` Direct `materialize()`
  Bypasses Per-Context Cache" — those bypass the registry cache and re-materialise on every
  call.
* Adding a field to a `*Schema` interface? The matching DSL builder's `lazyFields` map MUST
  be updated, otherwise callers will hit the new error at runtime. A startup-time validation
  is sized in [`docs/tech-debt.md`](../docs/tech-debt.md) §"Lazy-Fields Map Completeness as a
  Contract".

### Schemas as Composites

Schemas are always created using the generic `context.create` or `dataFactory.createComposite`
methods. Specialized "createSchema" methods in the `SchemaRegistry` are prohibited. The structure
of a schema is defined by its "schema of schema":

* `CompositeSchema` is described by `CompositeSchema.Schema`.
* `OperationSchema` is described by `OperationSchema.Schema`.
* `TraitSchema` is described by `Trait.Schema`.
* `ReferenceSchema` is described by `ReferenceSchema.Schema`.
