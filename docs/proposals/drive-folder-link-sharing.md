# Drive folder link sharing — Mode A for trees ("publish a folder")

**Status:** v0.5 — design **SETTLED**, no code (2026-07-14). ⚠ **Sequencing (maintainer, 2026-07-14):
P1 is gated on [`file-content-addressing.md`](file-content-addressing.md) Phases 0–2** — see §8.0.
**The two gates are resolved by the maintainer:** Q1 → folder links are **LIVE** (D1, §3.5); Q2 → **yes, with content-blind levers**
(D8/D9; policy source of truth in
[`docs/policy/acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md)). Q3 (quota)
also resolved → D7 confirmed; Q4 → passphrase **off by default, opt-in**; Q5–Q8 → the proposed
defaults stand ad hoc, confirmed against reality when P1 is built. **Nothing remains open — the doc is
a settled design record awaiting implementation.**
Originally written in response to: *"should we support link-only, not shared-realm-based, sharing for
folders, the way Google and OneDrive do — for people with no account?"*

**Answer proposed here: yes, and it is mostly a manifest away.** Mode A (`docs/file-sharing.md`,
[ADR 0018](../adr/0018-file-sharing-mode-a.md)) already provides the whole machinery — capability URL,
per-share key, anonymous relay fetch, capability-rotation revocation. It is *file*-shaped only because a
share has no way to describe more than one file. Give it an encrypted **manifest** and the folder case
falls out.

This doc is a design record to argue with, not an implementation plan to execute. Both former gates are
settled: **live links** (Q1 → D1, §3.5) and the **abuse policy** (Q2 → D8/D9 + the policy doc). All
questions are settled (Q1–Q4 decided; Q5–Q8 default-accepted, to be confirmed against reality at P1).

---

## 1. The gap

Drive has two sharing modes and neither covers the most ordinary case.

**Mode B** (shared-realm membership; `drive-shared-realm-file-model.md`, `shared-realm-membership-grant.md`)
shares a folder *properly*: live, bidirectional, in-realm, revocable by key rotation. It requires the
recipient to **be a Tenshu user**, to have completed a contact handshake (QR/SAS or link), and to hold
realm keys. That is the right shape for "my partner and I share this folder indefinitely".

**Mode A** shares by link, to anybody with a browser and no account — but only **one file at a time**.

So "send my mother the 200 holiday photos" has no answer. Mode B demands she install Tenshu, create an
identity, and complete a handshake. Mode A demands 200 links. Google, OneDrive, Dropbox all solve this
with a folder link, and they keep it deliberately separate from "add people" for the same reason we
should: **link sharing is the on-ramp that does not require the other side to join your platform.**

The gap surfaced concretely as a bug: Drive offered *Link* on folders, the op tried to read the
folder's (non-existent) bytes, and the user got `Cannot share '/test': file not found`. The fix gated the
row to files — correct as a bug fix, and it makes the missing feature explicit rather than broken.

---

## 2. What already exists (and therefore what this is not)

From `docs/file-sharing.md`, all of this is **built and shipping**:

- `K_share` (32 random bytes) lives **in the URL fragment**, never reaches the relay.
- Bytes are **re-encrypted per share**, chunked, AEAD-sealed, AAD-bound to `(shareId, chunkIndex, chunkCount)`.
- The relay stores opaque ciphertext behind `POST /share`, `PUT /share/{id}/chunk/{i}`,
  `GET /share/{id}`, `GET /share/{id}/chunk/{i}` (the two `GET`s **anonymous** — knowing the URL *is* the
  capability), `DELETE /share/{id}`.
- A `Share` composite (`Syncable(Eager)`) converges across the owner's devices → *My shares* in Drive and
  Data → Shares in Control.
- Revocation = delete the record. Expiry + max-opens are enforced relay-side.
- Threat model: asset **A17**, threats **T-S-01…03**, §8.10.

This proposal adds **one new concept** — a manifest — and reuses everything above. It is not a new
sharing plane, not a relay redesign, and not a change to House Rule #1.

---

## 3. Proposed design

### 3.1 A share becomes a tree

One `K_share` still sits in the fragment. Everything else derives from it, so the URL does not grow with
the folder. **The publish `version` enters the key derivation and the AAD from day one** — this is the
one piece of the live design (§3.5) that cannot be retrofitted, because without it old and new chunks of
the same file share an AAD and the relay could splice *across versions* undetected:

```
K_manifest[v]  = HKDF(K_share, info = "tenshu/share/manifest/v1" ‖ LE64(version))
K_file[v, i]   = HKDF(K_share, info = "tenshu/share/file/v1" ‖ LE64(version) ‖ LE32(fileIndex))
```

- **Manifest** — an encrypted blob (sealed under `K_manifest[v]`) holding the tree: for each entry, its
  `fileIndex`, path *within the share* (not within the realm), name, size, MIME type, chunk count, and
  **content hash** (the realm's existing content-addressing hash — this is what makes the §3.5 diff
  cheap). Directories are entries with no chunks. Uploaded as one more blob, chunked exactly like content.
- **File chunks** — sealed under `K_file[v, i]`, AAD `(shareId, version, fileIndex, chunkIndex,
  chunkCount)`. `fileIndex` stops cross-file splicing within a share, `version` stops cross-version
  splicing within a file, `shareId` stops splicing across shares.
- **Rollback residual** — the relay *can* serve a stale-but-consistent older version wholesale (it holds
  the version pointer). It cannot forge or mix. Same class of residual as the relay withholding sync
  blocks; documented, accepted.

The single-file share is then just a degenerate tree (one entry), which is how the two eventually
converge (§6, D2).

### 3.2 Relay endpoints (additive, no shape change)

| Endpoint | Auth | Change |
|---|---|---|
| `POST /share` | JWT | Descriptor gains `kind = file \| tree`, `fileCount`, `totalSize`, `currentVersion` |
| `PUT /share/{id}/v/{v}/manifest/chunk/{i}` | JWT | **new** — upload the sealed manifest for version `v` |
| `PUT /share/{id}/v/{v}/file/{f}/chunk/{i}` | JWT | **new shape** — the chunk route, keyed by version + file |
| `POST /share/{id}/publish` | JWT | **new** — CAS flip: activate version `v+1` iff `currentVersion == v`; 409 otherwise (the losing device re-diffs) |
| `GET /share/{id}` | anonymous | Returns the (content-free) descriptor incl. `currentVersion` + that version's `manifestChunkCount` |
| `GET /share/{id}/v/{v}/manifest/chunk/{i}` | anonymous | **new** |
| `GET /share/{id}/v/{v}/file/{f}/chunk/{i}` | anonymous | **new shape** |
| `DELETE /share/{id}` | JWT | unchanged — still one record, one revoke, one GC sweep across all versions |

⚠ **Superseded in part (2026-10-27, `relay-metadata-minimisation.md` F3):** the single-file descriptor no
longer carries `fileName`, `mimeType` or `totalSize` in the clear — they live in an opaque `sealedMeta`
under `K_share` ([ADR 0018](../adr/0018-file-sharing-mode-a.md) Decision 9). A tree descriptor must follow
the same rule: `fileCount` and `totalSize` belong in the sealed manifest (or `sealedMeta`), not on the
relay record. The relay keeps only what it enforces (`kind` if routing needs it, `currentVersion`, chunk
counts, caps).

Revocation, expiry, max-opens, and GC stay **per share**, not per file: one link, one capability, one
kill switch. **Version GC:** superseded versions linger a grace window (so a viewer mid-download of v
doesn't 410 when v+1 flips), then sweep — same retention discipline as the content plane. The relay's
version pointer + CAS live in `ShareStore` (our code, in-memory v1) — small.

### 3.3 Schema deltas (`:file-service`)

`Share` gains `kind`, `fileCount`, `manifestChunkCount`, `totalSize`; `fileId` (a single path) becomes
`rootPath`. `ShareLink` takes a folder path and, when it is a folder, walks it. `RevokeShareLink` is
untouched. *My shares* / Data → Shares render "Folder · 213 files · 1.8 GB" instead of a file name — and
the inventory stays the single audit surface for "everything that left this realm".

### 3.4 Recipient viewer

The apex `/s/{shareId}` route becomes a small **tree browser**: fetch descriptor → fetch + decrypt
manifest → render the folder → per-file download, with the existing inline preview for image/PDF/text.

⚠ **Do not buffer a whole file in a tab.** The Service-Worker `Range` shim built for streaming preview
(`drive-streaming-preview.md` S6b) already decrypts chunk ranges in the window and answers `206`s; the
share viewer should reuse it rather than rediscover the blob-URL OOM ceiling on a 2 GB video. A
"download all" is deliberately **not** a server-side zip (the relay cannot see plaintext, House Rule #1)
— it is a client-side sequential fetch, or simply not offered in v1.

### 3.5 Living links — the publish loop (D1)

"Live" means **link-freshness, not push-to-open-tabs**: a viewer renders the manifest version it
fetched; a reload gets the current one. (This is also how Google/OneDrive behave.) So no push channel,
no long-poll — the whole liveness question reduces to: *something on an owner device republishes when
the folder changes.*

The loop, on whichever owner device is awake:

1. **Observe** changes under the share's root — the same path-prefix observation the offline keeper
   (`SyncRuleSet`) and Synced Folders already do; nothing new.
2. **Debounce** — a settle window (30 s–2 min, tunable) so an actively-edited file doesn't trigger a
   republish per save.
3. **Diff by content hash** — the manifest carries each entry's content hash, and realm files are
   already content-addressed, so "what changed" is a hash comparison, never a byte comparison.
4. **Republish only the delta** under version `v+1`:
   - **changed file** → full blob re-encrypt + upload under `K_file[v+1, i]`. (Whole-blob replacement,
     per the maintainer's framing. Chunk-level reuse is *not* attempted: the AAD binds `chunkCount`, so
     any size change re-keys every chunk anyway — not worth the complexity.)
   - **rename / move within the folder** → content hash unchanged → **manifest-only edit, no upload**.
     Moves are effectively free.
   - **delete (or move out of the folder)** → entry removed from the manifest; the blob goes with its
     version at GC. A recipient who already downloaded keeps the bytes — capability semantics, unchanged.
   - ⚠ **Unchanged files:** v1 re-uploads nothing for them, which requires unchanged entries to keep
     referencing their *original* version's blobs — so the manifest stores per-entry `(publishedVersion,
     fileIndex)` and the chunk key derivation uses the entry's `publishedVersion`, not the manifest's.
     Version GC then collects a version only when no current manifest entry references it (refcount in
     `ShareStore`). This is the price of not re-uploading a 2 GB folder because one text file changed.
5. **Flip** via the CAS `POST /share/{id}/publish`. Two awake devices racing: one wins, the loser gets
   409, re-diffs against the new current, usually finds nothing to do.
6. **Offline owner** → the link serves the last published version until a device wakes. This is the
   honest meaning of "live" in an E2E system with no server-side logic, and the UI copy is actually
   *easier* than the snapshot's: **"kept up to date while your devices are online."**

**What already exists vs. what is new** (the "how much more effort" answer):

| | |
|---|---|
| Exists | subtree change observation (offline keeper / Synced Folders); content-addressing for the diff; background scheduling substrate (PlatformScheduler/WorkManager; Control tray always-on on desktop); chunked AEAD upload; `Share` Eager-sync for cross-device publisher state |
| New | version in key derivation + AAD (**day-one wire, cheap now, breaking later**); relay version pointer + CAS + refcounted version GC (`ShareStore`, small); the publisher loop itself (**the one real new component** — shaped like the offline keeper, homed in Control); the root-moved/deleted product call (Q7) |

Net: live costs roughly **half again over the snapshot design** — nearly all of it the publisher loop
and relay versioning/GC. The staging in §8 makes the risky part (the wire) land first and the scheduler-
dependent part (auto-republish) land last.

**Edge case needing a product call (Q7):** the shared *root itself* is deleted or moved. Deleting it
should revoke the link (matches Google). Moving it is awkward because realm files are path-keyed — v1
likely marks the share stale and prompts ("this shared folder was moved — update the link or revoke?")
rather than silently following the move.

---

## 4. Decisions

**D1 — A folder link is *live* (maintainer call, 2026-07-14; supersedes the v0.1 snapshot posture).**
Users' mental model of a folder link — from Google, Dropbox, OneDrive — is that it reflects the folder;
a snapshot is the harder abstraction to explain, not the easier one. So live is the goal, with the
precise, keepable meaning §3.5 gives it: **the link reflects the folder as of the last time an owner
device was awake to republish** ("kept up to date while your devices are online"). Mechanics in §3.5.
Staging in §8: the version-aware wire ships first (it cannot be retrofitted), a manual *Update link now*
action next (no scheduler dependency, and a permanently useful affordance given freshness depends on
device wake), auto-republish last (rides the bgproc Phase 6 wiring already planned). The v0.1 rationale
("promise a snapshot, over-deliver later") is preserved *inside* this staging — every intermediate state
is honest — but the destination is now fixed.

**D2 — The tree lives in an encrypted manifest; the relay learns no names.** When this was written the
single-file ShareDescriptor handed the relay `fileName` and `mimeType` in **plaintext**; since 2026-10-27
(`relay-metadata-minimisation.md` F3, [ADR 0018](../adr/0018-file-sharing-mode-a.md) Decision 9) they
travel only inside an opaque `sealedMeta` under `K_share`. Putting a folder's names on the relay would hand
it your entire directory structure, so the tree is sealed the same way. The single-file case can later be
folded into the manifest shape for uniformity (§8 P4), but it no longer has a name leak to close.

**D3 — Per-share re-encryption, again. Do not hand out realm keys.** The tempting "efficient" design is to
give the link-holder a key that unlocks the realm's *existing* content-plane chunks. Three things break at
once:

1. **Revocation becomes realm re-keying.** Cutting off a link would mean rotating `Kr` and re-encrypting
   content — the expensive GR-7 dance, triggered by every casual link.
2. **Path leakage.** The content plane's AAD binds `fileId` = the file's *path in your realm*; the
   recipient needs the AAD to decrypt, so they learn your directory layout.
3. **Relay linkage.** The relay would have to map a public `shareId` onto realm objects, handing it exactly
   the correlation House Rule #1 exists to deny it.

Independent per-share ciphertext buys clean, independent, cheaply-revocable capabilities. The cost is
duplicated storage (§7), and it is worth it. (This is ADR 0018 Decision 2, and it survives the folder case
unchanged.)

**D4 — One `K_share` for the whole tree, subkeys by HKDF.** Not per-file keys in the URL (which would grow
without bound), and not one key reused verbatim across files (which would make the AAD the only thing
separating them). HKDF gives per-file key separation for free.

**D5 — Read-only in v1.** A "drop-box" link (recipients *upload* into the share; the owner's device
ingests) is a natural and valuable extension of the same primitive run backwards — Dropbox's *File
requests*, Google's upload links. It is deferred (§8 P3) but the manifest shape should not preclude it.

**D6 — Optional passphrase on a link.** Wrap `K_share` under an Argon2id-derived key so the URL alone is
insufficient; the recipient types a passphrase the sharer sends by another channel. This is what OneDrive
does, it is cheap here (`wrappedKeyBlob` already exists in the schema for exactly this kind of use), and it
is the only real answer to "email is not a secure channel". Phase, not v1 (§8 P2). **Default: off,
opt-in** (Q4 resolved — consistency with Google/Dropbox/OneDrive and ease of use).

**D7 — Shared bytes count against the owner's quota (CONFIRMED, maintainer, 2026-07-14).** They are a
second copy in relay storage. The entitlement/commerce plane already meters `ownerId` storage; a folder
share of 1.8 GB costs 1.8 GB, for as long as the share exists (revoke frees it after GC). Quota is the
natural, already-metered brake: casual users never notice the duplication; heavy legitimate use (a
photographer publishing large sets) is served by the tier that already serves heavy storage; publishing
a terabyte costs a terabyte. Goes into the ToS/acceptable-use text —
[`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) §1 is the source.

**D8 — Notice-and-action: takedown-by-`shareId`, never by decryption (maintainer, 2026-07-14).** The
relay knows `shareId → ownerId` (creation is JWT-authed), so on a report we can disable the record —
link dead globally, ciphertext GC'd, repeat offenders lose sharing then the account — while never
holding a key. EU framing (not legal advice, counsel before launch): DSA hosting posture — no general
monitoring obligation (Art. 8), a notice-and-action mechanism (Art. 16), liability shield while acting
expeditiously on knowledge (Art. 6). "We structurally cannot look" is coherent because we are not
obliged to look; we are obliged to act when told. ⚠ **The report form strips the URL fragment
client-side** — a reporter pastes the full link, but `#k=` never reaches us; we receive only the
`shareId` and act on notice without verification (the Mega model — the reporter, who holds the URL, is
the party who could see the content). The ToS acceptable-use section gives takedown + suspension
contractual grounding. Full process: policy doc §3.

**D9 — Published, tier-pooled transfer limits (maintainer, 2026-07-14).** A per-owner daily egress pool
across all shares, Dropbox-parity by tier (Free 20 GB/day · Basic 200 GB/day · Premium 1 TB/day), `429`
+ `Retry-After` + a distinct "link is busy" viewer state (not the 410 revoked state) — plus infra-level
per-IP limits on the anonymous endpoints (~60 descriptor GETs/min, ~4 concurrent chunk streams).
Numbers are knobs (policy doc §2 is the source); *publishing* them is the decision — Dropbox-style
transparency, not Google-style opacity. Comparables + the cost bound (~$10/TB egress ⇒ maxed free ≈
$6/mo, maxed Premium ≈ $300/mo) and the R2/CDN relief valve (immutable version-keyed chunks are
cacheable, traded against revocation latency) live in the policy doc.

---

## 5. Alternatives considered

- **Bundle N single-file links.** Trivially available today; useless in practice (no tree, no single
  revoke, N URLs).
- **Server-side zip.** The relay cannot see plaintext. Dead on arrival under House Rule #1.
- **Realm-key capability** (share the actual realm chunks) — rejected, D3.
- **Mode B with a "guest identity"** (mint a throwaway Tenshu identity for the recipient behind the
  scenes). Superficially elegant: one sharing model. But it drags the whole realm-key machinery, key
  rotation on revoke, and a device-key chain onto someone who just wants to click a link in an email — and
  it puts a *writer* into the realm's sync plane. The friction it removes is smaller than the surface it
  adds.

---

## 6. Advantages

- **Zero recipient onboarding** — the entire point. No app, no account, no handshake.
- **No new trust in the relay.** Ciphertext in, ciphertext out; House Rule #1 untouched.
- **Cheap revocation** (delete one record) versus Mode B's key rotation.
- **Reuses what exists**: chunking + AEAD discipline (ADR 0017), the `/share/*` endpoints, the `Share`
  inventory, expiry/max-opens, the streaming Service-Worker shim.
- **Composes with the existing audit story** — Control's Data → Shares stays the one place to see
  everything that left the realm.
- **Additive**: no wire break for existing single-file shares (D2's retrofit is separately phased).

---

## 7. Costs, risks, and the one that needs a policy decision

- **Storage duplication.** A shared folder is a second encrypted copy. Mitigations are product-level
  (quota, size caps) — or eventually the K3/`FCK` evolution already reserved in the schema
  (`file-sharing.md` §7), which lets a share wrap a per-file content key instead of duplicating bytes.
  That is the right long-term answer if share volume grows; it is not a v1 blocker.
- **The URL is the permission.** Forwarded links grant access. Mitigations: expiry, max-opens (both exist),
  passphrase (D6), and honest UI copy.
- **No recall.** Anyone who already downloaded keeps the bytes. Inherent to capability sharing; the
  existing copy already says this and should for folders too.
- **Freshness window** — live means "as of the last owner-device wake" (D1/§3.5); an owner whose
  devices are all offline serves a stale-but-consistent tree until one wakes. Honest UI copy required.
- **Publish churn** — every edit to a shared file re-encrypts and re-uploads that file's whole blob, and
  superseded versions linger through the GC grace window (transient storage amplification on hot
  folders). The debounce (Q8) is the lever.
- **Big trees.** Publishing 200 photos means encrypting and uploading 200 files. Needs progress UI,
  resumability, and a sane cap; it is a foreground job, not a silent one. (Later *updates* are cheap —
  §3.5 diffs by content hash and moves/renames are manifest-only.)
- ⚠ **Abuse, and our structural inability to moderate it — POLICY SETTLED (Q2 → D8/D9).** Anonymous,
  encrypted, relay-served bytes make Tenshu a **file host that cannot look inside what it hosts** — by
  design (House Rule #1), not by omission. The maintainer's call: operate it, with exclusively
  content-blind levers — quota-as-brake (D7), published tier-pooled transfer limits (D9), DSA-shaped
  notice-and-action with takedown-by-`shareId` (D8). The single source of truth for the user-facing
  rules is [`docs/policy/acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md), which
  feeds the ToS acceptance flow (commerce §5.1's versioned record), the share-creation copy, the viewer
  footer, and the report form. Counsel review of that text is required before launch — that is the
  remaining execution item, not an open design question.

---

## 8. Phasing

### 8.0 Sequencing gate — content addressing lands first (maintainer, 2026-07-14)

[`file-content-addressing.md`](file-content-addressing.md) (FCA) merged the same day, and its
Phases 0–2 precede P1 here. Three couplings, the first hard:

1. **FCA Phase 0 (the reset) wipes relay state — and a share URL is the one artifact we cannot
   regenerate.** `shareId` + `K_share` are out in someone's inbox; a post-reset re-upload mints new
   ones and silently kills every link ever sent. Shares must not exist before the reset does.
2. **§3.5's diff consumes FCA Phase 1's `contentHash`.** The publisher decides "what changed?" by
   hash compare without touching bytes; that needs the stamped, synced `File.contentHash` field
   (as-built, the hash is buried in a content-plane manifest whose routing/AAD Phase 1 changes anyway
   — building the differ pre-FCA means building it twice).
3. **FCA Phase 2 dissolves Q7 — but not by the mechanism written here.** ⚠ Corrected 2026-08-11:
   this said `Share.rootPath` binds to a stable `fileId`. **`File.fileId` was deleted** —
   the migration primitive obviated it, as its own KDoc predicted. Phase 2 became that primitive,
   and it still dissolves the root-moved case, by **migrating `Share.fileId` (the stored path) on a
   move** rather than by handing out an identifier that never changes. Move-root keeps working,
   delete-root revokes, and the stale-share prompt below is never built. See
   `file-content-addressing.md` §3.3.2 and its Phase-2 completion note.

FCA Phases 3–4 are orthogonal (FCA §3.4 explicitly keeps Mode-A's per-share re-encryption — D3 from
the other side; its Phase-3 CEK is `file-sharing.md` §7's reserved K3, wrapped per-realm for Mode B).

**Order: FCA 0 → 1 → 2, then P1 below.**

- **P1 — Folder links on the live wire.** The full version-aware wire (§3.1/§3.2: versioned keys + AAD,
  CAS publish, refcounted version GC) + manifest + tree viewer + `Share.kind` + a **manual *Update link
  now*** action (a re-publish on demand — the whole §3.5 loop minus the trigger). Read-only.
  Threat-model amendment lands with it (§10). The wire ships live-shaped from day one even though the
  trigger is manual, because the version-in-AAD design cannot be retrofitted (§3.1).
- **P2 — Auto-republish (the live trigger).** The §3.5 publisher loop: subtree observation + debounce +
  hash diff, homed in Control; Android rides the bgproc Phase 6 `BackgroundSyncScheduler` wiring
  (already planned, currently outstanding), desktop's tray is trivially always-on. Also: passphrase-
  wrapped `K_share` (D6).
- **P3 — Drop-box links** (recipient upload → owner ingest, D5).
- **P4 — Retrofit the single-file share** onto the encrypted manifest, for one shape across both kinds.
  ⚠ No longer a leak fix: F3 (2026-10-27) already sealed the single-file name, MIME and size in
  `sealedMeta`. The wire slot is reserved, so this is a code change, not a schema break.

Each phase ships independently. P1 answers the original question; P2 delivers D1's promise in full.

---

## 9. Open questions (maintainer calls)

- **Q1 — Snapshot or live? RESOLVED (maintainer, 2026-07-14): live.** Users expect Google/OneDrive
  semantics and the live abstraction is the easier one to understand. Consequence absorbed into the
  design: versioning is load-bearing from day one (§3.1), the publish loop is specified (§3.5), phasing
  restructured (§8). Sizing: live costs roughly half again over the snapshot design, nearly all of it
  the publisher loop + relay versioning/GC — the mechanisms it composes (subtree observation, content-
  hash diffing, background scheduling, chunked upload) all already exist.
- **Q2 — Abuse policy. RESOLVED (maintainer, 2026-07-14): yes, with content-blind levers.** Quota as
  the storage brake (D7), published tier-pooled transfer limits (D9), notice-and-action with
  takedown-by-`shareId` and a fragment-stripping report form (D8). Levers written down in
  [`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) so every surface (ToS,
  share-creation copy, viewer footer, report form) renders from one text. Residual execution item:
  counsel review before launch.
- **Q3 — Quota. RESOLVED (maintainer, 2026-07-14): D7 confirmed.** Shares deduct from the owner's
  bought storage; no separate share quota, no exemption. "Whatever the user's tier allows and a browser
  can practically serve."
- **Q4 — Passphrase default. RESOLVED (maintainer, 2026-07-14): off by default, opt-in.** Consistency
  with what users know from Google/Dropbox/OneDrive, and ease of use — the passphrase lives in the
  share-creation "advanced" section alongside expiry and max-opens (D6 unchanged otherwise).
- **Q5 — Size/count caps for v1.** A cap that is too low makes the feature useless; too high makes the
  abuse and storage questions urgent. Suggest a concrete number with P1.
- **Q6 — "Download all".** Client-side sequential fetch, or not offered in v1? (A server-side zip is not
  on the table — §3.4.)
- **Q7 — Root moved or deleted. DISSOLVED by the §8.0 sequencing:** with FCA Phase 2 landing
  before P1, a move **migrates the share's stored path** through the `DataIdMigrator` primitive —
  move-root keeps working, delete-root revokes (matches Google). The stale-share prompt from
  earlier drafts is never built. ⚠ Corrected 2026-08-11: this previously said the binding was to a
  stable `fileId`, which no longer exists — see §8.0 coupling 3.
- **Q8 — Debounce + version-GC grace defaults.** Proposed: 60 s settle window before a republish; 24 h
  grace before a superseded, unreferenced version's chunks sweep. Small knobs — defaults to confirm at
  P1, not to over-design.

---

## 10. Threat-model deltas (proposal-level; land per phase, House Rule #2)

Nothing here changes House Rule #1 or the relay's ciphertext-only posture, but the surface is new:

- **A36 (new) — Folder-share tree key + manifest.** `K_share` for a *tree*: one capability now unlocks
  many files and their structure. Custody identical to A17 (fragment-only, client-generated, never sent to
  the relay), blast radius larger — the amendment must say so plainly.
- **A17 (amend)** — extend to note that a share key may now root an HKDF subkey hierarchy.
- **T-S-04 (new) — Whole-tree disclosure on link leak.** T-S-01 bounded to one file; a folder link leaks
  the folder. Residual *High by design*, mitigated by expiry / max-opens / passphrase (D6) and by the UI
  making the scope unmistakable at creation time.
- **T-S-05 (new) — Relay as an unmoderatable host.** The abuse surface of §7. Defences are administrative
  (quota D7, transfer limits D9, notice-and-action + takedown-by-`shareId` D8), never content-inspecting.
  Policy settled (Q2); the amendment landing with P1 records the DSA notice-and-action posture and the
  fragment-stripping report intake (the capability key must never reach us, even in a report). Residual
  *Medium, accepted, policy-bounded*.
- **T-S-06 (new, P3 only) — Drop-box inbound untrusted content.** When P3 lands: anyone with the link can
  write bytes into relay storage billed to the owner, which an owner device then ingests. Needs its own
  caps + an explicit owner-side accept step.
- **T-S-07 (new) — Version rollback by the relay.** The relay holds the `currentVersion` pointer, so it
  can serve a stale-but-consistent older tree; it cannot forge or splice one (the version is inside the
  AEAD key derivation + AAD, §3.1). Same class as the relay withholding sync blocks. Residual *Low,
  accepted, documented*.

When P1 lands, [ADR 0018](../adr/0018-file-sharing-mode-a.md) gains an amendment (Mode A is no longer
file-only) and `docs/file-sharing.md` becomes the canonical surface for both shapes — per the docs
distillation pattern, this proposal is then deleted.

---

## 11. Implementation notes (for whoever builds P1)

| Area | Files |
|---|---|
| Schema + ops | `:file-service` `Share.kt` (+`kind`/`fileCount`/`manifestChunkCount`/`rootPath`), `ShareLink.kt` (folder walk), `RevokeShareLink.kt` (unchanged) |
| Sharer-side crypto + upload | `:file-service-proxy` `ShareLinkImplementation` — HKDF subkeys, manifest seal, per-file chunk upload; **must** stream (`O(CHUNK)` memory), reusing the content-plane chunker |
| Relay | `:sync-relay-server` `ShareStore.kt` + `ShareRouting.kt` — the routes in §3.2; GC sweep already generic over a share record |
| Recipient | `@tenshu/sdk/share` `ShareViewer` (manifest fetch + decrypt + per-file range reads), apex `web/apex/src/routes/share/[shareId].tsx` → tree browser; reuse the SW `Range` shim from `drive-streaming-preview.md` S6b |
| Sharer UX | Drive `FileActionsBottomSheet` — re-enable *Link* on folders (the Mode-A file-only fix gated it off); `CreateShareLinkSheet` gains the folder summary + advanced (expiry / max-opens / passphrase) |
| Inventory | `SharedScreen` *My shares* + Control Data → Shares — render tree shares ("Folder · N files · S") |

## Version

v0.5 · 2026-07-14 · **Sequencing locked (maintainer): FCA Phases 0–2 land before P1** (§8.0 — the
reset would kill sent share URLs; the publish-loop diff consumes `File.contentHash`; stable `fileId`s
dissolve Q7, which is now closed). Crypto core unchanged on both sides (FCA §3.4 ↔ D3 agree).

v0.4 · 2026-07-14 · **Q4 resolved (maintainer): passphrase off by default, opt-in** (consistency with
Google/Dropbox/OneDrive + ease of use; D6 default recorded). Q5–Q8 accepted as proposed, ad hoc —
confirm at P1. All questions settled; doc frozen as the design record for implementation.

v0.3 · 2026-07-14 · **Q2 + Q3 resolved (maintainer): abuse policy = yes, with content-blind levers;
quota = the owner's storage quota, confirmed.** New D8 (DSA-shaped notice-and-action;
takedown-by-`shareId`; ⚠ report form strips the `#k=` fragment client-side so the capability never
reaches us even in a report) + D9 (published, tier-pooled transfer limits — Dropbox parity: Free
20 GB/day · Basic 200 GB · Premium 1 TB — plus per-IP limits on anonymous endpoints). Single source of
truth for the user-facing rules created: `docs/policy/acceptable-use-and-limits.md` (feeds ToS
acceptance per commerce §5.1, share-creation copy, viewer footer, report form). Both former gates now
closed; remaining opens Q4–Q8 are defaults/edge semantics. Counsel review of the policy text = launch
prerequisite.

v0.2 · 2026-07-14 · **Q1 resolved: folder links are LIVE** (maintainer call). D1 rewritten; §3.5 added
(the publish loop: link-freshness not push, content-hash diff, moves are manifest-only, whole-blob
replace on change, CAS multi-device publish, refcounted version GC); version moved **into** the key
derivation + AAD (day-one wire — cross-version splice otherwise); phasing restructured (P1 = live wire +
manual *Update now*, P2 = auto-republish on bgproc Ph6); Q7 (root moved/deleted) + Q8 (debounce/grace
defaults) + T-S-07 (rollback residual) added. Sizing: live ≈ +50% over snapshot, concentrated in the
publisher loop + relay versioning.

v0.1 · 2026-07-13 · design record, no code. Written to answer "should we support account-less link
sharing for folders?" — proposed answer: yes, as a manifest over the existing Mode A. Decisions D1–D7
proposed, Q1–Q6 open (Q2, the abuse policy, is a hard gate).
