# Contact invitation handshake — the v1 cross-user identity-acquisition protocol

**Status:** v1.0 — **CI-1…CI-4 shipped (2026-06)**. `CreateContactInvite`,
`RedeemContactInvite`, `CompleteContactHandshake` and `AwaitInviteAck` exist, over
`InvitePayloads` + `RestInviteRelayClient` and the relay's `InviteRouting`. ⚠ The §9
threat-model amendment **landed** (assets A24/A25 and §8.13 T-CI-01…09 are in
`sync/threat-model.md`), so §9's "ready-to-apply" framing is spent. ⚠ This doc has no
as-built section; the code is the reference. Originally designed with the maintainer
(2026-06-11 session). This is the protocol slice that
[`contact-service-identity-model.md`](contact-service-identity-model.md) §8 (v1) and §10
(first bullet) called for. It also **resolves §10's last open question** (realm-invite vs
contact-handshake — §2 below) and carries the **ready-to-apply threat-model amendment**
(§9) that co-lands with the v1 code per House Rule #2. **§12 is the canonical
implementation handoff** (file-level tables, test plan); start there when implementing.

**Maintainer decisions pinned this session:** (1) the v1 entities and ops land in a
**minimal `:contact-service`** (five-module split created now with only the v1 subset;
v2 fills in `ContactSource`/vCard/app — supersedes the §8 reading that the service itself
is v2); (2) invitation-link expiry defaults to **72 hours** (fixed in v1, relay-capped at
7 days).

---

## 1. Scope and relationship to device pairing

The handshake acquires a **`TenshuIdentity`** (peer's realm-identity X25519 public key,
A13-class, + self-asserted display name) on **both** sides, mutually, with no directory
(contact doc §2). It is the cross-user sibling of device pairing
([`sync/pairing-flow.md`](../sync/pairing-flow.md)), but three constraints break the
symmetry, and the protocol differs exactly where they bite:

1. **Remote + asynchronous.** A link rides WhatsApp/email; the invitee may redeem hours
   later and the inviter may be offline at redemption. Pairing's 5-minute in-memory
   session (A8) becomes a 72-hour, persisted, revocable invitation.
2. **No secret payload handoff.** Pairing delivers A1 (catastrophic if leaked), which
   justifies ephemeral-ephemeral ECDH and session burning. The handshake delivers only
   *public* identity material + names; confidentiality is a privacy concern (who is
   contacting whom, what they're called), not a key-compromise concern.
3. **Multi-device completion.** The inviter's confirmation should work on any of their
   devices, not only the issuing one.

All three are answered by one design move: **the inviter's static A13 public key rides in
the link, and both mailbox messages are sealed boxes to A13 keys** — reusing the existing
`wrapKeyForIdentity` construction (ephemeral-static X25519 → HKDF → XChaCha20-Poly1305,
threat-model A13 entry) instead of pairing's ephemeral-ephemeral session. No ephemeral
private state needs to survive the invite window on the inviter side at all, and every
inviter device holds A13 (delivered via pairing, SR-3b.4a), so any of them can complete.

**Explicit non-goal:** the handshake does **not** grant shared-realm membership and does
**not** admit the invitee's devices to any realm. Those are the separable cross-user join
runtime ([`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md) §3.1) — see §2.

## 2. Decision — contact handshake first, membership grant separate

Resolves [`contact-service-identity-model.md`](contact-service-identity-model.md) §10
last bullet. **v1's invite is a contact handshake; a realm membership grant is a separate,
subsequent owner action.** The §10 lean is confirmed, for five reasons:

1. **Contacts accrete independently of any one realm** — the entire point of §2 of the
   contact doc. The handshake's output is reusable ("invite from contacts"); a
   realm-coupled invite would produce a membership and only incidentally a contact.
2. **The two framings converge anyway.** Q53 ([`tenshu-rei.md`](tenshu-rei.md)) already
   specifies that even a "realm invite" link only authorises *becoming a member
   candidate* — the invitee presents its identity pubkey, and owner-side policy decides
   the wrap. That candidate-presentation step *is* the contact handshake. Coupling buys
   no protocol simplification; it only entangles two capability lifetimes.
3. **Decoupling unblocks shipping.** The membership grant depends on the cross-user join
   runtime (relay-side admission of the *other user's* devices + key delivery —
   `sync-multi-realm-runtime.md` §3.1, explicitly unsized). The handshake has no such
   dependency: it needs only a small relay mailbox. Coupled, contact acquisition would be
   blocked on the hardest outstanding sync track.
4. **Cleaner capability semantics.** A link that directly yields membership drifts toward
   the bearer-key shape Q53 rejects. Here the link is a capability for exactly one thing:
   *initiating one mutual handshake*. Worst case on leak: the attacker becomes a
   checkmark-less contact candidate pending the inviter's explicit confirmation — never a
   realm member (the grant is a second, key-fingerprint-specific owner action).
5. **Revocation stays orthogonal.** Revoking an unredeemed invite = delete one relay
   record. Revoking membership = Kr rotation + cap revocation (Q30). No conflated states.

**UX continuity is preserved by an advisory *share intent*.** The Drive *Manage access*
"Invite" button creates a contact invite carrying an optional
`intent = { kind: SHARE_FOLDER, label }` (inside the sealed ack, §5.3 — never in the link).
On completion, the owner's client immediately prompts *"Add {petname} to '{label}'?"* and
— once the join runtime exists — executes the grant (wrap Kr to the new identity, emit the
`SharedRealmMembership`, admit devices). Until then the prompt parks the grant; the
contact exists either way.

## 3. Capabilities and link format

Three values are minted per invite by the issuing device (CSPRNG):

| Value | Size | Role |
|---|---|---|
| `inviteId` | 16 bytes | Mailbox address at the relay. Knowledge = capability to post/fetch the sealed blobs. |
| `inviteSecret` (`K_link`) | 16 bytes (128-bit) | Proof of link possession. **Never sent to the relay in any form — not even hashed.** Verified end-to-end inside sealed payloads. |
| inviter A13 public key | 32 bytes | Pins the inviter's identity against substitution; the seal target for the response. |

The relay stores only `(inviteId → ownerId, expiresAt, state)` plus opaque sealed blobs.
With no secret-derived material relay-side, a compromised relay cannot forge either
direction of the handshake even in principle (§9 T-CI-02/03) — one step stronger than
pairing, where the relay at least sees `pairingSecretHash`.

Two encodings of the same triple (versioned; unknown version = hard error with an
"update your app" message, same rule as pairing improvements Step 8):

- **Messaging link** (OS share sheet): `https://tenshu.app/i#v1.<base64url(inviteId ‖ K_link ‖ a13Pub)>`
  — 64 bytes → 86 chars after the `v1.` tag. The payload lives in the **fragment**, so a
  browser never transmits it to the apex server; the apex `/i` page (web Phase 7 surface)
  deep-links into an installed app or shows install guidance.
- **QR (in-person)**: `tenshu-invite:v1?i=<base32(inviteId)>&k=<base32(K_link)>&p=<base32(a13Pub)>`
  — short keys, mirroring the pairing URI convention (now `tenshu://join`, renamed 2026-07-05;
  pairing improvements §0 decision 6).

The raw link is handed to the OS share sheet and may be re-displayed only from the issuing
session's memory; it is **not reconstructable afterwards** (the inviter persists only
`SHA-256(K_link)` — §7.1). "Show the QR again" after the session = revoke + re-issue.

Expiry: **72 h** default, relay caps any requested value at 7 days. One-time: a single
response slot per invite (§4 step 3). Revocable before redemption (Q53).

## 4. Message flow

```mermaid
sequenceDiagram
  participant A as Inviter (any device)
  participant R as Relay
  participant B as Invitee (redeeming device)

  A->>A: mint inviteId, K_link; build link
  A->>R: POST /sync/invite {inviteId, expiresAt}  [JWT]
  A->>A: save ContactInvite{secretHash, PENDING} (Syncable)
  A-->>B: link / QR via outside channel
  B->>B: parse link; build InviteResponsePayload{K_link, a13PubB, name}
  B->>R: POST /sync/invite/response {inviteId, sealed→a13PubA}
  Note over R: state OPEN → RESPONDED (single slot)
  loop inviter sync cycle (while invites pending)
    A->>R: GET /sync/invite/responses  [JWT]
    R-->>A: sealed response(s)
  end
  A->>A: unseal with A13priv; check SHA-256(K_link) == secretHash
  Note over A: confirm UI: name + fingerprint(a13PubB)<br/>"Save as new contact, or link to existing?"
  alt Accept
    A->>A: create TenshuIdentity{UNVERIFIED|VERIFIED} + thin Person
    A->>R: POST /sync/invite/ack {inviteId, sealed→a13PubB: accepted, K_link, a13PubA, nameA, intent?}  [JWT]
  else Reject (or K_link mismatch ⇒ auto-reject, COMPROMISED)
    A->>R: POST /sync/invite/ack {inviteId, sealed→a13PubB: rejected, K_link, a13PubA}  [JWT]
  end
  loop invitee poll (slow cadence until expiry)
    B->>R: GET /sync/invite/ack?inviteId=…
    R-->>B: sealed ack
  end
  B->>B: unseal with own A13priv; check K_link AND a13PubA == link's p
  B->>B: on accepted: create TenshuIdentity + Person for inviter
  B->>R: POST /sync/invite/done {inviteId}
```

Step-by-step, with the checks that carry the security argument:

1. **Issue (inviter).** Mint the §3 triple. `POST /sync/invite` registers the mailbox
   (JWT — issuance is authenticated, so mailbox creation is bounded per user). Persist
   `ContactInvite { inviteId, secretHash = SHA-256(K_link), status = PENDING, expiresAt,
   intent? }` as a personal-realm `Syncable(Eager)` — every inviter device now sees the
   pending invite and can complete it. Hand the link to the OS share sheet.
2. **Redeem (invitee, device-bound).** Parse the link. Build
   `InviteResponsePayload { inviteSecret, identityPublicKey = own A13 pub,
   selfAssertedName }`, seal it to the link's `a13Pub` (§5.2), `POST
   /sync/invite/response` (unauthenticated; `inviteId` is the capability). Persist a
   device-local `PendingRedemption { inviteId, K_link, peerA13Pub }` in
   `SecureStorageDelegate` (§7.2) and start polling for the ack.
3. **Single slot.** The relay accepts exactly one response per invite
   (`OPEN → RESPONDED`; later posts get `409`). A garbage response (attacker holding the
   link but unable to do better than wrong-`K_link`) **burns the invite**: the inviter
   client's `K_link` check fails, it auto-rejects, and marks the invite `COMPROMISED`
   with a UI warning. Burning is deliberate — a bad response is evidence the channel
   leaked; the user should re-issue over a better channel. The honest-case race for the
   slot is negligible.
4. **Complete (inviter, any device).** The proxy polls `GET /sync/invite/responses` on
   its sync cycle while any `ContactInvite` is `PENDING`. On a response: unseal with A13
   private key, verify `SHA-256(payload.inviteSecret) == secretHash` and the invite is
   locally `PENDING` and unexpired. Show the §6-of-the-contact-doc completion prompt
   (self-asserted name + key fingerprint, *"Save as new contact, or link to existing?"*,
   candidates ranked against imported sources). ⚠⚠ **What is accepted is what was shown**
   (threat model T-CI-10, 2026-10-02): the response completion acts on must carry the key the
   prompt's `claimedFingerprint` names — it is refused otherwise, `sasConfirmed` is refused when
   no code was ever shown (both as typed `InvalidParameter`s — `CompleteContactHandshake.Refusal`),
   and a later response under a different key never replaces a claim, in a later poll or later
   in the same one. ⭐ Since contact-service **v2** (2026-10-04) the completion also carries
   a **required** `shownFingerprint` — the raw claim the prompt displayed — and the proxy stores the
   response only if it is that key, which closes the two-inviter-device case where the synced claim
   moves by merge under an open prompt; every prompt clears its "codes match" tick when the claim
   changes and submits the claim it rendered, never one re-read at tap time. ⚠ An ACCEPT with an
   empty `shownFingerprint` is refused — there is no longer an on-demand TOFU accept without a
   pickup's claim. Lockstep: every app and `:hub` move together.
   Until then the stash-miss path took the key from a second relay fetch that nothing bound to
   the code the user had compared. On accept: create
   `TenshuIdentity { fingerprint, publicKey, selfAssertedName, verification, source }` +
   thin `Person` (both sync to all devices as ordinary Syncables), then post the sealed
   ack. On reject (user) or `K_link` mismatch (automatic): post a sealed `rejected` ack.
5. **Mutual close (invitee).** Unseal the ack with own A13 private key; verify it carries
   the correct `K_link` **and** that `ack.identityPublicKey` equals the link-pinned
   inviter key. On `accepted`: create the mirror `TenshuIdentity` + `Person` for the
   inviter. `POST /sync/invite/done` frees relay state (TTL is the fallback, as with
   pairing ack). On `rejected`: terminal "declined" state, no records created.

**Multi-device asymmetry (by design):** inviter-side issuance/completion works from any
device (A13 + the `ContactInvite` Syncable are everywhere); invitee-side redemption is
bound to the redeeming device (`K_link` stays local) — the *results* sync. A
cross-device redemption hand-off is a v2 nicety, noted in §11.

## 5. Cryptography

Primitives are the pinned project set (pairing improvements §0 decision 1): X25519,
HKDF-SHA-256, XChaCha20-Poly1305, SHA-256. No new primitive, no new curve.

### 5.1 Sealed box (generalised `wrapKeyForIdentity`)

`sealToIdentity(recipientPub, plaintext, info, aad)`:

As landed in CI-1 (`DefaultSyncCryptoManager.sealToIdentity` — normative for any future
platform implementation, e.g. web):

```
eph        = X25519.generateKeyPair()                       // fresh per seal
shared     = X25519(eph.priv, recipientPub)
key        = HKDF-SHA-256(ikm = shared, salt = empty,
                          info = info, length = 32)
ciphertext = AEAD-encryptWithAad(plaintext, key,
                                 aad = info || eph.pub || aad)   // XChaCha20-Poly1305;
                                                                 // nonce framed by encryptWithAad
output     = eph.pub(32) || ciphertext
```

This is exactly the `wrapKeyForIdentity` construction (threat-model A13 entry) with a
parameterised `info` string and caller-supplied additional AAD: the historical
`KEY_WRAP_AAD_PREFIX` was the same bytes as `KEY_WRAP_HKDF_INFO`, so
`info = "tenshu/key-wrap/v1", aad = empty` reproduces the legacy blobs bit-for-bit —
`wrapKeyForIdentity` now delegates, existing wrapped blobs stay readable
(cross-compat pinned in `SealToIdentityTest`). Note the deviations from this doc's
original pre-implementation sketch: HKDF salt is **empty** (the existing primitive's
shape, not `eph.pub || recipientPub`), `info` is additionally bound into the AAD, and
the AEAD nonce is framed by the project's `encryptWithAad` rather than hand-prefixed.
Recipient-binding via ECDH (wrong private key ⇒ AEAD tag failure) is unchanged.

### 5.2 The two messages

| Message | Sealed to | `info` | AAD (beyond eph.pub) | Plaintext (kotlinx JSON — see §10 CI-1 note) |
|---|---|---|---|---|
| `InviteResponsePayload` | inviter A13 pub (from link) | `tenshu/invite/response/v1` | `inviteId` | `{ inviteSecret(16), identityPublicKey(32), selfAssertedName (≤64 chars), version: 1 }` |
| `InviteAckPayload` | invitee A13 pub (from response) | `tenshu/invite/ack/v1` | `inviteId` | `{ status: accepted\|rejected, inviteSecret(16), identityPublicKey(32) (= inviter A13 pub), selfAssertedName?, intent? { kind, label } }` |

Why each binding exists:

- **`inviteSecret` inside both plaintexts** — end-to-end proof of link possession in both
  directions; the relay never holds it or its hash, so AD5 cannot forge either message.
- **`inviteId` in the AAD** — a relay holding two invites sealed to the same inviter A13
  cannot splice a response from invite X onto invite Y (unseal succeeds, AAD check
  fails); replays across invites additionally fail the per-invite `K_link` check.
- **`identityPublicKey` echoed in the ack** — the invitee checks it against the
  link-pinned key, closing ack forgery by anyone who somehow obtained the invitee's A13
  pub but not the inviter's A13 private key.
- **`intent` in the ack, never the link** — channel observers see no realm label/name.

Version rule: bumping any primitive or payload shape bumps the `info` version segment;
no in-protocol negotiation (same rule as pairing improvements Step 6).

### 5.3 Verification code (contact SAS) and fingerprint

The verification ladder's `UNVERIFIED → VERIFIED` upgrade compares a code derived from
**both identity public keys** — session-independent, so it works at handshake time *and*
any later re-verification (the Signal safety-number shape the contact doc §1 anticipates):

```
lo, hi   = sort_lexicographic(a13PubA, a13PubB)
sasBytes = HKDF-SHA-256(ikm = lo || hi, salt = empty,
                        info = "tenshu/contact/sas/v1", length = 4)
sas      = (be32(sasBytes) >> 12) % 1_000_000        // 6 digits, zero-padded
```

Public derivation is correct here: this is mutual key-fingerprint comparison over an
out-of-band human channel, not a secrecy mechanism. Display alongside it the existing
pairing fingerprint format (`SHA-256(pub)[0..8]` → `XXXXX-YYYYY-ZZZZZ`) for the peer key
on both confirmation screens.

- **QR-in-person flow:** invitee scans, redeems immediately, both devices are present →
  both show the SAS, both users confirm → identity is born `VERIFIED`, `source = QR_SAS`.
- **Remote link flow:** no SAS at completion → `UNVERIFIED`, `source = INVITE_LINK`.
  Later upgrade from the contact detail screen reuses the same derivation (UI is v2).

A pinned cross-platform test vector (fixed keypairs → expected SAS) follows the pairing
SAS convention: first implementation pins it, all platforms assert it.

## 6. Relay API

Style and conventions follow [`sync-relay-api.md`](sync-relay-api.md): JSON control-plane
envelopes with base64 fields, JWT per §2 of that doc where marked, capability-by-knowledge
where not, same error-code vocabulary (`404` unknown, `409` conflict/used, `410`
expired/revoked).

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /sync/invite` | JWT (inviter) | Register `{ inviteId, expiresAt }`. Relay stores `(inviteId → iss, min(expiresAt, now+7d), OPEN)`. `409` if `inviteId` exists. Per-owner open-invite cap (administrative, e.g. 32). |
| `DELETE /sync/invite/{inviteId}` | JWT (inviter; `iss` must own it) | Revoke before redemption. Deletes the record + any blobs. |
| `POST /sync/invite/response` | none — `inviteId` is the capability | Body `{ inviteId, sealedResponse }` (size-capped, ~4 KiB). Requires state `OPEN` + unexpired ⇒ `RESPONDED` (single slot). `404` / `410` / `409 already responded`. |
| `GET /sync/invite/responses` | JWT (inviter) | All pending sealed responses for `iss`'s open invites. Polled on the proxy sync cycle only while invites are `PENDING` client-side. |
| `POST /sync/invite/ack` | JWT (inviter) | `{ inviteId, sealedAck }` ⇒ `ACKED`. |
| `GET /sync/invite/ack?inviteId=…` | none — capability | Returns the sealed ack. Contents are sealed to the invitee's A13, so a channel observer replaying the `inviteId` learns nothing. |
| `POST /sync/invite/done` | none — capability | Frees all state for `inviteId`. TTL is the fallback. |

**Retention:** invite record until `expiresAt`; a posted response/ack survives until
`max(expiresAt, respondedAt + 72 h)` so an offline inviter/invitee can still complete;
`done` deletes immediately. **Storage:** `InviteStore` behind the storage SPI (in-memory
default + `SqliteInviteStore`), the established
[`sync-relay-persistence.md`](sync-relay-persistence.md) pattern. Ephemeral by nature but
persisting it is harmless — every stored field is either routing metadata or sealed
ciphertext (House Rule #1).

**What the relay sees, exhaustively:** `inviteId`, inviter `ownerId`, expiry, state
transitions + timing, sealed blob sizes, the redeeming party's transport-level IP. It
never sees `K_link` (or a hash of it), either party's name, the invitee's identity key,
or any realm association. The invitee is protocol-anonymous: redemption carries no JWT.

**Long-poll** on `GET /sync/invite/ack` / `/responses` is an optional optimisation in the
same bucket as relay-API Phases 3/5; the polling floor (sync-cycle cadence inviter-side;
slow poll, e.g. 30 s foreground / on-sync-tick background, invitee-side) ships first.

## 7. Client-side state

### 7.1 Inviter — `ContactInvite` (Syncable, personal realm)

```kotlin
interface ContactInvite : Composite {
    val inviteId: TString get() = getField(Field.INVITE_ID)        // the key
    val secretHash: TString get() = getField(Field.SECRET_HASH)    // SHA-256(K_link) hex; raw secret never persisted
    val status: TString get() = getField(Field.STATUS)             // PENDING | REDEEMED | COMPLETED | REJECTED | REVOKED | EXPIRED | COMPROMISED
    val createdAt: TTimestamp get() = getField(Field.CREATED_AT)
    val expiresAt: TTimestamp get() = getField(Field.EXPIRES_AT)
    val intentKind: TString get() = getField(Field.INTENT_KIND)    // "" | SHARE_FOLDER (advisory)
    val intentLabel: TString get() = getField(Field.INTENT_LABEL)
    val resultFingerprint: TString get() = getField(Field.RESULT_FINGERPRINT) // TenshuIdentity link once COMPLETED
    val claimedName: TString get() = getField(Field.CLAIMED_NAME)              // responder's self-asserted name, set at REDEEMED ("" before)
    val claimedFingerprint: TString get() = getField(Field.CLAIMED_FINGERPRINT) // fingerprint of the claimed key, set with claimedName
    val claimedSas: TString get() = getField(Field.CLAIMED_SAS)                // deriveContactSas(ownA13, claimedKey), set with claimedName (CI-4b)
    // stringDataId on INVITE_ID; Syncable(Eager)
}
```

Storing only the hash means a compromise of the inviter's synced data cannot impersonate
an invitee (and `K_link` round-trips back to the inviter inside the response, so the hash
is all completion needs). The raw link exists only in the issuing session's memory.

The `claimed*` pair (added with CI-4a) is written by the pickup when it flips the row to
`REDEEMED`: it's what lets the completion prompt's header render on **any** inviter
device as pure UI over synced state (`drive-manage-access-ux.md` §2.3) without a relay
round trip. Strictly a *claim* — the §6-of-the-contact-doc quarantine applies (rendered
quoted, never auto-promoted to a petname); same A24 trust class as the response payload
it derives from, encrypted realm data like every entity (House Rule #1).

### 7.2 Invitee — `PendingRedemption` (device-local, NOT synced)

`{ inviteId, K_link, peerA13Pub, redeemedAt }` in `SecureStorageDelegate`, cleared on
completion, rejection, expiry, or user cancel. Deliberately not a Syncable: it holds the
raw capability, redemption is an interactive single-device flow anyway, and keeping it
local minimises the at-rest surface (I8; §9 T-CI-08). Unlike pairing's A8 this state
legitimately outlives the app session (up to 72 h), hence secure storage rather than
memory — the structured-cancellation teardown that pairing's `AwaitPairing` got is
replaced by explicit lifecycle clearing on the terminal states above.

### 7.3 Module home and ops (maintainer-confirmed)

v1 creates the five-module **`:contact-service`** split (`-common`/`-client`/`-proxy`/
`-test-utils`, symmetric with `:notification-service`) containing only the v1 subset:
`Person` (thin), `TenshuIdentity`, `ContactInvite` schemas + the handshake ops. v2 adds
`ContactSource`, the vCard codec, and the app. Ops follow House Rule #3 (non-suspend
interfaces, `IoOperation` + `TaskInteractor`):

- `CreateContactInvite(intent?) → TString` — mint, register at relay, save `ContactInvite`, return the link payload.
- `RevokeContactInvite(inviteId)`
- `RedeemContactInvite(linkPayload)` — parse, seal+post response, persist `PendingRedemption`.
- `AwaitInviteAck(inviteId)` — Layer-2 task (the `AwaitPairing` shape) polling the ack endpoint; bounded per app session, resumed from `PendingRedemption` on next launch.
- `CompleteContactHandshake(inviteId, decision, personId? | newPersonName)` — verify, create `Person` + `TenshuIdentity` (accept) and post the sealed ack.
- The *Manage access* picker reads `Person` rows via the ordinary chain DSL — no dedicated list op.

None of these carry `HeadlessSafe` (I9): issuance, completion, and redemption are all
interactive. The response-arrival *check* rides the existing sync cycle, which is.

## 8. Verification ladder and the share-intent follow-through

The handshake feeds the contact doc's §4.2 ladder exactly as designed there: link flow ⇒
`UNVERIFIED` (TOFU), QR+SAS flow ⇒ `VERIFIED`, `ATTESTED` stays v3/Rei. The completion
prompt is the §6.2 "ask at handshake completion" moment — both the petname default
(self-asserted name suggested, user-owned) and link-to-existing-Person candidates happen
there. Nothing in this protocol writes to `Person.displayName` after creation.

When the invite carried a share intent, completion (inviter side) chains into the
membership-grant prompt (§2). The grant itself — wrap Kr to `TenshuIdentity.publicKey`,
emit the `SharedRealmMembership` for the invitee, admit the invitee's devices to the
shared `ownerId` — is the cross-user join runtime's surface and is specified there, not
here. The handshake's contract ends at: *both parties hold each other's verified-or-TOFU
identity reference.*

## 9. Threat-model amendment (ready-to-apply; co-lands with v1 code per House Rule #2)

> **Numbering note:** [`core-logging-facade.md`](core-logging-facade.md) §7 (draft, not
> landed) has informally reserved A22/A23. This amendment therefore claims **A24/A25**,
> threat group **§8.13**, attack surface **#11**. Whichever amendment lands second
> rebases trivially.

**§5 Assets — add:**

- **A24. Contact Graph & Identity Keyring.** `Person` / `TenshuIdentity` /
  `ContactInvite` (later `ContactSource`) rows including per-key verification state —
  personal-realm Syncables, ciphertext to the relay like any entity (House Rule #1
  holds). The trust-relevant component is `TenshuIdentity.verification`: it is never
  auto-elevated, never transferred by merge/link (contact doc §6), and the UI checkmark
  is derived from it alone. Compromise (a forged `VERIFIED` row written by a realm
  device) requires a realm-device compromise, which is game-over for worse reasons.
- **A25. Invitation Capability & Handshake State.** Per invite: the link triple
  `(inviteId, K_link, inviter A13 pub)` riding an outside channel; the inviter-side
  `ContactInvite` (stores `SHA-256(K_link)` only — the raw capability is never persisted
  inviter-side); the invitee-side `PendingRedemption` (raw `K_link` at rest in
  `SecureStorageDelegate` for ≤ 72 h — the deliberate deviation from A8's in-memory-only
  posture, justified by the asynchronous window and bounded by the terminal-state
  clearing in the protocol doc §7.2); relay-side `(inviteId → ownerId, expiry, state)` +
  sealed mailbox blobs. One-time, 72-hour default TTL, revocable before redemption.
  The relay never holds `K_link` in any form.

**§7 Attack Surfaces — add:**

11. Cross-user contact invitation handshake (invite links/QR, the `/sync/invite/*`
    mailbox, sealed response/ack blobs, self-asserted names).

**§8 Threats — add §8.13 Contact invitation handshake:**

**T-CI-01. Link interception + pre-emptive redemption.**
Actors: AD2–AD4 (channel compromise), AD5 (relay sees no link). Assets: A24; A12
transitively iff a grant follows. Invariants: none directly (the handshake grants no data
access).
An attacker who reads the outside channel redeems the invite first, presenting their own
key under the expected person's name.
Defense: single response slot — the legitimate invitee's later attempt fails loudly
(`409`), prompting investigation; TOFU quarantine (self-asserted name never becomes the
petname without user confirmation, no auto-merge per contact doc §6); the checkmark
requires SAS; any subsequent realm grant is a separate explicit act on a displayed key
fingerprint. Wrong-secret responses additionally burn the invite as `COMPROMISED`.
Residual: **Medium** — same consent-failure class as T-P-05; containment (it yields a
contact candidate, not realm access) is the design's load-bearing property.

**T-CI-02. Relay forges or substitutes an invite response.**
Actors: AD5. Invariants: I1-adjacent (identity substitution).
Defense: the response must contain `K_link`, which never reaches the relay in any form
(not even hashed — stronger than pairing's `pairingSecretHash` posture), and is sealed to
the inviter's A13 with `inviteId` bound in the AAD. The relay can neither read, forge,
nor cross-splice responses between invites.
Residual: **Low.**

**T-CI-03. Relay forges or substitutes the ack.**
Actors: AD5.
Defense: the ack is sealed to the invitee's A13 public key, which the relay never learns
(it sits inside the response ciphertext); it must carry `K_link`; and the invitee checks
the echoed inviter key against the link-pinned one. All three fail independently for AD5.
Residual: **Low.**

**T-CI-04. Replay / cross-invite splice.**
Actors: AD1–AD5.
Defense: per-invite `K_link` + `inviteId` AAD binding + the relay's single-slot state
machine + fresh ephemeral key per seal.
Residual: **Low.**

**T-CI-05. Mailbox flooding / invite griefing.**
Actors: AD2–AD5.
Defense: mailbox creation is JWT-authenticated and per-owner capped; response bodies are
size-capped; posting garbage requires the `inviteId`, i.e. the link — so griefing is a
strict subset of T-CI-01 channel compromise, and burning the invite on a bad secret is
the *intended* response to that evidence.
Residual: **Low / accepted** (relay DoS in general is §10 territory).

**T-CI-06. Handshake metadata.**
Actors: AD4, AD5.
What leaks: inviter `ownerId` ↔ invite issuance/revocation/redemption timing; blob
sizes; the redeemer's IP. The invitee is protocol-anonymous (no JWT on the capability
endpoints); the relay learns the social edge only later, and only if a shared realm
follows (the attack-surface #10 co-membership residual, unchanged).
Residual: **Accepted, documented** — consistent with the A6 metadata posture.

**T-CI-07. Self-asserted name spoofing at completion.**
Actors: AD2–AD4.
Defense: structural (contact doc §6): petname on `Person` is user-owned; the self-asserted
name stays quarantined on `TenshuIdentity`; suggest-don't-merge against imported sources;
checkmark derived from key verification only.
Residual: **Low.**

**T-CI-08. At-rest redemption state on the invitee device.**
Actors: AD1–AD2. Assets: A25. Invariants: I8.
`PendingRedemption` holds raw `K_link` for up to 72 h.
Defense: `SecureStorageDelegate` (TA2); cleared on every terminal state; compromise
yields impersonation-of-the-invitee for that one invite only (the attacker still cannot
read anything — the handshake conveys no data keys).
Residual: **Low.**

**T-CI-09. A13 public-key exposure in the link.**
Actors: channel observers, AD4–AD5.
The link reveals the inviter's long-lived identity public key to anyone who sees it,
enabling cross-context linkability of the inviter ("these two invites came from the same
identity").
Defense: none in v1 — accepted; this is precisely the linkability the contact doc §4.2's
*pairwise identities* door exists for, and adopting them is already flagged there as an
explicit future amendment (contact doc §9 last bullet).
Residual: **Accepted, documented.**

**Invariants:** I1–I9 unchanged. House Rule #1 audit: every relay-visible field in
§6 is routing metadata or sealed ciphertext; no decrypt code lands server-side.

## 10. Phasing

1. **CI-1 — crypto + schema.** `sealToIdentity` generalisation, contact-SAS helper,
   invite-secret helpers (reuse pairing's base32/SHA-256 utilities); `:contact-service`
   v1 modules with `Person` / `TenshuIdentity` / `ContactInvite`. In-process tests +
   pinned SAS vector. **Status: shipped 2026-06-11.** Notes: `wrapKeyForIdentity` now
   *delegates* to `sealToIdentity(info = "tenshu/key-wrap/v1")` (byte-identical blobs,
   cross-compat pinned in `SealToIdentityTest`); pairing had no base32/SHA-256-hex
   helpers to reuse (its link format is Bech32), so `InviteSecret.kt` carries its own
   RFC 4648 base32; the SAS vector is pinned at `744595` for
   `pubA = 32×0x01, pubB = 32×0x02`; a `ContactService` schema (persons /
   tenshuIdentities / contactInvites relations) landed alongside the composites so the
   CI-3 ops and the CI-4 picker have a chain-DSL surface.
2. **CI-2 — relay mailbox.** `InviteRouting` + `InviteStore` (+ SQLite store), the seven
   §6 endpoints, single-slot + TTL + cap semantics. Routing tests (the
   `AdmitDeviceRoutingTest` shape). **Status: shipped 2026-06-11.** Notes: revocation
   leaves a `REVOKED` tombstone until expiry (so a redeeming invitee sees `410`, not
   `404`); "no ack yet" and "unknown invite" share `404` (the invitee polls until `200`
   or `410`); the per-owner cap is a `RelayConfig` field (default 32, constructor-set
   for tests, no env plumbing yet); invite GC runs hourly (`INVITE_GC_SWEEP_INTERVAL_MS`)
   rather than the nightly cadence of the heavier sweeps.
3. **CI-3 — ops + flows.** The five §7.3 ops, inviter sync-cycle pickup, invitee
   `AwaitInviteAck`, completion prompt wiring. Dev-relay system test: full mutual
   handshake, both `TenshuIdentity` rows created, relay state freed. **Threat-model
   amendment (§9) lands in this PR** (first PR that ships the new wire surface
   end-to-end; House Rule #2). **Status: shipped 2026-06-11** (threat-model §8.13 +
   A24/A25 + surface #11 applied; A22/A23 left reserved for the logging facade).
   Implementation deviations, all doc'd in code: sealed payloads are **kotlinx-JSON**,
   not CBOR (the project's control-plane codec; the binding is the AEAD `info` +
   `inviteId` AAD, not the codec); the ops take a few extra optional params the UI
   needs (`selfAssertedName` on redeem/complete — the §11 display-name question pushed
   to the caller; `sasConfirmed` on complete **and** `awaitInviteAck` so the QR flow
   can mint `VERIFIED`/`QR_SAS` on both sides); the sync-cycle pickup rides a new
   generic `SyncScheduler.addSyncCycleHook` seam so `:sync-service-proxy` takes no
   `:contact-service` dependency, and flips redeemed invites to `REDEEMED` (the
   synced state CI-4's completion prompt reacts to). The pickup stashes verified
   responses in-memory only — the relay retains un-acked responses, so a restart
   re-fetches.
4. **CI-4 — Drive Manage access v1.** Member list (`getSharedRealmMemberships`) +
   Invite button → share sheet; share-intent prompt parked behind the join runtime.
   **UX pass done (2026-06-12):** the spec + implementation handoff is
   [`drive-manage-access-ux.md`](drive-manage-access-ux.md) — two PRs, CI-4a (link
   flow + completion prompt + redemption incl. the new `ListPendingRedemptions` read
   op) and CI-4b (QR + SAS via the `control-shared → ui-core` component move).
   **CI-4a shipped 2026-06-12.** Implementation deltas (the ux doc's §§2.3/3.2
   needed protocol surface CI-3 didn't expose; all doc'd in that doc's §9): the
   `claimed*` pair on `ContactInvite` (§7.1 above), `AwaitInviteAck` now returns a
   transient `InviteAckResult` composite (status + the ack's name claim /
   fingerprint / intent) instead of a bare status string, and redeem maps the
   relay's `409`/`410` to stable `RedeemContactInvite.ErrorCode` codes so the UI's
   T-CI-01 loud copy survives the IPC boundary. **CI-4b (QR + SAS) shipped
   2026-06-12:** `QrCode`/`QrScanner` moved `control-shared → :ui-core`, the link
   sheet's QR toggle, scan-to-redeem, and the §5.3 SAS panels. The SAS is
   **proxy-derived, display-ready** (`claimedSas` above + `InviteAckResult.sas` +
   `PendingRedemptionInfo.sas`) because only the proxy holds the raw keys; the
   invitee's *Codes match* confirm restarts the live ack wait with
   `sasConfirmed = true` (the literal "confirm after ACCEPTED" sequencing was
   unimplementable — close-out deletes the redemption; see the ux doc §9). **CI-4
   complete** (Android manual pass pending).

## 11. Open questions

- **Membership grant protocol** — wrapping Kr to a `TenshuIdentity` + cross-user device
  admission + the invitee-side accept/mount UX. The next protocol slice, on
  [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md) §3.1's runtime; explicitly
  out of scope here (§2).
- **Push-assisted delivery** — response/ack arrival via `:notification-service` instead
  of polling; needs cross-realm notification routing thought. Polling floor ships first.
- **Invitee-side cross-device redemption hand-off** (v2 nicety; results already sync).
- **Display-name source** for the ack's `selfAssertedName` — the realm owner's
  `displayName` (pairing payload `ownerMetadata` already carries one); prompt when unset.
- **Re-verification UI** (running the §5.3 SAS later from the contact detail screen) — v2
  with the Contacts app.

## 12. Implementation Notes for Claude Code

Consolidated file-level handoff (the [`sync-relay-api.md`](sync-relay-api.md) §9 /
[`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) §13 template).
If anything below contradicts §1–§11, those win. One PR per CI phase (§10); each phase's
tests green before the next.

### Order and dependencies

1. **CI-1** — no dependency on any in-flight proposal. Pure additive crypto + schema.
2. **CI-2** — depends on CI-1 only for shared constants (invite-id/blob shapes).
3. **CI-3** — depends on CI-1 + CI-2. **The §9 threat-model amendment is applied to
   `docs/sync/threat-model.md` in this PR** (first PR shipping the wire surface
   end-to-end; House Rule #2). Check whether `core-logging-facade.md` has landed A22/A23
   first and renumber per the §9 note if needed.
4. **CI-4** — UI; UX pass done — implemented from
   [`drive-manage-access-ux.md`](drive-manage-access-ux.md) §8 (CI-4a + CI-4b both
   **shipped 2026-06-12** — CI-4 complete).
5. The cross-user membership grant, long-poll, and push delivery are **not** in scope
   (§11). Do not start them from here.

### Module setup (CI-1)

New five-module split in `settings.gradle.kts`: `:contact-service`,
`:contact-service-common`, `:contact-service-client`, `:contact-service-proxy`,
`:contact-service-test-utils`. **Mirror `:preference-service`** (the most recent
symmetric service) for build files, module wiring, and DI registration; read
`docs/agent.md` (Service & Operation pattern, schema DSL) and `docs/schema-dsl.md`
before writing schemas. Contact entities route to the **personal** realm — natural
unprefixed DataIds, no `$realmId::` namespacing.

### File-level table

Verify exact package paths against existing neighbours before creating files; the paths
below follow the `org.tenshu.*` conventions of the pairing work.

**CI-1 — new:**

| Path | Contents |
|---|---|
| `core-common/.../util/ContactSas.kt` | `deriveContactSas(pubA, pubB): String` — lexicographic-sort ikm, `tenshu/contact/sas/v1`, 6-digit derivation reusing `PairingSas`'s `be32 >> 12 % 1_000_000` logic (§5.3). |
| `core-common/.../util/InviteSecret.kt` | Mint the §3 triple; encode/decode both link forms (`https://tenshu.app/i#v1.` base64url + `tenshu-invite:v1?i=&k=&p=` base32). Reuse `PairingSecret`'s base32 + SHA-256-hex helpers. |
| `SyncCryptoManager` (`:core-common`) | Add `sealToIdentity` / `unsealFromIdentity` per §5.1 — generalise `wrapKeyForIdentity` with parameterised `info` + extra AAD; **existing `tenshu/key-wrap/v1` call sites untouched**; match the existing blob layout (nonce placement) rather than the §5.1 sketch. |
| `contact-service/.../Person.kt`, `TenshuIdentity.kt`, `ContactInvite.kt` | Schemas per contact doc §4.1/§4.2 + §7.1 here, with `DataIdProvider`s (`PERSON_ID` / `FINGERPRINT` / `INVITE_ID`), all `Syncable(Eager)`. House Rule #6: no instance-level caching. |
| `contact-service-common/.../ContactServiceCommonModule.kt` | Schema/module registration, mirroring `PreferenceServiceCommonModule`. |

**CI-2 — new (`:sync-relay-server`):**

| Path | Contents |
|---|---|
| `.../routing/InviteRouting.kt` | The seven §6 endpoints. JWT via the existing `requireOwnerId` helper on the four authed routes; `inviteId`-capability on the rest. Single-slot state machine, `expiresAt = min(requested, now + 7d)`, per-owner open-invite cap (config, default 32), ~4 KiB body cap → `413`. Error codes per §6. |
| `.../store/InviteStore.kt` + `SqliteInviteStore.kt` | Storage-SPI pair per [`sync-relay-persistence.md`](sync-relay-persistence.md): in-memory default, SQLite when `DATABASE_URL` set. Retention sweep per §6 (record → `expiresAt`; blobs → `max(expiresAt, respondedAt + 72h)`; `done` deletes). Shared default GC logic over the SPI, not re-derived SQL. |

**CI-3 — new:**

| Path | Contents |
|---|---|
| `contact-service/.../{CreateContactInvite,RevokeContactInvite,RedeemContactInvite,AwaitInviteAck,CompleteContactHandshake}.kt` | Op schemas (§7.3). Non-suspend interfaces, `IoOperation` + `TaskInteractor` (House Rule #3 / ADR 0012). **None carry `HeadlessSafe`** (I9). |
| `contact-service-client/.../*Ro.kt` + `ContactServiceClientModule.kt` | Typed client extensions, mirror `:preference-service-client`. |
| `contact-service-common/.../InviteRelayClient.kt` | Client interface for the §6 endpoints (request/response DTOs, base64 JSON envelopes per relay conventions). |
| `contact-service-proxy/.../RestInviteRelayClient.kt` | Ktor impl; reuse the existing `jwtTokenProvider` seam from `PlatformProxyModule` (personal-realm `ownerId`) for the authed routes. ⚠ This is the one wiring decision to sanity-check in DI review — do **not** widen `SyncRelay` itself. |
| `contact-service-proxy/.../impl/*Implementation.kt` | The five ops per §4. `Complete…` enforces the `SHA-256(inviteSecret) == secretHash` check, the burn-on-bad-secret → `COMPROMISED` path, and creates `Person` + `TenshuIdentity` (verification per §5.3 flow: `QR_SAS` → `VERIFIED`, else `UNVERIFIED`). |
| `contact-service-proxy/.../PendingRedemptionStore.kt` | `SecureStorageDelegate`-backed (§7.2); cleared on every terminal state; **never synced**. |
| `docs/sync/threat-model.md` | Apply §9 verbatim: §5 A24/A25, §7 surface #11, new §8.13 T-CI-01–09. Update the doc's header amendment list. |

**Modified (CI-3):** the proxy sync cycle gains a "pending invites?" check that polls
`GET /sync/invite/responses` (inviter side) — gate on locally-`PENDING` `ContactInvite`
rows so steady-state sync traffic is unchanged.

**CI-4 — modified:** `drive-shared/.../FileActionsViewModel.kt`
(`onManageAccessClicked()` / `onShareClicked()` — currently `TODO()`), new Manage-access
sheet (member list via `getSharedRealmMemberships` + Invite → `CreateContactInvite` → OS
share sheet). **Every new user-facing string via `stringResource(…)`** (ADR 0016; bare
`'`, not `\'` — CMP 1.7.3). Typed VM errors, no freetext (`core-error-channel`).

### Test plan

- `ContactSasTest` — pinned cross-platform vector (`<TBD: pin on first implementation>`,
  pairing convention: first platform pins, all platforms assert); bit-flip in either
  pubkey changes the SAS; argument order does **not** (lexicographic sort).
- `InviteSecretTest` — round-trip both encodings; unknown-version hard error; entropy
  sanity (no collision over 32 generations).
- `SealToIdentityTest` — round-trip; wrong recipient key fails; AAD (`inviteId`)
  mismatch fails; existing `wrapKeyForIdentity` vectors still pass.
- `InviteRoutingTest` (the `AdmitDeviceRoutingTest` shape) — register / `409` dup /
  cap exceeded; revoke requires owning `iss`; response: single slot `409`, expired
  `410`, unknown `404`, oversized `413`; ack/done lifecycle frees state.
- `SqliteInviteStoreTest` — SPI parity with in-memory + retention sweep.
- `ContactHandshakeRoundTripTest` (`:contact-service-proxy`, mock relay) — full mutual
  flow creates both sides' `Person` + `TenshuIdentity`; wrong-`K_link` response burns the
  invite (`COMPROMISED`, sealed reject posted); forged ack (wrong `K_link` / wrong
  inviter key) is rejected invitee-side; link-to-existing re-points `personId` and never
  touches `verification` (contact doc §6.3).
- Dev-relay system test (the `SharedRealmStreamSyncSystemTest` shape, **real crypto** —
  that test's history shows stub crypto hides curve bugs) — two proxies, full handshake
  over the live relay, both identity rows present, relay state freed after `done`.

### Naming and gotchas

- Threat IDs `T-CI-*`; assets A24/A25; relay prefix `/sync/invite/*`; status enums are
  `TString` constants (`PENDING`, `REDEEMED`, `COMPLETED`, `REJECTED`, `REVOKED`,
  `EXPIRED`, `COMPROMISED`).
- `K_link` is spelled `inviteSecret` in code; the relay never receives it **or its
  hash** — if a hash field shows up in a relay DTO, the design has been violated.
- Don't bump CMP (pinned 1.7.3, tech-debt entry).
- Update this doc's §10 phase statuses + the contact doc's §8 v1 bullet as each phase
  lands (status-pin convention).

## References

- [`contact-service-identity-model.md`](contact-service-identity-model.md) — the data model + decisions this implements (§8 v1, §9, §10).
- [`../sync/pairing-flow.md`](../sync/pairing-flow.md) + [`sync-pairing-flow-improvements.md`](sync-pairing-flow-improvements.md) — the sibling protocol; primitives, SAS/fingerprint conventions, secret format.
- [`sync-relay-api.md`](sync-relay-api.md) — endpoint conventions, auth model, error vocabulary.
- [`sync-multi-realm-runtime.md`](sync-multi-realm-runtime.md) §3.1 — the cross-user join runtime the membership grant rides.
- [`tenshu-rei.md`](tenshu-rei.md) Q53 — invitation-link (not bearer-key) decision; Q30 — per-realm capability tokens the later grant interacts with.
- [`../sync/threat-model.md`](../sync/threat-model.md) — receives §9.
