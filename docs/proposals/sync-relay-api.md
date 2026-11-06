# Tenshu Sync Relay — Future HTTP API Specification

**Status:** Phases 1, 2, 4, 6 landed (Phase 4 completed 2026-05-05 with the
recovery-blob endpoints, which F8 removed on 2026-10-28 (§3.4); the rest landed 2026-05-04 alongside the synced
device-management work — see [ADR 0007](../adr/0007-sync-device-management.md)).
Tombstone wire layer (§3.5) shipped 2026-05-04 as separate
`PushTombstone`/`GetTombstones` ops not folded into `PushBlock`/`GetChanges` —
typing-driven deviation captured in [ADR 0006](../adr/0006-sync-tombstones.md).
Realm-state metadata (§3.6 — `GET /sync/realm-epoch`) shipped 2026-05-08 to
close the recovery-race carve-out (threat-model T-K-02). Content plane (§3.7 —
`PUT`/`GET /sync/content/{ref}` + the unreferenced-blob retention GC) shipped
2026-05-19 as the two-plane design
([ADR 0017](../adr/0017-file-content-two-plane-channel.md);
protocol semantics in [`sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel)).
The threat-model T-R-09 amendment co-landed (House Rule #2). Phases 3 (long-poll
on pairing) and 5 (long-poll on `/sync/changes`) not started; both ship value
without blocking the rest.

> **Route reference:** the per-route end state (after CR-2 B3) is
> [`protocol/relay-api.openapi.yaml`](../protocol/relay-api.openapi.yaml). This doc keeps
> the design rationale; where the two disagree on a route's shape, the YAML is newer.

**Scope:** the wire-level HTTP API of `:sync-relay-server` after the synced
device-management work (devices stop being relay-side state — see
[`docs/sync/design.md`](../sync/design.md) §2.2 +
[ADR 0007](../adr/0007-sync-device-management.md)). Companion to
[`sync-backend-relay-plan.md`](sync-backend-relay-plan.md);
this doc supersedes that one's §3.1 endpoint table, §3.3 (Phase 3.3 — Authoritative Device
Registration), and parts of §4.2 once the device-management work is in.

---

## 1. Overview

After the device-management refactor, the relay does exactly four things:

1. **Block mailbox.** Receive and serve E2EE blocks. Bulk traffic; steady state.
   Two block kinds at the wire-type level:
    - **`SyncBlock`** — eager. Included in `/sync/changes` polls.
    - **`LazyBlock`** — ⚠⚠ **DESIGNED, NEVER BUILT.** No `LazyBlock` type exists in the
      codebase and there is no `GET /sync/block/{id}` route; the relay's only entity-block
      retrieval is `POST /sync/fetch-entity-blocks` (body: the entity's EI-1 wire id as text,
      since 2026-10-04 — `content-plane-cr2-b3-wire-spec.md` §11.3). Deferred loading of large properties is
      served by the content plane's `OnDemand` path instead (ADR 0017). Kept here because
      later sections still reference it. As designed: deferred, excluded from
      `/sync/changes`; only retrieved by id
      via `GET /sync/block/{id}` (see §3.1).
      The kind is part of the wire type, not an envelope flag — the relay routes by
      kind. This replaces the previous `/sync/fetch-property` design (which leaked
      client-side semantic structure into the protocol).
2. **Ephemeral pairing mailbox.** Carry the master-key handoff between two devices for a
   bounded window during pairing. Time-limited; small payloads.
3. **Realm bootstrap and device-key registration.** Learn the first device's master public
   key when a realm is created; learn each new device's signing-key public key + its
   master delegation when pairing completes or recovery proves possession of the master
   identity. Used to verify subsequent JWTs.
4. **Recovery-blob storage.** Hold an opaque ciphertext per owner so the user can recover
   on a fresh install.

Everything else — device names, authorization status, revocation records, "approve this
device" UX — lives inside synced blocks. The relay never reads block contents, never knows
what a device is named, and never decides who is authorised.

The wire format is binary (existing Tenshu binary protocol) for everything that's already
binary today; control-plane requests use small JSON envelopes carrying base64-encoded
fields where helpful.

---

## 2. Authentication

Per [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §4.1 (Phase 2.1). Unchanged
by this spec; the auth design carries through the device-management refactor cleanly.

- Each device holds an Ed25519 signing keypair (asset A4).
- Every authenticated request carries a JWT signed with the device's private key.
- Algorithm pinned to `EdDSA`.
- Claims: `iss = ownerId`, `sub = deviceId`, `iat`, `exp ≤ 5 minutes`, `nonce` for
  state-changing endpoints.
- Verifier looks up `(ownerId, deviceId) → publicKey` in the relay's known-devices store.
- State-changing endpoints reject `iss` ≠ block ownerId (where applicable) and re-used
  nonces within the `exp` window.

Two endpoints are deliberately **unauthenticated**:

- `POST /sync/realm` (realm bootstrap) — there is no realm yet, so no JWT-signing key the
  relay would recognise. Authentication is by the master-identity self-signature in the
  body.
- `POST /sync/recovery-claim` (recovery-flow device admission) — the recovering device is
  not yet in the realm. Authentication is by master-identity signature derived from the
  recovery code.

Both are explicit, time-windowed, and scoped — see §4.

---

## 3. Endpoints

### 3.0 Operational surface (unauthenticated)

Two routes carry no auth, because a monitor holds no device JWT. Neither reads or writes user data,
so House Rule #1 is untouched.

| Route | Answers | Notes |
|---|---|---|
| `GET /` | *Is the process up?* | A static string. ⚠⚠ **Cannot fail while the process lives** — it returned 200 for the whole 2026-08-14 outage while every `POST /sync/push` returned 500. Never use it as a health check. |
| `GET /health` | *Can this relay accept a write?* | Writes a few bytes to the blob volume and deletes them. `200 {"status":"ok","durable":true}` / `503 {"status":"degraded",…}`. |

`GET /health` answers `{"status":"ok","durable":false}` when the relay has no `DATABASE_URL` — there
is no durable volume to probe, and in production that flag is itself an alarm (registrations are
wiped on restart).

⚠⚠ **It deliberately does not probe the database.** `RelayDatabase` serialises every caller on one
lock over one SQLite connection, so a `SELECT 1` would queue behind in-flight sync work and report a
*busy* relay as *down*. A full volume fails the SQLite write anyway, so the disk probe covers both.
Rationale: [`drive-upload-stop-and-reclamation.md`](drive-upload-stop-and-reclamation.md) §4.7.

⚠ The body is fixed on purpose — no free-space figure, no path, no exception text. A prober learns
liveness and nothing it could not learn by attempting a real request.

### 3.1 Block sync

#### `POST /sync/push`

Push one or more `SyncBlock`s. The bulk endpoint of the relay.

- **Auth:** JWT required.
- **Request body:** binary, framed as a list of `SyncBlock`s. Each block is opaque
  ciphertext to the relay; only the metadata envelope is read (`ownerId`, `deviceId`,
  `hlc`, `signature`).
- **Server-side checks:**
    - `block.ownerId == JWT.iss` (1:1 ownership).
    - `block.deviceId == JWT.sub` (the device pushing is the one signing).
    - The block's signature verifies against the device's stored public key.
    - The block's HLC is monotonic w.r.t. the relay's last-seen HLC for that device. The
      HLC consistency check from
      [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §5.1 (Phase 3.1) hooks
      in here.
- **Response:** `200` with the relay's acknowledged HLC tip for the owner. `4xx` on
  ownership/signature mismatch; `5xx` reserved for relay-internal failures (storage
  unavailable, etc.) — clients retry on `5xx`, surface `4xx` as bugs.
- **Idempotency:** by block id (each `SyncBlock` carries a stable id under the deviceId +
  HLC). Re-pushing the same block is a no-op.

##### ⭐ As built from CR-2 B3c (2026-10-02) — relay protocol 4

The body is **one binary `EdgedPush`** ([`content-plane-cr2-b3-wire-spec.md`](content-plane-cr2-b3-wire-spec.md)
§3.1): `relayProtocolVersion`, the `SyncBlock`, the row's **complete** edge set (`PushedEdge` =
`propertyId`, optional `contentId` — absent is *none* — and `regenerable`), and `revive` (reserved for
B4, must be empty). The holder is `block.targetEntityIdHash`; the stamp is `block.hlc`.
- **One transaction:** the block is appended, then each edge is applied as an HLC-ordered register
  (rule 1). The block is logged even when every edge loses.
- **Responses:** `200 OK`; `400` (edges on a block with no target, a duplicate `propertyId`, a
  malformed id, more than 256 edges, a non-empty `revive`, or an unparseable body); `402` (entitlement);
  `403` (owner mismatch); ⚠ `426` outside `RELAY_PROTOCOL_SUPPORTED_RANGE`. (A bare pre-B3 `SyncBlock`
  got `426` too until the 2026-10 reset; it is now an ordinary unparseable `400`.) Reserved for B4:
  `409 content_freed`.
- ⚠ The list-of-blocks framing and per-block signature checks above were never built; one block per
  request is the shipped shape.

#### `POST /sync/push/probe` — would a push be accepted right now?

- **Auth:** JWT required — the same owner gate as `/sync/push`, then the same entitlement write-gate.
- **Request body:** none. **Response:** `204` when a push from this caller would pass both gates; `401`
  / `402` exactly as `/sync/push` would answer. Stateless; the relay learns nothing new (HR #1).
- ⚠⚠ **Exactly `/push`'s gates, in the same order** — the answer stands in for a write and must not drift
  from it. A successful *read* is not evidence about writes (a `402` refuses pushes while pulls succeed),
  which is why the client's move fence (`content-plane-refcount.md` §9.17) needs this route rather than
  any pull.
- ⚠ Additive: a relay without it answers `404`, which the client reads as "could not ask" and falls back
  to its cached verdict — never as a refusal. No `RELAY_PROTOCOL_VERSION` bump.

#### `POST /sync/log` — the merged lane (supersedes `/sync/changes` + `/sync/tombstones`)

One ordered page covering **both** blocks and tombstones, under **one** cursor
([`sync-lane-cursor-split.md`](sync-lane-cursor-split.md) L3). Same auth and signed-HLC-tip
contract as `/sync/changes`, and it returns the same peer tips — a client on this route needs
neither of the two it replaces.

- **Auth:** JWT required; the signed tip must self-attribute to the authenticated owner+device
  (shared preamble with `/sync/changes`).
- **Query/body:** `?since=<relay seq>`; body is the signed `SignedHlcTip`.
- **Response:** `SyncLogResponse` — `blocks`, `tombstones`, `peerTips`, `nextCursor`, `hasMore`.
- **Behaviour:** the relay returns the first `SYNC_CHANGES_PAGE_LIMIT` entries of the **union**
  above the cursor, in seq order, and `nextCursor` is that prefix's end. Because the page is a
  prefix of the merged order, nothing below `nextCursor` is unsent in either kind — which is what
  makes a single cursor sound. `hasMore` is stated, not inferred from the row count.
- ⚠ **Purely additive.** `/sync/changes` and `/sync/tombstones` are unchanged and still served, so
  this owes **no `RELAY_PROTOCOL_VERSION` bump** and no coordinated redeploy: an old client is
  unaffected by the route's existence, and a new client meeting an old relay reads the bare 404 as
  "unsupported" and falls back to the two lanes.
- ⚠ The relay assigns blocks and tombstones seqs from **one** shared counter, so this route does
  not introduce a merged order — it exposes the one that always existed.

#### `GET /sync/changes`

Pull blocks since a given HLC. Steady-state polling endpoint.

- **Auth:** JWT required.
- **Query/body:** `since: HLC` (the requester's last-known tip across the realm). Plus a
  signed HLC-tip piggyback per
  [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §5.1 — the request body
  carries the requester's signed view of the realm's tip; the response carries the
  relay's view of every realm device's tip.
- **Behaviour:** see §4 for polling-vs-push.
- **Response:** binary, framed list of `SyncBlock`s the requester hasn't seen, plus the
  signed-tips piggyback.

#### `GET /sync/block/{id}`

Lazy fetch for a single block by id. Replaces the legacy `POST /sync/fetch-property`.

- **Auth:** JWT required.
- **Path parameter:** `id` — the opaque block id (deviceId + HLC + content hash).
- **Behaviour:** the relay looks up any block (eager or lazy) by id and returns it.
  Used primarily for `LazyBlock`s that don't appear in `/sync/changes`, but works
  for any block — useful for re-fetch after a corrupted local copy.
- **Response:** the matching block, or `404` if no such block exists. The relay does
  not read the block contents.

The corresponding sync-service operation is renamed: `FetchOnDemandProperty` →
`FetchBlock`. The `(entityId, propertyId, params)` shape goes away — the operation
takes a single block id, derived client-side from a `LazyBlockReference` carried in
the entity's eager block.

#### Block batching

`POST /sync/push` accepts a single block per request. Single-block keeps partial-
failure semantics clean (one block, one outcome). Revisit if measured catch-up time
is slow on devices that have been offline for weeks; not a concern today.

#### Pagination on `GET /sync/changes`

The response carries up to a server-configured `pageSize` (default 1000 blocks).
When more blocks are available, the response includes a `nextCursor` opaque token;
clients re-issue `/sync/changes?since=hlc&cursor=<token>` to continue. Cursor
semantics: opaque to the client, server-side keyed by `(ownerId, deviceId,
last-served-hlc)`. Long-poll's `wait` parameter and pagination compose: the server
returns immediately if blocks are available (paginated), otherwise long-polls.

##### As built (2026-08-01) — SHIPPED, and the shape differs from the spec above

⚠⚠ **This section was designed and never implemented.** Until 2026-08-01 both
`getChanges` and `getTombstones` answered "every row with `seq > since`" in one
unbounded response. That is not a latent inefficiency but a **hard availability
ceiling**: a device whose cursor is 0 (fresh install, store wipe — i.e. every
`TENSHU_STORAGE_FORMAT_VERSION` bump arms it) asks for the whole history at once. On
2026-07-29 a 49 070-block / 244 MB history met a 242 MB relay heap; every pull
`OutOfMemoryError`'d, the client scored that as "relay unreachable" and left its cursor
at 0, and the retry loop pinned the relay's single core — degrading *every* owner. See
threat-model **T-R-12**.

The as-built shape deliberately diverges from the spec above:

- **No opaque cursor token, and no second cursor.** `nextCursor` already carries the
  arrival `seq` of the last row served, and that is exactly a resumption point. Adding a
  `cursor=` token beside `since=` would mean two cursors to keep consistent, plus
  server-side per-(owner, device) state — where today the relay holds none.
- **The page size is a shared compile-time constant** (`SYNC_CHANGES_PAGE_LIMIT` = 500 in
  `core-common/.../RelayProtocol.kt`), **not server-configured.** The client must know the
  limit to detect a truncated page, and that signal decides how far its cursor may
  advance. Sending it as a request parameter would put it on the `GetChanges` op's params
  — client-serialised positional composites — reshaping the wire and owing a format bump
  plus a lockstep fleet move (House Rule #9). A constant costs nothing on the wire and
  keeps the fix deployable to the relay alone. Raising it later is a normal lockstep
  change.
- **Both lanes share the cap, and the client bounds one by the other.** ⚠ Blocks and
  tombstones are numbered from **one shared `relay_seq` counter**, and the client advances
  `lastSyncTimestamp` to the `max` of the two lanes' cursors (an empty lane reports `since`
  verbatim, so `max` is what keeps progress possible). Truncation breaks that: if blocks
  stop at their page cap while tombstones run to the tip, `max` advances the cursor past
  undelivered blocks and **skips them permanently**. So a truncated lane contributes a
  *ceiling* — see `advancedCursor` in `SyncScheduler`, whose truth table is pinned by
  `AdvancedCursorTest`. Landing below the other lane's cursor only re-fetches the overlap,
  which is free: both `processSyncBlock` and `processDeleteBlock` are idempotent.
- **Catch-up does not wait out the poll interval.** A truncated page wakes the scheduler
  immediately, so a large backlog drains in ticks rather than in poll intervals.
- **Compatibility:** relay-only deploys are safe for clients whose backlog fits in one
  page. A pre-fix client with a *larger* backlog can skip blocks via the `max` rule above,
  so relay and clients move together — the project's existing lockstep posture. No
  `RELAY_PROTOCOL_VERSION` bump: routes, envelope, auth and blob-ref format are unchanged
  (and the 426 gate is genesis-only anyway, `core-schema-evolution.md` §7.11).

Long-poll (§4.2) is still unbuilt and still composes: return immediately when a page is
available, otherwise wait.

---

### 3.2 Realm bootstrap

#### `POST /sync/realm`

Create a new realm. Called by the first device, once, when the user first sets up sync.

- **Auth:** none. Authentication is by the master signature in the body.
- **Request body** (JSON for clarity; could be binary):
  ```
  {
    "ownerId": <opaque string, derived from master public key by client>,
    "masterPublicKey": <ed25519 32 bytes, base64>,
    "firstDevicePublicKey": <ed25519 32 bytes, base64>,
    "firstDeviceId": <opaque string>,
    "masterSelfSignature": <signature over realmBootstrapMessage = LP("tenshu-realm-bootstrap-v1") ‖ LP(ownerId) ‖ LP(masterPublicKey) ‖ LP(firstDevicePublicKey) ‖ LP(firstDeviceId), base64>,
    "createdAt": <unix millis>
  }
  ```
  `LP(x) = u32be(|x|) ‖ x`; every signed message on this API starts with LP(its own tag) and length-prefixes every field — one builder each in `SignedMessageEncoding.kt` (`admission-revocation-names-the-capability.md` §7d.3). Every route that registers a device requires 32-byte keys and a `deviceId` of 1–256 characters from `[A-Za-z0-9_-]` — and, since workstream B (2026-10-29), **exactly `deviceIdForPublicKey` of the key it is registered with** (§5.3), else `400 device-id-not-key-derived: …`.
- **Server-side checks:**
    - `ownerId` matches the deterministic derivation of `masterPublicKey` (the client and
      server agree on `ownerId = base32(blake2b(masterPublicKey))` or similar — pin this in
      §6).
    - `masterSelfSignature` verifies against `masterPublicKey`.
    - No existing record for `ownerId`. If one exists, **409**. (TOFU on the master public
      key for that ownerId. The doc-set's residual risk on this is in §5.5.)
- **Side effects:**
    - Stores `(ownerId → masterPublicKey)` permanently.
    - Stores `(ownerId, firstDeviceId) → firstDevicePublicKey` as the first known device.
    - Allocates the block mailbox.
- **Response:** `201` with `{ "ownerId": ..., "createdAt": ... }`.
- **Idempotency:** by `ownerId`. Repeated calls return the same response if and only if
  the master signature matches; otherwise `409`.

After this, all subsequent calls from the first device use a JWT signed with
`firstDevicePublicKey`.

#### `POST /sync/admit-device`

Admit an **additional** device to an existing realm (SR-3b.4b,
`sync-multi-realm-runtime.md` §3.1). Used for **shared realms**: the realm's owner admits
another of the user's devices so its JWT verifies on the shared `ownerId` stream. Unlike
pairing, there is no key handover here — the admitted device already holds the realm's keys
(A12 via its synced `SharedRealmMembership`, A13 via personal-realm pairing); only the
relay-side device registration is missing. Decoupled from the pairing handshake (no
pairingSecret / encrypted-key mailbox).

- **Auth:** none. Authentication is by the master delegation in the body (mirrors
  `/sync/realm` + `/sync/recovery-claim`).
- **Request body:**
  ```
  {
    "ownerId": <opaque string of the realm being joined>,
    "newDeviceId": <opaque string>,
    "newDevicePublicKey": <ed25519 32 bytes, base64>,
    "masterDelegation": <signature over pairingDelegationMessage = LP("tenshu-admit-delegation-v1") ‖ LP(newDevicePublicKey) ‖ LP(newDeviceId) ‖ LP(epoch),
                         signed by the realm's master identity, base64>,
    "epoch": <int>
  }
  ```
- **Server-side checks:**
    - A realm exists for `ownerId` (else **404**).
    - `masterDelegation` verifies against the realm's stored `masterPublicKey` — the same
      message shape and verifier as `POST /sync/pairing/response` (`MasterDelegationVerifier
      .pairingResponseMessage`). Else **401**.
- **Side effects:** stores `(ownerId, newDeviceId) → newDevicePublicKey` in the known-devices
  store, so the admitted device's JWTs verify.
- **Response:** `200`. Idempotent — re-admitting the same device re-registers the same key.

---

### 3.3 Pairing

The pairing handshake delivers:

- The **encrypted master key** to the new device (the body of the message).
- The **new device's public key + master delegation** to the relay (so the relay can
  authenticate the new device's later JWTs).

There is no separate "register device" call. The pairing-response endpoint carries both
payloads. ⚠ *Since 2026-10-28* the relay verifies the delegation on the post but **applies** the
registration when the joiner first peeks the response (before serving it), so a response that
expires unfetched leaves no registered device behind (`PairingResponseInbox`).

#### `POST /sync/pairing/request`

The new device posts its handshake parameters.

- **Auth:** none. The pairing secret is the capability.
- **Request body:**
  ```
  {
    "ownerId": <derived as above>,
    "pairingSecret": <opaque, ≥128-bit; from the QR>,
    "newDevicePublicKey": <ed25519 32 bytes, base64>,
    "newDeviceId": <opaque string>,
    "ephemeralPublicKey": <x25519 32 bytes, base64; for the encrypted handoff>
  }
  ```
- **Server-side checks:**
    - `ownerId` exists.
    - No existing pairing-request entry for `pairingSecret` (single-use).
    - `pairingSecret` matches the format pinned in
      [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 7.
- **Side effects:** stores `(pairingSecret → request)` with a 5-minute TTL.
- **Response:** `202 Accepted`, no body.

> ⚠ **As built (2026-10-13), where it differs from the above.** The body's field names are
> `deviceId`, `deviceName`, `publicKey` (X25519) and `signingPublicKey` (Ed25519); success is
> `200 OK`. Requests are stored per **owner** (at most 32, oldest evicted), served and kept for
> **7 minutes**. Refusals: `400` for a key that is not 32 bytes or a stored string over 256
> characters, `404` when the owner has no registered device (the "`ownerId` exists" check — a
> request no host could poll for), `429` past the per-IP budget
> (`PAIRING_REQUEST_RATE_LIMIT_PER_HOUR`). ⚠ The single-use-per-`pairingSecret` check is **not**
> built. Threat model T-P-13 and T-P-15.
>
> ⚠⚠ **As built (F7, 2026-10-28; threat model T-P-16) — the relay is never sent the secret or the
> name.** The body is `{ownerId, pairingSecretHash, deviceId, sealedDeviceName, publicKey,
> signingPublicKey}`: `pairingSecretHash` is
> `hex(SHA-256("tenshu/pairing/join/rendezvous/v1" || 0x00 || utf8(secret)))` (64 lower-case hex,
> else `400`) and `sealedDeviceName` is base64 AEAD ciphertext under a key HKDF-derived from the
> secret (1..1024 decoded bytes, else `400`) — construction in
> [`pairing-flow.md`](../sync/pairing-flow.md) Step 2. A body carrying `pairingSecret` or
> `deviceName` is refused with `400`. The 256-character cap now applies to `ownerId` and `deviceId`.
> Changed in place before release: no `RELAY_PROTOCOL_VERSION` bump.

#### `GET /sync/pairing/requests`

The host device polls for pending requests.

- **Auth:** JWT required (the host is an admitted realm device).
- **Behaviour:** see §4 (long-poll recommended).
- **Response:** binary list of pending pairing requests for the host's ownerId. Each
  request carries the new device's `newDevicePublicKey`, `newDeviceId`, and
  `ephemeralPublicKey`. The list is non-destructive — multiple host devices can poll;
  responses are removed only when a paired device completes (see below).

> ⚠ **As built (F7, 2026-10-28).** A JSON array of
> `{deviceId, sealedDeviceName, publicKey, signingPublicKey, pairingSecretHash}` — base64 for the
> byte fields. It is no longer the `PairingRequest` composite: that type carries the **clear** name
> and is now produced only by the host proxy after it filters by its session's hash and unseals
> (`GetPendingPairingRequestsImplementation`). The `pairingSecretHash` slot carries a hash (openapi
> known issue 4 — the raw secret in that slot — is closed).

#### `POST /sync/pairing/response`

The host posts the encrypted master key + the new device's master delegation.

- **Auth:** JWT required.
- **Request body:**
  ```
  {
    "pairingSecret": <matches the request>,
    "encryptedMasterKey": <opaque ciphertext, decryptable only by the new device>,
    "newDevicePublicKey": <ed25519 32 bytes; same as in the request>,
    "newDeviceId": <opaque string; same as in the request>,
    "masterDelegation": <signature over pairingDelegationMessage (as above),
                         signed by master identity, base64>,
    "epoch": <int>
  }
  ```
- **Server-side checks:**
    - `pairingSecret` matches a non-expired pairing-request.
    - `newDevicePublicKey` and `newDeviceId` match the request (the host can't be confused
      into delegating to a different key than the one the relay's request entry said).
    - `masterDelegation` verifies against the realm's stored `masterPublicKey` for
      `ownerId` (the host can't admit a device under a different realm).
- **Side effects:**
    - Stores `(ownerId, newDeviceId) → newDevicePublicKey` permanently. From now on, JWTs
      signed by the new device's key are accepted.
    - Stores `pairingResponse[pairingSecret] = encryptedMasterKey` with a 5-minute TTL.
- **Response:** `200 OK`.

> ⚠ **As built (F7, 2026-10-28).** The body carries `pairingSecretHash` (the request's hash; 64
> lower-case hex, else `400`), not `pairingSecret`. The response is kept and served for **7
> minutes** (`PAIRING_REQUEST_TTL_MS`, equal to the offer flow's `PAIRING_OFFER_RESPONSE_TTL_MS`). A
> hash whose response was already acked is **retired for 24 hours** and answers `409` — checked
> before the device is registered, so a refused post has no side effect. The removed-device fence
> (`wasRemoved` → `403 device_removed`) and the delegation checks are unchanged.

The relay learns the new device's public key only via the master delegation in this body,
which it cryptographically validates. No trust-on-first-use; no separate "register device"
endpoint; no relay-side authorisation logic.

#### `GET /sync/pairing/response` — ⚠ replaced by `POST /sync/pairing/response/peek`

> ⚠⚠ **As built (F7, 2026-10-28): this route no longer exists.** It carried the raw secret in a
> query string, which reached access logs (F4). The poll is `POST /sync/pairing/response/peek` with
> body `{"pairingSecretHash": …}`: `200` + the opaque ciphertext bytes on hit, `404` while pending
> or after the 7-minute TTL, `400` for a key that is not 64 lower-case hex. Still non-destructive.
> The key is never put in a URI — not even as a path segment, which route-template logging would
> record. The text below is the original design.

The new device polls for the encrypted master key.

- **Auth:** none. The pairing secret is the capability. The relay enforces the 5-minute
  TTL.
- **Query:** `pairingSecret`.
- **Behaviour:** see §4 (long-poll recommended).
- **Response on hit:** `200` with `{ "encryptedMasterKey": ... }`.
- **Response on miss:** `204 No Content` (still waiting) or `410 Gone` (TTL expired).
- **Read semantics:** non-destructive. The new device is responsible for ack'ing
  consumption (next request).

#### `POST /sync/pairing/ack`

The new device acknowledges it has consumed the response.

- **Auth:** none. The pairing secret is the capability.
- **Body:** `{ "pairingSecret": ... }`.
- **Side effects:** delete `pairingResponse[pairingSecret]` and the corresponding
  `pairingRequest`. Frees the relay-side ephemeral state.
- **Response:** `200`.

> ⚠ **As built (F7, 2026-10-28).** Body `{"pairingSecretHash": …}` (64 lower-case hex, else `400`).
> Deletes the response and, when one was live, leaves a 24-hour consumed marker on the hash (an ack
> for a hash with no response retires nothing). The request was already removed when the response
> was posted.

This separates fetch from delete, fixing the "requester device crashes between fetch and
processing the response" footgun called out in
[`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §4.2. If the new device crashes
before ack'ing, it can re-fetch and re-process; the TTL eventually cleans up regardless.

After ack, the new device pushes its first `SyncBlock` — typically a
`Device { isAuthorized = true }` entity per
[`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) Step 5 — using a JWT signed
with its own key. The relay accepts the JWT because it learned the public key in
`POST /sync/pairing/response`.

---

### 3.4 Recovery

⚠⚠ **ownerId-blind since 2026-10-28** ([`relay-metadata-minimisation.md`](relay-metadata-minimisation.md)
F8; threat model T-RC-08). `ownerId` is public, because every pairing QR carries it. So no route
below answers a caller who presents only an `ownerId`:
- the challenge names no realm;
- the claim carries the master public key;
- `/sync/realm-epoch` (§3.6) takes the device JWT.

This is folded into protocol 1 (D13, `RelayProtocol.kt`).

⚠ **`POST`/`GET /sync/recovery-blob` were removed** in the same change, together with the relay's
`recovery_blob` table (absent from the 2026-10 relay baseline). The `GET` served the A9 blob to anyone holding an
`ownerId`. No client ever uploaded one, and recovery derives A1/A13 from the phrase alone. A future
A10 recovery manifest needs its own routes and its own threat-model amendment, and must not be
addressed by `ownerId` (threat model A9).

#### `POST /sync/recovery-claim`

The recovering device proves possession of the master identity (derived from the recovery
code) and registers its public key.

- **Auth:** none. The master signature in the body is the capability.
- **Request body:**
  ```
  {
    "masterPublicKey": <ed25519 32 bytes, base64; ownerId = hex(sha256(masterPublicKey))>,
    "newDevicePublicKey": <ed25519 32 bytes>,
    "newDeviceId": <opaque string>,
    "challenge": <fresh random bytes from the relay; see below>,
    "masterSignature": <signature over recoveryClaimMessage = LP("tenshu-recovery-claim-v1") ‖ LP(newDevicePublicKey) ‖ LP(newDeviceId) ‖ LP(challenge),
                        signed by master identity>,
    "epoch": <int>
  }
  ```
- **Two-step interaction.** Recovery is a challenge-response to prevent signature replay:
    1. `GET /sync/recovery-challenge` (no parameters) returns a fresh `challenge` (random bytes,
       server-stored with a 5-minute TTL; at most 10 000 outstanding, `503` past that). It
       names no realm and is issued to anyone, so it is no existence oracle. Per client IP
       (an IPv6 client per /64) at most `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE` (default 6:
       a burst of 6, then one per 10 s) are issued; past that `429` with `Retry-After`, checked
       before the store so a throttled caller holds no slot (threat model T-RC-08 (a)). The
       client surfaces it as `relay-rate-limited-429 … (retry after Ns)`, a retryable failure.
    2. The device signs the challenge with the master identity (derived from the recovery
       code) and POSTs `/sync/recovery-claim`.
- **Server-side checks, in this order:**
    - `challenge` matches an outstanding challenge (consumed on use) → else `401`.
    - `masterSignature` verifies against the `masterPublicKey` **in the body** → else `401`.
    - only then: the realm `sha256(masterPublicKey)` exists → else `404`, and its stored
      `masterPublicKey` equals the body's → else `401`. ⚠ The order is the F8 fix: a caller who
      cannot sign gets the same `401` whether or not the realm exists.
- **Side effects:**
    - Stores `(ownerId, newDeviceId) → newDevicePublicKey` permanently.
    - Allows the recovering device to push its first block (the `Device` entity that
      represents itself, with a master-self-delegation as its admission record per
      [`docs/sync/design.md`](../sync/design.md) §2.2 "Two paths into a realm").
- **Response:** `200`.

The recovering device's `Device { isAuthorized = true }` block is signed by *itself* but
its admission is by *master self-delegation* — i.e. the block claims "I was admitted by
the master identity directly," and other realm devices verify the master signature on
merge. This is the recovery-vs-first-device distinction the device-management doc owes
the threat model.

---

### 3.5 Tombstones

`DeleteBlock` is a third block kind alongside `SyncBlock` and `LazyBlock`. Protocol
semantics + merge interaction in [`docs/sync/design.md`](../sync/design.md) §2.6;
design rationale in [ADR 0006](../adr/0006-sync-tombstones.md). The relay-side semantics:

- `DeleteBlock`s flow through `POST /sync/push` and `GET /sync/changes` like any
  other block — the relay treats them as eager (always included in changes). They
  carry a signed `targetEntityId` plus the standard envelope (deviceId, ownerId,
  HLC, signature).
- The relay tracks two pieces of state per `DeleteBlock`:
    - **Acknowledgement set** — which realm devices have observed an HLC ≥ tombstone
      HLC via the existing HLC-tip piggyback.
    - **Retention deadline** — `tombstone.hlc + volume.retentionWindow`. The volume's
      retention window is configured per-volume in the control app; default 30 days.
- **Body GC** is gated on *both* conditions: every currently-known realm device has
  ack'd the tombstone *and* the retention window has elapsed. Only then does the
  relay drop the bodies of blocks targeted by the tombstone. The tombstone itself
  is kept indefinitely.
- A device returning after retention sees the tombstone but not the body; the
  device's `MergerRegistry` short-circuits on the tombstone and removes the entity
  locally. Body is irrecoverable; that's the user's opt-in cost.

The retention default and per-volume override are stored in the volume's metadata
block (a regular `SyncBlock` — the relay reads only the retention-window field's
clear envelope value, not the rest of the volume metadata).

⭐ **As built from CR-2 B3c (2026-10-02):** tombstones go to `POST /sync/push-tombstone` as a binary
`CascadedTombstonePush` — `relayProtocolVersion`, the `DeleteBlock`, and `cascade` (the wire names of
every descendant the recursive delete removed). In one transaction the relay stores the tombstone and
applies rule 2 to the target and every cascade name at `T = block.hlc`: each holder's watermark rises
to T and its edge registers stamped ≤ T become none. The response is still the assigned sequence. A
out-of-range version gets `426`; a bare `DeleteBlock` is an unparseable `400` (it was `426` until the
2026-10 reset). Device-revocation handling is unchanged.

⭐ **Envelope authentication** ([`sync-envelope-authentication.md`](sync-envelope-authentication.md),
2026-10-27, part of the protocol-1 release baseline): every `DeleteBlock` carries a trailing
`authTag`, an HMAC under a child of the realm identity key over all its fields in wire form, and
`/sync/push-tombstone` returns `400` when it is missing or not 32 bytes. ⚠ The relay **cannot verify**
the tag (House Rule 1) — receivers do, and drop what fails. A `SyncBlock`'s `encryptedPayload` is now
sealed with its envelope bound as associated data; no field changed, and nothing about it is visible
to the relay.

Revocation of a device ([ADR 0007](../adr/0007-sync-device-management.md)) is implemented as a
`DeleteBlock` of the device's `Device` entity plus the existing volume-key epoch
rotation. No special revocation-block kind. See [`docs/sync/design.md`](../sync/design.md)
§2.6 "Revocation is a tombstone" for the full revocation flow.

---

### 3.6 Realm-state metadata

#### `GET /sync/realm-epoch`

Out-of-band realm-epoch fetch. Closes the recovery-race carve-out from
[ADR 0007](../adr/0007-sync-device-management.md): a recovering / pairing-claim
device has no local tombstones yet, so its `currentRealmEpoch` view is 0 even if other
realm devices have advanced past multiple revocations. Without this endpoint, the
recovering device's self-pushed `Device` entity carries `admittedAtEpoch = 0` and is
T-K-02-rejected by every peer past epoch 0; the same device also accepts stale-epoch
peer blocks until tombstones drain.

- **Auth:** device JWT (F8, 2026-10-28). Both callers — the recovery claim and the pairing key
  installer — fetch the epoch only after the relay registered their device key, so they hold a
  JWT. The realm is the JWT's owner; there is no `ownerId` parameter. ⚠ Before F8 this took
  `?ownerId=` unauthenticated and answered realm existence plus the device-removal count to
  anyone holding a (public) `ownerId`.
- **Response:** `200 OK` with `{ "epoch": "<integer-as-string>" }` — the count of
  `DeleteBlock`s in the relay's mailbox whose `targetEntityType == Device.ID`. Returns
  `0` for an owner with no Device tombstones.
- **Idempotency:** read-only; no side effects.

The relay computes the count from its existing `revokedDeviceId` index (Phase 4 push-
rejection already exposes this field on Device-targeted tombstones — see
`SyncRouting.kt`'s push-rejection logic). Counting is just an aggregation over data the
relay already sees; no new metadata surface.

**Trust surface (threat-model T-K-02 follow-up).** This endpoint is the one place where
the relay is no longer fully zero-trust on realm metadata. The relay can:

- *Overstate* the count → recovering device claims a higher `admittedAtEpoch` than the
  realm has actually rotated to. Peers accept (T-K-02 enforcement is `<` only; future
  epochs pass), so this is harmless.
- *Understate* the count → recovering device claims a lower epoch, gets peer-rejected
  until tombstones drain. Degrades to the pre-fix behaviour for that device only —
  doesn't poison the realm.

Neither lets the relay forge an admission, weaken signature checks, or read user data.
A full closure of this residual would require a signature-attestation by an existing
realm device (a "realm-state attestation" block kind on the sync wire, paid for only
on recovery); sized as a future proposal.

**Client side.** `SyncRelay.fetchRealmEpoch(ownerId)` (`:sync-service-common`) is the
client-side surface. `RestSyncRelay` issues the GET; `MockSyncRelay` mirrors the relay
by counting Device-targeted tombstones in its in-memory store.
`RecoveryClaimImplementation` calls it after `recoveryClaim` succeeds (and surfaces a
typed failure if the fetch fails — we don't ship a stale-epoch self-push silently).
`GetPairingStatusImplementation` calls it on the joining-device path with soft-fail
fallback (Device A's authoritative Device block field-merges to the right epoch
regardless, so a one-shot relay error there isn't fatal).

---

### 3.7 Content plane (file-content chunk blobs)

The metadata/ordered-block plane (§3.1) carries small bounded blocks. Multi-MB
file *content* lives on a **separate content plane** per
[ADR 0017](../adr/0017-file-content-two-plane-channel.md) (canonical protocol
in [`sync/design.md` §2.7](../sync/design.md#27-file-content-two-plane-channel)) — immutable,
content-addressed, **ciphertext-opaque** chunk blobs, fetched on demand by
opaque ref, never on the ordered drain. This decouples a multi-TB realm's
metadata sync from its bytes (a fresh device opens one file without draining
every chunk of every file) and is the production fix for the confirmed Control
OOM. The client side is the merged `ContentBlobStore` /
`RestContentBlobStore` (P1 write `putChunk`, P2 read `getChunk`); these two
endpoints are the relay store those calls target.

#### `PUT /sync/content/{ref}`

Store one already-AEAD-encrypted chunk ciphertext.

- **Auth:** JWT required (same shape as `POST /sync/push`). Dev mode also
  accepts `X-Dev-Owner`.
- **Path parameter:** `ref` — the client's opaque `blob-<sha256(ciphertext)>`
  key (derived from *ciphertext*, never plaintext — see §6 / T-R-09). The relay
  treats it as an opaque path segment.
- **Request body:** binary, `application/octet-stream` — the opaque chunk
  ciphertext, streamed (the client writes it through a Ktor
  `WriteChannelContent`, not a `BinarySerializer` buffer; the relay reads it
  without parsing). The relay **never decrypts or inspects** it (House Rule #1).
- **Server-side scoping:** the blob is stored under `(authenticated-owner,
  ref)`. `ownerId` always comes from the verified JWT `iss`, never the request,
  so a device in realm A cannot `PUT` into realm B's namespace.
- **Re-`PUT` replaces:** a re-`PUT` of an existing ref replaces the stored
  ciphertext and refreshes the blob's GC liveness, on every backend (2026-11-01). A retried chunk is still idempotent, because a chunk's ref is
  `sha256(ciphertext)` and carries identical bytes. Refs that are *not* a hash of
  their body — a manifest re-sealed under a fresh nonce, a relation slice
  re-sealed or withdrawn in place (`sync-device-catch-up.md` §11.4.4) — rely on
  the replacement. A re-`PUT` of a ref the owner already holds is not
  quota-checked (Q-SL2, open).
- **Response:** `204 No Content` on success. The client accepts any
  2xx / 201 / 204; non-2xx is treated as a failed chunk and aborts the manifest.
- ⚠⚠ **Length floor.** A body below `AeadBackend.AEAD_MIN_CIPHERTEXT_SIZE` — one empty AEAD
  envelope, `nonce(24) || tag(16)` = **40 bytes** — is refused `400`. Every blob the store holds is
  AEAD output, chunk *and* manifest, so a shorter body is a **truncation**, and storing one silently
  replaces real ciphertext with something that can never decrypt. Checked on the **materialised**
  body, never on `Content-Length`: a body arriving shorter than its headers promised is the exact
  failure this closes. Production accumulated **278 zero-length blobs under well-formed refs** before
  it existed.
- ⚠⚠ **The relay does NOT verify `sha256(body) == ref`, and structurally cannot.** A *manifest's* ref
  is `sha256("manifest:$contentId")` (`FileContentChunking.manifestContentRef`), **not** a hash of
  its bytes, and manifests share the `blob-<64 hex>` namespace with chunk refs, which *are*
  `sha256(ciphertext)`. The relay cannot tell them apart, so blanket verification would reject every
  manifest `PUT`. The length floor is the strongest check available without a protocol change; a
  client-supplied digest header would be the real fix and is a wire change, not this one.
- **Errors:** `401` (no/invalid JWT), `400` (missing ref, malformed ref, or a body below the floor),
  `413` (above `MAX_CONTENT_BLOB_BYTES`), `507` (`over_quota`).

⚠ Additive validation, so **no `RELAY_PROTOCOL_VERSION` bump**: a client sending a well-formed blob
is unaffected, and one sending a truncation already treats non-2xx as a failed chunk and retries.

#### `POST /sync/content/delete`

> ⚠⚠ **Removed in CR-2 B6** (2026-10-04, `content-plane-cr2-b6-spec.md` §2, §7): no client frees or
> enumerates content for freeing any more; the relay's own sweep (`CONTENT_SWEEP_MODE`, deployed `armed`)
> is the only freeing path. The route is now an unmatched 404. Kept below as the record.

Client-driven reclamation (ST-3b). The owner's own device names refs it has determined nothing
references any more; the relay drops those blobs and their index rows.

```
POST /sync/content/delete
Authorization: Bearer <device JWT>
Content-Type: application/json

{ "refs": ["blob-<64 hex>", ...] }          → 200 { "blobsDropped": N, "bytesFreed": B }
```

- ⚠⚠ **`ownerId` comes from the verified JWT and the body carries no owner field.** A ref belonging
  to another owner is skipped, not errored — so this is not an existence oracle either. Threat
  model T-R-13.
- **Idempotent**: absent refs are skipped, so a retried reclamation returns `blobsDropped: 0`
  rather than failing.
- `400` for an empty list, more than **512** refs, or any ref not matching `blob-<64-hex>`.
- ⚠ **The relay cannot validate that a ref is unreferenced** — manifests are ciphertext (House
  Rule #1). That judgement is the client's, and a copy shares one `contentId`, so "a file was
  deleted" is not the same claim as "nothing references this content".
- ⚠ Additive: an older relay answers `404`, so a client must treat that as "reclamation
  unavailable" rather than as failure. No `RELAY_PROTOCOL_VERSION` bump — no existing route's
  semantics, envelope, auth or blob-ref format changes.

#### `GET /sync/content/{ref}`

Fetch one chunk ciphertext by opaque ref (P2 selective read path).

- **Auth:** JWT required. Dev mode also accepts `X-Dev-Owner`.
- **Path parameter:** `ref` — as above.
- **Server-side scoping:** lookup is `(authenticated-owner, ref)`. A device can
  only read blobs in its own realm; an identical ref under another owner is a
  distinct, invisible blob.
- **Response:** `200 OK`, `application/octet-stream`, body = the exact stored
  ciphertext bytes (streamed; one chunk is the memory unit, `O(CHUNK)`). `404`
  if absent (never stored, or aged out by the content GC). The client maps a
  non-2xx to a clean failure the manifest applier gates on — the file does not
  surface; a later resolve retries (immutable + content-addressed ⇒ idempotent).
- ⚠⚠ **A stored blob below the floor answers `404`, never `200`.** `FileBlobStore.get` is
  `if (it.exists()) it.readBytes() else null`, so a truncated file used to read back as a
  **successful empty body** — the caller then failed to *decrypt*, with no status code to blame,
  which is how 278 of them went unnoticed for six days. ⭐ `404` rather than `5xx` is deliberate:
  the client already treats a missing chunk as a clean failure and retries, and a chunk is immutable
  and content-addressed, so a re-emit is idempotent — this routes silent corruption onto the path
  that already heals. ⚠ The `PUT` floor cannot reach blobs already stored; this is what makes them
  legible.
- **Errors:** `401` (no/invalid JWT), `404` (no such blob, or one below the floor), `400` (missing ref).

#### `POST /sync/content/declare` / `POST /sync/content/undeclare`

> ⚠⚠ **Changed by CR-2 B3c (2026-10-02, relay protocol 4).** The **edge form** of `declare` and all of
> **`undeclare` are deleted**: edges arrive only on `POST /sync/push` and leave only by a tombstone's
> cascade or `POST /sync/content/reap` (below). A body that still carries `edges` gets `400`;
> `undeclare` answers `404`. `declare` keeps the `contents` form — `{contentId, refs}` only — and
> answers `200 { "refsDeclared": M, "contentsDescribed": C }`. ⚠ Since the 2026-10 reset `edges`,
> `referenceId` and `hlc` are **unknown keys** (the decoder rejects them ⇒ `400`); the accept-and-ignore
> tolerance for clients one release behind is gone. The text below is the pre-B3 contract, kept for
> its rationale.

The **declaration seam** ([`content-plane-refcount.md`](content-plane-refcount.md) §5.2, CR-3).
A holder tells the relay which content it references; a tombstone tells it that holder is gone.

```
POST /sync/content/declare
Authorization: Bearer <device JWT>
Content-Type: application/json

{ "edges": [ { "holderHash": "...", "propertyId": "...", "contentId": "...",
               "refs": ["blob-<64 hex>", ...] }, ... ],
  "contents": [ { "contentId": "...", "refs": ["blob-<64 hex>", ...],
                  "referenceId": "...",        // REQUIRED — the reference handle (N1)
                  "hlc": "..." }, ... ] }      // optional stamp, see below
                → 200 { "declared": N, "refsDeclared": M, "contentsDescribed": C }
                → 507 when the owner is at the reference-row cap

POST /sync/content/undeclare
Authorization: Bearer <device JWT>
Content-Type: application/json

{ "holderHashes": ["...", ...] }            → 200 { "edgesDropped": N }
```

- ⚠⚠ **Each form requires only the store it writes to.** `edges` needs the edge registry, `contents`
  needs the ref registry, and a form whose store is absent gets a `404` while the other keeps working.
  The route is therefore registered whether or not an edge store exists — gating its registration on
  one would make the edge-free end state (N6), the configuration the describe wire is *for*, the one
  configuration in which nothing can be described and `/sync/content/contents` answers empty for ever.
- ⭐⭐ **`referenceId` is the handle, and it is required** (`content-plane-refcount.md` §6e, N1).
  One live reference names this content, and the count the relay acts on is a `COUNT(*)` over
  **handles**, not over contents — which is what lets two live rows legitimately share a `contentId`
  (a conflict-split repair, a rename-vs-edit, dedup) hold it at two rather than one. ⚠⚠ A describe
  without one would record bytes that no reference holds: at N6, with the edge plane gone, deleting
  some *other* reference takes the count to zero while this row is still live. That is the pre-N1
  reference problem manufactured by the wire built to end it, so it is a `400` rather than a default.
  ⚠ Required cost no `RELAY_PROTOCOL_VERSION` bump because the `contents` form has never had a
  client caller — this completes an unfinished wire rather than breaking a contract.
  ⚠⚠ **Opaque and random at the client.** Never a hash of the holder (a derived id moves when the
  row moves) and never keyed (a keyed form cannot survive a shared-realm key rotation). The relay
  stores it and compares it; it recomputes nothing.
- ⚠ **`hlc` is optional and means "the HLC of the row version that names this handle"** — the stamp
  a later release CAS compares against, so a release computed before a describe the relay has
  already seen can be refused. Defaulted to empty because N1's emit does not yet carry one, and
  **N4's CAS may not be built on it until the emit does**: an unstamped handle compares equal to
  everything, which is an accepted-always CAS rather than a check.
- ⚠⚠ **A per-owner reference-row cap, answered `507`.** Handle rows are free — a device can describe
  a fresh random handle for one `contentId` indefinitely, inserting a row each time and uploading
  **no bytes**, so the byte quota does not bound this table at all (T-R-09/§9.4's declaration-row
  inflation, in a new table). The request is refused **whole**: half-writing it would leave live
  content described and unhandled, which is the state the handle exists to prevent, and a describe is
  idempotent so the client simply retries. ⚠ Re-describing a handle the relay already holds is
  **not** refused at the cap — it inserts no row, and refusing it would wedge the emit's ordinary
  retry for ever once an owner sat at the limit.
- ⭐ **Two forms, and one request may carry both** (`content-plane-refcount.md` §6e, phase N1).
  `edges` is the CR-3 form above. **`contents` is the holder-less describe wire**: it writes
  `content_ref` rows and **no `content_edge` row**, so neither a holder name nor a `propertyId`
  reaches the relay for those entries — the disclosure C′ exists to retire, since `propertyId` is on
  the wire today only because an edge is keyed by it (T-R-09's CR-4 amendment). What stays disclosed
  is which blobs form one content object, accepted at **Low** in the CR-4b amendment and the same set
  `/sync/content/delete` has sent since §4.9.
- ⚠⚠ **`contents` must not displace `edges` before phase N4.** Until the authority flips, the
  candidate predicate is still `described − referenced`, so content described *without* its edge is
  described and referenced by nothing; once the age window below expires the relay offers a live
  upload as an orphan, a peer that has not yet received the entity fails its live-walk veto, and the
  bytes go permanently. ⇒ clients keep declaring edges through N4. The wire may land early; dropping
  the edge half may not.
- ⚠ A `contents` entry with **empty `refs` is a `400`**, where the same on an edge is "I am not
  describing this content". The forms differ because the entries do: an edge still carries a
  holder→content reference without refs, while a `contents` entry is *only* a ref set, so an empty one
  makes the write delete the rows it claims to be writing. `404` when the relay has no ref table to
  write into — loud rather than a `200` that would tell a client its content is described when
  nothing on that relay knows the bytes.
- ⚠⚠ **A set keyed `(ownerId, holderHash, propertyId)`, never a counter.** Declaring is an
  **upsert**, so a re-emit *replaces* the edge — which is what makes the overwrite leak
  structural rather than something `UploadFileImplementation` has to notice. A counter
  would double-count a retry, and retries are the normal case here.
- ⚠⚠ **`ownerId` comes from the verified JWT; neither body carries an owner field** — the
  `/sync/content/delete` posture. `undeclare` is deletion-adjacent once enumeration lands, so
  this scoping is the control that stops one tenant stranding another's content.
- ⚠⚠ **`refs` is what makes the table answerable, and without it nothing can be freed.** An edge
  names a `contentId`; the blob store is keyed by ref. From a contentId the relay derives exactly one
  ref — the manifest's, `sha256("manifest:" + contentId)` — and every chunk ref is
  `sha256(ciphertext)`, inside the manifest it may not read (HR #1). The client sends the set it
  already holds at emit: the chunks plus the manifest. Stored per **content**, in `content_ref`,
  replaced atomically. ⚠ An **absent or empty** `refs` means *"I am not describing this content"* —
  any existing set is left standing, never wiped.
- ⚠ **Undeclare is by holder, not by content.** At delete time the row is gone and its
  `contentId` is unreadable — which is exactly why `DeleteFileImplementation` had to capture
  `orphanedContent` *before* deleting. Keyed by holder, undeclare needs nothing from the dying
  row, and the per-service hook can go.
- **Idempotent both ways**: an unknown holder drops `0` and is not an error; a repeated
  declaration is a no-op upsert.
- `400` for an empty list, more than **512** entries, an identifier over 256 characters or outside
  `[A-Za-z0-9_.:/@+~-]`, more than 4 096 refs on one edge, more than **8 192 refs in the request**,
  or any ref not matching `blob-<64-hex>`. ⚠⚠ **A malformed edge rejects the whole batch** (changed 2026-09-09; it used to
  be skipped). Silently dropping a declaration leaves live content with no edge, which under
  enumeration reads as an orphan — the dangerous direction — and answered `declared: 0` to a caller
  that believed it had succeeded. ⚠ The first three identifiers are bounded by length and charset
  rather than exact shape, deliberately: over-strict validation on *declare* rejects a real
  reference, while a loose bound only lets a caller waste its own rows.
- ⚠ **HR #1 intact**: the relay is *told* these edges. It reads no manifest and derives nothing.
  ADR 0017 Decision 6 ruled out a relay-*derived* refcount (still impossible) and also a
  client-*declared* one on privacy grounds — that second half was reopened 2026-09-08, because
  ST-3b's `/sync/content/delete` above already discloses the same ref set.
- ⚠ Additive: an older relay answers `404`. No `RELAY_PROTOCOL_VERSION` bump.
- **Written at the emit and at the tombstone drain; read by the sweep below.** `SyncObserver`'s
  content emit declares, `SyncScheduler.drainPendingTombstones` undeclares, and
  `/sync/content/orphan-candidates` is the only reader — live on Android and desktop, inert on iOS.
- ⚠⚠ **An absent edge does not mean "unreferenced."** Four populations legitimately carry none
  (`content-plane-refcount.md` §9.3, starting with every byte uploaded before CR-4), which is why
  nothing may free on this table alone: the reclaim frees only what a client's own live walk has
  confirmed dead, and nothing at all if that walk did not finish.

#### `POST /sync/content/reap` — a device's reaps (CR-2 B3c)

```
POST /sync/content/reap
Authorization: Bearer <device JWT>
Content-Type: application/json

{ "relayProtocolVersion": 4,
  "removals": [ { "holder": "<wire name>", "hlc": "<HlcStamp: physical:counter:device>" }, ... ] }
                → 200 { "removals": N, "edgesCleared": K }
```

- Rule 2 per entry, at the entry's `hlc` (the **folder tombstone's**, §10.12 Q4 revised); one
  transaction per request. Idempotent: every reaping device sends the same `(holder, T)`.
- Covers the children a deleter's cascade could not name — the ones a peer pushed before the deleter
  pulled them.
- **Errors:** `400` (empty, more than 1,000 removals, a malformed holder or an unreadable stamp — the
  batch is refused whole — and, since the 2026-10 reset, a body without `relayProtocolVersion`), `401`,
  `426` (version out of range). Registered only with an edge
  store.
- ⚠⚠ Every query that counts or enumerates edges filters `content_id IS NOT NULL` (the candidate set,
  the reclaim re-check, the inventory, the operator job): a none register is a stamp, not an edge.

#### `GET /sync/content/orphan-candidates` / `POST /sync/content/reclaim`

> ⚠⚠ **Removed in CR-2 B6** (2026-10-04, `content-plane-cr2-b6-spec.md` §2, §7): no client frees or
> enumerates content for freeing any more; the relay's own sweep (`CONTENT_SWEEP_MODE`, deployed `armed`)
> is the only freeing path. The route is now an unmatched 404. Kept below as the record.

CR-5 (`content-plane-refcount.md` §9.5/§9.8) — the pair that actually frees.

```
GET  /sync/content/orphan-candidates?limit=256   → 200 { "contentIds": [...] }
POST /sync/content/reclaim  { "contentIds": [...] }
     → 200 { "contentsFreed": N, "blobsDropped": B, "bytesFreed": Y, "skippedStillReferenced": S }
```

- **A candidate is `described ∧ no live edge names it`** — the difference between `content_ref` and
  `content_edge`. ⚠ **Candidates, not orphans**: a recreate (ADR 0006) and a move both leave a live
  row pointing at content whose edge was legitimately dropped, so a client MUST veto against its own
  live walk first, and MUST reclaim nothing at all if that walk did not finish.
- ⚠⚠ **ContentIds, never refs — the reason the pair is shaped this way.** The reclaim frees only
  what `content_ref` attributes to a described content object, so *"never touch an undeclared ref"*
  is enforced here rather than trusted from the client. A client can name a contentId; it cannot
  name a blob. Content with no description — every byte uploaded before CR-4, the legacy manifest
  path, Fit — is unreachable from this route by construction.
- ⚠⚠ **Reclaim re-derives candidacy and does not trust the request.** An edge that landed between
  the client's read and its call is skipped and counted in `skippedStillReferenced`.
- ⚠⚠ **The age window applies to `orphan-candidates` and NOT to `reclaim`** (`content-plane-refcount.md`
  §6e, phase N0). Candidacy withholds content **any** of whose blobs was touched within the last
  **24 h**: chunks are `PUT` before the description lands and the entity row reaches a peer later
  still, so an upload in flight is indistinguishable from an orphan to an enumerating device.
  `reclaim` must stay windowless, because a file uploaded and then deleted carries a fresh
  `last_referenced_at` and a window there would refuse exactly the delete-driven reclaim that frees
  space at once.
- ⚠⚠ **Any, not all — the rejected reading is the load-bearing part.** Withholding only while *every*
  blob is young expires at `firstChunkPut + window`, measured from the **start** of the upload, so a
  long upload would be offered *during its own upload*; no window length fixes that, since a longer
  one only moves which upload it fails on. Under "any", cover lasts until the whole object has been
  settled for the window. The cost is that a download of one chunk defers the object for a day —
  deferral of a backstop, never a loss, and `last_referenced_at` is refreshed by `get` as well as
  `put`. ⚠ Content whose refs match no blob the relay holds is **not** withheld: there is nothing
  young to protect, and withholding on an empty set would pin those ref rows out of every enumeration
  for ever.
- **Idempotent**: a contentId whose rows are already gone frees nothing and is not an error.
- `400` for an empty list or more than **256** contentIds; `404` when the relay has no edge/ref
  tables wired. ⚠ Additive, so no `RELAY_PROTOCOL_VERSION` bump.
- ⚠ Client-side this ships **inert**, behind the same `reclamationDryRun` switch as
  `POST /sync/content/delete`'s sweep.

#### `GET /sync/content/contents`

> ⚠⚠ **Removed in CR-2 B6** (2026-10-04, `content-plane-cr2-b6-spec.md` §2, §7): no client frees or
> enumerates content for freeing any more; the relay's own sweep (`CONTENT_SWEEP_MODE`, deployed `armed`)
> is the only freeing path. The route is now an unmatched 404. Kept below as the record.

C′'s enumeration read (`content-plane-refcount.md` §6e, phase N2) — one page of the contentIds this
owner has **described**, so a client can compute `stale = listed − (its own complete live walk)`.

```
GET /sync/content/contents?after=<contentId>&limit=512
    → 200 { "contentIds": [...], "nextAfter": "<contentId>" | null }
```

- ⚠⚠ **Paged on the content id, never an offset.** Devices declare into the set while the pass walks
  it, and an offset page shifts under a concurrent insert — the entry that slid across the boundary is
  never returned, and content nothing references is then missed for ever.
- ⚠ **`nextAfter` is non-null only when the page was full**, and it is taken from the page the store
  returned rather than from the filtered answer. The age window filters the answer; a page filtered to
  nothing still carries its cursor, because a walk that read that as the end would leave every id
  after it unvisited. ⇒ a short `contentIds` with a non-null `nextAfter` means "keep walking".
- `limit` is clamped to `1..512` and defaults to 512. The window above applies here too.
- ⚠ **Cost follows the page, not the store.** A page is answered with one grouped `content_ref` read
  for its ids and one `content_blob` read for exactly the refs those ids name — never a scan of the
  owner's whole blob table, which `ContentEnumerationSweep`'s polling would otherwise pay on every
  tick. An empty page reads no blob state at all. ⚠⚠ This narrows what is *read*, not what the rule
  *sees*: a content object's refs are fetched whole, per content, so the window still judges every
  blob of every id it answers about. Refs are never split across pages — a page is a page of content
  ids, and each id carries its whole ref set.
- ⚠⚠ **Described, never "unreferenced".** Under C′ the relay holds no reference graph and this route
  asks it for none — it depends on `content_ref` alone and returns only what this owner's own devices
  told it. The client's complete live walk is the authority, and an incomplete one reclaims nothing.
  ⚠ It also cannot name content that was never described: §9.9's legacy corpus stays CR-6's problem.
- ⚠ `ownerId` is the verified JWT's; `404` when the relay has no ref table wired — not an empty page,
  which a client would subtract its live set from and conclude nothing is stale, silently and for ever.
- ⚠ Additive, so no `RELAY_PROTOCOL_VERSION` bump; an older relay answers `404`.

#### `GET /sync/content/inventory`

CR-6e (`content-plane-refcount.md` §11.9, track P2) — **the inventory**: `blobs − refs(described)` for
the calling owner, i.e. how many stored bytes no declaration reaches.

```
GET /sync/content/inventory
    → 200 { "blobCount": N, "blobBytes": Y, "zeroLengthBlobCount": U,
            "describedContentCount": D, "candidateContentCount": C,
            "undescribedBlobCount": N', "undescribedBlobBytes": Y',
            "zeroLengthUndescribedBlobCount": U',
            "recentlyTouchedBlobCount": R, "recentlyTouchedBlobBytes": RY }
```

- ⚠⚠ **It frees nothing, and must never learn how.** Its value is that it can be run against
  production with no decision attached, so the number §9.9b wanted exists before CR-6 is designed
  around a guess. The freeing act is CR-6f's quarantine, separately gated.
- ⚠⚠ **An undescribed blob is not an orphan.** Five populations legitimately sit in the figure:
  bytes uploaded **before CR-4**; ⚠⚠ **slice snapshots**, whose writer declares *never* (§9.9a) —
  ⭐ a *stable* floor, not a growing one, since their refs are deterministic and a re-seal overwrites
  the same ref; the legacy `else` emit branch (**Fit**); **aborted emits** (the one P1 attacks); and
  **in-flight uploads**. Reclaiming on this number would delete live joiner snapshots — a
  delete/rewrite churn loop, since the next maintenance pass rewrites them.
- **`recentlyTouched*` is §11.5's `W`, reported and never subtracted.** ⚠⚠ It **over-counts**:
  `last_referenced_at` is refreshed by `GET` as well as `PUT`, so a blob someone just *downloaded*
  is indistinguishable from one mid-upload. Subtracting it would hide live old bytes behind a read.
- ⚠ **Per owner is per realm** — `ownerId` is derived from the realm's master public key (the realm
  bootstrap rejects any other), so these totals are realm-wide.
- ⚠ **Described ≠ referenced.** A CR-5 *candidate* (described, no live edge) is **not** in the
  undescribed figure — its bytes are described, so CR-5 owns them. `candidateContentCount` reports
  that population separately, which is what lets the two mechanisms be compared rather than summed.
- ⚠⚠ **The byte figures are lower bounds.** `zeroLength*` counts rows recording 0 bytes, which is
  a stored zero-length body — no legitimate blob is one (AEAD adds 40 bytes). The pre-v9
  `byte_len DEFAULT 0` population that once also produced zeros went with the 2026-10 reset (the
  column now has no default). History: on production 2026-09-11, 278 such rows held 0-byte files
  whose bytes were gone; `FileBlobStore.get` returns `readBytes()` for any existing file, so a `GET`
  on one answers **200 with an empty body** and the caller fails to decrypt instead of seeing a 404.
- **Returns counts only — never a ref or a contentId**, so the response cannot become something a
  client acts on.
- Caller-scoped from the verified token, the `/sync/usage` posture; `401` without a credential,
  `404` when the relay has no ref table wired (**not** an empty report: with no `content_ref` every
  blob would read as undescribed, a maximally alarming number meaning only "not installed").
- ⚠ Additive, so no `RELAY_PROTOCOL_VERSION` bump — but it needs a **deploy** to be readable.

#### Unreferenced-blob GC (content-plane refcount sweep)

Chunk blobs are referenced by **manifests**, which travel as encrypted
`SyncBlock`s the relay **cannot read**. The relay therefore cannot learn a
blob's refs from a manifest, nor from a file-delete `DeleteBlock` tombstone
(whose relay-visible fields are an opaque `targetEntityIdHash` + entity type —
never chunk refs). A literal manifest-driven refcount would require the relay to
read content, which House Rule #1 forbids.

⚠⚠ **Both halves of the following were reversed — see ADR 0017 Decision 6's amendment.**
The client "release refs" signal described here as *considered and rejected* shipped, as
`POST /sync/content/delete` (documented above); and the retention/orphan policy it was
rejected in favour of is **disabled in production** (`CONTENT_RETENTION_WINDOW_MS =
Long.MAX_VALUE`), because time-reaping durable content is the content-plane data-loss bug (`retention-quota-model.md` §2). The
original design is kept below because the `lastReferencedAt` mechanism still exists as a
seam, but it is not what reclaims content today:

- Every blob carries a `lastReferencedAt`, refreshed on every `PUT` **and**
  every `GET`. A `GET` is the relay-visible proof that *some* live manifest
  still points at this ref (a device only fetches a chunk it found in a manifest
  it holds). This is the refcount proxy that needs **no manifest reading**.
- A blob is collectible only once it has been **untouched** (no PUT, no GET)
  for a content-retention window that is **`≥` the tombstone-body retention
  window** (`RelayConfig.DEFAULT_RETENTION_WINDOW_MS`, 30 days). This bound is
  chosen to provably **outlive** the ordered-log tombstone GC
  ([`sync/design.md`](../sync/design.md) §2.6 "Relay-side body GC"):
  a deleted file's manifest body is dropped by
  `SyncMailbox.gcSweep` after full-quorum ack + retention; only after that will
  no device ever `GET` those chunks again, so they then age out by this
  equal-or-longer window. The two GCs **compose** (tombstone GC frees manifest
  *bodies* on the ordered plane; this frees *chunk blobs* on the content plane)
  — they do not duplicate.
- **Shared-blob safety:** identical ciphertext dedups to one ref; while **any**
  referencing manifest is live, that ref keeps being `GET`-ed, so it is never
  collected. **Orphan-PUT grace:** an upload whose manifest never lands ages out
  by the same window — that window *is* the grace.

Sweep cadence: nightly (`RelayConfig.CONTENT_GC_SWEEP_INTERVAL_MS`), the same
operational choice as the tombstone GC; the sweep is a cheap per-owner walk.
`Long.MAX_VALUE` retention disables content GC (parity with the tombstone-body
GC sentinel). **Bound (documented residual):** a deleted file's blobs linger up
to the content-retention window past the last `GET` rather than being promptly
deleted — conservative by construction (never collects a blob a live manifest
could still reference) at the cost of delayed reclamation. v1-accepted; see
`docs/sync/threat-model.md` T-R-09.

### 3.8 Operator surface (loopback-only)

Not client-facing; listed so nobody mistakes it for a gap. `/ops/content-reclaim/{plan,enqueue,drain}`,
`GET /ops/content-sweep/status` and `POST /ops/share/takedown` (`OperatorRouting.kt`; shapes in
[`relay-api.openapi.yaml`](../protocol/relay-api.openapi.yaml), tag `ops`). They exist only with
`RELAY_OPERATOR_TOKEN` set, answer only a **loopback socket peer** holding that token in
`X-Operator-Token` (never judged by forwarded headers), refuse every other caller with an empty 404,
and Caddy answers `/ops/*` with 404 at the edge. Runbooks:
[`relay-content-reclaim.md`](../how-to/relay-content-reclaim.md),
[`take-down-a-share-link.md`](../how-to/take-down-a-share-link.md).

The takedown changed one client-visible behaviour of the share routes, without a
`RELAY_PROTOCOL_VERSION` bump because no current client depends on the old one: `PUT
/share/{shareId}/chunk/{index}` answers **410** for a revoked share and **400** for an index at or past
the descriptor's `chunkCount`.

---

## 4. Polling vs Push

The user-facing question: how does the second device wait for the pairing response, and
how do steady-state devices learn about new blocks? Three options for each, ranked by
operational simplicity.

### 4.0 Why long-poll across the surface — architectural rationale

Per-endpoint analysis (below) repeatedly arrives at long-poll. The reasons aren't only
local; they compound across the relay's deployment posture. Spelling them out so a
future reader can re-evaluate when constraints change:

1. **The relay is stateless.** `:sync-relay-server` holds no decryptable state and no
   per-connection session. Long-poll connections are trivially load-balanced (any node
   can serve any request). SSE and WebSocket need either sticky sessions or a pub-sub
   fanout layer (Redis, NATS, …) once you scale beyond one node, because the node that
   *holds* the open connection is not generally the node that *receives* the new block
   from `POST /sync/push`. Phase 4 of `sync-backend-relay-plan.md` targets Postgres-only
   persistence; introducing a fanout layer would meaningfully expand that scope.

2. **Auth is uniform.** Every long-poll cycle is a JWT-authenticated HTTP request, same
   shape as `/sync/push`, `/sync/realm-epoch`, etc. SSE and WebSocket need the JWT in a
   query string or first message, plus reconnect-after-rotation logic that doesn't fit
   the per-request auth model the rest of the API uses. (Web's standard `EventSource`
   doesn't support custom headers — every SSE-on-the-web design has to work around this,
   typically with query-string tokens, which then leak into access logs.)

3. **Native client uniformity.** Drive and Control on Android/desktop use Ktor's HTTP
   client throughout. SSE works on Ktor but adds a parser + reconnect handler;
   WebSocket adds a fully separate code path with its own framing and ping/pong loop.
   Long-poll keeps the client surface boring — every relay interaction is the same
   `request → response` shape native clients already exercise.

4. **Volume doesn't justify a stateful connection.** Pairing is a one-shot 5-minute
   ceremony. `/sync/changes` is bursty (drained on push via the HLC-tip piggyback),
   not a chat firehose. The optimisation target is "user taps approve → other device
   sees it within ~1s", not 60Hz event streams. SSE's strengths are real-time event
   pushes at sustained rate; that's not the workload.

5. **Browser connection limits cut both ways.** HTTP/1.1 caps at ~6 connections per
   origin; the web apex's parallel long-poll streams have to live within that. SSE
   shares the same limit; only WebSocket sidesteps it (one connection multiplexes
   many streams). HTTP/2 multiplexing fixes the problem for both long-poll and SSE,
   so the limit is a deployment-time concern (terminate HTTP/2 at the load balancer)
   rather than a protocol choice.

**When this calculus changes.** Two plausible triggers for revisiting:

- **Sustained sub-second pull patterns.** If a future Tenshu app surfaces "watch
  another device's edits stream in live" UX, `/sync/changes` becomes a high-rate pull,
  and SSE's push semantics start to dominate long-poll's per-cycle overhead. Section
  4.2 already documents SSE as the upgrade path for this case.
- **A real fanout layer arrives anyway.** If the relay grows a pub-sub layer for some
  other reason (e.g. cross-region replication), the SSE/WebSocket cost drops and
  becomes a per-endpoint judgement call instead of an architectural barrier.

Neither is true today. The per-endpoint analysis below treats long-poll as the default
and only deviates with a load-bearing reason.

### 4.1 Pairing endpoints — long-poll, recommended

The pairing window is 5 minutes; the requester and host are both *waiting for a single
event*. Concrete recommendation:

- `GET /sync/pairing/response?pairingSecret=X&wait=30s` — relay holds the request open
  until either the response arrives or `wait` elapses. On wait timeout, returns `204`;
  the client immediately re-issues. On hit, returns `200` with the body.
  ⚠ Superseded by F7: the poll is `POST /sync/pairing/response/peek` with the secret's *hash* in
  the body (§3.3); a long-poll, if built, takes `wait` the same way and never a secret in the URI.
- `GET /sync/pairing/requests?wait=30s` — same shape on the host side.

Properties:

- **Cuts request volume by ~95%** vs 1-second fixed-interval polling, for the same UX.
- **No special server infra** beyond Ktor's `respond` being able to suspend; trivial to
  implement.
- **No new attack surface** — same JWT auth, same TLS connection, same wire format.
- **Graceful degradation** — clients that don't support long-poll (some constrained web
  contexts) can use `wait=0` and fixed-interval; the endpoint is identical.

Fallback: clients without long-poll capability (or operating under a transport that
strips connection holding — e.g. some CDNs) poll every 1–2 seconds for the duration of
the pairing window. Acceptable cost; pairing is rare.

**Why not SSE for pairing.** SSE works but adds an event-stream content type and its
own parser. For a single expected message per side per session, the complexity isn't
worth the marginal gain over long-poll.

**Why not WebSocket for pairing.** Bidirectional duplex over a 5-minute window where each
direction is a single message is overkill. Adds connection state, ping/pong heartbeats,
fragmentation handling — all to deliver one byte sequence.

### 4.2 Block sync — long-poll first, SSE later

Block sync is steady-state. Devices want to know about new blocks as they arrive, ideally
within a second. Options:

- **Fixed-interval polling.** `GET /sync/changes` every 30s in foreground, every 5min in
  background. Simple. Adequate for "your changes propagate within 30s". Wasteful and
  laggy.
- **Long-poll on `/sync/changes`.** `GET /sync/changes?since=hlc&wait=60s`. Relay holds
  the request open until either new blocks arrive or `wait` elapses. Clients reconnect
  immediately on response. Cuts polling overhead, gives ~immediate propagation in steady
  state. Same JWT auth, same wire format.
- **Server-Sent Events.** `GET /sync/changes/stream` keeps the connection open and
  pushes events as blocks arrive. Cleaner mental model for "subscribe to new blocks";
  slightly more server-side complexity (event-stream content type, heartbeat, reconnect
  semantics).
- **OS-native push (APNs / FCM).** Best UX (zero-latency wakeup from background); worst
  privacy footprint. Requires the relay to register tokens with Apple/Google and tells
  those providers "user X has new sync data at $time". That's an A6 metadata leak
  (timing) and adds two new AD5-class trust boundaries (APNs, FCM). **Out of scope for
  the relay protocol itself**; if we want it, it's an adjunct that wakes the device,
  which then uses the regular `/sync/changes` endpoint — the wakeup hint is metadata-only
  and doesn't carry block data.

**Recommendation:** **long-poll initially**, SSE later if we want sub-second propagation
on web specifically (where SSE is the natural primitive). Fixed-interval as a fallback
when long-poll isn't available.

This avoids OS-native push for now — the privacy cost is real and the UX gain is mostly
"app appears more responsive when backgrounded", which long-poll already covers in the
foreground case.

### 4.3 Heartbeat / connection-keepalive

Long-poll connections held open for 30–60s benefit from a periodic heartbeat in the
response stream so intermediaries (proxies, CDNs) don't kill the connection. For pure
HTTP long-poll this is implicit (the request just hangs); for SSE, a `: keepalive` line
every 15s is the convention.

---

## 5. Wire Format and Errors

### 5.1 Bodies

- **Block payloads** (`/sync/push`, `/sync/changes`, `/sync/fetch-property`): existing
  binary protocol, unchanged. The relay reads only the metadata envelope.
- **Control-plane bodies** (`/sync/realm`, `/sync/pairing/*`, `/sync/recovery-*`): JSON
  with base64-encoded binary fields. Small, human-readable, easy to debug. Pinned schema
  per endpoint.

### 5.2 Errors

- **`400 Bad Request`** — malformed body, missing required field, bad signature.
- **`401 Unauthorized`** — JWT missing, expired, or signed by an unknown device.
- **`403 Forbidden`** — JWT-claim mismatch (ownerId, deviceId not matching block).
- **`404 Not Found`** — recovery blob missing, pairing-secret unknown.
- **`409 Conflict`** — realm already exists; pairing-secret already used; manifest version
  not monotonic.
- **`410 Gone`** — pairing TTL expired; recovery challenge expired.
- **`429 Too Many Requests`** — rate limit (Phase 4.2 of the relay plan).
- **`5xx`** — relay-internal; client retries with backoff.

⚠⚠ **The binary `/sync` routes did not honour the first line until 2026-08-24.** `/sync/push`,
`/sync/push-tombstone`, `/sync/fetch-entity-blocks` and `/sync/changes` handed the body straight
to `BinaryParser.parse`, whose throw on a corrupt envelope escaped the route and hit the catch-all
`exception<Throwable>` mapping in `Application.kt` — answering **500** and logging at ERROR, so a
malformed upload read as a relay fault and any JWT-holding client could flood the log an operator
watches for real faults. All four now parse through `routing/ReceiveParsed.kt`; add new binary
routes through that helper rather than a bare `parser.parse`. No wire change and no
`RELAY_PROTOCOL_VERSION` bump: 400 was always the declared contract, and `RestSyncRelay` special-
cases only 402, so 400 and 500 already took the same client branch. These four answer plain text
like the sibling 403s in the same routes, not the typed body below.

Error bodies are typed (no internal exception text echoed; closes the
[`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §4.2 housekeeping item).

```
{ "error": "<typed_error_id>", "message": "<short human description>" }
```

### 5.3 ID derivation

- **`ownerId = base32(blake2b-256(masterPublicKey))`** — deterministic, lets any client
  derive ownerId from the master key without coordinating. Server verifies on receipt.
- **`deviceId = base32(blake2b-256(devicePublicKey))`** — same shape. Stable per device.
  ⭐ **As built (workstream B, 2026-10-29, rides the protocol-1 re-cut):**
  `deviceId = lowerhex(SHA-256(LP("tenshu-device-id-v1") ‖ LP(devicePublicKey)))[0:32]`
  (`deviceIdForPublicKey`, `sync-service-common/.../DeviceIdDerivation.kt`; vectors in
  `DeviceIdDerivationTest`). 128 bits, 32 characters, inside the `[A-Za-z0-9_-]` rule.
  `devicePublicKey` is the key the id is registered with: a device's A4 on its personal realm and
  on a shared realm it owns, the per-realm device key on a shared realm it joined as a member.
  **The relay re-derives it on every route that registers a device** — `/sync/realm`
  (`firstDeviceId` ↔ `firstDevicePublicKey`), `/sync/admit-device`, `/sync/pairing/request`
  (`deviceId` ↔ `signingPublicKey`), `/sync/pairing/response`, the approved
  `/sync/pairing/offer-response` (`offererDeviceId` ↔ `offererSigningKey`), `/sync/recovery-claim`
  and `/sync/realm-member/admit` (`deviceBinding.deviceId` ↔ `perRealmDevicePublicKey`) — before the
  admission/master signature is checked, the realm looked up, a challenge spent or anything
  written (caller auth — JWT, the offer responder's signature — may precede it), and answers
  `400` with body `device-id-not-key-derived: …` ([`RelayRefusal.DEVICE_ID_NOT_KEY_DERIVED`]) on a
  mismatch. No field was added or removed, so no `RELAY_PROTOCOL_VERSION` move: the reset puts every
  client and the relay on the rule together. ⚠ An id now **changes with its key**; there is no A4
  rotation (`tech-debt.md` *"Public-Key Rotation Without Re-Pairing"*), and one would mint a new
  device. Threat model A4, *workstream B sub-amendment*.
- **`pairingSecret`** — 192-bit random, base32-encoded. See
  [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 7.

---

## 6. Threat-Model Mapping

Each endpoint pins to specific threat-model entries:

| Endpoint                      | Mitigates / supports                                  |
|-------------------------------|-------------------------------------------------------|
| `POST /sync/realm`            | Establishes I1 anchor (master public key for owner).  |
| `POST /sync/push`             | Auth via Phase 2.1 closes T-R-01, T-R-02, T-T-02.     |
| `GET /sync/changes`           | HLC tip piggyback closes I6 / T-T-04.                 |
| `POST /sync/pairing/request`  | Single-use TTL closes T-P-06 (replay).                |
| `POST /sync/pairing/response` | Master delegation in body closes Phase 3.3 invariant. |
| `POST /sync/pairing/response/peek` | Capability-by-secret-hash (F7, T-P-16); was `GET /sync/pairing/response?pairingSecret=`. Registers the joiner on delivery, so an unfetched response leaves no device in the GC quorum. |
| `GET /sync/pairing/offer-response` | Offerer poll (T-P-06); registers the offerer on delivery of an approval, like the peek (2026-10-28). |
| `POST /sync/pairing/hub-offer/fetch` | The hub's long-lived rendezvous key in the body, not a URI (T-P-17); was `GET /sync/pairing/hub-offer?hash=` (2026-10-28). |
| `POST /sync/pairing/ack`      | Fixes destructive-read footgun in §4.2 of relay plan. |
| `POST /sync/recovery-claim`   | Master-signed challenge closes recovery-spoof vector. |
| `GET /sync/recovery-challenge` | ownerId-blind (T-RC-08, F8): names no realm; per-IP (IPv6 /64) cap, `429` + `Retry-After`. (`/sync/recovery-blob` removed in F8.) |
| `GET /sync/realm-epoch`       | Closes T-K-02 recovery race (bounded relay-trust residual; see §3.6). Device-JWT since F8 (T-RC-08). |
| `PUT /sync/content/{ref}`     | Zero-trust opaque store (I1/I6); ciphertext-derived ref avoids the dedup/confirmation oracle (T-R-09). |
| `GET /sync/content/{ref}`     | Per-owner scoped; realises the access-pattern residual explicitly accepted in T-R-09 (v1). |

---

## 7. What's *Not* on the Relay Anymore

After this spec lands, the relay does **not** have:

- A device registry distinct from "the keypair I learned during pairing/recovery". No
  device names. No `isAuthorized` flags. No revocation lists.
- An `/identity/*` namespace. The `getDevices` / `authorizeDevice` / `revokeDevice` /
  `pair-device` endpoints from today's relay are deleted.
- An `/auth/login` endpoint or a `users` table. JWT auth replaces password auth.

Concretely deleted from `:sync-relay-server`:

- `POST /auth/login` and the `users` map.
- `POST /identity/pair-device`, `POST /identity/revoke-device`, `GET /identity/devices`.
- `POST /sync/fetch-property` — replaced by the generic `GET /sync/block/{id}`
  per §3.1. The corresponding sync-service operation `FetchOnDemandProperty` is
  renamed `FetchBlock` and takes a single block id rather than
  `(entityId, propertyId, params)`.
- `deviceRegistry: ownerId → MutableList<ByteArray>` (devices are not relay state).
- The `X-Owner-Id` / `X-Dev-Owner` header trust paths (subsumed by JWT `iss`).

Cross-references:

- [`docs/sync/design.md`](../sync/design.md) §2.2 +
  [ADR 0007](../adr/0007-sync-device-management.md) is the design that makes
  this possible (devices become synced entities; relay stops being the source of truth).
- [`docs/sync/design.md`](../sync/design.md) §2.6 owns the protocol semantics for
  `DeleteBlock` (design rationale in [ADR 0006](../adr/0006-sync-tombstones.md));
  this spec covers the relay-side operational story (§3.5).
- [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §3.3 (Phase 3.3) is
  *superseded* by this spec — its "Authoritative Device Registration" goal is met by
  master delegation in `/sync/pairing/response` + master self-signature in
  `/sync/recovery-claim`.

---

## 8. Implementation Plan

This spec doesn't ship by itself; it lands in lockstep with the synced
device-management work ([ADR 0007](../adr/0007-sync-device-management.md)).
Suggested phasing:

1. **Phase 0 — Reconcile docs.** _(LANDED.)_ Done as part of the synced
   device-management cluster — threat-model amended, status headers updated.
2. **Phase 1 — JWT auth (relay-plan §4.1).** _(LANDED.)_ `JwtAuthenticator` +
   `KnownDevicesStore` shipped; production mode requires JWT, dev mode accepts
   `X-Dev-Owner` header. Tests in `SyncPushAuthTest`, `SyncEndpointAuthTest`.
3. **Phase 2 — Realm bootstrap + pairing endpoints in the new shape.** _(LANDED.)_
   `POST /sync/realm`, `POST /sync/pairing/response` (with master delegation +
   `MasterDelegationVerifier`), `POST /sync/pairing/ack` shipped.
4. **Phase 3 — Long-poll on pairing endpoints.** _(NOT STARTED.)_ `wait` query
   parameter unimplemented; pairing endpoints currently short-poll only. Independent
   value; can ship anytime.
5. **Phase 4 — Recovery flow.** _(LANDED.)_ `GET /sync/recovery-challenge` and
   `POST /sync/recovery-claim` shipped 2026-05-04. `POST /sync/recovery-blob` and
   `GET /sync/recovery-blob` shipped 2026-05-05 (⚠ removed by F8, 2026-10-28 — §3.4) with `RecoveryBlobStore`,
   `MasterDelegationVerifier.recoveryBlobMessage` (signature over
   `sha256(ciphertext) || manifestVersion (8-byte BE)`), and replay-rollback
   protection via the store's monotonic-version compare-and-swap. JWT-required
   for upload; unauthenticated for fetch by design (the recovering device hasn't
   been admitted yet; KDF cost on the recovery-code-derived key caps the exposure
   per T-RC-02). `Bip39.decode()` already wired into `RecoveryClaim`.
6. **Phase 5 — Long-poll on `/sync/changes`.** _(NOT STARTED.)_ Optional; spec
   leaves it for SSE later. Polling cadence is currently fine.
7. **Phase 6 — Delete the legacy endpoints.** _(LANDED.)_ `/auth/login`,
   `/identity/pair-device`, `/identity/authorize-device`, `/identity/revoke-device`,
   `/identity/devices`, and `SyncMailbox.deviceRegistry` all deleted as part of
   the synced device-management work ([ADR 0007](../adr/0007-sync-device-management.md)).

_Not in the original phase plan but shipped 2026-05-04:_
**§3.5 Tombstones** — `POST /sync/push-tombstone` + `GET /sync/tombstones` with the
same JWT-auth + ownerId-on-block enforcement as `/sync/push`. Implemented as separate
`PushTombstone`/`GetTombstones` ops rather than folded into `PushBlock`/`GetChanges` —
see [ADR 0006](../adr/0006-sync-tombstones.md) for the typing-driven deviation rationale.

Phases 1–4 are blocking for a deployable Phase 4 of the relay plan (persistence +
deployment). Phases 5 and 6 are independent.

---

## 8b. Implementation Notes for Claude Code

A consolidated, file-level handoff. If something below contradicts §1–§8, §1–§8
wins; this section exists to remove ambiguity for an autonomous agent.

### Order across in-flight docs

This spec was co-implemented with the synced device-management work
([ADR 0007](../adr/0007-sync-device-management.md)):

1. `sync-backend-relay-plan.md` Phase 2.1 (JWT auth) — prerequisite.
2. *This doc* Phase 2 (`POST /sync/realm`, new `POST /sync/pairing/response`
   shape, `POST /sync/pairing/ack`) lands alongside the device-management work
   Phase 2.
3. *This doc* Phase 4 (recovery flow endpoints) — depends on
   [`bip39-decode.md`](bip39-decode.md). Independent of Phase 2 land order.
4. *This doc* Phase 6 (delete legacy endpoints) — gated on every supported
   client having migrated to the synced device-management flow
   ([ADR 0007](../adr/0007-sync-device-management.md) Phase 0 gate).
5. Relay-side tombstone GC (`SyncMailbox.gcSweep`) — independent of this
   spec's phases; depends on `sync-backend-relay-plan.md` §5.1 (HLC
   piggyback). Landed 2026-05-08; see [ADR 0006](../adr/0006-sync-tombstones.md).

### Server module: `:sync-relay-server` layout

Today's Ktor app structure (`:sync-relay-server`):

- `SyncMailbox.kt` — in-memory state (mailboxes, deviceRegistry,
  pairingRequests, pairingResponses, users).
- `SyncRouting.kt` — route definitions; one route per endpoint.
- `Application.kt` — Ktor server bootstrap; `configure*` helpers.

The new endpoints land alongside existing ones in `SyncRouting.kt`. Each
endpoint follows the existing pattern: route handler → SyncMailbox method
→ response. No architectural change.

### Files added (Phase 2 — realm + new pairing shape)

- `sync-relay-server/src/main/kotlin/.../routing/RealmRouting.kt` —
  `POST /sync/realm` handler.
- `sync-relay-server/src/main/kotlin/.../routing/PairingRouting.kt` *(extend
  existing)* — `POST /sync/pairing/ack` handler; rework
  `POST /sync/pairing/response` to require master delegation in the body.
- `sync-relay-server/src/main/kotlin/.../mailbox/RealmStore.kt` — new
  per-owner state: `Map<ownerId, RealmRecord>` carrying
  `(masterPublicKey, createdAt)`. RealmRecord is a small data class.
- `sync-relay-server/src/main/kotlin/.../mailbox/MasterDelegationVerifier.kt` —
  Ed25519 signature verification helper used by `POST /sync/pairing/response`
  and `POST /sync/recovery-claim`.

### Files added (Phase 4 — recovery flow)

- `sync-relay-server/src/main/kotlin/.../routing/RecoveryRouting.kt` — all
  four endpoints at the time (`/sync/recovery-blob` GET+POST, since removed by F8; `/sync/recovery-challenge`,
  `/sync/recovery-claim`).
- `sync-relay-server/src/main/kotlin/.../mailbox/RecoveryBlobStore.kt` —
  per-owner blob storage. `Map<ownerId, RecoveryBlob>` with manifest version.
- `sync-relay-server/src/main/kotlin/.../mailbox/RecoveryChallengeStore.kt` —
  per-owner challenge issuance with TTL.

### Files added (content plane; [ADR 0017](../adr/0017-file-content-two-plane-channel.md))

- `sync-relay-server/src/main/kotlin/.../routing/ContentRouting.kt` —
  `configureContentRouting`: `PUT`/`GET /sync/content/{ref}`. JWT-authed +
  per-owner scoped via the shared `requireOwnerId` helper, exactly like
  `/sync/push`. Octet-stream body in/out; the relay never parses it.
- `sync-relay-server/src/main/kotlin/.../mailbox/ContentBlobStore.kt` —
  per-owner opaque-blob store `Map<ownerId, Map<ref, (ciphertext,
  lastReferencedAt)>>` + the unreferenced-blob retention `gcSweep`. Mirrors
  `RecoveryBlobStore`'s zero-trust shape; in-memory (Postgres + object storage
  is `sync-backend-relay-plan.md` Phase 4, out of scope here).
- `Application.kt` / `RelayConfig.kt` / test `TestRelayModule.kt` — wire the
  store + a nightly `contentBlobStore.gcSweep` job (sibling of the tombstone
  `gcJob`; same application-scope lifecycle). New
  `CONTENT_GC_SWEEP_INTERVAL_MS`.
- Tests: `ContentBlobStoreTest.kt` (store + GC policy: round-trip, per-owner
  isolation, immutability, shared-blob survival, orphan reclamation, the
  `≥ tombstone-retention` invariant, idempotence) and `ContentRoutingTest.kt`
  (HTTP surface conforming to `RestContentBlobStore`'s wire contract: PUT/GET
  round-trip, 401 unauth, 404 missing, cross-realm denial, dev-mode parity, the
  P1-emit→relay→P2-read + GC composition).

The relay-side store conforms to the **already-merged** P1/P2 client
(`RestContentBlobStore.kt`) — paths/method/headers/body were read off the
client and matched, not invented. No P1/P2 client or crypto change (T-RC-02
frozen). The threat-model T-R-09 amendment co-landed (House Rule #2).

### Files modified (Phase 1 — JWT auth)

JWT auth is spec'd in `sync-backend-relay-plan.md` §4.1 and is a *prerequisite*
to this work; the file-level migration for that lives in the relay-plan doc,
not here. Once auth lands, every endpoint in this spec wires `JwtPrincipal`
verification at the route level.

### Endpoint ordering for staging deployment

For zero-downtime migration:

1. Land Phase 1 (JWT auth) — gates only new requests; existing flows unchanged.
2. Land Phase 2 endpoints in *parallel* with the legacy ones — clients gain
   the option to use the new shape but can fall back.
3. Migrate clients to the new shape (this is the synced device-management
   Phase 2 work, [ADR 0007](../adr/0007-sync-device-management.md)).
4. Phase 6 deletes the legacy endpoints.

Pre-deployment, steps 2–3 collapse to "just write the new code"; the staging
gate is documentation only.

### Wire-format precision

`ownerId` derivation: `base32(blake2b-256(masterPublicKey))` per §5.3. Pin this
in the first realm-bootstrap test fixture so client and server agree byte-for-byte.

`pairingSecret`: 192-bit random, base32-encoded per
[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) Step 7.
Defer to that doc for the canonical format.

`epoch`: a `TInteger` carried in `POST /sync/pairing/response` body. Realm
starts at epoch 0; rotates on revocation per [`docs/sync/design.md`](../sync/design.md)
§2.6 "Revocation is a tombstone".

### Long-poll mechanism

In Ktor, long-poll = the route handler suspends with a timeout. The shape:

```kotlin
get("/sync/pairing/response") {
    val secret = call.parameters["pairingSecret"] ?: return@get call.respond(HttpStatusCode.BadRequest)
    val wait = call.parameters["wait"]?.toLongOrNull() ?: 0L
    val deadline = clock.now().plusMillis(wait.coerceAtMost(60_000L))

    while (clock.now() < deadline) {
        val response = pairingResponses[secret]
        if (response != null) {
            call.respond(HttpStatusCode.OK, response)
            return@get
        }
        delay(100)  // poll the in-memory store; replace with a Channel-based wakeup later
    }
    call.respond(HttpStatusCode.NoContent)
}
```

Production-quality long-poll uses a `Channel` per pairing-secret keyed by
arrival of the response — `pairingResponses.put(secret, ...)` triggers a wake.
The polling-with-`delay(100)` shape is fine for the dev relay; tighten when
moving to a real deployment.

### Tests

- `sync-relay-server/src/test/kotlin/.../RealmRoutingTest.kt` —
  `POST /sync/realm` happy path; duplicate ownerId returns 409; bad master
  signature returns 400.
- `sync-relay-server/src/test/kotlin/.../PairingRoutingTest.kt` *(extend)* —
  `POST /sync/pairing/response` requires valid master delegation; missing or
  forged delegation returns 4xx.
- `sync-relay-server/src/test/kotlin/.../PairingLongPollTest.kt` —
  `wait=30s`; response arrives mid-wait, returned immediately; no response,
  204 after deadline.
- `sync-relay-server/src/test/kotlin/.../RecoveryClaimTest.kt` — challenge-
  response round-trip; valid master signature accepted; invalid rejected.
- Integration tests (existing `MultiProxySyncIntegrationTest` shape) — add
  realm-bootstrap and pairing-response paths.

### Tests deleted

- Tests against `POST /identity/pair-device`, `POST /identity/revoke-device`,
  `GET /identity/devices` (Phase 6).
- Tests against `POST /auth/login` (subsumed by JWT auth in
  `sync-backend-relay-plan.md` §4.1).

### Module dependencies

`:sync-relay-server` already depends on `:sync-service-common`. New
crypto-verification helpers (`MasterDelegationVerifier`) need an Ed25519
library — likely `BouncyCastle` already on the classpath via
`SyncCryptoManager`'s implementation. Verify with
`grep -i "bouncy\|ed25519" sync-relay-server/build.gradle.kts`; if missing,
add the existing JVM-side dep.

---

## 9. Out of Scope

- **OS-native push.** Documented in §4.2 as a deliberate non-goal for now. If we add it
  later, it lands as an adjunct that triggers the regular endpoints; the protocol
  itself doesn't change.
- **WebSocket support.** Same reasoning — overkill for the message patterns this spec
  has.
- **Quota / billing endpoints.** Tied to the App Console & Tenshu Store milestone; once
  those land, the relay enforces per-owner storage quotas, but the *enforcement*
  endpoint is separate from the *bulk traffic* endpoints in this spec.
- **Multi-region routing.** Single-region for now; Phase 4 of the relay plan handles
  deployment shape.
- **Public health / status endpoints.** Operational; covered by Phase 4.3 of the relay
  plan.

---

## 10. Open Questions

- **Block batching on push** — *Resolved: single block per request.* Partial-failure
  semantics are clean; revisit if measured catch-up time becomes a complaint.
- **Server-side pagination on `/sync/changes`** — *Resolved: ship in Phase 5.* See
  §3.1 "Pagination on `/sync/changes`" for cursor design.
- **First-device registration race.** Two clients calling `POST /sync/realm` for the same
  derived `ownerId` simultaneously. The deterministic `ownerId = hash(masterPublicKey)`
  derivation makes this impossible *unless two clients independently generate the same
  master keypair*, which is a 1-in-2^256 event. Document the edge case; no code needed.
- **Public key rotation** — *Deferred.* Circular trust problem (the relay needs to
  update its known-keys map from a synced block, but the block is signed by a key
  the relay needs to update). Size separately when there's a concrete need (lost
  laptop, prophylactic rotation). Tracked in `tech-debt.md`.
- **Selective delivery (recipient hints in block envelope)** — *Considered and
  rejected.* Eager/lazy split already covers most bandwidth needs; the metadata
  leak (relay learns who-talks-to-whom) isn't worth it. Revisit only if measured
  bandwidth cost becomes a real complaint. Tracked in `tech-debt.md`.
- **Cloud-only / device-side eviction.** *Deferred.* Protocol already supports it
  via `LazyBlock`; the missing piece is UX/policy. Size when there's a concrete
  archive use case. Tracked in `tech-debt.md`.
