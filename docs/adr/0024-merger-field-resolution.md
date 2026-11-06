# ADR 0024 — `resolveListField` is the merge-time read for a collection field

- **Status:** Accepted, already implemented (ratifies existing practice; no code change)
- **Date:** 2026-08-25
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Depends on:** [ADR 0006 — sync tombstones](0006-sync-tombstones.md)
  (`purgeDeadMembers` supplies the removal semantics a union merge cannot express),
  [ADR 0008 — `ExecutionContext` decomposition](0008-execution-context-decomposition.md)
  (`ReferenceResolver.resolveList`)

## Context

`SemanticMerger.merge(executionContext, existing, incoming)` receives its two sides in
**different representations**, and this is structural rather than incidental:

- `existing` is the current local state, and **often carries lazy database references** —
  a collection field may be an unresolved `ArrayDatabaseReference`, not a list.
- `incoming` arrives off the wire and is **usually fully materialised**.

So a merger that reads a field the obvious way — `composite.getField(FILES)` — works on one
side and silently yields the wrong thing on the other. The failure is not a crash: an
unresolved reference read as a collection produces an empty or nonsensical member set, and a
union merge over it quietly drops members. That is the "lazy-vs-materialised data seam" that
`FolderMerger` surfaced, and it was carried as an open question in `CLAUDE.md` from the first
merger onward, with the note that *"the next merger over a list field is the one that wants the
doc."*

That trigger fired without being noticed. `TripMerger` and `MomentMerger`
(`:timeline-service-proxy`) are both mergers over collection fields, and both reached for the
same helper rather than a new design — `TripMerger`'s KDoc states it outright: *"The same seam
and shape as `FolderMerger`: read both collection fields with `resolveListField`."* Three
mergers converged on one answer independently. There are now **11 `SemanticMerger`
implementations across six services** (calendar, file, health, place, sync, timeline).

## Decision

**`ExecutionContext.resolveListField(data, fieldId)` (`:sync-service-common`) is the way a
merger reads a collection field. A merger MUST NOT read a collection field with `getField` /
`getFieldValue` directly.**

The helper is deliberately small — it is the two-arm shape dispatch plus a resolve:

```kotlin
fun ExecutionContext.resolveListField(data: Data, fieldId: String): List<Data> {
    val raw = when (data) {
        is Entity<*>  -> data.getFieldValue<Data>(fieldId)
        is Composite  -> data.getField<Data>(fieldId)
        else          -> null
    }
    return resolveList(raw)?.toList() ?: emptyList()
}
```

Two properties make it the right seam rather than merely a convenience:

1. **It absorbs the asymmetry.** Callers stop caring whether a side is an `Entity` or a
   `Composite`, or whether the field is lazy or materialised. The merger expresses only its
   merge policy.
2. **It is pure.** No IO beyond `resolveList`, so it is safe to call inside
   `SemanticMerger.merge`, which runs on the ingest path.

Consequently the seam needs **no** separate design doc, and the `CLAUDE.md` open item that
asked for one is closed by this ADR.

⚠ This decides the **read**. It does not decide removal semantics: a union merge cannot express
"this member was deleted", and `purgeDeadMembers` + the tombstone registry remain the only
thing that can. See `SemanticMerger.purgeDeadMembers`' KDoc for the failure that closes
(a folder that lists a child every device has deleted).

## Alternatives considered

- **Direct field access, with each merger handling laziness itself.** Rejected: this is the
  status quo ante that produced the seam. It puts a correctness-critical, easily-forgotten
  two-arm dispatch in every merger, and gets it wrong silently.
- **Materialise both sides before dispatching to the merger.** Rejected: it moves cost onto the
  ingest hot path for every merge, including the majority that touch only scalars, and it would
  resolve collections no merger reads.
- **A declarative per-field merge strategy in the schema.** Not rejected — deferred. That is
  [`sync-merge-strategies.md`](../proposals/sync-merge-strategies.md), which is spec'd and not
  started. It changes *how a merge is configured*, not how a merger reads a field, so it layers
  on top of this decision rather than replacing it. If it lands, the declarative path owes the
  same lazy-vs-materialised handling internally.

## Consequences

- A new merger over a collection field is now a **convention to follow, not a design question
  to reopen**. `FolderMerger`, `TripMerger` and `MomentMerger` are the reference shapes.
- ⚠ **The scalar half of the same asymmetry is still hand-rolled.** `PlaceMerger`,
  `PlaceMergeMerger`, `PlaceRoleMerger`, `PlaceNetworkFingerprintMerger`, `CalendarMergers` and
  `RealmSchemaEpochMerger` each repeat the identical `is Entity<*> → getFieldValue` /
  `is Composite → getField` `when` for scalar fields — the same two arms `resolveListField`
  encapsulates for collections. That duplication is **observed here, not decided**: a
  `resolveField` sibling is the obvious follow-up, but nothing in this ADR requires one, and no
  bug is known to follow from the current copies (they are individually correct).
  ⚠ Sized and tracked in [`tech-debt.md`](../tech-debt.md) — *"The scalar half of the merge
  two-shape read is hand-rolled in seven places"*, which also records that the copies' `else`
  arms have already drifted four ways (two of them silently), and that `PlaceReconciler` carries
  a verbatim copy despite not being a merger at all.
- `CLAUDE.md`'s "Open items worth knowing about" section is now empty of this entry, which is
  the point: the file routes to decisions, and this is one.
