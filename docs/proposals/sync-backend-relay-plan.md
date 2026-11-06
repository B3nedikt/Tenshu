# Tenshu Sync Relay: Backend Implementation Plan

**Status:** Phases 1–3 landed; **Phase 4 (persistence + deployment) has also largely landed**,
contrary to the May-2026 snapshot in §2 below — the relay runs durable SQLite stores
(`SqliteDeviceRegistry`, `SqliteContentBlobStore`) with S3-compatible blob storage, deployed by
the Terraform + cloud-init under `deploy/scaleway-relay/`. (`deploy/digitalocean/` is the retired
box it replaced; never `apply` it.)

⚠⚠ **Two things in this document are stale and are kept only as history.** §1 names a
Fly.io + Supabase Postgres stack that was never used — `sync-relay-persistence.md` is the
backend truth. And §3.1's endpoint table is the pre-device-management shape;
[`sync-relay-api.md`](sync-relay-api.md) is the canonical wire surface.

This document outlines the strategy for implementing the Tenshu Sync Relay — the central
component that brokers end-to-end encrypted data synchronization, pairing handshakes, and
recovery-blob storage between Tenshu-enabled devices.

The contract for what the relay must and must not learn lives in the
[Threat Model](../sync/threat-model.md). The protocol the relay carries during pairing lives in the
[Pairing Flow](../sync/pairing-flow.md). This document is the *engineering* plan to deliver them.

---

## 1. Vision & Architecture

The Sync Relay is an **encrypted mailbox**. It stores and forwards binary `SyncBlock` envelopes,
mediates a small number of stateful protocols (pairing, recovery), and refuses to look inside
any of them. It does not decrypt user data, it does not host user accounts, and it does not run
business logic. Compromising the relay must yield only ciphertext and the metadata the threat
model already classifies as exposed by design (asset A6, threat T-T-07, accepted in §10).

* **Hosting**: [Fly.io](https://fly.io), Dockerised Ktor server on Netty.
* **Database**: [Supabase](https://supabase.com) Postgres for block / device / recovery-blob
  indexing; Supabase Storage (S3) for large binary blobs.
* **Protocol**: Tenshu Binary Protocol over Ktor HTTP. HTTP/2 is fine but not required —
  nothing in the design currently exploits H/2-specific features.
* **Identity model**: device-key-signed JWTs for session auth (see Phase 2.1); master public
  key for user-level identity, referenced from Tenshu Rei records for entitlements
  (`milestones.md`).

The relay deliberately does not host: user accounts, payment data, plaintext PII, any
state that would make a relay breach worse than the threat model already accepts.

---

## 2. Status Snapshot (May 2026 — ⚠ aged; the header at the top of this file is current)

| Phase                                          | Status                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Phase 1 — Local Relay (foundation)             | **Largely landed**, then reshaped: legacy `/auth/login` and `/identity/*` endpoints + the `users` and `deviceRegistry` maps were deleted alongside the synced device-management work ([ADR 0007](../adr/0007-sync-device-management.md)). The relay's surface today is closer to `sync-relay-api.md` than to §3.1 below.                                                                                                                                                                                                                         |
| Phase 2 — Hardening                            | **Phase 2.1 (JWT auth) landed**. **Phase 2.2 landed**. Outstanding: relay-side rejection of pushes from revoked devices (depends on tombstones Phase 4 — itself unblocked by §3.1 ack-set tracking).                                                                                                                                                                                                                                                                                                                                             |
| Phase 3 — Protocol Completeness                | **All three sub-phases landed** 2026-05-05. §3.1 HLC Consistency Check (HLC-tip piggyback on `POST /sync/changes` + client-side `HlcDivergenceTracker` + `SyncManager.divergenceWarning` observable). §3.2 Recovery Blob Storage (`POST/GET /sync/recovery-blob` + `RecoveryBlobStore` + `MasterDelegationVerifier.recoveryBlobMessage`). §3.3 Authoritative Device Registration via [ADR 0007](../adr/0007-sync-device-management.md) (master-delegation chain through pairing + recovery). ⚠ The one downstream piece named here — UI rendering of the divergence warning — has since shipped: the desktop tray renders it via `ControlBridge.getSyncState`. |
| Phase 4 — Persistence, Deployment & Operations | ⚠ **This cell is stale — persistence and deployment largely landed.** Durable SQLite stores + S3-compatible blob storage, deployed by `deploy/scaleway-relay/` (formerly `deploy/digitalocean/`, now retired; not Postgres, not Fly.io — see `sync-relay-persistence.md`). Rate limiting and observability remain. Independent track; not blocking protocol completeness.                                                                                                                                                                                                                                                                                                                                                                                                          |

The Phase 1 endpoint table in §3.1 below describes the legacy shape (pre-device-management);
the canonical current relay surface is in [`sync-relay-api.md`](sync-relay-api.md). §3.1
is preserved for historical context.

Each phase below lists its current status, what it covers, the success criteria, and the
threat-model invariants it makes enforceable.

---

## 3. Phase 1 — Local Relay (foundation)

**Status: largely implemented.** The relay runs on `0.0.0.0:8080` and supports a desktop
client and a phone on the same LAN talking to it.

### 3.1 Endpoints implemented today

In `:sync-relay-server` (`SyncRouting.kt`, `SyncMailbox.kt`):

| Endpoint                       | Purpose                                                                                                                                                        |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /auth/login`             | Returns a dev session for an email/password. Auto-registers any email; password is stored plaintext. **Hardening target — 2.1 deletes this surface entirely.** |
| `POST /identity/pair-device`   | Registers a Device DTO under the `X-Owner-Id` header. **Hardening target — 2.1 + 3.3.**                                                                        |
| `POST /identity/revoke-device` | Removes a device from the local registry. Does **not** publish a signed revocation record. **Hardening target — 2.2 + 3.1.**                                   |
| `GET  /identity/devices`       | Lists registered device DTOs.                                                                                                                                  |
| `POST /sync/push`              | Stores a `SyncBlock`. Trusts `block.ownerId` from the client. **Hardening target — 2.1.**                                                                      |
| `GET  /sync/changes`           | Returns blocks since a given HLC.                                                                                                                              |
| `POST /sync/fetch-property`    | On-demand binary fetch for a single property of a single entity.                                                                                               |
| `POST /sync/pairing/request`   | Requester device posts its handshake parameters.                                                                                                               |
| `GET  /sync/pairing/requests`  | Host device polls for pending requests (now non-destructive).                                                                                                  |
| `POST /sync/pairing/response`  | Host posts the encrypted master key after approval.                                                                                                            |
| `GET  /sync/pairing/response`  | Requester device polls for the encrypted master key.                                                                                                           |

### 3.2 Storage

In-memory `ConcurrentHashMap`s (`SyncMailbox.kt`):

- `mailboxes: ownerId → MutableList<SyncBlock>`
- `deviceRegistry: ownerId → MutableList<ByteArray>` (Device DTOs)
- `pairingRequests: ownerId → MutableList<PairingRequestData>`
- `pairingResponses: pairingSecret → ByteArray` (destructive read on poll)
- `users: email → password` (plaintext; dev-only; deleted in Phase 2)

### 3.3 Local-network testing

To run two devices against a laptop relay:

1. **Server**: run `./gradlew :sync-relay-server:run` on the laptop. Default bind:
   `0.0.0.0:8080`.
2. **Find your laptop's LAN IP** (e.g. `192.168.1.50`).
3. **Client config**:

* Desktop client: point at `http://localhost:8080`.
* Android client: point at `http://192.168.1.50:8080`.

4. **Identity**: enter the same recovery phrase on both devices so they derive the same
   shared keys (or, post-pairing-flow integration, pair them through the relay).

### 3.4 Success criteria

* Uploading a file on Desktop is visible in the file list on the phone.
* The pairing flow completes end-to-end through the relay.
* Tests in `:sync-relay-server:test` pass.

### 3.5 Known limitations carried into Phase 2

The phase-1 baseline is intentionally trust-everything; every limitation here has a hardening
target in Phase 2. They are listed in §4 (Phase 2.2) so they are tracked in one place.

---

## 4. Phase 2 — Hardening

**Status: not started. Gating for any non-local deployment.** Without 2.1 and 2.2, persistence
in Phase 4 would only cement security holes rather than data.

### 4.1 Phase 2.1 — Authentication (Device-Key-Signed JWTs)

The relay does not have user accounts. It has **device identities**. Each device already holds
an Ed25519 signing key (asset A4 in the [Threat Model](../sync/threat-model.md)) generated during
pairing or recovery, and the relay already needs to know each device's public key (T-R-02).
The natural auth design is to reuse that primitive:

* **Wire format**: short-lived JWT signed with the device's Ed25519 key (alg `EdDSA`).
* **Claims**: `iss` = ownerId, `sub` = deviceId, `iat`, `exp` (≤ 5 minutes), `nonce` for
  state-changing endpoints.
* **Server verifier**: `ktor-server-auth-jwt` plugin with a custom key resolver that looks up
  the device's public key from the relay's known-devices store. The expected algorithm is
  pinned to `EdDSA` at verifier construction (no `alg=none`, no algorithm-confusion).
* **Replay window**: bounded by `exp`. State-changing endpoints (`POST /sync/push`, pairing
  endpoints) verify the request body against `nonce` to prevent intra-window replay.

This design fits the Zero-Trust Backend principle: a relay breach yields the same thing T-R-02
already accepts (device records and public keys), and the relay never holds long-term secrets
it could leak.

**Device identity vs. user identity.** A device-key JWT authenticates a *session*, not a user.
User-level identity is the **master public key** (asset A1 in the
[Threat Model](../sync/threat-model.md)), which is recoverable, persists across all of a user's
devices, and is the identifier referenced by Tenshu Rei records — including future
entitlements like storage-tier purchases, premium features, and any other items the user buys
through the App Console / Tenshu Store. Device keys are admitted to the realm by a
master-identity-signed delegation during pairing; the relay verifies that delegation chain
when it first sees a device, then stores the device public key and the owner's master public
key together. The mechanism by which purchase / quota grants reach the relay is sized as part
of the App Console & Tenshu Store milestone — see [`milestones.md`](../milestones.md).

**Why this instead of Keycloak / OIDC / a custom auth layer:**

* Keycloak presupposes an identity provider managing user accounts (passwords, social login,
  MFA). Tenshu's identity model is the user's recovery phrase plus the master key minted from
  it; there is no separate IdP, and Keycloak would be vestigial.
* Rolling our own session/token format would be the dangerous kind of "custom auth." Using
  JWT (RFC 7519) with Ed25519 (RFC 8032) via standard libraries is *not* custom auth — it's
  standard auth without an extra server.

**Dev / test story.** A hard requirement; the design is shaped around making it cheap.

* **Unit tests of the relay**: a test fixture pre-populates the relay's known-devices map with
  `(deviceId → publicKey)`. The test signs JWTs with the matching private key. Pure JCA /
  Ed25519, no network.
* **Integration tests across the relay-device boundary**: identical shape to production. The
  test "device" generates an Ed25519 keypair in `@BeforeTest`, mock-pairs (or inserts the
  public key into the relay's store directly), and issues real signed JWTs. No mocking of the
  auth layer at all — same code path as production.
* **Local dev without going through pairing**: a single config gate `relay.dev_mode=true` that
  accepts an `X-Dev-Owner` header instead of a JWT. Strictly off in production: the gate
  refuses to start the server if `dev_mode=true` is combined with a non-localhost bind
  address. This is a debug bypass, not a second auth method.

**Success criteria for 2.1:**

* All endpoints reject requests with no JWT / wrong-algorithm JWT / expired JWT.
* `POST /sync/push` rejects when `block.ownerId` ≠ JWT `iss`.
* All four pairing endpoints accept only correctly signed JWTs from devices already in the
  realm (or, for the requester side, only when the pairing secret matches the host's active
  invitation).
* `relay.dev_mode=true` + non-loopback bind refuses to start.
* Integration test exercises a full request → JWT-verified → response cycle with no auth
  mocking.

### 4.2 Phase 2.2 — `SyncMailbox` Hardening

The current `SyncMailbox` was written as a Phase-1 stub. Each issue below has a concrete fix
and a test instruction. Items marked **(subsumed by 2.1)** are removed entirely once auth
lands.

* **`/auth/login` plaintext password auto-register, `users.putIfAbsent(email, password)`.**
  Subsumed by 2.1. The whole `email/password` surface — `users` map, `authenticate(...)`,
  `/auth/login` route — is deleted.
* **`X-Owner-Id` header is trusted, falls back to `"user-1"`.** Subsumed by 2.1: ownerId
  comes from the verified JWT's `iss` claim.
* **`/sync/push` trusts `block.ownerId` from the client.** Subsumed by 2.1: cross-checked
  against JWT `iss`, mismatch is a 4xx. Add a test for the mismatch case.
* **`revokeDevice` removes from the local registry but does not publish a signed revocation
  record.** Cross-references threat-model I4 and T-K-01. Implementation: take a revocation
  record signed by an un-revoked realm device, persist it, propagate it to all other realm
  devices via `/sync/changes`, and reject any subsequent `/sync/push` whose JWT identifies
  the revoked device.
* **`pairingResponses.remove(...)` is destructive on read.** A requester device that crashes
  between fetching and processing the response loses the pairing. Switch to a separate ack
  mechanism with a server-enforced expiry (5 minutes per the pairing flow).
* **Non-atomic `getOrPut` on `mailboxes`, `deviceRegistry`, `pairingRequests`.** Two threads
  pushing for the same owner can race past the `getOrPut` check before either inserts; the
  second inserts a fresh `mutableListOf`, the first thread's reference points at a list no
  longer in the map. Switch to `ConcurrentHashMap.computeIfAbsent`. Add a concurrent-push
  test that hammers the same owner from multiple coroutines.
* **Dead `configureSyncMailbox()` and exception leak via `"500: $cause"`.** Housekeeping:
  delete the unused entry-point in `Application.kt`, and replace the `"500: $cause"`
  serialisation in the `StatusPages` handler with a typed error response that does not echo
  internal exception text to clients (that text can leak stack frames, file paths, and
  library versions to an attacker probing for vulnerabilities). Same for the
  `e.printStackTrace()` calls inside route handlers.

**Success criteria for 2.2:**

* No plaintext password storage anywhere in the relay.
* Concurrent-push test passes (no dropped blocks for a single owner).
* Revoking a device from one realm device causes another realm device to refuse subsequent
  blocks signed by the revoked device, within the consistency-check cadence.
* No internal exception text reaches clients; `StatusPages` responds with a typed error code.

---

## 5. Phase 3 — Protocol Completeness

**Status: not started. Depends on Phase 2.** Each item makes a specific threat-model
invariant enforceable rather than aspirational.

### 5.1 Phase 3.1 — HLC Consistency Check (Invariant I6) _(LANDED 2026-05-05)_

> ⚠⚠ **This slice's core assumption — one HLC stream per `deviceId` — does not hold on iOS or
> web, and the detector false-positives there by construction.** The tip is built from
> `HLCManager.current()`, which is a *process's* clock, while the relay keys one slot per
> *device*: iOS route-1 runs a runtime per first-party app under one shared-Keychain deviceId,
> and two web tabs of one origin share the IndexedDB-persisted deviceId. Diagnosed live
> 2026-08-28 (a 16-minute backward tip from an iPhone pinned the desktop tray's red triangle for
> ~50 minutes with sync fully healthy). Root cause, exposure matrix, why the one-line fix is
> wrong, and phases TC-1…TC-3 in
> [`sync-hlc-tip-device-identity.md`](sync-hlc-tip-device-identity.md). **TC-1 (the surface half)
> has landed**: the warning now carries a first-seen stamp and its peer, the tray names the
> device instead of saying "Sync issue detected", and a warning no longer holds the headline
> state indefinitely. The detector itself is unchanged — TC-2 is the fix.

* **Wire shape:** `POST /sync/changes` (was `GET`; switched because GET-with-body is
  fragile across proxies). Body carries a binary-serialised `SignedHlcTip` from the
  requesting device. Response is a `ChangesResponse { blocks, peerTips }` wrapper —
  every other realm device's last-known signed tip rides back with the blocks. Same
  JWT auth as `/sync/push`; tip's deviceId/ownerId pinned to the JWT claimant; tip
  signature verified against `KnownDevicesStore` (skipped in dev-mode `X-Dev-Owner`
  bypass for legacy fixtures).
* **Server**: `HlcTipStore` (per-owner per-device latest tip, in-memory, will move
  to Postgres in Phase 4). `MasterDelegationVerifier`-parallel `hlcTipCanonicalMessage`
  helper in `:sync-service-common` so client and server share the byte-level signed-
  bytes definition.
* **Client**: `SyncScheduler.pullChanges` builds the tip from `HLCManager.current()`,
  signs with the device's A4 key, sends; processes peer tips through an injected
  `onPeerTip` sink. `HlcDivergenceTracker` is the production sink — verifies each
  tip's signature against the peer's `Device.publicKey` (looked up from the local
  synced collection), tracks `Map<deviceId, highestSeenHlc>` in memory, emits
  `DivergenceWarning.SignatureMismatch` on a forgery and `Regression` when a peer
  tip's HLC goes backward (the "forked view" T-T-04 attack tell).
* **Observable surface**: `SyncManager.divergenceWarning: StateFlow<Any?>` (the
  concrete type is `org.tenshu.sync.proxy.DivergenceWarning`, kept as `Any?` because
  the proxy-side type isn't visible to `:sync-service-common`). UI consumers downcast
  and observe; no UI surface ships in this slice.
* **Tests**: `HlcTipStoreTest`, `SyncChangesPiggybackTest`, `HlcDivergenceTrackerTest`,
  `HlcPiggybackPropagationTest` (cross-proxy end-to-end).

**Carve-outs:**

- Persistent `highestSeenByDevice` across restart not yet (in-memory only — accepts
  the next tip as new floor after restart). Tracker won't false-positive but loses
  prior history; persistence is a follow-up if regression detection needs to survive
  restarts to catch slow-rolling attacks.
- UI rendering of the warning is downstream — this slice surfaces the observable
  state; the `SyncDashboardScreen` (or apex equivalent) wires the actual notification.

### 5.2 Phase 3.2 — Recovery Blob Storage (Invariant I7)

The recovery flow described in [`threat-model.md` §11.1](../sync/threat-model.md) and the
[Tenshu White Paper](../whitepaper.adoc) needs the relay to host an encrypted backup blob
per owner. Endpoints:

* `POST /sync/recovery-blob` — upload (or replace) the encrypted blob. Authenticated by JWT;
  the blob itself is opaque ciphertext encrypted under a key derived from the recovery code.
* `GET /sync/recovery-blob` — download the latest blob for a given owner. Authentication is
  via a recovery-mode JWT signed by the master key the user just derived from their typed
  recovery phrase (this is a different signer from the device-session JWT and gates only the
  recovery endpoint).
* Retention: when the user edits their recovery manifest (T-RC-05), the relay re-uploads.
  The relay should garbage-collect superseded blobs but cannot guarantee secure erasure;
  this caveat is already acknowledged in the threat model and surfaced to the user via the
  control app.

**Success criteria:** A test in which the user generates a recovery code on device A,
"loses" device A, freshly installs on device B, types the recovery code, and re-derives the
master identity from the downloaded blob.

### 5.3 Phase 3.3 — Authoritative Device Registration

Today's `POST /identity/pair-device` registers any Device DTO under the header-supplied
ownerId. After Phase 2.1 the relay knows about devices via the verified JWT chain, but
**how new devices first join the registry** still needs to be tightened:

* Pairing (existing realm device admits a new one): the host signs a master-identity
  delegation for the new device's signing key, the new device's first interaction with the
  relay carries this delegation, the relay verifies the master signature against the owner's
  known master public key, and only then is the device added to the registry.
* Recovery (no existing realm device): the new device proves possession of the master
  identity by signing a fresh challenge with the master key derived from the recovery code.

This is the seam between [`pairing-flow.md`](../sync/pairing-flow.md) and the relay-side device
store. Today the relay accepts the DTO on faith.

**Success criteria:** A device cannot register without either (a) a master-identity-signed
delegation, or (b) a master-identity-signed challenge response from the recovery flow.

---

## 6. Phase 4 — Persistence, Deployment & Operations

**Status: not started.** Lands on top of an authenticated, hardened, protocol-complete relay.

### 6.1 Phase 4.1 — Persistence

* **Database**: Postgres via Supabase to store:
    * `SyncBlock` metadata (block id, owner, HLC, target entity / property, blob URL).
    * Device registry (deviceId → publicKey, ownerId, masterDelegationSignature, createdAt,
      revokedAt).
    * Recovery-blob index (ownerId → S3 URL, version, uploadedAt).
    * Pairing state with TTL (5-minute pairing-flow expiry).
* **Blob Storage**: Supabase Storage (S3) for large payloads (image bytes); Postgres holds
  only the S3 URL.
* **Migration discipline**: every schema change must be reversible; the threat-model §10
  list of metadata exposures is the canonical reference for what fields are and are not
  allowed to live on the relay.

### 6.2 Phase 4.2 — Rate Limiting

A relay open to the internet, addressed by anonymous device keys, must rate-limit per-device
and per-owner to prevent abuse and runaway billing.

* Per-device: bound on `POST /sync/push` requests/second and total bytes/minute.
* Per-owner: aggregate cap across all of the owner's devices.
* Per-IP: a fallback for unauthenticated paths (only `/auth/login` survives Phase 2 as a
  dev-mode-only path, and even that should be rate-limited).
* Quota integration: the per-owner cap reflects the owner's storage tier as resolved through
  the App Console / Tenshu Store quota mechanism (see milestones.md).

### 6.3 Phase 4.3 — Observability

* **Metrics**: request rates, latency, error rates per endpoint, mailbox / device-registry
  / recovery-blob sizes, pairing-flow completion rate, JWT verification failure rate.
* **Logging**: structured logs that adhere to threat-model §10 — never log block payloads,
  never log device names alongside content, never log full request bodies.
* **Alerting**: error-rate thresholds, JWT-verification-failure spikes (potential attack),
  pairing-flow expiry rates (potential attack or genuine UX issue).

### 6.4 Phase 4.4 — Deployment

* **Fly.io**: `Dockerfile` for the Netty Ktor server, deployed to a region close to the user.
* **TLS**: Fly.io provides this; the threat model treats TLS as availability-only, but it
  still has to be present.
* **Config schema**: enforce at startup that `relay.dev_mode=true` is only legal with a
  loopback bind address. Refuse to start otherwise.
* **Secrets**: the relay holds no long-lived secrets (per Phase 2.1's design), so the secret
  surface is small — JWT verification keys are *public*; the only real secret is the database
  connection string. Fly.io secrets manager is sufficient.

---

## 7. References

- [Threat Model](../sync/threat-model.md) — security contract; every phase's success criteria should
  preserve the invariants listed there.
- [Pairing Flow](../sync/pairing-flow.md) — the protocol the relay's pairing endpoints support.
- [Pairing Flow Improvements](sync-pairing-flow-improvements.md) — the planned hardening of the
  pairing flow itself; informs Phase 2.1 and Phase 3.3.
- [`milestones.md`](../milestones.md) — where the App Console & Tenshu Store sit, and where
  the quota / entitlement integration story lives.
