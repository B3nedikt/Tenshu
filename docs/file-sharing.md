# File sharing

This is the canonical API + UX surface for sharing a file out of Drive.
Design rationale + alternatives + threat-model amendments live in
[ADR 0018](adr/0018-file-sharing-mode-a.md). House Rule #1 unchanged
throughout — the relay sees ciphertext only.

Two modes are conceptually adjacent but architecturally different:

| | **Mode A — Link sharing** | **Mode B — Named-recipient sharing** |
|---|---|---|
| Recipient | Anyone with the URL | A specific Tenshu identity |
| Auth | Knowledge of URL = capability | Recipient signs requests with their device key chain |
| Discovery | Sharer copies + sends the link out of band | Recipient picker in Drive UI |
| Relay change | Small — `/share/*` endpoints over the existing content store | Substantial — cross-realm routing, entitlement records, identity discovery |
| Identity dependency | None | Tenshu-identity primitive (does not exist) |
| Permissions | Read-only | Read / comment / edit (write deferred — CRDT territory) |
| Revocation | Per-share, by capability rotation | Per-recipient, by ACL update + epoch-like rotation |
| Recipient surface | Browser viewer at `drive.tenshu.app/s/{shareId}` | Native Drive "Shared with me" virtual folder |

**Today only Mode A is built.** Mode B's sketch lives in
[`drive-shared-realms.md`](proposals/drive-shared-realms.md); the rest of
this doc covers Mode A.

⚠ **Mode A is file-only.** A link shares one file; folders go out via Mode B,
which requires the recipient to be a Tenshu user. The account-less *folder*
link (what Google/OneDrive call a shared folder link) is designed — as a
manifest over this same machinery — in
[`drive-folder-link-sharing.md`](proposals/drive-folder-link-sharing.md);
not built, and gated on an abuse-policy decision (that doc's Q2).

## 1. Wire-level shape

A new `Share` composite (`Syncable(Eager)`, scoped to a `File` via the same
`entityRelation` shape as `FileHistory`) records, on the sharer's realm,
the existence of a share link:

```kotlin
interface Share : Composite {
    val shareId: TString           // opaque base64url, 16 bytes
    val fileId: TString            // path in the sharer's realm
    val createdAtMillis: Instant
    val createdOnDeviceId: TString
    val expiresAtMillis: Instant?
    val maxOpens: Int?
    val opensSoFar: Int            // relay-authoritative; not synced past 0
    val revoked: TBoolean          // Eager-flipped on revoke
    val wrappedKeyBlob: TByteArray // unused in v1; reserved for K3 (§7)
}
```

`Share` is `Syncable(Eager)` so every realm device sees the new share on the
next drain. Revoked rows are **kept** (with `revoked = true`) so the inventory
can render a Revoked badge + history.

`FileService.shares` is a realm-wide `serviceDataRelation` maintained by
`ShareRelationMaintainer` (mirrors `FileModificationRelationMaintainer` and
`NotificationRelationMaintainer`). Newest-first ordering; revoked rows
retained.

Two ops in `:file-service`:

```kotlin
operation ShareLink {
    params {
        path: TString               // file to share
        expiresAtMillis: Instant?   // sharer-side soft option
        maxOpens: Int?              // sharer-side soft option
    }
    returns: ShareLinkResult { shareId: TString, urlFragment: TString }
}

operation RevokeShareLink {
    params { shareId: TString }
    returns: Unit
}
```

`ShareLink` runs the full re-encrypt + upload + record + save flow described
below; `RevokeShareLink` flips the Eager `revoked` flag locally and `DELETE`s
the relay record.

## 2. URL shape

```
https://drive.tenshu.app/s/{shareId}#k={K_share_base64url}
```

> **Deployed host (today).** The recipient viewer route (`/s/{shareId}`) is
> registered on the **apex** (`web/apex`, `tenshu.app`) — the only web surface
> deployed. `drive.tenshu.app` is the web roadmap's Phase 8 per-app subdomain and
> is **not deployed yet**, so until it ships the app mints links against the apex:
> `https://tenshu.app/s/{shareId}#k=…`. The host is a single constant
> (`ShareLinkUrl.DEFAULT_HOST`, `:drive-shared`); flip it back to
> `drive.tenshu.app` when Phase 8 lands.

- `shareId` — opaque base64url-encoded 16-byte random id.
- `K_share` — 32-byte random symmetric key, base64url-encoded **in the URL
  fragment**. The fragment is client-side-only by the HTTP spec; the relay
  sees only `shareId`.

Apex JS reads `window.location.hash`, parses out `K_share`, and scrubs the
hash from history (`history.replaceState`) so the key doesn't survive in the
browser's history stack.

## 3. Per-share encryption (v1: re-encrypt under fresh key)

Sharing re-encrypts the file under a fresh per-share key:

1. `ShareLink` generates a 32-byte random `K_share` via the platform CSPRNG.
2. **Materialises the file's bytes on the sharing device**, then reads them from
   local `FileDatabase`. `File.Data` is `Syncable(OnDemand)`, so an entity being
   local says nothing about its bytes: they are absent on any device that didn't
   upload the file and never opened it, and on any device that evicted them
   (un-pin, or a folder flipped to `SyncPolicy.ONLINE_ONLY` —
   [`drive-offline-online.md`](proposals/drive-offline-online.md)). `ShareLink`
   therefore drives `OnDemandResolution.resolve(File.Data)` first, exactly as
   `ReadSharedFile` and the offline keeper do; it is a no-op when the bytes are
   already on disk. Reading `FileDatabase` blind instead — the pre-2026-07-13
   behaviour — made every online-only file unshareable, reported as the
   misleading `NotFoundError` "file not found".
3. Splits into bounded chunks (same `CHUNK` size as the content plane —
   [`sync/design.md` §2.7](sync/design.md#27-file-content-two-plane-channel) — 1–4 MiB).
4. AEAD-encrypts each chunk independently under `K_share`, binding
   `(shareId, chunkIndex, chunkCount)` as AAD (identical AAD discipline to
   the content plane, with `shareId` substituted for `fileId`).
5. **Record first.** `POST /share` creates the relay-side ShareRecord with
   `sealedMeta` (file name, MIME and exact size, AEAD-sealed under `K_share`
   — ADR 0018 Decision 9; the relay never sees them in the clear), the chunk
   count, optional expiry/cap, and empty `chunkHashes` (Decision 4).
6. `PUT`s each ciphertext to `PUT /share/{shareId}/chunk/{chunkIndex}` —
   the relay's chunk route binds to the existing owner-scoped record.
7. Writes the local `Share` entity (Syncable Eager) so every realm device
   sees the new share immediately.
8. Returns the URL fragment for the sharer-side UI to assemble the full link.

`wrappedKeyBlob` is **unused in v1** — `K_share` lives entirely in the URL
fragment, the relay never sees it. The field is reserved for the K3
evolution (§7).

### Why re-encrypt vs. wrap the existing chunks

The existing per-chunk AAD is `(fileId, chunkIndex, chunkCount)` where
`fileId` is the file's *path* in the sharer's realm. Reusing those chunks
either leaks the path to the recipient or requires changing the AAD scheme
on the regular file plane — invasive. And revoking a share by re-keying the
file would invalidate every other share of the same file. Per-share
independent chunks make revocation strictly per-share. The cost is per-share
storage duplication; for typical use (≤ a few links per file, files <
100 MB) this is acceptable. §7 sketches K3 as the storage-efficient
alternative if share counts get high.

### Integrity boundary in v1 (AEAD only)

The descriptor's per-chunk `chunkHashes` ships **empty** in v1. A
sharer-authored hash of a randomly-nonced ciphertext can't be known before
the chunk is produced, and computing it would need either a chunks-first
relay or buffering all ciphertext (breaking `O(CHUNK)`). The v1 integrity
boundary is the per-chunk AEAD under `K_share`: reorder / cross-share splice
fail the AAD bind, truncation is caught by `chunkCount` + the relay's 410
on a missing chunk. The `chunkHashes` field stays in the wire shape for the
K3 evolution; the recipient's `ShareViewer.fetch` therefore verifies via
AEAD only and does not hash-chain-check in v1.

## 4. Recipient flow

1. Browser loads `https://drive.tenshu.app/s/{shareId}#k=...`.
2. Apex JS reads `window.location.hash`, parses out `K_share`, scrubs the
   hash from history.
3. `GET /share/{shareId}` returns the ShareDescriptor (`sealedMeta`,
   `chunkCount`, `chunkHashes`, `expiresAtMillis`, `opensRemaining`). Relay
   atomically bumps `opensSoFar` and refuses with **410 Gone** if revoked /
   expired / cap exhausted. The viewer opens `sealedMeta` with `K_share`
   (`openShareMeta`) to learn the file's name, MIME type and size; a wrong
   key fails as `decrypt`, an absent or malformed blob as `meta` — both
   visible errors, never a blank file name.
4. JS fetches chunks via `GET /share/{shareId}/chunk/{i}` for
   `i = 0..chunkCount-1`. Relay serves opaque ciphertext from its content
   store; the endpoint is **anonymous** (no JWT) — knowing the URL is the
   capability.
5. JS decrypts each chunk with `K_share` and the reconstructed AAD
   `(shareId, chunkIndex, chunkCount)`. Per-chunk AEAD failure = abort with
   "this link looks tampered".
6. UI: a download button + an inline preview for supported MIME types
   (image, PDF, plain text). Everything else falls back to download-only.
   ⚠⚠ The MIME type is the **sharer's** claim, so the preview is an
   allowlist (raster images, PDF, text shown as escaped text) and the blob
   of any other type is `application/octet-stream`: a `blob:` URL inherits
   the apex origin, so an HTML / SVG / XHTML / XML blob framed or opened in
   a tab would run script as the apex. `web/apex/src/routes/share/inlineSafety.ts`.
   The apex's `mammoth` (docx) / `Chart.js` / etc. libraries are *not*
   loaded for the share viewer — keeps the bundle tiny.

The viewer is the only apex route that operates with no Tenshu session.
Lives at `web/apex/src/routes/share/[shareId].tsx`.

## 5. Revocation

`RevokeShareLink` (sharer-side op):

1. Flips the local `Share` entity's `revoked = true` (Eager-syncs to every
   realm device, so the share disappears — or shows as Revoked — across
   *My shares* / Control inventory immediately).
2. Sends `DELETE /share/{shareId}` to the relay (JWT-authenticated, scoped
   to `ownerId`).
3. Relay sets `revokedAt = now()` and schedules chunk GC under the same
   retention/orphan policy as the content plane
   ([ADR 0017](adr/0017-file-content-two-plane-channel.md) Decision 6) —
   chunks linger up to the retention window past `revokedAt`, then sweep.
   Subsequent `GET /share/{shareId}` returns 410.

Revocation is **capability rotation**: the moment the share record is gone,
new opens fail. A browser that has already loaded the file's bytes into
memory keeps them (capability semantics: there is no "un-give-them-this").
The UI copy makes this clear — "revoking stops new opens; people who
already opened the link still have the file".

## 6. Expiry and use cap

Both optional, both enforced relay-side:

- `expiresAtMillis: Instant?` — relay returns 410 after this time. Wall-clock,
  not HLC-tracked (relay clock is fine for this; sharer trusts it only for
  strictness of the deadline, and any drift only shortens the effective
  window).
- `maxOpens: Int?` — incremented atomically on each successful descriptor
  `GET`; 410 once exhausted. Useful for "send this to one person, link
  burns after open."

Sharer-side UI defaults: no expiry, no use cap. Both surfaced behind a
collapsible "advanced" section in the share-creation dialog.

## 7. Why `wrappedKeyBlob` is in the schema today (K3 forward-compat)

A more storage-efficient design avoids re-encrypting on share by introducing
a per-file content key (`FCK`) layered between the realm volume key and the
chunk AEAD:

- Sharer encrypts the file's chunks once under `FCK`, stored on the realm
  side.
- On share, the sharer generates `K_share`, produces `wrappedKeyBlob =
  Enc(FCK, K_share)`, and includes it in the ShareDescriptor.
- Recipient `GET`s the descriptor, decrypts `FCK` with `K_share`, then
  decrypts each chunk with `FCK`.
- Revocation continues to be per-share: invalidating `wrappedKeyBlob` (by
  deleting the share record) cuts off new recipients, while existing
  recipients who already retrieved `FCK` retain access (capability
  semantics, same as today's v1).

The cost is a layered crypto scheme + an `FCK` rotation story for the
multi-share-same-file case. Not built in v1 because per-share re-encryption
is acceptable for typical share counts; the `wrappedKeyBlob` field reserves
the wire slot so the future migration is a code change without a schema
break. See ADR 0018 *When to revisit* trigger #1.

## 8. UX surface

### 8.1 Drive

**Mode A is file-only.** A link re-encrypts *the file's bytes*, so there is
nothing to share for a folder — a folder goes out via Mode B (*Share* /
*Manage access*, [`drive-manage-access-ux.md`](proposals/drive-manage-access-ux.md)).
The sheet hides *Link* on folders, and `ShareLink` rejects a folder target with
an `InvalidCallerError` for the callers the sheet doesn't cover (multi-select,
the JS SDK, web). Until 2026-07-13 the sheet offered *Link* on folders and the op
answered "Cannot share '/x': file not found" — which read as data loss rather
than "wrong kind of share", and is the bug that surfaced both this gate and the
OnDemand-materialisation gap in §3.2.

- **`FileActionsBottomSheet` *Link* row** — single-file share-link creation.
- **`MultiSelectBottomSheet` *Share link* card** — wired to the same
  `CreateShareLinkSheet` + `CreateShareLinkViewModel` as the per-file row.
- **`SharedScreen` → *My shares* tab** — projects `FileService.shares` for
  the active realm; per-row Copy ID / Revoke. Revoked rows stay in the
  list with a Revoked badge.

#### Handing the link out

The generated-link state offers **Share** *and* **Copy link**, both, on every
platform that has a share-out at all. They are different intents — handing the
link straight to a messenger vs. putting it on the clipboard for somewhere else
— so neither is a fallback for the other. The seam is
`drive-shared-ui/.../ui/common/ShareText.kt` (`ShareTextLauncher` +
`ShareTargetKind`), shared with the Mode-B invite-link sheet; iOS reimplements
it natively (`ShareLink`), as everywhere else in the Swift UI layer.

| Platform | Share opens | Notes |
|---|---|---|
| Android | `ACTION_SEND` + `createChooser` | Every messenger registers for `text/plain`; Direct Share surfaces individual chats. Deliberately no package targeting — it needs a `<queries>` entry, breaks when the app is absent, and offers less than the chooser. |
| iOS | `ShareLink` (iOS 16+) | Deployment target is 16.0, so no `UIActivityViewController` fallback. |
| Desktop | `mailto:` via `Desktop.Action.MAIL` | No desktop OS has a share sheet (macOS needs AppKit, Windows WinRT, Linux has no share portal). Button relabels to *Send by email*; absent when no mail client is registered. |

Two constraints, both load-bearing — the security reasoning is in
[threat-model](sync/threat-model.md) T-S-01:

- **Never route a share link through a web share-intent redirector**
  (`wa.me/?text=`, `t.me/share/url?url=`). The `#k=` fragment is withheld from
  servers only while it stays a fragment; as another site's query parameter it
  is an ordinary request field and `K_share` lands in that operator's logs.
  Every hand-off above is OS-local for exactly this reason.
- **Percent-encode the `mailto:` fields.** An unencoded `#` terminates the URI
  there, and the composer opens with the key silently truncated off the link —
  a share that looks fine and does not work. Pinned by `MailtoUriTest`.

Copying goes through `SensitiveClipboard`, which sets
`ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ so the system clipboard
toast stops previewing the key on screen. *My shares*' per-row Copy ID
deliberately uses the plain clipboard — it copies the key-free path URL.

### 8.2 Control

- **Data → Shares** (`control-ia.md` §5.3) — realm-wide outbound share
  inventory + revoke. Mirrors Drive's *My shares*, but viewed from the
  Data section so a user can audit "everything that left this realm" in
  one place. The same `FileService.shares` data; the same revoke op.
  Lives in `control-shared/.../ui/screens/shares/SharesScreen.kt` +
  `SharesViewModel` (native); `web/control/.../shares/SharesPage.tsx`
  (web).

### 8.3 Web viewer

The only apex route that operates with no Tenshu session. Standalone
recipient surface; downloads + inline preview for image / PDF / plain text;
download-only fallback for everything else.

## 9. JS SDK surfaces

Two distinct surfaces that must not be confused:

- **`@tenshu/sdk/share` `ShareViewer.fetch(shareId, kShare, relayUrl)`** —
  anonymous recipient surface, standalone (own `HttpClient(Js)` +
  `DefaultSyncCryptoManager`, no session). Used only by the apex `/s/...`
  route.
- **Sharer-side `Shares.list()` / `Shares.revoke(shareId)`** over the
  authenticated client — the same projection Control / Drive use. Lives in
  `:tenshu-sdk-js`'s `Shares` surface. Requires a Tenshu session.

## 10. Relay endpoints (summary)

Full HTTP semantics in [`sync-relay-api.md`](proposals/sync-relay-api.md):

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /share` | JWT (owner-scoped) | Create ShareRecord (record-first; the chunk routes bind to it) |
| `PUT /share/{shareId}/chunk/{i}` | JWT (owner-scoped) | Upload one AEAD chunk; idempotent per `(shareId, i)`; size-capped |
| `DELETE /share/{shareId}` | JWT (owner-scoped) | Revoke: set `revokedAt = now()`, schedule chunk GC |
| `GET /share/{shareId}` | **anonymous** | ShareDescriptor; atomically bump `opensSoFar`; 410 on revoked / expired / exhausted |
| `GET /share/{shareId}/chunk/{i}` | **anonymous** | Stream ciphertext chunk; 410 if record gone |

Relay-side store + routes:
`:sync-relay-server/.../mailbox/ShareStore.kt` (in-memory v1 — the
ops/infra Phase 4 of [`sync-backend-relay-plan.md`](proposals/sync-backend-relay-plan.md)
swaps in S3 alongside the rest of the relay state) +
`:sync-relay-server/.../routing/ShareRouting.kt`. A nightly `shareGcJob`
runs the retention/orphan sweep.

## 11. Threat-model summary

Full text in [`sync/threat-model.md`](sync/threat-model.md) §8.10 + asset
A17:

- **A17. Per-Share Key + Capability URL.** 32-byte `K_share`, embedded in
  the URL fragment; generated client-side at share creation, never sent to
  the relay, invalidated by `RevokeShareLink`.
- **T-S-01. Share-link disclosure.** Capability semantics: knowledge of
  URL = read access. UI-level defence only (one-time confirmation copy on
  share creation; revoke is one tap). Residual *High* by design, bounded
  to the one shared file.
- **T-S-02. Anonymous fetch-trace.** Relay sees IP/UA/timing/chunk-count
  via anonymous `GET`. Rate-limit-by-IP is the administrative defence.
  Residual *accepted, documented* — extension of T-R-09's per-open
  access-pattern residual.
- **T-S-03. Stale ciphertext after revocation.** Bounded by one
  retention window via the content-plane GC. Residual *Low*.
- **I1 confidentiality carve-out** (§3): data published via a share link
  is readable by capability, bounded by revocation.
