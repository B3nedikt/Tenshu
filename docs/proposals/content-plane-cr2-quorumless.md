# CR-2, quorum-less — freeing content without asking every device

**Status:** v0.3 (2026-09-29), ⭐ **DECIDED: candidate B (§10), with batch option (ii)**, unbuilt. Phases in
§10.7. ⚠⚠ **Supersedes
`content-plane-refcount.md` §9.14's option 1** (a device progress quorum, a lease that fences local
merges, retention of the deleting device's plaintext), decided 2026-09-22. The maintainer chose to drop
the quorum on 2026-09-26, after a day in which the existing barrier stalled all reclamation for a realm.
- **Candidate B (§10), CHOSEN 2026-09-29: relay-authoritative references with a grace period.** Pushes
  carry their edges in the relay's transaction, and the relay frees content T after its last edge, on
  its own clock.
  - Content-bearing deltas are pushed as **standalone blocks**, never inside a `DeltaBatchPayload`, so
    batches stay opaque (option ii, §10.5).
  - ⭐ **Which content a row references is a schema declaration** (§10.11, 2026-10-01): `ContentReferencing`
    is repurposed, and one pure `contentEdgesOf(schema, payload)` computes every push's edges (phase B3a).
  - ⭐ **The B2 audit's Q2–Q8 are answered** (§10.12, 2026-10-01; Q5 on 2026-10-02, built); §10.13 records a
    follow-up.
- **Candidate A (§4–§9): quorum-less device progress. NOT CHOSEN.** Kept as the record of why: thirteen
  review rounds kept finding timing gaps of the same shape (§10.1). ⚠ Do not build M2, M3's delete-side
  check, M5, the pull lease or reserved edges from §4.
  - What B reuses from A: M1 (the "Syncing…" banner), M4's resolution with P3's message, the product
    decisions in §3, and §9 Q2–Q6.

⚠ Read with `content-plane-refcount.md` §6d (D3), §9.14 (the decision this replaces), §9.15–§9.17
(what shipped this week), and the quorum decomposition this builds on. The threat model's T-R-09
records the supersession and the changed accepted-loss mode (House Rule #2).

---

## 1. Why this exists — what happened on 2026-09-26

The user deleted two folders (about 5 GB) and got no quota back. The cause was not the delete path.
ST-3b's barrier waits until **every** admitted device has read past the tombstone, and two devices had
not:

- **Desktop Control on a 2026-09-21 build** had fenced itself on a newer schema. It was running, and had
  been 8098 log entries behind for days with **no visible signal anywhere**.
- **An iPhone** had not been foregrounded (469 behind).

Updating the desktop app and opening the phone freed everything within minutes. The barrier did its
job. What failed:

1. **Any one device can freeze reclamation for everyone, for ever:** an orphaned id, a sold phone, a
   fenced build.
2. **The user cannot see it.** A stalled barrier looks exactly like "delete doesn't work".

§9.14's option 1 would not fix either. It replaces the cursor barrier with a quorum that has the **same
liveness**: every admitted device must answer.

## 2. The one hazard, restated

Everything here protects one case, whose byte loss has been measured:

- **Device B** holds an **unpushed write** that references content C, typically a move `/x → /y`.
- Meanwhile **device A** deletes `/x`, and its sweep frees C.
- B's `/y` then lands naming bytes that no longer exist.

"Has B read the log?" cannot detect this: B can be fully caught up and still hold the write. §6d's
fence and declare-on-move shrink how often B can be in that state. This document decides what
happens in the remainder.

**The standard (T-R-09): make a loss visible, not impossible.** No device may block reclamation
for more than a bounded time. The rare device that loses the race tells its user so.

### 2.1 Why devices cannot decide the free alone (added 2026-10-02)

Every device can compute a row's edges itself (B3a's `contentEdgesOf` runs on the client), and dropping
**local** bytes is a purely local decision (§10.9). The relay is involved in exactly one decision:
**freeing the stored ciphertext**, the act that returns quota. That act is shared and irreversible, since
once any device frees C it is gone for every device. So whoever frees C must know that **no** device
still references it, including references that exist only as unpushed writes elsewhere. The hazard
above is precisely such a write: A cannot see B's pending `/y`, however caught up A is.

That leaves three shapes, and each design on this track is one of them:

1. **Every device agrees before a free.** This is ST-3b's barrier and §9.14's quorum. It is correct, but
   its liveness is the slowest device's: on 2026-09-26 one fenced desktop build and one unopened phone
   froze reclamation for the whole realm (§1).
2. **One point sees every reference.** All devices already go through the relay, which orders their
   pushes. If an edge lands in the same transaction as its block (G1, candidate B), the relay's "does
   anything still reference C?" and B's "`/y` now references C" are serialised in one place. A stale
   push is then refused with `content_freed` (G3), so the loss is detected at the moment it happens.
3. **One device decides from its own view and accepts the race.** This is candidate A's shape. Its basic
   flaw is that the check runs on the device and the free runs on the relay, so B's push can land between
   A's last check and A's free, and nothing on A can close that window. A's view also has blind spots:
   opaque batches, a sibling runtime's unpushed writes, stale caches, in-flight requests, legacy clients
   (§10.1). Under shape 3 a lost race is **silent at the protocol level**. It surfaces only if the losing
   device checks afterwards, or when someone next opens the file.

Shape 2 costs metadata: the relay learns opaque `(holder hash, property id, content id)` triples, which
link rows to blobs without revealing either (T-R-09; House Rule 1 holds). Shape 3 keeps the relay
edge-blind but moves all loss detection and repair into the applications.

**Maintainer note (2026-10-02): candidate B stays, for now.** The maintainer considers shape 3 a
possibly acceptable trade-off: the protocol need not guarantee that no device frees what another still
needs, provided applications protect against unwanted frees (pinning, P1's refusal of unrepairable
offline moves) and repair the cases that slip through, such as the move race above. This is recorded as
a candidate simplification, not a decision. Revisiting it would change B3c/B3d (the relay edge register)
but not B3a/B3b, which are client-side.

### 2.2 Prior art: how other systems free shared blobs (survey, 2026-10-02)

The problem is generic: garbage collection over shared storage, where writers can hold references the
collector cannot see. The survey below was compiled from primary sources (mostly source code and docs on
GitHub, read 2026-10-02). Several vendor sites were blocked from the research sandbox, so their claims
were checked against source code instead. Items marked *unverified* rest on secondary sources only.

| System | Who decides "unreferenced" | What the server learns | How concurrent / offline writers are protected | §2.1 shape |
|---|---|---|---|---|
| **Keybase KBFS** (E2EE) | A **writer client**: each folder's quota-reclamation job walks the metadata history and removes dropped references | Per block: a count of references keyed by `(block id, random 64-bit RefNonce)`, plus creator and writer UIDs. Never the tree, since pointers live in encrypted metadata | One ordered metadata log per folder. Conflicting writes go to an **unmerged branch**, and nothing is unref'd from unmerged revisions. Grace of **2 weeks** (`defaultQRMinUnrefAge`), with the head required to be 2 weeks + 24h old, measured by the **server's** timestamps | 3, over client-declared server counts |
| **Peergos** (E2EE) | The **server**: mark-and-sweep from each writer's mutable pointer | The block graph: CHAMP node links are cleartext CIDs in CBOR. File and folder relations stay inside encrypted cryptree payloads | Blocks of open upload **transactions** count as reachable, and transactions expire after 24h. Any unreadable block cancels the round | 2, by exposing structure |
| **Proton Drive** (E2EE) | The **server** | The tree: `ParentLinkID`, node type, size, times, state (draft / active / trashed / deleted), and revisions as block lists. Names and keys are encrypted | No client-held references exist, since the server holds the tree. Trash then delete, cascaded on the server (the cascade internals are *unverified*) | 2, by exposing structure |
| **MEGA** (E2EE) | The **server** | The tree: a plaintext parent handle per node, with encrypted attributes | Server-side delete and rubbish bin. Content sharing between copies and versions is *unverified* | 2, by exposing structure |
| **Duplicacy** (encrypted dedup backup) | The **client** running `prune` | File names, renames and deletes only | **Two-step fossil collection.** Unreferenced chunks are renamed to fossils, and a fossil is deleted only once **every** snapshot ID has a newer, completed snapshot. A fossil that turns out to be referenced is restored | 1, softened |
| **Kopia** (encrypted dedup backup) | One designated **maintenance owner**, under an exclusive lock | Blob names, ages and deletes | Time margins (`SafetyFull`): packs ≥ 24h old, content ≥ 24h old, 4h between snapshot GCs, **two GC cycles** required, sessions expire after 96h. Index epochs of 24h, and an error on index writes spanning more than 2 epochs ("laptops going to sleep") | 3, with time margins |
| **restic / borg** (encrypted dedup backup) | The **client** running `prune` / `compact` | Lock files (borg 2.x encrypts them) | **Exclusive lock** against shared locks. restic treats a lock older than 30 min as stale. borg 2.x expires a lock only if both the client clock and the store's mtime agree | 1, with locks |
| **Git** | Local `gc`, by reachability | Not applicable (local) | **Grace period**: `gc.pruneExpire` is 2 weeks, and writers "freshen" an existing object's mtime. Cruft packs keep per-object ages. The docs admit "some risk of corruption (low in practice)" | 3, with grace |
| **IPFS / Kubo** | Each node, for its own store. Pins are the roots | A pinning service learns the CID, an optional name and meta, and the requester | No global refcount at all. Persistence is explicit pinning | Pins (the application protects) |
| **Matrix / Synapse** | Nobody, for E2EE media: the server cannot link encrypted media to events | Upload and access times | Age-based purge (`media_retention`, off by default). MSC3911 (open) would attach media to events, with unattached uploads deletable after about 10 minutes | Time-based retention, otherwise leak |
| **Signal** | Backups: the **client** lists and deletes its unreferenced media | Backup media ids | A backup is a lease: if not refreshed, it may be deleted after 30 days (server job default 120 days). The transit-attachment TTL is *unverified* | Leases |
| **Java RMI DGC** | The server, by **leases** | Which client holds which reference | Dirty / clean calls with a 5-minute default lease. Silence counts as release, so a partitioned client can lose its object | Leases |

**What this tells us**

- **Every design trades safety against liveness in one of four ways.** Each way's weak point has already
  appeared on this track:
  - **Exclusive locks:** stale-lock recovery is the dangerous part.
  - **Two-phase deletion gated on evidence that every writer has moved on** (Duplicacy): offline writers
    stall it, so a cut-off is added (Duplicacy ignores repositories idle for 7 days), and the cut-off reopens
    the hole for a writer slower than it. This is ST-3b's barrier and §1's incident, almost exactly.
  - **Pure time margins** (Git, Kopia, MSC3911): a writer slower than the margin can lose data.
  - **Leases** (Signal, RMI): silence counts as release.
- **E2EE systems split by what they reveal.** Peergos, Proton and MEGA let the server decide by showing it
  structure, which is more than candidate B's opaque triples. KBFS is the only one whose server cannot see
  references.
- **KBFS is the closest precedent**, and it is shape 3 made safe by four things:
  - client-declared, opaque reference counts on the server (a random nonce per reference, so clients need
    not coordinate);
  - a collector that only writers may run;
  - a **2-week** grace period measured on the server's clock;
  - nothing freed from unmerged (conflicting) branches.

  ⚠ Candidate B keeps the first and moves the collector onto the relay. The maintainer's shape-3
  alternative (§2.1) would be KBFS's arrangement: a client collector, with the grace period still enforced
  on the server's clock.
- **None of these systems has the server derive references from content.** Every one either sees the
  structure or is told about it by clients. This agrees with the refcount doc's House-Rule-1 finding:
  references here are client-declared, never derived by the relay.
- **Worth borrowing whichever shape stays:**
  - Duplicacy's **restore-from-fossil**, which is B4's REVIVE.
  - Kopia's **two GC cycles** before a delete.
  - KBFS's rule never to free on behalf of an unmerged branch. Our analogue is never freeing from a device
    that is not caught up, which P2 already approaches.

**Sources** (read 2026-10-02)
- KBFS: `github.com/keybase/client`, files `go/kbfs/libkbfs/folder_block_manager.go`, `go/kbfs/libkbfs/modes.go`,
  `go/kbfs/libkbfs/interfaces.go` (BlockServer, MDServer) and `go/kbfs/kbfsblock/context.go`.
- Peergos: `github.com/Peergos/Peergos`, files `src/peergos/server/storage/GarbageCollector.java` and `README.md`.
- Proton Drive: `github.com/ProtonMail/go-proton-api`, files `link_types.go`, `block_types.go`, `link_folder.go`.
- MEGA: `github.com/meganz/sdk`, file `src/commands.cpp` (`CommandPutNodes`, `CommandDelNode`).
- Duplicacy: `github.com/gilbertchen/duplicacy/wiki/Lock-Free-Deduplication` and `/wiki/prune`; G. Chen,
  IEEE Trans. Cloud Computing (`ieeexplore.ieee.org/document/9310668`).
- Kopia: `github.com/kopia/kopia`, files `site/content/docs/Advanced/Maintenance/_index.md`,
  `repo/maintenance/maintenance_safety.go`, `internal/epoch/epoch_manager.go`, and issue `kopia/kopia#1090`.
- restic: `github.com/restic/restic`, files `doc/design.rst` (Locks) and `doc/060_forget.rst`. borg:
  `github.com/borgbackup/borg`, file `docs/internals/data-structures.rst` (branches 1.4-maint and master).
- Git: `github.com/git/git`, files `Documentation/git-gc.adoc` (NOTES), `Documentation/config/gc.adoc` and
  `Documentation/gitformat-pack.adoc` (cruft packs).
- IPFS: `github.com/ipfs/kubo`, file `docs/config.md`; and `github.com/ipfs/pinning-services-api-spec`.
- Matrix: `github.com/element-hq/synapse`, files `docs/admin_api/media_admin_api.md` and the configuration
  documentation; and `matrix-org/matrix-spec-proposals#3911`.
- Signal: `github.com/signalapp/Signal-Server`, files `ArchiveController.java` and
  `RemoveExpiredBackupsCommand.java`.
- Java RMI: Oracle Java SE 8, `java/rmi/dgc/DGC.html` and `sunrmiproperties.html`.

## 3. Product decisions (maintainer, 2026-09-26)

- **P1 — offline moves of content this device cannot repair are refused, with a dialog.** This replaces
  §6d's "defer rather than refuse". The deferred-move machinery (intent, pin, replay, resume)
  becomes a fallback for the few paths that still reach it (§9 Q5).
- **P2 — no in-between states.**
  - A device that knows it is **not caught up** shows **"Syncing…"**.
  - Content whose bytes it cannot fetch *while not caught up* is held back rather than shown as broken.
  - The catch-up then either delivers the bytes, or delivers the tombstone and the row goes away.
- **P3 — a lost race is shown, not hidden:** "`photo.jpg` was deleted on another device while your
  change was waiting."

## 4. The mechanism (candidate A; NOT CHOSEN, see §10)

⚠⚠ **Everything in §4 except M1 and M4 is candidate A's machinery, which was not chosen.** M1 and M4
carry over to candidate B, and §10.7 says how.

### M1 — "Syncing" is a first-class state

- **A caught-up signal the UI can read:** `caughtUp ∧ backfillCursor == 0`.
  - ⚠⚠ **Not `RealmConvergence.caughtUp` as it stands**. That value is deliberately
    **false during every in-flight poll**: `SyncScheduler` reports `hasMore = true` before each pull and
    records the outcome afterwards. Shown directly, it would flicker "Syncing…" on every poll and switch
    missing-content readers to placeholders each time.
  - So the UI gets a **stable** state: the outcome of the last **completed** pull (no backlog) plus
    `backfillCursor == 0`.
  - Reclamation keeps the momentary, fail-closed flag it uses today.
  - `caughtUp` alone is not enough: the forward cursor is promoted to the tip while a backward range is
    still unread (`content-plane-refcount.md` ~3750).
  - The signal is published on `SyncHealthStatus`. That is a sync-service **reshape**, so House Rule 9
    applies: a version bump plus its `SchemaMigration`.
- **Rendered in Drive and in Control.** "Needs update" (the schema pull fence) outranks it, because a fenced device
  never catches up. Showing "Syncing…" for ever would be the silent failure again, in a new place.
- **Reader rule for missing bytes (P2):**

  | this device | the relay | show |
  |---|---|---|
  | not caught up | anything | the row with a placeholder, plus the "Syncing…" banner |
  | caught up | content resolvable | the content |
  | caught up | content absent | M4 |

### M2 — Declare at conflicting ingest

When B ingests a tombstone for holder H that conflicts with **one of its own pending writes**, B
declares that write's edge **immediately**, as a single declare request outside the metadata push lane,
**before it requests the next log page**.

- ⭐ **The ordering comes from the pull.** The relay advances B's cursor only when B asks for the next
  page. So by the time the relay counts B as "past the tombstone", B's edge for `/y` already exists.
- ⚠⚠ **But only per DEVICE, not per runtime, and that is not free**. On iOS route-1
  and on the web, several runtimes share one `deviceId` and therefore one relay cursor
  (`SyncScheduler.buildSignedTip`, `PeerCursor`). A sibling runtime can process the same page and pull
  the next one while the runtime that found the conflict is still awaiting its declare, and the relay
  then counts the device as past the tombstone before the edge exists.
  - So phase Q3 owes a **device-wide pull lease**. A runtime acquires it **before it puts a page request on the wire**, and holds it
    through ingest, conflict detection and the declare. A flag written only after a page has been
    inspected serialises nothing, because a sibling could read it empty and pull first.
  - **The owed declares are persisted as work**, not as a bare flag: holder, property and content id,
    written in the same transaction that records the conflict. Whoever holds the lease next must
    **finish that work, or hand it to M4, before it pulls**.
  - ⚠⚠ **Expiry transfers ownership; it never waives the work**. A runtime that
    dies after detecting a conflict leaves the work in the store. The lease's owner and expiry only let
    a sibling take over, so a crash cannot stall the device's pulls for ever, and the sibling's first
    act is the dead runtime's declare. Treating expiry as permission to pull would let the cursor pass
    the tombstone with the edge missing, and M5 would then free it at once, not merely after W.
  - ⚠⚠ **The lease is an uncached, cross-process atomic primitive, not a row in the object store**.
    iOS runtimes bulk-load cache state, and `IndexedDbObjectStore` does not
    propagate changes across tabs (`content-plane-refcount.md`, the cache-aside warning). Two siblings
    could both read an absent cached lease and pull. Per platform:
    - **iOS:** a file lock (`flock`) in the App Group container.
    - **Web:** the Web Locks API (`navigator.locks`), which is exclusive across tabs.
    - **Android and desktop:** one proxy process, so an in-process mutex suffices.

    The persisted declare **work** may live in the store; the **ownership** may not.
  - ⚠⚠ **Everything a pull decides or reports on is read uncached, under the lock**. iOS runtimes bulk-load at open, and IndexedDB changes do not propagate across tabs, so any of
    these could be served stale by a sibling's cache:
    - the declare **journal**. A stale "no work" would let a taking-over runtime pull past owed work;
    - the **cursor**;
    - the **backfill coverage**. A stale `backfillCursor == 0` would report complete history while a
      sibling has reopened a backfill below the tombstone.

    Holding the pull lock, a runtime reads all three **from the underlying store**, and a writer commits
    them before it releases the lock. The coverage it reports is **not monotonic** on the relay: an
    "incomplete" report replaces an earlier "complete" one.
- ⚠⚠ **An M2 edge is RESERVED until its metadata is visible**. B declares `Y(C)`
  before its metadata block for Y has reached the log. Another device reconciling from a live walk
  captured before Y appeared would see an edge no row explains, and undeclare it. The existing contract
  already guards against this:
  - `content-plane-refcount.md` records `last_declared_seq` per handle;
  - the enumeration sweep only touches handles whose last describe is **at or before its walk's captured
    cursor**.

  ⚠⚠ **But no pull position is the right stamp**. A walk captured **after** the
  declare and **before** Y's metadata lands has a cursor at or past B's position, yet still cannot
  contain Y, so its reconciliation would undeclare the edge. So:
  - M2's declare marks the edge **reserved**, a relay-side state that **no reconciliation undeclare
    touches**;
  - once Y's metadata block has a relay sequence (the push's acknowledgement), B **confirms** the edge
    with `last_declared_seq` = **that metadata sequence**, and the reservation ends. Only then can a walk
    at or past it be trusted to have seen Y;
  - the declare work stays in B's journal, and the pull fence holds, until that confirmation. A
    successful declare alone releases nothing;
  - ⚠ a reservation is bounded, like the lease. If Y's metadata is never pushed (the write was
    abandoned), the journal entry is resolved by M4 or retired with the pending holder, and the
    reservation is released with it.
- ⚠⚠ **A refused declare keeps the pull fenced; it does not fall through to M4**.
  M4's `RESOLVABLE` branch would read "the bytes are there" and let the next pull go ahead with no
  edge installed. The relay could then free the content once the cursor passed the tombstone, and the
  pending write would push a dangling reference when quota recovered. So the declare work stays owed,
  and the device does not pull past it, until the edge is **accepted**, or until the user retires the
  pending holder.
  - To keep that rare, **a declare is exempt from the storage quota (507)**: it stores no bytes, and
    refusing it is what creates the risk.
  - 401 and 402 still refuse it. The device then shows the reason (signed out, no entitlement), which
    already stops its sync. Other devices are not stalled past W by it, because M5's cap applies.

**The detector is keyed by content, not by move provenance**. An online move
writes no `PendingMove`, and the only source→successor link (`TombstoneCascades.successors`) is in
memory and dies with the process. So "which pending write came from H" is not durably answerable.
What is durable is the outbound queue (the holders with unpushed writes) and the local rows those
holders name. Two cases:

1. **B still has a row for H, and H has no unpushed write of its own.** H's row then still says what
   the peer deleted. Read H's content ids **before** applying the tombstone, then declare every pending
   holder whose local row names any of them. That covers moves, copies and rename-then-edit, with no
   provenance.
2. **Otherwise: B has no row for H, or H itself has an unpushed local write.** B retired H itself (its
   own move or delete), or changed it, pushed or not. Either way H's local row no longer says what the
   peer deleted, so B declares **every** pending holder's edges.
   - ⚠ The "changed" half: B copies `H(C)` to a pending `Y(C)`, then overwrites H
     with D before A's tombstone for H arrives. Case 1 would read only D and never declare `Y → C`.

- ⚠ Case 2 is a superset. That is safe, because declaring more is never a loss. It is also cheap: it
  needs a conflict on a holder B itself retired or changed, and it costs one request per pending holder.
- ⚠⚠ **Each declare runs under the holder's per-property emit ownership, never from a snapshot**.
  The declare is an upsert keyed `(holder, property)`. So if M2 reads content C
  and an in-place overwrite then saves and declares D before M2's request lands, M2 writes C over D's
  edge, and D's live bytes become reclaimable. The rule is `SuccessorDeclaration.kt`'s:
  `withContentEmitOwnership(holder, property)` held across **both** the read of the row's content id
  and its declare, one property at a time.
- ⚠⚠ **Owed with it: persist the successor record** (`TombstoneCascades`). A move whose metadata
  already pushed has nothing pending for M2 to find. Its successor edge comes from declare-on-move's
  declare-before-undeclare in the tombstone drain, and after a restart that declare is lost, so the
  drain undeclares the source with no successor declared. That gap exists today, independent of CR-2,
  and M3 cannot protect an edge that was never declared.

### M3 — The relay refuses to free what something still references

- `POST /sync/content/delete` and `/sync/content/reclaim` **refuse the refs of any content that has a
  live edge**, checked **in the same transaction** as the delete.
- ⚠⚠ **The delete must name the content it frees**. Today `ContentDeleteRequest`
  (`ContentRouting.kt`) carries only `refs`, while edges and the serialisation below are keyed by
  `contentId`. A legal edge may also carry no refs, so a ref with no `content_ref` row cannot be
  attributed to any edge, and the check could not stop a delete behind a live edge. So phase Q2 changes
  the route:
  - the request carries the **`contentId` of each ref set**;
  - the relay checks and locks **by that id**;
  - it **refuses** any ref it cannot attribute to the named content.
- ⚠⚠ **Two-sided: declare and delete are serialised per content id**. Guarding
  only the delete leaves the other order open: the delete commits first and sees no edge, then an M2
  declare lands "successfully" on content whose bytes are already gone, and the device advances
  believing its holder is protected.
  - So a declare takes the same per-content serialisation as the delete.
  - A declare for content whose blobs the relay has already freed returns **`already_freed`**, never
    success.
  - The client treats `already_freed` as M4's `ABSENT`.
- Today `/delete` trusts the client's refs verbatim, and `/reclaim` checks edges once up front, not
  atomically (the declare-on-move notes).
- A refused ref reads as **not freed**. The sweep keeps or forgets its candidate accordingly, and never
  treats the refusal as an error to retry.
- ⚠ This is a relay change, so it needs a threat-model amendment (House Rule 2). The edges are already
  relay-visible, so House Rule 1 still holds. Whether `RELAY_PROTOCOL_VERSION` moves depends on whether
  old clients can misread the new refusal; §8's phase Q2 must check that.
- ⚠ At N6, when `content_edge` retires, the same check moves to the handle count
  (`content_reference`).

### M4 — Resolving the lost race

This applies when B's pending write reaches the relay, or the reader rule (M1) finds a caught-up device
with absent bytes. ⚠ The question is asked with **`contentResolvability`'s three answers**, never with
`ensureContentOnRelay`. That call returns `true` only for resolvable. It turns
both a definite absence and an unknown answer (relay unreachable, realm locked, manifest undecipherable)
into an attempted re-emit and `false`, so it cannot tell "freed" from "could not ask".

- **`RESOLVABLE`:** proceed. Nothing was lost.
- **`UNKNOWN`:** **never classify.** Keep M1's waiting state and ask again later. A transient failure
  must not tell the user their file was deleted.
- **`ABSENT`, and B holds the plaintext:** re-upload under the **same** content id, invoked separately
  (`repairFileContent`). The user never sees it.
- **`ABSENT`, B holds no plaintext, with evidence B had seen the content published:** the content was
  **freed**. Show P3's message. The row is either retired or kept as "content unavailable" (§9 Q2).
- **`ABSENT`, no plaintext, no such evidence:** the content may never have been published (another
  device's upload still in flight). It stays in M1's waiting state. Once caught up and past W, it is
  shown as **"content unavailable"**, never as "deleted on another device".
  - ⚠ **"Past W" is anchored to a persisted time**. This case has no tombstone and
    no relay marker to start the clock. So phase Q4 records the **first `ABSENT` observed while caught
    up**, per content id, next to the "seen published" record. The deadline is that time + W, and it
    survives a restart. Without it, a restart would reset the clock and leave the row waiting for ever,
    contradicting P2.
  - A later `RESOLVABLE` clears the record.

**The evidence must be recorded, because nothing records it today**. §9.17's
probe only confirms that **metadata** writes are accepted. `MoveFencePolicy` returns before its
local-byte preflight when the probe succeeds, and neither checks that the content id resolves on the
relay. So phase Q4 owes a durable **"seen published"** record per content id, written when this device:
- published **and declared** it itself (the one-content-one-upload marker, persisted);
- got `RESOLVABLE` from `contentResolvability`;
- downloaded its bytes.

A move of content with no such record asks `contentResolvability` once and records the answer. That
costs one request, only when the evidence is missing. This is why no relay "freed vs never seen"
tombstone is needed for moves.

### M5 — A bounded wait replaces the unbounded barrier

M2 only helps if the free waits long enough for an **online** peer to ingest the tombstone. The
deleting device's sweep therefore waits until **every device seen within the last W** has read past the
tombstone, **capped at W** (§9 Q1).

⚠⚠ **"Seen" needs a signal that does not exist yet**:
- `device_cursor` holds only a sequence number, updated with `MAX`, so an idle device polling at the
  tip never moves it.
- `DeviceLastSeen` tracks authored-block HLCs, is clock-skewed, and is documented as display-only.

Either way, an actively polling idle peer would read as silent and be excluded before its M2 declare
is observed. **Phase Q5 therefore owes relay and wire work**:
- the relay records a **`last_pull_at` per device, on its own clock**, written on every log pull,
  including an empty one at the tip;
- ⚠⚠ **Every comparison happens in the relay's clock domain**. The deleting device
  receives no relay "now", and its tombstone carries only a device HLC plus `relaySeq`. So comparing
  `last_pull_at` with the device's own clock would let a device clock ahead by W exclude an active peer
  at once, and one behind it keep the cap from ever expiring. Since W now bounds a permanent loss, the
  relay returns **ages, not timestamps**, all measured at the relay:
  - each peer's **`ms_since_last_pull`**, next to its cursor;
  - each peer's **complete-history coverage** (`backfillCursor == 0`, reported by the device). ⚠⚠ The
    cursor alone does not prove a peer ingested the tombstone: `backfillOnce` promotes the forward cursor
    to the tip while an older range is still unread, as M1 notes. A peer still backfilling counts as
    **not past** any tombstone until the cap expires;
  - the tombstone's **`ms_since_received`**, from the relay's receipt time of that `relaySeq`.

  The client compares only ages with W, so no two clocks ever meet. (Evaluating the whole window on the
  relay is the equivalent alternative.)

Two caveats:
- ⚠ **Disclosure:** the relay already observes every pull (House Rule #1 holds). Exposing pull times to
  the realm's own devices is new, but it is data those devices learn about each other anyway. The
  threat-model amendment co-lands with phase Q5.
- ⚠ **Wire compatibility:** a new response field is additive. Whether `RELAY_PROTOCOL_VERSION` moves is
  decided the same way as Q2's.

- A device that has not read past the tombstone within W no longer blocks anything. When it catches
  up, M4 handles it:
  - it re-uploads if it holds the bytes;
  - otherwise the user gets P3's visible message.
- ⚠⚠ **"Not past the tombstone within W" includes devices that are ACTIVE**. A
  device can keep pulling, and keep refreshing `last_pull_at`, while its cursor is still behind the
  tombstone: a large backfill, or a slow catch-up after a long absence. The cap still bypasses it; that
  is what "capped at W" means. So the accepted loss is **bounded by W, not by silence**: any device not
  past the tombstone within W, holding a pending reference without plaintext, loses the race and says
  so. Keeping active laggards blocking would give back the unbounded stall this document exists to end.
  - M2 cannot help such a device either: it declares at **ingest**, and a laggard has not ingested the
    tombstone yet.
  - P1's refusal of offline moves removes the usual way a device holds such a reference while far
    behind.
- ⇒ **No device can stall reclamation for longer than W.** That removes the failure of 2026-09-26
  without giving up the protection for devices that keep up.

⚠⚠ **A writer from before CR-2 must be refused before the bounded wait is enabled**. It performs neither M2 nor M4, so if it returns after W it pushes its stale reference and
produces exactly the silent dangling row this design exists to remove. A service-version bump does
not stop it, because the schema fence is **pull-fenced but push-allowed** (`core-schema-evolution.md`
§7.3). So phase Q5 raises a **writer floor on the relay**: the minimum accepted `RELAY_PROTOCOL_VERSION` for
pushes moves to the first CR-2 client. This is a flag day only for writers, and only after the
maintainer's devices have all updated. Old clients then fail loudly with 426, never silently.
⚠⚠ **And on every cursor-advancing pull.** A pre-CR-2 runtime on iOS route-1 or the web shares the
upgraded sibling's `deviceId` and cursor, but it knows nothing of the pull lease or M2. It could request
the next page while its sibling's declare is outstanding, and a 426 on its later push cannot restore
freed bytes. So once the floor is raised, a `/sync/log` pull without the CR-2
capability is either refused or **does not advance the cursor the barrier trusts**.
⚠⚠ **The version must travel on every push, which it does not today**.
`RealmRouting.kt` gates the protocol version at **genesis only**, and `RestSyncRelay` sends it only to
`POST /sync/realm`. A pre-CR-2 device registered earlier would present no version on its later pushes
and receive no 426. So phase Q5 adds the protocol version, or a CR-2 capability flag, to **every
authenticated push** (or persists it in a device credential the push path checks). A push without it is
refused once the floor is raised.

## 5. What this retires

- **§9.14 option 1, whole:** the device progress quorum, the merge-fencing lease, the synced
  awaiting-restore marker, and retention of the deleting device's plaintext.
  - "Retention has no terminal event" **dissolves**: nothing is retained for safety any more.
    Keeping plaintext as *undo* becomes a product choice (§9 Q4).
- **ST-3b's unbounded roster barrier**, replaced by M5. This answers N5's open "delete or keep"
  sub-decision:
  - the **wait** is replaced;
  - the recorder and the candidate ledger stay, feeding the sweep.
- **§6d's defer-rather-than-refuse**, per P1.

## 6. Prerequisites

1. **A truthful declaration plane.**
   - Measured 2026-09-26: 4.25 GiB of a 5 GiB realm was undescribed duplicate chunk sets.
   - In about 13% of duplicated contents the *live* set was the undescribed one.
   - M3 decides on edges, so the edges and descriptions must be right first:
     - stop the duplicate emits under fresh keys;
     - re-arm the declaration backfill;
     - reclaim the garbage.
2. **A visible fence.** M1's banner must never read "syncing" on a device that will never sync.
3. **Declaration coverage for every writer** — `content-plane-refcount.md` §9.9a, CR-6's precondition.
   M3 cannot protect a reference the relay has never been told about.

## 7. What it costs

- **One extra declare request** when a pending write conflicts with an ingested tombstone. That is rare.
- **Up to W of extra storage per delete**, for the bytes the bounded wait holds. This is billed to the
  user's quota until the quota-credit question (§9 Q6) is resolved. With W bounded, the discrepancy is bounded too.
- **The residual:** a device that has not read past the tombstone within W (silent **or** lagging),
  holding a pending reference to content it has no plaintext for. It loses the race and says so (P3).
  - P1's dialog removes the offline way into that state.
  - What remains is the probe→push gap combined with a later absence or catch-up longer than W.

## 8. Phases

⚠ Phases are **Q0–Q6** below. The open decisions in §9 are numbered separately, as "§9 Q1–Q6".

| phase | what | touches |
|---|---|---|
| **Q0** | Undescribed-duplicate heal: dedupe, backfill re-arm, garbage reclaim | client, then an operational step |
| **Q1** | M1: a stable catch-up field (last completed pull) on `SyncHealthStatus`, the Drive and Control banners, the schema pull fence's surface | sync-service (HR 9 bump), both UIs, SwiftUI twin |
| **Q2** | M3: the delete wire carries the `contentId`; the relay serialises declare and delete per content — a free refuses live-edge content, a declare of freed content answers `already_freed`; declares exempt from the 507 quota | relay + wire, threat model, deploy |
| **Q3** | M2: the content-keyed conflict detector at ingest; reserved edges confirmed with the metadata's relay sequence (relay: undeclares skip reserved edges); the out-of-band declare under per-property emit ownership; the device-wide pull lease (a platform cross-process lock) with persisted declare work that a taking-over runtime must finish before pulling; persist `TombstoneCascades`' successor record | sync-service-proxy, iOS + web lock shims |
| **Q4** | M4 + P3: the durable "seen published" and "first caught-up ABSENT" records, tri-state resolution, the reader rule, and the "deleted on another device" / "unavailable" UX | client, both UIs |
| **Q5** | M5: the relay's per-device `last_pull_at`, exposed as relay-measured ages (last pull, tombstone receipt) with the cursor and each peer's complete-history coverage; the protocol version on **every** push and cursor-advancing pull, plus the relay's floor refusing (or not trusting) pre-CR-2 ones; then the bounded wait replaces ST-3b's barrier wait | relay + wire, threat model, sync-service-proxy (**HR 9 bump + `SchemaMigration`** for the `PeerCursor` reshape) |
| **Q6** | P1: the offline-move dialog; decide the fate of the deferred-move machinery | Drive UI, file-service |

- ⚠⚠ **Every phase that reshapes a sync-service composite owes House Rule 9** (a service version bump
  plus its `SchemaMigration`), **independently** of whether `RELAY_PROTOCOL_VERSION` moves. A field the relay treats as additive is still a positional reshape to schema peers. Q1
  (`SyncHealthStatus`) and Q5 (`PeerCursor`) are known to; Q3 and Q4 check theirs with
  `SchemaShapeBaselineEnforcementTest`.
- ⚠⚠ **The bounded wait (Q5) is the LAST phase to switch on, and everything above it is its gate.** It
  is the step that introduces the accepted loss, so the phases that make that loss safe or visible
  land first:
  - Q2 (M3): the relay-side check that makes shortening the wait safe. Without it, Q5 frees content a
    peer is declaring at that very moment.
  - Q3 (M2): an online peer's conflicting write declares before the free.
  - Q4 (M4 + P3): the loss is **shown**. Without it, a device returning after W meets today's silent
    missing content (this used to be ordered after the wait).
  - The writer floor: a pre-CR-2 writer cannot push past W without M2 or M4.
- ⭐ The delete-vs-move system test (both orderings, **bytes** included) is owed before Q5, and is
  how Q5 proves itself.

## 9. Open decisions

- **Q1. W.** *(Candidate A only, void under B.)* Under B the equivalent is **T** (§10.5, §10.7 B2).
- **Q2. A freed row:** retire it automatically with a notice, or keep it as "content unavailable" for the
  user to dismiss?
  - ⭐ **Answered 2026-10-02 (maintainer): retire it automatically, with a notice.** The refused push never
    reached the log, so no other device holds that row: retiring it is a local drop with no tombstone, and
    the device converges with every other. The notice is P3's message, raised where it cannot be missed
    (Control's notification centre). Spec'd in `content-plane-cr2-b4-spec.md`.
- **Q3. While catching up:** show missing-bytes rows as placeholders (proposed), or hide them? Hiding a
  file a peer is still uploading looks like loss.
- **Q4. The deleting device's plaintext:** drop it at delete time as today, or keep a short undo window?
  This is now a product choice, not a safety one.
- **Q5. The deferred-move machinery after P1's dialog:** keep it as the fallback for online
  moves whose push is held back (a 402 or 507), or remove it and let M4 cover those?
- **Q6. The quota credit:** under B, deleted content is held for T after its last edge. Charge it,
  or exclude unreferenced bytes from the charged figure, a product choice (§10.5).
  - ⭐ **Answered 2026-10-02 (maintainer): excluded.** Held bytes are not charged (§10.14).

---

## 10. Candidate B — relay-authoritative references with a grace period

### 10.1 Why a second candidate

Candidate A decides "nothing references C any more" from **absence of evidence**, assembled across
devices: no edge seen, every recently active cursor past the tombstone, no declare pending. That evidence
lives in many places at once: several devices, several runtimes per device, runtime caches, and two
independent request streams (metadata pushes, and content declares on a separate best-effort request).
Each of thirteen review rounds found one more place where a reference exists but is not yet
visible to the decision. Each fix added machinery that needs its own proof, and the last one (reserved
edges) created a new unbounded stall:
- an unpushed write;
- a sibling runtime pulling first;
- a stale cache;
- a declare still in flight;
- a walk captured at the wrong moment;
- a lagging backfill;
- a legacy client;
- clock skew.

**The root cause is structural.** A reference can reach the log without reaching the edge table, and the
free is decided by devices from views that are never mutually consistent. Candidate B removes both.

### 10.2 The model

This is garbage collection with a grace period, the shape git's `gc.pruneExpire` uses for exactly this
reason (concurrent writers that the collector cannot see).

- **G1 — A reference reaches the edge table no later than the log.**
  - `POST /sync/push` carries the edges its block creates: `(holder, property, contentId)` for each
    content-bearing property the row names. The relay commits the block row and those edges in **one
    transaction**.
  - `POST /sync/push-tombstone` removes the tombstoned holder's edges, and its cascade's, in the same
    transaction as the tombstone row.
    - ⚠⚠ **The cascade must travel on the tombstone wire**. A recursive delete
      emits **one** tombstone, and the descendant holders exist only in the client's
      `TombstoneCascades`; `DeleteBlock` does not carry them. Once the device-side undeclare path is
      retired, every deleted descendant would keep its edge for ever, so a folder's content would never
      reach zero edges. So the tombstone push carries the **cascaded holder list**.
    - ⚠ **Cascade entries, and children updated after the folder's tombstone**.
      `DeletedFolderSubtreeReaper` removes every descendant regardless of HLC, including a child updated
      more recently than the folder's tombstone. A relay that only applied the tombstone's HLC to cascade
      entries would keep such a child's edge although no client keeps the row, and the subtree would
      hold quota for ever.
      - An **unconditional** drop would be wrong the other way: it could erase a legitimate later
        recreate.
      - So the cascade entries apply in HLC order, stamped with the tombstone's HLC, and the newer child
        is removed by the **per-reap removal** below, which carries the reaper's later HLC.
    - ⚠⚠ **Every local reap sends its own removal**. The deleting device's list
      cannot be complete:
      - a child another device pushed before the deleter pulled it is missing from the list;
      - a delayed write to a listed child, with an HLC newer than the watermark, would recreate its edge.

      In both cases every client still removes the row, through `DeletedFolderSubtreeReaper` and
      without a tombstone (`onDataDeleted` emits none), and after B6 nothing else would remove the edge.
      So **whenever a device's reaper removes a holder, it pushes a relay-visible edge removal** for it,
      stamped with **the reaper's own local-delete HLC**: a fresh HLC at reap time, newer than every
      write that device had seen for the child, including a concurrent edit it is reaping.
      - ⚠⚠ **Superseded 2026-10-01 (§10.12 Q4):** the removal is conditional, at the HLC of the version
        reaped, never the reaper's "now", which needs no clock skew to beat a legitimate recreate.
      - It is applied as an **ordered** register mutation, never unconditionally. A
        slow device's queued removal must not erase the edge of a holder that another device
        legitimately recreated later with a higher HLC. Clients keep that newer row, and so does the
        register.
      - Every device that reaps sends one, and they are idempotent. A delayed child write lands, is
        reaped by the next device that ingests it, and is removed again. The relay needs no parent/child
        knowledge.
  - ⚠ **Removals are explicit**. A write that **clears** a content-bearing property,
    rather than replacing it with another content id, would otherwise send no tuple for it, and the
    relay cannot decrypt the payload to tell a clear from an unrelated write, so the old edge would stay
    for ever. The push therefore carries **every content-bearing property its delta touches**: either
    `(property, contentId)` or `(property, none)`.
  - The separate best-effort declare and undeclare requests stop being the authority.
  - ⇒ **invariant:** the edge set equals what the log's live rows name. It is maintained by the relay's
    own transactions and never by a later client request that can fail.
  - ⚠⚠ **Edges are applied in HLC order, not arrival order**. A newer recreate of
    H can reach the relay before an older, still-queued tombstone of H. Clients keep the newer row,
    because `SyncProcessor` rejects the lower-HLC tombstone, but an unconditional delete would strip the
    live row's edges, and G2 would free its content after T. So each `(holder, property)` edge is a
    **last-writer-wins register stamped with the HLC of the write that set it**:
    - a tombstone removes an edge only if its HLC is newer;
    - a block replaces an edge only if its HLC is newer;
    - a stale write is a no-op on the edge table, whatever its arrival order.

    The comparison is the one clients use (shared code, not a copy).
    - ⚠⚠ **The stamp is each change's own semantic HLC, carried with its edge**.
      A `DeltaBatchPayload` block has a null target and a checkpoint HLC on the outside, while each
      encrypted `SyncDelta` inside carries the HLC clients actually order by. Ordering by the outer HLC
      would let an old delta sealed after a newer standalone update overwrite the newer edge. So every
      edge mutation on the wire carries its delta's HLC, and the relay never uses the block's.
    - ⚠ **A per-holder tombstone watermark outlives the edges**. Removing an edge
      removes its register, and with it the HLC a stale block must be compared against. A tombstone
      stored before the holder's first, older block arrives, or before a block adds a property never
      seen, would then let that block recreate an edge and suppress reclamation for ever. So the relay
      keeps the tombstone's HLC per holder (it already keeps `delete_block` rows), and an edge write for
      that holder at or below the watermark is a no-op.
    - ⚠ **Owed check before choosing:** that every content-bearing property converges by LWW on the
      clients. A property whose `SemanticMerger` could keep the **older** write's content would make the
      relay's register disagree with the rows. If one exists, the merge must re-emit its result as a
      new, higher-HLC block, which the register then follows.
- **G2 — Freeing is the relay's decision, on the relay's clock.**
  - ⚠⚠ **The sweep's free and a push's commit are serialised per content**.
    Without it the sweep could see an expired zero-edge content, a push could still find its bytes and
    commit a block plus edge, and the sweep would then delete the bytes behind an accepted push, with
    G3 never firing. So the sweep's final zero-edge check and its **logical** free (removing the content's
    `content_ref` rows and marking it freed) run in **one relay transaction**, and so do the push's
    presence check and its block-plus-edge commit. Whichever commits second sees the other's result.
    Physical blob deletion follows the logical free and is never observable: a push that lost the race is
    refused by G3.
  - ⚠⚠ **Physical deletion is conditional on nothing having rewritten the blob since the free**.
    A re-upload after G3 mints a fresh CEK, so its chunk refs differ. But the
    **manifest's ref is derived from the content id** (`manifestRefOf`), so a repair rewrites the very
    key being freed. A physical delete landing after that PUT would remove the repaired manifest, and a
    later accepted push would name missing bytes. So:
    - each queued delete carries the free's position in the relay's order;
    - the deleter removes a blob only if it has not been **PUT** since then, checked under the same store
      lock as a PUT;
    - a rewritten blob is skipped, and dropped from the queue.
    - ⚠⚠ **A dedicated PUT timestamp, never `last_referenced_at`**.
      `last_referenced_at` is refreshed by GET as well as PUT, and `ContentBlobRegistry` says so and
      forbids it as a freeing predicate. A stale device **reading** a freed blob before the deleter ran
      would look like a rewrite and turn reclaimed content into untracked orphan storage. So
      `content_blob` gains a column written only by PUT, and the deleter compares that.
    - ⚠ **An ordered generation, not a clock**. A millisecond timestamp can tie with
      the free: treating a tie as "older" deletes a repaired blob, and treating it as "newer" orphans a
      pre-free one. So both the PUT's **`put_seq`** and the free's **`freed_seq`** come from one relay
      counter, assigned under the store lock that serialises them, and the deleter removes a blob only if
      `put_seq < freed_seq`.
  - ⚠ **The deletions are a durable queue**. The logical-free transaction moves the
    content's refs into a `content_free_queue(owner_id, ref, freed_seq)` rather than just dropping the
    `content_ref` rows.
    - ⚠⚠ **Keyed by owner**. An identical ciphertext ref can exist in several owner
      keyspaces; the Mode-B cross-realm copy path permits exactly that. A ref-only queue could not tell
      whose blob it frees, so a drain or revive for one realm could remove another realm's live copy.
      Every enqueue, revive, acknowledgement and physical delete is keyed `(owner_id, ref)`, like
      `ContentBlobRegistry`. The physical deleter drains it idempotently and removes an entry only once its delete is
    acknowledged. A crash in between leaves work to resume, not orphans nobody can name.
  - When a content's **last** edge is removed, the relay stamps it `unreferenced_since = now`.
  - A new edge for that content clears the stamp.
  - A relay sweep deletes the content's described refs (`content_ref`) once `now − unreferenced_since > T`.
  - No device progress, cursor, lease or device clock is involved.
- **G3 — A late reference learns at push time.**
  - A push whose edges name content the relay has already freed is **refused** with `content_freed`; the
    block is not stored.
  - The client resolves it as candidate A's M4 does: re-upload if it holds the plaintext (an uploader
    always does), otherwise show P3's "deleted on another device".
  - ⚠⚠ **The way out of `content_freed` is one explicit, atomic transition: REVIVE**.
    - The client re-uploads every chunk and the manifest, then sends a **revive** for C with its full ref
      list. That is the existing describe (`contents` on `/sync/content/declare`), made the only way to
      clear the freed state.
    - ⚠⚠ **The revive rides on the retried push itself, in the same transaction**.
      A revive committed on its own leaves C with zero edges until the retry lands:
      - if the expired `unreferenced_since` survived, G2 could free C again;
      - if the revive cleared it, a client crash before the retry would leave described, zero-edge
        content with no future one-to-zero transition, leaking for ever.

      So the retried push carries the revive's ref list next to its block and edges, and the relay, in
      **one** transaction serialised with the sweep:
      - verifies that **every** ref is present and was PUT after the free;
      - restores `content_ref`, clears the freed marker and drops those refs from `content_free_queue`;
      - commits the block and its edges.

      Either all of it lands, or none of it does.
    - A chunk `PUT` alone never clears anything, so a partial repair cannot admit a row naming missing
      blobs. The conditional physical delete below remains the backstop for a revive that lands
      mid-drain.
  - With G1 this cannot happen silently: a row naming freed bytes cannot reach the log.
- **G4 — Never free what was never referenced.** The stamp is set **only** on an observed transition
  from one edge to zero. Content that never had an edge is never freed by G2. That covers legacy uploads,
  writers without declaration coverage (§9.9a) and the undescribed duplicate garbage; it is a leak, never a
  loss. Those populations keep their own owed work.

### 10.3 What happens to the hazard (§2)

B's move `/x → /y` is pending while A deletes `/x`:
- **B pushes within T** (24 h, §10.14; almost always, since B is usually online):
  - A's tombstone stamps C unreferenced;
  - B's block for `/y` lands with its edge and clears the stamp;
  - nothing is freed and no device ever waits.
- **B pushes after T:**
  - its push is refused with `content_freed`;
  - B re-uploads if it holds the plaintext, otherwise its user sees the loss;
  - P1's offline-move dialog makes this case rarer still.

A move made by one device is two requests, tombstone and block, and **either order is safe**: a
tombstone landing first starts a clock that the block's edge stops well within T.

### 10.4 What it removes from candidate A

- **ST-3b's barrier and M5's bounded wait**, including `last_pull_at`, relay ages and coverage reports.
- **M2**, including the pull lease, the persisted declare journal, cache bypasses and reserved edges.
  Nothing needs declaring at ingest, because references arrive with their writes.
- **M3's delete-side check.** Clients no longer call `/sync/content/delete` or `/reclaim` for ordinary
  deletes, so the relay's own sweep is the only freer, and the per-content serialisation reduces to G1's
  transaction.
- **The device-side sweeps** (the delete-driven sweep and CR-5's enumeration) as the ordinary path. They
  remain useful only for legacy populations (G4).
- **Declare-on-move's successor-declare gymnastics and `TombstoneCascades`' in-memory successor record.** The move's
  new block carries its own edge.
- **The writer floor for M2/M4-less clients** becomes a plain protocol gate on the new push shape. A
  legacy push carries no edges, so the relay must not accept it once G2 is armed, or its reference would
  be invisible to the sweep.

**What stays:** M1 (the "Syncing…" banner), M4's resolution and P3's message (now triggered by G3), P1's
offline-move dialog, and the undescribed-duplicate heal.

### 10.5 Costs and open points

- **Wire and House Rules.**
  - `POST /sync/push` gains an edges field.
    - Its **relay protocol** half: a push floor, so no legacy push lands once G2 is armed.
    - Any **sync-service composite** it reshapes owes House Rule 9, independently.
  - For a **standalone** block, the block's `target_entity_id_hash` and the edge's holder are the **same
    wire name** (`SyncObserver` stamps `resolved.wireName`), so pairing them adds no linkage the relay
    could not already join.
  - ⚠⚠ **For a `DeltaBatchPayload` block it does, and that is candidate B's real privacy cost**. A batch's outer target is deliberately null, and every per-delta target hash, property
    id and semantic HLC is inside the ciphertext. Edge mutations carried beside a batch push, each with
    its delta's HLC, would reveal:
    - which content-bearing holders and properties a checkpoint touches;
    - each one's write clock.

    That is linkage the relay cannot make today. The relay already learns `holder → contentId` from the
    separate declares, but not per-write clocks, and not which batch they came with. The options, a
    maintainer decision:
    - **(i) accept it, with a threat-model amendment.** The disclosure is limited to content-bearing
      properties (files and their thumbnails), not every write.
    - **(ii) keep batches opaque** by pushing a batch's content-bearing deltas as standalone blocks,
      which costs the batching those properties had.
    - **(iii) order edges by an opaque per-`(holder, property)` version** that clients derive
      deterministically from the merge, so no HLC crosses the wire. That needs a design of its own.
  - ⚠ **Option (ii) still adds a smaller linkage, and it must be recorded**. Binding
    `(property, contentId)` to the exact standalone push ties **which** content changed to that block's
    visible write clock. Today's separate declare carries no HLC and is bound to no block.
    - The increment is small. The relay already sees each standalone block's target hash and
      `hlc_physical`, and learns `holder → contentId` from the declares, sent right after the upload. So
      it could already correlate the two by timing, just not bindingly.
    - It is new all the same, and the amendment records it rather than claiming none.
  - ⚠ **The cascade list is a second, similar disclosure**. Today `DeleteBlock`
    exposes only the root holder, and descendant holders arrive later in best-effort `/undeclare`
    batches. Under B3 the tombstone carries the **complete descendant list**, binding "these holders were
    one subtree, deleted together" to one write.
    - The increment over today is small: the undeclare batches already reveal the same hashes by
      timing, right after the tombstone.
    - It is new all the same, and B3's amendment records it. The per-reap removals above disclose the
      same membership again, once per device.
  - Either way, a threat-model amendment (House Rule 2), for the deletion authority moving to the
    relay's sweep **and** for that linkage.
  - House Rule 1 holds: the relay reads only edge metadata, never content.
- **Deletion stays deploy-gated.** `retention-quota-model.md` P3 keeps relay-side deletion behind
  a per-plane flag (both ON in production since 2026-10-24), and the relay's existing time-based sweep (`contentGcJob` → `gcSweep`) is armed but
  disabled (`CONTENT_RETENTION_WINDOW_MS = Long.MAX_VALUE`). G2 is a new sweep of the same kind, and
  turning it on is the same explicit deploy-time decision.
- **Storage:** deleted content is held for T after its last reference goes. ⭐ Decided 2026-10-02: the
  held bytes are **not charged** to the user's quota, and T is 24 h (§10.14).
- **Uploads before their metadata.** Chunks put before the row's push carry no edge, so by G4 they are
  never freed. A crash between upload and push leaks them. ⚠ **The emit-time declare is dropped under
  B**. An edge declared early whose row never lands has no live log row and no
  future tombstone, so the clock would never start, and the content would also leave the undescribed
  population the heal cleans up. G4 already protects these uploads without it.
- **An outbox blocked by `content_freed`.** Rejecting the block makes the client resolve before its
  outbox moves on. A resolution with no bytes retires the row locally with a notice (§9 Q2, decided
  2026-10-02).
- **T.** Days, not minutes: long enough for any device that is merely offline over a weekend. It is the
  same trade as W, but measured on one clock and with nothing a device must report.

### 10.6 A against B

| | A — quorum-less device progress | B — grace period at the relay |
|---|---|---|
| Who decides a free | each deleting device, from assembled peer state | the relay, from its own edge table |
| Clocks involved | relay ages + per-device reports | the relay's only |
| A reference can outrun its edge | yes, the source of most review findings | no (G1's transaction) |
| Per-device machinery | lease, declare journal, reservations, coverage, cache rules | none beyond G3's resolution |
| Loss case | not past the tombstone within W, pending ref, no plaintext | pending ref pushed after T, no plaintext |
| Visible loss | M4/P3 | G3 → M4/P3 |
| Wire change | several (ages, coverage, version on push and pull, delete names content) | one (edges on push) + a push floor |
| Stall risk | bounded by W, but new mechanisms keep reopening it | none: T expires on the relay regardless of devices |
| Storage held after delete | ~W | T (24 h, §10.14) |
| Leftover populations (legacy, undescribed) | freed only once described and edge-less | never freed by G2; owned by the heal |
| New relay-visible metadata | per-device pull ages and coverage | per content-bearing write: holder, property, HLC, including inside batches (§10.5, a maintainer choice between i, ii and iii) |

**Recommendation: B.** It replaces a distributed agreement with one transaction and one clock, which is
why A's review rounds do not converge. ⭐ Evidence from B's own reviews (two rounds, 2026-09-29): every
finding so far is a rule **inside the relay's database** (HLC-ordered registers, per-delta stamps, a
tombstone watermark, serialising the sweep with a push, a cascade on the tombstone wire, conditional
physical deletes and a durable delete queue). Each is local to one store and testable there, and none
needs devices to agree. The one finding that is **not** a correctness rule is a real cost: batch
metadata disclosure (§10.5). What B pays for that:
- deleted bytes are kept for T;
- one push-shape change;
- a relay sweep that needs the deploy-time switch-on.

**Owed before choosing:** a check that every writer's push can name its edges (§9.9a's coverage
question, now at push time), and T.

### 10.7 Phases (candidate B, chosen)

| phase | what | touches |
|---|---|---|
| **B0** | Undescribed-duplicate heal: dedupe, backfill v6, then the garbage reclaim, run as a **relay job built from B5's deletion machinery** (the owner-keyed durable queue, the ordered put/free sequence, dry-run first). So the machinery ships early, tested on this one-off, and B5 only adds the automatic trigger | client, then relay |
| **B1** | M1: the stable catch-up state (last completed pull) on `SyncHealthStatus`, the Drive and Control banners. ⭐ **The schema pull fence's surface shipped on its own (2026-10-29)**, with no schema change: Control's home banner, the desktop tray's `NEEDS_UPDATE`, OS notifications, a desktop log (`core-schema-skew-surface.md` §8.8). ⚠ When M1's "Syncing…" lands, the fence outranks it, in both UIs | sync-service (HR 9 bump), both UIs, SwiftUI twin |
| **B2** | **Audit before building:** (a) every content-bearing writer can name its edges at push time (§9.9a coverage, now at push); (b) every content-bearing property converges by LWW on clients, or a semantic merge re-emits a higher-HLC block (§10.2 G1); (c) choose T. ⭐ **(a) and (b) are done and NOT clean** (2026-10-01): [`content-plane-cr2-b2-audit.md`](content-plane-cr2-b2-audit.md), eleven gaps and eight questions that B3's wire spec has to answer first | analysis + tests, no wire change |
| **B3a** | ⭐ **The edge set is declared by the schema (§10.11, decided 2026-10-01).** `ContentReferencing` gains `CONTENT_ID` and per-property `Primary` / `Derived` / `regenerable` declarations; one pure `contentEdgesOf(schema, payload)` returns the complete set, `none` included. `FileBytesPropertyWriter` and `SchemaDrivenContentWalk` switch to it, pinned by an equivalence test against today's `resolveContentId`. No wire change. Answers B2 audit Q1 | core, core-common, file-service(-proxy), health-service, sync-service-proxy |
| **B3b** | ⭐ **Client-local prerequisites (split out 2026-10-01), no wire change.** The persisted per-child deletion watermark, applied on ingest (§10.12 Q4, client half; closes audit gap 11, a divergence that exists **today**, audit §3.4 point 3; at the folder tombstone's HLC, §10.12's revised Q4, accepted 2026-10-02); the delete cascade persisted with its pending tombstone in a sidecar keyed by `blockId` (audit W5), so a restart no longer loses it; `OrphanedFileSweeper` recording its removals as `DeletedFolderSubtreeReaper` does (audit W6); the Q8 tests (self-echo convergence, gap 11) | sync-service-proxy, file-service-proxy |
| **B3c / B3d** | ⭐ **B3 proper, per [`content-plane-cr2-b3-wire-spec.md`](content-plane-cr2-b3-wire-spec.md) (v0.2, decided 2026-10-02), in two parallel halves** (split 2026-10-01). ⭐ **B3d (client) built 2026-10-02**: edges on the push from the payload (a `PendingBlockEdges` sidecar), Q7's standalone decision before the write, the cascade read from B3b's store at push time, a durable reap outbox to `/sync/content/reap`, and the edge declares, undeclares and declare-on-move's successor removed; as-built and deviations in the wire spec §10. ⭐ **B3c (relay) built 2026-10-02**: as-built in the wire spec §10. The two halves only work together. ⚠⚠ **Decided 2026-10-02: a hard cutover with a relay reset** (pre-release: test data only). The migration below — UNSTAMPED edges, the re-stamp pass, dual-write — is **not built**: no edge predates B3 on the reset relay, and the edge-form declare and undeclare are deleted in B3 (wire spec §0). The paragraph is kept as what a post-release change of this kind would need. Original text: **B3c**, the relay: edge records with the tombstone watermark, the unstamped-edge migration, the push floor, `referenceId` optional on the describe (§10.12 Q3); **B3d**, the client: edges on push from `contentEdgesOf` (B3a), standalone push by row (§10.12 Q7), the cascade and conditional reap removals on the wire (B3b's records), the re-stamp pass. In detail: **Migration first:** every existing `content_edge` row starts **UNSTAMPED**, because it has only `declared_at` and no originating HLC. A tombstone **never** removes an unstamped edge (an upgraded client draining a queued pre-B3 tombstone after another device's newer recreate would otherwise drop a live edge). A **re-stamp pass** (the v6 backfill's shape) re-declares every live `(holder, property)` with its row's current HLC. ⚠ **One device with complete-history coverage suffices**: it holds the whole live set. Requiring every device would bring back the barrier. Then the wire and relay registers: content-bearing deltas pushed as **standalone** blocks (option ii) carrying `(holder, property, contentId \| none, HLC)` for every content-bearing property they touch; tombstones carry the cascade; the relay applies edges as HLC-ordered LWW registers with a per-holder tombstone watermark, in the push's transaction; the protocol version on **every** push. **Dual-write:** clients keep today's declares and undeclares until B6 | relay + wire, sync-service-proxy, threat model (House Rule 2), HR 9 where a composite reshapes |
| **B4** | G3 + M4 + P3: the relay refuses `content_freed`; the client re-uploads and **revives** (the atomic transition out of the freed state) or shows "deleted on another device"; an outbox blocked by a refused block resolves first; ⭐ the client drain holds a removal of content C until every queued add naming C is acknowledged (adds before removes, §10.14), after which T drops from 24 h to minutes | relay, client, both UIs |
| **B5** | **Seed the clock at arming**: every described content with **zero** edges at that moment (ST-3b's stalled backlog, today charged to quota) gets `unreferenced_since = arming time`, so it enters T rather than never seeing a one-to-zero transition. Unstamped edges left after the re-stamp pass (holders no device has) are removed at arming and enter T the same way. Then G2, **dry-run first:** `unreferenced_since`, the durable `content_free_queue`, the sweep and push serialisation, conditional physical deletes. It runs logging what it **would** free until the maintainer arms it, as a deploy-time switch like `retention-quota-model.md` P3 | relay, deploy |
| **B6** | ⭐ **Spec: [`content-plane-cr2-b6-spec.md`](content-plane-cr2-b6-spec.md); D1–D4 decided 2026-10-04: all of B6 in one change, one deploy, the relay deployed `armed`** (so the dry-run comparison against the device sweeps is knowingly given up), P1 as a refusal, the deferred-move machinery removed. Original text: Retire what B replaces: the device-side delete and enumeration sweeps for the ordinary path, ST-3b's barrier for content, declare-on-move's successor declares and `TombstoneCascades`' successor record, the separate declare and undeclare as authority. ⭐ **And the reference roots with their handles (maintainer, 2026-10-01):** remove `File` / `SharedFile` / `Workout.ReferenceRoot`, `ContentReferencing.REFERENCE_ROOT` (⚠ the requirement, **not** the trait, which keeps §10.11's `CONTENT_ID` and derivations), their minting and move-preservation, `ReferenceHandle` and `content_reference`, with an HR 9 bump and `SchemaMigration` for file-service and health-service, in lockstep with apex and the relay. They are **not** moved into a sync-owned map (§6e's declined alternative, which would also need persisted `TMap` support). If a stable logical-file id is ever needed, it comes back as its own decision. Plus P1's offline-move dialog and §9 Q5. **Enable the local orphaned-bytes sweep (§10.9), which may ship earlier: it is independent** | sync-service-proxy, file-service, Drive UI |

- ⚠⚠ **Arming G2 (B5) is the last step, and six things gate it:** ⭐ *(2026-10-02: with B3's hard cutover and relay reset, the push floor is in place from B3c on, the side routes are deleted rather than fenced, and there is no re-stamp pass, so three of the six below are already met by construction; see the wire spec §0. B4 shipped, the legacy FREE routes closed, and B2's audit still gate it.)*
  - **The push floor is raised**: the relay refuses edge-less pushes (426), or a legacy writer's
    reference would be invisible to the sweep.
    - ⚠ This is a **one-time switch, not a barrier**. It does not wait for every device to update: an
      old or offline device simply cannot push until it does. When it does, its pending references go
      through G1, or get `content_freed` if older than T.
    - Once armed, **no device ever delays a free**. That is the property the old barrier lacked.
  - **The legacy FREE routes are closed in the same step**. While B6 has not run,
    the device-side sweeps still call `/sync/content/delete` and `/reclaim`. Under B, a device that
    ingested a tombstone but has not yet pushed its reference has **no edge yet**, so a legacy sweep
    would delete the content at once: without waiting T, without `content_freed`, and without the queue
    record REVIVE needs. So arming G2 and refusing those two routes (for G2-governed content, or
    outright) happen **in the same deploy**; the client sweeps are removed later in B6.
  - **The side-route edge mutations are refused outright** (⭐ decided and built in
    B3, 2026-10-02). A delayed `/sync/content/undeclare` arriving after a newer B3 edge push would erase
    the newer register, and G2 would then free live content; a `/declare` edge form would mint an
    unstamped edge no tombstone can remove (§10.10). ⚠⚠ **So both are refused for every holder,
    unconditionally**: the edge form of `/declare` answers 400 before any holder or register logic, and
    `/undeclare` is deleted (B3 wire spec §0). The weaker per-register fence ("ignore them only for
    holders that already have a B3 register") was an alternative and is **not** the decision; it would
    leave that hazard open. Do not reintroduce it.
  - B4 has shipped, so a loss is shown rather than silent.
  - **The re-stamp pass has completed on one device with complete-history coverage**, after the push
    floor was raised. Not on every device: a lost or fenced device would block it for ever, which is
    the barrier again. Any unstamped edge left then names a holder no caught-up device has. It is removed
    at arming and enters T, and a device that returns holding a reference to it meets G3.
  - B2's audit came back clean.
- ⚠ **Until G2 is armed, nothing changes for deletion.** The device-side sweeps keep running with
  today's barrier. B3's registers only accumulate, and B5's dry-run shows what G2 would free, to compare
  with what the sweeps free.
- ⭐ The delete-vs-move system test (both orderings, **bytes** included) is owed before B5 is
  armed, rewritten for B: a move pushed within T keeps its content, one pushed after T gets
  `content_freed`.

### 10.8 What B supersedes in `content-plane-refcount.md` (C′, N0–N7)

⚠⚠ **C′ (§6e there) was also "selected, unbuilt", and parts of it contradict B**.
C′ moves deletion authority to **the client's delete naming its orphan** (N4), and **retires
`content_edge`** (N6) in favour of client-described **handles** (`content_reference`, N1). Following both
plans would leave B without its reference predicate, or rebuild it on handles maintained outside the
push transaction, which recreates the visibility gap B exists to remove. Resolved as follows:

| C′ phase | under B |
|---|---|
| N0 age window, N2 `/contents`, N1c content-keyed journal | **kept**: independent of who frees |
| N1 handles (`content_reference`) and the reference roots | **no longer an authority.** G1's per-`(holder, property)` registers do what a handle was for, one reference per content-bearing property, but inside the push transaction. ⭐ **Decided 2026-10-01: retired in B6**, roots included. They are not moved into a sync-owned map. ✅ **Retired (B6, 2026-10-04):** the roots are gone from `File`/`SharedFile`/`Workout` (file-service v16 and health-service v4 strip the stored slots), the describe sends no `referenceId` (the relay still *accepts* one and ignores it), `PendingContentEmit.referenceId` is dropped (sync-service v9), and relay migration v25 drops `content_reference` — `content-plane-cr2-b6-spec.md` §6 |
| N3 (D3): the move fence, the repair pin, the writer floor | the fence and the pin go with the deferred-move machinery (§9 Q5); the writer floor becomes B3's push floor. ✅ **Removed (B6 D4)**; an offline move of content the device does not hold is refused with `MoveNeedsLocalBytesError` instead (D3) |
| N4 the authority flip (the client's delete names the candidate) | **superseded by G2**: the relay's own sweep is the only freer |
| N5 remove ST-3b | **= B6** |
| N6 retire `content_edge` | **superseded.** `content_edge` **stays**, as G1's register table: gaining the HLC, the tombstone watermark and `unreferenced_since`, written only by G1 |
| N7 CR-6, the undescribed remainder | **= B0's operational reclaim**; G4 guarantees G2 never touches it |

### 10.9 Local bytes: hide, never delete the row; drop plaintext once nothing local names it

This is the eventual-consistency side, per device (maintainer, 2026-09-29).
- **Missing bytes are hidden, never deleted.** M1's reader rule applies:
  - **not caught up:** a placeholder, and "Syncing…";
  - **caught up:** normally the tombstone has arrived and the row is gone;
  - **caught up, row still live, bytes absent:** under B's G1, a live row always has an edge, so its
    content is never freed. What remains is "the uploading device has not finished", shown as waiting,
    then "unavailable" (M4).

  ⚠⚠ A device must **never push a tombstone because bytes are missing**. That would propagate a delete
  to every device, and could remove the only live reference to a file that is merely still uploading.
  Rows leave through the deleting device's tombstone, arriving by the log.
- **When local plaintext goes:**
  - **The deleting device** drops its bytes at delete time, as today. An undo window is §9 Q4.
  - **Every other device** drops them when it **applies** the tombstone: `OrphanedBytesCandidates`
    records the path, and `OrphanedBytesSweep` removes the bytes once no local row names them and they
    have been untouched for `OrphanedBytesPolicy.DEFAULT_SETTLE_MS` (one hour). Every uncertain answer
    is a keep.
    - ⚠ **That sweep is built but disabled** (`ORPHANED_BYTES_SWEEP_ENABLED = false`), so today a delete
      frees the deleting device's disk and no one else's (tech-debt).
    - Under B it is safe to enable. The only reason to keep plaintext is a **pending local write** that
      still names the content (so it can revive after `content_freed`), and such a write is a local row
      naming it, which the policy already keeps.
- **A safety net for free:** the relay holds the bytes for T after the last edge goes. A later "restore
  a deleted file" feature could revive within T, without any device having kept plaintext.

### 10.10 Open review findings, settled in their phases

The design review ran for many rounds. Each found a narrower, real gap, and was fixed above.
The maintainer merged the document on 2026-09-30 with the findings still open **recorded here**. Each
belongs to a phase that is built and reviewed as its own change, against real code:

| finding | phase | direction |
|---|---|---|
| ⚠⚠ **A reaper's removal HLC is not causally visible to a later recreate.** `TombstoneRegistry.recordLocalDelete` keeps it in a local map, and `onDataDeleted` emits no block, so a device recreating the child never observes it. With clock skew the recreate can carry a **lower** HLC: clients keep the row, while the relay's register rejects its edge, and G2 would free live content after T. | B3 | Publish the reap's ordering through the log, as a block every later writer ingests before it can recreate. Alternatively, key the removal to an ordering token that any recreate must observe. |
| ⚠⚠ **A walk cannot tell a small realm from an unreadable one** (found 2026-10-01, after B0). `SchemaDrivenContentWalk.enumerate` skips, with a bare `continue`, a row that fails to decode, a row with no `DataId`, and a row whose `resolveContentId` **throws**, and still reports `walkComplete = true`. So the backfill latches `recorded complete` with nothing counted as skipped. The desktop's pass declared 492 edges where about 8,000 were expected, and the log cannot say which of the two it was. B3's re-stamp pass has this walk's shape, and its "one complete-history device suffices" rests on the walk being whole. | ✅ **counted** (2026-10-01); the latch question stays with B3 | Done: the line carries `from N rows`, `ROWS SKIPPED UNDECLARED` and `PROPERTIES SKIPPED UNDECLARED`, and the pass re-armed as `v7-counted-walk`. **Still open:** whether a decode failure or a throw may latch, to be decided on the first device numbers. ⚠ Not every skip is a failure: a property with no content id answers blank by design, and counting that against completeness made the marker unlatchable once already (`ContentDeclarationBackfill.run`). |
| ⚠⚠ **An unstamped edge created AFTER G2 is armed can never be removed**. B3 rules that a tombstone never removes an unstamped edge, and B5 removes the unstamped edges that exist at arming. Neither covers one created later through the `/declare` edge form. | ✅ **closed by B3** | The `/declare` edge form is refused for every holder, unconditionally, and `/undeclare` is deleted (§10.7's side-route gate, B3 wire spec §0). No path mints an edge outside `/sync/push`. |
| ⚠ **Queue acknowledgement ABA across free → revive → free.** A stale worker, acknowledging by `(owner_id, ref)` only, can remove a newer generation's queue entry. | B5 | Acknowledge by compare-and-delete on the observed free generation, or put the generation in the queue key. (B0's one-off job has no revive path, so it does not meet this.) |

### 10.11 The edge set is declared by the schema: `ContentReferencing`, repurposed (decided 2026-10-01)

⭐ **Maintainer, 2026-10-01**, answering B2 audit Q1 (`content-plane-cr2-b2-audit.md` §4 iii) and
widening it: *every* content a row references must be freeable, not only thumbnails. So which content a
row references becomes a schema declaration with one platform function behind it, and not
per-service code. This is phase **B3a** (§10.7), and it lands before B3.

**Why it is needed.** G1's invariant ("the edge set equals what the log's live rows name") makes the
relay's registers exactly as good as the edges each push carries. Today three places work those
edges out, and none of them is generic:
- **The derivation is hidden in one service.** "A `File`'s five thumbnail rungs are
  `sha256("thumbnail:$contentId:$class")`" exists only in
  `FileBytesPropertyWriter.derivedThumbnailContentId`. The schema cannot say it, so a block builder in
  `:sync-service-proxy` cannot know it without naming `File`.
- **It reads the stored row, not the payload.** `ExternalPropertyWriter.resolveContentId` reads the
  database (`FileBytesPropertyWriter`, `WorkoutStreamsPropertyWriter`), while blocks are built later on
  the async emit consumer. The stored row can be a later save, or already retired by a move (audit §2.1).
- **`SchemaDrivenContentWalk` asks the same port** per row per property. The backfill runs on it,
  and B3's re-stamp pass would too.

**The design.**
- **`ContentReferencing` changes job.** It stops naming where the handle seed lives
  (`REFERENCE_ROOT`, retired in B6) and declares *"this type's rows reference content, and here is how
  to compute which"*.
  - **On the type:** a value requirement `ContentReferencing.CONTENT_ID`, bound through
    `fulfilValueRequirement` exactly as `REFERENCE_ROOT` is today (`File.ContentId`,
    `Workout.ContentId`). The content plane still never names `File` or `Workout`.
  - **On each `Syncable(OnDemand)` property:** how its content id derives from that slot. This is
    schema data (a constant family plus fields, like `Sliceable`'s `SliceKey`), never a lambda, so it is
    the same on JVM, iOS and JS, and `SchemaShapeBaseline` pins it.
    - `Primary`: the slot's value (`File.Data`, `Workout.Streams`).
    - `Derived(namespace, qualifier)`: a hash of the slot's value (the five `File.Thumbnail*` rungs).
    - `regenerable`: content that can be rebuilt from the primary's bytes (every thumbnail rung,
      MOTION included). It is never the subject of `content_freed` (G3) and never part of a REVIVE's
      ref check: a freed rung is minted again on view.
      - ⚠⚠ **How a regenerable id leaves FREED**. A derived id is deterministic, so a
        file reference that returns after T names rung ids that may already be FREED. Exempting them
        from G3 lets the row's push land, but the FREED mark would stay, and Q3's rule ("a describe for
        a freed id is refused except as a REVIVE") would then refuse the rung's next mint for ever. So:
        - each edge on the wire carries its `regenerable` flag;
        - **a push whose regenerable edge names a FREED id clears that id's FREED mark** in the push's
          own transaction (serialised with the sweep, like every G2 step), leaving it PENDING: an edge,
          no `content_ref`. Its old refs stay in `content_free_queue`, and the conditional physical
          delete (`put_seq < freed_seq`) protects any blob rewritten since;
        - the next mint then describes it normally, because the id is no longer FREED.

        A non-regenerable id still leaves FREED only by REVIVE.
- **One pure function:** `contentEdgesOf(schema, payload) → [(property, contentId | none)]`.
  - It reads **only the payload in hand**, never the store.
  - It returns the **complete** set for the type, `none` included. That is G1's "removals are
    explicit", for free.
  - The trait and constants live in `:core`, and the function in `:core-common`, next to `sha256`.
- **Every consumer computes edges with it, and only with it:**
  - B3's block builder (fixes audit §2.1);
  - B3's re-stamp pass and the declaration backfill, which still read rows but stop calling the
    port per property;
  - `FileBytesPropertyWriter`, for the id it emits under, so upload and push cannot disagree.

**What it settles.**
- **Q1, for every type:** a block names every declared edge unconditionally. Late mints (audit W3) are
  covered because the row's block already named them. An edge to content never minted does nothing:
  when it goes to zero, the sweep finds no `content_ref` to delete.
- **The rung-overwrite leak** (audit W2): every push carries all six, so a rung the new content lacks
  is replaced by `none`.
- **A summary-only workout save** (audit W8) no longer wipes anything: a bare composite keeps the stored
  content slots (§10.12 Q5), so the payload still names the streams.
- **A future content-bearing type or rung** is one schema declaration, with no sync-layer change.

**Constraints.**
- ⚠⚠ **A `Derived` declaration must reproduce today's ids byte for byte.** A rung's content id feeds
  `manifestContentRef`, so a changed derivation readdresses every stored thumbnail, and the existing
  manifests become unreachable. The thumbnail form is exactly `sha256("thumbnail:$fileContentId:$class")`
  with today's class strings. B3a's equivalence test (below) is what guards it.
- ⚠ **Adding the trait reshapes no stored row and adds no wire vocabulary**, so it moves no version.
  Traits are code-side schema metadata, never serialised (`Workout.kt`'s trait list says so), and
  neither `ContentDerivation` nor its `ContentDerivationKind` constants are registered schemas: like
  `Sliceable` and `SliceKey`, they never reach the baseline, the value or constant factories, or a
  peer. The slot is an existing value property. ⚠ An earlier draft of this line said the constants
  would fail `SchemaShapeBaselineEnforcementTest`; that was wrong, and the gate that ran B3a passed it
  with the baseline unchanged. **Were these constants ever
  registered** (in a module's constant factory, or carried in a payload), that would be a wire change
  owing HR 9. B6's HR 9 bump, which removes `ReferenceRoot`, is unchanged.
- ⚠ **B6 keeps the trait.** B6 removes the `REFERENCE_ROOT` requirement and the root properties, not
  `ContentReferencing`, which by then holds only `CONTENT_ID` and the derivations.
- **`SharedFile` does not declare it yet.** Its content id is a positional composite field, not a
  value property, and its `DATA_PROPERTY_ID` is not a registered relation. It joins only once shared
  realms have a tombstone path (audit Q6). Until then it must emit **no** edges, never a partial set.
- **No new disclosure.** The relay could already link the derived ids: all six edges arrive on one
  holder in one push, and the backfill already declares all six. B3's threat-model amendment records it
  all the same.

**B3a, as a change.** No wire change and no relay change.
1. Add `CONTENT_ID` and the derivation declarations, and bind them for `File` and `Workout`.
2. Add `contentEdgesOf`.
3. Switch `FileBytesPropertyWriter.resolveContentId` and `SchemaDrivenContentWalk` to it.
4. ⭐ Add an **equivalence test**: for every registered OnDemand property on every type, `contentEdgesOf`
   on a row equals what today's `resolveContentId` answers for the same stored row, thumbnails
   included. It pins the derivation before anything new depends on it.

During the transition the trait carries both requirements.

**As built (B3a, 2026-10-01).**
- **The trait:** `ContentDerivation` (`:core`, `org.tenshu.core.trait`), declared with the
  `PrimaryContent()` / `DerivedContent(namespace, qualifier)` DSL. Its kind is a `ContentDerivationKind`
  constant. The requirement `ContentReferencing.CONTENT_ID` is bound for `File` and `Workout`, and the
  thumbnail namespace is `File`'s `THUMBNAIL_CONTENT_NAMESPACE`.
- **The function:** `ContentDeclarations.of(registry).edgesOf(row)` (`:core-common`,
  `org.tenshu.core.common.content`). Build it once per pass; `contentEdgesOf(registry, row)` is the
  one-row shorthand. The single derivation is `deriveContentId`.
- **Who uses it:** `SchemaDrivenContentWalk` and `declarableEntriesOf`. The two ports,
  `FileBytesPropertyWriter.resolveContentId` and `WorkoutStreamsPropertyWriter.resolveContentId`, use
  `contentEdgeOf(registry, row, propertyId)`, the one-property path over the same declaration. They are
  asked per row per property on read paths (a thumbnail grid), where walking every registered relation
  per call would be waste. `derivedThumbnailContentId` now delegates to `deriveContentId`.
- **Pinned by** `ContentEdgesEquivalenceTest`: golden thumbnail ids computed outside Kotlin, every
  OnDemand property declared, and the port agreeing with the edges.
- ⚠⚠ **`ExecutionContext.resolveProperty` reads the STORE, not the row it is handed.** For a stored
  value property, `resolveStaticProperty` re-reads the row by its `DataId`. So `edgesOf` reads the slot
  off the payload's own property map (`Entity.getProperty`), and a caller holding only a `DataId` reads
  the row with `getData(dataId, asEntity = true)`. A row decoded **without** `asEntity` is a bare
  composite with no property map, and it answers `none` for everything. The walk decodes with
  `asEntity = true` for that reason.
- **What is unchanged:** the walk still requires a registered port before it walks. ⭐ B3d (2026-10-02):
  the edges a push carries are `edgesOf` the payload on the emit consumer, never the port's store read
  (wire spec §10).
- ⚠ **`edgesOf` answers `null` ("cannot say")** when a content-bearing property lacks its
  `ContentDerivation`, or the type binds no `CONTENT_ID`. The walk counts that per property as
  unresolved. A `Folder` resolves `File`'s declarations through the parent chain and answers `none` for
  all six.

**As built (B3b, part, 2026-10-02).**
- **The persisted cascade (audit W5):** `PendingTombstoneCascade` (`:sync-service-common`) is a
  device-local row per pending tombstone, keyed by `blockId`, in
  `SyncService.Collections.PENDING_TOMBSTONE_CASCADES`. `TombstoneCascades(store)` writes it before
  the block is pushable, reads it back on first ask after a restart, and drops it in `forget`.
  Production wires `DatabaseTombstoneCascadeStore`.
  - A new type, so no `SyncService` version moved. The baseline gained exactly one line, at v8.
  - ⚠ Its two fields are positional and persisted: any change to them is a reshape and owes HR 9.
- **Q8 is pinned** by `SelfEchoConvergenceSystemTest`: two partitioned devices overwrite one file, and
  both end on the higher-HLC write.
- **The reap watermark (§10.12 Q4 revised, audit gap 11 and W6), as built:**
  - `ReapWatermark` (`:sync-service-common`) is a device-local row `(wire name → T)`, a new type, so
    there is no version bump; the baseline gains one line.
  - `TombstoneRegistry` implements `SubtreeReapOrdering`: `deletionPointOf` (a real tombstone, else
    the reap point), `versionOf` (the later of the last ingested block and the own last local write),
    and `recordReap`. `tombstoneHlcFor` now counts the watermark, so the ingest check refuses a block
    at or below T after a restart too.
  - A reaped child's delete **skips** the in-memory "now" marker, which would refuse blocks in
    (T, now] on this device only.
    - ⚠⚠ The skip must survive **repeated** delete notifications. `ReapOrderingTest` measured every
      delete reaching the registry twice, and a skip consumed by the first left the second to stamp
      the marker anyway. A reaped wire name therefore stays marked until the entity is saved again
      (a recreate).
  - `tombstoneHlcFor` caches the later of tombstone and watermark once either exists, so a resolved
    point is still read-free (`aLookupStillCostsOneReadAndCachesIt`). A lookup that misses both
    now costs two point reads instead of one.
  - The ordering reaches `:file-service-proxy` as `TombstoneEmitter.reapOrdering`, which `SyncObserver`
    serves with its registry. That needs no new wiring, because the emitter's holder already reaches
    `initFileService` on desktop, Android and iOS.
  - ⚠ The JS runtime and the system-test harness call `initFileService` **without** the holder, so
    there the reaper keeps the pre-B3b cascade.
  - `DeletedFolderSubtreeReaper` reaps children with version ≤ T (or none), records each one's T
    first, keeps newer ones, and re-arms the sweep when it kept any. A subfolder reaped in the cascade
    passes the same T down.
  - The deleting device's **own** cascade is recorded at its tombstone's HLC in
    `SyncObserver.enqueueTombstone` (`recordReapPoint`), which is the same T its peers reap at.
  - `OrphanedFileSweeper` removes an orphan with a tombstone first when the device is in a realm.
  - Pinned by `ReapOrderingTest` (reap ≤ T and keep > T, the placeholder case, acceptance above T in
    the same session, refusal ≤ T after a restart) and three new `OrphanedFileSweeperTest` cases.
  - ⚠ Watermarks are never pruned, like `PersistedTombstone`: one small row per reaped entity.

**Why `Sliceable` stays separate.** It answers a different question: how a realm-wide collection is cut
for a joiner's snapshot. It knows nothing about content, and only `File` and `Moment` declare it
(`Moment` has no content). Its own KDoc rejects folding it into `Syncable` because the consumers and
hot paths differ, and the same holds here. It meets content in two places, which become two small
items (§12.3):
- ⚠ **A provisional row from a slice snapshot must not be moved before it has caught up** (audit §3.5,
  W14). Edges are computed only on push, so the snapshot row itself is harmless. A move of it, though,
  pushes a new holder naming a possibly stale content id at a fresh HLC, and every client follows it.
  Guard: hold the move until the row has `SyncMetadata`. ✅ Confirmed unblocked by a test, then guarded
  (2026-10-03, `content-plane-cr2-b5-spec.md` §7).
- **Snapshot blobs have no edges** (audit W10). They are overwritten in place at
  `RelationSliceSnapshot.ref(realmId, relationId, sliceKey)`, so only slices that no longer exist
  (a deleted folder's) leak. They do not get edges: their holder would have no live log row, which
  breaks G1's invariant. The snapshot writer, which knows the live slice set, deletes dead slices
  itself. Until then they are a G4 leak.

### 10.12 The B2 audit's remaining questions, answered (2026-10-01)

⭐ **Maintainer, 2026-10-01.** These are the answers to `content-plane-cr2-b2-audit.md` §4 (iii) Q2–Q8
(Q1 is §10.11). B3's wire spec builds on them. Q5 was answered on 2026-10-02.

**Q2: G3 refuses only content marked freed, and the in-between state has a name.**
- G3's check is "is this content id marked **freed**", never "are its bytes present". A row's block is
  pushed **before** its bytes upload (audit §0.1), and an upload can wait without bound behind the
  metered gate, a `507` or a locked realm. A presence check would refuse nearly every new upload.
- A content id moves through:
  - **PENDING**: has an edge, not described. Normal, and allowed to last indefinitely. It starts no clock.
  - **LIVE**: described, and has edges.
  - **UNREFERENCED**: zero edges, `unreferenced_since` set, waiting out T.
  - **FREED**: marked freed, refs in `content_free_queue`.

  Content that never had an edge stays outside the cycle (G4).
- ⚠ **A free marks the content id freed even when it has no `content_ref` rows.** That is PENDING content
  whose last edge went before its upload landed. Without the mark, the late upload's describe would be
  accepted as new content. With it, the describe is refused (only a REVIVE clears the freed state), and
  the client drops the upload.

**Q3: the ref set comes from the `contents` describe, sent by the uploader after its chunks land.**
- A describe carries no edge, so it never starts G2's clock, and it can arrive before or after the row's
  push. Holding the row's push until the upload lands (ADR 0015's order reversed) and naming the content
  id on every chunk `PUT` were both rejected.
- ⚠ **B3 makes `referenceId` optional on the relay.** `ContentDeclareRequest` requires it today, and B6
  retires handles, so without this B6 breaks the only describe path.
- ⚠ **On iOS the describe waits for the background upload's completion**, never for the staging call:
  `BackgroundUrlSessionContentBlobStore.putChunk` returns once `nsurlsessiond` holds the chunk, not once
  the relay does.
- A describe for an id marked freed is refused, except as part of a REVIVE (Q2).

**Q4: a reap removal is a conditional removal at the HLC of what was reaped.**
- The removal reads "remove this edge only if its stamp is **at or below h**". h is the later of:
  - the folder tombstone's HLC, and
  - the reaped row's own last known HLC on the reaping device.

  It removes exactly the version the reaper saw. A recreate the reaper never saw carries a higher HLC
  and survives on the clients and at the relay alike. A placeholder row with no HLC falls back to the
  folder tombstone's. This replaces §10.2's "stamped with the reaper's own local-delete HLC" and settles
  §10.10's first finding.
- ⭐ **The client persists the same `(holder → h)`** as a per-child deletion watermark and refuses
  incoming blocks at or below it. That closes audit gap 11: a reaped child no longer comes back,
  without its edge, after a restart. So the client and the relay share one ordering point.
- `OrphanedFileSweeper` sends the same removal as `DeletedFolderSubtreeReaper` (audit W6).

⚠⚠ **Revised 2026-10-02 after review; ⭐ accepted by the maintainer the same day. The rule
above is unsafe and superseded — build only on the revision below.**
- **The defect:** h differs per device. Each reaper takes the child's last version *it* saw, so device
  A may reap at h₁ and device B at h₂ > h₁. A late block v with h₁ < v ≤ h₂ is then accepted on A and
  refused on B. B's removal ("≤ h₂") also strips v's edge at the relay while A holds v as a live row,
  and G2 frees it after T. "A recreate the reaper never saw carries a higher HLC" is false: an offline
  writer with a slow clock can produce one below h.
- **The proposed revision: one ordering point per deleted folder, the same on every device and at
  the relay, namely the folder tombstone's HLC T.**
  - **The reaper reaps only descendants whose version is ≤ T** (a placeholder with no HLC counts as
    ≤ T), and **keeps a newer one**. That is a behaviour change: today `DeletedFolderSubtreeReaper`
    removes every descendant regardless of HLC (§10.2). The kept child is a live row whose parent is
    gone. It is never deleted silently: if `OrphanedFileSweeper` removes it, that is a real delete
    with a tombstone. Where Drive shows such a row is an open UI question.
  - **The persisted client watermark is `(holder → T)`**: a block for a reaped child at or below T is
    refused, and one above T is accepted, on every device alike.
  - **The relay applies the cascade and every reap removal as "≤ T"**, the same comparison.
  - **The unseen slow-clock recreate** (below T) is then refused **everywhere**: on every client, the
    writer included (it reaps it on applying the tombstone), and at the relay. That is LWW's own
    outcome for a concurrent delete beating a skewed write, the same on every party. It is the accepted
    loss mode, not a silent divergence.
  - A child written above T survives everywhere, row and edge, so nothing leaks either.
- The per-reap removal becomes redundant with the cascade wherever the deleter's list was complete.
  It stays for children the deleter never saw, at the same T.
- ⭐ **The final clear is the sweep** (maintainer question, 2026-10-02). A kept child is an orphan, so
  the reaper re-arms `OrphanedFileSweeper`, and the sweep removes it with a **real tombstone** (a
  device in no realm still deletes locally; a refused tombstone keeps the row until the next pass). An
  edit made after the folder's deletion is therefore deleted in the end, consistently everywhere.
  Moving such rows to a "recovered" folder instead is an open UI choice, not taken.

**Q5: a bare composite never clears content (decided 2026-10-02, option d; built).**
- **The defect was real and unintended.** A save replaces the whole stored entry, and a bare composite
  carries no property map (`DbEntryMapper`; all three object stores replace). So `RecordWorkout` without
  streams, `RecordImportedHealth` and the timeline importer wiped `Workout.ContentId` and
  `ReferenceRoot` over a row that had streams. Health Connect triggers it: the same `metadata.id` comes
  back summary-only whenever route consent is missing or revoked. `RecordWorkoutImplementation`'s own
  comment already said the root must survive a re-record; the summary branch never read the row.
- **Under B the wipe would be data loss, not a leak:** the row names `(Streams, none)`, the edge goes,
  and the streams are freed after T for every device that has not fetched them.
- **The fix is generic, not per writer.** `ExecutionContext.saveData`, on any non-ingest save of a bare
  composite whose type declares content (B3a's `ContentDeclarations`), carries the stored row's
  `ContentReferencing.CONTENT_ID` and `REFERENCE_ROOT` slots into the save
  (`StoredContentReferences`). The fields still update.
  - ⚠ **An `Entity` save keeps replace semantics**, so a deliberate clear stays expressible.
  - ⚠ **Same type only.** `Folder` and `File` share a key space; a folder replacing a file must clear it.
  - ⚠ **Only the two declared slots**, never other properties (relations may rely on the replace).
  - ⚠ **Not on sync ingest.** A peer's row is applied as written; carrying over is the writer's job, or
    this device's row and the relay's edges would disagree.
  - Cost: an index lookup per bare save of such a type, and a read only when the row exists.
- Options not taken: per-writer preservation in `RecordWorkout`/`RecordImportedHealth` (a), skipping the
  re-record in the importers (b), and treating the wipe as intended (c). Revoking a source's route
  consent does not require deleting already imported routes; if that is ever wanted, it is an explicit
  operation, not a side effect of a re-sync.
- Pinned by `WorkoutContentIdTest` (summary re-record keeps both slots and the edge; an Entity save
  still clears; a peer's bare row is applied as written; a fresh summary stays bare) and
  `BareCompositeKeepsContentTest` (a second type, and Folder-over-File clears).

**Q6: shared-realm content is out of B's scope, as a G4 population.**
- `SharedFile` emits **no** edges (not a partial set) until it has a registered property and a tombstone
  path. G2 never frees it: it can leak, never be lost.
- ⚠⚠ **B5's arming seed must not reach it.** The seed puts every described content with zero edges into
  T. If `ContentRepair` ever described a shared copy, that copy would match and be freed. Scope the seed
  to personal storage, or prove shared copies are never described.
- ⭐ **Leaving it out also keeps a scaling question open on purpose.** In a realm with hundreds of
  members, the edge count itself does not grow with membership (holders are the realm's `SharedFile`
  rows; `Placement` bears no content). What does grow is "every device that reaps sends a removal"
  (§10.2), which becomes members × devices removals per deleted child, all idempotent. When shared realms
  join, design that fan-out down first, for example by letting only the deleting device's cascade and one
  designated device's reaps count.

**Q7: "content-bearing" is decided per row, by the payload.**
- A delta whose payload names a content id (per §10.11's `contentEdgesOf`) is pushed standalone, so it
  meets G3.
- ⚠⚠ **So is a delta that clears content**. If the row this save replaces named
  content and the payload names none, the save is a clear. Inside an opaque batch the relay would never
  see the `none`, the old edge would survive for ever, and that contradicts G1's complete-edge-set
  invariant. The decision is made **when the save is staged**, on the saving thread, against the
  row it replaces in the local store, which is still readable at that point.
- Only a delta that names none **and** replaces a row that named none (or no row) may stay inside a
  `DeltaBatchPayload`.
- Why this is loss-safe: the danger is a batched row naming **freed** content, which would skip G3, and
  that cannot happen. The one remaining gap is a clear of content this device never ingested (the local
  row predates a peer's write that named content). That leaves the old edge standing: a leak, never a
  loss.
- For Drive bulk uploads, every `File` names content, so it is the same as per type. What it keeps
  batched is the summary rows of Fit and Takeout imports over rows without streams.
- A clear still goes standalone, so a real removal reaches the relay. The accidental one, a summary
  re-record wiping `Workout.ContentId`, no longer happens (Q5).

**Q8: B3 adds a test that pins self-echo convergence (audit §3.2), and a test for gap 11 beside it.**
Clients converge only because each device re-ingests its own blocks: the relay applies no device filter,
and the client has none. Nothing tests that today, and filtering out a device's own blocks is an obvious
"optimisation" someone could add. Fixing the cause instead (variant b) is a follow-up: §10.13.

### 10.13 Follow-up (not a B gate): one ordering record per holder, on both sides

⭐ **Maintainer, 2026-10-01: noted as a potential follow-up, with Q1's declaration as its partner.**
This is audit Q8's variant (b): client last-writer-wins should stop ignoring the device's own
un-echoed writes.

**Today.** `SyncProcessor` compares an incoming block only against the last **ingested** HLC
(`SyncMetadata`). The device's own newer local write is invisible to it, so an older peer block
overwrites that write until the device's echo restores it (audit §3.2 caveat 1). The tombstone path
already reads `LocalWriteMetadata`, and the block path does not. The two local stamps also differ: the
save ticks once and the block ticks again later (audit §3.3).

**What fixing it would bring.**
- **No revert flicker.** A user's edit no longer flips back to an older peer version and then returns.
  The revert also stops triggering side effects (a re-download of the older content, a thumbnail mint).
- **Convergence stops depending on the echo.** A relay-side own-device filter becomes possible, which
  saves each device downloading its own blocks.
- **The client and the relay register agree at every moment**, not only eventually.

**What it would cost.**
- ⚠⚠ **It removes an accidental self-heal.** If a local write is never pushed (a save held while the realm
  is locked, then a process death: audit §2.1 and §3.5), today the next peer block overwrites it and the
  device converges. With the fix, the device keeps its unpushed row for ever. So the fix needs a rule
  that a local stamp counts only while its block is still queued or has been pushed.
- **Per-ingest cost.** Every ingested block would read one more record, on the merge path that
  `Sliceable`'s KDoc names as the hot one.
- **The two stamps have to become one.** The block should reuse the save's stamp. The locked-realm
  re-emit's fresh HLC (`SyncObserver`, the unlock path) has to be re-justified or kept as a deliberate
  exception.

**Standardising it with Q1 and Q4.**
- **Q1 (§10.11)** fixes *what* a row references: `contentEdgesOf(payload)`, on both sides.
- **Q4 (§10.12)** gives the client a persisted per-holder deletion watermark, the twin of the relay's
  tombstone watermark.
- **This follow-up** fixes *which write wins*: one per-holder ordering record. It holds the maximum of
  the last ingested HLC, the device's own queued-or-pushed block HLC and the deletion watermark. A write
  applies iff its HLC is above that record, by the one shared `compareHlc`.

Together they make the client's state for a holder the same shape as the relay's register: a stamp plus
a watermark, and the edges as a pure function of the winning row. That gives a checkable invariant: the
relay's edges for a holder equal `contentEdgesOf` of the client's row. The re-stamp pass, or a
diagnostic, can then assert it rather than assume it.

**When.** After B3, which ships with the echo pinned by Q8's test. Nothing in B depends on it.

---

### 10.14 T, the grace period: 24 hours, not charged (decided 2026-10-02)

⭐ **Maintainer, 2026-10-02.** Answers the B2 audit's (c) and §9 Q6, the quota credit.

- **T = 24 h**, on the relay's clock: one sweep cycle (the relay's sweep cadence is already 24 h, and the
  enumeration minimum blob age is 24 h). Content is therefore freed one to two days after its last edge.
- **Held bytes are not charged.** Content that is UNREFERENCED (zero edges, waiting out T) is excluded
  from the figure the user is billed and shown, so a delete frees quota at once from the user's side.
  The relay carries at most ~a day of deleted bytes; this resolves the quota-credit question. ⚠ Not built yet: the
  exclusion lands with B5, in the figure `/sync/usage` and the quota check compute.
- ⚠ **Notation.** "T" has meant two things on this track. In §10.2–§10.10 and here it is **this
  duration**. In §10.12 Q4, the B3 wire spec and the code (`ReapOrderingTest`'s `T`) it is a
  **removal stamp**: the folder tombstone's HLC that a reap or cascade applies at. They are unrelated;
  read each by its section.

**Why not 30 days.** A long T is a guess at how long devices take to come back, and the longer it gets
the more it approximates the quorum this document removed (§1). The realistic cases a late reference
comes from are:
1. **A device's own writes in flight** (seconds to minutes): a move is a block and a tombstone, and a
   retry, a 402, a lane boundary or a crash can land the old path's removal before the new path's edge.
   This is the frequent case and what T actually covers.
2. **The cross-device race** (§2): rare; P1 refuses an offline move this device could not repair,
   so the late device holds the plaintext and can re-upload.
3. Undo is not one: a trashed file is still a holder, so nothing is freed until the trash is emptied.

**What makes a short T safe is B4, not the timer.** Today a push naming freed content would succeed and
point at missing bytes, because G3's `content_freed` refusal and REVIVE are reserved and unbuilt. ⚠⚠ B4
must therefore land before B5 arms freeing, whatever T is. Re-upload from plaintext partly exists
(`ContentRepair`, the backfill's repair hand-off) but must go through REVIVE once a freed mark exists.

⭐ **Decided (maintainer, 2026-10-02): make case 1 deterministic, then shrink T to minutes.** T = 24 h
is the value until both pieces below are built; then it becomes **minutes**, covering only
push-to-acknowledgement latency. Both pieces are B4 work (row in §10.7 and §12.2).
- **Adds before removes, per content, on the client.** The drain holds a removal (tombstone, cascade,
  reap or `none` edge) for content C until every queued add naming C is acknowledged by the relay. A
  device's own move can then never pass through a zero-edge state, across retries, refusals, lanes or a
  restart (the queues are durable).
- **G3 as the atomic check.** The relay refuses a push naming freed content in the push's own transaction
  (B4), so the device learns at push time and repairs or tells the user (P3). A separate pre-move
  availability check is not enough on its own: a free can land between the check and the push.
- With both, T only covers push-to-acknowledgement latency, so **minutes**. What remains, two devices
  racing at once, is handled by refusal plus re-upload rather than by waiting: more re-uploads in a
  rare case, in exchange for failing early and visibly instead of late and silently.

## 11. Future direction — content-head addressing (design note, 2026-09-30)

**Status:** idea, unscheduled, not part of B's phases. Recorded because it would remove most of what
B keeps and all of what the declaration backfill costs. (Maintainer discussion, 2026-09-30.)

### 11.1 What the backfill actually pays for

The declaration backfill (CR-4d, re-armed as v6 for the undescribed duplicates) does two different jobs:

1. **Edges — "holder H names content C".** These protect C from being freed. Under B they ride on
   the push (G1), so this job disappears after B3's one migration pass.
2. **Descriptions — "content C consists of refs r₁…rₙ".** The relay needs these to delete C's
   **bytes**, because a chunk's key is `blob-<sha256(ciphertext)>`. The list exists only inside C's
   encrypted manifest, so a client must fetch and decrypt **every** manifest to produce it: about 8,100
   sequential `GET`s on the maintainer's realm, 10–15 minutes.

The expensive half exists **only because of deletion**. Content-hash addressing makes "which blobs
belong to C" a secret that only a client can reconstruct.

### 11.2 The idea: address blobs by the content that owns them

Instead of `blob-<sha256(ciphertext)>`, store a content's blobs **under its content id**, as a prefix:

```
blob store:  <contentId>/<gen>/manifest    → the encrypted manifest of that generation
             <contentId>/<gen>/<n>         → chunk n of that generation
             <rootContentId>/<derived>/…   → a file's thumbnail rungs and derived artifacts, nested
relay SQL:   content_head(owner_id, content_id, current_gen, freed_gens, …)
             content_generation(owner_id, content_id, gen, state, last_touched)   state: live | reclaiming
```

⚠⚠ **The head lives in the relay's database, never in the blob store**. Blobs live
in `FileBlobStore` or S3, outside the SQLite transaction G1 commits in. A pointer stored as a blob
could not change atomically with a push: written after the commit, a live row would name no readable
generation; written before, it would recreate the unreferenced-current leak. `content_head` is a row,
so the head changes in the same transactions as the edges and the free.

⚠⚠ **Generations have state in SQL too, and every touch is a guarded write**. A
point-in-time "does my generation exist?" check races the reaper: it can pass after the reaper has
picked the generation and before it deletes. So:
- A generation's liveness is a `content_generation` row.
- The reaper marks it `reclaiming` in a transaction that re-checks `last_touched`, **before** any byte
  is deleted.
- A touch (a chunk `GET`/`PUT`, a resume, a head publication) is an update that succeeds only while the
  row is `live`.
- A writer whose touch fails knows its generation is gone, and restarts the emit.

⚠ **Derived artifacts need a compound address, not a hash**. A rung's content id is
`derivedThumbnailContentId`, a one-way hash of the root and the class, so nothing can recover the root
from it to form a nested key. Rungs are therefore addressed by **`(rootContentId, artifactClass)`**:
the storage path, and the head's key, carry both. The derived id stays an identity for edges if one is
still wanted.
- ⚠⚠ **A derived artifact's publication serialises on its ROOT's free**. A rung that
  finished after the root's free recorded its generations would not be in the deletion set, and with no
  edge of its own it would stay current for ever beneath a freed root. So a derived `PUT` or head
  publication checks the root's state in the same serialisation as the free, and is **refused for a
  freed root**. The uploader then REVIVEs the root first.

What follows:

- **Freeing C deletes the prefix `<contentId>/`.** No description is needed. The relay knows which
  blobs are C's by their key, so the backfill's manifest fetches and `content_ref` become unnecessary.
  - ⚠ **G3's REVIVE stays; only its ref list goes**. A device returning after T
    still needs one atomic relay step that does all of the following, or a queued G2 deletion could
    erase the fresh generation, and a crash between steps would leak it:
    - cancels the pending deletion;
    - clears a **generation-stamped** freed marker;
    - flips `current` to the re-uploaded generation;
    - commits the retried block and its edge.
  - ⚠⚠ **A free names generations, not the whole prefix**. The re-upload happens
    **before** that atomic step, while the deletion is still armed. A prefix-wide delete could remove
    the fresh chunks first, and the revive would then publish a generation with nothing in it. So
    marking C freed records **the generations that exist at that moment**, and G2 deletes exactly
    those. A generation written after the free is never in the set. It is unreferenced until the revive
    publishes it, and reclaimed after T like any other abandoned generation if the revive never comes.
- **Freeing a file root frees its derived artifacts with it.** Rung content ids are already derived
  from the file's (`FileContentIds.contentIdsOf`, `derivedThumbnailContentId`). Nesting them under
  the root makes the reference model recursive: one edge per root, the file's root **content id**,
  not six per holder. ⚠ That is not `File.ReferenceRoot`: B's edges are keyed by `(holder, property)`
  and need no row-minted root, so the reference roots and their handles lose their last consumer
  (§10.8 already retires the handles in B6).
- **Undescribed duplicates cannot recur.** A re-emit under a fresh CEK writes a new generation, `current` flips to it,
  and every other generation under the prefix is garbage **the relay can name**: it reclaims them
  itself, with no client, no description and no backfill.
- **House Rule #1 holds.** Content ids are random, not content hashes, so there is no
  confirmation-of-file oracle. The relay already learns "these refs are one content" from
  descriptions today. The generation id is random too.

### 11.3 Why generations, not overwrite-in-place

Overwriting `<contentId>/<n>` on a re-emit would break any reader that is mid-download: it holds the
old manifest's CEK, and would meet new-CEK chunks. So each emit writes a **new generation** and
then flips the head in one relay operation.
- ⚠ **The FIRST head of a new content id rides on the push**. A device that
  flipped `current` and then crashed, or lost its outbox, before pushing the row would leave a current
  generation with no edge, and G4 never frees never-edged content, so it would leak for ever. So the
  push's edge tuple for a new content carries its generation, and the relay sets `current` in G1's
  transaction.
  - ⚠ **G1 verifies that generation in the same transaction**: it must still be
    `live`, with its manifest present. A push queued on an offline device for longer than T would
    otherwise publish a head over reclaimed bytes.
  - ⚠ **A never-published generation that expired is NOT a revive**. It had no
    edge, no freed marker and no pending G2 deletion, so REVIVE has no state to transition from, and
    the queued outbox would wedge. The check answers a distinct **`generation_expired`**. The uploader,
    who holds the plaintext, re-uploads into a new generation and retries through the ordinary
    first-head G1 path. `content_freed` and REVIVE stay reserved for content that was live and then
    freed. Until then the generation is non-current, and reclaimed after T if no push comes.
  Later flips (re-emits of live content) are a single `content_head` update, ⚠⚠ **serialised with G2**.
  A flip that raced the free could publish a generation the free had already
  recorded for deletion, or install a head on content that is already freed and edge-less, where the
  non-current reaper would never find it. So a flip runs under the same per-content serialisation as
  the free, and is **refused for freed content**. That case is a REVIVE, the atomic transition above. The relay reclaims a non-current generation only after
a grace period (the same T as G2). A crash mid-emit leaves a non-current generation, which the relay
also reclaims after T. That is the undescribed-duplicate garbage class, now self-cleaning.

⚠ **T alone does not protect a slow reader**: a large or paused download can read
`current` just before a flip and still need old chunks after T. Two things together close it:
- **Reads and writes touch their generation:** a chunk `GET` or `PUT` refreshes it, and only a
  generation untouched for T is reclaimed.
  - ⚠⚠ **Touches extend only OBSOLETE generations of LIVE content**. Once C's last
    edge is gone, C's free has a **hard** deadline (T from that moment) that no read extends. Otherwise
    a stale device could keep freed bytes alive for ever just by reading them. §10.2 already requires
    that reading freed bytes does not preserve them.

⚠⚠ **`content_blob` stays: it is the byte index**. `putWithinQuota`, `bytesUsed` and
`GET /sync/usage` sum `content_blob.byte_len` under the store lock. That is paid-storage quota
enforcement, and an S3 prefix cannot give an atomic per-owner total. So every blob keeps its index row,
keyed by its path instead of its hash, and every PUT and delete maintains it as today. What goes away is
the **description** tables (`content_ref`, `content_reference`), not the size accounting.
- ⚠⚠ **Paths are write-once**. Today a PUT to an existing key skips the quota sum,
  which is safe only because a hash key cannot change size. A mutable path would let a client park tiny
  bodies under quota and then overwrite each with a maximum-sized one. Generations are immutable by
  design, so the relay **refuses a PUT to an occupied path** unless the body is byte-identical (a
  retry). New bytes always mean a new generation, which is charged in full.
- ⚠ **A resumed upload verifies its generation first**. A deferred upload can stay
  paused for longer than T, while `PendingContentEmit` still records its landed chunks. Resuming
  blindly would upload only the remainder and publish a generation whose first chunks were reclaimed:
  an unreadable copy reported as synced. So a resume asks the relay whether its generation still
  exists. If it does not, it discards the landed-chunk record and restarts the emit.
- **Clients recover:** a reader that meets a missing chunk re-reads `current` and restarts from the new
  manifest, never failing the open outright.

⚠ **Not deterministic encryption** (reusing one CEK and nonce schedule per content id, so re-emits
produce identical bytes). It would also stop duplicates, but a single bug that reuses a content id
for different bytes becomes nonce reuse, which is catastrophic for confidentiality. Generations
cost only a pointer.

### 11.4 What it changes, and what it costs

| area | today | with content heads |
|---|---|---|
| relay tables | `content_blob` + `content_ref` + `content_reference` + edges | `content_blob` (still the byte index, now keyed by path) + heads/generations + B's edge registers |
| freeing C | describe, then delete the listed refs | delete the prefix |
| a duplicate emit | a new undescribed chunk set, for ever | a non-current generation, reclaimed after T |
| declaration backfill | a fetch and decrypt per content | edges only (B3), no manifests |
| integrity | AEAD, client-side (the relay cannot check `sha256(body) = ref`, because manifests break the pattern) | AEAD, client-side, unchanged. ⚠ The AAD must bind `(contentId, gen, n)` so a chunk cannot be replayed under another index |

Costs:

- **Migrating existing blobs.** Either copy the described ones into prefixes (a relay-side job needs
  descriptions, which exist for everything B3 re-stamps), or read both schemes until old content
  ages out.
- **Everything that assumes ref = ciphertext hash:**
  - upload resume records the hashes of landed chunks (`PendingContentEmit.landedChunkHashes`);
  - the relay's `REF_PATTERN`;
  - the manifest's chunk list, which shrinks to count and sizes;
  - the share store.
- **Deleting a freed content's generations on S3** means listing **each recorded generation's** prefix
  (`<contentId>/<gen>/`) and batch-deleting it. ⚠ It must never be the whole `<contentId>/` prefix:
  a revive's fresh generation lives there too. That is cheap, and B5's deletion
  machinery already batches.
- **The chunk AEAD must bind index and generation** (above). Today's per-chunk AAD must be checked for
  that before relying on it.

### 11.5 Relationship to B

This does not replace B: B decides **when** content is unreferenced, and this changes **what deleting
it means**. Together, B's edge registers plus prefix deletion are the whole content plane. Every
remaining relay table besides blobs and edge registers, and every client-side pass that fetches
manifests, goes away.

Natural order: after B3, so the edges are authoritative and re-stamped, and **before** B5 arms G2,
so the automatic sweep deletes prefixes rather than learning the description machinery first. If it
lands later, G2 needs a second implementation.

---

## 12. Roadmap: everything left on the data-lifecycle track (2026-10-01)

One list, so parallel sessions can pick lanes. ⚠ **Status lives in each phase's PR and issue; this
section is the map, not the tracker.** Update it when a lane opens or closes, not per commit.

### 12.1 B0: free the quota now (operations, little new code)

✅ **Drained on the maintainer's realm, 2026-10-01.** The plan and the enqueue agreed on one digest
(`5618ba8a…`), and the drain deleted **5,845 blobs, 4,570,891,217 bytes** (4.257 GiB): 0 spared, 0
failed, nothing left queued. The relay's index and the object store both stood at 10,458 objects,
0.800 GiB, right after, and the operator token is removed again (the probe with the old token
answers 404).

- **Before it:** the OnePlus had latched v6; the desktop then ran a fresh walk (492 edges, 685 refs,
  157 `without refs`) that changed **nothing** on the relay: the described content ids and the
  undescribed refs were identical before and after, and 156 of its 157 `without refs` were manifests
  the relay has never stored. The blob store was copied whole to
  `s3://tenshu-relay-blobs/archive/reclaim-2026-10-01/` (15,947 objects, byte-identical total, all
  5,845 enqueued refs present), with a database snapshot beside the nightly ones.
- ⚠ **The "two plans a day apart" step was skipped** (step 3 below), on the maintainer's decision that
  the archive makes the run reversible. One plan at 10:01Z and one at 10:24Z carried the same digest.
- ⚠⚠ **The Mac and the iPhone hold a quarter of the realm's rows, and the phone holds all of it**
  (measured from the stores and the v7 lines, 2026-10-01):
  | | OnePlus | Mac | iPhone |
  |---|---|---|---|
  | content-bearing rows (v7 `from N rows`) | 1,646 | 369 | 369 |
  | of which `File` rows | 1,474 | 197 | not read |
  | edges declared | 8,844 | 1,182 | 1,182 |
  - No rows or properties were skipped on any of the three, so this is what each store holds.
  - The Mac's files are a strict subset of the phone's. The 1,277 it lacks are 778 of 960 under
    `/Backup/OnePlus CPH2581/WhatsApp Images`, all 404 under `…/Camera`, and all 95 under `/Screenshots`.
    The `Camera` folder row itself is absent on the Mac, and `OrphanedFileSweeper` deletes, locally and
    without a line, every file whose ancestor folder row is missing.
  - ⚠ **They were pushed.** The relay's log holds blocks targeting 1,368 of the phone's 1,474 file
    holders, and all three device cursors are at its head. The phone's lane journal is empty: its
    1,198 `SyncDelta` rows are unreferenced leftovers of sealed batches, not unsent work.
  - ⚠⚠ **Which side is right is NOT known.** Either those folders were deleted in the realm and the
    phone never applied it (this pair of folders has that history), or the Mac and iPhone lost live
    rows. Until that is settled, the phone is the only device that can serve as the whole-realm walk.
- ✅ **The counted whole-realm walk completed on the OnePlus, 2026-10-01 18:39Z** (build `88ac28a59`,
  with the backfill's counting, progress, liveness and retry fixes):
  `declared 8868 edges, 11294 refs, 5633 handles, from 1675 rows, 3235 without refs, 1 re-declared after
  a racing emit — recorded complete`. No rows or properties skipped; 2 fetches got no answer in the
  scan and both resolved on the retry; all 18 declare batches accepted; one walk (two scheduled
  attempts were turned away as already running). The phone was in normal use throughout.
  - **Relay right after:** 8,868 edges over 1,478 holders, 5,638 described contents, 11,445 blobs
    (0.907 GiB). **Described refs with no stored blob: 2**, the same two as before the walk (contents
    `a0ea8b3b-…` and `e0105842-…`), which were never uploaded and were neither drained nor archived.
  - ⇒ Everything a readable manifest on the whole-realm device names is stored, except those two. No
    chunk the drain deleted is among them.
  - It took eleven attempts. What stood in the way is recorded in `tech-debt.md` (*Android blocks
    Control's network in the background*). The progress, liveness and retry fixes made a pass **started with Control
    on screen** survive it; a pass that starts with no foreground client bound is still unverified.
- **Still owed**, in `docs/how-to/relay-content-reclaim.md` *When the archive may go*:
  0. ~~a counted fresh walk on a device that holds the whole realm~~ ✅ done, above;
  1. the post-drain query for described refs with no stored blob, which must be 0. ⚠ **First run: 2**
     (contents `a0ea8b3b-…` and `e0105842-…`). Neither ref was on the drained list and neither is in
     the archive, so the relay **never stored** them: a description whose chunk upload did not land,
     most likely refused with 507 before the drain. Not a drain loss, but two contents are incomplete
     on the relay until a device that holds the bytes uploads them. ⚠ **Still 2 after the completed
     walk** and several restarts of Control. **Found:** they are the MANIFESTS of
     `/Backup/OnePlus CPH2581/Camera/IMG20250120171053.jpg` and `…/IMG20250120171555.jpg`. All five
     chunks of each reached the relay on 2026-09-21 within ten seconds of the row; the manifest never
     did, the phone keeps no upload intent, and `ContentRepair` was wired on desktop only. The fix
     wires it on Android and iOS and has the declaration backfill hand a content with no manifest on
     the relay to the repair on the device that holds its bytes. Rerun the query after a pass on that
     build. ⚠ Why the manifest upload failed that day is not known; the relay's logs are gone.
     - ⚠⚠ **Still 2 after that fix ran on the phone, 2026-10-02, and the fix cannot heal
       them.** Control's file store holds **no bytes** for either photo: `files/Backup/OnePlus
       CPH2581/Camera` is empty on the phone, against 404 `Camera` rows, so the repair answers "this
       device cannot help". The originals are in the phone's camera roll (`/sdcard/DCIM/Camera`, 9 MB
       each), which the repair does not read. "The phone held the bytes" above was true of the camera
       roll, not of Control.
     - ✅ **What the v8 pass did heal:** four `WhatsApp Images` files (`IMG-20260905-WA0018`, `19`,
       `20`, `24`) whose manifests had never landed, hidden until then among the ~3,200 never-emitted
       thumbnail entries. Pass 1 (11:27–11:53Z): `4 with NO MANIFEST ON THE RELAY handed to repair …
       NOT recorded complete`. The relay then held each manifest and its chunk, with the edge declared.
       Pass 2 re-declared all 8,868 edges at 12:14Z and recorded itself complete: its final line
       was not captured (the phone was unplugged at 7,500 of 8,868), but a third trigger at 12:37Z
       answered `skipped: already backfilled at this format`.
     - **Owed for the two photos:** either delete the two rows and let Synced Folders upload them
       again from the camera roll, or give the backup's skip an ensure from the source bytes. And
       find what removed Control's copy while the manifest was missing: `EvictFileBytes` and the
       offline keeper spare a file only while an upload intent is journalled, and these had none
       (`tech-debt.md`, *An upload interrupted before its content is journalled is never uploaded*).
       Not verified that this is what happened;
  2. the one `without refs` not matched to a never-stored manifest, by content id;
  3. opening a sample of files on each device, and the 507s on `PUT` having stopped;
  4. copying `/root/b0-evidence/enqueued-2026-10-01.txt` into the archive prefix: the runbook's Restore
     rebuilds the index rows from it, and the database snapshot expires after 30 days;
  5. then closing the undescribed-duplicate heal and, only once the reconciliation holds, deleting the archive and the saved
     S3 credentials in `/root/b0-evidence/` on the relay box.

The steps as they were planned, strictly in order:

1. **The v6 declaration backfill, after the one-content-one-upload fix reached every device:** one complete pass, the
   plan's `edgeOnlyWithManifestCount == 0`, and a **second** device's pass that agrees. ⚠ A caught-up
   cursor does not prove a complete live set (`sync-device-catch-up.md` §12.4); the runbook's
   precondition 1 has the reasoning.
   - ⚠ Until the scheduler-holder wiring fix, the scheduled pass is a **silent no-op on desktop and
     iOS**, so the device is an Android one.
   - On Android it runs on idle + charging + unmetered, or on demand through the debug-build
     `MaintenancePassReceiver` (USB, on Wi-Fi).
   - ✅ **Both passes done on the maintainer's realm** (2026-10-01): the OnePlus reported `already
     backfilled at this format`, and the desktop ran its fresh walk once the wiring fix shipped.

   On the maintainer's realm a pass is about 8,100 sequential manifest `GET`s, roughly 10–15 minutes.
2. **Copy `sync-relay-server/docker-compose.yml` to `/opt/tenshu/repo` on the relay box.** It cannot
   `git pull`, and B0's compose change is what forwards `RELAY_OPERATOR_TOKEN`.
3. **Two plans a day apart** (`docs/how-to/relay-content-reclaim.md`): the undescribed remainder must
   have stopped shrinking.
4. **Complete the runbook's ENTIRE preflight, not only its plans**. That includes
   ruling out **live undescribed content the relay cannot recognise**: legacy entity-keyed emits, a writer
   whose `resolveContentId` returns null, for example pre-`Workout.ContentId` streams. Deleting those
   loses data. On the maintainer's realm the Fit half was explicitly waived on 2026-09-30, because it is
   test data. That waiver covers no other realm and no other writer.
5. **Back up the database; the maintainer approves the plan; enqueue and drain.** Frees about
   4.25 GiB. Close the undescribed-duplicate heal.

### 12.2 The core chain: candidate B (§10.7)

| phase | what | size | needs |
|---|---|---|---|
| **B1** | M1: a stable "Syncing…" state and banners, plus the schema pull fence's surface | M | — |
| **B2** | Audit: push-time edge coverage per writer, newest-wins convergence of content properties, choose T | S–M | — |
| **B3a** | The edge set as a schema declaration: `ContentReferencing` derivations + `contentEdgesOf`, with an equivalence test (§10.11). No wire change | S–M | — |
| **B3b** | Client-local prerequisites: the persisted deletion watermark at the folder tombstone's HLC (closes gap 11 today), the cascade persisted with its pending tombstone, `OrphanedFileSweeper` removals recorded, the Q8 tests. No wire change | M | — |
| **B3c** | Relay half, after a short wire spec: edge records, the tombstone watermark, the unstamped-edge migration, the push floor, `referenceId` optional. Threat-model amendment | M–L | B2, wire spec |
| **B3d** | Client half: edges on push with per-delta HLC, standalone by row, cascade lists and conditional reap removals on the wire, the one-device re-stamp pass, the version on every **push**. Settles §10.10's reaper-HLC finding (§10.12 Q4) | M–L | B3a, B3b, wire spec |
| **B4** | ⭐ Spec: [`content-plane-cr2-b4-spec.md`](content-plane-cr2-b4-spec.md) (v0.1, 2026-10-02). G3: `content_freed`, the atomic REVIVE, "deleted on another device", outbox resolution; the client drain's **adds-before-removes per content** (§10.14), after which T drops from 24 h to minutes | M | B3's wire contract |
| **B5** | ⭐ Spec: [`content-plane-cr2-b5-spec.md`](content-plane-cr2-b5-spec.md) (v0.1, D1–D5 decided 2026-10-03: T = 15 min once armed, `CONTENT_SWEEP_MODE` off/dry_run/armed, legacy free routes 410 when armed, the clock seeded at arming, the quota exclusion armed-only). G2: the relay sweep, dry-run first. Arming gates: the push floor, legacy free routes closed, side routes fenced, the re-stamp done, the clock seeded. Settles §10.10's queue-ABA finding | M | B3's push floor raised, B3's re-stamp done on **one** complete-history device, B4, B2 |
| **B6** | Retire the device sweeps, ST-3b's barrier, successor declares, declare and undeclare as authority, the **reference roots and handles** (HR 9 for file and health), plus P1's offline-move dialog | M | B5 armed (⭐ decided 2026-10-04: in the same deploy, which arms it) |
| §11 *(optional)* | Content-head addressing | L | after B3, before B5 |

**B3 is the bottleneck.** ⚠ B5 does **not** wait for B3 on every device. That would be
the unbounded barrier again: a lost or fenced device would block reclamation for ever. It waits for the
push floor, which turns away an old or offline writer until it updates, and for the re-stamp on one
complete-history device.

⭐ **Split 2026-10-01: B3 = B3a + B3b + B3c + B3d.** "B3" elsewhere in this document means all four.
- **B3a and B3b need no wire change and can start now, in parallel.** B3b also fixes a divergence
  that exists today (gap 11).
- **Then the wire spec** ([`content-plane-cr2-b3-wire-spec.md`](content-plane-cr2-b3-wire-spec.md), v0.2, decided 2026-10-02: a hard cutover with a relay reset), after which B3c (relay) and B3d (client) run in parallel. ⚠ The reset dissolves B5's push-floor, side-route-fence and re-stamp gates (wire spec §0); B4, the dry run and closing the legacy FREE routes still gate B5.
- B4's relay half can overlap B3d.

⚠ **Not on the critical path, but owed before B5 arms:**
- ~~the provisional-row move guard (§12.3);~~ ✅ built 2026-10-03 (B5 spec §7);
- the quota exclusion of UNREFERENCED bytes in `/sync/usage` and the quota check (§10.14).

### 12.3 Independent items (any time, any session)

| item | size | note |
|---|---|---|
| Enable the local orphaned-bytes sweep **on every runtime** | M | Peers never free their disk after a delete today (§10.9). ⚠ `ORPHANED_BYTES_SWEEP_ENABLED` and the recorder and scheduler wiring exist only in `control-shared-proxy`'s **desktop** module. Android's, iOS's and the **web** runtime's `initFileService` provide neither (`tenshu-sdk-js`'s `FirstPartyPlugins.kt` passes no recorder, and `IndexedDbFileDatabase` keeps downloaded bytes too). So the task is to wire **and** enable it on all four runtimes, or those peers keep deleted files for ever |
| The delete-vs-move system test | S–M | Owed before B5. ⚠ **Half exists:** `ConcurrentMoveVsDeleteSystemTest` pins the **metadata** outcome in both ingest orderings and asserts no byte outcome. It must not close this item: the byte half is B's (§10.7: a move pushed within T keeps its content, one pushed after T gets `content_freed`). The byte-loss measurement (§2) is what happens to the bytes today |
| Two references to one content, the system test still owed from the double-stored-upload fix | S | ✅ Quota → 507 → recover exists (`ContentLifecycleSystemTest.aFullQuotaRefusesWith507_parksTheUpload_andItLandsOnceSpaceIsFreed`). Owed: two references to one content. No longer blocked: `storageGate` partitions devices (`tech-debt.md`) |
| ~~A refused "Remove download" is silent~~ | — | ✅ Closed 2026-10-29: moot, B6 D4 removed the pins and nothing throws `ContentPinnedError` |
| ~~A deferred move is silent~~ | — | ✅ Closed 2026-10-29: B6 D4 removed the deferred-move machinery; an offline move is refused with `MoveNeedsLocalBytesError`, shown by the move picker |
| `TombstoneDrainRealmGuardTest` flake | S | Moved to `tech-debt.md`; most likely fixed by `TestProxy`'s reentrant test lock |
| `MoveFile` tombstones children not yet synced | M | ✅ **Built 2026-10-29.** A folder move whose subtree reads short (a `Folder.FILES` reference with no row, or a member whose path cannot be read) is refused with `StillSyncingError` before its first write, in the same walk as the provisional-row check (`refuseIfSubtreeIncomplete`). ⚠ Refuse, not defer: online deferral would need a trigger, and a new error type would be a file-service bump for the same user remedy. Pinned by `MoveFolderIncompleteSubtreeTest` |
| Whether quota charges unreferenced bytes (§9 Q6) | decision, **reopened** | A decision was recorded on 2026-09-23: keep charging stored bytes. Its rationale was that declaration coverage would retire the quorum, which predates B, and its warning not to enable entitlement enforcement while the gauge reads high is already overtaken: enforcement is on in production and answered 507 until B0 freed the pile. Decide again once T is chosen (B2) |
| A provisional slice-snapshot row is not moved before it catches up (§10.11) | S | ✅ **Built 2026-10-03** (B5 spec §7). Test first confirmed the gap: nothing blocked the move, which pushed the snapshot's content id and tombstoned the old path. `MoveFile` now refuses with `StillSyncingError` while the device is catching up and holds no version of the row (neither `SyncMetadata` nor `LocalWriteMetadata`); pinned by `ProvisionalRowMoveTest` |
| The slice-snapshot writer deletes the snapshots of slices that no longer exist (§10.11) | S | A G4 leak, never a loss. Snapshot blobs get no edges |
| Parallel manifest fetches in the declaration backfill (`mapChunksBounded`, N = 4) | S | Optional: about 10–15 minutes down to 2–4. Moot after B3, whose re-stamp pass needs no manifests |

### 12.4 Closed by B as it lands

The quorum, and the successor and resolved-holder declares' displacement hazards (replaced by G1). Also
the lease-close round §9.14 owed (which §5 dissolves) and the measured byte loss of a delete-first
concurrent move (closed by B5 together with the delete-vs-move test's byte half).

⚠⚠ **"Closed by B" is a destination, not a mitigation, for the successor and resolved-holder declares.** By their descriptions
each can displace the edge of the content a live row names: a successor declare over a peer's
replacement, and a resolved-holder declare over a peer's overwrite. That is the loss direction, in
machinery that stays in production, dual-written, until B5 arms or B6 removes it. Nothing mitigates
either today. If B3 slips, they need their own fix (for the successor declare, a relay-side declare-if-absent).

⚠ **The deferred move's accepted residual and the probe→push gap close only if §9 Q5 removes the deferred-move machinery**. If Q5
keeps it as the fallback for online moves held back by a 402 or 507, those paths stay live and they
stay open. P1's dialog alone does not retire them.

### 12.5 Lanes, and how to shrink the track

Lanes that run in parallel today:
1. B0's remaining verification (§12.1), which needs the relay box and the devices, not code;
2. B1;
3. B2;
4. the §12.3 items.

The chain B2 → B3 → B4 → B5 → B6 is the critical path. B5's dependency on B3 is the push floor and one
re-stamp, not fleet-wide adoption.

To shrink it:
- **Ship P1's offline-move dialog early, and decide §9 Q5 with it.** The dialog is cheap. If Q5 removes the
  deferred-move machinery, the silent deferred move, its accepted residual and the probe→push gap go with it, without waiting for B.
- **Keep §11 parked** until B5 is in sight. B works without it.
- **Fold B6's removals into B5's PR series** rather than running a separate cleanup project.

