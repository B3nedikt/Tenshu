# Track C — Control UI for Shared-Realm File Sharing (+ Rei parts)

**Goal:** Give Control a surface to **see and manage what the realm shares** — outbound share
links (and, as shared-realms land, shared folders + their members) — with revoke and basic
metadata. Continue the `drive-file-sharing-p1` worktree's protocol work where it unblocks the
UI, and write the shared-realm parts of `tenshu-rei.md` + the threat model that the surface
depends on.

**Owning surfaces**
- Protocol/data: `:file-service*` `share/**` (continues the worktree), `:sync-relay-server`
  share routes (already drafted in worktree).
- Control UI: `control-shared/.../ui/screens/sharing/**` (rendered as a **Data → Shares**
  subsection — see decision below).
- Web: `web/apex/src/control/.../shares/**` (management) — distinct from the public recipient
  viewer `web/apex/src/routes/share/[shareId].tsx` (that's Drive's recipient surface, not
  Control).
- Docs (same PR): `tenshu-rei.md` §2.3 + §5.C, `docs/sync/threat-model.md` A12/A13 + T-SR-*.

**Primary references**
- Worktree: `~/StudioProjects/Drive/.claude/worktrees/drive-file-sharing-p1`
  — `Share` schema, `ShareLink`/`RevokeShareLink` ops, `ShareRelationMaintainer`,
  `FileService.SHARES` relation, relay `ShareStore`/`ShareRouting`, Drive
  `CreateShareLink*`, and `ShareLinkOperationTest`
  (`mintedShareIsVisibleOnRealmWideSharesProjection`).
- [`docs/file-sharing.md`](../file-sharing.md) (op specs + UX) +
  [ADR 0018](../adr/0018-file-sharing-mode-a.md) (rationale).
- `docs/proposals/tenshu-rei.md` §2.3 (shared realms as separate entities) + §5.C (Q22–Q32,
  decided 2026-05-29).
- `docs/sync/threat-model.md` §8.9 (A12 shared-realm master key, A13 member identity, T-SR-01..03).
- `docs/proposals/control-ia.md` §5 (Data) / §8 (open questions) — sharing is **not yet** in
  the IA; this track adds it.
- [`docs/sync/design.md`](../sync/design.md) §2.7 + [ADR 0017](../adr/0017-file-content-two-plane-channel.md) (two-plane content channel — shared files reuse the chunk/manifest + AEAD machinery).

---

## Current state (from exploration)

**Worktree `drive-file-sharing-p1` (Phase 1a landed, P1b pending):**
- `Share` composite (`Syncable(Eager)`): `shareId`, `fileId`, `createdAtMillis`,
  `createdOnDeviceId`, `expiresAtMillis` (0=none), `maxOpens` (0=none), `revoked`. `K_share`
  (32-byte key) is **deliberately not a field** — lives only in the URL fragment
  `https://drive.tenshu.app/s/{shareId}#k={K_share}` (House Rule #1).
- Ops: `ShareLink(path, expiresAtMillis, maxOpens) → ShareLinkResult(shareId, urlFragment)`;
  `RevokeShareLink(shareId)` flips `revoked` (Eager-syncs) + (P1b) deletes relay record.
- `ShareRelationMaintainer` puts minted shares on the realm-wide `FileService.SHARES`
  projection (proven by `mintedShareIsVisibleOnRealmWideSharesProjection`).
- Relay `ShareStore` + `ShareRouting` (`POST /share`, `PUT/GET /share/{id}/chunk/{i}`,
  `DELETE /share/{id}`, `GET /share/{id}`) — ciphertext-only, per-owner JWT, 8 MiB cap, 410
  on revoked/expired/exhausted.
- Drive UI: `CreateShareLinkUiState`/`CreateShareLinkViewModel` (mint + copy link).
- **P1b deferred:** per-chunk AEAD under `K_share`, relay HTTP client wiring in proxy factory,
  recipient viewer.

**Shared realms (`tenshu-rei.md` §2.3 / §5.C):** *decided, not implemented.* Single-owner
transferable realms; member list encrypted under the shared master key; wrap-per-member +
rotate-on-remove (no MLS in v1, ~150-member cap); writes signed under the member's own realm
identity (attribution + confusing-deputy protection). Threat-model assets A12/A13 + T-SR-01..03
exist in §8.9.

**Control IA:** has **no** sharing surface yet (§8 leaves it open).

---

## Decision to lock first: where does share management live?

Two options were surfaced (exploration): (1) a **Data → Shares** subsection in Control;
(2) entirely app-level in Drive (File → "Who has access?"). 

**This track builds (1) for the realm-wide *inventory + revoke* view** (Control answers "what
is my realm exposing, and let me shut any of it off") and **leaves per-file create/copy in
Drive** (already the worktree's `CreateShareLink*`). Rationale: Control's thesis is the
realm-level security/data dashboard; a single place to audit + revoke all outbound shares is
exactly that. Per-share *member management* for shared **folders** (when shared realms land)
also surfaces here, capped by the role model (§5.C Q29). Record this decision in `control-ia.md`
§5 as a new "Shares" data-inventory subsection.

---

## Phase C1 — Land P1b protocol bits the UI needs (continue the worktree)

The management UI needs revoke to actually take effect end-to-end and needs a readable
realm-wide projection. Prioritise:

1. **Relay HTTP client + JWT wiring** in the proxy factory (the deferred plumbing) so
   `RevokeShareLink` performs the `DELETE /share/{shareId}` *and* the local Eager flip.
2. **Per-chunk AEAD under `K_share`** + `POST /share` + `PUT /share/{id}/chunk/{i}` (reuse
   the two-plane chunk/manifest machinery + `SyncCryptoManager`; see ADR 0017) — needed for shares to be
   openable, and so revoke has something to revoke. (If sequencing demands, the Control
   *inventory + revoke* UI can land against the existing Eager `Share` projection before
   upload AEAD is done; revoke of an un-uploaded share is still meaningful.)
3. Confirm `FileService.SHARES` is a stable **realm-wide** read projection (it is, per the
   relation maintainer + passing test) — this is the list the UI binds to.

**C1 gate:** `:file-service-proxy:jvmTest` (existing share tests stay green + new revoke-hits-
relay test with a fake relay), `:sync-relay-server` route tests.

---

## Phase C2 — `ShareManager` data client (control-shared)

Mirror `PublisherClient`/`AppRegistry` shape.

```kotlin
// control-shared/.../data/ShareManager.kt
interface ShareManager {
    /** All active + revoked outbound shares for the realm, newest first. */
    fun shares(): Flow<List<ShareSummary>>
    suspend fun revoke(shareId: String)
    // Shared folders (gated on shared-realm impl; stub until then):
    fun sharedFolders(): Flow<List<SharedFolderSummary>>          // name, memberCount, ownerSelf?
    fun members(realmId: String): Flow<List<SharedRealmMember>>   // pseudonym, role, joinedAt
    suspend fun removeMember(realmId: String, memberId: String)   // -> rotate Kr (Q26)
}
data class ShareSummary(
    val shareId: String, val fileName: String, val createdAtMillis: Long,
    val expiresAtMillis: Long?, val maxOpens: Int?, val opensSoFar: Int?, val revoked: Boolean,
)
```

- Real impl binds `shares()` to the `FileService.SHARES` projection (map `Share` → summary;
  `fileName` resolved from `fileId`). `revoke()` calls the `RevokeShareLink` op.
- `sharedFolders()`/`members()`/`removeMember()` return empty / throw `NotYetAvailable` until
  the shared-realm protocol lands — keep the interface so the UI is forward-compatible.
- `MockShareManager` for screenshots/tests/empty-state. Register both in `ProxyModule`.

---

## Phase C3 — Control Sharing UI (Compose, control-shared)

Render as a **Data → Shares** subsection (the decision above), reusing the Data section's
card/list idiom.

1. **Destinations:** add `@Serializable data object Shares : Destination()` (reached from the
   Data screen as a card/row) and `@Serializable data class ShareDetail(val shareId: String)`.
   (If Track B's Data screen isn't merged yet, `Shares` can also be reached directly; keep the
   coupling to Track B loose — a single row that `backStack.add(Destination.Shares)`.)
2. **`SharesScreen` / `SharesScreenContent`**: newest-first list of `ShareSummary` — file
   name, created date, expiry/opens caps, opens-so-far, a **Revoked** badge. Per-row
   **Revoke** action (confirm; reuse `DisconnectConfirm` pattern). Empty state ("Nothing
   shared yet"). A "Shared folders" group appears only when `sharedFolders()` is non-empty.
3. **`ShareDetailScreen`** (optional v1): full metadata + revoke; for a shared folder, the
   **member list** (pseudonym, role, joined-at) with **Remove member** (which triggers Kr
   rotation per Q26 — surface the UX note "removal protects *future* content only").
4. **VMs:** `SharesViewModel(shareManager)` (list + revoke, optimistic),
   `ShareDetailViewModel(shareId, shareManager)`. Wire `viewModel { }`, `entry<>`, and the
   Data-row entry point.

**C3 gate:** `:control-shared:desktopTest` — `SharesScreenContentTest` (rows render, revoked
badge, revoke callback, empty state), member-list render test (with fake data).
`:control-desktop:compileKotlinJvm`. Android: unverified.

---

## Phase C4 — Web sharing management (web/apex)

- Add `/control/.../shares` management view under `<ControlShell />` (distinct from the public
  recipient viewer at `routes/share/[shareId].tsx`). Bind to an SDK `Shares.list()` surface
  over the `FileService.SHARES` projection (schema-walker pattern); `revoke()` calls the op.
- Keep the recipient viewer (`routes/share/[shareId].tsx`) as-is — it parses `shareId` +
  `#k=K_share`, fetches descriptor + chunks, AEAD-decrypts, verifies chunk hashes, renders.
  (That viewer is the P1b recipient deliverable; only touch it if C1's AEAD work changes its
  fetch/decrypt contract.)
- Tests: component test for the shares management list + revoke.

---

## `tenshu-rei.md` parts this track lands

In-PR (House Rule #2). §2.3 and §5.C are *decided* but this track makes them concrete and adds
the implementation/threat surface the UI relies on:

1. **§2.3 (Shared realms as separate entities)** — add an **implementation-status subsection**:
   record what landed for *link* sharing (the `Share` entity, `ShareLink`/`RevokeShareLink`,
   relay ciphertext store, `K_share`-in-fragment) and restate the shared-**folder** scheme it
   generalises to: shared realm has its own master key `Kr`; each member wraps `Kr` under their
   identity's X25519 sub-key (A12); writes are signed under the member's own realm identity
   (A13) for attribution + confusing-deputy protection. State that v1 management UI = inventory
   + revoke (links) and member-list + remove (folders, when the protocol lands).
2. **§5.C (Shared realms Q22–Q32)** — annotate the decisions the **Control surface** depends
   on, and where they show up in UI:
   - Q25 (encrypted, pseudonymous-by-default member list) → member rows show pseudonyms +
     optional identity-ref reveal.
   - Q26 (rotate `Kr` only on remove; no bulk-rekey) → "Remove member" UX note: protects
     future content only.
   - Q29 (grant follows app, capped by role) → member role is the cap shown next to each
     member; effective permission = role ∩ local grant.
   - Q22 (single-owner, transferable) → "owner" indicator; transfer is a later action.
   Mark Q22–Q32 as "consumed by Control sharing UI" with the screen names.
3. Add a `tenshu-rei.md` cross-link to [`docs/file-sharing.md`](../file-sharing.md) +
   [ADR 0018](../adr/0018-file-sharing-mode-a.md) for the op-level link-sharing spec (the
   concrete first instance of §2.3's private share channel).

**Threat-model (`docs/sync/threat-model.md`, same PR):** confirm/extend §8.9 — A12
(shared-realm master key), A13 (member identity), T-SR-01 (membership-change race), T-SR-02
(cross-member impersonation), T-SR-03 (owner recovery). Add the link-sharing residual already
noted in the worktree (per-open timing over opaque `shareId`/relay access pattern; oblivious
fetch not built in v1) and the Q30/Q32 metadata residuals (per-realm credentials minimise but
don't eliminate co-membership linkability; push-wakeup leaks activity timing).

---

## Definition of done
- Revoke works end-to-end (local Eager flip **and** relay `DELETE`), tests green.
- Control **Shares** subsection lists realm-wide outbound shares newest-first with metadata +
  per-row revoke + revoked badge; member-list scaffold renders for shared folders (empty/stub
  until shared-realm protocol lands).
- Web shares-management view binds to the SDK projection + revoke; public recipient viewer
  unaffected (or updated only if C1 changes its fetch/decrypt contract).
- `tenshu-rei.md` §2.3 (impl status) + §5.C (UI-consumed annotations) updated; threat-model
  §8.9 confirmed/extended with residuals — all in-PR.
- `control-ia.md` §5 gains the "Shares" subsection. Android wiring unverified.
