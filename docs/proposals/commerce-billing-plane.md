# Commerce & billing plane — paid storage without de-anonymising the data plane

**Status:** v0.2 — ⚠ this said "sketch, no code" until 2026-08-11 and contradicted its own §8,
where Phase 1 is `✅ Landed` and Phase 2 landed in all three slices (2a
`ENTITLEMENT_ENFORCE`, 2b per-owner quota `507 over_quota`, 2c client token-fetch +
`X-Entitlement`). Phases 3–5 remain unstarted. Opened from a maintainer discussion (2026-06-13)
on where paid-storage accounts live; open questions Q1–Q5 resolved 2026-06-14 (see §9),
Q6 (minimal-vs-strong launch timing) still open. Supersedes the offhand "the Publishing Service will be a
separate Fly app later" framing in [`deploy-relay-fly.md`](../how-to/deploy-relay-fly.md) and
the "publisher service registration" aside in
[`deploy-relay-digitalocean.md`](../how-to/deploy-relay-digitalocean.md) — that single
"publishing service" actually conflates **three** planes (§2). This doc fixes the
decomposition and specifies the bridge between the commerce plane and the relay. No phases
have shipped; the threat-model deltas in §6 are proposal-level and land with the
implementing phase per House Rule #2.

Companion reading: [`sync/threat-model.md`](../sync/threat-model.md) (the zero-trust stance
this must not weaken), [`control-app-registration.md`](control-app-registration.md) (asset
A18 — the *local* app-registration set, **not** this), and [`tenshu-rei.md`](tenshu-rei.md)
(the Rei trust chain — the likely home of the *developer* plane, §2).

---

## 1. The tension (and why it isn't actually a contradiction)

Selling storage means holding real customer data: payment, billing identity, invoices,
tax/retention records, GDPR data-subject obligations. That sounds like it contradicts the
whole product — the relay is in the adversary set and learns nothing
(threat-model §2; House Rule #1). It doesn't, once you're precise about *what* zero-trust
covers.

**Zero-trust is a property of the data plane, not of the company.** The relay never learns
who you are or what your bytes mean. That was never a claim that Tenshu-the-business has no
lawful relationship with its paying customers — obviously it does. A paying user makes a
**deliberate, informed disclosure** of identity + payment to a commercial counterparty. That
is a *different relationship in a different plane*, not a breach of the model.

So the design goal is **not** "billing knows nothing" (impossible, and unlawful for
commerce). It is:

> **The commerce plane holds the minimum the law requires, and that knowledge cannot be
> joined to any user's file data — not by Tenshu, not by a subpoena of one plane, and (in the
> strong variant, §3 D3) not even by compromising both planes at once.**

That line is clean, defensible to a user, and achievable.

---

## 2. The decomposition — split further than "dev accounts vs user accounts"

"Publishing service" is smooshing three concerns with different data, access patterns, trust
boundaries, and regulatory profiles. Separate them:

| Plane | Knows | Must never see | Regulatory profile |
|---|---|---|---|
| **Data plane — the relay** (`:sync-relay-server`) | opaque `ownerId`, ciphertext, public keys | identity, plaintext, payment | none (zero-trust; House Rule #1) |
| **Commerce plane — Accounts & Billing** (new) | real identity, payment ref, subscription/entitlement state, invoices | user data, ciphertext metadata, ideally the `ownerId↔account` link (§3 D3) | heavy: KYC/AML (jurisdiction-dependent), tax, consumer protection, GDPR/erasure |
| **Developer plane — App Registry / publishing** (likely [Rei](tenshu-rei.md)) | developer identity, app artifacts, signing keys, review state, payouts | end-user data; end-user billing | medium: developer payouts, marketplace rules — *consumer billing ≠ developer payouts* |

Two anti-conflations to hold onto:

- **Commerce ≠ developer.** Consumer billing and developer payouts are different counterparties,
  different money flows, different compliance. Keep them logically distinct even if co-deployed
  early, so the developer plane never inherits the consumer-PII blast radius (and vice versa).
- **This is not A18.** `control-app-registration.md`'s `AppRegistration` (A18) is the *local*
  set of apps a user has installed on their own device, gated by the IPC code-signing handshake.
  It has nothing to do with cloud accounts. Same word, unrelated plane.

The relay stays exactly as-is. Everything below is about standing up the commerce plane and
the **bridge** between it and the relay.

---

## 3. Decisions

### D1. Separate services, separate stores, separate trust boundary.

The commerce plane is its own service with its own database, deployed and secured
independently of the relay. It is **not** zero-trust toward its own customers (it can't be),
but it is firewalled from the data plane: it has no relay credentials beyond the bridge, no
access to ciphertext or to the relay's mailbox/metadata, and the relay has no read path into
it. A breach of one is not a breach of the other.

### D2. The bridge is a signed **entitlement token**, verified by the relay — the same shape Tenshu already uses.

The relay already authorises requests by verifying signed credentials it doesn't issue:
device-key JWTs (`sync-backend-relay-plan.md`) and the membership capability chain
(`shared-realm-membership-grant.md` D1). Entitlement is **one more such credential**:

- The commerce plane holds a signing keypair; the relay is configured with its **public** key.
- On a successful purchase/renewal, commerce issues an **entitlement token** — a signed,
  expiring assertion of the form *"the bearer is entitled to tier T (quota Q) until time E"*.
- The relay verifies the signature + expiry and enforces the quota. It learns the *tier*, not
  the *person*. No roster, no callback to commerce on the hot path (matches the relay's
  "never holds a roster" stance, GR D1).

This keeps the relay's verifier-only, stateless-auth posture and adds no plaintext-identity
surface to the data plane.

### D3. Privacy gradient on how the token is bound — target the strong variant, allow shipping the minimal one.

How the token links to a relay realm is the load-bearing privacy decision:

- **Minimal (ownerId-bound).** Token names the relay `ownerId`. Relay stays ciphertext-only,
  but commerce now stores an `account ↔ ownerId` mapping. Consequence: anyone who joins
  *both* planes (a breach of both, or a subpoena reaching both) can walk person → realm →
  ciphertext metadata (blob sizes, timing, device count). Simple; weaker.
- **Strong (blinded tokens).** Commerce issues a **blinded** entitlement token. Commerce
  knows "Alice paid for tier T"; the relay knows "some `ownerId` presented a valid tier-T
  token"; **neither alone — nor both colluding — can link Alice to a realm**, because the
  binding is blinded at issuance. This is the variant that survives the two planes being
  joined, and the one that actually matches the spirit of the no-directory decision
  (`contact-invitation-handshake.md`). **Scheme decided (Q1): blind RSA (RFC 9474), not a
  fuller anonymous-credential scheme** — the property we need is issuance-time unlinkability,
  not multi-show unlinkability, which the per-request device-key JWT discards anyway. See §9
  Q1 for the reasoning.

**Decision:** the strong variant is the target and the threat-model invariant (§6 I-CB-1) is
written against it. v1 *may* ship the minimal variant to unblock billing **iff** the
`ownerId↔account` link is called out as a known de-anonymisation vector in the amendment and
the token format is designed blinding-ready (so the upgrade is a token-issuance change, not a
relay-verifier rewrite). Don't paint the relay verifier into a corner that assumes a
plaintext `ownerId` claim.

### D4. Shrink the regulated footprint — be merchant-of-record-light.

The cheapest compliance is data you never hold. Use a payment processor / store billing as
the **merchant of record**:

- **Stripe (web/desktop):** Stripe holds card data (PCI scope stays with them); commerce
  stores only a customer token + subscription state + the invoices law requires.
- **App Store / Play IAP (mobile):** Apple/Google become merchant of record and handle
  VAT/sales-tax remittance and most consumer-protection surface; you receive an entitlement
  signal, not card data.

This can take the commerce plane from "we run a PCI/KYC operation" to "we store an email, a
processor customer ID, an entitlement record, and statutory invoices." Less stored PII = less
to leak *and* a smaller regulatory surface. (KYC/AML obligations are jurisdiction- and
threshold-dependent and may still attach above certain volumes — confirm with counsel; this
doc is not legal advice. None of those obligations *require linking* the two planes.)

### D5. Entitlement is the relay's only notion of "paid"; account lifecycle lives entirely in commerce.

The relay never learns about cancellations, refunds, chargebacks, or dunning. Those change
commerce state, which changes *which tokens get issued next*. A lapsed subscription simply
means no fresh token; the relay enforces the last token's expiry + a grace window (§4). This
keeps billing churn out of the data plane.

---

## 4. Entitlement token mechanics (sketch)

- **Issuance.** On purchase/renewal (or processor webhook: `invoice.paid`,
  `subscription.updated`), commerce mints a short-lived token (e.g. 24–72 h) asserting tier +
  quota + expiry. Short lifetime so revocation is mostly "stop reissuing"; no relay-side
  revocation list needed on the hot path.
- **Delivery (Q3 → client-pull).** Client fetches the current token from commerce
  (authenticated to commerce by the user's commerce session — *separate* from their relay
  device keys) and presents it to the relay alongside the existing device JWT. Pull keeps
  commerce off the relay's hot path and respects the D1 firewall (commerce-push would have to
  ride the relay or stand up its own push infra); see §9 Q3.
- **Verification + enforcement.** Relay verifies signature + expiry against the configured
  public key, reads the quota, and enforces it. **Enforcement gates writes, not reads (Q5):**
  uploads / mailbox bytes / content-blob bytes are gated on a live entitlement (the per-owner
  quotas §6 of the abuse work already wants); reads/downloads of already-stored data continue
  regardless of entitlement state. Over-quota → typed error (`core-error-channel.md` taxonomy),
  not silent drop.
- **Renewal / grace (Q5 → default 7 days).** Client refreshes before expiry with backoff. A
  bounded **offline grace** (token expired but within grace) avoids a momentary commerce outage
  locking users out — but because reads are never entitlement-gated, the stakes are low: grace
  only affects new writes. Default 7 days; tune later.
- **Free tier (Q2 → uniform token, sybil-gated issuance).** A self-serve no-payment free-tier
  token keeps the relay path uniform (always-a-token). Free-token issuance is protected by a
  sybil gate (proof-of-work / email verification / device attestation) — **not IP alone**,
  which over- and under-blocks — plus the per-token free-tier quota that bounds each token's
  worth. A high-privacy paid token can also be acquired via Rei (potentially Monero); the relay
  only checks the signature, so the payment rail is fully decoupled (§9 Q2 caveat: crypto rails
  make Tenshu merchant-of-record, losing D4's processor umbrella).

---

## 5. The regulated data, and the awkward GDPR ⨯ E2E corner

What the commerce plane legitimately stores: billing identity + contact, processor customer
ID + subscription state, statutory invoices/tax records (fixed legal retention), entitlement
history, and consent/audit records. All of it is *commercial* data about a customer — none of
it is *user content*, and none of it needs the `ownerId` link in the strong variant (D3).

The corner worth stating explicitly: a **GDPR erasure request** ("delete my data") is
satisfiable in the commerce plane the normal way (subject to invoice-retention law). It
**cannot** reach into the data plane to "delete the user's files," because the relay can't
identify the user's realm from the account (strong variant) and can't read content anyway —
erasure of E2E data is the user deleting their own keys/realm, which is already how I7
recovery and tombstones work. This is a *feature* to document for the DPA, not a gap: Tenshu
is structurally incapable of reading or selectively erasing content, by design.

---

## 6. Threat-model deltas (proposal-level; tighten per phase)

A full amendment lands with the implementing phase. Sketch:

**New invariant.**
- **I-CB-1 (plane unlinkability).** The commerce plane's identity/payment records cannot be
  joined to a relay realm (`ownerId`) or to ciphertext metadata. *Strong variant:* holds even
  against compromise of both planes. *Minimal variant (v1 carve-out, if taken):* holds against
  compromise of either plane alone, **not both** — documented de-anonymisation vector.

**New assets** (candidate numbers — current threat-model max is A27; confirm on landing):
- **A28 — commerce identity & payment set:** billing identity, contact, processor customer
  ID, subscription/entitlement state, invoices. Real PII; the first in the system. Lives only
  in the commerce plane.
- **A29 — the commerce signing keypair:** signs entitlement tokens. Private key is a commerce
  secret; the relay holds only the public key. Compromise = forged entitlements (a billing/DoS
  problem, **not** a confidentiality break — a forged token still can't decrypt anything).
- **A30 — the account↔ownerId link** (minimal variant only): the join that I-CB-1 strong
  eliminates. If the minimal variant ships, this is the asset whose existence is the carve-out.

**New attack surface** (candidate #13): the commerce↔relay bridge — token issuance endpoint,
the relay's token verifier, and the client's token-fetch path.

**New threat group** (candidate §8.15, T-CB-*), e.g.: forged entitlement token (mitigated by
A29 signature), token replay across owners (binding + expiry), commerce-plane PII breach
(blast radius bounded by I-CB-1 + D4 footprint reduction), correlation via the
`account↔ownerId` link (minimal-variant residual), processor-as-subprocessor trust (§7).

---

## 7. Honest limits

- **Stored-data unlinkability ≠ network unlinkability.** Even with blinded tokens, *traffic*
  correlation (user pays Stripe, then hits the relay from the same IP minutes later) can link
  the planes. Defeating that needs mixing/delay/relay-side IP blinding and is **out of scope
  for v1** — say so explicitly rather than imply the blind token buys it.
- **The payment processor is a subprocessor.** Stripe/Apple/Google see real identity + payment
  by definition. That's an accepted, disclosed third party (privacy policy + DPA), not a relay
  trust regression — but it is *a* party, and worth naming.
- **Not legal advice.** KYC/AML thresholds, tax registration, invoice retention, and DPA
  specifics vary by jurisdiction; confirm with counsel. The architecture's only hard claim is
  that none of those obligations require linking the commerce and data planes.

---

## 8. Phasing (each independently shippable)

1. **Plane carve-out + entitlement-token contract.** ✅ **Landed**. Define the
   token format (blinding-ready per D3), stand up the relay verifier against a configured public
   key, gate nothing yet (issue a permanent free-tier token). Pure addition; no commerce service
   required to test the relay side.
2. **Enforcement** keyed off the token, in three slices:
   - **2a — write-gating + owner-binding.** ✅ **Landed** (`ENTITLEMENT_ENFORCE`, opt-in). The relay
     gates writes (`/sync/push`, content-blob `PUT`, share + grant drops) on a valid, live,
     **owner-bound** token (typed `402`/`403`); reads stay ungated (§9 Q5); the T-CB-02 binding
     check is now load-bearing. Defaults off, no-op without an issuer key, so existing relays are
     unaffected until clients present a token (2c).
   - **2b — per-owner quota.** ✅ **Landed.** A content-blob `PUT` is metered against the token's
     `quota`: a new chunk that would push the owner's stored ciphertext over the tier quota is
     rejected `507 over_quota` (`bytesUsed(owner)` computed from the content store, a re-PUT of an
     existing ref never re-counts, the retention GC frees the quota). Scoped to the content plane
     (the durable file bytes the quota is sold against) — folds into the abuse-resistance work
     (`deploy-relay-digitalocean.md` "DDoS & abuse", per-owner *max content bytes*). Mailbox-bytes
     and share-chunk quotas remain follow-ups; `/sync/push` is intentionally not byte-rejected (it
     would stall CRDT convergence).
   - **2c — client token-fetch + presentation.** ✅ **Landed.** The client pulls the token from
     `:commerce-service` (`POST /commerce/entitlement/token`, §4 Q3 client-pull) via
     `CommerceEntitlementTokenClient`, caches it off the hot path in a `CachingEntitlementTokenProvider`
     (owner-keyed, single-flighted, TTL from the token's own `exp`), and presents it on the relay's
     gated **writes** via `X-Entitlement` — wired into `RestSyncRelay` (push, grant drop),
     `RestContentBlobStore` (content `PUT`), `RestShareRelayClient` (share + chunk). Scoped to the
     **personal** realm (the owner's own token); shared-realm `perRealmDeviceRelay` writes stay
     unentitled (a cross-user member's identity must not link to the owner's realm — Q30; shared-realm
     billing is deferred). No client-side dev token: the issuer seed lives only in `:commerce-service`,
     so a mock/unconfigured run (`TENSHU_BILLING_MOCK` default-on) presents no header. With 2b's quota
     also landed, the relay can now flip `ENTITLEMENT_ENFORCE=true` — writes carry the credential.
3. **Commerce service v1 (minimal variant):** accounts + Stripe/IAP integration +
   webhook→token issuance, `ownerId`-bound. Ships paid storage. A30 carve-out documented.
4. **Blinded tokens (strong variant):** swap token issuance to a blind-signature/Privacy-Pass
   scheme; retire A30. Relay verifier unchanged if Phase 1 kept it `ownerId`-claim-agnostic.
5. **Developer plane:** specced separately (likely under [Rei](tenshu-rei.md)) — developer
   accounts, payouts, marketplace. Out of scope here beyond the §2 boundary.

---

## 9. Open questions & decisions

Resolutions settled 2026-06-14 (maintainer discussion). Q6 remains genuinely open.

- **Q1. Blind-token scheme. → Decided: blind RSA (RFC 9474). No anonymous credentials.**
  The non-obvious reason: the privacy property we need is *issuance-time* unlinkability
  (commerce must not recognise the token later, and the token must not name the commerce
  account), **not** unlinkability across shows. The relay already sees the device-key JWT on
  every request — a stable per-device pseudonym — so it can already link a device's repeated
  requests to each other regardless of the entitlement token. A fuller anonymous-credential
  scheme (BBS+, CL) buys multi-show unlinkability that the device JWT immediately discards,
  while costing pairing curves / RSA-group accumulators, per-show ZK proofs, audit difficulty,
  and weak KMP/JS library support. Blind RSA gives exactly the property we keep: the client
  blinds a token asserting `ownerId=X, tier=T, expiry=E`, commerce signs the blinded form
  without seeing X, the client unblinds and presents it alongside the device JWT; commerce
  cannot link the signed token to the one the relay sees. Revisit anonymous credentials only
  if a future requirement hides the device↔realm link itself (a much larger change than this
  plane).
  - **Rotation** is driven by expiry/renewal, not security. A subscription extension (e.g. a
    1-month buy followed by a 12-month renewal) just means the next token fetch carries the new
    expiry; with the 24–72 h token lifetime (§4) the client re-fetches every couple of days
    anyway, so renewal flows in with no separate rotation mechanism. The one security-driven
    rotation is of the **commerce signing key (A29)** — infrequent, key-id-tagged, relay holds
    current+previous public keys during rollover. Keep the two notions of "rotation" distinct.

- **Q2. Free-tier modelling. → Decided: uniform token path (a no-payment free-tier token),
  with a sybil gate on free-token issuance — not IP alone.** A uniform always-a-token relay
  path is simpler to implement and keeps enforcement uniform. The real anti-abuse lever is the
  **cost of obtaining a free-tier token**, not the IP: IP rate-limiting over-blocks (CGNAT /
  university / corporate NAT puts many legit users behind one IPv4) and under-blocks (IPv6 /64s
  and botnets give attackers many addresses), so it is a coarse outer layer only. The
  load-bearing defence is a sybil gate on free-token issuance (proof-of-work, email
  verification, or device attestation) plus the per-token quota the entitlement already encodes
  (which bounds what any single free token is worth). High-privacy buyers can acquire a paid
  token via Rei (potentially Monero) — the relay doesn't care *how* a token was minted, only
  that it is validly signed, so this is just another issuance front-end with zero relay
  changes. Caveat: a crypto-payment path makes Tenshu merchant-of-record itself (it loses the
  Stripe/Apple compliance umbrella of D4), so it carries its own KYC/tax surface; acceptable
  for a low-volume privacy-max tier but flag it.

- **Q3. Token delivery. → Decided: client-pull.** Push isn't merely harder, it's wrong here:
  commerce is firewalled from the relay (D1), so the only existing client channel commerce
  could push over is the relay itself — which would couple the planes and break the firewall —
  and the alternative is commerce standing up push infra (APNs/FCM/websockets) it doesn't have.
  Pull keeps commerce off the relay's hot path and authenticated directly to the user's
  commerce session. Client refreshes before expiry with backoff, backed by the Q5 grace window.

- **Q4. Developer-plane home. → Leaning: its own service; home deferred.** The §2 boundary is
  decided; the deployment home can be settled when the developer plane is specced (likely under
  [Rei](tenshu-rei.md)).

- **Q5. Offline-grace window. → Largely defused by gating writes, not reads.** Gate *writes*
  (uploads / mailbox bytes) on a live entitlement but let *reads/downloads of already-stored
  data* continue regardless — the data is already encrypted and stored, and deleting it is a
  separate retention decision, not an enforcement one. With that split, an expired token during
  a commerce outage never locks a user out of their own data; it only pauses new uploads, so
  the grace window is low-stakes. Default **7 days**: rides out any realistic multi-day commerce
  outage, but a genuine cancellation stops mattering within a week. Tune later.

- **Q6. Minimal variant — ship it or wait?** *Still open.* Does paid storage launch on the
  `ownerId`-bound minimal variant (accepting the A30 carve-out) before the blind scheme is
  built, or do we hold launch for D3-strong? Now that Q1 is settled to blind RSA — a
  well-specified, KMP/JS-portable primitive rather than a heavyweight anonymous-credential
  build — the cost of going straight to the strong variant is lower than this question
  originally assumed, which should weigh into the answer.
