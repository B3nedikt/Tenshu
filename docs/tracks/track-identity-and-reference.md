# Track — identity and reference on the two planes

Four proposals, one question: **what names a thing, and who holds it.** The ordered plane names
entities (`DataId` → wire id); the content plane names bytes (`contentId` → refs). Every open item
in this cluster is a consequence of one of those two names being wrong, unstable, or unverifiable —
which is why they sequence against each other rather than in parallel.

| | doc | the name it fixes | state |
|---|---|---|---|
| **EI** | [`sync-entity-identity.md`](../proposals/sync-entity-identity.md) | the entity's **wire id** — an HMAC-SHA256 keyed id (`WireEntityId`), replacing a 32-bit unsalted `String.hashCode` of the path — ⚠ **the trap**: the old value was guessable and collided at measured realm sizes; ⭐ **EI-4** (§5b): the tombstone's clear entity *type* is a discriminator, not a type | §6 carries the phase state |
| **CR** | [`content-plane-refcount.md`](../proposals/content-plane-refcount.md) | **who holds** a contentId — the declared edge set | CR-0…CR-5 shipped. ⭐ **CR-2 = candidate B** of [`content-plane-cr2-quorumless.md`](../proposals/content-plane-cr2-quorumless.md) (decided 2026-09-29, unbuilt): phases §10.7, roadmap §12. CR-6 is absorbed (P5) |
| **§11** | same doc, §11 | CR-6 as a function of **relay state** rather than local belief | design |
| **DD** | [`file-content-addressing.md`](../proposals/file-content-addressing.md) §4.3 | one `contentId` for identical bytes — within-realm dedup | decided, **not built** |

---

## The spine

The order below is not preference; each step removes a reason the next one is unsafe.

### P0 — EI-1: the wire id becomes a keyed hash

`wireId = HMAC-SHA256(K_entity, "${type}_${identity}")`, `K_entity = HKDF(sharedVolumeKey, …)`.

**Why first.** It is the only item describing a *shipped* defect, and it has two faces: the relay can
compute a guessed path's id (a confirmation oracle the content plane forbids itself under ADR 0017
D3), and `p ≈ n²/2³³` gives **2.35 % at 14 208 rows, 29 % at 50 k**, against a 107 k-row replica in
this repo. ⚠ It is free pre-release and a flag day afterwards, so its cost only rises.

⭐ **Keep it a hash, not a random id** — and the reason is structural: a shared wire id is what makes
`SemanticMerger` reachable. Two offline creations of one entity arrive as `existing` + `incoming` for
one type, which is `merge`'s ordinary case (eleven mergers, six services, ADR 0024). Under a random
id nothing merges — two entities hold half the state each, and no code path notices.

⚠ **Open, and worth deciding here rather than later:** derive the id **once at creation and store
it**, instead of recomputing per emit. That would give rename-survival and rotation-immunity (Q2)
from EI-1's own mechanism and make EI-2's separate random `fileId` unnecessary. It trades those for
schema reach — a stored id needs somewhere to live on every synced entity.

### P1 — the emit resumes instead of restarting

Journal the refs an emit has already `PUT` beside its `PendingContentEmit`, and on retry send only
what is missing.

**Why second.** Measured 2026-09-10: one 20 MiB upload over a flaky link cost **3.00×** on the relay
and left **41,945,160 B** that nothing can reach (`content-plane-refcount.md` §9.9b). Each aborted
emit re-keys and re-`PUT`s every chunk, so the multiplier is the attempt count. This is an *active*
generator, not residue.

⚠⚠ **Not by reusing the CEK.** Both AEAD backends draw a fresh random nonce per encryption, so the
same key over the same bytes still hashes to a different ref. Byte-idempotency needs a *derived*
nonce — convergent encryption — which is the cross-user dedup this project rejected on privacy
grounds (`file-content-addressing.md` §2). Resuming keeps the ciphertext randomised and tells the
relay nothing new.

Costs: `PendingContentEmit` gains the landed refs **and the CEK** (an emit cannot be finished without
the key its chunks were encrypted under) ⇒ HR #9 version bump + migration, and an HR #2 line for key
material at rest.

### P2 — the inventory ✅ shipped 2026-09-11

Relay-side `blobs − refs(described)` per owner, reported and never freed (§11's CR-6e).

**Why here.** P1 first, so the inventory measures a steady state rather than its own generator. An inventory
installed before P1 would report the flakiness of whoever happened to be uploading.

**As built** — `GET /sync/content/inventory`, owner-scoped, read-only
(`content-plane-refcount.md` §11.11). ⚠⚠ **Un-gated**: it is the steady-state form above, *not*
§11.5's `orphans(S)`, because `S` and `gate(S)` are CR-6b/c/d machinery that does not exist. Sound
only because it frees nothing — CR-6f still owes the gated form.
⚠ The byte figures are **lower bounds**: a 0 `byte_len` is a stored zero-length body (the pre-v9
default that once also produced zeros went with the 2026-10 reset). ⚠⚠ Pre-reset production held 278
whose bytes were gone — a separate defect the inventory surfaced (§11.11). Read the counts before the bytes.

> ⚠⚠ **P2 measured the track's premise and found it false.** On production `content_ref` and
> `content_edge` are **empty for every owner**, because `ContentDeclarationBackfill` latched its
> completion marker over the empty store the format-21 wipe left behind, so nothing has been declared
> *since* (fixed 2026-09-11 — §9.7a, plus a `BACKFILL_FORMAT` bump to re-arm the fleet).
> ⚠⚠ **Corrected 2026-09-11:** the tables were empty because the relay **never wrote to them** —
> `main()` built the SQLite content stores and never passed them to `module(...)`, so edges and refs
> lived in memory and died with every restart. CR-5 was inert for its whole life, and §9.11's 55
> edges vanished at the next restart. The backfill latch is a real but **separate** bug. §9.7b.
> ⇒ **CR-5 has nothing to act on until the fleet re-declares**, and **P3 must not ship before it
> does** — ST-3b is currently the only mechanism reaching that content. ⭐ Confirm the two halves
> **separately** (§9.7b): one emit-time declare after a new upload, and one batched declare after the
> marker re-arms — `describedContentCount` alone lets a green backfill mask a dead emit path.

> ⚠⚠ **P2's number is over-counted until a full walk runs (§9.7d, corrected 2026-09-13):** the
> 5,211 undescribed blobs include **live, undeclared `Workout.Streams`** — every declared edge was
> `tenshu/file/…` and zero were `tenshu/health/…`. The **bytes** are still mostly garbage
> (the >2M bucket, 992 vs 993, ~78 %); the **count** is not. ⭐⭐ Original reading: 5,211 blobs / **2.67 GB** — and the histogram says they are **superseded upload attempts** (992 orphaned large blobs
> against 993 described ones). P1 has already stopped that generator, so the population should now be
> static; re-reading the gauge is the prediction to check. ⚠ Slice snapshots are ~10 blobs, so CR-6a
> is a correctness fix, not a size lever.

### P3 — CR-2 = candidate B (relay-authoritative references with a grace period)

⭐ **Decided 2026-09-29; the plan lives in
[`content-plane-cr2-quorumless.md`](../proposals/content-plane-cr2-quorumless.md)**: §10 for the
design, §10.7 for phases B0–B6, §12 for the roadmap and its parallel lanes. In one paragraph:
- a push carries its content edges in the relay's own transaction (G1);
- the relay alone frees content, T after its last edge, on its own clock (G2);
- a late reference gets `content_freed` and either revives or shows the loss (G3);
- content that never had an edge is never freed (G4).

No device's progress gates any free, so a lost or fenced device can no longer stall reclamation.

⚠⚠ **This supersedes what this section used to say.** §9.14's option 1 (a device progress quorum plus a
lease fencing local merges, decided 2026-09-22) was replaced, and "remove the wait, not the safety
argument" no longer describes the plan: B's safety argument is the edge register, not device progress.
Do not build the quorum, the lease, the awaiting-restore marker or retained plaintext. Candidate A, the
quorum-less device-progress variant, was also not chosen (§10.6).

What still holds from the old text:
- **P1 before P3.** An emit that resumes until its declaration lands keeps the
  manifest-without-declaration population transient.
- **ST-3b goes in B6.** B5's sweep is the only freer once it is armed.
- **The move-declares-successor fix (§9.16) is retired in B6.** Under G1 the move's new block
  carries its own edge.
- **The reference roots and handles are retired in B6** (maintainer, 2026-10-01). This also settles the
  divergent-move handle hazard and the mint-per-re-key question this section used to raise: B's edges
  are per `(holder, property)`, so two moves of one row are two holders and two references.

### P4 — within-realm dedup

On upload, look up `contentHash → contentId` against live rows and reuse the id instead of minting
one. `File.ContentHash` and `:app-ingest`'s `ContentIndex` already make the lookup expressible
(Photos PH-G3); nothing reuses a `contentId` yet.

⚠⚠ **P3 changes what P4 owes, and the old answer no longer holds.** While the roster barrier stood, a
dedup hit was safe by construction: the live-row veto covered it. P3 removes that barrier, and an
**offline** dedup hit then creates a holder visible only locally — another device deleting the last
declared holder runs a live walk that cannot see the deduplicated row and frees content it still
names. ⇒ dedup hits take D3's custody (`content-plane-refcount.md` §6d): durable intent, repair pin,
declare-then-verify on reconnect. That is a **P4 prerequisite**, not a property P4 inherits.

⭐ **Under candidate B (P3), that custody is no longer needed, so build P4 after B4.** B3 alone is not
enough.
- A dedup hit's new holder is pushed with its edge in the relay's transaction (G1). The relay therefore
  sees the reference the moment the row exists.
- If the content was freed meanwhile, the push gets `content_freed` (G3), and the uploader, who holds
  the plaintext, re-uploads.
- ⚠ That recovery, `content_freed` plus resolving the outbox, ships in **B4**, not B3. A dedup client
  without it that pushes after B5 arms would wedge its sync queue.

Building P4 before B4 would mean building D3's custody for dedup only to delete it in B6.

**Why it belongs on this track, and why now.** §4.3 recorded it as *"not built, and it now owes
reclamation a check before it is"* — because dedup is the case where one `contentId` has **more than
one holder**, and a delete of one holder must not free the bytes. That check is what CR-3/CR-4
shipped: a holder-keyed edge set that frees at zero. Dedup was blocked on this track; the track has
now unblocked it.

- ⚠⚠ **"A lookup against live rows is safe by construction" (§4.3) expired with P3.** It held because
  a hit implied a live `File` row carrying that `contentId`, and the **live-walk veto** protected its
  blobs. ⚠ P3 removes the **roster barrier**, not the walk — the live walk survives to guard recreates
  (`content-plane-refcount.md` §2) — but the walk that matters is the **deleting device's own**, and
  an *offline* dedup hit is precisely the row another device's walk
  cannot see. ⇒ superseded by the custody requirement above; an implementer following the old bullet
  would omit the intent, pin and declare-then-verify and reintroduce the loss.
- ⚠⚠ **Within-realm only, never cross-user** — settled in `file-content-addressing.md` §2, on the
  same reasoning that rules out the deterministic nonce in P1.
- ⚠ It makes free-at-zero load-bearing for the first time. Today >1 holder arises only from move and
  copy; dedup makes it routine, so P3's single-mechanism state should be reached first.

### P5 — CR-6

⭐ **Absorbed under candidate B (2026-09-29).**
- The existing undescribed pile is **B0**'s one-off operator reclaim (`docs/how-to/relay-content-reclaim.md`).
- Afterwards, **G4** means the automatic sweep never frees content that never had an edge. That
  prevents **loss**; it does not stop the population growing. An upload that
  crashes between its blobs and its push still leaves a permanent undescribed leak (§10.5), and legacy
  writers add more.
- ⇒ **Reclaiming undescribed content stays open as recurring work.** Until something removes the
  population, either rerun B0's operator job (with its runbook preflight), or build the population out
  with content-head addressing (`content-plane-cr2-quorumless.md` §11), where an abandoned upload is a
  non-current generation the relay reclaims itself.

The text below is the pre-B plan, kept for its hazards.

Relay-side subtraction under a published gate (§11).

**Why last.** It is the only step that deletes user content on inference, and the failure is
unrecoverable. Its gate — `∀ admitted device: cursor ≥ S ∧ marker = (F, complete)` — should be
carried as a **proof obligation** with the tests that discharge it, not as a design that looks right.

⚠⚠ **The hazard it inherits is roster hygiene**, not arithmetic: one orphaned device id blocks
reclamation realm-wide for ever, and auto-revoke is unsafe. A second instance of the same shape: one
permanently wedged emit keeps a marker false, so the marker needs an explicit *"I give up on this
content"* or any single stuck upload holds the realm hostage. ⭐ Gating on a **stamp** rather than a
clock is a real improvement over the barrier — a stamp cannot fail to converge the way an idle tip
does — but it does not fix the roster.

---

## What this ordering buys

- **P0 and P1 depend on nothing** and can run in parallel; everything after depends on one of them.
- **P1 before P2** so the inventory measures a realm, not a generator.
- **P1 before P3** so removing ST-3b loses a transient population rather than a permanent one.
- **P3 before P4** so dedup makes free-at-zero load-bearing against one mechanism, not two. Under
  candidate B, specifically **B4 before P4**: G1 (B3) and G3's client recovery (B4) together make a dedup
  hit safe without D3's custody.
- **P5 last**, because it is the only irreversible one. Under candidate B, B0's one-off reclaim takes
  today's pile and G4 prevents loss; future leaks stay open until §11 or a recurring B0-style run.
- ⭐ **Inside P3 the work parallelises** (`content-plane-cr2-quorumless.md` §12.5). B0's operations, B1,
  B2 and the independent items can run side by side, and B3 splits into a relay half and a client half
  once its wire spec is agreed. **Against the other tracks** (A, B and C in `README.md`) all of this
  runs in parallel: none of them touches the content plane.

⚠ **The standing risk on this track is design outpacing code.** §10 was evaluated and rejected, §11
is its successor, and the last phase to ship (CR-5) was only verified on hardware on 2026-09-10.
Three of the five phases above are code against decisions already made; that is the half to spend
next.
