# Delegated Device-Admission Authority — Any Realm Device Can Admit the Next

**Status: v1.0 (2026-07-03). PHASE MAP COMPLETE — P0–P4 MERGED,
P5 (subtree revocation + lineage UI, Q3 resolved:
conservative subtree) merged. All open questions Q1–Q5 resolved (Q1
chained · Q2 opportunistic re-root, depth 16 · Q3 conservative subtree · Q4
self-mint/announce/grant · Q5 no). Remaining: the on-device round-trip pass
(real hardware). See the §8 table + per-phase as-built notes.**

Surfaced by an on-device pairing test: approving a new device from a *joiner*
device fails with `Master keypair not available; SyncManager.initialize must be
called first`. Root cause is architectural, not a bug — see §1.

---

## 1. The problem, and the prize

Today, admitting a new device to a personal realm requires the **master identity
(A1)**. `ApprovePairingRequestImplementation` signs the new device's delegation

```
Sign_A1( newDeviceSigningPubKey ‖ newDeviceId ‖ epoch )
```

with `SyncManager.currentMasterKeyPair`, and the relay verifies it against
`realm.masterPublicKey` (`MasterDelegationVerifier.pairingResponseMessage` +
`.verify`). A1 lives on exactly two things: the **owner/genesis device** (or any
device recovered *with the recovery phrase*), and the **recovery phrase** itself.

A **joiner** — a device paired *into* the realm — receives `derivedKeys` + the
realm A13 via the pairing blob (`PairingKeyBlob.pack`), but **never A1**
(`SyncManagerImpl.getUnlockStatus`'s own comment: *"`currentMasterKeyPair` is only
set on master devices"*). So a joiner can read, write, and sync — but **cannot
admit a new device**. `ApprovePairingRequest` throws at its first step.

### The resilience prize (the actual motivation)

Concentrating admission on A1 makes the realm **fragile**:

| Failure | Today (A1-only admission) | With delegated admission |
|---|---|---|
| Lose the owner device, keep a joiner + the phrase | Existing devices keep working; **can't add a new device** except by recovering the phrase onto a device first | Any surviving device admits the next — no phrase needed |
| Lose the owner device **and** the phrase | **Realm is terminal** — no new devices ever; when the last device dies, total loss | Any surviving device admits the next indefinitely; only *total* device loss + phrase loss is terminal |
| Device theft | Data compromise (revocable via removal + Kr rotation, GR-7) | Same — **unchanged** (see §3) |

The upgrade the maintainer flagged: **losing the realm should require losing
*every* device *and* the recovery code — not the *first* device and the code.**
Delegated admission delivers exactly that.

---

## 2. Decision: delegate a capability, do **not** sync A1

Two mechanisms reach "any device can admit." They are **not** equivalent on
security — this is the load-bearing decision of the proposal.

### Option A — Sync the raw master key (A1) to every device — **rejected**

Ship `currentMasterKeyPair` in the pairing blob so every device holds A1.

- ✅ Resilience: any device can admit / recover / rotate.
- ❌ **Severe, unrevocable compromise.** A1 *is* the recovery seed (the
  `MasterKeyWrapper v2` already carries BIP-39 entropy, A1 amended). A single
  stolen/compromised device now yields: realm-root impersonation, rogue-device
  admission, **recovery-phrase exfiltration**, and there is **no revocation** —
  removing the device can't un-leak the master key, and rotating the master
  identity is a full-realm re-establishment that the thief's copy can also drive
  (or race). Device theft becomes game-over for the whole realm and its recovery
  anchor. This is not a "theoretical" downgrade; it is the largest possible one.

### Option B — Delegate a revocable admission capability (A33) — **recommended**

Give each device its own **Device-Admission Capability** (a fresh Ed25519
keypair, **A33**) plus a **capability certificate** signed by an authority that
chains back to A1. A device admits the next by signing the new-device delegation
with **its A33 key**, presenting the certificate chain; the relay verifies the
chain terminates at `realm.masterPublicKey`.

- ✅ Resilience: **identical to Option A** — any surviving device admits the next.
- ✅ **Bounded, revocable compromise.** A stolen device holds `derivedKeys`
  (revocable — device removal + Kr rotation, already built GR-7) and an A33
  capability (**revocable** — cap revocation list, §5). It does **not** hold the
  master private key or the recovery seed, so no root impersonation and no phrase
  exfiltration. The residual new risk — a compromised device admitting a rogue
  device before revocation — is detectable (the rogue Device row syncs) and
  revocable, the same shape as an over-broad A26 member key in GR.

**Conclusion:** Option B buys the entire resilience win for a *genuinely*
theoretical security cost, where Option A pays a real, unrevocable one. The
recovery phrase's role correctly **shrinks** to true disaster recovery (all
devices lost) and master rotation — it is no longer on the day-to-day
add-a-device path. This proposal specifies Option B.

---

## 3. What does **not** change

- **Data-plane compromise & revocation.** A stolen device's `derivedKeys` are
  exactly as exposed as today; removal + Kr rotation (GR-7) is unchanged.
- **The recovery phrase remains the root.** It still re-derives A1 for full
  recovery (all devices lost) and for master-identity rotation. "Reveal recovery
  phrase" (`Recovery.revealPhrase`, §7.4) **stays owner/A1-holder only** — we do
  not spread the seed (that is precisely the Option-A trap).
- **Threat-model House Rule #1.** The relay still sees only ciphertext + signed
  capability certificates it verifies but cannot mint. No plaintext.

---

## 4. Protocol

### 4.1 The capability (A33)

Per device, minted at admission time:

- **A33 keypair** — Ed25519 (`SyncCryptoManager.generateDeviceSigningKeyPair`
  shape). The private key signs new-device delegations *and* (for transitive
  admission, §4.3) child capability certificates. Held in memory + wrapped at
  rest like A4; **never** relay-visible in private form.
- **Capability certificate** — signed by the *admitting* device's authority key:

  ```
  cert = Sign_parent( "tenshu-admit-cap-v1" ‖ ownerId ‖ deviceA33Pub ‖ deviceId ‖ mintedAtEpoch )
  ```

  where `parent` is **A1** when an owner mints it, or the admitting device's
  **A33** for transitive admission (§4.3). The cert names the child's A33 public
  key, binding it into the chain.

  ⚠ **Superseded by the length-prefixed signed messages (`admission-revocation-names-the-capability.md` §7d, 2026-10-28).** The cert is now signed over
  `LP("tenshu-admit-cap-v2") ‖ LP(ownerId) ‖ LP(deviceA33Pub) ‖ LP(deviceId) ‖ LP(mintedAtEpoch)`,
  with `LP(x) = u32be(|x|) ‖ x`. The §4.3 delegation is likewise
  `LP("tenshu-admit-delegation-v1") ‖ LP(Ypub) ‖ LP(Yid) ‖ LP(epoch)`. Builders:
  `sync-service-common/.../SignedMessageEncoding.kt` and `AdmissionCapability.kt`. The layouts in
  this section are the original design.

### 4.2 Delivery — extend the pairing blob

`PairingKeyBlob.pack` today carries `sharedIdentityKey ‖ sharedVolumeKey ‖
A13pub ‖ A13priv`. Add, when the admitter can supply them:

```
… ‖ childA33pub ‖ childA33priv ‖ len-prefixed(certChain)
```

`certChain` is the ordered list of certificates from the child's cert up to the
A1-signed root (§4.3). `PairingKeyBlob.unpack` already recovers field count from
length and tolerates shorter legacy blobs — the joiner installs the A33 keypair +
chain when present, and is a phrase-less non-admitter (today's behaviour) when
absent (an owner that can't currently mint, e.g. locked).

### 4.3 Admission by a delegated device (the new approve path)

When device **X** (holding A33_X + `certChain_X`) admits new device **Y**:

1. X signs Y's device delegation with **A33_X** (not A1):
   `deviceDelegation = Sign_A33X( Ypub ‖ Yid ‖ epoch )`.
2. X mints Y's capability cert with A33_X (so Y can also admit — the resilience
   property): `cert_Y = Sign_A33X("tenshu-admit-cap-v1" ‖ …)`.
3. X POSTs to the relay: `deviceDelegation`, `Ypub`, `Yid`, `epoch`, **and**
   `certChain_Y = [cert_Y] + certChain_X`.
4. Relay verifies the **chain to root** (§4.4), then registers Y's signing key.
5. The A33_X-encrypted `PairingKeyBlob` hands Y its `derivedKeys` + A13 + **A33_Y
   keypair + certChain_Y**, so Y is a full admitter too.

An **A1-holder** (owner / phrase-recovered) admits exactly as today, except it
*also* mints the child's A33 cert directly against A1 (`certChain = [cert_Y]`,
one link) — so owner-admitted devices get depth-1 chains, joiner-admitted devices
get depth-N.

### 4.4 Relay verification — generalise the chain verifier

The relay currently verifies one signature (`MasterDelegationVerifier.verify`).
It must instead verify:

- **Chain link-by-link:** each `cert[i]` verifies against `cert[i+1]`'s named
  A33 pubkey (child signed by parent), the last cert verifies against
  `realm.masterPublicKey` (root signed by A1).
- **Leaf delegation:** `deviceDelegation` verifies against the *admitting*
  device's A33 pubkey (`cert[0]`'s subject).
- **Not revoked:** no cert in the chain is in the realm's capability revocation
  set (§5).
- **Depth bound:** reject chains deeper than `MAX_ADMIT_DEPTH` (§7 Q2).

This is a direct generalisation of the **already-shipped** `MembershipChainVerifier`
(`:sync-service-common`) — today a fixed 2-link master→member→device chain for
shared realms — to an N-link chain, over the same `Ed25519SignatureVerifier` SAM.
The GR admission endpoint `POST /sync/realm-member/admit` is the precedent for
capability-chain admission; the personal-realm pairing-response path
(`POST /sync/pairing/response`) grows the same verification.

---

## 5. Revocation

Removing a device must revoke its admission authority, else a removed device
keeps minting admissions.

- **Per-cert revocation set** on the relay, keyed by `deviceId` (or cert hash),
  master-signed by an A1-holder (mirrors GR's master-signed revoke). Verified in
  §4.4.
- ⚠ *Superseded by `admission-revocation-names-the-capability.md` §7j.10 — no subtree revocation.*
  **Subtree question (§7 Q3):** revoking X should arguably revoke every device X
  admitted (its subtree), since those were vouched for by X. v1 can take the
  conservative line — **revoke the subtree** — computed from the synced `Device`
  rows' recorded parent (add `admittedByDeviceId` to the Device schema). A
  compromised X's whole lineage drops in one revoke.
- **Kr rotation** on removal is unchanged (GR-7): the retained-keyring +
  trial-decrypt subsystem already re-keys remaining members. Delegated admission
  rides on top of it.

---

## 6. Relationship to inverted-direction pairing v2

`sync-pairing-inverted-direction.md` (v2 — new device shows QR, existing device
scans) is **related in surface, orthogonal in concern**:

- **Orthogonal:** v2 only changes *which way the QR points*. Its responder
  (existing device) **still signs an A1 master delegation** (§3.3 Step 3, §5.4
  extends `MasterDelegationVerifier`). So **v2 alone does not fix
  joiner-can't-approve** — a joiner responder in v2 hits the identical wall. The
  two proposals are on different axes: *who-may-admit* (this) vs *which-way-the-
  QR-points* (v2).
- **Shared surface — co-design:** both rework the **same** files — the approve op
  (`ApprovePairingRequest` / v2's `ApprovePairingOffer`), `MasterDelegationVerifier`,
  the relay admission verification, and `PairingWizardScreen`/`ViewModel` +
  `SyncManagerImpl`. Reworking `MasterDelegationVerifier` twice (once to add the
  v2 offer-response message, once to verify a capability chain) is wasteful and
  risky. **Recommendation:** land the chain-verification generalisation *once*,
  and have both the v1 approve path and the v2 offer-response path feed it.
  Sequence v2's relay/verifier phase (P2) and this proposal's verifier phase
  together.

---

## 7. Open questions

- **Q1 — Flat vs chained. RESOLVED (P3): chained.** Every admitter mints —
  an A1-holder against A1 (depth-1), a delegated admitter with its A33
  prepending to its own chain (depth+1). Flat/depth-1 was the stepping-stone
  option but leaves a phrase-less realm unable to grow new admitters — failing
  exactly the resilience scenario the track exists for. The flat/chained split
  never shipped as separate releases; P3 went straight to chained.
- **Q2 — Depth bound. RESOLVED:** `MAX_ADMIT_DEPTH = 16` (P1, overridable per
  call). Re-root (P4) is **opportunistic, not periodic**: a device holding a
  chain deeper than 1 announces its A33 key whenever it runs; any A1-holder
  that observes the announcement re-mints a depth-1 grant, which the device
  adopts because it's flatter. No timer, no scheduled pass — depth self-heals
  whenever an owner device is online.
- ⚠⚠ **Q3 is SUPERSEDED (2026-11-11) by `admission-revocation-names-the-capability.md` §7j.10:**
  removal revokes only the removed device's own key; its admittees are kept or removed per device in
  the removal dialog, and the relay checks revocation on the approver (`chain[0]`) only. What follows
  is history.
- **Q3 — Revocation granularity. RESOLVED (P5): conservative subtree.**
  Removing a device master-signs revocations for it AND every transitive
  descendant per `admittedByDeviceId` — including re-rooted ones, because the
  P4 re-root is automatic and must not launder a compromised admitter's
  lineage. Subtree members lose *minting power only*: their rows and data
  access are untouched and they stay listed (with lineage) for the user to
  remove individually — no destructive cascade.
- **Q4 — Migration. RESOLVED (P4), with one deliberate inversion:** the owner
  does NOT mint keypairs for devices (that would sync A33 *private* keys
  realm-wide — a stolen device could then impersonate every other device's
  admission identity). Instead the device **self-mints** its keypair locally
  and announces only the public key (A4-signed `AdmissionKeyAnnouncement`);
  the A1-holder verifies the announcement against the `Device` row and answers
  with a public `AdmissionCertGrant` (T-DA-06). Until the grant lands, the
  device stays a labelled non-admitter (§8 P0), exactly as before.
- **Q5 — Should "reveal recovery phrase" ever spread?** No (§3). Keep it
  A1-holder-only; delegated admission deliberately does not distribute the seed.

---

## 8. Phasing (each independently shippable)

| Phase | Scope | Deliverable |
|---|---|---|
| **P0 — UI guard (ship now)** | `PairingWizardScreen`/`ViewModel` + `SyncManager.getUnlockStatus`-style role check | On a device with no admission authority (no A1, no A33 cert), the "add a device / show a code" flow is disabled with a clear message ("Add new devices from a device set up with your recovery phrase"), replacing the cryptic `Master keypair not available` failure. Closes the reported UX immediately; no protocol change. |
| **P1 — Crypto + schema** ✅ | `:core`/`:sync-service-common`/`:identity-service` | A33 keypair + capability-cert format; generalise `MembershipChainVerifier` to N-link; `PairingKeyBlob` v2 fields; `admittedByDeviceId` on `Device`. Unit tests: chain verify (valid/forged/wrong-root/over-depth). |
| **P2 — Relay** ✅ | `:sync-relay-server` | Chain verification on the pairing-response (+ admit) path; capability revocation store; threat-model amendment (A33, T-DA-01..0n, new surface). **Co-design/sequence with inverted-direction P2.** |
| **P3 — Proxy ops** ✅ | `:sync-service(-proxy)` | `ApprovePairingRequest` signs with A33 when the device isn't an A1-holder (falls back to A1 when it is); pairing blob delivers the cap chain; joiner installs A33 + chain on completion. |
| **P4 — Migration + re-root** ✅ | `:sync-service(-proxy)` | Owner mints/syncs caps to existing devices; periodic re-root (Q2). |
| **P5 — Revocation UX + Kr** ✅ | Drive/Control UI + `:sync-service` | Remove-device revokes the cap (subtree, Q3) + rides GR-7 Kr rotation; Manage-devices surfaces admission lineage. |

**Threat-model amendments land per phase (House Rule #2).**

### P1 as-built

Deltas and pins vs the sections above; no behaviour change ships with P1
(nothing mints or presents a chain until P2/P3):

- **New verifier, same seam.** `AdmissionChainVerifier` +
  `AdmissionCapabilityCert`/`AdmissionRevocation` (`:sync-service-common`) sit
  *next to* `MembershipChainVerifier` rather than literally replacing it — the
  GR 2-link chain has heterogeneous link types (capability + device binding),
  so folding it into the N-link walk would churn shipped relay-verified code
  for no shared logic beyond the `Ed25519SignatureVerifier` SAM, which both
  use. The N-link chain is homogeneous (certs all the way), ordered leaf →
  A1-signed root.
- **Revocation format landed with P1** (tag `tenshu-admit-revoke-v1`), not P2
  — the verifier's §4.4 "not revoked" bullet needs the record shape; P2 adds
  only the store + routing. Epoch semantics mirror `MembershipRevocation`:
  revoked iff `revocation.epoch >= cert.mintedAtEpoch`, master-signed only.
  The §5 *subtree* revoke falls out of per-link checks — revoking X breaks
  every chain running through X's cert; no lineage walk needed at verify time
  (`admittedByDeviceId` is for the P5 *minting* of subtree revocations).
- **Depth bound:** `MAX_ADMIT_DEPTH = 16` as the Q2 provisional default,
  overridable per call; Q2 stays open only on the re-root cadence.
- **Chain transport codec** (`encodeAdmissionCertChain`, 4-byte-BE
  length-prefixed) lives with the records so blob (P1) and relay POST (P2)
  share one framing.
- **`PairingKeyBlob` v2** stays length-dispatched (no version byte): the
  admission section (`a33pub ‖ a33priv ‖ certChain`) only ever rides on top of
  the full 128-byte base — a host that can't supply A13 can't mint either. A
  ≥196-byte blob parses as v2; a corrupt admission tail degrades to the
  128-byte read (keys + A13 intact, joiner is a non-admitter) rather than
  crashing, mirroring the legacy fallback.
- **`Device.admittedByDeviceId`** is an *optional* schema field with a
  nullable `getOptionalField` accessor (the core layer enforces this for
  optional fields); empty/absent = genesis / pre-P1 row — treat the same.
- **Q1 unchanged:** the verifier handles flat and chained identically; the
  minting-policy decision still lands in P3.

---

### P2 as-built

- **One wire field, not a new endpoint, for admission:** `POST
  /sync/pairing/response` gains an optional `admissionCertChain` (base64 of the
  P1 codec); `masterDelegation` doubles as the delegation signature in both
  modes — A1-signed without a chain (byte-identical to before), A33-signed with
  one. `401` bad chain/delegation, `403` revoked, `400` undecodable.
- **Revocation:** `POST /sync/admission/revoke`, unauthenticated with the
  master signature as authorisation (the `/sync/realm-member/revoke` posture).
  It strips **minting power only** — device *removal* (registration purge, Kr
  rotation) stays the existing device-management flow. The relay never walks
  lineage: revoking X kills every chain through X's cert at verify time; the
  `admittedByDeviceId`-driven subtree *minting* of revocations is client-side
  (P5).
- **Ledger:** `AdmissionRevocationRegistry` SPI — in-memory default +
  `SqliteAdmissionRevocationStore` (migration **v11**, `admission_revocation`).
  Durable for the same reason as `member_revocation`.
- **Threat model:** A33 asset, surface #16, §8.17 T-DA-01..05 + header
  sub-amendment landed in the same PR (House Rule #2). Notable calls: T-DA-01
  (stolen device admits before revocation) is accepted at Medium as the same
  shape as the existing stolen-unlocked-device window; T-DA-04 (AD5 ignores the
  ledger) accepted consistent with the relay-trust posture — the client-side
  epoch + Kr-rotation backstop is the real boundary.
- **Dormant until P3:** no client presents a chain yet; the inverted-direction
  co-design (§6) is unaffected because the chain verification is a pure
  function both response shapes can call.

### P3 as-built

The phase that lights the path up end-to-end: from P3 on, **every newly-paired
device receives an A33 capability and can itself admit the next device** — the
realm is lost only if every device *and* the phrase are lost.

- **Signer fallback exactly per §9:** in-memory A1 → phrase-derived A1 → A33
  keypair + chain → the labelled P0 error. An A1-holder's POST is wire-identical
  to pre-P3; a delegated admitter signs the delegation with A33 and sends
  `admissionCertChain`. The relay-forgot-realm self-heal (re-bootstrap + retry)
  stays A1-only — genesis needs A1, so an A33-only admitter surfaces the relay
  error instead. ⚠ *Amended 2026-10-28:* the re-bootstrap stays A1-only, but the
  **retry** no longer does — an A33-only admitter re-posts once too, so a lost
  reply is not an error when the joiner is paired (`pairing-flow.md` Step 3).
- **Q1: chained** (see §7) — every approve mints the joiner a fresh A33 keypair
  (⚠ *since 2026-10-28* once per pinned request per session, reused by a re-approve,
  so a "consumed" retry records the capability the joiner actually holds)
  + cert (A1-signed depth-1 from an owner; A33-signed, prepended to the
  admitter's chain, from a delegate), delivered via the `PairingKeyBlob` v2
  fields. The A33 private key rides only inside the ECDH-encrypted blob.
- **Joiner custody:** a new **J3** joiner-payload shape (`J2` +
  `a33Pub|a33Priv|chain` before the trailing ownerId) persists the admission
  material through restarts and passphrase/biometric re-wrapping;
  `SyncManager.getAdmissionKeyPair()`/`getAdmissionCertChain()` expose it
  (interface defaults — fakes and pre-P3 impls unaffected).
- **Lineage:** the admitting device stamps `admittedByDeviceId` on the
  authorised `Device` row (the P5 subtree-revoke input).
- **Hardening:** `decodeAdmissionCertChain` bounds the claimed cert count by
  the remaining byte length before allocating — a hostile count on the relay's
  admission path previously drove an OOM-sized allocation attempt (T-DA-05
  hygiene, found by test).
- **Q4 unchanged:** pre-P3 joiners hold no A33 and stay honest non-admitters
  (P0 message) until the P4 migration pass re-mints for them.

### P4 as-built

- **No private key ever syncs** — the design deviation from the Q4 sketch (see
  Q4 above): device self-mints, announces the public key, owner grants the
  public chain. Two new realm-encrypted Syncable rows in `:identity-service` —
  `AdmissionKeyAnnouncement` (deviceId, a33Pub, A4 signature over
  `"tenshu-admit-announce-v1" ‖ deviceId ‖ a33Pub` — now
  `LP("tenshu-admit-announce-v2") ‖ LP(deviceId) ‖ LP(a33Pub)`, see
  `SignedMessageEncoding.kt`) and `AdmissionCertGrant`
  (deviceId, masterPub, depth-1 chain). No relay/protocol change.
- ⚠⚠ **`AdmissionKeyAnnouncement` is NOT a realm-wide directory of A33 keys, and
  nothing else is either.** It is published only by the two cases below — a
  capability-less device (migration) or one re-rooting a deep chain — so a device
  admitted **directly by an A1 holder gets a depth-1 chain and never publishes its
  key at all**, which in a small realm is most devices. `AdmissionCertGrant` covers
  the same population (it is minted *in response to* an announcement), so it is not
  an alternative lookup. Measured on real devices 2026-08-18; see
  `admission-revocation-names-the-capability.md` §3.1. Stated here because the
  absence is invisible from the mechanism description below — anything needing
  "look up device X's A33 key" must carry the key itself, not expect to find it.
- **One observer, three duties** (`AdmissionMigrationObserver`,
  `:sync-service-proxy`): self-announce (non-A1 device with no chain or a
  chain deeper than 1; mints + persists the keypair first), owner mint
  (verify announcement signature against the Device row's A4 → mint depth-1
  at current epoch), install (own deviceId + own key + ownerId↔masterPub
  binding + chain verify + strictly-better rule: first chain, flatter, or
  fresher leaf epoch).
- **Convergence is event-driven + self-healing:** the announce re-fires once
  per session while unmet, so an owner unlocking *after* the announcement
  synced still sees a fresh save event within one session of both being
  online. Every step is idempotent; a lost J3 write (e.g. a stale unlock
  wrapper) just re-announces with a fresh key next session and the grant row
  LWW-overwrites.
- **Custody:** `SyncManager.installAdmissionMaterial` + the J3 joiner payload
  now allows an **empty chain** ("keypair minted, grant pending" — still a
  non-admitter, the P0 guard applies).
- **Threat model:** T-DA-06 (forged announcement → A4-signature check) + A33
  custody note updated in the same PR.

### P5 as-built

⚠⚠ *The subtree strip below was retired by `admission-revocation-names-the-capability.md` §7j.10
(2026-11-11): `RevokeDevice` now revokes the removed device's own key(s) only.*

- **Rides the existing removal, adds no ceremony:** `RevokeDevice` computes
  the `admittedByDeviceId` subtree from the synced Device rows *before* the
  tombstone, then master-signs + POSTs one `AdmissionRevocation` per member
  to the P2 `/sync/admission/revoke` ledger. Best-effort — an offline relay
  never blocks the removal; an A1-less remover (delegated admitter) skips the
  strip entirely (revocations need A1) and the removal behaves exactly as
  pre-P5.
- **Q3: conservative subtree, minting power only** (see Q3 above). "Rides
  GR-7 Kr rotation" resolved to *composition, not code*: the data-plane
  boundary on removal remains the existing tombstone + epoch machinery,
  untouched — P5 adds only the admission-plane strip beside it.
- **Lineage UI:** Manage devices shows "Added by {name}" per device
  (`deviceAdmittedBy` map in the dashboard state; admitter name resolved
  in-list, raw id fallback when the admitter itself was removed).
- No new asset/surface — threat-model header note only; T-DA-01's
  one-master-signed-append revocation story is now wired to the actual
  remove-device action.
- **`DelegatedAdmissionSystemTest`** — the whole track end-to-end against the
  real relay with **real Ed25519** (every unit test runs stub crypto + a faked
  relay): host pairs B → phrase-less B admits C (relay chain-verifies) → P4
  re-roots C live → removing B revokes the {B, C} subtree → C's attempt to
  admit D is relay-rejected. It immediately caught two real bugs the stubs
  masked: (1) `DefaultSyncCryptoManager` lacked a `generateAdmissionKeyPair`
  override, so real A33 keys were X25519 and every chain signature failed
  (stub crypto's `pub == priv` hid it); (2) the joiner's self-pushed Device
  row LWW-clobbered the admitter's `admittedByDeviceId` stamp — fixed by
  having both writers derive the same lineage value (the joiner from
  `chain[1]`), with the rule sharpened to **lineage = A33-vouched admissions
  only**: an A1-minted capability has no device parent, so removing the owner
  *device* strips nothing transitively (A1-rooted caps aren't its subtree).

## 9. Implementation Notes for Claude Code

- **Reuse, don't reinvent.** The chain verifier is `MembershipChainVerifier` +
  `Ed25519SignatureVerifier` (`:sync-service-common`); generalise from 2-link to
  N-link rather than writing a new verifier. The admission-endpoint precedent is
  `POST /sync/realm-member/admit` (`MembershipGrantRouting`). The capability
  shape mirrors GR's Q30 chain (master-signed member capability + fresh
  per-(member,realm) authority key) — A33 is the personal-realm analogue of A26.
- **Canonical messages** must stay byte-identical across client and relay
  (`MasterDelegationVerifier.realmBootstrapMessage`/`.pairingResponseMessage` are
  the model). Add the `"tenshu-admit-cap-v1"` domain-separation tag. (Now
  every one of these messages is tagged and length-prefixed through
  `lengthPrefixedSignedMessage`; the cert's tag is `tenshu-admit-cap-v2`.)
- **Blob compatibility.** `PairingKeyBlob.unpack` recovers field count from
  length; keep the legacy short-blob path working (a locked/A1-less admitter
  ships no cap → joiner is a non-admitter, today's behaviour).
- **Fallback ordering in `ApprovePairingRequest`.** Resolve the signer as:
  `currentMasterKeyPair` (owner, sign+mint against A1) → else A33 keypair + cert
  chain (delegated) → else throw the *labelled* P0 error. Mirror
  `ReregisterDeviceImplementation`'s `currentMasterKeyPair ?: getRecoveryPhrase()…`
  fallback style.

## 10. References

- `sync-pairing-inverted-direction.md` — shared approve/verify surface (§6).
- `shared-realm-membership-grant.md` (GR-1..7) — the capability-chain machinery
  reused here (`MembershipChainVerifier`, A26, master-signed revoke, Kr rotation).
- `sync/threat-model.md` — A1/A4/A13/A26 assets; A33 + T-DA-* land per phase.
- `sync/design.md` §2.2 + `sync/pairing-flow.md` + ADR 0007 — device management.
