# Inverted-Direction Pairing (`offer` flow) — New Device Displays QR, Existing Device Scans

**Status:** GRADUATED (2026-07-06) — P1–P6 shipped. The protocol is now the
timeless spec in [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) §4 (offer flow) alongside §3
(join flow); threat deltas in [`docs/sync/threat-model.md`](../sync/threat-model.md) (T-P-06..08).
This document is retained as the design record (rationale + threat analysis §4 + phase map/as-built
§6). Consult `pairing-flow.md` for the current protocol. Below is the original phase log.

Draft v0.3 (2026-07-05). **P1 (shared protocol + flag-day URI migration) BUILT
2026-07-05** — canonical `PairingUri` generator/parser with role-host dispatch in `:core-common`
(join + offer round-trips, pinned legacy/unknown-host rejection vectors);
`SyncManagerImpl.getPairingString()` emits `tenshu://join?o=&d=&k=&s=`; every legacy
`tenshu://pair?…` parser flipped in the same change (wizard VM with typed + localised scan errors
incl. the §5.2 role dispatch stub for `offer`, `:tenshu-sdk-js`, the `web/apex` gate with
per-reason copy, `system-test-utils`, iOS fixtures). As-built deviation: the URI layer does **not**
enforce the §3.2 32-byte key length — it is transport; key validity belongs to the crypto layer
(test stubs mint short placeholder keys and must round-trip). **P2 (relay + threat model) BUILT
2026-07-05** — `PairingOfferRegistry` + in-memory `PairingOfferStore` + `SqlitePairingOfferStore`
(migration v12; the row doubles as the single-use consumed marker, 24 h retention past the
7-minute serving TTL), `POST`/`GET /sync/pairing/offer-response` on the existing `/sync/pairing`
route, `MasterDelegationVerifier.offerResponseMessage` (§3.4 canonical bytes), hourly GC sweep;
threat-model sub-amendment landed in the same change (surfaces #1/#3 extended; the proposal's
T-P1..T-P3 land as **T-P-06..T-P-08** following §8.1's existing numbering). As-built deltas vs
§3.4: the POST body adds `responderDeviceId` (selects which registered device key to verify —
the signed bytes are unchanged); single-use tracking lives in the offer store itself rather than
the join-flow `ConsumedPairingSecretsStore` (improvements §4 — that hardening hasn't shipped, and
the offer flow only needs post-once on its own rendezvous). **Offerer-registration resolution
(P3/P4 prep, maintainer-approved 2026-07-05):** the offer flow has no `PairingRequest` to convey the
offerer's Ed25519 device signing key to the responder, so §3.2's offer QR is extended with `g=`
(offerer signing key) + `d=` (offerer device id), and the approved offer-response POST carries a
master/A33 delegation the relay verifies + uses to register the offerer in `KnownDevicesStore`
(mirrors `POST /sync/pairing/response` via a shared `verifyAdmissionDelegation` helper). Without
this the offerer's first JWT-authed sync would 401. This is an as-built delta to §3.2/§3.4 pending
doc ratification. **P3 (offerer ops) + P4 (responder ops) BUILT 2026-07-05** (proxy-side): six ops in `:sync-service`(-proxy) — offerer `OfferPairing`/`GetOfferStatus`/`CancelPairingOffer`, responder `RespondToPairingOffer`/`ApprovePairingOffer`/`DenyPairingOffer` — plus the `SyncRelay.postOfferResponse`/`getOfferResponse` client (Rest + Mock), offerer/responder session state on `SyncManager`, and two shared helpers extracted from the join flow so the security-critical logic isn't duplicated: `PairingAdmission` (admission blob + delegation + child A33 cert, now used by both `ApprovePairingRequest` and `ApprovePairingOffer`) and `installReceivedPairingPayload` (decrypt → initializeWithKeys → self-push Device, used by both `PairingCompletion` and `GetOfferStatus`). As-built crypto note: the payload is sealed directly under the ECDH shared secret (matching the join flow's actual `encrypt(blob, sharedSecret)`), not the HKDF-derived key §3.3 describes — true reuse of the shipped join crypto. `OfferPairingRoundTripTest` proves the live offerer↔responder round trip (QR → scan → approve → install; SAS matches both sides; both Device rows authored) + deny + cancel. P3 SDK LANDED (`Pairing.offer()` in `:tenshu-sdk-js` + `@tenshu/sdk` `PairingApi.offer` TS + native.d.ts + apex mock; apex typechecks, sdk-js + mock tests green) — **P3 + P4 COMPLETE**. **P5 (UI) SHIPPED (2026-07-06)** — web "Show QR" tab + `QrDisplay`/`qrcode`, native + iOS responder screens (as-built in §6). **P6 (graduation) DONE 2026-07-06** — spec folded into [`pairing-flow.md`](../sync/pairing-flow.md) §4, tech-debt entry retired. Only an on-device offerer↔responder round-trip over a live relay remains.
Specs the `tenshu://offer` protocol variant that
lets the *new* device host the QR and an *existing* realm device scan it — the ergonomically
natural flow on web (laptop displays, phone scans) and the missing "Show a code" affordance on the
web onboarding screen.

**Naming (v0.2 + v0.3, both 2026-07-05).** Two decisions:

1. *(v0.2)* The QR discriminator is a **role token**, not a version: the two QR forms are siblings
   for opposite camera geometries, not successor formats. Draft v0.1 called them `v1`/`v2`; renamed
   because "v2" misleadingly implied succession when both flows coexist permanently.
2. *(v0.3)* The canonical URIs live under the **single `tenshu://` scheme with the role as host**:
   `tenshu://join?…` (existing device displays, new device scans — today's direction) and
   `tenshu://offer?…` (this proposal). This matches the house URI convention (`tenshu://pair?…`
   shipped today, `tenshu://epc/pair#…` for iOS EPC, planned `tenshu://checkout-result`) and parses
   with standard URL libraries (host + query) instead of a hand-rolled opaque-URI parser, which the
   earlier `tenshu-pair:join?…` shape would have needed. **The legacy `tenshu://pair?…` form is
   removed with no compatibility shim**: the installed base is essentially the maintainer's own
   devices, so the emitter and all parsers flip in one flag-day change (P1). Retiring the `pair`
   host makes legacy trivially detectable — anything on it is old by definition and gets a
   "regenerate the code on the other device" error, never a partial parse. (`tenshu://epc/pair#…`
   is a different path + fragment form owned by iOS EPC pairing — unaffected.)

[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §8 was amended alongside
both decisions. The crypto info strings (`tenshu/pairing/{sas,keys}/v1`) carry a separate *crypto*
version and are unchanged. In this doc, "the join flow" names today's direction and "the offer
flow" the inverted one; citations of the form *improvements §n* refer to
[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md).

Supersedes the "Outstanding work" section of
[`docs/tech-debt.md`](../tech-debt.md) §"Inverted-Direction Pairing for Web (Browser Hosts QR,
Phone Scans)" — that entry filed the gap; this proposal specifies the fix.

Companion to:
- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — the join-flow protocol this extends. Read it first.
- [`docs/proposals/sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) — the
  join-flow crypto/threat decisions (§0, §1). **The offer flow reuses every primitive and the
  SAS/fingerprint helpers unchanged**; this proposal adds no new crypto.

---

## 1. Motivation

The current pairing protocol has a fixed cryptographic direction: the **existing** device (Device A)
displays the QR, the **new** device (Device B) scans it. This is because the join QR payload encodes
`ownerId` + `hostDeviceId`
([`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §8) — both values only an
already-provisioned device knows.

That direction is wrong for web onboarding. A brand-new browser has no realm, no `ownerId`, no
`hostDeviceId`, so it cannot generate a join QR — it can only ever be the scanner
([`PairingScreens.tsx`](../../web/apex/src/onboarding/screens/PairingScreens.tsx) is scan/paste-only
by design). But scanning is the awkward half of the flow on web:

- webcam-permission UX is clunky and inconsistent across browsers;
- you can't comfortably scan a QR shown on a phone held between your eyes and the laptop's webcam;
- the natural ergonomics are the inverse — **the laptop displays, the phone scans**.

The offer flow makes the new device the QR host. It is *cryptographically symmetric* to the join
flow (same X25519 / HKDF / XChaCha20-Poly1305 / SAS), and — as §4 shows — it is actually **slightly
stronger** on the passive-QR-observer threat, because the joiner now holds its own ephemeral private
key.

**The join flow does not go away.** The two coexist. Join remains the flow for "new device scans an
existing device's code"; offer adds "new device displays a code an existing device scans." A
role-aware scanner on the existing device picks the branch from the QR's role token (§5.2).

---

## 2. The addressing problem (why this needs relay work, not just a new QR)

The join flow's relay mailboxes are **keyed by `ownerId`**: Device B posts its `PairingRequest` into
`ownerId`'s mailbox (`POST /sync/pairing/request`), and Device A polls its own `ownerId` mailbox
(`GET /sync/pairing/requests`) — see
[`SyncRouting.kt:337`](../../sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/routing/SyncRouting.kt#L337).

In the offer flow the new device **has no `ownerId`**, so it can neither post into nor poll an
`ownerId`-keyed mailbox. The offer flow therefore needs a **rendezvous keyed by the pairing-secret
hash** instead — a `PairingOfferStore` addressed by `pairingSecretHash`. This is the one genuinely
new relay component.

A pleasant consequence of the inversion: **the offer flow uses the relay for one direction only.**
In the join flow the relay carries two hops (B→A request, then A→B response). In the offer flow the
QR carries everything A needs *optically* (B→A over the camera, not the relay), so the relay only
carries the A→B offer-response. One store, two endpoints.

---

## 3. Protocol

### 3.1 Roles

- **Offerer (Device B)** — the new device (typically a web browser). Generates the QR, waits.
- **Responder (Device A)** — an existing realm device (typically a phone). Scans the QR, approves.

Note the role names are the inverse of the join flow's "host/requester" verbs. To avoid confusion
this proposal uses **offerer** / **responder** throughout for the offer flow.

### 3.2 The offer QR payload

```
tenshu://offer?k=<base32(PublicKeyB)>&s=<base32(pairingSecret)>&n=<url-encoded deviceName>
```

| Key | Field           | Encoding                                        | Notes                                             |
|-----|-----------------|-------------------------------------------------|---------------------------------------------------|
| `k` | `PublicKeyB`    | base32, 32 raw bytes → 52 chars                 | Offerer's ephemeral X25519 public key             |
| `s` | `pairingSecret` | base32, 16 raw bytes → 26 chars                 | 128-bit CSPRNG secret (same as improvements §7)   |
| `n` | `deviceName`    | url-encoded UTF-8                               | Offerer names itself (parity with the join flow's B) |

**Deliberately omitted vs the join QR:** `o` (`ownerId`) and `d` (`hostDeviceId`). The offerer
doesn't know them; the responder contributes them after the scan.

Client behaviour on an unrecognised role host follows the rule from improvements §8: an `offer` QR
scanned by a client too old to understand it shows *"This pairing code requires a newer version of
Tenshu. Update your app and try again."* — no parse attempt. The retired legacy `tenshu://pair?…`
host is the inverse case (the *code* is too old, not the client) and gets *"This pairing code is
from an older version of Tenshu. Generate a new code on the other device."*

### 3.3 Step-by-step handshake

**Step 1 — Offer (Device B).** B collects a device name, generates an ephemeral X25519 keypair
(`privB`/`PublicKeyB`) and a 128-bit `pairingSecret`; computes `pairingSecretHash =
SHA-256(pairingSecret)` (hex). B displays the offer QR and begins polling
`GET /sync/pairing/offer-response?hash=<pairingSecretHash>`. `privB` lives in memory only and is
zeroed on session teardown (5-minute window, per improvements §9).

**Step 2 — Scan (Device A).** A scans B's QR *optically* (the proximity/anti-MITM channel — the
relay never sees it). A decodes `PublicKeyB`, `pairingSecret`, `deviceName`. A already knows its own
`ownerId` and `hostDeviceId`. A generates its **own** ephemeral X25519 keypair (`privA`/`PublicKeyA`).

**Step 3 — Approve (Device A).** A derives:

```
sharedSecret = X25519(privA, PublicKeyB)
sas          = deriveSas(sharedSecret, PublicKeyA, PublicKeyB)   // identical helper to improvements §2
```

A shows the approval prompt, unchanged in spirit from the join flow's host prompt:

> "Add **'<deviceName>'** to your realm? Confirm the code `482913` matches on the other device.
> [Approve] [Deny]"

On **Approve**, A builds the same `PairingPayload` as the join flow
([`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §11 — master identity +
realm-epoch + volume keys), AEAD-seals it under `HKDF(sharedSecret, salt=PublicKeyA||PublicKeyB,
info="tenshu/pairing/keys/v1")`, and POSTs an **offer-response** (§3.4). On **Deny**, A POSTs a
signed `status:"denied"` offer-response (same shape as the join flow's signed Denied path,
[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §10).

**Step 4 — Complete (Device B).** B receives the offer-response via its poll. B derives the same
`sharedSecret = X25519(privB, PublicKeyA)`, derives and displays the SAS (the user compares it
against A's prompt), decrypts the payload, and verifies the recovered **master public key hashes to
the `ownerId`** carried in the offer-response (the trust anchor — see §4, T-P2). B installs the keys,
self-pushes its `Device{isAuthorized=true,…}` block, and the flow completes exactly as improvements
§5. A also authors the peer `Device` block for B from the scanned payload.

### 3.4 Relay surface (new)

A **`PairingOfferStore`** keyed by `pairingSecretHash`, holding at most one offer-response blob with
a 5-minute-plus-grace TTL. Two endpoints under the existing `/sync/pairing` route:

```
POST /sync/pairing/offer-response
  body: { ownerId, pairingSecretHash, status, publicKeyA, payload?, hostSignature }
    - status: "approved" | "denied"
    - payload present iff status=="approved" (nonce || AEAD ciphertext, per improvements §11)
    - hostSignature: Ed25519 by A's device key over canonical(status || pairingSecretHash
        || publicKeyA || payload)
  relay:
    - verify hostSignature against KnownDevicesStore[ownerId]  (reuse MasterDelegationVerifier)
    - reject if pairingSecretHash already consumed  → 409
    - store keyed by pairingSecretHash; mark consumed

GET  /sync/pairing/offer-response?hash=<pairingSecretHash>
  - offerer poll; returns the stored blob (200) or empty (204/404)
  - unauthenticated: the blob is AEAD ciphertext + a public key; HR#1 preserved (§4)
```

⚠ **Superseded by the length-prefixed signed messages (`admission-revocation-names-the-capability.md` §7d, 2026-10-28).** The `hostSignature` is no longer a bare concatenation. It
covers `LP("tenshu-offer-response-v1") ‖ LP(status) ‖ LP(pairingSecretHash) ‖ LP(publicKeyA) ‖
LP(payload, empty when absent)` with `LP(x) = u32be(|x|) ‖ x` (`offerResponseMessage`,
`sync-service-common/.../SignedMessageEncoding.kt`). The approved response's delegation is the
tagged `pairingDelegationMessage` too. The block above is the original design.

The `ownerId` in the POST body is used only to select the realm's `KnownDevicesStore` for signature
verification — it never reaches the offerer except inside the authenticated offer-response it is
already trusting via the master-key→ownerId check.

Single-use tracking reuses the same mechanism the join-flow hardening adds in
[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §4
(`ConsumedPairingSecretsStore`). No `ownerId`-keyed mailbox is touched by the offer flow.

### 3.5 Sequence diagram

```mermaid
sequenceDiagram
  participant B as Device B (new / offerer)
  participant R as Relay
  participant A as Device A (existing / responder)

  B->>B: generate ephemeral X25519 (privB/PubB) + pairingSecret
  B->>A: QR (tenshu://offer?k=PubB&s=secret&n=name)   %% optical, not via relay
  loop poll until offer-response or 5 min
    B->>R: GET /sync/pairing/offer-response?hash=H
  end
  A->>A: scan QR; generate ephemeral (privA/PubA)
  A->>A: sharedSecret = X25519(privA, PubB); derive SAS
  Note over A,B: user confirms SAS matches between A and B
  alt Approve
    A->>R: POST /sync/pairing/offer-response {ownerId, H, approved, PubA, payload, hostSig}
    R-->>B: offer-response {approved, PubA, payload}
    B->>B: sharedSecret = X25519(privB, PubA); decrypt payload
    B->>B: verify master-pubkey hashes to ownerId; install keys
  else Deny
    A->>R: POST /sync/pairing/offer-response {ownerId, H, denied, hostSig}
    R-->>B: offer-response {denied}
    B->>B: terminate, show "declined"
  end
```

---

## 4. Threat model (deltas vs improvements §1)

The offer flow inherits the join flow's T1–T6 matrix
([`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §1). What changes:

| ID    | Threat in the offer flow                        | join verdict | offer verdict | Note                                                                                                                                                                              |
|-------|-------------------------------------------------|-----------|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T1    | Passive relay eavesdropper                      | Closes    | **Closes** | Same. Only ciphertext + public keys transit; relay can't decrypt. HR#1 intact.                                                                                                   |
| T3    | Passive QR observer                             | Partial   | **Closes (confidentiality)** | **Improvement.** The joiner (B) holds `privB`, which never leaves B. An observer who photographs B's QR gets `PubB`+`secret` but not `privB`, so cannot decrypt the payload even if it races B's poll. At worst it consumes the single-use hash (a DoS, T-P3). |
| T4    | Active MITM on the A→B channel                  | Closes    | **Closes** | A MITM substituting `PubA'` in the offer-response yields a different `sharedSecret` → SAS mismatch, user-visible. The B→A channel is optical (unspoofable in transit).            |
| T6    | Replayed / stolen secret                        | Closes    | **Closes** | Single-use `pairingSecretHash` in `ConsumedPairingSecretsStore`; second offer-response for the same hash → 409.                                                                   |

New / shifted items to add to [`docs/sync/threat-model.md`](../sync/threat-model.md) with the
implementing phase (House Rule #2):

- **T-P1 (unauthenticated offer-response GET).** The poll endpoint is unauthenticated (the offerer
  has no credentials yet). It returns only AEAD ciphertext + `PubA` + `ownerId`. Confidentiality
  rests on `privB`; integrity on the `hostSignature` (relay-verified) and the master-key→`ownerId`
  check on B. **Accepts** — no plaintext exposure; HR#1 preserved.
- **T-P2 (rogue offer-response / key-substitution).** Only a real realm device can produce a
  signature that clears `MasterDelegationVerifier`, and B additionally verifies the recovered master
  public key hashes to the claimed `ownerId` and matches the SAS. **Closes.**
- **T-P3 (griefing via hash consumption).** An observer with the QR *and* a valid realm device key
  could POST a spurious offer-response and consume the hash, forcing B to re-display. **Partial** —
  requires an already-authorized device (T5-adjacent); mitigated by re-generation being cheap. See
  §9 open question on optional offerer pre-registration.

The A→B AEAD construction, AAD binding, and payload contents are **identical to improvements §11**,
so no new crypto review surface beyond the endpoints above.

---

## 5. Cross-platform work (this is the part that touches every client)

The user's instinct was right: **the phone app has to change too.** The offer flow is not "add a QR
image to a web screen" — it is a second, parallel handshake with a new role on the existing device.

### 5.1 Offerer side (new device shows QR)

Primary target: **web browser** (the whole motivation). Also available to Android/desktop as a new
device. New client ops in `:sync-service` / `:sync-service-proxy`, parallel to the join-flow
requester ops:

- `OfferPairing` — generate ephemeral keypair + secret, render the offer QR URI, start polling
  `/sync/pairing/offer-response`.
- `GetOfferStatus` / `AwaitPairingOffer` — surface `showing → scanned?/awaiting-approval(sas) →
  paired | denied | expired | error` to the UI.
- `CancelPairingOffer` — zero `privB`, drop the session.

### 5.2 Responder side (existing device scans) — **the phone change**

New client ops on the existing device, parallel to the join-flow host approval path:

- `RespondToPairingOffer(scannedUri)` — parse the offer QR, generate `privA`/`PubA`, derive
  `sharedSecret` + SAS, surface the approval prompt (device name + SAS).
- `ApprovePairingOffer(...)` — build + AEAD-seal the `PairingPayload`, sign, POST the offer-response.
- `DenyPairingOffer(...)` — POST a signed `status:"denied"` offer-response.

**Role-aware scanner (the integration point).** The existing device already has a camera scanner
in the pairing wizard's *Scan* tab
([`PairingWizardScreen.kt`](../../control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/sync/PairingWizardScreen.kt)).
Today it always means "I am a new device joining." The offer flow makes the scanner branch on the
QR's role host:

- scanned `tenshu://join?…` → existing **joiner** flow (this device is new);
- scanned `tenshu://offer?…` → new **responder** flow (this device is existing, admitting the new one);
- anything else — including the retired legacy `tenshu://pair?…` host — → the §3.2 error prompts
  ("regenerate on the other device" for `pair`, "update your app" for unknown hosts).

This keeps one scanner and one mental model ("point the camera at the other device's code"); the
role host disambiguates intent. iOS mirrors this in
[`PairingView.swift`](../../control-ios/Sources/PairingView.swift).

### 5.3 UI parity outcomes

- **Web onboarding** [`PairingScreens.tsx`](../../web/apex/src/onboarding/screens/PairingScreens.tsx)
  gains a **"Show QR" tab** (offerer) beside the existing scan/paste (joiner) — directly closing
  the original complaint that the screen is scan-only and conceptually unlike native's two-tab
  wizard.
- **Web needs a QR *render* component.** Today `web/apex/src/onboarding/widgets/` has only
  `QrScanner` — there is no display component and no `qrcode` dependency. This is the same cheap-win
  gap that also affects the join-flow host dialog
  ([`PairNewDeviceDialog.tsx`](../../web/apex/src/control/devices/PairNewDeviceDialog.tsx) shows the
  pairing URI as raw mono text). Add one small `qrcode` renderer, used by both.
- **SDK** [`Pairing.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/Pairing.kt) gains
  `Pairing.offer()` returning an `OfferPairingSession` symmetric to the existing `submit()`/`show()`.

---

## 6. Phasing

Each phase is independently shippable and test-covered. The threat-model amendment lands **with the
relay phase** (Phase 2), per House Rule #2 — not as a trailing doc phase.

| Phase | Scope | Key deliverables | Depends on |
|-------|-------|------------------|-----------|
| **P1** | Shared protocol + crypto + flag-day URI migration | Canonical URI generator/parser (`tenshu://join` + `tenshu://offer`) with role-host dispatch; **flip Control's emitter** (`SyncManagerImpl.getPairingString()` → `tenshu://join?o=&d=&k=&s=`) and **remove every legacy `tenshu://pair?…` parser** in the same change (no compatibility shim); reuse of `PairingSas`/`PairingSecret`/`PairingPayload`; pinned cross-platform test vectors for both URI round-trips | improvements §6–§8 (SAS/secret helpers) |
| **P2** | Relay + threat model | `PairingOfferStore` (secret-keyed), `POST`/`GET /sync/pairing/offer-response`, `MasterDelegationVerifier` reuse, single-use via `ConsumedPairingSecretsStore`; **threat-model.md amendment (T-P1..T-P3)** | P1 |
| **P3** | Offerer client + SDK | `OfferPairing`/`GetOfferStatus`/`CancelPairingOffer` in `:sync-service(-proxy)`; `Pairing.offer()` in `:tenshu-sdk-js` + `@tenshu/sdk` TS types | P2 |
| **P4** | Responder client | `RespondToPairingOffer`/`ApprovePairingOffer`/`DenyPairingOffer`; role-aware scanner dispatch | P2 |
| **P5** | UI **(SHIPPED 2026-07-06)** | Web onboarding "Show QR" tab + `qrcode` render component (also retrofit the join-flow host dialog); native wizard responder screens; iOS mirror | P3, P4 |
| **P6** | Graduation | Fold the offer-flow spec into [`pairing-flow.md`](../sync/pairing-flow.md); retire the tech-debt entry | P5 |

**P5 as-built (2026-07-06).** All three UI surfaces landed over the P1–P4 primitives:

- **Web (offerer).** `PairingScreen` hosts a "Scan a code" / "Show a code" segmented tab over the
  existing joiner submit panel and a new `PairingOfferScreen` (calls `Pairing.offer(deviceName)`,
  renders the `tenshu://offer?…` URI via a new `QrDisplay` widget over the `qrcode` npm dep, observes
  the session). A new `pairing.offerTerminal` orchestrator step handles `paired`/`denied`/`expired`/
  `error` — `paired` shows the SAS for post-hoc comparison and continues into the shared passkey tail
  (symmetric with the join flow). `PairNewDeviceDialog`'s host `ShowingStep` now renders the same
  `QrDisplay` above the raw URI.
- **Native (responder).** The role-aware scanner's `Offer` branch (previously an
  `OfferNotYetSupported` stub — enum value removed) now drives the responder flow:
  `respondToPairingOffer` → new `PairingStep.ResponderApproval` (device name + SAS + Approve/Deny,
  mirroring the host-approval vocabulary) → `approvePairingOffer`/`denyPairingOffer` → new
  `ResponderSuccess` ("Device added"). Three responder ops surfaced on `SyncRepository` as
  default-throw methods (the `lock()` pattern) and delegated in `TenshuSyncRepository`.
- **iOS.** `PairingView.swift` mirrors the two new steps (`responder_approval`/`responder_success`)
  over the same shared `PairingWizardViewModel`; `ControlSurface.pairingStepKey` gained the two keys.
  Verified with `xcodebuild` against the iPhone-16 simulator.
- **Incidental fixes** needed to compile the touched targets, both pre-existing on master behind the
  billing-disabled CI: a latent 3-arg `PairingUri.offerUri` call in `PairingWizardViewModelTest` (the
  P1 signature is 5-arg), and a missing `FakeFilesRepository.openStreamingContent` override in
  `:tenshu-sdk-ios` (D2b streaming gap).

P1 subsumes the "canonical URI migration" that had been deferred as a separate cross-platform track
since the 2026-06-23 reconciliation ([`docs/web/roadmap.md`](../web/roadmap.md)): with backward
compatibility deliberately dropped (Naming, v0.3), the emitter and all parsers flip together in one
flag-day change. Devices on opposite sides of the flag day fail loudly with the §3.2 error prompts,
never with a partial parse.

The join-flow hardening ([`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md)
Steps 1–5: single-use secret, signed Denied, `ConsumedPairingSecretsStore`) is **assumed landed**
before P2 — the offer flow reuses that machinery rather than duplicating it. If that hardening
hasn't shipped, P2 pulls those pieces in.

---

## 7. Open questions

1. **Offerer pre-registration.** Should B `POST /sync/pairing/offer {pairingSecretHash}` to reserve
   the rendezvous before showing the QR (tightening T-P3), or is single-use tracking on the
   offer-response POST enough? Recommendation: skip for the first cut (matches the "relay carries
   one direction" simplicity); revisit if T-P3 griefing is observed.
2. **Who names the device.** This proposal has the offerer (B) name itself in the QR (`n=`), parity
   with the join flow's requester. Alternative: A names it after scanning (A has better context —
   "Ben's laptop"). Cheap to let A edit the pre-filled name on the approval prompt; leave as UX polish.
3. **Web-as-responder.** An already-provisioned *web* device could also be an offer-flow responder
   (scan a new device's QR). Lower priority than phone-responder; the role-aware scanner design
   supports it for free where a webcam exists.
4. **Ack / cleanup ordering.** Same open question as the join flow
   ([`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §13 "Cleanup
   ordering") — TTL-only vs explicit ack. Inherit that decision.

---

## 8. Implementation Notes for Claude Code

Canonical handoff. If anything here contradicts §1–§7, §1–§7 wins.

### Reuse, don't reinvent

- **All crypto is the join flow's.** `deriveSas`, `computeFingerprint`, `generatePairingSecret`,
  `PairingPayload`, the AEAD construction, and the `tenshu/pairing/{sas,keys}/v1` info strings are
  reused **verbatim** (the `v1` in the info string is the *crypto* version, not the QR role token —
  do not bump it for the offer flow). The offer flow adds a QR format and a rendezvous endpoint,
  nothing cryptographic.
- **Single-use + signature verification** reuse `ConsumedPairingSecretsStore` and
  `MasterDelegationVerifier` from the join-flow hardening.

### New files (indicative)

| Path | Contents |
|------|----------|
| `core-common/src/commonMain/kotlin/org/tenshu/core/common/util/PairingUri.kt` | Generate/parse `tenshu://join?o=&d=&k=&s=` and `tenshu://offer?k=&s=&n=` + role-host dispatch. Rejects the retired `tenshu://pair?…` host with the "regenerate" error (§3.2), unknown hosts with "update your app". Shares base32 helpers with the existing URI code. |
| `sync-service/src/commonMain/kotlin/org/tenshu/sync/OfferPairing.kt` | Offerer op: ephemeral keys + secret + QR + start poll. |
| `sync-service/src/commonMain/kotlin/org/tenshu/sync/RespondToPairingOffer.kt` | Responder op: parse scanned offer URI, derive shared secret + SAS. |
| `sync-service/src/commonMain/kotlin/org/tenshu/sync/ApprovePairingOffer.kt` / `DenyPairingOffer.kt` | Responder approve/deny → signed offer-response POST. |
| `sync-service/src/commonMain/kotlin/org/tenshu/sync/PairingOfferResponse.kt` | Composite schema for the offer-response body (`status`, `publicKeyA`, `payload?`, `hostSignature`). Mirror DTO in `:sync-service-common`. |
| `sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/PairingOfferStore.kt` | Secret-keyed store; TTL eviction (sibling to the mailbox / `ConsumedPairingSecretsStore`). |

### Modified files (indicative)

| Path | Change |
|------|--------|
| [`sync-relay-server/.../routing/SyncRouting.kt`](../../sync-relay-server/src/main/kotlin/org/tenshu/sync/relay/routing/SyncRouting.kt) (`route("/pairing")`, ~L337) | Add `POST /sync/pairing/offer-response` (verify sig, reject consumed → 409, store) + `GET /sync/pairing/offer-response?hash=`. |
| `sync-relay-server/.../auth/MasterDelegationVerifier.kt` | Extend canonical-message construction to cover the offer-response bytes (`status \|\| hash \|\| PubA \|\| payload`). |
| `sync-service-proxy/.../SyncManagerImpl.kt` | **P1 flag day:** `getPairingString()` (~L447) emits canonical `tenshu://join?o=&d=&k=&s=` instead of the legacy `tenshu://pair?v=1&d=<bech32>&ownerId=&deviceId=` form. Plus offerer poll loop + responder approve/deny wiring; zero `privB`/`privA` on teardown. |
| Legacy `tenshu://pair` parsers — [`tenshu-sdk-js/.../internal/PairingUri.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/PairingUri.kt), [`web/apex/src/lib/pairingUri.ts`](../../web/apex/src/lib/pairingUri.ts), `system-test-utils/.../PairingUri.kt`, the `PairingWizardViewModel` scan path | **P1 flag day:** flip to the shared canonical parse (or delete in favour of the `:core-common` helper where reachable); no legacy acceptance remains. `web/sdk/src/onboarding.ts` contract comments update alongside. |
| iOS sample/fake pairing strings — `tenshu-sdk-ios/.../ControlSurface.kt`, `FakeControlRepositories.kt` | **P1 flag day:** sample `pairingString` fixtures move to the canonical `tenshu://join?…` form so `onQrScanned` round-trips keep passing. |
| [`tenshu-sdk-js/.../sdk/Pairing.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/Pairing.kt) | Add `Pairing.offer()` → `OfferPairingSession { observe, cancel }`. |
| `web/sdk/src/onboarding.ts` + `@tenshu/sdk` types | `OfferPairingSession` / `OfferPairingStatus` TS contract. |
| [`web/apex/src/onboarding/screens/PairingScreens.tsx`](../../web/apex/src/onboarding/screens/PairingScreens.tsx) | Add "Show QR" (offerer) tab beside scan/paste. |
| `web/apex/src/onboarding/widgets/` (new `QrDisplay.tsx`) | `qrcode`-based render component; also used by [`PairNewDeviceDialog.tsx`](../../web/apex/src/control/devices/PairNewDeviceDialog.tsx). |
| [`control-shared/.../sync/PairingWizardScreen.kt`](../../control-shared/src/commonMain/kotlin/app/tenshu/control/ui/screens/sync/PairingWizardScreen.kt) + `PairingWizardViewModel.kt` | Role-aware scanner dispatch (legacy/`join`→joiner, `offer`→responder); responder approval screen. |
| [`control-ios/Sources/PairingView.swift`](../../control-ios/Sources/PairingView.swift) | Mirror the role-aware scanner + responder screens natively. |
| [`docs/sync/threat-model.md`](../sync/threat-model.md) | Add T-P1..T-P3 (with P2). |
| [`docs/tech-debt.md`](../tech-debt.md) §"Inverted-Direction Pairing for Web" | Point at this proposal (done in this change); retire on P6. |

### Test vectors / fixtures

- `PairingUri` round-trip tests for both hosts (generate → parse → equal) + unknown-host rejection
  (an `offer` URI fed to a parser that predates it returns the "update your app" error, not a
  partial parse) + legacy rejection (a `tenshu://pair?…` URI returns the "regenerate" error — pin
  the exact shipped legacy string as the fixture so the removal is deliberate, not accidental).
- A pinned SAS vector shared with the join flow (the SAS is derived identically) — assert the offer
  path produces the same SAS as the join path for the same `(sharedSecret, PubA, PubB)`.
- Relay: `PairingOfferSingleUseTest` (second offer-response for a hash → 409),
  `PairingOfferSignatureTest` (unsigned / wrong-key offer-response rejected),
  `PairingOfferStoreTtlTest` (blob evicted after window).
- Responder/offerer round-trip in `:sync-service-proxy` commonTest: offerer shows QR → responder
  parses + approves → offerer decrypts + installs (the offer-flow analogue of the join-flow
  approve-path test).

### References

- [`docs/sync/pairing-flow.md`](../sync/pairing-flow.md) — the join-flow spec (extended by P6).
- [`docs/proposals/sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) — the
  join-flow crypto/threat decisions reused wholesale (§0, §1, §6–§11, §13).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — receives T-P1..T-P3.
- [`docs/proposals/sync-relay-api.md`](sync-relay-api.md) §3 — long-poll optimisation applies to the
  offerer poll too (optional).
- [`docs/tech-debt.md`](../tech-debt.md) §"Inverted-Direction Pairing for Web" — the gap this closes.
