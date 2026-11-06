# Tenshu Publisher Service — Paid-Tier & Entitlement Boundary

**Status:** Draft v0.1 — **superseded-in-part** by
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) (2026-06-13).
*Retired:* the monolithic single-Publisher framing, the entitlement-as-*Rei*-record assumption, and
the realm-record-as-enforcement-carrier idea (§3.2). *Still valid:* the §10 mock-first slice as
consumer-side scaffolding — much of it has since landed (see the reconciliation note §0.1). The
canonical architecture is now the three-plane decomposition in
[`commerce-billing-plane.md`](commerce-billing-plane.md); read the reconciliation note before treating
this doc as current.

**Scope:** A *new component* (call it the Publisher service) that lives outside both
the relay and the user's devices, holds the email-↔-`ownerId` mapping needed for
billing, talks to payment providers (Stripe / IAP / Play Billing), mints signed
**Rei Entitlement** records, and publishes them to Tenshu Rei. The relay never
gains a "user concept"; the user's devices never see the user's payment
identifiers. This doc captures the boundary, sketches the API, and enumerates
open questions before sizing implementation.

---

## 1. Motivation

After the email-auth removal (`Slice 14`) and the device-management refactor,
nothing on the relay or in the user's realm knows the user's email, payment
method, or any third-party-auth identity. That's the point.

But Tenshu Drive (and every future paid Tenshu app) eventually needs:

- A way to charge users.
- A way for the relay to know which `ownerId`s are entitled to which paid tiers.
- A way to send users receipts, security alerts, billing notices.

These needs require *some* component that knows the user as a billable entity.
The architectural question is: where does that component live, and what's its
trust boundary?

The answer is **a separate service**, scoped narrowly. One per publisher (Drive's
publisher is Anthropic; third-party app sales would each have their own
publisher). The relay stays in the AD5-by-default zero-trust posture; the
Publisher accepts a different trust model (it knows email and payment, by
necessity) but is fully sandboxed from sync data.

---

## 2. Trust Boundaries

| Component         | Knows                                                                                                        | Doesn't know                                                    |
|-------------------|--------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| **Relay**         | block traffic, ownerIds, device public keys, ephemeral pairing state, recovery blob (encrypted)              | email, payment, plaintext block contents, master keys           |
| **Rei**           | per-`ownerId` entitlement records (publisher-signed); per-publisher signing keys                             | email, payment, sync data, user PII                             |
| **Publisher**     | email, payment-method ref (Stripe customer id / IAP receipt), `ownerId`, subscription state, support tickets | sync data, plaintext block contents, master keys, recovery code |
| **User's device** | master key, sync state, current entitlements (read from Rei sync)                                            | other users' anything                                           |

A breach of the Publisher leaks "who paid for what" — a real but bounded privacy
loss. It doesn't compromise sync data, can't impersonate users on the relay,
can't bypass volume keys, can't decrypt anything. Same posture-class as a
breached relay (AD5), applied to a different component with a different threat
profile. The threat-model needs a new `§A11. Publisher State` asset entry and a
`§7` attack-surface entry; sized in §6 below.

---

## 3. Sketch of the Service

### 3.1 Endpoints (illustrative — not pinned)

```
POST /checkout
  body: { ownerId, productId, returnUrl }
  resp: { sessionUrl }                 // Stripe checkout URL or IAP product id
  notes: pre-checkout step; user enters/scans their ownerId in the app, the
         app POSTs here, the response opens the payment provider's flow.

POST /webhook/stripe
  body: Stripe webhook payload
  resp: 200 OK
  notes: receives payment-confirmed events; verifies Stripe signature; mints
         a Rei Entitlement record; publishes it (see §3.3).

POST /webhook/appstore
POST /webhook/playstore
  similar shape; IAP receipt verification on the publisher side.

GET /entitlements/:ownerId
  resp: { entitlements: [...] }
  notes: publisher-side mirror of the user's current Rei entitlements; useful
         for support and admin views. The user's device gets the same data
         via Rei sync, not via this endpoint.

POST /support/contact
  body: { email, ownerId, message }
  notes: support ticket binding; out of scope for v1.
```

### 3.2 Rei Entitlement record (illustrative)

```kotlin
interface Entitlement : Composite {
    val ownerId: TString             // user's master-key derivation
    val publisherId: TString         // signing key id of the publisher
    val productId: TString           // e.g. "tenshu/drive/premium"
    val validFrom: UnixInstant
    val validUntil: UnixInstant
    val publisherSignature: TByteArray
}
```

Synced through the existing Rei mechanism (whatever that turns out to be —
likely an extension of the sync infrastructure, with publisher-signed records
treated like any other Syncable entity but with signature-by-publisher
verification on merge). The relay reads entitlements alongside other Rei
records to enforce per-tier quota.

### 3.3 Flow

1. App displays plans; user taps "Subscribe to Premium."
2. App POSTs `/checkout` with `ownerId` (derivable from the user's master key
   without ever exposing it).
3. Publisher returns a checkout URL or IAP product handle.
4. User completes payment in Stripe Checkout / Apple IAP / Play Billing.
5. Payment provider POSTs `/webhook/...` to the Publisher.
6. Publisher verifies the receipt, records `(ownerId, productId, ...)` in its
   own DB, and mints a signed `Entitlement` Rei record.
7. Rei propagates the record to the user's devices (and the relay).
8. The relay reads the new entitlement on the user's next push and unlocks the
   paid quota.
9. The user's app sees the entitlement in its Rei sync and updates the UI
   ("Premium active until ...").

### 3.4 Per-publisher deployment

The Drive Publisher is *your* infrastructure for *your* paid product. Every
third-party app developer who sells through Tenshu Store runs their own
Publisher — same shape, different signing key. All publish to the same Rei. The
relay doesn't care which publisher signed any particular entitlement; it just
verifies the signature against the Rei-known publisher-key registry.

---

## 4. Why Not …

- **Why not put this in the relay?** Because then the relay knows email, and
  the zero-trust posture collapses. Per `sync-relay-api.md` the relay is
  deliberately user-blind.
- **Why not have each app talk to Stripe directly from the device?** Because
  Stripe webhooks need a stable HTTPS endpoint to land on, IAP receipt
  verification benefits from server-side validation against Apple/Google's
  servers, and refund/dispute flows need somewhere to record state. A
  device-only solution doesn't have a stable callback surface.
- **Why a per-publisher service rather than one shared one?** Decentralised
  app store: each developer owns their billing relationship with their users.
  Anthropic doesn't want to be the billing intermediary for every app on
  Tenshu Store; third-party developers don't want Anthropic seeing their
  customer list. Per-publisher is the federated answer.

---

## 5. Open Questions (must resolve before sizing)

- **How does Rei actually work?** This doc assumes Rei is a record-distribution
  layer with publisher-signed entries. The whitepaper sketches Rei as a
  six-layer DLT family but no concrete protocol exists today. Until Rei has a
  spec, the Publisher service can only mock it (publish to a Tenshu-internal
  side channel that *acts like* Rei).
- **`ownerId` capture at checkout.** The user types or pastes their `ownerId`,
  or the app deep-links to a checkout URL with `ownerId` baked in. Both
  approaches have UX trade-offs (typo risk vs. deep-link availability per
  platform). Pick before designing the UI.
- **Publisher signing key custody.** The Publisher's private key signs every
  Entitlement. Where does it live (HSM? KMS? plain disk?), how does it rotate,
  what happens on compromise? Standard key-management problem; needs a
  dedicated section once concrete.
- **Refunds / dispute flows.** Stripe and IAP both have refund mechanisms. The
  Publisher needs a "revoke this Entitlement" record (a Rei tombstone of an
  entitlement, mirroring the `DeleteBlock` pattern in
  [`docs/sync/design.md`](../sync/design.md) §2.6). Sized when refund flows land.
- **App Store / Play Billing constraints.** Apple requires IAP for digital
  goods on iOS. The Publisher needs to handle Apple's receipt format and
  obey App Store Review rules. Real engineering work; out of scope for v0.1.
- **Tax compliance.** Stripe Tax / Apple's tax handling / per-region VAT.
  Boring but real; sized when commercial launch is concrete.
- **Identity binding for support.** When a user emails support, the support
  agent needs to look up the user. Email is the natural lookup key in the
  Publisher's DB, but support must not be able to authenticate as the user.
  Standard support-system problem; design when concrete.

---

## 6. What This Means for the Threat Model

A new asset and a new attack surface in `docs/sync/threat-model.md`:

- **A11. Publisher State.** Email, payment-method references, ownerId-↔-email
  mapping, subscription history. Lives on Publisher infrastructure; neither
  the relay nor user devices see it.
- **§7 attack-surface 9 (new).** Publisher infrastructure (database, webhook
  endpoints, signing key custody).
- **New threats:** publisher breach (privacy leak, bounded — see §2 above);
  forged Entitlement (defended by publisher-key verification on Rei merge);
  Publisher refuses to honour a paid subscription (out-of-protocol; user
  recourse via support / chargeback).

These amendments land alongside the first concrete Publisher implementation;
no need to amend the threat model speculatively.

---

## 7. Implementation Order (rough)

1. **Spec Rei properly** — separate from this doc; a `tenshu-rei.md` proposal
   sized when concrete. Until Rei has a real shape, the Publisher mocks it.
2. **Publisher service skeleton** — Ktor service, in-memory state, stubbed
   Stripe/IAP webhooks, mints unsigned mock-Rei records. Local-only.
3. **Subscription UI in Drive** — extend
   [`control-onboarding-ux.md`](control-onboarding-ux.md) with a Settings →
   Subscription section; mock checkout flow; entitlement-arrival UX.
4. **Real Stripe / IAP integration** — webhook signature verification, real
   product catalogue, real receipt validation.
5. **Real signing-key infrastructure** — KMS or HSM for the Publisher's
   private key.
6. **Threat-model amendment** — apply §6 above when the first real Publisher
   ships.
7. **Quota enforcement on the relay** — relay reads Rei entitlements when
   sizing per-owner storage limits.

Steps 1–3 are the real near-term path; 4–7 land when commercial concerns are.

---

## 8. Sequencing With Other In-Flight Work

- **Independent of the sync proposal cluster** (`sync-relay-api.md`,
  `sync-merge-strategies.md`, plus the landed tombstone +
  synced-device-management mechanisms documented in
  [`docs/sync/design.md`](../sync/design.md) §2.2 + §2.6).
  Those reshape the sync protocol; this doc is
  about the layer above, where commercial concerns live.
- **Pairs with [`control-onboarding-ux.md`](control-onboarding-ux.md)** and
  [`web-onboarding-ux.md`](web-onboarding-ux.md) — both need a Subscription
  section once this design firms up.
- **Pairs with [`milestones.md`](../milestones.md)** — the App Console &
  Tenshu Store milestone makes the per-publisher model concrete.
- **Depends on a real Rei spec** before anything beyond mock implementation
  can ship.

---

## 9. What's Out of Scope for This Doc

- The Rei protocol itself (record distribution, consensus, key registry).
- The full UI flow for subscriptions (lives in
  [`control-subscription-ux.md`](control-subscription-ux.md), which consumes
  this doc's Phase 1 mock surface in §10 below).
- Per-region pricing, tax handling, regulatory compliance.
- Multi-publisher trust governance ("how does the platform decide which
  publishers' signatures the relay trusts?") — large topic, sized when the
  Tenshu Store milestone is concrete.
- Subscription gifting, family sharing, education discounts — all real
  product features but well beyond v0.1.

---

## 10. Phase 1 — Concrete Mock-First Slice

**Status:** Spec'd; not implemented.

§7 sketches a 7-step roadmap. This appendix pins the v1 slice — what's the
smallest implementation that lets [`control-subscription-ux.md`](control-subscription-ux.md)
ship and be exercised end-to-end without a deployed Publisher, real Stripe
account, or real Play product. The intent is to unblock the UX track while the
real billing infrastructure (steps 4–6) lands on its own timeline.

### 10.1 What v1 commits to

| Component               | v1 commitment                                                                                                                                                                                 |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Product catalogue       | One product, one tier: `tenshu.drive.basic` at €2.99/month, 50 GB quota. Hardcoded in client and Publisher; no `/products` endpoint reads.                                                    |
| Payment provider        | None real. Mock-only, behind compile-time flag `TENSHU_BILLING_MOCK=1` (on by default in dev builds, off in release).                                                                         |
| Publisher service       | New Gradle module `:tenshu-publisher`, Ktor on Netty (same shape as `:sync-relay-server`). Runs locally during dev. Endpoints: `/checkout`, `/webhook/dev`, `/entitlements/:ownerId`.         |
| Publisher state         | In-memory `Map<ownerId, List<Entitlement>>`. Persistence is v2; v1 loses state on restart, which is fine because the entitlements also live in the user's realm.                              |
| Entitlement signing     | Hardcoded dev key (Ed25519, committed in `tenshu-publisher/src/test/resources/`). Insecure by design — release builds reject this key. KMS / HSM is step 5 of §7.                             |
| Entitlement publication | The Publisher writes the entitlement directly into the user's realm via a new sync block, signed with the dev key. Mirrors how the recovery blob is delivered today. **No real Rei in v1.**   |
| Quota enforcement       | None. The client's UI honours the entitlement (shows the new tier in Settings → Storage); the relay does not yet read entitlements when sizing per-owner quotas. Honour-system. Step 7 of §7. |
| Refunds / revocation    | None. A user who needs a refund contacts support; the platform-side refund happens manually. Sized in §5 open questions.                                                                      |

> **Cross-device convergence + the local-first bootstrap.** How the §10.2
> `Entitlement` actually reaches *every* paired device — and why the ADR 0019
> local-first personal-realm sync gate deadlocks a freshly-paired joiner's first
> pull unless the gate is made owner-scoped — is designed in
> [`storage-entitlement-sync.md`](storage-entitlement-sync.md). That doc keeps
> this §10's "entitlement is a signed record written into the user's realm" model;
> it adds the device-side provider swap and the `joinedFundedRealm` gate fix.

### 10.2 Schema additions

One new composite in `:identity-service` (closest existing home; could move to
a new `:entitlement-service` if the v2 surface justifies it):

```kotlin
interface Entitlement : Composite {
    val ownerId: TString
    val publisherId: TString          // dev key id in v1
    val productId: TString            // "tenshu.drive.basic"
    val validFrom: UnixInstant
    val validUntil: UnixInstant
    val publisherSignature: TByteArray
}
```

One new operation:

```kotlin
interface GetCurrentEntitlements : Operation<List<Entitlement>>
```

The `MergerRegistry` (per `sync-merge-strategies.md`) gains an entry for
`Entitlement` that verifies `publisherSignature` against the known publisher
key registry on merge. v1 ships with one hardcoded publisher key; v2 turns
the registry into a Rei-synced store.

### 10.3 The mock surface (what dev builds wire up)

Per [`control-subscription-ux.md`](control-subscription-ux.md) §8, with
`TENSHU_BILLING_MOCK=1`:

1. The product picker reads from a hardcoded `MockPublisherClient` (same shape
   as the real `PublisherClient`, returns the one v1 product).
2. The Android Play Billing handoff and the Desktop Stripe browser handoff are
   both replaced by a dev-only screen: *"Mock checkout — pretend the user
   paid?"* with `[Yes, pay]` / `[No, cancel]`.
3. On `[Yes, pay]`, the `MockPublisherClient` calls into an in-process
   `MockPublisher` (bypassing the network entirely) which:
    - Synthesises a `PaymentConfirmed` event.
    - Mints a self-signed `Entitlement` with `validUntil = now + 30 days`.
    - Writes it directly into the user's realm via the sync block path.
4. Control's existing sync poll picks up the new entitlement; UI advances to
   §7 of the subscription doc.

A long-press on the mock pay button opens a dev menu for triggering the §6
failure modes (decline, network error, entitlement-never-arrives) without
needing fault injection in lower layers.

### 10.4 What v1 deliberately does not do

- **No real Stripe account.** No webhook endpoints actually verify Stripe
  signatures. No `/webhook/stripe` endpoint exists; only `/webhook/dev` does.
- **No real Play Console product.** The Android client compiles against the
  Play Billing Library API but never calls `launchBillingFlow(...)` in v1
  release builds — there's no v1 release build, only dev builds. When real
  release happens (v2), the mock flag flips off and the real Play surface
  gets wired.
- **No deployed Publisher.** The Publisher service runs `:tenshu-publisher:run`
  locally for development and tests; production deployment is part of
  `sync-backend-relay-plan.md` Phase 4 (which already covers the relay's
  deployment story; the Publisher rides the same infra track).
- **No persistence.** State lives in memory; restarting the Publisher loses
  the side record. Fine for dev because the user's realm has the entitlement
  too. Persistence (Postgres) is v2.
- **No threat-model amendment yet.** The Publisher infrastructure entry
  (§A11, §7-attack-9) lands when a *real* Publisher ships. v1's mock has no
  attack surface beyond what the dev build already exposes.

### 10.5 Build order for v1

Sized roughly in single-PR units:

1. **Schema**: `Entitlement` composite + `GetCurrentEntitlements` op +
   `MergerRegistry` entry. Tests.
2. **Publisher module skeleton**: `:tenshu-publisher` Gradle module, Ktor
   server, `/checkout` returning a mock `sessionUrl`, `/webhook/dev` synthesising
   a `PaymentConfirmed`, in-memory `Map<ownerId, List<Entitlement>>`.
3. **Publisher → realm bridge**: when `/webhook/dev` fires, the Publisher
   posts a sync block containing the new entitlement, signed with the dev key,
   addressed to the relay.
4. **Client `PublisherClient` interface + `MockPublisherClient`**: behind the
   `TENSHU_BILLING_MOCK` compile flag. No real implementation yet.
5. **UI**: implement [`control-subscription-ux.md`](control-subscription-ux.md)
   §3, §4 (mock variant), §6 (failure modes), §7 against the mock client.
6. **Settings → Storage**: implement `control-subscription-ux.md` §5 read-only
   view of the current entitlement.
7. **Onboarding integration**: wire the cross-link in
   [`control-onboarding-ux.md`](control-onboarding-ux.md) §4b.5 → §4b.6 to
   route through the new subscription flow with a single skip path.

Steps 1–3 can be a single PR (Publisher + schema + bridge land together, since
they don't make sense individually). Steps 4–7 are separable but small enough
to land sequentially without much overhead.

After v1: real Stripe + real Play wiring is the next big chunk (steps 4–6 of
§7); production Publisher deployment pairs with relay Phase 4; threat-model
amendments land at the same time.
