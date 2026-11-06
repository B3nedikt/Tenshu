# Tenshu Binary Protocol: Weaknesses & Improvement Plan

**Status:** Analysis + proposed fixes. **No code, and none scheduled** — every item here implies a
wire-format change, so each is a coordinated producer/consumer migration rather than a refactor.
Treat it as a catalogue of known weaknesses to consult before touching `LinearSerializer` /
`BinaryParser`, not a plan anyone is executing. ⚠ The schema-evolution item is the exception:
that problem was solved separately — see `core-schema-evolution.md`.

This document captures known weaknesses in the Tenshu binary protocol (the
wire format produced by `LinearSerializer` / `BinaryParser`, specified in
`core-common/binary-protocol.md`) and proposes concrete remediations. It
complements `tech-debt.md` but is kept separate because each item here
implies a wire-format change — i.e. a coordinated producer/consumer
migration, not a local code refactor.

**Scope**: weaknesses in the *protocol design* itself. Inaccuracies in the
spec doc (endianness, list-length units, composite field framing, etc.) are
already tracked in review notes and should be fixed in-place in
`core-common/binary-protocol.md` before any of the changes below are
considered.

**Relevant files**:

- `core-common/src/commonMain/kotlin/org/tenshu/core/common/parser/LinearSerializer.kt`
- `core-common/src/commonMain/kotlin/org/tenshu/core/common/parser/BinaryParser.kt`
- `core-common/src/commonMain/kotlin/org/tenshu/core/common/parser/BinarySerializer.kt`
- `core-common/src/commonMain/kotlin/org/tenshu/core/common/util/LongUtil.kt`
- `core-common/binary-protocol.md`

---

## No Schema Evolution Story

### Problem

Field order on the wire is determined entirely by `CompositeSchema.values`.
There are no field tags, no wire types, and no self-describing per-field
lengths that let a reader skip unknown content. Every schema change
(adding, removing, or reordering a field) is a hard break: a reader using
a new schema against a payload produced with an older schema will
misinterpret bytes silently — drifting length headers, wrong type
identification at the next polymorphic boundary, and eventual "Buffer
overflow" errors far from the actual mismatch, or silent garbage.

Compare with Protobuf (numbered tags + wire types), Avro (schema-on-read
with explicit resolution rules), or FlatBuffers (vtable-based field
access) — all three survive schema drift. This protocol has none of
those mechanisms. The 16-byte type UUID at the root identifies the
schema's *identity* but not its *revision*, so a reader cannot even
detect the mismatch.

### Consequence

- Safe only when producer and consumer share exact schema versions. Both
  current primary uses (persisted DB objects, network IPC) fail that test
  as soon as schemas evolve across versions.
- Adding a single optional field in the middle of an existing schema
  silently corrupts all previously-written payloads.
- Persisted data from an older binary becomes unreadable after any schema
  change, with no migration path short of rewriting every record under
  the old schema first.

### Proposed Fix

Two complementary moves:

**1. Add a schema revision to the polymorphic header.** Extend the
top-level (and polymorphic-field) encoding from `UUID[16]` to
`UUID[16] || revision: INT16` (18 bytes total). Receivers compare the
revision against the locally-registered schema and either dispatch to a
revision-aware parser or fail explicitly. Cost: 2 bytes per polymorphic
boundary. Enables: all other evolution mechanisms below.

**2. Make variable-length fields skippable by unknown readers.** The
per-field encoding currently loses information about length for fixed
composite and constant fields — you can't skip past them without the
schema. Introduce a wire-type nibble at the start of each field payload
in a composite:

- `0x0`: value (has its own length prefix, skippable)
- `0x1`: fixed-width constant (skippable — width derivable from wire
  type, or length-tagged)
- `0x2`: polymorphic message (has its own header + known per-type
  framing — already skippable via recursive parse-and-discard)

The nibble adds 4 bits per field (round up to 1 byte in practice), but
it lets a reader with a newer-schema understanding skip unknown fields
produced by an older writer. Combined with the schema revision, this
gives forward-compatible evolution: readers treat unknown fields as
opaque bytes and either drop them or round-trip them.

**Migration path**: gate the new header on a top-level version flag (see
"No Framing or Integrity" below). Old readers see a new-format payload
and fail loudly; new readers accept both.

---

## Null vs. Empty Ambiguity for Variable-Length Optionals

> **Decided 2026-10-29: accepted permanently, not fixed** (`core-common/binary-protocol.md` §3.4;
> authoring rule in `docs/schema-dsl.md`, "Optional fields"). ⚠ The *Problem* below is stale in one
> respect: the parser now reads the marker as **null**, not as an empty value
> (`OptionalNullEqualsEmptyContractTest`). The decision was taken before the production reset, the
> last point where a core wire change was cheap. Nothing needs the difference, and a schema that does
> carries it explicitly (a sentinel or a boolean).

### Problem

`LinearSerializer.kt:95–102` handles optional null fields by emitting
`Token.IntegerToken(0)` — an 8-byte zero length header with no payload.
A variable-length optional field whose value is legitimately empty
(empty `TString`, empty `TByteArray`, empty `TList`) serializes to
**exactly the same bytes**: 8-byte zero length + zero bytes.

The parser cannot distinguish the two cases. Both decode to a zero-length
value, which the factory then materialises as an empty value — never as
null.

### Consequence

Any schema that treats `null` and `""` / `[]` as semantically distinct
loses that distinction at the wire boundary. Writing `null` and reading
back `""` is a silent type-coercion the parser has no way to detect.

### Proposed Fix

Replace the zero-length placeholder with a presence sentinel. Two cheap
options:

- **Sentinel length `-1`** for null (still fits in INT64, no wire-width
  change). Reserve `0` for the legitimately-empty case and `>= 0` for
  non-empty. Parser reads the length first, if it's `-1` it records the
  field as null and advances; otherwise reads `length` payload bytes.
- **One-byte presence prefix** before variable-length optionals: `0x00`
  = null (no length/payload follows), `0x01` = present (length + payload
  follow as today). Slightly more overhead for present values but
  trivially unambiguous and symmetric with fixed-length optionals if
  they're unified on the same prefix.

Either fix requires a coordinated producer/consumer rollout because the
wire format shifts by 1–8 bytes per affected field. Combine with the
schema-revision work above so the change can be gated by revision.

---

## Optional Fields Only Elidable When Trailing

> **Decided 2026-10-29: accepted permanently** (`core-common/binary-protocol.md` §3.4). ⚠ Partly
> overtaken: every optional field carries the 8-byte marker and the parser reads it
> wherever the field sits, so a *middle* optional is safe (`OptionalValueFieldRoundTripTest`). What
> stays true is that the buffer-end elision only ever helps a top-level message. Appending an optional
> field is still a reshape (House Rule 9), which the presence prefix proposed here would not have
> changed either.

### Problem

Parser-side optional detection (`BinaryParser.kt:136–138`) is:

```kotlin
if (field.isOptional.get() && currentPosition >= data.size) {
    return@forEach
}
```

That is, an optional field is treated as absent only when the buffer
has run out. Combined with the variable-length zero-header "placeholder"
described in the previous section, this means:

- Fixed-length optional nulls can only be elided if they are the **last
  field(s)** of the composite.
- Variable-length optional nulls are never actually elided — they always
  cost 8 bytes of zero header.

The serializer reinforces the trailing-only assumption with
`hasTrailingOptional()` (`LinearSerializer.kt:77–79`), which fires only
for schemas with **exactly one** optional field. Multi-field schemas
with a trailing optional don't get the shortcut.

### Consequence

- The protocol's "optionality" is really "trailing optionality". New
  schema fields must be appended at the end; inserting an optional in
  the middle is unsafe.
- `hasTrailingOptional()` is load-bearing for parser correctness but is
  narrow in a way that doesn't match the stated contract in
  `binary-protocol.md` §4.4.
- Storage overhead for variable-length optional nulls is 8 bytes per
  field regardless of position.

### Proposed Fix

The presence-prefix approach from the previous section solves this too.
A 1-byte `0x00` (absent) / `0x01` (present) prefix per optional field
makes position irrelevant: optionals can appear anywhere in a composite,
the parser reads the prefix, and either skips or reads the payload
based on that bit. `hasTrailingOptional()` and the 8-byte null
placeholder both disappear.

If the overhead of a per-field presence byte is unacceptable, pack the
presence bits into a bitmask header at the start of the composite — one
bit per optional field, rounded up to bytes. A composite with ≤8
optional fields pays 1 byte total instead of N.

---

## List Elements Always Repeat Their Type UUID

### Problem

In `LinearSerializer.serializeList` (`LinearSerializer.kt:146–163`),
every element is recursively serialized via `serialize(value, isTopLevel
= false)`, which emits the element's 16-byte type UUID as its first
bytes. For a homogeneous list (the common case), this is 16 bytes of
redundancy per element.

`List<TInteger>` of 1000 elements: 16 (list UUID) + 8 (length header) +
1000 × (16 UUID + 8 value) = 24 KB, of which 16 KB is type UUIDs.
Theoretical minimum for the values alone is 8 KB.

### Consequence

Lists are the dominant wire-overhead contributor. For RPC payloads or
streaming events that push collections frequently, this is 2–3× larger
than necessary.

### Proposed Fix

Two variants, pick one based on how much heterogeneity actually appears
in practice:

**Variant A — Monomorphic list header.** Extend the list header from
`UUID[16] || length: INT64` to `UUID[16] || elementType: UUID[16] ||
length: INT64`. Elements are serialized without their own UUID prefix
when `elementType` is non-zero; a zero elementType preserves the
polymorphic case. Saves 16 bytes per element at a one-time 16-byte cost
per list.

**Variant B — Element-type factoring with a flag bit.** Use the high
bit of the length INT64 as a "monomorphic" flag. If set, a single
element-type UUID follows; elements omit their own UUIDs. If clear, the
current polymorphic encoding applies.

Variant A is simpler to implement and reason about; variant B squeezes
out the 16-byte header cost for tiny lists. Either requires the schema
revision gating from the first section.

---

## Always-8-Byte Length Prefixes

### Problem

Every variable-length field, string, byte array, and list carries an
8-byte (INT64) length prefix. In practice, the parser converts each
length to `Int` anyway (`BinaryParser.kt:77, 144, 248`), so the protocol
cannot actually represent anything longer than `Int.MAX_VALUE` (~2 GB)
— the extra 4 bytes are dead weight.

Most strings in real payloads are well under 256 bytes.

### Consequence

Every string/array field pays 4 bytes more than it needs to. For a
message with 20 short string fields that's 80 bytes of pure overhead.

### Proposed Fix

Switch length prefixes to a varint encoding (ULEB128 or the Protobuf
variant). Strings under 128 bytes use 1 length byte, under 16 KB use 2
bytes, etc. Worst case is 5 bytes for values up to 2 GB — still smaller
than the current fixed 8. Varint decoding is cheap and well-documented;
many Kotlin implementations exist.

Alternative if varints are deemed too complex: switch to fixed 4-byte
(INT32) length and document the 2 GB limit explicitly. Cuts every
length field in half with a trivial code change.

---

## Top-Level List Exception Creates a Mode Split

### Problem

`LinearSerializer.serializeList` omits the length header when
`isTopLevel == true` (`LinearSerializer.kt:159–161`), and
`BinaryParser.parseArray` mirrors this by reading to EOF in the top-level
case (`BinaryParser.kt:247–253`). The same list embedded in a composite
and standalone produces **different bytes**.

### Consequence

- You cannot concatenate two serialized messages and have them parse as
  a single stream; each needs its own framing.
- You cannot extract a nested sub-message by slicing bytes — the slice
  would be missing (or have extra) bytes depending on context.
- The framing layer must know in advance whether a payload is a list or
  not before `parse()` / `parseArray()` can be chosen — an out-of-band
  concern the protocol doesn't surface.

### Proposed Fix

Remove the top-level exception. Always emit the 8-byte (or varint, per
above) length prefix, regardless of position. The 8 bytes are
negligible relative to the correctness win: a payload's bytes become
position-independent, slicing works, concatenation works, `parse()`
alone suffices and the separate `parseArray()` entry point can be
deleted.

If there's a genuine reason for the top-level omission that I'm missing
(e.g. a legacy on-disk format that can't be rewritten), document it
explicitly in `binary-protocol.md` as "§2.5 Top-Level List Exception"
and spell out when to use which entry point.

---

## No Top-Level Framing or Integrity

### Problem

A serialized message has no total-length header, no magic number, and no
checksum. `parse()` assumes the entire buffer is one message; a
corrupted buffer decodes into whatever those bytes happen to represent
until the parser runs out of input, usually surfacing as "Buffer
overflow: requested N bytes at M" — often far from the actual corruption
site.

### Consequence

- No way to detect early whether a buffer is even plausibly a Tenshu
  message (a 4-byte magic would suffice).
- Corruption in the middle of a large payload produces errors at the
  end, or silent garbage if the corruption happens to preserve parse
  validity by chance.
- Appending or prepending unrelated bytes (e.g. accidentally including a
  BOM or a debug line) is not detected.

### Proposed Fix

Add a small top-level preamble:

```
magic:    BYTE[4]   // e.g. "TSHU"
version:  INT8      // protocol version; currently 1
flags:    INT8      // reserved for future wire-level flags
length:   INT32     // total message size excluding preamble
crc32:    INT32     // of the message body (excluding preamble)
```

Total preamble: 14 bytes. `version` lets future wire-format changes
co-exist with existing payloads. `length` turns the format into a
self-delimiting frame (removing the top-level-list exception above as a
side effect). `crc32` catches storage/transport corruption. For
latency-sensitive intra-process IPC, `flags` can opt out of the CRC
(leaving the four bytes zero).

---

## No Streaming or Zero-Copy

### Problem

`BinaryParser` uses `ByteArray.copyOfRange` pervasively — each string,
reference, and list element allocates a fresh array. The parser also
requires the entire buffer upfront; there's no way to feed bytes
incrementally or back-pressure on a slow consumer.

### Consequence

- GC pressure scales linearly with payload size and depth.
- Large messages (e.g. a synced workspace dump) must be fully buffered
  before any decoding starts.
- Streaming-first consumers (e.g. a UI that could render composites as
  they arrive) are impossible without layering a chunking protocol on
  top.

### Proposed Fix

A larger refactor, lower-priority than the correctness items. Two steps:

1. Migrate the parser from `ByteArray` to `kotlinx.io.Buffer` or a
   similar view type. Replace `copyOfRange` with slice/view operations
   so decoded `TString` / `TByteArray` values reference subranges of the
   source buffer where possible.
2. Make `parseData` coroutine-friendly — a `suspend fun` that reads from
   an `kotlinx.io.Source`, suspending when the source runs dry.
   Top-level framing (previous section) is a prerequisite: without a
   known message length, streaming can't know when a message ends.

---

## Per-Message Type-UUID Overhead for Small Values

### Problem

Every polymorphic boundary costs 16 bytes for the type UUID. A standalone
`TInteger` is 16 bytes (UUID) + 8 bytes (value) = 24 bytes — 67%
overhead. For RPC return values, streaming events, and other
small-message flows, this dominates wire cost.

### Consequence

Wire efficiency for primitive payloads is far below what comparable
protocols achieve. MsgPack and CBOR encode small integers in 1–2 bytes
total; Protobuf's varint tags are 1–2 bytes per field.

### Proposed Fix

Intern the commonly-used type UUIDs. Reserve the first 256 "type
slots" as a built-in table of well-known UUIDs (the primitive types,
core schemas, operation IDs, etc.) and let the wire encode those as a
single byte. Non-interned types fall back to the current full 16-byte
UUID, flagged by a leading `0xFF` byte.

```
type_header ::= <short_form> | <long_form>
short_form  ::= BYTE    // 0x00..0xFE — index into the well-known table
long_form   ::= 0xFF BYTE[16]
```

A single byte per primitive polymorphic boundary. For the
`TInteger`-as-root example: 1 + 8 = 9 bytes instead of 24 bytes, a 63%
reduction. The well-known table must be versioned alongside the
protocol — enumerate it in `binary-protocol.md`.

---

## Tag Redundancy for Monomorphic Collections (Maps & Lists)

### Problem

When a field is defined as a `TList<T>` or `TMap<K, V>` in the schema, its type is already known.
However, the protocol handles them inconsistently:

- **Lists** in composite fields currently omit the `TList.ID` tag but retain an 8-byte length
  header.
- **Maps** are treated as generic composites, meaning they always write the `TMap.ID` tag (16 bytes)
  plus their full field-based structure.
- **Top-level lists** (like callstacks) currently *require* the `TList.ID` tag because the parser
  has no `expectedType` context.

### Consequence

- **Redundancy**: 16 bytes are wasted for every map field.
- **Inconsistency**: The protocol depends on out-of-band information to decide whether a `TList.ID`
  tag is present or if it's a "naked" list.
- **Complexity**: `BinaryParser` and `LinearSerializer` have special-case logic for `TList.ID` that
  makes them harder to maintain.

### Proposed Fix

**Introduce Type-Aware Parsing and Serialization.**
Pass the `expectedType: Type` from the schema down to the parser and serializer.

- If a field is typed as `TList` or `TMap`, the serializer omits the type tag and proceeds directly
  to the content (Length + Elements for lists; Length + Key/Value pairs for maps).
- The parser uses the expected type to determine if it should look for a tag or read the content
  directly.
- This unifies the handling of all collection types and allows for a single, consistent
  `parse(data, expectedType)` entry point.

---

## Summary

The protocol as designed is tight and consistent **within a fixed
schema version**, and the current implementation is simple enough to
audit. The fragilities surface when schemas evolve or when payloads
cross version boundaries, and the per-element overhead is generous.

If there's time for only two changes, I would prioritise:

1. **Presence prefix for optionals** (solves null-vs-empty ambiguity
   and makes mid-composite optionals safe). Small code change, immediate
   correctness win.
2. **Schema revision in the polymorphic header** (foundational for any
   future evolution mechanism). Cheap to add; expensive to retrofit if
   deferred.

Everything else — wire-type nibble, monomorphic lists, varint lengths,
top-level framing, interning table, streaming parser — can be
sequenced independently once the revision header is in place.
