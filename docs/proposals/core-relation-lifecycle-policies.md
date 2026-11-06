# Relation lifecycle policies — cascade and retention as declarations

**Status:** **Draft v0.1 — design pass, no code (2026-07-29).** Opened off
[`core-relation-system.md`](core-relation-system.md) R2, which extracts *membership* from
`FileModificationRelationMaintainer` and deliberately leaves that observer's other two jobs —
a **retention cap** and a **tombstone cascade** — hand-written, because nothing generalises
them. This doc is the design for generalising them, written now while the reasoning is fresh;
**the build should wait for its forcing consumer** (§8).

⚠⚠ **Read §3 before anything else.** The two policies look like one thing ("delete rows from a
relation") and have **opposite propagation semantics**. A shared abstraction that gets that
backwards is worse than the two hand-written implementations it replaces.

---

## 1. The gap in one sentence

**Two relation lifecycle policies — "delete the children when the parent dies" and "keep at most
N children" — are implemented by hand, once each, inside an observer whose actual job is
membership; both have a second consumer, and neither has anywhere to be declared.**

## 2. What exists today

### 2.1 Retention cap — `FileModificationRelationMaintainer.enforceRetention`

On a locally-originated `FileModification` save, if that file's history exceeds
`FileModification.RETENTION_CAP` (50), trim oldest-first:

```kotlin
val forFile = executionContext.database.getArrayData(modificationsRef)?.toList()
    .filter { fileIdOf(it) == filePath }
var overBy = forFile.size - FileModification.RETENTION_CAP
for (row in forFile.sortedBy { timestampOf(it) }) {
    if (overBy <= 0) break
    if (typeIdOf(row) == CreatedFileModification.ID) continue   // pinned floor record
    executionContext.deleteData(rowId, origin = WriteOrigin.USER)
    tombstoneEmitterHolder?.emitter?.emit(targetEntityIdHash = …, targetEntityType = …)
    overBy--
}
```

Three things to notice, because they pre-validate the design in §5:

- **The eviction key is already a field** — `sortedBy { timestampOf(it) }` reads
  `FileModification.Field.TIMESTAMP`.
- **The pin is already a subtype id** — `typeIdOf(row) == CreatedFileModification.ID`, not a
  predicate lambda.
- **It emits a tombstone per trimmed row**, and only `origin == WriteOrigin.USER` saves trim.

### 2.2 Tombstone cascade — `cascadeDeleteHistory`

On a `File`/`Folder` delete, delete that file's `FileModification` rows. Its own comment states
the invariant:

> No tombstone is emitted (`SyncObserver.onDataDeleted` is a no-op); the parent file's tombstone
> already drives this cascade on every device.

It also **passes `origin` through**, so an ingest-driven cascade stays `SYNC_INGEST` — where
retention hardcodes `WriteOrigin.USER`.

### 2.3 Both have a second consumer

- **Cascade:** `DataIdMigrator.cascadeDeleteHistory(sourcePath)` in `:sync-service-common`
  (content-addressing Ph2 3b) is a second implementation of the same idea.
- **Retention:** [`core-error-channel.md`](core-error-channel.md) §4.4 / **Phase 5b** —
  designed, not built — wants a **500-event ring buffer** *plus* a **30-day eviction sweep** over
  `ErrorEvent`, each drop tombstoned. That is §2.1's policy with a second (time) bound.
  Notifications are a plausible third.

## 3. ⚠⚠ The load-bearing asymmetry

Both policies delete rows from a relation. **Their propagation semantics are opposites**, and
this is the single most important thing in this document.

| | Cascade | Retention |
|---|---|---|
| Derivable by a peer? | **Yes** — from the parent's tombstone | **No** — nothing a peer sees implies the trim |
| Tombstones emitted | **None** | **One per dropped row** |
| Who acts | **Every replica**, independently | **Only the originating device** |
| `origin` | passed through | forced to `USER` |

The reasons are sharp in both directions:

- **Cascade must not propagate.** The parent's single tombstone already crosses the wire. Emit a
  tombstone per cascaded child and one folder delete becomes a tombstone storm proportional to
  the subtree. This is exactly why `SyncObserver.onDataDeleted` is a deliberate no-op.
- **Retention must propagate.** A trim is a *local policy decision* invisible to peers, so
  without a tombstone the row simply reappears on the next sync. And only the originator may
  trim: if every replica trimmed independently the tombstone traffic would multiply by the
  device count, for a set of rows they all agree on anyway.

**Design consequence:** these are two policies, not one parameterised policy. Any shape that
lets an author write `retention = Cascade`-ish confusion — or that defaults the propagation
behaviour — is wrong. The propagation semantics must be a *property of which policy you chose*,
never a knob.

## 4. Why this is newly generalisable

Until `core-relation-system.md` R1/R2 there was nowhere to declare a relation, so a per-relation
policy had nowhere to live and "a hand-written observer" was the only option available.

That changed. `serviceDataRelation(id, type, order, chainReadable)` is now the declaration site,
and **`order` is the precedent**: a policy the proxy applies, declared where the relation is
declared, code-side metadata that never reaches the wire (positional composites make an added
field peer-breaking — the `ServiceSchema.version` / `deferredByDefault` rationale). `retention`
and `onParentDeleted` are siblings of `order`, not a new concept.

## 5. Proposed shape

```kotlin
serviceDataRelation(
    id = FileService.Relations.MODIFICATIONS,
    type = TList.typeOf(FileModification.Schema),
    order = MembershipOrder.MostRecentLast,

    // §3 left column — local on every replica, never propagates.
    onParentDeleted = ParentDeletion.Cascade,

    // §3 right column — originating device only, tombstones every drop.
    retention = Retention(
        maxCount = FileModification.RETENTION_CAP,
        maxAge = null,                                   // logging Ph5b sets 30.days
        evictBy = FileModification.Field.TIMESTAMP,      // a FIELD, not a comparator
        pinned = setOf(CreatedFileModification.ID),      // subtype ids, not a predicate
    ),
)
```

**`ParentDeletion`** = `Cascade` | `Orphan` (default). Genuinely *derivable* — "delete the parent
entity ⇒ delete its relation members" needs nothing the relation declaration does not already
carry, once the relation is entity-scoped (`RelationScope.EntityField` / `EntityProperty`).

**`Retention`** carries both bounds because the two known consumers need one each, and keeps
every input as **data**:

- ⚖️ **`evictBy` is a field reference, not a `Comparable` or comparator** — the same argument
  settled in `core-relation-system.md` §11.1 for the sort key. Data can be indexed and can ride
  the module graph to the JS SDK / a third-party EPC module; code can neither. And §2.1 shows the
  existing implementation already *is* a field read, so nothing is lost.
- ⚖️ **`pinned` is a set of subtype ids, not a predicate** — likewise, and likewise already true
  in §2.1.

### 5.1 ⚖️ Should these be **traits** — and if so, on what?

Raised in review: *"`ParentDeletion(mode = Cascade/Orphan)` as a trait, evaluated by the
generalised maintainer?"* The trait *shape* is right. The attachment point in the question is
not, and the correction turns out to be the more interesting answer.

**Traits attach to a `DataSchema` — a type.** `Syncable`, `AppendOnly`, `MeteredExempt`,
`DataIdProvider` all hang off the element type and are read via
`traitResolver.getTrait<T>(typeId, …)` / `schema.hasTrait(…)`. A trait on `FileModification`
can say *"I cascade"*, but it cannot say **from which relation, or from which parent** — that
information lives on the relation's scope, not on the type. `FileModification` has no intrinsic
parent; it has one *because* `File.FileHistory` scopes it to a file. Put the policy on the type
and it is split across two declarations again — precisely the drift hazard that got
`relationMaintainer(spec)` rejected in `core-relation-system.md` D1, and the same "wrong owner"
objection §9 below raises against a `Retainable`/`Cascading` element trait.

⚠ It *happens* to work at today's data (no element type sits in two entity-scoped relations),
but §10.3's audit is a fresh reminder of what happens when a current-data observation is
promoted to a design invariant.

**⚖️ So: a trait, on the RELATION schema.** And this is not new machinery —
`RelationSchema` **already extends `CompositeSchema` and already carries a `traits` slot**
(`ServiceDataRelationSchema.create` passes `emptyList<Trait>().toTList(context)` today). Every
relation variant has it. **Nothing has ever put a trait on a relation.** The slot is built,
wired through the schema DSL, and empty.

That reframes the whole §5 shape. Instead of growing `serviceDataRelation(id, type, order,
chainReadable, retention, onParentDeleted, …)` — a parameter list that gets one entry longer per
policy forever — each policy becomes a relation trait, and the generic maintainer resolves them
the way `SyncObserver` already resolves `Syncable`/`MeteredExempt` on a type. `order` and
`chainReadable`, shipped in R1/R2 as constructor parameters, would be the first candidates to
migrate.

**⚠ The cost, and it is real: traits are part of the *serialised* schema.** `CompositeSchema`
declares `list(Fields.TRAITS, of = TraitSchema.Schema)`, so a trait's value must be a wire type
— the `SyncPolicy` enum → `TConstant` conversion is the precedent for what that
costs. Today `order` is a plain Kotlin enum with a default, read only by the proxy's own
registration and deliberately **never serialised** (the `ServiceSchema.version` /
`deferredByDefault` rationale). Making it a trait reverses that decision, so it has to be worth
something.

**⚖️ Two things say it might be:**

1. **A trait is the *wire-safe* way to extend, where a field is not.** The reason relation
   metadata was kept off the wire is that composites serialise **positionally**, so an added
   field garbles peers mid-parse. A trait is not a positional field — it is a **new schema id
   appended to a list**, which is exactly the additive shape that does not break old readers.
   So the instinct "keep policies off the wire" was really "keep policies out of *positional
   fields*", and traits sidestep it.
2. **It answers D2's third-party gap.** `core-relation-system.md` D2 accepted `parentOf` as a
   lambda while noting a third-party/EPC module could not then declare an entity-scoped relation
   descriptively. Traits ride the module graph as data; lambdas do not. If relation policies
   become traits, EPC modules can declare them — the same reason §11.1 insisted the sort key be
   a field and not a `Comparable`.

**Open, and bigger than this document:** whether relation policies should be traits *generally*
is a `core-relation-system.md` question, not a lifecycle-policies one — it would migrate `order`
and `chainReadable` too, and it partially reverses a decision R1 shipped. Recorded here as
**Q6**; it should be settled before L2 adds a third parameter to that constructor, because the
migration gets more expensive with each one.

## 6. ⚖️ A scan elimination that falls out for free

Both today's implementations read the **realm-wide** `MODIFICATIONS` array and filter it by
`fileIdOf(it) == filePath`. That is O(*every modification in the realm*) on every file write and
every file delete — even though `File.FileHistory` (the per-file `EntityPropertyDataId` array,
maintained by `FileHistoryRelationMaintainer`) already exists and holds exactly the rows both
want.

⚠ So the policies are scanning the wrong relation. Declaring them **on the entity-scoped
relation** makes both O(this file's history) — for `enforceRetention`, a per-write full-log scan
becomes a bounded one. This is not the motivation for the work, but it is a real payoff and it
argues for the entity-scoped relation being the declaration site rather than the flat one.

⚠ Counter-note: eviction still has to find "the oldest", which without a maintained key order is
a scan of that relation, and each dropped row is another full-row array rewrite (tech-debt:
*Relation Arrays Are Full-Row Rewrites*). Bounded by the relation, not the realm — good enough,
but not free.

## 7. Open questions

- **Q1. Where does the time-based sweep run?** `maxCount` is naturally enforced on write (as
  §2.1 does). `maxAge` is not — nothing writes when a row *ages out*. Logging Ph5b says "a daily
  sweep". That wants an idle+charging `PlatformScheduler` maintenance slot — the same one
  compaction will use — and it must be **correctness-irrelevant** (a sweep that never runs must
  only mean "some old rows linger", never a broken invariant). ⚠ It also reintroduces the
  who-acts question: a sweep on every device would multiply tombstones exactly as a per-replica
  trim would. Probably the same answer — one device acts, peers follow tombstones — but "which
  device" is easy on a write (the writer) and undefined on a timer.
- **Q2. Does cascade need to be declared at all, or is it implied by scope?** An entity-scoped
  relation whose members are meaningless without the parent (a file's history) arguably *always*
  cascades. Is `Orphan` a real case, or a default nobody wants? If nobody wants it, cascade stops
  being a policy and becomes behaviour — cheaper.
- **Q3. Does retention belong on the relation or on the element type?** The cap is per-*file*
  history, which is per-relation. But logging's cap is per-*type* (`ErrorEvent`) with only one
  relation. Relation-level covers both; type-level does not. Confirm nothing wants two relations
  over the same type with different caps.
- **Q4. Interaction with `MembershipOrder`/`orderedBy`.** If a relation ever gains a declared
  order (`core-relation-system.md` §11.2), `evictBy` and `orderedBy` will usually be the same
  field. One key, or two independent ones? Two is more general; one is likely to be right every
  time and would make eviction O(1)-ish at the head of a maintained order.
- **Q5. Does the cascade generalisation subsume `DataIdMigrator.cascadeDeleteHistory`?** It is a
  cascade over the same relation triggered by a *re-key* rather than a delete. Possibly the same
  mechanism with a different trigger, possibly not — check before assuming.
- **⚖️ Q6. Should relation policies be *traits on the relation schema* rather than constructor
  parameters? See §5.1.** The slot already exists and is unused; it would unify `order` /
  `chainReadable` / `retention` / `onParentDeleted` and close D2's third-party gap, at the cost
  of making the values wire types. **Settle this before L2**, because it partially reverses an
  R1 decision and the migration gets more expensive with every parameter added to
  `serviceDataRelation(...)`. This is really a `core-relation-system.md` question.

## 8. Phasing — and why not yet

⚖️ **Recommendation: write this down (done), build it when logging Phase 5b is scheduled.**
Retention's second consumer is *designed*, not scheduled; building ahead of it is n=1 dressed as
n=2, and this is precisely the situation — two policies, opposite propagation, one of them
unbuilt — where a premature shared abstraction does damage.

| Phase | Scope | Gate |
|---|---|---|
| **L1** | `ParentDeletion.Cascade` on the declaration; migrate `FileModificationRelationMaintainer`'s cascade half. **Independently shippable** — cascade is the clean half and has no unbuilt consumer. | `FolderSyncSystemTest` + the file-delete cascade tests pass unmodified; ⚠ assert **no** tombstone is emitted per cascaded child |
| **L2** | `Retention(maxCount, evictBy, pinned)` on the declaration; migrate `enforceRetention`. Move both policies onto the entity-scoped relation (§6). | existing retention tests unmodified; a write-count before/after for §6 |
| **L3** | `maxAge` + the sweep (Q1), landing **with** logging Phase 5b as its consumer — not before. | Ph5b's own tests; a two-device test that a sweep does not multiply tombstones |

## 9. Considered and rejected

- **One unified `RelationLifecycle` policy object** covering both. Rejected: §3. They share a
  verb ("delete rows") and nothing else; unifying them buries the one distinction most likely to
  be got wrong.
- **A `Retainable`/`Cascading` trait on the element type.** Rejected for the same reason D1 in
  `core-relation-system.md` rejected an element trait for membership: the policy belongs to the
  *relation*, and one element type can sit in several relations wanting different policies —
  `FileModification` is in both `MODIFICATIONS` and `File.FileHistory` right now.
- **Predicate/comparator-based eviction** (`evictWhen: (Data) -> Boolean`). Rejected: §5 — code
  cannot be indexed and cannot cross to JS/EPC, and §2.1 shows the real implementation never
  needed it.
- **Relay-side retention.** Rejected on HR #1: the relay sees ciphertext and cannot evaluate
  "oldest non-`Created` row". (The relay's *own* mailbox GC is a different, already-correct
  mechanism — see `retention-quota-model.md`'s tier taxonomy.)

## 10. Related

- [`core-relation-system.md`](core-relation-system.md) — R2 extracts membership and leaves these
  behind; §11.1 settles the field-not-comparator argument this doc reuses.
- [`core-error-channel.md`](core-error-channel.md) §4.4 / Phase 5b — the forcing consumer.
- [`retention-quota-model.md`](retention-quota-model.md) — the *storage-tier* retention model.
  ⚠ Different subject: that is about relay blobs and device caches, this is about rows in a
  relation. Do not conflate them.
- `docs/tech-debt.md` — *"Relation Lifecycle Policies Are Hand-Written and Entangled With
  Membership"* (this doc's origin) and *"Relation Arrays Are Full-Row Rewrites"* (§6's caveat).
