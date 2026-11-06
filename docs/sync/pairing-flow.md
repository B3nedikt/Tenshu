# Device Pairing Flow: Active Session Approval

This document describes the end-to-end encrypted (E2EE) device pairing process used to add new
**realm devices** to a user's realm — the cryptographic membership set of devices that share the
user's master identity and volume keys. This is distinct from the app-to-proxy pairing used by
external clients (e.g. iOS apps) to attach to a single proxy; that handshake establishes a private
channel without granting realm membership.

This doc is the **protocol-level** spec. The user-facing screen flow (what each side sees during
each handshake step, the SAS / fingerprint comparison, error paths, recovery) lives in
[`docs/proposals/control-onboarding-ux.md`](../proposals/control-onboarding-ux.md) §4a (requester
side) and §6
(host side). The protocol-hardening items in
[`pairing-flow-improvements.md`](../proposals/sync-pairing-flow-improvements.md) are treated as
in-scope by the
UX doc.

## 1. Overview

Pairing is a synchronous, two-way handshake performed via a secure relay server. It uses ephemeral
keys and physical proximity (QR scanning) to safely transfer the user's Master Identity and Volume
keys to a new device.

## 2. The "Active Session" Concept

To maximize security and reliability, pairing is **session-bound**. Ephemeral private keys required
for the handshake are stored only in memory and are discarded as soon as the pairing dialog is
closed.

Pairing runs in one of two **directions**, which are cryptographically symmetric (same X25519 /
HKDF / XChaCha20-Poly1305 / SAS) and share every crypto helper:

* **Join flow** (§3) — the **existing** device displays the QR, the **new** device scans it. This
  is the default and the only direction on native today.
  * **Host (Device A)**: An existing realm device that displays the QR code.
  * **Requester (Device B)**: The new device that scans the QR code to join.
* **Offer flow** (§4) — the **new** device displays the QR, an **existing** device scans it. The
  ergonomically natural direction on web (laptop shows, phone scans) and the only direction a
  brand-new browser can drive (it has no `ownerId`/`hostDeviceId` to put in a join QR).
  * **Offerer (Device B)**: The new device that displays the QR and waits.
  * **Responder (Device A)**: An existing realm device that scans and approves.

The scanned QR's **role host** (`tenshu://join?…` vs `tenshu://offer?…`) disambiguates which flow a
scanner drives — one camera, one mental model ("point at the other device's code"). The retired
legacy `tenshu://pair?…` host and unknown hosts fail loudly with a "regenerate / update your app"
prompt, never a partial parse.

---

## 3. Step-by-Step Handshake (Join Flow — existing device shows QR)

### Step 1: Host Invitation

Device A generates a `PairingContext` containing:

1. **Ephemeral Key Pair**: A short-lived ECC key pair.
2. **Pairing Secret**: A high-entropy random string.
3. **QR Code**: the canonical `tenshu://join?o=<ownerId>&d=<hostDeviceId>&k=<hostPublicKey>&s=<secret>`
   URI (base32 fields; the `PairingUri` helper in `:core-common` generates + parses it). The
   `ownerId` + `hostDeviceId` are values only an already-provisioned device holds — which is why a
   brand-new device can't emit a join QR and must use the offer flow (§4) instead.

Device A then starts polling the `SyncRelay` for incoming `PairingRequest` objects that match its
active secret.

### Step 2: Requester Scan

Device B scans the QR code and:

1. Decodes the parameters (Public Key A, Secret).
2. Prompts the user for a **Device Name**.
3. Generates its own **Ephemeral Key Pair**.
4. Posts a `PairingRequest` to the relay containing:
    * `deviceId` (Device B) and its device name **sealed** — `sealedDeviceName`, see below.
    * **Public Key B** and B's Ed25519 signing key.
    * `pairingSecretHash` (to prove it scanned the QR) — **never the secret itself**.

Device B then starts polling for a `PairingResponse` (`POST /sync/pairing/response/peek`, the hash
in the body — never in a URI).

> **What the relay sees of a pairing (F7, 2026-10-28; threat model T-P-16).** `PairingRendezvous`
> (`:sync-service-proxy`) derives, from the QR's secret, two values on each side:
>
> ```
> pairingSecretHash = hex(SHA-256("tenshu/pairing/join/rendezvous/v1" || 0x00 || utf8(secret)))
> K_name            = HKDF-SHA-256(ikm = utf8(secret), salt = ∅,
>                                  info = "tenshu/pairing/join/device-name/v1", L = 32)
> sealedDeviceName  = AEAD(K_name, be16(len) || name || 0-pad to 64 B,
>                         aad = lp(pairingSecretHash) || lp(deviceId) || lp(publicKey) || lp(signingPublicKey))
> ```
>
> The AAD binds both keys the request carries, so a relay cannot repost a genuine sealed name
> under keys of its own: that copy does not open and is dropped before the host pins it.
>
> The hash keys every join route — the request inbox, the request list, the response, its peek and
> its ack. The host filters the list by its own session's hash and unseals each name with the
> session secret; a name that does not open is dropped like an underivable-SAS request. The SAS v2
> transcript is over the **clear** name, so a relay — which cannot even read the name — also cannot
> change it unnoticed. Requests and responses live 7 minutes on the relay, and an acked response
> retires its hash for 24 hours (the offer flow's TTL and consumed-marker shape). The EPC rendezvous
> uses a separate secret; nothing here feeds `deriveEpcRendezvousSessionId`.
>
> ⚠ **Name length (2026-10-28).** The relay refuses a sealed name over
> `PairingWireLimits.MAX_SEALED_DEVICE_NAME_BYTES` (1 KiB, `:core-common` — one constant for both
> sides), and `encryptWithAad` carries the AAD *inside* that ciphertext, so the name's budget is
> what is left: ≈766 UTF-8 bytes for a UUID `deviceId`. The joiner (and a hub) **truncate** the
> platform device name to the budget on a code-point boundary *before* the SAS, the stored name and
> the seal consume it (`PairingRendezvous.fitJoinDeviceName` / `fitHubDeviceName`) — it is a display
> label, and a shortened one beats an opaque relay `400`.

### Step 3: Host Authorization (The "Active Approval")

Device A receives the `PairingRequest` via polling.

1. The QR code UI is instantly replaced with an **Authorization Prompt**:
   > "Device 'John's iPad' wants to join your realm. [Approve] [Deny]"
   The name shown is the one the host unsealed from `sealedDeviceName` with its session secret.
   ⚠⚠ **What is shown is pinned.** When the poll derives the SAS for a request, the host records
   on its live pairing context the tuple that code stands for — `deviceId`, `PublicKey B`, the
   Ed25519 signing key and the device name — and serves the prompt *from that pin*. First shown
   wins for the life of the session: a later request claiming the same `deviceId` under different
   keys is withheld, and a changed name is ignored. Threat model T-P-14.
   ⚠⚠ **The code covers the joiner's identity (SAS v2, 2026-10-03).** Both sides derive the join
   SAS as HKDF-SHA-256 over the shared secret with salt `PublicKey A ‖ PublicKey B` and info
   `"tenshu/pairing/sas/v2" ‖ lp(signing key) ‖ lp(deviceId) ‖ lp(device name)` — the joiner over
   what it posts, the host over what it pins — so the relay cannot change the signing key, id or
   name without changing the host's code. **Lockstep:** no version field, no v1 fallback; a v1/v2
   pair shows different codes, and every join-flow code screen says to update both devices.
2. **On Approve** (which carries only the `deviceId`):
    * Device A takes the request **from the pin, never from the relay** — approve makes no
      request-list fetch. No pin for that `deviceId` in the current session (no SAS was shown for
      it) → refused, before anything is sealed or signed. Until 2026-10-02 this step re-fetched the
      list and used the first entry with a matching `deviceId`, so the keys admitted were not
      bound to the code the user had compared.
    * Device A calculates a **Shared Secret** using its `PrivateKey A` and the pinned `PublicKey B`.
    * Device A encrypts its **Master Identity Keys** using this shared secret.
    * Device A posts a `PairingResponse` containing the encrypted keys.
    * The post result is **checked** (an unchecked failed post would look identical
      to success, leaving B polling a mailbox that never fills). On failure Device A retries
      once; if the retry also fails it throws loudly. Before that retry, a Device A that holds
      the master identity (A1) re-asserts the realm genesis via the existing master-capability
      `POST /sync/realm` (mirroring `ReregisterDevice`, no new authority). This self-heals a relay
      that has forgotten the realm (in-memory restart / redeploy without durable storage),
      which the `RealmRegistrar` once-ever latch otherwise prevents recovering from
      (see [`design.md`](design.md) lazy first-device registration). A delegated (A33-only)
      admitter cannot re-bootstrap, so its retry is the plain re-post (since 2026-10-28; before,
      it did not retry at all, and a lost reply failed an approve whose joiner was paired).
    * ⚠⚠ **One admission per pin, and "consumed" can mean delivered (2026-10-28).** The admission
      material (sealed blob, delegation, the joiner's fresh A33 capability) is minted on the first
      approve of a pin and reused by every later approve of it in the session
      (`PairingContext.admissionFor`; only sealed and public material is kept). The relay answers
      `409 pairing-response-consumed` once the joiner acked the hash; Device A reads that as
      **delivered** only when every earlier post under the session's hash was this pin's
      (`PairingContext.recordResponsePost`) — then the joiner holds exactly the capability the
      `Device` row records. Otherwise (a first-ever post, or another `deviceId` posted first) it is
      a failure. Before, each approve minted fresh material, and an approve whose retry met an
      earlier approve's ack recorded a capability the joiner never received.
    * The relay **verifies** the post (delegation, revocation, removal ledger, consumed marker)
      but **registers** B's signing key only when B first fetches the response (peek), before the
      bytes are served. A response that expires unfetched therefore leaves no registered device
      behind — such a device never acks a tombstone and would block the realm's tombstone GC
      quorum for good (2026-10-28; before, the post registered).
3. **On Deny**: The request is discarded, and Device B remains in a waiting state until timeout.

### Step 4: Requester Completion

Device B receives the `PairingResponse`.

> **Poll-failure semantics.** The completion poll distinguishes a genuine 404
> ("response not posted yet" — keep polling) from a transport failure (network / CORS / 5xx),
> which previously was folded into "still pending" and masked for the full ~5-minute budget.
> Both the `AwaitPairing` loop and the JS `buildPairingSession` poll loop tolerate a bounded
> run of consecutive failures (a transient blip mid-handshake doesn't abort an active
> pairing) and surface the error once the relay stays unreachable.

1. Device B calculates the same **Shared Secret** using its `PrivateKey B` and `PublicKey A`.
2. Device B decrypts the **Master Identity Keys**.
3. Device B initializes its local `SyncManager` and `SyncCryptoManager`.
4. Pairing is complete. Device B is now a realm device.

### Step 5: Device entity blocks (post-master-handoff)

Once the master key arrives at Device B, both sides write `Device` entity blocks
to the realm. This is what makes the new device visible to itself and to every
other realm device through ordinary block sync (no relay-side device registry —
see [`design.md`](design.md) §2.2):

1. **Device B** self-pushes a `Device { isAuthorized = true, publicKey = …, name = … }`
   block. Signed by B's own A4 key (which `KnownDevicesStore` already holds —
   the relay accepted B's pairing response, which required Device A's master
   delegation to clear `MasterDelegationVerifier`, and registered the key when B
   fetched it, so B is authoritatively admitted at the moment the master key arrives). `isAuthorized = true` on
   the self-push is correct: every other realm device verifies the block
   through the master-delegation chain rather than trusting the field value
   directly, so B writing `true` locally doesn't introduce a forgery surface.
2. **Device A** also authors a peer `Device { isAuthorized = true, … }` block
   for B from the `PairingRequest` payload (this used to be a separate "host
   flips" step; it now happens during Approve).

Field-level LWW resolves both sides' blocks. They agree on `isAuthorized = true`;
HLC tie-breaks anything they disagree on (`admittedAtEpoch` — A's value wins as
authoritative).

The "Invisible Self" bug — where Device B's own dashboard (which filters by
`isAuthorized`) hid B from itself for the round-trip window before A's block
landed — does not recur with this ordering. The earlier "self-push false, then
host flips true" shape was the cause; it's gone.

---

## 4. Inverted Direction: The Offer Flow (new device shows QR)

The offer flow is the join flow with the QR direction reversed: the **new** device (Offerer,
Device B) displays the QR and an **existing** realm device (Responder, Device A) scans it. It reuses
every crypto primitive of §3 unchanged — the only new pieces are the QR role host and a
secret-keyed relay rendezvous. Graduated from `sync-pairing-inverted-direction.md` (P1–P5).

**Why it exists.** A brand-new browser has no `ownerId`/`hostDeviceId`, so it *cannot* generate a
join QR — it can only ever scan. But scanning is the awkward half on web (webcam-permission UX; you
can't comfortably hold a phone showing a QR between your eyes and the laptop's webcam). The natural
web ergonomics are the inverse — the laptop displays, the phone scans.

### 4.1 The offer QR payload

```
tenshu://offer?k=<PublicKeyB>&g=<SigningKeyB>&d=<offererDeviceId>&s=<pairingSecret>&n=<deviceName>
```

`PublicKeyB` = the offerer's ephemeral X25519 key; `SigningKeyB` (`g=`) + `offererDeviceId` (`d=`)
let the responder register the offerer on the relay when it approves (without them the offerer's
first JWT-authenticated sync would 401). Deliberately **omitted** vs the join QR: `o` (`ownerId`)
and the host device id — the offerer doesn't know them; the responder contributes them after the
scan.

### 4.2 Handshake

1. **Offer (Device B).** B generates an ephemeral X25519 keypair + a 128-bit `pairingSecret`,
   computes `pairingSecretHash = SHA-256(pairingSecret)`, displays the offer QR, and polls
   `GET /sync/pairing/offer-response?hash=<pairingSecretHash>`. `privB` lives in memory only.
2. **Scan (Device A).** A scans B's QR *optically* (the anti-MITM channel — the relay never sees
   it), decodes the fields, and generates its **own** ephemeral X25519 keypair.
3. **Approve (Device A).** A derives `sharedSecret = X25519(privA, PublicKeyB)` and the SAS, shows
   the admit prompt ("Add '<deviceName>' to your realm? Confirm `482913` matches."), and on
   **Approve** seals the same `PairingPayload` as the join flow (master identity + realm epoch +
   volume keys) plus a master/A33 delegation admitting the offerer, then POSTs an **offer-response**
   (`POST /sync/pairing/offer-response`). On **Deny** it POSTs a signed `denied` offer-response.
4. **Complete (Device B).** B's poll returns the offer-response; B derives the same `sharedSecret`,
   decrypts the payload, verifies the recovered **master public key hashes to the `ownerId`** in the
   response (the trust anchor), installs the keys, and self-pushes its `Device{isAuthorized=true}`
   block. A also authors the peer `Device` block for B. Completion is identical to §3 Step 5.

### 4.3 Relay surface

A **`PairingOfferStore`** keyed by `pairingSecretHash` (one blob, ~5-min-plus-grace TTL) behind two
endpoints under the existing `/sync/pairing` route:

- `POST /sync/pairing/offer-response` — `{ownerId, pairingSecretHash, status, publicKeyA, payload?,
  responderDeviceId, hostSignature}`. The relay verifies `hostSignature` against the realm's
  `KnownDevicesStore` (reuses `MasterDelegationVerifier`), rejects an already-consumed hash → `409`
  (single-use via `ConsumedPairingSecretsStore`), verifies the offerer's admission (delegation,
  revocation, removal ledger), and stores the blob **with the offerer's registration queued on it**.
- `GET /sync/pairing/offer-response?hash=<pairingSecretHash>` applies that registration — after
  re-checking the removal ledger (`403 device-removed`) — *before* serving an approval, exactly like
  the join flow's peek (§3 Step 3). An approval that expires unfetched therefore registers nothing:
  such a device would never ack a tombstone and would block the realm's tombstone-GC quorum for good
  (2026-10-28; before, the post registered). The queued registration lives on the terminal row
  (`pairing_offer_response.reg_*`, durable), so account erasure drops it with the row. While it
  waits, its `admittedAt` still orders `Device` tombstones (`DeviceRevocation`'s pending lookup, both
  flows): an older tombstone delivered in the window is stale, as when the post registered. The poll is
  otherwise unauthenticated
  (the offerer has no credentials yet); it returns only AEAD ciphertext + `publicKeyA` + `ownerId`,
  so House Rule #1 holds.

The relay carries **one direction only** here (A→B): the QR carried everything A needed optically.
See [`docs/sync/threat-model.md`](threat-model.md) T-P-06..08 for the offer-flow threat deltas
(notably: the passive-QR-observer risk *closes* for confidentiality — the observer gets `PublicKeyB`
+ `secret` but never `privB`, so cannot decrypt the payload).

### 4.4 Client surfaces

- **Web (offerer):** `Pairing.offer(deviceName)` (`:tenshu-sdk-js` + `@tenshu/sdk`) → an
  `OfferPairingSession`; the apex onboarding "Show a code" tab renders the QR via `QrDisplay`.
- **Native (offerer, 2026-07-17):** the unified "Add a device" screen's **Show** tab is
  role-branched on realm binding (`PairingWizardViewModel.onSelectShow`): a **paired** device
  hosts the origin `tenshu://join` QR (the `HostPairingMonitor` session, §3); an **unpaired**
  device takes the offerer role — `OfferPairing` → the `tenshu://offer` QR + a 2 s
  `GetOfferStatus` poll → `approved` (keys installed; SAS shown for post-hoc comparison) /
  `denied` / cancel-on-leave. The branch is taken only on a *successful* status read reporting
  no owner, so a transient failure can never mint an offer session over a live realm. (Before
  this, the Show tab on an unpaired device armed the host session, whose owner-less
  `StartPairing` deliberately returns no pairing string — the tab sat on a spinner forever.)
- **Native / iOS (responder):** the pairing wizard's role-aware scanner routes a scanned
  `tenshu://offer` to `RespondToPairingOffer` → an in-wizard approval step (device name + SAS +
  Approve/Deny) → `ApprovePairingOffer` / `DenyPairingOffer`.

### 4.5 SAS timing — asymmetric by construction

Unlike the join flow (§3), where both devices display the SAS *before* the host approves, the
offer flow's SAS comparison is necessarily **post-approval on the offerer's side**: the SAS is
derived from `X25519(privA, PublicKeyB)`, and the offerer only learns `publicKeyA` from the
offer-response — which the responder POSTs *when the user approves* (§4.2 step 3). While the
responder's approval prompt is on screen, the offerer can show nothing but the QR.

Consequences (settled 2026-07-17, after the first live desktop↔phone run):

- **Responder copy must not imply simultaneous comparison.** The prompt says the new device will
  show the same code *after* approving (`pairing_responder_match_note`), not "confirm it matches"
  — the join-flow phrasing that confused the first live run.
- **The security posture is acceptable for v1**: the optical QR scan is the anti-MITM channel (the
  responder binds to `PublicKeyB` authentically at scan time), and the post-hoc match on the
  offerer confirms the payload landed in the session the user approved. A pre-approval "scanned"
  announce state (the responder posting `publicKeyA` at scan time so both sides can compare
  simultaneously — sketched as `scanned?` in the original proposal) remains a possible hardening;
  it needs a new relay interaction + threat-model amendment and was deliberately deferred. Now
  designed in [`sync-pairing-scanned-announce.md`](../proposals/sync-pairing-scanned-announce.md)
  (draft v0.1, decision pending).
- The sequence is pinned by `PairingVmFlowSystemTest` (`:control-shared-logic` desktopTest) — a
  two-party **VM-level** flow test (real proxies + in-process relay from `:system-test-utils`,
  real `PairingWizardViewModel`s on both sides) asserting each side's *user-visible* states, so a
  UI or copy change that re-introduces a simultaneous-comparison assumption fails a test.

---

## 5. Key Security Features

* **No Long-Lived Secrets on Relay**: The relay server only holds ephemeral public keys and
  encrypted blobs for a few minutes. It never sees the Master Keys or the Shared Secret.
* **Mutual Proximity Proof**: Scanning proves Device B is physically near Device A. Manual approval
  on Device A proves the user intended to allow *that specific* device name.
* **Session Isolation**: Closing the QR dialog on Device A immediately "burns" the handshake. Even
  if an attacker scanned the QR previously, they cannot finish the handshake once the host moves
  away from the screen.

## 6. Error Handling & Timeouts

* **Timeout**: If no approval is received within 5 minutes, the Requester (Device B) fails with a "
  Pairing Timed Out" error.
* **Context Loss**: If the Host (Device A) app crashes or the dialog is closed, the session is
  invalidated. The Requester must re-scan a new QR code.
* **Re-Registration**: If a device is already a realm device (e.g., self-registration of the
  primary device), the UI handles this gracefully by marking it with a "This Device" badge.
