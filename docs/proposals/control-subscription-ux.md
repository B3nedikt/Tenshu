# Tenshu Control — Subscription & Payment UX

**Status:** Draft v0.1
**Scope:** The user-facing detail of every subscription-tier-picker, checkout, payment, and
entitlement-arrival flow inside the **Control** app on Android and Desktop. Two entry points
are in scope: onboarding (the new-realm path described in
[`control-onboarding-ux.md`](control-onboarding-ux.md) §4b, between local-unlock setup and
the recovery-manifest picker) and Settings → Storage (the upgrade-later path).

This doc is intentionally narrow:

- **Protocol-level architecture** for the commerce plane / entitlement records lives in
  [`commerce-billing-plane.md`](commerce-billing-plane.md) (canonical) and the account-lifecycle
  layer in [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) — these
  supersede the earlier `tenshu-publisher.md` framing. This doc consumes that boundary; it does
  not redefine it.
- **First-run pairing, recovery phrase, recovery manifest** screens are in
  [`control-onboarding-ux.md`](control-onboarding-ux.md). This doc only covers what slots
  *between* local-unlock setup and the recovery-manifest picker on the new-realm path, plus
  the Settings entry that returning users hit.
- **Per-region pricing, tax handling, App Store / Play Store policy compliance** are out of
  scope here (tax + self-MoR are owned by `commerce-accounts-and-sessions.md` §5). v1 ships
  two paid tiers (Basic, Premium), one currency, one provider per platform.

For wireframes: this doc uses text-based descriptions (layout, hierarchy, key elements). When
the project gains a design tool with shareable links, those should replace the text
descriptions here.

---

## 1. Conventions

- **Headings (H1, H2)** indicate flow milestones; the user sees them as page titles or large
  headings on the screen.
- **Body** copy is written for an end user — not a developer. Avoid jargon. Where Tenshu has
  a specific concept (realm device, entitlement, publisher), the first mention in any flow
  should briefly anchor it.
- **CTAs** (call-to-action buttons) are in `[Brackets]`. The primary action is bold-equivalent;
  secondary actions are plain.
- **Inline help** is denoted by `( ? )` and expands to a tooltip on press / hover.
- **Tone**: clear, calm, technically honest. The subscription flow is one of the few places
  in Tenshu where money changes hands; copy must not feel like a dark pattern. State the price,
  state what's included, give a friction-free way to skip.
- **Platform**: Control runs on Android and Desktop. The product picker and entitlement
  screens are platform-identical; the *checkout* leg branches per platform (Play Billing on
  Android, Stripe Checkout in the system browser on Desktop, future StoreKit on iOS).

---

## 2. Where This Flow Fits

Three entry points reach the screens below.

### 2.1 Onboarding (new-realm path)

After [`control-onboarding-ux.md`](control-onboarding-ux.md) §4b.5 (set up local unlock) and
before §4b.6 (recovery manifest picker). The user has a master identity, a confirmed recovery
phrase, and a working biometric unlock; they have *not* yet picked which volumes to back up.
The subscription tier influences the available backup quota, so the picker comes after this
flow.

The flow is **optional**. The user can pick the free tier and continue; it's not gated.

### 2.2 Settings → Storage (returning user)

Reached from Control's main navigation: *Settings → Storage*. Used when a returning user
wants to upgrade after hitting a quota soft-limit, or when they want to inspect or change an
existing subscription. The flow shares §3 and §5 below; the surrounding navigation differs.

### 2.3 Quota soft-limit prompt

When a user's storage usage crosses 80% of the free-tier quota during normal app use,
Control surfaces a one-time non-modal banner: *"You're using 80% of your free Tenshu
storage. [See plans]"*. Tapping `[See plans]` deep-links to §3 below with a `from=quota`
context flag (used to colour the post-flow CTA: returns to the screen the user was on
rather than to Settings).

This is the only "marketing" prompt in the app. No re-prompts; the banner's dismissal is
permanent.

> **Implemented** (`retention-quota-model.md` P1b-1): `QuotaSoftLimitViewModel` +
> `QuotaSoftLimitBanner` on the Control home screen, over the real synced `StorageStatus`
> (free tier + usage ≥ 80%). Both *dismiss* and *See plans* set the device-scoped
> `ControlPreferences.QuotaSoftLimitBannerDismissed` flag → permanent, no re-prompts. `[See
> plans]` routes to `Destination.Services` (the storage/subscription surface).

---

## 3. Product Picker

The picker is the first screen of the actual subscription flow, regardless of entry point.

### Screen 3.1 — Pick a Plan

**Title** (entry-dependent):

- Onboarding: *"Pick your storage plan"*
- Settings: *"Your Tenshu plan"*
- Quota prompt: *"Pick a plan"*

**Body** (one paragraph; entry-dependent):

- Onboarding:
  > Your Tenshu data is end-to-end encrypted on Tenshu's relay. The free tier is enough for
  > most people; you can change plans any time from Settings.
- Settings (no current paid subscription):
  > You're on the free tier. Upgrade for more encrypted storage on Tenshu's relay.
- Settings (current paid subscription): see §5.

**Layout**:

- A vertical stack of three plan cards — **Free, Basic, Premium**
  (`commerce-accounts-and-sessions.md` §2). Each card shows:
    - Plan name (*Free*, *Basic*, *Premium*).
    - Storage quota (*5 GB*, *50 GB*, *2 TB*).
    - Price (*Free*, *€2.99 / month*, *€9.99 / month*) with billing-period toggle if
      monthly/annual exist (v1: monthly only).
    - One-line scope description (e.g. *"All your encrypted data on Tenshu's relay."*).
    - Per-card primary CTA: `[Continue]` (free) or `[Subscribe]` (paid).
- The current plan (if any) shows a *"Current plan"* badge in the top-right of its card.
- Below the cards: a small footer link *"What's the difference between tiers?"* opens an
  inline expander with the full feature comparison.
- Below that, a low-emphasis text link **"Have a code?"** — the advanced redemption entry
  (`commerce-accounts-and-sessions.md` §2.3). No marketing copy; it opens a single
  redemption-code input and, on success, mints the matching entitlement and skips checkout.
  (Phase 1 wires this to a stub; the Stripe checkout itself lands in that doc's Phase 4.)

**No price displayed without scope.** Every price has an immediate adjacent description of
what's included. Avoid the "$X" with the description three lines down.

**Currency**: v1 displays prices in EUR for Desktop (Stripe) and uses the platform-localised
currency on Android (Play Billing returns the user's Play country price).

**CTAs**:

- `[Continue]` (free) — onboarding: advances to recovery-manifest picker; settings: returns
  to Storage view; quota prompt: returns to the prior screen.
- `[Subscribe]` (paid) — advances to §4 platform-specific checkout.
- `Skip — I'll decide later` (less prominent, onboarding only) — same as `[Continue]` on
  the free card. Belt-and-braces escape hatch for users who don't want to engage with the
  picker at all.

### Screen 3.2 — Pricing Detail Expander

Inline (not a separate screen). Opens below the plan cards when the user taps *"What's the
difference between tiers?"*. Shows a comparison table:

| Feature                    | Free                  | Basic                  | Premium                |
|----------------------------|-----------------------|------------------------|------------------------|
| Encrypted storage on relay | 5 GB                  | 50 GB                  | 2 TB                   |
| Number of realm devices    | Unlimited             | Unlimited              | Unlimited              |
| Recovery phrase backup     | Yes                   | Yes                    | Yes                    |
| Recovery manifest volumes  | Limited to 5 GB total | Limited to 50 GB total | Limited to 2 TB total  |
| Sync between your devices  | Yes                   | Yes                    | Yes                    |
| Apps you install           | Yes                   | Yes                    | Yes                    |

**Design note**: the table is short by design. Tenshu's value proposition isn't a feature
matrix — it's privacy and ownership, both of which are tier-independent. The only thing that
varies is quota.

Below the table, a one-line caveat:

> Your Tenshu data stays end-to-end encrypted on every plan. The free tier is not a "lite"
> version — it's the full Tenshu, with less storage.

---

## 4. Checkout (per-platform)

After the user taps `[Subscribe]` on a paid plan in §3.1, the flow branches by platform.

### 4a. Android — Play Billing

#### Screen 4a.1 — Confirm with Play

**Title**: "One moment…"

**Body**:

> We're connecting to Google Play to complete your subscription.

A subtle progress indicator runs while Control invokes the Play Billing Library to
`launchBillingFlow(...)` for the configured product SKU. No user action required; the screen
exists only to bridge the brief delay before the Play system bottom-sheet appears.

#### Screen 4a.2 — Play System Bottom-Sheet

This is a Google-rendered bottom-sheet, not a Tenshu screen. The user sees Google's standard
purchase confirmation (price, payment method, *"1-tap buy"*). On confirm: the Play Billing
Library returns a `Purchase` object → §4a.3. On cancel or failure → §6.

The sheet is provided by the Play services overlay; Control has no way to style or alter it.
The product appearing here must be configured in the Play Console with the same SKU the app
launches; v1 hardcodes one SKU (`tenshu.drive.basic.monthly`).

#### Screen 4a.3 — Purchase Acknowledged

**Title**: "Purchase complete"

**Body**:

> Your subscription is being activated. This usually takes a few seconds.

A spinner runs while Control:

1. Calls `acknowledgePurchase(...)` on the Play Billing Library (required by Play within
   3 days, or the purchase auto-refunds).
2. Forwards the `purchaseToken` to the Publisher via `POST /checkout/play-confirm` (per
   `tenshu-publisher.md` §3.1).
3. Polls (or long-polls, when `sync-relay-api.md` Phase 5 lands) for the entitlement to
   arrive in the realm via the existing sync mechanism.

Auto-advance to §7 on entitlement arrival, or §6.4 on failure.

### 4b. Desktop — Stripe Checkout

#### Screen 4b.1 — Opening Browser

**Title**: "Opening your browser to complete payment"

**Body**:

> Stripe handles your payment securely. Tenshu doesn't see your payment details — we only
> learn that the payment succeeded.

A spinner runs while Control:

1. POSTs `/checkout` to the Publisher with `(ownerId, productId)` — per
   [`tenshu-publisher.md`](tenshu-publisher.md) §3.1.
2. Receives a `sessionUrl` back.
3. Opens that URL in the user's default browser.

#### Screen 4b.2 — Stripe Checkout (in browser)

This happens in the user's browser, not in Control. Stripe's hosted checkout page collects
card details (or applies a saved payment method via the user's Stripe Link account) and
processes the payment. The page is Stripe-hosted; Tenshu cannot alter it.

The Stripe Checkout session is configured with:

- `success_url` and `cancel_url` deep-linking back into the Control app (
  `tenshu://checkout-result?...`).
- `customer_email` left blank — Stripe collects email at checkout. The Publisher records
  `(ownerId, email)` server-side; the device never sees the email.

#### Screen 4b.3 — Awaiting Confirmation

**Title**: "Waiting for payment confirmation…"

(Shown in Control after the browser deep-links back, regardless of success/cancel — the
deep-link result is the trigger; the actual confirmation comes via the entitlement sync.)

**Body**:

> Stripe is confirming your payment with us. This usually takes a few seconds.

A spinner runs while Control polls (or long-polls, when available) for the entitlement to
arrive. Auto-advance to §7 on arrival, or §6.4 on failure.

**Edge**: if the user closes the deep-link window before it can hand control back to the
desktop app (e.g. they completed payment then closed the browser tab without clicking
*"Return to Tenshu"*), Control still receives the entitlement via the next sync pull. The
Storage view in Settings shows the new tier; no error is surfaced.

---

## 5. Settings Flow (Returning User)

### Screen 5.1 — Storage

(Reached from Control's main navigation: *Settings → Storage*.)

**Title**: "Storage"

**Layout**:

- Top: a usage indicator — *"Using 1.2 GB of 5 GB"* with a progress bar. Colour-coded:
  green < 60%, amber 60–90%, red > 90%.
- Below: the current plan card (read-only):
    - Plan name + price.
    - Renewal date (paid plans) or *"Free forever"* (free plan).
    - Source-of-truth note: *"Managed by Google Play"* or *"Managed by Stripe"* (paid only).
- Below that: `[Change plan]` (always visible) → §3.1.
- Footer: a link *"Recovery & backups"* → recovery-manifest editing per
  [`control-onboarding-ux.md`](control-onboarding-ux.md) §8.

### Screen 5.2 — Cancel / Manage Subscription

Tapping `[Change plan]` on a paid subscription routes to §3.1 with the current plan card
showing *"Current plan"* and a downgrade option (`[Switch to Free]`).

**Cancellation is delegated to the platform.** Control does not implement its own cancellation
flow:

- Android: *"Manage subscription"* deep-links to the Play Store subscription management
  screen.
- Desktop: *"Manage subscription"* opens the Stripe customer portal in the user's browser,
  using a portal session URL minted by the Publisher.

This avoids the dark-pattern temptation of in-app cancellation friction, and it's also what
Apple's IAP review rules require on iOS (relevant for the future iOS port).

**On downgrade to free** (current paid plan → free): a confirmation modal:

> Your storage limit will drop to 5 GB at the end of your current billing period
> ({renewal_date}). If you're using more than 5 GB at that point, your devices will keep
> their data, but no new uploads will sync until you free space.

CTAs: `[Cancel subscription at period end]` (primary) and `[Keep my plan]` (secondary).

The actual cancellation request is routed through the platform's subscription manager, not
through the Publisher directly.

---

## 6. Failure Modes

The flow has three categories of failure: payment, entitlement-sync, and provider-internal.

### 6.1 Payment Cancelled (User-Initiated)

User dismissed the Play system sheet (Android) or clicked *"Back"* on Stripe Checkout
(Desktop).

**Title**: "Payment cancelled"

**Body**:

> No charge was made. You can try again or stay on the free tier.

**CTAs**:

- `[Try again]` — back to §4.
- `[Use the free tier]` — primary. Onboarding: advances to recovery-manifest picker.
  Settings: returns to Storage view.

### 6.2 Payment Declined

Stripe / Play returned a "declined" outcome (insufficient funds, card declined, etc.).

**Title**: "Payment couldn't go through"

**Body**:

> Your bank or payment provider declined the charge. {provider_message}

`{provider_message}` is the user-facing message returned by Stripe / Play (these are usually
short and non-technical: *"Your card was declined."*, *"Insufficient funds."*).

**CTAs**:

- `[Try a different payment method]` — restarts §4 with the picker pre-set to the previously
  attempted plan.
- `[Use the free tier]` — same as §6.1.

### 6.3 Network Error During Checkout

The Publisher endpoint is unreachable when Control tries to mint a checkout session.

**Title**: "We can't reach our servers"

**Body**:

> Check your network and try again. If the problem persists, you can use the free tier and
> upgrade later.

**CTAs**:

- `[Try again]` — retries the `/checkout` POST.
- `[Use the free tier]` — same as §6.1.

### 6.4 Entitlement Doesn't Arrive

Payment confirmed (Stripe webhook fired or Play Billing returned `OK`) but the entitlement
record didn't reach the device's realm within 60 seconds.

**Title**: "Your payment went through, but we're still setting up your plan"

**Body**:

> Your payment was successful, but it's taking longer than usual to activate your plan. Your
> subscription is safe — it'll activate on its own in the next few minutes. You can keep
> using Tenshu in the meantime.

**Layout**:

- A persistent banner at the top of Control's Storage view until the entitlement arrives.
- Tapping the banner shows: *"Last checked {timestamp}. Tenshu is checking every minute."*

**CTAs**:

- `[Got it]` — dismisses the screen; entitlement arrival is announced via a one-shot
  notification once it lands.

This is the most important failure mode to get right because the user has paid. The copy
explicitly preserves trust: *"Your subscription is safe."* If the entitlement never arrives
(the rare full-pipeline failure), the user contacts support; the Publisher's
`/entitlements/:ownerId` endpoint is the source of truth and can be queried by support
agents.

### 6.5 Entitlement Mismatch

The entitlement that arrived is for a different product than the user paid for. This should
be impossible in v1 (one product, one SKU); included for forward-compatibility.

Control surfaces a non-modal error in Settings → Storage, and the support link is
prominently shown. No automated remediation in v1.

---

## 7. Entitlement Arrival

### Screen 7.1 — Subscription Active

**Title**: "You're on the {plan_name} plan"

**Body**:

> Your storage is now {quota}. Renews on {renewal_date}.

**Layout**:

- A small confirmation card (no full-screen takeover; this is a victory moment but not a
  major event).
- One CTA.

**CTAs** (entry-dependent):

- Onboarding: `[Continue]` → recovery-manifest picker (`control-onboarding-ux.md` §4b.6).
- Settings: `[Done]` → Settings → Storage view.
- Quota prompt: `[Back to {prior_screen}]` → the screen the user was on when the soft-limit
  banner appeared.

---

## 8. Mock Mode (Dev Builds)

Per [`tenshu-publisher.md`](tenshu-publisher.md) §10 (Phase 1 appendix), v1 ships with the
real Publisher service stubbed out. The screens above are wired to a mock surface that lets
the UX flow be tested end-to-end without a deployed Publisher, real Stripe account, or real
Play product.

Behaviour with mock mode enabled (compile-time flag `TENSHU_BILLING_MOCK=1`, on by default
in dev builds, off in release):

- §3.1 (product picker) shows the real product cards but pulls the catalogue from a hardcoded
  `MockPublisherClient` instead of the Publisher's `/products` endpoint.
- §4a.1 / §4b.1 (checkout) skips the real Play / Stripe handoff. Instead, Control shows a
  dev-only screen: *"Mock checkout — pretend the user paid?"* with `[Yes, pay]` and
  `[No, cancel]` buttons.
- On `[Yes, pay]`: the `MockPublisherClient` synthesises a `PaymentConfirmed` event,
  immediately mints a self-signed entitlement using the bundled dev signing key, and writes
  it directly into the local realm via the same sync-block mechanism the real flow uses.
- §6 failure modes can be triggered manually: a long-press on the mock pay button opens a
  dev menu with `[Decline]`, `[Network error]`, `[Don't deliver entitlement]`.

This mock mode is what lets the UX flow ship and be exercised before the Publisher service is
deployable. Production builds compile out the mock entirely.

---

## 9. Edge Cases

### 9.1 User Closes Control Mid-Checkout

If the user backgrounds or kills Control after `launchBillingFlow(...)` (Android) or after
opening the Stripe browser tab (Desktop) but before the entitlement arrives, the flow is
recoverable:

- **Android**: Play Billing's `queryPurchasesAsync(...)` on next app start surfaces any
  unacknowledged purchase. Control acknowledges, forwards to Publisher, waits for
  entitlement.
- **Desktop**: the deep-link back from Stripe re-launches Control with a `checkout-result`
  intent; if Control is already running, the running instance handles it. If Control was
  killed, the next launch picks up the entitlement on the next sync pull. No deep-link
  handler runs in that case but the user reaches the same end state via Settings → Storage
  showing the new plan.

The user's payment is never lost.

### 9.2 Entitlement Arrives Before Acknowledgement (Android)

Race: the Publisher receives the Play webhook, mints the entitlement, and writes it to the
realm sync stream before Control finishes the `acknowledgePurchase(...)` call. Control sees
the new entitlement during its post-purchase poll loop and skips the *"Awaiting
confirmation"* screen, going straight to §7. Acknowledgement still happens in the background.

### 9.3 Multiple Devices Active During Checkout

The user has multiple realm devices. They subscribe from one device. The entitlement arrives
in the realm sync stream and propagates to *all* devices. The other devices show the new
plan in Settings → Storage on next view; no notification is fired (would be noisy).

### 9.4 User Re-Subscribes After a Lapse

A user whose subscription expired (didn't renew, or downgraded to free) can re-subscribe.
The flow is identical to first-time §3.1 → §4 → §7. The Publisher's record of the previous
subscription is internal only; the user-facing experience is fresh.

### 9.5 User Has No Internet When Quota Soft-Limit Fires

The 80% banner in §2.3 fires based on local usage data, so the banner appears regardless of
network. If the user taps `[See plans]` while offline, §3.1 loads the cached product
catalogue (or, in mock mode, the hardcoded one). Tapping `[Subscribe]` fails with §6.3.

### 9.6 Refunds (Out of Scope for v1)

Stripe and Play both have refund flows. v1 does not implement the Publisher-side
`Entitlement` revocation needed for refunds (per `tenshu-publisher.md` §5 open question).
A user who wants a refund contacts support; the Publisher can issue the platform-side refund
manually, but the entitlement record stays valid until the next billing cycle.

### 9.7 Family Sharing / Gifting

Out of scope. The Play Store and App Store both expose family-sharing surfaces; supporting
them requires the Publisher to handle the platform's "shared subscription" payload shape.
v1 ignores this; a user attempting to subscribe from a Family-sharing-enrolled account just
gets a normal individual subscription billed to the lead family member.

### 9.8 User Pays But Realm Is Wiped Before Entitlement Arrival

A user subscribes from device A, then before the entitlement arrives they wipe device A
(reset, factory wipe, lost device) and recover from recovery phrase on device B. The
Publisher's record is keyed by `ownerId` (which is derived from the master key and survives
recovery), so on recovery, the entitlement is still valid for the new device and arrives
in the recovered realm's sync stream. No re-purchase needed.

Edge of this edge: if the user wipes *and* the Publisher hadn't yet minted the entitlement
(payment in flight, webhook not yet fired), the entitlement is minted against the same
`ownerId` later and lands in the recovered realm. The recovery flow itself doesn't need to
be aware of subscriptions.

---

## 10. Cross-References Between Flows

- **§3.1 (product picker) is shared between onboarding and settings entry points.**
  Implementation should share one composable; the entry condition flag controls the title and
  the post-flow navigation only.
- **§4a (Android checkout) and §4b (Desktop checkout) are platform-specific composables**;
  they share the §4.x.3 *Acknowledged / Awaiting* screen below the platform branch.
- **§7 (entitlement arrival) feeds back into the entry-point's caller.** The screen itself
  doesn't know whether it was reached from onboarding, settings, or a quota prompt; the
  navigation context is passed in.

---

## 11. Open Items

- **Annual billing.** v1 ships monthly only. Annual is straightforward to add (one extra
  product per tier) but raises a UX question: do we surface monthly-vs-annual on the picker,
  or as a one-tap toggle inside the paid card? Defer until a second tier exists.
- **Multiple paid tiers.** v1 ships one paid tier (`Basic`). When a second tier ships
  (e.g. `Pro` with a higher quota), the picker layout needs to accommodate three or more
  cards; horizontal scroll on mobile, full-width grid on desktop. Sized when the second
  tier is concrete.
- **Localised pricing.** Stripe Checkout handles VAT and currency conversion based on the
  customer's billing country at checkout. Play Billing returns the user's local price
  directly. The picker in §3.1 needs to display these correctly; v1 displays EUR on Desktop
  and the platform-localised currency on Android. Decide the desktop strategy (geo-detect?
  locale-detect? user-pick?) before non-EUR markets.
- **App Store / iOS port.** When Control gets an iOS port, IAP via StoreKit replaces Stripe
  on iOS specifically (Apple policy requires it for digital goods). The Publisher already
  has the boundary for this; the UX additions are minor (iOS version of §4 with StoreKit's
  `SKPaymentQueue` flow). Sized when iOS is concrete.
- **Subscription management notifications.** Stripe and Play both send "subscription
  expiring" / "payment failed" notifications via webhook. The Publisher receives these but
  v1 has no surface to relay them to the user's devices in real time. v2 needs an
  `EntitlementWarning` Rei record (or equivalent) for "your subscription failed to renew, fix
  it within 7 days." Defer.
- **Promo codes / referrals.** Both Stripe and Play support promo codes. v1 doesn't expose a
  promo-code field on the picker. Adding it is straightforward (one input on §3.1, validated
  against a Publisher endpoint) but no business need yet. Defer.
- **Threat-model amendment for the subscription flow.** The Publisher's threat-model entry
  (`tenshu-publisher.md` §6) covers the publisher itself. The UX flow doesn't add new
  surfaces — but it does make the user briefly trust the Publisher's checkout URL with their
  `ownerId`. The amendment in `threat-model.md` should explicitly note the `ownerId`
  exposure to the Publisher (already implicit in `tenshu-publisher.md` but worth restating
  in the threat model).
- **Accessibility.** The product picker, the price-display detail expander, and the
  entitlement-arrival confirmation need a real WCAG AA pass. Particularly: screen-reader
  announcement of price changes when toggling monthly/annual, focus management when the
  Stripe browser tab returns deep-link control to Control.

---

## 12. Copy Guidelines

- **State the price clearly, never hide it.** Every plan card shows the price next to the
  scope. No "starting at" without an immediate concrete price.
- **No urgency pressure.** No countdown timers, no "limited time," no "X people just
  subscribed." Tenshu's positioning rules these out; the subscription flow has to match.
- **Honest cancellation.** §5.2's *"Manage subscription"* deep-link to Play / Stripe is the
  only path. No retention prompts, no "are you sure" loops past the one confirmation modal.
- **Failure copy preserves trust.** §6.4 (entitlement didn't arrive) is the highest-stakes
  copy in this doc. Lead with *"Your payment was successful"* — never with the failure.
- **Don't conflate price with value.** The picker's plan-detail expander explicitly notes
  that the free tier "is the full Tenshu, with less storage." Encryption, sync, recovery —
  none of these vary by tier.

---

## 13. Wireframe Notes

This doc is text-only. When the project gains a design tool, screens described here should be
mocked up at three breakpoints: phone-portrait (360dp), tablet (720dp), desktop (1280px+).
General layout principles:

- **Cards over tables.** Plan comparison is a card stack on the picker, never a feature
  matrix as the primary surface (the matrix is a secondary detail expander).
- **One primary action per screen.** Even §3.1, which has multiple plan cards, has one
  per-card primary CTA — never two equally weighted "buy this" buttons fighting for
  attention.
- **Progressive disclosure.** Tier comparison is collapsed by default; the user opens it if
  they want it.
- **No dark patterns.** §3.1's `[Use the free tier]` is always visually equivalent to the
  paid CTAs (same prominence, same colour weight, same vertical rhythm).
- **High-contrast price text.** Prices need to be unambiguous at a glance. WCAG AAA on the
  price-to-card-background ratio is the floor (not AA).

---

## 14. References

- [`tenshu-publisher.md`](tenshu-publisher.md) — Publisher service architecture, trust
  boundary, endpoint sketch, and the Phase 1 mock surface this doc binds to.
- [`control-onboarding-ux.md`](control-onboarding-ux.md) §4b.5 / §4b.6 — the surrounding
  onboarding screens this flow slots between.
- [`web-onboarding-ux.md`](web-onboarding-ux.md) §4b.8 — the web equivalent (currently
  punts; web's subscription UX is a follow-up doc, intentionally parallel in shape to this
  one).
- [`docs/sync/threat-model.md`](../sync/threat-model.md) — the security contract; this flow
  must not introduce surfaces that contradict the publisher boundary (`ownerId` exposure to
  Publisher is the only new surface, already noted in the publisher doc).
- [`docs/agent.md`](../agent.md) §6 (Shared App Module Conventions) — Screen / Content split
  and Compose Preview requirements that the screens above must follow.
