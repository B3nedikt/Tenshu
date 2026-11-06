# Union Type System: Implementation Plan

This document outlines the strategy for introducing formal Union Types (Sum Types) to the Tenshu
platform. This refactoring aims to solve protocol ambiguities in callstacks and selectors while
enabling more expressive DSLs.

## 1. Phase 1: "Union Reality" Immediate Fix & Type-Aware Parsing

**Goal**: Restore build stability and introduce deterministic parsing by passing expected type
information.

*   [x] **Parser Hardening**: Update `BinaryParser.parseData` to check if a 16-byte UUID refers to a
    `ServiceSchema` or `RelationSchema`. If so, treat it as a **Literal Reference** (16 bytes)
    instead of a Polymorphic Root (32 bytes).
*   [x] **Serializer Hardening**: Update `LinearSerializer.serialize` to skip the `Reference.ID`
    prefix when serializing references to Service and Relation schemas in a polymorphic context.
*   [x] **Test Alignment**: Revert `LegacyFileServiceBinaryParserTest` callstack elements from
    32-byte `polyRef` back to 16-byte `idToUuid` literals.
*   [ ] **Expected-Type Parsing**:
    * Refactor `BinaryParser.parseData` to accept `expectedType: Type?`.
    * If `expectedType` is a `GenericType` (e.g., `TList<T>`), the parser should expect the item
      format directly (Length + Elements) and omit the `TList.ID` tag if it was already consumed by
      the caller.
    * Rename `parseArray` to `parseCallstack` to clearly mark it as the legacy/transport-specific
      entry point that handles explicit `TList.ID` tagging.
*   [ ] **Type-Aware Serialization**:
    * Update `LinearSerializer.serialize` to accept `expectedType: Type?`.
    * Omit the `TMap.ID` tag when serializing a field defined as a Map, aligning it with the
      existing `TList` behavior.

## 2. Phase 2: Formal Type System Expansion

**Goal**: Replace incidental logic with first-class `UnionType` and `GenericType` definitions in
`:core`.

*   [x] **Hierarchy Refactoring**:
    * `Type` (Root Interface)
    * `GenericType` (Base + Parameters)
    * `UnionType` (Sum of Types)
*   [x] **UnionSchema (Virtual Unions)**:
    * Introduce `UnionSchema : CompositeSchema` to represent a named sum-type (e.g., `TResult`).
    * Virtual behavior: A `UnionSchema.ID` is never written to the wire. Instead, the parser uses
      the member list to dispatch to the correct concrete DTO.
*   [x] **Schema DSL Enhancement**:
    * `unionType(...)` for generic sum types.
    * `unionSchema(...)` for named sum types.
*   [x] **Registration**: Add `UnionTypeDto`, `GenericTypeDto`, and `UnionSchemaDto` to
    `CoreParserConfiguration`.

## 3. Phase 3: Ambiguity Resolution via Deterministic Dispatch

**Goal**: Use the new type definitions to make the binary protocol deterministic and compact.

*   [x] **Union Member Dispatch**:
    * When `BinaryParser` encounters a `UnionType` or a `Type` with a `UnionSchema`, it reads the
      next 16-byte tag.
    * It then checks the tag against the union's member schemas (using `isSubtypeOf`).
    * If a match is found, it recursively parses the data using the matching member's schema.
*   [x] **Selector Refactoring**: Update `Select.kt` to use a `UnionType` for selectors instead of a
    base interface.
*   [x] **TResult Refactoring**: Update `TResult` to use `UnionSchema`, making `Success` and
    `Failure` first-class citizens of the result sum-type.

## 4. Derived Client DSL (Optional/Future)

**Goal**: Use schema metadata to generate type-safe builder DSLs.

*   [ ] **Schema-Driven Builders**: Create a mechanism where `select { ... }` blocks use the generic
    type `T` to restrict the available `fields()` and `relations()` based on the schema of `T`.
