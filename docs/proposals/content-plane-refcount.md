# Content-plane refcount — the two planes are inverted

**Status:** v1.4 (2026-09-23). **CR-0…CR-5 all shipped. ⚠⚠ CR-2's §9.14 option 1 (a device progress quorum plus a merge-fencing
lease) is SUPERSEDED (2026-09-26) by the quorum-less design in
[`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md) — selected, unbuilt.** ⚠ The move-declares-no-edge defect (a move
declared no edge for the new holder) is fixed as a today-fix (§9.16) and does **not**
supersede the decision; §6e's divergent-move handle aliasing is untouched by it. ⚠⚠ **And the design has
been superseded by C′ (§6e): N0–N7.** ⚠⚠ **C′'s N4 (the authority flip) and N6 (retire `content_edge`)
are in turn superseded by CR-2 candidate B; handles stop being an authority.** The per-phase mapping is
in `content-plane-cr2-quorumless.md` §10.8. The phase map and every as-built
section are in §9; this header is a pointer, not a changelog.

> ⚠⚠ **CR-5 is REMOVED (CR-2 B6, 2026-10-04, `content-plane-cr2-b6-spec.md` §7):** `ContentEnumerationSweep`, `GET /sync/content/orphan-candidates`, `POST /sync/content/reclaim`, `GET /sync/content/contents` (N2) and N0's `ContentEnumerationAgeWindow` are gone; the CR-4d describe backfill stays. Freeing is the relay sweep's alone (CR-2 B5).

| | phase | state |
|---|---|---|
| **CR-2** | remove ST-3b's barrier | ⚠⚠ **superseded 2026-09-26, decided 2026-09-29** → [`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md) **candidate B**: pushes carry their content edges in the relay's transaction (HLC-ordered registers, G1); the relay alone frees content T after its last edge, on its own clock (G2); a late reference gets `content_freed` and revives or shows the loss (G3). Candidate A (a bounded wait, declare-at-ingest) was **not chosen**. Was: (§9.14, 2026-09-22) option 1 — a device progress quorum plus a lease that fences local merges through the physical free. ⚠ The move-declares-no-edge defect (a move declares no edge for the new holder) is a real adjacent defect but does **not** supersede this — what could is §6e's mint-per-re-key, unevaluated |
| **N0** | age window on enumeration | **shipped** (relay) |
| **N1** | the describe wire — holder-less form, the **handle** relay-side and the client half: a row mints a reference root, the emit derives `H(root‖propertyId)` and sends it beside the edge | **shipped** ⚠ three prerequisites owed before N6, below |
| ~~**N1b**~~ | ~~handles for pre-wire references~~ | **DELETED — the realm reset removed the population it existed for** (see below) |
| **N1c** | the deferred-content journal keyed on the **content**, not the row (§6d.1) — sync-service 7→8 | **shipped** ⚠ unrelated to the deleted N1b |
| **N2** | `GET /sync/content/contents` | **shipped** (relay) |
| **N3** | D3 — the offline-move fence | **live** (machinery and wiring as built §9.15) ⚠ its durable move intent sits on N1c's key; and a device that looks online asks the relay at move time (§9.17) |
| **N4** | the authority flip: the client's delete names what it orphaned | ⚠⚠ **superseded by CR-2 candidate B (G2)** |
| **N5** | remove ST-3b | not started; under CR-2 candidate B this is **B6** (deleted, not kept for the physical free) ⚠ **and "remove" was ambiguous** — whether this DELETES the recorder/ledger/barrier or keeps them for the physical free is an open sub-decision at §9.14 |
| **N6** | retire `content_edge`, `declare`'s edge half, `undeclare` | ⚠⚠ **superseded by CR-2 candidate B**: `content_edge` stays as G1's register table |
| **N7** | CR-6 — the undescribed pile | not started, and the only step that deletes on inference; under CR-2 candidate B this is **B0's operational reclaim** (G2 never touches it) |

⚠⚠ **CR-5's sweep is LIVE on Android and desktop** — it follows `RECLAMATION_SWEEP_ENABLED`, which
was already on — and inert on iOS. It can only ever act on content the relay has a **description**
for.

⚠⚠⚠ **It therefore does NOT reach the 4.7 GiB this doc was opened for (§9.9).** The backfill
describes *live* content, and an orphan is by definition unreferenced — so the existing pile is
undescribed and unreachable. What the track delivers is **no new orphans**; reaching the old ones is
CR-6 (§9.9b), gated on description being provably complete realm-wide. Earlier phases called CR-4d
"the only route to the 4.7 GiB"; that was wrong.

§10 — the log-as-declaration variant — is **evaluated and not adopted** (2026-09-10); its useful
parts shipped as §9.5 and §9.6b, and it does not change CR-6's gate.

⭐ **The device pass is done (§9.13): CR-5 `reclaimed 5 of 5 candidates (0 vetoed)` on hardware
and converged.** ⚠⚠ Its *control* path is proven; **bytes are not** — the probes' uploads all failed,
so nothing reclaimed had blobs. ⚠⚠ And ST-3b frees a candidate ~2 s after the delete, so CR-5 cannot
be watched acting while ST-3b is live.

⚠ **CR-2 was the only phase left of the *original* design, and it is gated on a DEVICE PASS rather
than on code (§9.10)** — C′'s N0–N7 above are the work that now follows it.
ST-3b resolves a candidate's refs by fetching its manifest, so it reaches the pre-CR-4 corpus that
CR-5 structurally cannot. Deleting it before each device's backfill has run strands, **permanently**,
any pre-CR-4 file deleted in that window — the backfill describes only *live* content, so a file
deleted first can never be described afterwards.

⚠ Two rules that outlived their phases: **§9.5's rule is stated positively** — `allRefs − keep`
would delete every Fit and shared-realm chunk today, verified in code — and **§9.6's invariant is
*declared ⇒ walkable***, which §9.6b makes structural.

⚠ Historical: §2 reopens **ADR 0017 D6**, not a parenthetical; the reference graph comes from the
schema's `OnDemand` trait, not from operations (§5, Q1); declaration is a holder-keyed edge set at
the sync layer's two seams (Q2); §6c is corrected against ADR 0006's recreate semantics; D1 and the
first D2 draft are retracted (§6c.3).

Related, and load-bearing:
[`file-content-addressing.md`](file-content-addressing.md) §4 (the claim this revisits),
[`sync-retention-horizons.md`](sync-retention-horizons.md) (the barrier, RH-0…RH-1b),
[`drive-upload-stop-and-reclamation.md`](drive-upload-stop-and-reclamation.md) §4.16 (ST-3b),
[`retention-quota-model.md`](retention-quota-model.md) §2 (the three-tier taxonomy),
[ADR 0017](../adr/0017-file-content-two-plane-channel.md) D6 (the decision this supersedes),
[ADR 0006](../adr/0006-sync-tombstones.md) (recreate semantics, §6c.1).

---

## 1. ⭐ The inversion

Tenshu has two planes, and they have the wrong disciplines.

| plane | what it holds | wants to be | **actually is** |
|---|---|---|---|
| **metadata** | rows — cheap, mergeable, regenerable | eventual | **immediate + authoritative** (HLC, tombstones, `SemanticMerger`, convergence) |
| **content** | bytes — expensive, finite, quota-bearing | **transactional** | **eventual, lossy and capped** |

The metadata plane got the rigorous treatment. The content plane got a best-effort ledger:
`ContentReclamationCandidates`, a device-scoped preference row, capped at **2 000 entries**, fed by
**one** operation, where every gap is permanent because nothing revisits it.

⚠⚠ **The reclamation barrier is a client-side approximation of a refcount.** That is what it is
structurally: *"have all peers seen this delete?"* is a proxy for *"does anything still reference
this?"*. It is expensive, approximate and leaky **because** it is compensating for a fact the relay
could simply be told. RH-0…RH-1b made that proxy correct; they could not make it complete.

---

## 2. The claim this revisits

Recorded in **ten places across seven files**, all tracing to one decision:

> A literal manifest refcount is **HR#1-impossible** (the relay can't read a manifest to learn which
> blobs a delete orphans).
> — [`file-content-addressing.md`](file-content-addressing.md) §4

That sentence rules out a relay-**derived** refcount. True, and not in dispute. But it is a summary,
not the decision. ⚠⚠ **The decision is [ADR 0017](../adr/0017-file-content-two-plane-channel.md)
Decision 6 and alternative (iv)**, which considered the client-**declared** variant by name — *"the
obvious 'client tells us which refs are still live' alternative"* — and rejected it as *"a new
metadata leak strictly worse than the access-pattern residual."* So the question this document asks
was asked, and answered, on privacy grounds. §4 answers that objection; it is the argument to beat.

⭐ **And the objection has already been overridden in practice.** ST-3b's shipped
`POST /sync/content/delete` sends a deleted file's chunk-ref set to the relay — exactly the disclosure
D6 refused — and did so without amending the ADR: it still carries `Superseded by: —`, and
[`drive-upload-stop-and-reclamation.md`](drive-upload-stop-and-reclamation.md) does not mention it.
Reopening the refcount therefore means **superseding ADR 0017 D6**, not striking a phrase from
proposals.

The other sites, so the strike list is complete: `file-content-addressing.md` (×3),
`contacts-app.md` (×2), `drive-upload-stop-and-reclamation.md`, `cross-app-file-access.md`,
`README.md`, `CLAUDE.md` (⭐ **already struck on this branch** — its row now reads "REOPENED", and
the file is back under budget at 35 954 of 36 000 bytes), and ⚠⚠
[`threat-model.md`](../sync/threat-model.md) **T-R-09**, whose
residual reads *"a manifest refcount stays HR#1-impossible … no new relay-visible wire shape."* A
declaration table **is** a new relay-visible shape, so the implementing PR owes a T-R-09 amendment
under House Rule 2 (what it discloses: §4, last paragraph).

A client already knows which blobs a manifest references — it wrote the manifest. Declaring
`holder H → content C` when it emits and `H is gone` when it deletes requires no decryption, no
manifest read, and no relay-side business logic on user data. **HR #1 is untouched**: the relay stores
what it is handed and counts it, exactly as it already does for `byte_len`.

### 2.1 ⚠ The accepted consequence has now been observed, and it is worse than predicted

The same section accepted the cost explicitly:

> deleted-file blobs … simply **linger, counted against the owner's quota** — quota is the brake

Measured 2026-09-08 on the maintainer's realm, after RH-1b shipped and a 929-file delete reclaimed
correctly:

| | |
|---|---|
| files the client references | **11** |
| blobs the relay holds | **14 788** |
| bytes | **5 050 247 863** — 94.07 % of 5 GiB |
| reachable by any existing mechanism | **~0** |

**Quota was not a brake; it became a wall.** The owner could not write, and no sweep could help,
because forward-only reclamation cannot see anything orphaned before the recorder existed — and the
recorder is capped, best-effort, and wired to one operation (§3).

⚠ **Two honest limits on what this measurement proves.** First, the pile is almost certainly
**pre-recorder residue**: a one-time population, not a rate. §3's mechanisms do leak on a fresh relay,
but *how fast* has not been measured, and the case for B as the primary mechanism rests on that rate.
Second, C is not the only route to these particular bytes: the relay's `reclaimOwner` primitive
exists (behind a commerce-signed account closure, so not an operator tool today), and Q6 asks whether
the pre-release reset still applies. Whether either may be used is an ops / ⚖️ counsel question; this
doc names them so that *"only C returns the 4.7 GiB"* is read as *only C, as a product mechanism*.

---

## 3. Why a fresh relay would still leak

⚠ **This is not a legacy problem.** On today's code, with a brand-new relay, content is orphaned by
at least six live mechanisms:

1. **The 2 000-candidate cap**, whose own KDoc states overflow drops the **new** candidate so *"that
   content is simply never reclaimed"*. Delete 3 000 files at once and ~1 000 strand permanently.
2. **Only `DeleteFile` records.** `FileServiceProxyImplementationFactory` wires the recorder to
   `DeleteFile.ID` and nothing else, while `UploadFileImplementation` sets `File.ContentId`
   unconditionally — so **overwriting a file orphans its previous content with no delete and no
   candidate.**
3. **Recording is best-effort by design** — `runCatching { … }`, and the recorder returns early with
   no `deviceId`. Both are correct (a delete must never fail because bookkeeping did) and both
   silently forfeit the candidate.
4. **Crash between blob emit and entity save** leaves an unreferenced manifest — already documented
   as *"counts against quota until account-lapse reclaim"*.
5. **Fit records nothing.** `DeleteWorkoutImplementation` hands its tombstone to the generic
   `TombstoneEmitter` and records no candidate — nothing in `:health-service-proxy` references
   `ContentReclamationRecorder` — so every workout delete orphans its stream blob. ⚠ The fix is
   **not** a Fit-side recorder, which would copy `DeleteFile`'s coupling into a second service; it is
   the generic seam of §5.2, with a pinning test (§7b.3) until that lands.
6. **Repair mints a fresh CEK.** ST-3f's re-emit *"mints a fresh CEK, so the chunks are new blobs and
   the old ones (if any survived) are orphaned"* (`ContentRepair`). Accepted in
   `file-content-addressing.md` §3.4; listed because it belongs in the inventory.

---

## 4. ⭐ The privacy objection does not survive the threat model

The instinctive objection is that a refcount table leaks the manifest→blob grouping. The threat
model already accepts it, twice:

- **A6. Metadata** — *"block sizes, **block timing**, owner IDs, sync frequency, tombstone
  existence"*, explicitly **not** end-to-end encrypted.
- **T-T-07. Metadata analysis** — *"**Timing correlation can still reveal editing patterns.**"*
  Defense: Partial. Residual: **"Accepted, documented. Full metadata privacy is not a v1 goal."**

⭐ And the correlation is available **at upload as well as delete**: `PUT blob X, Y, Z` followed by a
`SyncBlock` is the same temporal signature as the delete burst. A logging relay can already infer
the grouping for *live* content. A refcount converts an accepted inference into an explicit fact.

⚠⚠ **Cross-user dedup does not exist**, which bounds this tightly. Per the shared-realm analysis,
*"a random CEK ⇒ two users' copies of the same file get different ciphertext + different refs"*. So a
refcount can never reveal *"these two users hold the same file"*. The only sharing it exposes is one
owner's own content shared into a realm they shared it into — an edge the threat model has **already
analysed and accepted at Low**.

What is genuinely new is narrow: **certainty** (inference → fact), **retroactivity** (a table holds
what a relay that only started logging today would lack), and **durability**. Real, but the same
character as what is already accepted — and nowhere near "impossible".

⚠ **What a holder-keyed table (Q2) adds beyond ST-3b, in one sentence for the T-R-09 amendment.** The
relay already sees an entity-id hash on every tombstone (A6) and already receives ref sets on every
ST-3b delete, but never the **link** between them. A declaration keyed by that hash makes the link
explicit — *this entity's content changed at time T* — which is editing-pattern metadata T-T-07
already accepts.

---

## 5. ⚠⚠ The reference graph — and why no operation may be asked to declare it

The first draft of this section warned that a refcount would reap a contact avatar sharing a deleted
file's `contentId`. Q1 measured it: `PersonAvatar` stores its bytes inline (`MAX_IMAGE_BYTES`,
256 KiB) and has never had a `contentId`. The example was wrong. The principle — **a refcount is only
as safe as the completeness of its reference graph** — stands, and the real holders are different.

### 5.1 Who actually keeps bytes alive

| holds bytes? | where | how it addresses them |
|---|---|---|
| **yes** | `File.ContentId` | random `contentId` → `manifestContentRef(contentId)` |
| **yes** | `SharedFile.contentId` | the *personal* id, re-emitted under the **shared realm's** `ownerId` (`ContentRouting`: the storage key is the verified token's owner, so this is a second owner, not a second count) |
| **yes** | `Workout.Streams` | ⚠ **no field at all** — the emitter addresses the manifest by the entity-id hash (`RecordWorkoutImplementation`: *"no contentId value property"*) |
| no | `Album.coverContentId`, `AlbumEntry.contentId`, `TrashedPhoto.contentId`, `PhotoFlags.contentId` | annotations keyed by the Drive file's id; Photos owns no bytes — *"`File.ContentId` is the identity that actually survives"* (`AlbumEntry`) |

⚠⚠ Two consequences. A source-text audit for `contentId` **fields** cannot see Fit, so it would pass
with a live holder undeclared — the v0.2 answer to Q1 did exactly that. And the walk today's sweep
uses (`ProxyLiveContentIds`) covers Drive `File` rows **only**, so the completeness gap is not
something B introduces; it is latent in A.

### 5.2 ⭐ The right axis is a schema trait, and the sync layer already stands at both ends

Asking `RecordWorkoutImplementation`, `ShareFolderIntoRealmImplementation` and every future producer
to declare would couple each service to reclamation. It is also unnecessary. Byte ownership is
already marked **per property** by `Syncable(SyncStrategy.OnDemand)`; `DelegatingExternalPropertyWriter`
is registered against exactly those properties; and:

- **every content-plane byte is emitted by one function.** `SyncObserver`'s emit resolves
  `(entityId, propertyId, contentId)` through the `ExternalPropertyWriter` port for every `OnDemand`
  property — Drive, Fit, and the `SharedFile` cross-realm copy alike. The operation only writes the
  row.
- **every delete goes through one emitter.** `DeleteWorkoutImplementation` and
  `DeleteFileImplementation` both hand their tombstone to the generic `TombstoneEmitter`, and the
  drain (`SyncScheduler.drainPendingTombstones`) is sync-layer code.

⇒ **Declare at the emit; undeclare at the drain.** No `OperationImplementation` participates. The
only service-specific piece today — `DeleteFile`'s `reclamationRecorder` hook — is what goes away.
(`RealmData` is not the marker to use: it is a type ontology; `OnDemand` is the byte-ownership trait.)

⚠ **The one thing that forced the recorder into the operation, and how it is removed.** At delete
time the row is gone, so `resolveContentId` cannot read the id — which is why
`DeleteFileImplementation` captures `orphanedContent` *before* deleting. A declaration keyed by the
**holder** — the entity-id hash the tombstone already carries — needs nothing from the dying row:
undeclare is *"drop every edge held by H."* That is Q2's primitive, and it is a layering decision
before it is an idempotency one.

Completeness then reduces to **one assertion in `:core`**: every property carrying
`Syncable(SyncStrategy.OnDemand)` has a writer registered in `DelegatingExternalPropertyWriter`
(§7b.3). A schema walk, not a source-text audit — and one no operation can dodge, because there is no
call to skip.

**What becomes deletable** once the seam exists, which is the decoupling win in one list:
`ContentReclamationRecorder` and its proxy implementation, `ContentReclamationCandidates`, the
`reclamationRecorder` factory parameter, and the barrier.

⚠⚠ **The live walk is NOT on that list, and this line used to say it was** (corrected 2026-09-09
after review). §9.5's rule keeps a client-side walk as the **veto** that guards the recreate, so
`ProxyLiveContentIds` survives CR-2 — what changes is that it stops being Drive's tree walk and
becomes a schema-driven one (CR-4c).

---

## 6. Options

| | approach | deterministic? | recovers today's orphans? | cost |
|---|---|---|---|---|
| **A** | today — barrier + candidates (RH-0…RH-1b) | no | no | shipped |
| **B** | **client-declared refcount**, relay frees at zero | **yes** | no | wire + upload path + **§5 completeness** |
| **C** | enumeration sweep — relay lists an owner's refs, client diffs against its live set | no | **yes**, as drawn here — ⚠⚠ **no, as built** (§9.9) | one relay route + a client walk |
| **C′** | **C in reference handles** — relay lists *described content with its opaque `referenceId`s*, client diffs **handles**, relay keeps a **count** and no holder graph (§6e, revised 2026-09-17) | no | ⚠ **no** — described content only; the undescribed 2.67 GB still owes CR-6 | one relay route + `content_reference`; **retires `content_edge` itself** at N6 — B's edge half, `declare`'s edge half, `undeclare` *and* `orphan-candidates` all go, which is more than an earlier version of this row claimed |

⭐ **B and C are complements, not rivals — and under Q2 they are one table.** B declares edges; C is
the relay listing blobs with no edge older than a grace, answered by clients re-declaring from their
live `OnDemand` properties — a schema-driven walk, not Drive's tree walk. Building C on a separate
ref-listing route would build a second thing.

**Recommendation: C first, then B — with C built on B's table.**

- **C is unblocked and the only *product* mechanism that helps the live problem** (§2.1 names the
  non-product routes).
- **B is the better primary mechanism**, gated on §5.2's seam rather than on a per-service audit —
  smaller work than v0.1 estimated, and it touches no service.
- ⚠ Doing **B alone** strands today's orphans. Doing **C alone** leaves §3's structural leak in
  place and turns the sweep into a permanent garbage collector rather than a backstop.

⚠⚠ **C carries the blob-GC data loss hazard (time-based GC freed durable content, `retention-quota-model.md` §2), twice — and both times on the client's side.** (i) A sweep that
reads an *absent* ref as "unreferenced" is the shape that caused that data loss: a partial live walk
must abort, never reclaim. ⚠ Not symmetric (Q3): `orphans = relayRefs − liveRefs`, so a short *relay*
listing shrinks the diff and merely misses orphans, while a short *live set* enlarges it and deletes
live content. (ii) **An upload in flight
looks orphaned.** A peer that has `PUT` its chunks but not yet pushed the SyncBlock has bytes on the
relay that no live row references yet, and `caughtUp` cannot help — the reference is not in the log.
`ContentRepair` already notes *"an absent manifest also describes an upload still in flight."* C must
exclude blobs younger than a window; the relay's `last_referenced_at` (refreshed on every `PUT`) is
the input. Today's sweep is immune only because its candidates come exclusively from deletes of
things that existed.

⚠ **C's join is in the wrong unit unless the relay holds edges.** The relay lists *refs* (the query
exists: `SELECT ref FROM content_blob WHERE owner_id = ?`); a client's live set is *contentIds*; mapping
one to its chunk refs means fetching and decrypting its manifest — one `GET` per live file per sweep.
Fine at 11 files, not at 10 000. Diffing at the manifest level first helps (`manifestContentRef` is
derivable with no fetch); holding edges on the relay removes the walk.

---

## 6b. ⭐ The invariant, and what a refcount fixes

**Metadata leads, content follows.** `DeleteFileImplementation` emits the tombstone *before*
recording the candidate, and the barrier holds the bytes until peers have consumed it — so a peer
sees the file, or sees nothing, **never a file whose content has vanished**. Any redesign preserves
that ordering; §6c.1 is where a naive refcount breaks it.

⭐ **What the refcount fixes is partial deletion.** Today a delete is N calls in batches of ≤512
refs — 4 620 of them in the measured case — and a connection break mid-way leaves some chunks gone
and the rest stranded, with nothing recording where it stopped. With a declaration the **edge** is
the transaction: one message, and byte-freeing becomes the relay's own lazy, idempotent work. A
half-finished free is work to redo rather than a corrupted file.

⚠ Metadata needs none of this. Tombstones and rows are bytes-per-row, so whatever the watermark
gates there is irrelevant to quota and invisible to the user. **Content is the only plane where the
timing is user-visible** — which is the asymmetry that justifies the whole redesign.

## 6c. ⭐ D2 — decrement at declaration; the relay owns the deferral

```
declaration lands  → relay drops the holder's edges AND its quota accounting, atomically
                   → blobs with no remaining edge are freed on the RELAY's own schedule
```

The owner is credited immediately; the relay bounds its own disk on its own terms; no client can
stall it. **Quota amplification needs an attacker-controllable delay, and there is none here** — which
is exactly what the two retracted designs in §6c.3 got wrong.

⚠ Immediate credit is not a nicety. Physical deletion is **O(chunks)**, and `MAX_DELETE_REFS` caps a
request at 512, so a large file is thousands of round trips. If the quota only moved on the last
byte, "I deleted a movie to make room" would mean waiting out that whole sequence. The declaration is
the only point at which the answer is both known and instantaneous.

### 6c.1 ⚠⚠ "At zero, nothing references it" is false — declarations are not references

[ADR 0006](../adr/0006-sync-tombstones.md) records *"a future block with `hlc > tombstone.hlc` is
accepted; the entity is recreated. CRDT-standard,"* and `SyncProcessor.resolveConflictAndSave`
implements it. So: device A deletes and undeclares. Device B, which has not yet pulled the tombstone,
renames the same file with a later HLC. B's block **recreates the row on every peer, with the same
`contentId`.**

- **Today** the barrier holds the bytes until B consumes the tombstone; the next sweep's live walk
  sees the resurrected row and drops the candidate (`plan.stillReferenced`, no relay call). Correct
  by construction — the barrier *was* doing a correctness job here, which v0.2 denied.
- **Under a naive free-at-zero** the relay freed after minutes; the resurrected row points at reaped
  bytes; ST-3f repairs only from a device that still holds the plaintext, which an on-demand file on
  a phone may not. **Permanent loss, conditional on local cache** — not eventual consistency.

⇒ The count answers *"who has declared?"*, not *"who references?"*, and the two diverge exactly at a
recreate. Two closures; the design needs one of them:

- **(a) The recreate re-declares.** The peer that performs the recreate (`SyncProcessor`'s
  `hlc > tombstone.hlc` path) re-emits the edge for the row it just brought back — the same seam as
  §5.2, still with no operation involved. The relay grace must then cover the lag from B's write to
  *some* peer's re-declaration: seconds-to-minutes on a healthy realm, Q5's residue on an absent one.
- **(b) The grace is bounded below by the tombstone retention window** (30 days), after which no
  recreate can arrive. Relay-owned, so not amplifiable — but it re-creates the "invisible deferred
  set" liability: charged, yet on disk for a month.

**Recommended: (a), with (b)'s window as the ceiling on how long a recreate is honoured.**

### 6c.2 What the grace must satisfy

| | |
|---|---|
| owned by | the relay, entirely — no client input, so no stall handle |
| bounded **below** by | the re-declaration lag of §6c.1 (a) — not "minutes, chosen for UX" |
| bounded above by | the relay's own disk policy |
| **reportable** | ⚠ what the relay holds pending-free is charged but still on disk — the same shape as the orphan population this doc exists because of, so it must be visible |
| not | a settle window: it waits to learn nothing, and a longer one makes no answer safer |

Everything beyond it is Q5's irreducible residue, which needs a UI regardless.

### 6c.3 Retracted: D1, and the first draft of D2

**D1** proposed decrementing the quota at declaration while gating the *physical* free on the
barrier. ⚠⚠ Uncounted pending-free bytes are quota amplification — upload 5 GiB, declare deleted,
upload 5 GiB, repeat — and the attacker controls the barrier: keep one device that never syncs and
the pending set never drains. **The first D2 draft** kept the barrier as the gate on physical
deletion and was the same attack one level down: the owner is credited, keeps a device offline, and
the relay's disk grows without bound. Both fail for one reason — a **client-controllable**
deferral — which is why D2 as stated gives the client no handle at all.

---

## 6d. ⭐⭐ D3 — fence the write nobody can observe, instead of holding bytes for it

> ⚠ **Superseded in CR-2 B6 (2026-10-04, D3/D4):** the deferral, its intents, pins and replay are removed;
> an offline move of content the device does not hold is refused with `MoveNeedsLocalBytesError` instead
> (`content-plane-cr2-b6-spec.md` §3, §6). What follows is the record of the design that shipped before it.

**Asked 2026-09-16, by the maintainer:** *"we wait on a delete for the edge case that the user tries
to move files they don't have stored locally while offline… not freeing quota when the user might
need it seems like an incorrect compromise."*

Agreed, and the ledger is narrower than it looks. Two of the three cases the barrier is credited with
are already handled without it:

| case | what covers it | needs a wait? |
|---|---|---|
| **recreate** (§6c.1) — a peer renames a row it has not yet seen deleted | the sweeping device's live walk vetoes the candidate (§9.5) | no |
| **a peer-only row, peer online** — the write reaches the relay | `caughtUp`: the sweeper has consumed the log before it acts | no |
| **a peer-only row, peer offline** — a move/rename/trash keeps a `contentId` alive on a row nobody else has | nothing today | ⚠ the barrier tries, and **cannot succeed** |

⚠⚠ **The third is not a waiting problem.** `ReclamationBarrier`'s own KDoc: a device *awake and
disconnected* holds writes nobody can observe, so *"no amount of waiting or checking reveals it"*.
A barrier that waits for it therefore trades a permanent quota cost for a case it does not close —
which is the compromise this decision rejects.

⇒ **D3. Refuse (or defer) the one write that can create an unobservable reference, verify from the
device that holds the bytes, and let a delete free at zero edges without ST-3b's roster barrier.**
⚠⚠ **"No roster involved" is the early form of this and it is not the whole claim.** §9.14 establishes
that freeing the **bytes** safely needs a **device progress quorum** — every admitted device
answering the quarantine — and a **lease that fences local merges** through the physical delete,
because the obligation to reference content is created by *ingest*, which no gate over outbound state
can hold off. ⇒ read the sentence as scoped to **ST-3b's barrier**, which goes in every option; what
replaces it for the physical free was **decided at §9.14 (2026-09-22): option 1** — the quorum plus
the merge-fencing lease, with retention of the deleting device's bytes as its companion. ⚠⚠ **Superseded
2026-09-26** by [`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md), which
**retires the lease and the quorum** — **do not build either.** *(Historical, option 1 only: "no roster
involved" was not the end of the story there, and option 1 without its lease reopened the
conflict-born-holder window. Under the chosen candidate B that window cannot open. A reference reaches
the relay's edge table in the same transaction as the push that creates it (G1), and the relay frees
content only T after its last edge goes, on its own clock (G2). A later reference gets
`content_freed` and revives or shows the loss (G3).)*

Three parts, and the third is what makes the first sound:

- **(a) The fence.** An operation that carries a `contentId` onto a **new holder** — `MoveFile`,
  rename and trash, which are one op — is refused or deferred when **this device is offline** *and*
  **cannot repair from the row afterwards**. Online is covered by `caughtUp`; bytes-in-hand is
  covered by (c).
  - ⚠⚠ **Two different checks, and conflating them is what makes this look contradictory.** The
    **fence** decides before the first mutation, and it decides by **reading** — including
    `fileDatabase.fileSize(path)` per descendant, which is what `OfflineKeeperImplementation` and
    `HasLocalDataImplementation` already use to answer *"are the bytes here?"*. ⚠ Metadata alone
    cannot answer it: an explicitly evicted on-demand file keeps its synced size and content metadata
    unchanged, so a metadata-only preflight reads a cached file and an evicted one identically. The
    point is that the preflight **mutates nothing** — not that it avoids the filesystem. The **byte verification** below happens per child *after* its own move and feeds
    **repair**; it never refuses the operation, because by then refusing is too late to be safe.
    ⇒ preflight decides, verification repairs, and neither does the other's job.
    ⚠⚠ **And repair must find its bytes through the INTENT, not through the row's path.** On iOS a
    failed `moveItemAtPath` leaves the bytes at the **old** path while the row names the new one —
    and `ContentRepair` reads the row's path, so the entry is dropped as "no bytes" exactly
    when the bytes still exist one directory over. ⚠ Precisely: `drainDeferredContent` does **not**
    re-read the row — it reads the path captured **on the journal entry** at `emitFileContent` time,
    which is the same mismatch by a different route. The durable intent already records **both**
    paths: repair tries the target, then the source, and the checkpoint that releases the pin is
    *"bytes verified somewhere the intent names"*.
    ⚠⚠ **And the fix has to be applied at the drain's `gone` check, which is the site that
    DESTROYS the recovery record.** There, `gone = fileDatabase.fileSize(entry.filePath.get()) ==
    null` and `if (emitted || gone) clearDeferredContent(...)` — so a path/bytes mismatch does not
    merely defer the repair, it durably deletes the intent that would have driven it. Applying the
    two-path rule only to `ContentRepair` leaves the one place that turns a recoverable state into an
    unrecoverable one untouched.
  - ⚠⚠ **The test is the bytes at the row's path *after* the move, not before.** `ContentRepair`
    reads `File.PATH` and asks `fileDatabase.fileSize(thatPath)`, so a pre-move check on the source
    proves nothing about the holder that will exist. It is not hypothetical:
    `IosFileDatabase.moveFile` passes `error = null` to `moveItemAtPath` and **discards the failure**,
    leaving the bytes at the old path while the row names the new one — a device that then answers
    "not me" to every repair. ⚠ That silent discard is worth its own fix; the fence must not depend
    on it being fixed first.
  - ⚠⚠ **Presence is not the test — the stored hash is.** `IosFileDatabase.moveFile` returns early
    when the source is absent and leaves whatever sits at the target, so bytes at the moved path can
    be an unrelated leftover. ⚠⚠ Worse than no repair source: `ContentRepair` re-emits from the path
    and `emitFileContentNow` stamps the manifest with the row's **stored** `File.ContentHash` rather
    than hashing what it read, so a repair from stale bytes republishes the wrong content under the
    right id. The fence — and the repair itself — must verify against `File.ContentHash`.
  - ⚠⚠ **And the bytes must be pinned until the moved edge lands.** Local bytes are an *evictable
    cache*: `OfflineKeeperImplementation.evictOverCeiling` deletes anything not `KEEP_OFFLINE` under
    the local-cache ceiling or the free-space floor, and `EvictFileBytes` does it on request. So
    "this device holds the bytes" is true when the move is made and can be false by the time it
    reconnects — the repair source disappears while the row still names content no edge protects.
    A device with a moved row it has not yet pushed must therefore hold that file out of eviction
    until (c) has **succeeded** — not until it has run. ⚠⚠ **Including the eviction the user asks
    for.** Both candidate mechanisms fence the *automatic* path, but `EvictFileBytesImplementation`
    calls `fileDatabase.deleteFile(path)` **unconditionally** — so *"Remove download"* on a file with
    a pending move intent deletes the only repair copy, and if the old holder drains before reconnect
    (c) has nothing to re-emit from. The explicit op must refuse or defer a pinned path too, which
    also means the pin needs a user-facing answer — *"this file is still uploading"* — rather than a
    silent no-op. ⚠⚠ A verify that finds the content gone hands
    the re-emit to the deferred-content journal, which retries later; releasing the pin at that point
    lets eviction take the only plaintext before the retry, leaving an edge protecting content that is
    already absent. The release is therefore a durable checkpoint — every blob confirmed, or the
    re-emit acknowledged — and it survives a restart, or the pin is not a guarantee.
    ⭐ Open: whether the pin is a `KEEP_OFFLINE`-style policy or a pending-move set
    `selectCacheEvictions` excludes; the second is narrower and needs no user-visible policy.
  - ⚠⚠ **The fence is a PREFLIGHT over the subtree, never a per-child refusal.** A folder move is
    recursive and **commits as it goes**: `MoveFileImplementation` saves the destination folder first,
    then moves children one at a time, returning on the first child failure. So a fence that fires on
    an online-only descendant halfway through leaves a **partially moved tree** — and the retry does
    not heal it, because the destination folder now exists and the op answers `AlreadyExistsError`
    (*"Target already exists at …"*). ⚠ The code's own comment claims *"a retry folds: every re-move
    is content-addressed metadata"*; it does not — **at either granularity**. The target row is
    written before `DataIdMigrator.migrate` removes the source, so a throw in that window leaves both
    paths present and the retry fails the duplicate-target guard. That is a pre-existing defect
    (tech-debt, 2026-09-16) which this fence must not amplify: the preflight keeps the *fence* from
    opening the window, and closing the window itself is separate work. ⇒ walk the subtree for
    fenced descendants **before** the first mutation, and refuse or defer the operation whole.
    ⚠⚠ **And the preflight is not only about the offline fence.** An *online* folder move of several
    online-only files acquires one acknowledged declaration per child (b), sequentially — so a link
    that drops after the third child leaves the first three moved and declared, the rest not, and the
    retry into the same `AlreadyExistsError`. A preflight that asks only *"is any descendant fenced
    while offline?"* does not see that coming. ⇒ the preflight acquires **every acknowledgement the
    subtree needs up front**, and the cascade runs only once they are all in hand; a declaration that
    cannot be obtained refuses the move before anything has moved. ⚠⚠ **And a refusal must hand back
    what it took.** Reservations acquired for the first children are, on refusal, owed to nobody: no
    target row will ever confirm them, and reservations deliberately do not age out — so a refused
    folder move would leave content reserved, charged and unreconcilable for ever, which is a
    *worse* outcome than the partial tree the preflight exists to prevent. ⇒ the preflight's
    reservations belong to the **same durable intent** as the move, and refusing takes the intent's
    rollback path — the identical path a crash takes, so there is one recovery routine rather than
    two.

- **(b) A move declares — and an online-only file's move waits for the acknowledgement.** ⚠⚠ "Online"
  is an observation, not a state: `declareContentEdges` answers `false` for any non-2xx or transport
  error, and the link can drop between that observation and the commit. For a file this device does
  **not** hold, a declaration that did not land is fatal — the old holder's delete leaves zero edges,
  reclamation takes the content, and (c) cannot repair what this device never had. So such a move
  commits only on an **acknowledged** declaration, and falls back to the fence's refuse-or-defer
  otherwise. ⭐ For a file whose bytes are local the pin plus (c) already cover a failed declaration,
  so only the online-only case waits. ⚠⚠ **"Bytes are local" must be asked per declared property, not
  per file.** A holder carries six edges, and the five thumbnail contents are independently
  on-demand and independently evictable — so the common case is a file whose `File.Data` is cached
  while some derived blob is not. The pin, the hash check and `ensureContentOnRelay(contentId)` all
  address `File.Data` alone, while the old path's tombstone undeclares **all five**: the exemption
  then lets the move commit on bytes that only cover one of the five, and the four derived contents
  are left at zero edges with nothing local to repair them from. ⇒ the exemption holds only for the
  properties whose bytes are actually in hand; the rest take the online-only path.
  ⚠⚠ **And for a thumbnail there is no stored hash to hold it to.**
  `FileBytesPropertyWriter.resolveContentHash` returns `null` for the thumbnail properties by design —
  `File.ContentHash` hashes the **primary** file — so the verify-against-the-stored-hash rule cannot
  run for four of a holder's five contents. ⇒ either a derived content gets its own stored hash, or a
  locally-cached thumbnail is **not** exempt and takes the online-only path with the rest. ⚠ The
  second is free and the first is a schema change, so the burden of proof is on widening the
  exemption, not on refusing it. Online, the move declares an edge for the new holder name.
  - ⚠⚠ **And that edge needs protecting from reconciliation until the row arrives.** §9.14's pass
    undeclares edges no live walk names — and a caught-up *peer* cannot see the moved row until it is
    pushed, so it would read the pre-commit declaration as stale and drop it. Verification then
    succeeds, the pin releases, the old holder's tombstone leaves zero edges, and CR-5 reclaims
    content whose metadata is still in flight. ⇒ either the declaration carries a **reservation** the
    pass preserves, or the declaration and the row's visibility are sequenced so no window exists.
    ⚠⚠ **An age grace on `declared_at` is not that reservation.** It expires on a clock; the thing it
    is waiting for does not. An outbound block that is wedged — offline for a week, or stuck behind a
    `402`/`401` backoff — outlives any finite grace, and the pass then drops the move's only edge
    while the row is still in this device's queue. For an online-only mover that is the unrecoverable
    case, since there is no plaintext to repair from. ⇒ the reservation is released by the relay
    **acknowledging the moved row**, not by age — or repair custody is held for exactly as long.
    ⚠⚠ **And the acknowledgement is not the same event as a peer being able to see the row.** It
    means the relay stored the block; a peer that finished its pull a moment earlier still holds a
    live set that predates it, and reconciling on that stale set undeclares the new edge after the
    reservation has been released. ⇒ ⭐⭐ **the invariant this whole chain converges on: a device may only undeclare an edge whose
    justification it could have seen.** Every weaker rule failed in the same way — an age grace
    expires while a wedged block does not; a relay acknowledgement means the block is *stored*, not
    that any particular peer has *pulled* it; a confirm makes the edge judgeable by everyone,
    including a peer whose walk predates the row. The stale reconciler is never wrong about what it
    saw; it is wrong to treat *"absent from my walk"* as *"gone"*.

    ⇒ **Stamp relay-side, filter by the caller's own cursor.** The client cannot name a sequence —
    `relay_seq` is assigned by the relay's `nextSeq` when `/sync/push` stores the block, and push
    answers only `OK` — but the **relay** can: it stamps the edge when the mover confirms it, and
    §9.14's undeclare is then filtered to edges stamped **at or before the cursor the
    requesting walk was taken at** — ⚠⚠ the *captured* cursor, not the device's current one. A live
    walk is long, and the scheduler keeps ingesting during it: the moved row can arrive after the live
    set was collected and before the undeclare is sent, advancing the cursor past the very edge the
    walk could not have seen. Filtering on "where I am now" would admit exactly the edge filtering on
    "where I was when I looked" excludes. ⇒ the walk captures its cursor with its live set, and sends
    that. ⚠⚠ **And "caught up" is not "has read everything", here as well.** `RealmConvergence.caughtUp`
    flips once the **forward** pull reaches the live tip, while C2's **backward** range can still be
    unread — so a joining or restored device can reconcile with rows it has never seen, undeclare
    their edges, and (its live walk lacking those rows too) let CR-5 reclaim the content once P3 has
    removed ST-3b. ⇒ §9.14 runs only under **complete history coverage**: forward past the point it
    is judging *and* no outstanding backward range below it. ⭐ The same condition the quarantine gate
    needs, for the same reason — two mechanisms, one question: *have I actually seen everything that
    could contradict me?*
    ⚠⚠ **And none of this is specific to a move.** An ordinary upload has the same shape:
    `UploadFileImplementation` saves and enqueues the row, then declares the content edge before that
    row is necessarily acknowledged — so a peer whose captured walk predates the upload can undeclare
    it, and once the uploader's cache is evicted CR-6 frees content whose metadata was merely *in
    flight*. ⇒ **pending-until-confirmed and snapshot-filtered undeclare apply to every declaration**,
    whatever produced it. ⭐ Scoping the rule to moves was an artefact of how the hazard was found, not
    a property of the hazard.
    ⚠⚠ **Which means uploads owe the durable intent too.** `emitFileContent` clears
    `PendingContentEmit` on success, so an upload that crashes after its declaration is acknowledged
    and before the observer's channel consumer durably writes the row block leaves **nothing** to
    re-enqueue — and a reservation with no intent behind it is reserved for ever, ineligible for
    reconciliation and invisible to every repair. ⇒ the intent is written **before** the declaration
    and cleared **after** the row block is durable, for an upload exactly as for a move; the
    pending-until-confirmed rule is only as good as the record that outlives the crash. A peer that has not yet pulled the moved row simply does not see that edge as
    eligible, and needs no roster, no unanimity and no waiting to behave correctly. ⚠ Until it is
    confirmed the edge is reserved and eligible to nobody; an edge that is never confirmed is the
    abandoned-move case, closed by the durable intent — rolled back on restart, not aged out. An edge whose justifying row the walk could not have seen is not
    evidence of anything — treating "I did not see it" as "it is gone" is the bug, and it needs no
    roster, no unanimity and no waiting to fix.
    ⚠⚠ **Which needs a cancel, or the leak comes back from the other side.** If the declaration is
    acknowledged and the process then dies before the moved row is enqueued, no row is ever coming:
    with age expiry forbidden, that edge holds the content charged for ever. A clock cannot tell an
    **abandoned** move from a merely delayed one — but a **durable intent record** can, and the
    reservation should be bound to it rather than to time: write the intent before declaring, and on
    restart decide from the **local row**, not from the queue. ⚠⚠ **Queue absence does not mean the
    move was abandoned.** `saveData` persists the target row before observers dispatch, and
    `SyncObserver.onDataSaved` only puts the emit on an in-memory `Channel` — the durable pending
    block is written later, by the consumer. A crash in that window leaves the moved row **live
    locally with nothing queued**, and a rollback keyed on the queue would undeclare the reservation
    for a row that still exists and, because the channel event died with the process, would never be
    re-emitted either. ⚠⚠ **And a wipe can take the intent and the row together, leaving the reservation stranded.** The
    object store can be cleared while the device identity and relay registration survive (§11's epoch
    note), so both sides of the restart check vanish while the relay still holds a reservation that —
    by the rule above — must never age out. It would then protect and charge that content for ever,
    and the epoch fix repairs progress generations, not reservations. ⇒ ⚠⚠ **SUPERSEDED — see below: a proven store reset KEEPS them.** The rule as first written was
    "a proven store reset **releases** that device's outstanding reservations" — ⚠⚠ not
    re-registration, which happens for ordinary reasons: `SyncScheduler.recoverRegistration()` calls
    `reregisterThisDevice()` after a sustained `401` **without clearing the store**, so hanging the
    release off re-registration would strip protection from exactly the still-pending rows whose
    blocks have not reached the relay. The trigger is the **fresh store epoch** — evidence the local
    state is genuinely gone — and nothing weaker. ⚠⚠ **And then only those whose row the relay does
    not already hold.** A wipe can land *after* `/sync/push` stored the target row: the client loses the
    row and the intent, the relay keeps the encrypted row, and releasing that reservation leaves a
    **live remote row with nothing protecting its content** until some other device replays it. ⇒ the release is **not attempted at all** — see below: the
    reservations survive the reset and the reconciliation pass collects whatever no live row names. ⚠ A device presenting a fresh store epoch is stating that
    *it* holds no intents — not that the realm holds no rows it authored.
    ⚠⚠ **And a push already in flight is neither "stored" nor "absent".** On route-1 a sibling
    runtime sharing the replica can have `/sync/push` outstanding when the cleanup looks: the relay
    holds no block, releases the reservation, and then accepts that block — leaving a live remote row
    with nothing protecting its content, which is the case this whole rule exists to prevent, reached
    through its own remedy. ⇒ **pushes carry the store epoch and the relay refuses a superseded one.**
    The epoch is already the trigger for the cleanup, so this is the same fact used as a fence rather
    than only as a signal: a wipe makes every pre-wipe push unacceptable *before* the cleanup runs,
    which stops a wiped device's in-flight blocks landing under an identity that no
    longer has the state behind them. ⚠ It is no longer load-bearing for the reservation decision —
    that decision is retired below — but refusing a superseded push is sound on its own, and cheaper
    than the lock across two routes the alternative would need.
    ⚠⚠ **Which the relay cannot evaluate as the wire stands — and must not be taught to.** A
    reservation is keyed by its intent id; what the relay retains is the ciphertext plus a routing
    envelope — owner, HLC, `targetEntityIdHash`. There is no field joining the two, and the tempting
    join is **wrong**: the target hash is derived from the entity's identity, so a *different
    incarnation* at the same path carries the same hash, and "a block exists at that hash" would
    answer "stored" for a row this reservation never produced — the very displacement the identity
    check two paragraphs down exists to refuse. ⇒ ⭐⭐ **the relay does not decide this at all — the reservation simply survives the wipe.**
    ⚠⚠ Three revisions tried to give the relay a way to answer *"is there a stored block for
    reservation R"*, and each one was a protocol disclosure wearing a different hat: an envelope
    reservation id groups a row's blocks; a positionally aligned list publishes exact batch
    cardinality; a repeated id across a row's standalone blocks does the same thing across blocks,
    letting the relay group by source row and read the fan-out. T-R-11 promises the opposite — that
    per-delta metadata stays encrypted and block **size** is only a weak cardinality proxy. And
    completeness cannot be evaluated without the grouping, because the grouping *is* the question.
    ⚠ Correcting myself twice over: I claimed that cardinality was already visible (it is not), and
    then proposed a declared part-set that discloses it more precisely.
    ⇒ a proven store reset **keeps** that device's outstanding reservations rather than trying to
    classify them. The content stays protected, which is the safe direction, and the orphan is
    collected by the **enumeration/reconciliation pass** — the backstop that already exists for
    exactly this shape, a reservation or handle no live row names. ⭐ The failure mode becomes
    storage held until a reconciliation, never protection withdrawn from a live remote row — and the
    partially-landed set, which had no safe answer in either direction, stops being a case: nothing
    is classified, so nothing can be misclassified.
    ⚠ What this gives up is promptness: bytes stay charged from the wipe until the pass runs. That is
    the trade this track makes everywhere else, and the one place it refuses to make it the other way
    is where a live row can lose its content.
    ⚠⚠ It also retires the `SyncBlock` envelope field and the HR #9 bump it would have owed — a
    positional schema composite reshaped for a correlation that turned out to be unbuildable without
    breaking I6.
    ⇒ on restart: **the row is present *and* it is this intent's row ⇒ complete** — re-enqueue the emit
    and keep the reservation; otherwise **roll back** and undeclare. ⚠⚠ **Presence alone is not
    identity.** The target path can have been filled by a different incarnation while the mover was
    down — on route-1 another runtime sharing the App-Group replica can write it — and completing
    against that row would confirm the mover's reserved content against a stranger, displacing the
    real edge of a row that has its own. ⇒ the intent records what it created (its `opId` stamp, or
    the row identity and HLC it expects) and recovery matches before acting. ⚠ The lost-emit window is a defect in its
    own right, and this recovery is the first thing that would notice it. Same record the repair pin keys off, so the move has one
    lifecycle and not two. ⭐ Under C′ — the edge-free end state defined in **§6e**, which is a separate decision and
    deliberately not duplicated here — the hazard disappears with the edge: no edges, no reconciliation pass. ⚠⚠ **That is an
    argument for ordering, not a waiver.** Until C′ is actually adopted *and* shipped, every
    requirement here binds on the **B** path as written; a hazard is not discharged by a design that
    may land later, and D3 is explicitly sequenced *before* the authority flip. Read every "C′
    removes this" below as "C′ removes this, once C′ exists".
  - ⚠⚠ **A holder name is a path, so an older tombstone for that path erases the new edge.**
    An edge's holder is `DataId(File.ID, <path>)` and the drain undeclares by **holder**
    (`undeclareHolders`, every property at once), blocks first and tombstones second within one
    tick. So: delete `/a/x`, then move `/b/y` onto `/a/x` before that delete has drained — the move
    declares, the older tombstone pushes afterwards, and its undeclare takes the **new** row's
    edges with it. The row is live (later HLC) and its content is now protected by nothing. CR-5's
    live walk still vetoes it; CR-6, which has no veto, does not. ⇒ the release must be
    **incarnation-aware**: undeclare only what the tombstone's own incarnation declared, or swap the
    incarnation relay-side in one transaction. ⚠⚠ **"Re-declare after the drain" is not a third
    option** — it leaves a zero-edge interval between `undeclareHolders` returning and the
    replacement landing, and CR-6 reclaims inside intervals exactly that shape. The window is small,
    the loss inside it is total, and an online-only mover cannot repair it. ⭐ C′ removes
    this one too: no edges, no release to order.
  - ⚠⚠ **A move declares a holder's whole property set, not one `contentId`.** A file holder carries
    five edges — `File.Data` plus the four derived thumbnail ids `ProxyDeclarableContent` enumerates
    (§9.6) — and the old path's tombstone undeclares all five. Declaring only the primary id leaves
    four live content objects edge-less; CR-5's derived-id veto hides it today and CR-6 would free
    them. ⇒ the move's declaration reuses that same enumeration, so the two cannot drift.
  - ⚠⚠ **And an upsert keyed `(owner, holder, property)` lets a LOSING incarnation own the edge.**
    The target path can be created concurrently on another device: this mover sees it absent,
    predeclares, and the other row then wins LWW. The live row now names *its* content while the
    single edge row — overwritten by whichever declaration arrived last — names the **mover's**.
    §9.14 only removes edges no live walk names; it does not *restore* the winner's displaced one, so
    the winner's content sits at zero edges and CR-6 frees it. ⇒ conflict resolution must leave the **winning**
    row's edge installed — an upsert that silently replaces a different incarnation's edge is the
    bug, not the collision. ⚠⚠ **But the declaration cannot name the incarnation it belongs to.** The
    preflight declares *before* the first mutation, and the row's HLC does not exist then:
    `SyncObserver.generateSyncBlock` assigns it asynchronously, after `saveData`. Stamping the
    reservation with an independently minted earlier HLC is worse than nothing — a peer's row can
    fall between that stamp and the emitted block, and the edge would then claim an incarnation that
    never existed. ⇒ the reservation is keyed by the mover's own **intent id**, which it does have,
    and the **incarnation is bound at confirm** — the moment the block exists and its HLC is known.
    That is the same confirm the pending/confirmed rule above already requires, so it costs no extra
    round trip: before it the edge belongs to nobody and is eligible to nobody; after it the edge
    names exactly one incarnation, and a losing incarnation's confirm finds the edge already claimed
    by a newer one and releases its own reservation instead of overwriting.
  - ⚠⚠ **Declaring first does not beat a reclaim already in flight.** `/sync/content/reclaim`
    snapshots `referencedContentIds(ownerId)` and calls `dropRefs` **later**, sharing no transaction
    or lock with `/sync/content/declare`: a reclaim can snapshot an empty edge set, the move can then
    declare *and verify the still-present bytes*, and that earlier reclaim deletes them afterwards.
    The online-only mover has no plaintext to repair from, and a local mover has by then released its
    pin on a successful verification. ⇒ either eligibility and drop become **atomic** with
    declaration relay-side, or repair custody is held until no pre-existing reclaim can still finish.
    ⭐ C′ removes this one too: with no edge set there is no snapshot to race. ⭐ An edge with
  an **empty ref list** is already legal and already protects content (§9.7's `declaredWithoutRefs`),
  so the moved file stops depending on the veto at all. ⚠ Not independent of (c): see there — a
  verification that finds the content intact declares nothing by itself.
- **(c) ⚠⚠ Holder-side verification *and declaration*, because ST-3f cannot fire here.** The repair
  runs on a **failed resolve** (`SyncProcessor.resolveContentManifest`), and the device holding the
  plaintext reads it locally and never resolves — *"the uploader holds the bytes but never resolves
  (so never notices) while every peer resolves but holds no bytes (so cannot help)"* (§4.18). So the
  holder must ask: when it reconnects, or when it pushes a row it moved offline,
  `ensureContentOnRelay(contentId)` — the manifest-and-chunks check built for the
  unchanged-overwrite path — and re-emit under the **same** `contentId` when the answer is no.
  - ⚠⚠ **It must declare the new holder's edge either way.** A present manifest makes
    `ensureContentOnRelay` return early without emitting, and an emit is what declares — so
    verification alone leaves the moved row with **no edge at all**, its old one having been
    undeclared by the move's own tombstone. The content then survives on the veto alone, and CR-6,
    which has no veto, would free it. (b) is therefore not optional garnish: it is the same
    declaration, done at the one moment the device is online and holds the id.
  - ⚠⚠ **Declare first, then verify — the order is the race.** Verifying first can observe an intact
    manifest while the edge set is still empty; CR-5 re-derives candidacy at that moment
    (`/sync/content/reclaim` re-checks `referencedContentIds`) and frees the content, and the
    declaration then lands naming bytes that are gone — with the check already satisfied, so nothing
    re-emits. Declaring first makes the content non-candidate before anything is asked about it, and
    a declaration that turns out to name absent content is exactly what the verify step then repairs.

**What the residue becomes.** With (a) and (c), a peer-only row always names content that peer holds
the bytes for. The worst case is therefore a **dangling reference that a device can repair**, not
bytes nobody can reconstruct. That is a different class of failure from the blob-GC data loss, and it is the class
ST-3f exists for.

**⚠ What it costs.** An offline move of an online-only file. ⭐ **Open: defer, not refuse.** Queuing
the move and applying it on reconnect keeps the gesture working — a dialog saying *"you must be
online to move this file"* is hard to justify to someone reorganising photos on a train. Refusal is
simpler to build; which ships first is a product call, and the safety argument is identical.

**⚠⚠ Dedup (P4) DOES widen this — an earlier revision of this paragraph was wrong.** It argued that a
dedup hit reuses a `contentId` only after hashing bytes the device has in hand, so (c) covers it by
construction. (c) *can* repair from those bytes, but only while they are still there: they are an
evictable cache, and `evictOverCeiling` or an explicit *"Remove download"* can take them before the
device reconnects. Meanwhile the dedup hit has done the one thing this fence exists to stop — put an
existing `contentId` onto a **new holder with no declaration** — so another device deleting the last
declared holder leaves it at zero edges. ⇒ a dedup hit enrolls in the same custody as a move: durable
intent, repair pin, declare-then-verify on reconnect. ⭐ The general rule, of which the move is one
instance: **any operation that makes a new reference to existing content takes D3's custody** — today
`MoveFile`, and P4 second.

**⚠⚠ And a conflict can duplicate a reference without any operation at all.** A divergent rename
leaves two live destinations *"carrying the same `contentId`"* (`file-content-addressing.md` §B), so a
second reference is created by a **merge** — on a device that may be offline and may hold no bytes for
it. Being no one's gesture, it cannot be refused or deferred the way (a) refuses a move; it can only
be declared when connectivity allows, which makes it a repair case rather than a fence case. ⚠ Stated
here so it is not later found as a hole in (a): the fence covers operations, and this is not one.

⚠⚠ **Which means P3's gate cannot be about operations either.** (a) refuses or defers a *gesture*, and
(b)/(c) hang their retry off that gesture's durable intent — a merge has neither, so a conflict-born
holder whose declaration cannot land has nothing scheduled to try again, and on a device holding no
plaintext (c) cannot repair it. Delete the last declared holder and CR-6 takes content a live merged
row still names. ⇒ **the merge enqueues the same durable declaration intent an operation would — in the
same transaction that persists the merged row**, and P3's gate reads *"no unlanded declaration
intents, of either origin"* rather than *"every writer runs the D3 build"*. ⭐ Stated as a property
of the queue, this half of the gate stops depending on how the reference came to exist — the only
form that survives the next way one gets created. ⚠⚠ **This narrows one sub-question, it does not
retire the fleet condition.** §9.14's gate item 5 — *"every admitted device runs a D3 build"* — is a
separate requirement answering a different hazard (a pre-D3 device performing an *unfenced move*),
and §9.14 further makes it a **standing floor** rather than a one-off rollout check, because the
roster keeps changing after P3 removes the barrier permanently. Both conditions hold. ⚠⚠ Two writes are not enough: a crash between persisting the row and
persisting the queue entry produces precisely the live, undeclared holder this rule exists to
prevent, and the device then reports itself owing nothing because, as far as any journal knows, it
does not. ⚠⚠ **And "the same transaction" is not available to promise.** `DatabaseImpl.transaction`
takes the replica lock and runs a flush barrier; the iOS store has **no multi-file transaction at
all** — `FileObjectStore` says so outright: *"this store has none — `NSData.writeToFile(atomically =
true)` is atomic per file"*. ⇒ **write-ahead ordering instead of atomicity**: the **intent is
persisted first**, then the merged row. A crash between them then leaves an intent with no row —
which recovery already rolls back — rather than a row with no intent, which nothing can detect. ⭐ The
ordering is the guarantee; asking two files to commit together is asking for something the platform
does not have.
⚠⚠ **But ordering alone does not serialise RECOVERY, and on route-1 that is a live gap.** Runtime A
persists its intent and pauses; runtime B, sharing the replica, sees an intent with no matching row,
concludes "abandoned" and rolls the reservation back; A then persists the merged row — and the live
unjournalled reference this whole section exists to prevent is created by its own recovery. ⇒ the
ordered writes **and the recovery check** run inside `Database.transaction`, which is exactly the
cross-process exclusion route-1 needs: the iOS binding is an `NSRecursiveLock` plus a `flock(2)` over
the shared replica. ⚠ That is a different property from the one the paragraph above says it cannot
supply — it gives **mutual exclusion**, not crash atomicity — and mutual exclusion is all the
recovery race needs. B then either sees both writes or neither, and never the window between them.

⚠⚠ **But a local queue is invisible to every other device, so the gate cannot be the whole answer.**
A device that resolves a conflict while connected and loses transport before declaring has committed
the new holder and journalled its intent — and no peer, and not the relay, can see that. The content
is not at zero references (the twin that *was* declared still holds one), so the hazard is narrower
than it looks: it is that the count is **short by one**, and deleting the declared twin then frees
content the undeclared one still names. ⇒ what covers that window is not the gate but a
**quarantine** — free physically only after a grace, and let any describe naming those refs cancel it.
⚠⚠ **Which means the quarantine cannot stay CR-6's alone.** §11.5 specifies it for the enumeration
sweep, while CR-5's `/sync/content/reclaim` (§9.8) deletes described content the moment it has no edge
and is absent from the sweeping device's live walk — never entering a quarantine at all. The window
above is reached through *that* path, so a grace attached only to CR-6 does not close it. ⇒ **every
physical free goes through the quarantine**, whichever mechanism proposed the candidate; the paths
differ in how a candidate is *found*, and should not differ in how it is *destroyed*. The conflict-born declaration is exactly such a describe, arriving from
whichever device reconnects first. ⚠ So the two mechanisms divide cleanly: the gate is about *this*
device's unfinished work, the quarantine is about *other* devices' — and a design that asks the gate
to cover both has mistaken a local journal for a global fact.

**⚠ What it owes the threat model.** Two statements become wrong *in intent* the moment this is
implemented, and the implementing PR carries the amendment (House Rule 2, the staging CR-3 used):
T-R-09 records declarations as happening at exactly two seams — the content emit and the tombstone
drain — and D3 (b)/(c) adds a third, a move's own declaration and a holder-side verify; and
`sync-retention-horizons.md` RH-1b's analysis of the roster barrier describes a gate this removes
rather than corrects. ⚠ Neither of *those two* is a new disclosure: a move's declaration carries the
same `(holderHash, propertyId, contentId)` an emit already sends, and removing a gate discloses
nothing.
⚠⚠ **But two other items here ARE new relay-visible state, and an earlier revision closed this
ledger without them.** The same test this document runs on the envelope reservation id — and
retires it on — applies to both:
- **A per-content progress answer, and a per-content veto.** Scoping the emptiness claim to named
  contents turns §11.4's deliberately global `(deviceId, format, complete)` marker into durable
  per-`(device, contentId)` relay state, and a veto is semantic on top of that: *this device just
  ingested something that references that content*. The relay has no device↔content association
  today — `content_edge` is owner-keyed over a hashed holder, and every content route resolves only
  `requireOwnerId`. ⚠ The per-content scoping is still right (a global claim lets one wedged intent
  freeze realm-wide reclamation), so this is an amendment to write, not a design to withdraw.
- **A store epoch on every push.** A new per-device relay-visible field that reveals wipe events. ⚠ It
  is no longer load-bearing for the reservation decision, which is retired below — so unlike the
  first item this one is a **choice**, and the cheaper answer may be to drop it rather than amend for
  it. Either way it owes a `RELAY_PROTOCOL_VERSION` review, which the envelope field's retirement
  explicitly accounted for and this did not.

**What it changes downstream:**

- **P3 (CR-2)** stops being *"remove ST-3b"* and becomes *"remove ST-3b's **barrier**"* — the
  unanimity **wait** leaves the delete path in every option, so the user's credit is immediate.
  ⚠⚠ **What replaces it for the physical free is decided at §9.14 (2026-09-22): option 1**, not
  "nothing" — a device progress quorum plus a lease that fences local merges through the delete, with
  retention of the deleting device's bytes as its companion.
- A delete then **credits** when the relay holds no edge for that content — the immediate credit
  §6c's D2 already specifies. **When the bytes go** is what the lease settles.
- ⚠⚠ **Checked 2026-09-23 — the earlier "`MoveFile` is the only one" wording was FALSE unqualified,
  and it was carried here as "not decided", i.e. believed rather than verified.** **Two** sites carry
  a `contentId` onto a new holder: `MoveFileImplementation` (`:294`, and `:223` for folders) and
  `ShareFolderIntoRealmImplementation` (`:266`→`:279`, onto a **`SharedFile`** row).
  ⚠ A third writer, `FileSliceSnapshotProducer` (`:202`, the C8 joiner applier), is **replication,
  not a new holder**, and an earlier revision of this line wrongly counted it: it materialises the
  *same path* on another device, and a holder here is `DataId(File.ID, <path>)`, so it produces the
  **same** `holderHash`. The distinction is load-bearing rather than pedantic — this survey is what
  decides which write paths need D3 fencing, and a replica of an existing holder needs none.
  Every upload and replace path **mints** a fresh id instead (`UploadFileImplementation:310`:
  *"upload and overwrite both mint a new one"*), and **there is no copy operation** — confirmed by an
  exhaustive search, so `file-content-addressing.md` §3.1's *"copy is free"* remains model, not code.
  ⇒ `MoveFile` is the only carrier **within this owner's accounting**, which is the scope this
  section needs: the share path *copies the blobs* into the target realm's own ownerId-scoped store
  (`SyncObserver:2531-2584` — every chunk GET from the personal store, PUT into the target), so it
  creates no second reference here. ⚠ **Say the scope out loud.** Unqualified the sentence is wrong,
  and it is exactly the kind of line a later implementer takes as a survey.

### 6d.1 ⭐⭐ The deferred intent is keyed on its CONTENT — decided 2026-09-22

**The question:** should `PendingContentEmit` be keyed on the `contentId`, which a move cannot
invalidate, instead of the entity id and path, which every move does? It had to be answered before
D3's durable move intent was designed, because the answer decides what that intent must carry.

**⚠⚠ The defect was worse than the issue said, and the correction matters.** The issue described a peer's
move as leaving the drain's `gone` branch to *delete* the intent. It does not reach `gone`. Ingest never
moves bytes, so after a peer's move they are still exactly at the path the entry names; the drain
resolved the content id through the **retired** row, got null, fell through to the no-content-id
branch — a mailbox manifest keyed on a dead entity, which declares **no edge and no handle** — and that
branch returns `true`. The intent was cleared as *done*. No reader can ever resolve the content, and
the old-path bytes are then an orphan `OrphanedBytesSweep` deletes an hour later. Reproduced across the
wire by `PeerMoveOfAParkedUploadSystemTest` (red on master: the mover reads `null`).

⚠⚠ **And a peer's *overwrite* corrupted, which the issue did not mention.** A row synced from a peer
does not fetch its content, so after a peer overwrites a parked upload this device still holds the
OLD bytes while the row names the NEW content id and hash — and re-resolving the row published the old
plaintext **at the new content's address, stamped with its hash**. Every reader's integrity gate then
rejects the file the peer actually wrote.

⇒ **Yes: keyed on `(contentId, propertyId)`.** An intent is an obligation to publish *content*; the row
that wanted it is a fact about who asked. Once that is stated the two things the entry records by
position stop being identity and become what they physically are:

| field | was | is |
|---|---|---|
| `emitKey` | `entityHash\|propertyId` | `content:contentId\|propertyId` (the entity key survives only for rows that resolve no content id, and for pre-N1c rows) |
| `targetEntityId` | identity | a **holder hint** — the fast path and the edge's holder; after a peer's move it names a retired row, and nothing may depend on it existing |
| `filePath` | identity | a **byte location** — rewritten by a local move, and still right after a peer's, because ingest leaves the bytes where they were |
| `contentHash` | resume guard | also the **authority** on whether the bytes at `filePath` are this content, once no row vouches for them |
| `contentId`, `referenceId` | — | new; the handle captured while the row that minted its root still existed |

**⭐ The drain: the bytes decide, not the row.**

| the bytes at `filePath` | verdict |
|---|---|
| the holder still names this content there (or its writer exposes no path) | publish — today's trust, no extra read |
| absent, a live holder names this content | **keep** — a local move is relocating them |
| absent otherwise | retire — deleted here, or never recoverable from here |
| present, hash known | verify **before any `PUT`** and again **while streaming**: publish on a match, retire on a mismatch |
| present, hash unknown (thumbnail rungs, workout streams) | retire if the holder now names *other* content — rungs are re-mintable and workout rows never move — else publish |

⚠⚠ The edge is declared only when the holder is gone or still names this content. It is an upsert
keyed `(holder, property)`, so declaring under a holder that now names other content **displaces that
row's live edge**. A retired holder is safe to declare under: a stale edge is a leak, not a loss.

⚠ The hash pass is a full read, taken only off the fast path, and it folds at the emit's own chunk
boundary: `IncrementalSha256` is a hash-*chain*, so any other stride never matches the stored value and
every verified entry would read as superseded. An unreadable file answers "keep", not "retire".

⚠⚠ **And the pre-check alone is a check-then-act.** Off the fast path nothing claims
the location, so a local recreate at the old path after a peer's move, or an on-demand fetch
promoting a newcomer's bytes, can write it between the pre-check and the emit's ranged reads. A
manifest over those reads puts mixed bytes behind this content's address, stamped with its hash, and
every reader's integrity gate then rejects the file the moved row names. ⇒ the verified path also
hashes the bytes **as they stream**, and writes no manifest unless they match. That covers every
writer, not just the ones anyone thought of. The chunks already sent become unreachable ciphertext (a
leak), and the entry stays for the next pass, whose pre-check sees the new bytes and retires it.

⚠⚠ **An overwrite names what it superseded.** Keyed on content, a new intent no
longer replaces its predecessor the way the entity key did. The drain retires the predecessor only
when it runs, and behind a closed gate it does not, so every overwrite on cellular left the old intent
queued. That inflated the waiting count, the bytes, and the upload pre-flight that counts the queue.
⇒ `UploadFile`, the one party that still knows the old ids, calls `discardSupersededContent` with
`File.Data`'s and every rung's superseded `(contentId, propertyId)` once its own new intents are
durable. ⚠ The emitter retires an entry only while its holder is this row and the row names other
content now. Two rows on one content id (a divergent rename) share **one** entry, holding whichever
row journalled last and reading that row's path, so overwriting the other row replaced nothing it
depends on. ⚠ It runs as soon as the new `File.Data` intent is durable, **before** the thumbnail work:
the motion mint rethrows cancellation, and a cleanup placed after it was skipped by every cancelled
background overwrite.

⚠⚠ **Two races the second review pass found, both in how entries are claimed:**
- **A pass judges the row, never its snapshot.** A local move retargets an entry *in place* (same key),
  so the pass's read of the journal can describe a retired row and an empty path by the time it
  reaches that entry. Retiring on it deleted the freshly retargeted row. ⇒ `drainOne` re-reads the
  entry once its key is held, and a RETIRE is a **compare-and-delete** in a store transaction. The
  retarget runs in one too, so the two serialise, and a row that moved on survives to the next pass.
- **Ownership is revalidated after the wait.** Two overwrites of one path both read the key of the
  content the row names before either starts. The second gets it only once the first has moved the
  row on to content the second does not own, and a drain of the first's intent could then publish
  on the trusted fast path mid-rewrite. ⇒ `withContentEmitOwnership` re-reads the key once held and
  retries if it moved.

**What it changes for D3 — the two answers N3 was waiting for:**

- **(a) The durable move intent carries NO content-intent state.** No re-key list, no journal
  reference: the key survives every move, so there is nothing for it to carry. It holds source,
  target, subtree, reservations, the pin set and both paths, and the content drain never reads or
  clears it. That is structural, not a discipline: nothing in the drain can silently retire a move.
- **(b) `rekeyPendingContentEmits` is replaced by `retargetPendingContentEmits`.** It rewrites the holder
  and (for `File.Data` only; rung paths are `contentId`-derived and do not move) the path, in place,
  under the unchanged key. It runs wherever a move is *applied*: `MoveFileImplementation` today, and
  N3's deferred replay later. ⚠ A pre-N1c entry still carries the entity key, which a move does
  change, and is re-keyed as before.
- **The pin is content-keyed.** `hasQueuedContentFor` resolves the row's *current* content id, so the row
  a peer moved here is pinned by the intent its uploader journalled under the old path. Its companion
  `hasQueuedContentAt(path)` protects the old path itself: `OrphanedBytesSweep` WAITs on any path an
  intent still reads, with no default (a defaulted "nothing owed" is the answer that deletes the only
  copy). ⇒ N3's pin set should be keyed the same way, so "Remove download" asks one question — *is
  this content pinned, by the journal or by a move intent?* — instead of two subsystems answering
  about paths.

⭐ **Storage stays row-shaped: one row per intent.** A `TMap` was raised and not taken, but for a
narrower reason than first stated. ⚠ The relation array is **not** incremental: `addToArray` and
`removeFromArray` read the whole member-hash row and store it back (`tech-debt.md` *"Relation Arrays
Are Full-Row Rewrites"*). What rows buy is that each **member is its own row**, so a per-entry content
write — resume progress landing chunk by chunk — rewrites only that entry and cannot lose a sibling's
update, and row deletion is what `clearingAnIntentDeletesItsRow_notJustItsArrayMembership` pins. ⚠ A
persisted `TMap` is an **unverified** path rather than a rejected one: `DbEntryMapper` never mentions
it and no schema declares a `TMap` field, so whether dynamic keys round-trip through the generic
composite branch is unknown (`tech-debt.md`: `TMap` is registered and parseable, and nothing persists one). Map-shaped *access* over row-shaped storage is fine.

**Residuals, stated rather than closed:**

- ⚠ **A peer's *delete* of a parked upload publishes dead content.** At ingest a delete and a move are
  the same event — a tombstone — so the bytes stay, verify, and go out. A leak, not a loss, bounded by
  how long *this device's* upload stays parked (Wi-Fi-only, a 507, offline), and collected by the
  enumeration pass once it describes handles. ⚠ Not by a deferred move's length: a deferred move is
  of bytes this device does *not* hold, so it parks no upload.
- ⚠ **An overwrite of a row a peer moved here leaves its old intent queued** (declined,
  deliberately). After a peer's move the entry still names the *retired* row as its
  holder, so `discardSupersededContent` for the moved row sees a holder mismatch and keeps it. Until
  the gate opens it counts in the waiting figures and the quota pre-flight; then the drain publishes
  it (the bytes still verify) under the retired holder, a stale edge. ⚠⚠ Retiring on "the entry's
  holder is no longer live" was proposed and is **not** taken, because it is a loss: two peers moving
  one parked file to two places leave two live rows on that content, its only copy at the old path
  here, and overwriting one branch would drop the intent the other still needs. No index can prove
  that no other live row names a content id, so the mismatch cannot be told apart from a twin. ⇒ the
  cost is storage (bounded by how long the upload stays parked, plus the stale edge until N6), which
  is the direction this track chooses whenever the alternative is a live row losing its bytes.
  ⭐ A `contentId → rows` index would let it retire safely, and would serve N4's capture too.
  ⚖️ **Maintainer's call, 2026-09-22: accept the leak** — of the three options (keep the leak;
  retire on a retired holder and risk the divergent-rename loss; build the index), the first.
- ⚠ **A local move's crash window.** `MoveFile` renames the bytes before it retargets the journal. In
  process the drain keeps the entry (the row still names the content), but a crash in between leaves
  the entry naming a path with nothing at it for good. Owned by N3's durable move intent, which
  records both paths; until then the cost is a stuck entry, not a deleted one.
- ⚠⚠ **A peer's move followed by a LOCAL recreate at the old path, before the drain, loses the
  content.** The upload writes the new file over the orphaned old-path bytes, and those were the only
  copy. The streaming verify stops that becoming a corrupt manifest; it cannot bring the bytes back.
  ⇒ the fix is for an upload to relocate owed bytes it is about to overwrite (`hasQueuedContentAt`
  answers the question), and it is not built.
- ⚠ **A peer's move followed by path reuse *by a peer* before the drain.** If a new file takes the old
  path, the holder hint names *its* content. `File.Data`'s bytes still verify, so the content is published, but
  with no edge: declaring one would displace the newcomer's. Protected by the live walks until N4.
  Its parked thumbnail rungs are retired instead — nothing can verify them, and any byte holder can
  mint them again.
- ⚠ **A pre-N1c entry whose row a peer retired** cannot be published at all, since its content id died
  with the row. It is dropped, loudly. The old code dropped it too, after first uploading it under a
  dead entity's name and reporting success. The population is whatever was parked across the upgrade.

## 6e. ⭐⭐ C′ — the relay holds no reference graph, asked 2026-09-16

> ⚠ **N1's handles and roots are retired in CR-2 B6 (2026-10-04)** — `content_reference` dropped (relay
> v25), no root on any row (`content-plane-cr2-quorumless.md` §10.8, `content-plane-cr2-b6-spec.md` §6).

**The maintainer's objection, and it lands:** *"the thing here is the unreferenced, this knowledge
should not be in the backend. And we can derive it locally, right?"*

⚠⚠ **C′ presupposes §6d's D3, a separate decision and not yet merged.** Without the fence,
the declare-then-verify ordering and the writer-scoped capability gate recorded there, removing the
relay-side re-check has nothing standing behind it — so this is an option to weigh against that one,
never on its own. ⚠ The sharpest case it delegates: a peer reusing an old `contentId` on a new row
**without a new blob `PUT`** — routine once dedup (P4) ships — is caught by neither the age window
(the blobs are old) nor the sweeping device's walk (the row has not arrived).

⭐ **"Unreferenced" is a fact about live rows, and only devices have those.** The relay can answer it
today only because `content_edge` was built so it could. And that answer is not what protects the
data: `ContentEnumerationSweep` already vetoes every candidate against the sweeping device's own live
walk and reclaims **nothing** when that walk is incomplete (§9.5). The relay's candidacy is therefore
a **pre-filter over an authority that already lives on the client**.

### ⚠ Why §6 rejected this, and why that reason no longer holds

> *C's join is in the wrong unit unless the relay holds edges* — the relay lists **refs**, a client's
> live set is **contentIds**, so mapping one to the other is a manifest fetch per live file.

`content_ref` already maps `contentId → refs`, and that table is **not** reference knowledge: it is
the grouping the relay needs to free anything at all, declared at the emit by the device that wrote
the manifest. So the relay can list **described contentIds** — the client's own unit — and the
fetch-per-file objection disappears.

```
GET  /sync/content/contents?after=&limit=   → ⚠ paged by HANDLE, not only by content: `limit` over
                                               contents lets one contentId carrying the owner's whole
                                               handle cap return in a single array, which is the
                                               request-volume protection T-R-09 asks for defeated by
                                               the shape of the response. A continuation *within* a
                                               content, or a total-handles cap, is required.
                                             { "contents": [ { "contentId": "…",
                                                              "referenceIds": ["…", …] } ],
                                               "nextAfter": ... }
             ⚠ the handles come back WITH the listing: a sweeping device is looking at content
               some OTHER device described, so it has no local row to recover them from.
client:  stale = listed REFERENCES − (references a complete live walk still names)
             ⚠⚠ and a QUEUED RELEASE is re-checked against its own handle, never its contentId:
               once two live rows legitimately share a content (conflict-split repair, or P4) the
               sibling keeps that contentId in the live walk, so a contentId-level re-check drops
               the deleted row's release and strands its content_reference for ever
             ⚠⚠ handles, not contentIds. A content with one live reference and one orphaned
               handle (a release that was lost) is "live" at content level, so a content-level
               diff releases neither — and after the live one is deleted normally, the orphan
               holds COUNT(*) above zero and the bytes stay charged for ever. The subtraction
               has to be over the same objects the count is over.
POST /sync/content/reclaim { "kind": "release" | "sweep",
                             "references": [ { "contentId": "…", "referenceId": "…",
                                              "hlc": "…"        // kind=release
                                              "cursor": 12345   // kind=sweep
                                            } ] }
                            ⚠⚠ two shapes, named rather than inferred. A release is ordered by the
                              delete's HLC; a sweep has no delete to quote and is ordered by the
                              relay cursor its walk was captured at. Overloading one field would
                              compare a clock against a log sequence — unrelated domains, and the
                              comparison silently answers something
                            ⚠ the HLC of the fact that SUPERSEDED the reference, so the relay can
                              refuse a release computed before a describe it never saw. The same
                              clock the metadata plane uses to decide which branch survives — so the
                              two planes cannot disagree about whether the row lived
                            ⚠⚠ a delete is only one such fact. N4's capture is generic, and an
                              **overwrite** or a **conflict merge** discards a reference with no
                              delete anywhere: there the stamp is the **superseding row version's**
                              HLC — the version that stopped naming this handle. Defining the field
                              as "the delete's HLC" left those paths unable to construct the request
                              at all, and once N5 retires the overwrite recorder their old
                              `content_reference` rows stay counted and pin the superseded bytes for
                              ever. ⭐ One rule covers all three: *the HLC of the row version that no
                              longer names this handle*, against the same CAS
                            ⚠⚠ a SWEEP removal carries no delete HLC, and must not be forced to invent
                              one: the enumeration pass removes handles no live row names — a spammed
                              handle, or a stale row whose release was lost — and for those there is
                              no delete anywhere to quote. ⇒ a sweep submits its **walk's captured
                              relay cursor** instead, and the relay applies it only to handles whose
                              last describe is at or before that cursor. Same snapshot discipline
                              §9.14's undeclare already uses: a device may only retire what it could
                              have seen, and a describe it missed refuses the removal
                            ⚠⚠ authored locally, which is the point: a delete made offline has no
                              relay to ask, and a relay-assigned number would scope §4's "offline
                              deletes remain reclaimable" to devices that happen to be online
                            ⚠⚠ a ROUTE-SEMANTICS change, so it owes a RELAY_PROTOCOL_VERSION bump:
                              old clients send { "contentIds": [...] } and mean "free the content",
                              which is exactly the whole-content behaviour that becomes unsafe once
                              two references share an id. Neither side can deploy first without it
                            ⚠ a reclaim RELEASES ONE REFERENCE; the content is freed when the
                              last one goes. A whole-content form would delete a twin's bytes.
```

### What it retires

- **`content_edge`**, the edge half of `POST /sync/content/declare`, `POST /sync/content/undeclare`,
  and `GET /sync/content/orphan-candidates`.
- ⇒ **the entire stale-edge problem class** (§9.14): no edges, so none to go stale. A folder delete
  needs no cascade, and the pre-EI-1 naming mismatch has nothing to
  mismatch. ⚠ The cascade and the naming fix shipped; under C′ they become dead weight rather
  than wrong.
- **The grouping the relay was told** — which entity holds which content, and `propertyId` in the
  clear, the one field that inverted the metadata plane's posture (T-R-09's CR-4 amendment). C′ hands
  that back.

### ⚠⚠ What it costs

1. **Nothing double-checks the client at reclaim time.** Today `/sync/content/reclaim` re-derives
   candidacy from the edge set and skips what an edge has reappeared for — the guard against a
   recreate landing between the client's read and its call. With no edges the client's word is final,
   so that protection comes from §6d's D3 (the fence, declare-then-verify) instead of from the relay.
   ⇒ **C′ presupposes D3.**
2. **The in-flight window becomes load-bearing.** An upload whose chunks landed but whose row has not
   reached the sweeping device is, to that device, unreferenced. Today the edge declared at the emit
   excludes it (§9.3 #5). Without edges the only guard is an age window on
   `content_blob.last_referenced_at` — which §6 already names as C's own precondition, so this is a
   deferred cost coming due rather than a new one.
3. **"Undescribed ⇒ unreachable" stops protecting anything the day a sweep subtracts blobs.** Fit's
   legacy manifest path and every pre-CR-4 byte are safe today *because* the relay only offers
   described content (§9.5's positive rule). A listing of described content keeps that; CR-6 does not,
   and those populations then need declaring or excluding explicitly.

### ⭐ What it does NOT cost: the immediate credit

D2's argument for edges was that quota must free the moment the user deletes, not when a sweep runs.
That survives, because the deleting device already knows what it orphaned —
`DeleteFileImplementation` collects `orphanedContent` during the walk, before the rows are gone — and
with the barrier removed it can act at once. ⚠⚠ **As shipped it collects `File.ContentId` and nothing
else — one of a file's SIX content objects.** The five thumbnails live at
`derivedThumbnailContentId(fileContentId, class)` and never enter that list; today `undeclare` covers
them only because it is keyed by *holder* and drops all of a holder's edges at once. N6 retires
`undeclare`, so from that point a file delete would release one handle and strand four — the exact
leak this track exists to end. ⇒ N4's capture is per **content-bearing property**, not per entity:

```
delete → veto against this device's own live walk → POST /sync/content/reclaim   (same tick)
```

⚠ What the client keeps is therefore **not** ST-3b's barrier-gated ledger with its `relaySeq` stamping
and roster snapshots, but a small **durable retry queue**: *these contentIds are owed a **re-check***,
so a delete made offline, or a call that failed, still frees later.

⚠⚠ **A queue entry is a reminder to re-derive, never a stored verdict.** Between the delete and the
retry the same `contentId` can be live again — a recreate (ADR 0006), a move, or a dedup hit once P4
ships — and reclaiming on the deletion-time answer would then delete bytes a live row names. Every
retry repeats the whole decision: the complete live walk, and D3's fence. An entry whose **handle** the
walk now names is **dropped**, not deferred. ⚠⚠ The handle, not the `contentId` — the same correction
the subtraction above needs, for the same reason. Two conflict-split rows (or, once P4 ships, two
deduplicated references) share a `contentId`, so a sibling keeping that id in the live walk would
drop the *deleted* row's release while its `referenceId` stays in `content_reference`. Deleting the
sibling later then still finds `COUNT(*)` non-zero and the bytes stay charged for ever — the entry
was dropped on a question it was not asking. ⭐ This is the posture the existing sweep already takes —
it re-plans from scratch each pass rather than carrying a verdict forward — and C′ keeps it. ⇒ P3 shrinks the ledger instead of
deleting it, and **enumeration becomes the backstop it was meant to be** — another device's deletes,
and emits that died mid-flight **after their declaration landed** — rather than the mechanism.
⚠⚠ **Not the legacy corpus.** A listing of *described* content cannot name content that was never
described, and §9.9's 2.67 GB is exactly that population: it stays CR-6's problem under C′ as under
C. Reading C′ as the answer to this track's original storage question is reading it wrong.

### ⚠⚠ What it costs CR-6 — and the correction that matters

**Determinism is not a property of the sweep. It is a property of where the reference set lives.**
§11.1 sets the bar — `orphans(S)` computable by the relay from its own state, *"its safety must not
depend on any client's walk having finished"* — and rules out CR-5 on exactly the ground C′ builds
on: the veto *"is a client walk, and its answer depends on which device ran it and when"*. C′
therefore forfeits that bar by construction: the reference set exists only as something a device
derives at some moment.

⚠⚠ **And the belief has lied twice, in production**, which is why §11 wanted the bar in the first
place: the backfill latched *complete* over the empty store a format wipe left (§9.7a), and the
schema walk fell back to the Drive tree on the first type it could not enumerate and **reported itself
complete anyway** — 7,990 edges `tenshu/file/…`, zero `tenshu/health/…`, with 270 workouts' live
streams inside the "undescribed" pile. CR-6 deletes on *inference*, so it is precisely the mechanism
that must not run on a belief that can be wrong.

⭐ **But the scope is narrower than that makes it sound, and the split is the useful part:**

| | needs determinism? | why |
|---|---|---|
| **delete-driven freeing** — reclaim what *this delete* orphaned | ⚠ **bounded, not none** | a wrong walk cannot *widen* the set, but the delete only names a **candidate** — whether anything else still references it is a walk's answer, see below |
| **enumeration** — `listed − live`, C′'s repair pass | ⚠⚠ **yes** | see below |
| **CR-6** — the undescribed 2.67 GB | **yes** | blob-level subtraction on inference, irreversible, and not a response to any delete |

⚠⚠ **Corrected: "an incomplete walk reclaims nothing" does not cover the failures that happened.**
Both production failures returned a **falsely `Complete`** walk — the marker latched over an empty
store, and the schema walk fell back and reported complete having dropped a whole service. `Incomplete`
is the case the code handles; `wrongly Complete` is the case that occurred, twice. ⇒ For an
**enumeration** pass the blast radius of that is everything the walk failed to see, and today's edge
prefilter is what bounds it: the relay offers only content whose edges are *gone*, so a device deleted
it. Removing the prefilter without putting a completeness gate in its place widens "content someone
deleted" to "everything this walk missed".

⚠⚠ **And the delete half is not fully exempt: a `contentId` can be shared.** ⚠⚠ **Correction — not
today, and the reason matters.** An earlier revision of this section claimed ids are derived from the
bytes, so identical files already share one. They are not: `File.ContentId` is *"deliberately a
**random** id, never the content hash — a content-derived relay-visible address would be a
confirmation-of-file oracle"*, and `UploadFileImplementation` mints a fresh one per content write. So
identical uploads share neither the id nor, since the ciphertext differs, the refs. Sharing arrives
⇒ ⚠⚠ **but it is live today anyway, through conflict.** `file-content-addressing.md` §B documents two
**shipped** compositions that leave two live rows on one `contentId`: a divergent rename (`/x→/y` vs
`/x→/z`) creates both destinations *"carrying the same `contentId`"*, and rename-vs-edit resurrects
the source beside the target the same way. So the hazard does not wait for a feature — P4 makes it
ordinary rather than possible, and **N6 may not retire the edge-based reference check until reference
handles and split re-minting cover the conflict paths**, not merely the dedup one. `DeleteFileImplementation`
collects what the delete *may* have orphaned; it does not decide orphanhood. Today the relay decides,
by re-checking `referencedContentIds` — the surviving twin's edge is what saves the bytes. Remove that
and the only remaining answer is the client's live walk, which is the thing that has twice come back
**falsely `Complete`**. The blast radius is bounded by the delete (one id, not everything unseen), but
a bounded loss of live bytes is still a loss.

⇒ **The split C′ must keep:** delete-driven reclaim stays client-authoritative and bounded by the
delete, **and keeps a relay-side reference answer for the shared-id question until §11's gate replaces
it**; **any enumeration pass — C′'s repair and N7 alike — needs a deterministic completeness gate**,
not merely a walk that says it finished.

⇒ **C′ is a decision about the delete path, not about CR-6.** CR-6 still owes a relay-visible
statement of what is referenced, ordered against the log — §11.3's move one, which today's
side-channel `declare` is **not**: it rides a separate route, best-effort, and unordered against `S`.

⚠ So "drop `content_edge`" does not foreclose CR-6 so much as guarantee a rebuild: §11 needs a
transport the table does not have either way, and a re-declare pass already exists. Keeping the table
is therefore a **cheap hedge** — it stays as the fast path and the inventory's gauge — and *not* a
claim that it is CR-6-ready. ⚠⚠ What it must not become is a table nobody maintains but everybody
cites: once the delete path stops depending on it, its truthfulness is nobody's invariant, and a
later CR-6 must re-declare from scratch rather than trust it.

### ⭐⭐ The end-state wire — `property_id` never leaves the device

⚠⚠ **`property_id` is on the wire only because the edge is keyed by it** — two `OnDemand` properties
on one entity own different content, so an edge that could not tell them apart would let a
thumbnail's declaration evict the file's (T-R-09's CR-4 amendment, which flags it as the *opposite*
of the metadata plane's posture: `DataBlockPayload` hides the property id inside the ciphertext).
⇒ **Once edges are not authoritative, that justification is gone**, and keeping them means disclosing
the holder grouping *and* the slot name to keep a gauge alive. So the end state does not keep them:

```
POST /sync/content/describe   { "contents": [ { "contentId": "…",
                                                "refs":  ["blob-<64hex>", …],
                                                "reference_id": "<32hex random>",
                                                "hlc": "…" } ] }
                          ⚠⚠ the HLC of the ROW VERSION being described — §6e's release CAS compares
                            it against the release's delete HLC, so a describe that does not carry
                            one leaves the relay unable to tell a newer re-description from a stale
                            release, and the release wins over live content
```

⚠⚠ **`reference_id` is not optional — conflict already shares ids, and P4 (dedup) only makes it common**, and it is easier to carry from the start
than to add later: without it this payload cannot express *which* of two rows sharing a `contentId` is
describing, so a client that deletes one row asks to free the whole object while the other row still
needs it — the loss (ii) exists to prevent. It is a **random** handle (below), so the relay sees 32
opaque hex it can compare but never interpret.

- The relay learns **content → blobs**, and **how many distinct references** exist for that content —
  what it must know to free anything, and what it half sees at `PUT` time regardless. It learns
  nothing about entities, slots, or *which* rows share content **from the handles themselves**: they
  are **independent random values**, so one holder's references look unrelated across contents by
  construction.
  ⚠⚠ **A public derivation gives part of that back, and the claim must not stand without it.**
  `derivedThumbnailContentId` is an **unkeyed** `sha256("thumbnail:$fileContentId:$class")`, and the
  relay holds every `contentId` — it is the primary key of `content_ref` and of the proposed
  `content_reference`. Five hashes per file therefore recover, for any Drive file, which five content
  objects belong together *and which slot each is*: the (holder, property) grouping `content_edge`
  disclosed, minus only the holder hash — which was itself an HMAC rather than a name. ⇒ either the
  derivation becomes **keyed** (`HMAC(K_entity, …)`, the treatment §6e already gives the collision
  replacement, for exactly this reason: the relay must not be able to evaluate it), or the
  disclosure claim is narrowed to "no holder name" and the grouping goes into the amendment as
  retained. ⭐ Keying it is cheap and makes the claim true as written. ⚠ Not an HMAC —
  a keyed form cannot survive a shared-realm key rotation (below), and a random handle needs no key to
  be opaque.
- `content_ref` + `content_blob` + **`content_reference`** are the durable content tables.
  ⚠⚠ **The handles need a table of their own:** `content_ref`'s primary key is
  `(owner_id, content_id, ref)`, so it can hold *blobs per content* and nothing else — storing handles
  there would either overwrite them or duplicate every chunk row per handle. `content_reference` is
  `(owner_id, content_id, reference_id, last_hlc, last_declared_seq)`, one row per live reference, and
  the count the relay acts on is a `COUNT(*)` over it. ⚠⚠ The last two columns are not decoration:
  the release CAS compares against a **stored latest HLC per handle**, and the enumeration sweep is
  applied only to handles whose last describe is at or before the walk's captured cursor — neither is
  expressible over the three-column shape an earlier revision gave. ⚠ And `last_declared_seq` is a
  **metadata-log** sequence, which is only meaningful because the declaration now rides the log as a
  block (above); a cursor taken from the content plane would compare a clock against a sequence from
  an unrelated domain, which is the error this section warns about a few lines up. ⚠⚠ **Which needs a per-owner cap, because rows are free.** A device — buggy
  or hostile — can call `describe` with a fresh random `referenceId` for one `contentId` indefinitely,
  inserting a row each time and uploading **no bytes**, so the byte quota does not bound the relay's
  database at all. That is T-R-09/§9.4's declaration-row inflation in a new table, and it inherits the
  same answer: a per-owner row cap, refused past it, rather than a limit that only counts bytes.
  ⚠⚠ **But a refusal cannot be quiet, or the cap becomes a data-loss mechanism.** A rejected
  `describe` leaves a **live synced row with no `content_reference`** — and once N6 removes the edge
  fallback, deleting some *other* reference to that content takes the count to zero, while N7 reads
  the wholly-undescribed case as an orphan. Either path frees blobs a live row still names. ⇒ hitting
  the cap **freezes byte frees for that owner** — no delete-driven reclaim, no enumeration *freeing* —
  until the backlog is described or the cap is raised. ⚠⚠ **But not the operations that DRAIN it**, or
  the cap is a deadlock: releases still apply and the enumeration pass still **sweeps stale
  `content_reference` rows**, because removing a row frees no bytes and is the only way the owner gets
  back under the cap. ⇒ the freeze is on *deleting blobs*, never on *deleting rows* — otherwise a
  buggy device's spam can only be cleared by an operator, which is not a quota mechanism but an
  incident. ⚠⚠ **But the freeze must LATCH, not track the count.** The rejection is the relay's only
  evidence that its reference set is incomplete; if releases and sweeps then drain rows back under the
  cap and freeing resumes from the current count, zero-count quarantined content can be deleted
  **before the rejected handle is ever retried**, while its row is still live. ⇒ an owner-level
  **incomplete latch**, set on rejection and cleared only by an explicit *reconciliation-complete*
  acknowledgement — never by the number merely falling.
  ⚠⚠ **And one acknowledgement is not the condition — "every device, at or after the rejection" is.**
  A bare ack lets device B report complete before it has even ingested A's row, or after one of the
  falsely-complete walks §9 documents elsewhere; the latch then lifts while A's rejected handle is
  still absent from `content_reference`, which is the data-loss path it was set to block. ⇒ the latch
  records the **relay log position at the rejection** — its `nextSeq`, the metadata mailbox's own
  cursor — and clearing requires an acknowledgement from **every** registered device of that owner
  carrying a *consumed pull cursor* at or past it, *and* the owner back under the cap.
  ⚠⚠ **A content-plane counter cannot stand in for that, which an earlier revision got wrong.** A
  sequence advanced by `describe` calls is not causally tied to ingesting the metadata row whose
  handle was refused: if the refused device is later removed, another device can pass the threshold
  through an entirely unrelated describe and acknowledge while it has still never pulled the live
  row — and every remaining device then satisfies the rule. The bound has to be over the **log the
  row travels on**. ⚠ If the row had not yet been pushed when the describe was refused, the retry
  after the push is refused again and moves the bound forward, so the conservative `nextSeq` snapshot
  self-corrects rather than under-covering.
  ⚠⚠ **The device that was refused is the one that makes this sound**, and its obligation is the rule:
  a device must not acknowledge complete while it holds a reference the relay **told it** it refused.
  ⚠ That is deliberately not a walk result — a walk can be wrongly complete, where a `429` the device
  recorded durably cannot be. The honesty required is about a response it received, not an enumeration
  it performed. ⚠ A device that lies here costs its own owner's content — the cap, the latch and the references are
  all owner-scoped. ⚠⚠ **Which is not the same as "its own user's" in a shared realm**, and the
  difference is the whole point of one: `SharedFile.contentId` lives under the *shared realm's*
  `ownerId`, whose registered devices belong to several people. So one member's spam trips the
  **realm's** row cap, and the latch clears only on an ack from every registered device of that owner
  — one member's wedged or departed device freezes every other member's byte reclamation. ⚠ A device
  also runs N+1 independent HLC domains (personal, plus one `HLCManager` per shared realm), so a
  release stamped from the wrong clock is ordered against the wrong sequence; the existing
  reclamation recorder already carries that as a latent bug in a comment — *"the day a shared-realm
  delete exists, this must resolve that realm's `HLCManager` … It would fail rarely and silently"* —
  and N4's generic capture is the day that arrives.
  ⚠ A hostile device that simply keeps spamming refused describes keeps pushing the rejection value
  forward and the latch never clears — storage held, nothing reclaimed, which is the direction this
  whole mechanism chooses. ⭐ The right failure direction: an owner over the cap
  stops *reclaiming*, which costs storage, rather than continuing to reclaim against an incomplete
  picture, which costs data. **`content_edge` has no writers and goes** — it was never CR-6's seed (§11 needs a log-ordered transport either way), so keeping it
  unmaintained would be dead schema cited as a gauge.
- **`undeclare` retires with it**: no holder name ever reaches the relay.
- **`orphan-candidates` retires too** — "unreferenced" stops being computable relay-side, which is the
  point.
- **The gauge shrinks honestly**: blobs, described contents and undescribed blobs stay computable;
  `candidateContentCount` disappears rather than silently rotting.

⭐ **The knock-on is a point in its favour.** The folder cascade and the EI-1 holder-naming fix
become dead code: no holder name is sent, so none can be named wrongly or go stale. The class
disappears from the system rather than being maintained in it.

⚠ **What stays disclosed:** which blobs form one content object — accepted at **Low** in T-R-09's
CR-4b amendment, and the same set ST-3b's `/sync/content/delete` has sent since §4.9.

### ⭐ Phasing, including CR-6

Each step is safe to stop at, and the order is the dependency order, not preference.

| | step | why here |
|---|---|---|
| **N0** | **Age window on the relay** — exclude blobs younger than *X* (`last_referenced_at`) from **enumeration**: the contents listing and any unfenced reclaim | ⚠⚠ The in-flight upload is the one case no walk can see. ⚠ **Not** the delete-driven path: a file uploaded and then deleted has a fresh `last_referenced_at`, and excluding it would refuse exactly the reclaim N4 promises frees space at once — so a D3-authorised delete names its own contentIds and bypasses the window |
| **N1** | ⚠ **Additive, and the cost is a baseline line** — the handles are **named value properties**, the shape §9.12 records for `Workout.ContentId`. ⚠⚠ **But this row's original claim — *"no service-version bump and no migration: only a `PropertyKindBaseline` line"* — was WRONG and is what shipped corrected.** It cited health's `Workout.ContentId`, which took no bump; file-service's own v8 ledger says the opposite for six analogous additive scalars, on "the other half of HR #9" grounds (a peer that predates the property must be fenced, not asked for it). N1 landed **file-service 13→14 and health-service 2→3**, each with an empty `SchemaMigration` step — ⚠⚠ and the step is not optional: the version floor is *derived* from the chain, so a bumped service without one sits below its own floor and the store is **WIPED**, while `SchemaMigrationRegistry.verify` refuses to open a store at all. Shipping the bump without the step was caught in review, not by a test; `ServiceMigrationChainsReachTheirVersionTest` now catches it. ⚠⚠ A **positional** field would owe an HR #9 bump and a wipe risk for nothing, so it must not be one. ⚠⚠ **One handle per content-bearing property, not one per entity** — a `File` declares **six** references (`Data` plus five derived thumbnail rungs — LIST, LARGE, XLARGE, XXLARGE, MOTION; an earlier revision of this row said five, written before XXLARGE (X-1) and MOTION landed, and §6e's own prose below says six), and a single slot cannot preserve or release them independently: two properties can share a `contentId` today — a divergent rename or rename-vs-edit does it with no dedup involved — and one handle would then collapse two references, so clearing either frees the other's bytes. P4 only makes it common. ⚠⚠ `SharedFile` needs them too, and its `DATA_PROPERTY_ID` is a bare constant rather than a registered `OnDemand` property — which costs more than the delete half. `SchemaDrivenContentWalk` enumerates *registered* relations, so the property is **absent** rather than unenumerable: `sawEverything` stays true and the walk reports **Complete** with every shared file's content missing. Today the edge prefilter hides that (declared − referenced never offers it); C′'s diff is `listed handles − live handles`, so the first pass over a shared realm would call every shared file's content stale. ⇒ registering it is a **prerequisite**, not a tidy-up: without it N4's capture cannot find the content to release *and* the enumeration lies about being complete. **The describe wire** — refs *and a `referenceId`* per `contentId`; the relay accepts it, clients **keep declaring the edge too** (the describe also carries the edge's `(holder, property)` so N1b can correlate) | ⚠⚠ additive **only** while it dual-writes: see below |
| ~~**N1b**~~ | ~~Derive handles for references that predate the wire~~ — **DELETED 2026-09-19**: the realm reset emptied `content_edge`, so there is no pre-wire population to derive from. N6 no longer gates on it. |
| **N1c** | **The deferred-content journal keyed on its content** (§6d.1): `PendingContentEmit` keyed `(contentId, propertyId)`, carrying the `contentId` and the reference handle; the row it names becomes a holder *hint* and its path a byte *location*. ⚠⚠ A stored reshape of a local-only type, so **sync-service 7→8 with a non-empty `SchemaMigration`** (both fields derive empty, which the drain reads as a legacy row and upgrades) — the N1 row above records what a bump without its step does. ⚠ Nothing crosses the wire: `PENDING_CONTENT_EMITS` is not in the service's `relations`, it is a device-local relation built from a `RelationDataId`, so the bump is about the readable floor and the stored layout, not about peers — but the version vector still moves, so every schema peer (the web apex bundle included) moves in lockstep | ⭐ worth doing even if D3 were abandoned — a peer's move lost the upload without it. ⚠ **N3's durable move intent is built on this key**, and carries no content-intent state because of it |
| **N2** | **`GET /sync/content/contents`** — paged described contentIds (rewritten from holder names) | the read the client pass needs |
| **N3** | **D3** (§6d): the fence, describe-then-verify, the repair pin, the writer-scoped capability floor | ⚠⚠ **Before** anything frees without the barrier — it is what replaces the relay's re-check |
| **N4** | **The authority flip**: the delete names what it orphaned and reclaims on the same tick behind a durable **re-check** queue | ⚠⚠ **SUPERSEDED by CR-2 candidate B (G2)**: the relay's own sweep is the only freer. Was: the actual change of design — the *client's delete* names the candidate, not `described − referenced`. ⚠⚠ Neither half drops the edge **prefilter** here: enumeration keeps it until N7's gate, and the delete half keeps it to answer the shared-id question until N6 |
| **N5** | **P3 / CR-2**: remove ST-3b — barrier, candidate ledger, recorder (the retry queue stays) | nothing depends on it once N4 is live — ⚠⚠ except the **overwrite** case the recorder also carries, below |
| **N6** | **Retire `content_edge`**, `declare`'s edge half, `undeclare`, `orphan-candidates`; gauge loses its candidate count | ⚠⚠ **SUPERSEDED by CR-2 candidate B**: `content_edge` stays as G1's register table. Was: ⚠⚠ **not** mere cleanup: it takes away the last relay-side answer to *"does anything else name this id"*, so it inherits N7's prerequisite — **§11's log-ordered description** — **and** owes N4's capture being generic, both below |
| **N7** | **CR-6** — the undescribed 2.67 GB: `blobs − refs(described)`, gated on **§11's log-ordered description** plus cursors past *S* and N0's window | ⚠⚠ last, because it is the only step that deletes on inference and the only irreversible one |

⚠⚠ **N4's capture must be GENERIC, or N6 leaks every non-Drive delete.** `undeclare` is keyed by
holder and **not filtered by type** — it is the one path that frees any service's content today.
N4's replacement, as sketched, leans on `DeleteFileImplementation` collecting `orphanedContent`,
which is Drive's hook. ⚠⚠ `DeleteWorkoutImplementation` removes the row and emits a tombstone
**without reading `Workout.ContentId` or enqueueing anything**, and since §9.12 those workouts *are*
described — so after N6 their `content_ref` rows stand, N7's `blobs − refs(described)` reads them as
live, and every content-bearing workout deleted from then on leaks charged storage for ever. ⇒ N4
owes a **pre-delete capture at the sync layer**, over every entity carrying an `OnDemand` property
(the seam §5.2 already argues for), feeding the re-check queue. Until that exists, N6 may not ship.

⚠⚠ **And "pre-delete" is the wrong name for it: an overwrite orphans content with no delete at all.**
`UploadFileImplementation` replacing a file emits the new content and hands the old
`supersededContentId` to the same recorder — no entity dies, no tombstone is written, so a
capture hung off the delete path never sees it. N5 removes that recorder. If N4's replacement is
specified as a *delete* hook, then after N6 every overwrite leaves `content_ref` rows nothing
references, N7 reads them as live, and each save of an edited file leaks its predecessor's bytes for
ever. ⇒ the capture is over **superseded content**, of which a delete is one source and a replace the
other.

### ⚖️ Why the reference root lives ON THE ROW — and the objection to it, recorded

**The objection (maintainer, 2026-09-21), and it is a fair one.** `File.ReferenceRoot` /
`Workout.ReferenceRoot` / `SharedFile.ReferenceRoot` are sync-plane bookkeeping sitting in each
domain type's public API. They are *useless outside their context* — unlike `File.ContentHash`,
which a client could reasonably read for caching. The proposed alternative was a map
`(DataId, property) -> referenceRoot` owned by the sync service.

**Why the row won, and it is lifecycle, not elegance.** A side store is one more thing that must
reach a new device, in the right order, and stay in lockstep with a row it does not live in. The
joiner path already cannot carry the root — `FileSliceEntry` has no field for one, which is one of
the recorded rootless populations above — so a separate store makes that gap worse rather than
better. On the row, the root arrives with the row, dies with it, and is copied with it.

⚠ **The usual argument against a side table does NOT apply cleanly here, and saying so was an
overstatement.** "It is `content_edge`'s failure mode client-side" is too strong: if a mapping row
drifted, `referenceHandleFor` would answer null and the emit would describe no handle — the
*recorded rootless-row* state, which degrades to pre-N1 rather than freeing anything. `content_edge`
drift caused deletions; this would not. The lifecycle argument is the one that stands.

⭐ **What would reopen it:** a sync-owned store that rides the **same block** as the row it belongs
to. Then it travels and dies with the row, and the encapsulation is free. That is worth revisiting
when the joiner-slice gap is fixed, because both touch the same seam — and at that point the trait
requirement makes the swap cheap: the content plane resolves the root through
`ContentReferencing.REFERENCE_ROOT` and never names a domain type, so only the fulfilment moves.

⭐ **Superseded 2026-10-01: the roots are RETIRED, not moved.** Under CR-2 candidate B the
`(holder, property)` edge registers are the authority, so the handles, and the roots that exist only to
derive them, have no job (`content-plane-cr2-quorumless.md` §10.8). The maintainer decided to remove
them in B6 rather than build the sync-owned map discussed above, which would also need persisted `TMap`
support. Do not build that map.

### ⚠⚠ One handle legitimately names TWO contentIds — the diff must be over PAIRS

An **overwrite** does not change the `DataId`, so it does not re-mint the root (only a re-key may —
and even that is deferred, see below). The row's handle `H` therefore names the superseded content
*and* its replacement: after an edit the relay holds both `(cid_old, H)` and `(cid_new, H)`.

That is correct for **derivability** — the deleting device can still name `H` to release either — but
it makes the enumeration diff ambiguous if it is computed over handles alone. The N1 row's shorthand
*"C′'s diff is `listed handles − live handles`"* is loose: `H` is named by the live row, so a
handle-level subtraction classes `(cid_old, H)` as live, the superseded content is never swept, and
every edited file pins its predecessor's bytes once N5 retires the overwrite recorder.

⇒ ⭐ **the subtraction is over `(contentId, referenceId)` PAIRS, not handles.** §6e's own wire already
has the right shape — the listing returns `{ contentId, referenceIds[] }` and a reclaim names
`{ contentId, referenceId }` — so this is a precision fix to the prose, not a change of design. ⚠ But
it is load-bearing for N4/N7 and was not stated anywhere: a reader implementing "listed handles −
live handles" literally would build the bug.

### ⚠⚠ Rows that carry a contentId and NO reference root — owed before N6

N1 mints a root in the three creating operations. Three populations miss it, and each is content
that can never be released once N6 retires the edge plane:

1. **Every row that predates the N1 client build.** Nothing backfills a root, and CR-4d's backfill
   declares **edges only** (`SyncProxyInitialization.kt`, `declare = { edges -> … }`) — so a pre-N1
   file gets `content_ref` rows, is listed by N2's `/sync/content/contents`, and carries zero
   handles for ever. ⚠ For *this* realm the population is empty — ⭐ **re-confirmed 2026-09-21**,
   and on firmer ground than before: all three devices have now re-onboarded, each onto an N1
   client, so the rows they created carry roots. (The 2026-09-19 justification rested on "none has
   re-onboarded", which is no longer true — the conclusion survives, its reason changed.) It is not
   empty for any device that sits out a future reset, so the backfill owes a describe half.
2. **The C8 joiner slice path.** `FileSliceSnapshotProducer` materialises `File` rows with
   `ContentId`/`ContentHash` and no root, and `FileSliceEntry` has no field for one. The window
   closes when the authoritative entity block lands, but a thumbnail minted on that device before
   it does describes content with no handle.
3. **Shared-realm content**, which is undescribed entirely: `shareContentToRealm` copies chunks
   and PUTs a re-keyed manifest but declares nothing — no edge, no describe. `SharedFile` binds a
   reference root all the same, so the row is ready the day that path declares; today the root is
   write-only.

4. ⚠⚠ **A fourth, measured 2026-09-21 — and it fits none of the three above.** On the freshly reset
   realm, with every row created by an N1 client, `content_edge` ran exactly **28** ahead of
   `content_reference`, and every one of the 28 was `property_id = tenshu/file/thumbnailMotion`:

   | property | edges | handle |
   |---|---|---|
   | `data`, `thumbnail`, `thumbnailLarge`, `thumbnailXLarge`, `thumbnailXXLarge` | 402–403 each | ✅ |
   | **`thumbnailMotion`** | **28** | ❌ **0 of 28** |

   ⚠ It is **not** a rootless row. `referenceHandleFor`'s rootless branch reports once per ROW (its
   own comment: "a `File` has SIX content-bearing properties"), so a root-less row would strand all
   six *and* print. Five had handles on the same rows — the same `holder_hash` — so the root was
   there. All six properties are declared identically (`traits = listOf(Syncable(OnDemand))`), and
   `ThumbnailClassMinter.store()` emits through the same `emitFileContent(targetEntityId = dataId, …)`
   with the same `dataId`, at `UploadFileImplementation:522`, well after the row is saved with its
   root at `:358`.

   ⚠⚠ **Cause unestablished.** An earlier revision of this note named the CR-4d backfill; that was
   inference, and the `store()` reading above undermines it. The open hypothesis is that
   `referenceHandleFor` wraps its root read in `runCatching { … }.getOrNull()`, so a *thrown* read is
   indistinguishable from a missing one — and motion rendering may run in a context where the read
   throws while the inline `data` emit succeeds on the same row. Discriminator: the rootless branch
   `println`s once per row, and `println` from commonMain reaches Android logcat under `System.out`.
   ⚠ Re-measuring needs edges **intact** — after a delete `content_edge` is 0 and the property
   breakdown is gone, so capture during an upload.

   ⇒ **N6 gates on this too.** The list above is not known to be complete, and this one was found by
   measuring the relay rather than by reasoning about the code.

⚠ None of these is reachable as a *fault* today: nothing frees on handle count. All four are
prerequisites for N6, and the emit now prints a line naming the type whenever it describes without
a handle — ⚠ but only on the *rootless* branch: `referenceHandleFor`'s two other `return null`
paths (row not found, type binds no reference-root requirement) report nothing, which is why
population 4 was silent.

⚠⚠ **And the HR #9 bump has an operator half.** `tenshu-sdk-js` registers `fileServiceCommonModule`,
so the deployed **web bundle is a schema peer**: it must be rebuilt and redeployed in lockstep with
file-service v14, or web Drive sits one service slot behind — which wipes its store. "The realm was
reset so no peer predates it" is evidence about the relay's tables, not about installed builds.

⭐ **DONE 2026-09-21.** apex rebuilt and deployed as `d08791f1`, verified byte-identical to the built
image and carrying `referenceRoot` (the live bundle before it had **zero** occurrences). ⚠ Two
things worth recording from that deploy: the apex is the *only* deployed web artefact — `web/drive`
is not packaged and `drive.tenshu.app` does not resolve — and `deploy/scaleway-apex/terraform.tfvars`
had drifted to the *other* box's gate values (`construction_code` 191919 vs the live 171716, and
`signup_disabled` **absent**, i.e. defaulting to signup OPEN), so building from tfvars would have
changed the public site's gate and reopened signup. Build-time-inlined values live only in the
bundle; recover them from the running one.

⚠⚠ **And the relay is a peer too, which this section did not say.** N1's relay half (migration
20) had never been deployed: the box was still running `7b8a1a91` from 2026-09-14, six relay commits
behind, with `schema_version` topping out at **19** and no `content_reference` table at all. The
client would have described handles into a relay that could not store them — and, because the relay
installs ContentNegotiation as a bare `json()` (ktor's `DefaultJson`, no `ignoreUnknownKeys`), the
new `referenceId` field would most likely have been rejected outright rather than ignored. Deployed
as `d08791f1` on 2026-09-21; `schema_version = 20` and `content_reference` confirmed present.

### ⭐⭐ N1b is deleted — the realm reset removed the population, 2026-09-19

**Measured, not assumed.** After the production reset the relay answers `content_edge = 0`,
`content_ref = 0`, `content_reference = 0`, `device = 0`, `realm = 0` — every table but
`schema_version` (19) and `relay_seq` (1). The as-built record below said `content_edge = 7996` /
`content_ref = 9879`, of which **7,990 carried the pre-EI-1 decimal local hash**. All of it is gone.

⇒ the two conditions the standing note set are met: the reset **preceded** N1 (the client half is
unbuilt, so no device has described anything) and it covered **every** device (`device = 0`, and
nothing has re-registered since).

⭐⭐ **Closed out 2026-09-21 — both conditions now hold after the re-onboard, not merely before it.**
The realm was reset a second time (relay `d08791f1`, `schema_version = 20`, every table 0) and all
three devices re-onboarded onto N1 clients: desktop 11:20:35Z, OnePlus 11:35:10Z, iPhone 11:43:57Z —
one realm, three device cursors. So the pre-wire population is empty *by construction* rather than
by absence of devices, and N1b has nothing to come back for.

⭐ **N1 verified end to end the same day.** First upload produced `POST /sync/content/declare` ×26,
**all 200**, and `content_reference` took its first rows ever (2,014 at peak, 1.0 references per
content). The handles are the `sha256("reference:$root:$propertyId")` values, opaque to the relay —
C′ §6e working as specified. ⚠ Two defects surfaced in the same run and are *not* N1 bugs but do
block N6: the delete path maintained neither index table (since fixed), and population 4 above.

**What that deletes, and it is more than one phase.** The hazards below were specific to that corpus:
the legacy-named edges that would have given most live references **two** handles and made the
existing corpus permanently unreclaimable; §9.14's reconciliation as a hard prerequisite; and the
denominator problem — a migration that could not be proved complete, guarding a failure that is
silent and permanent. None of them has anything left to act on.

⚠⚠ **And it deletes the transitional field.** N1's describe was to carry the edge's
`(holder, property)` *purely* so the relay could correlate dual-written references at migration.
There is no migration, so the wire stays holder-less — which is what N0–N2 shipped anyway, before
this decision was taken.

⚠ **The residual risk, stated plainly:** references created between now and the client half of N1
shipping are still pre-N1 references. They carry an edge and no handle, and N6 could not release
them. That window is open only while the realm is unused, which is why the client half is the next
piece of work and why the realm should not be re-onboarded before it lands. If it is, the *simple*
form of the rule below (an edge with no `content_reference` gets a derived one) applies to a small
clean corpus with no aliases — a fraction of what this section specifies, but not nothing.

**The reasoning below is kept as the record of why it was needed, not as work to do.**

⚠⚠ **Every reference that predates N1 has no handle, and nothing re-runs a latched backfill.** CR-4d
has already recorded itself complete on deployed devices, so its pass will not revisit those rows;
N1 adds the wire and the dual-write but mints handles only for references created *after* it. If N6
then removes the edge plane, deleting one of those older rows cannot name the `(contentId,
referenceId)` pair the reclaim requires — the delete has nothing to release, and the bytes stay
charged for ever. ⇒ **N1b** — ⚠⚠ **but not as a coverage figure, because there is no sound denominator.** The relay can
count `content_reference` rows and cannot enumerate encrypted live references, so it has no total to
compare against; and `content_edge` cannot supply one either, since §9.14 leaves thousands of legacy
and deleted stale edges and its reconciliation is unbuilt. A coverage gate would therefore either
trust the same falsely-complete client walk it exists to replace, or never reach "total" at all.

⚠⚠ **But "the edge table" has to mean the edge table *as it was*, or N1b double-counts.** During
N1's dual-write every new reference writes **both** a random `referenceId` **and** an edge — so a
naive later transform derives a *second* handle for a reference that already has one, and deleting it
releases only the stored random one while the derived twin holds the count above zero for ever. ⚠⚠ **And it cannot run at all while the legacy-named edges are still there.** §9.14 records the live
state: the 7,990 edges declared under the pre-EI-1 local name **remain alongside** the v5 wire-name
edges added for the same rows. Transform each edge independently and most live pre-N1 references get
**two** handles — a later delete recomputes and releases only the current wire-name-derived one while
the legacy alias holds `COUNT(*)` above zero, so the existing corpus becomes permanently
unreclaimable. The N1 dual-write correlation does not help: both aliases predate N1. ⇒ **§9.14's
reconciliation runs first**, removing the legacy-named edges, and only then does N1b derive. ⚠ A
third independent reason the realm reset is the better path — it deletes this problem rather than
sequencing around it.

⇒ ⭐ state it as an **idempotent rule** rather than a snapshot: *an edge with no
`content_reference` gets a derived one; an edge that already has one is left alone.*
⚠⚠ **And "already has one" has to be answerable per REFERENCE, which `content_reference(owner,
content, referenceId)` cannot do on its own** — it carries no holder or property, so a content-level
check cannot tell a dual-written reference from a legacy edge sharing that `contentId`, and with two
conflict rows on one content it skips the legacy edge entirely. ⇒ during N1's dual-write the
`describe` carries the **same `(holder, property)` the edge already carries**, purely so the relay can
record the correspondence and skip that edge at migration. ⚠ No new disclosure: the edge on the wire
beside it says exactly that, and both end together at N6 — it is transitional scaffolding with the
same lifetime as the thing it correlates. Dual-written
references have a random handle already, so they are skipped by construction — and the rule is safe to
**re-run**, which matters because a snapshot taken at N1 misses every edge an un-upgraded client
writes afterwards (the writer floor only arrives at N3). ⇒ run it at N1b and again when the floor
lands; the derived id is deterministic, so repetition converges rather than duplicating.

⭐⭐ **So derive the legacy handle instead of minting it — the edge table *is* the pre-N1 reference
set.** Every `content_edge(owner, holder, property, content)` already names exactly one reference, so
the relay transforms each into `content_reference(owner, content, referenceId = H(holder‖property))`
— a migration over data it already holds, with no client walk, no marker and no denominator. The
client can compute the identical id for its own legacy rows (it knows the path and the key that make
the holder hash), so a delete of a pre-N1 row can name its handle exactly.

- ⚠ **Derived means holder-derived, so it moves when the row moves** — the very property random handles
  exist to avoid. That is acceptable *because it is transitional*: the first mutation of a legacy row
  re-mints a random handle, describes it, and releases the derived one it can still compute. Legacy
  ids self-heal on first touch, and a row never touched never needed the stability.
- ⚠ **Stale edges become stale reference rows**, which is the leak that already exists today and is
  what the handle-level enumeration diff above is for. The migration neither creates nor fixes it.
- ⭐ **"But N6 removes `property_id` — does the derivation still work afterwards?"** Yes, and the
  reason is worth stating because it is not obvious from the relay's side: **the client never lost the
  property.** `property_id` leaving the *wire* means the relay stops seeing it, not that the device
  forgets which slot holds which content — so a client deleting a never-touched legacy row can always
  recompute `H(holder‖property)` and name it exactly. The relay never recomputes anything: it
  **stores** the derived value at N1b as an opaque handle, while `content_edge` still exists, and from
  then on only compares it. ⇒ the two sides need the property at different times, and each has it when
  it does.
- ⚠⚠ **And the derivation itself does not survive a realm-key rotation, which is a third reason to
  prefer the reset.** The relay stores `H(oldHolder‖property)`, but a client recomputes the holder
  from the **current** `sharedVolumeKey` — `WireIdResolver` resolves holder names from the current key
  only, and retained historical keys exist for trial-decryption, not holder resolution. So if a shared
  realm rotates (a member removal) before a legacy row's first mutation, that row's later re-mint
  computes a *different* handle, cannot release the migrated one, and the content stays charged for
  ever. ⇒ if N1b ships at all, it owes a client re-describe for any realm that rotates before its rows
  are touched — which is the walk the derivation was chosen to avoid.
- ⭐⭐ **A realm reset before N1 deletes this whole sub-phase — maintainer's call, 2026-09-17.** N1b
  exists only for references that predate the wire. The realm is to be reset for the next test round
  and no external users are onboarded, so if that reset lands **before** N1 there are no pre-N1
  references to derive handles for: no migration, no derived ids, no transitional grouping, and the
  denominator question never arises. The same reasoning the project already applies to format bumps
  pre-release — a reset is cheaper than a migration nobody will ever need again.
  ⚠ Two conditions keep it honest: the reset must actually **precede** N1 (a realm that starts
  describing before it is wiped re-creates the problem), and it must cover **every** device in the
  realm — a device that sits out the reset still carries pre-N1 rows and still needs the derivation.
  ⇒ keep N1b specified, mark it **conditional**, and delete it from the plan once the reset is done.
- ⚠ **The residual below is therefore likely moot**, and is kept only for the case where the reset
  does not precede N1.
- ⚠⚠ **The residual is privacy, not correctness.** A derived handle encodes the holder grouping — in
  hashed form — that §6e set out to remove, so retiring `content_edge` does not erase that grouping
  for rows nobody has touched since N1. Nothing *new* is disclosed (the relay held those edges in the
  clear already), but the property is weakened until each legacy row's first mutation re-mints a
  random handle. ⚠ If that matters more than the traffic, the alternative is an eager re-mint pass —
  which buys the privacy back at the cost of the client walk this derivation exists to avoid.

⚠⚠ **N1 must dual-write, and this is the phasing's one real hazard.** Until N4 the relay's candidate
predicate is still `described − referenced`, so a client that describes content **without** declaring
its edge creates a row that is described and referenced by nothing. Once N0's window expires the relay
offers it as an orphan; a peer that has not yet received the entity fails its live-walk veto, the
reclaim's edge re-check finds nothing either, and a **live upload is deleted permanently**. ⇒ either
clients keep declaring the edge through N4, or client adoption of `describe` moves into N4 itself.
The wire may land early; dropping the edge half may not.

⚠ **EI-4 is parallel, not a phase here.** The tombstone's clear `target_entity_type`
(`sync-entity-identity.md` §5b) is the metadata plane's last type-level disclosure, and nothing in
N0–N7 depends on it: it may land before, during or after. Listed so it is not mistaken for part of
this sequence — or forgotten because it is not.

⚠⚠ **P4 (dedup) has a precondition that has nothing to do with counting: the manifest.** A content's
manifest address *"derives from the content id alone, so sharing one makes the second property
overwrite the first's manifest"* (`Syncable`'s own contract). So two references on one `contentId`
break **readability** before reference counting is even reached — the second describe overwrites the
first's manifest and the first row stops resolving. ⇒ P4 depends on separating the shared content
**address** from the per-reference **manifest**, and that is a larger change than the refcount work
below. ⚠ Until it exists, "dedup lands after N4" is not a schedule, it is a placeholder.

⚠ **P4 otherwise gets simpler after N4**: two rows sharing a `contentId` is
just a live set that names it, with no edge upsert to reason about. ⚠⚠ **But it does NOT make the shared-id question bind — conflict already did.** An earlier revision
said ids are random per content write, so dedup is what first puts one id on two live holders. That
is wrong on shipped behaviour: a **divergent rename** leaves `/y` and `/z` both live on one
`contentId`, and rename-vs-edit does the same, with no dedup anywhere (§6e). ⇒ handles and
**minting a fresh one per re-key are an unconditional N6 prerequisite**, not a P4-dependent one:
without them, deleting either branch of a conflict releases the sole reference and N6 frees bytes the
other branch still names. **P4 only makes the sharing common.** ⇒ P4 after N6 requires the
relay-side answer chosen there; P4 before N6 rides the edge plane and is free. The ordering is the decision, not the dedup. ⚠ **EI-2 leaves this track's
critical path** — a move preserving the wire id mattered for holder bookkeeping, which N6 deletes.

⚠⚠ **But §11 as written is an EDGE design, so "N6 inherits it" is a contradiction until it is
restated.** §11.3's move one has the relay *upsert `content_edge`* from a declaration block, and
§11.5's `described(S)` reads `edge(H, C)` and the holder's tombstone. N6 removes exactly that. Taken
literally the phasing deletes the mechanism its own two later steps depend on. ⇒ §11 needs a
**holder-less restatement before N6**, and the shape is forced by what each move is *for*:

- **Move one becomes: the `declaration` rides the log — `(contentId, referenceId, HLC)` and nothing
  else.** ⚠⚠ Not the whole `describe`: its `refs` array is variable-size and large — §10.7 puts one
  2 GiB file at roughly **72 KB of refs** — and §11.3 keeps exactly that set off the ordered log on a
  journalled, seq-stamped side route, because replaying it to every joining device is the Control OOM
  the two-plane split exists to prevent. What `described(S)` needs is the *fact* that a reference
  exists at a point in the order, which the fixed-size triple carries; the blob set is content-plane
  detail nobody reads to answer that question. ⇒ the triple on the log, the refs on the side route
  with their seq stamp. The privacy goal and the ordering goal do not conflict here; §11.3 only
  carried the holder because B did.
- **Move two becomes: the reclaim rides the log too.** Under N4 the client names what to free, and
  ordering that against `S` is what replaced the holder's tombstone in `described(S)`. A reclaim on a
  side-channel route is unordered against the log, so it cannot appear in a deterministic function —
  the same objection §11.3 makes against today's `declare`.

⚠⚠ **What that leaves open is the shared-id question, and it is a decision, not a detail.** With
holders gone the relay knows *described* and *reclaimed*, which cannot express "two live rows name
this id": one holder's reclaim would free content the other still uses. Three ways out, and they
trade the same axis §6e was chosen on —

| | what the relay learns | cost |
|---|---|---|
| **(i) client-arbitrated** | nothing new — the reclaim is only sent once the *client's* walk says no row names it | inherits walk completeness for the shared case, the hazard two sections up |
| **(ii) reference handles** | a **count** per contentId: the describe carries a stored **random** `referenceId`, the reclaim names it | a refcount without holder names — independent randoms, so nothing links one holder's references across objects, which was the grouping disclosure §6e set out to remove. ⚠ Not an HMAC: see below, a keyed form cannot survive shared-realm key rotation |
| **(iii) keep the edge plane** | today's answer | N6 does not happen |

⚠⚠ **(ii)'s key must be content-INDEPENDENT, with the contentId in the message.** An earlier
revision wrote the token as `HMAC(K_content, …)`. A per-content **key** is derived from that content's
material, and `ContentRepair` *"mints a fresh CEK"* on every repair — same `contentId`, new key, new
token for the same logical reference. The relay must keep distinct tokens apart (that is the whole
mechanism), so it cannot know the new one supersedes the old: a later reclaim names only the new
token, the stale one holds the count above zero, and the content is charged for ever. ⇒ ⚠ this was the first correction, and it did not go far enough: moving the
`contentId` into the message and the key to realm scope survives a repair but **not a shared-realm
key rotation**, which is why the selected design below drops the keying altogether in favour of a
stored random handle. Recorded because the reasoning is the same one step further on, not because
`HMAC(K_entity, …)` is the answer.

⚠⚠ **And (ii)'s token must be keyed on something a move does not change — which is a REFERENCE, not
an entity.** A personal file's holder id is derived from its **path** (§10.4), so a token built from
the holder changes on every move, and C′ has retired both move re-declaration and `undeclare`: the
relay keeps the old token for ever while a later delete names only the new one, so the count never
reaches zero and the moved file leaks permanently — this track's original bug, rebuilt on the
mechanism meant to end it.

⭐⭐ **The fix is to split what `contentId` currently conflates.** Today it plays two roles at once —
the content's **address** on the relay, and the **reference's** identity — and they are
indistinguishable only because there is exactly one reference per content. **Dedup is precisely what
separates them**: two rows would share one address and need distinct references. So the content plane
carries a *pair*:

| | what it is | who shares it |
|---|---|---|
| `contentId` | the relay-visible **address** the refs hang off; stays **random**, never content-derived (a derived address is §3.1's confirmation-of-file oracle) | every row that dedups onto the same bytes |
| `referenceId` | a **random** handle minted when an entity-and-property starts referencing content | nobody — it *is* the reference |

⚠⚠ **Per (entity, **property**), not per entity.** One entity can hold several `OnDemand` properties —
`File` has `Data` plus five thumbnails — and P4 may dedup two of them onto one `contentId`. One handle
per entity would give those two references the same value, so clearing one property would release the
only handle while its sibling still refers to the content.

⇒ ⭐ **and the handle needs no key at all.** An earlier revision wrapped it as
`HMAC(K_entity, contentId‖referenceId)`, inheriting the keying from the *holder-hash* design — where
the input was the meaningful path and the HMAC was what hid it. A **random** handle has nothing to
hide: it discloses no more than itself, tokens are independent across contents by construction, and
the relay simply counts distinct values. ⚠⚠ Dropping the key also removes a defect the keyed form
could not survive: `WireEntityId.keyFor(volumeKey)` derives `K_entity` from the **shared volume key**,
and `SharedRealmGrantManager.rotateAndRegrant` replaces that key whenever a member is removed — so a
token described before a rotation could never be reproduced at delete time, and the stale value would
pin the count for ever. A stored random handle survives rotation because it was never derived from
anything.

⇒ the handle is stable across moves (value properties are preserved generically by
`include(sourceProps)` — `file-content-addressing.md` §3.3.2 step 1, shipped), stable across repairs
(no CEK, no realm key), stable across rotation, and distinct per reference without ever naming a
holder.

⚠⚠ **But "preserved by `include(sourceProps)`" is exactly what breaks it when a reference SPLITS.**
A divergent rename (`A: /x→/y`, `B: /x→/z`) leaves *both* destinations alive *"carrying the same
`contentId` ⇒ two files sharing one content blob"* — and the same blanket copy hands both rows the
**same** `referenceId`. Two references, one handle: delete either and the sole token is released,
after which N6 frees bytes the other row still names. Rename-vs-edit produces the same duplicate.
⇒ ⭐⭐ **the rule is simpler than "preserve on move, re-mint on duplicate", and the HLC scheme below
requires the simpler one: a handle belongs to a `DataId`, so every re-key mints a fresh handle.** A
move produces a row at a new key; that row is a new reference, and it describes its own handle while
the migration releases the old one. ⚠⚠ Preserving across a move is what lets one handle span `/y` and
`/z`, and §6e's release CAS is ordered by an **HLC**, which orders facts about one `DataId` and says
nothing about two — so a preserved handle is not merely awkward there, it is unarbitrable. ⚠ The
implementation note is that this is *not* the default: `include(sourceProps)` copies value properties
generically, so the re-mint has to be explicit on the re-key path.
⚠ The cost is one describe and one release on a path already writing a tombstone and a new row.

⚠⚠ **A divergent offline move is why the trigger cannot be "did I observe a copy".**
A moves `/x→/y` and B moves `/x→/z`; each performed an **ordinary move**, and afterwards `/y` and
`/z` are distinct `DataId`s — so no merge, no discard, and no local moment at which either device
observed a copy. Under the preserve-on-move rule both live rows carried the **one** relay handle:
delete either and the sole reference is released while the other row still names the content. Minting
per re-key removes that state entirely — the two branches were never one reference to begin with.
⭐⭐ **So everything from here to the enumeration diff is retired by that rule** and kept only as the
record of what it buys: no ingest-time collision check, no deterministic tie-break, no derived
replacement handle, and no key-rotation window between two replicas computing one. ⚠ The residual it
leaves behind is worth keeping in view: a *genuine* duplicate — a copy operation, if one is ever
built — still mints its own handle for the same reason, because it too is a new `DataId`.
⚠ **For the record, the mechanism it replaces:** the trigger could not be the *act*; it had to be the
**state**. On ingest, a row arriving with a
`referenceId` that another live local row already holds is a collision, and the device re-mints for
the loser of a **deterministic** tie-break (lowest `DataId` keeps it, say) and describes the new
handle. Deterministic because both devices must reach the same answer without talking — otherwise
each re-mints the other's and the count is wrong in the opposite direction.
⚠⚠ **And the replacement handle must be deterministic too, not merely the *choice* of loser.** Both
replicas ingest each other's branch, both pick the same losing row — and then both mint their own
**random** id and describe it. Row convergence keeps one; the other stays counted for ever, which is
the leak this mechanism exists to prevent, arriving through the repair. ⇒ the replacement is
**derived** from values both sides already agree on — the colliding handle and the losing row's
`DataId` — so the two replicas compute the *same* new handle and the second describe is a no-op.
⚠⚠ **Keyed, though, or it is the confirmation oracle again.** The relay *knows* the colliding handle;
if the derivation is a bare hash it can guess a path, serialise its `DataId`, compute the candidate
and compare it against the new `content_reference` row — `sync-entity-identity.md` §1.1's oracle,
rebuilt here by accident. ⇒ `HMAC(K_entity, oldHandle‖losingDataId)`: the relay cannot evaluate it,
both replicas can, and the result is **stored** rather than recomputed, so the key's rotation schedule
does not matter afterwards. ⚠⚠ A rotation falling *between* the two replicas' computations makes them
disagree, and calling that "self-healing" was too quick: the two describe different replacements, row
convergence keeps one, and the **other handle is then duplicated by no local row at all** — so nothing
re-collides and the collision rule never fires on it again. What collects it is the **handle-level
enumeration diff** (`listed handles − live handles`), which exists precisely for an orphan no row
names. ⇒ the rotation window is a **leak the repair pass closes**, not a loss, and not something the
collision rule fixes.
⭐ This is the one place a derived handle is right: it is derived from data that has already
converged, not from a holder that can still move.

⚠⚠ **And ingest is too late on its own — the window before it is what the quarantine is for.** A can
delete `/y` and drain its release *before* B's `/z` has ever arrived: at that moment the one handle
represents both branches, so the release takes the count to zero while `/z` is live elsewhere. No
local check can see this, because A has no knowledge of `/z` yet. ⚠⚠ **And a collision check cannot fire if one branch never coexists with the other.** If A deletes
`/y` before either replica has seen both, then by the time `/z` arrives there is only one live row —
no collision, no re-mint, and B can drain `/z` and truthfully publish that it owes nothing while the
single handle has already been released. ⇒ the arriving branch's own describe is the repair — and it is **unconditional**, not conditioned on
the handle looking absent. ⚠⚠ Checking first reopens the race: `/z` can be ingested while the original
handle is **still present**, the check skips, and A's queued release lands immediately afterwards —
both runtimes then truthfully report empty and the quarantine expires with `/z` still referencing the
content. ⇒ **every ingest of a row carrying a `referenceId` re-describes it, idempotently**; a
describe that the relay already has is a no-op, which is a far cheaper thing to pay for than a race
that is invisible until the bytes are gone.

⚠⚠ **And re-description alone still loses to ordering — so the RELEASE has to be conditional.** A can
release the shared handle and report an empty outbound queue while B publishes `/z` and reports the
same; if A has not yet pulled `/z`, every runtime satisfies the progress gate before any
re-description happens. And in the other order, B's describe can land *first* and A's later release
simply removes the row it just restored. A gate over outbound state cannot fix either, because both
devices are telling the truth.

⇒ **a release names the handle and the version it was computed at**, and the relay applies it **only
if no describe for that handle is newer** — ⚠⚠ where "newer" is the **HLC of the client fact that
justified it**: a describe carries the HLC of the **declaration block itself**, a release carries the
HLC of the row version that stopped naming the handle — a delete, an overwrite, or the surviving
side of a merge — and the relay keeps the latest per handle and refuses anything below it.
⚠ Stating it as "the delete's HLC" was too narrow: N4's capture is generic, and the overwrite and
merge paths have no delete to quote.

⚠⚠ **"The HLC of the row version" is not readable on the device that wrote the row — which is why
the declaration is a BLOCK.** The outbound block's HLC is minted at emit and lives only in
`PENDING_BLOCKS`, which the scheduler deletes after a successful push; the durable sidecars hold
something else (`SyncMetadata` is ingest-only, and `LocalWriteMetadata` is documented as *"one tick
below the HLC of the block that same save emits"*). An author stamping from those would write a
value one tick under what every ingesting peer reads — two replicas of one row version, two stamps,
and a release computed against the lower one outranking a live re-description. ⇒ the stamp is the
**declaration block's own HLC**: the declaration rides the log as `(contentId, referenceId, HLC)`
(§11's move one), so it is minted once by the emitter and read identically by every replica, with no
lookup to get wrong and nothing new to store. ⭐ It also removes the need for the unconditional
ingest-time re-description: the relay saw the declaration when it saw the block.

⚠⚠ **And the relay must REFUSE an absent or unparseable stamp, which inverts a shipped convention.**
`HybridLogicalClockUtil` is explicit that a stamp is *"an ordering hint, never an authentication
input: an unreadable one must degrade to 'unknown', not reject the request carrying it"* — right for
routing, wrong here, where the stamp is the precondition for destroying bytes: degrade-to-unknown
lets a client omit the field and bypass the CAS entirely. ⇒ on this route a missing or malformed
`hlc` is a `400`, and that exception is stated rather than inherited. ⚠ The comparison is the **full**
HLC — physical time, counter, then the `deviceId` tiebreak — not the physical-time-only comparison
the relay's existing tombstone quorum uses; two describes inside one millisecond are ordinary.

⚠⚠ **An earlier revision made this a relay-assigned generation, and that was wrong in a way routine
use exposes.** The argument for it was that an idempotent re-description leaves no newer state and
that two divergent branches have no ordering. Both fail: ⇒ **the HLC is the mechanism that decides which branch
survives on the metadata plane for a LAST-WRITE-WINS type**, so for those the content plane agrees
with the row plane *by construction*.
⚠⚠ **Not for a `ConflictStrategy.Merge` type, and that exception has teeth.**
`SyncProcessor.resolveConflictAndSave` is HLC-*gated* and then strategy-dispatched: for a merge the
HLC decides only whether the merger runs, and the merger's own rule decides the content —
`FolderMerger` breaks ties on `lastModified`, not on the HLC at all. Worse for the release, the
merged row is saved with `origin = SYNC_INGEST` and stamped with the **incoming peer's** HLC, and
`SyncObserver.onDataSaved` returns immediately for `SYNC_INGEST` — so the merged state is never
republished, and a release emitted from there would carry a stamp naming a *peer's* block while each
merging device stamps the same logical outcome differently. ⇒ a merge-born release is stamped with
the **merging device's own authored tick**, like any local fact, and the merged row owes a
republication for the content plane to converge on it. ⚠ Deriving the stamp from whatever the merge
happened to ingest is the version of this that looks right and is not — B's merged `/z` outranks A's release precisely when B's row
outranks A's delete, and a re-description of an unchanged row carries that row's unchanged HLC, which
is what "no newer state" should mean. ⚠⚠ And the generation broke the ordinary case: every describe
advanced it, so a **benign** peer ingest — the unconditional re-description any device performs on
pulling a live row — silently invalidated every *other* replica's token. The origin device's later
delete was then refused, the rule below forbids re-arming it, and same-tick reclamation degraded into
"wait for an enumeration pass" for nothing more than a second device being online. A token that
routine replication destroys is not a capability.

⭐ Three earlier findings collapse into this one change:
- **Nothing restarts.** A handle removed and re-described does not count from the bottom again; the
  next fact about it carries a later HLC, so a delayed retry cannot outrank a live reference.
- **Offline deletes keep working.** The delete's own HLC is authored locally, so a device that has
  been offline for days still holds a valid, causally-correct token — where a relay-assigned number
  could only be read online.
- **Peers do not invalidate each other.** A describe advances the handle only to the HLC of the row
  it describes, which every replica of that row shares.

⚠ **The residual is a resurrection, and it is a leak rather than a loss.** Once a handle's row is
gone the relay has no stamp to compare a late describe against, so a stale one can recreate it at its
own (older) HLC and leave the content charged. ⇒ either keep the removal stamp for a retention
window, or let the enumeration sweep reclaim it — storage, repaired later, rather than bytes a live
row still names. ⚠ Whichever is chosen, it must not be a *refusal* to recreate: a describe the relay
cannot order is the case where re-charging is the safe answer.
⚠⚠ **And a refused release must not be retried against a fresher stamp.** The durable queue re-plans
from scratch on every attempt, so A's refused release would simply re-read whatever B's
re-description just wrote and remove the live handle on its second try, with both outbound statements
still passing. ⇒ a queued release is **bound to the HLC of the delete that created it**, and a refusal
means *the world changed under it*: the entry is dropped and the reference re-evaluated from the live
walk, never re-armed with fresher ammunition. ⚠ The residual there is a **charged handle**, not a lost
one: until the walk re-evaluates, the content keeps a reference nothing holds — storage again, which
the enumeration pass reclaims. Stale release, describe wins; release first, the describe recreates the
row and the count is non-zero again. ⭐ It is the same compare-and-set discipline the progress tuple
needed, applied where the damage actually happens — and unlike a gate condition it needs nothing from
devices that are not involved. ⚠⚠ **And the HLC only arbitrates because a handle never spans two
entities — which costs §6e's *"a move needs no re-declaration"*.** An HLC orders facts about one
`DataId`; it says nothing about two. A divergent rename produces exactly two: A renames `/x`→`/y`
while B renames `/x`→`/z`, and both rows are independently live. If the handle is *inherited* through
the re-key they share one `content_reference`, and A's delete of `/y` — which can carry the greater
HLC while `/z` is untouched and still live — releases the reference `/z` depends on, then refuses
`/z`'s re-description for carrying an older stamp. Quarantine expires and live content goes. ⚠ The
earlier scoping ("a move that *stays* one reference") does not save it: each branch believes it *is*
the one reference, and neither is wrong from where it stands.
⇒ **a re-key mints a fresh handle.** The handle is per-(entity, property), the entity is its
`DataId`, so a row at a new key is a new reference: describe the new handle, release the old one, on
the same migration that already retires the old key. `/y` and `/z` then hold **two** references to
one content, the count is 2, and deleting `/y` leaves `/z`'s bytes exactly where they are — no
arbitration needed, because there is no longer a shared object to arbitrate over. ⭐ What remains for
the HLC is a release racing a re-description of *the same entity and property*, which is a
single-`DataId` question and the one it answers correctly.
⚠ The cost is that a move stops being free on the content plane: one describe and one release, both
on a path that is already writing a tombstone and a new row. Cheap, and it removes a data-loss class
that the free version created.

⇒ otherwise it is covered by the same rule
that covers the conflict-born holder: **a release does not free anything immediately** — the content
sits in quarantine, and the ingest-time re-mint's describe arrives as the cancel. ⚠⚠ **And a clock cannot be the gate, however generous.**
Device B can stay offline longer than any `G`, so "long enough for realistic sync lag" is a guess
that fails silently in the case it was chosen for. ⇒ expiry is gated on **progress**, exactly as §6d
requires for the conflict-born holder — and on an **outbound** statement, not a pull cursor. A
consumed cursor proves device B *read*; it cannot show that B has published its pending `/z` or its
replacement handle, and the scheduler may skip the outbound drain while still pulling. ⇒ each runtime
publishes *"nothing outstanding as of seq X"* and expiry waits on that, or on the runtime being
**removed from the realm**. Time is a floor, never the condition — the two hazards turn out to be one
hazard and take one rule.

⚠⚠ **And an offline device must not become a storage attack.** Waiting on progress means one absent
runtime can stall every physical free indefinitely while an active device keeps uploading and
deleting — unbounded relay growth, which is D1 amplification wearing the safety mechanism's clothes.
⇒ the bound is the **cap** §6d sets on credited-but-unfreed bytes: once an owner is over it, deletes
stop being credited immediately and the pressure becomes visible to the user instead of invisible to
the operator. ⚠ The cap is therefore not a nicety — it is the only thing standing between
progress-gated safety and unbounded storage. ⚠ Which means the content-plane pair cannot ride the generic value-property
copy at all — it is the one property set whose duplication semantics differ from preservation, and
that is another argument for declaring it once in the sync layer rather than per type.

⚠⚠ **And this is why the shared-id case is not merely a P4 problem.** An earlier revision said
sharing arrives with dedup. It arrives with **conflict**: the two compositions above are documented,
shipped behaviour, so two live rows can already name one `contentId` today. P4 makes it ordinary
rather than making it possible.

⚠⚠ **So (ii) does NOT depend on EI-2.** An earlier revision made a stable *entity* identity
(`File.fileId`) the prerequisite, which dragged in a question §3.3.1 had already decided against — a
random `fileId` *"breaks cross-device convergence"*. A **reference** identity carries none of that: it
is minted once, by the device that creates the reference, and migrated locally; it never has to
converge with one another device invented independently. ⇒ EI-2 leaves this track's critical path for
the second time, and this time the reason holds.

⭐ **And the pair should be generic, not per type.** `File.ContentId` and `Workout.ContentId` are two
copies of one concept — which is why a per-type delete hook could be written to leak content
(`DeleteWorkoutImplementation`, N4 below) while Drive's did not. Declared once in the sync layer over
any entity carrying an `OnDemand` property, one definition serves N4's generic capture, the token and
the dedup map. ⚠ Dedup itself stays **client-side**: `contentHash → contentId` is a local map, so
reusing an address tells the relay nothing it did not already know — it sees a second reference, never
a statement that two files are identical.

⚠⚠ **A merge that discards a reference is the third thing the capture must see.** Two offline devices
creating the same path converge — that is what path identity buys — but each minted its own
`referenceId`, so both tokens can reach the relay while the merge keeps exactly one row. The loser's
token then has no event that releases it: the count never returns to zero and the content is charged
for ever, which is the same leak by a third route. ⚠ `resolveConflictAndSave` is the one place that
knows both sides, so the release belongs there. ⇒ the generic capture's sources are **delete**,
**replace** and **merge-discard** — "superseded content" is the concept, and a lost conflict supersedes
just as surely as an overwrite does.

⚠⚠ **(ii) owes the capture its release token — from N4, not from N6.** The retry queue holds
`contentId`s, but a reclaim under (ii) must name the **`referenceId`** — and once the row is deleted,
neither the live walk nor the queued id can recover a random handle that lived only in that row. A delete queued offline would then be unable to release its own reference: the count
never reaches zero and the content leaks for ever, which is the failure this track exists to end.
⚠⚠ **And the phase order makes it a migration, not only a contract.** N4 creates durable queue entries
carrying the id alone; a device that deletes while offline during N4 and upgrades after N6 holds an
id-only entry whose row is already gone, and can never compute the release. ⇒ the capture persists the
**token** from **N4** onward — before the mechanism that consumes it exists — and N6 may not ship
while any id-only entry is still undrained.

⭐ **(ii) is the recommendation** — it answers the one question the relay must answer, discloses a
count rather than a graph, and keeps N6 possible. ⚠ It is a **reversal in kind** of "the relay holds
no reference knowledge", so it is flagged as a decision rather than folded in.

⚠⚠ **N7 requires §11's log-ordered description — it is a prerequisite, not an option.** Markers plus
cursors are exactly the per-device belief that has twice reported a complete walk it had not done
(§9.7a's latch over an empty store; §9.7d's fallback that dropped a whole service). A third occurrence
under N7 deletes a live, undescribed blob that is outside `refs(described)` and older than N0's
window — irreversibly. An acknowledged caveat does not make that safe, so N7 does not ship on one.

⚠⚠ **And §11's own marker inherits that failure — naming §11 as the gate does not by itself close
it.** §11.4's marker is still *device-published*: `(deviceId, format F, complete)`, checkable only
against an empty declaration journal. An empty journal proves every declaration the walk **found**
has landed; it says nothing about what the walk never visited. The production failure was exactly
that shape — the schema walk fell back on the first type it could not enumerate and reported complete
with 270 workouts' streams unvisited — and it would publish `complete`, pass every cursor check, and
let N7 quarantine those streams. ⇒ two corrections the gate needs, neither of which is the marker
being more carefully written:

- **Make completeness per-service, not per-device** — and derive the expected set from something
  other than the reporting device. The marker becomes a vector over schema service ids, and a service
  the walk cannot enumerate is `incomplete` *for that service* rather than a silent narrowing of the
  whole pass. ⚠⚠ **A vector over the services the device itself reports is still self-certifying**: if
  discovery omits a service — or the format wipe leaves every registered service reading empty — then
  every entry present says `complete`, and the relay has no expected set to miss it against. That is
  the same shape as the marker it replaces. ⇒ the expected set is the **union of the vectors across
  every admitted device**, and `gate(S)` requires every device complete for every service in that
  union, with a device that has never published a vector blocking the gate outright. A device whose
  discovery drops Fit is then contradicted by one that still reports it — the check a single device's
  belief cannot supply. ⚠⚠ **And the union must be over a durable realm history, not over the
  currently admitted roster.** A union that follows the roster *shrinks* when a device leaves: remove
  the only phone that ran Fit and the service silently drops out of the expected set, while its rows
  and its content stay in the realm — the remaining devices then report every service in the reduced
  union complete without ever enumerating them, and N7 quarantines live content on a unanimity that
  was achieved by deleting the witness. ⇒ the expected set is **append-only per realm**: a service
  enters when any device ever reports it and never leaves on removal, so a realm that has held Fit
  data keeps owing a Fit answer until some device gives one. ⭐ E4 already publishes each device's
  schema versions for the roster floor; this is the same fact kept at realm scope and monotonic
  rather than recomputed from who is present. ⚠ It stalls rather than deletes when the last device
  for a service is gone for good — the direction this whole track chooses — and an operator
  retiring a service is an explicit act, not a side effect of removing a device. ⚠ The relay cannot do better unaided: under HR #1 it sees ciphertext and
  cannot attribute a blob to a service, and making it able to would disclose app-level usage, the
  exact leak EI-1/EI-4 remove. ⚠⚠ **Residual, unclosed:** a failure hiding the same service on *every*
  device defeats this too — which is why N7's first runs are read by a human rather than armed.
- **Let a resolve cancel a quarantine — for the whole content, not the ref that was fetched.**
  §11.5 already quarantines before freeing and cancels on a declaration naming the ref; extending
  the cancel to a `GET` makes ordinary use a veto, and it is the one signal that does not come from
  the walk that was wrong. ⚠⚠ **But per-ref cancellation corrupts the very file it rescues.**
  `StreamingContentSession.readRange` decrypts *"only the chunks a range touches"* and a seek can
  read chunk K without 0…K-1 — so previewing a large undescribed file saves its manifest and a
  handful of chunks while the rest expire after `G`, leaving a file that resolves and then fails
  mid-read. That is worse than deleting it whole, because the loss is silent until someone opens it.
  ⇒ resolving a content's **manifest** cancels quarantine for its **entire ref set**, and no member is
  freed while any member is protected. ⚠⚠ **But the relay cannot expand that set itself** — an earlier
  revision of this line said it could, and that is HR #1 backwards: the manifest is **ciphertext** the
  relay may not decrypt, and for exactly the live-but-undescribed content this veto exists to rescue
  there is no `content_ref` row to expand either. ⇒ the expansion is the **client's**: a resolve that
  touches quarantined content makes the resolving device — which has just decrypted the manifest and
  therefore holds the chunk list — **describe** that content. ⭐ Which is the better outcome anyway:
  the rescue and the repair are the same act, so content saved by a resolve stops being undescribed
  instead of being saved again on the next pass. ⚠ It follows that the veto is only as good as the
  client's ability to describe at that moment — an offline or old client resolving quarantined content
  rescues nothing — so the grace `G` must outlast a realistic offline window, not merely a sweep
  interval.
  ⚠⚠ **And the rescue is two calls, so the free has to be fenced between them.** A resolve landing
  near the end of `G` can `GET` the manifest successfully and have the chunks freed before its
  `describe` arrives — the description then records references to bytes that are already gone, and
  the file fails mid-read on ordinary access, which is the failure this veto exists to prevent
  arriving through the rescue path. ⇒ **the `GET` itself takes the fence — and it has to be
  OWNER-scoped, because the relay cannot name the set it is protecting.** A read of quarantined
  content is evidence that a live reference is in flight, and acting on that needs no plaintext. But
  the pin cannot be per-blob: `GET /sync/content/{ref}` names **one** opaque ref, and for exactly the
  live-but-undescribed content this rescue exists for there is no `content_ref` row mapping that
  manifest to its chunks — which is the premise of N7, not an oversight. Pinning the fetched ref
  alone leaves its chunks freeable while the client is still decrypting, and the description that
  follows names bytes that are already gone. ⚠⚠ The relay cannot fence a set it cannot enumerate
  without decrypting, and decrypting is HR #1 backwards. ⇒ a fetch of quarantined or undescribed
  content **suspends that owner's undescribed-blob free** for a bounded window — coarse, zero
  plaintext, and exactly as precise as the relay's knowledge actually is. The suspension lifts on the
  matching `describe` (which finally *does* name the set) or when the window expires, and an expired
  window returns the content to **quarantine with a fresh grace**, never straight to the free list,
  because a reader was demonstrably there. ⚠ Repeated fetches can hold an owner's undescribed pool
  indefinitely; that is storage held for something being read, which is the direction this track
  chooses every time — and N7, the only step that deletes on inference, is read by a human before it
  is armed.

⚠ **The residual, stated rather than closed:** content that is live, undescribed, never resolved for
the whole grace `G`, and whose service the walk mis-reported. `G` is the only thing standing between
that and deletion, which is an argument for a generous one — and for N7's report being read by a
human before the first realm is swept.

### Open

- Whether §11's transport is built for N7 alone, or earlier so the describe wire lands on the log from
  the start — the second is more work up front and removes a migration later.
- **Migration.** The tables exist and production holds 8,010 edges. Dropping them is a relay-side
  delete; leaving them inert is free and keeps the option open.
- ~~**§9.14's read half**~~ — the same paged read in a different unit (`contentIds` rather
  than `holderHash`). **Decided: shipped as N2, and the holder-name read closed 2026-09-21.**

---

## 7. Open questions

- ~~**Q1. Is the reference graph enumerable?**~~ **CLOSED 2026-09-08 — by the schema, not by an
  audit** (§5). The keep-alive holders are `File.ContentId`, `SharedFile.contentId` (under the shared
  realm's `ownerId`) and `Workout.Streams` (no field; entity-keyed). The four Photos fields are
  annotations. ⚠ The v0.2 answer — *"two fields, `File.ContentId` and `Album.coverContentId`"* — was
  wrong in both directions, because it audited `contentId` **fields** when the marker is the
  `OnDemand` **trait**; that is also why it could not see Fit. What remains is §7b.3's one-line core
  assertion.
- ~~**Q2. Where does the refcount live?**~~ **DECIDED, then SUPERSEDED by C′ (§6e).** ⚠⚠ The answer
  below is the *shipped* design and the one N6 retires: it is recorded here as history, not as an
  instruction. C′ replaces the holder-keyed edge with a **`content_reference` row per handle** —
  `(owner_id, content_id, reference_id)`, a random per-(entity, property) id — precisely so no holder
  name reaches the relay. Building to the text below would rebuild the protocol this document now
  removes. **The superseded decision:** a holder-keyed edge set on the relay, not a
  count. `(ownerId, holderHash, propertyId) → contentId`, where `holderHash` is the entity-id hash
  the tombstone already carries (A6). Declare = upsert at `SyncObserver`'s emit; undeclare =
  delete-by-holder at the tombstone drain; a blob is live iff any edge names its manifest.

  Why not a counter: a retried declaration double-counts; trash + restore, `SharedFile` sharing a
  personal id, the §6c.1 recreate, and the copy-as-metadata goal all put several rows on one manifest.

  What the edge set buys:
  - **idempotent** by construction, per row;
  - **no operation involved** — undeclare needs only the hash, never the dying row's field (§5.2);
  - **upsert on `(holder, property)` closes §3.2 for free** — a re-emit replaces the edge, so the
    overwritten content loses its last reference without anything noticing the overwrite;
  - **enumerable**, which makes B and C one table (§6).

  ⚠ Ordering: declare-before-`PUT` protects in-flight chunks (closes §6's race) but a crash before the
  manifest lands leaves an edge to nothing — the relay treats an edge to an absent blob as *pending*,
  not an error. ⚠ Two `OnDemand` properties can share an entity (`Syncable` KDoc), hence
  `propertyId` in the key.
- ~~**Q3. What does C's route expose?**~~ ⭐ **Answered 2026-09-08: nothing, and it is not the same
  question as §4.** The two were conflated here, which made enumeration look more expensive than it
  is.

  | | what the **relay** learns |
  |---|---|
  | refcount (B's edges) | holder→content grouping — inferable from timing today (§4), now explicit fact |
  | enumeration (C) | **nothing** — it returns to the owner what the relay already holds about that owner |

  A relay cannot learn from telling an owner their own ref list, and the client learns nothing it
  could not already compute. Under Q2 the listing is *blobs with no edge* — still derived entirely
  from what the relay holds. ⇒ **Enumeration carries no §4 cost and must not wait on that decision;
  only B's edge table does.** C's requirements are conventional, with a precedent to copy verbatim:
  `/sync/usage` is already *"strictly caller-scoped … `ownerId` is the verified-token owner (never a
  client-supplied value)"*. Add paging — ~15 k refs for the measured realm — and that is the whole
  route.

  ⚠⚠ **And the safety asymmetry runs one way only** (v0.2 said otherwise; corrected):

  ```
  orphans = relayRefs − liveRefs
  ```

  - **relay listing incomplete** → diff *smaller* → orphans missed → **safe**, it under-deletes
  - **client live set incomplete** → diff *larger* → **live content deleted** → the blob-GC data loss exactly

  So the dangerous side is entirely the client's, which is why `LiveContentIds.Result.Incomplete`
  carries no partial set — and why §6's in-flight upload matters: a not-yet-declared upload makes
  the live set *temporally* short in exactly the same direction. The relay wants a completeness
  signal for **diagnostics**, not for safety.
- ~~**Q4. What caps pending-free?**~~ **MOOT** — D1 is retracted (§6c.3), so nothing is ever
  uncounted.
- **Q5. Is there a first-class "content unavailable" state?** §6c.2's residue is **irreducible** —
  the barrier's own KDoc says an *awake and disconnected* device cannot be waited out, and a
  **revoked** device keeps metadata naming files whose bytes are then freed. So a device can hold a
  row whose content is legitimately gone. ⚠ Grepping `:drive-shared-ui` and `:ui-core` finds
  `AppControlUnavailableWall` (proxy unreachable) and a `cloudUnavailable` upload counter — **nothing
  for "this file's content is gone"**. ST-3f repairs a dangling reference but cannot run when no
  device holds the plaintext. ⭐ Since the state cannot be designed away it must be designed *for*,
  and this is true under today's barrier, under a refcount, and under enumeration alike — so it is
  independently shippable. Traced: today a fetch of a reaped ref resolves to a **null property
  value** on the on-demand read path (`OnDemandResolutionImpl`); whether the UI renders that
  acceptably is a UI question, not a sync one.
- **Q6. Does the pre-release reset still apply?** `file-content-addressing.md` records *"No
  backwards compatibility required (decided 2026-07-14)"* with a Phase-0 reset precedent. If that
  still holds, B's migration cost is close to zero, C's backfill is moot for this realm (§2.1), and
  this is the cheapest it will ever be.

---

## 7b. Tests — what exists, what extends, what is new

⚠ The existing suite is strong on the *barrier's arithmetic* and near-silent on the *reference
graph* and the *multi-device timing*. Those are exactly the two things this design turns
load-bearing.

### 7b.1 Already covered — do not rebuild

| test | covers |
|---|---|
| `ReclamationBarrierTest`, `ReclamationBarrierCursorTest` | every barrier branch, pure and total |
| `ContentReclamationPolicyTest`, `…SweepTest`, `…DryRunTest` | plan/act split, dry run stops before the first write |
| `ReclamationBarrierPropagationTest` | multi-proxy over a real relay; tips vs cursors |
| `TombstoneSeqStampTest` | the drain→stamp seam |
| `ContentReclamationCandidateSeqTest` | encode/decode, first-stamp-wins, pre-α compatibility |
| `ContentRoutingTest`, `AccountClosureReclaimerTest` | relay `dropRefs`, owner-scoped reclaim |
| `FileBytesOnDemandSystemTest` | ⭐ a joiner fetching host-uploaded bytes on demand — **the harness the ghost-file test extends** |

### 7b.2 Extend an existing test

- **`FileBytesOnDemandSystemTest` → the recreate window (§6c.1).** It already stands up two proxies
  over a relay and fetches bytes on demand. Add: host deletes and undeclares; the joiner, which has
  **not** consumed the tombstone, renames the file with a later HLC; the host ingests the recreate
  and re-declares; the joiner fetches. The bytes must still be there. ⚠⚠ That single assertion is
  what makes "metadata leads, content follows" true under a refcount — without it the invariant is
  an unverified slogan. A second variant: the joiner fetches *within* the relay grace with no
  recreate → succeeds; *after* it → the Q5 unavailable state, never a crash.
- **`ReclamationBarrierPropagationTest` → revocation.** Revoking a peer removes it from the roster.
  Assert the revoked device still holds the row **and** that its fetch after the grace lands in the
  Q5 state — the irreducible residue, demonstrated rather than asserted in prose.
- **`ContentRoutingTest` → `dropRefs` idempotence under partial failure.** Delete a subset, then the
  full set; assert `blobsDropped` counts only what was really removed and the second call is a no-op.
  The client already relies on this (`ContentReclamationSweep` retries a failed batch wholesale).

### 7b.3 New, and each guards a specific failure named in this doc

1. **Reference-graph completeness (§5).** One assertion in `:core`: every property carrying
   `Syncable(SyncStrategy.OnDemand)` has a writer registered in `DelegatingExternalPropertyWriter`,
   on every platform's registration. A schema walk, not a source-text audit. ⚠⚠ This is the gate on
   the whole refcount, and it is deliberately **not** "every operation that writes a `contentId`
   declares" — that would miss Fit (no field) and would make every service know about reclamation.
   With declaration at the emit there is no call an operation can fail to make.
2. **Quota amplification (§6c.3).** Upload to the limit, declare deleted, upload again — assert the
   relay's accounting never permits more than the quota **in total physical bytes**. This is the
   regression guard for the retracted D1, and it must fail if anyone re-introduces an uncounted
   pending state that a *client* can stall.
   ⚠⚠ **The bound moves with immediate credit, and the test must move with it.** Crediting a delete
   at once while its bytes wait out the quarantine means physical storage *deliberately* reaches
   `quota + the per-owner quarantine cap` — so as written this test fails the very accounting §6d
   adopts. ⇒ the assertion becomes **`quota + cap`, with the cap a constant the test names**, which
   keeps the guard's real job (rejecting *uncapped* growth, the D1 shape) while admitting the bounded
   overshoot that immediate credit buys. ⚠ Relaxing it to "no assertion" would retire the only
   regression guard this track has against its own worst failure.
3. **Enumeration completeness — one direction is the dangerous one (§6, Q3).** A client walk that
   returns `Incomplete` must reclaim **nothing**: `orphans = relayRefs − liveRefs`, so a short live
   set *enlarges* the diff and deletes live content — the blob-GC data loss's shape exactly. ⚠ The relay direction
   is **not** symmetric, and v0.2 wrongly said it was: a truncated listing *shrinks* the diff, so it
   merely misses orphans. Test it anyway, but as a **diagnostic** assertion — a short listing must be
   reported, not treated as a safety failure. And the same direction, temporally: a blob younger
   than the in-flight window is never listed as orphaned.
4. **The unresolvable-manifest leak (§3).** Assert that a candidate whose manifest cannot be resolved
   is dropped *and its refs remain on the relay* — pinning today's acknowledged leak so enumeration
   can later be shown to close it. A test that documents a known leak is worth more than a comment,
   because it fails when the leak is fixed and forces the fix to be noticed.
5. **Pin the Fit leak (§3.5)** in the same shape: a workout delete leaves its stream blob on the
   relay. It fails the day the generic seam lands, which is the point.
6. **Declaration atomicity (§6c).** One holder's edge set is replaced atomically — a connection
   dropped mid-declaration leaves the previous edges intact, never a partial set. This is the
   property that makes partial deletion stop being observable.
7. **The recreate re-declares (§6c.1 a).** Unit-level, on `SyncProcessor`'s `hlc > tombstone.hlc`
   path: bringing a row back emits an edge for it. Without this test (a) is a sentence, and the
   data-loss path of §6c.1 is open.

⚠ **What no test can cover, and must therefore be a device pass:** the relay grace (§6c.2) as a *user*
experiences it, and Q5's unavailable state. `docs/testing.md` §6's rule applies — the smoke gate
proves apps start, not that a realm behaves.

## 8. What this asks for

Not a rewrite. Four things:

1. **Supersede [ADR 0017](../adr/0017-file-content-two-plane-channel.md) D6 / alternative (iv)**, and
   record that ST-3b already crossed its line. Then strike "HR#1-impossible" from the ten sites §2
   lists, replacing it with: *a relay-**derived** refcount is HR#1-impossible; a client-**declared**
   one is not.* ⚠ The binding threat-model amendment co-lands with the **implementing** PR (HR #2, and as every
   phase of T-R-09 has), with §4's last paragraph as the residual — but T-R-09 now carries a
   **selected-successor** note naming these three debts, so the contract does not read as current
   while the successor is chosen and unbuilt. ⚠⚠ **C′ adds three specific debts to that
   amendment, named here so the implementing PR does not have to rediscover them:** the holder-less
   `describe` is a **new relay-visible shape** (contentId → refs) that T-R-09's enumeration of the
   content-plane wire does not cover; option (ii)'s tokens disclose a **per-content reference count**,
   which is new relay knowledge even though it is not a graph; and deletion authority moves from the
   relay's edge re-check to the **client's word**, which changes who the integrity argument rests on.
   ⚠ Recommending a design is not changing the protocol — but discovering these at implementation
   time is how an amendment gets written thin.
2. **Schedule C on B's table** (§6) — small, unblocked, and the product route to the 4.7 GiB.
3. **Ship one fix now, regardless of B vs C:** record the previous `contentId` as a candidate on
   overwrite in `UploadFileImplementation` (§3.2). It uses the mechanism Drive already has. ⚠ The Fit
   leak (§3.5) does **not** get the same treatment — a Fit-side recorder would copy the coupling
   §5.2 removes. Pin it (§7b.3) and let the seam absorb it.
4. **Index this doc in `docs/README.md`** — done in v0.3.

---

## 9. Phase map and as-built

The phase IDs existed only in the commit trail until now. What they are, and where each stands:

| | what | state |
|---|---|---|
| **CR-0** | Supersede [ADR 0017](../adr/0017-file-content-two-plane-channel.md) D6 / (iv); strike "HR#1-impossible" from the §2 sites | shipped 2026-09-08 |
| **CR-1** | The standing leak: record the superseded `contentId` on overwrite (§3.2) | shipped 2026-09-08 |
| **CR-6** | Subtractive sweep once description is provably complete — ⚠⚠ **not a legacy cleanup**: five generators leak continuously without it (§9.9a) | **not started**; gated on Fit declaring + realm-wide backfill completion |
| **CR-2** | Delete the per-service coupling — `ContentReclamationRecorder`, the candidates ledger, the barrier (⚠ **not** the walk, §5.2) | **not started**; ⚠⚠ gated on a DEVICE PASS, not on code — §9.10 |
| **CR-3** | Relay half of B: the `content_edge` table + store, then the declare / undeclare routes | shipped 2026-09-08 |
| **CR-4** | **Client half of B**: declare at `SyncObserver`'s emit, undeclare at `drainPendingTombstones` (§5.2) | shipped 2026-09-09 |
| **CR-4b** | The `contentId -> refs` hop the edge set was missing, + declare hardening (§9.2b) | shipped 2026-09-09 |
| **CR-4c** | *declared ⇒ walkable*: the walk names the derived thumbnail ids it was declaring but could not veto (§9.6) | shipped 2026-09-09 |
| **CR-4d** | The backfill pass — re-declare from live content, with refs (§9.7) | shipped 2026-09-09; ⚠ describes only LIVE content, so it does **not** reach today's orphans (§9.9) |
| **CR-4e** | The walk is schema-driven, so *declared ⇒ walkable* is structural (§9.6b) | shipped 2026-09-09 |
| **CR-5** | The enumeration sweep — ⚠⚠ **stated positively**, or it deletes live data (§9.5, as built §9.8) | shipped 2026-09-09, **inert**: same `reclamationDryRun` switch as ST-3b |

⚠⚠ **CR-2 is still not a cleanup you may do early.** CR-4 writes the table, but nothing *reads* it
until CR-5, so the mechanism CR-2 deletes — `ContentReclamationRecorder`, the candidates ledger,
the barrier (⚠ **not** the live walk, §5.2) — is still the only one keeping bytes reclaimable. Removing it
now would strand every subsequent overwrite and delete with no reclamation path at all. The staging
is RH-0's: the surface lands first so it can be observed in production before it decides anything.

### 9.1 As built — CR-3

- `ContentEdgeRegistry` + `SqliteContentEdgeStore` (migration 18, table `content_edge`, index
  `content_edge_by_content`) and the in-memory `ContentEdgeStore` twin for dev mode and tests.
  ⚠ The in-memory one is **not durable**, so on a durable relay it would read as *"nothing
  references anything"* — the SQLite path is production, and the fallback exists only where
  `DATABASE_URL` is absent.
- `POST /sync/content/declare` and `POST /sync/content/undeclare`, wired in `ContentRouting.kt`
  behind `if (contentEdges != null)`. Wire shape and rules: [`sync-relay-api.md`](sync-relay-api.md)
  §3.7. Threat model: T-R-09, the *"routes now exist"* amendment.
- Tests: `SqliteContentEdgeStoreTest` (persistence, upsert-replaces, owner isolation) and
  `ContentEdgeRoutingTest` (round-trip, owner scoping, 401, batch limits, malformed-edge skip,
  re-declare replaces). ⚠ The owner-scoping and skip guards were each verified by mutation — the
  first fails when the drop is keyed on `holderHash` alone, the second when the blank check is
  removed.

### 9.2 As built — CR-4

Two seams, no `OperationImplementation` in either, and `DeleteFile`'s `reclamationRecorder` hook is
untouched only because CR-2 removes it.

- **Declare at the emit.** `SyncObserver.emitFileContentNow`, in the content-addressed branch,
  **after** the manifest `PUT`. ⚠⚠ The order is the assertion: an edge declared for content that
  failed to land names bytes the relay does not have, which is the one wrong answer the table can
  give. The reverse — bytes with no edge — is merely undeclared.
- **Undeclare at the drain.** `SyncScheduler.drainPendingTombstones`, for the holders it actually
  pushed, batched at `ContentBlobStore.MAX_EDGE_BATCH` on the same rhythm as the queue removals
  (~10 k tombstones on a realm reset become ~20 requests). ⚠ Deliberately **not** filtered by
  entity type: a filter would have to be right about which types hold content, and being wrong
  excludes a holder whose edges then stand for ever.
- **Port**: `ContentBlobStore.declareContentEdges` / `undeclareHolders`, both defaulting to `false`
  so no existing implementor was widened, plus the `ContentEdge` triple.
- **Tests**: `ContentEdgeDeclarationTest` (the edge's shape; ⭐ a re-emit replaces it, closing
  CR-1's leak structurally; a failed manifest `PUT` declares nothing) and `TombstoneUndeclareTest` (only
  pushed holders; batching; an empty queue asks nothing). Both guards verified by mutation.
- **`OnDemandWriterCompletenessTest`** (§7b.3 #1): every `Syncable(OnDemand)` property has a
  registered writer. Seven today — `File.Data`, five thumbnail classes, `Workout.Streams`.

### 9.2b ⭐ As built — CR-4b, and the defect that produced it

⚠⚠ **An edge set alone cannot free a chunk, and CR-3/CR-4 shipped without noticing.** An edge names
a `contentId`. The blob store is keyed by **ref**. From a contentId the relay can derive exactly one
ref — the manifest's, `sha256("manifest:" + contentId)`, because that address is a pure function —
and every **chunk** ref is `sha256(ciphertext)`, which exists only inside the AEAD-encrypted manifest
it may not read (HR #1). So `allRefs − referencedRefs` computed from edges alone reduces to *"every
chunk ∪ every orphan manifest"*, which is not a diff. **The measured orphan population is ~14,750
chunks.** §6's *"holding edges on the relay removes the walk"* was therefore wrong as written: it
removes the walk only once the relay also holds the refs.

Found in review of the declaration seam, 2026-09-09. v0.1 had the shape right (`manifest -> [refs]`); the v0.3 edit
that introduced the holder key dropped the ref set, and CR-3's table built what was written.

**The fix, and why it is a second table rather than a wider key:**

- `content_ref(owner_id, content_id, ref)`, migration 19. Content is immutable and
  content-addressed, so a row is written **once and never rewritten**, while an edge is per-holder
  and mutable.
- ⚠ Putting refs in the edge key — `(owner, holder, property, ref)` — costs a row per chunk **per
  holder** (a 2 GiB file is ~1 000 rows each) and destroys the `(owner, holder, property)` upsert
  that makes an overwrite supersede its predecessor.
- ⚠⚠ **The deciding argument is backfill, and it was not in the review.** Recording the association
  at `PUT` time — a `content_id` column on `content_blob` — is the smallest change and needs no
  second table, but it can only describe bytes as they *arrive*. A ref table can be declared
  retroactively: a client reads its own manifests (the read `chunkRefsOf` already does) and declares
  `contentId -> refs` for content uploaded years ago. That is the only variant that makes the
  existing pile separable at all, which is the entire reason this track exists.

The refs ride the existing `POST /sync/content/declare` as a field on each edge, so edge and refs
land in one request, and the ref-set replace is **one transaction** (§7b.3 #6):
`SqliteContentRefStore` delete-then-inserts with `autoCommit = false`, because between the two
statements the content has no refs at all and a reader in that window would call every chunk an
orphan. Pinned by `aDeclarationThatFailsPartWayLeavesThePreviousSetIntact`, verified by mutation.

⚠ **Empty means "I am not describing this content", never "it is made of nothing."** An older
client, and the legacy manifest path, declare edges with no refs; wiping a good ref set for one of
those would manufacture orphans out of live chunks.

**Declare hardening, from the same review:**

- ⚠⚠ **A malformed edge is now a `400`, not a skip** — the earlier behaviour was wrong in the
  *dangerous* direction. A silently dropped declaration leaves live content with no edge, which
  under enumeration reads as an orphan, and the caller was told `declared: 0` while believing it
  succeeded. The "rejecting strands the well-formed siblings" argument does not survive contact with
  the emit, which declares **one** edge per call.
- ⚠ **Length + charset bounds** on `holderHash` / `propertyId` / `contentId`, and the strict
  `REF_PATTERN` on refs. Deliberately *not* exact-shape regexes for the first three: the directions
  are not symmetric, and over-strict validation on **declare** rejects a real reference and leaves
  live content looking orphaned. This bounds row **size**, which the unbounded per-owner row count
  (§9.4) does not.

### 9.3 ⚠⚠ The five populations that legitimately carry no declaration

Five ways a live blob legitimately has no declaration. **None is a bug**, and together they are why
§9.5's rule is stated positively: freeing on "no edge" alone is the blob-GC data loss's shape.

1. **Every byte uploaded before CR-4.** The entire existing population, including the 4.7 GiB this
   doc exists because of. ⭐ CR-4b makes the backfill *possible* — refs can be declared
   retroactively from manifests a client already holds (§9.2b) — but the pass that does it is
   unwritten, and it is the one that actually reaches the existing pile.
2. **A failed declaration.** Best-effort by decision (§9.2): an older relay's 404 or a transport
   failure logs and moves on, because failing the emit would make every upload retry for ever.
3. **The legacy / shared-realm manifest path.** When `resolveContentId` answers `null` the manifest
   rides the ordered mailbox — but the *chunks* still went to the content plane, with no edge.
4. ⚠⚠ **The recreate (§6c.1 a).** `SyncProcessor`'s `hlc > tombstone.hlc` path runs inside a
   **non-suspend `database.transaction { }`**, so it cannot make a network call; re-declaring needs
   a queue and a drain. ⭐ Under §9.5's rule it stops being a CR-5 blocker — the live walk sees the
   resurrected row and vetoes the candidate, exactly as it does today.
5. ⚠ **An upload in flight.** Its chunks are on the plane before the declaration follows, and a ref
   is `sha256(ciphertext)` so the set cannot be declared first — the window is structural. The only
   close is an age window on `last_referenced_at`, which §6 already names and CR-5 owes.

⚠ `SharedFile`'s content property is invisible to the completeness assertion for a different
reason: `SharedFile.DATA_PROPERTY_ID` is a bare `const`, not a registered `dynamicProperty` (its
projectable handle lands with §6 Phase 3b.2b). The **emit** declares its edges correctly —
`FileBytesPropertyWriter.resolveContentId` answers for it — so this is a gap in the *test's* reach,
not in the seam's.

### 9.4 ⚠ Open: the edge table has no per-owner row cap

`declare` writes rows for arbitrary `holderHash` values **without uploading any bytes**, so an
authenticated owner can inflate the table cheaply. Per-request size is capped
(`MAX_DECLARE_EDGES = 512`); the per-owner row count is not, and today's quota counts bytes, not
rows. Bounded to the attacker's own keyspace and to holding a valid device JWT — an availability
residual, never a confidentiality one.

⚠ **Deliberately not fixed.** A cap chosen before anything writes is guesswork about a distribution
nobody has measured; the honest number comes from CR-4 running against a real realm. **It belongs to
CR-5** — the slice that first gives the table authority is the one that must not be able to be filled
with lies. Recorded at T-R-09 as an accepted, named residual.

⭐ **Half of it is closed, and it was the better half.** CR-4b bounds each identifier's *length*
(§9.2b), so row **size** is now deterministic where before a 200-character holder hash was accepted.
That needed no measurement, unlike a count cap. What remains open is only the count — and the
natural expression may be "no more edges than the owner has blobs" rather than a number at all.

### 9.5 ⭐⭐ CR-5's rule — stated positively, and why the negative one deletes live data

**Decided 2026-09-09**, from a review that found the obvious formulation is a data-loss path.

⚠⚠ **`allRefs − keep` would delete every Fit and shared-realm chunk today.** Two facts, both
verified in the code rather than argued:

- `WorkoutStreamsPropertyWriter` does **not** override `resolveContentId`, so it inherits the
  `null` default. Every Fit emit therefore takes the legacy branch: chunks on the content plane,
  **no edge and no refs**.
- `ProxyLiveContentIds.liveContentIds()` is `LiveContentIds.collect(root = "/")` over `File`
  entities. It sees no `Workout` and no `SharedFile`.

So a subtractive rule classifies both populations as orphans and reclaims them. **The rule must be
positive:**

```
candidate = a contentId that IS declared, and that no live edge names   ← content_edge
confirm   = the client's live walk does not name that contentId         ← the recreate veto
free      = that contentId's refs                                       ← content_ref
NEVER     = any ref not belonging to a declared content object
```

⭐ **This is B, not "C with a ref table".** `no live edge names it` *is* `holdersOf(contentId)`, so
the edge table is load-bearing after all; the walk is retained as a **veto**, not as the source of
the keep set. That is also why §5.2 no longer lists the walk as deletable.

⭐ **The two gaps cancel — and only by accident.** A Drive-only walk can now only fail to *veto* a
candidate, and Fit content is never a candidate because it is undeclared. ⚠⚠ **That is a coupling,
not a coincidence, and it breaks the day it improves**: give `Workout.Streams` a `resolveContentId`
and Fit content becomes a candidate that the Drive-only walk cannot veto — which is the loss. So
the invariant is:

> **Declared ⇒ walkable.** A content type whose emit declares an edge MUST be reachable by the live
> walk that vetoes it.

⚠ `OnDemandWriterCompletenessTest` does **not** pin this: it asserts every `OnDemand` property has a
*writer*, and says nothing about the walk. The assertion CR-4c owes is the second half.

### 9.6 ⭐ As built — CR-4c: the invariant, and the violation it was already in

**`declared ⇒ walkable`.** §9.5 frees a content object only when it is declared and no live edge
names it, with the client's live walk as the **veto**. A content id the emit declares but the walk
cannot name therefore has no veto.

⚠⚠ **That was violated the day §9.5 was written.** A file's five thumbnail classes are
`Syncable(OnDemand)` with their own **derived** ids (`derivedThumbnailContentId`), so the emit
declares an edge for each — while `ProxyLiveContentIds` walked `File.ContentId` alone. The window is
narrow (a live file's thumbnail edge is live, so it is never a candidate) but real: across a
**recreate** the tombstone drops every edge the holder had, a peer brings the row back, the veto
covers the file's own content, and the four thumbnails go unnamed. The file survives with its
thumbnails reaped.

**Fixed structurally, not by a second list.** `FileBytesPropertyWriter.THUMBNAIL_CLASSES` is now the
one table both sides read: the emit declares from it and the walk derives from it, so adding a
thumbnail class extends both at once. `DeclaredContentIsWalkableTest` asks the *same port the emit
asks* what it would declare and requires the walk to name all of it — verified by mutation
(restore the pre-CR-4c walk and it names the four derived ids).

⚠ Distinct from `OnDemandWriterCompletenessTest`, and neither implies the other: a **writer** is
what declares, a **walk** is what vetoes.

### 9.6b ⭐⭐ As built — CR-4e: the walk is schema-driven, and *declared ⇒ walkable* is structural

CR-4c patched the four derived thumbnail ids into the tree walk **by hand**. That closed the
instance and left the class: a walk of `File` rows from `/` can only ever satisfy §9.6 for Drive,
and would miss `Workout.Streams` or `SharedFile` the day either declares.

```
every Syncable(OnDemand) property → its receiver type → every row of that type →
    resolveContentId(row, property) → (holderHash, propertyId, contentId)
```

`SchemaDrivenContentWalk` asks **the same port the emit asks**, about every type carrying an
`OnDemand` property, discovered from `SchemaRegistry.registeredRelations()`. It contains no list of
properties and no knowledge that four of them are thumbnails — which is what makes the invariant
hold by construction.

⚠⚠ **The premise CR-4d was built on was wrong, and this corrects it.** CR-4d walked Drive's tree
because I believed the store could not enumerate by type. It has **two** such primitives and
compaction already uses both: the unbounded `ObjectStore.rowsOfTypes` and the bounded, resumable
`ObjectStore.scanRowsOfType`.

**Three properties, and two of them are the same mistake in different clothes — reading *"I could
not look"* as *"there is nothing"*:**

- ⚠⚠ **The bounded scan is preferred, the unbounded one accepted.** `SqliteObjectStore` (Android,
  desktop) and iOS's `FileObjectStore` implement the scan; `InMemoryObjectStore` implements only
  `rowsOfTypes`, which costs nothing there because the whole map is resident. A measured store held
  **77 800 block rows totalling 129 MB** (`core-local-store-orphan-gc.md` G3), so loading a type
  wholesale is fatal on exactly the devices this is for.
- ⚠⚠ **Completion is keyed on `TypeScanPage.exhausted`, never on a short page.** The scan has two
  budgets, so a short page is ambiguous between "no more rows" and "I stopped early" — and reading
  the second as the first ends the walk with content undeclared.
- ⚠⚠ **A store that cannot enumerate, or a registry that is unreachable, is INCOMPLETE — never an
  empty success.** The backfill latches a once-only marker on a complete walk, so an empty-complete
  would record a realm as described that was never examined. `null` and empty mean opposite things,
  the same contract `ObjectStore.rowsOfTypes` states for itself.

⚠ **The JS store implements neither**, so on web the walk falls back to the injected tree walk. That
is why the tree walk survives rather than being replaced: it works on any store.

⚠ **The veto (`liveContentIds`) deliberately stays on the tree walk.** Moving it would make the
sweep permanently `Incomplete` on JS — safe, but a capability regression — and the sweep is the one
thing in the project that can delete a user's bytes. CR-4c's derived-id patch therefore stays; the
two walks are separate on purpose, and §9.6's invariant is now held on both sides for different
reasons.

⚠⚠ **Both guards above were vacuous when first written** — the store tests never reached the store,
because the walk checks the port first and no writer was registered, so they passed having
exercised nothing. Caught by mutation, not by review.

### 9.7 ⭐⭐ As built — CR-4d, the backfill: the only pass that reaches the existing corpus

Every emit since CR-4 declares inline, so the edge table describes **new** content by construction.
Nothing described the **existing** corpus — the ~14 750 orphaned blobs this track exists for, plus
§9.3's populations 1, 2 and 3. ⚠ A file is emitted **once**, so none of them heals on its own: a
transient 404 leaves a file undeclared for ever, and under §9.5's positive rule undeclared means
unreclaimable.

```
live files → (holder, property, contentId) → fetch + decrypt each manifest → refs → declare
```

**`BackfillContentDeclarations`**, a `HeadlessSafe` op on an idle+charging+unmetered schedule, run
by `SyncScheduler.backfillContentDeclarations()` — the `WriteSliceSnapshots` shape.

#### ⚠⚠ 9.7a The marker may not latch while the realm is still arriving (fixed 2026-09-11)

The marker used to latch on `walkComplete && allAccepted`. Both terms describe **the walk**, and
neither describes **the realm** — so the pass could summarise a corpus that had not finished
arriving. That is not hypothetical: it is why production's `content_ref` was empty and CR-5 inert
fleet-wide.

The sequence, measured on a device: a `TENSHU_STORAGE_FORMAT_VERSION` wipe (format 21, EI-1) empties
the store → the pass walks that **genuinely-but-transiently empty** realm, `entries.isEmpty()` and
`walkComplete` both hold → it latches → the corpus re-syncs **behind the latch**. The device ended
with **1 596 files, a latched marker and zero declarations**. ⚠ The marker lives *in* the store, so
the wipe clears it too — which is precisely why it re-latches on the empty intermediate state rather
than surviving as a stale `true`.

⇒ The pass now **does not run at all** until `isCaughtUp` (`syncManager.backfillCursor == 0L`), the
shape `SliceSnapshotWriter` uses. ⭐ Skipping beats declaring-without-latching on both counts:
declaring early buys nothing, because content with no declaration is unreachable by CR-5 **by
construction** (§9.5 — a candidate must be *described*), so it is already safe; and a partial pass
costs one manifest `GET` per content object **every idle+charging slot** until catch-up ends, for a
pass designed to run once.

⚠ **It is the *backward* predicate, deliberately.** `backfillCursor` tracks the catch-up walk
descending from the tip; the forward pass has its own cursor, and the two walk toward each other and
meet (`SyncManager.backfillCursor`). "Has the historical corpus arrived?" is a question about the
backward half only — the forward half is always at the tip — so do not "fix" the asymmetry by
folding in `lastSyncTimestamp`. Its three states are also load-bearing: `null` (never started) and
`> 0` (descending) must both read as *not settled*, which is why the test is `== 0L` and not a
null-tolerant comparison.

⚠⚠ **The fix cannot reach already-latched devices**, because they never run the pass again. The
migration is the format bump `BACKFILL_FORMAT` `v1-refs` → **`v2-settled`**, which is the lever
[BackfillComplete] documents for exactly this. ⚠ The format is compared with `in`, not equality, so
a device that has run several formats keeps its history.

#### ⚠⚠ 9.7b Why production's tables were empty — the relay never wrote to them

**This section's first two answers were both wrong.** The record matters, because each wrong answer
was well-evidenced and still false.

1. *"Nothing has ever been declared."* Wrong — §9.11's 09-09 pass declared **55 edges / 99 refs**.
2. *"The ordinary lifecycle consumed them"* (tombstone → `undeclareHolder` → candidate → reclaim →
   `forgetContent`). Also wrong: on the day it was tested there were **zero** undeclares and **zero**
   tombstones, and the tables were still empty.

**The actual cause.** `Application.kt` built `SqliteContentEdgeStore` and `SqliteContentRefStore` —
and the production `module(...)` call **never passed them**. Every parameter of `module` carries an
in-memory default so tests and dev mode need no wiring, so the defaults won *silently*: content edges
and ref sets lived in a `ConcurrentHashMap` and were lost on every restart. **Nothing was ever
written to `content_edge` or `content_ref` on production.**

The observable signature was an impossible one, which is what finally forced the right question:
`POST /sync/content/declare` answering **`200 OK`** while both tables stayed at `0`, with no
undeclare and no tombstone to explain it — and `content_blob` rows from the *same requests*
persisting correctly, because that store *was* passed.

⇒ Consequences, all of which were previously blamed on other things:
- **CR-5 was inert for its whole life.** Its candidate set is `described − referenced` over stores
  emptied by every restart.
- **The CR-6e inventory always reported `describedContentCount = 0`.**
- **§9.11's 55 edges vanished at the next relay restart** — the reviewer's "where did they go?" has
  this answer, not a restore and not the lifecycle.
- ⚠ The backfill latch (§9.7a) is a **real and separate** bug. It explains why no *new* declaration
  was attempted; it never explained the empty tables.

⚠⚠ **Why no test caught it.** `TestRelayModule` passes the stores **explicitly**, so every route test
was green exactly where production was broken — a harness laxer than production hides production's
bugs. Both halves are now closed: `module` **refuses to boot** when a database is configured and a
content store is in-memory (the `s3PartiallyConfigured` posture), and the test harness defaults to
the durable stores whenever the config names a database.

#### ⭐⭐ 9.7c Confirmed on hardware (2026-09-12) — and the re-arm that had to be spent twice

Both halves of the split signal are green, measured on a real device against the production relay:

| | |
|---|---|
| **emit-time** | one upload ⇒ 3 `content_edge` + 8 `content_ref` rows, read from the relay's SQLite file |
| **backfill** | `declared 7990 edges, 9929 refs, 3524 without refs — recorded complete` ⇒ `content_edge = 7996`, `content_ref = 9879` |

⭐ The holder hash is a **keyed EI-1 wire id** (hex, not the old decimal `Long`), so EI-1a is
confirmed on the wire in the same observation. `3524 without refs` is the expected thumbnail-class
population (§9.11), not a completeness failure, and the sweep correctly offered no candidates —
everything just declared is also referenced.

⚠⚠ **The `v2-settled` re-arm was wasted, and the reason generalises.** It shipped *before* the relay's durable content stores were wired, so
the pass it re-armed ran against a relay that answered `200 OK` into a `ConcurrentHashMap`. Every
batch was accepted, so the pass latched **legitimately** — and a legitimately-latched marker is
exactly the one nothing re-opens. The device that proved it held marker `v2-settled`, 1 597 files and
**3** edges, all from the inline emit path.

⇒ ⚠ **A `BACKFILL_FORMAT` bump is a one-shot the fleet can only spend once per format**, so check the
sink before spending it: `sqlite3 -readonly relay.db "SELECT COUNT(*) FROM content_edge"` must be
able to grow. `v3-relay-durable` is the re-spend.

#### ⭐⭐ 9.7d The number §9.9b asked for, decomposed (2026-09-12)

First reading against non-empty tables — before this the figure was 100 % by construction and said
nothing (§9.7b).

| | |
|---|---|
| blobs | **15,090** — 5,353,874,850 B |
| described content | 4,443 · referenced (live edges) 7,955 |
| CR-5 candidates | **0** |
| **undescribed** | **5,211 blobs — 2,669,657,864 B ≈ half the store** |

**What it is not.** ⭐ **Zero** undescribed blobs are manifests of known content, so the 3,512
edges-without-refs cost nothing here — their thumbnail classes were never emitted, so no manifest and
no chunks exist (§9.11's population, confirmed). ⭐ Slice snapshots are bounded by the **folder**
count (`FileSliceSnapshotProducer.sliceKeys` walks folders, ~10 in this realm), so CR-6a is a
correctness fix, **not a size lever** — the "stable floor" of §9.7b is ~10 blobs, not gigabytes.

**What it is.** The size histogram is near-symmetric with the described set in the large bucket:

```
             undescribed                    described
  >2M        992 blobs  2,080,479,024 B     993 blobs  2,082,576,201 B
  512K-2M    273                            299
  64K-512K  1281                           1189
```

⇒ **Superseded upload attempts.** A retried emit re-encrypts with a fresh nonce, so the same file
yields different ciphertext and a different ref (§P1: byte-idempotency would need a derived nonce,
which this project rejects on privacy grounds). The final attempt's refs are declared; every
abandoned attempt's are not. 992 ≈ 993 is one orphaned twin per large described blob — the 4.0×
amplification measured on 2026-09-10, accumulated.

⚠⚠ **Corrected 2026-09-13 — "undescribed" was not the same as "garbage", and this section said it
was.** Every edge behind these figures is `tenshu/file/…`; **zero** are `tenshu/health/…`, because
the walk was declaring Drive only (one unenumerable type stopped the walk seeing any other). The realm holds **270 `Workout` rows** whose `Streams` is
`Syncable(OnDemand)` with real content-plane bytes, so **live, undeclared content is inside the
5,211**. What survives the correction is narrower than what this section first claimed:

| | holds up? |
|---|---|
| the **>2M** bucket — 992 vs 993, 2.080 GB vs 2.083 GB | ⭐ **yes** — a twin per large live blob is superseded uploads, and it is ~78 % of the bytes |
| the **smaller** buckets — 2,387 under 64K, 1,281 under 512K | ⚠⚠ **contaminated** — exactly where 270 workouts' stream blobs land |
| *"half the store is garbage"* | ⚠ **no** — most of the **bytes** are; the **count** includes live data nobody had declared |

⇒ ⚠⚠ **Nothing may be reclaimed on this figure until a walk that covers every declaring type has
run** (the walk is fixed; `BACKFILL_FORMAT` `v4-full-walk` re-arms it). Reclaiming on the
uncorrected number would have destroyed 270 workouts' streams — the blob-GC data loss's shape. ⭐ The check that
catches this class of error in one line: **the edges must name more than one service** —
`SELECT property_id, COUNT(*) FROM content_edge GROUP BY 1`. Persistence was verified here;
**coverage was not**, and that was the gap.

⇒ **Consequences for the remaining phases:**
- **P1 has already stopped the generator** (resume-not-restart). This population should now be
  *static*, and re-reading the gauge is how that gets confirmed — the first prediction this
  instrument can be held to.
- **CR-6 (P5) is justified by size**: 2.67 GB is reachable by nothing else. ⚠ Neither CR-5 (needs a
  description) nor ST-3b (needs a manifest, which an emit aborted mid-chunks never wrote, §9.9b)
  can touch it.
- ⇒ **P3's premise holds**: keeping ST-3b does *not* protect this population, so it is not the
  conservative choice it looks like (§9.10).
- **P4 (dedup) would not have prevented it** — the duplicates are re-attempts within one emit, not
  two files with equal content.

⭐ **Which is why the confirmation signal has to be split.** `describedContentCount > 0` alone cannot
distinguish a healthy backfill from a healthy emit path, and a green backfill would mask a dead one:

1. **Emit-time** — upload one new file on a settled device and watch a single
   `POST /sync/content/declare` follow its chunk `PUT`s.
2. **Backfill** — on a device with a pre-existing corpus, watch one large batched declare after the
   marker re-arms.

⚠ A 0 `describedContentCount` in the CR-6e inventory (§11.11) still means *the mechanism has not
run*, never *nothing is stranded*.

⚠⚠ **The constraints are about COST, which is the opposite of its neighbour.** A slice snapshot is
never correctness-bearing, so it may never run. This *is* the thing that unstrands the corpus — but
a ref is `sha256(ciphertext)`, so describing one content object means fetching and decrypting its
manifest: one `GET` each. Fine once, ruinous per tick.

**Three properties, each of which fails quietly if it is wrong:**

- ⚠⚠ **Batched by REF COUNT, not edge count.** The relay caps a request at 8 192 refs, and 64 edges
  of a large file's ~1 000 refs is 64 000. Every such batch 400s, no batch is ever accepted, and the
  marker never latches — a backfill that runs for ever and finishes nothing, reporting failure to a
  log nobody reads. `noBatchCanExceedTheRelaysCeilings` is that guard; the edge-count mutation
  produces a 40 040-ref batch.
- ⚠⚠ **The marker latches only on a clean run** — where *clean* means **"I saw everything"**, not
  **"everything was describable"**. A partial walk or a refused batch blocks it; a manifest the relay
  does **not hold** does not.
  ⚠⚠ **"Unresolvable" is three facts, told apart** (the fetch answers null for all of them):
  the pass asks the relay about the manifest ref (`hasChunk`). A **404** is content that never existed
  and does not block. **No answer** (a timeout, a 5xx, a probe that could not ask) **blocks**: the
  content was declared without its refs by accident, and the pass runs again. A manifest the relay
  **holds** is fetched once more; still unreadable, it is reported and does **not** block, because it is
  permanent and would re-run the pass for ever. Fetches and declares are bounded (60 s, 180 s), and the
  pass prints its walk, its progress every 500 manifests and each declare batch.
  ⚠ The scheduler runs one pass per process (a second caller is skipped), holds
  `BackgroundLiveness.CONTENT_MAINTENANCE_PASS` while it runs so the proxy asks for its foreground
  service, and the pass **stops after 10 unanswered fetches in a row, declaring nothing**. A stopped
  pass is not complete and runs again.
  ⚠ What got no answer is **tried once more at the end** before it may block the marker: on a phone a
  pass of 8,844 fetches ends with a few transient failures every time, and without the retry it would
  never complete. A fetch that still gets no answer on the retry blocks as before. The retry has the
  same cutoff as the scan: 10 unanswered in a row stops the pass, declaring nothing.
  ⚠ **A content the relay answers 404 for is handed to the device's repair after the declares**
  (`repairAbsent` → `localBytesContentEnsure` → `SyncObserver.ensureNamedContentOnRelay`). It acts only
  for a file's own bytes that this device holds, and it **ensures, never forces**: on the uploading
  device "no manifest" is also an upload in flight or journalled, and a forced re-emit there is §9.18's
  duplicate. The holder must still name the content the walk read, checked under the content's emit
  key, and the bytes are hashed as they stream. Content repair is enabled on all three platforms since
  this change. The pass re-armed as `v8-repair-manifestless` for it: a device latched at v7 returns
  before the repair step. ⚠ **A pass that handed anything to repair, or whose repair threw, does not
  record itself complete**: the next pass finds the manifest, declares its refs and latches. ⚠ Those were conflated until the first device pass (§9.11) showed the
  conflation made the marker unlatchable on every device for ever. ⚠ Its value is a **format string**, not a boolean, so bumping `BACKFILL_FORMAT` re-runs the
  pass everywhere — which is what a change like CR-4b's added ref set would have needed.
- ⚠ **One manifest fetch per contentId, not per entry.** A file and its five thumbnail classes are
  five entries with five distinct ids, but a copy or a trash/restore legitimately repeats one, and
  refetching per holder multiplies the only expensive step by the sharing factor.

⭐ **Every failure direction here is a leak, by construction.** Declaring **more** is always safe —
an edge protects content from becoming a candidate, and an edge naming bytes the relay does not have
is inert (the keep set gains a ref the blob store has no row for). Declaring **less** leaves content
undeclared, which under §9.5 makes it unreachable rather than reclaimable. That is why a partial
walk may still declare what it found, where the veto's partial walk may not be used at all.

⚠ **A content object with more refs than one edge accepts is declared WITHOUT them.** Sending them
would 400 the batch and stall the pass for ever on a file nobody can act on. Declared-without still
protects it; it simply can never be freed, and it counts against completeness so a later pass
retries.

⚠ **The second producer is still missing, and is still not this slice.** `WorkoutStreamsPropertyWriter`
does not override `resolveContentId`, so Fit declares nothing and there is nothing of Fit's to walk —
which is exactly why §9.5's two gaps cancel. Giving Fit a content id is its own decision (it has no
`contentId` field, and deriving one from `workoutId` would reuse the id across a re-record and
re-open the overwrite leak) and it **must** land together with a Fit producer, or it breaks §9.6.

⚠⚠ **Nothing frees yet.** CR-4d only *describes*. CR-5 is still unbuilt, so the corpus becomes
reclaimable rather than reclaimed — and the first device run should be read for
`declaredWithoutRefs`, which counts content this pass could describe as an edge but not as bytes.
### 9.8 ⭐⭐ As built — CR-5, the only phase that frees

```
relay candidates  = described (content_ref) ∧ no live edge names it (content_edge)
  ∩ this device's live walk does NOT name them          ← the veto
  → POST /sync/content/reclaim
```

⭐ **The client is told contentIds, never refs — and that is the design decision.** It means the
reclaim can only free bytes the relay itself attributes to a *described* content object, so §9.5's
**"NEVER touch an undeclared ref" is enforced relay-side** rather than left to a client's
discipline. A client that has lost its mind can name a contentId; it cannot name a blob.

⇒ ⭐ **§9.3's five undeclared populations become unreachable rather than dangerous.** Population 5
closes itself for free: chunks are `PUT` before the declaration, so an upload in flight is
*undescribed*, and undescribed content is never a candidate. No age window was needed after all.

**Two routes.** `GET /sync/content/orphan-candidates` (the difference of the two tables, capped at
256) and `POST /sync/content/reclaim`, which ⚠⚠ **re-derives candidacy itself and skips anything an
edge has reappeared for** — the race between the client's read and its call is closed there, not
trusted from here. `skippedStillReferenced` is non-zero exactly when that happened.

**The veto is the client's whole contribution, and it is load-bearing for two live cases:**

- the **recreate** (ADR 0006) — a peer that has not consumed the tombstone writes a later HLC;
- the **move** — `MoveFileImplementation` emits an old-path tombstone and does *no content re-emit*,
  so the edge is dropped for a file that is still there under a new path.

⚠⚠ An **incomplete** walk reclaims *nothing*, never a subset. A short live set names fewer live ids,
which makes **more** candidates look orphaned — the walk failing makes the sweep *more* aggressive.
That is the blob-GC data loss's direction, and it is why `LiveContentIds.Result.Incomplete` carries no partial set
to be tempted by (§7b.3 #3).

⚠ **Ships inert.** `ContentEnumerationSweep` follows the same `reclamationDryRun` switch the ST-3b
sweep does, and a dry run is additionally handed a `reclaim` that is not the relay call — two
independent reasons it deletes nothing, so removing either alone changes nothing. Both arms are
tested. Turning it on is a deploy-time decision with a report behind it.

⚠ It runs on the backfill's pass, **after** it: describing is what makes content visible to
enumerate, so the order is causal. Each half is separately guarded, so a backfill that threw does
not stop the sweep from acting on what was already described.

### 9.9 ⚠⚠⚠ What CR-5 cannot reach — including the 4.7 GiB this track was opened for

**Everything undescribed**, by construction. That is the safety property. It is also, and I stated
this wrongly across CR-4d and CR-5, **the existing orphan population itself**.

⚠⚠ **The correction, stated plainly.** The backfill walks **live** content. An orphan is by
definition content no live row references — a superseded overwrite, a delete whose candidate was
lost, an upload whose manifest never landed. So the backfill never describes it, so it is never a
candidate, so CR-5 never frees it. Calling CR-4d *"the only route to the 4.7 GiB"* — which CR-4d's own description and
this doc's phase table both did — was wrong.

⚠⚠ **And the trade was silent.** §6's table records option C as *"recovers today's orphans: **yes**"*,
and it did: C as drawn started from the relay's **full blob list** and subtracted the live set.
§9.5 replaced that with a positive rule sourced from `content_ref` — for a good reason, since the
subtractive form deletes every Fit and shared-realm chunk today — but the exchange gave up C's
headline benefit and nothing said so until now.

⇒ **What the track as built actually delivers**: no *new* orphans. An overwrite or a delete of
content described since CR-4 is reclaimed deterministically, which is the structural fix §3 asked
for. The pre-existing pile is untouched.

### 9.9a ⭐⭐ CR-6 is NOT a legacy cleanup — the system leaks continuously without it

**Decided 2026-09-09.** §9.9 framed CR-6 as the way to reach the existing 4.7 GiB. That is true and
it is the smaller half. The larger half is that **CR-5 alone leaves the system leaking**, and the
leaks it cannot see are precisely the ones failure produces.

⚠⚠ **The structural reason, in one line: the positive rule can only free what it was TOLD about, so
it is blind to every failure of the telling.** That is not closable by making the telling more
reliable — a best-effort declare over a network can always fail, and one generator below orphans by
construction even when nothing fails at all.

**Five ongoing generators, all verified in code rather than argued:**

| generator | why CR-5 can never see it | rate |
|---|---|---|
| **A retried upload** | `SyncObserver` mints a **fresh CEK per emit** — the code says so: *"a re-emit of the same content re-keys + re-PUTs its chunks, orphaning the old blobs"*. New ciphertext ⇒ new refs; the previous chunks are orphaned and were never described under any surviving contentId. | every retry |
| **An emit that fails after its chunks** | chunks are `PUT` first, the manifest second. A failure between strands the chunks with no manifest, so nothing describes them. | every failed / over-quota upload |
| **Fit — which IS the "legacy manifest path"** | `WorkoutStreamsPropertyWriter` has no `resolveContentId`, so the emit takes the same `else` branch: manifest addressed by entity id, chunks on the plane, no edge, no refs. ⚠⚠ **This is by design and shipped** — `fit-workout-streams-content-plane.md` (COMPLETE 2026-07-23) states it: *"A workout has no `contentId` value property, so the manifest is addressed by entity id (the documented pre-content-addressing fallback)"*. Its migration row decided *"drop pre-existing streams"*, which is about old rows and gives Fit no contentId. | every deleted workout |
| **A failed declaration** | best-effort by decision (§9.2). The content is live and undescribed; delete it before a backfill runs and it is permanent — the backfill walks **live** content. | transient network |

⇒ ⭐ **CR-6 is the backstop that makes the design leak-*free* rather than leak-*bounded*.** That is
what §6 meant by *"B and C are complements, not rivals"*; §9.5's positive rule quietly demoted C from
backstop to nothing, and §9.9's correction only noticed the legacy half of the loss.

⚠⚠ **A reset of the test environment does NOT dispose of this** (corrected 2026-09-09). It disposes
of the existing *corpus*; it does not touch the *generators*. In particular the "legacy manifest
path" is not a legacy population at all — **Fit is its current principal user**, so after a reset
every workout still emits down it and still leaks a stream blob on deletion.

### ⚠⚠ CR-6's real precondition: every content-plane WRITER must declare

Not "description is complete" in the abstract — an inventory, because `undescribed ⇒ orphan` is only
sound if nothing legitimate writes without declaring. Today at least one does:

| writer | declares? | consequence for CR-6 |
|---|---|---|
| `SyncObserver.emitFileContentNow` | ✅ since CR-4 (content-addressed branch only) | fine |
| **`SliceSnapshotWriter`** | ❌ **never** | ⚠⚠ a subtractive sweep would reclaim **live joiner snapshots** |
| the legacy `else` branch (Fit, and any null `resolveContentId`) | ❌ | the generator above |

⭐ Slice snapshots do **not** accumulate orphans — `RelationSliceSnapshot.ref` is
`sha256("snapshot:$realmId:$relationId:$sliceKey")`, deterministic, so a re-seal overwrites the same
ref. ⚠ But they are undescribed *live* blobs, so CR-6 must either have them declared or exclude them
by construction (their refs are derivable, which makes exclusion cheap). Getting this wrong is not
data loss — a joiner falls back to replaying the log — but it would delete the thing that exists to
make bootstrap fast, and the next maintenance pass would rewrite it: a delete/rewrite churn loop.

⭐ **And the ongoing case is on firmer ground than the legacy one.** Once Fit declares and the legacy
path is closed, *"undescribed, and older than the in-flight window"* **is** the definition of an
orphan — so subtraction is not a heuristic there, it is the definition. Legacy content is merely
*old*, which is a weaker claim. If CR-6 is built for one case, the ongoing one is the better
justification.

⚠ Unchanged: subtraction is the dangerous direction (the blob-GC data loss), so it still needs the completeness
gates below. What changes is that those gates buy an ongoing guarantee, not a one-off tidy-up.

### 9.9b ⭐ CR-6 — what would reach it, and what that is gated on

Subtraction is safe **once description is complete**. If every live content object in a realm is
described, then `relayBlobs − refs(described)` is exactly the orphans, and the populations that make
subtraction dangerous today are gone by construction rather than by luck:

- **Fit** declares since §9.12, and landed with the walk coverage §9.6 requires. ⚠ **Only for
  workouts recorded after it.** `resolveContentId` reads `Workout.ContentId`, which is minted at
  record time and never backfilled, so an earlier workout's streams answer `null` and stay
  undeclared — they belong to the legacy-manifest population in the bullet below, not to a Fit gap.
- **The legacy / shared-realm manifest path** must declare, or be shown extinct.
- **Every device's backfill must have completed.** ⚠ `declaredWithoutRefs` need *not* be zero, and
  cannot be (§9.11) — but CR-6 owes a distinction the backfill cannot currently draw:
  `contentChunkRefs` returns `null` both for content that **never existed** (harmless — there are no
  blobs to mis-subtract) and for content that **exists but will not resolve** (dangerous — its blobs
  are real and would read as orphans). Today those are one value.

⚠⚠ The third is the hard one and it is a **realm-wide** property, not a per-device one: one device
that never finished its backfill makes every content object it alone holds look orphaned. That is
the same unanimity problem the reclamation barrier has, and it should be read alongside
`sync-retention-horizons.md` rather than re-derived.

⚠⚠ **"It is not growing" was wrong, and the rate is now measured (2026-09-10).** One 20 MiB upload
over a flaky link cost **3.00×** on the relay, and a delete afterwards returned only 1.00× of it:

| | bytes | |
|---|---|---|
| after upload | **+62,922,315** | 3.00× the file — three complete chunk sets |
| after delete + sweep | **−20,977,155** | 1.00× — the one set that was described |
| stranded | **41,945,160** | 2 sets, reachable by nothing |

Each aborted emit re-keys with a fresh CEK and re-`PUT`s **every** chunk, so the multiplier is the
number of attempts. ⇒ The pile is not a one-time pre-recorder residue after all: there is a live
generator whose rate is the link's flakiness, and CR-6 is what reaches what it leaves.

⚠⚠ **CEK reuse alone cannot fix it — the ciphertext is not deterministic.** Both AEAD backends draw
a fresh random 24-byte nonce per encryption and prefix it (`nonce (24) || ciphertext || tag (16)`,
`BouncyCastleAeadBackend` / `NobleAeadBackend`), so the same CEK over the same plaintext still yields
a different `sha256(ciphertext)` and therefore a different ref. Changing the rule from *"fresh per
emit"* to *"fresh per content"* ships a no-op that reads like a fix. Byte-idempotency needs a
**derived nonce** as well — convergent encryption — which hands the relay dedup visibility inside the
realm (identical content ⇒ identical refs, across devices and across delete-then-re-upload). That is
a threat-model change (HR #2), not a rule change, and it is the reason this is not simply done.

⭐ **The fix that costs no disclosure is to resume rather than restart**: journal the refs an emit has
already `PUT` beside its `PendingContentEmit`, and on retry send only what is missing. The ciphertext
stays randomised, so the relay learns nothing new, and nothing is orphaned because nothing is
re-encrypted. ⚠ The entry must also carry the **CEK** — an emit cannot be finished without the key
its landed chunks were encrypted under — and that is the half with threat-model weight (key material
at rest); the refs are `blob-` hashes and carry no user data. Adding both is a stored-shape change to
`PendingContentEmit`, so HR #9 applies.

⚠⚠ **Describing dead content by walking surviving manifest blobs cannot work — do not re-propose it.**
`manifestContentRef(contentId) = "blob-" + sha256("manifest:$contentId")` is one-way, and
`cekWrapAad` binds the contentId into the manifest's AAD, so the id is needed to read the very thing
that would reveal it. An orphan is exactly the content whose id nobody remembers. The existing pile
is subtraction or the pre-release reset, with nothing in between.

⚠ The two numbers to read from a device pass are the backfill's `declaredWithoutRefs` and the
sweep's `candidates` / `vetoed`.

⭐ **A migration exists for the old workouts, and it is cheap at exactly one moment**: re-emit them
with a minted `Workout.ContentId`. The client holds the old chunk refs while it re-emits, so it can
drop them in the same pass — the only point at which they are nameable.

⚠⚠ **Fit declares, and the old workouts do not — which is the shape of CR-6's remaining problem,
not a Fit bug.** `SyncObserver` keys a non-content-addressed emit on
`targetEntityId.getDataIdHash()` and writes its manifest as a mailbox block rather than a
`manifest:` blob, so a pre-§9.12 workout's chunks are real blobs that no description names. Under
subtraction they read as orphans. Every population in that bullet list is this same shape, and the
count that matters to CR-6 is how much content predates description — not how many types declare.

### 9.12 ⭐⭐ As built — `Workout.ContentId`: one generator removed, and the veto follows

Fit now declares. `Workout` carries a `contentId`, so `WorkoutStreamsPropertyWriter.resolveContentId`
answers and the emit takes the **content-addressed** branch.

⭐ **Two things turn on it, and only one is reclamation:**

- **The manifest leaves the ordered plane.** Without an id the emit put a manifest **block on the
  ordered metadata drain** for every workout — paying the expensive plane while getting none of
  content addressing's benefit. Catch-up is sequence-bound (7.5 seq/s measured in `SyncScheduler`),
  so those blocks cost every future device for ever and **no sweep could ever refund them**. This is
  the half that justified the change on its own.
- **Fit's content becomes reclaimable**, so §9.9a's largest generator is *removed* rather than
  backstopped by CR-6.

⚠ **A `valueProperty`, not a composite field** — the shape `File.ContentId` uses. A named slot
reshapes no stored row, so this cost **no service-version bump and no migration**: only a
`PropertyKindBaseline` line (`…/contentId|…/workout|INLINE`), which is the "additions get recorded"
path. A positional field would have owed an HR #9 bump and a wipe risk for nothing.

⚠⚠ **Random per emit, never derived from `workoutId`.** A derived id is stable across a re-emit, so
re-recording would overwrite the manifest in place and orphan the previous chunks under an id that is
**still declared** — reachable only by a subtractive sweep. A fresh id leaves the superseded content
declared-with-no-live-edge: a CR-5 candidate, self-cleaning.

### ⚠⚠ 9.12b The veto had to move with it — §9.6b's reasoning was wrong

§9.5 warned: *"give `Workout.Streams` a `resolveContentId` and Fit content becomes a candidate that
the Drive-only walk cannot veto — which is the loss."* This change fires exactly that, so the veto
moved to `SchemaDrivenContentWalk` — the same walk the backfill uses, one instance, so the two roles
cannot diverge.

⚠ §9.6b had kept the veto on the tree walk to avoid *"a capability regression on JS"*. **That was
wrong: JS wires no sweep at all** — `reclamationSources` appears nowhere in `tenshu-sdk-js` — so
there was nothing to regress. All three platforms that do sweep have enumerable stores
(`SqliteObjectStore` on Android and desktop, `FileObjectStore` on iOS), and the tree walk survives as
the fallback.

⭐ This also retires the accident §9.5 flagged: the two gaps no longer *cancel*, they are both
closed. *Declared ⇒ walkable* now holds because the veto walks whatever declares, not because the
undeclared thing happened to be unreachable.

### 9.10 ⚠⚠ CR-2's gate — and why doing it early strands bytes for ever

CR-2 deletes `ContentReclamationRecorder`, the candidates ledger and the barrier. **It is one atomic
removal, and it is gated on a measurement nobody has taken.**

⚠⚠ **The two mechanisms do not cover the same content, and the difference is the pre-CR-4 corpus.**
ST-3b resolves a candidate's refs by **fetching and decrypting its manifest**
(`SyncProcessor.contentChunkRefs`), so it works for *any* content whose manifest exists — described
or not. CR-5 can only ever act on content the relay has a **description** for.

⚠⚠⚠ **And the window is not merely a delay — it is permanent.** The backfill describes **live**
content. A pre-CR-4 file that is *deleted* after CR-2 but before the backfill described it can never
be described afterwards (it is gone) and can never be a CR-5 candidate (it was never described) —
and ST-3b, the one thing that would have caught it, has been removed. **Those bytes are stranded for
ever**, which is precisely the failure this whole track exists to eliminate.

⇒ **The gate is a device pass, not a code change:**

1. `BackfillContentDeclarations` has actually run on each real device — it is idle+charging and
   once-per-device, and has never run on hardware.
2. Its `declaredWithoutRefs` is **read**, not assumed — ⚠ but **not required to be zero**, which
   §9.11 explains: the walk legitimately declares edges for thumbnail classes that were never
   emitted, so a healthy realm carries a non-zero count for ever. What matters is that the number is
   *explicable* (content that never existed) rather than *surprising* (content that should resolve
   and does not).
3. `ContentEnumerationSweep`'s `candidates` / `vetoed` look sane against a realm whose contents are
   known.

⚠ Only then does removing ST-3b lose nothing. Until then the two run side by side, which costs a
little duplicated work and no correctness.

⚠⚠ **The gate is not something a device pass can fully clear, and the 2026-09-10 review is right
about why.** ST-3b resolves refs from the *manifest*, so it reaches content that is described-in-fact
but never declared — a manifest that landed while its `declare` call did not. Nothing else reaches
that population, so removing ST-3b loses it permanently rather than temporarily. CR-2 is therefore a
**hold that lifts when something else covers undescribed content**, not a pass/fail a device run
settles.

⚠⚠ **And the population is wider than that.** An emit that aborts *mid-chunks* never writes a
manifest at all, so ST-3b cannot resolve its refs either — measured 2026-09-10 at 41,945,160 B from a
single upload (§9.9b). Those bytes are reachable by neither mechanism today, which is a point in
favour of doing CR-2 sooner rather than later: keeping ST-3b does not cover them, and while both run
CR-5 cannot be observed acting at all (§9.13).

### 9.11 ⭐⭐ The first device pass — what it measured, and the bug it found

Run 2026-09-09 against the real realm, on a relay deployed from master.

```
ContentDeclarationBackfill -> declared 55 edges, 99 refs, 14 without refs — recorded complete
ContentEnumerationSweep    -> no candidates offered; nothing to do
[second pass]              -> skipped: already backfilled at this format
```

**55 = 11 files × 5 content-bearing properties** (data + the four thumbnail classes of the day; `XXLARGE` made it six per file in 2026-09) — the schema-driven
walk found exactly what it should. 99 refs over 41 described contents is ~2.4 each, i.e. one chunk
plus one manifest: small files. The sweep finding nothing is correct, because content described from
a *live* walk still has a live edge.

⚠⚠ **The bug it found, which no unit test could have.** The first run ended *"NOT recorded complete,
will run again"* — and would have, for ever. `FileBytesPropertyWriter.resolveContentId` answers for a
thumbnail class whenever the file has a `contentId`, without checking that the thumbnail was ever
emitted: a text file has none, an image may have three of four. So the walk legitimately declares
edges for **content that has never existed**, whose manifests are *correctly* unresolvable — 14 of 55
here. Counting those against completeness turned a once-only pass into an every-pass one that
re-fetches every manifest, and made §9.10's *"`declaredWithoutRefs` is zero"* gate unsatisfiable by
construction.

⇒ **Completeness asks "did I see everything?", not "was everything describable?"** A walk that did
not finish, or a batch the relay refused, still block the marker — those mean the next pass has work
this one did not do. An undescribable thumbnail does not.

⚠ It needed a real corpus where some thumbnails genuinely do not exist, which is what a device pass
is for and what a fixture would have papered over.

⭐ Validated in passing: the marker **persists and short-circuits** the second run, *and says so* —
visible only because of the same day's fix making every exit report. Without it the second pass was
silent and indistinguishable from one that never ran.

⚠ **Still unproven: the forward path.** Nothing has been deleted, so no candidate has ever been
produced. That half needs a throwaway file rather than one of the realm's eleven.

---

### 9.13 ⭐⭐ The forward-path pass — CR-5 reclaims on hardware, and what still is not proven

2026-09-10, OnePlus CPH2581, the realm §9.11 measured. ⚠ The device's installed Control carried the
backfill and the walk but **not** `ContentEnumerationSweep` — CR-5 had never run on hardware at all,
which is why §9.10's third point was open.

Run in two builds, observe before act (`RECLAMATION_SWEEP_ENABLED = false` leaves
`reclamationDryRun` true while `RECLAMATION_DRY_RUN_ENABLED` keeps the sources wired):

| step | observed |
|---|---|
| baseline | `no candidates offered; nothing to do` — `content_ref ∖ content_edge` = ∅ |
| one file deleted, dry run | `would reclaim 1 of 1 candidates (0 vetoed); nothing was deleted` (twice, identical) |
| several deleted, acting | **`reclaimed 5 of 5 candidates (0 vetoed)`** |
| next pass | `no candidates offered` — converged |

⇒ **§9.10 point 3 is discharged.** Candidates track deletes one-for-one, the walk vetoes nothing it
should not, `/sync/content/orphan-candidates` and `/sync/content/reclaim` both work against the
production relay with a real device credential, and the sweep converges instead of re-offering.

⚠⚠ **ST-3b masks CR-5, and this changes how the gate reads.** The recorder resolves and frees a
candidate about **two seconds after the delete**, so while both mechanisms are live the sweep
almost always arrives to an empty list — the acting pass above only saw five because they were
deleted faster than ST-3b cleared them. The dry run was the one configuration that could show
CR-5's *view*, precisely because it consumes nothing. **You cannot watch CR-5 act by waiting for it
while ST-3b is present**; a future pass wanting that evidence should run the observe build, or run
after CR-2.

⚠⚠ **What is still NOT proven: bytes.** Every probe file's chunk `PUT` failed (below), so the
reclaimed contentIds had **no blobs on the relay**. The *control* path — declare → undeclare →
candidate → veto → reclaim — is proven end to end; **byte accounting is not**. A reclaim that
demonstrably shrinks stored bytes still owes a pass on a device that can upload.

⚠⚠ **The blocker: every upload aborted, and the durable half of that is the missing retry.** Eight
attempts, each `file-content chunk PUT failed — aborting manifest emit — Unable to resolve host
"relay.tenshu.app"`.

⚠ **The cause was environmental, and an earlier draft of this section said otherwise.** The device
was in a burst of intermittent DNS resolution: the *metadata* plane logged the identical error in
the same window (`inbound pull failing`, `pending-blocks push stalled`, each recovering seconds
later), so the contrast between a failing `PUT` and a succeeding `GET` 30 s afterwards shows
**intermittency, not a path-specific defect** — the reads sampled good windows and the eight uploads
all fell inside a ~15-minute bad one. The relay was up throughout (`/health` 200, and the reclaim
below succeeded against it). ⚠ One hypothesis was tested and disproved on the way: that the relay
being IPv4-only (A record, no AAAA) collided with the phone's IPv6-only mobile interface —
disabling mobile data reproduced the failure on wifi alone.

⚠⚠ **This section also claimed "an aborted emit queues no retry". That is wrong, and the code says
so plainly.** `SyncObserver.emitFileContent` journals the intent as a `PendingContentEmit` **before
a single byte moves** — its first statement — and its failure path is commented *"a
failed emit deliberately leaves the intent in the journal for the drain to retry — that is the whole
point of writing it first"*. So an aborted emit **is** retried, by `drainDeferredContent`, and the
probe files above are queued rather than stranded.

⚠ **The reading that produced the wrong claim was a `pendingContentEmit` count of 3,628 before and
after eight uploads.** Journal-first means eight new `(entity, property)` keys should have moved it.
The count is therefore unexplained, and it is not evidence of anything — a live store read while the
app was running is the likeliest culprit. It is recorded here only so nobody re-derives a conclusion
from it.

⭐ **What a stuck journal actually indicates** is one of the drain's *designed* stops, and telling
them apart needs the device: the metered content gate closed (`contentGate.shouldSync()` false —
Wi-Fi-only on a cellular default route, which this phone had), `contentStorageRejected` latched by a
507 (⚠ §5's own table measured this realm at **94.07 % of 5 GiB**, so this is the live candidate), or
the drain not being ticked at all. None of them is a missing retry.

⚠ **Read a `skipped` reason as a symptom, not a diagnosis.** The sweep used to report *"the relay offers no
candidate query"* for **any** failure, and on the first attempt here that masked an HTTP 401 from an
unprovisioned device. Fixed 2026-09-10 — the store logs the status, the sweep names no cause.

### 9.14 ⚠⚠ Edges nothing can undeclare — two names for one holder, and a folder's cascade (2026-09-15)

**Measured.** Two large folders deleted on the OnePlus (929 files). ST-3b recorded **929
candidates** and reported `would reclaim 0 … waiting on 14 device(s)` on every sweep, while the relay
received 5 tombstones and 4 `undeclare` calls, offered CR-5 **zero** candidates, and freed nothing.
Of **8,010** `content_edge` rows, **7,990** carried a decimal local hash (the 2026-09-12 backfill) and
**20** a 32-hex keyed name (inline emits). Every tombstone since the device's EI-1 build carries the
32-hex name.

Two defects, either sufficient on its own to strand every one of those deletes:

1. ⚠⚠ **The backfill named holders by the local hash; the emit and the tombstone by the wire id.**
   EI-1a keyed the relay-visible name at the emit and at the tombstone. Both backfill walks
   (`SchemaDrivenContentWalk`, and `ProxyDeclarableContent` behind it) kept `getDataIdHash()`, with a
   comment asserting it was "the same value the emit declares with and the tombstone undeclares by".
   ⇒ **No backfilled edge could ever be undeclared**, so none of that content could ever become a
   CR-5 candidate. ⭐ Fixed: `DeclarableContent.Entry` carries the holder's `DataId`, and
   `ContentDeclarationBackfill` names it through the resolver the emit and the drain use
   (`WireIdResolver.ofOrNull`). An unnamed holder is skipped and blocks the marker.
   `BACKFILL_FORMAT` → `v5-wire-names` re-arms every device.
2. ⚠⚠ **A folder delete undeclared the folder alone.** ADR 0006 #7 sends one tombstone per delete
   operation, and §5.2's undeclare follows tombstones, so every file inside kept its edge. ⭐ Fixed:
   `DeleteFileImplementation` emits its tombstone with the removed descendants
   (`TombstoneEmitter.emitWithCascade`), and `drainPendingTombstones` undeclares them with the folder
   **after** its push, chunked at the relay's 512-holder cap.
   - ⚠⚠ **One call, and the names recorded before the block is queued.** An emit-then-record pair lost
     a race: the emit wakes the drain, which can push the block before the second call lands.
   - ⚠⚠ **One `TombstoneCascades` per process runtime**, beside `TombstoneRegistry`. Each connected app
     has its own `SyncObserver` feeding the one shared scheduler, so a per-observer map was invisible
     to the drain for every session but the first.
   - ⚠ In memory and best-effort: a process death before the push loses the names, which leaves edges
     standing. A leak, never a loss.

⇒ **Why these belong before P3 (CR-2).** §9.10's hold is "until something else covers what ST-3b
covers". ST-3b resolves refs from the manifest and ignores edges, so it was the only mechanism
that could ever free any of this. Removing it first would have made both leaks permanent.

⚠ **Separate, and not fixed here:** ST-3b's barrier waits on the phone's local `Device` rows, and 14
of them name devices the relay removed between 08-27 and 09-08. CR-2 deletes the barrier; nothing
in this section depends on it.

#### ⚠ What the fixes do not reach — standing edges

- **The 7,990 legacy-named edges.** `v5` re-declares live content under wire names, which adds
  edges and removes none (the key is `(owner, holder, property)`, and the holder name changed).
- **The 929 files deleted before the fix.** Their edges carry legacy names and a lost cascade.
- **A cascade lost to a process death, and any `undeclare` that failed** (best-effort by §9.2).
- **A folder move.** Descendants' old-path holders keep their edges until EI-2 makes a move
  preserve the wire id.

⚠⚠ **Conditional on the edge plane surviving, and it is not.** §6e (C′) retires it, which deletes
this problem class rather than reconciling it — no edges, none to go stale. ⇒ **The read half
below is CLOSED (2026-09-21)**: its read shipped as N2 in a different unit (`contentIds` rather than
`holderHash`), the 8,010-row corpus this section measures is gone (`content_edge = 0` after the realm
reset), and both causes of those stale edges are fixed independently — the backfill names holders
through `WireIdResolver`, and folder deletes cascade their undeclares.

⚠ **The residual, which is why this section stays.** The cascade is in-memory and best-effort, so a
process death before the push still leaves edges standing — a stale edge makes its content look
referenced and its bytes stay charged. A leak, never a loss, bounded by how long N6 takes, and what
C′'s enumeration pass addresses by design. If N6 slips far enough that charged-but-unreferenced bytes
become measurable, reopen the read; otherwise it is work with a scheduled demolition date.

#### ⭐ Design: reconcile the edge set against live holders

```
relay:  GET /sync/content/edges?after=<holderHash>&limit=N   → (holderHash, propertyId) page, owner-scoped
client: live  = wire names of every holder the schema walk enumerates     (walk must be COMPLETE)
        stale = relay edges whose holderHash ∉ live
        POST /sync/content/undeclare  stale   (≤ 512 per request)
```

- **When:** the backfill's slot (idle + charging), only on a caught-up device whose `v5` marker has
  latched, so its own declarations are already in the table under the names it compares against.
- ⭐ **Legacy names fall out with no rule of their own.** A decimal local hash never equals a live
  wire name, so it is stale by construction. No keyspace heuristic lives on the relay.
- **Safety.** Dropping an edge frees nothing by itself; it only makes content a CR-5 **candidate**.
  A live holder this device cannot see is still vetoed: the sweeping device's own live walk rescues
  it, and the relay's reclaim re-checks edges. The residue is §6c.1's recreate race, which (a)
  re-declare-on-recreate already owns. ⚠⚠ An incomplete walk runs **no** pass, never a partial one:
  a short live set is the blob-GC data loss's direction.
- **What the relay learns:** nothing new. It returns holder names the client itself declared, to
  the same authenticated owner.
- **Cost:** one paged read of the owner's edges (8,010 rows today) plus the walk the veto already
  performs.

**Open:** a stable cursor while edges change under the paging (holder-ordered `after`, restart on
a changed count); whether shared realms reconcile per owner store in the same pass; and whether
the pass should report what it dropped by property, which would also answer §9.12's missing
`tenshu/health/*` edges.

#### P3's gate, as it now reads

1. This section's two fixes deployed, and `v5-wire-names` latched on each device (read the report).
2. Reconciliation built and run once: `content_edge` holder names are all 32-hex, and CR-5 offers
   the pre-fix deletes as candidates.
3. ⚠ §9.12 says Fit declares, but production holds **zero** `tenshu/health/*` edges. Explain that
   before removing ST-3b, which may be the only path for that content today.
4. §6d's D3 parts (a), (b) **and** (c) built, since P3 removes the barrier rather than replacing it.
   ⚠ (b) is in the gate because a moved row that declares nothing survives on the veto alone, which
   CR-6 does not have; (a)'s pin is in it because an evicted cache is a repair source that was there
   when the move was made and gone when it mattered.
5. ⚠⚠ **Every admitted device runs a D3 build** — a *fleet* condition, not a code one. A pre-D3 device
   is unfenced by construction: it can move an online-only file offline, reconnect, and push a row
   whose content a newer device has already reclaimed. ⚠ It is the one part of the gate a
   **revocation** can satisfy: a device that never returns is removed, and the condition is about
   devices that do.
   - ⚠⚠ **And the signal for it does not exist yet.** `DeviceSchemaVersion` carries `deviceId` +
     `versionsBytes` and `DeviceSchemaVersionPublisher.publishIfChanged` rewrites the row **only when
     that vector changes** — D3 is behaviour, not shape, so a D3 build is indistinguishable from its
     predecessor there. Three ways to get one, none free:
     - **Bump a service version deliberately**, so the existing vector carries it. ⚠ HR #9: a bumped
       service with no registered migration chain wipes below its derived floor, so this owes a no-op
       `SchemaMigration` — the cheapest of the three, and the one that reuses the publisher.
     - **A capability field on `DeviceSchemaVersion`** — a reshape, so HR #9's bump *and* a migration.
     - **A synced per-device capability row** — additive, no reshape, one more type to register.
     - ⚠⚠ **And it must name the WRITER, not the device.** On iOS route-1 every first-party app
       adopts Control's one `deviceId` (`FirstPartyLocalRuntime` seeds the handed-off identity before
       the `SyncManager` is built) while each app is an independently updated build publishing its own
       vector. A device-scoped capability can therefore read "D3" because Fit updated, while the older
       Drive on that same device still performs the unfenced move.
     - ⭐⭐ **What "runtime" means here, because the word does the work.** `RuntimeIdentity`'s contract:
       *"a fence belongs to a runtime, and a runtime is owned by exactly one installable binary"* — a
       runtime is the **process that owns the sync proxy**, holding the clock, the cursors, the
       pending queues and the schema registry. It is **not** a synonym for "app":

       | platform | runtimes per device | the apps are |
       |---|---|---|
       | **Android** | **1** — Control's process | IPC clients of that proxy, not runtimes |
       | **desktop** | **1** — Control's process | the same |
       | **iOS route 1** | **7** — Control, Calendar, Contacts, Drive, Fit, Maps, Timeline | each links its own proxy, so each **is** a runtime |

       ⚠⚠ **Control is in that list, and leaving it out was the load-bearing mistake.**
       `SharedReplicaDatabaseLock` exists precisely because *"Control and Drive both run the proxy
       stack in-process over ONE shared App-Group replica"* — Control ingests and merges like any
       other runtime, and it is the one whose `deviceId` every sibling adopts. An enumeration that
       omits it leaves an older Control outside the capability gate: the exact hazard this paragraph
       raises for Fit and Maps, in the runtime the others ride on.

       ⇒ **on Android and desktop device == runtime**, and everything below is a no-op there: one
       binary, one version, one answer. The whole complication is **iOS route 1**, where six
       independently-updated binaries share Control's single `deviceId`. ⭐ Which is also the platform
       where those runtimes **share a store** (the App-Group replica; web tabs share IndexedDB the
       same way) — so the aggregation this section asks for lands exactly where both the problem and
       the substrate are, rather than being a mechanism invented for everyone.
     - ⚠⚠ **And "writer" is every runtime that MERGES, not every app that writes files.** The
       conflict-born holder above is created by *ingest*, not by a gesture — and on route-1 each
       first-party app *"links its own proxy"* and publishes its own `RuntimeIdentity`, so Fit or
       Maps, independently updated and sharing Control's `deviceId`, can ingest a divergent rename and
       create a `File` holder with no declaration intent **after Drive has advertised D3**. Delete the
       declared twin and the quarantine can expire before that holder is ever visible. ⇒ the capability
       is published **by the sync runtime**, which is the thing that performs the merge, and the gate
       requires it from **every runtime in the realm** — not from every device, and not from Drive
       alone. ⚠⚠ **And no such enumeration exists today — an earlier revision of this
       paragraph claimed `RuntimeIdentity` provided it, and it does not.** That type is *"a single row
       keyed on `SINGLETON_KEY`, describing **this process***", non-`Syncable`, which *"never enters a
       `SyncBlock`, never reaches the relay"* and whose key is always replaced at init. It can tell a
       process about itself and nothing about its siblings. ⇒ the gate needs something built for the
       job: a **synced per-`(device, runtime)` capability row**, or a **relay-enforced session
       capability** that refuses a runtime which cannot state it. ⚠ That is work, not a hook waiting
       to be used — and it is the difference between P3 being gated and P3 being hoped for.
   - ⚠⚠ **And the gate owes the quarantine, which is a relay change no item above names.** §6d
     establishes that a conflict-born holder's declaration intent is **local**, so no peer can see it
     — which is why *every* physical free, CR-5's immediate path included, must pass through the
     quarantine. A checklist that lets P3 proceed on the fence and the capability alone permits
     exactly the sequence it forbids: a runtime commits a conflict holder, disconnects, and loses the
     content when another device deletes the declared twin. ⇒ **"every free is quarantined" is a
     numbered prerequisite of P3**, not a detail of CR-6.
     ⚠⚠ **And it collides with D2's immediate quota credit unless the accounting is stated once.**
     §11.5 keeps quarantined bytes **charged**, deliberately, so a client cannot cycle
     upload-and-delete through the grace and accumulate physical storage nobody is billed for — D1
     amplification returning by the back door. D2/D3 promise the opposite: delete frees space *now*,
     which is the whole user-visible point of the track. Both cannot hold for the same bytes. ⇒ the
     two paths get **different accounting, because their graces exist for different reasons**:
     | path | grace is for | accounting |
     |---|---|---|
     | **delete-driven** (client named it) | the peer-visibility window — ⚠⚠ **measured in device progress, not minutes** (below) | credit **immediately**; the outstanding credited-but-unfreed total is **capped per owner**, and exceeding the cap stops granting *further credit* until the backlog drains |
     | **enumeration / CR-6** (inference) | being **wrong** — long, and deliberately so | bytes stay **charged** until physically freed; nothing is credited for a guess |
     ⚠⚠ **And a cancelled quarantine has to unwind the credit, which is an accounting transition, not
     a deletion.** If a late declaration cancels the free, the bytes are live again and must be
     charged again — the owner's usage rises, and if that puts them over quota they are over quota,
     which the system already handles by refusing new uploads. ⚠ It must **never** be handled by
     deleting content to make the number fit: the whole point of the cancel is that this content is
     still referenced. ⇒ credit is a **reservation that can be reclaimed**, and the over-quota state
     is a normal, recoverable one rather than an error.
     ⚠ The cap is the bound that makes the first row safe: amplification is then `rate × short G`,
     ceilinged, rather than unbounded. Without it "credit immediately and quarantine" is exactly the
     hole §11.5 closed.
     ⚠⚠ **And the grace cannot be a clock at all.** A runtime that journalled a conflict-born intent
     can stay disconnected for longer than any `G` anyone would pick, and "offline for a while" has
     no upper bound — so elapsed time never establishes that the intent has landed. ⇒ **expiry is
     gated on progress** — ⚠⚠ but *not* on a pull cursor. A consumed cursor proves the runtime
     **read**; it proves nothing about what it still owes. A conflict-born declaration produced during
     that very pull can fail while the next pull succeeds, so the cursor advances past the release
     with the intent still sitting in a local journal — and `SyncScheduler` can skip the outbound
     drain while continuing to pull, so the two are genuinely independent. ⇒ the runtime publishes an
     **outbound** statement of its own — *"no declaration intents outstanding as of seq X"* —
     ⚠⚠ **as one value, not two.** §11.4's marker is `(deviceId, format, complete)` and §11.5 reads it
     beside a separately published cursor: a runtime can publish `complete`, then pull past the
     release and create an intent, and the relay sees the **new cursor** with the **stale marker** and
     frees. The cursor and the emptiness claim must be **one atomic statement** — *"as of seq X I owe
     nothing **for this content**"* — so a later cursor cannot be paired with an earlier promise; and
     the bytes are freed when every admitted **device** has published one past the release, or when a
     device that has not is **removed from the realm**.
     ⚠⚠ **Scoped to the content being freed, not to the journal as a whole.** A global *"I owe
     nothing"* is false while *any* intent is stuck — one declaration wedged behind the `401`/`402`
     backoff above is enough — and then no quarantined content anywhere in the realm can complete its
     quorum: physical frees stop, the credited backlog walks into the per-owner cap, and deletes stop
     returning quota over an intent that references none of the content involved. ⇒ the answer is
     per-content, which costs the device nothing: its journal entries name their contents, so
     "nothing outstanding for *these* ids as of seq X" is a filter over what it already holds.

     ⚠⚠ **Device-scoped, not runtime-scoped — `sync-hlc-tip-device-identity.md` §O5 already rejected
     the other shape**, for the reason that applies here exactly: a `(deviceId, runtimeId)` quorum
     *"gives the GC quorum a set of participants with no lifecycle — an uninstalled app's runtime
     would block GC forever, and device removal has no runtime-level equivalent."* Uninstall Fit and
     reclamation stops permanently, with nothing to remove.

     ⇒ **split the two questions, because only one of them needs a quorum:**

     | question | scope | why |
     |---|---|---|
     | *has everyone seen this?* — the progress gate | **device** | devices have a lifecycle; removal is the escape hatch a stalled quorum needs |
     | *can this writer create unfenced references?* — the capability | **runtime** | it is about behaviour, answerable at the write rather than by waiting |

     ⭐⭐ **And the move that makes device-scoping *sufficient* rather than merely safer: put the intent
     journal in the device's SHARED store.** The only reason runtime-scoping looked necessary is that
     the declaration intents were per-runtime — so an uninstalled app's obligations died with it and
     the gate had to wait on a participant that no longer existed. In the shared store (the App-Group
     replica on iOS route 1, IndexedDB across web tabs — the same substrate `sync-hlc-tip-device-identity.md`
     O3 leans on) a dead runtime's pending work stays visible to its siblings, *"does this device owe
     anything?"* is answerable by whichever runtime is running, and the **device is the correct unit
     again**. ⚠⚠ **Shared is not the same as serialised.** Runtime A can read the journal empty and
     begin publishing *"nothing outstanding through X"* while runtime B commits a conflict holder and
     its intent — A's assertion then lands afterwards and authorises a deletion that was already
     obsolete when it was computed. ⇒ the assertion carries **two numbers, and is published on every
     journal transition *and* every cursor advance** — not only on empty ones:

     ```
     (deviceId, consumedRelayCursor, journalGeneration, owesNothing)
     ```

     - `journalGeneration` is the shared journal's own monotonic position. The relay keeps the
       **highest** it has seen per device and ignores anything older.
       ⚠⚠ **That alone does not close the race, because it depends on arrival order.** A's delayed
       *"empty at 10"* can reach the relay **before** B's *"not empty at 11"* — B may be offline, or
       simply slower — and the relay, having never seen 11, honours 10 and frees. Ordering at the
       relay cannot be assumed, so the race has to be closed **where the atomicity actually exists**:
       the device's own shared store. ⇒ a runtime publishes by **compare-and-set** against a shared
       `publishedGeneration` row — read journal and claim generation in one transaction — so a
       runtime whose read is stale **fails the CAS and re-reads instead of publishing**. B's commit
       at 11 makes A's 10 unpublishable at the source, rather than merely outrankable at the
       destination.
       ⚠⚠ **That still leaves "claim, then pause".** A can win the CAS at 10, stall before sending,
       and B can commit at 11 meanwhile — A's tuple is already claimed, nothing makes it
       unsendable, and if B's publication is delayed the relay can still act on A's stale emptiness.
       No amount of local atomicity fixes a message that is already in flight. ⇒ **the free is
       answered, not volunteered**: the relay **announces** the quarantine, and a claim carries the
       identifier of the quarantine it answers. A tuple composed before that announcement answers an
       older question and cannot authorise this free — so the device's answer is necessarily computed
       after B's commit is visible in the shared journal, because it is computed after the question
       was asked.
       ⚠⚠ **And an honest answer still goes stale between answering and acting.** A can observe an
       empty journal *after* the announcement, answer truthfully, and B can then ingest a divergent
       rename and commit its holder and intent before the relay acts — with B's publication delayed,
       A's valid answer authorises deleting content the new holder names. ⇒ the answer carries a
       **lease** and the free is **two-phase**: the relay collects answers, announces *intent to
       free*, and frees only after each answering device **acknowledges** — a device that has
       committed an intent for that content in the meantime **vetoes** instead. A veto costs a
       deferral; its absence would cost the content.
       ⭐ Liveness is then explicit and bounded: a device that stops answering blocks **this** free
       and nothing else, and the remedy is the one the realm already owes — remove the device.
       ⚠⚠ **Except for an owner device, where removal is not an escape hatch at all.** `threat-model.md`
       records that a revoked A1 holder *"re-derives the volume key from the phrase and re-registers
       itself"* — pinned by `AdmitDeviceRoutingTest` and `RecoveryRoutingTest` — so discharging its lease on removal lets the relay free
       the bytes and lets that device come back and push the still-live holder it had journalled: an
       unrecoverable reference, created by the escape hatch. ⇒ **removal discharges a lease only for
       devices that removal can actually keep out**; an owner device's outstanding lease needs an
       irrevocable identity or epoch fence before it can be discharged, and until one exists its lease
       is discharged only by the device itself answering. ⚠ The
       cost is real and should be said plainly: freeing content is now a round trip with every
       admitted device rather than a relay-local decision, which is most of what the roster barrier
       cost — the difference being that the **credit is already granted**, so the user waits for
       nothing.

       ⚠⚠⚠ **And the race survives even this, which is the finding that matters.** After a device
       acknowledges the intent-to-free, another route-1 runtime sharing that device can commit a
       conflict holder and its intent **before the relay performs the delete** — every acknowledgement
       is still satisfied, and the content goes. Pushing the window later never closes it, because the
       obligation is created by **ingest**, which no promise can hold off. ⇒ any protocol of this
       shape ultimately requires the lease to **fence local commits**: a conflict merge that would
       create a reference under an outstanding lease must block until the relay reports commit or
       abort.
       ⚠⚠ **And "block, then resume" is still the losing shape — the merge has to VETO.** A conflict
       that arrives after this device's final acknowledgement finds every acknowledgement satisfied;
       the relay deletes the bytes, the commit signal comes back, the merge unblocks and creates a
       live reference to content that no longer exists. Waiting changed *when* the reference is
       created, not whether the bytes were there. ⇒ the merge's first act is a **durable veto of that
       specific lease**, and it proceeds on the answer rather than on the outcome:
       | relay answers | the merge |
       |---|---|
       | **aborted** — the free was cancelled in time | proceeds, and describes its new reference normally |
       | **already freed** — the delete had committed | must **not** create the reference: the bytes are gone, and a `content_reference` to them is an unrecoverable dangling row |
       ⚠⚠ **And the second row cannot be handed to the existing repair, which an earlier revision
       claimed.** `localBytesContentRepair` finds its bytes through `readFilePath(entityId)` and
       declines the moment that row is not readable — and here the merge has deliberately *not*
       created the row, so there is nothing for it to read. Pointing at it would leave the merge with
       only the two outcomes the row above forbids: discard the merge, or persist the dangling
       reference. ⇒ the re-emit happens **before the commit, from the merge intent's own source
       path**, which the merging runtime holds precisely because it is the side that has the
       plaintext: push the bytes, wait for the durable acknowledgement that the blob is stored, then
       commit the holder and describe it. ⚠ The ordering is the whole point — a holder committed
       first is a reference over freed bytes for as long as the upload takes, and a crash in that
       window makes it permanent. ⚠⚠ **And a runtime with no plaintext needs a third state, which does not exist yet.** Handing this
       case to the existing repair does not work either: `localBytesContentRepair` answers `false`
       when the path has no local bytes, so making the row readable supplies no source — and the two
       obvious shapes are both forbidden. Keep the `contentId` to give the repair something to fire
       on, and that *is* the dangling reference the row above refuses; drop it, and nothing ever
       fires. ⇒ the merge commits the row with its content **unset and marked awaiting-restore** — a
       **non-reference** pending-repair state: it creates no `content_reference`, so no count is
       inflated and nothing looks live to any sweep, and readers see "content unavailable" rather
       than a reference that resolves to nothing.
       ⚠⚠ **And the marker has to be SYNCED, not local, or it names a repair nobody can perform.**
       The runtime that raises it is by definition the one without the plaintext; a peer that still
       holds the bytes cannot see a local flag, and §6d already establishes that a local read does
       not trigger the failed-resolution repair path. The row would then stay unavailable for ever
       beside a perfectly good copy. ⇒ the pending-restore state is a **schema-backed, synced**
       property carrying the `contentId` it needs, so a plaintext holder ingests it, re-uploads, and
       describes — at which point the row adopts that content and the marker clears. ⚠ It is still
       not a `content_reference`: it says *"this content is wanted"*, never *"this content is held"*,
       so no count moves and no sweep reads it as live. ⭐ The missing piece
       was a state between "no row" and "row holding a reference"; the merge outcome is real and the
       bytes are not, and the model had nowhere to say both. ⚠⚠ What must not
       happen is the silent version — a reference created over freed bytes looks live to every count,
       so nothing ever repairs it.

       ⭐⭐ **So this is a decision, not a further refinement, and it is recorded here as one.** Five
       passes have converged on the same trade:

       | | shape | cost |
       |---|---|---|
       | **1** | **Two-phase + a lease that blocks merges.** No roster barrier | ingest can block on a relay round trip — the one thing merges have never had to do |
       | **2** | **Keep ST-3b's barrier for the PHYSICAL free only.** Credit stays immediate; the wire and the describe plane still move to C′ | ⚠⚠ **does NOT close the race** — see below — and the barrier's stall returns for *bytes*, plus a stale device still holds storage |
       | **3** | **Free on answers alone.** Accept that a divergent rename landing inside the window can lose content | a rare, silent data loss — the failure this whole track exists to prevent |

       ⚠⚠⚠ **Correction — (2) is not the safe alternative, and an earlier revision recommended it as
       one.** Retaining ST-3b does not close the window either: once the device-level barrier has
       passed, another runtime sharing that device can still commit a conflict-born holder **before
       the relay deletes the bytes**, and ST-3b has no mechanism that atomically prevents or
       invalidates a post-barrier commit. It is the same silent loss, reached through a slower gate.
       ⇒ **the merge-blocking lease is required by (1) AND (2) alike** — the barrier changes *how long
       you wait*, never *whether a late commit can be missed*. The real choice is therefore narrower
       than the table suggests:

       - **fence local commits** (lease honoured through the physical delete) — ⚠⚠ which means the
         lease is **persisted in the shared replica before the ACK is sent**, consulted by every merge
         transaction, and cleared only on a durable signal from the relay. A lease that lives in the
         acknowledging runtime's memory protects nothing: that runtime can terminate, and a restarted
         or sibling runtime then merges freely while the relay is still acting on its ACK. ⇒ the ACK
         is a *promise the device has already written down*, not one it is holding in mind.
         ⚠⚠ **And written down is not the same as visible: the consulting runtime must go to disk.**
         `FileObjectStore` keeps a per-process resident cache, and `ObjectStore`'s own contract says
         the cross-process `flock` does not rescue it — *"it serialises writes, but this process's
         cache was already stale before it took the lock. Only a re-read fixes that"*. So B can hold
         a cached *"no lease"*, A can persist the lease and ACK, and B can then take the lock, consult
         its stale cache and commit the holder anyway. ⇒ the lease check is a **direct read of the
         lease row inside the lock** (`invalidateCached` first, or a cache-bypassing read) — the same
         caveat this document already attaches to the shared journal's watermarks, which is exactly
         why it is easy to forget twice. The
         barrier is then an independent question about latency, not safety; or
       - **accept the window** and say so in the threat model.

       ⚠⚠ **A standing candidate after every move — found 2026-09-23.** A move
       declares no edge for the new holder at all: `MoveFileImplementation` emits the source
       tombstone (which undeclares the old holder's edge) and does no content re-emit (`:315-317`),
       and saving the target row declares nothing — `processSavedData` calls only
       `generateSyncBlock`. So the content is left `described ∧ no live edge`, **the reclamation
       candidate predicate**, after *every* move, held back only by the client's live walk, which
       `ContentEnumerationSweep`'s own KDoc (`:31-36`) names as the sole protection. Not a race in a
       sliver — the steady state, and worth fixing on its own terms.

       ⚠⚠⚠ **It does NOT reopen this decision, and a previous revision of this entry claimed it
       did.** Two reasons, both already settled elsewhere in this document:
       - **N6 retires `content_edge`.** An edge declared on the move fixes the *current* architecture,
         not the selected one — it writes to a plane a later phase removes.
       - **§6e (above, "a divergent offline move") already records the hazard, and it lives in the
         HANDLE plane.** A moves `/x→/y` while B moves `/x→/z`; both are ordinary moves and `/y` and
         `/z` are distinct `DataId`s, so *"under the preserve-on-move rule both live rows carried the
         **one** relay handle: delete either and the sole reference is released while the other row
         still names the content."* Declaring an edge does not touch that.
       ⇒ the claim that *"the handle plane is already correct, which is why the fix is small"* was
       **wrong**. Preserving `ReferenceRoot` across a re-key is precisely the hazard §6e identifies,
       and §6e's answer is **minting per re-key** — the move mints a new root, describes the new
       handle and releases the old — which `MoveFileImplementation:310-312` and
       `ReferenceHandle.kt:42-44` both record as owed and neither implements.
       ⇒ **The fix stands as a today-fix — built, §9.16. The thing that could change this decision is §6e's
       mint-per-re-key, and that evaluation has not been done.** ⚠⚠ Four premises in this section
       have now turned out to be believed rather than verified, and this was the fourth — asserted
       from one session's reasoning, caught by review, walked back here rather than quietly amended.

       ⚠⚠ **SUPERSEDED 2026-09-26 by [`content-plane-cr2-quorumless.md`](content-plane-cr2-quorumless.md)**
       — the quorum shares the barrier's liveness (any one device stalls everyone), which the 2026-09-26
       incident made concrete. The record below is kept for its reasoning.
       ⇒ **DECIDED (2026-09-22): option 1 — fence the commits.** ⚠ (3) was not chosen: trading data
       for latency is the one thing this document has refused throughout.

       ⚠⚠ **What is NOT decided, and an earlier revision left it implied rather than named: whether
       CR-2/N5 DELETES ST-3b's machinery or runs it alongside.** Settled: its unanimity **wait**
       leaves the **credit** path in every option (§6d). Open: whether the recorder, the candidate
       ledger and the barrier are removed, or kept running for the physical free. The phase table
       calls N5 *"remove ST-3b"*, which reads as deletion; §9.14's option 2 is the shape that keeps
       it. ⇒ an implementer cannot tell which, and the two have different consequences:
       - **delete it** — CR-5 becomes observable for the first time. ⚠ Today ST-3b frees a candidate
         ~2 s after a delete, so the enumeration sweep always arrives to an empty list (§9.13); while
         it runs, CR-5 cannot be seen acting at all.
       - **keep it** — ST-3b remains the only mechanism reaching content whose *manifest* landed but
         whose declaration did not, which is the population P1 shrinks but does not empty, and the
         track records that **P3 must not ship before the fleet re-declares**.
       ⇒ **decide this before calling CR-2's shape settled, and make the phase summaries agree** —
       the storage question ("how much may a stale device hold") is downstream of it, not a
       substitute for it.

       **What it costs, because the table above overstates it.** *"Ingest can block on a relay round
       trip"* is true of a path, not of ingest. The lease is **local state**: a merge reads the lease
       rows inside the lock it already takes, and leases are keyed per content, so a merge touching
       anything else never waits. The relay round trip — the veto and its abort/already-freed answer
       — is reached only where a **conflict** merge meets an **outstanding lease on that same
       content**, the intersection of two rare conditions. ⇒ the user-facing cost is ~zero, and the
       credit is granted on the describe plane regardless (§6c's D2), so nobody waits on the physical
       free.

       ⚠⚠ **The cost is real, but it lands on storage rather than latency.** Freeing becomes a round
       trip with every admitted device instead of a relay-local decision; and the owner-device caveat
       above means a permanently-departed owner holds its leased content until an irrevocable
       identity or epoch fence exists. ⚠ One genuine hot-path cost: a cached *"no lease"* cannot be
       trusted, so every merge pays a direct read even when the lease set is empty.

       ⚠⚠⚠ **And "per-content, so strictly better than ST-3b" — which an earlier revision of this
       entry claimed, and propagated — is FALSE in the steady state.** It is true of a single free:
       a non-answering device blocks *that* content and no other. But it answers for **no** content,
       so in aggregate it blocks **every** subsequent free too — the same realm-wide stall as ST-3b,
       reached one content at a time. ⇒ compare the two on steady-state behaviour, not on the
       single-free case, and the honest statement is that it moves the stall from the **credit** path
       to the **physical-free** path rather than shrinking it.
       ⚠⚠ **Which also undercuts "the credit is already granted, so the user waits for nothing"**,
       because the relay charges **stored** bytes, not described ones: with every free
       blocked, quarantined bytes accumulate against the owner's quota until the cap, and deletes
       stop returning anything. The credit is immediate on the describe plane and irrelevant on the
       gauge the user actually sees.

       ⭐ **Companion, decided with it: the deleting device RETAINS its bytes until its lease
       resolves.** This is what makes the awaiting-restore state *recoverable* rather than merely
       visible, and it matters because the assumption it replaces is false. *"A peer will still hold
       the plaintext"* fails exactly where it is needed: a rename is a **metadata** operation, and
       `MoveFencePreflight` exists precisely because a device can move or rename a file whose bytes it
       does not have — so the peer whose divergent rename created the conflict is, in the ordinary
       case, the peer that never downloaded it. ⇒ retention on the **deleting** device is what gives
       the restore a source.
       ⚠⚠⚠ **NOT a timer — bound it to the lease, because this section elsewhere permits the free to
       be delayed INDEFINITELY.** An earlier revision of this entry said *"size the window against the
       24h enumeration sweep"*, which contradicts the liveness paragraph above it: a non-answering
       device — or a permanently-departed **owner**, which has no escape hatch at all — holds the
       content unfreed for as long as it stays away. Any fixed window can therefore expire *before*
       the delete it was sized to outlast, and the pin would be gone at the one moment
       awaiting-restore needs it. ⇒ **the pin is released by the same durable relay outcome that
       clears the lease** — freed or aborted — never by a clock. ⭐ That is the rule D3's pin already
       follows: `PendingMoveReplay` keeps the pin until the move **succeeds**, not until it is
       attempted. Established shape, not a new one.
       ⚠⚠ **And the residual is not zero — "never loses content" would overclaim.** One state
       survives: content the deleting device **never held locally** (so there is nothing to retain),
       where the veto also arrives after the relay's delete has committed and the merge is answered
       *already freed*. The lease covers the ordinary case by **preventing** the free — the veto
       aborts it — but it cannot **restore** bytes when it loses that race and no plaintext exists
       anywhere. ⇒ state it as *"loses content only in that sliver"*, and say so in the threat model
       in those words.

       ⚠⚠⚠ **The retention is NOT the existing pin, and an earlier revision of this entry said it
       was.** Two corrections, both found by review:
       - **D3's pin was not merged** (open at the time of writing), so *"already built"* was
         wrong on its face; and
       - more importantly it guards the **wrong door**. That pin refuses `EvictFileBytes`, whereas an
         ordinary delete destroys the plaintext *itself*: `DeleteFileImplementation` calls
         `fileDatabase.deleteFile` on every leaf it walks
         (`file-service-proxy/.../impl/DeleteFileImplementation.kt:190`) plus a top-level backstop
         (`:108`), and its own comment explains why — *"bytes, not entities, are what fills a device
         up"*. Those are direct calls inside the delete operation; no guard on the eviction path is
         anywhere near them.
       ⇒ retention has to live **in the delete path**: the delete **retains or relocates** the
       plaintext instead of destroying it, and the release is what the pin then governs. ⭐ This is
       also the shape a user-facing trash/undo needs, so the two wants are one mechanism rather than
       two.

       ⚠⚠ **And *"freed or aborted"* is STILL too early a release, which is the same error one layer
       in.** The plaintext-less peer can only raise the synced awaiting-restore marker *after* it
       receives `already freed` — by which time the deleting device has had the same successful-free
       outcome, released its retention and evicted the only copy, before the marker ever arrives.
       ⇒ the retention survives until the **restoration is durably complete**, not until the relay's
       free returns.

       ⚠⚠ **MEASURED (`ConcurrentMoveVsDeleteSystemTest`) — the behaviour this apparatus protects is no longer hypothetical,
       and neither is its failure.** An earlier revision of this entry said the behaviour was
       *untested* and that the claim *"rests on the 2026-07-16 paper spike"*. That was true when it
       was written and is no longer:
       `system-test-utils/src/test/kotlin/org/tenshu/systemtest/ConcurrentMoveVsDeleteSystemTest.kt`
       — `theMoveWinsWhenTheMoveReachesTheRelayFirst` and
       `theMoveWinsWhenTheDeleteReachesTheRelayFirst` — runs the composition on two real devices
       against the real relay in **both** ingest orderings. It partitions them by shutting each
       device's own `storageGate` and standing on a tick boundary (`SyncScheduler.drainNow`) before
       either edit, so the ingest order is a **parameter**, not a race. Two results, and they point
       in opposite directions:
       - **The metadata claim holds** — `file-content-addressing.md` §3.3.3 finding B, bullet
         *rename-vs-delete* (cited by section: the line range this entry used to carry,
         `:604-607`, had drifted onto a different bullet) — on both orderings,
         exactly as written: `/y` survives, `/x` is gone on both devices (including on the deleter,
         which never issued a create), `/y` still carries the original `contentId`, and it is a live
         member of the target folder rather than a reaped dead one. This is asserted, and settles.
       - **The bytes do not follow it.** Instrumenting the same test to read the relay's own
         blob/ref/handle tables inside the one-sided window — first device released, second still
         shut — gives **2 blobs / 2 refs / 1 handle** move-first and **0 / 0 / 0** delete-first.
         Delete-first is already 0/0/0 **one second** after the delete lands, and stays 0: the realm
         converges on a row at `/y` naming a `contentId` whose bytes the relay has freed, and the
         mover's later `create(/y)` restores nothing. Re-measured 3/3 identical on 2026-09-24.
       ⚠⚠ **Deterministic, and the determinant is not the ordering — it is the publication delay.**
       Say it exactly, because *"delete-first loses the bytes"* is true only within that window and
       would otherwise read as a property of the ordering alone. The free fires within a second of
       the delete reaching the relay; what can stop it is the mover's own push landing first and
       vetoing the candidate. The committed test reopens the second gate immediately, so that push
       wins and the run **ends with the bytes present in both orderings** (3/3). Hold the mover one
       extra second and they are gone for good. ⇒ what decides the outcome is **how long a peer's
       write stays unpublished**, which is precisely the quantity `ReclamationBarrier` does not
       measure: it clears the candidate once every peer's **cursor** has consumed the log past the
       tombstone, and the mover *has* consumed it — it pulls throughout, its push gate being shut —
       while still holding an unpushed `create(/y)`. `ContentReclamationPolicy`'s liveness veto then
       asks the deleting device's own tree, which cannot contain `/y` yet. ⭐ That is this section's
       *"has every peer read the log"* ≠ *"has every peer published what it wrote"*, with a
       reproduction behind it rather than an argument.
       ⚠ The test **asserts neither** byte outcome, deliberately: green over the delete-first
       figures would latch the defect as expected, and green over the move-first figures alone would
       claim an order-independence the content plane does not have. ⚠ **The byte assertion is owed
       by candidate B, not by this section**, whose option 1 is superseded
       (`content-plane-cr2-quorumless.md` §10.7: a move pushed within T keeps its content, one
       pushed after T gets `content_freed`; owed before B5 is armed, and this measurement stays open until
       then). ⚠ The figures above are 2026-09-24's and predate D3's wiring (§9.15), §9.16, §9.17 and §9.18; the
       handle column counts reference handles, which B6 retires. ⚠ Also checked and **rejected**
       — making a concurrent delete *beat* the move, which would remove the conflict holder
       and with it the need for any of this. It reverses that recorded decision, and it is not
       implementable anyway — a `File`'s DataId **is its path**, so a move is delete-source +
       create-at-target with **no provenance** on the new row and no back-pointer on the wire, and
       the `rekey(oldHash → newHash)` signal that would supply one is explicitly *"on the shelf"*.
       Suppressing the new row means correlating a create at one key with a tombstone at another,
       which nothing does and nothing can.

       ⚠⚠⚠ **OPEN — and the review loop was STOPPED here rather than settling it.** An earlier
       revision of this entry claimed the quorum bounds the wait: every admitted device has certified
       it owes nothing, so a marker can only *originate* in the ACK→delete sliver. True, and
       **insufficient — bounding when a claim ORIGINATES does not bound when it becomes VISIBLE.** A
       claimant from that sliver can receive `already freed` and then sit offline for weeks before
       syncing its marker. ⇒ as written the protocol has a terminal event in **neither** direction:
       an ordinary delete with no late merge produces no marker and therefore no
       restoration-complete signal, so *"retain until restoration completes"* never fires and the
       bytes are kept for ever; while releasing on the marker's **absence** can discard the sole
       recovery copy. Those are the only two behaviours the current text admits, and both are wrong.

       ⇒ **What is owed is a post-free lease-close step** — that positively proves every possible
       claimant has resolved — a second acknowledgement round over the admitted set, carrying the
       same liveness caveat the lease itself does (a device that never answers; the owner-device case
       that removal cannot discharge). ⚠ That is another round of the same protocol with the same
       unresolved liveness question, so it is a **maintainer's call about cost**, not something to
       settle by review volume: three consecutive passes each opened one layer deeper into this one
       question — a P1 recurring one level deeper each pass rather than converging.

       ⚠⚠⚠ **And the lease-close round does not close it either — it repeats the race it exists to
       end.** A device ACKs the close; while the relay waits on the others, a sibling runtime meets a
       stale divergent move, receives `already freed`, and raises an awaiting-restore marker that may
       only become visible **after** every close ACK has released the retained plaintext. ⇒ a finite
       round cannot *"prove every possible claimant has resolved"* at all, unless the close ACK also
       **permanently prevents** later claims, or post-close claims are made recoverable by some other
       means. ⭐ That is the useful constraint this leaves behind: the solution space is not
       "add one more round" — it is *make a late claim fail safe*, which is what the awaiting-restore
       state already does. ⇒ the realistic end state may be that this is **never** proven, only
       bounded, and the design should be read that way.
       ⇒ **until it is answered, the accepted loss mode recorded in `threat-model.md`
       T-R-09 is UNBOUNDED rather than confined to the sliver**, and the contract says so in those
       words.

       ⇒ **Downstream updated in the same slice**, as the open form of this entry required: §6d's D3
       statement and its P3-downstream note, the track summary's CR-2 row, and the P3 direction note
       in `drive-upload-stop-and-reclamation.md` now say what replaces ST-3b's barrier for the
       physical free, rather than naming an open question.
     - ⚠⚠ **And the generation needs an epoch, because the store can be wiped under a surviving
       identity.** `SyncProxyInitialization` documents exactly that: the object store is wiped while
       the device identity and relay registration survive. The journal lives in that store, so its
       generation restarts at zero while the relay still holds the old high-water — and then ignores
       every new tuple until the counter climbs back past it, blocking quarantines until the
       immediate-credit cap is exhausted. ⇒ publish `(epoch, generation)` ordered lexicographically,
       where `epoch` survives the wipe — the relay hands back the last it saw at re-registration and
       the device starts above it. ⚠ A counter whose zero means two different things is not
       monotonic, however carefully it is compared.
     - ⚠⚠ **Which requires B to publish when it *takes on* work, not only when it finishes.** An
       earlier version had devices publish only emptiness — so B, holding a pending intent, published
       nothing, the relay never learned generation 11 existed, and A's stale *"empty at 10"* stood
       unopposed. A generation is only a fence if the *non*-empty transitions raise it too.
     - `consumedRelayCursor` is what the free condition actually compares against the quarantine's
       relay sequence. ⚠⚠ **And "past the sequence" is not "has read everything up to it":**
       `sync-device-catch-up.md` promotes the **forward** cursor to the live tip while a **backward**
       range is still unread, so a device can be past the quarantine on paper and have an older
       conflict block — and the declaration intent it creates — still uningested. ⇒ the claim is
       gated on **complete history coverage**: forward cursor past the sequence *and* no outstanding
       backward range below it. A cursor that means "I am at the tip" answers a different question
       from "I have seen everything". ⚠⚠ **Which is why a journal transition alone is the wrong trigger.** A device
       that is fully caught up with an empty journal has no transition to publish on, so the relay
       keeps an older cursor for ever, the free condition never becomes true, the credited backlog
       reaches its cap — and deletes stop returning quota on a device that is doing everything right.
       ⇒ publish on **either** event: the journal changed, or the cursor moved. ⚠ The journal generation cannot stand in for it: they count different things
       in different address spaces, and an earlier revision conflated them. The capability aggregates the same way: the device publishes the **minimum** any runtime
     wrote there — O3's monotone watermark pointed the other way, so a non-capable sibling lowers the
     answer by starting and cannot raise it by being absent.
     ⚠⚠ **A pre-D3 runtime cannot lower what it has never heard of, so aggregation alone is
     advisory.** A legacy iOS app has no code to write the minimum; it starts, merges a conflict and
     leaves the device's published value untouched and stale-capable. Nothing inside the fleet can
     fence a binary that predates the fence. ⇒ the aggregate is a **fast, honest signal for
     participating runtimes**, and P3 additionally requires the same fleet statement EI-4's removal
     needs: *no runtime that predates the capability can still be in use*. ⚠ That is a judgement
     about the installed base, not a check — and stating it as one is the difference between a gate
     and a hope. ⚠ No relay-visible runtime id is needed,
     which matters because that would disclose which apps a user runs — the leak EI-1/EI-4 remove.

     ⚠⚠ **Two caveats, or the aggregate is confidently wrong.** The shared stores are **cache-aside**
     (iOS bulk-loads at open and refreshes at foreground; `IndexedDbObjectStore` does not propagate
     cross-tab), so these reads must go **around** the cache — O3 carries the same warning. And a
     platform whose runtimes share no store gets no aggregate at all: on Android and desktop that is
     moot, since Control is the only runtime, but it must be checked rather than assumed. Time survives
     only as a floor, never as the condition.
     ⭐ **This is not the barrier coming back**, and the difference is the one this track exists for:
     ST-3b made the *user's quota* wait on unanimity, so a stale device meant space never returned.
     Here the **credit is already granted** — only the physical delete waits, and the owner sees
     their space immediately either way. ⚠ Residual, stated: a realm with a permanently-absent device
     that nobody removes accumulates quarantined bytes. ⚠⚠ Those bytes are **credited to the user and
     still occupying the relay** — the delete-driven row of the table above, not the charged one — so
     the user sees their space back while the relay carries the cost, **bounded by the cap** and by
     nothing else. That is the deliberate trade: the bound is the cap, and the remedy is the one the realm
     already owes — remove the device. ⇒ this is the second place
     where reliable device removal (`sync-entity-identity.md` §5b) is load-bearing for reclamation,
     not merely tidy.
     ⚠⚠ **Except where removal is not a remedy at all, which §9.14 establishes above and this
     sentence must not quietly un-say.** An **owner** device cannot be removed into silence: a
     revoked A1 holder re-derives the volume key from the phrase and re-registers itself, so
     discharging its lease on removal frees bytes it can still come back and reference. ⇒ for an
     owner device the lease is discharged only by that device answering. ⚠ And in a **single-device
     realm the only device is an owner device**, so a permanently absent one leaves no remedy at all:
     physical frees stop for ever, the credited backlog walks into the cap, and deletes stop
     returning quota — the same failure the per-content scoping above exists to avoid, arrived at
     from the other side. Naming it is the honest position; closing it needs the irrevocable identity
     fence §9.14 says does not exist yet.
     ⚠⚠ **The cap applies back-pressure; it never shortens the grace.** An earlier draft had cap
     pressure free the bytes at once — which hands the quarantine's own job to whichever client
     deletes fastest, and physically deletes content a disconnected runtime may still hold an
     invisible conflict-born intent for. The grace exists for a correctness reason that a storage
     figure knows nothing about. ⇒ over the cap, the *credit* stops (deletes still queue, and the
     user sees space return as the backlog drains); the *bytes* still wait out `G` like every other
     free.
   - ⚠⚠ **And it is a standing floor, not a rollout check.** P3 removes the barrier *permanently*,
     while the roster keeps changing: a pre-D3 device admitted next month, or an older first-party iOS
     app installed under an already-admitted identity, performs the same unfenced move against a realm
     that no longer has a barrier. ⇒ the capability must be **enforced**, not merely observed once —
     an admission-time refusal, a session/protocol floor of the `RELAY_PROTOCOL_VERSION` kind, or a
     reclamation that re-evaluates the fleet each pass and stops when it cannot see it. ⚠ Downgrade is
     part of it: a reinstall of an older build must lower the realm's answer, not keep a stale "yes".
   - ⚠⚠ **And CR-6's own gate needs the same scoping, or the fix stops at P3.** §11.4 publishes
     `(deviceId, format, complete)` and §11.5's `gate(S)` quantifies over admitted **devices** — so on
     route-1 a current Fit runtime can advance and complete Control's shared device entry while an
     older Drive or Maps runtime under that same `deviceId` has not consumed `S`, or has just created
     an undeclared conflict holder. CR-6 then reads the realm as settled and frees that holder's
     blobs. ⇒ ⚠⚠ **superseded by the resolution above, and deliberately not by keying CR-6 on
     `(device, runtime)`**: runtimes have no lifecycle, so an uninstalled app would block CR-6 for
     ever (`sync-hlc-tip-device-identity.md` §O5). CR-6 takes the same shape as P3 — the cursor and
     the marker stay **device**-scoped, the intents live in the device's **shared store** so a dead
     runtime's obligations outlive it, and only the **capability** is per runtime, aggregated as the
     minimum any of them wrote.
   - ⚠⚠ **And a runtime that upgrades carries its old sins with it.** The capability describes what a
     runtime *will do next*, not what it already did: a device can latch `v5-wire-names`, perform an
     unfenced offline move on a pre-D3 build, then upgrade — after which it advertises D3, has no
     unlanded intent, and still holds a moved row with neither an edge nor a durable intent, which the
     latched backfill will never revisit. Delete the last declared holder and that content is
     reclaimed while the row is still local or in flight. ⇒ **an upgrade owes a repair pass** over
     holders created before it — the same walk, run once on the version transition rather than on a
     marker that has already latched — and the capability does not count as "yes" until that pass has
     reported. ⚠ A fleet check that reads only the current build answers a question nobody asked.
   - ⇒ **P3 cannot read this gate today.** Whoever implements D3 picks the signal; recording it as
     satisfied by `DeviceSchemaVersion` as it stands would be false.

### 9.15 ⭐⭐ As built — N3: D3's fence is live, and what it reads (2026-09-24)

The fence, pin, intent and replay first shipped **inert** (`offline = false` hardcoded, `replayAll`
uncalled). Both halves are wired on Android, desktop **and iOS** (route 1 is where an offline move
is likeliest). JS shares the factory but wires neither: no fence ⇒ nothing parked ⇒ nothing to replay.

**The signal — five review rounds, one sentence: only a WRITE is evidence about writes.**

- ⚠⚠ **Not `caughtUp`** — it is inbound ("has everything", never "is online"); a connected device
  mid-backlog would defer every move.
- ⚠⚠ **Not the banner's `online`** — `RelayReachability.isOnline()` is optimistic before the first
  attempt and smoothed across `CONFIRM_MS`, so it waves moves through for ~20 s after the link drops.
- The fence reads `SyncHealthFigures.relayConfirmedReachable` via **one shared predicate,
  `relayUnreachableForFencing()`** (three inlined copies could drift unnoticed). It is true only when
  the latest tick was **answered AND authorized** (`tickAuthorized`, separate from `tickOnline`
  because a `401` is reachable-but-refused), within `FENCE_EVIDENCE_TTL_MS` = 45 s (not the banner's
  90 s), **and** no `402` backoff is active, **and** no personal-relay push — block, exempt block or
  tombstone — was refused that tick (`tickWriteRefused`). ⚠⚠ **And a push is permitted at all** (`pushPermittedNow()`):
  with the storage gate shut on a registered realm every push is *skipped*, no refusal is recorded,
  and the pull still succeeds — "nothing refused" is not "a write would land". ⚠ The flag resets at
  the very top of `tickOnce`, ahead of the exempt drain that runs first. ⚠ The last is deliberately *any* failure,
  not a list of codes: `noteRelayAccepted()` is fed by the pull paths, so a push-500-then-pull-OK tick
  otherwise certified writes. ⚠ Shared-realm pushes are excluded — another owner's lapsed
  entitlement must not defer every personal move.
- ⚠⚠ **`null` (no tick yet) fences** when a relay is configured, but the fence is **inert** when none
  is (`relaySyncConfigured`): a never-funded local-only realm makes no relay call, so failing closed
  there deferred every content-bearing move for ever — and it has no peers to fence against.
- ⚠⚠ **A cached verdict cannot close the window**: connectivity can drop between the last accepted
  tick and the move. Closed by an acknowledged round-trip at move time — **§9.17**.

**The replay** (`ReplayPendingMoves`, `HeadlessSafe`, 15 min, no idle/charging gate) ships in the same
change on purpose: a fence with nothing applying its intents is worse than none, because the pins hold
bytes out of eviction for ever. `MoveFenceWiringTest` audits both halves together. ⚠ The schedule
needs its permissive `ConstraintSpec` — without one, registration threw into a best-effort catch and
no replay item existed on any platform. The verifier **converges**: `ensureContentOnRelay` re-emits
from the pinned copy when it answers `false`, and every file in the subtree is asked in one pass
(an early exit skips *repairs*, not just checks). Primary content only — a rung is re-mintable.
⚠⚠ It **fails closed**: the files the fence parked for (`subtreePaths`, recorded as source paths and
rebased onto the target) are asked **by key**, so a gap in the `FILES` walk cannot drop the content
the pin exists for; an unreadable child makes the walk incomplete, not empty; and a *recorded* path
whose content id no longer resolves is unverified, not contentless. ⚠ A target with no row at all was
deleted after the move and retires — reading its absence as "incomplete" would hold the pins for ever.

**Explicit eviction asks both journals.** `EvictFileBytes` refuses content pinned by a parked move
**or** still queued for upload (`hasQueuedContentFor`), matching `OfflineKeeper`.

**A half-applied move is resumed, not rolled back (decided 2026-09-24).** `MoveFile` commits
as it goes — a folder saves its destination then moves children one at a time; a file saves the
target row before the migrator retires the source — so a failed attempt can leave both ends present.
The replay used to answer `DEFERRED_AGAIN` there for ever: a livelock holding pins and reservations.
It now calls `MoveFileImplementation.resumeMove`, which moves what the source still lists (recursing
into a half-moved subfolder) and retires the source. Rollback was rejected: moving rows back can fail
part-way too, and needs a rule for a target the user touched meanwhile.
- ⚠⚠ **Provenance, not shape.** The `attempted` marker is written before the first attempt, while
  the target did not exist — but that proves only that an attempt started, not that the row at the
  target now is its work. Resume therefore accepts an existing target only where it is provably this
  move's own: **folder → folder with the same `CreatedAt`** (the destination is saved with the
  source's properties, and `CreatedAt` is immutable — a destination the user deleted and recreated
  carries its own), or **file → file with the same primary content id**. Anything else is a genuine
  collision and fails, keeping the intent and its pins.
- ⚠⚠ **The resume re-saves the destination AS IT STANDS** — never skips it, never saves a fresh one.
  A fresh folder entity carries `Size` 0 and would reset it under the children already moved in; a
  skipped save leaves a row the attempt persisted *before its observers ran* with no parent membership
  and no outbound block, so the finished move would be missing from listings and invisible to peers.
  The re-save is idempotent: the relation maintainer adds a member only when it is absent.
- ⚠⚠ **The resume does not consult the fence.** A deferral there would park a fresh intent under the
  same key, overwrite `attempted`, release the reservations — and the next pass would retire the
  half-moved tree as a collision. The intent's existing pin plus the verifier cover an offline resume.
- ⚠ Reached through the implementation factory, not a `MoveFile` param (a new op param is a wire
  reshape); `ResumeHalfAppliedFolderMoveTest` runs the real op so the null fallback cannot become the
  silent default.
- ⚠⚠ **The replay only ever touches the intent it read.** Marks go through `PendingMoveStore.update`
  and retirement through `retireIfUnchanged`; both act in one transaction and only if the stored row
  is still that exact intent. Never `write` — the park path, which resurrected a displaced intent and
  displaced the user's newer gesture. Never by key — the replay suspends (verification, `MoveFile`
  itself) and a same-key intent parked meanwhile (delete `/b`, recreate `/a`, move `/a → /b` again)
  is the user's next gesture: clearing it lost the gesture, and marking it `applied` claimed a move
  that never ran.
- ⚠⚠ **The same move parked again while its intent is in progress (`attempted`, not `applied`) is
  FOLDED in**, not swapped: the claim guards the row only up to `MoveFile`, and a replacement landing
  during *or after* execution was retired unverified on its vanished source by the next pass. Keyed on
  the persisted marker, never on "executing right now" — a park can enter its slow preflight during
  execution and write after it, so a time-scoped guard always has a gap. A completed-but-unmarked move
  whose source the user recreated folds too: it stays attempted, and the next pass runs the new move.
- ⚠ A folder whose `FILES` list has an unreadable member is moved *partly* by `MoveFile` — pre-existing
  on every online move, not D3's: a `MoveFile` defect, since fixed — `MoveFile` refuses a folder move whose subtree is not all on this device.
- ⚠ **File → file provenance is content identity, which is not unique**: copies from divergent moves
  share `contentId` *and* `CreatedAt`, so a sibling copy at exactly the target path passes the check.
  Bounded — the content survives, one duplicate row is lost — and accepted as a residual,
  whose fix is the per-move stamp designed in `tech-debt.md` (a `File` property, so a schema bump).

⚠ **Retiring the intent does not itself declare the moved holder** — and need not: that was the move-declares-no-edge defect, which
was never D3's (every move left the new holder undeclared, and a pin guards local bytes, never relay
content). The move's own tombstone drain now declares it — §9.16.

⚠⚠ **Live with D3's wiring:** a deferred move is silent, and a pinned "Remove download"
refusal is silent.

### 9.16 ⭐ As built — a move declares its new holder before the old one is undeclared (2026-09-25)

A move saved the target row (which declares nothing) and emitted a tombstone for the source, whose
drain **undeclared** the old holder's edge. So every moved file's content sat at `described ∧ no live
edge` — the candidate predicate itself — after *every* move, held back only by the live-walk veto.

**The fix rides the tombstone, not the move.** `DataIdMigrator.migrate` takes the move's target as its
`successor` and hands it to the emitter (`TombstoneEmitter.emitRetiring`), which records
`blockId → successor` beside the cascade record, before the block can be pushed. When the drain pushes
that block it **declares the successor first** — edge and handle in one request — and only then adds the
old holder to the undeclare batch.
- ⚠⚠ **A failed declare keeps the old edge.** A stale edge is a leak; a missing one is a candidate. The
  conservative half the content-keyed journal (§6d.1) already takes. ⚠ "Could not declare" includes a runtime with **no
  declare wiring** (web: a content store, no reclamation sources) and a successor with **no row** —
  queue `A→B→C` before the drain and `B` is gone when `A`'s tombstone is pushed. Both once read as
  success and dropped the old edge.
- ⚠ **One declaring path, not two.** The drain calls `ContentDeclarationBackfill.declareHolders`, which
  shares the backfill's own `declareEntries` — the manifest refs, the emit's handle derivation
  (`referenceHandleFor`), the describable rule and the ref-bounded batching. Its per-row content comes
  from `declarableEntriesOf`, which asks the same port about the same `Syncable(OnDemand)` properties
  the corpus walk asks. ⚠ It needs only the registry and the port, **not** an enumerable object store:
  the first cut hung it off `SchemaDrivenContentWalk`, which exists only where `initSyncService` gets a
  store, and a runtime without one silently kept the old behaviour — the system test caught it.
- ⚠⚠ **Declared under the content-emit ownership, one property at a time**
  (`declareEachPropertyOwned`). A snapshot declare races an in-place replacement: `ReplaceFile` can save
  the destination *and* finish declaring its new content between our read and our request, and our
  stale upsert then puts `(holder, property)` back on the old content. ⚠ **A re-read loop cannot close
  it** — three review rounds proved every bounded loop has a last declare it cannot validate. Ownership
  does: an overwrite takes `withContentEmitOwnership` *before* writing bytes or row, keyed on the
  content the row names now and revalidated after the wait, so holding that key across our read and
  declare means the replacement either finishes first (and we declare its content) or starts after
  (and its declare wins). ⚠ One key at a time, never nested: the upload holds its primary key while
  emitting rungs, and nesting ours in another order could deadlock. Costs a request per property.
  ⚠⚠ The ownership table is the **runtime's** (`EmitKeyLocks`), shared by every session's observer like
  `TombstoneCascades` — it used to live per observer, so an overwrite through one app never contended
  with an emit (or this declare) through another. A pre-existing gap in the guard itself, closed here.
- ⚠⚠ **Residual, accepted: a PEER's replacement.** Ownership is per process. A peer can replace the
  moved file and declare its content before this device pulls it, and this device's drain — which
  pushes before it pulls — then upserts `(holder, property)` back to the old content from its stale row.
  Narrow (a peer replacement in the seconds before the next pull), and opened by this fix while it
  closes the every-move case. The fix is a relay-side declare-if-absent for successor declares; superseded by CR-2 B6, which retired successor declares (edges travel with the push, G1).
  ⚠ The corpus backfill still declares from a snapshot; not changed here.
- ⚠ **In memory, like cascades.** A process death between emit and push loses the successor, and that
  tombstone undeclares as before. A migration resumed from its journal marker carries no successor
  either. Both fall back to the pre-fix behaviour; neither is worse than it.
- ⚠ **Edge + describe under the preserved root** — the root survives the move, so the handle is the
  same one. §6e's divergent-move handle aliasing (two live rows, one handle) is **not** touched: that is
  mint-per-re-key, N4's, and needs a relay release endpoint that does not exist.

Pinned by `MoveDeclaresItsNewHolderSystemTest` (relay edge table: the moved content keeps a holder, and
it is the new one) and `ContentBackfillWiringTest` (an undeclarable successor keeps the old edge, beside
a control that a plain tombstone does undeclare).

### 9.17 ⭐ As built — a device that looks online asks the relay before a move (2026-09-25)

§9.15's fence trusted a cached verdict — the last scheduler tick, up to `FENCE_EVIDENCE_TTL_MS` (45 s)
old. If the link dropped inside that window, a move of content this device does not hold went through
unfenced: no intent, no pin, and a peer free to delete the old holder.

**The fix is an acknowledged round-trip.** When the cached verdict says online, `MoveFencePolicy` asks
the relay `POST /sync/push/probe` — `/sync/push`'s own gates, no body (`sync-relay-api.md`). `204` ⇒
apply; refused, unreachable or timed out (5 s) ⇒ treat exactly as offline — preflight, park, pin.
- ⚠⚠ **Every online move of content pays one round-trip, not "only the rare case".** The design assumed the
  preflight would gate the probe to rare online-only moves. It cannot: the preflight SHA-256s every local
  file in the subtree, and every file with thumbnail rungs reads as online-only anyway (rungs carry no
  stored hash). So the probe runs FIRST and the preflight only after a refusal. Decided with the
  maintainer knowing the cost; an empty folder skips it.
- ⚠ **A relay without the route (`404`) keeps the cached verdict** — "could not ask" is not a refusal,
  or every move would defer until the relay deploy. Deploy the relay first regardless.
- ⚠ One shared mapping (`SyncRelay.confirmWritableForFencing(health)`), wired on all three platforms and
  audited by `MoveFenceWiringTest` beside `isOffline` — null compiles, passes, and keeps the window.
- ⚠⚠ **A local-only realm is never probed** (`relaySyncConfigured == false` ⇒ "could not ask"). It is
  never registered, so the probe would be refused and every content-bearing move deferred with no peer
  to fence against — §9.15's configured-vs-reachable distinction, which the first cut of this lost.
- ⚠⚠ **Narrowed, not closed — residual accepted.** The probe is a separate request from the move's own
  writes, which are pushed afterwards; a link lost between the `204` and that push reopens the window.
  From up to 45 s to the probe→push gap. Closing it needs the move itself to be the acknowledged write
  (synchronous push, commit on acceptance) — superseded by CR-2 B6 (`content-plane-cr2-quorumless.md` §12.4): the gap is now bounded by T.

### 9.18 ⭐⭐ As built — one content, one upload (2026-09-26)

**Measured on production (read-only):** the maintainer's realm stored 5.0 GiB, of which **4.25 GiB
(5806 blobs) was undescribed** — in no `content_ref` row. They were the **losing chunk sets of repeated
emits of the same content**: every emit mints a fresh CEK (a new chunk set), the manifest address and
the relay's describe are last-write-wins, and every other set stays stored, undescribed, unreachable
by any sweep. Up to 9 emits of one content; still ×2 per content on the day it was found; all from one
OkHttp client. ⚠⚠ **Not all garbage**: in ~13% of duplicated contents the winning manifest and the
winning describe came from different emits, so the LIVE chunk set was the undescribed one.

**The fix, two layers:**
- **Per runtime** — `EmitKeyLocks` records `(holder, content-keyed emit key)` once published, and a
  second emit of the same content for the same holder is skipped **under the emit key**, so a caller
  that waited while another emitted it stops there (the serialized duplicate; the concurrent one was
  §9.16's per-observer lock table). ⚠ Per holder AND content, never content alone — copies share a
  contentId across holders, and skipping the second holder would skip its edge (§9.16's defect).
  ⚠ Never for a legacy holder-keyed emit (no contentId), where a second emit is new bytes.
- **Across restarts and devices** — the thumbnail minter asks the relay first
  (`ensureContentOnRelay`) on the mint-on-view and backfill paths; only an upload's rungs, fresh by
  construction, emit directly.
- ⚠ **Repairs are forced** (`repairFileContent`, used by `ContentRepair` and `ensureContentOnRelay`):
  the relay lost the content, which is exactly the case "already published" must not skip.
- ⚠⚠ **"Already on the relay" still declares THIS holder** (`ensureContentOnRelay` → `declareResolvedHolder`):
  a copy or a shared rung finds its content published by another holder, and skipping the upload must
  not skip its edge. Declared against the refs the relay's manifest names — which also realigns a
  description a duplicate emit left on the losing set — and only while the holder still names that
  content (never displacing another row's live edge). ⚠⚠ The whole resolve → declare-or-repair runs
  **under the content's emit key**: unheld, a concurrent repair could republish under new refs between
  the resolve and the declare, and the late declare would write the stale set back.
- ⚠ **One rule for the marker on every publish path** (`recordPublish`) — the inline emit, the deferred
  drain, and content `ensureContentOnRelay` found and declared: a DECLARED publish marks the pair, an
  undeclared one CLEARS it (a forced repair bypasses the marker, so it must also be able to invalidate
  it). An undeclared publish stays re-emittable so its edge is retried; an unmarked drain publish let
  the next ordinary emit duplicate it (the metered/quota-deferred path escaping the fix).

**The heal, part 1 — as built:**
- ⭐ **Re-armed:** `BACKFILL_FORMAT` = `v6-realign-described`, so every device latched at v5 runs
  `ContentDeclarationBackfill` once more. Each content is re-described from the manifest the relay
  actually holds, which realigns the ~13%.
  ⚠⚠ **Deployment gate:** merged only once every emitting device ran the one-content-one-upload build. An older build
  re-running the pass still mints fresh-CEK duplicates.
- ⭐ **Re-armed again, to be measured:** `BACKFILL_FORMAT` = `v7-counted-walk` (2026-10-01). The walk
  dropped rows it could not decode, key, or resolve with a bare `continue`, so a v6 line cannot say
  whether the pass saw the realm: a desktop declared 492 edges where about 8,000 were expected and
  recorded itself complete. The line now carries `from N rows` and, when there are any,
  `ROWS SKIPPED UNDECLARED (… would not decode, … had no DataId)` and
  `PROPERTIES SKIPPED UNDECLARED` (resolving the content id threw; counted per property, since a row has several).
  ⚠ Skips are **reported, not latched on**: one permanently undecodable row would otherwise re-run the
  pass for ever. Whether a skip should block the marker is decided once a device has shown a number.
- ⚠⚠ **The pass revalidates against racing emits** (`ContentDeclarationBackfill.EmitGuard`):
  - The problem: it resolves and declares outside the emit keys. An emit landing in between publishes a
    new chunk set, and the pass's late declare then writes the stale set over it.
  - `EmitKeyLocks` keeps one stamp per **content id**, not per `(content, property)`. The manifest is
    content-addressed, so an emit through one property replaces it for every property sharing the
    content.
  - The stamp moves when a hold **begins and ends**, and reads **busy** while one is in progress. Stamps
    come from one global monotonic sequence, and an evicted content reads the eviction floor, never 0.
  - ⚠⚠ **The sequence is read before the walk reads a single holder.** A per-content snapshot taken after
    the walk would already include an overwrite that finished between the walk's read and the snapshot,
    so the stale edge would pass. A content stamped at or below the walk's start, and not busy, was not
    touched by any emit during the walk or the declare.
  - A content whose stamp moved is re-resolved, re-declared and re-checked, a bounded number of times.
    If it never settles, its refs cannot be re-resolved after a detected race, or a holder's current
    content cannot be read (`null` is "retired" **or** "unreadable"), the pass stays
    **incomplete** (it runs again) rather than latching v6 over a stale description.
  - A holder that an overwrite moved on to other content is re-declared under that content.
  - **No key is held.** A multi-key holder deadlocks against an overwrite, which holds the old key while
    it takes the new, and holding keys across hundreds of manifest `GET`s would stall every emit.
  - ⚠ Only this runtime's emits are visible; another device's publish of the same derived id is CR-2's M3.
- ⚠⚠ **The deferred drain honours the marker** (peer finding):
  - The gap: it wrote the marker but never read it. `ensureContentOnRelay` finds content another device
    published (a derived rung id is the same on every device), declares this holder and leaves the parked
    journal entry in place. The drain then re-uploaded that content under a fresh CEK.
  - Now a marked entry is retired, not published. That is also the KEEP rule's only exit.
  - ⚠⚠ **The marker is a hint, and the relay confirms it** (`contentResolvability`) before anything
    is retired. The marker lives in memory and a move never clears it, so it can outlive the relay's
    copy. The case: publish at `/a`, move away, park a repair, move back, and the retarget names `/a`
    again. `ABSENT` publishes, `UNKNOWN` keeps the entry. It costs one request, only on a marker hit.
  - ⚠⚠ `RESOLVABLE` proves the bytes exist, not that the relay's description matches the manifest. A
    forced repair through another holder of the content can publish fresh refs and then fail its
    declare. So the holder is **re-declared from the manifest's refs before the entry is retired**, and
    the entry is kept if that declare fails.
  - The marker is never cleared on that path, because unmarked the next pass would re-upload. The
    marker path applies while the holder still names the content, and also when the holder read
    **failed** while the bytes are present. Otherwise that case fell through to a duplicate publish. The
    declare then refuses, so the entry waits for a readable pass.
  - ⚠⚠ **Resolve-then-declare settles against the per-content stamp** (`resolveAndDeclareSettled`, used
    by the drain and by `ensureContentOnRelay`). A caller's key covers one `(content, property)`, so an
    emit through another property can publish between the resolve and the declare. The stamp is read
    before and after, not counting the caller's own hold, and the pair is redone until it holds still.
    If it never settles, nothing is declared and nothing is retired.
  - A **forced** emit clears the marker **before** journalling, so a parked repair is never retired as done.
  - The drain marks **before** it clears the journal, so a clear that throws leaves an entry that
    retires on the next pass.

**Owed:**
- ✅ **Done on the maintainer's realm, 2026-10-01** (CR-2 B0): the undescribed remainder was reclaimed
  as a one-off operator step. ⚠ **The gate is the runbook's, and only the runbook's**
  (`docs/how-to/relay-content-reclaim.md`): the one-content-one-upload build on every emitting device, one completed pass, the
  plan's edge-only-with-manifest count at 0, the legacy-emit check, an archive of the blob store, and the
  maintainer's approval. A second device's agreement is **verification after the fact**, not a gate; it
  was a gate here until 2026-10-01, when the archive replaced the stack of up-front proofs.
  ⚠⚠ Freeing by age **before** a re-describe pass still breaks files; that part is unchanged.
- Longer term: a re-emit that reuses its CEK, so identical bytes give identical refs (crypto; threat model).

## 10. Evaluated and not adopted: the log as the declaration

> ⚠⚠ **Evaluated and NOT ADOPTED (2026-09-10).** Written 2026-09-09 as a variant to weigh before
> CR-5; CR-5 shipped the same day and the variant was reviewed in place (the annotations below,
> 2026-09-09). Four findings decide it, each carried by the annotation that made it:
>
> 1. **It re-derives the relay table; it does not remove it** (§10.3). A fold over the log is an
>    index the relay must keep — `content_edge` + `content_ref` are that index, handed over.
> 2. **The deciding cost is recoverability, not which plane computes** (§10.4). A wrong tier-1 body
>    drop is recoverable by construction; a wrong tier-2 delete only if some device holds the
>    plaintext. The variant makes tombstone ordering the authority for the unrecoverable kind.
> 3. **The move re-emit is a correctness requirement, not a cost** (§10.4): the old hash's tombstone
>    drops the edge and nothing re-declares for the new holder. Today §9.5's veto covers it.
> 4. **The size question needs p99 file size on the drain every joiner replays** (§10.7), not a
>    median from an 11-file realm; the two-plane split exists because multi-MB blocks OOM'd Control.
>
> ⚠ **It does not help CR-6 either**, which an earlier status note suggested. Sourcing description
> from the log instead of from the tables changes the transport, not the gate: an orphan has no live
> manifest block exactly as it has no live edge, so `allRefs − live` is safe under §10 only once every
> live content object carries a manifest block — the same realm-wide completeness §9.9b already owes,
> with Fit declaring and slice snapshots excluded. Compaction re-emits *live* content, so it reaches
> the existing pile no better than CR-4d.
>
> ⭐ **What survived, by other routes:** the positive rule (§9.5, relay-enforced by CR-5), the
> schema-driven walk (§9.6b), the per-request refs cap (CR-4d). The argument below is kept as the
> record of why a relay that is *told* was the right shape. It is not a pending decision.

**Status: a variant to evaluate, not a decision** (2026-09-09, from a review of CR-3…CR-4b).
**Why now:** CR-5 is the slice that gives the edge and ref tables *authority*. If the ordered log
can carry the declaration itself, those tables are an index the log already implies — and the
right time to find that out is before anything frees on them.

### 10.1 The duplication, named

The ordered log already has a complete lifetime model: every block carries `targetEntityIdHash`,
a tombstone retires the entity, the relay's body GC is quorum + retention, and a replica rebuilds by
replaying `relay_seq`. **The lifetime of a block is the lifetime of its entity, and the relay knows
it from fields it is allowed to read.**

The content plane has none of that, by design — [ADR 0017](../adr/0017-file-content-two-plane-channel.md)
D1 put chunk bodies outside the log so they are never drained and never fanned out. So *"which
entity keeps this blob alive"* was reinvented twice: first as the candidate ledger + barrier, a
client-side approximation (§1); then as the edge + ref tables, a relay-side index (§9). ⚠⚠ **The
edge table is the content plane's `targetEntityIdHash`; the ref table is its manifest.** Both are
re-declared to the relay out-of-band because the log's own copy of that information is inside
ciphertext.

Content addressing made the gap deliberately: addressing the manifest by `contentId` instead of by
entity id is what makes a move pure metadata (`file-content-addressing.md` §3.1), and it is also what
severed the entity↔manifest link that the holder edge now re-adds as a table. That is the whole of
the duplication — not the two planes, which must differ in retention (`retention-quota-model.md` §2:
conflating them was the blob-GC data loss), but the *reference bookkeeping*, which exists twice.

⚠ Three laziness mechanisms are **orthogonal**, not duplicates, and are worth naming so they are not
folded into this: **transport** (`Syncable(SyncStrategy.OnDemand)` — are the bytes here at all),
**projection** (`@defer`, `core-deferred-properties.md` — when a selected property resolves), and
**residency** (`bounded-store-cache.md` — what stays in memory). `Syncable`'s own KDoc calls the
first two "the orthogonal axis". None of them is the subject of this section.

### 10.2 The variant

Emit the manifest of an `OnDemand` property as a **block on the ordered log** — which it was before
content addressing, and still is for the legacy path (`SyncObserver`'s `contentId == null` branch,
where the manifest rides `pendingBlocks` keyed by the entity hash) — and put the **ref list in the
relay-visible envelope**, next to `targetEntityIdHash`:

```
SyncBlock { blockId · ownerId · hlc · targetEntityIdHash · refs[] · supersedes? · encryptedPayload }
```

The chunks stay on the content plane exactly as now. `refs` is empty on every ordinary block; a
block with a non-empty `refs` *is* a manifest block, and the relay needs no other way to tell.

What the relay can then compute from fields it already indexes:

```
live(ref) ⇔ ∃ manifest block B naming ref, where
            B's entity is not tombstoned  ∧  no later block names B in `supersedes`
```

| event | what the log says | effect on liveness |
|---|---|---|
| upload | a manifest block for `(E, slot)` naming `[refs]` | refs live |
| overwrite | a newer manifest block whose `supersedes` names the old one | the old refs drop out — §3.2 closes with no recorder and no upsert |
| delete | a tombstone for `E` | `E`'s refs drop out — §5.2's undeclare, with no route and no drain hook |
| recreate (§6c.1) | a block with `hlc > tombstone.hlc` | `E` live again, refs with it — **the CR-5 blocker of §9.3 item 4 does not exist** |
| copy-as-metadata / `SharedFile` | another entity naming the same refs | still live — a set, never a count |
| move | the `DataId` is path-derived, so `targetEntityIdHash` changes | the manifest block is **re-emitted under the new hash** — small, metadata-only, and the same old-side retirement `DataIdMigrator` already performs (`file-content-addressing.md` §3.3.4); the old block keeps the refs live until its tombstone lands, so there is no window |

⚠⚠ **Why `supersedes`, and not a per-property slot.** `propertyId` is not in the envelope — it
lives inside `DataBlockPayload`, under the ciphertext — and that is a **closed threat-model
decision**, T-R-08: a relay-visible property discriminator let the relay profile users by which
property kinds their blocks carried, and was removed. The first draft of this section put an opaque
`refSlot` in the envelope to tell two `OnDemand` properties on one entity apart (`Syncable` KDoc);
that re-opens T-R-08 in a weaker form and is withdrawn. A `supersedes` pointer discloses only *"this
block replaces that one"*, which an overwrite already discloses by timing — no vocabulary, no slot
to enumerate. The emit already holds what it needs: the previous manifest block's id for the same
property. ⚠ A manifest block must always carry `targetEntityIdHash` — it must never ride a lane
batch, whose blocks carry `null` (`sync-device-catch-up.md` §11.8).

**The ghost-file grace** (§6c.2) becomes a relay policy rather than a client protocol: the relay owns
the consumed cursors since RH-1b, so it may *choose* to hold a dead entity's refs until every
admitted peer has consumed the tombstone, or for a fixed grace, or not at all — and no client holds
a handle on that choice, which is D2's requirement.

### 10.3 What it buys

- **Zero *client* reclamation code.** No candidate ledger, no barrier, no Drive-only live walk, no
  edge declaration, no drain undeclare, no backfill pass. CR-2's deletion list grows to include the
  client halves of CR-3, CR-4 and CR-4b.
  ⚠⚠ **It does not remove the relay-side table — it re-derives it** (correction, 2026-09-09). §10.4
  requires `refs` to ride the **marker** and outlive body GC, so the relay must still materialise
  *"the latest ref set per (entity, slot), kept for ever"*. That projection **is** `content_edge` +
  `content_ref` with a different write path: computed rather than told. What the variant genuinely
  deletes is the client half — the routes, the drain hook, the backfill — which is a real and
  substantial saving. "No edge or ref table" is not what is on offer, and the decision reads
  differently once that is stated: it is *who computes the projection*, not *whether one exists*.
- ⭐ **The four undeclared populations of §9.3 collapse into one mechanism that already exists.**
  (1) *Every byte uploaded before CR-4* — compaction (`core-schema-evolution.md` E4, built, lacking
  one production DI line) re-emits current state as fresh blocks; re-emitting every manifest block
  re-declares every ref set. ⚠ Two facts about E4 as built (`sync-device-catch-up.md` §11.8): it
  **never runs** today, and it **lengthens** the log — the relay drops bodies only for tombstoned
  entities, so a re-emitted block adds one and removes none. For a one-off backfill that is
  acceptable: one small manifest block per file. ⭐ And `supersedes` hands the relay the one
  supersede rule §11.8 found it could not have generically — a manifest block that names its
  predecessor may have that predecessor's body dropped, because it was *told*, not derived. (2) *A failed declaration* — there is no declaration to fail; the
  manifest block is the declaration, and the pending journal already retries it. (3) *The legacy /
  shared-realm manifest path* — already on the log; it needs only the `refs` field. (4) *The
  recreate* — is a block.
- ⭐ **The backfill is compaction** — once E4 has its production DI line. Not a new schema-driven
  walk to write and schedule (§9.3 item 1), but the pass the repo already has, run once per device.
  This is the "smarter backfill" in one line.
  ⚠ **Weaker than it reads, on a verified fact** (2026-09-09): a schema-driven walk is **cheap to
  write today**, because `ObjectStore.rowsOfTypes(typeIds)` already exists — implemented by the
  SQLite and in-memory stores, returning `null` where unsupported so absence-of-capability stays
  distinguishable from absence-of-rows. It is the primitive compaction itself uses. So "the repo
  already has the pass" is true of the *enumeration*, and the enumeration is available to a
  client-side backfill without §10 at all. ⇒ this bullet argues for **sourcing the backfill from
  `rowsOfTypes` rather than from Drive's tree**, which is a change CR-4d can take on its own; it is
  not an argument for moving the declaration onto the log.
- **The Drive-only walk hazard disappears.** Liveness is decided per block, so Fit's field-less
  `Workout.Streams`, a `SharedFile` in a shared realm, and the legacy path are covered by the same
  rule as a Drive file — no walk has to know which types hold content.
- **Q3's asymmetry becomes moot.** There is no client listing to be short, and a block is atomic,
  so the relay never sees a partial ref set — §7b.3 #6's transaction is unnecessary rather than
  load-bearing.
- **Enumeration (option C) is a relay query.** `allRefs − liveRefs` with both sides on the relay,
  in the same unit — the join §6 worried about, dissolved.

### 10.4 What it costs, honestly

- ⚠⚠ **The relay applies tombstone ordering to reach a verdict that deletes tier-2 data.** It
  already applies exactly this ordering — highest-HLC tombstone per `targetEntityIdHash` — to drop
  tier-1 bodies (`MailboxStore.gcSweep`), so it is the same computation on the same fields. What is
  new is the *consequence*: a wrong verdict there costs a delivery-buffer body a peer can re-request;
  here it costs the only durable copy. `retention-quota-model.md` §2's warning is about conflating
  the tiers' **retention**, not about sharing an index — but this is the sentence the decision turns
  on, and it should be made deliberately.
  ⚠⚠ **Sharpened (2026-09-09): the asymmetry is RECOVERABILITY, and the variant goes further than
  sharing an index.** A wrong tier-1 drop is recoverable *by construction* — the block is a delivery
  buffer and a peer still holds the state. A wrong tier-2 delete is recoverable only if some device
  happens to still hold the plaintext, which for `OnDemand` content on a phone is precisely what
  cannot be assumed (§6c.1). The two verdicts are therefore not one computation with two
  consequences; they are one computation whose **error budget** differs by everything. ⚠ And the
  variant does not merely share an index — it makes the ordered log's tombstone ordering the
  **liveness authority** for durable content. That is the blob-GC data loss's shape at the level of mechanism even
  with the retention windows kept apart, so this owes `retention-quota-model.md` §2 a **head-on
  amendment** arguing the taxonomy survives a shared authority, not a note that the windows differ.
- ⚠ **`refs` must outlive body GC.** Bodies are dropped after quorum + 30 days; the marker is kept
  for ever. The ref list rides the **marker** (the index row, beside `targetEntityIdHash`), never
  the body — otherwise the relay forgets a live file's refs a month after it was uploaded.
- **An envelope field is a wire-shape change**, so this bumps `RELAY_PROTOCOL_VERSION`
  (`RelayProtocol.kt`: envelope reshape) and coordinates a redeploy — unlike CR-3's additive routes.
  Pre-release, this is cheap (Q6), which is one more reason to decide now.
- **One block per emit, sized by ref count.** A 2 GiB file at 2 MiB chunks is ~1 024 refs, ~70 KB of
  envelope; bounded by the same per-edge ceiling CR-4b chose (4 096). Ordinary files are a few refs.
- **A move re-emits a manifest block.** Metadata-only and small, but it is a block a move does not
  emit today. ⚠⚠ **A correctness requirement, not a cost** (verified 2026-09-09):
  `MoveFileImplementation` emits an old-path tombstone and does *"No content re-emit"* (line 295),
  and a move changes the path, hence the `DataId`, hence `targetEntityIdHash`. So under §10.2 the
  old entity's marker is retired and the new entity has none — the refs go dead and the content is
  reaped — unless the move emits one. It also partly undoes what `file-content-addressing.md` bought
  by making move pure metadata.
  ⚠ **The table design has the mirror of this, and it is live today**: the same tombstone undeclares
  the edge, and nothing re-declares it for the new holder. §9.5's veto saves it — but only because
  the walk is keyed on `contentId` rather than on holder. Move is therefore a **second** reason the
  walk is load-bearing, alongside the recreate, and §9.5/§9.6 should say so. ⚠ Alternatively key the manifest block by a stable id where one exists — `SharedFile`
  has `fileId`; the personal `File` does not — which is the shape `drive-shared-realm-file-model.md`
  D1 introduced for exactly this reason.
- **The relay is less dumb.** It computes liveness from the log instead of storing declarations
  verbatim. HR #1 is untouched — every field read is one it already indexes — but the project has
  preferred a relay that stores what it is handed, and this moves one correctness decision behind
  the ciphertext boundary. That preference is a reason, not a rule; §2's ADR 0017 D6 was the last
  time it was weighed, and it lost to shipped code.

### 10.5 What it does not change

HR #1: the relay decrypts nothing and reads only refs and hashes it already stores. The three
tiers: tier-2 reclamation is still driven by a relay-visible reference, never by time. The Q5
unavailable state and ST-3f repair: unchanged. ADR 0017 D6's supersession: unchanged in kind — the
disclosure is the same ref set ST-3b sends and CR-4b stores, now on the block instead of in a table.

### 10.6 On "is this event sourcing"

No, and the difference is load-bearing for how the backfill should be understood. Event sourcing
keeps an event **history** as the source of truth, folds state from it, and snapshots only for speed.
Tenshu's ordered plane carries **new values** with last-writer-wins per field and CRDT tombstones,
and the relay GCs bodies after quorum + 30 days: the log is a bounded delivery buffer (tier 1), not a
history, and nothing can be replayed from the beginning. The source of truth is replica state. Two things
hand it over, and `sync-retention-horizons.md` §6 tabulates them as one family: compaction (E4,
built, not running) re-emits it as blocks; the `sync-device-catch-up.md` §5.7 slice snapshot (C8,
**shipped**) hands a joiner its first screen from one fetch. ⚠ Be precise about the second: it is
*content now* — opt-in per relation, never correctness-bearing, and it **leaves the cursors alone**
(§11.4.1), so the log still replays underneath it. It is necessary for **usable-before-complete**,
not for correctness; the re-bootstrap past the retention horizon that would make it
correctness-bearing (`sync-retention-horizons.md` §5–§6) is design only. So the snapshot is the
state-based counterpart of an event-sourcing snapshot, except that here it would be **necessary**
rather than an optimisation the moment the horizon design ships. The content plane has no history at all; it is a content-addressed heap. The
backfill is the one event-sourcing idea that does apply — a **projection rebuild** — but today its
replay source is the manifests a client holds, not the log. Under §10.2 it becomes a fold over the
log, and compaction is the snapshot that bounds it.

### 10.7 The decision, and when

Answered 2026-09-10 — see the verdict at the top of §10. Two questions decided it:

1. **May the relay compute a tier-2 delete from tombstone ordering?** It computes tier-1 body drops
   from the same ordering today. If *no*, keep the tables — but CR-4c's backfill should still be
   compaction, and the CR-5 rule should be the positive one: free only refs of a **declared** content
   object whose holders are all dead, and never touch an undeclared ref.
2. **Is a manifest small enough to ride the log at scale?** Ref count × ~70 B per emit, once per
   write and once per move.
   ⚠⚠ **Respecified (2026-09-09): the measured realm cannot answer this.** Eleven files whose whole
   story is their *orphans* yields a median, and the median was never in doubt — an ordinary file is
   a few refs. The number that decides it is at the **large end**: a 2 GiB file is ~1 024 chunks ≈
   **72 KB of refs in the envelope, on the ordered drain, delivered to every device**. The two-plane
   split exists because multi-MB blocks OOM'd Control ([ADR 0017](../adr/0017-file-content-two-plane-channel.md)), and this puts a
   fraction of that back onto the plane built to stop carrying it. So the experiment is **p99 file
   size across a realistic library**, × chunk count, × the envelope cost — and the question it
   answers is not "does one manifest fit" but "what does a photo library's worth of them do to the
   drain a joiner must replay".

Neither answer is *yes*. The tables stay, and CR-6 owes the same completeness gate whichever
transport carries the description.

---

## 11. ⭐ CR-6 as a deterministic function of relay state

**Status: a design to evaluate before CR-6 is built** (2026-09-10). §9.9b gates CR-6 on *"description
provably complete realm-wide"* and calls that the hard part, the same unanimity the reclamation barrier
had. This section makes the gate a **relay-visible fact** and the sweep a **pure function of relay
state at a sequence number**, so that any two runs over the same state give the same set and no
client walk sits in the loop. It costs two changes of where information travels, and no change to
what the relay is allowed to know.

### 11.1 What "deterministic" has to mean here

`orphans(S)` must be computable by the relay from its own state at relay sequence `S`, with the same
answer on every run, and its safety must not depend on any client's walk having finished. Neither
mechanism today qualifies:

- **CR-5** is safe because an `Incomplete` walk reclaims nothing (§9.8) — correct, but the veto is a
  client walk, and its answer depends on which device ran it and when.
- **CR-6 as sketched** (§9.9b) gates on every device's backfill having completed — a per-device
  *belief* held in a local marker that the relay cannot see, and one that a declaration failing
  *after* the marker latched silently falsifies.

### 11.2 Why catch-up alone cannot supply it

The tempting gate is RH-1b's: *every admitted device's consumed cursor is past S*. It is deterministic
and relay-visible, and it is the right gate for one half of the question — *has everyone seen the
delete?* But a cursor says *"I have consumed every block"*, not *"every content object I hold is
described"*, because **description is not on the log**. CR-4 declares over a side route, after the
manifest `PUT`, best-effort by decision (§9.2). A declaration that failed leaves live, undescribed
content that no cursor reveals — §9.9a's fourth generator. Catch-up cannot see what was never sent.

⇒ Determinism needs description to be **ordered against the log** and **impossible to lose**. Two
moves do that; the second is small once the first is understood.

### 11.3 Move one — the *edge* rides the log; the *ref set* is journalled and seq-stamped

⚠⚠ **Not §10.** §10 put the whole manifest on the drain and let the relay compute liveness from
tombstone ordering; it lost on recoverability and on p99 file size (§10.7). This carries the
**smallest possible declaration** on the log and leaves every freeing decision where CR-5 put it.

**(a) The edge is a block.** When the emit declares, it enqueues an ordinary block in the pending
journal whose relay-visible envelope carries the declaration the CR-3 route carries today:
`targetEntityIdHash` (the holder) and the `contentId`. Fixed size, a few dozen bytes, no refs. The
relay, on receipt, upserts `content_edge` exactly as the route does — **told, not derived** — and
otherwise stores the block as any other. Because it is a block:

- it is **journal-retried until it lands**, like every write — a failed declaration is now a pending
  block, not a lost fact;
- it has a **sequence number**, so *"declared by S"* is a relay-visible predicate;
- ⭐ **the recreate can re-declare.** §9.3 item 4 called this structurally blocked because
  `SyncProcessor`'s recreate path runs inside a non-suspend transaction and cannot call the network.
  Enqueueing a block is a local `addToArray` inside that same transaction. The blocker was the side
  route, not the recreate.

**(b) The ref set stays on the side route, but journalled and seq-stamped.** The refs are the
payload (§10.7's size question is really about them), so they do not ride the drain. Instead the
client's journal retries `POST /sync/content/declare` for the ref set until the relay acknowledges it,
and the relay stamps `content_ref` rows with the relay sequence at which they landed. Then
*"refs landed by S"* is also a relay-visible predicate, and a content object is **described at S** iff
its edge block has seq ≤ S and its ref rows carry a stamp ≤ S.

**(c) Undeclare.** Two options, identical in timing, different in taste:

| | mechanism | HR #1 | ordering |
|---|---|---|---|
| tombstone-derived | the relay drops holder `H`'s edges when it stores a tombstone for `H` | one inference on a field it already indexes for body GC; frees nothing | at the tombstone's seq |
| explicit release block | the drain enqueues a `release(H)` block beside the tombstone | zero inference | the block's seq, one later |

Both are deterministic; §11.8 Q1 decides between them. What matters is that the undeclare is on the
log and therefore ordered against `S`.

### 11.4 Move two — the per-device marker, published like a cursor

With §11.3 the only source of live-undescribed content is content from **before** the declaration
format existed — the backfill's population. The marker §9.7 keeps locally becomes a value the device
**publishes** to the relay, in the shape of its cursor: `(deviceId, format F, complete)`. And its
meaning tightens from *"the backfill ran clean once"* to a **live invariant**: *"this device holds no
content object without a landed declaration at format F"*. The journal makes that checkable — it is
*empty of declarations* — so the marker cannot be true while a declaration is still pending, and it
is re-evaluated, not latched.

### 11.5 The function

```
described(S)  = { C : edge(H, C).seq ≤ S  ∧  refs(C).stamp ≤ S
                    ∧ ¬ (tombstone(H).seq ≤ S with no later edge(H, C) ≤ S) }
live(S)       = ⋃ refs(C) for C ∈ described(S)
gate(S)       = ∀ admitted device d : cursor(d) ≥ S  ∧  marker(d) = (F, complete)
orphans(S)    = { b ∈ blobs(owner) : b.created ≤ S − W }  −  live(S)
```

Every term is relay state. `W` is the in-flight window; under §11.3 a blob whose edge block has
landed but whose refs have not is *described-in-progress* and never a candidate, so `W` only has to
cover the gap between a chunk `PUT` and its edge block — one journal step. Slice snapshots need no
exclusion term once `SliceSnapshotWriter` declares its blob under a synthetic holder (§9.9a's writer
inventory), which is the shape this design wants for every writer: **nothing legitimate writes to
the content plane without a declaration on the log.**

**The act is two-phase and relay-owned.** At `S` with `gate(S)` true, the relay **quarantines**
`orphans(S)` — still charged, so D1's amplification (§6c.3) cannot return — publishes the count and
bytes, and frees after a grace `G` unless a declaration naming a quarantined ref lands first, which
cancels it. Replaying the computation at `S` on any relay gives the same set, which is what makes the
report auditable before anything acts.

### 11.6 Why both failure directions are closed

- **A short live set** — the blob-GC data loss's direction — is impossible once `gate(S)` holds: every device has
  consumed past `S`, so every edge block ≤ `S` is in `content_edge`; every marker is complete at `F`,
  so no pre-`F` content is undescribed; and a pending declaration keeps its device's marker false,
  so the gate stays closed until it lands. Nothing in this depends on a walk.
- **A short blob list** under-deletes, which is safe.
- **A device that never catches up** holds the gate closed. That is the barrier's shape and the
  barrier's answer applies: revocation removes it from the roster (§4.16 of
  [`drive-upload-stop-and-reclamation.md`](drive-upload-stop-and-reclamation.md)).
- **CR-5's veto walk becomes redundant for CR-6** — the log is the veto — and may stay as
  belt-and-braces through the transition. It is no longer what safety rests on.

### 11.7 What it retires, and what it costs

Retires: the best-effort declare (it becomes a journalled block plus a journalled, acknowledged ref
post), the drain-side undeclare route (a tombstone or a release block), the walk as the safety
authority, and the structural blocker on the recreate re-declare. With
`sync-entity-identity.md` EI-2 (open) a move no longer retracts a declaration at all, so re-declaration traffic shrinks to genuine concurrent edits.

Costs, honestly:

- **One small block per declared emit** on the ordered plane — holder and content id, no refs. The
  drain grows by the number of content objects, not by their size; §10.7's p99 objection does not
  apply because the refs are not on it.
- **A sequence stamp on ref receipt** and **a published marker per device** — two new pieces of
  relay-visible metadata, both A6-shaped (a seq the relay assigns; a device state it already holds a
  cursor's worth of). T-R-09 owes an amendment naming them.
- **The relay evaluates a set-difference gated on cursors.** It already computes
  `content_ref ∖ content_edge` for CR-5 and already compares cursors for RH-1b; this composes the two.
  It derives no liveness from tombstone *ordering* — the objection that decided §10 — because a
  tombstone here only retracts an edge, and freeing is a separate, gated, quarantined step.
- **Declarations must carry `targetEntityIdHash`**, never `null`: they are single-entity blocks and
  must not ride a lane batch (`DeltaBatchPayload`), whose blocks the relay cannot index.

### 11.8 Open questions

- **Q1. Tombstone-derived undeclare, or an explicit release block?** Same seq semantics. The first
  is one inference on a field the relay already indexes; the second is zero inference and one more
  block per delete. Taste under HR #1; the freeing decision is untouched either way.
- **Q2. The marker on the web.** JS wires no sweep and writes no content today
  (`drive-web-parity.md`); a web device either publishes `complete` trivially or is excluded from the
  gate as a non-content device. Decide which, and record it, before the web content plane is wired.
- **Q3. What `W` is once edges are ordered.** It covers only the chunk-`PUT`-to-edge-block gap; with
  the journal that is bounded by the emit itself. Measure it rather than pick it.
- **Q4. Grace `G`.** The ghost-file argument of §6c.2 applies unchanged: relay-owned, bounded below
  by re-declaration lag, reportable.
- **Q5. Does the edge block replace the route, or ride beside it?** Beside it during transition —
  the route stays for the ref set regardless — then the edge half of the route is deleted.

### 11.9 Phasing

| | what |
|---|---|
| **CR-6a** | `SliceSnapshotWriter` declares its blob; old workouts re-emitted with an id (§9.9b) — no undeclared legitimate writer remains |
| **CR-6b** | Publish the marker; make it the §11.4 live invariant |
| **CR-6c** | The edge declaration as a block; the recreate enqueues one in its transaction; undeclare per Q1 |
| **CR-6d** | Seq-stamp on ref receipt; journal-retry the ref post until acknowledged |
| **CR-6e** | ✅ **shipped 2026-09-11** (§11.11) — the relay inventory, **reported, never freed**: the number §9.9b wanted before building. ⚠ Shipped un-gated (`blobs − refs(described)`), **not** `orphans(S)` — see §11.11 |
| **CR-6f** | Quarantine + grace + free, dry-run first, behind the same deploy-time switch as CR-5 |

### 11.10 Tests

1. **Determinism.** Two evaluations of `orphans(S)` over the same relay state return the same set;
   an evaluation at `S` after later writes still returns the `S` answer.
2. **The gate.** One cursor below `S`, or one marker incomplete, and `orphans(S)` is not offered.
3. **The recreate re-declares.** `SyncProcessor`'s `hlc > tombstone.hlc` path enqueues an edge block
   in the same transaction; the relay's edge is back before any grace can end.
4. **A failed ref post is retried until stamped.** Kill the connection on the first attempt; the
   journal retries; `content_ref` rows carry the stamp; the marker was false in between.
5. **A late declaration cancels a quarantine.** Declare a quarantined ref before `G`; it is not freed.
6. **A snapshot blob is never a candidate** once the writer declares it.
7. **Pinned leak, §7b.3 #4's shape.** A blob with no declaration and no manifest — an aborted emit —
   is exactly what `orphans(S)` returns, and nothing else does.

### 11.11 As built — CR-6e, the inventory (2026-09-11)

`GET /sync/content/inventory`, owner-scoped, read-only. `sync-relay-api.md` carries the wire shape;
`ContentInventory.kt` carries the arithmetic and its caveats.

⚠⚠ **It is not `orphans(S)`, and the difference is the gate.** §11.5's function is evaluated *at a
sequence number* and offered only when `gate(S)` holds — but `S` needs CR-6c's edge blocks and
CR-6d's ref stamps, and `gate(S)` needs CR-6b's published marker. **None of those exist.** What
shipped is the un-gated steady-state form the track sequences as P2:

```
inventory(owner) = blobs(owner) − refs(described(owner))
```

⭐ **That is sound only because it frees nothing.** The gate exists to make *freeing* safe; a report
needs no gate, and demanding one would have made the number unobtainable until the whole of CR-6
was built — which is the inversion §11.9 was written to avoid. CR-6f still owes the gated form.

**What sits inside the number, with no defect implied** (§9.9a's writer inventory): bytes uploaded
before CR-4; ⚠⚠ **slice snapshots**, which declare *never* — ⭐ a **stable floor**, since
`sha256("snapshot:$realmId:$relationId:$sliceKey")` is deterministic and a re-seal overwrites the
same ref; the legacy `else` branch (**Fit**); **aborted emits**; and **in-flight uploads**, reported
as `recentlyTouched*` (§11.5's `W`) and ⚠⚠ **never subtracted** — `last_referenced_at` is refreshed
by `GET` as well as `PUT`, so a download is indistinguishable from an upload and subtracting would
hide live old bytes behind a read. ⇒ CR-6a is what removes the snapshot floor; until then the number
has one.

⚠ **Per owner is per realm**: `ownerId` is derived from the realm's master public key, so one owner
is exactly one realm and every figure is realm-wide.

**What it deliberately excludes.** A CR-5 *candidate* — described, no live edge — is **not** in the
undescribed figure: its bytes are described, so CR-5 owns them. The report carries
`candidateContentCount` beside the undescribed figure so the two mechanisms can be **compared rather than
summed**, which is this track's recurring failure mode. A guard test pins that boundary by
mutation: swapping `described` for `referenced` in the keep set fails exactly one test.

⚠⚠ **The byte figures are lower bounds**, and the reason is not the one this section first gave.
A 0 in `byte_len` was **either** the pre-v9 `DEFAULT 0` (schema v9, never backfilled) **or** a blob
whose bytes are absent from the store; SQL cannot separate them. (⚠ Since the 2026-10 reset,
the column has no default and every writer records the length, so a 0 now means a stored
zero-length body.) The report names those rows
(`zeroLengthBlobCount`, `zeroLengthUndescribedBlobCount`) rather than summing them into a total that
would read as *"almost nothing is stranded"*.

⚠⚠ **Production, 2026-09-11: all 278 were the second kind.** Their files existed and were 0 bytes,
their refs were distinct, and none was sha256 of the empty string ⇒ the ciphertext those refs name is
**gone**, not merely unrecorded. They were written in one window, 2026-09-05 14:07 → 2026-09-07
17:14. ⚠ `FileBlobStore.get` returns `readBytes()` for any file that exists, so a `GET` on one
answers **200 with an empty body**: the caller fails to decrypt rather than seeing a 404, which is
why nothing reported this. **This is a defect of its own, not an inventory caveat** — it is recorded
here only because the inventory is what surfaced it.

⚠ Additive route, so no `RELAY_PROTOCOL_VERSION` bump — but the number is unobtainable until the
relay is **deployed**.
