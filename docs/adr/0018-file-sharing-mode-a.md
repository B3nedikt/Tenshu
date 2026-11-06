# ADR 0018 — File sharing Mode A: anonymous link sharing via capability URL

- **Status:** Accepted, implemented (closed out 2026-05-31)
- **Date:** 2026-05-28 (proposal) → 2026-05-31 (Track C C2 + C3 + C4 land)
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

`FileActionsBottomSheet` had a *Link* row whose handler `onLinkClicked()` was
`TODO("Not yet implemented")`; `SharedScreen` was a navigation destination with
a hardcoded two-row stub; multi-select's *Share* card click went nowhere. The
user-visible promise of sharing was half-painted across the UI and entirely
unwired underneath.

Picking it up split into two qualitatively different shapes with a large cost
gap. **Named-recipient sharing** (Mode B) requires a Tenshu-identity primitive
(we have none — `IdentityService` knows only `Device`), a cross-realm relay
routing protocol that distinguishes intra-realm (today's `SyncMailbox`) from
cross-realm flow, and the recipient-side virtual-shared-folder concept. Sized
in months and blocked on the *Cross-realm content distribution* milestone.
Sketched in [`drive-shared-realms.md`](../proposals/drive-shared-realms.md);
out of scope here.

**Link sharing** (Mode A) needs none of those. The content plane
([ADR 0017](0017-file-content-two-plane-channel.md)) is already an opaque-
ciphertext blob store addressed by opaque refs, served via `PUT`/`GET
/sync/content/{ref}`. A share link is a capability over that store: fresh key,
fresh ciphertext, fresh ref, the URL fragment carrying the decryption key
client-side so the relay only ever sees ciphertext (House Rule #1 unchanged).
Sized in days–weeks.

Shipping Mode A first closes the half-painted promise with primitives we have,
surfaces the threat-model amendments the *named* track will also need
(capability-as-URL hygiene, anonymous fetch-trace acknowledgement), and
validates the Drive UI shape for "this file leaves the realm" before Mode B's
larger surface area lands on top of it.

## Decision

Anonymous, capability-URL link sharing. Per-share re-encryption under a fresh
`K_share`; sharer-side `Share` entity `Syncable(Eager)` so the realm converges
on the same *My shares* list; recipient is any browser at
`https://drive.tenshu.app/s/{shareId}#k={K_share}` with no Tenshu session.
Protocol + UX surface in [`docs/file-sharing.md`](../file-sharing.md).
Threat-model amendments in §8.10 + asset A17.

Eight anchor decisions shape the design:

1. **The URL fragment is the capability.** `https://drive.tenshu.app/s/{shareId}#k={K_share}`.
   Knowledge of the URL = read access; knowledge of only `shareId` (everything
   before the `#`) is insufficient. `K_share` is generated client-side at
   share creation and **never sent to the relay** — the URL fragment is
   client-side-only by the HTTP spec; the relay sees only `shareId`.

2. **Per-share re-encryption under a fresh `K_share`** (not "wrap the
   existing volume-key-encrypted chunks"). Two reasons. (a) The existing
   per-chunk AAD is `(fileId, chunkIndex, chunkCount)` where `fileId` is the
   file's *path* in the sharer's realm. Reusing those chunks either leaks
   the path to the recipient (it's needed to reconstruct the AAD) or
   requires changing the AAD scheme on the regular file plane — invasive.
   (b) Revoking a share by re-keying *the file* would invalidate every
   other share of the same file. Per-share independent chunks make
   revocation strictly per-share.
   Cost: per-share storage duplication. For typical use (≤ a few links per
   file, files < 100 MB) this is acceptable.

3. **Per-chunk AEAD with AAD `(shareId, chunkIndex, chunkCount)`.** Identical
   AAD discipline to the content plane (ADR 0017 Decision 2), with `shareId`
   substituted for `fileId`. Reorder / cross-share splice fail the AAD bind,
   truncation is caught by `chunkCount` + the relay's 410 on a missing
   chunk.

4. **Record-first relay → AEAD-only integrity in v1.** The relay's chunk
   `PUT` binds to an owner-scoped record, so `ShareLinkImplementation` does
   `POST /share` first, then the chunk `PUT`s — and that forces a v1
   simplification: the descriptor's per-chunk `chunkHashes` anchor ships
   **empty**, because a sharer-authored hash of a randomly-nonced ciphertext
   can't be known before the chunk is produced, and computing it would need
   either a chunks-first relay or buffering all ciphertext (breaking the
   `O(CHUNK)` bound). The **integrity boundary in v1 is the per-chunk AEAD
   under `K_share`**; the recipient's `ShareViewer.fetch` verifies via AEAD
   only and does not hash-chain-check. `chunkHashes` stays in the wire
   shape for the K3 evolution (a chunks-first relay or finalize endpoint
   would let the sharer populate it).

5. **`Share` is `Syncable(Eager)`, scoped to a `File` via the
   `entityRelation` shape `FileHistory` uses.** Every realm device sees the
   new share immediately on the next drain. Revoked rows are *kept* (with
   the `revoked` flag set) so the inventory can render a Revoked badge +
   history. `FileService.shares` is a realm-wide service relation maintained
   by a new `ShareRelationMaintainer` (mirrors `FileModificationRelationMaintainer`).

6. **Revocation is capability rotation.** `RevokeShareLink` flips the local
   `Share.revoked` flag (Eager-syncs realm-wide) **and** `DELETE
   /share/{shareId}` on the relay (JWT + owner-scoped). The relay sets
   `revokedAt = now()` and schedules chunk GC under the same retention/orphan
   discipline as the content plane (ADR 0017 Decision 6). Subsequent
   anonymous `GET` returns 410. A browser that already loaded bytes keeps
   them (capability semantics: there is no "un-give-them-this") — the UI
   copy makes this clear.

7. **Anonymous viewer = no Tenshu session.** The recipient flow runs at
   `https://drive.tenshu.app/s/{shareId}` and uses `:tenshu-sdk-js`'s
   `ShareViewer.fetch(shareId, K_share, relayUrl)` — a standalone surface
   with its own anonymous `HttpClient(Js)` + `DefaultSyncCryptoManager`, no
   session lookup. Exported via `@tenshu/sdk/share`, distinct from the
   authenticated `Shares` surface (`Shares.list()`/`revoke()` over the
   sharer's projection). The viewer is the only apex route that operates
   with no Tenshu session.

8. **Optional sharer-side caps: expiry + max opens, both relay-enforced.**
   `expiresAt: Instant?` and `maxOpens: Int?` on the relay record (the
   sync-side `Share` entity carries the same fields for visibility); the
   relay returns 410 after either condition fires. Both default off; both
   live behind a collapsible "advanced" section in the share-creation
   dialog. UI copy makes the strictness model explicit ("we use the relay
   clock; drift only shortens the window").

9. **The file's name, MIME type and exact size are sealed, not relay-visible**
   (as-built 2026-10-27, [`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md)
   F3 / D7 / D13 item 2). The original design put `fileName`, `mimeType` and
   `totalSize` on the relay record in the clear, on the reasoning that the
   recipient sees them anyway — but the recipient holds `K_share` and the
   relay does not, and a share without expiry kept its file name for ever.
   The descriptor now carries one opaque `sealedMeta` instead:
   - **Crypto.** `sealShareMeta` / `openShareMeta` in `:sync-service-common`
     (shared by the sharer and `ShareViewer`, like `shareChunkAad`):
     `SyncCryptoManager.encryptWithAad` (XChaCha20-Poly1305, fresh 24-byte
     random nonce) under **`K_share` itself** — no derived key — with
     associated data `tenshu/file-share-meta/v1|{shareId}|{chunkCount}`,
     domain-separated from the chunk AAD so a chunk can never open as metadata
     or the reverse. ⚠ Not native AEAD AAD: `encryptWithAad` length-frames the
     associated data into the plaintext, so it is authenticated *inside* the
     ciphertext and checked byte-for-byte after decryption.
   - **Plaintext.** `version:u8 | nameLen:i32 | name | mimeLen:i32 | mime |
     size:i64`, zero-padded to a 256-byte bucket (name length hidden within
     the bucket). Malformed or non-zero padding is refused.
   - **Integrity bonus.** The AAD binds `chunkCount`, so a relay that edits the
     served `chunkCount` breaks the open; the viewer also checks the decrypted
     byte count against the sealed size.
   - **Failure is visible.** A wrong key ⇒ `errorKind = "decrypt"`; an absent
     or malformed blob ⇒ `"meta"`. The viewer opens the metadata before
     fetching any chunk and never falls back to a blank or placeholder name.
   - **Relay.** Stores and serves the blob verbatim (≤ 16 KiB, required);
     keeps only `chunkCount`, expiry, `maxOpens`, the open count and
     revocation. `total_size` had no relay consumer (no quota, upload limit or
     `Content-Length` used it); the per-chunk 8 MiB cap is unchanged. Relay DB
     migration 3 drops the three clear columns and revokes any pre-F3 row
     (it carries no sealed meta and could never open), so revoke-GC reclaims it. What the relay can still
     infer: the exact size, from `chunkCount` × the 2 MiB stride plus the last
     chunk's length (F10, accepted).
   - **One format.** Pre-release, so there is no readers-first rollout and no
     clear-field fallback anywhere — F3's Phase 6 dual-format plan was
     unnecessary (D13 item 2). After release, a change to this blob is a new
     `/vN` AAD label plus a versioned link.

## Consequences

- **Mode-A link sharing ships independently of Mode B.** No Tenshu-identity
  primitive needed. The shared file leaves the realm under a fresh key, fresh
  ciphertext, fresh ref. House Rule #1 unchanged — the relay sees ciphertext
  only.
- **Storage cost is per-share-duplicated.** Each share is its own
  K_share-encrypted chunk set. The K3 alternative (per-file content key
  wrapped under K_share) is sketched in [`file-sharing.md`](../file-sharing.md)
  as a later optimisation if share counts get high — and the `wrappedKeyBlob`
  field is reserved in the schema today (a forward-compat slot, unused in v1).
- **Three new threats in `threat-model.md` §8.10**: **T-S-01** (share-link
  disclosure — by capability semantics; UI-level defence only; residual
  *High* but bounded to the one shared file), **T-S-02** (anonymous
  fetch-trace — relay sees IP/UA/timing/chunk-count via anonymous `GET`;
  rate-limit-by-IP administrative defence only; residual *accepted* as the
  capability model's design intent and an extension of T-R-09's per-open
  access-pattern residual), **T-S-03** (stale ciphertext after revocation —
  bounded by one retention window via the content-plane GC; residual *Low*).
- **One new asset A17** (Per-Share Key + Capability URL). The original draft
  named it A11; A11/A12/A13 were taken by the Rei work
  ([`tenshu-rei.md`](../proposals/tenshu-rei.md)) between draft and
  implementation. The threats kept their `T-S-0x` ids.
- **I1 confidentiality carve-out** in `threat-model.md` §3: data the user
  explicitly published via a share link is readable by any party holding the
  link, by capability. Carve-out scope is the specific shared file under the
  specific share, bounded by revocation (T-S-03 retention window).
- **Control inventory + revoke** under Data → Shares (`control-ia.md` §5.3 +
  Track C C2/C3/C4). Per-row Revoke confirms with an AlertDialog; the
  Revoked badge + empty state are pinned by `SharesScreenContentTest`.
- **Drive *Link* row** + multi-select *Share link* card wired into a single
  create-link sheet (`CreateShareLinkSheet` + `CreateShareLinkViewModel`).
  Drive's *My shares* tab is the per-realm projection; Control's
  inventory is the same data viewed from the Data section.
- **`FileService.shares`** becomes a realm-wide `serviceDataRelation`
  maintained by `ShareRelationMaintainer`. Newest-first; revoked rows
  retained for badge rendering.
- **JS SDK gains two distinct surfaces** that must not be confused:
  authenticated `Shares` (sharer projection: `Shares.list()`/`revoke()`) and
  standalone anonymous `ShareViewer.fetch(...)` (recipient).
- **No new module.** Wire surface lives in `:file-service` +
  `:sync-relay-server`. Control's Shares screen lives in `:control-shared`;
  the web pages live in `web/apex` + `web/control`.

## When to revisit

Reopen when any of these are true:

1. **Share counts make per-share storage duplication painful** (high
   fan-out: hundreds of links per popular file, or shares of multi-GB
   files). The K3 alternative — per-file content key (`FCK`) layered between
   the realm volume key and the chunk AEAD, with `wrappedKeyBlob = Enc(FCK,
   K_share)` carried in the descriptor — lets a share reuse the existing
   chunks. `wrappedKeyBlob` is reserved in the schema today as the
   forward-compat slot.
2. **A chunks-first relay or finalize endpoint** lands (whether for content
   plane or shared realms or a future Console feature). With that surface, the
   sharer can populate `chunkHashes` before / on chunk close, lifting the v1
   AEAD-only-integrity simplification (Decision 4) to a per-chunk hash anchor
   matching the content plane's whole-file anchor in spirit.
3. **The T-S-02 anonymous-fetch-trace residual becomes unacceptable.** A
   regulator forbids the per-open IP/timing leak; a deployment threat profile
   calls for unlinkable recipient access. The content plane's
   batching/padding/decoy mitigation (ADR 0017 *When to revisit* #1) sets
   the precedent; share-side oblivious fetch would be larger.
4. **Mode B lands** ([`drive-shared-realms.md`](../proposals/drive-shared-realms.md)).
   The boundaries set here (UI shape for "this file leaves the realm", the
   `Share` entity's place in `FileService`, the `FileHistory` cross-link for
   `SharingChangedFileModification`) become the shoulders Mode B stands on;
   re-check that Mode A's UI vocabulary still composes with named-recipient
   sharing rather than competing with it.

## Alternatives considered

- **(i) Mode B (named-recipient sharing) first.** Rejected for v1 — sized in
  months, gated on cross-realm relay routing + Tenshu-identity primitive.
  Closing the half-painted Mode A surface with the primitives we already
  have is strictly cheaper and surfaces the threat-model amendments Mode B
  also needs. Sibling sketch in [`drive-shared-realms.md`](../proposals/drive-shared-realms.md).
- **(ii) Wrap the existing volume-key-encrypted chunks instead of
  re-encrypting.** Rejected. (a) The existing AAD includes the file's path
  — leaking it to the recipient or changing the AAD scheme on the regular
  file plane is invasive. (b) Revoking by re-keying *the file* would
  invalidate every other share of the same file. Per-share independent
  chunks make revocation strictly per-share — the cost (per-share storage
  duplication) is acceptable for typical use; K3 is the future
  optimisation.
- **(iii) Authenticate the recipient (require Tenshu install).** Rejected —
  that's Mode B. The whole point of Mode A is "anyone with the URL can
  open in a browser, no install"; gating it would defeat the use case.
- **(iv) Chunks-first relay so the sharer can pre-commit a hash chain.**
  Considered for Decision 4. Rejected for v1 — would require a different
  relay endpoint shape (chunks before the record they belong to) or
  buffering all ciphertext to compute the hashes before `POST /share` (breaks
  `O(CHUNK)`). AEAD-only integrity is the safe v1 boundary; the *When to
  revisit* trigger #2 calls out the precondition to lift it.
- **(v) `K_share` carried as a URL query parameter** rather than fragment.
  Rejected — query parameters get sent to the server. The fragment is
  client-only by HTTP spec, which is what keeps the relay from ever seeing
  `K_share`. Apex JS reads `window.location.hash` and scrubs it from
  history (`history.replaceState`).
- **(vi) Sharer-side use cap enforced client-side.** Rejected — the relay
  is the source of truth for `opensSoFar`. A client-side cap can be
  bypassed by anyone who hasn't opened the URL yet; a relay-side cap is
  the only enforceable bound.
- **(vii) Browser preview via the apex's existing mammoth (docx) /
  Chart.js / etc. libraries.** Rejected — those would bloat the share
  viewer bundle. The viewer ships a download button + inline preview for
  image / PDF / plain text only; everything else falls back to download.
  Keeps the bundle tiny (the only apex route that operates with no Tenshu
  session).

## References

- [`docs/file-sharing.md`](../file-sharing.md) — protocol semantics
  (wire shape, URL shape, encryption, recipient flow, revocation, expiry/cap)
  + UX surface (sharer dialog, *My shares*, web viewer) + the K3 sketch.
- [`docs/sync/threat-model.md`](../sync/threat-model.md) §8.10 + asset A17
  + §3 I1 carve-out — T-S-01/02/03 + the asset entry.
- [`docs/proposals/control-ia.md`](../proposals/control-ia.md) §5.3 —
  Control Data → Shares subsection (inventory + revoke).
- `:file-service/.../file/share/{Share,ShareLink,RevokeShareLink}.kt` —
  schemas + ops.
- `:file-service-proxy/.../share/{ShareLinkImplementation,RevokeShareLinkImplementation}.kt` —
  op implementations.
- `:sync-relay-server/.../mailbox/ShareStore.kt` + `routing/ShareRouting.kt` —
  relay store (in-memory v1, ops/infra Phase 4 swap to S3) + JWT-authed
  routes (`POST /share`, `PUT /share/{id}/chunk/{i}`, `DELETE /share/{id}`,
  anonymous `GET /share/{id}` + chunk fetch).
- `:sync-service-common/ShareRelayClient.kt` + `:sync-service-proxy/RestShareRelayClient.kt` —
  client port + Ktor impl.
- `:tenshu-sdk-js/share/ShareViewer.kt` — anonymous recipient surface
  (`@tenshu/sdk/share`), distinct from the authenticated
  `Shares.list()`/`revoke()` surface.
- `web/apex/src/routes/share/[shareId].tsx` — viewer route.
- `control-shared/.../data/ShareManager.kt` + `:control-shared/.../ui/screens/shares/SharesScreen.kt` —
  Control inventory + revoke (Track C C2 + C3).
- `web/control/.../shares/SharesPage.tsx` — web Control inventory (Track C C4).
- [ADR 0017](0017-file-content-two-plane-channel.md) — the content plane
  Mode A reuses for chunk transport; share-side per-chunk AEAD discipline
  is the same shape with `shareId` in place of `fileId`.
- [`file-service-history.md`](../proposals/file-service-history.md) §3.1 —
  `SharingChangedFileModification` is enumerated as deferred until sharing
  lands; emit on `ShareLink` and `RevokeShareLink`.
- [`tenshu-rei.md`](../proposals/tenshu-rei.md) — took A11/A12/A13 between
  this draft and implementation; the per-share-key asset shipped as A17.
- [`drive-shared-realms.md`](../proposals/drive-shared-realms.md) — Mode B
  (named-recipient sharing) sketch; orthogonal track gated on cross-realm
  relay routing + a Tenshu-identity primitive.
