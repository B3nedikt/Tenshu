# ADR 0017 — File-content sync via a two-plane channel (manifest + on-demand chunks)

- **Status:** Accepted, implemented (closed out 2026-05-20); **Decision 6 superseded in part
  2026-09-09** by `content-plane-refcount.md`. ⚠⚠ **Decision 6 / alternative (iv)
  REOPENED 2026-09-08** — their shared premise was falsified by ST-3b shipping the very
  disclosure they rejected. ⚠ *In part*: a relay-**derived** refcount is still
  HR#1-impossible and that half stands. See the note on Decision 6 and
  [`content-plane-refcount.md`](../proposals/content-plane-refcount.md).
- **Date:** 2026-05-19 (P1) → 2026-05-20 (P2, P3a, P3b/c)
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Pre-design, file content rode the same ordered sync drain as every other
`SyncBlock`. `UploadFileImplementation` called
`SyncObserver.emitPropertyBlock(value = bytes)` with the *entire* file content;
`SyncObserver.generateSyncBlock` wrapped it in `DataBlockPayload`, AEAD-encrypted
the whole file into one `SyncBlock.encryptedPayload`, and queued the block in
the DB-backed `pendingBlocks`. `DatabaseImpl.getArrayData` materialised the
entire pending list into a `List<Data>` inside the lock by design (lazy
iteration would race concurrent writes), so every `SyncScheduler` drain pulled
the *whole backlog* of full-file blocks into heap. `RestSyncRelay.pushBlock`
re-serialised via a doubling `ByteStringBuilder` + Ktor's body buffer on top of
that.

Two concrete things were broken:

1. **The OOM.** A background folder upload of ~10–13 MB DSLR JPEGs OOM-killed
   the **Control** process (Android Java heap ≈ 192 MB). The full file was
   materialised in heap 5+ times end-to-end and the *entire backlog* was
   resident per drain → heap ∝ Σ(queued file sizes). The receiver was
   symmetric. There was no contained fix: the body *was* the block payload, and
   `getArrayData`'s eager materialisation was deliberate. (The amplifier fix
   — `SyncManagerImpl`'s legacy auto-load deferred off the main thread — removed a `ProxyService` restart-storm — necessary but not sufficient.)

2. **The scale goal.** A realm may hold multiple TB of files. A freshly set-up
   device (e.g. a browser) must be able to open *one* file quickly **without
   downloading all files**. Any design that keeps file content in the *ordered*
   sync drain fails this: a new device would have to drain every chunk of every
   file before it is consistent. File content therefore must not be on the
   ordered plane at all.

The architecture already had the seam pointing at the answer:
`FileBytesPropertyWriter`'s KDoc described an OnDemand resolver
(`Maybe.get()` / `.flow()`) that "dispatches the resolver, fetches the bytes
from the relay" lazily. The eager `emitPropertyBlock` whole-file push was the
*anomaly*; the on-demand path was the intended read model, only incompletely
realised.

The decision **completes** the on-demand seam and **splits** content out of the
ordered drain.

## Decision

Two transport planes, distinct GC semantics, House Rule #1 unchanged. API +
protocol details live in [`docs/sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel).

Eight anchor decisions shape the design:

1. **Two planes, one for manifests, one for content bodies.**
   - **Metadata plane** (existing): a small per-file **manifest** rides the
     existing ordered `SyncBlock` / `DataBlockPayload` drain. Same envelope,
     same tombstone/quorum machinery, same `pendingBlocks`. Small and bounded,
     so every device always has every file's manifest cheaply, regardless of
     total realm size.
   - **Content plane** (new): immutable, independently-AEAD-encrypted chunk
     bodies on a **content-addressed encrypted blob store** on the relay,
     `PUT`/`GET` by opaque ref. Bodies are **never** added to `pendingBlocks`
     and never drained.

2. **Per-chunk AEAD with bound AAD.** Each chunk is AEAD-encrypted
   independently with `(fileId, chunkIndex, chunkCount)` bound into the AAD.
   The manifest carries a whole-file integrity anchor (hash / Merkle root) +
   per-chunk hashes. A partial set never surfaces — the resolver gates on
   manifest completeness, mirroring the existing entity-before-bytes
   ordering note. Peak memory at emit/store/push/fetch/decrypt/reassemble is
   `O(CHUNK)` (1–4 MiB), independent of file size, backlog depth, and realm
   size.

3. **Content-ref opacity: derived from ciphertext (or random), never
   plaintext.** A plaintext-derived ref is a confirmation-of-file / dedup
   oracle. Per-realm keying already prevents identical ciphertext across
   realms; the ref derivation makes that explicit on the wire. The tech-debt entry
   ("Cross-Process Byte Payloads Use Content-Derived IDs") is superseded by
   this — fold the `md5(content)` follow-up into hash-of-ciphertext during P5.

4. **OnDemand read path.** Opening a file goes through the existing OnDemand
   resolver: the device has the manifest (synced eagerly on the metadata
   plane) → fetches **only that file's** chunk blobs by ref → decrypts (verify
   per-chunk AAD + whole-file anchor) → reassembles incrementally into
   `FileDatabase`. `FileBytesPropertyWriter` becomes a manifest writer; the
   OnDemand resolver becomes the byte-fetch path.

5. **Per-owner JWT-scoped content store** (cross-realm denial). The relay
   `PUT`/`GET /sync/content/{ref}` endpoints authenticate via the existing
   device-key JWT and ACL on `ownerId` — a device in realm A can neither
   `PUT` into nor `GET` from realm B's content store. The initial P3a slice
   shipped a global ref keyspace; per-owner scoping was the P3b upgrade in
   place.

6. **Retention/orphan GC, not a client "release refs" signal.** The relay
   cannot read manifests (House Rule #1), so the obvious "client tells us
   which refs are still live" alternative was considered and rejected — it
   would disclose a deleted file's chunk-ref set + size to the relay, a new
   metadata leak strictly worse than the access-pattern residual.
   ⚠⚠ **REOPENED 2026-09-08 — this rejection's premise has been falsified by
   shipped code, not by argument.** ST-3b's `POST /sync/content/delete` sends
   the client's ref set to the relay on every reclamation: exactly the
   disclosure this decision rejected, live in production since
   `drive-upload-stop-and-reclamation.md` §4.9, and never amended here. The
   leak was accepted in practice without the decision being revisited. See
   [`content-plane-refcount.md`](../proposals/content-plane-refcount.md) §2/§4
   for what that costs and what it buys.
   ⚠⚠ **SUPERSEDED IN PART 2026-09-09.** Both conditions this note set have been met: the
   proposal has code (the relay's holder-keyed edge table, the declare/undeclare routes, the
   client half, plus CR-4b's `contentId -> refs` hop), and T-R-09 carries an amendment for
   each (HR #2). ⚠ *In part*: a relay-**derived** refcount remains HR#1-impossible and that
   half of this decision stands unchanged — what is superseded is only the rejection of a
   client-**declared** one. The retention mechanism below is still what actually frees
   bytes today; the successor is shipped but reads nothing yet
   (`content-plane-refcount.md` §9). Mechanism:
   every blob carries a `lastReferencedAt`, refreshed on every `PUT` **and**
   `GET` — a `GET` is the relay-visible proof a live manifest still points
   at this ref. A blob is collectible only after being untouched for a
   window ≥ the tombstone-body retention (default 30 days). This composes
   with the ordered-log tombstone GC: tombstone GC frees manifest bodies on
   the ordered plane; this frees chunk blobs on the content plane.
   Shared-blob-safe (dedup'd ciphertext stays alive while any live manifest
   keeps `GET`-ing it) and orphan-PUT-safe (an upload whose manifest never
   lands ages out by the same window). Bounded residual: a deleted file's
   blobs linger up to the content-retention window past the last `GET`.

7. **Access-pattern exposure is an accepted v1 residual.** With on-demand
   `GET by ref`, the relay observes *which* content refs a device fetches
   and *when* — so it can infer which files a user opens and their timing,
   even though content stays ciphertext. The all-drain model did not leak
   this. **v1 accepts the residual** and documents it in the substantive
   T-R-09 line. Batching / padding / decoy fetches are noted as the P4
   mitigation if the residual becomes unacceptable; oblivious fetch is
   **not** built for v1.

8. **Tombstones link the two GCs.** A file delete is a normal
   `DeleteBlock` against the manifest entity ([ADR 0006](0006-sync-tombstones.md)).
   The tombstone GC drops the manifest body on the ordered plane after
   quorum + retention; the manifest's referenced chunk blobs then age out
   via the content-plane retention sweep on this side. The two GCs are
   distinct and compose.

## Consequences

- **Peak heap is `O(CHUNK)` at every stage**, not Σ(file sizes). The OOM
  reproduces no longer reproduces; the desktop integration test asserts
  bounded peak memory for a multi-large-file upload.
  ⚠ **Amended: emit is `O(N × CHUNK)`, N = 4.** Chunk transfers now overlap
  ([`content-plane-transfer-concurrency.md`](../proposals/content-plane-transfer-concurrency.md))
  because a serial `PUT` loop against a single-region relay is round-trip-bound, not
  bandwidth-bound. This ADR's decision is intact — the load-bearing property was never the
  constant but independence from Σ(file sizes) and from backlog depth, and a bounded permit
  count preserves both. Set `chunkTransferConcurrency = 1` to restore the original path exactly.
- **Multi-TB realms work.** A fresh device syncs only manifests + metadata
  eagerly; bytes are pulled lazily per file actually viewed.
- **The relay gains a content store** + `PUT`/`GET /sync/content/{ref}`
  endpoint pair, JWT-required, 8 MiB per-blob cap, malformed-ref 400.
  Production endpoint surface lives in [`sync-relay-api.md`](../proposals/sync-relay-api.md) §3.7.
- **Two new GC sweeps** on the relay, distinct from each other and from
  ordered-log GC. Both nightly; both content-blob-safe.
- **Threat-model amendment T-R-09 covers two distinct lines.** The P3a
  sub-amendment scoped the endpoint surface (zero-trust unchanged); the
  P3b/c substantive line accepted the access-pattern residual.
- **Bandwidth shape changes.** A fresh device's catch-up is dominated by
  manifest blocks + the small ordered drain; byte traffic happens on
  demand. Profiling guidance: the byte plane has no transport-level cap
  beyond the relay's 8 MiB per-blob limit, so chunked uploads are bounded
  per-chunk rather than per-file.
- **Layer-3 deferred upload remains complementary.** Uploads should
  additionally run as Layer-3 deferred ops (`network = UNMETERED`) — see
  the bgproc track. Orthogonal scheduling, not a substitute.
- **The amplifier fix (deferring `SyncManagerImpl`'s legacy auto-load) is independent.** It removed the restart-storm,
  not the OOM disease this design closes.

## When to revisit

Reopen when any of these are true:

0. ⚠⚠ **FIRED 2026-09-08 — a shipped mechanism performs a disclosure this ADR
   rejected.** ST-3b's `/sync/content/delete` discloses the ref set Decision 6
   and alternative (iv) declined to disclose. A decision whose premise a later
   PR has quietly overridden is not a decision any more, and the retention/orphan
   approach it chose instead was measured on 2026-09-08 to leave a realm at 94 %
   of quota holding **11 files**. Tracked in
   [`content-plane-refcount.md`](../proposals/content-plane-refcount.md).
1. **The access-pattern residual becomes unacceptable.** Concrete trigger:
   a Console operator demonstrates per-user file-open inference at scale,
   or a regulatory line specifically prohibits the timing leak. P4
   (batching/padding/decoy) was sketched in §3 of the proposal; oblivious
   fetch is the larger structural answer.
2. **A non-file syncable type needs the same channel.** v1 scoped the
   design to file-content property blocks (the only multi-MB payload at
   the time). Generalising to a uniform "large-value via content plane"
   would change the manifest schema and the `OnDemand` resolver dispatch.
3. **The retention window assumption breaks.** Today the 30-day default
   composes the two GCs (content-plane window ≥ tombstone-body window).
   A per-volume per-delete override would change the GC algorithm — the
   composition guarantee needs re-checking.
4. **A motivated relay operator finds a House-Rule-#1 leak via the
   content store** that the existing T-R-09 line doesn't cover. The
   amendment is the canonical analysis surface; new findings extend it.

## Alternatives considered

- **(i) Self-describing chunks in the *ordered* block log** (the earlier
  lean before the two-plane revision). Rejected — does not solve the
  scale goal at all: a new device still drains every chunk of every file
  before it is consistent. This was the decisive argument for the
  two-plane split.
- **(ii) Single whole-file streamed ciphertext** (one AEAD over the
  whole file). Rejected — no bounded encrypt/decrypt (one AEAD needs the
  whole file fully resident), and no selective fetch on the read side.
- **(iii) Layer-3 deferred upload only.** Orthogonal scheduling (run
  when `network = UNMETERED`); does not fix the OOM nor enable
  selective fetch. Complementary, not a substitute.
- **(iv) Client "release refs" signal for GC.** Considered as the
  simplest way to drive content-plane GC. Rejected: it would disclose a
  deleted file's chunk-ref set + size to the relay, a new metadata leak
  strictly worse than the access-pattern residual the retention/orphan
  approach accepts.
  ⚠⚠ **REOPENED 2026-09-08.** The disclosure this rejects is what ST-3b's
  `/sync/content/delete` already performs. See Decision 6's note and
  [`content-plane-refcount.md`](../proposals/content-plane-refcount.md).
- **(v) Plaintext-derived content refs** (`md5(plaintext)`). Rejected —
  a confirmation-of-file / dedup oracle for the relay. Ciphertext-derived
  (or random) is the only safe form.
- **(vi) Eager content-plane GC** (drop blobs the instant the manifest
  tombstone clears the ordered-log retention window). Rejected — a
  shared blob (dedup'd ciphertext across manifests) would be dropped
  while another live manifest still references it. The `lastReferencedAt`
  refresh on `GET` is what makes shared-blob-safety hold.
- **(vii) Fold the content store into the existing
  `pushBlock`/`getChanges` ops.** Rejected during implementation — the
  blob shape is fundamentally different (immutable, opaque-keyed, no
  HLC ordering), and folding it would force `pushBlock` to grow a
  streaming `PUT` mode it doesn't need elsewhere. Dedicated
  `/sync/content/{ref}` endpoints kept the schemas precise.

## References

- [`docs/sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel) —
  protocol semantics + read path + GC composition.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — T-R-09 carries
  the access-pattern residual + the endpoint-surface sub-amendment;
  A-* entries unchanged.
- [`docs/proposals/sync-relay-api.md`](../proposals/sync-relay-api.md) §3.7 —
  `PUT`/`GET /sync/content/{ref}` endpoint pair, ref derivation, per-owner
  ACL, and GC interaction.
- `:sync-service-common/.../ContentBlobStore.kt` — client port.
- `:sync-service-proxy/.../RestContentBlobStore.kt` — streamed
  `PUT`/`GET` over Ktor; bridges the OnDemand resolver.
- `:sync-service-proxy/.../FileContentManifestApplier.kt` — the read
  path's apply step; replaces the P1 `SyncProcessor` manifest no-op on
  the suspend OnDemand path.
- `:sync-relay-server/.../ContentBlobStore.kt` (relay-side) — in-memory
  store today; Phase 4 of [`sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md)
  migrates to S3 alongside the rest of the relay state.
- `:file-service-proxy/.../UploadFileImplementation.kt` +
  `:sync-service-proxy/.../SyncObserver.kt` — emit-side chunking +
  manifest emission.
- `:file-service-proxy/.../FileBytesPropertyWriter.kt` — becomes a
  manifest writer; was the original seam doc.
- `:core-common/.../security/DefaultSyncCryptoManager.kt`,
  `AeadBackend` — per-chunk AEAD with bound AAD.
- [ADR 0006](0006-sync-tombstones.md) — `DeleteBlock(manifest)` is the
  trigger for the content-plane refcount GC; the two GCs compose.
- Tech-debt entry "Cross-Process Byte Payloads Use Content-Derived IDs" (content-ref = hash-of-ciphertext) — superseded by
  this design's ref-derivation rule.
- Resolves the open question in
  [`core-background-processing.md`](../proposals/core-background-processing.md) §7.1
  ("File content in the block log vs. a separate blob channel" → **separate
  channel**), and is the concrete realisation of its §4.4
  ("Block-size budget").
