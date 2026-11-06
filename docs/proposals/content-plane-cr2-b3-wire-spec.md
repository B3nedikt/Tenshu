# CR-2 B3 wire spec — edges on the push, cascades on the tombstone, registers at the relay

**Status:** v0.2 (2026-10-02). ⭐ **Decisions taken (maintainer, 2026-10-02, §9):**
- a **hard cutover with a relay reset**: the push and tombstone routes change their bodies in place,
  and the old forms are deleted rather than kept beside the new;
- "none" is an **absent** content id on the wire and `NULL` in the relay;
- every push carries the new body.

⭐ **B3c (relay) and B3d (client) BUILT 2026-10-02**, on `claude/cr2-b3c-relay` and `claude/cr2-b3d-client`;
as-built notes and deviations in §10. The two halves only work together: a B3d client's pushes are refused
by a pre-B3c relay, and every pre-B3d client's push and tombstone bodies are refused with 426 by a B3c
relay. This is the wire spec that gates B3c (relay) and B3d (client) in `content-plane-cr2-quorumless.md`
§10.7.

**Builds on, and does not re-argue:** candidate B's model, G1–G4 (§10.2); B3a's edge declaration
(§10.11); the B2 audit answers Q2–Q8 (§10.12), including the revised Q4 reap ordering, accepted
2026-10-02; and B3b's persisted cascade and reap watermark (§10.11 as built).

**Scope.** B3 makes the relay's edge set follow the log:
- every push names its edges;
- every tombstone names its subtree;
- every reap names its removal;
- the relay keeps one HLC-ordered register per `(holder, property)`.

**Not in B3:** freeing anything (G2, B5), and the `content_freed` refusal and REVIVE (B4). Their fields
are **reserved** here, so B4 and B5 change no wire shape.

---

## 0. ⭐ Why a hard cutover, and what it removes (maintainer, 2026-10-02)

Before the first release the relay holds only the maintainer's test data. A protocol change is
therefore a redeploy plus a relay reset, and losing that data is acceptable. The relay protocol is
also reset to version 1 before the first release. Consequences:
- **No edge predates B3 on the reset relay**, so there is no UNSTAMPED register state, no "a
  tombstone never removes an unstamped edge" rule, no one-device re-stamp pass and no `/restamp` route
  (§10.7 B3's migration).
- **No dual-write.** Every edge arrives with its push. The edge form of `/sync/content/declare` and
  all of `/sync/content/undeclare` are **deleted** in B3, along with the client's declare, undeclare
  and successor-declare calls. That pulls those items out of B6, and with them the "side routes
  fenced" arming gate and §10.10's unremovable-unstamped-edge finding.
  - ⚠⚠ **The refusal is unconditional, and that is the decision.** The edge form answers 400 for every
    holder, before any holder or register logic. The per-register fence the quorumless doc once offered as
    an alternative (refuse only for holders that already have a B3 register) is **not** taken: it would
    leave an unremovable unstamped edge possible. No path mints an edge outside `/sync/push`.
- **The push floor is in place from B3c on.** The old bodies are gone, so B5's "raise the floor" and
  "close the legacy routes" gates dissolve. What still gates B5 is B4, the dry run, and the legacy
  *FREE* routes (`/sync/content/delete`, `/reclaim`), which the device sweeps call until B6.
- ⚠ **This holds only while a reset is acceptable.** After the first release, a change of this kind
  needs the migration machinery this section removes: §10.7's B3 row as it stood before 2026-10-02.

## 1. Ground facts this spec relies on

- `POST /sync/push` takes one binary `SyncBlock` (`hlc`, optional `targetEntityIdHash`, sealed
  payload) and appends it to the mailbox. `POST /sync/push-tombstone` takes one `DeleteBlock` and
  returns its sequence number. Neither carries a protocol version today: the 426 gate runs only at
  `POST /sync/realm` (`RealmRouting.kt`, §7.11).
- Binary composites support **optional** fields (`SyncBlock`'s `targetEntityIdHash` is one), so an
  absent content id needs no sentinel.
- `HlcStamp` / `compareHlc` / `decodeHlcStamp` already live in `:core-common` for exactly this kind of
  shared comparison.
- After §10.12 Q7, a delta that names content **or clears it** is always a **standalone** block. So
  every edge on the wire has a non-null holder and a block HLC.

## 2. The register model (normative)

One register per `(owner, holder, property)`, holding `content_id` (`NULL` = **none**: the latest write
cleared it) and `stamp` (the `HlcStamp` of that write, never null). Plus one **holder watermark** per
`(owner, holder)`: the highest HLC at which the holder was removed, whether by tombstone, by cascade
or by reap.

Rules, applied in the request's transaction, using `compareHlc` and never arrival order:
1. **An edge write `(h, p, c?, s)`:**
   - if `watermark(h) ≥ s`, it is a no-op;
   - else if there is no register, create `(c, s)`;
   - else set it to `(c, s)` iff `s` is greater than its stamp.
   - An absent `c` is written as a none register, **never deleted**. Deleting would lose the stamp a
     later, stale write has to be compared against.
2. **A removal of holder `h` at `T`** (tombstone, cascade or reap):
   - `watermark(h) = max(watermark(h), T)`;
   - every register of `h` with stamp ≤ T becomes none at stamp T.
3. **A content's live-edge count** (everything G2 and the device sweeps read) counts registers with
   `content_id IS NOT NULL`. ⚠⚠ Every query over `content_edge` that counts or enumerates edges
   (`/orphan-candidates`, `/inventory`'s `candidateContentCount`, `/reclaim`'s re-check) must carry
   that filter, or a none register would read as an edge. (`/contents` reads `content_ref` only, so
   the filter does not touch it.)

## 3. Routes

The **replaced and new** bodies (`/sync/push`, `/sync/push-tombstone`, `/sync/content/reap`) carry
`relayProtocolVersion`, and the relay answers **426** outside `RELAY_PROTOCOL_SUPPORTED_RANGE`. This
is the per-request gate §10.7 asks for.
⚠ The **kept** JSON routes (`/declare`, `/delete`, `/reclaim`) do not carry it: Ktor's default `Json`
rejects unknown keys, so adding it to a kept body before the relay's DTO declares it is a 400.

### 3.1 `POST /sync/push` — a block and its edges, in one transaction (body replaced)

Body: binary `EdgedPush`

| field | type | B3 rule |
|---|---|---|
| `relayProtocolVersion` | integer | 4 |
| `block` | `SyncBlock` | stored in the mailbox exactly as today |
| `edges` | list of `PushedEdge` | the **complete** set for the row (`contentEdgesOf`), or empty |
| `revive` | list of `ReviveEntry` | **reserved for B4; must be empty** (400 otherwise, until B4) |

`PushedEdge` = `propertyId` (string), `contentId` (optional string: absent = none), `regenerable`
(boolean). ⚠ Since 2026-10-04 `propertyId` carries the **keyed property tag**, never the schema id
(§11.1); the field name is kept so the composite's shape does not move. `ReviveEntry` = `contentId`, `refs` (list of strings), reserved now so B4 is not a reshape.

- **The holder is `block.targetEntityIdHash` and the stamp is `block.hlc`.** Neither is a field of the
  edge, so neither can disagree with the block, and no write clock crosses the wire that the block
  does not already expose (§10.5 option ii's smaller linkage, unchanged).
- **400** if `edges` is non-empty and the block has no target (a batch), or if a `propertyId` (tag)
  appears twice.
- In one transaction: append the block, then apply rule 1 to each edge.
- **Responses:** `200 OK`; `400`; `403` (owner mismatch); `426` (version); `402`/`507` as today.
  **Reserved for B4:** `409 content_freed`, whose body lists the freed content ids; nothing is FREED
  before B5.

### 3.2 `POST /sync/push-tombstone` — a tombstone and its subtree (body replaced)

Body: binary `CascadedTombstonePush` = `relayProtocolVersion`, `block` (`DeleteBlock`, stored as
today), `cascade` (a list of descendant wire names, from B3b's persisted `TombstoneCascades`).

In one transaction: store the tombstone, then apply rule 2 to the target and to every cascade name, at
`T = block.hlc`. Response: the assigned sequence, as today. Device-revocation handling is unchanged.

### 3.3 `POST /sync/content/reap` — a device's reaps (new)

JSON: `{ "relayProtocolVersion": 4, "removals": [ { "holder": "<wire name>", "hlc": "<HlcStamp>" } ] }`,
at most 1,000 per request.

- Auth: `requireOwnerId` (JWT, or `X-Dev-Owner` in dev mode), like every other content route. No
  entitlement gate.
- `holder` is checked with `EDGE_ID_PATTERN`, and `hlc` with `decodeHlcStamp`. Either failing gives
  400, as do a body that will not decode, an empty list or more than 1,000 entries.
- Success: `200 {"removals": <entries applied>, "edgesCleared": <registers that went from an edge to
  none>}`. `removals` counts every well-formed entry, including ones that changed nothing
  (idempotent), so a retry reports `edgesCleared: 0` and is not an error. (As built by B3c; the
  draft's single `removalsApplied` was reconciled to this on 2026-10-02.)
- Applies rule 2 per entry. Idempotent: every device that reaps sends the same `(holder, T)`.
- T is the **folder tombstone's** HLC (§10.12 Q4 revised).
- It covers the children a deleter's cascade could not name: the ones a peer pushed before the
  deleter pulled them.

### 3.4 Changes to existing routes

- `/sync/content/declare` keeps **only** the `contents` (describe) form, with `referenceId` optional
  (default empty; §10.12 Q3). The **edge form is deleted**.
  - ⚠ `referenceId` goes through `EDGE_ID_PATTERN` (1–256 characters) today, so the check is skipped
    for a blank one. The handle-cap count already skips blank ids.
  - The response's `declared` field goes with the edge form, leaving `refsDeclared` and
    `contentsDescribed`. Clients must not require it.
  - "Both arrays empty" becomes "`contents` empty".
- `/sync/content/undeclare` is **deleted**.
- `/sync/content/delete` and `/reclaim` stay until B6. Arming G2 closes them for G2-governed content
  (§10.7, unchanged).

## 4. Relay storage (B3c, created fresh by the reset)

```sql
CREATE TABLE IF NOT EXISTS content_edge (
  owner_id    TEXT NOT NULL,
  holder_hash TEXT NOT NULL,
  property_id TEXT NOT NULL,
  content_id  TEXT,            -- NULL = none
  stamp       TEXT NOT NULL,   -- HlcStamp encoding
  PRIMARY KEY (owner_id, holder_hash, property_id)
);
CREATE INDEX IF NOT EXISTS content_edge_by_content ON content_edge (owner_id, content_id);
CREATE TABLE IF NOT EXISTS holder_watermark (
  owner_id    TEXT NOT NULL,
  holder_hash TEXT NOT NULL,
  stamp       TEXT NOT NULL,
  PRIMARY KEY (owner_id, holder_hash)
);
```

- `declared_at` goes. Comparisons go through `compareHlc` over decoded stamps, never string order.
- ⚠ A relay **not** reset still has the old `content_edge` shape. B3c's deploy must recreate the table
  rather than rely on `CREATE TABLE IF NOT EXISTS`.

## 5. Client (B3d)

- **Emit.** Every standalone block goes to `/sync/push` as an `EdgedPush`:
  - a content-referencing type carries `contentEdgesOf(payload)`, the complete set (B3a);
  - every other block (batches included) carries empty edges.
- **Staging (§10.12 Q7).** A save that names content, or replaces a stored row that named content,
  is forced standalone. That is decided on the saving thread, before the write.
- **Tombstones.** They go to `/sync/push-tombstone` with the cascade read from B3b's persisted
  `TombstoneCascades`.
- **Reaps.** Each reap enqueues a durable `PendingReapRemoval (wire name, T)`, a new local type with
  no version bump. It drains to `/sync/content/reap` in batches, retrying until 2xx.
- **Removed:**
  - the emit-time and successor edge declares (declare-on-move);
  - `undeclareHolders` after a tombstone push;
  - the backfill's edge half. `ContentDeclarationBackfill` keeps describing ref sets, which is the
    only declare left.

  `TombstoneCascades`' successor record goes with them.
- **One change point for every client.** Android, desktop, iOS (including `RelayBlockProbe`, then; deleted 2026-11-05) and
  the JS runtime all push through the shared `RestSyncRelay` behind `SyncRelay.pushBlock` and
  `pushTombstone`. Those two signatures gain the edges and the cascade (empty by default), so no
  client keeps an old body by accident.

## 6. Versioning

- **B3c ships `RELAY_PROTOCOL_VERSION = 4` and `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION = 4`**, together
  with the relay reset. A pre-B3 client is refused with 426, which is the floor.
- **The maintainer resets the protocol to 1 before the first release**, so 4 is a pre-release
  number. The fencing-floor rule in `RelayProtocol.kt` must be revisited at that reset, not
  silently broken.
- **HR 9:** `EdgedPush`, `PushedEdge`, `ReviveEntry`, `CascadedTombstonePush` and
  `PendingReapRemoval` are **new** sync-service composites, so the baseline gains lines but no
  version moves. ⚠ Their fields are positional, so `revive` is reserved now rather than appended
  later.

## 7. Threat model (House Rule 2): what the amendment must record

House Rule 1 holds: the relay reads edge metadata only. New linkage, each small but new:
1. **A content-bearing property bound to a block and its write clock.** Option ii's increment, as
   §10.5 already states. ⚠ It now covers **every** edge, because no edge arrives any other way.
2. **The cascade list:** "these holders were one subtree, deleted together".
3. **Per-device reaps:** the same subtree membership, once per reaping device.
4. **`regenerable` per edge:** which properties are thumbnails. The derived-id pattern already let the
   relay infer this; the flag makes it explicit.

It also records what **leaves**: the separate declares and undeclares, and with them their timing
correlation.

## 8. Tests owed

- **Relay (B3c):**
  - rule 1 in both arrival orders;
  - the watermark beats a late, older block;
  - none survives as a register;
  - cascade and reap at T, including a recreate above T surviving;
  - 426 outside the range;
  - `revive` refused while reserved;
  - every edge query ignores `NULL`;
  - the deleted routes answer 404.
- **Client (B3d):**
  - the payload's edges equal what goes on the wire;
  - a clearing save goes standalone;
  - the cascade survives a restart onto the wire (B3b's store);
  - the reap outbox retries;
  - nothing calls a deleted route;
  - the JS runtime pushes the new body.
- **System:** a two-device delete-vs-recreate across a restart, checking that the relay registers
  equal `contentEdgesOf` of both devices' rows (§10.13's invariant, asserted, not assumed).

## 9. Decisions (maintainer, 2026-10-02)

1. **Route shape: change the bodies in place and delete the old forms.** Not new routes beside old
   ones. The reset makes the cutover free (§0).
2. **None: an absent `contentId` on the wire, `NULL` in the relay.** There is no technical obstacle:
   binary composites support optional fields, and the reset creates the table fresh, so no SQLite
   rebuild is needed.
3. **Every push carries the new body:** it follows from 1.
4. **Fencing the old edge routes:** moot, because they are deleted (§3.4).

## 10. As built (2026-10-02)

The two halves were built in parallel against §1–§9; each records its own notes and deviations.

### 10.1 B3c, the relay half

What shipped, and where it differs from or adds to the text above.
- **Storage (§4).** Migration 22 `DROP`s `content_edge` and its index and recreates them in §4's
  shape, then creates `holder_watermark`. A relay that was not reset therefore loses its pre-B3 edges
  at deploy, which is §0's decision; nothing relies on `CREATE TABLE IF NOT EXISTS`.
- **Rules (§2).** `ContentEdgeRegistry.writeEdges` (rule 1) and `removeHolders` (rule 2), with the rule
  1 decision in one shared function (`edgeWriteApplies`) so the SQLite store and its in-memory twin
  cannot order differently. Stamps are stored as `HlcStamp.encode()` and compared **decoded**, through
  `HlcStamp.compareTo` (the shared `compareHlc`). The rules therefore run in Kotlin inside the
  transaction, not in SQL, because the encoding's string order is not HLC order.
  - A write at a stamp **equal** to the register's is a no-op (strictly greater wins), so a replayed
    push changes nothing.
  - A removal of a holder that has no registers still sets its watermark, so a push still in flight
    for a reaped child loses when it lands.
- **One transaction (§3.1, §3.2).** `ContentEdgeRegistry.atomically` runs the mailbox append and the
  register writes in one `RelayDatabase.inTransaction`. `SqliteSyncMailbox` re-enters the same lock and
  connection, so in production (both built over one `database` in `main`) they commit or roll back
  together. ⚠ That holds only while the two share a database; the in-memory twin serialises under a
  lock and cannot roll back.
- **Push (§3.1).** As specified, plus:
  - at most **256** edges per push (400), a bound the spec does not name;
  - `propertyId`, a present `contentId` and the holder are shape-checked with the existing id rule
    (`EDGE_ID_PATTERN`: length ≤ 256, a conservative charset), 400 otherwise;
  - an empty-string `contentId` or target is read as absent, because the binary codec can round-trip an
    absent optional string as `""`;
  - the block is appended even when every edge loses to rule 1: the mailbox is the log.
- **Tombstone (§3.2).** As specified. The cascade has no count cap; each name is shape-checked.
- **Reap (§3.3).** As specified. An empty batch is 400, and an unreadable `hlc` fails the whole batch
  with 400, because a stamp that cannot be ordered cannot be applied safely either way. The whole
  batch is one transaction. The response is `{ removals, edgesCleared }`. The route is registered only
  where an edge store is.
- **⚠ Legacy bodies get 426, not 400.** A bare `SyncBlock` on `/push` or a bare `DeleteBlock` on
  `/push-tombstone` (every pre-B3 client) is answered 426 "update required" rather than "malformed".
  The spec says only that a pre-B3 client is refused with 426. Without this, the parse failure would
  have answered 400, which a client cannot act on. ⚠ **Removed at the 2026-10 reset**: no
  pre-B3 client remains, so a bare body is now an ordinary unparseable 400; and on reap a missing
  version is a malformed body (400), only an out-of-range one 426.
- **Version gate (§3, §6).** `relayProtocolSupported` answers 426 for a missing or out-of-range
  version on all three bodies. `RELAY_PROTOCOL_VERSION = RELAY_PROTOCOL_MIN_SUPPORTED_VERSION = 4`; the
  fencing floor (1) is untouched, and `RelayProtocolTest` still pins it.
- **§3.4.** A `declare` body that still carries `edges` is a 400 naming `/sync/push`, never a silent
  partial success. `undeclare` is gone (404). `referenceId` defaults to empty: the refs are written and
  no handle is. The response dropped `declared`.
- **Rule 3.** `referencedContentIds` and `holdersOf` (the candidate set, the reclaim re-check, the
  inventory) and both `ContentReclaimJob` queries (protected manifests, edge-only-with-manifest) filter
  `content_id IS NOT NULL`. `/sync/content/contents` reads only `content_ref` and needed nothing.
- **`regenerable`** is received and parsed but **not stored**: §4's table has no column for it. B4
  decides whether it persists.
- **Resolved by the merge with B3d (2026-10-02):** on B3c alone, `:system-test-utils` (50 tests) and
  `:hub` (13) went red, because the in-process relay answered the old `RestSyncRelay`'s bare
  `SyncBlock` with 426 (580 and 2,485 `426 Upgrade Required: POST /sync/push` in one class each). This
  was the cutover working as specified. With B3d's client bodies merged, both suites pass.
- **Tests (§8's relay list):** `ContentEdgeRegistryContract` runs against both stores
  (`SqliteContentEdgeStoreTest`, `InMemoryContentEdgeStoreTest`); `PushEdgesRoutingTest` covers the
  routes; `ContentDescribeRoutingTest` covers the describe-only declare and the deleted routes;
  `ContentReclaimJobTest` covers a none register in the operator queries.

### 10.2 B3d, the client

What §5 asked for, where it lives, and every place the build departs from the text above.

**The wire (§3, §5's "one change point").**
- `SyncRelay.pushBlock(block, edges = [])` and `pushTombstone(block, cascade = [])`
  (`:sync-service-common`). `RestSyncRelay` serialises an `EdgedPush` / `CascadedTombstonePush`
  stamped with `RELAY_PROTOCOL_VERSION`, read from `RelayProtocol.kt` (B3c raises it to 4). Every
  client goes through it: Android, desktop, iOS (`RelayBlockProbe` / `RelayFolderProbe` pushed with the
  default empty set until they were deleted on 2026-11-05) and the JS runtime. `RestB3WireTest` pins the bodies; it is `commonTest`, so the
  JS compile lane builds it too (the JS test *run* is blocked in this container, see below).
- ⚠ **Deviation: the reap route is on `ContentBlobStore`**, not `SyncRelay`
  (`reapRemovals(removals)`), beside the describe, because it is a `/sync/content/*` route and the
  scheduler already holds the content store. A runtime with no content store keeps its reaps owed.
- `ContentBlobStore.describeContent(contents)` is the only declare left. `declareContentEdges`,
  `declareContent(edges, contents)` and `undeclareHolders` are deleted from the port, so nothing can
  call a deleted route. `referenceId` is optional (`null` → sent as `""`, the DTO default).
- Conforms to the 2026-10-02 clarifications in §3: `relayProtocolVersion` is sent only on the
  push, tombstone and reap bodies, never on the kept `/declare`, `/delete` or `/reclaim` JSON; an
  empty reap list is never sent (the relay 400s it); no response body is parsed (`declared`,
  the reap counts), so only the status code is read.
- ⚠ A `426` is an ordinary failed push: the block stays queued and is retried each tick, with no
  dedicated backoff or health signal. A client is never older than the relay's floor in B3 (the
  maintainer redeploys both), so this was not built out.

**Emit (§5).**
- `SyncObserver.generateSyncBlock` computes `contentEdgesOf` on the emit consumer, from the payload in
  hand (`ContentDeclarationsCache`, one `ContentDeclarations` per registry generation), never from the
  store (`EdgesOnPushTest.theEdgesComeFromThePayloadInHand_notFromTheStore`).
- ⚠⚠ **Deviation: a fifth new local composite, `PendingBlockEdges`** (`blockId` → encoded edges). The
  drain sees only the sealed block, so the edges are journalled beside it **before** it is queued and
  `PushBlock` reads them back by `blockId` — exactly B3b's cascade shape. The pending queue's element
  type stays `SyncBlock`, so nothing that reads the queue changed. The row dies with its block
  (`deleteRetiredBlock`). No version moved; the baseline gained one line.
- ⚠ **Only the entity block carries edges** (`propertyId == null`, non-null target). A property
  block's payload is one slot, not the row; it carries none, which leaves the registers as the entity
  block set them.
- A row whose type has no content-bearing property carries none. A `Folder` carries six `none`
  edges: it resolves `File`'s declarations through its parent chain (§10.11). "Cannot say"
  (`edgesOf` → null) pushes none and logs.

**Staging (§10.12 Q7).**
- ⚠ **A small core change:** `LaneAwareDataObserver.admitsToLane(data, origin, laneId)` is asked by
  `ExecutionContext.saveData` **before** the write, and a refusal turns the save into an ordinary
  standalone one. `SyncObserver` refuses a save whose payload names content, a save replacing a stored
  row that named content (read with `getData(asEntity = true)`), and a save it cannot classify.
  Pinned by `LaneContentStagingTest`, mutation-checked.

**Tombstones (§3.2).** `PushTombstone` reads the cascade from B3b's store
(`DatabaseTombstoneCascadeStore.load(blockId)`) at push time; the drain forgets it after the queue
removal. ⚠ **`TombstoneCascades` lost its in-memory copy**: the push reads the store, so a cascade
whose journal write failed is not on the wire (a leak: every other device's reaps still remove those
edges). `TombstoneCascadeOnTheWireTest` replaces `TombstoneUndeclareTest`.

**Reaps (§3.3).**
- `PendingReapRemoval` (wire name → T) is enqueued by `TombstoneRegistry.recordReapPoint` in the
  watermark's transaction, only when the point moves forward. One row per wire name: a later T
  replaces it. The drain (`SyncScheduler.drainReapRemovals`, after the tombstone drain) sends at most
  1,000 per request and deletes an entry only if it still holds the T it sent.
- ⚠ `recordReapPoint` is also the deleter's own cascade's call site (`SyncObserver.enqueueTombstone`),
  so the deleting device sends its descendants twice: in the cascade and as reaps, at the same T.
  Redundant and idempotent at the relay, as §10.12 Q4 says.
- The outbox is addressed outward, so `purgeOutboundQueues` drops it with the other queues.

**Removed.** The emit-time edge declare and `declareResolvedHolder`'s edge (both describe only now),
the declare-on-move successor declare (`SuccessorDeclaration.kt`, `TombstoneEmitter.emitRetiring`,
`TombstoneCascades`' successor record, `DataIdMigrator`'s `successor`, `declarableEntriesOf`),
`undeclareHolders` after a tombstone push, and the backfill's edge half. `ContentDeclarationBackfill`
describes ref sets only, one description per `(content, handle)`, and now describes content with no
reference root too (the handle is optional). Tests that pinned only the removed behaviour were deleted
with it (listed in the B3d commit).

**Not done in B3d, owed elsewhere.**
- ⚠⚠ **The threat-model amendment (§7) is not written.** House Rule 2 puts it in the same change as
  the wire; it should land with whichever half merges second, or with both together.
- ⚠ On iOS the describe still follows `putChunk`'s hand-off to `nsurlsessiond`, not the background
  upload's completion (§10.12 Q3's caveat). Unchanged by B3d.
- Shared realms: `SharedFile` declares nothing, so no edges (§10.12 Q6). A shared-realm entity's reap
  joins the personal outbox and reaches the personal relay, the same pre-existing gap the tombstone
  plane has (`SyncScheduler.drainPendingTombstones`).

**Tests that need B3c.** Every system test that syncs through the in-repo relay, because a pre-B3c
`/sync/push` does not parse an `EdgedPush` body; in particular `RelayEdgesMatchTheRowsSystemTest`
(§8's system test: delete racing an overwrite and a recreate on two devices; it asserts the relay's
live holders equal `contentEdgesOf` of both devices' rows through `ContentEdgeStore.holdersOf`, which
B3c must keep answering with the `NULL` filter of §2 rule 3) and `MovedContentKeepsALiveEdgeSystemTest`.
⚠ The harness now wires `initFileService` as production does (emitter holder and object store). Before
that it ran the pre-B3b folder cascade, which reaped a peer's writes above T on the device applying
the tombstone while the deleter kept them, so this test diverged on a merge with B3c. With the
production wiring both devices keep the writes above T and the relay matches them.
⚠ `TestSystem` has no process restart, so that test uses a push partition as the restart window, and
the restart durability of the three client journals is pinned at unit level instead
(`EdgesOnPushTest`, `TombstoneCascadeOnTheWireTest`, `ReapRemovalOutboxTest`).

## 11. As built: relay-metadata fixes (2026-10-04)

Three fixes from the maintainer's review of the B3–B6 cutover (the audit is `relay-metadata-minimisation.md`,
findings F5/N1, F2 and F1). All three ride that hard cutover and relay reset, so wire values
change in place: no new route, no new relay migration, no `RELAY_PROTOCOL_VERSION` move, and no HR 9
bump (no composite changes shape).

### 11.1 Keyed property tags on content edges (F5 / N1)

- **What changed.** `PushedEdge.propertyId` carries
  `hex(HMAC-SHA256(K_tag, holderWireName ‖ propertyId))` (64 lowercase hex chars), where
  `K_tag = HKDF-SHA256(sharedVolumeKey, info = "tenshu/realm/content-property-tag/v1")` and
  `holderWireName` is the block's own `targetEntityIdHash` (the EI-1 wire id). Code:
  `ContentPropertyTag` / `ContentPropertyTagger` in `:sync-service-proxy`.
- **Convergence.** Every device of a realm derives the same `K_tag` from the realm's volume key and
  the same holder name from EI-1, so one register keeps one tag across devices; rule 1 is unchanged.
  The concatenation needs no separator because the holder is a fixed-width 32-hex wire id.
- **Domain separation.** `K_tag` is its own HKDF child, following EI-1's convention of one `info`
  label per purpose, so a tag can never equal a wire id computed over the same bytes.
- **Per holder, not per realm.** `HMAC(K, propertyId)` alone would be a per-realm constant, and the
  relay could cluster rows into types by which tags co-occur. Folding in the holder makes every row's
  tags unrelated.
- **Where it is applied: at drain time, at all three drains** (`PushBlockImplementation`,
  `flushPendingBlocks`, `SyncScheduler.drainSharedRealms`), under the realm the block is addressed to
  (`block.ownerId`), which is the realm whose key named its holder. `PendingBlockEdgeStore` keeps the
  plaintext id on purpose: it never leaves the device, and `FreedContentResolver` reads the property
  back from it to decide repairability. A block whose realm has no key here is not pushed (it stays
  queued), because there is no honest tag to send.
- **Send-side guard.** `RestSyncRelay.pushEdged` refuses, locally and before any request, an edge
  whose `propertyId` is not shaped like a tag, so a future caller that forgets to tag fails loudly
  instead of leaking (`RestB3WireTest.anUntaggedEdgeNeverLeavesTheDevice`).
- **Relay: no code change.** Confirmed: the relay only shape-checks the value with `EDGE_ID_PATTERN`
  (64 hex chars pass) and uses it as a primary-key component (`ContentEdgeStore`,
  `SqliteContentEdgeStore`); nothing interprets it. The duplicate check (400 on a repeated
  `propertyId`) still works, because distinct properties of one holder give distinct tags. The
  `contents` describe carries no property id, so only `PushedEdge` changed.
- **Nothing reads a property id back.** No relay response carries one: the B4 `409` body names content
  ids only (`ContentRefusalDto`), and no route returns edges to a client.
- **Storage.** Rides migration 22's drop-and-recreate of `content_edge`. A relay that held plaintext
  rows from a pre-tag build of this branch must be reset with the rest of B3–B6 (test data only).
- **⚠ Still plaintext: `regenerable`.** The relay learns, per row, how many slots it has (*none*
  included) and how many are derived versus primary. §7's item 4 stands; item 1 now reads "a
  content-bearing *slot*", not a named property. Threat model: the 2026-10-04 keyed-property-tag
  amendment after the B3c binding amendment.
- **Tests.** `ContentPropertyTagTest` (convergence, per-holder and per-realm separation, the
  shape, domain separation, the tagger's realm choice and its refusal without a key);
  `RestB3WireTest` (tags on the wire; an untagged edge never leaves the device).


### 11.2 EI-4: the tombstone's type is one bit (F2)

- **What changed.** `DeleteBlock.targetEntityType`, on `POST /sync/push-tombstone` inside the
  `CascadedTombstonePush`, is `Device.ID` for a device tombstone and the sentinel `tenshu/entity` for
  every other one (`TombstoneWireType`), mapped at drain time in `PushTombstoneImplementation`.
  Design and reasoning: `sync-entity-identity.md` §5b, which records this as built.
- **Client only.** Same field, same position, same type: no reshape, no HR 9 bump, no protocol move.
  The relay compares the value only with `Device.ID` (`SyncRouting`'s tombstone handler,
  `SyncMailbox.deviceTombstoneCount`) and stores it in `delete_block.target_entity_type`, so it needs
  no change. Receivers resolve the target through `targetEntityIdHash` → `resolveDataId`, never by
  type.
- ⭐ Lands before the B3–B6 reset, so `delete_block` keeps no concrete-type history.
- **Tests.** `TombstoneWireTypeTest`; `TombstoneSentinelCollisionTest` in `:system-test-utils` (the
  sentinel collides with no id across the full service graph).

### 11.3 `POST /sync/fetch-entity-blocks` takes the wire id (F1)

- **The defect, confirmed.** `RestSyncRelay.fetchEntityBlocks` serialised the whole `DataId` (a File's
  type plus its path; a shared-realm entity's `"$realmId::"` prefix too) and the relay hashed it with
  the **local** `getDataIdHash()`, a decimal 64-bit FNV, while every block since EI-1 is stored under
  32 hex chars of keyed HMAC. The route therefore returned `[]` for every real block — the on-demand
  manifest path (`OnDemandResolutionImpl`, for files with no content id) and `EntitlementRecovery`
  silently found nothing — and every call disclosed the path. The relay test that pinned the route
  seeded its block under the local hash, so it could not notice.
- **What changed, in place (hard cutover, no new route).** The body is the entity's EI-1 wire id as
  UTF-8 `text/plain`; the relay shape-checks it with `EDGE_ID_PATTERN` (400 otherwise — a serialised
  `DataId` fails it) and compares it verbatim with `sync_block.target_entity_id_hash`. `SyncRelay`'s
  method takes `entityWireId: String`, so no caller can hand it a `DataId`.
- **Client.** `SyncProcessor.wireIdOf` names the entity under the processor's realm — the same
  resolver that names that realm's ingest, so a shared-realm entity resolves under its realm's key on
  its realm's relay (`OnDemandResolutionImpl.routeRuntime` already pairs the two). No realm key ⇒
  nothing is fetched (on-demand: a no-op success; recovery: `NotAttempted`); there is no `DataId`
  fallback.
- **The route's comment** cited the threat model's "Property-ID exposure" section for a body that
  leaked more than a property id; it now says what the body is. Threat model: the 2026-10-04
  `fetch-entity-blocks` amendment under T-R-08. OpenAPI: known issue 2 marked fixed, body re-specified.
- **Tests.** `FetchEntityBlocksByWireIdSystemTest` (`:system-test-utils`): a device's real
  `RestSyncRelay`, over HTTP to the in-process relay, returns exactly the blocks the device's own drain
  pushed for an entity — no seeded hash. `SyncRelayTest` stores under a wire id, fetches by it, and
  pins the 400 on a serialised `DataId`. `EntitlementRecoveryTest` asserts the recovery asks by the
  wire id and that the plaintext key never crosses.

### 11.4 What the audit read differently

- **F1's remedy** proposed an additive `/by-wire-id` route; the B3–B6 cutover made the in-place change
  possible, as the audit's own v0.2 note anticipated.
- **F5's tag** was proposed as `HMAC(K_entity, propertyId)`. As built it is keyed by a **separate**
  HKDF child and folds in the **holder**, so a tag is not a per-realm constant the relay could use to
  cluster rows by type. The audit also listed `declare` and `undeclare` as carrying property ids:
  in B3–B6 the edge form of `declare` and all of `undeclare` are already deleted, and the describe
  carries none, so only `PushedEdge` changed.
- **F2** needed no relay change and no history scrub, exactly as the audit's Phase 1 table said for
  inside the B3–B6 cutover.
