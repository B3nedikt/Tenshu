# Commerce end-to-end dry run — one demonstrable flow before Stripe

**Status:** v1.4 (2026-08-27). **CE-1…CE-7 all shipped**, plus the cancel-on-close follow-up, its refund policy, and the checkout withdrawal acknowledgement that policy surfaced. ⭐ **The ⚖️ mail-provider item is CLOSED** (2026-08-27, §CE-2): the provider account is live in production and all four DNS records resolve. What remains is the manual pass itself, which is the maintainer's, plus counsel's read of the acknowledgement wording.
**Owner:** Benedikt

**Goal, in the maintainer's words:** *"at the end the complete flow can be shown once. We can mock
Stripe for now, but all including a commerce-service UI, real OTP with email and synchronisation
commerce-service ⇒ relay I want to be able to test once before the Stripe integration."*

**Consumes:** [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) (the built
account lifecycle), [`commerce-billing-plane.md`](commerce-billing-plane.md) (canonical
architecture), [`publishing-commerce-reconciliation.md`](publishing-commerce-reconciliation.md)
(the two-carrier model), [`control-subscription-ux.md`](control-subscription-ux.md) (the UX this
surfaces), [`retention-quota-model.md`](retention-quota-model.md) (usage + closure),
[`second-device-entitlement-enforcement.md`](second-device-entitlement-enforcement.md) (the record
carrier), and the [`reset-and-commerce-test.md`](../how-to/reset-and-commerce-test.md) runbook this
supersedes for *local* verification.

> Read **§0** (what is actually built), **§1** (what the sweep found), and **§4** (the phasing).
> §2–§3 are the goal and the decisions behind the phasing.

---

## 0. As-built sweep (verified against the tree, 2026-08-05)

The prevailing impression — *"many parts are still mocked, and we don't have much UI"* — is
**mostly an artefact of one default**, not of missing code. `TENSHU_BILLING_MOCK` defaults to
**`true`** in every non-release build, on all three platforms, so an ordinary
`:control-desktop:run` or emulator install never touches `:commerce-service` at all. What that
default hides is a fairly complete plane.

### 0.1 Server — `:commerce-service`

| Piece | State | Where |
|---|---|---|
| Email-OTP register / verify / claim / session refresh, hashed codes, TTL + attempt burn, uniform 401s, no enumeration oracle | **Built** | `routing/AccountRouting.kt` |
| Durable account store (accounts, owner bindings w/ supersede, sessions, entitlement ledger, acceptances, invoices, Stripe events) — Postgres + in-memory twin | **Built** | `store/` (`PostgresCommerceStore`, `InMemoryCommerceStore`) |
| Session-gated `POST /commerce/entitlement/mint` (synced-record fields + signature) | **Built** | `routing/MintRouting.kt` |
| Session-gated `POST /commerce/entitlement/token` (relay bearer token) | **Built** | `routing/TokenRouting.kt` |
| Identity-light `POST /commerce/entitlement/redeem` (founder / privacy codes) | **Built** | `routing/RedeemRouting.kt` |
| Stripe checkout + billing portal + **signature-verified** webhook (ledger + statutory invoice + idempotency by event id) | **Built, real** | `routing/CheckoutRouting.kt`, `StripeWebhookRouting.kt`, `stripe/` |
| Account closure: minter, repeating delivery sweep, `closeAccount`/`reopenAccount` | **Built, ON in production since 2026-10-24** | `AccountClosure*.kt` — runs only with `ACCOUNT_CLOSURE_RELAY_URL` set |
| Read-only admin console (`GET /admin/users`), token-gated, own host/port | **Built** | `routing/AdminRouting.kt` |
| Config + `validate()` guards (no dev seed in prod, webhook secret required with the Stripe key) | **Built** | `CommerceConfig.kt` |
| **Outbound email** | **Log-only** at sweep time → **CE-2 shipped**: `SmtpEmailSender` + a production refusal of the log sender | `EmailSender.kt` |
| **Rate limiting** | **Absent** at sweep time → **CE-2 shipped**: per-IP + per-email OTP budgets | `RateLimiter.kt`, `AccountRouting.kt` |

### 0.2 Relay — `:sync-relay-server`

| Piece | State |
|---|---|
| `X-Entitlement` verification against `ENTITLEMENT_ISSUER_PUBKEY`, accepting **either** the short bearer token **or** the synced signed record (second-device Option 4) | **Built** |
| `ENTITLEMENT_ENFORCE` gating `/sync/push`, content `PUT`, share + grant drops (402) | **Built** |
| Per-owner quota from the token's asserted `quotaBytes` | **Built** |
| `GET /sync/usage` → live per-owner bytes | **Built and consumed** — wired into `initEntitlementStorageReader(usageReader = …)` on Android, desktop and iOS |
| `POST /sync/account/closure` + `AccountClosureReclaimer` | **Built, ON in production since 2026-10-24** — registered only with `ACCOUNT_CLOSURE_RECLAIM=true` |

> ⚠ `CLAUDE.md`'s retention-quota row still carries *"`StorageStatus.usedBytes` is MOCK-ZERO"* in
> its Face-C description. That was true when the row was written and is **no longer** — the P1
> usage seam shipped and is wired in all three platform DI graphs (the same row's header records
> P1 as implemented, so the entry disagrees with itself). Worth a clarifying edit. A zero usage
> figure seen on device today is real, but it comes from §1.1 below, not from the relay.

### 0.3 Client — Control

| Piece | State |
|---|---|
| `CommercePublisherClient` — register / verifyOtp / redeem / startCheckout / openBillingPortal / disconnect, session persisted to `SecureStorageDelegate`, session feeds the mint + token clients synchronously | **Built, real** |
| `MockPublisherClient` — accepts any 6 digits, grants checkout locally, self-signs with the dev key | **Built** — and **selected by default in every debug build** |
| Services tab: empty state, signed-in account card, synced-plan card for a session-less paired device, billing-portal launch | **Built** |
| Account picker with real tier copy + prices (Free / Basic €2.99 / Premium €9.99), "have a code" path | **Built** |
| Email-OTP sign-up **and** sign-in (`FreeAccountSignup(signIn=)`), edit-email back step | **Built** |
| Checkout screen (browser launch, waits on the synced entitlement, never trusts the browser return) | **Built** |
| Redeem (`ReiKeyScreen`) + disconnect confirm | **Built** |
| Quota soft-limit banner at 80 % over the real synced `StorageStatus` | **Built** |

### 0.4 Ops

Terraform for the commerce droplet, `redeploy-commerce.sh`, `reset-test-data.sh`,
`deploy-commerce-digitalocean.md`, and the `reset-and-commerce-test.md` runbook — all **built**,
all pointed at a **deployed droplet**.

---

## 1. What the sweep found

Four things, of which two are defects and two are missing pieces. Ordered by how visible they are
in the flow the maintainer wants to show.

### 1.1 ⚠⚠ The signed-in account card is a hardcoded stub — on the *real* client

`CommercePublisherClient` builds its `PublisherAccount` with literals, in both the post-verify path
and the session-rehydrate path:

```kotlin
PublisherAccount(
    plan = PublisherAccount.Plan.Free,      // always
    storageUsedBytes = 0L,                  // always
    storageLimitBytes = FREE_TIER_LIMIT_BYTES, // always
    …
)
```

Nothing ever revises it — there is no read-back endpoint and no subscription to the synced
entitlement. So after a successful paid checkout or redeem, the account card **still says "Free ·
0 B of 5 GiB"**, indefinitely.

It is worse than "stale", because `activeSyncedStorage` gates the *correct* card on
`account == null`:

```kotlin
if (account == null && status != null && status.hasStorage) { … }
```

⇒ **signing in on a device actively replaces live, correct figures with hardcoded wrong ones.** A
paired device that has *never* signed in shows the true plan and the true usage; the moment it
signs in, it regresses. This is, on its own, most of the "the UI is mocked" feeling — the surface
is built, but the number on it is a literal.

### 1.2 ⚠ A closed account keeps minting

`gateBySession` checks the session, the current entitlement and the live owner. It does **not**
check `AccountStatus`. `currentEntitlement` filters on the validity window only, and the Free
ledger row is written with a ~10-year window (`FREE_ENTITLEMENT_WINDOW_MS`).

So a **closed** account continues to mint synced records and relay bearer tokens for a decade —
while the closure sweep, on the other side of the same process, is telling the relay to delete that
owner's content. The two halves of retention P3 disagree with each other. It had not bitten while both flags were
off; ⚠ they are ON in production since 2026-10-24, so check this has been resolved (CE-5's
`gateBySession` refuses a closed account) before relying on it.

### 1.3 Real OTP email does not exist, and the route that would send it is unmetered

One `EmailSender` impl, `LoggingEmailSender`, hardcoded — no SMTP, no provider, no config key. The
documented way to get an OTP today is `ssh … docker compose logs commerce | grep -i OTP`.

Coupled to that: **`:commerce-service` has no rate limiting of any kind.**
`POST /commerce/account/register` is unauthenticated by design (the verified email *is* the sybil
gate), takes an arbitrary address, and — the moment a real sender is wired — sends an email per
request at a per-message price against our sender reputation. The relay already carries
`GENESIS_RATE_LIMIT_PER_HOUR` for exactly this shape of unauth route; commerce carries nothing.
The limiter is therefore **not** a follow-up to the real sender; it is part of it.

### 1.4 Nothing proves commerce → relay, and nothing can be stood up locally

- **No automated coverage spans the two planes.** `EntitlementEnforcementSystemTest` is good and
  runs a real relay with enforcement on — but it seeds a record it signs itself with
  `EntitlementSignature.DEV_SEED`. The `:commerce-service` process is never in the loop, so
  "commerce mints → relay accepts" has never been asserted anywhere.
- **No local stack.** Relay, commerce and Postgres are three separate manual starts with a public
  key to copy between them by hand, and the how-to reaches for a DigitalOcean droplet. A flow that
  needs a droplet to demonstrate is a flow that gets demonstrated once and then rots.

Also noted, smaller: the admin console is read-only (no close/reopen, no entitlement history, no
session view), so closure — the one destructive capability in the plane — has **no operator
surface at all**; and there is no "resend code" on the OTP step (the user must back out to the
email step, which re-registers and re-sends, so the capability exists but is not named).

---

## 2. The goal

**One command brings the whole plane up locally; one pass through Control exercises every hop; CI
keeps it honest afterwards.** Concretely, the flow to be shown once (§5 spells it out):

```
Control (real client, mock OFF)
  → register(email)              → commerce → a REAL email arrives with the OTP
  → verify(code)                 → commerce binds ownerId, opens a session
  → mint                         → commerce-signed Entitlement → syncs into the realm
  → token                        → X-Entitlement presented to the relay
  → relay (ENTITLEMENT_ENFORCE=true) accepts the push
  → upload a file in Drive       → usage gauge moves off /sync/usage
  → buy Premium (FAKE Stripe)    → real webhook → ledger + invoice → new entitlement
  → the plan and the quota change on THIS device and on a paired second device
```

Everything in that line is real except the payment processor.

---

## 3. Decisions

**D1 — Mock Stripe at the `StripeClient` seam; keep the webhook path real.**
The fake rail's `createCheckoutSession` returns a URL to a commerce-hosted confirm page which, on
*Confirm*, posts a **correctly HMAC-signed** event to the real `POST /commerce/webhook/stripe`. So
signature verification, idempotency, account create-or-find, owner rebinding, the entitlement
ledger and the statutory invoice row all execute unchanged, and the later Stripe swap is a config
flip rather than a different code path.
*Rejected:* granting directly from `/checkout` and skipping the webhook — it would leave the money
path, which is precisely the part we most want rehearsed, entirely unexercised.

**D2 — The billing mock stays default-on; the dry run is opt-out.**
`TENSHU_BILLING_MOCK=false` + `TENSHU_COMMERCE_URL=http://localhost:8081`, as the existing script
already does. *Rejected:* flipping the default — every emulator run would then require a live
commerce process, and `MockPublisherClient` is genuinely the right thing for UI work.

**D3 — `EmailSender` grows a real impl behind config, and `validate()` refuses the log sender in
production.**
Same shape as the existing `ISSUER_SEED` guard: `DEV_MODE=false` with no mail transport configured
⇒ **refuse to start**. The log sender prints the verification secret to the server log; shipping it
is the same class of mistake as shipping the dev signing key, and it deserves the same treatment.

**D4 — The limiter ships in the same phase as the sender.** Per-IP **and** per-email budgets on the
two routes that send mail (`/register`, `/claim`), plus a per-email verify budget above the
existing attempt burn. See §1.3 for why this is not a follow-up.

**D5 — The account card reads the synced entitlement; it does not keep a local guess.**
Delete the hardcoded figures. `PublisherAccount` keeps what only the local session knows —
identity method, email, display name — and plan + usage come from the same `StorageStatus` the
synced card already uses, via `planTierForLimit`. The `account == null` gate in
`activeSyncedStorage` goes away with them: both cards read one source, they differ only in which
*actions* they offer (D11's descriptor-vs-session boundary is about capability, never about the
number).
*Rejected for v1:* a `GET /commerce/account/me` read-back. It would be a second source of truth for
a figure the device already holds, and it would be wrong offline. Add it later only for facts the
realm genuinely does not carry — rail, next renewal date, invoice list.

**D6 — CI proves the two-plane hop; the droplet proves nothing repeatable.**
A SystemTest boots the real `:commerce-service` Ktor module in-process beside the real relay. The
manual pass then confirms the things a test cannot see (a real inbox, a browser, a second device).

---

## 4. Phasing

Each phase is independently shippable. **CE-1 … CE-4 + CE-6 are the demo path**; CE-5 and CE-7 are
the correctness and regression tail and do not block the pass.

### CE-1 — Local stack, one command — **SHIPPED**
A compose file (or script) standing up relay + commerce + Postgres wired to each other on loopback,
plus a `run-control-desktop-local-commerce.sh` sibling of the existing droplet-pointed script.
`ENTITLEMENT_ENFORCE=true` from the start, so the demo runs under the posture production will use.
⚠ In `DEV_MODE=true` the issuer public key is the pinned `EntitlementSignature.COMMERCE_PUBLIC_KEY`
— **no key needs copying between the two planes locally**, which is what makes one command
possible.

### CE-2 — Real OTP email + rate limiting (D3, D4) — **SHIPPED**
`SmtpEmailSender` (Angus Mail; SMTP rather than a vendor HTTP API so provider choice stays a
`deploy.env` change), selected in `Application.kt` from new `SMTP_*` config; `LoggingEmailSender`
stays the unconfigured dev default and is now a **startup refusal** when `DEV_MODE=false`. Plain
text + minimal HTML alternatives. Per-IP and per-email budgets on `/register` and `/claim`,
per-email on `/verify`, all wired through the compose file and the `fromEnv` table.

**As built, worth knowing:**

- ⚠⚠ **A refused request spends no budget** (`AccountRateLimits.allowSend` peeks both limiters,
  then commits both). The naive `tryAcquire(ip) && tryAcquire(email)` charged the shared per-IP
  budget for a request the per-email budget then refused — and the sequence that produces is a
  realistic one: a user hits the 5/hour cap on their own address, keeps pressing *resend* precisely
  **because** it is failing, and burns the per-IP budget to zero, locking every colleague behind
  the same NAT out of signing up, having caused no mail at all. Found by running the service, not
  by a unit test; `aRequestRefusedByTheEmailBudgetDoesNotSpendTheSharedIpBudget` pins it.
  Order is still IP-first: checking the recipient's budget first would let an abusive source burn a
  *victim's* budget instead.
- ⚠ *(`/claim` was removed 2026-10-13; the rule stands for any route that sends only for an
  existing address.)* **`/claim` spends budget for unknown addresses too.** It answers 202 regardless of existence so
  as not to be an enumeration oracle; charging only for real accounts would restore exactly that
  oracle through the limiter (429 vs 202 ⇒ "this address is real").
- ⚠ **The limiter's key table fails closed.** An attacker rotating keys would otherwise grow it
  without bound. On a full table an *unseen* key is refused; failing open would mean unbounded
  outbound mail exactly when an attack is underway. Already-tracked keys keep working, and
  `onTableFull` makes the condition visible instead of silent.
- ⚠ **The OTP is deliberately not in the subject line.** It is the *only* factor in this account
  model — no password — so a code visible in a lock-screen preview defeats the possession check.
  `theCodeIsInTheBodyAndNeverInTheSubject` stops a later UX tidy-up from undoing it.
- ⚠ **`XForwardedHeaders` is now installed** and `clientIp()` reads `request.origin.remoteAddress`.
  Behind Caddy the socket peer is Caddy, so without it the per-IP budget would be one global bucket
  and a single abuser would throttle everybody. Drive-by fix in the same change: the **acceptance
  record's `ip`** had the same bug, and an acceptance stamped with Caddy's address is worthless as
  evidence of contract formation.
- ⚠ `buildMessage` calls `saveChanges()` — `setContent` only attaches a DataHandler, and the MIME
  headers are otherwise written deep inside `Transport.send`, so without it what a test inspects is
  not what a server receives.
- Budgets default to 20/IP/h and 5/email/h for sends, 20/email/h for verifies; `<= 0` disables one,
  matching the relay's `GENESIS_RATE_LIMIT_PER_HOUR` convention. Route defaults are **unlimited**,
  so every pre-existing test and the in-process test module are unchanged.
- ⭐ **CLOSED 2026-08-27 — was ⚖️ "still needs you: a provider account and SPF/DKIM/DMARC on the
  `SMTP_FROM` domain".** Both halves verified: production runs
  `SMTP smtp.tem.scaleway.com:2587 from no-reply@tenshu.app` (Scaleway TEM, live since 2026-08-07),
  and `tenshu.app` resolves **SPF** (`include:_spf.tem.scaleway.com -all`), **DKIM** (2048-bit RSA),
  **DMARC** (`p=none`) and the required `blackhole.tem.scaleway.com` **MX**. Setup steps, the GDPR
  position and the EU-provider alternative remain in
  [`set-up-otp-email.md`](../how-to/set-up-otp-email.md) (§A picks a provider; §A.2 records why
  *not* to route by user location). `scripts/local-commerce-stack.sh` forwards `SMTP_*` so real
  mail can still be exercised locally.
- ⚠⚠ **Authenticated does not mean delivered, and this item closing must not be read as "mail
  works".** On 2026-08-27, with all four records in place, an OTP was accepted by the relay and
  then died at the recipient's mailbox ("Mailbox full") with no signal reaching the app. That class
  is invisible to every synchronous path — see
  [`commerce-otp-delivery-feedback.md`](commerce-otp-delivery-feedback.md).
- ⚠ **DKIM's selector is a UUID** (`<uuid>._domainkey.tenshu.app`), so a probe of plausible
  selector names finds nothing and proves nothing. Read it from the TEM console, or from the `s=`
  field of a `DKIM-Signature:` header on mail that arrived.

### CE-3 — Fake Stripe rail (D1) — **SHIPPED**
`FakeStripeClient` + a commerce-hosted confirm page, selected by `STRIPE_MODE=fake`, which
`validate()` **refuses under `DEV_MODE=false`**. Default-on in `local-commerce-stack.sh`.

**As built, worth knowing:**

- ⚠⚠ **The confirm page collects an email, and that is not decoration.** With real Stripe it is
  *Stripe* that collects it, and the webhook's `customer_email` is the only thing telling us who
  paid — `grantFromEvent` creates-or-finds the account from it. A rail that skipped the field would
  bypass the single most load-bearing value in the money path, and the first live Stripe run would
  be the first time that code ever saw a real one.
- The rail signs `t=<unix>,v1=<hmac over "t.payload">` and POSTs it to its **own**
  `/commerce/webhook/stripe` over real HTTP — not an internal call — so the replay window, the HMAC
  comparison, idempotency-by-event-id, owner rebinding, the ledger and the statutory invoice row all
  execute unmodified. Going live is `STRIPE_MODE=real` + keys.
- ⚠ In fake mode the webhook secret is **generated in-process** (signer and verifier are the same
  process, so there is nothing to configure and nothing to leak) and synthetic price ids make the
  tiers purchasable with no Stripe dashboard.
- ⚠ **Double-confirm is stopped by consuming the session**, not by webhook idempotency: a retry
  would carry a *fresh* event id, so the event-id check would not catch it.
- ⚠ VAT is **backed out of a VAT-inclusive gross** (European display prices include tax), rounded so
  `net + vat == gross` exactly — otherwise the invoice does not balance. Only the fake rail computes
  a price at all; with real Stripe the amount comes off the event, because we must never assert a
  number the processor did not charge.

**Verified live** against a running service: checkout → hosted page → confirm → real webhook `200`
→ admin console shows the account created from the collected email, `rail=Stripe`,
`tier=tenshu.drive.premium`, owner bound. Then registering **the same email** and signing in
returns a session whose `/token` asserts **Premium / 2 TiB** and whose `/mint` returns
`tenshu.drive.premium` — i.e. the paid tier is not displaced by the Free ledger row that `/verify`
appends, and it reaches both carriers. That is demo step 9 proven at the service level; what remains
for the manual pass is seeing it on the Control account card, which is CE-4's job.

### CE-4 — Truthful account card (D5) — **SHIPPED**
`PublisherAccount.storageUsedBytes` / `storageLimitBytes` are **deleted**, not merely unread — a
field nothing populates correctly is an invitation for the next reader to trust it. Plan and usage
now come from the synced entitlement on every path, and the two rival cards collapse into one.
Pure client change: no wire, schema or format movement, so HR#9 does not fire.

**As built, worth knowing:**

- ⚠⚠ **The `account == null` gate is gone.** That gate was the actual defect: it suppressed the
  *correct* card whenever a local session existed, so signing in replaced live figures with
  literals. `ServicesCardState` now carries `storage` (from the synced status, always) and
  `identity` (non-null exactly when this device holds a session). D11's descriptor-vs-session
  boundary survives untouched, because it was always about **actions**, never the number.
- ⚠⚠ **Control-on-iOS had the identical bug and is fixed by the same change.**
  `servicesStatusSnapshot` read `account.storageLimitBytes` on the local-account path, so the
  read-only SwiftUI `ServicesView` inherited the literals. It now takes figures from the status on
  both paths; `sourceKey` still distinguishes the two, which is correct and is about actions.
- ⚠ **A redeemed account now reports its real tier instead of `rei`.** "Rei" was never a plan — it
  is how the account was identified, and the identity row is where it belongs. The `"rei"` planKey
  is retired, and the two now-unreachable Swift `case "rei"` branches are removed (both switches
  keep their `default`). ⚠ Swift not compiled here — no Xcode in this environment.
- **New fourth state, deliberately:** signed in with no entitlement yet (the seconds after a signup
  or purchase) says *"Setting up your storage"* instead of rendering a `0 B of 0 B` gauge, which
  reads as "you have no storage" at exactly the wrong moment.
- *Resend code* is now a named action on the OTP step. The capability already existed via
  *Change email* → resubmit, but nothing surfaced it, so a user staring at an empty inbox had no
  obvious move. It draws on CE-2's per-address budget.
- ⚠ **Not visually verified.** The rendering assertions are Compose UI tests
  (`signingInDoesNotChangeTheFiguresOnScreen` pins the regression), but no screenshot or
  click-through was possible in this environment. The maintainer's pass is CE-6.

### CE-5 — Closed-account gate + operator surface (§1.2) — **SHIPPED**
`gateBySession` now rejects `AccountStatus.Closed`, and the admin console gained
`POST /admin/accounts/close` + `/reopen` behind the same bearer credential, on the same firewalled
host/port, audit-logged.
⚠ Closure deletes content only with `ACCOUNT_CLOSURE_RELAY_URL` **and** `ACCOUNT_CLOSURE_RECLAIM`
set. This phase made the two halves *agree* and switched nothing on; both were opened in production
on 2026-10-24 by maintainer decision, ahead of ⚖️ counsel's review (`retention-quota-model.md` P3).

**As built, worth knowing:**

- The check sits **before** the entitlement lookup, because a closed account legitimately still
  holds a long-window ledger row and that row is exactly what kept it minting.
- ⚠ **The redeem path needs no change**: `/commerce/entitlement/redeem` is identity-light — a code
  plus an `ownerId`, no account at all — so account closure has nothing to bite on there.
- ⚠ **A closed account's session stays valid.** Closure blocks *credentials*, not authentication:
  a future reopen flow has to be able to identify the user, so burning the session would paint us
  into a corner. Only `/mint` and `/token` refuse.
- ⚠⚠ **A Stripe event for a closed account grants nothing — deliberately a refusal, not an
  automatic reopen.** Reopen-on-payment is tempting (it avoids "paid but got nothing"), but closing
  does *not* cancel the customer's Stripe subscription, so recurring `invoice.paid` events keep
  arriving afterwards and would silently resurrect an account closed for erasure, on a schedule
  nobody chose. The webhook still answers `200` so Stripe does not retry forever.
- ⚖️ **Newly surfaced by CE-5 — and since built** (see below): closing an account must also cancel
  its Stripe subscription, or a closed customer keeps being charged for a service the webhook now
  correctly refuses to grant.
- ⚠ The admin mutations are **`curl` endpoints, not buttons**, and are keyed by **email**. Auth is a
  bearer header, which a browser form cannot send, so clickable controls would 401 every time; the
  users page prints the invocations instead. The account id is not on `AdminAccountRow` and an
  operator identifies an account by its address anyway.
- ⚠ An unknown address gets an honest **404** rather than a uniform success: there is no enumeration
  concern behind the admin credential, and hiding an operator's typo helps nobody.
- **Deferred from the original sketch:** entitlement history and an active-session count on the
  console. Both need new methods on *two* store backends for observability value only; the
  capability gap CE-5 existed to close was that nothing could close or reopen an account at all.

⚠ Every refusal test also asserts the account's **state**, not just the status code — a route that
answered `401` and closed the account anyway would pass a status-only test.

### CE-6 — The runbook and the pass — **SHIPPED** (the runbook; the pass is yours)
[`docs/how-to/commerce-dry-run.md`](../how-to/commerce-dry-run.md) — a **local** runbook distinct
from the droplet-oriented `reset-and-commerce-test.md`. Every step was walked server-side against a
running stack before being written down, so the expected outputs are observed rather than imagined.

**As built, worth knowing:**

- The runbook leads with the trap that would silently invalidate the whole exercise: launching
  `:control-desktop:run` directly leaves `TENSHU_BILLING_MOCK` on, so the pass would "succeed"
  against a fixture that never contacts the service.
- It distinguishes `401` from `402` on `/sync/push`, and warns that `/sync/changes` stays `200`
  under enforcement so it is not evidence of anything.
- ⚠ **Expect a browser error on `tenshu://checkout-result`** after paying — the Layer-2 deep-link
  is still unbuilt. Harmless: fulfilment is confirmed by the synced entitlement, never by the
  browser return. Called out so it does not read as a failure mid-demo.
- New `ServicesCardRenderTest` writes PNGs of all four card states to
  `control-shared/build/reports/services-card/`, so the CE-4 change can be **looked at**. No golden
  comparison — a pixel gate is a maintenance cost this project has not chosen.

**Two defects found by rendering the card and looking at it**, neither of which any string
assertion would have caught:

- ⚠⚠ **Premium displayed as "2048 GB"** — `formatBytes` stopped at GB, so the flagship tier read
  wrong on the exact screen we ask people to buy from, while the tier picker two taps earlier said
  "2 TB". Fixed with a TB step; GB and TB now carry one decimal (trailing ".0" dropped) because
  integer truncation understates a *usage* figure by up to a whole unit.
- The usage gauge sat 4.dp under its label and read as an underline of the text rather than as a
  gauge; now 8.dp.

⚠ This is the fifth byte formatter in the repo (`:drive-shared-logic`, `:home-shared-logic`,
`:drive-shared-ui`, the offline-maps screen, this one). Unifying them is worth doing and was
deliberately not done here.

**Still the maintainer's:** the pass itself — a real inbox, a browser, a second device, and eyes on
the running app.

### Cancel-subscription-on-close — **SHIPPED** (the follow-up CE-5 surfaced)

CE-5 made a closed account un-grantable, which on its own turns "closed" into **"paying for
nothing"**: the Stripe subscription keeps renewing against a service we now decline to provide.
`SubscriptionCanceller` closes that.

- ⚠⚠ **Close first, cancel second — the failure modes are asymmetric.** Cancelling before closing
  means a crash in between leaves an *active* account with no subscription: the customer silently
  stops paying, keeps the service, and nothing ever notices or retries. Closing first means a crash
  leaves a closed account still billing — wrong in the *visible* direction, and self-healing,
  because the sweep looks for exactly that.
- ⚠ **Cancelled by customer, not by a stored subscription id.** We never persisted one, so this
  needs **no schema change**; it also cannot miss the second subscription of a customer who
  re-subscribed.
- ⚠ **`status=all`, not `status=active`.** Filtering server-side on `active` would miss `trialing`,
  `past_due`, `unpaid` and `paused` — every one of which still bills or converts to billing, so a
  trial left running on a closed account would charge them later.
- **No new state.** Cancellation is idempotent at the rail (an already-cancelled subscription is
  not in the billing set), so the retry sweep needs no pending flag, no queue and no migration — a
  re-run is free and reports 0. That is what makes it safe to run forever.
- ⚠ A partial failure reports **failure, not the partial count**: answering "3 cancelled" while a
  fourth is still live would read as success and stop the retry that is meant to finish the job.
- ⚠ **A Stripe outage cannot veto a closure.** The close is durable; only the cancellation is
  retried. `theCloseStandsEvenWhenCancellationFails` pins it.
- ⚠ **Reopening does not un-cancel.** Reopen restores the *account*, not the billing relationship —
  the customer re-subscribes. We cancelled with them, not on their behalf.
- ⚠ The new interface method deliberately has **no default implementation**: a default no-op would
  silently reintroduce exactly this bug in the next `StripeClient`. Two existing test fakes had to
  be filled in, which is the seam doing its job.
- Sweep bounded by `SUBSCRIPTION_CANCEL_WINDOW_MS` (30 d) at `SUBSCRIPTION_CANCEL_SWEEP_MINUTES`
  (hourly), started only when the Stripe rail is on.
- **The refund question is now settled and published** —
  [`acceptable-use-and-limits.md`](../policy/acceptable-use-and-limits.md) §5 (v0.3): cancelling
  stops future charges and does **not** refund the current period, the ordinary online-subscription
  model; closing an account cancels **immediately** and forfeits the remainder. That is *why* this
  cancels outright rather than at period end — a closed account cannot use the tier it would be
  keeping. `TOS_VERSION` → `2026-08-06`; `PRIVACY_VERSION` deliberately unchanged, since nothing
  about data handling moved.
  ⚠ The forfeit is a real user cost: the closure surface must say so **before** the action.

### Checkout withdrawal acknowledgement — **SHIPPED** (the follow-up the refund policy surfaced)

Policy §5.2 says a cancellation does not refund the current period. That claim only holds for a
fresh purchase if the customer **expressly consented** to the service starting immediately and
acknowledged that this forfeits the EU 14-day right of withdrawal — which nothing collected. Until
now the honest reading was §5.4's carve-out: refundable inside 14 days, handled by hand. This closes
it, so §5.2 governs.

**As built, worth knowing:**

- ⚠⚠ **The checkbox is in the client, but the rule is in the service.** `/commerce/checkout` is an
  ordinary HTTP route anyone can POST directly, so a UI-only checkbox would be decoration and the
  terms would claim a waiver we could not evidence. The route **refuses** a request without
  `withdrawalAcknowledged=true` *and* a non-blank `tosVersion` (`400 acknowledgement_required`);
  absent and explicitly-`false` behave identically, because a defaulted field is exactly what a
  waiver may never be.
- ⚠⚠ **The acknowledgement rides as Stripe `metadata`, not as a column** — at checkout time there
  is **no account to attach it to**. The account is created from the email *the rail collects*
  (CE-3's load-bearing field), so the only carrier that survives the gap between "purchase started"
  and "purchaser known" is metadata echoed back on the event. The webhook then writes an
  `Acceptance` row of the new kind `PurchaseWithdrawalWaiver` against the account it just resolved.
- ⚠ **A renewal carries no acknowledgement and must not manufacture one.** Absence on
  `invoice.paid` is correct, not a gap — a renewal is not a new contract. Pinned by a test, since
  "write an acceptance whenever money arrives" is the obvious wrong simplification.
- ⚠ `AcceptanceKind` distinguishes the registration acceptance from the purchase waiver; Postgres
  migration **v4** adds a nullable `kind` column (existing rows read as the registration kind).
  A version, not a shape change to the client — the acceptance record already carried a ToS version.
- ⚠ The dev rail's hosted page states the immediate start too. Wherever a purchase is *confirmed*,
  the consequence has to be legible at that moment — with real Stripe that is Stripe's page, which
  is why the fake rail's page is the thing under test.
- Client: `CheckoutStep.Acknowledge` is now the **initial** step and `start()` no longer runs from
  `init` — the VM cannot open a checkout before the tick. `CheckoutScreenContent` was split out on
  the `ServicesScreenContent` precedent so the step could be **rendered and looked at**
  (`CheckoutScreenRenderTest` → `build/reports/checkout/`), the CE-6 lesson applied prospectively.
- ⚠ The plan and price are deliberately **not** repeated on this step: the tier picker immediately
  precedes it and the payment page states them again. Restating a price we compute client-side next
  to a legal acknowledgement is a way to be wrong twice.
- Policy §5.4 rewritten (v0.4): the right **is** disapplied for acknowledged purchases; purchases
  before `2026-08-06` carry no acknowledgement and stay refundable inside 14 days. ⚖️ What remains
  for counsel is the **wording** of the sentence customers tick, per member state — not whether the
  mechanism exists.
- `WithdrawalAcknowledgementTest` (7): refusal (absent / false / no version), the happy path, the
  durable waiver record, no-waiver-on-renewal, and the hosted page's statement.

### CE-7 — `CommerceRelaySystemTest` (D6) — **SHIPPED**
The real `:commerce-service` Ktor module runs in-process beside the real relay with enforcement on,
and the test drives the production sequence: register → capture OTP → verify → session →
`/commerce/entitlement/token` → `X-Entitlement` → `POST /sync/push` accepted. Four tests, ~7 s.

**As built, worth knowing:**

- **The harness gained the seam it explicitly lacked.** `SystemTestPlatformModule` carried a comment
  that a bearer token "needs a commerce session, which the harness has no analogue of", and wired
  only `entitlementProofProvider`. CE-7 supplies the analogue, so `entitlementTokenProvider` is now
  threaded through `TestSystem.client(...)`, defaulting to `{ null }` — every pre-existing test is
  byte-for-byte unaffected, and `{ null }` is also the honest posture of a session-less device.
- ⚠⚠ **The relay is configured with the key commerce actually signs with**, read off the live
  `IssuerKeypair` rather than the pinned `COMMERCE_PUBLIC_KEY` constant. That makes the test cover
  the operator hand-off (commerce prints its public half → `ENTITLEMENT_ISSUER_PUBKEY`), so a
  key-derivation or encoding mismatch fails in CI instead of at a deployment.
- **Mutation-checked.** Pointing the relay at a different public key fails
  `aCommerceMintedTokenIsAcceptedByTheRelay` and *only* that test — confirming the assertion is
  carried by the credential rather than passing incidentally.
- The negative control (`withoutACommerceSessionNothingIsAccepted`) is what makes the positive one
  mean anything, and it also proves the token is the *only* credential in play here: no `Entitlement`
  row is seeded, so the proof header is null and cannot quietly carry the write.
- ⚠ The token is fetched **lazily by the provider** from an atomic session holder, mirroring
  `CachingEntitlementTokenProvider` asking on the request path — not pre-computed, which would skip
  the ordering that actually exists (onboard first, sign up second).

---

## 5. What "shown once" means, concretely

| # | Step | What it proves | Observable |
|---|---|---|---|
| 1 | `docker compose up` (CE-1) | the stack stands up locally | relay + commerce healthy, enforcement banner in the relay log |
| 2 | Control, mock off, fresh onboarding | genesis against the local relay | realm created |
| 3 | Services → Sign up, real address | **real OTP email** (CE-2) | a mail lands in an actual inbox |
| 4 | Enter the code | session opens, owner binds | account card appears |
| 5 | — | mint + record sync | commerce log shows `/mint`; the record is in the realm |
| 6 | Upload a file in Drive | **commerce ⇒ relay** | relay accepts `POST /sync/push` (200, not 402) with `X-Entitlement` |
| 7 | Return to Services | live usage (CE-4) | the gauge shows the **real** bytes, plan **Free · 5 GiB** |
| 8 | Get more storage → Premium | fake Stripe → real webhook (CE-3) | confirm page → ledger + invoice rows |
| 9 | Return to Services | tier change propagates | plan **Premium · 2 TiB**, on this device |
| 10 | Pair a second device | record carrier, no session | second device syncs and shows the same plan |
| 11 | Admin console | operator visibility | the account, its tier, its live owner |

Steps 6, 7 and 9 are the three that have never been observed together.

---

## 6. Out of scope

Real Stripe keys and the live-payload validation of the webhook field mapping (the next milestone,
which this exists to de-risk); IAP / StoreKit (Ph4b); US sales tax (Ph5); the
`tenshu://checkout-result` deep-link; admin MFA / OTP lockout / rebinding step-up (Ph4c); blinded
tokens; switching closure on in any deployment.

---

## 7. Threat-model deltas

- **CE-2** — a real transport for A28 PII (an email address leaves the plane to a mail provider):
  a processor note on A28, plus the sender-refusal guard recorded beside the existing dev-key
  guard. House Rule #2's mandatory trigger (A1–A4/A9/relay-shape/sync-protocol) does not fire.
- **CE-3** — none. The fake rail is dev-only, refuses to run in production, and mints nothing the
  real rail could not.
- **CE-5** — the admin surface gains **mutations** on the highest-risk console (A28/A30). Amend the
  surface **#15** entry (⚠ this line said #11, which is the contact-invitation handshake — corrected
  2026-08-09): read-only → read + lifecycle mutation, same firewall + credential, audit-logged.
  **Landed with WC-5** (`commerce-accounts-and-sessions.md` §9.4d), together with the export
  capability WC-4 added and the MFA enforcement that gates all three.
- **CE-1/4/6/7** — none.

---

## 8. Implementation notes for Claude Code

- **The one-line reason the plane feels absent:** `TENSHU_BILLING_MOCK` defaults `true` in
  `desktopPublisherClient` / `androidPublisherClient` / `iosPublisherClient`. Reading the mock is
  reading a fixture, not the system.
- `EntitlementMinterSelection.useDevSeed(billingMock, isRelease)` is the single gate; a **release**
  build ignores `TENSHU_BILLING_MOCK` entirely and always takes the real client — worth remembering
  when a packaged desktop app behaves differently from `:control-desktop:run`.
- The client reaches commerce over **three** independent seams that all read `TENSHU_COMMERCE_URL`:
  `PublisherClient` (account/session), `EntitlementMintClient` (synced record), and
  `EntitlementTokenClient` (relay bearer). Flipping only one of them yields a half-real state that
  is confusing to debug.
- `system-test-utils` is a **JVM** module on toolchain 21 that already hosts the relay in-process
  via `testApplication`; `:commerce-service` is a plain JVM Ktor module, so CE-7 is an added
  dependency plus a second `testApplication`, not new infrastructure.
- No phase here reshapes a stored row or the wire, so **House Rule #9 does not fire** and
  `SchemaShapeBaseline` should stay untouched. If CE-4 tempts you to add a field to a synced type,
  stop — D5 exists precisely so it does not need one.
- Correct the stale `AGENTS.md` line about `StorageStatus.usedBytes` being mock-zero when CE-4
  lands (§0.2).
