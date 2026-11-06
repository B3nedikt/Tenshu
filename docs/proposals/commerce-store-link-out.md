# Store builds and paid storage — sell through the store, like OneDrive

**Status:** **Decided 2026-10-23 (§0):** store builds sell through the store's own billing, Stripe
stays for the web, desktop and a non-Play APK. **P1–P3 merged** and commerce
deployed with the Google rail on (`a2676fbc`, 2026-10-23); the review's fixes are folded into
§0.4/§0.7/§0.8 and are not yet deployed, and no Play build with P3 has shipped. P4–P7 open. §§1–4 are the research that led there (2026-10-22);
several numbers are mid-litigation (§2), so re-read the sources before acting on any figure.
⚖️ Counsel owes the store terms and the cross-rail rules (§0.3).
**Owner:** Benedikt
**Extends:** [`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) §9.4c (the
steering gate) and §9.4e (iOS cannot obtain a plan), and the rails table in its §3.1. In-app purchase
itself stays Ph4b there.

## 0. Decision (2026-10-23)

**Copy OneDrive (§0.1): every store build sells inside the app through that store's billing, and
the plan follows the account everywhere.** In the maintainer's words: *"At the moment selling
anything is far more important than saving 15%, and it really feels off when you cannot upgrade
inside the app."* The companion-app route (L0 in §3) is the state until store billing ships, not a
goal; the US link-out (L1) is an optional later saving, not the plan.

- **D1 — Apple:** in-app purchase (StoreKit 2) on iOS, and on macOS once a Mac App Store build
  exists. Route 3.1.3(b): the same plans are sold in the app and on the web.
- **D2 — Google Play builds:** Play Billing. Same principle as D1.
- **D3 — Stripe stays** for the web, the non-store desktop builds, and an Android APK distributed
  outside Play, which must work without Google Play services. Which one a build uses is a
  **build-time switch** (today `inAppCheckoutEnabled`, which grows into a billing-rail choice:
  `STORE` / `STRIPE`). ⚠ A Play build must never offer Stripe: Play allows no second rail outside
  its programs (§2.1).
  The APK's build path is `scripts/android-release.sh --direct`, which sets the Stripe rail and
  refuses an APK whose generated rail is anything else (`docs/how-to/android-release.md` "Releasing
  outside Play", 2026-10-31).
- **D4 — one account, one plan, wherever it was bought.** A store purchase is verified by
  `:commerce-service` (App Store Server API / Play Developer API, plus their server notifications)
  and minted into the same signed entitlement a Stripe purchase produces, so a plan bought on an
  iPhone works on Android, desktop and the web.
- **D5 — billing is managed where it was bought.** The app says which rail bills the plan and opens
  that rail's own management page (App Store subscriptions, Play subscriptions); Stripe plans are
  managed on the web. Nobody can change a store-billed plan from our side, as with Microsoft.

### 0.1 How OneDrive does it

Read 2026-10-22/23 from Microsoft's support pages and OneDrive's App Store listing; rows marked
*(inferred)* come from general store mechanics or third-party guides, not Microsoft.

| | iOS | Android (Play) | Web / Windows |
|---|---|---|---|
| Buy | in the app, *Me* tab, Apple IAP | in the app, Play Billing | Microsoft checkout |
| Price | from $1.99/month (US listing) | — not checked | — not checked |
| Billed by | Apple | Google | Microsoft |
| Plan works on | every platform, tied to the **Microsoft account**, not the store account | the same | the same |
| Cancel | iOS Settings → Subscriptions | Play Store → Subscriptions | account.microsoft.com |
| Upgrade (Personal → Family) | cancel in Apple, wait for expiry, buy Family | through Google *(inferred)* | in the account page |
| Refund | Apple only; Microsoft cannot | Google only; Microsoft cannot | Microsoft |
| Account page shows | the plan, with management options "limited or missing" | the same | Manage, Cancel, Change payment |
| Switch rail | cancel the old, let it expire, buy on the new rail | the same | the same |
| Restore | the purchasing store account plus the matching Microsoft account *(inferred)* | the same *(inferred)* | — |

What Microsoft does *not* solve: a user can end up paying on two rails at once, and an upgrade
across rails is "cancel, wait, buy again". **We do not copy that part (D6, §0.4).**

### 0.2 What copying it means for us

| Piece | Where | Note |
|---|---|---|
| Store products for each plan (Basic, Premium) | App Store Connect, Play Console | one subscription group on Apple, so up/downgrade stays inside Apple |
| Purchase UI per rail | Control: StoreKit 2 (Swift, iOS), Play Billing Library (Android) | the plan picker's Subscribe opens the store sheet instead of the browser |
| Receipt verification + mint | `:commerce-service` | App Store Server API, Play Developer API; the result is the D4 entitlement |
| Renewal, cancel, refund, grace period | `:commerce-service` | App Store Server Notifications v2, Play Real-time developer notifications |
| Binding a purchase to our account | client + commerce | Apple `appAccountToken`, Play `obfuscatedAccountId` carry our account id, so a receipt names its owner |
| Restore | Control on each platform | re-reads the store's current entitlements and re-sends them for verification |
| "Billed by …" + manage link | Services screen | D5 |
| No VAT/invoice work for store sales | commerce | the store is merchant of record (`commerce-accounts-and-sessions.md` §3.1) |
| Ledger and reporting | commerce | store revenue arrives net, in the store's reports; keep it apart from the Stripe ledger and the OSS VAT export |

### 0.4 One plan, never paid twice (D6, D7)

**D6 (decided 2026-10-23): a user never pays twice, and upgrades on whatever device they are on.**
The entitlement syncs first, so every device knows the current plan and the rail that bills it, and
offers only plans above it, through that device's own rail (D1–D3).

Why Microsoft does not: an upgrade across rails is not an upgrade but a second subscription, and the
first one has to stop. **Whether we can stop it depends on the rail:**

| Plan billed by | Can we stop it? | Return the unused time? |
|---|---|---|
| Stripe | yes | yes: prorated credit or refund |
| Google Play | yes: the Play Developer API cancels, and revokes with a prorated refund | yes |
| **Apple** | **no**: only the user cancels, in iOS Settings | no: only Apple refunds |

Rules (**proposed**; the asymmetry above forces them):

- **Same rail:** the rail's own upgrade does it, proration included. Apple: one subscription group,
  where an upgrade takes effect at once. Play: a subscription replacement. Stripe: a plan change.
- **Off Stripe or Play to another rail:** allowed. Commerce stops the old subscription when the new
  one is verified and returns the unused time, converted at the new rail's price.
- **Off Apple:** only on an Apple device (iOS, later the Mac App Store build). Elsewhere the app says
  the plan is billed by Apple and to upgrade on an iPhone or iPad. Anything else risks a renewal we
  cannot stop.
- **Downgrade:** at the end of the current period, on the same rail.
- **Never offer a plan at or below the current one** on any device, on any rail.

**D7 (decided 2026-10-23): Rei codes follow the same rule — no code is wasted.** A Premium code over
Standard upgrades at once and keeps the unused Standard time; a code for the same plan extends the
term; a code for a lower plan waits until the current period ends. Over a *store* subscription the
code's time starts when that subscription ends, since an Apple renewal cannot be stopped; over Stripe
or Play commerce may pause or cancel the subscription instead. **Not on iOS (decided 2026-10-23):** redeeming a code in the app may
count as a "license key" under Apple 3.1.1 (§3 L0), so iOS offers no code entry for now; an iOS user
redeems on the web. Revisit with counsel.

**Where the rules live: in `:commerce-service`, not in the synced entitlement.** Commerce already
keeps the account's rail (`Rail` has `IapApple` / `IapGoogle`) and an entitlement history; it gains a
subscription record (rail, product, period end, auto-renew, pending downgrade, the store's ids) and
serves it to signed-in devices (P1, §0.6). A device must be signed in to buy anyway, because the
purchase has to name its account. So the synced, signed entitlement keeps its shape and House Rule 9
does not fire; a device without a session shows the plan and offers no purchase.

**As built: the realm-wide rule is one function, `BillingRules.realmRefusal`.** `/billing`
(what a device is offered), Google verify, the RTDN adoption of a linked token (§0.7) and Stripe
checkout all call it, so a device is never offered what the purchase then refuses. Beyond the
per-account rules above, a plan under **another** account of the realm that the purchase would leave
billing refuses with `subscribed_under_another_account` (on Stripe only a Google one: a Stripe plan
elsewhere is asked of Stripe live by checkout). When the realm leaves nothing to buy, `/billing`
says why in `refusal`. Refusal codes a device can meet: `not_an_upgrade`, `billed_by_apple`,
`subscribed_under_another_account`, `google_subscription_not_replaced` (§0.7), `account_mismatch`,
`account_closed`.

### 0.5 Answers to the open questions (2026-10-23)

| Question | Decision |
|---|---|
| Price parity | **Same price everywhere.** Store products are priced like the Stripe plans; we absorb the store's 15%. Revisit later. |
| A Stripe-billed user opens the iOS app | **Show the plan and offer the upgrade through Apple.** When the Apple purchase verifies, commerce cancels the Stripe subscription and refunds its unused time pro rata (D6 allows it: we can stop Stripe). |
| First implementation includes | **Apple Small Business Program** (enrol now), **downgrades** (end of period, same rail), **Rei codes under D7** (not on iOS). **Not** family or shared plans. |
| The interim "Get more storage" / "See plans" buttons | **Left as they are** until store billing ships: the builds are on internal testing only, and phase P3/P5 puts the real purchase behind them. |

### 0.6 Phases

Ordered so each phase is testable on its own and the first needs no store setup at all.

| Phase | What | Needs |
|---|---|---|
| **P0** (maintainer, any time before P2/P4) | Apple Small Business Program. App Store Connect: one subscription group with Basic and Premium at the Stripe prices, an App Store Server API key (`.p8`), Server Notifications v2 pointed at commerce. Play Console: Basic and Premium subscriptions at the same prices, Real-time developer notifications (a Pub/Sub topic), and *View financial data* for the service account. | Console access |
| **P1** `:commerce-service` | `BillingRules` (pure): who bills the plan, what a device's rail may buy (only above the current plan; nothing off Apple for an Apple-billed plan), and when a purchase is refused. `POST /commerce/account/billing` returns it to a signed-in device. Checkout refuses a plan at or below a redeemed one (`not_an_upgrade`) and anything over a store plan (`billed_by_apple` / `billed_by_google`), across every account of the realm. **As built (2026-10-23):** no subscription table and no Stripe plan-change API — a Stripe plan stays checked against Stripe live, as before, and Stripe's own portal does upgrades (prorated) and downgrades (at period end; a portal setting). The store rails sell nothing until P2/P4. The `BillingRail` stop/refund abstraction moves to P2, its first user. | nothing |
| **P2** `:commerce-service`, Google | `POST /commerce/iap/google/verify`: Play Developer API `subscriptionsv2.get`, check `obfuscatedAccountId` is the account, acknowledge, record, mint. RTDN push endpoint for renewals, cancellations, refunds. The Google `BillingRail` (cancel; revoke with prorated refund). Cross-rail switches both ways with Stripe. **As built (2026-10-23), see §0.7.** | P0 Google half |
| **P3** Control, Android | **As built, see §0.8.** The build-time rail switch replaces `inAppCheckoutEnabled` (`STORE` for Play builds, `STRIPE` for the non-Play APK and desktop), likely as two product flavours. Play Billing Library in the Play flavour: the plan picker's Subscribe opens Play's sheet, the token goes to P2, restore on start. Services: "Billed by …", the rail's own manage link, upgrades from `/billing`. `android-release.sh` builds the Play flavour. | P1, P2; a Play test account |
| **P4** `:commerce-service`, Apple | `POST /commerce/iap/apple/verify`: verify the StoreKit 2 signed transaction (JWS, Apple's certificate chain), check `appAccountToken` is the account id (already a UUID), record, mint. Server Notifications v2 endpoint. The Apple `BillingRail` is report-only: commerce can never stop it (D6). | P0 Apple half |
| **P5** Control, iOS | Free sign-up first (§9.4e question 1, under 3.1.3(f)). StoreKit 2 purchase in the plan picker, the transaction to P4, restore from `Transaction.currentEntitlements`, "Billed by Apple" with `showManageSubscriptions`. A Stripe-billed user upgrades through Apple (§0.5). No code entry. | P1, P4; a sandbox tester |
| **P6** Rei codes, D7 | Redemption applies the stacking rule: higher upgrades now, same extends, lower queues; over a store subscription the time starts at its end. Android, desktop, web. | P1 |
| **P7** ledger | Store sales arrive net in the stores' reports: their own ledger rows, kept out of the OSS VAT export (the store is MoR). Refund and revocation notices from P2/P4 end the entitlement. | P2, P4 |

**P0 as done (2026-10-23, through the App Store Connect API with the `ASC_KEY_ID` key):**

- Tenshu Control (iOS, app id `6822740989`): subscription group **Tenshu Storage** (`22507348`),
  `tenshu.drive.premium.monthly` (level 1, `6826258755`) and `tenshu.drive.basic.monthly`
  (level 2, `6826258487`), one month, no Family Sharing. Germany is the base price (€9.99 / €2.99,
  the Stripe prices, VAT included on both); the other 174 territories carry Apple's equalised
  points (US $8.99 / $2.99, UK £8.99 / £2.99, CH CHF 8.00 / 3.00), available in all 175 and in new
  ones. State `MISSING_METADATA` until each has its App Review screenshot, which needs P5's screen.
  The proceeds Apple reports for these points are 85%, which reads as the Small Business rate. ⚠
  Confirm the enrolment in the account rather than inferring it.
- ⚠ Product IDs on Apple are permanent, even after deletion. Commerce maps them to its own
  (`tenshu.drive.basic` / `.premium`) in P4.
- **Google (2026-10-23, Play Developer API, once the payments profile existed):** on Tenshu
  Control (`app.tenshu.control.android`), subscriptions `tenshu.drive.basic` and
  `tenshu.drive.premium`, each with an active `monthly` base plan (7-day grace period, 30-day
  account hold), in 174 regions (regions version `2026/01`). Prices: every euro region exactly
  €2.99 / €9.99; **106 regions pinned to Apple's price in that country** where both stores bill the
  same currency (US $2.99 / $8.99, UK £2.99 / £8.99, CH CHF 3.00 / 8.00), so a plan costs the same in
  both stores there; the rest use Play's conversion of the German net price.
  ⚠ Play's `convertRegionPrices` treats its input as **excluding VAT**: converting €2.99 gives
  Germany €3.59. Convert the net price and pin the VAT-inclusive one instead.
  ⚠ The scripts that did this were one-off (scratch); P2 should land a repeatable version.
- Still the maintainer's, because no API covers them: the App Store
  **In-App Purchase key** for the App Store Server API (*Users and Access → Integrations*), the
  Paid Apps agreement and a sandbox tester. The notification URLs follow with P2/P4, once their
  endpoints exist.

P1 and P6 need no store setup at all and start now. P2–P3 follow once P0's Google half exists; P4–P5
once its Apple half does.

### 0.7 P2 as built (2026-10-23)

- **`POST /commerce/iap/google/verify`** `{sessionToken, purchaseToken}`. Commerce reads the purchase
  from Play (`subscriptionsv2.get`) and refuses it unless it names this account
  (`obfuscatedAccountId`, which `/billing` now returns as `accountId`), is a known product and is
  paid. Then the D6 rules apply across the realm (`BillingRules.realmRefusal`, §0.4). It books an
  `IapGoogle` grant to the period's expiry (once per period), records the purchase token on the
  account, acknowledges, and stops a Stripe plan on the same account. ⚠⚠ **A refused purchase is
  never acknowledged**, and Play refunds an unacknowledged purchase after three days: that is the
  backstop for a purchase that should not have been offered. Every step is idempotent, so any 5xx is
  safe for the device to retry.
  ⚠⚠ **Never a second Google subscription:** a new token that does not link back
  (`linkedPurchaseToken`) to the one the account holds is refused with
  `google_subscription_not_replaced` while the held one still entitles **or is on hold or paused**.
  Play may recover or resume those and bill again, and its `queryPurchases` does not return them, so
  the app bought without replacing.
- **`POST /commerce/iap/google/rtdn?token=…`**, the Pub/Sub push target. Commerce re-reads the
  purchase and reconciles on the re-read state alone: a renewal books the new period, a refund or a
  lapse ends the Google grants now. A voided-purchase notice for one refunded renewal of a
  subscription that stays active ends nothing. ⚠ Only the account's **current** purchase token may
  change its grants: an upgrade inside Play replaces the token, and the old one then reports expired.
  ⚠⚠ **A token whose `linkedPurchaseToken` is the current one is adopted** (Google's guidance): it
  becomes the stored token, is booked, and is acknowledged here. But a linked token is also what a
  re-signup in Play's own subscription centre produces, without our app. So adoption first applies
  the realm rule; a refusal leaves the token **unacknowledged** (Play refunds it), exactly as verify
  would. On a closed account a linked token is cancelled, never adopted.
- **Stripe → Google:** `refundUnusedAndCancel` refunds the unpaid-for rest of the current period pro
  rata, then cancels. ⚠⚠ **"Already refunded" is proved by Stripe's refund list, not by an
  idempotency key.** Stripe replays a failed first attempt under its key and prunes keys after 24 h,
  so before refunding a payment commerce lists its refunds. A `rail_switch` refund for the
  subscription (succeeded or pending) is done, and its amount counts. A failed or cancelled one
  does not count, and an idempotency error means "retry later". The amount comes from the
  subscription's paid invoices, line by line (each line by how much of its own period is left, scaled to
  `amount_paid`), so a portal upgrade's proration invoice counts too. It is capped by what is still
  unrefunded on the payment. An invoice with no Stripe payment (paid out of band, or by credit)
  refunds 0 and does not fail the switch. The refund's `charge.refunded` writes the credit note as
  for any refund. ⚠ A pending switch refund that later fails is not retried by anything; Stripe
  reports it as `refund.failed`, handled by hand.
- **Google → Stripe:** the Stripe webhook revokes the Google purchase with a prorated refund
  **before** booking anything, because its invoice write is not idempotent; a failure asks Stripe to
  redeliver. ⚠ It asks **Play** for the token's state, not our ledger: in account hold the
  notifications have ended our grants, yet Play may still recover and renew. It revokes unless the
  subscription is expired or revoked. A closed account is skipped (its grant is refused anyway).
- **Closing an account stops Google too** (`SubscriptionCanceller`, with its retry sweep, as for
  Stripe): `subscriptionsv2.cancel` with `DEVELOPER_REQUESTED_STOP_PAYMENTS`, which the user cannot
  undo and which refunds nothing, like closure on Stripe (policy §5.2). A user-cancelled
  subscription is cancelled again for that reason. If Play refuses, the cancelled state is accepted,
  as is an unpaid PENDING signup, rather than failing every sweep. No Google grant is ever booked
  for a closed account, and an RTDN for one cancels again.
- `EntitlementSource.Iap` split into `IapApple` / `IapGoogle`: who bills a grant must not follow the
  account's rail, which changes when the account buys elsewhere.
- **To turn it on:**
  - ✅ (2026-10-23) Pub/Sub in project `tenshu`: topic `projects/tenshu/topics/play-rtdn`, *Pub/Sub
    Publisher* for `google-play-developer-notifications@system.gserviceaccount.com` on it, and push
    subscription `play-rtdn-commerce` to
    `https://commerce.service.tenshu.app/commerce/iap/google/rtdn?token=<secret>` (7-day retention,
    10–600 s backoff, never expires). The secret is the Keychain item `google-play-rtdn-token`. Done
    by the `play-upload` service account, which holds *Pub/Sub Admin* on the project for this.
    ⚠ Until commerce is deployed with P2 the pushes get 404 and are retried, then dropped after 7 days.
  - Set `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (the `play-service-account` key, base64 in `deploy.env`)
    and `GOOGLE_PLAY_RTDN_TOKEN` (`google-play-rtdn-token`) in the commerce deployment, which now
    carries both (`deploy/scaleway-commerce`). Commerce refuses to start with the key and no secret.
    ⚠ The box's compose file must be copied by hand, as for the relay: it does not `git pull`.
  - ✅ (2026-10-23) Production commerce runs `a2676fbc` with both values set (staged `.next` files,
    dry-run, `/opt/tenshu/switch-commerce-a2676fbc.sh`; rollback script beside it). ⚠ The box cannot
    pull from GHCR (private image, no login): the image was pushed to GHCR, then loaded with
    `docker save | ssh docker load`.
  - ✅ (2026-10-23) The topic is named under Play Console → Monetization setup → Real-time developer
    notifications (no API for it).
- Not covered: the Postgres store's new methods have no test against a real database, like the rest
  of that store. Ledger rows for store revenue are P7.
- **Logs:** the RTDN secret rides in the push URL's query. Caddy's access log drops query strings
  (`commerce-service/Caddyfile`), and commerce logs request paths only. ⚠ The box's Caddyfile
  is copied by hand, like its compose file.

### 0.8 P3 as built (2026-10-23)

- **The rail is a build fact:** `-Ptenshu.android.billingRail=play|stripe` (default `play`)
  generates `ANDROID_BILLING_RAIL` in `:control-shared`, like `:ui-core`'s store links. No product
  flavours, so every existing task name (`:control:bundleRelease`, the smoke gate) is unchanged.
  `inAppCheckoutEnabled` is now true exactly on the Stripe rail; the plan picker shows paid plans
  on every build, because every build sells.
- **`StoreBilling`** (`control-shared-logic`), bound only in a Play build (`androidBillingModule`).
  ⚠⚠ **The rail decides what is compiled, not only what is bound:** `PlayStoreBilling` and its Koin
  module live in `control-shared/src/billingPlay`, and the Stripe rail compiles
  `src/billingStripe`'s empty module instead, without the `play-billing-ktx` dependency. So a
  non-Play APK has no `com.android.vending.BILLING` permission, no billing `<queries>` and no proxy
  activity (checked on built APKs). `scripts/android-smoke.sh` compiles the Stripe rail too, so a
  Play-only reference cannot break it silently.
- `PlayStoreBilling` (Play Billing Library 9.1.0) opens the sheet for the `monthly` base plan
  (never a promotional offer) with `obfuscatedAccountId` = the account id from `/billing`. ⚠ A plan
  Play already bills for us is **replaced** (`CHARGE_PRORATED_PRICE`), never bought beside it. If
  what is owned cannot be read, the purchase **fails** rather than buying blind. A plan the Google
  account pays for **another** Tenshu account is refused, neither replaced nor bought beside. A
  purchase naming no account (not made by our app, e.g. a Play promo code) is no Tenshu account's
  (commerce never binds it), so it is replaced.
- ⚠⚠ **The purchase runs in `CheckoutViewModel`'s scope, never in the screen.** The screen hands
  over its Activity once (`launchStorePurchase(host)`, a take-once `StorePurchaseHost`). A purchase
  run in the screen's effect was cancelled by the recomposition that consuming the one-shot causes,
  and by a rotation. `CheckoutStorePurchaseScreenTest` pins this.

- **Checkout on a store rail:** no waiver step (the store is the seller, with its own terms). The
  VM asks `/billing` whether the plan is offered here, the screen shows Play's sheet, and the token
  goes to commerce. An unconfirmed purchase is retried by **re-verifying the same token**, never by
  opening the sheet again. *Done* still comes only from the synced plan.
- **Restore:** opening Services re-verifies every purchase Play holds. ⚠ This is what saves a
  purchase interrupted before verification: Play refunds it after three days unacknowledged.
- **Not yet:** a "Billed by Google Play" line and Play's own manage link on the Services screen;
  the release script does not pass the rail yet (the default, `play`, is what Play gets); a real
  test purchase on a device (needs the P2 deployment and a license tester).

### 0.3 Open, before building

1. ⚖️ The store developer agreements; price parity is settled as *same price* (§0.5).
2. ~~A Stripe user on iOS~~ → upgrade through Apple, Stripe cancelled and refunded (§0.5).
3. ~~Two rails at once~~ → settled by D6 (§0.4).
4. Family or shared plans: out of the first implementation (§0.5); if added, they follow our
   account, not Apple Family Sharing.
5. ~~Small Business Program~~ → enrol now (§0.5, P0).

## 1. The question

Paid storage is sold on the web through Stripe, with us as merchant of record. The store builds
(Play, App Store) show the plan the synced entitlement grants but sell nothing. The question was
whether to link from the apps to the web checkout *"like Spotify"*: does that work in most regions,
and does it save money against the stores' own billing?

**Short answer: no to "most regions", and the saving is small everywhere except Apple's US
storefront.** Spotify's own setup is not one rule either: on iOS it is a *reader app* (§2.3), which
storage is not, plus the US link-out; on Android it uses Google's alternative billing. What carries
over to us is a different, older exemption that names cloud storage outright (§2.3), and it is the
model we already run.

## 2. What the stores allow (2026-10-22)

### 2.1 Fees for a subscription

| Route | Apple | Google Play |
|---|---|---|
| Store billing (IAP / Play Billing) | 15% in the Small Business Program and after a subscriber's first year; 30% otherwise | 15% on subscriptions (EEA: 10% service + 5% billing fee) |
| Link-out, **US** | **0% today**; Apple asked the court on 2026-08 for 15% (5% for small businesses), contested by Epic, Supreme Court review pending | External content links program: service fee reported and paid from 2026-10-01 (first deadline 2026-12-01); **10% for subscriptions on new installs, 20% on existing**, as far as Google's table reads; enrolment, Google's link APIs and an information screen before the link |
| Link-out, **EEA** | StoreKit External Purchase Link Entitlement: **15%, 10%** in the Small Business Program and after year one, on sales within **7 days** of the tap (terms effective 2026-10-01; no separate Core Technology Commission listed for it) | External offers program: **10%** for subscriptions, €1.20 per install only when the link leads to an app download |
| Link-out, **Japan, Brazil** | The same entitlement and 15% / 10% | not researched |
| Link-out, **everywhere else** | **Prohibited** (3.1.1(a): no buttons, links or calls to action to other purchase methods outside the US storefront) | **Prohibited** outside the programs; help pages and account pages are fine as long as they never lead to a payment |

Add Stripe's ~1.5–3% and, for our own checkout, the VAT and invoicing work we carry as merchant of
record ([`commerce-accounts-and-sessions.md`](commerce-accounts-and-sessions.md) §5).

### 2.2 What that means in money

- **Apple, US:** the only route with a clear saving: 0% (or 5% if the court grants Apple's small
  business rate) plus Stripe, against 15% IAP.
- **Apple, EEA/JP/BR:** 10% + Stripe ≈ 12–13% against 15% IAP for a small business. About two
  points, for an entitlement, a separate link flow and the MoR burden.
- **Google, US and EEA:** 10–20% + Stripe against 15%. Break-even at best, worse for existing
  installs in the US.

The point of linking out is therefore not the fee. It is that **one checkout, one invoice and one
subscription** work across web, desktop and phones, which store billing cannot give us (a store
subscription lives in that store and has to be bridged into the entitlement, Ph4b).

### 2.3 The exemption that fits us

Apple **3.1.3(f) Free Stand-alone Apps**, verbatim: *"Free apps acting as a stand-alone companion to
a paid web-based tool for VoIP, **Cloud Storage**, Email Services, or Web Hosting do not need to use
in-app purchase, provided there is no purchasing inside the app, or calls to action for purchase
outside of the app."*

That is our model by name: Control and the clients are free, the paid plan is sold on the web, and
the app only reflects it. Its condition is strict, though: **no calls to action for purchase outside
the app** (outside the US storefront). Two other exemptions do *not* fit:

- **3.1.3(a) Reader apps** (Spotify's route: account creation and an account-management link) is
  limited to magazines, newspapers, books, audio, music and video.
- **3.1.3(b) Multiplatform services** lets an app unlock what was bought on the web only if the same
  thing is **also sold as IAP** in the app.

Google's equivalent is the **consumption-only** rule: any app may let a user sign in and use what
they paid for elsewhere, as long as nothing in the app leads to a non-Play payment.

### 2.4 What the incumbents do

Every large storage service **sells inside its iOS app through Apple's in-app purchase**, beside
its web checkout, which is route 3.1.3(b). None runs as a 3.1.3(f) companion app:

- **OneDrive:** Microsoft 365 plans in the app's *Me* tab through Apple, from $1.99 a month in the
  US (its App Store listing, read 2026-10-22).
- **Dropbox:** Plus and the larger plans through Apple in the iOS app, through Play Billing on
  Android. ⚠ From memory, not re-checked against the store listings on 2026-10-22.
- **Google One / Drive on iOS:** storage through Apple, although Google sells the same plans on
  the web and through Play. ⚠ The same caveat.

They pay 15–30% because an in-app *Buy* converts far better than an app that may not say where to
buy; some price the in-app plan higher to recover the fee. So the three routes Apple leaves a
storage app are:

| Route | What the app may do | Store's cut |
|---|---|---|
| 3.1.3(f) companion (L0 below) | use a plan bought on the web; never mention buying | 0%, but a phone-only user has to find the website alone |
| US storefront link-out (L1) | link to our checkout, US storefront only | 0% today, under litigation |
| 3.1.3(b) web **and** IAP (Ph4b) | sell in the app, honour web purchases too | 15% in the Small Business Program |

**Conclusion:** L0 is the cheapest start, and it costs sales. If paid plans are meant to sell on
phones, the realistic end state is what the incumbents run, **store billing at 15% (Ph4b) plus the
US link-out where it is cheaper**, not link-out everywhere. Ph4b is the expensive part: a store
receipt has to be verified and turned into our synced entitlement, and a store subscription lives
in that store (`commerce-accounts-and-sessions.md` §3.1, IAP row).

## 3. The options considered (2026-10-22)

The ladder below was the proposal before §0. L0 now describes the state until store billing ships;
L1 and L2 remain options; Ph4b is what §0 decides to build.

### L0 — companion app everywhere (now; no counsel needed beyond the policies' own text)

The default in every region and both stores: no purchase, no link to a purchase, no call to action
to buy elsewhere. Free sign-up and sign-in stay, because creating a free account is not a purchase.

- Android: the Subscribe CTA is hidden (§9.4c), and so is *Manage subscription*.
- **The remaining wording is the gap.** These strings are calls to action in a store build, or
  promise something the build cannot do:

  | String | Where | Problem |
  |---|---|---|
  | `services_get_more_storage` "Get more storage" | Control, Services | a CTA; in a store build it opens a picker with Free and "Have a code" only |
  | `storage_gate_full_cta` "Get more storage" | Drive, storage-full gate | the same |
  | `home_quota_soft_limit_see_plans` "See plans" | Control, quota warning | the same |
  | `services_synced_manage_hint` "…change your plan, manage billing, or renew" | Control, Services | promises billing actions a store build no longer offers |

  Proposed: in store builds, say what the user has and nothing about buying (*"Your plan: Free, 5 GB.
  Plans are managed in your Tenshu account."*), with no button. Keyed on the existing
  `inAppCheckoutEnabled`, which may want a clearer name (`storeBuild`) once it gates text too.
- ⚖️ **"Have a code"** is the one open question in L0. Apple 3.1.1 bans *"license keys"* and similar
  mechanisms to unlock functionality; a code that grants a plan bought elsewhere is arguably that,
  while 3.1.3(f) exempts the app from IAP altogether. Google has no such clause. Decide before iOS
  ships the picker (below).
- **iOS (§9.4e question 1): build free sign-up under L0.** It is allowed under 3.1.3(f), and it
  closes the dead end where an iOS user can neither get Free nor see where plans come from.

### L1 — link-out on the US storefronts (when the fee settles; ⚖️ counsel)

Both stores allow a link to our checkout for US users today. Apple's fee is the one worth having
while it stays at or near 0–5%; Google's 10–20% is no better than Play Billing, so its value there
is only the single-subscription argument from §2.2.

- **Key on the storefront, not the user's address or location.** It is what both policies key on:
  Apple's `Storefront.current?.countryCode` (StoreKit 2), Google's billing country (Play Billing
  `getBillingConfigAsync`), which the external content links APIs check themselves. That settles
  the trap §9.4e recorded (storefront vs billing address vs location): the link follows the
  storefront, the invoice keeps following the billing address as today (§5), and the two may differ.
- Google needs enrolment, its link APIs and the information screen; Apple's US storefront needs no
  entitlement but forbids misleading wording.
- Revisit when the US cases settle: Apple's remand rate and the Supreme Court review, and Google's
  modified injunction (its current terms run to 2027-11-01).

### L2 — Apple's link entitlement in the EEA, Japan and Brazil (probably not)

About two points cheaper than IAP for a small business (§2.2), at the cost of an entitlement per
region, a seven-day attribution window to report, and the MoR work. Only worth it if L1 shows that
a link-out converts better than "go to the website", or if Ph4b's IAP turns out to be needed anyway.

### Ph4b — store billing (decided in §0)

Selling *inside* the app (IAP / Play Billing) stays the separate Ph4b track. It is the only way to
offer a purchase button outside the US storefront. Nothing above depends on it, and L0 makes it
optional rather than required: under 3.1.3(f) and consumption-only, a store build need not sell at
all.

## 4. Open questions

1. ⚖️ "Have a code" under Apple 3.1.1 (L0).
2. Whether the store-build wording (L0) should also drop the plan *names*, which some reviewers read
   as advertising. 3.1.3(f) does not forbid showing the plan; it forbids the call to action.
3. Desktop is unaffected (no store), but a Mac App Store build
   ([`desktop-macos-distribution.md`](desktop-macos-distribution.md)) falls under the same Apple
   guidelines as iOS; L0 applies to it the day it ships.

## Sources (read 2026-10-22)

- Apple App Review Guidelines, 3.1.1, 3.1.1(a), 3.1.3(a), 3.1.3(b), 3.1.3(f):
  <https://developer.apple.com/app-store/review/guidelines/>
- Apple, EU alternative terms (link-out 15% / 10%, 7 days, effective 2026-10-01):
  <https://developer.apple.com/support/apps-using-alternative-payment-providers-in-the-eu/>
- Apple's proposed US link-out commission (2026-08):
  <https://techcrunch.com/2026/08/14/apple-proposes-to-take-a-15-cut-of-purchases-made-outside-the-app-store/>;
  Ninth Circuit:
  <https://www.fenwick.com/insights/publications/ninth-circuit-largely-upholds-ruling-in-epic-v-apple>
- Google, US external content links program:
  <https://support.google.com/googleplay/android-developer/answer/16470497>
- Google, EEA conditions and fees:
  <https://support.google.com/googleplay/android-developer/answer/14659200>
- Google Payments policy (consumption-only, what Play Billing covers):
  <https://support.google.com/googleplay/android-developer/answer/10281818>
