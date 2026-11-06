# Content-addressed file content — metadata ops without moving bytes

**Status:** Draft v0.6 (2026-07-16). **Phase 0 (reset + format-version gate)
SHIPPED** (as-built §8.6.1). **Phase 1 (contentId + contentHash; manifest on
the content plane; move = pure metadata) SHIPPED** (as-built §8.12) —
move/rename/copy no longer touch bytes; supersedes the `materialiseBytes` step in
`MoveFileImplementation`. **The identity question is DECIDED (2026-07-16,
§3.3.1 → A): keep the converging path identity + build a generic
DataId-change→reference-migration primitive.** `fileId` (2a) is frozen → slated for
removal; 2c is reshaped to ride the primitive; v1 shape + sequencing in §3.3.2.
**Phase-2 progress:** step 1 (generic value-prop preservation in `MoveFile`)
**SHIPPED**; step 2 (paper spike) **DONE — VERDICT CLEAN, proceed** (§3.3.3;
surfaced a latent move-has-no-tombstone duplicate-on-peer bug + settled Q6); interim
old-path tombstone **SHIPPED**, closing the duplicate-on-peer bug today. Step 3
(the migration executor) design in **§3.3.4**, sliced 3a→3b→3c: **3a** (the
`DataIdMigrator` primitive in `:sync-service-common` + `SyncMetadata` sweep; `MoveFile`
collapses onto it) + **3b** (history-row re-key — **history now survives a move**;
`Share.fileId` left alone per §3.3 2b) + **3c** (crash-safe intent-journal +
resume — `RekeyMigrationMarker`) all **implemented — Phase 2 COMPLETE**, incl. the cleanup:
2c is subsumed by 3b (history follows the file via the re-key), and the frozen `File.fileId`
(2a) is **deleted**. **Phase 3 (per-content key / CEK) STARTED** (§3.4.1): slice **3-1**
(the single-realm CEK foundation — chunks under a per-content key wrapped under the realm volume
key on the manifest) **implemented**; slice **3-2a** (`SharedFile` gains a `contentId` = the
personal contentId → a shared file's manifest is content-addressed on the realm content plane,
not the legacy mailbox) **implemented**; slice **3-2b** (the cross-realm CEK re-wrap +
encrypted-blob copy — the Mode-B share payoff: Mode-B share moves zero plaintext, re-wraps the CEK to the
realm + copies the encrypted blobs by ref; `materialiseSubtree` deleted) **implemented**
(real cross-device browse→byte-read verified; relay log confirms same-ref blob copy). This is
option (1) copy-blobs; option (2) (single shared content plane, zero-byte share) is deferred with
its cross-realm reclamation. **Phase 4 (GC) REVISED + partly closed (2026-07-18, §4.2):** the model
was backwards — content is **durable user data**, not time-reaped. A relay retention GC was in fact
already built + wired live with a 30-day untouched window (a latent data-loss bug: a backup opened
after years → gone); **fixed** by defaulting the content retention window to `Long.MAX_VALUE` (GC
off). The correct reclamation is **account-lifecycle-driven** (owner over-quota / entitlement lapse
→ reclaim that owner's content, keyed on `ownerId`; a relay-*derived* manifest refcount is
HR#1-impossible, but a client-*declared* one shipped 2026-09-09 — §4's note and
`content-plane-refcount.md`) — tied to the commerce/entitlement plane.
⚠ **Both reclamation mechanisms are built**: `reclaimOwner` (account-lifecycle, wired through
`POST /sync/account/closure` and flag-gated off) and per-file reclamation — ST-3b's client-driven
`POST /sync/content/delete` until CR-2 B6 removed it, the relay's own edge-driven sweep since
(`retention-quota-model.md` §3.4). Only blobs neither names still linger *counted against quota*
(quota = brake). Broader "no end-to-end retention/quota
model" gap (durable content vs local-cache ceiling vs quota UX) tracked in `docs/tech-debt.md`.

**Problem in one line:** moving a 2 TB folder re-uploads 2 TB, because a file's
ciphertext is cryptographically bound to its *path*.

> ⚠⚠ **Historical (2026-10-29): this banner no longer holds.** It governed the pre-release phases
> below. The production reset before the Play open test is the **last** reset. After it, House Rule 9
> applies (a reshape owes a version bump, a `SchemaMigration` and lockstep clients, and a wipe is data
> loss), so read "just reset" wherever it appears in this doc as a description of how a phase shipped,
> never as a migration plan for a change made now.
>
> **No backwards compatibility required (decided 2026-07-14).** Tenshu is
> pre-release — there is no installed base whose data or wire format we owe
> continuity. This deletes the hardest part of the design: **no migration, no
> legacy read-fallback, no SIGNAL_ONLY lockstep dance.** Instead, a **Phase 0
> reset** wipes relay + local state and bumps a format version, and every phase
> below ships the new format cleanly with no old peers to accommodate. Where the
> earlier draft agonised over migration (old §4.2, Q1), it now just says *reset*.
> The one discipline this buys us: all clients + the relay must move together
> across the reset — which they can, because we control every deployment today.

---

## 1. Where we actually are (as-built)

Three facts, all in `SyncObserver.emitFileContent` (`:sync-service-proxy`):

```kotlin
val fileId = targetEntityId.getDataIdHash().toString()   // hash of the DataId = hash of the PATH
val aad = fileChunkAad(fileId, index, chunkCount)        // "tenshu/file-chunk/v1|$fileId|$i|$n"
val cipher = cryptoManager.encryptWithAad(plainChunk, volumeKey, aad)
val ref = "blob-${sha256(cipher).toHexLower()}"          // content-addressed by CIPHERTEXT
```

1. **The blob store is already a CAS.** A chunk's relay address is
   `blob-<sha256(ciphertext)>` — immutable, idempotent, dedup-friendly. The
   ciphertext hash (never a plaintext hash) is a deliberate choice: *"a
   plaintext-derived ref is a confirmation-of-file oracle"* (`SyncObserver.kt`).
   That reasoning stays correct and constrains everything below.

2. **A plaintext content hash already exists.** The manifest carries
   `wholeFileHash` — an incremental, order-sensitive hash-chain over the
   plaintext, computed during upload at `O(CHUNK)` memory. It lives *inside* the
   encrypted manifest, so the relay never sees it. This is the `contentHash` we
   want; it just isn't a `File` property and isn't an identity.

3. **The binding is path-addressed.** `File` entities are keyed
   `StringDataId(File.ID, path)`, the chunk AAD binds `hash(DataId)`, and the
   manifest block is routed by `targetEntityIdHash`. So a move — which mints a
   new path, hence a new entity id — invalidates the AAD of every chunk. The
   bytes must be re-encrypted and re-uploaded even though the blob store would
   happily have kept them.

**The content plane is content-addressed. The binding is not.** That mismatch is
the entire cost. Everything in this proposal follows from fixing the binding.

### 1.1 What else is path-keyed (goes stale on a move)

| Thing | Key | Consequence today |
|---|---|---|
| `File` entity | `StringDataId(File.ID, path)` | move = new identity |
| chunk AAD + manifest routing | `hash(DataId)` → path | move = re-encrypt + re-upload |
| `Share.fileId` | the path | a link to a moved file dangles |
| `FileModification.fileId` | the path | history splits across a rename |
| `SyncPolicy` / `PathSyncOverride` | path prefix | intentional (paths *are* the UX here) |
| `FileDatabase` plaintext | stored at the logical path | local rename is O(1), fine |

Note the in-repo precedent pointing the other way: **`SharedFile` already uses a
stable `generateId()` UUID**, not a path (`ShareFolderIntoRealmImplementation`).
The shared-realm model learned this lesson; the personal file model didn't.

---

## 2. How other products solve it

Everyone who ships this at scale separates **naming** from **content**. Nobody
who does it well keys content by path.

| System | Identity model | Move / rename cost |
|---|---|---|
| **git** | CAS blobs (SHA-1 → SHA-256); trees *name* blobs | free — the tree points at the same blob. Rename detection is heuristic *because* identity is content |
| **Dropbox** | namespace + path metadata, separate from content; 4 MiB blocks, SHA-256 | metadata RPC, O(1). Cross-user dedup was walked back on privacy grounds |
| **Google Drive** | stable opaque file ID; path is derived from parent links | `PATCH parents` — O(1). The API is id-based, not path-based, on purpose |
| **Apple File Provider / APFS** | stable item identifiers; clones | O(1); the OS *requires* identity independent of name |
| **Syncthing** | block-level SHA-256 | detects moves, reuses local blocks, no re-transfer |
| **restic / borg / Duplicacy** | content-defined chunking, hash-addressed chunks | metadata-only; dedup across snapshots |
| **Proton Drive / Tresorit / Cryptomator** (E2E) | per-file **content key**, wrapped per recipient/share | move = re-wrap a key. **No bytes move, no plaintext needed** |
| **S3 / object stores** | key *is* the name | **no rename — copy + delete, O(size)** |

The last row is us. Tenshu currently has the S3 failure mode, and it's the one
everyone else designed away.

The E2E row is the important one: Proton/Tresorit/Cryptomator show that
end-to-end encryption does **not** force byte movement on metadata ops — as long
as content is encrypted under a **per-content key** that gets *wrapped* to each
realm/recipient, rather than directly under a realm key. We encrypt directly
under `keys.sharedVolumeKey`, which is why sharing a folder into a realm needs
the plaintext today.

**Dedup is the one place to diverge deliberately.** Cross-user dedup requires
convergent encryption (deterministic, content-derived keys/nonces), which is a
confirmation-of-file oracle: the relay can test "does user X store *this exact*
file?". Dropbox and Mega both took criticism for it; Tahoe-LAFS documents it as a
known leak. We already rejected the same shape when choosing ciphertext-hash blob
refs. **Recommendation: no cross-user dedup, ever.** Within-realm dedup is safe
and comes almost free (§4.3).

---

## 3. The proposal

Two *different* questions get two *different* ids. Conflating them is the trap.

- **File identity** — "the same file, across renames and edits." A stable
  opaque id. Answers: where does history attach, what does a share point at.
- **Content identity** — "the same bytes." Answers: what do I have to upload,
  what can I reuse, what verifies.

### 3.1 `contentId` — a random id, not the content hash

The instinct "use the content hash as the reference" is right in shape but must
not be taken literally for the *relay-visible* key, for exactly the reason we
already rejected plaintext blob refs: a content-derived address is a
confirmation-of-file oracle.

So:

- **`contentId`: a random 16-byte id**, minted when content is written. It is the
  AAD binding and the manifest routing key. It leaks nothing — the relay sees an
  opaque id and cannot correlate content across files, realms, or users.
- **`contentHash`: the plaintext hash** (today's `wholeFileHash`), stored as a
  **synced field on `File`**, inside the encrypted metadata. It is *never* an
  address. It buys integrity, dedup lookups, and "did this actually change?".

> ⚠ **`contentHash` must be a value field, not a `dynamicProperty`.** A dynamic
> property is computed on demand *from the bytes* — which is precisely what a
> device that hasn't materialised the file does not have. It must be stamped by
> the writer at upload (where the hash is already computed for free) and synced
> eagerly, like `File.Size`.

### 3.2 The change that makes a move free

```kotlin
// today
val fileId = targetEntityId.getDataIdHash().toString()   // path-derived
// proposed
val fileId = contentId                                   // random, stable across moves
```

…plus routing the manifest block by `contentId` instead of `targetEntityIdHash`,
and giving `File` a `contentId` + `contentHash` field pair.

That's the whole mechanism. **Move / rename / copy become pure metadata**: write a
`File` entity at the new path carrying the same `contentId`. No re-encryption, no
re-upload, no materialisation. A peer resolving `File.Data` looks up the file's
`contentId` and fetches that manifest.

Copy becomes free as a side effect (two `File`s, one `contentId`) — which today
would duplicate the bytes on the relay.

### 3.3 Phase 2 as first drafted: stable file ids — a *field*, not a rekey (superseded 2026-07-16)

> ⚠ **Superseded by §3.3.1 (DECIDED 2026-07-16).** Working through 2c surfaced that
> this whole area was a deeper architectural decision: the path-key makes **every**
> stored `File` property fragile on a move, and a random `fileId` — as the key *or*
> as this §3.3 field — breaks cross-device convergence. The decision went to **(A)
> path identity + a generic DataId-migration primitive** (§3.3.1, shape + sequencing
> §3.3.2): the `fileId` field is frozen (2a slated for removal once the primitive
> lands), 2b stays dropped, and 2c's computed-relation shape is dead — history
> survives a move via the migration instead. This section is kept as the record of
> how the decision was reached.

`contentId` fixes the *byte* cost. It does not fix the *metadata* staleness:
`Share.fileId` and `FileModification.fileId` are paths, so a link to a moved file
still dangles and history still splits at a rename.

**Two distinct identities — don't conflate them (maintainer, 2026-07-15).**
`contentId` is the identity of the *bytes*: it changes on every edit, is absent for
an empty folder, and two files with identical content could share one. **File
identity** is the identity of the *node in the tree*: every file **and** folder has
one, and it is stable across **both** move **and** edit. They are different things.

**The earlier draft over-reached.** It proposed keying `File` by a stable `fileId`
UUID and demoting `path` to a mutable field — the Google-Drive / File-Provider /
`SharedFile` shape. But that conflates "give a file a stable identity" with "make
that identity the *database key*", and the two are separable. A personal file
naturally *is* path-keyed: a path uniquely locates a tree node, navigation is
inherently path-based, and the `SearchByKeyFastPath` point-read only fires when the
query predicate field equals the DataId key field — so rekeying to a UUID would turn
every `search { path equalTo … }` navigation hop into an O(array) scan, on top of a
~15-site rekey (every `StringDataId(File.ID, path)` construction, the `Folder.FILES`
parent arrays, the sync-delete re-parenting gap already deferred for `SharedFile`).
`SharedFile` is UUID-keyed only because it has **no** path (it lives in a realm as
`(realmId, fileId)`) — that precedent does **not** force a rekey on personal files.

**The fix is a stable `fileId` *field*, exactly parallel to how Phase 1 added
`contentId`.** Keep `File` keyed by its path (DataId unchanged — no navigation /
parent-array / fast-path churn). Add a `fileId` value field: a random UUID minted at
creation and **preserved across move *and* overwrite** (MoveFile already preserves
`contentId` explicitly — `fileId` joins that list; unlike `contentId`, which is
re-minted on overwrite, `fileId` is preserved on overwrite because the *node* is the
same file). Then `Share.fileId` and `FileModification.fileId` store that stable
`fileId` instead of the path, and resolve the file by looking it up by `fileId`
(a `search { fileId equalTo … }` — a scan, but only on the rare share-open / history
paths, never on hot navigation). No DataId rekey, no perf regression.

Sliced into three:
- **2a — SHIPPED, then DELETED (§3.3.2 step 5).** The `File.fileId` field
  (mint on create, preserve on move + overwrite) was shipped, frozen (no consumers ever
  added), and removed once the migration primitive obviated it — see §3.3.2 step 5.
- **2b — DEFERRED / dropped (maintainer, 2026-07-15).** Repointing `Share.fileId`
  path→`fileId` turns out to be near-worthless: a Mode-A share is a **relay-side
  snapshot** (`ShareLink` re-encrypts + uploads a copy under `shareId`), so the link
  **never dangles after a move** — it serves the snapshot regardless. `Share.fileId`
  is used *only* for the "My shares" management *display label*, and `fileId` has zero
  user-facing meaning for a share. The only thing a stable share↔file association
  would buy is an internal cascade (e.g. revoke a file's shares when it is deleted,
  surviving moves) — which doesn't exist today and, if ever built, is a small op-level
  change, not the cross-platform display-resolution churn (3 sites: Drive / Control /
  JS) full 2b would cost. Not worth it now.
- **2c — DESIGNED, deferred to its own focused pass (2026-07-15).** The valuable slice
  (history is inherently about a file *over time*, and today it splits on every rename)
  but a genuine rework of the history subsystem, not a field swap.

  **Root cause (maintainer):** `File.FileHistory` is an **entity-relation property** on
  `File`; the reader (`.relation(File.FileHistory)` in Drive + JS) resolves its array as
  `EntityPropertyDataId(parentEntity = <the File's DataId>, FileHistory)`, and the File's
  DataId is **path-derived** (`FileDataIdProvider` keys on `File.Field.PATH`). So history
  is connected to the file *through its path*: rename → new path → new DataId → the array
  hangs off the old key → history splits. The maintainer storing rows under the
  modification's `fileId` (also the path today) is the matching half of the same coupling.

  **The fix, decided:** decouple history from the path-DataId by connecting it via the
  stable `fileId` field instead. Two shapes were considered:
  - *Query rewrite:* drop the property, read history as
    `search<FileModification> { fileId equalTo file.fileId }` over the realm-wide log —
    but that reworks the Drive + JS read sites (cross-platform, JS hard to verify here).
  - *Computed relation (preferred):* keep `File.FileHistory` as a **property** — so the
    Drive/JS reads are untouched — but change it from a stored `entityRelation` into a
    **dynamic/computed relation** (same family as `File.Preview`) whose proxy-side
    resolver gathers `FileModification`s by the file's `fileId` field. Proxy-only,
    fully testable on jvm.

  **Why deferred, not done at the tail of this session:** either shape requires
  `FileModification.fileId` → the stable id + emitting it at ~5 sites, **and** re-homing
  the `FileHistoryRelationMaintainer` logic — which also owns **retention-cap trimming,
  delete-cascade, and sync convergence**. That machinery is the real risk + test surface
  and deserves a dedicated pass. 2a (the stable `fileId`) is the foundation and is merged,
  so it was unblocked whenever. **⚠ Superseded: §3.3.1 decided (A), and both 2c shapes
  above assumed the `fileId` field — they are dead.** History survives a move via the
  migration primitive instead (§3.3.2 step 4); of old 2c, only the
  `FileHistoryRelationMaintainer` re-home survives, if still wanted on its own merits.

### 3.3.1 The identity question (DECIDED 2026-07-16 → (A) path identity + migration primitive)

Working through 2c turned a small slice into a genuine, load-bearing architectural
decision (maintainer, 2026-07-15/16). Points (1)–(6) below are the analysis that framed
it, kept as the record; the decision + rationale follow them, and **§3.3.2** records the
settled v1 shape + sequencing. **Decided: (A) — keep path identity, build the generic
DataId-migration primitive. 2a is frozen and slated for removal; 2c is reshaped to ride
the primitive.**

**(1) The fragility is systemic, not a FileHistory quirk.** Because `File` is keyed by
its path, a move **re-keys the entity** (delete-old-path + create-new-path). Everything
*stored* is then at risk — *dynamic* properties (`Name`, `Preview`, recomputed from
`path`) are immune, but:
- **Value properties** (`Size`, `CreatedAt`, `ContentId`, `FileId`, thumbnails) survive
  only because `MoveFile` **explicitly re-stamps each one**. Add a new value prop, forget
  the list → **silent loss on every move** (the latent `CreatedAt`-on-folder-move bug was
  exactly this).
- **Relation arrays** keyed off the entity's DataId (`File.FileHistory`, `Folder.FILES`)
  aren't inline, so `MoveFile` doesn't carry them → they **orphan**. `Folder.FILES`
  survives only because a maintainer *rebuilds* it; `File.FileHistory` has no rebuild →
  it splits. Any *future* stored array splits the same way.

So the fragility is a property of "move = re-key," and it is permanent.

**(2) Path-identity is a *converging* identity — which rules out a naive rekey, and
weakens even the 2a field.** The tempting fix ("key `File` by a stable random id → move
is a field-write, nothing orphans") has a serious cost: the path-DataId is a **semantic,
converging** identity. Two devices that independently create a file at the same path
produce the **same** DataId, so a realm merge / cross-realm join treats them as the
**same file** and converges them — which is correct and load-bearing in a distributed
system. A **random** id breaks this: the same file created independently on two devices
gets two different ids → after a merge they are "different files" that never converge.
This afflicts **even the 2a *field***: independent same-path creation yields different
`fileId`s, and a merge picks one, **orphaning references to the other**. A random
per-device id does not converge; a semantic (path) id does. Neither pole is right:
- *Path as identity* — converges, but changes on move → re-key fragility.
- *Random id as identity* — stable across move, but does not converge across
  independent creation.

**(3) The promising fourth option — make a DataId change a first-class, relation-
migrating primitive (maintainer, 2026-07-15).** The root cause is that a move is
*delete-old + create-new*, which drops everything not inline. Instead, add a **generic
mechanism: when a `Composite`'s DataId changes, atomically migrate the entity + all its
relations (value props *and* arrays) from the old key to the new key, and propagate that
migration over sync.** Then:
- Path stays the identity → **convergence intact**.
- A move/rename migrates history, value props, everything — no per-property preservation,
  no orphaning, no bespoke computed relation per array. The fragility vanishes
  **generically, once**.
- It is reusable for any future legitimate DataId change.
- And it raises the real question: **if a DataId change migrates everything, do we need
  the `fileId` field (2a) at all?** History would survive a move via the migration, under
  the path identity — no separate stable id required. 2a would then be *reconsidered*,
  not built on. (`contentId` from Phase 1 is unaffected — it answers a different question,
  the identity of the *bytes*.)

  *Cost / open points:* it is a non-trivial **core** mechanism — enumerate every relation
  keyed by an entity's DataId, migrate atomically, and make that migration **converge over
  sync** (a rename on A must re-migrate deterministically on B, composing with the CRDT
  merge). Rename is arguably a primitive a path-keyed store fundamentally *should* have;
  today its absence is papered over per-op. Whether the sync/merge story is clean is the
  thing to work out before committing.

**(4) What the DataId sweep found (2026-07-16) — the mechanism the primitive fights.**
A three-pass sweep (taxonomy, storage+sync, cross-entity refs) pins down exactly what a
DataId change touches:
- **DataId is content-addressed.** `getDataIdHash()` is a pure hash of the DataId's key
  *content*, and that `Long` is the **universal** key — object-store rows, arrays, the
  reverse index, `SyncMetadata`, and sync routing (`targetEntityIdHash`) all key by it.
  There is no stable surrogate anywhere.
- **Array addresses fold the parent's hash.** `EntityFieldDataId` (`Folder.FILES`) and
  `EntityPropertyDataId` (`File.FileHistory`) hash as `f(parentHash, fieldRef)`,
  recursively for descendants — so a parent key change re-addresses *every array it owns
  and every descendant's arrays*. `RelationDataId` collections (global, free-form key —
  ~89 sites, most of sync-service state) are **immune**.
- **Convergence IS the hash.** Two devices independently creating the same entity converge
  *only because they produce the same hash* → same row → the merger runs
  (`resolveConflictAndSave`; `FolderMerger` keys by path). That is the feature a random id
  breaks.
- **Local half of a migration (bounded; `MoveFile` is the precedent):** rewrite the entity
  row old→new hash, every owned array + recursively descendants', membership in the parent
  array, the durable reverse-index record, and `SyncMetadata` (else HLC dedup restarts),
  **plus the soft foreign keys that store the target's key value** — `FileModification.fileId`,
  `Share.fileId`, `FileNotification.path` (all store the *path* today and *already* dangle
  on a move; the sweep confirmed the intent-vs-reality gap the 2a comments implied was
  closed but isn't).
- **Sync half (novel; no mechanism today):** blocks + tombstones carry **only the hash** —
  there is no wire form for "old hash is now new hash." A rename needs a **new convergent
  wire signal** (old→new) that every peer applies deterministically and that composes with
  the CRDT merge without resurrecting the entity under the old key. **This is the crux
  difficulty and why this is a core sync primitive, not a file-service change.**

**(5) It needs crash-safe, resumable atomicity — today's mechanism is bounded-loss, not
that (maintainer 2026-07-16; the intent-journal path is confirmed sufficient — Q6 RESOLVED in the §3.3.3 spike).** A rekey touches many rows; a crash mid-migration
leaves a partial state (entity at the new key, some arrays/FKs rewritten, some not).
Today's store is **write-behind with a durability *barrier*** (`SqliteObjectStore` +
`flushBarrier` — it bounds loss to the in-flight tail; it is **not** all-or-nothing across
a multi-row rekey). So the primitive needs one of:
- *A resumable intent-journal* — write a durable `rekey: oldKey→newKey in-progress` marker
  **before** touching anything, apply each step **idempotently** (re-running lands the same
  content-addressed hashes, so resume = safe re-run), clear the marker last; a lingering
  marker on startup ⇒ resume. Detecting the in-between state is trivial (the marker) as the
  maintainer noted; the work is making every step idempotent **and** the sync signal
  replay-safe (a half-sent rename must re-send safely).
- *or genuine cross-row ACID* spanning the write-behind cache + disk — harder given the
  async writer.

  The content-addressed store *helps* (idempotent re-apply), so the intent-journal is the
  tractable path — but it is real work and must cover the sync half too. **RESOLVED (→ Q6,
  §3.3.3.F): idempotent-migration + intent-marker IS enough — no transaction-mechanism
  strengthening — provided the marker is committed in its own transaction before step 1
  (the ordered write-behind mailbox then guarantees no committed step outlives the marker).**

**(6) A rekey is needed *regardless* of the identity model — which reframes the choice
(maintainer 2026-07-16; this became the deciding argument).** The merge case shows `fileId`-as-key does **not** escape
the rekey; it only moves the *trigger*:
- *Path as identity* converges automatically on merge (same path = same key), but
  **rekeys on move/rename** — a clear, local trigger.
- *`fileId` as identity* is stable on move, but two devices independently creating "the
  same" file get two different ids that do **not** converge; reconciling them (CRDT or a
  user "these are the same file" choice) means the **losing device must rekey its file +
  all dependent history** onto the winner's id. So the **merge itself becomes a rekey** —
  same migration + sync-convergence problem, with a *fuzzier* trigger (needs cross-device
  agreement) than a move.

  So **both models need the migration primitive** → building it is not wasted either way,
  and the identity choice reduces to *which trigger is easier to get right*: a move (clear,
  local) or a merge-reconciliation (fuzzy, distributed). That mildly favours **keeping the
  path identity + building the primitive** (A below).

  *A third identity option the merge case surfaces — a **device-local `fileId` + a
  reconciliation mapping** (each device mints its own id; a converging map records "A's id
  and B's id are the same logical file").* It is the honest generalisation of "independently
  created entities can't share a minted UUID," but it is **more complex and unusual**: the
  mapping must itself converge and be maintained, and every reference gains an indirection.
  A real option, but a hard sell versus semantic (path) or reconciled-UUID identity — not
  recommended without a concrete reason the simpler two can't serve.

**The options** (note: per (6), **A and C both need the migration primitive** — for a
move-trigger and a merge-trigger respectively; only B avoids it, by mitigating
per-property):
- **(A) path-key + generic DataId-change→relation-migration primitive** — keeps
  convergence, fixes the fragility at the root, obviates the `fileId` field. **CHOSEN.**
- **(B) path-key + per-property mitigation** — make `MoveFile` preserve *all* value props
  generically (not a hardcoded list) + a computed `File.FileHistory` relation keyed by the
  `fileId` field (2c). Incremental, no core change, navigation stays fast — but "move
  re-keys" remains and every new stored array needs care; and it leans on the `fileId`
  field whose convergence weakness (2) is unresolved. **Rejected as the direction;
  retained only as the named fallback (below).**
- **(C) random-`fileId` key + `path→fileId` nav index** — move is a field-write, nothing
  orphans — **rejected: breaks cross-device convergence (2).**

**Decision (maintainer, 2026-07-16): (A).** Three reasons, in order of weight:

1. **(6) is decisive — the migration machinery is needed under every viable model;** the
   only real choice is the *trigger*. A move is local, serialized on one device,
   user-initiated, observable. A merge-reconciliation is distributed, concurrent, fuzzy.
   Build the hard mechanism on the easy trigger. B does not avoid the cost — it defers it
   to the worse trigger while accreting mitigations (the computed relation, per-property
   preservation lists) that would later need unwinding.
2. **B ships a known silent-failure hole into the history subsystem.** The `fileId` merge
   weakness (2) is not theoretical: two machines running synced-folders over similar
   trees, or an offline re-create at the same path, *is* independent same-path creation.
   The merge picks a winner, the loser's history rows orphan, no error surfaces — a
   months-later-debugging class of bug. Under path identity both histories attach to the
   same key and merge, which is also the semantically *correct* outcome: they genuinely
   are the same logical file.
3. **The no-compat window is open now.** Phase 0 bought the right to change store/wire
   semantics with no migration and no legacy fallback. B-style incrementalism is the
   strategy for a frozen core; ours isn't frozen. Post-release, A becomes dramatically
   more expensive (mixed peers, real migrations) — this is the cheapest it will ever be.

**Fallback clause:** if the paper spike (§3.3.2, step 2) turns up something genuinely
ugly in the tombstone / concurrent-rename composition — e.g. resurrection under the old
key that cannot be deterministically prevented — fall back to (B) *knowingly*, and
document the accepted merge-orphan hole wherever 2c then lands.

### 3.3.2 The primitive — v1 shape + sequencing (decided 2026-07-16)

**v1 reframe: re-emission, not a novel wire signal.** Point (4) framed the sync half as
the crux: "blocks + tombstones carry only the hash — there is no wire form for 'old hash
is now new hash.'" True — but v1 doesn't need one. Two wire shapes exist:

- **(i) rekey signal** — a new block type `rekey(oldHash → newHash, hlc)`; every peer
  applies the migration locally. Tiny on the wire, but genuinely novel convergence
  semantics (deterministic application order, idempotent replay, resurrection guard,
  tombstone composition) — the research-grade part.
- **(ii) re-emission** — the initiating device runs the intent-journaled local migration
  ((5)), then re-emits the entity, its owned arrays (recursively for descendants), and
  the rewritten soft-FK rows under the new hashes as **ordinary blocks**, plus tombstones
  on the old keys. It introduces **no novel convergence primitive** — the existing CRDT
  merge + tombstone (ADR 0006) + HLC-LWW rules carry it. ⚠ *The first draft claimed
  "today's move already is delete+create on the wire"; the step-2 spike (§3.3.3.A) found
  that false — today's move emits create-new-**only**, no old-path tombstone, which is
  itself a latent duplicate-on-peer bug. (ii) **adds** the missing tombstone: a fix, not
  "zero change," but still only reusing machinery that already exists.*

**v1 = (ii).** That reframes the primitive as *"`MoveFile`, but complete and generic"*
rather than a new sync primitive. (i) stays on the shelf as a later optimisation, bought
for exactly two things: **folder-rename fan-out** (under (ii) a deep folder move re-emits
every descendant's entity + arrays — but that is today's wire cost too, minus the bytes
Phase 1 already removed) and **preserving a concurrent edit** addressed to the old hash
(under (ii) that delta loses to the tombstone by HLC-LWW — *better* than today, where the
edit survives as a silent duplicate at the old path; spike §3.3.3.B).

Two risk-shrinkers worth stating:
- **Phase 1 already bounded the blast radius.** A botched migration edge degrades to
  duplicated *metadata* (e.g. two path-entities sharing one `contentId`, converged by the
  existing same-path merge) — never byte loss, byte duplication, or re-upload. And the
  Phase-0 reset stance means even a bad early version is correctable without migrations.
- **One implementation, all platforms.** The migration executor lives in shared Kotlin
  core *above* the `ObjectStore` interface (applied through ordinary put/delete), so
  every platform runs the same code — and it is heavily testable on JVM.

**What v1 must cover — the sweep (4) is the checklist:** the entity row old→new hash;
every owned array + recursively the descendants' (`EntityFieldDataId` /
`EntityPropertyDataId`); membership in the parent's array; the durable reverse-index
record; `SyncMetadata` (else HLC dedup restarts); the soft FKs
(`FileModification.fileId`, `Share.fileId`, `FileNotification.path`). All of it under
the resumable intent-journal from (5): durable `rekey old→new in-progress` marker
first, every step idempotent (content-addressed hashes make re-apply land identically),
marker cleared last, lingering marker on startup ⇒ resume. The (5) question — is
intent-journal + idempotence enough, or must the transaction mechanism be strengthened
first — is **part of the spike below, not settled by this decision** (tracked as Q6).

**Sequencing (decided with the direction):**

1. **✅ SHIPPED: generic value-prop preservation in `MoveFile`.**
   Replaced the hardcoded re-stamp list (contentId / contentHash / Size / CreatedAt /
   thumbnails — §8.12) with generic preservation of *all* stored value properties. As-built
   deviation from "schema enumeration": the source is reloaded `asEntity = true` and its
   whole `.properties` map `include(...)`-ed — the re-stamp list existed only because the
   source was loaded `asEntity = false` (empty `.properties`), so the pre-existing
   `include(sourceProps)` was a no-op; the data-side reload is simpler than a new
   schema-enumeration API and carries the same "every value property, incl. future-added"
   guarantee. Kills the silent-loss-on-new-prop class (the latent-CreatedAt bug's whole
   class). It is the entity-row half of the migration executor, needed under every option.
2. **✅ DONE (2026-07-16): paper spike — VERDICT CLEAN, proceed (§3.3.3).** Worked
   rename-vs-edit / -delete / -rename (divergent), cascading folder renames, owned-array
   DataId tombstone coverage, half-sent-re-emission replay, and the (5)/Q6 atomicity
   question against the actual tombstone/HLC/emit/apply/store code. No composition produces
   resurrection-that-can't-be-prevented, byte loss, or non-convergence → the §3.3.1
   fallback does **not** fire. Surfaced one premise correction (today's move is
   create-new-only, a latent duplicate-on-peer bug (ii) fixes) and settled Q6 (intent-journal
   + idempotence is sufficient; mark-first in its own transaction). Full result in §3.3.3.
3. **The primitive itself (= the reshaped Phase 2).** Design + slicing in **§3.3.4**:
   **3a** = the `DataIdMigrator` primitive (`:sync-service-common`) + `SyncMetadata` sweep,
   `MoveFile` collapses onto it — **implemented**; **3b** = history-row re-key (**history now
   survives a move**; `Share.fileId` left alone per 2b) — **implemented**; **3c** = the
   crash-safe intent-journal + resume (`RekeyMigrationMarker`) — **implemented** (§3.3.4). Note the
   first research pass's cross-module-bridge design was over-scoped; `ResolvedDataId` falls out
   of the ordinary save+delete observers, so no `:sync-service-proxy` reach is needed (§3.3.4).
   **Phase 2 (the migration primitive) is COMPLETE** — 3a+3b+3c landed; only steps 4/5 (cleanup)
   remain.
4. **2c — DONE via 3b.** History survives moves *via the migration* (3b re-keys the
   `FileModification` rows) — no computed relation, no `FileModification.fileId` repoint. The
   optional `FileHistoryRelationMaintainer` re-home was **not** pursued (nothing needs it now
   that history follows the file). The computed-relation shape is dead.
5. **Remove 2a — DONE.** Deleted the `File.fileId` value property + its mint
   (`UploadFile`/`CreateFolder`) + its `fileServiceCommonModule` registration + the
   `FileIdStabilityTest`. The migration primitive obviated it exactly as the field's own KDoc
   predicted ("a generic 'DataId change migrates all relations' primitive that would obviate
   this field"). `MoveFile` already carried it only via the generic `include` (step 1), so no
   op-side change beyond dropping the two mints. Pre-release Phase-0 reset ⇒ no migration.

**Priority:** this does **not** queue-jump Phases 3 (CEK / Mode-B) or 4 (refcount + GC).
History-splitting has no user-visible urgency pre-release; what the decision buys *now*
is direction (nothing further accretes on `fileId`) plus step 1, which is cheap enough
to do immediately. The primitive slots in when a focused pass is available — but before
any feature wanting durable cross-move references
([`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) P1's root-moved case
now waits on the primitive, not on a `fileId` rekey — see §5).

### 3.3.3 Spike result (step 2) — CLEAN, proceed (2026-07-16)

**Verdict: proceed to step 3 (the primitive). The §3.3.1 fallback clause does not fire** —
no composition produces resurrection-that-can't-be-prevented, byte loss, or
non-convergence. Grounded in a read of the actual tombstone / HLC / emit / apply / store
code, not the ADR prose. Findings, and the corrections they force on §3.3.2:

⚠⚠ **CORRECTION (2026-09-24) — the *"no byte loss"* clause of this verdict does not
survive measurement, and the rest of it does.** The spike was a read of the *metadata* code,
and on metadata it was right: `ConcurrentMoveVsDeleteSystemTest`
(`system-test-utils/src/test/kotlin/org/tenshu/systemtest/`) now runs the rename-vs-delete
composition below on two real devices against a real relay, in both ingest orderings, and the
convergence it asserts is exactly what this section predicted. But the bytes are a separate
plane with its own reclaimer, and that plane was not what the spike read. Instrumented to
read the relay's blob/ref/handle tables while one device is released and the other is still
partitioned, the same test measures **2 blobs / 2 refs / 1 handle** when the move reaches the
relay first and **0 / 0 / 0** when the delete does — freed within a second of the delete, and
never restored by the move that arrives afterwards (3/3 identical runs). The surviving row at
`/y` is then a live reference to content the relay no longer holds. ⇒ *"Non-destructive"*
below is a statement about the **entity graph**, not about the file's contents; it is left
standing with that scope and must not be cited for the content plane. The determinant is not
the ordering but **how long the mover's `create(/y)` stays unpublished** — the free is racing
that push, and `ReclamationBarrier` clears the candidate on every peer's *cursor* having read
the log, which the mover has. The mechanism is recorded in
[`content-plane-refcount.md`](content-plane-refcount.md) §9.14; ⚠ the protocol that section proposed
is superseded, and the fix is candidate B
([`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md) §10: the relay frees content
a grace period T after its last edge, and a reference pushed after that gets `content_freed`).
⚠ The figures are 2026-09-24's, from before D3's connectivity fence, the move declaring its new
holder before the old one is undeclared, the `POST /sync/push/probe` move fence, and one-content-one-upload.

**A. Premise correction — today's move is NOT delete+create; it is create-new-only (a
latent duplicate-on-peer bug).** `MoveFileImplementation` emits only `create(new-path
File)` + the `Moved/RenamedFileModification`; it emits **no tombstone on the old path**,
and does not even delete the old-path entity locally (old-path removal is a local
`removeFromArray`, and array membership is not a synced primitive —
`ParentFilesRelationMaintainer` re-derives it per device). So a peer that already holds
`/x` never learns `/x` is gone: after A moves `/x→/y`, every other device shows the file
at **both** paths, and the origin keeps an orphaned old-path entity. This is a real,
currently-shipped Phase-1 defect (untested — the move tests assert only *local*
parent-array membership). Consequence: (ii) re-emission **adds the missing old-path
tombstone** — a *fix*, not "zero change." It introduces **no novel convergence primitive**
(tombstone + HLC-LWW + the same-path `FolderMerger` all pre-exist and already govern
delete-vs-edit and same-path creates), but it does change move's wire shape. *(The interim
leaf/per-descendant "emit the old-path tombstone on move" fix **SHIPPED** ahead of
the full primitive, closing the duplicate-on-peer bug today; slice 3a then folded it into
the reusable `DataIdMigrator` (§3.3.4) + added the `SyncMetadata` sweep. The
`FileModification` history re-key (C) is slice 3b.)*

**B. Compositions all converge non-destructively.** With the old-path tombstone present
(as (ii) emits it), keyed by the entity's own DataId-hash, gated by a pure HLC `>=`
short-circuit (no "delete is sticky" rule) plus a local-delete resurrection guard:
- **rename-vs-edit** (A: `/x→/y`; B edits `/x`): tombstone(`/x`) races B's update(`/x`) by
  HLC. Tombstone wins ⇒ single file at `/y`, B's edit lost; B wins ⇒ `/x` resurrects *and*
  `/y` exists (duplicate, same `contentId`). Standard move-vs-edit LWW, and **strictly
  better than today** (today B's edit always survives as a silent duplicate). Exactly the
  case the deferred rekey-signal (i) would later improve; not a regression, not ugly.
- **rename-vs-delete** (A: `/x→/y`; B deletes `/x`): both tombstone `/x` (idempotent,
  highest-HLC); `/y` has no competing tombstone ⇒ **move wins, file survives at `/y`**. B's
  delete of the *old* path does not reach the *new* path. Non-destructive **of the entity
  graph** — measured and confirmed by `ConcurrentMoveVsDeleteSystemTest` (both orderings).
  ⚠⚠ Non-destructive of the **bytes** it is not: see the correction above the findings list.
- **rename-vs-rename divergent** (A: `/x→/y`; B: `/x→/z`): both tombstone `/x`; `/y` and
  `/z` both created carrying the same `contentId` ⇒ two files sharing one content blob
  (copy semantics, safe as of Phase 1 — `contentId` is shared-safe), user-resolvable. No
  corruption, no loss. (The "same-path merge" applies only when the destinations coincide;
  divergent destinations legitimately yield two files.)
- **cascading folder rename**: the local recursive move re-emits every descendant as
  `create(new)` + `tombstone(old)` **per descendant** — so unlike a folder *delete* (which
  tombstones only the root and orphans children on receivers, a separate pre-existing gap),
  a folder *move* tombstones each descendant individually and converges (membership
  repaired everywhere by `ParentFilesRelationMaintainer`).

**C. Tombstones do NOT cover owned-array DataIds — and that is fine, except for `Syncable`
elements.** A tombstone carries exactly one `targetEntityIdHash` (the entity's own).
Owned-array *container* objects (`EntityFieldDataId` for `Folder.FILES`, the
`File.FileHistory` array) are never tombstoned and orphan on receivers as dead weight —
harmless (invisible; membership is maintainer-derived), reaped by Phase 4 GC. But
owned-array *elements that are themselves `Syncable` entities* are different: **`File.FileHistory`
rows are `FileModification` entities whose DataId bakes in `fileId` (`"$fileId|$deviceId|$timestamp"`)**,
so migrating a File's path is a **recursive re-key** of every history row (create-new +
tombstone-old each), not a container rewrite. This is the concrete reason history "splits
on rename" and the real work the executor owns.

**D. Replay is crash-safe — commit to the durable outbox, enqueue creates ≤ tombstones.**
The outbound path has two tiers: a **durable at-least-once outbox** (`PENDING_BLOCKS` /
`PENDING_TOMBSTONES` relation arrays — a block is removed only after a successful push,
resumes on restart, idempotent on peers), and an **in-memory pre-queue** (`emitChannel`,
between `saveData` and the outbox append) that a crash silently drops with no restart
re-scan. So (a) the migration's unit of progress must be the durable outbox append, never
`emitChannel` — and the intent-journal resume (re-run the idempotent migration on a
lingering marker → re-`saveData` → re-emit) is exactly what covers the pre-queue gap; and
(b) the scheduler drains all blocks before all tombstones each tick, so enqueuing creates
no later than tombstones makes the only crash-window failure a **transient duplicate**
(create seen, tombstone not-yet) that converges on restart — never "deleted with no
replacement." Apply is arrival-ordered but per-key HLC-LWW and idempotent, so out-of-order
create/tombstone across different keys converges once both land.

**E. `SyncMetadata` sweep is load-bearing.** `SyncMetadata` (per-DataId-hash, local-only,
never emitted; the LWW tip) is **not** cleared by the tombstone path. If the *new* path
previously held a file, a stale high-HLC `SyncMetadata` survives at that hash and can
silently drop the re-emitted block (`isNewer` false). The executor must delete
`SyncMetadata` at both the old hash (garbage + landmine on path reuse) and the new hash
(blank-slate the target). Likewise the durable `idHash→DataId` reverse-index
(`ResolvedDataId` in `TombstoneRegistry`) must be rewritten for old+new, or lookups
silently no-op.

**F. Q6 — intent-journal + idempotence is SUFFICIENT; no transaction-mechanism
strengthening needed.** The store is verified as claimed: non-suspend `ObjectStore` (HR#3),
cache-aside **write-behind** with an *ordered* single-writer mailbox, `flushBarrier()` a
fire-and-forget *ordered* commit point (bounded-loss tail, not fsync), **no** multi-row
atomic batch and **no** replay journal — but content-addressed puts are genuinely
idempotent (`upsert ON CONFLICT`), and there is a clean durable-sidecar precedent
(`TombstoneRegistry` writes below `saveData` to dodge observer re-entrancy;
`AdmissionMigrationObserver` / GR-6 `PendingGrant` give the idempotent resume-on-restart
lifecycle). The one discipline the spike adds: **commit the `rekey old→new in-progress`
marker in its own transaction before the first migration step.** Because the writer mailbox
is ordered, any migration step that reaches disk implies the earlier-enqueued marker
reached disk first — so a crash can never leave committed steps with no marker, and startup
can deterministically resume (marker present ⇒ re-run idempotently ⇒ complete; marker
cleared strictly last, after a barrier). This is ordinary idempotent-replay journaling over
an ordered log; it needs no cross-row ACID.

**Executor checklist (the sweep, made concrete for step 3):**
- **Entity row:** `put(new hash)` + `tombstone(old hash)`.
- **Descendants (folder):** recurse — each descendant is its own entity row + tombstone.
- **Owned-array *elements* that are `Syncable`:** `FileModification` history rows — re-key
  each (create-new + tombstone-old); `fileId` is baked into their DataId.
- **Owned-array *container* objects** (`EntityFieldDataId` `Folder.FILES` / `File.FileHistory`):
  leave to Phase 4 GC; membership is repaired by `ParentFilesRelationMaintainer`.
- **Soft FKs:** `Share.fileId` = plain field re-emit (shareId-keyed, **not** a re-key);
  `FileNotification.path` = plain field or leave (uuid-keyed, point-in-time event).
- **`SyncMetadata`:** delete at old **and** new hash.
- **`ResolvedDataId` reverse-index:** rewrite for old **and** new hash.
- **All of it** under the marker-first intent-journal (F), committing to the durable outbox
  with creates enqueued ≤ tombstones (D).

### 3.3.4 Step-3 implementation design — the migration executor (2026-07-16)

Grounded in a second research pass over the file-history + module-layering seams. Settles
*where* the executor lives, *how* `MoveFile` invokes it across the module boundary, and the
concrete per-step mechanics. Sliced 3a → 3b.

**Architecture — a plain `DataIdMigrator` helper in `:sync-service-common`; NO cross-module
bridge or DI.** The first research pass over-scoped this (it assumed the executor must reach
`TombstoneRegistry` in `:sync-service-proxy`, needing a `TombstoneEmitter`-style bridge + DI
wiring). It does **not**, because:
- **`ResolvedDataId` is already self-maintained by the existing observer flow.** `MoveFile`
  calls `ExecutionContext.saveData(newEntity)` (⇒ `TombstoneRegistry.onDataSaved` ⇒
  `rememberDataId(newDataId)` writes `ResolvedDataId` @ newHash) and `deleteData(oldEntity)`
  (⇒ `onDataDeleted` drops `ResolvedDataId` @ oldHash). So a bespoke `TombstoneRegistry.rekey`
  is **unnecessary** — the reverse-index rewrite falls out of the ordinary save+delete the
  migration already performs.
- **`SyncMetadata.ID` lives in `:sync-service-common`** (a `:file-service-proxy` `api` dep),
  and `SyncMetadata` is an ordinary `StringDataId`-keyed row deletable via
  `ExecutionContext.deleteData` from any layer. So the one genuinely-new sync-layer duty — the
  `SyncMetadata` sweep — needs no `:sync-service-proxy` reach either.

So the primitive is `class DataIdMigrator(executionContext, tombstoneEmitter)` in
`:sync-service-common` (it needs `SyncMetadata` + `TombstoneEmitter`, both there, over
`ExecutionContext` from `:core-common`). `MoveFile` constructs it from the
`(executionContext, tombstoneEmitter)` it already holds — no new interface, no holder, no
`SyncProxyComponents`/DI change. Its `migrate(oldDataId, entityType, extraLocalCleanup)`:
sweeps `SyncMetadata` at the old hash (+ new hash, passed in), runs `extraLocalCleanup` (the
folder's old FILES array), `deleteData(oldEntity)` (⇒ observers drop `ResolvedDataId` + parent
membership + fire the history cascade), then `tombstoneEmitter.emit(oldHash, entityType)`. The
interim `tombstoneEmitter?.emit(...)` tail in `MoveFileImplementation` *becomes* this call.

**⚠ History-cascade interaction (must fix in 3b).** The interim fix's `deleteData(sourceDataId)`
— and, on peers, the old-path File tombstone — fires `FileModificationRelationMaintainer`'s
`File.ID` → `cascadeDeleteHistory(sourcePath)` branch, which deletes every `FileModification`
with `fileId == sourcePath`. **So a move currently *destroys* prior history** (before the
interim fix those rows dangled at the old path; now they are reaped). The executor closes
this by **re-keying each history row to `targetPath` *before* the cascade fires**: mint an
equivalent `FileModification` with `fileId = targetPath` (⇒ new `compositeKey` ⇒ new DataId
⇒ new `Syncable` block), then let the existing cascade reap the old rows — the cascade filters
`fileId == sourcePath`, so the re-keyed `targetPath` rows survive on every device. This *is*
the "2c rides the migration" value (history survives moves), for free from the primitive.

**Enumeration reality (no generic query API).** There is no `getAllOfType(predicate)` at the
op layer. History rows are enumerated by reading a known relation array + a linear filter —
`getArrayData(FileService.Relations.MODIFICATIONS).toList().filter { fileId == path }`, the
exact pattern `enforceRetention`/`cascadeDeleteHistory` already use. A "generic relation walk"
(enumerate the entity's declared `entityRelation`s and re-key each element's owned Syncable
rows) is the eventual shape but is **not** built now — v1 re-keys the one known owned-Syncable
relation (`File.FileHistory`/`MODIFICATIONS`) explicitly. Value props + `contentId` already
survive via `include(sourceProps)` (step 1); thumbnails are value props (not path-keyed).

**Soft-FK reality — `Share.fileId` NOT rewritten (§3.3 2b).** `Share.fileId` is a `FilePath`
*field* on a `shareId`-keyed entity — a *field rewrite*, not a re-key — but §3.3's 2b already
found repointing it **near-worthless**: a Mode-A share is a **relay-side snapshot** (`ShareLink`
re-encrypts + uploads a copy under `shareId`), so its stored `fileId` is not load-bearing.
So 3b does **not** touch it. `FileNotification.path` is a descriptive field on a `uuid`-keyed
point-in-time row → **leave**. `SharedFile`/`Placement` are the separate shared-realm model
(cross-realm `fileId`, not the personal path) → out of scope. Net: the only owned reference a
move must migrate is the `FileModification` history.

**⚠ compositeKey collision (as-built).** A `FileModification`'s DataId is
`"$fileId|$deviceId|$timestamp"` with a **wall-clock-ms** `timestamp` (not the HLC, despite the
schema comment). So two same-file same-device events in the same millisecond collide on one key.
A move normally kept prior rows at a *different* path (different `fileId`) → no collision; the
history re-key brings them to `targetPath`, so a prior row re-keyed into the same ms as the
move's own new modification collides. As-built fix: **stamp the move's own new row LAST** (after
the re-key), so it wins that rare race — the same "keep the newest event" degradation a move has
today — while the common distinct-ms case keeps the full history. (A collision-free key —
appending the HLC counter — is a schema change deferred out of 3b.)

**Intent-journal — IMPLEMENTED (3c, 2026-07-17; deferred 2026-07-16, then built).** The
crash-safe resumable journal (§3.3.3.F) shipped as its own slice after 3a/3b. As-built:
- **Marker = `RekeyMigrationMarker`** (`:sync-service-common`), a local-only `DataIdProvider`-keyed
  composite (like `ResolvedDataId`/`SyncMetadata`; never emitted, never on the wire) carrying
  `oldDataId` (whole), `newHash`, `entityType`. Lives in a durable journal array
  (`SyncService.Collections.PENDING_REKEY_MIGRATIONS`), written **below** `saveData`
  (`database.addToArray`/`removeFromArray`) so it never re-fires observers.
- **`DataIdMigrator.migrate` = beginMarker → retire → clearMarker.** The marker is committed in
  its **own** `database.transaction {}` (ordered `flushBarrier`) *before* the first retirement
  write and removed after the last. Since the write mailbox is ordered, any retirement write on
  disk implies the marker is too — and the caller saved the new entity before calling `migrate`
  (hence before the marker), so a surviving marker always implies the replacement is durable.
- **Resume** = `DataIdMigrator.resumeIncompleteMigrations()`, called from `initSyncService`
  **after** `initFileService` (so the file-history cascade + parent-membership maintainers are
  wired) — it re-runs each lingering marker's idempotent retirement and clears it. Wrapped in
  `runCatching` so a bad marker never bricks startup. ⚠ No longer one-shot: it also runs on the
  **locked→enabled** edge, because the second journal below is waiting for exactly that.
This makes a crash mid-retirement converge to the *completed* move on next start, rather than
leaning on the eventual same-path merge. Q6's resolution (§3.3.3.F) is realised verbatim.

**⚠⚠ Two journals, not one (2026-09-17).** A retirement can also fail to **publish**: since EI-1
the tombstone's wire name is `HMAC(K_entity, identity)`, so `emit` answers null when there is no
realm to name the target in — and by then the old row is already deleted locally, which makes the
marker the only retry there is. Keeping it in `PENDING_REKEY_MIGRATIONS` was a data-loss bug: a
marker there authorises a **delete**, it survives for as long as the realm stays locked, and the
user can create a new file at the retired path in that window. Replaying then deletes the new row
*and* tombstones it — under a name derived from the same path, so every peer loses it too. ⚠ Row
presence cannot tell the cases apart: "crashed before the delete" and "deleted, then recreated"
look identical from the marker. As built:
- **Phase 1 — `PENDING_REKEY_MIGRATIONS`**: authorises the full retirement (sweep, delete, emit).
  `migrate` always leaves it — clearing it on success, moving the retry to phase 2 otherwise — so a
  marker here can only be a crash, and the init resume runs before any operation can recreate the
  path.
- **Phase 2 — `PENDING_REKEY_TOMBSTONES`** (same marker type, its own array): the local retirement
  is done and only the tombstone is owed. Replay **emits only**, and is *abandoned* outright if a
  row is live at the old key again. ⭐ Abandoning strands nothing: peers resolve the same wire name,
  so the recreated row's own block supersedes the old generation there anyway.
- **Realm transitions.** Phase 2's array is **named after the realm** —
  `PENDING_REKEY_TOMBSTONES/<ownerId>`, the shape a shared realm's outbound block queue already uses
  — so a retry written under one realm is invisible to a replay running in another. ⚠⚠ That is
  structural on purpose: a cleanup at the owner switch cannot be the guard, because a move can add
  its marker after that cleanup's snapshot and before the new keys are seated, and no lock spans the
  two. ⚠ Correcting an earlier claim in this section: the owner *is* knowable when the retry is
  written — `residentOwnerId` is the durable membership slot and survives the lock; it is
  `currentOwnerId` that is null there. `purgeOutboundQueues` and `installReceivedPairingPayload`
  still clear the array they are leaving, as tidy-up rather than as the guard. ⚠ No realm at all ⇒
  no retry is kept: there are no peers to tell.

**Slicing:**
- **3a — the `DataIdMigrator` primitive + `SyncMetadata` sweep; `MoveFile` collapses onto it.**
  `class DataIdMigrator` in `:sync-service-common` + the `SyncMetadata` sweep (entity-level
  old+new hash — the per-property `"$hash/$propertyId"` variant is **not** exercised by a move:
  a move re-emits only the whole-entity block, `File.Data` stays OnDemand-by-`contentId`, so
  entity-level is sufficient). `MoveFile`'s interim tombstone/local-delete tail becomes
  `DataIdMigrator(...).migrate(...)`. Folder recursion preserved. `ResolvedDataId` already
  falls out of the save+delete observers. **No history/soft-FK, no journal yet** — a small,
  reusable refactor that additionally sweeps stale `SyncMetadata`.
- **3b — history re-key (the "2c rides the migration" value). IMPLEMENTED.** `MoveFile` re-keys
  every `MODIFICATIONS` row for `sourcePath` to `targetPath` (a fresh `Syncable` block per row,
  reconstructing each subtype: Created/ContentChanged/Renamed/Moved) **before** stamping its own
  move row and **before** the migrator's `deleteData` → `cascadeDeleteHistory(sourcePath)` — the
  cascade filters `fileId==sourcePath`, so the re-keyed `targetPath` rows survive on every device.
  History follows the file across a move; this is why 2c's computed-relation shape is dead.
  `Share.fileId` **not** rewritten (§3.3 2b — near-worthless, above). As-built: the stamp-last
  ordering closes the ⚠ compositeKey collision.
- **3c — the crash-safe intent-journal + resume. IMPLEMENTED.** `RekeyMigrationMarker` journal
  (marker-first in its own `transaction {}`) + `resumeIncompleteMigrations()` on proxy init. See
  the intent-journal paragraph above. **Phase 2 is complete** (3a+3b+3c), incl. cleanup: 2c is
  subsumed by 3b, and the frozen `File.fileId` (2a) is deleted (§3.3.2 step 5).

**Priority note (unchanged):** step 3 does **not** queue-jump Phase 3 (CEK/Mode-B) or 4
(refcount+GC). Pre-release, history-splitting has no acute urgency; the interim fix already
took the acute duplicate-on-peer bug. So 3a/3b land when a focused pass is available.

### 3.4 Phase 3: per-content key → sharing without plaintext

Chunks are encrypted under `keys.sharedVolumeKey` (a realm key). That is why
Mode-B folder sharing genuinely needs the host's plaintext: the bytes
must be re-encrypted under the *target realm's* key.

Change content to be encrypted under a **per-content key (CEK)**, with the CEK
wrapped to each realm that references it:

- Mode-B share of a folder = **re-wrap N keys**. No plaintext, no upload, O(1) per
  file regardless of size. This is the Proton/Tresorit model.
- Revocation semantics need care: removing a member currently rotates `Kr`
  (GR-7 retained-keyring). With CEKs, a removed member who cached a CEK keeps the
  content it unlocks unless the content is re-keyed — i.e. the same trade GR-7
  already reasoned about, one level down.

**Mode-A link sharing (`ShareLink`) deliberately stays as-is**: it re-encrypts
under a fresh `K_share` precisely so revocation is strictly per-share
(`file-sharing.md` §3, *"Why re-encrypt"*). Wrapping the CEK into the URL fragment
instead would make revocation weaker. Its materialisation is correct and stays.
([`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) D3 records the
same decision from the sharing side — the two proposals agree on the crypto core.)

#### 3.4.1 Slicing + as-built (2026-07-17)

Sliced **3-1 (single-realm CEK foundation)** → **3-2 (Mode-B cross-realm re-wrap)**; the crux
of 3-2 is where shared content physically lives (below).

- **3-1 — the CEK foundation. IMPLEMENTED.** Chunks are encrypted under a fresh random
  per-content **CEK** (not `sharedVolumeKey` directly), and the CEK is AEAD-wrapped under the
  realm's `sharedVolumeKey` (domain-separated AAD, bound to `contentId`) and carried on the
  manifest as `wrappedCek`. Read: `FileContentManifestApplier.buildDecryptor` unwraps the CEK
  (trying current + retired volume keys — GR-7 moves from per-chunk trial to this one-shot
  unwrap) and `FileChunkDecryptor` decrypts the chunks with it. Within a realm this is
  equivalent confidentiality (the CEK is gated by the volume key) — its point is to make the
  chunk ciphertext **realm-independent**, the substrate for 3-2. `generateSharedRealmMasterKey()`
  (a CSPRNG 32-byte key) is the CEK generator. Threat-model T-R-09 CEK amendment co-landed
  (House Rule #2). Verified by the full `:sync-service-proxy` content round-trip + tamper suite
  and the 3 `:system-test-utils` cross-device tests (which exercise the real emit→CEK→read path).
  The `contentId` binding is now *also* enforced at CEK-unwrap (a strengthening). ⚠ A fresh CEK
  per emit means an emit *retry* re-keys + re-PUTs its chunks (orphaning the old blobs → Phase-4
  GC), losing the byte-idempotency of a content-addressed retry — acceptable pre-release.

- **3-2a — content-address the SharedFile. IMPLEMENTED.** `SharedFile` gains a `contentId`
  **field** carrying the *personal* file's `File.ContentId` (empty for a folder), set by
  `ShareFolderIntoRealm.placeChildren`. `FileBytesPropertyWriter.resolveContentId` now returns it
  for a `SharedFile` (was `null` → legacy mailbox), so a shared file's content manifest routes
  through the **content plane** (`manifestContentRef(contentId)` on the realm's per-owner content
  store) exactly like a personal file's — emit PUTs the manifest by contentId, the joiner's read
  resolves by contentId — instead of the legacy entity-id-keyed mailbox `SyncBlock`. This is pure
  routing + schema: emit **still re-encrypts** under a fresh CEK wrapped to the target volume key
  (unchanged crypto), and `materialiseSubtree` still fetches plaintext — so 3-2a has no user-facing
  cost change; its value is (i) aligning Mode-B with Phase 1's content-addressed model and (ii)
  establishing the load-bearing invariant **`SharedFile.contentId == personal contentId`** that
  3-2b re-wraps the CEK + copies the encrypted blobs against. Verified: `:file-service-proxy`
  (`ShareFolderOnDemandBytesTest` asserts the SharedFile carries the personal contentId) +
  `:system-test-utils` `SharedRealmFileReadSystemTest` (the real cross-device browse→byte-read now
  round-trips over `PUT/GET /sync/content/blob-…`, not the mailbox). Threat-model: no new asset —
  shared manifests are now content-addressed like personal ones, already covered by T-R-09 (note
  added). ⚠ A `SharedFile` minted before 3-2a (or a test file with no stamped contentId) has empty
  `contentId` → falls back to the legacy mailbox path, byte-identical to before.

- **3-2b — cross-realm CEK re-wrap + encrypted-blob copy. IMPLEMENTED.** `ShareFolderIntoRealm`
  no longer decrypts the host's plaintext + re-encrypts it into the realm (the Mode-B share cost). New
  `FileContentSyncEmitter.shareContentToRealm(contentId, targetRealmOwnerId, targetPropertyId)`
  (impl `SyncObserver`): (1) fetch the personal manifest from the relay-backed personal store +
  unwrap its CEK under the personal volume key(s) (current + GR-7 retired); (2) **copy the
  already-CEK-encrypted chunk blobs by ref** into the target realm's content store — no decrypt, no
  re-encrypt (the realm-independent ciphertext means the same `blob-<sha256(ciphertext)>` ref is
  valid in both keyspaces; the system-test relay log shows the *same* ref `GET`-from-personal then
  `PUT`-to-target); (3) re-wrap the CEK under the **target** volume key, rewrite the manifest's
  `propertyId` → `SharedFile.DATA_PROPERTY_ID` (so the joiner's applier resolves the realm
  byte-path), re-encrypt the tiny manifest under the target identity key, PUT at
  `manifestContentRef(contentId)` in the target store. The re-wrapped CEK rides *inside* the
  re-keyed manifest (`wrappedCek`) → **no separate per-`(realm, contentId)` key record needed**
  (simpler than the draft plan). `materialiseSubtree` (the "bytes must be on THIS device" guard) is
  **deleted**; the pre-mint guard is now `requireContentAddressed` ("the file has a `contentId`"),
  since the content is read from the relay, not disk.
  **✅ Load-bearing unknown RESOLVED (2026-07-17): the store IS relay-backed.** Both the personal
  and shared-realm stores are `RestContentBlobStore` against the same relay `baseUrl` (JWT scoped to
  the respective `ownerId`); `getChunk` is a direct `GET /sync/content/{ref}` with **no local cache**
  — the relay is the source of truth. And `UploadFile` **awaits** its content emit (the PUTs are
  synchronous), so a file's manifest+chunks are on the relay before any share — no ordering race. So
  the blob-copy works even for an **online-only** file (whose plaintext this device never held), and
  dropping `materialiseSubtree` does **not** regress online-only sharing — it improves it (no
  plaintext ever hits the sharing device's disk). Verified: unit (`ShareFolderIntoRealmOperationTest`
  re-share-by-contentId per file; `ShareFolderOnDemandBytesTest` online-only share triggers no
  plaintext fetch) + `:system-test-utils` `SharedRealmFileReadSystemTest` (real cross-device
  browse→byte-read; relay log confirms same-ref blob copy + re-keyed manifest). Threat-model T-R-09
  3-2b amendment co-landed (new A6 sharing-graph residual + T-GR-02-parallel revocation note; HR #1
  intact). Revocation mirrors GR-7 one level down (a removed member who cached the CEK keeps that
  content version — forward-only, same as any share).

  **⚠ Open decision — where shared content physically lives.** Because CEK ciphertext is
  realm-independent, the same `blob-<sha256(ciphertext)>` ref is valid in every realm — but the
  relay's content store is **per-realm-owner-scoped** today, so a shared-realm member can't fetch
  chunks that live in the sharer's store. Two paths, **decided: build (1) first, evolve to (2)**:
  - **(1) copy encrypted blobs** — keep per-realm stores; Mode-B copies the *already-CEK-encrypted*
    chunks into the target realm's store (no plaintext, no re-encrypt) + re-wraps the CEK.
    Contained (no relay change), still transfers the encrypted bytes once.
  - **(2) shared content plane** — the true Proton "no upload": one CAS namespace fetchable by any
    realm holding the CEK; Mode-B moves zero bytes. Bigger — relay content-store re-scoping +
    cross-realm fetch auth (the unguessable ref = capability) + **cross-realm GC (bundles with
    Phase 4)**. HR#1-safe (opaque ciphertext; random CEK ⇒ no cross-content dedup oracle).
  (1) is a genuine stepping stone: the CEK/wrapping/`contentId`-reference/read foundation is
  100% reused; only the small copy step is discarded, and its byte-identical realm-independent
  copies make evolving to (2) a *"stop copying, share the namespace, de-dup,"* not a rewrite.

---

## 4. Consequences

### 4.1 What this does to the four in-flight fixes

| Fix | Effect |
|---|---|
| **MoveFile** | **Superseded.** `materialiseBytes` + the manifest re-emit both become unnecessary; move is metadata-only. Keep the fix as the stopgap — it stops today's data loss — and delete the materialise step when Phase 1 lands |
| **Mode-B share** | **Only fixed by Phase 3** (CEK). Content hashing alone doesn't help: the target realm has a different key, so plaintext is still needed |
| **ShareLink** | **Unchanged** — deliberate per-share re-encryption |
| **web `getData`** | **Unchanged** — reading bytes needs bytes. This *is* materialisation working as designed |
| **DeleteFile** | Local cleanup stays. But once a `contentId` can be referenced by >1 `File` (move, copy, dedup), **blob deletion needs refcounting/GC** — see §4.2 |

**Materialisation itself is not the enemy** and does not go away. It is correct
and necessary whenever an op genuinely needs the *bytes* — reading, previewing,
re-encrypting for a link. What goes away is materialising for ops that only touch
*metadata*. Today we conflate the two because the metadata is welded to the bytes.

### 4.2 New costs — and the GC model (revised 2026-07-18)

- **Reclamation / GC — content is DURABLE, not time-reaped.** The earlier framing
  ("Phase 4 = refcount + GC before we can reap blobs") had the model backwards. The
  content plane holds **durable user data**: a file nobody opens for months (a backup,
  an archive) is still live — its `contentId` is still referenced, and the relay may be
  its only durable copy (content is `OnDemand`; local plaintext can be evicted, and
  second devices / the web client hold none). A *time-since-last-touch* retention sweep
  therefore **reaps live data** — the "opened after two years → gone" data-loss bug.
  - A relay-side retention/orphan GC (`ContentBlobStore.gcSweep`, untouched-window) was
    in fact built + wired live with a 30-day window during the P3 relay work — a latent
    data-loss bug. **Corrected 2026-07-18:** `RelayConfig.CONTENT_RETENTION_WINDOW_MS`
    defaults to `Long.MAX_VALUE` (GC **off**); content persists while the account is in
    good standing. The `gcSweep` mechanism is retained only as a test hook + the seam a
    future account-lapse/admin reclaim drives with an explicit window.
  - The **correct** reclamation is **account-lifecycle-driven, keyed on `ownerId`** (not
    on access time, and never on blob contents — HR#1): owner over-quota / entitlement
    lapse → after a grace period, reclaim *that owner's* content. This ties into the
    entitlement/commerce plane (`commerce-billing-plane.md`) and is a **designed,
    not-yet-built** mechanism.
  - A literal manifest refcount is **HR#1-impossible** (the relay can't read a manifest
    to learn which blobs a delete orphans). So deleted-file blobs (and shared `contentId`s
    where one `File` is deleted but another still references the content) simply **linger,
    counted against the owner's quota** — quota is the brake (same principle as
    `drive-folder-link-sharing.md` D7) — until account-lapse reclaim. This also subsumes
    the fresh-CEK orphan from a re-emit retry (§3.4 slice 3-1): the orphaned old chunks
    just count against quota until reclaim.
  - ⚠⚠ **The bullet above is too strong, and was answered 2026-09-09 —**
    **[`content-plane-refcount.md`](content-plane-refcount.md), CR-0…CR-5 shipped.** It rules out a
    relay-**derived** refcount, which is correct and not in dispute; it says nothing about a
    client-**declared** one, which is what shipped.
    The client wrote the manifest and can send `manifest → [refs]` directly, needing no decryption
    and no manifest read, so HR #1 is untouched.
    ⚠ And the accepted consequence — *"blobs linger, quota is the brake"* — was measured on
    2026-09-08 and did not hold: a realm reached **94 % of a 5 GiB quota holding 11 files**, with
    ~14 750 orphaned blobs unreachable by any mechanism. Quota became a wall, not a brake.
  - Broader gap: there is **no end-to-end retention/quota model** (durable relay content
    vs client local-cache ceiling vs quota-exhaustion UX) — tracked in `docs/tech-debt.md`
    "No end-to-end retention / quota model"; a design pass, not scheduled.
- **Orphan risk.** A crash between "content emitted" and "entity saved" leaves an
  unreferenced manifest. Idempotent CAS makes retry safe; it counts against quota until
  account-lapse reclaim (no time-GC to clean it up — see above).
- **~~Migration.~~ There is none** — see Phase 0 (§5). Because we require no
  backwards compatibility, existing files aren't migrated: the reset wipes them
  and clients re-upload under the new content-addressed format. This removes the
  only genuinely expensive option the earlier draft considered (a one-time
  re-encrypt backfill *is* the TB-scale byte movement this proposal exists to
  avoid) and the fiddly one (a permanent legacy read-fallback path). Both are
  now deleted.

### 4.3 What we get beyond cheap moves

- Copy is free; re-uploading an unchanged file is a no-op.
- Within-realm dedup: the writer holds the plaintext, so it can look up
  `contentHash → contentId` in a local index and reuse. No relay-visible leak, no
  convergent encryption.
  - ⚠⚠ **Not built, and it now owes reclamation a check before it is.** Today every upload mints a
    fresh id (`val contentId = generateId()`), so two `File` rows share one only through move/copy;
    `File.ContentHash` exists to make the hash *expressible* in a predicate (Photos PH-G3,
    `:app-ingest`'s `ContentIndex`), and nothing reuses a `contentId` on upload.
  - ⭐ **A lookup against live rows is safe by construction, and that is worth knowing before
    designing one that is not.** Both mechanisms that exist today derive from live `File` rows — the
    predicate is a linear walk, and `ContentIndex` is built by walking them. So a hit implies a live
    row still carries that `contentId`, which implies `LiveContentIds` protected its blobs and
    `drive-upload-stop-and-reclamation.md`'s sweep never touched them.
  - ⚠⚠ **A STALE lookup is not, and `ContentIndex` is explicitly stale-able.** Its own KDoc: *"a
    value taken at a moment, with no subscription… a caller that holds one across a long operation is
    reading the past. Nothing invalidates it for you."* A backup drain loads one, another device
    deletes that file mid-run, the barrier clears and the sweep reclaims — and the drain then dedups a
    later asset onto a `contentId` whose blobs are gone, minting a `File` that is born broken. ⚠ That
    needs no new index: only a drain outliving ~a minute, which a photo backup does by a wide margin.
  - ⚠ **It would be invisible from both ends.** ST-3f repairs a dangling reference only from a device
    holding the plaintext: the uploader has the bytes but never resolves (so never notices), and every
    peer resolves but has no bytes (so cannot help). Broken for everyone except its author.
  - ⭐ Two things follow. `ContentIndex`'s reload-per-run stops being a performance note and becomes a
    **safety property**; and a dedup hit should confirm the manifest still resolves before reusing an
    id, re-emitting under it otherwise — cheap, because that re-emit is exactly what ST-3f does.
- "Did this change?" is a hash compare — useful for the folder-watcher / synced
  folders, which currently re-upload on any mtime change.
- Version history / restore-a-version becomes cheap (the `FileHistory` track
  wanted a `manifestId` and had to punt — `UploadFileImplementation` literally
  comments *"the file path is the current content slot until then"*).

### 4.4 Hash choice — SHA-256 stays; change the *structure*, not the algorithm

**Decided 2026-07-14.** SHA-1 and MD5 are out — a CAS with a collidable hash is a
**content-substitution** primitive, and git's SHA-1 → SHA-256 migration is the
cautionary tale for picking the wrong one early.

**Keep SHA-256.** The BLAKE3 question was asked and answered against the as-built:

*Our SHA-256 is hardware, not software.* Every actual is a native, accelerated
primitive:

| Target | `sha256()` actual | Backing |
|---|---|---|
| Android / JVM | `MessageDigest.getInstance("SHA-256")` | Conscrypt/BoringSSL → **ARMv8 SHA-2 instructions** |
| iOS | `CC_SHA256` | corecrypto → **ARM SHA extensions** |
| JS | `@noble/hashes` (`Sha2`) | pure JS — the one software outlier |

BLAKE3's headline "5–10× faster than SHA-256" is **SIMD C (AVX2/NEON) vs SHA-256
without hardware acceleration**. That is not our comparison. A pure-Kotlin BLAKE3
is necessarily the *scalar* variant (~1 GB/s even in optimised C, less inside
ART/Kotlin-Native with bounds checks), competing against dedicated silicon doing
~1.5–3 GB/s. **The single-file Kotlin BLAKE3 we could cheaply write is the version
that loses.** Beating hardware SHA-256 requires NEON SIMD → per-platform native
code (cinterop / JNI / WASM), which is neither one file nor cheap to own.

*And hashing is not the bottleneck.* Post-Phase-1, a file is hashed **once, at
upload**, where the uplink (tens of MB/s) is an order of magnitude slower than
even a mediocre hash. What made TB-scale moves hurt was **re-uploading**, not
re-hashing — Phase 1 removes the re-upload; a faster hash would buy ~nothing. (For
scale: the AEAD is XChaCha20-Poly1305, which has *no* hardware path at all, so the
cipher already costs more per byte than the hardware hash.)

**The Merkle tree is the part worth having — and we can have it with SHA-256.**
Today's `wholeFileHash` is a hash-*chain* (`H = sha256(H || sha256(chunk))`), so
verifying any byte requires every byte before it. Once `contentHash` becomes a
content *identity* and D2b serves range reads, we want to verify an arbitrary
range against the root. That is a property of the **tree structure**, not of
BLAKE3: a Merkle tree of SHA-256 gives it, exactly as **BitTorrent v2** does.

→ **If verifiable range reads are wanted, change the structure (chain → Merkle
tree of SHA-256), keeping the hardware-accelerated primitive.** This closes Q4.

Revisit BLAKE3 only if hashing ever shows up in a profile — and if it does, note
that the JS target's win is `SubtleCrypto` (native, but async — which is why the
sync `sha256()` uses `@noble` today), not BLAKE3.

---

## 5. Phasing

| Phase | Scope | Ships |
|---|---|---|
| **0** | **Reset:** wipe relay blobs + realm state, clear local `FileDatabase` + object stores on every app, bump a format-version constant that clients assert on connect | A clean slate so Phase 1 ships the new format with **no old peers, no migration** — see §5.1 |
| **1** | `contentId` + `contentHash` on `File`; AAD + manifest routing keyed by `contentId` (**no** legacy fallback — Phase 0 removed the need) | **Move / rename / copy stop touching bytes.** Kills the TB-folder problem and supersedes the `MoveFile` stopgap's materialise |
| **2** | **Reshaped (§3.3.1/§3.3.2):** the generic DataId-migration primitive — intent-journaled local rekey executor + (ii) re-emission over existing block types; `MoveFile` collapses onto it; `fileId` (2a) deleted after | Value props, owned relation arrays (`File.FileHistory` — history stops splitting on rename), and soft FKs (`Share.fileId`, `FileModification.fileId`, `FileNotification.path`) survive a move **generically** |
| **3** | Per-content key (CEK) wrapped per realm | **Mode-B folder share stops needing plaintext** (fixes the Mode-B share cost); cross-realm content reuse |
| **4** | ~~Refcount + blob GC~~ ⚠⚠ **SUPERSEDED** — a manifest refcount is HR#1-impossible (§4.2). Reclamation is owner-scoped (`reclaimOwner`) and per-file client-driven (ST-3b); `retention-quota-model.md` owns it | — |

Phase 0 unblocks the rest by making backwards compatibility a non-goal. Phase 1
is the whole win for the reported pain and is self-contained. Phases 2–4 are
independently valuable and independently shippable.

**Downstream consumer (maintainer, 2026-07-14; updated 2026-07-16):**
[`drive-folder-link-sharing.md`](drive-folder-link-sharing.md) **P1 is sequenced
after Phases 0–2 here** (its §8.0): the Phase 0 reset must precede any live share
URLs (they cannot be re-minted once sent), its publish-loop diff consumes Phase 1's
`File.contentHash`, and Phase 2 — now the migration primitive (§3.3.2) — still
dissolves its root-moved edge case (its Q7), by migrating the share's stored path on a
move instead of via a stable `fileId`. ⚠ **Read that together with §3.3.4, which this
used to contradict**: slice 3b deliberately left `Share.fileId` alone (per §3.3 2b — a
Mode-A share is a relay snapshot, so repointing it is near-worthless *today*). The
primitive is general and can migrate it; folder-link-sharing P1 is the consumer that
will actually need it, and it should turn that on rather than assume 3b already did. Phases 3–4 are orthogonal
to it.

### 5.1 Phase 0 — the reset

Because we owe no installed base continuity (see the status banner), the cheapest
"migration" is none: reset to a clean slate and let clients re-upload under the
new format. Concretely:

- **Relay.** Drop the blob store + per-realm sync state (the `:sync-relay-server`
  stores — content blobs, `SyncMailbox`, manifest-bearing blocks). A `RelayDatabase`
  migration that truncates, or simply a fresh volume on redeploy. Persisted-store
  deployments (`DATABASE_URL`) reset the same way; in-memory ones reset on
  restart for free.
- **Apps.** Clear each proxy's local `FileDatabase` (plaintext cache) and object
  store (`SqliteObjectStore` / `InMemoryObjectStore`) so no path-bound entities or
  manifests survive. Simplest lever: a **format-version constant** the client
  writes into its local store and asserts on startup; a bump ⇒ wipe-and-rehydrate
  from the (also-reset) relay. This doubles as the compat gate — a client on the
  old version refuses to talk to the reset relay rather than corrupting.
- **Coordination.** All clients + the relay move across the reset together. We can
  require this today because every deployment is ours (dev devices, the DO test
  relay). This is the *only* discipline the no-compat stance costs, and it's free
  while there are no third-party installs.
- **User-visible effect.** Files must be re-uploaded after the reset. Acceptable
  pre-release; state it plainly in whatever release note accompanies the bump.

This is a **prerequisite for Phase 1, not an optional cleanup** — it is what lets
Phase 1 skip the legacy fallback and the SIGNAL_ONLY lockstep entirely.

---

## 6. Open questions

- ~~**Q1.** Legacy fallback vs. re-encrypt backfill for existing content?~~ —
  **RESOLVED 2026-07-14: neither.** No backwards compatibility is required
  (pre-release), so Phase 0 (§5.1) resets relay + local state instead. No
  migration, no fallback.
- ~~**Q2.** Wire compat: positional-composite field adds are peer-breaking →
  SIGNAL_ONLY lockstep?~~ — **RESOLVED 2026-07-14: moot.** Peer-breaking is a
  non-issue when Phase 0 moves every peer across the reset together. Still
  register the new `File` fields correctly (`feedback_wire_registration_gap`;
  serialize→parse round-trip test), but there is **no** graceful-degradation /
  SIGNAL_ONLY posture to maintain — the format-version gate makes an old client
  refuse to connect rather than misread.
- **Q3.** Does Phase 1 alone let two `File`s share a `contentId` *before* GC
  exists? If yes we must not delete blobs at all until Phase 4 — acceptable
  (that's the status quo), but state it explicitly.
- ~~**Q4.** Merkle tree vs. hash-chain for `contentHash`~~ — **RESOLVED 2026-07-14
  (§4.4):** keep SHA-256 (it is hardware-backed on every target that matters; a
  pure-Kotlin BLAKE3 would be *slower*, and hashing isn't the bottleneck anyway).
  If verifiable range reads are wanted, take the *structure* — a Merkle tree of
  SHA-256, à la BitTorrent v2 — not the algorithm. Decide the structure before
  `contentHash` is baked into the wire as an identity.
  ✅ **Structure closed 2026-10-29: keep the whole-file SHA-256 chain** (`WholeFileHash.kt`,
  `H = sha256(H ‖ sha256(chunk))`); no Merkle tree. Range reads need no tree: every chunk a range
  touches is already verified on its own, by its manifest `cipherHash` and its XChaCha20-Poly1305 tag,
  with its position bound in the AAD (`FileChunkDecryptor`; threat model, per-chunk AEAD + AAD).
  `File.ContentHash`'s job is **equality**: the Duplicates album (`ContentIndex`) and import presence
  (`RealmContentReads.heldContentHashes`). For that a chain is as good as a tree. Decided before the
  production reset because afterwards a different hash means re-hashing every stored file's bytes, or
  living with two hashes that never compare equal.
- **Q5.** Phase 3 revocation: a member removed after caching a CEK. How does this
  compose with GR-7's `Kr` rotation + retained keyring?
- **Q6. RESOLVED (2026-07-16, §3.3.3.F): YES — intent-journal + idempotent re-apply is
  sufficient; the transaction mechanism does not need strengthening.** The write-behind
  store gives no multi-row atomic batch and no replay journal, but its single-writer
  mailbox is *ordered* and content-addressed puts are idempotent. Discipline: commit the
  `rekey old→new in-progress` marker in its own transaction before the first migration step
  — then any committed step implies the marker committed first, so startup can
  deterministically resume. No cross-row ACID required.

## 7. Threat-model deltas (House Rule #2 — land per phase)

- `contentId` is **random**, not content-derived: no confirmation-of-file oracle.
  This preserves the property `SyncObserver` already protects with
  ciphertext-hash blob refs.
- `contentHash` (plaintext) lives only inside encrypted metadata; it must never
  become a relay-visible address or routing key.
- **No cross-user dedup** — that is the line convergent encryption crosses. State
  it as a non-goal so nobody "optimises" it in later.
- Phase 1 *reduces* one existing leak worth noting: today the relay sees
  `targetEntityIdHash` = a hash of the **path**, which is dictionary-attackable
  (`/photos/IMG_1234.jpg`). Routing by a random `contentId` removes that.
- Phase 3 (CEK) changes key custody — amendment lands with it.

---

## 8. Implementation Notes for Claude Code — Phase 1

Canonical handoff for the first phase (per the AGENTS.md convention). Everything
above is the *why*; this section is the *how*, with as-built anchors verified on
master at the time of writing (line numbers drift — grep the quoted token).

**Phase 1 goal:** move / rename / copy become **pure metadata** — no byte
re-upload, no materialisation. This supersedes the `materialiseBytes` step in
`MoveFileImplementation` (the merged stopgap).

> **Do Phase 0 (§5.1) first — a separate PR.** We require no backwards
> compatibility (pre-release), so instead of migrating existing content you
> **reset** relay + local state behind a bumped format-version constant. This is
> what lets Phase 1 skip the legacy read-fallback and the SIGNAL_ONLY lockstep
> entirely (§8.6, §8.8). Don't build a migration; you were freed from it.

### 8.1 The mechanism in one sentence
A file's ciphertext is bound to its **path** in two places (the chunk AAD and the
manifest routing key); repoint both at a stable random **`contentId`**, and store
the plaintext **`contentHash`** as a synced value field for integrity + dedup.

### 8.2 The core change — `:sync-service-proxy` `SyncObserver.emitFileContent`
Grep `val fileId = targetEntityId.getDataIdHash().toString()` (the emit body):

```kotlin
// TODAY — path-derived, so a move invalidates every chunk's AAD:
val fileId = targetEntityId.getDataIdHash().toString()   // hash of the DataId = the PATH
val aad = fileChunkAad(fileId, index, chunkCount)
// ...manifest block (grep `targetEntityIdHash = targetEntityId.getDataIdHash()`):
targetEntityIdHash = targetEntityId.getDataIdHash().toString()

// PHASE 1 — contentId-derived:
val fileId = contentId                     // random 16B, read from the File entity
val aad = fileChunkAad(contentId, index, chunkCount)
// manifest addressed by contentId, not targetEntityIdHash
```

Blobs are *already* `blob-<sha256(ciphertext)>` (immutable CAS) — **no change
there.** Only the *binding* moves path → contentId.

### 8.3 The read path — the manifest becomes a content-plane blob (SETTLED 2026-07-14)

**The decision (co-designed with the maintainer, 2026-07-14).** The original
concern was that `OnDemandResolutionImpl.resolve` fetches the manifest by *entity
id* (`effectiveRelay.fetchEntityBlocks(entityId)` → server hashes the DataId →
`SyncMailbox` filters the `target_entity_id_hash` column), so a contentId-routed
manifest wouldn't be found. The clarifying insight settled it:

> The relay **already** doesn't know about entities — `targetEntityIdHash` is just
> the name of one opaque routing column in the mailbox. The manifest got *lumped
> into* the entity-block mailbox as a convenience (it's emitted as an ordinary
> encrypted `SyncBlock` through the same `PENDING_BLOCKS` queue as metadata, so it
> inherited that column). That is the actual smell. A manifest is **content**, not
> metadata, and should live on the content plane.

There are three layers; only the first is clean today:

1. **Chunks** → `ContentBlobStore`, keyed `blob-<sha256(ciphertext)>`. Pure CAS. ✓
2. **Manifest** (chunk refs + `wholeFileHash`) → *should* be content-plane, keyed by
   content identity. Today it wrongly rides the entity-block mailbox keyed by the
   path-hash. ✗ — **this is what Phase 1 fixes.**
3. **File metadata** (incl. the pointer to content) → entity blocks, keyed by
   entity-id-hash. Correct — metadata genuinely is per-entity. ✓

**The fix: the manifest moves into the existing content-plane store**
(`ContentBlobStore` / `SqliteContentBlobStore`, `ContentBlobRegistry` — the store
the chunks already use), under a namespaced ref `manifest-<contentId>`. No new
`ManifestStore`, and it leaves the mailbox entirely. A manifest and a chunk are the
**same primitive** — an immutable, content-addressed, encrypted binary blob fetched
by an opaque key — differing only in:

| | ref | size | self-verifying? |
|---|---|---|---|
| **chunk** | `blob-<sha256(ciphertext)>` | ≤2 MiB | yes — ref *is* the hash |
| **manifest** | `manifest-<contentId>` | ~KB | **no** — random id, by design |

That last column is the one semantic difference worth preserving: a chunk's ref is
content-derived (self-certifying, dedup-friendly), a manifest's ref is deliberately
**random** (the `contentId`) because a hash-addressed manifest would reintroduce the
confirmation-of-file oracle §3.1/§7 rejects. The key-prefix namespacing keeps that
explicit and lets Phase 4 GC treat the two differently. The size gap is a *physical
storage* concern handled behind the one `put(ref)/get(ref)` contract (small
manifests inline in a SQLite row; big chunks spill to files — which is already why
`FileBlobStore` exists), not a reason for two conceptual stores.

**Read path becomes:** resolve `File.Data` → read the entity's synced `contentId`
field (already present, it's a value field) → `ContentBlobRegistry.get(owner,
"manifest-$contentId")` → decrypt → fetch chunks by their refs. **The relay never
routes content through entity identity**; the move-invalidates-bytes bug is gone by
construction because bytes are addressed by content and the entity carries only a
pointer.

**Bonus (not a cost):** the manifest is *already* consumed only on demand
(`File.Data` is `SyncStrategy.OnDemand`), yet today it's proactively **pushed** to
the mailbox. Moving it to pull-by-contentId **removes** that wasteful proactive push.
The `ContentBlobStore` GET-refresh retention GC composes cleanly: resolving a file
GETs the manifest (refreshing it) then GETs its chunks (refreshing them), so a live
file keeps its whole content-plane footprint alive exactly as the chunk-only model
did (its GC KDoc's "a GET proves a live manifest still points here" reasoning still
holds — now the manifest is itself GET-kept-alive).

Concretely this touches: `SyncObserver.emitFileContent` (PUT the manifest blob to
`ContentBlobRegistry` at `manifest-$contentId` instead of appending a `SyncBlock`);
a `getManifest(owner, contentId)` path on the relay client + a `/sync/content/manifest`
route (sibling to the chunk GET route); and `OnDemandResolutionImpl.resolve` /
`openContentStream` (read `File.contentId`, then fetch the manifest blob instead of
`fetchEntityBlocks`). The *metadata* entity keeps syncing by entity id, untouched.

### 8.4 Schema — copy the `File.Size` pattern, NOT `File.Data`
In `file-service/.../File.kt`, grep `private val fileSize`. Mirror it — a
`valueProperty`, **not** a `dynamicProperty`:

```kotlin
private val fileContentId: Property.Value<TString> =
    valueProperty(id = "${File.ID}/contentId", schema = TString.Schema)
val File.Companion.ContentId: Property.Value<TString> get() = fileContentId
// same for contentHash
```

Register both in `fileServiceCommonModule` (where `File.Size` registers its
factory). Stamp them in `UploadFileImplementation`: `contentId` = fresh random
16B; `contentHash` = the whole-file plaintext hash. Sync eagerly, exactly like
`File.Size`.

> **contentHash sourcing — SETTLED 2026-07-14: one shared hash pass.** `wholeFileHash`
> is today computed *inside* `emitFileContent` (`SyncObserver`), which runs *after*
> the entity is saved — so it isn't available when the entity is first stamped.
> Rather than hash twice or write the entity back a second time, **compute the
> whole-file SHA-256 once and thread it through**: `contentId` + `contentHash` are
> minted/computed in `UploadFileImplementation`, stamped on the entity pre-save
> (single save, eager sync like `File.Size`), and `contentHash` is passed into
> `emitFileContent` so the manifest reuses it (emit stops recomputing its own
> `wholeFileHash`). `contentId` is likewise passed in and used for the chunk AAD +
> manifest ref. Net: one hashing operation, one entity save, no write-back round.
> (Rejected: emit returns the hash → Upload does a follow-up `set(ContentHash)` +
> save — that's a second metadata sync round for no benefit.)

> **Why value, not dynamic:** a `dynamicProperty` computes *from the bytes*, which
> a non-materialised device does not have. The `File.Preview` KDoc (grep
> `private val filePreview`) makes this exact "must be a *stored* value because a
> device holds no bytes" argument — same reasoning applies to `contentHash`.

### 8.5 Then simplify MoveFile
Once content is contentId-bound, `MoveFileImplementation` becomes: write a `File`
at the new path carrying the **same `contentId`**. Delete `materialiseBytes` +
the manifest re-emit added by the `MoveFile` stopgap. `FileDatabase.moveFile` (local plaintext
rename) stays. Copy is then free (two `File`s, one `contentId`) — **but see 8.7.**

### 8.6 Migration — NONE. Do Phase 0 (reset) first.
**No backwards compatibility is required** (pre-release, §5.1). So there is *no*
migration and *no* legacy read-fallback — do not build one. Phase 0 wipes relay
blobs + state and clears every app's local `FileDatabase` + object store, gated by
a bumped **format-version constant** the client asserts on connect. Then Phase 1
ships the contentId format onto a clean slate, and every code path can assume
contentId-bound content — no "try new, fall back to old" branch to write or test.

Concretely for the implementer:
- **Land Phase 0 as its own PR before Phase 1.** Relay: truncate/fresh-volume the
  `:sync-relay-server` stores (blobs + sync state; `RelayDatabase` migration or a
  clean deploy). Apps: on a version mismatch, wipe the local `FileDatabase` +
  `SqliteObjectStore`/`InMemoryObjectStore` and rehydrate from the reset relay.
- The version gate is also the compat gate: an old client hits the reset relay and
  **refuses to connect** rather than misreading the new format. That is why 8.8's
  SIGNAL_ONLY worry evaporates.

#### 8.6.1 As-built (Phase 0 — `feat/file-content-addressing-phase0`, 2026-07-14)

Implemented and green (`:core-common:desktopTest`, `:sync-relay-server:test`,
`:sync-service-proxy:jvmTest`; iOS + JS targets compile; Android smoke gate run).
Two deviations from the sketch above, both simplifications:

- **The version constant is `TENSHU_STORAGE_FORMAT_VERSION` in `:core-common`**
  (`StorageFormat.kt`, currently `1`) — one module both the local stores, the proxy
  client, and the relay depend on.
- **The local reset is store-local self-wipe, not a DI-seam orchestration.** Each
  *persistent* `ObjectStore` records the version it was written under and wipes
  itself on `open()` when it differs: `SqliteObjectStore` via `PRAGMA user_version`
  (desktop **and** Android); iOS `FileObjectStore` via a `.formatversion` sidecar;
  JS `IndexedDbObjectStore` via a `meta` store introduced at IDB DB-version 2 (the
  bump makes `onupgradeneeded` fire on existing single-store DBs). This keeps the
  wipe internal to each store's already-suspend `open()` and needs **no change to
  the `ObjectStore` interface** — which must stay non-suspend (HR#3, the
  `flushBarrier` KDoc). The wipe keys on `recorded != CURRENT` (not `!= 0`) so every
  pre-format-version store (all recorded `0` today) wipes on the first rollout;
  `DELETE`/clear is a harmless no-op on a fresh store.
- **The `FileDatabase` plaintext cache is deliberately *not* wiped.** Once the
  object store is wiped the path-bound `File` entities are gone, so orphaned
  plaintext at a path with no entity is simply never read (and a re-upload
  overwrites it). Wiping it would be pure disk reclamation, not correctness — left
  out to keep the interface untouched.
- **The relay gate is on `/sync/realm` only** — the universal chokepoint (every
  realm is bootstrapped there, and the reset wipes realm state so every realm
  re-bootstraps through it). It refuses a mismatched/absent `formatVersion` with
  **426 Upgrade Required** *after* field validation (so a malformed body still 400s).
  `/sync/admit-device` is **not** gated: it requires an already-bootstrapped realm,
  which can only exist if a first device passed the `/sync/realm` gate — so gating it
  too would be redundant, and it kept the membership-grant admit/revoke flows clean.
  ⚠ **Superseded 2026-07-26 by schema-evolution E2e** (`core-schema-evolution.md`
  §7.5/§7.8): the chokepoint and the 426 status survive, but the gate no longer reads
  `formatVersion` at all — it accepts a range of `RELAY_PROTOCOL_VERSION`, the relay's own
  wire contract. A `TENSHU_STORAGE_FORMAT_VERSION` bump therefore no longer bounces old
  clients at the relay (client lockstep + the client-side epoch fence do that job now), and
  no longer owes a relay redeploy.
- **The relay *data* reset is operational, not a migration.** In-memory relays reset
  on restart for free; persisted (`DATABASE_URL`) relays reset via a clean redeploy /
  fresh volume (the DO test relay is redeployed). No truncating `RelayDatabase`
  migration was added — the enforceable code deliverable is the version gate, and a
  blunt truncate-on-deploy migration carried more risk (exact table names, one-shot
  firing) than value while every deployment is ours.

### 8.7 GC gate (Q3) — RESOLVED: content is durable, no time-reap (2026-07-18)
The gate was "don't reap a shared blob when one referencing `File` is deleted."
As-built this resolved **more strongly than either original option**: the relay
content GC is **off by default** (`RelayConfig.CONTENT_RETENTION_WINDOW_MS =
Long.MAX_VALUE`), because content is **durable user data** and a time-since-touch
sweep would reap live-but-unopened files (§4.2). So *nothing* is reaped on a
time basis — the shared-blob concern can't arise. (⚠ Historical note: a 30-day
retention GC was actually wired live during the P3 relay work, contradicting this
gate — a latent data-loss bug, fixed 2026-07-18.) Reclamation is
account-lifecycle-driven (owner over-quota / entitlement lapse), keyed on
`ownerId`; a relay-*derived* manifest refcount is HR#1-impossible, and the client-*declared*
refcount that reaches a deleted blob shipped 2026-09-09 (`content-plane-refcount.md`).
`DeleteFile`
deletes only *local* plaintext — still correct; the deleted blob lingers against
the owner's quota until account-lapse reclaim.

### 8.8 Wire compat + threat model
Positional-composite field adds are peer-breaking in general — but with Phase 0
resetting every peer together, there are **no old peers**, so there is **no
SIGNAL_ONLY / graceful-degradation posture to maintain.** Just register the new
`File` fields correctly (`feedback_wire_registration_gap`) and prove a
serialize→parse round-trip. Any new relay fetch path is still a relay-shape change
⇒ threat-model amendment in the same PR (House Rule #2). Note §7: Phase 1
*removes* a leak (the relay no longer sees a dictionary-attackable path-hash).

### 8.9 Tests (House Rule #7)
- `contentId` stable across a move; a moved file resolves on a **second** device
  with **zero** content re-emit — assert with a recording `FileContentSyncEmitter`
  (the fake in `MoveFileOnDemandBytesTest` / `ShareFolderOnDemandBytesTest` is the
  pattern to copy).
- `contentHash` stamped at upload == the manifest's plaintext hash.
- serialize→parse round-trip for the new composite fields
  (`feedback_wire_registration_gap`).
- Phase 0: an old-format-version client is refused by the reset relay; a
  version-bumped client wipes + rehydrates its local store cleanly.
- (No legacy-fallback test — there is no fallback.)
- Modules: `:file-service-proxy:jvmTest`, `:sync-service-proxy:jvmTest`; Android
  smoke gate (HR #8 — Control proxy is touched).

### 8.10 Out of scope for Phase 1 (don't scope-creep)
- `ShareLink` / Mode-A — deliberate per-share re-encrypt, untouched.
- Mode-B folder share needing plaintext — **Phase 3** (per-content key); contentId
  alone can't fix it (the target realm has a different key).
- Stable `fileId` UUID as the `File` key — **Phase 2** *(since reshaped: §3.3.1
  decided path identity + the migration primitive; the `fileId`-as-key shape is
  dead)*. `Share.fileId` / `FileModification.fileId` still go stale on a move after
  Phase 1; expected.
- Blob refcount / GC — **Phase 4** (see 8.7).

### 8.11 Setup
Worktree off fresh master (`feedback_worktree_write_paths` — write to the worktree
path, not the primary tree), copy `local.properties` in. The four correctness
fixes (§4.1) + this proposal are merged; master was green at
`408e1e28`.

### 8.12 As-built (Phase 1 — `feat/file-content-addressing-phase1`, 2026-07-15)

Implemented and green (`:file-service-proxy:jvmTest`, `:sync-service-proxy:jvmTest`,
`:core-common:desktopTest`; desktop consumers compile; Android smoke gate). The
design settled in §8.3/§8.4 shipped essentially as written; the notable as-built
points:

- **The content plane is a generic opaque `ref → bytes` KV, so the manifest needed
  ZERO new relay/client surface.** `ContentBlobStore.putChunk(ref, bytes)` /
  `getChunk(ref)` (client) and `PUT/GET /sync/content/{ref}` (relay) already accept
  any opaque ref. The manifest rides them at `ref = manifestContentRef(contentId)` —
  no `getManifest` route, no new store. (§8.3 had over-scoped this; the real change is
  just a derived content-plane ref.) ⚠ **As first shipped `manifestContentRef` returned
  `manifest-<contentId>`, which the relay's `ContentRouting.REF_PATTERN`
  (`^blob-[0-9a-f]{64}$`, enforced on PUT+GET) 400-rejected → cross-device manifests
  never landed, breaking OnDemand bytes (caught only by `:system-test-utils`, not the
  in-process unit mailboxes). Fixed 2026-07-17: `manifestContentRef` now returns
  `blob-<sha256("manifest:"+contentId)>` — satisfies the pattern, derivable from
  contentId, domain-tagged so it never collides with a chunk ref; relay unchanged,
  threat-model T-R-09 corrected.**
- **Emit branches on contentId presence, not a rewrite.** `SyncObserver.emitFileContent`
  reads `contentId`/`contentHash` from the just-saved entity via the
  **`ExternalPropertyWriter` port** (new `resolveContentId`/`resolveContentHash`
  defaults, implemented in `FileBytesPropertyWriter` — the same bridge the read path
  uses, since `:sync-service-proxy` can't see `File`). Non-null → AAD bound to
  contentId + manifest `PUT` to `manifestContentRef(contentId)`, **no mailbox block**. Null →
  the **pre-P1 path is untouched**, which is deliberate: a **shared-realm `SharedFile`
  already has a stable UUID id** (no move-invalidation), so Phase 1 leaves Mode-B
  entirely alone (Phase 3 owns shared content). Legacy/test wirings without the port
  also keep the old path — so all existing emit/read tests still pass unchanged.
- **Read: `OnDemandResolutionImpl.resolve` reads `contentId` via the port**, then
  `SyncProcessor.resolveContentManifest(entityId, contentId)` GETs `manifestContentRef(contentId)`,
  decrypts (reusing an extracted `decryptAndParseCipher`), and applies via the
  existing `FileContentManifestApplier.apply`. Null contentId → the legacy
  `fetchEntityBlocks` path. `openContentStream` branches the same way (D2b streaming).
- **One-hash-pass via a lifted helper.** The whole-file hash-chain (`IncrementalSha256`
  + `wholeFileHashOf` + the 2 MiB stride) moved from `:sync-service-proxy` internals to
  **`:core-common` `util/WholeFileHash.kt`** so `UploadFileImplementation` computes
  `contentHash` **once** over the in-memory upload bytes, stamps it pre-save, and the
  emitter reuses it (skips its own hashing) — a *single source of truth* so the value
  Upload stamps is byte-identical to what the manifest carries and the reader re-folds.
- **contentId is minted per *content write*** (upload + overwrite mint fresh; move/copy
  preserve) — a stale contentId would re-PUT its immutable manifest as a no-op and
  strand the new bytes. Move preserves value properties **explicitly** (`resolveProperty`
  + re-`set` of contentId/contentHash/Size/CreatedAt/thumbnails): `include(sourceProps)`
  does **not** carry value properties (they aren't inline on the entity), which the old
  code masked by re-setting `Size` from the materialised byte length — removing
  `materialiseBytes` surfaced (and this fixes) a latent CreatedAt-loss-on-move too.
- **GC still off (Q3/§8.7 honoured):** manifests + chunks are never reaped in Phase 1;
  an overwrite/move orphans the old contentId's blobs until Phase 4 refcount+GC.
- **Threat model amended** (T-R-09 content-addressing note): the manifest becomes an
  opaque content-plane blob keyed by a **random** contentId, which *removes* the pre-P1
  dictionary-attackable path-hash routing key; `contentHash` stays inside encrypted
  metadata; no cross-user dedup.
- **Pending:** on-device move round-trip across two real devices (the unit tests prove
  contentId-preservation + zero re-emit + zero fetch; a live A→B "move an un-opened
  file, B still reads it" pass wants hardware). Phases 2–4 unchanged.
