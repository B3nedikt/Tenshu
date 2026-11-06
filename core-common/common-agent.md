# Tenshu Platform: Common Implementation & Serialization Conventions

This document captures the conventions for DTOs and binary serialization.

## 1. Pattern Reference

See **[How-To: Adding Composites and Operations](../docs/how-to/add-composites-and-operations.md)**
for conventions on defining DTOs and Operation Implementations.

## 2. Binary Serialization & Parsing

* **Top-level Header**: Use `parser.parse(bytes)` for entire serialized tasks (which include a type
  UUID header). Use `parseArray(bytes)` only for raw element sequences.
* **Registration Strategy**: Use granular registration calls (`registerComposite`, `registerValue`,
  `registerOperation`, etc.) in the service's `moduleConfiguration`.
* **Order Independence**: Registration order in `ModuleConfiguration` does NOT matter. The system
  uses a smart weighting mechanism to ensure the most specific reference type (e.g.,
  `PropertyTypeReferenceDto` vs `TypeReferenceDto`) is preserved regardless of when it's registered.
* **Unique Identifiers**: Ensure distinct IDs for similar types (e.g. the relation schemas —
  `PropertyRelationSchemaDto` vs `ServiceSchemaDto`) to avoid collision in the hash mapping.
* **Field Serialization Order**: ⚠ There is exactly one `Field` type
  (`:core/schema/Field.kt` — an `id`, a `type` and `isOptional`). The categories below classify a
  field **by its `type`**; they are not class names, and `ValueField` / `ConstantField` /
  `ParameterizedField` / `CompositeField` do not exist as Kotlin types.
    * For `CompositeSchema` and `OperationSchema`, the binary format MUST first write lengths for
      specific fields, followed by all field values in sequence.
    * **Lengths MUST be prepended for "lengthed" fields** — those whose encoded size is not fixed:
        * Any field marked as `isOptional = true`.
        * A variable-length value type (`TString`, `TByteArray`).
        * A parameterized type (`TList`, `TMap`).
        * A composite (any object following a schema).
    * **Lengths MUST NOT be written for**:
        * A constant (always 16 bytes).
        * A fixed-length value type (`TInteger` is 8 bytes).
    * **Trailing Field Optimization**: If a non-optional lengthed field is the **very last field**
      of a composite, and that composite is at the end of the message (or its parent is optimized),
      its length header is omitted. Its length is inferred from the remaining bytes.
* **Array Serialization**:
    * Untyped arrays (`TList<Data>`) MUST include the type ID for each element.
    * Typed arrays (`TList<T>`) omit the type ID for elements if they match the expected type
      defined in the schema.
    * ⚠ The collection type is `TList`, not `TenshuList` — that name was renamed out of the
      codebase and resolves to nothing.

## 3. Binary Protocol Specification

The Tenshu platform uses a custom, type-aware binary serialization format for all out-of-process
communication.

**For the formal specification, packet layouts, and optimization rules,
see [Binary Protocol Specification](binary-protocol.md).**

## 4. Union Types & Virtual Sum-Types

The platform supports formal sum-types (Unions) through two primary mechanisms: `UnionType` (for
generic variants) and `UnionSchema` (for named dispatcher schemas).

### `UnionType` (Generic Sum-Type)

Used primarily in field definitions where a field can contain multiple distinct types (e.g.,
selectors in a `Select` operation).

* **Definition**: Created using `unionType(type1, type2, ...)`.
* **Behavior**: During serialization, if the member types are distinct (e.g., different Services or
  Relations), the 16-byte `Reference.ID` prefix is omitted, writing only the literal 16-byte tag.
* **Dispatch**: `BinaryParser` reads 16 bytes and checks them against the union's member list using
  `isSubtypeOf` to determine the concrete type.

### `UnionSchema` (Virtual Sum-Type)

Used to define a named `Composite` that represents a choice between multiple concrete schemas
(e.g., `TResult` is a union of `Success` and `Failure`).

* **Virtual Nature**: A `UnionSchema`'s own ID (`TResult.ID`) is **never** written to the binary
  stream. Instead, objects are serialized as their concrete subtypes (`Success` or `Failure`).
* **Deterministic Dispatch**: When the parser expects a `UnionSchema`, it uses the 16-byte tag to
  dispatch to the correct member schema.
* **Generic Propagation**: `UnionSchema` supports propagating generic parameters from the union
  contract to the members. For example, `TResult<File>` propagates `File` to the `Success` member,
  allowing its `value` field to be parsed correctly.

### reified `parse<T>` API

The `BinaryParser` provides a reified extension for ergonomic parsing:

```kotlin
val result: TResult<File> = parser.parse(responseBytes)
```

This API automatically resolves the reified type to a Tenshu `Type` and passes it as the
`expectedType` to the parser, enabling all optimized "naked" collection and union dispatch
behaviors.
