# Sync entity identity — a keyed hash on the wire, a stable identity for mutable keys

**Status:** v0.2 (2026-09-16; EI-4 updated 2026-10-04). **EI-1 shipped; EI-4's value narrowing shipped with CR-2 B3–B6 (2026-10-04, §5b as built); EI-2 is design.** ⚠ This table in §6 is
the phase state's single source of truth — the track routing row points here and carries none. Opens from a finding, not from a preference: the
relay-visible entity key **was** a 32-bit, unsalted `String.hashCode()` of the entity's type and key —
guessable for every type keyed on user-meaningful plaintext, and colliding at realm sizes already
measured. EI-1 replaced it with `WireEntityId`'s keyed HMAC-SHA256 and **has shipped**; the finding is
recorded here because the reasoning still governs EI-2 and EI-4. Proposes three changes of different scope — one generic and
small (EI-1), one a Drive modelling decision (EI-2), one that removes the tombstone's
type-level disclosure (EI-4, §5b — ⚠ **not** the plane's last: `content_edge.property_id` outlives it,
see §5b) — and names the one alternative that looks more general and should
not be taken.

Related, and load-bearing:
[`threat-model.md`](../sync/threat-model.md) A6 + T-R-08 (the residual this corrects),
[`design.md`](../sync/design.md) §2.2 (what the relay reads),
[ADR 0006](../adr/0006-sync-tombstones.md) (tombstones keyed on the hash),
[ADR 0017](../adr/0017-file-content-two-plane-channel.md) D3 (the content plane's rule for refs),
[`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) D1 (`SharedFile.fileId`, the precedent),
[`file-content-addressing.md`](file-content-addressing.md) §3.3.4 (`DataIdMigrator`),
[`content-plane-refcount.md`](content-plane-refcount.md) §5.2 / §6c.1 / §9.5 (what a stable identity retires),
[`core-realm-data.md`](core-realm-data.md) (why the identity does not go on `RealmData`).

---

## 1. ⚠⚠ The finding

`DataId.getDataIdHash()` (`core/.../entity/DataId.kt`) is, for every `DataId` shape:

```kotlin
val s = "${type.get()}_${keyValue.get()}"
s.hashCode().toLong()
```

That value is `SyncBlock.targetEntityIdHash`, `DeleteBlock`'s target, `SyncMetadata.targetDataIdHash`,
`LocalWriteMetadata.targetEntityIdHash`, `PersistedTombstone`'s key, `TombstoneRegistry`'s key,
`content_edge.holder_hash` (CR-3), and the identity `StreamMerge` dedups list members by — **55
non-test source files** reference one of those names. Two properties of it are wrong for a
relay-visible key.

### 1.1 It is a confirmation oracle for every meaningful key

The relay stores the hash as an opaque string and never validates its shape. It does not need to: it
can *compute* it. `String.hashCode` is public, unkeyed, and the input is `type_key`. For a type whose
key is user-meaningful, the relay can hash a guess and test it against any owner's blocks, tombstones
and edges. What feeds `keyValue` today, verified by grep over `*/src/commonMain`:

| key source | count | guessable? |
|---|---:|---|
| `path` | 9 | ⚠⚠ **yes** — a file path (`tenshu/file/file_/Documents/taxes.pdf`) |
| `preferenceId` | 5 | ⚠⚠ **yes** — a schema vocabulary; which settings exist is exactly the profiling T-R-08 closed |
| `key`, `data.get()` | 14 | ⚠ likely — generic strings; to audit (EI-0) |
| `DeviceSetting.compositeKey(deviceId, preferenceId)` | 3 | ⚠ the `preferenceId` half |
| `deviceId`, `eventId`, `workoutId`, `routineId`, `realmId`, `fingerprint`, `idHash` | 27 | no — random or high-entropy; to confirm (EI-0) |

[`threat-model.md`](../sync/threat-model.md) T-R-08 records the residual as *"the relay still sees
opaque `targetEntityIdHash`es"*. For a path-keyed `File` the hash is not opaque. It is a dictionary
attack away from the path.

⭐ **The content plane already obeys the rule the ordered plane breaks.** A chunk ref is
`sha256(ciphertext)` under a random CEK *and* a random nonce; a manifest ref is
`sha256("manifest:" + contentId)` over a **random** id (ADR 0017 D3 rejected plaintext-derived refs as a
confirmation oracle in as many words). The rule is: **a relay-visible key derives from randomness the
relay cannot guess, never from user-meaningful plaintext.** `targetEntityIdHash` violates it.

### 1.2 It collides, at sizes already measured

`String.hashCode` is 32 bits; `.toLong()` widens the value, not the space. The birthday bound for
`n` entities in one owner's keyspace is `p ≈ n² / 2³³`:

| entities per owner | chance of ≥ 1 colliding pair |
|---:|---:|
| 14 208 (`SyncMetadata` rows, measured 2026-08-04) | **≈ 2.3 %** |
| 50 000 | ≈ 25 % |
| 100 000 | expected pairs **> 1** |

A collision is not benign, because every consumer of the hash treats equality as identity:

- the relay's tombstone GC (`MailboxStore.gcSweep`) drops the **live twin's** older bodies — a
  joiner can no longer replay them;
- `TombstoneRegistry` short-circuits the live twin's blocks below the tombstone's HLC on every peer;
- `StreamMerge` dedups list members by the hash, so two distinct entities merge into one in a
  collection **locally**, with no relay involved;
- CR-3's `undeclareHolder` drops the live twin's content edges — CR-5's veto rescues it today, but
  only because the walk is complete.

⚠ None of this needs an attacker. It needs a realm with enough rows.

---

## 2. Two changes, two scopes — and the one that looks generic and is not

The two defects have one cause but two fixes, because they are answered at different layers.

| | fixes | scope | changes |
|---|---|---|---|
| **EI-1** keyed hash | oracle + collisions | **generic** — every `Syncable` type | one derivation, in the sync layer |
| **EI-2** stable identity | move-as-metadata; the recreate residue | **per type with a mutable key** — ⚠ `File` is **out** (§2.2: path identity converges, a random id does not); `Folder` is **undecided** (Q6) | a `Syncable.identity`, for types that genuinely need one |
| ~~random per-entity sync id~~ | both, apparently | generic | ⚠⚠ **rejected** — §2.3 |

### 2.1 EI-1 — the wire id is a keyed hash, and convergence is why it is a hash

```
wireId = HMAC-SHA256(K_entity, "${type}_${identity}")[0..16)      // 128 bits, hex
K_entity = HKDF(sharedVolumeKey, info = "tenshu/realm/entity-id/v1")
```

The derivation mirrors the realm-keyed one the slice snapshot already uses
(`K_snapshot = HKDF(sharedVolumeKey, info = "tenshu/realm/snapshot/v1")`,
[`sync-device-catch-up.md`](sync-device-catch-up.md) §5.7.2), so no new key-management shape is
introduced. It is computed **in the sync layer**, where realm keys live — not in `:core`, which holds
no key and does not need one.

What it keeps, and this is the whole reason it is a hash rather than an id: **convergence without
coordination.** Two devices that create `/Photos` offline agree on its wire id because they share
`K_entity`, so their blocks merge as one entity, one tombstone retires both, and the recreate
semantics of ADR 0006 are untouched. That property is what today's hash provides and what §2.3's
alternative would give up.

What it changes:

- **Unguessable.** The relay holds no `K_entity`; a path it guesses hashes to nothing it can test.
- **Collision-free in practice.** 128 bits.
- **Per realm.** The same identity shared into realm R hashes under R's key to a different wire id,
  so the relay cannot link one file across realms — the same denial the content plane's per-owner
  keyspace already gives (ADR 0017 D5).

⚠ **Local list identity is a separate function.** `StreamMerge` dedups by `getDataIdHash()` and
lives in `:core`, which cannot and should not hold a realm key. It needs no privacy, only width: a
64-bit strong hash (`sha256` truncated) of the same string ends the local collision case. The two
functions must not be one: the wire id is keyed, the local one is not, and the sweep in §5 is where
each caller is told which it is.

⚠ **Fence old clients.** The relay stores the hash opaquely, so EI-1 changes a *value*, not a wire
schema — but a client still deriving `hashCode` ids writes blocks no peer can match to an entity,
which is silent divergence, not an error. Bump `RELAY_PROTOCOL_VERSION`'s **minimum** so a pre-EI-1
client is refused with a 426 rather than admitted (`RelayProtocol.kt`'s `[min, current]` range). The
pre-release reset (decided 2026-07-14) means there is no stored population to migrate.

### 2.2 EI-2 — a stable identity, only where the key can change

Move-as-metadata needs an identity that survives a rename. Types keyed on a random id —
`workoutId`, `eventId`, `deviceId` — already have one: their key never changes. The types that do
not are the ones whose `DataId` is derived from a **mutable** value, and today that is `File` and
`Folder`, keyed on the path. So EI-2 is a Drive modelling decision, not a sync-layer one, and the
precedent to reuse is [`drive-shared-realm-file-model.md`](drive-shared-realm-file-model.md) D1:
`SharedFile.fileId`, a random id allocated once at a file's birth.

- ⚠⚠ **WITHDRAWN (2026-09-16): `File` does not get a `fileId`.** It was to be a random
  `valueProperty` minted at creation, and `file-content-addressing.md` §3.3.1 had already decided
  against exactly that — path identity **converges**, a random id does not, and two devices creating
  the same path would derive two wire ids for one row. §5b's end-state analysis reaches the same
  conclusion from the other side, so the field is dropped rather than carried as an open conflict.
  ⇒ `File` keeps its path-derived `DataId`; a move keeps emitting its old-path tombstone
  rather than becoming invisible on the wire, which is the benefit EI-2 gives up.
  ⚠⚠ **And that tombstone does not currently work — a live defect EI-1 introduced.**
  `DataIdMigrator.retire` computes `oldDataId.getDataIdHash().toString()` and passes it to the
  **hash form** of `TombstoneEmitter.emit`, whose own contract says what that costs: *"A caller that
  hashes the id itself produces the local name, so its tombstone addresses an entity no peer can
  match, and nothing errors: both sides sync and the row simply never dies."* So since EI-1 every
  move and rename has emitted an old-path tombstone no peer matches, and peers keep the file at
  **both** paths — the duplicate-on-peer bug the old-path tombstone closed (`file-content-addressing.md` §3.3.3.A), silently reopened. ⇒ the call takes the
  **id form** with `oldDataId`; `WireIdResolver.ofOrNull` needs the `DataId` and the realm key only,
  not the row, so it still resolves after `deleteData`. ⚠ The *local* marker keys around it are
  correctly local and must stay that way — it is only the wire name that was wrong. ⚠ The content
  plane's need for something move-stable is met by a **reference** id, not an entity id
  (`content-plane-refcount.md` §6e).
- ⚠⚠ **`SharedFile.fileId` exists but is NOT a cross-realm identity** — a correction to an earlier
  revision of this bullet. `ShareFolderIntoRealmImplementation` calls `generateId()` for the root and
  for every descendant, and `ShareFolderIntoRealm`'s own contract says *"fileIds are minted fresh each
  call"* — so re-sharing the same file mints a different id, and there is no personal identity to
  inherit from now that `File.fileId` is withdrawn. ⇒ D1's cross-realm identity item stays **open**,
  and withdrawing `File.fileId` neither closes it nor makes it worse: it was never closed.
- ⚠⚠ **And declaring it is not adopting it — the switch re-names every existing row.** EI-1 derives
  the wire id from the `DataId`; pointing it at a declared identity gives every already-synced row of
  that type a **new** relay-visible id, while retained blocks and tombstones stay under the old one
  and un-upgraded clients keep pushing there. The relay then holds one logical row as two entities,
  and a delete retires only the half its author can name. ⇒ adopting EI-2 for a type owes a per-type
  **migration or reset** plus a mixed-version story (dual-id reads, or a fence) — the same shape EI-1
  itself needed, and the reason that one shipped with a reset.
- **`Syncable` declares which property is the identity.** A value-bearing trait already carries
  `strategy` and `conflictStrategy`; `identity` joins them, defaulting to the `DataId` key. EI-1's
  derivation reads it: `wireId = HMAC(K_entity, "${type}_${identity}")`. A type that declares nothing
  behaves exactly as today, keyed on its `DataId`.
- **`DataIdMigrator` keeps its local job and loses its wire job.** A move still retires the old
  path-derived `DataId` and re-points references locally (§3.3.4); it now also tells sync *"same
  identity, new local key"* so `SyncMetadata` follows. ⚠ **For a type that declares an identity** the
  wire then sees no move — ⚠⚠ **`File` is not such a type** (§2.2's withdrawal), so for files this
  paragraph describes the mechanism, not the outcome: no old-hash
  tombstone, no recreate, and — the reclamation consequence — **no edge drop on move**, which today
  turns every moved file into a CR-5 candidate that only the veto walk rescues
  ([`content-plane-refcount.md`](content-plane-refcount.md) §9.8).

⭐ **Photos keyed on the wrong identity, for want of the right one.** `AlbumEntry`, `TrashedPhoto`,
`PhotoFlags` and `Album.coverContentId` use `contentId` as the stable name because a path dangles on
move and nothing else was stable. But `contentId` is **content** identity and a replace mints a new
one (CR-1) — so an in-place overwrite of a photo may drop it from its albums today. `fileId`
is **entity** identity and survives both. ✅ Verified 2026-10-29 — Q5 below: yes, and worse than the
album listing.

### 2.3 ⚠⚠ Not this: a random per-entity sync id minted by the sync layer

It looks like the more general answer — one id for everything, no per-type field — and it quietly
gives up convergence. Two devices that create the same path-keyed entity offline would mint two ids;
the relay would hold two entities; a delete of one would not retire the other; and reconciling them
needs an **identity-merge protocol** on ingest (detect two wire ids for one local `DataId`, pick a
deterministic winner, tombstone the loser, merge state). That protocol is new, rare enough to be
under-tested, and unnecessary: a keyed hash converges by construction. EI-1 is the generic change;
EI-2 is the identity change; the random id is neither.

⭐⭐ **The structural reason, which is stronger than the convenience one: a shared wire id is what
makes the convergence machinery that already exists reachable.** Two devices creating one entity
offline arrive as `existing` + `incoming` for one type — precisely `SemanticMerger.merge`'s ordinary
case, and this repo has eleven of them across six services (ADR 0024). `FolderMerger` unions
`Folder.FILES` by path and only falls back to `lastModified` for a path on both sides, so `a.jpg`
added on one device and `b.jpg` on the other both survive.

⇒ The layering is **wire id converges *identity*; the merger converges *state*.** The hash does not
do the merging — it routes both writes to the same entity so the merger runs at all. Under a random
id `FolderMerger` is never invoked, because there is no conflict: just two `Folder` entities holding
half the files each, indefinitely, with no code path that would notice. **A conflict gets resolved;
a silent duplicate only accumulates** — and an identity-merge protocol would have to match entities
*by name* to fix it, arriving back at this hash, later and with more states to test.

⚠ One thing to confirm before this reads as airtight: what a type with **no registered merger**
does. Eleven types have one, so most do not, and the default decides whether concurrent creation
merges its fields or silently keeps one side's.

### 2.4 Not `RealmData` either

[`core-realm-data.md`](core-realm-data.md) made `RealmData` a **fieldless marker** on purpose, with
presentation and semantics in each consumer's registry and never on the trait. An identity is a
per-type field decision, and the wire id must exist for every `Syncable` type — `Device` rows and
preferences sync and are not `RealmData`. `Syncable` is the trait that already decides how a type
syncs; it is the home.

---

## 3. What each change retires

| today | after EI-1 | after EI-2 |
|---|---|---|
| a guessed path confirms a file's existence on the relay | nothing to test | — |
| ~2 % of realms at 14 k rows carry a hash collision | 128-bit space | — |
| a move is an old-hash tombstone + a recreate on the wire | — | ⚠ **unchanged for `File`** — the old-path tombstone stays; this row described the withdrawn `fileId` model (§2.2) |
| a move drops the file's content edges; the veto rescues it | — | ⚠ **unchanged for `File`** — the content plane's answer is a **reference** id, not an entity one (`content-plane-refcount.md` §6e) |
| §6c.1's recreate residue includes every move | — | ⚠ **unchanged for `File`**, same reason |
| `SharedFile` allocates its own `fileId`; cross-realm identity is D1's open item | — | unaffected — `SharedFile.fileId` is shipped and is not withdrawn |
| Photos keys identity on `contentId` (changes on replace) | — | ⚠ **open** — `fileId` was the answer and is withdrawn; EI-3 owes a target that exists (§6) |

---

## 4. Threat model (HR #2)

EI-1 is a change to the relay's data shape and owes an amendment in the same PR:

- **T-R-08 residual, corrected.** *"Opaque `targetEntityIdHash`"* was true for random keys and false
  for path- and vocabulary-keyed types; the hash was `String.hashCode` over plaintext. After EI-1 the
  relay holds a keyed hash it cannot compute. What it still sees: that *some* entity exists, when it
  changed, and when it was deleted (A6, unchanged). What it can no longer do: confirm a guessed key.
- **A6**: *"tombstone existence (target entity id + HLC)"* should read *"(keyed entity id + HLC)"*.
- **New**: `K_entity` is one more key derived from `sharedVolumeKey`; it never leaves the client and
  is not stored, exactly as `K_snapshot`. A device with the volume key has it; a revoked device keeps
  the ids it already learned, which reveals nothing it did not already hold.
- **Cross-realm**: a per-realm derivation means the same identity is unlinkable across realms at the
  relay — a strengthening.

EI-2 owes none: `fileId` rides inside the encrypted payload, and the wire id's shape is EI-1's.

---

## 5. The sweep

Every site that reads or stores the hash is touched once, and the split in §2.1 decides which
function each gets. Verified by grep: 55 non-test files across `:core`, `:sync-service-*`,
`:file-service-*`, `:health-service-proxy`, and the relay. The relay's part is the smallest — it
stores strings and compares them — and the mailbox index needs no schema change, only the reset.

⚠ **Tests that pin the hash's *value* will go red**, and that is the audit: each one is either
asserting local identity (keep, on the local function) or wire identity (rewrite against a fixture
`K_entity`). A test that asserts a literal `Long` is asserting neither and should be deleted.

---

## 5b. ⭐⭐ EI-4 — the tombstone's type is a discriminator, not a type (2026-09-16)

**Asked by the maintainer**, reading the relay's end-state inventory after the content-plane track:
*"do we still need the `target_entity_type` here?"*

`delete_block` stores it **in the clear** — `tenshu/file`, `tenshu/identity/device`, and so on — so
every delete tells the relay *what kind of thing* died. ⚠ **Not the last such disclosure, and this
must not be claimed as one:** `content_edge.property_id` is a clear column naming the service and
content slot, and that table is load-bearing today (`content-plane-refcount.md` §9.5) and **retained**
by §11.6–§11.7 for CR-6. Its retirement is *proposed* (§6e, N6), not scheduled. EI-4 removes one
type-level disclosure; the residual survives it and must survive into the threat-model amendment.

### ⚠ What the relay actually does with it — both uses are one question

1. **`SyncRouting`'s push-tombstone handler:** `targetEntityType == Device.ID` ⇒ revoke that device in
   `KnownDevicesStore`, so its next `/sync/push` gets a 401 (threat model T-K-02's push rejection).
2. **`SyncMailbox.deviceTombstoneCount`** ⇒ `GET /sync/realm-epoch`: the realm epoch **is** the count
   of Device tombstones, closing [ADR 0007](../adr/0007-sync-device-management.md)'s recovery race.

⇒ Neither needs to know a tombstone is `tenshu/file`. Both ask **one bit**: *is this a device
tombstone?* ⚠⚠ **And the receiver does not need the type either** — a `DeleteBlock` has no encrypted
payload to carry one: `blockId`, `ownerId`, `hlc`, `targetEntityIdHash`, `targetEntityType`,
`revokedDeviceId`, all in the clear. `SyncProcessor.processDeleteBlock` resolves its target through
`targetEntityIdHash` and `TombstoneRegistry.resolveDataId`, and the resolved `DataId` carries the
type. So the field has **no reader that needs its value** beyond the device bit.

### ⭐ The decision: a kind flag

```
delete_block: … target_entity_type TEXT   -- "tenshu/identity/device" | "tenshu/entity"
                                          -- the SAME column and the same wire slot, narrowed
                                          -- from "what kind of thing died" to one bit
```

⭐ **The encoding is the decision's whole cost.** A new `target_kind INTEGER` says the same thing and
reads more honestly — and buys an envelope migration, a legacy decoder, a version bump and a
write-side flag day that (below) has no safe moment. Narrowing the existing column says it for free.
⇒ **narrow the column; keep `target_kind` as the shape to move to if the field ever needs to carry
more than one bit**, at which point the fleet can be assumed past this change.

- Both relay uses keep working unchanged, on a field that says nothing about the entity.
- A file delete then discloses only what **delivery** requires: owner, the EI-1 keyed entity hash, an
  HLC, a sequence. ⚠ That a *delete happened* stays visible, and must — the relay routes it.
- ⚠ **`revokedDeviceId` stays in the clear** on device tombstones: it is the value the relay acts on.
- ⚠ A *new field* on `DeleteBlock` would owe an HR #9 version bump with its migration and a
  `RELAY_PROTOCOL_VERSION` review. ⭐ The value-narrowing below avoids all of that; the T-R-08
  amendment in the implementing PR is owed either way (House Rule 2).
- ⚠⚠ **A missing kind must NOT default to "not a device".** That default makes an upgraded relay skip
  both `revokeIfNotStale` and the epoch increment for a pre-EI-4 client's device tombstone, leaving a
  revoked device's JWT working — and the explicit route cannot cover it, being best-effort,
  skippable by a delegated admitter and not durably retried. ⇒ a **legacy decoder** that derives the
  flag from `targetEntityType` when that field is present. Not a default.
- ⚠⚠ **And a protocol floor cannot stand in for it.** `relayProtocolVersionOk` runs on
  `POST /sync/realm` **only**, and `core-schema-evolution.md` §7.11 records that a device which has
  synced once never presents its version again — so an established pre-EI-4 client keeps pushing the
  old positional block to an upgraded relay, and an upgraded client the new shape to an old one,
  whatever the floor says. ⇒ **dual decoding on both sides is mandatory**, and the envelope's own
  version bump is a requirement rather than something to review. A per-request or per-session gate
  would be the alternative, and it is a larger change than this decision carries.
- ⚠⚠ **Dual decoding does not make the WRITE safe — and this is what changes the shape of EI-4.**
  Decoding both equips an upgraded reader; it does nothing for a reader that already exists. The
  moment an upgraded client writes the new positional field, a pre-EI-4 relay — or any peer pulling
  that retained tombstone, for ever, since ADR 0006 keeps them — reads a slot it expects to hold a
  `TString` type name. A misdecode there loses a deletion, or a device revocation. Combined with the
  point above (no session fence can exclude an established client), *"write the new shape when the
  fleet is ready"* has no moment at which it becomes true.

- ⭐⭐ **So don't change the shape: narrow the VALUE.** The field stays a `TString` in the same
  position, and what it carries becomes two constants — `Device.ID` and one non-device sentinel —
  instead of the entity's type. Every existing reader keeps working *by construction*, because the
  only semantics anyone reads out of it is `== Device.ID`:
  - relay: `SyncRouting` line 349 and `SyncMailbox` line 364, both exactly that comparison;
  - client: `TombstoneRegistry` counts per type, and the **only** production caller of
    `tombstoneCountForType` is `SyncProxyInitialization:472` — `Device.ID`, for the realm epoch.
    Every other caller is a test.

  ⇒ **no wire-shape change, so no envelope migration, no legacy decoder, no dual decoding, no
  protocol floor, no HR #9 bump** — the four obligations above collapse to one: the sentinel must be
  a string no entity type can equal.

  ⚠⚠ **And `RELAY_PROTOCOL_VERSION` does not move either — the argument that it should, examined and
  rejected.** It runs: the version covers *route semantics*, and the field's meaning changes, so bump
  current without raising the minimum. It loses on the rule's own normative test, which is not *"did
  a meaning change"* but *"would a client at N and a relay at N+1 **misunderstand each other**"*
  (`RelayProtocol.kt`). Here they cannot: an old relay reading the sentinel concludes *not a device*
  and skips the revocation — correct; a new relay reading a legacy concrete type compares it to
  `Device.ID` — also correct. Nothing misunderstands anything, which is the whole point of narrowing
  rather than reshaping.

  ⚠⚠ And the bump is not free in the direction people assume. `relayProtocolVersionOk` admits
  `clientVersion in MIN..CURRENT` — a client declaring **above** the relay's current is refused 426.
  So a bumped client that reaches a not-yet-redeployed relay cannot register a realm at all: a
  self-inflicted bootstrap outage, bought in exchange for a fence that fences nothing, on a route
  established clients never call again anyway. ⇒ **no bump**, recorded here so it is not re-proposed.
  ⚠ `targetEntityType` is not even in the envelope field list that version enumerates. Old and new clients read each other's tombstones correctly in
  both directions, and retained history keeps parsing byte-for-byte.

  ⚠ **What it costs:** per-type counts for non-device tombstones collapse into one bucket. Nothing
  reads them (above), and `PersistedTombstone.targetEntityType` becomes the same two values. ⚠ A
  future consumer of a *real* per-type count would have to source it locally — which it should
  anyway, since after this the wire no longer carries the type.

  ⚠ The disclosure is unchanged by the choice: device-vs-not is exactly what a flag discloses. This
  is the same decision, encoded in the field that is already there.

  ⚠⚠ **It is a forward-only guarantee, and that must be said rather than implied.** Narrowing what is
  *written* does not sanitise what already exists: pre-EI-4 clients keep emitting concrete types for
  as long as they run, the relay retains every prior `delete_block.envelope` indefinitely (ADR 0006),
  and an upgraded client can drain a `PENDING_TOMBSTONES` queue it filled **before** upgrading. So
  the relay's historical rows keep naming `tenshu/file` and friends; only new tombstones stop. ⭐ The
  one cheap mitigation worth taking is the queue: map the value at **drain** time rather than at
  enqueue, so an upgrading client does not emit concrete types after the upgrade for work it queued
  before it. History itself changes only under a reset, and is not worth one — ⚠ but a reader who
  assumes the disclosure is *gone* rather than *stopped* would mis-assess the residual.

- ⚠⚠ **The retained envelopes are the other half of it.** `delete_block.envelope` holds the
  serialized `DeleteBlock`, and `SqliteSyncMailbox.getTombstones` / `tombstoneIndex` parse those bytes
  with the **current** schema — ADR 0006 keeps tombstones indefinitely, so the store is full of them.
  Replacing a positional field therefore breaks or misdecodes history unless the plan carries a legacy
  decoder plus a reserialisation (or an explicit reset). ⇒ migrating the indexed SQL column alone is
  **not** the migration.

### ✅ As built (2026-10-04, with CR-2 B3–B6)

The value narrowing above, exactly as decided; nothing on the wire changed shape.
- **Sentinel:** `TombstoneWireType.NON_DEVICE = "tenshu/entity"` (`:sync-service-proxy`). The wire
  value is `Device.ID` for a device tombstone and the sentinel for every other one.
- **Mapped at drain time**, in `PushTombstoneImplementation` — the one push path for
  `PENDING_TOMBSTONES` — so a tombstone queued before the upgrade does not leak its type after it. The
  emitting device's own `TombstoneRegistry` / `PersistedTombstone` copy keeps the concrete type: it is
  local and never leaves the device.
- **Receivers were already type-blind**, confirmed: `SyncProcessor.processDeleteBlock` resolves the
  target through `targetEntityIdHash` → `TombstoneRegistry.resolveDataId`, never by type; the relay
  reads the field only as `== Device.ID` (`SyncRouting`'s push-tombstone handler and
  `SyncMailbox.deviceTombstoneCount`); the client's only production count is
  `tombstoneCountForType(Device.ID)`. Non-device `typeCounts` collapse into the sentinel bucket on
  receiving peers, and nothing reads that bucket.
- **No relay change, no `RELAY_PROTOCOL_VERSION` move, no HR 9 bump** (`DeleteBlock` and
  `PersistedTombstone` keep their shape; the baseline is unchanged).
- ⭐ **Not forward-only here.** Because it lands inside CR-2 B3–B6, before that change's relay reset, the relay
  keeps **no** concrete-type history: Phase 1b's scrub (`relay-metadata-minimisation.md`) is
  unnecessary.
- **Tests:** `TombstoneWireTypeTest` (every non-device type maps to the sentinel, a revocation keeps
  `Device.ID` and its `revokedDeviceId`, the drain pushes the sentinel for a tombstone queued with its
  concrete type, and the realm epoch still counts the device tombstone);
  `TombstoneSentinelCollisionTest` (`:system-test-utils`: the sentinel equals no schema id, schema
  key or relation id across the full twenty-service graph).
- ⚠ The four preconditions below are untouched: the discriminator stays until they move.

### ⭐ The target end state: no kind at all — and its four preconditions

⚠⚠ **Correction: they do not.** An earlier revision of this section said both relay uses already have
an explicit surface. `RevokeDeviceImplementation` does call `syncRelay.revokeAdmission`, and the relay
keeps `admission_revocation(...)` — but that route, in its own words, *"only strips minting power"*:
it appends an `AdmissionRevocation` and **never** calls `DeviceRegistry.revokeIfNotStale`, which is
what removes the device's JWT key and writes the permanent removal ledger. And `revokeIfNotStale` has
exactly **one** caller — `SyncRouting`'s applied `Device` tombstone. So the tombstone is not merely
the *durable* path to device-access revocation; it is the **only** path. Delete the discriminator
after satisfying the other three preconditions below and a removed device stays authenticated and
re-admittable.

⇒ **Four things must move, not three** — and the fourth is a route that does not exist:

0. ⚠⚠ **An explicit device-access revocation surface.** Something other than a tombstone must be able
   to reach `revokeIfNotStale` (key removal + removal ledger), with the same staleness rule. Until
   then the end state is not reachable at any price, which is a stronger statement than the three
   below: those are work, this is a missing capability.

   ⭐ **What is already there.** The *never-return* half is largely shipped: `recordRemoval` writes a
   permanent ledger row and `wasRemoved` is enforced at the join flow and the offer flow, each
   answering `403 DEVICE_REMOVED`. ⚠ Guarding one of those was tried and was not enough; the offer
   flow *"is precisely the one a returning device uses"*.

   ⚠⚠ **`wasRemoved` appears nowhere in `RealmRouting` or `RecoveryRouting` — and that is correct,
   not a gap.** An earlier revision of this section called it one. Both `/admit-device` and
   `/sync/recovery-claim` verify against the realm's **master** public key, so reaching either means
   **A1 is in hand** — which `threat-model.md` records as unfenceable by design (*"an A1 holder
   re-derives the volume key from the phrase and re-registers itself with `reregisterThisDevice`, so
   no rotation can exclude it"*), and §5b.9 of
   [`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)
   states outright that **an owner device cannot be removed**. ⚠⚠ Adding the check there is actively
   harmful: `RealmRegistrar.reregisterThisDevice` uses the admit-device fallback to repair a
   registration the relay lost, so a refusal turns a transient failure permanent — and guarding
   recovery breaks the remedy recovery exists to be. Pinned by `AdmitDeviceRoutingTest.anA1Holder_reRegisters_evenAfterItsIdentityWasRemoved` and `RecoveryRoutingTest.recoveryWorks_forAnIdentityThatWasPreviouslyRemoved`. ⇒ the ledger
   belongs to the join and offer flows, where a device presents itself **without** A1, and it is
   fully enforced there.

   ⚠⚠ **And this proposal makes it worse unless it is closed first.** `ReadmitDeviceImplementation`
   treats **row presence** as proof the device was not revoked. Keeping a revoked device's row — which
   is the whole point of marking rather than deleting — removes that inference.
   ⇒ **prerequisite 0 grows a second half**, and it is **client-side**:
   the readmit path must consult the `DeviceRevocation` record instead of inferring from row
   presence. ⚠ Not a relay change — the relay's A1-gated routes stay as they are, for the reason
   above.

   So the question is what **triggers** removal, and which routes **honour** it.

   ⇒ **Recommended: an append-only `DeviceRevocation` record keyed on `(deviceId, authoredHlc)`, in
   parallel with a new relay device-access route.** ⚠ The stamp is **in the key**: keyed on the
   device alone, two revocations are two writes to one row and whole-entity LWW can let an older
   delayed write replace a newer one (below). ⚠ **Not** a flag on the `Device` row — the row may be
   absent exactly when revocation is needed, and a field would inherit whole-entity LWW; both
   reasons are below. The two planes learn it differently and should stop sharing one artifact:

   | | carries | why it cannot be the other one |
   |---|---|---|
   | **client plane** | ⭐ a small **append-only `DeviceRevocation` record** keyed on **`(deviceId, authoredHlc)`** — *not* a field on `Device`, and not keyed on the device alone; see below | the relay **cannot read it** (HR #1 ciphertext), so it can never be an access fence |
   | **relay plane** | a **new** device-access revocation route — `revokeIfNotStale` + `recordRemoval`, carrying the **authored** HLC, behind a durable queue | it is a clear-text device id, so it can never be the roster's statement without disclosing the roster |

   **What the flag buys beyond removing the tombstone dependency:** a removed device becomes
   *marked* rather than *absent*. Absence is indistinguishable from an orphaned or never-seen
   identity, which is the failure where one stale device id blocks reclamation realm-wide; a
   positive statement ends that class, and gives the UI something to show.

   ⚠⚠ **But only if every roster consumer is taught to filter — otherwise it makes that failure
   WORSE.** `RealmConvergence.admittedDeviceIds` reads `IdentityService.Relations.DEVICES` and treats
   every row as an active peer, precisely *because* revocation deletes the row today. Keep the row
   and mark it, and the revoked device stays in `ReclamationBarrier`'s peer set for ever — the
   realm-wide stall this design is meant to end, now permanent and by construction rather than by
   accident. ⇒ the flag and the filtering land **together**, and "which code reads the device list"
   is an enumeration the implementing slice owes before it writes the field: the barrier's roster,
   the epoch count, and any UI or capability check that treats presence as membership.

   - ⚠⚠ **A NEW route, not an upgraded `/sync/admission/revoke`.** That route is posted once **per
     member of the admission subtree** (`for (member in admissionSubtree)`), and its contract keeps
     those descendants as realm members while stripping only their minting power — *"only the revoked
     device's row disappears; subtree members keep theirs"*. Teaching it to call `revokeIfNotStale` +
     `recordRemoval` would therefore revoke every descendant's JWT and fence all of them from
     re-admission for ever, on a user gesture that removed **one** device. ⚠⚠ That is §7c's two
     populations in one call — the precise mistake `recordRemoval`'s contract exists to prevent, and
     it would arrive through the fix for it. ⇒ device-access revocation is its own signed request,
     naming the one removed device.
   - ⚠⚠ **Not `isAuthorized`.** That field already means *pending approval* — a device self-publishes
     `isAuthorized = false` during pairing and it is flipped true on admit. Reusing it would put a
     not-yet-admitted population and a never-again population in one field: the exact conflation
     `recordRemoval`'s own contract warns about (*"one ledger holding two populations that need
     opposite treatment"*).
   - ⚠ **The record is additive, which is the cheaper half of why it wins.** A new composite type is
     registered, not reshaped, so it owes no positional migration of `Device`. ⚠⚠ The `admittedAtHlc`
     the ordering rule needs (below) *is* a reshape of the positional `Device` composite, so that one
     owes an Identity service version bump with a registered `SchemaMigration` (HR #9) — without which
     existing device rows misparse or the service store is wiped. A revocation mechanism whose rollout
     wipes the device list is not a revocation mechanism.
   - ⚠⚠ **A separate composite is not automatically append-only — the KEY has to make it so.** Keyed
     on the `deviceId` alone, two revocations of the same device are two writes to **one** row, and
     `Device`-style whole-entity LWW then lets a *later-emitted* block with an **older** authored
     stamp win: peers discard the newer revocation, treat an intervening admission as active, and
     diverge from a relay that is still revoked. ⇒ the key includes the **authored stamp**, so each
     revocation is its own row and readers take the highest — append-only by construction rather than
     by intention. ⚠ A single row per device would need an explicit semantic merger, and there is no
     reason to prefer one when the key can carry the ordering.
   - ⭐⭐ **Why a separate record rather than a field on `Device` — two independent reasons, and they
     agree.** First, the merge: see below. Second, and decisive, **the row may not be there to mark.**
     `RevokeDeviceImplementation` deliberately still revokes by id when the local `Device` row is
     absent but `wasRealmMember` succeeds — a degraded roster is exactly when a user reaches for
     *"remove this device"*. A marker that is a field on `Device` cannot be authored then: `name` and
     `publicKey` are required and unavailable, so the revocation would reach the relay and **no
     positive statement would ever reach peers**, leaving their `RealmConvergence` waiting on a device
     that is gone — the stall this design exists to end, in the one case that most needs it. A record
     keyed on `(deviceId, authoredHlc)` is authorable from what the caller always has — the device id
     it was given and the stamp it is authoring now, neither of which needs the row.
   - ⚠⚠ **And the marker needs one-way merge semantics of its own.** `Device` has no semantic merger,
     so it falls back to **whole-entity LWW**: an ordinary `Device` block authored later — a rename,
     a re-registration — carries a row without the flag and **erases the revocation**. The persisted
     epoch floor does not save this; it keeps the *counter* from rewinding while the client-visible
     roster silently un-revokes. ⇒ either `revokedAtEpoch` merges as a **max** (revocation is sticky,
     and un-revoking is an explicit re-admission rather than a merge artifact), or the fact lives in
     its own **append-only** record and the `Device` row only reflects it.
   - ⚠⚠ **The record needs the same staleness rule the relay already applies.** Append-only gives it
     monotonicity, not *ordering*: a revocation authored before a legitimate re-admission and pushed
     after it would be refused relay-side by `revokeIfNotStale` — correctly — while peers, reading an
     unordered marker, would filter an active device out of their rosters for ever. ⇒ the record
     carries its **authored HLC**, and an admission strictly newer than it supersedes it. The client
     rule and the relay rule are then the same rule, which is the only way the two planes stay
     consistent without talking to each other.
     ⚠⚠ **But peers cannot evaluate that rule today, and the obvious substitute is wrong.** `Device`
     exposes `admittedAtEpoch`; `admittedAtHlc` is sent to the **relay** and is not persisted in the
     synced row, so a peer has no admission stamp to compare against. ⚠ Reaching for the row's own
     `SyncBlock` HLC instead would be worse than nothing: an ordinary later edit — a rename — would
     then "supersede" a real revocation and silently un-filter a removed device. ⇒ **persist and sync
     an `admittedAtHlc` on the `Device` row.** It rides the same migration the revocation record
     already owes, so it costs a field rather than a phase.
     ⚠⚠ **And it must be the SAME stamp the relay registered, captured once.** `ApprovePairingRequest`
     and `ApprovePairingOffer` call `admissionStamp()` inline for the relay request and build the
     synced `Device` afterwards; an implementation that ticks the clock again for the new field
     produces a client stamp *later* than the relay's, and a revocation authored in between is then
     stale on one plane and live on the other — the two rules diverge precisely where they were
     introduced to agree. ⇒ capture the stamp once and hand the same value to **every flow that registers a key** — ⚠⚠ which
     is more than the two pairing approvals. `RealmRegistrar` mints fresh route-only stamps at three
     sites without touching the synced `Device` row, and `/sync/recovery-claim` registers with **no**
     admission stamp at all. ⚠ And the two cases fail *differently*: `RealmRegistrar` leaves the relay
     holding a stamp the row does not know, so a later revocation is stale on one plane and live on
     the other; recovery leaves it holding **none**, and `DeviceRegistry`'s rule is that *an absent
     stamp on either side means "unknown" and the revocation applies* — so every revocation applies
     to a recovered device unconditionally, which is the unsafe direction. ⇒ registration repair and
     genesis re-assert publish the value they registered with, and the recovery route must **carry**
     one before peers can rely on it at all. ⚠ Same trap as
     the captured-vs-authored stamp the project has hit before: the value a caller ticks is not
     automatically the value the operation records.
     ⚠⚠ **And "both" is three parties, not two — the joiner writes this row last.**
     `PairingKeyInstaller` self-publishes the same `Device` row when pairing completes, and that later
     whole-entity LWW write **wins**; `PairingKeyBlob.Unpacked` carries no admission HLC, so a joiner
     asked to populate the new field would invent its own value and overwrite the admitter's. ⇒ the
     stamp travels **in the pairing blob**, so the joiner writes the value it was admitted at rather
     than one it made up. ⚠ Otherwise the field is correct on the admitter's row for exactly as long
     as it takes the joiner to sync.
     ⚠⚠ **But carrying it is not authenticating it, and the difference is exploitable.** The joiner
     *self-publishes* that row: while its A4 is still admitted, a compromised one can write an
     arbitrarily **future** `admittedAtHlc`. The relay is unaffected — it compares against the stamp
     the **admitter** registered and cuts access — but peers compare against the forged row, conclude
     the revocation is stale, and keep a dead device in the active roster. The planes then disagree in
     exactly the harmful direction: access gone, roster intact, reclamation stalled. ⇒ peers trust
     only a stamp they can **verify was the admitter's** — carried inside the signed admission
     material the chain already validates, not inside a row its subject writes. ⚠ Anything
     self-published is a claim, and a revocation rule that trusts the revoked device's own claim is
     not a rule.
     ⚠ `admittedAtEpoch` is a tempting approximation and an incomplete one: a revocation bumps the
     epoch, so a re-admission that has *seen* it carries a higher value and compares correctly — but
     one minted by a device that had not yet seen the revocation carries the same epoch, which is
     exactly the ambiguity the HLC exists to resolve.
   - ⚠⚠ **Keep the epoch monotonic.** If the epoch counts *currently* revoked rows, a re-admission
     that clears the flag walks it **backwards** and the next capability is minted at a superseded
     epoch — 2026-08-18 again. The flag is append-only per revocation *event*, or the epoch keeps its
     persisted floor.
   - ⚠⚠ **The record must prove AUTHORITY, not just membership — otherwise it is an eviction
     primitive.** An ordinary synced block proves only that *some admitted device* signed it with its
     A4. If peers filter the roster on that alone, any admitted-but-compromised non-owner can publish
     `DeviceRevocation` rows for **arbitrary peers** and remove them from every device's roster and
     reclamation set — while their JWTs keep working, because the relay route was never called. The
     durable intent binds honest writers and does nothing about this. ⇒ the record carries an **A1 (master) signature** and **peers verify it before acting**. ⚠⚠ Not A33:
     `RevokeDeviceImplementation`'s own contract is *"Requires A1 — a delegated (A33-only) admitter
     can remove a device but **cannot master-sign revocations**"*, so accepting A33 here would hand
     every delegated admitter the eviction primitive this rule exists to deny. ⭐ A subject-bound
     **self**-removal (a device signing its own retirement) is the one narrower case worth defining
     separately, since it needs no authority over anyone else.
     ⚠⚠ **And requiring A1 strands a path that ships today.** `RevokeDeviceImplementation` lets an
     **A33-only delegated admitter remove another device**: it deletes the local row and calls the
     route, but it cannot produce an A1-signed marker — so peers keep the target in their roster and
     in `ReclamationBarrier` while the local UI and the relay both report it gone. That is the
     worst-shaped outcome available: the operator believes the device is out, the relay agrees, and
     every peer disagrees silently. ⇒ two ways out, and the choice is a product one:
     | | rule | cost |
     |---|---|---|
     | **a** | **A1 required before the local removal** — an A33-only admitter cannot remove at all | narrows a shipped capability; the delegated admitter can add devices but not remove them |
     | ⭐ **b** | **the A33 admitter queues the removal; an A1 holder signs and publishes it** | the roster update waits for an owner device to come online — a *visible, bounded* delay with an obvious remedy, instead of a silent divergence |
     ⇒ **(b)**, and the queued state must be **shown**: "removed — waiting for an owner device" is a
     true statement a user can act on, where a silent partial removal is not.
     ⚠⚠ **Except a realm can have no A1 left, and then the wait is not bounded by anything.** The
     last owner device lost or removed with the phrase unavailable is a reachable state
     (`admission-revocation-names-the-capability.md`), and in it no owner ever comes online to sign:
     invoke the access route and the target stays in the roster and `ReclamationBarrier` for ever;
     wait for A1 first and it keeps relay access instead. ⇒ this is not a gap in the rule — it is a
     property of the realm, and the rule's job is to **name it rather than pretend**:
     - the **access** half still works, though not for the reason an earlier revision gave. ⚠⚠
       Correcting it: `POST /sync/admission/revoke` verifies against `realm.masterPublicKey` — it is
       **A1**, not A33 — so a no-A1 realm cannot revoke an admission *capability* at all. What still
       cuts a device's **access** is the `Device` tombstone: `SyncRouting` calls
       `knownDevices.revokeIfNotStale` for any block whose `targetEntityType` is `Device.ID`,
       authorised by nothing but the pusher's JWT. ⇒ a no-A1 realm can still evict a device from the
       relay, because that path never needed an owner.
       ⚠⚠ **And that is worth saying plainly rather than banking:** the shipped access cut is
       available to **any admitted device**, which is precisely the eviction primitive §5b spends its
       length denying on the roster plane — pointed at the *stronger*, irreversible plane. The split
       is currently inverted: peer *belief* is A1-gated by this design, while peer *access* is gated
       by membership alone. Closing that is not this document's change, but a reader must not take
       "the access half still works" as reassurance that it is well-guarded.
     - the **roster** half cannot be authenticated by anyone, because there is no authority left to
       authenticate it. Peers keep the device, deliberately: the alternative is accepting an
       unauthenticated roster change, which is the eviction primitive this whole section refuses.
     - so reclamation **stalls** rather than proceeding on unsigned word — the same direction chosen
       everywhere above — and the UI says the true thing: *"removed from the relay; peers cannot be
       told until this realm has an owner device again."*
     - the remedy is restoring A1 — phrase recovery, or re-genesis — which is a product decision this
       proposal points at rather than invents.
     ⚠ What must **not** happen is a fallback that lets some lesser authority write the roster
     "because no owner is available". Availability is not permission either. ⚠ Same principle as the admission stamp
     above: membership is not permission, and a roster rule that accepts any member's word about any
     other member has inverted the thing it protects.
   - ⚠⚠ **And the record must survive an ordinary tombstone, or the same attacker just deletes it.**
     A compromised member that observes a legitimate `DeviceRevocation` knows its
     `(deviceId, authoredHlc)` key and holds the realm key, so it can derive that row's wire id and
     emit a perfectly ordinary A4-signed `DeleteBlock` for it. The generic tombstone path applies
     that without asking who may revoke — and once the author's durable intent has cleared, nothing
     republishes the marker, so peers restore the device to the roster while the relay still refuses
     it. ⇒ `DeviceRevocation` is **exempt from generic deletion outright** — peers ignore any tombstone
     targeting the type. ⚠⚠ Not "honoured when it carries the revoking authority", which is
     unimplementable as the wire stands: `DeleteBlock` carries only block id, owner, HLC, target hash
     and type, plus an optional revoked device id, and the relay does not retain the pushing JWT
     identity — so **no authority proof reaches the ingesting peer at all**, and a malicious tombstone
     is indistinguishable from a legitimate one. Exempting the type needs no proof plumbing — but ⚠⚠ **it does need the peer to know the tombstone
     targets a revocation, which EI-4's own narrowing takes away.** With every non-device tombstone
     mapped to one sentinel, a peer that has not already indexed that `DeviceRevocation` cannot tell
     what the tombstone is for — and `SyncProcessor.processDeleteBlock` records a tombstone **by hash
     before** `resolveDataId`, so a compromised member can derive the record's wire id and race a
     higher-HLC tombstone to a peer that has not yet seen the record, suppressing it pre-emptively.
     ⚠⚠ **A third discriminator value does not work either — and withdrawing it is the right answer.**
     An earlier revision added `revocation-record` beside `device` and `other`. But the **tombstone's
     author sets that field**, so a compromised member simply labels its attack `other`, and a peer
     that has not indexed the record has nothing to check the label against. Protecting a kind by
     trusting the attacker's declaration of the kind protects nothing.

     ⇒ ⭐⭐ **the record outranks the tombstone, by authority.** A `DeviceRevocation` carries an
     A1 signature; a tombstone carries none. So the rule needs no discriminator at all: **an
     authority-signed revocation record is applied even if a tombstone for that id was already
     recorded**, and a tombstone never suppresses one. The pre-emptive strike stops working because
     the thing it suppresses arrives with a proof the tombstone cannot match. ⚠ `processDeleteBlock`
     recording tombstones by hash before resolution is then harmless — the record wins on arrival,
     whenever it arrives.
     ⭐ And EI-4 keeps its **two** values: the concession made one revision ago is handed back.

     ⚠⚠ **But client-side precedence cannot help a device that arrives after GC.** `MailboxStore.gcSweep`
     drops block bodies once the quorum and retention window pass, leaving an empty envelope — so a
     device admitted later receives the attacker's retained tombstone and can never fetch, decrypt or
     verify the record it was supposed to outrank. Tombstones are kept indefinitely (ADR 0006) while
     the record's body is not, and that asymmetry is the whole attack.
     ⇒ **a late joiner must not reconstruct revocation state from the log** — ⚠⚠ **and must not take
     the relay's word for it either.** An earlier revision pointed late joiners at
     `admission_revocation` and the removal ledger. That is worse, not better: the relay is
     **untrusted** by the threat model, `removed_device` carries no client-verifiable proof and
     survives an A1-authorised re-registration, and `admission_revocation` means *loss of minting
     power* and deliberately includes descendants who remain realm members. Reading either as "who is
     revoked" makes an untrusted party's stale bookkeeping into authoritative client state.

     ⇒ ⭐⭐ **the revocation set leaves the block plane.** An earlier revision answered "retain
     `DeviceRevocation` bodies through GC and serve them to late joiners as opaque blobs". That
     cannot be built: a record is an ordinary `SyncBlock`, so the relay sees owner, HLC, opaque
     target hash and ciphertext — **the type and the A1 signature are both inside the payload**, and
     `gcSweep`, which keys on `(ownerId, targetEntityIdHash)`, has nothing to exempt *on*. Asking an
     untrusted, plaintext-blind relay to recognise a class of row is not an exemption, it is a wish.
     ⚠ And a relay-visible discriminator would only move the problem: the block's **author** sets it,
     which is the same trap EI-4's withdrawn third tombstone value fell into.

     ⇒ instead the realm publishes an A1-signed **revocation roster**: one document per realm, in its
     own relay table behind its own route, carrying

     | field | why |
     |---|---|
     | the complete set of revoked `(deviceId, authoredHlc)` entries, **as ciphertext** | the unit that is signed — see completeness below. ⚠ **Correcting the earlier justification for the encryption**: it is *not* that the relay would otherwise learn the revoked set, and *not* I6. The relay already holds that set in the clear and permanently — the access route takes a cleartext device id, `revokedDeviceId` rides every device tombstone in the clear, and `removed_device` is a table `DeviceRegistry` says can never be garbage-collected — and I6 is the **no-silent-censorship** (liveness) invariant, not a confidentiality one. What encryption buys is narrower and still worth it: the roster is a *signed, ordered* artefact rather than a second cleartext ledger the relay can quote back as authority, and it keeps `authoredHlc` and the set's exact membership-over-time out of a third place. ⚠⚠ The cost is real and now visible: it is what forces the outer signature, and with it the second message shape below |
     | a monotonic `version` **plus the digest of the version it replaces** — both in the clear, beside the ciphertext | anti-rollback, the pull cursor's cheap "is there anything new" check, the relay's compare-and-swap, and — see below — the thing that makes a version *unique*. ⚠ Digests are taken over the **ciphertext**, so the relay can chain and swap without reading anything, and a client verifies the same bytes it was served |
     | **two** A1 signatures — an inner one inside the ciphertext over `(tag ‖ len‖ownerId ‖ version ‖ len‖predecessor digest ‖ len‖the sorted set)`, and an **outer** one over `(tag′ ‖ len‖ownerId ‖ version ‖ len‖predecessor digest ‖ len‖digest of the ciphertext)` — ⚠⚠ **distinct domain tags and length-prefixed fields**, which this repository already learned the hard way: `AdmissionCapability`'s two shapes carry `tenshu-admit-cap-v1` / `tenshu-admit-revoke-v1` precisely because *"both messages are bare concatenations with no length prefixes, so under a shared tag a v1 message for one device is byte-identical to a v2 message for another"*, pinned by a collision test. These two shapes differ only in a variable-length tail, and the inner signature is readable by **every** realm member (the payload is encrypted under the shared volume key, which a revoked-but-not-yet-rotated device still holds) — so an untagged inner signature is a candidate outer signature over a ciphertext of the attacker's choosing | the inner one is authority, *coverage* and ancestry, verified by clients. ⚠⚠ The outer one exists because the relay cannot read the inner: with no A1 proof it can check, **any admitted device** could submit arbitrary bytes at the current predecessor, win the compare-and-swap, and leave a retained head that no legitimate owner can extend without chaining through an unverifiable version. The relay knows the realm's A1 **public** key, so it can authorise the publish without decrypting anything — ⚠⚠ the predecessor must be **inside** the signature: left out, a malicious relay rewrites that one field on an otherwise valid document and re-parents a set that omits revocations onto whichever fork a client holds, forging nothing |

     Three properties fall out, and each answers a failure the per-record design could not:
     - **GC cannot reach it.** The document is not a block and never enters the mailbox, so the sweep
       that drops bodies never sees it. ⚠⚠ The fix is not a better exemption — it is *not being in
       the place exemptions are needed*. Nothing about the relay's plaintext blindness matters any
       more, because it is no longer asked to classify anything.
     - **Omission requires forgery.** ⚠⚠ Per-record signatures authenticate only the records the
       relay *chooses to return*: a malicious relay serves the attacker's retained tombstone, omits
       the matching record, and the joiner cannot tell that from a realm where nobody was ever
       revoked. A signature over the **whole set** removes the choice — dropping one entry invalidates
       the signature, and there is no A1 key to re-sign with.
     - **Serving an old set is caught by a floor the relay does not control.** The joiner's
       **admission material** carries the roster **digest** current at the moment it was admitted —
       ⚠⚠ the digest, not the version number: forks can share a version, so a joiner admitted from
       fork A would accept fork B on a numeric floor even where B drops A's revocations. The digest
       names one document. That material is signed by its admitter and already validated by the chain — the very same
       discipline `admittedAtHlc` uses two bullets up, pointed at the roster instead of the device. A
       relay replaying an older document is then detected on the joiner's *first* read, not never.
       ⚠ Established devices need nothing extra: each keeps the highest version it has seen and
       refuses a lower one — ⚠⚠ and compares the **digest chain**, not the number, since two
       documents can carry the same version.

     ⚠⚠ **Two owner devices can sign the same version, so the version needs a predecessor.** Two A1
     holders revoking different devices from version N each legitimately sign an N+1 containing only
     their own revocation; both are valid, the relay forges nothing by serving either, last-writer-wins
     loses a removal permanently, and a bare version floor cannot even tell the two apart. ⇒ each
     version **commits to the digest of the one it replaces**, and a publish is a compare-and-swap
     against the document the relay holds: the second signer is refused, re-reads, and re-signs over
     the union. ⭐ **And a union is always available** *to a device that sees both forks*: the set is
     append-only and every version carries its own A1 signature, so two successors claiming the same
     predecessor are mergeable, and an A1 holder that sees both signs the union, which supersedes
     them.
     ⚠⚠ **Correcting an over-claim: "the relay can delay a revocation, it cannot erase one" is false
     for a fork.** A compromised relay serves only fork B to the devices anchored on B and withholds
     A indefinitely; those clients then verify a fully valid chain that simply never contained A's
     revocation, and cannot construct the union because they have never seen the other half. ⇒ two
     partial answers and one residual, stated rather than papered over:
     - **Forks are detectable by gossip, not by the relay.** Each device publishes the roster head
       digest it has adopted in the device-status block it already emits. A peer that sees a head it
       cannot derive from its own chain has evidence of a fork and surfaces it; an A1 holder then
       signs the union. ⚠ The evidence rides the same untrusted transport, so this raises the cost of
       equivocation rather than preventing it: the relay must now keep two populations apart on
       *both* planes.
     - **The blast radius is bounded by which side is authoritative for what.** The relay's ledger is
       authoritative for **access**, and the route that cuts it does not consult the roster — so a
       hidden fork costs peers their *knowledge* of a revocation, not the revoked device's loss of
       access. Those peers keep waiting for a device the relay already refuses: the stall, again.
     - ⚠⚠ **The residual:** against a relay that equivocates consistently on both planes, a
       revocation can be withheld from part of the realm indefinitely. Closing it needs an anchor
       outside the relay, which this design does not have and does not pretend to.
     ⚠⚠ **And a device with no A1 must still be able to leave.** `ForgetThisDevice` runs on ordinary
     paired joiners, which hold no master key and so cannot produce the only signature above — leaving
     the roster unwritable on exactly the path that ends with the device wiping itself, and future
     peers treating it as active for ever. ⇒ ⚠⚠ **a separate append-only table, not a second section of the roster.** The roster is one
     A1-signed, digest-chained document: adding anything to it changes the digest and needs a
     successor the departing device cannot sign, so "a section" it could write does not exist — it
     could not publish the certificate at all before wiping itself, and no owner could ever absorb
     what was never stored. ⇒ self-removals live in their **own relay table**, append-only, each
     entry a certificate signed by the departing device's own **A4** — no A1 in its publication path,
     and no roster digest touched.
     ⚠⚠ **And the certificate must prove the A4 belongs to the device it names.** The admission chain
     does not do that: `AdmissionCapabilityCert` binds an **A33** public key to a device id, while a
     device's A4 is bound separately, by the pairing `masterDelegation`. A certificate that merely
     ships "an A4 signature plus an admission chain" lets a compromised member sign a retirement for
     *another* device with its own A4 and attach the victim's public A33 chain — and an owner
     absorbing it then runs the access-revocation route and evicts the victim. ⇒ the certificate
     carries the **A4→device delegation** and a verifier checks that the key that signed is the key
     delegated to the device named.
     ⚠⚠ **And an authored HLC, under the same staleness rule as `DeviceRevocation`.** Without one the
     certificate is timeless, and the relay's *permitted* withholding turns into an eviction
     primitive: device D self-removes at T0 and its access is cut; D is later re-registered under the
     same id (⚠ `/admit-device` and `/sync/recovery-claim` deliberately do **not** consult
     `wasRemoved`); the relay then releases the withheld certificate, the absorbing owner runs the
     route, and `DeviceRegistry`'s *"an absent stamp on either side means unknown and the revocation
     applies"* cuts the **live** D and evicts it from the roster. ⇒ the certificate is stamped when
     authored and refused against a later admission — the identical rule §5b already makes mandatory
     for the A1 path, and the reason the two planes stay consistent. ⚠ It also bounds the withholding
     concession properly: a withheld certificate then expires into irrelevance instead of ageing into
     a weapon. ⚠ Same rule as everywhere else in this section, one level down:
     a signature is only evidence about whoever the reader can prove holds the key. ⚠ This is the narrow case §5b set aside earlier — a device
     asserting something about *itself* needs authority over no one, which is why it may use a key
     that confers none. ⚠⚠ **But authentic is not yet effective, and making it so
     would hand over an escape hatch.** A device whose A4 is compromised can publish its own
     certificate and simply **skip** the route that cuts its access — peers drop it from the roster
     and from `ReclamationBarrier` while its JWT keeps working, which is precisely the safety failure
     the route-first ordering below exists to prevent. A durable intent binds the honest
     implementation and says nothing about this one.
     ⇒ a self-removal certificate **takes effect on the roster only once an A1 holder absorbs it**
     from that table into the signed set. Until then it is *evidence for an owner device to act on*, not an
     authority: the device stays in the roster and the barrier. ⭐ Which is the same answer §5b
     already gives the A33 admitter who cannot master-sign a removal — a visible, bounded wait for an
     owner device ("leaving — waiting for an owner device") instead of a silent divergence, and the
     same reason: membership is not permission. ⚠ Relay-side proof is **not** the alternative: a
     statement from an untrusted relay that a device is deregistered is the eviction primitive again,
     pointed at whoever the relay dislikes.
     ⇒ and the ordering already holds the durable half — the certificate is written to that table
     **before** the route call, so it outlives the wipe that follows and is there for the owner to
     absorb. ⚠ The table's completeness is weaker than the roster's: a relay can withhold an entry.
     That leaves the device **in** the roster, which is the stall direction, and the absorption fixes
     it permanently once an owner reads the table.

     ⚠⚠ **Every version is retained, because a chain of one link cannot be verified.** A device
     admitted at version N and first reading at N+2 is served a document committing only to N+1: it
     cannot check that N+2 descends from the N its admission material named, and its two options are
     both wrong — accept, and a relay can switch it onto an unrelated valid fork; reject, and it
     never bootstraps. ⇒ the relay keeps the **signed intermediate versions** and serves the chain
     from the client's anchor forward. ⚠ The cost is bounded by revocation *events*, not by time or
     device count — a few hundred bytes each, in a realm that revokes a handful of devices in its
     life. ⚠⚠ A truncated chain is not a reason to accept the head: a device that cannot walk from
     its anchor **stalls** — it keeps the roster it has and asks a peer — which is the liveness
     direction this whole section chooses over the alternative.

     ⚠⚠ **And a recovery-created device has no anchor at all — which is a residual, not a solved
     problem.** A `RecoveryClaim` device starts from the phrase alone: no admitter to carry a digest,
     no persisted highest version. Once notification bodies are GC'd, a malicious relay can serve it
     any old correctly-A1-signed roster, which verifies perfectly while omitting every later
     revocation. ⇒ two things, and neither is a fix:
     - the roster is **advisory until a peer anchors it**. A recovery device may act on it to *keep*
       devices in the roster and the barrier, never to **shrink** either — so a rolled-back document
       stalls reclamation instead of dropping a live device, and the first sync with any live peer
       supplies a real anchor (that peer's own highest digest, over the block plane, not the relay's
       word).
     - the rollback window is an **accepted residual** and must be written into the threat model as
       one, with the honest statement: a device that recovers from the phrase, has never met a peer,
       and is served by a hostile relay cannot tell a current roster from an old one.
     ⚠ Anything stronger needs an anchor outside the relay — the phrase carries none, and inventing
     one (a digest the user reads aloud) is a product decision this proposal does not get to make.

     ⚠ What the relay can still do is **withhold** — serve a stale-but-valid document and starve a
     revocation of delivery. That is the pre-existing liveness failure ("the revocation has not
     arrived yet"), not a new attack: it cannot invent a revocation, resurrect a revoked device
     against a peer that has seen the newer version, or hide *which* devices a version covers.

     ⚠⚠ **Which side is authoritative, now that there are two carriers.** The roster is; the
     `DeviceRevocation` block is a **notification** — a fast path so live peers act within a sync
     tick instead of waiting for a roster read, applied immediately because it carries the same A1
     signature they can verify, and reconciled against the roster afterwards. ⇒ the
     "record outranks the tombstone" rule above survives, but it is no longer load-bearing: suppress
     the notification and you delay convergence by one roster read, where before you *were* the
     late-joiner attack. A peer that never sees the block converges anyway.
     ⚠ The publishing order gains a leg accordingly — the roster write joins the durable intent
     below, and it is the half that must not be lost, because it is the one late joiners read.
     ⚠ Three
     variants of one mistake have now been found here — forge an admission stamp, forge a revocation,
     delete a revocation — and they share a root: *a synced row is only as trustworthy as the
     authority the reader verifies, and "an admitted device signed it" is not one.*
   - ⚠ **Say which side wins.** The relay's ledger is authoritative for **access**; the A1-signed
     **roster document** is authoritative for the **roster and the UI**, with the `DeviceRevocation`
     block only its notification. They can diverge, and a reader who does not know which is which
     will pick the wrong one.
   - ⚠⚠ **The two planes cannot be made atomic, so the ORDER is the design.** Each is acknowledged
     separately and a crash can land between them, leaving contradictory durable state — and the two
     orders fail very differently:
     | order | if the second half is lost |
     |---|---|
     | **record first**, then route | peers drop the device from the barrier while its **JWT still works** — it can keep writing into a realm that has stopped waiting for it. A safety failure |
     | ⭐ **route first**, then record | the device loses access immediately; peers keep waiting on a dead roster entry until the record's durable retry lands. A **liveness** failure, and a temporary one |
     ⇒ **route first.** Revocation is a security act, so the half that must not be lost goes first.
     ⚠⚠ **But "the stall resolves itself" is only true if something durable remembers the second
     half.** A crash after the relay acknowledges the route and before `DeviceRevocation` is durably
     enqueued leaves a completed, non-retryable request and no marker to deliver — peers treat a
     revoked device as active for ever, and `ReclamationBarrier` stalls permanently. That is not a
     temporary stall; it is the permanent one wearing its clothes. ⇒ **both orders are wrapped in one
     durable intent**, written before either half and cleared only when both have landed, with each
     half idempotent so a restart can repeat whichever is outstanding. The intent is the same
     mechanism in both cases; only the order of the halves differs — route-then-record normally,
     record-then-route when a device revokes itself.
     ⚠⚠ **Three halves, since the roster.** The set a late joiner reads is the roster document, so
     the sequence is **route → roster → notification block**, all three under the one intent. ⚠ The
     roster write is the half whose loss is unbounded: lose the notification and live peers converge
     on their next roster read, lose the roster and every *future* device believes the revocation
     never happened. ⇒ the intent is cleared only after the roster is acknowledged, and the
     notification may be retried after it. ⚠⚠ **The self-revocation path is NOT this sequence, and cannot
     be.** An ordinary paired joiner running `ForgetThisDevice` holds A4 and no A1, so it can write
     neither the A1-signed roster successor nor the A1-signed notification record — demanding either
     before the route leaves it unable to leave at all. ⇒ a device retiring **itself** publishes its
     **A4-signed certificate** to the self-removal table (above) and then calls the route:
     **intent → certificate → route → clear intent**. The roster and the notification are an A1
     holder's later work, absorbing that certificate, and the departing device neither performs nor
     waits for them. ⚠ An A1 holder retiring itself may of course do both in one go, but it takes the
     same path: the certificate is what makes the removal durable across its own wipe.
     ⚠⚠ **Except when the device is revoking ITSELF, where the order inverts.** `ForgetThisDevice`
     retires the caller: route first cuts its own JWT, and the implementation then purges its outbound
     queues and custody, so the "durable retry" can never authenticate and the record is never
     published — peers keep an active roster entry for a device that is gone, which is the stall this
     was meant to end, made permanent. ⚠ The safety argument also does not apply here: a device
     removing *itself* is not the threat the ordering protects against. ⇒ self-revocation publishes
     the **certificate** first, and tries to confirm it is durably stored before calling the route.
     ⚠⚠ **Tries — it must not WAIT on it, and an earlier revision got this backwards.**
     `ForgetThisDeviceImplementation` states the shipped rule outright: *"A failed retirement never
     blocks the wipe. People forget a device precisely when something is broken, and a refusal would
     strand them"* — the push is bounded by `RETIREMENT_PUSH_TIMEOUT_MS`, and then execution
     continues to `purgeOutboundQueues()`, `wipeLocalStore()` and `forgetThisDevice()` regardless.
     Requiring a durable confirmation would reverse a rule written against a real stranding failure.
     ⇒ **the departing device is best-effort by construction, so the durable half cannot live on
     it.** Its queued publish is purged, its store — holding the intent — is wiped, and the A4 that
     could authenticate a retry is destroyed: there is no restart, because the device that owned the
     intent no longer exists. ⭐ Which settles where the obligation belongs: **an A1 holder can
     author the removal without the departing device's help at all**, exactly as §5b's A33 case
     already queues one. The certificate is an *accelerator* — when it lands, the owner absorbs it
     and the roster converges quickly; when it does not, the removal is the ordinary owner-signed one
     the user asks for. ⚠ The failure mode of the best-effort path is therefore a stall an owner can
     end, not a permanent divergence.
     ⚠⚠ **With a durable route intent written before either**, or the exception recreates the
     half-applied state the table above rejects: crash after the certificate is stored and before the
     route is called, leaving a device that has announced its departure with a JWT that still works
     and no record of an attempt to cut it. The intent is what makes the restart recoverable, and it
     is recoverable *because* the route has not run yet: this device still holds a valid JWT and can
     complete it. ⇒ **intent → certificate → route → clear intent**, with the certificate step bounded rather than
     awaited, and an owner-signed removal as the path that does not depend on this device at all.
     ⚠⚠ **But peers do NOT filter on the certificate alone, and that is what keeps this crash a
     liveness problem rather than the escape hatch.** A stored-but-unabsorbed certificate is evidence,
     not authority (above): the device stays in the roster and in `ReclamationBarrier` until an A1
     holder absorbs it. So the crash window costs a stalled reclamation, not a device that peers have
     stopped waiting for while its access is intact. ⚠ Otherwise a compromised A4 simply publishes
     its certificate, skips the route, and gets exactly the outcome the ordering exists to deny.
     ⇒ and the absorbing A1 holder **executes and acknowledges the access-revocation route itself**
     before signing the roster successor — the route is idempotent, so running it again after the
     departing device already did costs nothing, and running it when that device never got there is
     the whole point. Absorption is the moment both halves are known true, which is why it is the
     moment the roster changes.
     ⚠⚠ **And the intent carries the revocation's authored HLC, which both halves reuse.** If the
     route request and the `DeviceRevocation` record stamp themselves separately, a legitimate
     admission landing between them lets the two staleness checks reach **opposite** verdicts — the
     relay revoking an admission peers preserve, or peers filtering a device whose relay access is
     intact. One stamp, written into the intent before either half runs, is what keeps the planes
     agreeing: the same discipline the admission stamp needs, pointed the other way. ⚠ Claiming atomicity here would be worse than choosing: it cannot be
     delivered across two planes that acknowledge independently.
   - ⭐ **Independent cleanup, worth doing but not part of this:** the relay's own `revoke` is a hard
     DELETE plus a separate ledger row, so one device's state lives in two tables that can drift. One
     row with a state would answer both questions — ⚠ provided it keeps *which kind* of removal it
     was, since shared-realm member removal must stay re-mintable while owner removal must not.

1. ⚠⚠ **The epoch has two definitions that must agree — and a third source under both.**
   `SyncManager.currentRealmEpoch` is `maxOf(realmEpochProvider(), seededRealmEpoch)`: the client's
   **count of `Device.ID` tombstones**, floored by a **persisted seed**; `GET /sync/realm-epoch`
   exists so a recovered device can learn the same number before it has synced any. Counting a
   different table relay-side lets the two drift — and the epoch gates admission capabilities, so
   drift is not cosmetic. Removal moves the *definition*, on both sides.
   - ⚠⚠ **And the two sides already count different things — pick one before moving the source.**
     The relay's `SqliteSyncMailbox.deviceTombstoneCount` is `COUNT(*)` over retained `delete_block`
     rows; the client's `TombstoneRegistry` increments only when the entity hash is **new**. Revoke
     the same device identity twice and the relay says 2 while a peer says 1 — and only the
     *revoking* client gets `seedCurrentRealmEpoch(epoch + 1)`, so a peer that merely ingests the
     duplicate tombstone stays low while the relay has moved. A capability minted against the peer's
     number is then already superseded relay-side, which is the failure mode of 2026-08-18 wearing a
     different hat. ⇒ the removal slice **decides the cardinality** — distinct-identity or
     per-revocation — and makes all three sources agree on it. It is not a detail to settle in
     whichever module is edited last.
   - ⚠⚠ **The floor is not an optimisation, and a removal plan that replaces only the count loses
     it.** Revoking the **same** device identity twice does not advance a *distinct*-tombstone count,
     so `RevokeDeviceImplementation` calls `seedCurrentRealmEpoch(epoch + 1)` explicitly — after a
     2026-08-18 production case where a desktop revoked at epoch 2 and re-admitted 23 s later at
     epoch 2 was refused `403 Admission capability has been revoked` for ever, since the verifier's
     `N >= N` makes such a capability born superseded. `PairingKeyInstaller` and
     `RecoveryClaimImplementation` seed it too, and seeding only ever raises. ⇒ whatever replaces the
     count must carry the **monotonic floor** with it, or a later admission can be minted at an
     already-revoked epoch — a security regression, not a counting bug.
2. ⚠⚠ **The tombstone is the durable path; the strip is the lossy one** — and the code says so:
   *"deliberately best-effort … the tombstone above is the primary revocation"*. The tombstone rides
   the pending-tombstones queue and is retried until it lands; `revokeAdmission` is one call that an
   offline relay, a 401 or a 402 drops, re-fired only on the next A1-holder removal. So the explicit
   route needs its own durable queue before it can be the only one.
3. ⚠⚠ **The staleness guard lives on the tombstone.** `revokeIfNotStale` compares the **authored**
   HLC against the admission on record — added after 2026-08-17, when a retirement that could not
   push for hours revoked a device legitimately re-admitted meanwhile. `revokeAdmission` carries
   `epoch` and a wall-clock `revokedAt`, not that stamp.

⚠ And shared realms have `member_revocation` as the analogue, so "count revocations instead"
reconciles two tables, not one.

⚠⚠ **Unrelated to EI-4, but surfaced while checking it and worth settling before EI-2 is scheduled:
EI-2's `fileId` is the thing `file-content-addressing.md` §3.3.1 decided against.** §2.2 proposes
*"a random `valueProperty` minted at creation"*; §3.3.1 (DECIDED 2026-07-16) chose **(A) keep the
converging path identity**, froze `fileId` and slated it for removal, on the grounds that *"a random
`fileId` — as the key or as this §3.3 field — breaks cross-device convergence"*. EI-2 moves the
random id from the key to the wire-id derivation, which does not answer the objection so much as
relocate it: two devices that independently create the same path would mint different ids and derive
different wire ids, which is the convergence case path identity gets for free. ⚠ This proposal's
"Related" list cites §3.3.4 and not §3.3.1, so the conflict is currently invisible from either side.
⇒ **Resolved in §3.3.1's favour: drop `fileId` from EI-2.** The `DataId` already identifies the
entity, the migrator already carries references across a key change, and `File.ContentHash` already
identifies the *content* — so the stable-identity field EI-2 introduces has no job left that another
field is not already doing. ⚠ The honest cost is that a move keeps emitting its old-path tombstone
(shipped) instead of becoming invisible on the wire, which was EI-2's headline benefit. ⚠⚠ And
the thing that looked like it needed a stable *entity* identity — C′'s reference token — needs a
stable **reference** identity instead, which is a different object with none of the convergence
problem (`content-plane-refcount.md` §6e). ⇒ EI-2 narrows to `Syncable.identity` for types that
genuinely need it; `File` is not one of them.

⚠⚠ **And removing the field is not a schema deletion the relay can simply make.** `delete_block`
envelopes are retained indefinitely (ADR 0006), and they contain the positional field: parsing
historical tombstones with a schema that has dropped it misaligns or rejects them, losing deletions
and device revocations that are still load-bearing — the realm epoch is counted from them. ⇒ removal
owes the same retained-envelope migration (or explicit reset) the narrowing avoided, which is a
further argument for narrowing now and removing only when a reset is happening anyway.

⇒ **Take the flag first.** It removes the disclosure for one field change and moves nothing else.
⚠⚠ **And a fifth, which is not work but a waiting condition:** every client that can still *author* a
revocation must be using the new route. A pre-EI-4 build revokes by writing a `Device` tombstone —
`/sync/admission/revoke` strips minting only — so the moment the relay stops recognising the
tombstone's kind, that client's revocations become silent no-ops: the user removes a device, the UI
agrees, and the device keeps syncing. ⚠ The protocol floor cannot establish the condition either,
since `relayProtocolVersionOk` runs at genesis and an established client never presents its version
again (§5b). ⇒ the discriminator is removed only when no such client can be in use — which is a
fleet statement, not a code change, and the one part of this end state that cannot be scheduled.

Removal is the end state, as its own slice, once **all four** have moved — item 0 included, without
which durability, epoch and staleness can all be satisfied while nothing reaches `revokeIfNotStale`
and a removed device keeps a working JWT — **and once that fifth condition holds**. It is then a schema
deletion rather than a semantics change.

---

## 6. Phases

| | what | status | gate |
|---|---|---|---|
| **EI-0** | Threat-model finding: the T-R-08 correction and the collision arithmetic, recorded; audit the `key` / `data.get()` / `personId` sources (§1.1) | ⚠ **part** — finding recorded; the §1.1 audit is **open** (Q1) | doc-only; now |
| **EI-1** | The keyed wire id + the local identity hash split + `RELAY_PROTOCOL_VERSION` minimum bump + reset | ✅ **shipped** | `scripts/run-gate.sh`; one paired-device round trip; the 426 on a pre-EI-1 build |
| **EI-2** | `Syncable.identity` for types that need one; ⚠ **no `File.fileId`** (withdrawn, §2.2); the migrator's *"same identity, new key"* seam | design — **narrowed** | ⚠ **not** the old "no tombstone on the wire" gate — that was fileId's benefit. A move still emits its old-path tombstone; the gate is that a type which *does* declare an identity keeps its wire id across a key change |
| **EI-3** | Photos re-keys `AlbumEntry` / `TrashedPhoto` / `PhotoFlags` / `Album.cover` on a new `File` value property `NodeId` (Q5, chosen 2026-10-29); `contentId` is *content* identity and a replace mints a new one, which is the bug EI-3 exists to fix | ⚠ design — **unblocked 2026-10-29**: Q5 answered by test (a real bug: overwrite-then-move leaves the album, overwrite un-favourites), target chosen; not built | Cheapest before the production reset (in place at v1); afterwards two bumps and a re-keying migration (Q5) |
| **EI-4** | The tombstone's clear `target_entity_type` narrows to a device-or-not value (§5b) | ✅ **value narrowing shipped** with CR-2 B3–B6 (2026-10-04, §5b *As built*); the no-kind end state (§5b preconditions 0–3) is still design | the two relay uses still pass: a Device tombstone revokes, `realm-epoch` counts; a file delete's block carries no type; **and a pre-EI-4 peer still decodes a post-EI-4 tombstone** — the test that rules out the new-field encoding |

EI-1 first and alone: it is small, generic, and closes a threat-model defect. EI-2 is the Drive slice
and should not ride with it.

---

## 7. Open questions

- **Q1. Which `key` / `data.get()` / `personId` sources are meaningful?** The grep counts them; it does
  not classify them. EI-0's audit does, and a meaningful one anywhere is enough to justify EI-1.
- **Q2. Key rotation.** A rotated `sharedVolumeKey` rotates `K_entity` and with it every wire id — a
  full re-emit, which is a re-bootstrap event. Pre-release that is a reset; post-release it needs to
  ride whatever rotation already implies for the log. Is rotation designed anywhere yet?
- **Q3. Does anything derive the hash *outside* the client?** The commerce or hub services should
  not hold one; if any tooling does (`BlobMigration`'s key enumeration reads content refs, not entity
  hashes — to confirm), it needs the local function, not the keyed one.
- **Q4. Width of the local hash.** 64 bits ends the local birthday problem for any realistic list;
  the only reason to go wider is uniformity with the wire id, which is not a reason.
- **Q5. Does an in-place replace drop a photo from its albums today?** `AlbumEntry` is keyed
  `"$albumId|$contentId"` and CR-1 records that a replace mints a fresh `contentId`. If yes, EI-3 is a
  fix, not a tidy-up, and belongs earlier.
  ✅ **Answered 2026-10-29 by test — yes, EI-3 is a fix.** `RealRepositoryReadTest`'s `q5_*` cases
  (`:photos-shared-logic`, the real photo + file proxies under the real repository):
  - The album *listing* survives an overwrite, but only by accident: `TenshuPhotosRepository.photoFor`
    tries the entry's `filePath` hint first, and an overwrite keeps the path. Pinned as a guard.
  - ⚠⚠ **Overwrite, then move** → the photo silently leaves the album (the path hint misses, then the
    `contentId` fallback misses). `@Ignore`d red test.
  - ⚠⚠ The About sheet names no album (`albumsFor(contentId)` is empty); **"Remove from album" reports
    success and removes nothing** (it deletes a key that no longer exists); and an overwrite
    **un-favourites** the photo (`PhotoFlags` has no path hint). Three more `@Ignore`d red tests.
  - An overwrite keeps the path `DataId` and `CreatedAt` and mints a new `contentId`
    (`UploadFileImplementation`); a move keeps every value property (`MoveFileImplementation`'s
    `include(sourceProps)`). **No current `File` identity survives both:** the path does not survive a
    move, and neither `contentId` nor `ContentHash` survives an overwrite.

  **Re-key target, chosen 2026-10-29: a new `File` value property, `NodeId`.** It is a random id minted
  when a new path is created, kept on overwrite (the `replaceExisting` branch keeps it the way it keeps
  `CreatedAt`), kept on move for free (`include(sourceProps)`), and freshly minted on copy.
  `AlbumEntry`, `PhotoFlags`, `TrashedPhoto` and `Album.cover` key on it; `filePath` stays as the cheap
  hint, and `photoFor`'s fallback reads `NodeId` instead of `contentId`. ⚠ **This is not §2.2's
  withdrawn `fileId`.** That was a *wire* identity (`Syncable.identity`); `NodeId` is a foreign key
  only. `File` still merges by path, and `NodeId` is last-writer-wins. Residual: two offline devices
  creating the same path leave the loser's album entry dangling, which `contentId` (also random) does
  today, so nothing regresses.
  Rejected: the path `DataId` (empties albums on move), `ContentHash` (changes with the bytes), and
  repair-on-read in `photoFor` (~30 lines, no reshape, but it heals only albums read before a move and
  nothing for flags, trash or covers).
  ⚠ **Cost moves at the production reset.** Before it, this is an in-place reshape at v1: about
  1.5–2.5k lines across file-service, photo-service (four positional composites plus five operations'
  params), Photos, the JS SDK, `web/photos` and the apex gateway, with no backfill. After it, the same
  work needs a file-service bump (an empty step: a new value property), a photo-service bump, and a
  migration that re-keys every existing row by looking up each `contentId`'s `File`. Not built in the
  2026-10-29 pre-reset sweep because of its size; it is a candidate for its own session before v6.
- **Q6. `Folder`.** Same path-derived `DataId` as `File`; does it get its own `fileId`, or is a folder's
  identity its `SharedFile`-style realm-namespaced id already? Decide with EI-2.

---

## 8. Tests

1. **Two `DataId`s with equal `hashCode` exist** — a pinned pair, so the collision is a fact the suite
   states rather than a paragraph. Today it must show them merging in `StreamMerge`; after EI-1 it
   must show them distinct on the wire.
2. **Determinism across devices.** Two contexts with the same `K_entity` derive the same wire id for
   the same `(type, identity)`; different realms derive different ids.
3. **The reset is honest.** A store carrying pre-EI-1 `SyncMetadata` is refused or wiped, never
   merged — a hashCode id and a keyed id must not coexist in one keyspace.
4. ⚠ **WITHDRAWN with `File.fileId` (§2.2).** It asserted that `MoveFile` emits no `DeleteBlock`,
   which is only true of an entity whose identity survives the move — and `File` keeps its
   path-derived `DataId`. The move's old-path tombstone is the shipped behaviour, and a test
   demanding its absence would pin the model this proposal now rejects. ⇒ replaced by: a move
   re-keys `SyncMetadata` locally and leaves the new holder's content reachable.
5. ⚠ **WITHDRAWN too.** It asserted that a share makes the personal and shared rows carry the same
   identity — which needs both a personal `fileId` (withdrawn) and a share that reuses one
   (`generateId()` per call, so it does not). Cross-realm identity is D1's open item, and a test
   cannot pin an outcome no mechanism produces.
6. **The 426.** A client at the old protocol minimum is refused before it can push a `hashCode`-keyed
   block.
