# Tenshu Binary Protocol Specification

This document defines the binary serialization format used by the Tenshu platform for IPC,
network communication, and persistence.

## 1. Core Principles

* **Polymorphic Roots**: Every top-level message starts with a 16-byte UUID identifying its Type ID.
* **Recursive Structure**: Composites and lists are serialized recursively.
* **Type-Aware Optimization**: If the context (schema) already defines the expected type, the
  16-byte Type ID tag is omitted to reduce overhead (e.g., in lists or specific composite fields).
* **Deterministic Union Dispatch**: When a field is a Union, the protocol reads a 16-byte tag and
  matches it against the union's member schemas to determine the concrete type.
* **Explicit Lengths**: Variable-length data (Strings, ByteArrays, Lists) are prefixed with an
  8-byte length header (Long).

---

## 2. Type-Aware Encoding Rules

To minimize wire overhead, the serializer and parser use the `expectedType` context from the
`DataSchema`:

### 2.1 Marker Schemas (Metadata)

Schemas representing metadata (Services, Relations, Fields, Traits) are treated as **Markers**. They
are **always** serialized as naked 16-byte UUIDs. They are never instantiated as composites.

### 2.2 Collections (TList & TMap)

* **Tagged (Default)**: If the expected type is `Data` or unknown, the collection starts with the
  `TList.ID` or `TMap.ID` (16 bytes).
* **Naked (Optimized)**: If the schema defines a field as `TList<T>`, the `TList.ID` tag is *
  *omitted**. The payload starts directly with the 8-byte length header.

### 2.3 Structural References

References to Operations or Composites (as opposed to the objects themselves) in polymorphic
contexts must be tagged with `Reference.ID` (16 bytes) followed by the 16-byte Target ID to
distinguish them from instance data.

---

## 3. Packet Layouts

### 3.1 Value Types (TString, TByteArray)
```text
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Length (8 bytes, Little Endian)               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Data (Length bytes, UTF-8/Binary)              |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 3.2 Composite Objects (Tagged)
```text
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                   Type UUID (16 bytes)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Field 1 Content (Value or Message)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Field 2 Content (Value or Message)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 3.3 Lists (TList<T>)
```text
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Length (8 bytes, Total Byte Size)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Element 1 (Optional Tag + Content)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                Element 2 (Optional Tag + Content)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### 3.4 Optional fields — the permanent contract

Every **optional** field carries an 8-byte length marker ahead of its value: `0` means absent; anything
else is the byte length of the value that follows. That holds for fixed-length values too: a present
optional `int` 0 is marker `8` plus 8 zero bytes, so it is never mistaken for absent. The
parser reads that marker wherever the field sits, so **an optional field may sit anywhere in a
composite** (`OptionalValueFieldRoundTripTest`).

Two consequences are **accepted permanently** rather than fixed (`protocol-improvements.md`, the two
sections marked *Decided*). Changing either reshapes the bytes of every stored row and every peer
message. That is a `TENSHU_STORAGE_FORMAT_VERSION` bump with a rewrite of every store, and nothing
needs the difference:

- ⚠⚠ **Null ≡ empty for an optional variable-length field.** An optional `TString`, `TByteArray` or
  list holding an empty value is written as the same marker `0` as null, and it **reads back as null**
  (`OptionalNullEqualsEmptyContractTest`). A mandatory field keeps `""` / `[]` distinct from absent,
  because it has no absent.
- ⚠ **Optional is not evolvable.** The parser also treats a field as absent when the buffer has run
  out, but only a top-level message ends the buffer; a composite nested in a list, an operation's
  params or a sync block never does. So appending an optional field is a reshape like any other
  (House Rule 9), and the shape baseline pins `?`.

---

## 4. Formal BCNF Specification

```text
<message>        ::= [<type_id>] <content>
<type_id>        ::= BYTE[16]

<content>        ::= <value> | <composite> | <list> | <marker>

<value>          ::= <fixed_value> | <variable_value>
<fixed_value>    ::= BYTE[N]  /* N defined by schema (e.g. 8 for Long) */
<variable_value> ::= <length> BYTE[length]

<composite>      ::= <field_payload>*
<field_payload>  ::= <message> | <variable_value> | <fixed_value>

<list>           ::= <length> <message>*
<marker>         ::= BYTE[16] /* Naked ID for Metadata */

<length>         ::= INT64 /* Little Endian */
```

---

## 5. Known Weaknesses & Evolution Roadmap

See [`docs/proposals/protocol-improvements.md`](../docs/proposals/protocol-improvements.md) for details on:

1. **Schema Revision Headers**: Adding versioning to polymorphic boundaries.
2. ~~**Presence Prefixes**: Solving the Null vs. Empty ambiguity for optionals.~~ Decided against,
   permanently (§3.4).
3. **Varint Lengths**: Reducing length header overhead.
4. **Monomorphic Lists**: Eliminating redundant Type IDs for homogeneous collections.
