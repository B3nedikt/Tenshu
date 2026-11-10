# Threat Model — Tenshu Sync

**Status:** Draft v0.3
**Scope:** Whole system — pairing, ongoing sync, identity, relay, client-side storage, client
app surface, the Rei trust chain (publications / issues / evaluations), shared realms, and
desktop deployment (Control as a system-tray daemon + IPC peer-credential checks + the macOS
native-bridge surface backing the Drive File Provider).
**v0.3 amendment ([`tenshu-rei.md`](../proposals/tenshu-rei.md)):** introduces six new assets
(A11–A16) and three new threat groups — desktop client (T-C-11–13), Rei trust chain (§8.8),
and shared realms (§8.9). Threats sized at proposal-level granularity; per House Rule #2,
each implementation phase will tighten or supersede the corresponding entry.
**App-registration sub-amendment ([`control-app-registration.md`](../proposals/control-app-registration.md)):**
adds asset A18 (the App Registration set) and threat T-C-14 (a forged local client cannot
self-register, because the `RegisterApp` write is reachable only after the IPC handshake's
code-signing check). Landed with the `AppRegistration` / `AppGrant` schemas + `RegisterApp` /
`RemoveApp` / `SetAppGrant` operations in `:core`.
**Desktop macOS File Provider sub-amendment ([`desktop-macos-integration.md`](../proposals/desktop-macos-integration.md)):**
adds asset A20 (the daemon's native JSON-bridge surface + its group-container sockets) and threats
T-C-15 / T-C-16 — a same-user process driving the bridge's File Provider file read/write methods, and
the File Provider extension's transient plaintext materialisation. Two new local components handle
plaintext daemon-side or transiently (the tray agent, the File Provider `.appex`); the bridge inherits
A14's gate, which **is enforcing on desktop as of 2026-07-28**
([`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) P3 — the bridge file
methods were ungated through Phases 2–3, see T-C-15). The relay still sees ciphertext only:
every bridge method runs the same proxy/file-service path (I1, House Rule #1). Landed with the macOS
Phases 2–3 read/write bridge methods (`NativeBridgeServer.registerFileMethods` / `registerFileWriteMethods`).
**Android IPC caller-admission sub-amendment (2026-10-29, `docs/tech-debt.md` "The IPC surfaces
authenticate a peer but never authorize it" #1):** adds **T-C-20**, the Android twin of T-C-11, and
amends T-C-19. Before it, Control's exported `ProxyService` served any installed app: a
zero-permission app could bind, announce, and read and write the whole realm. Control now admits a
Binder caller only when its package is one of the ten first-party names **and** it is signed by that
app's pinned Play key or by Control's own key; every app checks Control's signer the same way before
binding it and before honouring a callback. No asset (A1–A4 / A9), relay surface or sync-protocol
change; device-local only (House Rule #1).
**Side-channel signer sub-amendment (2026-10-31, `docs/how-to/android-release.md` "Releasing outside
Play"):** amends **T-C-20**. The signer rule gains a third accepted certificate, the side-channel key
(one key of ours for every copy not delivered by Play), pinned in
`FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256` so a Play-installed app and a downloaded one
admit each other. The slot was empty until the key existed (an empty slot admits nothing new); it is pinned since 2026-11-06. No
asset (A1–A4 / A9), relay surface or sync-protocol change; device-local only (House Rule #1).
**Direct-release distribution sub-amendment (2026-11-04, [`android-direct-apk-distribution.md`](../proposals/android-direct-apk-distribution.md)
Phase 1 items 3/4/9):** adds assets **A43** (the side-channel signing key, a trust anchor under ops
custody) and **A44** (the release bucket, its publish key and the committed release manifest — an
**macOS direct-release sub-amendment (2026-11-06, [`desktop-macos-distribution.md`](../proposals/desktop-macos-distribution.md) §2.7):** widens **A44** to the notarized Developer ID `.dmg` of the ten desktop apps and the committed `store/macos-release.json`, in the same bucket under the same publish key, widens attack surface #20, and adds threat group §8.24 (T-MD-01–02). No asset (A1–A4 / A9), relay surface or sync-protocol change: public installers on Cloudflare R2, nothing user-owned (House Rule #1).
**Client-side gate sub-amendment (2026-11-06, [`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) §11):** amends **A14** — the binding now runs in both directions: every desktop app attests the process serving `ipc.sock` as Tenshu Control (`app.tenshu.control.ios`, a compiled-in team) before writing to it. **T-MD-01**'s residual drops to Medium. No asset (A1–A4 / A9), relay surface or sync-protocol change; device-local only (House Rule #1).
**install trust root** for a fresh install), attack surface #20 (app distribution outside Play) and
threat group §8.23 (T-DA-01–05), and qualifies **TA4** for copies downloaded from tenshu.app. A41/A42
are left to `sync-push-wake.md`, whose draft appendix holds them. No asset (A1–A4 / A9), relay surface
or sync-protocol change: the bucket is Cloudflare R2 holding public APKs, and nothing user-owned
passes through it (House Rule #1).
**Contact-invitation sub-amendment ([`contact-invitation-handshake.md`](../proposals/contact-invitation-handshake.md) §9):**
adds assets A24/A25 (the contact graph & identity keyring; the invitation capability & handshake
state), attack surface #11 (the cross-user invitation handshake), and threat group §8.13
(T-CI-01–09). Landed with the CI-3 implementation (the `/sync/invite/*` mailbox + the five
`:contact-service` handshake operations). A22/A23 stay reserved for the logging-facade draft
(`core-logging-facade.md` §7), which numbers around this amendment.
**Membership-grant sub-amendment ([`shared-realm-membership-grant.md`](../proposals/shared-realm-membership-grant.md) §5):**
adds assets A26/A27 (the per-(member, realm) member-authority keypair; the membership capability
& sealed grant payload), attack surface #12 (the cross-user membership-grant relay surface), and
threat group §8.14 (T-GR-01–06). Landed with the GR-2 relay implementation (the `/sync/grant/*`
mailbox + `/sync/realm-member/admit` + `/sync/realm-member/revoke`). **GR-7 sub-amendment (Kr
rotation, landed):** removal now rotates `Kr` → `Kr′` and re-keys the remaining members, dropping
the T-GR-02 residual from Medium to **Low**. It rests on a new retained-keyring asset — the
`RetainedRealmKey` row (a retired `Kr` wrapped to the member's A13, A1-class personal-realm state),
which the decrypt path trial-decrypts against so the ADR-0017 "no bulk re-encryption" invariant
holds (see `shared-realm-membership-grant.md` §13.4 / §13.5). No relay/protocol change — the re-key
rides the existing `/sync/grant/*` mailbox. **GR-6 sub-amendment:** durable owner-side custody of
the realm master (A1) + the granted-member roster, and the grantee-side quarantined [PendingGrant]
store — all in personal-realm state, no relay/protocol change (see the A1/A26/A27 notes below +
T-GR-02).
**F4 sub-amendment (message confusion, 2026-08-21,
[`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md) §7f):**
adds **T-GR-07** and corrects a false claim inside T-GR-02. The three membership messages were
untagged bare concatenations, and capability/revocation shared a shape exactly — so a capability
signature *was* a valid revocation signature, which the relay sees one of on every admit. Closed by
domain-separation tags; **this is a relay wire change** (`RELAY_PROTOCOL_VERSION` 1 → 2, min stays
1). ⚠ Assets A26/A27 are otherwise unchanged — no new asset, no new surface.
**Commerce↔relay bridge sub-amendment ([`commerce-billing-plane.md`](../proposals/commerce-billing-plane.md) §6):**
adds assets A28/A29/A30 (the commerce identity & payment set; the A29 entitlement signing keypair;
the minimal-variant `account↔ownerId` link), attack surface #13 (the commerce↔relay entitlement
bridge), invariant **I-CB-1** (plane unlinkability), and threat group §8.15 (T-CB-01–06). Landed
with the §8 Phase 1 slice — the entitlement-token contract + the relay's verify-only token verifier
(`ENTITLEMENT_ISSUER_PUBKEY`) + the `:commerce-service` issuance endpoints. **§8 Phase 2a (write-gating)
landed** (`ENTITLEMENT_ENFORCE`, opt-in): the relay now gates writes (`/sync/push`, content-blob `PUT`,
share + grant drops) on a valid, live, **owner-bound** token and rejects with a typed `402`/`403`,
making the T-CB-02 owner-binding check load-bearing; reads stay ungated (§9 Q5). **§8 Phase 2b
(per-owner quota, `507 over_quota` on content `PUT`) + Phase 2c (client token-fetch + `X-Entitlement`
presentation) landed.** **Commerce accounts/sessions + Stripe
([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md)):** A28
graduated from a whitelist config to a **persisted PII store** (durable Postgres: accounts, email-OTP,
sessions, entitlement ledger, statutory invoices) with the account endpoints and — with Phase 4 — the
**Stripe** rail; the account endpoints (`/commerce/account/{register,verify,claim,session}`,
`/commerce/entitlement/redeem`) + Stripe (`/commerce/checkout`, `/commerce/portal`,
`/commerce/webhook/stripe`) join surface #13, the internal **admin console** (`admin.tenshu.app`,
MFA-enforced; read + lifecycle mutation + a bounded `ownerId`-free export) is surface #15 on
A28/A30, and Stripe-webhook forgery is **T-CB-07**. The web
account surface makes surface #13 **browser-reachable for one configured origin** — opt-in via
`WEB_APP_ORIGIN`, credentials off, redirect targets derived server-side (**T-CB-09**, **T-CB-10**).
Each slice tightens the §8.15 residuals as it lands (House Rule #2, per-phase). v1 ships the **minimal** variant
(`ownerId`-bound token), blinding-ready for the strong-variant upgrade (Q1: blind RSA) that eliminates
A30.

**EPC rendezvous sub-amendment ([`ios-proxy-architecture.md`](../proposals/ios-proxy-architecture.md) §5, Phase 3):**
adds asset **A31** (the EPC rendezvous channel state + the out-of-band pairing capability), attack
surface **#14** (the app↔proxy `/sync/epc` rendezvous mailbox), and threat group §8.16
(T-RDV-01–06). Landed with the Phase-3a relay surface (the `/sync/epc/{sessionId}` mailbox + store +
admission gate). The EPC session keys themselves are ephemeral channel state (A8 class). No new
decrypt code lands server-side — the relay stores and serves opaque sealed frames addressed by an
HKDF-derived mailbox id, gating session creation behind a realm device's JWT (the admission gate).
O2 (edge App Attest) is resolved **no**: the admission story is JWT-gating + caps only, keeping the
relay vendor-neutral with no Apple dependency (T-RDV-03 residual Medium, DoS-only).

**Delegated-admission sub-amendment ([`delegated-device-admission-authority.md`](../proposals/delegated-device-admission-authority.md) §4/§5, P2):**
adds asset **A33** (the per-device admission capability keypair + capability-cert chain), attack
surface **#16** (the chain-verified pairing-response path + `/sync/admission/revoke` + the per-owner
admission revocation list), and threat group §8.17 (T-DA-01–05). Landed with the P2 relay slice:
`POST /sync/pairing/response` accepts an optional `admissionCertChain` — when present the delegation
is signed by the *admitting device's* A33 key and the relay walks the chain to the realm's stored
master key (the `MembershipChainVerifier` posture generalised to N links) and checks the revocation
list; when absent the original A1-signature path is unchanged. Client-side minting/consumption
landed with **P3** (approve signs with A1 or A33 per the §9 fallback; every approve mints the
joiner a chained A33 via the encrypted pairing blob). **P4 (migration + re-root)** adds two
synced, realm-encrypted row types — `AdmissionKeyAnnouncement` (a device's locally-minted A33
*public* key, A4-signed) and `AdmissionCertGrant` (the A1-holder's depth-1 chain) — no relay or
protocol change, and **no A33 private key ever syncs** (the naive migration alternative — syncing
freshly-minted keypairs realm-wide — was rejected: a stolen device could then impersonate every
other device's admission identity). Adds T-DA-06. **P5 (revocation UX, final phase)** wires the client side of the P2
ledger: removing a device master-signs `AdmissionRevocation`s for the device **and its whole
`admittedByDeviceId` lineage subtree** (Q3 conservative — the automatic P4 re-root must not
launder a compromised admitter's descendants; subtree members lose minting power but stay
listed for individual removal), best-effort alongside the existing tombstone/epoch flow;
Manage devices surfaces the lineage ("Added by …"). No new asset or surface.

**F0 + F1′ sub-amendment ([`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md)
§7b.3, 2026-08-19) — a revocation names the capability, not the device-at-an-epoch.** P5 above
revokes by `(deviceId, epoch)` and the verifier matches `revocation.epoch >= cert.mintedAtEpoch`. Two
consequences drove this change, one of them a live incident:

* ⚠⚠ **The epoch was load-bearing but not durable.** A re-mint cleared a revocation only because a
  *fresher epoch* outran the comparison, so a `seededRealmEpoch` lost to a process restart made a
  device mint **below** the revocation naming it — observed on hardware as a 403 "Admission
  capability has been revoked" on an ordinary pairing (§7b.1).
* **The predicate is wider than its purpose.** It cannot say *this capability, not the next one*, so
  it over-revokes by construction.

**F0** captures the A33 **public** key at admission on the `Device` row (`admissionPublicKey`,
identity-service v2). The admitter *mints* that keypair, so no new key material and no new message
exist — only the public half, already visible to the relay in every chain it verifies, is now
recorded. **F1′** adds `AdmissionRevocation.deviceA33PublicKey` and a **second domain tag**
(`tenshu-admit-revoke-v2`); `AdmissionChainVerifier.isRevoked` matches keys exactly when both sides
carry one and falls back to the epoch rule otherwise.

⚠ **Two new residuals, both deliberate:**
1. **The epoch rule survives for pre-F0 devices** — they carry no captured key, so their revocations
   remain coarse and the incident class above remains reachable *for them* until §7 F5 retires the
   fallback. Bounded and shrinking: it is the population admitted before v2.
2. ⚠⚠ **The signed messages are unprefixed concatenations**, so under a *shared* domain tag a
   device-scoped message for one device is byte-identical to a key-scoped one for another, with
   `deviceId` chosen by the joining device. The distinct v2 tag closes that pair; it does **not**
   close the class for any future message shape added to `AdmissionCapability.kt`. Recorded as a
   hardening item (proposal §7d) rather than fixed here, because length-prefixing changes the signed
   bytes of already-issued master-signed records.

House Rule #1 is unaffected: the relay stores one additional opaque public key beside a revocation
it already stored, mints nothing, and decrypts nothing. **No new asset, no new attack surface** — A33
and surface #16 already cover the capability and the revocation endpoint.

**§7d + B1 + F5b + S1 sub-amendment — every signed message tagged and length-prefixed, no keyless revocation
([`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md)
§7d/§7g, 2026-10-28, rides the protocol-1 re-cut).** Closes both residuals the F0 + F1′ amendment above
left open, on the fresh realms the complete production reset creates:

1. ⭐ **Every message an A1, A33, A4 or shared-realm-master key signs starts with LP(a fixed, unique
   tag) and is length-prefixed.** First the admission/membership family (§7d): the six messages — the A33
   cert, the A33 revocation, the A4-signed admission-key announcement, and the three membership
   messages (capability, device binding, revocation) — are now
   `LP(tag) ‖ LP(field₁) ‖ … ‖ LP(fieldₙ)` with `LP(x) = u32be(|x|) ‖ x`, every field included
   (`lengthPrefixedSignedMessage`). The encoding is injective: a field boundary can no longer move
   without changing the signed bytes, so the attacker-chosen-`deviceId` collision class is closed for
   every message in the family, not just the one pair a distinct tag used to separate. Tags moved with
   the encoding — `tenshu-admit-cap-v2`, `tenshu-admit-revoke-v3`, `tenshu-admit-announce-v2`,
   `tenshu-member-{cap,bind,revoke}-v2`; the old suffixes (incl. `tenshu-admit-revoke-v1`/`-v2`) are
   retired and never reused, so a signature from a pre-prefix build fails as a plain mismatch.
   ⚠⚠ **Then the pairing/realm family (B1, pre-existing, found in review of this change):** the
   new-device delegation (`K ‖ deviceId ‖ epoch(8)`), realm bootstrap, recovery claim, A4-signed
   offer-response and A4-signed HLC tip were untagged bare concatenations signed by the *same* keys.
   A root cert C split as `K = C[0:|C|−12]`, `deviceId = "\0\0\0\b"`, `epoch = C[−8:]` **was** a
   delegation, so any holder of a cert (the relay; any shared-realm member) could replay its A1
   signature at the unauthenticated `POST /sync/admit-device` and register a junk device — which
   stalls tombstone GC for the realm and leaves the owner nothing to remove it by; and in the forward
   direction a joiner grinding its A33 key, or any device writing a `Device.publicKey` A1 later
   re-admits (`ReadmitDevice`, `admitKnownDevices`), could steer an approved delegation into a valid
   unrevocable A1-signed cert. Now: `tenshu-admit-delegation-v1`, `tenshu-realm-bootstrap-v1`,
   `tenshu-recovery-claim-v1`, `tenshu-offer-response-v1`, `tenshu-hlc-tip-v1`, one shared builder
   each, used by every client signer and the relay. Two further walls: a delegation is only ever built
   over a 32-byte key (and `ReadmitDevice` / `admitKnownDevices` refuse any other `Device.publicKey`),
   and every relay route that registers a device (`/pairing/request`, `/pairing/response`, the
   approved offer-response, `/admit-device`, `/realm`, `/recovery-claim`, `/realm-member/admit`) or
   names a key (`/admission/revoke`) requires 32-byte keys and a `deviceId` of 1–256 characters from
   `[A-Za-z0-9_-]` (every real id was then a UUID or 64-hex; since workstream B below, exactly the
   key's derived id). The other things an A4 key signs are not LP but
   start with a printable byte, which no LP message can: JWS inputs (`eyJ…`) and the hub offer
   (`tenshu/pairing/hub-offer/v1` ‖ LP fields). **T-DA-02's "no cross-protocol signature
   reuse" holds from this amendment on, not before.**
2. ⭐ **F5b — a revocation always names a key.** `AdmissionRevocation.deviceA33PublicKey` is required,
   the relay's `admission_revocation.device_a33_public_key` is `NOT NULL`, `POST /sync/admission/revoke`
   400s a keyless body, and `AdmissionChainVerifier` has **one** rule: same device and same A33 key,
   exactly. The epoch fallback is gone. §7g measured both ways of removing it as unsafe *on a realm
   holding keyless revocations* (fail-open, or fail-closed for 3 of 4 devices); a realm cut after the
   reset holds none, which is the precondition §7g.5 named. ⚠ The revocation's `epoch` (and the cert's
   `mintedAtEpoch`) stay **signed but unread** — reserved, like the membership `epoch` (T-GR-07's F4
   note). A device with no known A33 key (none captured on its row, announced, or granted) is
   **skipped** by the client's relay strip rather than revoked by an epoch rule. ⚠⚠ That "known" is
   the revoker's view, built from rows a device writes about *itself* (whole-row LWW, no authorship
   check), so a malicious device can blank or garble its own `admissionPublicKey` and leave nothing
   to name — which item 3 closes.
3. ⭐ **S1 — a chain through a removed device is refused, key-independently.** The relay passes its
   F2′ removal ledger into `AdmissionChainVerifier` (`isRemovedDevice`), and any chain with a link
   whose `deviceId` was removed is refused (`RejectRemovedLink` → 403) — at post time **and again at
   delivery**. A queued registration (join peek, offer fetch) carries its raw admission chain, and the
   relay re-runs the full chain verification before it lands — against the removal ledger **and** the
   key-scoped revocation list. So a removed or revoked admitter's last delegations cannot register
   their joiners inside the TTL. (Revocations matter here too: a revoker strips by key at once while
   the removal tombstone lands later, and a Q3 subtree member is revoked without ever being removed.)
   F2′ guarantees a removed id never returns, so every chain **still running through** a removed
   device D loses its minting power, whatever keys the revoker did or did not see.
   ⚠⚠ **Intended wider consequence — it is not only the compromise case.** The ledger records every
   applied `Device` tombstone, whoever pushed it. A device that retires itself (`ForgetThisDevice`
   pushes the same tombstone as a removal) or is removed by a delegated, non-A1 device is in the
   ledger too, and every chain through it is refused from then on. The devices it admitted stay
   registered and keep syncing, but they can no longer admit others: the relay answers 403
   `admission-revoked: An admitter in this device's admission chain was …` (`revoked or removed`).
   They admit again once an A1 holder re-roots them with a fresh `[S ← A1]` cert. P4 does that
   without user action for any chain deeper than one (the device re-announces each session), but only
   once an A1-holding device is online to mint. This is deliberate: the relay cannot tell an honest retirement from a
   compromised device that retired itself to launder its sleepers, so it treats both the same.
   ⚠⚠ **Residual — re-rooted sleepers.** P4's automatic re-root (`AdmissionMigrationObserver.maybeMint`)
   gives any device that announces a key and has a `Device` row a fresh depth-1 cert `[S ← A1]`. A
   sleeper S admitted by a malicious D is re-rooted the same way, after which no chain of S's runs
   through D, and removing D does not touch S: the client's subtree strip walks the self-written
   `admittedByDeviceId`, which S can blank. S keeps minting power until it is **removed
   individually** (it is a listed device, with its own row). Refusing the re-root was considered and
   not taken: the lineage it would key on is self-written, and §7b.2 makes an A1 re-mint the designed
   way back for legitimately revoked subtree members. `tech-debt.md` *"Re-rooted sleepers survive their
   admitter's removal"* carries the open design question.
4. ⚠⚠ **S2 — `strips()`'s `deviceId` conjunct is load-bearing.** Because keys are learned from
   attacker-writable rows, a device can copy a victim's A33 key into its own row; revoking the
   attacker then names the victim's key. Only the `deviceId` match keeps that from stripping the
   victim. Pinned by `KeyScopedRevocationTest.aRevocationNamingAnotherDevicesKeyDoesNotStripIt`.

⚠ No version bump and no protocol range: every format and `RELAY_PROTOCOL_VERSION` restart at 1 in
the same re-cut (`core-schema-evolution.md` Appendix A), and client and relay ship together. The relay's
SQL baseline marker moved to `2026-10-reset-b`, so a database cut before the in-place `NOT NULL` edit
is refused rather than kept with a nullable column. House Rule #1 unaffected: the relay stores the same
columns, now with a non-null key, and verifies no new kind of thing. No new asset, no new surface.

**Workstream B sub-amendment — a device id is a function of its key
([`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md)
§7i, 2026-10-29, rides the protocol-1 re-cut).** Until now a `deviceId` was a random UUID the device
minted for itself (`SyncManagerImpl`, `generateId`), and the only thing binding it to an A4 key was
the admitter's signature over the pair. Whoever signed an admission — A1, *any* A33 holder in the
chain, a shared-realm master, or the A1-holding device doing a genesis or recovery — chose the id
freely. Two consequences, both now closed:

* **Squatting.** A compromised admitter (or a malicious device re-admitting from a `Device` row it
  wrote, `ReadmitDevice` / `admitKnownDevices`) could register a key it controls under **another
  device's id**. The relay's registry is keyed `(ownerId, deviceId)`, so the squat *replaced* the
  victim's key: its JWTs started failing, and every later check keyed on the id — the F2′ removal
  ledger, S1's `isRemovedDevice` chain refusal, S2's `strips()` `deviceId` conjunct, block authorship
  by `deviceId` — then applied to the attacker's key under the victim's name.
* **Reuse after removal.** F2′ promises *"a removed identity never returns"*, but the identity it
  recorded was a free string: the same holder could return under a fresh id, and a *different* key
  could later be admitted under the removed id's name the moment the ledger was lost or bypassed.

**Now:** `deviceId = lowerhex(SHA-256(LP("tenshu-device-id-v1") ‖ LP(publicKey)))[0:32]`
(`deviceIdForPublicKey`, `:sync-service-common`), where `publicKey` is the key registered for it —
the device's A4, or on a shared realm it joined as a member the per-realm device key (D2
unlinkability holds: a fresh key is a fresh, unlinkable id; the per-realm ids were already
`hex(SHA-256(key))`, untagged, and now use the same tagged rule). Every relay route that registers a
device re-derives it **before** the admission / master signature is verified, the realm is looked
up, any challenge is spent or any state is written (a route's own caller authentication — the
JWT, the offer responder's A4 signature — may come first) —
`/sync/realm`, `/sync/admit-device`, `/sync/pairing/request`, `/sync/pairing/response`, the approved
`/sync/pairing/offer-response`, `/sync/recovery-claim`, `/sync/realm-member/admit` — and refuses a
mismatch with `400 device-id-not-key-derived` (`RegistrationInputRules.isKeyDerivedDeviceId`). Every
client admitter refuses too, before it signs anything: `PairingAdmission.admit` (join and offer flows
— it also mints the A33 cert naming the id), `ReadmitDevice`, `SharedRealmBootstrapper.admitKnownDevices`.
So the F2′ ledger now means *"this key never returns"*, an id cannot carry a second key, and the
relay checks the binding without trusting anyone — House Rule #1 is untouched (a hash of a public
key the relay already stores).

⚠ **What it does not do.** (i) An id is not secret and not unguessable — it is a hash of a public
key the relay already holds; nothing relied on ids being private. (ii) Ids are 128 bits: forging a
victim's id is a second preimage (2^128); a collision between two keys the attacker made himself
(2^64) gains him nothing. (iii) **The id changes with the key.** There is no A4 rotation
(`tech-debt.md` *"Public-Key Rotation Without Re-Pairing — Deferred"*), and an unreadable stored key
blob mints a new key — that device now comes back as a stranger and is admitted again, the honest
outcome for a key that is gone. ⚠⚠ But the **old id is orphaned**: before, the re-admission reused
the persisted id and "register or replace" overwrote the key; now the old `(ownerId, oldId)` stays in
the relay registry and the old `Device` row stays synced until someone removes it — and that id
counts in the tombstone-GC ack quorum and the content-reclamation barrier, so it stalls both for the
realm. Remove the stale device from Manage devices. A future rotation design must therefore
re-admit **and retire the old id**, not re-key an id.
(iv) A cert chain's *link* ids are not re-derived (a link names its A33 key, not its A4), and synced
`Device` rows are not checked on merge; both are written only by admitted devices, every
registration they could feed is checked at the relay, and the one A1 signer that reads a row's
`(deviceId, publicKey)` pair without registering it — `AdmissionMigrationObserver.maybeMint`, which
mints a P4 re-root cert — refuses a row whose id is not its key's. No new asset, no new surface, no wire field
added or removed; `RELAY_PROTOCOL_VERSION` stays 1 — clients and relay ship together on the reset.

**Offer-flow pairing sub-amendment ([`sync-pairing-inverted-direction.md`](../proposals/sync-pairing-inverted-direction.md) §4, P2; extended P3/P4):**
the inverted pairing direction — the *new* device (offerer) displays a `tenshu://offer` QR carrying
its ephemeral X25519 public key + the pairing secret; an *existing* realm device (responder) scans
it and posts a single signed, sealed **offer-response** keyed by `SHA-256(pairingSecret)` to the new
`POST`/`GET /sync/pairing/offer-response` rendezvous. No new asset (the ephemeral keys/secret/SAS
are A8 class, the payload is the improvements-§11 `PairingPayload` unchanged) and no new crypto;
extends attack surface #1 (the offer QR + role-host URI forms) and #3 (the hash-keyed
`PairingOfferStore`), and adds **T-P-06/T-P-07/T-P-08** to §8.1 (the proposal's T-P1..T-P3,
renumbered to follow the section's sequence). Notable *improvement*: the passive-QR-observer threat
(T-P-01) **closes for confidentiality** in this direction — the offerer's private key never leaves
the offerer, so a photographed QR yields no decryption capability, only the T-P-08 DoS. Landed with
the P2 relay slice (store + endpoints + `MasterDelegationVerifier.offerResponseMessage`); the
client-side offerer/responder ops land with P3/P4. **P3/P4 as-built delta (maintainer-approved,
awaiting doc ratification):** because the offer flow has no `PairingRequest` to carry the offerer's
Ed25519 device signing key to the responder, the offer QR is extended with a `g=` (signing key) +
`d=` (offerer device id) field, and the approved offer-response POST carries a master/A33
delegation over `offererSigningKey || offererDeviceId || epoch` (the tagged
`pairingDelegationMessage`, §7d/B1 above) that the relay verifies (reusing the
shared `verifyAdmissionDelegation` helper — the same logic as `POST /sync/pairing/response`) and
then registers the offerer in `KnownDevicesStore` — since 2026-10-28 **when the offerer fetches the
approval** (`GET /sync/pairing/offer-response`, re-checking the removal ledger), not when it is
posted, so an approval that expires unfetched leaves no never-acking device in the tombstone-GC
quorum (the join flow's peek design, T-P-16). This closes the otherwise-fatal gap where the
offerer's first JWT-authed sync would 401. The offerer's signing key now transiting the QR is A4
*public* material (surface #1); it grants no capability by itself and the SAS still gates key
substitution.

**Co-resident EPC auto-connect sub-amendment (2026-07-17, Fit `ControlAutoConnect`) — ⚠ RETIRED
2026-11-05.** It allowed a first-party app on the **same device and Team ID** as Control to take the
EPC app-pairing invite (`tenshu://epc/pair#…`) from the shared `app.tenshu.shared` Keychain group,
and consume it **without the TA5 SAS compare**. The justification was that the Keychain group is an
Apple-gated local channel with no untrusted hop. **Deleted, with its only shipping consumer.** Fit's
`ControlAutoConnect` fired only when route 1 was *not* provisioned and Control's "Connect an app"
sheet was open. The co-resident case is route 1's (Decision C, A4 above,
[`ios-first-party-route-1-migration.md`](../proposals/ios-first-party-route-1-migration.md) §8):
first-party apps adopt the shared-Keychain key material, with no EPC channel. A shared-Keychain read
also cannot reach a Control on another device, so it was never the remote-proxy path either. The C3
`epc-session` **presence token**, the other payload of that Keychain item, is no longer minted
either. **What remains, and the posture it carries now:**
- Control's "Connect an app" host still writes its live invite into the `epc-session` item
  (`ControlSurface.publishEpcInvite`). The invite is A8-class, one-shot and short-lived, and the
  item is device-bound (`…ThisDeviceOnly`) and readable only by same-Team apps (TA2 + TA4). It is
  still deleted on every identity-destroying event (`clearDriveSession`).
- **No shipping code path consumes an EPC invite without the SAS.** The only remaining reader of the
  Keychain copy is drive-ios's DEV_HARNESS `-epcAutoConnectFromControl`, which a shipping build does
  not compile. Every EPC pairing, same-device or cross-device, runs the full QR/paste + SAS ceremony
  (TA5).
- Reinstating a SAS-less Keychain consumer needs a new amendment here, not a revival of this one.

The relay surface is unchanged, and there is no new or removed asset.

**Hub headless-pairing sub-amendment (2026-07-24, hub proposal §11, H2):** the printed-static-key
offer variant for devices with no display — the hub's **printed label** (`tenshu://hub?d=…&g=…&s=…`)
carries its device id, its long-term Ed25519 verify key (`g` = its A4 device key, printed rather
than QR-displayed), and a **long-lived** 128-bit pairing secret. While armed, the hub posts a
fresh ephemeral X25519 key signed by the static key to the new unauthenticated
`POST /sync/pairing/hub-offer` mailbox (keyed by `SHA-256(secret)` — since 2026-10-28 a
domain-separated hash of it, fetched by `POST …/hub-offer/fetch` with the key in the body, T-P-17;
re-post replaces,
fetch consumes, ≤7-min TTL); the phone verifies the signature **against the physically scanned
label** — the printed key plays the anti-MITM role the QR-carried ephemeral key plays in the
standard offer flow, and **replaces the SAS** the hub cannot render. The return leg reuses the
existing offer-response mailbox unchanged, under the **session-scoped** hash
`SHA-256(secret || ephemeralPub)` so the single-use offer-response semantics survive re-arms of
the long-lived printed secret. Signing the *ephemeral* key (not sealing to a static X25519 key)
preserves forward secrecy: a recorded exchange plus a later-extracted static private key still
cannot decrypt the `PairingPayload`. No new asset class (the printed secret/label are A8-class
rendezvous material; the static key is A4); extends surface #1 (the `tenshu://hub` printed form)
and #3 (the hub-offer mailbox — public material + a signature the relay cannot even verify, House
Rule #1 trivially intact); adds **T-P-09..T-P-12** to §8.1.

**Smart-home actuation sub-amendment (2026-07-25, [`home-device-service.md`](../proposals/home-device-service.md) HD-1, HD-D7 actuation half):**
realm-synced rows can now cause **physical effects** — a hub consumes `DeviceCommand` rows and
drives real actuators (lights, plugs; simulated in HD-1, radio-backed from HD-2). No new
*authority* class: the command channel is realm-key-sealed sync rows authored by realm devices,
which are already fully trusted peers (the H4 posture note), and the relay sees ciphertext only
(House Rule #1 untouched — no new endpoint, no relay change). What IS new is the consequence
class, so two hub-side mechanisms become **load-bearing and test-pinned**: the durable
consumed-command journal written *before* execution (at-most-once under re-delivery and hub
restart) and the **2-minute staleness window** (`DeviceCommand.STALE_WINDOW_MS` — deliberately
much tighter than hub management's 15 min: a command replayed from a stale sync backlog must
never flip lights hours after the gesture). Adds attack surface **#17** and threat group
**§8.19 (T-HD-01..03)**. The **RF half is explicitly deferred to HD-2** (untrusted
Zigbee-airspace ingest of device names/attributes, permit-join as the admission gate, the
zigbee2mqtt network key at rest) and lands with that slice's bridge.

**Smart-home RF sub-amendment (2026-07-25, `home-device-service.md` HD-2, HD-D7 RF half):**
the hub now drives a **real radio** through a zigbee2mqtt sidecar, so device names and
attributes chosen by whoever is within radio range become realm rows rendered in every app,
and the hub can open the household's Zigbee network to new joiners. Still no relay change and
no new *network* surface (the sidecar is reached over **loopback**; the hub remains an
outbound-only MQTT client and listens for nothing), so House Rule #1 and the §11 no-listener
decision are untouched. Adds surface **#17b** and threat group **§8.20 (T-HD-04..07)**:
air-sourced text is sanitised and length-bounded at ingest (control characters *replaced*, not
dropped; 64-char fields, 16-key/512-char extras, 512-device inventory), device identity keys
off the **IEEE address** so a hostile name cannot hijack another row, permit-join is explicit,
clamped to 5 minutes and rides the ordinary command channel (so the consumed journal and the
2-minute staleness window make a replayed "open the network" inert), the Zigbee network key is
declared same-at-rest-class as the H3 replica (LUKS), and the local broker binds to
`127.0.0.1` only. Adversary note: HD-2 is the first place where **radio proximity** matters —
AD1/AD2 within a few tens of metres gain capabilities they did not have; no new adversary
class is introduced (§9's AD1–AD5 coverage is unchanged).

**Desktop store-signature sub-amendment (2026-11-05,
[`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) §10):** A14 gains a
second admission route for our own apps when Apple has re-signed them for the Mac App Store or
TestFlight. Those copies carry Apple's leaf certificate, so the team-OU pin rejected them. Such a
peer must now satisfy `anchor apple generic` + a store marker + the identifier, **and** its signed
team identifier must be ours. The marker alone admits any store app and is never sufficient. No new
asset, no relay or wire change, no schema change. A14's residual gains the two-read window.

**Pairing-metadata sub-amendment (F7, 2026-10-28,
[`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md) §5 F7):** the join
flow now sends the relay a domain-separated **hash** of the pairing secret instead of the secret, on
every route (request, request list, response, peek, ack); the joiner's and the hub's **device names
travel sealed** under a key HKDF-derived from the respective pairing secret; the joiner's poll moved
from `GET /sync/pairing/response?pairingSecret=` to `POST /sync/pairing/response/peek` (body);
join-flow responses gained the offer flow's 7-minute TTL and 24-hour consumed marker. Resolves the
A6-vs-T-R-02 contradiction on device names in A6's favour, corrects T-P-06, and adds **T-P-16**
(the fix) and **T-P-17** (the hub's stable rendezvous hash, accepted). Relay wire change, **in place
before release** — no `RELAY_PROTOCOL_VERSION` bump, no service-version bump, no schema-shape change.

**Join-flow SAS v2 sub-amendment (2026-10-03):** the join flow's SAS now covers the joiner's
Ed25519 signing key (A4), its `deviceId` and its device name as well as the two X25519 keys
(`deriveJoinPairingSas`, HKDF info `tenshu/pairing/sas/v2`), closing the gap T-P-14 left open: a
relay that rewrites any of them before the host's first poll now makes the two screens disagree.
**Lockstep, no version field, no fallback:** a v1 device and a v2 device show different codes, and
every join-flow code screen says to update both. No new asset, no relay or wire change, no schema
change. The offer flow keeps v1 on purpose (its signing key rides the QR). Rewrites T-P-02, T-P-03
and T-P-14's residual.

**Join-flow approve-binding sub-amendment (2026-10-02):** a **defect, found by reading and fixed in
the same change** — not a design change. The join flow's stated defence for T-P-02/T-P-03 is the
SAS; the host derived it from one relay response (`GetPendingPairingRequests`) and then admitted
from a *second* one (`ApprovePairingRequest` re-fetched the request list and took the ECDH key, the
signing key and the device name from the first entry with a matching `deviceId`, without the
pairing-secret filter). Nothing bound the two, so the SAS did not cover the keys the admission used.
The host now **pins what it showed** and admits only from the pin; approve makes no request-list
fetch at all. No new asset (the pin is A8-class, in-memory, dies with the session), no new surface,
no relay or wire change, no schema change. Rewrites the defence text of **T-P-02** and **T-P-03**
and adds **T-P-14** to §8.1. Every other flow was checked for the same shape (T-P-14 records each
verdict). The offer and hub flows do not have it. ⚠ **The contact handshake's inviter-side
completion did** — the prompt's code came from one relay response and the key stored from another
— and is bound to the shown claim in the same change: **T-CI-10** in §8.13. Its residual (the
claim moving by merge under an open prompt) closed on 2026-10-04 with contact-service v2: the
client now sends the fingerprint it showed.

**1.0.1 re-cut sub-amendment (2026-11-05, [`core-schema-evolution.md`](../proposals/core-schema-evolution.md)
Appendix A.6):** the last pre-release reset deleted the code that tolerated keys, manifests and
peers written by older builds of our own. Three of those deletions touch this contract, and each one
**narrows** what a device accepts. Nothing new is accepted.
- **Pairing key-blob (A8 channel, A1/A2/A13 delivery; §8.1).** `PairingKeyBlob.unpack` accepts exactly
  three shapes, by length:
  - **64 bytes**: `sharedIdentityKey | sharedVolumeKey`, still sent when the host's realm key is not
    available (locked host);
  - **128 bytes**: plus A13;
  - **≥ 196 bytes (v2)**: plus the A33 key pair and its cert chain. A v2 tail that does not parse
    falls back to the 128-byte prefix, as before.

  Any other size is now **refused before anything is installed**: `PairingKeyInstaller` throws a
  typed error and writes no key, cursor or latch. Before this change, any other size was installed
  whole as the shared identity key, a pre-SR-3b.4 shape no 1.0.1 host sends. A forged or truncated
  blob could not have passed the ECDH-AEAD anyway; the change removes a path that installed a key
  of the wrong shape.
- **Chunk keys (A5, the CEK amendment in §8.3).** A manifest with no `wrappedCek`, or whose CEK no
  held volume key unwraps, is now **undecryptable**: the chunk decryptor gets no key and every read
  is a clean "not available". Chunks are **never** decrypted under `sharedVolumeKey` directly any
  more. The fallback existed for pre-Phase-0 manifests, which nothing writes.
- **Realm genesis.** `POST /sync/realm` no longer carries the `formatVersion` transition field. The
  relay never read it; its gate is `relayProtocolVersion` (E2e). No relay change.

Also in the re-cut, with no change to this contract:
- **Desktop IPC (A14/T-C-11):** pre-hello and pre-revision peers are refused and the connection closed,
  rather than served on the legacy wire.
- **Relay and commerce SQL:** squashed to one baseline (marker `2026-11-reset`). A database from
  before the reset is refused at boot. The stored columns are unchanged: House Rule #1 still holds,
  and the relay stores the same ciphertext and metadata as before.

No new asset, threat or surface.

## 1. Purpose

State the security properties the system must preserve, the attackers it must preserve them against,
and the assumptions those properties depend on. This document is the contract between protocol
design, implementation, and security review. If the code and this document disagree, one of them is
wrong.

## 2. Core Security Stance

**Only the user and their un-compromised devices are trusted.**

Concretely, this has three consequences that the rest of the document keeps coming back to:

- **The relay is in the adversary set.** Tenshu operates it as an operational fact, but the protocol
  must not assume it is honest. A compromised, coerced, or malicious backend must not reduce the
  security of user data below its encrypted-at-rest baseline.
- **Transport security is for availability, not confidentiality.** TLS and platform certificate
  trust help against casual wire-level attackers, but cryptographic end-to-end protections must hold
  even if TLS is subverted (rogue CA, MITM at a relay operator).
- **Authentication is cryptographic, not password-based.** A password or session token is only
  evidence that a request came from an authenticated session; it is never evidence that the device
  holding it is trusted to read data.

## 3. Security Invariants

Every threat below is evaluated against whether these invariants continue to hold.

- **I1. Confidentiality.** No party outside the user's realm device set can read user data,
  regardless of what they control on the network, the relay, or the storage layer.
  **Carve-out (Mode-A link sharing):** data the user has explicitly published via a share link
  (A17) is readable by any party holding the link, by capability. The carve-out scope is the
  one shared file under that one share, and is bounded by revocation (its retention window —
  see T-S-03). It is the user's deliberate publication act, not a protocol leak.
- **I2. Integrity.** Blocks accepted as authentic by a device originated from a realm device that
  was a member at the time of signing. Modifications by the relay or a non-member are detected.
- **I3. Authorship.** Each block is attributable to a specific signing device. Forged authorship is
  detected.
- **I4. Forward secrecy on revocation.** A device that is revoked from the realm cannot read any
  data created *after* its revocation, even if it retains a copy of every key it ever held.
- **I5. Bounded disclosure on new pairing.** A newly-paired device has access only to what the
  approving user explicitly granted (by default: forward-only).
- **I6. No silent censorship.** A relay that drops or withholds blocks is detectable by the affected
  devices within bounded time.
- **I7. Recoverability from declared loss.** A user who retains their recovery code can regain
  access to a user-controlled subset of their data, even after losing every device. The subset is
  defined by a **recovery manifest** the user manages in the control app: the master identity is
  always included; each volume is opt-in. A user who does not retain their recovery code cannot be
  recovered by any party.
  *Amendment ([`onboarding-low-friction.md`](../proposals/onboarding-low-friction.md) §9.1):*
  the redesigned first-run flow generates the recovery phrase silently and defers user-visible
  capture to a "Set up account recovery" Settings entry (§7.4). I7 still holds in the steady
  state — once the phrase is captured or a second device is paired, recoverability matches the
  pre-redesign invariant. During the deferral window (single device, phrase not captured), loss
  of that one device is unrecoverable: see T-OB-01 in §10.
- **I8. Local minimisation.** Plaintext keys and plaintext user data exist on a client device only
  while in active use; they never hit platform backups, logs, crash reports, or shared caches.
- **I9. No headless escalation.** An operation may run with no user present (OS-scheduled
  background context: no foreground UI, possibly a cold proxy boot) only if its author
  explicitly opted in by carrying the `HeadlessSafe` trait. Operations that require an
  interactive flow — recovery claim, pairing approval, recovery-phrase / key-derivation
  entry, a "tap approve" — never carry it and are therefore unschedulable *by
  construction*, not by convention.
  *Amendment (Phase 5, [`core-background-processing.md`](../proposals/core-background-processing.md)
  §3.2 / §6.4):* introduced with the `ScheduleService`. Enforced **fail-closed**: an op is
  unschedulable *unless* opted in (an allowlist), so a UI-requiring op added later defaults
  to unschedulable rather than slipping through a denylist someone forgot to update. Checked
  at `ScheduleService.schedule(...)` **and** re-checked by the in-process drain loop before
  dispatch (defence in depth — covers wire-deserialised / older-build `ScheduleItem`s whose
  op reference is dynamic). The complete Layer-3 attack surface is auditable as one grep:
  `isHeadlessSafe`.
- **I-CB-1. Plane unlinkability** *(commerce↔relay bridge, `commerce-billing-plane.md` §6).* The
  commerce plane's identity/payment records (A28) cannot be joined to a relay realm (`ownerId`) or
  to ciphertext metadata. *Strong variant (the target):* holds even against compromise of **both**
  planes — the entitlement token is blinded at issuance, so neither commerce ("Alice paid for tier
  T") nor the relay ("some `ownerId` presented a valid tier-T token") — nor both colluding — can
  link Alice to a realm. *Minimal variant (v1 carve-out, `ownerId`-bound token):* holds against
  compromise of **either** plane alone, **not both** — the `account↔ownerId` link (A30) is the
  documented de-anonymisation vector. The relay stays ciphertext-only in **both** variants (House
  Rule #1); I-CB-1 is about the *identity* link, which a forged or breached entitlement still
  cannot turn into content access (I1 is independent — a token grants write-quota, never decryption).

## 4. Trust Assumptions

Explicit yes/no list. If any of these is wrong, the threat model breaks and parts of this document
become invalid.

Trusted:

- **TA1.** Platform CSPRNGs are unbiased and unpredictable to an attacker.
- **TA2.** Platform keystores (iOS Keychain, Android Keystore, macOS Keychain, Windows DPAPI, Linux
  Secret Service) resist attackers who do not have root on the device.
- **TA3.** Chosen cryptographic primitives (X25519, HKDF-SHA-256, XChaCha20-Poly1305, Ed25519,
  SHA-256, BIP-39 or equivalent for mnemonics) are not broken during the lifetime of encrypted data.
- **TA4.** Code-signing and app distribution (App Store review, signed installers) prevent an
  attacker from silently swapping a trusted Tenshu client binary for a malicious one.
  ⚠ **Qualified for the direct Android channel (2026-11-04, §8.23):** an *update* of a downloaded
  APK is protected by Android's signer continuity (only A43 can produce one), but a *fresh* install
  has no prior signer to match, so it trusts whatever key signed the bytes the release bucket (A44)
  served. For that one case TA4 rests on A44's integrity and on the user checking the published
  certificate digest, not on a store's review.
- **TA5.** A human can correctly compare a 6-digit short authentication string (SAS) between two
  screens.

Explicitly not trusted:

- The relay operator, or anyone with access to relay storage or relay network terminators.
- The network path, including any TLS terminator.
- Any device that has been revoked from the realm.
- Any device that is not a current realm device, including devices that were legitimately paired
  but then compromised and not yet revoked.
- The signed metadata of a device (device name, advertised platform) — these are user-facing labels,
  not authenticators.

## 5. Assets

- **A1. Master Identity Private Key.** Root of trust for the user's realm device set. Used to sign
  a device's admission to the realm. Full compromise = attacker can create new realm devices
  indistinguishable from legitimate ones. Held in memory while the user is signed in. On Android
  and Desktop, A1 is re-derived from the recovery code on each session and never persisted at
  rest. On Web (per [`docs/web/architecture.md`](../web/architecture.md) §4.4), the apex
  additionally caches A1 at rest in IndexedDB, wrapped under a WebAuthn-PRF-derived key (Phase 5,
  `MasterKeyWrapper` + `tenshu-sdk-master-keys`), so daily unlock doesn't require the recovery
  code; the wrap key never leaves the authenticator. Browsers without PRF support fall back to
  the desktop/Android lifecycle (in-memory only, recovery code on each session). The wrapped blob
  additionally carries the 24-word phrase's **BIP-39 entropy** (32 bytes, inside the same
  XChaCha20-Poly1305 envelope — the v2 `MasterKeyWrapper` payload) so the web "set up account
  recovery" flow (`Recovery.revealPhrase`, low-friction onboarding §7.4) can re-display the phrase
  after a PRF unlock without re-entry. This adds **no new trust boundary**: the entropy is the
  seed A1 itself derives from, and a wrap-key holder who can open the blob already obtains A1 —
  so carrying the entropy alongside it does not widen what an unwrap discloses. The entropy is
  never persisted in the clear and lives in memory only for the session (web-side
  `JsRuntime.currentEntropy`, same lifecycle as the in-memory master keypair). Joiner devices
  never hold it (they receive derived keys via pairing, not the phrase); v1 (pre-entropy) blobs
  stay valid and reveal resolves `unavailable` until a passkey re-registration upgrades them.
  ⚠⚠ *"Same lifecycle as the in-memory master keypair"* is the invariant, and until **2026-09-14**
  the code met it at neither end. `Recovery.claim` seated the master keypair (via
  `SyncManagerImpl.initialize`) but discarded the entropy it had just decoded, so a recovered
  browser could not reveal its own phrase and wrote a v1 blob at its next passkey registration —
  the pre-v2 state reached by a new route. `claim` now seats it on success, and only when the
  phrase derives the seated keypair. The set of writers should track the set of paths that seat a
  master keypair; that correspondence is the invariant, not the four call sites that currently
  implement it.
  ⚠⚠ **It is not an exact correspondence, deliberately.** A v2 blob carries exactly 32 entropy
  bytes (`MasterKeyWrapper.ENTROPY_SIZE`), and BIP-39 admits 12/15/18/21/24-word phrases yielding
  16/20/24/28/32. A realm whose phrase is shorter than 24 words therefore seats a master keypair
  **without** seating entropy *on the `claim` path*: `claim` declines it, because wrapping a shorter
  one would throw inside `registerPasskey` *after* the WebAuthn credential is persisted.
  ⚠⚠ **The other two setters have no such check, so the behaviour is path-dependent, and the other
  paths are the unsafe ones.** `Session.unlockWithPhrase` and `Realm.create` seat whatever
  `Bip39.decode` returns; for a short-phrase realm `revealPhrase` then *succeeds*, and it is the next
  PRF passkey registration that throws on the 32-byte requirement, after the credential is stored.
  That hazard predates this change and is not fixed by it — `docs/tech-debt.md`, "A phrase shorter
  than 24 words crashes passkey registration on two of three paths". ⚠ Every *production* caller of `Bip39.generateMnemonic()`
  takes its 32-byte default (24 words), so in practice this is a phrase brought in from elsewhere —
  but the function accepts a smaller `entropySize`, so it is not structurally impossible to create
  such a realm.
  ⚠⚠ **The clearing end is still open, and web does not reach the *sealed* state described
  below.** Nothing nulls `currentEntropy` on any path: it outlives both "Lock now" and "Disconnect
  this browser" for the life of the page. Nor would nulling it be enough — sealed means the
  in-memory master-key material is gone, which is `SyncManager.lock()`, and **web dispatches
  `LockRealm` from nowhere**, so A1 and the derived sync keys stay resident either way; each tab
  also owns a separate runtime, so a clear in one leaves the others holding it. Treat the
  sealed-state sentence below as describing Android and desktop until that is fixed —
  `docs/tech-debt.md`, "Web's Lock now does not lock the realm — A1 stays resident", carries all
  three parts.
  A **user-initiated lock** (`SyncManager.lock()` behind the `LockRealm` op — Control's "Lock now")
  purges the in-memory A1 / A13 / derived-key material (and the session recovery phrase) on demand,
  returning the device to the *sealed* state (re-unlock needs the passphrase/biometric wrapper). This
  only **shrinks** A1's in-memory exposure window — the persisted unlock wrapper and the device
  signing key (A4) are untouched, so it adds no new trust boundary and changes no at-rest custody.
  *Amendment (default-headless autoload, [`control-keep-device-unlocked.md`](../proposals/control-keep-device-unlocked.md) §5, Ph1):*
  the "re-derived from the recovery code on each session, never persisted at rest" framing above
  described the state when onboarding **forced a passphrase floor**. That floor is now **optional**.
  On a fresh Android/Desktop realm the default is **headless autoload**: the recovery phrase (the
  seed A1 derives from) is sealed via `SecureStorageDelegate` under the platform's non-exportable,
  hardware-backed keystore key — `AndroidSecureStorageDelegate` under an AndroidKeyStore AES-256-GCM
  key (**StrongBox-preferred**, TEE fallback, no `setUserAuthenticationRequired`), `DesktopSecureStorageDelegate`
  under `DesktopMasterKeyStore` (A21) — and is **not** additionally wrapped behind a user passphrase.
  The OS releases the key **without user interaction after the first device unlock post-boot** (FBE +
  after-first-unlock accessibility), so the realm boots enabled and background sync resumes on its own
  after a reboot with no app-level unlock or notification. Default at-rest protection is therefore
  *device credential (FBE) + app sandbox + hardware keystore* — the **competitor-standard** posture
  (iOS `AfterFirstUnlock`, Android non-auth Keystore) and the same one the web already ships (A1 cached
  at rest under a WebAuthn-PRF key, above). This is **not a new at-rest exposure**: the seed already sat
  keystore-sealed via `storeSecret` before any wrapper was set; the change is that the default no longer
  *also* requires a passphrase. The expert **"Encrypt on unlock only"** hard mode (a later phase) restores
  the passphrase/biometric wrapper (`setUpUnlock`), returning A1 to the sealed-until-unlock lifecycle
  (background sync pauses when locked). The Ph1 **"Require unlock" soft app-lock** is **UI-only** — a
  biometric/device-credential prompt gating *viewing* Control's UI; it never touches key material (keys
  stay headless), so it adds **no cryptographic protection**, only a casual-access / shoulder-surf barrier
  (the WhatsApp/Dropbox app-lock posture). Residual covered by T-K-06 below; the recovery code (never at
  rest by default) + device-revocation remain the backstops for a lost device. ⚠ **Corrected
  2026-08-21:** the parenthetical here read *"GR-7 `Kr` rotation"*, which is **shared-realm** rotation
  — the personal realm has none (see T-K-01). The backstop for a lost *personal-realm* device is the
  relay-side revocation alone.
- **A2. Volume Keys.** Symmetric keys encrypting user data. ⚠⚠ **As-built 2026-08-21: there is ONE
  volume key, and it is not scoped per volume or per epoch.** This entry read *"scoped per volume and
  per epoch"*, which describes a design that was never built: `deriveKeys(phrase)` is
  `Argon2id(entropy) → HKDF → sharedIdentityKey + sharedVolumeKey`, so the volume key is a
  deterministic function of A9 and there is no per-epoch or per-volume dimension to it.
- **A3. Realm Epoch Keys.** ⚠⚠ **Not built, and DECLINED 2026-08-21 — see T-K-01.** The entry read
  *"generation marker used to rotate volume access on revocation"*; no such key exists (the only
  `epochKey` in the tree is `RealmSchemaEpochWriter`, an unrelated schema namespace). The realm
  *epoch* that does exist is a tombstone count used for admission ordering, not a key. Retained as a
  numbered asset so the id stays stable and the decline stays visible.
- **A4. Device Signing Keys.** Per-device Ed25519 keypair. Every block a device publishes is signed
  by its signing key. The device's `deviceId` is **derived from its A4 public key**
  (`deviceIdForPublicKey`; *workstream B sub-amendment*), and the relay refuses any registration
  whose id is not its key's. The relay's `KnownDevicesStore` registers the **A4 pubkey** (not the A8
  pairing pubkey) for each `(ownerId, deviceId)`; this is the value JWT verification on
  `/sync/push` and per-block signature verification on `/sync/changes` resolve against. Pairing
  carries A4 to the relay via `PairingRequest.signingPublicKey`, separate from the
  `PairingRequest.publicKey` field that holds the A8 ECDH key for SAS derivation —
  conflating the two registers the wrong key and breaks JWT auth on every later push.
  *Amendment (Control-on-iOS C4c, [`ios-control-handover.md`](../proposals/ios-control-handover.md) §C4):*
  A4 is now **persisted at rest** via `SecureStorageDelegate` (`SyncManagerImpl.getDeviceSigningKeyPair`
  stores `Base64(pub)|Base64(priv)` in the *secret* bucket — `AndroidSecureStorageDelegate` under the
  AndroidKeyStore, `DesktopSecureStorageDelegate` under `DesktopMasterKeyStore` (A21), iOS
  `IosSecureStorageDelegate` AEAD-sealed (A32)). Before this, A4 was regenerated on every process
  start, so each launch re-registered a fresh pubkey with the relay. The custody bound is therefore
  the platform secret store's (A21/A32); the private key never leaves the device and is excluded from
  iCloud / device transfer where the platform supports it. Residuals: (i) the *first* start after the
  change still rotates A4 once (nothing was persisted before) and re-registers — no worse than the
  prior every-launch behaviour, and the relay re-admits via the master capability, not the stale A4
  entry; (ii) stale `KnownDevicesStore` entries from the pre-persistence era accumulate harmlessly
  (relay auth gates on the *current* JWT, not the orphaned pubkey) — server-side stale-entry GC is a
  noted operational follow-up, not a security gap. The blob is stored in the **per-app** secret bucket.
  *Further amendment (Control-on-iOS C4e-3c, route-1 key hand-off, [`ios-control-handover.md`](../proposals/ios-control-handover.md) §"Route-1 write key custody"):*
  the deferred shared-Keychain custody has now landed. On iOS, route-1 in-process collapse gives the
  co-resident first-party **Drive** app the keys it needs to encrypt + upload sync blocks *itself* while
  it is foreground (so a user-made write syncs instantly rather than waiting for a suspended Control to
  wake — §11.2/§11.5). Control publishes its **A4 device signing key *and* the realm `derivedKeys`**
  (`sharedIdentityKey` for metadata blocks + `sharedVolumeKey` for content chunks) into the **shared
  `app.tenshu.shared` Keychain group** (`RealmKeyMaterialBridge`, items `AfterFirstUnlockThisDeviceOnly`,
  device-bound, excluded from iCloud); Drive reads them cross-app. **This is a deliberate key-custody
  decision** (maintainer-approved, durable shared-Keychain over the ephemeral-hand-off and
  keep-writes-in-Control alternatives): it makes Drive a **key-holding realm device for the first-party
  same-device case only** — the "Drive is keyless" property is preserved for **route 2** (remote proxy /
  third-party apps, which never receive keys and stay on keyless EPC). **Relay exposure is unchanged
  (House Rule #1):** the relay still sees one device identity (A4) and ciphertext blocks; it cannot tell
  which co-resident app authored a block, and no new key reaches it. **New device-exposure delta:** A4
  *and* the realm content keys now sit at rest in a location **two first-party apps** read, and a Drive
  compromise now exposes realm keys where before Drive held none. On a single device this is largely
  academic (both apps share one compromised box; AD3 same-user malware / AD1 disk theft could already
  reach Control's secret bucket), and the trust extension stays **within the user's own first-party app
  group** (one Apple team on-device; bare ad-hoc group on the simulator, C3). The realm
  `derivedKeys` were already persisted per-app on a host/joiner (recovery wrappers / `JOINER_PAYLOAD`);
  this extends that custody to a *second* first-party app, it does not create a new persistence class.
  ⚠ A4 / `derivedKeys` are now **realm key material at rest readable by Drive** — revocation on member
  removal / realm reset must `RealmKeyMaterialBridge.clear()` alongside the existing wrapper purge.
  *Further amendment ([`ios-drive-parity.md`](../proposals/ios-drive-parity.md) P5):* the bridge now
  also carries **A13, the realm's X25519 encryption keypair** — required for the sharing cluster on
  route-1 Drive (contact-invite sealing, shared-realm key unwrap); without it those write-paths fail
  loudly ("Identity key (A13) unavailable"). Same custody boundary and lifecycle as the rest of the
  material (published while unlocked, cleared by `clearDriveSession()`/deprovision, persisted in
  Drive's sealed joiner payload — the slot every pairing joiner already carries A13 in).
  *Further amendment (route-1 adoption in production, [`ios-drive-parity.md`](../proposals/ios-drive-parity.md) P1c):*
  Drive's runtime now **adopts** the handed-off material on launch through the standard joiner path
  (`SyncManagerImpl.initializeWithKeys`), which persists a *second at-rest copy* of the derived keys +
  A4 in **Drive's own per-app secret bucket** (`JOINER_PAYLOAD` / `DEVICE_SIGNING_KEY`, sealed under
  Drive's `IosSecureStorageDelegate` wrap key) — the same persistence class every joiner device already
  has, inside the custody boundary the paragraph above amended. No new readable location beyond
  Drive itself; no relay change. The revocation duty above now extends to Drive's per-app copies —
  **implemented (P2a)**: `ControlSurface.clearDriveSession()` clears the bridge alongside the
  `epc-session` item (the presence token it once held is retired, 2026-11-05), and Drive's shipping gate detects the vanished material on its next
  launch/foreground probe and runs `DriveLocalRuntime.deprovision()` — seals the running graph
  (`SyncManager.lock()`) and purges Drive's at-rest custody (joiner payload, A4 blob, ownerId,
  deviceId + realm latches). On "Forget this device" (`wipeDeviceState`) the clear runs **first**,
  before the device-row retirement wait and the store wipe: cleared last, a sibling that rebuilt
  during the wipe — or after a wipe killed partway — re-adopted the identity being retired; cleared
  first, an interrupted wipe errs toward deprovision. *(S0b,
  [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md) B.14 (191), 2026-10-23;
  every build since B.22, 2026-11-05: the forget wipes the SQLite shared store for every app
  **before** Control's custody purge, and keeps Control's custody when that wipe did not finish,
  answering "not finished": D3's order, never custody gone over a store still present. The bridge
  is still cleared first, so the apps deprovision as before.)* Residual: the sealed old graph's process-lifetime window (same as
  Control's own "Lock now"); a *relay-revoked* device is additionally closed server-side (its JWT
  stops verifying once purged). ⚠ "Lock now" deliberately does NOT clear the bridge — durable
  custody while Control is locked is the point of route 1; only identity-destroying events clear it.

  *Further amendment (first-party route-1 migration, [`ios-first-party-route-1-migration.md`](../proposals/ios-first-party-route-1-migration.md) P2 — key-scope decision **C**):*
  the same route-1 custody now extends beyond Drive to **Fit** (`FitLocalRuntime`, whole-blob
  key material via the same `RealmKeyMaterialBridge`), and by the same recipe to Timeline/Maps as
  they migrate. **Decision C (proposal §4): first-party apps are realm *co-owners* on iOS route-1 —
  there is no inter-first-party-app least privilege.** `RealmKeyMaterial` is one blob carrying the
  whole realm's keys (`sharedVolumeKey` decrypts *all* file content; `sharedIdentityKey` all
  metadata), and it is handed to every co-resident first-party app that adopts, so on one device
  each such app can decrypt any realm content/metadata the others hold (e.g. a route-1 Fit could
  read Drive's file bytes). This was a conscious trade: per-app content scoping is binary (one key
  for the whole content plane) and fights the lens model, and route-1 cannot replicate the
  proxy-mediated per-file permission the Android/desktop separate-process proxy gives (each iOS app
  *is* its own in-process proxy, self-decrypting). **The E2E guarantees are unchanged** — the relay
  still sees only ciphertext + one device identity (House Rule #1), keys live only on
  provisioned-and-adopted devices, and the master key A1 never leaves Control. What is *not*
  provided is defense-in-depth between our own signed apps; true per-file / per-app capability
  (per-app-wrapped CEKs or per-service sub-realms) is a deferred future track, non-negotiable for
  *third-party* apps. The revocation duty above applies per app: each app's shipping gate detects
  vanished bridge material and runs its own `deprovision()` (seal + purge that app's per-app custody
  copies). ⚠ App-Group availability (the shared *replica*) is paid-tier on device; the shared
  *Keychain* key hand-off is free-tier — so the custody exposure (Fit holding realm keys at rest)
  begins the moment Fit adopts, independent of whether the replica is shared or app-local.

  *Further amendment (Home on iOS route 1, [`home-and-mia-apps.md`](../proposals/home-and-mia-apps.md) §8, 2026-10-02):*
  **Home** (`HomeLocalRuntime`, client id `app.tenshu.home.ios`) is another key-holding co-owner
  under Decision C. Once it adopts, Home's process holds the whole `RealmKeyMaterial` blob —
  `sharedIdentityKey`, `sharedVolumeKey`, the A4 device signing key with its device id, `ownerId`
  and the A13 keypair — and keeps the standard second at-rest copy (`JOINER_PAYLOAD` /
  `DEVICE_SIGNING_KEY`) in its own Keychain bucket, `app.tenshu.home.ios.secure-storage`. It never
  holds A1. ⚠ Home's screens are read-only in this slice (devices, hub status, routines, run
  history), and that is a UI and repository restriction, **not** a custody one: the process can
  decrypt, and sign as the device, for the whole realm exactly as Drive can. Same revocation duty:
  Home's shipping gate detects vanished bridge material and runs `HomeLocalRuntime.deprovision()`.
  No relay or protocol change.

  *Further amendment (Mia on iOS route 1, [`mia-query-layer.md`](../proposals/mia-query-layer.md) §19, 2026-10-02):*
  **Mia** (`MiaLocalRuntime`, client id `app.tenshu.mia.ios`) is another key-holding co-owner
  under Decision C. Once it adopts, Mia's process holds the whole `RealmKeyMaterial` blob —
  `sharedIdentityKey`, `sharedVolumeKey`, the A4 device signing key with its device id, `ownerId`
  and the A13 keypair — and keeps the standard second at-rest copy (`JOINER_PAYLOAD` /
  `DEVICE_SIGNING_KEY`) in its own Keychain bucket, `app.tenshu.mia.ios.secure-storage`. It never
  holds A1. ⚠ Mia answers and offers no action in this slice, and `askMia` runs only chains
  `MiaChainValidator` allows; both are limits on that **operation** (A39), **not** on custody: the
  process can decrypt, and sign as the device, for the whole realm exactly as Drive can. Same
  revocation duty: Mia's shipping gate detects vanished bridge material and runs
  `MiaLocalRuntime.deprovision()`. No relay or protocol change.

  *Further amendment (Home on the web, [`web-app-suite.md`](../proposals/web-app-suite.md) §4.H and
  Wave 4 run B, 2026-10-28):* the **Home origin** (`home.tenshu.app`; `home.tenshu.xyz` on the dev
  lab) becomes a reader of the realm's `HomeDevice` / `DeviceState`, `Routine` /
  `RoutineRun` and `HubStatus` rows under F5 (T-CI-09b's web amendment). Unlike iOS route 1 it holds
  **no custody**: no A4 key, no realm key, no replica — the apex runtime holds those and answers over
  the bridge, and the origin reaches exactly the eight `home.*` reads by exact name
  (`web/apex/src/apps/registry/apps/home.ts`, pinned by `gatewayPermissions.test.ts`; no other origin
  may call `home.*`). It is **read-only** below the UI too: the web runtime maps no Home operation, so
  there is no device command, routine write or hub command to reach. The data is the same iOS Home
  reads. ⚠ So XSS on that origin can read device names, rooms and states, routine programs (device
  ids, times, notification text, geofence place ids) and run history — and nothing else. No relay or
  protocol change.
- **A5. User Data.** Stored encrypted both at rest on the relay and at rest on devices.
  *Amendment (synced thumbnails, [`drive-synced-thumbnails.md`](../proposals/drive-synced-thumbnails.md)):*
  a file's `File.Thumbnail` is a **low-resolution derivative of A5** — a small
  preview rendered on the uploading device and stored on the `File` entity, so it
  rides the file's existing **eager** metadata block to every paired device. It is
  protected identically to any other synced field: encrypted under the realm's
  shared identity key, so **the relay still sees only ciphertext (House Rule #1
  preserved)** and learns nothing beyond a marginally larger block (A6 already
  exposes block sizes). No change to the relay's data shape or the sync protocol —
  it is an ordinary value property on an already-`Syncable(Eager)` entity.
  **Device-exposure delta worth recording:** the thumbnail propagates eagerly to
  *all* of a user's devices regardless of that device's per-file sync policy,
  including files a device keeps **online-only** (bytes intentionally not
  retained — `drive-offline-online.md` §4). So a compromised *device* (actor AD1
  disk theft / AD3 same-user malware) that previously held no visual of an
  online-only file now holds its low-res thumbnail at rest. This is the intended
  trade (browsability without bytes) and the thumbnail enjoys the same at-rest
  protection as the rest of the device's synced metadata (A21 on desktop); it is
  not a relay-visibility change. A cap (`MAX_THUMBNAIL_BYTES`) keeps the thumbnail
  metadata-plane-sized so it never spills onto the bulk content channel (A2/A5
  chunk store).
  *Amendment (motion thumbnails, [`photos-video-support.md`](../proposals/photos-video-support.md)
  M3/M4, 2026-09-08):* `File.ThumbnailMotion` is a fourth derivative of A5 — a ~3 s, 256 px,
  **silent H.264 clip** of a video, minted on the uploading device (Android only today) and
  stored like the still classes have been since C7: on the **content plane** under its own
  derived content id, `Syncable(OnDemand)`, so it is sealed chunks plus a manifest and travels
  only to a device that asks. **Relay exposure is unchanged (House Rule #1 preserved):** the relay
  stores one more ciphertext blob per video and learns nothing from its size either, because the
  cap (`MAX_MOTION_THUMBNAIL_BYTES`, 96 KB) is met by construction — every clip costs about the
  same regardless of the footage (its §12.1), so a blob's size does not distinguish a static scene
  from a busy one. No change to the relay's data shape or the sync protocol;
  `RELAY_PROTOCOL_VERSION` is untouched, and `FileService` v10 (M3) fences a peer that has never
  heard of the property. ⚠ **Worth stating rather than implying: a clip is a strictly richer
  derivative than a still.** Three seconds of a private moment disclose more than one frame of it,
  so the first amendment's device-exposure delta applies with more weight — a device that has
  displayed the grid holds short loops of the user's videos at rest, under the same protection as
  the rest of its synced content, and a compromised device (AD1/AD3) that already held the poster
  still gains the motion. The conclusion does not change (both are E2E-sealed and the relay learns
  neither), and the OnDemand strategy bounds it: only a device that actually rendered the tile
  ever holds the clip, and the keeper's ceiling can drop it again — it is one of the evictable
  classes, spent coldest-first (⚠ a blob whose recency the platform cannot report sorts **last**),
  never first by class (its §4.1).
  *Amendment (the `XXLARGE` rung, [`drive-xxlarge-thumbnail-rung.md`](../proposals/drive-xxlarge-thumbnail-rung.md)
  X-1, 2026-09-18):* `File.ThumbnailXXLarge` is a fifth derivative of A5 — a 2048 px JPEG, minted at
  upload on the device holding the bytes and, once a device declares the class, backfilled onto
  older files, stored exactly as the classes above have been since C7: content plane, own derived
  content id, `Syncable(OnDemand)`.
  **Relay exposure is unchanged (House Rule #1 preserved):** one more sealed blob per image, and its
  size band (~0.3–1 MB, capped at `MAX_XXLARGE_THUMBNAIL_BYTES`) tells the relay no more than the
  original's already does. `RELAY_PROTOCOL_VERSION` is untouched; `FileService` v12 fences a peer
  that has never heard of the property.
  ⚠ **The device-exposure delta is the sharpest of the five, and worth stating plainly:** at 2048 px
  this derivative is *legible* — faces, text in a screenshot, a document's body — where 100 px and
  256 px were not. A device that pulls it (a viewer opened the photo there) holds that at rest, under
  the same protection as the rest of its synced content. Two things bound it: the class travels only
  to a device that asks for it by name, and it is **evictable** — the keeper's ceiling (D-20) spends
  it coldest-first, so it is not a permanent accumulation the way an eagerly-synced class would be.
  *Amendment (X-3, 2026-09-18):* **the exposure above is now live on one surface.** Photos declares
  `xxlarge` at launch and its viewer names the class for a photograph opened above 1024 px, so the
  backfill mints it for existing files on a byte holder and any device running Photos can come to
  hold one. What that changes, precisely: the bound *"travels only to a device that asks for it by
  name"* still holds and is now exercised — the ask is a viewer opening one photograph, never a grid
  tile or a feed row, which are capped below the class by `photos-app.md` §33. The second bound —
  evictable, coldest-first — is unchanged and is what keeps this from accumulating.
  ⚠ The web lightbox and Timeline's hero are still to come (X-5, X-6); each widens *which devices*
  hold the derivative, not what the relay sees.
  ⚠ X-3 adds a second read of the same resolver, `File.ViewerPreview`, which renders from the
  original rather than take a stretched rung. It reads local bytes only and publishes nothing, so it
  moves no data to the relay and adds no at-rest derivative.
  ⚠ `FileService` v13 fences a v12 peer **in one direction only**: a v13 app against a v12 proxy is
  refused loudly, while a v12 app against a v13 proxy is admitted (the gate refuses above the current
  version or below the migration floor, and the floor is 1). The stale app simply never asks for the
  property; what it eventually meets is `SchemaPullFence`, whose copy does not name the service.
  *Amendment (mint on view, X-4, 2026-09-18):* a device that opens a photograph nobody minted a
  large rung for may now **download the original and mint the rung itself**, publishing it inline
  (D-19). The residual this adds is one of **timing, not of linkage**: the rung's edge already joined
  it to its original by `holderHash` whoever minted it, so what is new is only that the rung appears
  immediately after that original's `GET` — which marks that read as a **viewer dwell** rather than
  an offline-keeper fetch or a download. ⭐ The maintainer settled D-19 as inline accepting exactly
  that, the alternative being a bounded pending state that cannot be exempt from eviction.
  ⚠⚠ **One sharpening it does add, beyond timing.** Before X-4 nothing in the Photos viewer ever
  resolved `File.Data` — the viewer projected thumbnail relations only. A whole-original chunk-set
  `GET` from a device that does *not* hold the file is therefore a new signal, and a relay that can
  tell a full fetch from a single rung fetch can separate *looked at* from *scrolled past*. The rung
  pull already runs for the composed neighbours, so that signal is blurred by ±1 page; the original's
  fetch is not — it happens only for the page settled long enough to count as looked at.
  ⚠ What it does **not** add: no per-file demand signal syncs (§10.3 is kept), the relay sees the
  same object shapes it already saw, and `RELAY_PROTOCOL_VERSION` is untouched. The op is new but
  carries no new data to the relay — its own parameters never leave the device.
  ⚠⚠ **The download is gated in the proxy, not in the app**: quota first (so an original is never
  spent on a rung the relay would refuse), then a **metered** refusal that only an explicit user
  request lifts. A UI check could not bind another caller, or a network that turned metered after it
  looked. ⚠ iOS wires no metered signal today, so there the gate is open — recorded in
  `drive-xxlarge-thumbnail-rung.md` §9.6 rather than silently accepted.
  *Amendment (iOS route-1 in-process collapse, Control-on-iOS C4e):* on iOS the
  proxy's on-device realm **replica** (the `FileObjectStore` of decrypted `DbEntry`
  rows — A5 user data + A6 metadata at rest) now lives in the **shared App-Group
  container** (`group.app.tenshu.shared`) rather than Control's app-private
  Documents dir, so the co-resident first-party Drive app can read **and write** the
  *same* replica **in-process** (route 1 — Drive runs realm reads/writes directly over
  the shared store instead of routing every op through Control's process; the §11.2/§11.5
  same-device EPC "wake-window" is what this avoids). Because an App-Group container is
  read-write to every member, *write* integrity rests on a **single-writer
  `FileAdvisoryLock`** (`flock`) the writing process holds for the duration of a replica
  mutation, so the two first-party apps take turns rather than tearing each other's
  writes; the lock is the integrity discipline, not a confidentiality boundary (both
  apps are trusted writers within the user's own app group). **Relay exposure is unchanged
  (House Rule #1 preserved):** the replica is device-local; nothing new is sent to
  or visible to the relay, no key material is shared (the at-rest blob is OS-protected
  plaintext, *not* app-encrypted — the sync-block crypto plane stays upstream at
  ingest, and decoding needs no key), and the sync protocol / relay data shape are
  untouched. **Device-exposure delta worth recording:** the realm's at-rest
  plaintext metadata is now in a container shared between **two first-party apps**
  (both the user's own, signed under one team on-device; bare-group ad-hoc on the
  simulator) rather than one app-private container. On an uncompromised device this
  is invisible — both apps belong to the user. On a compromised device, actor AD3
  (same-user malware) / AD1 (device theft) that could already read one first-party
  app's container gains nothing materially new by the two apps sharing one; the
  trust extension is deliberate and stays *within* the user's own first-party app
  group. No new relay-visibility, no new key custody.
  *Amendment (iOS shared replica on SQLite, [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md),
  S0b-4, 2026-11-01; on every build since B.22, 2026-11-05, with no switch):* the route-1 shared
  replica moves from the one-file-per-object `FileObjectStore` to a single SQLite database, `replica/objects.db` (with
  SQLite's `-wal` and `-shm`), in the same App-Group container (`group.app.tenshu.shared`). The rows
  are the same `DbEntryCodec` plaintext as before (A5 user data + A6 metadata at rest, decodable
  without key material). **Write integrity** now rests on SQLite's own locking and transactions
  instead of the advisory `flock`: every store access is one SQLite transaction holding the database
  write lock, a multi-row change is atomic against a crash, and a sibling never observes part of
  one; a replacement of the store (a wipe, a migration) moves a random epoch that fences every other
  process's handle before it can write. As before, the lock is an integrity discipline among trusted
  first-party writers, not a confidentiality boundary. **At rest:** the `replica/` directory and the
  store's files are set explicitly to `NSFileProtectionCompleteUntilFirstUserAuthentication`
  (previously the unasserted default) and the directory excluded from backup
  (`NSURLIsExcludedFromBackupKey`), both read back **at every open**, and the store is not handed
  out without them; `files/`, `upload-staging/` and the HLC watermark `sync-hlc/` beside it get the
  same two flags at every open (best effort, retried). The replica is unreadable before the first unlock after
  boot and readable afterwards while locked, which background wakes require and which matches the
  `AfterFirstUnlockThisDeviceOnly` class of the realm keys those wakes use (A1, A4 route-1
  amendments). **The old store is reset, not drained** (the maintainer's decision, that doc's B.22,
  2026-11-05, superseding S0b-4's drain-proven cutover of B.20): no iOS build had reached a user,
  and the only phones holding the shared `FileObjectStore` were test phones carrying test data. So
  whichever first-party process launches first, under the file store's `flock`, renames the old
  `objects/` aside **with** its watermark `sync-hlc/` and S0b-4's leftovers (a done mark keeps any
  app from making a file store again), deletes them in the background, and the SQLite store
  re-pulls from the relay (cursor 0). ⚠ **Accepted loss:** anything only that test phone held (an
  outbound item not yet pushed) is gone with it; this is a one-time step for those phones, never a
  path a shipped user's data takes. `files/` (content-keyed) and `upload-staging/` (sealed chunks by
  ref) stay. A reset that cannot complete (its `flock` busy past a 300 ms bound, a rename refused)
  leaves that launch on a retryable not-ready stand-in, never on the SQLite store beside the old
  watermark and never on the file store, which no build runs any more (B.22.6 (427), (432)).
  **Relay exposure unchanged (House Rule #1 preserved):** device-local only; no key material, sync
  protocol or relay data shape changes.
  *Amendment (the 1.0.1 re-cut, that doc's B.22.9, 2026-11-05):* the launch reset above, its done
  mark and `FileObjectStore` itself are **deleted**. Every test phone is cleared at the operational
  reset and every app re-ships as 1.0.1, so no build reads, writes or removes `objects/`. ⚠ A
  leftover from a pre-1.0.1 build is therefore never removed by code: an `objects/` stays in the
  container, unread, at its old protection and backup flags until every app is deleted; a
  `sync-hlc/` is where the SQLite store keeps its live watermark, so an old one would be adopted and
  over-state what the new store holds (the forged tombstone-GC acknowledgement the reset guarded
  against, T-T-08). That is why test phones are wiped by hand and no pre-1.0.1 build may be installed
  beside a newer one. Relay exposure unchanged.
  *Amendment (durable iOS content cache, [`ios-fit-timeline-maps-parity.md`](../proposals/ios-fit-timeline-maps-parity.md)
  P4):* the same C4e trust extension now also covers **content bytes**, not only the
  metadata replica. iOS bound an in-memory `FileDatabase`, so every decrypted byte —
  Drive's plaintext cache, Timeline's keep-offline files, a downloaded map region —
  lived only in RAM and died with the process; `IosFileDatabase` writes them to a
  `files/` directory in the **same App-Group container** as the `objects/` replica
  above. **This is a durability fix whose privacy delta is exactly the one already
  accepted for the replica**, one plane down: the plaintext is OS-protected at rest
  (Data Protection, no app-layer encryption — the sync-block/content-chunk crypto
  plane stays upstream at ingest) and readable by the user's other first-party apps
  in the group, which is the co-owner posture route-1 chose deliberately
  (`ios-first-party-route-1-migration.md` P0-C: `sharedVolumeKey` is realm-level and
  every first-party app already holds it, so an app that can read the cache could
  have decrypted the same bytes itself). **Relay exposure unchanged (House Rule #1
  preserved)** — device-local only; no key material, sync protocol or relay data
  shape is touched. **Device-exposure delta worth recording:** plaintext user data
  that previously survived only until the app was killed now **persists on disk**, so
  AD1 (device theft, mitigated by device-lock Data Protection) and AD3 (same-user
  malware) reach content they would previously have found only in a live process's
  memory. That is the same trade Android and desktop already make — a cache that does
  not survive a restart is not a cache — and it is what makes keep-offline honest
  rather than a button that silently forgets. The eviction half landed 2026-08-03
  (`retention-quota-model.md` Face B): the keeper's sweep enforces a free-disk floor
  by default and an opt-in byte ceiling, evicting unpinned content coldest-first.
  That *reduces* at-rest exposure relative to the unbounded cache described above —
  plaintext now has a maximum lifetime on disk rather than only a maximum size.
  *Amendment (durable desktop content cache):* the same change on desktop Control, one
  day after iOS and for the same reason. Desktop had inherited `InMemoryFileDatabase`,
  so decrypted content lived only in RAM and was re-fetched from the relay after every
  launch; it now writes to `<data-dir>/content/`, beside the `objects.db` replica whose
  at-rest posture A21 already records. **The delta is smaller here than on iOS**: no
  container is shared with another app (desktop Control is app-private), so this is
  purely "plaintext that used to die with the process now persists on disk" — AD1
  (device theft, mitigated by the OS's own full-disk encryption: FileVault, BitLocker,
  LUKS) and AD3 (same-user malware) reach content they would previously have found only
  in a live process's memory. That is the trade Android has always made, and the same
  one the hub made explicitly at H3 (§8.15 / T-C-17) with the *same* implementation —
  `JvmFileDatabase` is now shared by both. Relay exposure unchanged (House Rule #1):
  device-local only; no key material, sync protocol or relay data-shape change.
  ⚠ The cache was bounded only by free disk when this landed; eviction followed on
  2026-08-03 (`retention-quota-model.md` Face B) — a free-disk floor on by default
  plus an opt-in ceiling, both evicting unpinned content coldest-first. No new
  exposure: eviction only ever *removes* plaintext.
  *Amendment (iOS inference stores leave the App Group, [`ios-on-device-inference.md`](../proposals/ios-on-device-inference.md)
  §3.1 / §13, PIO-7 PR-3, 2026-10-07):* a **narrowing** of the C4e extension above. Until this
  change every route-1 process bound the device-local inference stores — the image-vector log, the
  face-template log (A40) and the model bytes — over the shared `IosFileDatabase`, so derived
  content of A5 would have sat in the **App-Group container**, written by whichever first-party
  process resolved it. Now exactly one app (Photos) owns them, in **its own container**
  (`<Application Support>/photos-private`), and every other iOS graph, Control's included, binds stores
  that hold no file database: they open nothing, write nothing and clear nothing in the shared
  container. The logs were empty on every shipped build (no iOS build binds a backend), so nothing
  moves; this fixes where the first vector will land. **What changes for an attacker:** AD3 code
  running as another first-party app no longer reaches the vectors through the group container; it
  would need Photos' container. AD1 is unchanged (Data Protection at rest, no app-layer encryption,
  as for the content cache above). **Two properties the private location adds:** the directory is
  marked excluded from backup, so vectors, weights and (later) templates never enter an iCloud or
  Finder backup — ⚠ **and this fails closed**: the flag is set with its error read and then read
  back, and a directory on which it cannot be established is never written to (the stores read as
  empty, refuse to store anything and stay unusable). What such a directory **already holds** is
  removed first, the model weights and their index included: without the flag it is being backed
  up as it stands. A purge that could not finish is retried at every access to those stores, and
  when a consent withdrawal asks the template store what it holds, until nothing is left; so a
  later wipe, withdrawal or deprovision removes what the first attempt could not; and the model index moved out of the Keychain to sit beside the bytes, so nothing
  about inference outlives an uninstall. ⚠ **What the move costs, and how it is paid:** the
  realm-wipe hook runs in the process that observes the wipe, and a reset made from Control while
  Photos is not running no longer reaches Photos' stores. They carry a **stamp of the realm they
  were built for** (the owner id; the shared store's `meta.epoch` joins it after S0b-1), checked
  at install, on every return to the foreground, at the start of every index pass and before
  every `photosLike`; a mismatch cancels a running pass and clears the vectors and templates
  before anything reads them, and the stamp is rewritten only after both clears return. The same
  check, under the same lock, then applies the runtime fingerprint (model archive, OS build,
  compute units, preprocessing revision), so a query or a pass that reaches the stores before the
  launch-time check has run cannot read or extend vectors made under another one.
  ⚠ **Residual, accepted until S0b-1:** a reset that keeps the owner id is not seen by the stamp.
  Vectors are keyed by file path, so a photograph recreated at the same path could be skipped as
  already indexed and an old vector could rank under it; nothing crosses to another user, and
  deleted photographs still drop out through the live-path filter. ⚠ **Residual:** stale vectors
  stay on disk, unread, for as long as Photos is not launched after a reset. **Relay exposure
  unchanged (House Rule #1):** device-local only; no key material, sync protocol or relay data
  shape is touched, and no schema'd row is reshaped.
  *Amendment (Android Auto Backup opt-out):* Control sets `android:allowBackup="false"` so its
  app-private ObjectStore replica (`files/objects.db` — A5 user data + A6 metadata at rest) and any
  cached content bytes in `files/` are **excluded from Android Auto Backup and device-to-device
  transfer** — they never reach the user's Google cloud, where they would otherwise survive an app
  wipe and re-hydrate stale files on reinstall/transfer (a data-durability *and* privacy gap: A5/A6
  plaintext-at-rest sitting in a third-party cloud). The prior `data_extraction_rules`/`backup_rules`
  excluded only the secret store, not the ObjectStore; `:drive-android` already set
  `allowBackup="false"`. Relay exposure unchanged (House Rule #1 preserved) — device-local only.
  *Amendment (in-app video playback, [`drive-content-preview.md`](../proposals/drive-content-preview.md)
  §5 Phase 4):* a video opened for playback is materialised as a **transient decrypted
  plaintext file** in the app-private temp dir (`java.io.tmpdir`, the app cache on
  Android — sandboxed, not external/shared storage), because the platform player
  (`VideoView`/`MediaPlayer`) seeks against a file path, not a memory blob. The file is a
  full plaintext copy of one A5 object for the duration of playback and is **deleted when
  the viewer leaves the back stack** (`VideoPreviewViewModel.onCleared`). **Relay exposure
  is unchanged (House Rule #1 preserved):** nothing is sent to or visible to the relay, no
  key material is involved, the sync protocol / relay data shape are untouched — the bytes
  were already decrypted client-side via the same `getRemoteFile` path images and PDFs use;
  the only delta is *where* the plaintext lives (disk, briefly) rather than memory.
  **Device-exposure delta worth recording:** a compromised device (AD1 disk theft / AD3
  same-user malware) could observe the plaintext video on disk during playback, and — until
  the delete runs (or after an OS kill that pre-empts `onCleared`) — briefly afterward. This
  is the same class of exposure as macOS's File Provider transient plaintext materialisation
  (§intro) and is the accepted cost of native playback; it is bounded to app-private storage
  and to the playback window. Desktop has no player, so it never materialises the file. ⚠
  Hardening not yet done: explicit exclusion from OS backups/indexers, and best-effort
  deletion on next launch for files orphaned by an OS process kill.
  *Amendment ("open in another app", [`drive-content-preview.md`](../proposals/drive-content-preview.md)
  §6 Decision D3):* the fallback preview for a file Drive can't render in-app (documents,
  archives, unhandled types) offers an **Open in another app** action that materialises the
  **same** transient decrypted plaintext temp file (`writePreviewTempFile`, app-private cache)
  and then **hands it to a third-party app** via the OS — Android through a `FileProvider`
  `content://` URI on an `ACTION_VIEW` chooser, desktop through `java.awt.Desktop.open`. Two
  deltas beyond the video case: (1) the plaintext is **deliberately disclosed to another
  app** the user picks — a *user-initiated* hand-off, not an automatic one; once opened, that
  app holds its own copy outside Drive's control (the same trust step as any OS "share / open
  with" on any platform). (2) On Android the temp file is briefly reachable by the chosen app
  via a **per-launch** read grant (`FLAG_GRANT_READ_URI_PERMISSION`) through a **non-exported**
  `FileProvider` whose root is scoped to the app cache dir (`<cache-path>`) — no broad or
  persistent URI exposure, no other app can enumerate it. **Relay exposure is unchanged (House
  Rule #1 preserved):** nothing reaches the relay, no key material is involved, the bytes were
  already decrypted client-side via the same `getRemoteFile` path; the protocol / relay data
  shape are untouched. The temp file is deleted in `OpenExternallyViewModel.onCleared` (same
  ⚠ orphan-on-OS-kill caveat as the video case above).
  *Amendment (streaming preview / D2b, [`drive-streaming-preview.md`](../proposals/drive-streaming-preview.md)
  §3.5/§4/§5):* for **streamed** video the whole-file decrypt-to-temp above is **replaced** by an
  on-demand seekable source: the player reads byte *ranges* through a proxy file descriptor whose
  `onRead` calls `ReadContentRange`, decrypting only the chunk(s) a range touches. **This is a net
  *reduction* in A5 disk exposure, not a new one:** the whole-file plaintext **never lands on disk**
  for a streamed video — Control holds only a bounded few decrypted 2 MiB chunks in **memory** (an
  LRU, `StreamingContentSession`) and Drive holds a single read's bytes transiently in memory; the
  D2a temp file (and its orphan-on-OS-kill caveat) does not exist on this path. API < 26 falls back
  to the D2a temp file (its amendment above still applies there). **Integrity delta worth recording
  (§4):** because a seek reads chunk K without 0…K-1, the up-front whole-file `wholeFileHash` +
  truncation gate the reassemble path enforces **cannot** hold for the streaming consumer; it is
  **relaxed** for render-only playback. Every byte surfaced is still individually authenticated to
  the right position of the right file — per-chunk `cipherHash` + the XChaCha20-Poly1305 AEAD tag +
  the AAD `(fileId, chunkIndex, chunkCount)` position-binding are enforced on **every** read (a
  relay serving wrong/flipped/reordered/cross-file-spliced bytes is caught). The whole-file gate
  stays the contract on the reassemble path (open-externally, download-for-offline — anything that
  produces a durable whole-file artifact). **Relay exposure is unchanged (House Rule #1 preserved):**
  the chunks ride the **same** content store + ops the reassemble read already used; no new
  transport, no relay-visible data, no key material crosses, the sync protocol / relay data shape
  are untouched — the only delta is *when* + *how much* is decrypted (a range on demand vs the whole
  file up front). *S4 consumer note:* on Android the streaming session reaches `VideoView` via a
  `content://` provider (`StreamingVideoProvider`) declared `exported="false"` +
  `grantUriPermissions="false"` — the uri is opened only by Drive's **own** process
  (`MediaPlayer.setDataSource(context, uri)` calls `ContentResolver` in-process, then passes a raw
  fd to the media server), so the provider grants no third party access and **adds no exposure
  surface** beyond the in-app decrypted bytes the player already handles (contrast the D3
  open-externally FileProvider above, which *deliberately* discloses to a third-party app).
  *S7 iOS consumer note ([`ios-drive-parity.md`](../proposals/ios-drive-parity.md) S7):* the same
  streaming contract now backs iOS video — a Swift `AVAssetResourceLoaderDelegate` fulfils each
  `dataRequest` by pulling ≤256 KiB ranges through the shipped `ReadContentRange` op (only the
  touched chunks decrypt, in the proxy session's bounded LRU), replacing P4's D2a whole-file
  decrypt-to-temp for video. Like Android S4 this is a **net *reduction* in A5 disk exposure**: no
  whole-file plaintext temp exists on the streamed path — only a transient ≤256 KiB range crosses
  into Swift (as `NSData`) per read, plus the bounded decrypted-chunk LRU in the proxy (which under
  route-1 is Drive's own in-process proxy). The custom-scheme `AVURLAsset` is played only by Drive's
  own `AVPlayer` — no third-party disclosure (contrast D3). The D2a temp-file path remains the
  offline/open-fail fallback (its amendment above still applies there). **Relay exposure unchanged
  (House Rule #1 preserved):** the ranges ride the same content store + ops the reassemble read used;
  no new transport/endpoint/relay-visible data, no key crosses, protocol / relay shape untouched.
  *Amendment (iOS route-1 background upload / O3-B, [`ios-drive-parity.md`](../proposals/ios-drive-parity.md)
  P3b):* on iOS a bulk upload's already-sealed content-plane chunks are **staged to files** in the
  shared App-Group container (`upload-staging/`) and transferred to the relay content store by a
  background `URLSession` (out-of-process `nsurlsessiond`), so a transfer survives the app being
  suspended or terminated. **This is a transport change, not a new key custody or content-key
  exception.** The route-2-era O3 write-up
  ([`ios-proxy-architecture.md`](../proposals/ios-proxy-architecture.md) §9.5) framed background
  upload as needing a *content-key exception* because Drive was then a key-less EPC client; route-1
  already made Drive a **key-holding realm device** (the A4/A5 route-1 amendments above), so it
  already encrypts chunks under a per-content CEK and PUTs blobs from its own process today (P3a,
  foreground). P3b changes only *how* those already-sealed bytes reach the relay. **New at-rest
  surface:** transient **opaque AEAD ciphertext** in the App-Group container for the life of the
  transfer — the *same* posture as the ObjectStore replica already there (both device-local, both
  readable only within the user's own first-party app group). **Nothing else sensitive is staged:**
  no key and no bearer token — the durable journal holds only content-addressed `blob-<sha256>` refs,
  and the device-signed JWT (A4) + entitlement headers are **re-minted per (re-)enqueue**, never
  persisted (a JWT baked into a background task can expire before `nsurlsessiond` runs it → the 401
  re-mint path). **Relay exposure is unchanged (House Rule #1 preserved):** the chunks ride the
  **same** `PUT /sync/content/{ref}` content store + `blob-<sha256(ciphertext)>` refs the foreground
  path already used — no new endpoint, no relay-visible plaintext, no key crosses, the sync protocol
  / relay data shape are untouched; the only delta is the transport (a background session) plus a
  transient ciphertext staging file. **Device-exposure delta:** AD1 (disk theft) / AD3 (same-user
  malware) could observe the staged *ciphertext* until the transfer acks and the file is deleted —
  but it is AEAD-sealed and useless without the realm keys those actors would separately need, so it
  adds nothing material beyond the replica already in the container. ⚠ Live survives-suspension
  behaviour is hardware-only (the P7 on-device pass), like P6a's BGTask.
  *Amendment (calendar reminders on a lock screen, [`calendar-app.md`](../proposals/calendar-app.md)
  §4.3 gap 2 / Q10; CA-5b):* an event reminder puts **event text on a lock screen** — the title and
  location of an A5 row, rendered by the OS to whoever can see the device, without unlocking it.
  This is a **deliberate disclosure**, and the same class as the transient decrypted previews above:
  the whole point of a reminder is to be legible before you pick the phone up. **Relay exposure is
  unchanged (House Rule #1 preserved):** nothing is sent to or visible to the relay, no key material
  is involved, and the sync protocol / relay data shape are untouched — the text was already
  decrypted client-side. The delta is *where* a fragment of one A5 row is rendered, and to whom.
  **Adversary delta:** **AD1** already names the shoulder-surfer, but until now that adversary had
  to *hold* the device to reach anything. A reminder is the first Tenshu surface that discloses a
  fragment of A5 to someone who only has **line of sight to a locked screen** — no possession, no
  unlock, no malware. AD2 (a motivated individual with proximity — an ex-partner, a stalker) gains
  the most, because for them repeated glances at a lock screen are a realistic collection method.
  Adversaries who already hold or have compromised the device gain nothing new: they reach the whole
  store regardless.
  **Mitigation — the `Calendar.sensitive` flag (Q10):** a calendar marked sensitive yields reminders
  with **no event text whatsoever** — a fixed generic title, an empty body, and no calendar name
  (which would itself name the category the flag exists to hide). ⚠ Default is *show*, decided
  deliberately: a reminder that will not say what it is for reads as broken, and a default nobody
  understands is a default nobody keeps. ⚠⚠ **Redaction is applied where the reminder is
  *composed*, not where it is displayed** (`ReminderPlanner`), and that is load-bearing rather than
  stylistic: on Apple platforms the OS is handed finished notification text days in advance and the
  app never sees it again (§4.6), so a display-time redaction would be structurally unable to fire.
  ⚠ The **location** is redacted with the title — "Oncology, 3rd floor" says everything the title
  would. ⚠ The notification's **tap payload** carries event and occurrence *ids only*, never the
  title, so a redacted alert cannot smuggle its text back through a `PendingIntent` extra; and
  reminders use **one** channel for all calendars rather than one per calendar, because a
  per-calendar channel would list the calendar's *name* in the OS settings UI — the same disclosure
  by another route. ⚠ Alerts are posted
  `VISIBILITY_PRIVATE` — set explicitly rather than inherited, so the user's own OS-level "hide
  sensitive notifications" setting can redact them, and so a future platform default cannot silently
  un-redact every alert Tenshu posts. **Not yet closed:** a global "hide all details on the lock
  screen" control — the per-calendar flag is the only granularity today.
  *Amendment (calendar export to a file, [`calendar-app.md`](../proposals/calendar-app.md) CA-6b):*
  exporting a calendar writes a **plaintext `.ics`** — every event's title, location, notes, times
  and recurrence, plus whatever the lossless `EventSource` sidecar preserved from an earlier import
  — to a destination the user picks, **outside the app's storage and outside Tenshu's control**.
  This is the same class as D3's *open in another app* above and is scoped the same way: a
  **user-initiated, one-file hand-off**, never automatic, never a background copy. Once written the
  file is an ordinary document — it can be backed up to a cloud, mailed, or read by any app with
  storage access, and Tenshu can neither track nor revoke it. That is what an export *is*, and it is
  the point: a calendar you cannot take out is a calendar you are locked into (§2 item 10, "the
  migration story"). **Relay exposure is unchanged (House Rule #1 preserved):** nothing reaches the
  relay, no key material is involved, the rows were already decrypted client-side, and the sync
  protocol / relay data shape are untouched.
  **Delta beyond D3, and it is the one worth recording:** D3 discloses **one file** the user had
  already opened; an export discloses **a whole calendar at once**, and it is *durable* rather than
  transient — there is no `onCleared` that deletes it. It is also the first Tenshu surface where the
  `Calendar.sensitive` flag does **not** apply: the flag redacts *reminders*, and an export is the
  user deliberately asking for the full text. Marking a calendar sensitive and then exporting it is
  not a contradiction the app should silently resolve — but it does mean `sensitive` must not be
  read as "this calendar never leaves in the clear".
  ⚠ **Scope of the OS grant.** On Android the destination comes from the Storage Access Framework
  (`CreateDocument`), so Calendar holds a write grant to **that one file** and declares **no storage
  permission at all** — the alternative (a `WRITE_EXTERNAL_STORAGE`-style path) would put every file
  on the device in reach for the sake of one. Desktop writes through the user's own file dialog. The
  **import** direction is the mirror (`OpenDocument`) and discloses nothing: it reads one file the
  user chose into the encrypted store.
  **Not yet closed:** no warning is shown before an export names a sensitive calendar, and there is
  no export-to-encrypted-archive option. Both are UI, not protocol.
  *Amendment (calendar subscriptions, [`calendar-app.md`](../proposals/calendar-app.md) §2.4 / Q9;
  CA-7):* subscribing to a remote `.ics` — a holiday feed, a fixtures list, school terms — makes
  Calendar **reach a third party on a schedule**, which nothing else in Calendar does. ⚠⚠ **The
  disclosure runs the other way from every A5 amendment above.** Those disclose the user's *data*
  outward; this one discloses **who and where the user is** to a publisher who was not previously
  in the picture: their IP address, an approximate location from it, that they hold this particular
  calendar, and — from the fetch cadence — roughly when their devices are awake. Nothing of the
  user's own calendar is sent; the request is a bare conditional `GET`.
  **Relay exposure is unchanged (House Rule #1 preserved):** our relay never sees the fetch, the
  events land in the ordinary encrypted store, and the sync protocol / relay data shape are
  untouched.
  **Consent shape, and why there is no global toggle:** Q9 settled this as **per-subscription
  opt-in**, and *creating a subscription is the consent* — there is no ambient behaviour to
  consent to, because a user with no subscriptions makes no requests. Removing one stops it
  permanently; **pausing** (`CalendarSubscription.enabled = false`) stops the egress while keeping
  the events already fetched, which is the setting for someone who wants the holidays but not the
  traffic. Contrast `PlaceGeocodingControl`, which *is* a global gate — correct there because the
  behaviour it governs (enrichment on capture and on import) happens without a per-item act.
  ⚠⚠ **The subscription row syncs; the fetch does not.** A feed added on the phone appears on the
  laptop, and **each device fetches for itself** — so the publisher sees one IP per device, not
  one per account. Nominating a single fetching device would need an election this design does not
  have, and the events converge through the sync plane regardless. The add-subscription copy has
  to say this; a user who assumes one device fetches on behalf of the rest has assumed wrong.
  ⚠ **HTTPS only, and `http://` is refused rather than upgraded** (`normalizeIcsUrl`). A silent
  upgrade would claim a guarantee we cannot make if the host does not serve TLS, and a calendar
  fetched in the clear discloses its *contents* to every hop — a far stronger disclosure than the
  IP the user agreed to. `webcal://` is accepted and rewritten to `https`, because it is what every
  publisher's *Subscribe* button emits and is not a distinct protocol.
  ⚠ **On Android the fetch is Control's, never the app's** — Calendar's manifest strips `INTERNET`
  and keeps it stripped; the `RegionDownloader` shape. On iOS route 1 every first-party process
  assembles the same proxy graph, so the refresh runs in whichever one drains the schedule
  (Calendar, Control or another Tenshu app) — still a direct fetch to the publisher, which the
  relay never sees. ⚠ No credentials and no cookies ride with it: a
  calendar feed is a public document, and one that needed a secret would be a different feature
  with a different consent conversation. ⚠ An `ETag` is sent when known, which makes the common
  case a 304 that transfers no calendar and tells the publisher *less* than a full `GET` would.
  **Not yet closed:** the fetch is direct, so there is no way to subscribe without revealing the
  IP (a relay-side fetch would fix that and would break HR#1 in the process — it would put a
  user's calendar interests on our server); and nothing bounds how often a user may add feeds.
  *Amendment (iOS damaged-store copy and export, [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md)
  §6 step 4 `CORRUPT`, Appendix B.18, Q47; behind the off-by-default SQLite switch):* when the
  route-1 shared replica is found corrupt (`replica/CORRUPT`), **Control-iOS keeps one copy of the
  damaged store aside** in `replica-damaged/<UTC timestamp>/`, in the same App-Group container,
  and offers **"Export damaged data"**: the system share sheet over that copy's files. The copy is
  the replica itself — A5 user data and A6 metadata of **every first-party app's** rows, as
  `DbEntryCodec` plaintext, decodable without key material (the C4e posture above) — so it is the
  largest single disclosure any Tenshu export makes. It is scoped like the calendar export: a
  **user-initiated hand-off to a destination the user picks**, never automatic. **Relay exposure
  unchanged (House Rule #1 preserved):** the copy is made and read on the device only, nothing
  uploads it, no code path reads it again, and no key material, sync protocol or relay data shape
  is touched. **At rest:** `replica-damaged/`, its `.partial` and every copied file are set
  explicitly to `NSFileProtectionCompleteUntilFirstUserAuthentication` and the directory to
  `NSURLIsExcludedFromBackupKey` (read back), the class §2.4 (D3) chose for the replica; a copy
  whose class or exclusion cannot be set is not made. So the copy is never *less* protected than
  the store it preserves (whose own explicit flags land with S0b-4) and is never in an OS backup
  (T-C-04). **Device-exposure delta worth recording:** for as long as the copy exists the
  replica's plaintext is on disk **twice**, so AD1 (device theft, mitigated by Data Protection)
  and AD3 (same-user malware) reach the same data in two places; the bound is one complete copy
  plus one unfinished `.partial`, and for a moment two complete copies (a newer one replaces the
  older only once it is final). ⚠ **Every app in the App Group can read the copy**, as it can read
  the replica itself: the co-owner posture of A4's Decision C, so nothing new is exposed to them.
  It is removed by the user's **"Delete the copy"** in Control (with a confirmation), by Control's
  forget once the store's marker is gone (the forget's confirmation says so first), and when the
  user deletes every Tenshu app (the container goes, D8's recovery). It is deliberately **not**
  removed after an export, which cannot be confirmed to have landed.
  **Once exported** the file is outside Tenshu's control, as any export is: it can reach a cloud,
  a mail or a Mac, and it carries every app's rows in the clear, not one calendar. That is the
  point of it — a damaged store the user cannot take off the phone is data the reinstall destroys
  — and the cover says what it holds before the share sheet opens. The share sheet excludes the
  pasteboard, which would reach the user's other devices by Universal Clipboard with no destination
  chosen. **Not yet closed:** no export-to-encrypted-archive option (⭐ decided by the maintainer
  2026-10-30, Q51: not for now).
  *Amendment (copying a calendar from the OS calendar store,
  [`calendar-external-sources.md`](../proposals/calendar-external-sources.md) CX-1):* *Copy from
  this phone* reads one calendar out of Android's calendar provider — a Google, Exchange or DAVx5
  account's events — and writes them into the ordinary encrypted store as an editable copy. The
  flow is **inbound**: nothing leaves the device and no request is made (the read is a local
  `ContentResolver` query; the network stays Control's, HR#1). Recorded anyway, because it changes
  *where the user's data may end up*: a calendar that lived in one account on one phone is now
  **replicated, decrypted, to every device in the realm** — a work calendar can land on a personal
  laptop. That is the purpose of the feature, so it is a disclosure *to the user's own devices*,
  not to a third party; the sheet says it copies "into Tenshu", and the user picks each calendar.
  ⚠ **Scope of the OS grant.** `READ_CALENDAR` is declared by **Calendar**, not Control, and asked
  for at the one button that uses it; it covers *every* calendar the provider holds, but only the
  calendar the user taps is read. No `WRITE_CALENDAR`: the copy is one-way by decision (D1), so
  Tenshu can never alter the source. ⚠ No credential is involved — the OS's own sync adapter did
  the sign-in — which is the reason the OS store comes before any direct sign-in (D2); the direct
  sign-ins (CX-3/CX-4) will hold OAuth tokens and owe their own amendment. **Not yet closed:**
  nothing distinguishes a managed (MDM) work calendar from a personal one before copying.
  *Keep in sync* (CX-1b) adds two things to this, neither of which reaches the relay in the clear:
  the bound device **re-reads the source in the background** (a Calendar-owned job, woken by
  provider changes and daily — still a local query, still no network), and a synced
  `CalendarMirror` row **names the source** — its account (often an email address) and calendar
  name — in the ordinary encrypted store, so every device in the realm learns which external
  account a mirrored calendar comes from. The relay sees only ciphertext and the usual metadata
  (HR#1). A mirror is one-way like a copy; Tenshu still holds no `WRITE_CALENDAR` and no
  credential.
  *Amendment (Drive in the Android system file dialogs, [`cross-app-file-access.md`](../proposals/cross-app-file-access.md)
  P3, D-C/D-D/D-H):* Control hosts a **`DocumentsProvider`** (`DriveDocumentsProvider`), so Tenshu
  Drive appears as a root whenever any app on the device opens the system picker ("Open from…",
  `ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT`). It is **exported but guarded by
  `MANAGE_DOCUMENTS`**, a system permission only the system picker (DocumentsUI) is granted. So the root and the folder listings — file **names**, sizes and dates, a slice of A6 — are
  disclosed to **the system picker, not to the requesting app**; the app receives a **per-URI
  grant** for the one file the user picked and nothing else — ⚠ which holds only because the root
  does **not** offer folder picks (`ACTION_OPEN_DOCUMENT_TREE`, no `FLAG_SUPPORTS_IS_CHILD`): a tree
  grant would let the app list everything under the folder. Picking is user-initiated and
  system-mediated, the same trust step as D3's *open in another app* above. **Relay exposure is
  unchanged (House Rule #1 preserved):** nothing reaches the relay beyond the ordinary content-plane
  pulls a preview already makes, no key material crosses, and the sync protocol / relay data shape
  are untouched.
  **Delta beyond D3, and the reason for this entry:** D3 hands over one file the user had already
  opened *inside Drive*; here **any app can ask**, and the plaintext of the picked file crosses to
  it — where it is outside Tenshu's control, as with any export. The bytes are served through a
  **proxy file descriptor** that decrypts by range (the streaming substrate above), so **no
  plaintext copy lands on disk** on Tenshu's side; ⚠ a file whose content has no stream yet (its
  bytes only local, not pushed) is read whole into Control's **memory** instead, up to 64 MiB, and
  refused above it. Thumbnails are the stored `LIST`/`LARGE` derivatives only. ⚠ Document ids are
  realm **paths**, so a grant the app holds follows the *path*, not the file: it breaks on a rename
  or move, and ⚠⚠ if the user later saves a **different** file at that same path, an app holding a
  persisted grant reads the new file. Accepted for v1 because picker grants are nearly always used
  at once and the master switch cuts every grant; content-bound ids would close it.
  **Mitigation — the master switch:** Control → Settings → "Let other apps open your Drive files"
  (device-local, **default on**, matching Google Drive and OneDrive — D-D). Off hides the root and
  refuses **every** call, including opens of URIs granted earlier and further reads on a
  descriptor that is already open. **Read-only** (D-H): no create,
  write, rename or delete is offered, and a write-mode open is refused. **Not yet closed:** no
  per-app allowlist (D-D rejected it as duplicating the OS's per-file enforcement, and it would need
  Track A A4's app identity), and Control's soft app-lock does not gate the provider — that lock is
  a control-plane curtain, not data-at-view protection (`control-keep-device-unlocked.md` §6.1).
  API < 26 offers no root at all.
- **A6. Metadata.** Device counts, HLCs, block sizes, block timing, owner IDs, sync
  frequency, tombstone existence (**keyed** entity id + HLC — EI-1; the id is an HMAC under a
  realm key the relay does not hold, so it names an entity without identifying it) and tombstone
  **kind = device or not** (EI-4, 2026-10-04: `targetEntityType` is `Device.ID` or the `tenshu/entity`
  sentinel, never the deleted entity's type), per-volume retention
  windows. Not end-to-end encrypted.
  *Amendment (envelope authentication, 2026-10-27):* every tombstone also carries a 32-byte
  `authTag` (an HMAC under a realm-key child the relay does not hold). It is an opaque,
  per-tombstone value and reveals nothing beyond the fields it covers, which the relay already reads.
  Deliberately **no key-generation hint** rides the envelope (D-e), so which realm-key epoch wrote a
  block stays as inferable as before (timing) and no more. A block's envelope binding adds no field. Per
  [`design.md`](design.md) §2.2 + [ADR 0007](../adr/0007-sync-device-management.md),
  device *names* and authorization status live inside encrypted `Device` entity
  blocks and are no longer relay-visible — the relay sees device counts and
  timing but not who is named what.
  *Amendment (pairing metadata, F7, 2026-10-28):* until this amendment that sentence was false on
  one path — the join-flow pairing request and the hub offer carried the joining device's name in
  clear, and the join flow carried the **raw** pairing secret (T-R-02 sided with the code, this
  asset with the design). Both are now sealed: the relay holds a joiner's name and a hub's name only
  as AEAD ciphertext under a key HKDF-derived from a pairing secret it never receives, and the join
  secret only as a domain-separated SHA-256 (T-P-16). What the pairing path still reveals is metadata
  of the kind listed above — a per-session rendezvous hash, the joiner's `deviceId` and public keys
  (which the relay registers on approval anyway), the sealed name's 64-byte length bucket, timing and
  IP — plus, for a hub, a **stable** hash that links its arms over its lifetime (T-P-17, accepted).
  *Amendment (wanted thumbnail classes, [`drive-synced-thumbnails.md`](../proposals/drive-synced-thumbnails.md)
  §15 W-1, 2026-09-09):* each device may now carry one more synced row — its
  `thumbnails.wantedClasses` `DeviceSetting`, the set of thumbnail-class ids its apps render
  (`xlarge`, `motion`, and since X-3 `xxlarge`). It is **device-capability metadata at class
  granularity**, of the same kind as the device's name and platform above, and it travels the same
  way: an ordinary `Syncable(Eager)` row, encrypted under the realm keys, so **the relay sees one
  more ciphertext block per device and House Rule #1 is preserved**; `RELAY_PROTOCOL_VERSION` is
  untouched. What it discloses, and to whom: to *realm devices* (which already hold every device
  row), a device's *kind* — a desktop with a hero surface, a phone that plays clips, a watch that
  wants a small rung — nothing the device rows do not already reveal. ⚠⚠ **What it must never
  carry, restated here so the two are not confused:** *which file* was wanted, *how often*, or
  *when*. §10.3 of the same doc rejected exactly that per-file demand signal as an access pattern
  that would have to sync between the wanting device and the byte holder, and the rejection stands;
  the row is written by the proxy at class granularity from a pull's *property id* and forgets the
  entity id it arrived with (`WantedThumbnailClasses`). A second row, `thumbnails.backfillMarker`,
  holds the hash of the wanted set the device's backfill last completed for — the same class of
  device-progress row as `BackupCursors` and the reclamation candidates. The W-2 backfill that
  reads `wanted(realm)` mints derivatives of A5 on the byte holder through the upload's own
  content-plane emit, so every blob it publishes is sealed and declared exactly as an upload's is
  (the A5 amendments above already cover the derivatives' exposure).
- **A7. Session Tokens.** Authenticate a device's connection to the relay. Not load-bearing for
  confidentiality.
- **A8. Pairing Ephemeral State.** Ephemeral keypairs, pairing secrets, SAS values. In-memory only.
  *Amendment (2026-10-02, T-P-14):* on a join-flow host this now includes the **shown-request pins** —
  per `deviceId`, the `(publicKey, signingPublicKey, deviceName, SAS)` tuple the host put in front of
  the user. They live on the `PairingContext` object itself, so they are cleared by the same
  teardown that zeroes the ephemeral private key (approve, deny, expiry) and can never be read
  through a later session's context. Public material plus a display string; never persisted.
  *Amendment (Phase L2 §7.5, [`core-background-processing.md`](../proposals/core-background-processing.md)):*
  the requester-side handshake (the stashed ephemeral keypair under a pairing
  secret) is now torn down **deterministically as part of structured
  cancellation**. The wait is a single `AwaitPairing` Layer-2 task whose
  `CancellationException` path calls `clearHandshake`, so a UI back-out (or a
  cross-process wire cancel of the `TaskHandle`) reliably burns A8 instead of
  relying on a separate best-effort `CancelPairing` op the UI might fail to
  send. The task is also bounded (~5 min, then it self-completes), so A8
  cannot outlive that window even if the requesting UI process detaches
  without cancelling. No change to the wire protocol or relay storage model
  (`AwaitPairing` carries the same parameters and hits the same peek/ack relay
  endpoints as the `GetPairingStatus` it loops). **Residual, unchanged:** the
  relay still holds the pairing *request* until its TTL — single-use
  invalidation is tracked in `sync-pairing-flow-improvements.md` §5, not
  altered here.
- **A9. Recovery Code Material.** A 24-word BIP-39 mnemonic, the user-facing form of the master
  entropy from which the master identity and shared keys are deterministically derived (Argon2id
  → master keypair). The mnemonic is persisted in the device's [`SecureStorageDelegate`] —
  Android Keystore-backed `EncryptedSharedPreferences` on Android, the platform secure-storage
  primitive on Desktop, and the WebAuthn-PRF-unwrapped IndexedDB slot on web. After
  `setUpLocalUnlock`, the plaintext is wrapped under a passphrase-derived key and the unwrapped
  form lives only in memory of an unlocked session.
  ⚠⚠ *Amendment (2026-09-15):* that last clause was violated on one path until this date.
  `SyncManagerImpl.initialize` persisted the mnemonic on its first line and derived on its
  third, so a derivation that threw — a phrase minted under the pre-swap wordlist (§11.2), or
  any future wordlist change — left the **unwrapped** mnemonic in the plaintext slot of a
  device that never unlocked, with no `OWNER_ID` beside it and `clearPlaintextPayloadSlots`
  unreached because the throw escaped the function. It now derives everything before writing
  anything, so a failure leaves the previous state intact; `InitializeAtomicityTest` pins the
  ordering. ⚠ The reachable trigger today is narrow, but the invariant is not conditional on
  BIP-39: any derivation failure used to leave A9 at rest in the clear.
  ⚠ *Second amendment, same date:* the wrap is now refused for a payload that could not be
  unwrapped again. `setUpLocalUnlock` / `setUpUnlock` / `setUpBiometricUnlock` previously sealed
  any stored payload unchecked and reported success, and the first two clear the plaintext slots
  on success — so an undecodable phrase became an unopenable wrapper, converting a readable
  problem into a lost realm. ⚠⚠ Note the consequence for this asset's at-rest state: **a refused
  wrap deliberately leaves A9 in the plaintext slot**, because that is the pre-wrap state the
  device was already in and losing the phrase is the worse outcome. It is not the violation the
  amendment above describes, where plaintext survived on a device that believed itself wrapped.
  Note on re-derivation: the master keys derive from the phrase via a one-way KDF (Argon2id);
  there is no inverse, so the phrase cannot be reconstructed from the stored keys.
  `GetRecoveryPhrase` therefore reads the persisted mnemonic rather than re-deriving it (the
  earlier draft of [`onboarding-low-friction.md`](../proposals/onboarding-low-friction.md) §7.1
  claimed re-derivation — that's been corrected to match this lifecycle).
  Server-side blob: encrypted under a key derived from the mnemonic, contents determined by the
  **recovery manifest** (A10) and changing over time as the user adjusts it.
  ⚠⚠ *Amendment (2026-10-28, F8): **the server-side blob no longer exists.*** The relay's
  `POST`/`GET /sync/recovery-blob` routes and its `recovery_blob` table are removed (the 2026-10 relay
  baseline has no such table). `GET` served the blob and its `manifestVersion` to anyone holding an
  `ownerId`, which is public because every pairing QR carries it (T-RC-08). **No client ever uploaded
  a blob**, and recovery never needed one, because A1 and A13 derive from the phrase alone (HKDF).
  ⚠ **What remains of A9 is the mnemonic itself**: the device-local secret described above. Its
  confidentiality (T-RC-01, T-RC-04, T-RC-07) and its entropy and KDF (T-RC-02, §11.2) are unchanged.
  T-RC-03, T-RC-05 and T-RC-06, which concern the server-side backup, are **dormant**: there is no
  backup to deny, retain or watch. ⚠ A10 (the manifest of opted-in volume keys) has no implementation.
  If it is built, it needs its **own** relay routes and its **own threat-model amendment**, and must
  not address the backup by `ownerId`. A phrase-derived lookup key is the obvious shape; F8 built and
  then withdrew one.
- **A10. Recovery Manifest.** The user's standing decision about which volume-wrapping keys to
  include in the server-side backup. Always includes the master identity private key; each volume
  is opt-in. Edited in the control app; every change triggers a re-encrypt-and-re-upload of A9.
- **A11. Tenshu Root Developer Identity.** Ed25519 signing keypair held by Tenshu Inc.; the trust
  anchor for Rei (per [`tenshu-rei.md`](../proposals/tenshu-rei.md) §2.1). The **public side** is
  compiled into every Control binary at build time and reaches users via TA4. The **private side**
  is held by Tenshu Inc. on HSM-backed infrastructure and never reaches user devices. Used to
  sign first-party publications, issues, and evaluations that Control treats as authentic via
  `BundledReiSource`. Full compromise = an attacker can fabricate arbitrary publications and
  chain-of-trust evaluations, defeating Rei's permission-derivation and app-attestation
  surfaces. Rotation requires a new Control binary (`tenshu-rei.md` §5.A.4); a future
  ledger-side revocation feed may allow faster propagation of "this Rei key is compromised"
  without a binary update (`tenshu-rei.md` §5.A.3).
- **A12. Shared-Realm Master Key.** Symmetric master key of a *shared realm* (per
  [`tenshu-rei.md`](../proposals/tenshu-rei.md) §2.3), wrapped under each member's realm-identity
  encryption sub-key (A13) and persisted as a `SharedRealmMembership` record on each member's
  `IdentityService`. Provides confidentiality between the share's members and the relay for the
  shared realm's block stream and content blobs. Member writes to the shared realm are signed
  under each member's **own** A1 (not A12), so authorship within the share is attributable —
  A12 is a confidentiality key only, not an authorship key. Held in memory while the member's
  realm is unlocked. Rotated on membership change. Compromise = an attacker reads + writes the
  shared realm's blob channel; cannot forge member-attributable writes (A1 still required).
  *Realised — bootstrap (SR-3b.1, `sync-multi-realm-runtime.md` §3):* a shared realm is created with a
  **random** 32-byte A12 (`SyncCryptoManager.generateSharedRealmMasterKey`, CSPRNG — a shared realm
  has no recovery phrase) plus a separate random Ed25519 realm identity used only for the realm's
  `POST /sync/realm` bootstrap (unauthenticated, via the existing endpoint — **no new relay surface**,
  House Rule #1 intact) and future device admission. The realm's symmetric data keys are
  `deriveSharedRealmKeys(A12)` = pure HKDF split (`"tenshu/shared-realm/shared-{identity,volume}/v1"`,
  **distinct** from the personal `deriveKeys` tags — no key-space collision; no Argon2id since A12 is
  already full-entropy). The creating device self-joins by wrapping A12 to its **own** A13 and saving
  a `SharedRealmMembership(OWNER)` (the only on-storage/on-wire form of A12 — House Rule #1). The
  device's single A4 now authenticates to two `ownerId` streams (personal + shared) ⇒ the relay can
  build a co-membership graph **without decrypting** — the accepted residual already tracked in
  attack-surface #10 below; per-realm device credentials are the deferred mitigation.
- **A13. Realm Identity Encryption Sub-key.** Per-realm X25519 keypair attached to the realm
  identity (per [`tenshu-rei.md`](../proposals/tenshu-rei.md) §2.3). Used to wrap shared-realm
  master keys (A12) for delivery to a member realm and to unwrap them on read. Shares A1's
  lifecycle: in-memory while the realm is unlocked, re-derivable from the recovery code (A9),
  covered by pairing and revocation together with A1. Compromise pattern matches A1's:
  unwrapping access to every shared realm the member is in.
  *Realised (crypto primitive only — no new wire/relay surface):* A13 is derived deterministically
  as `seed = HKDF-SHA256(ikm = decoded BIP-39 entropy, info = "tenshu/realm-enc-keypair-seed/v1")`
  → X25519 keypair (`SyncCryptoManager.deriveRealmEncryptionKeyPair`). The distinct HKDF `info`
  (A1's master key uses `"tenshu/master-keypair-seed/v1"`) plus the distinct curve (X25519 vs
  Ed25519) make A13 ⊥ A1 despite the shared entropy — same domain-separation argument already
  covered by T-K-04. The **A12 wrap** is an ephemeral-static X25519 sealed box
  (`wrapKeyForIdentity`/`unwrapKeyForIdentity`): fresh ephemeral X25519 → ECDH with the recipient's
  A13 public key → HKDF (`"tenshu/key-wrap/v1"`) wrap key → XChaCha20-Poly1305 AEAD, output
  `ephemeralPub(32) || aeadBlob`, AAD-binding the ephemeral public key. Recipient-binding is
  enforced by the ECDH (a wrong private key → wrong shared secret → AEAD tag failure). This wraps
  a single key blob (one A12 per member) — **once at master-key level, never per file** (§2.3) —
  and is confidentiality-only: it neither signs (member attribution stays an A1 signature, §2.3)
  nor encodes rotation. The two §2.3 invariants are **unaffected**: *single unilateral rotation
  authority* (the primitive is stateless/per-call; rotate-on-remove re-wraps a fresh A12 to the
  remaining members at a higher layer) and *fresh content key per write* (out of scope — this
  primitive operates only at master-key level, touching no content-encryption path). No new
  threat row: same curves/AEAD/KDF already covered by TA3 / T-RC-02 / T-K-04.
  *Realised — delivery to joiners (SR-3b.4 option B, `sync-multi-realm-runtime.md` §3.1):* a paired
  joiner never receives the recovery code (A9), so it cannot *derive* A13 — it is instead **delivered
  A13 in the pairing key-blob**. The blob the host encrypts under the pairing ECDH shared secret
  (A8) — previously just the shared identity key — now carries
  `sharedIdentityKey | sharedVolumeKey | A13pub | A13priv` (`PairingKeyBlob`), so the joiner ends up
  holding the *same* per-realm A13 as the host and can unwrap any A12 wrapped to it. *(1.0.1 re-cut, 2026-11-05: the blob is parsed by exactly three lengths, 64 / 128 / ≥196; any other size is refused before installation — see the re-cut sub-amendment at the top.)* **No new wire/
  relay surface** — it rides the existing `POST /sync/pairing/response` `encryptedMasterKey` field, an
  opaque blob the relay forwards without inspecting (House Rule #1); only the plaintext *inside* the
  ECDH-encrypted envelope grew. Trust surface is unchanged from delivering the symmetric `DerivedKeys`
  the same way: A13's private key is exactly as sensitive and travels the same handshake-encrypted,
  forward-secret channel. `SyncManager.getRealmEncryptionKeyPair()` resolves A13 uniformly (host:
  derive-from-phrase; joiner: the delivered key), so both device kinds participate in shared realms.
  Residual (accepted, v1): all of a user's devices share one per-realm A13, so cryptographically
  excluding a *single* device from a shared realm requires rotating A13 across all of them — no
  regression over the personal realm, which already shares `DerivedKeys` across devices; per-device
  key compartmentalization (the §3.1 option-A alternative) is the deferred mitigation if ever needed.
  *Realised — relay device admission (SR-3b.4b):* a member device's A4 is registered for the shared
  `ownerId` via a new unauthenticated, master-delegation-gated endpoint `POST /sync/admit-device`. The
  realm's OWNER signs `pairingDelegationMessage(newDevicePublicKey, newDeviceId, epoch)` (tagged + length-prefixed since B1) with the realm's Ed25519 master
  key; the relay verifies it against the realm's stored `masterPublicKey` (the same delegation shape +
  verifier as `POST /sync/pairing/response`) before registering the device in `KnownDevicesStore`. This
  is the **same master-delegation trust model** already covered by T-K-04 / device-management — the
  relay registers a public key it was authorised to register and decrypts nothing (House Rule #1
  intact); the new endpoint is just the pairing-response registration **decoupled from** the
  pairingSecret/encrypted-key handover (the admitted device already holds the realm's keys). No new
  threat row.
  *Realised — device re-registration (`ReregisterDevice`):* the relay's `KnownDevicesStore` /
  `RealmStore` are process-local (in-memory pending the Phase 4 persistence work), so a relay restart
  under a long-lived autostarted daemon drops a device's registration and every JWT-authenticated call
  401s with nothing re-asserting it (the failure that silently broke the host's pairing-request poll).
  `ReregisterDevice` re-asserts registration **without re-pairing**, reusing the **existing**
  unauthenticated-but-master-capability-gated endpoints: a master-identity holder re-bootstraps via
  `POST /sync/realm` (deterministic — same `ownerId`/`masterPublicKey`) and falls back to
  `POST /sync/admit-device` (self-admission delegation) when the realm record already exists. **No new
  relay surface, no new authority, no data-shape change** — the master self-signature / delegation is
  the same capability the original registration presented, the relay re-registers only a public key it
  re-verifies it was authorised to register, and decrypts nothing (House Rule #1 intact). A pairing
  **joiner** (no master identity) cannot self-reregister — it has only A12/A13 + a one-shot admission
  delegation — so it must re-pair or be re-admitted by the owner; this is a recovery-from-relay-loss
  affordance, not a new trust grant. No new threat row.
  *Amended 2026-08-17 — the same repair now fires **automatically**.* It previously ran only where a
  caller happened to invoke it: `HostPairingMonitor`'s failed pairing poll (and only while a host
  pairing session was live). It now also runs from the sync loop at a backoff probing boundary
  (`SyncScheduler.recoverRegistration`) and from the offer-flow approve, so a device whose
  registration the relay lost recovers without the user finding the one screen that repaired it.
  **Endpoints, capability and data shape are unchanged** — it is the same A1-gated
  `POST /sync/realm` → `POST /sync/admit-device` sequence described above, still refused to a joiner.
  Two consequences worth stating explicitly:
  (a) it is **not conditioned on why the relay returned `401`**, because the relay deliberately does
  not say (`JwtAuth` answers "unknown device" and "bad signature" identically, so the response is not
  a device-existence oracle) — the repair is instead built to be safe under that ambiguity: idempotent,
  granting no new authority, and falling back into a geometric backoff when the cause was something
  else; and
  (b) a device that was **revoked** while still holding A1 therefore re-admits itself, now without user
  action. That is not a new exposure: per **T-K-04**, a device holding the master identity is
  indistinguishable from the owner, and the remedy for a compromised A1 has always been an identity
  rotation rather than a per-device revocation. Revocation remains effective against exactly the
  devices it was ever effective against — joiners and A33-delegated admitters, whose chains the
  relay checks against the admission revocation list. No new threat row.
  *Realised — relay persistence (`docs/proposals/sync-relay-persistence.md`, Phase 1):* the
  device registry + realm store are now **durable** when `DATABASE_URL` is set (SQLite v1; in-memory
  remains the default), so a relay restart no longer drops registrations — `ReregisterDevice` above
  becomes the recovery path, not the routine one. **Zero-trust unchanged:** the only at-rest additions
  are A4 device **public** keys and the realm **master public** key + `createdAt` — public material,
  no plaintext and no key-secret (House Rule #1 intact). The SQLite file's only sensitivity is
  availability/integrity; at-rest encryption of it is defence-in-depth, not a confidentiality
  requirement (same posture as TLS). Ephemeral state (`RecoveryChallengeStore`) deliberately stays
  in-memory. The durable backend reuses the **same logical data shape** behind the storage SPI
  (`DeviceRegistry`/`RealmRegistry`) — no protocol or wire change. **Phase 2a** extends the same
  pattern to the **recovery blob (A9)** (`RecoveryBlobRegistry`/`SqliteRecoveryBlobStore`): the blob
  is **opaque ciphertext** keyed off the recovery code (T-RC-02), so persisting it stores no
  relay-readable plaintext, and the monotonic-`manifestVersion` replay guard is preserved in SQL
  (a relay can still only roll back, never forward). Persisting it closes a recovery-availability
  gap (a restart no longer drops the blob a brand-new recovering device would need). **Phase 2b**
  persists the **per-device HLC tips** (`HlcTipRegistry`/`SqliteHlcTipStore`, via `RelayBinaryCodec`):
  the tip is **signed metadata**, not plaintext, and the relay's serving rule is unchanged (whatever
  the device last claimed; regression detection stays client-side) — so the I6/§11.3 no-silent-
  censorship surface is preserved across restart rather than reset. **Phase 2b-ii** persists the
  **mailbox** (`MailboxStore`/`SqliteSyncMailbox`): the sync-block + tombstone **logs** survive
  restart (closing the gap where blocks pushed-but-not-yet-pulled were lost), stored as the same
  serialized **ciphertext envelopes** the relay already handles — it still never decrypts (House Rule
  #1). The tombstone-GC body-drop, the ack-quorum + retention rules, and the shared monotonic arrival
  cursor are unchanged in semantics (the cursor now *continues* across restart, as it must once the
  blocks it numbers persist); GC logic is shared default code over the storage SPI, so there is one
  implementation, not a re-derived SQL copy. Ephemeral pairing-handshake state stays in-memory. No
  protocol/wire change. **Phase 3a** persists the **content plane** (`ContentBlobRegistry` /
  `SqliteContentBlobStore` + a `BlobStore`): the multi-MB **opaque ciphertext** chunk bodies move to a
  filesystem `BlobStore` (one base64url-named file per `<ownerId>/<ref>`, in `<db-dir>/relay-blobs` or
  `BLOB_DIR`), with only the `last_referenced_at` index in SQLite. New at-rest surface = ciphertext on
  disk — the relay still never decrypts (House Rule #1); at-rest encryption of that directory is
  availability/defence-in-depth, not a confidentiality requirement (same posture as the DB file and
  TLS). The retention/orphan GC *mechanism* (T-R-09) is unchanged in shape — a last-reference-time
  sweep that deletes both the index row and the file — but is **disabled by default** (content is
  durable; see the T-R-09 P3 (b) 2026-07-18 amendment). Documented residual: a crash between the byte
  write and the index upsert can leave an orphan file (never an index-row-without-bytes), reclaimable
  by a future filesystem sweep — wasted disk only, never incorrect behaviour. **Phase 3b** persists
  **Mode-A shares** (`ShareRegistry`/`SqliteShareStore`): descriptors in SQLite, chunk **ciphertext**
  in the same `BlobStore`. `K_share` is still never stored (A11) — only the enforcement fields the
  relay routes on (`chunkCount`, expiry, open cap and count, revocation), the opaque `sealed_meta`
  blob (name, MIME and size under `K_share`; relay migration 3 dropped the clear `file_name`,
  `mime_type` and `total_size` columns and revoked every pre-F3 row, which could never open — §8.10, F3), plus opaque chunks; the revoked/expired/orphan
  retention GC and the open-cap atomicity are unchanged. With this, **every relay store is durable**
  behind the storage SPI; a `DATABASE_URL` relay loses nothing on restart (the 401-on-restart bug is
  closed at the root). The only remaining work is the orthogonal scale-up track (Postgres + S3
  backends) — each carries its own amendment when it lands.
  *Realised — lazy first-device registration (local-first, [ADR 0019](../adr/0019-storage-capacity-gating.md)):*
  the first-device `POST /sync/realm` bootstrap is **deferred until a storage subscription binds** — a
  realm created without one is fully local (keys derived, `Device` block saved locally, **no relay
  contact at all**), and the genesis runs on the first sync tick after `hasStorage` becomes true,
  latched once-ever via the persistent `SyncManager.realmRelayRegistered` flag. This is a **client-side
  timing change only**: the endpoint, its wire shape, the TOFU-on-master trust model, and the
  ciphertext-only relay (House Rule #1) are all unchanged; the same is true of the personal sync
  cycle and single-file upload, which are likewise gated on `hasStorage`. The net effect strictly
  **reduces** an unsubscribed user's relay exposure (an unsubscribed device is invisible to the relay
  — no realm record, no device key, no block stream) and makes second-device pairing contingent on a
  subscription (the pairing mailbox presupposes the realm record). No asset (A1–A4 / A9) change, no
  new relay surface, no new threat row.
- **A14. Desktop IPC Peer-Credential Binding.** The trust binding established at each IPC
  handshake on desktop ([`docs/protocol/desktop-ipc.md`](../protocol/desktop-ipc.md) §3,
  [`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md)).
  **Implemented and enforcing since 2026-07-28 (P3).** The binding is:
  peer **audit token** (`getsockopt(LOCAL_PEERTOKEN)`) → `SecCodeCopyGuestWithAttributes`
  (a handle on the **running** process) → `SecCodeCheckValidity` against a **code
  requirement** pinning `anchor apple generic` + `certificate leaf[subject.OU] = <Team ID>`
  + the claimed identifier. The peer's `appId` *is* the identifier that requirement
  authenticated; it is never self-declared.
  *Amended 2026-11-05 (store-signed copies):* a Mac App Store or TestFlight install is re-signed
  by Apple with Apple's own leaf certificate, so the `subject.OU` pin cannot match it. Such a peer
  is admitted instead when its running code satisfies `anchor apple generic` + the Mac App Store
  (`1.2.840.113635.100.6.1.9`) or TestFlight (`…6.1.25.1`) leaf marker + the claimed identifier,
  **and** the team identifier in its Apple-covered CodeDirectory (`kSecCodeInfoTeamIdentifier`) is
  one of the compiled-in teams for that identifier. ⚠⚠ Never the marker plus the identifier alone:
  every store app satisfies the marker, and nested-code identifiers (`tray-agent`) are not
  registered with Apple, so any store developer could claim one. Residual: this route is a validity
  check followed by a second read on the same code object rather than one atomic check. Exploiting
  the gap would take an attacker's own Apple-reviewed store binary winning a disk-swap race
  (`desktop-ipc-client-identity.md` §10). Accepted.
  ⚠ **Two earlier framings of this asset were unsafe and are superseded.** (a) *PID → binary
  path → `SecStaticCode`* is defeated by PID reuse and by TOCTOU — `SecStaticCode` inspects
  the file on disk, not the running image, so the binary can be swapped after exec. (b)
  *Reading the code-signing identifier and comparing it to a known string* is not
  authentication at all: ad-hoc signing is self-service and the identifier is a free-text
  argument, so any binary can claim `app.tenshu.drive`, be "valid on disk" and satisfy its own
  designated requirement. Only the certificate chain — anchored at Apple, issued to our team —
  is unforgeable without our signing key, so the **requirement check is the whole defense**.
  *Amended 2026-11-06 (both directions):* the binding also runs **client → server**. Before it
  writes a byte, every desktop app (`ControlServerCheck` in `:ui-core`, through
  `UnixSocketTaskTransport.serverVerifier`) attests the process on the other end of `ipc.sock` the
  same way, with a policy that vouches for one identifier, `app.tenshu.control.ios`, by the
  compiled-in teams (route 1, or route 2 for a store copy). `LOCAL_PEERTOKEN` on a connected client
  socket names the process that listened. A server that fails is closed and the connect counts as
  failed; the app shows "Control not running" and keeps retrying. Developer mode (A16) downgrades
  it to a warning, from the dev `run` task's `-Dtenshu.release=false` only, and never inside a
  packaged app (`jpackage.app-path` present), so a `JAVA_TOOL_OPTIONS` injection cannot switch it on;
  the same rule now guards Control's server-side developer mode. ⚠ Not covered: the
  Swift peers of `bridge.sock` (tray agent, File Provider), which still trust the socket path.
  The trust anchor (accepted Team IDs) is **compiled into the binary**, deliberately not read
  from a property, environment variable or config file at runtime: the very adversary this
  asset defends against (AD3, a same-user process) could otherwise add its own team.
  On **Windows** the anchor is the set of **Store publisher ids** our MSIX packages carry
  (`FIRST_PARTY_WINDOWS_PUBLISHER_IDS`, compiled in by the same rule), and the attested identity is
  the package name of the process holding the socket — see T-C-11's Windows paragraph.
  Compromise (subverted `Security.framework` verification, a stolen signing key, or a
  developer-mode A16 flip) defeats per-process authentication on the IPC channel.
- **A15. Desktop Master-Key At-Rest Storage.** Container that persists A1 across Control
  sessions on desktop. Choice depends on platform + environment per
  [`tenshu-rei.md`](../proposals/tenshu-rei.md) §3.6:
  - **macOS:** native Keychain (TA2). Required — Control refuses to start without it.
  - **Windows:** native DPAPI (TA2), user scope. Required. ⚠ As built (2026-11-10,
    `DpapiWrappingKeyStore`): DPAPI seals A21's wrapping key into `wrap-key.dpapi`; there is no
    Windows biometric gate yet, so this is the baseline of T-C-17 only.
    ⚠ "Required" is not yet enforced: a Windows Control without DPAPI falls back to in-memory
    secrets like every other desktop, rather than refusing to start.
  - **Linux + Secret Service:** gnome-keyring / KWallet via dbus (TA2). Preferred.
  - **Linux without Secret Service:** Argon2id-derived encrypted file on disk, two
    user-selected modes (`tenshu-rei.md` §5.F.50):
    - *Passphrase mode (default):* file-encryption key derives from a user-chosen passphrase
      via Argon2id (OWASP-recommended parameters); passphrase exists only in user memory and
      the transient input field, derived key in memory while Control runs.
    - *Machine-bound mode (opt-in):* file-encryption key derives from a per-install machine
      identifier (precise source open per §5.F.50); trades offline-disk-read resistance for
      zero-prompt convenience. User opts in explicitly after a prominent warning.
  Compromise depends on the variant — see T-C-13.
- **A16. Developer-Mode Toggle State.** Per-install boolean in Control's local config on
  desktop (per [`tenshu-rei.md`](../proposals/tenshu-rei.md) §3.6 + §5.F.49) that, while
  active, downgrades the IPC peer-credential check (A14) from hard-fail to warning so
  unsigned development builds can connect. Stored alongside other Control config; protection
  class matches A15's (no special at-rest protection beyond what the OS gives). Production
  builds hide the toggle behind an Android-style seven-tap reveal on Control's build-number
  row; debug builds may expose it directly. A persistent status-bar indicator surfaces
  while active. Compromise (toggle silently flipped) = any same-user process can connect to
  Control's IPC as if it were a known Tenshu app.
- **A17. Per-Share Key + Capability URL.** A 32-byte symmetric `K_share` generated per Mode-A
  link share (per [`docs/file-sharing.md`](../file-sharing.md) §1, rationale in
  [ADR 0018](../adr/0018-file-sharing-mode-a.md)), embedded
  in the URL fragment. The URL — `https://drive.tenshu.app/s/{shareId}#k={K_share}` — *is* the
  read capability for the shared file: knowledge of it is sufficient to decrypt; knowledge of
  only `shareId` (everything before the `#`) is not. Lifecycle: generated client-side at share
  creation (CSPRNG), **never sent to the relay** (only the share's ciphertext chunks, its sealed
  metadata blob — name, MIME type and size AEAD-sealed under `K_share`, §8.10 — and the opaque
  `shareId` reach it), invalidated by `RevokeShareLink` (which Eager-flips `Share.revoked` and
  issues `DELETE /share/{shareId}`, scheduling relay-side chunk GC). Distinct from A2 (volume
  keys) and A12 (shared-realm master key): A17 is a single-file, single-share read capability
  with no authorship dimension. Compromise (anyone who learns the URL) = read access to that one
  shared file until revocation — see T-S-01.
- **A18. App Registration Set.** The realm-wide set of `AppRegistration` records (one per
  `(appId, deviceId)`) plus their realm-level `AppGrant` permission records, per
  [`control-app-registration.md`](../proposals/control-app-registration.md). This is realm
  *metadata* describing which apps are installed on which devices and what each has been
  granted — not key material (an app never holds key material; §1.3 of that doc). It is
  `Syncable`, so it replicates as ciphertext blocks like any other entity (House Rule #1
  holds: the relay learns nothing beyond block existence). Written by the **proxy** at
  handshake time (`RegisterApp`), never by the connecting app — see T-C-14. Removal
  (`RemoveApp`) is a `state = Removed` tombstone whose cross-device propagation rides the
  same revocation machinery as I4 and inherits the §9 HLC-consistency carve-out; until the
  tombstone propagates, a lagging device may still admit a removed app, but the residual is
  *operation access on one lagging device*, not data at rest (no key material is involved).
  The `verified = false` badge keeps A16's elevated dev-mode trust posture visible per-app.

- **A19. App Console Upload Payload.** The bytes the user's device POSTs to the future App
  Console's `/reports` endpoint when they tap "Send error report" on Settings → Errors
  (`docs/proposals/core-error-channel.md` §4.3, threats T-L-01/T-L-03). Anonymous by
  construction: the body is `{ uploadId, events[] }` where `uploadId` is a freshly minted
  UUID per upload (not persisted, not reused) and each `event` is the **redacted view** of
  the local `ErrorEvent` — `deviceId` is dropped (identifying across uploads) and
  `internalDetail` is dropped (the only freetext field in the system; proxy-local
  diagnostic text). The remaining fields are typed identifiers (`errorTypeId`,
  `operationId`) or coarse platform metadata (`appId`, `appVersion`, `osLabel`,
  `callstackDepth`, `capturedAt`). Always user-initiated — no auto-send, no background
  upload, no "anonymous statistics" toggle that defaults on. TLS in transit (same posture
  as the sync relay); no JWT (attaching per-user authentication defeats the anonymity goal).
  The Console operator may rate-limit by IP / payload shape; that's a Console-side concern.

- **A20. macOS Native Bridge Surface + Group-Container Sockets.** The daemon's *second*
  IPC surface on desktop: a small, private JSON-over-UDS protocol (`NativeBridgeServer`,
  [`desktop-ipc.md`](../protocol/desktop-ipc.md) §6.2) on a separate `bridge.sock`, alongside
  the binary `ipc.sock` (the §3 transport, A14). It exists so the macOS native clients — the
  Control **tray agent** and the Drive **File Provider extension** (`.appex`) — can stay thin
  Swift processes instead of reimplementing `BinarySerializer`. Its handlers translate JSON
  calls into real `ProxyGateway` / file-service operations JVM-side. Surface today:
  - *tray methods* (metadata only): `getSyncState`, `recentNotifications`, `listApps`,
    `openControlWindow` / `quitApp`;
  - *File Provider methods* (**plaintext file payloads**): `listFolder` / `itemMetadata` /
    `readContents` and `createFolder` / `createFile` / `modifyContents` / `moveItem` /
    `deleteItem`. `readContents` returns **decrypted** bytes; `createFile` / `modifyContents`
    accept plaintext bytes (base64 in the JSON envelope). The daemon does the
    encryption/decryption behind the bridge, so a connected client reads and writes the user's
    Drive files in the clear.

  On a signed macOS install both sockets live in the App Group container
  `~/Library/Group Containers/<TEAMID>.app.tenshu.shared/` so the **sandboxed** native clients
  can reach them. The container is **reachability, not a security boundary** (`desktop-ipc.md`
  §6.1, proposal §2.2): a same-user process can reach it too. The gate is therefore the same
  **A14 handshake** as the binary transport — and, as for A14, **that handshake is enforcing on
  desktop as of 2026-07-28**
  ([`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) P3): both
  `NativeBridgeServer` and the binary `UnixSocketServer` run one shared `PeerCredentialPolicy`
  chokepoint on `accept()` (`core-common` `transport/PeerCredentialGate.kt`), configured
  `strict = true` behind a real verifier (`MacPeerAttestation`, `:macos-helpers`), so a peer whose
  *running code* does not satisfy a Team-ID-pinned requirement is rejected and its channel closed
  before a frame is served. ⚠ The gate admitted every peer through Phases 2–3 — that window is what
  T-C-15 treats, and this paragraph described it in the present tense until 2026-08-21. Three
  carve-outs survive, all narrow: **(a)** developer mode (A16) downgrades a rejection to
  admit-with-warning, but is sourced from a build-type flag the packaged app never passes;
  **(b)** the verifier is **macOS-only** — nothing resolves a peer on Linux/Windows, so a packaged
  build there would admit no one and a dev run admits everyone (shipping is macOS-only today, so
  this is a dev-posture rather than a shipped exposure); **(c)** an admitted peer's *identity* is
  not used for per-method authorization inside the bridge, so **any** admitted first-party bundle
  reaches the File Provider methods, not only the tray agent and the `.appex` (`tech-debt.md`, the
  2026-08-21 IPC audit). The `com.apple.security.application-groups` entitlement remains defence in
  depth — it constrains *which signed bundles* may reach the container, but not an arbitrary
  non-sandboxed same-user process. **Relay exposure is unchanged**: every bridge method runs the
  same proxy/file-service path as the in-app client, so the relay still sees only
  ciphertext blocks (I1, House Rule #1). The File Provider extension is additionally a local
  component that **transiently holds plaintext** — on open it materialises decrypted bytes to a
  temp file in its sandbox container for Finder (I8; T-C-16).

- **A21. Desktop At-Rest Master-Key Store.** The desktop secret bucket persisted **encrypted at
  rest** (`DesktopMasterKeyStore`, `docs/proposals/desktop-masterkeystore.md` §3): the master-key
  secrets `SyncManagerImpl` writes via `storeSecret` — `RECOVERY_PHRASE` (A9), `UNLOCK_WRAPPER` /
  `BIOMETRIC_WRAPPER`, `OWNER_ID`, `JOINER_PAYLOAD`, and derived volume/identity key material
  (A1/A2) — held in an `AES-256-GCM` blob (`<data root>/<pkg>/secrets.enc`: the App Group
  container on a signed install since 2026-10-04, `~/.tenshu` on an unsigned dev run) whose 32-byte wrapping
  key lives in the **macOS login Keychain** (a generic-password item). This **changes the desktop
  at-rest exposure**: previously these secrets were in-memory-only and lost on restart (the device
  re-paired); now A1/A2/A9-derived material persists as ciphertext on disk so the device resumes
  its realm. On **Windows** (since 2026-11-10) the wrapping key is instead sealed with **DPAPI, user
  scope**, in `wrap-key.dpapi` beside `secrets.enc` (`DpapiWrappingKeyStore`; `WindowsDpapi` in
  `:windows-helpers`) — DPAPI keeps no store of its own, so the sealed key is a file. Where no OS
  credential store is wired (Linux today, DPAPI unavailable, or `security` unavailable) the store
  falls back to in-memory and the old re-pair-on-restart behaviour. The wrapping key is
  the same indirection the biometric path reuses (A1/A2 wrappers, Phase 2). **Relay exposure is
  unchanged** — purely device-local at rest (House Rule #1 unaffected). The Android twin is
  `EncryptedSharedPreferences` (AndroidKeyStore-backed). Threats: T-C-17.

- **A32. iOS At-Rest Master-Key Store.** The iOS analog of A21 — the Control-on-iOS realm-device
  secret bucket persisted **encrypted at rest** (`IosSecureStorageDelegate`,
  `docs/proposals/ios-control-handover.md` C1). Control-on-iOS is a full, key-holding realm device
  (§3 / §11.1 of `ios-proxy-architecture.md`), so the same `storeSecret` material as A21 —
  `RECOVERY_PHRASE` (A9), `UNLOCK_WRAPPER` / `BIOMETRIC_WRAPPER`, `OWNER_ID`, `JOINER_PAYLOAD`, and
  derived volume/identity key material (A1/A2) — is AEAD-sealed (libsodium **XChaCha20-Poly1305**,
  the cross-platform `AeadBackend`) under a single 32-byte **wrap key** held as an iOS **Keychain**
  generic-password item, and the sealed blobs are themselves Keychain items. All items are
  `…ThisDeviceOnly`, so they are excluded from iCloud Keychain and device-to-device transfer (a
  foreign restore can't carry decryptable blobs). The wrap key has two protection modes: a
  non-prompting **baseline** (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) and a
  **Secure-Enclave / `SecAccessControl` gate** (`kSecAccessControlUserPresence` over
  `…WhenPasscodeSetThisDeviceOnly`) that presents Face ID / Touch ID / passcode on first read,
  unwrapped-once-cached-per-process. Unlike desktop's Swift `KeychainHelper`, the gate is driven
  **in-process** from Kotlin/Native (`platform.LocalAuthentication` via the Keychain ACL — no helper
  binary). **Relay exposure is unchanged** — purely device-local at rest (House Rule #1 unaffected).
  The passphrase floor (`UNLOCK_WRAPPER`, Argon2id) remains cross-platform, so the biometric is never
  the sole credential. C1 ships the store + the gate capability. **Returning-user biometric unlock is
  now wired** via the cross-platform `BiometricUnlockDelegate` seam (`IosBiometricUnlockDelegate`),
  the iOS sibling of the Android/desktop delegates: enrollment (Settings → "Unlock with Face ID")
  mints a random 32-byte wrap-key, biometric-protects it in a **second** user-presence-gated Keychain
  item (`kSecAccessControlUserPresence` over `…WhenPasscodeSetThisDeviceOnly`, service
  `app.tenshu.control.ios.biometric-unlock`), and only then wraps the realm secret under it via
  `SyncManager.setUpBiometricUnlock` (`BIOMETRIC_WRAPPER`); the returning-user unlock screen then
  reads it back behind Face ID / Touch ID (`unlockBiometric`) instead of the passphrase. Same
  device-local, `…ThisDeviceOnly`, passphrase-floor properties as the A32 baseline — no relay/wire
  change (House Rule #1 unaffected); a later biometric-enrollment change does not invalidate it
  (userPresence, not `biometryCurrentSet`). Threats: T-C-18.

- **A24. Contact Graph & Identity Keyring.** `Person` / `TenshuIdentity` / `ContactInvite`
  (later `ContactSource`) rows including per-key verification state — personal-realm
  Syncables, ciphertext to the relay like any entity (House Rule #1 holds). The
  trust-relevant component is `TenshuIdentity.verification`: it is never auto-elevated,
  never transferred by merge/link (contact doc §6), and the UI checkmark is derived from it
  alone. Compromise (a forged `VERIFIED` row written by a realm device) requires a
  realm-device compromise, which is game-over for worse reasons. Threats: §8.13.
  *Amendment (2026-10-31, contacts v2, [`contacts-app.md`](../proposals/contacts-app.md) §9.10):*
  a `ContactSource` card's `PHOTO` lines over 64 KiB now ride the **encrypted content plane**
  (CEK-sealed AEAD chunks addressed by a random per-relocation `contentId`, ADR 0017) instead of
  the row's own block, the card keeping a placeholder. **No trust-boundary change:** the photo was
  relay ciphertext before and is relay ciphertext now (House Rule #1). The relay learns one thing
  it did not: that a content object belongs to a contact row (its edge) and its size class — the
  same size it already saw as an outsized entity block. Deletion is now propagated: `DeletePerson`
  tombstones the rows it deletes, which also retires the photos' edge so they are reclaimed.
  *Amendment (post-handshake re-verification, [`contacts-app.md`](../proposals/contacts-app.md)
  Ph5 / D4 / Q2a):* `TenshuIdentity.verification` becomes reachable **after** the handshake
  window, through two new `:contact-service` ops. `GetContactSas(fingerprint)` derives the
  §5.3 six-digit code for a stored key; `VerifyTenshuIdentity(fingerprint, verified)` records
  the user's verdict, moving `UNVERIFIED ↔ VERIFIED` and **refusing to touch `ATTESTED`** in
  either direction (that rung is Rei-carried, v3, and outranks a device-local human
  judgement). **The A24 invariant is unchanged and is what the design turns on: verification
  is still never auto-elevated.** `verified = true` is only ever the record of a person
  answering *"did the digits match?"* — there is no cryptographic check at this boundary,
  because the check is the out-of-band comparison itself. **Nothing new is exposed.**
  `deriveContactSas` is over the two identity **public** keys, so the code is computable by
  anyone holding both and is not a secret (§5.3); returning it to a realm app reveals nothing
  a realm device did not already hold, and the derivation is done proxy-side only because the
  app has no view of its own A13. **No relay, wire or protocol delta:** re-verification needs
  no exchange at all — the derivation is session-independent, so each side computes the same
  digits locally and each records its own verdict on its own row, which then replicates as an
  ordinary personal-realm Syncable (House Rule #1 holds). The reachable-state change is that
  a *realm-device* compromise can now forge a `VERIFIED` row at any time rather than only
  during a live handshake — unchanged in kind (it was always writable by a realm device) and
  bounded by the same "realm-device compromise is game-over for worse reasons" argument.
  Threats: §8.13 (T-CI-09b).
  *Amendment (synced dismissal, [`control-central-notifications.md`](../proposals/control-central-notifications.md) Phase 4):*
  Control's central-notifications surface records a **`DismissedRequest`** `Syncable(Eager)`
  row when the user marks an inbound actionable request seen, so the dismissal clears the
  unread badge + pinned card on **every** paired device (the synced-dismissal point of
  Phase 4). It carries a **single field** — the request's content-derived id, either
  `grant:<grantorFingerprint>` (an A27 grant) or `invite:<inviteId>` (an A25 invite) — and
  nothing else (no claimed name, no SAS, no timestamp). It is a personal-realm Syncable,
  **ciphertext to the relay like any entity (House Rule #1 holds)**; no change to the
  relay's data shape or the sync protocol (it is an ordinary new entity type that replicates
  in `SyncBlock`s, keyed by `requestId` so concurrent dismissals on two devices union-merge
  rather than clobber). **Device-exposure delta: nil-new.** The fingerprint/inviteId in the
  marker is already held at rest on that device inside the very `PendingGrant` (A27) /
  `ContactInvite` (A24/A25) row the dismissal refers to — a compromised device learns
  *which already-known requests the user chose not to act on yet*, no new identifier. Copy
  rendered to OS notifications stays generic/kind-only as before (§6). Threats: §8.13.

- **A25. Invitation Capability & Handshake State.** Per invite: the link triple
  `(inviteId, K_link, inviter A13 pub)` riding an outside channel; the inviter-side
  `ContactInvite` (stores `SHA-256(K_link)` only — the raw capability is never persisted
  inviter-side); the invitee-side `PendingRedemption` (raw `K_link` at rest in
  `SecureStorageDelegate` for ≤ 72 h — the deliberate deviation from A8's in-memory-only
  posture, justified by the asynchronous window and bounded by the terminal-state
  clearing in the protocol doc §7.2); relay-side `(inviteId → ownerId, expiry, state)` +
  sealed mailbox blobs. One-time, 72-hour default TTL, revocable before redemption.
  The relay never holds `K_link` in any form. Threats: §8.13.

- **A26. Member Authority Keypair.** A fresh Ed25519 keypair minted **per (member, shared
  realm)** (`shared-realm-membership-grant.md` D2). Its *private* key is
  personal-realm state (a `MemberAuthorityKey` Syncable row, at rest like A24 — ciphertext to
  the relay) and signs the member's per-realm device bindings; its *public* key is what the
  realm-master capability (A27) vouches for, and is registered relay-side as a foreign-realm
  device admitter. Realm-local **by construction**: the relay cannot correlate it with the
  member's personal `ownerId` (the Q30 per-realm-credential lever), so compromise stays scoped
  to the one realm and **does not yield Kr** — it admits attacker *devices* to that realm until
  revoked (a write-capable-member compromise, not a key disclosure). Threats: §8.14.

  **As-built custody (GR-3, `shared-realm-membership-grant.md` §11.1):** the **owner** mints
  A26 at *grant* time (the landed pre-signed capability requires it) and ships the private key
  inside the grant payload (A27 — sealed to the grantee's A13, so the relay/inbox never see
  it); the owner discards the private half after sealing, keeping only the public key to
  revoke. The owner therefore transiently holds A26-private — **not an escalation**: holding
  the realm master (A1), the owner can already admit any device to *its own* realm, so a
  realm-scoped A26 grants it nothing it lacks. All A26 properties above (freshness,
  realm-locality, relay-unlinkability, one-key revocation) are preserved; only D2's "the
  member mints" sequencing changed.

  **Durable owner roster (GR-6, `shared-realm-membership-grant.md` §12):** since the owner
  discards A26-private but needs the A26 *public* key to revoke later, it persists that public
  key (only) in a `GrantedMembership` Syncable row in its **own personal realm**, keyed by the
  in-realm `memberId`. Master-signed-capability material, never a secret — syncing it to the
  owner's devices is House-Rule-#1-safe and lets *any* owner device remove a member.

- **A27. Membership Capability & Grant Payload.** Per grant: the realm-master-signed
  **capability** `(realmId ‖ memberAuthorityPub ‖ epoch ‖ issuedAt)` binding A26 to the realm;
  and the **sealed grant blob** `{grantId, realmId, wrappedKr, capability, label,
  grantorFingerprint}` riding the `/sync/grant/{address}` mailbox. The blob carries the realm
  key (Kr/A12) **wrapped to the grantee's A13**, so a relay/mailbox compromise yields nothing
  without the grantee's A13 private key; the mailbox address is an HKDF of the contact
  handshake's shared secret (`deriveGrantAddress`), derivable only by grantor + grantee, never a
  public identifier. Single-pickup + 30-day TTL. The matching revocation
  `(realmId ‖ memberAuthorityPub ‖ epoch ‖ revokedAt)`, also realm-master-signed, lives in the
  relay's append-only per-realm revocation list. The relay never holds Kr or the grant address's
  derivation inputs. Threats: §8.14.

  **Grantee-side quarantine store (GR-6, `shared-realm-membership-grant.md` §12):** the inbound
  poll (`pollPendingGrants`) picks the sealed blob off the single-pickup mailbox and stores it
  **still sealed** in a `PendingGrant` Syncable row in the grantee's own personal realm (D4
  quarantine — the user accepts/declines explicitly). The stored blob is the same A27 ciphertext
  the relay held, sealed to the grantee's A13; persisting it in personal-realm state is the same
  posture as A26-private storage (`MemberAuthorityKey`). Accept consumes + tombstones it; decline
  tombstones it. No relay/protocol change — the poll is the existing anonymous mailbox `GET`.

- **A1 durable owner custody of the shared-realm master (GR-6).** Before GR-6 a shared realm's
  Ed25519 master keypair (A1 for that realm) lived only in the in-memory `RealmContext` the
  creating session built — so the owner could grant/remove members only that session. GR-6
  persists it as an `OwnedRealmMaster` Syncable row in the owner's **own personal realm**
  (private key included, at rest like the personal A1/A24 — ciphertext to the relay). It syncs to
  the owner's paired devices, so any of them can grant/remove. No escalation: every owner device
  is, by identity, the realm owner; the relay never sees it in any form. Threats: §8.14 (T-GR-03
  forgery still requires this key).

- **A28. Commerce identity & payment set.** Billing identity, contact email, processor customer
  ID, subscription/entitlement state, statutory invoices — real PII, the **first** in the system.
  Lives **only** in the commerce plane (`:commerce-service`), never in the data plane: never on
  the relay, never in a realm, never on the wire to either (House Rule #1; commerce↔relay D1).
  It began as a whitelist bootstrap (`commerce-billing-plane.md` §8 Phase 1) but is now a
  **persisted PII store** (`commerce-accounts-and-sessions.md`): a durable Postgres holding accounts
  (email, `stripe_customer_id`, rail), the re-bindable `account↔ownerId` link (A30), the entitlement
  ledger, email-OTP + session rows (both stored **hashed**), versioned ToS/consent acceptances, and —
  with the Stripe rail (Phase 4) — **statutory invoices**. Retention splits (§5.2): the operational
  tables are erasable (GDPR), while `invoice`/`acceptance` are append-only (~10-year, survive account
  erasure, WORM-archived) under the legal-obligation basis. At-rest encryption + tight access control
  + audited backups are deployment obligations on this now-crown-jewel store; it stays on the commerce
  host, never the relay (D1).
  *Amendment (2026-10-23, self-service account deletion — `commerce-accounts-and-sessions.md` §9.7):*
  erasure is now a code path, not a manual process. `eraseAccount` closes the account and **removes
  the person** from the operational store — the email is replaced by an `erased:<id>` tombstone (no
  `@`, so no lookup by address can reach it), `email_verified_at` / `display_name` are cleared, and
  every session, pending OTP and marketing-consent row is deleted. ⚠ The account **row stays,
  pseudonymous**: the closure sweep, the subscription-cancellation retry and the statutory
  `invoice`/`credit_note`/`acceptance` rows all key on its `id`, and it keeps `stripe_customer_id` /
  `iap_original_txn_id` for those sweeps and for attributing a late chargeback. What survives
  therefore names no person *here* — but the processor (Stripe/Google) still holds the billing
  identity under that customer reference, and `acceptance.ip` survives as statutory evidence.
  ⚠ After a re-signup from the same realm the erased row is **linkable** to the new account: both
  carry the same `account_owner.owner_id`, so whoever reads the commerce store can join "the erased
  account" to "the person who came back".
  *Amendment (2026-10-24, store-review sign-in — `commerce-accounts-and-sessions.md` D13 / §9.8):*
  A28 now carries **static credentials**: per unpublished review account (one per store), the HMAC
  under a box-side key of its own fixed 6-digit code, which signs in and confirms deletion for that
  account only; plus persisted failure logs — the review lockout and, for every address, the daily
  cap on failed code checks (keyed by the address's SHA-256, deleted on erasure) — and a fingerprint
  row that ends review sessions when a code rotates. Off unless configured; on while configured.
  Threats: §8.15 T-CB-11, T-CB-12, T-CB-13.
- **A29. Commerce entitlement signing keypair.** Ed25519. Signs the entitlement **token** (the
  relay-verified bearer credential) and the synced `Entitlement` **record** (the on-device
  verify-on-merge path). The **private** seed is a commerce secret — `:commerce-service`'s
  `ISSUER_SEED`, never on a device or the relay (custody = the deferred KMS/HSM item; v1 = an
  env-supplied seed, with a committed *dev* seed for local runs, insecure by design). The relay
  holds only the **public** key (`ENTITLEMENT_ISSUER_PUBKEY`); devices pin it
  (`EntitlementSignature.COMMERCE_PUBLIC_KEY`). Compromise = forged entitlements — a **billing/DoS**
  problem, **not** a confidentiality break: a forged token still decrypts nothing (it only asserts
  a tier the relay would honour for *writes*). Rotation is `keyId`-tagged (the token `kid`); the
  relay holds current + previous public keys during a rollover.
  ⚠ **A build may carry one more issuer (2026-10-12, `entitlement-trust-anchor-distribution.md`
  D3a).** `TENSHU_EXTRA_ISSUER=<publisherId>:<public key>` at *compile* time adds a single issuer
  to a client's trust set, for a non-production plane whose commerce signs with its own seed (the
  dev lab, `ops-dev-lab.md`). Three properties bound it: it is **additive under a new id only** —
  the built-in ids resolve first and the build refuses them, so it cannot replace or widen A29's
  production anchor; it is **never in force in a release binary** (nor in a web bundle unless that
  bundle's own build was handed it — a line left in `local.properties` or a `gradle.properties` does not count); and it changes nothing the
  relay accepts, which still verifies against its own configured key. The residual is a debug
  build that trusts a test plane's issuer for the `hasStorage` gate and for display — on a plane
  that issuer already controls.
- **A30. The account↔`ownerId` link** *(minimal variant only)*. The join that I-CB-1 (strong)
  eliminates. The v1 **minimal** variant binds the entitlement token to a relay `ownerId`, so the
  commerce plane (and the token itself) carries an `account → ownerId` mapping. Consequence:
  anyone joining *both* planes (a breach of both, or a subpoena reaching both) can walk person →
  realm → ciphertext metadata. The **strong** variant (blinded tokens, decided Q1: blind RSA)
  removes this asset entirely. Documented de-anonymisation vector for as long as the minimal
  variant ships (see I-CB-1, T-CB-04). The token format is **blinding-ready** (`ownerId` is an
  optional claim; the verifier never requires it) so the upgrade is a token-issuance change, not a
  relay-verifier rewrite.
- **A31. EPC rendezvous channel state & pairing capability.** Per app↔proxy pairing: the
  out-of-band pairing seed (shown by the proxy as QR/link, scanned by the app — **never
  relay-visible, not even hashed**, like the device-pairing QR and `K_link`), the
  HKDF-derived `/sync/epc` mailbox session id it seeds (`deriveEpcRendezvousSessionId`), and the
  relay-side mailbox contents (opaque sealed `EpcSession` frames + `(sessionId, direction, seq,
  expiry, owner-binding)` metadata). The derived X25519 session keys are ephemeral, in-memory
  channel state (asset **A8** class). The pairing seed grants *channel rendezvous*, not data
  access — what the app then sees is whatever the proxy chooses to expose through the channel,
  mediated by Rei (measure 3, iOS Phase 5). Custody: the seed lives only in the proxy's pairing UI
  and the app after scanning; the session keys only in each end's memory for the channel's life.
- **A33. Device-Admission Capability (keypair + cert chain).** Per realm device: a fresh Ed25519
  **admission keypair** (the personal-realm analogue of A26) plus a **capability-cert chain**
  rooting its authority in the realm master key (A1) — each link
  `LP("tenshu-admit-cap-v2") ‖ LP(ownerId) ‖ LP(deviceA33Pub) ‖ LP(deviceId) ‖ LP(mintedAtEpoch)`
  (length-prefixed, §7d), signed by the
  parent's A33 (or by A1 for the root link). Holding A33 + chain lets a device **admit new
  devices** (sign pairing-response delegations) and — chained minting — create further admitters,
  which is the resilience property: losing the realm requires losing *every* device *and* the
  recovery phrase, not the first device and the phrase. Deliberately **not** A1: a stolen device
  yields a *revocable* minting capability (the `/sync/admission/revoke` ledger; every chain
  through a revoked cert dies with it), never the master key or the recovery seed
  (`delegated-device-admission-authority.md` §2, the Option-A/Option-B decision). Custody: the
  private key is held like A4 (in memory + wrapped at rest); the cert chain is public,
  relay-visible material the relay verifies but cannot mint. Delivered to a joiner inside the
  ECDH-encrypted pairing key-blob (v2 fields), which the relay forwards but cannot read — or,
  for the P4 migration/re-root path, minted *locally* by the device itself with only the public
  key announced (A4-signed `AdmissionKeyAnnouncement`) and only the public cert chain granted
  back (`AdmissionCertGrant`): the private half never leaves the device on either path.
- **A34. Health & fitness data (GDPR Article 9 special-category).** The Tenshu Fit app's health
  record set — `:health-service`'s `Workout` / `DailyActivity` / `SleepSession` / `HealthSample`
  (heart rate, weight, SpO₂, respiratory rate) / `MedicalRecord` (medications, immunisations,
  allergies, conditions), and — deferred but designed — GPS routes and per-second HR streams.
  This is **GDPR Article 9 special-category** data and among the most sensitive on the platform:
  routes reveal home/work/movement patterns, medical records reveal diagnoses. **Custody is the
  ordinary sealed sync plane** — these are feature-service entities keyed and encrypted exactly
  like file metadata (A2/A3 volume/epoch keys), and the dense payloads (routes, HR streams) ride
  the **encrypted content plane** like file bytes (ADR 0017), so **no health field is ever
  relay-visible** and House Rule #1 is unchanged: the relay sees ciphertext blocks and a device
  identity (A4), never a step count or a diagnosis. **Acquisition** is the new surface: the
  `HealthImporter` seam (`timeline-services.md` §4.2) reads from platform sources — Health Connect
  (Android) / HealthKit (iOS) under their explicit per-permission consent grants, or an explicit
  FIT/TCX/GPX file pick — and maps into the neutral schema in `commonMain`; an importer must
  acquire only under that explicit user consent and must never route a sensitive field into a
  relay-visible metadata position. **Platform constraint:** HealthKit-derived data must not be
  used for advertising or AI/ML (Apple ToS); mirror as a hard product rule. **Residual:** health
  data at rest on-device shares the platform secret/keystore custody of the realm keys (A21/A32);
  a device compromise that already yields the realm keys also yields the health data — no new
  at-rest trust boundary beyond the existing per-app store. **Custody as-built (2026-07-13):** the
  sealed-plane custody described above is now what the code actually does. Until this date the Fit
  app did *not* store health data in the sync plane at all — it hosted its own in-process
  `:health-service` over an **in-memory** store, so health rows lived only in Fit's process (never
  written to disk, never synced, and silently lost at process death; see `fit-app.md` §10). Fit is
  now a **client of the Control proxy** like Drive: health entities are read and written over IPC,
  land in Control's durable store, and sync as ordinary feature-service entities under A2/A3 — no
  relay-visible field, House Rule #1 unchanged, no new relay surface. The at-rest residual above is
  therefore now the *real* one: health data shares Control's store and the realm-key custody
  (A21/A32), rather than being confined to a volatile per-app store. *Status:* the schema entities
  (`:health-service`) + the import **seam, writer, and reference importer** have landed (Fit P5.1),
  the **Android `HealthConnectImporter`** — the first real acquisition path — has landed under
  Health Connect's per-permission consent (Fit P5.2, on-device verified), and the **Android recorder**
  (GPS / BLE HR / step counter) persists routes + HR streams via `Workout.Streams`. The iOS HealthKit
  importer and desktop/web file import are later slices. **Streams custody as-built (2026-07-23,
  read-scaling option B):** `Workout.Streams` now *actually* rides the encrypted content plane as
  this entry always described — a `dynamicProperty(Syncable(OnDemand))` emitted as CEK-sealed AEAD
  chunks off the external `FileDatabase` (`/.health/streams/{workoutId}`), fetched lazily by
  receivers. Until this date it in fact rode the *eager entity block* (sealed under A2/A3 all the
  same — no relay-visible field either way, House Rule #1 unchanged in both directions); the move
  changes scaling and receiver-side residency, not the trust boundary. **Durable recorder outbox (2026-07-18,
  `fit-app.md` §9.13):** a finished recording is journalled as plain JSON — route + HR included — in
  **app-private storage** (Android `filesDir/workout_outbox/`; the desktop app-data dir) until its
  store write is confirmed, so a save against an unreachable Control survives process death instead
  of being lost. Local-only, never a wire format, deleted on confirmed write; at rest it shares the
  same per-app custody residual as the store above (a device compromise that yields the store also
  yields the journal). Mirrors Timeline's visit outbox posture (A35). **In-flight capture journal
  (2026-07-25, `fit-app.md` §13.5):** the recorder (Android **and iOS**) additionally journals the
  *in-progress* session — route fixes + HR samples as plain lines in the same app-private storage
  (Android `filesDir/recording.journal`, appended; iOS Application Support, atomically-replaced
  snapshots; flushed ~every 15 s) — so a process death mid-workout recovers as a reviewable draft
  instead of losing the recording. Same posture exactly: local-only, never a wire format, deleted
  when the workout is saved or discarded; the identical per-app at-rest residual.
- **A35. Precise location — visits & traces (special-category-adjacent).** The Timeline app's
  location capture: the continuous background stream of coordinates that live capture
  (`timeline-ingestion.md` §4) turns into `Visit` moments (`:timeline-service`) and `Place`s
  (`:place-service`). This is the **single biggest sensitivity escalation the platform has taken
  on** — a location history reveals home, work, daily routines, and presence, and is
  special-category-adjacent (it can expose religion, health, and relationships by inference).
  **Custody is the ordinary sealed sync plane** — `Place`/`Moment` are feature-service entities
  keyed and encrypted exactly like file metadata (A2/A3 volume/epoch keys), so **no coordinate is
  ever relay-visible** and House Rule #1 is unchanged: the relay sees ciphertext and a device
  identity (A4), never a latitude. **Acquisition is the new surface, and it is the tightly
  controlled one:** (a) capture is **opt-in, off by default, and revocable** — nothing captures
  until the user explicitly enables it; (b) a **persistent OS indicator runs whenever capture is
  active** (the Android `location` foreground-service notification; mirror the intent on iOS); (c)
  the visit-detection clustering (`VisitDetector` / `detectVisits`) runs **on-device in
  `commonMain`, and raw pings are never stored** (§8 raw-ping decision) — only dwell centroids and
  durations reach the schema; (d) **geocoding egress** (turning a coordinate into a place label)
  goes **direct to the provider / self-hosted, never via the relay** — the relay must never learn
  a location — and only behind its **own persisted, revocable opt-in** (`PlaceGeocodingControl`,
  off by default; one gate for capture-time and import-backfill lookups alike, because a backfill
  sends the coordinates of the user's *entire imported history* to the provider). "Native ≠
  private": the device tier is the platform **address** API (`android.location.Geocoder` /
  `CLGeocoder`), which is server-backed — an enabled lookup sends the coordinate to the OS
  vendor. What is persisted is the returned label text on the sealed `Place` row (platform
  address output is storable; never Places-SDK content, never a raw provider response; at most a
  foreign-key external id besides — `timeline-ingestion.md` §5, `place-geocoding.md` §5).
  *As-built (2026-07-17, G2):* Android `AndroidReverseGeocoder` behind `PlaceLabelEnricher` —
  consent re-checked before **every** lookup (disabling stops a run mid-way), blank-only label
  fill so a user-authored name is never replaced, ~1 rps throttle, per-session fruitless-lookup
  memoisation. *As-built (2026-07-19, P3 — proxy-mediated POI geocoding):* the Timeline Android app
  **deliberately holds no `INTERNET`** (manifest `tools:node="remove"`; its map + capture egress is
  Control's), so venue-name (POI) geocoding cannot call out from the app. A **`ReverseGeocode` op on
  `:map-region-service`** routes the coordinate through **Control** (the trusted proxy, which does
  hold network access), which reverse-geocodes via Photon (OSM POI names) and returns only the label
  — exactly as region tiles are already fetched proxy-side. This keeps the egress off the app *and*
  off the **relay** (Control ≠ relay; the relay still never learns a location, HR #1), still behind
  the same `PlaceGeocodingControl` opt-in (the app-side `ProxyReverseGeocoder` tier is chained ahead
  of the platform address geocoder, both gated by the enricher). The provider endpoint is
  configurable (self-hosted Photon preferred; komoot public default under consent). Nothing new is
  persisted beyond the label text already covered above. *Correction (2026-10-17):* until then two
  callers bypassed that opt-in — the transit-stop lookup (`nearestTransitStop`, whose proxy falls back
  from tiles to Photon) and the place picker's candidates — so transit-leg endpoints reached komoot
  with the opt-in off (on iOS, every leg). Every Timeline call to a geocoding op now goes through one
  consent-checking gate, `PlaceGeocodingEgress`, enforced by a source guard test; an absent control
  refuses (`place-geocoding.md` §5.1). (e) bulk **import** of a Google/Apple export
  treats the file as untrusted input. An
  importer/capturer must acquire only under that explicit consent and must never route a coordinate
  into a relay-visible metadata position.
  (f) **Map *rendering* is a second egress surface on this asset, distinct from capture and from
  geocoding** — and it is *viewing behaviour*, re-derived from the UI rather than from the sensor:
  which place, trip or workout the user chose to open, and when. The default renderer is **MapLibre
  over Tenshu-served tiles** (`maps-region-delivery.md` D6/D10 — a coarse viewport pattern by IP at
  our own CDN, reducible to **zero** by holding a region offline, and never the app's own egress:
  the map apps stay `INTERNET`-stripped, R1). On **iOS only**, a user MAY elect **MapKit** instead
  (`ios-apple-maps-option.md`, accepted 2026-08-13): **off by default, revocable, and scoped to one
  app** — under route 1 each first-party app is its own sandbox, so the election does not travel
  between Fit, Timeline and Maps (its D9). An elected device discloses viewport and IP **to Apple at
  view time**, and unlike our own plane that is **not reducible to zero** — there is no offline
  variant of it. ⚠ The election governs the three detail heroes only (its D6), never the region
  picker and never a new surface. **Custody is unchanged under either renderer:** no coordinate
  reaches the relay, `Place`/`Moment` sealing is untouched, and House Rule #1 is not in question —
  this is a render-time decision with no wire, schema or relay component. Threats: **T-MR-05**. **Custody as-built (2026-07-13):** as with health (A34),
  the sealed-plane custody described here is now what the code does. Until this date the Timeline and
  Maps apps hosted their own in-process `:timeline-service` / `:place-service` / `:health-service`
  over an **in-memory** store, so captured visits and places lived only in the app's process — never
  written to disk, never synced, and silently lost at process death (`timeline-app.md` §10,
  `maps-app.md` §7). Both apps are now **clients of the Control proxy** like Drive: moments, places
  and visits are read and written over IPC, land in Control's durable store, and sync as ordinary
  feature-service entities under A2/A3 — no relay-visible coordinate, House Rule #1 unchanged, no new
  relay surface. Two consequences worth stating plainly: (i) the at-rest residual below is now the
  *real* one (location history shares Control's store, not a volatile per-app one), and (ii) the
  **launch-time fixture seeding both apps performed is gone** — writing fabricated moments/places
  into a durable, *synced* realm would have pushed invented location history to every paired device.
  **Residual:** like health (A34), location data at rest
  shares the platform secret/keystore custody of the realm keys (A21/A32) — a device compromise that
  already yields the realm keys also yields the location history; no new at-rest boundary beyond the
  existing per-app store. *Status:* the neutral capture DTOs, the **device-free visit detection**
  (`VisitDetector` batch + the `detectVisits` streaming operator), the DI-bound `LocationCaptureSource`
  seam, and the `LocationCaptureEngine` write loop have landed (I3a / I3b-1). The Android
  `FusedLocation`/`LocationManager` capture behind the foreground-service capture indicator, the
  background-location consent grant, and the explicit in-app opt-in are the next slice (I3b-2;
  on-device pending). *As-built (2026-07-24, M4/I4-2 — iOS capture):* the iOS acquisition surface
  is live with the same posture. (a) off by default behind its own persisted opt-in
  (`IosTimelineCaptureControl`; the Settings toggle tap is the consent moment and triggers the
  Always-authorization request; a denied/restricted grant resets the flag); (b) the persistent-
  indicator intent is met by the **OS's own surfaces** — iOS shows the location-arrow status
  indicator on deliveries and attributes background access in Settings ("Always" apps are
  user-auditable), and unlike Android no app-drawn indicator is possible for `CLVisit`/
  significant-change monitoring (there is no long-lived process to pin a notification to);
  (c) acquisition is **coarse by construction** — `CLVisit` dwell events plus
  significant-location-change fixes (~hundreds of metres apart), no continuous standard updates,
  no `location` background mode — and raw fixes still are never stored (the shared segmenter →
  decimated trace path, §8 raw-ping decision); custody is unchanged (writes ride the sealed
  route-1 in-process plane; no new relay surface, HR #1 intact). The geocoding rail on iOS remains
  behind its own `IosPlaceGeocodingControl` opt-in as documented under (d).

- **A37. The realm schema epoch vector (which apps the user runs).** The
  `RealmSchemaEpoch` row (`core-schema-evolution.md` §7.1, E2d): per service that has ever written
  data in this realm, the highest schema version any of the realm's devices has emitted under. It is
  what lets a lagging device discover it must stop pulling before it garbles blocks it cannot parse
  (§7.3's pull-fence). **Custody: ordinary sealed sync plane** — it is a `Syncable` row like any
  other, so it rides an encrypted `SyncBlock` and the relay never sees it (House Rule #1). That
  placement is a *security decision, not an implementation detail*: the vector is **per service**, so
  a relay-visible copy would leak **which apps the user runs** — a Fit entry identifies a
  fitness-app user, a Timeline entry a location-tracking user — turning a compatibility mechanism
  into an app-inventory side channel. Contrast the relay's own plaintext compatibility number: as of
  **E2e** (§7.5, as built §7.8) the 426 gate on `/sync/realm` reads `RELAY_PROTOCOL_VERSION` — the
  relay's *own* wire contract (routes, bodies, `SyncBlock` envelope, auth, blob-ref format) — which
  carries no information about the user at all. The plaintext `formatVersion` that gate used to read
  (A9-adjacent: one global integer leaking "this user upgraded") is **no longer read by the relay**, so
  the metadata the relay learns here *shrinks* rather than merely staying acceptable. Clients still
  send the field through the E2e transition window; an E2e relay ignores it. **Residual:** at rest the row shares
  Control's store and therefore the realm-key custody of A21/A32 — a device compromise that already
  yields the realm keys also yields the epoch vector, which is strictly less interesting than the
  data it describes. No new relay surface; no new key material.

- **A40. Face templates and person labels (GDPR Article 9 special-category).** Two artifacts with
  two custodies, and **the split is the protection** (`photos-people-and-pets.md` §3.1, IN-D5).

  **Templates** — per-face identity vectors derived on-device by the user's own opted-in devices —
  are **device-local and never enter the realm**. They live in a per-device append-only log
  (`DefaultFaceTemplateStore`, `/inference/faces.log`) that syncs nothing, appear in no schema, are
  re-derivable from content the device already holds, and are dropped when the local store is wiped.
  ⚠⚠ **No biometric template is ever realm data**, so none is ever relay-visible and there is no
  ciphertext on the relay from which one could be recovered. The read operation (`faceGroups`)
  enforces this at the boundary: it groups templates in-process and returns **file paths, an
  ephemeral group id, each face's box and each photograph's face count** — no vector crosses IPC, so
  an app process renders People while holding no biometric data at all. ⚠ The box and the count
  (IN-6 F6a, inference service v2) are **position, never identity**: they say where a face is and
  how many a photograph holds, not whose it is, and the app uses them only to crop a header face and
  to filter "Only them". Adding anything identity-bearing to that return is a change to this entry.

  **Person labels** — `MediaLabel` rows meaning *this photo shows Anna* — **are** realm data, and
  are special-category under Art. 9 because they associate an identified natural person with an
  image. Custody is the ordinary sealed sync plane: keyed and encrypted like every other row (A2),
  so **House Rule #1 is unchanged — the relay sees ciphertext blocks and a device identity (A4),
  never a name and never a face.** ⭐ The label's content is a string the user typed, never a model
  output, and it is stamped `ModelProvenance.User` so that no re-index may treat it as a stale
  model opinion and overwrite it. `nameFaceGroup` is the **only** operation in this feature that
  writes realm data; it carries a path and a name and nothing else, and widening it is a change to
  this entry (House Rule 2).

  **Acquisition is gated twice** (IN-D3): a realm switch and a per-device switch, both defaulting
  to *no*, offered in Settings only and never during onboarding, with consent re-read per file so a
  withdrawal stops a running pass rather than taking effect at the next one. With no consent the
  photograph's bytes are never read. ⚠⚠ Consent also gates **reading stored templates** and
  **writing a name**, not merely producing new ones: a withdrawal while a screen full of faces is
  open must neither serve what is already on disk nor accept a write arriving afterwards.

  **Scope boundary:** this covers clustering faces in the user's **own library** on their **own
  device**. Camera-side recognition of non-consenting third parties remains excluded as policy
  (`camera-service.md` CD-D7); the distinction is argued in `inference-service.md` IN-D3 and is
  ⚖️ a counsel-review item before release (`policy/in6-face-clustering-counsel-packet.md`).

  **Residual:** a device compromise that already yields the realm keys also yields the person
  labels — no new at-rest trust boundary beyond the existing per-app store (A21/A32). A realm
  member admitted later (A26/A27) sees person labels like any other row; the templates never travel
  to them.

  **Withdrawal (decided 2026-09-24, IN-6 F4): templates are deleted and person labels are kept.**
  Templates are biometric data produced by the consented processing, so withdrawing consent (the
  realm switch or this device's) removes them. Labels are the user's own words about their own
  photographs and stay. ⚠ The proxy cannot observe a preference flip, so the deletion happens at
  the two places the store would be *used*: the next index pass (`MediaIndexer.indexPass`, before
  any backend check) and the next People read (`faceGroups`, before any template is read). Until
  one of them runs the file stays on disk, but it is never served, because consent is checked
  before every read. ⚠⚠ **Only an explicit switch-off deletes** (hardened 2026-09-27): a consent
  row that reads `false`. A read that finds *no* row still refuses (absence is "no" for doing work
  and for serving), but it deletes nothing, because a read that finds nothing — a row mid-update,
  a read that failed — is not a user's act, and a deletion cannot be taken back. ⚠ The realm switch syncs, so a withdrawal made on one device takes effect on
  each other device at its own next pass or read. ⚖️ The label half is still a counsel question
  (`policy/in6-face-clustering-counsel-packet.md` §6).

  **Deleting the photograph (P0f-a, 2026-10-02): its template and its vector go with it; person
  labels stay.** A template is derived from one photograph, so a photograph the user deleted or
  trashed — on this device, or on another one and synced here — must stop contributing to People
  and must leave this device. Before P0f-a neither held: nothing on the delete, trash or sync-apply
  path touched the device-local stores, `faceGroups` clustered every stored row, and a deleted
  photograph's template kept pulling its person's centroid until consent was withdrawn, the model
  changed or the realm was wiped (`media-processing-pipeline-sweep.md` F0). Two mechanisms now:
  - **Removal at the next index pass.** Each pass starts by dropping, from the face log **and** the
    vector log, every row the store remembers — a template, a vector, an empty face row or a
    vectorless visit — whose photograph is gone. ⚠⚠ **Gone needs two proofs:** the path is absent
    from a library walk (live, untrashed images) that its source vouches for as complete, **and** a
    point read finds no live file at it. A walk is not a snapshot — a move unlinks its source before
    it saves its destination — so absence alone is never acted on; and a walk that throws, could not
    read part of the tree (a device mid-sync), or comes with no point read removes nothing.
    ⚠⚠ **How a row leaves the log: struck, then scrubbed, in place** (`LogStrikes`). The strike is
    one byte — the record's flag — and it *is* the removal: no read sees the row again, in this
    process or after any restart. The scrub then overwrites the record's path, geometry and floats
    with zeros, in the same call. ⚠ **So between the two writes the template is masked but still
    on disk.** That window is closed by the next thing that happens: the same call's scrub; or, if
    the process died in between or the write failed, the next open of the store (every Control
    start that touches People, search or indexing) and every later removal, each of which finds
    struck-but-unzeroed records by parsing the log and retries. A scrub that fails is reported, never
    swallowed — and "fails" includes a write that returned normally and left the bytes in place: a
    record counts as erased only once the file has been **read back** as zeros, as the strike is
    read back before the row is forgotten. No write to either log is believed on the strength of
    the call returning (`LogStrikes.kt` lists each write and what confirms it).
    ⚠⚠ **A removal reports all of what it did, in one result** (`Removal`): rows removed, rows
    refused (still in the store, for the next pass), and how many removed records still hold their
    bytes. One batch can do all three, and the erasure debt is never dropped because a refusal was
    also raised — the index pass counts and logs from that result.
    ⚠⚠ **Memory never outruns the file.** After any operation returns *or throws*, what the store
    serves is what a reopen would parse. A write whose outcome is unknown — it threw, it tore, or
    the read that would confirm it failed — is settled one way only: memory is discarded and
    rebuilt from the log. If that re-read fails too, the store holds nothing and **refuses**
    (`LogOutOfSync`) every read and write until a re-read succeeds, rather than serve or extend a
    guess; a wipe is exempt, because it needs no knowledge of what the log held. ⚠ **What "erased" does not mean:** the file's bytes are
    overwritten; what flash wear-levelling or a copy-on-write filesystem retains of the old blocks
    is outside an app's reach, exactly as for a deleted file, and the removed row's *length* stays
    in the log as zeros until the next wipe or model change (there is no compaction). ⚠ The store
    has no `fsync`: a killed process loses nothing it wrote, but a power cut can lose a recent
    strike, in which case the row is simply still there for the next pass.
    ⚠⚠ **No second file, by design.** A removal never copies, renames or deletes, so there is no
    staging or recovery copy of the templates anywhere: nothing for a crash to leave half-promoted,
    and nothing for a wipe (consent withdrawal, realm reset) to miss and a later open to bring
    back. A wipe empties the one log through a write that fails loudly and then deletes it; either
    step alone leaves a store that opens empty.
    ⚠⚠ **Wired in production since P0f-b PR-1**: Control's media source (`controlMediaSource`)
    reports a complete walk only when `walkImageLibrary` read the whole tree, and always with
    `livePathsAmong` as the point read; anything less is a partial walk, which deletes nothing. ⚠ It
    acts only where a pass runs: Android and desktop. iOS route-1 runs no pass (no indexing
    conditions and no backend are bound, so the scheduled pass defers and *Index now* refuses), and
    its stores stay empty until Photos owns them (PIO-7, PIO-D10).
  - **The read filter.** `faceGroups` (and `photosLike`) drop every row whose path does not name a
    live, untrashed file *before* clustering or ranking, so a delete holds on the very next read with
    no pass. **Wired since P0f-b PR-1**: one `LivePaths` predicate in Control's common
    `ProxyModule` (so Android, desktop and iOS alike) — the same one the reconcile uses. It answers
    against the personal realm's `File` rows, which every session of a proxy shares and which are
    the only paths the walk ever indexes, so it can never ask a different realm than the one the
    pass walked. ⚠⚠ **A store that cannot answer is not "all dead"**: if the realm root does not
    read, the predicate throws, `faceGroups` and `photosLike` fail as an ordinary failed read (Photos:
    "couldn't load", never "nobody here" and never the unfiltered rows), and the reconcile drops
    nothing that pass. ⚠ Between the delete and the next complete pass the template is still on disk
    — hidden from every read, counted by nothing.
  **P0f-a deletes no label row** — neither a name the user typed nor a model-produced label or its
  marker: the reconcile touches the two device-local logs and nothing in the realm. ⚠ That is a
  statement about this change, not the end state: removing a permanently deleted photograph's
  labels, typed names included (D13 as amended, 2026-10-02), is P0f-b's purge hook, which also
  emits the tombstones peers need. Until it lands, a label outlives a purged photograph.
  ⚠ **Not covered: a photograph replaced in place** keeps its old
  template and vector, because neither store records what content a row came from; that is P10's
  content-hash key (D11). ⚠ Once wired, removal is at the next pass, which needs idle and charging
  (or *Index now*); an immediate hook on Control's own delete/trash paths is P0f-b too. ⚠⚠ **What
  holds since P0f-b PR-1**: a deleted or trashed photograph stops shaping People and search on the
  next read, and its template and vector leave the disk at the next complete pass on Android and
  desktop. ⚠ Still owed by later P0f-b PRs: removal at the moment of the delete (S1), label deletion
  on a permanent delete (S2), names following a move (S4) and invalidation on replace (S3).

  **Where the templates live on iOS (PIO-7 PR-3, 2026-10-07; `ios-on-device-inference.md` §3.1 /
  §13).** No iOS build produces a template yet: nothing binds a face backend there. The place is
  fixed first. On iOS the template log is **Photos' alone, in Photos' own container**, never the
  App Group the other first-party apps share (the A5 amendment of the same date), in a directory
  excluded from backup, so a biometric template cannot reach an iCloud or Finder backup; a directory that cannot be so marked is never written to. Every
  other route-1 process binds a template store with no file behind it. ⚠ *"Dropped when the local
  store is wiped"*, above, is not automatic there: the wipe hook runs in the process that sees
  the wipe, and Photos may not be running. The realm stamp carries it instead — a mismatch clears
  the templates before anything reads them, a deprovision clears them, and the stamp is written
  only after the template clear has returned. **`faceGroups` asks the same gate as `photosLike`,
  before it reads the store to serve anything.** The one thing that runs ahead of the gate is the
  explicit consent-withdrawal cleanup, which reads only the store's stamp and deletes (below). A
  People request that reaches the store ahead of the launch-time check, after a realm
  reset, runs the whole check itself and finds the templates cleared, or is refused. ⚠ The
  live-path filter is not that protection: after a reset the same path can name a live photograph
  of someone else. ⚠⚠ **That gate governs serving, never deletion.** An explicit consent
  withdrawal (the *Withdrawal* paragraph above) deletes the templates before the gate is asked,
  in `faceGroups` and at the start of an index pass the gate refuses, so a gate closed for an
  unrelated reason cannot keep a withdrawn user's templates on disk. That holds also where the
  store cannot see its own files (the backup-exclusion failure of the A5 amendment, where reads
  are hidden): the withdrawal's question to the store retries the purge of the directory, so the
  templates go although the store reported none. ⚠ Residual: the removal of a
  *deleted photograph's* template runs inside a pass, so it waits while the gate is closed; the
  template is on disk, unread, until a check succeeds. Until S0b-1 the stamp is the owner id alone, so a same-owner reset is not
  seen: acceptable while no template exists on iOS, and a precondition for PIO-8.


- **A39. Mia's query capability (cross-service read on the user's behalf).** Mia is the first
  component that composes and executes reads across *every* registered service on the user's behalf
  (`mia-query-layer.md` MQ-1). The capability is not a stored artifact — there is no Mia row, no
  synced transcript, no index — it is the **set of chains the proxy will execute when asked**. Two
  things bound it. First, the chain is **data, not code** (`RequestChain.getCallstack(): List<Data>`),
  so it can be walked and refused before anything runs; `MiaChainValidator` does exactly that, and it
  ships in v1 while the only generator is our own grammar rather than being retrofitted around a
  model later. Second, **read-only is enforced, never inherent** — the identical `OperationDto`
  callstack shape carries every write in the platform, so the op allowlist is the thing that makes a
  query a query. **Custody: none new.** Mia stores nothing; conversation history is process-local and
  unsynced (MQ-D8), which is deliberate — what someone *asks* is routinely more revealing than what
  they are shown, so a transcript is the most sensitive artifact the feature could produce and it is
  not produced. **Residual:** grants are **per service, not per row or property** — Tenshu has no
  finer capability system (the track flagged in `camera-service.md` CD-D8 and
  `ios-first-party-route-1-migration.md` §4) — and the projection allowlist stands in for it by
  pinning what a chain may project. No new key material, no new relay surface (HR #1 untouched: the
  relay sees ciphertext and never a query).

  *Amendment (Mia on iOS route 1, 2026-10-02, [`mia-query-layer.md`](../proposals/mia-query-layer.md) §19):*
  **"Custody: none new" describes Android and desktop**, where Mia is a client of Control's proxy
  and holds no key. **On iOS route 1 the Mia process holds the realm key material** — the whole
  `RealmKeyMaterial` blob, like every other first-party app there (A4, Decision C) — plus its
  per-app custody copies (joiner payload, device identity) in the `app.tenshu.mia.ios` Keychain
  bucket, purged by `MiaLocalRuntime.deprovision()`. It still **stores no artifact of its own**:
  no row, no index, and the conversation stays process-local and unsynced. Two consequences,
  stated plainly. (a) **The validator bounds what `askMia` reads, not what the Mia process could
  read**: that process links the same proxy graph Drive does and can decrypt any realm content.
  `MiaChainValidator` remains what it was built as — a check on a *generated chain*
  (T-MQ-01) — and is not a sandbox around the app. (b) **`mia.grant.*` is not a security boundary
  on iOS**, because Decision C provides none between first-party apps. It is what it is for the
  user on every platform — a per-service preference the query layer reads per question and
  honours by refusing a revoked source by name (T-MQ-01b) — and on iOS it is *only* that: it
  governs what Mia will answer, not what the Mia binary can reach. One thing is narrower there:
  the route-1 proxy is wired `MiaActionOffer.WITHHELD`, so `askMia` plans no action and mints no
  confirmation token, and T-MQ-01c / T-MQ-01d's surfaces are not reachable through Mia on an
  iPhone. No new relay surface (HR #1 untouched).

  *Amendment (Mia on the web, 2026-10-28, [`web-app-suite.md`](../proposals/web-app-suite.md) §4.B,
  [`mia-query-layer.md`](../proposals/mia-query-layer.md) §20):* **a new reader under F5.** The
  `web/mia` origin (`mia.tenshu.app`) is allowlisted for `mia.ask` (and the history, turns and
  grant methods — exact names, no `mia.*` wildcard; `web/apex/src/apps/registry/apps/mia.ts`). Every
  other hosted app's allowlist is pinned to name **no** `mia.*` method
  (`proxyGateway.mia.test.ts`), and Mia's to name nothing from another app's namespace. Through
  questions that origin can now read **every source the grants allow** — file names, timeline
  moments, workouts, places, calendar occurrences, devices and their state, routines, labels — where
  before F5 scoped it to its own data. That is a confused-deputy widening by design: the runtime in
  the apex (which holds the keys; the Mia origin holds none, so A4 and every key asset are
  untouched) composes and runs the reads with the user's authority and posts the answers to the
  Mia frame. Stated plainly:
  - **What the origin learns** is what an answer carries: per cited row its type, key parts, title,
    subtitle and time, plus the planner's explanation sentence — bounded per question by
    `MiaChainValidator` (reads only; the **projection allowlist**, so no content bytes, no
    `File.Data`; a terminal `take` and step ceilings). It never sees a chain it did not ask for, and
    nothing it can call returns more than an answer.
  - **Read-only, enforced twice**: the tab's proxy is wired `MiaActionOffer.WITHHELD` and
    `confirmMiaAction` is not mapped, so no question writes (T-MQ-01c/d are unreachable from the
    web). The origin's only writes are the conversation (in the runtime's memory, MQ-D8, cleared on
    sign-out or lock — no durable transcript, T-MQ-02 unchanged) and the **grant switches**.
  - ⚠⚠ **The grants bound what Mia answers, not what a compromised Mia origin can read.** The same
    origin holds the switches (native Settings' `PreferenceService.set` on `mia.grant.*`, decided
    2026-10-28), so script running on `mia.tenshu.app` (an XSS) can turn a revoked source back on and
    then ask. Exactly as natively: on Android and desktop the Mia app holds both the question and the
    switches against Control's proxy, and on iOS route 1 the Mia process holds the realm keys
    outright (above). The web origin is therefore *no wider* than native Mia on any platform —
    narrower than iOS (no keys, no proxy graph, only the eight allowlisted methods) — but it is a
    new place that position exists. ⚠ It can also turn the Calendar **write** grant on. That widens
    nothing in the tab (actions stay withheld), and on a native device an event still needs MQ-D7's
    confirmation tap there (T-MQ-01d); the grant only lets native Mia *offer* it.
  - **What did not change:** the relay (HR #1), key custody, every other origin's reach, and the
    per-service granularity residual above. The allowlist is code, not a user grant; the per-app
    capability model is still Rei Q12.


- **A38. Per-place Wi-Fi network fingerprints (hashed).** The `PlaceNetworkFingerprint` rows
  (`timeline-recognition-and-place-naming.md` §9.3, F4): one row per access point the user's devices
  have seen at a place, mapping an opaque `networkTag` to a `placeId`. They exist so Wi-Fi place
  recognition is a property of the *user* rather than of one handset — a device-local map is not
  merely lost on a new phone, it **re-fragments the user's places while relearning them** (§8.2).
  **Custody: ordinary sealed sync plane** — `Syncable` rows on an encrypted `SyncBlock`; the relay
  never sees them (House Rule #1). **Personal realm only**: nothing writes them into a shared realm
  and nothing should — a shared-folder peer must never learn the owner's networks.

  **What is stored is a hash, not a MAC.** `networkTag = SHA-256("tenshu:place-network:bssid:" ‖
  lowercase(bssid))`. The lookup is exact-match, so a tag is functionally identical to the raw value,
  and the raw BSSIDs of the user's home, employer and friends' routers therefore never sit in
  plaintext in the realm where a diagnostics export, an error report, a future data export or a
  backup inspection could surface them. ⚠ **This is deliberately not claimed as a defence against an
  attacker holding the realm plaintext**: the BSSID space is small and OUI-constrained, so such an
  attacker can brute-force the mapping. ⚠ **No salt, by decision** — a salt would have to ride in the
  realm for other devices to derive the same tag, so it would sit beside the tags it purports to
  protect and buy nothing against the only adversary that could use it, while adding a synced secret
  with its own genesis and rotation problems.

  **Why syncing them is acceptable.** The realm already contains the exact coordinates of every place
  the user dwells (`Place`) and a decimated polyline of every trip (`MovementTrace`). A network
  identifier adds **no new class of information** about the user; what changes is blast radius — from
  one device to every provisioned device. That is the same widening every other `Syncable` row in the
  realm already accepts, and it is gated by an opt-in that is **off by default**
  (`WifiFingerprintControl`), which the user can revoke at any time.

  ⚠ **SSIDs are NOT synced.** The SSID index stays device-local (§10.4 Q9): one SSID spans a campus
  or a chain, so it is poor colocation evidence, and SSIDs are user-authored strings that frequently
  contain a person's name — strictly more identifying than the hashed AP id. **Residual:** at rest the
  rows share Control's store and therefore the realm-key custody of A21/A32; a device compromise that
  already yields the realm keys also yields the fingerprints, which are strictly less interesting than
  the coordinates they point at. No new relay surface, no new key material, no new permission (the
  connected-AP read rides the location grant the app already holds), and no `INTERNET` in Timeline.

- **A43. Android side-channel signing key.** One RSA-4096 key of ours that signs every Android copy
  not delivered by Play (`docs/how-to/android-release.md` *The side-channel key*). Its certificate
  digest is pinned in `FirstPartySigning.SIDE_CHANNEL_SIGNING_CERT_SHA256` and shipped through Play,
  so Control and the apps admit a downloaded copy beside Play ones (T-C-20). It is a **permanent
  identity on users' devices**: Android installs an update only if it carries the same signer, so
  this key is the *only* thing that can update a downloaded copy, and whatever it signs under a
  first-party package name is admitted by Control. Google cannot reset it (unlike the upload key).
  **Custody: a trust anchor** under [`ops-secret-custody.md`](../proposals/ops-secret-custody.md) D1 —
  never in CI or any automation besides the maintainer's own `android-release.sh` run, which
  materialises it into a temporary directory for one Gradle invocation and removes it on exit. Its
  working copy sits in the maintainer's macOS Keychain (`android-sidechannel-keystore`), which D2
  says is hygiene, not a boundary against a process running as the maintainer; the **offline
  backup** made before the first APK ships lives outside any directory tooling wipes (D5; ⚠ D5's
`~/Documents` is iCloud-synced, see T-DA-02). D6's
  hardware token is the destination. **Compromise** lets an attacker publish updates that install
  over every downloaded copy and are admitted by Control (T-DA-02); recovery is replacing the pin in a
  Play release to all ten apps and asking every downloading user to uninstall and reinstall.
  **Loss** strands every downloaded install on its current version until it is uninstalled (T-DA-03).
- **A44. The release bucket, its publish key and the release manifest.** The Cloudflare R2 bucket
  `tenshu-releases` behind `releases.tenshu.app` (`deploy/cloudflare-releases/`), holding the
  direct APKs at content-addressed keys `sha256/<hex>.apk` and, since 2026-11-06, the notarized macOS
  `.dmg` at `sha256/<hex>.dmg` (§8.24); the bucket-scoped R2 S3 key pair that
  can write it (Keychain `r2-releases-*`, an operational credential under `ops-secret-custody.md`
  D1/D2); and the committed `store/android-release.json` that names each app's URL, SHA-256, size,
  versionCode and A43's certificate digest (and `store/macos-release.json`, the same for the `.dmg`), from which the download pages are rendered at build time.
  Public by design — no user data, no secret in the objects. It matters for **integrity only**: R2
  does not check that an object's bytes match its key, so the write key can replace an APK, and a
  fresh install trusts whatever key signed the APK it receives (TA4 note, T-DA-01). Hence a bucket and
  key of its own, never shared with `tenshu-assets` (proposal D3).
## 6. Adversaries

Each threat in §8 is tagged with the adversaries that can execute it. The full list is the "maximal
adversary" model — the protocol must preserve §3's invariants against all of them simultaneously.

- **AD1. Opportunistic.** Finder of a lost/stolen device, curious roommate, shoulder-surfer. No
  targeted effort.
- **AD2. Motivated individual.** Ex-partner, stalker, coworker. Physical access during unlocked
  moments, access to the victim's home/office network, ability to social-engineer the user.
- **AD3. Well-funded adversary.** Criminal syndicate, corporate espionage. Active MITM at ISP or
  WiFi scale, custom malware, purchased zero-days for consumer platforms.
- **AD4. Nation-state.** AD3 plus: legal authority over the relay operator, subverted CAs,
  wire-level access at IXPs, large-scale traffic analysis, targeted binary modification at the
  platform distribution layer.
- **AD5. Compromised relay.** Tenshu backend itself is compromised by any of AD2–AD4, or by a
  malicious insider, or operates under legal compulsion. Has full read/write over all relay storage
  and all responses served to clients.

AD5 is called out separately because the core security stance says the system must preserve its
invariants even if it is true. It is not a subset of AD4; it is the assumed default.

## 7. Attack Surfaces

1. Pairing handshake (QR — both role-host forms `tenshu://join` and `tenshu://offer` —
   PairingRequest, PairingResponse, and the offer-flow offer-response).
2. Relay transport (every HTTP exchange with the relay).
3. Relay storage (block history, ephemeral pairing mailbox, the hash-keyed offer-response
   rendezvous store, recovery blob, master public keys per owner, device public keys learned
   via pairing/recovery, and the content-plane blob store — whose owner-authed per-ref delete,
   `POST /sync/content/delete` (T-R-13), was **removed in CR-2 B6**: content is freed only by the
   relay's own sweep).
4. Client-side at-rest storage (app storage, keystore, caches, logs, OS backups).
5. Client UI (approval prompts, screens, accessibility APIs, screenshots).
6. Device boundaries (OS ↔ app, app ↔ keystore, app ↔ IPC, Control ↔ co-installed Tenshu
   apps on desktop via a Unix-domain socket (Windows included), and on Android via Binder in both directions —
   each app's exported `IClientInterface` callback service is T-C-19, Control's exported
   `IProxyInterface` is T-C-20).
7. Human user (social engineering, coercion, mistakes).
8. Recovery code lifecycle (display, storage, use).
9. Rei artifact distribution and verification (publications, issues, evaluations, signature
   chains rooted at A11, bundled vs ledger-backed sources).
10. Cross-realm channels (shared-realm membership records, wrapped A12 keys, shared-realm
    block streams and content blobs, cross-realm notification routing).
11. Cross-user contact invitation handshake (invite links/QR, the `/sync/invite/*`
    mailbox, sealed response/ack blobs, self-asserted names).
12. Cross-user membership grant (the `/sync/grant/*` sealed-grant mailbox, the
    `/sync/realm-member/admit` capability-chain admission + `/sync/realm-member/revoke`
    master-signed revocation, the per-realm revocation list + admitted-device associations).
13. Commerce↔relay entitlement bridge + the commerce-plane account/payment endpoints (the
    `:commerce-service` issuance endpoints `/commerce/entitlement/{token,mint}`, the relay's
    entitlement-token verifier + the `X-Entitlement` header it reads, and the client's token-fetch +
    presentation path; **plus** the account/session/consent endpoints
    `/commerce/account/{register,verify,claim,session}` + `/commerce/entitlement/redeem`, and the
    Stripe rail `/commerce/checkout`, `/commerce/portal`, `/commerce/webhook/stripe`). The relay holds
    only the issuer public key; commerce holds the private key + the account set (A28). The webhook is
    Stripe-signature-verified (T-CB-07); the account endpoints keep the relay's content-free error
    posture (no account-enumeration oracle).
14. App↔proxy EPC rendezvous mailbox (the `/sync/epc/{sessionId}` store-and-forward surface: the
    proxy's device-key-JWT-gated session provisioning + teardown, the app's anonymous
    capability-addressed frame post/poll, and the two directional sealed-frame queues).
15. Internal commerce admin console (`admin.tenshu.app`, `commerce-accounts-and-sessions.md` §4.5):
    the server-rendered views served by `:commerce-service` on a **separate host/port** from the
    public API, behind an admin-token credential distinct from any user/commerce session and from the
    issuer key (A29). It is the one surface that joins **A28 (real PII) ⨯ A30 (`ownerId`)** in the
    clear — a compromised or over-exposed console de-anonymises every paying user at once. Production
    additionally fronts it with a Caddy allowlist / VPN (off public DNS) and audit-logs every access
    (§5.3). It now carries three capabilities, not one:
    - **Read** — the users view, the purchase ledger (`/admin/purchases`) and one account's
      entitlement history (`/admin/account`). Sessions are **counted, never listed**: a session token
      is a live credential and has no reason to appear here.
    - **Lifecycle mutation** — `POST /admin/accounts/close` + `/reopen` (CE-5). ⚠⚠ Closure is the
      first step of a path that ends in permanently deleting an owner's content, which runs only when
      **both** planes are switched on (`ACCOUNT_CLOSURE_RELAY_URL` + `ACCOUNT_CLOSURE_RECLAIM` — both
      are, in production, since 2026-10-24), so a close here deletes the content 30 days later.
    - **Bounded export** — `/admin/export/ledger.csv` + `/vat-summary.csv` (WC-4), reversing §4.5's
      original "no bulk export" per **D12**: an OSS VAT return is a statutory obligation, not the
      "little ops value" that decision weighed. ⚠⚠ **The export does not extend this surface's
      A28 ⨯ A30 exposure**, and that is the whole basis for the reversal: a VAT return needs neither
      side of the join, so `LedgerExportRow` carries no `ownerId`, no email and no `accountId` — the
      fields are absent from the type rather than filtered from a query, so a widening edit cannot
      reintroduce them, and the absence is asserted on the rendered bytes. The range is **mandatory**
      (no parameter default that would mean "everything") and the **window** is audit-logged, since
      for an export the interesting fact is its scope.

    **MFA is enforced, not recommended** (WC-5): a second factor rides as a TOTP code in
    `X-Admin-Totp`, checked for **every** admin path by an application-level gate rather than per
    route, so a route added later is covered by construction. ⚠ Accepted codes are **burned**, so an
    intercepted request cannot be replayed inside the code's remaining validity — without that, MFA
    would only shorten the life of a captured request rather than end it. ⚠ A refusal is one opaque
    401 whichever factor failed; distinguishing them would confirm to a holder of a stolen token that
    the token is live. ⚠⚠ `CommerceConfig.validate()` **refuses to start** an admin console without
    `ADMIN_TOTP_SECRET` when `DEV_MODE=false`, so single-factor is a configuration production cannot
    hold — the same treatment as the dev signing seed, the fake Stripe rail and the log email sender.

16. Delegated device admission (the chain-verified `POST /sync/pairing/response` path — the
    optional `admissionCertChain` + A33-signed delegation — plus the `/sync/admission/revoke`
    master-signed revocation endpoint and the per-owner admission revocation list the relay
    stores and consults).

17. Smart-home actuation channel (the hub's consumption of realm-synced `DeviceCommand` rows
    into physical device effects via a `DeviceBridge` — no new network surface, the rows ride
    ordinary sync; the surface is the hub-side executor: its ownership check, its durable
    consumed journal, and its staleness window).

17b. Smart-home **radio** ingest and admission (HD-2): the zigbee2mqtt sidecar's MQTT contract
    as consumed by the hub over **loopback** — attacker-influenced device names/attributes
    arriving from the airspace and becoming realm rows, the permit-join admission gate, the
    Zigbee network key on the node's disk, and the local broker itself. Still no *network*
    surface (loopback only, hub is a client), and still nothing the relay can see (§8.20).

18. **Elected third-party map rendering (iOS only).** The tile requests an *elected* MapKit
    renderer makes to Apple while drawing a place, trip or workout hero — a **viewing-behaviour**
    channel on A35, opened by explicit per-app consent and closed by revoking it
    (`ios-apple-maps-option.md`; A35(f), T-MR-05). ⚠ This is the one map path that is the **app's**
    egress rather than the proxy's, and the only one whose observer is a party we cannot audit.
    Absent the election it does not exist: the default renderer reaches no third party at all.

19. **Operational health surface (unauthenticated).** `GET /` and `GET /health` on the relay, and
    `GET /health` on commerce. Deliberately outside auth — a monitor holds no device JWT and no
    session — so they are reachable by anyone who can reach the host. Neither reads or writes user
    data (House Rule #1 untouched): the relay's writes one byte to a random name under the blob
    root and deletes it; commerce's runs `SELECT 1`. What they disclose is bounded by design to
    liveness plus, on the relay, one `durable` flag. See T-T-09.

20. **App distribution outside the stores (Android direct APKs, macOS `.dmg`).** The release bucket
    (A44) a browser or Homebrew downloads an APK or `.dmg` from, the download pages on tenshu.app that link it, the publish step that
    writes both (`scripts/android-direct-publish.sh`), and the side-channel key (A43) that signs every
    such APK. Integrity only: nothing user-owned passes through it. See §8.23.

## 8. Threats

Each threat has: ID, one-line description, relevant actors, affected assets, affected invariants,
defense, residual risk.

### 8.1 Pairing handshake

**T-P-01. Passive QR observation.**
Actors: AD1–AD5. Assets: A8, eventually A1–A3. Invariants: I1, I5.
An attacker observes the QR (photograph across a room, screen share, accessibility API,
screen-reading malware).
Defense: SAS comparison on approval (closes active-MITM use of the observed QR); pairing secret is
single-use with 5-minute TTL (a stale observation cannot be replayed); QR does not contain any
long-lived secrets.
Residual risk: **Low** if the user performs SAS comparison; **Medium** otherwise.

**T-P-02. Active MITM substituting Public Key B.**
Actors: AD3–AD5. Assets: A1–A3. Invariants: I1.
An attacker observes the QR and posts their own `PairingRequest` to the relay before the legitimate
Device B does. The host derives a shared secret with the attacker.
Defense: SAS is computed over both ephemeral public keys **and, since 2026-10-03, over the joiner's
Ed25519 signing key, `deviceId` and name** (v2, `deriveJoinPairingSas`); an MITM derives a different
SAS from the legitimate Device B, which the human comparison detects — and so does a relay that
keeps Public Key B but changes anything else the request carries. ⚠⚠ **And the comparison is binding
(T-P-14): the keys the SAS was computed from are the keys the admission uses.** The host pins the
request it showed — `deviceId`, Public Key B, the Ed25519 signing key and the device name — at the
moment it derives the SAS, and approve seals the payload to, and signs the delegation over, the
pinned keys. Approve never takes key material from a later relay response; it does not ask for one.
Residual risk: **Low** — provided SAS comparison is enforced, not optional, and the UI makes
skipping it difficult. ⚠ Until 2026-10-02 this entry claimed the defence without the binding, and
the code did not have it; see T-P-14 for what that was worth to an attacker.

**T-P-03. Relay forges a PairingRequest to the host.**
Actors: AD5. Assets: A1–A3. Invariants: I1, I5.
A compromised relay injects a fake `PairingRequest` to get a legitimate host to issue a
`PairingResponse` encrypted to the relay's chosen key.
Defense: Same as T-P-02 — the SAS on the host does not match anything the user will see on a real
device, because no real Device B is participating. The user should be trained to only approve when a
real device they expect is showing the matching code. ⚠⚠ The relay is also the party that serves
the request list, so this defence holds only because of the T-P-14 binding: what the relay says
*after* the SAS is on screen cannot change which keys are admitted, and a request the host never
showed a SAS for (any other pairing secret, any other session) cannot be approved at all. And
what it says *before* the SAS is on screen is covered by the code itself: the v2 transcript binds
the joiner's signing key, `deviceId` and name, so a relay that serves the genuine ECDH key with its
own identity from the very first poll shows the host a code no real device displays.
Residual risk: **Low** if SAS is enforced and the UI mentions "only approve if the code matches on a
device you physically have." The relay keeps what it always had: it can drop, delay or reorder
requests (T-P-04), and it can make the host show a code that matches nothing.

**T-P-04. Relay suppresses a legitimate PairingRequest.**
Actors: AD5. Assets: availability. Invariants: I6.
Relay silently drops requests so pairing appears to fail.
Defense: User-visible timeout; the handshake has no "partial" intermediate state that could be
exploited. Failure is failure.
Residual risk: **Accepted.** Pure denial-of-service is not distinguishable from legitimate
connectivity problems. Documented in §10.

**T-P-05. User approves a rogue device (consent failure).**
Actors: AD2–AD4. Assets: A1–A3. Invariants: violated by design once approved.
The user is socially engineered, coerced, or careless, and approves pairing for a device actually
controlled by the attacker.
Prevention (imperfect, and ⚠ **corrected 2026-08-28 to what is actually built** — the previous
wording listed six measures of which two exist, which under House Rule #2 is a contract stating a
defence it does not have): the approval prompt shows **`deviceName` and the SAS**.
⚠ **Not built, and not currently planned:** a stable fingerprint of Public Key B, the requester's
reported IP, last-seen geography, and a "type the code on the other device" step for sensitive
approvals. They remain reasonable prevention ideas — they are simply not defences this system
presently offers, and the residual risk below rests on containment, not on them.
Containment (primary defense): revocation + epoch rotation (T-K-01). Forward-only history by
default (§8.5) limits retrospective damage.
Residual risk: **Medium before approval, Low-to-Medium after containment.** Plaintext already
exfiltrated by the rogue device is unrecoverable.

**T-P-06. Replay of a captured PairingRequest.**
Actors: AD1–AD5. Assets: A8. Invariants: I5.
Attacker captures a valid `PairingRequest` on the wire and re-posts it later.
Defense: Pairing secret is single-use (the host mints a fresh one per session and approve ends the
session, so a replay addresses a session that no longer polls). ⚠ *Corrected 2026-10-28 (F7):* this
said "the relay tracks consumed secrets with TTL eviction", which the join flow did not do — it
kept requests and responses keyed by the raw secret with no consumed state at all. Now a request
expires after 7 minutes, a response after 7 minutes, and an acked response leaves a **24-hour
consumed marker** on its `pairingSecretHash` so it cannot be refilled (the offer flow's shape,
T-P-16). A replayed request also re-presents the same sealed name, keys and SAS transcript, so it
can only re-show a code for the device that posted it.
Residual risk: **Low.**

**T-P-07. Attacker pre-posts a PairingResponse under a guessed secret.**
Actors: AD3–AD5. Assets: A1–A3. Invariants: I1.
If secrets are low-entropy, an attacker can post crafted responses keyed under guesses.
Defense: the pairing secret is a v4 UUID — **122 bits** of entropy (⚠ this said "128-bit" until
2026-08-28; `generateId()` is `Uuid.random()`, and six bits are fixed for version and variant). Far
past brute force either way, and ⭐ the manual-entry path carries the **whole** string rather than a
human-typed truncation, which is the property that would actually have mattered. The response
payload is encrypted with a
key derived from the actual ECDH shared secret, not from the pairing secret, so a guessed-secret
injection doesn't compromise confidentiality even if accepted.
Residual risk: **Low.**

*Offer-flow variant (`sync-pairing-inverted-direction.md` §4).* The threats above are stated for
the join direction (existing device displays, new device scans). The offer flow is
cryptographically symmetric (same X25519/HKDF/AEAD/SAS) and inherits them with one delta worth
naming: under T-P-01, an observer of an **offer** QR gains no decryption capability at all — the
offerer's ephemeral private key never leaves the offerer — so passive observation closes for
confidentiality and degrades to the T-P-08 DoS. The offer-specific threats:

**T-P-06. Unauthenticated offer-response poll.** *(proposal T-P1)*
Actors: AD1–AD5. Assets: A8. Invariants: I1, I5.
`GET /sync/pairing/offer-response?hash=…` carries no credentials — the offerer has none yet. Anyone
holding the hash (e.g. a QR observer) can fetch the stored blob. *Since 2026-10-28* serving an
approval also applies the offerer's queued relay registration (registration on delivery — see the
offer-flow amendment and T-P-16 item 5): a QR observer's poll can therefore trigger that
registration, but only of the key the responder's verified approval named, and only as early as the
offerer's own poll would — before the change the responder's post registered it outright.
Defense: the reply is routing metadata (`ownerId`, `status`), the responder's ephemeral public key,
and AEAD ciphertext sealed under `X25519(privB, PublicKeyA)`; confidentiality rests on `privB`
(never transmitted), integrity on the relay-verified device signature (T-P-07) plus the offerer's
master-key→`ownerId` check on the decrypted payload.
Residual risk: **Accepted** — no plaintext exposure; House Rule #1 preserved.

**T-P-07. Rogue offer-response / key substitution.** *(proposal T-P2)*
Actors: AD3–AD5. Assets: A1–A3. Invariants: I1.
An attacker (or the relay itself) posts a crafted offer-response for a scanned hash, substituting
its own `PublicKeyA'` to become the offerer's counterparty.
Defense: the relay accepts a post only with a valid Ed25519 signature by a **registered realm
device key** over the canonical `status || hash || publicKeyA || payload` bytes
(`MasterDelegationVerifier.offerResponseMessage`); the offerer additionally verifies the recovered
master public key hashes to the claimed `ownerId` and the user compares the SAS (computed over both
ephemeral keys) against the responder's approval prompt.
Residual risk: **Low** — closes on the same SAS-enforcement condition as T-P-02.

⭐ **Amended 2026-08-28 (A1 of `sync-pairing-scanned-announce.md`).** The relay now also accepts
`status: "scanned"` on the same route — the responder's **pre-approval announce** of its ephemeral
key, so the offerer can derive and display the SAS *while the approval prompt is still on screen*
instead of only after approval. Same signature requirement, so a passive QR observer still cannot
post one; ⚠ that is load-bearing rather than incidental, because an unauthenticated announce would
let an observer plant a key on the offerer's screen and show it a SAS matching nothing — a
confusion primitive signing removes for free.

Three properties the relay enforces, each closing a way the announce could otherwise be turned
around:
* **It does not consume the hash** — the terminal approve/deny still arrives and consumes it
  exactly as before (stored in a separate table for that reason). Consuming here would kill every
  handshake at the moment it was scanned.
* **One announce per hash** (409 on a second). The offerer *pins* the announced key; a second
  announce that overwrote the first would re-point that pin at an attacker's key and invert the
  defence it exists for.
* **No announce after the decision** (409). A late announce must not displace the terminal blob
  the offerer is polling for.

⚠⚠ **A1 changes the relay's capability, not yet the user's protection.** Nothing posts an announce
until A2, and nobody sees a SAS earlier until A3; the **key-equality lock** that upgrades this
threat from *detect-after-install* to *reject-before-install* is A2's, not A1's. Until those land,
the residual risk above is unchanged and the shipped post-hoc posture (`pairing-flow.md` §4.5)
remains the accurate description. ⭐ Independently, the responder's SAS now stays on screen at its
terminal (§5.3, shipped 2026-08-28), so the post-hoc comparison is a glance rather than a memory
test — which raises how often the existing tripwire is actually exercised without changing when it
fires.

**T-P-08. Griefing via hash consumption.** *(proposal T-P3)*
Actors: AD3 (holding both the QR and a realm device key), AD5. Assets: availability. Invariants: I6.
The offer-response slot is single-use: a spurious-but-validly-signed post consumes the hash (second
post → 409), forcing the offerer to regenerate its QR.
Defense: producing an accepted post requires an *already-authorized* device of some realm
(T-P-05-adjacent — an insider or stolen-device problem, not an anonymous one); regeneration is
cheap; the single-use marker outlives the 5-minute serving TTL (24 h retention) so a late replay
cannot swap the stored response either.
Residual risk: **Partial / Accepted** — DoS-only, same class as T-P-04. The optional offerer
pre-registration hardening is deliberately deferred (proposal §7 Q1).

*Hub headless variant (hub proposal §11, H2).* The printed-static-key offer variant inherits the
offer-flow threats above with one structural change: the optical channel is **static** (a printed
label), and it authenticates a **dynamic** ephemeral key delivered over the relay
(`/sync/pairing/hub-offer`). The label carries the hub's device id, its long-term Ed25519 verify
key (= its A4 device key), and a long-lived pairing secret. The hub-specific threats:

**T-P-09. Printed-secret longevity / replay window.**
Actors: AD1–AD5. Assets: A8, eventually A1–A3. Invariants: I1, I5.
Unlike an offer QR's per-session secret, the printed secret survives for the device's physical
lifetime (including factory reset). Anyone who ever learned it can compute the rendezvous hash
forever and (a) poll for offers, (b) post junk offers.
Defense: knowledge of the secret grants **no decryption or admission capability** — the pairing
payload is sealed under the fresh ECDH shared secret, and a posted offer only pairs if it carries a
signature by the hub's *static private key* (which never leaves the hub). The exposure is bounded
to the **arming window**: offers exist only while the hub is armed (v1: process start while
unpaired / `pair`; a physical arming button on real hardware), each arm mints a fresh ephemeral key,
the mailbox entry is single-fetch (consumed) with a ≤7-minute TTL, and the return leg is keyed by
the *session-scoped* `SHA-256(secret || ephemeralPub)` so a recorded past exchange addresses
nothing current. `--rotate` re-provisioning voids a leaked secret entirely.
Residual risk: **Low** outside the arming window; see T-P-10 for inside it.

**T-P-10. Label photographed by a visitor (hub capture during the armed window).**
Actors: AD2–AD4. Assets: the hub device itself (unpaired — it holds no realm data yet).
Invariants: I1 at the moment of admission.
An attacker who photographed the label races the legitimate owner while the hub is armed: they
fetch the signed offer, verify it (it is genuine), and *approve from their own realm* — the hub
would install the attacker's keys and join the attacker's realm.
Defense: arming requires **possession, not sight** — the hub only posts an offer when armed
(process start / button), which the owner does immediately before pairing; the window is minutes
and single-fetch (the legitimate phone's fetch consumes the offer, and vice versa a hijacked fetch
leaves the owner's phone reporting "not armed" — a visible anomaly, not a silent success); the
phone-side approval prompt names the hub and says "approve only if you're setting up this hub
yourself". Worst case is capture of an **empty** device — recoverable by wipe + re-provision
(`--rotate`) — never exposure of the owner's data.
Residual risk: **Low/Accepted** — bounded to onboarding-time DoS/capture of a data-less device;
the GPIO arming button on production hardware narrows it further.

**T-P-11. Relay substitutes the hub's offer (rogue ephemeral key).**
Actors: AD3–AD5. Assets: A1–A3. Invariants: I1.
A compromised relay (or anyone who guessed the hash) swaps the stored offer for one carrying an
attacker ephemeral key, hoping the phone seals the `PairingPayload` to it.
Defense: **closed by the printed verify key** — the phone verifies the offer's Ed25519 signature
(over the domain-separated, length-prefixed `hubOfferMessage` bytes, binding the ephemeral key,
device id, and name) against the key on the *physically scanned label*, and additionally requires
the offer's device id to equal the printed one. The relay cannot mint a signature that verifies
against material it never held. Verification failure is a hard client error ("this code doesn't
match this hub"), never a retry-through. *Since F7 (T-P-16)* the name rides the offer sealed under a
key derived from the printed secret, bound to the ephemeral key and device id; the phone unseals it
and then verifies the signature over the clear name, so a blob that does not open is the same hard
error.
Residual risk: **Low** — equivalent in strength to T-P-02's SAS closure, without the human
comparison dependency.

**T-P-12. SAS replacement (consent-ceremony delta).**
Actors: AD2–AD5. Assets: A1–A3. Invariants: I1.
The hub cannot display an SAS, so the simultaneous-comparison ceremony of T-P-02/T-P-07 is
unavailable — is the approval still anchored?
Defense: the ceremony is **replaced, not dropped** (hub §11.4): the printed-key signature
verification supplies the machine-checkable channel binding the SAS supplied (stronger, in fact:
not skippable by a hasty user), the approval prompt names the device from the *signed* offer and
carries hub-specific copy, and the hub's terminal/LED state confirms the outcome on the hub side.
The join/offer flows keep their SAS unchanged — this delta applies only to the `tenshu://hub`
role host.
Residual risk: **Low** — the human factor moves from "compare six digits" to "scan the right
physical label", which is the possession property the flow is built on.

**T-P-13. Peer wedges a host's pairing session with an underivable-SAS request.**
Actors: AD2–AD5 (anyone who has seen the QR), and equally a merely-broken client.
Assets: availability. Invariants: I6.
`POST /sync/pairing/request` is necessarily unauthenticated — an unpaired joiner holds no
credentials — and neither the relay nor the client validates the X25519 public key on it. The host
derives a SAS per pending request; a key that is the wrong length or a small-order point makes that
derivation throw. That throw used to escape the *whole* `GetPendingPairingRequests` op, so a single
junk request turned the host's entire poll into a failure and hid the legitimate joiner — the
T-P-04 effect, reached without controlling the relay, by anyone who scanned the code.
Defense, in two layers:
1. **Relay** — `POST /sync/pairing/request` rejects a `publicKey`/`signingPublicKey` that is not
   base64 of exactly 32 bytes with a 400, so an ordinary hostile or broken peer never reaches a
   host and nothing junk occupies the mailbox. (It also stops malformed base64 throwing out of
   `Base64.decode` as a 500 — the caller's fault reported as the server's.)
2. **Host** — and this is the actual defence: it derives each request's SAS independently and
   **drops** any request whose SAS cannot be derived (logged), so every other request stays
   visible. ⚠ Layer 1 is defence in depth only. House Rule #1 makes the relay untrusted, so a
   compromised relay forging requests (T-P-03) bypasses it entirely; the host must survive
   whatever arrives regardless of what the route allows.
Dropped rather than shown with an empty SAS deliberately: the SAS is this ceremony's MITM defence,
and a device the user cannot verify must not be offered for approval (T-P-02, T-U-03).
Pinned by `PairingRequestValidationTest` (layer 1) and `MalformedPairingRequestSystemTest`, which
covers both — the route turning a bad key away, and both throw shapes injected *past* it straight
into the relay's mailbox, modelling T-P-03.
Residual risk: **Low.** A compromised relay can still spend the host's CPU on requests that get
dropped (ordinary T-P-04-class DoS, accepted); it can no longer deny the host its legitimate
requests.
⚠ Operationally: **no `RELAY_PROTOCOL_VERSION` bump** — a conforming client is unaffected, since
only input that was already broken changes outcome (previously a 500 or a wedged host). Layer 1 is
inert until the relay is redeployed; layer 2 protects already-deployed hosts in the meantime, which
is the correct order of reliance anyway.

**T-P-14. Request substituted between the SAS and the approve (join flow).** *(found and fixed
2026-10-02)*
Actors: AD5 (the relay); AD3–AD4 who know the joiner's `deviceId`. Assets: A1–A4, A33.
Invariants: I1.
The host learns of a joiner from `GET /sync/pairing/requests`, derives the SAS from that response's
Public Key B, and shows it. The user compares and taps Approve, which carries only a `deviceId`.
⚠⚠ **The defect:** approve fetched the request list *again* and used the first entry whose
`deviceId` matched — its Public Key B for the key agreement that seals the `PairingPayload`, its
signing key for the master/A33 delegation and the synced `Device` row, its name for that row — and
did not re-apply the pairing-secret filter the poll applies. Two ways to exploit it, neither of
which the SAS detected, because both screens showed the genuine code throughout:
* **The relay** answers the poll with the genuine request and the approve's fetch with its own keys
  under the same `deviceId`.
* **Anyone** who knows the `ownerId` and the joiner's `deviceId` posts a request for that `deviceId`
  under an arbitrary secret *before* the joiner does. `POST /sync/pairing/request` is
  unauthenticated and the per-owner list is oldest-first; the entry never reaches the host's screen
  (wrong secret) and was approve's first match. Deterministic — no race to win.

What it yielded: the host signed an admission delegation over the attacker's signing key and wrote
it into the realm as an authorised `Device` (A4), and sealed the realm keys plus a freshly minted
admission capability (A1–A3, A33) under `X25519(privA, attacker key)`. ⚠ Opening that blob also
needs the host's ephemeral **public** key, which travels only in the QR — so full key disclosure
needed the substitution *and* sight of the QR (the T-P-01 observer, which this model assumes is
cheap); a relay acting alone obtained the rogue admission but not the plaintext. Nothing downstream
caught either: the relay verifies the delegation the host itself signed; the genuine joiner either
fails to decrypt (reads as a flaky pairing, after the blob is already out) or — if only the signing
key was swapped — pairs normally.
Defense, by construction rather than by check:
1. **The pin.** When the poll derives a SAS for a request it records, on the live `PairingContext`,
   the tuple that SAS stands for: `deviceId`, Public Key B, signing key, device name. A request the
   host cannot derive a SAS for is neither shown nor pinned (T-P-13).
2. **Approve admits the pin and nothing else.** No pin for the `deviceId` in the *current* session
   → refused, nothing sealed, nothing posted, no row written. Otherwise the key agreement, the
   delegation and the `Device` row all use the pinned values. Approve makes no request-list fetch,
   so there is no later relay response to be wrong about.
3. **First shown wins for the life of the session.** A later request for an already-pinned
   `deviceId` with different keys is withheld from the UI and logged; one with the same keys is
   re-served *from the pin*, so the name on screen cannot move either. The UI replaces its request
   from every poll, so a poll that re-pointed the pin would leave code X on screen over key Y for
   as long as its answer was in flight; a pin that never moves cannot. The pin is taken with a
   compare-and-set, so two overlapping polls agree on one tuple.
4. **The pin dies with the session**, being a field of the context that teardown discards. The
   poll reads the context before its relay round-trip and returns nothing if the session ended
   meanwhile, so an answer that arrives late is neither shown nor pinned into the next session.
   (`HostPairingMonitor`'s `sessionEpoch` is the UI-side half of the same rule — it refuses to
   *publish* such an answer; the two are independent and neither relies on the other.)
⚠ What the pin does **not** do is decide who is genuine. If the attacker's request for a `deviceId`
reaches the host first *under the right secret*, it is the attacker's code that is shown — which
does not match the joiner's screen, and the human comparison is what rejects it (T-P-02, T-U-03).
⭐ **The SAS now covers the joiner's identity too — closed 2026-10-03.** As first fixed, the pin
made the admission use the request *as first shown*, but it could not make a relay's first answer
honest: v1 covered the two X25519 keys only, so a relay serving the genuine Public Key B with **its
own signing key** (or another `deviceId` or name) from the first poll onwards produced a matching
code and a delegation over its key. Bounded — the realm keys still went only to the genuine joiner
— but it let the relay plant a `Device` row whose key `HlcDivergenceTracker` trusts for that
device's signed HLC tips and `ReadmitDevice` would re-delegate. **Fix (SAS v2):**
`deriveJoinPairingSas` = HKDF-SHA-256(ikm = shared secret, salt = Public Key A ‖ Public Key B,
info = `"tenshu/pairing/sas/v2"` ‖ lp(signing key) ‖ lp(utf8 `deviceId`) ‖ lp(utf8 name), 4) with
`lp(x) = be32(len x) ‖ x`, truncated as v1. The joiner computes it over exactly what it posts
(`InitiatePairing`); the host over the tuple it pins (`GetPendingPairingRequests`). Any change to
any of the four fields changes the host's code and not the joiner's.
⚠⚠ **Rollout: lockstep** (maintainer, 2026-10-03). No version field in the QR or the request and
no dual computation: the host never derives the v1 code for a join request. A version the relay
carries would be a downgrade lever (it would simply claim "old"), and one in the QR informs one
direction only. So a v1 device paired against a v2 device **fails closed** — the codes differ —
and every join-flow code screen (Compose host dialog and joiner step, the SwiftUI twins, the web
host dialog and joiner screen) carries the same line: *"If the codes don't match, don't continue —
make sure both devices are on the latest version of Tenshu."* The cost is that line plus a
confusing first mismatch for anyone mid-upgrade; the alternative was a fallback the relay could
choose.
The **offer flow keeps v1** deliberately: its offerer's signing key and `deviceId` ride the scanned
QR, never the relay, so the scan authenticates them and v1 already covers everything the relay can
touch; moving it would buy nothing and break offer pairing across versions too. The hub flow has no
SAS (T-P-12) and is unaffected.
The honest cost: a joiner that re-mints its ephemeral key mid-session (cancel, then scan the same
code again) is shown under its *first* key and its codes will not match until the host starts a new
session. That was already true before this change — approve picked the first entry too.
Pinned by `PairingApproveBindsVerifiedKeysTest` — a scripted relay that swaps both keys, only the
signing key, the name, the list order, and the secret, against the real poll and approve ops; and
for v2 the same test computing the joiner's code with the real `InitiatePairing`, `PairingSasTest`
(vectors from an independent HKDF), and `PairingSystemTest` over real X25519 and the real relay.
Residual risk: **Low**, on the same SAS-enforcement condition as T-P-02. Remaining: T-P-04-class
denial (withholding or pre-empting a request), and T-U-03 (approving without comparing).
⚠ Not verified on hardware: the binding is unit-tested against the real ops with stub crypto; a
two-device pairing over a live relay was not run for this change, nor for SAS v2 (where it matters
more: the two sides run on different platforms, and the vector test is the only cross-platform
check that runs in CI).
*Other flows, checked for the same shape (verify one thing, then admit from a later fetch):* the
**offer flow** takes the offerer's keys, device id and name from the scanned QR into the responder
session at `RespondToPairingOffer` and approves from that session — no second fetch; its offerer
side has the T-P-07 key-equality lock. The **hub flow** fetches the offer once, verifies it against
the printed key, and stores it in the same session. **Delegated admission** (§8.17) runs through the
same two approve ops and inherits the fix. **Deny** carries no key material in either direction
(join: it drops the context and its pins; offer: a signed `denied` from the stored session).
⚠⚠ The **contact handshake** (§8.13) is split. Its *invitee* side stores the link-pinned inviter
key and refuses an ack that does not echo it, and post-handshake *re-verification* derives its code
from a locally stored key row addressed by that key's own fingerprint and records the verdict on
that row — neither has the shape. Its *inviter-side completion* **did**: see **T-CI-10**. The
**membership grant** (§8.14) has no human comparison to outrun — the sealed grant is verified
(address, A13 seal, grantor fingerprint) on the bytes it then consumes. **EPC app pairing** (§8.16)
derives its SAS from the handshake that establishes the very session it then serves.
⚠ One more caller, found the same way: the device list's *"Pending approval"* row
(`SyncDashboardViewModel.approveDevice`) calls `ApprovePairingRequest` with a bare `deviceId` and
no code on screen. It used to admit whatever the relay named for that id; it is now refused unless
this session's poll has already pinned — and so shown — that request.

**T-P-15. Unauthenticated pairing requests exhaust the relay's memory, or a host's 32 slots.** *(found 2026-10-13)*
Actors: AD2–AD5, and anyone at all for the first half — no QR is needed.
Assets: availability (the relay process, and through it every realm on it). Invariants: I6.
`POST /sync/pairing/request` is credential-less by necessity, and its pending requests live in the
relay's memory keyed by the **caller-supplied** `ownerId`. The 2026-08-28 fix capped each owner's
list at 32 (oldest evicted first) and stopped there: nothing expired an entry, nothing removed an
owner, and nothing checked that the owner existed. A caller sending a fresh random `ownerId` per
request therefore grew the map without bound — one entry per request, each as large as the body
it chose to send — until the relay died. T-P-13 does not cover it: that threat is about the *shape*
of one request, this is about how many, addressed to whom.
Defense, on the relay (there is nothing a client can do about the relay's heap):
1. **Known owner only.** A request for an owner with no registered device is refused with a 404.
   Such a request is unreadable by construction — `GET /sync/pairing/requests` needs a device JWT
   for that owner — so the refusal costs no legitimate flow, and it ties the map's size to the
   realm set, which the genesis limiter already bounds. ⚠ Not skipped lightly in dev mode: there
   `X-Dev-Owner` replaces registration and the registry is empty, so the check would refuse
   everything.
2. **A TTL.** An entry is neither served nor kept past 7 minutes (`sync-relay-api.md` §3.3's
   five-minute window plus the offer stores' grace), and an owner with nothing pending holds no map
   entry. Swept on the write path, which is the only place this state grows.
3. **A size cap** of 256 characters on each stored string — the count bounds mean nothing if one
   entry may be megabytes.
4. **A per-IP rate limit** (`PAIRING_REQUEST_RATE_LIMIT_PER_HOUR`, default 120), which is the
   answer to the *other* half: an attacker who does hold a QR and posts faster than the user can
   approve, pushing the genuine request out of the 32 slots.
Pinned by `PairingRequestInboxTest` (time and owners) and `PairingRequestAbuseBoundsTest` (the
route's three refusals, run with `devMode = false`).
Residual risk: **Low.** A distributed sender who knows a victim's `ownerId` can still crowd that
one owner's list from many addresses (T-P-04-class, and the SAS means a crowded list admits
nobody). ⚠ The 404 tells a caller whether an owner id is registered. That is not treated as a
leak: the id is derived from a master key, is not guessable, and is already known to anyone holding
one. ⚠ The same class on `POST /sync/pairing/hub-offer` is bounded by that store's own TTL and
single-entry-per-key shape, and was not re-audited here.
⚠ Operationally: **no `RELAY_PROTOCOL_VERSION` bump** — a conforming joiner addresses a registered
owner, posts once, and sends short strings. Inert until the relay is redeployed.
⚠ *Amended 2026-10-28 (F7):* item 3's cap now applies to `ownerId` and `deviceId`; the name is a
sealed blob capped at 1 KiB decoded (`MAX_SEALED_DEVICE_NAME_BYTES`), and `pairingSecretHash` must be
64 lower-case hex. The hub offer's sealed name has the same cap.

**T-P-16. The relay learns the join secret, device names, or holds pairing state indefinitely.**
*(F7 of [`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md), fixed
2026-10-28)*
Actors: AD4, AD5. Assets: A6, A8. Invariants: I5.
The join flow sent the **raw** pairing secret on `POST /sync/pairing/request` and `POST
/sync/pairing/response`, served it back on `GET /sync/pairing/requests` in a slot named
`pairingSecretHash` (openapi known issue 4), and carried it in a **query string** on `GET
/sync/pairing/response?pairingSecret=` and in the body of the ack. The joiner's device name and the
hub's travelled in clear. Requests had a TTL (T-P-15); responses had none. A relay operator — or
anyone reading its logs or memory — therefore held, per pairing, the rendezvous capability for the
response mailbox (peek and ack) and the user's device names, contradicting A6.
Defense (`PairingRendezvous`, client-side; the relay only validates shapes):
1. **Hashed rendezvous.** Every join route is addressed by
   `pairingSecretHash = hex(SHA-256("tenshu/pairing/join/rendezvous/v1" || 0x00 || utf8(secret)))` —
   the offer/hub construction with a domain prefix, so it equals no other hash the platform
   publishes. The relay refuses a body carrying `pairingSecret` and any key that is not 64 lower-case
   hex, so a stale client fails loudly instead of leaking.
2. **Sealed names.** `sealedDeviceName = XChaCha20-Poly1305(K, pad64(be16(len) || name), aad)` via
   `encryptWithAad`, with `K = HKDF-SHA-256(ikm = secret, salt = ∅, info =
   "tenshu/pairing/join/device-name/v1" | "tenshu/pairing/hub/device-name/v1", L = 32)` and
   `aad = lp(pairingSecretHash) || lp(deviceId) || lp(publicKey) || lp(signingPublicKey)` (join) or
   `lp(ephemeralPub) || lp(deviceId)` (hub). ⚠ The join AAD binds **every key the host will trust**:
   with `deviceId` alone the relay could repost a genuine sealed name under its own X25519/Ed25519
   keys, the host would open it, and — the first-seen pin per `deviceId` (T-P-14) winning — the
   forged copy would mask the genuine request until the user rejected it (caught by the SAS, but a
   denial of service). The hub AAD already covers the only key the relay carries (the ephemeral key;
   the verify key comes from the label). The relay never holds the
   secret, so it cannot derive `K`. A blob that does not open — another secret, another `deviceId`,
   keys swapped, tampered — is dropped by the host exactly like an underivable-SAS request (T-P-13), and refused by
   the phone exactly like a bad hub signature (T-P-11). Integrity of the shown name still rests on the
   SAS v2 transcript (join) and the printed-key signature (hub), which both cover the clear name.
3. **No capability in a URI.** The joiner's poll is `POST /sync/pairing/response/peek` with the hash
   in the body; the old `GET` route is removed, not kept beside it.
4. **TTLs consistent with the offer flow.** Requests and responses are served and kept for 7 minutes
   (`PAIRING_REQUEST_TTL_MS` = `PAIRING_OFFER_RESPONSE_TTL_MS`); an ack retires the hash for 24 hours
   (`PairingOfferRegistry.CONSUMED_RETENTION_MS`), checked before the response route registers
   anything.
5. **Registration on delivery** *(hardening follow-up, 2026-10-28)*. The response route verifies the
   delegation, revocation list and removal ledger as before but no longer registers the joiner's
   signing key; the verified registration is stored with the response and applied by the joiner's
   first `peek`, before the bytes are served (removal ledger re-checked there). A response that
   expires unfetched now leaves nothing in `DeviceRegistry` — before, it left a device whose key was
   never delivered, which never acks a tombstone and so held the realm's tombstone-GC quorum
   (`MailboxStore.gcSweep`) open for good. A restart loses response and registration together, and
   account erasure (F12) drops the owner's queued responses so a late peek cannot re-register into
   an erased realm. *The offer flow does the same since 2026-10-28* (`GET /sync/pairing/offer-response`
   applies the registration queued on the approved row, `pairing_offer_response.reg_*`; account
   erasure drops the row). ⚠ A queued admission still **orders `Device` tombstones**:
   `DeviceRevocation` consults the pending stamps (both queues), so a tombstone authored before the
   admission but delivered while it waits is stale exactly as when the post registered — otherwise it
   would write the removal ledger and the delivery would refuse the re-admitted device for good. The
   peek is unauthenticated, but it can only apply a registration a realm device's verified post
   queued, and only for the hash's holder.
6. **One admission per pin; "consumed" is delivery only for our own material** *(2026-10-28)*. The
   host mints the admission (sealed blob + delegation + the joiner's fresh A33 capability) once per
   pinned request and reuses it for every approve of that pin in the session, and reads a `409
   pairing-response-consumed` as delivered only when every earlier post under the session's hash
   was that pin's. Before, an approve whose retry met an earlier approve's ack recorded on the
   `Device` row an A33 capability the joiner never received, so a key-scoped revocation (F0/A′)
   would have named the wrong capability. Delegated (A33-only) admitters now retry a failed post
   like A1-holders (without the genesis re-bootstrap they cannot do).
⚠ **EPC (checked, open question in the F7 audit §10):** no join-flow secret feeds
`deriveEpcRendezvousSessionId`. The EPC address is HKDF'd from a separate 128-bit secret minted by
`DefaultEpcPairingHost` and carried only in the `tenshu://epc/pair#…` fragment, which never reaches
the relay; so the relay could not compute EPC session ids before this change either, and still
cannot.
Pinned by `PairingRendezvousTest` (real AEAD: opacity, binding, padding), `PairingRequestValidationTest`,
`PairingResponseInboxTest`, `PairingResponseMasterDelegationTest`, `HubOfferRoutingTest`, and
`PairingSystemTest.theRelayNeverSeesTheJoinSecretOrTheClearDeviceName` (a wire spy over the whole
flow plus the stored rows).
Residual risk: **Low.** The relay still sees, per session, an unlinkable random hash, the joiner's
`deviceId` and public keys, which 64-byte bucket the name falls in, timing and IP. ⚠ A party that
**holds the secret** (a QR observer, T-P-01) can read and forge the sealed name; forging it changes
the SAS (T-P-02), so it is caught by the same comparison as before. ⚠ Pre-release, in place: **no
`RELAY_PROTOCOL_VERSION` bump**, so relay and clients move together.

**T-P-17. The relay tracks one hub across its re-arms.** *(F7, accepted residual; hash
domain-separated 2026-10-28)*
Actors: AD4, AD5. Assets: A6.
A hub's printed secret is long-lived by design (T-P-09), so the key of every
`/sync/pairing/hub-offer` it posts is a **stable identifier**: the relay can link every arm of one
hub across its lifetime, with the IPs and times it armed from, and the moment it was consumed. F7
sealed the hub's *name* (T-P-16), so the label text is no longer relay-visible; the linkability is
untouched.
The key was a bare `SHA-256(printedSecret)` — the one rendezvous hash on the platform without a
domain label, so it could equal another context's SHA-256 of the same bytes. It is now
`hex(SHA-256("tenshu/pairing/hub/rendezvous/v1" || 0x00 || printedSecret))`
(`PairingRendezvous.hubOfferHash`), the join hash's construction under its own label (T-P-16).
That is hygiene, **not** a linkability fix: the derivation is still a function of the label alone.
Also hygiene, same day: the phone's fetch carried that stable key in a **query string**
(`GET /sync/pairing/hub-offer?hash=`). Queries were already logged nowhere (relay route templates,
Caddy drops the query), but a stable identifier has no business in a URI that any future proxy,
CDN or debug log could record, so the fetch is now `POST /sync/pairing/hub-offer/fetch` with the key
in the JSON body and the `GET` is removed (the `/response/peek` precedent, T-P-16 item 3).
The label carries the **secret**, so printed labels keep working; hub and phone derive the hash
independently and must run builds from the same side of the change (pre-release, in place — no
`RELAY_PROTOCOL_VERSION` bump; the relay treats the key as opaque and needs no change).
Defense: none in the protocol — a printed label cannot rotate, and a rendezvous the phone can find
from the label alone must be derivable from the label alone. `--rotate` re-provisioning gives the hub
a new identifier (and a new label). The session-scoped return leg `SHA-256(secret || ephemeralPub)`
is already unlinkable across arms.
Residual risk: **Accepted.** Bounded to *one hub's arming history*; the hash names no realm and no
user until the hub joins one, at which point the relay learns that via the hub's registration.

### 8.2 Relay transport

**T-T-01. Passive logging of all relay traffic.**
Actors: AD4, AD5.
Defense: All payloads carrying user data are encrypted end-to-end under keys the relay does not
hold. Metadata (A6) is exposed; see T-T-07.
*Our own logs* (relay-metadata-minimisation F4, 2026-10): several relay routes carry a
**capability** in a path segment — share id, grant-mailbox address, EPC session id, invite id,
content ref — and a leaked or subpoenaed log would hand each one over. The relay's call log and
error lines name the matched **route template** (`/share/{shareId}`), never the path; Ktor's own
engine failure line is masked to `<path>` by the logback pattern; and the Caddy access and error
logs rewrite the same segments before writing, with a backstop for any 20+-character segment
(`CaddyLogFilterTest` checks every real route against them). Secrets and query strings were
already never logged. Policy and the route list: [`server-logging.md`](../how-to/server-logging.md).
⚠ Not covered: a library's exception message or stack trace that quotes a path.
Residual risk: **Low** for confidentiality of A5. **Accepted** for metadata.

**T-T-02. Relay injects or modifies blocks.**
Actors: AD5. Invariants: I2, I3.
Defense: Every block is signed by its author device's signing key (A4). Modification by the relay
produces an invalid signature. Receiving devices reject blocks that do not verify against a
currently-trusted signer.
Residual risk: **Low.**

*Amendment (relay-metadata-minimisation.md D13 item 1 / finding Fx, 2026-10-26: corrects the
Defense above to match the code).* ⚠⚠ **There is no block signature.** Neither `SyncBlock` nor
`DeleteBlock` has a signature or MAC field, and ingest verifies none
(`SyncProcessor.processSyncBlock` / `processDeleteBlock`). What actually holds:
- **A `SyncBlock`'s payload** is AEAD under the realm key (`SyncCryptoManager.decrypt(cipher, key)`).
  The relay holds no realm key, so it **cannot forge or modify a payload**: a tampered ciphertext
  fails to decrypt and is dropped. That part of the Defense holds, through AEAD, not a signature.
- ⚠ **The envelope fields are not bound to the payload.** The AEAD takes no associated data, so
  `hlc`, `targetEntityIdHash` and `blockId` sit outside its protection. The merge orders by the
  envelope's `block.hlc` (`resolveConflictAndSave`). [I] So an active relay can re-stamp a genuine
  ciphertext with a later HLC, or replay an old one with a fresh HLC, and roll an entity back to
  any value it has ever held. It cannot invent a value. Not yet confirmed by a test.
- ⚠⚠ **A `DeleteBlock` has no ciphertext at all**, so nothing about it is protected. An active
  relay can mint a tombstone for any entity whose keyed wire id it has stored (it has them all),
  with any HLC. `processDeleteBlock` applies one after HLC checks alone. [I] With an HLC above the
  entity's last write it deletes the entity on every device, as if the user had; content reclaim
  can then free the bytes. That is durable data loss, not a withheld or stale view. (A recreate is
  not shadowed for long: ingest first `observe()`s the HLC, pulling each device's clock past it.)
- ⚠ **No bound on a received HLC.** `HybridLogicalClockManager.update` takes
  `max(local, remote, now)` with no drift limit, and every ingest calls it before any check. [I] So
  one block or tombstone stamped far in the future, from the relay or any realm device, drags every
  device's clock there for good, and everything time-based that reads HLC physical time inherits the
  skew.
Residual risk: **High** for integrity of deletes, against an active AD5. **Medium** for rollback
of `SyncBlock`s. **Low** for forging or modifying payload content. The confidentiality story (I1)
is unaffected. Tracked as finding Fx in
[`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md) (§5, D4, D13
item 6). The fix is tombstone authentication (a MAC or sealed body under a realm key) plus binding
the envelope fields into the AEAD as associated data. Both are envelope reshapes that need a
`RELAY_PROTOCOL_VERSION` review, and are cheapest before the first public release.

*Amendment (envelope authentication, [`sync-envelope-authentication.md`](../proposals/sync-envelope-authentication.md),
2026-10-27, part of the relay-protocol-1 release baseline).* Both gaps above are closed:
- **Tombstones are authenticated.** A `DeleteBlock` carries `authTag = HMAC-SHA256(K_tomb,
  canonical(ownerId, blockId, hlc, targetEntityIdHash, targetEntityType, revokedDeviceId))`, with
  `K_tomb` an HKDF child of the realm identity key the relay does not hold. Ingest verifies it as the
  **first** step of `processDeleteBlock` — before the HLC is observed and before the tombstone is
  recorded — and drops a tombstone that fails, so a forgery deletes nothing, moves no clock and no
  realm epoch. The relay still reads every field in the clear (it needs the Device bit,
  `revokedDeviceId` and `hlc`), but can no longer mint or alter one.
- **A `SyncBlock`'s envelope is bound.** Its payload is sealed with `SHA-256(canonical(ownerId,
  blockId, hlc, targetEntityIdHash))` as associated data, so a re-stamped or re-targeted ciphertext
  fails to open, and the HLC is observed only after a successful open.
- ⚠ What remains: an AD5 relay can still **destroy or withhold** (T-T-04, availability), and can
  still **replay a genuine envelope verbatim** — harmless by construction, since ingest is
  idempotent and order-independent. Any holder of the realm key can author a valid tombstone or
  block (T-K-01 / T-K-05). The unbounded received-HLC update above is a separate fix (a drift bound),
  but its relay-forged trigger is gone: only an authenticated envelope reaches `observe`.
Residual risk: **Low** for integrity of deletes and for `SyncBlock` rollback against an active AD5
(down from High / Medium). **Low** for payload forgery (unchanged).

**T-T-03. Relay impersonates a device.**
Actors: AD5. Invariants: I3.
Relay constructs blocks and claims they came from Device X.
Defense: Same as T-T-02. The relay does not hold any device's private signing key.
Residual risk: **Low.**

*Amendment (Fx, 2026-10-26).* The Defense holds for `SyncBlock` payloads, but for a different
reason: the relay holds no **realm key**, so it cannot produce a payload that decrypts. It does not
hold for tombstones, which the relay can mint outright (T-T-02 amendment). ⚠ Device attribution
is weaker than "signed by Device X" implies. Nothing on the wire binds a block to its author: the
envelope's `hlc.deviceId` is unauthenticated, and any holder of the realm key, i.e. any admitted
device, can author a block that names another device. Residual: **Low** for payload
impersonation by the relay; **High** for tombstones (as T-T-02).

*Amendment (envelope authentication, 2026-10-27).* Tombstones now carry a realm-key MAC and block
envelopes are bound (T-T-02 amendment), so the relay can impersonate **neither**. ⚠ Attribution is
unchanged: the MAC and the binding prove *realm-key holder*, not *which device* — `hlc.deviceId` is
now authenticated against the relay but still chosen by whichever admitted device wrote it.
Residual: **Low** for both payloads and tombstones against the relay.

**T-T-04. Relay withholds blocks (silent censorship / forked view).**
Actors: AD5. Invariants: I6.
Relay serves device A a view that omits blocks from device B.
Defense: HLCs are monotonic and total-ordered within a realm; devices periodically exchange
(signed) HLC tips through the relay and compare. A relay serving a forked view causes the
tips to disagree, which is detectable. **HLC-tip piggyback on `POST /sync/changes` landed
2026-05-05** ([`sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md) §5.1):
client-side `HlcDivergenceTracker` verifies each peer tip's signature against the peer's
`Device.publicKey` and emits `DivergenceWarning.Regression` when a peer tip's HLC goes
backward (the "forked view" tell). Surfaced via `SyncManager.divergenceWarning: StateFlow`,
and rendered by the desktop tray as `SyncStateCode.DIVERGENCE`.

⚠⚠ **The tip is a property of the device's *store*, not of the process that publishes it**
([`sync-hlc-tip-device-identity.md`](../proposals/sync-hlc-tip-device-identity.md), TC-2). It was
built from `HLCManager.current()` — a per-process, zero-initialised clock — while the relay keeps
one slot per `(ownerId, deviceId)` and overwrites it unconditionally. A device running several
runtimes under one deviceId (iOS route-1's per-app runtimes; two web tabs of one origin) therefore
published a non-monotone sequence into that slot, and *this detector read its own architecture as
the attack*. Confirmed live 2026-08-28: a 16-minute backward tip from an iPhone with sync fully
healthy. The tip is now merged through a durable `HlcWatermarkStore` co-located with the store it
describes, so a device's published position is monotone across its runtimes and survives a restart.

⚠ **A watermark must never be shared more widely than the store it describes.** An over-wide one
makes a device claim it observed HLCs its store never saw, which is a *forgery of the ack* T-T-08
depends on. This is why the implementation keys off the object store's own location resolution
rather than off `SecureStorageDelegate`, whose sharing differs per platform. On an iOS build with
no App-Group container the first-party apps share a deviceId but **not** a store; the per-app
watermark shipped there is narrower than the identity and therefore honest, at the cost of leaving
the false positive in place until TC-2b.

**False-positive cost is a security cost.** A detector that fires on ordinary multi-app use trains
users to dismiss it, so the residual risk below is about detection that is *believed*, not merely
present.
Residual risk: **Low** with detection in place and the multi-runtime false positive removed.

**T-T-05. Relay reorders blocks to cause causal anomalies.**
Actors: AD5. Invariants: I2.
Defense: HLCs carry physical time + logical counter + device ID. Out-of-order delivery is handled by
the client's merge logic; ordering attacks cannot change the final merged state because merge is
commutative under HLC. Ordering attacks can only change *perceived* intermediate states.
Residual risk: **Low.**

**T-T-06. Session token theft.**
Actors: AD1–AD5. Assets: A7. Invariants: none (A7 is not load-bearing).
A stolen session token lets an attacker post blocks to the relay, but not decrypt them or sign as a
legitimate device. The worst they can do is fill storage quotas.
Defense: Session tokens are bound to a device signing key at issuance; the relay accepts posted
blocks only when signed by a device currently registered under that session. Stolen tokens without a
stolen signing key are useless.
Residual risk: **Low.**

**T-T-07. Metadata analysis.**
Actors: AD4, AD5.
What leaks: who syncs with whom, when, how often, block sizes, device counts. Per
[`design.md`](design.md) §2.2 + [ADR 0007](../adr/0007-sync-device-management.md),
device names are no longer relay-visible (they live inside encrypted `Device` entity blocks) —
and since F7 not on the pairing path either (T-P-16).
Timing correlation can still reveal editing patterns.
Defense: Partial. Block-size padding to fixed buckets can reduce content-size
inference; timing is harder (cover traffic is an option but expensive). See §10.
Residual risk: **Accepted, documented.** Full metadata privacy is not a v1 goal.

*Amendment (`relay-metadata-minimisation.md` F10 / D8, 2026-10-28): what "block sizes" means, in
numbers — and that nothing is padded.* The "padding to fixed buckets" above is a **possible**
defence, not a built one: no ordered-plane or content-plane ciphertext is padded today (the only
padded seal in the sync stack is the Mode-A share descriptor's `sealedMeta`, §8.10). Concretely, the
relay learns:
- **Ordered plane.** A `SyncBlock`'s ciphertext length is its sealed plaintext
  (`BlockPayloadEnvelope`: version stamp + payload composite, `sync-service-common/.../BlockPayloadEnvelope.kt:14`)
  plus a 24-byte nonce and a 16-byte tag (`AeadBackend.kt`) and the length-framed envelope AAD
  (`EnvelopeAuth.sealSyncBlock`), whose fields the relay already holds. So the relay can subtract the
  overhead exactly and sees the size of every row write, which separates, say, a thumbnail-bearing
  row from a preference toggle.
- **Content plane.** Plaintext is cut on a fixed **2 MiB** stride (`FileContentChunkSizeBytes`,
  `core-common/.../WholeFileHash.kt:37`). Every chunk but the last carries exactly 2 MiB, and each
  chunk's ciphertext is that plaintext plus a fixed overhead (nonce + tag, plus the length-framed
  `fileChunkAad` that `encryptWithAad` seals *inside* the ciphertext). The relay stores each chunk's
  length (`content_blob.byte_len`) and knows which refs make up one content (`content_ref`), so the
  chunk count gives the size in 2 MiB steps and the last chunk's length gives it **modulo 2 MiB** —
  the file's exact size, to within a few bytes of AAD framing. A file under 2 MiB is one chunk and
  shows its size directly.
- **Shares (Mode A).** Same arithmetic on the share plane's own 2 MiB stride
  (`ShareLinkImplementation.SHARE_CHUNK_SIZE`): `chunkCount` × 2 MiB bounds the size and the last
  chunk's length completes it (§8.10's F3 residual (a)).

**Decision (D8): accepted explicitly, padding deferred** (`relay-metadata-minimisation.md` Phase 7).
If it is built, it goes **inside the AEAD**, as a pad within the sync-owned `BlockPayloadEnvelope` (and
the chunk plaintext), so the relay changes nothing; rolled out reader-first (readers tolerate a pad,
then writers pad, gated on the owning sync service's version). ⚠⚠ It is **never** a
`TENSHU_STORAGE_FORMAT_VERSION` bump — that constant is `:core`'s alone and a bump wipes every local
store (AGENTS.md House Rule 9). The cost is storage overhead on every write. Residual unchanged:
**Accepted, documented**, now with the numbers above rather than the generic "block sizes".

**T-T-08. Relay drops or delays a tombstone.**
Actors: AD5. Invariants: I4, I6.
A hostile relay drops or delays a `DeleteBlock` so target devices keep the deleted
data alive longer than the user intended.
Defense: HLC-tip piggyback (see §11.3, [
`docs/proposals/sync-backend-relay-plan.md`](../proposals/sync-backend-relay-plan.md)
§5.1). A device that has observed HLCs past the tombstone's HLC, but not the
tombstone itself, fires a consistency-warning. Cross-references T-T-04.

⚠ The same tip is the relay's GC quorum input (`MailboxStore.acknowledgementSet` requires
`tip.physicalTime >= tombstone.hlc.physicalTime` for **every** realm device), and until TC-2 it was
process-scoped: a restart published `(0,0)` and reset this device's quorum position, while an
idle-but-online client — one that neither pulls nor writes — never advanced its clock at all and
could hold GC for the realm indefinitely. The watermark is durable, so the ack position now
survives a restart. Note the failure direction: a *deflated* tip stalls GC (safe, wasteful), an
*inflated* one drops bodies a device never applied (unsafe) — which is why the watermark is only
ever raised by what a store has actually observed, and never floored at wall-clock now.
*Amendment (iOS shared replica, [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md)
§8, S0b-4, 2026-11-01; B.22, 2026-11-05):* on iOS the durable watermark is still the shared
container's `sync-hlc/` file beside the replica (TC-2b), also over the SQLite store: the design's
identity-bound copy in the database (§5.4 there) is **not built**, and this amendment claims nothing
of it. **The launch reset keeps the watermark's invariant:** when a phone still holds the shared
file store, the old `objects/` and its `sync-hlc/` are removed **together**, the watermark renamed
aside first and the store last, so a kill at any step leaves the store (which the next launch
resets again) and never the old watermark beside no store; a lone `sync-hlc/` is the SQLite store's
own and is never touched. So the new store never sits beside a watermark it did not observe. Since
B.22 `sync-hlc/` is also **excluded from backup** (T-C-04), so a restore cannot put back a watermark
above what a re-pulling store has applied. Residual (1), **the initial reset:** the new store's
watermark starts empty, so the device's first tip from it
can sit below its relay slot and peers show a temporary `DIVERGENCE` (the T-T-04 tell) for that
device, once, until its first local write or the reset's re-pull carries the tip past the old
value; the tracker then clears itself. Accepted deliberately: carrying the old value could raise a
slot with a value the new store never observed. Residual (2) of TC-2b (a wiped store beside a
watermark that was not reset over-states, until it has re-pulled, what it has applied) is **closed
on every platform** since B.22.6 (430), 2026-11-05: the scheduler neither reads nor raises the
durable watermark until the store has pulled its realm's history (first pull finished and no
backward walk mid-way), so the signed tip until then is the process's own clock. That also covers a
`sync-hlc/` an older-build process made again beside a reset store. Once the history is pulled the
store has applied every tombstone the relay holds, so no stamp in the file can over-state it.
⚠ **The cost, for the whole first pull or re-pull:** the tip is each process's own clock. It can
step backwards against tips already sent, and on iOS every route-1 app writes its own unrelated
clock into the one slot (the TC-2b sawtooth). So peers show the device as `DIVERGENCE` (T-T-04) for
that whole period, not once, and the relay's tombstone-GC quorum stalls on it. Accepted over
publishing a stamp the store never observed. A backward-walk floor counts as pending only while the
device still walks it (a floor left by a relay that lost the merged lane does not hold the watermark
for ever), and the gate is logged once per process (B.22.7). §5.4's identity-bound watermark would still make it strict by construction, and owes its
own amendment.
Residual risk: **Low** with HLC piggyback in place; **Medium** without it.

**T-T-09. The unauthenticated health endpoint is abused as an oracle or an amplifier.**
Actors: AD1, AD4. Surfaces: 19.
`GET /health` is reachable without credentials (a monitor holds no device JWT), and on the relay it
performs a filesystem **write**. Two distinct worries, with different answers.

*Disclosure.* The body is a fixed `status` plus a `durable` flag — no free-space figure, no path, no
exception text, no version. It carries **no user data and no metadata about any owner** (A6 is not
touched: the response is identical whoever asks and whichever realms exist). The only fact leaked is
whether the deployment is durable, which is a property of the operator's config, not of any user —
and an operator running a production relay with `durable:false` has a much larger problem than its
disclosure (registrations are wiped on restart).

*Amplification.* Each relay probe is one 1-byte create + unlink under the blob root, with a
per-request unique name, so it neither accumulates nor collides. That is cheaper than most
authenticated routes and comparable to `GET /`, which is equally unauthenticated — an attacker who
wants to burn relay I/O has strictly better options. ⚠ It is nonetheless **not rate-limited**, unlike
the unauthenticated realm bootstrap (`GENESIS_RATE_LIMIT_PER_HOUR`), because that route *creates
durable state* and this one provably does not. If the health probe ever grows a more expensive check
— free-space stats, a DB round-trip, anything that takes a lock — that reasoning expires and it owes
a limiter.
Residual risk: **Low.**

### 8.3 Relay storage

**T-R-01. Relay reads all stored user data.**
Actors: AD5. Invariants: I1.
Defense: E2EE. Stored blocks are ciphertext. See §8.2 T-T-01.
Residual risk: **Low.**

**T-R-02. Relay reads stored device records.**
Actors: AD5. Assets: A6.
The relay's device registry (`KnownDevicesStore`) carries `deviceId`, the Ed25519 signing public
key and the admission stamp. This is leakage by design (the relay verifies device JWTs against it)
and the relay necessarily sees it.
⚠ *Amended 2026-10-28 (F7), resolving the contradiction with A6:* this entry used to list the
**device name** among the relay-visible fields. The registry never stored one — the name lives in
the encrypted `Device` entity block (A6) — but the pairing path *did* carry it in clear (the join
request and the hub offer), so in practice the relay saw every joining device's name once. Since
F7 it travels sealed on both paths (T-P-16), and **no relay route, row or log holds a clear device
name**. A6 is the statement that holds.
Residual risk: **Accepted.** Documented as part of metadata exposure.

**T-R-03. Relay modifies stored blocks in place.**
Actors: AD5. Invariants: I2.
Defense: Signature verification on read. Tampering produces an invalid signature.
Residual risk: **Low.**

**T-R-04. Relay deletes blocks.**
Actors: AD5. Invariants: I6, affects availability.
Defense: Every device keeps its own copy of blocks it has authored, indexed by HLC. On any sync
cycle, a device can detect that its own historical blocks are missing from the relay's view and
either re-upload or alert the user.
Residual risk: **Low** once self-check is implemented; otherwise **Medium**.

**T-R-05. Relay reads the encrypted recovery-code backup.**
Actors: AD5. Assets: A9.
Stored blob is encrypted under a key derived from a 24-word mnemonic (256 bits; §11.2).
Defense: Mnemonic entropy of 256 bits; KDF tuned for offline brute-force resistance (Argon2id
with parameters sized so that a single guess takes ~1 second on a high-end GPU).
Residual risk: **Low** against AD1–AD5. 256-bit entropy is well outside any feasible brute-force
envelope even under decade-plus adversarial horizons and anticipated compute growth; the
remaining risk is KDF-parameter drift, revisited annually per §12.

**T-R-06. Legal compulsion of the relay operator.**
Actors: AD4 acting through AD5. Invariants: I1, I6.
Relay operator is compelled to hand over all stored data and to serve specific users modified
responses.
Defense: I1 still holds — they hand over ciphertext. I6 can be defeated for a targeted user (the
relay serves them a manipulated view). Consistency checks (§8.2 T-T-04) allow the targeted user to
detect this if they sync with a device that sees a non-manipulated view.
Residual risk: **Medium** for targeted users; **Low** for bulk data.

**T-R-07. Relay tags users for targeted downgrade.**
Actors: AD5.
Relay serves a targeted user a different app binary (if the relay is also the update channel) or
different responses than other users.
Defense: Out of the relay's scope — binary distribution goes through App Store / signed installers (
TA4). A compromised relay cannot downgrade a client to a vulnerable version.
Residual risk: **Low** given TA4; if TA4 is violated (e.g. side-loaded app using the relay as an
update channel), this threat becomes severe.

**T-R-08. Property-ID exposure to relay (closed).**
Actors: AD5. Invariants: I1 (E2EE), I6 (relay never learns user-data semantics).
Earlier versions of the wire schema carried `SyncBlock.targetPropertyId` as a plaintext string
(e.g. `"tenshu/file/file/data"`, `"tenshu/file/file/preview"`) so the relay could index property
blocks for OnDemand fetch. The relay could enumerate the schema vocabulary every user's blocks
referenced, allowing per-user profiling by which property kinds appeared
("this user has files-with-bytes, previews, device profiles, notifications").
Defense: `targetPropertyId` removed from `SyncBlock`'s wire schema. The property identity now
lives inside the encrypted payload as `DataBlockPayload.propertyId`, recoverable only after
client-side decryption. The relay's `fetchProperty(entityHash, propertyId)` API became
`fetchEntityBlocks(entityHash)`: returns every block scoped to an entity, the client filters
locally. Relay-visible wire metadata is now `(blockId, ownerId, hlc, encryptedPayload, targetEntityIdHash?)`.
Residual risk: **Low.** The relay still sees opaque `targetEntityIdHash`es — same opaque-hash
shape that already scoped entity-level fetches.

*Amendment (sync-entity-identity.md EI-1, 2026-09-11): "opaque" was false for most types, and is
now true.* The hash was `"${type}_${key}".hashCode()` — 32-bit, unsalted, over **plaintext**. For
the nine `path`-keyed sources and the five `preferenceId` ones the relay could compute it: a guessed
path hashed to a value it could test against any owner's blocks and tombstones. That is a
**confirmation-of-file oracle**, the exact shape the content plane rejected when it chose
ciphertext-hash refs (ADR 0017 D3). ⚠ It also collided. `n²/2³³` is the **expected number of colliding pairs**, not the probability —
0.024 at 14 208 rows, 0.29 at 50 000, 1.16 at 100 000 — so `P(at least one) = 1 − e^−λ` is
**2.3 %**, **25 %** and **69 %** respectively, against a 107 k-row replica in this repo.

⇒ The wire name is now `HMAC-SHA256(K_entity, identity)[0..16)`, where
`K_entity = HKDF(sharedVolumeKey, "tenshu/realm/entity-id/v1")`. What the relay still sees is
unchanged (A6): that *some* entity exists, when it changed, when it was deleted. What it can no
longer do is **confirm a guessed key**. ⭐ Per realm, so the same identity shared into another realm
is unlinkable at the relay — the denial ADR 0017 D5 already gives the content plane.

⚠ `K_entity` never leaves the client and is not stored; a revoked device keeps only the ids it
already learned, which reveal nothing it did not already hold. ⚠⚠ `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION`
rises to 3 in the same change: a pre-EI-1 client addresses entities no post-EI-1 peer can match and
**neither side errors**, so the 426 is what converts silent divergence into a refusal.
*Amendment (sync-entity-identity.md EI-4, 2026-10-04 — co-lands with the code per House Rule 2).*
`DeleteBlock.targetEntityType` used to name the deleted entity's concrete type in the clear, stored in
`delete_block` and retained: per-type delete counts and cadence (labels, workouts, places,
`FileModification` trims) — the same vocabulary profiling this entry closed for `SyncBlock`. It is
now **one bit**: `Device.ID` for a device tombstone, the sentinel `tenshu/entity` for every other,
mapped at drain time (`TombstoneWireType`). Both relay readers only ever asked `== Device.ID`
(revocation, realm epoch) and still get the right answer; receivers resolve the target by its keyed
id, never by type. No shape change, no protocol move. A test pins that the sentinel equals no
registered schema id. ⚠ Forward-only in general (history keeps what it held); under CR-2 B6 it lands
before that track's relay reset, so the relay keeps no concrete-type history.

*Amendment (`fetch-entity-blocks`, 2026-10-04 — B3 wire spec §11.3).* ⚠ The "`fetchEntityBlocks(entityHash)`"
above was **false on the wire**: the client serialised the whole `DataId` — for a `File`, type plus
`keyValue = path`; for a shared-realm entity, the realm prefix too — and the relay hashed it itself with
the local `getDataIdHash()`. So every on-demand fetch and every `EntitlementRecovery` handed the relay a
**plaintext path or key**, and, since EI-1 stored blocks under keyed wire ids, the hash matched nothing.
The body is now the entity's EI-1 wire id, compared verbatim (no `RELAY_PROTOCOL_VERSION` move: CR-2's relay-protocol-4
hard cutover changes it in place). A body that is not a wire-id-shaped string, a serialised `DataId`
included, is refused with 400. What the relay learns per call is now what A6 already lists: *some*
entity, named by its keyed id, was read on demand.

Carve-out: the previously-existing distinction between "blocks pushed eagerly into the changes
feed" and "blocks fetched only on demand" relied on `targetPropertyId == null` as a sentinel.
With the field gone, `getChanges` now returns every block since the timestamp, including
OnDemand-marked property blocks (today none — no production schema pushes OnDemand bytes yet, so
the prior filter was dead code). When host-side OnDemand push lands (separate track), the
eager-vs-OnDemand distinction needs a different wire-level marker (likely a boolean flag or
separate bucket) — that marker leaks a single strategy bit per block, not the schema vocabulary.

*Amendment (`relay-metadata-minimisation.md` F9 / N4, 2026-10-28): the tuple above under-states
`hlc`.* `HybridLogicalClock` is `(physicalTime, logicalCounter, **deviceId**)`
(`core/.../time/HybridLogicalClock.kt`), so every stored `SyncBlock` envelope **names the device
that authored it**, and every `DeleteBlock` the device that deleted — at rest, for the life of the
log, and in every backup. The relay-visible wire metadata is therefore
`(blockId, ownerId, hlc = (physicalTime, logicalCounter, authoring deviceId), encryptedPayload,
targetEntityIdHash?)`. The CR-2 registers carry it too (N4): `content_edge.stamp` and
`holder_watermark.stamp` are the HLC in `physical:counter:deviceId` form (`HlcStamp.encode`,
`core-common/.../HybridLogicalClockUtil.kt`), as is the device registry's `admitted_hlc_device`. So
the relay holds a per-device write and delete history per entity.
**Decision: accepted for now.** What moving `deviceId` into the ciphertext would buy is narrower than
it looks: every push is already authenticated **per device** — the relay verifies the device JWT
against the key it looks up by `(iss = ownerId, sub = deviceId)` (`auth/JwtAuth.kt:113-121`) — so the
relay learns the authoring device of every write in flight regardless. The fix would reduce only the
*at-rest* and backup exposure (a later breach of the store, rather than a relay that logs as it
goes). And `deviceId` is load-bearing in the clear: it is the HLC's final tie-break (`compareHlc`,
equal physical time and counter order by `deviceId`), which the relay itself uses to order the edge
registers, and a tombstone has no ciphertext to move it into. That is why it was deliberately **not**
bundled with envelope authentication ([`sync-envelope-authentication.md`](../proposals/sync-envelope-authentication.md)
D-a). If done, it is a `SyncBlock` reshape and a `RELAY_PROTOCOL_VERSION` bump with dual decoding
(Phase 7). Residual: **Accepted, documented** (A6 "HLCs").

**T-R-09. File-content two-plane: manifest payload + content-plane chunks.**
Actors: AD5. Invariants: I1 (E2EE), I2 (integrity), I6 (relay learns no
user-data semantics). Assets: A5 (user data), A6 (metadata).
*Amendment scope — fully landed; design rationale in
[ADR 0017](../adr/0017-file-content-two-plane-channel.md), protocol
semantics in [`sync/design.md` §2.7](design.md#27-file-content-two-plane-channel);
each phase's amendment co-landed with its implementation per House
Rule #2.* The design splits multi-MB file-content property blocks into
two planes: a small **manifest** on the existing ordered metadata
drain, and immutable **chunk bodies** on a new content plane addressed
by opaque ref. (*Note, 2026-11-01:* chunk bodies are immutable
because their ref is the hash of their ciphertext; a ref that is not —
a manifest, a relation slice — is replaced in place by a re-`PUT` from
any admitted device of the realm, which is the capability the durable
store already granted. Owner scoping by the verified JWT is unchanged,
so no device can write into another realm's namespace.) P1 covered the *emitted shape* (below). P2 added a
scoped note (no new relay data shape ⇒ no new amendment). **P3 (the
relay-side content store + ref-counted GC, 2026-05-19) completes this
entry**: the two former P3 deferrals — (a) access-pattern exposure and
(b) content-plane GC — are now substantively analysed in the *P3
section* near the end of this entry, replacing the placeholder
"Deferred to P3" note. The substantive new line is **(a)
access-pattern exposure**, accepted as a documented v1 residual with a
noted future mitigation.

*⚠⚠ Selected successor — NOT yet an amendment (2026-09-17).* The reclamation protocol described
below is **superseded by a selected design** that is not yet built:
[`proposals/content-plane-refcount.md`](../proposals/content-plane-refcount.md)'s reference-handle
model. Four things in it change this entry's security contract, named here so a reader does not
take the text below as the intended end state: the holder-less **`describe` wire** is a new
relay-visible shape (`contentId` → refs + an opaque handle) that this entry's enumeration of the
content-plane wire does not cover; the handle table discloses a **per-content reference count**,
which is new relay knowledge (not a graph, but not nothing); and deletion authority moves from the
relay's **edge re-check** to the **client's word**, which changes who the integrity argument rests
on. **Fourth — added 2026-09-22 with §9.14's decision — the free protocol ACCEPTS a residual
permanent content loss.** Option 1 (a device progress quorum plus a lease fencing local merges)
prevents the ordinary case by vetoing the free, but it cannot *restore* bytes in the sliver where the
veto arrives after the relay's delete has committed **and** the deleting device never held the
plaintext locally. I2's integrity story therefore gains a state in which a merged row's content is
permanently **unavailable**, not merely unreachable — surfaced as a synced awaiting-restore marker
rather than as a dangling reference, so it is visible rather than silent.
⚠⚠⚠ **And the residual is NOT confined to that sliver — do not implement against this entry as if it
were.** §9.14 establishes a second, open-ended class: a **post-close late claim**. A sibling runtime
can meet a stale divergent move *after* this device's close acknowledgement has released the
retained plaintext, receive `already freed`, and raise its marker with nothing left to restore from
— and because bounding when a claim *originates* does not bound when it becomes **visible**, such a
claim can arrive after an arbitrary wait. No terminal event proves every claimant has resolved, and
§9.14 records that a further acknowledgement round would repeat the same race rather than end it.
⇒ read the accepted loss mode as **unbounded and post-close**, not as a bounded sliver. ⚠ An
implementation that follows the sliver framing would release retained plaintext at close and omit
handling for later claims, which is precisely the irrecoverable case.
⚠ The binding amendment co-lands with the implementation, per House Rule #2 and exactly as every
phase of this entry has — recording the supersession here is what stops the contract reading as
current in the meantime.
⚠⚠⚠ **Option 1 is itself SUPERSEDED (2026-09-26) by
[`content-plane-cr2-quorumless.md`](../proposals/content-plane-cr2-quorumless.md), unbuilt. ⭐ Candidate
B was CHOSEN on 2026-09-29 (§10 there): relay-authoritative references with a grace period T on the
relay's clock. Do not build the quorum, the merge-fencing lease, the synced awaiting-restore marker or
the retained plaintext this entry describes above, nor candidate A's machinery in that proposal (M2,
M3's client delete path, M5, W).** The contract, pending B's binding amendment at phases B3 and B5:
- **Protections:**
  - **G1:** a push carries `(property, contentId | none, HLC)` for every content-bearing property its
    delta touches. The relay commits the block and the edges in one transaction, as HLC-ordered LWW
    registers with a per-holder tombstone watermark. A reference therefore never reaches the log ahead of
    its edge.
    - **Cascades are ordered, and reaps complete them.** A tombstone removes its own and its listed
      descendants' edges in HLC order. `DeletedFolderSubtreeReaper` removes descendants regardless of
      HLC, so **every local reap pushes its own edge removal**, stamped with the reaper's local-delete
      HLC and applied in order. That covers a child updated after the folder's tombstone, one the deleter
      did not list, and a delayed write to a reaped child. It never erases a later legitimate recreate.
    - **Pre-B3 edges start unstamped**, and no tombstone removes an unstamped edge. A re-stamp pass on
      **one** device with complete-history coverage stamps the live ones before G2 is armed. Leftovers
      enter T, and a returning reference meets G3.
  - **G2:** only the relay frees. It frees content T after the last edge of that content goes (an
    observed transition from one edge to zero), measured on its own clock. The sweep's logical free and
    a push's commit are serialised per content, and physical deletion drains a durable queue, skipping
    any blob **PUT** after the free. Content that never had an edge is never freed.
  - **G3:** a push naming freed content is refused (`content_freed`). The only way out of that state is
    an atomic **revive** that verifies the complete, re-uploaded ref set.
- **Accepted loss, bounded by T on one clock:** a device that pushes a reference more than T after the
  content's last edge went, and holds no plaintext, loses the race. Its user is told the file was
  deleted on another device (P3). An uploader always holds the plaintext, so it re-uploads and revives
  instead.
- **Disclosure:**
  - Content-bearing deltas are pushed as standalone blocks, so batches stay opaque (option ii).
  - ⚠ That is **not** linkage-free. Each content-bearing push binds `(property, contentId)` to that
    block's visible write clock, where today's separate declare carries no HLC and is bound to no block.
    The increment over what the relay can already correlate by timing is small: standalone blocks expose
    target hash and `hlc_physical`, and declares expose `holder → contentId`.
  - ⚠ **The cascade list** binds a deleted subtree's membership to its tombstone. Today it is revealed
    only by the timing of later best-effort undeclare batches. The increment is small, but it is
    recorded here.
  - House Rule #1 holds throughout: the relay reads edge metadata only.
- **Deletion authority** moves from the clients' sweeps to the relay's own. Arming it is a deploy-time
  decision (phase B5, dry-run first).
- ⚠ The "unbounded and post-close" reading above belongs to option 1's retained-plaintext design. It
  does not carry over: nothing is retained for safety, so there is no close to be late for.
- The binding amendment for each mechanism co-lands with its phase (House Rule #2).
- ⚠ **Content-head addressing** (`content-plane-cr2-quorumless.md` §11) is recorded as an
  **unscheduled** design note, not a contract. If it is ever adopted, its amendment must assess:
  - **Generation churn:** the relay sees when a content is re-emitted, and how often.
  - **Root↔artifact grouping:** blobs keyed under `(rootContentId, artifactClass)` group a file's
    thumbnails and previews by **path**. The grouping itself is not new: `content_edge` already stores
    each rung's `content_id` under the same `(owner_id, holder_hash)` with its `property_id`. The delta
    to assess is the path-level exposure (it also covers blobs whose edges are gone) and its retention.
  - **Content-id-keyed paths** replacing ciphertext-hash keys. Content ids are random, so there is still
    no confirmation-of-file oracle, and integrity stays with the per-chunk AEAD, whose AAD must bind
    `(contentId, gen, n)`.
  - **Write-once paths**, which keep quota accounting exact.

*⭐ Binding amendment (CR-2 B3c, the relay half, 2026-10-02 — co-lands with the code per House Rule 2;
wire spec [`content-plane-cr2-b3-wire-spec.md`](../proposals/content-plane-cr2-b3-wire-spec.md) v0.2
§7).* G1 is now built at the relay. `RELAY_PROTOCOL_VERSION` and its minimum are both **4**.
- **What the relay now holds.** `content_edge` is one HLC-stamped register per `(owner, holder,
  property)`, with `content_id` NULL meaning *none* (the latest write cleared the slot), plus a
  `holder_watermark` per `(owner, holder)`: the highest HLC at which the holder was removed. Both are
  ordered by the shared `compareHlc`, never by arrival. House Rule 1 holds: the relay reads edge
  metadata it is told and decrypts nothing.
- **⚠ The "pre-B3 edges start unstamped" bullet above is superseded.** B3 shipped as a hard cutover
  with a relay reset (spec §0, maintainer 2026-10-02): migration 22 drops and recreates `content_edge`,
  so no unstamped edge exists. This holds only while a reset is acceptable (pre-release).
- **New linkage, each small but new (spec §7):**
  1. **A content-bearing property bound to a block and its write clock.** Option ii's increment, as
     stated above. ⚠ It now covers **every** edge, because no edge arrives any other way: `POST
     /sync/push` carries the row's complete edge set, and the holder and stamp are the block's own
     `targetEntityIdHash` and `hlc`. No write clock reaches the relay that the block did not already
     expose.
  2. **The cascade list** on `POST /sync/push-tombstone`: "these holders were one subtree, deleted
     together", bound to the tombstone and its HLC.
  3. **Per-device reaps** on `POST /sync/content/reap`: the same subtree membership, once per reaping
     device, at the folder tombstone's HLC. ⚠ The relay can therefore count how many devices reaped a
     subtree, and when each did.
  4. **`regenerable` per edge**: which properties are thumbnails. The derived-id pattern already let
     the relay infer this; the flag makes it explicit. ⚠ As built, B3c receives and validates it but
     does **not** store it (spec §4's table has no column for it); B4 decides whether it persists.
- **What leaves.** The edge form of `/sync/content/declare` and all of `/sync/content/undeclare` are
  deleted, and with them the separate declare/undeclare traffic and its timing correlation. The
  `contents` describe stays, with `referenceId` now optional.
- **Integrity: the relay's edge set now follows the log.** The block and its register writes commit in
  one transaction, so a reference cannot reach the log ahead of its edge. A late, older push cannot
  resurrect an edge a tombstone or reap removed (the watermark), and a removal never erases a register
  stamped above it (a recreate). ⚠ A device JWT holder can still write registers only in its own
  owner's keyspace; the owner always comes from the token. Within that keyspace it could already
  delete the file outright, so the register writes add no capability.
- **Abuse surface.** A push may carry at most 256 edges and a reap at most 1,000 removals; a cascade
  is unbounded in count, but every name is shape-checked (length and charset) and the body is one
  authenticated request. ⚠ The per-owner register count is still bounded only by the owner's own row
  count, as for the CR-3 table below.
- **Residual: Low.** Unchanged from the candidate B contract above: no new confidentiality class, and
  three small linkage increments over what block timing already disclosed. Deletion authority does
  **not** move in B3 (that is B5).

*⭐ Binding amendment (CR-2 B3, keyed property tags, 2026-10-04 — co-lands with the code per House
Rule 2; wire spec [`content-plane-cr2-b3-wire-spec.md`](../proposals/content-plane-cr2-b3-wire-spec.md)
§11.1; audit finding F5/N1 in `relay-metadata-minimisation.md`).* ⚠ The B3c amendment above
under-reported linkage item 1: as first built, `PushedEdge.propertyId` was the **plaintext schema
property id** (`tenshu/file/file/data`, a thumbnail rung, a workout stream), stored as
`content_edge.property_id` and joined to the ordered log through `holder_hash` — and because *none*
registers are sent too, it told the relay **which content slots every row has, empty ones included**,
on every write. That is the schema-vocabulary profiling T-R-08 closed for `SyncBlock`, reopened on the
push path.
- **Defense.** The edge's property is now a per-holder keyed tag:
  `tag = hex(HMAC-SHA256(K_tag, holderWireName ‖ propertyId))`, with
  `K_tag = HKDF(sharedVolumeKey, "tenshu/realm/content-property-tag/v1")` — a sibling of EI-1's
  `K_entity`, never the same key. Every device of the realm computes the same tag for the same register
  (same key, same EI-1 holder name), so the register model is unchanged. Per holder, so one row's tags
  are unrelated to another's: the relay cannot cluster rows into types by which tags co-occur.
  `K_tag` never leaves the client and is not stored.
- **The relay is unchanged.** It never interpreted `property_id`: it shape-checks it
  (`EDGE_ID_PATTERN`, which 64 hex chars pass) and uses it as a key component. The client tags at
  drain time and `RestSyncRelay` refuses to send an untagged edge. No client code reads a property id
  back from a relay response (the B4 `409` names content ids only). Rides migration 22's reset; no new
  relay migration and no `RELAY_PROTOCOL_VERSION` move.
- **⚠ What the relay still learns per row:** how many content slots it has (the edge count, *none*
  included), and **`PushedEdge.regenerable` stays plaintext** — so it learns how many of those slots
  are derived (rebuildable rungs) versus primary. That count still distinguishes broad row classes (a
  file with a thumbnail ladder from a row with one stream); it no longer names them.
- **⚠ Key rotation** renames every tag, as it renames every EI-1 wire id (EI Q2 accepted that class).
- **Residual: Low.** Item 1 above now reads "a content-bearing *slot* bound to a block and its write
  clock", not a named property.

*⭐ Binding amendment (CR-2 B4r, the relay half, 2026-10-02 — co-lands with the code per House Rule 2;
spec [`content-plane-cr2-b4-spec.md`](../proposals/content-plane-cr2-b4-spec.md) §1, §3).* The FREED
content state, G3's refusal and REVIVE are built at the relay. `RELAY_PROTOCOL_VERSION` stays **4**:
B3 reserved both the `revive` field and the 409.
- **New relay state:** `content_freed(owner_id, content_id, freed_at)` (migration 23). A row means the
  content was logically freed: its refs moved into `content_free_queue` (the B0 table above) and its
  `content_ref` and `content_reference` rows dropped. The relay already held every content id and ref
  involved, so B4 adds **no new linkage**. `freed_at` is the relay's own clock, not a client's.
- **The refusal reveals only** that an id the client itself named is FREED. A 409 `content_freed` (on
  `/sync/push` or `/sync/content/declare`) or `revive_incomplete` (on `/sync/push`) lists ids taken from
  the request, never any other; the owner comes from the verified token, so no request can probe another
  owner's state.
- **A revive cannot resurrect another owner's bytes.** Every check and every write — the FREED lookup,
  "present in `content_blob`", "not in `content_free_queue`", the `content_ref` replace — is keyed by
  the token's owner. Mode-B copies share refs across owners, and one owner's identical ref neither
  satisfies nor blocks another's revive.
- **A revive cannot admit a row naming missing blobs.** Every ref must be present **and** not queued,
  i.e. PUT (or read) since the free; a ref still queued is one the drain may delete the moment the push
  commits. Otherwise 409 `revive_incomplete`, and nothing is written. ⚠ The relay cannot check that the
  refs are the content's real chunks (it reads no manifest, HR #1): a client that revives with a wrong
  set gets the integrity failure the client-side AEAD already gives a corrupt manifest, on its own data.
- **Integrity: the refusal, the revive and the push are one transaction.** G3 and the revive run inside
  the push's existing block-and-edges transaction, before the append, so either all of it lands or none.
  The logical free takes the same lock and requires zero live edges, so a push and a free on one content
  serialise: whichever commits second sees the other. The describe's freed-check and all of its writes
  are one transaction for the same reason.
- **What does not change.** No production caller frees anything in B4 (B5's sweep is the first), so no
  byte is deleted that B0's operator drain would not already delete. `regenerable` is still validated per
  push and not stored: a regenerable edge naming a FREED id clears the row (the rung is re-minted) and is
  never refused.
- **Residual: Low.** No new confidentiality class and no new linkage; the new integrity property (a
  client cannot re-reference freed bytes it did not re-upload) closes a loss path rather than opening one.

*⭐ Binding amendment (CR-2 B5r, the relay half, 2026-10-03 — co-lands with the code per House Rule 2;
spec [`content-plane-cr2-b5-spec.md`](../proposals/content-plane-cr2-b5-spec.md) §1, §5, as built §6).*
G2 is built: **deletion of a user's durable ciphertext becomes relay-initiated**, on the relay's own
clock, with no device input. It ships `off` and is armed by a deploy-time switch (`CONTENT_SWEEP_MODE =
off | dry_run | armed`). `RELAY_PROTOCOL_VERSION` stays **4**: the 410 is a new answer on existing routes,
and `heldBytes` is an additive field.
- **Who decides a free, and on what.** In `armed`, the relay's sweep frees a content once it has had **no
  live edge for T** (`CONTENT_GRACE_MS`, 15 min by default) on the relay's clock. The edge set is the one
  the log maintains (B3c above), so the input is what devices already pushed; no device is consulted, and
  no device can delay a free (no barrier, no cursor, no lease). Freeing is the B4r logical free followed
  by the B0 drain: a push naming the content after that is refused (`content_freed`) and can REVIVE.
- **New relay state, and what it reveals.** `content_unreferenced(owner_id, content_id, since)` (migration
  24): when a content's last live edge went. The relay **already** held this — every register carries the
  stamp and the time of the write that cleared it — so the row is a derived index, not new linkage. It is
  kept in every mode. `content_sweep_state` records when the arming seed ran (one row, no user data).
- **The arming seed (D4).** On the first `armed` run, every described content with zero live edges gets
  `since = now`, so ST-3b's stalled backlog is freed T later. ⚠ That is a one-time mass free of content
  the relay believes unreferenced, decided by the relay's edge set; it is why `dry_run` exists and
  reports the backlog size, and arming is gated on the maintainer reading a dry run (spec §2). Content
  that was never described is never put on the clock (G4): a leak, never a loss.
- **The legacy client-initiated FREE routes are closed when armed (D3).** `/sync/content/delete` and
  `/sync/content/reclaim` answer **410** `freeing_is_the_relays` for every content, so no device can free
  bytes outside T and without the queue record a revive needs. Operator routes (`/ops/content-reclaim/*`,
  B0) are unchanged and stay behind their brakes.
- **The quota (D5).** In `armed`, `/sync/usage` and the PUT quota check leave out the bytes of refs held
  only by UNREFERENCED or queued content, and `/sync/usage` reports them as `heldBytes`. ⚠ A **delete
  frees quota at once** while the bytes stay on the relay for up to T + one sweep interval; the excess is
  bounded by what the owner deleted in that window, and a ref shared with live content stays charged. In
  `off`/`dry_run` everything is charged: bytes nothing will ever free must count, or deleting would grant
  unbounded uncharged storage. ⚠ The held figure is read just before the store's locked quota check, so a
  push reviving held content in between can overshoot the quota once by at most that content.
- **New operator surface.** `GET /ops/content-sweep/status`: mode, T, interval, last run and totals (no
  content ids). Behind the operator token **and** a loopback socket peer, as the B0 routes. The `dry_run`
  log names content ids and byte counts per owner; it is server-side only and carries nothing the
  relay's tables do not.
- **Integrity: the sweep cannot free a live content or delete revived bytes.** `free()` re-checks zero live
  edges in its own transaction, which serialises with a push's block-and-edges transaction, so a push that
  lands between selection and free wins (`NOT_FREED`). The drain holds the store lock from selecting a
  batch of queued refs to deleting their queue rows, and a PUT or GET takes its ref out of the queue under
  that lock first, so a revive's re-PUT can never be deleted by a batch that selected the ref earlier
  (§10.10's queue ABA, pinned by `ContentSweepJobTest`).
- **Residual: Medium until armed in production, Low once a dry run has been read.** No new confidentiality
  class and no new linkage. What is new is an **availability** decision moved to the relay: a defect in the
  edge set (a writer that pushes no edge for a reference, §10.7's gates) would now become a loss after T
  rather than a leak. That is what the arming gates in spec §2 — B4's refusal and revive, the closed
  legacy routes, the provisional-row move guard and a read dry run — exist to rule out.

*⭐ Binding amendment (CR-2 B6, reference handles and moves, 2026-10-04 — co-lands with the code per
House Rule 2; spec [`content-plane-cr2-b6-spec.md`](../proposals/content-plane-cr2-b6-spec.md) §1, §3,
as built §6).* `RELAY_PROTOCOL_VERSION` stays **4**: the describe still accepts every body it accepted.
- **The relay no longer holds reference handles.** Relay migration v25 drops `content_reference`, and
  with it the per-owner handle count, the row cap (and its `507`) and the N1 amendment's inflation
  surface below. G1's per-`(holder, property)` registers (B3c) are the only reference set; nothing reads a
  handle. ⚠ The describe still **accepts** `referenceId` and `hlc` — the JSON decoder rejects unknown keys,
  so dropping them would 400 a client one release behind — and ignores both: no shape check, nothing
  stored. Current clients send neither.
- **The reference roots leave the synced rows.** `File`/`SharedFile`/`Workout` no longer carry the random
  root the handles derived from (file-service v16, health-service v4 strip the stored slot;
  `PendingContentEmit` drops its captured handle, sync-service v9). That root never reached the relay in
  the clear; removing it removes a synced random value and no confidentiality class.
- **Net disclosure: one less.** The relay no longer learns how many live references describe a content
  object; it still learns which blobs form one (`content_ref`), unchanged.
- **Offline moves (D3/D4).** D3's deferred-move machinery is gone (intents, pins, replay). An **offline**
  move of a file — or a folder holding a file — whose bytes are not on the device is refused with
  `MoveNeedsLocalBytesError`, client-side and before any write; "offline" is the same cached
  reachability verdict plus acknowledged probe the fence used. This is an availability rule, not a relay
  one: it keeps a device from moving content it could not put back if a concurrent write got it freed
  (B4's repair needs local bytes). Nothing reaches the relay that did not before.
- **Residual: Low**, and lower than before on linkage (the per-content reference count is gone).

*⭐ Binding amendment (CR-2 B6, the device-side sweeps, 2026-10-04 — co-lands with the code per House Rule 2;
spec [`content-plane-cr2-b6-spec.md`](../proposals/content-plane-cr2-b6-spec.md) §2, §5, as built §7).*
**Clients no longer initiate frees at all.** The relay's sweep (B5r above) is the **only** path by which a
user's durable content is freed, and the deploy runs it **`armed`** (`sync-relay-server/docker-compose.yml`;
`RelayConfig`'s own default stays `off`). `RELAY_PROTOCOL_VERSION` stays **4**: routes are removed, none is
reshaped, and no current client calls them.
- **Removed on the device:** ST-3b's `ContentReclamationSweep` (with `ContentReclamationPolicy`'s liveness
  veto, `ReclamationBarrier` and `RealmConvergence`), its per-device candidate list and the recorder in
  `DeleteFile`/`ReplaceFile`/`UploadFile`, CR-5's `ContentEnumerationSweep`, and the platform switches. A
  device's delete now ends at its tombstone (and cascade); it records, decides and names no bytes to free.
- **Removed on the relay:** `POST /sync/content/delete` (T-R-13's surface), `POST /sync/content/reclaim`,
  and the two enumeration reads that fed client-side freeing, `GET /sync/content/orphan-candidates` and
  `GET /sync/content/contents` (with N0's age window). They are a 404 in every mode, not D3's 410.
- **What this closes.** T-R-13's *self-inflicted* class — an admitted device, buggy or compromised,
  destroying its own owner's live bytes **at once** by naming refs — no longer exists: there is no route a
  device can name content on to free it. A device can still remove the **edges** that keep content alive
  (a delete, a reap, an overwrite), exactly as it can delete the metadata, so the consequence of a bad
  delete is the relay freeing after T, and a push naming the content inside T keeps it; after T, B4's
  refusal lets the holder revive it from its plaintext. The cross-tenant half of T-R-13 had nothing left to
  defend.
- **What it costs.** Relay storage is now freed only on the relay's clock. A relay deployed `off` or
  `dry_run` frees **nothing** and keeps charging deleted content to quota — a leak, never a loss. ⚠ The
  dry-run comparison of relay-would-free against devices-did-free (§10.7) was given up knowingly (B6 D1),
  so B5r's residual "Low once a dry run has been read" is replaced by: **Medium until the armed sweep has
  been observed in production**, for the availability reason B5r states (an edge-set defect becomes a loss
  after T). No new confidentiality class and no new relay state.

*CEK amendment (content-addressing §3.4 slice 3-1, co-lands per House Rule #2, 2026-07-17).*
Content chunks are no longer AEAD-encrypted directly under the realm's `sharedVolumeKey`
(A12-derived). Instead each content object is encrypted under a fresh **per-content key (CEK)**
— a random 32-byte key (CSPRNG) — and the CEK is AEAD-**wrapped under `sharedVolumeKey`**
(domain-separated AAD `"tenshu/cek-wrap/v1|<contentId>"`) and carried inside the (already
identity-key-encrypted) manifest as `wrappedCek`. The reader unwraps it with its volume key(s)
(current + retired, GR-7) and decrypts the chunks with the CEK. **Confidentiality within a realm
is unchanged**: the CEK is gated by `sharedVolumeKey`, so exactly the devices that could decrypt
content before still can, and no key or plaintext new-leaks to the relay (the wrapped CEK rides
inside the manifest ciphertext; chunks stay opaque `blob-<sha256(ciphertext)>`). The CEK adds a
new **asset** — a per-content symmetric key, sitting between A5 (user data) and A12 (it is
*wrapped to* a realm key and *encrypts* user data) — whose sole purpose is to make the chunk
ciphertext **realm-independent**, the substrate for Mode-B cross-realm sharing by CEK re-wrap
(slice 3-2b, which re-wraps the CEK under the target realm's volume key + copies the encrypted
blobs + its own amendment, with a T-GR-02-parallel revocation note for a removed member who cached
a CEK). The per-content-key
whole-file integrity anchor (`wholeFileHash`) and the per-chunk AAD binding are unchanged; note
the `contentId` binding is now *also* enforced at CEK-unwrap (a manifest claiming another file's
`contentId` fails to unwrap the CEK — a strengthening). *(1.0.1 re-cut, 2026-11-05: a manifest without a CEK that unwraps is undecryptable; there is no direct volume-key chunk decrypt any more.)*

*Shared-file content-addressing note (content-addressing §3.4 slice 3-2a, co-lands per House Rule
#2, 2026-07-17).* A shared-realm file (`SharedFile`) now carries a `contentId` (= the personal
file's), so its content **manifest is content-addressed on the realm's content plane**
(`manifestContentRef(contentId)`, an opaque `blob-<sha256("manifest:"+contentId)>` ref in the
realm-owner-scoped store) exactly as a personal file's is — replacing the former legacy
entity-id-keyed mailbox `SyncBlock`. **No new relay-visible data shape and no new asset**: this is
the same content-plane manifest posture P1/P3 already established for personal files, now extended
to shared realms; the ref is derived from a random `contentId` (not a path-hash), so it exposes no
dictionary-attackable routing key (if anything it removes the mailbox's per-entity-hash routing
key for shared files). Crypto is unchanged from the 3-1 CEK amendment above (still a fresh CEK
wrapped under the realm's `sharedVolumeKey`); slice 3-2a is manifest *placement*, not a key
change. The cross-realm CEK **re-wrap** lands with slice **3-2b** (below).

*Mode-B cross-realm content re-wrap (content-addressing §3.4 slice 3-2b, co-lands per House Rule
#2, 2026-07-17).* Sharing a personal file into a shared realm no longer decrypts the host's
plaintext and re-encrypts it under the realm keys (the old `ShareFolderIntoRealm` path). Instead,
because the chunk ciphertext is realm-independent (the CEK, not the volume key, encrypts it), the
proxy: (1) fetches the personal manifest from the relay-backed personal content store + unwraps
its CEK under the personal volume key(s); (2) **copies the already-CEK-encrypted chunk blobs by
ref** into the target realm's content keyspace — no decrypt, no re-encrypt; (3) re-wraps the CEK
under the **target realm's** `sharedVolumeKey` (same domain-separated `cekWrapAad(contentId)`),
rewrites the manifest's `propertyId` to the shared data slot, re-encrypts the (small) manifest
under the target realm's `sharedIdentityKey`, and PUTs it at the content-addressed manifest ref in
the target keyspace. **Confidentiality is unchanged** (HR #1 intact): every blob the relay handles
is opaque AEAD output; the plaintext never leaves the AEAD boundary and now never even touches the
sharing device's disk. The CEK ends up wrapped under both the personal and the target volume keys
(two wrapped copies on two manifests, each gated by its realm's key) — exactly the members who
should read the content can, in each realm.
**New relay-visible residual (A6, metadata — Low, accepted):** the *same*
`blob-<sha256(ciphertext)>` ref now appears under **two owners' content keyspaces** (the sharer's
and the shared realm's), so a relay correlating refs across keyspaces learns *"this content object
is shared between owner P and realm R"* — a sharing-graph edge, never content. This is the price of
copy-by-ref (option 1); it is the same class of linkage the shared-realm block channel already
exposes (a realm's members write to its keyspace) and is bounded by there being no cross-*user*
dedup (a random CEK ⇒ two users' copies of the same file get different ciphertext + different refs,
so this edge only ever links *one owner's own file* to *a realm they shared it into*). Option 2 (a
single shared content plane) would replace this with an unguessable-ref capability model and is
deferred with cross-realm GC (Phase 4).
**Revocation residual (T-GR-02-parallel — Low, accepted):** a member removed from the realm who
cached the re-wrapped CEK (or the copied blobs) retains read access to *that already-shared content
version* — identical to GR-7's cached-content posture (a share is a copy; removal is forward-only).
Kr rotation (GR-7) re-keys *future* realm content but does not retroactively lock a cached CEK; a
per-content re-key on removal is out of scope (the content is immutable and content-addressed).

*P2 scoped note (co-lands with the P2 implementation).* Phase P2
(read path) landed the **client** side: `ContentBlobStore.getChunk`
(streamed `GET /sync/content/{ref}` by opaque ref) + the OnDemand
manifest applier that fetches only an opened file's chunks, verifies
per-chunk `cipherHash` + AEAD + the bound `(fileId, chunkIndex,
chunkCount)` AAD + the whole-file hash-chain anchor, and reassembles
into `FileDatabase` integrity-gated and `O(CHUNK)`-bounded. P2
introduces **no new relay-visible data shape** — the `GET`-by-ref is
the exact symmetric read of the P1 `PUT`, all decrypt + integrity
stays client-side (House Rule #1 unchanged), and the access-pattern
exposure it realises (a relay observing *which* refs a device fetches
and *when*) was already enumerated and **deferred to P3** in this
entry's deferral (a) below. No new T-/A- amendment is therefore
required for P2; the deferrals (a) access-pattern exposure analysis +
mitigation and (b) the relay-side content store + ref-counted GC
remain **P3** and are unchanged by P2 landing the client read path.

*P3a scoped sub-amendment (co-lands with the P3a implementation —
House Rule #2).* Phase P3a lands the **relay-side** counterpart of the
already-amended P1 + P2 client shape: an in-memory `ContentBlobStore`
on `:sync-relay-server` (mirrors `RecoveryBlobStore`'s shape; Phase 4
of `sync-backend-relay-plan.md` migrates this to S3 alongside the
other relay-side state) plus the `PUT` + `GET /sync/content/{ref}`
endpoint pair. JWT-authenticated on both verbs via the existing
`requireOwnerId` helper. **No new T-/A- surface relative to the
already-accepted P1 amendment.** Specifically:
- **Data shape unchanged from the P1 amendment.** The relay accepts +
  serves opaque ciphertext addressed by the same hash-of-ciphertext
  ref the P1 + P2 amendments already described and justified. The
  server enforces a `blob-<64-hex>` ref shape (rejects malformed refs
  with 400) and an 8 MiB per-blob size cap (rejects oversized PUTs
  with 413); both are administrative-defense hygiene, not new
  threat-model surface.
- **Zero-trust unchanged (House Rule #1).** The relay stores +
  serves opaque ciphertext only. There is no decrypt code on the
  server side; the `PUT` handler does not inspect bytes beyond their
  size, the `GET` handler does not inspect bytes at all. The router
  + store are byte-pipe transparent.
- **No ownership / per-ref ACL today.** P3a stores blobs under a
  global ref keyspace (an admitted device can `PUT` and `GET` any
  ref). The ref is a content hash so cross-realm linkability is
  bounded by per-realm volume keying (already accepted in the P1
  amendment); an admitted device that PUTs garbage under random refs
  wastes bounded relay memory until P3b's refcount GC reclaims it.
  Tighter per-ref ownership becomes natural in P3b when manifests
  in `SyncMailbox` become the authority for which refs are live —
  out of scope for P3a.
- **No GC in P3a.** The store grows unboundedly until P3b lands; an
  in-memory map with no sweep is the v1 dev shape (mirrors the rest
  of the relay's in-memory stores pre-Phase-4). Production hardening
  is the P3b + Phase 4 track.
Residual risk: **Low** (unchanged from the P1 amendment's bound on
A5 confidentiality + integrity under AD5; the admitted-device
spam-PUT abuse is bounded by the per-blob size cap + JWT-gated
admission). The deferrals (a) access-pattern exposure analysis and
(b) ref-counted GC mechanism shipped in **P3b** alongside the GC
mechanism they describe — coupling them in one slice keeps the
threat-model line load-bearing rather than abstract. Both are
covered by the (a) + (b) blocks below and codified in
[ADR 0017](../adr/0017-file-content-two-plane-channel.md).

- **Manifest payload.** A new `FileContentManifestPayload` is a sibling
  of `DataBlockPayload`, carried as the encrypted body of an ordinary
  `SyncBlock` on the same drain. Every identifying field (`fileId`,
  `propertyId`, per-chunk refs, sizes, hashes) lives **inside the
  ciphertext**, exactly like `DataBlockPayload.propertyId` (T-R-08).
  Relay-visible wire metadata is unchanged:
  `(blockId, ownerId, hlc, encryptedPayload, targetEntityIdHash?)`. The
  relay does not learn that a block is a manifest, nor which file. The
  manifest is small and bounded regardless of file size.
- **Per-chunk AEAD + AAD.** Each chunk is AEAD-encrypted *independently*
  with `(fileId, chunkIndex, chunkCount)` bound as associated data. P1
  does **not** change the AEAD primitive or any platform crypto backend
  (TA3 / T-RC-02 frozen — recovery-KDF parameters untouched): the AAD is
  bound via a length-prefixed frame *inside* the existing
  XChaCha20-Poly1305 ciphertext (`SyncCryptoManager.encryptWithAad` /
  `decryptWithAad`), so the AAD inherits exactly the underlying AEAD's
  Poly1305 integrity strength. Consequence (I2): a relay cannot
  silently reorder chunks, truncate the file, splice a chunk from
  another file, or replay an old chunk — any such manipulation changes
  the bound `(fileId, chunkIndex, chunkCount)` and fails authenticated
  decryption on the reader. The manifest additionally carries a
  whole-file integrity anchor (a hash-chain over per-chunk hashes,
  order-sensitive) the P2 reader verifies after reassembly.
- **Content-ref opacity (oracle avoidance).** The content-plane key for
  a chunk is derived from a hash of the **ciphertext**, never the
  plaintext. A plaintext-derived ref would be a confirmation-of-file /
  cross-user dedup oracle (the relay could test "does this user store
  *this exact known file*"). Per-realm volume keying already makes
  identical plaintext encrypt to unrelated ciphertext across realms, so
  refs are not cross-realm-linkable. This supersedes and resolves the
  `tech-debt.md` "Cross-Process Byte Payloads Use Content-Derived IDs"
  follow-up: the mandated ref is hash-of-*ciphertext*, not
  `md5(content)`.
- **Zero-trust unchanged (House Rule #1).** All encryption (manifest
  body + every chunk) happens on Control before any queue/`PUT`; the
  relay only ever sees opaque ciphertext + opaque refs and never
  decrypts. The `SyncBlock` envelope is unchanged.
- **File-size leakage.** `chunkCount × CHUNK` ≈ file size — already
  inferable from a single large ciphertext's length under the
  pre-P1 model; no material new leakage. Optional final-chunk padding
  is a future option, not P1.

**Content-addressing Phase 1 — manifest moves to the content plane
(2026-07-15).** [`file-content-addressing.md`](../proposals/file-content-addressing.md)
Phase 1 changes *where* a personal file's manifest lives and *what* it is
keyed by. It **reduces** relay-visible metadata; it introduces no new
plaintext exposure.
- **Manifest is now a content-plane blob, not a mailbox block.** For a
  personal (content-addressed) file the encrypted manifest is `PUT` to the
  existing opaque content store under a `blob-<sha256(...)>` ref — the **same
  opaque `blob-<64-hex>` key space** as the chunks' `blob-<sha256(ciphertext)>`
  — instead of riding the ordered mailbox as a `SyncBlock`. The relay treats it
  as one more opaque ciphertext blob under an opaque ref (House Rule #1
  unchanged); it never learns the ref names a manifest, and cannot distinguish
  it from a chunk. (Shared-realm files, whose entity id is already a stable
  UUID, keep the pre-P1 mailbox manifest — unchanged.)
  - *Ref-shape correction (2026-07-17).* Phase 1 first minted the manifest ref
    as `manifest-<contentId>`, but the relay's `ContentRouting.REF_PATTERN`
    (`^blob-[0-9a-f]{64}$`, enforced on PUT **and** GET, to keep the content
    store from being an arbitrary-key bucket) 400-rejected that shape — so
    cross-device manifests never reached the relay and OnDemand byte delivery
    broke. Fixed by deriving the ref as `blob-<sha256("manifest:"+contentId)>`
    (`manifestContentRef`): it satisfies the pattern, stays derivable from the
    `contentId` the reader already holds, and is domain-tagged so it never
    collides with a chunk ref. This **tightens** the posture — the relay now
    sees a single uniform opaque ref shape — and needs no relay-side change.
- **Routing key is a random `contentId`, not a path-hash — a leak removed.**
  The pre-P1 manifest block carried `targetEntityIdHash` = a hash of the
  file's **path**, which is dictionary-attackable (`/photos/IMG_1234.jpg`).
  The content-plane ref is `blob-<sha256("manifest:"+contentId)>` where
  `contentId` is a **random 16-byte id** minted per content write — the ref
  correlates nothing and is not a path oracle (it is `sha256` of a random id,
  not of the file's path or its content). Net: Phase 1 *removes* the path-hash
  leak the content-addressed path formerly had.
- **`contentId` is random, never content-derived** — same reasoning as the
  ciphertext-hash chunk refs: a content-derived manifest address would be a
  confirmation-of-file oracle. **`contentHash`** (the whole-file plaintext
  hash-chain) lives **only inside the encrypted manifest and the encrypted
  `File` metadata**; it is never a relay-visible address or routing key. **No
  cross-user dedup** remains a stated non-goal.
- **Access-pattern exposure (a) is unchanged in kind:** a `GET
  blob-<sha256(...)>` for a manifest is one more opaque-ref fetch in the same traffic-
  analysis-over-opaque-handles bucket as a chunk `GET`; it reveals *that* a
  file was opened and its recency, never content. A move now produces **no**
  new content-plane traffic at all (metadata-only), so it leaks *less* than
  the pre-P1 re-emit.

**P3 — relay-side content store + ref-counted GC (2026-05-19).** The
relay gained a per-owner opaque-blob store served by `PUT`/`GET
/sync/content/{ref}` (`sync-relay-api.md` §3.7). The store is the exact
zero-trust shape of the recovery-blob store (T-RC-02 / I7): the relay
holds and serves ciphertext addressed only by an opaque ref, never
decrypts, never inspects, never derives anything from blob contents
(House Rule #1). Keying material never reaches the relay. The four
points P1 analysed (manifest opacity, per-chunk AEAD+AAD integrity,
content-ref opacity, file-size leakage) are **unchanged** by P3 — P3
adds storage + GC, not a new emitted shape. The two formerly-deferred
lines:

- **(a) Access-pattern exposure — the substantive new line; v1
  residual.** On-demand `GET /sync/content/{ref}` lets the relay (AD5)
  observe *which* opaque refs a device fetches and *when*. Even though
  every blob stays ciphertext and refs are non-plaintext-derived
  (no *content* is learned, no cross-realm linkage — per-realm volume
  keying, see P1's content-ref-opacity point), the **fetch trace
  itself** is new metadata: the relay can infer *that* a user opened
  *some* file, the file's approximate size (count×CHUNK of refs fetched
  together — already ≈ inferable from the pre-design single big
  ciphertext's length, so no material new size leakage), and the
  **timing/recency** of opens. The all-drain pre-design model did not
  leak per-open timing (every device drained everything regardless of
  what was opened). Refs are opaque and not file-identifying, so this
  is *traffic analysis over opaque handles*, not content disclosure;
  it falls under the metadata exposure already accepted for AD5 under
  T-T-07 / A6, **extended** by the per-open *timing* dimension.
  **v1 stance (per [ADR 0017](../adr/0017-file-content-two-plane-channel.md)
  Decision 7, which explicitly accepts this):** *accepted as a documented
  residual.* We do **not** build oblivious fetch / PIR for v1 — that is a
  research-grade project disproportionate to a
  traffic-analysis-over-opaque-refs threat under the already-zero-trust
  relay. **Noted future mitigation (P4, sized but not built):**
  batched/padded prefetch of a file's chunks in one request, fixed-size
  fetch groups, and/or decoy fetches — each reduces the timing/size
  signal without changing the wire contract or the AEAD. Tracked as
  ADR 0017 *When to revisit* trigger #1; promote if a deployment's threat
  profile makes per-open timing unacceptable.
  Residual: **Low–Medium** for A6 metadata under AD5 (a passive relay
  learns open-timing over opaque refs); **unchanged Low** for A5
  confidentiality + integrity (content + integrity stay client-side).
- **(b) Content-plane GC — bounded, House-Rule-#1-safe.** A new
  unreferenced-blob retention sweep on the relay, **distinct from** the
  ordered-log full-quorum HLC-tip tombstone GC ([`sync/design.md`](design.md)
  §2.6) and composing with it (the tombstone GC frees manifest *bodies*
  on the ordered plane; this frees *chunk blobs* on the content plane).
  Because manifests are encrypted `SyncBlock`s the relay cannot read —
  and a file-delete `DeleteBlock` exposes only an opaque
  `targetEntityIdHash` + entity type, never chunk refs — the relay
  *cannot* learn a blob's refs to do a literal refcount without reading
  content (House-Rule-#1 violation). A client "release refs" signal was
  **considered and rejected**: it would disclose a deleted file's
  chunk-ref set + size to the relay (a *new* metadata leak strictly
  worse than (a)) and add client wire surface for no correctness gain.
  The chosen design is a **retention/orphan policy**
  ([ADR 0017](../adr/0017-file-content-two-plane-channel.md) Decision 6 —
  the House-Rule-#1-safe alternative): a blob is collectible only after
  it is untouched
  by any `PUT` or `GET` for a window `≥` the tombstone-body retention
  window, so it provably **outlives** the ordered-log tombstone GC; a
  `GET` is the relay-visible proof a live manifest still points at the
  ref (the refcount proxy needing no manifest reading). **Shared-blob
  safety:** dedup'd ciphertext under one ref stays alive while *any*
  referencing manifest is live (it keeps being `GET`-ed).
  **Orphan-PUT grace:** an upload whose manifest never lands ages out
  by the same window. **Bound:** a deleted file's blobs linger up to
  the content-retention window past the last `GET` — conservative by
  construction (never collects a blob a live manifest could still
  reference) at the cost of delayed reclamation; this is the documented
  v1 trade-off. GC trust surface: the relay choosing *not* to GC only
  costs it storage (it already holds the ciphertext); choosing to GC
  early at worst makes a `GET` 404, which the client gates as a clean
  retriable failure (the file does not surface — same failure class as
  a never-stored blob). The relay cannot use GC to forge, substitute,
  or learn content. Residual: **Low** — a storage-reclamation policy on
  data the relay already holds opaquely; no confidentiality/integrity
  impact (those stay client-side per the P1 AEAD+AAD analysis).

  *Amendment (content-addressing §4.2, 2026-07-18): the time-based content
  GC is DISABLED by default.* The retention/orphan sweep above was wired live
  with a 30-day untouched window — but the content plane is **durable user
  data**, not an ephemeral log, and a *time-since-last-touch* sweep reaps
  **live-but-unopened** files (content is `OnDemand` — the relay may be a
  file's only durable copy; ordinary metadata sync never `GET`s content
  blobs, so a backup nobody opens for a month has its chunks reaped → data
  loss on next open). The "a live manifest keeps being `GET`-ed" reasoning in
  bullet (b) is **false for an unopened file**. Corrected by defaulting
  `RelayConfig.CONTENT_RETENTION_WINDOW_MS` to `Long.MAX_VALUE` (GC off);
  content now persists indefinitely while the account is in good standing.
  Legitimate reclamation is **account-lifecycle-driven, keyed on `ownerId`**
  (owner over-quota / entitlement lapse → grace → reclaim that owner's
  content — the relay reclaims by `ownerId`, still never reading a manifest,
  HR #1 intact) — a designed, not-yet-built mechanism tied to the
  entitlement/commerce plane. Deleted / orphan / fresh-CEK-superseded blobs
  linger **counted against the owner's quota** (quota is the brake) until
  that reclaim; a manifest refcount stays HR#1-impossible. **Residual delta:**
  the (b) "delayed reclamation up to the retention window" bound is replaced
  by "reclaimed on account-lapse, else bounded by quota" — a *larger* storage
  residual (Low; the relay already holds the ciphertext), traded for closing
  a **data-loss** hole. No new relay-visible wire shape; confidentiality /
  integrity unchanged.

  *Amendment (retention-quota-model.md Face A / D4, P3a, 2026-08-04): the reclaim
  mechanism exists — and is deliberately unreachable.* The "designed, not-yet-built
  mechanism" named above is now built: `ContentBlobRegistry.reclaimOwner(ownerId)`
  drops every `(ownerId, *)` blob on both backends, returning what it freed.
  ⚠⚠ **Nothing calls it.** There is no route, no scheduler hook and no commerce
  signal — the trigger (D4's explicit "account closed past grace" event) is a
  separate slice, and the ⚖️ legal prerequisite (a retention/closure clause in
  `docs/policy/acceptable-use-and-limits.md` + a ToS version bump) still gates
  *shipping* reclamation at all. So this amendment records a **capability, not a
  behaviour**: no owner's content can be reclaimed today by any code path.
  **HR #1 intact** — reclamation reads no manifest and inspects no ciphertext; it
  operates on the `ownerId` keyspace the relay already owns, which is exactly why
  account-lifecycle granularity is the only kind available (and sufficient).
  **No new relay-visible wire shape**, no new endpoint, no auth surface.
  ⚠ Scope is **content only**: tier-1 mailbox blocks keep their own quorum+retention
  GC and device/identity rows belong to the ADR-0007 revocation path — conflating the
  three planes is what made the time-based blob sweep a data-loss bug. ⚠ The primitive is
  deliberately **independent of `CONTENT_RETENTION_WINDOW_MS`**: that window is
  `Long.MAX_VALUE` in production (GC off), and inheriting its short-circuit would make
  reclamation a silent no-op precisely where it is meant to work.
  **Residual:** unchanged from the amendment above — deleted/orphan blobs still linger
  against quota until a reclaim trigger exists.

  *Amendment (retention-quota-model.md Face A / D4, P3b-1, 2026-08-04): the closure
  credential + the reclaim decision.* The trigger's **credential and decision** now exist:
  `AccountClosureClaims` (a commerce-signed, alg-pinned EdDSA JWT verified against the
  **existing** `ENTITLEMENT_ISSUER_PUBKEY` — no new trust anchor, no new secret) and
  `AccountClosureReclaimer`, which reclaims only once the record's `closed` time is older
  than a configurable grace window. ⚠⚠ **Still unreachable:** no route accepts a closure
  record and commerce cannot mint one (`AccountStatus` is `Active`-only), and
  `ACCOUNT_CLOSURE_RECLAIM` defaults `false` — ⚖️ a *release* gate held by the policy/ToS
  prerequisite, not a convenience default.
  **⚠ A real, deliberate exception to D3 blinding-readiness (A30).** The entitlement token's
  `sub` is optional precisely so a blinded token links no person to a realm. A closure record's
  `ownerId` is **mandatory**: reclamation must know *whose* keyspace to drop, and the keyspace
  *is* the `ownerId`. So this one flow discloses a person→realm association to the relay that a
  blinded entitlement deliberately withholds. It is unavoidable rather than incidental — a
  content-blind relay (HR #1) cannot be told "delete this account's data" in any way that does
  not name the account — and it is bounded: the disclosure happens once, at closure, for an
  account that is ending. Recorded so a future blinding push does not assume this flow can
  follow.
  ⚠ **Freshness is the revocation.** The record is short-lived and re-issued while the account
  stays closed; a reopened account simply stops having records minted. Without expiry a leaked
  record would be a permanent delete-this-owner capability that could fire against a live
  account — the same "stop reissuing = revocation" model the entitlement token already relies on.
  ⚠ **Grace is measured from commerce's recorded closure time, never from delivery**, so a
  retried or delayed record cannot silently extend retention past what the published terms
  commit to. A future-dated record fails *closed* (negative elapsed time is inside any window).
  **HR #1 intact**; no new wire shape on the data plane; the credential rides the same
  verifier-only posture as the entitlement token.

  *Amendment (retention-quota-model.md Face A / D4, P3b-2, 2026-08-04): the producer, the
  route, and the sweep — the path is now complete and still switched off.* Commerce gains a
  closure state (`AccountStatus.Closed` + `closedAt`, Postgres migration v3) and an
  `AccountClosureMinter`; the relay gains **`POST /sync/account/closure`**, a **new inbound
  endpoint and the only one in the system that can destroy user content**.
  ⚠⚠ **The request body *is* the credential** — a commerce-signed record verified against the
  existing `ENTITLEMENT_ISSUER_PUBKEY`. Reachability is not part of the security argument: an
  attacker who reaches the endpoint without the issuer's private key achieves nothing, which is
  the entitlement token's posture applied to a heavier verb. No new trust anchor, no shared
  secret, no bearer credential the relay stores.
  ⚠ **The route is not registered** unless `ACCOUNT_CLOSURE_RECLAIM` is on *and* an issuer key is
  configured — an unconfigured relay answers a plain **404**, so a probe cannot even learn that
  this deployment has reclamation. Rejections are a **single opaque 401** (bad signature, expired,
  wrong `kid`, malformed, missing owner) so a caller cannot discover which brake they cleared.
  ⚠⚠ **Two independent opt-ins, one per plane** — commerce's `ACCOUNT_CLOSURE_RELAY_URL` and the
  relay's `ACCOUNT_CLOSURE_RECLAIM`. Content is deleted only when **both** are set, which is what
  makes the ⚖️ policy/ToS gate enforceable at deploy time rather than by code review; either flag
  alone leaves the system as inert as it was before this slice.
  ⚠ **The relay keeps no closure state.** Delivery is a repeating sweep, and reopening works by
  commerce *stopping* — nothing has to be un-said. A durable "this owner is closed" fact on the
  relay would require a reopen message whose loss would delete a live account's files, so the
  freshness model above is load-bearing rather than an optimisation.
  A6 metadata delta: the relay learns, once per sweep while an account is closed, that a given
  `ownerId` is ending — the D3/A30 exception recorded above, no wider.
  ⚠⚠ **Amendment (2026-10-24): both flags ON in production** (maintainer decision, ahead of ⚖️
  counsel's review of the retention text), so this path now deletes. Only change to the mechanism:
  commerce backdates the record's `iat` by 30 s, because the strict `iat <= now` check fails *every*
  record while commerce's clock runs fast; `closed` and the record's lifetime are unchanged (see the
  account-closure note in §8.15).
  ⚠⚠ **Amendment (relay-metadata-minimisation.md F12 / Phase 4 (c), 2026-10-27): closure now erases
  the owner's *whole* relay footprint, not only content.** Until this change the route above ran
  `reclaimOwner`, which dropped `content_blob` rows and bytes and nothing else, so a closed account's
  ordered log (`sync_block`, `delete_block` — A6 envelopes plus their ciphertext), device roster and
  admission stamps, HLC tips and cursors, the CR-2 edge/ref/freed/clock tables, the A9 recovery blob,
  share descriptors and chunks, invites, grants, EPC sessions, pairing offers and the realm row itself
  survived closure **indefinitely**. The "content is deleted after grace" promise held for bytes and
  not for the metadata this document spends most of A6 bounding. `AccountErasure` now runs every
  owner-scoped store's `eraseOwner` as **one SQLite transaction**, then deletes bytes from a durable
  ledger (`content_free_queue` for content, the revoked `share_record` rows for share chunks), so a
  crash or a failed object-store delete is finished by the next closure post rather than leaking
  bytes nothing names. Re-running on an erased owner is a no-op.
  ⚠⚠ **Retained after closure, deliberately — three revocation fences:** `removed_device` (F2′),
  `admission_revocation` (A33) and `member_revocation` (Q30), each keyed by the closed `ownerId`.
  Reason: `ownerId = sha256(masterPub)` and genesis is master-self-signed (the client's
  `reassertGenesis` self-heals a missing realm), so the **owner can re-create the realm** after
  erasure — and every capability those fences revoke is a master signature that verifies again in the
  re-created realm. Dropping them would let a removed device or a revoked member/admitter back in.
  They hold opaque ids, public keys, epochs, timestamps and master signatures only (no content, no
  names). ⚖️ Their indefinite retention is a pseudonymous-data question owed to counsel.
  ⚠⚠ *Corrected 2026-10-28: the paragraph above describes the F12 account-closure change's first draft, not what shipped.* The
  shipped change replaced the three fences with a single tombstone: closure **erases**
  `removed_device`, `admission_revocation` and `member_revocation` with everything else, and writes
  `erased_realm(owner_id, erased_at)` (relay migration 2, `storage/RelayDatabase.kt`). That row is the
  only thing an erased owner leaves behind (`AccountErasure.KEPT_TABLES = {erased_realm}`, pinned by
  `AccountErasureSchemaTest`). It closes the same hole more strongly: `POST /sync/realm` answers
  **410 Gone** for an erased `ownerId` before checking the master self-signature
  (`routing/RealmRouting.kt:330-333`), so the realm can never be re-created, and with no `realm` row
  every route that admits a device, a member or a recovery refuses the owner — the old master
  signatures have nothing left to verify against. Consequence: a closed-and-erased master key is
  retired for good, and an account that comes back starts a new realm. This is post-*erasure* state,
  not closure state, so the "no closure state" note above still holds for the grace period. What
  survives is the opaque `ownerId` and a timestamp. ⚖️ Reviewed by the maintainer as counsel on
  2026-10-28 (`retention-quota-model.md` §3.3a): `erased_realm` is pseudonymous, Art. 6(1)(f), kept
  while the service runs. The in-memory (dev-mode) twins now match: they also erase the ledgers.
  ⚠ **Scope is exactly the posted `ownerId`.** P3's "unless an open account still holds that realm"
  is decided in commerce before a record is minted; a shared realm is its own `ownerId`, so erasing a
  closed member or owner never reaches a shared realm's rows (including the closed person's member
  admissions *into* it). Shared realms a closed person created therefore persist until their own
  realm id is closed — which today nothing does (open question, see `retention-quota-model.md` §3.3).
  ⚠ **Not reached:** bytes orphaned by a crash *before* any index row existed (`BlobStore` cannot be
  enumerated by owner), and the two TTL'd rendezvous maps keyed by pairing secret rather than owner
  (pairing responses, hub offers). **HR #1 intact** — every delete is keyed by the routing id the
  relay already holds; no ciphertext is read. **No wire change** beyond an additive `rowsErased`
  count in the 200 body. A6 delta: strictly *less* retained metadata after closure.
  ⚠⚠ **Amendment (2026-09-29, CR-2 B0): a second destructive surface, operator-only.**
  `/ops/content-reclaim/{plan,enqueue,drain}` (`OperatorRouting.kt`, `ContentReclaimJob`) delete
  `content_blob` entries, and their bytes, that **no `content_ref` row describes**: the duplicate-emit
  chunk sets (published content re-emitted under fresh keys), which nothing else frees. The statement above ("the only one in the system") no longer
  holds. Its guards:
  - **Registered only with `RELAY_OPERATOR_TOKEN` set** (and a durable store); a plain 404 otherwise.
  - **Answers only a loopback socket peer**, judged by the raw connection, never the forwarded headers
    `XForwardedHeaders` trusts, so the public proxy cannot reach it.
  - **And the token**, compared in constant time.
  - A **three-step** operator flow. `plan` writes nothing. `enqueue` must quote the plan's
    **candidate digest** (sha256 of the exact candidate set; an equal count could hide a different set), and refuses while any content with an edge and a stored manifest lacks a
    description (its chunks would be indistinguishable from garbage). `drain` re-checks every ref and
    deletes under the store lock.
  - It **spares**:
    - described refs;
    - the derived manifest of every known content;
    - blobs touched within the idle window;
    - any ref PUT after it was queued;
    - other owners' identical refs.
  - **New relay state:** `content_free_queue(owner_id, ref, enqueued_at)`. It holds refs the relay
    already held as `content_blob` keys, so no new metadata.

  House Rule #1 holds: the relay reads its own index tables only, never content. The accepted risk is
  that of any operator deletion. A live content whose description is missing **and** whose manifest is
  not derivable from a known content id would be deleted. This is why the runbook
  (`docs/how-to/relay-content-reclaim.md`) requires the v6 declaration backfill to have completed first.
  **HR #1 intact**: the route reads no ciphertext and the reclaim is the P3a `ownerId`-keyspace
  primitive, unchanged.

  *Amendment (retention-quota-model.md §5 / D5, P1, 2026-07-18): the content-plane
  usage surface `GET /sync/usage`.* A new authed endpoint returns
  `{ "usedBytes": <long> }` = `ContentBlobStore.bytesUsed(ownerId)` for the
  **caller's own** `ownerId` (resolved from the device-key JWT via `requireOwnerId`,
  exactly like `/sync/content`; 401 unauthenticated). This reveals to an owner **their
  own** total stored byte-count over an authed channel — **not a new leak**: it is the
  byte COUNT the relay already keeps for the per-owner quota gate (§8.15), not anything
  derived from decrypting blob contents (HR #1 intact). Strictly caller-scoped: `ownerId`
  is the verified-token owner, never client-supplied, so there is **no cross-owner peek**
  (an owner cannot query another's usage). A6 metadata delta: nil beyond what the quota
  gate's `507 over_quota` already implied to the same owner. Feeds the client's
  `StorageStatus` fill gauge (`EntitlementStorageProvider`, replacing its `0L` mock).

  *Amendment (content-plane-refcount.md §9.17, 2026-09-25): the write probe
  `POST /sync/push/probe`.* An authed, bodyless endpoint that runs exactly `/sync/push`'s
  gates — `requireOwnerId` (401), then the entitlement write-gate (402) — and answers `204`
  when a push from the caller would pass both. It reveals to the caller **only whether their
  own writes are currently accepted**, which any push attempt already reveals; `ownerId` is the
  verified-token owner, never client-supplied, so there is no cross-owner peek. Stateless: no
  row written, no ciphertext read (HR #1 intact). A6 metadata delta: the relay can observe *when*
  a client is about to move content (the client probes before each move of content) — a timing
  signal of the same class as the push that follows it moments later, and nil beyond it.

Residual risk (overall): **Low** for confidentiality + integrity of A5
under AD5 (unchanged from P1 — all crypto stays client-side);
**Low–Medium** for A6 metadata, the delta being the P3 access-pattern
*timing* dimension explicitly accepted as a v1 residual with the P4
mitigation noted above.
*Amendment (content-plane-refcount.md CR-3 / B, 2026-09-08): the relay gains a
`content_edge` table — a client-**declared** reference set. Declared here; nothing
writes to it yet.*

⚠⚠ **This contradicts the residual above**, which reads *"a manifest refcount stays
HR#1-impossible … No new relay-visible wire shape."* Both halves need correcting.
**"HR#1-impossible" was always about a relay-*derived* count** — the relay cannot read
a manifest, which remains true and is not in dispute. It never ruled out the relay
being **told**. ADR 0017 D6 / alternative (iv) did rule that out, on privacy grounds,
and that rejection was falsified by ST-3b shipping `/sync/content/delete`, which
discloses the same ref set (ADR 0017, reopened 2026-09-08).

**What the table holds:** `(ownerId, holderHash, propertyId) -> contentId`. `holderHash`
is the entity-id hash a tombstone already carries and the relay already sees (A6);
`contentId` is an opaque id the relay already stores per blob. **Nothing is decrypted,
and no manifest is read — HR #1 is intact.**

⚠ **What is genuinely new is the *grouping*:** which content a given holder references,
and that two holders reference the same content. Previously inferable from **block
timing** (A6, accepted) and from the upload/delete bursts T-T-07 already records as
*"timing correlation can still reveal editing patterns … Accepted, documented"* — so
this converts an accepted inference into an explicit, durable fact. That is a
sharpening of an accepted exposure, not a new class. ⚠⚠ **Bounded by there being no
cross-*user* dedup**: a random CEK gives two users' copies of one file different
ciphertext and different refs, so an edge can never reveal *"these two users hold the
same file."* The only sharing it exposes is one owner's own content shared into a realm
they shared it into — the edge already analysed at **Low** under the shared-realm
residual above.

⚠⚠ **No disclosure has occurred yet.** This PR adds the table, its store and its tests;
there is **no route and no client**, so nothing is written and the wire shape is unchanged
*in practice*. The staging is deliberate and matches RH-0's: the surface lands first so it
can be observed before it decides anything. **The PR that adds the declare / undeclare
routes owes the residual update** — at that point the "no new relay-visible wire shape"
clause above becomes false in fact, not only in intent.

*Amendment (content-plane-refcount.md CR-3 / the declaration seam, 2026-09-08): the routes
now exist. This is the update the paragraph above said it owed.*

**The wire shape is new in fact.** `POST /sync/content/declare` takes a batch of
`(holderHash, propertyId, contentId)` and upserts each; `POST /sync/content/undeclare`
takes a batch of `holderHash` and drops every edge they hold. Both are JWT-required.
⚠ **Neither carries an `ownerId`** — it comes from the verified token, the `/sync/usage`
posture — because a body-supplied owner would let one tenant declare into another's
keyspace, or undeclare it. Additive routes, so no `RELAY_PROTOCOL_VERSION` bump
(`RelayProtocol.kt`, normative: *"a client that never calls a new route cannot be broken by
its existence"*), and therefore no coordinated redeploy.

⚠⚠ **Superseded by CR-2 B3c (2026-10-02):** both routes' edge halves are deleted — the edge form of
`declare` and all of `undeclare`. Edges now arrive only on `POST /sync/push`, and are removed only by
a tombstone's cascade or a reap (see the B3c binding amendment above). The test this paragraph cites
(`ContentEdgeRoutingTest`) went with them.

⚠ **Still nothing writes.** The client half — declare at emit, undeclare at the tombstone
drain — is not built, so in a deployed relay the table stays empty. The exposure below is
what the routes *make possible*, not what is happening.

**Two things are genuinely new, and neither is a confidentiality change:**

- **The grouping becomes durable and explicit** — as analysed above. A relay is now *told*
  which content a holder references instead of inferring it from block timing (A6,
  accepted) and upload/delete bursts (T-T-07, *"timing correlation can still reveal editing
  patterns … Accepted, documented"*). A sharpening of an accepted exposure; **Low**.
- ⚠⚠ **`undeclare` is a deletion-adjacent primitive once enumeration (option C) lands.**
  Dropping an edge is what will make a blob *look* orphaned, so the owner scoping above is
  not hygiene — it is the control that stops one tenant stranding another's content. It is
  pinned by test (`ContentEdgeRoutingTest.oneOwnerCannotUndeclareAnothers`, verified by
  mutation: keying the drop on `holderHash` alone fails it). Within its own keyspace an
  authenticated device can strand its own content — but such a device can already delete
  the file outright, so this adds no capability, only a second route to the same end.
  ⚠ It is also why C must not free on an empty edge set alone: ADR 0006's recreate
  semantics mean "no edges" is not "nothing will reference this" (`content-plane-refcount.md`
  §6c.1).

⚠ **New abuse surface, unmitigated and named rather than fixed:** `declare` writes rows for
arbitrary `holderHash` values without uploading any bytes, so an authenticated owner can
inflate the table cheaply. Per-request size is capped (`MAX_DECLARE_EDGES = 512`); **the
per-owner row count is not**, and today's quota counts bytes, not rows. Bounded to the
attacker's own keyspace and to holding a valid device JWT. The mitigation — cap rows per
owner, or bound them by that owner's blob count — belongs with the enumeration slice that
first gives the table authority; recorded in `content-plane-refcount.md` rather than fixed
here, because a cap enforced before anything writes would only be guesswork about a
distribution nobody has measured.

**Residual: Low.** One new stored metadata relation and two additive authed routes; no
decryption, no key custody change, no manifest read — HR #1 intact — and a grouping fact
that timing already disclosed. It becomes **Low–Medium** only if enumeration ships without
the row cap above, where the residual is availability (a full table), not confidentiality.

*Amendment (content-plane-refcount.md CR-4, the client half, 2026-09-09): the edges are now
actually written.* The clause above — *"Still nothing writes"* — is false from this PR on.
Two seams produce them, both in the sync layer and neither in any operation:

- **Declare** at `SyncObserver`'s content emit, once per `OnDemand` property whose bytes land:
  `(holderHash, propertyId, contentId)`, upserted.
- **Undeclare** at `SyncScheduler.drainPendingTombstones`, by holder hash, for the tombstones
  that actually reached the relay, batched.

**Nothing new reaches the relay that it did not already see**, and this is the whole of the
delta. `holderHash` is the `targetEntityIdHash` every `SyncBlock` and `DeleteBlock` already
carries (A6). `contentId` is the id the relay already stores per blob and already receives on
`PUT`. `propertyId` is the one genuinely new field (⚠ since 2026-10-04 a keyed per-holder tag on the
push path, see the CR-2 B3 keyed-property-tag amendment) — and ⚠ note it is the *opposite* of the
metadata plane's posture, where `DataBlockPayload` deliberately hides the property id inside
the ciphertext (§"Property-ID exposure to relay"). Here it is in the clear, because the edge is
keyed by it: two `OnDemand` properties on one entity own different content, and an edge that
could not tell them apart would make a thumbnail's declaration evict the file's.

⚠ **What that discloses, precisely:** for content the relay already holds, which *slot* of an
entity it belongs to — "this blob is a thumbnail, that one is file bytes". A size distribution
the relay can already measure implies the same split, and property ids are per *service*, not
per user. It is a sharpening of A6, at the granularity the content plane already had. **Residual
unchanged at Low.**

⚠⚠ **The exposure is bounded by what actually declares.** Four populations legitimately carry no
edge (`content-plane-refcount.md` §9.3): everything uploaded before CR-4 — including the whole
existing corpus — a declaration whose request failed, the legacy/shared-realm manifest path, and
the not-yet-built recreate re-declaration. So the table is a *partial* view for the foreseeable
future, which is a privacy floor and a correctness ceiling at once: **nothing may free on an
absent edge alone**, and CR-5 must confirm against a client's own live walk. That is recorded as
a precondition, not an aspiration.

**Residual: Low**, unchanged. No decryption, no key-custody change, no manifest read; one
already-visible identifier pair plus a per-service slot name.

*Amendment (content-plane-refcount.md CR-4b, 2026-09-09): the declaration now carries the blob
**ref set**, in a second table.* Found in review: an edge names a `contentId` while the blob store
is keyed by ref, so the relay could classify manifests and nothing else — the table could not have
freed a chunk. `content_ref(owner_id, content_id, ref)` closes that, declared on the same request
as the edge.

⚠ **The new disclosure is the ref set, and it is not new in kind.** ST-3b's
`POST /sync/content/delete` has sent the client's ref set to the relay on every reclamation since
`drive-upload-stop-and-reclamation.md` §4.9 — it is the *exact* disclosure ADR 0017 D6 rejected and
the falsification that reopened it (§2 of the proposal). What changes is the **tense**: the relay
now learns a content object's ref set at upload rather than at deletion, so the grouping *"these N
blobs are one file"* is durable rather than inferable from a delete burst. ⚠ It was always
inferable from upload timing (A6, accepted) and T-T-07 already records that *"timing correlation
can still reveal editing patterns … Accepted, documented"*. Sharpening, not a new class.

⚠⚠ **It also discloses a size distribution more precisely than before**: chunk count per content
object is now explicit, and at a fixed 2 MiB chunk size that bounds a file's size to within one
chunk. The relay already stores `byte_len` per blob (`content_blob`), so it could sum this for any
owner; what it could not previously do is attribute the sum to *one file*. Accepted as an extension
of the A6 metadata residual, at **Low** — it is a file size, never its content, and the same
information is available from the burst of `PUT`s that created it.

⚠ **HR #1 is untouched.** The relay is told the refs; it decrypts nothing, reads no manifest, and
derives nothing. Every ref is already a key it stores bytes under.

**Hardening in the same PR**, from that review: a malformed edge is now rejected rather than
silently skipped (a dropped declaration leaves live content looking orphaned — the loss direction),
every identifier is length- and charset-bounded, and a request's **total** refs are capped at 8 192.
⚠⚠ The last was a regression this PR introduced and a second review caught: a per-edge cap alone left
512 × 4 096 = 2.1 M refs, ~150 MB of JSON, in one authenticated request — a far cheaper lever on the
row count than the inflation named above. ⭐ Together these close **half** the residual: row *size*
and per-request volume are now deterministic, where before a 200-character holder hash was accepted.
The per-owner row *count* stays open, and stays with CR-5.

**Residual: Low**, unchanged.

*Amendment (content-plane-refcount.md CR-5, 2026-09-09): two routes that read and act on the edge
and ref tables.* `GET /sync/content/orphan-candidates` returns the owner's own contentIds that are
**described** and that no live edge names; `POST /sync/content/reclaim` frees the refs those
descriptions attribute to them.
⚠ **Both routes were removed in CR-2 B6** (2026-10-04, the B6 amendment in §8.3); kept as the record.

⚠ **The disclosure is the owner's own data, to the owner's own device.** Candidates are contentIds
the relay already stores per blob and already received on `PUT`; the owner is the verified token's,
never a body field, so no keyspace but the caller's is readable or reachable. ⚠⚠ No manifest is
read and nothing is decrypted — the relay computes the difference of two tables it was **told**.

⚠⚠ **`/sync/content/reclaim` is the second route in this project that frees a user's durable bytes,
and it is deliberately narrower than the first.** ST-3b's `/sync/content/delete` takes **refs** and
obeys; this takes **contentIds**, re-derives candidacy itself, and frees only what `content_ref`
attributes to them. So a client cannot widen the blast radius by naming a blob, and an edge that
landed since the candidate read is caught relay-side. Content the relay has no description for —
every byte uploaded before CR-4, the legacy manifest path, Fit — is **unreachable from this route by
construction**, which is the safety property rather than a gap.

⚠ It ships behind the same `reclamationDryRun` switch as the ST-3b sweep, so a deployment that has
not opted into byte deletion does not opt into this one.

**Residual: Low.** No new relay-visible identifier class, no decryption, no key-custody change; one
additional read of state the relay already holds, scoped to its owner, plus a delete primitive
strictly narrower than the one already shipped.

*Amendment (content-plane-refcount.md §6e, phases N0–N2, 2026-09-16): an age window on enumeration,
a holder-less form of `declare`, and `GET /sync/content/contents`.*

⭐ **The describe wire discloses strictly less than the edge it will replace.**
`POST /sync/content/declare` now also accepts `{"contents":[{"contentId","refs"}]}`, which writes
`content_ref` rows and **no `content_edge` row** — so for those entries neither a `holderHash` nor a
`propertyId` reaches the relay. `propertyId` in the clear is the one field that inverted the metadata
plane's posture (the CR-4 amendment above flags it as the opposite of `DataBlockPayload`, which hides
the property id inside the ciphertext), and it is on the wire only because an edge is keyed by it. The
ref set this form does send is the CR-4b disclosure already analysed at **Low**, unchanged in kind.
⚠ The edge half is untouched and clients keep using it — the disclosure *narrows* only when they stop,
which is a later phase, so this amendment records a ceiling coming down rather than one that has.

⚠ **`GET /sync/content/contents` reads the owner's own contentIds to the owner's own device**, paged,
with the owner taken from the verified token and never a body field. Same class as
`orphan-candidates` above and a wider set than it — every **described** contentId rather than only
the undeclared-by-any-edge ones — but every element is an identifier the relay was told on `declare`
and already stores, and no keyspace but the caller's is reachable. ⚠⚠ It answers *described*, never
*unreferenced*: under C′ the relay holds no reference graph, and "unreferenced" is a fact about live
rows that only a device has. No manifest is read and nothing is decrypted.

⚠⚠ **The age window is a withholding, not a disclosure.** Content **any** of whose blobs was touched
inside a 24 h window is excluded from both enumeration reads, because chunks are `PUT` before the
description lands and an upload in flight is otherwise indistinguishable from an orphan to a sweeping
device. ⚠ *Any*, not *all*: the latter expires at `firstChunkPut + window` and would offer a long
upload during its own upload. It is deliberately **not** applied to `reclaim` or `/sync/content/delete`: a file
uploaded then deleted carries a fresh `last_referenced_at`, and a window there would refuse exactly
the delete-driven reclaim that frees space at once. ⚠ Youth is a sound reason to *withhold* and never
a reason to *free* — `last_referenced_at` is refreshed by `get` as well as `put`, so a download looks
like an upload.

**Residual: Low**, unchanged. Additive routes and an additive request field, so no
`RELAY_PROTOCOL_VERSION` bump; one read of state the relay already holds, scoped to its owner, and no
new freeing primitive.

*Amendment (content-plane-refcount.md §6e, phase N1 — the reference handle, 2026-09-19).*
⚠ **Retired in CR-2 B6 (2026-10-04):** `content_reference` is dropped and the relay holds no handles —
see the B6 amendment above. Kept as the record of what was disclosed while it existed.

⭐ **What the handle adds is a COUNT, and nothing that says what is counted.** The `contents` form now
carries a required `referenceId`, stored in `content_reference(owner_id, content_id, reference_id,
last_hlc, last_declared_seq)`. The relay learns **how many live references name a content object** —
one more bit of shape than "this content is described" — and learns nothing about which entity, which
property, or which device holds any of them. ⚠ That is the field this phase *buys back*: the count is
what makes two live rows sharing a `contentId` (a conflict-split repair, a rename-vs-edit, dedup) hold
the bytes at two rather than one, and it replaces the holder graph that answered the same question by
naming holders.

⚠⚠ **The handle is opaque, random, and unkeyed, which is a deliberate choice against two weaker
forms.** A *derived* id (`H(holder‖property)`) would leak the grouping the wire exists to retire and
would move when the row moves; a *keyed* one could not survive a shared-realm key rotation. A random
handle needs no key to be opaque, and the relay never recomputes it — it stores and compares. ⚠ The
optional `hlc` stamp is an HLC the metadata plane already puts on the wire; it discloses ordering the
relay can see anyway.

⚠⚠ **A new inflation surface, answered the same way §9.4 answers the others.** Handle rows cost no
bytes, so the byte quota does not bound this table: a device can describe fresh random handles for one
`contentId` indefinitely. ⇒ a per-owner **row cap**, refused `507`, refused **whole** so a partial
write cannot leave content described-without-a-handle, and *not* applied to a re-describe of a handle
already held (which inserts no row, and refusing it would wedge a correct client's retry for ever).
⚠ What is **not** yet built, and is owed before N4 acts on this count: hitting the cap must also
**freeze byte frees** for that owner, and the freeze must **latch** — a rejected describe is the
relay's only evidence that its reference set is incomplete. Nothing frees on handle count today (the
reclaim is still edge-derived), so there is nothing to freeze yet; the prerequisite is recorded in
the route and in `content-plane-refcount.md`.

**Residual: Low.** One additional opaque identifier per described content and a per-content count,
both supplied by the owner's own devices and readable only within the owner's keyspace; no manifest
is read, nothing is decrypted, and no new freeing primitive lands with it.


**T-R-10. File-modification history: one `Eager` block per file change.**
Actors: AD5. Invariants: I1 (E2EE), I2 (integrity), I6 (relay learns no
user-data semantics). Assets: A5 (user data), A6 (metadata).
*Amendment co-lands with
[`file-service-history.md`](../proposals/file-service-history.md) Phase 2
(emit hooks) per House Rule #2.* Every File write op now emits a
`FileModification` (`Syncable(Eager)`) — `Created` on create,
`ContentChanged` on overwrite, `Renamed`/`Moved` on a same-/cross-parent
move — synced as an ordinary `SyncBlock` on the existing ordered drain.
- **Payload is fully inside the ciphertext (unchanged wire shape).** Every
  identifying field (`fileId`, `deviceId`, old/new name, old/new parent
  path, `manifestId`, `timestamp`) lives in the encrypted body, exactly
  like `DataBlockPayload` (T-R-08). Relay-visible wire metadata is
  unchanged: `(blockId, ownerId, hlc, encryptedPayload,
  targetEntityIdHash?)`. The relay does not learn that a block is a
  modification, which file it concerns, or what changed.
- **New leak: per-modification cadence.** The relay observes one
  additional small block per file change, so the *count* and *timing* of
  modification blocks per realm is in the clear — a finer-grained view of
  write activity than the pre-history model (which emitted only the file
  block itself). This is traffic analysis over opaque blocks, not content
  disclosure; it falls under the metadata exposure already accepted for
  AD5 (A6), extended by the per-modification timing dimension. It is the
  same *shape* as T-R-09's per-open fetch-timing residual.
- **Zero-trust unchanged (House Rule #1).** Encryption happens on Control
  before the block is queued; the relay only ever sees opaque ciphertext.
- **`AccessedFileModification` deliberately excluded from v1** precisely
  because a per-open emit would worsen this cadence leak to one block per
  file *read* (`file-service-history.md` §2.3); if ever added it must be
  local-only (skip sync) and opt-in.
- **Retention (§4.6, landed) reuses the existing tombstone surface.** The
  per-file cap trims over-cap rows by emitting ordinary `DeleteBlock`s
  (`targetEntityType = FileModification.ID`) — no new wire shape, the same
  tombstone mechanism as file/device deletes (ADR 0006). It adds a *bounded*
  delete cadence (at most one trim per over-cap write) in the same A6 metadata
  class as the emit cadence above; the cap also *reduces* total modification
  volume the relay stores. No separate amendment.
Residual: **Low–Medium** for A6 metadata under AD5 (a passive relay learns
write cadence over opaque blocks); **Low** for A5 confidentiality +
integrity (payload + integrity stay client-side, identical to T-R-08).
Mitigations (block batching, decoy emits, padding) are the same set
spec'd for the blob channel (T-R-09 / [ADR 0017](../adr/0017-file-content-two-plane-channel.md)
*When to revisit* #1) and not built in v1; if T-R-09's residual becomes
unacceptable, the modification cadence joins the same mitigation track.

**T-R-11. Delta-batch container blocks (task-scoped sync lane).**
Actors: AD5. Invariants: I1 (E2EE), I6 (relay learns no user-data
semantics). Assets: A5 (user data), A6 (metadata).
*Amendment co-lands with the TS-1 wire primitive
(`core-background-processing.md` §10.2) per House Rule #2; the sender-side
lane arrives in TS-2+.* A new `SyncBlock` payload variant,
`DeltaBatchPayload` — a container of per-entity `SyncDelta`s, each
with its own HLC — joins `DataBlockPayload` / `FileContentManifestPayload`,
so one bulk-task checkpoint rides the mailbox as one block instead of N.
- **Everything new lives inside the ciphertext.** The variant discriminator,
  the delta list, each delta's HLC, per-delta `propertyId`, and per-delta
  `targetEntityIdHash` are all inside the encrypted payload. Relay-visible
  wire metadata is unchanged: `(blockId, ownerId, hlc, encryptedPayload,
  targetEntityIdHash?)`. The relay cannot distinguish a checkpoint block
  from any other block.
- **Per-delta target hashes move INSIDE the ciphertext.** A container block
  sets the wire-level `targetEntityIdHash` to null (§10.2 D2) — the relay
  sees strictly *less* per-entity routing metadata than for the equivalent N
  standalone blocks (which each expose one opaque hash). Consequence: lane-
  carried entities are not addressable via `fetchEntityBlocks`; receivers
  persist rows on ingest, and on-demand *content* stays manifest-addressed
  (unchanged).
- **Cadence leak strictly improves.** This is the "block batching"
  mitigation T-R-08/T-R-09/T-R-10 name for the A6 write-cadence residual: a
  bulk import that today emits N blocks (N observable count/timing data
  points) emits ~N/500 container blocks. Block *size* becomes a weak proxy
  for batch size, bounded by the sender-side caps (500 deltas / 256 KiB —
  §10.3); size variance over opaque ciphertext is within the A6 exposure
  already accepted for AD5.
- **Format compatibility, not security.** A pre-TS-1 peer parses the
  unknown payload type to null and drops the block — no crash, no misapply,
  but silent row loss on that peer; handled by the `TENSHU_STORAGE_FORMAT_
  VERSION` lockstep bump between clients (House Rule #9), not by this
  model. ⚠ Since E2e the relay's 426 gate plays no part in this: it reads
  `RELAY_PROTOCOL_VERSION`, its own wire contract, and never the payload
  version (`core-schema-evolution.md` §7.5/§7.8).
Residual: **Low** for A5 (payload confidentiality/integrity identical to
T-R-08); A6 metadata exposure *reduced* relative to the pre-lane baseline.

**T-R-12. Client-triggered relay memory exhaustion via unbounded history read.**
Actors: AD4 (an authenticated realm device, malicious *or* merely reset).
Invariants: I6. Assets: availability — **for every tenant, not only the
requesting one**.
*Amendment co-lands with the `/sync/changes` page cap per House Rule #2.*
`getChanges`/`getTombstones` answered "every row with `seq > since`" in one
response, materialising the result set in heap before serialising it. A device
whose cursor is 0 — a fresh install, a store wipe, or simply a client that
sends `since=0` — therefore asks the relay to hold that owner's **entire
history** at once. Cost is a function of history length, which the requester
controls by writing.
- **Observed, not theoretical.** On 2026-07-29 a 49 070-block / 244 MB history
  met a relay whose ergonomic max heap was 242 MB: every pull died with
  `OutOfMemoryError` after ~15 s of saturated CPU. The requesting device read
  the 500 as "relay unreachable" and left its cursor at 0, so it re-asked every
  tick — an unintentional but self-sustaining loop.
- **The tenant boundary is what makes this a threat, not just a bug.** The
  relay is single-process and (on the deployed droplet) single-core, so one
  owner's unservable pull degraded *unrelated* owners' requests to 15-second
  responses. Authentication does not mitigate it: any admitted device can reset
  its own cursor, and `since` is client-supplied.
- **Defense.** Responses are capped at `SYNC_CHANGES_PAGE_LIMIT`
  (`core-common/.../RelayProtocol.kt`), so peak response memory is a function
  of that constant rather than of history length; clients walk the backlog one
  page per tick. Container heap raised off the 25% ergonomic default
  (defence-in-depth only — the cap is what bounds the cost). No confidentiality
  or integrity delta: the relay still stores and serves opaque ciphertext
  (House Rule #1), and pagination changes only how much of it moves per
  response.
- ⚠ **Not a rate limit.** A client may still request pages in a tight loop; the
  cap bounds the cost of *one* response, not of a determined caller. Per-device
  request throttling remains unbuilt and is the correct home for that (the
  published-share transfer caps in `drive-folder-link-sharing.md` D9 are the
  nearest existing precedent).
Residual: **Low** for the accidental case (the observed one, now bounded).
**Medium** for a deliberately abusive admitted device until request throttling
exists — bounded in practice by the trusted-device set (a realm's devices are
admitted by its owner, ADR 0007).

**T-R-13. The client-driven content delete is aimed at another owner, or at live content.**
Actors: AD1, AD3, AD5. Surfaces: 3. Assets: A5.
⚠ **Retired by CR-2 B6 (2026-10-04):** `POST /sync/content/delete` and `/sync/content/reclaim` are
removed and no client frees content; see the B6 amendment in §8.3. Kept below as the record of what the
route permitted while it existed.
`POST /sync/content/delete` (ST-3b, `drive-upload-stop-and-reclamation.md` §4.4) lets an owner's own
device name content refs to drop. It is the only per-file reclamation House Rule #1 permits — the
relay cannot know which blobs a deleted *file* orphaned, so the client, which holds the plaintext
manifests, decides and the relay obeys.

*Cross-tenant.* `ownerId` is taken from the verified device JWT and **never** from the request body,
which carries no owner field at all. A ref belonging to another owner is silently skipped, so the
response is also not an existence oracle for other tenants. Same scoping rule as `PUT`/`GET`
`/sync/content/{ref}`; pinned by `delete_cannotReachAnotherOwnersContent`, which was verified to
fail against a deliberately body-trusting implementation.

*Self-inflicted.* ⚠⚠ A device **can** destroy its own owner's live content, and for most owners the
relay holds the only durable copy (content is `OnDemand`; a device's local plaintext is an evictable
cache). The relay cannot validate the claim "nothing references this any more" — that is precisely
what House Rule #1 denies it. So the blast radius of a client bug is the owner's own files, and the
correctness burden sits entirely on the client's orphan computation. ⚠ A compromised *admitted*
device therefore gains a data-destruction capability it did not previously have; it already had
`DeleteFile` over the metadata plane, so this widens the consequence (bytes, unrecoverable) rather
than the reach.
Residual risk: **Low** for cross-tenant. **Medium** for a compromised or buggy owner device, and
explicitly accepted: the alternative is no per-file reclamation at all (§4.4).

**T-R-14. One party fills the relay-wide storage ceiling and blocks everyone's uploads.** *(added 2026-11-06)*
Actors: AD2, AD3. Surfaces: 3. Assets: A5 (availability only).
`STORAGE_CEILING_GIB` (`StorageCeiling`, `docs/how-to/relay-storage-ceiling.md`) is the operator's
cost brake: past it, a new content `PUT` or share create gets `507` + `storage_ceiling`, for every
owner. That makes "fill the relay" a denial of service against all users' **new** uploads. Nothing
already stored is lost or hidden: reads, sync, and re-uploads of refs the owner already holds are never
refused, and the ceiling decrypts and inspects nothing (House Rule #1: it sums `byte_len` and declared
share-chunk counts, both already relay-visible).

*Cost of the attack.* Each verified Free account holds at most 5 GiB (the per-owner quota), so filling
a ceiling of C GiB takes C / 5 verified email addresses. Commerce's `SIGNUP_LIMIT_PER_DAY` /
`SIGNUP_LIMIT_TOTAL` cap how fast those can be minted, and counts only **verified** accounts, so a
flood of code requests cannot close sign-ups either.

*Share links.* Their bytes are charged at create, at the 8 MiB per-chunk cap, an upper bound because
chunk sizes are never indexed. ⚠ Shares are still **not** counted against the per-owner quota
(`docs/tech-debt.md`), so until that lands one entitled owner can consume ceiling headroom through
shares alone. The ceiling bounds the bill regardless; it is availability that this residual affects.

Residual risk: **Medium**, accepted. A full relay is an outage of new uploads, visible in the log
(`Storage ceiling reached`), and the operator's remedy is to raise the ceiling or take the abusive
accounts down. Without the ceiling, the same attacker gets an unbounded storage bill instead.

### 8.4 Client device

**T-C-01. Lost/stolen locked device.**
Actors: AD1–AD3. Invariants: depends on platform.
Defense: Keystore-backed keys are protected by device lock (TA2). Plaintext in app storage is
encrypted at rest under a keystore-resident key. File-level keys are not present in plaintext
anywhere on disk.
Residual risk: **Low** for modern platforms with hardware-backed keystores. **Medium** for platforms
without (document which are supported and which aren't).

**T-C-02. Lost/stolen unlocked device.**
Actors: AD1–AD3. Invariants: I1, I2 for this user.
Defense: Outside the protocol's control while the device is unlocked. Mitigation: user revokes the
device from another realm device, triggering epoch rotation (§8.5 T-K-01). Remote wipe if the OS
supports it.
Residual risk: **High** until the user notices and revokes. Any data accessed by the attacker is
already exfiltrated.

**T-C-03. Rooted/jailbroken device running the app.**
Actors: AD3, AD4. Invariants: I1 on that device.
Defense: Limited. Root-detection heuristics can refuse to run, but are bypassable. The core claim is
that *the un-rooted device's data* is still safe — a rooted realm device is equivalent to a
compromised member (T-P-05), and the defense is revocation + rotation.
Residual risk: **Accepted.** Rooted devices are explicitly untrusted by the core security stance.

**T-C-04. Plaintext leakage to OS backups (iCloud / Google Drive).**
Actors: AD4, AD5.
Defense: App storage is marked "do not back up" on platforms that support the flag (
`NSURLIsExcludedFromBackupKey` on iOS; `android:allowBackup="false"` on Android); keystore items are
by default excluded from cloud backups on both major mobile platforms.
Residual risk: **Low** if the flags are correctly set. This is a checklist item for the
implementation, not a protocol defense.
*Amendment (iOS shared replica, [`ios-shared-store-sqlite.md`](../proposals/ios-shared-store-sqlite.md),
D3/D3b, S0b-4, 2026-11-01):* until this change no iOS code set `NSURLIsExcludedFromBackupKey` on the
route-1 replica, so the checklist item above was not met for it on iOS. On every build since B.22
(2026-11-05; there is no switch), the replica's `replica/` directory carries the flag, asserted and
read back at every open (no store is handed out without it), and so do `files/` (`IosFileDatabase`),
`upload-staging/` and the HLC watermark `sync-hlc/` in the same container (best effort, retried at
the next open), which had the same gap. The flags are asserted on a **locked** phone's open too,
from the first unlock on (a background wake's open; only before the first unlock are they
deferred). ⚠ **What is not covered:** an old file store found at launch (`objects/`) and the
`replica-legacy.removing-*` directory it is renamed into keep the container's defaults for the
short time until the background delete removes them; and a `sync-hlc/` made again by a raise
between two opens is flagged only at the next open.
*Amendment (2026-11-05, privacy policy `2026-11-05`):* also outside the flags, because they live in
each app's own sandbox and not the App Group: Fit's workout outbox and in-flight recording journal
(route, steps, strap heart rate, and workouts promoted from Timeline traces, until saved — held
for as long as Control has no realm; never HealthKit data), Timeline's in-progress move journal
and its Wi-Fi network→place map, Calendar's mirror digests, and settings flags (`NSUserDefaults`,
including Control's app lock and install sentinel). They go to the device backup without
app-level encryption: an encrypted backup protects them, an unencrypted local (Finder) backup
holds them in plaintext. The policy discloses them (`docs/tech-debt.md`).

**T-C-05. Plaintext leakage to OS crash reports, logs, accessibility APIs.**
Actors: AD4, AD5, AD3.
Defense: Do not log plaintext. Mark sensitive views as non-screenshottable on platforms that support
it (`FLAG_SECURE` on Android; `UIScreen.isCaptured` handling on iOS). Do not expose plaintext to
accessibility services beyond what's necessary for screen readers.
Residual risk: **Low** if implemented. High-risk screens (recovery code display, pairing approval
with SAS) must enforce this.

**T-C-06. Plaintext in app caches, thumbnails, temp files.**
Actors: AD1–AD4 with on-device access.
Defense: Cache files use the same at-rest encryption as primary storage. Thumbnails of encrypted
content are themselves encrypted. Clipboard operations of sensitive content auto-clear after a short
timeout.
Residual risk: **Low** if implemented.

**T-C-07. Shoulder-surfing the recovery code.**
Actors: AD1–AD2.
The recovery code is shown once at signup. An attacker behind the user reads it off the screen.
Defense: Recovery code display screen uses `FLAG_SECURE`/equivalent. A "cover to reveal" interaction
hides the code by default. The user is prompted to explicitly confirm they've stored it before the
flow proceeds.
Residual risk: **Low** given the UI treatment.

**T-C-08. Wrapped master keypair lifted from web IndexedDB.**
Actors: AD1–AD4 with read access to the apex origin's IndexedDB
(physical access to an unlocked browser profile, malicious extension
with `indexedDB` permission, profile sync to a compromised account).
Assets: A1.
On web, the apex caches A1 wrapped under a WebAuthn-PRF-derived key in
the `tenshu-sdk-master-keys` IDB (per
[`docs/web/architecture.md`](../web/architecture.md) §4.4). An attacker
who reads the wrapped blob without also extracting the wrap key obtains
ciphertext + 24-byte nonce + 16-byte Poly1305 tag — useless without the
PRF output, which only the user's authenticator can produce on a
matching `navigator.credentials.get()` ceremony bound to the apex
origin.
Defense: The wrap key derives from the WebAuthn `prf` extension's
per-credential output, not from anything stored on the device. Rotation
of the wrap key ≡ rotating the underlying passkey credential (which is
authenticator-bound). The blob is XChaCha20-Poly1305 with a 32-byte
key; even with full ciphertext access, the attacker faces a
brute-force search over 2^256.
Residual risk: **Low.** Read access to the IDB without the user's
authenticator gives ciphertext only. The attacker must additionally
either (a) trigger a passkey ceremony at the apex origin (requires
user presence + UV) or (b) compromise the authenticator hardware.
Both reduce to the existing physical-access / authenticator-compromise
threat surface.

**T-C-09. Persistent cleartext materialised entity/relation store on disk
(Android/Desktop/iOS).**
Actors: AD1–AD4 with on-device storage access. Assets: A5/A6 (folder/file
structure + metadata).
Per [`core-sql-persistence.md`](../proposals/core-sql-persistence.md), the
proxy's materialised entity/relation state moved from an in-memory
`ObjectStore` to a durable `SqliteObjectStore` (a single-table SQLite
blob-KV, Android + Desktop). That state — folder/file structure, sizes,
relation graphs — therefore now **persists to disk in cleartext**, where
before it was in-memory only and lost on process death. This is **not** a
relay/wire change: no new block shape, no protocol change, **A1–A4 and A9
are untouched** (those govern keys + relay ciphertext; this is purely
local materialised state derived from the authoritative relay block log).
House Rule #2's relay/protocol trigger does not fire; this entry is the
voluntary at-rest documentation the proposal commits to. On iOS the same
single-table blob store is `replica/objects.db` in the App-Group container
(A5 SQLite amendment), on every build since B.22, under
`CompleteUntilFirstUserAuthentication` and excluded from backup.
Defense: this is *consistent with the existing posture* — the
`FileDatabase` file-byte blob store already persists file bytes in
cleartext locally and relies on the same protection class: OS/device-level
storage protection (platform full-disk encryption, app-private storage
directory, `allowBackup=false` / `NSURLIsExcludedFromBackupKey` per
T-C-04, device lock per T-C-01), not app-level encryption. Lost inbound
state self-heals (re-pulled from the relay block log on next sync); the
only durability-critical content is the not-yet-pushed outbound
`PENDING_BLOCKS` tail, whose loss window the transaction-boundary flush
barrier bounds to one in-flight transaction.
Residual risk: **Low** on modern platforms with hardware-backed full-disk
encryption + device lock — same class as T-C-01 / T-C-06, and no worse
than the pre-existing `FileDatabase` surface (file *bytes* are strictly
more sensitive than structure metadata). Whether to additionally encrypt
this local DB at the app layer (SQLCipher, or master-key-wrapped blobs)
is an explicit **open decision** tracked in §11a — deliberately taken
*jointly* with the `FileDatabase` encryption decision so the two
same-class surfaces do not diverge.

**T-C-10. Cross-app read visibility of realm Device names via `FileModification.Device`.**
Actors: AD3/AD4 — a co-installed (or compromised) client app on a realm device. Assets: A6
(metadata — device names).
[`core-reference-property.md`](../proposals/core-reference-property.md) adds a
`FileModification.Device` *reference value property*: the FileDetailsScreen activity list
resolves each modification's `deviceId` to the authoring `Device`'s name ("modified on
iPhone"). Any app that declares/reads the relation (Drive, via the `:drive-shared`
identity-service opt-in) thereby gains read visibility into the realm's `Device` *names* — a
capability expansion, **not** a relay/wire change: resolution is a local `Database.getData`
at the proxy, no new block shape, no protocol change, **A1–A4 and A9 are untouched**. House
Rule #2's relay/protocol trigger does not fire; this entry is the voluntary capability
documentation the proposal commits to.
Defense: device names are already user-facing labels carried in `Device` entity blocks —
encrypted to the relay, plaintext only to the realm's own proxy and apps (§4, §5 A6; since F7
the pairing name is sealed too, T-P-16) — and in a **single-user** realm every device belongs to that one user —
so the relation only surfaces the user's own data to the user's own apps. The proxy still
mediates access (no client reads the store directly); the relation resolves only entities
already local to the realm.
Residual risk: **Low** for single-user realms. **Revisit before shared/multi-user realms
ship:** there, file attribution would let one realm member infer *another* member's device
names, at which point the relation needs a per-member access gate — tracked alongside
`file-service-history.md` §4.5 (attribution) and T-R-09's attribution residual.

**T-C-11. Same-user malicious app connects to Control IPC (desktop).**
Actors: AD3 — a co-installed but untrusted process running as the user. Assets: A1 (by
extension), A14. Invariants: I1, I3.
A non-Tenshu binary opens a connection to `~/.tenshu/ipc.sock` (on Windows the same AF_UNIX
socket under `%USERPROFILE%\.tenshu` — there is no named pipe, `desktop-ipc.md` §4)
and sends a forged `Hello` block claiming to be a known Tenshu `AppId`. The
filesystem-permission posture (`700` on `~/.tenshu/`; on NTFS an owner-only DACL — user, SYSTEM,
Administrators — checked and repaired by `SocketDirectory`) blocks cross-user but not same-user
processes. On a signed macOS install the socket is in the App Group container instead; the
cross-user posture is unchanged there (`~/Library/Group Containers` is `700`, and the container
itself is `700` once `containermanagerd` owns it), and same-user reachability is unchanged too.
Defense: peer-credential check per [
`docs/protocol/desktop-ipc.md`](../protocol/desktop-ipc.md) §3 step 2. Control resolves
the connecting PID's binary path via `SO_PEERCRED` (Linux), `LOCAL_PEERPID` (macOS), or
`SIO_AF_UNIX_GETPEERPID` (Windows), reads its code-signing identity, and verifies it
matches a publication held by the local Rei source. The hello `AppId` must agree with the
peer-credential identity (A14) — discrepancy is a hard reject.
Residual risk: **Low** with peer-credential checks enforced; **High** without them. Open
edge cases (cache warmup before publication is observed, signature-change-on-update, the
Windows peer-PID race where `SIO_AF_UNIX_GETPEERPID` may return a PID whose process
has exited) tracked in [`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.F.49. ⚠ The macOS
verifier uses the audit token, not the pid, so the race does not apply there.
**Windows (2026-11-10, `WindowsPeerCredentialVerifier`;
[`desktop-windows-port.md`](../proposals/desktop-windows-port.md) §3.2, D1):** the peer is identified by
the **MSIX package identity of the process holding the socket** — its package family name must be one
of our package names *and* one of the publisher ids compiled into the binary (empty until the Partner
Center account exists, so today nothing attests). Not by a signature: a Store package lives in the
OS-protected `WindowsApps` folder with integrity enforced, whereas a per-user install's jars are
writable by the very AD3 this threat is about (§4.1 there; per-user installs are ruled out, D3).
⚠⚠ **The family name alone is forgeable.** It hashes the publisher *string*, which is public, so AD3 can
register a loose, unsigned manifest under our name and publisher (`Add-AppxPackage -Register`, Developer
Mode) and receive our exact family name, with its files in a folder AD3 writes. The gate therefore also
requires the package's **origin** (`GetStagedPackageOrigin`) to be one that needed a signature from a
certificate the machine trusts — Store, line-of-business, or developer-signed (our local test build) — and
refuses loose, unsigned and unreadable origins (amended 2026-11-10). Trusting a new certificate takes an
administrator, which AD3 is not. ⚠ Developer machines carrying the local test certificate
(`scripts/windows/msix-test-cert.ps1`, its PFX on disk, trusted machine-wide) let AD3 *there* sign a package
that passes; the test publisher id must never be in a release anchor.
⚠⚠ **Not covered: AD3 acting through one of our running packages.** A full-trust MSIX process runs at the
user's integrity, not as a protected process: AD3 can inject into a running `Tenshu.Drive`, duplicate its
attested socket handle, or plant `JAVA_TOOL_OPTIONS` (`-javaagent:…`) in the user environment before the
next launch. The gate stops impostor *processes*; it cannot stop the user's own malware from borrowing a
genuine one — the same limit as macOS without the hardened runtime's injection protections.
The pid race is narrowed, not closed: the gate holds an open handle to the peer for the whole read (no
reuse during the check) and rejects a process **created after verification began**, since the real
peer connected before the accept. A peer that exits, and a pid recycled to a process started, inside
the gap between its connect and the start of verification remains. ⚠ AD3 can widen that gap (queued
connections wait in the listen backlog) and can launch our packages at will, so a recycled pid landing on
one of ours is not a meaningful barrier; this residual folds into the borrowing limit above. The reverse direction (an app checking that the server is Control,
T-MD-01's defence) uses the same identity on Windows: the listening process must be the `Tenshu.Control`
package from an anchored publisher (`ControlServerCheck`). Verified end to end with side-loaded MSIX
packages on 2026-11-10. The socket directory `%USERPROFILE%\.tenshu` is gated by its DACL on Windows (2026-11-10):
the daemon repairs it to user + SYSTEM + Administrators and clients refuse one another principal can write to,
which closes directory squatting by another local user on NTFS; same-user processes remain the peer gate's job.
Residual risk on Windows: **Low** against impostor processes with a configured anchor; **Medium** against
same-user malware borrowing a running package (above), which the gate does not address. Until an anchor
exists a strict Control admits no one and a developer-mode run admits everyone, a dev posture since no
Windows build ships.

**T-C-12. Developer mode silently enabled by attacker.**
Actors: AD2 / AD3 with local write access to Control's config. Assets: A16; by extension
A1 via A14. Invariants: I3.
An attacker flips A16 without the user's knowledge — a malicious local installer, a script
in a compromised dev environment, a coerced user. With dev mode active, the peer-credential
check (T-C-11) downgrades unknown-signature to a warning, so any same-user process can
connect.
Defense: production builds hide the toggle behind a seven-tap reveal on Control's
build-number row — defence-in-depth via UI friction, not strong protection. The persistent
status-bar indicator while dev mode is active is the user-visible signal that a flip
happened. A16's storage shares A15's protection class (OS-level user-only permissions).
Residual risk: **Medium.** UI obfuscation is bypassable by an attacker with direct write
access to the config; the status indicator is the load-bearing defence. Open sub-question
([`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.F.49): whether dev mode is a blanket
bypass or requires a user-installed dev-only trust anchor — the latter would re-introduce
a cryptographic check and reduce this risk.

**T-C-13. Linux SS-absent fallback file recovery (offline disk read).**
Actors: AD3 / AD4 with offline read access to the disk. Assets: A1 (via A15). Invariants: I1.
An attacker dumps the Argon2id-derived encrypted master-key file (A15, Linux SS-absent
variant) and attempts to recover the master key offline.
Defense: in *passphrase mode*, brute-force resistance depends on the user's passphrase
entropy combined with Argon2id parameters pinned to OWASP recommendations — same KDF
posture as T-RC-02 for the recovery blob, applied to a smaller, user-chosen entropy
source. In *machine-bound mode*, an attacker who captures both the file AND the
machine-bound identifier defeats the encryption by design; the user is shown an explicit
warning before opting in and a persistent Settings indicator while the mode is active.
Residual risk: **Medium** in passphrase mode (depends on passphrase choice; user
education in the Settings UI is the operational mitigation). **High** in machine-bound
mode (documented residual, accepted with informed user consent). Cross-references
[`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.F.50 for the open sub-questions on the
machine-bound identifier's derivation source and forgotten-passphrase recovery UX.

**T-C-14. Forged local client self-registers as an installed app.**
Actors: AD2 / AD3 running a malicious process under the user's account (T-C-11 extension).
Assets: A18; by extension A14. Invariants: I3.
A hostile or forged local process attempts to insert itself into Control's Apps list — to
appear as a legitimately-installed app, harvest grants, or impersonate a first-party app —
by triggering an `AppRegistration` write.
Defense: registration is **proxy-mediated, never app-driven**
([`control-app-registration.md`](../proposals/control-app-registration.md) §3.1). The
`RegisterApp` write is reachable only *after* the IPC handshake's peer-credential +
code-signing check (A14) has established a verified `AppId`; a forged client fails that
check (T-C-11) and never reaches the registration step, so it cannot create an
`AppRegistration`. An app admitted under the developer-mode downgrade (A16) registers with
`verified = false`, surfaced as an "unverified" badge so it is never indistinguishable from
a signed app (composes with T-C-12). First-run registration is silent by design — consent is
a permission-grant surface, not a connection gate — which does not weaken this: the gate that
matters is the signature check, not a connect dialog.
Residual risk: **Low**, equal to T-C-11's — registration adds no new self-assertion path; it
inherits the handshake's authentication. Removal propagation across devices inherits the §9
HLC-consistency carve-out (a lagging device may briefly still admit a `Removed` app), bounded
to operation access on that one device since no key material is involved.

**Implementation status (A4, v1 — `docs/tracks/track-a-control-apps.md`).** The
handshake-triggered `RegisterApp` writer has shipped, but the **full code-signing check
described above is not yet built** (it is a later Rei phase — `tenshu-rei.md` §5.F,
`desktop-ipc.md` §3). v1's gate is therefore *weaker* than the end-state this threat assumes:
- The proxy resolves the connecting `clientId` against a **static first-party allowlist**
  (`AppIdentityResolver` / `BundledAppIdentityResolver`). A client not on the allowlist
  resolves to no `appId` and is never registered.
- On **Android**, the `clientId` is the kernel-trustworthy calling package
  (`getCallingUid` → package), so a process cannot forge another app's package — the allowlist
  entry is a meaningful identity bind there.
- On **desktop**, this is **CLOSED as of 2026-07-28**
  ([`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) P2/P3). The
  socket `clientId` was previously a hardcoded literal — *every* UDS peer was served as
  `drive-desktop-client`, so every desktop app registered **as Drive** and would have inherited
  Drive's grants. It is now the identifier A14's requirement check attested, and an unattested
  peer is rejected outright (strict) or, under developer mode, admitted under a unique
  `unattested-<uuid>` that is in no allowlist and therefore never registers.
- Registration now carries a **real** `verified` flag, supplied per platform by a
  `PeerAttestationProbe`: desktop binds it to the A14 gate outcome; Android has OS-attested
  identity but has not yet made the claim explicitly, so it still reports `false`.
  ⚠ `TenshuAppRegistry` aggregates rows with `all { it.verified }`, so an app present on both a
  desktop and an Android device stays *unverified* overall until Android makes its claim — a
  deliberate conservatism ("verified" should mean everywhere, not somewhere), not a defect.

Residual risk: **Low on desktop** (self-registration now requires a binary signed by our Apple
team; the pre-P2 hardcode also meant a same-user process inherited Drive's identity, which is
what P2 removed), **Low on Android** (package identity is enforced by the OS, and since 2026-10-29
only a first-party-signed package reaches Control at all, T-C-20).

**T-C-15. Same-user process drives the macOS native bridge (File Provider read/write).**
Actors: AD3 — a co-installed untrusted process running as the user. Assets: A20; by extension the
user's Drive file plaintext (A2-protected at rest, but the bridge hands it over decrypted).
Invariants: I1, I8.
A non-Tenshu process `connect()`s to `bridge.sock` (the App Group container on a signed install,
`~/.tenshu/bridge.sock` in dev) and calls the File Provider bridge methods. (Since 2026-10-04 those
methods address items by a daemon-minted identifier instead of a path; the identifier is not a
capability — `listAll` hands every one of them to any admitted caller — so this changes nothing
about what a connected peer can do.) Because those methods
run real file-service operations behind the daemon, the caller can **enumerate, read the decrypted
bytes of, and create / modify / move / delete** the user's Drive files — exactly the capability the
legitimate `.appex` has. This is the bridge-surface analogue of T-C-11, but **higher-impact**:
T-C-11 yields an unverified-badged app entry with no key material, whereas the bridge directly
yields and accepts **file plaintext**.
Defense (design): the bridge socket must carry the **same §3 handshake** as the binary transport
(peer-PID → resolve binary → verify code-signing identity against a Rei publication). `desktop-ipc.md`
§6.1 already states the group container is reachability-not-boundary and that the §3 handshake remains
the gate; §6.2 notes the daemon "owns … the permission checks". On a signed install the App-Group
entitlement constrains *which signed bundles* may reach the container — a partial bar against a
*sandboxed* attacker, but not against an arbitrary same-user process.
Implementation status: **ENFORCING since 2026-07-28**
([`desktop-ipc-client-identity.md`](../proposals/desktop-ipc-client-identity.md) P3). Both desktop
IPC servers (`NativeBridgeServer` and the binary `UnixSocketServer`) run the same
`PeerCredentialPolicy` chokepoint on every `accept()` (`PeerCredentialGate.kt`, core-common
desktopMain), now configured `strict = true` with a real verifier (`MacPeerAttestation`,
`:macos-helpers`) implementing the A14 binding. A peer whose running code does not satisfy a
Team-ID-pinned requirement is **rejected and its channel closed** before a single frame is served,
so a same-user process can no longer reach the File Provider bridge methods.
Prerequisite that made this possible: every locally installed bundle now signs with a real
identity (`signDesktopApp`); until then a Team-ID requirement would have rejected Control's own
peers.
⚠ Carve-outs remain, all narrow and deliberate: **(a) developer mode (A16)** downgrades a
rejection to admit-with-warning, but is sourced from a build-type flag the packaged app never
passes, so it cannot be enabled in a release build; **(b)** an admitted peer's *identity* still
gates registration through the static allowlist (T-C-11), which is now a second, independent
check rather than the only one; **(c)** *(recorded 2026-08-21)* that identity is **not** consulted
again inside the bridge — `NativeBridgeServer` uses the admit/reject half of the gate and discards
the credential — so every admitted first-party bundle can call the File Provider methods, not only
the tray agent and the `.appex` that need them. (c) does not reopen this threat, whose actor is an
*untrusted* same-user process: it is a least-privilege gap among already-trusted peers, tracked in
`tech-debt.md`'s 2026-08-21 IPC audit.
Residual risk: **Low.** Bounded by the signing-key custody assumption (a peer must be signed by
our Apple team to be admitted at all) and by the A16 dev-mode carve-out on developer machines.
The App-Group entitlement remains a defence-in-depth constraint against sandboxed peers.

**T-C-16. File Provider plaintext materialisation to a temp file.**
Actors: AD3 / AD4 with same-user or offline read access. Assets: user file plaintext. Invariants: I8.
On open, the File Provider extension fetches decrypted bytes (`readContents`) and writes them to a
temp file for Finder / the opening app to read — the `NSFileProviderReplicatedExtension` contract
hands the system a materialised on-disk copy. Plaintext therefore exists on disk transiently, outside
the daemon's address space.
Defense: the materialised copy lives in the **extension's sandbox container** (`FileManager.temporaryDirectory`
resolves inside the sandbox for a signed extension), is the OS-managed File Provider working copy, and
is evicted back to dataless on the system's schedule — so plaintext exists "only while in active use",
consistent with I8. It is no more exposed than any document opened from any file provider (iCloud
Drive, etc.); within the local trust boundary (TA2).
**Amended 2026-10-05 — the bytes now pass through the App Group container on their way.** To
stop holding whole files in memory (and to lift the bridge's frame limit), contents cross between
the daemon and the extension as a **file** in `<group container>/fileprovider-staging/`, with only
its name on the bridge (`desktop-macos-integration.md` §5b). So decrypted bytes exist, for the
duration of a transfer, in a directory that is **not** the extension's own sandbox container: it is
reachable by every Tenshu desktop process (they share the group) and, like the rest of the group
container, by a same-user process to whatever extent macOS's group-container protection allows.
Defense: the directory is `0700`; a staged name is a token checked against a fixed pattern and is
never used as a path, and the entry behind a name is never followed if it is a symlink (uploads
must be regular files opened no-follow; downloads unlink and create exclusively), so a caller
cannot make the daemon read or write outside it by what it names or plants; the directory itself
is re-verified on every operation to be a real directory with the identity it had at start, so
replacing it with a symlink or another directory switches staging off rather than redirecting it; a download is
moved out to the extension's own temporary directory as soon as it is complete; an upload is
deleted by the daemon as soon as it is consumed; a failed or cancelled transfer's file is deleted
at once; and anything a crash leaves behind is swept after 24 hours.
⚠ That last check is check-then-use, not a held handle — the JDK has no directory handles on
macOS — so a same-user process that swaps the directory in the instant between the check and the
open still wins the race. Such a process already holds the wider powers T-C-15 describes; closing
the race itself needs `openat` through a native call and is not built.
Residual risk: **Low**, and slightly wider than before: the window is one transfer, but for a
large file that is seconds to minutes rather than an instant, and a crash can extend it to the
sweep. The obligations stay checklist items: the final materialisation must remain inside the
sandbox container (never a world-readable global temp) and be cleaned on eviction (per-platform I8
client-hardening checklist, §9).

**T-C-17. Desktop at-rest master-key store exposure.**
Actors: AD1 (offline disk theft) and AD3 (same-user malware). Assets: A21 (and the A1/A2/A9
material it wraps). Invariants: I1 (device-local secret confidentiality).
The desktop secret bucket now persists to disk (`secrets.enc`, A21) instead of in-memory-only.
Three sub-threats:
- **Offline disk theft / another OS user (AD1).** *Mitigated.* The blob is `AES-256-GCM` ciphertext
  and the wrapping key lives in the login Keychain, encrypted at rest and bound to the login
  session — not readable from a powered-off disk or by a different OS user. This is strictly better
  than the prior plaintext-in-memory-then-lost posture for the threats it now covers, and matches
  the Android `EncryptedSharedPreferences` floor.
- **Same-user malware while logged in (AD3).** *Exposed at the baseline.* A process running as the
  logged-in user can read the login-Keychain-held wrapping key (the keychain is unlocked while the
  user is logged in) and decrypt the blob — the same residual surface as every password manager's
  "unlocked" state. The Phase-2 Secure-Enclave / `userPresence` path (proposal §4,
  `BIOMETRIC_WRAPPER`) raises this to a per-access Touch-ID/password prompt; it is **not** in this
  phase. A narrower baseline artifact: the *one-time* wrapping-key creation passes the base64 key as
  an argv to `/usr/bin/security add-generic-password`, briefly visible via `ps`; read-back uses
  stdout, not argv, and the Enclave path (which never puts the key on a command line) removes it.
- **Wrapping-key orphaning on signing-identity change.** *Availability, not confidentiality.* A
  keychain item is bound to the signing identity; a signature change can orphan it, so `secrets.enc`
  becomes undecryptable. The store treats an undecryptable blob as an empty bucket → the device
  re-pairs (mirrors Android `KeyInvalidated`). No secret is disclosed.
**Windows (DPAPI baseline, 2026-11-10).** The same three sub-threats, with these differences:
- *Offline disk theft / another OS user (AD1):* mitigated — `wrap-key.dpapi` opens only for the same
  Windows user on the same machine (the DPAPI master key derives from the logon credential). ⚠ A
  domain-joined machine's DPAPI master keys are also recoverable by a domain administrator via the
  domain backup key; that admin is outside this threat model's adversaries, and is recorded here so
  nobody claims otherwise.
- *Same-user malware (AD3):* exposed, as on macOS — any process running as the user can call
  `CryptUnprotectData` on the blob. The fixed entropy (`…master-wrap-key.v1`) is in our binary and does
  not change that; it only stops a blob being opened by code that merely calls DPAPI on files it finds.
  No `argv` exposure: the key never leaves the process (an improvement on the macOS `security` CLI).
  The Windows Hello gate (masterkeystore Phase 3) is not built.
- *Orphaning:* an administrator **password reset** (not a change) discards the user's DPAPI master key,
  and a profile copied to another user or machine cannot open the blob. `DpapiWrappingKeyStore` retries
  briefly, then moves the blob **aside** (`wrap-key.dpapi.unreadable-<time>`, never deleted) and mints a new
  key; `DesktopMasterKeyStore` cannot read `secrets.enc` either and keeps it aside the same way. The outcome
  is one re-pair, as on macOS, with both old files on disk for diagnosis or a manual restore. No secret is
  disclosed. (Amended 2026-11-10: keeping the blob in place and running in memory meant nothing could ever
  be saved again — a silent re-pair on every launch.)
- ⚠ *Roaming profiles:* with a domain roaming profile both `~\.tenshu` and the DPAPI master keys
  (`AppData\Roaming\Microsoft\Protect`) roam, so the blob **does** open on another machine of the same
  user, and two machines would run as one device identity. Not addressed; out of scope until a Windows
  build targets managed environments.

Residual risk: **Low–Medium** at the baseline (same-user malware exposed, by design for the §3
"saved-password" baseline), reduced to **Low** once the Phase-2 gate is active (macOS; no Windows
gate yet). House Rule #1 unaffected:
the relay never sees any of this — it is device-local at rest.

**Phase 2 update (the per-access gate is now built).** The biometric path (proposal §4) is
implemented: a signed Swift `KeychainHelper` holds the unlock wrap-key in a `.userPresence`-gated
**data-protection-keychain** item, so reading it requires a per-access **Touch ID / login-password**
prompt — `userPresence` is precisely what closes the AD3 "unlocked keychain" gap above. Two things
keep this from *fully* discharging the AD3 exposure today: (1) the gate protects the
`BIOMETRIC_WRAPPER` wrap-key, but the Phase-1 baseline at-rest store (A21) and its login-Keychain
wrapping key remain readable by same-user code unless/until that store is *also* moved behind the
gated helper (future work); (2) the gated helper is **dormant until the app is packaged + signed**
(it needs an embedded provisioning profile — free Apple team suffices, but the binary must ship inside
`Control.app`), so unsigned `:control-desktop:run` keeps the baseline posture. The `ps`-visibility of
the Phase-1 `security add-generic-password` argv is unchanged by Phase 2 (it's the *baseline* store's
wrapping key, not the helper's — the helper never puts a key on a command line). Net: when the signed
app ships, the **biometric unlock** surface is per-access-gated; the **baseline at-rest** surface stays
Low–Medium until folded behind the same gate.

**Hub (H1) note — Linux file-backed wrapping key.** The headless `:hub` runtime (hub proposal
§13 H1) reuses the same `DesktopMasterKeyStore` blob, but Linux has no OS credential store, and the
generic desktop fallback (in-memory secrets, re-pair every restart) is unusable on an always-on node
whose point is durable realm membership. The hub therefore wraps the blob under a key held in an
owner-only **file** in its data directory (`FileWrappingKeyStore`, `wrap.key`, 0600). Posture,
stated plainly: with the wrapping key on the same disk as `secrets.enc`, the AD1 offline-theft
mitigation above does **not** hold at the filesystem level — protection is POSIX permissions only,
i.e. equivalent to plaintext-at-rest key custody against disk theft. This is a deliberate,
documented prototype trade (durability over secrecy-from-disk-theft); the disk-level answer is
**LUKS on the NVMe** (hub proposal §12.1, ops-level), which the H3 full-replica slice — the one
that makes the hub's at-rest surface large — records as its prerequisite. AD3 (same-user code) is
unchanged vs. the desktop baseline. House Rule #1 unaffected.

**Hub (H3) sub-amendment — full-replica plaintext content at rest (hub proposal §12).** H3 turns
the paired hub into the realm's **keep-everything replica**: a durable, disk-backed `FileDatabase`
(`JvmFileDatabase`, `<data-dir>/content/`) plus a device-scoped `KEEP_OFFLINE`-at-root sync policy
driven through the existing offline keeper. The *kind* of exposure is not new — every device's
local content cache is decrypted plaintext at rest (retention-model tier 3; Android's
`AndroidFileDatabaseImpl` is the same shape) — but the *quantity* is maximal by design: the hub
holds **every file's plaintext**, durably, on an always-on living-room box, where a phone or
laptop holds a partial, evictable subset behind OS full-disk encryption that ships on by default.
Against AD1 (disk/box theft) the application layer offers **no mitigation on Linux** (the wrapping
key sits beside the data, per the H1 note above); the mandated mitigation is **operational
full-disk encryption — LUKS on the NVMe** (`docs/how-to/hub-pi-deploy.md` §"Full-disk encryption"),
which reduces box theft to the desktop T-C-17 baseline. A product-grade alternative worth one
line, **noted, not built**: a "retain **ciphertext**, decrypt on demand" mode for the hub's copy
(store the AEAD chunks instead of reassembled plaintext), which would shrink the at-rest plaintext
surface to the working set at the cost of read-side latency and a second decrypt path. AD3
(same-user code on the hub) is unchanged; the hub runs no third-party code. House Rule #1
unaffected: the relay's view (ciphertext blocks + opaque content blobs) is untouched — this slice
is entirely device-local storage.

**T-C-18. iOS at-rest master-key store exposure.**
Actors: AD1 (offline device theft) and AD3 (same-user / in-sandbox malware). Assets: A32 (and the
A1/A2/A9 material it wraps). Invariants: I1 (device-local secret confidentiality).
Control-on-iOS persists its realm-device secrets to the Keychain (`IosSecureStorageDelegate`, A32)
instead of in-memory-only. Three sub-threats, mirroring the desktop T-C-17 shape:
- **Powered-off device theft / another app (AD1).** *Mitigated.* The secrets are AEAD ciphertext and
  the wrap key is a `…ThisDeviceOnly` Keychain item — Data-Protection-encrypted at rest, locked
  before first unlock, and namespaced to this app's Keychain access group. Not readable from a
  powered-off device and not reachable from another app's sandbox. Strictly better than the prior
  re-pair-on-each-launch posture, and at parity with the Android `EncryptedSharedPreferences` floor.
- **In-process / same-app malware after unlock (AD3).** *Baseline: exposed; gated: per-access prompt.*
  With the **baseline** wrap-key protection, any code running inside the unlocked app can read the
  wrap key and decrypt the bucket (the keychain item is available after first unlock — the same
  "unlocked vault" residual as A21's login-Keychain baseline). The **Secure-Enclave / `userPresence`
  gate** raises this to a Face ID / Touch ID / passcode prompt on the wrap key's first read per
  process, after which the unwrapped key is cached in memory for that process — so the gate bounds
  *silent* extraction, not a single post-prompt compromise. C1 builds the gate; C2 decides when it
  is on. (The desktop `ps`/argv-visibility sub-threat has **no iOS analog** — the wrap key is never
  passed on a command line; it is generated in-process via `SecRandomCopyBytes` and stored through
  the Keychain API.)
- **Wrap-key / item orphaning.** *Availability, not confidentiality.* Removing the device passcode
  destroys a `…WhenPasscodeSetThisDeviceOnly`-gated item, and a decrypt that fails for any reason
  (missing wrap key, tag mismatch, foreign restore) is treated as an **empty bucket** → the device
  re-onboards / recovers (mirrors Android `KeyInvalidated` and the desktop undecryptable-blob path).
  No secret is disclosed; `getSecret` returns `null` rather than throwing.
Residual risk: **Low–Medium** at the baseline (in-app malware after unlock, by design for the
at-rest baseline), reduced to **Low** once the C2 gate is active. House Rule #1 unaffected: the relay
never sees any of this — it is device-local at rest.

**T-C-19. A co-installed Android app calls a Tenshu app's callback service.**
Actors: AD3 (a malicious app on the same device). Assets: the realm data an app displays (by
extension A4's per-app scoping), app availability. Invariants: none of I1–I9 applies (I2/I3
concern relay blocks); the property is the **integrity of what the app displays and of its launch
tickets**, plus its availability.
Every first-party Android app exports `ClientCallbackService` with **no permission** — Control
binds it to push stream elements (`IClientInterface.callback`), byte payloads (`receiveFile`,
`receiveSharedMemory`), launch tickets and, since R9 (`core-client-resubscription.md` §4.8),
whole messages in a `SharedMemory` region (`callbackShared`). Any installed app can bind the same
service. Two sub-threats:
- **Forged deliveries.** A stranger feeds an app fabricated stream elements or descriptors, which
  it would render as the user's realm data, or a launch ticket. *Mitigated (2026-10-23):* every
  delivering method admits only **Control's UID**: the kernel-supplied `Binder.getCallingUid()` must
  belong to `app.tenshu.control.android` (`getPackagesForUid`, as Control checks its own callers;
  `ClientCallbackGuard`). Asked about the caller, not resolved from Control up front, so a Control
  reinstalled under a new UID is admitted on its first call. A positive answer is cached for at
  most 60 s and dropped at once when the app's binding to Control dies or disconnects, because
  below API 33 an uninstalled Control's UID can be reused by another app. A UID found not to be
  Control is refused without asking again for 10 s — **per UID**, so several UIDs (isolated
  processes) each get their own lookup, and the 64-entry table is reset when it fills — so a
  stranger's calls are not each a package-manager lookup. Anything refused gets a
  `SecurityException`, with any descriptor or region it sent closed first. A
  package name cannot be held by a second app while Control is installed. ⚠ **Amended 2026-10-29
  (T-C-20):** while Control is *not* installed any sideloaded app can take its name, so the UID's
  package must also be signed like the real Control (`FirstPartySigning`). This entry used to say
  no signature check was needed because "an app installed *as* Control is the Control the app
  binds to anyway"; that was the hole, since the app also binds it and sends it everything. (Before
  this the surface was open; it predates R9.)
- **Caller-sized allocation.** `callbackShared` allocates the region's own size to copy it out, so
  an unchecked caller could OOM-crash the app with a huge region. *Mitigated:* beyond the UID check,
  a region outside 1..64 MiB (`BinderIpc.MAX_SHARED_MESSAGE_BYTES`) is refused before anything is
  mapped or allocated, and Control never sends one that large (the stream ends with a typed error).
The reverse direction, an app calling Control's `IProxyInterface`, is T-C-20. Residual risk: **Low**. A
stranger can no longer forge what the app shows, deliver a launch ticket, or force a large
allocation. It can still make the app do some work: each rejected call spends a binder thread and
briefly a descriptor (closed on refusal), a package-manager call per UID at most every 10 s, and a bind
with `BIND_AUTO_CREATE` keeps the app's process alive. That is a nuisance (battery and memory), not
a breach. House Rule #1 unaffected: device-local IPC, nothing reaches the relay.

**T-C-20. A co-installed Android app drives Control, or poses as Control.**
Actors: AD3 (a malicious app on the same device, with no permissions). Assets: A5 user data and A6
metadata, i.e. the whole realm as Control's proxy serves it to its apps; A18 (by extension, through
registration); what the user's apps send Control (uploads, location fixes, health data).
Invariants: I1 (the relay still sees only ciphertext; this is about who reaches the plaintext side).
The Android twin of T-C-11. Two directions:
- **A stranger calls Control.** `ProxyService` is exported with an intent filter and no
  `android:permission`, because ten apps with ten Play signing keys must bind it
  (`docs/how-to/android-release.md` "Which key signs what"). A `signature` permission cannot span
  ten keys; `signature|knownSigner` needs API 31, and with minSdk 24 it falls back to plain
  `signature` below that and would lock every app out. Before 2026-10-29 the caller's uid was turned
  into a package name and nothing checked it, so any app could bind, announce, and run every
  operation: read and write the realm. *Mitigated:* every `IProxyInterface` method takes its client
  id from `ProxyService.admittedCallerPackage`, which refuses with a `SecurityException` unless the
  kernel-supplied `Binder.getCallingUid()` holds one of the ten first-party package names **and**
  that package is signed by its pinned Play app-signing certificate, by the pinned side-channel
  certificate, or by the same certificate as Control (`BinderCallerAdmission`, `FirstPartySigning`).
  The side-channel pin is what lets a Play-installed app and a downloaded one admit each other; it is
  empty until the key exists (as of 2026-10-31), and while empty admits nothing extra. The same-certificate rule
  covers debug builds and two downloaded copies; in a Play build no other app holds Control's key.
  ⚠ A leaked side-channel key lets an attacker sign an app under a first-party name that Control
  admits on any device where that name is not yet installed — the same exposure a leaked Play key
  has, and the reason that key is held offline (`docs/how-to/android-release.md` "The side-channel
  key").
  A refused caller's descriptor (`sendFile`) is closed before the refusal. The check is `hasSigningCertificate`, which
  also matches past signers. ⚠ **That is load-bearing on Android 17:** a Play-delivered install
  there reports Play's ML-DSA certificate as the current signer, with the pinned `deployment_cert`
  as a past signer in its rotation lineage (measured 2026-10-29 on an API 37 emulator, Control
  versionCode 5). A comparison of current signature bytes would refuse every Play-installed app.
- **A stranger poses as Control.** Play keeps package names unique, but while Control is not
  installed a sideloaded app can take the name `app.tenshu.control.android`. Every app would bind it
  and send it everything, and it could push forged stream elements back (T-C-19). *Mitigated:* an
  app binds Control only if the installed `app.tenshu.control.android` passes the same signer rule
  (`BinderTaskTransport.bind`), and `ClientCallbackGuard` admits a callback only from such a
  Control.
Answers are cached per uid: an admission for at most 60 s and until the last binding goes, because
below API 33 an uninstalled app's uid can be reused; a refusal for 10 s, so a stranger's calls are not
each a package-manager lookup. An app Control refuses drops its binding and holds off for 60 s, so
it behaves as if Control were absent rather than retrying into the refusal.
Residual risk: **Low.** Remaining: (a) a stranger can still bind (`BIND_AUTO_CREATE` keeps Control's
process alive) and spend a binder thread per refused call, a nuisance rather than a breach; (b) an
**admitted** first-party app reaches every operation, because per-op `AppGrant` enforcement is
still unbuilt (`docs/tech-debt.md` same entry, #5), which matters once apps hold different grants;
(c) a wrong pin fails closed: a Play key upgrade or a new app missing from the table locks that app
out of Control until a release fixes it. Control logs each refusal with the caller's signer digests,
and `FirstPartyCallerCoverageTest` / `FirstPartySigningTest` keep the table in step with the app
catalogue and the release doc. Builds installed through Play's *internal app sharing* are re-signed
with another key and refused. **Third-party apps (Rei) are refused** until they get their own path:
a package the user granted in Control whose signer matches the digest in its signed Rei publication
(`tenshu-rei.md` §3.2), never an entry in the first-party table.

### 8.5 Key management and revocation

**T-K-01. Revoked device keeps publishing blocks.**
Actors: a compromised former member. Invariants: I4.
Defense: On revoke, the realm advances an epoch. A signed `DeleteBlock` targeting the
revoked device's `Device` entity is propagated to every other realm device (per
[`sync/design.md`](design.md) §2.6 "Revocation is a tombstone"); they refuse to accept
blocks signed by the revoked device with a post-revocation HLC. **Relay-side push
rejection landed 2026-05-05** ([ADR 0006](../adr/0006-sync-tombstones.md)): the tombstone carries
`revokedDeviceId` so the relay extracts it and revokes the device's
`KnownDevicesStore` entry; subsequent `/sync/push` from the revoked device's JWT
returns 401. Without this, a revoked device could keep spamming the relay's
mailbox until tombstones propagated to every other realm device — wasted relay
storage + a real attack window. The revoked device's own `DeleteBlock`s are also
rejected post-epoch-rotation (revocation cannot be used as a delete-bomb). The revoked device can
still decrypt what it already has.

⚠⚠ **CORRECTED 2026-08-21 — the personal realm does NOT rotate volume keys.** This entry previously
read *"Volume keys are rotated under the new epoch and re-wrapped for the remaining realm devices …
it cannot read anything encrypted under the new epoch."* The first clause is unbuilt and the second
is therefore false. Measured against the code:

* `RevokeDeviceImplementation` rotates nothing — it removes the row, emits the tombstone, and posts
  admission revocations.
* The only rotation in the tree is `SharedRealmGrantManager.rotateAndRegrant` — **GR-7, shared
  realms only.**
* ⚠ It is not merely unimplemented, it is **not expressible as designed**: `deriveKeys(phrase)` is
  `Argon2id(entropy) → HKDF → sharedIdentityKey + sharedVolumeKey`, so the volume key is a
  *deterministic function of the phrase*. There is no wrapped-key indirection to re-wrap, and A2/A3
  (per-volume, per-epoch keys) have no implementation — the only `epochKey` in the tree is
  `RealmSchemaEpochWriter`, an unrelated schema namespace. `sync/design.md` §"Actions" and its §525
  test plan carry the same unbuilt promise.

**What actually contains a revoked device: relay-side access revocation, and nothing else.** The
JWT/`KnownDevicesStore` cut is the whole control. ⭐ That is also what Signal relies on, and Matrix's
extra layer (rotating the group session so new content is not encrypted to the removed device) is
the thing Tenshu lacks — a real gap, but a narrower one than the old text implied, since it only
covers new data reaching a device that has *already* lost relay access.

⚠⚠ **And for an owner device it would buy nothing even if built** (§5b.9 / §5b.11 of
[`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md)):
an A1 holder re-derives the volume key from the phrase and re-registers itself with
`reregisterThisDevice`, so no rotation can exclude it. Matrix carries the same residual for a holder
of its Secure Storage recovery key. Any "one secret regenerates everything" recovery design accepts
this trade deliberately.

Residual risk: **Low for the modelled case** (a revoked non-owner device, cut off at the relay);
⚠ **unchanged-by-revocation for an owner device**, which §5b.9 records as the owner concept's real
cost. Plaintext already exfiltrated is out; the invariant bounds are "no *new* disclosure," which the
relay cut meets for a device that cannot re-authenticate.
⭐⭐ **DECLINED 2026-08-21 — A2/A3 epoch rotation will not be built.** Recorded so it is not
re-litigated. The reasoning, in the order that decided it:

1. **The cost is not where it looks.** *Rotation* is cheap — mint a fresh volume key per epoch, wrap
   it per remaining device, encrypt new writes under it (the Matrix model). **Re-encryption** is what
   costs terabytes, and `sync/design.md` §6 (the retired implementation plan) asked for exactly that
   (*"starts re-encryption"*, the `ReKeyingWorker` item).
   ⭐ Re-encryption also buys almost nothing: the removed device **already holds** the old data. It
   defends against a threat that has already succeeded. Neither Matrix nor Signal re-encrypts history.
2. ⚠⚠ **The prerequisite migration is the real bill, and it lands before any benefit.**
   `sharedVolumeKey` is `HKDF(Argon2id(phrase))` — a pure function of the phrase — so rotation is not
   expressible until content moves to a random per-epoch key wrapped per device. That is a key-material
   migration over every existing blob.
3. **The property bought is narrow**: new data reaching a device that has *already* lost relay access.
   The relay cut (T-K-01) is the containment, and it is what Signal relies on.
4. ⚠⚠ **It closes nothing against the case that actually motivated the question** — a stolen *owner*
   device. An A1 holder re-derives the volume key from the phrase and re-registers via
   `recoverRegistration` (`masterIdentity() ?: return` — the gate is *holding A1*, so a non-owner
   device cannot do this). No rotation can exclude the holder of the seed the keys come from.

⭐ **What is being built instead: the owner-device concept** (§5b of
[`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md)).
Since relay revocation already contains every device *except* owners, the residual is owner-shaped —
and the mitigation for it is a human knowing which devices hold A1 before something goes wrong. That
is a badge, honest copy and the RG-2 phrase reveal, not cryptography.

⚠ Revisit only if the threat model gains a case this actually addresses — e.g. a shared realm where a
removed *member* must be excluded from future content. That case already has its own mechanism
(GR-7's `rotateAndRegrant`), which is why it was built there and not here.

**T-K-08. A removed device rejoins under the same identity.** *(F2′, 2026-08-21)*
Actors: a former member holding a device the owner removed.
Under option F a removed identity never returns — but the relay had no way to enforce it:
`SqliteDeviceRegistry.revoke` is a hard `DELETE FROM device`, so after a removal **no record that
the identity existed remained**, and the admit path could not answer *"has this deviceId been
removed before?"*.
Defense: a durable **removal ledger** (`removed_device`), written from `revokeIfNotStale` — i.e.
from an applied `Device` tombstone, the one signal meaning *the owner removed this device*. Both
admit paths (join flow and offer flow) refuse a `deviceId` present in it with `403`.
⚠⚠ **Written from `revokeIfNotStale` only, never from `revoke`.** `MembershipGrantRouting` calls
`revoke` for shared-realm **member** removal, a different fact; recording both would put two
populations needing opposite treatment in one ledger — the conflation that made the earlier F2
design wrong (`admission-revocation-names-the-capability.md` §7c.1).
⚠ **The ordering guard becomes more load-bearing, not less** (§7c.4): a stale retirement that
applied would write a permanent bar on a device legitimately re-admitted in the meantime, and under
F that bar never lifts. A refused tombstone therefore records nothing.
⚠ **The ledger is never garbage-collected** — deleting a row re-admits that identity. One row per
removal: unbounded by construction, trivially small in practice.
⚠ **iOS depends on RG-1 for this to be humane**: the Keychain outlives the app, so a reinstalled
device returns with the *same* `deviceId` and hits this refusal. "Start fresh" is the only supported
way to obtain a new identity, which is why it shipped first.
House Rule #1 unaffected: the rows are an owner id and a device id, both already in `device`.
Residual risk: **Low.** Bounded by the same custody as the registry it replaces reading.

**T-K-02. Race between pairing and revocation.**
Actors: AD5.
*⚠⚠ Selected successor — NOT yet an amendment (2026-09-17).* `proposals/sync-entity-identity.md`
§5b selects a device-revocation design that changes this entry's surfaces, recorded here so the
contract does not read as current while the successor is chosen and unbuilt. Its new surfaces: an
**A1-signed revocation roster** in its own relay table (encrypted payload; version, predecessor
digest and an **outer** A1 signature in the clear so the relay can authorise a compare-and-swap it
cannot decrypt), and an append-only **A4-signed self-removal table** whose entries carry the
A4→device delegation. Its authorization changes: peers act on a revocation only against an **A1**
signature (membership is not permission — an A33 admitter queues a removal an owner signs), and a
self-removal is evidence until an owner absorbs it. Its accepted residuals — **two**, not one.
(1) A device recovered from the phrase alone has **no continuity anchor**, so a hostile relay can
serve it an older correctly-signed roster — it may keep devices in the roster on that basis but never
shrink it, and the first sync with a live peer anchors it. (2) ⚠⚠ **An explicit exception to I6's
"within bounded time"**: two owner devices can sign different successors to one roster version, and a
relay that equivocates on **both** planes can serve one fork to one population while withholding the
other indefinitely — so a revocation can be withheld from part of the realm with no bound. Fork
*detection* is by client gossip of the adopted roster head, which raises the cost of equivocation
without removing it; closing it needs an anchor outside the relay that the design does not have.
⚠ Related: a realm whose last A1 holder is gone cannot
change its roster at all; access is still cut, peers keep waiting, reclamation stalls. ⚠ The binding
amendment co-lands with the implementation, per House Rule #2.
Relay delays a revocation record so that a newly-paired device on a different branch is admitted
under the revoked device's admission signature.
Defense: `Device` entity blocks carry the realm epoch in `admittedAtEpoch`. A device
attempting to admit another at an epoch that has since been rotated is rejected on
merge. **Client-side epoch tracking landed 2026-05-05** (
`SyncManager.currentRealmEpoch` derived from
`TombstoneRegistry.tombstoneCountForType(Device.ID)` per
[ADR 0007](../adr/0007-sync-device-management.md) anchor decision 3 — derived
realm-state cache); pairing-host (`ApprovePairingRequest`) stamps the
authoritative epoch into both the master-delegation message and the flipped Device
entity. **Merge-time enforcement landed 2026-05-08** —
`SyncProcessor.resolveConflictAndSave` now drops any `Device` entity block whose
`admittedAtEpoch < currentRealmEpoch` (logged at WARN with blockId/deviceId/claimed
epoch/current epoch) before it lands in the local DB. **Recovery-race OOB epoch
fetch landed 2026-05-08** —
[`sync-relay-api.md`](../proposals/sync-relay-api.md) §3.6 specs `GET
/sync/realm-epoch` (a count of Device-targeted tombstones the relay surfaces from
its existing `revokedDeviceId` index — no new metadata leak); `RecoveryClaim
Implementation` and `GetPairingStatusImplementation` call it before stamping
their self-pushed Device entity, and seed `SyncManager.currentRealmEpoch` from
the result via `seedCurrentRealmEpoch(epoch)` so the merge-time check on
*incoming* blocks is meaningful immediately rather than waiting for tombstones
to drain. Both halves of the carve-out (the recovering device's own block AND
its acceptance of stale-epoch peer blocks) close in lockstep.

The fix introduces a bounded relay-trust residual: the relay is no longer fully
zero-trust on the realm-epoch metadata it serves. Concretely the relay can
*overstate* the count (forcing the recovering device to claim a higher
`admittedAtEpoch` than necessary — still accepted by all peers because T-K-02
enforcement is `<` only, future epochs pass) or *understate* it (the recovering
device claims a lower epoch and gets T-K-02-rejected by peers until tombstones
drain — degrades to today's known behaviour). Neither lets the relay forge an
admission, weaken existing peer-signature checks, or read user data. A signature-
attestation by an existing realm device would fully close this residual; sized
as a future proposal (would require a "realm-state attestation" block kind on
the sync wire, paid for only on recovery).
Residual risk: **Low** post-OOB-fetch (steady-state and recovery both have a
meaningful epoch view; only forged Device-entity admissions can land, and those
require a master-key compromise covered by T-K-01 / T-RC-01).

*Amended 2026-10-28 (F8) — `GET /sync/realm-epoch` is device-JWT authenticated.* It used to answer
`?ownerId=` to anyone, reasoning that the recovery call site had no JWT yet; in fact both callers
(`RecoveryClaimImplementation`, `PairingKeyInstaller`) fetch the epoch only after the relay has
registered their device key, so they always hold one. The open route was an oracle for realm
existence and the device-removal count to anyone with an `ownerId`, which is public (T-RC-08). The
relay now reads the realm from the JWT; the trust residual above is unchanged.

*Amended 2026-08-18 — the relay's own registry now respects the same ordering.* T-K-02's
enforcement above is **merge-time and client-side**: peers drop a `Device` block whose admission
the realm has rotated past, and `SyncProcessor.resolveConflictAndSave` treats a write newer than a
tombstone as a recreate. The **relay's device registry did not participate**: `POST
/sync/push-tombstone` applied `revokedDeviceId` the moment it arrived, comparing nothing, so it was
the one replica of *"is this device a member"* that ignored the ordering every peer respects.
⚠⚠ **Delivery order is not authorship order**, and the gap is unbounded in time: a retirement that
cannot be pushed (a `401` against a wiped relay, a `402` without an entitlement) waits in the
client's queue and executes whenever it finally can. Observed on real devices 2026-08-17 — a
morning retirement revoked a device that evening, minutes after it had been legitimately
re-admitted, leaving it `401`ing with no way back.

**Fix**: each admission now records the HLC the admitting client authored it at
(`device.admitted_hlc_*`, relay migration 13, carried as `admittedAtHlc` on the four admission
routes), and a `Device` tombstone revokes only when it is **not older** than that stamp —
`HlcStamp` ordering, shared with `HybridLogicalClock.compare` so the relay and its peers cannot
break ties differently. **The tombstone is still stored and served either way**; only the registry
revocation is skipped, so peers converge exactly as before.

Security posture, stated deliberately:
- ⚠ **This weakens no revocation that could previously succeed.** An absent stamp on either side
  means "unknown" ⇒ revoke, i.e. the prior behaviour, and a retirement authored *after* the
  admission it supersedes still applies. What it removes is the ability of a *stale* instruction to
  undo a *later* authorised admission — a lost-update bug, not a permission.
- ⚠ **The stamp is client-asserted**, like the `epoch` beside it, and is an ordering hint rather
  than an authentication input: a malformed value parses to `null` and degrades to "unknown". A
  device could understate its own admission stamp and thereby make itself easier to revoke; it
  cannot make itself *harder* to revoke than the tombstone's own clock allows, because the
  comparison is against the tombstone the realm authored.
- ⚠ **Self-re-registration is deliberately not special-cased.** `RealmRegistrar.reregisterThisDevice`
  re-admits *this* device and so refreshes the stamp — but producing that admission requires the A1
  master identity, which per **T-K-04** already makes per-device revocation meaningless for its
  holder. A pairing joiner holds no A1 and therefore cannot refresh its own stamp: the capability
  boundary enforces "a revoked joiner cannot launder away its revocation" without a check.
- **Relay data shape** (House Rule #2): three nullable columns holding a logical clock — physical
  millis, a counter, and the authoring device id, which the relay already sees on every block's
  HLC. No plaintext, no new identifier class, House Rule #1 intact.
- ⚠ **Not yet stamped**: `recovery-claim` and the shared-realm `membership-grant` register without
  one, so they retain today's unconditional-revocation behaviour rather than a half-guard. Tracked
  in `docs/tech-debt.md`.
Residual risk: **Low** — unchanged for forged admissions (still T-K-01 / T-RC-01), and the
stale-revocation lost update is closed for the four routes that carry the stamp.

**T-D-01. Relay tampers with a Device entity block.**
Actors: AD5. Invariants: I1, I4. Assets: A4 (by extension), A6.
A hostile relay rewrites a `Device { isAuthorized = true, deviceId = X, ... }` block
to flip a flag, change a public key, or admit a device the realm never approved.
Defense: every `Device` entity block is a `SyncBlock` signed by an admitted A4 key
through the master-delegation chain (per [`design.md`](design.md) §2.2 "Authority"
+ [ADR 0007](../adr/0007-sync-device-management.md)); merge-time signature
verification rejects tampered blocks. Same family as
T-T-04 (relay tampering with forwarded data is detectable via signatures or HLC
divergence).
Residual risk: **Low.**

**T-K-05. Forged tombstone by revoked device.**
Actors: AD2 with stolen device key. Assets: user data integrity. Invariants: I4.
A revoked device emits a `DeleteBlock` for entities the legitimate user wants to
keep.
Defense: Tombstones are signed; the master-delegation chain is checked at merge
time. Once revocation has propagated and the realm has rotated the epoch, blocks
(including tombstones) signed by the revoked device are rejected. During the
revocation window, T-K-01's data-exfiltration consideration also applies; the
delete-bomb risk is bounded by the same window.
Residual risk: **Low** post-revocation propagation. **Medium** during the window
(coupled to T-K-01).

*Amendment (Fx, 2026-10-26: corrects the Defense above to match the code).* ⚠⚠ **Tombstones are
not signed**, and no delegation chain is checked at merge. `DeleteBlock` has no signature, MAC or
ciphertext field (`sync-service/.../DeleteBlock.kt`), and `processDeleteBlock` applies one after
HLC checks alone. So a tombstone is not rejected for coming from a revoked device; the receiver
cannot tell who authored it at all. What bounds this threat in practice is the **relay**: after
revocation it refuses the revoked device's JWT (T-K-02), so the device can no longer *push*. That
holds only while the relay is honest. An AD5 relay can forge tombstones directly, without any
device key (T-T-02 amendment), which makes the stolen key unnecessary.
Residual risk: **Low** post-revocation against an honest relay (JWT refusal, not a signature).
**Medium** during the revocation window. **High** against AD5 (T-T-02). Fix: finding Fx /
D4 in [`relay-metadata-minimisation.md`](../proposals/relay-metadata-minimisation.md).

*Amendment (envelope authentication, 2026-10-27).* The AD5 path is closed: the relay holds no realm
key, so it can no longer forge a tombstone (T-T-02 amendment). ⚠⚠ The revoked-device path is
**not** closed by it, and the Defense above still overstates what holds: the personal realm key never
rotates, so a revoked device still holds the key the tag is computed under and can mint a tombstone
that verifies. What stops it is still only the relay refusing its JWT (T-K-02). For a **shared**
realm, rotation retires the removed member's key and ingest accepts a retired-key block only if its
HLC predates the retirement (+5 min skew, `sync-envelope-authentication.md` §3.5) — but shared-realm
tombstones do not travel on the shared stream at all yet, so that rule covers blocks only.
Residual risk: **Low** post-revocation against an honest relay. **Medium** during the revocation
window. **Medium** against a revoked device colluding with an AD5 relay (needs both; was High against
AD5 alone).

**T-K-03. Device loses a volume key.**
Actors: user error, device fault.
Defense: Volume keys are present on at least one other device at all times (the realm must have
≥2 devices for this invariant; for single-device realms, the recovery code is the only backup).
For realms with ≥2 devices, any surviving device can re-share the key to a replacement device via
pairing.
Residual risk: **Low** for multi-device realms. For single-device realms, **the recovery code is
load-bearing**; if the user has lost both the device and the recovery code, data is gone (I7, by
design).

**T-K-04. Master identity key compromise.**
Actors: AD5, AD3 via client compromise.
Defense: The master identity key is derived deterministically from the recovery code mnemonic. It is
not written to disk in plaintext; it is reconstructed on demand by devices that have been
bootstrapped with it, and kept only in memory. If it leaks, the user must initiate an identity
rotation — generate a new master identity, re-pair all devices under it, revoke the old one, and
re-encrypt data they wish to carry forward.
Residual risk: **High** if it leaks; catastrophic. Surface area for leakage is small (in-memory
only, derived from recovery code), so likelihood is correspondingly low. Document the rotation
procedure explicitly.

**T-K-06. A1 recovery from a headless-autoload device.**
Actors: AD1 (lost/stolen device already past first unlock), AD3 (on-device malware).
Assets: A1 (and the seed it derives from). Invariants: I1.
On the **default headless posture** (`control-keep-device-unlocked.md`, no expert lock set) the
recovery phrase is sealed under a hardware-backed keystore key the OS releases without user
interaction after the first device unlock post-boot. An attacker who holds the device **already
unlocked**, or who can defeat the hardware keystore, can recover A1 without any Tenshu-level
passphrase/biometric — the app-level unlock that the old forced floor provided is gone by default.
Defense: hardware-backed + non-exportable key material, **StrongBox where available**; FBE +
after-first-unlock accessibility protects a fully-powered-off / pre-first-unlock device (the
lost-powered-off-phone case); the recovery code is never at rest on the device by default; and
**device-revocation still applies** — a lost device is removed via the existing revocation + GR-7
`Kr` rotation (T-K-01), cutting it off regardless of local A1 exposure. Users who want the stronger
on-device boundary enable **"Encrypt on unlock only"** (the expert hard mode), which restores the
`setUpUnlock` passphrase/biometric wrapper and the sealed-until-unlock lifecycle covered by the A1
note. The Ph1 **"Require unlock" soft app-lock is not a mitigation here** — it gates UI viewing only,
not key custody.
Residual risk: **Medium** for a device captured already-unlocked on the default posture (bounded by
revocation once the loss is noticed); **Low** otherwise. This is the deliberate
competitor-standard tradeoff — the same posture as iOS `AfterFirstUnlock` / Android non-auth
Keystore and Tenshu web (T-RC-02 / the A1 note) — chosen so background sync works without an
app-level unlock; the expert hard mode is the opt-out.

**T-K-07. An abandoned realm identity resident on a transferred device.** *(RG-1, 2026-08-19)*
Actors: whoever holds the hardware next — a buyer, a recipient, a repair technician.
On iOS the Keychain **outlives app deletion**, so uninstalling Control does not remove the realm
identity (A4 device signing key, unlock/biometric wrappers, `OWNER_ID`, relay latches). A user who
deletes the app to "remove Tenshu" before parting with the phone leaves a usable identity resident
indefinitely, and a later reinstall silently resumes it. ⚠ Not iOS-exclusive in principle, but iOS is
the live case: Android and desktop keep identity and store in one app-data directory, so clearing
app data takes both.
Defense: the reinstall gate (`ios-identity-reinstall-gate.md` RG-1) makes the resumption **visible**
at the one moment the user is present and can act on it, and offers a discard that purges the
custody bucket. ⚠ It mitigates rather than closes: the exposure window is between the app delete and
any later reinstall, and nobody is present during it. A user who deletes and never reinstalls is
still carrying the identity. The full close is an OS-level "delete app data on uninstall" the
platform does not offer, so the honest remedy remains "Forget this device" **before** deleting the
app, which the gate cannot reach retroactively.
⚠⚠ Discovering this as a *pre-existing* weakness is the point: the gate did not create it, and
before RG-1 nothing surfaced it at all.
Residual risk: **Medium** — unchanged in the delete-and-never-reinstall case, reduced to Low where a
reinstall happens. Mitigation is UX-shaped because the mechanism is the platform's, not ours.

### 8.6 Recovery

**T-RC-01. Attacker obtains the recovery code.**
Actors: AD1–AD4.
If an attacker has the 24-word mnemonic (§11.2), they can derive the master identity and decrypt
the server-side backup blob. They effectively become the user.
Defense: **Prevention only.** UI design treats the recovery code as extremely sensitive: displayed
once, displayed on a secure screen (T-C-07), never written to app storage, never shown in
notifications. The user is instructed to store it offline (paper, hardware wallet, password manager
with strong master password).
Residual risk: **High** if the user handles the code carelessly. The system has no way to detect use
of a valid recovery code by the wrong person; that's the nature of a recovery mechanism.

**T-RC-02. Offline brute-force of the encrypted backup.**
Actors: AD4, AD5 with bulk storage.
Defense: KDF tuned for expensive guessing (Argon2id with parameters sized to ~1s/guess on current
high-end GPUs, periodically revisited). 256-bit mnemonic entropy (§11.2) puts brute force outside
practical reach at 2026 compute and remains so under any reasonable projection of adversarial
compute growth; KDF parameters are the only variable worth revisiting.
Implementation (Phase 4 slice C, 2026-05-08): `KdfBackend`
(`:core-common/security/KdfBackend.kt`) abstracts Argon2id v0x13 across platforms — BouncyCastle's
`Argon2BytesGenerator` on JVM, `@noble/hashes`'s sync `argon2id` on JS.
`DefaultSyncCryptoManager.deriveKeys` runs Argon2id over the decoded BIP-39 entropy with
production parameters m=64 MiB / t=3 / p=1 / dkLen=32, then HKDF-domain-separates the output
into the shared-identity and shared-volume halves. Parameter constants live as
`KdfBackend.RECOVERY_KDF_*`; bumping them is a protocol-version change (changes all derived
shared keys), so it has to coordinate with the recovery-blob format from slice D.
Residual risk: **Low** at 256-bit entropy. Re-evaluate KDF parameters annually per §12.

**T-RC-03. Relay denies the user their own backup.**
Actors: AD5. Invariants: I7 for one user.
Defense: The user's last-known backup can be cached on any realm device and re-uploaded to an
alternative relay. Eventually: the backup blob is small enough to fit in a printed QR alongside
the recovery code, giving the user an offline copy. Document both options.
Residual risk: **Low** for users who still have any realm device. **Medium** for users who have
lost all devices and only have the recovery code (they rely on the relay to serve the blob).

**T-RC-04. Recovery code exposed in a data breach of a password manager.**
Actors: AD3 via compromise of the user's password manager.
Defense: Out of protocol scope. Treat as equivalent to T-RC-01.
Residual risk: **Accepted, documented.**

**T-RC-05. Relay retains superseded backup versions after a manifest downgrade.**
Actors: AD5. Assets: A9, A10. Invariants: I7 — *ceiling* on what's recoverable, not floor.
The user removes a volume from their recovery manifest. The client re-encrypts and uploads the
reduced blob. A malicious relay keeps the previous, larger blob and ignores the overwrite.
Defense: Partial. At the protocol level, the client POSTs the new blob with a monotonically
increasing manifest version, and signs it under the master identity; any blob the user signs is
authoritative and supersedes earlier ones. At the relay level, the client has no way to prove the
previous blob is actually deleted — a hostile relay could retain it indefinitely.
Residual risk: **Medium, accepted with caveats.** The user should treat any volume that was *ever*
on the manifest as potentially recoverable by an attacker who obtains the recovery code, until
the recovery code itself is rotated (§10). The control app surfaces this caveat when a user
downgrades the manifest: "Removing 'Photos' from recovery will stop new backups, but we cannot
guarantee the server deletes the previous backup. If you want certainty, rotate your recovery
code."

**T-RC-06. Recovery manifest metadata leakage.**
Actors: AD5. Assets: A6 (by extension).
Manifest upload frequency, blob size changes, and upload timing reveal when the user changes their
recovery scope.
Defense: None. The manifest is edited rarely; the signal is low-bandwidth. Pad blob sizes to fixed
buckets (same policy as block-size padding in T-T-07).
Residual risk: **Accepted.** Consistent with the v1 metadata-exposure stance.

**T-RC-07. Phrase capture from an unattended unlocked tab.**
Actors: AD2 (proximate adversary) with physical access to the user's already-unlocked browser.
Assets: A4 (recovery code).
The web Settings → Recovery section exposes a `[Re-display recovery phrase]` action so users who
need to re-record their phrase don't have to wipe and recover. An attacker who finds the user's
browser unlocked and unattended could navigate to Settings → Recovery, click reveal, and read the
phrase off the screen. Equivalent vector exists on native (biometric-prompt re-display); this
entry covers the web shape because the unattended-tab idiom doesn't map cleanly onto the existing
client-device entries (T-C-*).
Defense: **Dedicated WebAuthn re-challenge** at the moment of click — fresh ceremony with its own
challenge context (random + action-bound domain separator), `userVerification: 'required'`, and a
~60s freshness check at the call site so an assertion can't be stockpiled. The user is prompted
for Touch ID / passkey / hardware-key approval *even when their session is already unlocked*; an
unattended tab does not satisfy this gate. Implementation: `getDedicatedAssertion(action)` in
`web/apex/src/lib/webauthn.ts` (separate from `getPasskeyAssertion()` which mints a session
token); consumed by the `/control/settings/recovery` reveal flow.
v1 status: the auth gate is wired and tested. The phrase-reveal panel itself is staged behind a
"Coming soon" placeholder pending a Track A SDK extension that adds BIP-39 entropy alongside the
master keypair in the `tenshu-sdk-master-keys` IDB blob (Argon2id is one-way, so the entropy
isn't recoverable from the wrapped keypair). Until that extension lands, T-RC-07 is dormant — no
phrase can be revealed regardless of the gate. When the SDK extension ships, this entry's gate
is the v1 mitigation; no fallback path is enabled.
Residual risk: **Low**, conditional on the gate's correctness. The remaining surface is a
proximate attacker who can also satisfy the user's authenticator (e.g. a coerced user — covered
under T-U-02). Re-evaluate if user research surfaces a need for an "ask every time" session-mode
override during reveal sessions.

**T-RC-08. `ownerId` as a recovery capability (relay-metadata-minimisation F8).**
Actors: AD1+, meaning anyone who has seen one pairing QR, link or screenshot. Assets: A9 (the former
server-side blob), plus realm existence and the device-removal count (metadata). Invariants: I1, I7.
`ownerId = sha256(masterPub)` is **public by construction**. The pairing QR is
`tenshu://join?o=<ownerId>&d=…&k=…&s=…` (`PairingUri.kt`), so photographing it yields the ownerId.
Until 2026-10-28, three unauthenticated routes took it as their only input:
- `GET /sync/recovery-blob?ownerId=` returned the A9 ciphertext plus `manifestVersion`;
- `GET /sync/recovery-challenge?ownerId=` returned 404 for no realm, an existence oracle. The claim
  route leaked the same thing through 404-vs-401;
- `GET /sync/realm-epoch?ownerId=` returned existence plus how many devices the realm has removed.

The severity was bounded:
- the phrase is 24 BIP-39 words, 256-bit entropy (§11.2), and the blob key was Argon2id over it
  (T-RC-02), so the blob was never brute-forceable;
- no client ever uploaded a blob, so production served none.

The metadata half was real.

Defense (F8):
- the **blob routes and table are removed** (A9 amendment). Recovery never used them.
- the **challenge** names no realm and is issued to anyone. At most 10 000 may be outstanding; past
  that the relay answers 503. Each client IP (an IPv6 client per /64, since one subscriber holds a
  whole /64) gets at most `RECOVERY_CHALLENGE_RATE_LIMIT_PER_MINUTE` (default 6) and then a 429 with
  `Retry-After`. That check runs before the store, so a throttled caller holds no slot. The IP is the
  `X-Forwarded-For` Caddy sets, which it overwrites for untrusted peers and the relay port is not
  published. The limiter's own map is capped at 10 000 sources (LRU), and the IP is never logged.
- the **claim** carries the master public key. The relay verifies the signature against it *first*,
  and only then looks up `sha256(masterPub)`. So "no such realm" (404) is told only to a caller who
  proved the master key, and every stranger gets the same 401.
- the **realm epoch** needs the device JWT (T-K-02 amendment).

Residual risk: **Low.** What remains:
- (a) availability only. One source can hold at most ~36 challenges per 5-minute TTL (a burst of 6
  plus 6 a minute), so filling the 10 000 slots and delaying recoveries for one TTL takes ~280
  IPv4 addresses or IPv6 /64s acting together. Bounding a distributed flood is the open CDN decision
  (`deploy-relay-digitalocean.md` §"DDoS & abuse resistance"). A household behind one NAT shares one
  budget, which is ample for a recovery that needs one challenge per attempt.
- (b) the challenge route still answers, so a prober learns a relay is up, and nothing about any realm.

**T-RC-09. A refused recovery phrase must not become a realm.**
Actors: none hostile; the user who typed a wrong phrase. Assets: A1 (the master identity a phrase
seats on a device). Invariants: I1.
Until 2026-10-28 `RecoveryClaim` seated the phrase on the device (`SyncManager.initialize`) *before*
claiming. Seating is not passive: it makes the device enabled and puts A1 in memory, and the next
`SyncScheduler` tick runs the lazy personal-realm genesis (`RealmRegistrar.ensureRegistered`,
`POST /sync/realm`) for it. A valid-but-wrong phrase therefore created a **fresh, empty realm** under
it: if that tick won the race the claim found the realm and reported success, so the user believed
they had recovered and landed in an empty account; if the claim won, it failed with 404 but the realm
was registered a tick later anyway. The device was also left seated on the wrong phrase, so a retry
with the right one skipped `initialize` and kept the wrong keys.
A BIP-39 checksum catches most typos, but about 1 in 16 single-word substitutions still checksums
for a 12-word phrase, and fewer for 24 words. A phrase for a realm the relay no longer holds also
lands here (the realm was erased, or the user is on a different relay).
Defense: `RecoveryClaim` seats A1 **only after the relay admits the claim**. The challenge and the
claim need nothing seated: the claim is unauthenticated, and the device key and id are device-local.
A refused claim leaves the device unseated, and no realm is created on the relay.
`RecoveryOwnerIdBlindSystemTest.aPhraseWithNoRealm_isRefused_andCreatesNoRealm` and
`RecoveryClaimImplementationTest.claimFailure_propagates_andDeviceEntityNotSaved` pin it.
A device that already belongs to a **different** realm is refused before any relay call
(2026-10-28, maintainer decision): `RecoveryClaim` compares the phrase's ownerId with
`SyncManager.residentOwnerId` — the durable membership marker, so a *locked* device and one still in
its cold-start autoload count as set up, where `isEnabled()` would not — and throws the typed
`DeviceAlreadySetUpError`, which every recovery UI renders as "forget / disconnect this device
first". Before this, recovering realm Y on a device enabled for X kept X's keys (the `isEnabled()`
guard skipped `initialize`) while the relay admitted the device to Y, and on a locked X device it
seated Y's keys beside X's unlock wrapper and joiner payload. The **same** realm is let through on
purpose: the lock screen's "Recover" is how a user who forgot the passphrase gets back into the realm
the device already belongs to. `RecoveryClaimImplementationTest.aDeviceEnabledOnAnotherRealm_*`,
`aLockedDeviceOnAnotherRealm_*`, `aLockedDevice_recoveringItsOwnRealm_isAllowed` and
`RecoveryOwnerIdBlindSystemTest.aDeviceAlreadySetUp_isRefused_recoveringAnotherRealm` pin it.
Residual risk: **Low.** None specific to this threat; the refusal leaks nothing to the relay (it
never hears of the attempt).

### 8.7 User

**T-U-01. Social engineering / phishing.**
Actors: AD2–AD4.
Attacker impersonates support, convinces the user to read their recovery code or approve a pairing.
Defense: UI never asks the user to enter the recovery code anywhere except the explicit "I lost all
my devices" recovery flow. UI repeatedly reminds users that no legitimate Tenshu support will ever
ask for the recovery code. Pairing approval requires physical proximity to the device doing the
scanning.
Residual risk: **Medium.** Social engineering is fundamentally a human problem.

**T-U-02. Coerced user (duress).**
Actors: AD2, AD4.
User is physically compelled to approve a pairing or recover via their code.
Defense: Out of protocol scope. A duress password or duress recovery mode (fake but plausible
account contents) is a possible future feature; not in v1.
Residual risk: **Accepted.** Documented in §10.

**T-U-03. User approves pairing without checking the SAS.**
Actors: AD3–AD5. Loops back to T-P-02.
Defense: UI design. Make the SAS comparison the primary gesture, not an optional step. Approval
button is only enabled after the user confirms "the code matches" via an explicit tap. A ≥5 s delay
before the button enables on first use.
Residual risk: **Medium** by default; dropping to **Low** if the UI enforces the gesture.

### 8.8 Rei trust chain

**T-REI-01. Tenshu root developer identity compromise.**
Actors: AD4 / AD5 with access to Tenshu Inc.'s signing infrastructure, or a key-extraction
attack against the HSM. Assets: A11; by extension every user's trust in every first-party
Tenshu app. Invariants: I1, I2, I3.
An attacker who controls A11 can sign arbitrary publications, issues, and evaluations that
any Control treats as authentic — including a fabricated first-party manifest that grants a
malicious app full ambient access to user data.
Defense: A11's private side is HSM-backed at Tenshu Inc. and never leaves that
infrastructure; multi-party signing operationally required for any artifact emission.
Rotation requires a new Control binary distributed via TA4 (slow). Once a ledger-side
revocation feed exists ([`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.A.3), a "this Rei
key is compromised" record can propagate without a binary update — but the bundled source
has to be supplemented at runtime to consume it, and that mechanism is itself open
([`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.A.2 bundled-record update channel).
Residual risk: **High** if compromised — Tenshu's entire trust anchor goes with it.
**Low likelihood** given operational practices. Pre-ledger, rotation is slow (binary
update) and is an explicit operational concern.

**T-REI-02. Compromised REL evaluator key.**
Actors: AD3 / AD4 with access to an REL evaluator's signing identity. Assets: trust in the
Rei evaluation chain (chains rooted at A11 transit through evaluator signatures).
Invariants: depend on what the evaluator is trusted for.
An attacker uses a stolen evaluator key to sign fake "this developer is trustworthy" or
"this app's permissions are correct" attestations.
Defense: evaluator trust forms a chain — Control trusts an evaluator only via a path back
to A11 or via an evaluator the user has explicitly chosen to trust. Revocation of a
compromised evaluator key flows from any source to all sources per
[`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.A.3 (cross-source revocation). The
behavioural-review tier for sandbox-exception apps ([`tenshu-rei.md`](../proposals/tenshu-rei.md)
§5.F.47) requires a higher-tier signature, providing defence-in-depth for that subset.
Residual risk: **Medium** for a targeted attack. Most exposed: pseudonymous developers
signed by a single evaluator (cross-references
[`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.D.39 — the Sybil-resistance signal choice
shapes this exposure).

### 8.9 Shared realms

**T-SR-01. Shared-realm membership-change race.**
Actors: AD5. Assets: A12. Invariants: I4 for the shared realm.
The relay reorders or delays a membership-change block so a recently-removed member's
signed write is accepted by other members. Mirrors T-K-02's pattern but on shared rather
than personal realms.
Defense: shared realms reuse the existing HLC + signature mechanism. Membership changes
carry the shared realm's epoch; merge-time enforcement rejects writes whose
`admittedAtEpoch < currentSharedRealmEpoch` (same `<` rule as T-K-02). Each member tracks
the shared realm's current epoch via tombstone count over the shared realm's stream.
Residual risk: **Low** post-implementation; mirrors T-K-02's bounded residual on personal
realms. Implementation slice not yet landed.

**T-SR-02. Cross-member impersonation within a shared realm.**
Actors: a compromised member, or AD3 / AD4 holding a stolen member key. Assets: A1 of the
impersonating party, A5 within the share. Invariants: I3 for the share.
A malicious member signs writes attempting to claim they originated from a different
member.
Defense: per [`tenshu-rei.md`](../proposals/tenshu-rei.md) §2.3, writes to a shared realm
are signed under the member's own A1 identity (not A12). Authorship verification uses the
same per-block signature check as personal realms (T-T-02 / T-T-03). A12 provides
confidentiality only, not authorship — the confused-deputy attack the §2.3 design avoids.
Residual risk: **Low** by design.

**T-SR-03. Recovery of a shared realm when the owner's personal realm is lost.**
Actors: user error compounded by the single-owner shared-realm design
([`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.C.22 default lean). Assets:
availability of A12. Invariants: I7 for the share.
A shared realm has a single owner. The owner loses their personal realm and didn't
include the shared realm's wrapped key in their recovery manifest (A10). Without owner
recovery, rotation authority is lost.
Defense: open per [`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.C.28. Current default
lean: every member's `SharedRealmMembership` record includes the wrapped shared-realm
key, so any surviving member can re-create the shared realm under a new owner — turning
single-owner-with-transferable-ownership into "owner is recoverable as long as ≥1 member
survives." If §5.C.28 resolves the other way, the worst case is unrecoverable shared
realms.
Residual risk: depends on §5.C.28 resolution. **Medium** pre-resolution.

**T-SR-04. Per-app permission leakage across realm boundaries.**
Actors: AD3 — a malicious or compromised app installed in a member's realm. Assets: A5
within the shared realm. Invariants: I1 / I3 partial — specifically, the integrity of
*attribution* in the share.
[`tenshu-rei.md`](../proposals/tenshu-rei.md) §5.C.29 (open): does an app's
`FileSystem.list` grant on the member's personal realm extend automatically to shared
realms the member is in (default lean), or does each shared realm have its own grant
set? If permissions extend automatically, a misbehaving app reads/writes shared content
under the member's identity — which other members see attributed to the (legitimate)
member, masking the app as the actual source.
Defense: open per §5.C.29. If permissions are realm-scoped, the trade-off is friction (a
user re-grants for each shared realm they join). If permissions are member-scoped, the
trade-off is the impersonation surface described above.
Residual risk: depends on §5.C.29 resolution. Flagged so the eventual implementation
slice picks deliberately rather than defaulting.

**T-SR-05. Shared-realm file content exposed to realm membership.**
Actors: AD1 (a current realm member, including a since-removed one who fetched while a member),
AD4/AD5 (relay / network). Assets: A5 (a shared file's plaintext), A12 (the realm volume key).
Invariants: I1 (relay confidentiality — preserved).
When a folder is shared, `ShareFolderIntoRealm` fans each file's content **into the shared realm's
content plane** (`drive-shared-realm-file-model.md` §6 Phase 3b.2): the host reads the plaintext from
its own `FileDatabase`, chunks it, AEAD-encrypts each chunk under the **realm's** volume key
(`deriveSharedRealmKeys(A12).sharedVolumeKey`, *not* the personal key), `PUT`s the ciphertext to the
realm's own `ownerId`-scoped `ContentBlobStore`, and emits a manifest bound to the `SharedFile`'s
realm-namespaced DataId. Every current realm member holds A12 (via their `SharedRealmMembership`
key-wrap, A12/A13), so **every member can fetch + decrypt the content — by design: placing a file into
the realm *is* the authorization act.** The relay only ever sees ciphertext + opaque content refs
(House Rule #1 holds — same posture as personal-realm content, T-R-09).
Defense: confidentiality rides entirely on A12 (per-realm, full-entropy, never on storage/wire except
key-wrapped — A12 asset). Revocation = dropping the `SharedFile` (Phase 4) **plus a realm re-key on
member removal** (rotate A12, re-wrap to remaining members — A12 "rotate-on-remove" note) so a removed
member's retained A12 no longer decrypts post-rotation content. No new relay surface: the realm content
store + AEAD path are the SR-3c-prereq mechanism, unchanged.
Residual risk: **a member (or removed member) who fetched + decrypted content while authorized retains
those plaintext bytes** — capability semantics identical in shape to T-S-01 (link sharing), bounded to
the realm's shared files. The per-open access-pattern residual (T-R-09) applies to the realm content
store as to the personal one. Re-key-on-remove is the forward-secrecy boundary for *future* content,
not already-fetched content.

**T-SR-06. The relay links a shared-realm owner's devices to its personal realm.**
*(Added 2026-10-28, `relay-metadata-minimisation.md` F6 / D9.)*
Actors: AD5. Assets: A6, A4. Invariants: none violated (I1 holds; this is metadata).
A shared realm is created through the ordinary `POST /sync/realm` by an owner that **reuses its
personal A4 signing key and `deviceId`** (`SharedRealmBootstrapper.kt:93-94`, *"A4 — reused across
realms"*), and the bootstrapper then admits the owner's other devices under their personal ids and
keys too (`admitKnownDevices`). So the relay's device registry holds `(personalOwnerId, D, K)` and
`(sharedRealmId, D, K)` with the same `D` and `K`: it can join a person's personal realm to every
shared realm they **own**, and attribute shared-realm writes (by the JWT `sub`, and by `hlc.deviceId`
at rest — T-R-08's F9 amendment) to that person's devices. Cross-user **members** do not have this
problem: they register a fresh per-realm key and `deviceId = sha256(perRealmPub)`
(`SharedRealmGrantManager.kt:214-215`), so only IP and timing link them (§8.14).
Defense: none today. The fix is a per-realm device identity for owners, as members already have.
It is **large**: the owner's personal A4 key is what lets its route-1 runtimes co-own shared realms
(A4, Decision C — first-party apps are realm co-owners), so a per-realm owner identity touches the
bootstrapper, the device registrar and route-1 co-ownership, not the relay.
**Decision (D9): accepted and documented; revisit at shared-realm GA.**
Residual risk: **Accepted, documented** — the relay learns which shared realms a personal realm owns
(no content, no member identities beyond the owner's own devices).

### 8.10 Link sharing (Mode A)

Covers the anonymous capability-URL share channel
([`docs/file-sharing.md`](../file-sharing.md), rationale in
[ADR 0018](../adr/0018-file-sharing-mode-a.md)): the sharer re-encrypts a
file under a fresh per-share key (A17), uploads independent ciphertext chunks to the relay's
`/share` store, and the recipient fetches + decrypts anonymously from the URL fragment. Landed
end-to-end at the protocol level (proxy `RestShareRelayClient` + per-chunk AEAD under `K_share`;
recipient `ShareViewer.fetch` in `:tenshu-sdk-js`).

**T-S-01. Share-link disclosure.**
Actors: AD1–AD5. Assets: A17, A5 (the shared file's plaintext). Invariants: I1 (carved out in §3).
Anyone who learns the URL — the user forwards it, posts it publicly, a messaging app scans its
content — gains read access to that one file until revocation. `K_share` is the read capability.
Defense: **UI-level only.** The share-creation dialog never auto-copies the link; copy is an
explicit gesture behind a one-time "anyone with this link can view this file" confirmation that
also surfaces the expiry / opens-cap options. Revocation is one tap from the *My shares* list
(and from Control's Shares inventory, `control-ia.md` §5.3). No technical defence against
re-forwarding by design — capability sharing means the URL *is* the permission.

Two constraints bind the *share-out* affordance beside Copy (`drive-shared-ui/.../ShareText.kt`),
which hands the URL to a messenger:

- **No web share-intent redirectors.** `wa.me/?text=…`, `t.me/share/url?url=…` and their
  equivalents place the whole URL in the *query string* of a request to that operator's server.
  The `#k=` fragment is withheld from servers only while it remains a fragment; as another
  site's query parameter it is an ordinary request field, so the operator would receive
  `K_share` in its logs. All hand-offs are therefore OS-local: Android's `ACTION_SEND` chooser,
  iOS's `ShareLink`, desktop's `mailto:` (dispatched to an installed client). This is a hard
  constraint, not a preference — it converts AD-of-the-user's-choice into an unrelated third
  party who was never a recipient.
- **The subject line never carries the key.** Only the shared text does; `EXTRA_SUBJECT` /
  `mailto:?subject=` carry the file name, which the recipient's mail provider sees either way.

Copying is marked `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+, which suppresses the
system clipboard toast's content preview — otherwise `K_share` is rendered on screen after
the copy, the one shoulder-surfing exposure in the flow that costs nothing to close.
Residual risk: **High by capability semantics**, equivalent in shape to T-RC-01 (recovery-code
disclosure) but with a bounded blast radius (one file, not the realm).

**T-S-02. Anonymous fetch-trace.**
Actors: AD4, AD5. Assets: A6 (metadata).
`GET /share/{shareId}` and `GET /share/{shareId}/chunk/{i}` are unauthenticated (capability-as-URL).
A passive relay (AD5) learns *that* a share was opened, the requester's network identity (IP,
User-Agent), timing, and by chunk-fetch count the approximate file size (exact, from the last
chunk's length — F10); an active relay can correlate by IP across shares. It does **not** learn
the file's name or MIME type, which reach it only sealed under `K_share` (amended 2026-10-27; see
the reaffirmations below).
Defense: rate-limit-by-IP at the relay (administrative, not protocol); the sharer-visible opens
count helps detect unexpected traffic. No further mitigation in v1 — anonymous-by-design.
Residual risk: **Accepted, documented.** Extension of the T-R-09 P3 access-pattern residual to
*unauthenticated* opens where the request source is whatever IP the recipient is on.

**T-S-03. Stale ciphertext after revocation.**
Actors: AD5. Assets: A5 (content of revoked shares).
A revoked share's chunk blobs sit in the relay's share store for one retention/orphan-GC window
past `revokedAt` (the same GC discipline as the content plane, T-R-09 P3b). A relay that
snapshotted before the sweep retains a usable ciphertext copy for that window.
Defense: revocation propagates immediately as `revokedAt` + a 410 on every recipient `GET`;
chunks linger only at the storage layer, never at the serving layer. Bound is one retention
window.
Residual risk: **Low.** Unchanged from T-R-09 P3b's orphan-blob posture — revoking a share has
the same retention shape as deleting a file.

**Reaffirmations (no separate threat).** *Integrity / authorship (I2/I3)* of share content hold
via **per-chunk AEAD under A17** with share-scoped AAD `(shareId, chunkIndex, chunkCount)`: a
tampering or wrong-chunk-serving relay produces AEAD/AAD failures the viewer surfaces as a
decrypt error, a reordered or cross-share chunk fails the AAD bind, and a missing trailing chunk
reads 410. *v1 integrity note:* AEAD is the **sole** integrity boundary in v1 — the descriptor's
per-chunk hash anchor (`chunkHashes`) is left empty because a record-first relay can't carry
sharer-authored ciphertext hashes without buffering all ciphertext or a finalize endpoint; the
field stays in the schema for a future slice ([`docs/file-sharing.md`](../file-sharing.md) §7 K3;
[ADR 0018](../adr/0018-file-sharing-mode-a.md) *When to revisit* trigger #1). *I4 (forward
secrecy on revocation)* is not in play — share revocation is capability invalidation, not volume
re-keying; the §3 I1 carve-out already scopes this. *House Rule #1* is unchanged: the relay holds
only ciphertext chunks, an opaque `shareId`, and the share's enforcement state — `chunkCount`,
expiry, `maxOpens`, the open count, revocation — never `K_share`, never plaintext.

*Amended 2026-10-27 (`relay-metadata-minimisation.md` F3, D13 item 2):* the relay **no longer
holds the file's name, MIME type or exact size**. The earlier wording kept them in the clear on
the rationale that they are "file metadata the recipient is about to see anyway" — which
conflated the *recipient* (who holds `K_share`) with the *relay* (which does not), and kept a
file name for ever on any share without an expiry. They now travel only inside the descriptor's
`sealedMeta`: XChaCha20-Poly1305 under `K_share` itself (A17, no derived key; a fresh 24-byte
random nonce per seal), with associated data `tenshu/file-share-meta/v1|shareId|chunkCount` that
is domain-separated from the chunk AAD. (As everywhere `SyncCryptoManager.encryptWithAad` is used,
the associated data is length-framed into the plaintext and so authenticated inside the
ciphertext, then compared byte-for-byte after decryption — not passed as native AEAD AAD.) The plaintext is zero-padded to a 256-byte bucket, so the
stored length reveals the name's length only to within that bucket. Binding `chunkCount` also
means a relay that edits the served `chunkCount` makes the metadata fail to open, and the viewer
checks the decrypted byte count against the sealed size. A viewer that cannot open `sealedMeta`
(wrong key, absent or malformed blob) fails visibly (`decrypt` / `meta`); it never renders the
file under a blank or placeholder name. Pre-release, so the sealed descriptor is the **only**
format — no clear-field fallback exists on any viewer or on the wire.
**Residual, accepted:** (a) **size** — `chunkCount` × the 2 MiB share chunk stride bounds the
file size, and the last chunk's ciphertext length gives it modulo the stride, so the relay can
still compute the exact size from the chunk store (F10, T-T-07; padding is Phase 7's question);
(b) **who opened it** — the sharer→recipient-IP linkage of T-S-02 is untouched; (c) the MIME
type is not separately inferable, but the chunk bytes' size and access timing remain observable.
**Dropping the columns did not erase them** (relay migration 4, 2026-10-28): `DROP COLUMN` rewrites
each row but leaves the old record bytes in freed pages and freed cell space (`secure_delete` is
off), so version 3 alone left the clear names and MIME types readable in the raw `relay.db`.
Migration 4 runs one `VACUUM` plus a `wal_checkpoint(TRUNCATE)`, once, which rebuilds the file
from live rows only. ⚠ Since the 2026-10-28 re-cut (`sync-relay-persistence.md` §0.1) there is no
migration 4: the relay restarts on an empty volume whose `share_record` never had the clear columns,
so nothing remains to purge, and a pre-re-cut file is refused at startup rather than migrated. (d) **Backups** — a nightly snapshot taken *before* the relay ran version 3
holds them as live columns, and nothing rewrites it; it expires under the 30-day bucket lifecycle
([`back-up-the-relay-database.md`](../how-to/back-up-the-relay-database.md)). Snapshots taken
after are `VACUUM INTO` copies of live rows, so they never held the residue.

**T-S-04. Operator takedown of a share (notice-and-action).** *Added 2026-10-29*
([`take-down-a-share-link.md`](../how-to/take-down-a-share-link.md), acceptable-use §3).
Actors: AD5 (the operator), and anyone who reaches the route. Assets: A5 (a share's ciphertext), the
relay's share state.
`POST /ops/share/takedown` revokes one share by `shareId`, appends a `share_takedown` row in the same
transaction, then deletes that share's chunk bytes (`share/<shareId>/<n>`) at once rather than after
T-S-03's retention window. It is a **third destructive operator surface**, beside account closure
(§8.15) and `/ops/content-reclaim/*` (§8.3, CR-2 B0), and it sits behind the same three guards as the
latter: registered only with `RELAY_OPERATOR_TOKEN` set (a plain 404 otherwise), answered only for a
**loopback socket peer** judged on the raw connection and never on `X-Forwarded-*`
(`OperatorRouteNetworkExposureTest` drives a real socket from a non-loopback address with a forged
`X-Forwarded-For: 127.0.0.1` and gets 404), and the token compared in constant time. Caddy now
also answers `/ops/*` with 404 at the edge, so a regression in the relay's guards is still not an
internet-facing route.
**New relay state:** `share_takedown(id, share_id, owner_id, taken_down_at, reason_code,
operator_note, outcome)`, relay migration 4 (⚠ not the pre-re-cut migration 4 that `VACUUM`ed F3's
residue, above; the 2026-10-28 re-cut retired that number). Append-only: SQLite triggers refuse
`UPDATE` and `DELETE`. It holds an opaque `shareId` and `ownerId` the relay already held, a time, a
fixed reason code and the operator's one-line note. **No user plaintext.** ⚠ It **is new retained
metadata**: `share_record` loses the `shareId → ownerId` link when the share GC drops a revoked row or
the account is closed, while `share_takedown` keeps `shareId`, `ownerId` and the time for 3 years,
**past account closure** (`AccountErasure.KEPT_TABLES`, beside `erased_realm`), as the operator's
evidence of having acted on a notice; a `not_found` row also keeps whatever id an operator typed.
Retention: **3 years from the takedown, then deleted** (maintainer decision 2026-10-29; the purge is not
built yet, `tech-debt.md`). ⚠ The purge must keep the bare `shareId` retired (see below): it erases the
owner link and the note, not the id. The table also retires the id: `create` refuses any `shareId` with a
takedown on record, because the sharer picks ids and could otherwise re-publish the reported URL once
the GC dropped the row. `POST /share` now enforces the same `shareId` shape the takedown route
accepts (base64url, 1–128), so no link can be created that an operator cannot name. Recipients learn nothing new: a taken-down link answers the same 410 as a revoke or an
expiry. The share PUT route now also refuses a chunk for a revoked share (410) and an index at or past
`chunkCount` (400), and the store re-checks revocation *after* writing a chunk and deletes it if the
share was revoked meanwhile (a takedown commits its revoke before deleting), so neither a takedown nor
the GC (both delete `0 until chunkCount`) can miss bytes, including a PUT in flight.
**HR #1 intact:** the action is keyed by the routing id; nothing is read or decrypted, and the note is
the operator's words, not the user's (the runbook forbids content descriptions and reporter data;
the relay cannot enforce it).
Residual risk: **Low.** (a) A compromised relay box or operator can take any link down; that actor
could already delete any byte, so the route adds no capability, only a record. (b) The weekly blob
backup keeps the deleted chunks until it is pruned (policy §4.4). (c) The audit rows live in the
relay database, so a full relay wipe (`deploy/reset-test-data.sh`) removes them with everything else;
"append-only" holds against the application, not against the operator's own reset.

**Mode B (named-recipient sharing)** is deferred to the *Cross-realm content distribution*
milestone and the shared-realm work (A12/A13, §8.9); the Mode-A link channel here ships first
and does not depend on it.

### 8.11 App Console diagnostic uploads

Threats against the user-initiated diagnostic upload (A19), reachable from Settings → Errors via the
"Send error report" CTA. The recorder + local log are addressed by `docs/proposals/core-error-channel.md`
§5; this section enumerates the wire-side residuals.

- **T-L-01. Console operator infers user identity from upload payload correlation.** *AD3, AD4
  (Console operator role).* The operator receives anonymous `{ uploadId, events[] }` payloads from
  many devices; correlating timestamps, `appVersion`s, and `osLabel`s could re-identify a small
  user population.
  **Mitigation:** zero identifying fields on the payload (`deviceId` dropped, no user id, no realm
  id, no IP-stable identifier inside the body); `uploadId` is freshly minted per upload and never
  reused. Always user-initiated — the user must tap a button. One-shot — no retry persists state
  across attempts. Coarse `osLabel` (no model strings, no installation id). **Residual:** an
  operator that observes IP + roughly clock + app version on multiple uploads from the same user
  can still cluster them. We don't claim defence against a Console operator who is also a *network
  observer*; an external mix / privacy proxy is the structural answer there. Accepted residual for
  v1 — the proposal §4.3 states the trust model explicitly.

- **T-L-02. Regex-scrubbing bypass.** N/A by construction. There is no regex scrubber — payload
  safety is structural: the `ReportEvent` type physically cannot carry `deviceId` or
  `internalDetail`, and the seam (`UploadableEvent.toReportEvent()`) is the single redaction
  point pinned by `ReportEventRedactionTest`. The proposal §5 explicitly rejected scrubbing as
  brittle vs. typed-by-construction.

- **T-L-03. Uncaught exception leaks user payload via `e.message`.** *AD3 (Console operator).* A
  caught `Throwable` inside an op body keeps its developer-friendly message via
  `InternalError.internalDetail` (proposal §3.5). That field may contain a file path, an entity
  id, a user-entered string. **Mitigation:** `internalDetail` is **proxy-local only** — kept on
  the local `ErrorEvent` for the Settings → Errors detail view, but **dropped at the upload
  seam** by `UploadableEvent.toReportEvent()` (the redaction is structural, not regex-based).
  `ReportEventRedactionTest` pins both omissions. The user sees the full local detail on the
  Diagnostics screen, so they know exactly what would *not* be sent. Accepted residual: a
  developer needing the detail must obtain it out-of-band (screenshot in a bug report).

### 8.12 Automatic background upload (Synced Folders)

Threats against the Synced Folders auto-backup feature (`docs/proposals/drive-synced-folders.md`).
**No new cryptographic / relay / protocol surface:** auto-upload is N invocations of the existing
per-file upload op, so the relay sees the same ciphertext SyncBlocks as a manual upload and assets
A1–A4 / A9 are untouched (proposal §6, same conclusion as folder-upload). The genuinely new property
is a **consent / expectation** one, recorded here per House Rule #2 rather than as a new asset row —
final placement is a maintainer call when the feature graduates from Phase 1.

- **T-B-01. User content leaves the device automatically, on a stored policy, with no per-file
  action.** *AD5 (the user's own expectation surface), AD3 (relay sees more uploads sooner).* This is
  the first feature where content egresses without a deliberate per-item gesture, so a
  mis-configuration silently ships more than the user intended.
  **Mitigation:** the feature is **opt-in** — the master switch defaults OFF (proposal §3.7); the
  source selection is **device-local and user-revocable** (a `PreferenceScope.DEVICE` preference,
  §3.1); uploads honour the **storage gate** (§3.6) and the **Wi-Fi / charging constraints** (§3.8);
  video is a **separate explicit opt-in** (§3.7 / open-Q4). Disabling the master switch stops the
  in-process loop immediately, and a **device revocation** must stop any scheduled ingest (the
  `PlatformScheduler.cancelAll` / WorkManager `cancelAllWork` lever, `core-background-processing.md`
  §6.2). **Residual:** the per-bucket selection roams to no other device, so a user managing many
  devices reviews each separately (accepted, shared with Google Photos).

- **T-B-02. Control→app reverse trigger / cross-app process-start — WITHDRAWN.** This line
  reserved a threat for a Control→app "ingest now" tick that could wake a dead ingest process — a new
  IPC direction. **That mechanism is cancelled.** The maintainer decision
  ([`milestones.md`](../milestones.md) "Background-service story";
  [`drive-synced-folders-phase2.md`](../proposals/drive-synced-folders-phase2.md) §1) is
  *capture is app-side, policy is Control-side* — **each app registers its own OS background trigger
  and wakes itself**, so there is **no** reverse trigger and **no** cross-app process-start. IPC stays
  strictly **app→Control**; Control is a *pull* authority (the app reads policy + enabled-state), never
  a *push* driver. The new IPC direction this line guarded against therefore does not exist, and the
  surface is **smaller** than the original Phase-2 design, not larger. No threat line is needed.

- **T-B-03. Background-clock pattern-of-life (Phase 2).** *AD3 (relay observes push timing), §10
  metadata caveats.* Self-triggered background ingest pushes ciphertext blocks on a *clock* (e.g. a
  nightly burst on Wi-Fi + charging), so even though every block is opaque, the **regularity and timing**
  of `POST /sync/changes` is a pattern-of-life signal to the relay ("the user got home and plugged in").
  **Mitigation:** this is an *amplification* of the A6/§10 metadata the live sync loop already exposes
  (the relay always sees push timing), not a new channel; the `ScheduleSpec.flexPeriodMs` window + the
  OS scheduler's own batching blur exact times. Accepted as an A6 amplification, not a new asset.
  Timing-decorrelation (jitter / cover traffic) is out of scope and unsized. The registry rows
  (`IngestSource`, phase-2 doc §3) are the user's own synced metadata — same trust domain as any synced
  composite; `LAST_ERROR` rides the redaction-by-construction seam (§8.11 / A19) so no path/content leaks.

- **T-B-04. iOS photo-library authorization + PhotoKit source
  ([`ios-drive-parity.md`](../proposals/ios-drive-parity.md) P6b).** *AD5 (consent / expectation).*
  The iOS Synced-Folders reframe reads the photo library via PhotoKit (`PHAsset` enumeration +
  `PHAssetResourceManager` original-bytes read), which requires **full photo-library read
  authorization** (`NSPhotoLibraryUsageDescription` + a runtime
  `PHPhotoLibrary.requestAuthorization(.readWrite)` prompt) — a new consent surface not present on
  the manual-picker path (which uses the scoped `PHPickerViewController`, no library-wide grant).
  **Mitigation:** the grant is requested only when the user turns the **opt-in** "Back up my Photos"
  toggle on (default OFF); a denial — or iOS "Limited" selection — leaves backup off / scoped to the
  granted assets. **No new cryptographic / relay / protocol surface:** each photo rides the SAME
  per-file upload op as a manual upload — which since P3b stages + enqueues the sealed CEK chunks on
  a background `URLSession` — so the relay sees the same ciphertext SyncBlocks / content blobs
  (T-B-01 conclusion holds; A1–A4 / A9 untouched). The read bytes are a transient in-memory
  `ByteArray` (A5); decrypted plaintext never lands on disk beyond the existing P3b staging (opaque
  sealed ciphertext only). ⚠ The live photo-change trigger (`PHPhotoLibraryChangeObserver`) is
  deferred — v1 drives drains only from the foreground "Back up now" catch-up + the P6a background
  wake, so a mis-configured always-on background photo scan is structurally impossible (no scan
  without an app wake). Actual enumeration / consent / upload against a real library is the P7
  on-device pass.


### 8.13 Contact invitation handshake

Threats against the cross-user identity-acquisition handshake
([`contact-invitation-handshake.md`](../proposals/contact-invitation-handshake.md); assets A24/A25,
surface #11). The handshake delivers *public* identity material + self-asserted names — never data
keys — so its failure modes are consent/identity failures, not key-compromise ones.

**T-CI-01. Link interception + pre-emptive redemption.**
Actors: AD2–AD4 (channel compromise), AD5 (relay sees no link). Assets: A24; A12
transitively iff a grant follows. Invariants: none directly (the handshake grants no data
access).
An attacker who reads the outside channel redeems the invite first, presenting their own
key under the expected person's name.
Defense: single response slot — the legitimate invitee's later attempt fails loudly
(`409`), prompting investigation; TOFU quarantine (self-asserted name never becomes the
petname without user confirmation, no auto-merge per contact doc §6); the checkmark
requires SAS (⚠ and the SAS is bound to the key that gets stored only since 2026-10-02 —
T-CI-10); any subsequent realm grant is a separate explicit act on a displayed key
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

**T-CI-09b. A verification recorded without a comparison.**
Actors: AD1 (the user themselves, tricked or hurrying); a malicious/compromised app on the
device holding a realm client. Assets: A24. Invariants: A24's *never auto-elevated*.
Post-handshake re-verification (`contacts-app.md` Ph5) makes `VERIFIED` reachable at any
time, so the interesting failure is no longer interception but a checkmark that means
nothing — a user who confirms without having compared, or a caller that passes
`verified = true` on some other trigger.
Defense: the verdict is inert by construction — `VerifyTenshuIdentity` writes only what it
is told, so the guarantee has to live at the surface, and it does: the confirm action is
disabled unless the six digits are actually on screen (`hasSas`), the code is fetched
per-key rather than cached, and the copy states that confirming is an assertion about a
comparison the user performed. `ATTESTED` is refused in both directions so a local verdict
can never overwrite a Rei attestation. What remains is the ordinary A24 boundary: any app
holding a realm client can write the row directly, which is the realm-device-compromise
case A24 already accepts.
⚠ **Residual: Medium, and the same class as T-CI-01** — a consent failure, not a key
compromise. Containment is the design's load-bearing property: a forged checkmark grants no
data access on its own; realm access is still a separate explicit act on a displayed key
fingerprint (T-GR-\*). The per-app capability model that would close the malicious-app half
is Rei Q12, v2 and unbuilt.
*Web amendment (2026-10-23, [`web-app-suite.md`](../proposals/web-app-suite.md) F5):* on the
web the "app holding a realm client" is an **origin**, and the boundary between apps is the
apex gateway's **per-origin method allowlist** (`web/apex/src/lib/gatewayPermissions.ts`, which
since F4 composes each app's list from its registry file `web/apex/src/apps/registry/apps/<id>.ts`;
enforced in `serveProxyGateway`, `web/sdk/src/bridge.ts`). Before F5 any admitted origin was
served every method, so script running on `drive.tenshu.app` (an XSS) could call
`contact.setVerified(…, true)` with no comparison, or `contact.merge` / `contact.delete`. Now
each origin reaches only the methods its own UI calls, by exact name: Drive gets its list, which
holds the handshake ops its sharing flow runs and none of `setVerified`, `merge`, `delete`,
`rename` or the other address-book writes; Contacts gets its address-book and invite methods and
none of Drive's. Both get the session methods. A call outside the list is refused
(`not permitted …`), subscriptions included, and an origin not in the map is ignored.
**What this does not change:** (1) XSS on `contacts.tenshu.app` reaches every method Contacts is
allowed, including `setVerified` — the T-CI-09b residual, scoped to one origin instead of all of
them. (2) ⚠ **Verified-on-handshake from Drive:** Drive keeps `contact.complete` and
`contact.awaitAck`, whose `sasConfirmed: true` creates the identity at `VERIFIED` / `QR_SAS`
(Drive's QR+SAS sharing flow needs it). So XSS on `drive.tenshu.app` can mint an invite, redeem
it from an attacker's device, and complete it with `sasConfirmed: true` and a chosen `personId` —
attaching the attacker's key to an existing contact as verified with no comparison. F5 narrows
Drive's reach; it does not remove this path. Closing it needs the SAS confirmation to come from a
surface Drive's script cannot drive (the apex's own UI), which is future work. The allowlist is code, not a user grant; the per-app capability model is still
Rei Q12.

**T-CI-10. Response substituted between the prompt and the completion (inviter side).** *(found
2026-10-02 with T-P-14; bound in the same change; residual closed 2026-10-04 by contact-service v2)*
Actors: AD5 **together with** a T-CI-01 link reader — the relay alone cannot mint a response that
passes the `K_link` check (T-CI-02), so this needs the link *and* the relay. Assets: A24; A12
transitively iff a grant follows.
The inviter's completion prompt shows `ContactInvite.claimedFingerprint` and `claimedSas`, written
when the sync-cycle pickup verified one relay-served response. `CompleteContactHandshake` carries
only an `inviteId` and `sasConfirmed`, and takes the key it stores from the in-memory stash — or,
when the stash is empty (after a restart, and on every other inviter device, which render the same
*synced* claim), from a **second** relay fetch.
⚠⚠ **The defect:** nothing bound that response to the claim on the row, and the pickup re-pointed
both the claim and the stash at whatever valid response it was served next. A relay holding two
`K_link`-valid responses — the genuine invitee's and the link reader's — served the first to the
pickup and the second to the completion: the user compared the genuine code, ticked *codes match*,
and the link reader's key was stored `VERIFIED`, with the `accepted` ack sealed to it. T-CI-01's
defence ("the checkmark requires SAS") did not hold against a relay that broke its own single-slot
promise.
Defense:
1. **Completion accepts only the response the prompt showed.** A response whose key fingerprint is
   not the row's `claimedFingerprint` is refused before any row is written or any ack sealed, and
   is dropped from the stash so the next attempt asks again.
2. **A confirmation needs a code to have been shown.** `sasConfirmed` on a row that carries no
   claim is refused. (An *unconfirmed* accept of an invite nothing was shown for is the
   pre-existing TOFU path and is unchanged.)
3. **First claim wins.** A later response for an invite that already carries a claim, under a
   different key, is neither stashed nor shown. The relay's single slot already promises this; the
   client no longer forgives a relay that stops keeping the promise.
   ⚠⚠ **Including inside one relay answer.** The pickup walks `GET /sync/invite/responses` against
   a snapshot of the invite rows taken *before* the loop, so two `K_link`-valid entries for one
   invite in a single answer both saw "no claim yet" — and the second overwrote the stash and the
   synced claim (found in review of this change, the same day). The pass now carries what it has
   already settled per invite: the first entry's claim stands, and an invite burned earlier in the
   answer is not revived by a valid entry after it (nor does a response stashed earlier outlive a
   burn that follows it). The pairing poll has no such snapshot — it reads the live pin per entry.
4. **The refusals are typed, so the app can say what to do.** Rules 1 and 2 throw
   `InvalidParameter` (`inviteId` / `already-consumed`, `sasConfirmed` / `not-ready`), recognised
   by `CompleteContactHandshake.Refusal` in the Contacts, Drive and Control repositories and
   rendered from the shared catalogue as *"…decline this invite and send a new one"*. As first
   written they threw an English `GenericError`, which every app discards for "Something went
   wrong". No new composite, so no service bump.
5. **The client says what it showed — closed 2026-10-04 (contact-service v2).** The claim lives on
   a *synced, last-writer-wins* row: with two inviter devices both seeing the invite `PENDING`, a
   relay could serve each a different response, and the later write replaced the claim on the
   other device **by merge** — not through the pickup, so rule 3 never saw it — under a prompt
   already open. The prompts re-rendered the new code but kept the tick, and rule 1 passed because
   the row now named the second key. Now `CompleteContactHandshake` carries a **required**
   `shownFingerprint` — the raw claim the prompt displayed — and on ACCEPT the proxy stores the
   response only if its key is that fingerprint (and the row's, rule 1); a mismatch is the same
   typed `already-consumed` refusal. ⚠⚠ The comparison is **unconditional**: an ACCEPT with
   `shownFingerprint = ""` is refused (`already-consumed`, or `code-not-shown` with
   `sasConfirmed`), never read as "nothing to compare" — skipping it let a prompt that had not
   loaded its claim accept whatever the relay served, and it retires the old on-demand TOFU accept
   of an invite no pickup had claimed. `""` is legitimate only on REJECT, and every prompt gates
   accept on a loaded claim. Every prompt (Contacts, Drive, Control in Compose and SwiftUI, web)
   also clears its tick when the claim under it changes, and **submits the fingerprint of the card
   the user tapped, never the claim re-read at tap time** — the iOS center re-resolves its request
   by id, so it hands the rendered row's `shownFingerprint` back with the id
   (`NotificationsViewModel.acceptById`); re-reading would send a key that flipped before SwiftUI
   re-rendered with the old code's tick. So what is sent is what the tick was made against. **The bump:** an op-parameter change is a wire reshape (House Rule #9), so
   `ContactService` 1 → 2 with an empty, registered `schemaMigration(fromVersion = 1)` — no stored
   row moved, and the step is what keeps a v1 store migrating instead of being wiped.
   `TENSHU_STORAGE_FORMAT_VERSION` and `RELAY_PROTOCOL_VERSION` do not move; no relay change.
   **Lockstep:** the parameter is required, so a v1 caller fails loudly rather than defaulting to
   "nothing shown"; every app — and `:hub`, which registers the contact service through
   `buildProxyModule()` — moves together.
Pinned by `ContactCompletionBindsShownClaimTest` (the proxy, including both orders of two
responses in one answer and the claim flipping by merge under an open prompt),
`CompleteHandshakeRefusalCopyTest` (the key at the app boundary, including the flip),
`ContactServiceV2MigrationTest` (the v1 → v2 chain; a v1 store migrates, not wipes) and the prompt
view-model / card tests (raw fingerprint sent, tick cleared when the claim moves, no accept
before the claim loads; `NotificationsAcceptByIdTest` for the rendered-row passthrough).
Residual: **Low** — what remains is the T-CI-01 class it was always narrower than (a link reader
answering first: the code then shown is theirs and does not match the genuine invitee's), same
containment: a contact candidate, not realm access.
⚠ Not verified on hardware or against the real relay.

Invariants I1–I9 unchanged by this group. House Rule #1 audit: every relay-visible field
of the `/sync/invite/*` surface is routing metadata or sealed ciphertext; no decrypt code
lands server-side.

### 8.14 Cross-user membership grant

Threats against the cross-user membership-grant relay surface
([`shared-realm-membership-grant.md`](../proposals/shared-realm-membership-grant.md); assets
A26/A27, surface #12). The grant conveys *the realm key* (Kr/A12) — wrapped to the grantee's
A13 — so unlike the contact handshake (§8.13, public material only) a disclosure failure here
*is* key compromise; the design's load-bearing properties are the seal-to-A13 of the payload
and the relay-never-holds-Kr invariant.

**T-GR-01. Stolen / replayed grant blob.**
Actors: AD2–AD5 (channel/relay observers of the address). Assets: A27; A12 transitively.
Invariants: I1.
An attacker who learns a grant address fetches or replays the sealed grant.
Defense: the payload is sealed to the grantee's A13 (the relay never learns it — it is not in
any routing field), so a fetched blob is opaque without the grantee's A13 private key; the
mailbox is single-pickup + 30-day TTL, so a leaked address yields **denial** (someone deletes
it before the grantee reads), never disclosure; the address itself is an HKDF of the handshake
secret, derivable only by grantor + grantee. No proof-of-A13 challenge in v1 (accepted — the
seal makes a fetch worthless).
Residual: **Low** (disclosure); **Low / accepted** (denial — same class as T-CI-05).

**T-GR-02. Relay serves stale registrations after revocation.**
Actors: AD5. Assets: A26. Invariants: I5 (revocation).
A compromised/lagging relay keeps admitting a revoked member's devices, or fails to purge them.
Defense: **two layers** (Q26/D6). The admit path rejects any capability superseded by a
master-signed revocation in the per-realm list (`MembershipChainVerifier.isRevoked`), and the
revoke endpoint purges the authority's already-registered device keys from `KnownDevicesStore`.
⚠⚠ **"The relay cannot forge a revocation" was false until F4 (2026-08-21)** — see **T-GR-07**. It
could not *mint* one from nothing, but it did not have to: the capability and revocation messages
were byte-identical, so a capability the relay receives on every admit replayed as a revocation.
The sentence below is now true, and is true *because* of the domain tags F4 added.

The relay cannot forge a revocation (master-signed) but *could* withhold one — so **Kr rotation
(GR-7, landed)** is the cryptographic backstop: on removal the owner mints `Kr′`, re-wraps it to the
*remaining* members only (a re-key drop on the grant mailbox the remaining members auto-adopt via
the GR-6 poll), and writes all future content under `Kr′`. A withheld revocation now buys the
attacker no *new* content — the removed member holds only the retired `Kr`, which decrypts only
pre-removal blocks (and a colluding relay could have served those before removal regardless). The
revocation list is durable (survives restart), closing the in-memory-wipe re-admit window.
Non-destructive rotation rests on the GR-7 **retained keyring** (`shared-realm-membership-grant.md`
§13.4): each member persists prior `Kr`'s as `RetainedRealmKey` rows (the old key wrapped to their
A13 — House Rule #1, only the wrapped form leaves the device) and the decrypt path trial-decrypts
across them, so the ADR-0017 "no bulk re-encryption of unchanged files" invariant holds without
orphaning pre-rotation content. The retired `Kr` is A1-class personal-realm state (like the A26
private key), synced only across the member's own devices.
⚠⚠ **Why the re-key is auto-applied without user consent, when D4 says an inbound grant needs
explicit accept.** The remaining members adopt `Kr′` *silently* — no quarantine card, no prompt —
and that is a deliberate exception to the explicit-consent decision, not an oversight. It is safe
because of the gate, and only because of the gate: `pollPendingGrants` auto-applies **only when the
device already holds a `SharedRealmMembership` for that `realmId`**. A payload for a realm we are
not already in still quarantines. So a re-key **cannot inject a new realm** — the worst a forged or
misdirected re-key can do is hand us a key for something we already have, and the blob is in any
case sealed to our A13 and addressed via the handshake-derived mailbox, so an attacker who can
neither seal to our identity key nor derive that address cannot place one at all. The consent D4
protects is consent to *join* something, and a re-key asks for nothing new. Prompting instead would
be strictly worse: rotation is triggered by someone *else's* removal, so a member would face a
dialogue about an event they did not cause and cannot evaluate, and declining would silently strand
them on a retired key. See `shared-realm-membership-grant.md` §14.2.

Residual: **Low** — the removed member is cryptographically cut off from post-rotation content; the
only surviving exposure is pre-removal ciphertext they could already read, bounded by the
removal-then-rotation sequence.

**T-GR-03. Forged capability / device binding admits an attacker device.**
Actors: AD2–AD5.
Defense: the relay verifies the two-link Q30 chain against the `realm → masterPublicKey` binding
it already stores from genesis — the capability must be signed by the realm master, the device
binding by the capability's member authority, for the same realm. A forgery requires the realm
master key (A1, game-over for worse reasons) or the member-authority private key (A26 — admits
only that one realm, until revoked). Admission is unauthenticated-by-JWT by design (the chain is
the capability, the `/sync/admit-device` posture), so no JWT forgery surface is added.
Residual: **Low.**

**T-GR-04. Grant-mailbox flooding / grant griefing.**
Actors: AD5 (an authenticated but malicious grantor).
Defense: grant drops are JWT-authenticated and per-grantor capped
([`RelayConfig.maxOpenGrantsPerOwner`]); sealed blobs are size-capped (8 KiB); the address is
not guessable (HKDF of a secret), so a third party cannot squat a victim's slot.
Residual: **Low / accepted** (relay DoS in general is §10 territory).

**T-GR-05. Relay correlation of member identities.**
Actors: AD4, AD5.
What could leak: a co-membership graph linking a member's personal `ownerId` to the realms they
join. Defeated **structurally** by A26 — the relay sees only a realm-local authority public key
and realm-local device ids (`deviceId'`), never the member's personal `ownerId`, on the
admit/JWT path. Residual: pickup/admit *timing* + the grantor's IP, identical to the existing
invite mailbox.
Residual: **Accepted, documented** — consistent with the A6 metadata posture and the surface #10
co-membership residual.

**T-GR-06. Malicious member corrupts or floods realm state.**
Actors: a granted member (every member is read-write in v1; no permission tiers — §1 non-goal).
Defense: writes are attributable via the Q25 `Member` records; removal is capability revocation +
Kr rotation (Q26). Accepted v1 residual — identical to the trust placed in any write-capable
member of a shared realm (the surface #9 model, unchanged); permission tiers are the deferred
mitigation.
Residual: **Accepted, documented.**

**T-GR-07. A capability signature replayed as a revocation (cross-protocol message confusion).**
*Added by F4, 2026-08-21 — [`admission-revocation-names-the-capability.md`](../proposals/admission-revocation-names-the-capability.md) §7f.*
Actors: **AD5 (the relay)** primarily; also the member themselves. Assets: A26. Invariants: I5
(revocation).
`MembershipCapability` and `MembershipRevocation` were both bare concatenations of the same four
fields in the same order, signed by the same key (A1): `realmId ‖ memberAuthorityPub ‖ epoch ‖
<timestamp>`. With `epoch` pinned to 0 at every mint site, a capability with `issuedAt = T` was
**byte-identical** to a revocation with `revokedAt = T`. Its master signature therefore verified as
a revocation of its own holder — no collision search, no attacker-chosen field, just a relabelling.
The relay receives a capability in the body of **every** `POST /sync/realm-member/admit`, so it
could evict any member of any realm from material it legitimately holds. That inverts the House
Rule #1 posture for this surface: the relay is meant to *verify* master signatures and never to
author one.
⚠ Bounded to eviction (denial), not disclosure or admission: the forged record only ever names an
authority key the attacker already saw, and grants no read access. Not cross-member either —
`MemberAuthorityKey` carries the A26 *private* key, so it syncs across one member's own devices,
never to another member.
Defense: **domain-separation tags** on all three membership messages
(`tenshu-member-{cap,bind,revoke}-v1`, now `-v2` and length-prefixed since §7d), making the shapes
unforgeably distinct. Pinned by
`MembershipMessageConfusionTest`, which constructs the replay and asserts both the relay's
`/revoke` authorisation check and `MembershipChainVerifier.isRevoked` refuse it.
⚠ Latent, never exercised: production held **0** `member_revocation` and **0** `member_admission`
rows when the fix shipped, which is also why the cutover carries no dual-accept window for the
untagged form (`RELAY_PROTOCOL_VERSION` 1 → 2).
Residual: **Low.**

Invariants I1–I9 unchanged by this group. House Rule #1 audit: every relay-visible field of the
`/sync/grant/*` and `/sync/realm-member/*` surface is routing metadata, a public key, sealed
ciphertext, or a master-signed token; no decrypt code lands server-side, and Kr never appears in
any form. ⚠ F4 tightened the *authoring* half of that audit: a relay-visible master-signed token
must also be unusable as a **different** master-signed token, which is what the domain tags now
guarantee for this surface (T-GR-07).

### 8.15 Commerce↔relay entitlement bridge

Threats against the commerce↔relay entitlement bridge ([`commerce-billing-plane.md`](../proposals/commerce-billing-plane.md);
assets A28/A29/A30, surfaces #13 + #15, invariant I-CB-1; T-CB-01–07). The bridge is a **signed entitlement** the
relay verifies as one more credential it doesn't issue (D2) — no roster, no commerce callback on
the hot path. Two carriers, both A29-signed: the synced `Entitlement` **record** (on-device
verify-on-merge, landed earlier) and the bearer **token** (relay-verified; the §8 Phase 1 slice).
**As-built scope note:** Phase 1 *verified* a presented token (signature + expiry) but gated
nothing. **§8 Phase 2a (write-gating) has now landed** (`ENTITLEMENT_ENFORCE`, opt-in): the relay
rejects a *write* (`/sync/push`, content-blob `PUT`, share + grant drops) that lacks a valid, live,
**owner-bound** token — `402 entitlement_required` / `entitlement_invalid`, `403
entitlement_owner_mismatch` — while reads stay ungated (§9 Q5). The flag defaults off and is a no-op
without a configured issuer key. **§8 Phase 2b (per-owner content-byte quota) has also landed:** when
enforcing, a content-blob `PUT` is metered against the token's `quotaBytes` — a new chunk that would
push the owner's stored ciphertext total over the tier's quota is rejected `507 over_quota`, computed
from the content store itself (a re-PUT of an already-stored ref never re-counts, and the retention GC
frees the quota as blobs age out). Scoped to the content plane (the durable file bytes the quota is
sold against); mailbox-bytes and share-chunk quotas remain follow-ups, and `/sync/push` is
deliberately *not* byte-rejected (it would stall CRDT convergence). **§8 Phase 2c (client token-fetch
+ presentation) has landed too:** the client pulls a token from `:commerce-service`, caches it off the
hot path, and presents it on the relay's gated *writes* via `X-Entitlement` (personal realm only;
shared-realm writes stay unentitled to preserve the Q30 member↔owner unlinkability). With a client now
presenting tokens **and** the quota in place, a deployment can flip `ENTITLEMENT_ENFORCE=true` to make
enforcement live. The mock/unconfigured default presents no token (the issuer seed is
`:commerce-service`-only, so no in-process minting) and an unconfigured relay still ignores the header
— both unaffected. Residuals below are written for the landed write-gating + quota + token
presentation (House Rule #2, per-phase). **Track B2 (`second-device-entitlement-enforcement.md`
option 4, landed) — the relay now accepts a second, *proof* credential on a separate header:** a
paired second device inherits the realm (and thus the A29-signed `Entitlement` record) but no
commerce session, so it can't fetch a token. Rather than overload `X-Entitlement`, the relay adds a
**distinct `X-Entitlement-Proof` header** carrying a generic, self-describing `<type>.v<n>:<payload>`
*entitlement proof* it verifies itself (zero-trust of commerce liveness). `X-Entitlement` stays
**single-purpose** — a bearer token, checked strictly, never re-interpreted — while the proof header
is a general channel whose **v1 type is `signed-record.v1`** (the synced record; `RelayEntitlementVerifier`
verifies the same A29 signature over `EntitlementSignature.message(...)`, requires the record's
`ownerId` == the JWT-authenticated owner, and checks `now ∈ [validFrom, validUntil]`; any failure —
incl. an unknown proof type — → `402 entitlement_invalid`). Future proof sources (e.g. a Rei on-chain
purchase proof) slot in as new registered proof *types*, additive and without touching the token
path. The token path is unchanged and takes precedence when both headers are present, so the residual
this introduces is T-CB-08 below. **Commerce-plane account store + Stripe
([`commerce-accounts-and-sessions.md`](../proposals/commerce-accounts-and-sessions.md)):** A28 is now
a persisted PII store (see A28 above), reached via the account/session/consent endpoints and the
Stripe rail (surface #13); the internal admin console is surface #15; webhook forgery is **T-CB-07**.
None of this crosses into the data plane — the relay still learns only the signed token, never email,
`stripe_customer_id`, or an invoice (House Rule #1; I-CB-1 holds, A30 the documented residual).
**A third A29-signed carrier rides this same bridge (2026-08-04):** the **account-closure record**
(`retention-quota-model.md` Face A / D4, delivered to `POST /sync/account/closure` — surface #13).
It is the same verifier posture as the token, but it authorises **deletion** rather than a write, so
it is held behind two independent deploy flags (`ACCOUNT_CLOSURE_RELAY_URL` on the commerce side,
`ACCOUNT_CLOSURE_RECLAIM` on the relay side) and, unlike the token, its `ownerId` is **mandatory** —
the recorded D3/A30 exception under T-R-09.
⚠ **Live since 2026-10-24:** both flags are ON in production (maintainer decision, ahead of ⚖️ counsel's
review of the retention text), so a valid closure record past its 30-day grace now deletes that
owner's content. Production then held one account and none closed. Commerce backdates the record's
`iat` by 30 s to absorb its own clock running fast; the verifier stays strict (no `iat` leeway), and
grace runs from the recorded `closed` claim, so the backdate cannot bring a deletion forward.

**T-CB-01. Forged entitlement token / record.**
Actors: AD2–AD5 (anyone presenting a credential), AD5 (a relay tempted to fabricate "paid").
Assets: A29. Invariants: I-CB-1 (not I1 — see below).
An attacker mints a token/record asserting a tier they didn't buy.
Defense: every entitlement is **EdDSA-signed by A29**; the relay verifies against the pinned issuer
public key and devices pin `COMMERCE_PUBLIC_KEY`. The token decoder is **algorithm-pinned** (header
must be `{"alg":"EdDSA"}` — `alg:none` and confusion variants are rejected before the verifier runs),
expiry is enforced (`iat ≤ now < exp`), and an unknown `kid` is a typed `UnknownKey`, not a silent
pass. Forgery therefore requires the A29 **private** seed (a commerce secret, never on the relay or
a device).
Residual: **Confidentiality unaffected** — a forged entitlement grants at most write-quota the relay
would honour; it **cannot decrypt anything** (I1 is independent of A29). Blast radius of an A29
compromise is billing/DoS, mitigated by `keyId` rotation (relay holds current+previous). **Low** for
confidentiality; **Medium/accepted** for billing integrity until KMS custody + rotation land.

**T-CB-02. Token replay across owners.**
Actors: AD2–AD5. Assets: A30. Invariants: I-CB-1.
A leaked/observed token is presented under a different realm to borrow its tier.
Defense: the minimal-variant token **binds `ownerId`** (`sub`). **As-built (§8 Phase 2a):** the
relay's `requireEntitlement` owner-binding check is now **live and load-bearing** — a write whose
token `sub` ≠ the request's authenticated `ownerId` is rejected `403 entitlement_owner_mismatch`
(`EntitlementEnforcementTest`). The check sits on the *relay*, not in the token verifier (which
never inspects `ownerId`), so the strong/blinded variant — whose tokens carry no `sub` — verifies
and passes through the identical path. Short TTL (24–72 h) bounds the replay window independently.
Residual: **Low.** A token only borrows write-quota (no confidentiality — I1 is independent of the
bridge); the binding + expiry close cross-owner reuse, and the blinded variant carries no `sub` to
steal at all.

**T-CB-03. Commerce-plane PII breach.**
Actors: AD3–AD5 (commerce-plane compromise). Assets: A28. Invariants: I-CB-1.
The commerce store (email, processor IDs, invoices) leaks.
Defense: blast radius is bounded by **I-CB-1** + the merchant-of-record-light footprint (D4 — store
an email + processor token + entitlement + statutory invoices, not card/KYC data). Critically, a
commerce breach **cannot reach the data plane**: the relay can't identify a realm from an account
(strong variant) and can't read content in any variant — E2E erasure is the user deleting their own
keys (a *feature* for the DPA, §5).
Residual: **Minimal variant:** the `account↔ownerId` link (A30) is exposed → see T-CB-04.
**Strong variant:** no realm link leaks. Documented.

**T-CB-04. Correlation via the `account↔ownerId` link (minimal-variant residual).**
Actors: AD4 (subpoena/breach reaching both planes), AD5+commerce collusion. Assets: A30.
Invariants: I-CB-1 (minimal).
Joining both planes walks person → realm → ciphertext metadata (blob sizes, timing, device count).
Defense: **structurally eliminated by the strong variant** (blinded tokens, Q1: blind RSA) — the
target design and what I-CB-1 is written against. v1 may ship the minimal variant **iff** this is
called out (here) and the token format is blinding-ready — which it is (`ownerId` optional, verifier
never requires it), so the upgrade is issuance-only.
Residual: **Accepted, documented** for as long as the minimal variant ships — the single residual
de-anonymisation vector, removed by the blinding upgrade.
*Amendment (`relay-metadata-minimisation.md` F13 / D11, 2026-10-28): what the relay learns from
`sub`, and why it stays.* Commerce mints `sub = ownerId` (`commerce-service/.../TokenRouting.kt:48`),
and the token's `tier` and `quota` claims ride every gated write as `X-Entitlement`. So, with
enforcement on, the relay learns **per write** the owner's tier (paid or free, and which plan) and
quota in bytes. It persists none of it; a 402/403 logs the owner id (8-char prefix), not the claims.
That is the minimal variant's documented cost, not a new leak. **Decision (D11): keep `sub`.**
Blinding would remove T-CB-02's owner binding — the relay's only anti-sharing check, which rejects a
token whose `sub` ≠ the authenticated owner with `403 entitlement_owner_mismatch`
(`routing/EntitlementEnforcement.kt:142-153`); a token with no `sub` passes for any realm within
its TTL. Since the entitlement is the relay's only notion of "paid" (billing D5), that check is
what stops one subscription funding many realms. The strong variant is therefore a trade-off rather
than a pending upgrade — adopting it would first need a replacement for the binding — and this
residual stays **Accepted, documented**.

**T-CB-05. Commerce signing-key (A29) compromise.**
Actors: AD3–AD5. Assets: A29. Invariants: I-CB-1.
The A29 private seed leaks → an attacker mints arbitrary entitlements.
Defense: the key is a commerce secret only (env-supplied seed v1; KMS/HSM custody is the deferred
item); the relay/devices hold only the public half. Impact is **billing/DoS, not confidentiality**
(T-CB-01). Recovery is `keyId`-tagged rotation: issue under a new `kid`, the relay adds the new
public key + drops the old after the max token TTL.
Residual: **Medium/accepted** until KMS custody + an automated rotation runbook land; **no
confidentiality impact** at any point.

**T-CB-06. Processor as sub-processor.**
Actors: the payment processor (Stripe / App Store / Play) as a trusted third party. Assets: A28.
A processor is necessarily trusted with billing identity (D4 deliberately pushes card/KYC scope to
them).
Defense: out of the cryptographic boundary — addressed by contract/DPA, not protocol. None of the
processor's data links to the data plane (I-CB-1); the processor never sees realm/ciphertext.
Residual: **Accepted** — inherent to taking payment; bounded by I-CB-1 + §7.

**T-CB-07. Stripe webhook forgery.**
Actors: AD2–AD4 (anyone who can POST to the public webhook URL). Assets: A28, A29 (indirectly — a
forged webhook that mints an entitlement launders into an A29-signed record). Invariants: I-CB-1.
A forged `invoice.paid` / `checkout.session.completed` would fabricate a paid account, grant a paid
entitlement, and write a bogus invoice — a **billing/DoS** problem (never a confidentiality break;
I1 is independent).
Defense: **every webhook is Stripe-signature-verified before any state change** —
`HMAC-SHA256("$t.$rawBody", webhook_secret)` compared constant-time against the `Stripe-Signature`
`v1`, with a 5-minute timestamp tolerance bounding captured-signature replay
(`StripeSignature.verify`, `commerce-accounts-and-sessions.md` §5.3/§6). The signing secret is a
commerce secret; the service refuses to start with a Stripe key but no webhook secret (config guard).
Event ids are idempotency-checked so a replayed *valid* event doesn't double-grant.
Residual: **Low** — a leaked webhook secret forges entitlements (billing/DoS, rotate the secret); a
mid-processing crash won't reprocess on Stripe's retry (v1 limit — events are replayable from the
Stripe dashboard). No confidentiality impact at any point.

**T-CB-08. Coarser forced-revocation window via the `signed-record.v1` proof (Track B2 residual).**
Actors: AD2 (a force-revoked owner — chargeback / fraud flag — still wanting storage). Assets: A30
(the record binds `ownerId`). Invariants: I-CB-1.
Because the relay accepts the long-lived, `ownerId`-bound synced `Entitlement` record as a write
credential (the `signed-record.v1` proof type on `X-Entitlement-Proof`), a **force-revoked** account
keeps writing until the record's `validUntil` — the relay is zero-trust and never re-asks commerce,
so it honours the signed record for its full lifetime, whatever commerce decided. With the token
alone the window is the short TTL (~72 h); the record's is potentially weeks, and a non-cooperating
client presents whichever accepted credential lives **longest** (`second-device-entitlement-enforcement.md`
§1/§6, downside 1). This is a property of the *proof channel* generally (a self-verifying, longer-lived
credential), not of the header split — any future proof type (e.g. a Rei on-chain proof) inherits the
same "verify signature + window, never call back" latency profile.
Defense: forced early revocation is **rare** and the damage is **bounded** — a flagged account keeps
consuming write-quota/storage for the window, **never decryption** (I1 is independent of the bridge).
**Now mitigated (record TTL + renewal, `EntitlementWriter`):** the record TTL is short by default
(7 days, `ENTITLEMENT_TTL_HOURS`) and the client **renews** a good-standing record before it expires
(the `initEntitlementWriter` tick re-mints once the record passes the halfway point of its window),
so a *revoked* account — for which commerce refuses to re-mint — lapses within the TTL rather than
living for weeks. The window is thus bounded by the TTL (~renewal cadence), tunable down toward the
token's ~72 h if forced-revocation latency becomes load-bearing (cost: a shorter offline-tolerance
window before a peer's storage lapses). Dropping the hybrid to token-only remains the escape hatch.
Residual: **Low/accepted** for v1 (redemption-code / early accounts, no live subscriptions):
revocation latency is bounded by the record TTL (7 days default), not weeks; **no confidentiality impact** at any point. No
new relay visibility — the relay already authenticates `ownerId` via the device JWT; no key-custody
change; House Rule #1 preserved.

*Amendment (free-grant write-death fix, 2026-09-07): an expired record floors to Free rather
than failing closed.* The relay's `signed-record.v1` verifier no longer returns `Invalid` past
`validUntil`; it returns `Valid` metered at `StorageTiers.FREE_BYTES`, and the client presents an
expired record instead of withholding it. **Why:** the mitigation recorded above — "the client
renews a good-standing record before it expires" — assumes a renewal path, and the identity-light
**redeem** path has none. `initEntitlementWriter` derives its mint intent from
`publisherClient.account`, so with no commerce session the intent is `null` and `mintEntitlement`
is never called: for a redeemed free grant, *expired* is the steady state, not an anomaly. The
observed effect on production 2026-09-07 was an owner refused **every** content write for hours
(8010 × 402 against 8534 × 200) while metadata sync continued — a Drive listing files whose bytes
were nowhere — with the relay reporting `entitlement_required` ("the client attached nothing"),
which is also what a device with no record at all reports. Expiry was therefore invisible as a
distinct state.
**Residual delta:** a **force-revoked** owner (AD2) no longer becomes write-dead when the record
lapses — they retain **free-tier** write capacity (5 GiB) indefinitely. This is judged **Low**: the
free tier is available to anyone who creates an owner, so denying it to a revoked owner buys no
confidentiality and little cost — a determined AD2 provisions a new realm and is entitled to the
same 5 GiB. What revocation must remove is **paid** quota, and that is unchanged and now
*immediate*: the one-sided skew leeway still does not extend the end bound, so a lapsed
`tenshu.drive.basic` record drops from 50 GiB to 5 GiB at `validUntil + 1 ms`
(`RelayEntitlementVerifierTest.expiryGetsNoLeeway_dropsToFreeImmediately`). Reclamation of an
over-quota owner's stored content is unaffected (that is the account-lifecycle mechanism keyed on
`ownerId`, still unbuilt).
**Unchanged:** signature verification (a forged or wrong-issuer record is still `Invalid`), owner
binding, the **not-yet-valid** rejection, the bearer-token path's own expiry, and metering — an
expired record is metered, so the second-device unmetered-storage bypass D8 closes stays closed.
No new relay visibility, no wire-shape change, House Rule #1 preserved.

*Amendment (sync-retention-horizons.md RH-0, 2026-09-07): the relay retains each device's
consumed-log cursor.* A new `device_cursor(owner_id, device_id, cursor)` table records the `?since=`
value a device sends on every pull, and `ChangesResponse` / `SyncLogResponse` gain a `peerCursors`
list echoing every *other* device's.

⭐ **No new information reaches the relay.** `since` is how pagination works — `getChanges(ownerId,
since)` has always been handed it on every request — and the relay already persists per-device state
of exactly this shape in `hlc_tip`, keyed identically. What is relay-visible is unchanged: device
counts and timing, per A6's existing scope. A cursor is a position in the owner's *own* log and says
nothing about content; it is metadata of the kind already accepted, and House Rule #1 is untouched
because nothing is decrypted to produce it.

⚠ **What does change is retention, and that is the honest cost.** A value the relay saw in passing
is now held at rest, so it appears in backups and in whatever a compromised relay yields. The
marginal exposure is small — the relay already stores a per-device **wall-clock tip**, which is
arguably the more revealing of the two — but it is not zero. Accepted deliberately: the cursor is
what lets the reclamation barrier stop comparing clocks (RH-1), which removes both a skew allowance
and a quiet-realm stall that leaves a realm unable to reclaim storage at all.

⚠ **Recorded monotonically**, both in SQLite (`MAX(...)` upsert) and in the client's
`RealmConvergence`. A client may legitimately send a lower `since` — a re-read, a restarted pull —
and a regression would otherwise read as a peer *un-consuming* the log. Under RH-1 that would be
authority to delete, so it must be impossible rather than unlikely.

*Amendment (sync-retention-horizons.md RH-1b, 2026-09-08): the barrier now consumes the cursor, and
Q1 is answered with an argument rather than a signature.*
⚠ *Corrected 2026-10-28: nothing consumes the cursor any more.* CR-2 B6 deleted the
client's `ReclamationBarrier` and `RealmConvergence` (relay-side content reclamation replaced the
convergence barrier), so the "barrier" this amendment, RH-0's and RH-1a's speak of no longer exists.
The relay still records `device_cursor` from `/sync/log` and still echoes it as `peerCursors`, but no
client reads that field. The unsigned-cursor argument below is therefore moot rather than wrong: an
inflated cursor is authority over nothing. And the retention cost RH-0 accepted is now bounded (F11): an applied `Device` tombstone (and a shared-realm member removal) deletes the revoked device's
`hlc_tip` and `device_cursor` in the same transaction as its registration
(`auth/DeviceRevocation.kt`), and a revoked device's late `/changes` or `/log` cannot re-create them;
account closure erases both (F12).

*Amendment (pre-reset one-way doors, 2026-10-29): the cursor is no longer retained or served.* With
no reader left, RH-0's accepted retention cost bought nothing, so it is withdrawn before the
production reset: `/sync/log` no longer records `since`, the `device_cursor` table is dropped (relay
schema v5, `RelayDatabase.kt`), and `peerCursors` is removed from `ChangesResponse` and
`SyncLogResponse` along with the `PeerCursor` type. The relay still *sees* `since` on every pull —
pagination needs it — but holds it only for the request. What remains at rest per device is the
signed `hlc_tip`, unchanged. The RH-0, RH-1a and RH-1b paragraphs here are history. ⚠ A future
consumer (e.g. `content-plane-cr2-quorumless.md` Q5's relay-measured catch-up) re-adds the table
**with** its reader and re-amends this section; the wire field would then be a new response field,
which after the reset is a `RELAY_PROTOCOL_VERSION` and `SyncService` reshape, not a free append.

⚠ **The cursor is unsigned**, unlike `SignedHlcTip`. It is a request parameter, so an admitted
device can report a cursor higher than it has truly consumed. Lying *downward* only delays its own
realm; lying **upward** clears the barrier early and causes **another** device to delete bytes the
liar still references.

⭐ **No signature is required, because the liar is already inside the trust boundary.** Two facts
close it:

- **A device can only report its own cursor.** The relay records `(ownerId, deviceId)` from the
  *authenticated session*, never from the request body, so device A cannot move device B's cursor.
  The forgeable claim is therefore always "I am further ahead than I am".
- **An admitted device can already destroy the same data far more directly.** It holds the realm
  keys (A4: first-party apps are realm co-owners), so it can delete any file outright, or push a
  tombstone for it. Lying about a cursor to make *another* device drop bytes is a strictly weaker
  and slower path to an outcome already available to it.

⚠ **The revoked-but-not-yet-removed device** is the one actor the doc's §3.2 raised against this,
and it does not survive contact with the mechanism. Revocation **deletes the `Device` row**
(`RevokeDeviceImplementation`), so the device drops out of `admittedDeviceIds` and the barrier stops
waiting on it at all — a cursor it reports, honest or forged, is no longer read by anyone. And the
relay revokes its registration on the same tombstone (T-K-02), so it cannot push one. In the window
*before* revocation propagates it is still an admitted co-owner holding the realm keys, which is
precisely the case above, not a new one.

⇒ Signing the cursor would raise the cost of an attack that a realm member never needs to mount.
The residual is the same one A4 already accepts: **a device admitted to a realm is trusted with that
realm's data.** ⚠ This argument does **not** survive a future in which a realm has members who are
*not* co-owners — a read-only or least-privilege membership would make the cursor a privilege
boundary, and would owe the signature. `shared-realm-membership-grant.md` is where that would first
appear.

⚠ Unchanged and still load-bearing: an **absent** cursor reads as "not caught up", never as
convergence, so nothing is gained by withholding one.
*Amendment (sync-retention-horizons.md RH-1a, 2026-09-07): `/sync/push-tombstone` returns the
sequence it assigns, and `/sync/changes` stops recording a cursor.* Two changes, one of which
**corrects the amendment above**.

⭐ **The returned sequence is not new information.** `relay_seq` already reaches clients: it is what
`nextCursor` carries on every pull, and what RH-0's `peerCursors` echoes. The counter is **global
rather than per-owner**, so the gaps between an owner's own seqs already disclose that other traffic
occurred — a volume side-channel that predates this change and is unaltered by it. What the client
gains is the seq of *its own* push, which it would observe on its next pull regardless. The response
body was the literal string `OK`; the client checks only `status.isSuccess()` and ignores the body,
so this is compatible in both directions. House Rule #1 is untouched: the value is assigned by an
append, not derived from plaintext.

⚠⚠ **The correction matters more than the addition.** RH-0 recorded `?since=` from **both**
`/sync/changes` and `/sync/log` into one row upserted with `MAX(...)`. Those two routes do not carry
the same quantity: `/log` carries `min(blockCursor, tombstoneCursor)` while `/changes` carries the
**block lane's cursor alone**, and the lanes share the relay's counter without sharing its density —
measured on-device, 500 blocks spanned ~56 000 seq against 500 tombstones' 500. `MAX(...)` therefore
stored an **over-estimate** of how far a device had consumed.

⚠⚠ That is unsafe in the one direction that matters. The barrier's question is *"has every peer
consumed past this tombstone?"*, and an over-stated cursor answers **yes early** — authority to
delete relay bytes another device still references. It was inert while nothing consumed the value,
which is why RH-0 shipped it, but RH-1b would have consumed it. `/log` is now the sole recorder,
because it is the only route whose cursor is conservative for that question. A device that pulls
only through the two-lane fallback therefore records no cursor at all, and **absence must be read as
"not caught up", never as "caught up"** — an obligation RH-1b inherits.

⚠ The RH-0 note above that `MAX(...)` makes a regression impossible remains true and remains
necessary; it was simply not *sufficient*, because monotonicity in the wrong quantity is still the
wrong quantity.

**Residual: Low.** No new relay visibility, no key-custody change, no decryption; one new stored
metadata column and one new response field. ⚠ The cursor-source correction moves the *stored* value
downward, i.e. strictly toward the conservative side of the barrier's decision.

**T-CB-09. Browser-origin access to the commerce API (`WEB_APP_ORIGIN`).**
Actors: AD2–AD4 (any web page a customer visits, plus anyone who can POST directly). Assets: A28
(the account/PII store reached through `/commerce/account/*`), and the outbound mail budget.
Invariants: I-CB-1.
Until the apex grew an account surface, every caller of `:commerce-service` was a native HTTP client
— not subject to the same-origin policy — so the service carried **no CORS at all** and needed none.
Serving a browser changes that: `POST /commerce/account/register` is deliberately unauthenticated
(the verified email *is* the sybil gate) and sends real mail, so a permissive policy would let any
page on the internet drive our outbound mail through a visitor's browser, and would expose
`/verify` to cross-site guessing carried on the victim's network position.
Defense: CORS is **off unless `WEB_APP_ORIGIN` is set**, and when set it admits **exactly that one
origin** — never `anyHost()`, never a pattern. That is enforced at startup, not only by
convention: Ktor's `allowHost("*")` *is* `anyHost()`, so `CommerceConfig.validate()` refuses any
`WEB_APP_ORIGIN` that is not one bare lower-case origin (https outside dev mode).
`allowCredentials` stays **false**: the commerce
session token rides in the JSON body, never a cookie, so there is no ambient authority for a
cross-site request to borrow and a stolen `Origin` header alone buys nothing. The rate limiter
(CE-2) is unchanged and still governs both budgets per-IP and per-email, so browser reachability
does not widen the mail-bomb surface beyond what a direct POST already had. Nothing about CORS gates
a *server-side* check — every route keeps its own authentication, and CORS is understood here as
protection for the customer's browser, not for us.
Residual: **Low** — CORS is a browser-enforced control, so it never was and is not now a defence
against a direct attacker (who simply POSTs); the substantive gates remain the rate limiter, the OTP,
and the session. The delta is that a *victim's browser* can no longer be used as the vehicle, and
only for deployments that opt in.

**T-CB-10. Post-checkout redirect as an open redirect.**
Actors: AD2–AD4. Assets: A28 (indirectly — a phished customer mid-purchase). Invariants: none.
The checkout flow hands Stripe a URL to send the customer back to. Accepting that URL **from the
request** would be an open redirect on an unauthenticated route, carrying a customer who has just
paid — an unusually good phishing position.
Defense: the browser return targets are **derived server-side** from `WEB_APP_ORIGIN`
(`WebReturnUrls`), never read from the request. The request carries only a `client` discriminator
(`Native` | `Web`) choosing between two server-held pairs; an unrecognised or absent value means
`Native`, so existing clients are unaffected. A `client=Web` checkout on a deployment with no web
origin configured is **refused before a Stripe session is created**, rather than redirecting a paid
customer to a `tenshu://` link no browser can follow.
Residual: **None beyond the trust already placed in the deployment's own configuration.**

**T-CB-11. Self-service account deletion (`/commerce/account/delete/*`, 2026-10-23).**
Actors: AD2–AD4 (anyone who can POST, or who holds a signed-in device). Assets: A28, A30, and — via
the closure path — the owner's durable content (T-R-09). Invariants: I-CB-1.
Three new things an attacker could reach for: an irreversible delete, an unauthenticated route that
sends mail to any address, and a closure that can now be produced by a user rather than an operator.
Defense:
- **Possession of the inbox is the proof, even with a session.** Both variants (`{sessionToken}` from
  the app, `{email}` from the public web page) require a fresh code mailed to the account's address,
  stored under a **purpose-bound key** (`account-deletion:<email>`), so a sign-in code cannot delete and
  a deletion code cannot sign in or re-bind an owner. A stolen or left-unlocked device is therefore not
  enough to delete. Codes are hashed, single-use, TTL- and attempt-bounded exactly as sign-in codes.
  ⚠⚠ The key spaces are disjoint **by construction**: `normaliseEmail` rejects any `:` and requires a
  strict single RFC 822 address, so no input to `/register` can normalise to `account-deletion:…` (or
  `erased:…`) and overwrite a victim's pending deletion code with a sign-in code.
- **No enumeration oracle.** The `{email}` request answers `202` and sends *a* mail whatever the
  address — the code, or a "no account uses this address" notice — so neither the status nor the SMTP
  round trip reveals existence; every `/confirm` refusal is one `401`. Both branches also do the same
  database work (one hashed-code upsert; an unknown address writes a fixed decoy key, never a row
  naming the stranger). Both routes spend `/register`'s
  and `/verify`'s budgets (CE-2), so the mail-bomb surface is the one `/register` already had.
- **A self-made closure cannot delete somebody else's files.** ⚠⚠ The closure sweep now **skips any
  `ownerId` still live-bound to an account that is not closed** (`closedAccountsWithOwners`). Without
  it, erasure opened a real hole: the address no longer finds the erased row, so the same person
  signing in again from the same device creates a *new* account bound to the *same* realm — and the
  erased account's closure record would have told the relay to reclaim the files the new account is
  using. The same guard covers a realm bound to two accounts under two emails (one closing does not
  reclaim what the other still holds). ⚠ Its flip side (**Low**): anyone who knows a realm's
  `ownerId` and can bind an open account to it — sign-up's `/verify` takes the `ownerId` from the
  request — can **hold back** that realm's deletion for as long as their account stays open. It
  delays a deletion, never causes one, and reveals nothing.
- **Erasure is terminal and does not resurrect.** `reopenAccount` is a no-op on an erased row, and the
  Stripe webhook resolves a known **customer** before the address, so a renewal or redelivered event
  for an erased customer is refused rather than creating a fresh account from the email on the event
  (which would re-collect the very address the person asked us to erase).
- **The relay learns nothing new.** Deletion reuses the existing closure record and its two deploy
  flags; content is reclaimed after the grace window **only** when both `ACCOUNT_CLOSURE_RELAY_URL` and
  `ACCOUNT_CLOSURE_RECLAIM` are set — both are, in production, since 2026-10-24. The A30 disclosure the closure record carries is
  unchanged — it happens once, for an account that is ending.
Residual: **Low.** A compromised *inbox* can delete the account — but it can already sign in, and
deletion is the weaker of the two harms to the data (content survives the grace window, during which
signing in again from a device keeps it). Erasure is pseudonymisation of the operational row, not
destruction of it: the processor's records and the statutory rows remain by design (A28 amendment).

**T-CB-12. Store-review sign-in — a static 6-digit code per unpublished review account (2026-10-24).**
Actors: AD2–AD4 (anyone who can POST to the account routes), and the app stores' review staff as
trusted holders of their store's address and code. Assets: A28 (the review accounts — ordinary
accounts on addresses we own), and indirectly the realm a reviewer binds (A30). Invariants: I-CB-1.
New: one review account per store — an **unpublished** address shaped `google-review-<suffix>@tenshu.app`
/ `apple-review-<suffix>@tenshu.app`, configured only in `deploy.env` — each accepts **its own** fixed
6-digit code (six digits because that is what every client's code field accepts) in place of a
mailed one-time code, for `/commerce/account/verify` and `/commerce/account/delete/confirm`
(`commerce-accounts-and-sessions.md` D13 / §9.8). It is a password per account, on the production
auth path, and it stays on (stores re-review live apps) — so the questions are whether it can reach
any other account, and how long a guesser needs.
Defense:
- **Off by default, never half on.** Enabled only when both `REVIEW_SIGNIN_ACCOUNTS` and
  `REVIEW_SIGNIN_HMAC_KEY` are set; `CommerceConfig.validate` refuses to start with one, with an entry
  whose address is not exactly what `normaliseEmail` returns (no wildcard, domain or display-name form
  exists to misconfigure), whose digest is not 64 hex characters (the code pasted in its place), with
  a repeated address or a repeated digest (two stores sharing a code), or a key under 32 characters.
  Refusals name positions, never values.
- ⚠⚠ **Exact membership, per-account codes.** The request address is normalised by the same
  `normaliseEmail` every route uses, then looked up exactly: `+suffix`, a trailing dot, a suffix
  domain, a sub-domain, a homoglyph and a truncated suffix are different strings and are refused
  (tested). Each address is checked only against its own digest, so one store's code never opens the
  other store's account (tested both ways). Every other address never reaches the review check, and
  the check refuses an unlisted address as a second wall.
- **No second way in, and no first way for anyone else.** A listed address is never mailed a code
  and never accepts a mailed one (sign-in or deletion), so its fixed code is its only credential; no
  other address ever accepts a review code.
- ⚠⚠ **A keyed digest on the box, never a code.** The box holds HMAC-SHA256 of each code under a
  box-side secret key, compared with `MessageDigest.isEqual`. A plain or salted SHA-256 would not do:
  six digits are 10⁶ candidates, so anyone holding an unkeyed digest — and a salt is not secret —
  recovers the code in well under a second. Keyed, a digest alone (a log, a pasted config, a
  screenshot) gives nothing. ⚠ The key and the digests sit side by side in `deploy.env`, so whoever
  reads that file can still brute-force the codes offline — but that reader already holds the issuer
  seed (A29) and the database password. The codes and the key are drawn from a CSPRNG on the
  maintainer's Mac and kept in the Keychain; the repository holds placeholders only, and no test or
  doc names a real review address. Nothing logs a code, the key, a digest or an address
  (`ReviewSignInRoutingTest.nothingSecretReachesTheLog`, `LogStatementPiiGuardTest`), and
  `CommerceConfig.toString` redacts them (`CommerceConfigToStringTest`).
- ⚠⚠ **A persisted lockout is the security bound.** 10 wrong codes on one account within a rolling
  24 hours refuse every attempt on it, the right code included (`429`), until the oldest of those
  failures ages out — shared by sign-in and the deletion confirm, on top of the per-email budgets.
  The failure stamps live in the store (`review-signin-failures:<address>`, unreachable by any address
  because `normaliseEmail` rejects `:`), so neither a restart nor an erasure resets them, and a
  correct code does not clear them. Arithmetic, per account and its own code: 10⁶ / 10 a day ≈ 274
  years to try every code; ≈ 3,650 guesses a year ≈ **0.37 % a year**. **And that is for an attacker
  who already knows the address**: the 50-bit random suffix is published nowhere, and the routes give
  no response that tells a review address from any other except the missing SMTP latency on
  `/register` — a membership test that needs the address to aim at.
- **Rotation ends sessions.** A session outlives the code that minted it, so at boot commerce
  compares each account's digest fingerprint (an HMAC of the digest under the key, kept in the
  `review-signin-fingerprints:` row) with the previous boot's and deletes every session of an
  account whose code changed, that is newly listed, or that is no longer configured (a disabled
  feature compares against an empty set). Rotating a code — periodically, or after a suspected
  store-side leak — therefore also signs out whoever used the old one (tested).
- **Same answers as everyone.** `/register` and `/delete/request` answer the ordinary `202` with the
  ordinary body and do the same database work; only the SMTP round trip is missing.
Residual: **Low.** (1) **The stores hold the credentials**: anyone at Google or Apple with access to
the review fields — or a breach of either — can sign in to that store's account, see its plan,
re-bind it to a realm of their own, and delete it; re-binding reaches no other realm's content
(I-CB-1; content is end-to-end encrypted with device-held keys). ⚠ **Nothing stops a review account
from buying**: reviewers must be able to test a purchase, so a plan or subscription can appear on
one, paid through Play Billing or Stripe checkout with whatever payment method the reviewer supplies.
The code enforces nothing about payments; the rotation routine checks the review accounts for
purchases and subscriptions. Rotate periodically and at once after a suspected leak
(`how-to/play-console-answers.md` §14). (2) **Online guessing** at ≈ 0.37 % per account per year,
once the address is known. (3) **Lockout as denial of service** needs the address, so it reduces to
(1); the remedy is clearing the row by hand, which also hands the guesser ten fresh tries. (4) A
reviewer's realm stays bound to the review account until the next sign-in re-binds it; nothing of
ours depends on it.

**T-CB-13. Online guessing of an emailed one-time code (2026-10-24).**
Actors: AD2–AD4 (anyone who knows a customer's address and can POST). Assets: A28 (the account: its
plan, billing actions, deletion), A30 (an `ownerId` re-bind). Invariants: I-CB-1.
A sign-in or deletion code is six digits (10⁶). The attacker requests codes for the victim's address
(each mails the victim) and guesses. ⚠ **Before (until 2026-10-24):** the bounds were the per-code
attempt burn (`OTP_MAX_ATTEMPTS`, 5), the per-email send budget (5 codes an hour) and the per-email
`/verify` budget (20 an hour) — i.e. **~20 guesses an hour, ~480 a day**, each against a fresh
uniform code: 480 / 10⁶ ≈ 0.048 % a day, **≈ 16 % a year** for an attacker patient enough to keep
mail-bombing the victim. **Now** (`commerce-accounts-and-sessions.md` D15 / §9.9):
Defense:
- **A persisted daily cap**: 20 failed code checks per address per rolling 24 hours
  (`OTP_FAILED_PER_EMAIL_PER_DAY`), shared by `/verify` and `/delete/confirm`, kept in the store so a
  restart does not reset it. Over it every attempt is `429`, the right code included, until the
  oldest failure ages out. 20 a day = 7,300 a year: **≈ 0.73 % a year**, and every few guesses still
  cost the attacker a mail to the victim's inbox, which is how a victim notices.
- **No oracle.** Every refusal counts — a wrong, expired or burned code **and no pending code at
  all** — so the cap's 429 says nothing about whether a code was issued, or (on the deletion route)
  whether an account exists (T-CB-11's no-enumeration property). The row is keyed by SHA-256 of the
  address so counting strangers does not store their addresses in the clear; erasure deletes it.
Residual: **Low.** (1) ≈ 0.73 % a year against a targeted account. (2) ⚠ **Denial of service**: anyone
who knows an address can keep its owner from signing in or deleting for a day with 20 wrong guesses,
indefinitely. Accepted: the account guards billing only — the owner's data stays usable and
end-to-end encrypted (D14) — and the alternative is the 16 % a year above. (3) The cap is per
address, not per source: a distributed attacker gets no more guesses, but cannot be told apart from
the victim either.

House Rule #1 audit: the relay's entitlement surface reads **one signed credential** (a token, or a
proof on the separate header) and a configured **public** key — no account data, no commerce callback, no decrypt code
server-side. A29's private
key and A28's PII never cross into the data plane. Invariants I1–I9 are unchanged by this group;
**I1 in particular is independent of the bridge** — an entitlement asserts write-quota, never
decryption capability. The new invariant is **I-CB-1**, whose minimal-variant carve-out (A30) is the
one documented residual, closed by the blinded-token upgrade.

### 8.16 App↔proxy EPC rendezvous

The relay surface ([`ios-proxy-architecture.md`](../proposals/ios-proxy-architecture.md) §5, Phase
3a) that brokers the External Proxy Connection between an iOS app and its proxy: a store-and-forward
mailbox carrying the EPC handshake + sealed `EpcSession` frames (asset A31, surface #14). It opens
the relay to connections from clients that are **not** realm devices, so the group below covers both
the channel's E2E guarantees (which the EPC protocol provides) and the new availability surface.

**T-RDV-01. Relay reads / correlates rendezvous traffic.**
Actors: AD5 (the relay, in the adversary set). Assets: A31; A5/A6 transitively (the operation
traffic the channel carries). Invariants: I1.
The relay sees every sealed frame, the session id, the directional sequence numbers, their timing,
and the proxy's `ownerId` binding on the session.
Defense: frames are opaque `EpcSession` ciphertext (XChaCha20-Poly1305) the relay has no key for;
the session id is a high-entropy HKDF of a seed the relay never sees; confidentiality + integrity
are end-to-end. No decrypt code server-side.
Residual: **Medium** — frame count/size/timing + the owner-binding are metadata, the exposure
already accepted for AD5 under T-T-07 / A6, extended by the per-session traffic dimension. It reveals
*that* an owner runs an EPC channel and its volume, never its content.

**T-RDV-02. Malicious relay MITMs the handshake.**
Actors: AD5. Assets: A31, A1-class data transitively. Invariants: I1.
The relay swaps the `ClientHello`/`ServerHello` ephemeral keys to terminate two channels and sit in
the middle.
Defense: the authenticated key exchange derives a 6-digit **SAS** from the shared secret; a
substituted key yields a divergent SAS on the app vs the proxy screen, which the user compares
before trusting the channel (whitepaper EPC measure 1 — no third party, including the relay,
trusted). Identical posture to device pairing T-P-01.
Residual: **Low** — gated on the user actually comparing the SAS (same consent dependency as
T-P-01 / T-P-05).

**T-RDV-03. Rendezvous flooding / DoS.**
Actors: AD2 (network), AD5, anonymous app-side clients. Assets: A31 (availability).
EPC opens the relay to posts/polls from clients that are **not** realm devices; an attacker floods
the mailbox surface to exhaust storage or starve legitimate sessions.
Defense: a rendezvous *session* can only be provisioned by a realm device under its **device-key
JWT** (binding it to an `ownerId`), with a per-owner open-session cap
([`RelayConfig.maxOpenRendezvousSessionsPerOwner`]); per-direction frame caps, a short (15-minute)
TTL, and rate limiting bound anonymous app-side traffic; session ids are high-entropy so an attacker
cannot target a live session without the out-of-band seed.
Residual: **Medium** — DoS only (never content). Per O2 (resolved 2026-06-16: **no** edge App
Attest, to keep the relay vendor-neutral with no Apple dependency) this is the accepted residual,
mitigated by JWT-gated provisioning + caps + rate-limit. Cheap to drop to Low later (a one-line
edge-attest add) if abuse is observed.

**T-RDV-04. Session-id guessing / capability leak.**
Actors: AD2–AD4 (channel compromise), AD5. Assets: A31.
An attacker who guesses or intercepts a session id can inject/drain frames for that session.
Defense: the id is an HKDF of a high-entropy pairing seed (infeasible to guess); knowledge of the id
is the capability, the accepted `/sync/invite` + `/sync/grant` posture; the short TTL bounds the
window; **injected/drained frames still fail the end-to-end `EpcSession` AEAD + sequence checks**
(T-RDV-05), so a leaked id yields DoS at most, never content or forgery.
Residual: **Low**.

**T-RDV-05. Frame replay / injection / reorder by the relay or an id holder.**
Actors: AD5, AD4. Assets: A31, A5. Invariants: I1, I2.
The relay (or anyone with the id) replays, drops, reorders, or injects frames.
Defense: the `EpcSession` binds a strictly-increasing sequence as AEAD associated data per direction
— replays and reorders are rejected, injected/tampered frames fail authentication, and neither party
holds a key the relay has. Dropping frames is a liveness (not integrity) failure, surfaced as a
transport timeout.
Residual: **Low**.

**T-RDV-06. Cross-session frame splicing.**
Actors: AD5. Assets: A31. Invariants: I1.
The relay moves frames from one EPC session's mailbox into another's.
Defense: each session has its own pairing-seed-derived id and its own directional AEAD keys; a
spliced frame fails the recipient session's AEAD. The relay cannot manufacture a frame that
authenticates under a session it has no key for.
Residual: **Low**.

Invariants I1–I9 unchanged by this group — it *rests on* I1/I2 (the EPC channel provides them
end-to-end) and adds an availability concern (T-RDV-03) bounded by admission control, consistent with
the relay-availability posture already accepted. House Rule #1 audit: every relay-visible field of
the `/sync/epc` surface is routing metadata (the HKDF session id, a direction tag, a sequence number,
a TTL), the proxy's `ownerId` session-binding (already relay-visible via A6), or an opaque sealed
`EpcSession` frame. No decrypt code lands server-side; the pairing seed and the session keys never
reach the relay in any form. The relay is a byte-pipe + addressed queue, transparent to the EPC
payload.

### 8.17 Delegated device admission

Threats introduced by the A33 delegated-admission capability
(`delegated-device-admission-authority.md` §4/§5, surface #16). Admission authority moves from
"A1-holders only" to "any device holding an unrevoked A33 chain"; the group's job is to show this
widens *availability* without widening *unrevocable* compromise. Until P3 lands no client presents
a chain — the relay path is live but dormant, so P2's exposure is the new endpoints themselves.

**T-DA-01. Stolen/compromised device admits a rogue device before revocation.**
Actors: AD1–AD3. Assets: A33; A4/derived keys transitively (the rogue device becomes a realm
device). Invariants: I5.
A thief holding an unlocked (or unwrapped-at-rest) device uses its A33 chain to admit an
attacker-controlled device — the *new* risk delegated admission accepts, since today the same
thief needs an A1-holder device.
Defense: detectable — the admission produces a synced `Device` row (with `admittedByDeviceId`
lineage) every other device renders; revocable — `/sync/admission/revoke` strips the stolen
device's cert, and every chain through it, in one master-signed append (the §5 subtree property
falls out of per-link verification); data access is bounded by the existing removal + Kr-rotation
machinery (GR-7), unchanged. The rogue device's own admissions die with the same revocation.
Residual: **Medium → the window between theft and revocation** — the same shape as the pre-existing
stolen-unlocked-device exposure (§8.4), which already requires removal + rotation. Accepted;
delegated admission does not add an *unrevocable* capability (contrast the rejected sync-raw-A1
Option A, whose theft is game-over).

**T-DA-02. Forged chain admission at the relay.**
Actors: AD2–AD5. Assets: A33 (integrity), A4. Invariants: I5.
An attacker (or the relay itself) presents a fabricated chain — self-signed certs, a chain rooted
at a non-master key, another realm's certs, or a valid chain with a delegation signed by a
different key — to register an attacker device key.
Defense: link-by-link Ed25519 verification to the realm's TOFU-pinned `masterPublicKey`; every
cert is `ownerId`-bound, domain-separated and length-prefixed (`tenshu-admit-cap-v2`). ⚠ "No
cross-protocol signature reuse" is true only since B1 (2026-10-28, sub-amendment above): until then
the untagged new-device delegation, signed by the same A1/A33 keys, could be byte-identical to a cert. The leaf delegation must verify against the *presenting* chain's leaf A33.
The relay verifies but cannot mint (it holds no private key). Depth is capped
(`MAX_ADMIT_DEPTH`), bounding verification cost.
Residual: **Low** — reduces to Ed25519 forgery or a compromised A33 private key (T-DA-01).

**T-DA-03. Revoked admitter keeps admitting (replay of a once-valid chain).**
Actors: AD2–AD3 (holding a revoked device). Assets: A33. Invariants: I5.
A removed device replays its old, formerly-valid chain to admit new devices.
Defense: the relay consults the per-owner revocation ledger on every chain admission; a
master-signed revocation naming the cert's device **and A33 key** kills the cert and every chain
through it (F1′; since F5b the key is required and the old `epoch >= mintedAtEpoch` fallback is gone).
⚠ The `deviceId` half of that match is load-bearing (S2). And since S1 any chain still running through a
device in the F2′ removal ledger is refused regardless of keys (at post and at delivery), which covers a
removed device whose key the revoker never learnt — but not a sleeper already re-rooted to A1 (see the
sub-amendment's residual).
The ledger is durable (SQLite `admission_revocation`, the `member_revocation` posture) — a relay
restart cannot silently un-revoke.
Residual: **Low**, *given an honest relay*; a malicious relay is T-DA-04.

**T-DA-04. Relay drops or withholds revocations (AD5 re-admits a revoked device).**
Actors: AD5. Assets: A33. Invariants: I5.
The compromised relay ignores the revocation ledger and admits through a revoked chain.
Defense: none at the relay boundary — AD5 controls its own enforcement, exactly as it controls
`KnownDevicesStore` registration today (the pre-existing §8.3 posture: relay-side gating is
defence-in-depth, not the trust root). The *data-plane* backstop is client-side and unchanged:
peers reject blocks from devices whose admission epoch predates the realm epoch (T-K-02), and a
removed member's data access dies with Kr rotation regardless of what the relay admits.
Residual: **Medium, accepted** — consistent with the existing relay-trust posture: AD5 can grief
device *registration*, but registration alone conveys no plaintext (I1) and no valid data-plane
standing post-rotation.

**T-DA-05. Griefing the revocation ledger / the unauthenticated revoke endpoint.**
Actors: AD2–AD5. Assets: A33 (availability). Invariants: I5 (availability of revocation).
An attacker floods `/sync/admission/revoke` with garbage, or tries to strip a *legitimate*
device's admission authority.
Defense: a revocation enters the ledger only if its signature verifies against the realm's
master key — an unsigned/mis-signed post is rejected before storage, so stripping an admitter
requires A1 (which could rotate the whole realm anyway). Flooding with *valid* revocations
requires A1 for that realm (self-harm only); flooding with invalid ones is bounded CPU + a 401,
the same profile as every other signature-gated endpoint.
Residual: **Low**.

**T-DA-06. Forged admission-key announcement (P4 migration path).**
Actors: AD2–AD3 holding any realm device; AD5 cannot (rows are realm-encrypted). Assets: A33
(attribution), the revocation lineage. Invariants: I5.
A realm device writes an `AdmissionKeyAnnouncement` binding *its* A33 public key to *another*
device's `deviceId`. If the owner minted for it blindly, the attacker would hold a cert under a
different device's identity — surviving a targeted revocation of its own deviceId and polluting
the §5 lineage.
Defense: the announcement is signed by the claimed device's **A4** signing key over a
domain-separated, length-prefixed message (`tenshu-admit-announce-v2`), and the A1-holder verifies it against the
`Device` row's registered public key before minting. A mis-signed announcement is ignored. The
forger is by definition already a realm device (the rows are realm-key-encrypted), so it gains no
*admission* power it lacks — only mis-attribution, which the signature check removes.
Residual: **Low** — reduces to A4 compromise of the impersonated device, which is the existing
stolen-device posture (§8.4).

Invariants I1–I9 unchanged by this group. I5 (compromised devices can be evicted) is *extended*,
not weakened: eviction now also strips minting authority, and the thing being minted was always
detectable, synced state. House Rule #1 audit: every relay-visible field of surface #16 is an
opaque id, an epoch, a public key, or a master-/A33-signed token the relay verifies but cannot
mint; the A33 *private* key rides only inside the ECDH-encrypted pairing blob the relay forwards
unread. No decrypt code lands server-side.

### 8.18 Offline map-region delivery

Threats from the proxy fetching + verifying offline basemap region bundles
(`maps-region-delivery.md` §5). The bundles are public ODbL open data — no user secret rides this
path — so the concern is not confidentiality but (a) a coarse **location-interest** signal at infra
and (b) the integrity of what the proxy renders. Map apps stay `INTERNET`-stripped (D6): only the
**proxy** gains this egress (`tiles.service.tenshu.app`); the map app receives verified bytes over
IPC. As-built note: the proxy DI is wired (`MapRegionProxyComponents`) but the pinned manifest key
is a placeholder, so no live fetch happens until Phase 1 publishes a signed catalog.

**Streaming default (D10).** The delivery default flipped from whole-region download to
**streaming**: the proxy range-fetches tiles **on demand** and caches them, and whole-region
prefetch becomes the "make available offline" opt-in. This changes the infra signal from "some
client fetched region X once" to a finer **viewport pattern** (T-MR-03) and relaxes the per-tile
integrity guarantee (T-MR-04) — the honest cost the reframe accepts. As-built: the proxy-side
streaming range source has landed (`StreamingRegionSource` + `HttpRegionRangeFetcher` + the
`streamRegionRange` op, serving installed-first else a cached CDN `Range` fetch, with a low-zoom
prefetch); the **app-side `ProxyFileDescriptor` MapLibre reads through is a follow-up spike**, so no
per-viewport egress actually fires yet. Both properties below still hold: the **relay** stays
uninvolved and the map app stays `INTERNET`-stripped (the egress is the proxy's, D6/R1).

**T-MR-01. Region-interest signal at the tile host / CDN.**
Actors: the tile-host operator / a network observer. Assets: a new coarse location-interest signal
(**not** A1–A4 — no synced user data). Invariants: House Rule #1 is untouched — the **relay** is
entirely uninvolved (tiles never ride sync); this is a *separate* infra egress.
The tile host observes "some client fetched region X at time T", and (pre-blinding) commerce
observes "a subscriber asked for a tiles voucher" — never which region.
Defense: ownerless `aud=tiles` vouchers (§4 D4) so the gateway can't bind fetch→account; no durable
voucher-id logs; blinded issuance as the target state. **Residual (accepted, documented):** a
subpoena to *both* planes, or IP-level correlation, can re-link — the same posture as
`commerce-billing-plane.md` §7.

**T-MR-02. Tampered catalog / bundle from a compromised CDN.**
Actors: a compromised bucket/CDN. Assets: integrity of the rendered basemap (a MapLibre parsing
surface, not just wrong maps). Invariants: R4.
Defense: the catalog is Ed25519-signed with a pinned **release-key trust anchor** (a *new* trust
anchor, handled under the release-key regime); the proxy verifies the detached signature
(`RegionCatalogVerifier`, the same `Ed25519Backend::verify` the platform uses everywhere) before
parsing, then sha256-gates each downloaded bundle against the signed manifest
(`DefaultRegionDownloader`). A compromised CDN cannot serve tampered tiles that verify.
**Residual:** compromise of the signing key ⇒ attacker bundles accepted; mitigated by the
release-key handling + client key-pinning.

**T-MR-03. Streamed viewport pattern at the tile host (D10, new).**
Actors: the tile-host operator / a network observer. Assets: a **finer** coarse location signal than
T-MR-01 — by IP over time, *which tiles* (hence roughly *where in a region*) the proxy fetches while
the user pans, the exact signal the original whole-region-only default avoided. Invariants: still
**not** A1–A4 (no synced user data) and the relay is still entirely uninvolved — the observer is a
public, zero-user-data CDN plane (D6/R2), never the sync relay, and the egress is the proxy's, never
the map app's (R1). Defense (D10): **low-zoom prefetch** coarsens precision (the populated low-zoom
band is fetched up front, so only high-zoom detail streams); **caching** (`StreamingRegionCache`)
means re-views make no request and a streamed user accrues a partial offline cache; **offline mode**
("make available offline") is the zero-per-viewport-egress escape hatch. **Residual (accepted,
stated):** IP-level correlation of the viewport stream — out of scope, the same posture as
`commerce-billing-plane.md` §7. This is the surface the streaming phase accepts in reversing the
D1 anti-decision.
**Web (2026-10-28, `maps-region-delivery.md` §Web scope W.1):** a browser device streams the same
way, its **apex runtime** fetching from the tile host directly; the R2 bucket's CORS rule admits only
the apex origins (`https://tenshu.app`, `https://tenshu.xyz`), never an app origin. The observer is
unchanged — the CDN, by the device's own IP — and so is the relay's absence. **Delta:** each request
carries `Origin: https://tenshu.app`, so the CDN can tell a web reader from a native one. Requests
send no cookies and no referrer. The web has **no** low-zoom prefetch, only a 16 MiB in-memory cache,
and no offline mode, so a web viewer's stream is finer than a native one's (§Web scope W.3/W.5).

**T-MR-04. Streamed tiles are not sha-verified (D10 integrity delta, new).**
Actors: a tampering CDN/origin. Assets: integrity of a rendered tile in **streamed** mode. Invariants:
R4 holds for **offline** downloads (the signed catalog's per-region sha256 verifies the *complete*
bundle) but **cannot** cover an individual streamed range — PMTiles has no per-tile hashes, and these
tiles are public plaintext (not AEAD-sealed like Drive chunks). Defense: streamed integrity rests on
**TLS** + the **signed catalog pinning which object** is ranged into (`StreamingRegionSource` resolves
the path only from the verified catalog). **Residual:** a tampering CDN can serve a wrong
(MapLibre-parsed) tile while streaming; **offline users keep the full-file guarantee.** Accepted for
public, render-only data; stated.

**T-MR-05. Elected MapKit rendering discloses viewing behaviour to Apple (iOS only, new).**
Actors: Apple as the tile host; a network observer. Assets: **A35** — but the *viewing* projection of
it (which place, trip or workout was opened, and when), not the captured trace. Invariants: House
Rule #1 is untouched — the relay is entirely uninvolved and no coordinate rides sync under either
renderer; `Place`/`Moment` custody and sealing are unchanged.
Unlike T-MR-01/T-MR-03 the observer here is **not our own zero-user-data plane** but a third party
we cannot audit, and the egress is the **app's own**, not the proxy's — an elected iOS app talks to
Apple directly (the `INTERNET`-stripped posture is an Android manifest property with no iOS analogue,
R1).
Defense: it happens **only by election** — off by default, revocable at any time, re-read at every
hero construction so revoking takes effect on the next screen; scoped to **one app** (D9), so it
never silently spreads across the first-party set; scoped to the **three detail heroes** (D6), so it
is not the region picker, not a full-screen map and not an Explore surface; and disclosed in the
consent copy in the terms above — *which* place, trip or workout, and *when* — rather than as a
generic "Apple sees your location", which would overstate it (Location Services may already, and
A35 already records that `CLGeocoder` is server-backed: "Native ≠ private").
**Residual (accepted, documented): an elected device's map-viewing behaviour is observable by Apple,
and we cannot verify their handling of it.** Apple's stated MapKit posture — fuzzed location for map
service calls, rotating identifiers, no Apple-ID association — is genuine engineering but is exactly
the kind of claim the zero-trust posture declines to rely on. Unlike our own tile plane this residual
has **no offline reduction**: holding a region changes nothing for an elected user. That sentence is
the cost of the feature and is stated rather than softened.
⚠ Nothing here is default behaviour: a user who never opens Settings is unaffected in look and in
egress (`ios-apple-maps-option.md` D1).

**No new relay exposure.** The relay is uninvolved; A1–A4/A9 untouched. The only new infra egress is
the proxy → `tiles.service.tenshu.app` (a single whole-region GET for offline, a stream of `Range`
GETs for the D10 default) — never the map app's, **except** under an explicit MapKit election
(T-MR-05), which is the one path on which a map app talks to a tile host itself. All three `:ui-map` consumer apps (Maps, Timeline,
Fit) strip `INTERNET` at the manifest boundary; the Ph7b `INTERNET`-stripped render check (folded into
`scripts/android-smoke.sh`) is the standing regression proving map apps gain no egress.

### 8.19 Smart-home device actuation (HD-1)

The `:home-device-service` spine (`home-device-service.md` HD-D1/HD-D7): a hub consumes
realm-synced `DeviceCommand` rows and drives physical actuators through its `DeviceBridge`.
Surface #17. The command channel itself is ordinary realm-key-sealed sync — the relay stores
ciphertext blocks and never learns that a row is a command (House Rule #1 intact, no relay
change) — so the threats live entirely at the hub-side **executor**. HD-1's bridge is
simulated (no radio); the RF ingest threats (untrusted Zigbee airspace, permit-join
admission, the zigbee2mqtt network key at rest) are **deferred to HD-2** with the first real
bridge, per HD-D7.

**T-HD-01. Stale sync-backlog replay actuates the home.**
Actors: none required — ordinary sync-recovery behaviour is the hazard. Assets: physical
devices (and the household's sleep).
A hub restored from an old snapshot, rejoining after an outage, or receiving a delayed block
stream re-ingests historical `DeviceCommand` rows. Executing them would replay hours of light
toggles at whatever time the backlog happens to drain — physical effects long after any
gesture.
Defense: the **2-minute staleness window** (`DeviceCommand.STALE_WINDOW_MS`, HD-D7 —
deliberately much tighter than hub management's 15 min because the consequence is physical
actuation, not a restart). The window is part of the service contract, pinned by
`DeviceCommandSerializationTest.deviceCommand_stalenessWindowIsTwoMinutes` and the boundary
table in `DeviceCommandGateTest`; the end-to-end never-actuates path is pinned by
`HomeDeviceSystemTest.staleCommand_neverActuates`. Residual: an issuer clock skewed > 2 min
makes fresh commands stale (fail-safe direction — nothing actuates); a *hub* clock skewed
backwards widens the window by the skew — bounded by NTP on the node, accepted (§10).
Severity: Low after mitigation.

**T-HD-02. Command re-delivery double-actuates.**
Actors: none required (sync re-delivery is normal); AD1 can also replay ciphertext blocks.
Assets: physical devices.
The same `DeviceCommand` row is delivered more than once (re-sync, relay replay, hub
restart mid-stream). A `toggle` executed twice is a *different end state*, not a harmless
repeat — idempotence-by-row-key alone is not enough once the process restarts.
Defense: rows are keyed by the caller-minted `commandId` (a re-synced row is the *same*
command), and the hub persists consumed ids in a durable append-only journal
(`ConsumedCommandJournal`, `consumed-device-commands.log`) written **before** execution —
at-MOST-once by construction: a crash between journal and radio loses the command (the
issuer re-issues off the stale state row), never doubles it. Pinned by
`DeviceCommandGateTest.journalMakesRedeliveryANoOp_acrossReloads` and the re-delivery leg of
`HomeDeviceSystemTest`. An AD1-replayed ciphertext block is additionally inert: same row key,
already journaled. Severity: Low after mitigation.

**T-HD-03. A compromised realm device runs the home.**
Actors: AD4 (stolen/compromised paired device). Assets: physical devices, occupancy signals.
Any paired realm device can author commands for every bridged device and read every state
row (occupancy-adjacent data: lights, temperature, motion-shaped sensor readings).
Posture: **accepted — no new authority class** (HD-D7). Realm devices are already fully
trusted peers (the H4 posture note: they can restart the hub, wipe files, read everything);
device revocation (§8.5) is the recovery path, and the 2-minute window means a revoked
device's queued commands die quickly. What HD-1 changes is the *consequence* of that
existing trust (physical effects), recorded here so later slices (per-app/per-device
capability scoping, child-device roles) have the baseline. Severity: Medium, accepted for
v1 (unchanged trust model).

### 8.20 Smart-home radio ingest and admission (HD-2, zigbee2mqtt)

HD-2 gives the hub a **real radio** through a zigbee2mqtt sidecar (`home-device-service.md`
HD-D3), which is the half HD-D7 deferred: until now every bridged device was one we invented.
Surface **#17b**. Nothing here touches the relay (it still sees only ciphertext blocks — House
Rule #1 — and the sidecar is reached over *loopback*, so no new network surface exists at all);
what is new is that **bytes from the neighbourhood's airspace now become realm rows**, and that
the hub can open the household's radio to strangers.

**T-HD-04. Air-sourced device metadata becomes hostile realm content.**
Actors: AD1–AD2 exercising **radio proximity** (a neighbour, a passer-by, a device that was
compromised before it was bought). Assets: every app's rendering surface; the realm's rows.
A Zigbee device announces its own `friendly_name`, vendor, model and attribute set. Those
strings arrive unauthenticated, are attacker-chosen, and — before this slice — would have
flowed straight into a `HomeDevice` row that every Tenshu app on every device renders.
A 4 KB name, embedded newlines, terminal escapes, zero-width characters or a name chosen to
impersonate another row are all trivially available to whoever holds the device during a
pairing window.
Defense: `Zigbee2Mqtt.sanitiseText` at the ingest boundary — control characters (Cc/Cf, plus
the BOM and zero-width space) replaced by spaces rather than *dropped* (so `a\nb` cannot
become `ab` and forge a different legible word), whitespace collapsed, length capped at 64
characters per field; unmapped attributes bounded to 16 keys and 512 characters before
entering the `extras` slot; the ingested inventory itself capped at 512 devices. The IEEE
address, not the name, is the row key, so a name collision cannot merge or hijack another
device's row. Pinned by
`Zigbee2MqttMappingTest.anAdversarialDeviceNameIsBoundedBeforeItCanBecomeARow` /
`sanitisationCollapsesRatherThanConcatenates` / `stateExtrasAreBoundedInCountAndSize`.
Residual: a *legible* impersonating name ("Front door lock") is still possible and is not
something sanitisation can decide; the user renames it (the name field is user-editable, and
a rename survives every re-announce). Rendering surfaces must keep treating these strings as
text, never as markup. Severity: Low after mitigation.

**T-HD-05. An open radio admits the neighbourhood.**
Actors: AD1–AD2 within radio range. Assets: the Zigbee network, and through it physical devices.
Zigbee joining is unauthenticated by design: while the coordinator permits joining, anything in
range can join, and a joined device is thereafter a device the household drives (and, in v1, a
row every realm device sees). A permanently-open network is the smart-home equivalent of an
open Wi-Fi AP with no password.
Defense: permit-join is **explicit, time-bounded and off by default at every layer**.
`permit_join: false` is pinned in the shipped z2m configuration; opening it requires a
`DeviceCommand(verb = permitJoin)` authored by a realm device; the hub clamps the window to
`DeviceCommand.PERMIT_JOIN_MAX_SECONDS` (5 min) regardless of what was asked, and an
unparseable argument closes rather than opens. Crucially the request rides the *ordinary*
command channel, so it inherits both HD-1 mechanisms: the durable consumed journal (a
permit-join executes at most once) and the **2-minute staleness window** — a replayed
"open the network" from a sync backlog is inert, which is the sharp case, since a silently
re-opened radio is a standing invitation rather than a one-off wrong light. Pinned by
`Zigbee2MqttBridgeTest.permitJoinIsClampedAndPublishedAsATimeBoundedRequest` and
`Zigbee2MqttSystemTest.stalePermitJoinNeverOpensTheRadio` (a stale request reaches neither the
radio nor the journal). Residual: within a legitimately-open window, admission is
first-come-first-served — Zigbee's own model, not something the hub can fix; the window is
short and user-initiated. Severity: Low after mitigation.

**T-HD-06. The Zigbee network key at rest.**
Actors: AD1–AD2 with physical access to the node or its disk. Assets: the Zigbee network key.
zigbee2mqtt stores the network key in plaintext in its data dir (`data/configuration.yaml`).
Whoever reads it can join the household's Zigbee network from within radio range.
Defense: **same at-rest class as the H3 replica, same answer** — the z2m data dir belongs on
the LUKS volume (`hub-pi-deploy.md` §6/§7.3), plus POSIX ownership by a dedicated
`zigbee2mqtt` user and a `ProtectSystem`/`ReadWritePaths`-confined unit. The key is
`GENERATE`d per install and never committed. Residual: on a node without LUKS the key is
readable by anyone who takes the disk — identical to the hub's own `wrap.key` exposure
(T-C-17's hub notes), and accepted on the same terms. Severity: Medium without LUKS, Low with.

**T-HD-07. The local MQTT broker as a trust surface.**
Actors: AD2 (already on the home network), or any local process on the node. Assets: physical devices,
occupancy-adjacent telemetry.
mosquitto speaks plaintext MQTT with no authentication in the shipped configuration, and
anything that can reach it can publish `zigbee2mqtt/<device>/set` (switch everything) and
subscribe to every state topic (watch the household).
Defense: the broker **binds to 127.0.0.1 only** (`hub/deploy/mosquitto-tenshu.conf`, verified
with `ss -ltnp` in the how-to), so its reachability set is "processes on this node", which is
the same trust boundary the hub's own data dir already sits inside; persistence is off, so the
broker writes nothing about the household to disk. This also keeps the hub's posture intact —
it is an MQTT *client*, outbound-only, and adds no listener (the §11 no-listener decision).
The hub supports `TENSHU_MQTT_USERNAME`/`TENSHU_MQTT_PASSWORD` (environment, never argv, since
`/proc/<pid>/cmdline` is world-readable) for the day a LAN listener becomes genuinely
necessary; the how-to states that credentials are then the minimum and TLS the real answer.
Residual: a local root already owns the node. Severity: Low as deployed; Medium if an operator
exposes the broker to the LAN — called out explicitly in the config file and the how-to.

### 8.22 Household presence as realm data (RE-4)

The routines engine's `Presence` condition ("only when nobody's home") reads a new synced
`PresenceState` row — one per **location-capable device**, carrying `inside` / `outside` /
`unknown` plus a freshness stamp. Surface #18. Authored by the device itself off its own
geofence plumbing; ordinary realm-key-sealed sync carries it, so the relay stores ciphertext
and never learns that a row describes a person's whereabouts (House Rule #1 intact, no relay
change).

**T-RE-04. Household location becomes realm data without its own consent step.**
Actors: none required — the hazard is the feature arriving silently. Assets: a new coarse
location signal about household members. Invariants: I1, I2 (metadata minimisation).
Per-device inside/outside is genuinely new for a household that has never enabled Timeline
capture, and a user who writes an automation has not thereby agreed to publish their comings
and goings to every device in the realm. Defense: the row is **deliberately coarse** — it
carries the boolean and nothing else, never coordinates and never a place name, because a
routine only ever needs the boolean and a synced trail of positions would be a far larger
disclosure for no additional capability. ⚠ **Owed before the writer ships:** presence
reporting needs its own opt-in surface, separate from creating a routine, and the automation
editor's copy must state what the condition actually means (see T-RE-05). Residual: within a
realm every device can read every other device's presence — the realm is a single trust
domain by construction (peers are fully trusted), and per-device read scoping would need the
granular capability track. Severity: Low inside the realm; the consent gap is the real item.

**T-RE-05. A fail-open presence verdict actuates the home at the wrong moment.**
Actors: none required — a flat battery is enough. Assets: physical actuation (surface #17).
The naive implementation reads "no signal" as "not home", so "arm the alarm when nobody's
home" fires while someone is asleep upstairs with a dead phone. Defense: `PresenceResolver`
**fails closed** — any contributing device that is `unknown`, or whose row is older than
`PresenceState.STALE_WINDOW_MS`, makes the whole verdict `Unknown`, and an `Unknown`
condition **skips** the routine and records why. No contributors at all is likewise
`Unknown`, not "nobody home": a household that never enabled location has not told us the
house is empty. Hubs and revoked devices are excluded from the verdict (an always-on box in
the hall is definitionally home; a revoked phone's last known position must not keep deciding
the house). ⚠ Residual, and it belongs in the UI copy rather than being hidden: a device
with location turned off never contributes at all, so the condition means *"none of the
devices you have given location to is home"* — which is also the honest reading of a realm
that is one user's devices. Severity: Low with the fail-closed rule; High without it.

### 8.21 Schema epoch fencing (E2d)

**T-SE-01. App-inventory disclosure via a relay-visible schema version vector.**
Actors: AD1 (an honest-but-curious or breached relay operator). Assets: A37 (the realm schema epoch
vector). Invariants: I1, I2 (metadata minimisation).
The compatibility mechanism that stops a lagging device from garbling blocks it cannot parse
(`core-schema-evolution.md` §7.3) needs a realm-level record of "highest schema version emitted per
service". The naive placement is the one the platform used for its coarse 426 gate before E2e: a
plaintext field on `/sync/realm`. Done per service, that would hand the relay an **app inventory** —
a Fit entry marks a fitness-app user, a Timeline entry a location-tracking user, a Mia entry an
assistant user — which is a materially new metadata capability, and one the relay has no need for:
under House Rule #1 it cannot act on a payload schema it never decrypts.
Defense: **the epoch vector is E2E** — `RealmSchemaEpoch` is an ordinary `Syncable` row carried in an
encrypted `SyncBlock`, so the fence is evaluated **client-side** by devices that hold the realm keys,
and the relay stores opaque bytes (§7.1). The relay keeps exactly one plaintext compatibility number,
and E2e re-keyed it onto `RELAY_PROTOCOL_VERSION` — the relay's *own* wire contract (envelope, routes)
— so payload schema versions have left the relay entirely rather than being demoted (§7.5, as built
§7.8). One consequence is a small, free privacy gain: the relay no longer learns the client's
storage-format number in any form. Client-side fencing is
also strictly *better* than a relay-side check: per service, self-scoping (a realm with no Fit data
never grows a Fit entry, §7.2), and it never sees the app list at all.
Residual: **availability only.** A relay that withholds or delays the block carrying an epoch bump can
delay a peer's fencing — but withholding blocks is a power it already has (§8.1), and the failure mode
degrades to today's behaviour (an unparseable block is dropped, now logged by decision — §8.2/E2c)
rather than to silent corruption. A relay cannot *forge* a bump: the row is authenticated by the same
AEAD as every other block. Ordering skew between two devices' bumps is handled by the merge being MAX
(a join-semilattice: order-independent, §12 Q3), so no adversary-controllable arrival order changes the
converged vector. Severity: **Low**.

### 8.22 Mia's query layer (MQ-1)

**T-MQ-01. Generated chains as a confused-deputy surface.**
Actors: AD3 (a malicious or buggy caller inside the device), and — from MQ-5 — a language model.
Assets: A39, plus whatever the granted services hold. Invariants: I1, I4.
Mia executes chains **it composed itself, with the user's own authority**. That makes the *caller*
the wrong boundary and the **validator** the right one: nothing about the chain mechanism restricts a
callstack to reads, and a chain that projected `File.Data` would exfiltrate content bytes through a
surface whose stated job is metadata. Defense: `MiaChainValidator` refuses before execution on four
checks — an **operation allowlist** (reads only, enforced not inherent), a **service allowlist** (a
revoked source is refused, never filtered afterwards), a **projection allowlist** (fails closed:
content-bearing properties are absent by construction, so the next one added is refused without
anyone remembering to add it), and **cost bounds** (a terminal `take` or a self-bounding op, plus
step and nesting ceilings — an unbounded generated tree-walk is the likeliest way to take the proxy
down, since `RecursiveListWalk` loses the projection and materialises every stored property per row).
⚠ **Two limits recorded plainly.** (a) Grants are **per service, not per property** — the projection
allowlist is a stand-in until the fine-grained capability track lands. (b) **Subject identity is
weaker than it looks**: this authenticates a chain, not a caller, and on desktop `UnixSocketServer`
still hardcodes `clientId`, so per-service grants cannot yet be enforced against a reliably
identified subject there (tracked as app-registration work, not query-layer work).
A model-generated chain (MQ-5) changes the *generator*, not this boundary — which is the entire
reason the validator ships in MQ-1. Severity: **Low** as shipped (the only generator is a closed
grammar whose every emitted chain is test-asserted to pass); the checks exist for what comes next.

**T-MQ-01b. A grant the user believes they revoked.**
Actors: AD3. Assets: A39, plus whatever the granted services hold. Invariants: I1, I4.
MQ-D6's per-service switches are only worth anything if revocation is *effective* and
*legible*. Three properties carry that. (a) Grants are read **per question, never cached** —
a revocation takes effect on the next thing the user asks, which is what flipping a switch
plainly implies; a cached set would keep answering from a source they just turned off for as
long as the proxy lived. (b) The revoked source is refused **during planning, by name** —
"I'm not allowed to read your timeline" — because answering *empty* would assert an absence
we never established, telling the user their timeline is bare when it is full. (c) The
validator's `allowedServiceIds` is **derived from the same grants**, so a chain for a revoked
source is refused even if the planner were bypassed; the two checks answer different
questions ("should Mia have tried" vs "may this callstack run") and only the second is the
boundary.
⚠ **Absent means granted, and that is deliberate.** A grant row exists only once the switch
has been touched, so a never-configured realm grants everything — failing closed there would
silently disable an assistant nobody had restricted. The fail-closed posture lives one layer
down: the validator refuses anything *not* on the allowlist those grants produce.
⚠ Grants are realm-scoped (`RealmSetting`), so revoking on one device revokes on all — which
is the user's evident intent, and also means the switch must follow the row rather than only
its own taps. ⚠ Residual: unchanged from T-MQ-01 — per **service**, never per row or
property, and the UI says exactly that rather than implying finer control. Severity: **Low**.
*Web amendment (2026-10-28, `web-app-suite.md` §8):* the switches are also writable from the
`web/mia` origin (`Mia.setGranted` / `setWritable`, the same `PreferenceService.set` native Settings
calls), and a revocation there is effective and legible on the next question on **every** device —
`MiaWebContractTest` turns a grant off through the web call and asserts the next question needing it
is `noMatch` naming the source, with exactly one write dispatched (the grant row). ⚠ The web view is
never optimistic: a switch draws the set read back after the write. ⚠ What this does *not* buy is a
boundary against the Mia origin itself (A39's web amendment): whoever drives that origin drives its
switches.

**T-MQ-01c. Mia actuating something the user did not confirm.**
Actors: AD3, and the user's own misheard phrasing. Assets: A39 plus the smart-home actuation
surface (§8.20). Invariants: I4.
MQ-1/MQ-2 were reads: a wrong answer is seen and ignored. An action is not — and once voice
lands there is no undo and no hover state, so "turn off *everything*" is a bad night.
Defense, in layers. (a) **A question cannot actuate**: the mutating op is a *separate call*,
never a step in a query chain, so MQ-D10's reads-only allowlist keeps the two paths disjoint
no matter how the grammar drifts. (b) **The confirmation names the resolved action**, not the
utterance — the device Mia actually found — so the user agrees to what will happen rather than
to what was understood. (c) **The token is a capability over one resolved action**: it cannot
name a device or a verb, so `ConfirmMiaAction` cannot command anything Mia did not already
resolve and display. (d) **Single-use and expiring** (5 min), so a stale confirmation cannot be
tapped meaningfully later, on top of `DeviceCommand`'s own 2-minute staleness window which
stops a backlog-replayed command actuating late. (e) **Absolute intent only** — `setPower`,
never `toggle`, inherited from HD-3 for the same reason: a flip against a stale reading
disagrees with the user exactly when it matters. (f) **Never guesses between candidates**: two
devices matching the name is a disambiguation turn, not a coin flip with a physical outcome.
⚠ Residual: the pending registry is in-memory, so a proxy restart drops confirmations — a
refusal, never a resurrected command. ⚠ And Mia adds **no privilege**: `IssueDeviceCommand` is
an ordinary op any registered client can call, so this path narrows what Mia can do rather
than widening what the device plane exposes. Severity: **Low**.

**T-MQ-01d. Mia writing a row the user did not agree to.**
Actors: AD3, and the user's own misheard phrasing. Assets: A39 plus the calendar's own data.
Invariants: I4.
`CreateEvent` is the first thing Mia does that **authors user data**, and it leaves MQ-D1's
containment: a wrong *answer* is seen and ignored, a wrong *row* persists, syncs to every device
and fires a reminder long after the utterance is forgotten. Two controls, answering different
questions. (a) **A write grant** — `(service, read + write)`, defaulting to **off** while the
read grant defaults on, because reading was already happening and writing never was; write
without read is not offered, since a write intent resolves its slots against existing data
first. (b) **A resolved-artifact confirmation** — the cell shows the row that will exist, with
the service-owned calendar and end time resolved and displayed, and those resolved values travel
with the pending action so a default changed in between cannot silently move the event.
⚠ A missing time is **asked for, never guessed**: an event with an invented time is precisely
the durable wrong row this threat is about. ⚠ A bare hour ("at 3") *is* resolved by convention,
which is acceptable only because the cell shows the result first.
⭐ Bounded by choice of first write: an event is **reversible**. `DeleteEvent` and editing are
deliberately not intents (MQ-D4a), so the worst outcome is an unwanted entry, not lost data.
⚠ Residual: Mia adds no privilege — `SaveEvent` is an ordinary op any registered client can
call — so this narrows what Mia may do rather than widening the calendar's surface.
Severity: **Low**.

**T-MQ-02. Conversation history as a local sensitive artifact.**
Actors: AD2 (an attacker with the unlocked device), AD4 (a co-resident app). Assets: A39.
What someone asks — a symptom, a person's name, a place they are checking on — is routinely more
revealing than the rows they are shown, so treating transcripts as ordinary small synced data would
be a mistake. Defense: **there is no durable transcript.** History is process-local and dies with the
app; nothing is written to the store and nothing is synced. Syncing history is a separate opt-in with
its own copy, and MQ-D8 requires the delete affordance to land in the same slice that makes it
durable — so "a history you cannot clear" is unreachable by construction rather than by discipline.
⚠ Forward-looking: a Rei-era Skill must never see history it did not produce; recorded now so that
permission model does not have to discover it. Severity: **Low**.

### 8.23 Android direct-release distribution

Threats from offering the Android apps as APKs on tenshu.app, outside Play
([`android-direct-apk-distribution.md`](../proposals/android-direct-apk-distribution.md), decisions
D1–D5; built: `scripts/android-release.sh --direct`, `deploy/cloudflare-releases/`,
`scripts/android-direct-publish.sh`). The path carries no user data, so the concern is which binary a
user ends up running. Android's own rule shapes every entry: an **update** installs only with the
installed copy's signer, so it is guarded by A43 alone; a **fresh install** has no signer to match and
trusts what it is given, so it is guarded by A44 and by the user.

**T-DA-01. A replaced APK in the release bucket, taken by a fresh install.**
Actors: anyone holding A44's publish key or the Cloudflare account (a leaked key, a compromised
maintainer machine, AD3/AD4 against Cloudflare). Assets: A44, and through it A5/A6 once the user pairs
the malicious app. Invariants: I1 at the user's device boundary (the malicious binary sits on the
plaintext side).
The attacker writes other bytes under an existing `sha256/<hex>.apk` key, signed by their own key.
A user installing that app fresh gets it; if it is Control, and the user then pairs it into their
realm, it holds the realm. *Mitigated:* (a) the bucket and its key are their own, scoped to this bucket
alone and held by the maintainer only, never by CI or the assets tooling (D3); (b) **the attacker's
copy cannot update anything**: a user who already has the app keeps the genuine one, since Android
refuses a different signer; (c) a substituted app other than Control is **refused by an installed
genuine Control** (T-C-20: its signer is neither a pinned Play key, nor A43, nor Control's own), so the
damage is confined to a device with no Tenshu app yet that installs a fake Control first; (d) the
committed manifest — written by the maintainer and rendered into the download pages from git, not
from the bucket — publishes each APK's SHA-256 and A43's certificate digest, and the download page's
guide is to tell the user to check them with `apksigner verify --print-certs` (Phase 1 item 5, not
built as of 2026-11-04); (e) the publish step downloads every object it is about to name and refuses
to write the manifest unless the bytes hash to their key, and `scripts/android-direct-publish.sh
--verify` does the same against the public URLs — a step of the runbook, run after each publish and
on any suspicion, not an automatic monitor. Residual: **Medium** — (d) helps only a user who checks, and an
attacker who also controls the tenshu.app pages (a separate trust root: the apex host) could change
the digests shown. A signed `release.json` (proposal Phase 3) would help tooling, not a browser
install.

**T-DA-02. A43 leaks.**
Actors: AD2–AD4 reaching the maintainer's machine or the offline backup. Assets: A43, and through it
A5/A6 on every device with a downloaded copy. Invariants: I1 (device boundary), T-C-20's admission.
With A43 an attacker signs an app under a first-party package name that **installs as an update over
every downloaded copy** and that Control admits on any device (T-C-20's ⚠ already names the latter).
Distribution still needs a channel — A44, a phishing page, a side-loaded file — but no user check
catches it, since the signer is the published one. *Mitigated:* trust-anchor custody (A43:
`ops-secret-custody.md` D1, never in automation; the offline backup outside directories tooling
wipes, D5). ⚠ D5's own placement for trust anchors, `~/Documents`, is **iCloud-synced** by a
knowing trade (off-machine backup against loss), so unless the backup is kept elsewhere the key also
exists in consumer cloud storage; it is held only by the maintainer, never by CI or a server. Recovery: the pin is
**replaced** (not kept beside a new one, `android-release.md` *The side-channel key* — a kept pin keeps
admitting the leaked signer through rotation lineage) in a Play release to all ten apps, and every
downloading user reinstalls. Residual: **Medium** (low likelihood, high impact); it drops with D6's
hardware token, which makes the key unreadable by software.

**T-DA-03. A43 is lost.**
Actors: none (accident, a wipe, a dead disk). Assets: A43. Availability only.
Without the key no update can be signed, so every downloaded copy is stuck on its version until the
user uninstalls (losing that app's local store, `allowBackup="false"`) and installs a copy signed by a
new key — and the schema-skew fence (House Rule 9) will eventually make the stuck copy unusable.
*Mitigated:* an offline backup before the first APK ships (`android-release.md`, step 2) and D5's
placement rule. Residual: **Low**.

**T-DA-04. A copy signed by the wrong key is published.**
Actors: the maintainer, by mistake. Assets: A44; T-C-20's admission.
An APK signed by an unpinned or throwaway key (the `--allow-unpinned` dry run) would be refused by
every Play-installed app and, once installed, could never be updated by a correctly signed one.
*Mitigated:* `android-release.sh --direct` refuses to build with an unpinned key into `build/direct/`
and puts a dry run in `build/direct-unpinned/`; the publish step refuses that directory by resolved
path, refuses an empty pin, and reads every APK's signer back with `apksigner` before any upload,
refusing anything but the pinned digest; it also refuses a manifest change of signing key. Residual:
**Low**.

**T-DA-05. The download page offers an older or inconsistent APK.**
Actors: the maintainer by mistake, or anyone able to change `store/android-release.json` in git.
Assets: A44. Availability and confusion rather than compromise.
A page pointing at a lower versionCode than a user has installed offers an APK Android refuses; two
different builds under one versionCode make "the latest" ambiguous. *Mitigated:* the publish step
refuses a lower versionCode or the same code with other bytes, writes the manifest only after every
object it names is uploaded, read back with the right headers and hashed (objects first, manifest
last), and `direct_publish.py check` enforces the manifest's shape (one signer, one ABI, URL = base +
hash). The manifest is reviewed like any other committed file. Residual: **Low** — the publish step
re-reads each APK's signer, hash and ABI, but trusts `release.json`'s versionCode (the build checked
it with `aapt2`), so a hand-assembled `build/direct/` with a mislabelled APK would pass.

Privacy note: Cloudflare sees the downloader's IP address and which APK was fetched, as it already
does for `assets.tenshu.app` — infrastructure metadata about an anonymous download, never user data
(House Rule #1). The privacy policy update is Phase 1 item 7.

### 8.24 macOS direct-release distribution

Threats from offering the ten desktop apps as notarized Developer ID `.dmg` on tenshu.app and through
the `b3nedikt/homebrew-tenshu` casks ([`desktop-macos-distribution.md`](../proposals/desktop-macos-distribution.md)
§2.7, §2.12; built: `scripts/macos-release.sh`, `scripts/macos-direct-publish.sh`). Same bucket, key
and rule as §8.23 (A44): objects first, every object hashed back, manifest last. What differs is
macOS's own rule: Gatekeeper opens **any** notarized Developer ID app, whichever team signed it, and
nothing on a Mac ties an app's next version to its current signer the way Android's update rule does.

**T-MD-01. A replaced `.dmg` in the release bucket.**
Actors: as T-DA-01 (A44's key, the Cloudflare account). Assets: A44, and through it A5/A6 once the
user pairs the malicious app. Invariants: I1 at the device boundary.
The attacker writes, under an existing `sha256/<hex>.dmg` key, an app notarized under **their own**
Developer ID; Gatekeeper accepts it. *Mitigated:* (a) as T-DA-01 (a), (e): one bucket-scoped key held by
the maintainer, the publish step's read-back hash, `--verify` against the public URLs; (b) **a
Homebrew install pins the bytes**: the cask carries the `.dmg`'s sha256 and `brew` refuses any other,
so the attack reaches only browser downloads; (c) the IPC gate (A14) admits a peer only when it is
signed by team `752LWAZMKZ` with a first-party identifier, so a substituted **client** app is refused
by a genuine Control; (d) the committed manifest publishes each `.dmg`'s sha256, and `spctl -a -vv`
names the signing team, which a careful user can check; (e) **the gate runs both ways** (A14, amended
2026-11-06): every genuine app attests the Control it connects to, so a substituted **Control**,
signed by another team, gets no request from an app that is already installed. The damage is
confined to a Mac whose Tenshu apps all came from the attacker. Recovery: re-upload the genuine
bytes and ask Apple to revoke the attacker's notarization. Residual: **Medium**, as T-DA-01.

**T-MD-02. An installer that is not the release's.**
Actors: the maintainer by mistake. Assets: A44.
An unnotarized `--pipeline-test` build, one signed by another identity, a mixed-version set, or a
second build under a published version. *Mitigated:* the publish step reads a `macos-release` directory
(never `macos-release-test`), requires exactly the ten catalogue apps at one version and one build, runs `stapler
validate` and `spctl … context:primary-signature` (must say Notarized Developer ID) on each `.dmg`,
mounts each read-only to check the bundle id, the version and the team's Developer ID signature, and
refuses a lower version or a published version with other bytes. ⚠ It reads any directory named
`macos-release`, so `stapler` and `spctl` are what keep an unnotarized set out. Residual: **Low**.

## 9. Security Invariants Revisited

Re-checking §3 against §8. All eight hold under the full adversary set (AD1–AD5), with the following
explicit carve-outs:

- I1 holds under AD5 for data at rest. Metadata (A6) is exposed by design; this is stated in §8.2
  T-T-07 and is accepted for v1. The macOS native-bridge surface (A20) adds **no** relay exposure:
  its File Provider methods run the same proxy/file-service path as the in-app client, so the relay
  still sees only ciphertext blocks (House Rule #1). Against a *local* adversary (AD3), I1 currently
  has a desktop gap — the bridge socket is not yet behind the A14 handshake (T-C-15) — closed when
  Phase 4 wires the §3 peer-credential check onto `bridge.sock`.
- I4 is implemented via `DeleteBlock` of the revoked Device entity plus volume-key epoch
  rotation plus relay-side push rejection. See [`sync/design.md`](design.md) §2.6 +
  [ADR 0006](../adr/0006-sync-tombstones.md), and `sync/design.md` §2.2 +
  [ADR 0007](../adr/0007-sync-device-management.md). Both client- and relay-side mechanisms shipped 2026-05-05: the Device-entity
  tombstone propagates end-to-end AND the relay extracts `revokedDeviceId` to revoke
  the `KnownDevicesStore` entry, so the revoked device's next push returns 401.
  Outstanding: tombstone GC sweep (storage cleanup, no security impact). The legacy
  `SyncMailbox.revokeDevice` empty stub and `/identity/*` device-management endpoints
  have been deleted.
- I6 is enforced client-side as of 2026-05-05: HLC-tip piggyback on `POST /sync/changes`
  + `HlcDivergenceTracker` detects forged peer tips (signature mismatch) and forked-view
    attacks (peer tip regression). `SyncManager.divergenceWarning: StateFlow` surfaces the
    warning state; **UI rendering** is the only downstream piece left.
- I7 is scoped to the user's **Recovery Manifest** (A10). The master identity key is always
  covered; volume keys are covered only for volumes the user has opted in, per §11.1. Volumes
  outside the manifest are explicitly out of scope for server-assisted recovery and the UI must
  make this trade-off visible when the user edits the manifest.
- I8 is a client-implementation concern that depends on checklist compliance per platform. Track in
  a separate client-hardening checklist. The macOS File Provider extension adds an entry: its
  on-open temp-file materialisation of decrypted bytes (T-C-16) must stay inside the extension's
  sandbox container and be evicted on the system's schedule, so plaintext lives on disk only while
  the file is in active use.

## 10. Out of Scope / Accepted Risk

- **T-OB-01. Onboarding deferral window.** Per
  [`onboarding-low-friction.md`](../proposals/onboarding-low-friction.md) §9.2, the redesigned
  first-run flow defers user-visible recovery-phrase capture: the phrase is generated and
  persisted but never shown until the user opens "Set up account recovery" in Settings or pairs
  a second device. *Threat:* a single-device user loses the device during this window with no
  alternative copy. Data unrecoverable. *Mitigations:* the §7.2 Level 0 affordance prompts the
  user from first-run; future Levels 1–3 escalate based on time and data accrued. *Accepted
  residual:* users who churn before engaging either path. By design these users have little
  data — the loss is bounded by what they had time to accrue. Telemetry on dismissal-time
  distribution will validate the assumption once it lands.

- **macOS File Provider `.appex` verification is on-device only.** The Swift File Provider extension
  runs only inside a signed, installed `Drive.app` (Xcode build + codesign + notarisation), so its
  behaviour — including the I8 temp-file discipline (T-C-16) and the App-Group reachability posture —
  is verified by manual on-device testing, not the protocol test suite. The *daemon-side* bridge
  methods it drives are covered by automated UDS round-trip tests
  (`FileBridgeRoundTripTest` / `FileWriteBridgeRoundTripTest`). Note this is a *verification-coverage*
  limitation, not a gate one: the bridge handshake itself has **enforced since 2026-07-28**
  (T-C-15, A20).
- Traffic analysis revealing who-syncs-with-whom and when. Mitigations are expensive and not planned
  for v1.
- ~~Device names visible to the relay.~~ *(Resolved 2026-05-04; see
  [`design.md`](design.md) §2.2 + [ADR 0007](../adr/0007-sync-device-management.md).
  Device names live in encrypted `Device` entity blocks; the `/identity/*`
  endpoints + `SyncMailbox.deviceRegistry` are deleted.)*
- Targeted binary compromise (TA4 violation) — handled by the platform, not the protocol.
- OS-level compromise of client devices (rooted/jailbroken) — explicitly untrusted.
- Duress scenarios. No duress password in v1.
- Active DoS by the relay (pure availability attacks indistinguishable from outages).
- **Browser sync as a key-distribution channel.** On web (per
  [`docs/web/architecture.md`](../web/architecture.md) §4.2), Chrome Sync / Firefox Sync may
  replicate the `tenshu.app` IndexedDB across all browsers signed into the user's
  Google/Mozilla account. When this happens, the device key in IndexedDB is shared across
  every machine in the sync set, making them all *one* realm device rather than several. This
  is a property of the user's browser configuration, not a protocol-level fact, and is
  accepted as out-of-scope for the protocol. The web control plane surfaces the situation
  legibly (*"This realm device is shared via browser sync"*) and offers per-browser
  instructions for excluding `tenshu.app` from sync if the user wants per-machine
  granularity. Browser-sync providers are themselves outside the trust set per TA2 (platform
  keystores are the only platform key-distribution mechanism Tenshu trusts); a compromised
  browser-sync account therefore expands the attack surface, and the user should treat their
  Google/Mozilla account credentials with the same care as their device passwords.

## 11. Resolved Decisions

Decisions previously open; now locked in and reflected in the rest of this document.

1. **Recovery backup contents — user-managed manifest (A10).** The server-side backup contains the
   master identity private key (always) plus the volume-wrapping keys for every volume currently
   included in the user's recovery manifest. The manifest is chosen at signup with a sensible
   default (master-only) and is editable in the control app, per-volume, at any time. Each edit
   triggers a signed re-upload (see T-RC-05 for the caveat about superseded blobs).
2. **Recovery code entropy — 24 words (256 bits).** Pure future-proofing margin; 128 bits is
   already infeasible to brute-force under reasonable KDF parameters, but backup blobs may live
   for decades and the cost of the extra 12 words is one screen of onboarding.
   Implementation notes: the recovery code is a standard BIP-39 mnemonic generated by
   `core-common/.../util/Bip39.kt`. The BIP-39 PBKDF2 *seed* derivation is **not** used; the
   raw entropy decoded from the mnemonic is fed directly into Argon2id per T-RC-02. The
   implementation provides `encode()`, `generateMnemonic()`, and `decode()`; the `decode()`
   path raises typed `Bip39InvalidMnemonicException` subtypes (`UnknownWord`,
   `InvalidLength`, `ChecksumMismatch`) so the recovery UI can drive distinct user
   feedback for each failure mode. Wordlist is BIP-39 English only; localisation is a v1.x
   item.
   *UX caveat:* a standard BIP-39 wordlist is interchangeable with hardware-wallet recovery
   phrases at the format level. The control app must make clear that a Tenshu recovery code
   is **not** a Bitcoin/Ethereum wallet seed and must never be entered into a wallet UI.

   *Amendment (2026-09-15) — the list was not the published one, and phrases minted before
   this date are void.* ⚠⚠ `BIP39_ENGLISH_WORDLIST` had drifted from `bip-0039/english.txt`:
   2060 entries instead of 2048, diverging at index **33** and from there on, carrying 28 words
   the spec does not contain (`crocodile`, `facilitate`, `romp`, …) and missing 16 it does
   (`aerobic`, `noodle`, `pizza`, `satoshi`, `version`, …). A word's position *is* 11 bits of
   entropy, so 2015 of the 2048 addressable indices meant something other than what the
   spec says — and the twelve entries past 2047 were unreachable by `encode` and aliased on
   `decode` (`zoo` decoded as `accident`), because an index only ever gets 11 bits. The UX
   caveat above therefore described an interchangeability that did not exist: measured over
   20 000 mints, a conforming BIP-39 implementation rejected 99.7 % of Tenshu phrases outright
   and silently derived a *different* key for the remainder. The list is now byte-for-byte the
   published file, pinned by SHA-256 in `Bip39WordlistTest`.
   ⚠⚠ This is not only a recovery-path break. `deriveKeys` and `deriveMasterKeyPair` both run
   over `Bip39.decode(phrase)`, so the same written-down words now yield different shared keys
   and a different `ownerId`: an owner device re-derives from its stored phrase at startup
   (`SyncManagerImpl`'s auto-load) and on unlock (the passphrase wrapper wraps the *phrase*, not
   the keys), and so loses its own realm, while joiner devices hold derived keys directly, keep
   working, and diverge from it. No migration was written — at the time of the change no realm
   existed whose loss mattered. ⚠⚠ That will not be true of the next such change: the wordlist
   is consensus data, not a resource file, and any future edit needs a migration designed first.
3. **Consistency-check cadence — piggyback on `/sync/changes`.** Every poll a device makes to
   `/sync/changes` includes a signed HLC tip (latest HLC the device has observed across the realm).
   The relay's response carries other devices' last-known tips. Any divergence — a device sees a
   tip for itself that does not match what it knows it published, or sees tips from peer devices
   that contradict what a parallel device sees — triggers a user-visible warning and a cross-device
   reconciliation request. No additional polling cost; one extra signed field per existing poll.
   This is what makes I6 enforceable rather than aspirational.
4. **Duress features — explicit v1 non-goal.** Revisit post-v1.
5. **Cover traffic / metadata obscuring — explicit v1 non-goal.** Revisit post-v1.

## 11a. Still-Open Questions

These are tracked items, not blockers for the document itself, but they need to land before the
relevant flow can ship.

- **Recovery wordlist localisation.** Default is BIP-39 English. Once the recovery flow is
  user-facing, agree on whether non-English locales select a different BIP-39 wordlist or
  remain on English. Either choice is fine; pick deliberately.
- **Local at-rest encryption of the materialised store + file bytes.**
  (T-C-09.) The `SqliteObjectStore` (entity/relation metadata) and the
  `FileDatabase` (file bytes) both persist cleartext on disk, protected
  only by OS/device-level storage encryption. Decide whether to add an
  app-level layer (SQLCipher, or master-key/keystore-wrapped blobs).
  **Must be decided jointly for both stores** — they share one threat
  class (T-C-09) and diverging (encrypt one, not the other) would be
  incoherent. Out of scope for `core-sql-persistence.md`; not a v1
  blocker on platforms with hardware-backed full-disk encryption, but
  pick deliberately before positioning Tenshu against a stronger local
  adversary than T-C-01.
- **Rei + shared-realm + desktop open questions.** The v0.3 amendment
  surfaces several open design questions tracked in
  [`tenshu-rei.md`](../proposals/tenshu-rei.md) that bear on threat
  posture and will need to be reflected back here as they resolve:
  bundled-source revocation feed (§5.A.2, §5.A.3 → tightens T-REI-01
  rotation latency); single-vs-multi-owner shared realms (§5.C.22 →
  shapes T-SR-03); shared-realm recovery semantics (§5.C.28 → resolves
  T-SR-03); per-app permission scope on shared realms (§5.C.29 →
  resolves T-SR-04); developer-mode blanket-vs-dev-trust-anchor
  (§5.F.49 → tightens T-C-12); machine-bound identifier derivation
  source (§5.F.50 → tightens T-C-13). Each will land its own
  sub-amendment per House Rule #2 when the implementation slice ships.

## 12. Review and Maintenance

- This document is re-reviewed on every material change to the sync protocol, pairing flow, or relay
  storage model.
- Cryptographic parameter choices (KDF cost, mnemonic length) are re-reviewed annually.
- Every threat ID in §8 is cross-referenced from the code that implements its defense (as a comment
  or in a dedicated "security-critical paths" index).
- Any new feature that touches A1–A4 or A9 requires an amendment to this document before
  implementation.
