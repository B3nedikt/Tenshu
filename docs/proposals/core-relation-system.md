# Relation system — relation membership as a first-class synced primitive

**Status:** **Draft v0.4 — design §9 settled for Half A; phase R1 SHIPPED (2026-07-28, as-built
§10.1); R2–R4 pending.** ⚖️ The §7 audit downgrades urgency: this is an **E4-compaction
prerequisite**, not a standing bug — a local wipe alone recovers from relay history. Read §7 before
§5/§6.
Opened because the doc was already referenced as "the proper fix" from
`IdentityProxyInitialization`'s KDoc but had never been written, and because a concrete production
incident (below) turned the gap from theoretical into a broken security-management capability. §1–§4
are established fact, gathered from the incident and the code; §5/§6 are the original open options.
**§9 onward is the proposed design** — it splits the problem into a pure refactor that can land
immediately (§9.2, no wire change) and a new synced primitive that is a *capability*, not a fix
(§9.3), and is explicit about which §4 requirements it does and does not meet (§9.5).
⚖️ **§9.6 answers "so what actually fixes §4.1?" — and the answer is not this proposal**: the
lever is closing the **core-version store wipe** (HR #9 lockstep manufactures the "all replicas
simultaneously" premise; E2b already built the migrate-instead-of-wipe machinery), plus escrowing
the one row that can never have a producer (`RetainedRealmKey`). ⚠ Finding along the way: the
relay's **A9 recovery-blob channel is built, durable, replay-protected — and no client ever
writes to it** (`RecoveryRouting.kt:141`; `RestSyncRelay` has no blob PUT). ⚠ *2026-10-28:* F8
(`relay-metadata-minimisation.md`) **removed** that channel — it served the blob by the public
`ownerId`; an escrow for `RetainedRealmKey` would need new, ownerId-blind routes. **Q1 and Q3 are now
answered** (§6).

---

## 1. The gap in one sentence

**`SyncBlock`s carry entity data, not relation mutations** — so relation membership is not a synced
primitive, and every relation in the system is a *locally derived cache* rebuilt by an observer that
watches entity saves.

The stand-in is `DeviceRelationMaintainer` (and its four siblings) in
`identity-service-proxy/.../IdentityProxyInitialization.kt`, whose own KDoc names this doc as the fix:

> **Why this exists**: relation membership is not a synced primitive today. `SyncBlock`s carry entity
> data, not relation-mutation operations. Without this observer, a Device entity that arrived via sync
> would land in the local DB but stay invisible to `getDevices()` […] The proper fix is the
> `core-relation-system.md` proposal (relation mutations as first-class CRDT ops); this observer is a
> pragmatic stand-in until that lands.

Five such maintainers exist today (`Device`, `SharedRealmMembership`, `Member`, `PendingGrant`,
`RetainedRealmKey`), all registered from `initIdentityService`.

## 2. Why it stopped being theoretical (the 2026-07-27 incident)

Desktop Control's "Realm devices" list rendered empty. The store held **zero** `tenshu/identity/device`
rows — not even the desktop's own — while sync itself was healthy. Forensics: nothing in the store
predated 2026-07-26 18:13, the instant it self-wiped on the fmt v14→v15 core-version mismatch.

The full write-up is the tech-debt entry *"`Device` rows have no regeneration path"*. The part that
matters **here** is the structural lesson, not the incident:

- Membership has an **authoritative** representation (A4 device keypair + derived realm keys + the
  relay's `KnownDevicesStore` registration) and a **descriptive** one (the synced `Device` row driving
  the roster, revocation targeting and `admittedAtEpoch`).
- They are written together **once**, at pairing, then drift independently forever.
- The authoritative one **cannot regenerate** the descriptive one. `Device.create` has five non-test
  call sites (`CreateRealm`, `RecoveryClaim`, `PairingKeyInstaller`, `ApprovePairingRequest`,
  `ApprovePairingOffer`); none re-runs spontaneously.
- Because HR #9 moves clients in lockstep, a core-version wipe destroys the rows on **every replica at
  the same instant**. No peer retains a copy, so there is nothing to re-sync — and a `SchemaMigration`
  is no help, because a migration reshapes rows that *exist*; it cannot resurrect rows nothing retained.

Consequence worth carrying into the design: **revocation broke.** `RevokeDeviceImplementation` looks up
the local `Device` entity and bails (`"Local Device entity not found … nothing to revoke"`), so an empty
roster means nothing can be revoked at all.

A tier-1 self-heal (`reconcileOwnDeviceRow`) has shipped to stop the bleeding. It is explicitly **not**
this proposal — it repairs one row on one device from locally held authority. It does not make relation
membership synced, and it cannot reconstruct the two fields §4 lists as unrecoverable.

⚠ **A lesson from building it, worth carrying into any design here.** The first cut gated the repair on
"realm not locked". That is *not* a membership predicate — an unpaired device also reports unlocked
(no unlock wrapper ⇒ `isSetUp = false`) — so a device that had never joined wrote itself an
`isAuthorized = true` row. Because `Device` rows merge **whole-entity LWW**, that phantom row then
raced the admitter's real one and erased `admittedByDeviceId`, silently shrinking the revoke subtree.
`DelegatedAdmissionSystemTest` caught it. Two transferable points: (i) the canonical membership gate is
`SyncManager.currentRealmContext()` (owner id + derived keys), and (ii) **whole-entity LWW on `Device`
means any reconstruction races real writes and can destroy fields it did not intend to touch** — a
strong argument for field-level or op-based semantics (§5a) over any scheme that re-authors whole rows.

## 3. What the observer stand-in does and does not buy

Works today: an entity arriving via sync-merge is picked up by the observer and added to the relation, so
`getDevices()` (a relation query — `GetDevicesImplementation` resolves
`IdentityService.Relations.DEVICES`, the legacy `GET /identity/devices` endpoint is gone) sees it.
`onDataDeleted` symmetrically removes it, so tombstoned devices disappear. It is idempotent on save.

Does **not** work:
- **Relation membership itself never travels.** Only entity rows do. Every replica must independently
  re-derive the same relation, and any replica that loses the entities loses the relation with no path
  back.
- **No ordering, no conflict semantics.** Two devices concurrently adding/removing the same member
  resolve by whole-entity LWW on the *entity*, not by any relation-level rule.
- **Derived state depends on it silently.** The realm epoch is
  `tombstoneRegistry.tombstoneCountForType(Device.ID)` — a count of `Device` tombstones, i.e. relation
  history standing in for a counter. A store wipe resets it to genesis, degrading T-K-02 fencing.

## 4. Requirements any design must meet

1. **Membership survives a store wipe on all replicas simultaneously** — or is reconstructible from
   authority that does survive. This is the requirement the current design fails.
2. **Revocation must not depend on a cache** that can be empty. Either the roster is durable, or
   `RevokeDevice` targets something else.
3. **Preserve admission lineage.** `admittedByDeviceId` drives revocation of the transitive admission
   subtree (A33 delegated authority, `delegated-device-admission-authority.md` §5). It is one of the two
   fields the tier-1 self-heal *cannot* reconstruct — empty lineage revokes less than it should. (Empty
   is correct only for an A1-rooted genesis/owner device, which has no lineage parent by design.)
4. **Preserve or replace the epoch derivation.** The other unrecoverable field is the original
   `admittedAtEpoch`, and the local epoch view itself resets after a wipe, so "stamp current" is not a
   faithful reconstruction. A design that makes membership durable should probably stop deriving the
   epoch from tombstone counts.
5. **Converge without a coordinator.** No election, no quorum — the same posture as compaction (§8.3 of
   `core-schema-evolution.md`): there is no adversary inside the trusted device set, so idempotent +
   convergent beats consensus.
6. **HR #1 intact.** Relation state is E2E; the relay must not learn realm topology it cannot already
   see. ⚠ Note the relay *does* already know device registrations (`KnownDevicesStore`) — so a design
   that leans on the relay for the roster is not obviously a leak, but it **is** a trust shift and needs
   a threat-model amendment (HR #2), not a shrug.
7. **Positional-composite discipline.** Any new synced type is subject to the same
   `TENSHU_STORAGE_FORMAT_VERSION` lockstep + migration rules as everything else (HR #9).

## 5. Options sketched (none chosen)

- **(a) Relation mutations as first-class CRDT ops.** The shape the existing KDoc names: an
  add/remove op travels in its own block; membership is an OR-Set (or similar) rather than a derived
  view. Most general, most work; needs a wire type and a merge rule, and inherits the same "lost on a
  simultaneous wipe" problem *unless* the op history is retained or replayable from the relay.
- **(b) Derive the descriptive record from held authority.** Each device reconstructs *its own* row from
  the keys + registration it holds, and peers learn it the ordinary way. This is the tier-1 self-heal
  generalised. Cheap and self-healing; does **not** solve lineage (§4.3), and a device that is offline
  forever never re-announces.
- **(c) Make the roster durable rather than derived** — e.g. an owner-held membership record on the
  content plane, re-fetchable after a wipe. Solves reconstruction cleanly; concentrates authority on the
  owner device and needs a story for member-side reads.
- **(d) Reconstruct from the relay's `KnownDevicesStore`.** The relay already holds registrations, so
  the roster is recoverable from it. Trust shift per §4.6 — the relay would become the source of truth
  for realm topology, which today it merely observes.

Likely the answer is (b) for self-repair + one of (a)/(c) for the general primitive; that pairing is a
guess, not a recommendation.

## 6. Open questions

- **Q1.** ~~Is the general CRDT relation primitive actually needed…~~ **ANSWERED by §9.1/§9.5 —
  neither, exactly.** The general primitive is not needed *for the 30 relations that exist*
  (all derived; §9.2's refactor serves them with no wire change), and it does **not** deliver
  recovery either (§9.5 row 1). It is worth building as a **capability** for relations whose
  membership is not derivable — and when built, it needs no new CRDT: whole-entity LWW on a
  `RelationMember` row *is* a per-element LWW-Element-Set (§9.3).
- **Q2.** What replaces the tombstone-count epoch derivation, and does changing it need a coordinated
  bump (every device must agree on the epoch or T-K-02 flaps)?
- **Q3.** ~~Can admission lineage be reconstructed at all after loss…~~ **ANSWERED by §9.6 — no.**
  Neither surviving tier (client key material, relay non-mailbox state) records *who admitted
  whom*. Accept as permanently lost for pre-fix realms; re-established at the next pairing.
- ~~**Q4.**~~ **ANSWERED — see §7.** All five are `Syncable(Eager)`; the failure needs a store wipe *plus*
  absent relay history; `RetainedRealmKey` is indeed the worst (permanent undecryptability) and the real
  trigger to guard is **E4 compaction**, not everyday operation.
- **Q5.** Should relations become queryable/synced generally, or is this a per-relation decision?

## 7. Recovery audit — Q4 ANSWERED (2026-07-27)

Done. The result **lowers the urgency and changes the trigger to watch**, so read this before acting on
§5/§6.

**All five relation element types are `Syncable(SyncStrategy.Eager)`** — `Device`,
`SharedRealmMembership` (5 create sites), `Member` (4), `PendingGrant` (2), `RetainedRealmKey` (1). None
is device-local. That single fact drives everything below, because it means they all share one recovery
path and one failure condition.

**⚠ The original framing of this doc (and of the tech-debt entry) was too strong.** A local store wipe
**alone is recoverable**: the sync cursor is a row *in* the store, so it is wiped with it, the next pull
runs `since=0`, and every `Syncable` row re-materialises from retained relay history. The unrecoverable
case is a store wipe **plus absent relay history**. And relay history is durable for live rows —
`MailboxStore.gcSweep` is **tombstone-driven only**: it iterates `tombstoneIndex(ownerId)` and drops
bodies solely for targets that were *deleted*, after quorum-ack + retention. A never-deleted row's block
is never swept.

So the real triggers are exactly two:
1. an **operator relay-data wipe** — what fired on 2026-07-26 (the E2 deploy), routine pre-release and
   ideally never post-release;
2. **E4 compaction**, whose stated purpose is to advance a floor and drop old history.

**⚖️ The actionable conclusion: this is an E4 prerequisite, not a standing bug.** Compaction must not
advance the floor past the last emission of a row type that nothing re-emits, or it converts a designed
optimisation into silent data loss. That is a concrete constraint to carry into
[`core-schema-evolution.md`](core-schema-evolution.md) §8.3 — and it is a *better* framing than "relations
should be a CRDT", because it names the one place the invariant can actually be enforced.

**Severity ranking, now that the condition is understood:**
- **`RetainedRealmKey` is the worst** — and Q4's hunch was right in kind, if not in urgency. It is
  created at exactly **one** site (`SharedRealmGrantManager.adoptNewRealmKey`), from the *previous*
  `SharedRealmMembership.wrappedMasterKey`, at the moment of a Kr rotation. Once the membership row is
  swapped to Kr′, **the retired key exists nowhere else on the device.** Lose it with the history gone
  and every block + chunk written under the old Kr is permanently undecryptable (ADR-0017 does not bulk
  re-encrypt unchanged files; GR-7's trial-decrypt has nothing to try). That is true **data loss**, not a
  management-surface gap — strictly worse than the roster.
- `SharedRealmMembership` — losing it drops access to the shared realm (the current Kr wrap lives here),
  recoverable only by a re-grant from the owner.
- `Member` / `PendingGrant` — management-surface only, like `Device`.

**What did not change:** the tier-1 self-heal is still worth having (it repaired the desktop without any
relay history, which is precisely the state the 07-26 wipe left), and admission lineage is still
unreconstructible. What changed is that the general fix is **not urgent**, and its most valuable form may
be an E4 floor constraint rather than a new relation primitive.

## 9. Design — proposed (2026-07-28)

### 9.1 The two halves, and why separating them is the whole point

The doc has been carrying one question that is really two, with very different risk and very
different payoff:

| | Question | Wire impact | Payoff |
|---|---|---|---|
| **A** | Why are there ~30 hand-written copies of the same observer? | **none** | deletes ≈1 650 lines, one dispatch instead of 30, kills a class of copy-paste bug |
| **B** | Should relation *membership* travel as blocks? | new synced type, HR #9 lockstep | **a new capability** — relations whose membership is not derivable from entity state |

They are separable because **A is a prerequisite for B and independently valuable**: once one
mechanism owns every relation, B is "teach that mechanism a second membership mode", not a
second sweep across 14 modules.

⚠ **B is not a fix for §4.1, and the design must not be sold as one.** A relation block is
relay history like every other block, so it dies under exactly the two triggers §7 identified
(operator relay wipe, E4 compaction). Worse, a *naive* B is a **regression**: today membership
is a total function of entity presence, so it is self-healing — an entity that arrives is
always visible. If a synced relation row became authoritative, an entity could arrive while
its membership row did not, and the entity would sit in the store invisible to every read.
§9.3 resolves this by making sync an *additional* membership mode rather than a replacement,
and §9.4 is the part that actually answers §4.1.

### 9.2 Half A — one maintainer, declared instead of written

**Finding from the survey (30 classes across 14 modules): every maintainer is the same function
of four parameters, and two of the four are already derivable from machinery that exists.**

Each one does: match the element type → compute the element's `StringDataId` → compute the
array's `ArrayDatabaseReference` → `transaction { removeFromArray(...); addToArray(...) }`, plus
the mirrored `removeFromArray` in `onDataDeleted`. `StringKeyedRelationMaintainer` in
[`TimelineProxyInitialization.kt`](../../timeline-service-proxy/src/commonMain/kotlin/org/tenshu/timeline/proxy/TimelineProxyInitialization.kt)
is already the local generalisation of exactly this — it serves three relations from one class.
The proposal is to promote that class to `:core-common` and drive it from a registration.

**What falls out for free (i.e. what the 30 copies are re-implementing by hand):**

- **The key.** Every copy hand-builds `DataId.StringDataId.create(type = X.ID, keyValue =
  <field>)` with a bespoke `keyOf`/`compositeKeyOf`/`shareIdOf`/`uuidOf` reader. That is
  precisely what `ExecutionContext.getDataId(data)` already returns — including the
  identity-root type resolution (`findIdentityRoot` in `GetStringDataIdImplementation`) that
  `FileModificationRelationMaintainer` reproduces in a comment. **Every `keyFieldRef` parameter
  and every `*Of(data)` helper in the codebase is redundant with the registered
  `DataIdProvider` trait.**
- **The type match.** `schemaProvider.getSchema(typeId).isSubtypeOf(elementTypeId)` is uniform
  (a few copies use a bare `!=` comparison instead and are therefore silently wrong for
  subtypes — `DeviceRelationMaintainer` compares `type.get() != Device.ID`).

**So the declaration reduces to the two things that genuinely differ:**

```kotlin
// :core-common — org.tenshu.core.common.relation
sealed interface RelationScope {
    /** Realm-wide array keyed by relation id — `DataId.RelationDataId`. 26 of 30 today. */
    data class Service(val relationId: String) : RelationScope
    /** Per-entity field array — `DataId.EntityFieldDataId`. `Folder.FILES`. */
    data class EntityField(val fieldRef: String, val parentOf: (ExecutionContext, Data) -> DataId.EntityDataId?) : RelationScope
    /** Per-entity property array — `DataId.EntityPropertyDataId`. `File.FileHistory`. */
    data class EntityProperty(val propertyId: String, val parentOf: (ExecutionContext, Data) -> DataId.EntityDataId?) : RelationScope
}

enum class MembershipOrder {
    /** Re-saves keep their slot; only absent/duplicate members are rebuilt. `Folder.FILES`. */
    Stable,
    /** Remove-then-add on every save. Today's default for the 29 flat relations. */
    MostRecentLast,
}

data class RelationSpec(
    val elementTypeId: String,
    val scope: RelationScope,
    val order: MembershipOrder = MembershipOrder.MostRecentLast,
    val isEntity: Boolean = false,
    /** §9.3. `Derived` = today's behaviour exactly; `Curated` mints `RelationMember` rows. */
    val membership: Membership = Membership.Derived,
)
```

⚠ **`MembershipOrder` above is SUPERSEDED — R2b deleted it (§10.4).** `Stable` was never
cosmetic: it is the fix `ParentFilesRelationMaintainer` carried in a 25-line comment
(remove-then-add reshuffled a re-saved file to the end of its folder, visible to the user as the
list reordering after a detail-view round trip). Encoding it as a policy meant the next relation
got the fixed behaviour by choosing an enum value; R2b went one better and made it the only
behaviour, so nobody has to choose. `RelationSpec` no longer carries `order`, and the declaration
parameter is now `maintained: Boolean` (the opt-in half of what `order` was doing).

**Where it is declared — ⚠ REVISED 2026-07-28, see §9.2.1.** R1 as first built put the spec
on the module configuration as a new `relationMaintainer(spec)` DSL verb. **That was wrong on
two counts** (maintainer review): the verb names an *implementation* (an observer) in a DSL that
should be purely descriptive, and it declares as *new* two facts the service schema already
carries. The corrected placement is the relation's own schema declaration; §9.2.1 is the
replacement design and D1 is re-settled accordingly.

### 9.2.1 Declaration site — corrected: enrich the schema, derive everything else

**The decisive fact: a service relation is already fully schema-declared, and R1's `RelationSpec`
re-declares two thirds of it.**

- `ServiceSchema.relationSchemas: TList<RelationSchema>` — every service's relations are already
  enumerable off its schema.
- `ServiceDataRelationSchema.type` already carries `TList.typeOf(Device.Schema)`, and
  `Type.elementSchema()` (`core/schema/Type.kt`) already extracts the element schema from a
  parameterised type. **So `RelationSpec.elementTypeId` is redundant with the existing
  declaration**, as is the relation id.

Only `order` is genuinely new information — and putting behaviour metadata on a schema without
putting it on the wire is a **twice-established pattern in this repo**, not a novelty:

- `ServiceSchema.version` — *"Code-side schema metadata, **never serialized** (the `@defer`
  `deferredByDefault` precedent: positional composites make added fields peer-breaking, so schema
  metadata stays off the wire)."*
- `RelationSchema.DynamicPropertyRelationSchema.deferredByDefault` — read-side execution policy,
  declared on a relation schema, `get() = false` default, never a wire field.

`order` is the same kind of thing as `deferredByDefault`: a policy the proxy applies, declared
where the relation is declared, invisible to the wire. So:

```kotlin
val Schema = service(
    id = ID,
    relations = listOf(
        serviceDataRelation(
            id = Relations.DEVICES,
            type = TList.typeOf(Device.Schema),
            order = MembershipOrder.MostRecentLast,      // code-side metadata; never serialized
        ),
        serviceDataRelation(
            id = Relations.MEMBERS,
            type = TList.typeOf(Member.Schema),
            order = MembershipOrder.MostRecentLast,
            chainReadable = false,                        // op-only read surface, preserved (§9.7)
        ),
    )
)
```

**and `ModuleConfiguration.service(interfaceClass, serviceSchema, roFactory)` walks
`serviceSchema.relationSchemas` and registers what it finds** — both the `ServiceDataRelation`
and the maintenance entry. Consequences:

- **`relationMaintainer` disappears.** No DSL verb names an observer; nothing a module author
  writes contains the word "maintainer". Declaration is a description of *what the relation is*,
  and maintenance is derived from it — which is what was asked for.
- **⚠ It also removes a duplication that predates this proposal entirely.** `ModuleConfiguration.service(...)`
  today registers only the service *type*; it does **not** walk `relationSchemas`. That is why every
  `*CommonModule` separately calls `serviceDataRelation(fromId, targetId, ServiceDataRelation.create(...))`
  restating what the service schema already said. So the true count is: a relation is declared
  **five** times today (interface property · service schema · common-module registration · client
  `ServiceRo` accessor · proxy maintainer), R1 as merged still leaves five, and this collapses it
  to **two** — the typed interface property, and the schema declaration. §9.7's read-side work
  (`chainReadable` + `ServiceRo.serviceRelation<E>`) is the same collapse from the other end, and
  the two now meet.
- **`chainReadable` is where it belongs.** It is the flag that decides whether the module
  registers the chain reference at all — i.e. exactly the choice the `IdentityService.Relations`
  KDocs make in prose today ("*Dedicated-op read surface […] so this stays a plain array-key
  id*"). Four identity relations have no `serviceDataRelation` at all right now, purely because
  the write side had nowhere else to live; under this shape they are declared like any other
  relation with `chainReadable = false`.

**What this does *not* reach, stated plainly:** entity-scoped relations. `Folder.FILES` is a list
*field* on a composite schema and `File.FileHistory` is a *property* relation — neither is a
service relation, so neither is covered by `ServiceSchema.relationSchemas`. They declare at their
own sites, and the `parentOf` question (D2) splits usefully there:

- `File.FileHistory`'s parent is derivable from a **field** of the element
  (`FileModification.fileId`) ⇒ declarative: `parentKeyField = FileModification.Field.FILE_ID`.
- `Folder.FILES`'s parent is derived from the child's own key by path surgery
  (`path.substringBeforeLast("/")`) ⇒ genuinely code, and irreducibly so.

**So 29 of 30 relations become purely declarative, and exactly one keeps a lambda** — a materially
better answer than D2's original "2 of 30".

**Cost of the correction.** It reaches further than R1's module-config approach: it touches
`:core`'s schema DSL and `ServiceSchema`, and `MembershipOrder` moves from `:core-common` to
`:core`. Still no wire change (all added metadata is code-side per the `version` precedent), no
fmt bump, no relay redeploy. R1 was **not merged** when this was decided, so revising it was strictly cheaper than
shipping the module-config API and migrating off it later.

**One observer, registered once** in the core proxy init:
`RelationMaintainer(executionContext, relationRegistry) : DataObserver`, holding a
`Map<elementTypeId, List<RelationSpec>>` index built at registration. Today a single `saveData`
fans out to ~30 observers that each re-run a type check; after this it is one indexed lookup.

**Readers benefit too.** `ErrorEventRecorderImpl`, `ContactInviteLocalStore`, `RealmKeyring`,
`GrantContactDirectory`, `GetRealmMembersImplementation`, `GetSharedRealmMembershipsImplementation`
and friends each hand-build the same `ArrayDatabaseReference`. A
`RelationRegistry.referenceFor(relationId)` / `.referenceFor(spec, parent)` removes the
duplication and, more importantly, removes the chance of a reader and a writer disagreeing
about `isEntity` or `elementSchemaId`.

**Deliberate exclusions — these are not relation maintainers and must not be folded in:**

- `LatestSampleMaintainer` (`:health-service-proxy`) is a **rollup/aggregate**, not membership:
  it computes a derived row (`latest per (metric, resting)`) with a newer-wins comparison. It
  keeps its own observer.
- `FileModificationRelationMaintainer` splits: its *membership* half collapses into the generic
  spec, its **retention cap** (§4.6) and **tombstone cascade** (§4.4) stay as a separate
  observer. That split is an improvement on its own — those two policies are currently
  entangled with array bookkeeping in one class.
- `AppRelationMaintainer` / `PreferenceRelationMaintainer` / the notification pair each serve
  **two** relations from one class; they become two `RelationSpec` registrations.

**No wire change, no schema change, no `TENSHU_STORAGE_FORMAT_VERSION` bump, no relay
redeploy.** The array rows it writes are byte-identical to what the hand-written observers
write. This is the piece that can land next week.

### 9.3 Half B — curated relations: membership as an ordinary synced row

The maintainer's KDoc, and §5(a), assume the answer is a relation-mutation **wire type** with a
new CRDT merge rule. **Recommendation: don't build one.** Membership is already expressible as
a `Data` row, and every mechanism it needs already exists.

```kotlin
// :core-common — Syncable(Eager), DataIdProvider on `compositeKey`
interface RelationMember : Composite {
    val compositeKey: TString   // "$relationKey|$memberKey" — the DataIdProvider key
    val relation: DataId        // the array's own ReferencedDataId (Relation/EntityField/EntityProperty)
    val member: DataId          // the element's identity-root DataId
    val removed: TBoolean       // per-member tombstone-as-field
}
```

Then:

- **Emission is free.** `saveData(RelationMember(...))` is a `Syncable` save, so `SyncObserver`
  already serialises it, seals it under the realm key with the E0 stamp, routes it to the
  entity's realm (`realmForDataId` — a `"$realmId::"`-prefixed relation id routes to the shared
  realm by construction, exactly like E2h's epoch row) and queues it. Zero new emit code.
- **Merge is free, and it is the right CRDT.** Whole-entity LWW on a row whose only mutable
  field is `removed` **is** a per-element LWW-Element-Set. It converges under any delivery
  order, is idempotent under redelivery, needs no election and no quorum — §4.5 satisfied by
  the merge rule the codebase already runs, not by a new one.
- **The per-member tombstone is the row itself.** `removed = true` at an HLC is exactly the
  state a late re-add must lose to. No `DeleteBlock`, no resurrection window, and the surviving
  row is prunable at whatever floor E4 eventually advances.
- **Ingest is free.** The row lands via `resolveConflictAndSave`; the generic maintainer of
  §9.2 sees the save (`origin = SYNC_INGEST`) and applies `add`/`remove` to the local array.
  Membership therefore *reconstructs from data blocks as they synchronise* — which is the
  requirement, and it is the same derive-on-save loop, just fed by an explicit row instead of
  an implicit rule.

**The two membership modes:**

```kotlin
sealed interface Membership {
    /** Membership is a pure function of element state. No rows minted, no blocks. 30/30 today. */
    data object Derived : Membership
    /** Membership is explicit. `addToRelation`/`removeFromRelation` mint `RelationMember` rows. */
    data object Curated : Membership
}
```

⚠⚠ **Derived relations must never mint `RelationMember` rows, and this is a hard cost
constraint, not a preference.** One row per membership edge means a 10 000-file folder pays
10 000 extra synced rows to encode a fact each device can compute for free from the file's own
path. The rule: *if a derivation exists, derive.* Sync membership only where no derivation
exists — the relation is a user's or an agent's decision rather than a consequence of entity
state. That is also why B is a capability rather than a fix: **all 30 relations today are
derived, so none of them changes.**

**What Curated unlocks** (the reason to build it at all): albums and playlists; tags; a room →
device assignment in the smart-home service; Mia's entity groupings, which are curated by
construction — an inference decides a set of photos belongs together and there is no field on
the photo that says so. Today each of those would have to invent a join-row type and a
maintainer by hand, which is how we got 30 maintainers in the first place.

**Cost of the primitive:** one new `Syncable` composite. Per HR #9 that is a
`TENSHU_STORAGE_FORMAT_VERSION` bump in the same PR + client lockstep; per E2e it owes **no**
relay redeploy. A pre-B peer parses the unknown type to `null` and drops the block — same
receiver posture as `DeltaBatchPayload` (TS-1), same reason the bump is not optional.

### 9.4 Durability — the content-plane snapshot (the part that answers §4.1)

Neither half above survives "store wiped on every replica simultaneously **and** relay history
gone", because both ride the ephemeral delivery log. The piece that does is the one the user's
own framing points at: **treat a relation like file content — a manifest on the content plane.**

`retention-quota-model.md` establishes that relay blobs are a *different tier* from blocks:
durable master copy, `ownerId`-keyed, **account-lifecycle reclaim only, never time-reaped**, and
untouched by E4 compaction. So:

- Any device holding a relation may PUT a **`RelationSnapshot`** — the sealed list of current
  member `DataId`s — to the content plane under a ref derived from the relation's identity.
  Re-emission is idempotent and convergent, so races cost bandwidth and not correctness (the
  §8.3 compaction posture, applied unchanged).
- A device with an empty relation and no history GETs the snapshot and rehydrates. That is a
  genuine answer to §4.1 for *curated* relations, and it composes with §9.2 for derived ones
  (a derived relation needs no snapshot — it needs its elements, which is a different problem).
- Debounced rewrite (a snapshot per membership change would be O(n) per edit); the natural
  trigger is the same idle+charging `PlatformScheduler` maintenance slot compaction will use.

⚠⚠ **Ref-derivation privacy trap — do not copy `manifestContentRef` naively.** That helper is
safe *because* `contentId` is a random per-file id, so `sha256("manifest:$contentId")` is
unguessable. Relation ids are **public compile-time constants** (`tenshu/identity/devices`,
`tenshu/fit/workouts`). A ref derived from one would let the relay probe `GET
blob-sha256("relation:tenshu/fit/workouts")` per owner and learn **which apps the user runs** —
precisely the leak `core-schema-evolution.md` refused when it kept per-service versions out of
the relay's view. The snapshot ref must therefore be salted with a realm-private value (an HKDF
subkey off the realm key), and that is a **threat-model amendment under HR #2**, not an
implementation detail.

⚠ Second-order: a snapshot's *size* leaks the relation's cardinality to the relay. Pad to a
bucket, or accept and document it — the relay already learns block counts.

### 9.5 What this design does and does not meet (against §4)

| §4 requirement | Verdict |
|---|---|
| 1. Membership survives a simultaneous wipe | **Partly, and only via §9.4** — curated relations rehydrate from the content-plane snapshot; derived relations still need their *elements* back, which is the entity-recovery problem, not the relation problem. |
| 2. Revocation must not depend on an emptiable cache | **No.** `RevokeDevice` targets the local `Device` entity, and `Device` membership is derived. Unchanged by this design; the tier-1 `reconcileOwnDeviceRow` self-heal remains the answer. |
| 3. Preserve admission lineage | **No.** `admittedByDeviceId` is a field on `Device`, not a membership fact. Q3 stands. |
| 4. Replace the tombstone-count epoch derivation | **No.** Q2 stands and is independent. |
| 5. Converge without a coordinator | **Yes**, by construction — whole-entity LWW on `RelationMember` is a per-element LWW-Element-Set (§9.3). |
| 6. HR #1 intact | **Yes for A and B** (nothing new reaches the relay; a relation-addressed block is one more opaque ciphertext). **⚠ Not free for §9.4** — see the ref-derivation trap; amendment owed. |
| 7. Positional-composite discipline | **A: n/a** (no new type). **B: one new type ⇒ fmt bump + lockstep**, no relay redeploy (E2e). |

**Honest summary: this design is a large structural win and a new capability; it is not the
recovery fix.** §7's conclusion holds — the recovery-side action item is the **E4 floor
constraint** (compaction must not advance past the last emission of a row type nothing
re-emits), and §9.4 is what would let a floor advance safely for curated relations.

### 9.6 So what *does* fix §4.1?

§4.1's failure condition is precise (§7): **local stores empty on every replica AND relay block
history gone.** Asking what fixes it is really asking *what survives that*, and the answer is a
short, verifiable list — three things, none of them a relation:

1. **Client key material outside the object store.** A4 / A13 / the unlock wrapper live in
   `shared_prefs` / Keychain / `MasterKeyStore`, not the store. This is exactly why the standing
   remedy is `adb install -r` and never uninstall: identity survives, the store self-wipes.
2. **Relay state that is not the block mailbox.** `KnownDevicesStore` (per-realm device keys —
   and it already exposes `deviceIds(ownerId)`, described in its own KDoc as *"the relay's
   authoritative view of which devices are currently in this realm"*), `RecoveryBlobStore`
   (asset A9), the grant / invite / share registries, and the **content-blob tier** — whose
   `CONTENT_RETENTION_WINDOW_MS` is `Long.MAX_VALUE` (the content plane is durable), i.e. never time-reaped.
3. Nothing else.

Everything that fixes §4.1 therefore reduces to *routing the irreplaceable onto one of those*,
or to *not creating the condition in the first place*. Four levers, in descending order of
leverage:

**L1 — stop manufacturing the simultaneity (the biggest lever, and half-built).**
"All replicas simultaneously" is not a natural disaster: **HR #9 lockstep manufactures it.** A
core-version mismatch still calls `wipeAndStampCurrent()` (`StoreSchemaMigrator`,
`SqliteObjectStore`'s `PRAGMA user_version` gate), and lockstep guarantees every replica does it
in the same window. E2b built the machinery to *migrate instead of wipe* — the core version is
the one gate that still wipes. Close it and the premise of §4.1 dissolves: each replica migrates
in place, and relay history is not even consulted. This has to happen anyway — **post-release we
cannot ship a wipe at all** — so §4.1 is, viewed correctly, a *pre-release-only* failure mode
with an already-scheduled owner. **⚖️ This, not the relation system, is the fix for §4.1.**

**L2 — the E4 floor constraint** (§7 already named it): compaction may not advance the floor
past the last emission of a row type nothing re-emits. A scheduling predicate, not a mechanism.

**L3 — the actual invariant: every durable row needs a producer or a durable home.** The defect
was never "relations aren't synced"; it is that **some rows have no producer**. A `File` row has
one (the bytes are on disk; a rescan re-emits). `Device.create` has five call sites and none
re-runs spontaneously. State it as a rule and audit against it:

> Every durable row must have either **(a)** a producer that can re-run from surviving
> authority, or **(b)** storage outside the ephemeral block log.

Auditing §7's five against that rule is what makes the work concrete — and it splits them
cleanly:

| Row | (a) producer? | Verdict |
|---|---|---|
| `PendingGrant` | **yes** — `PollPendingGrants` re-fetches from the relay grant mailbox | already compliant; nothing to do |
| `Device` (own row) | **yes** — `reconcileOwnDeviceRow` re-mints from A4 (shipped) | compliant |
| `Device` (peers' rows) | no | needs (b), or an endpoint over `KnownDevicesStore.deviceIds` — the relay **already holds this** |
| `Member` / `SharedRealmMembership` | only by another party's action (owner re-grant) | not self-service; (b) preferred |
| `RetainedRealmKey` | **never can** — §7: after the rotation the retired `Kr` exists nowhere else | **must be (b)**; the only true-data-loss case |

**L4 — escrow the irreplaceable, using a tier that already exists.** For the (b) class there
are two durable homes, and ⚠ **the purpose-built one is currently unfed**: the relay serves
`POST`/`GET /sync/recovery-blob` with a monotonic `manifestVersion` (asset A9,
`RecoveryRouting.kt:141`/`:220`, `SqliteRecoveryBlobStore`) and **no production client ever
PUTs one** — `RestSyncRelay` has `recoveryChallenge` and `recoveryClaim` but no blob upload.
So the channel designed for exactly this class of material is built, durable, replay-protected,
and empty. `RetainedRealmKey` is precisely A9-shaped. ⚠ *Removed by F8 (2026-10-28)*: the routes
and table are gone; L4 would have to re-add an ownerId-blind channel with its own threat-model
amendment (threat model A9). The content plane (§9.4) is the second
home, better suited to bulkier or per-relation snapshots.

**What is not fixable, and should stop being tracked as if it were.** Admission lineage
(§4.3 / **Q3**) is a fact about a past event with no surviving witness — neither key material
nor relay state records *who admitted whom*. For realms that already lost it, it is gone;
it re-establishes only at the next pairing. **Q3's answer is "no — accept it."**

**Ranking for anyone picking this up:** L1 ≫ L4 (for `RetainedRealmKey`, the one true
data-loss case) > L2 > L3-remainder. Note that **none of these is Half A or Half B** — which
is the honest reason §9.5's first row says "partly": the relation system is worth building for
what §9.1 column "payoff" says it buys, and it should not be scheduled as the recovery fix.

### 9.7 What "adding a relation" costs after this — the read side is the remaining half

The write side does become free: **one `RelationSpec` registration, no observer, no code.** That
is the entirety of what the 30 maintainers do today. But "works out of the box" is only true of
half the surface, and the honest accounting matters for scheduling.

**Today one relation is declared four times.** Taking `NotificationService.NOTIFICATIONS`:

| # | Where | What |
|---|---|---|
| 1 | `NotificationService` interface | `val notifications: TList<Notification>` |
| 2 | `NotificationService.Schema` | `serviceDataRelation(id = …, type = TList.typeOf(Notification.Schema))` |
| 3 | `NotificationServiceCommonModule` | `registerServiceDataRelation(fromId, targetId, relation)` |
| 4 | `NotificationServiceRo` (client) | 12 lines: `addReference(id).addReference(relationId)` → `roProvider().createRequestObject(listType(Notification.Schema), …)` |

…plus (5) the proxy-side maintainer §9.2 deletes. Five declarations of one fact, of which the
proposal as written removes one.

**Two of the remaining four collapse into the same registration, for free:**

- **#2 + #3 derive from the spec.** A `RelationSpec(elementTypeId, RelationScope.Service(relationId))`
  already carries every input `ServiceDataRelation.create(from = serviceId, to = relationId,
  type = TList.typeOf(elementSchema))` needs. Add `serviceId` and a `chainReadable: Boolean` to
  the spec and the registry emits the `serviceDataRelation` itself. ⚠ The flag must be
  **opt-in, not implied**: several relations deliberately have *no* `serviceDataRelation` and
  are read through a dedicated op instead (see the `IdentityService.Relations` KDocs —
  "Dedicated-op read surface […] so this stays a plain array-key id"). Today that choice is
  implicit in whether someone remembered to add the registration; the flag makes it a decision.
- **#4 collapses to one line.** The client accessor body is pure boilerplate over
  `(serviceId, relationId, elementSchema)`. A `ServiceRo.serviceRelation<E>(relationId,
  elementSchema)` helper reduces it to
  `override val notifications get() = serviceRelation(Relations.NOTIFICATIONS, Notification.Schema)`.
  ⚠ This is worth doing for correctness, not just brevity: hand-writing the `listType(...)` /
  `roProvider()` pairing is a known footgun class in this codebase (the `Workout.Streams` and
  TS-4 incidents — a client-module RO registration that looks fine and dies as an opaque
  `error/internal` at read time). One helper, one place to get it right.

**#1 stays hand-written, and should.** KMP has no codegen here, and callers want the typed
`TList<Notification>` property. That is the declaration you actually want to write.

**So the end state is: a new derived relation = one typed interface property + one
`RelationSpec`.** Everything else — maintenance, ordering, deletion, the chain read binding,
the client accessor — falls out. That *is* "out of the box" in the sense asked, but it is worth
naming the three things that remain per-relation regardless of any machinery:

1. **The element type still needs its own registration** — a `DataIdProvider` trait (the spec
   leans on it entirely, §9.2) and, if it should replicate, `Syncable`. That is type
   registration, not relation machinery, and it is not removable.
2. **`EntityField` / `EntityProperty` scopes still need a `parentOf` lambda** (D2) — 2 of 30
   today. Code, not data.
3. **A brand-new element *type* is a new schema type ⇒ HR #9 fmt bump + lockstep.** The
   *relation* costs no bump; the type it holds does. Easy to conflate.
4. **A curated relation (§9.3) additionally needs a write op** — `addToRelation` is a user's or
   an agent's action, so it needs an operation surface regardless. Only the *plumbing* under it
   is generic.

---

## 10. Phasing

⚠ **REVISED 2026-07-28** after the §9.2.1 / §11.1 / §11.2 review. Two structural changes: R1
absorbed half of R2's read side (the schema shape forces it), and the D3 behaviour change was
split out of R2 into its own slice, because R2's whole claim is "nothing changed" and D3's is
"something did". Each phase is independently shippable and useful without the next.

| Phase | Scope | Wire / fmt | Gate |
|---|---|---|---|
| **R1 — SHIPPED 2026-07-28 (§10.1)** | **(grew — §9.2.1)** `RelationScope` / `MembershipOrder` / `RelationRegistry` / one `RelationMaintainer`; declaration on the **schema** — `serviceDataRelation(id, type, order, chainReadable)` as code-side metadata — and `ModuleConfiguration.service(...)` walking `relationSchemas` to register both the `ServiceDataRelation` and the maintenance entry. `MembershipOrder` lives in `:core`. Migrate **two** modules: `:timeline-service-proxy` (proves the shape by *deleting* `StringKeyedRelationMaintainer`) and `:identity-service-proxy` (5 maintainers, best existing coverage), deleting only *their* now-redundant manual registrations. | none (all added metadata is code-side, `ServiceSchema.version` precedent) | existing `DeviceRelationMaintainerTest` / `SharedRealmMembershipRelationMaintainerTest` pass **unchanged** — the regression gate. ⚠ `chainReadable = false` must register **no** chain reference (else the four op-only identity relations gain a public surface). |
| **R2 — SHIPPED 2026-07-29 (§10.2, audit §10.3)** | Sweep the remaining 12 modules + delete their manual `serviceDataRelation` registrations. Split `FileModificationRelationMaintainer` into membership-spec + a retention/cascade observer. `ParentFilesRelationMaintainer` → `EntityField` scope. Leave `LatestSampleMaintainer` alone (a rollup). Remaining read side: the `ServiceRo.serviceRelation<E>(…)` helper (§9.7). **Deliverable, not a change: the D3 audit** — all 30 relations' read sites, checking for raw-order consumption (`getArrayDataWindow` paging, `.take(n)` with no preceding `sortBy`). | none | per-module `desktopTest`; `FolderSyncSystemTest`, `FileSyncSettingsRelationSyncSystemTest`, `ShareSyncSystemTest`; **Android smoke gate** (HR #8 — DI wiring moves) |
| **R2b — SHIPPED 2026-07-29 (§10.4)** | Acted on R2's audit: `MembershipOrder` **deleted**, probe-first unconditional, and the pointless `removeFromArray` skipped when the member is absent. ⚠⚠ Needed one thing the audit did not see — the `remove`+`add` pair was also the **only change-notification** a reactive read of a relation ever got, so probe-first had to come with `Database.notifyArrayMemberUpdated`. ⚖️ Also the cheap half of the tech-debt entry *"Relation Arrays Are Full-Row Rewrites"* — steady-state saves now write nothing. | none | before/after write counts + ordering, `./gradlew desktopTest jvmTest test`, Android smoke gate |
| **R3** | `RelationMember` + `Membership.Curated` + `addToRelation`/`removeFromRelation`. Carries `ordinal` (fractional index) if the first consumer needs explicit position (§11.1). First consumer decides the shape — do **not** land this without one. | new `Syncable` type ⇒ **fmt bump + lockstep**; no relay redeploy | a two-device system test in `:system-test-utils`: curate on A, converge on B, concurrent add/remove resolves by HLC on both |
| **R4** | `RelationSnapshot` on the content plane + salted ref derivation + rehydrate-on-empty. **Threat-model amendment lands in the same PR** (HR #2). | content-plane ref (new relay-visible metadata) | wipe-one-device system test: rehydrate a curated relation with the block history withheld |

**Sequencing.** R1 → R2 → R2b is a strict chain (R2b needs R2's audit; R2 needs R1's mechanism).
R3/R4 are independent of all three and should not gate them. R3 waits for a real consumer — the
first curated relation in the product (a Mia entity grouping, a home-service room assignment)
— because "what does a curated relation need" is a question a consumer answers better than a
design doc. R4 is the only phase touching the threat model.

**Three things that are deliberately NOT phases here**, because scheduling them under this
proposal would put them behind the wrong dependencies:

- **Declared `orderedBy` + sort elision (§11.1/§11.2).** Its sound form *is* `core-ordered-window-fusion.md`'s
  **F3** (a maintained key→DataId index); without F3 it is an unenforced invariant, and it fights
  probe-first membership besides. It belongs to that proposal as a consumer, not to this one as a
  phase. ⚠ Whoever schedules F3 should read §11.2 first — the collision with insertion-ordered
  arrays (R2b, §10.4) is not obvious from the fusion doc alone.
- **The O(n)-per-change array representation** (append/remove journal or chunked member pages).
  Recorded in `tech-debt.md` *"Relation Arrays Are Full-Row Rewrites"*; R2b takes the cheap half
  only and explicitly does not change the complexity class.
- **The §4.1 recovery levers (§9.6)** — closing the core-version store wipe, and escrowing
  `RetainedRealmKey`. Neither is relation work: the first belongs to
  `core-schema-evolution.md` (E2b already built migrate-instead-of-wipe), the second is a
  standalone item (the A9 recovery-blob channel it would have used was removed by F8, 2026-10-28).

### 10.1 R1 as built (2026-07-28)

Shipped in the **§9.2.1 schema shape**, not §9.2's original module-config shape — the first cut
introduced a `relationMaintainer(spec)` DSL verb and was sent back in review (D1 re-settled). 8
relations across 2 modules now run on one observer; the other 12 modules are untouched and both
mechanisms coexist. No wire, schema, stored-byte or `TENSHU_STORAGE_FORMAT_VERSION` change; the
arrays written are byte-identical.

Declaration is `serviceDataRelation(id, type, order = …, chainReadable = …)` on the service
schema, with `order`/`chainReadable` as code-side metadata (never serialized — the
`ServiceSchema.version` precedent). `ModuleConfiguration.service(...)` walks
`ServiceSchema.relationSchemas` and derives both the `ServiceDataRelation` and the maintenance
entry. `MembershipOrder` lives in `:core` so the schema DSL can name it. Machinery in
`:core-common` (`org.tenshu.core.common.relation`): `RelationSpec` / `RelationScope`
(`Service` · `EntityField` · `EntityProperty`) / `RelationRegistry` / `RelationMaintainer`.

Deltas worth carrying forward:

- **`order = null` is the coexistence hinge.** The default means "a hand-written observer still
  owns this relation", which is what lets the 12 unmigrated modules keep working. The walk is
  additive and non-clobbering — `serviceDataRelations` entries are filled in, never overwritten,
  so a hand-written registration wins in either order.
- ⚠ **A real limit of §9.2.1, found in implementation:** the *single-entity*
  `serviceDataRelation` form (`FileSystem.ROOT` — `type = Folder.Schema.toType()` plus a concrete
  `dataId`) needs a `dataId` the schema does not carry, so it **cannot** be schema-derived and
  keeps its hand-written registration permanently. Not a phase boundary; a permanent carve-out.
- ⚠ **`isSubtypeOf`, uniformly — a deliberate behaviour improvement, not a like-for-like port.**
  Four identity maintainers compared `type.get() != X.ID`, which drops a subtype whose rows key
  under the *supertype*'s identity root. R1's 8 relations have no subtypes, so nothing observable
  changed; R2's `File`/`Folder` pair is where it starts mattering.
- **`chainReadable = false` is pinned by a test**, not by convention: the four op-only identity
  relations (`SHARED_REALM_MEMBERSHIPS`, `MEMBERS`, `PENDING_GRANTS`, `RETAINED_REALM_KEYS`) are
  now *declared* like any other relation, so without that assertion they would silently gain a
  public chain read surface they deliberately do not have.
- ⚠ **§9.2's `parentOf` lambda is under-specified for the delete path.** A delete carries only a
  `DataId`, never a row, so one `(ExecutionContext, Data) -> DataId.EntityDataId?` cannot mean
  "read a field off the element" on both paths. Resolved by feeding the lambda the element's own
  `DataId` on delete (a `DataId` *is* a `Data`) — what `ParentFilesRelationMaintainer` already
  does by hand. ⚖️ Checked against both future consumers and the contract holds: `Folder.FILES`
  derives the parent via `keyValue.substringBeforeLast("/")` and `File.FileHistory` via
  `keyValue.substringBefore("|")` — both from the key, neither from a field. **R2 confirms it on
  the real migration.**
- **Installation is `ExecutionContext.installRelationMaintainer()`, idempotent**, called from
  each migrated `init<X>Service()` rather than once in the core proxy init: the gate tests call
  `initIdentityService()` on a bare `TestProxy` and cannot move. ⚠ Consequence: the maintainer
  serves **every** registered module's specs, not only those whose `init` ran — benign (a spec
  whose types are never saved never fires; array writes don't fan out to observers, so nothing
  extra syncs) but worth knowing before R2 widens the spec set.
- **Corrected a stale KDoc** claiming `ApprovePairingRequest` writes the DEVICES array directly.
  It does not, and has not for some time — the ops were cleaned up because two writers duplicate
  entries (`CreateRealmImplementation` documents it). The text read as licence to add a second
  writer, which would also break the single-writer precondition §11.2 depends on.
- `RelationRegistry.referenceFor(...)` exists but has **no reader consumer yet** — folding the ~6
  hand-built `ArrayDatabaseReference` reader sites onto it is D4/R2.
- **D3 deferred**, per §11: `order` is explicit at all 8 sites and nothing changed behaviourally.
  `MembershipOrder.Stable` and both entity scopes ship with **no production consumer** (they
  arrive in R2), so they are pinned by `RelationMaintainerTest` rather than by a migration.

Gate: `DeviceRelationMaintainerTest` (3) + `SharedRealmMembershipRelationMaintainerTest` (3) pass
**byte-for-byte unchanged** — zero diff against `origin/master` — plus `RelationMaintainerTest`
(15) and a full `./gradlew test` (1206 tasks, green).

### 10.2 R2 as built (2026-07-29)

The sweep landed as designed for 34 of 37 derived relations across 13 modules; the three
that did not are findings about §9.2/§9.2.1, not workarounds. **No wire, schema, stored-byte
or `TENSHU_STORAGE_FORMAT_VERSION` change** — the arrays written stay byte-identical.

⚠ **One existing assertion had to be re-scoped, and it is worth knowing why.**
`RelationMaintainerTest.registryIndexesTheDeclaredSpecs` asserted `registry.specs.size == 3`
over the *whole* test registry. That registry includes `coreCommonModule`, so migrating
`AppService` legitimately moved the number to 5. It now counts the test service's own
relations — which is what it was always about. Nothing was weakened; every other assertion in
the repo passes byte-for-byte, including all five R1 gate suites.

**Entity-scoped relations needed a declaration site, and §9.2.1 did not name one.** It says
only that they "declare at their own sites". They cannot declare on a schema: `Folder.FILES`
is a list *field* and `File.FileHistory` a *property*, so `ServiceSchema.relationSchemas`
cannot reach them, and the parent an element belongs under is derived by key surgery, which is
irreducibly code. R2 adds one DSL verb, **`ModuleConfiguration.relationMembership(spec)`**,
used by `fileServiceCommonModule` for all three of its entity-scoped relations. ⚠ This is
*not* the `relationMaintainer(spec)` verb the §9.2.1 review rejected: that one re-declared
facts a service schema already carried, and named an observer. This one declares the only
facts that exist nowhere else, and names the relation's *membership*.

**⚠⚠ §10.1's `parentOf` delete-path contract holds for the two consumers it checked, and
fails for a third it did not know about.** `Folder.FILES` (`keyValue.substringBeforeLast("/")`)
and `File.FileHistory` (`keyValue.substringBefore("|")`) both derive the parent from the
element's own key, exactly as predicted — and both got *simpler* than the hand-written code,
because `ExecutionContext.getDataId` returns a `DataId` unchanged, so one expression serves
save and delete. But **`SharedFile`'s children tree derives its parent from a *field*
(`parentFileId`) while the row's own key is `(realmId, fileId)`** — the field is not
recoverable from the key, so the single-lambda shape genuinely cannot express its delete path.
It migrates cleanly today only because `SharedFileRelationMaintainer.onDataDeleted` was
*already* a deliberate no-op (`SharedFile` removal is that proposal's Phase 4), and returning
`null` for a bare `DataId` reproduces that exactly. **So the honest statement is: the contract
holds for every relation that has a delete path, and the one relation whose parent is
field-derived has no delete path yet.** When Phase 4 lands it needs either resolve-before-delete
or §10.1's two-lambda fix — recorded at `SharedFileChildrenMembership`'s declaration.

**Two relations are not membership and stayed hand-written** — one anticipated, one not:

- `LatestSampleMaintainer` (`:health-service-proxy`), as §9.2 required.
- ⚠ **`FileSyncSettingsRelationMaintainer` (`:file-service-proxy`), which §9.2 did not
  anticipate.** Every relation the generic mechanism serves mirrors *the saved entity* into an
  array. This is a **2→1 fold onto a different type**: two `DeviceSetting` rows fold into one
  `FileSyncSetting` projection row, re-read from the DB on every save/delete of either. No
  `RelationSpec` can say that, and none should — it is the same class of thing as the health
  rollup. Two of 37, not one.

**A second permanent carve-out from §9.2.1, beside `FileSystem.ROOT`'s single-entity form:
cross-service relations.** `FileSyncSetting.RELATION` is declared from `:file-service-common`
but hung off `PreferenceService.ID`. A walk of `PreferenceService.Schema` cannot see a relation
another module contributes, so it keeps its hand-written
`serviceDataRelation(fromId, targetId, …)` registration permanently.

**`FileModificationRelationMaintainer` split as designed**, and the split is cleaner than
expected: the surviving `FileHistoryPolicyObserver` owns only the retention cap (§4.6) and the
tombstone cascade (§4.4), and it **touches no array at all**. Both policies work by
`deleteData(<FileModification>)`, which fans out to the generic maintainer, which drops the row
from `MODIFICATIONS` *and* that file's `File.FileHistory` — so the two per-file bookkeeping
branches the old class carried simply disappeared. Observer order is preserved by construction:
`installRelationMaintainer()` runs before `addDataObserver(FileHistoryPolicyObserver(...))`, so
retention still counts the array *after* the triggering row joined it.

**`Folder.FILES` is `MembershipOrder.Stable` and `RelationScope.EntityField`** — R1 shipped both
with no production consumer; this is it. Not a behaviour change: the hand-written maintainer
already probed-then-rebuilt.

**Read side (§9.7 / D4).** `ServiceRo.serviceRelation<E>(relationId, elementSchema)` collapses
**36 accessors across 16 client modules** from ~12 lines to one. Eight hand-built
`ArrayDatabaseReference` reader sites now derive from the writer's own spec via a new
`ExecutionContext.relationReference(relationId)` (`ContactInviteLocalStore`,
`GetRealmMembersImplementation`, `GetSharedRealmMembershipsImplementation`,
`GrantContactDirectory`, `RealmKeyring`, `ListPlacementsImplementation`, and both
`SharedRealmGrantManager` readers). ⚠ It **fails loudly** on an undeclared relation id rather
than returning `null`: reading an empty array is indistinguishable from the truth at the call
site, which is the failure mode the helper exists to remove. Two sites keep hand-built
references and must: `LatestSampleMaintainer`'s `LATEST_SAMPLES` and
`FileSyncSettingsRelationMaintainer`'s array, neither of which has a spec to derive from.
⚠ `ErrorEventRecorderImpl` was on §9.2's D4 list but builds no reference at all — it saves the
entity and lets the observer do the rest.

**⚠⚠ The D3 audit falsifies §11 D3's hypothesis. See §10.3.**

### 10.3 The D3 audit — verdict: the hypothesis is FALSE

§11 D3 predicted "only `Folder.FILES` observes raw order; every other consumer sorts or keys
downstream", and made `MembershipOrder`'s deletion contingent on it. **Every read site of all
~40 relations was checked in R2. The prediction does not hold.**

The narrow claim survives: **index-sliced paging** over a relation array happens in exactly one
place. Repo-wide, `getArrayDataWindow` has one production caller path (`EvaluateCallstack`'s
drop/take fusion) and the only unsorted chain reaching it is
`TenshuFilesRepository.getFilesPaged(folderPath)`. But D3's own second criterion — *UI rendering
the list in array order* — catches **at least ten more relations**:

| Relation | Raw order observed? | Where |
|---|---|---|
| `Folder.FILES` | **YES** (paging) | `TenshuFilesRepository.getFilesPaged(folderPath)` → `getArrayDataWindow`. Web Drive sorts client-side, so it does not. |
| `HomeDeviceService.HOME_DEVICES` | **YES — strongest case** | `SyncedDeviceAwareHomeDevicesRepository` writes the array index into `Device.sortIndex`. Array position promoted to a model field. |
| `IdentityService.DEVICES` | **YES** | `SyncDashboardScreen` `items(devices)` and web `DevicesListPage`; no sort at any layer, on either platform. |
| `IdentityService.MEMBERS` | **YES** | Manage-access roster: filter → `items(...)`, no sort. |
| `IdentityService.SHARED_REALM_MEMBERSHIPS` | **YES** | Drive *Shared with me* listing, native + web. |
| `IdentityService.PENDING_GRANTS` | **YES in Drive**, no in Control | Drive's pending cards unsorted; Control sorts newest-first. |
| `FileService.PLACEMENTS` | **YES** | *Shared folders* listing, native + web. |
| `FileService.SHARES` | **YES in Drive**, no in Control/SDK | Control's `ShareManager` even comments that it sorts where Drive does not. |
| `ContactService.PERSONS` | **YES** | Contacts dialog + grant picker render in array order. |
| `ContactService.CONTACT_INVITES` | **YES in Drive**, no in Control/SDK | Same split as `SHARES`. |
| `PlaceService.PLACES` | **YES** | Maps *Places* tabs; no sort anywhere in `maps-shared-data`/`-logic`. |
| `SharedFile` children | **YES, twice** | Shared-folder browse listing, *and* `wrapperName()` = unsorted `firstOrNull()` — the realm's displayed name is whichever child sits first. |
| every other relation | NO | Sorted (`TimelineProjection`, `FitProjection`, `ErrorEventsManager`, `BackgroundIngestManager`, Control's feeds), keyed into a map, or reduced (`maxByOrNull`, `.toSet()`). |

⚠ Three relations have **no read site at all**: `RoutineService.ROUTINES` / `ROUTINE_RUNS`
(write ops only) and `FileService.MODIFICATIONS` (production migrated to `File.FileHistory`;
only `:file-service-proxy` tests read it).

⚠ Ambiguous, flagged not decided: `HOME_DEVICES`/`HUB_COMMANDS` command application *sequence*
(every element is processed and deduped by id, but ordering shows in `lastCommandId`);
`TENSHU_IDENTITIES`' `keyring.first()` tie-break within a per-person group; `APP_GRANTS`' no-manifest
fallback branch; `STATUSES`' `firstOrNull()` (vacuous — the relation is single-row by construction).

**The structurally interesting finding is the split.** `SHARES`, `CONTACT_INVITES` and
`PENDING_GRANTS` are order-invisible to Control and the JS SDK, which sort them, and
order-visible to Drive, which does not. That is a per-consumer omission, not a property of the
relation — which undercuts §11.1's proposed reframing (`orderedBy = <field>` as "does this
relation have an intrinsic order?"): for these three, the relation alone cannot answer.

**Bearing on R2b — unchanged, but for a different reason.** Nothing in the audit *relies* on
`MostRecentLast`, and no read site would be worse under `Stable`. In fact §11's own argument
applies with more force: `Stable` is strictly better wherever order *is* visible, because every
one of the eleven order-observing consumers today renders a list that reshuffles on an unrelated
re-save. So R2b stays safe to land; its justification changes from *"29 of 30 don't care"* to
**"≈11 of 40 do care, and all eleven are made more stable by the change, not less"**. What R2b
must not do is claim invisibility — it is a visible improvement, and needs the before/after
evidence §10 already asks of it.

⚠⚠ **One axis the audit did not check, and it nearly cost a silent regression — see §10.4.**
The audit asked *"does any consumer observe raw array order?"*. It did not ask *"does any
consumer depend on the remove-then-add pair **firing**?"* — and they all do, because a reactive
read of a relation is re-evaluated by nothing else.

### 10.4 R2b as built (2026-07-29)

**Three changes as scoped, plus one the audit did not predict.** `MembershipOrder` is deleted,
every relation converges probe-first, and a genuinely new member costs one array write instead of
two. No wire, schema, stored-byte or `TENSHU_STORAGE_FORMAT_VERSION` change: the *contents* of
every array are exactly what they were; only the order within them, and the number of times the
row is rewritten, differ.

**⚠⚠ The finding: `removeFromArray` + `addToArray` was doing two jobs, and §10.3 only audited
one of them.** [`StreamRegistry`] re-evaluates a reactive read when, and only when, an array
listener fires — and array listeners fire on membership changes. So the remove-then-add pair was
*also* the change-notification channel for every stream over a relation. Probe-first membership,
implemented exactly as this phase's brief specified it, writes nothing in the steady state and
therefore **notifies nothing**:

- `HomeDeviceService.DEVICE_STATES` is one hub-authored row per device, re-saved on every state
  change. Membership never changes. A lamp turning on would have updated the store and never
  reached Home's `deviceStatesFlow()` — verified: with the notification removed, the re-save
  produces **0** listener events where the old code produced 2.
- The same shape covers `HUB_STATUSES`, `IdentityService.DEVICES`, and every other "one status
  row per thing, re-saved in place" relation.

⚖️ **Fix: separate the two jobs rather than keep the write.** New
`Database.notifyArrayMemberUpdated(reference, value, origin)` — default no-op on the interface
(so no test double changed), `DatabaseImpl` fires the array's `onAdded` callbacks without touching
the store or the membership snapshot. `onAdded` rather than a new callback kind because it already
means "this member is in the array, here it is" (the diff fallback fires it for members a bulk row
rewrite re-introduced), and `StreamRegistry` — the only production listener — answers it by
re-evaluating, which is precisely the wanted effect.

**This makes the notification path *better* than either predecessor, not merely preserved:**
a re-save used to fire **twice** (one remove, one add) under `MostRecentLast` and **never** under
`Stable`. It now fires exactly once, everywhere. ⚠ That "never" was a live bug in the one relation
that shipped `Stable`: an open Drive folder listing did not re-render when a file row was re-saved
in place — a `size`/`lastModified` update, OnDemand byte materialisation, a thumbnail resolving,
or this device's own block coming back through sync. `FolderFilesRelationUpdateTest` pins the fix.

**Evidence — write counts** (`RelationMaintainerWriteCountTest`, counted at
`ObjectStore.storeObject` for the relation's own array row; ⚠ *not* a claim about physical SQLite
writes — the bounded write mailbox dedupes queued Puts by row hash, so the two writes of one
transaction may already collapse into one. What it cannot do is collapse two into **zero**):

| array-row writes per save | before (`MostRecentLast`, ~30 relations) | before (`Stable`, `Folder.FILES`) | after (R2b, all) |
|---|---|---|---|
| re-save of an existing member | 2 | 0 | **0** |
| a genuinely new member | 2 | 2 | **1** |
| repair of a duplicated member | 2 | 2 | 2 |
| listener events on a re-save | 2 | **0** | **1** |

**Evidence — ordering.** `HomeDeviceRelationOrderTest` runs the sharpest of §10.3's eleven cases
against the real `HomeDeviceService` schema: three devices joined in order, then one renamed
(`UpdateHomeDevice` = one `HomeDevice` save).

| | before | after |
|---|---|---|
| `HOME_DEVICES` after renaming the first device | `[light-2, thermo-1, light-1]` | `[light-1, light-2, thermo-1]` |

Measured, not assumed — the "before" column is the assertion the same test produces when the
maintainer is reverted to remove-then-add. It matters more here than anywhere else because
`SyncedDeviceAwareHomeDevicesRepository` writes the array index into `Device.sortIndex`: array
position *is* the Home screen's sort key, so renaming the hallway lamp used to move it to the
bottom of the list. `Folder.FILES` order is **unchanged** (it was the one relation already
probe-first) — pinned by `FolderFilesRelationUpdateTest` so the equivalence is stated rather than
assumed.

**Deltas worth carrying forward:**

- **The declaration parameter did not disappear, it changed meaning.** `order` was doing double
  duty: which policy, *and* whether the relation is generically maintained at all. Only the policy
  half is gone, so `serviceDataRelation(id, type, order = …)` became
  `serviceDataRelation(id, type, maintained = true)`. The opt-out is still needed by exactly the
  two relations §10.2 identified as not-membership (`HealthService.LATEST_SAMPLES` and the
  `FileSyncSetting` 2→1 fold), which is why the default stays `false`.
- **`RelationSpec` is now fully derived**, with nothing declared on it that the schema does not
  already carry — `elementTypeId`, `scope` and `isEntity` all fall out of the declaration.
- ⚠ **Three tests changed assertions, and all three were assertions *about the deleted policy*:**
  `RelationMaintainerTest`'s `mostRecentLast_movesAReSavedMemberToTheEnd` (deleted; its `Stable`
  twin became the general case), and two `FileRelationDeclarationTest` assertions on
  `spec.order`. Everything else in the repo passes byte-for-byte, including all five R1/R2 gate
  suites.
- ⚖️ **What R2b deliberately did not do:** change the complexity class. A membership change is
  still one full-row rewrite, O(n) in the relation's size, and a bulk build is still O(n²) bytes.
  The incremental representation stays in `tech-debt.md`.

---

## 11. Decisions to confirm

D1/D2/D3 are settled: D1 by §9.2.1 (re-settled after the R1 review), D2 = lambda (with the
delete-path contract §10.1 records), D3 **CLOSED in R2b** — `MembershipOrder` is deleted and
probe-first is unconditional (§10.4). D4/D5/D6 remain open.

- ~~**D1.**~~ **RE-SETTLED 2026-07-28 → an argument to `serviceDataRelation`, on the schema
  (§9.2.1).** The original answer (module config, built in R1 as a `relationMaintainer`
  verb) was **wrong**, and its stated reason was wrong too: "~4 relations have no
  `serviceDataRelation`" is not a property of those relations, it is an artefact of the write
  side having had nowhere else to live — a `chainReadable = false` flag expresses that choice
  directly. Two independent objections killed the module-config route: (i) the verb names an
  *implementation* (an observer) in a DSL that should describe *what a relation is*; (ii) it
  re-declares as new what `ServiceSchema.relationSchemas` + `Type.elementSchema()` already
  carry. Rejected alongside it: a **trait on the element type** — membership is a property of
  the *relation*, not the element, and one element type can belong to several relations
  (`FileModification` is in both `MODIFICATIONS` and `File.FileHistory`), so a trait cannot say
  *which*.
- **D2.** Does `RelationSpec.scope`'s `parentOf` lambda stay a lambda? It is code, not data —
  fine on native/JVM, and consistent with `factories stay code` in
  `core-schema-evolution.md` §4.2. But it means a third-party/EPC module cannot declare an
  entity-scoped relation descriptively. Only 2 of 30 need it today; proposed answer is
  lambda now, revisit at EPC.
- ✅ **D3 — CLOSED 2026-07-29 (R2b, §10.4). `MembershipOrder` is deleted; probe-first is
  unconditional.** The bullets below are kept as the reasoning trail; three notes on how it
  actually landed:
  - **The conclusion held, both of its stated justifications were wrong, and the second
    replacement is the real one.** v1: *"29 of 30 consumers don't observe order"* — falsified by
    §10.3's audit (≈11 of 40 do). v2: *"all eleven are made more stable, not less"* — true, and it
    is what makes the change safe, but it is a claim about *order* only.
  - ⚠⚠ **The load-bearing fact was on a third axis nobody had audited: remove-then-add was the
    only change-notification a reactive read of a relation ever received.** Deleting the write
    without replacing the notification would have frozen Home's live device state — measured, not
    hypothesised (§10.4). `Database.notifyArrayMemberUpdated` separates the two, which leaves
    every relation strictly better off than under *either* old policy (one event per re-save,
    versus two under `MostRecentLast` and none under `Stable`).
  - **The parameter survived, in a smaller form.** `order` was also the opt-in flag; the policy
    half is gone, the opt-in is now `maintained: Boolean` — still needed by the two relations that
    are rollups rather than membership (§10.2).
- **D3 — REFRAMED 2026-07-28. The question is not the default; it is whether `MembershipOrder`
  is a real choice at all.** ⚠ **It is not derivable, and it is probably not even a decision.**
  - **Not derivable, in principle.** Array order is only observable if a *consumer reads the
    array in raw order*. That is a fact about read sites, which no schema can see. So `order`
    is either a genuine declaration or it should not exist — "sometimes derived" is the worst
    of the three, since it would make the same parameter mean different things per relation.
  - **Probably not a decision** (evidence, 2026-07-28): the 29 relations that do remove-then-add
    are **not choosing an order** — remove-then-add is the simplest idempotent
    converge-to-exactly-one idiom, and their consumers all sort or key downstream
    (`TimelineProjection` sorts moments/trips by timestamp; `ControlDataBridge` sorts events
    `sortedByDescending { timestamp }`; the notification client Ro documents
    `sortByDescending { timestamp }`). Array order is invisible to every one of them, so
    `MostRecentLast` is an **artefact presented as a policy** — exactly the implementation leak
    the schema-shape review (§9.2.1) objected to elsewhere.
  - **`Folder.FILES` is the one relation where order is observable**, and it is observable
    *because* Drive has an unsorted default listing (`FilesRepository.getFilesPaged(folderPath)`
    — "*Default body delegates to the unsorted `getFilesPaged`*"; the "Sort by" menu is the
    opt-in overload). That is precisely where append-on-re-save was reported as a **bug**.
  - **Therefore the likely end state is that `MembershipOrder` is deleted and `Stable` becomes
    unconditional** — it is correct wherever order is invisible, and strictly better where it is
    not. What blocks asserting that today is an audit nobody has done: every read site of all 30
    relations, checking for raw-order consumption (`getArrayDataWindow` paging, `.take(n)`
    without a preceding `sortBy`).
  - **Decision for now:** R1 keeps `order` as a **required, explicit** parameter and passes each
    relation's current value, so behaviour is provably unchanged and the gate tests stay honest.
    **R2 owns the audit** — it already touches all 30 relations' modules — and deletes the enum
    if the audit holds.
  - ⚠⚠ **AUDIT DONE 2026-07-29 — the hypothesis is FALSE. See §10.3.** (And the audit itself
    turned out to be looking at only one of the two things the idiom was doing — §10.4.)
    Index-sliced paging is
    indeed unique to `Folder.FILES`, but *ten more* relations are rendered straight to the UI in
    array order (`HOME_DEVICES` even promotes the array index to a model field), and three —
    `SHARES` / `CONTACT_INVITES` / `PENDING_GRANTS` — are order-visible in Drive and
    order-invisible in Control. R2b is still safe, for a stronger reason; the argument changes,
    not the conclusion.
  - **⚖️ It is also a write-path performance change, which strengthens the case (2026-07-28).**
    A relation array is stored as ONE row — `DbEntry(type = TList.ID, fields = [memberHash…])` —
    so `addToArray`/`removeFromArray` each `getObject` the whole row, rebuild the list, and
    `storeObject` **the whole row back**. `MostRecentLast` therefore costs **2 full-row reads +
    2 full-row writes on every save, unconditionally**. `Stable` probes with
    `getArrayMemberHashes` (member hashes only, no element deserialisation) and, when the member
    is already present exactly once, **writes nothing at all**.
    - That steady state is the *common* case, not an edge: re-saves dominate a synced realm —
      sync pulling this device's own block back, a local `size`/`lastModified` update, OnDemand
      byte materialisation, thumbnail resolution. Each of those currently rewrites the entire
      folder array twice to produce an identical result.
    - **Scale threshold:** `SqliteObjectStore.LARGE_ROW_THRESHOLD_BYTES` is 8 KiB, so an array
      past roughly a thousand members leaves the resident small-row tier for the
      pinned-until-commit + point-SELECT large-row path (`bounded-store-cache.md` P2) — the same
      regime that work was built for. Above that threshold the redundant rewrites are paid on
      the expensive tier.
    - ⚠ **Do not quote a factor without measuring.** The bounded write mailbox dedupes by row
      hash, so the remove-write and add-write of one transaction may already collapse to one
      store write. The defensible claim is the **zero-write steady state**, not a 2× halving.
    - Easy adjacent win, independent of D3: on `count == 0` (a genuinely new member) the current
      code still does remove-then-add — two writes where one `addToArray` would do.
  - ⚠ **Separate, larger finding worth its own tech-debt entry:** because a relation array is a
    single full-rewrite row, **every membership change is O(n) in the relation's size** regardless
    of order policy. An n-file folder upload therefore writes O(n²) bytes of array row. `Stable`
    removes the constant-factor doubling and the re-save cost; it does **not** change that
    complexity class. Fixing it needs an incremental array representation (append/remove
    journal, or chunked member pages), which is out of scope here.
  - ~~**If it survives the audit, it should change shape**~~ — moot: it did not survive. Kept
    because the reasoning still applies to `orderedBy`, should anyone build it: the descriptive
    question is *"does this relation have an intrinsic order?"* (`orderedBy = <field>`, absent =
    unordered), not *"which mutation idiom does the maintainer use?"*. The first is a property
    of the relation; the second is a property of the code that maintains it. §11.1 works through
    the alternatives and why the key must be a **field reference, never a comparator**.

### 11.1 Ordering — prior art, and why the key is a field and not a `Comparable`

Raised in review: *"a specific order by convention, overridable with a `Comparable`-like trait?"*
Worth answering against how comparable systems actually solve it, because two of the three halves
of that proposal are right and one is a trap.

**What everyone else does — and they agree.** Relational databases give a table **no** order at
all; `ORDER BY` is a property of the *query*, and indexes make particular orders cheap. Core Data
mirrors it: relationships are an unordered `NSSet` and you pass explicit `sortDescriptors`, with
`NSOrderedSet` (a persisted index) available and documented as expensive. ORMs add an optional
*declared default*: JPA splits `@OrderBy("name ASC")` (order derived from a field, applied at
fetch) from `@OrderColumn` (a persisted list position) — precisely the two-mode split this
section arrives at independently. Django's `Meta.ordering` is the same idea. ⚠ Rails'
`default_scope { order(...) }` is the cautionary tale: an *implicit* default order is widely
regarded as a footgun because it silently changes every query and is awkward to opt out of — so
**"by convention" should mean optional-and-explicit, not implicit**.

**In the CRDT world** the split is sharper. Unordered sets (OR-Set / AWSet — Yjs `Y.Map`,
Automerge maps) are cheap and need no position state. Genuine ordered sequences (RGA / Logoot /
LSEQ / Fugue — Yjs `Y.Array`, Automerge lists) preserve concurrent-insert intent but make every
element carry a position identifier and accumulate tombstones. The pragmatic middle that
collaborative products actually ship is **fractional indexing** (Figma) / **LexoRank** (Jira):
each item holds a key strictly between its neighbours, a move rewrites one key, and concurrent
inserts at the same slot tie-break on actor id.

**Applied here, three conclusions:**

1. **Default unordered.** Matches SQL and Core Data. ⚠ It does *not* match this repo as well as
   D3 first assumed: §10.3 found ≈11 of 40 relations rendering raw array order, so "consumers
   sort" describes the majority, not everyone. Since R2b (§10.4) that order is at least stable —
   insertion order, unperturbed by re-saves — which is what makes relying on it survivable for the
   eleven that do.
2. **Optional declared order = a field reference** (`orderedBy = <field>` + direction), which is
   JPA's `@OrderBy`. It is **data**, and that is the whole point.
3. **Curated relations (§9.3) get explicit position via fractional indexing** on a
   `RelationMember.ordinal: TString` — LWW per member, so it composes with §9.3's
   LWW-Element-Set and introduces **no second convergence philosophy**. A sequence CRDT would;
   that is the reason not to reach for one.

**⚠ Why *not* a `Comparable`-style trait, which is the trap:**

- **Wrong owner.** `Comparable` is a property of the *type*; order is a property of the
  *relation*. One element type sits in several relations wanting different orders —
  `FileModification` is in both `FileService.Relations.MODIFICATIONS` and `File.FileHistory`.
  This is the same objection that killed the element-trait option in D1.
- **⚠⚠ It would opt every ordered relation out of work this repo just shipped.** A comparator is
  **code**; a field reference is **data**. `core-ordered-window-fusion.md` F1 fuses
  `sortBy(key).take(n)` into one bounded streamed pass, and it gates on **single-step selectors
  only — a property or a `{ field }` lambda**. An arbitrary comparator falls through that gate
  and back to full materialisation plus a per-row interpreted selector. Worse, F3 (the deferred
  maintained key→DataId ordered index) is only *expressible* for a field key — "the index over
  `Comparable`" is not a thing you can build.
- **Doesn't cross the boundary.** Code cannot ride the module graph to the JS SDK or a
  third-party EPC module; a field id can. Same reason `core-schema-evolution.md` §4.2 keeps
  factories as code but pushes *descriptions* toward composites.

### 11.2 `sortBy` is the same order — `orderedBy` is a declared default, not a second concept

Raised in review: *"could the `sortBy` operation also use this, or is it inherently another type
of order?"* **Same type. Different binding time.**

Both name *a single-step key selector over the element* — which is exactly F1's fusion gate (a
property, or a `{ field }` lambda). So `orderedBy` should not introduce a parallel notion of
order; it should be **the same expression `sortBy` already accepts**, bound at declaration
instead of per query. That is precisely how the prior art in §11.1 works: JPA's `@OrderBy` *is*
a default `ORDER BY` for that collection, and Django's `Meta.ordering` is a default the
per-query `.order_by()` overrides.

**The user-assigned case collapses into it too.** Playlist / drag-to-reorder position looks like
a different kind of order, but once §9.3's fractional-indexing `RelationMember.ordinal` exists it
is just another field — `sortBy { ordinal }`. So the taxonomy is not *kinds of order*; it is
**where the key comes from**:

| | Key source | Expression |
|---|---|---|
| Unordered (29/30 today) | none — consumer decides per query | `relation.sortBy { … }` |
| Declared derived | a field of the element | `orderedBy = <field>`, = a default `sortBy` |
| Declared assigned | a field of the *membership row* | `orderedBy = RelationMember.ordinal` |

**There is one notion of order in this system, and it is `sortBy`'s key.**

**What that buys — and it is the same win §11.1 named, now precisely stated.** If a relation
declares its order *and* the array is maintained in it, the walker can recognise
`relation.sortBy(k).take(n)` where `k` is the declared key, **elide the sort**, and fall straight
through to `getArrayDataWindow` — the paged-listings tech-debt entry's "*the sort becomes a no-op
the fold could skip through*". That is a fifth peephole alongside F1, and strictly cheaper than
F1 (F1 still streams all N thinly; elision reads only the window).

**⚠ Three constraints that make this narrower than it first looks:**

1. **Single key, and direction must be declared.** `compareKeys`' composite branch is a hard
   error (`core-chain-sort` §6.7, restated in `core-ordered-window-fusion.md` §3.3) — so a
   declared *multi-field* order is not expressible, fused or unfused. `orderedBy` inherits that
   ceiling.
2. **⚠⚠ Elision is a correctness risk, and a different risk *class* from F1.** F1 is safe by
   construction — it still performs the sort, just in one bounded streamed pass; if its gate
   misses you get the same answer, slower. **Its failure mode is "slower".** Elision does not
   sort at all: it asserts the stored array is already in key order. **Its failure mode is
   "wrong".** F1 trades work for less work; elision trades a guarantee for an assumption.

   Four ways the assumption goes false:

   - **`addToArray` appends** — `(oldRefs + newEntryRef)`. The array is in *insertion* order, not
     key order. Key order holds only if the maintainer inserts at the correct index, which needs
     the members' keys, which the member-hash row does not carry.
   - **Sync ingest does not arrive in key order.** Blocks arrive in HLC order; a historical
     import (Fit full-history, Google Timeline parse) delivers old timestamps long after recent
     ones.
   - **⚠⚠ A key-changing update — and this collides head-on with `Stable`, the very policy D3
     moves toward.** `Stable`'s defining behaviour is *"member already present exactly once ⇒ do
     nothing"*. So correcting a `Moment`'s timestamp, or renaming a file in a name-ordered
     relation, leaves the member in its old slot and the array out of order **by design**.
     **D3 and a future `orderedBy` pull the same code path in opposite directions**; whichever
     ships second must resolve it, not discover it.
   - **Direct writers.** Verified absent today (below), but nothing *enforces* their absence.

   And the failure is **silent**: validating the order costs exactly the O(n) scan elision
   skipped, so there is no cheap assertion to add. It surfaces as "the feed is subtly
   mis-ordered, sometimes", on whichever histories happened to insert out of order — which reads
   as a UI bug, not a store bug.

   - ⚖️ **Verified 2026-07-28: relation arrays already have exactly one writer.** The op
     implementations that used to write directly were cleaned up —
     `CreateRealmImplementation` carries "*Saving is sufficient… Don't also call `addToArray`
     here — both paths writing would produce duplicate entries in the relation*". A sweep of
     non-maintainer `addToArray` callers finds **no relation writers at all** (the hits are all
     queue arrays — see point 3).
   - ⚠ **But `DeviceRelationMaintainer`'s KDoc still claims "`ApprovePairingRequest` already
     calls `addToArray` directly"** — stale, and actively misleading: it reads as licence to add
     a second writer. Correct it in R1/R2 while that file is open.

   **Three ways to make elision sound, in increasing order of honesty:** (i) *enforce* the
   invariant — single writer, ordered insert, reposition on key change (the part that fights
   `Stable`); (ii) a **validity flag on the array row** — "sorted by K", cleared by any write
   that might violate it, restored by a full re-sort, with elision gated on the flag (the
   classic index-validity trick, and cheap); (iii) maintain a real key→DataId index, at which
   point elision is just *reading the index* and is sound by construction — **which is F3**.
   ⚖️ So the sound version of this optimisation *is* F3. It must not be pitched as "declare an
   order and the sort becomes free": the thing that makes it correct is the index, and the index
   is the expensive part.
3. **Queue arrays are not relations, and must not be swept into this.** `PENDING_BLOCKS`,
   `PENDING_TOMBSTONES`, `PENDING_LANE_DELTAS`, `PENDING_REKEY_MIGRATIONS`, the tombstone store
   and schedule items all use `DataId.RelationDataId` + `ArrayDatabaseReference`, and they *are*
   written directly by their owners. They are FIFO work queues whose insertion order is
   load-bearing and which have no membership rule at all. **Scope boundary for R2:** seeing
   `RelationDataId.create(relationId = PENDING_BLOCKS)` does not make it a relation.

**⚖️ The payoff that makes a declared field order worth building at all** is not tidiness: it is
that the maintainer could keep the array *in* that order at write time, which would let sorted
paged listings window-fuse. The tech-debt entry *Paged Listings — Sorted Chains + Signal-Only
Streams Don't Fully Window-Fuse* names exactly this — the events feed pays a full read + sort per
page "*unless the notifications array is maintained timestamp-ordered at write time*". ⚠ Honest
cost: finding the insertion point needs the members' *keys*, which the member-hash row does not
carry, so insert-in-order implies maintaining a parallel key array — i.e. it **is** F3, not a
freebie on top of it. Do not sell it as cheap.
- **D4.** Should R2 also fold the ~6 hand-built `ArrayDatabaseReference` **reader** sites onto
  `RelationRegistry.referenceFor`? Proposed yes, in the same PR as each module's writer.
- **D6.** Does R2 also take the read side (§9.7) — spec-derived `serviceDataRelation` behind an
  opt-in `chainReadable` flag, plus the `ServiceRo.serviceRelation<E>(…)` helper? Proposed
  **yes**, and in R2 rather than a later phase: R2 already touches all 14 modules' registrations,
  and doing the read side separately means touching them twice. It is additive (existing manual
  registrations and accessors keep working), so it can also be deferred without rework if R2
  is running long.
- **D5.** `RelationMember.compositeKey` is proposed as `"$relationKey|$memberKey"` built from
  `getDataIdHash()`. ⚠ That hash is `String.hashCode().toLong()` — **32 bits**. The system
  already leans on it for `targetEntityIdHash`, so this is not a new exposure, but minting a
  *key* from it makes collisions a correctness bug rather than a routing inefficiency. Prefer a
  canonical DataId string (which does not exist yet) or accept and document.

## 12. Rejected alternatives

- **A dedicated `RelationDeltaPayload` wire type** (the shape §5(a) and the maintainer KDoc
  assume): a payload carrying `(relationDataId, [ (member, hlc, removed) ])`, with the outer
  `SyncBlock.targetEntityIdHash` set to the relation array's own hash — which works today with
  no envelope change, since every `ReferencedDataId` already has `getDataIdHash()`. **Rejected
  because `RelationMember`-as-a-row gets the same semantics for strictly less machinery**: no
  new payload variant in `decryptAndParse`, no new branch in `processSyncBlock`, no new merge
  rule, no new drop-on-old-peer class. The one thing the payload buys is batching N membership
  changes into one block — and `DeltaBatchPayload` (TS-1) already batches N row saves, so it is
  buyable later without a second wire type.
- **Reconstructing rosters from the relay's `KnownDevicesStore`** (§5d): a real trust shift
  (the relay becomes the source of truth for realm topology rather than an observer of it) for
  a benefit §9.4 delivers without one.
- **Modelling derived relations as `dynamicProperty` instead** (asked 2026-07-30 of the device
  roster: if membership is a derived cache that drifts, why maintain an array at all rather than
  compute the list at read time?). Attractive, and it does not work — for two structural reasons
  and two consequential ones:
  - **A dynamic property needs a receiver entity.** The DSL is
    `dynamicProperty<Receiver, T>(id, receiver = Schema) { … }` — `Folder.FileCount` on `Folder`,
    `File.Name` on `File`. A service relation like `IdentityService.DEVICES` has no host entity, and
    minting one to hold it would create exactly the kind of row §9.6 L3 says needs a producer.
  - ⚠⚠ **The body would have nothing to enumerate.** `ObjectStore` is hash-keyed
    (`getObject(dataIdHash: Long)`); there is **no enumerate-by-type primitive** in the
    `Database`/`ObjectStore` seam. `Folder.FileCount`'s chain works precisely because it reads
    `files` — *the relation array is the type index*. A dynamic property is therefore a **consumer**
    of relations, never a substitute for one. (`SqliteObjectStore`'s table is `(hash, type, data)`
    and E2b's `StoreSchemaMigrator` does scan by type, but that is private to one platform's store,
    not on the interface, and the in-memory / IndexedDB / iOS twins each lack it. Exposing it means
    adding a type index to `:core` — a far larger change than redeclaring one relation.)
  - **It would forfeit the reactivity R2b had to add.** `Database.notifyArrayMemberUpdated` exists
    because the array is the *invalidation anchor* — the only change-notification a reactive
    relation read gets (§10.4). A read-time scan has no anchor, so every consumer would be back to
    explicit refresh: the bug the reactive device-roster read (no more `refreshDevices`) deleted.
  - **It is orthogonal to regeneration anyway.** Read shape does not create rows: a computed read
    over zero `Device` rows returns zero devices, exactly as a maintained array does.

  ⚖️ Where it *is* the right tool: cheap derived facts **about** an element (`isThisDevice`, a
  last-seen rollup) — the `Folder.FileCount` shape, computed per entity instead of stored and synced.
- **Making every relation curated** ("membership is always explicit, no derivation"):
  conceptually cleaner, and rejected on cost — see the 10 000-row folder in §9.3 — and on
  robustness, since it discards the self-healing property that derivation gives for free.

---

## 13. Related

- Tech-debt: *"`Device` rows have no regeneration path"* (the incident + the tier-1 self-heal).
- [`core-schema-evolution.md`](core-schema-evolution.md) §7.5 — why the core version and its wipe stay,
  and §8.3 for the no-election/no-quorum convergence posture (the E4 floor constraint §7 names).
- [`retention-quota-model.md`](retention-quota-model.md) — the three-tier storage taxonomy §9.4 leans
  on: relay blobs are the durable tier, never time-reaped, which is why a snapshot survives what a
  block does not.
- [`delegated-device-admission-authority.md`](delegated-device-admission-authority.md) §5 — admission
  lineage semantics.
- `docs/sync/design.md` §2.2 + [ADR 0007](../adr/0007-sync-device-management.md) — synced device
  management as it stands.
