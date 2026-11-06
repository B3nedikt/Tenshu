# Commerce accounts & sessions — real email registration, self-MoR billing

**Status:** v0.7 — **implemented through Phase 4 incl. client UI** (2026-07-02); **Ph4d specified
2026-08-09, WC-1 shipped the same day** (§9.4d). Shipped: Ph1
tier simplification → Ph2 accounts + email-OTP + session + Postgres → Ph2b admin
console → Ph3a dev-key release guard → Ph3b device account/session flow →
Ph4 server-side Stripe money path, EU self-MoR → Ph4 client checkout-launch
UI (§9.4a) + manage-subscription / billing-portal UI (§9.4b). Issuer deploy tooling
(Terraform `deploy/digitalocean-commerce/` + redeploy script + how-to) has landed.
**Self-service account deletion (Play's requirement) shipped 2026-10-23 — §9.7.**
**Remaining:** **the live Stripe test-mode round-trip** — Ph4d **WC-1…WC-6 is COMPLETE**
(ledger completeness, the subscription field mapping, store reads, the purchases/account views, the
OSS export, admin MFA + the threat-model amendment owed since CE-5, and the chargeback→invoice link):
refunds and chargebacks now credit *and apportion*, `invoice` has a read path, an operator surface
*and* a per-country filing export, the console is two-factor, and ⚠⚠
**renewals now grant at all**: `invoice.paid` was skipped for a tier it reads from the wrong place, so
a paying customer got ~7 days and silently dropped to Free — §9.4d),
Ph4b IAP, Ph5 US sales tax, the Layer-2 `tenshu://checkout-result` deep-link, and the on-device pass
against a live `:commerce-service` with Stripe test-mode keys. Per-phase as-built notes live in the
§9 pins. Specifies the missing **account
lifecycle** layer of the commerce plane: real email registration + verification, a commerce
session, a durable account store, and the swap of the entitlement *mint authority* from the
Phase-1 email whitelist to a verified account. Closes the gap called out in
[`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md) §9
("commerce session … undefined") and Phase 3 of its §7.

> **v0.2 (2026-06-28)** reworked the flow ordering and recovery model after maintainer
> discussion: **payment/rail is chosen before registration**, the data captured is **keyed on
> the rail** (§3.1), **email is the universal recovery anchor on every rail including IAP**
> (§3.3 D6), the **purchase is a re-mintable artifact re-bindable to a new `ownerId`** after
> recovery-code loss (§3.4), and a **consent & data-handling** layer is specified (§5.1 D7).
>
> **v0.3 (2026-06-28)** added a minimal **internal admin console** (read-only registered-users
> view) at **`admin.tenshu.app`**, internal-only (§4.5 D8); established the **`*.service.tenshu.app`**
> host convention for backend services and pinned the issuer API to **`commerce.service.tenshu.app`**.
> Admin name + apex placement chosen to avoid collision with the developer-facing *App Console*
> milestone (`milestones.md`) and the services namespace.
>
> **v0.4 (2026-06-28)** added MoR follow-ups: an **operational-vs-statutory retention split**
> (append-only `invoice`/`acceptance` + WORM archive surviving account erasure, §5.2 D10) and a
> **security & abuse hardening** subsection (admin MFA, webhook-sig verify, rebinding step-up +
> owner-notification, OTP lockout, Stripe Radar — §5.3 Q6, Phase 4c). Auth baseline unchanged.
>
> **v0.5 (2026-07-02)** is a status sync, no design change: pinned the shipped phases (Ph1–Ph4
> incl. the §9.4a/§9.4b client UI) with their PR numbers and refreshed the §0 as-built table.
>
> **v0.6 (2026-07-14)** corrects the stated rationale for why the commerce session stays
> device-local, after a maintainer challenge. The old framing ("separate from the relay …
> firewall, D1") implied *relay exposure* was the reason — but realm sync is E2E-encrypted, the
> relay only ever sees ciphertext, and the `Entitlement` already rides that path, so syncing a
> session record through the realm would expose **nothing** to the relay. The real reason is a
> **different firewall — data-plane vs. billing/identity-plane** (defense-in-depth): a realm
> compromise must not also hand over live control of the payment account. New **§3.5** records the
> decision that falls out of this: **sync the account *descriptor* (plan) so every device shows
> the real plan + a *Change plan* control, but gate every action that touches billing/identity
> (sign-up, change/renew, portal, disconnect, redeem) behind a per-device email login.** See
> **D11**.

> **v0.7 (2026-08-09)** adds **§4.7 + D12**: the console gains purchase visibility and a
> period-bounded MoR export, **superseding D8's "no export"** — D8 weighed breach blast radius
> against "little ops value", but an OSS return is a *statutory obligation*, so it was the value
> side of that trade that was mis-stated. ⚠⚠ The reversal stays narrow because a VAT return needs
> **neither side of the A28 × A30 join**, so the export is built `ownerId`-free by construction.
> ⚠ Producer-side work lands first (WC-1): `ipCountry` is `null` on every invoice row today, and
> the ledger records only money *in*, so an export built before that over-states VAT owed. Phase
> **4d**, implementation notes in **§9.4d**.

**Owner:** Benedikt

**Builds on (already landed):** the `:commerce-service` issuer (mint + token routes), the
relay-side verifier + write-gating + per-owner quota, the synced `Entitlement` record + the
`publicKeyFor` registry, and the threat-model group A28/A29/A30 + I-CB-1 + T-CB-01–06.

**Consumes:** [`commerce-billing-plane.md`](commerce-billing-plane.md) (canonical architecture),
[`commerce-service-phase1-plan.md`](commerce-service-phase1-plan.md) (the built issuer),
[`control-subscription-ux.md`](control-subscription-ux.md) (the tier-picker / checkout UX this
revises), [ADR 0019](../adr/0019-storage-capacity-gating.md) + the `storage-service*` modules
(the on-device gate this feeds).

> **Read §0 (as-built) and §9 (Implementation Notes for Claude Code) first if you are about to
> write code.** §1–§8 are the rationale and decisions.

---

## 0. As-built baseline (verified against the tree, 2026-06-28; last row updated 2026-07-02)

| Piece | State | Where |
|---|---|---|
| `:commerce-service` issuer — `/commerce/entitlement/mint` + `/commerce/entitlement/token`, A29 signing key, config-backed **email whitelist**, stateless mint | **Built** | `commerce-service/src/main/kotlin/org/tenshu/commerce/` |
| Relay verifier + write-gating + per-owner quota (`ENTITLEMENT_ENFORCE`) | **Built** | `:sync-relay-server` |
| Synced `Entitlement` record + `EntitlementSignature.publicKeyFor(publisherId)` (dev + `tenshu.commerce` keys) | **Built** | `identity-service/.../identity/` |
| On-device gate (`hasStorage && fits`), `EntitlementProducts` catalogue, `EntitlementStorageProvider` | **Built** | `storage-service*` + `control-shared/.../data/` |
| `PublisherAccount` (`Plan.Free`/`Lifetime`/`Rei`; `IdentityMethod.Email`/`LifetimeStore`/`ReiKey`) + Account-picker / Lifetime-purchase screens | **Built, mock** | `control-shared/.../ui/screens/services/` |
| **Durable account store, email verification, commerce session, payment** | **Built since** (Ph2 store/OTP/session; Ph4 Stripe; client UI §9.4a/§9.4b — see §9 pins) | `commerce-service/` + `control-shared/.../services/` |

Two facts shape everything below. First, the issuer is **stateless and whitelist-gated**: the
only "account" is a comma-separated env var, and `/token` trusts a client-supplied `ownerId`
with no verification. Second, the **synced `Entitlement` record stays the device-convergence
carrier** (D2 of the reconciliation note) — this proposal does **not** replace it; it replaces
the *whitelist* as the thing that decides whether to mint.

---

## 1. Scope

**In:** the three-tier simplification; the "redeem a code" advanced path; the
**rail-before-registration** flow ordering (§3); **email-OTP registration + verification as a
universal recovery anchor** (every rail, including IAP); a durable Postgres account store on the
commerce plane; a commerce session; the **purchase re-mint / `ownerId`-rebinding** recovery
model (§3.4); a **consent & data-handling** layer (§5.1); the mint-authority swap (verified
account, not whitelist); a minimal **internal admin console** (read-only registered-users view,
§4.5); and the **self-MoR** compliance posture for a real Stripe integration, EU first.

**Out (named, deferred):** the actual Stripe wiring and webhooks (Phase 4 here — designed, not
built in the first slice); US sales-tax nexus (Phase 5); the *native* IAP purchase plumbing
(StoreKit / Play Billing receipt verification) on mobile/iOS — this doc fixes the **account +
email-anchor** model IAP plugs into, but the per-store purchase code rides the existing
`PublisherClient` seam later; blinded tokens / strong privacy variant
(`commerce-billing-plane.md` Phase 6); the developer plane / Rei.

---

## 2. Tier model — drop "Lifetime", ship three tiers + a redeem path

### 2.1 The change

`PublisherAccount.Plan` is currently `Free | Lifetime | Rei`. **Remove `Lifetime`.** It
conflates a *billing model* (one-time purchase) with a *tier*, and a 50 GB Lifetime sitting
next to a 50 GB Basic is pure confusion. The new model is three subscription tiers plus a
quiet redemption entry for advanced users:

| Tier | `productId` | Quota | Price (v1, EUR) |
|---|---|---|---|
| **Free** | `tenshu.drive.free` | existing `FREE_LIMIT_BYTES` (1 GiB in code today; `control-subscription-ux.md` says 5 GB — reconcile before ship) | €0 |
| **Basic** | `tenshu.drive.basic` | 50 GiB | €2.99 / month |
| **Premium** | `tenshu.drive.premium` | 2 TiB | €9.99 / month |

Note the collision the user flagged is real in code: `EntitlementProducts.LIFETIME` =
`tenshu.drive.lifetime` already maps to **exactly 50 GiB** (`LIFETIME_LIMIT_BYTES`) — i.e. a
"50 GB Lifetime" sitting next to a "50 GB Basic". Removing `LIFETIME` and folding its quota into
`Basic` is the clean resolution.

Premium is the new **top tier**: `CommerceConfig.DEFAULT_TOP_TIER_PRODUCT_ID` moves from
`tenshu.drive.pro` (50 GiB) to `tenshu.drive.premium`, and `DEFAULT_TOP_TIER_QUOTA_BYTES`
moves from 50 GiB to 2 TiB. The `Rei` plan/identity stays as the high-privacy escape hatch but
is **not** surfaced as a card — it is reached only via the redeem path (§2.3).

### 2.2 The picker (revises `control-subscription-ux.md` §3)

Three plan cards in a vertical stack — Free, Basic, Premium — each with name, quota, price, a
one-line scope description, and a per-card CTA (`[Continue]` for Free, `[Subscribe]` for paid).
The §3.2 comparison expander grows a Premium column; the §13 "no dark patterns" and "state the
price next to the scope" rules carry over unchanged.

### 2.3 "Have a code?" — the advanced redemption entry

Below the cards, a low-emphasis text link: **"Have a code?"** — *no marketing copy, no
explanation.* It opens a single input that accepts a **redemption code** and, on success, mints
the corresponding entitlement and skips checkout entirely. This is the one surface for:

- **Founder / debug grants** (generalises today's email whitelist — see §4.3).
- **High-privacy paid tokens** acquired out of band (the `Rei` / potential-Monero path of
  `commerce-billing-plane.md` Q2) — the relay only checks the signature, so the payment rail is
  fully decoupled.

The redeem path is the **one exception to the universal email anchor** (§3.3): it is deliberately
**identity-light** and skips registration entirely, because the only legal reason to capture
identity is a Stripe-paid EU invoice (§5) and a redeemed code is not a Stripe sale. This keeps the
advanced/privacy path clean and is the whole point of separating it from the mainstream flow.
(Trade-off, stated: a redeemed entitlement therefore has no email anchor, so its only recovery is
the code / out-of-band — acceptable for the advanced audience this path targets.)

---

## 3. Flow ordering, rails, and the universal email anchor

### 3.1 Rail before registration — the data we capture is keyed on the rail

The order is **tier → choose rail → rail-specific step**, *not* tier → registration → pay.
"Registration" is not one fixed screen: its role and required data depend on how the user pays.
Critically, two things that sound alike are **different rails**:

- **Card / Apple Pay / Google Pay *inside Stripe Checkout*** — these are payment *methods* (card
  wrappers). Stripe is the processor, **we are the merchant of record**, and we need the same
  data as a raw card (email + location evidence + invoice). No change to the data model.
- **App Store IAP / Google Play Billing** — the platform billing systems. **Apple/Google are the
  merchant of record**; they hold identity + payment and remit VAT. We receive a receipt →
  verify → mint. Minimal data on our side. (On iOS Apple has historically required IAP for
  digital goods; the DMA / recent US rulings are loosening this — re-verify when mobile is
  concrete. Current targets are Android + Desktop.)

| Rail | MoR | What we must store | Recovery anchor |
|---|---|---|---|
| Free | — | email (sybil gate) | email |
| Stripe (incl. Apple/Google Pay wallets) | **us** | email + location evidence + invoice (legal, §5) | email |
| App Store / Play IAP | Apple/Google | receipt/transaction id + `ownerId` | email *and* the store account |
| Redeem code | — | nothing (identity-light, §2.3) | the code itself / out-of-band |

### 3.2 Where the email comes from (per rail)

Because the email's *role* differs by rail, so does *when* we collect it — there is no single
mandatory email screen before everything:

- **Free** → email + OTP **up front**. There is no payment to anchor or gate the account, so the
  verified email *is* the sybil gate (`commerce-billing-plane.md` Q2). This is the one path where
  registration genuinely precedes everything.
- **Stripe** → **no pre-checkout email screen.** Stripe Checkout already collects the email (we
  need it for the invoice anyway); the webhook hands it back and we create the account *from* the
  payment. OTP verification then doesn't interrupt the purchase — it happens later, only when the
  user wants to *claim/re-bind* (§3.4). (Mistype risk is bounded: Stripe validates the address and
  emails its own receipt; we also send a one-tap claim mail post-purchase.)
- **IAP** → native purchase first (the store sheet collects nothing from us), then a
  **"link an email to recover this purchase"** step (D6) — see §3.3.
- **Redeem** → no email at all (§2.3).

In every case the client supplies its master-derived **`ownerId`** (the value `/token` already
takes) so commerce can bind `(account ↔ ownerId)`. That binding is the A30 minimal-variant link,
already a documented residual.

### 3.3 Email is the universal recovery anchor — even on IAP (D6)

*Decided:* an account links a **verified email on every rail, including IAP.** We could have let
the store account be IAP's sole anchor (native "Restore Purchases"), but a store-only anchor
locks the entitlement to one platform — a Play purchase can't be restored on the Stripe desktop
build — and leaves us unable to re-issue if anything upstream breaks. With email everywhere we
get **one uniform recovery model**, cross-platform portability, and a Tenshu-controlled way to
re-mint regardless of store/processor reachability.

So after an IAP purchase, Control prompts the user to verify an email and binds it to the same
account that holds the receipt. The store account remains a *second* anchor (Restore Purchases
still works); email is the *portable* one.

A verified email + the `ownerId` binding is the **commerce session** anchor (§4.2): how the
client later pulls fresh tokens (`/token`), reaches the Stripe customer portal, and re-claims a
purchase — authenticated to commerce and **separate from the relay device keys** (firewall, D1).

> The session is **per-device**, re-established by email login rather than synced across a
> realm's devices. Note this is **not** for relay-exposure reasons — a realm-encrypted session
> record would be ciphertext to the relay, exactly like the `Entitlement` we already sync. The
> reason is the data↔billing-plane firewall in **§3.5** (D11): don't let a realm compromise
> confer live control of the payment/identity account.

### 3.4 Not losing the purchase — re-mint and re-bind (the "Restore Purchases" of the Stripe path)

The entitlement is a **signed, re-mintable artifact** derived from the durable account ledger
(active Stripe sub / valid IAP receipt / redeemed code). The account — keyed by **email** — is
the source of truth; the `Entitlement` record bound to an `ownerId` is just a current projection
of it. Two recovery cases:

- **Reinstall *with* the recovery code** → the `ownerId` is restored, the existing entitlement is
  still bound to it, everything works. **No account interaction needed.**
- **Reinstall *without* the recovery code** (forgot it) → the realm and identity are gone (E2E
  data is unrecoverable by design), and the user starts a **new** identity with a **new**
  `ownerId`. The purchase was bound to the *old* `ownerId`, so to keep it the user proves control
  of the account and we **re-bind the entitlement to the new `ownerId`**: log in via email-OTP
  (Stripe path) or Restore Purchases (IAP) → commerce updates `account_owner` to the new owner →
  re-mints. This is the Stripe-path equivalent of "Restore Purchases."

This makes `account_owner` re-bindable, **not** a forever-fixed UNIQUE `(account, owner)` — the
old `ownerId` is dead after recovery-code loss. Policy: an account has **at most one live
`ownerId`**; re-binding supersedes the previous one (history retained for audit). The
operator-side "anything got messed up → re-mint" capability falls straight out of this: the
account ledger is authoritative, the signed record is cheap to regenerate. (Resolves the former
Q3.)

### 3.5 Cross-device visibility vs. billing actions — the data↔billing firewall (D11)

A second device in the same realm (a paired *joiner*) shares the realm's identity, so it already
pulls the owner's synced `Entitlement`. But it has no device-local commerce **account/session**
(that's `commerce_session`, written only on the device that signed in). Today it therefore falls
to the read-only *"storage active, managed on another device"* card — no plan name, no way to
manage the subscription from here. The user reasonably reads that as *"I'm not signed up."*

**Why not just sync the session so every device is fully signed in?** The intuitive objection —
"it's PII, keep it off the relay" — **does not hold**, and it's worth stating why so this isn't
re-litigated. Realm sync is E2E-encrypted; the relay sees only ciphertext; the `Entitlement`
(itself a commerce↔realm link, the A30 residual) already syncs through exactly that path. A
session record sealed under the realm key would expose **nothing** to the relay that isn't already
exposed. So the House-Rule-#1 / D1 "firewall" (which is about *the relay*) is simply not an
argument against syncing it.

**The argument that does hold is a different firewall: the data plane vs. the billing/identity
plane.** Reaching the billing portal lets a device cancel/modify the subscription, change the
payment method, and read **invoices bearing the user's legal name + address** (self-MoR, §5,
retained ~10 years). Today that account sits behind a *separate factor* — control of the
**email** (email-OTP) — so a realm compromise (a stolen paired device, a leaked recovery phrase,
a future shared-realm bug) yields the user's files but **not** live control of their money and
legal identity; that still requires their inbox, a distinct trust domain. Syncing a portable,
long-lived session credential into the realm collapses the two: the realm key becomes the single
thing guarding the payment account, and one compromise becomes total. This is the same posture
§3.4 / §5.3 already take, where sensitive account operations demand a **fresh email step-up,
"not just a live session."**

**Decision.** Split "seeing" from "acting", and put the login exactly on that seam:

- **Sync the account *descriptor*, not a credential.** Every device renders the **real plan**
  (Free / Basic / Premium) + quota from data it already holds — the plan tier is derivable from
  the synced `Entitlement`'s `productId`, so the minimal version needs **no new synced field and
  no new PII in the realm** (we deliberately do **not** need to sync the email to make this work;
  syncing a display descriptor is an optional later nicety, not required). The card stops being an
  anonymous "managed elsewhere" panel and becomes *"You're on Free — [Change plan]"*.
- **Gate every billing/identity action behind a per-device login.** Sign-up, change/renew plan,
  open the Stripe portal, disconnect the account, redeem a code — anything that mutates or reveals
  the billing/identity account — requires a fresh email-OTP session **on that device**. Looking at
  data that is already local does not.

The UX payoff is not just parity — it's *legibility*. The login becomes the visible boundary that
tells the user they are crossing from "reading my synced data" into "changing things on my
account". Sign-up and plan changes — the operations actually bound to money and identity — are the
ones that ask to authenticate per device; passive viewing of the entitlement they already have
does not. We are, in effect, just **visualising the synced `Entitlement`** honestly, and reserving
the credential step for the moments it actually protects something.

**Not-in-scope / non-goals of this decision:** syncing the session token or any portable
long-lived commerce credential through the realm (explicitly rejected above); removing the
email-login requirement for billing actions. A middle option — show *Change plan* everywhere but
back it with a one-tap OTP step-up at the moment of action — is consistent with this decision and
is the intended shape (it *is* the per-device login, surfaced lazily). This section fixes the
policy and its rationale; the first implementation **has shipped** (a descriptor-driven
Services card that names the real plan from the synced entitlement + a `[Sign in to manage]`
action that re-authenticates the existing account on this device via the email-OTP flow).

---

## 4. Backend — durable account store, verification, session, mint authority

The Phase-1 issuer is stateless. This phase gives `:commerce-service` its first persistence:
a **self-hosted Postgres** on the commerce trust boundary (decided — no external MoR/identity
vendor; see §5 and §8 D1). Postgres, not SQLite: concurrent server access, real backups,
statutory retention.

### 4.1 Schema (minimal — store only what law or function requires)

```
account(id, email_normalised UNIQUE, email_verified_at, created_at, status,
        rail,                       -- {free, stripe, iap_apple, iap_google, redeem}
        stripe_customer_id NULL, iap_original_txn_id NULL, display_name NULL)
account_owner(account_id, owner_id, bound_at, superseded_at NULL)
                                    -- the A30 link; re-bindable (§3.4). At most one row with
                                    --   superseded_at IS NULL per account = the live owner.
entitlement_history(account_id, product_id, valid_from, valid_until, source, issued_at)
                                    -- source ∈ {stripe, iap, redeem, free}
email_verification(email_normalised, code_hash, expires_at, attempts)  -- OTP, short-lived
redemption_code(code_hash, product_id, max_uses, used_count, expires_at)
session(token_hash, account_id, created_at, expires_at)   -- commerce session (§8 Q1)
acceptance(account_id, tos_version, privacy_version, accepted_at, ip)  -- §5.1, versioned
marketing_consent(account_id, granted BOOL, changed_at)   -- §5.1; default FALSE, separate basis
invoice(...)                                       -- §5; statutory, long-retained
```

Email lives **only** here (A28); it is never put in a mint response, never sent to the relay,
never written to a realm (House Rule #1). OTP codes and session tokens are stored **hashed**.
`rail` records which billing system owns the payment side (it does **not** change that email is
the recovery anchor — §3.3). The re-bindable `account_owner` is what makes purchase recovery
after recovery-code loss work (§3.4). **`invoice` + `acceptance` are append-only statutory rows**
with a different retention lifecycle than the rest (no `UPDATE`/`DELETE`, ~10-year, survive account
erasure, WORM-archived) — see §5.2.

### 4.2 New endpoints (alongside the built `/mint` + `/token`)

```
POST /commerce/account/register   { email, tosVersion, privacyVersion } → 202, sends OTP
POST /commerce/account/verify     { email, code, ownerId } → 200 { sessionToken } + binds owner
POST /commerce/account/claim      { email, ownerId }       → 202, sends OTP   (re-bind, §3.4)  ⚠ REMOVED 2026-10-13
POST /commerce/entitlement/redeem { code, ownerId }        → 200 (mints) | 403
POST /commerce/account/session    { sessionToken }         → 200 (refresh) | 401
POST /commerce/account/delete/request { sessionToken | email } → 202, mails a deletion code   (§9.7)
POST /commerce/account/delete/confirm { sessionToken | email, code } → 200 deleted | 401       (§9.7)
```

`register` is the **Free** path (and records the §5.1 acceptance). For **Stripe**, the account is
created from the `invoice.paid` webhook (§6) using Stripe's collected email — no pre-checkout
`register` call; the user later runs `claim` to verify + bind/re-bind. For **IAP**, the receipt
creates the account and the post-purchase email-link runs `register`/`verify` against the same
account (§3.3). `claim` + `verify` are also the **recovery** path: prove the email, re-point
`account_owner` to the new `ownerId` (§3.4).

⚠ **`/claim` was removed on 2026-10-13: no client ever called it.** `/register` already does both
jobs it was meant for — it finds an existing account and sends the OTP, and `/verify` supersedes the
binding — so sign-in *is* the recovery path (`OwnerRebindTest`). Sign-in-before-pay (§9.4f) removed
the other use: a Stripe buyer now has a session before the purchase, so nothing is left to claim.

`/token` and `/mint` are **re-gated**: they move from "whitelist email" to "valid commerce
session whose account holds a live entitlement." The whitelist survives only as the seed for
**redemption codes** (§4.3), not as a request gate. Same content-free `StatusPages` posture as
today; never echo which check failed.

### 4.3 Mint authority swap

Today `EntitlementMinter`/`EntitlementTokenMinter` mint for any whitelisted email. After this
phase they mint for an account that has a current entitlement in `entitlement_history`, written
by one of four sources: a verified-free registration, a redeemed code, a Stripe `invoice.paid`
webhook (§6), or a verified IAP receipt. The `EntitlementSignature.message(...)` bytes and the
`publicKeyFor` verify path are **reused verbatim** — the relay and on-device verifier are
unchanged, so this is purely a commerce-internal change.

### 4.4 Control-side: replace the in-process minter

`control-shared/.../data/EntitlementStorageInitialization.kt` currently self-signs with the dev
key via `DevSeedEntitlementMintClient`. Behind the existing `PublisherClient` seam, the writer
fetches the signed entitlement from `:commerce-service` instead; `MockPublisherClient` stays
gated on `TENSHU_BILLING_MOCK` for dev. (This is Phase 2 of the reconciliation note, pulled in
here because real registration needs a real mint.)

### 4.5 Admin console — minimal internal ops UI (D8)

A read-only window onto the account store, "enough to see the registered users." Deliberately
minimal; this is the highest-risk UI in the system because it is the one surface that shows
**A28 (real PII)** and the **A30 `ownerId↔email` join** in the clear.

**Host convention.** Backend services live under a dedicated **`*.service.tenshu.app`** parent —
distinct from the `*.tenshu.app` *user-app* subdomains (which exist for browser Same-Origin
isolation) and from the key-holding apex. So the issuer **API** host (`$COMMERCE_DOMAIN`) is
pinned to **`commerce.service.tenshu.app`** (mirrors the `:commerce-service` module name). Future
services follow suit (`sync.service…`, `logging.service…`, etc.). *Caveat:* never authenticate a
service with a cookie scoped to the shared `.service.tenshu.app` parent (it would be readable
across all services) — auth stays in bearer headers (device JWT, commerce session token), CORS
allowlists explicit. The canonical home for this convention is `web/architecture.md` §3.4; the
config-vs-code reality of adopting/renaming a host is §4.6 below.

**Where the admin UI lives.** `admin.tenshu.app` — the deliberate **odd one out**: it is an
internal *human* tool, not a machine service, so it sits at the apex level rather than under
`*.service.…`. It is **internal-only**, *not* a public app like the `*.tenshu.app` user
subdomains. Served by `:commerce-service` itself as a **guarded server-rendered route** (a tiny
HTML table — no React; House Rule #5 governs the *user-facing* frontend, and a separate Kotlin
runtime for an internal admin page isn't worth it). Distinct host from the API so the admin
surface can be firewalled independently. (Obscurity is not the control — the allowlist + admin
auth below are.)

**Access control (load-bearing, not hygiene).** Bind it off the public internet — allowlist / VPN
/ mTLS at the Caddy layer (mirror the relay's posture) — *and* require an **admin credential
separate from any user/commerce session** (a dedicated admin token / SSO; MFA recommended). It
must never share the issuer signing key (A29) or a user session token. Every admin page load is
**audit-logged** (who, when, what query). No public DNS exposure in v1.

**Scope v1 — read-only.** A searchable (by email / `ownerId`) list of accounts showing:
`email`, `email_verified_at`, `created_at`, `status`, `rail`, current `product_id` (tier) +
`valid_until`, entitlement `source`, and the live `ownerId`. A total count. **No mutation** —
no manual mint, refund, or delete from the UI in v1 (those stay ops/CLI or a later, separately
guarded phase). **No bulk export button** (raises the breach blast radius for little ops value);
columns are the minimum needed to answer "who is registered / what are they on."

**Threat-model.** This is a **new attack surface** on A28/A30 (the existing surface #13 is the
commerce↔relay *bridge*; the admin UI is a distinct internal surface). It lands with the code per
House Rule #2: a compromised or over-exposed admin console de-anonymises paying users (person →
`ownerId` → relay metadata, the A30 vector). The allowlist + separate-admin-auth + audit-log +
no-export posture is the mitigation; note it explicitly in the amendment.

### 4.6 Host convention & the relay retrofit — config vs code (not just deploy)

The `*.service.tenshu.app` convention (D9) is canonical in `web/architecture.md` §3.4. Adopting it
is **not** a pure deploy-config change — clients bake default service URLs, and the relay URL even
travels inside pairing payloads. Concretely, verified against the tree:

**Commerce (the new service — light).** No production host is baked in yet: the client reads
`TENSHU_COMMERCE_URL` / `-Dtenshu.commerceUrl` and currently defaults to a **dev loopback**
(`control-shared-proxy` Android + desktop `PlatformProxyModule`, the `CommerceEntitlementMintClient`
/ `CommerceEntitlementTokenClient` wiring). So pinning `commerce.service.tenshu.app` = deploy config
(DNS + Caddy `$COMMERCE_DOMAIN` + cert) **plus** one small code change: add the **production default
constant** as the fallback (mirroring the relay's `RelayConfig.RELAY_URL`), so release builds don't
fall back to loopback.

**Relay retrofit `relay.tenshu.app` → `relay.service.tenshu.app` (heavier — its own PR).** The relay
*does* have baked defaults and embedded URLs, so this is deploy **+ code + migration**:

| Touch-point | Where | Kind |
|---|---|---|
| `RELAY_DOMAIN=relay.tenshu.app` | `sync-relay-server/relay.env.example` + Caddy | deploy config |
| Desktop default `"https://relay.tenshu.app"` | `control-shared-proxy` desktop `PlatformProxyModule` | **code** |
| Android default `RelayConfig.RELAY_URL` | `control-shared-proxy` android `PlatformProxyModule` | **code** |
| Test asserts the literal host | `EpcPairingInviteTest` | **code/test** |
| Relay URL embedded in **EPC pairing payloads / QRs** | `relayBaseUrl` in pairing invites | **migration** |
| Web/JS relay URL via `JsRuntime.configure(relayBaseUrl)` | apex config → `:tenshu-sdk-js` | config (apex) |
| **Not affected:** `CORS_ALLOWED_ORIGINS` | relay | — (lists *app* origins, not the relay host) |

The **migration** row is the real catch: already-paired devices and already-shared QR/links carry
the old `relay.tenshu.app`, so a rename needs the old host kept as a **DNS alias / 301 redirect** to
the new one (or, pre-GA, an accepted clean break since there are no deployed clients yet). Because
clients reach the relay over an absolute origin and pairing bakes it in, you cannot rename purely at
the edge.

**Recommendation.** Adopt `commerce.service.tenshu.app` **now** (cheap — new service, just add the
prod default + deploy). Treat the relay retrofit as a **separate small migration PR** (rename
defaults + keep `relay.tenshu.app` as an alias), sized when the services are actually stood up —
**don't block commerce on it.**

---

### 4.7 Purchase visibility & the MoR export (D12)

The console of §4.5 answers *"who is registered and what are they on."* It cannot answer **"what
did we sell, and what do we owe each member state"** — and that second question is the one the
self-MoR posture of §5 actually obliges us to answer, quarterly, in an OSS return.

**The data exists; nothing can read it.** `invoice` is written by the Stripe webhook with every
field §5 requires (sequential `number`, `amountMinor`, `currency`, `vatAmountMinor`,
`vatRateBasisPoints`, `billingCountry`, `ipCountry`, `issuedAt`) and is then **write-only in
production**: the only `SELECT` touching it anywhere is `nextval('invoice_number_seq')`.
`InMemoryCommerceStore.invoices()` is a test helper; `PostgresCommerceStore` has no invoice read at
all. `entitlement_history` and `acceptance` are the same shape — appended, never queried
(`currentEntitlement` is a point lookup, not a listing). So the first missing thing is not a UI, it
is a **store primitive**.

**⚠⚠ Two data-completeness holes have to close before an export means anything.** Both are in the
producer, not the reader, so building the export first would ship a report that is confidently
wrong:

1. **`ipCountry` is always `null`.** `StripeWebhookRouting.kt` writes it as null with an in-code
   "deploy TODO". §5 requires **two non-contradictory pieces of location evidence** to defend the
   VAT rate; today exactly one (`billingCountry`) is captured. ⚠ Rows already written **cannot be
   backfilled** once the Stripe event retention window passes, so the export must mark
   single-evidence rows rather than quietly presenting them as defended.
2. **The ledger records only money *in*.** The webhook handles `checkout.session.completed` and
   `invoice.paid` and nothing else — no refunds, no disputes. Refunds are issued by hand in the
   Stripe dashboard (policy §5.4), and chargebacks Stripe executes unilaterally whether we offer
   refunds or not. ⚠⚠ **VAT already remitted on a sale later refunded must be corrected in a
   subsequent return** — so a money-in-only export does not merely omit refunds, it
   **systematically over-states VAT owed and causes us to over-remit**. This is a reporting defect,
   not a policy question: it is unaffected by whether the product offers refunds as a courtesy.

⚠ Per **D10** the statutory tables are append-only, so a refund is **never** an update to the
invoice row. It is a **credit note** — its own append-only row, negative-signed, referencing the
original `number`, and carrying its own sequential number (which folds into **Q4**, since
per-jurisdiction numbering now has a second document type to number).

**⚠⚠ The export is structurally A30-free, and that is what makes it a narrow reversal of D8.**
D8 refused a bulk export because it "raises the breach blast radius for little ops value." The
blast radius of the console is specifically the **A28 × A30 join** — real PII against the
`ownerId` that indexes relay metadata. **A VAT return needs neither side of that join**: it needs
invoice number, date, net, VAT rate + amount, currency, and the two country evidences. It does not
need `ownerId` *at all*, and it does not need the email — the account is identified by its internal
id, and customer name/location live on the invoice row where law wants them. So the export can be
built to **contain no `ownerId` column by construction**, which means it does not inherit the
console's headline risk even though it is served from the console's host.

What actually changed to justify reversing D8 is the *other* side of its trade: an OSS return is a
**statutory obligation**, not the "little ops value" D8 weighed. The mitigations that keep the
reversal honest — all load-bearing, none hygiene:

- **No `ownerId` in any export, ever** (above). Enforce it in the row type, not by remembering.
- **Period-bounded only.** No "export everything" affordance; a range is required.
- **Audit-log the range**, not just the access — an export is the one admin action whose *scope*
  is the interesting fact.
- **Gated behind admin MFA**, which §5.3 called "non-negotiable before it holds real users" and D8
  only *recommended*. Adding export raises the stake, so this stops being Phase-4c-someday and
  becomes the export's own release gate (WC-5).

**Slices.** Producer-side first, so the report is never built on a ledger that cannot support it:

| | Slice | What it is |
|---|---|---|
| **WC-1** | Ledger completeness | Capture `ipCountry` from the live Stripe payload; handle `charge.refunded` + `charge.dispute.*`; append credit-note rows (D10-shaped, own sequence). **No reader yet.** |
| **WC-2** | Store read primitives | `listInvoices(from, to)`, `listCreditNotes(from, to)`, `listEntitlementHistory(accountId)` on `CommerceStore` + both impls. Pure reads, no route. |
| **WC-3** | Purchases view | `GET /admin/purchases` — server-rendered, same host/credential/audit posture as §4.5. Also closes CE-5's two deferrals (entitlement history + active-session count on an account). |
| **WC-4** | The export | `GET /admin/export/invoices.csv?from&to` + a per-country VAT aggregation shaped for an OSS return. Net/VAT/gross reconciled per country, credit notes signed negative, single-evidence rows flagged. |
| **WC-5** | Admin MFA + threat-model amendment | The §5.3 hardening, as this track's release gate; surface #15 amended read-only → read + lifecycle mutation + bounded export. |

⚠ **The threat-model amendment is already owed and has not landed.** Surface #15 still reads
"**v1 is read-only (no mutation/export)**" — it was never amended for CE-5's `close`/`reopen`
mutations, which shipped. WC-5 pays both debts in one edit; ⚠ note the dry-run doc's §7 mis-numbers
this surface as **#11** (that is the contact-invitation handshake) — the admin console is **#15**.

**Not in scope.** Invoice *documents* (PDF/UBL) and the 2028 EU structured-e-invoice mandate stay in
**Q4**; the WORM/S3 statutory archive (§5.2) stays on the infra track; a refund *mechanism* in the
product stays explicitly out (policy §5.3) — WC-1 records refunds that happen by other means, it
does not offer them. Reconciling our ledger against Stripe's own reporting is a nice-to-have that
WC-4 makes possible, not a deliverable.

---

## 5. Self-MoR compliance posture (EU first)

**Decision: we are our own merchant of record.** Standard Stripe is a *processor*, not an MoR —
so the seller-of-record duties (VAT registration, invoicing, retention, refunds) fall on us by
choice, in exchange for not adding a vendor we must trust. The architecture already anticipates
the consequence: A28 (real PII) becomes a *persisted* store, not just whitelist config.

What being self-MoR obliges, and how this design meets it:

- **EU VAT via OSS.** Register for the One-Stop-Shop and file/remit VAT for each member state
  we sell into. **Stripe Tax** computes the correct rate at checkout (place-of-supply for
  digital services); we file. Capture **two non-contradictory pieces of location evidence**
  (billing country + IP/BIN country from Stripe) on each paid sale and store them on the
  `invoice` row — required to defend the VAT rate.
- **Invoices, retained ~10 years.** Generate a sequential, immutable invoice per paid
  transaction (customer name, location, amount, VAT rate + amount, invoice number, date).
  Retention overrides GDPR erasure for these specific rows (the lawful-obligation basis) — which
  is exactly the GDPR ⨯ E2E corner `commerce-billing-plane.md` §5 already documents for the DPA.
- **GDPR controller duties.** Data-subject access/export and erasure (with the invoice
  carve-out), a Record of Processing, a privacy policy + DPA naming **Stripe as subprocessor**
  (already T-CB-06 in the threat model), and 72-hour breach notification. None of this is a
  schema feature — it is process the commerce plane owns.
- **Security of A28.** This Postgres is now the single highest-value PII target in an otherwise
  zero-trust system: encryption at rest, tight access control, audited backups, on the commerce
  host **never** the relay (House Rule #1; D1 firewall).

> **Not legal advice.** OSS thresholds, registration triggers, and exact retention periods are
> jurisdiction- and fact-dependent — confirm with a tax advisor before launch. The architectural
> claim is only that none of these obligations require linking the commerce and data planes
> (I-CB-1 holds; the A30 `account↔ownerId` link is the documented minimal-variant residual).

### 5.1 Consent & data handling (D7)

Holding a verified email on every account (§3.3) pulls in a consent/notice layer — but a lighter
one than "checkboxes for everything," because most of what we process runs on a lawful basis that
is **not** consent:

- **Core processing needs no consent checkbox.** Collecting email + payment to *deliver the paid
  service* runs on GDPR Art. 6(1)(b) **performance of a contract**; keeping VAT invoices runs on
  Art. 6(1)(c) **legal obligation**. What these bases *require* is **transparency**: a privacy
  notice shown at the point of collection (Art. 13 — what, why, basis, retention, rights, and that
  Stripe is a subprocessor). So at registration the user sees a **ToS + Privacy link with a single
  "I agree" acceptance** (contract formation), recorded **versioned** in the `acceptance` table.
- **Transactional email needs no marketing consent.** OTP, receipts, "subscription expiring", and
  security notices are necessary to the service → contractual basis. Only **marketing/promotional**
  mail needs separate, **unticked, opt-in** consent — hence the standalone `marketing_consent`
  flag (default FALSE), so we never have to retro-collect it. v1 may simply send no marketing mail
  and leave the flag unused.
- **Subprocessor + breach duties.** DPA naming **Stripe** as subprocessor (already T-CB-06), a
  Record of Processing, and 72-hour breach notification. Erasure deletes the account/email but
  **retains invoices** (legal-obligation carve-out, §5 above).
- **US (later).** No single federal law — a growing state patchwork (California's CCPA/CPRA being
  the bellwether), and a **notice + opt-out** model rather than GDPR opt-in (plus CAN-SPAM opt-out
  for any marketing mail). Strategy: **build to GDPR (the stricter bar)** and the US is mostly
  covered; add the CCPA-style notice + "do not sell/share" (easy — we don't sell data) and opt-out
  at US launch. The state list keeps expanding — re-check at launch.

Net new surface in the product: one acceptance checkbox + a versioned acceptance record + a
`marketing_consent` flag. No change to the data-plane architecture. *Not legal advice — get the
privacy policy, DPA, and any SCCs reviewed by counsel before taking real payments.*

### 5.2 Record retention — operational vs statutory store (D10)

Invoice/consent records have a fundamentally different lifecycle than operational account data, so
**split the retention semantics** (not necessarily a second database product):

- **Operational store** (`account`, `session`, `entitlement_history`, `account_owner`): hot,
  mutable, and **erasable** — a GDPR deletion wipes the email/account. ⚠ *As built (§9.7):* the
  person is erased, the row is **pseudonymised, not dropped** — the closure sweep, the cancellation
  retry and the statutory rows below all key on `account.id`.
- **Statutory archive** (`invoice`, `acceptance`): **append-only** (no edits, no deletes — ⚠ *as
  built (§9.10):* enforced by a Postgres trigger, not the role-level `REVOKE` first planned, because
  the app connects as the table owner), **~10-year** retention, and it **survives account erasure**
  (delete the account, keep the invoice — the §5 carve-out). Tamper-evident: sequential numbering
  (Q4) + optional hash-chaining / signed rows.

**Durability backstop.** Periodically export the statutory tables to **immutable object storage
(WORM / S3 Object Lock)** as the real long-term archive — it rides the relay's Phase-4 S3 infra
track and survives the operational DB being migrated, pruned, or restored. The asymmetry is the
point: **entitlements are re-mintable from Stripe if the operational DB is lost, but invoices
legally are not** — so the WORM archive is the one thing that must never be lost. Same posture
covers DR backups of the operational store (A28 is crown-jewel PII). This also makes "delete the
account, keep the invoice" mechanically clean and removes any temptation to `UPDATE` a statutory row.

### 5.3 Security & abuse hardening for the MoR (Phase-4-adjacent)

Email-OTP + sessions is an adequate v1 baseline, but "MoR protection" is broader than the login
mechanism. The sharper exposures, with the hardening each wants (lands around Phase 4, when real
money + PII flow — not a Phase 1/2 blocker):

- **Admin console (highest risk).** It is the one breach that de-anonymises *every* user at once
  (A28 + the A30 join). Beyond the §4.5 allowlist + separate auth + audit log, require **MFA /
  hardware-key** and keep it off public DNS. Non-negotiable before it holds real users.
- **Stripe webhook forgery.** A forged `invoice.paid` could mint entitlements or fabricate paid
  accounts → **verify the Stripe signature** on every webhook (load-bearing, §6).
- **The rebinding / recovery flow (§3.4) is an account-takeover→purchase-theft vector.** "Prove the
  email → re-bind to a new `ownerId`" must have **rate-limiting + a step-up** (fresh OTP, not just a
  live session) **+ an "your purchase was moved to a new device" notification** to the original
  email so a legitimate owner notices a hijack. (It can't expose file data — that stays E2E — but it
  can hijack the subscription + billing PII.)
- **OTP brute-force.** The `email_verification.attempts` column needs real **lockout / backoff**.
- **Chargebacks & fraud.** As MoR we eat both — enable **Stripe Radar**; track dispute state in
  commerce (it changes which tokens reissue, never the data plane).

None of these change the data-plane architecture; they are commerce-plane controls. Sized in Phase
4 (the `Q6` hardening open item).

---

## 6. Stripe integration (processor; we are seller of record)

Stripe Checkout (hosted) for web/desktop, exactly as `control-subscription-ux.md` §4b sketches,
with the bridge endpoints made real:

- `POST /commerce/checkout` `{ ownerId, productId }` → a Stripe Checkout `sessionUrl` (Stripe
  Tax enabled; `customer_email` collected by Stripe; `success_url`/`cancel_url` deep-link back to
  Control). **Apple Pay / Google Pay show up here as Stripe payment *methods*** — same MoR (us),
  same data, no IAP involvement.
- `POST /commerce/webhook/stripe` → verify the Stripe signature; on `invoice.paid` /
  `customer.subscription.updated`, **create-or-find the account from the collected email** (§3.2),
  write `entitlement_history` + generate the `invoice` row + (re)issue the signed entitlement. On
  `subscription.deleted`/lapse: stop reissuing (the short-lived token's expiry is the revocation —
  `commerce-billing-plane.md` D5).
- **Customer portal** for cancellation/management — delegate to Stripe's portal (no in-app
  dark-pattern cancellation; matches `control-subscription-ux.md` §5.2).

Card data never touches us (PCI scope stays with Stripe). **Native IAP** (StoreKit / Play
Billing) is the *separate* rail where Apple/Google are MoR (§3.1); its per-store receipt-
verification plumbing rides the `PublisherClient` seam later, but the account + email-anchor it
binds to is the model this doc fixes (§3.3), so IAP is no longer "unchanged by this doc."

---

## 7. Phasing (each independently shippable)

1. **Tier simplification.** Remove `Lifetime`; reshape `EntitlementProducts` + the picker to
   Free/Basic/Premium; add the "Have a code?" entry (wired to a stub `/redeem`). Move the
   commerce-config top tier to Premium/2 TiB. UI + catalogue only; no payment.
2. **Accounts + email-OTP + session + Postgres.** The §4 store and endpoints (incl. `claim`/
   re-bind §3.4 + versioned `acceptance` §5.1); re-gate `/mint` + `/token` to sessions; free-tier
   issuance on verified email; `/redeem` real. Establishes the universal email anchor (D6).
2b. **Admin console (read-only).** The §4.5 guarded server-rendered users view at
   `admin.tenshu.app`, behind the allowlist + admin auth. Small, ships right after the store
   exists; carries its own threat-model surface note.
3. **Mint-authority swap on device.** Point Control's writer at the service behind
   `PublisherClient`; dev key becomes dev-only; release rejects `tenshu.dev` (reconciliation §10.3).
   *Shipped as Ph3a (dev-key guard + client alignment) + Ph3b (real `register`/`verifyOtp`/
   `redeem` device flow + session token wired into the mint/token clients) — see §9.3.*
4. **Stripe (EU, self-MoR). — SHIPPED (server-side), see §9.4 as-built.** §6 checkout + webhook →
   **account-from-email** + mint + invoice; Stripe Tax; OSS filing process; invoice generation +
   retention (append-only `invoice`/`acceptance` + WORM archive, §5.2); the §5.1 privacy notice + ToS
   acceptance surface. Ships paid storage in the EU. **Threat-model amendment landed in this PR** (House
   Rule #2): A28 became a persisted store; the new `/register`,`/verify`,`/claim`,`/checkout`,`/webhook`
   surfaces + the admin console (#15) + webhook forgery (T-CB-07) are in surface #13's group. *(Deferred
   to follow-ups: WORM/S3 archive, OSS filing process. **Client checkout-launch UI shipped — §9.4a.**)*
4b. **IAP rail (mobile).** Native StoreKit / Play Billing receipt verification behind
   `PublisherClient`, binding to the email-anchored account (§3.3) + Restore-Purchases re-bind.
   Sized when mobile is concrete; gated on the current Apple/Google policy check (§3.1).
4c. **MoR security & abuse hardening (§5.3, Q6).** Admin MFA, webhook-signature verification,
   rebinding step-up + rate-limit + owner-notification, OTP lockout, Stripe Radar / chargeback
   handling. Commerce-plane controls; pairs with Phase 4 (real money + PII), no data-plane change.
4d. **Purchase visibility & MoR export (§4.7, D12) — WC-1…WC-5.** Ledger completeness
   (`ipCountry` capture + refund/dispute credit notes) → store read primitives → `GET
   /admin/purchases` → the period-bounded, `ownerId`-free invoice + per-country VAT export → admin
   MFA + the threat-model amendment that surface #15 is **already owed** for CE-5's mutations.
   Independently shippable per slice; WC-1 is producer-side and valuable on its own, since without
   it the ledger cannot support a correct return whether or not anyone exports it.
5. **US sales tax.** Economic-nexus tracking + per-state registration/remittance (Stripe Tax
   computes; we file). No code shape change from Phase 4 — a compliance/config phase.
6. **(Later) strong privacy.** Blinded tokens retire A30 — `commerce-billing-plane.md` Phase 6,
   verifier unchanged.

---

## 8. Decisions & open questions

- **D1. Self-hosted Postgres, no external MoR/identity vendor.** *Decided.* Trades more
  compliance process for zero added trusted parties; consistent with the no-directory ethos.
- **D2. Email OTP, no passwords.** *Decided.* The verification step doubles as the sybil gate;
  no password store, no reset flow, desktop/mobile parity, no magic-link deep-link dependency.
  Revisit only if account-recovery UX demands it.
- **D3. Redeem path is identity-light.** *Decided.* No email required to redeem a code — only
  Stripe-paid EU sales need captured identity (§5).
- **D4. Mint authority = verified account, not whitelist.** *Decided.* Whitelist degrades to a
  redemption-code seed.
- **D5. Rail before registration; data keyed on the rail.** *Decided (§3.1).* Order is
  tier → rail → rail-specific. Stripe collects email at checkout (account from the webhook); Free
  collects email+OTP up front; IAP links email after the native purchase; redeem stays anonymous.
  ⚠⚠ **Amended 2026-10-12 for the web Stripe rail: sign in first, then pay (§9.4f).** Letting
  Stripe collect a free-typed email put the plan on whichever account that address named, while a
  browser can only fetch a plan with a session — so the two had to match by the buyer's typing, and
  on the first live pass they did not. The rail is still chosen before registration; what moved is
  that the email is proven *before* the money, and handed to Stripe rather than collected by it.
  Native still lets Stripe collect it, and owes the same change.
- **D6. Email is the universal recovery anchor — on every rail, including IAP.** *Decided (§3.3).*
  Store accounts remain a second anchor for IAP, but email gives one uniform, cross-platform,
  operator-re-mintable recovery model.
- **D7. Consent = transparency + one acceptance, not blanket checkboxes.** *Decided (§5.1).* Core
  processing rides contract/legal-obligation bases (no consent checkbox); only marketing needs
  separate opt-in (`marketing_consent`, default off). Build to GDPR; add CCPA notice+opt-out at US
  launch.
- **D8. Admin console = `admin.tenshu.app`, internal-only, read-only v1.** *Decided (§4.5);*
  **the no-mutation and no-export halves have since been superseded — see D12.** Separate host from
  the issuer API; separate admin auth; off public DNS; audit-logged. Name avoids the developer
  *App Console* collision. (Mutations arrived with CE-5's `close`/`reopen`; export with D12.)
- **D9. Backend services live under `*.service.tenshu.app`.** *Decided (§4.5).* Issuer API =
  `commerce.service.tenshu.app`; future services follow (`sync.service…`, etc.). Distinct from
  user-app subdomains + the apex; no parent-domain cookies (bearer-header auth only). Admin is the
  intentional exception (apex-level, human tool). Convention codified in `web/architecture.md` §3.4;
  adoption/rename touch-points (not pure config) in §4.6 — relay retrofit is a separate migration PR.
- **D10. Operational store vs append-only statutory archive.** *Decided (§5.2).* `invoice` +
  `acceptance` are append-only (no UPDATE/DELETE — a trigger enforces it since §9.10, `credit_note`
  included; one exception since v2: a never-verified account's `Registration` rows go with it, §9.10),
  ~10-year, survive account erasure, and are
  WORM-archived to immutable object storage; the operational tables stay mutable/erasable. Splits
  retention semantics without needing a second database product.
- **D11. Sync the account *descriptor*; per-device login for billing/identity actions.**
  *Decided (§3.5).* The commerce session stays **per-device, re-established by email login, not
  synced** — but **not** for relay-exposure reasons (a realm-encrypted session record is
  ciphertext to the relay, like the `Entitlement` already is). The reason is the **data↔billing
  firewall**: a realm compromise must not confer live control of the payment/legal-identity
  account, which stays behind the separate factor of email control. So every device shows the
  **real plan** (derivable from the synced `Entitlement`, no new synced PII required) with a
  *Change plan* control, and every action that touches billing/identity (sign-up, change/renew,
  portal, disconnect, redeem) requires a fresh email-OTP login **on that device**; passive viewing
  does not. Supersedes the old "session is device-local because House Rule #1 / D1 firewall"
  framing. First implementation **shipped** (descriptor card + `[Sign in to manage]`).
- **D12. The console gains purchase visibility and a bounded, `ownerId`-free MoR export.**
  *Decided (§4.7), superseding D8's "no export".* D8 weighed breach blast radius against "little
  ops value"; an **OSS return is a statutory obligation**, so the value side of that trade was
  mis-stated rather than the risk side. ⚠⚠ The reversal is narrow because the export is
  **structurally free of the A30 join** — a VAT return needs invoice number, date, net, VAT rate +
  amount, currency and the two country evidences, and needs `ownerId` not at all — so it does not
  inherit the console's headline risk. Bounded by: no `ownerId` column enforced in the row type,
  period-bounded only (no export-everything), the *range* audit-logged, and **admin MFA promoted
  from "recommended someday" (D8/§5.3/Q6) to this track's own release gate**. ⚠ Producer-side
  slices land first (WC-1): `ipCountry` is null on every row today and the ledger records only
  money *in*, so an export built before them over-states VAT owed and causes over-remittance.
- **D13. Store reviewers sign in with a fixed 6-digit code, one unpublished account per store.**
  *Decided (2026-10-24, §9.8; threat model T-CB-12).* Google Play and App Review must reach
  everything behind sign-in without contacting us, and a reviewer cannot be relied on to have a
  receiving inbox. So each store gets one review account — an **unpublished** address shaped
  `google-review-<suffix>@tenshu.app` / `apple-review-<suffix>@tenshu.app`, the suffix random, the
  address living only in `deploy.env`, the maintainer's Keychain and that store's review form — and
  each accepts **its own** fixed secret code in place of a mailed one, for sign-in **and** for the
  deletion confirm (`REVIEW_SIGNIN_ACCOUNTS`). They are never mailed a code and never accept one; no
  other address ever accepts a review code; one store's code never opens the other's account.
  **Six digits**, because that is what every client's code field takes — so no client changes. What
  makes six digits safe enough: a **persisted** lockout of 10 wrong codes per account per rolling
  24 hours (≈ 274 years to try every code, ≈ 0.37 % per account per year), and an address an attacker
  has to find before guessing at all. **Always on**: Google re-reviews live apps and expects the App
  access credentials to keep working, so the env is not toggled per submission; rotation is periodic
  or after a suspected leak, and a rotation ends the account's sessions. The box holds an **HMAC
  under a box-side key** of each code, never a code: a 6-digit code's plain hash is reversible by
  enumeration. Nothing secret is in this repository, which will be public. Runbook:
  `how-to/play-console-answers.md` §14. Supersedes the "not recommended" option 3 of that file's old
  §1 and option C of `how-to/app-store-release.md` §5.2.
- **D14. No password and no passkey for ordinary accounts.** *Decided (2026-10-24), confirming D2.*
  The account is a **billing identity only**: user data is end-to-end encrypted under keys the
  devices hold, so the account guards a plan and an invoice trail, never the content. A password
  would therefore add no security over the email it would have to be reset through — the inbox stays
  the real factor either way — while adding a credential store to breach, a reset flow, and a
  phishable secret users reuse. D13 is not a precedent: it is a static credential on two addresses we
  own, for an audience that has no inbox. ⚠ **Passkeys are the one addition worth revisiting**, for
  phishing resistance (an emailed code can be relayed by a phishing page; a passkey cannot), and only
  as an *additional* sign-in factor with email kept as the recovery path — never as a replacement
  that makes a lost device a lost account.
- **D15. A daily cap on failed one-time-code checks for every account.** *Decided (2026-10-24,
  §9.9; threat model T-CB-13).* Until now a known address allowed 20 `/verify` attempts an hour
  (`OTP_VERIFY_PER_EMAIL_PER_HOUR`) across freshly requested codes — ~480 guesses a day against
  10⁶-code space, **≈ 16 % a year** for a patient attacker willing to mail-bomb the victim with codes.
  Now every address also has a **persisted** cap of 20 failed code checks per rolling 24 hours
  (`OTP_FAILED_PER_EMAIL_PER_DAY`), shared by `/verify` and `/delete/confirm`: 20 a day, **≈ 0.73 % a
  year**. Over the cap even the right code is refused (`429 rate_limited`, the existing shape) until
  the oldest failure ages out. ⚠ The cost is a denial-of-service lever: anyone who knows an address can
  keep its owner from signing in for a day with 20 wrong guesses — accepted, because that owner's
  data is unaffected (end-to-end encrypted, usable offline) and only billing actions wait.
- **Q1. Session mechanics.** Opaque random token in `session` (hashed) vs. a signed stateless
  session. Lean opaque + DB (simplest, instantly revocable). *Open — size in Phase 2.*
- **Q2. Free-tier abuse beyond email.** Is verified email a strong enough sybil gate, or add
  proof-of-work / device attestation (`commerce-billing-plane.md` Q2)? *Open; email-only for v1.*
- **Q3. `ownerId` rebinding.** *Resolved (§3.4):* `account_owner` is re-bindable, at most one live
  `ownerId` per account; re-binding (via email-OTP claim or IAP restore) supersedes the previous
  owner, with history retained. This is what makes purchase recovery after recovery-code loss work.
- **Q4. Invoice numbering + storage format.** Sequential-per-jurisdiction numbering and a
  tamper-evident store (and the 2028 EU structured-e-invoice mandate, XML/UBL/PDF-A3). *Open —
  size in Phase 4 with counsel.* ⚠ **D12/WC-1 adds a second document type to number** — credit
  notes, for refunds and chargebacks — so whatever numbering scheme lands has to cover both.
- **Q5. A29 key custody.** Still env-seed; KMS/HSM + rotation deferred (`commerce-service-phase1-plan`
  §9). The `kid`/`SIGNING_KEY_ID` rotation seam already exists.
- **Q6. MoR security & abuse hardening.** *Open — size in Phase 4 (§5.3).* Admin MFA, webhook
  signature verification, rebinding step-up + rate-limit + owner notification, OTP lockout, Stripe
  Radar / chargeback handling. Commerce-plane controls; no data-plane change.

---

## 9. Implementation Notes for Claude Code

Paths verified against the tree 2026-06-28. Each phase ≈ one PR unit. Run the named tests
(House Rule #7). **Build order — start at §9.1 and go in sequence:** Ph1 tier simplification →
Ph2 accounts+OTP+session+Postgres → Ph2b admin console → Ph3 device mint swap → Ph4 Stripe
(EU, self-MoR) + threat-model amendment → Ph4b IAP → Ph5 US sales tax. Ph1 is independent and
ships value alone (UI + catalogue, no backend); Ph2 is the first backend slice. Decisions D1–D9
(§8) are settled — implement to them, don't re-litigate.

### 9.1 Phase 1 — tier simplification (UI + catalogue, no payment) — SHIPPED

Remove the `Lifetime` plan and reshape the catalogue:

- `storage-service-proxy` `EntitlementProducts` (pkg `org.tenshu.storage.proxy`): add
  `BASIC`/`PREMIUM` product ids + `*_LIMIT_BYTES`; keep `FREE`. (Find the file:
  `grep -rl "object EntitlementProducts" storage-service*/src`.)
- `control-shared/.../data/` `PublisherAccount`: drop `Plan.Lifetime` and
  `IdentityMethod.LifetimeStore`. Keep `Rei` (now redeem-only, not a card).
- Delete/retire the Lifetime screens + nav: `ui/screens/services/LifetimePurchaseScreen.kt`
  + `LifetimePurchaseViewModel`, the `LifetimeStubCard` in `AccountPickerScreen.kt`,
  `Destination.LifetimePurchase`, the `onPickLifetime` wiring in `App.kt`, the
  `services_plan_lifetime` string + the `ServicesScreen` label branch. Replace the picker body
  with the three-card stack + the "Have a code?" link (`control-subscription-ux.md` §3 revised).
- `commerce-service` `CommerceConfig`: `DEFAULT_TOP_TIER_PRODUCT_ID` → `tenshu.drive.premium`;
  `DEFAULT_TOP_TIER_QUOTA_BYTES` → `2 TiB`. Update `CommerceConfigTest`.
- Tests: `EntitlementStorageInitializationTest` (catalogue assertions), the services-screen
  preview/test, `CommerceConfigTest`.

### 9.2 Phase 2 — accounts + email-OTP + session + Postgres — SHIPPED (Ph2; admin console Ph2b)

- New persistence in `:commerce-service` (its own datasource — **not** the relay's). Add a JDBC
  Postgres driver + a thin DAO; mirror the relay's `SyncMailbox`/store split. Migrations like
  the relay's SQLite migrations but Postgres.
- New routes (§4.2): `routing/AccountRouting.kt` (`/register`, `/verify`, `/claim`, `/session`),
  `routing/RedeemRouting.kt` (`/redeem`). An `EmailSender` seam (SMTP/provider) with a dev
  no-op that logs the OTP. Re-gate `MintRouting`/`TokenRouting` on a `Session` lookup instead of
  `Whitelist` (keep `Whitelist` only as the `redemption_code` seed importer). `account_owner`
  re-bind is a supersede-not-replace update (§3.4); `register` records the §5.1 `acceptance` row.
- Ops stay non-suspend at the interface where any new proxy `Operation` is added (House Rule #3);
  the service itself is Ktor-suspend internally, which is fine.
- Tests: `AccountRoutingTest` (register→verify→session happy path; bad/expired OTP → 4xx;
  unverified → no mint), `ClaimRebindTest` (claim with a *new* `ownerId` supersedes the old
  binding and re-mints; old `ownerId` no longer resolves), `RedeemRoutingTest` (valid code mints;
  exhausted/expired → 403), `EntitlementMinterTest` still green (message bytes unchanged).
- **Admin console (§4.5):** `routing/AdminRouting.kt` — a guarded server-rendered users table
  (Ktor HTML DSL, no React). Auth via an `AdminAuth` seam (admin token / SSO), distinct from
  `Session`; Caddy allowlist on the `admin.tenshu.app` vhost (mirror the relay Caddyfile). Bind to
  a separate host/port from the public API. Tests: `AdminAuthTest` (no/invalid admin credential →
  401, never the issuer key), `AdminUsersViewTest` (renders accounts read-only; no mutation route
  exists). Audit-log each access.

### 9.3 Phase 3 — device mint swap

Phase 3 split in two when built, because the original "point the writer at the service" predated
the Ph2 re-gate of `/mint`+`/token` onto a **session** (the device had no account/session flow yet):

**Ph3a (SHIPPED) — dev-key release guard + client alignment.** A `BuildVariant(isRelease)`
DI flag + `EntitlementMinterSelection.useDevSeed` (pure, unit-tested): the in-process
`DevSeedEntitlementMintClient` is selected **only** in a non-release build; release fails closed to
the commerce client (never self-signs a dev entitlement). `BuildVariant` per platform (desktop
`-Dtenshu.release`; Android `FLAG_DEBUGGABLE`; iOS dev-only). `CommerceEntitlement{Mint,Token}Client`
gained a `sessionTokenProvider: () -> String?` and now POST `{sessionToken}` (no PII) — but the
provider returned `null`, so they were **contract-correct but inert**.

**Ph3b (SHIPPED) — the real device-side account/session flow that lights up Ph3a.**
- **`PublisherClient` reshaped** (`:control-shared-logic/.../data/PublisherClient.kt`) from the
  poll-based mock contract to the real one: `register(email)` → `verifyOtp(code)` → `redeem(code)`
  + a synchronous `currentSessionToken()`. Two impls under the **same `TENSHU_BILLING_MOCK` gate as
  the minter**: `MockPublisherClient` (dev; any 6-digit OTP, grants via the dev-key writer) and the
  new `CommercePublisherClient` (real HTTP to `/commerce/account/{register,verify}` +
  `/commerce/entitlement/redeem`). The session record (email + token) persists to
  `SecureStorageDelegate` (rehydrated on restart); email stays device-local PII (House Rule #1). The
  `PublisherClient` binding moved from `:control-shared-proxy` commonMain to each `PlatformProxyModule`
  (it needs the platform HttpClient + owner-id source + commerce origin).
- **The four `sessionTokenProvider = { null }` sites** (desktop+android mint/token helpers) now read
  `{ publisherClient.currentSessionToken() }` — a verified session immediately unlocks the off-device
  mint and the relay `X-Entitlement` token. **No reader-half change**; the account flow → mint writer
  (`initEntitlementWriter`) is reused verbatim for the Free path (account flips → mint with the live
  session).
- **Redeem (§2.3) is identity-light**, so it has no session to mint through: `CommercePublisherClient`
  emits the commerce-signed grant on a new `PublisherClient.redeemedEntitlements` flow, persisted by
  `initRedeemedEntitlementWriter` → `saveSignedEntitlement` (extracted from `mintEntitlement`'s save
  half, `:storage-service-proxy`). The mock grants the redeem tier through the account → dev-key
  writer instead and emits nothing.
- **UI**: the Free-signup screen/VM became email → **6-digit OTP entry** → done; the "Have a code?"
  screen/VM became a real redeem. Strings updated; iOS SDK `devBindFreeAccount` updated to
  `register`/`verifyOtp`. Tests: `CommercePublisherClientTest` (MockEngine: request bodies, session
  persistence, redeem emission), reshaped `MockPublisherClient*Test`, `saveSignedEntitlement` writer
  test. iOS remains the dev-only mock path (no commerce wiring yet).

### 9.4 Phase 4 — Stripe (EU, self-MoR)

- `routing/CheckoutRouting.kt` (`/checkout` → Stripe session, Stripe Tax on) +
  `routing/StripeWebhookRouting.kt` (verify signature; `invoice.paid` → **create-or-find account
  from the collected email** → `entitlement_history` + `invoice` row + mint). Invoice generator +
  retention store. Customer-portal session route. The §5.1 privacy-notice + ToS-acceptance surface
  (client-side) + the `acceptance`/`marketing_consent` writes.
- **Amend `docs/sync/threat-model.md` in this PR** (House Rule #2): A28 is now a persisted PII
  store (was whitelist config); add `/register`,`/verify`,`/claim`,`/checkout`,`/webhook` to
  surface #13; add the **admin console as a new internal surface on A28/A30** (§4.5; lands with
  Phase 2b); Stripe-subprocessor is already T-CB-06; I-CB-1 unchanged (still minimal variant, A30
  residual).

**Ph4 — SHIPPED (with an Android dex-collision fix), server-side Stripe money path.** Scoped to the backend (the testable
core); the client checkout-launch UI is a follow-up slice, mirroring how Ph2→2b and Ph3→3a/3b split.
- **Stripe seam** `stripe/StripeClient.kt` — hand-rolled over `ktor-client` (no `stripe-java` SDK;
  matches the module's grain + keeps the dep light). `RealStripeClient` form-POSTs Checkout + Billing
  Portal sessions with a Bearer secret and reads the hosted `url`; tested over a `MockEngine`.
- **`stripe/StripeSignature.kt`** — from-scratch port of Stripe's `t=…,v1=…` HMAC-SHA256 webhook
  scheme (§5.3, **T-CB-07**), constant-time, 5-minute replay window; unit-tested.
- **Routes** `routing/CheckoutRouting.kt` (`POST /commerce/checkout` {ownerId, productId} → session
  URL, Stripe Tax on, `client_reference_id`=ownerId; `POST /commerce/portal` session-gated) +
  `routing/StripeWebhookRouting.kt` (`POST /commerce/webhook/stripe`: verify signature → on
  `invoice.paid`/`checkout.session.completed`, create-or-find account from email → attach customer →
  bind owner → entitlement ledger → statutory invoice; `subscription.deleted` = let it lapse; event-id
  idempotency). All content-free errors; `503` when Stripe is unconfigured (the free/redeem paths are
  unaffected).
- **Store** — `invoice` (append-only, sequential number via a Postgres sequence, §5.2) + `stripe_event`
  (idempotency) tables (Migration v2, append-only); `attachStripeCustomer` / `appendInvoice` /
  `stripeEventSeen` / `recordStripeEvent`; both `InMemoryCommerceStore` (tested source of truth) +
  `PostgresCommerceStore`.
- **`currentEntitlement` rule change (§4.3):** among currently-valid rows the **highest quota** now
  wins (was latest-`validUntil`). Load-bearing once paid tiers coexist with the ~permanent Free row —
  a Free-then-upgrade account must serve Premium while the paid window is valid, then fall back to Free
  when it lapses ("stop reissuing" revocation). Extracted to a shared `pickCurrentEntitlement` so both
  store backends select identically.
- **Config** — `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` (required together — an unverified
  webhook is refused at startup) / `STRIPE_PRICE_IDS` (tier→price map) / `CHECKOUT_{SUCCESS,CANCEL}_URL`
  / `PORTAL_RETURN_URL` / `STRIPE_API_BASE`. Stripe is **opt-in**; off by default so a free/redeem-only
  deployment and an unconfigured `run` are unchanged.
- Tests GREEN (`:commerce-service:test`, 75 total): `StripeSignatureTest`, `StripeClientTest`
  (MockEngine), `CheckoutRoutingTest`, `StripeWebhookRoutingTest`, `CurrentEntitlementSelectionTest`,
  `CommerceConfigStripeTest`.
- **Deferred (stated, not silently cut):** live Stripe test-mode round-trip (deploy-time, like the other
  commerce phases' on-device passes — the Stripe field mapping in the webhook parser needs validating
  against real payloads); the WORM/S3 statutory archive (rides the relay Phase-4 infra track); OSS VAT
  registration/filing (process); the client-side checkout-launch UI + `tenshu://checkout-result`
  deep-link handling in Control (Compose slice — the ToS/`acceptance` write already lands server-side at
  Ph2 register). IAP is Ph4b.

### 9.4a Phase 4 (client) — checkout-launch UI — SHIPPED

Turns the Ph4 server money path into a reachable on-device purchase. Control-side only
(`:control-shared` + `:control-shared-logic`); no protocol/wire/schema change.

- **Client seam** — `PublisherClient.startCheckout(productId): CheckoutResult`
  (`Ready(url)` / `CompletedLocally` (mock) / `Unavailable` (503) / `Error`). `CommercePublisherClient`
  POSTs `/commerce/checkout` `{ownerId, productId}` (owner-gated, **not** session-gated — the account is
  bound server-side from the webhook) and reads `checkoutUrl`; `MockPublisherClient` grants the tier
  locally (no browser). Paid product ids + quotas live in a new `CommerceProducts` object (mirrors
  `EntitlementProducts`; a logic module can't depend on `:storage-service-proxy`).
- **Confirmation is two-layer (decided; not "deep-link vs poll"):** fulfilment is driven by the Stripe
  webhook, never the browser return. **Layer 1 (authoritative, always on):** `CheckoutViewModel` watches
  the synced `StorageStatus` and reaches `Done` only when the quota reaches the purchased tier — this
  runs regardless of whether/how the user returns. **Layer 2 (deferred UX):** a `tenshu://checkout-result`
  deep-link would only *accelerate* Layer 1's refetch, never replace it (per Stripe's guidance that the
  redirect is not a reliable completion signal). Deferred because a desktop/JVM custom-URI handler is
  fiddly for a focus-return convenience.
- **UI** — `AccountPickerScreen` paid cards enable `Subscribe` → `Destination.Checkout(productId)` →
  `CheckoutScreen` (Launching → AwaitingConfirmation → Done/Error, with reopen + retry). URL opened via a
  new `OpenExternalUrlLauncher` expect/actual in `:control-shared` (Android `ACTION_VIEW`, desktop
  `Desktop.browse`; mirrors Drive's `OpenFileLauncher`), kept out of the VM so `-logic` stays Compose-free.
- **Platforms:** Stripe web checkout on desktop **and** Android (opens the hosted URL in the browser) —
  IAP is still Ph4b. ⚠ If Control is ever Play-distributed, Play policy may require IAP for digital goods.
- **No threat-model amendment:** the client only opens a Stripe URL carrying `ownerId`
  (`client_reference_id`) — already covered by Ph4's A28/surface-#13; no new persisted client data
  (session token already stored at Ph3b).
- Tests GREEN (`:control-shared-logic:desktopTest` + `:control-shared:desktopTest`): `CheckoutViewModelTest`
  (two-layer confirmation, CompletedLocally, unavailable, unknown-product no-call), checkout cases added to
  `CommercePublisherClientTest` (MockEngine), `MockPublisherClient.startCheckout`, reworked
  `AccountPickerScreenTest` (Subscribe now enabled + fires productId). `:control:assembleDebug` green
  (dex-merge clean — the `MintedEntitlementResponse` dex-collision guard). **Deferred:** billing-portal *UI* (→ §9.4b) + the Layer-2
  deep-link; on-device pass vs a live `:commerce-service` with Stripe test-mode keys.

### 9.4b Phase 4 (client) — manage-subscription (billing portal) — SHIPPED

The §9.4a follow-up: lets a subscribed user reach Stripe's billing portal (update card, cancel, view
invoices). Control-only; no protocol/wire/schema change.

- **Client seam** — re-adds `PublisherClient.openBillingPortal(): PortalResult`
  (`Ready(url)` / `NoSubscription` (409) / `Unauthorized` (401) / `Unavailable` (503) / `Error`).
  `CommercePublisherClient` POSTs `/commerce/portal` `{sessionToken}` (session-gated — returns
  `Unauthorized` without a request when no session); `MockPublisherClient` → `Unavailable`.
- **UI** — `ServicesViewModel.openBillingPortal()` maps the result to a one-shot `portalUrl` (opened via
  the §9.4a `OpenExternalUrlLauncher`) or an inline `portalNotice` on the account card; a "Manage
  subscription" button is shown only where a browser can open the portal (`launcher.canOpen`). Proper
  failure surface (the reason it was deferred from §9.4a): 409/401/503/error each get a distinct inline
  message rather than a silent no-op.
- Tests GREEN: portal cases in `CommercePublisherClientTest` (200/409/401/503 + no-session-no-request),
  `MockPublisherClient.openBillingPortal`, `ServicesViewModelTest` (Ready→url, NoSubscription→notice,
  Error→notice), `ServicesScreenTest` (button shown+fires+notice; hidden when not offered).
  `:control:assembleDebug` green (dex-merge clean). **Still deferred:** the Layer-2 checkout deep-link;
  live `:commerce-service` on-device pass.

### 9.4c Phase 4 (client) — app-store steering gate on the checkout CTA — SHIPPED

Store-distributed mobile builds must not steer users to external (Stripe) payment (Apple 3.1.x /
Google Play Payments). Paid storage is a cross-platform service, so the model is **buy on the web,
reflect the entitlement in-app** — which keeps the store builds policy-clean **without native IAP**
(that's the separate Ph4b track).

- **`inAppCheckoutEnabled`** — an `expect val` in `:control-shared` (mirrors `PlatformSettingsSection`):
  **desktop = true** (no app store; the in-app CTA → browser → Stripe is the natural funnel),
  **android = false** (a Play build drops the CTA). Control has no iOS UI; the web app gates itself.
- `AccountPickerScreen` gains `showPaidTiers` (default true, testable); when false the paid Subscribe
  cards are omitted — Free signup + "Have a code" remain, and the Services screen still reflects
  whatever tier the synced entitlement grants (a web purchase surfaces regardless). `App.kt` passes
  `showPaidTiers = inAppCheckoutEnabled`.
- The checkout flow itself (§9.4a) is unchanged and stays compiled — it's just unreachable when the
  cards are hidden. A sideloaded Android build (off Play) can flip the actual to `true`.
- Test: `AccountPickerScreenTest.paidTiers_hiddenWhenShowPaidTiersFalse`.
- ⚠ **The same gate hides "Manage subscription"** (2026-10-22). The Services screen had offered the
  Stripe billing portal on Android all along, gated only on "a browser can open it"; the portal
  changes plans and payment methods, so it is the same external-payment link. `ServicesScreen` takes
  `showManageSubscription`, which `App.kt` sets from `inAppCheckoutEnabled`. An Android user manages
  a web-bought plan on the web. Test: `ManageSubscriptionActionTest`.

### 9.4d Phase 4d — purchase visibility & MoR export (§4.7, D12) — WC-1…WC-6 COMPLETE

Paths verified 2026-08-09. **Build in slice order — WC-1 is a prerequisite for WC-4 being correct,
not merely complete.** All of it is commerce-plane; no data-plane, wire, schema, or format change,
so House Rule #9 does not fire. House Rule #2 does — see WC-5.

**WC-1 — ledger completeness (producer side, no reader) — SHIPPED.** `CreditNote`/`CreditNoteDraft`
+ `CreditNoteKind` (Refund/Chargeback), `appendCreditNote` on both impls, Postgres migration **v5**
(`credit_note` + its own `credit_note_number_seq` + an index on `invoice.stripe_invoice_id`), and
`charge.refunded` / `charge.dispute.closed` in `StripeWebhookRouting.kt`. As built:

- ⚠⚠ **The spec's "idempotency rides the existing `stripe_event` table" was wrong — necessary but
  not sufficient**, and this is the one thing here that would have silently mis-stated a return.
  Stripe's `charge.refunded` carries a **cumulative** `amount_refunded`, so a *second partial refund*
  on one charge arrives as a genuinely new event id whose total already includes the first. Credit
  notes are therefore keyed on the Stripe **refund id** (`stripe_reference_id`, UNIQUE); re-delivery
  and successive partials then both fall out with no arithmetic. Mutation-checked.
- ⚠⚠ Unapportionable VAT is stored **null, not `0`**: zero is a *claim* that no VAT was involved, and
  a false one corrupts the exact per-country total WC-4 fills a return from. Null says "a human owes
  this one". A dispute names a *charge* while invoices are keyed by session/invoice id, so disputes
  are normally unlinked ⇒ null by construction.
- Only a **lost** `charge.dispute.closed` credits; a dispute we won reverses no revenue (its fee is a
  cost, not a sales reversal), so crediting it would understate what we owe. `charge.dispute.created`
  deliberately skipped — §5.3 dispute *state* is separate work.
- VAT apportioned **proportionally to the original invoice's gross**, not re-derived from the stored
  bps: exact for a full reversal, and it avoids a second rounding on top of the one already taken
  when the rate was derived.
- An unattributable credit is **logged loudly and skipped**, never guessed — it would have no country
  either, so it could only be noise in the one total that matters.
- Two narrow reads landed here rather than in WC-2 because WC-1 *needs* them to apportion at the
  original rate: `findInvoiceByStripeId` + `findAccountByStripeCustomerId`. The bulk listings are
  still WC-2's.
- `ipCountry` now reads the card's issuing country where the payload carries it and ⚠⚠ **never falls
  back to `billingCountry`** — two identical evidences are not two evidences, and a fabricated match
  is worse than an honest absence (the export can flag a null; it cannot detect a fake). ⚠ On the
  *sale* events the payment method is usually not expanded, so this stays null there and is now
  **logged** rather than silently hardcoded; closing it fully needs expansion or a follow-up API call
  and belongs to §6's live-payload validation. The fake rail was deliberately **not** taught to emit
  it — fabricating a field real Stripe does not send on that event would hide the gap rather than
  cover it.
- ⚠ Postgres SQL is unverified (no DB in CI, no Postgres store test exists) — the file's existing
  posture. `InMemoryCommerceStore` is the tested source of truth.

**WC-1b — the subscription field mapping — SHIPPED.** Opened by WC-1's double-invoice flag, which
turned out to be **latent** and to be masking a live one. Both field questions were settled against
the Stripe API reference (`api/invoices/object`, `api/checkout/sessions/object`, 2026-08-09):

- ⚠⚠ **Renewals granted nothing.** `StripeClient` sets `subscription_data[metadata][product_id]` so
  the tier survives past checkout, but Stripe does not copy a subscription's metadata onto the
  invoice's own `metadata` — it exposes it as **`parent.subscription_details.metadata`**, an immutable
  snapshot taken at finalization. The code read only the top level, so every `invoice.paid` looked
  tier-less and hit the "missing email or product_id" skip. A **renewal arrives only as
  `invoice.paid`**. New `subscriptionAwareMetadata` reads both spellings (the shape moved under
  `parent`; which arrives is decided by the API version pinned on the *webhook endpoint*).
- ⚠⚠ **The paid window was the TTL, not the billing period.** `current_period_end` is a *Subscription*
  field, present on neither object, so `validUntil` always fell back to **7 days** on a monthly
  subscription. New `resolvePaidUntil` prefers the invoice **line item's** service period, then the
  invoice-level `period_end`. ⚠ Every candidate must be **in the future**: an invoice's top-level
  period legitimately collapses to a point (Stripe's own reference example shows `period_start ==
  period_end == created`), and a zero-length grant is worse than the fallback it replaces.
- ⚠⚠ **Net before the fix: a paying customer got ~7 days of the paid tier, then silently dropped to
  Free** — and the client renewal tick could not rescue them, since it re-mints from
  `currentEntitlement`, which by then resolves to the ~permanent Free row.
- ⚠⚠ **The two defects were entangled**, which is why they had to land together: fixing the metadata
  read *creates* the double-write, because both events then carry a usable tier. `invoice.paid` is
  now the **sole invoice writer** — it *is* the invoice; a Checkout Session has no number, no period
  and no tax breakdown of its own. Both events still **grant**, which is harmless (rows are additive,
  highest quota wins, so the checkout's short grant is superseded seconds later) and keeps a paid
  customer entitled if one event is delayed.
- ⚠ The withdrawal acknowledgement deliberately keeps the **narrow** top-level read: the ack is set on
  the session only, so a subscription-aware read would let a renewal manufacture a waiver. Narrowness
  makes that structurally impossible rather than merely untrue today.
- ⚠ Owner re-binding is now skipped when the owner is unchanged — otherwise every renewal appended an
  `account_owner` row and superseded the previous, churning A30 history for no change.

⚠⚠ **Why no manual pass caught this: the fake rail sent only `checkout.session.completed`.** The
statutory invoice, the subscription-metadata read and the billing period all live on the *second*
event, so none were ever exercised locally — a fake modelling half the traffic hid exactly the half
nobody looked at. `FakeStripeRouting` now emits **both** events in Stripe's order, with the tier on
`parent.subscription_details.metadata` and empty top-level `metadata`, as the real thing sends it.
Mutation-checked: reverting either fix now fails `FakeStripeRailTest` too, i.e. the local rail would
have caught the original bug. ⚠ A live test-mode round-trip is **still owed** (§6) — these are the
documented shapes, not captured payloads.

⭐ **The live test-mode round-trip ran on 2026-10-12** (dev lab, Stripe sandbox, API
`2026-06-24.dahlia`). Both events arrived, one payment was booked and the tier was granted for the
real billing period — and the invoice row was wrong in four fields, none of which a documented-shape
fixture could show:

| Invoice field | What the live `invoice.paid` does | Fix |
|---|---|---|
| `billingCountry` | spells it `customer_address`; only a Checkout Session has `customer_details` | read both |
| `vatAmountMinor` | has no top-level `tax`; the amount is the sum of `total_taxes[]` | read both |
| `paymentRef` | carries no `payment_intent`, no `charge`, and `payments` is not included unexpanded | `StripeClient.invoicePaymentEvidence` |
| `ipCountry` | carries no payment method | the same lookup (`latest_charge…card.country`) |

⚠⚠ The lookup happens **when the invoice is booked**, because the row is append-only (§5.2) and a
later event could not fill it in. One request: `GET /v1/invoice_payments?invoice=…` expanding
`data.payment.payment_intent.latest_charge` — four levels, Stripe's limit. It is skipped when the
payload already names both, so older endpoint versions cost no call. ⚠ A failed lookup still books
the sale with the two fields empty and a warning: refusing the event would make Stripe retry, but
would also hold up a renewal's grant on a Stripe outage. `StripeLivePayloadWebhookTest` pins the
shape from the captured event.

⚠ Also found, **not fixed here**: a rail-first purchase never reaches the realm. The webhook writes
commerce's ledger only; the signed record is client-pull through session-gated `/mint`, and after
Stripe's redirect no client signs in or mints — web has no mint call at all.

**WC-2 — store read primitives — SHIPPED.** `listInvoices(from, to)`, `listCreditNotes(from, to)`,
`listEntitlementHistory(accountId)` on `CommerceStore` + both impls. `invoice` finally has a read
path; until now its only `SELECT` in production was `nextval`. As built:

- ⚠⚠ **Ranges are half-open, `[from, to)`.** A VAT return is filed per period and adjacent periods
  share a boundary instant, so a closed range would put a midnight row in **two** quarters — once as
  revenue, once again as a correction basis. Half-open makes consecutive windows tile the timeline
  exactly. Mutation-checked: flipping to `<=` fails the tiling test and only it.
- ⚠ Ordered by the **statutory sequence**, not by `issuedAt` — two rows can legitimately share a
  millisecond, and an export needs a deterministic order.
- ⚠ `vatAmountMinor`/`vatRateBasisPoints` are read back **nullable** on the Postgres side too;
  mapping them through `getLong` would silently turn "could not be apportioned" into `0`, which is
  the exact false claim the nullable column exists to prevent.
- `InMemoryCommerceStore.invoices()`/`creditNotes()` are **gone**, replaced by
  `CommerceStore.allInvoices()`/`allCreditNotes()` test extensions over the real interface — the old
  helpers existed on one backend only, which is the shape that lets a test pass against a method
  production cannot call.
- ⚠ Still no Postgres test (no DB in CI); the in-memory impl remains the tested source of truth.

**WC-3 — purchases view — SHIPPED.** `GET /admin/purchases?from&to` (invoices + credit notes) and
`GET /admin/account?email=` (entitlement history + active-session count — CE-5 deferred both), same
host, same `AdminAuth`, same server-rendered `kotlinx.html` path. As built:

- ⚠⚠ **Typed dates are inclusive; store instants are half-open**, and `AdminDateWindow` is the one
  place they meet. "1–31 August" must include the 31st, so `toExclusive` is midnight at the start of
  1 September; passing the typed date straight through — the obvious reading — would silently drop
  **the last day of every period** from a filing that still looked complete. Resolved in **UTC**, so
  the same URL cannot mean different rows depending on which machine opened the console.
- ⚠ A *view* may default its range (current month to date); the **export** may not (§4.7) — an
  unbounded export is the bulk-extraction shape D8 objected to, a bounded listing on a firewalled
  page is not. The range is **audit-logged**, which is the shape WC-4 inherits.
- ⚠⚠ **The two operator-actionable rows are named on the page, not left as blank cells**: an invoice
  with only one location evidence ("⚠ missing — 1 evidence only") and a credit note whose VAT could
  not be apportioned ("⚠ unknown — reconcile by hand" / "⚠ unlinked").
- The page states **"do not file from this page"** — the per-country aggregation is WC-4's, and an
  operator filing from a listing that merely looks authoritative is the failure this phase exists to
  prevent.
- `countActiveSessions` is a **count, never the tokens**: a session token is a live credential with
  no reason to appear on the highest-risk page in the system. (It is a new store primitive; it
  belonged with WC-2's reads but was only needed here.)
- ⚠⚠ **Two defects found by rendering the page and reading it**, both of which every string assertion
  had already passed — the CE-6 lesson, applied: (1) money in and money out both rendered as a bare
  "Total", so they read as the same kind of figure and invited being added; they now carry their
  direction and their VAT. (2) Fixing that, the VAT total coalesced a null to `0`, restating **at the
  aggregate** the false claim the nullable column exists to prevent — caught by the assertion written
  for the per-row cell. Totals are now `VAT x` / `VAT ≥ x` / `VAT unknown` by how much is known.
  `AdminPurchasesRenderTest` writes the pages to `build/admin-render/` for review (no golden gate),
  following `ServicesCardRenderTest`.
- ⚠ `formatMinor` assumes a **two-decimal currency** — correct for EUR, 100× wrong for JPY; a second
  currency needs it revisited rather than reused.

**WC-4 — the export — SHIPPED.** `GET /admin/export/ledger.csv?from&to` (line items) +
`GET /admin/export/vat-summary.csv?from&to` (per-country totals, the shape a return is filed from),
over a pure `LedgerExport` so the arithmetic is testable away from HTTP. As built:

- ⚠⚠ **The no-identity rule lives in the row type.** `LedgerExportRow` has no `ownerId`, no email and
  **no `accountId`** — not filtered in a query where a later `SELECT *` could quietly reintroduce
  them, but absent from the shape, so a widening edit does not compile into a leak. A line traces
  back to an account through its **number** in the console, which is where identity belongs. Pinned
  on the rendered bytes, not the type.
- ⚠⚠ **Amounts are integer minor units, never decimal strings.** A German Excel reads `"11.90"` as
  **1190**, and a spreadsheet is by definition where this file is opened. The console keeps decimals
  for humans reading a page — a different job.
- Credit notes are **signed negative** throughout (a return is filed on the period's net movement),
  and `net + vat == gross` holds per country with a reversal in the window — the assertion the whole
  slice exists for.
- ⚠⚠ **Unattributable rows get their own `(unreconciled)` bucket, never a country total.** A summary
  that silently drops them balances perfectly and under-reports with nothing to show for it;
  folding them in at `vat = 0` would assert zero-rating. Mutation-checked — removing the partition
  fails exactly the two tests that should.
- ⚠ A **single-evidence** sale still enters its country total (the missing evidence is about
  *defending* the rate under audit, not whether to report it), but the summary row now names how
  many such documents it rests on — otherwise an operator reading only the summary never learns.
- Range is **mandatory** here, unlike the view: one absent parameter away from "everything" is the
  bulk-extraction affordance D8 refused. The **window** is audit-logged, at `warn`.
- ⚠ CSV escaping quotes separators/quotes/newlines and neutralises a leading `=`/`+`/`@` (a
  spreadsheet reads those as formulas); a leading `-` deliberately is **not** escaped, since negative
  amounts are the point and Excel reads them as numbers.

⚠⚠ **Found by reading the rendered CSV:** a **chargeback could not be linked to its invoice**, so a
fully-reversed sale showed its country total intact while an equal-and-opposite figure sat in
`(unreconciled)` — an operator filing without reconciling would remit VAT on a sale that never stood.
**Closed by WC-6 below.**

**WC-6 — the chargeback→invoice link — SHIPPED.** New nullable `invoice.payment_ref` (Postgres
migration **v6**) + `findInvoiceByPaymentRef`; the sale records the payment that settled it, and a
lost dispute resolves its invoice through the payment it names. As built:

- ⚠⚠ **The Invoice object has no `charge` field at all** in the current API — WC-4's note assumed one
  and was wrong. The payment hangs off `payments.data[].payment`, whose `charge` is documented as
  populated *only* when the charge has no PaymentIntent, so for ordinary card payments the
  **PaymentIntent** is the one that exists. Older API versions put `payment_intent`/`charge` at the
  top level. All four are read, for the same reason as WC-1b's metadata: the shape is decided by the
  API version pinned on the **webhook endpoint**, not by us.
- ⚠ `payments` is an **expandable** field. If a payload arrives without it the reference is null, the
  miss is **logged** rather than guessed, and linkage simply does not improve on WC-4 — which is what
  the live pass should check first.
- ⚠⚠ **No backfill, and not by choice**: the reference only ever existed in the event, so invoices
  written before v6 have nothing to recover it from and their disputes stay unlinkable. The
  `(unreconciled)` bucket therefore remains necessary, not vestigial.
- The refund path gained the same fallback, so a `charge.refunded` whose `invoice` is absent still
  apportions.
- ⚠ A dispute naming a payment we never invoiced links to **nothing**, rather than to some other
  sale — pinned by a test, since a wrong link would silently move money between countries.
- The fake rail emits the payment under `payments.data[].payment.payment_intent` with a null
  `charge`, the shape a real card payment produces; faking it at the top level would make the link
  look like it works against a payload Stripe does not send.

**Effect, from the rendered summary:** a €249 AT sale fully charged back now nets to
`AT,EUR,0,0,0,2` with **nothing** unreconciled, where before it reported €249 gross / €39.75 VAT
against an unattributed −€249.

**WC-5 — MFA + threat model — SHIPPED.** As built:

- **TOTP as a second header** (`X-Admin-Totp`), RFC 6238, hand-rolled beside `StripeSignature`'s
  hand-rolled HMAC. The shape is forced by what the console is: bearer-authenticated and curl-driven,
  so WebAuthn (needs a browser session) and mTLS (a Caddy concern) do not fit. Bearer token in a
  password manager, seed on a phone, an attacker needs both.
- ⚠⚠ **The gate moved to the admin *application*, not each route.** `adminModule` installs one plugin
  that checks both factors before anything runs, and the five per-route checks are gone. On the
  surface that joins A28 ⨯ A30 and now exports, a per-route check is one forgotten line from an open
  door; this way a route added later is covered by construction.
- ⚠⚠ **Accepted codes are burned.** A code stays arithmetically valid for its step plus skew (~90 s),
  so without this an intercepted request is replayable for that long and MFA merely shortens the life
  of a captured credential instead of ending it. Mutation-checked.
- ⚠ **One opaque 401 whichever factor failed** — distinguishing them tells a holder of a stolen token
  that the token is live, which is the fact the second factor exists to keep useless. The log tells
  them apart; the response does not.
- ⚠⚠ **`validate()` refuses to start** an admin console without `ADMIN_TOTP_SECRET` when
  `DEV_MODE=false` — the CE-2/CE-3/Ph3a treatment (log email sender, fake rail, dev seed). §5.3 called
  MFA "non-negotiable"; a startup refusal is what makes that true of the deployment rather than of
  the document. An unparseable seed also fails at startup, or it would present as "MFA configured"
  while rejecting every code — a console locked against its own operator.
- ⚠ Skew is ±1 step. Widening it widens the replay window proportionally; fix the server clock.

**Threat model amended** (`docs/sync/threat-model.md` surface #15 + the §"summary" line): read-only →
**read + lifecycle mutation + bounded export**, with MFA recorded as enforced and the reasoning that
the export does **not** extend A28 ⨯ A30 because the row type carries neither side. This also pays
the **CE-5 debt** that had been owed since the mutations shipped. ⚠ `commerce-end-to-end-dry-run.md`
§7 said surface **#11** (that is the contact handshake) — corrected.

**Tests.** WC-1 shipped `StripeReversalWebhookTest` (11: full/partial refund apportionment,
successive partials crediting once each, redelivery under a fresh event id, unattributable skip,
unlinked-VAT-is-null, lost vs won dispute, both evidence rules, and that a reversal leaves the
entitlement ledger alone) + 4 store pins in `InMemoryCommerceStoreTest`. 166 tests in
`:commerce-service`, 0 failures; `CommerceRelaySystemTest` green. ⚠ The idempotency guard was
**mutation-checked** — disabling it kills exactly the two tests that should die and no others.

Still owed by WC-2…WC-5: `AdminRoutingTest` (new routes authorised/refused, range required), a store
test per new listing on **both** impls, and an aggregation test asserting per-country
`net + vat == gross` with a credit note in range. ⚠ Assert the absence of `ownerId` in the export
output explicitly — that is the D12 mitigation, so it needs a test that fails if someone adds the
column back.

### 9.4e An iOS-only user cannot obtain ANY plan, including Free — OPEN (2026-08-17)

**Observed** while re-establishing an entitlement on a real iPhone: Control-on-iOS shows
*"No plan"* and offers no way to change it. `control-ios/Sources/ServicesView.swift` is 112 lines
and its own header calls it *"the read-only storage/plan status surface"* — no sign-up, no OTP, no
"Have a code", no checkout. The device can **report** an entitlement and never **obtain** one.

⚠⚠ **This is not the App Store policy consequence it looks like.** §9.4c's steering gate hides
*paid* tiers in store builds (Apple 3.1.x), but explicitly keeps **"Free signup + 'Have a code'"**
on Android — because no store rule prohibits creating a free account. iOS is therefore *worse off
than Android for a reason policy does not explain*: the surface was simply never built.

⚠⚠ **§9.4c's stated rationale has expired.** It reads *"Control has no iOS UI; the web app gates
itself"* — true when written, false since Control-on-iOS shipped. The conclusion (buy on the web,
reflect in-app) may still be right, but it now rests on a premise that no longer holds and was
never re-derived. Ph4b's own trigger — *"re-verify when mobile is concrete"* (§3.1 rails note) —
has likewise fired: iOS is concrete.

**Two questions, deliberately separated** — conflating them is what leaves the current dead end:

1. **Free sign-up on iOS.** No payment, no merchant of record, no store rule in play. Whatever is
   decided about paid rails, an iOS user should be able to create an account and land on Free, as
   Android already can. This is unbuilt UI, not a legal question.
2. **Paid purchase on iOS.** The genuine ⚖️ counsel question, and the one the rails table (§3.1)
   frames: App Store IAP (Apple as MoR, they remit VAT, commission applies) versus external
   checkout (we stay MoR, keeping the §5 invoice/location obligations we already carry). The DMA
   and recent US rulings loosened the historic IAP-only position, but the terms are
   region-specific, carry their own entitlement programs and disclosure requirements, and change
   often — **verify against current Apple terms with counsel rather than against this paragraph.**

⚠ A trap worth recording before anyone builds "Stripe in the EU, IAP elsewhere": the App Store
**storefront** a user is in is not their billing address and not where they are standing. Any
region-gated rail needs to state which of those three it keys on, and what happens when they
disagree — including for a user who moves.

→ (2) is **decided** in [`commerce-store-link-out.md`](commerce-store-link-out.md) §0 (2026-10-23):
App Store IAP on iOS and Play Billing in Play builds, like OneDrive, minted into the same
entitlement; Stripe stays for the web, desktop and a non-Play APK. That makes Ph4b the plan. (1) is
allowed meanwhile under Apple 3.1.3(f), provided the iOS surface does not say where to buy.

**Minimum honest fix, independent of (2):** iOS must not dead-end. Free sign-up costs nothing
policy-wise, and a device that cannot sell should still say where storage comes from rather than
render *"No plan"* with no affordance — cf. the same "state with no signal" family collected in
`docs/tech-debt.md` under the Control connectivity surface.

### 9.4f A purchase reaches the browser — the web claim + mint (2026-10-12)

**Found on the first live Stripe pass:** paying on the web left the Services card on Free. Nothing
was failing. The webhook writes commerce's ledger only, the signed `Entitlement` is client-pull
through session-gated `/mint`, and the web had no mint call and no way to save a commerce-signed
record — its one write was the dev-signed Free record at genesis. A plan bought in a browser
arrived only if another device of the realm happened to hold a session and minted it.

**As built (web):**

- `Storage.applyEntitlement` (`:tenshu-sdk-js`) saves the `/mint` body through the shared
  `saveSignedEntitlement`. ⚠⚠ It refuses a record for another owner or one no trusted key signed:
  the save does not verify, and the row *replaces* the owner's entitlement, so an unverifiable
  record would take a working Free plan down to "no storage".
- `PublisherClient.syncPlan()` (apex) = `/mint` + that save. It runs on sign-in, whenever the
  Services page opens with a session, and on the checkout result page.
- The result page is where the purchase is fetched. With a session it polls `syncPlan` until
  commerce answers with a paid tier — the redirect and the webhook race, so Free first means "not
  yet" — and reports the plan off the signed record, never off `?status=`. Without one it asks the
  buyer to confirm the **checkout** email (rail-before-registration, §3.2: the account was created
  by the webhook from the address typed into Stripe), and sign-in mints.

**Sign in first, then pay (same day, amending D5).** The confirm-your-email step above ran after
the money and relied on the buyer typing the same address twice. On the second live purchase three
addresses were in play — one per purchase and a third at sign-in — and the session minted Free
while two paid accounts sat unreachable. So on the web:

- `/commerce/checkout` takes the buyer's `sessionToken` and **requires it for `client: Web`**
  (`401` otherwise). It hands Stripe the account's existing `customer`, else `customer_email`, so
  the hosted page shows the session's address and the webhook finds the session's account.
- The picker and the checkout page route a signed-out buyer through the email step with
  `?return=checkout&product=…`, which carries on to the purchase once the code is entered.
- Closing the tab after paying is then harmless: the session exists, and Services mints when it
  next opens. The result page's confirm step remains only as the fallback for a browser that lost
  its session in between.

**One subscription per customer (same day).** A hosted Checkout always starts a NEW subscription,
and nothing stopped a customer who already had one: on the live pass Basic was bought three times
and all three kept billing. An "upgrade" through Checkout had the same shape — Premium beside
Basic, both charging.

- `/commerce/checkout` asks Stripe what is billing for the buyer's customer before it sells
  (`StripeClient.billingSubscriptions`). Same price → `409 already_subscribed`; another tier →
  `409 plan_change_required`; lookup failed → `502`, because the wrong guess is a second charge.
  ⚠ Asked of Stripe, not the ledger: a subscription cancelled at period end still shows a live
  entitlement here.
- **A plan change is made on the existing subscription, in the billing portal.** That needs the
  portal's *subscription update* enabled for the two prices, prorating with `always_invoice` (so an
  upgrade invoices at once and the grant follows) and scheduling a decrease at period end.
  ⚠ That is Stripe-side configuration, set per account and per mode — live needs it too.
- ⚠⚠ The webhook now reads the tier from the **price on the invoice's charged lines**
  (`billedProduct`), falling back to the `product_id` metadata. The metadata is written once at
  checkout and a portal change does not rewrite it, so a customer who moved to Premium would have
  been granted Basic on every renewal.

**Refunds credited nothing (same day).** The live `charge.refunded` carries `amount_refunded` and
the PaymentIntent, but no `refunds` list and no `invoice` — the list left the charge object in a
past API version, and the fixtures still carried both. Two real refunds were acknowledged and wrote
no credit note. The webhook now reads them with `StripeClient.refundsForCharge` when the event has
none, and links to the sale by the payment reference. ⚠⚠ A failed read answers `503` and is **not**
recorded as seen: an acknowledged event is never redelivered, and an uncredited refund leaves VAT
remitted on money we gave back. Nothing is granted on that path, so a retry costs nobody.

⚠ The fake rail keeps no subscriptions and never refuses. ⚠ A session-less native checkout has no
customer to look up, so it is not guarded. ⚖️ A plan change in the portal collects no withdrawal
acknowledgement of its own.

**Native follows (Android + desktop).** The same two gaps, closed the same way:

- `PublisherClient.startCheckout` sends the session and answers `SignInRequired` without one; the
  plan picker routes a signed-out buyer through the email step (`FreeAccountSignup(thenCheckout)`)
  and on to the purchase. The `409`s surface as `AlreadySubscribed` / `PlanChangeRequired`, so the
  one-subscription guard now covers native — it had no customer to look up before.
- `PublisherClient.syncPlan()` = `/mint` with the session, the signed record handed to the proxy on
  `redeemedEntitlements` (the seam that already persists commerce-signed grants). The checkout
  screen polls it while the customer is in the browser, bounded to 15 minutes, and the Services
  screen calls it whenever it opens signed in. ⚠ Confirmation still comes from the synced
  `StorageStatus`, never from that call's answer.
- A `401` on either drops the dead session, as on the web.

- ⚠⚠ **A `/mint` answer is only handed on when it is news** — a changed plan, or a window at least
  12 h further on (native `handOnIfNews`, web `lastApplied`). Every answer is a new record with a
  `validUntil` a few seconds later, and the save treats a later `validUntil` as a renewal, so the
  five-second poll was a stored-row write and a sync block per tick.
- ⚠⚠ **Both screens ask again when they come back to the front** (`onResumed`). Android suspends a
  backgrounded app, so the poll does not run while the customer pays in the browser (measured: it
  stopped ~70 s after Chrome came forward); the poll is bounded, so a late payment left the
  checkout screen waiting forever; and the billing portal changes the plan while the Services
  view model stays alive and signed in.

⚠ The service still accepts a native checkout with no session, for clients built before this.
⭐ Device-verified 2026-10-13 on the dev-lab emulator against the Stripe sandbox: sign-in, checkout
with the session (Stripe showed the account email), payment, plan on the card, and both `409`
refusals. ⚠ With a **local** `inAppCheckoutEnabled = true`: Android ships it `false` (Play steering,
§9.4c), so in a shipped build this checkout is reachable on desktop only.
⚠ iOS has no purchase surface, so only the stand-in client changed there.

**A lost chargeback takes the plan back (2026-10-13).** It used to correct the VAT ledger and
nothing else: the customer kept the storage to the end of a period they had been refunded for, and
— the subscription still being live — was charged again at the next renewal, which re-granted it.
On `charge.dispute.closed` with `status = lost` the webhook now, after the credit note:

- ends the account's still-valid **Stripe** grants (`CommerceStore.revokePaidEntitlements`), so
  `currentEntitlement` falls back to Free. ⚠⚠ The one place the entitlement ledger is not
  append-only: "stop reissuing" (§6) only revokes when the money stops, and here it was taken back.
  The chargeback credit note is the record of why the window was cut. Redeemed and Free grants are
  untouched; a refund still changes nothing.
- cancels **all** of the customer's subscriptions (we hold no subscription id to be narrower with).
  ⚠ If Stripe cannot be told, the event is answered `503` and not recorded, so it is redelivered.

⚠ The device keeps the record it holds until that record expires or it next asks for its plan; the
relay stops honouring a fresh bearer token at once, since `/token` reads the ledger. ⚠ An account
with no Free row (created by the webhook, never signed in) falls to *no* entitlement.

**One subscription per realm, not per account (2026-10-13).** An owner binding is superseded per
*account*, never per owner, so a realm that signs in with a second email is live-bound to both
accounts, and the new one has no Stripe customer for the guard to find: on the live pass one realm
bought Basic three times under three emails. Checkout now also asks Stripe about every *other*
account bound to the realm (`CommerceStore.accountIdsForOwner`) and answers `409
subscribed_under_another_account` — its own answer rather than a plan change, because the billing
portal is per account and this session cannot manage that subscription. It applies to a
session-less native checkout too.

⚠ Two things these changes do **not** weigh, on purpose, and which are decisions rather than bugs:

- A lost chargeback on an **old** payment ends the plan the customer is currently paying for and
  cancels the subscription, with no refund of the current period. One disputed payment is treated
  as the end of the billing relationship.
- The realm check answers by `ownerId`, which a session-less native checkout supplies itself — so
  the `409` tells a caller who already knows a realm's `ownerId` that the realm is paying. That
  caller could already open a Checkout for it; the id is not public.

**Three smaller gaps closed together (2026-10-13).**

- **A paid record is renewed before it expires**. The automatic writer asks for the
  product the *account* names, which is always Free, and it declined to touch a still-valid higher
  grant at all — so a paid record was never renewed and the device read as Free from its expiry
  until someone opened Services. Past the record's own renewal point the writer now asks the
  minter, and keeps the answer only if it grants at least the same quota **and** lasts longer.
  ⚠⚠ That second half is what keeps the 2026-08-08 protection: the dev minter echoes the Free it
  is asked for, and that answer is still never written over a higher grant.
- **Redeeming a code on the web saves the plan**. The 200 body is the signed record and the
  web threw it away. ⚠ `redeemed` now means *saved in the realm*; once commerce has spent the code
  every later failure is `notSaved`, never an error that invites redeeming again. A code redeemed
  without a session is bound to no account, so `syncPlan` cannot fetch it back.
- **The fake rail keeps subscriptions**, in memory, keyed by the customer id the webhook
  attaches to the account — so the one-subscription guard refuses on the dev rail as it does
  against Stripe. ⚠ A restart forgets them: an account whose customer predates it gets a `502` at
  checkout, as Stripe would for an unknown customer.

⚠⚠ **Production reaches none of this until commerce names the apex in `WEB_APP_ORIGIN`.** The
compose file dropped that key until 2026-10-30, so production commerce refused every browser call
while the dev lab, which sets it in its overlay, worked. How to switch it on:
`deploy/scaleway-commerce/README.md`.

⚠ **Still open.** The `notSaved` copy for a signed-out redeemer points at a support channel that
is not defined anywhere. The native deep link back from Stripe is unbuilt.

### 9.5 House rules that bite

#1 commerce PII (A28) never crosses to the relay — separate datasource, separate host. #2
threat-model amendment ships with Phase 4. #3 non-suspend `Operation` interfaces on any new
proxy op. #4 `EntitlementToken`/wire types stay schema-first. #7 run the named tests, don't
claim.

### 9.6 Doc cleanup (do alongside)

The `CLAUDE.md` in-flight table row + the `web/architecture.md` §3.4 host convention are
**already added** (this doc's handoff prep). Remaining cleanup, do with Phase 1: update
`control-subscription-ux.md` §3 (three tiers + redeem; drop the one-paid-tier assumption) and
flip its references from the superseded `tenshu-publisher.md` to this doc + the commerce plane.

### 9.7 Self-service account deletion — SHIPPED (2026-10-23)

Google Play requires an in-app **and** a web path to delete an account for any app that creates one
(`how-to/play-console-answers.md` §11.1 / §13 D2). Until this, closure existed only as
`/admin/accounts/close`, which kept the email row.

**The routes** (`routing/AccountDeletionRouting.kt`), both on the public API:

- `POST /commerce/account/delete/request` with **`{sessionToken}`** (Control, signed in) **or**
  **`{email}`** (the public page `tenshu.app/account/delete`, which has no session) → `202`, a
  **deletion code** is mailed (`AccountDeletionEmail`).
- `POST /commerce/account/delete/confirm` with the same identifier **plus `code`** → `200 deleted`.

Decisions, each pinned by `AccountDeletionRoutingTest` (19):

- ⚠⚠ **A fresh code is required even with a session.** An account here *is* an address plus the codes
  mailed to it; a session is a bearer credential on a device somebody else may be holding. Deletion is
  irreversible, so the inbox is the proof on both paths.
- ⚠ **Purpose-bound codes.** A deletion code lives under `account-deletion:<email>` in
  `email_verification` (`ErasedAccounts.deletionOtpKey`, no migration) — a sign-in code cannot delete,
  a deletion code cannot sign in or re-bind, and requesting one does not void a sign-in in flight.
  ⚠⚠ Disjoint **by construction**: `normaliseEmail` rejects `:` and requires one strict RFC 822
  address, so `/register` with `account-deletion:victim@x.com` is a `400`, not a write over the
  victim's pending code (found in review).
- ⚠ **No enumeration.** The `{email}` request always answers `202` and always sends a mail — the code,
  or "no account uses this address" (`sendNoAccountNotice`) — so neither status nor SMTP timing tells.
  That notice is also how a retried deletion learns it already went through. Both branches do the
  same DB work: an unknown address upserts a throwaway hashed code under the fixed
  `ErasedAccounts.DECOY_OTP_KEY` (never under the stranger's address). Budgets are `/register`'s
  (send) and `/verify`'s (guess).
- ⚠ **A lost confirm response.** The retry gets `401` (no account, no session). The app does not read
  that as "wrong code": on a `401` it asks `/account/session`, and if commerce says the session is
  gone it disconnects and says *"This device is signed out. If you just deleted your account, the
  deletion went through."* An unreachable probe is not evidence and changes nothing.
- **What confirming does, in order:** `eraseAccount` — close (keeping an earlier closure's stamp, so
  the relay's grace never restarts), replace the email with `erased:<id>` (no `@`: unreachable by any
  address lookup), clear `email_verified_at`/`display_name`, delete every session, both OTP rows and
  the marketing-consent row — **then** `SubscriptionCanceller.cancelFor` (close first, cancel second,
  the CE-5 asymmetry). Idempotent: a second call changes nothing (`erased_at`, Postgres migration v7,
  guarded `FOR UPDATE`).
- ⚠⚠ **Erasure is pseudonymisation of the operational row, not deletion of it** — and that is the
  answer to the statutory-retention question. The row keeps `id`, timestamps, `rail`,
  `stripe_customer_id`, `iap_original_txn_id`, the owner binding and `entitlement_history`, because:
  the closure sweep needs `(id, closed_at, live owner)` for as long as the grace window runs (dropping
  the row would *cancel* the content deletion — that is how reopen works); the cancellation sweep
  retries from the billing references; and `invoice`/`credit_note`/`acceptance` (§5.2, append-only,
  ~10 years, Art. 6(1)(c)) reference `account_id`. None of those names a person here; the processor
  (Stripe/Google) still holds the billing identity under the customer reference, as the statutory
  invoice documents require. ⚖️ Whether the processor-side customer object should also be deleted
  (Stripe's `DELETE /v1/customers` keeps invoices but drops the customer) is **left to counsel** —
  not done, because a refund or chargeback on a retained invoice still needs it.
- ⚠⚠ **The closure sweep no longer mints for a realm an open account still holds**
  (`closedAccountsWithOwners`, both stores). Erasure made this live: the same person signing in again
  from the same device creates a *new* account bound to the *same* `ownerId`, and the erased one's
  closure would otherwise reclaim the files the new one is using. The user-facing consequence is the
  policy's "reopening cancels the deletion" in a new form: **signing in again within the grace window
  keeps the files.** (It also stops one email's closure reclaiming a realm another email still pays for.)
  ⚠ Flip side: whoever knows an `ownerId` can bind an open account to it and hold that realm's
  deletion back (T-CB-11, Low).
- **Erasure is terminal on this plane.** `reopenAccount` is a no-op on an erased row; the Stripe
  webhook checks the event's **customer** before its address, so a late renewal for an erased
  customer is refused instead of creating a new account from the email on the event.
- **Grace window: unchanged — the existing 30 days** (`ACCOUNT_CLOSURE_GRACE_MS`, policy §4.3), measured
  from the recorded closure. ⚠⚠ **Content is deleted only while both `ACCOUNT_CLOSURE_RELAY_URL`
  (commerce) and `ACCOUNT_CLOSURE_RECLAIM` (relay) are set.** **Both opened in production on
  2026-10-24** by the maintainer's decision, ahead of counsel's review of the retention text (still
  owed; production then held a single account, none closed). The copy (erased *now*; content
  *scheduled* for deletion after a 30-day grace period) is true with them open. The Data safety
  upload gate (`"upload_gate"` in `store/control/play/data-safety.json`, refused by
  `play_publish.py data-safety` without `--closure-flags-open`) was removed with them; put it back if
  a flag is ever closed again.

**Rails.** One shared `SubscriptionCanceller` in `main` serves the admin close, the self-service
delete and the retry sweep, over **Stripe and Google Play**. On Play it calls
`subscriptionsv2:cancel` with `DEVELOPER_REQUESTED_STOP_PAYMENTS` — no further renewals; it also
re-cancels a *user*-cancelled subscription (a user can restore that one) and accepts a refused cancel
only for an ended, cancelled or PENDING subscription (`GooglePlayClient.cancelRenewals`). Erasure keeps
`rail`, `iap_original_txn_id` and the `IapGoogle` ledger rows that `cancelGoogle` keys on, so the sweep
can still retry (`deletingStopsTheGooglePlaySubscription`). **Apple** cannot be cancelled by us
(`commerce-store-link-out.md` D6); the UI says so.

**Clients.** Control (Android/desktop, shared Compose): Services → *Delete account* (email identity
only) → `AccountDeletionScreen` (explain → code + "I understand" → done), `AccountDeletionViewModel`,
`PublisherClient.requestAccountDeletion`/`confirmAccountDeletion`; a confirmed deletion drops the local
session as `disconnect()` does. ⚠ **iOS Control's SwiftUI `ServicesView` has no button yet**: it needs
the same two calls through the iOS SDK's publisher seam plus a SwiftUI sheet with the same copy;
until then an iOS user deletes on the web page. Web: `web/apex` `/account/delete` (public half, exempt
from the construction curtain via `lib/alwaysPublicPaths.ts`), `{email}` variant.

**Postgres.** Verified against a real Postgres 16 (`PostgresAccountErasureTest`, opt-in via
`COMMERCE_TEST_DATABASE_URL`; skipped — and passing vacuously — without it).

### 9.8 Store-review sign-in — BUILT, off until configured (2026-10-24)

Decision D13; security posture T-CB-12; operator runbook `how-to/play-console-answers.md` §14.

**Config** (`CommerceConfig`, passed through `commerce-service/docker-compose.yml` and
`deploy/scaleway-commerce`):

| Env | Meaning |
|---|---|
| `REVIEW_SIGNIN_ACCOUNTS` | `<address>=<hex HMAC-SHA256 of that account's 6-digit code>`, comma-separated, one per store. Each address trimmed, lower-cased and required to be what `normaliseEmail` returns unchanged; each digest 64 hex characters; no address twice, no digest twice (one code per store). Split on the **last** `=`, since a local part may contain one. ⚠ A secret: the addresses are unpublished. |
| `REVIEW_SIGNIN_HMAC_KEY` | The HMAC key, ≥ 32 characters (`openssl rand -hex 32`). A box secret like `ISSUER_SEED`. |

Both unset → **off**, and the routes behave as before. One without the other, or any malformed entry
→ `validate()` refuses to start, naming the variable and the entry's **position** — never an
address, a digest or the key. The Terraform module refuses the plan for the partial pair.
`CommerceConfig.toString` (and `SmtpConfig`'s, `RedemptionSeed`'s) now redacts every secret, the
review ones included (`CommerceConfigToStringTest`).

⚠⚠ **Why keyed and not salted.** Six digits are 10⁶ candidates; an unkeyed SHA-256 — salted or not,
since a salt is public — gives the code back in under a second to anyone who sees it. HMAC under a
secret key is the simplest construction where the digest alone is worthless. It does not help
against someone who reads `deploy.env` (key and digests are both there), but that reader already
holds the issuer seed and the database password.

**Routes** (`ReviewSignIn`, the single home of the rules; `AccountRouting`, `AccountDeletionRouting`):

- `/register`, listed address: the same `202 {"status":"otp_sent"}`, the same per-IP/per-email send
  budget, the same account + acceptance writes, and the same generate-hash-upsert — onto the deletion
  route's never-confirmable decoy key — but **no mail**, and any pending mailed code for the address
  is deleted. ⚠ The SMTP round trip is the one thing absent, so the latency differs; with an
  unpublished address that is a membership test nobody can aim.
- `/verify`, listed address: **that account's** code only — `MessageDigest.isEqual` over the HMAC of
  the trimmed candidate. A mailed code is never consulted. Success is the ordinary completion
  (verified, owner bound, Free entitlement, session) via the shared `completeSignIn`.
- `/delete/request`, listed address: `202`, no mail, no deletion code stored. `/delete/confirm`: the
  account's code only. After erasure the address finds no account (`erased:<id>`), so signing up again
  creates a fresh one and the code works on it.
- Every other address never reaches the review check, and `ReviewSignIn.check` refuses an unlisted
  address even if one did. One store's code never opens the other's account (tested both ways).
- ⚠⚠ **Lockout — the bound that makes six digits acceptable:** 10 wrong codes on one account within
  a **rolling 24 hours** lock **that account** (sign-in and deletion share the counter): every attempt
  is `429 rate_limited`, the right code included, until the oldest of those ten ages out.
  **Persisted** (`PersistedFailureLimit`) in the existing `email_verification` table, no migration:
  one row per account under `review-signin-failures:<address>` whose `code_hash` column holds the
  last ten failure timestamps. No address can normalise to that key (`normaliseEmail` rejects `:`),
  and `eraseAccount` does not delete it, so neither a restart nor an erasure resets it; a correct code
  does not clear it either. ⚠ The read-modify-write is serialised in-process only (commerce is one
  process; the worst race loses a stamp). Arithmetic: 10⁶ codes at 10 a day ≈ 274 years to exhaust,
  ≈ 0.37 % per account per year — for an attacker who has already found the address.
- ⚠ **Rotation ends sessions.** At boot, `endSessionsOnRotation` compares each account's digest
  fingerprint (an HMAC of the digest under the key) with the one recorded last boot in the
  `review-signin-fingerprints:` row, and deletes every session (`CommerceStore.deleteSessionsForAccount`)
  of an account whose code changed, that is newly listed, or that was removed — the feature switched
  off runs the same comparison against an empty set. A key rotation changes every fingerprint, so it
  resets every review account.
- **Purchases are allowed.** Nothing stops a review account from buying a plan — reviewers must be
  able to test it. So the runbook's rotation step includes checking the review accounts for purchases
  or subscriptions.
- Logged: that a review sign-in, request or lockout happened, as `***@tenshu.app` plus the account's
  `shortId`, and at boot a count of accounts and of sessions ended — never a code, the key, a digest or
  an address. `LogStatementPiiGuardTest` also flags `*codeSha256*`, `*codeHash`, `*HmacKey`,
  `candidate`, `expectedMac(s)`, `*.code` and a bare `code` (the dev-only `LoggingEmailSender`'s two
  lines carry the exemption marker; printing the code is that sender's job).

Pinned by `ReviewSignInRoutingTest` (17) and `CommerceConfigReviewSignInTest` (12).
⚠ **Not verified:** the Postgres store end to end (the feature's only new SQL is
`deleteSessionsForAccount` and the extra key in `eraseAccount`'s delete; the routing tests run on the
in-memory store), and a real deployment — the env lines are set on the box by hand (runbook §14).

### 9.9 Daily failed-code cap — BUILT, on by default (2026-10-24)

Decision D15; threat model T-CB-13. `OTP_FAILED_PER_EMAIL_PER_DAY` (default **20**, `<= 0` disables;
passed through `docker-compose.yml`): failed one-time-code checks per address per rolling 24 hours,
across `/verify` and `/delete/confirm` and across every code issued. Over it, the route answers
`429 {"error":"rate_limited"}` — the hourly budget's shape — **before** looking at any code, so the
right code is refused too until the oldest counted failure ages out. Review addresses are not on
this path (they have their own, tighter lockout, §9.8).

- **What counts:** every `401` on those two routes for a normal address — a wrong code, an expired or
  burned one, **and no pending code at all**. ⚠ Counting only real mismatches would make the 429 an
  oracle for "a code was issued to this address" — on the deletion route, for "an account uses this
  address", which T-CB-11 forbids.
- **Storage:** the same `PersistedFailureLimit`, one `email_verification` row per address under
  `otp-failures:<sha256(address)>`. ⚠ Keyed by the **hash**: counting refusals for addresses no
  account uses means strangers' addresses would otherwise sit in the table in the clear, against the
  deletion route's decoy-key posture. A pseudonym, not anonymisation (a guessed address can be hashed
  and looked up). `eraseAccount` deletes the row (both stores) because it is about the person; a row
  whose stamps have all aged out is deleted on its next read. ⚠ There is no sweep, so a stranger's
  hashed row lingers until that address is checked again.
- **Unchanged:** the per-code attempt burn (`OTP_MAX_ATTEMPTS`, 5) and the hourly budgets still apply
  first; the module's default is production's, so the test host runs what the box runs.

Pinned by `OtpFailureCapTest` (10): failures across several issued codes, the right code refused
over the cap, restart, the 24 h roll, per address, deletion and sign-in sharing one count, the
no-pending-code case, the hashed key, erasure, and the stale-row cleanup.

### 9.10 Schema re-cut + the append-only rule enforced — BUILT (2026-10-28)

The coming complete reset (relay, commerce and dev-lab databases all recreated empty — ⚠ not yet
done; until it is, the deployed commerce database is pre-reset and this build refuses it) let the
commerce schema be squashed. `deploy/reset-test-data.sh` reads the expected version as the max
`version = N,` in `CommerceDatabase.kt` — 1 now. `CommerceDatabase.MIGRATIONS` v1–v8 became **one baseline v1** that creates
the final schema directly. The server half of the same re-cut is `core-schema-evolution.md` Appendix A.3.

**Final schema (v1).** `account` (… `closed_at`, `erased_at`), `account_owner`, `entitlement_history`,
`email_verification`, `redemption_code`, `session`, `acceptance` (… `privacy_version` nullable, `ip`,
**`kind TEXT NOT NULL`**), `marketing_consent`, `invoice` (… `payment_ref`), `credit_note`, `stripe_event`,
the sequences `invoice_number_seq` / `credit_note_number_seq`, and `schema_baseline`.

- ⚠ **Nulls kept where they carry meaning:** `closed_at` (absent = Active; a 0 would read as "closed at
  the epoch", past every grace window), `erased_at` (absent = never erased), `payment_ref` (an invoice
  settled without one), `privacy_version` (the purchase waiver accepts no privacy notice), the
  credit-note `vat_*` (null = could not apportion). `acceptance.kind` lost its null: NULL meant "a
  pre-v4 Registration row", none exist, and the only writer (`recordAcceptance`) always binds the enum.
- ⚠⚠ **A pre-reset database is refused, not migrated.** Its recorded version (8) is above the new
  baseline, so the runner would have skipped every migration silently. The baseline writes a
  `schema_baseline` row (`cut = '2026-10-reset'`, **`2026-11-reset`** since the 2026-11-05 re-cut folded
  the never-verified-account expiry triggers (old v2) into v1 — `core-schema-evolution.md` Appendix A.6); `refusePreResetLadder`
  fails startup on any versioned database without the current marker, with *"wipe it"* — including one at old v1, which the number alone
  could not distinguish. Future migrations append v2, v3, … as before. The runner holds a
  `pg_advisory_lock` while it migrates, so two replicas booting on an empty database cannot race the
  baseline.

**The append-only rule (D10) is now enforced, not assumed.** Until this it lived only in comments
("a deployment revokes UPDATE/DELETE") — no deploy ever did, and none could have helped: both cloud-inits
run Postgres with `POSTGRES_USER=commerce` and the app connects as that user, i.e. the **owner and a
superuser**, whom privileges do not bind. So the baseline installs a `BEFORE UPDATE OR DELETE OR
TRUNCATE … FOR EACH STATEMENT` trigger (`refuse_statutory_mutation()`) on **`invoice`, `credit_note` and
`acceptance`** (`CommerceDatabase.APPEND_ONLY_TABLES`); it raises `insufficient_privilege` for any
attempt, even one matching zero rows. Triggers bind owners and superusers alike.

- ⚠ **A guard against bugs and stray edits, not against the owner**: `ALTER TABLE … DISABLE TRIGGER` or
  `session_replication_role = replica` bypasses it. That is the honest ceiling with one DB role; a
  separate non-owner app role + `REVOKE` would be the next step if it is ever needed.
- ⚠ **Erasure (§9.7) never touches these tables** — it updates `account` and deletes from
  `email_verification`, `session`, `marketing_consent` (checked: no store method updates or deletes a
  statutory row). Consequence worth knowing: **`acceptance.ip` survives erasure**, as evidence of
  what was shown and confirmed (Art. 6(1)(c) and (f), as the privacy notice and
  `policy/erasure-request-runbook.md` state). If counsel ever wants it scrubbed, that is a migration
  that drops the trigger on purpose, not a quiet `UPDATE` — the trigger will refuse the quiet one.
- ⚠ **The ~10-year retention purge needs the same.** Nothing purges statutory rows today; when a purge
  of rows past their retention is built, it is a migration that drops (or narrows) the trigger, never a
  `DELETE` the trigger will refuse.
- ⚠⚠ **One narrowed case: never-verified accounts (migration v2).** `UnverifiedAccountExpirer`
  (hourly) deletes an account that is *only* a code request — never verified, created **and** last
  `/register`-ed more than 30 days ago, open, never erased, Free rail, no Stripe/IAP reference and no
  row in any other account table — together with its `Registration` acceptance rows and its codes
  (`CommerceStore.expireUnverifiedAccounts` lists the guards). Counsel (maintainer, 2026-10-29): no
  contract was concluded, so the request record proves nothing worth ten years of a stranger's IP;
  deleting it is data minimisation, and the privacy notice says so in its retention list. v2 keeps
  `UPDATE`/`TRUNCATE` refused and splits `DELETE` into a statement trigger (refused unless the
  transaction set `tenshu.expire_unverified`) and a row trigger (only `kind = 'Registration'` rows of an
  account that still exists, unverified, open, never erased — so the account row goes **after** its
  acceptance rows). `invoice` and `credit_note` are unchanged. Pinned against a real Postgres by
  `UnverifiedAccountExpiryTest` (opt-in, `COMMERCE_TEST_DATABASE_URL`).
- ⚠⚠ **`INSERT … ON CONFLICT DO UPDATE` is refused on these tables even when nothing conflicts** — the
  statement counts as an `UPDATE` for a statement-level trigger. A future "idempotent" write must use
  `ON CONFLICT DO NOTHING` (fine — `appendCreditNote` already does, and `PostgresCommerceSchemaTest`
  pins both halves).

Pinned against a real Postgres 16 by `PostgresCommerceSchemaTest` (5; opt-in via
`COMMERCE_TEST_DATABASE_URL`, vacuous without it): every statutory table refuses `UPDATE`, `DELETE`
(also a zero-row one) and `TRUNCATE`, and erasure still succeeds beside them; `kind` is required; a
pre-reset ladder at v8 and at v1 is refused; the new baseline reopens. Each guard was broken once
(trigger reduced to `TRUNCATE` only; the marker check disabled) and the suite went red.
